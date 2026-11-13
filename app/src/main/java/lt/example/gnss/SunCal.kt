package lt.example.gnss

import android.content.Context
import java.io.File
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan
import kotlin.math.sin
import kotlin.math.cos

/**
 * Kalibravimas pagal Saulę: Saulės padėtis (Astro.sunEq + refrakcija), prietaiso kryptis iš sukimo matricos
 * (planšetės nugarėlė −Z, projekcija į vietinę horizontalią plokštumą pagal gravitaciją) ir įrašų žurnalas.
 */
object SunCal {
    const val FILE = "saules_kalibravimai.csv"
    const val HEADER = "utc,platuma,ilguma,aukstis_elips_m,fikso_tikslumas_m,saules_az_deg,saules_aukstis_deg,prietaiso_az_deg,prietaiso_aukstis_deg," +
        "pataisa_deg,magnetometro_az_deg,deklinacija_deg,saules_vs_magnetometro_siaure_deg,pitch_deg,roll_deg,modelis,taikinys\n"
    const val MIN_EL = 0.0; const val MAX_EL = 70.0; const val WARN_DEL = 10.0
    /** Didesnė magnetometro klaida rodo stiprų iškraipymą – jo klaida nepastovi, automatinis taisymas išjungiamas. */
    const val MAG_MAX = 25.0

    /** Atmosferos refrakcija (Saemundsson), ° – pridedama prie geometrinio aukščio. */
    fun refraction(elDeg: Double): Double {
        if (elDeg < -1.0) return 0.0
        val r = 1.02 / tan(Math.toRadians(elDeg + 10.3 / (elDeg + 5.11)))   // lanko minutės
        return r / 60.0
    }

    // ---- gamtos orientyrai: Saulė, Mėnulis, Šiaurinė žvaigždė, Oriono žvaigždės ----
    class Target(val key: String, val name: String, val raJ2000: Double = Double.NaN, val decJ2000: Double = Double.NaN)
    val targets = listOf(
        Target("sun", "Saulė"), Target("moon", "Mėnulis"),
        Target("polaris", "Šiaurinė žvaigždė (Polaris)", 37.95456, 89.26411),
        Target("betelgeuse", "Betelgeizė (Orionas, raudona)", 88.79294, 7.40706),
        Target("rigel", "Rigelis (Orionas, melsva)", 78.63447, -8.20164),
        Target("bellatrix", "Belatriksė (Orionas)", 81.28276, 6.34970),
        Target("alnilam", "Alnilamas (Oriono juostos vidurys)", 84.05339, -1.20192))
    fun target(key: String) = targets.firstOrNull { it.key == key } ?: targets[0]
    fun isStar(key: String) = key != "sun" && key != "moon"

    /**
     * Mėnulis (Astronomical Almanac mažo tikslumo formulės, ~0,3°) su topocentrine paralakse (iki 1°): (azimutas, aukštis su refrakcija).
     */
    fun moonAzEl(ms: Long, lat: Double, lon: Double): Pair<Double, Double> {
        val t = Astro.daysJ2000(ms) / 36525.0
        fun sd(a: Double) = kotlin.math.sin(Math.toRadians(a))
        fun cd(a: Double) = kotlin.math.cos(Math.toRadians(a))
        val lam = 218.32 + 481267.881 * t + 6.29 * sd(135.0 + 477198.87 * t) - 1.27 * sd(259.3 - 413335.36 * t) +
            0.66 * sd(235.7 + 890534.22 * t) + 0.21 * sd(269.9 + 954397.74 * t) - 0.19 * sd(357.5 + 35999.05 * t) - 0.11 * sd(186.5 + 966404.03 * t)
        val bet = 5.13 * sd(93.3 + 483202.02 * t) + 0.28 * sd(228.2 + 960400.89 * t) - 0.28 * sd(318.3 + 6003.15 * t) - 0.17 * sd(217.6 - 407332.21 * t)
        val par = 0.9508 + 0.0518 * cd(135.0 + 477198.87 * t) + 0.0095 * cd(259.3 - 413335.36 * t) + 0.0078 * cd(235.7 + 890534.22 * t) + 0.0028 * cd(269.9 + 954397.74 * t)
        val v = Astro.eclToEq(Math.toRadians(lam), Math.toRadians(bet))
        val (az, elGeo) = Astro.altAz(v, lat, lon, ms)
        val el = elGeo - par * cd(elGeo)                                   // paralaksė: iš Žemės paviršiaus Mėnulis žemiau
        return az to el + refraction(el)
    }

    /** Orientyro (azimutas, regimasis aukštis su refrakcija), °. Žvaigždės – J2000 + precesija. */
    fun targetAzEl(key: String, ms: Long, lat: Double, lon: Double): Pair<Double, Double> {
        val t = target(key)
        return when (key) {
            "sun" -> sunAzEl(ms, lat, lon)
            "moon" -> moonAzEl(ms, lat, lon)
            else -> { val (ra, dec) = Astro.precess(t.raJ2000, t.decJ2000, ms)
                val (az, el) = Astro.altAzRaDec(ra, dec, lat, lon, ms); az to el + refraction(el) }
        }
    }

