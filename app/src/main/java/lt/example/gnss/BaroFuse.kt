package lt.example.gnss

import java.util.Locale
import kotlin.math.abs

/**
 * Barometro ir GNSS aukščio derinys (telefone; planšetė barometro neturi).
 *  - Įvedus žinomą aukštį (ar išmatavus žemę) įsimenamas tos akimirkos barometro aukštis – inkaras. Toliau prietaiso aukštis =
 *    įvestas + barometro pokytis nuo inkaro: lipant laiptais ar liftu aukštis seka pats (barometras pokyčius mato ~0,1–0,3 m tikslumu).
 *  - Barometras ilgainiui nuslenka (orų frontai iki kelių hPa per valandą ≈ 8–25 m, temperatūra), todėl poslinkis lėtai (laiko
 *    konstanta 2 h) taisomas pagal programos laisvą (be aukščio apribojimo) GNSS aukštį: jis šokinėja ±10–20 m, bet per valandas neslenka.
 *    Pavieniai GNSS aukščiai toliau nei 3σ (ar 40 m) nuo derinio praleidžiami.
 */
object BaroFuse {
    /** Barometro aukštis (m, santykinis – bet kokiu atskaitos tašku), null – nėra ar senas. Nustato MainActivity. */
    @Volatile var source: (() -> Double?)? = null

    @Volatile private var anchorMsl = Double.NaN
    @Volatile private var anchorBaro = Double.NaN
    @Volatile private var drift = 0.0
    @Volatile private var lastBaro = Double.NaN
    @Volatile private var lastMs = 0L
    @Volatile var gnssN = 0
        private set
    const val TAU_S = 7200.0

    /** Įvestas (ar išmatuotas) aukštis pasikeitė – naujas inkaras. */
    @Synchronized fun anchor(msl: Double?) {
        if (msl == null) { anchorMsl = Double.NaN; return }
        if (msl == anchorMsl && !anchorBaro.isNaN()) return
        val b = baroNow() ?: lastBaro
        anchorMsl = msl; anchorBaro = b; drift = 0.0; gnssN = 0
    }

    private fun baroNow(): Double? = try { source?.invoke() } catch (_: Exception) { null }?.also { lastBaro = it }

    val active get() = !anchorMsl.isNaN() && !anchorBaro.isNaN()

    /** Dabartinis prietaiso aukštis virš jūros (m) arba null, jei derinio nėra (tada naudojamas įvestas aukštis). */
    fun msl(): Double? {
        if (anchorMsl.isNaN()) return null
        if (anchorBaro.isNaN()) { val b = baroNow() ?: return null; synchronized(this) { if (anchorBaro.isNaN()) anchorBaro = b } }
        val b = baroNow() ?: lastBaro.takeIf { !it.isNaN() } ?: return null
        return anchorMsl + (b - anchorBaro) + drift
    }

    /** Laisvas GNSS aukštis virš jūros (m) su σ: lėtai taiso barometro nuslinkimą. */
    @Synchronized fun gnss(nowMs: Long, hMsl: Double, sigma: Double) {
        val cur = msl() ?: return
        val dt = if (lastMs == 0L) 1.0 else ((nowMs - lastMs) / 1000.0).coerceIn(0.0, 30.0)
        lastMs = nowMs
        val d = hMsl - cur
        if (abs(d) > maxOf(40.0, 3 * sigma)) return
        drift += d * (dt / TAU_S)
        gnssN++
    }

    fun text(): String = if (!active) "Barometro ir GNSS aukščio derinys: neveikia (nėra barometro ar įvesto aukščio)"
        else "Barometro ir GNSS aukščio derinys: įvesta %.1f m, barometras nuo įvedimo %+.1f m, GNSS pataisa %+.1f m (%d mėg.), dabar %.1f m virš jūros".format(Locale.US,
            anchorMsl, (lastBaro - anchorBaro).takeIf { !it.isNaN() } ?: 0.0, drift, gnssN, msl() ?: Double.NaN)
}
