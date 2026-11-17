package lt.example.gnss

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Mažiausias signalo stiprumas (dB-Hz) programos sprendiniuose (SP3, RTK, realus tikslumas); lusto padėties neveikia. */
object CnoMask {
    @Volatile var min = 20.0
    /** Automatinis slenkstis (NLO šliaužiklis juda pats); pajudinus ranka – išjungiamas. */
    @Volatile var auto = true
    const val LO = 15.0
    const val HI = 40.0

    class S(val sys: String, val cn0: Double, val elDeg: Double, val azDeg: Double)

    /**
     * Aukščiausias slenkstis, prie kurio dar lieka pakankamai palydovų, išsidėsčiusių po dangų:
     * pirmiausia – perteklius ≥ 3 (palydovų minus 3 koordinatės minus po laikrodį kiekvienai konsteliacijai) ir PDOP ≤ 3;
     * jei tokio nėra – perteklius ≥ 1 ir PDOP ≤ 6; kitaip – žemiausias.
     */
    fun target(sats: List<S>): Double {
        fun ok(t: Double, minRed: Int, maxPdop: Double): Boolean {
            val s = sats.filter { it.cn0 >= t && it.elDeg >= 10.0 }
            val k = s.map { it.sys }.distinct().size
            if (s.size - 3 - k < minRed) return false
            return pdop(s) <= maxPdop
        }
        for (pass in 0..1) {
            var t = HI
            while (t >= LO) {
                if (if (pass == 0) ok(t, 3, 3.0) else ok(t, 1, 6.0)) return t
                t -= 1.0
            }
        }
        return LO
    }

    /** PDOP su vienu bendru laikrodžiu (apytiksliai – geometrijai įvertinti). */
    fun pdop(s: List<S>): Double {
        if (s.size < 4) return Double.MAX_VALUE
        val a = Array(4) { DoubleArray(8) }
        for (p in s) {
            val el = Math.toRadians(p.elDeg); val az = Math.toRadians(p.azDeg)
            val g = doubleArrayOf(-cos(el) * sin(az), -cos(el) * cos(az), -sin(el), 1.0)
            for (i in 0..3) for (j in 0..3) a[i][j] += g[i] * g[j]
        }
        for (i in 0..3) a[i][4 + i] = 1.0
        for (c in 0..3) {
            var p = c
            for (k in c + 1..3) if (abs(a[k][c]) > abs(a[p][c])) p = k
            if (abs(a[p][c]) < 1e-12) return Double.MAX_VALUE
            val t = a[c]; a[c] = a[p]; a[p] = t
            val d = a[c][c]; for (j in 0..7) a[c][j] /= d
            for (k in 0..3) if (k != c) { val f = a[k][c]; if (f != 0.0) for (j in 0..7) a[k][j] -= f * a[c][j] }
        }
        return sqrt((a[0][4] + a[1][5] + a[2][6]).coerceAtLeast(0.0))
    }

    private val hist = ArrayDeque<Double>()

    /**
     * Kartą per sekundę, kai auto: slenkstis nukrenta iš karto (kad padėtis nedingtų), o kyla tik kai ~10 s iš eilės
     * leidžiama aukštesnis – po 1 dB per sekundę. Grąžina true, jei slenkstis pasikeitė.
     */
    @Synchronized fun autoStep(sats: List<S>): Boolean {
        if (!auto) { hist.clear(); return false }
        val t = target(sats)
        hist.addLast(t); while (hist.size > 10) hist.removeFirst()
        val lo = hist.minOrNull() ?: t
        val nw = when {
            t < min -> t
            hist.size >= 10 && lo > min -> minOf(min + 1.0, lo)
            else -> min
        }
        if (nw == min) return false
        min = nw
        return true
    }
}
