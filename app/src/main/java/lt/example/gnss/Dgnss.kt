package lt.example.gnss

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * DGNSS po įrašo: bazės (NTRIP, RTCM 3 MSM) kodo matavimai -> pataisa kiekvienam palydovui PRC = ρ_bazė − (P_bazė + c·dts),
 * pritaikoma prietaiso (gnss_raw.csv) GPS L1 ir Galileo E1 pseudoatstumams. Orbitų, palydovų laikrodžių, jonosferos ir
 * troposferos paklaidos, bendros bazei ir prietaisui, išnyksta; atmosferos modeliai nenaudojami. Palydovų padėtys – SP3.
 */
object Dgnss {
    const val OUT_FILE = "gnss_solver_dgnss.csv"
    private const val C = PositionSolver.C
    private const val W = Sp3Reader.WEEK

    class BaseEp(val t: Double, val prc: Map<String, Double>)
    class Base(val pos: DoubleArray?, val eps: List<BaseEp>, val msgs: Int, val counts: Map<Int, Int>, val note: String)

    /** Pirmenybė signalams: GPS 1C; Galileo 1C, 1X, 1B (prietaisas – E1). */
    private fun pick(sys: Char, sig: String): Int = when (sys) {
        'G' -> if (sig == "1C") 0 else -1
        'E' -> when (sig) { "1C" -> 0; "1X" -> 1; "1B" -> 2; else -> -1 }
        else -> -1
    }

    fun readBase(rtcm: File, sp3: Sp3Reader, refT: Double): Base {
        if (!rtcm.exists()) return Base(null, emptyList(), 0, emptyMap(), "nėra gnss_rtcm.csv")
        val lines = ArrayList<Pair<Int, ByteArray>>()
        val counts = java.util.TreeMap<Int, Int>()
        rtcm.bufferedReader().use { br ->
            br.readLine()
            br.forEachLine { l ->
                val p = l.split(',')
                if (p.size < 3) return@forEachLine
                val type = p[1].toIntOrNull() ?: return@forEachLine
                counts[type] = (counts[type] ?: 0) + 1
                if (type == 1005 || type == 1006 || Rtcm3.sysOf(type) == 'G' || Rtcm3.sysOf(type) == 'E')
                    try { lines.add(type to Rtcm3.unhex(p[2].trim())) } catch (_: Exception) {}
            }
        }
        val stations = lines.mapNotNull { if (it.first == 1005 || it.first == 1006) Rtcm3.station(it.second) else null }
        val pos = stations.lastOrNull() ?: return Base(null, emptyList(), lines.size, counts, "nėra bazės koordinačių (1005/1006)")
        // MSM pagal epochą (abi sistemos tuo pačiu laiku)
        val byEpoch = java.util.TreeMap<Double, HashMap<String, Pair<Int, Rtcm3.Obs>>>()
        val refW = Math.floorDiv(refT.toLong(), W.toLong())
        for ((type, p) in lines) {
            if (type == 1005 || type == 1006) continue
            val m = try { Rtcm3.msm(p) } catch (_: Exception) { null } ?: continue
            var t = refW * W + m.tow
            if (t - refT > W / 2) t -= W; if (refT - t > W / 2) t += W
            val map = byEpoch.getOrPut(Math.round(t * 1000) / 1000.0) { HashMap() }
            for (o in m.obs) {
                val r = pick(o.sys, o.sig); if (r < 0) continue
                val id = "%c%02d".format(Locale.US, o.sys, o.prn)
                val cur = map[id]
                if (cur == null || r < cur.first) map[id] = r to o
            }
        }
        val eps = ArrayList<BaseEp>()
        for ((t, map) in byEpoch) {
            if (!Sp3Solver.inRange(sp3, t)) continue
            val obs = map.values.map { Sp3V5.Ob(it.second.sys, it.second.prn, it.second.pr, it.second.cn0) }
            val sats = Sp3V5.prepare(t, obs, sp3)
            val prc = HashMap<String, Double>()
            for (s in sats) {
                s.rp.fill(0.0); s.rotate(pos); s.rotate(pos)
                val dx = pos[0] - s.rp[0]; val dy = pos[1] - s.rp[1]; val dz = pos[2] - s.rp[2]
                prc["%c%02d".format(Locale.US, s.sys, s.svid)] = sqrt(dx * dx + dy * dy + dz * dz) - s.prc
            }
            if (prc.isNotEmpty()) eps.add(BaseEp(t, prc))
        }
        return Base(pos, eps, lines.size, counts, "")
    }

    private class Lsq(val x: DoubleArray, val used: Int, val dropped: List<String>)

