package lt.example.gnss

import java.io.BufferedReader
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS-only pozicijos skaičiuoklė (1 žingsnis).
 *
 * Įvestis:
 *  - gnss_nav.csv: GPS L1 C/A LNAV 1–3 subkadrai (duomenys_hex), iš jų – efemeridės;
 *  - gnss_raw.csv: GPS L1 eilutės su galiojančiu subkadro laiku (state & STATE_SUBFRAME_SYNC).
 * Išvestis: gnss_solver.csv (laikas_ms,platuma,ilguma,aukstis_m,sats,hacc_m).
 *
 * Be Android priklausomybių – galima testuoti JVM'e.
 */
object PositionSolver {

    // ---------------- Konstantos (IS-GPS-200, WGS84) ----------------
    const val C = 299_792_458.0
    const val MU = 3.986005e14
    const val OMEGA_E = 7.2921151467e-5
    const val F_REL = -4.442807633e-10
    const val WEEK_S = 604_800.0
    const val MAX_EPH_AGE_S = 7_200.0
    private const val WGS_A = 6_378_137.0
    private const val WGS_F = 1.0 / 298.257223563
    private const val PI_GPS = 3.1415926535898

    /** GnssMeasurement.STATE_SUBFRAME_SYNC ir STATE_TOW_DECODED. */
    const val STATE_SUBFRAME_SYNC = 4
    const val STATE_TOW_DECODED = 8

    /** GnssNavigationMessage.TYPE_GPS_L1CA. */
    const val NAV_TYPE_GPS_L1CA = 0x0101

    const val SOLVER_HEADER = "laikas_ms,platuma,ilguma,aukstis_m,sats,hacc_m\n"

    // ---------------- Efemeridės ----------------
    data class Ephemeris(
        val svid: Int,
        val week: Int,          // pilna GPS savaitė
        val toc: Double, val af0: Double, val af1: Double, val af2: Double, val tgd: Double,
        val iode: Int,
        val toe: Double, val sqrtA: Double, val e: Double, val m0: Double, val deltaN: Double,
        val omega0: Double, val i0: Double, val omega: Double, val omegaDot: Double, val idot: Double,
        val cuc: Double, val cus: Double, val crc: Double, val crs: Double, val cic: Double, val cis: Double,
    ) {
        /** Absoliutus GPS laikas (s nuo GPS epochos) efemeridės atskaitos momentu. */
        val toeAbs: Double get() = week * WEEK_S + toe
    }

    /** Vienas subkadras: 10 žodžių po 24 duomenų bitus. */
    class Subframe(val id: Int, val words: IntArray)

