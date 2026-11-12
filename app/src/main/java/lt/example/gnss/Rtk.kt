package lt.example.gnss

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vieno dažnio RTK (plaukiojantis sprendinys) po įrašo: prietaiso (gnss_raw.csv) ir bazės (NTRIP MSM, gnss_rtcm.csv)
 * GPS L1 ir Galileo E1 kodo bei nešlio fazės dvigubi skirtumai, Kalmano filtras: padėtis + kiekvieno palydovo
 * vieno skirtumo fazės dviprasmybė (m). Palydovų padėtys – SP3. Fazės šuoliai: Android ADR būsena, bazės užrakto laikas,
 * tarpas > 2 s, inovacija > 5σ. Sveikųjų dviprasmybių sprendimas nedaromas (tik plaukiojantis sprendinys).
 */
object Rtk {
    const val OUT_FILE = "gnss_solver_rtk.csv"
    private const val C = PositionSolver.C
    private const val W = Sp3Reader.WEEK
    private const val ADR_VALID = 1
    private const val ADR_RESET = 2
    private const val ADR_SLIP = 4

    /** Bazės liekanos (m): kodas ir fazė be geometrijos ir palydovo laikrodžio; lock – užrakto indikatorius. */
    class BObs(val rc: Double, val rp: Double, val lock: Int)
    class BEp(val t: Double, val obs: Map<String, BObs>)

    private fun pick(sys: Char, sig: String): Int = when (sys) {
        'G' -> if (sig == "1C") 0 else -1
        'E' -> when (sig) { "1C" -> 0; "1X" -> 1; "1B" -> 2; else -> -1 }
        else -> -1
    }

    fun readBase(rtcm: File, sp3: Sp3Reader, refT: Double): Pair<DoubleArray?, List<BEp>> {
        if (!rtcm.exists()) return null to emptyList()
        var pos: DoubleArray? = null
        val byEpoch = java.util.TreeMap<Double, HashMap<String, Pair<Int, Rtcm3.Obs>>>()
        val refW = Math.floorDiv(refT.toLong(), W.toLong())
        rtcm.bufferedReader().use { br ->
            br.readLine()
            br.forEachLine { l ->
                val p = l.split(','); if (p.size < 3) return@forEachLine
                val type = p[1].toIntOrNull() ?: return@forEachLine
                val sys = Rtcm3.sysOf(type)
                if (type != 1005 && type != 1006 && sys != 'G' && sys != 'E') return@forEachLine
                val b = try { Rtcm3.unhex(p[2].trim()) } catch (_: Exception) { return@forEachLine }
                if (type == 1005 || type == 1006) { Rtcm3.station(b)?.let { pos = it }; return@forEachLine }
                val m = try { Rtcm3.msm(b) } catch (_: Exception) { null } ?: return@forEachLine
                var t = refW * W + m.tow
                if (t - refT > W / 2) t -= W; if (refT - t > W / 2) t += W
                val map = byEpoch.getOrPut(Math.round(t * 1000) / 1000.0) { HashMap() }
                for (o in m.obs) {
                    val r = pick(o.sys, o.sig); if (r < 0) continue
                    val id = "%c%02d".format(Locale.US, o.sys, o.prn)
                    val cur = map[id]; if (cur == null || r < cur.first) map[id] = r to o
                }
            }
        }
        val bp = pos ?: return null to emptyList()
        val eps = ArrayList<BEp>()
        for ((t, map) in byEpoch) {
            if (!Sp3Solver.inRange(sp3, t)) continue
            baseEpoch(t, map.values.map { it.second }, bp) { tt, ob -> Sp3V5.prepare(tt, ob, sp3) }?.let { eps.add(it) }
        }
        return bp to eps
    }

