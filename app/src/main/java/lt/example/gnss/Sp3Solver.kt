package lt.example.gnss

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS L1 C/A padėties sprendimas su SP3 orbitomis ir laikrodžiais (tik testui; pagrindinė skaičiuoklė nekeičiama).
 * Matavimai – gnss_raw.csv per PositionSolver.readEpochs (SUBFRAME_SYNC, pseudoatstumas iš time_nanos, full_bias, bias).
 * Palydovo padėtis – siuntimo momentu t = t_rx − pr/c − dt_sv (dt_sv iš SP3 laikrodžio, viena iteracija),
 * Sagnac pataisa (Žemės sukimasis per sklidimo laiką) ir periodinė reliatyvistinė pataisa −2 r·v / c².
 */
object Sp3Solver {
    const val OUT_FILE = "gnss_solver_sp3_test.csv"
    val REF_LAT = 54.7400
    val REF_LON = 25.2545

    private const val C = PositionSolver.C
    private const val W = Sp3Reader.WEEK

    /** Palydovo padėtis (m, ECEF siuntimo momentu) ir laikrodžio pataisa (s, su reliatyvistine). */
    class SatState(val pos: DoubleArray, val dts: Double)

    fun satState(sp3: Sp3Reader, sat: String, t: Double): SatState? {
        val w = Math.floorDiv(t.toLong(), W.toLong()).toInt()
        val sow = t - w * W
        val p = sp3.positionXyz(sat, w, sow) ?: return null
        val c = sp3.clock(sat, w, sow) ?: return null
        if (c.isNaN()) return null
        // greitis skaitmeniškai (±0,5 s) reliatyvistinei pataisai
        val a = sp3.positionXyz(sat, w, sow - 0.5) ?: return null
        val b = sp3.positionXyz(sat, w, sow + 0.5) ?: return null
        val pos = DoubleArray(3) { p[it] * 1000.0 }
        val vel = DoubleArray(3) { (b[it] - a[it]) * 1000.0 }
        val rel = -2.0 * (pos[0] * vel[0] + pos[1] * vel[1] + pos[2] * vel[2]) / (C * C)
        return SatState(pos, c * 1e-6 + rel)
    }

    /** Ar laikas (GPS s nuo 1980-01-06) yra SP3 failo intervale. */
    fun inRange(sp3: Sp3Reader, t: Double) = sp3.epochCount > 0 && t >= sp3.epochs.first() && t <= sp3.epochs.last()

