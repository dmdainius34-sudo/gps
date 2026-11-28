package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class CabinLearnTest {
    @Test
    fun multipathSeparatedFromIonoAndClock() {
        MpTrack.reset()
        val rnd = Random(7)
        val clean = ArrayList<Double>(); val bad = ArrayList<Double>()
        var adr1 = 2.0e7; var adr2 = 2.1e7
        for (k in 0 until 400) {
            val t = k.toDouble()
            val clk = 300.0 * t                                   // bendras laikrodžio dreifas
            adr1 += 120.0; adr2 -= 80.0                          // palydovų judėjimas (nešlio pokytis)
            val iono = 0.01 * t                                   // lėta jonosfera
            val mpBad = if ((k / 20) % 2 == 0) 25.0 else -15.0     // atspindžiai: kodo paklaida šokinėja dešimtimis metrų
            val pr1 = adr1 + clk + 2 * iono + 1.5 * rnd.nextGaussian()
            val pr2 = adr2 + clk + 2 * iono + mpBad + 1.5 * rnd.nextGaussian()
            val out = MpTrack.epoch(t, listOf(
                MpTrack.In(1, pr1, adr1 + clk, null), MpTrack.In(2, pr2, adr2 + clk, null),
                MpTrack.In(3, adr1 + 5e5 + clk + rnd.nextGaussian(), adr1 + 5e5 + clk, null),
                MpTrack.In(4, adr2 + 7e5 + clk + rnd.nextGaussian(), adr2 + 7e5 + clk, null)))
            if (k > 60) { out[1]?.let { clean.add(abs(it)) }; out[2]?.let { bad.add(abs(it)) } }
        }
        val c = clean.average(); val b = bad.average()
        println("kodo–fazės paklaida: švarus %.1f m, su atspindžiais %.1f m".format(java.util.Locale.US, c, b))
        assertTrue(c < 3.0); assertTrue(b > 8.0)
    }

    @Test
    fun dopplerUsedWhenNoPhase() {
        MpTrack.reset()
        var rng = 2.0e7; var out: Map<Int, Double> = emptyMap()
        val vals = ArrayList<Double>()
        for (k in 0 until 200) {
            rng += 500.0
            out = MpTrack.epoch(k.toDouble(), listOf(MpTrack.In(1, rng, null, 500.0), MpTrack.In(2, rng * 1.1, null, 550.0),
                MpTrack.In(3, rng * 0.9, null, 450.0), MpTrack.In(4, rng * 1.05, null, 525.0)))
            if (k > 40) out[1]?.let { vals.add(abs(it)) }
        }
        assertTrue(vals.isNotEmpty()); assertTrue(vals.average() < 1.0)
    }

    @Test
    fun cnoModelTracksGoodSignal() {
        CnoModel.reset()
        val rnd = Random(3)
        // 60° aukštyje: dauguma signalų 38–44 dB-Hz, dalis užstotų ~25
        repeat(4000) { val v = if (rnd.nextDouble() < 0.2) 25.0 + rnd.nextGaussian() else 41.0 + 1.5 * rnd.nextGaussian(); CnoModel.update('G', 62.0, v) }
        val e = CnoModel.expected('G', 62.0)
        println("laukiamas C/N0 ties 62°: %.1f".format(java.util.Locale.US, e))
        assertEquals(41.5, e, 2.0)
        assertTrue(CnoModel.deficit('G', 62.0, 28.0) > 10.0)
    }

    @Test
    fun classifierLearnsReflections() {
        NlosModel.reset()
        val rnd = Random(11)
        repeat(3000) {
            val y = if (rnd.nextDouble() < 0.25) 1 else 0
            val def = if (y == 1) 9.0 + 3 * rnd.nextGaussian() else 1.0 + 3 * rnd.nextGaussian()
            val mp = if (y == 1) 18.0 + 6 * rnd.nextGaussian() else 2.0 + 1.5 * rnd.nextGaussian()
            NlosModel.train(NlosModel.feat(def, 20.0 + 50 * rnd.nextDouble(), mp, rnd.nextGaussian()), y)
        }
        assertTrue(NlosModel.ready)
        val pBad = NlosModel.p(NlosModel.feat(10.0, 30.0, 20.0, 0.0)); val pGood = NlosModel.p(NlosModel.feat(0.0, 50.0, 2.0, 0.0))
        println("atspindžio tikimybė: blogas %.2f, geras %.2f".format(java.util.Locale.US, pBad, pGood))
        assertTrue(pBad > 0.8); assertTrue(pGood < 0.2)
        NlosModel.reset()
    }
}
