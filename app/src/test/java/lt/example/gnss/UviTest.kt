package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

class UviTest {
    @Test
    fun modelSubsolarHasSunAtZenith() {
        val ms = 1794394292379L
        val (la, lo) = Uvi.modelSubsolar(ms)
        val (_, alt) = Astro.altAz(Astro.sunEq(ms), la, lo, ms)
        assertEquals(90.0, alt, 0.05)
    }

    @Test
    fun fitFindsCenterOfSyntheticField() {
        val sLat = -17.4; val sLon = 13.0
        fun u(la: Double, lo: Double): Double {
            val a = Math.toRadians(la); val b = Math.toRadians(sLat); val d = Math.toRadians(lo - sLon)
            val c = sin(a) * sin(b) + cos(a) * cos(b) * cos(d)
            return 12.5 * c.coerceAtLeast(0.0).pow(1.2)
        }
        val sb = StringBuilder("[")
        var first = true
        for (la in Uvi.lats) for (lo in Uvi.lons) {
            if (!first) sb.append(','); first = false
            val v = u(la, lo)
            sb.append("{\"latitude\":$la,\"longitude\":$lo,\"hourly\":{\"time\":[\"2026-11-12T10:00\",\"2026-11-12T11:00\"],\"uv_index\":[$v,$v],\"uv_index_clear_sky\":[$v,$v]}}")
        }
        sb.append("]")
        Uvi.parse(sb.toString())
        val ms = java.time.LocalDateTime.parse("2026-11-12T10:30").toEpochSecond(java.time.ZoneOffset.UTC) * 1000
        val f = Uvi.fitCenter(ms)!!
        assertEquals(sLat, f[0], 0.5); assertEquals(sLon, f[1], 0.5)
    }
}
