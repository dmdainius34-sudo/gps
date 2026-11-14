package lt.example.gnss

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Paukščių Tako plokštuma pusiaujo J2000 sistemoje (IAU galaktinės koordinatės). */
object GalaxyModel {
    /** Galaktikos šiaurės polius ir centras (Sgr A*), J2000, laipsniais. */
    const val NGP_RA = 192.85948; const val NGP_DEC = 27.12825
    const val GC_RA = 266.40510; const val GC_DEC = -28.93617

    fun unit(raDeg: Double, decDeg: Double): DoubleArray {
        val ra = Math.toRadians(raDeg); val de = Math.toRadians(decDeg)
        return doubleArrayOf(cos(de) * cos(ra), cos(de) * sin(ra), sin(de))
    }

    /** [x0,x1,x2, y0,y1,y2, z0,z1,z2]: x – į centrą (l = 0°), y – l = 90°, z – šiaurės polius. */
    fun frame(): DoubleArray {
        val z = unit(NGP_RA, NGP_DEC); val g = unit(GC_RA, GC_DEC)
        val dot = g[0] * z[0] + g[1] * z[1] + g[2] * z[2]
        val x = DoubleArray(3) { g[it] - dot * z[it] }
        val n = sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]); for (i in 0..2) x[i] /= n
        val y = doubleArrayOf(z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0])
        return x + y + z
    }

    /** Saulės atstumas iki centro, kpc. */
    const val R_SUN = 8.2
    private val K = kotlin.math.tan(Math.toRadians(12.0))           // vijų posvyris 12°
    /** Vijų spinduliai Saulės azimute (kpc): Scutum–Centaurus, Sagittarius–Carina, Perseus, Outer (žingsnis ×1,40 = e^(k·π/2)). */
    val ARM_R = doubleArrayOf(4.9, 6.86, 9.6, 13.4)

    /**
     * Procedūrinis 3D modelis: [x, y, z, r, g, b, a] kiekvienam taškui, kpc, galaktocentrinė sistema –
     * x nuo Saulės į centrą, y – l = 90°, z – šiaurės polius; Saulė (−8,2; 0; 0). Sukimasis pagal laikrodžio rodyklę žiūrint iš poliaus,
     * todėl vijos (logaritminės, 12°) tolsta didėjant azimutui. Skersė ~4,5 kpc, artimasis galas – teigiamose ilgumose (~27°).
     */
    fun points(n: Int = 60_000, seed: Long = 42): FloatArray {
        val rnd = java.util.Random(seed)
        val out = FloatArray(n * 7)
        fun put(i: Int, x: Double, y: Double, z: Double, r: Float, g: Float, b: Float, a: Float) {
            val o = i * 7; out[o] = x.toFloat(); out[o + 1] = y.toFloat(); out[o + 2] = z.toFloat()
            out[o + 3] = r; out[o + 4] = g; out[o + 5] = b; out[o + 6] = a
        }
        val barA = Math.toRadians(27.0); val bux = -cos(barA); val buy = sin(barA)
        for (i in 0 until n) {
            val u = rnd.nextDouble()
            when {
                u < 0.10 -> {                                              // skersė
                    val t = (rnd.nextGaussian() * 1.7).coerceIn(-4.5, 4.5); val w = rnd.nextGaussian() * 0.45
                    put(i, bux * t - buy * w, buy * t + bux * w, rnd.nextGaussian() * 0.3, 1f, 0.82f, 0.55f, 0.13f)
                }
                u < 0.18 -> {                                              // centrinis gumulas
                    put(i, rnd.nextGaussian() * 0.75, rnd.nextGaussian() * 0.75, rnd.nextGaussian() * 0.5, 1f, 0.78f, 0.5f, 0.12f)
                }
                u < 0.70 -> {                                              // 4 vijos
                    val arm = rnd.nextInt(4)
                    val r = 3.0 * Math.pow(16.0 / 3.0, rnd.nextDouble())
                    val phi = Math.PI + kotlin.math.ln(r / ARM_R[arm]) / K
                    val sc = 0.15 + 0.02 * r
                    val x = r * cos(phi) + rnd.nextGaussian() * sc; val y = r * sin(phi) + rnd.nextGaussian() * sc
                    val fade = (1.0 - ((r - 12.0) / 5.0).coerceIn(0.0, 1.0)).toFloat()
                    if (rnd.nextDouble() < 0.07) put(i, x, y, rnd.nextGaussian() * 0.08, 1f, 0.45f, 0.6f, 0.55f * fade)
                    else put(i, x, y, rnd.nextGaussian() * 0.12, 0.7f, 0.8f, 1f, 0.28f * fade)
                }
                u < 0.72 -> {                                              // Oriono atšaka – prie Saulės
                    val phi = Math.PI + (rnd.nextDouble() - 0.6) * 0.7; val r = 8.45 + rnd.nextGaussian() * 0.2
                    put(i, r * cos(phi) + rnd.nextGaussian() * 0.25, r * sin(phi) + rnd.nextGaussian() * 0.25, rnd.nextGaussian() * 0.1, 0.75f, 0.85f, 1f, 0.15f)
                }
                else -> {                                                  // plonasis diskas (senos žvaigždės)
                    var r: Double; do { r = 1.5 - 3.0 * kotlin.math.ln(1 - rnd.nextDouble()) } while (r > 15.0)
                    val phi = rnd.nextDouble() * 2 * Math.PI
                    put(i, r * cos(phi), r * sin(phi), rnd.nextGaussian() * 0.25, 1f, 0.92f, 0.8f, 0.07f)
                }
            }
        }
        return out
    }
}
