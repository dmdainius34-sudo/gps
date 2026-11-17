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

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("known_ref", Context.MODE_PRIVATE)
        fun d(k: String) = p.getString(k, "")?.trim()?.replace(',', '.')?.toDoubleOrNull()
        val a = d("agl")?.takeIf { it in -100.0..1000.0 }; val m = d("msl")?.takeIf { it in -500.0..9000.0 }
        if (a != agl || m != msl) { dhAvg = Double.NaN; dhCnt = 0; conflict = false }   // nauja vertė – patikra iš naujo
        agl = a; msl = m
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
    @Synchronized fun checkFree(lat: Double, lon: Double, h: Double) {
        val h0 = h0(lat, lon) ?: return
        val dh = h - h0
        dhAvg = if (dhAvg.isNaN()) dh else dhAvg * 0.95 + dh * 0.05
        dhCnt++; freeDh = dhAvg
        if (dhCnt >= 20 && abs(dhAvg) > 30.0) conflict = true
        else if (abs(dhAvg) < 15.0) conflict = false
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
    val active get() = (msl != null || (agl != null && !ground.isNaN() && !demSuspect)) && !conflict

    /** Prietaiso aukštis virš jūros, m. */
    fun deviceMsl(): Double? = msl ?: agl?.let { a -> if (ground.isNaN()) null else ground + a }

    /** σ: įvestas aukštis virš jūros – 0,3 m; DEM (Copernicus GLO-90, ~1–2 m) + aukštis virš žemės – 2 m. */
    fun sigma() = if (msl != null) 0.3 else 2.0

    /** Elipsoidinis aukštis h0 vietoje (lat, lon), m. */
    fun h0(lat: Double, lon: Double): Double? {
        val h = deviceMsl() ?: return null
        val n = Geoid.undulation(lat, lon) ?: return null
        return h + n
    }

    /** Žemės paviršiaus aukštis iš DEM (kai įvestas tik aukštis virš žemės); persiunčiama pasislinkus > 100 m. */
    private fun refreshDem(lat: Double, lon: Double) {
        if (agl == null || msl != null || busy) return
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
        if (demStatus.isNotEmpty() && msl == null) append("; $demStatus")
        if (!freeDh.isNaN()) append("; programos padėtis be aukščio %+.0f m nuo įvesto".format(Locale.US, freeDh))
        append(when {
            conflict -> "; NENAUDOJAMAS: programos padėtis daugiau nei 30 m kitame aukštyje (nulipote?) – pataisykite arba ištrinkite"
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
