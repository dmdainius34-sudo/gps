package lt.example.gnss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Paukščių Tako plokštuma pusiaujo J2000 sistemoje (IAU galaktinės koordinatės) ir paveikslėlio mastelis. */
object GalaxyModel {
    /** Galaktikos šiaurės polius ir centras (Sgr A*), J2000, laipsniais. */
    const val NGP_RA = 192.85948; const val NGP_DEC = 27.12825
    const val GC_RA = 266.40510; const val GC_DEC = -28.93617
    /** Saulės atstumas nuo centro paveikslėlio pločio dalimis (~26 700 š. m., diskas ~100 000 š. m.). */
    const val SUN_FRAC = 0.27
    /** Disko pusplotis scenoje (Žemės spindulys 0,25). */
    const val SCENE_R = 12f

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

    /** Schema, kai NASA paveikslėlio nėra: branduolys, 4 vijos (sukasi pagal laikrodžio rodyklę žiūrint iš šiaurės poliaus), Saulė. */
    fun schematic(size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(Color.BLACK)
        val cx = size / 2f; val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = java.util.Random(7)
        for (arm in 0 until 4) for (i in 0 until 2600) {
            val t = rnd.nextDouble()
            val r = 0.06 + 0.42 * t
            val th = arm * Math.PI / 2 - 3.2 * Math.log(r / 0.06) + rnd.nextGaussian() * 0.12
            val x = cx + (r * cos(th) * size).toFloat(); val y = cx + (r * sin(th) * size).toFloat()
            p.color = Color.argb((40 + 120 * exp(-2 * t)).toInt().coerceIn(0, 255), 170, 190, 255)
            c.drawCircle(x, y, size * 0.0025f, p)
        }
        p.shader = RadialGradient(cx, cx, size * 0.12f, intArrayOf(Color.rgb(255, 236, 190), Color.argb(0, 255, 200, 140)), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cx, size * 0.12f, p); p.shader = null
        p.color = Color.rgb(255, 220, 80); c.drawCircle(cx, cx + (SUN_FRAC * size).toFloat(), size * 0.006f, p)
        return b
    }
}
