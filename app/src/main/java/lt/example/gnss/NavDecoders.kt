package lt.example.gnss

import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** Bitų skaitymas iš baitų srauto (vyriausias bitas pirmas). */
class Bits(private val d: ByteArray, private val base: Int = 0) {
    fun bit(p: Int): Int { val b = base + p; return (d[b ushr 3].toInt() ushr (7 - (b and 7))) and 1 }
    fun u(p: Int, n: Int): Long { var v = 0L; for (k in 0 until n) v = (v shl 1) or bit(p + k).toLong(); return v }
    fun s(p: Int, n: Int): Long { val v = u(p, n); return if (v and (1L shl (n - 1)) != 0L) v - (1L shl n) else v }
    /** Ženklas-modulis (GLONASS): pirmasis bitas – ženklas. */
    fun sm(p: Int, n: Int): Long { val v = u(p + 1, n - 1); return if (bit(p) == 1) -v else v }
    val size get() = d.size * 8 - base
}

/**
 * Galileo I/NAV (GnssNavigationMessage 0x0601): 29 baitai = lyginė (114 b) + nelyginė (114 b) puslapio dalis be sinchro ir uodegos.
 * Žodis (128 b) = lyginės dalies bitai 2..113 + nelyginės 2..17. CRC-24Q – per 196 bitus (lyginė 114 + nelyginės 82).
 * Efemeridė – žodžiai 1–4 su tuo pačiu IODnav; BGD(E1,E5b) – 5 žodis.
 */
object GalileoNav {
    const val TYPE = 0x0601
    private const val PI = 3.1415926535898

    fun crcOk(d: ByteArray): Boolean {
        if (d.size < 29) return false
        val b = Bits(d)
        var crc = 0
        for (k in 0 until 196) {
            crc = crc xor (b.bit(k) shl 23)
            crc = crc shl 1
            if (crc and (1 shl 24) != 0) crc = crc xor 0x1864CFB
        }
        return (crc and 0xFFFFFF).toLong() == b.u(196, 24)
    }

    /** 128 bitų žodis arba null (CRC klaida, „alert“ puslapis ar ne nominalus). */
    fun word(d: ByteArray): ByteArray? {
        if (!crcOk(d)) return null
        val b = Bits(d)
        if (b.bit(1) == 1 || b.bit(114 + 1) == 1) return null            // puslapio tipas: 1 – „alert“
        val out = ByteArray(16)
        for (k in 0 until 128) {
            val bit = if (k < 112) b.bit(2 + k) else b.bit(114 + 2 + k - 112)
            if (bit == 1) out[k ushr 3] = (out[k ushr 3].toInt() or (0x80 ushr (k and 7))).toByte()
        }
        return out
    }

    fun type(w: ByteArray) = Bits(w).u(0, 6).toInt()

    /** Žodžiai 1–4 (to paties IODnav) -> efemeridė; week – GPS savaitė, artimiausia gavimo laikui tRx (GPS s). */
    fun ephemeris(svid: Int, w: Map<Int, ByteArray>, tRx: Double, bgd: Double = 0.0): PositionSolver.Ephemeris? {
        val w1 = Bits(w[1] ?: return null); val w2 = Bits(w[2] ?: return null)
        val w3 = Bits(w[3] ?: return null); val w4 = Bits(w[4] ?: return null)
        val iod = w1.u(6, 10)
        if (w2.u(6, 10) != iod || w3.u(6, 10) != iod || w4.u(6, 10) != iod) return null
        val toe = w1.u(16, 14) * 60.0
        val m0 = w1.s(30, 32) * Math.scalb(1.0, -31) * PI
        val e = w1.u(62, 32) * Math.scalb(1.0, -33)
        val sqrtA = w1.u(94, 32) * Math.scalb(1.0, -19)
        val om0 = w2.s(16, 32) * Math.scalb(1.0, -31) * PI
        val i0 = w2.s(48, 32) * Math.scalb(1.0, -31) * PI
        val om = w2.s(80, 32) * Math.scalb(1.0, -31) * PI
        val idot = w2.s(112, 14) * Math.scalb(1.0, -43) * PI
        val omd = w3.s(16, 24) * Math.scalb(1.0, -43) * PI
        val dn = w3.s(40, 16) * Math.scalb(1.0, -43) * PI
        val cuc = w3.s(56, 16) * Math.scalb(1.0, -29); val cus = w3.s(72, 16) * Math.scalb(1.0, -29)
        val crc = w3.s(88, 16) * Math.scalb(1.0, -5); val crs = w3.s(104, 16) * Math.scalb(1.0, -5)
        val cic = w4.s(22, 16) * Math.scalb(1.0, -29); val cis = w4.s(38, 16) * Math.scalb(1.0, -29)
        val toc = w4.u(54, 14) * 60.0
        val af0 = w4.s(68, 31) * Math.scalb(1.0, -34)
        val af1 = w4.s(99, 21) * Math.scalb(1.0, -46)
        val af2 = w4.s(120, 6) * Math.scalb(1.0, -59)
        if (sqrtA < 5000.0 || sqrtA > 5600.0 || e >= 0.05) return null
        val week = nearestWeek(toe, tRx)
        return PositionSolver.Ephemeris(svid, week, toc, af0, af1, af2, bgd, iod.toInt(), toe, sqrtA, e, m0, dn,
            om0, i0, om, omd, idot, cuc, cus, crc, crs, cic, cis)
    }

