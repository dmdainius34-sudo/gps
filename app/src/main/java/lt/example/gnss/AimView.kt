package lt.example.gnss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Taikinys kalibravimui: centras – orientyras, taškas – kur rodo planšetės nugarėlė.
 * Vertikalė – aukščio skirtumas (gravitacija, patikima); horizontalė – azimuto skirtumas pagal DABARTINĮ kompasą
 * (būtent jį kalibravimas ir ištaisys, todėl rodomas brūkšniuotu žiedu). Mastelis netiesinis: žiedai 2°, 5°, 10°, 20°, 45°.
 */
class AimView(ctx: Context) : View(ctx) {
    var dAz = 0.0; var dEl = 0.0; var ok = false; var label = ""
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rings = doubleArrayOf(2.0, 5.0, 10.0, 20.0, 45.0)

    override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); setMeasuredDimension(s, (s * 0.9).toInt()) }

    private fun k(d: Double): Float = (sign(d) * min(1.0, sqrt(abs(d) / 45.0))).toFloat()

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(cx, cy) * 0.9f
        p.style = Paint.Style.FILL; p.color = 0xFF0B1020.toInt(); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        for (d in rings) {
            p.color = if (d == 2.0) 0xFF22C55E.toInt() else 0x66FFFFFF
            c.drawCircle(cx, cy, r * k(d), p)
        }
        p.color = 0x66FFFFFF; c.drawLine(cx - r, cy, cx + r, cy, p); c.drawLine(cx, cy - r, cx, cy + r, p)
        // orientyras centre
        p.style = Paint.Style.FILL; p.color = 0xFFFACC15.toInt(); c.drawCircle(cx, cy, r * 0.06f, p)
        // planšetės nugarėlė: aukštyn – nugarėlė per aukštai (orientyras žemiau), dešinėn – pasukta į dešinę
        val x = cx + r * k(dAz); val y = cy - r * k(dEl)
        p.color = if (ok) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
        p.strokeWidth = 5f; p.style = Paint.Style.STROKE
        c.drawCircle(x, y, r * 0.07f, p)
        c.drawLine(x - r * 0.11f, y, x + r * 0.11f, y, p); c.drawLine(x, y - r * 0.11f, x, y + r * 0.11f, p)
        p.style = Paint.Style.FILL; p.color = 0xCCFFFFFF.toInt(); p.textSize = r * 0.08f; p.textAlign = Paint.Align.CENTER
        c.drawText(label, cx, cy + r + r * 0.09f, p)
        p.textSize = r * 0.06f; p.textAlign = Paint.Align.LEFT
        for (d in rings.take(4)) c.drawText("${d.toInt()}°", cx + r * k(d) + 4, cy - 4, p)
    }
}
