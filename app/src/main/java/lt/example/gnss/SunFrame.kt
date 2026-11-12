package lt.example.gnss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
            Locale.US, p, b, l, carrington(ms)) + SunImage.status
    }
}

/**
 * Tikras Saulės vaizdas: NASA SDO HMI intensitygrama (matomos dėmės), disko vaizdas perskaičiuojamas į heliografinį
 * (ilgumos × platumos) žemėlapį; nematoma pusė – iš pagrindinės tekstūros. Atnaujinama kas 30 min.
 */
object SunImage {
    const val URL_HMI = "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_1024_HMIIC.jpg"
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
    @Volatile private var at = 0L
    @Volatile private var busy = false

    fun refresh(ctx: Context) {
        val now = System.currentTimeMillis()
        if (!enabled || busy || now - at < (if (map == null) 2 * 60_000L else 30 * 60_000L)) return
        busy = true; at = now
        Thread {
            try {
                status = "tikras Saulės vaizdas: siunčiama…"
                val c = (URL(URL_HMI).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Android) GnssApp")
                }
                if (c.responseCode != 200) { status = "tikras Saulės vaizdas: HTTP ${c.responseCode}"; return@Thread }
                val mod = c.lastModified.takeIf { it > 0 } ?: now
                val disk = c.inputStream.use { BitmapFactory.decodeStream(it) } ?: run { status = "tikras Saulės vaizdas: neperskaitomas"; return@Thread }
                val base = try { ctx.assets.open("sun.jpg").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
                val m = build(disk, base, mod) ?: run { status = "tikras Saulės vaizdas: disko nerasta"; return@Thread }
                map = m; version++; imageMs = mod
                status = "tikras Saulės vaizdas: NASA SDO HMI, %s UTC".format(Locale.US,
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(mod))) + "; $lastDisk"
            } catch (e: Exception) { status = "tikras Saulės vaizdas: ${e.javaClass.simpleName}" }
            finally { busy = false }
        }.start()
    }

    /** Disko radimas (šviesūs pikseliai) ir perskaičiavimas į 1024×512 heliografinį žemėlapį. */
    fun build(disk: Bitmap, base: Bitmap?, ms: Long): Bitmap? {
        val w = disk.width; val h = disk.height
        val px = IntArray(w * h); disk.getPixels(px, 0, w, 0, 0, w, h)
        // disko kraštai – per centrinę eilutę ir stulpelį (kampuose esantys užrašai netrukdo)
        fun lum(x: Int, y: Int): Int { val p = px[y * w + x]; return ((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3 }
        val thr = 25
        var x0 = -1; var x1 = -1; var y0 = -1; var y1 = -1
        for (x in 0 until w) if (lum(x, h / 2) > thr) { if (x0 < 0) x0 = x; x1 = x }
        for (y in 0 until h) if (lum(w / 2, y) > thr) { if (y0 < 0) y0 = y; y1 = y }
        if (x0 < 0 || y0 < 0 || x1 <= x0 || y1 <= y0) return null
        val cx = (x0 + x1) / 2.0; val cy = (y0 + y1) / 2.0; val rad = ((x1 - x0) + (y1 - y0)) / 4.0
        lastDisk = "diskas: centras %.0f, %.0f; spindulys %.0f px iš %d×%d".format(Locale.US, cx, cy, rad, w, h)
        if (rad < w * 0.2) return null
        val (ax, ay, az) = SunFrame.axes(ms).let { Triple(it[0], it[1], it[2]) }
        val v = Astro.sunEq(ms); val s = DoubleArray(3) { -v[it] }
        val zs = az[0] * s[0] + az[1] * s[1] + az[2] * s[2]
        val up = DoubleArray(3) { az[it] - zs * s[it] }.let { u -> val l = sqrt(u[0] * u[0] + u[1] * u[1] + u[2] * u[2]); DoubleArray(3) { u[it] / l } }
        val right = SunFrame.cross(v, up)                                // žiūrint iš Žemės: dešinė – vakarai
        val mw = 1024; val mh = 512
        val out = IntArray(mw * mh)
        val bw = base?.width ?: 0; val bh = base?.height ?: 0
        val bpx = if (base != null) IntArray(bw * bh).also { base.getPixels(it, 0, bw, 0, 0, bw, bh) } else null
        for (r in 0 until mh) {
            val lat = Math.toRadians(90.0 - 180.0 * (r + 0.5) / mh)
            for (cc in 0 until mw) {
                val lon = Math.toRadians(-180.0 + 360.0 * (cc + 0.5) / mw)
                val b = DoubleArray(3) { cos(lat) * cos(lon) * ax[it] + cos(lat) * sin(lon) * ay[it] + sin(lat) * az[it] }
                val mu = b[0] * s[0] + b[1] * s[1] + b[2] * s[2]
                val basePix = if (bpx != null) bpx[(r * bh / mh) * bw + (cc * bw / mw)] else 0xFFFF9020.toInt()
                if (mu <= 0.08) { out[r * mw + cc] = basePix; continue }
                val ix = (cx + rad * (b[0] * right[0] + b[1] * right[1] + b[2] * right[2])).toInt()
                val iy = (cy - rad * (b[0] * up[0] + b[1] * up[1] + b[2] * up[2])).toInt()
                if (ix !in 0 until w || iy !in 0 until h) { out[r * mw + cc] = basePix; continue }
                val p = px[iy * w + ix]
                // HMI intensitygrama – pilka/oranžinė; nuspalvinama Saulės tonu, kraštas suliejamas su pagrindu
                val g = (p shr 8 and 255) / 255.0
                val sr = (255 * minOf(1.0, g * 1.15)).toInt(); val sg = (255 * minOf(1.0, g * 0.85)).toInt(); val sb = (255 * minOf(1.0, g * 0.35)).toInt()
                val k = ((mu - 0.08) / 0.12).coerceIn(0.0, 1.0)
                fun mix(a: Int, bb: Int) = (a * k + bb * (1 - k)).toInt()
                out[r * mw + cc] = (0xFF shl 24) or (mix(sr, basePix shr 16 and 255) shl 16) or (mix(sg, basePix shr 8 and 255) shl 8) or mix(sb, basePix and 255)
            }
        }
        return Bitmap.createBitmap(out, mw, mh, Bitmap.Config.ARGB_8888)
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
    fun subEarth(ms: Long, moonDir: DoubleArray): DoubleArray {
        val (x, y, z) = axes(ms)
        val s = DoubleArray(3) { -moonDir[it] }
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        return doubleArrayOf(Math.toDegrees(atan2(dot(s, y), dot(s, x))), Math.toDegrees(asin(dot(s, z).coerceIn(-1.0, 1.0))))
    }
}
