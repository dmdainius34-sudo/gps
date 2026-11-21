package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.Locale
import kotlin.math.sqrt

class BrdcTest {
    private fun b(s: String) = ByteArray(s.length / 2) { ((Character.digit(s[2 * it], 16) shl 4) or Character.digit(s[2 * it + 1], 16)).toByte() }
    private fun d(v: Double) = String.format(Locale.US, "%19.12E", v).replace('E', 'D')

    @Test
    fun gpsRecordMatchesBroadcastDecoder() {
        val h6 = PositionSolver.Alignment(0, 0, false, PositionSolver.PolarityRule.NONE, false, PositionSolver.Hyp.H6)
        val hex = listOf(
            "068b063000612427206310003e5f42b3011b9d343512db8a05d4d6e8320549d32800004a18104a66",
            "068b0630286124aa2305028b1d2f3b652f564e330e02660534ca3e38071400a1240db0000049d336",
            "068b06301861252f08003573195739d11f002e273a0028a31f1810d9237c33282cffad8e1405e296")
        val sf = hex.map { PositionSolver.subframeFromBytes(b(it), align = h6)!! }
        val e = PositionSolver.decodeEphemeris(22, sf[0], sf[1], sf[2], 2444)!!
        // toc kaip kalendorinė data (GPS laikas)
        val tocMs = ((e.week * 604_800.0 + e.toc + 315_964_800.0) * 1000).toLong()
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = tocMs }
        val first = String.format(Locale.US, "G22 %04d %02d %02d %02d %02d %02d", c.get(1), c.get(2) + 1, c.get(5), c.get(11), c.get(12), c.get(13)) +
            d(e.af0) + d(e.af1) + d(e.af2)
        fun row(vararg v: Double) = "    " + v.joinToString("") { d(it) }
        val rnx = """     3.04           N: GNSS NAV DATA    M: MIXED            RINEX VERSION / TYPE
                                                            END OF HEADER
""" + listOf(first,
            row(e.iode.toDouble(), e.crs, e.deltaN, e.m0), row(e.cuc, e.e, e.cus, e.sqrtA), row(e.toe, e.cic, e.omega0, e.cis),
            row(e.i0, e.crc, e.omega, e.omegaDot), row(e.idot, 1.0, e.week.toDouble(), 0.0), row(2.0, 0.0, e.tgd, e.iode.toDouble()),
            row(e.toe, 4.0, 0.0, 0.0)).joinToString("\n") + "\n"
        val set = Brdc.parse(rnx.byteInputStream())
        assertEquals(1, set.kep["G22"]!!.size)
        val t = 1794394411.420 - 315_964_800.0 + 18.0
        val p = set.ecef("G22", t)!!
        val q = PositionSolver.satState(e, t - e.week * 604_800.0)
        val dd = sqrt((p[0] - q[0]) * (p[0] - q[0]) + (p[1] - q[1]) * (p[1] - q[1]) + (p[2] - q[2]) * (p[2] - q[2]))
        assertEquals(0.0, dd, 0.5)
    }

    @Test
    fun beidouGeoRadius() {
        val k = Brdc.Kep('C', 3, 2444 * 604_800.0 + 302_414.0, 0.0, 6493.4, 0.0005, 0.03, 1.0, 0.5, 0.2, 0.0, 0.0, 0.0,
            0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val p = Brdc.kepler(k, 2444 * 604_800.0 + 303_000.0)
        val r = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]) / 1000
        assertEquals(42_164.0, r, 40.0)
        assertNotNull(Brdc.pick("<a>BRDC00WRD_S_20263160000_01D_MN.rnx.gz</a> <a>BRDC00WRD_R_20263160000_01D_MN.rnx.gz</a>"))
    }
}
