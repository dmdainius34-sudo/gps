package lt.example.gnss

import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Bendri astronominiai skaičiavimai (tie patys naudojami 3D vaizde, ataskaitoje ir testuose). */
object Astro {
    /** IAU galaktinė -> pusiaujo (J2000) sukimo matrica (pusiaujo -> galaktinė transponuota). */
    private val EQ_TO_GAL = arrayOf(
        doubleArrayOf(-0.0548755604, -0.8734370902, -0.4838350155),
        doubleArrayOf(0.4941094279, -0.4448296300, 0.7469822445),
        doubleArrayOf(-0.8676661490, -0.1980763734, 0.4559837762))

    /** Galaktinės (l, b) laipsniais -> (RA, Dec) laipsniais, RA 0..360. */
    fun galToEq(lDeg: Double, bDeg: Double): Pair<Double, Double> {
        val l = Math.toRadians(lDeg); val b = Math.toRadians(bDeg)
        val g = doubleArrayOf(cos(b) * cos(l), cos(b) * sin(l), sin(b))
        val e = DoubleArray(3) { i -> (0..2).sumOf { k -> EQ_TO_GAL[k][i] * g[k] } }
        var ra = Math.toDegrees(atan2(e[1], e[0])); if (ra < 0) ra += 360.0
        return ra to Math.toDegrees(asin(e[2].coerceIn(-1.0, 1.0)))
    }

    fun daysJ2000(ms: Long): Double = ms / 86400000.0 + 2440587.5 - 2451545.0

    /** Vidutinis Grinvičo žvaigždinis laikas, laipsniais 0..360. */
    fun gmstDeg(ms: Long): Double = ((280.46061837 + 360.98564736629 * daysJ2000(ms)) % 360.0 + 360.0) % 360.0

    fun eclToEq(lon: Double, lat: Double): DoubleArray {
        val e = Math.toRadians(23.4397)
        val xe = cos(lat) * cos(lon); val ye = cos(lat) * sin(lon); val ze = sin(lat)
        return doubleArrayOf(xe, ye * cos(e) - ze * sin(e), ye * sin(e) + ze * cos(e))
    }

    /** Saulės kryptis pusiaujo (sideralinėje) sistemoje – ta pati formulė kaip 3D vaizde. */
    fun sunEq(ms: Long): DoubleArray {
        val d = daysJ2000(ms)
        val m = Math.toRadians(357.5291 + 0.98560028 * d)
        val c = Math.toRadians(1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + Math.toRadians(102.9372) + Math.PI
        return eclToEq(l, 0.0)
    }

    /** Pusiaujo vektorius -> (azimutas, aukštis) laipsniais stebėtojui (platuma, ilguma). */
    fun altAz(v: DoubleArray, latDeg: Double, lonDeg: Double, ms: Long): Pair<Double, Double> {
        val ra = atan2(v[1], v[0]); val dec = asin((v[2] / sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])).coerceIn(-1.0, 1.0))
        return altAzRaDec(Math.toDegrees(ra), Math.toDegrees(dec), latDeg, lonDeg, ms)
    }

    fun altAzRaDec(raDeg: Double, decDeg: Double, latDeg: Double, lonDeg: Double, ms: Long): Pair<Double, Double> {
        val h = Math.toRadians(hourAngleDeg(raDeg, lonDeg, ms)); val d = Math.toRadians(decDeg); val p = Math.toRadians(latDeg)
        val alt = asin((sin(p) * sin(d) + cos(p) * cos(d) * cos(h)).coerceIn(-1.0, 1.0))
        var az = Math.toDegrees(atan2(-cos(d) * sin(h), sin(d) * cos(p) - cos(d) * sin(p) * cos(h)))
        if (az < 0) az += 360.0
        return az to Math.toDegrees(alt)
    }

    /** Valandinis kampas (laipsniais, −180..180). */
    fun hourAngleDeg(raDeg: Double, lonDeg: Double, ms: Long): Double {
        var h = gmstDeg(ms) + lonDeg - raDeg
        h = ((h + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return h
    }

    fun raDeg(v: DoubleArray): Double { var r = Math.toDegrees(atan2(v[1], v[0])); if (r < 0) r += 360.0; return r }
}

/** Sesijos ZIP: kiekvienas failas vienu vardu (pasikartojantys – su priesaga). */
object SessionZip {
    fun create(out: File, entries: List<Pair<String, () -> InputStream>>): List<String> {
        val names = ArrayList<String>()
        out.parentFile?.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for ((name0, open) in entries) {
                var name = name0; var k = 2
                while (name in names) { name = name0.substringBeforeLast('.') + "_$k." + name0.substringAfterLast('.', "bin"); k++ }
                try {
                    open().use { ins -> z.putNextEntry(ZipEntry(name)); ins.copyTo(z); z.closeEntry() }
                    names.add(name)
                } catch (e: Exception) { /* nepasiekiamas failas praleidžiamas */ }
            }
        }
        return names
    }

    fun name(ms: Long, model: String): String {
        val t = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(ms))
        return "gnss_${t}_${model.replace(Regex("[^A-Za-z0-9_-]"), "_")}.zip"
    }
}
