package lt.example.gnss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Dangaus kliūčių žemėlapis: langeliai 10° (azimutas) × 10° (aukštis); azimutas – prietaiso arba pasaulio atžvilgiu. */
class SkyMap(val file: String) {
    private class Cell { var n = 0; var cn0 = 0.0; var nRes = 0; var nlos = 0; var absRes = 0.0; var slips = 0; var nPh = 0 }
    private val cells = Array(CabinMap.AZ * CabinMap.EL) { Cell() }
    var samples = 0L
        private set

    private fun idx(az: Double, el: Double): Int? {
        if (el < 0 || el > 90) return null
        val a = (((az % 360) + 360) % 360 / 10).toInt().coerceIn(0, CabinMap.AZ - 1)
        val e = (el / 10).toInt().coerceIn(0, CabinMap.EL - 1)
        return e * CabinMap.AZ + a
    }

    @Synchronized fun load(d: File) {
        val f = File(d, file); if (!f.exists()) return
        try {
            val lines = f.readLines()
            val v2 = lines.firstOrNull()?.contains("v2") == true          // v1 liekanos kauptos ir nesuartėjus RTK – atmetamos
            lines.drop(1).forEach { l ->
                val v = l.split(','); if (v.size < 8) return@forEach
                val i = v[0].toInt(); if (i !in cells.indices) return@forEach
                cells[i].n = v[1].toInt(); cells[i].cn0 = v[2].toDouble(); cells[i].nRes = v[3].toInt(); cells[i].nlos = v[4].toInt()
                cells[i].absRes = v[5].toDouble(); cells[i].slips = v[6].toInt(); cells[i].nPh = v[7].toInt()
                if (!v2) { cells[i].nRes = 0; cells[i].nlos = 0; cells[i].absRes = 0.0 }
                samples += cells[i].n
            }
        } catch (_: Exception) {}
    }

    @Synchronized fun save(d: File) {
        try {
            File(d, file).bufferedWriter().use { w ->
                w.write("langelis,n,cn0_suma,n_liekanu,n_atspindziu,abs_liekanu_suma,fazes_truikiai,n_fazes,v2\n")
                for ((i, c) in cells.withIndex()) if (c.n > 0 || c.nRes > 0)
                    w.write(String.format(Locale.US, "%d,%d,%.1f,%d,%d,%.1f,%d,%d\n", i, c.n, c.cn0, c.nRes, c.nlos, c.absRes, c.slips, c.nPh))
            }
        } catch (_: Exception) {}
    }

    @Synchronized fun clear() { cells.forEach { it.n = 0; it.cn0 = 0.0; it.nRes = 0; it.nlos = 0; it.absRes = 0.0; it.slips = 0; it.nPh = 0 }; samples = 0 }

    @Synchronized fun addCn0(az: Double, el: Double, cn0: Double) { val i = idx(az, el) ?: return; cells[i].n++; cells[i].cn0 += cn0; samples++ }

    @Synchronized fun addRtk(az: Double, el: Double, res: Double, slip: Boolean) {
        val i = idx(az, el) ?: return
        if (!res.isNaN()) { cells[i].nRes++; cells[i].absRes += abs(res); if (abs(res) > 10.0) cells[i].nlos++ }
        cells[i].nPh++; if (slip) cells[i].slips++
    }

    @Synchronized fun cls(az: Double, el: Double): Int {
        val c = cells[idx(az, el) ?: return CabinMap.UNKNOWN]
        if (c.nRes >= 15 && c.nlos.toDouble() / c.nRes > 0.3) return CabinMap.REFLECT
        if (c.n >= 30 && c.cn0 / c.n < 24.0) return CabinMap.WEAK
        if (c.n >= 30 || c.nRes >= 15) return CabinMap.CLEAN
        return CabinMap.UNKNOWN
    }

    fun counts(): IntArray {
        val n = IntArray(4)
        for (e in 0 until CabinMap.EL) for (a in 0 until CabinMap.AZ) n[cls(a * 10.0 + 5, e * 10.0 + 5)]++
        return n
    }

