package lt.example.gnss

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ITRF (IGS SP3 orbitos, transliuojamos efemeridės ≈ WGS84) → ETRS89 (ETRF2000; LKS-94 pagrindas, LitPOS, Lietuvos žemėlapiai,
 * geodeziniai punktai). Europos plokštė juda ~2,5 cm/m. į šiaurės rytus, todėl Lietuvoje 2026 m. sistemos skiriasi ~1 m.
 * EUREF techninė pastaba 1 (Altamimi): ITRF2014→ETRF2000 parametrai epochai 2010,0 ir jų greičiai (ITRF2020 nuo ITRF2014 skiriasi mm).
 * X_E = X_I + T + D·X_I + R×X_I (R – mažų kampų matrica).
 */
object Etrs {
    @Volatile var enabled = true
    private const val MAS = Math.PI / 180.0 / 3600.0 / 1000.0
    private val T0 = doubleArrayOf(54.7e-3, 52.2e-3, -74.1e-3); private val TD = doubleArrayOf(0.1e-3, 0.1e-3, -1.9e-3)
    private const val D0 = 2.12e-9; private const val DD = 0.11e-9
    private val R0 = doubleArrayOf(1.701 * MAS, 10.290 * MAS, -16.632 * MAS); private val RD = doubleArrayOf(0.081 * MAS, 0.490 * MAS, -0.792 * MAS)
    private const val A = 6378137.0; private const val F = 1 / 298.257222101; private const val E2 = F * (2 - F)

    fun yearOf(ms: Long): Double {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")); c.timeInMillis = ms
        return c.get(Calendar.YEAR) + (c.get(Calendar.DAY_OF_YEAR) - 1) / 365.25
    }

    fun ecef(t: Double, x: Double, y: Double, z: Double): DoubleArray {
        val dt = t - 2010.0
        val tx = T0[0] + TD[0] * dt; val ty = T0[1] + TD[1] * dt; val tz = T0[2] + TD[2] * dt
        val d = D0 + DD * dt
        val r1 = R0[0] + RD[0] * dt; val r2 = R0[1] + RD[1] * dt; val r3 = R0[2] + RD[2] * dt
        return doubleArrayOf(x + tx + d * x - r3 * y + r2 * z, y + ty + r3 * x + d * y - r1 * z, z + tz - r2 * x + r1 * y + d * z)
    }

    /** (lat°, lon°, h elipsoidinis) ITRF → ETRS89. */
    fun llh(t: Double, latDeg: Double, lonDeg: Double, h: Double): DoubleArray {
        val la = Math.toRadians(latDeg); val lo = Math.toRadians(lonDeg)
        val n = A / sqrt(1 - E2 * sin(la) * sin(la))
        val e = ecef(t, (n + h) * cos(la) * cos(lo), (n + h) * cos(la) * sin(lo), (n * (1 - E2) + h) * sin(la))
        val lo2 = atan2(e[1], e[0]); val p = hypot(e[0], e[1])
        var la2 = atan2(e[2], p * (1 - E2)); var h2 = 0.0
        repeat(6) { val nn = A / sqrt(1 - E2 * sin(la2) * sin(la2)); h2 = p / cos(la2) - nn; la2 = atan2(e[2], p * (1 - E2 * nn / (nn + h2))) }
        return doubleArrayOf(Math.toDegrees(la2), Math.toDegrees(lo2), h2)
    }

    /** Poslinkis (m į šiaurę, į rytus) ITRF → ETRS89 vietoje (ataskaitai). */
    fun shiftNE(t: Double, latDeg: Double, lonDeg: Double): DoubleArray {
        val r = llh(t, latDeg, lonDeg, 0.0)
        return doubleArrayOf((r[0] - latDeg) * 111_320.0, (r[1] - lonDeg) * 111_320.0 * cos(Math.toRadians(latDeg)))
    }
}
