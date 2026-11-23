package lt.example.gnss

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Prietaiso kryptis iš palydovų signalų stiprumo: kabinos žemėlapis (C/N0 prietaiso atžvilgiu) sukamas per 360°
 * ir lyginamas su dabartiniais signalais (palydovų azimutai tikri – iš efemeridžių). Geriausias posūkis – kryptis.
 * Aukščio įtaka (aukštesni palydovai stipresni) pašalinama iš abiejų pusių (dalinė koreliacija).
 * Pastaba: žemėlapis mokomas vaizdo kryptimi, todėl ši kryptis yra toje pačioje sistemoje – ji aptinka kompaso / giroskopo
 * nuokrypį nuo mokymosi meto; absoliuti tik tada, kai žemėlapis mokytas po kalibravimo (Saulė, Polaris).
 */
object SignalHeading {
    class Est(val ms: Long, val deg: Double, val corr: Double, val margin: Double, val n: Int, val scores: DoubleArray = DoubleArray(0))
    @Volatile var last: Est? = null
        private set
    private val hist = ArrayDeque<Pair<Est, Double>>()          // (įvertis, vaizdo kryptis)
    @Volatile var autoFix = true
    /** Kabinos žemėlapis išvalytas po krypties patikimumo įvedimo (mokytas tik patikima kryptimi) – tik tada pataisos pagal signalus leidžiamos. */
    @Volatile var mapClean = false
    @Volatile var lastFixMs = 0L
        private set
    @Volatile var lastFixDeg = 0.0
        private set

    private fun resid(y: DoubleArray, x: DoubleArray): DoubleArray {
        val n = y.size; val mx = x.average(); val my = y.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) { sxy += (x[i] - mx) * (y[i] - my); sxx += (x[i] - mx) * (x[i] - mx) }
        val b = if (sxx > 1e-9) sxy / sxx else 0.0
        return DoubleArray(n) { y[it] - my - b * (x[it] - mx) }
    }

    private fun corr(a: DoubleArray, b: DoubleArray): Double {
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i] }
        return if (aa < 1e-9 || bb < 1e-9) 0.0 else ab / sqrt(aa * bb)
    }

    /** Įvertis iš vieno palydovų sąrašo: (kryptis, koreliacija, atotrūkis nuo kito pikso, palydovų). */
    fun estimate(sats: List<Sat>, map: SkyMap = CabinMap.cabin): Est? {
        val use = sats.filter { it.elev >= 5f && it.cn0 > 0f }
        if (use.size < 6) return null
        val scores = DoubleArray(180); val counts = IntArray(180)
        for (k in 0 until 180) {
            val h = k * 2.0
            val o = ArrayList<Double>(); val e = ArrayList<Double>(); val se = ArrayList<Double>()
            for (s in use) { val m = map.meanCn0(s.az - h, s.elev.toDouble()) ?: continue
                o.add(s.cn0.toDouble()); e.add(m); se.add(sin(Math.toRadians(s.elev.toDouble()))) }
            counts[k] = o.size
            if (o.size < 6) { scores[k] = -2.0; continue }
            val x = se.toDoubleArray()
            scores[k] = corr(resid(o.toDoubleArray(), x), resid(e.toDoubleArray(), x))
        }
        val best = scores.indices.maxByOrNull { scores[it] } ?: return null
        if (scores[best] <= -1.5) return null
        // antras geriausias pikas ne arčiau kaip 30° – dviprasmiškumo matas
        val second = scores.indices.filter { val d = kotlin.math.abs(((it - best) * 2 + 540) % 360 - 180); d >= 30 }.maxOfOrNull { scores[it] } ?: -1.0
        return Est(System.currentTimeMillis(), best * 2.0, scores[best], scores[best] - second, counts[best], scores)
    }

    /** Kas sekundę: įvertis, istorija (2 min.) ir (pasirinktinai) šiaurės pataisa, kai įvertis ilgai stabilus ir patikimas. */
    @Synchronized fun update(sats: List<Sat>, viewHeading: Double?, applyFix: (Double) -> Unit) {
        val e = estimate(sats) ?: return
        last = e
        if (viewHeading == null) return
        hist.addLast(e to viewHeading)
        while (hist.isNotEmpty() && e.ms - hist.first().first.ms > 120_000L) hist.removeFirst()
        val good = hist.filter { it.first.corr >= 0.6 && it.first.margin >= 0.15 }
        // (kabinos žemėlapio „švarumo“ sąlyga nebetaikoma – kokybę užtikrina koreliacija, atotrūkis ir 2 min. stabilumas)
        if (!autoFix || good.size < 60 || e.ms - lastFixMs < 300_000L) return
        // skirtumas „vaizdo kryptis − signalų kryptis“: vidurkis ir sklaida
        val d = good.map { Math.toRadians(wrap(it.second - it.first.deg)) }
        val sx = d.sumOf { sin(it) }; val cx = d.sumOf { cos(it) }
        val mean = Math.toDegrees(atan2(sx, cx)); val rl = sqrt(sx * sx + cx * cx) / d.size
        if (rl < 0.97 || kotlin.math.abs(mean) < 5.0) return         // sklaida > ~14° arba nuokrypis mažas – nekeičiama
        lastFixMs = e.ms; lastFixDeg = mean; hist.clear()
        applyFix(mean)
    }

    /** Paskutinių 2 min. įverčiai (radaro indikatoriui). */
    @Synchronized fun recent(): List<Est> = hist.map { it.first }

    private fun wrap(a: Double): Double { var x = (a + 540.0) % 360.0; if (x < 0) x += 360.0; return x - 180.0 }

    fun text(viewHeading: Double?): String {
        val e = last ?: return "Kryptis iš signalų: dar nėra (reikia kabinos žemėlapio ir ≥ 6 palydovų su žinomais langeliais)"
        val q = when { e.corr >= 0.6 && e.margin >= 0.15 -> "patikima"; e.corr >= 0.4 -> "vidutinė"; else -> "nepatikima" }
        return "Kryptis iš signalų: %.0f° (koreliacija %.2f, atotrūkis nuo kito varianto %.2f, palydovų %d – %s)".format(Locale.US, e.deg, e.corr, e.margin, e.n, q) +
            (viewHeading?.let { "; vaizdo kryptis %.0f°, skirtumas %+.0f°".format(Locale.US, it, wrap(it - e.deg)) } ?: "") +
            (if (lastFixMs > 0) "; paskutinė automatinė šiaurės pataisa %+.1f° %s".format(Locale.US, -lastFixDeg,
                java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(lastFixMs))) else "") +
            "; automatinis taisymas ${if (!autoFix) "išjungtas" else "įjungtas (kas ≥ 5 min., kai patikima ir skirtumas ≥ 5°)"}"
    }
}

