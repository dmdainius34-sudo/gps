package lt.example.gnss

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pasaulinis UV indekso tinklelis (Open-Meteo, 10°): UVI ir giedro dangaus UVI kas valandą.
 * Giedro dangaus UVI ≈ k·cos(zenito kampas)^1,2 – dėmės centras yra subsolarinis taškas. Jis lyginamas su programos
 * Saulės modeliu (Astro.sunEq + Žemės sukimasis): nepriklausoma Saulės krypties ir Žemės pasisukimo patikra.
 */
object Uvi {
    const val FILE = "uvi_grid.json"
    val lats = (-80..80 step 10).map { it.toDouble() }
    val lons = (-180..170 step 10).map { it.toDouble() }
    private class Pt(val lat: Double, val lon: Double, val uvi: DoubleArray, val clear: DoubleArray)
    @Volatile private var times: LongArray = LongArray(0)
    @Volatile private var pts: List<Pt> = emptyList()
    @Volatile var status = "neatsisiųsta"
        private set
    @Volatile private var loadedAt = 0L
    @Volatile private var busy = false

    fun refresh(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (busy || (!force && now - loadedAt < 3_600_000L && pts.isNotEmpty())) return
        busy = true
        Thread {
            try {
                val f = File(ctx.applicationContext.filesDir, FILE)
                if (pts.isEmpty() && f.exists()) parse(f.readText())
                val all = ArrayList<String>()
                val grid = lats.flatMap { la -> lons.map { lo -> la to lo } }
                for (chunk in grid.chunked(160)) {
                    val url = "https://api.open-meteo.com/v1/forecast?latitude=" + chunk.joinToString(",") { "%.1f".format(Locale.US, it.first) } +
                        "&longitude=" + chunk.joinToString(",") { "%.1f".format(Locale.US, it.second) } +
                        "&hourly=uv_index,uv_index_clear_sky&forecast_days=2&timezone=GMT"
                    val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 60_000 }
                    if (c.responseCode != 200) { status = "nepavyko: HTTP ${c.responseCode}"; return@Thread }
                    val t = c.inputStream.bufferedReader().readText().trim()
                    all.add(if (t.startsWith("[")) t.substring(1, t.length - 1) else t)
                }
                val json = "[" + all.joinToString(",") + "]"
                parse(json); f.writeText(json)
                status = "atnaujinta"
            } catch (e: Exception) { status = "klaida: ${e.javaClass.simpleName} ${e.message ?: ""}".trim() }
            finally { loadedAt = System.currentTimeMillis(); busy = false }
        }.start()
    }

    fun parse(json: String) {
        val arr = org.json.JSONArray(json)
        val out = ArrayList<Pt>(); var tms = LongArray(0)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i); val h = o.getJSONObject("hourly")
            val tt = h.getJSONArray("time")
            if (tms.isEmpty()) tms = LongArray(tt.length()) { k -> java.time.LocalDateTime.parse(tt.getString(k)).toEpochSecond(java.time.ZoneOffset.UTC) * 1000 }
            val u = h.getJSONArray("uv_index"); val cl = h.getJSONArray("uv_index_clear_sky")
            out.add(Pt(o.getDouble("latitude"), o.getDouble("longitude"),
                DoubleArray(u.length()) { k -> u.optDouble(k, Double.NaN) }, DoubleArray(cl.length()) { k -> cl.optDouble(k, Double.NaN) }))
        }
        times = tms; pts = out
    }

    /** (platuma, ilguma, UVI, giedro dangaus UVI) laiku ms (tiesinė interpoliacija tarp valandų). */
    fun field(ms: Long): List<DoubleArray> {
        val t = times; if (t.size < 2 || ms < t.first() || ms > t.last()) return emptyList()
        var i = java.util.Arrays.binarySearch(t, ms); if (i < 0) i = -i - 2
        i = i.coerceIn(0, t.size - 2)
        val a = (ms - t[i]).toDouble() / (t[i + 1] - t[i])
        return pts.map { p -> doubleArrayOf(p.lat, p.lon, p.uvi[i] + (p.uvi[i + 1] - p.uvi[i]) * a, p.clear[i] + (p.clear[i + 1] - p.clear[i]) * a) }
            .filter { !it[2].isNaN() && !it[3].isNaN() }
    }

    /** Programos subsolarinis taškas (platuma, ilguma), °. */
    fun modelSubsolar(ms: Long): Pair<Double, Double> {
        val v = Astro.sunEq(ms)
        val dec = Math.toDegrees(asin(v[2].coerceIn(-1.0, 1.0)))
        var lon = Astro.raDeg(v) - Astro.gmstDeg(ms)
        lon = ((lon + 540.0) % 360.0) - 180.0
        return dec to lon
    }

    private fun cosZ(lat: Double, lon: Double, sLat: Double, sLon: Double): Double {
        val a = Math.toRadians(lat); val b = Math.toRadians(sLat); val d = Math.toRadians(lon - sLon)
        return sin(a) * sin(b) + cos(a) * cos(b) * cos(d)
    }

    /**
     * Giedro dangaus UVI dėmės centras: paieška (platuma ±30°, visa ilguma), maksimizuojant UVI ir cos(z)^1,2 koreliaciją;
     * patikslinama 0,25° žingsniu. Grąžina (platuma, ilguma, koreliacija) arba null.
     */
    fun fitCenter(ms: Long): DoubleArray? {
        val f = field(ms); if (f.size < 100 || f.maxOf { it[3] } < 1.0) return null
        fun score(sLat: Double, sLon: Double): Double {
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0; val n = f.size
            for (p in f) { val x = cosZ(p[0], p[1], sLat, sLon).coerceAtLeast(0.0).pow(1.2); val y = p[3]; sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y }
            val cov = sxy - sx * sy / n; val vx = sxx - sx * sx / n; val vy = syy - sy * sy / n
            return if (vx <= 0 || vy <= 0) -1.0 else cov / sqrt(vx * vy)
        }
        var best = doubleArrayOf(0.0, 0.0, -2.0)
        var la = -30.0
        while (la <= 30.0) { var lo = -180.0; while (lo < 180.0) { val s = score(la, lo); if (s > best[2]) best = doubleArrayOf(la, lo, s); lo += 2.0 }; la += 2.0 }
        var step = 1.0
        while (step >= 0.25) {
            var improved = true
            while (improved) {
                improved = false
                for ((dl, dn) in listOf(step to 0.0, -step to 0.0, 0.0 to step, 0.0 to -step)) {
                    val s = score(best[0] + dl, best[1] + dn); if (s > best[2]) { best = doubleArrayOf(best[0] + dl, best[1] + dn, s); improved = true }
                }
            }
            step /= 2
        }
        best[1] = ((best[1] + 540.0) % 360.0) - 180.0
        return best
    }

    @Volatile var enabled = false
    @Volatile var lastFit: DoubleArray? = null
        private set
    @Volatile private var fitAt = 0L
    @Volatile private var fitting = false

    /** Dėmės centras, perskaičiuojamas fone kas minutę. */
    fun centerCached(ms: Long = System.currentTimeMillis()): DoubleArray? {
        if (!fitting && ms - fitAt > 60_000L && pts.isNotEmpty()) {
            fitting = true; fitAt = ms
            Thread { try { lastFit = fitCenter(ms) } catch (_: Exception) {} finally { fitting = false } }.start()
        }
        return lastFit
    }

    fun text(ms: Long = System.currentTimeMillis()): String {
        if (pts.isEmpty()) return "UVI (Open-Meteo): $status"
        val (mLat, mLon) = modelSubsolar(ms)
        val fit = fitCenter(ms) ?: return "UVI (Open-Meteo): duomenys neapima dabarties arba per mažai ($status)"
        val dLon = ((fit[1] - mLon + 540.0) % 360.0) - 180.0; val dLat = fit[0] - mLat
        val ang = Math.toDegrees(acos(cosZ(fit[0], fit[1], mLat, mLon).coerceIn(-1.0, 1.0)))
        return "UVI patikra: programos subsolarinis taškas %.2f°, %.2f°; giedro dangaus UVI dėmės centras %.2f°, %.2f° (koreliacija %.3f); skirtumas %.2f° (platuma %+.2f°, ilguma %+.2f° = %+.0f min. Žemės sukimosi)".format(
            Locale.US, mLat, mLon, fit[0], fit[1], fit[2], ang, dLat, dLon, dLon * 4.0) + "; $status"
    }

    /** UVI spalva pagal PSO skalę (ARGB). */
    fun color(u: Double): Int = when {
        u < 0.5 -> 0; u < 3 -> 0xFF3CB44B.toInt(); u < 6 -> 0xFFFFE119.toInt(); u < 8 -> 0xFFF58231.toInt(); u < 11 -> 0xFFE6194B.toInt(); else -> 0xFF911EB4.toInt()
    }
}
