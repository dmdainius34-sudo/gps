package lt.example.gnss

import android.app.ActivityManager
import android.content.Context
import android.location.GnssMeasurement
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.os.Build
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Įrašymo sesijos failai gnss_raw.csv, gnss_fix.csv, gnss_nav.csv – vienas bendras rašytojas procesui.
 * Paslaugai ar procesui paleidus iš naujo failai tęsiami (append); naujas failas – tik po „Baigti įrašą“
 * ir naujo įrašymo pradžios (arba atgalinio skaičiavimo pradžioje – tik raw ir fix).
 */
object SessionFiles {
    const val RAW = "gnss_raw.csv"
    const val FIX = "gnss_fix.csv"
    private const val PREFS = "session"

    val RAW_HEADER = "laikas_ms,konstelacija,svid,signalas,dazniu_hz,cn0_dbhz,baseband_cn0_dbhz," +
        "kampas_deg,azimutas_deg,fiksui," +
        "time_nanos,full_bias_nanos,bias_nanos,bias_unc_nanos,drift_nsps,time_offset_ns," +
        "state,recv_sv_time_ns,recv_sv_time_unc_ns,doplerio_mps,doplerio_unc_mps," +
        "adr_m,adr_state,adr_unc_m,multipath,agc_db,aplinka\n"
    val FIX_HEADER = "laikas_ms,platuma,ilguma,aukstis_elips_m,geoido_N_m,aukstis_jura_m," +
        "h_tikslumas_m,v_tikslumas_m,greitis_mps,kryptis_deg,palydovu_naudojama,palydovu_matoma," +
        "vid_cn0_naudojami,top4_cn0,pitch_deg,roll_deg,azimutas_ekrano_deg,judejimas_mps2,aplinka," +
        "zinomas_agl_m,zinomas_msl_m,zinoma_platuma,zinoma_ilguma,klaida_aukscio_m,klaida_horiz_m,klaida_rytai_m,klaida_siaure_m\n"
    private val RAW_FIELDS = RAW_HEADER.count { it == ',' } + 1
    private val FIX_FIELDS = FIX_HEADER.count { it == ',' } + 1

    private var dir: File? = null
    private var raw: BufferedWriter? = null
    private var fix: BufferedWriter? = null
    private var rawRows = 0L
    @Volatile var badWritten = 0
        private set
    @Volatile var activityWritesFixes = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun restarts(ctx: Context) = prefs(ctx).getInt("restarts", 0)
    fun active(ctx: Context) = prefs(ctx).getBoolean("active", false)

    /** Atidaro sesijos failus (vieną kartą procesui): tęsia aktyvią sesiją arba pradeda naują. */
    @Synchronized fun open(ctx: Context) {
        if (dir != null) return
        val d = ctx.applicationContext.cacheDir
        dir = d
        val p = prefs(ctx)
        val cont = p.getBoolean("active", false) && File(d, RAW).exists()
        if (cont) {
            val n = p.getInt("restarts", 0) + 1
            p.edit().putInt("restarts", n).apply()
            GapLog.event(ctx, "SESIJA tęsiama po paleidimo iš naujo #$n; priežastis: ${lastExitReason(ctx)}")
        } else {
            p.edit().putBoolean("active", true).putInt("restarts", 0).apply()
        }
        openWriters(d, append = cont)
        NavRecorder.open(d, append = cont)
        RtcmRecorder.open(d, append = cont)
        GapLog.newSession(ctx)
    }

    /** Nauja sesija (po „Baigti įrašą“ pradėjus iš naujo): visi trys failai perrašomi. */
    @Synchronized fun startNew(ctx: Context) {
        val d = ctx.applicationContext.cacheDir
        dir = d
        prefs(ctx).edit().putBoolean("active", true).putInt("restarts", 0).apply()
        openWriters(d, append = false)
        NavRecorder.open(d, append = false)
        RtcmRecorder.open(d, append = false)
        GapLog.newSession(ctx)
        GapLog.event(ctx, "SESIJA nauja (vartotojas pradėjo įrašą)")
    }

    /** „Baigti įrašą“: failai nuplaunami, kitas paleidimas pradės naują sesiją. */
    @Synchronized fun end(ctx: Context) {
        flush()
        prefs(ctx).edit().putBoolean("active", false).apply()
    }

    /** Atgalinio skaičiavimo pradžia: raw ir fix pradedami iš naujo (nav paliekamas efemeridėms). */
    @Synchronized fun resetRawFix(ctx: Context) {
        val d = ctx.applicationContext.cacheDir
        dir = d
        openWriters(d, append = false)
        GapLog.newSession(ctx)
    }

    private fun openWriters(d: File, append: Boolean) {
        try { raw?.close() } catch (_: Exception) {}
        try { fix?.close() } catch (_: Exception) {}
        rawRows = 0
        raw = writer(File(d, RAW), RAW_HEADER, append)
        fix = writer(File(d, FIX), FIX_HEADER, append)
    }

