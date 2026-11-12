package lt.example.gnss

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS L1 C/A efemeridės iš gyvų navigacijos pranešimų (GnssNavigationMessage, tipas 0x0101).
 * Išdėstymas parenkamas automatiškai (H1..H6 × inversija); MediaTek MT6686 – H6 be inversijos.
 * Naudojama 3D vaizde GPS palydovams, kai SP3 failas dabarties neapima.
 */
object BroadcastEph {
    private val slots = HashMap<Int, Array<PositionSolver.Subframe?>>()
    private val ephs = HashMap<Int, PositionSolver.Ephemeris>()
    private val candidates: List<PositionSolver.Alignment> =
        listOf(PositionSolver.Alignment(0, 0, false, PositionSolver.PolarityRule.NONE, false, PositionSolver.Hyp.H6)) +
            PositionSolver.Hyp.values().filter { it != PositionSolver.Hyp.BITS && it != PositionSolver.Hyp.H6 }.flatMap { h ->
                listOf(PositionSolver.PolarityRule.NONE, PositionSolver.PolarityRule.CURRENT).map { PositionSolver.Alignment(0, 0, false, it, false, h) }
            } + PositionSolver.Alignment(0, 0, false, PositionSolver.PolarityRule.CURRENT, false, PositionSolver.Hyp.H6)
    @Volatile var align: PositionSolver.Alignment? = null
        private set
    @Volatile var subframes = 0
        private set
    @Volatile var rejected = 0
        private set

    // ---- Išsaugojimas: paskutiniai efemeridėms reikalingi neapdoroti pranešimai (GPS 1–3 subkadrai, Galileo 1–5 žodžiai,
    // GLONASS 1–4 eilutės) su gavimo laiku; po paleidimo iš naujo jie perskaitomi per tuos pačius iššifruotojus.
    const val FILE = "broadcast_eph.csv"
    private const val KEEP_MS = 4 * 3_600_000L
    private class RawMsg(val tMs: Long, val kind: Char, val svid: Int, val data: ByteArray)
    private val raw = HashMap<String, RawMsg>()
    private var dirty = false
    private var savedAt = 0L
    private var loadedDir: java.io.File? = null
    @Volatile var loadedCount = 0
        private set

    private fun keep(kind: Char, svid: Int, no: Int, tMs: Long, data: ByteArray) {
        raw["$kind$svid:$no"] = RawMsg(tMs, kind, svid, data.copyOf()); dirty = true
    }

    private fun gpsWeekAt(ms: Long) = Math.floorDiv((ms / 1000.0 - 315_964_800.0 + 18.0).toLong(), 604_800L).toInt()

    /** Įkelia išsaugotas efemerides (vieną kartą katalogui); senesni nei 4 val. pranešimai praleidžiami. */
    @Synchronized
    fun load(dir: java.io.File, nowMs: Long = System.currentTimeMillis()) {
        if (loadedDir == dir) return
        loadedDir = dir
        val f = java.io.File(dir, FILE)
        if (!f.exists()) return
        val list = ArrayList<RawMsg>()
        try {
            f.forEachLine { l ->
                val p = l.split(',')
                if (p.size < 4) return@forEachLine
                val t = p[0].toLongOrNull() ?: return@forEachLine
                val sv = p[2].toIntOrNull() ?: return@forEachLine
                if (nowMs - t > KEEP_MS || t - nowMs > 600_000L) return@forEachLine
                val h = p[3].trim()
                val d = ByteArray(h.length / 2) { ((Character.digit(h[2 * it], 16) shl 4) or Character.digit(h[2 * it + 1], 16)).toByte() }
                list.add(RawMsg(t, p[1].firstOrNull() ?: return@forEachLine, sv, d))
            }
        } catch (e: Exception) { android.util.Log.w("GnssApp", "BroadcastEph.load: ${e.message}"); return }
        val before = count()
        list.sortBy { it.tMs }
        // perskaityti pranešimai per keep() vėl patenka į išsaugojimo sąrašą su savo gavimo laiku
        for (m in list) {
            when (m.kind) {
                'G' -> add(m.svid, m.data, gpsWeekAt(m.tMs), m.tMs)
                'E' -> addGal(m.svid, m.data, m.tMs / 1000.0 - 315_964_800.0 + 18.0, m.tMs)
                'R' -> addGlo(m.svid, m.data, m.tMs)
            }
        }
        loadedCount = count() - before
    }

