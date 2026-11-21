package lt.example.gnss

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kosminiai orai ir dangus gyvai (nemokami šaltiniai be rakto):
 *  - NOAA SWPC: pašvaistės ovalas (OVATION, kas 5 min.), saulės vėjas (DSCOVR/ACE: greitis, tankis, Bz), GOES rentgeno srautas
 *    (žybsnių klasė), Saulės dėmių skaičius (25-asis ciklas);
 *  - NASA DONKI: žybsnių vieta Saulės diske;
 *  - NASA JPL Horizons: Parker Solar Probe ir Solar Orbiter vietos;
 *  - Celestrak: Tarptautinės kosminės stoties (TKS) orbitos elementai → praskridimai virš stebėtojo;
 *  - užtemimų sąrašas (NASA katalogas, laikai apytiksliai ±10 min.).
 * Duomenys atsisiunčiami foniniu srautu; piešimas – MainActivity (GlobeRenderer).
 */
object SpaceLive {
    @Volatile var enabled = true

    // ---------------- pašvaistė ----------------
    /** Tikimybė 0..100 % tinkleliu 360 × 181: indeksas (lat + 90) · 360 + lon (0..359, rytų ilguma). */
    @Volatile var aurora: FloatArray? = null; private set
    @Volatile var auroraTime = ""; private set
    @Volatile var auroraVer = 0; private set

    fun parseAurora(json: String): FloatArray? {
        val o = org.json.JSONObject(json)
        val c = o.optJSONArray("coordinates") ?: return null
        val g = FloatArray(360 * 181)
        for (i in 0 until c.length()) {
            val p = c.optJSONArray(i) ?: continue
            val lon = ((p.optDouble(0).toInt() % 360) + 360) % 360
            val lat = p.optDouble(1).toInt().coerceIn(-90, 90)
            g[(lat + 90) * 360 + lon] = p.optDouble(2).toFloat()
        }
        auroraTime = o.optString("Forecast Time", o.optString("Observation Time", ""))
        return g
    }

    fun auroraAt(g: FloatArray, latDeg: Double, lonDeg: Double): Float {
        val la = Math.round(latDeg).toInt().coerceIn(-90, 90); val lo = ((Math.round(lonDeg).toInt() % 360) + 360) % 360
        return g[(la + 90) * 360 + lo]
    }

    /**
     * Ar pašvaistė gali matytis iš stebėtojo vietos: didžiausia tikimybė ruože į ašigalio pusę iki ~9° platumos (≈ 1000 km –
     * tiek toli 100–300 km aukštyje švytinti pašvaistė dar matoma virš horizonto) ir ±15° ilgumos.
     */
    fun auroraNear(g: FloatArray, latDeg: Double, lonDeg: Double): Float {
        var m = 0f; val sgn = if (latDeg >= 0) 1 else -1
        for (dl in 0..9) for (dlo in -15..15) m = maxOf(m, auroraAt(g, latDeg + sgn * dl, lonDeg + dlo))
        return m
    }

    // ---------------- saulės vėjas ----------------
    @Volatile var windSpeed = Double.NaN; private set
    @Volatile var windDensity = Double.NaN; private set
    @Volatile var bz = Double.NaN; private set
    @Volatile var bt = Double.NaN; private set
    @Volatile var windTime = ""; private set

    // NOAA „products“ formatas – masyvas, kurio pirma eilutė antraštės, toliau reikšmės; grąžina paskutinę pilną eilutę pagal stulpelių vardus
    fun lastRow(json: String, vararg cols: String): DoubleArray? {
        val a = org.json.JSONArray(json); if (a.length() < 2) return null
        val h = a.getJSONArray(0); val idx = cols.map { c -> (0 until h.length()).firstOrNull { h.getString(it) == c } ?: return null }
        for (i in a.length() - 1 downTo 1) {
            val r = a.getJSONArray(i)
            val v = idx.map { r.optString(it, "").toDoubleOrNull() ?: Double.NaN }
            if (v.none { it.isNaN() }) { windTime = r.optString(0); return v.toDoubleArray() }
        }
        return null
    }

    /** Kelionės laikas iki Žemės (val.) dabartiniu greičiu. */
    fun travelHours(speedKms: Double): Double = 149.6e6 / speedKms / 3600.0

    // ---------------- žybsniai ----------------
    @Volatile var xrayFlux = Double.NaN; private set           // W/m², 0,1–0,8 nm
    class Flare(val peakMs: Long, val cls: String, val lat: Double, val lonW: Double)
    @Volatile var flares: List<Flare> = emptyList(); private set

