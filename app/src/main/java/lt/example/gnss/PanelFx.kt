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
    private val rows = ArrayDeque<List<Pair<Float, Int>>>()
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f * ctx.resources.displayMetrics.density; color = 0xFF111827.toInt() }
    private val grid = Paint().apply { color = 0x33000000; strokeWidth = 1f }
    private val lo = 10f; private val hi = 50f

    fun addSample(sats: List<Sat>) {
        rows.addFirst(sats.filter { it.cn0 > 0f }.map { it.cn0 to constColor(it.const) })
        while (rows.size > 40) rows.removeLast()
        invalidate()
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (120 * dp).toInt())

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val top = 14 * dp; val rowH = 4 * dp
        c.drawText("silpni", 2 * dp, 10 * dp, txt)
        txt.textAlign = Paint.Align.RIGHT; c.drawText("stiprūs", w - 2 * dp, 10 * dp, txt); txt.textAlign = Paint.Align.LEFT
        for (db in listOf(20f, 30f, 40f)) { val x = (db - lo) / (hi - lo) * w; c.drawLine(x, top, x, h, grid) }
        for ((i, row) in rows.withIndex()) {
            val y = top + i * rowH + rowH / 2; if (y > h) break
            val a = (255 * (1f - i.toFloat() / rows.size.coerceAtLeast(1)) ).toInt().coerceIn(40, 255)
            for ((cn0, col) in row) {
                val x = ((cn0 - lo) / (hi - lo)).coerceIn(0f, 1f) * w
                dot.color = (col and 0x00FFFFFF) or (a shl 24)
                c.drawCircle(x, y, (if (i == 0) 3.2f else 2.2f) * dp, dot)
            }
        }
    }
}
