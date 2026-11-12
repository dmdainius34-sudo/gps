package lt.example.gnss

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * BRDC – visų sistemų transliuojamos efemeridės iš interneto (IGS, BKG: BRDC00WRD_*_MN.rnx.gz, RINEX 3/4 navigacija).
 * GPS, Galileo, BeiDou (Kepleris; BeiDou GEO – atskiras posūkis) ir GLONASS (RK4). Atnaujinama kas valandą, saugoma prietaise.
 */
object Brdc {
    const val BASE = "https://igs.bkg.bund.de/root_ftp/IGS/BRDC/"
    private const val SUBDIR = "brdc"
    private const val GM_GAL = 3.986004418e14
    private const val OE_BDS = 7.292115e-5

    class Kep(val sys: Char, val prn: Int, val toeAbs: Double, val tocAbs: Double, val sqrtA: Double, val e: Double, val i0: Double,
              val om0: Double, val om: Double, val m0: Double, val dn: Double, val omd: Double, val idot: Double,
              val cuc: Double, val cus: Double, val crc: Double, val crs: Double, val cic: Double, val cis: Double,
              val af0: Double, val af1: Double, val af2: Double, val tgd: Double)

    /** Atskiras rinkinys (testams ir failui) – visi įrašai pagal palydovą. */
    class Set {
        val kep = HashMap<String, ArrayList<Kep>>()
        val glo = HashMap<String, ArrayList<GloNav.Eph>>()
        var gpsIonA: DoubleArray? = null; var gpsIonB: DoubleArray? = null
        var version = 0.0
        fun counts(): Map<Char, Int> {
            val m = java.util.TreeMap<Char, Int>()
            kep.keys.forEach { m[it[0]] = (m[it[0]] ?: 0) + 1 }; glo.keys.forEach { m[it[0]] = (m[it[0]] ?: 0) + 1 }
            return m
        }
        val first get() = (kep.values.flatten().map { it.toeAbs } + glo.values.flatten().map { it.tbGps }).minOrNull()
        val last get() = (kep.values.flatten().map { it.toeAbs } + glo.values.flatten().map { it.tbGps }).maxOrNull()

        fun ecef(id: String, t: Double): DoubleArray? {
            if (id[0] == 'R') {
                val e = glo[id]?.minByOrNull { abs(it.tbGps - t) } ?: return null
                return GloNav.position(e, t, 1.5 * 3600)
            }
            val k = kep[id]?.minByOrNull { abs(it.toeAbs - t) } ?: return null
            if (abs(k.toeAbs - t) > 4 * 3600) return null
            return kepler(k, t)
        }
    }

    private fun num(s: String): Double = s.trim().replace('D', 'E').replace('d', 'e').ifEmpty { "0" }.toDouble()
    /** UTC/sistemos epocha -> GPS sekundės (GPS ir Galileo – be poslinkio, BeiDou BDT + 14 s, GLONASS UTC + 18 s). */
    private fun epochGps(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Double, sys: Char): Double {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { clear(); set(y, mo - 1, d, h, mi, 0) }
        val t = c.timeInMillis / 1000.0 + s - 315_964_800.0
        return when (sys) { 'C' -> t + 14.0; 'R' -> t + 18.0; else -> t }
    }

