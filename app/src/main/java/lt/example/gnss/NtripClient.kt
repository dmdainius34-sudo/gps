package lt.example.gnss

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * NTRIP klientas (NTRIP 1.0 užklausa, „ICY 200 OK“ arba HTTP 200, taip pat „chunked“ atsakymas).
 * VRS tinklui kas 10 s siunčiama GGA su dabartine vieta. Gautos RTCM žinutės įrašomos į gnss_rtcm.csv.
 * Prisijungimo duomenys – tik prietaiso nustatymuose (SharedPreferences „ntrip“).
 */
object NtripClient {
    class Cfg(val host: String, val port: Int, val mount: String, val user: String, val pass: String)

    fun cfg(ctx: Context): Cfg? {
        val p = ctx.getSharedPreferences("ntrip", Context.MODE_PRIVATE)
        val host = p.getString("host", "")!!.trim(); val mount = p.getString("mount", "")!!.trim()
        if (host.isEmpty() || mount.isEmpty()) return null
        return Cfg(host, p.getInt("port", 2101), mount, p.getString("user", "")!!, p.getString("pass", "")!!)
    }

    fun enabled(ctx: Context) = ctx.getSharedPreferences("ntrip", Context.MODE_PRIVATE).getBoolean("on", false)

    @Volatile var status = "išjungta"
        private set
    @Volatile var bytes = 0L
        private set
    @Volatile var lastMsgMs = 0L
        private set
    @Volatile var base: DoubleArray? = null
        private set
    /** Fizinė bazinė stotis (1032): ID ir ECEF, m. */
    @Volatile var phys: Pair<Int, DoubleArray>? = null
        private set
    /** Pataisų (MSM) žinučių gavimo laikai – bangelėms 3D vaizde. */
    @Volatile var lastMsmMs = 0L
        private set
    val counts = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    /** Paskutinis MSM pagal sistemą: palydovų skaičius. */
    val satsBySys = java.util.concurrent.ConcurrentHashMap<Char, Int>()
    @Volatile private var thread: Thread? = null
    @Volatile private var stop = false
    @Volatile private var sock: Socket? = null

