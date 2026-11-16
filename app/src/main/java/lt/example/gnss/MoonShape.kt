package lt.example.gnss

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Mėnulio atpažinimas kadre pagal tai, kaip jis TURI atrodyti dabar:
 * dydis (≈ 0,52°), apšviesta dalis (fazė iš Saulės ir Mėnulio krypčių) ir pjautuvo iškiloji pusė, atsukta į Saulę.
 * Kryptis į Saulę kadre skaičiuojama iš gravitacijos („aukštyn“) – kalibruojamas azimutas jai įtakos neturi.
 */
object MoonShape {
    const val DIAM_DEG = 0.52

    /** Laukiamas vaizdas: skersmuo taškais, apšviesta dalis 0..1, kryptis į Saulę kadre (vienetinis, x – dešinėn, y – žemyn). */
    class Expect(val diamPx: Double, val k: Double, val sunDx: Double, val sunDy: Double, val flat: Double = 1.0)

    class Result(val ok: Boolean, val why: String, val extentPx: Double, val areaFrac: Double, val dirErrDeg: Double)

    /** Apšviesta dalis iš kampinio atstumo tarp Saulės ir Mėnulio (rad). */
    fun illuminated(elongRad: Double) = (1 - cos(elongRad)) / 2

    /** Saulės kryptis dangaus plokštumoje ties Mėnuliu, išreikšta bazėje (aukštyn, e = m × aukštyn): (a, c). Vektoriai ENU, vienetiniai. */
    fun sunInUpBasis(m: DoubleArray, s: DoubleArray): Pair<Double, Double> {
        val z = doubleArrayOf(0.0, 0.0, 1.0)
        val up = tangent(z, m) ?: return 0.0 to 0.0
        val e = cross(m, up)
        val st = tangent(s, m) ?: return 0.0 to 0.0
        return dot(st, up) to dot(st, e)
    }

    fun tangent(v: DoubleArray, m: DoubleArray): DoubleArray? {
        val d = dot(v, m); val t = DoubleArray(3) { v[it] - d * m[it] }
        val l = sqrt(dot(t, t)); return if (l < 1e-9) null else DoubleArray(3) { t[it] / l }
    }
    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /**
     * g(x, y) – ryškumas 0..255 pilnos raiškos kadre (w × h); (cx, cy) – rastos dėmės centras.
     * Apšviesti taškai – virš fono + 35 % (smailė − fonas) lange 1,2 skersmens spinduliu.
     */
    fun check(g: (Int, Int) -> Int, w: Int, h: Int, cx: Double, cy: Double, ex: Expect): Result {
        val r = maxOf(8, (1.2 * ex.diamPx).toInt())
        val x0 = (cx - r).toInt().coerceAtLeast(0); val x1 = (cx + r).toInt().coerceAtMost(w - 1)
        val y0 = (cy - r).toInt().coerceAtLeast(0); val y1 = (cy + r).toInt().coerceAtMost(h - 1)
        if (x1 - x0 < 4 || y1 - y0 < 4) return Result(false, "per arti kadro krašto", 0.0, 0.0, 0.0)
        val border = ArrayList<Int>()
        for (x in x0..x1) { border.add(g(x, y0)); border.add(g(x, y1)) }
        for (y in y0..y1) { border.add(g(x0, y)); border.add(g(x1, y)) }
        border.sort(); val bg = border[border.size / 2].toDouble()
        var peak = 0; for (y in y0..y1) for (x in x0..x1) peak = maxOf(peak, g(x, y))
        if (peak - bg < 8) return Result(false, "per mažas kontrastas", 0.0, 0.0, 0.0)
        val thr = bg + 0.35 * (peak - bg)
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (y in y0..y1) for (x in x0..x1) if (g(x, y) >= thr) { xs.add(x); ys.add(y) }
        val n = xs.size
        if (n < 3) return Result(false, "per maža dėmė", 0.0, 0.0, 0.0)
        // didžiausias ilgis (36 kryptys) ir pagrindinė ašis
        var ext = 0.0; var axA = 0.0
        for (k in 0 until 36) {
            val a = PI * k / 36; val ca = cos(a); val sa = sin(a)
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (i in 0 until n) { val p = xs[i] * ca + ys[i] * sa; if (p < lo) lo = p; if (p > hi) hi = p }
            if (hi - lo + 1 > ext) { ext = hi - lo + 1; axA = a }
        }
        val areaFrac = n / (PI * ex.diamPx * ex.diamPx / 4 * ex.flat)      // prie horizonto diskas suplotas refrakcijos
        if (ext < 0.6 * ex.diamPx) return Result(false, "per maža dėmė (%.0f tšk., Mėnulis ~%.0f)".format(java.util.Locale.US, ext, ex.diamPx), ext, areaFrac, 0.0)
        if (ext > 1.7 * ex.diamPx) return Result(false, "per didelė dėmė (%.0f tšk., Mėnulis ~%.0f)".format(java.util.Locale.US, ext, ex.diamPx), ext, areaFrac, 0.0)
        if (areaFrac > 2.0 * ex.k + 0.25) return Result(false, "per daug apšviesta fazei (%.0f %% vietoj ~%.0f %%)".format(java.util.Locale.US, 100 * areaFrac, 100 * ex.k), ext, areaFrac, 0.0)
        // pjautuvas: iškiloji pusė (nuo ragų vidurio į masės centrą) turi žiūrėti į Saulę
        var dirErr = 0.0
        if (ex.k < 0.45 && hypot(ex.sunDx, ex.sunDy) > 0.1) {
            val ca = cos(axA); val sa = sin(axA)
            var iLo = 0; var iHi = 0
            for (i in 0 until n) { val p = xs[i] * ca + ys[i] * sa; if (p < xs[iLo] * ca + ys[iLo] * sa) iLo = i; if (p > xs[iHi] * ca + ys[iHi] * sa) iHi = i }
            val mx = (xs[iLo] + xs[iHi]) / 2.0; val my = (ys[iLo] + ys[iHi]) / 2.0
            val gx = xs.average(); val gy = ys.average()
            val vx = gx - mx; val vy = gy - my
            if (hypot(vx, vy) > 0.5) {
                dirErr = Math.toDegrees(abs(atan2(vx * ex.sunDy - vy * ex.sunDx, vx * ex.sunDx + vy * ex.sunDy)))
                if (dirErr > 50.0) return Result(false, "pjautuvas atsuktas ne į Saulę (%.0f°)".format(java.util.Locale.US, dirErr), ext, areaFrac, dirErr)
            }
        }
        return Result(true, "atpažinta: Mėnulis (%.0f tšk., apšviesta ~%.0f %%%s)".format(java.util.Locale.US, ext, 100 * ex.k,
            if (ex.k < 0.45) ", pjautuvas į Saulę ±%.0f°".format(java.util.Locale.US, dirErr) else ""), ext, areaFrac, dirErr)
    }
}