    /** Mažiausi kvadratai: x, y, z ir laikrodis kiekvienai sistemai; svoris sin²(aukštis); iki 3 atmetimų. */
    private fun lsq(sats0: List<Sp3V5.SatEp>, tow: Double, atmo: Boolean): Lsq? {
        var sats = sats0
        val dropped = ArrayList<String>()
        repeat(4) {
            val systems = sats.map { it.sys }.distinct().sorted()
            val np = 3 + systems.size
            if (sats.size < np + 1) return null
            val x = DoubleArray(np); x[2] = 6_378_137.0
            sats.forEach { it.rp.fill(0.0) }
            var res = DoubleArray(0); var rowsSat = ArrayList<Sp3V5.SatEp>()
            for (iter in 0 until 30) {
                val h = ArrayList<DoubleArray>(); val r = ArrayList<Double>(); val w = ArrayList<Double>(); rowsSat = ArrayList()
                val near = iter > 3
                for (s in sats) {
                    s.rotate(x)
                    val dx = x[0] - s.rp[0]; val dy = x[1] - s.rp[1]; val dz = x[2] - s.rp[2]
                    val rho = sqrt(dx * dx + dy * dy + dz * dz)
                    var corr = 0.0; var wt = 1.0
                    if (near) {
                        val l = Sp3Solver.look(x, s.rp, rho)
                        if (l[4] < Math.toRadians(10.0)) continue
                        if (atmo) corr = Sp3Solver.saastamoinen(l[0], l[2], l[4]) + Sp3Solver.klobuchar(l[0], l[1], l[3], l[4], tow)
                        wt = kotlin.math.sin(l[4]).let { it * it }
                    }
                    val row = DoubleArray(np); row[0] = dx / rho; row[1] = dy / rho; row[2] = dz / rho
                    row[3 + systems.indexOf(s.sys)] = 1.0
                    h.add(row); r.add(s.prc - (rho + x[3 + systems.indexOf(s.sys)] + corr)); w.add(wt); rowsSat.add(s)
                }
                if (h.size < np) return null
                // normalinės lygtys
                val n = Array(np) { DoubleArray(np) }; val b = DoubleArray(np)
                for (k in h.indices) for (i in 0 until np) { b[i] += h[k][i] * w[k] * r[k]; for (j in 0 until np) n[i][j] += h[k][i] * w[k] * h[k][j] }
                for (j in 0 until np) if (n[j][j] == 0.0) n[j][j] = 1.0
                val d = solve(n, b) ?: return null
                for (i in 0 until np) x[i] += d[i]
                res = r.toDoubleArray()
                if (sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]) < 1e-3 && near) break
            }
            if (rowsSat.size < np + 2) return Lsq(x, rowsSat.size, dropped)
            val rms = sqrt(res.sumOf { it * it } / res.size)
            val k = res.indices.maxByOrNull { abs(res[it]) } ?: return Lsq(x, rowsSat.size, dropped)
            if (abs(res[k]) <= maxOf(5.0, 3.0 * rms)) return Lsq(x, rowsSat.size, dropped)
            val bad = rowsSat[k]
            dropped.add("%c%02d".format(Locale.US, bad.sys, bad.svid))
            sats = sats.filter { it !== bad }
        }
        return null
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size; val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (c in 0 until n) {
            val p = (c until n).maxByOrNull { abs(m[it][c]) }!!
            if (abs(m[p][c]) < 1e-12) return null
            val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in 0 until n) if (r != c) { val f = m[r][c] / m[c][c]; for (j in c..n) m[r][j] -= f * m[c][j] }
        }
        return DoubleArray(n) { m[it][n] / m[it][it] }
    }

    class Run(val name: String, val sols: List<Pair<Long, PositionSolver.Solution>>, var corrected: Int = 0, var noCorr: Int = 0)

    /** withPrc = false – tas pats sprendėjas be pataisų, bet su troposferos ir jonosferos modeliais (palyginimui). */
    fun run(raw: File, sp3: Sp3Reader, base: Base, withPrc: Boolean, out: File? = null): Run {
        val sols = ArrayList<Pair<Long, PositionSolver.Solution>>()
        val run = Run(if (withPrc) "DGNSS (su NTRIP pataisomis)" else "be pataisų (modeliai)", sols)
        val eps = base.eps
        val ts = DoubleArray(eps.size) { eps[it].t }
        val w = out?.bufferedWriter()
        try {
            w?.write(PositionSolver.SOLVER_HEADER)
            Sp3V5.readRaw(raw) { ep ->
                val t = ep.t
                if (!Sp3Solver.inRange(sp3, t)) return@readRaw
                val acc = ep.meas.filter { Sp3V5.accept(it) && (it.sys == 'G' || it.sys == 'E') }
                val obs: List<Sp3V5.Ob>
                if (withPrc) {
                    var i = java.util.Arrays.binarySearch(ts, t); if (i < 0) i = -i - 1
                    val cand = listOf(i - 1, i).filter { it in eps.indices }.minByOrNull { abs(ts[it] - t) } ?: return@readRaw
                    if (abs(ts[cand] - t) > 1.5) return@readRaw
                    val e = eps[cand]
                    val prev = eps.getOrNull(cand - 1)?.takeIf { e.t - it.t in 0.5..2.5 }
                    obs = acc.mapNotNull { m ->
                        val id = m.id
                        val c = e.prc[id] ?: run { run.noCorr++; return@mapNotNull null }
                        val rate = prev?.prc?.get(id)?.let { (c - it) / (e.t - prev.t) } ?: 0.0
                        run.corrected++
                        Sp3V5.Ob(m.sys, m.svid, m.pr + c + rate * (t - e.t), m.cn0)
                    }
                } else obs = acc.map { Sp3V5.Ob(it.sys, it.svid, it.pr, it.cn0) }
                val sats = Sp3V5.prepare(t, obs, sp3)
                val l = lsq(sats, ep.tow, !withPrc) ?: return@readRaw
                val llh = PositionSolver.ecefToLlh(l.x[0], l.x[1], l.x[2])
                val sol = PositionSolver.Solution(Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2], l.used, 0.0, doubleArrayOf(l.x[0], l.x[1], l.x[2]))
                sols.add(ep.timeMs to sol)
                w?.write(String.format(Locale.US, "%d,%.8f,%.8f,%.2f,%d,%.2f\n", ep.timeMs, sol.lat, sol.lon, sol.h, sol.sats, sol.hacc))
            }
        } finally { w?.close() }
        return run
    }

    /**
     * Kiekvieno palydovo papildomas vėlinimas prietaise po pataisų, laikant, kad prietaisas yra atskaitos taške:
     * P + PRC + c·dts − ρ(atskaita) − sistemos laikrodis (epochos mediana). Bazė pašalina bendras paklaidas,
     * todėl liekana – vietinė: atspindžiai (NLOS) ir daugiakelis sklidimas. Teigiama – signalas atėjo ilgesniu keliu.
     */
    fun excessText(raw: File, sp3: Sp3Reader, base: Base, ref: Pair<Double, Double>, h: Double): String {
        val rx = PositionSolver.llhToEcef(Math.toRadians(ref.first), Math.toRadians(ref.second), h)
        val eps = base.eps; val ts = DoubleArray(eps.size) { eps[it].t }
        val per = HashMap<String, ArrayList<Double>>(); val azel = HashMap<String, DoubleArray>(); val cn = HashMap<String, ArrayList<Double>>()
        Sp3V5.readRaw(raw) { ep ->
            val t = ep.t
            var i = java.util.Arrays.binarySearch(ts, t); if (i < 0) i = -i - 1
            val cand = listOf(i - 1, i).filter { it in eps.indices }.minByOrNull { abs(ts[it] - t) } ?: return@readRaw
            if (abs(ts[cand] - t) > 1.5) return@readRaw
            val e = eps[cand]
            val obs = ep.meas.filter { Sp3V5.accept(it) && (it.sys == 'G' || it.sys == 'E') }.mapNotNull { m ->
                e.prc[m.id]?.let { Sp3V5.Ob(m.sys, m.svid, m.pr + it, m.cn0) } }
            val sats = Sp3V5.prepare(t, obs, sp3)
            val r = HashMap<String, Double>(); val sysOf = HashMap<String, Char>()
            for (s in sats) {
                s.rp.fill(0.0); s.rotate(rx); s.rotate(rx)
                val dx = rx[0] - s.rp[0]; val dy = rx[1] - s.rp[1]; val dz = rx[2] - s.rp[2]
                val rho = sqrt(dx * dx + dy * dy + dz * dz)
                val l = Sp3Solver.look(rx, s.rp, rho)
                if (l[4] < Math.toRadians(10.0)) continue
                val id = "%c%02d".format(Locale.US, s.sys, s.svid)
                r[id] = s.prc - rho; sysOf[id] = s.sys
                azel[id] = doubleArrayOf(Math.toDegrees(l[3]).let { (it + 360) % 360 }, Math.toDegrees(l[4]))
                cn.getOrPut(id) { ArrayList() }.add(s.cn0)
            }
            for (sy in listOf('G', 'E')) {
                val v = r.filter { sysOf[it.key] == sy }.values.sorted()
                if (v.size < 3) continue
                val m = v[v.size / 2]
                r.filter { sysOf[it.key] == sy }.forEach { (k, x) -> per.getOrPut(k) { ArrayList() }.add(x - m) }
            }
        }
        val rows = per.filter { it.value.size >= 30 }.map { (k, v) -> Triple(k, PositionSolver.percentile(v.sorted(), 0.5), azel[k]!!) }
            .sortedByDescending { abs(it.second) }
        val bad = rows.filter { it.second > 10.0 }
        return "Palydovų vėlinimas prietaise po pataisų (laikant, kad prietaisas atskaitos taške; + = ilgesnis kelias, atspindys):\n" +
            rows.take(12).joinToString("\n") { (k, v, a) ->
                "  %s az %3.0f° aukštis %2.0f° C/N0 %.1f: %+.1f m".format(Locale.US, k, a[0], a[1],
                    PositionSolver.percentile(cn[k]!!.sorted(), 0.5), v) } +
            "\nTikėtini atspindžiai (NLOS, > 10 m): " + (if (bad.isEmpty()) "nėra" else bad.joinToString(", ") { "%s (az %.0f°)".format(Locale.US, it.first, it.third[0]) }) +
            "\nPataisos pašalina orbitų, laikrodžių ir atmosferos paklaidas, bet ne vietinius atspindžius – juos lemia aplinka (plienas, pastatai, kūnas)."
    }

    private fun line(r: Run, ref: Pair<Double, Double>?, fixH: Double?): String {
        if (r.sols.isEmpty()) return "${r.name}: nėra sprendinių"
        val sb = StringBuilder("${r.name}: ")
        if (ref != null) {
            val mPerDeg = Math.toRadians(1.0) * 6_371_000.0; val cl = cos(Math.toRadians(ref.first))
            val e = r.sols.map { (it.second.lon - ref.second) * mPerDeg * cl }; val n = r.sols.map { (it.second.lat - ref.first) * mPerDeg }
            val d = e.indices.map { sqrt(e[it] * e[it] + n[it] * n[it]) }.sorted()
            sb.append("vid. rytai %+.2f m, vid. šiaurė %+.2f m, atstumo mediana %.2f m, 90 %% %.2f m".format(Locale.US,
                e.average(), n.average(), PositionSolver.percentile(d, 0.5), PositionSolver.percentile(d, 0.9)))
        } else sb.append("nėra atskaitos taško")
        val h = PositionSolver.percentile(r.sols.map { it.second.h }.sorted(), 0.5)
        sb.append("; aukščio skirtumas ").append(fixH?.let { "%+.2f m".format(Locale.US, h - it) } ?: "nėra")
        sb.append("; palydovų mediana %.0f; sprendinių %d".format(Locale.US, PositionSolver.percentile(r.sols.map { it.second.sats.toDouble() }.sorted(), 0.5), r.sols.size))
        return sb.toString()
    }

    fun block(raw: File, rtcm: File, sp3: Sp3Reader, out: File, ref: Pair<Double, Double>?, fixH: Double?): String = buildString {
        appendLine()
        appendLine("--- DGNSS (NTRIP / RTCM 3) ---")
        if (!rtcm.exists() || rtcm.length() < 40) { appendLine("Neatlikta: įraše nėra RTCM žinučių (gnss_rtcm.csv) – įjunkite NTRIP"); return@buildString }
        val t0 = run { var t = 0.0; Sp3V5.readRaw(raw) { if (t == 0.0) t = it.t }; t }
        val base = readBase(rtcm, sp3, t0)
        appendLine("RTCM žinutės: " + base.counts.entries.joinToString(" ") { "${it.key}×${it.value}" })
        if (base.pos == null) { appendLine("Neatlikta: ${base.note}"); return@buildString }
        val llh = PositionSolver.ecefToLlh(base.pos[0], base.pos[1], base.pos[2])
        appendLine("Bazė (1005/1006): %.7f, %.7f, h %.2f m".format(Locale.US, Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2]) +
            (ref?.let { "; atstumas iki atskaitos taško %.2f km".format(Locale.US, PositionSolver.horizDist(it.first, it.second, Math.toDegrees(llh[0]), Math.toDegrees(llh[1])) / 1000.0) } ?: ""))
        appendLine("Bazės epochų su pataisomis: ${base.eps.size}" + (base.eps.firstOrNull()?.let { "; palydovų epochoje (mediana) %.0f".format(Locale.US,
            PositionSolver.percentile(base.eps.map { e -> e.prc.size.toDouble() }.sorted(), 0.5)) } ?: ""))
        if (base.eps.isEmpty()) { appendLine("Neatlikta: SP3 neapima bazės epochų arba nėra GPS/Galileo MSM"); return@buildString }
        val plain = run(raw, sp3, base, false)
        val dg = run(raw, sp3, base, true, out)
        appendLine(line(plain, ref, fixH))
        appendLine(line(dg, ref, fixH))
        appendLine("Pataisytų stebėjimų ${dg.corrected}, be pataisos (bazė palydovo nemato) ${dg.noCorr}")
        if (ref != null) appendLine(excessText(raw, sp3, base, ref, fixH ?: 0.0))
        appendLine("Failas $OUT_FILE")
    }
}