    fun xrayClass(flux: Double): String {
        if (flux.isNaN() || flux <= 0) return "?"
        val (l, b) = when { flux >= 1e-4 -> "X" to 1e-4; flux >= 1e-5 -> "M" to 1e-5; flux >= 1e-6 -> "C" to 1e-6; flux >= 1e-7 -> "B" to 1e-7; else -> "A" to 1e-8 }
        return "%s%.1f".format(Locale.US, l, flux / b)
    }

    /** Vieta „N15W34“ → (platuma, vakarų ilguma nuo centrinio dienovidinio), °; rytai – neigiama. */
    fun parseLoc(s: String): Pair<Double, Double>? {
        val m = Regex("([NS])(\\d{1,2})([EW])(\\d{1,3})").find(s.uppercase()) ?: return null
        val la = m.groupValues[2].toDouble() * (if (m.groupValues[1] == "S") -1 else 1)
        val lo = m.groupValues[4].toDouble() * (if (m.groupValues[3] == "E") -1 else 1)
        return la to lo
    }

    fun parseFlares(json: String): List<Flare> {
        val a = org.json.JSONArray(json); val out = ArrayList<Flare>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val t = try { java.time.OffsetDateTime.parse(o.optString("peakTime").let { if (it.endsWith("Z")) it else it + "Z" }.replace("ZZ", "Z")).toInstant().toEpochMilli() } catch (_: Exception) { continue }
            val loc = parseLoc(o.optString("sourceLocation", "")) ?: (0.0 to 0.0)
            out.add(Flare(t, o.optString("classType", "?"), loc.first, loc.second))
        }
        return out.sortedBy { it.peakMs }
    }

    // ---------------- Saulės ciklas ----------------
    @Volatile var ssn = Double.NaN; private set
    @Volatile var ssnSmoothed = Double.NaN; private set
    @Volatile var ssnMonth = ""; private set
    /** Mėnesio dėmių skaičius (paskutiniai ~13 metų) grafikui ataskaitoje. */
    @Volatile var ssnSeries: List<Pair<String, Double>> = emptyList(); private set

    fun parseCycle(json: String) {
        val a = org.json.JSONArray(json); val s = ArrayList<Pair<String, Double>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val v = o.optDouble("ssn", Double.NaN); if (v.isNaN()) continue
            s.add(o.optString("time-tag") to v)
            ssn = v; ssnMonth = o.optString("time-tag"); val sm = o.optDouble("smoothed_ssn", Double.NaN); if (!sm.isNaN() && sm > 0) ssnSmoothed = sm
        }
        ssnSeries = s.takeLast(160)
    }

    // ---------------- zondai prie Saulės (JPL Horizons) ----------------
    /** Kūnas: vardas, padėtis nuo Saulės (AU, pusiaujo J2000), kelias (−60…+30 d. kas parą). */
    class Probe(val name: String, val now: DoubleArray, val path: List<DoubleArray>)
    @Volatile var probes: List<Probe> = emptyList(); private set

    fun parseHorizons(txt: String): List<DoubleArray> {
        val soe = txt.indexOf("\$\$SOE"); val eoe = txt.indexOf("\$\$EOE"); if (soe < 0 || eoe < soe) return emptyList()
        val num = "([-+]?\\d*\\.?\\d+(?:[Ee][-+]?\\d+)?)"
        val re = Regex("X\\s*=\\s*$num\\s+Y\\s*=\\s*$num\\s+Z\\s*=\\s*$num")
        return re.findAll(txt.substring(soe, eoe)).map { m -> doubleArrayOf(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDouble()) }.toList()
    }

    /** Atstumas nuo Saulės centro (Saulės spinduliais). */
    fun rSun(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) * Donki.AU_RSUN

    // ---------------- TKS (Celestrak OMM) ----------------
    class Elements(val epochMs: Long, val n: Double /* rev/d */, val e: Double, val i: Double, val raan: Double, val argp: Double, val m: Double)
    @Volatile var iss: Elements? = null; private set
    class Pass(val startMs: Long, val maxMs: Long, val endMs: Long, val maxEl: Double, val azStart: Double, val azEnd: Double, val visible: Boolean)
    @Volatile var passes: List<Pass> = emptyList(); private set

    fun parseOmm(json: String): Elements? {
        val a = org.json.JSONArray(json); val o = a.optJSONObject(0) ?: return null
        val ep = try { java.time.LocalDateTime.parse(o.getString("EPOCH")).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { return null }
        return Elements(ep, o.getDouble("MEAN_MOTION"), o.getDouble("ECCENTRICITY"), Math.toRadians(o.getDouble("INCLINATION")),
            Math.toRadians(o.getDouble("RA_OF_ASC_NODE")), Math.toRadians(o.getDouble("ARG_OF_PERICENTER")), Math.toRadians(o.getDouble("MEAN_ANOMALY")))
    }

    private const val MU = 398600.4418; private const val RE = 6378.137; private const val J2 = 1.08263e-3

    /**
     * TKS padėtis (km, pusiaujo sistema – TEME ≈ dienos lygiadienio): Keplerio orbita + J2 sekuliarūs pokyčiai (mazgo ir perigėjaus slinkimas).
     * Tikslumas – keli km per parą nuo epochos (be atmosferos stabdymo); praskridimams pakanka.
     */
    fun issEci(el: Elements, ms: Long): DoubleArray {
        val n = el.n * 2 * PI / 86400.0
        val a = Math.cbrt(MU / (n * n))
        val p = a * (1 - el.e * el.e)
        val dt = (ms - el.epochMs) / 1000.0
        val k = 1.5 * J2 * (RE / p) * (RE / p) * n
        val raan = el.raan - k * cos(el.i) * dt
        val argp = el.argp + k * (2 - 2.5 * sin(el.i) * sin(el.i)) * dt
        var m = (el.m + n * dt) % (2 * PI)
        var ea = m; repeat(8) { ea -= (ea - el.e * sin(ea) - m) / (1 - el.e * cos(ea)) }
        val nu = 2 * atan2(sqrt(1 + el.e) * sin(ea / 2), sqrt(1 - el.e) * cos(ea / 2))
        val r = a * (1 - el.e * cos(ea))
        val u = argp + nu
        val cO = cos(raan); val sO = sin(raan); val ci = cos(el.i); val si = sin(el.i); val cu = cos(u); val su = sin(u)
        return doubleArrayOf(r * (cO * cu - sO * su * ci), r * (sO * cu + cO * su * ci), r * (su * si))
    }

    /** Pusiaujo → Žemės (ECEF) pagal GMST. */
    fun eciToEcef(v: DoubleArray, ms: Long): DoubleArray {
        val g = Math.toRadians(Astro.gmstDeg(ms)); val c = cos(g); val s = sin(g)
        return doubleArrayOf(c * v[0] + s * v[1], -s * v[0] + c * v[1], v[2])
    }

    /** Aukštis ir azimutas (°) iš stebėtojo (platuma, ilguma) į ECEF tašką (km). */
    fun lookAngles(latDeg: Double, lonDeg: Double, p: DoubleArray): Pair<Double, Double> {
        val la = Math.toRadians(latDeg); val lo = Math.toRadians(lonDeg)
        val o = doubleArrayOf(RE * cos(la) * cos(lo), RE * cos(la) * sin(lo), RE * sin(la))
        val d = DoubleArray(3) { p[it] - o[it] }
        val e = -sin(lo) * d[0] + cos(lo) * d[1]
        val nn = -sin(la) * cos(lo) * d[0] - sin(la) * sin(lo) * d[1] + cos(la) * d[2]
        val u = cos(la) * cos(lo) * d[0] + cos(la) * sin(lo) * d[1] + sin(la) * d[2]
        val el = Math.toDegrees(atan2(u, sqrt(e * e + nn * nn)))
        val az = (Math.toDegrees(atan2(e, nn)) + 360) % 360
        return el to az
    }

    /** Ar TKS Saulės apšviesta (ne Žemės cilindriniame šešėlyje). */
    fun sunlit(eci: DoubleArray, ms: Long): Boolean {
        val s = Astro.sunEq(ms)
        val d = eci[0] * s[0] + eci[1] * s[1] + eci[2] * s[2]
        if (d > 0) return true
        val perp = sqrt((eci[0] * eci[0] + eci[1] * eci[1] + eci[2] * eci[2]) - d * d)
        return perp > RE
    }

    /** Praskridimai virš 10° per kitas `hours` valandas (žingsnis 20 s). visible – TKS apšviesta, o stebėtojui sutemę (Saulė < −6°). */
    fun computePasses(el: Elements, latDeg: Double, lonDeg: Double, fromMs: Long, hours: Int = 72): List<Pass> {
        val out = ArrayList<Pass>()
        var inPass = false; var st = 0L; var mx = 0L; var mxEl = -90.0; var azS = 0.0; var azE = 0.0; var vis = false
        var t = fromMs; val end = fromMs + hours * 3_600_000L
        while (t <= end) {
            val eci = issEci(el, t)
            val (e, az) = lookAngles(latDeg, lonDeg, eciToEcef(eci, t))
            if (e >= 10.0) {
                if (!inPass) { inPass = true; st = t; azS = az; mxEl = -90.0; vis = false }
                if (e > mxEl) { mxEl = e; mx = t }
                azE = az
                val sunEl = Astro.altAz(Astro.sunEq(t), latDeg, lonDeg, t).second
                if (sunlit(eci, t) && sunEl < -6.0) vis = true
            } else if (inPass) { inPass = false; out.add(Pass(st, mx, t, mxEl, azS, azE, vis)) }
            t += 20_000L
        }
        return out
    }

    // ---------------- užtemimai ----------------
    /** (laikas UTC, saulės užtemimas, aprašas, ar dalinis matomas Europoje – tik Saulės užtemimams). Laikai – didžiausios fazės, apytiksliai. */
    class Eclipse(val ms: Long, val solar: Boolean, val what: String, val europe: Boolean)
    private fun t(s: String) = java.time.Instant.parse(s).toEpochMilli()
    val eclipses: List<Eclipse> by lazy { listOf(
        Eclipse(t("2026-08-12T17:46:00Z"), true, "visiškas Saulės (Grenlandija, Islandija, Ispanija)", true),
        Eclipse(t("2026-08-28T04:13:00Z"), false, "dalinis Mėnulio", false),
        Eclipse(t("2027-02-20T23:13:00Z"), false, "pusšešėlinis Mėnulio", false),
        Eclipse(t("2027-08-02T10:07:00Z"), true, "visiškas Saulės (Ispanija, Šiaurės Afrika, Egiptas)", true),
        Eclipse(t("2028-01-12T04:13:00Z"), false, "dalinis Mėnulio", false),
        Eclipse(t("2028-07-06T18:20:00Z"), false, "dalinis Mėnulio", false),
        Eclipse(t("2028-12-31T16:52:00Z"), false, "visiškas Mėnulio", false),
        Eclipse(t("2029-06-12T04:06:00Z"), true, "dalinis Saulės (Arktis, Šiaurės Europa)", true),
        Eclipse(t("2029-06-26T03:22:00Z"), false, "visiškas Mėnulio", false),
        Eclipse(t("2029-12-20T22:42:00Z"), false, "visiškas Mėnulio", false),
    ) }

    /** Ar matomas iš (lat, lon): Saulės – Saulė virš horizonto ir užtemimas dengia Europą; Mėnulio – Mėnulis virš horizonto (≈ priešais Saulę). */
    fun eclipseVisible(e: Eclipse, latDeg: Double, lonDeg: Double): Boolean {
        val sunEl = Astro.altAz(Astro.sunEq(e.ms), latDeg, lonDeg, e.ms).second
        return if (e.solar) e.europe && sunEl > 0.0 else -sunEl > 0.0
    }

    // ---------------- atsisiuntimas ----------------
    @Volatile var status = "neatsisiųsta"; private set
    private val last = HashMap<String, Long>()
    @Volatile private var busy = false

    private fun get(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 60_000; setRequestProperty("User-Agent", "GnssApp/1.0 (Android)") }
        if (c.responseCode == 200) c.inputStream.bufferedReader().readText() else { status = "${URL(url).host}: HTTP ${c.responseCode}"; null }
    } catch (e: Exception) { status = "${URL(url).host}: ${e.javaClass.simpleName}"; null }

    private fun due(key: String, everyMs: Long, now: Long): Boolean { val l = last[key] ?: 0L; if (now - l < everyMs) return false; last[key] = now; return true }

    /** Kviečiama kas minutę; kiekvienas šaltinis atsinaujina savo dažniu. */
    fun refresh(latDeg: Double, lonDeg: Double) {
        if (!enabled || busy) return
        busy = true
        Thread {
            val now = System.currentTimeMillis()
            try {
                if (due("aur", 10 * 60_000L, now)) get("https://services.swpc.noaa.gov/json/ovation_aurora_latest.json")?.let { runCatching { parseAurora(it) }.getOrNull()?.let { g -> aurora = g; auroraVer++ } }
                if (due("wind", 5 * 60_000L, now)) {
                    get("https://services.swpc.noaa.gov/products/solar-wind/plasma-5-minute.json")?.let { runCatching { lastRow(it, "density", "speed") }.getOrNull()?.let { r -> windDensity = r[0]; windSpeed = r[1] } }
                    get("https://services.swpc.noaa.gov/products/solar-wind/mag-5-minute.json")?.let { runCatching { lastRow(it, "bz_gsm", "bt") }.getOrNull()?.let { r -> bz = r[0]; bt = r[1] } }
                }
                if (due("xray", 2 * 60_000L, now)) get("https://services.swpc.noaa.gov/json/goes/primary/xrays-6-hour.json")?.let { txt ->
                    runCatching { val a = org.json.JSONArray(txt)
                        for (i in a.length() - 1 downTo 0) { val o = a.getJSONObject(i); if (o.optString("energy") == "0.1-0.8nm") { xrayFlux = o.optDouble("flux"); break } } } }
                if (due("flr", 60 * 60_000L, now)) {
                    val f = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    get("https://kauai.ccmc.gsfc.nasa.gov/DONKI/WS/get/FLR?startDate=" + f.format(java.util.Date(now - 3 * 86_400_000L)) + "&endDate=" + f.format(java.util.Date(now + 86_400_000L)))
                        ?.let { txt -> if (txt.isBlank()) flares = emptyList() else runCatching { parseFlares(txt) }.getOrNull()?.let { flares = it } }
                }
                if (due("ssn", 24 * 3_600_000L, now)) get("https://services.swpc.noaa.gov/json/solar-cycle/observed-solar-cycle-indices.json")?.let { runCatching { parseCycle(it) } }
                if (due("probes", 12 * 3_600_000L, now)) {
                    val f = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                    val st = f.format(java.util.Date(now - 60 * 86_400_000L)); val en = f.format(java.util.Date(now + 30 * 86_400_000L))
                    val out = ArrayList<Probe>()
                    for ((id, name) in listOf("-96" to "Parker Solar Probe", "-144" to "Solar Orbiter")) {
                        val q = "https://ssd.jpl.nasa.gov/api/horizons.api?format=text&COMMAND=%27$id%27&OBJ_DATA=%27NO%27&MAKE_EPHEM=%27YES%27" +
                            "&EPHEM_TYPE=%27VECTORS%27&CENTER=%27500@10%27&START_TIME=%27$st%27&STOP_TIME=%27$en%27&STEP_SIZE=%271%20d%27" +
                            "&VEC_TABLE=%271%27&REF_PLANE=%27FRAME%27&OUT_UNITS=%27AU-D%27&CSV_FORMAT=%27NO%27"
                        get(q)?.let { txt -> val p = runCatching { parseHorizons(txt) }.getOrDefault(emptyList())
                            if (p.size >= 61) { val d0 = ((now - (now / 86_400_000L) * 86_400_000L) / 86_400_000.0)
                                val a = p[60]; val b = p[minOf(61, p.size - 1)]
                                out.add(Probe(name, DoubleArray(3) { a[it] + (b[it] - a[it]) * d0 }, p)) } }
                    }
                    if (out.isNotEmpty()) probes = out
                }
                if (due("iss", 12 * 3_600_000L, now)) get("https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=json")?.let { runCatching { parseOmm(it) }.getOrNull()?.let { iss = it } }
                val el = iss
                if (el != null && (lonDeg != 0.0 || latDeg != 0.0) && due("pass", 30 * 60_000L, now)) passes = computePasses(el, latDeg, lonDeg, now)
                if (status == "neatsisiųsta") status = "atnaujinta"
            } finally { busy = false }
        }.start()
    }

    // ---------------- tekstas ----------------
    private fun hm(ms: Long) = java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(java.util.Date(ms))
    private fun compass(az: Double) = arrayOf("Š", "ŠR", "R", "PR", "P", "PV", "V", "ŠV")[(((az + 22.5) % 360) / 45).toInt()]

    fun auroraLine(latDeg: Double, lonDeg: Double): String? {
        val g = aurora ?: return null
        val p = auroraNear(g, latDeg, lonDeg)
        return "Pašvaistė (NOAA OVATION %s): iki ~1000 km į šiaurę nuo jūsų tikimybė iki %.0f %%%s".format(Locale.US, auroraTime, p,
            when { p >= 30 -> " – didelė, žiūrėkite į šiaurę tamsoje"; p >= 10 -> " – galima žemai šiaurėje"; else -> " – mažai tikėtina" })
    }

    fun windLine(): String? = if (windSpeed.isNaN()) null else
        "Saulės vėjas (%s UTC): %.0f km/s, tankis %.1f /cm³, Bz %+.1f nT, Bt %.1f nT; iki Žemės ~%.0f val.%s".format(Locale.US,
            windTime, windSpeed, windDensity, bz, bt, travelHours(windSpeed),
            if (!bz.isNaN() && bz <= -10) " – Bz stipriai neigiamas: pašvaistei ir GNSS trikdžiams palanku" else if (!bz.isNaN() && bz < -5) " – Bz neigiamas" else "")

    fun flareLine(now: Long = System.currentTimeMillis()): String = buildString {
        append("Rentgeno srautas (GOES): ${xrayClass(xrayFlux)}")
        flares.lastOrNull()?.let { f -> append("; paskutinis žybsnis %s %s UTC (%s%02.0f%s%02.0f)".format(Locale.US, f.cls, hm(f.peakMs),
            if (f.lat >= 0) "N" else "S", abs(f.lat), if (f.lonW >= 0) "W" else "E", abs(f.lonW))) }
    }

    fun text(latDeg: Double, lonDeg: Double, now: Long = System.currentTimeMillis()): String = buildString {
        appendLine("=== KOSMINIAI ORAI GYVAI ===")
        if (!enabled) { appendLine("Išjungta"); return@buildString }
        appendLine("Šaltiniai: $status")
        auroraLine(latDeg, lonDeg)?.let { appendLine(it) }
        windLine()?.let { appendLine(it) }
        appendLine(flareLine(now))
        if (!ssn.isNaN()) appendLine("Saulės dėmės (%s): %.0f, išlygintas %.0f – 25-asis ciklas".format(Locale.US, ssnMonth, ssn, ssnSmoothed))
        probes.forEach { p -> appendLine("%s: %.1f Saulės spindulių (%.3f AU) nuo Saulės".format(Locale.US, p.name, rSun(p.now), rSun(p.now) / Donki.AU_RSUN)) }
        val vis = passes.filter { it.visible }.take(3)
        if (iss != null) appendLine(if (vis.isEmpty()) "TKS: matomų praskridimų per 3 paras nėra" else "TKS matoma: " + vis.joinToString("; ") {
            "%s, iki %.0f°, iš %s į %s".format(Locale.US, hm(it.startMs), it.maxEl, compass(it.azStart), compass(it.azEnd)) })
        eclipses.filter { it.ms > now }.take(3).forEach { e ->
            appendLine("Užtemimas %s UTC: %s – %s".format(Locale.US, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(e.ms)),
                e.what, if (eclipseVisible(e, latDeg, lonDeg)) "matomas iš čia (bent dalis)" else "iš čia nematomas")) }
    }

    /** Įspėjimai (rodomi vieną kartą): pašvaistė, stiprus žybsnis, palankus saulės vėjas, artimas TKS praskridimas. */
    private val shown = HashSet<String>()
    fun alerts(latDeg: Double, lonDeg: Double, now: Long = System.currentTimeMillis()): List<String> {
        if (!enabled) return emptyList()
        val out = ArrayList<String>()
        val dark = Astro.altAz(Astro.sunEq(now), latDeg, lonDeg, now).second < -6.0
        aurora?.let { g -> val p = auroraNear(g, latDeg, lonDeg)
            if (p >= 10 && dark && shown.add("aur" + now / 21_600_000L)) out.add("🌌 Šiąnakt gali matytis pašvaistė šiaurėje (iki %.0f %%)".format(Locale.US, p)) }
        flares.lastOrNull()?.let { f -> if ((f.cls.startsWith("X") || f.cls.startsWith("M")) && now - f.peakMs < 3_600_000L && shown.add("flr" + f.peakMs))
            out.add("☀ Saulės žybsnis %s prieš %d min.".format(Locale.US, f.cls, (now - f.peakMs) / 60_000L)) }
        if (!bz.isNaN() && bz <= -10 && windSpeed >= 500 && shown.add("bz" + now / 10_800_000L))
            out.add("Saulės vėjas %.0f km/s, Bz %+.0f nT – GNSS gali šokinėti, galima pašvaistė".format(Locale.US, windSpeed, bz))
        passes.firstOrNull { it.visible && it.startMs > now && it.startMs - now < 15 * 60_000L }?.let { p ->
            if (shown.add("iss" + p.startMs)) out.add("🛰 TKS praskris po %d min.: iš %s, iki %.0f°".format(Locale.US, (p.startMs - now) / 60_000L, compass(p.azStart), p.maxEl)) }
        return out
    }
}