    fun bgdE1E5b(w5: ByteArray): Double = Bits(w5).s(57, 10) * Math.scalb(1.0, -32)

    /** Savaitė, kurioje toe artimiausias laikui t (GPS s). */
    fun nearestWeek(toe: Double, t: Double): Int {
        val w = Math.floorDiv(t.toLong(), 604_800L).toInt()
        val dt = t - (w * 604_800.0 + toe)
        return when { dt > 302_400 -> w + 1; dt < -302_400 -> w - 1; else -> w }
    }

    /** Laukiami žodžiai pagal palydovą (IODnav). */
    class Collector {
        private val words = HashMap<Int, HashMap<Long, HashMap<Int, ByteArray>>>()
        private val bgd = HashMap<Int, Double>()
        val ephs = HashMap<Int, PositionSolver.Ephemeris>()
        var pages = 0; var bad = 0
        fun add(svid: Int, d: ByteArray, tRx: Double) {
            val w = word(d) ?: run { bad++; return }
            pages++
            val t = type(w)
            if (t == 5) { bgd[svid] = bgdE1E5b(w); ephs[svid]?.let { ephs[svid] = it.copy(tgd = bgd[svid]!!) }; return }
            if (t !in 1..4) return
            val iod = Bits(w).u(6, 10)
            val byIod = words.getOrPut(svid) { HashMap() }
            val m = byIod.getOrPut(iod) { HashMap() }
            m[t] = w
            if (byIod.size > 3) byIod.keys.filter { it != iod }.take(byIod.size - 3).forEach { byIod.remove(it) }
            if (m.size == 4) ephemeris(svid, m, tRx, bgd[svid] ?: 0.0)?.let { ephs[svid] = it }
        }
    }
}

/**
 * GLONASS L1 C/A eilutės (GnssNavigationMessage 0x0301): 11 baitų, MediaTek – 3 užpildo bitai priekyje, po jų 85 eilutės bitai
 * (0 – tuščias bitas, 1..4 – eilutės numeris m). Efemeridė – eilutės 1–4 (PZ-90 ECEF būsena laiku tb, Maskvos laikas).
 * Padėtis – RK4 integravimas su J2 ir saulės/mėnulio pagreičiais (GLONASS ICD A.3.1.2).
 */
object GloNav {
    const val TYPE = 0x0301
    private const val MU = 3.9860044e14
    private const val J2 = 1.0826257e-3
    private const val RE = 6378136.0
    private const val OG = 7.292115e-5

    class Eph(val slot: Int, val tbGps: Double, val x: DoubleArray, val acc: DoubleArray, val tauN: Double, val gammaN: Double)

    /** Eilutės bitai: pirmas užpildo bitų skaičius parenkamas pagal tuščią bitą ir m 1..15 (MediaTek – 3). */
    fun bits(d: ByteArray, pad: Int = 3): Bits? = if (d.size >= 11) Bits(d, pad) else null

