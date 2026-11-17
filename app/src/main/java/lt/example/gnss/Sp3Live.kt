package lt.example.gnss

import android.content.Context
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** SP3 3D vaizdui: dabartinis failas, palydovų ECEF padėtys ir Android az/el palyginimas. */
object Sp3Live {
    @Volatile var reader: Sp3Reader? = null
        private set
    @Volatile var source: String = ""
        private set
    @Volatile private var loadedAt = 0L
    @Volatile private var loading = false

    /** GPS laikas (s nuo 1980-01-06) dabar: UTC + 18 s. */
    fun gpsNow(ms: Long = System.currentTimeMillis()): Double = ms / 1000.0 - 315_964_800.0 + 18.0

    fun covers(t: Double): Boolean = reader?.let { Sp3Solver.inRange(it, t) } == true

    /** Kas 10 min. arba priverstinai (po „Atnaujinti SP3“) – fone parenka failą, kuris apima dabartį. */
    fun refresh(ctx: Context, force: Boolean = false) {
        try { Brdc.refresh(ctx.applicationContext) } catch (_: Exception) {}
        if (Uvi.enabled) try { Uvi.refresh(ctx.applicationContext) } catch (_: Exception) {}
        try { SunImage.refresh(ctx.applicationContext) } catch (_: Exception) {}
        try { Lasco.refresh(ctx.applicationContext) } catch (_: Exception) {}
        try { Donki.refresh(ctx.applicationContext) } catch (_: Exception) {}
        try { BroadcastEph.load(ctx.applicationContext.filesDir) } catch (e: Exception) { android.util.Log.w("GnssApp", "BroadcastEph.load: ${e.message}") }
        val now = System.currentTimeMillis()
        if (loading || (!force && now - loadedAt < 600_000L)) return
        loading = true; loadedAt = now
        Thread {
            try {
                val t = gpsNow()
                val search = Sp3Storage.search(ctx.applicationContext)
                val (f, _) = Sp3Locator.chooseByRange(search.all, t to t) { it.open().use { s -> Sp3Reader.headerRange(s) } }
                if (f != null) { reader = f.open().use { Sp3Reader.read(it) }; source = f.name }
            } catch (e: Exception) { android.util.Log.w("GnssApp", "Sp3Live: ${e.message}") }
            finally { loading = false }
        }.start()
    }

    /** SP3 palydovo ID iš Android konsteliacijos ir svid; null – nepalaikoma. */
    fun id(const: String, svid: Int): String? = when (const) {
        "GPS" -> "G%02d".format(Locale.US, svid)
        "GAL" -> "E%02d".format(Locale.US, svid)
        "BDS" -> "C%02d".format(Locale.US, svid)
        "GLO" -> if (svid in 1..24) "R%02d".format(Locale.US, svid) else null
        else -> null
    }

    /** Ar galima padėti bent dalį palydovų tiksliai: SP3 apima laiką arba yra GPS navigacinių efemeridžių. */
    fun available(t: Double): Boolean = covers(t) || BroadcastEph.any(t) || Brdc.set != null

    @Volatile var fromBroadcast = 0
        private set

    /** ECEF (km) laiku t (GPS s) arba null. SP3, jei apima laiką; GPS – kitaip iš navigacinių efemeridžių. */
    fun ecefKm(id: String, t: Double): DoubleArray? {
        val r = reader
        if (r != null && covers(t)) {
            val w = Math.floorDiv(t.toLong(), Sp3Reader.WEEK.toLong()).toInt()
            r.positionXyz(id, w, t - w * Sp3Reader.WEEK)?.let { return it }
        }
        return (BroadcastEph.ecefKm(id, t) ?: Brdc.ecefKm(id, t))?.also { fromBroadcast++ }
    }

