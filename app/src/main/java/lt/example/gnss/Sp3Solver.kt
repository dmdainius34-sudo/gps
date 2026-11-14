package lt.example.gnss

import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * GPS L1 C/A padėties sprendimas su SP3 orbitomis ir laikrodžiais (tik testui; pagrindinė skaičiuoklė nekeičiama).
 * Matavimai – gnss_raw.csv per PositionSolver.readEpochs (SUBFRAME_SYNC, pseudoatstumas iš time_nanos, full_bias, bias).
 * Palydovo padėtis – siuntimo momentu t = t_rx − pr/c − dt_sv (dt_sv iš SP3 laikrodžio, viena iteracija),
 * Sagnac pataisa (Žemės sukimasis per sklidimo laiką) ir periodinė reliatyvistinė pataisa −2 r·v / c².
 */
object Sp3Solver {
    const val OUT_FILE = "gnss_solver_sp3_test.csv"
    const val OUT_FILE_V2 = "gnss_solver_sp3_test_v2.csv"
    const val OUT_FILE_V3 = "gnss_solver_sp3_test_v3.csv"

    /**
     * Žemės sukimosi (Sagnac) pataisa: palydovo ECEF padėtis siuntimo momentu pasukama apie Z kampu −ω·τ.
     * LEGACY – v1/v2 elgsena: −ω·τ vieną kartą, τ = pr/c + dt_sv (su imtuvo laikrodžio poslinkiu);
     * MINUS – teisinga: −ω·τ, τ = geometrinis atstumas / c, perskaičiuojamas kiekvienoje iteracijoje;
     * NONE – be pasukimo; PLUS – neteisingas ženklas (+ω·τ). NONE ir PLUS – tik diagnostikai.
     */
    enum class Rot(val sign: Double) { LEGACY(1.0), MINUS(1.0), NONE(0.0), PLUS(-1.0) }
    /** Planšetės fiksas palyginimui. */
    const val FIX_LAT = 54.73998
    const val FIX_LON = 25.25466
    private val ELEV_MASK_RAD = Math.toRadians(10.0)
    val REF_LAT = 54.7400
    val REF_LON = 25.2545

    private const val C = PositionSolver.C
    private const val W = Sp3Reader.WEEK

    /** Palydovo padėtis (m, ECEF siuntimo momentu) ir laikrodžio pataisa (s, su reliatyvistine). */
    class SatState(val pos: DoubleArray, val dts: Double)

    fun satState(sp3: Sp3Reader, sat: String, t: Double): SatState? {
        val w = Math.floorDiv(t.toLong(), W.toLong()).toInt()
        val sow = t - w * W
        val p = sp3.positionXyz(sat, w, sow) ?: return null
        val c = sp3.clock(sat, w, sow) ?: return null
        if (c.isNaN()) return null
        // greitis skaitmeniškai (±0,5 s) reliatyvistinei pataisai
        val a = sp3.positionXyz(sat, w, sow - 0.5) ?: return null
        val b = sp3.positionXyz(sat, w, sow + 0.5) ?: return null
        val pos = DoubleArray(3) { p[it] * 1000.0 }
        val vel = DoubleArray(3) { (b[it] - a[it]) * 1000.0 }
        val rel = -2.0 * (pos[0] * vel[0] + pos[1] * vel[1] + pos[2] * vel[2]) / (C * C)
        return SatState(pos, c * 1e-6 + rel)
    }

    /** Ar laikas (GPS s nuo 1980-01-06) yra SP3 failo intervale. */
    fun inRange(sp3: Sp3Reader, t: Double) = sp3.epochCount > 0 && t >= sp3.epochs.first() && t <= sp3.epochs.last()

