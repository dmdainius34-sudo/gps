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

    /** Slenksčio t tinkamumas: 2 – perteklius ≥ 3 ir PDOP ≤ 3; 1 – perteklius ≥ 1 ir PDOP ≤ 6 (padėtis dar gera); 0 – per mažai. */
    fun level(t: Double, sats: List<S>): Int {
        val s = sats.filter { it.cn0 >= t && it.elDeg >= 10.0 }
        val red = s.size - 3 - s.map { it.sys }.distinct().size
        if (red < 1) return 0
        val p = pdop(s)
        return if (red >= 3 && p <= 3.0) 2 else if (p <= 6.0) 1 else 0
    }

    /** Aukščiausias slenkstis su 2 lygiu; jei tokio nėra – su 1 lygiu; kitaip – žemiausias. */
    fun target(sats: List<S>): Double {
        for (need in intArrayOf(2, 1)) {
            var t = HI
            while (t >= LO) { if (level(t, sats) >= need) return t; t -= 1.0 }
        }
        return LO
    }

    /** PDOP su vienu bendru laikrodžiu (apytiksliai – geometrijai įvertinti). */
    fun pdop(s: List<S>): Double = dop(s)?.let { sqrt(it[0] + it[1] + it[2]) } ?: Double.MAX_VALUE

    /** VDOP (vietinis zenitas) su vienu bendru laikrodžiu. */
    fun vdop(s: List<S>): Double = dop(s)?.let { sqrt(it[2]) } ?: Double.MAX_VALUE

    /** (QEE, QNN, QUU) – kovariacijos įstrižainė ENU ašyse; null – geometrija išsigimusi. */
    private fun dop(s: List<S>): DoubleArray? {
        if (s.size < 4) return null
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
            if (abs(a[p][c]) < 1e-12) return null
            val t = a[c]; a[c] = a[p]; a[p] = t
            val d = a[c][c]; for (j in 0..7) a[c][j] /= d
            for (k in 0..3) if (k != c) { val f = a[k][c]; if (f != 0.0) for (j in 0..7) a[k][j] -= f * a[c][j] }
        }
        return doubleArrayOf(a[0][4].coerceAtLeast(0.0), a[1][5].coerceAtLeast(0.0), a[2][6].coerceAtLeast(0.0))
    }

    private var upN = 0
    private var downN = 0

    /**
     * Kartą per sekundę, kai auto. Slenkstis turi būti ramus (kiekvienas pakeitimas keičia palydovų rinkinį ir pastumia padėtį):
     *  - iš karto žemyn – tik kai dabartinis slenkstis nebeleidžia geros padėties (0 lygis);
     *  - aukštyn – kai ≥ 20 s iš eilės leidžiama bent 2 dB aukščiau, po 1 dB (toliau kas 5 s);
     *  - lėtai žemyn – kai ≥ 30 s dabartinis tik 1 lygio, o žemiau yra 2 lygio, po 1 dB (toliau kas 5 s).
     * Grąžina true, jei slenkstis pasikeitė.
     */
    @Synchronized fun autoStep(sats: List<S>): Boolean {
        if (!auto) { upN = 0; downN = 0; return false }
        val t = target(sats)
        val cur = level(min, sats)
        var nw = min
        if (cur == 0 && t < min) { nw = t; upN = 0; downN = 0 }
        else {
            upN = if (t >= min + 2.0) upN + 1 else 0
            downN = if (cur < 2 && t < min) downN + 1 else 0
            if (upN >= 20) { nw = min + 1.0; upN = 15 }
            else if (downN >= 30) { nw = min - 1.0; downN = 25 }
        }
        if (nw == min) return false
        AppDiag.thr(min, nw, sats.count { it.cn0 >= nw && it.elDeg >= 10.0 })
        min = nw
        return true
    }
}