    /** Iš 40 baitų (10 × 32 bitų, kiekviename paskutiniai 30 bitų) – 24 duomenų bitai su poliškumo pataisa. */
    fun subframeFromBytes(data: ByteArray): Subframe? {
        if (data.size < 40) return null
        val raw = IntArray(10) { i ->
            ((data[4 * i].toInt() and 0xFF) shl 24) or ((data[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((data[4 * i + 2].toInt() and 0xFF) shl 8) or (data[4 * i + 3].toInt() and 0xFF)
        }
        val w30 = IntArray(10) { raw[it] and 0x3FFFFFFF }
        val words = IntArray(10)
        for (i in 0 until 10) {
            var d = (w30[i] ushr 6) and 0xFFFFFF
            // D30* ankstesnio žodžio = 1 -> duomenų bitai invertuoti
            val inverted = if (i == 0) ((d ushr 16) and 0xFF) == 0x74 else (w30[i - 1] and 1) == 1
            if (inverted) d = d.inv() and 0xFFFFFF
            words[i] = d
        }
        if (((words[0] ushr 16) and 0xFF) != 0x8B) return null      // preambulė
        val id = (words[1] ushr 2) and 0x7                          // HOW bitai 20–22
        if (id !in 1..5) return null
        return Subframe(id, words)
    }

    /** Žodžio (1..10) bitai [start, start+len) (1 – vyriausias iš 24). */
    private fun ub(w: IntArray, word: Int, start: Int, len: Int): Long =
        ((w[word - 1].toLong() ushr (24 - (start - 1) - len)) and ((1L shl len) - 1))

    private fun signed(v: Long, bits: Int): Long = if (v and (1L shl (bits - 1)) != 0L) v - (1L shl bits) else v

    private fun cat(hi: Long, lo: Long, loBits: Int): Long = (hi shl loBits) or lo

    private fun p2(e: Int): Double = Math.scalb(1.0, e)

    /** 1–3 subkadrų rinkinys -> efemeridė (IODE turi sutapti). */
    fun decodeEphemeris(svid: Int, sf1: Subframe, sf2: Subframe, sf3: Subframe, refWeek: Int): Ephemeris? {
        val w1 = sf1.words; val w2 = sf2.words; val w3 = sf3.words
        val wn10 = ub(w1, 3, 1, 10).toInt()
        val iodc = cat(ub(w1, 3, 23, 2), ub(w1, 8, 1, 8), 8).toInt()
        val tgd = signed(ub(w1, 7, 17, 8), 8) * p2(-31)
        val toc = ub(w1, 8, 9, 16) * 16.0
        val af2 = signed(ub(w1, 9, 1, 8), 8) * p2(-55)
        val af1 = signed(ub(w1, 9, 9, 16), 16) * p2(-43)
        val af0 = signed(ub(w1, 10, 1, 22), 22) * p2(-31)

        val iode2 = ub(w2, 3, 1, 8).toInt()
        val crs = signed(ub(w2, 3, 9, 16), 16) * p2(-5)
        val dn = signed(ub(w2, 4, 1, 16), 16) * p2(-43) * PI_GPS
        val m0 = signed(cat(ub(w2, 4, 17, 8), ub(w2, 5, 1, 24), 24), 32) * p2(-31) * PI_GPS
        val cuc = signed(ub(w2, 6, 1, 16), 16) * p2(-29)
        val e = cat(ub(w2, 6, 17, 8), ub(w2, 7, 1, 24), 24) * p2(-33)
        val cus = signed(ub(w2, 8, 1, 16), 16) * p2(-29)
        val sqrtA = cat(ub(w2, 8, 17, 8), ub(w2, 9, 1, 24), 24) * p2(-19)
        val toe = ub(w2, 10, 1, 16) * 16.0

        val cic = signed(ub(w3, 3, 1, 16), 16) * p2(-29)
        val om0 = signed(cat(ub(w3, 3, 17, 8), ub(w3, 4, 1, 24), 24), 32) * p2(-31) * PI_GPS
        val cis = signed(ub(w3, 5, 1, 16), 16) * p2(-29)
        val i0 = signed(cat(ub(w3, 5, 17, 8), ub(w3, 6, 1, 24), 24), 32) * p2(-31) * PI_GPS
        val crc = signed(ub(w3, 7, 1, 16), 16) * p2(-5)
        val om = signed(cat(ub(w3, 7, 17, 8), ub(w3, 8, 1, 24), 24), 32) * p2(-31) * PI_GPS
        val omDot = signed(ub(w3, 9, 1, 24), 24) * p2(-43) * PI_GPS
        val iode3 = ub(w3, 10, 1, 8).toInt()
        val idot = signed(ub(w3, 10, 9, 14), 14) * p2(-43) * PI_GPS

        if (iode2 != iode3 || (iodc and 0xFF) != iode2) return null
        if (sqrtA < 4000.0 || e >= 0.1) return null                  // akivaizdžiai neteisingi duomenys
        // 10 bitų savaitė -> pilna, artimiausia atskaitos savaitei
        val week = if (refWeek > 0) refWeek + (((wn10 - refWeek) % 1024 + 1536) % 1024 - 512) else wn10
        return Ephemeris(svid, week, toc, af0, af1, af2, tgd, iode2, toe, sqrtA, e, m0, dn,
            om0, i0, om, omDot, idot, cuc, cus, crc, crs, cic, cis)
    }

    /** Laiko skirtumas savaitės viduje (±302400 s). */
    private fun wrapWeek(dt: Double): Double = when {
        dt > WEEK_S / 2 -> dt - WEEK_S
        dt < -WEEK_S / 2 -> dt + WEEK_S
        else -> dt
    }

    /** Palydovo ECEF padėtis ir laikrodžio pataisa (s) GPS savaitės laiku t (s). */
    fun satState(eph: Ephemeris, tTow: Double): DoubleArray {
        val a = eph.sqrtA * eph.sqrtA
        val n = sqrt(MU / (a * a * a)) + eph.deltaN
        val tk = wrapWeek(tTow - eph.toe)
        val m = eph.m0 + n * tk
        var ek = m
        repeat(10) { ek = m + eph.e * sin(ek) }
        val vk = atan2(sqrt(1 - eph.e * eph.e) * sin(ek), cos(ek) - eph.e)
        val phi = vk + eph.omega
        val s2 = sin(2 * phi); val c2 = cos(2 * phi)
        val u = phi + eph.cus * s2 + eph.cuc * c2
        val r = a * (1 - eph.e * cos(ek)) + eph.crs * s2 + eph.crc * c2
        val i = eph.i0 + eph.idot * tk + eph.cis * s2 + eph.cic * c2
        val xp = r * cos(u); val yp = r * sin(u)
        val om = eph.omega0 + (eph.omegaDot - OMEGA_E) * tk - OMEGA_E * eph.toe
        val x = xp * cos(om) - yp * cos(i) * sin(om)
        val y = xp * sin(om) + yp * cos(i) * cos(om)
        val z = yp * sin(i)
        val tc = wrapWeek(tTow - eph.toc)
        val dt = eph.af0 + eph.af1 * tc + eph.af2 * tc * tc + F_REL * eph.e * eph.sqrtA * sin(ek) - eph.tgd
        return doubleArrayOf(x, y, z, dt)
    }

    // ---------------- Matavimai ----------------
    class Obs(val svid: Int, val pr: Double)

    /** Viena epocha: GPS savaitės laikas, pilna savaitė, laikas_ms ir pseudoatstumai. */
    class Epoch(val week: Int, val tRxTow: Double, val timeMs: Long, val obs: List<Obs>)

    /**
     * Pseudoatstumas pagal Android neapdorotus matavimus (GPS L1 C/A).
     * Grąžina (pseudoatstumas m, pilna savaitė, matavimo imtuvo laikas savaitėje s) arba null.
     * Be STATE_TOW_DECODED laikas žinomas tik 6 s subkadro ribose – dviprasmybė išsprendžiama moduliu 6 s.
     */
    fun pseudorange(timeNanos: Long, fullBias: Long, bias: Double, timeOffset: Double, state: Int, svTimeNs: Long): Triple<Double, Int, Double>? {
        if (state and STATE_SUBFRAME_SYNC == 0) return null
        val gpsNs = timeNanos - fullBias
        val weekNs = 604_800_000_000_000L
        val week = Math.floorDiv(gpsNs, weekNs).toInt()
        val tRxNs = (gpsNs - week * weekNs).toDouble() + timeOffset - bias
        val period = if (state and STATE_TOW_DECODED != 0) 604_800e9 else 6e9
        var dt = (tRxNs - svTimeNs) % period
        if (dt < 0) dt += period
        if (dt > period / 2) dt -= period
        val pr = dt * 1e-9 * C
        if (pr < 1.5e7 || pr > 3.5e7) return null
        return Triple(pr, week, tRxNs * 1e-9)
    }

    // ---------------- Mažiausių kvadratų sprendimas ----------------
    class Solution(val lat: Double, val lon: Double, val h: Double, val sats: Int, val hacc: Double, val ecef: DoubleArray)

    fun solve(epoch: Epoch, ephs: Map<Int, List<Ephemeris>>): Solution? {
        val tAbs = epoch.week * WEEK_S + epoch.tRxTow
        data class Row(val pos: DoubleArray, val pr: Double)
        val rows = ArrayList<Row>()
        for (o in epoch.obs) {
            val eph = ephs[o.svid]?.filter { abs(tAbs - it.toeAbs) <= MAX_EPH_AGE_S }
                ?.minByOrNull { abs(tAbs - it.toeAbs) } ?: continue
            var tTx = epoch.tRxTow - o.pr / C
            var st = satState(eph, tTx)
            tTx -= st[3]
            st = satState(eph, tTx)
            rows.add(Row(st, o.pr + C * st[3]))
        }
        if (rows.size < 4) return null
        val x = DoubleArray(4)                                   // x, y, z, c·dt
        val n = rows.size
        var hMat = Array(n) { DoubleArray(4) }
        var res = DoubleArray(n)
        for (iter in 0 until 10) {
            for ((k, r) in rows.withIndex()) {
                // Sagnac: Žemės sukimasis signalo kelionės metu
                val tau = r.pr / C
                val th = OMEGA_E * tau
                val sx = cos(th) * r.pos[0] + sin(th) * r.pos[1]
                val sy = -sin(th) * r.pos[0] + cos(th) * r.pos[1]
                val sz = r.pos[2]
                val dx = sx - x[0]; val dy = sy - x[1]; val dz = sz - x[2]
                val rho = sqrt(dx * dx + dy * dy + dz * dz)
                hMat[k][0] = -dx / rho; hMat[k][1] = -dy / rho; hMat[k][2] = -dz / rho; hMat[k][3] = 1.0
                res[k] = r.pr - (rho + x[3])
            }
            val dxv = lsq(hMat, res) ?: return null
            for (j in 0..3) x[j] += dxv[j]
            if (sqrt(dxv[0] * dxv[0] + dxv[1] * dxv[1] + dxv[2] * dxv[2]) < 1e-4) break
        }
        val llh = ecefToLlh(x[0], x[1], x[2])
        // HDOP -> horizontalus tikslumas
        val q = inv4(normal(hMat)) ?: return null
        val sl = sin(llh[0]); val cl = cos(llh[0]); val so = sin(llh[1]); val co = cos(llh[1])
        val e = doubleArrayOf(-so, co, 0.0)
        val nn = doubleArrayOf(-sl * co, -sl * so, cl)
        fun quad(a: DoubleArray): Double {
            var s = 0.0
            for (i in 0..2) for (j in 0..2) s += a[i] * q[i][j] * a[j]
            return s
        }
        val hdop = sqrt(quad(e) + quad(nn))
        var ss = 0.0
        for (v in res) ss += v * v
        val sigma = if (n > 4) sqrt(ss / (n - 4)) else 5.0
        val llhDeg = doubleArrayOf(Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2])
        if (llhDeg[2] < -1000 || llhDeg[2] > 20_000) return null
        return Solution(llhDeg[0], llhDeg[1], llhDeg[2], n, hdop * sigma, doubleArrayOf(x[0], x[1], x[2]))
    }

    private fun normal(h: Array<DoubleArray>): Array<DoubleArray> {
        val a = Array(4) { DoubleArray(4) }
        for (r in h) for (i in 0..3) for (j in 0..3) a[i][j] += r[i] * r[j]
        return a
    }

    private fun lsq(h: Array<DoubleArray>, y: DoubleArray): DoubleArray? {
        val a = inv4(normal(h)) ?: return null
        val b = DoubleArray(4)
        for ((k, r) in h.withIndex()) for (i in 0..3) b[i] += r[i] * y[k]
        return DoubleArray(4) { i -> (0..3).sumOf { j -> a[i][j] * b[j] } }
    }

    /** 4×4 matricos atvirkštinė (Gauso–Jordano). */
    private fun inv4(m: Array<DoubleArray>): Array<DoubleArray>? {
        val n = 4
        val a = Array(n) { i -> DoubleArray(2 * n) { j -> if (j < n) m[i][j] else if (j - n == i) 1.0 else 0.0 } }
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(a[r][c]) > abs(a[p][c])) p = r
            if (abs(a[p][c]) < 1e-12) return null
            val t = a[c]; a[c] = a[p]; a[p] = t
            val d = a[c][c]
            for (j in 0 until 2 * n) a[c][j] /= d
            for (r in 0 until n) if (r != c) {
                val f = a[r][c]
                if (f != 0.0) for (j in 0 until 2 * n) a[r][j] -= f * a[c][j]
            }
        }
        return Array(n) { i -> DoubleArray(n) { j -> a[i][j + n] } }
    }

