package lt.example.gnss

import java.io.InputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.math.sqrt

/**
 * SP3-c/d tikslių orbitų skaitytuvas (pvz. WUM0MGXRAP_*_ORB.SP3.gz).
 * Antraštė: #d (pradžia, epochų skaičius), ## (GPS savaitė, sekundės, intervalas).
 * Epochos: "*  yyyy mm dd hh mm ss"; padėtys: "P<sat> X Y Z clk" (km, μs). EP ir V eilutės praleidžiamos.
 * Laikas – GPS laiko sekundės nuo 1980-01-06 (SP3 laiko sistema GPS).
 */
class Sp3Reader private constructor(
    val startWeek: Int,
    val startSow: Double,
    val intervalSec: Double,
    val headerEpochs: Int,
    /** Epochų laikai (GPS sekundės nuo 1980-01-06), didėjančia tvarka. */
    val epochs: DoubleArray,
    /** Palydovas -> [epochų skaičius × 4] (X, Y, Z km; laikrodis μs; NaN – nėra). */
    private val data: Map<String, DoubleArray>
) {
    class State(val x: Double, val y: Double, val z: Double, val clockUs: Double)

    val satellites: Set<String> get() = data.keys

    val epochCount: Int get() = epochs.size

    /** Įrašo reikšmė epochoje k (be interpoliacijos): X, Y, Z km, laikrodis μs. */
    fun record(sat: String, k: Int): DoubleArray? {
        val d = data[sat] ?: return null
        if (k !in epochs.indices || d[4 * k].isNaN()) return null
        return doubleArrayOf(d[4 * k], d[4 * k + 1], d[4 * k + 2], d[4 * k + 3])
    }

    private val validPos = HashMap<String, IntArray>()
    private val validClk = HashMap<String, IntArray>()

    /** Palydovo epochų indeksai su padėtimi (pos = true) arba su laikrodžiu (pos = false). */
    private fun valid(sat: String, pos: Boolean): IntArray? = synchronized(this) {
        val cache = if (pos) validPos else validClk
        cache[sat] ?: run {
            val d = data[sat] ?: return null
            val off = if (pos) 0 else 3
            epochs.indices.filter { !d[4 * it + off].isNaN() }.toIntArray().also { cache[sat] = it }
        }
    }

    /**
     * Lagranžo interpoliacija per POINTS artimiausių to paties palydovo įrašų: langas centruojamas ties artimiausia epocha,
     * failo kraštuose pastumiamas, kad liktų įrašų ribose. Už pirmo / paskutinio įrašo grąžinama krašto reikšmė.
     * comp – komponentų poslinkiai įraše (0..3); exclude – praleidžiamas epochos indeksas (leave-one-out patikrai).
     */
    private fun lagrange(sat: String, t: Double, comps: IntArray, pos: Boolean, exclude: Int = -1): DoubleArray? {
        val d = data[sat] ?: return null
        val idx = (valid(sat, pos) ?: return null).let { v -> if (exclude >= 0) v.filter { it != exclude }.toIntArray() else v }
        if (idx.isEmpty()) return null
        val out = DoubleArray(comps.size)
        fun take(k: Int) { for (c in comps.indices) out[c] = d[4 * k + comps[c]] }
        if (t <= epochs[idx.first()]) { take(idx.first()); return out }
        if (t >= epochs[idx.last()]) { take(idx.last()); return out }
        // artimiausias įrašas (dvejetainė paieška)
        var lo = 0; var hi = idx.size - 1
        while (hi - lo > 1) { val m = (lo + hi) ushr 1; if (epochs[idx[m]] <= t) lo = m else hi = m }
        val near = if (t - epochs[idx[lo]] <= epochs[idx[hi]] - t) lo else hi
        if (idx.size < POINTS) {                                   // per mažai įrašų – tiesinė
            val a = idx[lo]; val b = idx[hi]; val f = (t - epochs[a]) / (epochs[b] - epochs[a])
            for (c in comps.indices) out[c] = d[4 * a + comps[c]] + f * (d[4 * b + comps[c]] - d[4 * a + comps[c]])
            return out
        }
        val s = (near - POINTS / 2).coerceIn(0, idx.size - POINTS)
        for (a in s until s + POINTS) {
            val i = idx[a]
            var li = 1.0
            for (b in s until s + POINTS) if (b != a) { val j = idx[b]; li *= (t - epochs[j]) / (epochs[i] - epochs[j]) }
            for (c in comps.indices) out[c] += li * d[4 * i + comps[c]]
        }
        return out
    }

    private val XYZ = intArrayOf(0, 1, 2)
    private val CLK = intArrayOf(3)

    /** Padėtis X, Y, Z (km, ECEF): 11 eilės Lagranžo interpoliacija per 11 artimiausių įrašų. */
    fun positionXyz(sat: String, gpsWeek: Int, gpsSeconds: Double): DoubleArray? =
        lagrange(sat, gpsWeek * WEEK + gpsSeconds, XYZ, true)

    /** Laikrodžio pataisa (μs): ta pati interpoliacija (tiesinė, jei įrašų mažiau nei 11). */
    fun clock(sat: String, gpsWeek: Int, gpsSeconds: Double): Double? =
        lagrange(sat, gpsWeek * WEEK + gpsSeconds, CLK, false)?.get(0)

    /** Padėtis ir laikrodis kartu. */
    fun position(sat: String, gpsWeek: Int, gpsSeconds: Double): State? {
        val p = positionXyz(sat, gpsWeek, gpsSeconds) ?: return null
        return State(p[0], p[1], p[2], clock(sat, gpsWeek, gpsSeconds) ?: Double.NaN)
    }

    /** Leave-one-out: įrašas k interpoliuojamas iš kaimynų be jo paties; grąžina 3D paklaidą metrais. */
    fun leaveOneOutError(sat: String, k: Int): Double? {
        val truth = record(sat, k) ?: return null
        val p = lagrange(sat, epochs[k], XYZ, true, exclude = k) ?: return null
        val dx = p[0] - truth[0]; val dy = p[1] - truth[1]; val dz = p[2] - truth[2]
        return sqrt(dx * dx + dy * dy + dz * dz) * 1000.0
    }

    /** Krašto patikra: prieš pirmą ir po paskutinio įrašo grąžinamos krašto reikšmės, be išimčių. */
    fun edgeCheck(sat: String): Boolean = try {
        val v = valid(sat, true)
        if (v == null || v.isEmpty()) false else {
            val first = record(sat, v.first())!!; val last = record(sat, v.last())!!
            val t0 = epochs[v.first()] - 600.0; val t1 = epochs[v.last()] + 600.0
            val w0 = Math.floorDiv(t0.toLong(), WEEK.toLong()).toInt(); val w1 = Math.floorDiv(t1.toLong(), WEEK.toLong()).toInt()
            val a = positionXyz(sat, w0, t0 - w0 * WEEK); val b = positionXyz(sat, w1, t1 - w1 * WEEK)
            a != null && b != null && (0..2).all { a[it] == first[it] && b[it] == last[it] }
        }
    } catch (e: Exception) { false }

    /** Leave-one-out epochos: 00:05, 06:00, 12:00, 18:00 ir priešpaskutinė (23:55). */
    fun looEpochs(): List<Int> {
        val n = epochs.size
        return if (n < 3) emptyList() else listOf(1, n / 4, n / 2, 3 * n / 4, n - 2).distinct()
    }

    /** Palydovų skaičius pagal konsteliaciją (G, R, E, C, J ...). */
    fun perConstellation(): Map<Char, Int> = data.keys.groupingBy { it[0] }.eachCount().toSortedMap()

    /** Mažiausias ir didžiausias spindulys (km) per visas palydovo epochas. */
    fun radiusRange(sat: String): Pair<Double, Double>? {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        for (k in epochs.indices) {
            val r = record(sat, k) ?: continue
            val rad = sqrt(r[0] * r[0] + r[1] * r[1] + r[2] * r[2])
            if (rad < lo) lo = rad
            if (rad > hi) hi = rad
        }
        return if (lo == Double.MAX_VALUE) null else lo to hi
    }

    companion object {
        const val ORDER = 11
        /** Interpoliacijos taškų skaičius (11 artimiausių įrašų). */
        const val POINTS = 11
        const val WEEK = 604800.0
        /** G01 2026-11-04 00:00:00 pavyzdiniame faile (km). */
        val G01_REF = doubleArrayOf(15137.948274, -16111.611868, 14635.953497)

        /** GPS laiko sekundės nuo 1980-01-06 kalendorinei datai (GPS laiku). */
        fun gpsSeconds(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Double): Double {
            val days = java.time.LocalDate.of(y, mo, d).toEpochDay() - java.time.LocalDate.of(1980, 1, 6).toEpochDay()
            return days * 86400.0 + h * 3600.0 + mi * 60.0 + s
        }

        /** Skaito SP3 (gzip arba paprastą) srautą. */
        fun read(input: InputStream, gzip: Boolean = true): Sp3Reader {
            val stream = if (gzip) GZIPInputStream(input) else input
            var week = 0; var sow = 0.0; var interval = 300.0; var nEp = 0
            val times = ArrayList<Double>()
            val rows = HashMap<String, ArrayList<Pair<Int, DoubleArray>>>()
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    when {
                        line.startsWith("#") && !line.startsWith("##") && line.length >= 40 ->
                            nEp = line.substring(32, 39).trim().toIntOrNull() ?: 0
                        line.startsWith("##") -> {
                            val p = line.substring(2).trim().split(Regex("\\s+"))
                            week = p.getOrNull(0)?.toIntOrNull() ?: 0
                            sow = p.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                            interval = p.getOrNull(2)?.toDoubleOrNull() ?: 300.0
                        }
                        line.startsWith("* ") -> {
                            val p = line.substring(1).trim().split(Regex("\\s+"))
                            times.add(gpsSeconds(p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toInt(), p[4].toInt(), p[5].toDouble()))
                        }
                        line.startsWith("P") && line.length >= 46 && times.isNotEmpty() -> {
                            val sat = line.substring(1, 4).replace(' ', '0')
                            val x = line.substring(4, 18).trim().toDoubleOrNull() ?: continue
                            val y = line.substring(18, 32).trim().toDoubleOrNull() ?: continue
                            val z = line.substring(32, 46).trim().toDoubleOrNull() ?: continue
                            val c = if (line.length >= 60) line.substring(46, 60).trim().toDoubleOrNull() else null
                            if (x == 0.0 && y == 0.0 && z == 0.0) continue        // trūkstama padėtis
                            val clk = if (c == null || c >= 999999.0) Double.NaN else c
                            rows.getOrPut(sat) { ArrayList() }.add(times.size - 1 to doubleArrayOf(x, y, z, clk))
                        }
                        // EP, V, EOF ir kitos eilutės praleidžiamos
                    }
                }
            }
            val n = times.size
            val data = HashMap<String, DoubleArray>()
            for ((sat, list) in rows) {
                val arr = DoubleArray(4 * n) { Double.NaN }
                for ((k, v) in list) System.arraycopy(v, 0, arr, 4 * k, 4)
                data[sat] = arr
            }
            return Sp3Reader(week, sow, interval, nEp, times.toDoubleArray(), data)
        }

        /** „SP3 TESTAS“ blokas diagnostikai. */
        fun diagBlock(name: String, r: Sp3Reader?, error: String? = null, searched: List<String> = emptyList()): String = buildString {
            appendLine()
            appendLine("--- SP3 TESTAS ---")
            appendLine("Failas: $name")
            if (searched.isNotEmpty()) {
                appendLine("Paieškos vietos:")
                searched.forEach { appendLine("  $it") }
            }
            if (r == null) { appendLine("Nepavyko nuskaityti: ${error ?: "failo nėra"}"); return@buildString }
            appendLine("Pradžia: GPS savaitė ${r.startWeek}, ${"%.0f".format(Locale.US, r.startSow)} s; intervalas ${"%.0f".format(Locale.US, r.intervalSec)} s")
            appendLine("Epochų: ${r.epochCount} (antraštėje ${r.headerEpochs})")
            appendLine("Palydovų pagal konsteliaciją: " + r.perConstellation().entries.joinToString(" ") { "${it.key}=${it.value}" } +
                " (iš viso ${r.satellites.size})")
            val g = r.record("G01", 0)
            val ok = g != null && (0..2).all { kotlin.math.abs(g[it] - G01_REF[it]) <= 1e-6 }
            appendLine("G01 patikra (pirmoji epocha = 15137.948274, -16111.611868, 14635.953497 km): ${if (ok) "PASS" else "FAIL"}")
            if (g != null) appendLine("G01 spindulys pirmojoje epochoje: %.3f km".format(Locale.US, sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])))
            val rr = r.radiusRange("G01")
            appendLine("G01 spindulio intervalas: " + (rr?.let { "%.3f – %.3f km".format(Locale.US, it.first, it.second) } ?: "nėra"))
            appendLine()
            appendLine("--- SP3 INTERPOLIACIJA ---")
            appendLine("G01 leave-one-out (11 artimiausių įrašų be tikrinamo, 11 eilės Lagranžas):")
            var mx = 0.0
            for (k in r.looEpochs()) {
                val e = r.leaveOneOutError("G01", k)
                val t = r.epochs[k] - r.epochs[0]
                appendLine("  %02d:%02d – %s".format(Locale.US, (t / 3600).toInt(), ((t % 3600) / 60).toInt(),
                    e?.let { "%.4f m".format(Locale.US, it) } ?: "nėra"))
                if (e != null && e > mx) mx = e
            }
            appendLine("Didžiausia paklaida: %.4f m (riba 0.05 m: %s)".format(Locale.US, mx, if (mx < 0.05) "PASS" else "FAIL"))
            appendLine("Krašto patikra (prieš pirmą / po paskutinio įrašo): ${if (r.edgeCheck("G01")) "PASS" else "FAIL"}")
        }
    }
}
