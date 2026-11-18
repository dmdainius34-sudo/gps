package lt.example.gnss

import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * GSDC 2023 (Google) važiavimo įrašas per programos skaičiavimą (Sp3V5, glodinimas, DualFreq, RAIM, PosFilter) ir palyginimas su tikrąja trajektorija.
 * Palydovų padėtys ir laikrodžiai – iš duomenų rinkinio (Google apskaičiavo iš transliuojamų efemeridžių).
 */
class Row(val ms: Long, val sys: Char, val svid: Int, val l5: Boolean, val pr: Double, val cn0: Double, val state: Int, val prr: Double?,
          val pos: DoubleArray, val vel: DoubleArray, val clk: Double, val clkDrift: Double, val tow: Double, val el: Double, val az: Double,
          val wls: DoubleArray?, val ionoDs: Double = 0.0, val adr: Double? = null, val adrSt: Int = 0)

fun readGsdc(f: File): List<Row> {
    val br = f.bufferedReader(); val h = br.readLine().split(',')
    fun i(n: String) = h.indexOf(n)
    val iMs = i("utcTimeMillis"); val iCt = i("ConstellationType"); val iSv = i("Svid"); val iF = i("CarrierFrequencyHz")
    val iPr = i("RawPseudorangeMeters"); val iCn = i("Cn0DbHz"); val iSt = i("State"); val iPrr = i("PseudorangeRateMetersPerSecond")
    val iX = i("SvPositionXEcefMeters"); val iVx = i("SvVelocityXEcefMetersPerSecond"); val iClk = i("SvClockBiasMeters"); val iCd = i("SvClockDriftMetersPerSecond")
    val iTn = i("TimeNanos"); val iFb = i("FullBiasNanos"); val iB = i("BiasNanos"); val iEl = i("SvElevationDegrees"); val iAz = i("SvAzimuthDegrees")
    val iW = i("WlsPositionXEcefMeters"); val iIo = i("IonosphericDelayMeters"); val iAdr = i("AccumulatedDeltaRangeMeters"); val iAs = i("AccumulatedDeltaRangeState")
    val out = ArrayList<Row>()
    br.forEachLine { ln ->
        val p = ln.split(','); if (p.size < h.size || p[0] != "Raw") return@forEachLine
        val ct = p[iCt].toIntOrNull() ?: return@forEachLine
        val sys = when (ct) { 1 -> 'G'; 3 -> 'R'; 4 -> 'J'; 5 -> 'C'; 6 -> 'E'; else -> return@forEachLine }
        val fr = p[iF].toDoubleOrNull() ?: return@forEachLine
        val l5 = fr < 1.3e9
        val pr = p[iPr].toDoubleOrNull() ?: return@forEachLine
        val x = p[iX].toDoubleOrNull() ?: return@forEachLine
        val tn = p[iTn].toLong(); val fb = p[iFb].toLong(); val b = p[iB].toDoubleOrNull() ?: 0.0
        val tow = (((tn - fb) % 604_800_000_000_000L).toDouble() - b) * 1e-9
        val w = p[iW].toDoubleOrNull()?.let { doubleArrayOf(it, p[iW + 1].toDouble(), p[iW + 2].toDouble()) }
        out.add(Row(p[iMs].toLong(), sys, p[iSv].toInt(), l5, pr, p[iCn].toDouble(), p[iSt].toInt(), p[iPrr].toDoubleOrNull(),
            doubleArrayOf(x, p[iX + 1].toDouble(), p[iX + 2].toDouble()), doubleArrayOf(p[iVx].toDouble(), p[iVx + 1].toDouble(), p[iVx + 2].toDouble()),
            p[iClk].toDouble(), p[iCd].toDoubleOrNull() ?: 0.0, tow, p[iEl].toDoubleOrNull() ?: 0.0, p[iAz].toDoubleOrNull() ?: 0.0, w, p[iIo].toDoubleOrNull() ?: 0.0, p[iAdr].toDoubleOrNull(), p[iAs].toDoubleOrNull()?.toInt() ?: 0))
    }
    return out
}

class Gt(val lat: Double, val lon: Double, val h: Double, val speed: Double)
fun readGt(f: File): Map<Long, Gt> {
    val br = f.bufferedReader(); val h = br.readLine().split(',')
    val iLa = h.indexOf("LatitudeDegrees"); val iLo = h.indexOf("LongitudeDegrees"); val iA = h.indexOf("AltitudeMeters"); val iS = h.indexOf("SpeedMps"); val iT = h.indexOf("UnixTimeMillis")
    val m = HashMap<Long, Gt>()
    br.forEachLine { ln -> val p = ln.split(','); if (p.size > iT) m[p[iT].toLong()] = Gt(p[iLa].toDouble(), p[iLo].toDouble(), p[iA].toDouble(), p[iS].toDoubleOrNull() ?: 0.0) }
    return m
}

