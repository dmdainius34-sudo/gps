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
    private var farN = 0
    /** Judant atmesti tolimi sprendiniai iš eilės – persikėlimas priimamas tik jei jie sutampa tarpusavyje (ne išsibarstę atspindžiai). */
    private val far = ArrayList<P>()
    private var wasMoving = false
    /** Judant: kiek naujas sprendinys pastumia išlygintą vietą (likusi dalis – pagal Doplerio greitį). */
    @Volatile var moveGain = 0.5
    /** Saulės audra (SpaceWeather): jonosfera neramiai – judant naujas sprendinys pastumia perpus mažiau. */
    @Volatile var storm = false
    private const val M = 111_320.0

    @Synchronized fun reset() { win.clear(); sn = Double.NaN; lat0 = Double.NaN; far.clear(); farN = 0; wasMoving = false }

    /** Grąžina (platuma, ilguma, aukštis, tikslumas m). */
    /** vel – (šiaurės, rytų) greitis m/s iš Doplerio: judant ankstesnė vieta pirmiau pastumiama juo, kitaip išlyginta vieta vėluoja (automobilyje – dešimtis m). */
    @Synchronized fun step(ms: Long, lat: Double, lon: Double, h: Double, real: Double, moving: Boolean, vel: DoubleArray? = null): DoubleArray {
        if (lat0.isNaN() || ms - lastMs > 30_000L) { lat0 = lat; lon0 = lon; win.clear(); sn = Double.NaN }
        val k = M * cos(Math.toRadians(lat0))
        val p = P((lat - lat0) * M, (lon - lon0) * k, h)
        val dt = ((ms - lastMs) / 1000.0).coerceIn(0.2, 30.0); lastMs = ms
        val out: DoubleArray
        if (moving) {
            win.clear()
            if (vel != null && !sn.isNaN()) { sn += vel[0] * dt; se += vel[1] * dt }
            val dist = if (sn.isNaN()) 0.0 else sqrt((p.n - sn).let { it * it } + (p.e - se).let { it * it })
            if (sn.isNaN()) { sn = p.n; se = p.e; sh = p.h; farN = 0; far.clear() }
            else if (dist > 50.0 * dt + 3 * real) {
                // neįmanomas šuolis: atmetamas (vieta lieka, kur buvo). Tikras persikėlimas – tik kai 3 tolimi iš eilės sutampa tarpusavyje
                // (≤ 10 m + 2σ); išsibarstę atspindžiai (kabinoje šokinėja po šimtus m) nebeperkelia (buvo – 345 m šuolis išlygintoje vietoje)
                farN++; far.add(p); while (far.size > 3) far.removeAt(0)
                if (far.size == 3) {
                    val cn = far.sumOf { it.n } / 3; val ce = far.sumOf { it.e } / 3
                    val spread = far.maxOf { sqrt((it.n - cn).let { x -> x * x } + (it.e - ce).let { x -> x * x }) }
                    if (spread <= 10.0 + 2 * real) { sn = cn; se = ce; sh = far.sumOf { it.h } / 3; farN = 0; far.clear() }
                }
            } else {
                farN = 0; far.clear()
                // didelis nuokrypis sveriamas mažiau (Huberio svoris): atspindžio „šuolis“ pastumia tik dalį
                val lim = 5.0 + 3 * real
                // lėtai (kranas sukasi, einama; < 3 m/s) – stipresnis išlyginimas: kabinos atspindžių triukšmas didesnis už tikrą judesį;
                // greitai (automobilis) – 0,5, kad nevėluotų (GSDC: kabinoje šuolių > 5 m 165 → 0, automobilyje p95 nepablogėja)
                val spd = vel?.let { sqrt(it[0] * it[0] + it[1] * it[1]) }
                val g0 = if (spd != null && spd < 3.0) minOf(moveGain, 0.2) else moveGain
                val g = (if (dist <= lim) g0 else g0 * lim / dist) * (if (storm) 0.5 else 1.0)
                sn += g * (p.n - sn); se += g * (p.e - se); sh += 0.5 * (p.h - sh)
            }
            out = doubleArrayOf(sn, se, sh, real)
        } else {
            // sustojus po judėjimo – langas pradedamas nuo išlygintos vietos (5 jos kopijos), kad pirmas sprendinys (gal atspindys) neperkeltų taško
            if (wasMoving && !sn.isNaN()) { win.clear(); repeat(5) { win.addLast(P(sn, se, sh)) } }
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
        wasMoving = moving
        return doubleArrayOf(lat0 + out[0] / M, lon0 + out[1] / k, out[2], out[3])
    }
}
