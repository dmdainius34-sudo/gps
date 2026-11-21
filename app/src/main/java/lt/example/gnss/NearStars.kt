package lt.example.gnss

import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Gerai žinomos ryškios žvaigždės (J2000, atstumai šviesmečiais) – atitolinant vaizdą jos palieka savo vietą fone ir
 * „sutraukiamos“ į tikrą 3D vietą aplink Saulę: iš stebėtojui matomų (virš horizonto) – artimiausios pirmiausia, tolimiausios paskiausiai.
 */
object NearStars {
    class Star(val name: String, raH: Double, decDeg: Double, val ly: Double, val mag: Double, val rgb: FloatArray) {
        val dir: FloatArray = run {
            val ra = Math.toRadians(raH * 15.0); val de = Math.toRadians(decDeg)
            floatArrayOf((cos(de) * cos(ra)).toFloat(), (cos(de) * sin(ra)).toFloat(), sin(de).toFloat())
        }
    }

    private fun h(h: Int, m: Int, s: Double) = h + m / 60.0 + s / 3600.0
    private fun d(sign: Int, d: Int, m: Int, s: Double) = sign * (d + m / 60.0 + s / 3600.0)
    private val W = floatArrayOf(0.85f, 0.9f, 1f)        // balta–melsva
    private val B = floatArrayOf(0.7f, 0.8f, 1f)         // mėlyna
    private val Y = floatArrayOf(1f, 0.95f, 0.8f)        // gelsva
    private val O = floatArrayOf(1f, 0.75f, 0.5f)        // oranžinė
    private val R = floatArrayOf(1f, 0.6f, 0.4f)         // raudona

    /** Iki šios kolapso dalies prasideda visų žvaigždžių kelionė; kiekviena trunka SPAN. */
    const val START_MAX = 0.5f
    const val SPAN = 0.3f