    /** pos – dabartinė vieta (platuma, ilguma, aukštis) GGA žinutei. */
    @Synchronized
    fun start(ctx: Context, pos: () -> DoubleArray?) {
        if (thread?.isAlive == true) return
        val c = cfg(ctx) ?: run { status = "nenustatyta (serveris / mount point)"; return }
        val dir = ctx.applicationContext.cacheDir
        stop = false
        thread = Thread({ loop(c, dir, pos) }, "ntrip").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stopNow() {
        stop = true
        try { sock?.close() } catch (_: Exception) {}
        thread = null
        status = "išjungta"
    }

    private fun loop(c: Cfg, dir: File, pos: () -> DoubleArray?) {
        var wait = 2000L
        while (!stop) {
            try {
                status = "jungiamasi prie ${c.host}:${c.port}/${c.mount}"
                val s = Socket(); sock = s
                s.connect(InetSocketAddress(c.host, c.port), 10_000); s.soTimeout = 30_000
                val out = s.getOutputStream(); val inp = java.io.BufferedInputStream(s.getInputStream())
                val auth = android.util.Base64.encodeToString("${c.user}:${c.pass}".toByteArray(), android.util.Base64.NO_WRAP)
                out.write(("GET /${c.mount} HTTP/1.0\r\nUser-Agent: NTRIP GnssApp/1.0\r\nAccept: */*\r\n" +
                    "Authorization: Basic $auth\r\nConnection: close\r\n\r\n").toByteArray())
                out.flush()
                // atsakymo antraštė
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n") && !(head.startsWith("ICY") && head.endsWith("\r\n")) && head.length < 4096) {
                    val ch = inp.read(); if (ch < 0) break; head.append(ch.toChar())
                }
                val first = head.lineSequence().firstOrNull() ?: ""
                if (!(first.startsWith("ICY 200") || first.matches(Regex("HTTP/1\\.[01] 200.*")))) {
                    status = "atmesta: ${first.ifEmpty { "nėra atsakymo" }}" + if (first.contains("401")) " (vartotojas / slaptažodis)" else ""
                    s.close(); sleepMs(30_000); continue
                }
                if (first.startsWith("ICY")) while (true) { // ICY: likusi antraštė iki tuščios eilutės (jei yra)
                    inp.mark(2); val a = inp.read(); val b = inp.read()
                    if (a == '\r'.code && b == '\n'.code) break
                    inp.reset(); break
                }
                val chunked = head.contains("chunked", ignoreCase = true)
                status = "prisijungta (${c.mount})"
                wait = 2000L
                val framer = Rtcm3.Framer()
                var lastGga = 0L
                val buf = ByteArray(4096)
                var chunkLeft = 0
                while (!stop) {
                    val now = System.currentTimeMillis()
                    if (now - lastGga > 10_000L) {
                        pos()?.let { p -> out.write(Rtcm3.gga(p[0], p[1], p[2], now).toByteArray()); out.flush() }
                        lastGga = now
                    }
                    val n = if (!chunked) inp.read(buf) else {
                        if (chunkLeft == 0) {
                            val line = StringBuilder()
                            while (true) { val ch = inp.read(); if (ch < 0 || ch == '\n'.code) break; if (ch != '\r'.code) line.append(ch.toChar()) }
                            if (line.isEmpty()) continue
                            chunkLeft = line.toString().trim().substringBefore(';').toIntOrNull(16) ?: 0
                            if (chunkLeft == 0) -1 else 0
                        } else {
                            val k = inp.read(buf, 0, minOf(buf.size, chunkLeft)); if (k > 0) chunkLeft -= k
                            if (chunkLeft == 0) { inp.read(); inp.read() }
                            k
                        }
                    }
                    if (n < 0) break
                    if (n == 0) continue
                    bytes += n
                    val tMs = System.currentTimeMillis()
                    for ((type, p) in framer.push(buf, n)) {
                        counts[type] = (counts[type] ?: 0) + 1
                        lastMsgMs = tMs
                        RtcmRecorder.append(dir, tMs, type, p)
                        Rtcm3.station(p)?.let { base = it }
                        Rtcm3.physStation(p)?.let { phys = it }
                        if (Rtcm3.sysOf(type) != null) lastMsmMs = tMs
                        if (Rtcm3.sysOf(type) != null) Rtcm3.msm(p)?.let { m -> satsBySys[m.sys] = m.obs.map { it.prn }.distinct().size }
                    }
                }
                s.close()
                if (!stop) status = "ryšys nutrūko, jungiamasi iš naujo"
            } catch (e: Exception) {
                if (!stop) status = "klaida: ${e.javaClass.simpleName} ${e.message ?: ""}".trim()
            }
            if (!stop) { sleepMs(wait); wait = minOf(wait * 2, 60_000L) }
        }
    }

    private fun sleepMs(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    /** Būsenos tekstas ekranui ir ataskaitai; lat/lon – atstumui iki bazės. */
    fun text(lat: Double?, lon: Double?): String = buildString {
        append("NTRIP: $status; gauta ${bytes / 1024} KB")
        if (counts.isNotEmpty()) append("; žinutės: " + counts.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}×${it.value}" })
        if (satsBySys.isNotEmpty()) append("; palydovų pataisose: " + satsBySys.entries.sortedBy { it.key }.joinToString(" ") { "${it.key} ${it.value}" })
        base?.let { b ->
            val llh = PositionSolver.ecefToLlh(b[0], b[1], b[2])
            append("; bazė %.6f, %.6f".format(Locale.US, Math.toDegrees(llh[0]), Math.toDegrees(llh[1])))
            if (lat != null && lon != null) append(", atstumas %.2f km".format(Locale.US,
                PositionSolver.horizDist(lat, lon, Math.toDegrees(llh[0]), Math.toDegrees(llh[1])) / 1000.0))
        }
        phys?.let { (id, x) ->
            val l = PositionSolver.ecefToLlh(x[0], x[1], x[2])
            append("; fizinė stotis ID $id: %.5f, %.5f".format(Locale.US, Math.toDegrees(l[0]), Math.toDegrees(l[1])))
            if (lat != null && lon != null) append(", %.1f km".format(Locale.US, PositionSolver.horizDist(lat, lon, Math.toDegrees(l[0]), Math.toDegrees(l[1])) / 1000.0))
        }
        if (lastMsgMs > 0) append("; paskutinė žinutė prieš %d s".format(Locale.US, (System.currentTimeMillis() - lastMsgMs) / 1000))
    }
}
