package lt.example.gnss

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SP3 sprendinys v4 (tik testui): atmosferos pataisos (A), matavimų filtras, svertiniai MKM ir Hatch glodinimas.
 * Sagnac – −ω·τ su geometriniu τ (kaip v3). v1–v3 skaičiavimai ir failai nekeičiami.
 */
object Sp3V4 {
    const val OUT_FILE_V4 = "gnss_solver_sp3_test_v4.csv"
    private const val C = PositionSolver.C
    private const val W = Sp3Reader.WEEK
    private val MASK = Math.toRadians(10.0)

    // GnssMeasurement.ADR_STATE_*
    const val ADR_VALID = 1
    const val ADR_RESET = 2
    const val ADR_CYCLE_SLIP = 4

    class Meas(val svid: Int, val pr: Double, val cn0: Double, val state: Int, val multipath: Int, val adr: Double?, val adrState: Int)
    class Ep(val timeMs: Long, val week: Int, val tow: Double, val meas: List<Meas>)

    /** Atmetimų skaičius pagal priežastį. */
    class Rejects { var noTow = 0; var multipath = 0; var lowCn0 = 0; var noTime = 0 }

    /** gnss_raw.csv -> epochos su visais matavimo laukais (GPS L1); be laiko sinchronizacijos – noTime. */
    fun readRaw(raw: File, rej: Rejects? = null, consumer: (Ep) -> Unit) {
        if (!raw.exists()) return
        raw.bufferedReader().use { br ->
            val head = br.readLine()?.split(',') ?: return
            fun idx(n: String) = head.indexOf(n)
            val iMs = idx("laikas_ms"); val iC = idx("konstelacija"); val iSv = idx("svid"); val iSig = idx("signalas")
            val iTn = idx("time_nanos"); val iFb = idx("full_bias_nanos"); val iB = idx("bias_nanos"); val iOff = idx("time_offset_ns")
            val iSt = idx("state"); val iSvt = idx("recv_sv_time_ns"); val iCn = idx("cn0_dbhz")
            val iAdr = idx("adr_m"); val iAs = idx("adr_state"); val iMp = idx("multipath")
            if (listOf(iMs, iC, iSv, iSig, iTn, iFb, iB, iSt, iSvt, iCn).any { it < 0 }) return
            var curTn = Long.MIN_VALUE; var curMs = 0L; var curWeek = 0; var curTow = 0.0
            val list = ArrayList<Meas>()
            fun flush() { if (list.isNotEmpty()) consumer(Ep(curMs, curWeek, curTow, ArrayList(list))); list.clear() }
            br.forEachLine { line ->
                val p = line.split(',')
                if (p.size <= maxOf(iSvt, iSt, iTn, iCn)) return@forEachLine
                if (p[iC] != "GPS" || p[iSig] != "L1") return@forEachLine
                val tn = p[iTn].toLongOrNull() ?: return@forEachLine
                val fb = p[iFb].toLongOrNull() ?: return@forEachLine
                val b = p[iB].toDoubleOrNull() ?: 0.0
                val off = if (iOff >= 0) p.getOrNull(iOff)?.toDoubleOrNull() ?: 0.0 else 0.0
                val st = p[iSt].toIntOrNull() ?: return@forEachLine
                val svt = p[iSvt].toLongOrNull() ?: return@forEachLine
                val sv = p[iSv].toIntOrNull() ?: return@forEachLine
                if (tn != curTn) { flush(); curTn = tn }
                val r = PositionSolver.pseudorange(tn, fb, b, off, st, svt)
                if (r == null) { rej?.let { it.noTime++ }; return@forEachLine }
                curMs = p[iMs].toLongOrNull() ?: curMs
                curWeek = r.second
                curTow = r.third - off * 1e-9
                if (list.any { it.svid == sv }) return@forEachLine
                val adr = if (iAdr >= 0) p.getOrNull(iAdr)?.toDoubleOrNull() else null
                val adrSt = if (iAs >= 0) p.getOrNull(iAs)?.toIntOrNull() ?: 0 else 0
                val mp = if (iMp >= 0) p.getOrNull(iMp)?.toIntOrNull() ?: 0 else 0
                list.add(Meas(sv, r.first, p[iCn].toDoubleOrNull() ?: 0.0, st, mp, adr, adrSt))
            }
            flush()
        }
    }

    /** Filtras: TOW iššifruotas, daugiaspindis nepažymėtas (1 – aptiktas), C/N0 ≥ 20 dB-Hz. */
    fun accept(m: Meas, rej: Rejects?): Boolean {
        if (m.state and PositionSolver.STATE_TOW_DECODED == 0) { rej?.let { it.noTow++ }; return false }
        if (m.multipath == 1) { rej?.let { it.multipath++ }; return false }
        if (m.cn0 < 20.0) { rej?.let { it.lowCn0++ }; return false }
        return true
    }

