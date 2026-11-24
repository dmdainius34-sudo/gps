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
    @Volatile private var img: Bitmap? = null
    @Volatile private var loading = false
    private var ver = -1

    init { isClickable = false; isFocusable = false }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); postInvalidateOnAnimation() }

    override fun onDraw(c: Canvas) {
        postInvalidateOnAnimation()
        // teritorijų planavimo plytelės (po PGD planu)
        val tiles = PlanningTiles.shown
        if (tiles.isNotEmpty()) {
            val src = floatArrayOf(0f, 0f, 256f, 0f, 256f, 256f, 0f, 256f)
            for ((k, q) in tiles) {
                val b = PlanningTiles.get(k) ?: continue
                if (b.width != 256) { src[2] = b.width.toFloat(); src[4] = b.width.toFloat(); src[5] = b.height.toFloat(); src[7] = b.height.toFloat() }
                if (!m.setPolyToPoly(src, 0, q, 0, 4)) continue
                val sc0 = kotlin.math.hypot(q[2] - q[0], q[3] - q[1]) / b.width
                c.drawBitmap(b, m, if (sc0 > 2f) sharp else smooth)
            }
        }
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
        c.drawBitmap(b, m, if (scale > 2f) sharp else smooth)
    }
}