    fun sectors(): String = (0 until 12).joinToString(" ") { s ->
        val cs = (0 until CabinMap.EL).flatMap { e -> (s * 3 until s * 3 + 3).map { a -> cls(a * 10.0 + 5, e * 10.0 + 5) } }.filter { it != CabinMap.UNKNOWN }
        val lab = when { cs.isEmpty() -> "?"; cs.count { it == CabinMap.REFLECT } * 3 >= cs.size -> "A"; cs.count { it == CabinMap.WEAK } * 2 >= cs.size -> "U"; else -> "Š" }
        "%d°:%s".format(Locale.US, s * 30, lab)
    }
}

/**
 * Du dangaus kliūčių žemėlapiai: kabinos (azimutas prietaiso atžvilgiu – kas sukasi kartu su kabina) ir aplinkos
 * (azimutas nuo šiaurės – pastatai, kiti kranai). Kartu – „radaras“: atstumai iki atspindinčių paviršių iš papildomo signalo kelio.
 */
object CabinMap {
    const val AZ = 36; const val EL = 9
    const val UNKNOWN = 0; const val CLEAN = 1; const val REFLECT = 2; const val WEAK = 3
    val cabin = SkyMap("cabin_map.csv")
    val world = SkyMap("world_map.csv")
    @Volatile var learn = true
    @Volatile var useInFilters = true
    private var dir: File? = null
    private var savedAt = 0L

    val samples get() = cabin.samples + world.samples

    @Synchronized fun load(ctx: Context) {
        val d = ctx.applicationContext.filesDir; if (dir == d) return
        dir = d
        val p = ctx.getSharedPreferences("cabin", Context.MODE_PRIVATE)
        learn = p.getBoolean("learn", true); useInFilters = p.getBoolean("use", true)
        cabin.load(d); world.load(d); Radar.load(d)
    }

    @Synchronized fun save(force: Boolean = false) {
        val d = dir ?: return
        val now = System.currentTimeMillis(); if (!force && now - savedAt < 60_000) return
        savedAt = now
        cabin.save(d); world.save(d); Radar.save(d)
    }

    @Synchronized fun clear() { cabin.clear(); world.clear(); Radar.clear(); save(true) }

    /** Signalo stiprumas (kas sekundę). Aplinkos žemėlapiui kryptis nereikalinga; kabinos – reikia prietaiso krypties. */
    fun learnStatus(heading: Double?, sats: List<Sat>) {
        if (!learn) return
        for (s in sats) {
            if (s.elev < 5f || s.cn0 <= 0f) continue
            world.addCn0(s.az.toDouble(), s.elev.toDouble(), s.cn0.toDouble())
            if (heading != null) cabin.addCn0(s.az - heading, s.elev.toDouble(), s.cn0.toDouble())
        }
        save()
    }

    /** RTK epochos palydovai: [az, el, C/N0, kodo liekana, šuolis, kaukė]; sigH – RTK neapibrėžtumas (radarui). */
    fun learnRtk(heading: Double?, obs: Map<String, DoubleArray>, sigH: Double = Double.NaN) {
        if (!learn) return
        // liekanos prasmingos tik suartėjus RTK (kitaip jos – filtro, o ne aplinkos paklaida)
        if (sigH.isNaN() || sigH >= 1.0) return
        for (v in obs.values) {
            world.addRtk(v[0], v[1], v[3], v[4] > 0.5)
            if (heading != null) cabin.addRtk(v[0] - heading, v[1], v[3], v[4] > 0.5)
            Radar.add(v[0], v[1], v[3])
        }
    }

    /** Kabinos žemėlapio klasė (santykinis azimutas). */
    fun cls(relAz: Double, el: Double): Int = cabin.cls(relAz, el)

    /** Kaukė filtrams: atspindžiai kabinos (prietaiso kryptimi) arba aplinkos (nuo šiaurės) žemėlapyje. */
    fun excluded(az: Double, el: Double, heading: Double?): Boolean {
        if (!useInFilters) return false
        if (world.cls(az, el) == REFLECT) return true
        return heading != null && cabin.cls(az - heading, el) == REFLECT
    }

