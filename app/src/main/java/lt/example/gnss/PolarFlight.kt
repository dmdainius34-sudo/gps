package lt.example.gnss

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Skrydis pro Šiaurinę žvaigždę (pietų pusrutulyje – pro pietų dangaus polių, σ Octantis): iš stebėtojo vietos tiesiai dangaus
 * poliaus kryptimi – jo aukščiu virš horizonto (≈ platuma), dešinė ranka šiaurės pusrutulyje – rytai. Kelias – pusiaujo (J2000)
 * erdvėje, todėl nuo vietos ir laiko priklauso tik tai, kaip jis atrodo iš stebėtojo horizonto. Galaktikos plokštumą kelias kerta
 * |b| ≈ 27° kampu (šiaurės polius: l = 122,9°, b = +27,1°).
 *
 * Atstumai scenoje: pradžia – prie stebėtojo (Žemės mastelis, 0,03 vnt.), Saulės sistema (3 vnt.), toliau – galaktikos masteliu
 * (upk vnt./kpc): Šiaurinė žvaigždė, plonojo disko riba, storasis diskas, galaktika po kojomis (15 kpc), „blynas“ (50 kpc).
 * Tarp žymių log(atstumas) kinta tolygiai, prie žymių – sulėtėja perpus (nesustoja).
 */
object PolarFlight {
    /** Žymių laikas (s) ir atstumas: pirmi du – scenos vienetais, kiti – kpc. */
    val T = doubleArrayOf(0.0, 3.0, 10.0, 17.0, 23.0, 31.0, 39.0)
    private val KPC = doubleArrayOf(0.0, 0.0, 0.133, 0.67, 2.2, 15.0, 50.0)
    val T_END get() = T.last()
    fun keyDist(i: Int, upk: Double) = when (i) { 0 -> 0.03; 1 -> 3.0; else -> KPC[i] * upk }

    /** Kameros atstumas nuo stebėtojo (scenos vnt.) laiku t. */
    fun dist(t: Double, upk: Double): Double {
        if (t <= 0.0) return keyDist(0, upk)
        for (i in 1 until T.size) {
            if (t <= T[i]) {
                val x = (t - T[i - 1]) / (T[i] - T[i - 1])
                val e = 0.5 * x + 0.5 * x * x * (3 - 2 * x)
                val l0 = ln(keyDist(i - 1, upk)); val l1 = ln(keyDist(i, upk))
                return exp(l0 + (l1 - l0) * e)
            }
        }
        return keyDist(T.size - 1, upk)
    }

    /** Žvilgsnio pasukimas žemyn (į galaktiką): 0 – į polių, 1 – į galaktikos centrą (12° žemiau ekrano vidurio). */
    fun turn(distKpc: Double): Double {
        val x = ((ln(distKpc) - ln(0.4)) / (ln(3.0) - ln(0.4))).coerceIn(0.0, 1.0)
        return x * x * (3 - 2 * x)
    }

    const val LY_PER_KPC = 3261.56

    /** Kampas (°), kuriuo kelias kerta galaktikos plokštumą: |b| kelio krypties. */
    fun crossingDeg(dir: DoubleArray, ngp: DoubleArray): Double =
        Math.toDegrees(asin(kotlin.math.abs(dir[0] * ngp[0] + dir[1] * ngp[1] + dir[2] * ngp[2]).coerceAtMost(1.0)))

