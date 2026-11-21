package lt.example.gnss

/**
 * Patarimas, kaip pasukti ar kur patraukti planšetę, kai NLO pamėlynuoja (signalai suprastėjo).
 *  - Mokosi pati: kiekvienai planšetės krypčiai (12 × 30°) – stipriausių 6 palydovų vidutinis C/N0 per paskutines 30 min.
 *    Jei kita kryptis buvo ≥ 3 dB geresnė už dabartinę – lenkta rodyklė „pasuk tiek laipsnių“.
 *  - Kitaip – iš kurios dangaus pusės (8 × 45°) signalai dabar stipriausi (≥ 5 dB už vidurkį): tiesi rodyklė ta kryptimi
 *    (ten langas / atviras dangus – patraukti ar atsukti planšetę į tą pusę).
 * Rodoma, kai NLO ≥ 10 s mėlynas ar juodas (kokybė < 31), slepiama, kai ≥ 5 s vėl žydras ar geltonas.
 */
object TurnHint {
    class S(val az: Double, val el: Double, val cn0: Double, val expected: Boolean)

    sealed class Hint(val gainDb: Double) {
        /** Pasukti planšetę deg laipsnių (+ pagal laikrodžio rodyklę). */
        class Turn(val deg: Double, gain: Double) : Hint(gain)
        /** Stipriausi signalai iš krypties relDeg nuo planšetės krypties (+ į dešinę). */
        class Toward(val relDeg: Double, gain: Double) : Hint(gain)
    }

    private const val FRESH_MS = 30 * 60_000L
    private val binV = DoubleArray(12) { Double.NaN }
    private val binMs = LongArray(12)
    private val binN = IntArray(12)
    private var curM = Double.NaN
    private var badSince = 0L
    private var goodSince = 0L
    @Volatile var shown = false
        private set
    /** NLO ryšio kokybė (tas pats skaičius, kuris lemia NLO spalvą). */
    @Volatile var q: Float? = null

    private fun norm(d: Double): Double { var x = d % 360.0; if (x > 180.0) x -= 360.0; if (x < -180.0) x += 360.0; return x }
    private fun bin(h: Double) = (((h % 360.0) + 360.0) % 360.0 / 30.0).toInt().coerceIn(0, 11)

    /** Stipriausių (iki 6) palydovų virš 10° vidutinis C/N0; null – per mažai. */
    fun metric(s: List<S>): Double? {
        val c = s.filter { it.el >= 10.0 && it.cn0 > 0.0 }.map { it.cn0 }.sortedDescending().take(6)
        return if (c.size < 4) null else c.average()
    }

    @Synchronized fun learn(now: Long, heading: Double?, s: List<S>) {
        val m = metric(s) ?: return
        curM = if (curM.isNaN()) m else curM * 0.7 + m * 0.3
        if (heading == null) return
        val b = bin(heading)
        val stale = binV[b].isNaN() || now - binMs[b] > FRESH_MS
        binV[b] = if (stale) m else binV[b] * 0.85 + m * 0.15
        binN[b] = if (stale) 1 else binN[b] + 1
        binMs[b] = now
    }

    /** NLO kokybė kas sekundę: grąžina, ar patarimą rodyti. */
    @Synchronized fun quality(now: Long, q: Float?): Boolean {
        val bad = q != null && q < 31f
        if (bad) { goodSince = 0L; if (badSince == 0L) badSince = now; if (now - badSince >= 10_000L) shown = true }
        else { badSince = 0L; if (goodSince == 0L) goodSince = now; if (now - goodSince >= 5_000L) shown = false }
        return shown
    }

    @Synchronized fun advice(now: Long, heading: Double?, s: List<S>): Hint? {
        if (heading == null || curM.isNaN()) return null
        val cb = bin(heading)
        var best = -1; var bv = Double.NEGATIVE_INFINITY
        for (i in 0 until 12) if (i != cb && !binV[i].isNaN() && now - binMs[i] < FRESH_MS && binN[i] >= 10 && binV[i] > bv) { bv = binV[i]; best = i }
        if (best >= 0 && bv - curM >= 3.0) return Hint.Turn(norm(best * 30.0 + 15.0 - heading), bv - curM)
        val vis = s.filter { it.el >= 10.0 && it.expected }
        if (vis.size < 6) return null
        val sec = (0 until 8).mapNotNull { k ->
            val l = vis.filter { ((((it.az % 360.0) + 360.0) % 360.0) / 45.0).toInt().coerceIn(0, 7) == k }
            if (l.size < 2) null else k to l.map { it.cn0 }.average()
        }
        if (sec.size < 3) return null
        val all = vis.map { it.cn0 }.average()
        val (k, v) = sec.maxByOrNull { it.second } ?: return null
        if (v - all < 5.0) return null
        return Hint.Toward(norm(k * 45.0 + 22.5 - heading), v - all)
    }

    @Synchronized fun reset() { binV.fill(Double.NaN); binN.fill(0); curM = Double.NaN; badSince = 0L; goodSince = 0L; shown = false }
}
