package lt.example.gnss

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Taikiklio diagnostika: kas ekrano kadrą – judesio jutikliai (gravitacija, orientacijos matrica, įvykių dažnis),
 * ekrano pasukimas, nugarėlės ir orientyro kryptys, kryželio ir taikinio vietos ekrane, kadrų dažnis.
 * Failas taikiklio_diag.csv (paskutinė taikiklio sesija + ankstesnės, iki ~20 000 eilučių) – į ZIP; santrauka – į ataskaitą.
 */
object AimDiag {
    const val FILE = "taikiklio_diag.csv"
    const val HEADER = "laikas_ms,kadras_ms,ekrano_pasukimas_deg,grav_x,grav_y,grav_z,R00,R01,R02,R10,R11,R12,R20,R21,R22," +
        "nugareles_az_deg,nugareles_aukstis_deg,taikinys,taikinio_az_deg,taikinio_aukstis_deg,d_az_deg,d_aukstis_deg," +
        "kryzelis_x_px,kryzelis_y_px,centras_x_px,centras_y_px,giroskopo_ivykiai,rotvec_ivykiai,grav_ivykiai,orientacijos_amzius_ms,giroskopas_yra\n"
    private val rows = StringBuilder()
    private var n = 0; private var t0 = 0L; private var last = 0L
    private var fpsSum = 0.0; private var g0 = 0.0; private var r0 = 0.0; private var ageSum = 0.0; private var ageMax = 0.0
    private var elMin = 999.0; private var elMax = -999.0; private var rot = 0.0; private var target = ""
    @Volatile var summary = ""
        private set

    @Synchronized fun start(t: String) { rows.setLength(0); n = 0; t0 = 0L; last = 0L; fpsSum = 0.0; ageSum = 0.0; ageMax = 0.0; elMin = 999.0; elMax = -999.0; target = t }

    @Synchronized fun frame(ms: Long, d: DoubleArray, devAz: Double, devEl: Double, tAz: Double, tEl: Double, dAz: Double, dEl: Double,
                            cross: FloatArray, center: FloatArray) {
        if (t0 == 0L) { t0 = ms; g0 = d[13]; r0 = d[14] }
        val dt = if (last == 0L) 0L else ms - last; last = ms
        if (dt > 0) fpsSum += 1000.0 / dt
        n++; ageSum += d[16]; ageMax = maxOf(ageMax, d[16]); rot = d[12]
        elMin = minOf(elMin, devEl); elMax = maxOf(elMax, devEl)
        if (n > 20_000) return
        fun f(v: Double, k: Int = 3) = "%.${k}f".format(Locale.US, v)
        rows.append(ms).append(',').append(dt).append(',').append(f(d[12], 0))
        for (i in 0..11) rows.append(',').append(f(d[i], 4))
        rows.append(',').append(f(devAz, 2)).append(',').append(f(devEl, 2)).append(',').append(target.substringBefore(" (").replace(',', ' '))
            .append(',').append(f(tAz, 2)).append(',').append(f(tEl, 2)).append(',').append(f(dAz, 2)).append(',').append(f(dEl, 2))
            .append(',').append(f(cross[0].toDouble(), 0)).append(',').append(f(cross[1].toDouble(), 0))
            .append(',').append(f(center[0].toDouble(), 0)).append(',').append(f(center[1].toDouble(), 0))
            .append(',').append(f(d[13], 0)).append(',').append(f(d[14], 0)).append(',').append(f(d[15], 0)).append(',').append(f(d[16], 0)).append(',').append(f(d[17], 0)).append('\n')
    }

    /** Taikiklio uždarymas: santrauka ir įrašas į failą. */
    @Synchronized fun stop(ctx: Context, gyroN: Double, rvN: Double) {
        if (n == 0) return
        val sec = (last - t0) / 1000.0
        summary = "Taikiklio diagnostika (%s): %d kadrų per %.1f s, vid. %.0f kadr./s; giroskopas %.0f įv./s, rotation vector %.0f įv./s; orientacijos amžius vid. %.0f ms, maks. %.0f ms; ekrano pasukimas %.0f°; nugarėlės aukštis %.1f…%.1f°".format(
            Locale.US, target, n, sec, if (n > 1) fpsSum / (n - 1) else 0.0, if (sec > 0) (gyroN - g0) / sec else 0.0, if (sec > 0) (rvN - r0) / sec else 0.0,
            ageSum / n, ageMax, rot, elMin, elMax)
        try {
            val f = File(ctx.applicationContext.filesDir, FILE)
            if (!f.exists() || f.length() > 8_000_000L) f.writeText(HEADER)
            f.appendText(rows.toString())
            ctx.getSharedPreferences("aim_diag", Context.MODE_PRIVATE).edit().putString("summary", summary).apply()
        } catch (_: Exception) {}
    }

    fun lastSummary(ctx: Context): String = summary.ifEmpty { ctx.getSharedPreferences("aim_diag", Context.MODE_PRIVATE).getString("summary", "") ?: "" }
    fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)
}
