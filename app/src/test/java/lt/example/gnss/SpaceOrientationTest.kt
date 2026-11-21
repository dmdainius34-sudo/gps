package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceOrientationTest {
    private val ms = 1794394292379L          // 2026-11-11 10:51:32 UTC, Vilnius (planšetės įrašas)
    private val lat = 54.7399767; private val lon = 25.2544567

    @Test
    fun vilniusNow() {
        val r = SpaceOrientation.compute(lat, lon, ms)
        assertEquals(192.6, r.sunAz, 0.3); assertEquals(17.1, r.sunAlt, 0.3)
        assertEquals(54.1, r.polarisAlt, 0.3)
        assertTrue(r.polarisAz < 1.5 || r.polarisAz > 358.5)
        val (lo, hi) = SpaceOrientation.tiltRange(lat)
        assertEquals(11.8, lo, 0.1); assertEquals(58.7, hi, 0.1)
        assertTrue(r.eclTilt in lo - 0.1..hi + 0.1)
        assertEquals(17.6, r.poleDepression, 0.1)
        assertEquals(268.6, r.rotationMs, 0.5)
        assertEquals(17.9, r.sunMaxAlt, 0.3)
    }

    @Test
    fun motionPerpendicularToSunInEcliptic() {
        val m = SpaceOrientation.motionEq(ms); val s = Astro.sunEq(ms)
        assertEquals(0.0, m[0] * s[0] + m[1] * s[1] + m[2] * s[2], 1e-9)
        val pole = Astro.eclToEq(0.0, Math.PI / 2)
        assertEquals(0.0, m[0] * pole[0] + m[1] * pole[1] + m[2] * pole[2], 1e-9)
    }
}