    /** Saulės (azimutas, regimasis aukštis su refrakcija), °. */
    fun sunAzEl(ms: Long, lat: Double, lon: Double): Pair<Double, Double> {
        val (az, el) = Astro.altAz(Astro.sunEq(ms), lat, lon, ms)
        return az to el + refraction(el)
    }

    /**
     * Prietaiso kryptis iš sukimo matricos R (prietaisas → pasaulis: rytai, šiaurė, aukštyn; eilutėmis):
     * planšetės nugarėlė −Z = (−R[2], −R[5], −R[8]). Azimutas – jos projekcijos į horizontalią plokštumą kryptis
     * (vertikalė – iš gravitacijos), aukštis – kampas virš horizonto. Gulint (horizontali dalis < 0,3) – viršutinis kraštas +Y.
     * Grąžina (azimutas, aukštis, ašis).
     */
    fun deviceAzEl(r: FloatArray): Triple<Double, Double, String> {
        var e = -r[2].toDouble(); var n = -r[5].toDouble(); var u = -r[8].toDouble(); var axis = "-Z"
        if (hypot(e, n) < 0.3) { e = r[1].toDouble(); n = r[4].toDouble(); u = r[7].toDouble(); axis = "+Y" }
        val az = (Math.toDegrees(atan2(e, n)) + 360.0) % 360.0
        val el = Math.toDegrees(asin((u / kotlin.math.sqrt(e * e + n * n + u * u)).coerceIn(-1.0, 1.0)))
        return Triple(az, el, axis)
    }

    fun wrap(a: Double): Double { var x = (a + 540.0) % 360.0; if (x < 0) x += 360.0; return x - 180.0 }

