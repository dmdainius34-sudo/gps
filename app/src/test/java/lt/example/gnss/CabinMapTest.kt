package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CabinMapTest {
    @Test
    fun learnsReflectionsRelativeToDevice() {
        CabinMap.learn = true; CabinMap.useInFilters = true
        // prietaisas atsuktas į 90°; palydovas az 275°, aukštis 45° -> santykinis 185°: dideli atspindžiai
        repeat(40) {
            CabinMap.learnRtk(90.0, mapOf("G05" to doubleArrayOf(275.0, 45.0, 30.0, 40.0, 0.0, 0.0),
                "G07" to doubleArrayOf(95.0, 65.0, 40.0, 1.5, 0.0, 0.0)))
            CabinMap.learnStatus(90.0, listOf(Sat("GPS", 7, 40f, 65f, 95f, true)))
        }
        assertEquals(CabinMap.REFLECT, CabinMap.cls(185.0, 45.0))
        assertEquals(CabinMap.CLEAN, CabinMap.cls(5.0, 65.0))
        // kabina pasisuko kartu su prietaisu (kryptis 180°): tas pats santykinis langelis – palydovas az 5°
        assertTrue(CabinMap.excluded(5.0, 45.0, 180.0))
        assertFalse(CabinMap.excluded(185.0, 65.0, 180.0))
        // aplinkos žemėlapis (nuo šiaurės): atspindžiai iš az 275° nepriklauso nuo prietaiso krypties
        assertEquals(CabinMap.REFLECT, CabinMap.world.cls(275.0, 45.0))
        assertTrue(CabinMap.excluded(275.0, 45.0, null))
        CabinMap.clear()
        // radaras: δ = 40 m, aukštis 60° -> d = 40 m, atspindys iš priešingos pusės
        repeat(10) { CabinMap.learnRtk(null, mapOf("G05" to doubleArrayOf(275.0, 60.0, 30.0, 40.0, 0.0, 0.0)), 0.3) }
        val r = Radar.points().last()
        assertEquals(95.0, r.az, 1e-9); assertEquals(40.0, r.d, 1e-6)
        CabinMap.clear()
    }
}
