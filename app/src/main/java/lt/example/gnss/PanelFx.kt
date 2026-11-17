package lt.example.gnss

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.sin

/**
 * Signalo kokybės spalvos (pagal vidutinį C/N0): juoda + raudona – labai blogai, tamsiai mėlyna – blogai,
 * šviesiai mėlyna + geltona – geriau, geltona – puiku. Grąžina dvi sodrias spalvas.
 */
fun qualityColors(db: Float?): Pair<Int, Int> = when {
    db == null -> 0xFF64748B.toInt() to 0xFF94A3B8.toInt()
    db <= 22f -> 0xFF0A0A0A.toInt() to 0xFFDC2626.toInt()
    db <= 30f -> 0xFF1E3A8A.toInt() to 0xFF1D4ED8.toInt()
    db <= 38f -> 0xFF38BDF8.toInt() to 0xFFFACC15.toInt()
    else -> 0xFFFDE047.toInt() to 0xFFFACC15.toInt()
}

/** NLO meniu fonas: sodrios spalvotos dėmės lėtai plaukia ir mirga (kaip pagrindinis fonas), kraštas pulsuoja. */
class ShimmerDrawable(private val density: Float) : Drawable() {
    var t = 0f
    private var c1 = 0xFF64748B.toInt(); private var c2 = 0xFF94A3B8.toInt()
    private var tc1 = c1; private var tc2 = c2
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val clip = Path()

    fun setSignal(db: Float?) { val (a, b) = qualityColors(db); tc1 = a; tc2 = b }