    fun text(): String {
        val c = cabin.counts(); val w = world.counts()
        return "Kabinos žemėlapis (0° – prietaiso kryptis): mėginių ${cabin.samples}; švarių ${c[CLEAN]}, atspindžių ${c[REFLECT]}, užstotų ${c[WEAK]}, nežinomų ${c[UNKNOWN]}\n" +
            "  Sektoriai (Š – švaru, A – atspindžiai, U – užstota, ? – nežinoma): ${cabin.sectors()}\n" +
            "Aplinkos žemėlapis (0° – šiaurė): mėginių ${world.samples}; švarių ${w[CLEAN]}, atspindžių ${w[REFLECT]}, užstotų ${w[WEAK]}, nežinomų ${w[UNKNOWN]}\n" +
            "  Sektoriai: ${world.sectors()}\n" +
            "Mokymasis ${if (learn) "įjungtas" else "išjungtas"}, filtruose ${if (useInFilters) "naudojama" else "nenaudojama"}\n" + Radar.text()
    }
}

/**
 * „Radaras“: atspindėto signalo papildomas kelias δ (RTK kodo liekana, m). Vertikalios sienos, statmenos palydovo krypčiai,
 * modelis: atspindys ateina iš priešingos pusės (azimutas + 180°), atstumas d ≈ δ / (2·cos(aukštis)).
 * Tai įvertis (sienos kryptis nežinoma – tikrasis atstumas gali būti didesnis).
 */
object Radar {
    const val FILE = "radar.csv"
    class P(val az: Double, val el: Double, val d: Double)
    private val pts = ArrayDeque<P>()

    @Synchronized fun add(satAz: Double, el: Double, res: Double) {
        if (res.isNaN() || res < 8.0 || res > 300.0 || el < 5 || el > 80) return
        val d = res / (2 * cos(Math.toRadians(el)))
        if (d > 200) return
        pts.addLast(P((satAz + 180) % 360, el, d)); while (pts.size > 20_000) pts.removeFirst()
    }

    @Synchronized fun points(): List<P> = pts.toList()
    @Synchronized fun clear() = pts.clear()

    @Synchronized fun load(d: File) {
        val f = File(d, FILE); if (!f.exists()) return
        try { f.readLines().drop(1).forEach { l -> val v = l.split(','); if (v.size >= 3) pts.addLast(P(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())) } } catch (_: Exception) {}
    }

    @Synchronized fun save(d: File) {
        try { File(d, FILE).bufferedWriter().use { w -> w.write("azimutas_deg,aukstis_deg,atstumas_m\n"); pts.forEach { w.write(String.format(Locale.US, "%.1f,%.1f,%.1f\n", it.az, it.el, it.d)) } } } catch (_: Exception) {}
    }

    /** Atstumo mediana kiekviename 30° sektoriuje (nuo šiaurės). */
    @Synchronized fun text(): String {
        if (pts.isEmpty()) return "Radaras: atspindžių dar nėra (reikia gyvo RTK, σh < 1 m)"
        val s = (0 until 12).mapNotNull { k ->
            val d = pts.filter { (it.az / 30).toInt() == k }.map { it.d }.sorted()
            if (d.size < 5) null else "%d°: %.0f m (%d)".format(Locale.US, k * 30, d[d.size / 2], d.size)
        }
        return "Radaras (atspindinčių paviršių atstumai, 0° – šiaurė; mediana ir taškų skaičius): " + (if (s.isEmpty()) "per mažai taškų" else s.joinToString(", "))
    }
}

/**
 * Žiedinis vaizdas, paspaudus keičiasi: kabina (viršus – prietaisas), aplinka (viršus – šiaurė), radaras (atstumai iki 60 m).
 * Taškai – dabartiniai palydovai.
 */