    /** Topocentrinis (azimutas, aukštis) laipsniais iš ECEF (km) stebėtojui. */
    fun azEl(ecefKm: DoubleArray, latDeg: Double, lonDeg: Double, hM: Double): Pair<Double, Double> {
        val la = Math.toRadians(latDeg); val lo = Math.toRadians(lonDeg)
        val o = PositionSolver.llhToEcef(la, lo, hM)
        val d = DoubleArray(3) { ecefKm[it] * 1000.0 - o[it] }
        val e = -sin(lo) * d[0] + cos(lo) * d[1]
        val n = -sin(la) * cos(lo) * d[0] - sin(la) * sin(lo) * d[1] + cos(la) * d[2]
        val u = cos(la) * cos(lo) * d[0] + cos(la) * sin(lo) * d[1] + sin(la) * d[2]
        var az = Math.toDegrees(atan2(e, n)); if (az < 0) az += 360.0
        return az to Math.toDegrees(asin(u / sqrt(e * e + n * n + u * u)))
    }

    /** Kampinis atstumas (laipsniais) tarp dviejų krypčių (azimutas, aukštis). */
    fun angSep(az1: Double, el1: Double, az2: Double, el2: Double): Double {
        val a1 = Math.toRadians(az1); val e1 = Math.toRadians(el1); val a2 = Math.toRadians(az2); val e2 = Math.toRadians(el2)
        val c = sin(e1) * sin(e2) + cos(e1) * cos(e2) * cos(a1 - a2)
        return Math.toDegrees(kotlin.math.acos(c.coerceIn(-1.0, 1.0)))
    }

    // SP3 ir Android krypčių palyginimas pagal konsteliaciją (kampinis atstumas, aukštis ≥ 10°)
    private class Acc { val v = ArrayList<Double>(); var maxV = 0.0; var worst = "" }
    private val acc = java.util.TreeMap<Char, Acc>()
    @Volatile var compared = 0
        private set

    @Synchronized
    fun compare(id: String, azSp3: Double, elSp3: Double, azAnd: Double, elAnd: Double) {
        if (elAnd < 10.0 || (azAnd == 0.0 && elAnd == 0.0)) return
        val d = angSep(azSp3, elSp3, azAnd, elAnd)
        val a = acc.getOrPut(id[0]) { Acc() }
        if (a.v.size >= 5000) a.v.removeAt(0)
        a.v.add(d)
        if (d > a.maxV) { a.maxV = d; a.worst = id }
        compared++
    }

    /** Palydovai, kuriems SP3 padėties nėra (padėti pagal Android az/el, brūkšninė kreivė). */
    @Volatile var approx: List<String> = emptyList()
        private set
    fun setApprox(l: List<String>) { approx = l.sorted() }

    private fun approxLine(): String = "Palydovai be SP3 (padėti pagal Android az/el, brūkšninė kreivė): ${approx.size}" +
        (if (approx.isEmpty()) "" else " – " + approx.joinToString(", "))

    @Synchronized
    fun text(): String {
        if (compared == 0) return "SP3 ir Android krypčių palyginimas: nėra duomenų (SP3 neapima dabarties arba nėra palydovų ≥ 10°)\n" + approxLine() + "\n" + BroadcastEph.text() + "\n" + Brdc.text()
        val names = mapOf('G' to "GPS", 'E' to "Galileo", 'R' to "GLONASS", 'C' to "BeiDou")
        return "SP3 ir Android krypčių palyginimas (kampinis atstumas, aukštis ≥ 10°, SP3 $source${if (reader != null && !covers(gpsNow())) " – dabarties neapima, GPS/Galileo/GLONASS iš navigacinių efemeridžių" else ""}):\n" +
            listOf('G', 'E', 'R', 'C').joinToString("\n") { k ->
                val a = acc[k]
                if (a == null || a.v.isEmpty()) "  ${names[k]}: nėra"
                else {
                    val so = a.v.sorted()
                    "  %s: %d, mediana %.2f°, maks. %.2f° (%s); riba (mediana < 1°): %s".format(Locale.US, names[k], so.size, so[so.size / 2], a.maxV, a.worst,
                        if (so[so.size / 2] < 1.0) "TAIP" else "NE")
                }
            } + "\n  GLONASS: svid 1–24 = orbitinė vieta (SP3 Rnn); svid 93–106 (dažnio kanalas) nesiejamas\n" + approxLine() + "\n" + BroadcastEph.text() + "\n" + Brdc.text() +
                (if (fromBroadcast > 0) "\nPadėčių iš navigacinių efemeridžių (kai SP3 neapima arba palydovo neturi): $fromBroadcast" else "")
    }
}
