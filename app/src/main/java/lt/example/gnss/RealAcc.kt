package lt.example.gnss

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Realus horizontalus tikslumas (1σ, m) iš naudojamų palydovų išsidėstymo ir signalo stiprumo – lusto įvertis to neįskaito.
 * Kiekvienam palydovui σ = 2 m · 10^((40 − C/N0)/20) (40 dB-Hz → 2 m, 30 → 6 m, 25 → 11 m, 20 → 20 m), svertinis DOP;
 * jei visi palydovai vienoje pusėje (didžiausias azimuto tarpas > 180°), nepastebimi atspindžiai ir poslinkis – papildomas daugiklis.
 */
object RealAcc {
    fun sigma(cn0: Double) = (2.0 * 10.0.pow((40.0 - cn0) / 20.0)).coerceIn(2.0, 40.0)

    /** az, el – laipsniais; grąžina NaN, jei per mažai palydovų. */
    fun horizontal(az: DoubleArray, el: DoubleArray, cn0: DoubleArray): Double {
        val n = az.size; if (n < 4) return Double.NaN
        val a = Array(4) { DoubleArray(4) }
        for (i in 0 until n) {
            val e = Math.toRadians(el[i]); val z = Math.toRadians(az[i])
            val h = doubleArrayOf(-cos(e) * sin(z), -cos(e) * cos(z), -sin(e), 1.0)
            val w = 1.0 / sigma(cn0[i]).pow(2)
            for (r in 0..3) for (c in 0..3) a[r][c] += w * h[r] * h[c]
        }
        val inv = invert(a) ?: return Double.NaN
        val hs = sqrt((inv[0][0] + inv[1][1]).coerceAtLeast(0.0))
        val s = az.sorted(); var gap = 360.0 - s.last() + s.first()
        for (i in 1 until s.size) gap = maxOf(gap, s[i] - s[i - 1])
        return hs * (1.0 + ((gap - 180.0) / 90.0).coerceAtLeast(0.0))
    }

    private fun invert(m: Array<DoubleArray>): Array<DoubleArray>? {
        val n = m.size; val a = Array(n) { r -> DoubleArray(2 * n) { c -> if (c < n) m[r][c] else if (c - n == r) 1.0 else 0.0 } }
        for (col in 0 until n) {
            val p = (col until n).maxByOrNull { kotlin.math.abs(a[it][col]) }!!
            if (kotlin.math.abs(a[p][col]) < 1e-12) return null
            val t = a[p]; a[p] = a[col]; a[col] = t
            val d = a[col][col]; for (c in 0 until 2 * n) a[col][c] /= d
            for (r in 0 until n) if (r != col) { val f = a[r][col]; if (f != 0.0) for (c in 0 until 2 * n) a[r][c] -= f * a[col][c] }
        }
        return Array(n) { r -> DoubleArray(n) { c -> a[r][c + n] } }
    }
}
