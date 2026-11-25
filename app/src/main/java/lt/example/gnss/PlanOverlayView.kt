package lt.example.gnss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.View

/** PGD planas virš gaublio arti žemės: kampai – iš GlobeRenderer (PgdOverlay.screenCorners), vaizdas – pilnos raiškos. Lietimų nepriima. */
class PlanOverlayView(ctx: Context) : View(ctx) {
    private val m = Matrix()
    private val sharp = Paint().apply { isFilterBitmap = false }
    private val smooth = Paint().apply { isFilterBitmap = true }
    private val darkSharp = Paint().apply { isFilterBitmap = false; colorFilter = PgdOverlay.darkFilter }
    private val darkSmooth = Paint().apply { isFilterBitmap = true; colorFilter = PgdOverlay.darkFilter }
    @Volatile private var img: Bitmap? = null
    @Volatile private var loading = false
    private var ver = -1

    init { isClickable = false; isFocusable = false }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postInvalidateOnAnimation() }

    private val dp = ctx.resources.displayMetrics.density
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF2D2D.toInt() }
    private val pDotE = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f * dp }
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FF2D2D }
    private val pRingE = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFF2D2D.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f * dp }

    override fun onDraw(c: Canvas) {
        postInvalidateOnAnimation()
        drawPlans(c)
        // mano padėtis (virš plano): tikslumo apskritimas ir taškas
        PgdOverlay.meScreen?.let { p ->
            if (p[2] > 3f) { c.drawCircle(p[0], p[1], p[2], pRing); c.drawCircle(p[0], p[1], p[2], pRingE) }
            c.drawCircle(p[0], p[1], 6f * dp, pDot); c.drawCircle(p[0], p[1], 6f * dp, pDotE)
        }
    }

    private fun drawPlans(c: Canvas) {
        // teritorijų planavimo plytelės (po PGD planu)
        for (L in arrayOf(OrthoTiles, PlanningTiles)) drawLayer(c, L)
        drawPgd(c)
    }

    private fun drawLayer(c: Canvas, L: ArcLayer) {
        val tiles = L.shown
        if (tiles.isNotEmpty()) {
            val src = floatArrayOf(0f, 0f, 256f, 0f, 256f, 256f, 0f, 256f)
            for ((k, q) in tiles) {
                val b = L.get(k) ?: continue
                if (b.width != 256) { src[2] = b.width.toFloat(); src[4] = b.width.toFloat(); src[5] = b.height.toFloat(); src[7] = b.height.toFloat() }
                if (!m.setPolyToPoly(src, 0, q, 0, 4)) continue
                val sc0 = kotlin.math.hypot(q[2] - q[0], q[3] - q[1]) / b.width
                c.drawBitmap(b, m, if (sc0 > 2f) sharp else smooth)
            }
        }
    }

    private fun drawPgd(c: Canvas) {
        val sc = PgdOverlay.screenCorners
        if (ver != PgdOverlay.version) { ver = PgdOverlay.version; img = null }
        if (sc == null || PgdOverlay.corners == null) return
        val b = img
        if (b == null || b.isRecycled) {
            if (!loading) { loading = true; Thread { try { img = PgdOverlay.fullImage() } catch (_: Throwable) {} ; loading = false }.start() }
            return
        }
        val w = b.width.toFloat(); val h = b.height.toFloat()
        if (!m.setPolyToPoly(floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h), 0, sc, 0, 4)) return
        // ekrane vienas plano taškas didesnis nei ~2 px – ryškūs kvadratėliai (kaip AlpineQuest)
        val scale = kotlin.math.hypot(sc[2] - sc[0], sc[3] - sc[1]) / w
        val pnt = if (PgdOverlay.dark) (if (scale > 2f) darkSharp else darkSmooth) else if (scale > 2f) sharp else smooth
        val al = (PgdOverlay.alpha * 255).toInt().coerceIn(25, 255)
        pnt.alpha = al
        c.drawBitmap(b, m, pnt)
        pnt.alpha = 255
    }
}
