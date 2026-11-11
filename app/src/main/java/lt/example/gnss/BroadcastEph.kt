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

    /** Vienas pranešimas; gpsWeek – dabartinė pilna GPS savaitė (10 bitų WN išplėtimui). */
    @Synchronized
    fun add(svid: Int, data: ByteArray, gpsWeek: Int) {
        var sf: PositionSolver.Subframe? = null
        align?.let { sf = PositionSolver.subframeFromBytes(data, align = it) }
        if (sf == null) for (a in candidates) {
            sf = PositionSolver.subframeFromBytes(data, align = a)
            if (sf != null) { align = a; break }
        }
        val s = sf ?: run { rejected++; return }
        subframes++
        if (s.id > 3) return
        val slot = slots.getOrPut(svid) { arrayOfNulls(3) }
        slot[s.id - 1] = s
        val s1 = slot[0] ?: return; val s2 = slot[1] ?: return; val s3 = slot[2] ?: return
        PositionSolver.decodeEphemeris(svid, s1, s2, s3, gpsWeek)?.let { ephs[svid] = it }
    }

    @Synchronized fun get(svid: Int): PositionSolver.Ephemeris? = ephs[svid]
    @Synchronized fun count(): Int = ephs.size + gal.ephs.size + glo.ephs.size

    private val gal = GalileoNav.Collector()
    private val glo = GloNav.Collector()

    /** Galileo I/NAV puslapis; tRx – gavimo laikas (GPS s). */
    @Synchronized fun addGal(svid: Int, data: ByteArray, tRx: Double) = gal.add(svid, data, tRx)

    /** GLONASS eilutė; svid – orbitinė vieta 1..24; tRxUtcMs – gavimo laikas (UTC ms). */
    @Synchronized fun addGlo(svid: Int, data: ByteArray, tRxUtcMs: Long) { if (svid in 1..24) glo.add(svid, data, tRxUtcMs) }

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
    fun text(): String = "GPS navigacinės efemeridės (gyvi pranešimai): ${ephs.size} palydovų" +
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
