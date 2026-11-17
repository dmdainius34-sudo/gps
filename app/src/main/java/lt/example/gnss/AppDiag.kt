package lt.example.gnss

import java.util.Locale
import kotlin.math.abs

/**
 * Lauko bandymų diagnostika ataskaitai (ZIP ir Claude): kaip per paskutines 30 min. veikė programos padėtis,
 * automatinis signalo slenkstis, žinomas aukštis ir NLO ryšio spalva. Tik atmintyje, kas sekundę po eilutę.
 */
object AppDiag {
    private class E(val ms: Long, val thr: Double, val auto: Boolean, val heard: Int, val used: Int, val hCon: Boolean,
                    val hacc: Double, val jump: Double, val freeDh: Double, val outJump: Double = Double.NaN, val smN: Double = Double.NaN)
    private class T(val ms: Long, val from: Double, val to: Double, val sats: Int)
    private class L(val ms: Long, val q: Float, val lost: Double)

    private const val KEEP_MS = 30 * 60_000L
    private val eps = ArrayDeque<E>()
    private val thrs = ArrayDeque<T>()
    private val links = ArrayDeque<L>()
    private var prevLat = Double.NaN; private var prevLon = Double.NaN
    private var oLat = Double.NaN; private var oLon = Double.NaN

    private fun <X> trim(q: ArrayDeque<X>, ms: (X) -> Long, now: Long) { while (q.isNotEmpty() && now - ms(q.first()) > KEEP_MS) q.removeFirst() }

    /** Programos padėtis apskaičiuota. */
    /** sol – sprendinys, out – išlyginta padėtis (tokia perduodama kitoms programoms). */
    private var restarts0 = -1; private var restartsN = 0
    @Synchronized fun pos(heard: Int, sol: PositionSolver.Solution, hCon: Boolean, out: PositionSolver.Solution, smN: Double = Double.NaN, restarts: Int = 0) {
        if (restarts0 < 0) restarts0 = restarts
        restartsN = restarts - restarts0
        val now = System.currentTimeMillis()
        val jump = if (prevLat.isNaN()) 0.0 else PositionSolver.horizDist(prevLat, prevLon, sol.lat, sol.lon)
        prevLat = sol.lat; prevLon = sol.lon
        val oj = if (oLat.isNaN()) 0.0 else PositionSolver.horizDist(oLat, oLon, out.lat, out.lon)
        oLat = out.lat; oLon = out.lon
        eps.addLast(E(now, CnoMask.min, CnoMask.auto, heard, sol.sats, hCon, sol.hacc, jump, KnownHeight.freeDh, oj, smN))
        trim(eps, { it.ms }, now)
    }

    /** Padėties nėra (per mažai palydovų virš slenksčio arba sprendinys nepavyko). */
    @Synchronized fun noPos(heard: Int) {
        val now = System.currentTimeMillis()
        eps.addLast(E(now, CnoMask.min, CnoMask.auto, heard, 0, false, Double.NaN, Double.NaN, Double.NaN))
        trim(eps, { it.ms }, now)
    }

    @Synchronized fun thr(from: Double, to: Double, sats: Int) {
        val now = System.currentTimeMillis()
        thrs.addLast(T(now, from, to, sats)); trim(thrs, { it.ms }, now)
    }

    @Synchronized fun link(q: Float?, lost: Double) {
        val now = System.currentTimeMillis()
        if (links.isNotEmpty() && now - links.last().ms < 900L) return
        links.addLast(L(now, q ?: Float.NaN, lost)); trim(links, { it.ms }, now)
    }

