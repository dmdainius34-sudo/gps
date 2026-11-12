package lt.example.gnss

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round
import kotlin.math.sqrt

/**
 * LAMBDA (Teunissen) – sveikųjų dviprasmybių paieška: LᵀDL išskaidymas, dekoreliacija (Z transformacija)
 * ir mažiausių kvadratų paieška dviem geriausiems sprendiniams (pagal RTKLIB lambda.c).
 * a – plaukiojančios dviprasmybės (ciklais), q – jų kovariacija. Grąžina (geriausias, antras, s1, s2) arba null.
 */
object Lambda {
    class Result(val best: DoubleArray, val second: DoubleArray, val s1: Double, val s2: Double) {
        val ratio get() = if (s1 > 0) s2 / s1 else 0.0
    }

    private fun ld(n: Int, q: Array<DoubleArray>): Pair<Array<DoubleArray>, DoubleArray>? {
        val a = Array(n) { q[it].copyOf() }
        val l = Array(n) { DoubleArray(n) }; val d = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            d[i] = a[i][i]
            if (d[i] <= 0.0) return null
            val s = sqrt(d[i])
            for (j in 0..i) l[i][j] = a[i][j] / s
            for (j in 0 until i) for (k in 0..j) a[j][k] -= l[i][k] * l[i][j]
            val lii = l[i][i]
            for (j in 0..i) l[i][j] /= lii
        }
        return l to d
    }

    private fun gauss(n: Int, l: Array<DoubleArray>, z: Array<DoubleArray>, i: Int, j: Int) {
        val mu = round(l[i][j]).toInt()
        if (mu != 0) {
            for (k in i until n) l[k][j] -= mu * l[k][i]
            for (k in 0 until n) z[k][j] -= mu * z[k][i]
        }
    }

    private fun perm(n: Int, l: Array<DoubleArray>, d: DoubleArray, j: Int, del: Double, z: Array<DoubleArray>) {
        val eta = d[j] / del; val lam = d[j + 1] * l[j + 1][j] / del
        d[j] = eta * d[j + 1]; d[j + 1] = del
        for (k in 0 until j) {
            val a0 = l[j][k]; val a1 = l[j + 1][k]
            l[j][k] = -l[j + 1][j] * a0 + a1
            l[j + 1][k] = eta * a0 + lam * a1
        }
        l[j + 1][j] = lam
        for (k in j + 2 until n) { val t = l[k][j]; l[k][j] = l[k][j + 1]; l[k][j + 1] = t }
        for (k in 0 until n) { val t = z[k][j]; z[k][j] = z[k][j + 1]; z[k][j + 1] = t }
    }

    private fun reduction(n: Int, l: Array<DoubleArray>, d: DoubleArray, z: Array<DoubleArray>) {
        var j = n - 2; var k = n - 2
        while (j >= 0) {
            if (j <= k) for (i in j + 1 until n) gauss(n, l, z, i, j)
            val del = d[j] + l[j + 1][j] * l[j + 1][j] * d[j + 1]
            if (del + 1e-6 < d[j + 1]) { perm(n, l, d, j, del, z); k = j; j = n - 2 } else j--
        }
    }

    private fun sgn(x: Double) = if (x <= 0.0) -1.0 else 1.0

    /** Dviejų geriausių sveikųjų vektorių paieška (m = 2). */
    private fun search(n: Int, l: Array<DoubleArray>, d: DoubleArray, zs: DoubleArray): Pair<List<DoubleArray>, DoubleArray>? {
        val m = 2
        val s = Array(n) { DoubleArray(n) }; val dist = DoubleArray(n); val zb = DoubleArray(n); val z = DoubleArray(n); val step = DoubleArray(n)
        val zn = Array(m) { DoubleArray(n) }; val sv = DoubleArray(m) { Double.MAX_VALUE }
        var nn = 0; var imax = 0; var maxdist = 1e99
        var k = n - 1
        dist[k] = 0.0; zb[k] = zs[k]; z[k] = floor(zb[k] + 0.5); var y = zb[k] - z[k]; step[k] = sgn(y)
        var c = 0
        while (c < 100_000) {
            c++
            val newdist = dist[k] + y * y / d[k]
            if (newdist < maxdist) {
                if (k != 0) {
                    k--; dist[k] = newdist
                    for (i in 0..k) s[k][i] = s[k + 1][i] + (z[k + 1] - zb[k + 1]) * l[k + 1][i]
                    zb[k] = zs[k] + s[k][k]; z[k] = floor(zb[k] + 0.5); y = zb[k] - z[k]; step[k] = sgn(y)
                } else {
                    if (nn < m) {
                        if (nn == 0 || newdist > sv[imax]) imax = nn
                        z.copyInto(zn[nn]); sv[nn] = newdist; nn++
                    } else {
                        if (newdist < sv[imax]) {
                            z.copyInto(zn[imax]); sv[imax] = newdist
                            imax = 0; for (i in 0 until m) if (sv[imax] < sv[i]) imax = i
                        }
                        maxdist = sv[imax]
                    }
                    z[0] += step[0]; y = zb[0] - z[0]; step[0] = -step[0] - sgn(step[0])
                }
            } else {
                if (k == n - 1) break
                k++; z[k] += step[k]; y = zb[k] - z[k]; step[k] = -step[k] - sgn(step[k])
            }
        }
        if (nn < 2) return null
        val order = (0 until m).sortedBy { sv[it] }
        return order.map { zn[it] } to DoubleArray(m) { sv[order[it]] }
    }

    /** (Zᵀ)⁻¹ · e – Gauso eliminacija (Z sveikasis, unimodulinis). */
    private fun solveZt(n: Int, z: Array<DoubleArray>, e: DoubleArray): DoubleArray? {
        val a = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) z[j][i] else e[i] } }
        for (col in 0 until n) {
            val p = (col until n).maxByOrNull { abs(a[it][col]) }!!
            if (abs(a[p][col]) < 1e-12) return null
            val t = a[col]; a[col] = a[p]; a[p] = t
            for (r in 0 until n) if (r != col) { val f = a[r][col] / a[col][col]; for (j in col..n) a[r][j] -= f * a[col][j] }
        }
        return DoubleArray(n) { round(a[it][n] / a[it][it]) }
    }

    fun solve(a: DoubleArray, q: Array<DoubleArray>): Result? {
        val n = a.size; if (n < 1) return null
        val (l, d) = ld(n, q) ?: return null
        val z = Array(n) { i -> DoubleArray(n) { j -> if (i == j) 1.0 else 0.0 } }
        reduction(n, l, d, z)
        val zs = DoubleArray(n) { i -> (0 until n).sumOf { k -> z[k][i] * a[k] } }
        val (cands, s) = search(n, l, d, zs) ?: return null
        val f1 = solveZt(n, z, cands[0]) ?: return null
        val f2 = solveZt(n, z, cands[1]) ?: return null
        return Result(f1, f2, s[0], s[1])
    }
}