    fun stringNo(b: Bits) = b.u(1, 4).toInt()

    /** Eilutės 1–4 -> efemeridė; tRxUtcMs – gavimo laikas (UTC ms), tb dienai nustatyti. */
    fun ephemeris(slot: Int, s: Map<Int, Bits>, tRxUtcMs: Long): Eph? {
        val s1 = s[1] ?: return null; val s2 = s[2] ?: return null; val s3 = s[3] ?: return null; val s4 = s[4] ?: return null
        val km = 1000.0
        val x = doubleArrayOf(
            s1.sm(50, 27) * Math.scalb(1.0, -11) * km, s2.sm(50, 27) * Math.scalb(1.0, -11) * km, s3.sm(50, 27) * Math.scalb(1.0, -11) * km,
            s1.sm(21, 24) * Math.scalb(1.0, -20) * km, s2.sm(21, 24) * Math.scalb(1.0, -20) * km, s3.sm(21, 24) * Math.scalb(1.0, -20) * km)
        val acc = doubleArrayOf(s1.sm(45, 5) * Math.scalb(1.0, -30) * km, s2.sm(45, 5) * Math.scalb(1.0, -30) * km, s3.sm(45, 5) * Math.scalb(1.0, -30) * km)
        val r = sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]); val v = sqrt(x[3] * x[3] + x[4] * x[4] + x[5] * x[5])
        if (r !in 25.0e6..26.0e6 || v !in 2.5e3..4.5e3) return null
        val tbMin = s2.u(9, 7) * 15.0
        // tb – Maskvos laikas (UTC+3 h); diena – artimiausia gavimo laikui
        val dayMs = Math.floorDiv(tRxUtcMs, 86_400_000L) * 86_400_000L
        var tbUtcMs = dayMs + (tbMin * 60_000.0).toLong() - 3 * 3_600_000L
        while (tbUtcMs - tRxUtcMs > 43_200_000L) tbUtcMs -= 86_400_000L
        while (tRxUtcMs - tbUtcMs > 43_200_000L) tbUtcMs += 86_400_000L
        val tbGps = tbUtcMs / 1000.0 - 315_964_800.0 + 18.0
        return Eph(slot, tbGps, x, acc, s4.sm(5, 22) * Math.scalb(1.0, -30), s3.sm(6, 11) * Math.scalb(1.0, -40))
    }

    private fun deq(x: DoubleArray, a: DoubleArray): DoubleArray {
        val r2 = x[0] * x[0] + x[1] * x[1] + x[2] * x[2]; val r3 = r2 * sqrt(r2)
        val aa = 1.5 * J2 * MU * RE * RE / r2 / r3; val b = 5.0 * x[2] * x[2] / r2; val c = -MU / r3 - aa * (1.0 - b)
        return doubleArrayOf(x[3], x[4], x[5],
            (c + OG * OG) * x[0] + 2.0 * OG * x[4] + a[0], (c + OG * OG) * x[1] - 2.0 * OG * x[3] + a[1], (c - 2.0 * aa) * x[2] + a[2])
    }

    /** ECEF (m) laiku t (GPS s); null, jei |t − tb| > maxDt. */
    fun position(e: Eph, t: Double, maxDt: Double = 2 * 3600.0): DoubleArray? {
        var tt = t - e.tbGps
        if (abs(tt) > maxDt) return null
        var x = e.x.copyOf()
        val step = if (tt >= 0) 60.0 else -60.0
        while (abs(tt) > 1e-9) {
            val h = if (abs(tt) > abs(step)) step else tt
            val k1 = deq(x, e.acc)
            val k2 = deq(DoubleArray(6) { x[it] + k1[it] * h / 2 }, e.acc)
            val k3 = deq(DoubleArray(6) { x[it] + k2[it] * h / 2 }, e.acc)
            val k4 = deq(DoubleArray(6) { x[it] + k3[it] * h }, e.acc)
            x = DoubleArray(6) { x[it] + (k1[it] + 2 * k2[it] + 2 * k3[it] + k4[it]) * h / 6 }
            tt -= h
        }
        return doubleArrayOf(x[0], x[1], x[2])
    }

    /** Užpildo bitų skaičius: MediaTek – 3 priekyje, Android dokumentacija – 0 (užpildas gale); parenkamas pagal galiojančią efemeridę. */
    @Volatile var pad = 3

    class Collector {
        private val strs = HashMap<Int, HashMap<Int, Pair<Long, Bits>>>()
        private val alt = HashMap<Int, HashMap<Int, Pair<Long, Bits>>>()
        val ephs = HashMap<Int, Eph>()
        var strings = 0; var bad = 0
        fun add(slot: Int, d: ByteArray, tRxUtcMs: Long) {
            // kitas užpildas – lygiagrečiai; jei jis duoda galiojančią efemeridę, perjungiama
            val other = if (pad == 3) 0 else 3
            bits(d, other)?.let { ob ->
                val m = stringNo(ob)
                if (ob.bit(0) == 0 && m in 1..4) {
                    val st = alt.getOrPut(slot) { HashMap() }
                    st[m] = tRxUtcMs to ob
                    if (st.size == 4 && (1..4).all { k -> st[k]?.let { abs(it.first - tRxUtcMs) <= 30_000 } == true } && ephs.isEmpty())
                        ephemeris(slot, st.mapValues { it.value.second }, tRxUtcMs)?.let { ephs[slot] = it; pad = other; strs.clear(); alt.clear(); return }
                }
            }
            val b = bits(d, pad) ?: run { bad++; return }
            val m = stringNo(b)
            if (b.bit(0) != 0 || m !in 1..15) { bad++; return }
            strings++
            if (m > 4) return
            val st = strs.getOrPut(slot) { HashMap() }
            st[m] = tRxUtcMs to b
            // 1–4 eilutės to paties kadro: per 30 s
            if (st.size == 4 && (1..4).all { k -> st[k]?.let { abs(it.first - tRxUtcMs) <= 30_000 } == true })
                ephemeris(slot, st.mapValues { it.value.second }, tRxUtcMs)?.let { ephs[slot] = it }
        }
    }
}

