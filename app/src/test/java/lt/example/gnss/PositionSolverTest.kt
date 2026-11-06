package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PositionSolverTest {

    private val week = 2440

    /** Sintetinė efemeridė: beveik apskritiminė GPS orbita. */
    private fun eph(sv: Int, om0Deg: Double, m0Deg: Double, toe: Double) = PositionSolver.Ephemeris(
        svid = sv, week = week, toc = toe, af0 = 1e-5 * sv, af1 = 1e-12, af2 = 0.0, tgd = -5e-9, iode = 10 + sv,
        toe = toe, sqrtA = 5153.7, e = 0.01, m0 = Math.toRadians(m0Deg), deltaN = 4.5e-9,
        omega0 = Math.toRadians(om0Deg), i0 = Math.toRadians(55.0), omega = 0.3, omegaDot = -8e-9, idot = 1e-10,
        cuc = 1e-6, cus = 5e-6, crc = 200.0, crs = 20.0, cic = 1e-7, cis = -1e-7,
    )

    /** Pseudoatstumas, suderintas su skaičiuoklės modeliu (Sagnac, palydovo laikrodis). */
    private fun simPr(e: PositionSolver.Ephemeris, rx: DoubleArray, tRx: Double, clkM: Double): Double {
        var tau = 0.07
        var rho = 0.0
        var dts = 0.0
        repeat(6) {
            val s = PositionSolver.satState(e, tRx - tau - dts)
            dts = s[3]
            val th = PositionSolver.OMEGA_E * tau
            val sx = cos(th) * s[0] + sin(th) * s[1]
            val sy = -sin(th) * s[0] + cos(th) * s[1]
            val dx = sx - rx[0]; val dy = sy - rx[1]; val dz = s[2] - rx[2]
            rho = sqrt(dx * dx + dy * dy + dz * dz)
            tau = rho / PositionSolver.C
        }
        return rho + clkM - PositionSolver.C * dts
    }

    @Test
    fun solvesSyntheticVilniusPosition() {
        val lat = 54.74; val lon = 25.2545; val h = 150.0
        val rx = PositionSolver.llhToEcef(Math.toRadians(lat), Math.toRadians(lon), h)
        val tRx = 300_000.0
        val ephs = HashMap<Int, List<PositionSolver.Ephemeris>>()
        val obs = ArrayList<PositionSolver.Obs>()
        var sv = 1
        for (om in listOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0)) for (m in listOf(0.0, 90.0, 180.0, 270.0)) {
            val e = eph(sv, om, m + om / 3, tRx - 1800)
            val s = PositionSolver.satState(e, tRx)
            // tik palydovai virš horizonto
            val up = doubleArrayOf(cos(Math.toRadians(lat)) * cos(Math.toRadians(lon)), cos(Math.toRadians(lat)) * sin(Math.toRadians(lon)), sin(Math.toRadians(lat)))
            val d = doubleArrayOf(s[0] - rx[0], s[1] - rx[1], s[2] - rx[2])
            val el = (d[0] * up[0] + d[1] * up[1] + d[2] * up[2]) / sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            if (el > 0.17) {
                ephs[sv] = listOf(e)
                obs.add(PositionSolver.Obs(sv, simPr(e, rx, tRx, 12_345.0)))
            }
            sv++
        }
        assertTrue("per mažai palydovų: ${obs.size}", obs.size >= 4)
        val sol = PositionSolver.solve(PositionSolver.Epoch(week, tRx, 0L, obs), ephs)
        assertNotNull(sol)
        val dH = PositionSolver.horizDist(lat, lon, sol!!.lat, sol.lon)
        println("Sintetinis sprendinys: ${"%.7f".format(sol.lat)}, ${"%.7f".format(sol.lon)}, h=${"%.2f".format(sol.h)}, sats=${sol.sats}, klaida=${"%.3f".format(dH)} m")
        assertTrue("horizontali klaida $dH m", dH < 1.0)
        assertEquals(h, sol.h, 1.0)
    }

    @Test
    fun rejectsOldEphemeris() {
        val e = eph(1, 0.0, 0.0, 100_000.0)
        val ep = PositionSolver.Epoch(week, 100_000.0 + 3 * 3600, 0L, listOf(PositionSolver.Obs(1, 2.2e7)))
        assertEquals(null, PositionSolver.solve(ep, mapOf(1 to listOf(e))))
    }

    // ---- LNAV subkadrų kodavimas testui ----
    private fun put(w: IntArray, word: Int, start: Int, len: Int, v: Long) {
        val mask = ((1L shl len) - 1)
        val sh = 24 - (start - 1) - len
        w[word - 1] = ((w[word - 1].toLong() and (mask shl sh).inv()) or ((v and mask) shl sh)).toInt()
    }
    private fun putSplit(w: IntArray, wHi: Int, sHi: Int, lenHi: Int, wLo: Int, sLo: Int, lenLo: Int, v: Long) {
        put(w, wHi, sHi, lenHi, v ushr lenLo); put(w, wLo, sLo, lenLo, v)
    }
    private fun q(x: Double, e: Int) = Math.round(Math.scalb(x, -e))
    private val pi = 3.1415926535898

    private fun frame(id: Int, fill: (IntArray) -> Unit): ByteArray {
        val w = IntArray(10)
        w[0] = 0x8B shl 16
        w[1] = id shl 2
        fill(w)
        val b = ByteArray(40)
        for (i in 0 until 10) {
            val v = (w[i] and 0xFFFFFF) shl 6           // D30 = 0 -> be inversijos
            b[4 * i] = (v ushr 24).toByte(); b[4 * i + 1] = (v ushr 16).toByte()
            b[4 * i + 2] = (v ushr 8).toByte(); b[4 * i + 3] = v.toByte()
        }
        return b
    }

    @Test
    fun decodesLnavSubframes() {
        val e = eph(7, 123.0, -45.0, 302_400.0)
        val iode = 77
        val sf1 = frame(1) { w ->
            put(w, 3, 1, 10, (week % 1024).toLong())
            put(w, 3, 23, 2, 0); put(w, 8, 1, 8, iode.toLong())
            put(w, 7, 17, 8, q(e.tgd, -31)); put(w, 8, 9, 16, (e.toc / 16).toLong())
            put(w, 9, 1, 8, q(e.af2, -55)); put(w, 9, 9, 16, q(e.af1, -43)); put(w, 10, 1, 22, q(e.af0, -31))
        }
        val sf2 = frame(2) { w ->
            put(w, 3, 1, 8, iode.toLong()); put(w, 3, 9, 16, q(e.crs, -5)); put(w, 4, 1, 16, q(e.deltaN / pi, -43))
            putSplit(w, 4, 17, 8, 5, 1, 24, q(e.m0 / pi, -31)); put(w, 6, 1, 16, q(e.cuc, -29))
            putSplit(w, 6, 17, 8, 7, 1, 24, q(e.e, -33)); put(w, 8, 1, 16, q(e.cus, -29))
            putSplit(w, 8, 17, 8, 9, 1, 24, q(e.sqrtA, -19)); put(w, 10, 1, 16, (e.toe / 16).toLong())
        }
        val sf3 = frame(3) { w ->
            put(w, 3, 1, 16, q(e.cic, -29)); putSplit(w, 3, 17, 8, 4, 1, 24, q(e.omega0 / pi, -31))
            put(w, 5, 1, 16, q(e.cis, -29)); putSplit(w, 5, 17, 8, 6, 1, 24, q(e.i0 / pi, -31))
            put(w, 7, 1, 16, q(e.crc, -5)); putSplit(w, 7, 17, 8, 8, 1, 24, q(e.omega / pi, -31))
            put(w, 9, 1, 24, q(e.omegaDot / pi, -43)); put(w, 10, 1, 8, iode.toLong()); put(w, 10, 9, 14, q(e.idot / pi, -43))
        }
        val s1 = PositionSolver.subframeFromBytes(sf1)!!
        val s2 = PositionSolver.subframeFromBytes(sf2)!!
        val s3 = PositionSolver.subframeFromBytes(sf3)!!
        assertEquals(1, s1.id); assertEquals(2, s2.id); assertEquals(3, s3.id)
        val d = PositionSolver.decodeEphemeris(7, s1, s2, s3, week)!!
        assertEquals(week, d.week)
        assertEquals(e.sqrtA, d.sqrtA, 1e-5)
        assertEquals(e.e, d.e, 1e-9)
        assertEquals(e.m0, d.m0, 1e-8)
        assertEquals(e.omega0, d.omega0, 1e-8)
        assertEquals(e.i0, d.i0, 1e-8)
        assertEquals(e.toe, d.toe, 0.0)
        // Palydovo padėtis iš dekoduotos efemeridės sutampa (kvantavimo ribose)
        val a = PositionSolver.satState(e, 303_000.0); val b = PositionSolver.satState(d, 303_000.0)
        val dist = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))
        assertTrue("padėties skirtumas $dist m", dist < 1.0)
    }

    @Test
    fun pseudorangeFromRawFields() {
        val weekNs = 604_800_000_000_000L
        val fullBias = -(week * weekNs + 300_000_000_000_000L)       // timeNanos = 0
        val svTime = 300_000_000_000_000L - 70_000_000L               // 70 ms kelionė
        val tow = PositionSolver.pseudorange(0L, fullBias, 0.0, 0.0, PositionSolver.STATE_SUBFRAME_SYNC or PositionSolver.STATE_TOW_DECODED, svTime)!!
        assertEquals(0.07 * PositionSolver.C, tow.first, 1e-3)
        // Tik subkadro sinchronizacija: laikas 6 s ribose
        val sub = PositionSolver.pseudorange(0L, fullBias, 0.0, 0.0, PositionSolver.STATE_SUBFRAME_SYNC, svTime % 6_000_000_000L)!!
        assertEquals(0.07 * PositionSolver.C, sub.first, 1e-3)
        assertEquals(null, PositionSolver.pseudorange(0L, fullBias, 0.0, 0.0, 1, svTime))
    }

    /** Savikontrolė su tikrais failais: -Dgnss.sample=<katalogas> arba GNSS_SAMPLE_DIR (gnss_raw.csv, gnss_nav.csv, gnss_fix.csv). */
    @Test
    fun sampleFilesSelfCheck() {
        val dir = System.getProperty("gnss.sample") ?: System.getenv("GNSS_SAMPLE_DIR")
        if (dir == null || !File(dir, "gnss_raw.csv").exists()) {
            println("Savikontrolė: pavyzdinių failų nėra (nustatykite GNSS_SAMPLE_DIR) – praleista")
            return
        }
        val out = File(dir, "gnss_solver.csv")
        val sols = PositionSolver.solveFiles(File(dir, "gnss_raw.csv"), File(dir, "gnss_nav.csv"), out)
        println("Savikontrolė: sprendinių ${sols.size}")
        sols.take(5).forEach { (t, s) ->
            println("  $t: ${"%.7f".format(s.lat)}, ${"%.7f".format(s.lon)}, h=${"%.1f".format(s.h)} m, sats=${s.sats}, hacc=${"%.1f".format(s.hacc)} m")
        }
        val med = PositionSolver.medianDiffToFix(sols, File(dir, "gnss_fix.csv"))
        println("Savikontrolė: horizontalaus skirtumo nuo telefono fikso mediana = ${med?.let { "%.1f m".format(it) } ?: "nėra"}")
    }
}