class Opt(val name: String, val smooth: Int, val dual: Boolean, val filter: Boolean, val autoThr: Boolean, val thr: Double = 20.0, val hyst: Boolean = false, val dsIono: Boolean = false, val few: Boolean = true, val velPred: Boolean = false, val adr: Boolean = false, val glo: Boolean = false, val ionoMode: Int = 0, val tdcpK: Double = 0.0)

val agg = LinkedHashMap<String, MutableList<DoubleArray>>()
fun run(rows: List<Row>, gt: Map<Long, Gt>, o: Opt): String {
    val sm = Sp3V5.Smoother(doppler = true, useAdr = o.adr, dopWindow = when (o.smooth) { 2 -> 100; 4 -> 20; 5 -> 50; 6 -> 100; else -> 20 })
    PosFilter.reset(); CnoMask.min = o.thr; CnoMask.auto = o.autoThr
    PosFilter.moveGain = when { o.name.startsWith("D2") -> 0.2; o.name.startsWith("D1") -> 0.1; else -> 0.5 }
    var inSet = emptySet<String>()
    val dualTmp = HashMap<Int, Pair<Double, Double>>()
    var prevTd: Map<Int, DoubleArray>? = null; var prevTdMs = 0L; var outX: DoubleArray? = null; var tdHit = 0; var tdMiss = 0
    val err = ArrayList<Double>(); val herr = ArrayList<Double>(); var prev: DoubleArray? = null; var jumps = 0; var n = 0; var none = 0
    // DualFreq būsena – nauja
    val byEp = rows.groupBy { it.ms }.toSortedMap()
    for ((ms, rs) in byEp) {
        val g = gt[ms] ?: continue
        n++
        val l1 = rs.filter { !it.l5 && it.sys != 'R' && (it.state and 8) != 0 }
        val l5 = rs.filter { it.l5 && (it.state and 8) != 0 }.associateBy { it.sys.code * 1000 + it.svid }
        val t = l1.first().let { it.tow } + 0.0
        val ms_ = l1.map { r -> Sp3V5.M(r.sys, r.svid, r.pr, r.cn0, Sp3V5.STATE_TOW_DECODED, 0, if (o.adr || o.smooth >= 4) r.adr else null, if (o.adr || o.smooth >= 4) r.adrSt else 0, r.prr).also { m -> if (o.dual) m.pr5 = l5[m.key]?.pr } }
        if (o.autoThr) CnoMask.autoStep(l1.map { CnoMask.S(it.sys.toString(), it.cn0, it.el, it.az) })
        val smoothed: Map<Int, Double> = when (o.smooth) {
            0 -> emptyMap()
            2 -> ms_.associate { it.key to sm.apply(it, t) }
            3 -> { val d = sm.applyEpoch(ms_.filter { m -> !(m.adr != null && (m.adrState and 1) != 0 && (m.adrState and 6) == 0) }, t)
                   d + ms_.filter { m -> m.adr != null && (m.adrState and 1) != 0 && (m.adrState and 6) == 0 }.associate { it.key to sm.apply(it, t) } }
            else -> sm.applyEpoch(ms_, t)
        }
        val keep = ms_.filter { m -> m.cn0 >= CnoMask.min || (o.hyst && m.cn0 >= CnoMask.min - 3.0 && (m.sys.toString() + m.svid) in inSet) }
        inSet = keep.map { it.sys.toString() + it.svid }.toSet()
        val byKey = l1.associateBy { it.sys.code * 1000 + it.svid }
        val sats = keep.mapNotNull { m ->
            val r = byKey[m.key] ?: return@mapNotNull null
            val p = smoothed[m.key] ?: m.pr
            val i = if (o.dsIono) r.ionoDs else if (o.dual) DualFreq.iono(m, t) else null
            if (o.ionoMode == 3) {
                val kdef = Sp3Solver.klobuchar(Math.toRadians(g.lat), Math.toRadians(g.lon), Math.toRadians(r.az), Math.toRadians(r.el), t)
                Sp3V5.SatEp(m.sys, m.svid, r.pos, r.vel, p + r.clk + kdef, r.pr / 299_792_458.0, m.cn0, r.clkDrift, m.prr).also { it.ifree = false }
                    .also { dualTmp[it.sys.code * 1000 + it.svid] = (i ?: Double.NaN) to r.ionoDs }
            } else
            Sp3V5.SatEp(m.sys, m.svid, r.pos, r.vel, p - (i ?: 0.0) + r.clk, r.pr / 299_792_458.0, m.cn0, r.clkDrift, m.prr).also { s -> s.ifree = i != null }
        }.let { l ->
            if (o.ionoMode != 3) l else {
                // bendras (imtuvo) L1–L5 poslinkis – mediana (dviejų dažnių − transliuojamas Klobuchar); tas pats laikrodis
                val d = l.mapNotNull { s -> dualTmp[s.sys.code * 1000 + s.svid]?.let { (a, b) -> if (a.isNaN()) null else a - b } }
                val c = if (d.size >= 4) d.sorted()[d.size / 2] else Double.NaN
                l.map { s -> val (a, b) = dualTmp[s.sys.code * 1000 + s.svid]!!; val net = if (!a.isNaN() && !c.isNaN()) a - c else b
                    Sp3V5.SatEp(s.sys, s.svid, s.pos, s.vel, s.prc - net, s.tau0, s.cn0, s.clkDrift, s.prr) }
            }
        }.let { l ->
            if (!o.dual || !o.few) l else {
                // kaip programoje: L1+L5 tik kai sistemoje jų ≥ 3, kitaip – atgal į L1
                val few = l.filter { it.ifree }.groupingBy { it.sys }.eachCount().filterValues { it < 3 }.keys
                l.map { s -> if (s.ifree && s.sys in few) { val r = byKey[s.sys.code * 1000 + s.svid]!!; val m = ms_.first { it.key == s.sys.code * 1000 + s.svid }
                    Sp3V5.SatEp(s.sys, s.svid, r.pos, r.vel, (smoothed[m.key] ?: m.pr) + r.clk, r.pr / 299_792_458.0, m.cn0, r.clkDrift, m.prr) } else s }
            }
        }
        if (sats.size < 5) { none++; continue }
        val f = try { Sp3V5.solveSats(t, sats, isb = true, raim = true) } catch (_: Exception) { null }
        if (f == null) { none++; continue }
        var lat = f.sol.lat; var lon = f.sol.lon
        if (o.tdcpK > 0.0) {
            // TDCP: viso telefono poslinkis per epochą iš fazės skirtumų (LSQ: dx, dy, dz, bendras laikrodžio pokytis)
            val cur = HashMap<Int, DoubleArray>()        // key -> [adr, sx, sy, sz, clkDrift]
            for (r in l1) if (r.adr != null && (r.adrSt and 1) != 0 && (r.adrSt and 6) == 0) cur[r.sys.code * 1000 + r.svid] = doubleArrayOf(r.adr, r.pos[0], r.pos[1], r.pos[2], r.clkDrift)
            val x = doubleArrayOf(f.x[0], f.x[1], f.x[2])
            var dX: DoubleArray? = null
            val pv = prevTd
            if (pv != null && (ms - prevTdMs) in 500..1500) {
                val dt = (ms - prevTdMs) / 1000.0
                val H = ArrayList<DoubleArray>(); val y = ArrayList<Double>()
                for ((k, c) in cur) { val p0 = pv[k] ?: continue
                    val dx = c[1] - x[0]; val dy = c[2] - x[1]; val dz = c[3] - x[2]; val rr = sqrt(dx * dx + dy * dy + dz * dz)
                    val u = doubleArrayOf(dx / rr, dy / rr, dz / rr)
                    val ds = doubleArrayOf(c[1] - p0[1], c[2] - p0[2], c[3] - p0[3])
                    // ΔADR = u·Δs − u·Δx + Δb − Δ(palydovo laikrodis)
                    val obs = (c[0] - p0[0]) - (u[0] * ds[0] + u[1] * ds[1] + u[2] * ds[2]) + 0.5 * (c[4] + p0[4]) * dt
                    H.add(doubleArrayOf(-u[0], -u[1], -u[2], 1.0)); y.add(obs) }
                var keep = H.indices.toMutableList()
                repeat(3) {
                    if (keep.size < 6) return@repeat
                    val sol = lsq4(keep.map { H[it] }, keep.map { y[it] }) ?: return@repeat
                    val res = keep.map { i -> y[i] - (0 until 4).sumOf { j -> H[i][j] * sol[j] } }
                    val bad = res.indices.maxByOrNull { kotlin.math.abs(res[it]) }!!
                    if (kotlin.math.abs(res[bad]) > 0.3) keep.removeAt(bad) else { dX = doubleArrayOf(sol[0], sol[1], sol[2]); return@repeat }
                }
            }
            prevTd = cur; prevTdMs = ms
            // papildomas filtras ECEF: out = (out + Δx) + K·(kodas − (out + Δx))
            val code = doubleArrayOf(f.x[0], f.x[1], f.x[2])
            val o0 = outX
            outX = if (o0 == null) code else {
                val pred = if (dX != null) DoubleArray(3) { o0[it] + dX!![it] } else null
                if (pred == null) code.also { tdMiss++ } else DoubleArray(3) { pred[it] + o.tdcpK * (code[it] - pred[it]) }.also { tdHit++ }
            }
            val llh = PositionSolver.ecefToLlh(outX!![0], outX!![1], outX!![2]); lat = Math.toDegrees(llh[0]); lon = Math.toDegrees(llh[1])
        } else
        if (o.filter) {
            val v = if (o.velPred) try { DopplerVel.solve(f.sats, f.x) } catch (_: Exception) { null } else null
            val q = PosFilter.step(ms, f.sol.lat, f.sol.lon, f.sol.h, f.sol.hacc, g.speed > 0.5, v?.let { doubleArrayOf(it.speedH * kotlin.math.cos(Math.toRadians(it.courseDeg)), it.speedH * kotlin.math.sin(Math.toRadians(it.courseDeg))) }); lat = q[0]; lon = q[1] }
        val e = PositionSolver.horizDist(lat, lon, g.lat, g.lon); err.add(e)
        val pp = prev; if (pp != null && PositionSolver.horizDist(pp[0], pp[1], lat, lon) - g.speed > 5.0) jumps++
        prev = doubleArrayOf(lat, lon)
    }
    err.sort()
    fun q(p: Double) = err[((err.size - 1) * p).toInt()]
    if (o.tdcpK > 0) System.err.println("   ${o.name}: TDCP poslinkis ${tdHit} epochų, be jo ${tdMiss}")
    agg.getOrPut(o.name) { ArrayList() }.add(doubleArrayOf(q(0.5), q(0.95), jumps.toDouble(), none.toDouble()))
    return "%-38s n=%4d be pad.=%3d | p50 %6.2f m  p95 %7.2f m  (GSDC %6.2f)  šuolių>5m %d".format(o.name, err.size, none, q(0.5), q(0.95), (q(0.5) + q(0.95)) / 2, jumps)
}