    /** ECEF -> geodezinės (rad, rad, m), WGS84. */
    fun ecefToLlh(x: Double, y: Double, z: Double): DoubleArray {
        val e2 = WGS_F * (2 - WGS_F)
        val lon = atan2(y, x)
        val p = sqrt(x * x + y * y)
        var lat = atan2(z, p * (1 - e2))
        var h = 0.0
        repeat(10) {
            val sl = sin(lat)
            val nRad = WGS_A / sqrt(1 - e2 * sl * sl)
            h = p / cos(lat) - nRad
            lat = atan2(z, p * (1 - e2 * nRad / (nRad + h)))
        }
        return doubleArrayOf(lat, lon, h)
    }

    /** Geodezinės (rad, rad, m) -> ECEF. */
    fun llhToEcef(lat: Double, lon: Double, h: Double): DoubleArray {
        val e2 = WGS_F * (2 - WGS_F)
        val nRad = WGS_A / sqrt(1 - e2 * sin(lat) * sin(lat))
        return doubleArrayOf((nRad + h) * cos(lat) * cos(lon), (nRad + h) * cos(lat) * sin(lon), (nRad * (1 - e2) + h) * sin(lat))
    }

    // ---------------- CSV ----------------
    private fun hexToBytes(s: String): ByteArray {
        val t = s.trim()
        val out = ByteArray(t.length / 2)
        for (i in out.indices) out[i] = ((Character.digit(t[2 * i], 16) shl 4) or Character.digit(t[2 * i + 1], 16)).toByte()
        return out
    }

