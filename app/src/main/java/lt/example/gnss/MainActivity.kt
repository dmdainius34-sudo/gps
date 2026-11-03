package lt.example.gnss

import android.Manifest
import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.Notification
import android.os.IBinder
import android.app.Service
import android.provider.Settings
import android.view.GestureDetector
import kotlin.math.sqrt
import android.view.WindowManager
import android.view.Surface
import javax.microedition.khronos.opengles.GL10
import javax.microedition.khronos.egl.EGLConfig
import java.nio.FloatBuffer
import java.nio.ByteOrder
import java.nio.ByteBuffer
import android.graphics.PixelFormat
import android.opengl.Matrix
import android.opengl.GLUtils
import android.opengl.GLSurfaceView
import android.opengl.GLES20
import android.view.HapticFeedbackConstants
import android.text.InputType
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.SeekBar
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurement
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ============================================================
//  PAGALBINĖS FUNKCIJOS
// ============================================================

fun fx(v: Double, digits: Int = 1): String = String.format(Locale.US, "%.${digits}f", v)

// ============================================================
//  GEOIDAS
// ============================================================

enum class GeoidMode(val label: String, val assetFile: String) {
    LIT20G("LIT20G (Lietuva)", "LIT20G.txt"),
    WORLD("Pasaulinis (EGM2008)", "WORLD_EGM2008.txt")
}

class GeoidGrid(
    private val assetName: String,
    private val latMin: Double,
    private val latStep: Double,
    private val nLat: Int,
    private val lonMin: Double,
    private val lonStep: Double,
    private val nLon: Int,
) {
    @Volatile private var grid: FloatArray? = null
    val isLoaded: Boolean get() = grid != null

    fun load(context: Context) {
        if (grid != null) return
        val data = FloatArray(nLat * nLon) { Float.NaN }
        context.assets.open(assetName).bufferedReader().useLines { lines ->
            for (line in lines) {
                val p = line.trim().split(Regex("\\s+"))
                if (p.size < 3) continue
                val lat = p[0].toDoubleOrNull() ?: continue
                val lon = p[1].toDoubleOrNull() ?: continue
                val n = p[2].toDoubleOrNull() ?: continue
                val i = ((lat - latMin) / latStep).roundToInt()
                val j = ((lon - lonMin) / lonStep).roundToInt()
                if (i in 0 until nLat && j in 0 until nLon) {
                    data[i * nLon + j] = n.toFloat()
                }
            }
        }
        grid = data
    }

    fun undulation(lat: Double, lon: Double): Double? {
        val g = grid ?: return null
        val fi = (lat - latMin) / latStep
        val fj = (lon - lonMin) / lonStep
        val i0 = floor(fi).toInt()
        val j0 = floor(fj).toInt()
        if (i0 < 0 || i0 >= nLat - 1 || j0 < 0 || j0 >= nLon - 1) return null
        val di = fi - i0
        val dj = fj - j0
        val v00 = g[i0 * nLon + j0]
        val v01 = g[i0 * nLon + j0 + 1]
        val v10 = g[(i0 + 1) * nLon + j0]
        val v11 = g[(i0 + 1) * nLon + j0 + 1]
        if (v00.isNaN() || v01.isNaN() || v10.isNaN() || v11.isNaN()) return null
        val v0 = v00 * (1 - dj) + v01 * dj
        val v1 = v10 * (1 - dj) + v11 * dj
        return v0 * (1 - di) + v1 * di
    }
}

object Geoid {
    private val lit20g = GeoidGrid(
        assetName = GeoidMode.LIT20G.assetFile,
        latMin = 53.0, latStep = 0.01, nLat = 401,
        lonMin = 18.0, lonStep = 0.02, nLon = 451
    )
    private val world = GeoidGrid(
        assetName = GeoidMode.WORLD.assetFile,
        latMin = -90.0, latStep = 0.25, nLat = 721,
        lonMin = -180.0, lonStep = 0.25, nLon = 1441
    )
    @Volatile var mode: GeoidMode = GeoidMode.LIT20G
        private set
    @Volatile var lastError: String? = null
        private set

    private fun gridOf(m: GeoidMode) = if (m == GeoidMode.LIT20G) lit20g else world

    fun select(context: Context, m: GeoidMode, onReady: () -> Unit) {
        mode = m
        Thread {
            try {
                gridOf(m).load(context.applicationContext)
                lastError = null
            } catch (e: Exception) {
                lastError = "nepavyko įkelti ${m.assetFile} (turi būti app/src/main/assets/${m.assetFile}). Klaida: ${e.message}"
            }
            onReady()
        }.start()
    }
    val isReady: Boolean get() = gridOf(mode).isLoaded
    fun undulation(lat: Double, lon: Double): Double? = gridOf(mode).undulation(lat, lon)
}

// ============================================================
//  BAROMETRAS
// ============================================================

class Barometer(context: Context) : SensorEventListener {
    private val app = context.applicationContext
    private val sm = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
    private val prefs = app.getSharedPreferences("baro", Context.MODE_PRIVATE)
    val hasSensor: Boolean get() = sensor != null

    @Volatile var stationHpa: Double? = null
        private set
    @Volatile var mslpHpa: Double? = null
        private set
    @Volatile var mslpSource: String = "nėra (naudojamas 1013,25 hPa)"
        private set
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastFetch = 0L
    @Volatile private var fetching = false

    var offsetM: Double
        get() = prefs.getFloat("offset", 0f).toDouble()
        private set(v) { prefs.edit().putFloat("offset", v.toFloat()).apply() }

    fun start() { sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) } }
    fun stop() { sm.unregisterListener(this) }

    override fun onSensorChanged(event: SensorEvent) { stationHpa = event.values[0].toDouble() }
    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    fun altitude(): Double? {
        val p = stationHpa ?: return null
        return rawAltitude(p) + offsetM
    }

    private fun rawAltitude(p: Double): Double {
        val sea = mslpHpa ?: SensorManager.PRESSURE_STANDARD_ATMOSPHERE.toDouble()
        return SensorManager.getAltitude(sea.toFloat(), p.toFloat()).toDouble()
    }

    fun updateMslp(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        val moved = lastLat.isNaN() || abs(lat - lastLat) > 0.05 || abs(lon - lastLon) > 0.05
        val stale = now - lastFetch > 30 * 60_000L
        if (fetching || (!moved && !stale)) return
        fetching = true
        Thread {
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=pressure_msl"
                val cur = JSONObject(URL(url).readText()).getJSONObject("current")
                mslpHpa = cur.getDouble("pressure_msl")
                mslpSource = "Open-Meteo"
                lastLat = lat
                lastLon = lon
                lastFetch = now
            } catch (e: Exception) {
                mslpSource = "klaida: ${e.message}"
            } finally {
                fetching = false
            }
        }.start()
    }

    fun calibrateZero() {
        val p = stationHpa ?: return
        offsetM = -rawAltitude(p)
    }

    fun calibrateTo(targetM: Double) {
        val p = stationHpa ?: return
        offsetM = targetM - rawAltitude(p)
    }

    fun reset() { offsetM = 0.0 }
}

// ============================================================
//  PALYDOVAI, SPALVOS
// ============================================================

data class Sat(
    val const: String,
    val svid: Int,
    val cn0: Float,
    val elev: Float,
    val az: Float,
    val used: Boolean
)

fun constColor(c: String): Int = when (c) {
    "GPS" -> Color.rgb(37, 99, 235)
    "GAL" -> Color.rgb(22, 163, 74)
    "GLO" -> Color.rgb(234, 88, 12)
    "BDS" -> Color.rgb(220, 38, 38)
    "SBS" -> Color.rgb(107, 114, 128)
    "QZS" -> Color.rgb(147, 51, 234)
    else -> Color.DKGRAY
}

// ============================================================
//  DANGAUS ŽEMĖLAPIS
// ============================================================

class GnssView(context: Context) : View(context) {
    var sats: List<Sat> = emptyList()
        set(value) { field = value; invalidate() }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0F172A.toInt()
        textSize = 24f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val r0 = min(w, h) / 2f - 40f
        val cx = w / 2f
        val cy = h / 2f
        for (e in listOf(0f, 30f, 60f)) canvas.drawCircle(cx, cy, r0 * (90f - e) / 90f, ringPaint)
        canvas.drawLine(cx, cy - r0, cx, cy + r0, ringPaint)
        canvas.drawLine(cx - r0, cy, cx + r0, cy, ringPaint)
        canvas.drawText("N", cx - 8f, cy - r0 - 8f, textPaint)
        for (s in sats) {
            val r = r0 * (90f - s.elev.coerceIn(0f, 90f)) / 90f
            val a = Math.toRadians(s.az.toDouble())
            val x = cx + r * sin(a).toFloat()
            val y = cy - r * cos(a).toFloat()
            dotPaint.color = constColor(s.const)
            dotPaint.style = if (s.used) Paint.Style.FILL else Paint.Style.STROKE
            canvas.drawCircle(x, y, 16f, dotPaint)
            canvas.drawText(s.svid.toString(), x + 18f, y + 8f, textPaint)
        }
    }
}

// ============================================================
//  C/N0 LAIKO GRAFIKAS
// ============================================================

