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

    val valid get() = on && radius in 0.1..200.0 && lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0) &&
        (height.isNaN() || height in -500.0..9000.0)

    /**
     * Laipsnių įvedimo taisymas: kablelis -> taškas; jei trūksta dešimtainio skirtuko (pvz. „547400171“),
     * įterpiamas po sveikųjų laipsnių skaitmenų (platumai – 2, ilgumai – 2, jei taip gaunamas leistinas skaičius).
     */
    fun parseDeg(text: String, max: Double): Double? {
        val t = text.trim().replace(',', '.').replace(" ", "")
        t.toDoubleOrNull()?.let { if (kotlin.math.abs(it) <= max) return it }
        val neg = t.startsWith("-"); val digits = t.removePrefix("-").filter { it.isDigit() }
        if (digits.length < 3) return null
        for (k in listOf(2, 3, 1)) {
            val v = "${digits.substring(0, k)}.${digits.substring(k)}".toDoubleOrNull() ?: continue
            if (v <= max) return if (neg) -v else v
        }
        return null
    }

    fun parseNum(text: String): Double? = text.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()

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
        // apsauga: filtras dar toli (kodas nesuartėjęs) arba klaidingi duomenys – apribojimas netaikomas
        if (rh > 0.05 && kotlin.math.abs(radius - rh) < 50.0) out.add(Triple(DoubleArray(3) { hz[it] / rh }, radius - rh, SIGMA_R))
        if (!height.isNaN() && kotlin.math.abs(du) < 100.0) out.add(Triple(u.copyOf(), -du, SIGMA_H))
        return out
    }

    /** Kreivumo spinduliai ašies platumoje: (N – pirmasis vertikalus, M – meridiano), m. */
    private fun radii(): Pair<Double, Double> {
        val a = 6_378_137.0; val f = 1 / 298.257223563; val e2 = f * (2 - f); val sl = sin(Math.toRadians(lat))
        val w = sqrt(1 - e2 * sl * sl)
        return a / w to a * (1 - e2) / (w * w * w)
    }

    /** (rytai, šiaurė) m nuo ašies. */
    fun en(latDeg: Double, lonDeg: Double): DoubleArray {
        val (nn, mm) = radii()
        return doubleArrayOf((lonDeg - lon) * Math.toRadians(1.0) * nn * cos(Math.toRadians(lat)), (latDeg - lat) * Math.toRadians(1.0) * mm)
    }

    /** Kabinos (prietaiso) padėties kampas aplink bokštą, ° nuo šiaurės. */
    fun slewDeg(latDeg: Double, lonDeg: Double): Double { val v = en(latDeg, lonDeg); return (Math.toDegrees(atan2(v[0], v[1])) + 360) % 360 }

    /** Taškas, projektuotas į apskritimą (ta pati kryptis nuo ašies). */
    fun project(latDeg: Double, lonDeg: Double): Pair<Double, Double> {
        val v = en(latDeg, lonDeg); val d = sqrt(v[0] * v[0] + v[1] * v[1]).coerceAtLeast(1e-6)
        val e = v[0] / d * radius; val n = v[1] / d * radius
        val (nn, mm) = radii()
        return (lat + Math.toDegrees(n / mm)) to (lon + Math.toDegrees(e / (nn * cos(Math.toRadians(lat)))))
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
        // kampo neapibrėžtumas ≈ padėties σ / spindulys (radianais): mažu spinduliu kampas labai jautrus
        LiveRtk.last?.let { s -> sb.append("; RTK kampas aplink bokštą %.1f° (±%.0f°)".format(Locale.US, slewDeg(s.lat, s.lon),
            Math.toDegrees(minOf(Math.PI, s.hacc / radius)))) }
        return sb.toString()
    }
}
