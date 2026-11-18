package lt.example.gnss

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Žinomas prietaiso aukštis („Žinomas aukštis virš žemės“, pvz. 42 m krano kabinoje) kaip etalonas:
 *  - prietaiso aukštis virš jūros = žinomas aukštis virš jūros arba žemės paviršius (Copernicus DEM per Open-Meteo) + aukštis virš žemės;
 *  - elipsoidinis aukštis h0 = H + N (geoidas) – RTK filtrui pridedamas pseudo-matavimas „aukštis = h0“
 *    (fiksuotas aukštis sumažina nežinomųjų skaičių ir pagerina horizontalią padėtį, ypač prastos geometrijos kabinoje);
 *  - stovint padėtis vidurkinama (svoriai 1/σ²), kartu – Android aukščio poslinkis nuo etalono.
 */
object KnownHeight {
    @Volatile var agl: Double? = null; private set
    @Volatile var msl: Double? = null; private set
    @Volatile var ground = Double.NaN; private set
    @Volatile private var gLat = Double.NaN; @Volatile private var gLon = Double.NaN
    @Volatile var demStatus = ""; private set
    @Volatile private var busy = false
    @Volatile private var lastLat = Double.NaN; @Volatile private var lastLon = Double.NaN

    @Volatile private var appCtx: Context? = null

    fun load(ctx: Context) {
        appCtx = ctx.applicationContext
        val p = ctx.getSharedPreferences("known_ref", Context.MODE_PRIVATE)
        fun d(k: String) = p.getString(k, "")?.trim()?.replace(',', '.')?.toDoubleOrNull()
        val a = d("agl")?.takeIf { it in -100.0..1000.0 }; val m = d("msl")?.takeIf { it in -500.0..9000.0 }
        val g = p.getFloat("g_msl", Float.NaN).toDouble()
        if (a != agl || m != msl || !(g == mGround || (g.isNaN() && mGround.isNaN()))) { dhAvg = Double.NaN; dhCnt = 0; conflict = false; slowM = Double.NaN; slowM2 = Double.NaN; dhSd = Double.NaN }   // nauja vertė – patikra iš naujo
        agl = a; msl = m
        mGround = g; mLat = p.getFloat("g_lat", Float.NaN).toDouble(); mLon = p.getFloat("g_lon", Float.NaN).toDouble()
        mSigma = p.getFloat("g_sig", 3f).toDouble(); mSrc = p.getString("g_src", "") ?: ""
    }

    // ---- „Čia žemė“: žemės aukštis, išmatuotas pačios programos (vietoj interneto žemėlapio) ----
    @Volatile private var mGround = Double.NaN
    @Volatile private var mLat = Double.NaN; @Volatile private var mLon = Double.NaN
    @Volatile private var mSigma = 3.0; @Volatile private var mSrc = ""
    /** Išmatuota žemė, jei ji šioje vietoje (≤ 300 m). */
    private fun groundNear(): Double? = mGround.takeIf { !it.isNaN() &&
        (lastLat.isNaN() || PositionSolver.horizDist(lastLat, lastLon, mLat, mLon) < 300.0) }
    val hasMeasuredGround get() = groundNear() != null
    /** Žemės paviršiaus aukštis virš jūros: išmatuotas čia, kitaip – interneto žemėlapis (jei neįtartinas). */
    fun groundMsl(): Double? = groundNear() ?: ground.takeIf { !it.isNaN() && !demSuspect }

    @Volatile var gMeasuring = false
        private set
    /** Matavimo eiga 0..1. */
    @Volatile var gProgress = 0f
        private set
    @Volatile var gResult = ""
        private set
    /** Matuojant – dabartinis vidurkis (žemė virš jūros, m); NaN – dar nėra. */
    @Volatile var gLiveMsl = Double.NaN
        private set
    /** Išmatuota žemė čia (virš jūros, m) arba null. */
    fun measuredGround(): Double? = groundNear()
    private const val G_MS = 180_000L
    private val gH = ArrayList<Double>(); private val gFix = ArrayList<Double>()
    private var gStart = 0L; private var gLat0 = Double.NaN; private var gLon0 = Double.NaN; private var gWalk = 0