/**
 * Ar dabartine vaizdo kryptimi galima pasitikėti: taip – po kalibravimo pagal orientyrą arba pataisos pagal signalus;
 * ne – kai kryptis paimta iš magnetometro (paleidus programą). Kabinos žemėlapis (prietaiso atžvilgiu) mokomas tik kai patikima,
 * kitaip jis „išsitepa“ per klaidingas kryptis ir kryptis iš signalų tampa nepatikima.
 */
object HeadingTrust {
    @Volatile var trusted = false
        private set
    @Volatile var since = 0L
        private set
    @Volatile var why = "kryptis iš magnetometro (paleidus)"
        private set
    fun set(on: Boolean, reason: String) { trusted = on; since = System.currentTimeMillis(); why = reason }
    fun text(): String = "Krypties patikimumas: " + (if (trusted) "patikima" else "NEPATIKIMA – kabinos žemėlapis nemokomas") +
        " (%s, nuo %s)".format(java.util.Locale.US, why, java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(since)))
}


/**
 * Krypties žurnalas heading_log.csv (kas sekundę, kol gaunami palydovai): vaizdo kryptis, kryptis iš signalų ir jų patikimumas,
 * magnetometro kryptis, giroskopo likutinis poslinkis, ramybė, šiaurės poslinkis ir pritaikytos pataisos – šiaurės dreifo tyrimui.
 */
object HeadingLog {
    const val FILE = "heading_log.csv"
    private var w: java.io.BufferedWriter? = null
    private var n = 0
    @Synchronized fun write(dir: java.io.File, ms: Long, view: Double?, sig: SignalHeading.Est?, magAz: Double?, bias: DoubleArray?,
                            still: Boolean, offset: Double?, trusted: Boolean, fix: Double?) {
        try {
            val f = java.io.File(dir, FILE)
            if (w == null || !f.exists()) {
                if (f.exists() && f.length() > 20_000_000L) f.delete()            // ne daugiau ~20 MB
                val fresh = !f.exists()
                w = java.io.BufferedWriter(java.io.FileWriter(f, true)).also {
                    if (fresh) it.write("laikas_ms,vaizdo_kryptis_deg,signalu_kryptis_deg,koreliacija,atotrukis,palydovu,skirtumas_deg," +
                        "magnetometro_kryptis_deg,gyro_bias_x_dps,gyro_bias_y_dps,gyro_bias_z_dps,ramybe,siaures_poslinkis_deg,patikima,pataisa_deg\n") }
            }
            fun d(v: Double?, p: Int = 1) = if (v == null || v.isNaN()) "" else String.format(Locale.US, "%.${p}f", v)
            val sg = sig?.takeIf { ms - it.ms <= 10_000L }
            val diff = if (view != null && sg != null) { var x = (view - sg.deg + 540.0) % 360.0; if (x < 0) x += 360.0; x - 180.0 } else null
            w?.write(listOf(ms.toString(), d(view), d(sg?.deg), d(sg?.corr, 3), d(sg?.margin, 3), sg?.n?.toString() ?: "", d(diff),
                d(magAz), d(bias?.getOrNull(0), 4), d(bias?.getOrNull(1), 4), d(bias?.getOrNull(2), 4),
                if (still) "1" else "0", d(offset), if (trusted) "1" else "0", d(fix)).joinToString(",") + "\n")
            if (++n % 30 == 0) w?.flush()
        } catch (_: Exception) {}
    }
    @Synchronized fun flush() { try { w?.flush() } catch (_: Exception) {} }
}
