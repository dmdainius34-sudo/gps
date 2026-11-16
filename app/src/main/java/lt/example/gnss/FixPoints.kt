package lt.example.gnss

import java.io.File
import java.util.Locale

/**
 * RTK FIX taškai: kiekviena nepertraukta FIX atkarpa – vienas taškas (FIX epochų vidurkis).
 * Kiekviena FIX epocha įrašoma į fix_taskai.csv; NLO lange rodomi paskutiniai taškai, juos galima nukopijuoti.
 */
object FixPoints {
    const val FILE = "fix_taskai.csv"
    private const val GAP_MS = 5_000L          // ilgesnė pertrauka tarp FIX epochų – naujas taškas
    private const val KEEP = 20

    class Pt(val startMs: Long) {
        var endMs = startMs; var n = 0
        var lat = 0.0; var lon = 0.0; var h = 0.0; var bestAcc = 99.0; var sumAcc = 0.0
        val acc get() = if (n > 0) sumAcc / n / kotlin.math.sqrt(n.toDouble()).coerceAtMost(3.0) else 99.0
    }

    @Volatile private var dir: File? = null
    private val pts = ArrayList<Pt>()
    @Volatile var version = 0
        private set

    fun init(filesDir: File) { dir = filesDir }

    @Synchronized fun onFix(s: PositionSolver.Solution, ms: Long = System.currentTimeMillis()) {
        var p = pts.lastOrNull()
        if (p == null || ms - p.endMs > GAP_MS) { p = Pt(ms); pts.add(p); while (pts.size > KEEP) pts.removeAt(0) }
        p.n++; p.endMs = ms
        p.lat += (s.lat - p.lat) / p.n; p.lon += (s.lon - p.lon) / p.n; p.h += (s.h - p.h) / p.n
        p.sumAcc += s.hacc; p.bestAcc = minOf(p.bestAcc, s.hacc)
        version++
        try {
            val f = File(dir ?: return, FILE)
            val head = !f.exists()
            f.appendText((if (head) "laikas_ms,taskas,platuma,ilguma,aukstis_m,sigma_h_m,palydovu\n" else "") +
                "%d,%d,%.8f,%.8f,%.3f,%.3f,%d\n".format(Locale.US, ms, pts.size, s.lat, s.lon, s.h, s.hacc, s.sats))
        } catch (_: Exception) {}
    }

    @Synchronized fun last(k: Int = 2): List<Pt> = pts.takeLast(k).reversed()

    fun line(p: Pt): String {
        val f = java.text.SimpleDateFormat("HH:mm:ss", Locale.US)
        return "%.8f, %.8f".format(Locale.US, p.lat, p.lon) +
            "  h %.2f m · ±%.2f m · %d ep. · %s–%s".format(Locale.US, p.h, maxOf(p.acc, 0.01), p.n, f.format(java.util.Date(p.startMs)), f.format(java.util.Date(p.endMs)))
    }

    /** Tekstas kopijavimui: tik koordinatės (po vieną tašką eilutėje), kad tiktų kitoms programoms. */
    fun copyText(k: Int = 2): String = last(k).joinToString("\n") { "%.8f, %.8f".format(Locale.US, it.lat, it.lon) }

    fun text(k: Int = 2): String {
        val l = last(k)
        if (l.isEmpty()) return "RTK FIX taškų dar nėra (atsiras čia, kai tik bus FIX)"
        return "RTK FIX taškai (naujausias viršuje; failas $FILE):\n" + l.mapIndexed { i, p -> "${i + 1}) " + line(p) }.joinToString("\n")
    }
}
