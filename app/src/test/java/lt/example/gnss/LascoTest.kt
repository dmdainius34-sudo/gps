package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Test

class LascoTest {
    @Test fun namesFromDirectory() {
        val html = """<a href="20261112_2112_c2_1024.jpg">x</a> <a href="20261112_2112_c2_512.jpg">y</a>
            <a href="20261112_2036_c2_512.jpg">z</a> <a href="20261112_2036_c2_512.jpg">z</a>"""
        assertEquals(listOf("20261112_2036_c2_512.jpg", "20261112_2112_c2_512.jpg"), Lasco.names(html))
        assertEquals(1_794_517_920_000L, Lasco.nameMs("20261112_2112_c2_512.jpg"))
    }
}
