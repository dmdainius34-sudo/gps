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
    /** Prietaisas juda (linijinis pagreitis) – tada padėties proceso triukšmas didelis, stovint – labai mažas. */
    @Volatile var moving = false
    /** Prietaiso kryptis (°) kabinos žemėlapiui. */
    @Volatile var heading: Double? = null
    private var movedMs = 0L
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
    val fix get() = engine.lastFix
    private var pushed: PositionSolver.Solution? = null
    // Paskutinis patvirtintas FIX (būsenai)
    private var lastFixSol: PositionSolver.Solution? = null
    private var lastFixMs = 0L
    private var held = 0
    @Volatile var pushedN = 0
        private set
    @Volatile var heldN = 0
        private set
    const val LOG = "gnss_rtk_live.csv"
    private var logW: java.io.BufferedWriter? = null
    @Volatile var logDir: java.io.File? = null

    private fun log(ms: Long, s: PositionSolver.Solution) {
        val d = logDir ?: return
        try {
            if (logW == null) {
                val f = java.io.File(d, LOG); val fresh = !f.exists()
                logW = java.io.BufferedWriter(java.io.FileWriter(f, true)).also { if (fresh) it.write("laikas_ms,platuma,ilguma,aukstis_m,fazes_palydovu,sigma_h_m,saltinis,fix,ratio\n") }
            }
            logW?.write(String.format(Locale.US, "%d,%.9f,%.9f,%.3f,%d,%.3f,%s,%d,%.2f\n", ms, s.lat, s.lon, s.h, s.sats, s.hacc, source, if (engine.lastFix) 1 else 0, engine.lastRatio))
            if (epochs % 10 == 0) logW?.flush()
        } catch (_: Exception) {}
    }
    @Synchronized fun flushLog() { try { logW?.flush() } catch (_: Exception) {}; try { vaultW?.flush() } catch (_: Exception) {} }

    /** Vault paties padėtis kas epochą (ZIP: gnss_vault.csv) – palyginimui su lustu ir krano ašimi. */
    const val VAULT_LOG = "gnss_vault.csv"
    private var vaultW: java.io.BufferedWriter? = null
    private var vaultN = 0
    @Synchronized private fun vaultLog(ms: Long, raw: PositionSolver.Solution, out: PositionSolver.Solution, used: Int, weak: Int, hCon: Boolean, crane: Boolean, thr: Double) {
        val d = logDir ?: return
        try {
            if (vaultW == null) {
                val f = java.io.File(d, VAULT_LOG)
                if (f.exists() && f.length() > 30_000_000L) f.delete()
                val fresh = !f.exists()
                vaultW = java.io.BufferedWriter(java.io.FileWriter(f, true)).also { if (fresh) it.write(
                    "laikas_ms,platuma,ilguma,aukstis_m,islyg_platuma,islyg_ilguma,islyg_aukstis_m,palydovu,silpnu,hacc_m,su_aukscio_riba,su_krano_riba,slenkstis_dbhz,orbitos,judama\n") }
            }
            vaultW?.write(String.format(Locale.US, "%d,%.8f,%.8f,%.2f,%.8f,%.8f,%.2f,%d,%d,%.2f,%d,%d,%.0f,%s,%d\n", ms, raw.lat, raw.lon, raw.h,
                out.lat, out.lon, out.h, used, weak, raw.hacc, if (hCon) 1 else 0, if (crane) 1 else 0, thr, sppSource.replace(',', ' '), if (moving) 1 else 0))
            if (++vaultN % 10 == 0) vaultW?.flush()
        } catch (_: Exception) {}
    }

    fun load(ctx: Context) {
        enabled = ctx.getSharedPreferences("ntrip", Context.MODE_PRIVATE).getBoolean("rtk_live", false)
        logDir = ctx.applicationContext.cacheDir
    }

    @Synchronized fun reset() {
        engine = Rtk.Engine(1e-3, Rtk.Run("gyvas")); pending.clear(); baseEps.clear(); rover.clear(); last = null; epochs = 0
    }

    /** NTRIP: 1005/1006 ir MSM (GPS, Galileo). */
    @Synchronized
    fun onRtcm(type: Int, p: ByteArray) {
        if (!enabled) return
        if (type == 1005 || type == 1006) { Rtcm3.station(p)?.let { basePos = it }; return }
        val sys = Rtcm3.sysOf(type); if (sys != 'G' && sys != 'E' && sys != 'R' && sys != 'C') return
        val m = Rtcm3.msm(p) ?: return
        val now = Sp3Live.gpsNow()
        val w = Math.floorDiv(now.toLong(), 604_800L)
        // GLONASS epocha – savaitės diena ir paros laikas Maskvos laiku (UTC + 3 h) -> GPS savaitės sekundės
        // BeiDou – BDT savaitės sekundės (BDT = GPS − 14 s)
        val tow = when (sys) {
            'R' -> { if (m.dow > 6) return; ((m.dow * 86_400.0 + m.tow - 10_800.0 + 18.0) % 604_800.0 + 604_800.0) % 604_800.0 }
            'C' -> (m.tow + 14.0) % 604_800.0
            else -> m.tow
        }
        var t = w * 604_800.0 + tow
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

    // ---- Viena išvestis kitoms programoms ----
    // Pagrindas – programos padėtis be bazės (išlyginta); patvirtintas RTK FIX ją patikslina palaipsniui (τ 2 s), FIX dingus padėtis
    // grįžta palaipsniui (τ 30 s, iki 2 min.); tarp epochų ir trumpai nutrūkus skaičiavimui (iki 30 s) – tęsiama pagal Doplerio greitį.
    @Volatile private var fixOut: PositionSolver.Solution? = null
    @Volatile private var fixOutMs = 0L
    private var oLat = Double.NaN; private var oLon = Double.NaN; private var oH = Double.NaN
    private var oMs = 0L; private var oTargetMs = 0L; private var oFixMs = 0L; private var oFixAcc = 0.0; private var oAcc = 99.0
    @Volatile var outSource = ""
        private set
    private const val TAU_FIX = 2.0
    private const val TAU_RETURN = 30.0
    private const val RETURN_MS = 120_000L
    private const val COAST_MS = 30_000L

    /** Vault paties padėtis (lat, lon, tikslumas m): RTK FIX, kitaip programos padėtis; šviežia ≤ 2,5 s; lusto fiksai nenaudojami. */
    fun ownPos(now: Long = System.currentTimeMillis()): DoubleArray? {
        fixOut?.takeIf { now - fixOutMs < 2_500L }?.let { return doubleArrayOf(it.lat, it.lon, it.hacc) }
        sppLast?.takeIf { now - sppMs < 2_500L }?.let { return doubleArrayOf(it.lat, it.lon, it.hacc) }
        return null
    }

    private fun outTick(now: Long) {
        if (!MockLoc.armed) { oLat = Double.NaN; return }
        val dt = if (oMs == 0L) 1.0 else ((now - oMs) / 1000.0).coerceIn(0.0, 5.0)
        oMs = now
        // prognozė pagal Doplerio greitį (stovint – be dreifo)
        val v = velocity()
        if (!oLat.isNaN() && v != null && (v.speedH >= 0.3 || moving)) {
            val c = Math.toRadians(v.courseDeg)
            oLat += v.speedH * kotlin.math.cos(c) * dt / 111_320.0
            oLon += v.speedH * kotlin.math.sin(c) * dt / (111_320.0 * kotlin.math.cos(Math.toRadians(oLat)))
            oH += v.vUp * dt
        }
        val fix = fixOut?.takeIf { now - fixOutMs < 2_500L }
        val spp = sppLast?.takeIf { now - sppMs < 2_500L }
        val target: PositionSolver.Solution; val tau: Double; val tAcc: Double; val src: String
        when {
            fix != null -> { target = fix; tau = TAU_FIX; tAcc = fix.hacc; oFixMs = now; oFixAcc = fix.hacc; src = "RTK FIX" }
            spp != null && now - oFixMs < RETURN_MS -> {
                target = spp; tau = TAU_RETURN
                tAcc = minOf(spp.hacc, oFixAcc + 0.1 * (now - oFixMs) / 1000.0); src = "po RTK FIX (grįžtama palaipsniui)"
            }
            spp != null -> { target = spp; tau = 3.0; tAcc = spp.hacc; src = "programa ($sppSource)" }
            else -> {
                // nėra naujo sprendinio: tęsiama pagal greitį iki COAST_MS, toliau – vieta grąžinama lustui (sargas)
                if (oLat.isNaN() || now - oTargetMs > COAST_MS) return
                oAcc += 0.5 * dt
                MockLoc.push(PositionSolver.Solution(oLat, oLon, oH, 0, oAcc, DoubleArray(3)), "tęsiama pagal greitį")
                outSource = "tęsiama pagal greitį"; return
            }
        }
        oTargetMs = now
        val d = if (oLat.isNaN()) Double.MAX_VALUE else PositionSolver.horizDist(oLat, oLon, target.lat, target.lon)
        if (oLat.isNaN() || d > 100.0) { oLat = target.lat; oLon = target.lon; oH = target.h }     // pradžia arba didelis tikras pokytis
        else {
            val k = (dt / tau).coerceIn(0.0, 1.0)
            oLat += k * (target.lat - oLat); oLon += k * (target.lon - oLon); oH += k * (target.h - oH)
        }
        oAcc = maxOf(tAcc, PositionSolver.horizDist(oLat, oLon, target.lat, target.lon), 0.05)
        outSource = src
        MockLoc.push(PositionSolver.Solution(oLat, oLon, oH, target.sats, oAcc, target.ecef), src)
    }

    /** Prietaiso matavimai (veikla ir paslauga – pasikartojimai praleidžiami). */
    @Synchronized
    fun onMeasurements(event: GnssMeasurementsEvent) {
        lastMeasMs = System.currentTimeMillis()
        if (event.clock.timeNanos == lastClockNs) return
        lastClockNs = event.clock.timeNanos
        val text = SessionFiles.RAW_HEADER + RawRecorder.lines(event).joinToString("\n") + "\n"
        val eps = ArrayList<Sp3V5.Ep>()
        Sp3V5.readRaw(java.io.BufferedReader(java.io.StringReader(text)), glo = true) { eps.add(it) }
        eps.lastOrNull()?.let { spp(it) }
        if (enabled) {
            rover.addAll(eps)
            while (rover.size > 20) rover.removeFirst()
            process()
        }
        outTick(System.currentTimeMillis())
    }

    /** Programos sprendinys be bazės (SP3 orbitos, slenkstis CnoMask) – perduodamas kitoms programoms, kai nėra gero RTK. */
    @Volatile var sppLast: PositionSolver.Solution? = null
        private set
    @Volatile private var sppMs = 0L
    fun lastSppMs() = sppMs
    private var sppIn: Set<String> = emptySet()
    /** „Čia žemė“ matavimas ką tik baigtas (UI pasiima ir nuima). */
    @Volatile var groundDone = false
    /**
     * Pseudoatstumų glodinimas pagal Doplerį (Hatch, langas 20 s – telefonų Doppler ilgiau kaupia paklaidą): atstumo pokytis tarp epochų imamas iš Doplerio (tikslus,
     * mažai triukšmo), o pats atstumas – vis lėčiau vidurkinamas kodas. Fazė (ADR) nenaudojama – planšetėje ji kabinoje trūkinėja.
     * Šuolis (kodas − integruotas Doppler nesutampa) ar > 1,5 s pertrauka – palydovo glodinimas pradedamas iš naujo.
     */
    private val sppSmooth = Sp3V5.Smoother(doppler = true, useAdr = false, dopWindow = 60)
    /** Palydovai su faze RTK skaičiavime (šviežia – iki 5 s); tušti – RTK neveikia. */
    fun phaseIds(): Set<String> = if (enabled && System.currentTimeMillis() - lastMs < 5_000L) engine.lastPhaseIds else emptySet()
    /** Paskutinė programos padėtis skaičiuota su žinomu aukščiu. */
    @Volatile var sppHeightCon = false
    /** Kiek BeiDou palydovų paskutinėje epochoje pridėta iš BRDC (SP3 jų neturi). */
    @Volatile var bdsFromBrdc = 0
        private set

    /** Silpni palydovai (slenkstis − SOFT_DB … slenkstis) neišmetami, o naudojami su mažu svoriu (patalpoje kitaip lieka per mažai). */
    const val SOFT_DB = 6.0
    const val WEAK_W = 0.3
    fun softMin() = maxOf(CnoMask.min - SOFT_DB, 12.0)
    @Volatile var weakUsed = 0
    /** Kiek palydovų paskutinėje epochoje sumažintas svoris pagal išmoktą kabinos / aplinkos žemėlapį. */
    @Volatile var mapWeighted = 0
    /** Stebima sklaida stovint (m): neišlygintos ir išlygintos padėties RMS apie medianą per 2 min. */
    private var dopMoveN = 0
    @Volatile var lastRawScatter = 0.0
    @Volatile var lastOutScatter = 0.0
    private val rawWin = ArrayDeque<DoubleArray>(); private val outWin = ArrayDeque<DoubleArray>()
    private fun scat(w: ArrayDeque<DoubleArray>, now: Long, lat: Double, lon: Double, moving: Boolean) {
        if (moving) { w.clear(); return }
        w.addLast(doubleArrayOf(now.toDouble(), lat, lon))
        while (w.isNotEmpty() && now - w.first()[0] > 120_000.0) w.removeFirst()
    }
    private fun scatter(w: ArrayDeque<DoubleArray>): Double {
        if (w.size < 20) return 0.0
        val la = w.map { it[1] }.sorted()[w.size / 2]; val lo = w.map { it[2] }.sorted()[w.size / 2]
        val k = 111_320.0 * kotlin.math.cos(Math.toRadians(la))
        return kotlin.math.sqrt(w.sumOf { ((it[1] - la) * 111_320.0).let { x -> x * x } + ((it[2] - lo) * k).let { x -> x * x } } / w.size)
    }

    /**
     * GLONASS (FDMA) – kiekvienas palydovas siunčia savo dažniu, o imtuvo vėlinimas kiekvienam dažnio kanalui kitoks (lusto
     * dažnių poslinkis, iki keliolikos ar keliasdešimt m). Poslinkis įvertinamas pagal palydovo liekanas (lėtas vidurkis,
     * τ ≈ 120 epochų, tik liekanos < 30 m, kai tokių GLONASS palydovų ≥ 4, riba ±15 m) ir atimamas iš pseudoatstumo. Vidurkis per palydovus – 0 (jį sugeria GLONASS laikrodis).
     */
    object GloBias {
        private val b = HashMap<Int, Double>(); private val n = HashMap<Int, Int>()
        @Synchronized fun of(svid: Int) = if ((n[svid] ?: 0) >= 10) b[svid] ?: 0.0 else 0.0
        @Synchronized fun update(res: Map<Int, Double>) {
            // tik nedidelės liekanos (< 30 m): didesnės – atspindžiai, ne lusto kanalo poslinkis
            val ok = res.filterValues { kotlin.math.abs(it) < 30.0 }
            if (ok.size < 4) return
            val m = ok.values.sorted()[ok.size / 2]
            for ((k, v) in ok) {
                val c = (n[k] ?: 0) + 1; n[k] = c
                val a = 1.0 / minOf(c, 120)
                b[k] = ((b[k] ?: 0.0) + a * (v - m)).coerceIn(-15.0, 15.0)
            }
        }
        @Synchronized fun text(): String {
            val l = b.keys.sorted().filter { (n[it] ?: 0) >= 10 }
            if (l.isEmpty()) return "GLONASS kanalų poslinkiai: dar neįvertinti"
            return "GLONASS kanalų poslinkiai (atimami, m): " + l.joinToString(", ") { "R%02d %+.1f".format(Locale.US, it, b[it]) }
        }
    }
    /** Kiek palydovų panaudojo programos padėtis (šviežia – iki 5 s); null – neskaičiuojama. */
    fun sppUsed(): Int? = sppLast?.takeIf { System.currentTimeMillis() - sppMs < 5_000L }?.sats
    /** Programos padėtyje panaudoti palydovai („GPS12“, „GAL7“…), paskutinio sprendinio. */
    @Volatile var sppUsedKeys: Set<String> = emptySet()
        private set
    private fun constOf(sys: Char) = when (sys) { 'G' -> "GPS"; 'E' -> "GAL"; 'C' -> "BDS"; 'R' -> "GLO"; 'J' -> "QZS"; else -> sys.toString() }

    /**
     * Palydovo aparatūros vėlinimas L1/E1 (s) ir šaltinis: GPS TGD, Galileo BGD(E1,E5b) – iš paties palydovo žinučių, kitaip iš BRDC.
     * SP3 laikrodžiai (kaip ir transliuojamas daugianaris) skirti dviejų dažnių kombinacijai – vieno dažnio vartotojas turi atimti TGD
     * (2026-11 BRDC: GPS −17,7…+6,5 ns, sklaida 6,8 ns ≈ 2 m kiekvienam palydovui; Galileo sklaida ≈ 1,4 m). null – nežinomas.
     */
    fun groupDelay(sys: Char, svid: Int): Pair<Double, String>? {
        val own = when (sys) { 'G' -> BroadcastEph.get(svid)?.tgd; 'E' -> BroadcastEph.galEph(svid)?.tgd; else -> null }
        if (own != null && own != 0.0) return own to "iš paties palydovo"
        if (sys != 'G' && sys != 'E') return null
        val k = Brdc.set?.kep?.get("%c%02d".format(Locale.US, sys, svid))?.firstOrNull { it.tgd != 0.0 } ?: return own?.let { it to "iš paties palydovo" }
        return k.tgd to "iš BRDC"
    }
    /** Ar programa šį palydovą „užtildė“ (neįtraukė į savo padėtį): pagal paskutinį sprendinį, kitaip – pagal signalo slenkstį. */
    fun isMuted(const: String, svid: Int, cn0: Float): Boolean =
        if (System.currentTimeMillis() - sppMs < 5_000L && sppUsedKeys.isNotEmpty()) (const + svid) !in sppUsedKeys else cn0 < CnoMask.min
    /** Greitis ir judėjimo kryptis iš Doplerio (programos sprendinys). */
    @Volatile var velLast: DopplerVel.Vel? = null
        private set
    @Volatile var velMs = 0L
        private set
    fun velocity(): DopplerVel.Vel? = velLast?.takeIf { System.currentTimeMillis() - velMs < 3_000L }

    /** FIX turi sutapti su programos padėtimi be bazės: horizontaliai ≤ 10 m, aukštis ≤ 15 m (kai ji šviežia). */
    private fun sppCheck(x: DoubleArray): String? {
        val s = sppLast ?: return null
        if (System.currentTimeMillis() - sppMs > 15_000L) return null
        val llh = PositionSolver.ecefToLlh(x[0], x[1], x[2])
        val d = PositionSolver.horizDist(s.lat, s.lon, Math.toDegrees(llh[0]), Math.toDegrees(llh[1]))
        if (d > 10.0) return "%.0f m nuo programos padėties be bazės".format(Locale.US, d)
        val dh = llh[2] - s.h
        if (kotlin.math.abs(dh) > 15.0) return "aukštis %+.0f m nuo programos padėties be bazės".format(Locale.US, dh)
        return null
    }

    /** Trumpa RTK pastaba būsenos eilutei: kodėl nėra FIX, ar fazė nutrūksta per dažnai. */
    fun note(): String {
        if (engine.lastFix) return ""
        // kiek fazės matavimų ilgose (≥ 30 s) atkarpose – mediana klaidindavo (daug trumpai mirksinčių palydovų)
        val long = engine.phaseLongShare()
        if (!long.isNaN() && long < 0.3) return "RTK sunkus: tik %.0f %% fazės nenutrūksta ≥ 30 s".format(Locale.US, 100 * long)
        return engine.fixNote
    }

    /** Iš ko skaičiuota programos padėtis: „SP3“ arba „efemeridės iš palydovų“. */
    @Volatile var sppSource = ""
        private set

    /** Sprendinio VDOP pagal panaudotų palydovų kryptis (virš 10°). */
    private fun vdopOf(f: Sp3V5.Fit): Double {
        val x = f.x
        val s = f.sats.mapNotNull { st ->
            val dx = x[0] - st.rp[0]; val dy = x[1] - st.rp[1]; val dz = x[2] - st.rp[2]
            val rho = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            val l = Sp3Solver.look(x, st.rp, rho)
            if (l[4] < Math.toRadians(10.0)) null else CnoMask.S("", 40.0, Math.toDegrees(l[4]), Math.toDegrees(l[3]))
        }
        return CnoMask.vdop(s).let { if (it.isInfinite() || it > 1e6) 30.0 else it }
    }

    private fun spp(ep: Sp3V5.Ep) {
        val now = System.currentTimeMillis()
        // SP3 (tiksliausia), o jei jo nėra ar nebeapima – efemeridės iš palydovų žinučių (ar BRDC): padėtis veikia ir be interneto
        val r = Sp3Live.reader?.takeIf { Sp3Solver.inRange(it, ep.t) }
        // slenkstis su 3 dB histereze kiekvienam palydovui: įeina nuo slenksčio, išeina tik nukritęs 3 dB žemiau –
        // ties slenksčiu mirgantys palydovai nekaitalioja rinkinio (kiekvienas pasikeitimas pastumia padėtį)
        val sm = HashMap<Int, Double>()
        sm.putAll(sppSmooth.applyEpoch(ep.meas.filter { (if (it.sys == 'R') (it.state and Sp3V5.STATE_GLO_TOD_DECODED) else (it.state and Sp3V5.STATE_TOW_DECODED)) != 0 }, ep.t))
        fun pr(m: Sp3V5.M) = sm[m.key] ?: m.pr
        // GLONASS įtrauktas (GSDC 2023: Pixel 7 Pro p50 2,9 → 2,6 m, p95 8,8 → 5,9 m; S22 Ultra 5,2 → 5,0 m), svoris /4 (Sp3V5.GLO_W)
        // kodėl palydovas nenaudojamas (kortelei ir ataskaitai): pirmas atmetimo žingsnis
        val why = HashMap<String, Int>()
        val cn0s = HashMap<String, Double>()
        for (m in ep.meas) {
            val k = constOf(m.sys) + m.svid
            cn0s[k] = m.cn0
            why[k] = when {
                (if (m.sys == 'R') (m.state and Sp3V5.STATE_GLO_TOD_DECODED) else (m.state and Sp3V5.STATE_TOW_DECODED)) == 0 -> SatStats.NO_TIME
                SatHealth.bad(m.sys, m.svid) -> SatStats.UNHEALTHY
                ManualMute.has(k) -> SatStats.MANUAL
                m.cn0 < softMin() -> SatStats.WEAK
                else -> SatStats.NO_ORBIT   // patikslinama po sprendimo
            }
        }
        fun flush(all: Int) {
            for (k in why.keys.toList()) if (why[k] == SatStats.NO_ORBIT) why[k] = all
            SatStats.epoch(now, CnoMask.min, why.map { Triple(it.key, it.value, Double.NaN) }, cn0s)
        }
        val obs = ep.meas.filter { (if (it.sys == 'R') (it.state and Sp3V5.STATE_GLO_TOD_DECODED) else (it.state and Sp3V5.STATE_TOW_DECODED)) != 0 && !SatHealth.bad(it.sys, it.svid) &&
                !ManualMute.has(constOf(it.sys) + it.svid) &&
                it.cn0 >= softMin() }
            .also { l -> sppIn = l.map { it.sys.toString() + it.svid }.toSet() }
            .map { m -> (if (DualFreq.enabled) DualFreq.iono(m, ep.t) else null)?.let { i -> Sp3V5.Ob(m.sys, m.svid, pr(m) - i, m.cn0, m.prr, ifree = true) }
                ?: Sp3V5.Ob(m.sys, m.svid, pr(m) - (if (m.sys == 'R') GloBias.of(m.svid) else 0.0), m.cn0, m.prr) }
            .let { l ->
                // L1+L5 palydovams reikia savo laikrodžio – verta tik kai sistemoje jų ≥ 3, kitaip lieka įprastas L1 su Klobuchar
                val few = l.filter { it.ifree }.groupingBy { it.sys }.eachCount().filterValues { it < 3 }.keys
                l.map { o -> if (o.ifree && o.sys in few) ep.meas.first { it.sys == o.sys && it.svid == o.svid }.let { m -> Sp3V5.Ob(m.sys, m.svid, pr(m), m.cn0, m.prr) } else o }
            }
        DualFreq.lastUsed = obs.count { it.ifree }; DualFreq.lastTotal = obs.size
        // SP3 keliu – aparatūros vėlinimo pataisa (transliuojamų efemeridžių kelias ją jau turi laikrodyje)
        var tgdN = 0
        val obsSp3 = if (r == null) obs else obs.map { o ->
            if (o.ifree) o else groupDelay(o.sys, o.svid)?.let { g -> tgdN++; Sp3V5.Ob(o.sys, o.svid, o.pr - C * g.first, o.cn0, o.prr) } ?: o
        }
        SatStats.tgdN = tgdN; SatStats.tgdOf = obs.count { (it.sys == 'G' || it.sys == 'E') && !it.ifree }
        val rx = sppLast?.ecef
        val hd = heading?.takeIf { HeadingTrust.trusted }
        var mapW = 0
        fun weak(l: List<Sp3V5.SatEp>) = l.onEach {
            it.wMul = if (it.cn0 < CnoMask.min) WEAK_W else 1.0
            // išmoktas kabinos / aplinkos žemėlapis: iš atspindžių krypčių – svoris ×0,15, iš užstotų – ×0,4
            if (rx != null && CabinMap.useInSpp) {
                val dx = it.pos[0] - rx[0]; val dy = it.pos[1] - rx[1]; val dz = it.pos[2] - rx[2]
                val rho = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                if (rho > 1e6) {
                    val lk = Sp3Solver.look(rx, it.pos, rho)
                    val k = CabinMap.sppWeight(Math.toDegrees(lk[3]).let { a -> (a + 360.0) % 360.0 }, Math.toDegrees(lk[4]), hd)
                    if (k < 1.0) { it.wMul *= k; mapW++ }
                }
            }
        }
        fun fit(hc: Sp3V5.HCon?) = try {
            if (r != null) {
                // SP3 (CODE) turi tik GPS, GLONASS ir Galileo – BeiDou palydovai pridedami iš transliuojamų efemeridžių (BRDC),
                // su savo laikrodžio poslinkiu (isb): daugiau palydovų ir geresnė geometrija
                val sp = Sp3V5.prepare(ep.week * 604_800.0 + ep.tow, obsSp3, r)
                val have = sp.map { it.sys to it.svid }.toSet()
                val bds = prepareBroadcast(ep.t, obs.filter { it.sys == 'C' && (it.sys to it.svid) !in have })
                bdsFromBrdc = bds.size
                Sp3V5.solveSats(ep.tow, weak(sp + bds), isb = true, raim = true, hCon = hc)
            }
            else Sp3V5.solveSats(ep.tow, weak(prepareBroadcast(ep.t, obs)), isb = true, raim = true, hCon = hc)
        } catch (_: Exception) { null }
        // žinomas aukštis (įvestas ranka): kai palydovų pakanka – patikra, ar jis čia tinka (be barometro kitaip nežinoma)
        val free = if (obs.size >= 6 && (KnownHeight.deviceMsl() != null || (Crane.sppOn && Crane.geoValid) || (KnownPos.use && KnownPos.valid))) fit(null)?.also {
            if (KnownHeight.deviceMsl() != null) KnownHeight.checkFree(it.sol.lat, it.sol.lon, it.sol.h, vdopOf(it))
            Crane.checkFree(it.sol.lat, it.sol.lon, it.sol.hacc)
            KnownPos.checkFree(it.sol.lat, it.sol.lon, now, Steps.walking(), Steps.count > 0)
        } else null
        val ref = sppLast?.takeIf { now - sppMs < 60_000L }?.let { it.lat to it.lon } ?: KnownHeight.lastLatLon()
        // žinomas aukštis ir (jei nustatytas) krano apskritimas aplink bokšto ašį
        val hc = KnownPos.sppConstraint(Crane.sppConstraint(ref?.let { KnownHeight.constraint(it.first, it.second) }, ref?.first, ref?.second),
            sppLast?.h ?: KnownHeight.deviceMsl() ?: 0.0)
        if (obs.size < (if (hc != null) 3 else 5)) { AppDiag.noPos(obs.size); flush(SatStats.NO_FIX); sppUsedKeys = emptySet(); return }
        val f = (if (hc != null) fit(hc) else free ?: fit(null)) ?: run { AppDiag.noPos(obs.size); flush(SatStats.NO_FIX); sppUsedKeys = emptySet(); return }
        // „Čia žemė“: matuojama programos padėtimi be aukščio apribojimo
        if (KnownHeight.gMeasuring) (if (hc == null) f else free ?: fit(null))?.let {
            if (KnownHeight.groundSample(now, it.sol.lat, it.sol.lon, it.sol.h, false, Steps.walking())) groundDone = true
        }
        sppHeightCon = hc != null && !hc.h0.isNaN()
        sppSource = if (r != null) "SP3" else "efemeridės iš palydovų"
        AppDiag.orbit(r != null)
        // ITRF (SP3 / efemeridės) → ETRS89 (LKS-94 pagrindas: Lietuvos žemėlapiai, LitPOS, geodeziniai punktai), ~1 m Lietuvoje
        val tY = Etrs.yearOf(now)
        fun toE(x: PositionSolver.Solution) = if (!Etrs.enabled) x else Etrs.llh(tY, x.lat, x.lon, x.h).let { e -> PositionSolver.Solution(e[0], e[1], e[2], x.sats, x.hacc, x.ecef) }
        val fs = toE(f.sol)
        val real = Readiness.realAcc().let { if (it.isNaN()) fs.hacc else maxOf(it, fs.hacc) }
        // greitis iš Doplerio – pirmiau, kad išlyginta vieta judant nevėluotų (Google GSDC važiavimai: p50 5,5 → 2,7 m)
        try { DopplerVel.solve(f.sats, f.x)?.let { velLast = it; velMs = now } } catch (_: Exception) {}
        val vNE = velocity()?.let { v -> doubleArrayOf(v.speedH * kotlin.math.cos(Math.toRadians(v.courseDeg)), v.speedH * kotlin.math.sin(Math.toRadians(v.courseDeg))) }
        // judama: jutikliai (žingsniai, judesys) arba Doplerio greitis > 1,5 m/s (sklandžiai važiuojant automobiliu jutikliai gali nieko nerodyti)
        // Doplerio greitis patalpoje kartais pavieniui „rodo“ > 1,5 m/s – judėjimu laikoma tik ≥ 3 s iš eilės
        // (kitaip stovint išjungdavo vidurkinimą ir sąžiningo tikslumo sklaidos langą)
        dopMoveN = if (velocity()?.let { it.speedH > 1.5 && it.sigma < 1.0 } == true) dopMoveN + 1 else 0
        val mv = moving || dopMoveN >= 3
        val q = PosFilter.step(now, fs.lat, fs.lon, fs.h, real, mv, vNE)
        // sąžiningas tikslumas: formalus (iš kovariacijos) per optimistiškas – kabinoje / namie žada 0,5 m, o tikra sklaida ~7 m.
        // Stovint – ne mažesnis už stebimą sklaidą per paskutines 2 min. (neišlygintos ir išlygintos padėties RMS apie medianą)
        scat(rawWin, now, fs.lat, fs.lon, mv); val sRaw = scatter(rawWin)
        scat(outWin, now, q[0], q[1], mv); val sOut = scatter(outWin)
        val accOut = maxOf(q[3], sOut, 0.5 * sRaw)
        lastRawScatter = sRaw; lastOutScatter = sOut
        val sol = PositionSolver.Solution(q[0], q[1], q[2], fs.sats, accOut, fs.ecef)
        sppLast = sol; sppMs = now
        AccRef.add("Vault (žymeklis, išlyginta)", sol.lat, sol.lon, sol.h)
        AccRef.add("Vault (neišlyginta)", fs.lat, fs.lon, fs.h)
        free?.let { toE(it.sol).let { e -> AccRef.add("Vault be apribojimų (aukščio, krano, vietos)", e.lat, e.lon, e.h) } }
        vaultLog(now, fs, sol, fs.sats, f.sats.count { it.cn0 < CnoMask.min }, hc != null && !hc.h0.isNaN(), Crane.sppActive, CnoMask.min)
        AppDiag.pos(obs.size, PositionSolver.Solution(fs.lat, fs.lon, fs.h, fs.sats, maxOf(fs.hacc, lastRawScatter), f.sol.ecef), hc != null && !hc.h0.isNaN(), sol,
            obs.map { sppSmooth.dopHatch.count(it.sys.code * 1000 + it.svid).toDouble() }.average(), sppSmooth.dopHatch.restarts)
        // naudoti – tik tie, kurių liekana yra (žemiau 10° palydovai lieka f.sats, bet sprendime nedalyvauja)
        val resK = HashMap<String, Double>()
        for (s in f.sats) {
            val k = constOf(s.sys) + s.svid
            val rv = f.res["%c%02d".format(Locale.US, s.sys, s.svid)]
            if (rv != null) { why[k] = SatStats.USED; resK[k] = rv } else why[k] = SatStats.LOW_EL
        }
        mapWeighted = mapW
        weakUsed = f.sats.count { it.cn0 < CnoMask.min && "%c%02d".format(Locale.US, it.sys, it.svid) in f.res }
        if (f.dropped.none { it[0] == 'R' }) GloBias.update(f.sats.filter { it.sys == 'R' && !it.ifree }
            .mapNotNull { s -> f.res["R%02d".format(Locale.US, s.svid)]?.let { s.svid to it } }.toMap())
        for (d in f.dropped) why[constOf(d[0]) + d.substring(1).toInt()] = SatStats.RAIM
        for (k in why.keys.toList()) if (why[k] == SatStats.NO_ORBIT && k.take(3) !in setOf("GPS", "GAL", "BDS", "GLO")) why[k] = SatStats.NOT_SYS
        SatStats.epoch(now, CnoMask.min, why.map { Triple(it.key, it.value, resK[it.key] ?: Double.NaN) }, cn0s)
        sppUsedKeys = resK.keys.toSet()
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
            if (moving) movedMs = System.currentTimeMillis()
            // stovint (≥ 5 s be judesio) – statinis filtras, judant – kinematinis
            engine.qPos = if (System.currentTimeMillis() - movedMs < 5_000) 0.5 else 1e-5
            engine.mask = { az, el -> CabinMap.excluded(az, el, heading) }
            engine.extCheck = ::sppCheck
            val sol = try { engine.step(ep, e0, e1, bp, ::prepare) } catch (e: Exception) { null } ?: continue
            last = sol; lastMs = System.currentTimeMillis(); epochs++
            CabinMap.learnRtk(heading, engine.lastObs, sol.hacc)
            log(ep.timeMs, sol)
            // kitoms programoms – tik suartėjęs ir stabilus sprendinys
            val prev = pushed
            // kitoms programoms – tik sveikųjų dviprasmybių FIX (patalpoje FLOAT „±0,5 m“ klaidina ir kaitaliojasi su SP3 sprendiniu)
            val ok = engine.lastFix && sol.hacc < 0.5 && sol.sats >= 4 &&
                (prev == null || PositionSolver.horizDist(prev.lat, prev.lon, sol.lat, sol.lon) < maxOf(1.0, 4 * sol.hacc) || held >= 10)
            val nowMs = System.currentTimeMillis()
            if (engine.lastFix && sol.hacc < 0.5) FixPoints.onFix(sol, nowMs)
            if (engine.lastFix && sol.hacc < 0.5 && KnownHeight.gMeasuring &&
                KnownHeight.groundSample(nowMs, sol.lat, sol.lon, sol.h, true, Steps.walking())) groundDone = true
            if (ok) { AccRef.add(if (engine.lastFix) "RTK FIX" else "RTK FLOAT", sol.lat, sol.lon, sol.h); pushed = sol; held = 0; pushedN++; lastFixSol = sol; lastFixMs = nowMs; fixOut = sol; fixOutMs = nowMs }
            else { held++; heldN++ }
            // kitoms programoms perduodama tik per bendrą išvestį (outTick)
        }
    }

    /** Palydovų būsena: SP3, jei apima; kitaip – transliuojamos efemeridės (GPS/Galileo žinutės, BRDC). */
    fun prepare(t: Double, obs0: List<Sp3V5.Ob>): List<Sp3V5.SatEp> {
        val obs = obs0.filter { it.cn0 >= CnoMask.min && !SatHealth.bad(it.sys, it.svid) }
        val r = Sp3Live.reader
        if (r != null && Sp3Solver.inRange(r, t)) { source = "SP3"; return Sp3V5.prepare(t, obs, r) }
        source = "transliuojamos efemeridės"
        return prepareBroadcast(t, obs)
    }

    /** Palydovų būsena iš transliuojamų efemeridžių (iš palydovų žinučių arba BRDC failo): padėtis, greitis (±0,5 s), laikrodis ir jo dreifas. */
    private fun prepareBroadcast(t: Double, obs: List<Sp3V5.Ob>): List<Sp3V5.SatEp> {
        val out = ArrayList<Sp3V5.SatEp>()
        for (o in obs) {
            val tSv = t - o.pr / C
            var st = state(o.sys, o.svid, tSv) ?: continue
            val tTx = tSv - st.second
            st = state(o.sys, o.svid, tTx) ?: continue
            val a = state(o.sys, o.svid, tTx - 0.5); val b = state(o.sys, o.svid, tTx + 0.5)
            val vel = if (a != null && b != null) DoubleArray(3) { b.first[it] - a.first[it] } else DoubleArray(3)
            val drift = if (a != null && b != null) (b.second - a.second) * C else 0.0
            out.add(Sp3V5.SatEp(o.sys, o.svid, st.first, vel, o.pr + C * st.second, t - tTx, o.cn0, drift, o.prr).also { it.ifree = o.ifree })
        }
        return out
    }

    /** (ECEF m, laikrodžio pataisa s) arba null. */
    private fun state(sys: Char, svid: Int, t: Double): Pair<DoubleArray, Double>? {
        if (sys == 'R') {
            // GLONASS: iš palydovo eilučių arba BRDC; laikrodis −τn + γn·(t − tb)
            val id = "R%02d".format(Locale.US, svid)
            val e = BroadcastEph.gloEph(svid)?.takeIf { kotlin.math.abs(it.tbGps - t) < 1.5 * 3600 }
                ?: Brdc.set?.glo?.get(id)?.minByOrNull { kotlin.math.abs(it.tbGps - t) } ?: return null
            if (kotlin.math.abs(e.tbGps - t) > 1.5 * 3600) return null
            val pos = GloNav.position(e, t, 1.5 * 3600) ?: return null
            return pos to (-e.tauN + e.gammaN * (t - e.tbGps))
        }
        val e = when (sys) { 'G' -> BroadcastEph.get(svid); 'E' -> BroadcastEph.galEph(svid); else -> null }
        if (e != null && kotlin.math.abs(t - e.toeAbs) < 4 * 3600) {
            val s = PositionSolver.satState(e, t - e.week * 604_800.0)
            return doubleArrayOf(s[0], s[1], s[2]) to s[3]
        }
        val id = "%c%02d".format(Locale.US, sys, svid)
        val k = Brdc.set?.kep?.get(id)?.minByOrNull { kotlin.math.abs(it.toeAbs - t) } ?: return null
        if (kotlin.math.abs(k.toeAbs - t) > 4 * 3600) return null
        return Brdc.kepler(k, t) to Brdc.clock(k, t)
    }

    fun text(): String {
        if (!enabled) return "Gyvas RTK: išjungtas"
        val s = last ?: return "Gyvas RTK: laukiama (bazė ${if (basePos != null) "yra" else "nėra"}, bazės epochų ${baseEps.size}, prietaiso epochų eilėje ${rover.size})"
        return "Gyvas RTK: %s %.7f, %.7f, h %.2f m, σh %.2f m, fazės palydovų %d, ratio %.1f, prieš %d s, epochų %d, FIX epochų %d (%s)".format(Locale.US,
            if (engine.lastFix) "FIX" else "FLOAT", s.lat, s.lon, s.h, s.hacc, s.sats, engine.lastRatio, (System.currentTimeMillis() - lastMs) / 1000, epochs, engine.run.fixEpochs, source) + (if (engine.qPos > 0.1) ", judant" else ", stovint") +
            (note().let { if (it.isEmpty()) "" else "; $it" }) +
            "; nepertraukta fazė (mediana) " + engine.phaseSegMedian().let { if (it.isNaN()) "nežinoma" else "%.0f s".format(Locale.US, it) } +
            engine.phaseLongShare().let { if (it.isNaN()) "" else ", ilgose (≥ 30 s) atkarpose %.0f %% fazės".format(Locale.US, 100 * it) } +
            "; kaukė praleido ${engine.masked} palydovų-epochų, Doppler aptiko ${engine.dopplerSlips} fazės trūkių; kitoms programoms perduota $pushedN, sulaikyta $heldN (nesuartėjęs ar šuolis); " + MockLoc.status
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

    /** Įjungta vartotojo (net jei laikinai nuimta, kol RTK nesuartėjęs). */
    @Volatile var armed = false
        private set
    @Volatile var active = false
        private set
    private var ctxApp: Context? = null

    /** Vartotojas įjungia: patikrinama leidimas; teikėjas uždedamas tik kai RTK suartėjęs. */
    fun enable(ctx: Context): Boolean {
        ctxApp = ctx.applicationContext
        val ok = attach()
        if (ok) { armed = true; detach("laukiama suartėjusio RTK (tol rodomas lusto GPS)") }
        return ok
    }

    fun suspend() { if (active) detach("laikinai nuimta: RTK nesuartėjęs – rodomas lusto GPS") }

    private fun detach(why: String) {
        try { lm?.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        active = false; on = armed; status = "bandomoji vieta: $why"
    }

    @Suppress("DEPRECATION")
    private fun attach(): Boolean {
        val ctx = ctxApp ?: return false
        val m = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            try { m.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
            m.addTestProvider(LocationManager.GPS_PROVIDER, false, true, false, false, true, true, true,
                android.location.Criteria.POWER_LOW, android.location.Criteria.ACCURACY_FINE)
            m.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            lm = m; on = true; active = true; since = System.currentTimeMillis()
            status = "bandomoji vieta: perduodamas RTK (GPS teikėjas)"
            true
        } catch (e: SecurityException) {
            status = "bandomoji vieta neleidžiama: kūrėjo nustatymuose pasirinkite šią programą „Bandomosios vietos programa“"
            false
        } catch (e: Exception) { status = "bandomoji vieta: klaida ${e.message}"; false }
    }

    fun disable(why: String = "išjungta") {
        try { lm?.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
        on = false; armed = false; active = false; lm = null; status = "bandomoji vieta $why"
    }

    @Volatile var lastPushMs = 0L
        private set
    /** Sargas: lustas nebeteikia matavimų – išjungti; > 5 s be jokio sprendinio – laikinai grąžinti lusto GPS. */
    fun watchdog() {
        if (active && System.currentTimeMillis() - since > 5_000 && System.currentTimeMillis() - lastPushMs > 5_000)
            detach("laikinai nuimta: nėra programos sprendinio (SP3 ar RTK) – rodomas lusto GPS")
        if (active && System.currentTimeMillis() - since > 15_000 && System.currentTimeMillis() - LiveRtk.lastMeasMs > 15_000)
            disable("išjungta: GNSS lustas su bandomąja vieta nebeteikė matavimų")
    }

    fun push(s: PositionSolver.Solution, src: String = "RTK") {
        if (!armed) return
        if (!active && !attach()) return
        val m = lm ?: return
        try {
            val l = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = s.lat; longitude = s.lon; altitude = s.h
                accuracy = s.hacc.toFloat().coerceAtLeast(0.05f)
                time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                if (android.os.Build.VERSION.SDK_INT >= 26) verticalAccuracyMeters = (s.hacc * 2).toFloat().coerceAtLeast(0.1f)
                // greitis ir kryptis: judant (≥ 1 m/s) – judėjimo kryptis iš Doplerio; stovint – kalibruota planšetės kryptis (ne magnetinė)
                val v = LiveRtk.velocity()
                val hd = LiveRtk.heading?.takeIf { HeadingTrust.trusted }
                if (v != null) { speed = v.speedH.toFloat(); if (android.os.Build.VERSION.SDK_INT >= 26) speedAccuracyMetersPerSecond = (2 * v.sigma).toFloat().coerceIn(0.05f, 5f) }
                val moving = v != null && v.speedH >= 1.0
                val b = if (moving) v!!.courseDeg else hd
                if (b != null) {
                    bearing = b.toFloat()
                    if (android.os.Build.VERSION.SDK_INT >= 26) bearingAccuracyDegrees = if (moving) Math.toDegrees(2 * v!!.sigma / v.speedH).toFloat().coerceIn(1f, 45f) else 3f
                }
                extras = android.os.Bundle().apply {
                    putInt("satellites", s.sats); putString("source", src)
                    hd?.let { putDouble("heading", it) }
                    putString("bearing_source", if (moving) "doppler" else if (hd != null) "kalibruota planšetės kryptis" else "nėra")
                }
            }
            m.setTestProviderLocation(LocationManager.GPS_PROVIDER, l)
            lastPushMs = System.currentTimeMillis(); status = "bandomoji vieta: perduodama – $src, ±%.1f m".format(Locale.US, s.hacc)
        } catch (e: Exception) { disable("išjungta: ${e.message}") }
    }
}