class CabinMapView(ctx: Context) : View(ctx) {
    var heading: Double? = null
    var sats: List<Sat> = emptyList()
    var mode = 0
        private set
    var onMode: ((Int) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); setMeasuredDimension(s, s) }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) { mode = (mode + 1) % 3; onMode?.invoke(mode); invalidate() }
        return true
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) * 0.88f
        p.style = Paint.Style.FILL; p.color = 0xFF0B1020.toInt(); c.drawCircle(cx, cy, r, p)
        val h = heading
        // kiek pasukti: kabinoje viršus – prietaisas, aplinkoje ir radare – šiaurė
        if (mode == 2) drawRadar(c, cx, cy, r) else {
            val map = if (mode == 0) CabinMap.cabin else CabinMap.world
            for (e in 0 until CabinMap.EL) for (a in 0 until CabinMap.AZ) {
                val col = when (map.cls(a * 10.0 + 5, e * 10.0 + 5)) {
                    CabinMap.CLEAN -> 0xCC22C55E.toInt(); CabinMap.REFLECT -> 0xDDF59E0B.toInt(); CabinMap.WEAK -> 0xCC6B7280.toInt(); else -> 0
                }
                if (col == 0) continue
                p.color = col
                val r1 = r * (90 - e * 10) / 90f; val r0 = r * (90 - (e + 1) * 10) / 90f
                val path = android.graphics.Path()
                val a0 = a * 10f - 90f
                path.arcTo(RectF(cx - r1, cy - r1, cx + r1, cy + r1), a0, 10f)
                path.arcTo(RectF(cx - r0, cy - r0, cx + r0, cy + r0), a0 + 10f, -10f)
                path.close(); c.drawPath(path, p)
            }
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = 0x66FFFFFF
        for (k in 1..3) c.drawCircle(cx, cy, r * k / 3f, p)
        c.drawLine(cx, cy - r, cx, cy + r, p); c.drawLine(cx - r, cy, cx + r, cy, p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = r * 0.11f; p.textAlign = Paint.Align.CENTER
        c.drawText(when (mode) { 0 -> "▲ prietaisas · KABINA"; 1 -> "Š · APLINKA"; else -> "Š · RADARAS (20/40/60 m)" }, cx, cy - r - 6, p)
        // prietaiso kryptis aplinkos ir radaro vaizde
        if (mode != 0 && h != null) {
            p.color = 0xFF38BDF8.toInt(); p.strokeWidth = 4f; p.style = Paint.Style.STROKE
            val a = Math.toRadians(h); c.drawLine(cx, cy, cx + (r * 0.95f * sin(a)).toFloat(), cy - (r * 0.95f * cos(a)).toFloat(), p)
            p.style = Paint.Style.FILL
        }
        if (mode == 2) return
        val rot = if (mode == 0) (h ?: return) else 0.0
        for (s in sats) {
            if (s.elev < 0f) continue
            val rel = Math.toRadians(s.az - rot); val rr = r * (90 - s.elev) / 90f
            p.color = if (s.used) Color.WHITE else 0x99FFFFFF.toInt()
            c.drawCircle(cx + (rr * sin(rel)).toFloat(), cy - (rr * cos(rel)).toFloat(), r * 0.035f, p)
        }
    }

    private fun drawRadar(c: Canvas, cx: Float, cy: Float, r: Float) {
        val pts = Radar.points()
        for (q in pts.takeLast(3000)) {
            val rr = (q.d / 60.0).coerceAtMost(1.0) * r
            val a = Math.toRadians(q.az)
            p.color = when { q.el < 30 -> 0x99F97316.toInt(); q.el < 60 -> 0x99FACC15.toInt(); else -> 0x99A3E635.toInt() }
            c.drawCircle(cx + (rr * sin(a)).toFloat(), cy - (rr * cos(a)).toFloat(), r * 0.02f, p)
        }
        p.color = 0x88FFFFFF.toInt(); p.textSize = r * 0.08f; p.textAlign = Paint.Align.LEFT
        for (k in 1..3) c.drawText("${k * 20} m", cx + 4, cy - r * k / 3f + r * 0.09f, p)
    }
}
