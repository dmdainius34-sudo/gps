package lt.example.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * PGD plano peržiūra plokščiai (kaip AlpineQuest): vilkimas, žiupsnelis, priartinus – ryškūs taškai (be glotninimo),
 * mastelio liniuotė ir dabartinė padėtis (raudonas taškas su tikslumo apskritimu).
 */
@SuppressLint("ViewConstructor")
class PlanView(ctx: Context, private val bmp: Bitmap, private val posProvider: () -> DoubleArray?) : View(ctx) {
    private val m = Matrix()
    private val inv = Matrix()
    private var fitted = false
    private val dp = ctx.resources.displayMetrics.density
    private val pSharp = Paint().apply { isFilterBitmap = false }
    private val pSmooth = Paint().apply { isFilterBitmap = true }
    private val pDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED }
    private val pRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FF0000; style = Paint.Style.FILL }
    private val pRingE = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 1.5f * dp }
    private val pTxt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13f * dp; setShadowLayer(3f * dp, 0f, 0f, Color.BLACK) }
    private val pBar = Paint().apply { color = Color.WHITE; strokeWidth = 3f * dp; setShadowLayer(3f * dp, 0f, 0f, Color.BLACK) }
    /** Metrų viename vaizdo taške. */
    private val mPerPx: Double = PgdOverlay.sizeM()?.let { it[0] / bmp.width } ?: Double.NaN

    private fun scale(): Float { val v = FloatArray(9); m.getValues(v); return kotlin.math.hypot(v[0], v[3]) }

    private val sg = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val s = scale(); val f = d.scaleFactor.coerceIn(0.5f, 2f)
            val nf = (s * f).coerceIn(0.05f, 200f) / s
            m.postScale(nf, nf, d.focusX, d.focusY); invalidate(); return true
        }
    })
    private var lx = 0f; private var ly = 0f; private var pid = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        sg.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pid = e.getPointerId(0); lx = e.x; ly = e.y }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                val i = if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && e.actionIndex == 0) 1 else 0
                if (i < e.pointerCount) { pid = e.getPointerId(i); lx = e.getX(i); ly = e.getY(i) }
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(pid); if (i < 0) return true
                if (!sg.isInProgress || e.pointerCount == 1) m.postTranslate(e.getX(i) - lx, e.getY(i) - ly)
                else m.postTranslate(e.getX(i) - lx, e.getY(i) - ly)
                lx = e.getX(i); ly = e.getY(i); invalidate()
            }
        }
        return true
    }

    /** Centruoti ties padėtimi (jei ji plane), kitaip – visas planas. */
    fun centreOnMe() {
        val p = posProvider()?.let { PgdOverlay.uvOf(it[0], it[1]) }
        if (p == null || p[0] !in 0.0..1.0 || p[1] !in 0.0..1.0) { fit(); return }
        val s = scale().coerceAtLeast(2f)
        m.setScale(s, s)
        m.postTranslate(width / 2f - (p[0] * bmp.width).toFloat() * s, height / 2f - (p[1] * bmp.height).toFloat() * s)
        invalidate()
    }

    private fun fit() {
        val s = minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        m.setScale(s, s); m.postTranslate((width - bmp.width * s) / 2f, (height - bmp.height * s) / 2f)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (!fitted && width > 0) { fitted = true; fit() }
        c.drawColor(Color.BLACK)
        val s = scale()
        c.drawBitmap(bmp, m, if (s >= 2f) pSharp else pSmooth)
        // padėtis
        posProvider()?.let { pos ->
            PgdOverlay.uvOf(pos[0], pos[1])?.let { uv ->
                val pt = floatArrayOf((uv[0] * bmp.width).toFloat(), (uv[1] * bmp.height).toFloat()); m.mapPoints(pt)
                if (!mPerPx.isNaN() && pos.size > 2 && !pos[2].isNaN()) {
                    val r = (pos[2] / mPerPx).toFloat() * s
                    c.drawCircle(pt[0], pt[1], r, pRing); c.drawCircle(pt[0], pt[1], r, pRingE)
                }
                c.drawCircle(pt[0], pt[1], 5f * dp, pDot)
            }
        }
        // mastelio liniuotė: 1–2–5 × 10ⁿ m, ~100 dp ilgio
        if (!mPerPx.isNaN()) {
            val mPerScr = mPerPx / s
            val target = 100 * dp * mPerScr
            val e = kotlin.math.floor(kotlin.math.log10(target)); var v = Math.pow(10.0, e)
            for (k in doubleArrayOf(2.0, 5.0, 10.0)) if (v * k <= target) v = Math.pow(10.0, e) * k
            val len = (v / mPerScr).toFloat()
            val x0 = 16 * dp; val y0 = height - 24 * dp
            c.drawLine(x0, y0, x0 + len, y0, pBar)
            val lbl = if (v >= 1.0) "%.0f m".format(java.util.Locale.US, v) else if (v >= 0.01) "%.0f cm".format(java.util.Locale.US, v * 100) else "%.0f mm".format(java.util.Locale.US, v * 1000)
            c.drawText(lbl + "   (plano taškas %.1f cm)".format(java.util.Locale.US, mPerPx * 100), x0, y0 - 8 * dp, pTxt)
        }
        postInvalidateDelayed(1000)   // padėtis atnaujinama kas sekundę
    }
}
