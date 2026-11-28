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

    /** Precesija J2000 → data (IAU 1976): (RA, Dec) laipsniais. */
    fun precess(ra0Deg: Double, dec0Deg: Double, ms: Long): Pair<Double, Double> {
        val t = daysJ2000(ms) / 36525.0
        val sec = Math.PI / 180.0 / 3600.0
        val zeta = (2306.2181 * t + 0.30188 * t * t + 0.017998 * t * t * t) * sec
        val z = (2306.2181 * t + 1.09468 * t * t + 0.018203 * t * t * t) * sec
        val th = (2004.3109 * t - 0.42665 * t * t - 0.041833 * t * t * t) * sec
        val r0 = Math.toRadians(ra0Deg); val d0 = Math.toRadians(dec0Deg)
        val a = cos(d0) * sin(r0 + zeta)
        val b = cos(th) * cos(d0) * cos(r0 + zeta) - sin(th) * sin(d0)
        val c = sin(th) * cos(d0) * cos(r0 + zeta) + cos(th) * sin(d0)
        var ra = Math.toDegrees(atan2(a, b) + z); ra = ((ra % 360.0) + 360.0) % 360.0
        return ra to Math.toDegrees(atan2(c, sqrt(a * a + b * b)))
    }

    /** Šiaurinė žvaigždė (Polaris, α UMi) – J2000 RA 37,95456°, Dec 89,26411°. */
    fun polaris(ms: Long): Pair<Double, Double> = precess(37.95456, 89.26411, ms)

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
