package lt.example.gnss

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SP3 sprendinys v5 (tik testui): GPS L1 C/A + Galileo E1, atskiras imtuvo laikrodis kiekvienai konsteliacijai,
 * Hatch glodinimas su ADR arba Doppler, RAIM ir Kalmano filtras. v1–v4 nekeičiami.
 * GLONASS nenaudojamas (kitas laiko pagrindas ir FDMA dažniai), BeiDou – tik jei yra SP3 (+14 s BDT).
 * TGD (GPS L1) imamas iš navigacijos pranešimų 1 subkadro, jei jie iššifruoti.
 */
object Sp3V5 {
    const val OUT_FILE_V5 = "gnss_solver_sp3_test_v5.csv"
    private const val C = PositionSolver.C
    private const val W = Sp3Reader.WEEK
    private val MASK = Math.toRadians(10.0)
    const val STATE_TOW_DECODED = 8

    class M(val sys: Char, val svid: Int, val pr: Double, val cn0: Double, val state: Int, val mp: Int,
            val adr: Double?, val adrState: Int, val prr: Double?) {
        val key get() = sys.code * 1000 + svid
        val id get() = "%c%02d".format(Locale.US, sys, svid)
    }
    class Ep(val timeMs: Long, val week: Int, val tow: Double, val meas: List<M>) { val t get() = week * W + tow }

    /** Pseudoatstumas iš Android laukų; reikia iššifruoto TOW. sysOffsetS – sistemos laiko poslinkis nuo GPS (BDT: −14 s). */
    fun pseudorange(tn: Long, fb: Long, bias: Double, off: Double, state: Int, svt: Long, sysOffsetS: Double): Triple<Double, Int, Double>? {
        if (state and STATE_TOW_DECODED == 0) return null
        val weekNs = 604_800_000_000_000L
        val gpsNs = tn - fb
        val week = Math.floorDiv(gpsNs, weekNs).toInt()
        val tRxNs = (gpsNs - week * weekNs).toDouble() + off - bias
        var dt = (tRxNs + sysOffsetS * 1e9 - svt) % 604_800e9
        if (dt < 0) dt += 604_800e9
        if (dt > 302_400e9) dt -= 604_800e9
        val pr = dt * 1e-9 * C
        if (pr < 1.5e7 || pr > 3.6e7) return null
        return Triple(pr, week, tRxNs * 1e-9)
    }

    private fun sysOf(c: String, sig: String): Pair<Char, Double>? = when {
        c == "GPS" && sig == "L1" -> 'G' to 0.0
        c == "GAL" && sig == "E1" -> 'E' to 0.0
        c == "BDS" && sig == "B1I" -> 'C' to -14.0
        else -> null
    }