    /** Kampas (°), kuriuo galaktika matoma iš taško p (kpc nuo centro): aukštis virš plokštumos žiūrint iš centro. */
    fun viewDeg(p: DoubleArray, ngp: DoubleArray): Double {
        val l = sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]); if (l < 1e-9) return 0.0
        return Math.toDegrees(asin(((p[0] * ngp[0] + p[1] * ngp[1] + p[2] * ngp[2]) / l).coerceIn(-1.0, 1.0)))
    }

    // ---- kryptys ir sukimai (kamera: f – žiūrėjimas, u – ekrano viršus; dešinė = f × u) ----
    fun nrm(v: DoubleArray): DoubleArray { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return if (l < 1e-12) v.copyOf() else DoubleArray(3) { v[it] / l } }
    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /** Kameros bazė (f, u) -> kvaternionas (w, x, y, z); stulpeliai: dešinė, viršus, −f. */
    fun quat(f: DoubleArray, u: DoubleArray): DoubleArray {
        val r = nrm(cross(f, u)); val uu = cross(r, f)
        val m00 = r[0]; val m01 = uu[0]; val m02 = -f[0]
        val m10 = r[1]; val m11 = uu[1]; val m12 = -f[1]
        val m20 = r[2]; val m21 = uu[2]; val m22 = -f[2]
        val tr = m00 + m11 + m22
        val q = when {
            tr > 0 -> { val s = sqrt(tr + 1.0) * 2; doubleArrayOf(0.25 * s, (m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s) }
            m00 > m11 && m00 > m22 -> { val s = sqrt(1.0 + m00 - m11 - m22) * 2; doubleArrayOf((m21 - m12) / s, 0.25 * s, (m01 + m10) / s, (m02 + m20) / s) }
            m11 > m22 -> { val s = sqrt(1.0 + m11 - m00 - m22) * 2; doubleArrayOf((m02 - m20) / s, (m01 + m10) / s, 0.25 * s, (m12 + m21) / s) }
            else -> { val s = sqrt(1.0 + m22 - m00 - m11) * 2; doubleArrayOf((m10 - m01) / s, (m02 + m20) / s, (m12 + m21) / s, 0.25 * s) }
        }
        val l = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return DoubleArray(4) { q[it] / l }
    }

    /** Kvaternionas -> (f, u). */
    fun basis(q: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val (w, x, y, z) = q.toList()
        val u = doubleArrayOf(2 * (x * y - w * z), 1 - 2 * (x * x + z * z), 2 * (y * z + w * x))
        val back = doubleArrayOf(2 * (x * z + w * y), 2 * (y * z - w * x), 1 - 2 * (x * x + y * y))
        return DoubleArray(3) { -back[it] } to u
    }

    fun slerp(a: DoubleArray, b0: DoubleArray, t: Double): DoubleArray {
        var b = b0; var d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        if (d < 0) { b = DoubleArray(4) { -b0[it] }; d = -d }
        if (d > 0.9995) { val q = DoubleArray(4) { a[it] + t * (b[it] - a[it]) }; val l = sqrt(q.sumOf { it * it }); return DoubleArray(4) { q[it] / l } }
        val th = acos(d.coerceIn(-1.0, 1.0)); val s = sin(th)
        val ka = sin((1 - t) * th) / s; val kb = sin(t * th) / s
        return DoubleArray(4) { ka * a[it] + kb * b[it] }
    }

    /**
     * Žiūrėjimas į galaktiką iš taško eye: tikslas – centras, 12° žemiau ekrano vidurio (galaktika ekrano apačioje, „bedugnė“);
     * ekrano dešinė – kiek įmanoma ta pati kaip pradžioje (r0), t. y. žvilgsnis nuleidžiamas žemyn, ne pasukamas į šoną.
     */
    fun lookAtGalaxy(eye: DoubleArray, gc: DoubleArray, r0: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val g = nrm(DoubleArray(3) { gc[it] - eye[it] })
        var r = DoubleArray(3) { r0[it] - dot(r0, g) * g[it] }
        if (sqrt(dot(r, r)) < 1e-6) r = nrm(cross(g, doubleArrayOf(0.0, 0.0, 1.0))) else r = nrm(r)
        val u = cross(r, g)
        val a = Math.toRadians(12.0)
        val f = DoubleArray(3) { cos(a) * g[it] + sin(a) * u[it] }
        return f to cross(r, f)
    }
}
