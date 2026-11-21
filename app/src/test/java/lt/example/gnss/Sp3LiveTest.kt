package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Sp3LiveTest {
    @Test
    fun angularSeparationNearZenith() {
        assertEquals(2.0, Sp3Live.angSep(0.0, 89.0, 180.0, 89.0), 1e-9)
        assertEquals(0.0, Sp3Live.angSep(123.0, 45.0, 123.0, 45.0), 1e-6)
    }

    @Test
    fun glonassMapping() {
        assertNull(Sp3Live.id("GLO", 100))
        assertNull(Sp3Live.id("GLO", 93))
        assertEquals("R11", Sp3Live.id("GLO", 11))
        assertEquals("G05", Sp3Live.id("GPS", 5))
    }
}
