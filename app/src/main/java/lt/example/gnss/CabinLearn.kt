package lt.example.gnss

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Laukiamas signalo stiprumas pagal palydovo aukštį: kiekvienai sistemai ir 5° aukščio ruožui sekamas „gero“ signalo lygis
 * (85-asis procentilis, pradžioje – tipinis telefono atviro dangaus C/N0). Užstotas palydovas – tas, kurio signalas gerokai
 * silpnesnis už laukiamą jo aukščiui (žemas palydovas natūraliai silpnesnis – tai ne kliūtis).
 */
object CnoModel {
    private const val BINS = 18
    private val q = HashMap<Char, DoubleArray>()
    private val n = HashMap<Char, IntArray>()

    /** Tipinis atviro dangaus C/N0 (dB-Hz) telefone ar planšetėje: ~30 ties 5°, ~42 virš 60°. */
    fun prior(elDeg: Double): Double = 30.0 + 12.0 * sin(Math.toRadians(elDeg.coerceIn(0.0, 90.0))).let { minOf(1.0, it * 1.15) }

    private fun bin(el: Double) = (el / 5.0).toInt().coerceIn(0, BINS - 1)

    @Synchronized fun update(sys: Char, elDeg: Double, cn0: Double) {
        if (cn0 <= 0 || elDeg < 0) return
        val a = q.getOrPut(sys) { DoubleArray(BINS) { prior(it * 5.0 + 2.5) } }
        val c = n.getOrPut(sys) { IntArray(BINS) }
        val b = bin(elDeg); c[b]++
        // stochastinis kvantilio sekimas: 85 % mėginių žemiau – žingsnis mažėja, kol sukaupiama duomenų
        val step = maxOf(0.02, 1.0 / (1 + c[b] / 50.0))
        a[b] += if (cn0 > a[b]) step * 0.85 else -step * 0.15
    }

    @Synchronized fun expected(sys: Char, elDeg: Double): Double = q[sys]?.get(bin(elDeg)) ?: prior(elDeg)

    /** Kiek signalas silpnesnis už laukiamą (dB; > 0 – silpnesnis). */
    fun deficit(sys: Char, elDeg: Double, cn0: Double) = expected(sys, elDeg) - cn0

    @Synchronized fun reset() { q.clear(); n.clear() }
}

/**
 * Atspindžių matavimas be RTK: kodo ir fazės (ADR) skirtumas – arba, kai fazės nėra (telefonas), kodo ir integruoto Doplerio.
 * Pastovi dalis (dviprasmybė, jonosfera) lėtai kinta, todėl ji pašalinama slenkančiu vidurkiu; lieka greitai kintanti
 * kodo paklaida – atspindžiai. Bendras visiems palydovams laikrodžio dreifas pašalinamas epochos mediana.
 */
object MpTrack {
    class In(val key: Int, val pr: Double, val adr: Double?, val prr: Double?)
    private class St(var lastPr: Double, var lastCar: Double, var lastPrr: Double, var lastT: Double, var cum: Double, var ema: Double, var n: Int)
    private val st = HashMap<Int, St>()
    /** Paskutinis įvertis (m) kiekvienam palydovui; NaN – dar nepakanka duomenų. */
    val last = HashMap<Int, Double>()
    private const val TAU = 40.0          // slenkančio vidurkio trukmė (s)
    private const val WARM = 15           // epochų, kol įvertis laikomas patikimu

