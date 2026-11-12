package lt.example.gnss

import android.content.Context
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Krano apribojimas: prietaisas kabinoje šone nuo bokšto juda tik apskritimu aplink bokšto ašį
 * (spindulys r, m), o jo aukštis pastovus (jei nurodytas). RTK filtrui pridedami pseudo-matavimai
 * „horizontalus atstumas iki ašies = r“ ir „aukštis = h0“; iš padėties gaunamas kabinos posūkio kampas aplink bokštą.
 */
object Crane {
    @Volatile var on = false
    @Volatile var lat = 0.0; @Volatile var lon = 0.0
    @Volatile var radius = 0.0
    @Volatile var height = Double.NaN          // elipsoidinis aukštis, m (NaN – nežinomas)
    const val SIGMA_R = 0.3; const val SIGMA_H = 0.5

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("crane", Context.MODE_PRIVATE)
        on = p.getBoolean("on", false)
        lat = p.getString("lat_s", null)?.toDoubleOrNull() ?: 0.0
        lon = p.getString("lon_s", null)?.toDoubleOrNull() ?: 0.0
        radius = p.getString("r_s", null)?.toDoubleOrNull() ?: 0.0
        height = p.getString("h_s", null)?.toDoubleOrNull() ?: Double.NaN
    }

    fun save(ctx: Context) {
        ctx.getSharedPreferences("crane", Context.MODE_PRIVATE).edit().putBoolean("on", on)
            .putString("lat_s", lat.toString()).putString("lon_s", lon.toString()).putString("r_s", radius.toString())
            .putString("h_s", if (height.isNaN()) "" else height.toString()).apply()
    }

    val valid get() = on && radius > 0.1 && (lat != 0.0 || lon != 0.0)

    /** Ašies taškas (ECEF, m; aukštis h0 arba 0) ir vietinis zenitas. */
    fun axis(): Pair<DoubleArray, DoubleArray> {
        val la = Math.toRadians(lat); val lo = Math.toRadians(lon)
        val a = PositionSolver.llhToEcef(la, lo, if (height.isNaN()) 0.0 else height)
        return a to doubleArrayOf(cos(la) * cos(lo), cos(la) * sin(lo), sin(la))
    }

    /**
     * Pseudo-matavimai filtrui: (H eilutė padėčiai [3], inovacija, σ). x – dabartinė padėtis (ECEF).
     */
    fun pseudo(x: DoubleArray): List<Triple<DoubleArray, Double, Double>> {
        if (!valid) return emptyList()
        val (a, u) = axis()
        val d = DoubleArray(3) { x[it] - a[it] }
        val du = d[0] * u[0] + d[1] * u[1] + d[2] * u[2]
        val hz = DoubleArray(3) { d[it] - du * u[it] }
        val rh = sqrt(hz[0] * hz[0] + hz[1] * hz[1] + hz[2] * hz[2])
        val out = ArrayList<Triple<DoubleArray, Double, Double>>()
        if (rh > 0.05) out.add(Triple(DoubleArray(3) { hz[it] / rh }, radius - rh, SIGMA_R))
        if (!height.isNaN()) out.add(Triple(u.copyOf(), -du, SIGMA_H))
        return out
    }

    /** (rytai, šiaurė) m nuo ašies. */
    fun en(latDeg: Double, lonDeg: Double): DoubleArray {
        val la0 = Math.toRadians(lat)
        val e = (lonDeg - lon) * Math.toRadians(1.0) * 6_378_137.0 * cos(la0)
        val n = (latDeg - lat) * Math.toRadians(1.0) * 6_356_752.3
        return doubleArrayOf(e, n)
    }

    /** Kabinos (prietaiso) padėties kampas aplink bokštą, ° nuo šiaurės. */
    fun slewDeg(latDeg: Double, lonDeg: Double): Double { val v = en(latDeg, lonDeg); return (Math.toDegrees(atan2(v[0], v[1])) + 360) % 360 }

    /** Taškas, projektuotas į apskritimą (ta pati kryptis nuo ašies). */
    fun project(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val v = en(latDeg, lonDeg); val d = sqrt(v[0] * v[0] + v[1] * v[1]).coerceAtLeast(1e-6)
        val e = v[0] / d * radius; val n = v[1] / d * radius
        return (lat + Math.toDegrees(n / 6_356_752.3)) to (lon + Math.toDegrees(e / (6_378_137.0 * cos(Math.toRadians(lat)))))
    }

    fun text(fixLat: Double?, fixLon: Double?): String {
        if (!valid) return "Krano apribojimas: ${if (on) "nenustatytas (ašis ir spindulys)" else "išjungtas"}"
        val sb = StringBuilder("Krano apribojimas: ašis %.7f, %.7f; spindulys %.2f m%s".format(Locale.US, lat, lon, radius,
            if (height.isNaN()) "; aukštis laisvas" else "; aukštis %.2f m".format(Locale.US, height)))
        if (fixLat != null && fixLon != null) {
            val v = en(fixLat, fixLon); val d = sqrt(v[0] * v[0] + v[1] * v[1])
            val (pl, po) = project(fixLat, fixLon)
            sb.append("; lusto fiksas %.2f m nuo ašies (turėtų būti %.2f), kampas aplink bokštą %.1f°, projektuotas %.7f, %.7f".format(Locale.US,
                d, radius, slewDeg(fixLat, fixLon), pl, po))
        }
        LiveRtk.last?.let { s -> sb.append("; RTK kampas aplink bokštą %.1f°".format(Locale.US, slewDeg(s.lat, s.lon))) }
        return sb.toString()
    }
}
