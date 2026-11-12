package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Rtcm3Test {
    private class W { val bits = StringBuilder()
        fun u(v: Long, n: Int) { for (k in n - 1 downTo 0) bits.append(((v ushr k) and 1L).toString()) }
        fun s(v: Long, n: Int) = u(v and ((1L shl n) - 1), n)
        fun bytes(): ByteArray { while (bits.length % 8 != 0) bits.append('0'); return ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() } }
    }
    private fun frame(p: ByteArray): ByteArray {
        val h = byteArrayOf(0xD3.toByte(), ((p.size ushr 8) and 3).toByte(), (p.size and 0xFF).toByte())
        val body = h + p
        val c = Rtcm3.crc24q(body, 0, body.size)
        return body + byteArrayOf((c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte())
    }

    @Test
    fun stationAndMsm5() {
        // 1005: X, Y, Z (0,1 mm)
        val a = W(); a.u(1005, 12); a.u(7, 12); a.u(0, 6); a.u(1, 1); a.u(0, 1); a.u(1, 1); a.u(0, 1)
        a.s(31_000_000_000L, 38); a.u(0, 1); a.u(0, 1); a.s(14_600_000_000L, 38); a.u(0, 2); a.s(51_800_000_000L, 38)
        // 1075: GPS MSM5, PRN 5 ir 12, signalas 2 (1C)
        val m = W(); m.u(1075, 12); m.u(7, 12); m.u(298_314_000, 30); m.u(0, 1 + 3 + 7 + 2 + 2 + 1 + 3)
        for (k in 1..64) m.u(if (k == 5 || k == 12) 1 else 0, 1)
        for (k in 1..32) m.u(if (k == 2) 1 else 0, 1)
        m.u(1, 1); m.u(1, 1)                                           // ląstelės
        m.u(70, 8); m.u(75, 8)                                        // sveikos ms
        m.u(0, 4); m.u(0, 4)
        m.u(512, 10); m.u(256, 10)                                    // ms dalis
        m.s(0, 14); m.s(0, 14)
        m.s(1000, 15); m.s(-16384, 15)                                 // antra – negaliojanti
        m.s(0, 22); m.s(0, 22); m.u(0, 4); m.u(0, 4); m.u(0, 1); m.u(0, 1); m.u(45, 6); m.u(30, 6); m.s(0, 15); m.s(0, 15)
        val f = Rtcm3.Framer()
        val stream = byteArrayOf(1, 2, 3) + frame(a.bytes()) + frame(m.bytes())
        val msgs = f.push(stream.copyOfRange(0, 20)) + f.push(stream.copyOfRange(20, stream.size))
        assertEquals(listOf(1005, 1075), msgs.map { it.first })
        val st = Rtcm3.station(msgs[0].second)!!
        assertEquals(3_100_000.0, st[0], 1e-6); assertEquals(1_460_000.0, st[1], 1e-6); assertEquals(5_180_000.0, st[2], 1e-6)
        val msm = Rtcm3.msm(msgs[1].second)
        assertNotNull(msm)
        assertEquals('G', msm!!.sys); assertEquals(298_314.0, msm.tow, 1e-9)
        assertEquals(1, msm.obs.size)
        val o = msm.obs[0]
        assertEquals(5, o.prn); assertEquals("1C", o.sig); assertEquals(45.0, o.cn0, 0.0)
        assertEquals((70 + 0.5 + 1000 * Math.scalb(1.0, -24)) * 1e-3 * Rtcm3.C, o.pr, 1e-6)
        assertEquals((70 + 0.5) * 1e-3 * Rtcm3.C, o.phase, 1e-6)
    }

    @Test
    fun physicalStation1032() {
        val a = W(); a.u(1032, 12); a.u(0, 12); a.u(42, 12); a.u(0, 6)
        a.s(31_000_000_000L, 38); a.s(14_600_000_000L, 38); a.s(51_800_000_000L, 38)
        val (id, x) = Rtcm3.physStation(a.bytes())!!
        assertEquals(42, id); assertEquals(3_100_000.0, x[0], 1e-6); assertEquals(5_180_000.0, x[2], 1e-6)
    }

    @Test
    fun ggaChecksum() {
        val g = Rtcm3.gga(54.74, 25.2545, 200.0, 1794394292000L)
        assertTrue(g.startsWith("\$GPGGA,"))
        val body = g.substring(1, g.indexOf('*'))
        var cs = 0; for (ch in body) cs = cs xor ch.code
        assertEquals("%02X".format(cs), g.substring(g.indexOf('*') + 1, g.indexOf('*') + 3))
        assertTrue(g.contains(",5444.4000,N,02515.2700,E,1,"))
    }
}