    @Synchronized fun epoch(t: Double, ins: List<In>): Map<Int, Double> {
        // 1) kiekvieno palydovo kodo pokytis minus nešlio (fazės ar integruoto Doplerio) pokytis
        val inc = HashMap<Int, Double>(); val car = HashMap<Int, Double>()
        for (m in ins) {
            val s = st[m.key]
            val dt = if (s == null) -1.0 else t - s.lastT
            val c = when {
                m.adr != null -> m.adr
                m.prr != null && s != null && dt in 0.2..2.0 -> s.lastCar + 0.5 * (m.prr + s.lastPrr) * dt
                m.prr != null -> 0.0
                else -> Double.NaN
            }
            if (c.isNaN()) { st.remove(m.key); continue }
            car[m.key] = c
            if (s != null && dt in 0.2..2.0) inc[m.key] = (m.pr - s.lastPr) - (c - s.lastCar)
        }
        // 2) bendra dalis (imtuvo laikrodžio dreifas) – mediana
        // (kodas ir nešlis turi tą patį imtuvo laikrodį – atimama tik aiškus laikrodžio šuolis, kitaip mediana triukšmą kauptų)
        val med = (if (inc.size >= 4) inc.values.sorted()[inc.size / 2] else 0.0).let { if (abs(it) > 5.0) it else 0.0 }
        val out = HashMap<Int, Double>()
        for (m in ins) {
            val c = car[m.key] ?: continue
            val s = st[m.key]
            val d = inc[m.key]
            if (s == null || d == null || abs(d - med) > 150.0) {
                // naujas lankas (arba fazės šuolis): pradedama iš naujo
                st[m.key] = St(m.pr, c, m.prr ?: 0.0, t, 0.0, 0.0, 0); last[m.key] = Double.NaN; continue
            }
            s.cum += d - med
            val a = 1.0 / minOf(TAU, (s.n + 1).toDouble())
            s.ema += a * (s.cum - s.ema)
            s.n++
            s.lastPr = m.pr; s.lastCar = c; s.lastPrr = m.prr ?: 0.0; s.lastT = t
            val v = if (s.n >= WARM) s.cum - s.ema else Double.NaN
            last[m.key] = v; if (!v.isNaN()) out[m.key] = v
        }
        val seen = ins.map { it.key }.toSet()
        st.keys.retainAll(seen); last.keys.retainAll(seen)
        return out
    }

    @Synchronized fun reset() { st.clear(); last.clear() }
}

/**
 * Atspindžių klasifikatorius (logistinė regresija), kuris mokosi pats: kai padėtis tikrai žinoma (RTK arba žinoma kabinos vieta
 * su krano apribojimu), palydovo liekana parodo, ar signalas atspindėtas (> 15 m) ar tiesioginis (< 4 m). Požymiai: signalo
 * trūkumas iki laukiamo, aukštis, kodo–fazės paklaida, signalo šuolis. Išmokus (≥ 300 pavyzdžių) – atspindžio tikimybė mažina svorį.
 */
object NlosModel {
    const val FILE = "nlos_model.csv"
    const val NF = 5
    private val w = DoubleArray(NF)
    @Volatile var samples = 0
        private set
    @Volatile var pos = 0
        private set
    val ready get() = samples >= 300 && pos >= 20

    /** Požymiai: [1, trūkumas/10, sin(aukštis), |kodo–fazės|/10, |C/N0 šuolis|/5]. */
    fun feat(def: Double, elDeg: Double, mp: Double, dCn0: Double) = doubleArrayOf(1.0, (def / 10.0).coerceIn(-2.0, 4.0),
        sin(Math.toRadians(elDeg.coerceIn(0.0, 90.0))), if (mp.isNaN()) 0.5 else (abs(mp) / 10.0).coerceAtMost(5.0), (abs(dCn0) / 5.0).coerceAtMost(4.0))