    /**
     * gnss_nav.csv -> efemeridės pagal SVID. Iš eilės renkami 1, 2 ir 3 subkadrai; laikomos visos galiojančios
     * (pasirenkama artimiausia epochai, ne senesnė nei 2 val.).
     */
    fun readEphemerides(nav: File, refWeek: Int): Map<Int, List<Ephemeris>> {
        val out = HashMap<Int, MutableList<Ephemeris>>()
        if (!nav.exists()) return out
        val last = HashMap<Int, Array<Subframe?>>()
        nav.bufferedReader().use { br ->
            val head = br.readLine()?.split(',') ?: return out
            val iConst = head.indexOf("konstelacija"); val iSv = head.indexOf("svid")
            val iType = head.indexOf("tipas"); val iHex = head.indexOf("duomenys_hex")
            if (minOf(iConst, iSv, iType, iHex) < 0) return out
            br.forEachLine { line ->
                val p = line.split(',')
                if (p.size <= iHex) return@forEachLine
                if (p[iConst] != "GPS" || p[iType].toIntOrNull() != NAV_TYPE_GPS_L1CA) return@forEachLine
                val sv = p[iSv].toIntOrNull() ?: return@forEachLine
                val sf = subframeFromBytes(hexToBytes(p[iHex])) ?: return@forEachLine
                if (sf.id > 3) return@forEachLine
                val slot = last.getOrPut(sv) { arrayOfNulls(3) }
                slot[sf.id - 1] = sf
                val s1 = slot[0]; val s2 = slot[1]; val s3 = slot[2]
                if (s1 != null && s2 != null && s3 != null) {
                    val eph = decodeEphemeris(sv, s1, s2, s3, refWeek) ?: return@forEachLine
                    val list = out.getOrPut(sv) { ArrayList() }
                    // Ta pati efemeridė (IODE + toe) – pakeičiama naujausia
                    list.removeAll { it.iode == eph.iode && it.toe == eph.toe }
                    list.add(eph)
                }
            }
        }
        return out
    }

