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
    fun saastamoinenZenithAtSeaLevel() {
        val lat = Math.toRadians(Sp3Solver.REF_LAT)
        val zenith = Sp3Solver.saastamoinen(lat, 0.0, Math.PI / 2)
        println("Saastamoinen 0 m, zenitas: %.3f m".format(Locale.US, zenith))
        assertTrue("$zenith m", zenith in 2.2..2.5)
        // Žemiau zenito kelias per atmosferą ilgesnis, po horizontu pataisos nėra
        assertTrue(Sp3Solver.saastamoinen(lat, 0.0, Math.toRadians(30.0)) > 1.9 * zenith)
        assertEquals(0.0, Sp3Solver.saastamoinen(lat, 0.0, -0.1), 0.0)
    }

    @Test
    fun klobucharPlausible() {
        val la = Math.toRadians(Sp3Solver.REF_LAT); val lo = Math.toRadians(Sp3Solver.REF_LON)
        // Naktį lieka tik 5 ns pastovioji dalis (apie 1,5 m zenite)
        val night = (0 until 24).minOf { Sp3Solver.klobuchar(la, lo, 0.0, Math.PI / 2, it * 3600.0) }
        assertEquals(1.5, night, 0.05)
        val low = Sp3Solver.klobuchar(la, lo, 0.0, Math.toRadians(10.0), 43200.0)
        assertTrue("$low m", low > night && low < 40.0)
    }

    @Test
    fun syntheticReceiverRecoveredWithCorrections() {
        val t = Sp3Reader.gpsSeconds(2026, 11, 4, 12, 0, 0.0)
        val week = (t / Sp3Reader.WEEK).toInt()
        val tow = t - week * Sp3Reader.WEEK
        val sats = visible(t, 6)
        val obs = sats.map { sat ->
            val st = Sp3Solver.satState(sp3, sat, t - 0.07)!!
            val rho = sqrt((0..2).sumOf { (st.pos[it] - rx[it]) * (st.pos[it] - rx[it]) })
            PositionSolver.Obs(sat.substring(1).toInt(), simPr(sat, t)!! + Sp3Solver.atmosphereDelay(rx, st.pos, rho, tow))
        }
        val ep = PositionSolver.Epoch(week, tow, 0L, obs)
        fun err(corrected: Boolean): Double {
            val s = Sp3Solver.solve(ep, sp3, corrected)!!
            return sqrt((0..2).sumOf { (s.ecef[it] - rx[it]) * (s.ecef[it] - rx[it]) })
        }
        println("SP3 su atmosfera: v1 %.3f m, v2 %.3f m".format(Locale.US, err(false), err(true)))
        assertTrue(err(true) < 1.0)
        assertTrue(err(false) > 1.0)
    }

    @Test
    fun elevationMaskDropsLowSatellites() {
        val t = Sp3Reader.gpsSeconds(2026, 11, 4, 12, 0, 0.0)
        val week = (t / Sp3Reader.WEEK).toInt()
        val high = visible(t, 6)
        val low = (1..32).map { "G%02d".format(Locale.US, it) }.first { elevation(it, t) in -60.0..5.0 }
        val obs = (high + low).map { PositionSolver.Obs(it.substring(1).toInt(), simPr(it, t)!!) }
        var dropped = -1
        val s = Sp3Solver.solve(PositionSolver.Epoch(week, t - week * Sp3Reader.WEEK, 0L, obs), sp3, true) { dropped = it }
        assertEquals(1, dropped)
        assertEquals(6, s!!.sats)
    }

    /**
     * Nepriklausomas Sagnac testas: pseudoatstumai sudaromi be skaičiuoklės kodo (tik Sp3Reader padėtys ir laikrodis):
     * iteruojamas sklidimo laikas, palydovo padėtis siuntimo momentu pasukama apie Z kampu α = −ω·τ.
     */
    @Test
    fun sagnacIndependentSynthetic() {
        val omega = 7.2921151467e-5; val c = 299_792_458.0
        val lat0 = 54.74; val lon0 = 25.2545
        val me = PositionSolver.llhToEcef(Math.toRadians(lat0), Math.toRadians(lon0), 150.0)
        val t = Sp3Reader.gpsSeconds(2026, 11, 4, 9, 30, 0.0)
        val week = (t / Sp3Reader.WEEK).toInt()
        fun xyz(sat: String, tt: Double): DoubleArray? {
            val w = (tt / Sp3Reader.WEEK).toInt()
            return sp3.positionXyz(sat, w, tt - w * Sp3Reader.WEEK)?.let { p -> DoubleArray(3) { p[it] * 1000.0 } }
        }
        fun pr(sat: String): Double? {
            var tau = 0.075; var rho = 0.0; var dts = 0.0
            repeat(8) {
                val tTx = t - tau
                val p = xyz(sat, tTx) ?: return null
                val a = xyz(sat, tTx - 0.5) ?: return null; val b = xyz(sat, tTx + 0.5) ?: return null
                val w = (tTx / Sp3Reader.WEEK).toInt()
                val clk = sp3.clock(sat, w, tTx - w * Sp3Reader.WEEK) ?: return null
                dts = clk * 1e-6 - 2.0 * (p[0] * (b[0] - a[0]) + p[1] * (b[1] - a[1]) + p[2] * (b[2] - a[2])) / (c * c)
                val al = -omega * tau                                 // pasukimas apie Z kampu −ω·τ
                val x = cos(al) * p[0] - sin(al) * p[1]; val y = sin(al) * p[0] + cos(al) * p[1]
                rho = sqrt((x - me[0]) * (x - me[0]) + (y - me[1]) * (y - me[1]) + (p[2] - me[2]) * (p[2] - me[2]))
                tau = rho / c
            }
            return rho + 250.0 - c * dts
        }
        val up = me.map { it / sqrt(me[0] * me[0] + me[1] * me[1] + me[2] * me[2]) }
        val obs = (1..32).mapNotNull { n ->
            val sat = "G%02d".format(Locale.US, n)
            val p = xyz(sat, t) ?: return@mapNotNull null
            val d = DoubleArray(3) { p[it] - me[it] }
            val el = Math.toDegrees(Math.asin((d[0] * up[0] + d[1] * up[1] + d[2] * up[2]) / sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])))
            if (el < 15.0) null else pr(sat)?.let { PositionSolver.Obs(n, it) }
        }
        assertTrue("palydovų ${obs.size}", obs.size >= 6)
        val ep = PositionSolver.Epoch(week, t - week * Sp3Reader.WEEK, 0L, obs)
        fun east(s: PositionSolver.Solution) = Math.toRadians(s.lon - lon0) * 6_371_000.0 * cos(Math.toRadians(lat0))
        fun err(s: PositionSolver.Solution) = sqrt((0..2).sumOf { (s.ecef[it] - me[it]) * (s.ecef[it] - me[it]) })
        val ok = Sp3Solver.solve(ep, sp3, false, Sp3Solver.Rot.MINUS)!!
        val legacy = Sp3Solver.solve(ep, sp3, false, Sp3Solver.Rot.LEGACY)!!
        val none = Sp3Solver.solve(ep, sp3, false, Sp3Solver.Rot.NONE)!!
        val plus = Sp3Solver.solve(ep, sp3, false, Sp3Solver.Rot.PLUS)!!
        println("SAGNAC sintetinis (%d palydovų): −ω·τ paklaida %.4f m; v1/v2 (LEGACY) %.4f m; be pasukimo rytai %+.2f m (3D %.2f m); +ω·τ rytai %+.2f m"
            .format(Locale.US, obs.size, err(ok), err(legacy), east(none), err(none), east(plus)))
        assertTrue("−ω·τ: ${err(ok)} m", err(ok) < 1.0)
        assertTrue("LEGACY: ${err(legacy)} m", err(legacy) < 1.0)
        assertTrue("be pasukimo rytai: ${east(none)} m", kotlin.math.abs(east(none)) in 15.0..25.0)
        assertTrue("+ω·τ rytai: ${east(plus)} m", kotlin.math.abs(east(plus)) in 30.0..50.0)
    }

    @Test
    fun v2BlockReportsDistancesAndHeights() {
        val dir = createTempDir("sp3cmp")
        val fix = File(dir, "gnss_fix.csv")
        fix.writeText("laikas_ms,platuma,ilguma,aukstis_elips_m\n1000,54.73998,25.25466,100.0\n2000,54.73998,25.25466,102.0\n3000,54.73998,25.25466,\n")
        // 1 m, 2 m ... 10 m į šiaurę nuo fikso taško, aukštis 110 m
        val perM = 1.0 / Math.toRadians(1.0) / 6_371_000.0
        val sols = (1..10).map { k ->
            k * 1000L to PositionSolver.Solution(Sp3Solver.FIX_LAT + k * perM, Sp3Solver.FIX_LON, 110.0, 6, 1.0, doubleArrayOf(0.0, 0.0, 0.0))
        }
        val text = Sp3Solver.v2Block(Sp3Solver.Result(10, 10, 0, sols), Sp3Solver.Result(10, 10, 0, sols, List(10) { it % 2 }), fix)
        assertTrue(text, text.contains("--- SP3 TESTAS v2 ---"))
        assertTrue(text, text.contains("elipsoidinio aukščio mediana: 100.00 m"))
        assertTrue(text, text.contains("gnss_fix.csv mediana: 54.7399800, 25.2546600"))
        assertTrue(text, text.contains("v2: mediana 5.00 m, 90 % 9.00 m, maks. 10.00 m; aukščio mediana 110.00 m, skirtumas nuo fikso 10.00 m; sprendinių 10"))
        assertTrue(text, text.contains("v1: mediana 5.00 m"))
        assertTrue(text, text.contains("iš viso 5; per epochą: 0 1 0 1 0 1 0 1 0 1"))
        assertEquals("nėra", PositionSolver.medianFixHeight(File(dir, "nera.csv"))?.toString() ?: "nėra")
        dir.deleteRecursively()
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
