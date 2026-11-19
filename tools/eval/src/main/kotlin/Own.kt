package lt.example.gnss

import java.io.File

/**
 * Tavo paties įrašas (programos ZIP): gnss_raw.csv + SP3 orbitos iš ZIP -> programos skaičiavimas -> paklaidos.
 * Tiesa: žinomos koordinatės (iš ataskaitos „Žinomos koordinatės“ arba argumentu), kitaip – sprendinių mediana (tik sklaida).
 */
object Own {
    class Res(val name: String, val p50: Double, val p95: Double, val jumps: Int, val n: Int, val none: Int)

    var useGlo = true
    var maxJump = 0.0
    /** Transliuojamos efemeridės (BRDC) palydovams, kurių SP3 neturi (BeiDou, kai kurie GLONASS) – kaip programoje. */
    var brdc: Brdc.Set? = null
    var hybridN = 0; var epN = 0
    fun bState(set: Brdc.Set, sys: Char, svid: Int, t: Double): Pair<DoubleArray, Double>? {
        val id = "%c%02d".format(java.util.Locale.US, sys, svid)
        if (sys == 'R') { val e = set.glo[id]?.minByOrNull { Math.abs(it.tbGps - t) } ?: return null
            if (Math.abs(e.tbGps - t) > 1.5 * 3600) return null
            val pos = GloNav.position(e, t, 1.5 * 3600) ?: return null; return pos to (-e.tauN + e.gammaN * (t - e.tbGps)) }
        val k = set.kep[id]?.minByOrNull { Math.abs(it.toeAbs - t) } ?: return null
        if (Math.abs(k.toeAbs - t) > 4 * 3600) return null
        return Brdc.kepler(k, t) to Brdc.clock(k, t)
    }
    fun bPrepare(set: Brdc.Set, t: Double, obs: List<Sp3V5.Ob>): List<Sp3V5.SatEp> = obs.mapNotNull { o ->
        val C = 299_792_458.0
        val tSv = t - o.pr / C
        var st = bState(set, o.sys, o.svid, tSv) ?: return@mapNotNull null
        val tTx = tSv - st.second
        st = bState(set, o.sys, o.svid, tTx) ?: return@mapNotNull null
        val a = bState(set, o.sys, o.svid, tTx - 0.5); val b = bState(set, o.sys, o.svid, tTx + 0.5)
        val vel = if (a != null && b != null) DoubleArray(3) { b.first[it] - a.first[it] } else DoubleArray(3)
        val drift = if (a != null && b != null) (b.second - a.second) * C else 0.0
        Sp3V5.SatEp(o.sys, o.svid, st.first, vel, o.pr + C * st.second, t - tTx, o.cn0, drift, o.prr)
    }
    /** Aparatūros vėlinimas (s) pagal „G05“ – kaip programoje SP3 keliu atimamas iš pseudoatstumo. */
    var tgd: Map<String, Double> = emptyMap()
    val hacc = ArrayList<Double>()
    val resAll = ArrayList<Double>()
    val resBy = HashMap<String, ArrayList<Double>>()

