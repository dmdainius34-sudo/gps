package lt.example.gnss

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/** Langų pagalbininkai: tikras ekrano dydis (ir padalintame ekrane) ir visų iššokančių langų tempimas bei kopijavimas. */
object WinUtil {
    /** Visas ekranas (ne programos lango dalis): padalintame ekrane skiriasi nuo resources.displayMetrics. */
    @Suppress("DEPRECATION")
    fun fullSize(ctx: Context): Point {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics(); wm.defaultDisplay.getRealMetrics(m)
        return Point(m.widthPixels, m.heightPixels)
    }

    private fun texts(v: View, out: ArrayList<String>) {
        if (v is TextView && v !is Button && v !is EditText) { val t = v.text?.toString()?.trim(); if (!t.isNullOrEmpty()) out.add(t) }
        else if (v is ViewGroup) for (i in 0 until v.childCount) texts(v.getChildAt(i), out)
    }

    /** Tekstas langelyje pažymimas pirštu (ne sąrašuose, kad nesugestų bakstelėjimai). */
    private fun selectable(v: View) {
        if (v is AbsListView) return
        if (v is TextView && v !is Button && v !is EditText) { try { v.setTextIsSelectable(true) } catch (_: Exception) {} }
        else if (v is ViewGroup) for (i in 0 until v.childCount) selectable(v.getChildAt(i))
    }

    /**
     * Po dialog.show(): langas tempiamas už antraštės į visas puses (už ekrano gali išeiti, kol lieka ~10 %; viršuje antraštė lieka matoma),
     * tekstas pažymimas pirštu, o ilgai palaikius antraštę nukopijuojamas visas lango tekstas.
     */
    fun draggable(d: Dialog) {
        val w = d.window ?: return
        val decor = w.decorView
        val ctx = decor.context
        selectable(decor)
        val topId = ctx.resources.getIdentifier("topPanel", "id", "android")
        val header = (if (topId != 0) decor.findViewById<View>(topId) else null)?.takeIf { it.visibility == View.VISIBLE && it.height > 0 } ?: return
        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (moved) return
                val all = ArrayList<String>(); texts(decor, all)
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("langas", all.joinToString("\n")))
                Toast.makeText(ctx, "Lango tekstas nukopijuotas", Toast.LENGTH_SHORT).show()
            }
        })
        header.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val loc = IntArray(2); decor.getLocationOnScreen(loc)
                    val lp = w.attributes; lp.gravity = Gravity.TOP or Gravity.START; lp.x = loc[0]; lp.y = loc[1]; w.attributes = lp
                    ox = loc[0]; oy = loc[1]; sx = e.rawX; sy = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && kotlin.math.abs(dx) + kotlin.math.abs(dy) < 12f) return@setOnTouchListener true
                    moved = true
                    val sz = fullSize(ctx); val lp = w.attributes
                    lp.x = (ox + dx).toInt().coerceIn(-(decor.width * 0.9f).toInt(), sz.x - (decor.width * 0.1f).toInt())
                    lp.y = (oy + dy).toInt().coerceIn(0, sz.y - (decor.height * 0.1f).toInt())
                    w.attributes = lp
                }
            }
            true
        }
    }
}