    /** Visos efemeridės ir pranešimai pamirštami (testams). */
    @Synchronized
    fun clear() {
        slots.clear(); ephs.clear(); raw.clear(); gal.ephs.clear(); glo.ephs.clear()
        dirty = false; savedAt = 0L; loadedDir = null; loadedCount = 0
    }

    /** Išsaugo, jei yra naujų pranešimų ir praėjo ≥ 60 s (force – iškart). */
    @Synchronized
    fun save(dir: java.io.File, force: Boolean = false, nowMs: Long = System.currentTimeMillis()) {
        if (!dirty || (!force && nowMs - savedAt < 60_000L)) return
        raw.values.removeAll { nowMs - it.tMs > KEEP_MS }
        try {
            val tmp = java.io.File(dir, "$FILE.tmp")
            tmp.bufferedWriter().use { w ->
                for (m in raw.values.sortedBy { it.tMs })
                    w.write("${m.tMs},${m.kind},${m.svid},${m.data.joinToString("") { "%02x".format(it) }}\n")
            }
            if (!tmp.renameTo(java.io.File(dir, FILE))) { java.io.File(dir, FILE).delete(); tmp.renameTo(java.io.File(dir, FILE)) }
            dirty = false; savedAt = nowMs
        } catch (e: Exception) { android.util.Log.w("GnssApp", "BroadcastEph.save: ${e.message}") }
    }

    /** Vienas pranešimas; gpsWeek – dabartinė pilna GPS savaitė (10 bitų WN išplėtimui). */
    @Synchronized
    fun add(svid: Int, data: ByteArray, gpsWeek: Int, tMs: Long = System.currentTimeMillis()) {
        var sf: PositionSolver.Subframe? = null
        align?.let { sf = PositionSolver.subframeFromBytes(data, align = it) }
        if (sf == null) for (a in candidates) {
            sf = PositionSolver.subframeFromBytes(data, align = a)
            if (sf != null) { align = a; break }
        }
        val s = sf ?: run { rejected++; return }
        subframes++
        if (s.id > 3) return
        keep('G', svid, s.id, tMs, data)
        val slot = slots.getOrPut(svid) { arrayOfNulls(3) }
        slot[s.id - 1] = s
        val s1 = slot[0] ?: return; val s2 = slot[1] ?: return; val s3 = slot[2] ?: return
        PositionSolver.decodeEphemeris(svid, s1, s2, s3, gpsWeek)?.let { ephs[svid] = it }
    }

    @Synchronized fun get(svid: Int): PositionSolver.Ephemeris? = ephs[svid]
    @Synchronized fun galEph(svid: Int): PositionSolver.Ephemeris? = gal.ephs[svid]
    @Synchronized fun count(): Int = ephs.size + gal.ephs.size + glo.ephs.size

    private val gal = GalileoNav.Collector()
    private val glo = GloNav.Collector()

    /** Galileo I/NAV puslapis; tRx – gavimo laikas (GPS s). */
    @Synchronized fun addGal(svid: Int, data: ByteArray, tRx: Double, tMs: Long = System.currentTimeMillis()) {
        gal.add(svid, data, tRx)
        GalileoNav.word(data)?.let { w -> val t = GalileoNav.type(w); if (t in 1..5) keep('E', svid, t, tMs, data) }
    }

    /** GLONASS eilutė; svid – orbitinė vieta 1..24; tRxUtcMs – gavimo laikas (UTC ms). */
    @Synchronized fun addGlo(svid: Int, data: ByteArray, tRxUtcMs: Long) {
        if (svid !in 1..24) return
        glo.add(svid, data, tRxUtcMs)
        GloNav.bits(data, GloNav.pad)?.let { b -> val m = GloNav.stringNo(b); if (b.bit(0) == 0 && m in 1..4) keep('R', svid, m, tRxUtcMs, data) }
    }

