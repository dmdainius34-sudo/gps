package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Sp3V4Test {
    private val sp3: Sp3Reader by lazy {
        val f = listOf("../samples", "samples").map { File(it, Sp3ReaderTest.SAMPLE) }.firstOrNull { it.exists() }
            ?: File("../samples", Sp3ReaderTest.SAMPLE)
        f.inputStream().use { Sp3Reader.read(it) }
    }
    private val lat = Math.toRadians(54.74)

    @Test
    fun saastamoinenZenithAndMapping() {
        val z = Sp3Solver.saastamoinen(lat, 200.0, Math.PI / 2)
        val m30 = Sp3Solver.saastamoinen(lat, 200.0, Math.toRadians(30.0))
        println("Saastamoinen 200 m: zenitas %.3f m, 30° %.3f m (santykis %.3f)".format(Locale.US, z, m30, m30 / z))
        assertTrue("$z", z in 2.2..2.5)
        assertEquals(2.0, m30 / z, 0.05)
    }

    @Test
    fun klobucharDefaultRange() {
        for (tow in listOf(0.0, 43_200.0, 50_400.0)) for (elDeg in listOf(10.0, 30.0, 90.0)) {
            val d = Sp3Solver.klobuchar(lat, Math.toRadians(25.25), 0.5, Math.toRadians(elDeg), tow)
            assertTrue("el $elDeg tow $tow: $d m", d in 1.0..30.0)
        }
    }

    /** Pseudoatstumai su troposfera ir jonosfera (be skaičiuoklės kodo, tik SP3 padėtys ir modelių funkcijos). */
    @Test
    fun correctionsShortenRangeAndRecoverReceiver() {
        val omega = 7.2921151467e-5; val c = 299_792_458.0
        val me = PositionSolver.llhToEcef(lat, Math.toRadians(25.2545), 150.0)
        val t = Sp3Reader.gpsSeconds(2026, 11, 4, 12, 0, 0.0)
        val week = (t / Sp3Reader.WEEK).toInt(); val tow = t - week * Sp3Reader.WEEK
        val up = me.map { it / sqrt(me[0] * me[0] + me[1] * me[1] + me[2] * me[2]) }
        val la = lat; val lo = Math.toRadians(25.2545)
        val obs = ArrayList<Triple<Int, Double, Double>>(); val delays = ArrayList<Double>()
        for (n in 1..32) {
            val sat = "G%02d".format(Locale.US, n)
            var tau = 0.075; var rho = 0.0; var dts = 0.0; var el = 0.0; var az = 0.0; var bad = false
            repeat(8) {
                val st = Sp3Solver.satState(sp3, sat, t - tau) ?: run { bad = true; return@repeat }
                dts = st.dts
                val th = omega * tau
                val x = cos(th) * st.pos[0] + sin(th) * st.pos[1]; val y = -sin(th) * st.pos[0] + cos(th) * st.pos[1]
                val d = doubleArrayOf(x - me[0], y - me[1], st.pos[2] - me[2])
                rho = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]); tau = rho / c
                el = asin((d[0] * up[0] + d[1] * up[1] + d[2] * up[2]) / rho)
                val e = (-sin(lo) * d[0] + cos(lo) * d[1]) / rho
                val nn = (-sin(la) * cos(lo) * d[0] - sin(la) * sin(lo) * d[1] + cos(la) * d[2]) / rho
                az = atan2(e, nn)
            }
            if (bad || Math.toDegrees(el) < 15.0) continue
            val delay = Sp3Solver.saastamoinen(la, 150.0, el) + Sp3Solver.klobuchar(la, lo, az, el, tow)
            delays.add(delay)
            obs.add(Triple(n, rho + 300.0 - c * dts + delay, 40.0))
        }
        assertTrue(delays.all { it > 0.0 })
        fun err(o: Sp3V4.Opts) = Sp3V4.solve(week, tow, obs, sp3, o)!!.sol.let { s -> sqrt((0..2).sumOf { (s.ecef[it] - me[it]) * (s.ecef[it] - me[it]) }) }
        val with = err(Sp3V4.Opts(true, true, true, false))
        val without = err(Sp3V4.Opts(false, false, false, false))
        val weighted = err(Sp3V4.Opts(true, true, true, true))
        println("Atmosferos sintetinis (%d palyd.): su pataisomis %.3f m, svertinis %.3f m, be pataisų %.2f m".format(Locale.US, obs.size, with, weighted, without))
        assertTrue("$with", with < 1.0)
        assertTrue("$weighted", weighted < 1.0)
        assertTrue("$without", without > 2.0)
    }

    @Test
    fun hatchSmoothsAndRestartsOnSlip() {
        val rnd = java.util.Random(7)
        val h = Sp3V4.Hatch()
        val errs = ArrayList<Double>()
        for (k in 0 until 200) {
            val truth = 2.0e7 + 700.0 * k
            val code = truth + 10.0 * rnd.nextGaussian()
            val phase = truth - 1234.5                           // švari fazė su pastoviu poslinkiu
            val sm = h.update(5, code, phase, Sp3V4.ADR_VALID, k.toDouble())
            if (k >= 100) errs.add(sm - truth)
        }
        val mean = errs.average()
        val sd = sqrt(errs.sumOf { (it - mean) * (it - mean) } / errs.size)
        println("Hatch: triukšmas po 100 epochų %.2f m (žalias 10 m)".format(Locale.US, sd))
        assertTrue("$sd", sd < 2.0)
        assertEquals(100, h.count(5))
        h.update(5, 2.0e7 + 700.0 * 200, 2.0e7 + 700.0 * 200 - 1234.5 + 0.19, Sp3V4.ADR_VALID or Sp3V4.ADR_CYCLE_SLIP, 200.0)
        assertEquals(0, h.count(5))
        h.update(5, 2.0e7 + 700.0 * 201, 2.0e7 + 700.0 * 201 - 1234.5, Sp3V4.ADR_VALID, 201.0)
        assertEquals(1, h.count(5))
        // šuolis kodas–fazė > 10 m be žymės taip pat paleidžia iš naujo
        h.update(5, 2.0e7 + 700.0 * 202, 2.0e7 + 700.0 * 202 - 1234.5, Sp3V4.ADR_VALID, 202.0)
        h.update(5, 2.0e7 + 700.0 * 203, 2.0e7 + 700.0 * 203 - 1234.5 + 50.0, Sp3V4.ADR_VALID, 203.0)
        assertEquals(1, h.count(5))
    }
}
