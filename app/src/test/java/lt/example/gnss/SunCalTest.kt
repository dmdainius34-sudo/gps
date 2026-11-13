package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class SunCalTest {
    /** 2026-11-13 10:00 UTC, 54,74° N, 25,25° E. NOAA saulės algoritmas: azimutas 179,20°, geometrinis aukštis 17,24°. */
    @Test fun sunPositionVilnius() {
        val ms = 1_794_564_000_000L
        val (az, el) = SunCal.sunAzEl(ms, 54.74, 25.25)
        assertEquals(17.24 + SunCal.refraction(17.24), el, 0.3)
        assertEquals(17.0, el, 1.0)
        assertEquals(179.20, az, 0.5)
    }

    /** Sukimo matrica (prietaisas → rytai, šiaurė, aukštyn): azimutas h, nugarėlė −Z pakelta kampu p. */
    private fun rot(hDeg: Double, pDeg: Double, rollDeg: Double = 0.0): FloatArray {
        val h = Math.toRadians(hDeg); val p = Math.toRadians(pDeg); val q = Math.toRadians(rollDeg)
        // −Z (nugarėlė) pasaulyje: horizontali kryptis h, pakelta p
        val back = doubleArrayOf(sin(h) * cos(p), cos(h) * cos(p), sin(p))
        val z = DoubleArray(3) { -back[it] }
        // +Y – statmena, kuo arčiau vertikalės; pasukta apie Z kampu roll
        val up = doubleArrayOf(0.0, 0.0, 1.0)
        val d = up[0] * z[0] + up[1] * z[1] + up[2] * z[2]
        var y = DoubleArray(3) { up[it] - d * z[it] }; val yl = kotlin.math.sqrt(y.sumOf { it * it }); y = DoubleArray(3) { y[it] / yl }
        var x = doubleArrayOf(y[1] * z[2] - y[2] * z[1], y[2] * z[0] - y[0] * z[2], y[0] * z[1] - y[1] * z[0])
        val x2 = DoubleArray(3) { x[it] * cos(q) + y[it] * sin(q) }; val y2 = DoubleArray(3) { -x[it] * sin(q) + y[it] * cos(q) }
        // R eilutėmis: R[i][j] = pasaulio ašis i, prietaiso ašis j (stulpeliai – x, y, z)
        return FloatArray(9) { k -> val i = k / 3; val j = k % 3; (when (j) { 0 -> x2[i]; 1 -> y2[i]; else -> z[i] }).toFloat() }
    }

    @Test fun azimuthIndependentOfPitch() {
        val a0 = SunCal.deviceAzEl(rot(170.0, 10.0)); val a1 = SunCal.deviceAzEl(rot(170.0, 30.0)); val a2 = SunCal.deviceAzEl(rot(170.0, 30.0, 15.0))
        assertEquals(170.0, a0.first, 0.01)
        assertEquals(a0.first, a1.first, 0.01)                 // pitch +20° – azimutas nesikeičia
        assertEquals(a0.first, a2.first, 0.01)                 // ir pasukus apie žiūrėjimo ašį
        assertEquals(10.0, a0.second, 0.01); assertEquals(30.0, a1.second, 0.01)
        assertEquals("-Z", a1.third)
    }

    @Test fun logLineColumns() {
        val r = SunCal.Rec(1_794_564_000_000L, 54.74, 25.25, 190.0, 3.0, 179.2, 17.3, 181.0, 18.0, 1.8, 175.0, 9.0, 30.0, 0.0, "Samsung SM-X926B")
        val cols = r.line().trim().split(",")
        assertEquals(SunCal.HEADER.trim().split(",").size, cols.size)
        assertEquals("2026-11-13T10:00:00Z", cols[0])
        assertEquals(4.8, r.magDiff, 1e-9)                      // 175 + 9 − 179,2
    }
}

class SunCalLogTest {
    @Test fun trueHeadingFromMagnetometerAndLog() {
        // kalibruojant: magnetinis 175°, deklinacija 9°, Saulė 179,2° → klaida +4,8°
        val r = SunCal.Rec(0L, 54.74, 25.25, 190.0, 3.0, 179.2, 17.3, 181.0, 18.0, 1.8, 175.0, 9.0, 30.0, 0.0, "x")
        val e = SunCal.Entry(1, 0L, 54.74, 25.25, 17.3, 18.0, 1.8, 175.0, r.magDiff, "")
        assertEquals(true, e.good)
        // vėliau magnetometras rodo 85° → tikra kryptis 85 + 9 − 4,8 = 89,2°
        assertEquals(89.2, SunCal.trueFromMag(e, 85.0, 9.0), 1e-9)
        val bad = SunCal.Entry(2, 0L, 54.74, 25.25, 17.0, -20.0, 4.4, 170.0, 3.0, "")
        assertEquals(false, bad.good)
    }
}

class FormatPrecedenceTest {
    /** Kotlin: "a" + "b".format(...) formatuoja tik "b" – todėl abi dalys skliaustuose. */
    @Test fun wholeStringFormatted() {
        val t = (("Kalibravimas: %s, azimutas %.1f°") + ", pataisa %+.1f°").format(java.util.Locale.US, "2026-11-13", 179.2, 4.4)
        assertEquals("Kalibravimas: 2026-11-13, azimutas 179.2°, pataisa +4.4°", t)
    }
}
