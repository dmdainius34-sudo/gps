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

    /** Vienas subkadras: 10 žodžių po 24 duomenų bitus; parityOk – kiek žodžių praėjo pariteto patikrą. */
    class Subframe(val id: Int, val words: IntArray, val parityOk: Int = 10)

    /** Išpakavimo statistika diagnostikai. */
    class UnpackStats {
        var unpacked = 0          // pranešimų, išpakuotų į 10 žodžių
        var preamble = 0          // rasta preambulė 0x8B (1 žodžio pradžioje)
        var parityOk = 0          // žodžių su teisingu paritetu
        var parityBad = 0
        var rejParity = 0         // subkadrų atmesta: ne visi 10 žodžių praėjo paritetą
        var rejTow = 0            // subkadrų atmesta: HOW TOW × 6 ≥ 604800 s
        var lastParityOk = 0
        var chosen: Alignment = DEFAULT_ALIGNMENT
        var chosenCount = 0
        var top: List<Pair<Alignment, Int>> = emptyList()
    }

    /**
     * Žodžių išdėstymas 320 bitų sraute (40 baitų): pirmojo žodžio bitų poslinkis, žingsnis tarp žodžių
     * (30 arba 32 bitai) ir poliškumas (inverted – visi bitai apversti).
     */
    data class Alignment(
        val offset: Int, val stride: Int, val inverted: Boolean,
        val rule: PolarityRule = PolarityRule.CURRENT,
        /** D29* ir D30* imami iš 2 bitų prieš žodį (32 bitų žodžio užpildas), o ne iš ankstesnio žodžio. */
        val starsFromPad: Boolean = false,
        /** Formato hipotezė (H1..H5); BITS – bendras poslinkio/žingsnio išpakavimas. */
        val hyp: Hyp = Hyp.BITS
    ) {
        override fun toString() = if (hyp != Hyp.BITS) "${hyp.name} (${hyp.label}), " +
            (if (rule == PolarityRule.NONE) "be inversijos" else "su D30* inversija") else
            "poslinkis $offset b, žingsnis $stride b, " +
            (if (rule == PolarityRule.NONE) "be inversijos" else "su D30* inversija") +
            (if (starsFromPad) ", D29*/D30* iš užpildo bitų" else "") + (if (inverted) ", bitai apversti" else "")
    }

    /** Formato hipotezės: kaip 40 baitų virsta 10 GPS žodžių (30 bitų, D1 – vyriausias). */
    enum class Hyp(val label: String) {
        BITS("bendras"),
        H1("BE 32 b žodis, GPS žodis = jaunesni 30 bitų (0..29)"),
        H2("BE 32 b žodis, GPS žodis = vyresni 30 bitų (2..31)"),
        H3A("LE 32 b žodis, jaunesni 30 bitų"),
        H3B("LE 32 b žodis, vyresni 30 bitų"),
        H4("300 bitų iš eilės be užpildo"),
        H5("be pariteto: 24 duomenų bitai (vyresni) + 8 kiti bitai (paritetas – jauniausi 6)"),
        H6("BE 32 b žodis: bitai 29–24 = paritetas D25..D30, bitai 23–0 = duomenys D1..D24 (MediaTek)");
    }

    private fun be32(d: ByteArray, i: Int) = ((d[4 * i].toInt() and 0xFF) shl 24) or ((d[4 * i + 1].toInt() and 0xFF) shl 16) or
        ((d[4 * i + 2].toInt() and 0xFF) shl 8) or (d[4 * i + 3].toInt() and 0xFF)
    private fun le32(d: ByteArray, i: Int) = ((d[4 * i + 3].toInt() and 0xFF) shl 24) or ((d[4 * i + 2].toInt() and 0xFF) shl 16) or
        ((d[4 * i + 1].toInt() and 0xFF) shl 8) or (d[4 * i].toInt() and 0xFF)

    /** 10 žodžių po 30 bitų pagal hipotezę. */
    fun hypWords(d: ByteArray, h: Hyp): IntArray = IntArray(10) { i ->
        when (h) {
            Hyp.H1 -> be32(d, i) and 0x3FFFFFFF
            Hyp.H2 -> be32(d, i) ushr 2
            Hyp.H3A -> le32(d, i) and 0x3FFFFFFF
            Hyp.H3B -> le32(d, i) ushr 2
            Hyp.H4 -> bits(d, 30 * i, 30)
            Hyp.H5 -> ((be32(d, i) ushr 8) shl 6) or (be32(d, i) and 0x3F)
            Hyp.H6 -> ((be32(d, i) and 0xFFFFFF) shl 6) or ((be32(d, i) ushr 24) and 0x3F)
            Hyp.BITS -> 0
        }
    }

    /** Numatytasis (Android dokumentacija): 30 bitų žodis 32 bitų žodžio jaunesniuose bituose. */
    val DEFAULT_ALIGNMENT = Alignment(offset = 2, stride = 32, inverted = false)

    /** Šiam failui parinktas išdėstymas (nustatomas readEphemerides()). */
    @Volatile var alignment: Alignment = DEFAULT_ALIGNMENT

    /** Bitai [pos, pos+len) iš baitų srauto (vyriausias bitas pirmas), len ≤ 32. */
    private fun bits(data: ByteArray, pos: Int, len: Int): Int {
        var v = 0L
        for (k in 0 until len) {
            val b = pos + k
            val bit = (data[b ushr 3].toInt() ushr (7 - (b and 7))) and 1
            v = (v shl 1) or bit.toLong()
        }
        return v.toInt()
    }

    /** Ar visi 10 žodžių (po 30 bitų) telpa į pranešimą su šiuo išdėstymu. */
    private fun fits(a: Alignment, nBits: Int) = a.offset + 9 * a.stride + 30 <= nBits

    /** Vienos hipotezės (ir inversijos taisyklės) rezultatai. */
    class HypResult(val align: Alignment) {
        var preamble = 0; var preambleInv = 0; var parityWords = 0; var words = 0; var full = 0
        var towPairs = 0; var towOk = 0
        val fullPerSv = java.util.TreeMap<Int, Int>()
        fun towText() = if (towPairs == 0) "nėra porų" else "$towOk iš $towPairs – ${if (towOk == towPairs) "taip" else "ne"}"
    }

    /** GPS pranešimas formato testui. */
    class NavMsg(val tMs: Long, val sv: Int, val data: ByteArray)

    /**
     * FORMATO TESTAS: H1..H5, kiekviena be inversijos ir su D30* inversija. Skaičiuojama: preambulės 1 žodžio pradžioje,
     * žodžiai su teisingu paritetu, pilni subkadrai (preambulė + 10/10 pariteto), HOW TOW +6 s gretimiems to paties SVID.
     */
    fun formatTest(msgs: List<NavMsg>): List<HypResult> {
        val out = ArrayList<HypResult>()
        for (h in Hyp.values()) {
            if (h == Hyp.BITS) continue
            for (r in arrayOf(PolarityRule.NONE, PolarityRule.CURRENT)) {
                val res = HypResult(Alignment(0, 0, false, r, false, h))
                val last = HashMap<Int, LongArray>()
                for (m in msgs) {
                    if (m.data.size < 40) continue
                    val dec = decodeWords(m.data, res.align) ?: continue
                    val pre = (hypWords(m.data, h)[0] ushr 22) and 0xFF
                    if (pre == 0x8B) res.preamble++ else if (pre == 0x74) res.preambleInv++
                    res.parityWords += dec.parityOk; res.words += 10
                    if (pre != 0x8B || dec.parityOk < 10) continue
                    res.full++
                    res.fullPerSv[m.sv] = (res.fullPerSv[m.sv] ?: 0) + 1
                    val tow = ((dec.words[1] ushr 7) and 0x1FFFF).toLong() * 6
                    last[m.sv]?.let { (t0, tow0) ->
                        if (m.tMs - t0 in 4000..8000) {
                            res.towPairs++
                            if ((tow - tow0 + 604_800) % 604_800 == 6L) res.towOk++
                        }
                    }
                    last[m.sv] = longArrayOf(m.tMs, tow)
                }
                out.add(res)
            }
        }
        return out
    }

    private fun bitString(d: ByteArray, n: Int) = buildString { for (k in 0 until minOf(n, d.size * 8)) append(bits(d, k, 1)) }

    /**
     * IS-GPS-200 pariteto lygtys (20-XIV lentelė): kiekvienam D25..D30 – D29* arba D30* ir šaltinio
     * duomenų bitų d1..d24 numeriai (d_i = D_i ⊕ D30*).
     */
    private val PARITY_EQ: Array<Pair<Int, IntArray>> = arrayOf(
        29 to intArrayOf(1, 2, 3, 5, 6, 10, 11, 12, 13, 14, 17, 18, 20, 23),          // D25
        30 to intArrayOf(2, 3, 4, 6, 7, 11, 12, 13, 14, 15, 18, 19, 21, 24),          // D26
        29 to intArrayOf(1, 3, 4, 5, 7, 8, 12, 13, 14, 15, 16, 19, 20, 22),           // D27
        30 to intArrayOf(2, 4, 5, 6, 8, 9, 13, 14, 15, 16, 17, 20, 21, 23),           // D28
        30 to intArrayOf(1, 3, 5, 6, 7, 9, 10, 14, 15, 16, 17, 18, 21, 22, 24),       // D29
        29 to intArrayOf(3, 5, 6, 8, 9, 10, 11, 13, 15, 19, 22, 23, 24),              // D30
    )

    /**
     * Pariteto patikra pagal IS-GPS-200. w32: bitai 31–30 = ankstesnio žodžio D29*, D30* (kaip perduoti);
     * bitai 29–0 = šis žodis D1..D30 (D1 – vyriausias). Jei D30* = 1, D1..D24 invertuojami (gaunami d1..d24),
     * iš jų ir D29*, D30* apskaičiuojami D25..D30 ir lyginami su perduotais.
     * Grąžina d1..d24 (24 bitai) arba −1, jei paritetas nesutampa.
     */
    fun checkWord(w32: Int, rule: PolarityRule = PolarityRule.CURRENT): Int {
        val d29s = (w32 ushr 31) and 1
        val d30s = (w32 ushr 30) and 1
        var data = (w32 ushr 6) and 0xFFFFFF                    // D1..D24 (D1 = bitas 23)
        if (rule.invert(d29s, d30s)) data = data.inv() and 0xFFFFFF   // pvz. d_i = D_i ⊕ D30*
        var parity = 0
        for ((star, idx) in PARITY_EQ) {
            var b = if (star == 29) d29s else d30s
            for (i in idx) b = b xor ((data ushr (24 - i)) and 1)
            parity = (parity shl 1) or b
        }
        return if (parity == (w32 and 0x3F)) data else -1
    }

    /**
     * Nepriklausomas pariteto skaičiavimas (RTKLIB decode_word Hamingo kaukės, plačiai naudojamas viešas įgyvendinimas),
     * naudojamas tik savitikrai. Grąžina 6 apskaičiuotus pariteto bitus.
     */
    private fun parityReference(w32In: Int): Int {
        val ham = intArrayOf(0xBB1F3480.toInt(), 0x5D8F9A40, 0xAEC7CD00.toInt(), 0x5763E680, 0x6BB1F340, 0x8B7A89C0.toInt())
        var w = w32In
        if (w and 0x40000000 != 0) w = w xor 0x3FFFFFC0
        var par = 0
        for (h in ham) {
            par = par shl 1
            var x = (w and h) ushr 6; var b = 0
            while (x != 0) { b = b xor (x and 1); x = x ushr 1 }
            par = par or b
        }
        return par
    }

    /**
     * Pariteto savitikra: (1) nulinis žodis su D29* = D30* = 0 turi nulinį paritetą;
     * (2) checkWord (IS-GPS-200 20-XIV lentelė) sutampa su nepriklausomu įgyvendinimu 4096 žodžiams
     * (visos D29* ir D30* kombinacijos); (3) vieno bito klaida aptinkama.
     */
    fun paritySelfTest(): Boolean {
        if (checkWord(0) != 0) return false
        val rnd = java.util.Random(20)
        repeat(4096) {
            val d24 = rnd.nextInt(1 shl 24)
            val stars = it and 3
            // perduodamas žodis su teisingu paritetu pagal nepriklausomą įgyvendinimą
            val tx = if (stars and 1 == 1) d24.inv() and 0xFFFFFF else d24
            val noPar = (stars shl 30) or (tx shl 6)
            val w32 = noPar or parityReference(noPar)
            if (checkWord(w32) != d24) return false
            if (checkWord(w32 xor (1 shl (6 + rnd.nextInt(24)))) != -1) return false
        }
        return true
    }

    /** Duomenų bitų inversijos taisyklė (pagal ankstesnio žodžio D29* ir D30*). */
    enum class PolarityRule(val label: String) {
        CURRENT("invertuoti, kai D30* = 1 (IS-GPS-200)"),
        NONE("be inversijos");
        fun invert(@Suppress("UNUSED_PARAMETER") d29s: Int, d30s: Int): Boolean = this == CURRENT && d30s == 1
    }


    /** Išpakuoti 10 žodžių: 24 duomenų bitai kiekvienam ir kiek žodžių praėjo paritetą. */
    class Decoded(val words: IntArray, val parityOk: Int)

    /**
     * 10 žodžių po 30 bitų pagal išdėstymą. Kiekvienam žodžiui k = 1..10 – standartinė GPS L1 C/A pariteto patikra:
     * D29*, D30* imami iš ankstesnio žodžio (1 žodžiui D29* = D30* = 0); jei D30* = 1, 24 duomenų bitai invertuojami.
     */
    fun decodeWords(data: ByteArray, align: Alignment = alignment, rule: PolarityRule = align.rule): Decoded? {
        if (data.size < 40 || (align.hyp == Hyp.BITS && !fits(align, data.size * 8))) return null
        val w30 = if (align.hyp != Hyp.BITS) hypWords(data, align.hyp) else IntArray(10) { i ->
            val v = bits(data, align.offset + i * align.stride, 30)
            if (align.inverted) v.inv() and 0x3FFFFFFF else v
        }
        val words = IntArray(10)
        var ok = 0
        var prev = 0                                                // 1 žodžiui D29* = D30* = 0
        for (i in 0 until 10) {
            if (align.starsFromPad) {
                val pos = align.offset + i * align.stride - 2
                prev = if (pos >= 0) bits(data, pos, 2).let { if (align.inverted) it.inv() and 3 else it } else 0
            }
            var d = checkWord((prev shl 30) or w30[i], rule)
            // 1 žodžio D29*, D30* yra ankstesnio subkadro paskutiniai bitai – nežinomi, todėl bandomi visi 4 variantai
            if (d < 0 && i == 0 && !align.starsFromPad) for (st in intArrayOf(3, 1, 2)) {
                d = checkWord((st shl 30) or w30[i], rule); if (d >= 0) break
            }
            if (d >= 0) ok++ else {
                d = (w30[i] ushr 6) and 0xFFFFFF
                if (rule.invert((prev ushr 1) and 1, prev and 1)) d = d.inv() and 0xFFFFFF
            }
            words[i] = d
            prev = w30[i] and 0x3
        }
        return Decoded(words, ok)
    }

    /**
     * Subkadras priimamas tik jei: rasta preambulė 0x8B, visi 10 žodžių praėjo paritetą,
     * subkadro ID 1–5 ir HOW TOW × 6 yra [0, 604800) s.
     */
    fun subframeFromBytes(data: ByteArray, stats: UnpackStats? = null, align: Alignment = alignment): Subframe? {
        val dec = decodeWords(data, align) ?: return null
        stats?.let { it.unpacked++; it.lastParityOk = dec.parityOk; it.parityOk += dec.parityOk; it.parityBad += 10 - dec.parityOk }
        val words = dec.words
        if (((words[0] ushr 16) and 0xFF) != 0x8B) return null
        stats?.let { it.preamble++ }
        if (dec.parityOk < 10) { stats?.let { it.rejParity++ }; return null }
        val id = (words[1] ushr 2) and 0x7
        if (id !in 1..5) return null
        val tow = ((words[1] ushr 7) and 0x1FFFF).toLong() * 6
        if (tow < 0 || tow >= 604_800L) { stats?.let { it.rejTow++ }; return null }
        return Subframe(id, words, dec.parityOk)
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
    // ---------------- Diagnostika (gnss_solver_diag.txt) ----------------
    class Diag {
        // navigacija
        var navRows = 0; var navGps = 0; var navGpsL1ca = 0; var navBadSubframe = 0
        val paritySelfTestOk = paritySelfTest()
        val unpack = UnpackStats()
        val subframesPerSv = java.util.TreeMap<Int, IntArray>()       // priimti [sf1, sf2, sf3, sf4, sf5]
        val parityPerSv = java.util.TreeMap<Int, IntArray>()          // [žodžių su teisingu paritetu, iš viso]
        val ephPerSv = java.util.TreeMap<Int, Int>()
        val ephFailPerSv = java.util.TreeMap<Int, Int>()
        var firstSfInfo: String? = null
        var formatResults: List<HypResult> = emptyList()
        var formatBest: HypResult? = null
        val firstMsgs = ArrayList<String>()
        val towLast = HashMap<Int, LongArray>()                           // SVID -> [laikas_ms, TOW]
        var towPairs = 0; var towOk = 0
        val towBad = ArrayList<String>()
        var firstWnInfo: String? = null
        // matavimai
        var rawRows = 0; var rawNotGps = 0; var rawGpsNotL1 = 0; var rawGpsL1 = 0; var rawParseErr = 0
        var rawNoState = 0; var rawTimeMismatch = 0; var rawDuplicate = 0; var rawAccepted = 0
        var firstRawInfo: String? = null
        // sprendimas
        var obsNoEph = 0; var obsEphOld = 0; var obsUsable = 0
        var epochs = 0; var epochs4 = 0
        val noEphSv = java.util.TreeSet<Int>()
        val oldEphSv = java.util.TreeMap<Int, Double>()             // SVID -> mažiausias amžius (s)
        val firstEpochs = ArrayList<String>()
        val ageFirst = java.util.TreeMap<Int, String>()                  // SVID -> pirmasis amžiaus skaičiavimas
        var firstNavTow: String? = null
        var firstRawTow: String? = null

        fun text(solutions: Int): String = buildString {
            appendLine("=== GPS SKAIČIUOKLĖS DIAGNOSTIKA ===")
            appendLine()
            appendLine("--- Navigacijos pranešimai (gnss_nav.csv) ---")
            appendLine("Pariteto savitikra (IS-GPS-200 20-XIV): ${if (paritySelfTestOk) "PASS" else "FAIL"}")
            appendLine("Eilučių iš viso: $navRows")
            appendLine("GPS eilučių: $navGps, iš jų GPS L1 C/A (tipas 257): $navGpsL1ca")
            appendLine("GPS pranešimų išpakuota į 10 žodžių: ${unpack.unpacked}")
            appendLine("Rasta preambulių 0x8B (1 žodžio pradžioje): ${unpack.preamble}")
            appendLine("Žodžių su teisingu paritetu: ${unpack.parityOk}, su klaidingu: ${unpack.parityBad}")
            appendLine()
            appendLine("FORMATO TESTAS (GPS L1 C/A, tipas 257; preambulės / žodžiai su paritetu / pilni subkadrai / TOW +6 s):")
            if (formatResults.isEmpty()) appendLine("  (nėra GPS pranešimų)")
            for (r in formatResults) appendLine("  ${r.align}: preambulių ${r.preamble} (invertuotų ${r.preambleInv}); " +
                "pariteto ${r.parityWords}/${r.words}; pilnų subkadrų ${r.full}; TOW +6 s: ${r.towText()}")
            val fb = formatBest
            if (fb != null && fb.full > 0) {
                appendLine("TEISINGAS IŠDĖSTYMAS: ${fb.align.hyp.name}, ${fb.align}, pilnų subkadrų ${fb.full}")
                appendLine("Pilni subkadrai pagal SVID: " + fb.fullPerSv.entries.joinToString(" ") { "G%02d=%d".format(Locale.US, it.key, it.value) })
            } else {
                appendLine("TEISINGAS IŠDĖSTYMAS: nežinomas – nė viena hipotezė H1..H6 nedavė pilno subkadro; efemeridės neiššifruojamos")
                appendLine("Pirmieji 3 GPS pranešimai:")
                firstMsgs.forEach { appendLine(it) }
            }
            appendLine()
            appendLine("HOW TOW +6 s patikra (gretimi to paties SVID subkadrai, 4–8 s tarpas): " +
                (if (towPairs == 0) "nėra porų – ne" else "$towOk iš $towPairs – ${if (towOk == towPairs) "taip" else "ne"}"))
            towBad.take(5).forEach { appendLine("  neatitinka: $it") }
            appendLine("Iššifruota subkadrų 1/2/3 pagal SVID:")
            if (subframesPerSv.isEmpty()) appendLine("  (nėra)")
            for ((sv, c) in subframesPerSv) appendLine("  G%02d: %d/%d/%d".format(Locale.US, sv, c[0], c[1], c[2]))
            appendLine("Iš viso 1/2/3: ${subframesPerSv.values.sumOf { it[0] }}/${subframesPerSv.values.sumOf { it[1] }}/${subframesPerSv.values.sumOf { it[2] }}")
            appendLine("Neiššifruotų / atmestų subkadrų iš viso: $navBadSubframe")
            appendLine("Atmesta dėl pariteto (ne visi 10 žodžių teisingi): ${unpack.rejParity}")
            appendLine("Atmesta dėl TOW ribų (HOW TOW × 6 ≥ 604800 s): ${unpack.rejTow}")
            appendLine("Žodžiai su teisingu paritetu pagal SVID:")
            if (parityPerSv.isEmpty()) appendLine("  (nėra)")
            for ((sv, a) in parityPerSv) appendLine("  G%02d: %d iš %d".format(Locale.US, sv, a[0], a[1]))
            appendLine("Iš viso: ${parityPerSv.values.sumOf { it[0] }} iš ${parityPerSv.values.sumOf { it[1] }}")
            appendLine("Pirmasis priimtas TOW: ${firstNavTow ?: "nėra"}")
            appendLine("Pirmasis iššifruotas subkadras: ${firstSfInfo ?: "nėra"}")
            appendLine("Pirmasis navigacijos TOW (HOW TOW × 6): ${firstNavTow ?: "nėra"}")
            appendLine("Pirmoji savaitė iš 1 subkadro: ${firstWnInfo ?: "nėra"}")
            appendLine("Priimti subkadrai ir efemeridės pagal SVID (sf1/sf2/sf3/sf4/sf5; 1–3 visi; efemeridžių; nepavyko):")
            if (subframesPerSv.isEmpty()) appendLine("  (nėra GPS L1 C/A subkadrų)")
            for ((sv, c) in subframesPerSv) {
                val all = c[0] > 0 && c[1] > 0 && c[2] > 0
                appendLine("  G%02d: %d/%d/%d/%d/%d; %s; %d; %d".format(Locale.US, sv, c[0], c[1], c[2], c[3], c[4],
                    if (all) "taip" else "ne", ephPerSv[sv] ?: 0, ephFailPerSv[sv] ?: 0))
            }
            appendLine("Efemeridžių iš viso: ${ephPerSv.values.sum()} (${ephPerSv.size} SVID)")
            appendLine()
            appendLine("--- Matavimai (gnss_raw.csv) ---")
            appendLine("Pirmoji eilutė: ${firstRawInfo ?: "nėra"}")
            appendLine("Pirmasis matavimų TOW ((time_nanos − full_bias_nanos − bias_nanos) mod savaitė): ${firstRawTow ?: "nėra"}")
            appendLine("Eilučių iš viso: $rawRows")
            appendLine("GPS L1 C/A eilučių: $rawGpsL1")
            appendLine("Atmesta:")
            appendLine("  ne GPS konsteliacija: $rawNotGps")
            appendLine("  GPS, bet ne L1: $rawGpsNotL1")
            appendLine("  netinkamas eilutės formatas: $rawParseErr")
            appendLine("  nėra state bito STATE_SUBFRAME_SYNC (4): $rawNoState")
            appendLine("  laiko neatitikimas (pseudoatstumas už 15 000–35 000 km): $rawTimeMismatch")
            appendLine("  pasikartojantis SVID epochoje: $rawDuplicate")
            appendLine("Priimta pseudoatstumų: $rawAccepted")
            appendLine()
            appendLine("--- Sprendimas ---")
            appendLine("Stebėjimų be efemeridės (SVID nėra): $obsNoEph${if (noEphSv.isEmpty()) "" else " – " + noEphSv.joinToString(" ") { "G%02d".format(Locale.US, it) }}")
            appendLine("Stebėjimų su per sena efemeride (> 2 val.): $obsEphOld${if (oldEphSv.isEmpty()) "" else " – " + oldEphSv.entries.joinToString(" ") { "G%02d (%.0f s)".format(Locale.US, it.key, it.value) }}")
            appendLine("Tinkamų stebėjimų: $obsUsable")
            appendLine("Efemeridės amžius pagal SVID (pirmasis stebėjimas, |t_tx − toe| ≤ 7200 s):")
            if (ageFirst.isEmpty()) appendLine("  (nėra)")
            for ((sv, a) in ageFirst) appendLine("  G%02d: %s".format(Locale.US, sv, a))
            appendLine("Epochų iš viso: $epochs, su ≥4 tinkamais palydovais: $epochs4")
            appendLine("Sprendinių įrašyta: $solutions")
            appendLine("Pirmosios 5 epochos:")
            if (firstEpochs.isEmpty()) appendLine("  (nėra)")
            firstEpochs.forEach { appendLine("  $it") }
        }
    }

    class Solution(val lat: Double, val lon: Double, val h: Double, val sats: Int, val hacc: Double, val ecef: DoubleArray)

    /** Efemeridės amžius (s): t_tx − toe GPS savaitės sekundėmis, su savaitės persivertimu (±302400 s). */
    fun ephAge(tTxTow: Double, eph: Ephemeris): Double = wrapWeek(tTxTow - eph.toe)

    fun solve(epoch: Epoch, ephs: Map<Int, List<Ephemeris>>, diag: Diag? = null): Solution? {
        data class Row(val pos: DoubleArray, val pr: Double)
        val rows = ArrayList<Row>()
        val epInfo = if (diag != null && diag.firstEpochs.size < 5) ArrayList<String>() else null
        for (o in epoch.obs) {
            val list = ephs[o.svid]
            // Vienas laiko pagrindas: palydovo siuntimo laikas GPS savaitės sekundėmis, lyginamas su toe
            val tTxTow = epoch.tRxTow - o.pr / C
            val eph = list?.filter { abs(ephAge(tTxTow, it)) <= MAX_EPH_AGE_S }
                ?.minByOrNull { abs(ephAge(tTxTow, it)) }
            if (diag != null && !list.isNullOrEmpty()) {
                val best = list.minByOrNull { abs(ephAge(tTxTow, it)) }!!
                diag.ageFirst.putIfAbsent(o.svid, "t_tx %.3f s, toe %.0f s, amžius %+.0f s%s".format(Locale.US,
                    tTxTow, best.toe, ephAge(tTxTow, best), if (eph == null) " (per sena)" else ""))
            }
            if (diag != null) {
                val tag = "G%02d".format(Locale.US, o.svid)
                when {
                    list.isNullOrEmpty() -> { diag.obsNoEph++; diag.noEphSv.add(o.svid); epInfo?.add("$tag(nėra eph)") }
                    eph == null -> {
                        diag.obsEphOld++
                        val age = list.minOf { abs(ephAge(tTxTow, it)) }
                        diag.oldEphSv[o.svid] = minOf(diag.oldEphSv[o.svid] ?: Double.MAX_VALUE, age)
                        epInfo?.add("$tag(sena eph %.0f s)".format(Locale.US, age))
                    }
                    else -> { diag.obsUsable++; epInfo?.add("${tag}✓") }
                }
            }
            if (eph == null) continue
            var tTx = epoch.tRxTow - o.pr / C
            var st = satState(eph, tTx)
            tTx -= st[3]
            st = satState(eph, tTx)
            rows.add(Row(st, o.pr + C * st[3]))
        }
        if (diag != null) {
            diag.epochs++
            if (rows.size >= 4) diag.epochs4++
            if (epInfo != null) diag.firstEpochs.add("%d (savaitė %d, TOW %.3f s): tinkami %d – %s".format(Locale.US,
                epoch.timeMs, epoch.week, epoch.tRxTow, rows.size, epInfo.joinToString(" ")))
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
    fun readEphemerides(nav: File, refWeek: Int, diag: Diag? = null): Map<Int, List<Ephemeris>> {
        val out = HashMap<Int, MutableList<Ephemeris>>()
        if (!nav.exists()) return out
        val last = HashMap<Int, Array<Subframe?>>()
        // 1 praėjimas: žodžių išdėstymo paieška per visus GPS L1 C/A pranešimus
        val msgs = ArrayList<NavMsg>()
        nav.bufferedReader().use { br ->
            val head = br.readLine()?.split(',') ?: return out
            val iC = head.indexOf("konstelacija"); val iT = head.indexOf("tipas"); val iH = head.indexOf("duomenys_hex")
            val iS = head.indexOf("svid")
            if (minOf(iC, iT, iH, iS) < 0) return out
            br.forEachLine { line ->
                val p = line.split(',')
                diag?.let { if (p.size > iH) { it.navRows++; if (p[iC] == "GPS") it.navGps++ } }
                if (p.size > iH && p[iC] == "GPS" && p[iT].toIntOrNull() == NAV_TYPE_GPS_L1CA)
                    msgs.add(NavMsg(p[0].toLongOrNull() ?: 0L, p[iS].toIntOrNull() ?: 0, hexToBytes(p[iH])))
            }
        }
        val results = formatTest(msgs)
        val best = results.sortedWith(compareByDescending<HypResult> { it.full }.thenByDescending { it.preamble }
            .thenByDescending { it.parityWords }.thenBy { if (it.align.rule == PolarityRule.CURRENT) 0 else 1 }).firstOrNull()
        diag?.let { d ->
            d.formatResults = results
            d.formatBest = best
            for (m in msgs.take(3)) {
                d.firstMsgs.add("  G%02d laikas_ms=%d hex=%s".format(Locale.US, m.sv, m.tMs, m.data.joinToString("") { "%02x".format(it) }))
                d.firstMsgs.add("    300 bitų: ${bitString(m.data, 300)}")
            }
        }
        if (best == null || best.full == 0) {
            alignment = DEFAULT_ALIGNMENT
            diag?.unpack?.let { it.chosen = alignment; it.chosenCount = 0 }
            return out                                                  // formatas nežinomas – stabdoma
        }
        alignment = best.align
        diag?.unpack?.let { u -> u.chosen = alignment; u.chosenCount = best.full }
        // 2 praėjimas: išpakavimas tik su parinktu išdėstymu ir taisykle
        nav.bufferedReader().use { br ->
            val head = br.readLine()?.split(',') ?: return out
            val iConst = head.indexOf("konstelacija"); val iSv = head.indexOf("svid")
            val iType = head.indexOf("tipas"); val iHex = head.indexOf("duomenys_hex")
            if (minOf(iConst, iSv, iType, iHex) < 0) return out
            br.forEachLine { line ->
                val p = line.split(',')
                if (p.size <= iHex) return@forEachLine
                if (p[iConst] != "GPS" || p[iType].toIntOrNull() != NAV_TYPE_GPS_L1CA) return@forEachLine
                diag?.let { it.navGpsL1ca++ }
                val sv = p[iSv].toIntOrNull() ?: return@forEachLine
                val bytes = hexToBytes(p[iHex])
                diag?.unpack?.lastParityOk = -1
                val sf = subframeFromBytes(bytes, diag?.unpack)
                diag?.let { d ->
                    val ok = d.unpack.lastParityOk
                    if (ok >= 0) {
                        val a = d.parityPerSv.getOrPut(sv) { IntArray(2) }
                        a[0] += ok; a[1] += 10
                    }
                }
                if (sf == null) { diag?.let { it.navBadSubframe++ }; return@forEachLine }
                if (diag != null) {
                    diag.subframesPerSv.getOrPut(sv) { IntArray(5) }[sf.id - 1]++
                    val tms = p[0].toLongOrNull()
                    val towNow = ((sf.words[1] ushr 7) and 0x1FFFF).toLong() * 6
                    if (tms != null) {
                        diag.towLast[sv]?.let { (t0, tow0) ->
                            if (tms - t0 in 4000..8000) {
                                diag.towPairs++
                                if ((towNow - tow0 + 604_800) % 604_800 == 6L) diag.towOk++
                                else if (diag.towBad.size < 5) diag.towBad.add("G%02d: TOW %d -> %d (%d ms)".format(Locale.US, sv, tow0, towNow, tms - t0))
                            }
                        }
                        diag.towLast[sv] = longArrayOf(tms, towNow)
                    }
                    if (diag.firstSfInfo == null) {
                        val tow = ((sf.words[1] ushr 7) and 0x1FFFF) * 6       // HOW TOW (kito subkadro pradžia)
                        diag.firstSfInfo = "laikas_ms=${p[0]}, G%02d, subkadras %d, TOW %d s (%s)".format(Locale.US, sv, sf.id, tow, dhms(tow.toDouble()))
                        // HOW TOW – kito subkadro pradžia; šio subkadro pradžia = TOW − 6 s
                        diag.firstNavTow = "%d s (subkadro pradžia %d s, %s)".format(Locale.US, tow, tow - 6, dhms((tow - 6).toDouble().coerceAtLeast(0.0)))
                    }
                    if (diag.firstWnInfo == null && sf.id == 1) {
                        val wn10 = (sf.words[2] ushr 14) and 0x3FF
                        val full = if (refWeek > 0) refWeek + (((wn10 - refWeek) % 1024 + 1536) % 1024 - 512) else wn10
                        diag.firstWnInfo = "WN(10 bitų)=$wn10 -> pilna savaitė $full (iš gnss_raw.csv: $refWeek)"
                    }
                }
                if (sf.id > 3) return@forEachLine
                val slot = last.getOrPut(sv) { arrayOfNulls(3) }
                slot[sf.id - 1] = sf
                val s1 = slot[0]; val s2 = slot[1]; val s3 = slot[2]
                if (s1 != null && s2 != null && s3 != null) {
                    val eph = decodeEphemeris(sv, s1, s2, s3, refWeek)
                    if (eph == null) {
                        diag?.let { it.ephFailPerSv[sv] = (it.ephFailPerSv[sv] ?: 0) + 1 }
                        return@forEachLine
                    }
                    val list = out.getOrPut(sv) { ArrayList() }
                    if (list.none { it.iode == eph.iode && it.toe == eph.toe }) diag?.let { it.ephPerSv[sv] = (it.ephPerSv[sv] ?: 0) + 1 }
                    // Ta pati efemeridė (IODE + toe) – pakeičiama naujausia
                    list.removeAll { it.iode == eph.iode && it.toe == eph.toe }
                    list.add(eph)
                }
            }
        }
        return out
    }

    /** gnss_raw.csv -> epochos (grupuojama pagal time_nanos), tik GPS L1 su subkadro sinchronizacija. */
    fun readEpochs(raw: File, diag: Diag? = null, consumer: (Epoch) -> Unit) {
        if (!raw.exists()) return
        raw.bufferedReader().use { br -> readEpochs(br, diag, consumer) }
    }

    fun readEpochs(br: BufferedReader, diag: Diag? = null, consumer: (Epoch) -> Unit) {
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
            diag?.let { it.rawRows++ }
            if (p.size <= maxOf(iSvt, iSt, iTn)) { diag?.let { it.rawParseErr++ }; return@forEachLine }
            if (diag != null && diag.firstRawInfo == null) {
                val tn0 = p[iTn].toLongOrNull(); val fb0 = p[iFb].toLongOrNull(); val b0 = p[iB].toDoubleOrNull() ?: 0.0
                diag.firstRawInfo = if (tn0 != null && fb0 != null) {
                    val g = tn0 - fb0
                    val wk = Math.floorDiv(g, 604_800_000_000_000L)
                    val tow = (g - wk * 604_800_000_000_000L - b0) * 1e-9
                    diag.firstRawTow = "%.3f s (%s)".format(Locale.US, tow, dhms(tow))
                    "laikas_ms=${p[iMs]}, time_nanos=$tn0, full_bias_nanos=$fb0, bias_nanos=$b0 -> savaitė $wk, TOW %.3f s (%s)".format(Locale.US, tow, dhms(tow))
                } else "laikas_ms=${p[iMs]}, time_nanos=${p[iTn]}, full_bias_nanos=${p[iFb]} (nėra laiko)"
            }
            if (p[iC] != "GPS") { diag?.let { it.rawNotGps++ }; return@forEachLine }
            if (p[iSig] != "L1") { diag?.let { it.rawGpsNotL1++ }; return@forEachLine }
            diag?.let { it.rawGpsL1++ }
            val tn = p[iTn].toLongOrNull()
            val fb = p[iFb].toLongOrNull()
            val b = p[iB].toDoubleOrNull() ?: 0.0
            val off = if (iOff >= 0) p[iOff].toDoubleOrNull() ?: 0.0 else 0.0
            val st = p[iSt].toIntOrNull()
            val svt = p[iSvt].toLongOrNull()
            val sv = p[iSv].toIntOrNull()
            if (tn == null || fb == null || st == null || svt == null || sv == null) { diag?.let { it.rawParseErr++ }; return@forEachLine }
            if (tn != curTn) { flush(); curTn = tn }
            val r = pseudorange(tn, fb, b, off, st, svt)
            if (r == null) {
                diag?.let { if (st and STATE_SUBFRAME_SYNC == 0) it.rawNoState++ else it.rawTimeMismatch++ }
                return@forEachLine
            }
            curMs = p[iMs].toLongOrNull() ?: curMs
            curWeek = r.second
            curTow = r.third - off * 1e-9               // epochos imtuvo laikas be signalo poslinkio
            if (obs.none { it.svid == sv }) { obs.add(Obs(sv, r.first)); diag?.let { it.rawAccepted++ } }
            else diag?.let { it.rawDuplicate++ }
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
    /** sp3: SP3 failo paieška „SP3 TESTAS“ blokui; skaičiuoklė SP3 dar nenaudoja. */
    fun solveFiles(raw: File, nav: File, out: File, sp3: (() -> Sp3Locator.Search)? = null): List<Pair<Long, Solution>> {
        val diag = Diag()
        val ephs = readEphemerides(nav, firstWeek(raw), diag)
        val sols = ArrayList<Pair<Long, Solution>>()
        out.bufferedWriter().use { w ->
            w.write(SOLVER_HEADER)
            readEpochs(raw, diag) { ep ->
                val s = solve(ep, ephs, diag) ?: return@readEpochs
                sols.add(ep.timeMs to s)
                w.write(String.format(Locale.US, "%d,%.8f,%.8f,%.2f,%d,%.2f\n", ep.timeMs, s.lat, s.lon, s.h, s.sats, s.hacc))
            }
        }
        lastSp3Note = null
        val navOther = try { NavFile.read(nav) } catch (e: Exception) { null }
        val galBgd = navOther?.gal?.ephs?.entries?.associate { 1000 + it.key to it.value.tgd } ?: emptyMap()
        lastDiagText = diag.text(sols.size) + (navOther?.let { "\n--- GALILEO IR GLONASS NAVIGACIJOS PRANEŠIMAI ---\n" + NavFile.text(it) + "\n" } ?: "") + (sp3?.let { locate ->
            var location = "nėra"
            var searched = emptyList<String>()
            try {
                val search = locate()
                val rec = try { Sp3Solver.recordingRange(raw) } catch (e: Exception) { null }
                // Parinkimas pagal laiką: failas, kurio intervalas apima įrašą (keli – naujausias)
                val (used, _) = Sp3Locator.chooseByRange(search.all, rec) { f -> f.open().use { Sp3Reader.headerRange(it) } }
                searched = search.lines() + listOf("Įrašas: " + (rec?.let { "${Sp3Reader.utc(it.first)}–${Sp3Reader.utc(it.second)} UTC" } ?: "nėra epochų") +
                    "; parinkta pagal laiko intervalą: " + (used?.name ?: "nėra"))
                if (used == null) throw java.io.FileNotFoundException(Sp3Locator.NOT_FOUND)
                location = used.location
                val r = used.open().use { Sp3Reader.read(it) }
                val note = Sp3Solver.staleNote(r, rec)
                lastSp3Note = note
                val fixFile = File(raw.parentFile, SessionFiles.FIX)
                val known = knownRef
                refIsKnown = known != null
                val ref = known ?: try { medianFix(fixFile) } catch (e: Exception) { null }
                val refLine = "\n--- SP3 ATSKAITOS TAŠKAS ---\nNaudojamas: ${refLabel()}" +
                    (ref?.let { " (%.7f, %.7f)".format(Locale.US, it.first, it.second) } ?: " – nėra") +
                    "; fikso tikslumo (h_tikslumas_m) mediana: " +
                    (try { medianFixAccuracy(fixFile) } catch (e: Exception) { null }?.let { "%.1f m".format(Locale.US, it) } ?: "nėra") + "\n"
                // SP3 sprendinys – tik testui, atskiras failas (pagrindinė išvestis nekeičiama)
                val sp3Sol = try {
                    val v1 = Sp3Solver.solveFile(raw, r, File(out.parentFile, Sp3Solver.OUT_FILE))
                    val v2 = try { Sp3Solver.solveFile(raw, r, File(out.parentFile, Sp3Solver.OUT_FILE_V2), corrected = true) } catch (e: Exception) { null }
                    refLine + Sp3Solver.diagBlock(v1, ref = ref, note = note) + Sp3Solver.v2Block(v1, v2, fixFile, ref) +
                        Sp3Solver.sagnacBlock(raw, r, File(out.parentFile, Sp3Solver.OUT_FILE_V3), ref) +
                        (try {
                            val fixH = medianFixHeight(fixFile)
                            Sp3V4.atmosphereBlock(raw, r, ref, fixH) + Sp3V4.v4Block(raw, r, File(out.parentFile, Sp3V4.OUT_FILE_V4), ref, fixH) +
                                (try { Sp3V5.v5Block(raw, r, File(out.parentFile, Sp3V5.OUT_FILE_V5), ref, fixH,
                                    ephs.mapValues { e -> e.value.maxByOrNull { it.toeAbs }!!.tgd } + galBgd) }
                                 catch (e: Exception) { "\n--- SP3 TESTAS v5 ---\nNeatlikta: ${e.message}\n" }) +
                                (try { Sp3V6.v6Block(raw, r, File(out.parentFile, Sp3V6.OUT_FILE_V6), ref, fixH) }
                                 catch (e: Exception) { "\n--- SP3 TESTAS v6 ---\nNeatlikta: ${e.message}\n" }) +
                                (try { Dgnss.block(raw, File(raw.parentFile, RtcmRecorder.FILE), r, File(out.parentFile, Dgnss.OUT_FILE), ref, fixH) }
                                 catch (e: Exception) { "\n--- DGNSS (NTRIP / RTCM 3) ---\nNeatlikta: ${e.message}\n" })
                        } catch (e: Exception) { "\n--- SP3 TESTAS v4 ---\nNeatlikta: ${e.message}\n" })
                } catch (e: Exception) { Sp3Solver.diagBlock(null, e.message) }
                Sp3Reader.diagBlock(location, r, searched = searched) + sp3Sol
            }
            catch (e: Exception) { Sp3Reader.diagBlock(location, null, e.message, searched) + Sp3Solver.diagBlock(null, e.message) }
        } ?: "")
        File(out.parentFile, DIAG_FILE).writeText(lastDiagText)
        return sols
    }

    const val DIAG_FILE = "gnss_solver_diag.txt"
    /** Paskutinės solveFiles() diagnostikos tekstas. */
    @Volatile var lastDiagText: String = ""

    /** Savaitės laikas -> „d hh:mm:ss“. */
    fun dhms(tow: Double): String {
        val t = tow.toLong()
        val d = t / 86400; val h = (t % 86400) / 3600; val m = (t % 3600) / 60; val sec = t % 60
        val days = arrayOf("sk", "pr", "an", "tr", "kt", "pn", "št")
        return "%s %02d:%02d:%02d".format(Locale.US, days.getOrElse(d.toInt()) { "?" }, h, m, sec)
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
        val d = diffsToFix(sols, fix)
        if (d.isEmpty()) return null
        return if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
    }

    /** Procentilis p (0..1) didėjančiai surūšiuotame sąraše (artimiausio rango metodas). */
    fun percentile(sorted: List<Double>, p: Double): Double =
        sorted[(kotlin.math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]

    /** Fiksų platumos ir ilgumos (gnss_fix.csv) medianos – SP3 testų atskaitos taškas; null – fiksų nėra. */
    fun medianFix(fix: File): Pair<Double, Double>? {
        if (!fix.exists()) return null
        val la = ArrayList<Double>(); val lo = ArrayList<Double>()
        fix.bufferedReader().use { br ->
            val head = br.readLine()?.split(',') ?: return null
            val iLa = head.indexOf("platuma"); val iLo = head.indexOf("ilguma")
            if (iLa < 0 || iLo < 0) return null
            br.forEachLine { l ->
                val p = l.split(',')
                val a = p.getOrNull(iLa)?.toDoubleOrNull(); val b = p.getOrNull(iLo)?.toDoubleOrNull()
                if (a != null && b != null && !(a == 0.0 && b == 0.0)) { la.add(a); lo.add(b) }
            }
        }
        if (la.isEmpty()) return null
        la.sort(); lo.sort()
        return percentile(la, 0.5) to percentile(lo, 0.5)
    }

    /** Vartotojo „Žinomos koordinatės“ (platuma, ilguma) – SP3 testų atskaitos taškas; null – gnss_fix.csv mediana. */
    @Volatile var knownRef: Pair<Double, Double>? = null
    @Volatile private var refIsKnown = false
    fun refLabel() = if (refIsKnown) "žinomos koordinatės (vartotojo)" else "gnss_fix.csv mediana"

    /** Fiksų horizontalaus tikslumo (h_tikslumas_m) mediana. */
    fun medianFixAccuracy(fix: File): Double? {
        if (!fix.exists()) return null
        val a = ArrayList<Double>()
        fix.bufferedReader().use { br ->
            val i = br.readLine()?.split(',')?.indexOf("h_tikslumas_m") ?: return null
            if (i < 0) return null
            br.forEachLine { l -> l.split(',').getOrNull(i)?.toDoubleOrNull()?.let { a.add(it) } }
        }
        if (a.isEmpty()) return null
        a.sort(); return percentile(a, 0.5)
    }

    /** Paskutinio solveFiles() SP3 tinkamumo pastaba ataskaitai („SP3 failas netinka: …“) arba null. */
    @Volatile var lastSp3Note: String? = null

    /** Fiksų elipsoidinio aukščio (gnss_fix.csv, aukstis_elips_m) mediana. */
    fun medianFixHeight(fix: File): Double? {
        if (!fix.exists()) return null
        val hs = ArrayList<Double>()
        fix.bufferedReader().use { br ->
            val iH = br.readLine()?.split(',')?.indexOf("aukstis_elips_m") ?: return null
            if (iH < 0) return null
            br.forEachLine { l -> l.split(',').getOrNull(iH)?.toDoubleOrNull()?.let { hs.add(it) } }
        }
        if (hs.isEmpty()) return null
        hs.sort()
        return percentile(hs, 0.5)
    }

    /** Sprendinių ir fiksų horizontalūs skirtumai (artimiausias laikas ≤ 1,5 s), didėjančia tvarka. */
    fun diffsToFix(sols: List<Pair<Long, Solution>>, fix: File): List<Double> {
        if (!fix.exists() || sols.isEmpty()) return emptyList()
        val fixes = ArrayList<Triple<Long, Double, Double>>()
        fix.bufferedReader().use { br ->
            val head = br.readLine()?.split(',') ?: return emptyList()
            val iT = head.indexOf("laikas_ms"); val iLa = head.indexOf("platuma"); val iLo = head.indexOf("ilguma")
            br.forEachLine { l ->
                val p = l.split(',')
                val t = p.getOrNull(iT)?.toLongOrNull() ?: return@forEachLine
                val la = p.getOrNull(iLa)?.toDoubleOrNull() ?: return@forEachLine
                val lo = p.getOrNull(iLo)?.toDoubleOrNull() ?: return@forEachLine
                fixes.add(Triple(t, la, lo))
            }
        }
        if (fixes.isEmpty()) return emptyList()
        fixes.sortBy { it.first }
        val d = ArrayList<Double>()
        for ((t, s) in sols) {
            val f = fixes.minByOrNull { abs(it.first - t) } ?: continue
            if (abs(f.first - t) <= 1500) d.add(horizDist(s.lat, s.lon, f.second, f.third))
        }
        d.sort()
        return d
    }
}
