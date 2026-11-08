package lt.example.gnss

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Įrašymo pertraukų žurnalas gap_log.txt: kiekviena > 2 s pertrauka tarp gnss_raw.csv matavimų
 * ir paslaugos / sistemos įvykiai (gyvavimo ciklas, ekranas, GNSS būsena).
 */
object GapLog {
    const val FILE = "gap_log.txt"
    private const val GAP_MS = 2000L
    private const val PREFS = "gap_log"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile var gapCount = 0
        private set
    @Volatile var longestMs = 0L
        private set
    private val firstGaps = ArrayList<String>()
    private val pending = ArrayList<String>()      // įvykiai nuo paskutinio matavimo
    private var lastRaw = 0L
    private var lastSaved = 0L
    private var sessionStarted = false

    fun file(ctx: Context) = File(ctx.cacheDir, FILE)

    private fun ts(ms: Long) = synchronized(fmt) { fmt.format(Date(ms)) }

    @Synchronized private fun write(ctx: Context, line: String) {
        try {
            val f = file(ctx)
            if (f.length() > 2_000_000) f.writeText("(žurnalas sutrumpintas)\n")
            f.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    /** Sistemos ar paslaugos įvykis: įrašomas iš karto ir pridedamas prie kitos pertraukos eilutės. */
    @Synchronized fun event(ctx: Context, what: String) {
        val now = System.currentTimeMillis()
        write(ctx, "${ts(now)} ĮVYKIS $what")
        synchronized(pending) { if (pending.size < 20) pending.add("${ts(now).substring(11)} $what") }
    }

    fun screenOn(ctx: Context) = try { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive } catch (_: Exception) { true }

    fun batteryExempt(ctx: Context) = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) { false }

    private fun foreground(): String {
        val info = ActivityManager.RunningAppProcessInfo()
        return try {
            ActivityManager.getMyMemoryState(info)
            if (info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) "ši programa"
            else "kita programa (GNSS fone)"
        } catch (_: Exception) { "nežinoma" }
    }

    /** Naujas gnss_raw.csv failas: pertrauka nuo ankstesnės sesijos paskutinio matavimo (jei buvo). */
    @Synchronized fun newSession(ctx: Context) {
        gapCount = 0; longestMs = 0; firstGaps.clear(); lastRaw = 0
        val prev = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_raw", 0L)
        write(ctx, "${ts(System.currentTimeMillis())} SESIJA naujas gnss_raw.csv; ankstesnės sesijos paskutinis matavimas: ${if (prev > 0) ts(prev) else "nėra"}")
        if (prev > 0) lastRaw = prev
        sessionStarted = true
    }

    /** Kviečiama gavus matavimų įvykį (įrašomą į gnss_raw.csv). */
    @Synchronized fun onRaw(ctx: Context) {
        val now = System.currentTimeMillis()
        if (!sessionStarted) newSession(ctx)
        if (lastRaw > 0 && now - lastRaw > GAP_MS) {
            val len = now - lastRaw
            val ev = synchronized(pending) { if (pending.isEmpty()) "nėra" else pending.joinToString("; ") }
            val line = "PERTRAUKA pradžia=${ts(lastRaw)} pabaiga=${ts(now)} trukmė=%.1f s ekranas=%s priekyje=%s paslauga=%s baterijos_išimtis=%s įvykiai=%s".format(
                Locale.US, len / 1000.0, if (screenOn(ctx)) "įjungtas" else "išjungtas", foreground(),
                if (NavRecordService.running) "taip" else "ne", if (batteryExempt(ctx)) "taip" else "ne", ev)
            write(ctx, line)
            gapCount++
            if (len > longestMs) longestMs = len
            if (firstGaps.size < 5) firstGaps.add(line)
        }
        synchronized(pending) { pending.clear() }
        lastRaw = now
        if (now - lastSaved > 1000) {
            lastSaved = now
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last_raw", now).apply()
        }
    }

    /** Ataskaitos skyrius „Įrašymo pertraukos“. */
    @Synchronized fun report(ctx: Context): String = buildString {
        appendLine("Pertraukų (> 2 s) skaičius: $gapCount")
        appendLine("Ilgiausia pertrauka: %.1f s".format(Locale.US, longestMs / 1000.0))
        appendLine("Baterijos optimizavimo išimtis: ${if (batteryExempt(ctx)) "taip" else "ne"}")
        appendLine("Įrašymo paslauga: ${if (NavRecordService.running) "veikia" else "neveikia"}")
        appendLine("Pirmosios 5 gap_log eilutės:")
        if (firstGaps.isEmpty()) appendLine("  (pertraukų nėra)")
        firstGaps.forEach { appendLine("  $it") }
    }
}