    /** gnss_raw.csv -> epochos (grupuojama pagal time_nanos), tik GPS L1 su subkadro sinchronizacija. */
    fun readEpochs(raw: File, consumer: (Epoch) -> Unit) {
        if (!raw.exists()) return
        raw.bufferedReader().use { br -> readEpochs(br, consumer) }
    }

    fun readEpochs(br: BufferedReader, consumer: (Epoch) -> Unit) {
        val head = br.readLine()?.split(',') ?: return
        fun idx(n: String) = head.indexOf(n)
        val iMs = idx("laikas_ms"); val iC = idx("konstelacija"); val iSv = idx("svid"); val iSig = idx("signalas")
        val iTn = idx("time_nanos"); val iFb = idx("full_bias_nanos"); val iB = idx("bias_nanos")
        val iOff = idx("time_offset_ns"); val iSt = idx("state"); val iSvt = idx("recv_sv_time_ns")
        if (listOf(iMs, iC, iSv, iSig, iTn, iFb, iB, iSt, iSvt).any { it < 0 }) return
        var curTn = Long.MIN_VALUE
        var curMs = 0L; var curWeek = 0; var curTow = 0.0
        val obs = ArrayList<Obs>()
        fun flush() {
            if (obs.isNotEmpty()) consumer(Epoch(curWeek, curTow, curMs, ArrayList(obs)))
            obs.clear()
        }
        br.forEachLine { line ->
            val p = line.split(',')
            if (p.size <= maxOf(iSvt, iSt, iTn)) return@forEachLine
            if (p[iC] != "GPS" || p[iSig] != "L1") return@forEachLine
            val tn = p[iTn].toLongOrNull() ?: return@forEachLine
            val fb = p[iFb].toLongOrNull() ?: return@forEachLine
            val b = p[iB].toDoubleOrNull() ?: 0.0
            val off = if (iOff >= 0) p[iOff].toDoubleOrNull() ?: 0.0 else 0.0
            val st = p[iSt].toIntOrNull() ?: return@forEachLine
            val svt = p[iSvt].toLongOrNull() ?: return@forEachLine
            val sv = p[iSv].toIntOrNull() ?: return@forEachLine
            if (tn != curTn) { flush(); curTn = tn }
            val r = pseudorange(tn, fb, b, off, st, svt) ?: return@forEachLine
            curMs = p[iMs].toLongOrNull() ?: curMs
            curWeek = r.second
            curTow = r.third - off * 1e-9               // epochos imtuvo laikas be signalo poslinkio
            if (obs.none { it.svid == sv }) obs.add(Obs(sv, r.first))
        }
        flush()
    }