    fun readRaw(raw: File, consumer: (Ep) -> Unit) {
        if (!raw.exists()) return
        raw.bufferedReader().use { br ->
            val head = br.readLine()?.split(',') ?: return
            fun idx(n: String) = head.indexOf(n)
            val iMs = idx("laikas_ms"); val iC = idx("konstelacija"); val iSv = idx("svid"); val iSig = idx("signalas")
            val iTn = idx("time_nanos"); val iFb = idx("full_bias_nanos"); val iB = idx("bias_nanos"); val iOff = idx("time_offset_ns")
            val iSt = idx("state"); val iSvt = idx("recv_sv_time_ns"); val iCn = idx("cn0_dbhz")
            val iAdr = idx("adr_m"); val iAs = idx("adr_state"); val iMp = idx("multipath"); val iPrr = idx("doplerio_mps")
            if (listOf(iMs, iC, iSv, iSig, iTn, iFb, iB, iSt, iSvt, iCn).any { it < 0 }) return
            var curTn = Long.MIN_VALUE; var curMs = 0L; var curWeek = 0; var curTow = 0.0
            val list = ArrayList<M>()
            fun flush() { if (list.isNotEmpty()) consumer(Ep(curMs, curWeek, curTow, ArrayList(list))); list.clear() }
            br.forEachLine { line ->
                val p = line.split(',')
                if (p.size <= maxOf(iSvt, iSt, iTn, iCn, iSig)) return@forEachLine
                val sys = sysOf(p[iC], p[iSig]) ?: return@forEachLine
                val tn = p[iTn].toLongOrNull() ?: return@forEachLine
                val fb = p[iFb].toLongOrNull() ?: return@forEachLine
                val b = p[iB].toDoubleOrNull() ?: 0.0
                val off = if (iOff >= 0) p.getOrNull(iOff)?.toDoubleOrNull() ?: 0.0 else 0.0
                val st = p[iSt].toIntOrNull() ?: return@forEachLine
                val svt = p[iSvt].toLongOrNull() ?: return@forEachLine
                val sv = p[iSv].toIntOrNull() ?: return@forEachLine
                if (tn != curTn) { flush(); curTn = tn }
                val r = pseudorange(tn, fb, b, off, st, svt, sys.second) ?: return@forEachLine
                curMs = p[iMs].toLongOrNull() ?: curMs
                curWeek = r.second; curTow = r.third - off * 1e-9
                if (list.any { it.sys == sys.first && it.svid == sv }) return@forEachLine
                list.add(M(sys.first, sv, r.first, p[iCn].toDoubleOrNull() ?: 0.0, st,
                    if (iMp >= 0) p.getOrNull(iMp)?.toIntOrNull() ?: 0 else 0,
                    if (iAdr >= 0) p.getOrNull(iAdr)?.toDoubleOrNull() else null,
                    if (iAs >= 0) p.getOrNull(iAs)?.toIntOrNull() ?: 0 else 0,
                    if (iPrr >= 0) p.getOrNull(iPrr)?.toDoubleOrNull() else null))
            }
            flush()
        }
    }

    fun accept(m: M) = m.state and STATE_TOW_DECODED != 0 && m.mp != 1 && m.cn0 >= 20.0

    /** Glodinimas: ADR (fazė), kitaip integruotas Doppler; tas pats Hatch langas ir paleidimo iš naujo taisyklės. */
    class Smoother(val doppler: Boolean) {
        val adrHatch = Sp3V4.Hatch(); val dopHatch = Sp3V4.Hatch()
        private val acc = HashMap<Int, DoubleArray>()        // [integralas, paskutinis prr, paskutinis t]
        var nAdr = 0; var nDop = 0; var nNone = 0

        fun apply(m: M, t: Double): Double {
            val adrOk = m.adr != null && (m.adrState and Sp3V4.ADR_VALID) != 0 &&
                (m.adrState and (Sp3V4.ADR_RESET or Sp3V4.ADR_CYCLE_SLIP)) == 0
            if (adrOk) { nAdr++; return adrHatch.update(m.key, m.pr, m.adr, m.adrState, t) }
            if (doppler && m.prr != null) {
                val a = acc[m.key]
                val v = if (a != null && t - a[2] in 0.0..1.5) a[0] + 0.5 * (m.prr + a[1]) * (t - a[2]) else 0.0
                acc[m.key] = doubleArrayOf(v, m.prr, t)
                nDop++
                return dopHatch.update(m.key, m.pr, v, Sp3V4.ADR_VALID, t)
            }
            nNone++
            return m.pr
        }
    }

    /** Palydovo duomenys epochai: nepasukta padėtis, greitis, pataisytas pseudoatstumas, laikrodžio dreifas (m/s). */
    class SatEp(val sys: Char, val svid: Int, val pos: DoubleArray, val vel: DoubleArray, val prc: Double, val tau0: Double,
                val cn0: Double, val clkDrift: Double, val prr: Double?) {
        val rp = DoubleArray(3)
        fun rotate(rx: DoubleArray) {
            val dx = rx[0] - rp[0]; val dy = rx[1] - rp[1]; val dz = rx[2] - pos[2]
            val tg = if (rp[0] == 0.0 && rp[1] == 0.0) 0.0 else sqrt(dx * dx + dy * dy + dz * dz) / C
            val tau = if (tg in 0.05..0.13) tg else tau0
            val th = PositionSolver.OMEGA_E * tau
            rp[0] = cos(th) * pos[0] + sin(th) * pos[1]; rp[1] = -sin(th) * pos[0] + cos(th) * pos[1]; rp[2] = pos[2]
        }
    }

