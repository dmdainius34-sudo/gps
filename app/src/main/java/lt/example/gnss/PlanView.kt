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
    private val pDarkSharp = Paint().apply { isFilterBitmap = false; colorFilter = PgdOverlay.darkFilter }
    private val pDarkSmooth = Paint().apply { isFilterBitmap = true; colorFilter = PgdOverlay.darkFilter }
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
    private var lastAng = Float.NaN
    /** Ekrano centro koordinatės (ETRS89, kaip plano kampai) – ilgai palaikius nukopijuojamos. */
    @Volatile var centreText = ""
    private val gd = android.view.GestureDetector(ctx, object : android.view.GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (centreText.isEmpty()) return
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("koordinatės", centreText))
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            android.widget.Toast.makeText(context, "Nukopijuota: $centreText", android.widget.Toast.LENGTH_SHORT).show()
        }
    })
    private val pCross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f * dp; setShadowLayer(3f * dp, 0f, 0f, Color.BLACK) }
    private val pNorth = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5A4E.toInt(); textSize = 16f * dp; isFakeBoldText = true; setShadowLayer(3f * dp, 0f, 0f, Color.BLACK) }
    private fun ang(e: MotionEvent) = Math.toDegrees(kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        sg.onTouchEvent(e); gd.onTouchEvent(e)
        // sukimas dviem pirštais
        if (e.pointerCount >= 2 && e.actionMasked == MotionEvent.ACTION_MOVE) {
            val a = ang(e)
            if (!lastAng.isNaN()) {
                var d = a - lastAng; if (d > 180) d -= 360f; if (d < -180) d += 360f
                m.postRotate(d, (e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2)
            }
            lastAng = a
        } else if (e.pointerCount < 2) lastAng = Float.NaN
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) lastAng = Float.NaN
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
        c.drawBitmap(bmp, m, if (PgdOverlay.dark) (if (s >= 2f) pDarkSharp else pDarkSmooth) else if (s >= 2f) pSharp else pSmooth)
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
        // ekrano centro kryželis ir koordinatės (kaip gaublyje)
        val cx = width / 2f; val cy = height / 2f
        if (m.invert(inv)) {
            val pt = floatArrayOf(cx, cy); inv.mapPoints(pt)
            val ll = PgdOverlay.latLonOf(pt[0].toDouble() / bmp.width, pt[1].toDouble() / bmp.height)
            if (ll != null) {
                centreText = "%.7f, %.7f".format(java.util.Locale.US, ll[0], ll[1])
                val r = 14f * dp
                c.drawLine(cx - r, cy, cx - 4 * dp, cy, pCross); c.drawLine(cx + 4 * dp, cy, cx + r, cy, pCross)
                c.drawLine(cx, cy - r, cx, cy - 4 * dp, pCross); c.drawLine(cx, cy + 4 * dp, cx, cy + r, pCross)
                val tw = pTxt.measureText(centreText)
                c.drawText(centreText, cx - tw / 2, cy + r + 18 * dp, pTxt)
                // šiaurės kryptis ekrane (planą pasukus)
                PgdOverlay.uvOf(ll[0] + 0.0005, ll[1])?.let { uv ->
                    val q = floatArrayOf((uv[0] * bmp.width).toFloat(), (uv[1] * bmp.height).toFloat()); m.mapPoints(q)
                    val dx = q[0] - cx; val dy = q[1] - cy; val dl = kotlin.math.hypot(dx, dy)
                    if (dl > 1e-3f) {
                        val nx = 40f * dp; val ny = 90f * dp; val len = 22f * dp
                        c.drawLine(nx, ny, nx + dx / dl * len, ny + dy / dl * len, pNorth.apply { strokeWidth = 3f * dp })
                        c.drawText("Š", nx + dx / dl * (len + 12 * dp) - 6 * dp, ny + dy / dl * (len + 12 * dp) + 6 * dp, pNorth)
                    }
                }
            }
        }
        postInvalidateDelayed(1000)   // padėtis atnaujinama kas sekundę
    }
}
