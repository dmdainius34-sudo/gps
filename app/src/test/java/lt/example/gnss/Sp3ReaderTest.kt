package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

class Sp3ReaderTest {
    private val file: File = listOf("../samples", "samples").map { File(it, SAMPLE) }.firstOrNull { it.exists() } ?: File("../samples", SAMPLE)

    private val sp3: Sp3Reader by lazy { file.inputStream().use { Sp3Reader.read(it) } }

    @Test
    fun g01AtMidnight() {
        val t = Sp3Reader.gpsSeconds(2026, 11, 4, 0, 0, 0.0)
        val week = (t / Sp3Reader.WEEK).toInt()
        val s = sp3.position("G01", week, t - week * Sp3Reader.WEEK)
        assertNotNull(s); s!!
        assertEquals(15137.948274, s.x, 1e-6)
        assertEquals(-16111.611868, s.y, 1e-6)
        assertEquals(14635.953497, s.z, 1e-6)
        assertEquals(138.577803, s.clockUs, 1e-9)
        println("SP3 G01 00:00 spindulys: %.3f km".format(java.util.Locale.US, sqrt(s.x * s.x + s.y * s.y + s.z * s.z)))
    }

    @Test
    fun g01RadiusAllEpochs() {
        var n = 0
        for (k in 0 until sp3.epochCount) {
            val r = sp3.record("G01", k) ?: continue
            val rad = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
            // Fizinės ribos: a = 26560 km, e ≈ 0.01 -> 26250–26850 km
            assertTrue("epocha $k: $rad km", rad in 26250.0..26850.0)
            n++
        }
        assertTrue(n > 0)
        // interpoliacija tarp įrašų taip pat orbitoje
        val t = Sp3Reader.gpsSeconds(2026, 11, 4, 12, 2, 30.0)
        val week = (t / Sp3Reader.WEEK).toInt()
        val s = sp3.position("G01", week, t - week * Sp3Reader.WEEK)!!
        assertTrue(sqrt(s.x * s.x + s.y * s.y + s.z * s.z) in 26250.0..26850.0)
        println(Sp3Reader.diagBlock(file.name, sp3))
    }

    @Test
    fun epochCount() {
        assertEquals(289, sp3.epochCount)
        assertEquals(2443, sp3.startWeek)
    }

    @Test
    fun leaveOneOutAt0005() {
        val k = 1                                                  // 00:05:00
        val e = sp3.leaveOneOutError("G01", k)!!
        println("SP3 leave-one-out G01 00:05: %.4f m".format(java.util.Locale.US, e))
        assertTrue("$e m", e < 0.05)
    }

    @Test
    fun leaveOneOutFiveEpochs() {
        var mx = 0.0
        for (k in sp3.looEpochs()) {
            val e = sp3.leaveOneOutError("G01", k)!!
            val t = sp3.epochs[k] - sp3.epochs[0]
            println("SP3 leave-one-out G01 %02d:%02d: %.4f m".format(java.util.Locale.US, (t / 3600).toInt(), ((t % 3600) / 60).toInt(), e))
            assertTrue("epocha $k: $e m", e < 0.05)
            mx = maxOf(mx, e)
        }
        assertEquals(5, sp3.looEpochs().size)
        println("SP3 leave-one-out maks.: %.4f m".format(java.util.Locale.US, mx))
    }

    @Test
    fun edgesReturnEdgeValues() {
        assertTrue(sp3.edgeCheck("G01"))
        val t = sp3.epochs.last() + 3600.0
        val w = (t / Sp3Reader.WEEK).toInt()
        val c = sp3.clock("G01", w, t - w * Sp3Reader.WEEK)
        assertEquals(sp3.record("G01", sp3.epochCount - 1)!![3], c!!, 0.0)
    }

    @Test
    fun diagBlocks() {
        val text = Sp3Reader.diagBlock(file.name, sp3)
        assertTrue(text.contains("SP3 TESTAS") && text.contains("SP3 INTERPOLIACIJA"))
    }

    companion object { const val SAMPLE = "WUM0MGXRAP_20263080000_01D_05M_ORB.SP3.gz" }
}
