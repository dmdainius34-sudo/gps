package lt.example.gnss

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.sqrt

/** Doppler glodinimas be skaičiuoklės kodo: sintetinis palydovas su žinomu atstumo kitimo greičiu. */
class Sp3V6Test {
    /** Grąžina glodinto kodo paklaidos SKN paskutinėse 50 epochų (po 100) ir paleidimų skaičių. */
    private fun run(rateSign: Double, seed: Long): Pair<Double, Int> {
        val rnd = java.util.Random(seed)
        val s = Sp3V6.DopplerSmoother()
        val errs = ArrayList<Double>()
        val r0 = 22_000_000.0; val v = -620.0; val a = 0.15             // m, m/s, m/s²
        for (k in 0 until 150) {
            val t = 1000.0 + k
            val tt = k.toDouble()
            val range = r0 + v * tt + 0.5 * a * tt * tt
            val rate = v + a * tt                                       // Android: > 0, kai atstumas didėja
            val code = range + 10.0 * rnd.nextGaussian()
            val sm = s.update(7, code, rateSign * rate, t)
            if (k >= 100) errs.add(sm - range)
        }
        val m = errs.average()
        return sqrt(errs.sumOf { (it - m) * (it - m) } / errs.size + m * m) to s.restarts
    }

    @Test
    fun dopplerSmoothingNoiseAndSign() {
        val ok = (1L..5L).map { run(1.0, it) }
        val flipped = (1L..5L).map { run(-1.0, it) }
        println("Doppler glodinimas: teisingas ženklas – " + ok.joinToString("; ") { "%.2f m (%d iš naujo)".format(Locale.US, it.first, it.second) })
        println("Doppler glodinimas: apverstas ženklas – " + flipped.joinToString("; ") { "%.2f m (%d iš naujo)".format(Locale.US, it.first, it.second) })
        assertTrue(ok.all { it.first < 3.0 })
        // apverstas ženklas turi neišlaikyti tos pačios ribos
        assertTrue(flipped.all { it.first >= 3.0 })
    }
}
