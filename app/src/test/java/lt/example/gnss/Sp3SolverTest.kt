package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Sp3SolverTest {
    private val sp3: Sp3Reader by lazy {
        val f = listOf("../samples", "samples").map { File(it, Sp3ReaderTest.SAMPLE) }.firstOrNull { it.exists() }
            ?: File("../samples", Sp3ReaderTest.SAMPLE)
        f.inputStream().use { Sp3Reader.read(it) }
    }
    private val rx = PositionSolver.llhToEcef(Math.toRadians(Sp3Solver.REF_LAT), Math.toRadians(Sp3Solver.REF_LON), 150.0)
    private val clkM = 123.4                                       // imtuvo laikrodžio poslinkis, m

    /** Tikras pseudoatstumas: siuntimo momentas, palydovo laikrodis, Sagnac. */
    private fun simPr(sat: String, tRx: Double): Double? {
        var tau = 0.07; var rho = 0.0; var dts = 0.0
        repeat(6) {
            val st = Sp3Solver.satState(sp3, sat, tRx - tau) ?: return null
            dts = st.dts
            val th = PositionSolver.OMEGA_E * tau
            val sx = cos(th) * st.pos[0] + sin(th) * st.pos[1]
            val sy = -sin(th) * st.pos[0] + cos(th) * st.pos[1]
            val dx = sx - rx[0]; val dy = sy - rx[1]; val dz = st.pos[2] - rx[2]
            rho = sqrt(dx * dx + dy * dy + dz * dz)
            tau = rho / PositionSolver.C
        }
        return rho + clkM - PositionSolver.C * dts
    }

    private fun elevation(sat: String, t: Double): Double {
        val st = Sp3Solver.satState(sp3, sat, t) ?: return -90.0
        val up = rx.map { it / sqrt(rx[0] * rx[0] + rx[1] * rx[1] + rx[2] * rx[2]) }
        val d = DoubleArray(3) { st.pos[it] - rx[it] }
        val n = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
        return Math.toDegrees(Math.asin((d[0] * up[0] + d[1] * up[1] + d[2] * up[2]) / n))
    }

    private fun visible(t: Double, count: Int) =
        (1..32).map { "G%02d".format(Locale.US, it) }.filter { elevation(it, t) > 10.0 }
            .sortedByDescending { elevation(it, t) }.take(count)

    @Test
    fun syntheticReceiverRecovered() {
        val t = Sp3Reader.gpsSeconds(2026, 11, 4, 12, 0, 0.0)
        val week = (t / Sp3Reader.WEEK).toInt()
        val sats = visible(t, 6)
        assertEquals(6, sats.size)
        val obs = sats.map { PositionSolver.Obs(it.substring(1).toInt(), simPr(it, t)!!) }
        val s = Sp3Solver.solve(PositionSolver.Epoch(week, t - week * Sp3Reader.WEEK, 0L, obs), sp3)
        assertNotNull(s); s!!
        val e = sqrt((0..2).sumOf { (s.ecef[it] - rx[it]) * (s.ecef[it] - rx[it]) })
        println("SP3 sintetinis imtuvas (${sats.joinToString(" ")}): paklaida %.4f m".format(Locale.US, e))
        assertTrue("$e m", e < 1.0)
    }

    @Test
    fun rawFileWithSkippedEpochs() {
        val dir = createTempDir("sp3")
        val weekNs = 604_800_000_000_000L
        val raw = StringBuilder("laikas_ms,konstelacija,svid,signalas,time_nanos,full_bias_nanos,bias_nanos,time_offset_ns,state,recv_sv_time_ns\n")
        val t0 = Sp3Reader.gpsSeconds(2026, 11, 4, 12, 0, 0.0)
        val sats = visible(t0, 8)
        fun addEpoch(k: Int, tRx: Double, prs: Map<String, Double>) {
            val week = (tRx / Sp3Reader.WEEK).toInt()
            val tow = tRx - week * Sp3Reader.WEEK
            val timeNanos = k * 1_000_000_000L
            val fullBias = -(week * weekNs + Math.round(tow * 1e9)) + timeNanos
            for ((sat, pr) in prs) {
                val svTime = Math.round((tow - pr / PositionSolver.C) * 1e9)
                raw.append("${1000L * k},GPS,${sat.substring(1).toInt()},L1,$timeNanos,$fullBias,0.0,0.0,15,$svTime\n")
            }
        }
        var k = 0
        // 2 epochos prieš SP3 intervalą (praleidžiamos)
        val pr0 = sats.associateWith { simPr(it, t0)!! }
        repeat(2) { addEpoch(k++, Sp3Reader.gpsSeconds(2026, 11, 3, 23, 0, it.toDouble()), pr0) }
        // 20 epochų intervale
        repeat(20) { i -> val t = t0 + i * 30.0; addEpoch(k++, t, sats.associateWith { simPr(it, t)!! }) }
        File(dir, "gnss_raw.csv").writeText(raw.toString())
        val r = Sp3Solver.solveFile(File(dir, "gnss_raw.csv"), sp3, File(dir, Sp3Solver.OUT_FILE))
        println(Sp3Solver.diagBlock(r))
        assertEquals(22, r.epochsTotal)
        assertEquals(2, r.skipped)
        assertEquals(20, r.sols.size)
        assertTrue(r.medianHoriz()!! < 1.0)
        assertTrue(File(dir, Sp3Solver.OUT_FILE).readLines().first() == PositionSolver.SOLVER_HEADER.trim())
        dir.deleteRecursively()
    }
}