    /**
     * Matuoti galima iš bet kurio aukšto: jei laukelyje „aukštis virš žemės“ įrašyta (pvz. 25 m – 9 aukštas, 45 m – krano kabina),
     * ta vertė (užfiksuota matavimo pradžioje) atimama – įsimenama žemė po tavimi. Ant pačios žemės laukelyje turi būti 0 arba tuščia.
     */
    @Volatile var gAgl = 0.0
        private set
    @Synchronized fun startGround(now: Long) { gH.clear(); gFix.clear(); gStart = now; gWalk = 0; gLat0 = Double.NaN; gProgress = 0f; gLiveMsl = Double.NaN
        gAgl = agl ?: 0.0; gMeasuring = true; gResult = "Žemė matuojama…" }
    @Synchronized fun cancelGround(why: String) { if (!gMeasuring) return; gMeasuring = false; gProgress = 0f; gResult = "Žemės matavimas nebaigtas: $why" }

    /** Išmatuotą žemę pamiršti – vėl naudojamas interneto žemėlapis. */
    @Synchronized fun clearGround() {
        cancelGround("atšaukta")
        appCtx?.getSharedPreferences("known_ref", Context.MODE_PRIVATE)?.edit()?.remove("g_msl")?.remove("g_lat")?.remove("g_lon")
            ?.remove("g_sig")?.remove("g_src")?.apply()
        mGround = Double.NaN; dhAvg = Double.NaN; dhCnt = 0; conflict = false; slowM = Double.NaN; slowM2 = Double.NaN; dhSd = Double.NaN; gResult = "Išmatuota žemė pašalinta – naudojamas interneto žemėlapis"
    }

    /**
     * Aukščio mėginys matuojant žemę (elipsoidinis, m): programos padėtis be aukščio apribojimo kas sekundę arba RTK FIX.
     * Pajudėjus (žingsniai) ar nutolus > 25 m – matavimas nutraukiamas. Baigiama po 3 min. (≥ 100 mėg.) arba po 30 FIX.
     * Grąžina true, kai baigta sėkmingai.
     */
    @Synchronized fun groundSample(now: Long, lat: Double, lon: Double, hEll: Double, fix: Boolean, walking: Boolean): Boolean {
        if (!gMeasuring) return false
        if (gLat0.isNaN()) { gLat0 = lat; gLon0 = lon }
        // nuėjus (žingsniai ≥ 5 s iš eilės): jei jau ≥ 60 mėginių – baigiama su turimais, kitaip nutraukiama.
        // Pavienių padėties šuolių nežiūrima – programos padėtis be glodinimo kartais peršoka dešimtis metrų.
        gWalk = if (walking) gWalk + 1 else 0
        if (gWalk >= 5) {
            if (gFix.size + gH.size >= 60) return finishGround(lat, lon)
            cancelGround("pajudėjote per anksti"); return false
        }
        if (walking) return false
        if (fix) gFix.add(hEll) else gH.add(hEll)
        gProgress = ((now - gStart).toFloat() / G_MS).coerceIn(0f, 1f)
        Geoid.undulation(lat, lon)?.let { n ->
            val l = (if (gFix.size >= 5) gFix else gH).sorted()
            gLiveMsl = l[l.size / 2] - n - gAgl
        }
        if (gFix.size >= 30 || (now - gStart >= G_MS && gH.size >= 100)) return finishGround(lat, lon)
        return false
    }

