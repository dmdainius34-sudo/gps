package lt.example.gnss

import android.content.Context
import android.location.GnssMeasurementsEvent
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import java.util.Locale

/**
 * Gyvas RTK: prietaiso matavimai (GnssMeasurementsEvent) + NTRIP bazė (1005, MSM GPS/Galileo).
 * Prietaiso epochos laukia, kol ateina vėlesnė bazės epocha (~1–2 s vėlavimas), tada Rtk.Engine žingsnis.
 * Palydovų padėtys: SP3 (jei apima), kitaip transliuojamos efemeridės (palydovų žinutės, BRDC) – dvigubuose
 * skirtumuose orbitų ir laikrodžių paklaidos išnyksta. Rezultatas gali būti perduotas kaip bandomoji vieta (MockLoc).
 */
object LiveRtk {
    private const val C = PositionSolver.C
    @Volatile var enabled = false
    private var engine = Rtk.Engine(1e-3, Rtk.Run("gyvas"))
    private var basePos: DoubleArray? = null
    private val pending = java.util.TreeMap<Double, HashMap<String, Pair<Int, Rtcm3.Obs>>>()
    private val baseEps = ArrayDeque<Rtk.BEp>()
    private val rover = ArrayDeque<Sp3V5.Ep>()
    private var lastClockNs = Long.MIN_VALUE
    @Volatile var last: PositionSolver.Solution? = null
        private set
    @Volatile var lastMs = 0L
        private set
    @Volatile var lastMeasMs = 0L
        private set
    @Volatile var source = ""
        private set
    @Volatile var epochs = 0
        private set

    fun load(ctx: Context) { enabled = ctx.getSharedPreferences("ntrip", Context.MODE_PRIVATE).getBoolean("rtk_live", false) }

    @Synchronized fun reset() {
        engine = Rtk.Engine(1e-3, Rtk.Run("gyvas")); pending.clear(); baseEps.clear(); rover.clear(); last = null; epochs = 0
    }

    /** NTRIP: 1005/1006 ir MSM (GPS, Galileo). */
    @Synchronized
    fun onRtcm(type: Int, p: ByteArray) {
        if (!enabled) return
        if (type == 1005 || type == 1006) { Rtcm3.station(p)?.let { basePos = it }; return }
        val sys = Rtcm3.sysOf(type); if (sys != 'G' && sys != 'E') return
        val m = Rtcm3.msm(p) ?: return
        val now = Sp3Live.gpsNow()
        val w = Math.floorDiv(now.toLong(), 604_800L)
        var t = w * 604_800.0 + m.tow
        if (t - now > 302_400) t -= 604_800.0; if (now - t > 302_400) t += 604_800.0
        val key = Math.round(t * 1000) / 1000.0
        Rtk.pickObs(m, pending.getOrPut(key) { HashMap() })
        // ankstesnės epochos baigtos (atėjo naujesnė)
        val bp = basePos ?: return
        while (pending.size > 1) {
            val (tb, map) = pending.pollFirstEntry() ?: break
            Rtk.baseEpoch(tb, map.values.map { it.second }, bp, ::prepare)?.let { baseEps.addLast(it); if (baseEps.size > 20) baseEps.removeFirst() }
        }
        process()
    }

    /** Prietaiso matavimai (veikla ir paslauga – pasikartojimai praleidžiami). */
    @Synchronized
    fun onMeasurements(event: GnssMeasurementsEvent) {
        lastMeasMs = System.currentTimeMillis()
        if (!enabled) return
        if (event.clock.timeNanos == lastClockNs) return
        lastClockNs = event.clock.timeNanos
        val text = SessionFiles.RAW_HEADER + RawRecorder.lines(event).joinToString("\n") + "\n"
        Sp3V5.readRaw(java.io.BufferedReader(java.io.StringReader(text))) { rover.addLast(it) }
        while (rover.size > 20) rover.removeFirst()
        process()
    }

    private fun process() {
        val bp = basePos ?: return
        while (rover.isNotEmpty() && baseEps.size >= 2) {
            val ep = rover.first()
            val t = ep.t
            if (t > baseEps.last().t) return                      // laukiama naujesnės bazės epochos
            rover.removeFirst()
            var i = baseEps.indexOfFirst { it.t >= t }; if (i <= 0) continue
            val e0 = baseEps[i - 1]; val e1 = baseEps[i]
            if (e1.t - e0.t > 2.5) continue
            val sol = try { engine.step(ep, e0, e1, bp, ::prepare) } catch (e: Exception) { null } ?: continue
            last = sol; lastMs = System.currentTimeMillis(); epochs++
            MockLoc.push(sol)
        }
    }