    class Ob(val sys: Char, val svid: Int, val pr: Double, val cn0: Double, val prr: Double? = null)

    fun prepare(t: Double, obs: List<Ob>, sp3: Sp3Reader): List<SatEp> {
        val out = ArrayList<SatEp>()
        for (o in obs) {
            val id = "%c%02d".format(Locale.US, o.sys, o.svid)
            val tSv = t - o.pr / C
            var st = Sp3Solver.satState(sp3, id, tSv) ?: continue
            val tTx = tSv - st.dts
            st = Sp3Solver.satState(sp3, id, tTx) ?: continue
            val w = Math.floorDiv(tTx.toLong(), W.toLong()).toInt(); val sow = tTx - w * W
            val a = sp3.positionXyz(id, w, sow - 0.5) ?: continue; val b = sp3.positionXyz(id, w, sow + 0.5) ?: continue
            val ca = sp3.clock(id, w, sow - 0.5); val cb = sp3.clock(id, w, sow + 0.5)
            val drift = if (ca != null && cb != null && !ca.isNaN() && !cb.isNaN()) (cb - ca) * 1e-6 * C else 0.0
            out.add(SatEp(o.sys, o.svid, st.pos, DoubleArray(3) { (b[it] - a[it]) * 1000.0 }, o.pr + C * st.dts, t - tTx, o.cn0, drift, o.prr))
        }
        return out
    }

    fun weight(el: Double, cn0: Double) = sin(el).pow(2) * 10.0.pow((cn0 - 45.0) / 10.0)

    class Fit(val sol: PositionSolver.Solution, val clocks: Map<Char, Double>, val x: DoubleArray, val dropped: List<String>, val sats: List<SatEp>)

    /**
     * Svertiniai MKM su atmosferos pataisomis ir 10° kauke; isb – atskiras laikrodis kiekvienai konsteliacijai;
     * raim – jei didžiausia normuota liekana > 3σ ir lieka ≥ 5 palydovai, palydovas atmetamas ir sprendžiama iš naujo.
     */
    fun solve(week: Int, tow: Double, obs: List<Ob>, sp3: Sp3Reader, isb: Boolean, raim: Boolean, weighted: Boolean = true): Fit? {
        var sats = prepare(week * W + tow, obs, sp3)
        val dropped = ArrayList<String>()
        while (true) {
            val f = lsq(sats, tow, isb, weighted) ?: return null
            if (!raim) return Fit(f.sol, f.clocks, f.x, dropped, sats)
            val (worst, nr) = f.worst
            if (worst == null || nr <= 3.0 || f.used - 1 < 5) return Fit(f.sol, f.clocks, f.x, dropped, sats)
            dropped.add("%c%02d".format(Locale.US, worst.sys, worst.svid))
            sats = sats.filter { it !== worst }
        }
    }

    private class Lsq(val sol: PositionSolver.Solution, val clocks: Map<Char, Double>, val x: DoubleArray, val worst: Pair<SatEp?, Double>, val used: Int)

