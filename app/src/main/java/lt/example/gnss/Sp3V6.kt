package lt.example.gnss

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * SP3 sprendinys v6 (tik testui): pataisytas Doppler glodinimas ir Kalmanas su 4σ inovacijų riba.
 * Signalai – tik GPS L1 ir Galileo E1 (Sp3V5.readRaw), kad tas pats palydovas nebūtų skaičiuojamas du kartus.
 * v1–v5 nekeičiami.
 */
object Sp3V6 {
    const val OUT_FILE_V6 = "gnss_solver_sp3_test_v6.csv"

    /**
     * Doppler glodinimas: P̂_k = P̂_{k−1} + ṙ_vid·Δt (Android ṙ > 0, kai atstumas didėja; Δt – imtuvo epochų skirtumas, s),
     * P̄_k = P_k / n + (n−1)/n · P̂_k. Paleidžiama iš naujo: tarpas > 1,5 s, |P − P̂| > jump (šuolis),
     * arba slenkantis P − P̂ vidurkis viršija drift (liekana auga – Doppler ir kodo nesutapimas).
     * epochCorr – bendras Doppler ir kodo greičių neatitikimas epochoje (m/s), atimamas iš ṙ.
     */
    class DopplerSmoother(val window: Int = 100, val maxGap: Double = 1.5, val jump: Double = 50.0, val drift: Double = 10.0) {
        private class St(var n: Int, var sm: Double, var t: Double, var rate: Double, var ewma: Double)
        private val st = HashMap<Int, St>()
        var restarts = 0
            private set

        fun count(key: Int) = st[key]?.n ?: 0

        fun update(key: Int, code: Double, rate: Double, t: Double, epochCorr: Double = 0.0): Double {
            val s = st[key]
            if (s == null || t - s.t > maxGap || t <= s.t) {
                if (s != null) restarts++
                st[key] = St(1, code, t, rate, 0.0); return code
            }
            val dt = t - s.t
            val pred = s.sm + (0.5 * (rate + s.rate) - epochCorr) * dt
            val e = code - pred
            val ew = 0.9 * s.ewma + 0.1 * e
            if (abs(e) > jump || abs(ew) > drift) {
                restarts++
                st[key] = St(1, code, t, rate, 0.0); return code
            }
            val n = minOf(s.n + 1, window)
            s.sm = code / n + (n - 1.0) / n * pred
            s.n = n; s.t = t; s.rate = rate; s.ewma = ew
            return s.sm
        }
    }

    /** Signalų skaičius gnss_raw.csv pagal konsteliaciją ir signalą (eilučių). */
    fun signalCounts(raw: File): Map<String, Int> {
        val out = java.util.TreeMap<String, Int>()
        if (!raw.exists()) return out
        raw.bufferedReader().use { br ->
            val head = br.readLine()?.split(',') ?: return out
            val iC = head.indexOf("konstelacija"); val iS = head.indexOf("signalas")
            if (iC < 0 || iS < 0) return out
            br.forEachLine { l ->
                val p = l.split(',')
                if (p.size > maxOf(iC, iS)) { val k = "${p[iC]} ${p[iS]}"; out[k] = (out[k] ?: 0) + 1 }
            }
        }
        return out
    }

    class Stats { var adr = 0; var dop = 0; var none = 0; val corr = ArrayList<Double>() }

    /** Vienas žingsnis; doppler – pataisytas glodinimas (ADR, jei galioja, kitaip Doppler). */
    fun run(raw: File, sp3: Sp3Reader, name: String, doppler: Boolean, raim: Boolean, kalman: Boolean, out: File? = null,
            stats: Stats? = null, ds: DopplerSmoother? = null, kfOut: Array<Sp3V5.Kalman?>? = null): Sp3V5.Run {
        val sols = ArrayList<Pair<Long, PositionSolver.Solution>>()
        val adr = Sp3V4.Hatch()
        val dsm = ds ?: DopplerSmoother()
        val prev = HashMap<Int, Triple<Double, Double, Double>>()      // key -> (kodas, ṙ, t)
        var kf: Sp3V5.Kalman? = null
        val w = out?.bufferedWriter()
        try {
            w?.write(PositionSolver.SOLVER_HEADER)
            Sp3V5.readRaw(raw) { ep ->
                val t = ep.t
                val acc = ep.meas.filter { Sp3V5.accept(it) }
                // Bendras kodo ir Doppler greičių neatitikimas (imtuvo laikrodžio derinimas): mediana per palydovus
                val d = acc.mapNotNull { m ->
                    val p = prev[m.key]; val r = m.prr
                    if (p == null || r == null || t - p.third !in 0.5..1.5) null
                    else (m.pr - p.first) / (t - p.third) - 0.5 * (r + p.second)
                }.sorted()
                val corr = if (d.size >= 4) d[d.size / 2] else 0.0
                if (d.size >= 4) stats?.corr?.add(corr)
                acc.forEach { m -> m.prr?.let { prev[m.key] = Triple(m.pr, it, t) } }
                val obs = acc.map { m ->
                    val adrOk = m.adr != null && (m.adrState and Sp3V4.ADR_VALID) != 0 &&
                        (m.adrState and (Sp3V4.ADR_RESET or Sp3V4.ADR_CYCLE_SLIP)) == 0
                    val pr = when {
                        !doppler -> { stats?.let { it.none++ }; m.pr }
                        adrOk -> { stats?.let { it.adr++ }; adr.update(m.key, m.pr, m.adr, m.adrState, t) }
                        m.prr != null -> { stats?.let { it.dop++ }; dsm.update(m.key, m.pr, m.prr, t, corr) }
                        else -> { stats?.let { it.none++ }; m.pr }
                    }
                    Sp3V5.Ob(m.sys, m.svid, pr, m.cn0, m.prr)
                }
                if (!Sp3Solver.inRange(sp3, t)) return@readRaw
                val fit = Sp3V5.solve(ep.week, ep.tow, obs, sp3, true, raim)
                val sol = if (!kalman) fit?.sol else {
                    if (kf == null) kf = Sp3V5.Kalman(listOf('C', 'E', 'G'), codeGate = 4.0, realignM = 15.0).also { kfOut?.set(0, it) }
                    val sats = Sp3V5.prepare(t, obs.filter { o -> fit == null || fit.dropped.none { it == "%c%02d".format(Locale.US, o.sys, o.svid) } }, sp3)
                    kf!!.step(t, ep.tow, sats, fit)
                } ?: return@readRaw
                sols.add(ep.timeMs to sol)
                w?.write(String.format(Locale.US, "%d,%.8f,%.8f,%.2f,%d,%.2f\n", ep.timeMs, sol.lat, sol.lon, sol.h, sol.sats, sol.hacc))
            }
        } finally { w?.close() }
        return Sp3V5.Run(name, sols)
    }