    /** GPS TGD ir Galileo BGD(E1,E5b) iš BRDC (RINEX 3/4) – kaip Brdc.kt programoje. */
    fun tgdFromBrdc(f: File): Map<String, Double> {
        val l = f.readLines(); var i = l.indexOfFirst { "END OF HEADER" in it } + 1
        val out = HashMap<String, Double>()
        fun num(x: String) = x.trim().replace('D', 'E').toDoubleOrNull() ?: 0.0
        while (i < l.size) {
            val sv = l[i].take(3); val n = if (sv[0] == 'R' || sv[0] == 'S') 4 else 8
            if ((sv[0] == 'G' || sv[0] == 'E') && i + 6 < l.size) {
                val r = l[i + 6]; val v = num(r.substring(4 + 19 * (if (sv[0] == 'G') 2 else 3)).take(19))
                if (v != 0.0 && sv !in out) out[sv] = v
            }
            i += n
        }
        return out
    }
    fun run(raw: File, sp3: Sp3Reader, truth: Pair<Double, Double>?, thr: Double, smooth: Boolean, filter: Boolean, name: String,
            hMsl: Double? = null, geoidN: Double = 24.89, forceMove: Boolean = false): Pair<Res, List<DoubleArray>> {
        val sm = Sp3V5.Smoother(doppler = true, useAdr = false, dopWindow = 20)
        PosFilter.reset(); CnoMask.min = thr; CnoMask.auto = false
        PosFilter.moveGain = when { name.startsWith("M2") -> 0.2; name.startsWith("M1") -> 0.1; else -> 0.5 }
        val sols = ArrayList<DoubleArray>(); var none = 0
        var vel: DopplerVel.Vel? = null
        Sp3V5.readRaw(raw.bufferedReader(), glo = useGlo) { ep ->
            val ms = ep.meas.filter { (if (it.sys == 'R') (it.state and Sp3V5.STATE_GLO_TOD_DECODED) else (it.state and Sp3V5.STATE_TOW_DECODED)) != 0 }
            val smd = if (smooth) sm.applyEpoch(ms, ep.t) else emptyMap()
            val obs = ms.filter { it.cn0 >= thr }.map { m -> Sp3V5.Ob(m.sys, m.svid, (smd[m.key] ?: m.pr) - 299_792_458.0 * (tgd["%c%02d".format(m.sys, m.svid)] ?: 0.0), m.cn0, m.prr) }
            if (obs.size < 5 || !Sp3Solver.inRange(sp3, ep.t)) { none++; return@readRaw }
            val last = sols.lastOrNull()
            val hc = if (hMsl != null && last != null) Sp3V5.HCon(hMsl + geoidN, 2.0,
                PositionSolver.llhToEcef(Math.toRadians(last[0]), Math.toRadians(last[1]), hMsl + geoidN)) else null
            val f = try {
                val bs = brdc
                if (bs == null) Sp3V5.solve(ep.week, ep.tow, obs, sp3, isb = true, raim = true, hCon = hc) else {
                    val a = Sp3V5.prepare(ep.t, obs, sp3); val have = a.map { it.sys.code * 1000 + it.svid }.toSet()
                    val extra = bPrepare(bs, ep.t, obs.filter { (it.sys.code * 1000 + it.svid) !in have })
                    hybridN += extra.size; epN++
                    Sp3V5.solveSats(ep.tow, a + extra, isb = true, raim = true, hCon = hc)
                }
            } catch (_: Exception) { null }
            if (f == null) { none++; return@readRaw }
            hacc.add(f.sol.hacc); resAll.addAll(f.res.values.map { Math.abs(it) })
            f.res.forEach { (k, v) -> resBy.getOrPut(k) { ArrayList() }.add(v) }
            vel = try { DopplerVel.solve(f.sats, f.x) } catch (_: Exception) { null }
            var lat = f.sol.lat; var lon = f.sol.lon
            if (filter) {
                val v = vel
                val vNE = v?.let { doubleArrayOf(it.speedH * Math.cos(Math.toRadians(it.courseDeg)), it.speedH * Math.sin(Math.toRadians(it.courseDeg))) }
                val mv = forceMove || (v != null && v.speedH > 1.5 && v.sigma < 1.0) || (name.startsWith("T") && ((ep.timeMs / 1000) * 7919 % 13) < 4)
                val q = PosFilter.step(ep.timeMs, lat, lon, f.sol.h, f.sol.hacc, mv, vNE); lat = q[0]; lon = q[1]
            }
            sols.add(doubleArrayOf(lat, lon, f.sol.h, ep.timeMs.toDouble(), f.sol.sats.toDouble()))
        }
        val ref = truth ?: (sols.map { it[0] }.sorted()[sols.size / 2] to sols.map { it[1] }.sorted()[sols.size / 2])
        val err = sols.map { PositionSolver.horizDist(it[0], it[1], ref.first, ref.second) }.sorted()
        var jumps = 0; maxJump = 0.0
        for (i in 1 until sols.size) { val dj = PositionSolver.horizDist(sols[i - 1][0], sols[i - 1][1], sols[i][0], sols[i][1]); if (dj > 5.0) jumps++; maxJump = maxOf(maxJump, dj) }
        fun q(p: Double) = if (err.isEmpty()) Double.NaN else err[((err.size - 1) * p).toInt()]
        return Res(name, q(0.5), q(0.95), jumps, sols.size, none) to sols
    }

