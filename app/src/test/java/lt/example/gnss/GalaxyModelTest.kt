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

class NearStarsTest {
    @Test fun nearerStarsCollapseFirst() {
        val l = NearStars.list
        val st = NearStars.starts(l)
        val start = l.indices.associate { l[it].name to st[it] }
        assertEquals(0f, start["Alpha Centauri"]!!, 1e-6f)
        assertEquals(NearStars.START_MAX, start["Deneb"]!!, 1e-6f)
        org.junit.Assert.assertTrue(start["Sirius"]!! < start["Polaris"]!! && start["Polaris"]!! < start["Rigel"]!!)
        // be α Kentauro (nematoma iš Lietuvos) pirmasis – Sirijus
        val north = l.filter { it.name != "Alpha Centauri" }
        assertEquals(0f, NearStars.starts(north)[north.indexOfFirst { it.name == "Sirius" }], 1e-6f)
        for (s in l) assertEquals(1.0, kotlin.math.sqrt((s.dir[0] * s.dir[0] + s.dir[1] * s.dir[1] + s.dir[2] * s.dir[2]).toDouble()), 1e-5)
    }
}

class RealAccTest {
    @Test fun openSkyIsGoodOneSidedWeakIsBad() {
        val az = doubleArrayOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)
        val el = doubleArrayOf(30.0, 60.0, 25.0, 50.0, 35.0, 70.0, 20.0, 45.0)
        val open = RealAcc.horizontal(az, el, DoubleArray(8) { 42.0 })
        org.junit.Assert.assertTrue("atviras dangus $open m", open < 3.0)
        val waz = doubleArrayOf(200.0, 230.0, 250.0, 270.0, 290.0, 310.0)
        val wel = doubleArrayOf(30.0, 60.0, 40.0, 75.0, 25.0, 50.0)
        val roof = RealAcc.horizontal(waz, wel, DoubleArray(6) { 26.0 })
        org.junit.Assert.assertTrue("po stogu iš vakarų $roof m", roof > 10.0)
    }
}

class CourseHeadingTest {
    @Test fun learnsMountThenCorrectsDrift() {
        CourseHeading.mountDeg = Double.NaN
        var t = 1_000_000L
        // ką tik kalibruota: planšetė pasukta 30° į kairę nuo važiavimo krypties (kursas 100°, rodoma 70°)
        repeat(40) { CourseHeading.onMove(t, 100.0, 70.0, t - 1000) { }; t += 1000 }
        assertEquals(30.0, CourseHeading.mountDeg, 0.5)
        // vėliau giroskopas nudrifavo +8°: rodoma 78°, tikra 70°
        var fix = Double.NaN
        repeat(40) { CourseHeading.onMove(t, 100.0, 78.0, null) { fix = it }; t += 1000 }
        assertEquals(8.0, fix, 0.5)
    }
}

class PosFilterTest {
    @Test fun stationaryAveragingSettlesAndRejectsJumps() {
        PosFilter.reset()
        val rnd = java.util.Random(1)
        var out = DoubleArray(4)
        for (i in 0 until 300) {
            val jump = if (i % 25 == 0) 0.0005 else 0.0          // retkarčiais ~55 m šuolis
            out = PosFilter.step(1_000_000L + i * 1000L, 54.7 + rnd.nextGaussian() * 0.00006 + jump, 25.3 + rnd.nextGaussian() * 0.0001, 200.0, 10.0, false)
        }
        assertEquals(54.7, out[0], 0.00003)                     // ~3 m
        assertEquals(25.3, out[1], 0.00005)
        org.junit.Assert.assertTrue(out[3] >= 5.0)               // ne mažiau nei pusė realaus
    }
}