    private fun finishGround(lat: Double, lon: Double): Boolean {
        val useFix = gFix.size >= 30 || (gFix.size >= 10 && gFix.size * 3 >= gH.size)
        val l = (if (useFix) gFix else gH).sorted()
        val med = l[l.size / 2]
        val mad = l.map { abs(it - med) }.sorted()[l.size / 2].coerceAtLeast(0.5)
        val ins = l.filter { abs(it - med) <= 3 * mad }
        val mean = ins.average()
        val sd = sqrt(ins.sumOf { (it - mean) * (it - mean) } / ins.size)
        // gretimų sekundžių klaidos koreliuotos (~30 s): nepriklausomų mėginių ~ n/30
        val sig = if (useFix) 0.1 else maxOf(2.0, sd / sqrt(maxOf(1.0, ins.size / 30.0)))
        // per didelė sklaida (kambaryje, kabinoje: atspindžiai, užstoti aukšti palydovai) – toks matavimas blogesnis už žemėlapį, neįsimenamas
        if (!useFix && sig > 6.0) { cancelGround("per didelė sklaida (σ ≈ %.0f m) – čia patikimai neišmatuojama, matuokite lauke po atviru dangumi".format(Locale.US, sig)); return false }
        val n = Geoid.undulation(lat, lon) ?: run { cancelGround("nėra geoido"); return false }
        val g = mean - n - gAgl
        val src = (if (useFix) "RTK FIX, ${ins.size} mėg." else "programos padėtis, ${ins.size} mėg. per ${(G_MS / 60_000)} min.") +
            (if (gAgl != 0.0) "; matuota %.1f m virš žemės – atimta".format(Locale.US, gAgl) else "")
        appCtx?.getSharedPreferences("known_ref", Context.MODE_PRIVATE)?.edit()
            ?.putFloat("g_msl", g.toFloat())?.putFloat("g_lat", lat.toFloat())?.putFloat("g_lon", lon.toFloat())
            ?.putFloat("g_sig", sig.toFloat())?.putString("g_src", src)?.apply()
        mGround = g; mLat = lat; mLon = lon; mSigma = sig; mSrc = src
        dhAvg = Double.NaN; dhCnt = 0; conflict = false; slowM = Double.NaN; slowM2 = Double.NaN; dhSd = Double.NaN
        gMeasuring = false; gProgress = 1f
        gResult = "Žemė išmatuota: %.1f m virš jūros (%s, σ ≈ %.1f m)".format(Locale.US, g, src, sig)
        return true
    }

    /**
     * Planšetė be barometro nežino, ar dar esama įvesto aukščio vietoje. Kai palydovų pakanka (≥ 6), programa skaičiuoja ir padėtį
     * be aukščio apribojimo: jei ji ~20 s vidutiniškai > 30 m kitame aukštyje (pvz. nulipus nuo krano), įvestas aukštis nenaudojamas,
     * kol vėl sutaps (< 15 m).
     */
    @Volatile var conflict = false
        private set
    @Volatile var freeDh = Double.NaN
        private set
    private var dhAvg = Double.NaN; private var dhCnt = 0
    /**
     * vdop – kiek palydovų išsidėstymas leidžia pasitikėti aukščiu be apribojimo. Kabinoje stogas užstoja aukštus palydovus:
     * VDOP didelis, aukštis be apribojimo gali klysti ir 50 m – tada nesutapimas nieko nereiškia (ten žinomas aukštis labiausiai reikalingas).
     * Riba: max(30 m, 3 · 6 m · VDOP, 2,5 · aukščio sklaida per ~50 s), vidurkinant ~20 s; vėl naudojama, kai nesutapimas < 0,7 ribos.
     * Sklaida svarbi kabinoje: ten aukštis be apribojimo šokinėja ±30–50 m net esant neblogam VDOP, ir tikras įvestas aukštis
     * (pvz. išmatuota žemė + kabina) būdavo atmetamas. Ant žemės po atviru dangumi sklaida maža – pamirštas 45 m aukštis vis tiek pagaunamas.
     */
    @Volatile var dhSd = Double.NaN
        private set
    private var slowM = Double.NaN; private var slowM2 = Double.NaN
    fun limit(): Double = maxOf(30.0, 18.0 * (if (vdopAvg.isNaN()) 1.5 else vdopAvg), if (dhSd.isNaN()) 0.0 else 2.5 * dhSd)
    @Volatile var vdopAvg = Double.NaN
        private set
    @Synchronized fun checkFree(lat: Double, lon: Double, h: Double, vdop: Double = 1.5) {
        val h0 = h0(lat, lon) ?: return
        val dh = h - h0
        dhAvg = if (dhAvg.isNaN()) dh else dhAvg * 0.95 + dh * 0.05
        val v = vdop.coerceIn(0.5, 30.0)
        vdopAvg = if (vdopAvg.isNaN()) v else vdopAvg * 0.95 + v * 0.05
        slowM = if (slowM.isNaN()) dh else slowM * 0.98 + dh * 0.02
        slowM2 = if (slowM2.isNaN()) dh * dh else slowM2 * 0.98 + dh * dh * 0.02
        if (dhCnt >= 10) dhSd = sqrt((slowM2 - slowM * slowM).coerceAtLeast(0.0))
        dhCnt++; freeDh = dhAvg
        val lim = limit()
        if (dhCnt >= 20 && abs(dhAvg) > lim) conflict = true
        else if (abs(dhAvg) < lim * 0.7) conflict = false
    }

