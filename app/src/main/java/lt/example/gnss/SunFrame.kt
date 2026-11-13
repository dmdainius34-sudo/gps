package lt.example.gnss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Saulės heliografinės koordinatės (IAU sukimosi modelis): ašies polius α0 = 286,13°, δ0 = 63,87° (J2000),
 * nulinio dienovidinio kampas W = 84,176° + 14,1844°·d (siderinis apsisukimas 25,38 paros; Carringtono ilgumos).
 * Ašys – pusiaujo (sideralinėje) sistemoje, kurioje piešiama scena. P, B0, L0 – kaip astronomų efemeridėse.
 */
object SunFrame {
    private const val A0 = 286.13; private const val D0 = 63.87

    /** (X – nulinis dienovidinis, Y, Z – šiaurės polius), vienetiniai vektoriai. */
    fun axes(ms: Long): Array<DoubleArray> {
        val a = Math.toRadians(A0); val d = Math.toRadians(D0)
        val z = doubleArrayOf(cos(d) * cos(a), cos(d) * sin(a), sin(d))
        val q = doubleArrayOf(-sin(a), cos(a), 0.0)                       // pusiaujo mazgas (α0 + 90°)
        val zq = cross(z, q)
        val w = Math.toRadians(((84.176 + 14.1844000 * Astro.daysJ2000(ms)) % 360.0 + 360.0) % 360.0)
        val x = DoubleArray(3) { q[it] * cos(w) + zq[it] * sin(w) }
        return arrayOf(x, cross(z, x), z)
    }

    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun norm(a: DoubleArray): DoubleArray { val l = sqrt(dot(a, a)); return DoubleArray(3) { a[it] / l } }

    /** P (ašies pozicijos kampas nuo dangaus šiaurės į rytus), B0, L0 – laipsniais. */
    fun pbl(ms: Long): DoubleArray {
        val (x, y, z) = axes(ms)
        val v = Astro.sunEq(ms)                                          // Žemė -> Saulė
        val s = DoubleArray(3) { -v[it] }                                // Saulė -> Žemė
        val b0 = Math.toDegrees(asin(dot(s, z).coerceIn(-1.0, 1.0)))
        val l0 = (Math.toDegrees(atan2(dot(s, y), dot(s, x))) + 360.0) % 360.0
        val n = norm(DoubleArray(3) { (if (it == 2) 1.0 else 0.0) - v[2] * v[it] })
        val e = cross(n, v)
        val p = Math.toDegrees(atan2(dot(z, e), dot(z, n)))
        return doubleArrayOf(p, b0, l0)
    }

    fun carrington(ms: Long): Double = 1690.0 + (Astro.daysJ2000(ms) + 2451545.0 - 2444235.34) / 27.2753

    fun text(ms: Long = System.currentTimeMillis()): String {
        val (p, b, l) = pbl(ms).let { Triple(it[0], it[1], it[2]) }
        return "Saulės koordinatės: P = %+.2f°, B₀ = %+.2f°, L₀ = %.2f° (į Žemę atsuktas dienovidinis), Carringtono apsisukimas %.2f; ".format(
            Locale.US, p, b, l, carrington(ms)) + SunImage.status + "; " + Lasco.status + "; " + Donki.text() + (if (GlCaps.err.isNotEmpty()) "; " + GlCaps.err else "")
    }
}

/**
 * Tikras Saulės vaizdas: NASA SDO HMI intensitygrama (2048 px, matomos dėmės). Kiekviena nuotrauka perskaičiuojama į
 * heliografinį (Carringtono ilgumos × platumos) 2048×1024 žemėlapį ir KAUPIAMA: atnaujinama tik į Žemę atsukta dalis,
 * kitos lieka iš ankstesnių nuotraukų (Saulė apsisuka per ~27 paras – po ~2 savaičių padengtas visas rutulys).
 * Krašto šešėliavimas išlyginamas (I/I(μ)), kontrastas ištempiamas. Nepadengtos vietos – vienoda fotosferos spalva.
 */