class CnoPlotView(context: Context) : View(context) {
    private val maxPts = 120
    private var total = 0
    private val series = LinkedHashMap<String, ArrayDeque<Float?>>()
    private val avgSeries = ArrayDeque<Float?>()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40334155; strokeWidth = 1f }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; strokeJoin = Paint.Join.ROUND }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.RED; pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F172A.toInt(); textSize = 22f }

    fun addSample(sats: List<Sat>, avgUsed: Float?) {
        total++
        val seen = sats.associate { "${it.const}|${it.svid}" to it.cn0 }
        val it = series.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            e.value.addLast(seen[e.key])
            if (e.value.size > maxPts) e.value.removeFirst()
            if (e.value.all { v -> v == null }) it.remove()
        }
        for ((k, v) in seen) {
            if (!series.containsKey(k)) {
                val dq = ArrayDeque<Float?>()
                repeat(min(total - 1, maxPts - 1)) { dq.addLast(null) }
                dq.addLast(v)
                series[k] = dq
            }
        }
        avgSeries.addLast(avgUsed)
        if (avgSeries.size > maxPts) avgSeries.removeFirst()
        invalidate()
    }

    fun summary(): String {
        if (series.isEmpty()) return "Duomenų dar nėra"
        val lines = mutableListOf<String>()
        for ((k, dq) in series) {
            val v = dq.filterNotNull()
            if (v.isEmpty()) continue
            lines.add("$k: min ${fx(v.minOrNull()?.toDouble() ?: 0.0)}, max ${fx(v.maxOrNull()?.toDouble() ?: 0.0)}, vid ${fx(v.average())}, mėginių ${v.size}")
        }
        val avgVals = avgSeries.filterNotNull()
        if (avgVals.isNotEmpty()) {
            lines.add("Naudojamų vidurkis: min ${fx(avgVals.minOrNull()?.toDouble() ?: 0.0)}, max ${fx(avgVals.maxOrNull()?.toDouble() ?: 0.0)}, vid ${fx(avgVals.average())}")
        }
        return lines.joinToString("\n")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val left = 50f
        val right = w - 20f
        val top = 20f
        val bottom = h - 40f
        fun xOf(i: Int) = left + i * (right - left) / (maxPts - 1)
        fun yOf(v: Float) = bottom - ((v.coerceIn(10f, 50f) - 10f) / 40f) * (bottom - top)

        for (db in 10..50 step 10) {
            val y = yOf(db.toFloat())
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText("$db", 6f, y + 8f, textPaint)
        }
        canvas.drawText("dB-Hz", 6f, top - 4f, textPaint)
        canvas.drawText("laikas →", right - 110f, bottom + 30f, textPaint)

        for ((k, dq) in series) {
            linePaint.color = constColor(k.substringBefore('|'))
            drawSeries(canvas, dq, ::xOf, ::yOf, linePaint, label = k.substringAfter('|'))
        }
        drawSeries(canvas, avgSeries, ::xOf, ::yOf, avgPaint, label = null)
    }

    private fun drawSeries(canvas: Canvas, dq: ArrayDeque<Float?>, xOf: (Int) -> Float, yOf: (Float) -> Float, paint: Paint, label: String?) {
        val start = maxPts - dq.size
        val path = Path()
        var pen = false
        var lastX = 0f
        var lastY = 0f
        var hasLast = false
        dq.forEachIndexed { j, v ->
            if (v == null) {
                pen = false
            } else {
                val x = xOf(start + j)
                val y = yOf(v)
                if (!pen) path.moveTo(x, y) else path.lineTo(x, y)
                pen = true
                lastX = x
                lastY = y
                hasLast = true
            }
        }
        canvas.drawPath(path, paint)
        if (label != null && hasLast) canvas.drawText(label, lastX + 4f, lastY + 6f, textPaint)
    }
}

// ============================================================
//  STUMDOMA LENTELĖ SU BURBULU
// ============================================================

