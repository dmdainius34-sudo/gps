package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

class SunChromoTest {
    /** Sintetinis 304 Å vaizdas: diskas su krašto pašviesėjimu, gijomis, protuberancais virš krašto ir triukšmu. */
    private fun image(w: Int, cx: Double, cy: Double, r: Double): IntArray {
        val rnd = Random(3)
        val px = IntArray(w * w)
        for (y in 0 until w) for (x in 0 until w) {
            val d = hypot(x - cx, y - cy) / r
            val a = atan2(y - cy, x - cx)
            var v = if (d < 1.0) 110.0 + 40.0 * d * d * d * d + 25.0 * rnd.nextGaussian() else 12.0 * exp(-(d - 1.0) / 0.1)
            if (d < 1.0 && kotlin.math.abs(a - 0.4) < 0.15 && d > 0.5) v *= 0.4                 // tamsi gija diske
            if (d >= 1.0) {
                for (pa in doubleArrayOf(0.7, 2.5, -1.4)) {                                     // protuberancai
                    val da = Math.IEEEremainder(a - pa, 2 * Math.PI)
                    v += 140.0 * exp(-(da / 0.08) * (da / 0.08)) * exp(-(d - 1.0) / 0.12)
                }
            }
            px[y * w + x] = v.toInt().coerceIn(0, 255)
        }
        return px
    }

    @Test
    fun findsDiskDespiteProminences() {
        val w = 1024; val cx = 517.3; val cy = 506.8; val r = 401.5
        val px = image(w, cx, cy, r)
        val d = SunChromo.findDisk({ x, y -> px[y * w + x] }, w, w)
        assertNotNull(d)
        println("rasta: centras %.2f, %.2f; spindulys %.2f (tikra %.1f, %.1f; %.1f)".format(java.util.Locale.US, d!![0], d[1], d[2], cx, cy, r))
        assertEquals(cx, d[0], 2.0); assertEquals(cy, d[1], 2.0); assertEquals(r, d[2], 2.5)
    }

    @Test
    fun noDiskInEmptyImage() {
        val w = 512
        assertNull(SunChromo.findDisk({ _, _ -> 5 }, w, w))
    }

    @Test
    fun mapEncodingIsLinear() {
        // 1,0 (rami fotosfera) → 170, šešėlis 0,3 → 51, 1,5 ir daugiau → 255
        assertEquals(170, SunImage.enc(1.0) and 255)
        assertEquals(51, SunImage.enc(0.3) and 255)
        assertEquals(255, SunImage.enc(2.0) and 255)
        assertEquals(0xFF, SunImage.enc(0.5) ushr 24)
    }
}