    fun parse(inp: InputStream): Set {
        val set = Set()
        val lines = inp.bufferedReader().readLines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]; i++
            if (l.length > 60) {
                val lab = l.substring(60).trim()
                if (lab.startsWith("RINEX VERSION")) set.version = l.substring(0, 9).trim().toDoubleOrNull() ?: 0.0
                if (lab.startsWith("IONOSPHERIC CORR")) {
                    val v = DoubleArray(4) { num(l.substring(5 + 12 * it, 17 + 12 * it)) }
                    if (l.startsWith("GPSA")) set.gpsIonA = v; if (l.startsWith("GPSB")) set.gpsIonB = v
                }
                if (lab.startsWith("END OF HEADER")) break
            }
        }
        while (i < lines.size) {
            var l = lines[i]
            if (l.startsWith(">")) {                                       // RINEX 4: „> EPH G01 LNAV“
                val p = l.trim().split(Regex("\\s+"))
                val ok = p.size >= 4 && p[1] == "EPH" && (p[3] in setOf("LNAV", "INAV", "FDMA", "D1", "D2"))
                i++
                if (!ok) { while (i < lines.size && !lines[i].startsWith(">")) i++; continue }
                l = lines[i]
            }
            if (l.length < 23 || l[0] !in "GERCJIS") { i++; continue }
            val sys = l[0]
            val prn = l.substring(1, 3).trim().toIntOrNull()
            if (prn == null) { i++; continue }
            val nLines = when (sys) { 'R' -> if (set.version >= 3.05) 5 else 4; 'S' -> 4; 'I' -> 8; else -> 8 }
            if (i + nLines > lines.size) break
            val rec = lines.subList(i, i + nLines); i += nLines
            try {
                val y = rec[0].substring(4, 8).trim().toInt(); val mo = rec[0].substring(9, 11).trim().toInt()
                val d = rec[0].substring(12, 14).trim().toInt(); val h = rec[0].substring(15, 17).trim().toInt()
                val mi = rec[0].substring(18, 20).trim().toInt(); val s = rec[0].substring(21, 23).trim().toDouble()
                val c0 = DoubleArray(3) { k -> num(rec[0].substring(23 + 19 * k, minOf(42 + 19 * k, rec[0].length))) }
                val b = Array(nLines - 1) { r -> val ln = rec[r + 1]; DoubleArray(4) { k ->
                    val a = 4 + 19 * k; if (a >= ln.length) 0.0 else ln.substring(a, minOf(a + 19, ln.length)).let { if (it.isBlank()) 0.0 else num(it) } } }
                val id = "%c%02d".format(Locale.US, sys, prn)
                val toc = epochGps(y, mo, d, h, mi, s, sys)
                when (sys) {
                    'G', 'E', 'C', 'J' -> {
                        val toe = b[2][0]; val week = b[4][2]
                        val weekGps = when (sys) { 'C' -> week + 1356; else -> week }
                        val toeAbs = weekGps * 604_800.0 + toe + (if (sys == 'C') 14.0 else 0.0)
                        val tgd = when (sys) { 'E' -> b[5][3]; else -> b[5][2] }
                        set.kep.getOrPut(id) { ArrayList() }.add(Kep(sys, prn, toeAbs, toc, b[1][3], b[1][1], b[3][0], b[2][2], b[3][2],
                            b[0][3], b[0][2], b[3][3], b[4][0], b[1][0], b[1][2], b[3][1], b[0][1], b[2][1], b[2][3], c0[0], c0[1], c0[2], tgd))
                    }
                    'R' -> {
                        val km = 1000.0
                        val x = doubleArrayOf(b[0][0] * km, b[1][0] * km, b[2][0] * km, b[0][1] * km, b[1][1] * km, b[2][1] * km)
                        val acc = doubleArrayOf(b[0][2] * km, b[1][2] * km, b[2][2] * km)
                        set.glo.getOrPut(id) { ArrayList() }.add(GloNav.Eph(prn, toc, x, acc, -c0[0], c0[1]))
                    }
                }
            } catch (_: Exception) {}
        }
        return set
    }

    /** Kepleris: GPS/Galileo/QZSS ir BeiDou MEO/IGSO; BeiDou GEO (C01–C05, C59–C63) – su −5° posūkiu. */
    fun kepler(k: Kep, t: Double): DoubleArray {
        val gm = if (k.sys == 'G' || k.sys == 'J') PositionSolver.MU else GM_GAL
        val oe = if (k.sys == 'C') OE_BDS else PositionSolver.OMEGA_E
        val a = k.sqrtA * k.sqrtA
        val tk = t - k.toeAbs
        val n = sqrt(gm / (a * a * a)) + k.dn
        val m = k.m0 + n * tk
        var e = m; repeat(12) { e = m + k.e * sin(e) }
        val v = atan2(sqrt(1 - k.e * k.e) * sin(e), cos(e) - k.e)
        val ph = v + k.om
        val u = ph + k.cus * sin(2 * ph) + k.cuc * cos(2 * ph)
        val r = a * (1 - k.e * cos(e)) + k.crs * sin(2 * ph) + k.crc * cos(2 * ph)
        val inc = k.i0 + k.idot * tk + k.cis * sin(2 * ph) + k.cic * cos(2 * ph)
        val xp = r * cos(u); val yp = r * sin(u)
        val toeSow = ((k.toeAbs - (if (k.sys == 'C') 14.0 else 0.0)) % 604_800.0 + 604_800.0) % 604_800.0
        val geo = k.sys == 'C' && (k.prn <= 5 || k.prn >= 59)
        if (!geo) {
            val om = k.om0 + (k.omd - oe) * tk - oe * toeSow
            return doubleArrayOf(xp * cos(om) - yp * cos(inc) * sin(om), xp * sin(om) + yp * cos(inc) * cos(om), yp * sin(inc))
        }
        val om = k.om0 + k.omd * tk - oe * toeSow
        val xg = xp * cos(om) - yp * cos(inc) * sin(om); val yg = xp * sin(om) + yp * cos(inc) * cos(om); val zg = yp * sin(inc)
        val f = Math.toRadians(-5.0); val z = oe * tk
        val y1 = yg * cos(f) + zg * sin(f); val z1 = -yg * sin(f) + zg * cos(f)
        return doubleArrayOf(xg * cos(z) + y1 * sin(z), -xg * sin(z) + y1 * cos(z), z1)
    }

    // ---------------- Atsisiuntimas ir būsena ----------------
    @Volatile var set: Set? = null
        private set
    @Volatile var source = ""
        private set
    @Volatile var status = "neatsisiųsta"
        private set
    @Volatile private var loadedAt = 0L
    @Volatile private var busy = false

    fun dir(ctx: Context) = File(ctx.filesDir, SUBDIR).apply { mkdirs() }

    /** Kas valandą (arba jei nėra failo) – fone: įkelia vietinį, atsisiunčia naują. */
    fun refresh(ctx: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (busy || (!force && now - loadedAt < 3_600_000L && set != null)) return
        busy = true
        Thread {
            try {
                if (set == null) loadLocal(ctx)
                val r = download(ctx)
                status = r
            } catch (e: Exception) { status = "klaida: ${e.message}" }
            finally { loadedAt = System.currentTimeMillis(); busy = false }
        }.start()
    }

    private fun loadLocal(ctx: Context) {
        val f = dir(ctx).listFiles()?.filter { it.name.endsWith(".rnx") }?.maxByOrNull { it.lastModified() } ?: return
        set = f.inputStream().use { parse(it) }; source = f.name
    }

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "GnssApp")
    }

    /** Šiandienos (arba vakar) katalogo sąraše – naujausias BRDC*MN.rnx(.gz). */
    fun pick(listing: String): String? =
        Regex("BRDC00[A-Z]{3}_[A-Z]_\\d{11}_01D_MN\\.rnx(\\.gz)?").findAll(listing).map { it.value }.distinct().sorted().lastOrNull()

    private fun download(ctx: Context): String {
        val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        for (back in 0..1) {
            val cal = (c.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -back) }
            val y = cal.get(java.util.Calendar.YEAR); val doy = cal.get(java.util.Calendar.DAY_OF_YEAR)
            val dirUrl = "%s%d/%03d/".format(Locale.US, BASE, y, doy)
            val conn = get(dirUrl)
            if (conn.responseCode != 200) continue
            val name = pick(conn.inputStream.bufferedReader().readText()) ?: continue
            val fc = get(dirUrl + name)
            if (fc.responseCode != 200) return "nepavyko: HTTP ${fc.responseCode} $name"
            val raw = fc.inputStream.use { it.readBytes() }
            val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
                java.util.zip.GZIPInputStream(raw.inputStream()).use { it.readBytes() } else raw
            val parsed = bytes.inputStream().use { parse(it) }
            if (parsed.kep.isEmpty() && parsed.glo.isEmpty()) return "nepavyko: tuščias $name"
            val out = File(dir(ctx), name.removeSuffix(".gz"))
            out.writeBytes(bytes)
            dir(ctx).listFiles()?.filter { it != out }?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
            set = parsed; source = out.name
            return "atnaujinta: ${out.name}"
        }
        return "nepavyko rasti BRDC failo (${BASE})"
    }

    fun ecefKm(id: String, t: Double): DoubleArray? = set?.ecef(id, t)?.let { doubleArrayOf(it[0] / 1000, it[1] / 1000, it[2] / 1000) }

    fun text(): String {
        val s = set ?: return "BRDC (interneto efemeridės): $status"
        val f = s.first; val l = s.last
        return "BRDC (interneto efemeridės): $source; palydovų " + s.counts().entries.joinToString(" ") { "${it.key} ${it.value}" } +
            (if (f != null && l != null) "; efemeridžių laikai ${Sp3Reader.utc(f)}–${Sp3Reader.utc(l)} UTC" else "") + "; $status"
    }
}