    /** Aukščio apribojimas programos padėčiai (vieta lat, lon – paskutinė žinoma) arba null. */
    fun constraint(lat: Double, lon: Double): Sp3V5.HCon? {
        if (!active) return null
        val h0 = h0(lat, lon) ?: return null
        return Sp3V5.HCon(h0, sigma(), PositionSolver.llhToEcef(Math.toRadians(lat), Math.toRadians(lon), h0))
    }
    /** Paskutinė Android vieta (kai programa dar neturi savo padėties). */
    fun lastLatLon(): Pair<Double, Double>? = if (lastLat.isNaN()) null else lastLat to lastLon

    /**
     * Copernicus DEM yra paviršiaus modelis (su pastatais, medžiais) – gali būti klaidingas keliolika metrų.
     * Jei ilgas vidurkis stovint (≥ 300 fiksų) rodo, kad GNSS aukštis nuo DEM etalono skiriasi > 10 m, DEM etalonas nenaudojamas.
     */
    @Volatile var demSuspect = false
        private set
    val active get() = (msl != null || (agl != null && groundMsl() != null)) && !conflict

    /** Prietaiso aukštis virš jūros, m. */
    fun deviceMsl(): Double? = msl ?: agl?.let { a -> groundMsl()?.let { it + a } }

    /**
     * σ: įvestas aukštis virš jūros – 2 m (dažnai jis pats išmatuotas ±3–5 m; 0,3 m buvo per griežta ir kabinoje „išmušdavo“ RTK filtrą);
     * išmatuota žemė + aukštis virš žemės – žemės σ; DEM (Copernicus GLO-90) + aukštis virš žemės – 2 m.
     */
    fun sigma() = if (msl != null) 2.0 else groundNear()?.let { sqrt(mSigma * mSigma + 0.3 * 0.3) } ?: 2.0

    /** Elipsoidinis aukštis h0 vietoje (lat, lon), m. */
    fun h0(lat: Double, lon: Double): Double? {
        val h = deviceMsl() ?: return null
        val n = Geoid.undulation(lat, lon) ?: return null
        return h + n
    }

    /** Žemės paviršiaus aukštis iš DEM (kai įvestas tik aukštis virš žemės); persiunčiama pasislinkus > 100 m. */
    private fun refreshDem(lat: Double, lon: Double) {
        if (agl == null || msl != null || busy || groundNear() != null) return
        if (!gLat.isNaN() && abs(lat - gLat) * 111_320 < 100 && abs(lon - gLon) * 111_320 * cos(Math.toRadians(lat)) < 100) return
        busy = true
        Thread {
            try {
                val c = (URL("https://api.open-meteo.com/v1/elevation?latitude=%.5f&longitude=%.5f".format(Locale.US, lat, lon)).openConnection() as HttpURLConnection)
                    .apply { connectTimeout = 15_000; readTimeout = 30_000 }
                if (c.responseCode == 200) {
                    val e = org.json.JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("elevation").getDouble(0)
                    ground = e; gLat = lat; gLon = lon
                    demStatus = "žemės paviršius %.1f m virš jūros (Copernicus DEM, Open-Meteo)".format(Locale.US, e)
                } else demStatus = "žemės aukštis: HTTP ${c.responseCode}"
            } catch (e: Exception) { demStatus = "žemės aukštis: ${e.javaClass.simpleName}" }
            finally { busy = false }
        }.start()
    }

    /** RTK filtrui: (H eilutė – vietinis zenitas, inovacija h0 − h(x), σ) arba null. x – ECEF, m. */
    fun pseudo(x: DoubleArray): Triple<DoubleArray, Double, Double>? {
        if (!active) return null
        val llh = PositionSolver.ecefToLlh(x[0], x[1], x[2])
        val lat = Math.toDegrees(llh[0]); val lon = Math.toDegrees(llh[1])
        val h0 = h0(lat, lon) ?: return null
        val v = h0 - llh[2]
        if (abs(v) > 100.0) return null            // filtras dar nesuartėjęs arba klaidingas įvedimas
        val up = doubleArrayOf(cos(llh[0]) * cos(llh[1]), cos(llh[0]) * sin(llh[1]), sin(llh[0]))
        return Triple(up, v, sigma())
    }

    // ---- vidurkinimas stovint ----
    private var n = 0; private var sw = 0.0; private var sLat = 0.0; private var sLon = 0.0
    private var sLat2 = 0.0; private var sLon2 = 0.0; private var dhSum = 0.0; private var dhN = 0
    private var t0 = 0L