    private fun writer(f: File, header: String, append: Boolean): BufferedWriter? = try {
        val keep = append && f.exists() && f.length() > 0
        BufferedWriter(FileWriter(f, keep), 1 shl 16).also { if (!keep) { it.write(header); it.flush() } }
    } catch (e: Exception) { Log.e("GnssApp", "${f.name}: ${e.message}"); null }

    /** Laukų skaičius pagal CSV (kabutėse esantys kableliai neskaičiuojami). */
    fun fieldCount(line: String): Int {
        var n = 1; var q = false
        for (ch in line) { if (ch == '"') q = !q else if (ch == ',' && !q) n++ }
        return n
    }

    /** Laisvo teksto laukas: be kablelių, kabučių ir eilučių pabaigų. */
    fun clean(s: String): String = s.replace(',', ';').replace('"', '\'').replace('\n', ' ').replace('\r', ' ')

    @Synchronized fun writeRaw(ctx: Context, line: String) {
        if (raw == null) open(ctx)
        if (rawRows > 800_000) { dir?.let { raw = writer(File(it, RAW), RAW_HEADER, false) }; rawRows = 0 }
        if (fieldCount(line.trimEnd('\n')) != RAW_FIELDS) badWritten++
        try { raw?.write(line); rawRows++ } catch (_: Exception) {}
    }

    @Synchronized fun writeFix(ctx: Context, line: String) {
        if (fix == null) open(ctx)
        if (fieldCount(line.trimEnd('\n')) != FIX_FIELDS) badWritten++
        try { fix?.write(line); fix?.flush() } catch (_: Exception) {}
    }

    /** Kiekvienos partijos (1 s) pabaigoje – praradimas strigties atveju ne daugiau 1 s. */
    @Synchronized fun flush() {
        try { raw?.flush() } catch (_: Exception) {}
        try { fix?.flush() } catch (_: Exception) {}
    }

    @Synchronized fun file(ctx: Context, name: String): File {
        flush()
        val f = File(ctx.cacheDir, name)
        if (!f.exists()) f.writeText(if (name == RAW) RAW_HEADER else FIX_HEADER)
        return f
    }

    /** Savitikra eksportui: eilučių, kurių laukų skaičius ≠ antraštės, kiekis ir iš viso eilučių. */
    fun badLines(f: File): Pair<Int, Int> {
        if (!f.exists()) return 0 to 0
        var bad = 0; var total = 0; var expect = -1
        try {
            f.bufferedReader().useLines { seq ->
                for (l in seq) {
                    if (l.isEmpty()) continue
                    if (expect < 0) { expect = fieldCount(l); continue }
                    total++
                    if (fieldCount(l) != expect) bad++
                }
            }
        } catch (_: Exception) {}
        return bad to total
    }

    private fun lastExitReason(ctx: Context): String {
        if (Build.VERSION.SDK_INT < 30) return "nežinoma (Android < 11)"
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val r = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull() ?: return "nežinoma"
            val why = when (r.reason) {
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "trūko atminties"
                android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "vartotojas sustabdė"
                android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "vartotojas sustabdė (nustatymuose)"
                android.app.ApplicationExitInfo.REASON_CRASH -> "strigtis"
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "gimtoji strigtis"
                android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
                android.app.ApplicationExitInfo.REASON_SIGNALED -> "signalas"
                android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "per didelis resursų naudojimas"
                android.app.ApplicationExitInfo.REASON_OTHER -> "sistema (kita)"
                android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "priklausomybė nutrūko"
                android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "pakeisti leidimai"
                android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "programa baigė pati"
                else -> "kodas ${r.reason}"
            }
            "$why (${r.description ?: "be aprašo"}, ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(r.timestamp))})"
        } catch (e: Exception) { "nežinoma (${e.message})" }
    }
}

/** gnss_raw.csv eilučių formavimas (formatas nepakeistas); naudojamas NavRecordService. */
object RawRecorder {
    @Volatile var envTag: String = ""
    @Volatile var sats: Map<String, Sat> = emptyMap()
    val bandsSeen: MutableSet<String> = java.util.Collections.synchronizedSortedSet(java.util.TreeSet())
    @Volatile var events = 0L
        private set

    fun bandName(c: String, hz: Double): String {
        val mhz = hz / 1e6
        return when {
            kotlin.math.abs(mhz - 1575.42) < 2 -> when (c) { "GAL" -> "E1"; "BDS" -> "B1C"; else -> "L1" }
            kotlin.math.abs(mhz - 1176.45) < 2 -> when (c) { "GAL" -> "E5a"; "BDS" -> "B2a"; else -> "L5" }
            kotlin.math.abs(mhz - 1207.14) < 2 -> if (c == "GAL") "E5b" else "B2b"
            kotlin.math.abs(mhz - 1227.60) < 2 -> "L2"
            kotlin.math.abs(mhz - 1561.098) < 2 -> "B1I"
            kotlin.math.abs(mhz - 1268.52) < 2 -> "B3I"
            mhz in 1598.0..1607.0 -> "G1"
            mhz in 1242.0..1249.0 -> "G2"
            else -> fx(mhz, 2) + "MHz"
        }
    }

