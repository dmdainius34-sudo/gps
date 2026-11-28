package lt.example.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SatCountTest {
    @Test
    fun uniqueSatellitesFromSignals() {
        val l = ArrayList<SatCount.Sig>()
        for (n in 1..16) { l.add(SatCount.Sig("BDS", n, n <= 10)); l.add(SatCount.Sig("BDS", n, false)) }   // B1I ir B1C
        for (n in 1..12) l.add(SatCount.Sig("GPS", n, n <= 8))
        for (n in 1..10) l.add(SatCount.Sig("GAL", n, n <= 6))
        for (n in 1..8) l.add(SatCount.Sig("GLO", n, n <= 4))
        for (n in 120..122) l.add(SatCount.Sig("SBS", n, false))
        val r = SatCount.count(l)
        assertEquals(65, r.sigVisible)
        assertEquals(49, r.satVisible)
        assertEquals(28, r.sigUsed); assertEquals(28, r.satUsed)
        assertEquals(listOf(16, 10, 32, 10), r.perConst["BDS"]!!.toList())
    }

    @Test
    fun landWaterMask() {
        val f = listOf("src/main/assets/earth_ocean.png", "app/src/main/assets/earth_ocean.png").map { File(it) }.first { it.exists() }
        val (w, h, px) = readPng(f.readBytes())
        val bpp = px.size / (w * h)
        val red = { x: Int, y: Int -> px[(y * w + x) * bpp].toInt() and 0xFF }
        assertTrue(OceanMask.isWater(red, w, h, 0.0, -30.0))       // Atlantas
        assertFalse(OceanMask.isWater(red, w, h, 54.74, 25.25))    // Vilnius
    }

    /** Paprastas PNG (8 bitų, be persipynimo) skaitytuvas: Android unit testuose javax.imageio nėra. */
    private fun readPng(b: ByteArray): Triple<Int, Int, ByteArray> {
        fun u32(i: Int) = ((b[i].toInt() and 0xFF) shl 24) or ((b[i + 1].toInt() and 0xFF) shl 16) or ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
        var i = 8; var w = 0; var h = 0; var bpp = 3
        val idat = java.io.ByteArrayOutputStream()
        while (i < b.size) {
            val len = u32(i); val type = String(b, i + 4, 4, Charsets.US_ASCII)
            when (type) {
                "IHDR" -> { w = u32(i + 8); h = u32(i + 12); bpp = when (b[i + 17].toInt()) { 0 -> 1; 2 -> 3; 4 -> 2; 6 -> 4; else -> 3 } }
                "IDAT" -> idat.write(b, i + 8, len)
            }
            i += 12 + len
            if (type == "IEND") break
        }
        val raw = java.util.zip.InflaterInputStream(idat.toByteArray().inputStream()).readBytes()
        val stride = w * bpp; val out = ByteArray(stride * h)
        for (y in 0 until h) {
            val ft = raw[y * (stride + 1)].toInt(); val o = y * stride; val r = y * (stride + 1) + 1
            for (x in 0 until stride) {
                val a = if (x >= bpp) out[o + x - bpp].toInt() and 0xFF else 0
                val up = if (y > 0) out[o - stride + x].toInt() and 0xFF else 0
                val c = if (x >= bpp && y > 0) out[o - stride + x - bpp].toInt() and 0xFF else 0
                val v = raw[r + x].toInt() and 0xFF
                out[o + x] = (v + when (ft) {
                    1 -> a; 2 -> up; 3 -> (a + up) / 2
                    4 -> { val p = a + up - c; val pa = Math.abs(p - a); val pb = Math.abs(p - up); val pc = Math.abs(p - c)
                        if (pa <= pb && pa <= pc) a else if (pb <= pc) up else c }
                    else -> 0 }).toByte()
            }
        }
        return Triple(w, h, out)
    }
}