    /** TGD pataisos patikra: kaip programoje be TGD ir su TGD iš BRDC; liekanos rodo, ar palydovų atstumai geriau sutampa. */
    fun tgdMain(dir: File) {
        val raw = File(dir, "gnss_raw.csv")
        val sp3 = (dir.listFiles()!!.firstOrNull { it.name.endsWith(".SP3", true) } ?: return).inputStream().use { Sp3Reader.read(it) }
        val br = dir.listFiles()!!.firstOrNull { it.name.startsWith("brdc") } ?: return
        val t = tgdFromBrdc(br)
        println("=== ${dir.name}: TGD/BGD iš BRDC – ${t.size} palydovų")
        for (on in listOf(false, true)) {
            tgd = if (on) t else emptyMap(); hacc.clear(); resAll.clear(); resBy.clear()
            val (r, sols) = run(raw, sp3, null, 20.0, true, true, "x")
            val h = hacc.sorted(); val ra = resAll.sorted()
            val med = sols.map { it[0] }.sorted()[sols.size / 2] to sols.map { it[1] }.sorted()[sols.size / 2]
            println("   %-14s n=%5d  sklaida p50 %5.2f p95 %6.2f m  σ̂ (2·s0) mediana %5.2f m | |liekana| p50 %5.2f p90 %5.2f m  vidurys %.6f %.6f".format(
                if (on) "+ TGD" else "be TGD", r.n, r.p50, r.p95, h[h.size / 2], ra[ra.size / 2], ra[(ra.size * 0.9).toInt()], med.first, med.second))
            // palydovo vidutinė liekana (mediana) prieš c·TGD: be pataisos turėtų koreliuoti teigiamai
            for (sys in listOf('G', 'E')) {
                val pts = resBy.filter { it.key[0] == sys && it.value.size >= 100 && it.key in t }.map { (k, v) -> v.sorted()[v.size / 2] to 299_792_458.0 * t[k]!! }
                if (pts.size < 4) continue
                val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
                val cxy = pts.sumOf { (it.first - mx) * (it.second - my) }; val cxx = pts.sumOf { (it.first - mx) * (it.first - mx) }; val cyy = pts.sumOf { (it.second - my) * (it.second - my) }
                println("      %c: palydovų %d, koreliacija liekana~c·TGD %.2f, nuolydis %.2f, liekanų sklaida %.2f m".format(sys, pts.size, cxy / Math.sqrt(cxx * cyy), cxy / cyy, Math.sqrt(cxx / pts.size)))
            }
        }
        tgd = emptyMap()
    }

    /** SP3 + transliuojamos efemeridės trūkstamiems palydovams: sklaida, šuoliai. */
    fun hybMain(dir: File) {
        val raw = File(dir, "gnss_raw.csv")
        val sp3 = (dir.listFiles()!!.first { it.name.endsWith(".SP3", true) }).inputStream().use { Sp3Reader.read(it) }
        val set = (dir.listFiles()!!.first { it.name.startsWith("brdc") }).inputStream().use { Brdc.parse(it) }
        println("=== ${dir.name}")
        for (thr in listOf(20.0, 25.0)) for (h in listOf(false, true)) for (hm in listOf<Double?>(null, 214.73)) {
            brdc = if (h) set else null; hybridN = 0; epN = 0
            val (r, sols) = run(raw, sp3, null, thr, true, true, "x", hm)
            val used = sols.map { it[4] }.average()
            var j20 = 0; for (i in 1 until sols.size) if (PositionSolver.horizDist(sols[i-1][0], sols[i-1][1], sols[i][0], sols[i][1]) > 20.0) j20++
            println("  slenkstis %2.0f %-22s %-14s n=%4d p50 %6.2f p95 %7.2f m  šuolių >5 m %3d, >20 m %3d; palydovų vid. %.1f%s".format(thr,
                if (h) "SP3 + BRDC trūkstamiems" else "tik SP3", if (hm != null) "su aukščiu" else "be aukščio", r.n, r.p50, r.p95, r.jumps, j20, used,
                if (h && epN > 0) ", iš BRDC vid. %.1f".format(hybridN.toDouble() / epN) else ""))
        }
        brdc = null
    }