    /** Palydovų būsena: SP3, jei apima; kitaip – transliuojamos efemeridės (GPS/Galileo žinutės, BRDC). */
    fun prepare(t: Double, obs: List<Sp3V5.Ob>): List<Sp3V5.SatEp> {
        val r = Sp3Live.reader
        if (r != null && Sp3Solver.inRange(r, t)) { source = "SP3"; return Sp3V5.prepare(t, obs, r) }
        source = "transliuojamos efemeridės"
        val out = ArrayList<Sp3V5.SatEp>()
        for (o in obs) {
            var tSv = t - o.pr / C
            var st = state(o.sys, o.svid, tSv) ?: continue
            val tTx = tSv - st.second
            st = state(o.sys, o.svid, tTx) ?: continue
            out.add(Sp3V5.SatEp(o.sys, o.svid, st.first, DoubleArray(3), o.pr + C * st.second, t - tTx, o.cn0, 0.0, o.prr))
        }
        return out
    }

    /** (ECEF m, laikrodžio pataisa s) arba null. */
    private fun state(sys: Char, svid: Int, t: Double): Pair<DoubleArray, Double>? {
        val e = when (sys) { 'G' -> BroadcastEph.get(svid); 'E' -> BroadcastEph.galEph(svid); else -> null }
        if (e != null && kotlin.math.abs(t - e.toeAbs) < 4 * 3600) {
            val s = PositionSolver.satState(e, t - e.week * 604_800.0)
            return doubleArrayOf(s[0], s[1], s[2]) to s[3]
        }
        val id = "%c%02d".format(Locale.US, sys, svid)
        val k = Brdc.set?.kep?.get(id)?.minByOrNull { kotlin.math.abs(it.toeAbs - t) } ?: return null
        if (kotlin.math.abs(k.toeAbs - t) > 4 * 3600) return null
        val dt = t - k.tocAbs
        return Brdc.kepler(k, t) to (k.af0 + k.af1 * dt + k.af2 * dt * dt)
    }

    fun text(): String {
        if (!enabled) return "Gyvas RTK: išjungtas"
        val s = last ?: return "Gyvas RTK: laukiama (bazė ${if (basePos != null) "yra" else "nėra"}, bazės epochų ${baseEps.size}, prietaiso epochų eilėje ${rover.size})"
        return "Gyvas RTK: %.7f, %.7f, h %.2f m, σh %.2f m, fazės palydovų %d, prieš %d s, epochų %d (%s)".format(Locale.US,
            s.lat, s.lon, s.h, s.hacc, s.sats, (System.currentTimeMillis() - lastMs) / 1000, epochs, source) + "; " + MockLoc.status
    }
}

/**
 * Bandomoji vieta (mock location): gyvas RTK perduodamas sistemai kaip GPS teikėjo vieta, kad ją matytų kitos programos
 * (pvz. AlpineQuest). Reikia kūrėjo nustatymuose pasirinkti šią programą „Bandomosios vietos programa“.
 * Jei po įjungimo GNSS lustas nebeteikia matavimų (> 15 s), bandomoji vieta išjungiama.
 */
object MockLoc {
    @Volatile var on = false
        private set
    @Volatile var status = "bandomoji vieta išjungta"
        private set
    private var lm: LocationManager? = null
    private var since = 0L

    @Suppress("DEPRECATION")
    fun enable(ctx: Context): Boolean {
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            try { m.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
            m.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
                android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE)
            m.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            lm = m; on = true; since = System.currentTimeMillis()
            status = "bandomoji vieta įjungta (GPS teikėjas)"
            true
        } catch (e: SecurityException) {
            status = "bandomoji vieta neleidžiama: kūrėjo nustatymuose pasirinkite šią programą „Bandomosios vietos programa“"
            false
        } catch (e: Exception) { status = "bandomoji vieta: klaida ${e.message}"; false }
    }

    fun disable(why: String = "išjungta") {
        try { lm?.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        on = false; lm = null; status = "bandomoji vieta $why"
    }

    /** Sargas: lustas nebeteikia matavimų – išjungti. */
    fun watchdog() {
        if (on && System.currentTimeMillis() - since > 15_000 && System.currentTimeMillis() - LiveRtk.lastMeasMs > 15_000)
            disable("išjungta: GNSS lustas su bandomąja vieta nebeteikė matavimų")
    }

    fun push(s: PositionSolver.Solution) {
        val m = lm ?: return
        if (!on) return
        try {
            val l = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = s.lat; longitude = s.lon; altitude = s.h
                accuracy = s.hacc.toFloat().coerceAtLeast(0.05f)
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (android.os.Build.VERSION.SDK_INT >= 26) verticalAccuracyMeters = (s.hacc * 2).toFloat().coerceAtLeast(0.1f)
                extras = android.os.Bundle().apply { putInt("satellites", s.sats); putString("source", "RTK") }
            }
            m.setTestProviderLocation(LocationManager.GPS_PROVIDER, l)
        } catch (e: Exception) { disable("išjungta: ${e.message}") }
    }
}
