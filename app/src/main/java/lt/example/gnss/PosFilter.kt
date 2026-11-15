package lt.example.gnss

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Programos sprendinio filtras prieš perduodant kitoms programoms.
 * Stovint – visų sekundės sprendinių vidurkis lange (iki 10 min.) su išsišokusių atmetimu (> 3 × mediana nuokrypio):
 * taškas nusistovi ir ilgainiui tikslėja. Judant – sklandus sekimas (α = 0,5) su neįmanomų šuolių atmetimu.
 * Tikslumas – ne mažesnis už pusę realaus (atspindžių poslinkio vidurkinimas nepanaikina) ir už vidurkio sklaidą.
 */
object PosFilter {
    private class P(val n: Double, val e: Double, val h: Double)
    private var lat0 = Double.NaN; private var lon0 = Double.NaN
    private val win = ArrayDeque<P>()
    private var sn = Double.NaN; private var se = 0.0; private var sh = 0.0; private var lastMs = 0L
    private const val M = 111_320.0

    @Synchronized fun reset() { win.clear(); sn = Double.NaN; lat0 = Double.NaN }

    /** Grąžina (platuma, ilguma, aukštis, tikslumas m). */
    @Synchronized fun step(ms: Long, lat: Double, lon: Double, h: Double, real: Double, moving: Boolean): DoubleArray {
        if (lat0.isNaN() || ms - lastMs > 30_000L) { lat0 = lat; lon0 = lon; win.clear(); sn = Double.NaN }
        val k = M * cos(Math.toRadians(lat0))
        val p = P((lat - lat0) * M, (lon - lon0) * k, h)
        val dt = ((ms - lastMs) / 1000.0).coerceIn(0.2, 30.0); lastMs = ms
        val out: DoubleArray
        if (moving) {
            win.clear()
            if (sn.isNaN() || sqrt((p.n - sn).let { it * it } + (p.e - se).let { it * it }) > 50.0 * dt + 3 * real) { sn = p.n; se = p.e; sh = p.h }
            else { sn += 0.5 * (p.n - sn); se += 0.5 * (p.e - se); sh += 0.5 * (p.h - sh) }
            out = doubleArrayOf(sn, se, sh, real)
        } else {
            win.addLast(p); while (win.size > 600) win.removeFirst()
            val mn = win.map { it.n }.sorted()[win.size / 2]; val me = win.map { it.e }.sorted()[win.size / 2]
            val d = win.map { sqrt((it.n - mn).let { x -> x * x } + (it.e - me).let { x -> x * x }) }
            val mad = d.sorted()[d.size / 2].coerceAtLeast(0.5)
            val ins = win.filterIndexed { i, _ -> d[i] <= 3 * mad }
            val an = ins.sumOf { it.n } / ins.size; val ae = ins.sumOf { it.e } / ins.size; val ah = ins.sumOf { it.h } / ins.size
            val rms = sqrt(ins.sumOf { (it.n - an).let { x -> x * x } + (it.e - ae).let { x -> x * x } } / ins.size)
            // gretimų sekundžių klaidos koreliuotos (~30 s), todėl nepriklausomų mėginių ~ n/30
            val acc = maxOf(0.5 * real, rms / sqrt(maxOf(1.0, ins.size / 30.0)), 1.0)
            sn = an; se = ae; sh = ah
            out = doubleArrayOf(an, ae, ah, acc)
        }
        return doubleArrayOf(lat0 + out[0] / M, lon0 + out[1] / k, out[2], out[3])
    }
}