    /** Pirmoji GPS savaitė iš gnss_raw.csv (10 bitų WN išplėtimui). */
    fun firstWeek(raw: File): Int {
        var w = 0
        try {
            raw.bufferedReader().use { br ->
                val head = br.readLine()?.split(',') ?: return 0
                val iTn = head.indexOf("time_nanos"); val iFb = head.indexOf("full_bias_nanos")
                if (iTn < 0 || iFb < 0) return 0
                while (true) {
                    val p = br.readLine()?.split(',') ?: break
                    val tn = p.getOrNull(iTn)?.toLongOrNull() ?: continue
                    val fb = p.getOrNull(iFb)?.toLongOrNull() ?: continue
                    w = Math.floorDiv(tn - fb, 604_800_000_000_000L).toInt()
                    break
                }
            }
        } catch (_: Exception) {}
        return w
    }

    /** Pagrindinis įėjimas: nuskaito failus, sprendžia ir rašo gnss_solver.csv. Grąžina sprendinius. */
    fun solveFiles(raw: File, nav: File, out: File): List<Pair<Long, Solution>> {
        val ephs = readEphemerides(nav, firstWeek(raw))
        val sols = ArrayList<Pair<Long, Solution>>()
        out.bufferedWriter().use { w ->
            w.write(SOLVER_HEADER)
            readEpochs(raw) { ep ->
                val s = solve(ep, ephs) ?: return@readEpochs
                sols.add(ep.timeMs to s)
                w.write(String.format(Locale.US, "%d,%.8f,%.8f,%.2f,%d,%.2f\n", ep.timeMs, s.lat, s.lon, s.h, s.sats, s.hacc))
            }
        }
        return sols
    }

    /** Horizontalus atstumas (m) tarp dviejų taškų (laipsniai). */
    fun horizDist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return r * sqrt(dLat * dLat + dLon * dLon)
    }

    /** Mediana: sprendinio ir telefono fikso (gnss_fix.csv, artimiausias laikas ≤ 1,5 s) horizontalus skirtumas. */
    fun medianDiffToFix(sols: List<Pair<Long, Solution>>, fix: File): Double? {
        if (!fix.exists() || sols.isEmpty()) return null
        val fixes = ArrayList<Triple<Long, Double, Double>>()
        fix.bufferedReader().use { br ->
            val head = br.readLine()?.split(',') ?: return null
            val iT = head.indexOf("laikas_ms"); val iLa = head.indexOf("platuma"); val iLo = head.indexOf("ilguma")
            br.forEachLine { l ->
                val p = l.split(',')
                val t = p.getOrNull(iT)?.toLongOrNull() ?: return@forEachLine
                val la = p.getOrNull(iLa)?.toDoubleOrNull() ?: return@forEachLine
                val lo = p.getOrNull(iLo)?.toDoubleOrNull() ?: return@forEachLine
                fixes.add(Triple(t, la, lo))
            }
        }
        if (fixes.isEmpty()) return null
        fixes.sortBy { it.first }
        val d = ArrayList<Double>()
        for ((t, s) in sols) {
            val f = fixes.minByOrNull { abs(it.first - t) } ?: continue
            if (abs(f.first - t) <= 1500) d.add(horizDist(s.lat, s.lon, f.second, f.third))
        }
        if (d.isEmpty()) return null
        d.sort()
        return if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
    }
}
