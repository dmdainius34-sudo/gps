package lt.example.gnss

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Imtuvo greitis iš Doplerio (pseudoatstumo kitimo greičio) matavimų.
 * Modelis: prr + c·ḋts − v_sat·e = −v_rx·e + d, e – vienetinis vektorius nuo imtuvo į palydovą, d – imtuvo laikrodžio dreifas (m/s).
 * Nežinomieji: v_rx (ECEF, m/s) ir d. Svoriai – kaip padėties sprendinyje (aukštis, C/N0); vienas didžiausios liekanos atmetimas.
 */
object DopplerVel {
    class Vel(val ecef: DoubleArray, val speedH: Double, val courseDeg: Double, val vUp: Double, val sigma: Double, val n: Int)

    fun solve(sats: List<Sp3V5.SatEp>, x: DoubleArray): Vel? {
        class Row(val h: DoubleArray, val y: Double, val w: Double)
        val rows = ArrayList<Row>()
        for (s in sats) {
            val prr = s.prr ?: continue
            if (s.rp[0] == 0.0 && s.rp[1] == 0.0) continue
            val dx = s.rp[0] - x[0]; val dy = s.rp[1] - x[1]; val dz = s.rp[2] - x[2]
            val rho = sqrt(dx * dx + dy * dy + dz * dz); if (rho < 1e6) continue
            val e = doubleArrayOf(dx / rho, dy / rho, dz / rho)
            val l = Sp3Solver.look(x, s.rp, rho)
            if (l[4] < Math.toRadians(10.0)) continue
            val y = prr + s.clkDrift - (s.vel[0] * e[0] + s.vel[1] * e[1] + s.vel[2] * e[2])
            rows.add(Row(doubleArrayOf(-e[0], -e[1], -e[2], 1.0), y, Sp3V5.weight(l[4], s.cn0)))
        }
        var use: List<Row> = rows
        repeat(2) {
            if (use.size < 5) return null
            val sol = lsq(use.map { it.h }, use.map { it.y }, use.map { it.w }) ?: return null
            val res = use.map { r -> r.y - (0..3).sumOf { k -> r.h[k] * sol[k] } }
            val worst = res.indices.maxByOrNull { abs(res[it]) }!!
            if (it == 0 && abs(res[worst]) > 1.0 && use.size >= 6) { use = use.filterIndexed { i, _ -> i != worst }; return@repeat }
            val ss = res.indices.sumOf { i -> use[i].w * res[i] * res[i] } / use.sumOf { r -> r.w }
            val v = doubleArrayOf(sol[0], sol[1], sol[2])
            val llh = PositionSolver.ecefToLlh(x[0], x[1], x[2])
            val la = llh[0]; val lo = llh[1]
            val vE = -sin(lo) * v[0] + cos(lo) * v[1]
            val vN = -sin(la) * cos(lo) * v[0] - sin(la) * sin(lo) * v[1] + cos(la) * v[2]
            val vU = cos(la) * cos(lo) * v[0] + cos(la) * sin(lo) * v[1] + sin(la) * v[2]
            val course = (Math.toDegrees(atan2(vE, vN)) + 360.0) % 360.0
            return Vel(v, sqrt(vE * vE + vN * vN), course, vU, sqrt(ss), use.size)
        }
        return null
    }

    private fun lsq(h: List<DoubleArray>, y: List<Double>, w: List<Double>): DoubleArray? {
        val n = 4
        val a = Array(n) { DoubleArray(n + 1) }
        for (i in h.indices) for (j in 0 until n) {
            for (k in 0 until n) a[j][k] += w[i] * h[i][j] * h[i][k]
            a[j][n] += w[i] * h[i][j] * y[i]
        }
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (abs(a[p][c]) < 1e-12) return null
            val t = a[c]; a[c] = a[p]; a[p] = t
            for (r in 0 until n) if (r != c) { val f = a[r][c] / a[c][c]; for (k in c..n) a[r][k] -= f * a[c][k] }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }
}