fun lsq4(H: List<DoubleArray>, y: List<Double>): DoubleArray? {
    val a = Array(4) { DoubleArray(5) }
    for (i in H.indices) for (j in 0..3) { for (k in 0..3) a[j][k] += H[i][j] * H[i][k]; a[j][4] += H[i][j] * y[i] }
    for (c in 0..3) { var p = c; for (k in c + 1..3) if (kotlin.math.abs(a[k][c]) > kotlin.math.abs(a[p][c])) p = k
        if (kotlin.math.abs(a[p][c]) < 1e-12) return null; val t = a[c]; a[c] = a[p]; a[p] = t
        for (k in 0..3) if (k != c) { val f = a[k][c] / a[c][c]; for (j in c..4) a[k][j] -= f * a[c][j] } }
    return DoubleArray(4) { a[it][4] / a[it][it] }
}

fun wlsBaseline(rows: List<Row>, gt: Map<Long, Gt>): String {
    val err = ArrayList<Double>()
    for ((ms, rs) in rows.groupBy { it.ms }) {
        val g = gt[ms] ?: continue; val w = rs.firstOrNull { it.wls != null }?.wls ?: continue
        val llh = PositionSolver.ecefToLlh(w[0], w[1], w[2])
        err.add(PositionSolver.horizDist(Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), g.lat, g.lon))
    }
    err.sort(); fun q(p: Double) = err[((err.size - 1) * p).toInt()]
    return "%-38s n=%4d          | p50 %6.2f m  p95 %7.2f m  (GSDC %6.2f)".format("Google WLS (palyginimui)", err.size, q(0.5), q(0.95), (q(0.5) + q(0.95)) / 2)
}

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "own") { for (d in args.drop(1)) Own.main(File(d), null); return }
    val opts = listOf(
        Opt("D  K=0.5 (dabar)", 1, false, true, false, 20.0, velPred = true),
        Opt("D2 K=0.2", 1, false, true, false, 20.0, velPred = true),
        Opt("D1 K=0.1", 1, false, true, false, 20.0, velPred = true),
    )
    for (dir in args) {
        val rows = readGsdc(File(dir, "device_gnss.csv")); val gt = readGt(File(dir, "ground_truth.csv"))
        println("=== " + File(dir).parentFile.name.take(28) + "/" + File(dir).name)
        for (o in opts) { DualFreq.reset(); println(run(rows, gt, o)) }
    }
    println("=== VIDURKIS per visus važiavimus (p50, p95, GSDC, šuoliai>5m)")
    for ((k, l) in agg) println("%-38s p50 %5.2f  p95 %6.2f  GSDC %5.2f  šuolių %5.0f".format(k, l.map { it[0] }.average(), l.map { it[1] }.average(),
        l.map { (it[0] + it[1]) / 2 }.average(), l.map { it[2] }.average()))
}