class BubbleView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rnd = java.util.Random()
    private var t = 0f

    // avg – vidutinis C/N0 (spalvai ir silpnam signalui), peak – maksimalus C/N0 (ULTRA)
    private var avg: Float? = null
    private var peak: Float? = null

    private class Bolt(val path: Path, var life: Int)
    private class Dust(var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Int)

    private val bolts = ArrayList<Bolt>()
    private val dust = ArrayList<Dust>()

    fun setSignal(avgDb: Float?, peakDb: Float?) {
        avg = avgDb
        peak = peakDb
        invalidate()
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedFraction
            updateEffects()
            invalidate()
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    private fun isUltra() = (peak ?: 0f) > 45f
    private fun isWeak() = avg != null && avg!! < 20f

    private fun updateEffects() {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w / 2f; val cy = h / 2f
        val r = min(w, h) / 2f

        // Elektros kibirkštys (ULTRA)
        if (isUltra() && rnd.nextFloat() < 0.6f) {
            val p = Path()
            val a = rnd.nextFloat() * 2 * Math.PI.toFloat()
            var x = cx + cos(a) * r * 0.55f
            var y = cy + sin(a) * r * 0.55f
            p.moveTo(x, y)
            val steps = 4 + rnd.nextInt(3)
            for (i in 1..steps) {
                val dist = r * (0.55f + 0.45f * i / steps)
                val ja = a + (rnd.nextFloat() - 0.5f) * 0.9f
                x = cx + cos(ja) * dist
                y = cy + sin(ja) * dist
                p.lineTo(x, y)
            }
            bolts.add(Bolt(p, 3 + rnd.nextInt(3)))
        }
        bolts.forEach { it.life-- }
        bolts.removeAll { it.life <= 0 }

        // Išsklaidyti dulkės (silpnas signalas)
        if (isWeak() && rnd.nextFloat() < 0.5f) {
            val a = rnd.nextFloat() * 2 * Math.PI.toFloat()
            val sp = r * 0.02f + rnd.nextFloat() * r * 0.02f
            dust.add(Dust(cx + cos(a) * r * 0.2f, cy + sin(a) * r * 0.2f, cos(a) * sp, sin(a) * sp, 30))
        }
        dust.forEach { it.x += it.vx; it.y += it.vy; it.life-- }
        dust.removeAll { it.life <= 0 }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val r = min(w, h) / 2f
        if (r <= 0f) return

        val ultra = isUltra()
        val weak = isWeak()
        val base = when {
            avg == null -> SIG_BLUE
            weak -> 0xFF1F2937.toInt()
            ultra -> SIG_YELLOW
            else -> signalColor(avg!!)
        }

        // Pulsuojantis švytėjimas
        val coreR = r * 0.55f
        val pulseR = r * (0.85f + 0.12f * t)
        paint.shader = RadialGradient(cx, cy, pulseR, withAlpha(base, 0.6f), 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, pulseR, paint)
        paint.shader = null

        // Branduolys
        paint.color = base
        canvas.drawCircle(cx, cy, coreR, paint)

        if (ultra) {
            // Geltonas švytėjimas + baltos kibirkštys
            boltPaint.color = SIG_YELLOW
            boltPaint.alpha = 220
            boltPaint.strokeWidth = 3f * density
            bolts.forEach { canvas.drawPath(it.path, boltPaint) }
            boltPaint.color = 0xFFFFFFFF.toInt()
            boltPaint.strokeWidth = 1.2f * density
            bolts.forEach { canvas.drawPath(it.path, boltPaint) }
        }

        if (weak) {
            drawSkullCloud(canvas, cx, cy, coreR)
            // Išsisklaidantys dulkės
            paint.color = 0xFF1F2937.toInt()
            dust.forEach {
                paint.alpha = (255 * it.life / 30f).toInt().coerceIn(0, 255)
                canvas.drawCircle(it.x, it.y, 2.2f * density, paint)
            }
            paint.alpha = 255
        }
    }

    /** Tamsi debesies formos kaukolė (nupiešta, be paveikslėlio). */
    private fun drawSkullCloud(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = 0xFF111827.toInt()
        // Debesis: keli persidengiantys apskritimai
        canvas.drawCircle(cx - r * 0.42f, cy + r * 0.05f, r * 0.42f, paint)
        canvas.drawCircle(cx + r * 0.42f, cy + r * 0.05f, r * 0.42f, paint)
        canvas.drawCircle(cx, cy - r * 0.18f, r * 0.55f, paint)
        canvas.drawCircle(cx - r * 0.22f, cy + r * 0.32f, r * 0.38f, paint)
        canvas.drawCircle(cx + r * 0.22f, cy + r * 0.32f, r * 0.38f, paint)
        canvas.drawRect(cx - r * 0.5f, cy + r * 0.05f, cx + r * 0.5f, cy + r * 0.36f, paint)
        // Kaukolės akys ir dantys
        paint.color = 0xFFE5E7EB.toInt()
        canvas.drawCircle(cx - r * 0.22f, cy - r * 0.02f, r * 0.16f, paint)
        canvas.drawCircle(cx + r * 0.22f, cy - r * 0.02f, r * 0.16f, paint)
        paint.color = 0xFF111827.toInt()
        canvas.drawRect(cx - r * 0.2f, cy + r * 0.2f, cx + r * 0.2f, cy + r * 0.24f, paint)
        canvas.drawRect(cx - r * 0.05f, cy + r * 0.2f, cx + r * 0.05f, cy + r * 0.34f, paint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
}

// ============================================================
//  3D BURBULAS (OpenGL ES 2.0, GLSurfaceView)
// ============================================================

private class GlBolt(val xy: FloatArray, var life: Int)
private class GlDust(var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Int)

/** Permatomas 3D burbulas: šviesa, spalva pagal signalą, ULTRA kibirkštys, silpno signalo kaukolė. */
class BubbleGL(context: Context) : GLSurfaceView(context) {
    private val renderer = BubbleRenderer(context.applicationContext, resources.displayMetrics.density)

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        onResume()
    }

    fun setSignal(avgDb: Float?, peakDb: Float?) = renderer.setSignal(avgDb, peakDb)
}

private class BubbleRenderer(private val appCtx: Context, private val density: Float) : GLSurfaceView.Renderer {

    @Volatile private var avg: Float? = null
    @Volatile private var peak: Float? = null
    fun setSignal(a: Float?, p: Float?) { avg = a; peak = p }

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)

    private var sphereProg = 0
    private var flatProg = 0
    private var dotProg = 0
    private var texProg = 0
    private var texId = 0
    private var sphereBuf: FloatBuffer? = null
    private var sphereCount = 0
    private var quadPos: FloatBuffer? = null
    private var quadUv: FloatBuffer? = null

    private val bolts = ArrayList<GlBolt>()
    private val dust = ArrayList<GlDust>()
    private val rnd = java.util.Random()
    private var frame = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        sphereProg = program(SPHERE_VS, SPHERE_FS)
        flatProg = program(FLAT_VS, FLAT_FS)
        dotProg = program(FLAT_VS, DOT_FS)
        texProg = program(TEX_VS, TEX_FS)

        val sph = buildSphere(24, 32)
        sphereCount = sph.size / 3
        sphereBuf = floatBuf(sph)

        quadPos = floatBuf(floatArrayOf(
            -0.9f, -0.9f, 1.0f,  0.9f, -0.9f, 1.0f,
            -0.9f,  0.9f, 1.0f,  0.9f,  0.9f, 1.0f))
        quadUv = floatBuf(floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f))

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val bmp = skullBitmap()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        // Kamera žiūri į rutulį iš z=4; matomas plotis ±0.6 ties artimąja plokštuma
        Matrix.frustumM(proj, 0, -0.6f, 0.6f, -0.6f, 0.6f, 2f, 10f)
        Matrix.setLookAtM(view, 0, 0f, 0f, 4f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        frame++
        updateEffects()

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val a = avg
        val pk = peak
        val weak = a != null && a < 20f
        val ultra = (pk ?: 0f) > 45f
        val col = when {
            a == null -> floatArrayOf(0.23f, 0.51f, 0.96f)
            weak -> floatArrayOf(0.12f, 0.16f, 0.22f)
            ultra -> floatArrayOf(0.98f, 0.80f, 0.08f)
            else -> rgb(signalColor(a))
        }

        // 1) Rutulys su šviesa (šviesos kryptis lėtai sukasi)
        val pulse = 0.9f + 0.03f * kotlin.math.sin(frame * 0.05).toFloat()
        Matrix.setIdentityM(model, 0)
        Matrix.scaleM(model, 0, pulse, pulse, pulse)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        val ang = frame * 0.02
        GLES20.glUseProgram(sphereProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(sphereProg, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(sphereProg, "uLight"),
            (0.6 * kotlin.math.cos(ang)).toFloat(), 0.5f, (0.6 * kotlin.math.sin(ang) + 0.5).toFloat())
        GLES20.glUniform3f(GLES20.glGetUniformLocation(sphereProg, "uColor"), col[0], col[1], col[2])
        GLES20.glUniform1f(GLES20.glGetUniformLocation(sphereProg, "uAlpha"), 0.95f)
        bindAttrib(sphereProg, "aPos", sphereBuf!!, 3)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, sphereCount)

        // 2) ULTRA: elektros kibirkštys (geltonas švytėjimas + baltas branduolys)
        if (ultra && bolts.isNotEmpty()) {
            drawBoltStrips(0.022f, floatArrayOf(1f, 0.85f, 0.2f, 0.9f))
            drawBoltStrips(0.008f, floatArrayOf(1f, 1f, 1f, 1f))
        }

        // 3) Silpnas signalas: kaukolė (tekstūra) ir išsisklaidančios dulkės
        if (weak) {
            GLES20.glUseProgram(texProg)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(texProg, "uMVP"), 1, false, vp, 0)
            bindAttrib(texProg, "aPos", quadPos!!, 3)
            bindAttrib(texProg, "aUV", quadUv!!, 2)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(texProg, "uTex"), 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glUseProgram(dotProg)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(dotProg, "uMVP"), 1, false, vp, 0)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(dotProg, "uPoint"), 10f * density)
            for (d in dust) {
                val alpha = d.life / 40f
                val buf = floatBuf(floatArrayOf(d.x, d.y, 1.02f))
                GLES20.glUniform4f(GLES20.glGetUniformLocation(dotProg, "uColor"), 0.12f, 0.16f, 0.22f, alpha)
                bindAttrib(dotProg, "aPos", buf, 3)
                GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
            }
        }
    }

    private fun drawBoltStrips(width: Float, color: FloatArray) {
        val verts = ArrayList<Float>()
        for (b in bolts) {
            val xy = b.xy
            var i = 0
            while (i + 3 < xy.size) {
                val x0 = xy[i]; val y0 = xy[i + 1]; val x1 = xy[i + 2]; val y1 = xy[i + 3]
                val dx = x1 - x0; val dy = y1 - y0
                val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
                val px = -dy / len * width; val py = dx / len * width
                val z = 1.02f
                val quad = floatArrayOf(
                    x0 + px, y0 + py, z, x0 - px, y0 - py, z, x1 + px, y1 + py, z,
                    x0 - px, y0 - py, z, x1 - px, y1 - py, z, x1 + px, y1 + py, z)
                for (v in quad) verts.add(v)
                i += 2
            }
        }
        if (verts.isEmpty()) return
        val buf = floatBuf(verts.toFloatArray())
        GLES20.glUseProgram(flatProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(flatProg, "uMVP"), 1, false, vp, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(flatProg, "uColor"), color[0], color[1], color[2], color[3])
        bindAttrib(flatProg, "aPos", buf, 3)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts.size / 3)
    }

    private fun updateEffects() {
        if ((peak ?: 0f) > 45f && rnd.nextFloat() < 0.6f) {
            // Kibirkšties kelias: lūžinėjanti linija nuo rutulio krašto į išorę
            val a = rnd.nextFloat() * 2f * Math.PI.toFloat()
            // Žaibas spinduliu į išorę, lūžinėja, bet lieka apskritimo viduje (be kvadratinių kraštų)
            val steps = 6 + rnd.nextInt(4)
            val xy = FloatArray((steps + 1) * 2)
            val r0 = 0.5f
            val r1 = 0.9f
            var x = kotlin.math.cos(a) * r0
            var y = kotlin.math.sin(a) * r0
            xy[0] = x; xy[1] = y
            var ja = a
            for (i in 1..steps) {
                val f = i.toFloat() / steps
                val dist = r0 + (r1 - r0) * f
                ja += (rnd.nextFloat() - 0.5f) * 0.35f
                x = kotlin.math.cos(ja) * dist
                y = kotlin.math.sin(ja) * dist
                xy[i * 2] = x; xy[i * 2 + 1] = y
            }
            bolts.add(GlBolt(xy, 3 + rnd.nextInt(3)))
        }
        bolts.forEach { it.life-- }
        bolts.removeAll { it.life <= 0 }

        if ((avg ?: 99f) < 20f && rnd.nextFloat() < 0.5f) {
            val a = rnd.nextFloat() * 2f * Math.PI.toFloat()
            val sp = 0.012f + rnd.nextFloat() * 0.012f
            dust.add(GlDust(kotlin.math.cos(a) * 0.5f, kotlin.math.sin(a) * 0.5f,
                kotlin.math.cos(a) * sp, kotlin.math.sin(a) * sp, 40))
        }
        dust.forEach { it.x += it.vx; it.y += it.vy; it.life-- }
        dust.removeAll { it.life <= 0 }
    }

    private fun bindAttrib(prog: Int, name: String, buf: FloatBuffer, size: Int) {
        val loc = GLES20.glGetAttribLocation(prog, name)
        if (loc < 0) return
        GLES20.glEnableVertexAttribArray(loc)
        buf.position(0)
        GLES20.glVertexAttribPointer(loc, size, GLES20.GL_FLOAT, false, 0, buf)
    }

    private fun rgb(c: Int) = floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)

    private fun floatBuf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }

    private fun buildSphere(stacks: Int, slices: Int): FloatArray {
        val out = ArrayList<Float>()
        fun v(st: Int, sl: Int): FloatArray {
            val th = Math.PI * st / stacks
            val ph = 2 * Math.PI * sl / slices
            return floatArrayOf(
                (kotlin.math.sin(th) * kotlin.math.cos(ph)).toFloat(),
                kotlin.math.cos(th).toFloat(),
                (kotlin.math.sin(th) * kotlin.math.sin(ph)).toFloat())
        }
        for (st in 0 until stacks) for (sl in 0 until slices) {
            val a = v(st, sl); val b = v(st + 1, sl); val c = v(st + 1, sl + 1); val d = v(st, sl + 1)
            for (p in arrayOf(a, b, c, a, c, d)) for (x in p) out.add(x)
        }
        return out.toFloatArray()
    }

    private fun skullBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = 100f; val cx = 128f; val cy = 128f
        p.color = 0xFF111827.toInt()
        c.drawCircle(cx - r * 0.42f, cy + r * 0.05f, r * 0.42f, p)
        c.drawCircle(cx + r * 0.42f, cy + r * 0.05f, r * 0.42f, p)
        c.drawCircle(cx, cy - r * 0.18f, r * 0.55f, p)
        c.drawCircle(cx - r * 0.22f, cy + r * 0.32f, r * 0.38f, p)
        c.drawCircle(cx + r * 0.22f, cy + r * 0.32f, r * 0.38f, p)
        c.drawRect(cx - r * 0.5f, cy + r * 0.05f, cx + r * 0.5f, cy + r * 0.36f, p)
        p.color = 0xFFE5E7EB.toInt()
        c.drawCircle(cx - r * 0.22f, cy - r * 0.02f, r * 0.16f, p)
        c.drawCircle(cx + r * 0.22f, cy - r * 0.02f, r * 0.16f, p)
        p.color = 0xFF111827.toInt()
        c.drawRect(cx - r * 0.2f, cy + r * 0.2f, cx + r * 0.2f, cy + r * 0.24f, p)
        c.drawRect(cx - r * 0.05f, cy + r * 0.2f, cx + r * 0.05f, cy + r * 0.34f, p)
        return bmp
    }

    private fun program(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, v)
        GLES20.glAttachShader(prog, f)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        return sh
    }

    companion object {
        private const val SPHERE_VS = """
            attribute vec3 aPos;
            uniform mat4 uMVP;
            varying vec3 vN;
            void main() { vN = aPos; gl_Position = uMVP * vec4(aPos, 1.0); }
        """
        private const val SPHERE_FS = """
            precision mediump float;
            varying vec3 vN;
            uniform vec3 uLight;
            uniform vec3 uColor;
            uniform float uAlpha;
            void main() {
                vec3 n = normalize(vN);
                vec3 l = normalize(uLight);
                float d = max(dot(n, l), 0.0);
                vec3 h = normalize(l + vec3(0.0, 0.0, 1.0));
                float spec = pow(max(dot(n, h), 0.0), 48.0);
                float rim = pow(1.0 - abs(n.z), 2.5);
                vec3 col = uColor * (0.22 + 0.78 * d) + uColor * rim * 0.5 + vec3(spec) * 0.9;
                gl_FragColor = vec4(col, uAlpha);
            }
        """
        private const val FLAT_VS = """
            attribute vec3 aPos;
            uniform mat4 uMVP;
            uniform float uPoint;
            void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uPoint; }
        """
        private const val FLAT_FS = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """
        private const val DOT_FS = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                vec2 c = gl_PointCoord - vec2(0.5);
                if (length(c) > 0.5) discard;
                gl_FragColor = uColor;
            }
        """
        private const val TEX_VS = """
            attribute vec3 aPos;
            attribute vec2 aUV;
            uniform mat4 uMVP;
            varying vec2 vUV;
            void main() { gl_Position = uMVP * vec4(aPos, 1.0); vUV = aUV; }
        """
        private const val TEX_FS = """
            precision mediump float;
            uniform sampler2D uTex;
            varying vec2 vUV;
            void main() { gl_FragColor = texture2D(uTex, vUV); }
        """
    }
}