/** gnss_nav.csv -> Galileo BGD(E1,E5b) pagal SVID ir efemeridžių skaičiai (ataskaitai ir v5). */
object NavFile {
    class Result(val gal: GalileoNav.Collector, val glo: GloNav.Collector)

    fun read(nav: File): Result {
        val gal = GalileoNav.Collector(); val glo = GloNav.Collector()
        if (!nav.exists()) return Result(gal, glo)
        nav.bufferedReader().use { br ->
            val head = br.readLine()?.split(',') ?: return Result(gal, glo)
            val iC = head.indexOf("konstelacija"); val iS = head.indexOf("svid"); val iT = head.indexOf("tipas"); val iH = head.indexOf("duomenys_hex")
            if (minOf(iC, iS, iT, iH) < 0) return Result(gal, glo)
            br.forEachLine { l ->
                val p = l.split(',')
                if (p.size <= iH) return@forEachLine
                val ms = p[0].toLongOrNull() ?: return@forEachLine
                val sv = p[iS].toIntOrNull() ?: return@forEachLine
                val h = p[iH].trim()
                val d = ByteArray(h.length / 2) { ((Character.digit(h[2 * it], 16) shl 4) or Character.digit(h[2 * it + 1], 16)).toByte() }
                when (p[iT].toIntOrNull()) {
                    GalileoNav.TYPE -> gal.add(sv, d, ms / 1000.0 - 315_964_800.0 + 18.0)
                    GloNav.TYPE -> glo.add(sv, d, ms)
                }
            }
        }
        return Result(gal, glo)
    }

    fun text(r: Result): String = "Galileo I/NAV: puslapių su teisingu CRC ${r.gal.pages}, atmesta ${r.gal.bad}; efemeridžių ${r.gal.ephs.size}" +
        (if (r.gal.ephs.isEmpty()) "" else " (" + r.gal.ephs.values.sortedBy { it.svid }.joinToString(", ") { "E%02d BGD %+.2f ns".format(Locale.US, it.svid, it.tgd * 1e9) } + ")") +
        "\nGLONASS: eilučių ${r.glo.strings}, atmesta ${r.glo.bad}; efemeridžių ${r.glo.ephs.size}" +
        (if (r.glo.ephs.isEmpty()) "" else " (" + r.glo.ephs.keys.sorted().joinToString(" ") { "R%02d".format(Locale.US, it) } + ")")
}