    /**
     * Hatch filtras: P̄_k = P_k / n + (n−1)/n · (P̄_{k−1} + Φ_k − Φ_{k−1}), n ≤ window.
     * Iš naujo palydovui: nėra fazės / ADR ne VALID / RESET / CYCLE_SLIP, laiko tarpas > maxGap,
     * arba |(P−Φ) − vid(P−Φ)| > jump.
     */
    class Hatch(val window: Int = 100, val jump: Double = 10.0, val maxGap: Double = 1.5) {
        private class St(var n: Int, var sm: Double, var phase: Double, var cmpMean: Double, var t: Double)
        private val st = HashMap<Int, St>()
        var restarts = 0
            private set

        fun count(key: Int): Int = st[key]?.n ?: 0

        fun update(key: Int, code: Double, phase: Double?, adrState: Int, t: Double): Double {
            val ok = phase != null && (adrState and ADR_VALID) != 0 && (adrState and (ADR_RESET or ADR_CYCLE_SLIP)) == 0
            if (!ok) { st.remove(key); return code }
            val s = st[key]
            val cmp = code - phase!!
            if (s == null || t - s.t > maxGap || abs(cmp - s.cmpMean) > jump) {
                if (s != null) restarts++
                st[key] = St(1, code, phase, cmp, t)
                return code
            }
            val n = minOf(s.n + 1, window)
            s.sm = code / n + (n - 1.0) / n * (s.sm + (phase - s.phase))
            s.cmpMean += (cmp - s.cmpMean) / n
            s.n = n; s.phase = phase; s.t = t
            return s.sm
        }
    }

    class Opts(val mask: Boolean, val trop: Boolean, val iono: Boolean, val weighted: Boolean)
    class Row(val svid: Int, val elDeg: Double, val trop: Double, val iono: Double, val used: Boolean)
    class Fit(val sol: PositionSolver.Solution, val rows: List<Row>)

    private class Sat(val svid: Int, val x: Double, val y: Double, val z: Double, val prc: Double, val tau0: Double, val cn0: Double) {
        var rx = x; var ry = y
    }