// ============================================================
//  3D GAUBLYS (dangaus skliautas, sensoriais suderintas)
// ============================================================

/**
 * 3D gaublys žiūrint iš išorės: virš horizonto – pulsuojantis GPS kupolas,
 * žemiau horizonto – permatoma struktūra. Palydovai (geras signalas) žybsi kaip žvaigždės.
 * Gaublys sukasi kartu su planšete (rotacijos vektoriaus jutiklis).
 */
class SkyDomeGL(context: Context) : GLSurfaceView(context), SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val renderer = GlobeRenderer(context.applicationContext, resources.displayMetrics.density)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    // Rankinis sukimas pirštu: sensoriai sustabdomi, po minutės be liečiant – grįžtama
    private var manual = false
    private var baseRot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private var lastRaw = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private var yawD = 0f
    private var pitchD = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val resumeSensors = Runnable { manual = false }
    var onDoubleTapCb: (() -> Unit)? = null
    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean { onDoubleTapCb?.invoke(); return true }
    })

    // Perkėlimas: dviem pirštais tempti gaublį po ekraną; vienu pirštu – sukti
    var onMove: ((Float, Float) -> Unit)? = null
    private var moveMode = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        // Permatomas paviršius: matomas tik pats gaublys, be juodo kvadrato
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setSats(list: List<Sat>) = renderer.setSats(list)

    fun start() {
        if (running) return
        running = true
        onResume()
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        running = false
        manual = false
        handler.removeCallbacks(resumeSensors)
        sm.unregisterListener(this)
        onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val raw = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(raw, event.values)
        val out = FloatArray(9)
        when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, out)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
            else -> raw.copyInto(out)
        }
        lastRaw = out
        if (!manual) renderer.setRotation(out)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gd.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(resumeSensors)
                if (!manual) {
                    baseRot = lastRaw.copyOf()
                    yawD = 0f
                    pitchD = 0f
                    manual = true
                }
                moveMode = false
                lastX = e.x
                lastY = e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    // Du pirštai – perkėlimo režimas
                    moveMode = true
                    lastRawX = (e.getRawX(0) + e.getRawX(1)) / 2f
                    lastRawY = (e.getRawY(0) + e.getRawY(1)) / 2f
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (moveMode && e.pointerCount >= 2) {
                    val cx = (e.getRawX(0) + e.getRawX(1)) / 2f
                    val cy = (e.getRawY(0) + e.getRawY(1)) / 2f
                    onMove?.invoke(cx - lastRawX, cy - lastRawY)
                    lastRawX = cx
                    lastRawY = cy
                } else if (!moveMode) {
                    // Vienas pirštas – sukimas aplink savo ašį
                    yawD += (e.x - lastX) * 0.4f
                    pitchD = (pitchD + (e.y - lastY) * 0.4f).coerceIn(-85f, 85f)
                    renderer.setRotation(composeManual())
                }
                lastX = e.x
                lastY = e.y
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Lieka vienas pirštas – tęsiame sukimą be šuolio
                val remaining = if (e.actionIndex == 0) 1 else 0
                moveMode = false
                if (remaining < e.pointerCount) {
                    lastX = e.getX(remaining)
                    lastY = e.getY(remaining)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                moveMode = false
                // Po 60 s be prisilietimo vėl įjungiami sensoriai
                handler.removeCallbacks(resumeSensors)
                handler.postDelayed(resumeSensors, 60_000L)
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun composeManual(): FloatArray {
        val a = Math.toRadians(yawD.toDouble())
        val b = Math.toRadians(pitchD.toDouble())
        val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
        val cb = cos(b).toFloat(); val sb = sin(b).toFloat()
        val rz = floatArrayOf(ca, -sa, 0f, sa, ca, 0f, 0f, 0f, 1f)
        val rx = floatArrayOf(1f, 0f, 0f, 0f, cb, -sb, 0f, sb, cb)
        return mul3(rz, mul3(rx, baseRot))
    }

    private fun mul3(a: FloatArray, b: FloatArray): FloatArray {
        val o = FloatArray(9)
        for (i in 0..2) for (j in 0..2) {
            var sum = 0f
            for (k in 0..2) sum += a[i * 3 + k] * b[k * 3 + j]
            o[i * 3 + j] = sum
        }
        return o
    }
}

/** Plūduriuojantis gaublio langas: viršutinė juosta stumdo, gaublys sukasi pirštu. */
class FloatingSky(context: Context) : FrameLayout(context) {

    val dome = SkyDomeGL(context)
    private val density = resources.displayMetrics.density
    private val size = (300 * density).toInt()

    init {
        addView(dome, FrameLayout.LayoutParams(size, size))
        dome.onMove = { dx, dy ->
            translationX += dx
            translationY += dy
            constrain()
        }
    }

    /** Bent 10 % lango lieka matoma. */
    private fun constrain() {
        val pw = (parent as? View)?.width?.toFloat() ?: return
        val ph = (parent as? View)?.height?.toFloat() ?: return
        val w = size.toFloat()
        translationX = translationX.coerceIn(-w * 0.9f, pw - w * 0.1f)
        translationY = translationY.coerceIn(-w * 0.9f, ph - w * 0.1f)
    }
}

private class GlobeRenderer(private val appCtx: Context, private val density: Float) : GLSurfaceView.Renderer {

