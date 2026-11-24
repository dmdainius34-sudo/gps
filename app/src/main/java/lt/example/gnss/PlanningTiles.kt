package lt.example.gnss

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln

/**
 * Vilniaus teritorijų planavimas (gis.vplanas.lt, ArcGIS MapServer „Interaktyvus_zemelapis/Teritoriju_planavimas“, © gis.vplanas.lt):
 * permatomos PNG plytelės Web Mercator tinkleliu (z/x/y), užklausa „export“ su plytelės ribomis (EPSG:3857, 256×256).
 * Kaupiamos prietaiso atmintyje (filesDir/vplanas/…); piešiamos sluoksniu virš gaublio (PlanOverlayView), todėl priartinti galima iki pat smulkmenų.
 */
object PlanningTiles {
    const val ATTRIBUTION = "Teritorijų planavimas – © gis.vplanas.lt (Vilniaus m. savivaldybė)"
    private const val BASE = "https://gis.vplanas.lt/arcgis/rest/services/Interaktyvus_zemelapis/Teritoriju_planavimas/MapServer"
    private const val LAYERS = "show:0,1,2,3,4,5,6,9,15,7,8,10,11,12,13,14,17,18,19,20,16,127,21,22,23,24,25,26,121,122,123,124,125,126,114,115,116,117,118,119,90,80,81,82,83,84,85,86,87,88,89,91,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63,64,65,66,67,68,69,70,71,72,73,74,75,76,79,77,78,93,94,95,96,97,98,112,113,99,100,101,102,103,104,105,106,107,108,109,110,111"
    const val MAX_Z = 21
    private const val HALF = 20037508.342789244

    @Volatile var enabled = false
    @Volatile var dir: File? = null
    @Volatile var status = ""
    /** Šiame kadre rodomos plytelės ir jų kampai ekrane (px, 4 taškai). */
    @Volatile var shown: List<Pair<GlobeTiles.Key, FloatArray>> = emptyList()

    private val mem = object : LinkedHashMap<GlobeTiles.Key, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<GlobeTiles.Key, Bitmap>?) = size > 90
    }
    private val pending = ConcurrentHashMap.newKeySet<GlobeTiles.Key>()
    /** Plytelės be duomenų (tuščios / klaida) – nebeprašoma iki paleidimo iš naujo. */
    private val empty = ConcurrentHashMap.newKeySet<GlobeTiles.Key>()
    private val pool = Executors.newFixedThreadPool(4)

    /** Lygis pagal metrus ekrano pikseliui (256 px plytelė). */
    fun zoomFor(mpp: Double, latDeg: Double): Int {
        if (!(mpp > 0)) return 12
        return floor(ln(156_543.03 * cos(Math.toRadians(latDeg)) / mpp) / ln(2.0) + 0.3).toInt().coerceIn(12, MAX_Z)
    }

    fun url(k: GlobeTiles.Key): String {
        val n = (1 shl k.z).toDouble(); val s = 2 * HALF / n
        val x0 = -HALF + k.x * s; val x1 = x0 + s; val y1 = HALF - k.y * s; val y0 = y1 - s
        return "$BASE/export?f=image&bbox=%.3f,%.3f,%.3f,%.3f&imageSR=102100&bboxSR=102100&layers=%s&size=256,256&transparent=true&format=png32".format(Locale.US, x0, y0, x1, y1, LAYERS)
    }

    @Synchronized fun get(k: GlobeTiles.Key): Bitmap? = mem[k]

    /** Paprašyti plytelės (fone): iš atminties arba iš serverio. */
    fun request(k: GlobeTiles.Key) {
        if (!enabled || k in empty || get(k) != null || !pending.add(k)) return
        pool.execute {
            try {
                val f = dir?.let { File(it, "${k.z}/${k.x}/${k.y}.png") }
                var bytes: ByteArray? = if (f != null && f.exists()) f.readBytes() else null
                if (bytes == null) {
                    val c = URL(url(k)).openConnection() as HttpURLConnection
                    c.connectTimeout = 15_000; c.readTimeout = 30_000
                    c.setRequestProperty("User-Agent", "GnssApp/1.0 (Android)"); c.setRequestProperty("Referer", BASE)
                    try {
                        if (c.responseCode == 200 && (c.contentType ?: "").startsWith("image")) bytes = c.inputStream.use { it.readBytes() }
                        else status = "serveris: HTTP ${c.responseCode}"
                    } finally { c.disconnect() }
                    if (bytes != null && f != null) try { f.parentFile?.mkdirs(); f.writeBytes(bytes) } catch (_: Exception) {}
                }
                val b = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                if (b == null) empty.add(k) else { synchronized(this) { mem[k] = b }; status = "gauta" }
            } catch (e: Exception) { status = "klaida: ${e.message}"; empty.add(k) }
            finally { pending.remove(k) }
        }
    }

    /** Plytelės kampai (lat, lon): kairė viršus, dešinė viršus, dešinė apačia, kairė apačia. */
    fun corners(k: GlobeTiles.Key): Array<DoubleArray> {
        val la0 = GlobeTiles.latOf(k.y.toDouble(), k.z); val la1 = GlobeTiles.latOf(k.y + 1.0, k.z)
        val lo0 = GlobeTiles.lonOf(k.x.toDouble(), k.z); val lo1 = GlobeTiles.lonOf(k.x + 1.0, k.z)
        return arrayOf(doubleArrayOf(la0, lo0), doubleArrayOf(la0, lo1), doubleArrayOf(la1, lo1), doubleArrayOf(la1, lo0))
    }
}