object SunImage {
    private const val URL_BASE = "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_%d_HMIIC.jpg"
    /** Didžiausia raiška pagal įrenginio grafiką: 4096 px nuotrauka ir 4096×2048 žemėlapis, jei leidžia tekstūros dydis. */
    private val big get() = GlCaps.maxTex >= 8192
    val urlHmi get() = URL_BASE.format(if (big) 4096 else 2048)
    const val MAP_FILE = "sun_synoptic.png"
    const val COV_FILE = "sun_synoptic_cov.txt"
    val MW get() = if (big) 4096 else 2048
    val MH get() = MW / 2
    @Volatile var map: Bitmap? = null
        private set
    @Volatile var version = 0
        private set
    @Volatile var status = "tikras Saulės vaizdas: neatsisiųstas"
        private set
    @Volatile var enabled = true
    @Volatile var lastDisk = ""
    @Volatile var imageMs = 0L
        private set
    /** Kiekvieno 1° ilgumos ruožo paskutinio atnaujinimo laikas (ms), 0 – nepadengta. */
    private val cov = LongArray(360)
    @Volatile private var at = 0L
    @Volatile private var busy = false
    private var loaded = false

    fun coverage(): Int = cov.count { it > 0 } * 100 / 360

    private fun loadStored(ctx: Context) {
        if (loaded) return; loaded = true
        try {
            val d = ctx.applicationContext.filesDir
            File(d, COV_FILE).takeIf { it.exists() }?.readLines()?.forEachIndexed { i, l -> if (i < 360) cov[i] = l.trim().toLongOrNull() ?: 0L }
            File(d, MAP_FILE).takeIf { it.exists() }?.let { f -> BitmapFactory.decodeFile(f.path)?.let { map = it.copy(Bitmap.Config.ARGB_8888, true); version++ } }
        } catch (_: Exception) {}
    }

