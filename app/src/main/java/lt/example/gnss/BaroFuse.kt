package lt.example.gnss

import java.util.Locale
import kotlin.math.abs

/**
 * Barometro ir GNSS aukščio derinys (telefone; planšetė barometro neturi).
 *  - Įvedus žinomą aukštį (ar išmatavus žemę) įsimenamas tos akimirkos barometro aukštis – inkaras. Toliau prietaiso aukštis =
 *    įvestas + barometro pokytis nuo inkaro: lipant laiptais ar liftu aukštis seka pats (barometras pokyčius mato ~0,1–0,3 m tikslumu).
 *  - Barometras ilgainiui nuslenka (orų frontai iki kelių hPa per valandą ≈ 8–25 m, temperatūra), todėl poslinkis lėtai (laiko
 *    konstanta 2 h) taisomas pagal programos laisvą (be aukščio apribojimo) GNSS aukštį: jis šokinėja ±10–20 m, bet per valandas neslenka.
 *    Kai barometras jau pataisytas interneto jūros lygio slėgiu, GNSS pataisa ribojama ±3 m (lieka tik temperatūros ir modelio paklaida).
 *    Pavieniai GNSS aukščiai toliau nei 3σ (ar 40 m) nuo derinio praleidžiami.
 */
object BaroFuse {
    /**
     * Barometro duomenys: (stoties slėgis hPa, jūros lygio slėgis iš interneto hPa arba null), null – nėra ar seni. Nustato MainActivity.
     * Aukščio pokytis skaičiuojamas pačiame derinyje: inkaras įsimena slėgį ir tuometinį jūros lygio slėgį, todėl vėliau atėjęs
     * interneto slėgis (pvz. 989 hPa vietoj numatytojo 1013 hPa) aukščio nebešokdina ~200 m.
     */
    @Volatile var source: (() -> Pair<Double, Double?>?)? = null
    /** Kai orai jau pataisyti, GNSS pataisa ribojama: kabinoje laisvas GNSS aukštis būna nuolat ~10–20 m per žemas (atspindžiai). */
    const val MAX_DRIFT_WEATHER = 3.0
    private const val STD = 1013.25

    @Volatile private var anchorMsl = Double.NaN
    @Volatile private var anchorP = Double.NaN
    @Volatile private var anchorRef = Double.NaN
    @Volatile private var drift = 0.0
    @Volatile private var lastP = Double.NaN
    @Volatile private var lastRef = Double.NaN
    @Volatile private var lastMs = 0L
    @Volatile var gnssN = 0
        private set
    const val TAU_S = 7200.0

    /** Barometrinis aukštis (m) pagal jūros lygio slėgį ref. */
    fun alt(ref: Double, p: Double): Double = 44330.0 * (1.0 - Math.pow(p / ref, 1.0 / 5.255))

    private fun read(): Boolean {
        val v = try { source?.invoke() } catch (_: Exception) { null } ?: return false
        lastP = v.first; v.second?.let { lastRef = it }
        return true
    }

    /** Įvestas (ar išmatuotas) aukštis pasikeitė – naujas inkaras. */
    @Synchronized fun anchor(msl: Double?) {
        if (msl == null) { anchorMsl = Double.NaN; return }
        if (msl == anchorMsl && !anchorP.isNaN()) return
        read()
        anchorMsl = msl; anchorP = lastP; anchorRef = lastRef; drift = 0.0; gnssN = 0
    }

    val active get() = !anchorMsl.isNaN() && !anchorP.isNaN()
    /** Ar orų pokytis atimamas interneto slėgiu (inkaras ir dabartis turi jūros lygio slėgį). */
    val weatherOk get() = active && !anchorRef.isNaN() && !lastRef.isNaN()

    /** Barometro aukščio pokytis nuo inkaro (m) arba null. */
    @Synchronized fun delta(): Double? {
        read()
        if (anchorMsl.isNaN() || lastP.isNaN()) return null
        if (anchorP.isNaN()) { anchorP = lastP; anchorRef = lastRef }
        // interneto slėgis atėjo po inkaro: laikoma, kad per tą laiką orai nepasikeitė – inkaro jūros lygio slėgis = pirmasis gautas
        if (anchorRef.isNaN() && !lastRef.isNaN()) anchorRef = lastRef
        val r0 = if (anchorRef.isNaN()) STD else anchorRef
        val r1 = if (anchorRef.isNaN()) STD else lastRef
        return alt(r1, lastP) - alt(r0, anchorP)
    }

    /** Dabartinis prietaiso aukštis virš jūros (m) arba null, jei derinio nėra (tada naudojamas įvestas aukštis). */
    fun msl(): Double? { val d = delta() ?: return null; return anchorMsl + d + drift }

    /** Laisvas GNSS aukštis virš jūros (m) su σ: lėtai taiso barometro nuslinkimą. */
    @Synchronized fun gnss(nowMs: Long, hMsl: Double, sigma: Double) {
        val cur = msl() ?: return
        val dt = if (lastMs == 0L) 1.0 else ((nowMs - lastMs) / 1000.0).coerceIn(0.0, 30.0)
        lastMs = nowMs
        val d = hMsl - cur
        if (abs(d) > maxOf(40.0, 3 * sigma)) return
        drift += d * (dt / TAU_S)
        if (weatherOk) drift = drift.coerceIn(-MAX_DRIFT_WEATHER, MAX_DRIFT_WEATHER)
        gnssN++
    }

    /** Testams. */
    @Synchronized fun reset() { anchorMsl = Double.NaN; anchorP = Double.NaN; anchorRef = Double.NaN; drift = 0.0; lastP = Double.NaN; lastRef = Double.NaN; lastMs = 0L; gnssN = 0 }

    fun text(): String = if (!active) "Barometro ir GNSS aukščio derinys: neveikia (nėra barometro ar įvesto aukščio)"
        else "Barometro ir GNSS aukščio derinys: įvesta %.1f m, barometras nuo įvedimo %+.1f m, GNSS pataisa %+.1f m (%d mėg.), dabar %.1f m virš jūros%s".format(Locale.US,
            anchorMsl, delta() ?: 0.0, drift, gnssN, msl() ?: Double.NaN,
            if (weatherOk) " (orai pataisyti interneto slėgiu, GNSS pataisa ≤ ±%.0f m)".format(Locale.US, MAX_DRIFT_WEATHER) else " (interneto slėgio nėra – orų pokytį taiso GNSS)")
}
