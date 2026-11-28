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
                private val status: (Int) -> String, start: Int, private val onPick: (Int) -> Unit,
                /** Mėnulio apšviesta dalis 0..1 ir ar jaunatis (apšviesta dešinė pusė) – paveikslėlis rodo tikrą fazę. */
                private val moonK: Double = 0.5, private val moonWax: Boolean = true) : View(ctx) {
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
            drawIcon(c, icons[i], cx, y, r, a)
        }
        // centrinio pavadinimas ir būsena – kairėje, permatomoje juostelėje
        val i = current
        label.textSize = 14 * dp; small.textSize = 11 * dp
        val st = status(i).let { if (it.length > 26) it.take(25) + "…" else it }
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

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private fun col(a: Int, rgb: Int) = (a shl 24) or (rgb and 0xFFFFFF)

    /** Piešti paveikslėliai (ne šriftas): kompaso rodyklė, Saulė su spinduliais, tikros fazės Mėnulis, Šiaurinė žvaigždė, Oriono žvaigždynas, užrašų knygelė. */
    private fun drawIcon(c: Canvas, key: String, x: Float, y: Float, r: Float, a: Int) {
        fill.shader = null; fill.style = Paint.Style.FILL
        when (key) {
            "north" -> {
                val l = r * 0.78f; val w = r * 0.24f
                val n = android.graphics.Path().apply { moveTo(x, y - l); lineTo(x + w, y); lineTo(x - w, y); close() }
                val s = android.graphics.Path().apply { moveTo(x, y + l); lineTo(x + w, y); lineTo(x - w, y); close() }
                fill.color = col(a, 0xEF4444); c.drawPath(n, fill)
                fill.color = col(a, 0xE5E7EB); c.drawPath(s, fill)
                fill.color = col(a, 0x111827); c.drawCircle(x, y, r * 0.07f, fill)
            }
            "sun" -> {
                line.color = col(a, 0xFBBF24); line.strokeWidth = r * 0.09f
                for (k in 0 until 12) { val t = Math.PI * k / 6; val cs = cos(t).toFloat(); val sn = kotlin.math.sin(t).toFloat()
                    c.drawLine(x + cs * r * 0.58f, y + sn * r * 0.58f, x + cs * r * (if (k % 2 == 0) 0.88f else 0.76f), y + sn * r * (if (k % 2 == 0) 0.88f else 0.76f), line) }
                fill.shader = android.graphics.RadialGradient(x - r * 0.12f, y - r * 0.12f, r * 0.5f, col(a, 0xFFF7AE), col(a, 0xF59E0B), android.graphics.Shader.TileMode.CLAMP)
                c.drawCircle(x, y, r * 0.44f, fill); fill.shader = null
            }
            "moon" -> {
                val R = r * 0.62f
                fill.color = col((a * 0.35).toInt(), 0x9CA3AF); c.drawCircle(x, y, R, fill)          // neapšviesta dalis
                val k = moonK.coerceIn(0.0, 1.0); val ax = (R * kotlin.math.abs(1 - 2 * k)).toFloat()
                val p = android.graphics.Path()
                p.arcTo(RectF(x - R, y - R, x + R, y + R), -90f, 180f)                                 // apšviestas kraštas (dešinėje)
                p.arcTo(RectF(x - ax, y - R, x + ax, y + R), 90f, if (k < 0.5) -180f else 180f)        // terminatorius
                p.close()
                c.save(); if (!moonWax) c.scale(-1f, 1f, x, y)
                fill.color = col(a, 0xF3F4F6); c.drawPath(p, fill); c.restore()
            }
            "polaris" -> {
                fill.shader = android.graphics.RadialGradient(x, y, r * 0.55f, col((a * 0.7).toInt(), 0xBFDBFE), col(0, 0xBFDBFE), android.graphics.Shader.TileMode.CLAMP)
                c.drawCircle(x, y, r * 0.55f, fill); fill.shader = null
                fill.color = col(a, 0xFFFFFF)
                val sp = android.graphics.Path(); val L = r * 0.8f; val S = r * 0.12f
                sp.moveTo(x, y - L); sp.lineTo(x + S, y - S); sp.lineTo(x + L, y); sp.lineTo(x + S, y + S)
                sp.lineTo(x, y + L); sp.lineTo(x - S, y + S); sp.lineTo(x - L, y); sp.lineTo(x - S, y - S); sp.close()
                c.drawPath(sp, fill)
            }
            "orion" -> {
                fun st(dx: Float, dy: Float, rr: Float, rgb: Int) { fill.color = col(a, rgb); c.drawCircle(x + dx * r, y + dy * r, rr * r, fill) }
                line.color = col((a * 0.35).toInt(), 0xFFFFFF); line.strokeWidth = r * 0.04f
                val pts = floatArrayOf(-0.38f, -0.55f, 0.36f, -0.5f, 0.12f, -0.05f, -0.12f, 0.05f, 0.42f, 0.55f, -0.36f, 0.58f)
                c.drawLine(x - 0.38f * r, y - 0.55f * r, x - 0.12f * r, y + 0.05f * r, line); c.drawLine(x + 0.36f * r, y - 0.5f * r, x + 0.12f * r, y - 0.05f * r, line)
                c.drawLine(x + 0.12f * r, y - 0.05f * r, x + 0.42f * r, y + 0.55f * r, line); c.drawLine(x - 0.12f * r, y + 0.05f * r, x - 0.36f * r, y + 0.58f * r, line)
                st(pts[0], pts[1], 0.11f, 0xFB923C)           // Betelgeizė
                st(pts[2], pts[3], 0.08f, 0xE0E7FF)           // Belatriksė
                st(0.12f, -0.05f, 0.07f, 0xFFFFFF); st(0f, 0f, 0.07f, 0xFFFFFF); st(-0.12f, 0.05f, 0.07f, 0xFFFFFF)   // juosta
                st(pts[8], pts[9], 0.11f, 0x93C5FD)           // Rigelis
                st(pts[10], pts[11], 0.07f, 0xE0E7FF)         // Saifas
            }
            "grid" -> {
                // tinklelis (neoninis salotinis), šiaurės rodyklė viršuje
                line.color = col(a, 0xD4FF1A); line.strokeWidth = r * 0.08f
                val g = r * 0.62f
                for (k in -1..1) { val o = k * g * 0.66f; c.drawLine(x + o, y - g, x + o, y + g, line); c.drawLine(x - g, y + o, x + g, y + o, line) }
                fill.color = col(a, 0xEF4444)
                c.drawPath(android.graphics.Path().apply { moveTo(x, y - r * 0.98f); lineTo(x + r * 0.2f, y - r * 0.62f); lineTo(x - r * 0.2f, y - r * 0.62f); close() }, fill)
            }
            "log" -> {
                val bw = r * 0.62f; val bh = r * 0.8f
                fill.color = col(a, 0xFDE68A); c.drawRoundRect(RectF(x - bw, y - bh, x + bw, y + bh), r * 0.1f, r * 0.1f, fill)
                fill.color = col(a, 0xB45309); c.drawRect(x - bw, y - bh, x - bw + r * 0.18f, y + bh, fill)
                line.color = col(a, 0x92400E); line.strokeWidth = r * 0.06f
                for (k in 0..3) { val yy = y - bh * 0.5f + k * bh * 0.33f; c.drawLine(x - bw + r * 0.32f, yy, x + bw - r * 0.15f, yy, line) }
            }
            else -> { icon.textSize = 20 * dp; icon.color = col(a, 0xFFFFFF); c.drawText(key, x, y - (icon.descent() + icon.ascent()) / 2, icon) }
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
