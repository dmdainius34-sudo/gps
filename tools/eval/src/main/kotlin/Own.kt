package lt.example.gnss

import java.io.File

/**
 * Tavo paties įrašas (programos ZIP): gnss_raw.csv + SP3 orbitos iš ZIP -> programos skaičiavimas -> paklaidos.
 * Tiesa: žinomos koordinatės (iš ataskaitos „Žinomos koordinatės“ arba argumentu), kitaip – sprendinių mediana (tik sklaida).
 */
object Own {
    class Res(val name: String, val p50: Double, val p95: Double, val jumps: Int, val n: Int, val none: Int)

    fun run(raw: File, sp3: Sp3Reader, truth: Pair<Double, Double>?, thr: Double, smooth: Boolean, filter: Boolean, name: String,
            hMsl: Double? = null, geoidN: Double = 24.89, forceMove: Boolean = false): Pair<Res, List<DoubleArray>> {
        val sm = Sp3V5.Smoother(doppler = true, useAdr = false, dopWindow = 20)
        PosFilter.reset(); CnoMask.min = thr; CnoMask.auto = false
        PosFilter.moveGain = when { name.startsWith("M2") -> 0.2; name.startsWith("M1") -> 0.1; else -> 0.5 }
        val sols = ArrayList<DoubleArray>(); var none = 0
        var vel: DopplerVel.Vel? = null
        Sp3V5.readRaw(raw.bufferedReader(), glo = false) { ep ->
            val ms = ep.meas.filter { it.sys != 'R' && (it.state and Sp3V5.STATE_TOW_DECODED) != 0 }
            val smd = if (smooth) sm.applyEpoch(ms, ep.t) else emptyMap()
            val obs = ms.filter { it.cn0 >= thr }.map { m -> Sp3V5.Ob(m.sys, m.svid, smd[m.key] ?: m.pr, m.cn0, m.prr) }
            if (obs.size < 5 || !Sp3Solver.inRange(sp3, ep.t)) { none++; return@readRaw }
            val last = sols.lastOrNull()
            val hc = if (hMsl != null && last != null) Sp3V5.HCon(hMsl + geoidN, 2.0,
                PositionSolver.llhToEcef(Math.toRadians(last[0]), Math.toRadians(last[1]), hMsl + geoidN)) else null
            val f = try { Sp3V5.solve(ep.week, ep.tow, obs, sp3, isb = true, raim = true, hCon = hc) } catch (_: Exception) { null }
            if (f == null) { none++; return@readRaw }
            vel = try { DopplerVel.solve(f.sats, f.x) } catch (_: Exception) { null }
            var lat = f.sol.lat; var lon = f.sol.lon
            if (filter) {
                val v = vel
                val vNE = v?.let { doubleArrayOf(it.speedH * Math.cos(Math.toRadians(it.courseDeg)), it.speedH * Math.sin(Math.toRadians(it.courseDeg))) }
                val mv = forceMove || (v != null && v.speedH > 1.5 && v.sigma < 1.0)
                val q = PosFilter.step(ep.timeMs, lat, lon, f.sol.h, f.sol.hacc, mv, vNE); lat = q[0]; lon = q[1]
            }
            sols.add(doubleArrayOf(lat, lon, f.sol.h, ep.timeMs.toDouble(), f.sol.sats.toDouble()))
        }
        val ref = truth ?: (sols.map { it[0] }.sorted()[sols.size / 2] to sols.map { it[1] }.sorted()[sols.size / 2])
        val err = sols.map { PositionSolver.horizDist(it[0], it[1], ref.first, ref.second) }.sorted()
        var jumps = 0
        for (i in 1 until sols.size) if (PositionSolver.horizDist(sols[i - 1][0], sols[i - 1][1], sols[i][0], sols[i][1]) > 5.0) jumps++
        fun q(p: Double) = if (err.isEmpty()) Double.NaN else err[((err.size - 1) * p).toInt()]
        return Res(name, q(0.5), q(0.95), jumps, sols.size, none) to sols
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
        for ((thr, sm, fl, nm) in listOf(
            Quad(20.0, false, false, "kodas, slenkstis 20"),
            Quad(20.0, true, false, "+ glodinimas pagal Doplerį"),
            Quad(20.0, true, true, "+ išlyginimas (kaip programoje)"),
            Quad(25.0, true, true, "slenkstis 25 + glod. + išlyg."),
            Quad(30.0, true, true, "slenkstis 30 + glod. + išlyg."),
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
            println("%-34s n=%4d be pad.=%4d | p50 %6.2f m  p95 %7.2f m  šuolių>5m %d".format(r.name, r.n, r.none, r.p50, r.p95, r.jumps))
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