    /** Viena matavimų partija (≈1 s): pertraukų žurnalas, eilutės, nuplovimas. */
    fun onEvent(ctx: Context, event: GnssMeasurementsEvent) {
        events++
        GapLog.onRaw(ctx)
        val sb = StringBuilder(256)
        for (m in event.measurements) {
            sb.setLength(0)
            line(sb, m, event.clock)
            SessionFiles.writeRaw(ctx, sb.toString())
        }
        SessionFiles.flush()
    }

    private fun line(sb: StringBuilder, m: GnssMeasurement, clk: android.location.GnssClock) {
        val c = constName(m.constellationType)
        val hz = if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else 1575.42e6
        val band = bandName(c, hz)
        bandsSeen.add("$c $band")
        val sat = sats["$c${m.svid}"]
        val bb = if (Build.VERSION.SDK_INT >= 30 && m.hasBasebandCn0DbHz()) m.basebandCn0DbHz.toString() else ""
        @Suppress("DEPRECATION")
        val agc = if (m.hasAutomaticGainControlLevelDb()) m.automaticGainControlLevelDb.toString() else ""
        sb.append(System.currentTimeMillis()).append(',')
            .append(c).append(',').append(m.svid).append(',').append(band).append(',')
            .append(if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toString() else "").append(',')
            .append(m.cn0DbHz).append(',').append(bb).append(',')
            .append(sat?.elev?.let { fx(it.toDouble(), 1) } ?: "").append(',')
            .append(sat?.az?.let { fx(it.toDouble(), 1) } ?: "").append(',')
            .append(if (sat?.used == true) 1 else 0).append(',')
            .append(clk.timeNanos).append(',')
            .append(if (clk.hasFullBiasNanos()) clk.fullBiasNanos.toString() else "").append(',')
            .append(if (clk.hasBiasNanos()) clk.biasNanos.toString() else "").append(',')
            .append(if (clk.hasBiasUncertaintyNanos()) clk.biasUncertaintyNanos.toString() else "").append(',')
            .append(if (clk.hasDriftNanosPerSecond()) clk.driftNanosPerSecond.toString() else "").append(',')
            .append(m.timeOffsetNanos).append(',')
            .append(m.state).append(',')
            .append(m.receivedSvTimeNanos).append(',')
            .append(m.receivedSvTimeUncertaintyNanos).append(',')
            .append(m.pseudorangeRateMetersPerSecond).append(',')
            .append(m.pseudorangeRateUncertaintyMetersPerSecond).append(',')
            .append(m.accumulatedDeltaRangeMeters).append(',')
            .append(m.accumulatedDeltaRangeState).append(',')
            .append(m.accumulatedDeltaRangeUncertaintyMeters).append(',')
            .append(m.multipathIndicator).append(',')
            .append(agc).append(',')
            .append(SessionFiles.clean(envTag)).append('\n')
    }

    /** Fikso eilutė, kai veiklos nėra (paslauga paleista iš naujo fone): veiklos laukai tušti, formatas tas pats. */
    fun fixLineWithoutActivity(loc: Location): String {
        val list = sats.values.toList()
        val used = list.filter { it.used }
        val avg = if (used.isNotEmpty()) used.map { it.cn0 }.average() else Double.NaN
        val t4 = top4Avg(list)
        val n = try { Geoid.undulation(loc.latitude, loc.longitude) } catch (_: Exception) { null }
        val sb = StringBuilder()
        sb.append(loc.time).append(',')
            .append(fx(loc.latitude, 8)).append(',').append(fx(loc.longitude, 8)).append(',')
            .append(if (loc.hasAltitude()) fx(loc.altitude, 2) else "").append(',')
            .append(n?.let { fx(it, 3) } ?: "").append(',')
            .append(if (loc.hasAltitude() && n != null) fx(loc.altitude - n, 2) else "").append(',')
            .append(if (loc.hasAccuracy()) fx(loc.accuracy.toDouble(), 2) else "").append(',')
            .append(if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) fx(loc.verticalAccuracyMeters.toDouble(), 2) else "").append(',')
            .append(if (loc.hasSpeed()) fx(loc.speed.toDouble(), 2) else "").append(',')
            .append(if (loc.hasBearing()) fx(loc.bearing.toDouble(), 1) else "").append(',')
            .append(used.size).append(',').append(list.size).append(',')
            .append(if (avg.isNaN()) "" else fx(avg, 1)).append(',')
            .append(t4?.let { fx(it.toDouble(), 1) } ?: "").append(',')
            .append(",,,,")                                   // pitch, roll, azimutas, judėjimas – nėra veiklos
            .append(SessionFiles.clean(envTag)).append(',')
            .append(",,,,,,,\n")                              // žinomi taškai ir paklaidos
        return sb.toString()
    }
}