    override fun draw(c: Canvas) {
        c1 = ColorUtils.blendARGB(c1, tc1, 0.05f); c2 = ColorUtils.blendARGB(c2, tc2, 0.05f)
        val b = bounds; val w = b.width().toFloat(); val h = b.height().toFloat(); if (w <= 0f || h <= 0f) return
        val r = 16f * density
        clip.reset(); clip.addRoundRect(RectF(b), r, r, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        c.drawColor(0xFFFFFFFF.toInt())
        val ang = t * 2 * Math.PI
        val cols = intArrayOf(c1, c2, ColorUtils.blendARGB(c1, c2, 0.5f))
        for (i in cols.indices) {
            val ph = ang + i * 2 * Math.PI / cols.size
            val cx = b.left + w * (0.5f + 0.42f * cos(ph).toFloat()); val cy = b.top + h * (0.5f + 0.38f * sin(ph * 1.3).toFloat())
            // mirgėjimas: kiekviena dėmė savo ritmu ryškėja ir blėsta
            val fl = (0.55f + 0.25f * sin(ph * 3.0 + i).toFloat()).coerceIn(0.2f, 0.85f)
            paint.shader = RadialGradient(cx, cy, maxOf(w, h) * 0.7f, (cols[i] and 0x00FFFFFF) or ((fl * 255).toInt() shl 24), cols[i] and 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(RectF(b), paint)
        }
        paint.shader = null
        c.restore()
        border.strokeWidth = 4f * density
        val pulse = (0.6f + 0.4f * sin(ang * 2).toFloat())
        border.color = (ColorUtils.blendARGB(c1, c2, 0.5f + 0.5f * sin(ang).toFloat()) and 0x00FFFFFF) or ((pulse * 255).toInt() shl 24)
        val inset = border.strokeWidth / 2
        c.drawRoundRect(RectF(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset), r, r, border)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * C/N0 „krioklys“: kiekvieną sekundę viršuje nauja eilutė – kiekvienas palydovas taškas pagal signalo stiprumą
 * (kairėje silpni, dešinėje stiprūs), senesnės eilutės leidžiasi žemyn ir blėsta.
 */
class CnoFallView(ctx: Context) : View(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    /** Eilutės (naujausia – pirma): palydovas -> (C/N0, spalva). */
    private val rows = ArrayDeque<Map<String, Pair<Float, Int>>>()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * ctx.resources.displayMetrics.density; color = 0xFF111827.toInt() }
    private val grid = Paint().apply { color = 0x33000000; strokeWidth = 1f }
    // automatinė skalė (jautriau): rodomas dabartinių signalų intervalas ±2 dB, keičiamas sklandžiai
    private var lo = 15f; private var hi = 48f
    /** Programos signalo slenkstis (dB-Hz): juoda vertikali riba, kairėje – išmetami signalai (pilkai pritemdyta). */
    var threshold = 20f
    private val shade = Paint().apply { color = 0x33000000 }
    private val thr = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }

    fun addSample(sats: List<Sat>) {
        val m = sats.filter { it.cn0 > 0f }.associate { (it.const + it.svid) to (it.cn0 to constColor(it.const)) }
        rows.addFirst(m)
        while (rows.size > 60) rows.removeLast()
        if (m.isNotEmpty()) {
            val mn = m.values.minOf { it.first } - 2f; val mx = m.values.maxOf { it.first } + 2f
            lo += (minOf(mn, mx - 8f) - lo) * 0.2f; hi += (maxOf(mx, mn + 8f) - hi) * 0.2f
        }
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (130 * dp).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = 14 * dp; val rowH = 2 * dp
        c.drawText("silpni %.0f dB-Hz".format(java.util.Locale.US, lo), 2 * dp, 10 * dp, txt)
        txt.textAlign = Paint.Align.RIGHT; c.drawText("%.0f dB-Hz stiprūs".format(java.util.Locale.US, hi), w - 2 * dp, 10 * dp, txt); txt.textAlign = Paint.Align.LEFT
        fun xOf(db: Float) = ((db - lo) / (hi - lo)).coerceIn(0f, 1f) * w
        var g = (kotlin.math.ceil(lo / 5f) * 5f); while (g < hi) { val x = xOf(g); c.drawLine(x, top, x, h, grid); g += 5f }
        // slenkstis: kairėje išmetami signalai
        val tx = ((threshold - lo) / (hi - lo)) * w
        if (tx > 0f) { c.drawRect(0f, top, minOf(tx, w), h, shade); if (tx <= w) { thr.strokeWidth = 2f * dp; c.drawLine(tx, top, tx, h, thr) } }
        // kiekvieno palydovo pėdsakas – linija per eilutes (sukant prietaisą matomi antenos svyravimai)
        val keys = rows.firstOrNull()?.keys ?: return
        for (k in keys) {
            val p = Path(); var started = false; var col = 0
            for ((i, row) in rows.withIndex()) {
                val y = top + i * rowH; if (y > h) break
                val v = row[k]
                if (v == null) { started = false; continue }
                col = v.second
                val x = xOf(v.first)
                if (!started) { p.moveTo(x, y); started = true } else p.lineTo(x, y)
            }
            line.color = col; line.strokeWidth = 1.6f * dp; line.alpha = 220
            c.drawPath(p, line)
        }
    }
}

/** Piktograma „pasukite prietaisą į šiaurę“: prietaiso stačiakampis, aplink jį lenkta rodyklė ir raudona N viršuje. */
class DeviceTurnIcon(ctx: Context) : View(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44000000 }
    private val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val nTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEF4444.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val cx = w / 2; val cy = h / 2; val r = minOf(w, h) / 2
        c.drawCircle(cx, cy, r, bg)
        ln.strokeWidth = 1.6f * dp
        // prietaisas
        val pw = r * 0.42f; val ph = r * 0.72f
        c.drawRoundRect(RectF(cx - pw / 2, cy - ph / 2 + r * 0.08f, cx + pw / 2, cy + ph / 2 + r * 0.08f), r * 0.1f, r * 0.1f, ln)
        // lenkta rodyklė aplink
        val ar = r * 0.68f
        c.drawArc(RectF(cx - ar, cy - ar + r * 0.08f, cx + ar, cy + ar + r * 0.08f), 200f, 230f, false, ln)
        val ang = Math.toRadians(70.0); val tx = cx + ar * cos(ang).toFloat(); val ty = cy + r * 0.08f + ar * sin(ang).toFloat()
        val head = Path().apply { moveTo(tx, ty); lineTo(tx + r * 0.2f, ty - r * 0.02f); lineTo(tx + r * 0.05f, ty - r * 0.2f); close() }
        c.drawPath(head, fill)
        // N viršuje
        nTxt.textSize = r * 0.42f
        c.drawText("N", cx, cy - r * 0.48f, nTxt)
    }
}