    /** Ar yra bent viena efemeridė, tinkanti laikui t (GPS s). */
    @Synchronized fun any(t: Double): Boolean = ephs.values.any { abs(t - it.toeAbs) < 4 * 3600.0 } ||
        gal.ephs.values.any { abs(t - it.toeAbs) < 4 * 3600.0 } || glo.ephs.values.any { abs(t - it.tbGps) < 2 * 3600.0 }

    /** SP3 tipo ID (G01, E05, R12) -> ECEF (km) iš navigacinių efemeridžių arba null. */
    fun ecefKm(id: String, t: Double): DoubleArray? {
        val sv = id.substring(1).toIntOrNull() ?: return null
        return when (id[0]) {
            'G' -> ecefKm(sv, t)
            'E' -> synchronized(this) { gal.ephs[sv] }?.let { e ->
                if (abs(t - e.toeAbs) > 4 * 3600.0) null
                else PositionSolver.satState(e, t - e.week * 604_800.0).let { p -> doubleArrayOf(p[0] / 1000.0, p[1] / 1000.0, p[2] / 1000.0) }
            }
            'R' -> synchronized(this) { glo.ephs[sv] }?.let { e -> GloNav.position(e, t)?.let { p -> doubleArrayOf(p[0] / 1000.0, p[1] / 1000.0, p[2] / 1000.0) } }
            else -> null
        }
    }

    /** Palydovo ECEF (km) laiku t (GPS s) arba null (nėra efemeridės arba ji senesnė nei 4 val.). */
    fun ecefKm(svid: Int, t: Double): DoubleArray? {
        val e = get(svid) ?: return null
        if (abs(t - e.toeAbs) > 4 * 3600.0) return null
        val tow = t - Math.floorDiv(t.toLong(), 604_800L) * 604_800.0
        val p = PositionSolver.satState(e, tow)
        return doubleArrayOf(p[0] / 1000.0, p[1] / 1000.0, p[2] / 1000.0)
    }

    @Synchronized
    fun text(): String = (if (loadedCount > 0) "Efemeridžių įkelta iš išsaugoto failo (po paleidimo iš naujo): $loadedCount\n" else "") +
        "GPS navigacinės efemeridės (gyvi pranešimai): ${ephs.size} palydovų" +
        (if (ephs.isEmpty()) "" else " – " + ephs.keys.sorted().joinToString(" ") { "G%02d".format(Locale.US, it) }) +
        "; subkadrų priimta $subframes, atmesta $rejected; išdėstymas ${align?.toString() ?: "nerastas"}" +
        "\nGalileo navigacinės efemeridės: ${gal.ephs.size}" + (if (gal.ephs.isEmpty()) "" else " – " + gal.ephs.keys.sorted().joinToString(" ") { "E%02d".format(Locale.US, it) }) +
        "; puslapių su teisingu CRC ${gal.pages}, atmesta ${gal.bad}" +
        "\nGLONASS navigacinės efemeridės: ${glo.ephs.size}" + (if (glo.ephs.isEmpty()) "" else " – " + glo.ephs.keys.sorted().joinToString(" ") { "R%02d".format(Locale.US, it) }) +
        "; eilučių ${glo.strings}, atmesta ${glo.bad}"
}

/**
 * Kompaso patikra judant: GNSS kurso (Location.bearing) ir planšetės krypties skirtumas.
 * Planšetės kryptis matuojama dviem ašimis – ekrano viršaus (Y) ir žiūrėjimo pro ekraną (−Z);
 * kiekviena naudojama tik kai yra ne daugiau kaip 45° nuo horizontalės.
 * Pastovus skirtumas – planšetės pasukimas transporto priemonėje; sklaida – kompaso iškraipymas.
 */
class CompassCheck {
    class Acc { val d = ArrayList<Double>() }
    val axes = linkedMapOf("ekrano viršus (Y)" to Acc(), "žiūrėjimas pro ekraną (−Z)" to Acc())

    /** Skirtumas (−180..180] laipsniais. */
    fun add(axis: String, headingDeg: Double, courseDeg: Double) {
        val a = axes[axis] ?: return
        if (a.d.size >= 20_000) a.d.removeAt(0)
        a.d.add(wrap(headingDeg - courseDeg))
    }