    private fun hms(ms: Long) = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date(ms))
    private fun pct(a: Int, b: Int) = if (b == 0) "—" else "%.0f %%".format(Locale.US, 100.0 * a / b)
    private fun q(l: List<Double>, p: Double) = if (l.isEmpty()) Double.NaN else l.sorted()[((l.size - 1) * p).toInt()]

    @Synchronized fun text(): String = buildString {
        appendLine("=== PROGRAMOS PADĖTIS, SLENKSTIS, ŽINOMAS AUKŠTIS, NLO (paskutinės ${if (eps.isEmpty()) 0 else (eps.last().ms - eps.first().ms) / 60_000 + 1} min.) ===")
        if (eps.isEmpty()) { appendLine("Duomenų dar nėra"); return@buildString }
        val ok = eps.filter { it.used > 0 }
        appendLine("Epochų ${eps.size}: su padėtimi ${ok.size}, be padėties ${eps.size - ok.size} (${pct(eps.size - ok.size, eps.size)})")
        if (ok.isNotEmpty()) {
            val acc = ok.map { it.hacc }
            appendLine("Naudota palydovų: vid. %.1f, min %d, max %d; girdima virš slenksčio vid. %.1f".format(Locale.US,
                ok.map { it.used }.average(), ok.minOf { it.used }, ok.maxOf { it.used }, eps.map { it.heard }.average()))
            appendLine("Tikslumo įvertis: mediana %.1f m, 90 %% %.1f m; šuoliai tarp epochų: > 5 m – %d, > 20 m – %d, didžiausias %.0f m".format(Locale.US,
                q(acc, 0.5), q(acc, 0.9), ok.count { it.jump > 5.0 }, ok.count { it.jump > 20.0 }, ok.maxOf { it.jump }))
            val oj = ok.map { it.outJump }.filter { !it.isNaN() }
            if (oj.isNotEmpty()) appendLine("Išlyginta padėtis (kitoms programoms): šuoliai > 2 m – %d, > 5 m – %d, didžiausias %.1f m".format(Locale.US,
                oj.count { it > 2.0 }, oj.count { it > 5.0 }, oj.maxOrNull() ?: 0.0))
            val sn = ok.map { it.smN }.filter { !it.isNaN() }
            if (sn.isNotEmpty()) appendLine("Glodinimas pagal Doplerį: vidutinis glodinimo ilgis %.0f s (daugiausia 100), palydovų glodinimų pradėta iš naujo %d".format(Locale.US,
                sn.average(), restartsN))
            val hc = ok.filter { it.hCon }
            appendLine("Su žinomu aukščiu: ${hc.size} epochų (${pct(hc.size, ok.size)}), iš jų iš 3–4 palydovų ${hc.count { it.used <= 4 }}" +
                (if (hc.isNotEmpty()) "; tikslumas su aukščiu mediana %.1f m, be jo %.1f m".format(Locale.US,
                    q(hc.map { it.hacc }, 0.5), q(ok.filter { !it.hCon }.map { it.hacc }, 0.5)) else ""))
            val fd = eps.map { it.freeDh }.filter { !it.isNaN() }
            if (fd.isNotEmpty()) appendLine("Aukščio patikra (padėtis be aukščio – įvestas): vid. %+.1f m, didžiausias |%.0f| m; dabar %s".format(Locale.US,
                fd.average(), fd.maxOf { abs(it) }, if (KnownHeight.conflict) "NENAUDOJAMAS (nesutampa)" else "tinka"))
        }
        val th = eps.map { it.thr }
        appendLine("Slenkstis: %s, vid. %.0f, min %.0f, max %.0f dB-Hz; automatinių pakeitimų %d".format(Locale.US,
            if (CnoMask.auto) "automatinis" else "rankinis", th.average(), th.minOrNull() ?: 0.0, th.maxOrNull() ?: 0.0, thrs.size))
        thrs.toList().takeLast(12).forEach { appendLine("  ${hms(it.ms)} %.0f → %.0f (tinkamų palydovų %d)".format(Locale.US, it.from, it.to, it.sats)) }
        val lq = links.filter { !it.q.isNaN() }
        if (lq.isNotEmpty()) appendLine("NLO spalva: vid. %.0f; geltona (> 38) %s, žydra (31–38) %s, mėlyna (23–30) %s, juoda (≤ 22) %s; stiprių palydovų dingimų (svertinai) %.0f".format(Locale.US,
            lq.map { it.q.toDouble() }.average(), pct(lq.count { it.q > 38f }, lq.size), pct(lq.count { it.q > 30f && it.q <= 38f }, lq.size),
            pct(lq.count { it.q > 22f && it.q <= 30f }, lq.size), pct(lq.count { it.q <= 22f }, lq.size), links.sumOf { it.lost }))
    }
}