    fun main(dir: File, truthArg: Pair<Double, Double>?) {
        val raw = File(dir, "gnss_raw.csv")
        val sp3f = dir.listFiles()!!.firstOrNull { it.name.endsWith(".SP3", true) } ?: run { println("SP3 nėra ZIP'e – reikia BRDC kelio"); return }
        val sp3 = sp3f.inputStream().use { Sp3Reader.read(it) }
        val rep = File(dir, "ataskaita.txt").takeIf { it.exists() }?.readLines()
        val truth = truthArg ?: rep?.firstOrNull { it.startsWith("Žinomos koordinatės:") }?.substringAfter(":")?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.size >= 2 }?.let { it[0] to it[1] }
        val env = rep?.firstOrNull { it.startsWith("Aplinka") } ?: ""
        println("=== ${dir.name}: ${if (truth != null) "TIESA – žinomos koordinatės" else "tiesos nėra – paklaida nuo sprendinių medianos (tik sklaida)"}; $env")
        val chip = File(dir, "gnss_fix.csv").takeIf { it.exists() }?.readLines()?.drop(1)?.mapNotNull { l -> val p = l.split(','); p.getOrNull(1)?.toDoubleOrNull()?.let { a -> p[2].toDoubleOrNull()?.let { b -> a to b } } }
        for (g in listOf(false, true)) { useGlo = g
            val (r0, _) = run(raw, sp3, truth, 20.0, true, true, "x")
            println("   %-30s p50 %6.2f m  p95 %7.2f m  šuolių>5m %d".format(if (g) "kaip programoje + GLONASS" else "kaip programoje be GLONASS", r0.p50, r0.p95, r0.jumps)) }
        useGlo = true
        for ((thr, sm, fl, nm) in listOf(
            Quad(20.0, false, false, "kodas, slenkstis 20"),
            Quad(20.0, true, false, "+ glodinimas pagal Doplerį"),
            Quad(20.0, true, true, "+ išlyginimas (kaip programoje)"),
            Quad(25.0, true, true, "slenkstis 25 + glod. + išlyg."),
            Quad(30.0, true, true, "slenkstis 30 + glod. + išlyg."),
            Quad(20.0, true, true, "T  judėjimas mirksi (vibracija)"),
            Quad(28.0, true, true, "T  mirksi, slenkstis 28"),
            Quad(20.0, true, true, "M  judant, K=0.5"),
            Quad(20.0, true, true, "M2 judant, K=0.2"),
            Quad(20.0, true, true, "M1 judant, K=0.1"),
            Quad(20.0, true, true, "H222 kaip programoje + aukštis 222"),
            Quad(20.0, true, true, "H172 kaip programoje + aukštis 172"),
        )) {
            val hm = when { nm.startsWith("H222") -> 222.0; nm.startsWith("H172") -> 172.0; else -> null }
            val (r, sols) = run(raw, sp3, truth, thr, sm, fl, nm, hm, forceMove = nm.startsWith("M"))
            if (hm != null) { val base = run(raw, sp3, truth, 20.0, true, true, "x").second
                val m1 = sols.map { it[0] }.sorted()[sols.size / 2] to sols.map { it[1] }.sorted()[sols.size / 2]
                val m0 = base.map { it[0] }.sorted()[base.size / 2] to base.map { it[1] }.sorted()[base.size / 2]
                println("   vidurio poslinkis nuo varianto be aukščio: %.1f m".format(PositionSolver.horizDist(m0.first, m0.second, m1.first, m1.second))) }
            println("%-34s n=%4d be pad.=%4d | p50 %6.2f m  p95 %7.2f m  šuolių>5m %d, didž. %.0f m".format(r.name, r.n, r.none, r.p50, r.p95, r.jumps, maxJump))
            if (nm.startsWith("+ išlyg") && chip != null && chip.isNotEmpty()) {
                val ref = truth ?: (sols.map { it[0] }.sorted()[sols.size / 2] to sols.map { it[1] }.sorted()[sols.size / 2])
                val ce = chip.map { PositionSolver.horizDist(it.first, it.second, ref.first, ref.second) }.sorted()
                val cm = chip.map { it.first }.sorted()[chip.size / 2] to chip.map { it.second }.sorted()[chip.size / 2]
                println("   lustas: p50 %.2f m, p95 %.2f m nuo tos pačios atskaitos; lusto ir programos medianų skirtumas %.1f m".format(
                    ce[ce.size / 2], ce[((ce.size - 1) * 0.95).toInt()], PositionSolver.horizDist(cm.first, cm.second,
                        sols.map { it[0] }.sorted()[sols.size / 2], sols.map { it[1] }.sorted()[sols.size / 2])))
            }
        }
    }
}
data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
