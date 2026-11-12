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
    @Volatile private var at = 0L
    @Volatile private var busy = false

    fun refresh(ctx: Context) {
        val now = System.currentTimeMillis()
        if (!enabled || busy || now - at < 30 * 60_000L) return
        busy = true; at = now
        Thread {
            try {
                val c = (URL(URL_HMI).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000 }
                if (c.responseCode != 200) { status = "tikras Saulės vaizdas: HTTP ${c.responseCode}"; return@Thread }
                val mod = c.lastModified.takeIf { it > 0 } ?: now
                val disk = c.inputStream.use { BitmapFactory.decodeStream(it) } ?: run { status = "tikras Saulės vaizdas: neperskaitomas"; return@Thread }
                val base = try { ctx.assets.open("sun.jpg").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
                val m = build(disk, base, mod) ?: run { status = "tikras Saulės vaizdas: disko nerasta"; return@Thread }
                map = m; version++
                status = "tikras Saulės vaizdas: NASA SDO HMI, %s UTC".format(Locale.US,
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(mod)))
            } catch (e: Exception) { status = "tikras Saulės vaizdas: ${e.javaClass.simpleName}" }
            finally { busy = false }
        }.start()
    }

    /** Disko radimas (šviesūs pikseliai) ir perskaičiavimas į 1024×512 heliografinį žemėlapį. */
    fun build(disk: Bitmap, base: Bitmap?, ms: Long): Bitmap? {
        val w = disk.width; val h = disk.height
        val px = IntArray(w * h); disk.getPixels(px, 0, w, 0, 0, w, h)
        var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
        for (y in 0 until h step 2) for (x in 0 until w step 2) {
            val p = px[y * w + x]; val lum = ((p shr 16 and 255) + (p shr 8 and 255) + (p and 255)) / 3
            if (lum > 40) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y }
        }
        if (x1 <= x0 || y1 <= y0) return null
        val cx = (x0 + x1) / 2.0; val cy = (y0 + y1) / 2.0; val rad = ((x1 - x0) + (y1 - y0)) / 4.0
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
