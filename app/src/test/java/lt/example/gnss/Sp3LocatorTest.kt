package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class Sp3LocatorTest {
    @get:Rule val tmp = TemporaryFolder()

    private val day312 = "COD0OPSULT_20263120000_02D_05M_ORB.SP3.gz"
    private val day311 = "COD0OPSULT_20263110000_02D_05M_ORB.SP3.gz"
    private val noPick: () -> Pair<Sp3Locator.Check, Sp3Locator.Found?> =
        { Sp3Locator.Check("SAF", Sp3Locator.ABSENT) to null }

    private fun dirWith(vararg names: String): File =
        tmp.newFolder().also { d -> names.forEach { File(d, it).writeText("x") } }

    @Test
    fun newestDayWins() {
        val dir = dirWith(day311, day312, "notes.txt")
        assertEquals(day312, Sp3Locator.newestIn(dir)?.name)
    }

    @Test
    fun lowerCaseAndOtherYearAccepted() {
        val dir = dirWith("a_20253650000_01D.sp3.gz", "b_20260010000_01D.sp3.gz", "c.SP3")
        assertEquals("b_20260010000_01D.sp3.gz", Sp3Locator.newestIn(dir)?.name)
    }

    @Test
    fun firstLocationWithFileIsUsedAndAllAreLogged() {
        val first = dirWith(day311)
        val second = dirWith(day312)
        val s = Sp3Locator.search(listOf(first, second), noPick)
        assertEquals(File(first, day311).absolutePath, s.used?.location)
        assertEquals(listOf(Sp3Locator.FOUND, Sp3Locator.FOUND, Sp3Locator.ABSENT), s.checks.map { it.status })
    }

    @Test
    fun fallsBackToPickedFile() {
        val picked = Sp3Locator.Found(day312, "content://x/1") { ByteArrayInputStream(ByteArray(0)) }
        val s = Sp3Locator.search(listOf(dirWith())) { Sp3Locator.Check("content://x/tree", Sp3Locator.FOUND, day312) to picked }
        assertEquals("content://x/1", s.used?.location)
        assertTrue(s.lines().contains("content://x/tree – found: $day312"))
    }

    @Test
    fun newestAmongFolderRows() {
        val rows = listOf("1" to day311, "2" to day312, "3" to "notes.txt", "4" to "kitas.SP3")
        assertEquals("2", Sp3Locator.newest(rows) { it.second }?.first)
        assertNull(Sp3Locator.newest(listOf("3" to "notes.txt")) { it.second })
    }

    @Test
    fun statusesWhenNothingFound() {
        val missing = File(tmp.root, "nera")
        val notDir = tmp.newFile("failas")
        val s = Sp3Locator.search(listOf(missing, notDir, dirWith()), noPick)
        assertNull(s.used)
        assertEquals(
            listOf(Sp3Locator.ABSENT, Sp3Locator.BLOCKED, Sp3Locator.ABSENT, Sp3Locator.ABSENT),
            s.checks.map { it.status }
        )
    }

    @Test
    fun diagListsLocationsWhenNoFile() {
        val s = Sp3Locator.search(listOf(File("/nera/Download")), noPick)
        val text = Sp3Reader.diagBlock("nėra", null, Sp3Locator.NOT_FOUND, s.lines())
        assertTrue(text.contains("SP3 failo nėra"))
        assertTrue(text.contains("/nera/Download – not found"))
        assertTrue(text.contains("SAF – not found"))
    }
}
