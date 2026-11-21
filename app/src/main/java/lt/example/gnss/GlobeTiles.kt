package lt.example.gnss

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Palydovinės nuotraukos plytelės Žemės gaubliui: priartinus (žiupsneliu) gaublys pagal atstumą iki paviršiaus parenka
 * plytelių lygį (Web Mercator z), atsisiunčia matomas plyteles ir kaupia jas prietaiso atmintyje (filesDir/tiles/…) –
 * antrą kartą toje vietoje rodomos iš atminties, ir be interneto.
 *
 * Šaltinis – EOX „Sentinel-2 cloudless“ (Copernicus Sentinel-2, 10 m pikselis, be debesų; licencija CC BY-NC-SA 4.0 –
 * nekomercinis naudojimas su autorystės nuoroda, ji rodoma ekrane, kai plytelės matomos). Didžiausias lygis 14 (~6 m/pikselis
 * Lietuvoje) – tikslesnio vaizdo šis šaltinis neturi.
 */
object GlobeTiles {
    const val MAX_Z = 14
    const val MIN_Z = 3
    const val ATTRIBUTION = "Sentinel-2 cloudless – s2maps.eu, EOX IT Services GmbH (Copernicus Sentinel duomenys)"
    private val LAYERS = listOf("s2cloudless-2023_3857", "s2cloudless-2021_3857", "s2cloudless-2020_3857")

    /** Plytelė: lygis z, stulpelis x (nuo −180°), eilutė y (nuo šiaurės). */
    data class Key(val z: Int, val x: Int, val y: Int) {
        override fun toString() = "$z/$x/$y"
    }

    // ---- Web Mercator ----
    fun lonOf(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0
    fun latOf(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))
    /** (x, y) plytelių vienetais (trupmeninis). */
    fun xyOf(latDeg: Double, lonDeg: Double, z: Int): DoubleArray {
        val n = (1 shl z).toDouble()
        val la = Math.toRadians(latDeg.coerceIn(-85.05, 85.05))
        return doubleArrayOf((lonDeg + 180.0) / 360.0 * n, (1 - ln(tan(la) + 1 / cos(la)) / PI) / 2 * n)
    }

    /**
     * Lygis pagal ekrano raišką: mpp – kiek metrų ant žemės tenka vienam ekrano pikseliui. Parenkamas lygis, kurio plytelės
     * pikselis ne stambesnis už ekrano (256 px plytelė, pusiaujyje z=0 – 156 543 m/pikselis).
     */
    fun zoomFor(mpp: Double, latDeg: Double): Int {
        if (!(mpp > 0)) return MIN_Z
        val z = ln(156_543.03 * cos(Math.toRadians(latDeg)) / mpp) / ln(2.0)
        return floor(z + 0.3).toInt().coerceIn(MIN_Z, MAX_Z)
    }

    /** Plytelės aplink tašką: (2r+1)² tinklelis, artimiausios centrui – pirmos. */
    fun around(latDeg: Double, lonDeg: Double, z: Int, r: Int): List<Key> {
        val xy = xyOf(latDeg, lonDeg, z); val n = 1 shl z
        val cx = floor(xy[0]).toInt(); val cy = floor(xy[1]).toInt()
        val out = ArrayList<Key>()
        for (dy in -r..r) for (dx in -r..r) {
            val y = cy + dy; if (y < 0 || y >= n) continue
            out.add(Key(z, ((cx + dx) % n + n) % n, y))
        }
        out.sortBy { (it.x + 0.5 - xy[0]).let { a -> a * a } + (it.y + 0.5 - xy[1]).let { b -> b * b } }
        return out
    }

    // ---- įkėlimas ir kaupimas ----
    @Volatile private var dir: File? = null
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "plyteles").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val pending = ConcurrentHashMap.newKeySet<Key>()
    private val failed = ConcurrentHashMap<Key, Long>()
    /** Paruoštos (atsisiųstos ar iš atminties) plytelės – JPEG baitai; GL gija jas iškoduoja ir įkelia. */
    val ready = ConcurrentLinkedQueue<Pair<Key, ByteArray>>()
    /** Plytelės, kurių dabar reikia vaizdui – seni užklausimai praleidžiami. */
    @Volatile var wanted: Set<Key> = emptySet()
    @Volatile private var layer = 0
    @Volatile var downloaded = 0; private set
    @Volatile var fromDisk = 0; private set
    @Volatile var lastError = ""; private set
    /** Kada paskutinį kartą plytelės buvo matomos (autorystės užrašui ekrane). */
    @Volatile var shownMs = 0L

    fun init(filesDir: File) { if (dir == null) dir = File(filesDir, "tiles/s2") }

    private fun file(k: Key): File? = dir?.let { File(it, "${k.z}/${k.x}/${k.y}.jpg") }

    /** Užklausti plytelę (jei dar nėra GL atmintyje). Grąžina iš karto; rezultatas – eilėje [ready]. */
    fun request(k: Key) {
        if (!pending.add(k)) return
        failed[k]?.let { if (System.currentTimeMillis() - it < 60_000L) { pending.remove(k); return } }
        pool.execute {
            try {
                if (k !in wanted) return@execute
                val f = file(k)
                if (f != null && f.isFile && f.length() > 0) { ready.add(k to f.readBytes()); fromDisk++; return@execute }
                val b = fetch(k) ?: run { failed[k] = System.currentTimeMillis(); return@execute }
                if (f != null) try { f.parentFile?.mkdirs(); val tmp = File(f.path + ".tmp"); tmp.writeBytes(b); tmp.renameTo(f) } catch (_: Exception) {}
                ready.add(k to b); downloaded++
            } catch (e: Exception) {
                failed[k] = System.currentTimeMillis(); lastError = "${e.javaClass.simpleName} ${e.message?.take(80) ?: ""}"
            } finally { pending.remove(k) }
        }
    }

    private fun fetch(k: Key): ByteArray? {
        for (i in layer until LAYERS.size) {
            val url = "https://tiles.maps.eox.at/wmts/1.0.0/${LAYERS[i]}/default/g/${k.z}/${k.y}/${k.x}.jpg"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "GnssApp/1.0 (Android)")
            try {
                when (c.responseCode) {
                    200 -> { layer = i; return c.inputStream.use { it.readBytes() } }
                    404 -> if (i == layer && k.z <= 6) continue else return null   // sluoksnio nėra – bandomas senesnis (tik mažame lygyje)
                    else -> { lastError = "HTTP ${c.responseCode}"; return null }
                }
            } finally { c.disconnect() }
        }
        return null
    }

    /** Užkaupta atmintyje (MB). */
    fun diskMb(): Double = dir?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() }?.div(1e6) ?: 0.0

    fun text(): String = "Palydovinės plytelės: atsisiųsta %d, iš atminties %d, sukaupta %.1f MB%s".format(Locale.US,
        downloaded, fromDisk, diskMb(), if (lastError.isNotEmpty()) "; paskutinė klaida: $lastError" else "")
}
