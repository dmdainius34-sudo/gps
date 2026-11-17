package lt.example.gnss

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Kalibravimo „būgnas“ permatomame fone: maži apvalūs paveikslėliai sukami pirštu aukštyn–žemyn (ratu),
 * centrinis – didžiausias ir ryškiausias, šalia jo – pavadinimas ir būsena. Bakstelėjus paveikslėlį – jis atidaromas.
 */
@SuppressLint("ViewConstructor")
class CalibDrum(ctx: Context, private val icons: List<String>, private val names: List<String>,
                private val status: (Int) -> String, start: Int, private val onPick: (Int) -> Unit) : View(ctx) {
    private val dp = ctx.resources.displayMetrics.density
    val itemH = 50 * dp
    private val n = icons.size
    private var offset = start * itemH                 // centrinio elemento padėtis (ratu)
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.RIGHT; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD1D5DB.toInt(); textAlign = Paint.Align.RIGHT }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() }
    private var vt: VelocityTracker? = null
    private var lastY = 0f; private var downY = 0f; private var dragged = false
    private var vel = 0f; private var lastT = 0L

    val current: Int get() = Math.floorMod((offset / itemH).roundToInt(), n)

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((230 * dp).toInt(), (itemH * 5).toInt())

    override fun onDraw(c: Canvas) {
        val cx = width - 26 * dp; val cy = height / 2f
        val base = offset / itemH
        val first = kotlin.math.floor(base - 2.5).toInt(); val last = kotlin.math.ceil(base + 2.5).toInt()
        // tolimiausi pirmiau, centrinis – paskutinis (viršuje)
        val order = (first..last).sortedByDescending { abs(it - base) }
        for (k in order) {
            val d = k - base
            if (abs(d) > 2.6) continue
            // būgno vaizdas: elementai „apsisuka“ tolstant nuo centro
            val ang = d * 0.55
            val y = cy + (kotlin.math.sin(ang) * itemH * 2.2).toFloat()
            val sc = (0.55 + 0.45 * cos(ang)).toFloat()
            val a = (255 * (0.25 + 0.75 * cos(ang).coerceAtLeast(0.0))).toInt().coerceIn(0, 255)
            val r = 21 * dp * sc
            val i = Math.floorMod(k, n)
            circle.color = Color.argb((a * 0.55).toInt(), 0, 0, 0); c.drawCircle(cx, y, r, circle)
            val center = abs(d) < 0.5
            ring.strokeWidth = (if (center) 2.5f else 1.2f) * dp
            ring.color = if (center) Color.argb(a, 250, 204, 21) else Color.argb((a * 0.6).toInt(), 255, 255, 255)
            c.drawCircle(cx, y, r, ring)
            icon.textSize = 22 * dp * sc; icon.alpha = a
            icon.color = if (icons[i] == "N") Color.argb(a, 239, 68, 68) else Color.argb(a, 255, 255, 255)
            icon.isFakeBoldText = icons[i] == "N"
            c.drawText(icons[i], cx, y - (icon.descent() + icon.ascent()) / 2, icon)
        }
        // centrinio pavadinimas ir būsena – kairėje, permatomoje juostelėje
        val i = current
        label.textSize = 14 * dp; small.textSize = 11 * dp
        val st = status(i).let { if (it.length > 34) it.take(33) + "…" else it }
        val right = cx - 30 * dp
        val w = maxOf(label.measureText(names[i]), small.measureText(st)) + 16 * dp
        c.drawRoundRect(RectF(right - w, cy - 22 * dp, right + 6 * dp, cy + 22 * dp), 10 * dp, 10 * dp, pill)
        c.drawText(names[i], right - 4 * dp, cy - 3 * dp, label)
        c.drawText(st, right - 4 * dp, cy + 14 * dp, small)
        // inercija ir prisitraukimas prie artimiausio
        if (vt == null) {
            val now = System.nanoTime(); val dt = if (lastT == 0L) 0.016f else ((now - lastT) / 1e9f).coerceIn(0f, 0.05f); lastT = now
            val an = anim
            if (an != null) {
                val diff = (an - offset).toFloat()
                if (abs(diff) > 0.5f) { offset += diff * 0.22f; invalidate() } else { offset = an.toFloat(); anim = null; lastT = 0L }
            } else if (abs(vel) > 30f) { offset -= vel * dt; vel *= 0.92f; invalidate() }
            else {
                vel = 0f
                val target = (offset / itemH).roundToInt() * itemH
                val diff = target - offset
                if (abs(diff) > 0.5f) { offset += diff * 0.25f; invalidate() } else { offset = target; lastT = 0L }
            }
        }
    }

    /** Elemento poslinkis nuo centro (elementais) pagal ekrano y (atvirkščiai būgno formulei). */
    private fun dAt(y: Float): Double {
        val rel = ((y - height / 2f) / (itemH * 2.2f)).coerceIn(-0.99f, 0.99f)
        return kotlin.math.asin(rel.toDouble()) / 0.55
    }
    private var grab = 0.0                               // pirštu paimtas elementas (indeksas ratu)
    private var anim: Double? = null                     // tikslas, į kurį sukama po bakstelėjimo

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                vt = VelocityTracker.obtain().also { it.addMovement(e) }; downY = e.y; dragged = false; vel = 0f; anim = null
                grab = offset / itemH + dAt(e.y)                 // kuris paveikslėlis po pirštu
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (abs(e.y - downY) > 6 * dp) dragged = true
                // paimtas paveikslėlis seka pirštą tiksliai: jo padėtis = piršto y
                if (dragged) { offset = ((grab - dAt(e.y)) * itemH).toFloat(); invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.let { it.addMovement(e); it.computeCurrentVelocity(1000); vel = if (dragged) it.yVelocity / 1.21f else 0f; it.recycle() }
                vt = null; lastT = 0L
                if (!dragged && e.actionMasked == MotionEvent.ACTION_UP) {
                    val k = Math.round(offset / itemH + dAt(e.y)).toInt()
                    val cur = Math.round(offset / itemH)
                    if (k == cur) onPick(Math.floorMod(k, n))         // bakstelėtas paryškintas – atidaromas
                    else anim = k * itemH.toDouble()                  // kitas – atsukamas į paryškintą ratuką
                }
                invalidate()
            }
        }
        return true
    }
}