    @Volatile private var rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    @Volatile private var sats: List<Sat> = emptyList()
    fun setRotation(r: FloatArray) { rot = r.copyOf() }
    fun setSats(l: List<Sat>) { sats = l }

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val pv = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)
    private val t0 = System.nanoTime()

    private var domeProg = 0; private var lowProg = 0; private var colorProg = 0
    private var dotProg = 0; private var texProg = 0
    private var upperBuf: FloatBuffer? = null; private var upperN = 0
    private var lowerBuf: FloatBuffer? = null; private var lowerN = 0
    private var gridBuf: FloatBuffer? = null; private var gridN = 0
    private var horizonBuf: FloatBuffer? = null; private var horizonN = 0
    private var starTex = 0
    private val labelTex = HashMap<String, Int>()
    private val prnTex = HashMap<String, Int>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)

        domeProg = program(DOME_VS, DOME_FS)
        lowProg = program(DOME_VS, LOW_FS)
        colorProg = program(COLOR_VS, COLOR_FS)
        dotProg = program(COLOR_VS, DOT_FS)
        texProg = program(TEX_VS, TEX_FS)

        val up = meshSphere(0.0, 90.0, 18, 72, 1.0)
        upperBuf = floatBuf(up); upperN = up.size / 3
        val low = meshSphere(-90.0, 0.0, 12, 72, 1.0)
        lowerBuf = floatBuf(low); lowerN = low.size / 3
        val grid = buildGrid(1.004)
        gridBuf = floatBuf(grid); gridN = grid.size / 3
        val hz = buildHorizon(1.006)
        horizonBuf = floatBuf(hz); horizonN = hz.size / 3

        starTex = makeStarTexture()
        for (d in 0 until 360 step 30) labelTex[labelOf(d)] = makeTexture(labelOf(d), 0xFFFFFFFF.toInt(), 96)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        val aspect = if (h == 0) 1f else w.toFloat() / h
        Matrix.perspectiveM(proj, 0, 40f, aspect, 0.1f, 20f)
        Matrix.setIdentityM(view, 0)
        Matrix.translateM(view, 0, 0f, 0f, -3f)           // kamera išorėje, žiūri į gaublį
        Matrix.multiplyMM(pv, 0, proj, 0, view, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val t = (System.nanoTime() - t0) / 1e9f

        // Pasaulis -> planšetė (sensorių suderinimas)
        val r = rot
        java.util.Arrays.fill(mv, 0f)
        for (c in 0..2) for (rr in 0..2) mv[c * 4 + rr] = r[c * 3 + rr]
        mv[15] = 1f
        Matrix.multiplyMM(mvp, 0, pv, 0, mv, 0)
        // Kameros kryptis pasaulio koordinatėse (planšetės +Z)
        val eye = floatArrayOf(r[2], r[5], r[8])

        // Signalo kokybė -> pulsavimo stiprumas
        val used = sats.filter { it.used }
        val avgCn0 = if (used.isNotEmpty()) used.map { it.cn0 }.average().toFloat() else 0f
        val beat = ((avgCn0 - 15f) / 25f).coerceIn(0.2f, 1f)

        // 1) Virš horizonto – pulsuojantis GPS kupolas
        GLES20.glDepthMask(true)
        GLES20.glUseProgram(domeProg)
        setMat(domeProg, "uMVP", mvp)
        setVec3(domeProg, "uLight", 0.4f, 0.5f, 0.8f)
        setVec3(domeProg, "uEye", eye[0], eye[1], eye[2])
        setFloat(domeProg, "uTime", t)
        setFloat(domeProg, "uBeat", beat)
        setVec3(domeProg, "uTint", 0.2f + 0.7f * beat, 0.6f + 0.3f * beat, 1.0f - 0.6f * beat)
        bindPos(domeProg, upperBuf!!)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, upperN)

        // 2) Žemiau horizonto – permatoma struktūra
        GLES20.glDepthMask(false)
        GLES20.glUseProgram(lowProg)
        setMat(lowProg, "uMVP", mvp)
        setVec3(lowProg, "uEye", eye[0], eye[1], eye[2])
        setVec4(lowProg, "uColor", 0.35f, 0.4f, 0.5f, 0.14f)
        bindPos(lowProg, lowerBuf!!)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, lowerN)
        GLES20.glDepthMask(true)

        // 3) Laipsnių tinklelis
        GLES20.glUseProgram(colorProg)
        setMat(colorProg, "uMVP", mvp)
        setVec4(colorProg, "uColor", 0.6f, 0.7f, 0.85f, 0.22f)
        setFloat(colorProg, "uPoint", 1f)
        bindPos(colorProg, gridBuf!!)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, gridN)

        // 4) Horizontas – pulsuojantis auksinis žiedas
        val hzAlpha = 0.6f + 0.4f * kotlin.math.sin(t * 2.5f)
        setVec4(colorProg, "uColor", 0.95f, 0.72f, 0.3f, hzAlpha)
        bindPos(colorProg, horizonBuf!!)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, horizonN)

        // Žymos ir palydovai piešiami be gylio testo (visada matomi priekyje)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        // 5) Laipsnių žymos ant horizonto
        for (d in 0 until 360 step 30) {
            val tex = labelTex[labelOf(d)] ?: continue
            val a = Math.toRadians(d.toDouble())
            val c = floatArrayOf((1.04 * sin(a)).toFloat(), (1.04 * cos(a)).toFloat(), 0f)
            if (dot3(c, eye) < 0.05f) continue
            val col = when (d) { 0 -> floatArrayOf(0.94f, 0.27f, 0.27f); 90, 180, 270 -> floatArrayOf(0.98f, 0.8f, 0.08f); else -> floatArrayOf(0.85f, 0.87f, 0.9f) }
            billboard(c, eye, 0.1f, 0.05f, tex, col[0], col[1], col[2], 0.95f)
        }

        // 6) Palydovai: geras signalas žybsi kaip žvaigždės
        for (sat in sats) {
            val el = Math.toRadians(sat.elev.toDouble().coerceIn(-10.0, 90.0))
            val az = Math.toRadians(sat.az.toDouble())
            val p = floatArrayOf(
                (0.98 * cos(el) * sin(az)).toFloat(),
                (0.98 * cos(el) * cos(az)).toFloat(),
                (0.98 * sin(el)).toFloat())
            if (dot3(p, eye) < -0.05f) continue
            val col = constRgb(sat.const)
            val usedK = if (sat.used) 1f else 0.4f
            val phase = sat.svid * 0.7f
            when {
                sat.cn0 >= 30f -> {
                    val tw = kotlin.math.sin(t * 4f + phase)
                    val size = 0.075f * (1f + 0.35f * tw)
                    val alpha = (0.75f + 0.25f * tw) * usedK
                    billboard(p, eye, size, size, starTex, col[0], col[1], col[2], alpha)
                    val pn = prnTex.getOrPut(sat.const + sat.svid) { makeTexture("${sat.svid}", 0xFFFFFFFF.toInt(), 64) }
                    billboard(floatArrayOf(p[0], p[1], p[2] + 0.07f), eye, 0.05f, 0.025f, pn, col[0], col[1], col[2], 0.9f * usedK)
                }
                sat.cn0 >= 20f -> {
                    billboard(p, eye, 0.05f, 0.05f, starTex, col[0], col[1], col[2], 0.6f * usedK)
                }
                else -> {
                    drawPoint(p, col, 0.3f * usedK, 6f * density)
                }
            }
        }

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun labelOf(d: Int): String = when (d) { 0 -> "N"; 90 -> "E"; 180 -> "S"; 270 -> "W"; else -> d.toString() }

    private fun dot3(a: FloatArray, b: FloatArray): Float = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun drawPoint(p: FloatArray, col: FloatArray, alpha: Float, size: Float) {
        GLES20.glUseProgram(dotProg)
        setMat(dotProg, "uMVP", mvp)
        setVec4(dotProg, "uColor", col[0], col[1], col[2], alpha)
        setFloat(dotProg, "uPoint", size)
        bindPos(dotProg, floatBuf(p))
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
    }

    /** Plokštuma, kuri visada atsisuka į kamerą (centras c, pusplotis w, pusaukštis h). */
    private fun billboard(c: FloatArray, eye: FloatArray, w: Float, h: Float, tex: Int, cr: Float, cg: Float, cb: Float, ca: Float) {
        var nx = eye[0] - c[0]; var ny = eye[1] - c[1]; var nz = eye[2] - c[2]
        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
        nx /= nl; ny /= nl; nz /= nl
        // pagalbinė "aukštis": (0,0,1), o ties poliais – (0,1,0)
        val ax = if (kotlin.math.abs(nz) > 0.95f) 0f else 0f
        val ay = if (kotlin.math.abs(nz) > 0.95f) 1f else 0f
        val az = if (kotlin.math.abs(nz) > 0.95f) 0f else 1f
        // right = up0 x n
        var rx = ay * nz - az * ny
        var ry = az * nx - ax * nz
        var rz = ax * ny - ay * nx
        val rl = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-6f)
        rx /= rl; ry /= rl; rz /= rl
        // up = n x right
        val ux = ny * rz - nz * ry
        val uy = nz * rx - nx * rz
        val uz = nx * ry - ny * rx
        val pos = FloatArray(12)
        val cs = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f))
        for (i in 0..3) {
            val sx = cs[i][0] * w; val sy = cs[i][1] * h
            pos[i * 3] = c[0] + rx * sx + ux * sy
            pos[i * 3 + 1] = c[1] + ry * sx + uy * sy
            pos[i * 3 + 2] = c[2] + rz * sx + uz * sy
        }
        val uv = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        GLES20.glUseProgram(texProg)
        setMat(texProg, "uMVP", mvp)
        setVec4(texProg, "uColor", cr, cg, cb, ca)
        val lp = GLES20.glGetAttribLocation(texProg, "aPos")
        val lu = GLES20.glGetAttribLocation(texProg, "aUV")
        GLES20.glEnableVertexAttribArray(lp)
        GLES20.glEnableVertexAttribArray(lu)
        val bp = floatBuf(pos); val bu = floatBuf(uv)
        GLES20.glVertexAttribPointer(lp, 3, GLES20.GL_FLOAT, false, 0, bp)
        GLES20.glVertexAttribPointer(lu, 2, GLES20.GL_FLOAT, false, 0, bu)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(texProg, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun makeStarTexture(): Int {
        val size = 128
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(size / 2f, size / 2f, size / 2f,
                intArrayOf(0xFFFFFFFF.toInt(), 0x88FFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(size / 2f, size / 2f, size / 2f, glow)
        val ray = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAAFFFFFF.toInt()
            strokeWidth = 3f
        }
        c.drawLine(size / 2f, 4f, size / 2f, size - 4f, ray)
        c.drawLine(4f, size / 2f, size - 4f, size / 2f, ray)
        return uploadTexture(bmp)
    }

    private fun makeTexture(text: String, color: Int, sizePx: Int): Int {
        val w = sizePx * 2; val h = sizePx
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = h * 0.7f
            textAlign = Paint.Align.CENTER
        }
        c.drawText(text, w / 2f, h * 0.75f, p)
        return uploadTexture(bmp)
    }

    private fun uploadTexture(bmp: Bitmap): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        bmp.recycle()
        return ids[0]
    }

    private fun constRgb(c: String): FloatArray = when (c) {
        "GPS" -> floatArrayOf(0.35f, 0.6f, 1f)
        "GAL" -> floatArrayOf(0.3f, 1f, 0.5f)
        "GLO" -> floatArrayOf(1f, 0.6f, 0.2f)
        "BDS" -> floatArrayOf(1f, 0.35f, 0.35f)
        "SBS" -> floatArrayOf(0.7f, 0.72f, 0.78f)
        else -> floatArrayOf(1f, 1f, 1f)
    }

    private fun dirVec(elDeg: Double, azDeg: Double, radius: Double): DoubleArray {
        val el = Math.toRadians(elDeg); val az = Math.toRadians(azDeg)
        return doubleArrayOf(radius * cos(el) * sin(az), radius * cos(el) * cos(az), radius * sin(el))
    }

    /** Sferos dalis tarp elevacijų e0..e1 (laipsniais). */
    private fun meshSphere(e0: Double, e1: Double, stacks: Int, slices: Int, radius: Double): FloatArray {
        val out = ArrayList<Float>()
        fun v(st: Int, sl: Int): DoubleArray = dirVec(e0 + (e1 - e0) * st / stacks, 360.0 * sl / slices, radius)
        for (st in 0 until stacks) for (sl in 0 until slices) {
            val a = v(st, sl); val b = v(st + 1, sl); val c = v(st + 1, sl + 1); val d = v(st, sl + 1)
            for (q in arrayOf(a, b, c, a, c, d)) for (x in q) out.add(x.toFloat())
        }
        return out.toFloatArray()
    }

    private fun buildGrid(radius: Double): FloatArray {
        val out = ArrayList<Float>()
        fun seg(a: DoubleArray, b: DoubleArray) { for (x in a) out.add(x.toFloat()); for (x in b) out.add(x.toFloat()) }
        for (k in -4..4) {
            val el = k * 15.0
            for (i in 0 until 72) seg(dirVec(el, i * 5.0, radius), dirVec(el, (i + 1) * 5.0, radius))
        }
        for (az in 0 until 360 step 30) {
            for (i in -18 until 18) seg(dirVec(i * 5.0, az.toDouble(), radius), dirVec((i + 1) * 5.0, az.toDouble(), radius))
        }
        return out.toFloatArray()
    }

    private fun buildHorizon(radius: Double): FloatArray {
        val out = ArrayList<Float>()
        val n = 180
        for (i in 0 until n) {
            val a0 = 2 * Math.PI * i / n; val a1 = 2 * Math.PI * (i + 1) / n
            val ro = radius; val ri = radius * 0.985
            val p00 = doubleArrayOf(ro * cos(a0), ro * sin(a0), 0.0)
            val p01 = doubleArrayOf(ri * cos(a0), ri * sin(a0), 0.0)
            val p10 = doubleArrayOf(ro * cos(a1), ro * sin(a1), 0.0)
            val p11 = doubleArrayOf(ri * cos(a1), ri * sin(a1), 0.0)
            for (q in arrayOf(p00, p10, p11, p00, p11, p01)) for (x in q) out.add(x.toFloat())
        }
        return out.toFloatArray()
    }

    private fun bindPos(prog: Int, buf: FloatBuffer) {
        val loc = GLES20.glGetAttribLocation(prog, "aPos")
        GLES20.glEnableVertexAttribArray(loc)
        buf.position(0)
        GLES20.glVertexAttribPointer(loc, 3, GLES20.GL_FLOAT, false, 0, buf)
    }

    private fun setMat(prog: Int, name: String, m: FloatArray) =
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, name), 1, false, m, 0)
    private fun setVec3(prog: Int, name: String, x: Float, y: Float, z: Float) =
        GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, name), x, y, z)
    private fun setVec4(prog: Int, name: String, x: Float, y: Float, z: Float, w: Float) =
        GLES20.glUniform4f(GLES20.glGetUniformLocation(prog, name), x, y, z, w)
    private fun setFloat(prog: Int, name: String, v: Float) =
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, name), v)

    private fun floatBuf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }

    private fun program(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, v)
        GLES20.glAttachShader(prog, f)
        GLES20.glLinkProgram(prog)
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES20.glCreateShader(type)
        GLES20.glShaderSource(sh, src)
        GLES20.glCompileShader(sh)
        return sh
    }

    companion object {
        private const val DOME_VS = """
            attribute vec3 aPos;
            uniform mat4 uMVP;
            varying vec3 vN;
            varying float vZ;
            void main() { vN = aPos; vZ = aPos.z; gl_Position = uMVP * vec4(aPos, 1.0); }
        """
        private const val DOME_FS = """
            precision mediump float;
            varying vec3 vN;
            varying float vZ;
            uniform vec3 uLight;
            uniform vec3 uEye;
            uniform vec3 uTint;
            uniform float uTime;
            uniform float uBeat;
            void main() {
                vec3 n = normalize(vN);
                vec3 l = normalize(uLight);
                float d = max(dot(n, l), 0.0);
                float fres = pow(1.0 - max(dot(n, normalize(uEye)), 0.0), 2.0);
                float wave = 0.5 + 0.5 * sin(uTime * 2.2 - vZ * 7.0);
                vec3 col = uTint * (0.25 + 0.75 * d) + uTint * fres * 0.9 + uTint * wave * uBeat * 0.5;
                float a = 0.35 + 0.35 * fres + 0.25 * uBeat * wave;
                gl_FragColor = vec4(col, clamp(a, 0.0, 1.0));
            }
        """
        private const val LOW_FS = """
            precision mediump float;
            varying vec3 vN;
            varying float vZ;
            uniform vec3 uEye;
            uniform vec4 uColor;
            void main() {
                vec3 n = normalize(vN);
                float fres = pow(1.0 - abs(dot(n, normalize(uEye))), 2.0);
                gl_FragColor = vec4(uColor.rgb, uColor.a * (0.6 + 0.4 * fres));
            }
        """
        private const val COLOR_VS = """
            attribute vec3 aPos;
            uniform mat4 uMVP;
            uniform float uPoint;
            void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uPoint; }
        """
        private const val COLOR_FS = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }
        """
        private const val DOT_FS = """
            precision mediump float;
            uniform vec4 uColor;
            void main() {
                vec2 c = gl_PointCoord - vec2(0.5);
                if (length(c) > 0.5) discard;
                float edge = smoothstep(0.5, 0.36, length(c));
                gl_FragColor = vec4(uColor.rgb, uColor.a * edge);
            }
        """
        private const val TEX_VS = """
            attribute vec3 aPos;
            attribute vec2 aUV;
            uniform mat4 uMVP;
            varying vec2 vUV;
            void main() { gl_Position = uMVP * vec4(aPos, 1.0); vUV = aUV; }
        """
        private const val TEX_FS = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec4 uColor;
            varying vec2 vUV;
            void main() {
                vec4 t = texture2D(uTex, vUV);
                gl_FragColor = vec4(t.rgb * uColor.rgb, t.a * uColor.a);
            }
        """
    }
}