/**
 * Patarimas planšetei (TurnHint): lenkta rodyklė aplink planšetę – pasukti tiek laipsnių; tiesi rodyklė – stipriausių signalų pusė.
 * Apačioje – kiek dB-Hz geriau. Bakstelėjus – paslepiama 2 min.
 */
class TurnHintView(ctx: Context) : View(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF38BDF8.toInt() }
    private val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFDE047.toInt() }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private var hiddenUntil = 0L
    var hint: TurnHint.Hint? = null
        set(v) {
            field = v
            visibility = if (v == null || System.currentTimeMillis() < hiddenUntil) GONE else VISIBLE
            invalidate()
        }

    init { setOnClickListener { hiddenUntil = System.currentTimeMillis() + 120_000L; visibility = GONE } }

    override fun onDraw(c: Canvas) {
        val h = hint ?: return
        val w = width.toFloat(); val ht = height.toFloat(); val cx = w / 2; val r = minOf(w, ht * 0.82f) / 2; val cy = r + 2 * dp
        c.drawCircle(cx, cy, r, bg)
        ring.strokeWidth = 2f * dp; c.drawCircle(cx, cy, r - dp, ring)
        ln.strokeWidth = 2.2f * dp
        // planšetė centre
        val pw = r * 0.36f; val ph = r * 0.56f
        c.drawRoundRect(RectF(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2), r * 0.08f, r * 0.08f, ln)
        when (h) {
            is TurnHint.Hint.Turn -> {
                val cw = h.deg > 0
                val sweep = (kotlin.math.abs(h.deg).toFloat()).coerceIn(30f, 300f)
                val ar = r * 0.72f
                val start = -90f
                c.drawArc(RectF(cx - ar, cy - ar, cx + ar, cy + ar), start, if (cw) sweep else -sweep, false, ln)
                val end = Math.toRadians((start + if (cw) sweep else -sweep).toDouble())
                val tx = cx + ar * cos(end).toFloat(); val ty = cy + ar * sin(end).toFloat()
                // rodyklės smaigalys – liestinės kryptimi
                val tang = end + if (cw) Math.PI / 2 else -Math.PI / 2
                val hl = r * 0.26f
                val p = Path().apply {
                    moveTo(tx + hl * cos(tang).toFloat(), ty + hl * sin(tang).toFloat())
                    lineTo(tx + hl * 0.6f * cos(tang + 2.4).toFloat(), ty + hl * 0.6f * sin(tang + 2.4).toFloat())
                    lineTo(tx + hl * 0.6f * cos(tang - 2.4).toFloat(), ty + hl * 0.6f * sin(tang - 2.4).toFloat())
                    close()
                }
                c.drawPath(p, fill)
                txt.textSize = r * 0.34f
                c.drawText("%d°".format(kotlin.math.abs(h.deg).toInt()), cx, cy + r * 0.12f, txt)
            }
            is TurnHint.Hint.Toward -> {
                c.save(); c.rotate(h.relDeg.toFloat(), cx, cy)
                c.drawLine(cx, cy + r * 0.1f, cx, cy - r * 0.62f, ln)
                val p = Path().apply { moveTo(cx, cy - r * 0.92f); lineTo(cx - r * 0.22f, cy - r * 0.56f); lineTo(cx + r * 0.22f, cy - r * 0.56f); close() }
                c.drawPath(p, fill)
                c.restore()
            }
        }
        txt.textSize = 11f * dp
        c.drawText("+%.0f dB".format(java.util.Locale.US, h.gainDb), cx, ht - 2 * dp, txt)
    }
}
