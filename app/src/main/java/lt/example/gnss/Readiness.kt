package lt.example.gnss

import java.util.Locale

/**
 * „Paruošta matavimui“: lustas įšilęs. Sąlygos (paskutinė minutė): fiksas ateina, naudojamų palydovų skaičius nebeauga
 * (svyravimas ≤ 3), tikslumas stabilus (mediana ≤ 3 m, svyravimas ≤ 1,5 m), nuo šalto starto praėjo ≥ 5 min.,
 * o jei įjungtas gyvas RTK – jis suartėjęs (σh < 0,5 m).
 */
object Readiness {
    @Volatile var coldStartMs = 0L
    private class S(val ms: Long, val used: Int, val acc: Double, val real: Double)
    private val hist = ArrayDeque<S>()
    @Volatile private var lastFixMs = 0L
    @Volatile private var lastAcc = Double.NaN
    @Volatile private var firstFixMs = 0L

    @Synchronized fun onFix(acc: Double, now: Long = System.currentTimeMillis()) {
        if (firstFixMs == 0L || now - lastFixMs > 30_000) firstFixMs = now
        lastFixMs = now; lastAcc = acc
    }

    @Synchronized fun onStatus(used: Int, now: Long = System.currentTimeMillis()) {
        if (now - lastFixMs > 5_000 || lastAcc.isNaN()) { hist.clear(); return }
        hist.addLast(S(now, used, lastAcc, realAcc().let { if (it.isNaN()) lastAcc else maxOf(it, lastAcc) }))
        while (hist.isNotEmpty() && now - hist.first().ms > 60_000) hist.removeFirst()
    }

    // ---- Lusto žinios: kiek informacijos iš palydovų lustas jau turi ----
    @Volatile private var sats: List<Sat> = emptyList()
    @Volatile private var meas: List<Triple<Int, Double, Int>> = emptyList()
    fun onSats(l: List<Sat>) { sats = l }
    fun onMeas(l: List<Triple<Int, Double, Int>>) { meas = l }

    /** total 0–100: 60 % efemeridės, 20 % laikas (TOW iššifruotas), 20 % almanachas – matomiems (≥ 10°, C/N0 ≥ 20) palydovams. */
    class Knowledge(val total: Int, val eph: Int, val tow: Int, val alm: Int, val n: Int) {
        val text get() = "$total % (efemeridės $eph %, laikas $tow %, almanachas $alm %; matomų palydovų $n)"
    }

    fun knowledge(): Knowledge? {
        val vis = sats.filter { it.elev >= 10f && it.cn0 >= 20f }.distinctBy { it.const to it.svid }
        if (vis.isEmpty()) return null
        val eph = 100 * vis.count { it.eph } / vis.size
        val alm = 100 * vis.count { it.alm } / vis.size
        val m = meas.filter { it.second >= 20.0 }.distinctBy { it.first }
        val tow = if (m.isEmpty()) 0 else 100 * m.count { (it.third and 8) != 0 || (it.third and 0x4000) != 0 } / m.size
        return Knowledge((0.6 * eph + 0.2 * tow + 0.2 * alm).toInt(), eph, tow, alm, vis.size)
    }

    /** Realus tikslumas iš naudojamų palydovų išsidėstymo ir stiprumo (RealAcc), m; NaN – per mažai palydovų. */
    fun realAcc(): Double {
        val u = sats.filter { it.used && it.cn0 >= CnoMask.min }.groupBy { it.const to it.svid }.map { (_, v) -> v.maxBy { it.cn0 } }
        return RealAcc.horizontal(DoubleArray(u.size) { u[it].az.toDouble() }, DoubleArray(u.size) { u[it].elev.toDouble() },
            DoubleArray(u.size) { u[it].cn0.toDouble() })
    }

    /** Trumpai indikatoriui: „lustas 1.7 m (10 m)“ – lusto įvertis ir skliaustuose realus; null – fikso nėra. */
    fun shortAcc(now: Long = System.currentTimeMillis()): String? {
        if (now - lastFixMs > 5_000 || lastAcc.isNaN()) return null
        val r = realAcc().let { if (it.isNaN()) lastAcc else maxOf(it, lastAcc) }
        return "%.1f m (%.0f m)".format(Locale.US, lastAcc, r)
    }

    class State(val ready: Boolean, val text: String)

    @Synchronized fun state(rtkOn: Boolean, rtkSig: Double?, now: Long = System.currentTimeMillis()): State {
        if (now - lastFixMs > 5_000) return State(false, "Šyla: laukiama fikso")
        val st = stateCore(rtkOn, rtkSig, now)
        if (st.text.contains("realiai")) return st
        // realus tikslumas rodomas visada (lusto skaičius – skliaustuose)
        val r = realAcc().let { if (it.isNaN()) lastAcc else maxOf(it, lastAcc) }
        return if (r.isNaN()) st else State(st.ready, st.text + " · realiai ~%.0f m (lustas %.1f m)".format(Locale.US, r, lastAcc))
    }

    private fun stateCore(rtkOn: Boolean, rtkSig: Double?, now: Long): State {
        val sinceCold = now - coldStartMs
        if (coldStartMs > 0 && sinceCold < 300_000)
            return State(false, "Šyla po šalto starto: dar %d:%02d".format(Locale.US, (300_000 - sinceCold) / 60_000, (300_000 - sinceCold) / 1000 % 60))
        val span = if (hist.isEmpty()) 0L else hist.last().ms - hist.first().ms
        if (span < 50_000 || now - firstFixMs < 60_000) return State(false, "Šyla: stebima %d s iš 60".format(Locale.US, minOf(span, now - firstFixMs) / 1000))
        val used = hist.map { it.used }; val acc = hist.map { it.acc }.sorted()
        val dUsed = used.max() - used.min()
        val accMed = acc[acc.size / 2]; val dAcc = acc.last() - acc.first()
        if (dUsed > 3) return State(false, "Šyla: dar nusistovi")
        if (accMed > 3.0 || dAcc > 1.5) return State(false, "Šyla: tikslumas dar kinta (%.1f–%.1f m)".format(Locale.US, acc.first(), acc.last()))
        // lustas mano, kad tikslu, bet signalai silpni ar tik iš vienos pusės (patalpa, po stogu) – rodomas realus tikslumas
        val real = hist.map { it.real }.sorted().let { it[it.size / 2] }
        if (real > 5.0) return State(false, "Silpni ar vienpusiai signalai (patalpa?): realiai ~%.0f m (lustas rodo %.1f m)".format(Locale.US, real, accMed))
        // GPS paruoštas; RTK būsena – atskirai (jis suartėja vėliau ir pasiruošimo nestabdo)
        return State(true, "Paruošta matavimui" +
            (if (!rtkOn) "" else if (rtkSig == null) "; RTK laukiama" else if (rtkSig < 0.5) "; RTK ±%.2f m".format(Locale.US, rtkSig)
             else "; RTK dar suartėja ±%.2f m".format(Locale.US, rtkSig)))
    }
}
