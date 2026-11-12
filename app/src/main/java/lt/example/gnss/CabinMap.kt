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
        // atspindžiai: dauguma liekanų > 10 m ir vidutinė > 8 m (kabinoje vien kodo triukšmas siekia ~5–10 m)
        if (c.nRes >= 20 && c.nlos.toDouble() / c.nRes > 0.5 && c.absRes / c.nRes > 8.0) return CabinMap.REFLECT
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

    // 3D vaizdo kamera: posūkis (yaw) ir pakilimas (pitch), keičiami vilkimu
    private var yaw = 0.0; private var pitch = 35.0
    private var dx0 = 0f; private var dy0 = 0f; private var lx = 0f; private var ly = 0f; private var dragged = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dx0 = e.x; dy0 = e.y; lx = e.x; ly = e.y; dragged = false; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                if (kotlin.math.abs(e.x - dx0) + kotlin.math.abs(e.y - dy0) > 12f) dragged = true
                if (dragged && mode == 3) {
                    yaw -= (e.x - lx) * 0.4; pitch = (pitch + (e.y - ly) * 0.3).coerceIn(-10.0, 89.0)
                    lx = e.x; ly = e.y; invalidate()
                }
            }
            MotionEvent.ACTION_UP -> { if (!dragged) { mode = (mode + 1) % 4; onMode?.invoke(mode); invalidate() } }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        if (mode == 3) { draw3d(c); return }
        val cx = width / 2f; val cy = height / 2f; val r = minOf(cx, cy) * 0.88f
        p.style = Paint.Style.FILL; p.color = 0xFF0B1020.toInt(); c.drawCircle(cx, cy, r, p)
        val h = heading
        // kiek pasukti: kabinoje viršus – prietaisas, aplinkoje ir radare – šiaurė
        if (mode == 2) drawRadar(c, cx, cy, r) else {
            val map = if (mode == 0) CabinMap.cabin else CabinMap.world
            for (e in 0 until CabinMap.EL) for (a in 0 until CabinMap.AZ) {
                val col = when (map.cls(a * 10.0 + 5, e * 10.0 + 5)) {
                    // švaru – neoninė geltona, kliūtis (užstota) – mėlyna, atspindžiai – raudona
                    CabinMap.CLEAN -> 0xDDE6FF00.toInt(); CabinMap.REFLECT -> 0xDDEF4444.toInt(); CabinMap.WEAK -> 0xDD2563EB.toInt(); else -> 0
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

    /**
     * 3D: stebėtojas centre (planšetės sistema: +y – planšetės kryptis, +z – aukštyn). Kiekvienam kliūties langeliui –
     * kubelis ta kryptimi (atstumas – radaro mediana tame sektoriuje arba 10 m): mėlyna – kliūtis, raudona – atspindžiai;
     * švarios kryptys – maži geltoni taškai. Permatoma kabinos dėžė ir žemės tinklelis; vilkite – sukasi vaizdas.
     */
    private fun draw3d(c: Canvas) {
        c.drawColor(0xFF0B1020.toInt())
        val w = width.toFloat(); val hgt = height.toFloat()
        val h = heading
        val cy = Math.toRadians(yaw); val cp = Math.toRadians(pitch)
        val camD = 34.0; val f = minOf(w, hgt) * 1.1
        // pasaulio (planšetės) taškas -> ekranas; grąžina (x, y, gylis)
        fun proj(x: Double, y: Double, z: Double): DoubleArray? {
            val x1 = x * cos(cy) - y * sin(cy); val y1 = x * sin(cy) + y * cos(cy)
            val y2 = y1 * cos(cp) + z * sin(cp); val z2 = -y1 * sin(cp) + z * cos(cp)
            val depth = camD + y2
            if (depth < 1.0) return null
            return doubleArrayOf(w / 2 + f * x1 / depth, hgt * 0.55 - f * z2 / depth, depth)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x334ADE80
        for (k in -4..4) {                                       // žemės tinklelis 40×40 m
            val a = proj(k * 5.0, -20.0, 0.0); val b = proj(k * 5.0, 20.0, 0.0)
            if (a != null && b != null) c.drawLine(a[0].toFloat(), a[1].toFloat(), b[0].toFloat(), b[1].toFloat(), p)
            val a2 = proj(-20.0, k * 5.0, 0.0); val b2 = proj(20.0, k * 5.0, 0.0)
            if (a2 != null && b2 != null) c.drawLine(a2[0].toFloat(), a2[1].toFloat(), b2[0].toFloat(), b2[1].toFloat(), p)
        }
        // kubeliai
        class Face(val pts: List<DoubleArray>, val depth: Double, val col: Int)
        val faces = ArrayList<Face>()
        val dots = ArrayList<DoubleArray>()
        val radar = Radar.points()
        fun dist(relAz: Double): Double {
            if (h == null) return 10.0
            val abs = (relAz + h + 360) % 360
            val d = radar.filter { kotlin.math.abs(((it.az - abs + 540) % 360) - 180) < 15 }.map { it.d }.sorted()
            return if (d.size >= 5) d[d.size / 2].coerceIn(4.0, 30.0) else 10.0
        }
        for (ei in 0 until CabinMap.EL) for (ai in 0 until CabinMap.AZ) {
            val rel = ai * 10.0 + 5; val el = ei * 10.0 + 5
            val wc = if (h != null) CabinMap.world.cls(rel + h, el) else CabinMap.UNKNOWN
            val cc = CabinMap.cabin.cls(rel, el)
            val cls = when { wc == CabinMap.REFLECT || cc == CabinMap.REFLECT -> 2; wc == CabinMap.WEAK || cc == CabinMap.WEAK -> 1
                wc == CabinMap.CLEAN || cc == CabinMap.CLEAN -> 0; else -> -1 }
            if (cls < 0) continue
            val d = dist(rel)
            val a = Math.toRadians(rel); val e = Math.toRadians(el)
            val x = d * kotlin.math.cos(e) * sin(a); val y = d * kotlin.math.cos(e) * cos(a); val z = 1.5 + d * sin(e)
            if (cls == 0) { proj(x, y, z)?.let { dots.add(it) }; continue }
            val sz = (d * 0.17).coerceIn(0.8, 4.0) / 2
            val base = if (cls == 2) intArrayOf(239, 68, 68) else intArrayOf(37, 99, 235)
            val v = Array(8) { k -> proj(x + if (k and 1 != 0) sz else -sz, y + if (k and 2 != 0) sz else -sz, z + if (k and 4 != 0) sz else -sz) }
            if (v.any { it == null }) continue
            val quads = arrayOf(intArrayOf(0, 1, 3, 2) to 0.55, intArrayOf(4, 5, 7, 6) to 1.0, intArrayOf(0, 1, 5, 4) to 0.75,
                intArrayOf(2, 3, 7, 6) to 0.75, intArrayOf(0, 2, 6, 4) to 0.65, intArrayOf(1, 3, 7, 5) to 0.65)
            for ((q, shade) in quads) {
                val pts = q.map { v[it]!! }
                val col = Color.argb(150, (base[0] * shade).toInt(), (base[1] * shade).toInt(), (base[2] * shade).toInt())
                faces.add(Face(pts, pts.sumOf { it[2] } / 4, col))
            }
        }
        // permatoma kabinos dėžė aplink stebėtoją
        val bx = 3.0; val bz = 3.5
        val box = Array(8) { k -> proj(if (k and 1 != 0) bx else -bx, if (k and 2 != 0) bx else -bx, if (k and 4 != 0) bz else 0.0) }
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x88E6FF00.toInt()
        for ((i, j) in listOf(0 to 1, 1 to 3, 3 to 2, 2 to 0, 4 to 5, 5 to 7, 7 to 6, 6 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)) {
            val a = box[i]; val b = box[j]; if (a != null && b != null) c.drawLine(a[0].toFloat(), a[1].toFloat(), b[0].toFloat(), b[1].toFloat(), p)
        }
        // geltoni (švarūs) taškai, tada kubeliai nuo tolimiausių
        p.style = Paint.Style.FILL; p.color = 0xAAE6FF00.toInt()
        for (d in dots) c.drawCircle(d[0].toFloat(), d[1].toFloat(), (f * 0.25 / d[2]).toFloat().coerceIn(1.5f, 5f), p)
        for (fc in faces.sortedByDescending { it.depth }) {
            val path = android.graphics.Path(); path.moveTo(fc.pts[0][0].toFloat(), fc.pts[0][1].toFloat())
            for (k in 1 until fc.pts.size) path.lineTo(fc.pts[k][0].toFloat(), fc.pts[k][1].toFloat()); path.close()
            p.style = Paint.Style.FILL; p.color = fc.col; c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x55FFFFFF; c.drawPath(path, p)
        }
        // stebėtojas ir planšetės kryptis
        p.style = Paint.Style.FILL; p.color = Color.WHITE
        proj(0.0, 0.0, 1.5)?.let { c.drawCircle(it[0].toFloat(), it[1].toFloat(), 6f, p) }
        val a = proj(0.0, 0.0, 0.05); val b = proj(0.0, 4.0, 0.05)
        if (a != null && b != null) { p.strokeWidth = 4f; p.style = Paint.Style.STROKE; p.color = 0xFF38BDF8.toInt(); c.drawLine(a[0].toFloat(), a[1].toFloat(), b[0].toFloat(), b[1].toFloat(), p) }
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.textSize = minOf(w, hgt) * 0.045f; p.textAlign = Paint.Align.CENTER
        c.drawText("3D · vilkite – sukasi; mėlyna – kliūtis, raudona – atspindžiai", w / 2, hgt * 0.06f, p)
        if (h == null) c.drawText("(planšetės kryptis nežinoma – rodomas tik kabinos žemėlapis)", w / 2, hgt * 0.97f, p)
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
