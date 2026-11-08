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

    /**
     * Padėtis (ECEF, km) ir laikrodžio pataisa (μs) duotu GPS laiku.
     * Padėtis – 11 eilės Lagranžo interpoliacija (12 gretimų 5 min. įrašų, langas centruojamas, kraštuose pastumiamas);
     * laikrodis – tiesinė interpoliacija tarp gretimų įrašų. Grąžina null, jei laikas už failo ribų ar trūksta įrašų.
     */
    fun position(sat: String, gpsWeek: Int, gpsSeconds: Double): State? {
        val d = data[sat] ?: return null
        val t = gpsWeek * WEEK + gpsSeconds
        val n = epochs.size
        if (n < ORDER + 1 || t < epochs[0] - 1e-6 || t > epochs[n - 1] + 1e-6) return null
        // artimiausias įrašas
        var k = ((t - epochs[0]) / intervalSec).toInt().coerceIn(0, n - 1)
        while (k + 1 < n && epochs[k + 1] <= t) k++
        while (k > 0 && epochs[k] > t) k--
        val pts = ORDER + 1
        var s = k - (pts / 2 - 1)
        s = s.coerceIn(0, n - pts)
        val xyz = DoubleArray(3)
        for (i in s until s + pts) {
            if (d[4 * i].isNaN()) return null
            var li = 1.0
            for (j in s until s + pts) if (j != i) li *= (t - epochs[j]) / (epochs[i] - epochs[j])
            xyz[0] += li * d[4 * i]; xyz[1] += li * d[4 * i + 1]; xyz[2] += li * d[4 * i + 2]
        }
        val c0 = d[4 * k + 3]
        val clk = if (k + 1 < n && t > epochs[k]) {
            val c1 = d[4 * (k + 1) + 3]
            c0 + (c1 - c0) * (t - epochs[k]) / (epochs[k + 1] - epochs[k])
        } else c0
        return State(xyz[0], xyz[1], xyz[2], clk)
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
        fun diagBlock(name: String, r: Sp3Reader?, error: String? = null): String = buildString {
            appendLine()
            appendLine("--- SP3 TESTAS ---")
            appendLine("Failas: $name")
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
        }
    }
}
