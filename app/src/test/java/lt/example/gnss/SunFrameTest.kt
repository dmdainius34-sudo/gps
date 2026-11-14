package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Test

/** Palyginimas su astronominėmis efemeridėmis (Meeus 29 skyriaus pavyzdys: 1992-10-13 0h TD: P = 26,27°, B0 = 5,99°, L0 = 238,63°). */
class SunFrameTest {
    @Test
    fun meeusExample() {
        val ms = java.time.LocalDateTime.parse("1992-10-13T00:00").toEpochSecond(java.time.ZoneOffset.UTC) * 1000
        val r = SunFrame.pbl(ms)
        assertEquals(26.27, r[0], 0.6)
        assertEquals(5.99, r[1], 0.3)
        assertEquals(238.63, r[2], 1.5)
    }
}