    fun refresh(ctx: Context) {
        loadStored(ctx)
        val now = System.currentTimeMillis()
        if (!enabled || busy || now - at < (if (imageMs == 0L) 2 * 60_000L else 30 * 60_000L)) return
        busy = true; at = now
        Thread {
            try {
                status = "tikras Saulės vaizdas: siunčiama…"
                if (GlCaps.maxTex == 0) { status = "tikras Saulės vaizdas: laukiama grafikos"; return@Thread }
                val c = (URL(urlHmi).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 90_000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) GnssApp")
                }
                if (c.responseCode != 200) { status = "tikras Saulės vaizdas: HTTP ${c.responseCode}"; return@Thread }
                val mod = c.lastModified.takeIf { it > 0 } ?: now
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }   // 4096² – mažiau atminties
                val disk = c.inputStream.use { BitmapFactory.decodeStream(it, null, opts) } ?: run { status = "tikras Saulės vaizdas: neperskaitomas"; return@Thread }
                val m = build(disk, map, mod) ?: run { status = "tikras Saulės vaizdas: disko nerasta"; return@Thread }
                val dw = disk.width; disk.recycle()
                map = m; version++; imageMs = mod
                try {
                    val d = ctx.applicationContext.filesDir
                    File(d, MAP_FILE).outputStream().use { m.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    File(d, COV_FILE).writeText(cov.joinToString("\n"))
                } catch (_: Exception) {}
                status = "tikras Saulės vaizdas: NASA SDO HMI ${dw} px, žemėlapis ${MW}×${MH}, %s UTC; padengta %d %% paviršiaus".format(Locale.US,
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(mod)), coverage()) +
                    oldest(now) + "; $lastDisk"
            } catch (e: Exception) { status = "tikras Saulės vaizdas: ${e.javaClass.simpleName} ${e.message ?: ""}".trim() }
            finally { busy = false }
        }.start()
    }

    private fun oldest(now: Long): String {
        val t = cov.filter { it > 0 }.minOrNull() ?: return ""
        return "; seniausi duomenys prieš %.1f d.".format(Locale.US, (now - t) / 86_400_000.0)
    }

    /** Disko radimas, krašto šešėliavimo išlyginimas ir įrašymas į kaupiamą heliografinį žemėlapį. */
    fun build(disk: Bitmap, prev: Bitmap?, ms: Long): Bitmap? {
        val w = disk.width; val h = disk.height
        val lumA = ByteArray(w * h)
        val row = IntArray(w)
        for (y in 0 until h) { disk.getPixels(row, 0, w, 0, y, w, 1); for (x in 0 until w) { val p = row[x]; lumA[y * w + x] = (((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3).toByte() } }
        fun lum(x: Int, y: Int): Int = lumA[y * w + x].toInt() and 255
        val thr = 25
        var x0 = -1; var x1 = -1; var y0 = -1; var y1 = -1
        for (x in 0 until w) if (lum(x, h / 2) > thr) { if (x0 < 0) x0 = x; x1 = x }
        for (y in 0 until h) if (lum(w / 2, y) > thr) { if (y0 < 0) y0 = y; y1 = y }
        if (x0 < 0 || y0 < 0 || x1 <= x0 || y1 <= y0) return null
        val cx = (x0 + x1) / 2.0; val cy = (y0 + y1) / 2.0; val rad = ((x1 - x0) + (y1 - y0)) / 4.0
        lastDisk = "diskas: centras %.0f, %.0f; spindulys %.0f px iš %d×%d".format(Locale.US, cx, cy, rad, w, h)
        if (rad < w * 0.2) return null
        // fotosferos lygis (disko centras) – normavimui
        var sum = 0.0; var n = 0
        for (dy in -20..20 step 4) for (dx in -20..20 step 4) { val x = (cx + dx * rad / 100).toInt(); val y = (cy + dy * rad / 100).toInt(); if (x in 0 until w && y in 0 until h) { sum += lum(x, y); n++ } }
        val i0 = (sum / maxOf(1, n)).coerceAtLeast(30.0)
        val (ax, ay, az) = SunFrame.axes(ms).let { Triple(it[0], it[1], it[2]) }
        val v = Astro.sunEq(ms); val s = DoubleArray(3) { -v[it] }
        val zs = az[0] * s[0] + az[1] * s[1] + az[2] * s[2]
        val up = DoubleArray(3) { az[it] - zs * s[it] }.let { u -> val l = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]); DoubleArray(3) { u[it] / l } }
        val right = SunFrame.cross(v, up)
        val MW = this.MW; val MH = this.MH
        val out = IntArray(MW * MH)
        if (prev != null && prev.width == MW && prev.height == MH) prev.getPixels(out, 0, MW, 0, 0, MW, MH)
        else if (prev != null) Bitmap.createScaledBitmap(prev, MW, MH, true).getPixels(out, 0, MW, 0, 0, MW, MH)
        else java.util.Arrays.fill(out, 0)                              // 0 – nepadengta (šešėliuoklė rodo fotosferos spalvą)
        val now = ms
        for (r in 0 until MH) {
            val lat = Math.toRadians(90.0 - 180.0 * (r + 0.5) / MH)
            val cl = cos(lat); val sl = sin(lat)
            for (cc in 0 until MW) {
                val lon = Math.toRadians(-180.0 + 360.0 * (cc + 0.5) / MW)
                val clo = cos(lon); val slo = sin(lon)
                val b0 = cl * clo * ax[0] + cl * slo * ay[0] + sl * az[0]
                val b1 = cl * clo * ax[1] + cl * slo * ay[1] + sl * az[1]
                val b2 = cl * clo * ax[2] + cl * slo * ay[2] + sl * az[2]
                val mu = b0 * s[0] + b1 * s[1] + b2 * s[2]
                if (mu <= 0.15) continue
                val ix = (cx + rad * (b0 * right[0] + b1 * right[1] + b2 * right[2])).toInt()
                val iy = (cy - rad * (b0 * up[0] + b1 * up[1] + b2 * up[2])).toInt()
                if (ix !in 0 until w || iy !in 0 until h) continue
                // krašto šešėliavimo išlyginimas (I(μ) = 1 − 0,6·(1 − μ)) ir kontrasto ištempimas
                val ld = 1 - 0.6 * (1 - mu)
                val rel = (lum(ix, iy) / (i0 * ld)).coerceIn(0.0, 1.3)
                val g = (0.5 + (rel - 1.0) * 2.2).coerceIn(0.0, 1.0)     // dėmės – tamsios, fotosfera – šviesi
                val red = (255 * (0.35 + 0.65 * g)).toInt().coerceIn(0, 255); val grn = (255 * (0.16 + 0.62 * g)).toInt().coerceIn(0, 255); val blu = (255 * (0.02 + 0.22 * g * g)).toInt().coerceIn(0, 255)
                val newPx = (0xFF shl 24) or (red shl 16) or (grn shl 8) or blu
                val old = out[r * MW + cc]
                val k = ((mu - 0.15) / 0.25).coerceIn(0.0, 1.0)            // kraštuose – sulieti su ankstesniais duomenimis
                out[r * MW + cc] = if (old == 0 || k >= 1.0) newPx else {
                    fun ch(a: Int, b: Int, sh: Int) = (((a shr sh) and 255) * k + ((b shr sh) and 255) * (1 - k)).toInt()
                    (0xFF shl 24) or (ch(newPx, old, 16) shl 16) or (ch(newPx, old, 8) shl 8) or ch(newPx, old, 0)
                }
                if (mu > 0.5) cov[(Math.toDegrees(lon) + 180).toInt().coerceIn(0, 359)] = now
            }
        }
        return Bitmap.createBitmap(out, MW, MH, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Mėnulio selenografinės koordinatės (IAU 2009 modelis, vidutinės Žemės / poliaus ašių sistema – ta pati kaip LRO žemėlapio):
 * polius α0, δ0 ir nulinio dienovidinio kampas W su pagrindiniais periodiniais nariais (libracija ~ ±7°).
 */
object MoonFrame {
    fun axes(ms: Long): Array<DoubleArray> {
        val d = Astro.daysJ2000(ms); val t = d / 36525.0
        fun e(a: Double, b: Double) = Math.toRadians(a + b * d)
        val e1 = e(125.045, -0.0529921); val e2 = e(250.089, -0.1059842); val e3 = e(260.008, 13.0120009); val e4 = e(176.625, 13.3407154)
        val e5 = e(357.529, 0.9856003); val e6 = e(311.589, 26.4057084); val e7 = e(134.963, 13.0649930); val e8 = e(276.617, 0.3287146)
        val e9 = e(34.226, 1.7484877); val e10 = e(15.134, -0.1589763); val e11 = e(119.743, 0.0036096); val e12 = e(239.961, 0.1643573); val e13 = e(25.053, 12.9590088)
        val a0 = 269.9949 + 0.0031 * t - 3.8787 * sin(e1) - 0.1204 * sin(e2) + 0.0700 * sin(e3) - 0.0172 * sin(e4) + 0.0072 * sin(e6) - 0.0052 * sin(e10) + 0.0043 * sin(e13)
        val d0 = 66.5392 + 0.0130 * t + 1.5419 * cos(e1) + 0.0239 * cos(e2) - 0.0278 * cos(e3) + 0.0068 * cos(e4) - 0.0029 * cos(e6) + 0.0009 * cos(e7) + 0.0008 * cos(e10) - 0.0009 * cos(e13)
        val w = 38.3213 + 13.17635815 * d - 1.4e-12 * d * d + 3.5610 * sin(e1) + 0.1208 * sin(e2) - 0.0642 * sin(e3) + 0.0158 * sin(e4) + 0.0252 * sin(e5) -
            0.0066 * sin(e6) - 0.0047 * sin(e7) - 0.0046 * sin(e8) + 0.0028 * sin(e9) + 0.0052 * sin(e10) + 0.0040 * sin(e11) + 0.0019 * sin(e12) - 0.0044 * sin(e13)
        val a = Math.toRadians(a0); val dd = Math.toRadians(d0); val ww = Math.toRadians(((w % 360) + 360) % 360)
        val z = doubleArrayOf(cos(dd) * cos(a), cos(dd) * sin(a), sin(dd))
        val q = doubleArrayOf(-sin(a), cos(a), 0.0)
        val zq = SunFrame.cross(z, q)
        val x = DoubleArray(3) { q[it] * cos(ww) + zq[it] * sin(ww) }
        return arrayOf(x, SunFrame.cross(z, x), z)
    }

    /** Į Žemę atsuktas taškas (selenografinė ilguma, platuma – optinė libracija), °; moonDir – kryptis Žemė -> Mėnulis. */
    /** Taškas Mėnulyje, kuriame Saulė zenite: (ilguma, platuma) °. Kryptys geocentrinės, atstumai km. */
    fun subSolar(ms: Long, moonDir: DoubleArray, moonKm: Double, sunDir: DoubleArray, sunKm: Double): DoubleArray {
        val (x, y, z) = axes(ms)
        val v = DoubleArray(3) { sunDir[it] * sunKm - moonDir[it] * moonKm }
        val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); val s = DoubleArray(3) { v[it] / l }
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        return doubleArrayOf(Math.toDegrees(atan2(dot(s, y), dot(s, x))), Math.toDegrees(asin(dot(s, z).coerceIn(-1.0, 1.0))))
    }

    fun subEarth(ms: Long, moonDir: DoubleArray): DoubleArray {
        val (x, y, z) = axes(ms)
        val s = DoubleArray(3) { -moonDir[it] }
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        return doubleArrayOf(Math.toDegrees(atan2(dot(s, y), dot(s, x))), Math.toDegrees(asin(dot(s, z).coerceIn(-1.0, 1.0))))
    }
}

/** Įrenginio grafikos galimybės (nustatomos GL konteksto sukūrimo metu). */
object GlCaps { @Volatile var maxTex = 0; @Volatile var err = "" }

/**
 * Saulės išmetimai (vainikinės masės išmetimai, CME): SOHO LASCO C2 koronografo naujausias vaizdas (NASA/ESA, kas ~12–20 min.).
 * Vaizdas – dangaus plokštumos projekcija, matoma iš Žemės: šiaurė viršuje, rytai kairėje, laukas ≈ ±6 Saulės spindulių.
 */
object Lasco {
    const val URL_C2 = "https://soho.nascom.nasa.gov/data/realtime/c2/1024/latest.jpg"
    private const val DIR = "https://soho.nascom.nasa.gov/data/REPROCESSING/Completed/%s/c2/%s/"
    /** Pusė vaizdo pločio Saulės spinduliais (C2: 11,9″/px 1024 px vaizde, R☉ ≈ 960″); 512 px kadrams – tas pats laukas. */
    const val HALF_RSUN = 1024 * 11.9 / 2 / 960.0
    const val MAX_FRAMES = 12
    /** Animacijos kadrai (seniausias pirmas) ir jų laikai. */
    @Volatile var frames: List<Bitmap> = emptyList()
        private set
    @Volatile var frameMs: List<Long> = emptyList()
        private set
    val image: Bitmap? get() = frames.lastOrNull()
    /** Kiekvieno kadro (fonas, ryškiausi 0,5 %) skaisčio lygiai 0..1 koronografo lauke 2,5–5,5 R – normalizavimui. */
    @Volatile var stats: List<FloatArray> = emptyList()
        private set

    /** Fono (20-as procentilis) ir šviesiausių (99,5) skaistis žiede 2,5–5,5 Saulės spindulio. */
    fun levels(b: Bitmap): FloatArray {
        val w = b.width; val h = b.height; val cx = w / 2.0; val cy = h / 2.0
        val pxPerR = w / 2.0 / HALF_RSUN
        val hist = IntArray(256); var n = 0
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val r = kotlin.math.hypot(x - cx, y - cy) / pxPerR
                if (r in 2.5..5.5) { val p = row[x]; hist[((p shr 16 and 255) * 5 + (p shr 8 and 255) * 4 + (p and 255)) / 10]++; n++ }
                x += 2
            }
            y += 2
        }
        if (n < 100) return floatArrayOf(0.1f, 1f)
        fun pct(q: Double): Float { var c = 0; val lim = (n * q).toInt(); for (i in 0..255) { c += hist[i]; if (c >= lim) return i / 255f }; return 1f }
        val bg = pct(0.20); val hi = pct(0.995)
        return floatArrayOf(bg, maxOf(hi, bg + 0.05f))
    }
    @Volatile var version = 0
        private set
    @Volatile var imageMs = 0L
        private set
    @Volatile var status = "Saulės išmetimai (LASCO C2): neatsisiųsta"
        private set
    @Volatile var enabled = true
    @Volatile private var at = 0L
    @Volatile private var busy = false
    private val cache = LinkedHashMap<String, Bitmap>()

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Android) GnssApp")
    }

    /** Kadrų vardai iš SOHO katalogo puslapio (pvz. 20261112_2112_c2_512.jpg). */
    fun names(html: String): List<String> {
        val r512 = Regex("""\d{8}_\d{4}_c2_512\.jpg""").findAll(html).map { it.value }.toSortedSet()
        return (if (r512.isNotEmpty()) r512 else Regex("""\d{8}_\d{4}_c2_1024\.jpg""").findAll(html).map { it.value }.toSortedSet()).toList()
    }

    fun nameMs(n: String): Long = try {
        java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(n.substring(0, 13))!!.time
    } catch (_: Exception) { 0L }

    fun refresh(ctx: Context) {
        val now = System.currentTimeMillis()
        if (!enabled || busy || now - at < (if (frames.isEmpty()) 2 * 60_000L else 15 * 60_000L)) return
        busy = true; at = now
        Thread {
            try {
                // 1) paskutinių ~8 val. kadrai iš SOHO katalogo (šiandien ir vakar, UTC)
                val fd = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                val all = ArrayList<Pair<String, String>>()
                var dirErr = ""
                for (d in listOf(now - 86_400_000L, now)) {
                    val day = fd.format(java.util.Date(d)); val base = DIR.format(Locale.US, day.substring(0, 4), day)
                    try { val c = get(base)
                        if (c.responseCode == 200) names(c.inputStream.bufferedReader().readText()).forEach { all.add(it to base + it) }
                        else dirErr = " katalogas HTTP ${c.responseCode}"
                    } catch (e: Exception) { dirErr = " katalogas ${e.javaClass.simpleName}" }
                }
                val pick = all.filter { now - nameMs(it.first) < 8 * 3_600_000L }.takeLast(MAX_FRAMES - 1)
                val out = ArrayList<Pair<Long, Bitmap>>()
                for ((n, url) in pick) {
                    val bmp = synchronized(cache) { cache[n] } ?: try {
                        val c = get(url); if (c.responseCode == 200) c.inputStream.use { BitmapFactory.decodeStream(it) } else null
                    } catch (_: Exception) { null }
                    if (bmp != null) { synchronized(cache) { cache[n] = bmp }; out.add(nameMs(n) to bmp) }
                }
                // 2) naujausias realaus laiko kadras
                val c = get(URL_C2)
                if (c.responseCode == 200) {
                    val mod = c.lastModified.takeIf { it > 0 } ?: now
                    c.inputStream.use { BitmapFactory.decodeStream(it) }?.let { b -> if (out.none { kotlin.math.abs(it.first - mod) < 5 * 60_000L }) out.add(mod to b) }
                }
                if (out.isEmpty()) { status = "Saulės išmetimai (LASCO C2): nepavyko –$dirErr, realaus laiko HTTP ${c.responseCode}"; return@Thread }
                out.sortBy { it.first }
                synchronized(cache) { val keep = pick.map { it.first }.toSet(); cache.keys.retainAll(keep) }
                stats = out.map { levels(it.second) }
                frames = out.map { it.second }; frameMs = out.map { it.first }; imageMs = out.last().first; version++
                val f = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                status = "Saulės išmetimai: SOHO LASCO C2, animacija %d kadr. %s–%s UTC%s".format(Locale.US, out.size,
                    f.format(java.util.Date(out.first().first)), f.format(java.util.Date(out.last().first)), if (out.size == 1) " (tik naujausias:$dirErr)" else "")
            } catch (e: Exception) { status = "Saulės išmetimai (LASCO C2): ${e.javaClass.simpleName} ${e.message ?: ""}".trim() }
            finally { busy = false }
        }.start()
    }
}

