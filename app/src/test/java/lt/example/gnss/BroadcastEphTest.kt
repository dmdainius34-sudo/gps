package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tikri SM-X926B (MediaTek MT6686) GPS L1 C/A pranešimai, 2026-11-11, G22 subkadrai 1, 2, 3. */
class BroadcastEphTest {
    private val hex = listOf(
        "068b063000612427206310003e5f42b3011b9d343512db8a05d4d6e8320549d32800004a18104a66",
        "068b0630286124aa2305028b1d2f3b652f564e330e02660534ca3e38071400a1240db0000049d336",
        "068b06301861252f08003573195739d11f002e273a0028a31f1810d9237c33282cffad8e1405e296")
    private fun bytes(s: String) = ByteArray(s.length / 2) { ((Character.digit(s[2 * it], 16) shl 4) or Character.digit(s[2 * it + 1], 16)).toByte() }
    private val h6 = PositionSolver.Alignment(0, 0, false, PositionSolver.PolarityRule.NONE, false, PositionSolver.Hyp.H6)

    @Test
    fun mediatekLayoutParity() {
        for ((i, h) in hex.withIndex()) {
            val d = PositionSolver.decodeWords(bytes(h), h6)!!
            assertEquals(10, d.parityOk)
            val sf = PositionSolver.subframeFromBytes(bytes(h), align = h6)
            assertNotNull(sf); assertEquals(i + 1, sf!!.id)
        }
        // senosios hipotezės pilno subkadro neduoda
        val h1 = PositionSolver.Alignment(0, 0, false, PositionSolver.PolarityRule.CURRENT, false, PositionSolver.Hyp.H1)
        assertEquals(null, PositionSolver.subframeFromBytes(bytes(hex[0]), align = h1))
    }

    @Test
    fun ephemerisAndDirection() {
        val sf = hex.map { PositionSolver.subframeFromBytes(bytes(it), align = h6)!! }
        val e = PositionSolver.decodeEphemeris(22, sf[0], sf[1], sf[2], 2444)!!
        assertEquals(2444, e.week)
        assertEquals(302384.0, e.toe, 0.0)
        assertEquals(26561.0, e.sqrtA * e.sqrtA / 1000.0, 1.0)
        assertEquals(-11.2e-9, e.tgd, 0.1e-9)
        val t = 1794394411.420 - 315_964_800.0 + 18.0
        val p = PositionSolver.satState(e, t - 2444 * 604_800.0)
        val (az, el) = Sp3Live.azEl(doubleArrayOf(p[0] / 1000, p[1] / 1000, p[2] / 1000), 54.7399767, 25.2544567, 222.4)
        assertEquals(143.6, az, 0.2); assertEquals(72.0, el, 0.2)
    }

    @Test
    fun liveDecoderPicksLayout() {
        hex.forEach { BroadcastEph.add(22, bytes(it), 2444) }
        assertEquals(PositionSolver.Hyp.H6, BroadcastEph.align!!.hyp)
        val t = 1794394411.420 - 315_964_800.0 + 18.0
        assertTrue(BroadcastEph.any(t))
        assertNotNull(BroadcastEph.ecefKm(22, t))
        assertNotNull(Sp3Live.ecefKm("G22", t))
    }

    @Test
    fun compassCheckStats() {
        val c = CompassCheck()
        for (k in 0 until 100) c.add("ekrano viršus (Y)", (350.0 + k % 5) % 360.0, 340.0)
        val s = CompassCheck.stats(c.axes["ekrano viršus (Y)"]!!.d)!!
        assertEquals(12.0, s.first, 0.1); assertTrue(s.second <= 2.0)
        assertEquals(-170.0, CompassCheck.wrap(190.0), 1e-9)
    }
}