    @Synchronized fun p(x: DoubleArray): Double { var z = 0.0; for (i in 0 until NF) z += w[i] * x[i]; return 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0))) }

    /** Vienas mokymo žingsnis (stochastinis gradientas su L2); klasės subalansuojamos (atspindžių būna mažiau). */
    @Synchronized fun train(x: DoubleArray, y: Int) {
        val pr = p(x)
        val cw = if (y == 1) 3.0 else 1.0
        val lr = maxOf(0.01, 0.2 / (1 + samples / 500.0))
        for (i in 0 until NF) w[i] += lr * (cw * (y - pr) * x[i] - 1e-3 * w[i])
        samples++; if (y == 1) pos++
    }

    @Synchronized fun reset() { java.util.Arrays.fill(w, 0.0); samples = 0; pos = 0 }

    @Synchronized fun save(d: File) {
        try { File(d, FILE).writeText("w,${w.joinToString(",") { String.format(Locale.US, "%.6f", it) }}\nn,$samples,$pos\n") } catch (_: Exception) {}
    }

    @Synchronized fun load(d: File) {
        try {
            val f = File(d, FILE); if (!f.exists()) return
            val ls = f.readLines()
            // jau išmokta daugiau nei faile (įkelta pavėluotai) – failas neperrašo
            val fileN = ls.firstOrNull { it.startsWith("n,") }?.split(',')?.getOrNull(1)?.toIntOrNull() ?: 0
            if (samples >= fileN && samples > 0) return
            for (l in ls) {
                val v = l.split(',')
                if (v[0] == "w" && v.size == NF + 1) for (i in 0 until NF) w[i] = v[i + 1].toDouble()
                if (v[0] == "n" && v.size >= 3) { samples = v[1].toInt(); pos = v[2].toInt() }
            }
        } catch (_: Exception) {}
    }

    fun text(): String = "Atspindžių klasifikatorius: pavyzdžių %d (atspindžių %d), %s; svoriai %s".format(Locale.US, samples, pos,
        if (ready) "naudojamas" else "dar mokosi", w.joinToString(" ") { String.format(Locale.US, "%+.2f", it) })
}

/** Kiekvienos epochos mokymasis: laukiamas C/N0, kodo–fazės atspindžiai į žemėlapius, klasifikatoriaus pavyzdžiai. */
object CabinLearn {
    /** Paskutiniai požymiai kiekvienam palydovui (raktas sys·1000 + svid) – svoriams kitoje epochoje. */
    val feats = HashMap<Int, DoubleArray>()
    private val lastCn0 = HashMap<Int, Double>()
    @Volatile var labeled = 0
        private set

    class Sat(val sys: Char, val svid: Int, val azDeg: Double, val elDeg: Double, val cn0: Double, val pr: Double, val adr: Double?, val prr: Double?, val res: Double?)

    /** truth – padėtis tikrai žinoma (liekanos tinka klasifikatoriaus pavyzdžiams); heading – patikima prietaiso kryptis. */
    @Synchronized fun epoch(t: Double, sats: List<Sat>, truth: Boolean, heading: Double?) {
        for (s in sats) CnoModel.update(s.sys, s.elDeg, s.cn0)
        val mp = MpTrack.epoch(t, sats.map { MpTrack.In(it.sys.code * 1000 + it.svid, it.pr, it.adr, it.prr) })
        for (s in sats) {
            val key = s.sys.code * 1000 + s.svid
            val def = CnoModel.deficit(s.sys, s.elDeg, s.cn0)
            val m = mp[key] ?: Double.NaN
            val d = lastCn0[key]?.let { s.cn0 - it } ?: 0.0
            lastCn0[key] = s.cn0
            val x = NlosModel.feat(def, s.elDeg, m, d)
            feats[key] = x
            if (CabinMap.learn) CabinMap.learnQuality(heading, s.azDeg, s.elDeg, def, m)
            val r = s.res
            if (truth && r != null && s.elDeg >= 10.0) {
                val y = if (abs(r) > 15.0) 1 else if (abs(r) < 4.0) 0 else -1
                if (y >= 0) { NlosModel.train(x, y); labeled++ }
            }
        }
        val seen = sats.map { it.sys.code * 1000 + it.svid }.toSet()
        feats.keys.retainAll(seen); lastCn0.keys.retainAll(seen)
    }

    /** Svorio daugiklis pagal klasifikatorių (1 – neišmokęs arba tiesioginis signalas). */
    @Synchronized fun weight(sys: Char, svid: Int): Double {
        if (!NlosModel.ready) return 1.0
        val x = feats[sys.code * 1000 + svid] ?: return 1.0
        return 1.0 - 0.8 * NlosModel.p(x)
    }
}