/**
 * NASA CCMC DONKI: išmetimų (CME) 3D analizė – heliografinė platuma, Stonyhursto ilguma (0° – į Žemę, + vakarai),
 * kūgio pusė kampo, greitis ir laikas, kai priekis pasiekė 21,5 Saulės spindulio.
 */
object Donki {
    const val AU_RSUN = 215.03
    private const val RSUN_KM = 696_000.0
    class Cme(val t215Ms: Long, val lat: Double, val lon: Double, val halfAngle: Double, val speed: Double, val type: String, val id: String)
    @Volatile var list: List<Cme> = emptyList()
        private set
    @Volatile var status = "NASA DONKI: neatsisiųsta"
        private set
    @Volatile private var at = 0L
    @Volatile private var busy = false
    @Volatile private var ok = false
    @Volatile private var loaded = false
    @Volatile var savedMs = 0L
        private set
    private const val FILE = "donki_cme.json"

    fun parse(json: String): List<Cme> {
        val a = org.json.JSONArray(json); val out = ArrayList<Cme>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            if (o.isNull("time21_5") || o.isNull("latitude") || o.isNull("longitude") || o.isNull("speed")) continue
            val t = try { java.time.OffsetDateTime.parse(o.getString("time21_5")).toInstant().toEpochMilli() } catch (_: Exception) { continue }
            out.add(Cme(t, o.getDouble("latitude"), o.getDouble("longitude"), if (o.isNull("halfAngle")) 30.0 else o.getDouble("halfAngle"),
                o.getDouble("speed"), o.optString("type", ""), o.optString("associatedCMEID", "")))
        }
        // vienam išmetimui – naujausia analizė
        return out.groupBy { it.id.ifEmpty { it.t215Ms.toString() } }.values.map { it.last() }.sortedBy { it.t215Ms }
    }

    fun refresh(ctx: Context) {
        val now = System.currentTimeMillis()
        // išsaugotas sąrašas – kūgiai matomi iškart po paleidimo ir be interneto
        if (!loaded) { loaded = true
            try { val fl = java.io.File(ctx.applicationContext.filesDir, FILE)
                if (fl.exists()) { list = parse(fl.readText()); savedMs = fl.lastModified(); status = "NASA DONKI: išsaugotas sąrašas" } } catch (_: Exception) {} }
        // pavykus – kas 2 val. (NASA DEMO_KEY: ≤ 50 užklausų per parą), nepavykus – po 20 min.
        if (!Lasco.enabled || busy || now - at < (if (ok) 2 * 3_600_000L else 20 * 60_000L)) return
        busy = true; at = now
        Thread {
            try {
                val f = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                val q = "CMEAnalysis?startDate=" + f.format(java.util.Date(now - 8 * 86_400_000L)) +
                    "&endDate=" + f.format(java.util.Date(now + 86_400_000L)) + "&mostAccurateOnly=true&speed=0&halfAngle=0&catalog=ALL"
                // pirmiausia CCMC serveris, nepavykus – NASA API tarpininkas (DEMO_KEY)
                var txt: String? = null; var err = ""
                for (url in listOf("https://kauai.ccmc.gsfc.nasa.gov/DONKI/WS/get/$q", "https://api.nasa.gov/DONKI/$q&api_key=DEMO_KEY")) {
                    try {
                        val c = (URL(url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 30_000; readTimeout = 90_000; setRequestProperty("User-Agent", "Mozilla/5.0 (Android) GnssApp") }
                        if (c.responseCode == 200) { txt = c.inputStream.bufferedReader().readText().trim(); status = "NASA DONKI (${URL(url).host}): atnaujinta"; break }
                        err += " ${URL(url).host} HTTP ${c.responseCode}" + if (c.responseCode == 429) " (paros užklausų riba)" else ""
                    } catch (e: Exception) { err += " ${URL(url).host} ${e.javaClass.simpleName}" }
                }
                if (txt == null) { ok = false
                    status = "NASA DONKI: nepavyko –$err" + if (list.isNotEmpty()) "; rodomas išsaugotas sąrašas" else ""; return@Thread }
                list = if (txt.isEmpty()) emptyList() else parse(txt)
                ok = true
                try { java.io.File(ctx.applicationContext.filesDir, FILE).writeText(if (txt.isEmpty()) "[]" else txt); savedMs = now } catch (_: Exception) {}
            } catch (e: Exception) { status = "NASA DONKI: ${e.javaClass.simpleName} ${e.message ?: ""}".trim() }
            finally { busy = false }
        }.start()
    }

    /** Priekio atstumas Saulės spinduliais (pastovus greitis nuo 21,5 R). */
    fun frontRsun(c: Cme, ms: Long): Double = 21.5 + c.speed * (ms - c.t215Ms) / 1000.0 / RSUN_KM

    /** Rodomi išmetimai: jau pasirodę ir dar nenuskridę toliau nei 1,3 AU. */
    fun active(ms: Long): List<Cme> = list.filter { ms >= c0(it) && frontRsun(it, ms) < 1.3 * AU_RSUN }
    private fun c0(c: Cme) = c.t215Ms - ((21.5 - 1.0) * RSUN_KM / c.speed.coerceAtLeast(50.0) * 1000).toLong()

    /** Kryptis n ir dvi statmenos ašys; e – Saulė→Žemė, z – Saulės šiaurės ašis (scenos koordinatėmis). */
    fun frame(c: Cme, e: DoubleArray, z: DoubleArray): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val ez = e[0] * z[0] + e[1] * z[1] + e[2] * z[2]
        var x = DoubleArray(3) { e[it] - ez * z[it] }; val xl = sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]); x = DoubleArray(3) { x[it] / xl }
        val y = SunFrame.cross(z, x)
        val la = Math.toRadians(c.lat); val lo = Math.toRadians(c.lon)
        val n = DoubleArray(3) { cos(la) * cos(lo) * x[it] + cos(la) * sin(lo) * y[it] + sin(la) * z[it] }
        val ref = if (kotlin.math.abs(n[0] * z[0] + n[1] * z[1] + n[2] * z[2]) < 0.9) z else x
        var a = SunFrame.cross(n, ref); val al = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); a = DoubleArray(3) { a[it] / al }
        return Triple(n, a, SunFrame.cross(n, a))
    }

    fun hitsEarth(c: Cme, e: DoubleArray, z: DoubleArray): Boolean {
        val n = frame(c, e, z).first
        val ang = Math.toDegrees(kotlin.math.acos((n[0] * e[0] + n[1] * e[1] + n[2] * e[2]).coerceIn(-1.0, 1.0)))
        return ang <= c.halfAngle
    }

    /** Atskridimas prie Žemės (1 AU) pastoviu greičiu, ms. */
    fun arrivalMs(c: Cme): Long = c.t215Ms + ((AU_RSUN - 21.5) * RSUN_KM / c.speed.coerceAtLeast(50.0) * 1000).toLong()

    fun color(v: Double): FloatArray = when {
        v < 500 -> floatArrayOf(1f, 0.92f, 0.35f); v < 1000 -> floatArrayOf(1f, 0.6f, 0.15f); else -> floatArrayOf(1f, 0.25f, 0.2f)
    }

    fun text(ms: Long = System.currentTimeMillis()): String {
        val act = active(ms); if (act.isEmpty()) return "$status; aktyvių išmetimų nėra"
        val e = doubleArrayOf(1.0, 0.0, 0.0); val z = doubleArrayOf(0.0, 0.0, 1.0)     // Stonyhursto sistema
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return "$status; išmetimai: " + act.joinToString(", ") { c ->
            "%s UTC %.0f km/s, plat. %+.0f° ilg. %+.0f°, pločio ±%.0f°".format(Locale.US, fmt.format(java.util.Date(c.t215Ms)), c.speed, c.lat, c.lon, c.halfAngle) +
                (if (hitsEarth(c, e, z)) " → į Žemę, atskris ~" + fmt.format(java.util.Date(arrivalMs(c))) + " UTC" else "")
        }
    }
}
