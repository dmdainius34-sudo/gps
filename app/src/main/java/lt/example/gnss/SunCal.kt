package lt.example.gnss

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Kalibravimas pagal Saulę: Saulės padėtis (Astro.sunEq + refrakcija), prietaiso kryptis iš sukimo matricos
 * (planšetės nugarėlė −Z, projekcija į vietinę horizontalią plokštumą pagal gravitaciją) ir įrašų žurnalas.
 */
object SunCal {
    const val FILE = "saules_kalibravimai.csv"
    const val HEADER = "utc,platuma,ilguma,aukstis_elips_m,fikso_tikslumas_m,saules_az_deg,saules_aukstis_deg,prietaiso_az_deg,prietaiso_aukstis_deg," +
        "pataisa_deg,magnetometro_az_deg,deklinacija_deg,saules_vs_magnetometro_siaure_deg,pitch_deg,roll_deg,modelis\n"
    const val MIN_EL = 0.0; const val MAX_EL = 70.0; const val WARN_DEL = 10.0

    /** Atmosferos refrakcija (Saemundsson), ° – pridedama prie geometrinio aukščio. */
    fun refraction(elDeg: Double): Double {
        if (elDeg < -1.0) return 0.0
        val r = 1.02 / tan(Math.toRadians(elDeg + 10.3 / (elDeg + 5.11)))   // lanko minutės
        return r / 60.0
    }

    /** Saulės (azimutas, regimasis aukštis su refrakcija), °. */
    fun sunAzEl(ms: Long, lat: Double, lon: Double): Pair<Double, Double> {
        val (az, el) = Astro.altAz(Astro.sunEq(ms), lat, lon, ms)
        return az to el + refraction(el)
    }

    /**
     * Prietaiso kryptis iš sukimo matricos R (prietaisas → pasaulis: rytai, šiaurė, aukštyn; eilutėmis):
     * planšetės nugarėlė −Z = (−R[2], −R[5], −R[8]). Azimutas – jos projekcijos į horizontalią plokštumą kryptis
     * (vertikalė – iš gravitacijos), aukštis – kampas virš horizonto. Gulint (horizontali dalis < 0,3) – viršutinis kraštas +Y.
     * Grąžina (azimutas, aukštis, ašis).
     */
    fun deviceAzEl(r: FloatArray): Triple<Double, Double, String> {
        var e = -r[2].toDouble(); var n = -r[5].toDouble(); var u = -r[8].toDouble(); var axis = "-Z"
        if (hypot(e, n) < 0.3) { e = r[1].toDouble(); n = r[4].toDouble(); u = r[7].toDouble(); axis = "+Y" }
        val az = (Math.toDegrees(atan2(e, n)) + 360.0) % 360.0
        val el = Math.toDegrees(asin((u / kotlin.math.sqrt(e * e + n * n + u * u)).coerceIn(-1.0, 1.0)))
        return Triple(az, el, axis)
    }

    fun wrap(a: Double): Double { var x = (a + 540.0) % 360.0; if (x < 0) x += 360.0; return x - 180.0 }

    class Rec(val ms: Long, val lat: Double, val lon: Double, val h: Double, val acc: Double, val sunAz: Double, val sunEl: Double,
              val devAz: Double, val devEl: Double, val corr: Double, val magAz: Double, val decl: Double, val pitch: Double, val roll: Double, val model: String) {
        /** Saulės nustatyta tikroji šiaurė − magnetometro (su deklinacija) šiaurė, °: magnetometro tikrasis azimutas − Saulės azimutas. */
        val magDiff get() = if (magAz.isNaN()) Double.NaN else wrap(magAz + decl - sunAz)
        fun line(): String {
            fun f(v: Double, d: Int) = if (v.isNaN()) "" else "%.${d}f".format(Locale.US, v)
            val t = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
            return listOf(t, f(lat, 8), f(lon, 8), f(h, 2), f(acc, 1), f(sunAz, 3), f(sunEl, 3), f(devAz, 2), f(devEl, 2), f(corr, 2),
                f(magAz, 2), f(decl, 2), f(magDiff, 2), f(pitch, 1), f(roll, 1), model.replace(',', ' ')).joinToString(",") + "\n"
        }
    }

    fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    /** Pridedama eilutė (istorija išlaikoma). */
    @Synchronized fun log(ctx: Context, r: Rec) {
        val f = file(ctx)
        if (!f.exists() || f.length() == 0L) f.writeText(HEADER)
        f.appendText(r.line())
    }

    fun lastLine(ctx: Context): String? = try {
        file(ctx).takeIf { it.exists() }?.readLines()?.lastOrNull { it.isNotBlank() && !it.startsWith("utc,") }
    } catch (_: Exception) { null }
}