// ============================================================
//  PLŪDURUOJANTIS BURBULAS VIRŠ KITŲ PROGRAMŲ
// ============================================================

object OverlayBus {
    @Volatile var avg: Float? = null
    @Volatile var peak: Float? = null
    @Volatile var onUnpin: (() -> Unit)? = null
}

class BubbleOverlayService : Service() {

    private var wm: WindowManager? = null
    private var bubble: BubbleGL? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pollSignal = object : Runnable {
        override fun run() {
            bubble?.setSignal(OverlayBus.avg, OverlayBus.peak)
            handler.postDelayed(this, 300L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        val dm = resources.displayMetrics
        val size = (88 * dm.density).toInt()
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val view = BubbleGL(this)
        bubble = view
        val lp = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dm.widthPixels - size - 40
        lp.y = dm.heightPixels / 3

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean { unpin(); return true }
        })
        view.setOnTouchListener { _, ev ->
            gd.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = lp.x; startY = lp.y; touchX = ev.rawX; touchY = ev.rawY }
                MotionEvent.ACTION_MOVE -> {
                    val nx = startX + (ev.rawX - touchX).toInt()
                    val ny = startY + (ev.rawY - touchY).toInt()
                    // Burbulas gali išeiti už krašto, bet bent 10 % lieka matoma
                    lp.x = nx.coerceIn(-(size * 0.9f).toInt(), dm.widthPixels - (size * 0.1f).toInt())
                    lp.y = ny.coerceIn(-(size * 0.9f).toInt(), dm.heightPixels - (size * 0.1f).toInt())
                    manager.updateViewLayout(view, lp)
                }
            }
            true
        }
        manager.addView(view, lp)
        handler.post(pollSignal)
    }

    private fun unpin() {
        OverlayBus.onUnpin?.invoke()
        stopSelf()
    }

    private fun startAsForeground() {
        val chId = "gnss_overlay"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(chId, "Plūduriuojantis burbulas", NotificationManager.IMPORTANCE_MIN))
        }
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId) else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = builder
            .setContentTitle("GNSS burbulas aktyvus")
            .setContentText("Dvigubas bakstelėjimas – grąžinti į programą")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .build()
        startForeground(1, n)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { bubble?.let { wm?.removeView(it) } } catch (_: Exception) {}
        bubble = null
        super.onDestroy()
    }
}

/** Nupiešta „dalintis“ piktograma (tokia kaip nuotraukoje) – be teksto. */
class ShareIcon(context: Context) : View(context) {
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        val ox = (width - s) / 2f
        val oy = (height - s) / 2f
        val ax = ox + s * 0.30f; val ay = oy + s * 0.50f
        val bx = ox + s * 0.72f; val by = oy + s * 0.25f
        val cx = ox + s * 0.72f; val cy = oy + s * 0.75f
        val r = s * 0.10f
        canvas.drawLine(ax, ay, bx, by, linePaint)
        canvas.drawLine(ax, ay, cx, cy, linePaint)
        canvas.drawCircle(ax, ay, r, dotPaint)
        canvas.drawCircle(bx, by, r, dotPaint)
        canvas.drawCircle(cx, cy, r, dotPaint)
    }
}