    /** Viena bazės epocha: liekanos be geometrijos ir palydovo laikrodžio (prepare – SP3 arba transliuojamos efemeridės). */
    fun baseEpoch(t: Double, obs: Collection<Rtcm3.Obs>, bp: DoubleArray, prepare: (Double, List<Sp3V5.Ob>) -> List<Sp3V5.SatEp>): BEp? {
        val byId = obs.associateBy { "%c%02d".format(Locale.US, it.sys, it.prn) }
        val sats = prepare(t, obs.map { Sp3V5.Ob(it.sys, it.prn, it.pr, it.cn0) })
        val out = HashMap<String, BObs>()
        for (s in sats) {
            val id = "%c%02d".format(Locale.US, s.sys, s.svid)
            val o = byId[id] ?: continue
            s.rp.fill(0.0); s.rotate(bp); s.rotate(bp)
            val rho = dist(bp, s.rp)
            val cdts = s.prc - o.pr
            out[id] = BObs(s.prc - rho, if (o.phase.isNaN()) Double.NaN else o.phase + cdts - rho, o.lock)
        }
        return if (out.isEmpty()) null else BEp(t, out)
    }

    /** Bazės matavimų pasirinkimas (GPS 1C, Galileo 1C/1X/1B) iš vienos MSM žinutės. */
    fun pickObs(m: Rtcm3.Msm, into: HashMap<String, Pair<Int, Rtcm3.Obs>>) {
        for (o in m.obs) {
            val r = pick(o.sys, o.sig); if (r < 0) continue
            val id = "%c%02d".format(Locale.US, o.sys, o.prn)
            val cur = into[id]; if (cur == null || r < cur.first) into[id] = r to o
        }
    }

    private fun dist(a: DoubleArray, b: DoubleArray): Double { val x = a[0] - b[0]; val y = a[1] - b[1]; val z = a[2] - b[2]; return sqrt(x * x + y * y + z * z) }