    val list: List<Star> = listOf(
        Star("Alpha Centauri", h(14, 39, 36.5), d(-1, 60, 50, 2.0), 4.37, -0.27, Y),
        Star("Sirius", h(6, 45, 8.9), d(-1, 16, 42, 58.0), 8.6, -1.46, W),
        Star("Procyon", h(7, 39, 18.1), d(1, 5, 13, 30.0), 11.5, 0.34, Y),
        Star("Altair", h(19, 50, 47.0), d(1, 8, 52, 6.0), 16.7, 0.77, W),
        Star("Vega", h(18, 36, 56.3), d(1, 38, 47, 1.0), 25.0, 0.03, W),
        Star("Fomalhaut", h(22, 57, 39.0), d(-1, 29, 37, 20.0), 25.1, 1.16, W),
        Star("Pollux", h(7, 45, 18.9), d(1, 28, 1, 34.0), 33.8, 1.14, O),
        Star("Arcturus", h(14, 15, 39.7), d(1, 19, 10, 57.0), 36.7, -0.05, O),
        Star("Denebola", h(11, 49, 3.6), d(1, 14, 34, 19.0), 35.9, 2.13, W),
        Star("Capella", h(5, 16, 41.4), d(1, 45, 59, 53.0), 42.9, 0.08, Y),
        Star("Rasalhague", h(17, 34, 56.1), d(1, 12, 33, 36.0), 48.6, 2.08, W),
        Star("Alderamin", h(21, 18, 34.8), d(1, 62, 35, 8.0), 49.0, 2.45, W),
        Star("Castor", h(7, 34, 35.9), d(1, 31, 53, 18.0), 51.0, 1.58, W),
        Star("Caph", h(0, 9, 10.7), d(1, 59, 8, 59.0), 54.7, 2.28, Y),
        Star("Aldebaran", h(4, 35, 55.2), d(1, 16, 30, 33.0), 65.3, 0.85, R),
        Star("Hamal", h(2, 7, 10.4), d(1, 23, 27, 45.0), 66.0, 2.00, O),
        Star("Regulus", h(10, 8, 22.3), d(1, 11, 58, 2.0), 79.3, 1.35, B),
        Star("Merak", h(11, 1, 50.5), d(1, 56, 22, 57.0), 79.7, 2.37, W),
        Star("Megrez", h(12, 15, 25.6), d(1, 57, 1, 57.0), 80.5, 3.31, W),
        Star("Menkalinan", h(5, 59, 31.7), d(1, 44, 56, 51.0), 81.1, 1.90, W),
        Star("Alioth", h(12, 54, 1.7), d(1, 55, 57, 35.0), 82.6, 1.77, W),
        Star("Phecda", h(11, 53, 49.8), d(1, 53, 41, 41.0), 83.2, 2.44, W),
        Star("Mizar", h(13, 23, 55.5), d(1, 54, 55, 31.0), 82.9, 2.27, W),
        Star("Algol", h(3, 8, 10.1), d(1, 40, 57, 20.0), 90.0, 2.12, B),
        Star("Alpheratz", h(0, 8, 23.3), d(1, 29, 5, 26.0), 97.0, 2.06, B),
        Star("Ruchbah", h(1, 25, 49.0), d(1, 60, 14, 7.0), 99.4, 2.68, W),
        Star("Alkaid", h(13, 47, 32.4), d(1, 49, 18, 48.0), 104.0, 1.86, B),
        Star("Alhena", h(6, 37, 42.7), d(1, 16, 23, 57.0), 109.0, 1.93, W),
        Star("Dubhe", h(11, 3, 43.7), d(1, 61, 45, 3.0), 123.0, 1.79, O),
        Star("Kochab", h(14, 50, 42.3), d(1, 74, 9, 20.0), 131.0, 2.08, O),
        Star("Elnath", h(5, 26, 17.5), d(1, 28, 36, 27.0), 134.0, 1.65, B),
        Star("Achernar", h(1, 37, 42.8), d(-1, 57, 14, 12.0), 139.0, 0.46, B),
        Star("Markab", h(23, 4, 45.7), d(1, 15, 12, 19.0), 140.0, 2.49, B),
        Star("Eltanin", h(17, 56, 36.4), d(1, 51, 29, 20.0), 154.0, 2.23, O),
        Star("Alphard", h(9, 27, 35.2), d(-1, 8, 39, 31.0), 177.0, 1.98, O),
        Star("Scheat", h(23, 3, 46.5), d(1, 28, 4, 58.0), 196.0, 2.42, R),
        Star("Mirach", h(1, 9, 43.9), d(1, 35, 37, 14.0), 197.0, 2.07, R),
        Star("Schedar", h(0, 40, 30.4), d(1, 56, 32, 14.0), 228.0, 2.24, O),
        Star("Bellatrix", h(5, 25, 7.9), d(1, 6, 20, 59.0), 250.0, 1.64, B),
        Star("Spica", h(13, 25, 11.6), d(-1, 11, 9, 41.0), 250.0, 0.97, B),
        Star("Mimosa", h(12, 47, 43.3), d(-1, 59, 41, 19.0), 280.0, 1.25, B),
        Star("Canopus", h(6, 23, 57.1), d(-1, 52, 41, 45.0), 310.0, -0.74, W),
        Star("Acrux", h(12, 26, 35.9), d(-1, 63, 5, 57.0), 320.0, 0.76, B),
        Star("Almach", h(2, 3, 54.0), d(1, 42, 19, 47.0), 350.0, 2.10, O),
        Star("Hadar", h(14, 3, 49.4), d(-1, 60, 22, 23.0), 390.0, 0.61, B),
        Star("Albireo", h(19, 30, 43.3), d(1, 27, 57, 35.0), 430.0, 3.05, O),
        Star("Adhara", h(6, 58, 37.5), d(-1, 28, 58, 20.0), 430.0, 1.50, B),
        Star("Polaris", h(2, 31, 49.1), d(1, 89, 15, 51.0), 433.0, 1.98, Y),
        Star("Segin", h(1, 54, 23.7), d(1, 63, 40, 12.0), 440.0, 3.37, B),
        // Sietynas (Plejados)
        Star("Alcyone", h(3, 47, 29.1), d(1, 24, 6, 18.0), 440.0, 2.87, B),
        Star("Atlas", h(3, 49, 9.7), d(1, 24, 3, 12.0), 440.0, 3.62, B),
        Star("Electra", h(3, 44, 52.5), d(1, 24, 6, 48.0), 440.0, 3.70, B),
        Star("Maia", h(3, 45, 49.6), d(1, 24, 22, 4.0), 440.0, 3.86, B),
        Star("Merope", h(3, 46, 19.6), d(1, 23, 56, 54.0), 440.0, 4.17, B),
        Star("Taygeta", h(3, 45, 12.5), d(1, 24, 28, 2.0), 440.0, 4.29, B),
        Star("Mirzam", h(6, 22, 42.0), d(-1, 17, 57, 21.0), 490.0, 1.98, B),
        Star("Mirfak", h(3, 24, 19.4), d(1, 49, 51, 40.0), 510.0, 1.79, Y),
        Star("Betelgeuse", h(5, 55, 10.3), d(1, 7, 24, 25.0), 548.0, 0.50, R),
        Star("Navi", h(0, 56, 42.5), d(1, 60, 43, 0.0), 550.0, 2.47, B),
        Star("Antares", h(16, 29, 24.5), d(-1, 26, 25, 55.0), 550.0, 1.09, R),
        Star("Shaula", h(17, 33, 36.5), d(-1, 37, 6, 14.0), 570.0, 1.62, B),
        Star("Saiph", h(5, 47, 45.4), d(-1, 9, 40, 11.0), 650.0, 2.06, B),
        Star("Enif", h(21, 44, 11.2), d(1, 9, 52, 30.0), 690.0, 2.39, O),
        Star("Rigel", h(5, 14, 32.3), d(-1, 8, 12, 6.0), 863.0, 0.13, B),
        Star("Mintaka", h(5, 32, 0.4), d(-1, 0, 17, 57.0), 1200.0, 2.23, B),
        Star("Alnitak", h(5, 40, 45.5), d(-1, 1, 56, 34.0), 1260.0, 1.77, B),
        Star("Wezen", h(7, 8, 23.5), d(-1, 26, 23, 36.0), 1600.0, 1.83, Y),
        Star("Sadr", h(20, 22, 13.7), d(1, 40, 15, 24.0), 1800.0, 2.23, Y),
        Star("Alnilam", h(5, 36, 12.8), d(-1, 1, 12, 7.0), 2000.0, 1.69, B),
        Star("Deneb", h(20, 41, 25.9), d(1, 45, 16, 49.0), 2600.0, 1.25, W),
    )

    /** Kolapso pradžios 0..START_MAX pagal log atstumą tarp duotų (pvz. stebėtojui matomų) žvaigždžių: artimiausia – 0, tolimiausia – START_MAX. */
    fun starts(vis: List<Star>): FloatArray {
        if (vis.isEmpty()) return FloatArray(0)
        val lo = log10(vis.minOf { it.ly }); val hi = log10(vis.maxOf { it.ly })
        return FloatArray(vis.size) { if (hi - lo < 1e-9) 0f else (START_MAX * (log10(vis[it].ly) - lo) / (hi - lo)).toFloat() }
    }
}
