package lt.example.gnss

import android.Manifest
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
//  DANGAUS ŽEMĖLAPIS (2D) – dabar su dvigubu bakstelėjimu
// ============================================================

class GnssView(context: Context) : View(context) {
    var sats: List<Sat> = emptyList()
        set(value) { field = value; invalidate() }

    /** Iškviečiama dvigubo bakstelėjimo metu – perduodamos vietinės koordinatės. */
    var onDoubleTap: ((Float, Float) -> Unit)? = null

    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var hasMoved = false

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

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                downX = e.x
                downY = e.y
                hasMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 30f || abs(e.y - downY) > 30f) hasMoved = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                val upTime = System.currentTimeMillis()
                if (!hasMoved && upTime - downTime < 250) {
                    if (lastTapTime > 0 && upTime - lastTapTime < 400 &&
                        abs(e.x - lastTapX) < 100f && abs(e.y - lastTapY) < 100f) {
                        onDoubleTap?.invoke(e.x, e.y)
                        lastTapTime = 0L
                    } else {
                        lastTapTime = upTime
                        lastTapX = e.x
                        lastTapY = e.y
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                hasMoved = true
                return true
            }
        }
        return super.onTouchEvent(e)
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

        val coreR = r * 0.55f
        val pulseR = r * (0.85f + 0.12f * t)
        paint.shader = RadialGradient(cx, cy, pulseR, withAlpha(base, 0.6f), 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, pulseR, paint)
        paint.shader = null

        paint.color = base
        canvas.drawCircle(cx, cy, coreR, paint)

        if (ultra) {
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
            paint.color = 0xFF1F2937.toInt()
            dust.forEach {
                paint.alpha = (255 * it.life / 30f).toInt().coerceIn(0, 255)
                canvas.drawCircle(it.x, it.y, 2.2f * density, paint)
            }
            paint.alpha = 255
        }
    }

    private fun drawSkullCloud(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.color = 0xFF111827.toInt()
        canvas.drawCircle(cx - r * 0.42f, cy + r * 0.05f, r * 0.42f, paint)
        canvas.drawCircle(cx + r * 0.42f, cy + r * 0.05f, r * 0.42f, paint)
        canvas.drawCircle(cx, cy - r * 0.18f, r * 0.55f, paint)
        canvas.drawCircle(cx - r * 0.22f, cy + r * 0.32f, r * 0.38f, paint)
        canvas.drawCircle(cx + r * 0.22f, cy + r * 0.32f, r * 0.38f, paint)
        canvas.drawRect(cx - r * 0.5f, cy + r * 0.05f, cx + r * 0.5f, cy + r * 0.36f, paint)
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

        if (ultra && bolts.isNotEmpty()) {
            drawBoltStrips(0.022f, floatArrayOf(1f, 0.85f, 0.2f, 0.9f))
            drawBoltStrips(0.008f, floatArrayOf(1f, 1f, 1f, 1f))
        }

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
            val a = rnd.nextFloat() * 2f * Math.PI.toFloat()
            val steps = 4 + rnd.nextInt(3)
            val xy = FloatArray((steps + 1) * 2)
            var x = kotlin.math.cos(a) * 0.92f
            var y = kotlin.math.sin(a) * 0.92f
            xy[0] = x; xy[1] = y
            for (i in 1..steps) {
                val dist = 0.92f + 0.22f * i / steps
                val ja = a + (rnd.nextFloat() - 0.5f) * 0.9f
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
//  + dvigubas bakstelėjimas -> onDoubleTap
// ============================================================

class SkyDomeGL(context: Context) : GLSurfaceView(context), SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val renderer = GlobeRenderer(context.applicationContext, resources.displayMetrics.density)
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    /** Dvigubas bakstelėjimas ant gaublio → suskleisti į 2D. */
    var onDoubleTap: (() -> Unit)? = null

    private var manual = false
    private var baseRot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private var lastRaw = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private var yawD = 0f
    private var pitchD = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val resumeSensors = Runnable { manual = false }

    // Dviejų pirštų perkėlimas
    var onMove: ((Float, Float) -> Unit)? = null
    private var moveMode = false
    private var lastRawX = 0f
    private var lastRawY = 0f

    // Dviejų bakstelėjimų aptikimas
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var hasMoved = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
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
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                downX = e.x
                downY = e.y
                hasMoved = false
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
                // Du pirštai – tai ne bakstelėjimas; panaikiname bakstelėjimo būseną
                lastTapTime = 0L
                if (e.pointerCount >= 2) {
                    moveMode = true
                    lastRawX = (e.getRawX(0) + e.getRawX(1)) / 2f
                    lastRawY = (e.getRawY(0) + e.getRawY(1)) / 2f
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 20f || abs(e.y - downY) > 20f) hasMoved = true
                if (moveMode && e.pointerCount >= 2) {
                    val cx = (e.getRawX(0) + e.getRawX(1)) / 2f
                    val cy = (e.getRawY(0) + e.getRawY(1)) / 2f
                    onMove?.invoke(cx - lastRawX, cy - lastRawY)
                    lastRawX = cx
                    lastRawY = cy
                } else if (!moveMode) {
                    yawD += (e.x - lastX) * 0.4f
                    pitchD = (pitchD + (e.y - lastY) * 0.4f).coerceIn(-85f, 85f)
                    renderer.setRotation(composeManual())
                }
                lastX = e.x
                lastY = e.y
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining = if (e.actionIndex == 0) 1 else 0
                moveMode = false
                if (remaining < e.pointerCount) {
                    lastX = e.getX(remaining)
                    lastY = e.getY(remaining)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                moveMode = false
                val upTime = System.currentTimeMillis()
                if (!hasMoved && upTime - downTime < 250) {
                    if (lastTapTime > 0 && upTime - lastTapTime < 400 &&
                        abs(e.x - lastTapX) < 100f && abs(e.y - lastTapY) < 100f) {
                        onDoubleTap?.invoke()
                        lastTapTime = 0L
                    } else {
                        lastTapTime = upTime
                        lastTapX = e.x
                        lastTapY = e.y
                    }
                }
                handler.removeCallbacks(resumeSensors)
                handler.postDelayed(resumeSensors, 60_000L)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                moveMode = false
                hasMoved = true
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
        val ca = cos(a).toFloat(); val sa = sin(a