    private fun line(r: Sp3V5.Run, ref: Pair<Double, Double>?, fixH: Double?): String {
        if (r.sols.isEmpty()) return "${r.name}: nėra sprendinių"
        val sb = StringBuilder("${r.name}: ")
        if (ref != null) {
            val o = Sp3Solver.offsets(r.sols, ref)!!
            val mPerDeg = Math.toRadians(1.0) * 6_371_000.0; val cl = cos(Math.toRadians(ref.first))
            val d = r.sols.map { val e = (it.second.lon - ref.second) * mPerDeg * cl; val n = (it.second.lat - ref.first) * mPerDeg; sqrt(e * e + n * n) }.sorted()
            sb.append("vid. rytai %+.2f m, vid. šiaurė %+.2f m, atstumo mediana %.2f m, 90 %% %.2f m, sklaida %.2f m".format(Locale.US,
                o[0], o[1], o[2], PositionSolver.percentile(d, 0.9), o[3]))
        } else sb.append("atstumų nėra (nėra atskaitos taško)")
        val h = PositionSolver.percentile(r.sols.map { it.second.h }.sorted(), 0.5)
        sb.append("; aukščio skirtumas ").append(fixH?.let { "%+.2f m".format(Locale.US, h - it) } ?: "nėra")
        sb.append("; palydovų mediana %.0f; sprendinių %d".format(Locale.US, PositionSolver.percentile(r.sols.map { it.second.sats.toDouble() }.sorted(), 0.5), r.sols.size))
        return sb.toString()
    }

    /** „SP3 TESTAS v6“: ISB → +Doppler (pataisytas) → +RAIM → +Kalman (4σ); paskutinis – gnss_solver_sp3_test_v6.csv. */
    fun v6Block(raw: File, sp3: Sp3Reader, outV6: File, ref: Pair<Double, Double>?, fixH: Double?): String = buildString {
        appendLine()
        appendLine("--- SP3 TESTAS v6 ---")
        appendLine("Atskaitos taškas: ${PositionSolver.refLabel()}" + (ref?.let { " (%.7f, %.7f)".format(Locale.US, it.first, it.second) } ?: " – nėra"))
        val sig = signalCounts(raw)
        appendLine("Signalai gnss_raw.csv (eilučių): " + (if (sig.isEmpty()) "nėra" else sig.entries.joinToString(", ") { "${it.key} ${it.value}" }) +
            "; sprendinyje naudojami tik GPS L1 ir GAL E1")
        val st = Stats(); val ds = DopplerSmoother(); val kfRef = arrayOfNulls<Sp3V5.Kalman>(1)
        val steps = listOf(
            run(raw, sp3, "ISB", false, false, false),
            run(raw, sp3, "+ Doppler glodinimas (pataisytas)", true, false, false, stats = st, ds = ds),
            run(raw, sp3, "+ RAIM", true, true, false),
            run(raw, sp3, "+ Kalmanas (4σ)", true, true, true, outV6, kfOut = kfRef))
        steps.forEach { appendLine(line(it, ref, fixH)) }
        val c = st.corr.sorted()
        appendLine("Glodinimas (palydovų-epochų): ADR ${st.adr}, Doppler ${st.dop}, be glodinimo ${st.none}; Doppler paleidimų iš naujo ${ds.restarts}")
        appendLine("Kodo ir Doppler greičių neatitikimas (bendras, atimamas): " +
            (if (c.isEmpty()) "nėra" else "mediana %.3f m/s, 90 %% |·| %.3f m/s".format(Locale.US, c[c.size / 2], PositionSolver.percentile(c.map { abs(it) }.sorted(), 0.9))))
        kfRef[0]?.let { appendLine("Kalmanas: laikrodžio sulyginimų ${it.realigned}, paleidimų iš naujo ${it.resets}") }
        appendLine("Failas $OUT_FILE_V6")
    }
}