    /**
     * Vienos epochos sprendinys. Pataisos ir maska taikomos, kai įvertis priartėja (< 1 km žingsnis):
     * aukščio kampas iš dabartinio įverčio, radianais; vėlinimai įtraukiami į modelį ρ + b + T + I, t. y. atimami iš pseudoatstumo.
     */
    fun solve(week: Int, tow: Double, obs: List<Triple<Int, Double, Double>>, sp3: Sp3Reader, o: Opts): Fit? {
        val tRx = week * W + tow
        val sats = ArrayList<Sat>()
        for ((svid, pr, cn0) in obs) {
            val sat = "G%02d".format(Locale.US, svid)
            val tSv = tRx - pr / C
            var st = Sp3Solver.satState(sp3, sat, tSv) ?: continue
            st = Sp3Solver.satState(sp3, sat, tSv - st.dts) ?: continue
            sats.add(Sat(svid, st.pos[0], st.pos[1], st.pos[2], pr + C * st.dts, tRx - (tSv - st.dts), cn0))
        }
        if (sats.size < 4) return null
        val s = doubleArrayOf(0.0, 0.0, 6_378_137.0, 0.0)
        var lastStep = Double.MAX_VALUE
        var rows = emptyList<Row>()
        for (it in 0 until 40) {
            val near = lastStep < 1000.0
            val q = DoubleArray(3)
            val hh = ArrayList<DoubleArray>(); val rr = ArrayList<Double>(); val ww = ArrayList<Double>()
            val rowsNow = ArrayList<Row>()
            for (sat in sats) {
                fun rng(): Double { val dx = s[0] - sat.rx; val dy = s[1] - sat.ry; val dz = s[2] - sat.z; return sqrt(dx * dx + dy * dy + dz * dz) }
                val tg = rng() / C
                val tau = if (tg in 0.05..0.11) tg else sat.tau0
                val th = PositionSolver.OMEGA_E * tau
                sat.rx = cos(th) * sat.x + sin(th) * sat.y
                sat.ry = -sin(th) * sat.x + cos(th) * sat.y
                val rho = rng()
                q[0] = sat.rx; q[1] = sat.ry; q[2] = sat.z
                var el = Math.PI / 2; var trop = 0.0; var iono = 0.0
                if (near) {
                    val l = Sp3Solver.look(s, q, rho)                    // [lat, lon, h, az, el] – radianais
                    el = l[4]
                    if (o.trop) trop = Sp3Solver.saastamoinen(l[0], l[2], el)
                    if (o.iono) iono = Sp3Solver.klobuchar(l[0], l[1], l[3], el, tow)
                }
                val used = !(o.mask && near && el < MASK)
                rowsNow.add(Row(sat.svid, Math.toDegrees(el), trop, iono, used))
                if (!used) continue
                hh.add(doubleArrayOf((s[0] - sat.rx) / rho, (s[1] - sat.ry) / rho, (s[2] - sat.z) / rho, 1.0))
                rr.add(sat.prc - (rho + s[3] + trop + iono))
                ww.add(if (o.weighted && near) sin(el).pow(2) * 10.0.pow((sat.cn0 - 45.0) / 10.0) else 1.0)
            }
            if (hh.size < 4) return null
            val d = lsqW(hh, rr, ww) ?: return null
            for (k in 0..3) s[k] += d[k]
            val step = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            rows = rowsNow
            if (near && step < 1e-3) {
                var ss = 0.0; var sw = 0.0
                for (i in rr.indices) { ss += ww[i] * rr[i] * rr[i]; sw += ww[i] }
                val llh = PositionSolver.ecefToLlh(s[0], s[1], s[2])
                val sol = PositionSolver.Solution(Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2], hh.size,
                    sqrt(ss / sw) * 2.0, doubleArrayOf(s[0], s[1], s[2]))
                return Fit(sol, rows)
            }
            lastStep = step
        }
        return null
    }

    /** Svertinis MKM žingsnis: (HᵀWH)⁻¹ HᵀW r. */
    private fun lsqW(h: List<DoubleArray>, r: List<Double>, w: List<Double>): DoubleArray? {
        val n = 4
        val a = Array(n) { DoubleArray(n + 1) }
        for (i in h.indices) for (j in 0 until n) {
            for (k in 0 until n) a[j][k] += w[i] * h[i][j] * h[i][k]
            a[j][n] += w[i] * h[i][j] * r[i]
        }
        for (c in 0 until n) {
            var p = c
            for (k in c + 1 until n) if (abs(a[k][c]) > abs(a[p][c])) p = k
            if (abs(a[p][c]) < 1e-18) return null
            val t = a[c]; a[c] = a[p]; a[p] = t
            for (k in 0 until n) if (k != c) {
                val f = a[k][c] / a[c][c]
                for (j in c..n) a[k][j] -= f * a[c][j]
            }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

    class StepResult(val name: String, val sols: List<Pair<Long, PositionSolver.Solution>>)

    /** Vienas žingsnis per visą įrašą; out – rašomas CSV (tik galutiniam v4). */
    fun run(raw: File, sp3: Sp3Reader, name: String, o: Opts, filter: Boolean, hatch: Boolean, out: File? = null,
            rej: Rejects? = null, hatchState: Hatch? = null): StepResult {
        val sols = ArrayList<Pair<Long, PositionSolver.Solution>>()
        val hf = hatchState ?: Hatch()
        val w = out?.bufferedWriter()
        try {
            w?.write(PositionSolver.SOLVER_HEADER)
            readRaw(raw) { ep ->
                val t = ep.week * W + ep.tow
                val ms = if (filter) ep.meas.filter { accept(it, rej) } else ep.meas
                val obs = ms.map { m ->
                    val pr = if (hatch) hf.update(m.svid, m.pr, m.adr, m.adrState, t) else m.pr
                    Triple(m.svid, pr, m.cn0)
                }
                if (!Sp3Solver.inRange(sp3, t)) return@readRaw
                val f = solve(ep.week, ep.tow, obs, sp3, o) ?: return@readRaw
                sols.add(ep.timeMs to f.sol)
                val s = f.sol
                w?.write(String.format(Locale.US, "%d,%.8f,%.8f,%.2f,%d,%.2f\n", ep.timeMs, s.lat, s.lon, s.h, s.sats, s.hacc))
            }
        } finally { w?.close() }
        return StepResult(name, sols)
    }

    private fun stats(r: StepResult, ref: Pair<Double, Double>?, fixH: Double?): String {
        if (r.sols.isEmpty()) return "${r.name}: nėra sprendinių"
        val o = ref?.let { Sp3Solver.offsets(r.sols, it) }
        val h = PositionSolver.percentile(r.sols.map { it.second.h }.sorted(), 0.5)
        val sats = PositionSolver.percentile(r.sols.map { it.second.sats.toDouble() }.sorted(), 0.5)
        return "${r.name}: " + (o?.let { "vid. rytai %+.2f m, vid. šiaurė %+.2f m, atstumo mediana %.2f m, sklaida %.2f m".format(Locale.US, it[0], it[1], it[2], it[3]) }
            ?: "atstumų nėra (nėra atskaitos taško)") +
            "; aukščio skirtumas nuo fikso " + (fixH?.let { "%+.2f m".format(Locale.US, h - it) } ?: "nėra") +
            "; palydovų mediana %.0f; sprendinių %d".format(Locale.US, sats, r.sols.size)
    }

    /** „ATMOSFEROS PATIKRA“: 3 palydovai vienoje epochoje ir pataisų įtaka atskirai (kaukė, troposfera, jonosfera). */
    fun atmosphereBlock(raw: File, sp3: Sp3Reader, ref: Pair<Double, Double>?, fixH: Double?): String = buildString {
        appendLine()
        appendLine("--- ATMOSFEROS PATIKRA ---")
        appendLine("Patikrinta: vėlinimai įtraukti į modelį ρ + b + T + I (atimami iš pseudoatstumo); aukščio kampas radianais " +
            "iš dabartinio imtuvo įverčio; Klobuchar – pusapskritimiais pagal IS-GPS-200; kartografavimas 1/sin(el)")
        var shown = false
        readRaw(raw) { ep ->
            if (shown) return@readRaw
            val t = ep.week * W + ep.tow
            if (!Sp3Solver.inRange(sp3, t)) return@readRaw
            val f = solve(ep.week, ep.tow, ep.meas.map { Triple(it.svid, it.pr, it.cn0) }, sp3, Opts(true, true, true, false)) ?: return@readRaw
            shown = true
            val rows = f.rows.sortedByDescending { it.elDeg }
            val pick = if (rows.size >= 3) listOf(rows.first(), rows[rows.size / 2], rows.last()) else rows
            appendLine("Epocha ${ep.timeMs}:")
            pick.forEach { r ->
                appendLine("  G%02d: aukščio kampas %.1f°, troposfera %.2f m, Klobuchar %.2f m%s".format(Locale.US,
                    r.svid, r.elDeg, r.trop, r.iono, if (r.used) "" else " (atmestas < 10°)"))
            }
        }
        if (!shown) appendLine("Nėra epochos su sprendiniu SP3 intervale")
        appendLine("Pataisų įtaka (visas įrašas, be filtro ir svorių):")
        appendLine("  " + stats(run(raw, sp3, "be pataisų ir kaukės", Opts(false, false, false, false), false, false), ref, fixH))
        appendLine("  " + stats(run(raw, sp3, "tik 10° kaukė", Opts(true, false, false, false), false, false), ref, fixH))
        appendLine("  " + stats(run(raw, sp3, "kaukė + troposfera", Opts(true, true, false, false), false, false), ref, fixH))
        appendLine("  " + stats(run(raw, sp3, "kaukė + troposfera + jonosfera", Opts(true, true, true, false), false, false), ref, fixH))
    }

    /** „SP3 TESTAS v4“: A → +filtras → +svoriai → +Hatch; paskutinis rašomas į gnss_solver_sp3_test_v4.csv. */
    fun v4Block(raw: File, sp3: Sp3Reader, outV4: File, ref: Pair<Double, Double>?, fixH: Double?): String = buildString {
        appendLine()
        appendLine("--- SP3 TESTAS v4 ---")
        appendLine("Atskaitos taškas: " + (ref?.let { "%.7f, %.7f (gnss_fix.csv mediana)".format(Locale.US, it.first, it.second) } ?: "nėra") +
            "; fikso aukščio mediana: " + (fixH?.let { "%.2f m".format(Locale.US, it) } ?: "nėra"))
        val a = Opts(true, true, true, false); val aw = Opts(true, true, true, true)
        val rej = Rejects()
        val hatch = Hatch()
        val steps = listOf(
            run(raw, sp3, "A (pataisos)", a, false, false),
            run(raw, sp3, "A + filtras", a, true, false, rej = rej),
            run(raw, sp3, "A + filtras + svoriai", aw, true, false),
            run(raw, sp3, "A + filtras + svoriai + Hatch", aw, true, true, outV4, hatchState = hatch))
        steps.forEach { appendLine(stats(it, ref, fixH)) }
        appendLine("Filtro atmetimai: TOW neiššifruotas ${rej.noTow}, daugiaspindis ${rej.multipath}, C/N0 < 20 dB-Hz ${rej.lowCn0}; " +
            "be laiko sinchronizacijos (neįtraukta į epochas) – žr. matavimų skyrių")
        appendLine("Hatch: langas 100 epochų, glodinimo paleidimų iš naujo (šuolis > 10 m / tarpas): ${hatch.restarts}; failas $OUT_FILE_V4")
    }
}
