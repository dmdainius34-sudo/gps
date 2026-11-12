package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CraneTest {
    @Test
    fun circleGeometry() {
        Crane.on = true; Crane.lat = 54.7398; Crane.lon = 25.2545; Crane.radius = 3.0; Crane.height = Double.NaN
        // 5 m į rytus nuo ašies -> projektuota 3 m į rytus, kampas 90°
        val e2 = (1 / 298.257223563) * (2 - 1 / 298.257223563); val sl = Math.sin(Math.toRadians(Crane.lat))
        val nRad = 6_378_137.0 / Math.sqrt(1 - e2 * sl * sl)
        val e5 = Crane.lon + Math.toDegrees(5.0 / (nRad * Math.cos(Math.toRadians(Crane.lat))))
        assertEquals(90.0, Crane.slewDeg(Crane.lat, e5), 1e-6)
        val (pl, po) = Crane.project(Crane.lat, e5)
        assertEquals(3.0, Crane.en(pl, po)[0], 1e-3); assertEquals(0.0, Crane.en(pl, po)[1], 1e-3)
        // pseudo-matavimas: taškas 5 m nuo ašies -> inovacija −2 m, H rodo nuo ašies
        val x = PositionSolver.llhToEcef(Math.toRadians(Crane.lat), Math.toRadians(e5), 0.0)
        val ps = Crane.pseudo(x)
        assertEquals(1, ps.size)
        assertEquals(-2.0, ps[0].second, 1e-2)
        val h = ps[0].first; assertTrue(Math.abs(Math.sqrt(h[0] * h[0] + h[1] * h[1] + h[2] * h[2]) - 1.0) < 1e-9)
        Crane.on = false
    }

    @Test
    fun degreeInput() {
        assertEquals(54.7400171, Crane.parseDeg("547400171", 90.0)!!, 1e-9)
        assertEquals(54.7400171, Crane.parseDeg("54,7400171", 90.0)!!, 1e-9)
        assertEquals(25.2545205, Crane.parseDeg("252545205", 180.0)!!, 1e-9)
        assertEquals(25.2545205, Crane.parseDeg("25.2545205", 180.0)!!, 1e-9)
        assertEquals(null, Crane.parseDeg("ab", 90.0))
    }
}
