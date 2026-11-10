package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/** Be tinklo: AIUB sąrašo analizė, paprastas ir gzip SP3, laiko intervalo patikra. */
class Sp3AiubTest {
    private val listing = """
        <html><head><title>Index of /download/CODE</title></head><body><h1>Index of /download/CODE</h1><pre>
        <a href="COD0OPSFIN_20263050000_01D_05M_ORB.SP3.gz">COD0OPSFIN_20263050000_01D_05M_ORB.SP3.gz</a> 2026-11-03 10:00  2.1M
        <a href="COD0OPSULT_20263120000_02D_05M_ORB.SP3">COD0OPSULT_20263120000_02D_05M_ORB.SP3</a> 2026-11-09 05:00  4.0M
        <a href="COD0OPSULT_20263130000_02D_05M_ORB.SP3.gz">COD0OPSULT_20263130000_02D_05M_ORB.SP3.gz</a> 2026-11-10 05:00  1.3M
        <a href="COD0OPSULT.ERP">COD0OPSULT.ERP</a> 2026-11-10 05:00  1K
        <a href="COD0OPSULT.SP3">COD0OPSULT.SP3</a> 2026-11-10 05:00  4.0M
        </pre></body></html>
    """.trimIndent()

    private val sample: File = listOf("../samples", "samples").map { File(it, Sp3ReaderTest.SAMPLE) }.firstOrNull { it.exists() }
        ?: File("../samples", Sp3ReaderTest.SAMPLE)

    @Test
    fun listingPrefersUndatedUltraRapid() {
        assertEquals("COD0OPSULT.SP3", Sp3Aiub.pickFromListing(listing))
    }

    @Test
    fun listingFallsBackToNewestDated() {
        val noUndated = listing.lines().filterNot { it.contains("COD0OPSULT.SP3") }.joinToString("\n")
        assertEquals("COD0OPSULT_20263130000_02D_05M_ORB.SP3.gz", Sp3Aiub.pickFromListing(noUndated))
        assertNull(Sp3Aiub.pickFromListing("<html><a href=\"COD0OPSFIN.CLK\">x</a></html>"))
    }

    @Test
    fun s3XmlListingAndUrls() {
        val xml = """<?xml version="1.0"?><ListBucketResult><Name>aiub</Name><Prefix>CODE/COD0OPSULT</Prefix>
            <Contents><Key>CODE/COD0OPSULT_20263120000_02D_05M_ORB.SP3.gz</Key><Size>1</Size></Contents>
            <Contents><Key>CODE/COD0OPSULT_20263130000_02D_05M_ORB.SP3.gz</Key><Size>1</Size></Contents>
            <Contents><Key>CODE/COD0OPSULT.ERP</Key></Contents></ListBucketResult>"""
        assertEquals("COD0OPSULT_20263130000_02D_05M_ORB.SP3.gz", Sp3Aiub.pickFromListing(xml))
        assertEquals(listOf(Sp3Aiub.BASE, "https://zhw-b.s3.cloud.switch.ch/aiub/?prefix=CODE/COD0OPSULT&max-keys=1000"),
            Sp3Aiub.listingUrls("https://zhw-b.s3.cloud.switch.ch/aiub/CODE/"))
        assertEquals(listOf(Sp3Aiub.BASE), Sp3Aiub.listingUrls(Sp3Aiub.BASE))
    }

    @Test
    fun plainAndGzipLoadTheSame() {
        val gz = sample.readBytes()
        val plain = GZIPInputStream(ByteArrayInputStream(gz)).readBytes()
        val a = Sp3Reader.read(ByteArrayInputStream(gz))
        val b = Sp3Reader.read(ByteArrayInputStream(plain))      // be vardo – gzip atpažįstamas pagal baitus
        assertEquals(289, a.epochCount)
        assertEquals(289, b.epochCount)
        assertEquals(a.record("G01", 0)!!.toList(), b.record("G01", 0)!!.toList())
        val hr = Sp3Reader.headerRange(ByteArrayInputStream(plain))!!
        assertEquals(a.epochs.first(), hr.first, 1e-6)
        assertEquals(a.epochs.last(), hr.second, 1e-6)
        assertEquals(hr, Sp3Reader.headerRange(ByteArrayInputStream(gz)))
    }

    @Test
    fun recordingAfterFileEndIsOutside() {
        val r = Sp3Reader.read(ByteArrayInputStream(sample.readBytes()))
        val s0 = r.epochs.first(); val s1 = r.epochs.last()
        assertEquals(Sp3Reader.OUTSIDE, Sp3Reader.coverage(s0, s1, s1 + 86400.0, s1 + 90000.0))
        assertEquals(Sp3Reader.COVERS, Sp3Reader.coverage(s0, s1, s0 + 600.0, s0 + 1200.0))
        assertEquals(Sp3Reader.PARTIAL, Sp3Reader.coverage(s0, s1, s1 - 600.0, s1 + 600.0))
        val note = Sp3Solver.staleNote(r, s1 + 86400.0 to s1 + 90000.0)!!
        println(note)
        assertTrue(note, note.startsWith("SP3 failas netinka: apima 2026-11-03 23:59–2026-11-04 23:59 UTC, įrašas 2026-11-05 23:59"))
        assertNull(Sp3Solver.staleNote(r, s0 + 600.0 to s0 + 1200.0))
    }

    @Test
    fun choosesFileCoveringRecording() {
        val old = Sp3Locator.Found("A.SP3", "a") { ByteArrayInputStream(ByteArray(0)) }
        val mid = Sp3Locator.Found("B.SP3", "b") { ByteArrayInputStream(ByteArray(0)) }
        val new = Sp3Locator.Found("C.SP3", "c") { ByteArrayInputStream(ByteArray(0)) }
        val ranges = mapOf("A.SP3" to (0.0 to 100.0), "B.SP3" to (50.0 to 300.0), "C.SP3" to (80.0 to 400.0))
        val (f, _) = Sp3Locator.chooseByRange(listOf(old, mid, new), 90.0 to 200.0) { ranges[it.name] }
        assertEquals("C.SP3", f!!.name)
        val (g, _) = Sp3Locator.chooseByRange(listOf(old, mid, new), 500.0 to 600.0) { ranges[it.name] }
        assertEquals("A.SP3", g!!.name)                         // niekas neapima – pirmas paieškos tvarka
    }
}
