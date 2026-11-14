package lt.example.gnss

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream

/**
 * Pokalbio su Claude Code „PagrindinisKodas“ langas (~10×10 cm): istorija iš GitHub PR #2, žinutės įvedimas,
 * nuotraukos pridėjimas (rodoma maža, be atsisiuntimo), mano atsakymuose – paveikslėliai mažu formatu ir failų atsisiuntimas.
 */
class PkChat(ctx: Context, private val token: () -> String, private val report: (String) -> String,
             private val pickImage: ((Bitmap?) -> Unit) -> Unit, private val saveFile: (String, ByteArray) -> String,
             private val openUrl: (String) -> Unit, val onClose: () -> Unit) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private val list = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6)) }
    private val scroll = ScrollView(ctx).apply { addView(list) }
    private val input = EditText(ctx).apply {
        hint = "Žinutė „PagrindinisKodas“…"; textSize = 14f; minLines = 1; maxLines = 4
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    private val withReport = CheckBox(ctx).apply { text = "su ataskaita"; textSize = 11f; isChecked = false }
    private var photo: Bitmap? = null
    private val photoView = ImageView(ctx).apply { visibility = GONE; adjustViewBounds = true }
    private val status = TextView(ctx).apply { textSize = 11f; setTextColor(Color.DKGRAY) }
    private var lastIds = ""
    private val imgCache = HashMap<String, Bitmap?>()
    private var alive = true
    private val lamp = View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.GRAY) } }

    /** Virš kitų programų: tempimas už antraštės perstumia langą. */
    var onDragBy: ((Float, Float) -> Unit)? = null
    private var dragX = 0f; private var dragY = 0f

    private fun setLamp(c: Int) { (lamp.background as GradientDrawable).setColor(c) }

    private fun dp(v: Int) = (v * d).toInt()

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply { cornerRadius = 16f * d; setColor(Color.WHITE) }
        elevation = 10 * d
        setPadding(dp(8), dp(8), dp(8), dp(8))
        val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(lamp, LayoutParams(dp(12), dp(12)).apply { setMargins(0, 0, dp(6), 0) })
        head.addView(TextView(ctx).apply { text = "PagrindinisKodas · Claude Code"; textSize = 15f; setTextColor(Color.BLACK) },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Button(ctx).apply { text = "↻"; minWidth = 0; minimumWidth = 0; setOnClickListener { refresh() } }, LayoutParams(dp(44), dp(44)))
        head.addView(Button(ctx).apply { text = "✕"; minWidth = 0; minimumWidth = 0; setOnClickListener { close() } }, LayoutParams(dp(44), dp(44)))
        head.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { dragX = e.rawX; dragY = e.rawY; true }
                android.view.MotionEvent.ACTION_MOVE -> { onDragBy?.invoke(e.rawX - dragX, e.rawY - dragY); dragX = e.rawX; dragY = e.rawY; true }
                else -> true
            }
        }
        addView(head)
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(photoView, LayoutParams(dp(90), dp(90)))
        addView(status)
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(Button(ctx).apply { text = "📷"; minWidth = 0; minimumWidth = 0
            setOnClickListener { pickImage { b -> photo = b?.let { scaled(it, 900) }; photoView.setImageBitmap(photo); photoView.visibility = if (photo != null) VISIBLE else GONE } } },
            LayoutParams(dp(44), dp(44)))
        row.addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(ctx).apply { text = "➤"; minWidth = 0; minimumWidth = 0; setOnClickListener { send() } }, LayoutParams(dp(48), dp(44)))
        addView(withReport)
        addView(row)
        refresh(); poll()
    }

    fun prefill(t: String) { if (t.isNotBlank()) input.setText(t) }

    private fun poll() { ui.postDelayed({ if (alive) { refresh(silent = true); poll() } }, 15_000L) }

    fun close() { alive = false; onClose() }

    private fun scaled(b: Bitmap, max: Int): Bitmap {
        val k = minOf(1f, max.toFloat() / maxOf(b.width, b.height))
        return if (k >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    /** JPEG ≤ ~44 tūkst. base64 ženklų (GitHub komentaras ≤ 65 536). */
    private fun jpegB64(b: Bitmap): String {
        var bmp = b; var q = 80
        while (true) {
            val o = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, q, o)
            val s = Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP)
            if (s.length <= 44_000 || (q <= 30 && bmp.width < 300)) return s
            if (q > 40) q -= 15 else { bmp = scaled(bmp, (maxOf(bmp.width, bmp.height) * 0.75f).toInt()); q = 70 }
        }
    }

    private fun send() {
        val t = token(); val q = input.text.toString().trim()
        if (t.isBlank()) { status.text = "Įveskite GitHub raktą NLO lange (laukelis po „Pridėti ataskaitą“)."; return }
        if (q.isEmpty() && photo == null) { status.text = "Parašykite žinutę arba pridėkite nuotrauką."; return }
        status.text = "Siunčiama…"
        val img = photo?.let { jpegB64(it) }
        val rep = if (withReport.isChecked) report(q) else null
        Thread {
            try {
                var body = PkBridge.format(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL, q.ifEmpty { "(nuotrauka)" }, rep)
                if (img != null) body += PkBridge.imageBlock(img)
                PkBridge.post(t, body)
                ui.post { input.setText(""); photo = null; photoView.visibility = GONE; setLamp(0xFF22C55E.toInt()); status.text = "Išsiųsta. Atsakymas atsiras čia (tikrinama kas 15 s)."; refresh() }
            } catch (e: Exception) { ui.post { setLamp(0xFFEF4444.toInt()); status.text = "Nepavyko: ${e.message}" } }
        }.start()
    }

    fun refresh(silent: Boolean = false) {
        val t = token(); if (t.isBlank()) { setLamp(Color.GRAY); status.text = "Įveskite GitHub raktą NLO lange."; return }
        if (!silent) { status.text = "Atnaujinama…"; setLamp(0xFFEAB308.toInt()) }
        Thread {
            try {
                val all = PkBridge.all(t).takeLast(40)
                val ids = all.joinToString(",") { it.id.toString() }
                ui.post { setLamp(0xFF22C55E.toInt()); if (ids != lastIds) { lastIds = ids; render(all) }; if (!silent || status.text == "Atnaujinama…") status.text = "" }
            } catch (e: Exception) { ui.post { setLamp(0xFFEF4444.toInt()); status.text = "Nepavyko gauti pokalbio: ${e.message}" } }
        }.start()
    }

    private fun render(all: List<PkBridge.Reply>) {
        list.removeAllViews()
        for (m in all) {
            val mine = m.body.contains(PkBridge.MARK)
            val box = LinearLayout(context).apply {
                orientation = VERTICAL; setPadding(dp(8), dp(6), dp(8), dp(6))
                background = GradientDrawable().apply { cornerRadius = 12f * d; setColor(if (mine) 0xFFDBEAFE.toInt() else 0xFFF1F5F9.toInt()) }
            }
            val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { setMargins(if (mine) dp(24) else 0, dp(3), if (mine) 0 else dp(24), dp(3)) }
            box.addView(TextView(context).apply { textSize = 10f; setTextColor(Color.GRAY)
                text = (if (mine) "Jūs (programa)" else "PagrindinisKodas") + " · " + m.at.replace('T', ' ').take(16) })
            box.addView(TextView(context).apply { textSize = 13f; setTextColor(Color.BLACK); setTextIsSelectable(true)
                text = PkBridge.textOf(m.body).replace(Regex("""!\[[^\]]*\]\([^)]*\)"""), "").replace(Regex("""\[([^\]]+)\]\((https?://[^)]+)\)"""), "$1") })
            // jūsų nuotrauka – maža, be atsisiuntimo
            PkBridge.imageOf(m.body)?.let { b64 -> try { val bytes = Base64.decode(b64, Base64.DEFAULT)
                box.addView(ImageView(context).apply { adjustViewBounds = true; setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) }, LayoutParams(dp(120), dp(120))) } catch (_: Exception) {} }
            // mano paveikslėliai – maži, failai – atsisiuntimo mygtukai
            Regex("""!\[[^\]]*\]\((https?://[^)\s]+)\)""").findAll(m.body).forEach { addRemoteImage(box, it.groupValues[1]) }
            Regex("""(?<!!)\[([^\]]+)\]\((https?://[^)\s]+)\)""").findAll(m.body).forEach { mm ->
                val name = mm.groupValues[1]; val url = mm.groupValues[2]
                box.addView(Button(context).apply { text = "⬇ $name"; textSize = 12f; isAllCaps = false
                    setOnClickListener {
                        if (url.contains("raw.githubusercontent.com") || url.contains("/raw/")) {
                            status.text = "Atsisiunčiama $name…"
                            Thread { try { val b = PkBridge.download(url, token()); val where = saveFile(url.substringAfterLast('/').substringBefore('?'), b)
                                ui.post { status.text = "Išsaugota: $where" } } catch (e: Exception) { ui.post { status.text = "Nepavyko: ${e.message}" } } }.start()
                        } else openUrl(url)
                    } })
            }
            list.addView(box, lp)
        }
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addRemoteImage(box: LinearLayout, url: String) {
        val iv = ImageView(context).apply { adjustViewBounds = true }
        box.addView(iv, LayoutParams(dp(150), dp(150)))
        imgCache[url]?.let { iv.setImageBitmap(it); return }
        Thread { try { val b = PkBridge.download(url, token())
            val o = BitmapFactory.Options().apply { inSampleSize = 1 }
            val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, o)?.let { scaled(it, 600) }
            imgCache[url] = bmp; ui.post { iv.setImageBitmap(bmp) } } catch (_: Exception) {} }.start()
    }
}
