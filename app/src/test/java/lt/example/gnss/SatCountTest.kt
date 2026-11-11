package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

class SatCountTest {
    @Test
    fun uniqueSatellitesFromSignals() {
        val l = ArrayList<SatCount.Sig>()
        for (n in 1..16) { l.add(SatCount.Sig("BDS", n, n <= 10)); l.add(SatCount.Sig("BDS", n, false)) }   // B1I ir B1C
        for (n in 1..12) l.add(SatCount.Sig("GPS", n, n <= 8))
        for (n in 1..10) l.add(SatCount.Sig("GAL", n, n <= 6))
        for (n in 1..8) l.add(SatCount.Sig("GLO", n, n <= 4))
        for (n in 120..122) l.add(SatCount.Sig("SBS", n, false))
        val r = SatCount.count(l)
        assertEquals(65, r.sigVisible)
        assertEquals(49, r.satVisible)
        assertEquals(28, r.sigUsed); assertEquals(28, r.satUsed)
        assertEquals(listOf(16, 10, 32, 10), r.perConst["BDS"]!!.toList())
    }

    @Test
    fun landWaterMask() {
        val f = listOf("src/main/assets/earth_ocean.png", "app/src/main/assets/earth_ocean.png").map { File(it) }.first { it.exists() }
        val img = ImageIO.read(f)
        val red = { x: Int, y: Int -> (img.getRGB(x, y) shr 16) and 0xFF }
        assertTrue(OceanMask.isWater(red, img.width, img.height, 0.0, -30.0))       // Atlantas
        assertFalse(OceanMask.isWater(red, img.width, img.height, 54.74, 25.25))    // Vilnius
    }
}
