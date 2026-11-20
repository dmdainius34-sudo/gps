package lt.example.gnss

import android.content.Context
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Žinoma vieta su ± (apytikslės koordinatės): programos padėčiai pridedami du pseudo-matavimai – „į šiaurę = žinoma“ ir
 * „į rytus = žinoma“ su σ = ± (m). Kuo didesnis ±, tuo silpniau traukiama; palydovai vis tiek lemia, kur tiksliai esama.
 * Padeda kabinoje: pavienis atspindys ar keli palydovai padėties dešimtimis metrų nebenumeta.
 *
 * Saugiklis (kaip žinomam aukščiui): padėtis be apribojimo lyginama su žinoma vieta. Jei ~20 s vidutiniškai toliau nei
 * max(3σ, 15 m) – apribojimas nenaudojamas (pajudėjote ar neteisingos koordinatės), vėl naudojamas, kai < 0,7 ribos.
 * Koordinatės saugomos tik prietaise (laukelis „Žinomos koordinatės“).
 */
object KnownPos {
    @Volatile var use = false; private set
    @Volatile var lat = Double.NaN; private set
    @Volatile var lon = Double.NaN; private set
    const val DEFAULT_SIGMA = 10.0
    @Volatile var sigma = DEFAULT_SIGMA; private set

    @Volatile var conflict = false
        private set
    /** Padėties be apribojimo atstumas nuo žinomos vietos (vidurkis ~20 s), m. */
    @Volatile var freeD = Double.NaN
        private set
    private var aE = Double.NaN; private var aN = Double.NaN; private var cnt = 0

    /** „54.7399, 25.2546“ (tarpas, kabliataškis arba kablelis su tarpu tarp skaičių). */
    fun parse(text: String): Pair<Double, Double>? {
        val parts = text.trim().split(Regex("[;\\s]+|,\\s+")).map { it.trim().replace(',', '.') }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val la = parts[0].toDoubleOrNull() ?: return null
        val lo = parts[1].toDoubleOrNull() ?: return null
        return if (la in -90.0..90.0 && lo in -180.0..180.0 && (la != 0.0 || lo != 0.0)) la to lo else null
    }

    fun parseSigma(text: String?): Double =
        text?.trim()?.removePrefix("±")?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it in 0.5..500.0 } ?: DEFAULT_SIGMA

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("known_ref", Context.MODE_PRIVATE)
        set(p.getBoolean("coord_use", false), parse(p.getString("coord", "") ?: ""), parseSigma(p.getString("coord_sig", "")))
    }

    @Synchronized fun set(on: Boolean, ll: Pair<Double, Double>?, sig: Double) {
        val la = ll?.first ?: Double.NaN; val lo = ll?.second ?: Double.NaN
        if (la != lat || lo != lon || sig != sigma || on != use) { conflict = false; aE = Double.NaN; aN = Double.NaN; cnt = 0; freeD = Double.NaN }
        use = on; lat = la; lon = lo; sigma = sig
    }

    val valid get() = !lat.isNaN() && !lon.isNaN()
    val active get() = use && valid && !conflict
    fun limit() = maxOf(3 * sigma, 15.0)

    /** (rytai, šiaurė) m nuo žinomos vietos. */
    fun en(latDeg: Double, lonDeg: Double): DoubleArray {
        val a = 6_378_137.0; val f = 1 / 298.257223563; val e2 = f * (2 - f); val sl = sin(Math.toRadians(lat))
        val w = sqrt(1 - e2 * sl * sl); val nn = a / w; val mm = a * (1 - e2) / (w * w * w)
        return doubleArrayOf((lonDeg - lon) * Math.toRadians(1.0) * nn * cos(Math.toRadians(lat)), (latDeg - lat) * Math.toRadians(1.0) * mm)
    }

    /** Padėtis be apribojimų (kai palydovų pakanka): ar žinoma vieta čia tinka. */
    @Synchronized fun checkFree(latDeg: Double, lonDeg: Double) {
        if (!use || !valid) return
        val v = en(latDeg, lonDeg)
        if (abs(v[0]) > 50_000 || abs(v[1]) > 50_000) { conflict = true; freeD = sqrt(v[0] * v[0] + v[1] * v[1]); return }
        aE = if (aE.isNaN()) v[0] else aE * 0.95 + v[0] * 0.05
        aN = if (aN.isNaN()) v[1] else aN * 0.95 + v[1] * 0.05
        cnt++
        freeD = sqrt(aE * aE + aN * aN)
        val lim = limit()
        if (cnt >= 20 && freeD > lim) conflict = true
        else if (freeD < lim * 0.7) conflict = false
    }

    /** Programos padėties apribojimas: prie esamo (aukščio, krano) pridedama vieta; aukštis pradžiai – h. */
    fun sppConstraint(base: Sp3V5.HCon?, h: Double): Sp3V5.HCon? {
        if (!active) return base
        val la = Math.toRadians(lat); val lo = Math.toRadians(lon)
        val p = PositionSolver.llhToEcef(la, lo, if (h.isNaN()) 0.0 else h)
        val c = base ?: Sp3V5.HCon(Double.NaN, 0.0, p)
        c.pos = p
        c.east = doubleArrayOf(-sin(lo), cos(lo), 0.0)
        c.north = doubleArrayOf(-sin(la) * cos(lo), -sin(la) * sin(lo), cos(la))
        c.pSigma = sigma
        return c
    }

    fun text(): String {
        if (!valid) return "Žinoma vieta (±): neįvesta"
        val st = when {
            !use -> "tik paklaidoms skaičiuoti (padėčiai nenaudojama)"
            conflict -> "NENAUDOJAMA: padėtis be apribojimo per toli (pajudėjote ar neteisingos koordinatės)"
            else -> "naudojama programos padėtyje"
        }
        return "Žinoma vieta: ±%.0f m; %s%s".format(Locale.US, sigma, st,
            if (freeD.isNaN()) "" else "; padėtis be apribojimo %.0f m nuo jos (riba %.0f m)".format(Locale.US, freeD, limit()))
    }
}
