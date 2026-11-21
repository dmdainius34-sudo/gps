package lt.example.gnss

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Magnetinio trikdžio sargas (nekalibruotas magnetometras: neapdorotas laukas + sistemos įvertintas poslinkis).
 * Trikdis, kai išvalyto lauko stipris toli nuo Žemės (Lietuvoje ~51 µT), kai jis per paskutines 5 s svyruoja
 * (metalas, srovė šalia) arba kai per 30 s smarkiai pasikeitė poslinkis (prijungtas laidas, magnetas, dėklas).
 */
object MagWatch {
    const val EARTH_UT = 51.5
    private class S(val ms: Long, val b: Double, val bias: DoubleArray)
    private val hist = ArrayDeque<S>()
    @Volatile var fieldUt = Double.NaN
        private set
    @Volatile var disturbed = false
        private set
    @Volatile var why = "dar nėra duomenų"
        private set

    @Synchronized fun onUncal(ms: Long, raw: FloatArray) {
        val bias = doubleArrayOf(raw[3].toDouble(), raw[4].toDouble(), raw[5].toDouble())
        val x = raw[0] - bias[0]; val y = raw[1] - bias[1]; val z = raw[2] - bias[2]
        val b = sqrt(x * x + y * y + z * z)
        hist.addLast(S(ms, b, bias)); while (hist.isNotEmpty() && ms - hist.first().ms > 30_000L) hist.removeFirst()
        fieldUt = b
        val recent = hist.filter { ms - it.ms <= 5_000L }.map { it.b }
        val mean = recent.average(); val sd = sqrt(recent.sumOf { (it - mean) * (it - mean) } / recent.size)
        val first = hist.first().bias
        val dBias = sqrt((0..2).sumOf { (bias[it] - first[it]).let { d -> d * d } })
        disturbed = true
        why = when {
            abs(mean - EARTH_UT) > 12.0 -> "lauko stipris %.0f µT (Žemės ~%.0f)".format(java.util.Locale.US, mean, EARTH_UT)
            sd > 3.0 -> "laukas svyruoja ±%.1f µT".format(java.util.Locale.US, sd)
            dBias > 5.0 -> "pasikeitė magnetometro poslinkis %.0f µT (laidas, magnetas?)".format(java.util.Locale.US, dBias)
            else -> { disturbed = false; "švarus (%.0f µT)".format(java.util.Locale.US, mean) }
        }
    }
}

/** Žingsniai: einama, jei paskutinis žingsnis ne senesnis nei 3 s. */
object Steps {
    @Volatile var lastMs = 0L
    @Volatile var count = 0
    fun onStep(ms: Long) { lastMs = ms; count++ }
    fun walking(now: Long = System.currentTimeMillis()) = now - lastMs < 3_000L
}
