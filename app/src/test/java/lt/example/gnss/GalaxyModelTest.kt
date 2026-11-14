package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2

class GalaxyModelTest {
    @Test fun axesMatchIauGalacticFrame() {
        val f = GalaxyModel.frame()
        fun radec(i: Int): Pair<Double, Double> =
            ((Math.toDegrees(atan2(f[i + 1], f[i])) + 360) % 360) to Math.toDegrees(asin(f[i + 2]))
        val (xr, xd) = radec(0); val (yr, yd) = radec(3)
        assertEquals(266.405, xr, 0.05); assertEquals(-28.936, xd, 0.05)   // l = 0°, b = 0°
        assertEquals(318.004, yr, 0.05); assertEquals(48.330, yd, 0.05)    // l = 90°, b = 0°
    }
}

class GalaxyPointsTest {
    @Test fun sunSitsBetweenSagittariusAndPerseus() {
        org.junit.Assert.assertTrue(GalaxyModel.ARM_R[1] < GalaxyModel.R_SUN && GalaxyModel.R_SUN < GalaxyModel.ARM_R[2])
        val p = GalaxyModel.points(5000)
        for (i in 0 until 5000) {
            val r = kotlin.math.sqrt(p[i * 7] * p[i * 7] + p[i * 7 + 1] * p[i * 7 + 1])
            org.junit.Assert.assertTrue("taškas $i už galaktikos ribų: $r kpc", r.isFinite() && r < 25f)
        }
    }
}