    private fun lsq(sats: List<SatEp>, tow: Double, isb: Boolean, weighted: Boolean): Lsq? {
        if (sats.size < 4) return null
        val systems = if (isb) sats.map { it.sys }.distinct().sorted() else listOf('*')
        val np = 3 + systems.size
        val x = DoubleArray(np); x[2] = 6_378_137.0
        sats.forEach { it.rp.fill(0.0) }
        var lastStep = Double.MAX_VALUE
        for (iter in 0 until 40) {
            val near = lastStep < 1000.0
            val rows = ArrayList<Triple<SatEp, DoubleArray, Double>>(); val res = ArrayList<Double>()
            for (s in sats) {
                s.rotate(x)
                val dx = x[0] - s.rp[0]; val dy = x[1] - s.rp[1]; val dz = x[2] - s.rp[2]
                val rho = sqrt(dx * dx + dy * dy + dz * dz)
                var corr = 0.0; var w = 1.0
                if (near) {
                    val l = Sp3Solver.look(x, s.rp, rho)
                    if (l[4] < MASK) continue
                    corr = Sp3Solver.saastamoinen(l[0], l[2], l[4]) + Sp3Solver.klobuchar(l[0], l[1], l[3], l[4], tow)
                    if (weighted) w = weight(l[4], s.cn0)
                }
                val h = DoubleArray(np); h[0] = dx / rho; h[1] = dy / rho; h[2] = dz / rho
                val ci = if (isb) systems.indexOf(s.sys) else 0
                h[3 + ci] = 1.0
                rows.add(Triple(s, h, w)); res.add(s.prc - (rho + x[3 + ci] + corr))
            }
            // kiekvienam laikrodžiui reikia bent vieno palydovo; nenaudojamas laikrodis neįvertinamas
            val usedSys = rows.map { it.first.sys }.toSet()
            if (isb && systems.any { it !in usedSys }) return lsq(sats.filter { it.sys in usedSys }, tow, isb, weighted)
            if (rows.size < np) return null
            val d = solveNormal(rows.map { it.second }, res, rows.map { it.third }) ?: return null
            for (k in 0 until np) x[k] += d[k]
            val step = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            if (near && step < 1e-3) {
                var ss = 0.0
                val post = rows.indices.map { i ->
                    var r = res[i]; for (k in 0 until np) r -= rows[i].second[k] * d[k]
                    ss += rows[i].third * r * r; r
                }
                val dof = (rows.size - np).coerceAtLeast(1)
                val s0 = sqrt(ss / dof)
                // Normuota liekana: |v|·√w / (σ̂ · √r_ii), r_ii = 1 − w·hᵀ(HᵀWH)⁻¹h (perteklius);
                // σ̂ – be tikrinamo palydovo (kad klaida nepadidintų savo pačios slenksčio)
                var worst: SatEp? = null; var nrMax = 0.0
                val ninv = if (rows.size >= np + 2) invertNormal(rows.map { it.second }, rows.map { it.third }) else null
                if (ninv != null) rows.indices.forEach { i ->
                    val h = rows[i].second; val wi = rows[i].third
                    var q = 0.0; for (a in 0 until np) for (b in 0 until np) q += h[a] * ninv[a][b] * h[b]
                    val rii = (1.0 - wi * q).coerceAtLeast(1e-6)
                    val ssi = ss - wi * post[i] * post[i] / rii
                    // apatinė riba 1 m: be triukšmo likusios liekanos ~0 ir būtų atmetami geri palydovai
                    val si = sqrt(ssi.coerceAtLeast(0.0) / (rows.size - np - 1)).coerceAtLeast(1.0)
                    val nr = abs(post[i]) * sqrt(wi) / (si * sqrt(rii))
                    if (nr > nrMax) { nrMax = nr; worst = rows[i].first }
                }
                val llh = PositionSolver.ecefToLlh(x[0], x[1], x[2])
                val clocks = if (isb) systems.indices.associate { systems[it] to x[3 + it] } else mapOf('*' to x[3])
                return Lsq(PositionSolver.Solution(Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2], rows.size, s0 * 2, doubleArrayOf(x[0], x[1], x[2])),
                    clocks, x.copyOf(), worst to nrMax, rows.size)
            }
            lastStep = step
        }
        return null
    }

    private fun invertNormal(h: List<DoubleArray>, w: List<Double>): Array<DoubleArray>? {
        val n = h[0].size
        val a = Array(n) { DoubleArray(2 * n) }
        for (i in h.indices) for (j in 0 until n) for (k in 0 until n) a[j][k] += w[i] * h[i][j] * h[i][k]
        for (j in 0 until n) a[j][n + j] = 1.0
        for (c in 0 until n) {
            var p = c
            for (k in c + 1 until n) if (abs(a[k][c]) > abs(a[p][c])) p = k
            if (abs(a[p][c]) < 1e-18) return null
            val t = a[c]; a[c] = a[p]; a[p] = t
            val d = a[c][c]; for (j in 0 until 2 * n) a[c][j] /= d
            for (k in 0 until n) if (k != c) { val f = a[k][c]; if (f != 0.0) for (j in 0 until 2 * n) a[k][j] -= f * a[c][j] }
        }
        return Array(n) { i -> DoubleArray(n) { j -> a[i][n + j] } }
    }

    private fun solveNormal(h: List<DoubleArray>, r: List<Double>, w: List<Double>): DoubleArray? {
        val n = h[0].size
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
            for (k in 0 until n) if (k != c) { val f = a[k][c] / a[c][c]; for (j in c..n) a[k][j] -= f * a[c][j] }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

    /**
     * Kalmano filtras: būsena [x, y, z, vx, vy, vz, dreifas d, laikrodis kiekvienai konsteliacijai].
     * Matavimai – glodinti pseudoatstumai (ρ + b_s + T + I) ir Doppler (u·(v_sat − v) + d − ċ_sat).
     * Beveik nejudantis imtuvas: maža greičio proceso triukšmo spektrinė galia. Didelės inovacijos – paleidimas iš naujo.
     */
    class Kalman(val systems: List<Char>, val qVel: Double = 1e-4, val qDrift: Double = 0.01, val qClock: Double = 0.01,
                 val codeBase: Double = 7.0, val elevWeight: Boolean = false,
                 /** Kodo inovacijos riba (σ): v5 – 6, v6 – 4. */
                 val codeGate: Double = 6.0,
                 /** v6: bendras kodo laikrodžio šuolis (mediana > realignM) perkeliamas į laikrodžio būseną prieš atnaujinimą. */
                 val realignM: Double = Double.NaN) {
        val n = 7 + systems.size
        var x = DoubleArray(n); var p = Array(n) { DoubleArray(n) }
        var t = Double.NaN
        var resets = 0
        val ready get() = !t.isNaN()

        fun init(fit: Fit, time: Double) {
            x = DoubleArray(n); p = Array(n) { DoubleArray(n) }
            for (k in 0..2) { x[k] = fit.x[k]; p[k][k] = 100.0 }
            for (k in 3..5) p[k][k] = 1.0
            p[6][6] = 1e4
            val shared = fit.clocks['*']
            systems.forEachIndexed { i, s -> x[7 + i] = fit.clocks[s] ?: shared ?: fit.clocks.values.first(); p[7 + i][7 + i] = 1e4 }
            t = time
        }

        private fun predict(dt: Double) {
            val f = Array(n) { i -> DoubleArray(n).also { it[i] = 1.0 } }
            for (k in 0..2) f[k][3 + k] = dt
            for (i in systems.indices) f[7 + i][6] = dt
            val nx = DoubleArray(n) { i -> (0 until n).sumOf { f[i][it] * x[it] } }
            val fp = Array(n) { i -> DoubleArray(n) { j -> (0 until n).sumOf { f[i][it] * p[it][j] } } }
            val np2 = Array(n) { i -> DoubleArray(n) { j -> (0 until n).sumOf { fp[i][it] * f[j][it] } } }
            for (k in 3..5) np2[k][k] += qVel * dt
            for (k in 0..2) np2[k][k] += qVel * dt * dt * dt / 3
            np2[6][6] += qDrift * dt
            for (i in systems.indices) np2[7 + i][7 + i] += qClock * dt
            x = nx; p = np2
        }

        /** Skaliarinis atnaujinimas; grąžina normuotą inovaciją. */
        private fun update(h: DoubleArray, nu: Double, r: Double, gate: Double): Double {
            val ph = DoubleArray(n) { i -> (0 until n).sumOf { p[i][it] * h[it] } }
            val s = (0 until n).sumOf { h[it] * ph[it] } + r
            val z = nu / sqrt(s)
            if (abs(z) > gate) return z
            val k = DoubleArray(n) { ph[it] / s }
            for (i in 0 until n) x[i] += k[i] * nu
            for (i in 0 until n) for (j in 0 until n) p[i][j] -= k[i] * ph[j]
            return z
        }

        /** Kodo inovacijų mediana kiekvienai sistemai; jei > realignM – laikrodžio būsena pastumiama (kodo ir Doppler laikrodžio nesutapimas). */
        private fun realign(tow: Double, sats: List<SatEp>) {
            for ((si, sys) in systems.withIndex()) {
                val nu = ArrayList<Double>()
                for (s in sats) {
                    if (s.sys != sys) continue
                    s.rotate(x)
                    val dx = x[0] - s.rp[0]; val dy = x[1] - s.rp[1]; val dz = x[2] - s.rp[2]
                    val rho = sqrt(dx * dx + dy * dy + dz * dz)
                    val l = Sp3Solver.look(x, s.rp, rho)
                    if (l[4] < MASK) continue
                    val corr = Sp3Solver.saastamoinen(l[0], l[2], l[4]) + Sp3Solver.klobuchar(l[0], l[1], l[3], l[4], tow)
                    nu.add(s.prc - (rho + x[7 + si] + corr))
                }
                if (nu.size < 3) continue
                nu.sort()
                val med = nu[nu.size / 2]
                if (abs(med) > realignM) { x[7 + si] += med; realigned++ }
            }
        }
        var realigned = 0

        /** Vienos epochos žingsnis; lsqFit – tos epochos MKM sprendinys (pradžiai / paleidimui iš naujo). */
        fun step(time: Double, tow: Double, sats: List<SatEp>, lsqFit: Fit?): PositionSolver.Solution? {
            if (!ready || time - t <= 0.0 || time - t > 30.0) {
                if (lsqFit == null) return null
                init(lsqFit, time); return lsqFit.sol
            }
            predict(time - t); t = time
            if (!realignM.isNaN()) realign(tow, sats)
            var big = 0; var total = 0
            for (s in sats) {
                val si = systems.indexOf(s.sys); if (si < 0) continue
                s.rotate(x)
                val dx = x[0] - s.rp[0]; val dy = x[1] - s.rp[1]; val dz = x[2] - s.rp[2]
                val rho = sqrt(dx * dx + dy * dy + dz * dz)
                val l = Sp3Solver.look(x, s.rp, rho)
                if (l[4] < MASK) continue
                val w = if (elevWeight) weight(l[4], s.cn0) else 1.0
                val corr = Sp3Solver.saastamoinen(l[0], l[2], l[4]) + Sp3Solver.klobuchar(l[0], l[1], l[3], l[4], tow)
                val h = DoubleArray(n); h[0] = dx / rho; h[1] = dy / rho; h[2] = dz / rho; h[7 + si] = 1.0
                // Kodo σ pagal C/N0 (σ ∝ 10^(−C/N0/20)); elevWeight – papildomai σ² = a² + (b / sin el)²
                val cnF = 10.0.pow((45.0 - s.cn0) / 20.0)
                val sig = (if (!elevWeight) codeBase * cnF else codeBase * sqrt(1.0 + 1.0 / sin(l[4]).pow(2)) * cnF).coerceIn(3.0, 80.0)
                val z = update(h, s.prc - (rho + x[7 + si] + corr), sig * sig, codeGate)
                total++; if (abs(z) > codeGate) big++
                if (s.prr != null) {
                    val ux = -dx / rho; val uy = -dy / rho; val uz = -dz / rho
                    val pred = ux * (s.vel[0] - x[3]) + uy * (s.vel[1] - x[4]) + uz * (s.vel[2] - x[5]) + x[6] - s.clkDrift
                    val hd = DoubleArray(n); hd[3] = -ux; hd[4] = -uy; hd[5] = -uz; hd[6] = 1.0
                    val sd = (0.3 / sqrt(w)).coerceIn(0.1, 5.0)
                    update(hd, s.prr - pred, sd * sd, 8.0)
                }
            }
            if (total >= 4 && big * 2 > total) {                     // dauguma atmesta – paleidžiama iš naujo
                resets++
                if (lsqFit == null) { t = Double.NaN; return null }
                init(lsqFit, time); return lsqFit.sol
            }
            val llh = PositionSolver.ecefToLlh(x[0], x[1], x[2])
            return PositionSolver.Solution(Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2], total, 0.0, doubleArrayOf(x[0], x[1], x[2]))
        }
    }

    class Run(val name: String, val sols: List<Pair<Long, PositionSolver.Solution>>, val note: String = "")

    /** Vienas žingsnis per įrašą. */
    fun run(raw: File, sp3: Sp3Reader, name: String, isb: Boolean, doppler: Boolean, raim: Boolean, kalman: Boolean, out: File? = null,
            usage: HashMap<Char, Int>? = null, clockSum: HashMap<Char, ArrayList<Double>>? = null, sm: Smoother? = null,
            drops: HashMap<String, Int>? = null, tgd: Map<Int, Double> = emptyMap()): Run {
        val sols = ArrayList<Pair<Long, PositionSolver.Solution>>()
        val smoother = sm ?: Smoother(doppler)
        var kf: Kalman? = null
        val w = out?.bufferedWriter()
        try {
            w?.write(PositionSolver.SOLVER_HEADER)
            readRaw(raw) { ep ->
                val t = ep.t
                val obs = ep.meas.filter { accept(it) }.map { m ->
                    // L1 vartotojui palydovo laikrodis = SP3 (L1/L2 kombinacija) − TGD: P − c·TGD = ρ + c·dtr − c·dts
                    val tg = if (m.sys == 'G') tgd[m.svid] ?: 0.0 else 0.0
                    Ob(m.sys, m.svid, smoother.apply(m, t) - C * tg, m.cn0, m.prr)
                }.filter { isb || it.sys == 'G' }
                if (!Sp3Solver.inRange(sp3, t)) return@readRaw
                val fit = solve(ep.week, ep.tow, obs, sp3, isb, raim)
                fit?.dropped?.forEach { d -> drops?.let { it[d] = (it[d] ?: 0) + 1 } }
                val sol = if (!kalman) fit?.sol else {
                    if (kf == null) kf = Kalman(listOf('C', 'E', 'G'))   // nenaudojamas laikrodis tiesiog neatnaujinamas
                    val sats = prepare(t, obs.filter { o -> fit == null || fit.dropped.none { it == "%c%02d".format(Locale.US, o.sys, o.svid) } }, sp3)
                    kf!!.step(t, ep.tow, sats, fit)
                } ?: return@readRaw
                fit?.let { f ->
                    usage?.let { u -> f.sats.forEach { s -> u[s.sys] = (u[s.sys] ?: 0) + 1 } }
                    clockSum?.let { cs -> f.clocks.forEach { (k, v) -> cs.getOrPut(k) { ArrayList() }.add(v) } }
                }
                sols.add(ep.timeMs to sol)
                w?.write(String.format(Locale.US, "%d,%.8f,%.8f,%.2f,%d,%.2f\n", ep.timeMs, sol.lat, sol.lon, sol.h, sol.sats, sol.hacc))
            }
        } finally { w?.close() }
        return Run(name, sols)
    }

    private fun line(r: Run, ref: Pair<Double, Double>?, fixH: Double?): String {
        if (r.sols.isEmpty()) return "${r.name}: nėra sprendinių${r.note}"
        val sb = StringBuilder("${r.name}: ")
        if (ref != null) {
            val o = Sp3Solver.offsets(r.sols, ref)!!
            val mPerDeg = Math.toRadians(1.0) * 6_371_000.0; val cl = cos(Math.toRadians(ref.first))
            val d = r.sols.map { val e = (it.second.lon - ref.second) * mPerDeg * cl; val n = (it.second.lat - ref.first) * mPerDeg; sqrt(e * e + n * n) }.sorted()
            sb.append("vid. rytai %+.2f m, vid. šiaurė %+.2f m, atstumo mediana %.2f m, 90 %% %.2f m, sklaida %.2f m".format(Locale.US,
                o[0], o[1], o[2], PositionSolver.percentile(d, 0.9), o[3]))
        } else sb.append("atstumų nėra (nėra atskaitos taško)")
        val h = PositionSolver.percentile(r.sols.map { it.second.h }.sorted(), 0.5)
        sb.append("; aukščio skirtumas nuo fikso ").append(fixH?.let { "%+.2f m".format(Locale.US, h - it) } ?: "nėra")
        sb.append("; palydovų mediana %.0f; sprendinių %d".format(Locale.US, PositionSolver.percentile(r.sols.map { it.second.sats.toDouble() }.sorted(), 0.5), r.sols.size))
        return sb.append(r.note).toString()
    }

    /** „SP3 TESTAS v5“: v4 → +ISB → +TGD → +Doppler → +RAIM → +Kalman; paskutinis – gnss_solver_sp3_test_v5.csv. */
    fun v5Block(raw: File, sp3: Sp3Reader, outV5: File, ref: Pair<Double, Double>?, fixH: Double?,
                tgd: Map<Int, Double> = emptyMap()): String = buildString {
        appendLine()
        appendLine("--- SP3 TESTAS v5 ---")
        val v4 = Sp3V4.run(raw, sp3, "v4 bazė", Sp3V4.Opts(true, true, true, true), true, true)
        val usage = HashMap<Char, Int>(); val clocks = HashMap<Char, ArrayList<Double>>()
        val isbRun = run(raw, sp3, "+ atskiri laikrodžiai (ISB)", true, false, false, false, usage = usage, clockSum = clocks)
        val tgdRun = if (tgd.isEmpty()) null else run(raw, sp3, "+ TGD (iš navigacijos pranešimų)", true, false, false, false, tgd = tgd)
        val dSm = Smoother(true)
        val dop = run(raw, sp3, "+ Doppler glodinimas", true, true, false, false, sm = dSm, tgd = tgd)
        val drops = HashMap<String, Int>()
        val raimRun = run(raw, sp3, "+ RAIM", true, true, true, false, drops = drops, tgd = tgd)
        val kal = run(raw, sp3, "+ Kalmano filtras", true, true, true, true, outV5, tgd = tgd)
        val names = mapOf('G' to "GPS", 'E' to "Galileo", 'C' to "BeiDou")
        appendLine("Konsteliacijos (palydovų-epochų sprendiniuose): " +
            (if (usage.isEmpty()) "nėra" else usage.entries.sortedBy { it.key }.joinToString(", ") { "${names[it.key] ?: it.key} ${it.value}" }) +
            "; GLONASS nenaudojamas (kitas laiko pagrindas, FDMA)")
        appendLine("Imtuvo laikrodžio poslinkiai (mediana, m): " + clocks.entries.sortedBy { it.key }.joinToString(", ") {
            "${names[it.key] ?: it.key} %.1f".format(Locale.US, PositionSolver.percentile(it.value.sorted(), 0.5)) } +
            (clocks['G']?.let { g -> clocks['E']?.let { e ->
                val isb = g.indices.filter { it < e.size }.map { e[it] - g[it] }.sorted()
                if (isb.isEmpty()) "" else "; Galileo−GPS (ISB) mediana %.2f m".format(Locale.US, PositionSolver.percentile(isb, 0.5)) } } ?: ""))
        appendLine(line(v4.let { Run(it.name, it.sols) }, ref, fixH))
        appendLine(line(isbRun, ref, fixH))
        if (tgdRun == null) appendLine("+ TGD: nepritaikyta – navigacijos pranešimuose neiššifruota nė vienos GPS efemeridės; eilutė lygi ankstesnei")
        else {
            appendLine(line(tgdRun, ref, fixH))
            appendLine("  TGD (ns): " + tgd.entries.sortedBy { it.key }.joinToString(", ") { "G%02d %+.1f".format(Locale.US, it.key, it.value * 1e9) })
        }
        appendLine(line(dop, ref, fixH))
        appendLine(line(raimRun, ref, fixH))
        appendLine(line(kal, ref, fixH))
        appendLine("Glodinimas (palydovų-epochų): ADR ${dSm.nAdr}, Doppler ${dSm.nDop}, be glodinimo ${dSm.nNone}")
        appendLine("RAIM atmetimai pagal palydovą: " + if (drops.isEmpty()) "nėra" else drops.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" })
        appendLine("Failas $OUT_FILE_V5")
    }
}
