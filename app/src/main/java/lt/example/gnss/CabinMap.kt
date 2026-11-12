package lt.example.gnss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kabinos dangaus žemėlapis prietaiso atžvilgiu: santykinis azimutas (palydovo azimutas − prietaiso kryptis) × aukštis,
 * langeliai 10° × 10°. Kaupiama: signalo stiprumas (GnssStatus), RTK kodo liekanos (atspindžiai, NLOS) ir fazės trūkiai.
 * Jei prietaisas visada padedamas toje pačioje kabinos vietoje, žemėlapis sukasi kartu su kabina.
 */
object CabinMap {
    const val AZ = 36; const val EL = 9
    const val FILE = "cabin_map.csv"
    const val UNKNOWN = 0; const val CLEAN = 1; const val REFLECT = 2; const val WEAK = 3

    private class Cell { var n = 0; var cn0 = 0.0; var nRes = 0; var nlos = 0; var absRes = 0.0; var slips = 0; var nPh = 0 }
    private val cells = Array(AZ * EL) { Cell() }
    @Volatile var learn = true
    @Volatile var useInFilters = true
    @Volatile var samples = 0L
        private set
    private var dir: File? = null
    private var savedAt = 0L

    private fun idx(relAz: Double, el: Double): Int? {
        if (el < 0 || el > 90) return null
        val a = (((relAz % 360) + 360) % 360 / 10).toInt().coerceIn(0, AZ - 1)
        val e = (el / 10).toInt().coerceIn(0, EL - 1)
        return e * AZ + a
    }

    @Synchronized fun load(ctx: Context) {
        val d = ctx.applicationContext.filesDir; if (dir == d) return
        dir = d
        val p = ctx.getSharedPreferences("cabin", Context.MODE_PRIVATE)
        learn = p.getBoolean("learn", true); useInFilters = p.getBoolean("use", true)
        val f = File(d, FILE); if (!f.exists()) return
        try {
            f.readLines().drop(1).forEach { l ->
                val v = l.split(','); if (v.size < 8) return@forEach
                val i = v[0].toInt(); if (i !in cells.indices) return@forEach
                cells[i].n = v[1].toInt(); cells[i].cn0 = v[2].toDouble(); cells[i].nRes = v[3].toInt(); cells[i].nlos = v[4].toInt()
                cells[i].absRes = v[5].toDouble(); cells[i].slips = v[6].toInt(); cells[i].nPh = v[7].toInt()
                samples += cells[i].n
            }
        } catch (_: Exception) {}
    }

    @Synchronized fun save(force: Boolean = false) {
        val d = dir ?: return
        val now = System.currentTimeMillis(); if (!force && now - savedAt < 60_000) return
        savedAt = now
        try {
            File(d, FILE).bufferedWriter().use { w ->
                w.write("langelis,n,cn0_suma,n_liekanu,n_atspindziu,abs_liekanu_suma,fazes_truikiai,n_fazes\n")
                for ((i, c) in cells.withIndex()) if (c.n > 0 || c.nRes > 0)
                    w.write(String.format(Locale.US, "%d,%d,%.1f,%d,%d,%.1f,%d,%d\n", i, c.n, c.cn0, c.nRes, c.nlos, c.absRes, c.slips, c.nPh))
            }
        } catch (_: Exception) {}
    }

    @Synchronized fun clear() { cells.forEach { it.n = 0; it.cn0 = 0.0; it.nRes = 0; it.nlos = 0; it.absRes = 0.0; it.slips = 0; it.nPh = 0 }; samples = 0; save(true) }

    /** Signalo stiprumas iš GnssStatus (kas sekundę). heading – prietaiso kryptis, °. */
    @Synchronized fun learnStatus(heading: Double, sats: List<Sat>) {
        if (!learn) return
        for (s in sats) {
            if (s.elev < 5f || s.cn0 <= 0f) continue
            val i = idx(s.az - heading, s.elev.toDouble()) ?: continue
            cells[i].n++; cells[i].cn0 += s.cn0; samples++
        }
        save()
    }

    /** RTK epochos palydovai: [az, el, C/N0, kodo liekana, šuolis, kaukė]. */
    @Synchronized fun learnRtk(heading: Double, obs: Map<String, DoubleArray>) {
        if (!learn) return
        for (v in obs.values) {
            val i = idx(v[0] - heading, v[1]) ?: continue
            if (!v[3].isNaN()) { cells[i].nRes++; cells[i].absRes += abs(v[3]); if (abs(v[3]) > 10.0) cells[i].nlos++ }
            cells[i].nPh++; if (v[4] > 0.5) cells[i].slips++
        }
    }

