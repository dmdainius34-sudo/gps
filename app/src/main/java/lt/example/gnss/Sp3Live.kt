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

    /** ECEF (km) laiku t (GPS s) arba null. */
    fun ecefKm(id: String, t: Double): DoubleArray? {
        val r = reader ?: return null
        val w = Math.floorDiv(t.toLong(), Sp3Reader.WEEK.toLong()).toInt()
        return r.positionXyz(id, w, t - w * Sp3Reader.WEEK)
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

    // Android az/el palyginimas (didžiausi skirtumai nuo programos paleidimo)
    @Volatile var maxDaz = 0.0; @Volatile var maxDazId = ""
    @Volatile var maxDel = 0.0; @Volatile var maxDelId = ""
    @Volatile var compared = 0

    fun compare(id: String, azSp3: Double, elSp3: Double, azAnd: Double, elAnd: Double) {
        if (elAnd <= 0.0 || (azAnd == 0.0 && elAnd == 0.0)) return
        compared++
        val de = abs(elSp3 - elAnd)
        if (de > maxDel) { maxDel = de; maxDelId = id }
        if (elAnd in 5.0..80.0) {
            val da = abs(((azSp3 - azAnd + 540.0) % 360.0) - 180.0)
            if (da > maxDaz) { maxDaz = da; maxDazId = id }
        }
    }

    fun text(): String = if (compared == 0) "SP3 ir Android az/el palyginimas: nėra duomenų (SP3 neapima dabarties arba nėra palydovų)"
        else "SP3 ir Android az/el palyginimas (%d matavimų, SP3 %s): didžiausias Δaz %.2f° (%s, aukštis 5–80°), didžiausias Δel %.2f° (%s); riba 1°: %s"
            .format(Locale.US, compared, source, maxDaz, maxDazId, maxDel, maxDelId, if (maxDaz <= 1.0 && maxDel <= 1.0) "TAIP" else "NE")
}