    fun text(minSpeed: Double): String = buildString {
        append("Kompaso patikra judant (greitis ≥ ${"%.1f".format(Locale.US, minSpeed)} m/s, GNSS kursas − planšetės kryptis):")
        for ((name, a) in axes) {
            append("\n  $name: ")
            val s = stats(a.d)
            if (s == null) append("nėra mėginių")
            else append("%d mėg., vidutinis skirtumas %+.1f°, sklaida (90 %% |nuokrypis|) %.1f°; %s".format(Locale.US, a.d.size, s.first, s.second,
                if (s.second <= 10.0) "kompasas stabilus" else "kompasas iškraipytas arba planšetė sukiojama"))
        }
    }

    companion object {
        fun wrap(a: Double): Double { var x = (a + 180.0) % 360.0; if (x <= 0) x += 360.0; return x - 180.0 }

        /** (apskritiminis vidurkis, 90 % |nuokrypis nuo jo|) arba null. */
        fun stats(d: List<Double>): Pair<Double, Double>? {
            if (d.isEmpty()) return null
            val s = d.sumOf { sin(Math.toRadians(it)) }; val c = d.sumOf { cos(Math.toRadians(it)) }
            val m = Math.toDegrees(atan2(s, c))
            val dev = d.map { abs(wrap(it - m)) }.sorted()
            return m to dev[minOf(dev.size - 1, (0.9 * dev.size).toInt())]
        }

        /** Ašies azimutas (°) ir kampas nuo horizontalės (°) iš pasaulio komponenčių (rytai, šiaurė, aukštyn). */
        fun axisAz(e: Double, n: Double, u: Double): Pair<Double, Double> =
            ((Math.toDegrees(atan2(e, n)) + 360.0) % 360.0) to Math.toDegrees(atan2(abs(u), sqrt(e * e + n * n)))
    }
}

/**
 * Planšetės orientacija, kurią skaito ir foninė paslauga (ekranas užgesęs, kuprinėje): pitch, roll, azimutas (getOrientation),
 * judėjimo lygis ir ašių kryptys; bendra kompaso patikra judant (veikla ir paslauga).
 */
object Orient {
    @Volatile var pitch = Double.NaN; @Volatile var roll = Double.NaN; @Volatile var az = Double.NaN; @Volatile var motion = 0.0
    @Volatile var axisY: Pair<Double, Double>? = null; @Volatile var axisZ: Pair<Double, Double>? = null
    @Volatile var ms = 0L
    val check = CompassCheck()

    fun onRotation(values: FloatArray) {
        val r = FloatArray(9); val o = FloatArray(3)
        android.hardware.SensorManager.getRotationMatrixFromVector(r, values)
        android.hardware.SensorManager.getOrientation(r, o)
        az = (Math.toDegrees(o[0].toDouble()) + 360.0) % 360.0; pitch = Math.toDegrees(o[1].toDouble()); roll = Math.toDegrees(o[2].toDouble())
        axisY = CompassCheck.axisAz(r[1].toDouble(), r[4].toDouble(), r[7].toDouble())
        axisZ = CompassCheck.axisAz(-r[2].toDouble(), -r[5].toDouble(), -r[8].toDouble())
        ms = System.currentTimeMillis()
    }

    fun onLinear(v: FloatArray) { motion = motion * 0.9 + 0.1 * kotlin.math.sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()) }

    val fresh get() = System.currentTimeMillis() - ms < 5_000

    /** Kompaso patikra: GNSS kursas judant ≥ 1,5 m/s. */
    fun onLocation(loc: android.location.Location) {
        if (!fresh || !loc.hasSpeed() || !loc.hasBearing() || loc.speed < 1.5f) return
        axisY?.let { if (it.second <= 45.0) check.add("ekrano viršus (Y)", it.first, loc.bearing.toDouble()) }
        axisZ?.let { if (it.second <= 45.0) check.add("žiūrėjimas pro ekraną (−Z)", it.first, loc.bearing.toDouble()) }
    }
}
