package lt.example.gnss

import java.util.Locale
import kotlin.math.sqrt

/**
 * Kiekvieno palydovo istorija programos padėčiai (paskutinės 30 min., minutėmis): kiek kartų naudotas,
 * kodėl užtildytas, liekana (kiek jo atstumas nesutampa su padėtimi iš visų palydovų), signalo stiprumas.
 * Kortelei („kodėl užtildytas“, „ar jam galima tikėti“) ir ZIP ataskaitai – iš jos matyti, kurie palydovai gadina padėtį.
 */
object SatStats {
    const val USED = 0; const val NO_TIME = 1; const val UNHEALTHY = 2; const val WEAK = 3; const val NO_ORBIT = 4
    const val LOW_EL = 5; const val RAIM = 6; const val NO_FIX = 7; const val NOT_SYS = 8; const val MANUAL = 9
    private const val N = 10
    private val short = arrayOf("naudotas", "nėra laiko žymos", "nesveikas", "silpnas", "nėra orbitos", "žemiau 10°", "RAIM", "nėra padėties", "sistema nenaudojama", "rankiniu būdu")

    private class B(val min: Long) { var n = 0; var used = 0; var rs = 0.0; var rq = 0.0; var cn = 0.0; val why = IntArray(N) }
    private val m = HashMap<String, ArrayDeque<B>>()
    private const val KEEP_MIN = 30

    /** Paskutinė epocha: raktas („GPS12“) → (priežastis, liekana m arba NaN). */
    @Volatile var last: Map<String, Pair<Int, Double>> = emptyMap()
        private set
    @Volatile var lastMs = 0L
        private set
    @Volatile var lastThr = 0.0
        private set
    /** Palydovai, kurių pseudoatstumai pataisyti aparatūros vėlinimu (TGD/BGD) paskutinėje epochoje. */
    @Volatile var tgdN = 0
    @Volatile var tgdOf = 0

    @Synchronized fun epoch(now: Long, thr: Double, items: List<Triple<String, Int, Double>>, cn0: Map<String, Double>) {
        val minute = now / 60_000L
        for ((k, why, res) in items) {
            val q = m.getOrPut(k) { ArrayDeque() }
            if (q.isEmpty() || q.last().min != minute) q.addLast(B(minute))
            while (q.isNotEmpty() && minute - q.first().min >= KEEP_MIN) q.removeFirst()
            val b = q.last()
            b.n++; b.why[why]++; cn0[k]?.let { b.cn += it }
            if (why == USED) { b.used++; if (!res.isNaN()) { b.rs += res; b.rq += res * res } }
        }
        m.entries.removeAll { e -> e.value.isEmpty() || minute - e.value.last().min >= KEEP_MIN }
        last = items.associate { it.first to (it.second to it.third) }
        lastMs = now; lastThr = thr
    }

    class Sum(val n: Int, val used: Int, val resMean: Double, val resRms: Double, val cn0: Double, val why: IntArray)

    @Synchronized fun sum(key: String, now: Long, minutes: Int = KEEP_MIN): Sum? {
        val q = m[key] ?: return null
        val from = now / 60_000L - minutes + 1
        var n = 0; var u = 0; var rs = 0.0; var rq = 0.0; var cn = 0.0; val why = IntArray(N)
        for (b in q) if (b.min >= from) { n += b.n; u += b.used; rs += b.rs; rq += b.rq; cn += b.cn; for (i in 0 until N) why[i] += b.why[i] }
        if (n == 0) return null
        return Sum(n, u, if (u > 0) rs / u else Double.NaN, if (u > 0) sqrt(rq / u) else Double.NaN, cn / n, why)
    }

    /** Dabartinė priežastis žodžiais; null – naudojamas arba nežinoma. */
    fun reason(key: String, cn0: Double): String? {
        if (System.currentTimeMillis() - lastMs > 5_000L) return null
        val (w, _) = last[key] ?: return if (key.take(3) in setOf("GPS", "GAL", "GLO", "BDS")) "lustas šio palydovo atstumo dabar nematuoja (žinoma tik kryptis)"
            else text(NOT_SYS, cn0, lastThr, key)
        return text(w, cn0, lastThr, key)
    }

    fun text(w: Int, cn0: Double, thr: Double, key: String): String? = when (w) {
        USED -> null
        NO_TIME -> "lustas dar neiššifravo signalo laiko žymos – atstumo nežinoma"
        UNHEALTHY -> "nesveikas pagal sveikatos žymą"
        WEAK -> "silpnas signalas: %.0f dB-Hz, slenkstis %.0f dB-Hz".format(Locale.US, cn0, thr)
        NO_ORBIT -> "nėra orbitos (nei SP3, nei efemeridės šiam palydovui)"
        LOW_EL -> "per žemai: žemiau 10° virš horizonto (ilgas kelias per atmosferą, daug atspindžių)"
        RAIM -> "RAIM atmetė: jo atstumas nesutampa su kitais palydovais (tikėtinas atspindys)"
        NO_FIX -> "padėtis nesuskaičiuota – per mažai tinkamų palydovų"
        MANUAL -> "užtildytas rankiniu būdu (C/N0 grafike ar kortelėje)"
        NOT_SYS -> if (key.startsWith("SBS")) "SBAS – pataisų palydovas, atstumui nenaudojamas" else "šios sistemos signalas programos padėčiai nenaudojamas"
        else -> null
    }

    /** Liekanos paaiškinimas žmogui. */
    fun resWord(rms: Double): String = when {
        rms.isNaN() -> ""
        rms < 2.0 -> " – patikimas"
        rms < 5.0 -> " – vidutiniškai"
        else -> " – prastas (tikėtini atspindžiai)"
    }

    @Synchronized fun text(): String = buildString {
        val now = System.currentTimeMillis()
        appendLine("=== PALYDOVAI PROGRAMOS PADĖTYJE (paskutinės 30 min.) ===")
        appendLine("Aparatūros vėlinimo (TGD/BGD) pataisa SP3 keliu: paskutinėje epochoje $tgdN iš $tgdOf GPS/Galileo palydovų")
        if (m.isEmpty()) { appendLine("Duomenų dar nėra"); return@buildString }
        appendLine("palydovas: epochų, naudotas %, liekana vid./RMS (m), C/N0 vid., užtildymo priežastys")
        val rows = m.keys.mapNotNull { k -> sum(k, now)?.let { k to it } }.sortedBy { it.first }
        for ((k, s) in rows) {
            val why = (1 until N).filter { s.why[it] > 0 }.sortedByDescending { s.why[it] }
                .joinToString(", ") { "%s %.0f %%".format(Locale.US, short[it], 100.0 * s.why[it] / s.n) }
            appendLine("  %-6s %5d  %3.0f %%  %s  %4.1f  %s".format(Locale.US, k, s.n, 100.0 * s.used / s.n,
                if (s.used > 0) "%+5.1f / %4.1f".format(Locale.US, s.resMean, s.resRms) else "    —       ", s.cn0, why))
        }
        val bad = rows.filter { it.second.used >= 60 && it.second.resRms > 5.0 }
        if (bad.isNotEmpty()) appendLine("Prasti (RMS > 5 m, naudoti ≥ 60 epochų): " + bad.joinToString(", ") { it.first })
    }

    @Synchronized fun reset() { m.clear(); last = emptyMap(); lastMs = 0L }
}
