package lt.example.gnss

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Vietinis aukščių modelis iš failo (programos ištekliai): FABDEM V1-2 (Bristolio universitetas, Copernicus GLO-30 be pastatų ir miškų,
 * 1″ ≈ 30 m, „plika žemė“), Vilniaus bandomasis plotas. Licencija – nekomercinė (Non-Commercial Government Licence). Aukštis – EGM2008
 * (Lietuvoje nuo LAS07 skiriasi ~0,1–0,3 m). Formatas: „DEM1“, viršutinė platuma, kairė ilguma, žingsniai (°), eilutės, stulpeliai, uint16 cm.
 */
object LocalDem {
    class Grid(val name: String, val lat0: Double, val lon0: Double, val dLat: Double, val dLon: Double, val rows: Int, val cols: Int, val cm: ShortArray)
    @Volatile var fabdem: Grid? = null; private set

    fun init(ctx: Context) {
        if (fabdem != null) return
        try {
            val b = ctx.assets.open("dem_fabdem_vilnius.bin").use { it.readBytes() }
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            if (b.size < 44 || String(b, 0, 4) != "DEM1") return
            bb.position(4)
            val lat0 = bb.double; val lon0 = bb.double; val dLat = bb.double; val dLon = bb.double; val rows = bb.int; val cols = bb.int
            val a = ShortArray(rows * cols); bb.asShortBuffer().get(a)
            fabdem = Grid("FABDEM", lat0, lon0, dLat, dLon, rows, cols, a)
        } catch (_: Exception) {}
    }

    /** Aukštis (m) dvitiesiškai tarp tinklelio mazgų (mazgas – taško centras) arba null, jei už ploto. */
    fun at(g: Grid, lat: Double, lon: Double): Double? {
        val fr = (g.lat0 - lat) / g.dLat; val fc = (lon - g.lon0) / g.dLon
        if (fr < 0 || fc < 0 || fr > g.rows - 1 || fc > g.cols - 1) return null
        val r0 = floor(fr).toInt().coerceAtMost(g.rows - 2); val c0 = floor(fc).toInt().coerceAtMost(g.cols - 2)
        val ar = fr - r0; val ac = fc - c0
        fun v(r: Int, c: Int) = (g.cm[r * g.cols + c].toInt() and 0xFFFF) / 100.0
        return (v(r0, c0) * (1 - ac) + v(r0, c0 + 1) * ac) * (1 - ar) + (v(r0 + 1, c0) * (1 - ac) + v(r0 + 1, c0 + 1) * ac) * ar
    }
}