    /**
     * Saastamoinen troposferos vėlinimas (m), kartografavimas 1/sin(el). Meteorologija jūros lygyje:
     * 1013,25 hPa, 15 °C, santykinė drėgmė 50 %, perskaičiuota į imtuvo aukštį. Po horizontu arba už aukščių ribų – 0.
     */
    fun saastamoinen(latRad: Double, hM: Double, elRad: Double): Double {
        if (elRad <= 0.0 || hM < -100.0 || hM > 10_000.0) return 0.0
        val pres = 1013.25 * (1.0 - 2.2557e-5 * hM).pow(5.2568)
        val temp = 15.0 - 6.5e-3 * hM + 273.16
        val e = 6.108 * 0.5 * exp((17.15 * temp - 4684.0) / (temp - 38.45))
        val dry = 0.0022768 * pres / (1.0 - 0.00266 * cos(2.0 * latRad) - 0.00028 * hM / 1000.0)
        val wet = 0.002277 * (1255.0 / temp + 0.05) * e
        return (dry + wet) / sin(elRad)
    }

    private val KLOB_ALPHA = doubleArrayOf(0.1118e-7, 0.1490e-7, -0.5960e-7, -0.5960e-7)
    private val KLOB_BETA = doubleArrayOf(0.1167e6, 0.1638e6, -0.3277e6, -0.2621e6)

    /** Klobuchar L1 jonosferos vėlinimas (m), IS-GPS-200 algoritmas su numatytaisiais koeficientais. */
    fun klobuchar(latRad: Double, lonRad: Double, azRad: Double, elRad: Double, towSec: Double): Double {
        val el = elRad / PI                                          // pusapskritimiai
        val psi = 0.0137 / (el + 0.11) - 0.022
        val phiI = (latRad / PI + psi * cos(azRad)).coerceIn(-0.416, 0.416)
        val lamI = lonRad / PI + psi * sin(azRad) / cos(phiI * PI)
        val phiM = phiI + 0.064 * cos((lamI - 1.617) * PI)
        val t = Math.floorMod((43200.0 * lamI + towSec).toLong(), 86400L).toDouble()
        val poly = { k: DoubleArray -> k[0] + phiM * (k[1] + phiM * (k[2] + phiM * k[3])) }
        val amp = poly(KLOB_ALPHA).coerceAtLeast(0.0)
        val per = poly(KLOB_BETA).coerceAtLeast(72_000.0)
        val x = 2.0 * PI * (t - 50_400.0) / per
        val f = 1.0 + 16.0 * (0.53 - el).pow(3)
        val day = if (abs(x) < 1.57) amp * (1.0 - x * x / 2.0 + x * x * x * x / 24.0) else 0.0
        return C * f * (5e-9 + day)
    }

    /** Palydovo q matymo kampai iš imtuvo s (ECEF): platuma, ilguma, aukštis, azimutas, aukščio kampas. */
    internal fun look(s: DoubleArray, q: DoubleArray, rho: Double): DoubleArray {
        val llh = PositionSolver.ecefToLlh(s[0], s[1], s[2])
        val sLa = sin(llh[0]); val cLa = cos(llh[0]); val sLo = sin(llh[1]); val cLo = cos(llh[1])
        val dx = (q[0] - s[0]) / rho; val dy = (q[1] - s[1]) / rho; val dz = (q[2] - s[2]) / rho
        val e = -sLo * dx + cLo * dy
        val n = -sLa * cLo * dx - sLa * sLo * dy + cLa * dz
        val u = cLa * cLo * dx + cLa * sLo * dy + sLa * dz
        return doubleArrayOf(llh[0], llh[1], llh[2], atan2(e, n), asin(u.coerceIn(-1.0, 1.0)))
    }

    /** Troposferos ir jonosferos (L1) vėlinimas kelyje iki palydovo, m. */
    fun atmosphereDelay(s: DoubleArray, q: DoubleArray, rho: Double, towSec: Double): Double {
        val l = look(s, q, rho)
        return saastamoinen(l[0], l[2], l[4]) + klobuchar(l[0], l[1], l[3], l[4], towSec)
    }

