package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tikri SM-X926B (MediaTek MT6686) Galileo I/NAV ir GLONASS pranešimai, 2026-11-11. */
class NavDecodersTest {
    private fun b(s: String) = ByteArray(s.length / 2) { ((Character.digit(s[2 * it], 16) shl 4) or Character.digit(s[2 * it + 1], 16)).toByte() }
    private fun gps(ms: Long) = ms / 1000.0 - 315_964_800.0 + 18.0
    private val lat = 54.74002; private val lon = 25.254465; private val h = 229.5

    private val gal = listOf(
        1794397566408L to "011e139237bbdbe500313bddaa04e9cab80100f4905aaaaa91820282b0",
        1794397516389L to "021e08e6fbd6c9f0c448c8adbcc021de192447fb3aaaaaaa96c36c4040",
        1794397598390L to "031e3ff1da4807ff52051d044e3f25aff9e9214d171aaaaa96eb9c02f0",
        1794397548410L to "041e09ffec00194e48000db690032cd0175bdd62ae9aaaaa937767d2b0",
        1794397600421L to "053bff7ffb507f5fd80b189345eaaaaaaa6de6760f5aaaaa9aee911040")
    private val glo = listOf(
        1794396903417L to "01074624e143023c47b2ec",
        1794396905384L to "0217600bd6f70433943cd8",
        1794396907399L to "038016a546209a28d3cf37",
        1794396908421L to "0481d82e600002660b0dd2")

    @Test
    fun galileoCrcAndEphemeris() {
        gal.forEach { assertTrue(GalileoNav.crcOk(b(it.second))) }
        val bad = b(gal[0].second).also { it[5] = (it[5].toInt() xor 0x10).toByte() }
        assertFalse(GalileoNav.crcOk(bad))
        val c = GalileoNav.Collector()
        gal.forEach { c.add(9, b(it.second), gps(it.first)) }
        val e = c.ephs[9]!!
        assertEquals(300600.0, e.toe, 0.0)
        assertEquals(29600.0, e.sqrtA * e.sqrtA / 1000.0, 1.0)
        assertEquals(-1.16e-9, e.tgd, 0.01e-9)
        val t = gps(1794397600421L)
        val p = PositionSolver.satState(e, t - e.week * 604_800.0)
        val (az, el) = Sp3Live.azEl(doubleArrayOf(p[0] / 1000, p[1] / 1000, p[2] / 1000), lat, lon, h)
        assertEquals(192.8, az, 0.3); assertEquals(66.2, el, 0.3)
    }

    @Test
    fun glonassStringsAndOrbit() {
        val c = GloNav.Collector()
        glo.forEach { c.add(3, b(it.second), it.first) }
        assertEquals(4, c.strings); assertEquals(0, c.bad)
        val e = c.ephs[3]!!
        assertEquals(-28.1e-6, e.tauN, 0.1e-6)
        val p = GloNav.position(e, gps(1794396908421L))!!
        val (az, el) = Sp3Live.azEl(doubleArrayOf(p[0] / 1000, p[1] / 1000, p[2] / 1000), lat, lon, h)
        assertEquals(265.3, az, 0.2); assertEquals(62.2, el, 0.2)
        assertEquals(null, GloNav.position(e, gps(1794396908421L) + 3 * 3600.0))
    }

    @Test
    fun broadcastDispatch() {
        gal.forEach { BroadcastEph.addGal(9, b(it.second), gps(it.first)) }
        glo.forEach { BroadcastEph.addGlo(3, b(it.second), it.first) }
        assertNotNull(BroadcastEph.ecefKm("E09", gps(1794397600421L)))
        assertNotNull(BroadcastEph.ecefKm("R03", gps(1794396908421L)))
        assertTrue(BroadcastEph.any(gps(1794397600421L)))
    }
}
