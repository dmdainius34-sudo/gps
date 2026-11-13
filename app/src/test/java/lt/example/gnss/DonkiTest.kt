package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DonkiTest {
    private val json = """[
      {"time21_5":"2026-11-10T12:00Z","latitude":-5.0,"longitude":10.0,"halfAngle":35.0,"speed":800.0,"type":"C","isMostAccurate":true,"associatedCMEID":"A"},
      {"time21_5":"2026-11-11T06:30Z","latitude":20.0,"longitude":-90.0,"halfAngle":20.0,"speed":400.0,"type":"S","isMostAccurate":true,"associatedCMEID":"B"},
      {"time21_5":null,"latitude":null,"longitude":null,"halfAngle":null,"speed":null,"associatedCMEID":"C"}
    ]"""

    @Test fun parseAndEarthDirection() {
        val l = Donki.parse(json)
        assertEquals(2, l.size)
        val e = doubleArrayOf(1.0, 0.0, 0.0); val z = doubleArrayOf(0.0, 0.0, 1.0)
        assertTrue(Donki.hitsEarth(l[0], e, z))
        assertFalse(Donki.hitsEarth(l[1], e, z))
        // 800 km/s: (215 − 21,5) R × 696 000 km / 800 km/s ≈ 46,8 h
        assertEquals(46.8, (Donki.arrivalMs(l[0]) - l[0].t215Ms) / 3_600_000.0, 0.2)
        // vakarų ilguma (+) – į +Y (vaizdo dešinė iš Žemės)
        val n = Donki.frame(Donki.Cme(0, 0.0, 90.0, 10.0, 500.0, "", ""), e, z).first
        assertEquals(1.0, n[1], 1e-9)
    }
}

class PolarisTest {
    @org.junit.Test fun precession2025() {
        // 2025-06-01: Polaris Dec ≈ +89°22′ (89,37°), RA ≈ 3 h (≈ 45°)
        val (ra, dec) = Astro.polaris(1_748_736_000_000L)
        org.junit.Assert.assertEquals(89.37, dec, 0.03)
        org.junit.Assert.assertTrue(ra in 35.0..55.0)
    }
}
