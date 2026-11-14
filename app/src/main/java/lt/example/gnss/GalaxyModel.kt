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
}
