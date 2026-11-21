package lt.example.gnss

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/** Statinė schema: kaip laikyti planšetę – ekranas į save, nugarėlė į orientyrą. Nerodo judančio taikinio. */
class AimView(ctx: Context) : View(ctx) {
    var ok = false
    var label = ""
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(w: Int, h: Int) { val s = MeasureSpec.getSize(w); setMeasuredDimension(s, (s * 0.6).toInt()) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        p.style = Paint.Style.FILL; p.color = 0xFF0B1020.toInt(); c.drawRoundRect(0f, 0f, w, h, 24f, 24f, p)
        val tw = w * 0.34f; val th = tw * 0.62f
        val tx = w * 0.3f; val ty = h * 0.55f
        p.color = 0xFF334155.toInt(); c.drawRoundRect(tx - tw / 2, ty - th / 2, tx + tw / 2, ty + th / 2, 18f, 18f, p)
        p.color = 0xFF1E293B.toInt(); c.drawRoundRect(tx - tw * 0.42f, ty - th * 0.42f, tx + tw * 0.42f, ty + th * 0.42f, 10f, 10f, p)
        p.color = 0xFF94A3B8.toInt(); p.textSize = h * 0.07f; p.textAlign = Paint.Align.CENTER
        c.drawText("ekranas į save", tx, ty + p.textSize / 3, p)
        val sx = w * 0.8f; val sy = h * 0.22f; val sr = h * 0.12f
        p.color = 0xFFFACC15.toInt(); c.drawCircle(sx, sy, sr, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 5f
        p.color = if (ok) 0xFF22C55E.toInt() else 0xFFEF4444.toInt()
        c.drawLine(tx + tw * 0.25f, ty - th * 0.25f, sx - sr * 1.4f, sy + sr * 1.4f, p)
        p.style = Paint.Style.FILL; p.color = 0xCCFFFFFF.toInt(); p.textSize = h * 0.08f; p.textAlign = Paint.Align.CENTER
        c.drawText("nugarėlė → orientyras", w / 2, h * 0.92f, p)
        p.textSize = h * 0.07f
        c.drawText(label, w / 2, h * 0.92f - p.textSize * 1.4f, p)
    }
}
