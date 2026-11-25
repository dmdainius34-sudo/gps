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
    @Volatile private var steps = false
    @Volatile var why = ""
        private set
    private var aE = Double.NaN; private var aN = Double.NaN; private var cnt = 0
    private var walkFrom = 0L; private var stillFrom = 0L

    const val WALK_S = 15.0; const val STILL_S = 30.0
    private var farS = 0

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
        if (la != lat || lo != lon || sig != sigma || on != use) { conflict = false; aE = Double.NaN; aN = Double.NaN; cnt = 0; freeD = Double.NaN; why = ""; walkFrom = 0L; stillFrom = 0L; farS = 0 }
        use = on; lat = la; lon = lo; sigma = sig
    }

    val valid get() = !lat.isNaN() && !lon.isNaN()
    val active get() = use && valid && !conflict
    /**
     * Atstumo riba padėčiai be apribojimo. Kabinoje telefonas gali nuosekliai klysti ~20–25 m (atspindžiai iš vienos pusės) –
     * tada vien atstumas nieko nereiškia. Pajudėjimą rodo žingsniai; atstumo riba – max(3σ, 60 m), jei žingsniai skaičiuojami,
     * kitaip max(3σ, 40 m).
     */
    fun limit() = if (steps) maxOf(3 * sigma, 60.0) else maxOf(3 * sigma, 40.0)

    /** (rytai, šiaurė) m nuo žinomos vietos. */
    fun en(latDeg: Double, lonDeg: Double): DoubleArray {
        val a = 6_378_137.0; val f = 1 / 298.257223563; val e2 = f * (2 - f); val sl = sin(Math.toRadians(lat))
        val w = sqrt(1 - e2 * sl * sl); val nn = a / w; val mm = a * (1 - e2) / (w * w * w)
        return doubleArrayOf((lonDeg - lon) * Math.toRadians(1.0) * nn * cos(Math.toRadians(lat)), (latDeg - lat) * Math.toRadians(1.0) * mm)
    }

    /**
     * Padėtis be apribojimų (kai palydovų pakanka): ar žinoma vieta čia tinka.
     * walking – žingsniai dabar; hasSteps – prietaisas žingsnius skaičiuoja (tada atstumo riba didesnė).
     * Išjungiama: einama ≥ 15 s arba padėtis be apribojimo vidutiniškai toliau nei riba(). Aukštis nesvarbus: bokšto ašis ant žemės
     * ir kabinoje – ta pati horizontali vieta. Vėl įjungiama: ≥ 30 s stovint ir padėtis < 0,7 ribos.
     */
    @Synchronized fun checkFree(latDeg: Double, lonDeg: Double, now: Long = System.currentTimeMillis(), walking: Boolean = false, hasSteps: Boolean = false) {
        if (!use || !valid) return
        if (walking) { if (walkFrom == 0L) walkFrom = now; stillFrom = 0L } else { walkFrom = 0L; if (stillFrom == 0L) stillFrom = now }
        val walkS = if (walkFrom == 0L) 0.0 else (now - walkFrom) / 1000.0
        val stillS = if (stillFrom == 0L) 0.0 else (now - stillFrom) / 1000.0
        steps = hasSteps
        val v = en(latDeg, lonDeg)
        if (abs(v[0]) > 50_000 || abs(v[1]) > 50_000) { conflict = true; why = "labai toli"; freeD = sqrt(v[0] * v[0] + v[1] * v[1]); return }
        aE = if (aE.isNaN()) v[0] else aE * 0.95 + v[0] * 0.05
        aN = if (aN.isNaN()) v[1] else aN * 0.95 + v[1] * 0.05
        cnt++
        freeD = sqrt(aE * aE + aN * aN)
        val lim = limit()
        if (freeD > lim) farS++ else if (freeD < lim * 0.7) farS = 0
        when {
            walkS >= WALK_S -> { conflict = true; why = "einate" }
            cnt >= 20 && farS >= 180 -> { conflict = true; why = "padėtis per toli ≥ 3 min." }
            conflict && stillS >= STILL_S && freeD < lim * 0.7 -> { conflict = false; why = "" }
        }
    }

    /** Programos padėties apribojimas: prie esamo (aukščio, krano) pridedama vieta; aukštis pradžiai – h. */
    fun sppConstraint(base: Sp3V5.HCon?, h: Double): Sp3V5.HCon? {
        if (!active) return base
        // žinoma vieta – ETRS89 (ortofoto, geodeziniai punktai); programos padėtis skaičiuojama ITRF
        val ll = Etrs.toItrf(lat, lon)
        val la = Math.toRadians(ll[0]); val lo = Math.toRadians(ll[1])
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
            conflict -> "NENAUDOJAMA: $why (vėl įsijungs pastovėjus ${STILL_S.toInt()} s arčiau)"
            else -> "naudojama programos padėtyje"
        }
        return "Žinoma vieta: ±%.0f m; %s%s".format(Locale.US, sigma, st,
            if (freeD.isNaN()) "" else "; padėtis be apribojimo %.0f m nuo jos (riba %.0f m; išjungiama ir einant ≥ %.0f s)".format(Locale.US, freeD, limit(), WALK_S))
    }
}