    class Rec(val ms: Long, val lat: Double, val lon: Double, val h: Double, val acc: Double, val sunAz: Double, val sunEl: Double,
              val devAz: Double, val devEl: Double, val corr: Double, val magAz: Double, val decl: Double, val pitch: Double, val roll: Double, val model: String,
              val target: String = "Saulė") {
        /** Saulės nustatyta tikroji šiaurė − magnetometro (su deklinacija) šiaurė, °: magnetometro tikrasis azimutas − Saulės azimutas. */
        val magDiff get() = if (magAz.isNaN()) Double.NaN else wrap(magAz + decl - sunAz)
        fun line(): String {
            fun f(v: Double, d: Int) = if (v.isNaN()) "" else "%.${d}f".format(Locale.US, v)
            val t = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(ms))
            return listOf(t, f(lat, 8), f(lon, 8), f(h, 2), f(acc, 1), f(sunAz, 3), f(sunEl, 3), f(devAz, 2), f(devEl, 2), f(corr, 2),
                f(magAz, 2), f(decl, 2), f(magDiff, 2), f(pitch, 1), f(roll, 1), model.replace(',', ' '), target.replace(',', ' ')).joinToString(",") + "\n"
        }
    }

    fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    /** Pridedama eilutė (istorija išlaikoma). */
    @Synchronized fun log(ctx: Context, r: Rec) {
        val f = file(ctx)
        if (!f.exists() || f.length() == 0L) f.writeText(HEADER)
        else {
            // senas failas be stulpelio „taikinys“ – papildomas (seni įrašai – Saulė)
            val ls = f.readLines()
            if (ls.isNotEmpty() && ls[0].trim() != HEADER.trim())
                f.writeText(HEADER + ls.drop(1).filter { it.isNotBlank() }.joinToString("") { (if (it.split(",").size < 17) "$it,Saulė" else it) + "\n" })
        }
        val ln = r.line()
        if (lastLine(ctx)?.trim() == ln.trim()) return            // dvigubas paspaudimas – ta pati eilutė neįrašoma
        f.appendText(ln)
    }

    /** Žurnalo įrašas, nuskaitytas iš CSV (eilutės numeris – trynimui). */
    class Entry(val line: Int, val ms: Long, val lat: Double, val lon: Double, val sunEl: Double, val devEl: Double,
                val corr: Double, val magAz: Double, val magDiff: Double, val raw: String, val sunAz: Double = Double.NaN, val target: String = "Saulė") {
        /** Geras: prietaiso kampas sutapo su Saulės (≤ 10°), nugarėlė ne per stačiai (−Z azimutas patikimas), magnetometras buvo. */
        val good get() = kotlin.math.abs(devEl - sunEl) <= WARN_DEL && kotlin.math.abs(devEl) < 60.0 && !magDiff.isNaN()
        /** Magnetometras stipriai iškraipytas (geležis, įkrovimo srovė) – automatiniam taisymui netinka. */
        val magDistorted get() = !magDiff.isNaN() && kotlin.math.abs(magDiff) > MAG_MAX

    }

    @Synchronized fun entries(ctx: Context): List<Entry> = try {
        val f = file(ctx); if (!f.exists()) emptyList() else {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            f.readLines().mapIndexedNotNull { i, l ->
                if (l.isBlank() || l.startsWith("utc,")) return@mapIndexedNotNull null
                val c = l.split(","); if (c.size < 16) return@mapIndexedNotNull null
                fun d(k: Int) = c[k].toDoubleOrNull() ?: Double.NaN
                Entry(i, try { fmt.parse(c[0])!!.time } catch (_: Exception) { 0L }, d(1), d(2), d(6), d(8), d(9), d(10), d(12), l, d(5),
                    c.getOrNull(16)?.takeIf { it.isNotBlank() } ?: "Saulė")
            }
        }
    } catch (_: Exception) { emptyList() }

    /** Pažymėtų eilučių trynimas (pagal eilutės numerį faile). */
    @Synchronized fun delete(ctx: Context, lines: Set<Int>) {
        val f = file(ctx); if (!f.exists()) return
        val keep = f.readLines().filterIndexed { i, l -> i !in lines && l.isNotBlank() }
        f.writeText(keep.joinToString("\n") + "\n")
    }

    /** Paskutinis geras kalibravimas iki 100 m nuo (lat, lon) arba null. */
    fun nearestGood(ctx: Context, lat: Double, lon: Double): Entry? = entries(ctx).filter { it.good &&
        kotlin.math.hypot((it.lat - lat) * 111_320.0, (it.lon - lon) * 111_320.0 * kotlin.math.cos(Math.toRadians(lat))) <= 100.0 }.maxByOrNull { it.ms }

    /**
     * Ar magnetometro klaida šioje vietoje pastovi: ≥ 2 geri įrašai iki 100 m ir jų klaidų sklaida ≤ 8°.
     * Kabinoje su plienu ar įkraunant ji per minutę gali pasikeisti 20°+ – tada automatinis taisymas pagal magnetometrą žalingas.
     */
    fun magStable(ctx: Context, lat: Double, lon: Double): Boolean {
        val g = entries(ctx).filter { it.good && !it.magDistorted &&
            kotlin.math.hypot((it.lat - lat) * 111_320.0, (it.lon - lon) * 111_320.0 * kotlin.math.cos(Math.toRadians(lat))) <= 100.0 }
        if (g.size < 2) return false
        val m = g.map { it.magDiff }
        return m.max() - m.min() <= 8.0
    }

    /** Paskutinis bet koks (ir ⚠) įrašas iki 100 m, kurio azimutas tinkamas (nugarėlė ne per stačiai, magnetometras buvo). */
    fun nearestAny(ctx: Context, lat: Double, lon: Double): Entry? = entries(ctx).filter { kotlin.math.abs(it.devEl) < 60.0 && !it.magDiff.isNaN() &&
        kotlin.math.hypot((it.lat - lat) * 111_320.0, (it.lon - lon) * 111_320.0 * kotlin.math.cos(Math.toRadians(lat))) <= 100.0 }.maxByOrNull { it.ms }

    /**
     * Tikra prietaiso (−Z) kryptis iš magnetometro, pataisyta magnetometro klaida iš žurnalo:
     * tikrasis az = magnetinis az + deklinacija − (klaida kalibruojant). Grąžina null, jei nėra gero įrašo.
     */
    fun trueFromMag(e: Entry, magAz: Double, decl: Double): Double = ((magAz + decl - e.magDiff) % 360.0 + 360.0) % 360.0

    // ---- automatinis taisymas: nuokrypis tarp vaizdo krypties ir magnetometro + žurnalo, stebimas 2 min. ----
    private val hist = ArrayDeque<Pair<Long, Double>>()
    @Volatile var autoFix = true
    @Volatile var lastFixMs = 0L
        private set
    @Volatile var lastFixDeg = 0.0
        private set
    @Volatile var lastDiff = Double.NaN
        private set

    /** Kas sekundę: d = vaizdo kryptis − tikra kryptis (iš magnetometro ir žurnalo). Pataisa – kai 2 min. stabilu (sklaida < 4°) ir |d| > 5°. */
    @Synchronized fun watch(ms: Long, d: Double, apply: (Double) -> Unit) {
        lastDiff = d
        hist.addLast(ms to d)
        while (hist.isNotEmpty() && ms - hist.first().first > 120_000L) hist.removeFirst()
        if (!autoFix || hist.size < 60 || ms - lastFixMs < 300_000L) return
        val r = hist.map { Math.toRadians(it.second) }
        val sx = r.sumOf { sin(it) }; val cx = r.sumOf { cos(it) }
        val mean = Math.toDegrees(atan2(sx, cx)); val rl = kotlin.math.sqrt(sx * sx + cx * cx) / r.size
        if (rl < 0.9976 || kotlin.math.abs(mean) < 5.0) return             // 0,9976 ≈ sklaida 4°
        lastFixMs = ms; lastFixDeg = mean; hist.clear()
        apply(mean)
    }

    fun lastLine(ctx: Context): String? = try {
        file(ctx).takeIf { it.exists() }?.readLines()?.lastOrNull { it.isNotBlank() && !it.startsWith("utc,") }
    } catch (_: Exception) { null }
}
