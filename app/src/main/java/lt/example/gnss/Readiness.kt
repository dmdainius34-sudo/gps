package lt.example.gnss

import java.util.Locale

/**
 * „Paruošta matavimui“: lustas įšilęs. Sąlygos (paskutinė minutė): fiksas ateina, naudojamų palydovų skaičius nebeauga
 * (svyravimas ≤ 3), tikslumas stabilus (mediana ≤ 3 m, svyravimas ≤ 1,5 m), nuo šalto starto praėjo ≥ 5 min.,
 * o jei įjungtas gyvas RTK – jis suartėjęs (σh < 0,5 m).
 */
object Readiness {
    @Volatile var coldStartMs = 0L
    private class S(val ms: Long, val used: Int, val acc: Double)
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
        hist.addLast(S(now, used, lastAcc))
        while (hist.isNotEmpty() && now - hist.first().ms > 60_000) hist.removeFirst()
    }

    class State(val ready: Boolean, val text: String)

    @Synchronized fun state(rtkOn: Boolean, rtkSig: Double?, now: Long = System.currentTimeMillis()): State {
        if (now - lastFixMs > 5_000) return State(false, "Šyla: laukiama fikso")
        val sinceCold = now - coldStartMs
        if (coldStartMs > 0 && sinceCold < 300_000)
            return State(false, "Šyla po šalto starto: dar %d:%02d".format(Locale.US, (300_000 - sinceCold) / 60_000, (300_000 - sinceCold) / 1000 % 60))
        val span = if (hist.isEmpty()) 0L else hist.last().ms - hist.first().ms
        if (span < 50_000 || now - firstFixMs < 60_000) return State(false, "Šyla: stebima %d s iš 60".format(Locale.US, minOf(span, now - firstFixMs) / 1000))
        val used = hist.map { it.used }; val acc = hist.map { it.acc }.sorted()
        val dUsed = used.max() - used.min()
        val accMed = acc[acc.size / 2]; val dAcc = acc.last() - acc.first()
        if (dUsed > 3) return State(false, "Šyla: palydovų skaičius dar kinta (${used.min()}–${used.max()})")
        if (accMed > 3.0 || dAcc > 1.5) return State(false, "Šyla: tikslumas dar kinta (%.1f–%.1f m)".format(Locale.US, acc.first(), acc.last()))
        if (rtkOn && (rtkSig == null || rtkSig >= 0.5)) return State(false, "Šyla: RTK dar nesuartėjęs" + (rtkSig?.let { " (±%.2f m)".format(Locale.US, it) } ?: ""))
        return State(true, "Paruošta matavimui: palydovų ~${used.last()}, tikslumas %.1f m".format(Locale.US, accMed) +
            (if (rtkOn && rtkSig != null) ", RTK ±%.2f m".format(Locale.US, rtkSig) else ""))
    }
}