    @Synchronized fun cls(relAz: Double, el: Double): Int {
        val c = cells[idx(relAz, el) ?: return UNKNOWN]
        if (c.nRes >= 15 && c.nlos.toDouble() / c.nRes > 0.3) return REFLECT
        if (c.n >= 30 && c.cn0 / c.n < 24.0) return WEAK
        if (c.n >= 30 || c.nRes >= 15) return CLEAN
        return UNKNOWN
    }

    /** Kaukė filtrams: atspindžių langeliai (žemėlapis turi būti pakankamai išmoktas). */
    fun excluded(az: Double, el: Double, heading: Double?): Boolean =
        useInFilters && heading != null && cls(az - heading, el) == REFLECT

    @Synchronized fun text(): String {
        val n = IntArray(4)
        for (e in 0 until EL) for (a in 0 until AZ) n[cls(a * 10.0 + 5, e * 10.0 + 5)]++
        val sectors = (0 until 12).joinToString(" ") { s ->
            val cs = (0 until EL).flatMap { e -> (s * 3 until s * 3 + 3).map { a -> cls(a * 10.0 + 5, e * 10.0 + 5) } }.filter { it != UNKNOWN }
            val lab = when { cs.isEmpty() -> "?"; cs.count { it == REFLECT } * 3 >= cs.size -> "A"; cs.count { it == WEAK } * 2 >= cs.size -> "U"; else -> "Š" }
            "%d°:%s".format(Locale.US, s * 30, lab)
        }
        return "Kabinos žemėlapis (prietaiso atžvilgiu, 0° – prietaiso kryptis): mėginių $samples; langelių švarių ${n[CLEAN]}, atspindžių ${n[REFLECT]}, " +
            "užstotų/silpnų ${n[WEAK]}, nežinomų ${n[UNKNOWN]}; mokymasis ${if (learn) "įjungtas" else "išjungtas"}, filtruose ${if (useInFilters) "naudojamas" else "nenaudojamas"}\n" +
            "  Sektoriai (Š – švaru, A – atspindžiai, U – užstota, ? – nežinoma): $sectors"
    }
}

/** Žiedinis kabinos žemėlapis: centras – zenitas, kraštas – horizontas, viršus – prietaiso kryptis; taškai – dabartiniai palydovai. */
class CabinMapView(ctx: Context) : View(ctx) {
    var heading: Double? = null
    var sats: List<Sat> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); setMeasuredDimension(s, s) }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) * 0.92f
        p.style = Paint.Style.FILL; p.color = 0xFF0B1020.toInt(); c.drawCircle(cx, cy, r, p)
        for (e in 0 until CabinMap.EL) for (a in 0 until CabinMap.AZ) {
            val col = when (CabinMap.cls(a * 10.0 + 5, e * 10.0 + 5)) {
                CabinMap.CLEAN -> 0xCC22C55E.toInt(); CabinMap.REFLECT -> 0xDDF59E0B.toInt(); CabinMap.WEAK -> 0xCC6B7280.toInt(); else -> 0
            }
            if (col == 0) continue
            p.color = col
            val r1 = r * (90 - e * 10) / 90f; val r0 = r * (90 - (e + 1) * 10) / 90f
            val path = android.graphics.Path()
            val a0 = a * 10f - 90f; val sweep = 10f
            path.arcTo(RectF(cx - r1, cy - r1, cx + r1, cy + r1), a0, sweep)
            path.arcTo(RectF(cx - r0, cy - r0, cx + r0, cy + r0), a0 + sweep, -sweep)
            path.close(); c.drawPath(path, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0x66FFFFFF
        for (k in 1..3) c.drawCircle(cx, cy, r * k / 3f, p)
        c.drawLine(cx, cy - r, cx, cy + r, p); c.drawLine(cx - r, cy, cx + r, cy, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = r * 0.12f; p.textAlign = Paint.Align.CENTER
        c.drawText("▲ prietaisas", cx, cy - r - 4, p)
        val h = heading ?: return
        for (s in sats) {
            if (s.elev < 0f) continue
            val rel = Math.toRadians(s.az - h); val rr = r * (90 - s.elev) / 90f
            val x = cx + (rr * sin(rel)).toFloat(); val y = cy - (rr * cos(rel)).toFloat()
            p.color = if (s.used) Color.WHITE else 0x99FFFFFF.toInt()
            c.drawCircle(x, y, r * 0.035f, p)
        }
    }
}