    /** Kiekvienam Android fiksui. Vidurkis atstatomas, kai judama (> 0,5 m/s) arba nutolstama > 30 m nuo vidurkio. */
    @Synchronized fun onFix(ctx: Context, lat: Double, lon: Double, hEll: Double?, speed: Double, acc: Double, ms: Long) {
        load(ctx); refreshDem(lat, lon); lastLat = lat; lastLon = lon
        if (speed > 0.5) { reset(); return }
        if (n > 0) {
            val mLat = sLat / sw; val mLon = sLon / sw
            val d = sqrt(((lat - mLat) * 111_320).let { it * it } + ((lon - mLon) * 111_320 * cos(Math.toRadians(lat))).let { it * it })
            if (d > 30.0) reset()
        }
        if (n == 0) t0 = ms
        val w = 1.0 / (acc.coerceAtLeast(0.5) * acc.coerceAtLeast(0.5))
        n++; sw += w; sLat += w * lat; sLon += w * lon; sLat2 += w * lat * lat; sLon2 += w * lon * lon
        val h0 = h0(lat, lon)
        if (hEll != null && h0 != null) { dhSum += hEll - h0; dhN++ }
        if (msl == null && dhN >= 300) demSuspect = abs(dhSum / dhN) > 10.0
    }

    @Synchronized fun reset() { n = 0; sw = 0.0; sLat = 0.0; sLon = 0.0; sLat2 = 0.0; sLon2 = 0.0; dhSum = 0.0; dhN = 0 }

    @Synchronized fun text(nowMs: Long = System.currentTimeMillis()): String = buildString {
        if (agl == null && msl == null) return "Žinomas aukštis: neįvestas"
        append("Žinomas aukštis: ")
        agl?.let { append("virš žemės %.2f m".format(Locale.US, it)) }
        msl?.let { append((if (agl != null) ", " else "") + "virš jūros %.2f m".format(Locale.US, it)) }
        val dm = deviceMsl()
        if (dm != null) append("; prietaisas %.2f m virš jūros (σ %.1f m)".format(Locale.US, dm, sigma()))
        if (!lastLat.isNaN()) h0(lastLat, lastLon)?.let { append(", elipsoidinis %.2f m".format(Locale.US, it)) }
        if (msl == null) groundNear()?.let { append("; žemė išmatuota čia: %.1f m virš jūros (%s)".format(Locale.US, it, mSrc)) }
            ?: run { if (demStatus.isNotEmpty()) append("; $demStatus") }
        if (gResult.isNotEmpty()) append("; $gResult")
        if (!freeDh.isNaN()) append("; programos padėtis be aukščio %+.0f m nuo įvesto (VDOP %.1f, aukščio sklaida ±%.0f m, riba ±%.0f m)".format(Locale.US, freeDh, vdopAvg, dhSd, limit()))
        append(when {
            conflict -> "; NENAUDOJAMAS: programos padėtis per toli kitame aukštyje (nulipote?) – pataisykite arba ištrinkite"
            active -> "; naudojamas programos padėtyje ir RTK filtre"
            demSuspect -> "; NENAUDOJAMAS: GNSS aukštis nuo DEM etalono skiriasi > 10 m (DEM su pastatais ar netikslus, arba neteisingas aukštis virš žemės) – įveskite aukštį virš jūros"
            else -> "; dar nenaudojamas (laukiama žemės aukščio)"
        })
        if (n >= 5) {
            val mLat = sLat / sw; val mLon = sLon / sw
            val vLat = (sLat2 / sw - mLat * mLat).coerceAtLeast(0.0); val vLon = (sLon2 / sw - mLon * mLon).coerceAtLeast(0.0)
            val sd = sqrt(vLat * 111_320.0 * 111_320.0 + vLon * (111_320.0 * cos(Math.toRadians(mLat))).let { it * it })
            append("\nVidurkis stovint: %.8f, %.8f; %d fiksų per %.0f min., sklaida %.2f m, vidurkio σ ≈ %.2f m".format(Locale.US,
                mLat, mLon, n, (nowMs - t0) / 60_000.0, sd, sd / sqrt(n.toDouble())))
            if (dhN > 0) append("; Android aukštis nuo etalono %+.2f m (vidurkis)".format(Locale.US, dhSum / dhN))
        }
    }
}
