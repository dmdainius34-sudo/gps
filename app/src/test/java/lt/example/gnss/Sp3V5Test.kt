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

class Sp3V5Test {
    private val sp3: Sp3Reader by lazy {
        val f = listOf("../samples", "samples").map { File(it, Sp3ReaderTest.SAMPLE) }.firstOrNull { it.exists() }
            ?: File("../samples", Sp3ReaderTest.SAMPLE)
        f.inputStream().use { Sp3Reader.read(it) }
    }
    private val c = 299_792_458.0
    private val la = Math.toRadians(54.74); private val lo = Math.toRadians(25.2545)
    private val me = PositionSolver.llhToEcef(la, lo, 150.0)

    /** Tikras pseudoatstumas (su Sagnac, laikrodžiu ir atmosfera) bei Doppler; null – po 15° arba nėra SP3. */
    private fun sim(id: String, t: Double, clk: Double, drift: Double): Pair<Double, Double>? {
        var tau = 0.075; var rho = 0.0; var dts = 0.0; var el = 0.0; var az = 0.0; var u = DoubleArray(3)
        val up = me.map { it / sqrt(me[0] * me[0] + me[1] * me[1] + me[2] * me[2]) }
        repeat(8) {
            val st = Sp3Solver.satState(sp3, id, t - tau) ?: return null
            dts = st.dts
            val th = 7.2921151467e-5 * tau
            val x = cos(th) * st.pos[0] + sin(th) * st.pos[1]; val y = -sin(th) * st.pos[0] + cos(th) * st.pos[1]
            val d = doubleArrayOf(x - me[0], y - me[1], st.pos[2] - me[2])
            rho = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]); tau = rho / c
            u = DoubleArray(3) { d[it] / rho }
            el = asin(u[0] * up[0] + u[1] * up[1] + u[2] * up[2])
            az = atan2(-sin(lo) * u[0] + cos(lo) * u[1], -sin(la) * cos(lo) * u[0] - sin(la) * sin(lo) * u[1] + cos(la) * u[2])
        }
        if (Math.toDegrees(el) < 15.0) return null
        val w = (t / Sp3Reader.WEEK).toInt(); val sow = t - tau - w * Sp3Reader.WEEK
        val a = sp3.positionXyz(id, w, sow - 0.5)!!; val b = sp3.positionXyz(id, w, sow + 0.5)!!
        val ca = sp3.clock(id, w, sow - 0.5)!!; val cb = sp3.clock(id, w, sow + 0.5)!!
        val v = DoubleArray(3) { (b[it] - a[it]) * 1000.0 }
        val prr = u[0] * v[0] + u[1] * v[1] + u[2] * v[2] + drift - (cb - ca) * 1e-6 * c
        val atm = Sp3Solver.saastamoinen(la, 150.0, el) + Sp3Solver.klobuchar(la, lo, az, el, t % Sp3Reader.WEEK)
        return rho + clk - c * dts + atm to prr
    }

    private fun err(s: PositionSolver.Solution) = sqrt((0..2).sumOf { (s.ecef[it] - me[it]) * (s.ecef[it] - me[it]) })

    @Test
    fun interSystemBias30m() {
        val t = Sp3Reader.gpsSeconds(2026, 11, 4, 12, 0, 0.0)
        val week = (t / Sp3Reader.WEEK).toInt(); val tow = t - week * Sp3Reader.WEEK
        val obs = ArrayList<Sp3V5.Ob>()
        for (n in 1..36) for ((sys, extra) in listOf('G' to 0.0, 'E' to 30.0)) {
            val id = "%c%02d".format(Locale.US, sys, n)
            sim(id, t, 100.0 + extra, 0.0)?.let { obs.add(Sp3V5.Ob(sys, n, it.first, 40.0)) }
        }
        val fit = Sp3V5.solve(week, tow, obs, sp3, isb = true, raim = false)!!
        val shared = Sp3V5.solve(week, tow, obs, sp3, isb = false, raim = false)!!
        val isb = fit.clocks['E']!! - fit.clocks['G']!!
        println("ISB: G ${obs.count { it.sys == 'G' }}, E ${obs.count { it.sys == 'E' }}; paklaida %.3f m, ISB %.2f m; bendras laikrodis – paklaida %.2f m"
            .format(Locale.US, err(fit.sol), isb, err(shared.sol)))
        assertTrue("${err(fit.sol)}", err(fit.sol) < 1.0)
        assertEquals(30.0, isb, 1.0)
    }

    @Test
    fun raimDrops50mSatellite() {
        val t = Sp3Reader.gpsSeconds(2026, 11, 4, 15, 0, 0.0)
        val week = (t / Sp3Reader.WEEK).toInt(); val tow = t - week * Sp3Reader.WEEK
        val obs = (1..32).mapNotNull { n -> sim("G%02d".format(Locale.US, n), t, 50.0, 0.0)?.let { Sp3V5.Ob('G', n, it.first, 40.0) } }.toMutableList()
        val bad = obs[obs.size / 2]
        obs[obs.size / 2] = Sp3V5.Ob('G', bad.svid, bad.pr + 50.0, bad.cn0)
        val fit = Sp3V5.solve(week, tow, obs, sp3, isb = false, raim = true)!!
        println("RAIM: palydovų ${obs.size}, klaidingas G%02d, atmesta ${fit.dropped}, paklaida %.3f m".format(Locale.US, bad.svid, err(fit.sol)))
        assertEquals(listOf("G%02d".format(Locale.US, bad.svid)), fit.dropped)
        assertTrue(err(fit.sol) < 1.0)
    }

    @Test
    fun kalmanStaticConverges() {
        val rnd = java.util.Random(11)
        val t0 = Sp3Reader.gpsSeconds(2026, 11, 4, 9, 0, 0.0)
        val kf = Sp3V5.Kalman(listOf('C', 'E', 'G'))
        var last = Double.NaN; var lastH = Double.NaN
        var firstLsq = Double.NaN
        for (k in 0 until 60) {
            val t = t0 + k
            val week = (t / Sp3Reader.WEEK).toInt(); val tow = t - week * Sp3Reader.WEEK
            val clk = 300.0 + 0.5 * k
            val obs = ArrayList<Sp3V5.Ob>()
            for (n in 1..36) for (sys in listOf('G', 'E')) {
                val id = "%c%02d".format(Locale.US, sys, n)
                sim(id, t, clk + (if (sys == 'E') 12.0 else 0.0), 0.5)?.let { (pr, prr) ->
                    obs.add(Sp3V5.Ob(sys, n, pr + 10.0 * rnd.nextGaussian(), 45.0, prr + 0.05 * rnd.nextGaussian()))
                }
            }
            val fit = Sp3V5.solve(week, tow, obs, sp3, isb = true, raim = false)
            if (k == 0) firstLsq = err(fit!!.sol)
            val sol = kf.step(t, tow, Sp3V5.prepare(t, obs, sp3), fit)!!
            last = err(sol)
            val l = PositionSolver.ecefToLlh(sol.ecef[0], sol.ecef[1], sol.ecef[2])
            lastH = PositionSolver.horizDist(Math.toDegrees(l[0]), Math.toDegrees(l[1]), 54.74, 25.2545)
        }
        println("Kalman: 1 epochos MKM paklaida %.2f m; po 60 epochų 3D %.2f m, horizontaliai %.2f m".format(Locale.US, firstLsq, last, lastH))
        assertTrue("3D $last m", last < 2.0)
    }
}
