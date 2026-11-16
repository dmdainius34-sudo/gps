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

    private fun unselectable(v: View) {
        if (v is TextView) { try { v.setTextIsSelectable(false); v.isClickable = false; v.isLongClickable = false; v.isFocusable = false } catch (_: Exception) {} }
        else if (v is ViewGroup) for (i in 0 until v.childCount) unselectable(v.getChildAt(i))
    }

    /**
     * Po dialog.show(): langas tempiamas už antraštės į visas puses (už ekrano gali išeiti, kol lieka ~10 %; viršuje antraštė lieka matoma),
     * tekstas pažymimas pirštu, o ilgai palaikius antraštę nukopijuojamas visas lango tekstas.
     */
    /** Voko atsivėrimas: langas išsiskleidžia žemyn nuo viršutinio krašto (kaip voko atvartas). Kviesti po show(). */
    fun unfold(d: Dialog) {
        val w = d.window ?: return
        w.setWindowAnimations(0)
        val v = w.decorView
        v.cameraDistance = 12000f * v.resources.displayMetrics.density
        v.alpha = 0f
        v.post {
            v.pivotX = v.width / 2f; v.pivotY = 0f
            v.rotationX = -95f; v.scaleY = 0.3f; v.alpha = 1f
            v.animate().rotationX(0f).scaleY(1f).setDuration(420)
                .setInterpolator(android.view.animation.OvershootInterpolator(0.8f)).start()
        }
    }

    fun draggable(d: Dialog) {
        val w = d.window ?: return
        val decor = w.decorView
        decor.post { makeDraggable(d, w, decor) }
    }

    private fun makeDraggable(d: Dialog, w: android.view.Window, decor: View) {
        val ctx = decor.context
        selectable(decor)
        val topId = ctx.resources.getIdentifier("topPanel", "id", "android")
        val header = (if (topId != 0) decor.findViewById<View>(topId) else null)?.takeIf { it.visibility == View.VISIBLE } ?: return
        unselectable(header)   // antraštė – tempimo rankena, jos tekstas neturi gaudyti lietimų
        // FLAG_LAYOUT_NO_LIMITS – tik pradėjus tempti: kitaip aukštas langas (telefone) išlenda už ekrano ir dingsta mygtukai
        // lango koordinatės skaičiuojamos programos lango atžvilgiu (padalintame ekrane jis ne nuo ekrano kampo)
        val parent = d.ownerActivity?.window?.decorView
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
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (!moved && kotlin.math.abs(dx) + kotlin.math.abs(dy) < 12f) return@setOnTouchListener true
                    if (!moved) {
                        // pirmas judesys: langas pririšamas prie kairio viršutinio kampo dabartinėje vietoje
                        val loc = IntArray(2); decor.getLocationOnScreen(loc)
                        val p0 = IntArray(2); parent?.getLocationOnScreen(p0)
                        w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
                        val lp = w.attributes; lp.gravity = Gravity.TOP or Gravity.START
                        lp.x = loc[0] - p0[0]; lp.y = loc[1] - p0[1]; w.attributes = lp
                        ox = lp.x; oy = lp.y
                    }
                    moved = true
                    val pw = parent?.width?.takeIf { it > 0 } ?: fullSize(ctx).x
                    val ph = parent?.height?.takeIf { it > 0 } ?: fullSize(ctx).y
                    val lp = w.attributes
                    lp.x = (ox + dx).toInt().coerceIn(-(decor.width * 0.9f).toInt(), pw - (decor.width * 0.1f).toInt())
                    lp.y = (oy + dy).toInt().coerceIn(0, ph - (decor.height * 0.1f).toInt())
                    w.attributes = lp
                }
            }
            true
        }
    }
}