class DraggablePanel(
    context: Context,
    private val onShare: (Set<String>, String, Boolean) -> Unit
) : FrameLayout(context) {

    /** Kviečiama, kai prasideda neapdorotų matavimų įrašymo laikmatis. */
    var onCountdownStart: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val bubbleSize = (88 * density).toInt()
    private val handler = Handler(Looper.getMainLooper())
    private var counting = false
    private var remaining = 0

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            setColor(Color.WHITE)
        }
        elevation = 10 * density
        val pad = (10 * density).toInt()
        setPadding(pad, pad, pad, pad)
        alpha = 0.92f
        visibility = GONE
    }

    private val bubble = BubbleGL(context).apply {
        visibility = VISIBLE
        isClickable = true
        layoutParams = LayoutParams(bubbleSize, bubbleSize)
    }

    private val title = TextView(context).apply {
        text = "Ką siųsti AI?"
        textSize = 15f
        setTextColor(Color.BLACK)
        setTypeface(null, Typeface.BOLD)
    }

    private val optionsLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val boxes = LinkedHashMap<String, CheckBox>()

    // Neapdoroti matavimai: varnelė + įrašo trukmė sekundėmis (atgalinis skaičiavimas)
    private val rawRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val rawSecs = EditText(context).apply {
        setText("30")
        inputType = InputType.TYPE_CLASS_NUMBER
        setSingleLine()
        textSize = 13f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams((56 * density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    private val secLabel = TextView(context).apply {
        text = " s"
        textSize = 13f
        setTextColor(Color.DKGRAY)
    }

    private val questionInput = EditText(context).apply {
        hint = "?"
        setSingleLine()
        textSize = 14f
    }

    private val alphaLabel = TextView(context).apply {
        text = "Permatomumas"
        textSize = 12f
        setTextColor(Color.DKGRAY)
    }

    private val alphaSeek = SeekBar(context).apply {
        max = 80 // 20..100 %
        progress = 72 // ~92 %
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                panel.alpha = (20 + p) / 100f
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun smallButton(label: String, sizeDp: Int): Button = Button(context).apply {
        text = label
        textSize = 14f
        minWidth = 0
        minHeight = 0
        minimumWidth = 0
        minimumHeight = 0
        setPadding(0, 0, 0, 0)
        layoutParams = LinearLayout.LayoutParams((sizeDp * density).toInt(), (sizeDp * density).toInt())
    }

    private val cancelBtn = smallButton("\u2715", 36)     // ✕ (ir atšaukti, ir sumažinti)

    private val shareBtn = FrameLayout(context).apply {
        isClickable = true
        layoutParams = LinearLayout.LayoutParams((40 * density).toInt(), (40 * density).toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE2E8F0.toInt())
        }
        addView(ShareIcon(context), LayoutParams((40 * density * 0.6f).toInt(), (40 * density * 0.6f).toInt(), Gravity.CENTER))
    }

    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val inputRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        isClickable = true
        isFocusable = true

        val opts = linkedMapOf(
            "fiksas" to "Fiksas (koordinatės, tikslumas)",
            "geoidas" to "Geoidas ir aukštis",
            "baro" to "Barometras ir kalibravimas",
            "palydovai" to "Palydovai ir C/N0 dabar",
            "cno" to "C/N0 istorija (~2 min)",
            "klaidos" to "Klaidos ir būsena",
            "irenginys" to "Įrenginys (modelis, Android)",
            "screenshot" to "Įtraukti ekrano kopiją",
            "raw" to "Neapdoroti matavimai (CSV)"
        )
        for ((k, v) in opts) {
            val cb = CheckBox(context).apply {
                text = v
                textSize = 13f
                isChecked = k != "raw"
            }
            boxes[k] = cb
            if (k == "raw") {
                rawRow.addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                rawRow.addView(rawSecs)
                rawRow.addView(secLabel)
            } else {
                optionsLayout.addView(cb)
            }
        }

        header.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(cancelBtn)

        inputRow.addView(questionInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(shareBtn)

        panel.addView(header)
        panel.addView(optionsLayout)
        panel.addView(rawRow)
        panel.addView(alphaLabel)
        panel.addView(alphaSeek)
        panel.addView(inputRow)

        addView(panel)
        addView(bubble)

        cancelBtn.setOnClickListener { minimize() }
        shareBtn.setOnClickListener { runShare() }

        bubble.setOnTouchListener { _, ev -> bubbleGd.onTouchEvent(ev); false }
    }

    /** Burbulo spalva ir efektai: avg – vidutinis C/N0, peak – maksimalus. */
    fun setSignal(avgDb: Float?, peakDb: Float?) {
        bubble.setSignal(avgDb, peakDb)
    }


    var onPin: (() -> Unit)? = null
    private val bubbleAction: () -> Unit = { expandBubble() }
    private val bubbleGd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { bubbleAction?.invoke(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { onPin?.invoke(); return true }
    })

    private fun expandBubble() {
            // Ekrano (lietimo) vibracija – veikia, jei įrenginys ją palaiko
            bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            try {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (vibrator != null && vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(50)
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.w("GnssApp", "Vibracija nepavyko", e)
            }
            try {
                maximize()
            } catch (e: Throwable) {
                android.util.Log.e("GnssApp", "Panelės atidarymas nepavyko", e)
                Toast.makeText(context, "Klaida: ${e.javaClass.simpleName}: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun runShare() {
        if (counting) return
        val selected = boxes.filterValues { it.isChecked }.keys.toSet()
        val screenshot = "screenshot" in selected
        if ("raw" in selected) {
            val secs = rawSecs.text.toString().toIntOrNull()?.coerceIn(5, 600) ?: 30
            startCountdown(secs) {
                // Klausimas nuskaitomas tik pasibaigus laikmačiui, kad spėtumėte parašyti per įrašymą
                val question = questionInput.text.toString().trim()
                onShare(selected, question, screenshot)
                minimize()
            }
        } else {
            val question = questionInput.text.toString().trim()
            onShare(selected, question, screenshot)
            minimize()
        }
    }

    /** Atgalinis skaičiavimas: CSV renkamas per visą laikmatį, tada dalinamasi. */
    private fun startCountdown(secs: Int, done: () -> Unit) {
        counting = true
        remaining = secs
        rawSecs.isEnabled = false
        shareBtn.isEnabled = false
        onCountdownStart?.invoke()
        val tick = object : Runnable {
            override fun run() {
                if (remaining <= 0) {
                    counting = false
                    rawSecs.isEnabled = true
                    rawSecs.setText(secs.toString())
                    shareBtn.isEnabled = true
                    done()
                } else {
                    rawSecs.setText(remaining.toString())
                    remaining--
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        counting = false
        super.onDetachedFromWindow()
    }

    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var touchOnControl = false

    private fun inside(v: View, x: Float, y: Float): Boolean {
        if (v.visibility != VISIBLE) return false
        val r = Rect()
        v.getGlobalVisibleRect(r)
        return r.contains(x.toInt(), y.toInt())
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.rawX
                lastY = ev.rawY
                isDragging = false
                touchOnControl = inside(questionInput, ev.rawX, ev.rawY) ||
                    inside(alphaSeek, ev.rawX, ev.rawY) || inside(rawSecs, ev.rawX, ev.rawY)
                if (touchOnControl) return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchOnControl && !isDragging) {
                    if (abs(ev.rawX - lastX) > 15 || abs(ev.rawY - lastY) > 15) {
                        isDragging = true
                        return true
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    translationX += dx
                    translationY += dy
                    lastX = event.rawX
                    lastY = event.rawY
                    constrainPosition()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    isDragging = false
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun setOwnWidth(px: Int) {
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = px
        layoutParams = lp
    }

    private fun minimize() {
        setOwnWidth(FrameLayout.LayoutParams.WRAP_CONTENT)
        panel.visibility = GONE
        bubble.visibility = VISIBLE
        post { constrainPosition() }
    }

    private fun maximize() {
        // Mažesnė panelė: iki 70 % ekrano pločio ir ne daugiau kaip 260 dp
        val parentW = (parent as? View)?.width ?: 0
        val maxW = (260 * density).toInt()
        val target = if (parentW > 0) min((parentW * 0.7f).toInt(), maxW) else maxW
        setOwnWidth(target)
        bubble.visibility = GONE
        panel.visibility = VISIBLE
        post { adjustPositionOnExpand() }
    }

    private fun adjustPositionOnExpand() {
        val parentW = (parent as? View)?.width?.toFloat() ?: return
        val parentH = (parent as? View)?.height?.toFloat() ?: return

        val minTx = -width * 0.1f
        val maxTx = parentW - width * 0.9f
        val minTy = -height * 0.1f
        val maxTy = parentH - height * 0.9f

        translationX = translationX.coerceIn(minTx, maxTx)
        translationY = translationY.coerceIn(minTy, maxTy)

        if (width > parentW) translationX = 0f
        if (height > parentH) translationY = 0f
    }

    // Burbulas/panelė gali išeiti už ekrano, bet bent 10 % lieka matoma
    private fun constrainPosition() {
        val parentW = (parent as? View)?.width?.toFloat() ?: return
        val parentH = (parent as? View)?.height?.toFloat() ?: return

        val minTx = -width * 0.9f
        val maxTx = parentW - width * 0.1f
        val minTy = -height * 0.9f
        val maxTy = parentH - height * 0.1f

        translationX = translationX.coerceIn(minTx, maxTx)
        translationY = translationY.coerceIn(minTy, maxTy)
    }
}

/** Konstelacijos pavadinimas pagal GnssStatus tipą (viršutinio lygio funkcija). */
fun constName(type: Int): String = when (type) {
    GnssStatus.CONSTELLATION_GPS -> "GPS"
    GnssStatus.CONSTELLATION_GALILEO -> "GAL"
    GnssStatus.CONSTELLATION_GLONASS -> "GLO"
    GnssStatus.CONSTELLATION_BEIDOU -> "BDS"
    GnssStatus.CONSTELLATION_QZSS -> "QZS"
    GnssStatus.CONSTELLATION_SBAS -> "SBS"
    GnssStatus.CONSTELLATION_IRNSS -> "NVC"
    else -> "?"
}

// ============================================================
//  SIGNALO SPALVOS SKALĖ
// ============================================================

const val SIG_RED = 0xFFEF4444.toInt()
const val SIG_BLUE = 0xFF3B82F6.toInt()
const val SIG_YELLOW = 0xFFFACC15.toInt()
const val BG_NEUTRAL = 0xFFF8FAFC.toInt()

fun signalColor(db: Float): Int = when {
    db <= 20f -> SIG_RED
    db <= 30f -> ColorUtils.blendARGB(SIG_RED, SIG_BLUE, (db - 20f) / 10f)
    db <= 40f -> ColorUtils.blendARGB(SIG_BLUE, SIG_YELLOW, (db - 30f) / 10f)
    else -> SIG_YELLOW
}

// ============================================================
//  JUDANTIS SPALVŲ FONAS
// ============================================================

class GradientBackground(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    @Volatile private var targetColor = BG_NEUTRAL
    private var currentColor = BG_NEUTRAL

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 14000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedFraction
            currentColor = ColorUtils.blendARGB(currentColor, targetColor, 0.03f)
            invalidate()
        }
    }

    fun setSignal(db: Float?) {
        targetColor = if (db == null) BG_NEUTRAL else signalColor(db)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(BG_NEUTRAL)
        val angle = t * 2 * Math.PI
        val radius = max(w, h) * 0.6f
        val blobColors = intArrayOf(
            currentColor,
            ColorUtils.blendARGB(currentColor, 0xFFFFFFFF.toInt(), 0.35f),
            ColorUtils.blendARGB(currentColor, 0xFF000000.toInt(), 0.20f)
        )
        for (i in blobColors.indices) {
            val phase = angle + i * 2 * Math.PI / blobColors.size
            val cx = w * (0.5f + 0.35f * cos(phase).toFloat())
            val cy = h * (0.5f + 0.25f * sin(phase * 1.3).toFloat())
            paint.shader = RadialGradient(cx, cy, radius, withAlpha(blobColors[i], 0.55f), 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
}

// ============================================================
//  PAGRINDINIS EKRANAS
// ============================================================

class MainActivity : AppCompatActivity() {
    private lateinit var lm: LocationManager
    private lateinit var info: TextView
    private lateinit var skyView: GnssView
    private lateinit var skyDome: SkyDomeGL

    /** Perjungia 2D dangų ir 3D gaublį jų vietoje (dvigubas bakstelėjimas). */
    private fun setGlobe(on: Boolean) {
        use3d = on
        skyView.visibility = if (on) View.GONE else View.VISIBLE
        skyDome.visibility = if (on) View.VISIBLE else View.GONE
        if (on) skyDome.start() else skyDome.stop()
    }

    /** Prisegti burbulą prie ekrano (veikia ir kitose programose). */
    private fun pinBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Leiskite rodyti virš kitų programų", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        OverlayBus.onUnpin = { runOnUiThread { sharePanel?.visibility = View.VISIBLE } }
        sharePanel?.visibility = View.GONE
        startForegroundService(Intent(this, BubbleOverlayService::class.java))
    }
    private var use3d = false
    private lateinit var cnoView: CnoPlotView
    private lateinit var bg: GradientBackground
    private lateinit var baro: Barometer
    private lateinit var mainExecutor: Executor
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var container: FrameLayout
    private var sharePanel: DraggablePanel? = null

    private var lastGpsH: Double? = null
    private var lastSats: List<Sat> = emptyList()
    private val errorLog = ArrayDeque<String>()

    private var posText = "Laukiama fikso..."
    private var satText: CharSequence = "Laukiama palydovų..."
    private var measText = "Laukiama matavimų..."

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val list = (0 until status.satelliteCount).map { i ->
                Sat(
                    const = constName(status.getConstellationType(i)),
                    svid = status.getSvid(i),
                    cn0 = status.getCn0DbHz(i),
                    elev = status.getElevationDegrees(i),
                    az = status.getAzimuthDegrees(i),
                    used = status.usedInFix(i)
                )
            }
            lastSats = list
            skyView.sats = list
            if (::skyDome.isInitialized) skyDome.setSats(list)
            val used = list.filter { it.used }
            val avg = if (used.isNotEmpty()) used.map { it.cn0.toDouble() }.average() else 0.0
            val max = list.maxOfOrNull { it.cn0 } ?: 0f
            val byConst = list.groupingBy { it.const }.eachCount().toSortedMap()

            cnoView.addSample(list, if (used.isNotEmpty()) avg.toFloat() else null)
            bg.setSignal(if (used.isNotEmpty()) avg.toFloat() else null)
            OverlayBus.avg = if (used.isNotEmpty()) avg.toFloat() else null
            OverlayBus.peak = if (used.isNotEmpty()) used.maxOf { it.cn0 } else null
            sharePanel?.setSignal(
                if (used.isNotEmpty()) avg.toFloat() else null,
                if (used.isNotEmpty()) used.maxOf { it.cn0 } else null
            )

            satText = SpannableStringBuilder().apply {
                appendLine("Matomi palydovai: ${list.size}")
                appendLine("Naudojami fiksui: ${used.size}")
                appendLine("Vidutinis C/N0 (naudojami): ${fx(avg)} dB-Hz")
                appendLine("Maks. C/N0: ${fx(max.toDouble())} dB-Hz")
                val sbsAll = list.count { it.const == "SBS" }
                val sbsUsed = list.count { it.const == "SBS" && it.used }
                appendLine("SBAS (EGNOS) matomi: $sbsAll, naudojami: $sbsUsed")
                appendLine(
                    "SBAS pataisos: " + when {
                        sbsUsed > 0 -> "taip (SBAS palydovas naudojamas fiksui)"
                        sbsAll > 0 -> "matomas, bet fiksui nenaudojamas"
                        else -> "SBAS palydovų nematyti"
                    }
                )
                append("Pagal konstelaciją:\n")
                for ((const, count) in byConst) {
                    val start = length
                    append("$const=$count   ")
                    val end = length
                    setSpan(ForegroundColorSpan(constColor(const)), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(RelativeSizeSpan(1.4f), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            render()
        }
    }

    private val measCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            measText = "Neapdoroti matavimai: ${event.measurements.size}"
            for (m in event.measurements) appendRaw(m)
            render()
        }
    }

    private val locationListener = LocationListener { loc -> onLocation(loc) }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startGnss() else {
            posText = "Reikia vietos leidimo."
            logError("Vietos leidimas nesuteiktas")
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = Color.TRANSPARENT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        mainExecutor = ContextCompat.getMainExecutor(this)
        baro = Barometer(this)

        info = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF0F172A.toInt())
            setPadding(32, 16, 32, 16)
        }
        skyView = GnssView(this)
        cnoView = CnoPlotView(this)
        bg = GradientBackground(this)

        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, GeoidMode.values().map { it.label })
            setSelection(GeoidMode.values().indexOf(Geoid.mode))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    Geoid.select(applicationContext, GeoidMode.values()[pos]) { runOnUiThread { render() } }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        val btnZero = Button(this).apply { text = "Nulis čia"; setOnClickListener { baro.calibrateZero(); render() } }
        val btnGps = Button(this).apply {
            text = "Kalibruoti pagal GPS aukštį"
            setOnClickListener {
                val h = lastGpsH
                if (h != null) baro.calibrateTo(h)
                else {
                    posText = "Nėra GPS aukščio (laukiama fikso ir geoido)."
                    logError("Kalibravimas be GPS aukščio")
                }
                render()
            }
        }
        val btnReset = Button(this).apply { text = "Atstatyti kalibravimą"; setOnClickListener { baro.reset(); render() } }

        Geoid.select(applicationContext, GeoidMode.LIT20G) { runOnUiThread { render() } }

        val dm = resources.displayMetrics
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val colW = if (landscape) dm.widthPixels / 2 else dm.widthPixels
        val skySide = if (landscape) min(colW, dm.heightPixels) else colW
        val cnoH = (260 * dm.density).toInt()
        val mw = LinearLayout.LayoutParams.MATCH_PARENT
        val ww = LinearLayout.LayoutParams.WRAP_CONTENT

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(modeSpinner, LinearLayout.LayoutParams(mw, ww))
        left.addView(btnZero, LinearLayout.LayoutParams(mw, ww))
        left.addView(btnGps, LinearLayout.LayoutParams(mw, ww))
        left.addView(btnReset, LinearLayout.LayoutParams(mw, ww))
        left.addView(info, LinearLayout.LayoutParams(mw, ww))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(skyView, LinearLayout.LayoutParams(skySide, skySide))
        skyDome = SkyDomeGL(this).apply { visibility = View.GONE }
        right.addView(skyDome, LinearLayout.LayoutParams(skySide, skySide))
        skyDome.onDoubleTapCb = { setGlobe(false) }
        val skyGd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean { setGlobe(true); return true }
        })
        skyView.setOnTouchListener { _, ev -> skyGd.onTouchEvent(ev); true }
        right.addView(cnoView, LinearLayout.LayoutParams(mw, cnoH))

        val content = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        if (landscape) {
            content.addView(left, LinearLayout.LayoutParams(0, ww, 1f))
            content.addView(right, LinearLayout.LayoutParams(0, ww, 1f))
        } else {
            content.addView(left, LinearLayout.LayoutParams(mw, ww))
            content.addView(right, LinearLayout.LayoutParams(mw, ww))
        }

        val root = ScrollView(this).apply { addView(content) }

        container = FrameLayout(this)
        container.addView(bg, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        container.addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        setContentView(container)
        
        // Pradedame su iš karto aktyviu burbulu (minimizuota būsena)
        setupShareBubble()
    }

    private fun setupShareBubble() {
        if (sharePanel != null) return
        val panel = DraggablePanel(this) { sections, question, wantScreenshot ->
            val report = buildReport(sections - "screenshot" - "raw", question)
            val uris = ArrayList<Uri>()
            if (wantScreenshot) {
                // Burbulas ir panelė NEPATENKA į ekrano kopiją
                sharePanel?.visibility = View.INVISIBLE
                try {
                    takeScreenshot()?.let { uris.add(it) }
                } finally {
                    sharePanel?.visibility = View.VISIBLE
                }
            }
            if ("raw" in sections) rawCsvUri()?.let { uris.add(it) }
            // Ataskaita (su klausimu) visada kaip tekstinis failas: kai siunčiami failai,
            // kai kurios programos (pvz., AI) ignoruoja EXTRA_TEXT ir klausimas dingsta.
            reportFileUri(report)?.let { uris.add(0, it) }
            shareContent(report, uris)
        }
        panel.onCountdownStart = {
            // Atgalinio skaičiavimo pradžioje CSV buferis pradedamas iš naujo
            rawLog.setLength(0)
            rawLog.append(rawHeader)
        }
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        
        // PAKEISTA: Naudojame START, kad translationX skaičiavimas būtų teisingas
        params.gravity = Gravity.TOP or Gravity.START 
        
        container.addView(panel, params)
        panel.onPin = { pinBubble() }
        sharePanel = panel
        
        // Nustatome pradinę poziciją viršutiniame dešiniajame kampe
        panel.post {
            val margin = (16 * resources.displayMetrics.density).toInt()
            // Kadangi gravitacija START, translationX pastums į dešinę
            panel.translationX = (container.width - panel.width - margin).toFloat()
            panel.translationY = margin.toFloat()
        }
    }

    override fun onStart() {
        super.onStart()
        baro.start()
        if (use3d && ::skyDome.isInitialized) skyDome.start()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startGnss()
        } else {
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onStop() {
        super.onStop()
        baro.stop()
        if (::skyDome.isInitialized) skyDome.stop()
        lm.unregisterGnssStatusCallback(statusCallback)
        lm.unregisterGnssMeasurementsCallback(measCallback)
        lm.removeUpdates(locationListener)
    }

    @SuppressLint("MissingPermission")
    private fun startGnss() {
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            posText = "GPS išjungtas. Įjunkite vietos nustatymus."
            logError("GPS išjungtas")
            render()
        }
        lm.registerGnssStatusCallback(statusCallback, mainHandler)
        if (Build.VERSION.SDK_INT >= 31) registerFullTracking() else lm.registerGnssMeasurementsCallback(measCallback, mainHandler)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
    }

    @RequiresApi(31)
    @SuppressLint("MissingPermission")
    private fun registerFullTracking() {
        val request = GnssMeasurementRequest.Builder().setFullTracking(true).build()
        lm.registerGnssMeasurementsCallback(request, mainExecutor, measCallback)
    }

    private fun onLocation(loc: Location) {
        posText = buildString {
            appendLine("Platuma: ${fx(loc.latitude, 7)}")
            appendLine("Ilguma: ${fx(loc.longitude, 7)}")
            appendLine(if (loc.hasAccuracy()) "Tikslumas: ${fx(loc.accuracy.toDouble())} m" else "Tikslumas: nežinomas")
            if (loc.hasAltitude()) {
                appendLine("Aukštis (elipsoidas): ${fx(loc.altitude)} m")
                appendLine("Geoidas: ${Geoid.mode.label}")
                val n = Geoid.undulation(loc.latitude, loc.longitude)
         