    /**
     * Vienos epochos sprendinys arba null (< 4 palydovai, nekonverguoja).
     * corrected = true – v2: 10° maska ir atmosferos pataisos; onDropped gauna atmestų palydovų skaičių.
     */
    fun solve(ep: PositionSolver.Epoch, sp3: Sp3Reader, corrected: Boolean = false, rot: Rot = Rot.LEGACY,
              onDropped: ((Int) -> Unit)? = null): PositionSolver.Solution? {
        val tRx = ep.week * W + ep.tRxTow
        val sats = ArrayList<DoubleArray>()   // x, y, z, pr pataisytas palydovo laikrodžiu
        for (o in ep.obs) {
            val sat = "G%02d".format(Locale.US, o.svid)
            val tSv = tRx - o.pr / C
            var st = satState(sp3, sat, tSv) ?: continue
            st = satState(sp3, sat, tSv - st.dts) ?: continue              // viena iteracija
            val tau = tRx - (tSv - st.dts)                                   // sklidimo laikas (su imtuvo laikrodžiu – mažas)
            // R3(ω·τ): x' = cos·x + sin·y, y' = −sin·x + cos·y  (pasukimas apie Z kampu −ω·τ)
            val th = rot.sign * PositionSolver.OMEGA_E * tau
            val x = cos(th) * st.pos[0] + sin(th) * st.pos[1]
            val y = -sin(th) * st.pos[0] + cos(th) * st.pos[1]
            // [0..3] – pasukta padėtis ir pataisytas pseudoatstumas; [4..6] – nepasukta X, Y ir pradinis τ
            sats.add(doubleArrayOf(x, y, st.pos[2], o.pr + C * st.dts, st.pos[0], st.pos[1], tau))
        }
        if (sats.size < 4) return null
        val s = doubleArrayOf(0.0, 0.0, 6_378_137.0, 0.0)
        var active: List<DoubleArray> = sats
        var masked = false
        var ok = false
        var lastStep = Double.MAX_VALUE
        fun range(q: DoubleArray): Double {
            val dx = s[0] - q[0]; val dy = s[1] - q[1]; val dz = s[2] - q[2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        for (it in 0 until 30) {
            // Maska ir pataisos – tik padėčiai priartėjus: pradinis taškas (ašigalis) aukščio kampui netinka
            if (corrected && !masked && lastStep < 1000.0) {
                active = sats.filter { q -> look(s, q, range(q))[4] >= ELEV_MASK_RAD }
                masked = true
                onDropped?.invoke(sats.size - active.size)
                if (active.size < 4) return null
            }
            val h = Array(active.size) { DoubleArray(4) }
            val r = DoubleArray(active.size)
            if (rot != Rot.LEGACY && rot != Rot.NONE) for (q in active) {
                // τ iš geometrinio atstumo (be imtuvo laikrodžio poslinkio), perskaičiuojamas kiekvienoje iteracijoje
                val tg = range(q) / C
                val tau = if (tg in 0.05..0.11) tg else q[6]
                val th = rot.sign * PositionSolver.OMEGA_E * tau
                q[0] = cos(th) * q[4] + sin(th) * q[5]
                q[1] = -sin(th) * q[4] + cos(th) * q[5]
            }
            for ((i, q) in active.withIndex()) {
                val rho = range(q)
                h[i][0] = (s[0] - q[0]) / rho; h[i][1] = (s[1] - q[1]) / rho; h[i][2] = (s[2] - q[2]) / rho; h[i][3] = 1.0
                r[i] = q[3] - (rho + s[3] + (if (masked) atmosphereDelay(s, q, rho, ep.tRxTow) else 0.0))
            }
            val d = lsq(h, r) ?: return null
            for (k in 0..3) s[k] += d[k]
            lastStep = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
            if (lastStep < 1e-3 && (masked || !corrected)) { ok = true; break }
        }
        if (!ok) return null
        var ss = 0.0
        for (q in active) {
            val rho = range(q)
            val v = q[3] - (rho + s[3] + (if (masked) atmosphereDelay(s, q, rho, ep.tRxTow) else 0.0)); ss += v * v
        }
        val rms = sqrt(ss / active.size)
        val llh = PositionSolver.ecefToLlh(s[0], s[1], s[2])
        return PositionSolver.Solution(Math.toDegrees(llh[0]), Math.toDegrees(llh[1]), llh[2], active.size, rms * 2.0,
            doubleArrayOf(s[0], s[1], s[2]))
    }

    /** Mažiausių kvadratų žingsnis: (HᵀH)⁻¹ Hᵀ r. */
    private fun lsq(h: Array<DoubleArray>, r: DoubleArray): DoubleArray? {
        val n = 4
        val a = Array(n) { DoubleArray(n + 1) }
        for (i in h.indices) for (j in 0 until n) {
            for (k in 0 until n) a[j][k] += h[i][j] * h[i][k]
            a[j][n] += h[i][j] * r[i]
        }
        for (c in 0 until n) {
            var p = c
            for (k in c + 1 until n) if (abs(a[k][c]) > abs(a[p][c])) p = k
            if (abs(a[p][c]) < 1e-12) return null
            val t = a[c]; a[c] = a[p]; a[p] = t
            for (k in 0 until n) if (k != c) {
                val f = a[k][c] / a[c][c]
                for (j in c..n) a[k][j] -= f * a[c][j]
            }
        }
        return DoubleArray(n) { a[it][n] / a[it][it] }
    }

    class Result(val epochsTotal: Int, val inRange: Int, val skipped: Int, val sols: List<Pair<Long, PositionSolver.Solution>>,
        /** Atmestų palydovų skaičius kiekvienoje SP3 intervalo epochoje (tik v2). */
        val dropped: List<Int> = emptyList()) {
        fun medianHoriz(ref: Pair<Double, Double> = REF_LAT to REF_LON): Double? {
            if (sols.isEmpty()) return null
            val d = sols.map { PositionSolver.horizDist(it.second.lat, it.second.lon, ref.first, ref.second) }.sorted()
            return if (d.size % 2 == 1) d[d.size / 2] else (d[d.size / 2 - 1] + d[d.size / 2]) / 2
        }
    }

    /** Įrašo laiko intervalas (GPS s) iš gnss_raw.csv epochų; null – epochų nėra. */
    fun recordingRange(raw: File): Pair<Double, Double>? {
        var a = Double.MAX_VALUE; var b = -Double.MAX_VALUE
        PositionSolver.readEpochs(raw) { ep -> val t = ep.week * W + ep.tRxTow; if (t < a) a = t; if (t > b) b = t }
        return if (a <= b) a to b else null
    }

    /** „SP3 failas netinka: …“, kai įrašas nėra visas SP3 intervale; null – tinka arba nežinoma. */
    fun staleNote(sp3: Sp3Reader, rec: Pair<Double, Double>?): String? {
        if (rec == null || sp3.epochCount == 0) return null
        val s0 = sp3.epochs.first(); val s1 = sp3.epochs.last()
        if (Sp3Reader.coverage(s0, s1, rec.first, rec.second) == Sp3Reader.COVERS) return null
        return "SP3 failas netinka: apima ${Sp3Reader.utc(s0)}–${Sp3Reader.utc(s1)} UTC, įrašas ${Sp3Reader.utc(rec.first)}–${Sp3Reader.utc(rec.second)} UTC"
    }

    /** gnss_raw.csv + SP3 -> gnss_solver_sp3_test.csv; epochos už SP3 intervalo praleidžiamos ir skaičiuojamos. */
    fun solveFile(raw: File, sp3: Sp3Reader, out: File?, corrected: Boolean = false, rot: Rot = Rot.LEGACY): Result {
        var total = 0; var inR = 0; var skipped = 0
        val dropped = ArrayList<Int>()
        val sols = ArrayList<Pair<Long, PositionSolver.Solution>>()
        val w = out?.bufferedWriter()
        try {
            w?.write(PositionSolver.SOLVER_HEADER)
            PositionSolver.readEpochs(raw) { ep ->
                total++
                if (!inRange(sp3, ep.week * W + ep.tRxTow)) { skipped++; return@readEpochs }
                inR++
                var drop = 0
                val s = solve(ep, sp3, corrected, rot) { drop = it }
                if (corrected) dropped.add(drop)
                if (s == null) return@readEpochs
                sols.add(ep.timeMs to s)
                w?.write(String.format(Locale.US, "%d,%.8f,%.8f,%.2f,%d,%.2f\n", ep.timeMs, s.lat, s.lon, s.h, s.sats, s.hacc))
            }
        } finally { w?.close() }
        return Result(total, inR, skipped, sols, dropped)
    }

    /** Poslinkiai nuo atskaitos taško: [vid. rytai, vid. šiaurė, atstumo mediana, sklaida aplink savo medianą (RMS)], m. */
    fun offsets(sols: List<Pair<Long, PositionSolver.Solution>>, ref: Pair<Double, Double>): DoubleArray? {
        if (sols.isEmpty()) return null
        val mPerDeg = Math.toRadians(1.0) * 6_371_000.0
        val cl = cos(Math.toRadians(ref.first))
        val e = sols.map { (it.second.lon - ref.second) * mPerDeg * cl }
        val n = sols.map { (it.second.lat - ref.first) * mPerDeg }
        val me = PositionSolver.percentile(e.sorted(), 0.5); val mn = PositionSolver.percentile(n.sorted(), 0.5)
        val d = e.indices.map { sqrt(e[it] * e[it] + n[it] * n[it]) }.sorted()
        val sc = sqrt(e.indices.sumOf { (e[it] - me) * (e[it] - me) + (n[it] - mn) * (n[it] - mn) } / e.size)
        return doubleArrayOf(e.average(), n.average(), PositionSolver.percentile(d, 0.5), sc)
    }

    /** „vid. rytai … m, vid. šiaurė … m, sklaida … m“ arba „nėra“. */
    fun offsetText(r: Result?, ref: Pair<Double, Double>?): String {
        val o = if (r == null || ref == null) null else offsets(r.sols, ref)
        return o?.let { "vid. rytai %+.2f m, vid. šiaurė %+.2f m, atstumo mediana %.2f m, sklaida aplink savo medianą %.2f m; sprendinių %d".format(
            Locale.US, it[0], it[1], it[2], it[3], r!!.sols.size) } ?: "nėra"
    }

    /** „SP3 SAGNAC TESTAS“: tas pats įrašas be pasukimo, su −ω·τ ir su +ω·τ (v2 pataisos); v3 failas – −ω·τ. */
    fun sagnacBlock(raw: File, sp3: Sp3Reader, outV3: File, ref: Pair<Double, Double>?): String = buildString {
        appendLine()
        appendLine("--- SP3 SAGNAC TESTAS ---")
        appendLine("ω = 7.2921151467e-5 rad/s; τ = geometrinis atstumas / c, perskaičiuojamas kiekvienoje iteracijoje")
        appendLine("Atskaitos taškas: " + (ref?.let { "%.7f, %.7f (gnss_fix.csv mediana)".format(Locale.US, it.first, it.second) } ?: "nėra"))
        val none = try { solveFile(raw, sp3, null, corrected = true, rot = Rot.NONE) } catch (e: Exception) { null }
        val minus = try { solveFile(raw, sp3, outV3, corrected = true, rot = Rot.MINUS) } catch (e: Exception) { null }
        val plus = try { solveFile(raw, sp3, null, corrected = true, rot = Rot.PLUS) } catch (e: Exception) { null }
        appendLine("Be pasukimo: " + offsetText(none, ref))
        appendLine("−ω·τ (teisinga, numatytoji, $OUT_FILE_V3): " + offsetText(minus, ref))
        appendLine("+ω·τ (neteisingas ženklas): " + offsetText(plus, ref))
        appendLine("Išvada: v1/v2 jau naudojo −ω·τ (τ = pr/c + dt_sv); v3 skiriasi tik τ skaičiavimu.")
    }

    /** „SP3 TESTAS v2“: atstumai iki planšetės fikso ir aukščiai, v2 ir v1 palyginimui. */
    fun v2Block(v1: Result, v2: Result?, fix: File, refOverride: Pair<Double, Double>? = null): String = buildString {
        appendLine()
        appendLine("--- SP3 TESTAS v2 ---")
        appendLine("Pataisos: Saastamoinen, Klobuchar (numatytieji koeficientai), maska 10°; failas $OUT_FILE_V2")
        val fixH = PositionSolver.medianFixHeight(fix)
        val ref = refOverride ?: PositionSolver.medianFix(fix)
        appendLine("Atskaitos taškas – " + (if (refOverride != null) PositionSolver.refLabel() else "šio įrašo gnss_fix.csv mediana") + ": " +
            (ref?.let { "%.7f, %.7f".format(Locale.US, it.first, it.second) } ?: "nėra (gnss_fix.csv tuščias)") +
            "; elipsoidinio aukščio mediana: " + (fixH?.let { "%.2f m".format(Locale.US, it) } ?: "nėra"))
        fun line(name: String, r: Result?) {
            val sols = r?.sols ?: emptyList()
            if (sols.isEmpty()) { appendLine("$name: nėra sprendinių"); return }
            if (ref == null) { appendLine("$name: sprendinių ${sols.size}; atstumų nėra (nėra atskaitos taško)"); return }
            val d = sols.map { PositionSolver.horizDist(it.second.lat, it.second.lon, ref.first, ref.second) }.sorted()
            val h = PositionSolver.percentile(sols.map { it.second.h }.sorted(), 0.5)
            appendLine("$name: mediana %.2f m, 90 %% %.2f m, maks. %.2f m; aukščio mediana %.2f m, skirtumas nuo fikso %s; sprendinių %d".format(Locale.US,
                PositionSolver.percentile(d, 0.5), PositionSolver.percentile(d, 0.9), d.last(), h,
                fixH?.let { "%.2f m".format(Locale.US, h - it) } ?: "nėra", sols.size))
        }
        line("v2", v2)
        line("v1", v1)
        appendLine("v2 poslinkiai: " + offsetText(v2, ref))
        appendLine("v1 poslinkiai: " + offsetText(v1, ref))
        val dropped = v2?.dropped ?: emptyList()
        appendLine(maskSummary(dropped))
    }

    /** Kaukės santrauka: iš viso, epochų su atmestais ir pasiskirstymas „atmesta→epochų“ (vietoj ilgos eilutės). */
    fun maskSummary(dropped: List<Int>): String {
        if (dropped.isEmpty()) return "Atmesta palydovų (< 10°): nėra duomenų"
        val hist = dropped.groupingBy { it }.eachCount().toSortedMap()
        return "Atmesta palydovų (< 10°): iš viso ${dropped.sum()}; epochų su atmestais: ${dropped.count { it > 0 }} iš ${dropped.size}; " +
            "pasiskirstymas (atmesta→epochų): " + hist.entries.joinToString(", ") { "${it.key}→${it.value}" }
    }

    /** ref – šio įrašo gnss_fix.csv mediana (null – nėra); note – „SP3 failas netinka: …“. */
    fun diagBlock(r: Result?, error: String? = null, ref: Pair<Double, Double>? = REF_LAT to REF_LON, note: String? = null): String = buildString {
        appendLine()
        appendLine("--- SP3 SOLVER TESTAS ---")
        if (r == null) { appendLine("Neatlikta: ${error ?: "nėra SP3"}"); return@buildString }
        note?.let { appendLine(it) }
        appendLine("Epochų gnss_raw.csv: ${r.epochsTotal}; SP3 intervale: ${r.inRange}; praleista (už intervalo): ${r.skipped}")
        appendLine("Sprendinių įrašyta ($OUT_FILE): ${r.sols.size}")
        if (ref == null) appendLine("Atskaitos taškas: nėra (gnss_fix.csv tuščias) – atstumai neskaičiuojami")
        else appendLine("Horizontalaus skirtumo iki atskaitos taško (%.7f, %.7f – gnss_fix.csv mediana) mediana: ".format(Locale.US, ref.first, ref.second) +
            (r.medianHoriz(ref)?.let { "%.2f m".format(Locale.US, it) } ?: "nėra"))
        appendLine("Poslinkiai nuo atskaitos taško: " + offsetText(r, ref))
        appendLine("Pirmieji 3 sprendiniai:")
        if (r.sols.isEmpty()) appendLine("  (nėra)")
        r.sols.take(3).forEach { (t, s) ->
            appendLine("  %d: %.7f, %.7f, h=%.2f m, palydovų %d, hacc %.2f m".format(Locale.US, t, s.lat, s.lon, s.h, s.sats, s.hacc))
        }
    }
}