    /** Vienos epochos sprendinys arba null (< 4 palydovai, nekonverguoja). */
    fun solve(ep: PositionSolver.Epoch, sp3: Sp3Reader): PositionSolver.Solution? {
        val tRx = ep.week * W + ep.tRxTow
        val sats = ArrayList<DoubleArray>()   // x, y, z, pr pataisytas palydovo laikrodžiu
        for (o in ep.obs) {
            val sat = "G%02d".format(Locale.US, o.svid)
            val tSv = tRx - o.pr / C
            var st = satState(sp3, sat, tSv) ?: continue
            st = satState(sp3, sat, tSv - st.dts) ?: continue              // viena iteracija
            val tau = tRx - (tSv - st.dts)                                   // sklidimo laikas (su imtuvo laikrodžiu – mažas)
            val th = PositionSolver.OMEGA_E * tau
            val x = cos(th) * st.pos[0] + sin(th) * st.pos[1]
            val y = -sin(th) * st.pos[0] + cos(th) * st.pos[1]
            sats.add(doubleArrayOf(x, y, st.pos[2], o.pr + C * st.dts))
        }
        if (sats.size < 4) return null
        val s = doubleArrayOf(0.0, 0.0, 6_378_137.0, 0.0)
        var ok = false
        for (it in 0 until 30) {
            val h = Array(sats.size) { DoubleArray(4) }
            val r = DoubleArray(sats.size)
            for ((i, q) in sats.withIndex()) {
                val dx = s[0] - q[0]; val dy = s[1] - q[1]; val dz = s[2] - q[2]
                val rho = sqrt(dx * dx + dy * dy + dz * dz)
                h[i][0] = dx / rho; h[i][1] = dy / rho; h[i][2] = dz / rho; h[i][3] = 1.0
                r[i] = q[3] - (rho + s[3])
            }
            val d = lsq(h, r) ?: return null
            for (k in 0..3) s[k] += d[k]
            if (sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]) < 1e-3) { ok = true; break }
        }
        if (!ok) return null
        var ss = 0.0
        for (q in sats) {
            val dx = s[0] - q[0]; val dy = s[1] - q[1]; val dz = s[2] - q[2]
            val v = q[3] - (sqrt(dx * dx + dy * dy + dz * dz) + s[3]); ss += v * v
        }
        val rms = sqrt(ss / sats.size)
        val llh = PositionSolver.ecefToLlh(s[0], s[1], s[2])
        return PositionSolver.Solution(Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2], sats.size, rms * 2.0,
            doubleArrayOf(s[0], s[1], s[2]))
    }

    /** Mažiausių kvadratų žingsnis: (HᵀH)⁻¹ Hᵀ r. */
    private fun lsq(h: Array<DoubleArray>, r: DoubleArray): DoubleArray? {
        val n = 4
        val a = Array(n) { DoubleArray(n + 1) }
        for (i in h.indices) for (j in 0 until n) {
            for (k in 0 until n) a[j][k] += h[i][j] * h[i][k]
            a[j][n] += h[i][j] * r[i]
        }
        for (c in 0 until n) {
            var p = c
            for (k in c + 1 until n) if (abs(a[k][c]) > abs(a[p][c])) p = k
            if (abs(a[p][c]) < 1e-12) return null
            val t = a[c]; a[c] = a[p]; a[p] = t
            for (k in 0 until n) if (k != c) {
                val f = a[k][c] / a[c][c]
                for (j in c..n) a[k][j] -= f * a[c][j]
            }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

    class Result(val epochsTotal: Int, val inRange: Int, val skipped: Int, val sols: List<Pair<Long, PositionSolver.Solution>>) {
        fun medianHoriz(): Double? {
            if (sols.isEmpty()) return null
            val d = sols.map { PositionSolver.horizDist(it.second.lat, it.second.lon, REF_LAT, REF_LON) }.sorted()
            return if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
        }
    }

    /** gnss_raw.csv + SP3 -> gnss_solver_sp3_test.csv; epochos už SP3 intervalo praleidžiamos ir skaičiuojamos. */
    fun solveFile(raw: File, sp3: Sp3Reader, out: File): Result {
        var total = 0; var inR = 0; var skipped = 0
        val sols = ArrayList<Pair<Long, PositionSolver.Solution>>()
        out.bufferedWriter().use { w ->
            w.write(PositionSolver.SOLVER_HEADER)
            PositionSolver.readEpochs(raw) { ep ->
                total++
                if (!inRange(sp3, ep.week * W + ep.tRxTow)) { skipped++; return@readEpochs }
                inR++
                val s = solve(ep, sp3) ?: return@readEpochs
                sols.add(ep.timeMs to s)
                w.write(String.format(Locale.US, "%d,%.8f,%.8f,%.2f,%d,%.2f\n", ep.timeMs, s.lat, s.lon, s.h, s.sats, s.hacc))
            }
        }
        return Result(total, inR, skipped, sols)
    }

    fun diagBlock(r: Result?, error: String? = null): String = buildString {
        appendLine()
        appendLine("--- SP3 SOLVER TESTAS ---")
        if (r == null) { appendLine("Neatlikta: ${error ?: "nėra SP3"}"); return@buildString }
        appendLine("Epochų gnss_raw.csv: ${r.epochsTotal}; SP3 intervale: ${r.inRange}; praleista (už intervalo): ${r.skipped}")
        appendLine("Sprendinių įrašyta ($OUT_FILE): ${r.sols.size}")
        appendLine("Horizontalaus skirtumo iki atskaitos taško ($REF_LAT, $REF_LON) mediana: " +
            (r.medianHoriz()?.let { "%.2f m".format(Locale.US, it) } ?: "nėra"))
        appendLine("Pirmieji 3 sprendiniai:")
        if (r.sols.isEmpty()) appendLine("  (nėra)")
        r.sols.take(3).forEach { (t, s) ->
            appendLine("  %d: %.7f, %.7f, h=%.2f m, palydovų %d, hacc %.2f m".format(Locale.US, t, s.lat, s.lon, s.h, s.sats, s.hacc))
        }
    }
}
