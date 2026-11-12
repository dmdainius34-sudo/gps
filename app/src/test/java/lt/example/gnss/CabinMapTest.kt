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
        assertFalse(CabinMap.excluded(5.0, 45.0, null))
        CabinMap.clear()
    }
}
