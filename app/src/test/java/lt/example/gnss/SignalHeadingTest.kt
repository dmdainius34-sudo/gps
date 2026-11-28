package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SignalHeadingTest {
    /** Kabina: prietaiso dešinėje (rel. az 60–150°) siena −12 dB. Prietaisas atsuktas 200°; kryptis turi būti atkurta. */
    @Test fun recoversHeading() {
        val map = SkyMap("test_map.csv")
        fun cn0(rel: Double, el: Double): Double {
            val r = ((rel % 360) + 360) % 360
            return 30.0 + 10.0 * kotlin.math.sin(Math.toRadians(el)) - (if (r in 60.0..150.0) 12.0 else 0.0)
        }
        for (az in 0 until 360 step 5) for (el in 5 until 85 step 5) repeat(10) { map.addCn0(az + 2.5, el + 2.5, cn0(az + 2.5, el + 2.5)) }
        val heading = 200.0
        val sats = (0 until 14).map { i ->
            val az = (i * 360.0 / 14 + 7) % 360; val el = 15.0 + (i * 37 % 60)
            Sat("GPS", i + 1, cn0(az - heading, el).toFloat(), el.toFloat(), az.toFloat(), true)
        }
        val e = SignalHeading.estimate(sats, map)!!
        val d = abs(((e.deg - heading) + 540) % 360 - 180)
        assertTrue("kryptis ${e.deg}", d <= 15.0)
        assertTrue(e.corr > 0.8)
    }
}
