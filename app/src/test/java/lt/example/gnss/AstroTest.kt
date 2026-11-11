package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile

class AstroTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun galacticCentreToEquatorial() {
        val (ra, dec) = Astro.galToEq(0.0, 0.0)
        val (raP, decP) = Astro.galToEq(0.0, 90.0)
        println("Galaktikos centras: RA %.4f°, Dec %.4f°; šiaurės polius: RA %.4f°, Dec %.4f°".format(Locale.US, ra, dec, raP, decP))
        assertEquals(266.405, ra, 0.01); assertEquals(-28.936, dec, 0.01)
        assertEquals(192.859, raP, 0.01); assertEquals(27.128, decP, 0.01)
    }

    @Test
    fun sgrAMaxAltitudeVilnius() {
        val start = java.time.Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()
        var best = -90.0
        for (k in 0 until 24 * 60) {
            val (_, alt) = Astro.altAzRaDec(266.417, -29.008, 54.74, 25.25, start + k * 60_000L)
            if (alt > best) best = alt
        }
        println("Sgr A* didžiausias aukštis ties 54,74°: %.2f°".format(Locale.US, best))
        assertEquals(6.25, best, 0.1)
    }

    @Test
    fun sunHourAngleMoves15DegPerHour() {
        val t = java.time.Instant.parse("2026-11-11T10:00:00Z").toEpochMilli()
        val h0 = Astro.hourAngleDeg(Astro.raDeg(Astro.sunEq(t)), 25.25, t)
        val h1 = Astro.hourAngleDeg(Astro.raDeg(Astro.sunEq(t + 3_600_000L)), 25.25, t + 3_600_000L)
        val dh = ((h1 - h0 + 540.0) % 360.0) - 180.0
        println("Saulės valandinio kampo pokytis per 60 min.: %.3f°".format(Locale.US, dh))
        assertEquals(15.0, dh, 0.2)
    }

    @Test
    fun zipContainsAllSessionFiles() {
        val names = listOf("ataskaita.txt", "gnss_raw.csv", "gnss_fix.csv", "gnss_nav.csv", "gap_log.txt", "gnss_solver.csv",
            "gnss_solver_sp3_test.csv", "gnss_solver_sp3_test_v6.csv", "gnss_solver_diag.txt", "screenshot.png")
        val files = names.map { n -> tmp.newFile(n).also { it.writeText("turinys $n") } }
        val zipName = SessionZip.name(java.time.Instant.parse("2026-11-11T08:09:10Z").toEpochMilli(), "SM-X926B")
        val out = File(tmp.root, "out/$zipName")
        val written = SessionZip.create(out, files.map { f -> f.name to { f.inputStream() } })
        assertEquals(names, written)
        ZipFile(out).use { z ->
            val inZip = z.entries().toList().map { it.name }
            assertEquals(names.toSet(), inZip.toSet())
            assertEquals("turinys gnss_raw.csv", z.getInputStream(z.getEntry("gnss_raw.csv")).bufferedReader().readText())
        }
        assertTrue(zipName, zipName.matches(Regex("gnss_\\d{8}_\\d{6}_SM-X926B\\.zip")))
    }
}