/**
 * Paskutinis kiekvieno palydovo navigacijos pranešimas 3D spinduliui: tikri perduoti bitai (GPS – 10 žodžių po 30 b su paritetu,
 * Galileo – 228 b puslapis, GLONASS – 85 b eilutė), aprašas ir perdavimo sparta (b/s).
 */
object NavBits {
    class Msg(val bits: String, val label: String, val tMs: Long, val rate: Double, val eph: Boolean)
    private val last = java.util.concurrent.ConcurrentHashMap<String, Msg>()

    fun get(key: String): Msg? = last[key]

    /** type – GnssNavigationMessage tipas; key – „GPS22“, „GAL9“, „GLO3“ (kaip Sat.const + svid). */
    fun put(type: Int, svid: Int, data: ByteArray, tMs: Long = System.currentTimeMillis()) {
        describe(type, data, tMs)?.let { last[it.first + svid] = it.second }
    }

    fun describe(type: Int, data: ByteArray, tMs: Long): Pair<String, Msg>? = when (type) {
        PositionSolver.NAV_TYPE_GPS_L1CA -> {
            if (data.size < 40) null else {
                val w = PositionSolver.hypWords(data, PositionSolver.Hyp.H6)
                val bits = w.joinToString("") { Integer.toBinaryString(it or (1 shl 30)).substring(1) }
                val id = ((w[1] ushr 8) and 0x7)                       // HOW: D20..D22 (be pariteto – 6 jaunesnių bitų)
                val label = when (id) {
                    1 -> "SF1 laikrodis, TGD"; 2, 3 -> "SF$id efemeridė"; 4, 5 -> "SF$id almanachas"; else -> "subkadras ?"
                }
                "GPS" to Msg(bits, label, tMs, 50.0, id in 1..3)
            }
        }
        GalileoNav.TYPE -> {
            if (data.size < 29) null else {
                val b = Bits(data)
                val bits = buildString { for (k in 0 until 228) append(b.bit(k)) }
                val t = b.u(2, 6).toInt()
                val label = "I/NAV žodis $t " + when (t) {
                    in 1..4 -> "efemeridė"; 5 -> "jonosfera, BGD"; 6 -> "GST–UTC"; in 7..10 -> "almanachas"
                    0 -> "laikas"; 16 -> "sumažinta efemeridė"; in 17..20 -> "FEC"; else -> ""
                }
                "GAL" to Msg(bits, label.trim(), tMs, 114.0, t in 1..5)
            }
        }
        GloNav.TYPE -> {
            if (data.size < 11) null else {
                val b = Bits(data, GloNav.pad)
                val bits = buildString { for (k in 0 until 85) append(b.bit(k)) }
                val m = GloNav.stringNo(b)
                "GLO" to Msg(bits, "eilutė $m " + if (m in 1..4) "efemeridė" else "almanachas", tMs, 50.0, m in 1..4)
            }
        }
        else -> null
    }
}

/** Bendras navigacijos pranešimų priėmimas (įrašymo paslauga ir 3D vaizdas be įrašymo); pasikartojimai per 1,5 s praleidžiami. */
object NavFeed {
    private val seen = HashMap<String, Pair<Int, Long>>()

    @Synchronized
    fun onMessage(type: Int, svid: Int, data: ByteArray, tMs: Long = System.currentTimeMillis()) {
        val k = "$type:$svid"; val h = data.contentHashCode()
        seen[k]?.let { if (it.first == h && tMs - it.second in 0..1500) return }
        seen[k] = h to tMs
        NavBits.put(type, svid, data, tMs)
        val tGps = tMs / 1000.0 - 315_964_800.0 + 18.0
        when (type) {
            PositionSolver.NAV_TYPE_GPS_L1CA -> BroadcastEph.add(svid, data, Math.floorDiv(tGps.toLong(), 604_800L).toInt(), tMs)
            GalileoNav.TYPE -> BroadcastEph.addGal(svid, data, tGps, tMs)
            GloNav.TYPE -> BroadcastEph.addGlo(svid, data, tMs)
        }
    }
}
