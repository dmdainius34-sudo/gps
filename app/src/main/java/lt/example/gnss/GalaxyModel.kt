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
    fun points(n: Int = 250_000, seed: Long = 42): FloatArray {
        val rnd = java.util.Random(seed)
        val out = FloatArray(n * 7)
        fun put(i: Int, x: Double, y: Double, z: Double, r: Float, g: Float, b: Float, a: Float) {
            val o = i * 7; out[o] = x.toFloat(); out[o + 1] = y.toFloat(); out[o + 2] = z.toFloat()
            out[o + 3] = r; out[o + 4] = g; out[o + 5] = b; out[o + 6] = a * 0.5f
        }
        val barA = Math.toRadians(27.0); val bux = -cos(barA); val buy = sin(barA)
        for (i in 0 until n) {
            val u = rnd.nextDouble()
            when {
                u < 0.10 -> {                                              // skersė – minkštas pailgas elipsoidas, be kietų galų
                    var t: Double; do { t = rnd.nextGaussian() * 1.6 } while (kotlin.math.abs(t) > 5.0)
                    val w = rnd.nextGaussian() * (0.55 - 0.06 * kotlin.math.abs(t))
                    put(i, bux * t - buy * w, buy * t + bux * w, rnd.nextGaussian() * 0.3, 1f, 0.82f, 0.55f, 0.13f)
                }
                u < 0.18 -> {                                              // centrinis gumulas – apvalus, eksponentinis kraštas
                    val r = -0.6 * kotlin.math.ln(1 - rnd.nextDouble() * 0.995)
                    val th = kotlin.math.acos(2 * rnd.nextDouble() - 1); val ph = 2 * Math.PI * rnd.nextDouble()
                    put(i, r * sin(th) * cos(ph), r * sin(th) * sin(ph), 0.7 * r * cos(th), 1f, 0.78f, 0.5f, 0.12f)
                }
                u < 0.24 -> {                                              // vidinis žiedas aplink skersės galus („3 kpc vijos“)
                    val phi = rnd.nextDouble() * 2 * Math.PI
                    val ax = 4.6 + rnd.nextGaussian() * 0.35; val bx = 3.4 + rnd.nextGaussian() * 0.35   // ištemptas skersės kryptimi
                    val t = ax * cos(phi); val w = bx * sin(phi)
                    put(i, bux * t - buy * w, buy * t + bux * w, rnd.nextGaussian() * 0.15, 0.85f, 0.85f, 0.95f, 0.16f)
                }
                u < 0.70 -> {                                              // 4 vijos
                    val arm = rnd.nextInt(4)
                    val r = 3.0 * Math.pow(16.0 / 3.0, rnd.nextDouble())
                    val phi = Math.PI + kotlin.math.ln(r / ARM_R[arm]) / K
                    val sc = 0.3 + 0.03 * r
                    val x = r * cos(phi) + rnd.nextGaussian() * sc; val y = r * sin(phi) + rnd.nextGaussian() * sc
                    val fade = (1.0 - ((r - 12.0) / 5.0).coerceIn(0.0, 1.0)).toFloat()
                    if (rnd.nextDouble() < 0.07) put(i, x, y, rnd.nextGaussian() * 0.08, 1f, 0.45f, 0.6f, 0.55f * fade)
                    else put(i, x, y, rnd.nextGaussian() * 0.12, 0.7f, 0.8f, 1f, 0.28f * fade)
                }
                u < 0.72 -> {                                              // Oriono atšaka – Saulė jos viduje
                    val phi = Math.PI + (rnd.nextDouble() - 0.5) * 0.8; val r = R_SUN + rnd.nextGaussian() * 0.25
                    put(i, r * cos(phi) + rnd.nextGaussian() * 0.35, r * sin(phi) + rnd.nextGaussian() * 0.35, rnd.nextGaussian() * 0.12, 0.75f, 0.85f, 1f, 0.15f)
                }
                else -> {                                                  // plonasis diskas (senos žvaigždės) – iki pat centro, viduje praretėjęs
                    var r: Double
                    do { r = -3.0 * kotlin.math.ln(1 - rnd.nextDouble()) } while (r > 15.0 || rnd.nextDouble() > 0.35 + 0.65 * (r / 3.0).coerceAtMost(1.0))
                    val phi = rnd.nextDouble() * 2 * Math.PI
                    put(i, r * cos(phi), r * sin(phi), rnd.nextGaussian() * 0.25, 1f, 0.92f, 0.8f, 0.07f)
                }
            }
        }
        return out
    }

    /**
     * Tarpžvaigždinės dulkės: [x, y, z, dydis (kpc), neskaidrumas] kiekvienam debesėliui (ta pati sistema kaip points()).
     * Piešiamos po už jų esančiomis žvaigždėmis – sugeria jų šviesą (mėlyną labiau, todėl už dulkių paraudę), taip atsiranda
     * tamsios juostos ir šešėliai, iš kurių matyti disko gylis, storis ir tolumas.
     *  - juostos vijų vidiniame (įgaubtame) krašte, kur dujos atsitrenkia į tankio bangą – gumulais (debesų kompleksai, ištempti išilgai vijos);
     *  - dvi juostos skersės priekiniuose kraštuose (sukimasis pagal laikrodžio rodyklę žiūrint iš šiaurės poliaus);
     *  - tankus centrinis žiedas (~0,2–0,5 kpc) ir retas difuzinis sluoksnis (eksponentinis diskas, ~3,5 kpc, storis ~0,07 kpc).
     */
    fun dust(n: Int = 12_000, seed: Long = 7): FloatArray {
        val rnd = java.util.Random(seed)
        val out = FloatArray(n * 5)
        var i = 0
        fun put(x: Double, y: Double, z: Double, size: Double, op: Double) {
            if (i >= n) return
            val o = i * 5; out[o] = x.toFloat(); out[o + 1] = y.toFloat(); out[o + 2] = z.toFloat(); out[o + 3] = size.toFloat(); out[o + 4] = op.toFloat(); i++
        }
        val barA = Math.toRadians(27.0); val bux = -cos(barA); val buy = sin(barA)
        val norm = sqrt(1 + K * K)
        while (i < n) {
            val u = rnd.nextDouble()
            when {
                u < 0.20 -> {                                              // vijų juostos: debesų kompleksas
                    val arm = rnd.nextInt(4)
                    val r = 3.2 * Math.pow(15.0 / 3.2, rnd.nextDouble())
                    val phi = Math.PI + kotlin.math.ln(r / ARM_R[arm]) / K
                    val rd = r - (0.22 + 0.025 * r)                        // vidinis (įgaubtas) vijos kraštas
                    val cx = rd * cos(phi); val cy = rd * sin(phi)
                    val tx = (K * cos(phi) - sin(phi)) / norm; val ty = (K * sin(phi) + cos(phi)) / norm   // vijos liestinė
                    val nx = cos(phi); val ny = sin(phi)
                    val fade = 1.0 - ((r - 11.5) / 4.0).coerceIn(0.0, 1.0)
                    val zc = rnd.nextGaussian() * 0.035
                    repeat(3 + rnd.nextInt(5)) {
                        val along = rnd.nextGaussian() * 0.28; val across = rnd.nextGaussian() * 0.06
                        put(cx + tx * along + nx * across, cy + ty * along + ny * across, zc + rnd.nextGaussian() * 0.025,
                            0.09 + 0.10 * rnd.nextDouble(), (0.16 + 0.18 * rnd.nextDouble()) * fade)
                    }
                }
                u < 0.27 -> {                                              // skersės priekinių kraštų juostos
                    val end = if (rnd.nextBoolean()) 1.0 else -1.0
                    val t = end * (0.5 + 3.8 * rnd.nextDouble())
                    val w = -end * (0.40 + 0.06 * rnd.nextGaussian() + 0.04 * kotlin.math.abs(t))
                    put(bux * t - buy * w, buy * t + bux * w, rnd.nextGaussian() * 0.03, 0.08 + 0.07 * rnd.nextDouble(), 0.22 + 0.18 * rnd.nextDouble())
                }
                u < 0.30 -> {                                              // centrinis žiedas
                    val r = 0.2 + 0.3 * rnd.nextDouble(); val ph = rnd.nextDouble() * 2 * Math.PI
                    put(r * cos(ph), r * sin(ph), rnd.nextGaussian() * 0.03, 0.06 + 0.05 * rnd.nextDouble(), 0.25 + 0.2 * rnd.nextDouble())
                }
                else -> {                                                  // difuzinis sluoksnis
                    var r: Double
                    do { r = -3.5 * kotlin.math.ln(1 - rnd.nextDouble()) } while (r < 1.5 || r > 14.0)
                    val ph = rnd.nextDouble() * 2 * Math.PI
                    put(r * cos(ph), r * sin(ph), rnd.nextGaussian() * 0.07, 0.20 + 0.25 * rnd.nextDouble(), 0.04 + 0.05 * rnd.nextDouble())
                }
            }
        }
        return out
    }
}