    // ---------------- Kalmano filtras ----------------
    private class Filter(var x: DoubleArray, var p: Array<DoubleArray>, val idx: HashMap<String, Int>)

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size; val m = b[0].size; val k = b.size
        return Array(n) { i -> DoubleArray(m) { j -> var s = 0.0; for (q in 0 until k) s += a[i][q] * b[q][j]; s } }
    }
    private fun tr(a: Array<DoubleArray>) = Array(a[0].size) { j -> DoubleArray(a.size) { i -> a[i][j] } }
    private fun inv(a0: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a0.size; val a = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) a0[i][j] else if (j - n == i) 1.0 else 0.0 } }
        for (c in 0 until n) {
            val piv = (c until n).maxByOrNull { abs(a[it][c]) }!!
            if (abs(a[piv][c]) < 1e-15) return null
            val t = a[c]; a[c] = a[piv]; a[piv] = t
            val d = a[c][c]; for (j in 0 until 2 * n) a[c][j] /= d
            for (r in 0 until n) if (r != c) { val f = a[r][c]; if (f != 0.0) for (j in 0 until 2 * n) a[r][j] -= f * a[c][j] }
        }
        return Array(n) { i -> DoubleArray(n) { j -> a[i][j + n] } }
    }

    private fun addAmb(f: Filter, id: String, value: Double, sigma: Double) {
        val n = f.x.size
        f.idx[id] = n
        f.x = f.x.copyOf(n + 1).also { it[n] = value }
        f.p = Array(n + 1) { i -> DoubleArray(n + 1) { j -> if (i < n && j < n) f.p[i][j] else if (i == n && j == n) sigma * sigma else 0.0 } }
    }
    private fun resetAmb(f: Filter, id: String, value: Double, sigma: Double) {
        val k = f.idx[id] ?: return addAmb(f, id, value, sigma)
        f.x[k] = value
        for (i in f.p.indices) { f.p[i][k] = 0.0; f.p[k][i] = 0.0 }
        f.p[k][k] = sigma * sigma
    }

    class Run(val name: String, val sols: ArrayList<Pair<Long, PositionSolver.Solution>> = ArrayList(),
              var resets: Int = 0, var phaseObs: Int = 0, var codeRej: Int = 0, var convergedS: Double = Double.NaN, val sigH: ArrayList<Double> = ArrayList())

    /** qPos – padėties proceso triukšmas (m²/s): statinis ~1e-4, kinematinis ~1. */
    /** Vieno epochos RTK žingsnis (po įrašo ir gyvai): e0, e1 – bazės epochos aplink prietaiso laiką. */
    class Engine(var qPos: Double, val run: Run) {
        private var f: Filter? = null
        private var lastT = Double.NaN; private var t0 = Double.NaN
        private val lastAdr = HashMap<String, DoubleArray>()          // id -> [ADR, Doppler, t]
        var lastSigH = Double.NaN
            private set
        /** Kabinos kaukė: true – palydovą (azimutas, aukštis °) šioje epochoje nenaudoti. */
        var mask: ((Double, Double) -> Boolean)? = null
        /** Paskutinės epochos palydovai: id -> [azimutas °, aukštis °, C/N0, kodo DD liekana m (NaN), fazės šuolis 0/1, kaukė 0/1]. */
        @Volatile var lastObs: Map<String, DoubleArray> = emptyMap()
            private set
        var dopplerSlips = 0
            private set
        var masked = 0
            private set
        /** Krano apskritimo apribojimas (Crane) – gyvai ir po įrašo. */
        var useCrane = true
        var lastPhase = 0
            private set

        fun step(ep: Sp3V5.Ep, e0: BEp, e1: BEp, bp: DoubleArray, prepare: (Double, List<Sp3V5.Ob>) -> List<Sp3V5.SatEp>): PositionSolver.Solution? {
        val t = ep.t
        val a = (t - e0.t) / (e1.t - e0.t)
        val acc = ep.meas.filter { Sp3V5.accept(it) && (it.sys == 'G' || it.sys == 'E') }
        val sats = prepare(t, acc.map { Sp3V5.Ob(it.sys, it.svid, it.pr, it.cn0) })
        val byId = acc.associateBy { it.id }
        // matavimai: yP = P + c·dts − bazės liekana, yL = Φ + c·dts − bazės fazės liekana
        class Ob(val id: String, val sys: Char, val s: Sp3V5.SatEp, val yP: Double, val yL: Double, val slip: Boolean)
        val obs = ArrayList<Ob>()
        for (s in sats) {
            val id = "%c%02d".format(Locale.US, s.sys, s.svid)
            val m = byId[id] ?: continue
            val b0 = e0.obs[id] ?: continue; val b1 = e1.obs[id] ?: continue
            val cdts = s.prc - m.pr
            val rc = b0.rc + (b1.rc - b0.rc) * a
            val yP = s.prc - rc
            var yL = Double.NaN; var slip = false
            val adrOk = m.adr != null && (m.adrState and ADR_VALID) != 0 && (m.adrState and (ADR_RESET or ADR_SLIP)) == 0
            if (adrOk && !b0.rp.isNaN() && !b1.rp.isNaN() && b1.lock >= b0.lock) {
                yL = m.adr!! + cdts - (b0.rp + (b1.rp - b0.rp) * a)
                val prev = lastAdr[id]
                if (prev == null) slip = true
                else if (m.prr != null && !prev[1].isNaN() && t - prev[2] in 0.2..3.0) {
                    // fazės ir Doppler suderinamumas: ΔADR ≈ vidutinis ṙ · Δt (kitaip – nepranešta fazės trūkis)
                    val exp = 0.5 * (m.prr + prev[1]) * (t - prev[2])
                    if (abs(m.adr!! - prev[0] - exp) > 1.0) { slip = true; dopplerSlips++ }
                }
            } else slip = true
            if (adrOk) lastAdr[id] = doubleArrayOf(m.adr!!, m.prr ?: Double.NaN, t) else lastAdr.remove(id)
            obs.add(Ob(id, s.sys, s, yP, yL, slip))
        }
        if (obs.size < 5) return null
        // inicializacija: bazės vieta (VRS – šalia prietaiso), σ 30 m
        if (f == null) {
            f = Filter(doubleArrayOf(bp[0], bp[1], bp[2]), Array(3) { i -> DoubleArray(3) { k -> if (i == k) 900.0 else 0.0 } }, HashMap())
            t0 = t
        }
        val ff = f!!
        // prognozė
        val dt = if (lastT.isNaN()) 1.0 else (t - lastT).coerceIn(0.0, 30.0)
        if (!lastT.isNaN() && t - lastT > 2.5) {
            for (id in ff.idx.keys.toList()) resetAmb(ff, id, 0.0, 1e4); run.resets++
        }
        lastT = t
        for (i in 0..2) ff.p[i][i] += qPos * dt
        // aukščiai ir vienetiniai vektoriai
        val x = doubleArrayOf(ff.x[0], ff.x[1], ff.x[2])
        class G(val o: Ob, val rho: Double, val e: DoubleArray, val el: Double, val az: Double)
        val geo = obs.mapNotNull { o ->
            o.s.rp.fill(0.0); o.s.rotate(x); o.s.rotate(x)
            val r = dist(x, o.s.rp)
            val l = Sp3Solver.look(x, o.s.rp, r)
            if (l[4] < Math.toRadians(10.0)) null
            else G(o, r, DoubleArray(3) { (x[it] - o.s.rp[it]) / r }, l[4], (Math.toDegrees(l[3]) + 360) % 360)
        }
        // fazės dviprasmybės: nauji palydovai ir šuoliai
        for (g in geo) {
            if (g.o.yL.isNaN()) continue
            val init = g.o.yL - g.o.yP
            if (g.o.id !in ff.idx) addAmb(ff, g.o.id, init, 30.0)
            else if (g.o.slip) { resetAmb(ff, g.o.id, init, 30.0); run.resets++ }
        }
        // dvigubi skirtumai kiekvienai sistemai
        // key = sistema·2 + tipas (0 kodas, 1 fazė) – kovariacijai (bendras atskaitos palydovas)
        class Dd(val h: DoubleArray, val v: Double, val sRef: Double, val sI: Double, val key: Int, val id: String)
        val dds = ArrayList<Dd>()
        val n = ff.x.size
        val masks = geo.associate { it.o.id to (mask?.invoke(it.az, Math.toDegrees(it.el)) == true) }
        val maskedRes = HashMap<String, Double>()
        for ((si, sy) in listOf('G', 'E').withIndex()) {
            val gs = geo.filter { it.o.sys == sy && masks[it.o.id] != true }
            // užmaskuotų palydovų kodo liekana (tik žemėlapiui) – to paties atskaitos palydovo atžvilgiu
            gs.maxByOrNull { it.el }?.let { ref -> geo.filter { it.o.sys == sy && masks[it.o.id] == true }.forEach { g ->
                maskedRes[g.o.id] = (g.o.yP - ref.o.yP) - (g.rho - ref.rho) } }
            if (gs.size < 2) continue
            for (type in 0..1) {
                val cand = if (type == 0) gs else gs.filter { !it.o.yL.isNaN() && it.o.id in ff.idx }
                if (cand.size < 2) continue
                val ref = cand.maxByOrNull { it.el }!!
                // svoris pagal aukštį ir signalo stiprumą: silpnas signalas – didesnė σ
                fun sd(g: G): Double {
                    val k = Math.pow(10.0, (40.0 - g.o.s.cn0) / 20.0).coerceIn(0.7, 5.0)
                    return (if (type == 0) 3.0 else 0.02) * k / sin(g.el)
                }
                for (g in cand) {
                    if (g === ref) continue
                    val h = DoubleArray(n)
                    for (k in 0..2) h[k] = g.e[k] - ref.e[k]
                    var pred = g.rho - ref.rho
                    val meas: Double
                    if (type == 0) meas = g.o.yP - ref.o.yP
                    else {
                        val ki = ff.idx[g.o.id]!!; val kr = ff.idx[ref.o.id]!!
                        h[ki] += 1.0; h[kr] -= 1.0
                        pred += ff.x[ki] - ff.x[kr]
                        meas = g.o.yL - ref.o.yL
                    }
                    dds.add(Dd(h, meas - pred, sd(ref), sd(g), si * 2 + type, g.o.id))
                }
            }
        }
        if (dds.isEmpty()) return null
        // kabinos kaukė ir paskutinės epochos palydovų informacija (žemėlapio mokymuisi – visi, ir užmaskuoti)
        masked += masks.count { it.value }
        val codeRes = dds.filter { it.key % 2 == 0 }.associate { it.id to it.v } + maskedRes
        lastObs = geo.associate { g -> g.o.id to doubleArrayOf(g.az, Math.toDegrees(g.el), g.o.s.cn0, codeRes[g.o.id] ?: Double.NaN,
            if (g.o.slip) 1.0 else 0.0, if (masks[g.o.id] == true) 1.0 else 0.0) }
        // inovacijų patikra: kodo – atspindžiai (> 15 m, kai filtras jau suartėjęs); fazės – nepastebėti šuoliai
        val posSig = sqrt(ff.p[0][0] + ff.p[1][1] + ff.p[2][2])
        // filtras nuklydo (kodo inovacijų mediana > 30 m, nors σ maža) – iš naujo
        val codeV = dds.filter { it.key % 2 == 0 }.map { abs(it.v) }.sorted()
        if (codeV.size >= 4 && posSig < 5.0 && codeV[codeV.size / 2] > 30.0) { f = null; lastAdr.clear(); run.resets++; return null }
        val keep = dds.filter { d ->
            val isCode = d.key % 2 == 0
            if (isCode && posSig < 5.0 && abs(d.v) > maxOf(15.0, 4 * d.sI)) { run.codeRej++; false }
            else if (!isCode && posSig < 2.0 && abs(d.v) > 1.5) { resetAmb(ff, d.id, ff.x[ff.idx[d.id]!!] + d.v, 30.0); run.resets++; false }
            else true
        }
        if (keep.isEmpty()) return null
        // krano apribojimas: atstumas iki bokšto ašies ir aukštis (nepriklausomi pseudo-matavimai)
        val cr = if (useCrane) Crane.pseudo(doubleArrayOf(ff.x[0], ff.x[1], ff.x[2])) else emptyList()
        val m0 = keep.size; val m = m0 + cr.size
        val H = Array(m) { i -> if (i < m0) keep[i].h else DoubleArray(n).also { r -> for (k in 0..2) r[k] = cr[i - m0].first[k] } }
        val R = Array(m) { i -> DoubleArray(m) { k ->
            if (i < m0 && k < m0) (if (keep[i].key == keep[k].key) keep[i].sRef * keep[i].sRef else 0.0) + (if (i == k) keep[i].sI * keep[i].sI else 0.0)
            else if (i == k) cr[i - m0].third * cr[i - m0].third else 0.0 } }
        val v = Array(m) { i -> doubleArrayOf(if (i < m0) keep[i].v else cr[i - m0].second) }
        val pht = mul(ff.p, tr(H))
        val sMat = mul(H, pht).also { for (i in 0 until m) for (k in 0 until m) it[i][k] += R[i][k] }
        val sInv = inv(sMat) ?: return null
        val K = mul(pht, sInv)
        val dx = mul(K, v)
        for (i in 0 until n) ff.x[i] += dx[i][0]
        val kh = mul(K, H)
        val ikh = Array(n) { i -> DoubleArray(n) { k -> (if (i == k) 1.0 else 0.0) - kh[i][k] } }
        ff.p = mul(ikh, ff.p).let { p2 -> Array(n) { i -> DoubleArray(n) { k -> 0.5 * (p2[i][k] + p2[k][i]) } } }
        run.phaseObs += keep.count { it.key % 2 == 1 }
        // rezultatas
        val llh = PositionSolver.ecefToLlh(ff.x[0], ff.x[1], ff.x[2])
        val la = llh[0]; val lo = llh[1]
        val eN = doubleArrayOf(-sin(la) * cos(lo), -sin(la) * sin(lo), cos(la)); val eE = doubleArrayOf(-sin(lo), cos(lo), 0.0)
        fun q(a: DoubleArray) = (0..2).sumOf { i -> (0..2).sumOf { k -> a[i] * ff.p[i][k] * a[k] } }
        val sh = sqrt(q(eN) + q(eE))
        run.sigH.add(sh)
        if (run.convergedS.isNaN() && sh < 0.5) run.convergedS = t - t0
        val nPh = keep.count { it.key % 2 == 1 }
        val sol = PositionSolver.Solution(Math.toDegrees(la), Math.toDegrees(lo), llh[2], nPh, sh, doubleArrayOf(ff.x[0], ff.x[1], ff.x[2]))
        lastSigH = sh; lastPhase = nPh
        return sol
        }
    }

    fun run(raw: File, sp3: Sp3Reader, base: Pair<DoubleArray?, List<BEp>>, qPos: Double, name: String, out: File? = null, crane: Boolean = false): Run {
        val run = Run(name)
        val bp = base.first ?: return run
        val eps = base.second; if (eps.size < 2) return run
        val ts = DoubleArray(eps.size) { eps[it].t }
        val eng = Engine(qPos, run).also { it.useCrane = crane }
        val w = out?.bufferedWriter()
        try {
            w?.write("laikas_ms,platuma,ilguma,aukstis_m,fazes_palydovu,sigma_h_m\n")
            Sp3V5.readRaw(raw) { ep ->
                val t = ep.t
                if (!Sp3Solver.inRange(sp3, t)) return@readRaw
                var j = java.util.Arrays.binarySearch(ts, t); if (j < 0) j = -j - 1
                if (j <= 0 || j >= eps.size) return@readRaw
                val e0 = eps[j - 1]; val e1 = eps[j]
                if (e1.t - e0.t > 2.5) return@readRaw
                val sol = eng.step(ep, e0, e1, bp) { tt, ob -> Sp3V5.prepare(tt, ob, sp3) } ?: return@readRaw
                run.sols.add(ep.timeMs to sol)
                w?.write(String.format(Locale.US, "%d,%.9f,%.9f,%.3f,%d,%.3f\n", ep.timeMs, sol.lat, sol.lon, sol.h, sol.sats, sol.hacc))
            }
        } finally { w?.close() }
        return run
    }

    private fun line(r: Run, ref: Pair<Double, Double>?, fixH: Double?): String {
        if (r.sols.isEmpty()) return "${r.name}: nėra sprendinių"
        val mPerDeg = Math.toRadians(1.0) * 6_371_000.0
        val la0 = r.sols.map { it.second.lat }.sorted().let { it[it.size / 2] }; val lo0 = r.sols.map { it.second.lon }.sorted().let { it[it.size / 2] }
        val cl = cos(Math.toRadians(la0))
        val last = r.sols.takeLast(maxOf(1, r.sols.size / 3))
        val spread = last.map { sqrt(((it.second.lon - lo0) * mPerDeg * cl).let { e -> e * e } + ((it.second.lat - la0) * mPerDeg).let { nn -> nn * nn }) }.sorted()
        val sb = StringBuilder("${r.name}: ")
        if (ref != null) {
            val e = (lo0 - ref.second) * mPerDeg * cos(Math.toRadians(ref.first)); val nn = (la0 - ref.first) * mPerDeg
            sb.append("medianos poslinkis nuo atskaitos rytai %+.2f m, šiaurė %+.2f m (%.2f m)".format(Locale.US, e, nn, sqrt(e * e + nn * nn)))
        }
        sb.append("; paskutinio trečdalio sklaida aplink medianą: mediana %.2f m, 90 %% %.2f m".format(Locale.US,
            PositionSolver.percentile(spread, 0.5), PositionSolver.percentile(spread, 0.9)))
        val h = PositionSolver.percentile(r.sols.map { it.second.h }.sorted(), 0.5)
        sb.append("; aukščio skirtumas ").append(fixH?.let { "%+.2f m".format(Locale.US, h - it) } ?: "nėra")
        sb.append("; filtro σh (paskutinis) %.2f m".format(Locale.US, r.sigH.lastOrNull() ?: Double.NaN))
        sb.append("; suartėjo (σh < 0,5 m) po ").append(if (r.convergedS.isNaN()) "nesuartėjo" else "%.0f s".format(Locale.US, r.convergedS))
        sb.append("; sprendinių ${r.sols.size}, fazės DD ${r.phaseObs}, dviprasmybių paleidimų ${r.resets}, atmesta kodo (atspindžiai) ${r.codeRej}")
        return sb.toString()
    }

    /** ADR statistika: kiek GPS L1 / Galileo E1 matavimų turi galiojančią fazę. */
    fun adrShare(raw: File): Pair<Int, Int> {
        var ok = 0; var all = 0
        Sp3V5.readRaw(raw) { ep -> ep.meas.filter { it.sys == 'G' || it.sys == 'E' }.forEach { m ->
            all++; if (m.adr != null && (m.adrState and ADR_VALID) != 0) ok++ } }
        return ok to all
    }

    fun block(raw: File, rtcm: File, sp3: Sp3Reader, out: File, ref: Pair<Double, Double>?, fixH: Double?): String = buildString {
        appendLine()
        appendLine("--- RTK (nešlio fazė, vieno dažnio, plaukiojantis sprendinys) ---")
        if (!rtcm.exists() || rtcm.length() < 40) { appendLine("Neatlikta: nėra RTCM žinučių (gnss_rtcm.csv) – įjunkite NTRIP"); return@buildString }
        val (ok, all) = adrShare(raw)
        appendLine("Prietaiso fazė (ADR) galioja: $ok iš $all GPS L1 / Galileo E1 matavimų (%.0f %%)".format(Locale.US, if (all > 0) 100.0 * ok / all else 0.0))
        if (ok < 100) { appendLine("Neatlikta: prietaisas nepateikia nešlio fazės (ADR) – RTK neįmanomas, lieka DGNSS"); return@buildString }
        val t0 = run { var t = 0.0; Sp3V5.readRaw(raw) { if (t == 0.0) t = it.t }; t }
        val base = readBase(rtcm, sp3, t0)
        if (base.first == null) { appendLine("Neatlikta: nėra bazės koordinačių (1005)"); return@buildString }
        val bPh = base.second.sumOf { e -> e.obs.values.count { !it.rp.isNaN() } }; val bAll = base.second.sumOf { it.obs.size }
        appendLine("Bazės epochų ${base.second.size}; bazės fazė galioja $bPh iš $bAll")
        val st = run(raw, sp3, base, 1e-4, "RTK statinis", out)
        val kin = run(raw, sp3, base, 1.0, "RTK kinematinis")
        appendLine(line(st, ref, fixH))
        appendLine(line(kin, ref, fixH))
        if (Crane.valid) {
            val cr = run(raw, sp3, base, 0.05, "RTK su krano apribojimu", crane = true)
            appendLine(line(cr, ref, fixH))
            cr.sols.lastOrNull()?.let { appendLine("  Kampas aplink bokštą (paskutinis): %.1f°".format(Locale.US, Crane.slewDeg(it.second.lat, it.second.lon))) }
        }
        appendLine("Failas $OUT_FILE (statinis); σh – filtro įvertintas horizontalus neapibrėžtumas")
    }
}
