package lt.example.gnss

import android.Manifest
import android.graphics.BitmapFactory
import android.hardware.GeomagneticField
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
import android.location.GnssNavigationMessage
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
import android.view.ScaleGestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

    @Synchronized
    fun load(context: Context) {
        if (grid != null) return
        val t0 = android.os.SystemClock.elapsedRealtime()
        val data = FloatArray(nLat * nLon) { Float.NaN }
        // Visas failas nuskaitomas į baitų buferį ir skaičiai išskiriami rankiniu būdu
        // (be eilučių objektų ir Regex – daug mažiau atminties šiukšlių).
        val buf = context.assets.open(assetName).use { it.readBytes() }
        val vals = DoubleArray(3)
        var points = 0
        var pos = 0
        val end = buf.size
        while (pos < end) {
            // Vienos eilutės ribos
            var eol = pos
            while (eol < end && buf[eol] != '\n'.code.toByte()) eol++
            var cnt = 0
            var p = pos
            while (cnt < 3) {
                while (p < eol && (buf[p] == ' '.code.toByte() || buf[p] == '\t'.code.toByte() || buf[p] == '\r'.code.toByte())) p++
                if (p >= eol) break
                val s = p
                while (p < eol && buf[p] != ' '.code.toByte() && buf[p] != '\t'.code.toByte() && buf[p] != '\r'.code.toByte()) p++
                val v = parseNumber(buf, s, p)
                if (v.isNaN()) break
                vals[cnt++] = v
            }
            if (cnt == 3) {
                val i = ((vals[0] - latMin) / latStep).roundToInt()
                val j = ((vals[1] - lonMin) / lonStep).roundToInt()
                if (i in 0 until nLat && j in 0 until nLon) {
                    data[i * nLon + j] = vals[2].toFloat()
                    points++
                }
            }
            pos = eol + 1
        }
        grid = data
        android.util.Log.i(
            "Geoid",
            "$assetName įkeltas per ${android.os.SystemClock.elapsedRealtime() - t0} ms " +
                "(${buf.size} B, taškų: $points)"
        )
    }

    /** Skaičius iš buf[s, e): ženklas, sveikoji ir trupmeninė dalys, nebūtinas eksponentas. NaN – jei ne skaičius. */
    private fun parseNumber(buf: ByteArray, s: Int, e: Int): Double {
        var p = s
        var neg = false
        if (p < e && (buf[p] == '-'.code.toByte() || buf[p] == '+'.code.toByte())) {
            neg = buf[p] == '-'.code.toByte(); p++
        }
        var mant = 0L
        var scale = 0
        var digits = 0
        while (p < e && buf[p] in '0'.code.toByte()..'9'.code.toByte()) {
            mant = mant * 10 + (buf[p] - '0'.code.toByte()); p++; digits++
        }
        if (p < e && buf[p] == '.'.code.toByte()) {
            p++
            while (p < e && buf[p] in '0'.code.toByte()..'9'.code.toByte()) {
                mant = mant * 10 + (buf[p] - '0'.code.toByte()); p++; digits++; scale++
            }
        }
        if (digits == 0 || digits > 18) return String(buf, s, e - s, Charsets.US_ASCII).toDoubleOrNull() ?: Double.NaN
        if (p < e && (buf[p] == 'e'.code.toByte() || buf[p] == 'E'.code.toByte())) {
            return String(buf, s, e - s, Charsets.US_ASCII).toDoubleOrNull() ?: Double.NaN
        }
        if (p != e) return Double.NaN
        val v = mant.toDouble() / POW10[scale]
        return if (neg) -v else v
    }

    private companion object {
        val POW10 = DoubleArray(19).also { a -> a[0] = 1.0; for (k in 1 until a.size) a[k] = a[k - 1] * 10.0 }
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
                android.util.Log.e("Geoid", "Nepavyko įkelti ${m.assetFile}", e)
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

    private fun isUltra() = (peak ?: 0f) >= ULTRA_DB
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

/** Permatomas 3D burbulas: šviesa, spalva pagal signalą, ULTRA kibirkštys, silpno signalo ateivis po atsidariusiu liuku. */
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
    fun setRecording(on: Boolean) { renderer.recording = on }
}

private class BubbleRenderer(private val appCtx: Context, private val density: Float) : GLSurfaceView.Renderer {

    @Volatile private var avg: Float? = null
    @Volatile private var peak: Float? = null
    fun setSignal(a: Float?, p: Float?) { avg = a; peak = p }
    @Volatile var recording = false

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)

    private var sphereProg = 0
    private var flatProg = 0
    private var dotProg = 0
    private var sphereBuf: FloatBuffer? = null
    private var sphereCount = 0

    /** Kabinos liuko atidarymo laipsnis: 0 – uždarytas, 1 – visiškai atidarytas. */
    private var hatchOpen = 0f

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

        val sph = buildSphere(24, 32)
        sphereCount = sph.size / 3
        sphereBuf = floatBuf(sph)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        // Kamera žiūri į rutulį iš z=4; matomas plotis ±0.6 ties artimąja plokštuma
        // Langelis aukštesnis už plotį: lėkštė viršuje, spindulys turi vietos žemyn
        val asp = if (w > 0) h.toFloat() / w else 1f
        Matrix.frustumM(proj, 0, -0.6f, 0.6f, 0.6f - 1.2f * asp, 0.6f, 2f, 10f)
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
        val ultra = (pk ?: 0f) >= ULTRA_DB
        val col = when {
            a == null -> floatArrayOf(0.23f, 0.51f, 0.96f)
            weak -> floatArrayOf(0.12f, 0.16f, 0.22f)
            ultra -> floatArrayOf(0.98f, 0.80f, 0.08f)
            else -> rgb(signalColor(a))
        }

        // 1) NSO lėkštė: plokščias diskas + kupolas + mirksintys šviesos ant krašto
        val pulse = 0.9f + 0.03f * kotlin.math.sin(frame * 0.05).toFloat()
        val hover = 0.06f * kotlin.math.sin(frame * 0.04).toFloat()      // lengvas plūduriavimas
        val ang = frame * 0.02
        spinAcc = (spinAcc + if (ultra) 2.8f else 1.2f) % 360f            // ULTRA – sukasi greičiau
        val spinDeg = spinAcc

        // Liukas: silpnas signalas -> lėtai atsidaro, signalas atsistato -> užsidaro
        val hatchTarget = if (weak) 1f else 0f
        hatchOpen += (hatchTarget - hatchOpen).coerceIn(-HATCH_STEP, HATCH_STEP)
        val hOpen = hatchOpen * hatchOpen * (3f - 2f * hatchOpen)          // smoothstep – minkštas judesys

        /**
         * Viena lėkštės dalis (elipsoidas).
         * [spin] – ar sukasi kartu su lėkšte; [lift]/[hingeZ]/[tilt] – pakėlimas ir pasukimas apie vyrį (liukui),
         * [dx]/[dz] – poslinkis lėkštės plokštumoje (pvz., akims).
         */
        fun part(sx: Float, sy: Float, sz: Float, ty: Float, c: FloatArray, alpha: Float,
                 spin: Boolean = true, lift: Float = 0f, hingeZ: Float = 0f, tilt: Float = 0f,
                 dx: Float = 0f, dz: Float = 0f) {
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, 0f, hover, 0f)
            Matrix.rotateM(model, 0, 22f, 1f, 0f, 0f)                     // pakreipta į žiūrovą
            if (spin) Matrix.rotateM(model, 0, spinDeg, 0f, 1f, 0f)
            Matrix.translateM(model, 0, dx * pulse, (ty + lift) * pulse, dz * pulse)
            if (tilt != 0f) {
                // Vyris ties užpakaliniu kraštu (toliau nuo žiūrovo): priekinis kraštas kyla aukštyn
                Matrix.translateM(model, 0, 0f, 0f, hingeZ * pulse)
                Matrix.rotateM(model, 0, -tilt, 1f, 0f, 0f)
                Matrix.translateM(model, 0, 0f, 0f, -hingeZ * pulse)
            }
            Matrix.scaleM(model, 0, sx * pulse, sy * pulse, sz * pulse)
            Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
            GLES20.glUseProgram(sphereProg)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(sphereProg, "uMVP"), 1, false, mvp, 0)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(sphereProg, "uLight"),
                (0.6 * kotlin.math.cos(ang)).toFloat(), 0.8f, 0.6f)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(sphereProg, "uColor"), c[0], c[1], c[2])
            GLES20.glUniform1f(GLES20.glGetUniformLocation(sphereProg, "uAlpha"), alpha)
            bindAttrib(sphereProg, "aPos", sphereBuf!!, 3)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, sphereCount)
        }
        val metal = floatArrayOf(col[0] * 0.2f + 0.62f, col[1] * 0.2f + 0.64f, col[2] * 0.2f + 0.68f)
        part(1.0f, 0.26f, 1.0f, 0f, metal, 0.97f)                                  // diskas
        part(0.62f, 0.12f, 0.62f, -0.12f, floatArrayOf(col[0] * 0.5f, col[1] * 0.5f, col[2] * 0.5f), 0.97f) // apačia

        // Ateivis kabinoje (nepermatomas – piešiamas prieš stiklinį kupolą)
        if (hOpen > 0.01f) {
            val bob = 0.03f * kotlin.math.sin(frame * 0.035).toFloat()      // lėtas pliūpsnis aukštyn-žemyn
            val bodyY = 0.14f + (0.12f + bob) * hOpen                        // iškyla, kai liukas atsidaro
            val headY = bodyY + 0.17f
            val green = floatArrayOf(0.25f, 0.85f, 0.30f)
            val black = floatArrayOf(0.04f, 0.04f, 0.05f)
            val eye = floatArrayOf(0.35f, 1.0f, 0.40f)
            part(0.15f, 0.14f, 0.15f, bodyY, green, 1f, spin = false)          // žalias kūnas
            part(0.13f, 0.12f, 0.12f, headY, black, 1f, spin = false)          // juoda galva
            part(0.035f, 0.045f, 0.02f, headY + 0.01f, eye, 1f, spin = false, dx = -0.055f, dz = 0.105f) // kairė akis
            part(0.035f, 0.045f, 0.02f, headY + 0.01f, eye, 1f, spin = false, dx = 0.055f, dz = 0.105f)  // dešinė akis
        }

        // Stiklinis kupolas – kabinos liukas: pakyla ir pasvyra apie užpakalinį kraštą
        val domeLift = 0.10f * hOpen
        val domeTilt = 65f * hOpen
        val domeHinge = -0.42f
        val domeSpin = hOpen < 0.001f                                       // atidarytas liukas nesisuka su lėkšte
        GLES20.glDepthMask(false)                                           // stiklas neuždengia ateivio gylio buferyje
        if (recording) {
            // Įrašas vyksta: tik stiklinė kabina švyti, spalva plaukia mėlyna ↔ žalia
            val k = 0.5f + 0.5f * kotlin.math.sin(frame * 0.08).toFloat()
            val glow = 0.8f + 0.2f * kotlin.math.sin(frame * 0.2).toFloat()
            val cab = floatArrayOf(
                (0.15f * k + 0.15f * (1 - k)) * glow,
                (1.0f * k + 0.55f * (1 - k)) * glow,
                (0.35f * k + 1.0f * (1 - k)) * glow)
            part(0.42f, 0.38f, 0.42f, 0.16f, cab, 0.85f,
                spin = domeSpin, lift = domeLift, hingeZ = domeHinge, tilt = domeTilt)
        } else {
            part(0.42f, 0.38f, 0.42f, 0.16f, floatArrayOf(0.75f, 0.9f, 1f), 0.6f,
                spin = domeSpin, lift = domeLift, hingeZ = domeHinge, tilt = domeTilt)   // stiklinis kupolas
        }
        GLES20.glDepthMask(true)

        // Lėkštės plokštumos matrica (spinduliui ir žaibams)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, hover, 0f)
        Matrix.rotateM(model, 0, 22f, 1f, 0f, 0f)
        Matrix.multiplyMM(ufoMvp, 0, vp, 0, model, 0)
        if (ultra) drawTractorBeam(col)

        // Šviesos ant krašto – signalo spalva, bėga ratu (ULTRA – dega visos)
        GLES20.glUseProgram(dotProg)
        System.arraycopy(ufoMvp, 0, mvp, 0, 16)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(dotProg, "uMVP"), 1, false, mvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(dotProg, "uPoint"), 9f * density)
        val nLights = 10
        val lead = (frame / (if (ultra) 2 else 4)) % nLights
        for (i in 0 until nLights) {
            val a = 2.0 * Math.PI * i / nLights + Math.toRadians(spinDeg.toDouble())
            val on = ultra || ((i - lead + nLights) % nLights) < 3
            var br = if (on) 1f else 0.35f
            if (recording) br = 0.35f + 0.65f * rnd.nextFloat()      // mirguliavimas įrašo metu
            GLES20.glUniform4f(GLES20.glGetUniformLocation(dotProg, "uColor"),
                (col[0] * 0.5f + 0.5f) * br, (col[1] * 0.5f + 0.5f) * br, (col[2] * 0.5f + 0.5f) * br, 1f)
            bindAttrib(dotProg, "aPos", floatBuf(floatArrayOf(
                (0.97 * pulse * kotlin.math.cos(a)).toFloat(), 0.02f, (0.97 * pulse * kotlin.math.sin(a)).toFloat())), 3)
            GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
        }

        // 2) ULTRA: elektros kibirkštys (geltonas švytėjimas + baltas branduolys)
        if (ultra && bolts.isNotEmpty()) {
            drawBoltStrips(6f * density, floatArrayOf(1f, 0.85f, 0.2f, 0.85f))
            drawBoltStrips(2f * density, floatArrayOf(1f, 1f, 1f, 1f))
        }

        // 3) Silpnas signalas: išsisklaidančios dulkės (ateivis po liuku – aukščiau)
        if (weak) {
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

    /** Žaibai – 3D linijos lėkštės koordinatėse (ufoMat). */
    private fun drawBoltStrips(width: Float, color: FloatArray) {
        val verts = ArrayList<Float>()
        for (bt in bolts) {
            val p = bt.xy
            var i = 0
            while (i + 5 < p.size) {
                for (k in 0 until 6) verts.add(p[i + k])
                i += 3
            }
        }
        if (verts.isEmpty()) return
        GLES20.glUseProgram(flatProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(flatProg, "uMVP"), 1, false, ufoMvp, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(flatProg, "uColor"), color[0], color[1], color[2], color[3])
        bindAttrib(flatProg, "aPos", floatBuf(verts.toFloatArray()), 3)
        GLES20.glLineWidth(width)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, verts.size / 3)
        GLES20.glLineWidth(1f)
    }

    private val ufoMvp = FloatArray(16)
    private var spinAcc = 0f

    /** Traktorinis spindulys: pulsuojantis šviesos kūgis žemyn iš lėkštės apačios. */
    private fun drawTractorBeam(col: FloatArray) {
        val segs = 6
        val sides = 40
        val yTop = -0.14f; val yBot = -1.05f
        val rTop = 0.30f; val rBot = 0.85f
        val pulseB = 0.75f + 0.25f * kotlin.math.sin(frame * 0.12).toFloat()
        GLES20.glDepthMask(false)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(flatProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(flatProg, "uMVP"), 1, false, ufoMvp, 0)
        for (sgi in 0 until segs) {
            val f0 = sgi.toFloat() / segs; val f1 = (sgi + 1).toFloat() / segs
            val y0 = yTop + (yBot - yTop) * f0; val y1 = yTop + (yBot - yTop) * f1
            val r0 = rTop + (rBot - rTop) * f0; val r1 = rTop + (rBot - rTop) * f1
            val v = ArrayList<Float>()
            for (i in 0 until sides) {
                val a0 = 2.0 * Math.PI * i / sides; val a1 = 2.0 * Math.PI * (i + 1) / sides
                val c0 = kotlin.math.cos(a0).toFloat(); val s0 = kotlin.math.sin(a0).toFloat()
                val c1 = kotlin.math.cos(a1).toFloat(); val s1 = kotlin.math.sin(a1).toFloat()
                val q = floatArrayOf(
                    r0 * c0, y0, r0 * s0, r1 * c0, y1, r1 * s0, r1 * c1, y1, r1 * s1,
                    r0 * c0, y0, r0 * s0, r1 * c1, y1, r1 * s1, r0 * c1, y0, r0 * s1)
                for (x in q) v.add(x)
            }
            val alpha = 0.22f * (1f - f0) * pulseB
            GLES20.glUniform4f(GLES20.glGetUniformLocation(flatProg, "uColor"),
                col[0] * 0.4f + 0.6f, col[1] * 0.4f + 0.6f, col[2] * 0.3f + 0.5f, alpha)
            bindAttrib(flatProg, "aPos", floatBuf(v.toFloatArray()), 3)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, v.size / 3)
        }
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
    }

    private fun updateEffects() {
        if ((peak ?: 0f) >= ULTRA_DB && rnd.nextFloat() < 0.6f) {
            // Elektros iškrova nuo lėkštės krašto į išorę, lėkštės plokštumoje (x, y, z trejetai)
            val a = rnd.nextFloat() * 2f * Math.PI.toFloat()
            val steps = 5 + rnd.nextInt(4)
            val xyz = FloatArray((steps + 1) * 3)
            val r0 = 0.95f
            val r1 = 1.38f
            var ja = a
            for (i in 0..steps) {
                val f = i.toFloat() / steps
                val dist = r0 + (r1 - r0) * f
                if (i > 0) ja += (rnd.nextFloat() - 0.5f) * 0.28f
                xyz[i * 3] = kotlin.math.cos(ja) * dist
                xyz[i * 3 + 1] = if (i == 0) 0f else (rnd.nextFloat() - 0.5f) * 0.16f
                xyz[i * 3 + 2] = kotlin.math.sin(ja) * dist
            }
            bolts.add(GlBolt(xyz, 3 + rnd.nextInt(3)))
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
        private const val HATCH_STEP = 0.02f                              // ~50 kadrų liukui atsidaryti
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
/** EGL konfigūracija su 4x MSAA (lygūs kraštai); jei nepalaikoma – be jos. */
class MsaaConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: javax.microedition.khronos.egl.EGL10,
                              display: javax.microedition.khronos.egl.EGLDisplay): EGLConfig {
        val E = javax.microedition.khronos.egl.EGL10.EGL_NONE
        val ms = intArrayOf(0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3025, 16,
            0x3040, 4, 0x3032, 1, 0x3031, 4, E)          // RGBA8, depth16, ES2, sample buffers=1, samples=4
        val plain = intArrayOf(0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3025, 16, 0x3040, 4, E)
        for (attrs in arrayOf(ms, plain)) {
            val num = IntArray(1)
            val cfg = arrayOfNulls<EGLConfig>(1)
            if (egl.eglChooseConfig(display, attrs, cfg, 1, num) && num[0] > 0 && cfg[0] != null) return cfg[0]!!
        }
        throw IllegalArgumentException("Nerasta EGL konfigūracija")
    }
}

class SkyDomeGL(context: Context) : GLSurfaceView(context), SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyroSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val renderer = GlobeRenderer(context.applicationContext, resources.displayMetrics.density)
    private val handler = Handler(Looper.getMainLooper())
    private val calPrefs = context.getSharedPreferences("sky_cal", Context.MODE_PRIVATE)
    private var offsetDeg = calPrefs.getFloat("offset", 0f)
    private var declDeg = calPrefs.getFloat("decl0", 0f)

    // ---- Orientacijos sintezė: giroskopas + gravitacija (komplementarus filtras), magnetometras – tik pradžiai ----
    /** Stabili šiaurė: po pradinio nustatymo posūkis (yaw) imamas tik iš giroskopo. */
    var stableNorth = calPrefs.getBoolean("stable_north", true)
        private set
    private val fR = DoubleArray(9)          // įrenginys -> pasaulis (ENU), eilutėmis, kaip getRotationMatrixFromVector
    private var fInit = false
    private var lastGyroNs = 0L
    private var gyroSteps = 0
    private val gUp = DoubleArray(3)         // gravitacijos kryptis įrenginio koordinatėmis (normuota)
    private var gValid = false
    private var spikeSinceNs = 0L            // magnetinio šuolio pradžia (ne stabiliame režime)
    private val tiltK = 0.02                 // 98 % giroskopas, 2 % akselerometras/gravitacija
    private val yawK = 0.02                  // ne stabiliame režime – lėta yaw korekcija iš rotation vector

    fun setCmb(on: Boolean) {
        renderer.cmbOn = on
        calPrefs.edit().putBoolean("cmb_bg", on).apply()
    }

    fun setStableNorth(on: Boolean) {
        stableNorth = on
        calPrefs.edit().putBoolean("stable_north", on).apply()
    }
    private var running = false
    private var manual = false
    private var lastRaw = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val resumeSensors = Runnable { manual = false }
    var onDoubleTapCb: (() -> Unit)? = null
    private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean { onDoubleTapCb?.invoke(); return true }
    })
    private var prevArc = doubleArrayOf(0.0, 0.0, 1.0)
    private var pinchLast = 0f
    private var pinchAngle = 0.0

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaConfigChooser())
        preserveEGLContextOnPause = true
        renderer.cmbOn = true                                     // žvaigždžių fonas visada (CMB pašalintas)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setSats(list: List<Sat>) { renderer.setSats(list) }

    fun setSouthUp(on: Boolean) = renderer.setSouthUpMode(on)

    fun setLocation(lat: Double, lon: Double, alt: Double) {
        renderer.setLocation(lat, lon)
        // Stabili šiaurė: deklinacija taikoma tik pirmą kartą, vėliau – įrašyta reikšmė
        if (stableNorth && calPrefs.contains("decl0")) {
            declDeg = calPrefs.getFloat("decl0", 0f)
            return
        }
        declDeg = try {
            GeomagneticField(lat.toFloat(), lon.toFloat(), alt.toFloat(), System.currentTimeMillis()).declination
        } catch (e: Exception) { 0f }
        if (stableNorth) calPrefs.edit().putFloat("decl0", declDeg).apply()
    }

    fun saveNorth() {
        offsetDeg += Math.toDegrees(renderer.northDeltaRad()).toFloat()
        // Įrašytas kampas ir deklinacija lieka – magnetometras jų nebeatnaujina
        calPrefs.edit().putFloat("offset", offsetDeg).putFloat("decl0", declDeg).putBoolean("north_saved", true).apply()
        renderer.resetUser()
        manual = false
        handler.removeCallbacks(resumeSensors)
    }

    fun resetNorth() {
        offsetDeg = 0f
        calPrefs.edit().putFloat("offset", 0f).remove("decl0").remove("north_saved").apply()
        fInit = false          // pradinė kryptis bus vėl paimta iš rotation vector
        renderer.resetUser()
        manual = false
    }

    fun start() {
        if (running) return
        running = true
        onResume()
        fInit = false; lastGyroNs = 0L; gValid = false
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gravSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        running = false
        manual = false
        handler.removeCallbacks(resumeSensors)
        sm.unregisterListener(this)
        onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> onRotationVector(event)
            Sensor.TYPE_GYROSCOPE -> onGyro(event)
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> onGravity(event)
        }
    }

    private fun onRotationVector(event: SensorEvent) {
        val rv = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rv, event.values)
        if (gyroSensor == null) {                      // be giroskopo – kaip anksčiau, tiesiai iš rotation vector
            for (i in 0..8) fR[i] = rv[i].toDouble()
            fInit = true
            publish()
            return
        }
        if (!fInit) {                                  // pradinė orientacija (vienintelis kartas, kai naudojamas magnetometras)
            for (i in 0..8) fR[i] = rv[i].toDouble()
            fInit = true
            publish()
            return
        }
        if (stableNorth) return                        // stabili šiaurė: magnetometro nebeklausome
        // Lėta yaw korekcija; trumpi magnetiniai šuoliai (>20°, trumpiau nei 2 s) ignoruojami
        // Δ = RV · Rᵀ – pasaulio posūkis nuo filtro iki rotation vector; imame jo dalį apie vertikalę
        val d = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var sum = 0.0
            for (k in 0..2) sum += rv[i * 3 + k] * fR[j * 3 + k]
            d[i * 3 + j] = sum
        }
        val yawErr = kotlin.math.atan2(d[3] - d[1], d[0] + d[4])
        if (kotlin.math.abs(yawErr) > Math.toRadians(20.0)) {
            if (spikeSinceNs == 0L) spikeSinceNs = event.timestamp
            if (event.timestamp - spikeSinceNs < 2_000_000_000L) return
        } else spikeSinceNs = 0L
        rotWorldZ(yawK * yawErr)
    }

    private fun onGyro(event: SensorEvent) {
        val ts = event.timestamp
        val prev = lastGyroNs
        lastGyroNs = ts
        if (!fInit || prev == 0L) return
        val dt = (ts - prev) / 1e9
        if (dt <= 0.0 || dt > 0.2) return
        val wx = event.values[0].toDouble(); val wy = event.values[1].toDouble(); val wz = event.values[2].toDouble()
        val w = kotlin.math.sqrt(wx * wx + wy * wy + wz * wz)
        if (w * dt > 1e-9) {
            // R ← R · exp([ω]·dt) (posūkis įrenginio koordinatėmis)
            val m = axisAngle(wx / w, wy / w, wz / w, w * dt)
            val o = DoubleArray(9)
            for (i in 0..2) for (j in 0..2) {
                var sum = 0.0
                for (k in 0..2) sum += fR[i * 3 + k] * m[k * 3 + j]
                o[i * 3 + j] = sum
            }
            o.copyInto(fR)
        }
        if (++gyroSteps % 50 == 0) orthonormalize()
        publish()
    }

    private fun onGravity(event: SensorEvent) {
        val x = event.values[0].toDouble(); val y = event.values[1].toDouble(); val z = event.values[2].toDouble()
        val n = kotlin.math.sqrt(x * x + y * y + z * z)
        if (n < 1e-3) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER && gValid) {
            // akselerometras – žemo dažnio filtras
            gUp[0] = 0.8 * gUp[0] + 0.2 * x / n; gUp[1] = 0.8 * gUp[1] + 0.2 * y / n; gUp[2] = 0.8 * gUp[2] + 0.2 * z / n
        } else {
            gUp[0] = x / n; gUp[1] = y / n; gUp[2] = z / n
        }
        gValid = true
        if (!fInit || gyroSensor == null) return
        // Pitch/roll korekcija: R·g turi rodyti į pasaulio viršų (0,0,1); posūkis tik apie horizontalią ašį
        val ux = fR[0] * gUp[0] + fR[1] * gUp[1] + fR[2] * gUp[2]
        val uy = fR[3] * gUp[0] + fR[4] * gUp[1] + fR[5] * gUp[2]
        val uz = fR[6] * gUp[0] + fR[7] * gUp[1] + fR[8] * gUp[2]
        val ax = uy; val ay = -ux                      // u × z
        val s = kotlin.math.sqrt(ax * ax + ay * ay)
        if (s < 1e-9) return
        val ang = kotlin.math.atan2(s, uz)
        val c = axisAngle(ax / s, ay / s, 0.0, tiltK * ang)
        val o = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var sum = 0.0
            for (k in 0..2) sum += c[i * 3 + k] * fR[k * 3 + j]
            o[i * 3 + j] = sum
        }
        o.copyInto(fR)
    }

    /** Posūkis apie pasaulio vertikalę (tik yaw). */
    private fun rotWorldZ(a: Double) {
        val m = axisAngle(0.0, 0.0, 1.0, a)
        val o = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var sum = 0.0
            for (k in 0..2) sum += m[i * 3 + k] * fR[k * 3 + j]
            o[i * 3 + j] = sum
        }
        o.copyInto(fR)
    }

    private fun axisAngle(x: Double, y: Double, z: Double, th: Double): DoubleArray {
        val c = cos(th); val s = sin(th); val t = 1 - c
        return doubleArrayOf(
            t * x * x + c, t * x * y - s * z, t * x * z + s * y,
            t * x * y + s * z, t * y * y + c, t * y * z - s * x,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c)
    }

    /** Gram–Schmidt eilutėms, kad skaitinės paklaidos nesikauptų. */
    private fun orthonormalize() {
        fun norm(i: Int) {
            val l = kotlin.math.sqrt(fR[i] * fR[i] + fR[i + 1] * fR[i + 1] + fR[i + 2] * fR[i + 2]).coerceAtLeast(1e-12)
            fR[i] /= l; fR[i + 1] /= l; fR[i + 2] /= l
        }
        norm(0)
        val d = fR[0] * fR[3] + fR[1] * fR[4] + fR[2] * fR[5]
        fR[3] -= d * fR[0]; fR[4] -= d * fR[1]; fR[5] -= d * fR[2]
        norm(3)
        fR[6] = fR[1] * fR[5] - fR[2] * fR[4]
        fR[7] = fR[2] * fR[3] - fR[0] * fR[5]
        fR[8] = fR[0] * fR[4] - fR[1] * fR[3]
    }

    /** Filtro orientacija -> ekrano pasukimas -> šiaurės korekcija -> modelis (kaip anksčiau). */
    private fun publish() {
        val raw = FloatArray(9) { fR[it].toFloat() }
        val out = FloatArray(9)
        when (wm.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, out)
            Surface.ROTATION_180 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y, out)
            Surface.ROTATION_270 -> SensorManager.remapCoordinateSystem(raw, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X, out)
            else -> raw.copyInto(out)
        }
        val total = Math.toRadians((offsetDeg + declDeg).toDouble())
        val c = cos(total).toFloat(); val sn = sin(total).toFloat()
        val fixed = mul3(floatArrayOf(c, -sn, 0f, sn, c, 0f, 0f, 0f, 1f), out)
        lastRaw = fixed
        if (!manual) renderer.setRotation(fixed)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun arcMap(x: Float, y: Float): DoubleArray {
        val w = width.toDouble().coerceAtLeast(1.0)
        val h = height.toDouble().coerceAtLeast(1.0)
        val sz = kotlin.math.min(w, h)
        val nx = (2.0 * x - w) / sz
        val ny = (h - 2.0 * y) / sz
        val r2 = nx * nx + ny * ny
        return if (r2 <= 1.0) {
            doubleArrayOf(nx, ny, kotlin.math.sqrt(1.0 - r2))
        } else {
            val l = kotlin.math.sqrt(r2)
            doubleArrayOf(nx / l, ny / l, 0.0)
        }
    }

    private fun pinchAng(e: MotionEvent): Double =
        kotlin.math.atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())

    private fun pinchDist(e: MotionEvent): Float {
        val dx = e.getX(0) - e.getX(1)
        val dy = e.getY(0) - e.getY(1)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gd.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(resumeSensors)
                manual = true
                renderer.setDragging(true)
                prevArc = arcMap(e.x, e.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    pinchLast = pinchDist(e)
                    pinchAngle = pinchAng(e)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount >= 2) {
                    val d = pinchDist(e)
                    if (pinchLast > 1f && d > 1f) renderer.zoomBy(d / pinchLast)
                    pinchLast = d
                    val ang = pinchAng(e)
                    var da = ang - pinchAngle
                    if (da > Math.PI) da -= 2 * Math.PI
                    if (da < -Math.PI) da += 2 * Math.PI
                    renderer.rollBy(da)
                    pinchAngle = ang
                } else {
                    val cur = arcMap(e.x, e.y)
                    renderer.arcRotate(prevArc, cur)
                    prevArc = cur
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                pinchLast = 0f
                pinchAngle = 0.0
                val remaining = if (e.actionIndex == 0) 1 else 0
                if (remaining < e.pointerCount) prevArc = arcMap(e.getX(remaining), e.getY(remaining))
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                renderer.setDragging(false)
                handler.removeCallbacks(resumeSensors)
                handler.postDelayed(resumeSensors, 60_000L)
                return true
            }
        }
        return super.onTouchEvent(e)
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

class FloatingSky(context: Context) : FrameLayout(context) {
    val dome = SkyDomeGL(context)
    init { addView(dome, FrameLayout.LayoutParams(300, 300)) }
}

private class GlobeRenderer(private val appCtx: Context, private val density: Float) : GLSurfaceView.Renderer {

    @Volatile private var rot = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    @Volatile private var sats: List<Sat> = emptyList()
    fun setRotation(r: FloatArray) { rot = r.copyOf() }
    // Kadro bazė: ENU stebėtojo ašys (e, n, u) sideralinėje (I) erdvėje, stebėtojo vieta ant paviršiaus
    private var fE = DoubleArray(3)
    private var fN = DoubleArray(3)
    private var fU = DoubleArray(3)
    private var fQ = DoubleArray(9)
    private var fP = FloatArray(3)
    private var fG = 0.0
    private val mvpI = FloatArray(16)
    private val lMat = FloatArray(16)
    private var eyeI = floatArrayOf(0f, 0f, 1f)

    private class SatI(val sat: Sat, val pos: FloatArray)
    @Volatile private var satsI: List<SatI> = emptyList()

    private fun frameNow() {
        val d = System.currentTimeMillis() / 86400000.0 + 2440587.5 - 2451545.0
        var gm = (280.46061837 + 360.98564736629 * d) % 360.0
        if (gm < 0) gm += 360.0
        fG = Math.toRadians(gm)
        val la = Math.toRadians(obsLat)
        val lst = fG + Math.toRadians(obsLon)
        fE = doubleArrayOf(-sin(lst), cos(lst), 0.0)
        fN = doubleArrayOf(-sin(la) * cos(lst), -sin(la) * sin(lst), cos(la))
        fU = doubleArrayOf(cos(la) * cos(lst), cos(la) * sin(lst), sin(la))
        fQ = doubleArrayOf(fE[0], fN[0], fU[0], fE[1], fN[1], fU[1], fE[2], fN[2], fU[2])
        // Dangaus skliauto centras – Žemės centras, kad gaublys būtų kupolo viduryje
        // (stebėtojo vietą rodo raudonas smeigtukas ant paviršiaus)
        fP = floatArrayOf(0f, 0f, 0f)
    }

    // Kiekvieno palydovo C/N0 istorija (kaip GNSS Logger „CN0 vs Time“): ~120 s
    private val satHist = LinkedHashMap<String, ArrayList<Float>>()
    private var ticks = 0
    private val winN = 120
    fun addSamples(list: List<Sat>) {
        synchronized(satHist) {
            ticks++
            val seen = HashMap<String, Float>()
            for (x in list) seen[x.const + x.svid] = x.cn0
            for (k in seen.keys) if (!satHist.containsKey(k)) {
                satHist[k] = ArrayList<Float>().apply { repeat(minOf(ticks - 1, winN)) { add(Float.NaN) } }
            }
            for ((k, h) in satHist) {
                h.add(seen[k] ?: Float.NaN)
                if (h.size > winN) h.removeAt(0)
            }
            satHist.entries.removeAll { e -> e.value.all { it.isNaN() } }
        }
    }

    fun setSats(l: List<Sat>) {
        sats = l
        frameNow()
        val out = ArrayList<SatI>()
        for (s in l) {
            if (s.elev < 0f) continue
            val el = Math.toRadians(s.elev.toDouble().coerceIn(0.0, 90.0))
            val az = Math.toRadians(s.az.toDouble())
            val re = 6371.0
            val rs = re + altKm(s.const)
            val se = sin(el)
            val rho = -re * se + kotlin.math.sqrt(re * re * se * se + rs * rs - re * re)
            val dist = rho * EARTH_R / re
            val dx = cos(el) * sin(az); val dy = cos(el) * cos(az); val dz = sin(el)
            out.add(SatI(s, floatArrayOf(
                (fP[0] + dist * (dx * fE[0] + dy * fN[0] + dz * fU[0])).toFloat(),
                (fP[1] + dist * (dx * fE[1] + dy * fN[1] + dz * fU[1])).toFloat(),
                (fP[2] + dist * (dx * fE[2] + dy * fN[2] + dz * fU[2])).toFloat())))
        }
        satsI = out
        recordTrails(out)
    }

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
        satProg = program(SAT_VS, SAT_FS)
        buildSatMesh()
        dotProg = program(COLOR_VS, DOT_FS)
        texProg = program(TEX_VS, TEX_FS)

        val up = meshSphere(0.0, 90.0, 72, 192, 1.0)
        upperBuf = floatBuf(up); upperN = up.size / 3
        val low = meshSphere(-90.0, 0.0, 48, 192, 1.0)
        lowerBuf = floatBuf(low); lowerN = low.size / 3
        val grid = buildGrid(1.004)
        gridBuf = floatBuf(grid); gridN = grid.size / 3
        val hz = buildHorizon(1.006)
        horizonBuf = floatBuf(hz); horizonN = hz.size / 3

        // GL kontekstas atkuriamas po išėjimo/įėjimo – senos tekstūrų ID nebegalioja, todėl viską perkuriame
        prnTex.clear()
        monthTex.clear()
        labelTex.clear()
        wideLabels.clear()
        atmoProg = 0   // senas kontekstas – programą reikia sukurti iš naujo
        sunProg = 0
        glowProg = 0
        buildEarth()
        loadCmb()
        starTex = makeStarTexture()
        sunTex = makeSunTexture()
        sunImgTex = 0
        try {
            appCtx.assets.open("sun.jpg").use { ins ->
                val bmp = android.graphics.BitmapFactory.decodeStream(ins)
                if (bmp != null) sunImgTex = uploadTexture(bmp)
            }
        } catch (e: Exception) {
            android.util.Log.w("GnssApp", "sun.jpg nerastas: rodoma programiškai nupiešta Saulė")
        }
        for (d in 0 until 360 step 30) labelTex[labelOf(d)] = makeTexture(labelOf(d), 0xFFFFFFFF.toInt(), if (d % 90 == 0) 170 else 96)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        val aspect = if (h == 0) 1f else w.toFloat() / h
        aspectNow = aspect
        Matrix.perspectiveM(proj, 0, 40f, aspect, 0.1f, 20f)
        // Vaizdo centras pastumtas žemyn, kad viršutinė kupolo dalis tilptų į ekraną
        proj[9] = 0.3f
        Matrix.setIdentityM(view, 0)
        Matrix.translateM(view, 0, 0f, 0f, -zoom)         // kamera išorėje, žiūri į gaublį
        Matrix.multiplyMM(pv, 0, proj, 0, view, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        // Fonas – ta pati C/N0 spalva kaip ekrano fone ir burbule (švelniai pereinama)
        val bgTarget = ColorUtils.blendARGB(BG_NEUTRAL, lastTint, 0.45f)
        bgNow = ColorUtils.blendARGB(bgNow, bgTarget, 0.05f)
        GLES20.glClearColor(Color.red(bgNow) / 255f, Color.green(bgNow) / 255f, Color.blue(bgNow) / 255f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val t = (System.nanoTime() - t0) / 1e9f

        // Zoom: kameros atstumas kiekvieną kadrą; artima riba mažėja priartinus (kad Žemė nebūtų nukirpta)
        val near = ((zoom - EARTH_R) * 0.3f).coerceIn(0.003f, 0.1f)
        Matrix.perspectiveM(proj, 0, 40f, aspectNow, near, 20f)
        proj[9] = 0.3f
        Matrix.setIdentityM(view, 0)
        Matrix.translateM(view, 0, 0f, 0f, -zoom)
        Matrix.multiplyMM(pv, 0, proj, 0, view, 0)
        // Kadras: įrenginio orientacija sideralinėje erdvėje (A = Q * rot)
        frameNow()
        val r = rot
        val aM = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var sum = 0.0
            for (k in 0..2) sum += fQ[i * 3 + k] * r[k * 3 + j]
            aM[i * 3 + j] = sum
        }
        java.util.Arrays.fill(mv, 0f)
        for (c in 0..2) for (rr in 0..2) mv[c * 4 + rr] = aM[c * 3 + rr].toFloat()
        mv[15] = 1f
        if (!dragging && spinAngle > 1e-4) {
            userR = mul3d(axisAngleMat(spinAxis, spinAngle), userR)
            spinAngle *= 0.92
        }
        val u4 = FloatArray(16)
        for (c in 0..2) for (rr in 0..2) u4[c * 4 + rr] = userR[rr * 3 + c].toFloat()
        u4[15] = 1f
        val mvU = FloatArray(16)
        Matrix.multiplyMM(mvU, 0, u4, 0, mv, 0)
        Matrix.multiplyMM(mvpI, 0, pv, 0, mvU, 0)          // Žemė, palydovai (sideralinė erdvė)
        // Stebėtojo lokali sistema (dangaus kupolas, horizontas): ENU -> I + vieta
        java.util.Arrays.fill(lMat, 0f)
        for (c in 0..2) for (rr in 0..2) lMat[c * 4 + rr] = fQ[rr * 3 + c].toFloat()
        lMat[12] = fP[0]; lMat[13] = fP[1]; lMat[14] = fP[2]; lMat[15] = 1f
        Matrix.multiplyMM(mvp, 0, mvpI, 0, lMat, 0)        // kupolas, horizontas, žymos
        eyeI = floatArrayOf(mvU[2], mvU[6], mvU[10])
        // Kameros kryptis pasaulio koordinatėse (planšetės +Z)
        val eye = floatArrayOf(
            (fE[0] * eyeI[0] + fE[1] * eyeI[1] + fE[2] * eyeI[2]).toFloat(),
            (fN[0] * eyeI[0] + fN[1] * eyeI[1] + fN[2] * eyeI[2]).toFloat(),
            (fU[0] * eyeI[0] + fU[1] * eyeI[1] + fU[2] * eyeI[2]).toFloat())

        // Signalo kokybė -> pulsavimo stiprumas
        val used = sats.filter { it.used }
        val avgCn0 = if (used.isNotEmpty()) used.map { it.cn0 }.average().toFloat() else 0f
        val peakCn0 = top4Avg(sats) ?: 0f
        val beat = ((avgCn0 - 15f) / 25f).coerceIn(0.2f, 1f)
        val tint = signalTint(if (used.isNotEmpty()) avgCn0 else null, if (used.isNotEmpty()) peakCn0 else null)
        lastTint = tint

        // Žvaigždžių fonas – didelė sfera pusiaujo (RA/Dec) sistemoje, sukasi kartu su žvaigždėmis
        drawCmb()

        // Dangaus skliauto kupolas (viršutinis ir apatinis) nebepiešiamas
        GLES20.glDepthMask(true)

        // 2b) Žemė gaublio viduryje (pasvirusi į Polaris, sukasi realiu laiku)
        drawEarth()
        drawEquatorRing(t)
        drawMoon()
        drawTrails()


        // Žymos ir palydovai piešiami be gylio testo (visada matomi priekyje)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        // 5) Laipsnių žymos ant horizonto
        for (d in 0 until 360 step 30) {
            val tex = labelTex[labelOf(d)] ?: continue
            val a = Math.toRadians(d.toDouble())
            val c = floatArrayOf((1.04 * sin(a)).toFloat(), (1.04 * cos(a)).toFloat(), 0f)
            if (dot3(c, eye) < 0.05f) continue
            val col = when (d) { 0 -> floatArrayOf(0.94f, 0.27f, 0.27f); 90, 180, 270 -> floatArrayOf(0.98f, 0.8f, 0.08f); else -> floatArrayOf(0.85f, 0.87f, 0.9f) }
            val card = d % 90 == 0
            billboard(c, eye, if (card) 0.18f else 0.09f, if (card) 0.09f else 0.045f, tex, col[0], col[1], col[2], 1f)
        }

        // 6) Palydovai: geras signalas žybsi kaip žvaigždės
        System.arraycopy(mvpI, 0, mvp, 0, 16)

        // Žvaigždės, Oriono linijos, dangaus pusiaujas ir ekliptika (I erdvė, tik virš horizonto)
        drawSky()

        // Saulė tikroje dangaus kryptyje nuo stebėtojo (švytintis diskas)
        run {
            val sd = sunDir()
            val sp = floatArrayOf(
                (fP[0] + sd[0] * SUN_DIST).toFloat(), (fP[1] + sd[1] * SUN_DIST).toFloat(), (fP[2] + sd[2] * SUN_DIST).toFloat())
            val dd = sp[0] * eyeI[0] + sp[1] * eyeI[1] + sp[2] * eyeI[2]
            val qx = sp[0] - dd * eyeI[0]; val qy = sp[1] - dd * eyeI[1]; val qz = sp[2] - dd * eyeI[2]
            val hidden = dd < 0f && kotlin.math.sqrt(qx * qx + qy * qy + qz * qz) < EARTH_R
            if (!hidden && sunTex != 0) {
                val pulse = 1f + 0.04f * kotlin.math.sin(t * 1.5f)
                drawSun3D(sp, t)
            }
        }
        val pin = pinWorld()
        for (si in satsI) {
            val sat = si.sat
            val p = si.pos
            // Už Žemės: kamera -> palydovas kerta Žemės rutulį – nerodome
            val dd = p[0] * eyeI[0] + p[1] * eyeI[1] + p[2] * eyeI[2]
            val px2 = p[0] - dd * eyeI[0]; val py2 = p[1] - dd * eyeI[1]; val pz2 = p[2] - dd * eyeI[2]
            if (dd < 0f && kotlin.math.sqrt(px2 * px2 + py2 * py2 + pz2 * pz2) < EARTH_R) continue
            val col = constRgb(sat.const)
            val usedK = if (sat.used) 1f else 0.4f
            // Švytėjimas: 0 ties 20 dB-Hz, didžiausias ties 45 dB-Hz
            val glow = ((sat.cn0 - 20f) / 25f).coerceIn(0f, 1f)
            drawLaser(p, pin, sat.cn0, sat.svid, t)
            drawSatModel(p, col, glow, usedK, sat.const)
            if (sat.cn0 >= 30f) {
                val pn = prnTex.getOrPut(sat.const + sat.svid) { makeTexture("${sat.svid}", 0xFFFFFFFF.toInt(), 64) }
                billboard(floatArrayOf(p[0], p[1], p[2] + 0.07f), eyeI, 0.05f, 0.025f, pn, col[0], col[1], col[2], 0.9f * usedK)
            }
        }

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    // ---------------- ŽVAIGŽDŽIŲ FONAS (vietoj CMB) ----------------
    @Volatile var cmbOn = true
    @Volatile var cmbAlpha = 1f
    private var cmbTex = 0
    private var cmbProg = 0
    private val cmbModel = FloatArray(16)
    private val cmbMvp = FloatArray(16)

    /** assets/stars_bg.jpg – lygiakampė projekcija RA/Dec (u: RA −180°..180°, v: Dec +90°..−90°), perskaičiuota iš galaktinių koordinačių. */
    private fun loadCmb() {
        cmbTex = 0
        cmbProg = program(CMB_VS, CMB_FS)
        try {
            appCtx.assets.open("stars_bg.jpg").use { ins ->
                val bmp = android.graphics.BitmapFactory.decodeStream(ins)
                if (bmp != null) {
                    cmbTex = uploadTexture(bmp)
                    android.util.Log.i("GnssApp", "Žvaigždžių fonas įkeltas: ${bmp.width}x${bmp.height}")
                } else android.util.Log.w("GnssApp", "stars_bg.jpg neskaitomas – naudojamas paprastas fonas")
            }
        } catch (e: Exception) {
            android.util.Log.w("GnssApp", "stars_bg.jpg nerastas – naudojamas paprastas fonas")
        }
    }

    /** Sfera R = 50 aplink stebėtoją (fP), ta pati transformacija kaip žvaigždžių (mvpI); atskira projekcija su tolima riba. */
    private fun drawCmb() {
        if (!cmbOn || cmbTex == 0 || earthPos == null || earthUv == null) return
        val inv = FloatArray(16)
        if (!Matrix.invertM(inv, 0, proj, 0)) return
        val vm = FloatArray(16)
        Matrix.multiplyMM(vm, 0, inv, 0, mvpI, 0)                 // vaizdas × sideralinė erdvė
        val pFar = proj.copyOf()
        val n = 0.1f; val f = 200f
        pFar[10] = -(f + n) / (f - n); pFar[14] = -2f * f * n / (f - n)
        val k = 50f / EARTH_R                                     // Žemės rutulio tinklelis (0,25) -> 50
        java.util.Arrays.fill(cmbModel, 0f)
        cmbModel[0] = k; cmbModel[5] = k; cmbModel[10] = k
        cmbModel[12] = fP[0]; cmbModel[13] = fP[1]; cmbModel[14] = fP[2]; cmbModel[15] = 1f
        val tmp = FloatArray(16)
        Matrix.multiplyMM(tmp, 0, vm, 0, cmbModel, 0)
        Matrix.multiplyMM(cmbMvp, 0, pFar, 0, tmp, 0)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(cmbProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(cmbProg, "uMVP"), 1, false, cmbMvp, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(cmbProg, "uAlpha"), cmbAlpha)
        resetAttribs()
        val lp = GLES20.glGetAttribLocation(cmbProg, "aPos")
        val lu = GLES20.glGetAttribLocation(cmbProg, "aUV")
        GLES20.glEnableVertexAttribArray(lp); GLES20.glEnableVertexAttribArray(lu)
        earthPos!!.position(0); earthUv!!.position(0)
        GLES20.glVertexAttribPointer(lp, 3, GLES20.GL_FLOAT, false, 0, earthPos)
        GLES20.glVertexAttribPointer(lu, 2, GLES20.GL_FLOAT, false, 0, earthUv)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cmbTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cmbProg, "uTex"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, earthCount)
        GLES20.glDisableVertexAttribArray(lu)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    // ---------------- ŽVAIGŽDŽIŲ KATALOGAS, PUSIAUJAS, EKLIPTIKA ----------------
    /** RA (val.), Dec (laipsn.), regimasis ryškis, spalva. J2000. */
    private class StarCat(val name: String, val raH: Double, val decDeg: Double, val mag: Float, val rgb: FloatArray) {
        val dir: FloatArray = run {
            val ra = Math.toRadians(raH * 15.0)
            val dec = Math.toRadians(decDeg)
            floatArrayOf((cos(dec) * cos(ra)).toFloat(), (cos(dec) * sin(ra)).toFloat(), sin(dec).toFloat())
        }
    }

    // Vienas RA/Dec -> I konvertavimas ir mvpI visiems dangaus objektams.
    private val starR = 3.0                 // žvaigždės – tolimas fonas
    private val ringR get() = SUN_DIST      // pusiaujas ir ekliptika – Saulės atstumu, kad Saulė gulėtų ant ekliptikos
    private var eclCheckMs = 0L
    private val starCatalog = listOf(
        StarCat("Polaris", hms(2, 31, 49.0), dms(89, 15, 51.0), 1.98f, floatArrayOf(1f, 0.97f, 0.85f)),
        StarCat("Betelgeuse", hms(5, 55, 10.3), dms(7, 24, 25.0), 0.50f, floatArrayOf(1f, 0.6f, 0.35f)),
        StarCat("Bellatrix", hms(5, 25, 7.9), dms(6, 20, 59.0), 1.64f, floatArrayOf(0.8f, 0.88f, 1f)),
        StarCat("Mintaka", hms(5, 32, 0.4), -dms(0, 17, 57.0), 2.23f, floatArrayOf(0.8f, 0.88f, 1f)),
        StarCat("Alnilam", hms(5, 36, 12.8), -dms(1, 12, 7.0), 1.69f, floatArrayOf(0.8f, 0.88f, 1f)),
        StarCat("Alnitak", hms(5, 40, 45.5), -dms(1, 56, 34.0), 1.77f, floatArrayOf(0.8f, 0.88f, 1f)),
        StarCat("Saiph", hms(5, 47, 45.4), -dms(9, 40, 11.0), 2.06f, floatArrayOf(0.8f, 0.88f, 1f)),
        StarCat("Rigel", hms(5, 14, 32.3), -dms(8, 12, 6.0), 0.13f, floatArrayOf(0.75f, 0.85f, 1f)),
    )
    private val orionLines = listOf(
        "Betelgeuse" to "Bellatrix", "Bellatrix" to "Mintaka", "Mintaka" to "Alnilam", "Alnilam" to "Alnitak",
        "Alnitak" to "Saiph", "Saiph" to "Rigel", "Rigel" to "Mintaka", "Betelgeuse" to "Alnitak")

    private fun hms(h: Int, m: Int, s: Double) = h + m / 60.0 + s / 3600.0
    private fun dms(d: Int, m: Int, s: Double) = d + m / 60.0 + s / 3600.0

    /** Taškas I erdvėje: fP + dir × r. */
    private fun skyPt(d: FloatArray, r: Double) = floatArrayOf(
        (fP[0] + d[0] * r).toFloat(), (fP[1] + d[1] * r).toFloat(), (fP[2] + d[2] * r).toFloat())

    /** Ekliptinė platuma (laipsniais) krypčiai I erdvėje; ekliptikos polius = eclToEq(0, π/2). */
    private fun eclLatDeg(d: DoubleArray): Double {
        val pole = eclToEq(0.0, Math.PI / 2)
        val dot = (d[0] * pole[0] + d[1] * pole[1] + d[2] * pole[2]) /
            kotlin.math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]).coerceAtLeast(1e-12)
        return Math.toDegrees(kotlin.math.asin(dot.coerceIn(-1.0, 1.0)))
    }

    /** Kiekvieną kadrą: Saulė turi gulėti ant ekliptikos, Mėnulis – ±5° (5,3° su paklaida). */
    private fun checkEcliptic() {
        val sunB = eclLatDeg(sunDir())
        val moonB = eclLatDeg(moonDir())
        val ok = kotlin.math.abs(sunB) < 0.05 && kotlin.math.abs(moonB) <= 5.3
        val now = System.currentTimeMillis()
        if (!ok && now - eclCheckMs > 10_000L) {
            eclCheckMs = now
            android.util.Log.w("GnssApp", "Ekliptikos patikra: Saulė β=%.3f°, Mėnulis β=%.2f°".format(sunB, moonB))
        }
    }

    /** Aukštis > 0: taško kryptis nuo stebėtojo (fP) turi teigiamą projekciją į zenitą fU. */
    private fun aboveHorizon(p: FloatArray): Boolean =
        (p[0] - fP[0]) * fU[0] + (p[1] - fP[1]) * fU[1] + (p[2] - fP[2]) * fU[2] > 0.0

    /** Segmentai tarp gretimų taškų, abu virš horizonto. */
    private fun visibleSegments(pts: List<FloatArray>, closed: Boolean): FloatArray {
        val out = ArrayList<Float>()
        val n = if (closed) pts.size else pts.size - 1
        for (i in 0 until n) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            if (!aboveHorizon(a) || !aboveHorizon(b)) continue
            out.add(a[0]); out.add(a[1]); out.add(a[2]); out.add(b[0]); out.add(b[1]); out.add(b[2])
        }
        return out.toFloatArray()
    }

    private fun drawLines(seg: FloatArray, r: Float, g: Float, b: Float, a: Float) {
        if (seg.isEmpty()) return
        GLES20.glUseProgram(colorProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uMVP"), 1, false, mvpI, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"), r, g, b, a)
        bindPos(colorProg, floatBuf(seg))
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, seg.size / 3)
    }

    private val wideLabels = HashMap<String, Pair<Int, Float>>()

    /** Teksto tekstūra pagal teksto plotį: (tekstūra, plotis/aukštis). */
    private fun wideLabel(text: String): Pair<Int, Float> = wideLabels.getOrPut(text) {
        val h = 64
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = h * 0.7f; textAlign = Paint.Align.CENTER }
        val w = (p.measureText(text) + h * 0.4f).toInt().coerceAtLeast(h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(text, w / 2f, h * 0.75f, p)
        uploadTexture(bmp) to (w.toFloat() / h)
    }

    /** Žiedo pavadinimas – ties labiausiai į kamerą atsuktu matomu žiedo tašku. */
    private fun ringLabel(pts: List<FloatArray>, text: String, r: Float, g: Float, b: Float) {
        var best: FloatArray? = null
        var bestDot = -1e9f
        for (q in pts) {
            if (!aboveHorizon(q)) continue
            val d = q[0] * eyeI[0] + q[1] * eyeI[1] + q[2] * eyeI[2]
            if (d > bestDot) { bestDot = d; best = q }
        }
        val q = best ?: return
        val (tex, aspect) = wideLabel(text)
        val h = 0.07f
        billboard(floatArrayOf(q[0] * 1.04f, q[1] * 1.04f, q[2] * 1.04f), eyeI, h * aspect, h, tex, r, g, b, 0.95f)
    }

    /** Saulės ekliptinė ilguma (rad) – ta pati formulė kaip sunDir(). */
    private val monthTex = HashMap<Int, Int>()
    private val MONTHS_LT = arrayOf("Sau", "Vas", "Kov", "Bal", "Geg", "Bir", "Lie", "Rgp", "Rgs", "Spa", "Lap", "Gru")

    private fun sunEclLon(): Double = sunEclLonAt(jdDays())

    /** Saulės ekliptinė ilguma (rad) dienai d nuo J2000. */
    private fun sunEclLonAt(d: Double): Double {
        val m = Math.toRadians(357.5291 + 0.98560028 * d)
        val c = Math.toRadians(1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        return m + c + Math.toRadians(102.9372) + Math.PI
    }

    /**
     * Žemės metinė orbita aplink Saulę geocentriniame vaizde: elipsė ekliptikos plokštumoje (eclToEq, 23,4397°),
     * centre – Saulė, e = 0,0167, perihelio ilguma 102,9372°. Mastelis parinktas taip, kad Žemė (scenos centras)
     * šiuo metu gulėtų ant orbitos, o Saulė būtų SUN_DIST atstumu. Piešiama per mvpI.
     */
    private fun drawEarthOrbit() {
        val ecc = 0.0167
        val peri = Math.toRadians(102.9372)
        val lEarth = sunEclLon() + Math.PI                  // Žemės heliocentrinė ilguma
        val rNowUnit = (1 - ecc * ecc) / (1 + ecc * cos(lEarth - peri))
        val a = SUN_DIST / rNowUnit
        val sd = sunDir()
        val sx = fP[0] + sd[0] * SUN_DIST; val sy = fP[1] + sd[1] * SUN_DIST; val sz = fP[2] + sd[2] * SUN_DIST
        val n = 360
        val pts = ArrayList<FloatArray>(n)
        for (i in 0 until n) {
            val nu = 2.0 * Math.PI * i / n                  // tikroji anomalija
            val r = a * (1 - ecc * ecc) / (1 + ecc * cos(nu))
            val d = eclToEq(nu + peri, 0.0)
            pts.add(floatArrayOf((sx + d[0] * r).toFloat(), (sy + d[1] * r).toFloat(), (sz + d[2] * r).toFloat()))
        }
        val seg = FloatArray(n * 6)
        var k = 0
        for (i in 0 until n) {
            val p0 = pts[i]; val p1 = pts[(i + 1) % n]
            seg[k++] = p0[0]; seg[k++] = p0[1]; seg[k++] = p0[2]; seg[k++] = p1[0]; seg[k++] = p1[1]; seg[k++] = p1[2]
        }
        // Mėnesių žymės: Žemės vieta orbitoje kiekvieno mėnesio 1 d. (brūkšnelis skersai orbitos ir pavadinimas)
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        val year = cal.get(java.util.Calendar.YEAR); val monthNow = cal.get(java.util.Calendar.MONTH)
        val ticks = FloatArray(12 * 6)
        val labels = ArrayList<Pair<FloatArray, Int>>(12)
        for (mo in 0 until 12) {
            cal.clear(); cal.set(year, mo, 1, 0, 0, 0)
            val dJ = cal.timeInMillis / 86400000.0 + 2440587.5 - 2451545.0
            val lon = sunEclLonAt(dJ) + Math.PI                 // Žemės heliocentrinė ilguma mėnesio 1 d.
            val r = a * (1 - ecc * ecc) / (1 + ecc * cos(lon - peri))
            val d = eclToEq(lon, 0.0)
            fun at(k: Double) = floatArrayOf((sx + d[0] * r * k).toFloat(), (sy + d[1] * r * k).toFloat(), (sz + d[2] * r * k).toFloat())
            val p0 = at(0.975); val p1 = at(1.025)
            System.arraycopy(p0, 0, ticks, mo * 6, 3); System.arraycopy(p1, 0, ticks, mo * 6 + 3, 3)
            labels.add(at(1.07) to mo)
        }
        // Su gylio testu: Žemės rutulys uždengia orbitos dalį, einančią per jį ir už jo
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        drawLines(seg, 1f, 0.82f, 0.3f, 0.75f)
        drawLines(ticks, 1f, 0.9f, 0.5f, 0.95f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        for ((p, mo) in labels) {
            val tex = monthTex.getOrPut(mo) { makeTexture(MONTHS_LT[mo], 0xFFFFFFFF.toInt(), 64) }
            // einamasis mėnuo (Žemė dabar tarp šios ir kitos žymės) – ryškesnis
            val cur = mo == monthNow
            billboard(p, eyeI, if (cur) 0.2f else 0.16f, if (cur) 0.1f else 0.08f, tex, 1f, if (cur) 1f else 0.85f, if (cur) 1f else 0.45f, if (cur) 1f else 0.8f)
        }
    }

    private fun drawSky() {
        checkEcliptic()
        // Žemės orbita aplink Saulę (dangaus pusiaujo žiedas pašalintas)
        GLES20.glLineWidth(1.5f * density)
        drawEarthOrbit()

        // Oriono linijos
        val pos = HashMap<String, FloatArray>()
        for (st in starCatalog) pos[st.name] = skyPt(st.dir, starR)
        // Po horizontu – blankiai (orientacijai), virš – ryškiai
        val seg = ArrayList<Float>()
        val segDim = ArrayList<Float>()
        for ((a, b) in orionLines) {
            val pa = pos[a] ?: continue; val pb = pos[b] ?: continue
            val dst = if (aboveHorizon(pa) && aboveHorizon(pb)) seg else segDim
            dst.add(pa[0]); dst.add(pa[1]); dst.add(pa[2]); dst.add(pb[0]); dst.add(pb[1]); dst.add(pb[2])
        }
        drawLines(segDim.toFloatArray(), 0.7f, 0.8f, 1f, 0.15f)
        drawLines(seg.toFloatArray(), 0.7f, 0.8f, 1f, 0.5f)
        GLES20.glLineWidth(1f)

        // Žvaigždės: dydis ir ryškumas pagal regimąjį ryškį (mažesnis – ryškesnė)
        if (starTex == 0) return
        for (st in starCatalog) {
            val p = pos[st.name]!!
            val above = aboveHorizon(p)
            val k = ((2.5f - st.mag) / 2.4f).coerceIn(0f, 1f)          // 0..1
            val size = (0.035f + 0.05f * k) * (if (above) 1f else 0.7f) * (starR / 1.5).toFloat()   // toliau – didesnės, kad regimas dydis nesikeistų
            val alpha = (0.55f + 0.45f * k) * (if (above) 1f else 0.3f)   // po horizontu – blanki
            billboard(p, eyeI, size, size, starTex, st.rgb[0], st.rgb[1], st.rgb[2], alpha)
            billboard(p, eyeI, size * 0.45f, size * 0.45f, starTex, 1f, 1f, 1f, alpha)
        }
    }

    // ---------------- PALYDOVŲ 3D MODELIAI (korpusas, sekančios Saulę baterijos, antenos, variklis) ----------------
    private val satSize = 0.12f            // palydovo dydis (baterijų mojis), scenos vnt.
    private var satProg = 0
    private var satPos: FloatBuffer? = null
    private var satNrm: FloatBuffer? = null
    private val satModel = FloatArray(16)
    private val satWing = FloatArray(16)
    private val satMvp = FloatArray(16)

    /** Tinklo dalis: pradžia, viršūnių skaičius, medžiaga (MAT_*), ar sukasi su baterijomis. */
    private class SatPart(val start: Int, val count: Int, val mat: Int, val wing: Boolean)
    /** Variantai pagal konsteliaciją: 0 GPS, 1 GLONASS, 2 Galileo, 3 BeiDou. */
    private val satVariants = ArrayList<List<SatPart>>()

    /**
     * Modelio ašys: X – baterijų ašis, +Y – nuo Žemės, −Y – į Žemę (antenos), Z – skersai.
     * Baterijų mojis 2,5 vnt. Kiekvienas variantas turi savo korpusą, baterijų sekcijas ir antenų rinkinį.
     */
    private fun buildSatMesh() {
        satVariants.clear()
        val pos = ArrayList<Float>(); val nrm = ArrayList<Float>()
        var parts = ArrayList<SatPart>()
        var partStart = 0
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            pos.add(x); pos.add(y); pos.add(z); nrm.add(nx); nrm.add(ny); nrm.add(nz)
        }
        fun end(mat: Int, wing: Boolean = false) {
            val n = pos.size / 3
            if (n > partStart) parts.add(SatPart(partStart, n - partStart, mat, wing))
            partStart = n
        }
        fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
            val lo = floatArrayOf(x0, y0, z0); val hi = floatArrayOf(x1, y1, z1)
            for (ax in 0..2) for (sgn in intArrayOf(1, -1)) {
                val ua = (ax + 1) % 3; val wa = (ax + 2) % 3
                val n = FloatArray(3); n[ax] = sgn.toFloat()
                fun corner(uHi: Boolean, wHi: Boolean): FloatArray {
                    val c = FloatArray(3)
                    c[ax] = if (sgn > 0) hi[ax] else lo[ax]; c[ua] = if (uHi) hi[ua] else lo[ua]; c[wa] = if (wHi) hi[wa] else lo[wa]
                    return c
                }
                val q = arrayOf(corner(false, false), corner(true, false), corner(true, true), corner(false, true))
                for (idx in intArrayOf(0, 1, 2, 0, 2, 3)) v(q[idx][0], q[idx][1], q[idx][2], n[0], n[1], n[2])
            }
        }
        /** Nupjautas kūgis išilgai ašies ax nuo a0 (spindulys r0) iki a1 (r1), centras (cu, cw) kitose ašyse. */
        fun frustum(ax: Int, cu: Float, cw: Float, a0: Float, r0: Float, a1: Float, r1: Float, seg: Int, cap0: Boolean, cap1: Boolean) {
            val ua = (ax + 1) % 3; val wa = (ax + 2) % 3
            fun pt(a: Float, r: Float, c: Float, sn: Float): FloatArray {
                val o = FloatArray(3); o[ax] = a; o[ua] = cu + r * c; o[wa] = cw + r * sn; return o
            }
            val slope = (r0 - r1) / (a1 - a0)
            val nl = kotlin.math.sqrt(1f + slope * slope)
            fun nr(c: Float, sn: Float): FloatArray {
                val o = FloatArray(3); o[ax] = slope / nl; o[ua] = c / nl; o[wa] = sn / nl; return o
            }
            for (k in 0 until seg) {
                val t0 = 2.0 * Math.PI * k / seg; val t1 = 2.0 * Math.PI * (k + 1) / seg
                val c0 = cos(t0).toFloat(); val s0 = sin(t0).toFloat(); val c1 = cos(t1).toFloat(); val s1 = sin(t1).toFloat()
                val p00 = pt(a0, r0, c0, s0); val p01 = pt(a0, r0, c1, s1); val p10 = pt(a1, r1, c0, s0); val p11 = pt(a1, r1, c1, s1)
                val n0 = nr(c0, s0); val n1 = nr(c1, s1)
                v(p00[0], p00[1], p00[2], n0[0], n0[1], n0[2]); v(p01[0], p01[1], p01[2], n1[0], n1[1], n1[2]); v(p11[0], p11[1], p11[2], n1[0], n1[1], n1[2])
                v(p00[0], p00[1], p00[2], n0[0], n0[1], n0[2]); v(p11[0], p11[1], p11[2], n1[0], n1[1], n1[2]); v(p10[0], p10[1], p10[2], n0[0], n0[1], n0[2])
                for ((on, a, r, sg) in listOf(Quad(cap0, a0, r0, -1f), Quad(cap1, a1, r1, 1f))) {
                    if (!on || r <= 0f) continue
                    val cn = FloatArray(3); cn[ax] = sg
                    val cc = pt(a, 0f, 0f, 0f); val e0 = pt(a, r, c0, s0); val e1 = pt(a, r, c1, s1)
                    v(cc[0], cc[1], cc[2], cn[0], cn[1], cn[2]); v(e0[0], e0[1], e0[2], cn[0], cn[1], cn[2]); v(e1[0], e1[1], e1[2], cn[0], cn[1], cn[2])
                }
            }
        }

        /** Baterijų sparnai: n sekcijų kiekvienoje pusėje nuo x0 iki 1,25; pusplotis hz. Sukasi apie X ašį. */
        fun wings(x0: Float, n: Int, hz: Float) {
            val gap = 0.025f
            val len = (1.25f - x0 - gap * (n - 1)) / n
            for (side in intArrayOf(-1, 1)) for (k in 0 until n) {
                val a = x0 + k * (len + gap); val b = a + len
                if (side > 0) box(a, -0.012f, -hz, b, 0.012f, hz) else box(-b, -0.012f, -hz, -a, 0.012f, hz)
            }
            end(MAT_PANEL, wing = true)
            // rėmeliai, jungtys ir laikiklis (metalas)
            for (side in intArrayOf(-1, 1)) {
                for (k in 0 until n) {
                    val a = x0 + k * (len + gap); val b = a + len
                    for (zz in floatArrayOf(-hz, hz)) {
                        if (side > 0) box(a, -0.018f, zz - 0.012f, b, 0.018f, zz + 0.012f) else box(-b, -0.018f, zz - 0.012f, -a, 0.018f, zz + 0.012f)
                    }
                    if (k < n - 1) {
                        if (side > 0) box(b, -0.01f, -hz * 0.6f, b + gap, 0.01f, hz * 0.6f) else box(-b - gap, -0.01f, -hz * 0.6f, -b, 0.01f, hz * 0.6f)
                    }
                }
                // „V“ formos laikiklis nuo korpuso iki pirmos sekcijos
                val sx = side.toFloat()
                frustum(0, 0f, 0f, sx * 0.2f, 0.022f, sx * x0, 0.022f, 6, false, false)
                box(minOf(sx * (x0 - 0.03f), sx * x0), -0.014f, -hz * 0.7f, maxOf(sx * (x0 - 0.03f), sx * x0), 0.014f, hz * 0.7f)
            }
            end(MAT_METAL, wing = true)
        }
        /** Žemės pusės antenų laukas: centrinis elementas ir žiedas spiralinių antenų (kūgeliai) ant −Y sienos. */
        fun helixArray(yBase: Float, ringR: Float, count: Int, h: Float) {
            frustum(1, 0f, 0f, yBase, 0.05f, yBase - h * 1.25f, 0.018f, 8, true, true)
            for (k in 0 until count) {
                val t = 2.0 * Math.PI * k / count
                val cx = (ringR * cos(t)).toFloat(); val cz = (ringR * sin(t)).toFloat()
                frustum(1, cz, cx, yBase, 0.04f, yBase - h, 0.014f, 6, true, true)
            }
            end(MAT_METAL)
        }
        /** Parabolinė lėkštė į Žemę: stiebas, lėkštė (atvira į −Y) ir spinduolis. */
        fun dish(cx: Float, cz: Float, yBase: Float, r: Float) {
            frustum(1, cz, cx, yBase, 0.02f, yBase - 0.1f, 0.02f, 6, false, false)
            end(MAT_METAL)
            frustum(1, cz, cx, yBase - 0.1f, 0.03f, yBase - 0.1f - r * 0.45f, r, 14, true, false)
            end(MAT_DISH)
            frustum(1, cz, cx, yBase - 0.1f, 0.012f, yBase - 0.1f - r * 0.6f, 0.012f, 5, false, false)
            frustum(1, cz, cx, yBase - 0.1f - r * 0.6f, 0.03f, yBase - 0.1f - r * 0.68f, 0.02f, 6, true, true)
            end(MAT_METAL)
        }
        /** Variklio tūta +Y pusėje. */
        fun nozzle(yBase: Float, r: Float) {
            frustum(1, 0f, 0f, yBase, r * 0.45f, yBase + r * 1.3f, r, 10, false, false)
            end(MAT_DARK)
        }
        /** Plonos strypinės antenos (TT&C) ant +Y sienos. */
        fun whips(yBase: Float, d: Float, h: Float) {
            for (sx in floatArrayOf(-d, d)) for (sz in floatArrayOf(-d, d)) {
                frustum(1, sz, sx, yBase, 0.012f, yBase + h, 0.006f, 4, false, true)
            }
            end(MAT_METAL)
        }
        fun finish() { satVariants.add(parts); parts = ArrayList() }

        // 0 – GPS: dėžės formos korpusas, 3 sekcijų baterijos, 12 spiralinių antenų laukas, strypinės antenos, variklis
        box(-0.2f, -0.3f, -0.2f, 0.2f, 0.3f, 0.2f); end(MAT_BODY)
        box(-0.215f, -0.31f, -0.215f, 0.215f, -0.27f, 0.215f); box(-0.215f, 0.27f, -0.215f, 0.215f, 0.31f, 0.215f); end(MAT_METAL)
        wings(0.34f, 3, 0.2f)
        helixArray(-0.31f, 0.13f, 8, 0.13f)
        whips(0.31f, 0.15f, 0.22f)
        nozzle(0.31f, 0.07f)
        finish()

        // 1 – GLONASS: ilgas cilindrinis korpusas, 2 platesnės sekcijos, tankus antenų laukas, žiediniai apvadai
        frustum(1, 0f, 0f, -0.36f, 0.19f, 0.36f, 0.19f, 14, true, true); end(MAT_BODY)
        frustum(1, 0f, 0f, -0.38f, 0.21f, -0.33f, 0.21f, 14, true, true)
        frustum(1, 0f, 0f, 0.1f, 0.2f, 0.14f, 0.2f, 14, false, false)
        frustum(1, 0f, 0f, 0.36f, 0.19f, 0.46f, 0.1f, 14, false, true); end(MAT_METAL)
        wings(0.33f, 2, 0.26f)
        helixArray(-0.38f, 0.12f, 10, 0.11f)
        whips(0.4f, 0.07f, 0.2f)
        finish()

        // 2 – Galileo: kompaktiškas korpusas, 2 ilgos siauros sekcijos, plokščias navigacijos antenos diskas ir SAR lėkštė
        box(-0.19f, -0.24f, -0.24f, 0.19f, 0.24f, 0.24f); end(MAT_BODY)
        box(-0.2f, -0.25f, -0.25f, 0.2f, -0.22f, 0.25f); end(MAT_METAL)
        wings(0.36f, 2, 0.17f)
        frustum(1, -0.06f, 0f, -0.25f, 0.15f, -0.29f, 0.15f, 16, true, true); end(MAT_DISH)
        helixArray(-0.29f, 0.09f, 6, 0.07f)
        // helixArray centruotas (0,0) – diskas pastumtas per Z, kad matytųsi abu
        dish(0f, 0.15f, -0.25f, 0.1f)
        whips(0.24f, 0.13f, 0.16f)
        nozzle(0.24f, 0.055f)
        finish()

        // 3 – BeiDou: aukštesnis korpusas, 3 sekcijų baterijos, didelė lėkštė ir antenų laukas
        box(-0.21f, -0.32f, -0.19f, 0.21f, 0.32f, 0.19f); end(MAT_BODY)
        box(-0.225f, -0.05f, -0.205f, 0.225f, 0.0f, 0.205f); end(MAT_METAL)
        wings(0.35f, 3, 0.19f)
        dish(0.0f, 0.0f, -0.32f, 0.17f)
        frustum(1, 0.13f, 0.12f, -0.32f, 0.035f, -0.42f, 0.012f, 6, true, true)
        frustum(1, -0.13f, 0.12f, -0.32f, 0.035f, -0.42f, 0.012f, 6, true, true)
        frustum(1, 0.13f, -0.12f, -0.32f, 0.035f, -0.42f, 0.012f, 6, true, true)
        frustum(1, -0.13f, -0.12f, -0.32f, 0.035f, -0.42f, 0.012f, 6, true, true); end(MAT_METAL)
        whips(0.32f, 0.14f, 0.18f)
        nozzle(0.32f, 0.065f)
        finish()

        satPos = floatBuf(pos.toFloatArray())
        satNrm = floatBuf(nrm.toFloatArray())
    }

    private data class Quad(val on: Boolean, val a: Float, val r: Float, val sg: Float)

    private fun satVariant(const: String) = when (const) { "GLO" -> 1; "GAL" -> 2; "BDS" -> 3; else -> 0 }

    /** Palydovas p (sideralinė erdvė): korpusas – konsteliacijos spalvos folija, švytėjimas pagal C/N0, baterijos atsuktos į Saulę. */
    private fun drawSatModel(p: FloatArray, col: FloatArray, glow: Float, usedK: Float, const: String = "GPS") {
        if (satProg == 0 || satPos == null || satVariants.isEmpty()) return
        // Orientacija: Y – nuo Žemės centro, X (baterijos) – statmenai poliui
        val pl = kotlin.math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]).coerceAtLeast(1e-6f)
        val y = floatArrayOf(p[0] / pl, p[1] / pl, p[2] / pl)
        var x = floatArrayOf(-y[1], y[0], 0f)
        var xl = kotlin.math.sqrt(x[0] * x[0] + x[1] * x[1])
        if (xl < 1e-4f) { x = floatArrayOf(1f, 0f, 0f); xl = 1f }
        x = floatArrayOf(x[0] / xl, x[1] / xl, 0f)
        val z = floatArrayOf(x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0])
        val k = satSize / 2.5f
        java.util.Arrays.fill(satModel, 0f)
        for (i in 0..2) { satModel[i] = x[i] * k; satModel[4 + i] = y[i] * k; satModel[8 + i] = z[i] * k }
        satModel[12] = p[0]; satModel[13] = p[1]; satModel[14] = p[2]; satModel[15] = 1f
        val sun = sunDir()
        val sx = sun[0].toFloat(); val sy = sun[1].toFloat(); val sz = sun[2].toFloat()
        // Baterijos sukasi apie X ašį, kad plokštumos normalė žiūrėtų į Saulę
        val sunY = sx * y[0] + sy * y[1] + sz * y[2]; val sunZ = sx * z[0] + sy * z[1] + sz * z[2]
        val wingDeg = Math.toDegrees(kotlin.math.atan2(sunZ.toDouble(), sunY.toDouble())).toFloat()
        System.arraycopy(satModel, 0, satWing, 0, 16)
        Matrix.rotateM(satWing, 0, wingDeg, 1f, 0f, 0f)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(satProg)
        setVec3(satProg, "uLight", sx, sy, sz)
        setVec3(satProg, "uEye", eyeI[0], eyeI[1], eyeI[2])
        resetAttribs()
        val lp = GLES20.glGetAttribLocation(satProg, "aPos")
        val ln = GLES20.glGetAttribLocation(satProg, "aNormal")
        GLES20.glEnableVertexAttribArray(lp); GLES20.glEnableVertexAttribArray(ln)
        satPos!!.position(0); satNrm!!.position(0)
        GLES20.glVertexAttribPointer(lp, 3, GLES20.GL_FLOAT, false, 0, satPos)
        GLES20.glVertexAttribPointer(ln, 3, GLES20.GL_FLOAT, false, 0, satNrm)
        val a = 0.55f + 0.45f * usedK
        var wingBound = -1
        for (part in satVariants[satVariant(const)]) {
            val w = if (part.wing) 1 else 0
            if (w != wingBound) {
                val m = if (part.wing) satWing else satModel
                Matrix.multiplyMM(satMvp, 0, mvpI, 0, m, 0)
                setMat(satProg, "uMVP", satMvp)
                setMat(satProg, "uModel", m)
                wingBound = w
            }
            when (part.mat) {
                MAT_BODY -> {   // auksinė folija su konsteliacijos atspalviu
                    setVec4(satProg, "uColor", 0.45f * col[0] + 0.5f, 0.45f * col[1] + 0.36f, 0.45f * col[2] + 0.1f, a)
                    setVec3(satProg, "uGlow", col[0] * glow * 0.55f, col[1] * glow * 0.55f, col[2] * glow * 0.55f)
                    setVec3(satProg, "uSpec", 1f, 0.85f, 0.5f); setFloat(satProg, "uShine", 18f)
                }
                MAT_PANEL -> {  // saulės elementai: tamsiai mėlyni, stiprus atspindys, tinklelis
                    setVec4(satProg, "uColor", 0.05f, 0.1f, 0.34f, a)
                    setVec3(satProg, "uGlow", col[0] * glow * 0.22f, col[1] * glow * 0.22f, col[2] * glow * 0.22f)
                    setVec3(satProg, "uSpec", 0.75f, 0.85f, 1f); setFloat(satProg, "uShine", 60f)
                }
                MAT_DISH -> {   // balta antenos lėkštė
                    setVec4(satProg, "uColor", 0.92f, 0.93f, 0.95f, a)
                    setVec3(satProg, "uGlow", col[0] * glow * 0.3f, col[1] * glow * 0.3f, col[2] * glow * 0.3f)
                    setVec3(satProg, "uSpec", 0.4f, 0.4f, 0.4f); setFloat(satProg, "uShine", 12f)
                }
                MAT_DARK -> {   // variklio tūta
                    setVec4(satProg, "uColor", 0.16f, 0.15f, 0.17f, a)
                    setVec3(satProg, "uGlow", 0f, 0f, 0f)
                    setVec3(satProg, "uSpec", 0.5f, 0.45f, 0.4f); setFloat(satProg, "uShine", 30f)
                }
                else -> {       // metalas: rėmai, laikikliai, antenų elementai (antgaliai švyti pagal signalą)
                    setVec4(satProg, "uColor", 0.72f, 0.74f, 0.78f, a)
                    setVec3(satProg, "uGlow", col[0] * glow * 0.4f, col[1] * glow * 0.4f, col[2] * glow * 0.4f)
                    setVec3(satProg, "uSpec", 1f, 1f, 1f); setFloat(satProg, "uShine", 40f)
                }
            }
            setFloat(satProg, "uPanel", if (part.mat == MAT_PANEL) 1f else 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, part.start, part.count)
        }
        GLES20.glDisableVertexAttribArray(ln)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    // ---------------- LAZERIAI: palydovas -> smeigtukas ----------------
    /** Smeigtuko galvutė (stebėtojo vieta) sideralinėje erdvėje – ta pati, kurią piešia drawEarth(). */
    private fun pinWorld(): FloatArray {
        val la = Math.toRadians(obsLat); val lo = Math.toRadians(obsLon)
        val k = 0.25 * 1.35
        val x = (k * cos(la) * cos(lo)).toFloat(); val y = (k * cos(la) * sin(lo)).toFloat(); val z = (k * sin(la)).toFloat()
        val m = earthMat
        return floatArrayOf(
            m[0] * x + m[4] * y + m[8] * z + m[12],
            m[1] * x + m[5] * y + m[9] * z + m[13],
            m[2] * x + m[6] * y + m[10] * z + m[14])
    }

    /**
     * Šviesos impulsai nuo palydovo į smeigtuką pagal C/N0:
     * ≥45 – greiti elektriniai geltoni neoniniai su baltu centru; >20 – lėti tamsiai mėlyni;
     * >10 – lėti pilki; ≤10 – nėra.
     */
    private fun drawLaser(sp: FloatArray, pin: FloatArray, cn0: Float, svid: Int, t: Float) {
        if (cn0 <= 10f) return
        val fast = cn0 >= 45f
        val col = when {
            fast -> floatArrayOf(1f, 0.95f, 0.15f)
            cn0 > 20f -> floatArrayOf(0.1f, 0.2f, 0.75f)
            else -> floatArrayOf(0.6f, 0.6f, 0.62f)
        }
        val speed = if (fast) 1.6f else 0.3f            // impulsų greitis (kelio dalis per sekundę)
        val pulses = if (fast) 5 else 3
        val len = if (fast) 0.07f else 0.10f
        val dx = pin[0] - sp[0]; val dy = pin[1] - sp[1]; val dz = pin[2] - sp[2]
        fun at(s: Float) = floatArrayOf(sp[0] + dx * s, sp[1] + dy * s, sp[2] + dz * s)

        GLES20.glUseProgram(colorProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uMVP"), 1, false, mvpI, 0)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)       // šviesa sumuojasi (neoninis efektas)
        // silpnas spindulys
        GLES20.glLineWidth(1f * density)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"), col[0], col[1], col[2], if (fast) 0.25f else 0.15f)
        bindPos(colorProg, floatBuf(floatArrayOf(sp[0], sp[1], sp[2], pin[0], pin[1], pin[2])))
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        // judantys impulsai
        val seg = ArrayList<Float>()
        val phase = (svid * 0.137f) % 1f
        for (j in 0 until pulses) {
            var s0 = (t * speed + phase + j.toFloat() / pulses) % 1f
            if (s0 < 0f) s0 += 1f
            val s1 = (s0 + len).coerceAtMost(1f)
            val a = at(s0); val b = at(s1)
            seg.add(a[0]); seg.add(a[1]); seg.add(a[2]); seg.add(b[0]); seg.add(b[1]); seg.add(b[2])
        }
        val buf = floatBuf(seg.toFloatArray())
        GLES20.glLineWidth((if (fast) 5f else 3f) * density)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"), col[0], col[1], col[2], if (fast) 0.9f else 0.75f)
        bindPos(colorProg, buf)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, seg.size / 3)
        if (fast) {
            // baltas centras – „elektrinis“ neonas, šiek tiek mirga
            val flick = 0.75f + 0.25f * sin(t * 40f + svid).toFloat()
            GLES20.glLineWidth(1.6f * density)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"), 1f, 1f, 1f, flick)
            bindPos(colorProg, buf)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, seg.size / 3)
        }
        GLES20.glLineWidth(1f)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    // ---------------- PALYDOVŲ TRAJEKTORIJOS (paskutinės 2 min, taškas kas sekundę) ----------------
    private val trailWindowMs = 120_000L
    private class TrailPt(val t: Long, val p: FloatArray)
    private val trails = HashMap<String, ArrayDeque<TrailPt>>()
    private val trailConst = HashMap<String, String>()

    private fun recordTrails(list: List<SatI>) {
        val now = System.currentTimeMillis()
        synchronized(trails) {
            for (si in list) {
                val key = si.sat.const + si.sat.svid
                val q = trails.getOrPut(key) { ArrayDeque() }
                trailConst[key] = si.sat.const
                if (q.isEmpty() || now - q.last().t >= 1000L) q.addLast(TrailPt(now, si.pos.copyOf()))
            }
            val it = trails.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                while (e.value.isNotEmpty() && now - e.value.first().t > trailWindowMs) e.value.removeFirst()
                if (e.value.isEmpty()) { it.remove(); trailConst.remove(e.key) }
            }
        }
    }

    /** Kiekvieno palydovo kreivė per paskutines 2 min (segmentas kas sekundę), spalva – pagal konsteliaciją. */
    private fun drawTrails() {
        val snap: List<Pair<String, FloatArray>> = synchronized(trails) {
            trails.mapNotNull { (k, q) ->
                if (q.size < 2) null else {
                    val a = FloatArray(q.size * 3)
                    var i = 0
                    for (tp in q) { a[i++] = tp.p[0]; a[i++] = tp.p[1]; a[i++] = tp.p[2] }
                    (trailConst[k] ?: "GPS") to a
                }
            }
        }
        if (snap.isEmpty()) return
        GLES20.glUseProgram(colorProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uMVP"), 1, false, mvpI, 0)
        GLES20.glLineWidth(2f * density)
        for ((c, a) in snap) {
            val col = constRgb(c)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"), col[0], col[1], col[2], 0.85f)
            bindPos(colorProg, floatBuf(a))
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, a.size / 3)
        }
        GLES20.glLineWidth(1f)
    }

    // ---------------- ŽEMĖ GAUBLIO VIDUJE ----------------
    @Volatile private var obsLat = 54.7
    @Volatile private var obsLon = 25.25
    fun setLocation(lat: Double, lon: Double) { obsLat = lat; obsLon = lon }

    private var lastTint = 0xFF3B82F6.toInt()
    private var bgNow = BG_NEUTRAL

    @Volatile var zoom = 3f
    /** Priartinimas pagal aukštį virš paviršiaus: arčiausiai ~0,05 vnt. (≈ 1 300 km) – Kreta ~2 cm ekrane. */
    fun zoomBy(factor: Float) { zoom = EARTH_R + ((zoom - EARTH_R) / factor).coerceIn(0.05f, 5.75f) }
    private var aspectNow = 1f

    @Volatile private var userR = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    @Volatile private var dragging = false
    @Volatile private var spinAngle = 0.0
    @Volatile private var spinAxis = doubleArrayOf(0.0, 0.0, 1.0)
    fun setDragging(b: Boolean) { dragging = b }
    fun resetUser() { userR = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0); spinAngle = 0.0 }

    fun arcRotate(p0: DoubleArray, p1: DoubleArray) {
        val ax = p0[1] * p1[2] - p0[2] * p1[1]
        val ay = p0[2] * p1[0] - p0[0] * p1[2]
        val az = p0[0] * p1[1] - p0[1] * p1[0]
        val len = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        if (len < 1e-9) return
        val dot = (p0[0] * p1[0] + p0[1] * p1[1] + p0[2] * p1[2]).coerceIn(-1.0, 1.0)
        val angle = kotlin.math.acos(dot)
        if (angle < 1e-6) return
        val axis = doubleArrayOf(ax / len, ay / len, az / len)
        userR = mul3d(axisAngleMat(axis, angle), userR)
        spinAxis = axis
        spinAngle = angle
    }

    private fun axisAngleMat(a: DoubleArray, th: Double): DoubleArray {
        val c = cos(th); val s = sin(th); val t = 1 - c
        val x = a[0]; val y = a[1]; val z = a[2]
        return doubleArrayOf(
            t * x * x + c, t * x * y - s * z, t * x * z + s * y,
            t * x * y + s * z, t * y * y + c, t * y * z - s * x,
            t * x * z - s * y, t * y * z + s * x, t * z * z + c)
    }

    private fun mul3d(a: DoubleArray, b: DoubleArray): DoubleArray {
        val o = DoubleArray(9)
        for (i in 0..2) for (j in 0..2) {
            var sum = 0.0
            for (k in 0..2) sum += a[i * 3 + k] * b[k * 3 + j]
            o[i * 3 + j] = sum
        }
        return o
    }

    /** Ritinimas aplink ekrano (žiūros) ašį – dviejų pirštų pasukimas. */
    fun rollBy(angle: Double) {
        if (kotlin.math.abs(angle) < 1e-6) return
        userR = mul3d(axisAngleMat(doubleArrayOf(0.0, 0.0, 1.0), angle), userR)
    }

    fun northDeltaRad(): Double {
        val r = rot
        val nd = doubleArrayOf(r[3].toDouble(), r[4].toDouble(), r[5].toDouble())
        val u = userR
        val nu = doubleArrayOf(
            u[0] * nd[0] + u[1] * nd[1] + u[2] * nd[2],
            u[3] * nd[0] + u[4] * nd[1] + u[5] * nd[2],
            u[6] * nd[0] + u[7] * nd[1] + u[8] * nd[2])
        return kotlin.math.atan2(nu[0], nu[1]) - kotlin.math.atan2(nd[0], nd[1])
    }

    @Volatile private var southUp = false
    fun setSouthUpMode(on: Boolean) { southUp = on }

    private var earthTex = 0
    private var earthNightTex = 0
    private var earthMaskTex = 0

    // ---------------- ŽEMĖ SU REALJEFU (GEBCO 2008: sausuma 0–6400 m, vandenynas iki −8000 m) ----------------
    private var earthReliefProg = 0
    /** Juostos po ≤ 65535 viršūnių: (x, y, z, u, v, nx, ny, nz) ir short indeksai. */
    private class ReliefBand(val vbo: FloatBuffer, val idx: java.nio.ShortBuffer, val count: Int)
    private val earthBands = ArrayList<ReliefBand>()
    /** Reljefo išryškinimas: Everestas ×30 ≈ 3,8 % spindulio – kalnai matomi, Žemė lieka apvali. */
    private val EARTH_RELIEF_X = 30.0

    /** assets/earth_relief.bin – 1440×720 int16 LE (m), eilutės nuo šiaurės, stulpeliai nuo −180°. Tinklelis 720×360. */
    private fun buildEarthRelief() {
        earthReliefProg = program(EARTH_RELIEF_VS, EARTH_FS)
        earthBands.clear()
        val w = 1440; val hgt = 720
        val hm = try {
            appCtx.assets.open("earth_relief.bin").use { ins ->
                val sb = java.nio.ByteBuffer.wrap(ins.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                ShortArray(w * hgt).also { sb.get(it) }
            }
        } catch (e: Exception) {
            android.util.Log.w("GnssApp", "earth_relief.bin nerastas – Žemė be reljefo"); return
        }
        fun height(latDeg: Double, lonDeg: Double): Double {
            val fx = ((lonDeg + 180.0) * 4.0 - 0.5).let { ((it % w) + w) % w }
            val fy = ((90.0 - latDeg) * 4.0 - 0.5).coerceIn(0.0, hgt - 1.0)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = (x0 + 1) % w; val y1 = (y0 + 1).coerceAtMost(hgt - 1)
            val ax = fx - x0; val ay = fy - y0
            fun v(x: Int, y: Int) = hm[y * w + x].toDouble()
            return (v(x0, y0) * (1 - ax) + v(x1, y0) * ax) * (1 - ay) + (v(x0, y1) * (1 - ax) + v(x1, y1) * ax) * ay
        }
        val slices = 720; val stacks = 360; val r0 = 0.25
        val cols = slices + 1
        val p = Array((stacks + 1) * cols) { DoubleArray(3) }
        for (st in 0..stacks) for (sl in 0..slices) {
            val latD = -90.0 + 180.0 * st / stacks; val lonD = -180.0 + 360.0 * sl / slices
            val la = Math.toRadians(latD); val lo = Math.toRadians(lonD)
            val r = r0 * (1.0 + EARTH_RELIEF_X * height(latD, lonD) / 6_371_000.0)
            val q = p[st * cols + sl]
            q[0] = r * cos(la) * cos(lo); q[1] = r * cos(la) * sin(lo); q[2] = r * sin(la)
        }
        fun vertex(out: FloatArray, o: Int, st: Int, sl: Int) {
            val i = st * cols + sl
            val e = p[st * cols + (if (sl == slices) 1 else sl + 1)]; val wv = p[st * cols + (if (sl == 0) slices - 1 else sl - 1)]
            val n = p[(st + 1).coerceAtMost(stacks) * cols + sl]; val sv = p[(st - 1).coerceAtLeast(0) * cols + sl]
            val tx = DoubleArray(3) { e[it] - wv[it] }; val ty = DoubleArray(3) { n[it] - sv[it] }
            var nx = tx[1] * ty[2] - tx[2] * ty[1]; var ny = tx[2] * ty[0] - tx[0] * ty[2]; var nz = tx[0] * ty[1] - tx[1] * ty[0]
            var nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (st == 0 || st == stacks || nl < 1e-12) { nx = p[i][0]; ny = p[i][1]; nz = p[i][2]; nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz) }
            if (nx * p[i][0] + ny * p[i][1] + nz * p[i][2] < 0) { nx = -nx; ny = -ny; nz = -nz }
            out[o] = p[i][0].toFloat(); out[o + 1] = p[i][1].toFloat(); out[o + 2] = p[i][2].toFloat()
            out[o + 3] = sl.toFloat() / slices; out[o + 4] = (stacks - st).toFloat() / stacks
            out[o + 5] = (nx / nl).toFloat(); out[o + 6] = (ny / nl).toFloat(); out[o + 7] = (nz / nl).toFloat()
        }
        val rowsPerBand = 60                                     // (60 + 1) × 721 = 43 981 viršūnių < 65 535
        var st0 = 0
        while (st0 < stacks) {
            val st1 = minOf(st0 + rowsPerBand, stacks)
            val nRows = st1 - st0 + 1
            val data = FloatArray(nRows * cols * 8)
            for (a in 0 until nRows) for (sl in 0..slices) vertex(data, (a * cols + sl) * 8, st0 + a, sl)
            val idx = ShortArray((nRows - 1) * slices * 6)
            var k = 0
            for (a in 0 until nRows - 1) for (sl in 0 until slices) {
                val i0 = a * cols + sl; val i1 = i0 + cols
                idx[k++] = i0.toShort(); idx[k++] = i1.toShort(); idx[k++] = (i1 + 1).toShort()
                idx[k++] = i0.toShort(); idx[k++] = (i1 + 1).toShort(); idx[k++] = (i0 + 1).toShort()
            }
            val ib = java.nio.ByteBuffer.allocateDirect(idx.size * 2).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
            ib.put(idx); ib.position(0)
            earthBands.add(ReliefBand(floatBuf(data), ib, idx.size))
            st0 = st1
        }
        android.util.Log.i("GnssApp", "Žemės reljefas: ${earthBands.size} juostos, ${(stacks + 1) * cols} viršūnių, išryškinimas ×${EARTH_RELIEF_X.toInt()}")
    }
    private var earthProg = 0
    private val earthAxis by lazy { floatBuf(floatArrayOf(0f, 0f, -0.5f, 0f, 0f, 0.5f)) }
    private var earthPos: FloatBuffer? = null
    private var earthUv: FloatBuffer? = null
    private var earthCount = 0
    private val earthMat = FloatArray(16)
    private val earthMvp = FloatArray(16)

    /** Įkelia assets/earth.jpg ir sukuria rutulį su lygiakampe tekstūra. */
    private fun buildEarth() {
        earthProg = program(EARTH_VS, EARTH_FS)
        val main = Handler(Looper.getMainLooper())
        try {
            appCtx.assets.open("earth.jpg").use { ins ->
                var bmp = android.graphics.BitmapFactory.decodeStream(ins)
                // Didžiausia tekstūra pagal įrenginį (Blue Marble 5400×2700; jei netelpa – sumažinama)
                val maxTex = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
                if (bmp != null && maxTex in 1 until bmp.width) {
                    val sc = Bitmap.createScaledBitmap(bmp, maxTex, maxTex / 2, true); bmp.recycle(); bmp = sc
                }
                if (bmp != null) {
                    earthTex = uploadTexture(bmp)
                    val info = "Žemės tekstūra įkelta: ${bmp.width}x${bmp.height}"
                    main.post { Toast.makeText(appCtx, info, Toast.LENGTH_LONG).show() }
                } else {
                    main.post { Toast.makeText(appCtx, "earth.jpg neskaitomas (ne JPG/PNG?)", Toast.LENGTH_LONG).show() }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("GnssApp", "earth.jpg nerastas assets aplanke: app/src/main/assets/earth.jpg", e)
            earthTex = 0
            main.post { Toast.makeText(appCtx, "earth.jpg nerastas: app/src/main/assets/earth.jpg", Toast.LENGTH_LONG).show() }
        }
        earthMaskTex = 0
        try {
            appCtx.assets.open("earth_ocean.png").use { ins ->
                val bmp = android.graphics.BitmapFactory.decodeStream(ins)
                if (bmp != null) earthMaskTex = uploadTexture(bmp)
            }
        } catch (e: Exception) {
            android.util.Log.w("GnssApp", "earth_ocean.png nerastas – vandenynas pagal spalvą")
        }
        buildEarthRelief()
        earthNightTex = 0
        try {
            appCtx.assets.open("earth_night.jpg").use { ins ->
                val bmp = android.graphics.BitmapFactory.decodeStream(ins)
                if (bmp != null) earthNightTex = uploadTexture(bmp)
            }
        } catch (e: Exception) {
            android.util.Log.w("GnssApp", "earth_night.jpg nerastas: naktinė pusė rodoma šešėliu", e)
        }
        moonTex = 0
        try {
            appCtx.assets.open("moon.jpg").use { ins ->
                val bmp = android.graphics.BitmapFactory.decodeStream(ins)
                if (bmp != null) moonTex = uploadTexture(bmp)
            }
        } catch (e: Exception) {
            android.util.Log.w("GnssApp", "moon.jpg nerastas: app/src/main/assets/moon.jpg (rodomas pilkas Mėnulis)")
        }
        buildMoonRelief()
        val stacks = 48; val slices = 96; val r = 0.25
        val pos = ArrayList<Float>(); val uv = ArrayList<Float>()
        fun vertex(st: Int, sl: Int) {
            val lat = Math.toRadians(-90.0 + 180.0 * st / stacks)
            val lon = Math.toRadians(-180.0 + 360.0 * sl / slices)
            pos.add((r * cos(lat) * cos(lon)).toFloat())
            pos.add((r * cos(lat) * sin(lon)).toFloat())
            pos.add((r * sin(lat)).toFloat())
            uv.add(sl.toFloat() / slices)
            uv.add((stacks - st).toFloat() / stacks)
        }
        for (st in 0 until stacks) for (sl in 0 until slices) {
            // du trikampiai kiekvienam tinklelio kvadratui
            vertex(st, sl); vertex(st + 1, sl); vertex(st + 1, sl + 1)
            vertex(st, sl); vertex(st + 1, sl + 1); vertex(st, sl + 1)
        }
        earthPos = floatBuf(pos.toFloatArray())
        earthUv = floatBuf(uv.toFloatArray())
        earthCount = pos.size / 3
    }

    // ---------------- MĖNULIS SU REALJEFU (NASA SVS CGI Moon Kit: LROC spalvos, LOLA aukščiai) ----------------
    private var moonProg = 0
    private var moonVbo: FloatBuffer? = null           // x, y, z, u, v, nx, ny, nz
    private var moonIdx: java.nio.ShortBuffer? = null
    private var moonIdxCount = 0
    /** Reljefo išryškinimas: tikras ±10 km prie 1737 km spindulio (0,6 %) būtų nematomas. */
    private val MOON_RELIEF_X = 10.0

    /**
     * assets/moon_height.bin – 720×360 int16 LE, aukštis metrais nuo 1737,4 km (eilutės nuo šiaurės, stulpeliai nuo −180°).
     * Tinklelis 240×120 su indeksais; normalės iš pakelto paviršiaus (kraterių ir baseinų šešėliai).
     */
    private fun buildMoonRelief() {
        moonProg = program(MOON_VS, MOON_FS)
        val w = 720; val hgt = 360
        val hm = try {
            appCtx.assets.open("moon_height.bin").use { ins ->
                val b = ins.readBytes()
                val sb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                ShortArray(w * hgt).also { sb.get(it) }
            }
        } catch (e: Exception) {
            android.util.Log.w("GnssApp", "moon_height.bin nerastas – Mėnulis be reljefo"); moonVbo = null; return
        }
        fun height(latDeg: Double, lonDeg: Double): Double {
            val fx = ((lonDeg + 180.0) * 2.0 - 0.5).let { ((it % w) + w) % w }
            val fy = ((90.0 - latDeg) * 2.0 - 0.5).coerceIn(0.0, hgt - 1.0)
            val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = (x0 + 1) % w; val y1 = (y0 + 1).coerceAtMost(hgt - 1)
            val ax = fx - x0; val ay = fy - y0
            fun v(x: Int, y: Int) = hm[y * w + x].toDouble()
            return (v(x0, y0) * (1 - ax) + v(x1, y0) * ax) * (1 - ay) + (v(x0, y1) * (1 - ax) + v(x1, y1) * ax) * ay
        }
        val slices = 240; val stacks = 120; val r0 = 0.25
        val nv = (slices + 1) * (stacks + 1)
        val p = Array(nv) { DoubleArray(3) }
        val data = FloatArray(nv * 8)
        for (st in 0..stacks) for (sl in 0..slices) {
            val latD = -90.0 + 180.0 * st / stacks; val lonD = -180.0 + 360.0 * sl / slices
            val la = Math.toRadians(latD); val lo = Math.toRadians(lonD)
            val r = r0 * (1.0 + MOON_RELIEF_X * height(latD, lonD) / 1_737_400.0)
            val i = st * (slices + 1) + sl
            p[i][0] = r * cos(la) * cos(lo); p[i][1] = r * cos(la) * sin(lo); p[i][2] = r * sin(la)
            data[i * 8 + 3] = sl.toFloat() / slices; data[i * 8 + 4] = (stacks - st).toFloat() / stacks
        }
        for (st in 0..stacks) for (sl in 0..slices) {
            val i = st * (slices + 1) + sl
            val e = p[st * (slices + 1) + (if (sl == slices) 1 else sl + 1)]
            val wv = p[st * (slices + 1) + (if (sl == 0) slices - 1 else sl - 1)]
            val n = p[(st + 1).coerceAtMost(stacks) * (slices + 1) + sl]
            val sv = p[(st - 1).coerceAtLeast(0) * (slices + 1) + sl]
            val tx = DoubleArray(3) { e[it] - wv[it] }; val ty = DoubleArray(3) { n[it] - sv[it] }
            var nx = tx[1] * ty[2] - tx[2] * ty[1]; var ny = tx[2] * ty[0] - tx[0] * ty[2]; var nz = tx[0] * ty[1] - tx[1] * ty[0]
            var nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
            if (st == 0 || st == stacks || nl < 1e-12) { nx = p[i][0]; ny = p[i][1]; nz = p[i][2]; nl = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz) }
            if (nx * p[i][0] + ny * p[i][1] + nz * p[i][2] < 0) { nx = -nx; ny = -ny; nz = -nz }
            for (k in 0..2) data[i * 8 + k] = p[i][k].toFloat()
            data[i * 8 + 5] = (nx / nl).toFloat(); data[i * 8 + 6] = (ny / nl).toFloat(); data[i * 8 + 7] = (nz / nl).toFloat()
        }
        val idx = ShortArray(stacks * slices * 6)
        var k = 0
        for (st in 0 until stacks) for (sl in 0 until slices) {
            val a = st * (slices + 1) + sl; val b = a + slices + 1
            idx[k++] = a.toShort(); idx[k++] = b.toShort(); idx[k++] = (b + 1).toShort()
            idx[k++] = a.toShort(); idx[k++] = (b + 1).toShort(); idx[k++] = (a + 1).toShort()
        }
        moonVbo = floatBuf(data)
        moonIdx = java.nio.ByteBuffer.allocateDirect(idx.size * 2).order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().apply { put(idx); position(0) }
        moonIdxCount = idx.size
        android.util.Log.i("GnssApp", "Mėnulio reljefas: $nv viršūnių, išryškinimas ×${MOON_RELIEF_X.toInt()}")
    }

    /** Žemės sukimasis: GMST + stebėtojo ilguma; ašis rodo į Polaris (pasvirimas = 90° − platuma). */
    private fun drawEarth() {
        if (earthPos == null) return
        // Žemė sukasi apie savo ašį (Polaris kryptimi) pagal GMST; smeigtukas ir horizontas – jos dalis
        val gg = fG
        java.util.Arrays.fill(earthMat, 0f)
        earthMat[0] = cos(gg).toFloat(); earthMat[1] = sin(gg).toFloat()
        earthMat[4] = (-sin(gg)).toFloat(); earthMat[5] = cos(gg).toFloat()
        earthMat[10] = 1f; earthMat[15] = 1f
        if (southUp) {
            // 180° apie zenito ašį: pietų pusrutulis rodomas viršuje, smeigtukas lieka vietoje
            val ux = fU[0]; val uy = fU[1]; val uz = fU[2]
            val ru = doubleArrayOf(
                2 * ux * ux - 1, 2 * ux * uy, 2 * ux * uz,
                2 * uy * ux, 2 * uy * uy - 1, 2 * uy * uz,
                2 * uz * ux, 2 * uz * uy, 2 * uz * uz - 1)
            val rzm = doubleArrayOf(
                earthMat[0].toDouble(), earthMat[4].toDouble(), 0.0,
                earthMat[1].toDouble(), earthMat[5].toDouble(), 0.0,
                0.0, 0.0, 1.0)
            val out = DoubleArray(9)
            for (i in 0..2) for (j in 0..2) {
                var sum = 0.0
                for (k in 0..2) sum += ru[i * 3 + k] * rzm[k * 3 + j]
                out[i * 3 + j] = sum
            }
            earthMat[0] = out[0].toFloat(); earthMat[1] = out[3].toFloat(); earthMat[2] = out[6].toFloat()
            earthMat[4] = out[1].toFloat(); earthMat[5] = out[4].toFloat(); earthMat[6] = out[7].toFloat()
            earthMat[8] = out[2].toFloat(); earthMat[9] = out[5].toFloat(); earthMat[10] = out[8].toFloat()
        }
        Matrix.multiplyMM(earthMvp, 0, mvpI, 0, earthMat, 0)

        if (earthTex == 0) {
            GLES20.glUseProgram(colorProg)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uMVP"), 1, false, earthMvp, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"), 0.18f, 0.45f, 0.85f, 1f)
            bindPos(colorProg, earthPos!!)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, earthCount)
        } else if (earthBands.isNotEmpty() && earthReliefProg != 0) {
            val prog = earthReliefProg
            GLES20.glUseProgram(prog)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uMVP"), 1, false, earthMvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uModel"), 1, false, earthMat, 0)
            val sun = sunDir()
            GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uLight"), sun[0].toFloat(), sun[1].toFloat(), sun[2].toFloat())
            GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uEye"), eyeI[0], eyeI[1], eyeI[2])
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, earthTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (earthNightTex != 0) earthNightTex else earthTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uNight"), 1)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (earthMaskTex != 0) earthMaskTex else earthTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uMask"), 2)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHasNight"), if (earthNightTex != 0) 1f else 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHasMask"), if (earthMaskTex != 0) 1f else 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uWater"), 1f)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            resetAttribs()
            val lp = GLES20.glGetAttribLocation(prog, "aPos")
            val lu = GLES20.glGetAttribLocation(prog, "aUV")
            val ln = GLES20.glGetAttribLocation(prog, "aNormal")
            GLES20.glEnableVertexAttribArray(lp); GLES20.glEnableVertexAttribArray(lu); GLES20.glEnableVertexAttribArray(ln)
            // Reljefas nėra iškilas – reikia gylio testo (būsena atstatoma)
            val depthWas = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            for (b in earthBands) {
                b.vbo.position(0); GLES20.glVertexAttribPointer(lp, 3, GLES20.GL_FLOAT, false, 32, b.vbo)
                b.vbo.position(3); GLES20.glVertexAttribPointer(lu, 2, GLES20.GL_FLOAT, false, 32, b.vbo)
                b.vbo.position(5); GLES20.glVertexAttribPointer(ln, 3, GLES20.GL_FLOAT, false, 32, b.vbo)
                b.vbo.position(0); b.idx.position(0)
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, b.count, GLES20.GL_UNSIGNED_SHORT, b.idx)
            }
            GLES20.glDisableVertexAttribArray(ln)
            if (!depthWas) GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        } else {
            // 3D apšvietimas: saulės šviesa iš viršaus-dešinės, žemynai matomi tekstūroje
            GLES20.glUseProgram(earthProg)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(earthProg, "uMVP"), 1, false, earthMvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(earthProg, "uModel"), 1, false, earthMat, 0)
            val sun = sunDir()
            GLES20.glUniform3f(GLES20.glGetUniformLocation(earthProg, "uLight"), sun[0].toFloat(), sun[1].toFloat(), sun[2].toFloat())
            GLES20.glUniform3f(GLES20.glGetUniformLocation(earthProg, "uEye"), eyeI[0], eyeI[1], eyeI[2])
            resetAttribs()
            val lp = GLES20.glGetAttribLocation(earthProg, "aPos")
            val lu = GLES20.glGetAttribLocation(earthProg, "aUV")
            GLES20.glEnableVertexAttribArray(lp)
            GLES20.glEnableVertexAttribArray(lu)
            earthPos!!.position(0); earthUv!!.position(0)
            GLES20.glVertexAttribPointer(lp, 3, GLES20.GL_FLOAT, false, 0, earthPos)
            GLES20.glVertexAttribPointer(lu, 2, GLES20.GL_FLOAT, false, 0, earthUv)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, earthTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(earthProg, "uTex"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, if (earthNightTex != 0) earthNightTex else earthTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(earthProg, "uNight"), 1)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(earthProg, "uHasNight"), if (earthNightTex != 0) 1f else 0f)
            GLES20.glUniform1f(GLES20.glGetUniformLocation(earthProg, "uWater"), 1f)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, earthCount)
        }

        // Žemės ašis (strypas per polius), rodanti į Polaris
        GLES20.glUseProgram(colorProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uMVP"), 1, false, earthMvp, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"), 1f, 0.85f, 0.3f, 1f)
        bindPos(colorProg, earthAxis)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)

        // Stebėtojo vieta: raudonas smeigtukas ant Žemės paviršiaus
        val la = Math.toRadians(obsLat); val lo = Math.toRadians(obsLon)
        val r = 0.25
        val px = (r * cos(la) * cos(lo)).toFloat()
        val py = (r * cos(la) * sin(lo)).toFloat()
        val pz = (r * sin(la)).toFloat()
        GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"), 0.95f, 0.15f, 0.15f, 1f)
        bindPos(colorProg, floatBuf(floatArrayOf(px, py, pz, px * 1.35f, py * 1.35f, pz * 1.35f)))
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        GLES20.glUseProgram(dotProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(dotProg, "uMVP"), 1, false, earthMvp, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(dotProg, "uColor"), 0.95f, 0.15f, 0.15f, 1f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(dotProg, "uPoint"), 22f * density)
        bindPos(dotProg, floatBuf(floatArrayOf(px * 1.35f, py * 1.35f, pz * 1.35f)))
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
    }

    private val EARTH_R = 0.25f

    /** Palydovo aukštis virš Žemės (km) pagal konstelaciją. */
    private fun altKm(c: String): Double = when (c) {
        "GPS" -> 20200.0
        "GAL" -> 23222.0
        "GLO" -> 19100.0
        "BDS" -> 21528.0
        "SBS" -> 35786.0
        else -> 20200.0
    }

    private fun cnoEl(v: Float): Double = (-80.0 + (v - 15.0) * 70.0 / 30.0).coerceIn(-86.0, -10.0)

    private fun cnoPt(az: Double, el: Double): FloatArray {
        val r = 0.995
        return floatArrayOf(
            (r * cos(el) * sin(az)).toFloat(),
            (r * cos(el) * cos(az)).toFloat(),
            (r * sin(el)).toFloat())
    }

    /** Palydovų C/N0 kreivės aplink apatinę gaublio dalį – laikas eina apskritimu. */
    private fun drawSatCurves() {
        val snap: Map<String, List<Float>> = synchronized(satHist) {
            satHist.mapValues { it.value.toList() }
        }
        GLES20.glUseProgram(colorProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uMVP"), 1, false, mvp, 0)
        for ((key, h) in snap) {
            if (h.size < 2) continue
            val pts = ArrayList<Float>()
            var prev: FloatArray? = null
            var prevIdx = -2
            for (i in h.indices) {
                val v = h[i]
                if (v.isNaN()) { prev = null; continue }
                val age = h.size - 1 - i
                val az = Math.toRadians(360.0 * (winN - 1 - age) / winN)
                val p = cnoPt(az, Math.toRadians(cnoEl(v)))
                if (prev != null && i == prevIdx + 1) {
                    pts.add(prev[0]); pts.add(prev[1]); pts.add(prev[2])
                    pts.add(p[0]); pts.add(p[1]); pts.add(p[2])
                }
                prev = p
                prevIdx = i
            }
            if (pts.isEmpty()) continue
            val c = constRgb(key.substring(0, minOf(3, key.length)))
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"), c[0], c[1], c[2], 0.9f)
            bindPos(colorProg, floatBuf(pts.toFloatArray()))
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, pts.size / 3)
        }
    }

    /** Mėlyna atmosferos aureolė aplink Žemę (matoma tik prie krašto). */
    private fun drawAtmosphere() {
        if (earthPos == null) return
        if (atmoProg == 0) atmoProg = program(ATMO_VS, ATMO_FS)
        val m = FloatArray(16)
        java.util.Arrays.fill(m, 0f)
        val k = 1.06f
        m[0] = k; m[5] = k; m[10] = k; m[15] = 1f
        val mvpA = FloatArray(16)
        Matrix.multiplyMM(mvpA, 0, mvpI, 0, m, 0)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(atmoProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(atmoProg, "uMVP"), 1, false, mvpA, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(atmoProg, "uModel"), 1, false, m, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(atmoProg, "uEye"), eyeI[0], eyeI[1], eyeI[2])
        resetAttribs()
        val lp = GLES20.glGetAttribLocation(atmoProg, "aPos")
        GLES20.glEnableVertexAttribArray(lp)
        earthPos!!.position(0)
        GLES20.glVertexAttribPointer(lp, 3, GLES20.GL_FLOAT, false, 0, earthPos)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, earthCount)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private var atmoProg = 0

    // ---------------- MĖNULIS, SAULĖ, PUSIAUJO ŽIEDAS ----------------
    private var moonTex = 0
    private val moonMat = FloatArray(16)
    private val moonMvp = FloatArray(16)

    private fun jdDays(): Double = System.currentTimeMillis() / 86400000.0 + 2440587.5 - 2451545.0

    /** Ekliptikos vektorius -> pusiaujo (sideralinė) sistema. */
    private fun eclToEq(lon: Double, lat: Double): DoubleArray {
        val e = Math.toRadians(23.4397)
        val xe = cos(lat) * cos(lon); val ye = cos(lat) * sin(lon); val ze = sin(lat)
        return doubleArrayOf(xe, ye * cos(e) - ze * sin(e), ye * sin(e) + ze * cos(e))
    }

    /** Saulės kryptis (apytikslė, ~0,01°). */
    private fun sunDir(): DoubleArray {
        val d = jdDays()
        val m = Math.toRadians(357.5291 + 0.98560028 * d)
        val c = Math.toRadians(1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        val l = m + c + Math.toRadians(102.9372) + Math.PI
        return eclToEq(l, 0.0)
    }

    /** Mėnulio kryptis (apytikslė, ~1°). */
    private fun moonDir(): DoubleArray {
        val d = jdDays()
        val L = Math.toRadians(218.316 + 13.176396 * d)
        val M = Math.toRadians(134.963 + 13.064993 * d)
        val F = Math.toRadians(93.272 + 13.229350 * d)
        val lon = L + Math.toRadians(6.289) * sin(M)
        val lat = Math.toRadians(5.128) * sin(F)
        return eclToEq(lon, lat)
    }

    /** Mėnulis tikroje dangaus kryptyje nuo stebėtojo, apšviestas Saulės (matosi fazė). */
    private fun drawMoon() {
        if (earthPos == null) return
        val dir = moonDir()
        val k = 0.40f                                   // Mėnulio spindulys = 0,10 scenos vnt.
        val dist = 1.9                                  // už GEO palydovų (~1,6), arčiau nei Saulė (2,3)
        val px = fP[0] + dir[0] * dist; val py = fP[1] + dir[1] * dist; val pz = fP[2] + dir[2] * dist
        // Artimoji pusė atsukta į Žemę
        val x = doubleArrayOf(-dir[0], -dir[1], -dir[2])
        var y = doubleArrayOf(-x[1], x[0], 0.0)                 // z0 × x
        val yl = kotlin.math.sqrt(y[0] * y[0] + y[1] * y[1]).coerceAtLeast(1e-9)
        y = doubleArrayOf(y[0] / yl, y[1] / yl, 0.0)
        val z = doubleArrayOf(x[1] * y[2] - x[2] * y[1], x[2] * y[0] - x[0] * y[2], x[0] * y[1] - x[1] * y[0])
        java.util.Arrays.fill(moonMat, 0f)
        for (i in 0..2) {
            moonMat[i] = (x[i] * k).toFloat()
            moonMat[4 + i] = (y[i] * k).toFloat()
            moonMat[8 + i] = (z[i] * k).toFloat()
        }
        moonMat[12] = px.toFloat(); moonMat[13] = py.toFloat(); moonMat[14] = pz.toFloat(); moonMat[15] = 1f
        Matrix.multiplyMM(moonMvp, 0, mvpI, 0, moonMat, 0)

        val sun = sunDir()
        if (moonTex != 0 && moonProg != 0 && moonVbo != null && moonIdx != null) {
            GLES20.glUseProgram(moonProg)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(moonProg, "uMVP"), 1, false, moonMvp, 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(moonProg, "uModel"), 1, false, moonMat, 0)
            GLES20.glUniform3f(GLES20.glGetUniformLocation(moonProg, "uLight"), sun[0].toFloat(), sun[1].toFloat(), sun[2].toFloat())
            resetAttribs()
            val lp = GLES20.glGetAttribLocation(moonProg, "aPos")
            val lu = GLES20.glGetAttribLocation(moonProg, "aUV")
            val ln = GLES20.glGetAttribLocation(moonProg, "aNormal")
            GLES20.glEnableVertexAttribArray(lp); GLES20.glEnableVertexAttribArray(lu); GLES20.glEnableVertexAttribArray(ln)
            val vb = moonVbo!!
            vb.position(0); GLES20.glVertexAttribPointer(lp, 3, GLES20.GL_FLOAT, false, 32, vb)
            vb.position(3); GLES20.glVertexAttribPointer(lu, 2, GLES20.GL_FLOAT, false, 32, vb)
            vb.position(5); GLES20.glVertexAttribPointer(ln, 3, GLES20.GL_FLOAT, false, 32, vb)
            vb.position(0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, moonTex)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(moonProg, "uTex"), 0)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            moonIdx!!.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, moonIdxCount, GLES20.GL_UNSIGNED_SHORT, moonIdx)
            GLES20.glDisableVertexAttribArray(ln)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            return
        }
        if (moonTex == 0) {
            GLES20.glUseProgram(colorProg)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uMVP"), 1, false, moonMvp, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"), 0.75f, 0.75f, 0.72f, 1f)
            bindPos(colorProg, earthPos!!)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, earthCount)
            return
        }
        GLES20.glUseProgram(earthProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(earthProg, "uMVP"), 1, false, moonMvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(earthProg, "uModel"), 1, false, moonMat, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(earthProg, "uLight"), sun[0].toFloat(), sun[1].toFloat(), sun[2].toFloat())
        GLES20.glUniform3f(GLES20.glGetUniformLocation(earthProg, "uEye"), eyeI[0], eyeI[1], eyeI[2])
        resetAttribs()
        val lp = GLES20.glGetAttribLocation(earthProg, "aPos")
        val lu = GLES20.glGetAttribLocation(earthProg, "aUV")
        GLES20.glEnableVertexAttribArray(lp)
        GLES20.glEnableVertexAttribArray(lu)
        earthPos!!.position(0); earthUv!!.position(0)
        GLES20.glVertexAttribPointer(lp, 3, GLES20.GL_FLOAT, false, 0, earthPos)
        GLES20.glVertexAttribPointer(lu, 2, GLES20.GL_FLOAT, false, 0, earthUv)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, moonTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(earthProg, "uTex"), 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(earthProg, "uHasNight"), 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(earthProg, "uWater"), 0f)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, earthCount)
    }

    /** Pusiaujo žiedas aplink Žemę: brūkšneliai bėga iš vakarų į rytus (Žemės sukimosi kryptimi). */
    private fun drawEquatorRing(t: Float) {
        val n = 240
        val r = 0.30
        val phase = (t * 24f).toInt()
        val pts = ArrayList<Float>()
        for (i in 0 until n) {
            if (((i - phase) % 12 + 12) % 12 >= 6) continue
            val a0 = 2 * Math.PI * i / n; val a1 = 2 * Math.PI * (i + 1) / n
            pts.add((r * cos(a0)).toFloat()); pts.add((r * sin(a0)).toFloat()); pts.add(0f)
            pts.add((r * cos(a1)).toFloat()); pts.add((r * sin(a1)).toFloat()); pts.add(0f)
        }
        GLES20.glUseProgram(colorProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProg, "uMVP"), 1, false, mvpI, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProg, "uColor"), 0.55f, 0.9f, 1f, 0.9f)
        bindPos(colorProg, floatBuf(pts.toFloatArray()))
        GLES20.glLineWidth(3f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, pts.size / 3)
        GLES20.glLineWidth(1f)
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
        if (tex == 0) return   // tekstūra dar nesukurta – nepiešiame (be juodų kvadratų)
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
        resetAttribs()
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

    private var sunTex = 0
    private var sunImgTex = 0

    /** Saulės tekstūra: ryškus diskas + minkšta korona (nupiešta programiškai). */
    private fun makeSunTexture(): Int {
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val h = size / 2f
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // Centras permatomas (ten – 3D rutulys), švyti tik žiedas aplink kraštą
            shader = RadialGradient(h, h, h,
                intArrayOf(0x00FFC060, 0x00FFC060, 0xCCFFC060.toInt(), 0x55FF9030, 0x00FF8000),
                floatArrayOf(0f, 0.36f, 0.42f, 0.62f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawCircle(h, h, h, glow)
        return uploadTexture(bmp)
    }

    private var sunProg = 0
    // GEO palydovai (~36 000 km) scenoje yra ~1,6 vnt. nuo stebėtojo; Saulė – toliau, už jų
    private val SUN_DIST = 2.3
    private val sunMat = FloatArray(16)
    private val sunMvp = FloatArray(16)

    /** 3D Saulės rutulys: animuota granuliacija, dėmės, kraštų patamsėjimas; lėtai sukasi. */
    private fun drawSun3D(sp: FloatArray, t: Float) {
        if (earthPos == null) return
        if (sunProg == 0) sunProg = program(SUN_VS, SUN_FS)
        val k = 0.48f                                     // spindulys 0,12 scenos vnt.
        val a = t * 0.08                                  // lėtas sukimasis aplink ašį
        java.util.Arrays.fill(sunMat, 0f)
        sunMat[0] = (cos(a) * k).toFloat(); sunMat[1] = (sin(a) * k).toFloat()
        sunMat[4] = (-sin(a) * k).toFloat(); sunMat[5] = (cos(a) * k).toFloat()
        sunMat[10] = k
        sunMat[12] = sp[0]; sunMat[13] = sp[1]; sunMat[14] = sp[2]; sunMat[15] = 1f
        Matrix.multiplyMM(sunMvp, 0, mvpI, 0, sunMat, 0)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(sunProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(sunProg, "uMVP"), 1, false, sunMvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(sunProg, "uModel"), 1, false, sunMat, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(sunProg, "uEye"), eyeI[0], eyeI[1], eyeI[2])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(sunProg, "uCam"), eyeI[0] * zoom, eyeI[1] * zoom, eyeI[2] * zoom)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(sunProg, "uTime"), t)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(sunProg, "uHasTex"), if (sunImgTex != 0) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sunImgTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(sunProg, "uTex"), 0)
        resetAttribs()
        val lp = GLES20.glGetAttribLocation(sunProg, "aPos")
        val lu = GLES20.glGetAttribLocation(sunProg, "aUV")
        GLES20.glEnableVertexAttribArray(lp)
        earthPos!!.position(0)
        GLES20.glVertexAttribPointer(lp, 3, GLES20.GL_FLOAT, false, 0, earthPos)
        if (lu >= 0) {
            GLES20.glEnableVertexAttribArray(lu)
            earthUv!!.position(0)
            GLES20.glVertexAttribPointer(lu, 2, GLES20.GL_FLOAT, false, 0, earthUv)
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, earthCount)

        // 3D korona: didesnis rutulys aplink Saulę, švyti tik prie krašto (pridėtinis maišymas)
        if (glowProg == 0) glowProg = program(GLOW_VS, SUNGLOW_FS)
        val gk = k * 2.0f
        val gm = FloatArray(16)
        gm[0] = gk; gm[5] = gk; gm[10] = gk
        gm[12] = sp[0]; gm[13] = sp[1]; gm[14] = sp[2]; gm[15] = 1f
        val gmvp = FloatArray(16)
        Matrix.multiplyMM(gmvp, 0, mvpI, 0, gm, 0)
        GLES20.glDepthMask(false)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        GLES20.glUseProgram(glowProg)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(glowProg, "uMVP"), 1, false, gmvp, 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(glowProg, "uModel"), 1, false, gm, 0)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(glowProg, "uCam"), eyeI[0] * zoom, eyeI[1] * zoom, eyeI[2] * zoom)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(glowProg, "uCenter"), sp[0], sp[1], sp[2])
        GLES20.glUniform1f(GLES20.glGetUniformLocation(glowProg, "uTime"), t)
        resetAttribs()
        val gl = GLES20.glGetAttribLocation(glowProg, "aPos")
        GLES20.glEnableVertexAttribArray(gl)
        earthPos!!.position(0)
        GLES20.glVertexAttribPointer(gl, 3, GLES20.GL_FLOAT, false, 0, earthPos)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, earthCount)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private var glowProg = 0

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

    private fun resetAttribs() {
        for (i in 0 until 4) GLES20.glDisableVertexAttribArray(i)
    }

    private fun bindPos(prog: Int, buf: FloatBuffer) {
        resetAttribs()
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
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
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
                float wave = 0.5 + 0.5 * sin(uTime * 2.2);   // visas kupolas pulsuoja kartu (be horizontalių juostų)
                vec3 h = normalize(l + normalize(uEye));
                float spec = pow(max(dot(n, h), 0.0), 64.0);
                vec3 rim = uTint * pow(fres, 1.4) * 1.6;
                vec3 col = uTint * (0.08 + 0.40 * d) + rim + vec3(spec) * 0.55 + uTint * wave * uBeat * 0.30;
                float a = 0.30 + 0.55 * pow(fres, 1.2) + 0.25 * uBeat * wave + spec * 0.6;
                float nz = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
                col += (nz - 0.5) / 128.0;
                gl_FragColor = vec4(col, clamp(a + (nz - 0.5) / 255.0, 0.0, 1.0));
            }
        """
        private const val LOW_FS = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec3 vN;
            varying float vZ;
            uniform vec3 uEye;
            uniform vec4 uColor;
            void main() {
                vec3 n = normalize(vN);
                float fres = pow(1.0 - abs(dot(n, normalize(uEye))), 2.0);
                float nz = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
                gl_FragColor = vec4(uColor.rgb + (nz - 0.5) / 128.0, uColor.a * (0.6 + 0.4 * fres));
            }
        """
        private const val COLOR_VS = """
            attribute vec3 aPos;
            uniform mat4 uMVP;
            uniform float uPoint;
            void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uPoint; }
        """
        private const val MAT_BODY = 0
        private const val MAT_PANEL = 1
        private const val MAT_METAL = 2
        private const val MAT_DISH = 3
        private const val MAT_DARK = 4
        private const val SAT_VS = """
            attribute vec3 aPos;
            attribute vec3 aNormal;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            varying vec3 vN;
            varying vec3 vP;
            void main() {
                vN = mat3(uModel) * aNormal;
                vP = aPos;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val SAT_FS = """
            precision mediump float;
            uniform vec4 uColor;
            uniform vec3 uGlow;
            uniform vec3 uLight;
            uniform vec3 uEye;
            uniform vec3 uSpec;
            uniform float uShine;
            uniform float uPanel;
            varying vec3 vN;
            varying vec3 vP;
            void main() {
                vec3 n = normalize(vN);
                vec3 e = normalize(uEye);
                vec3 l = normalize(uLight);
                if (dot(n, e) < 0.0) n = -n;                       // dvipusės plokštumos (lėkštė, tūta)
                float d = max(dot(n, l), 0.0);
                vec3 base = uColor.rgb;
                if (uPanel > 0.5) {
                    // saulės elementų tinklelis: tamsios siūlės ir šviesesnės šynos
                    vec2 g = abs(fract(vec2(vP.x * 14.0, vP.z * 10.0)) - 0.5);
                    float seam = smoothstep(0.40, 0.47, max(g.x, g.y));
                    float bus = smoothstep(0.035, 0.0, abs(fract(vP.z * 5.0) - 0.5));
                    base = mix(base, vec3(0.01, 0.015, 0.05), seam) + vec3(0.10, 0.12, 0.16) * bus * (1.0 - seam);
                }
                vec3 h = normalize(l + e);
                float sp = pow(max(dot(n, h), 0.0), uShine) * step(0.0, dot(n, l));
                float rim = pow(1.0 - max(dot(n, e), 0.0), 3.0);
                vec3 c = base * (0.22 + 0.78 * d) + uSpec * sp * 0.8 + base * rim * 0.35 + uGlow * (0.6 + 0.6 * rim);
                gl_FragColor = vec4(min(c, vec3(1.0)), uColor.a);
            }
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
        private const val EARTH_VS = """
            attribute vec3 aPos;
            attribute vec2 aUV;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            varying vec2 vUV;
            varying vec3 vN;
            varying vec3 vS;
            void main() {
                vUV = aUV;
                vN = mat3(uModel) * aPos;
                vS = vN;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val SUN_VS = """
            attribute vec3 aPos;
            attribute vec2 aUV;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            varying vec3 vO;
            varying vec3 vN;
            varying vec2 vUV;
            varying vec3 vW;
            void main() {
                vO = aPos;
                vUV = aUV;
                vW = (uModel * vec4(aPos, 1.0)).xyz;
                vN = mat3(uModel) * aPos;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val SUN_FS = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec3 vO;
            varying vec3 vN;
            varying vec2 vUV;
            varying vec3 vW;
            uniform vec3 uEye;
            uniform vec3 uCam;
            uniform float uTime;
            uniform sampler2D uTex;
            uniform float uHasTex;
            float hash(vec3 p) {
                return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453);
            }
            float noise(vec3 x) {
                vec3 i = floor(x);
                vec3 f = fract(x);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(mix(hash(i), hash(i + vec3(1.0, 0.0, 0.0)), f.x),
                               mix(hash(i + vec3(0.0, 1.0, 0.0)), hash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
                           mix(mix(hash(i + vec3(0.0, 0.0, 1.0)), hash(i + vec3(1.0, 0.0, 1.0)), f.x),
                               mix(hash(i + vec3(0.0, 1.0, 1.0)), hash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
            }
            void main() {
                vec3 p = normalize(vO);
                float t = uTime * 0.06;
                float n = 0.6 * noise(p * 4.0 + vec3(t, 0.0, 0.0))
                        + 0.4 * noise(p * 8.0 - vec3(0.0, t * 1.4, 0.0));
                n = smoothstep(0.15, 0.85, n);
                float spots = smoothstep(0.78, 0.86, noise(p * 2.5 + vec3(7.0)));
                vec3 hot = vec3(1.0, 0.96, 0.72);
                vec3 warm = vec3(1.0, 0.52, 0.08);
                vec3 col = mix(warm, hot, n);
                col *= 1.0 - 0.35 * spots;
                if (uHasTex > 0.5) {
                    // NASA SDO žemėlapis + lengvas mirgėjimas, kad paviršius „gyventų“
                    col = texture2D(uTex, vUV).rgb * 1.25 * (0.92 + 0.16 * n);
                }
                float mu = max(dot(normalize(vN), normalize(uCam - vW)), 0.0);
                col *= 0.42 + 0.58 * pow(mu, 0.5);      // kraštų patamsėjimas (limb darkening)
                col += vec3(1.0, 0.6, 0.2) * pow(1.0 - mu, 4.0) * 0.6;   // ryškus kraštas
                gl_FragColor = vec4(col, 1.0);
            }
        """
        private const val GLOW_VS = """
            attribute vec3 aPos;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            varying vec3 vW;
            void main() {
                vW = (uModel * vec4(aPos, 1.0)).xyz;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val SUNGLOW_FS = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform vec3 uCam;
            uniform vec3 uCenter;
            uniform float uTime;
            varying vec3 vW;
            void main() {
                // Spindulio nuo kameros atstumas iki Saulės centro – korona visada koncentriška su Saule
                vec3 d = normalize(vW - uCam);
                vec3 oc = uCenter - uCam;
                float tc = dot(oc, d);
                float dist = length(oc - d * tc);
                float shellR = length(vW - uCenter);
                float r = dist / shellR;                      // 0 centre, 1 korona kraštas
                float r0 = 1.0 / 2.0;                         // Saulės kraštas
                float g = r < r0 ? 0.35 : pow(clamp((1.0 - r) / (1.0 - r0), 0.0, 1.0), 1.8);
                g *= 0.85 + 0.15 * sin(uTime * 1.5);
                gl_FragColor = vec4(vec3(1.0, 0.62, 0.22) * g, g);
            }
        """
        private const val ATMO_VS = """
            attribute vec3 aPos;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            varying vec3 vN;
            void main() {
                vN = mat3(uModel) * aPos;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val ATMO_FS = """
            precision mediump float;
            uniform vec3 uEye;
            varying vec3 vN;
            void main() {
                vec3 n = normalize(vN);
                float fres = pow(1.0 - max(dot(n, normalize(uEye)), 0.0), 3.5);
                gl_FragColor = vec4(vec3(0.3, 0.55, 1.0) * fres, fres * 0.9);
            }
        """
        private const val MOON_VS = """
            attribute vec3 aPos;
            attribute vec2 aUV;
            attribute vec3 aNormal;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            varying vec2 vUV;
            varying vec3 vN;
            void main() {
                vUV = aUV;
                vN = mat3(uModel) * aNormal;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val MOON_FS = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec3 uLight;
            varying vec2 vUV;
            varying vec3 vN;
            void main() {
                vec3 n = normalize(vN);
                float d = max(dot(n, normalize(uLight)), 0.0);
                vec3 t = texture2D(uTex, vUV).rgb;
                // be atmosferos: ryškus terminatorius, silpna Žemės šviesa nakties pusėje
                gl_FragColor = vec4(t * (0.03 + 1.15 * d), 1.0);
            }
        """
        private const val EARTH_RELIEF_VS = """
            attribute vec3 aPos;
            attribute vec2 aUV;
            attribute vec3 aNormal;
            uniform mat4 uMVP;
            uniform mat4 uModel;
            varying vec2 vUV;
            varying vec3 vN;
            varying vec3 vS;
            void main() {
                vUV = aUV;
                vN = mat3(uModel) * aNormal;      // reljefo normalė – šlaitų šešėliai
                vS = mat3(uModel) * aPos;         // rutulio normalė – diena/naktis, atmosfera, vandens blizgesys
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        private const val EARTH_FS = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform sampler2D uNight;
            uniform sampler2D uMask;
            uniform float uHasNight;
            uniform float uHasMask;
            uniform float uWater;
            uniform vec3 uLight;
            uniform vec3 uEye;
            varying vec2 vUV;
            varying vec3 vN;
            varying vec3 vS;
            float oceanAt(vec2 uv) {
                if (uHasMask > 0.5) return step(0.004, texture2D(uMask, uv).r);
                return 1.0 - smoothstep(0.08, 0.22, texture2D(uTex, uv).r);
            }
            void main() {
                vec3 n = normalize(vN);
                vec3 sN = normalize(vS);
                vec3 l = normalize(uLight);
                vec3 v = normalize(uEye);
                float nl = dot(sN, l);                             // terminatorius – pagal rutulį
                float d = max(dot(n, l), 0.0);                     // šešėliai – pagal reljefą
                float soft = smoothstep(-0.12, 0.25, nl);          // švelnus terminatorius
                vec4 t = texture2D(uTex, vUV);
                float ocean = oceanAt(vUV) * uWater;
                float nv = max(dot(sN, v), 0.0);
                float fres = pow(1.0 - nv, 3.0);
                vec3 atm = vec3(0.35, 0.6, 1.0) * fres * 1.1;      // atmosferos kraštas
                vec3 base;
                if (uHasMask > 0.5) {
                    // Blue Marble jau nuspalvina vandenyną pagal gylį; dugno reljefas – iš geometrijos.
                    // Gelmės +30 %, seklumos ir povandeniniai kalnagūbriai – dar šviesiau; sausuma +15 %
                    float m = texture2D(uMask, vUV).r;
                    float gain = mix(1.75, 1.3, smoothstep(0.05, 0.45, m));
                    base = t.rgb * mix(1.15, gain, ocean);
                } else {
                    // Gylis: kuo toliau nuo kranto (daugiau vandens aplink), tuo giliau
                    float r = 0.012;
                    float deep = 0.0;
                    deep += oceanAt(vUV + vec2( r, 0.0)) + oceanAt(vUV + vec2(-r, 0.0));
                    deep += oceanAt(vUV + vec2(0.0,  r)) + oceanAt(vUV + vec2(0.0, -r));
                    deep += oceanAt(vUV + vec2( r,  r) * 2.0) + oceanAt(vUV + vec2(-r, -r) * 2.0);
                    deep += oceanAt(vUV + vec2( r, -r) * 2.0) + oceanAt(vUV + vec2(-r,  r) * 2.0);
                    deep = smoothstep(0.3, 1.0, deep / 8.0) * ocean;
                    // Permatomas vanduo: sekluma turkio, gelmė beveik juoda mėlyna
                    vec3 shallow = mix(t.rgb, vec3(0.05, 0.32, 0.42), 0.45);
                    vec3 abyss = vec3(0.004, 0.02, 0.07);
                    vec3 water = mix(shallow, abyss, deep);
                    float path = deep * (1.0 + 1.5 * (1.0 - nv));
                    water *= exp(-1.2 * path) * 0.7 + 0.3;
                    base = mix(t.rgb, water, ocean);
                }
                // Blizgesys: ryškus Saulės atspindys ir dangaus atspindys pakraščiuose (lygus vandens paviršius)
                vec3 h = normalize(l + v);
                float nh = max(dot(sN, h), 0.0);
                float spec = (pow(nh, 160.0) * 1.6 + pow(nh, 24.0) * 0.18) * ocean;
                float wFres = 0.02 + 0.98 * pow(1.0 - nv, 5.0);
                vec3 sky = vec3(0.35, 0.55, 0.85) * wFres * ocean * soft;
                // Apšviesta pusė šviesesnė: daugiau išsklaidytos šviesos, šlaitų šešėliai švelnesni
                float lit = mix(d, max(dot(sN, l), 0.0), 0.35);
                vec3 day = base * (0.2 + 1.1 * lit * soft) + vec3(1.0, 0.95, 0.85) * spec * soft + sky + atm;
                // Naktinė pusė: miestų šviesų nuotrauka vietoj šešėlio
                vec3 night = texture2D(uNight, vUV).rgb * 1.6 + atm * 0.35;
                float dayW = mix(1.0, smoothstep(-0.18, 0.12, nl), uHasNight);
                gl_FragColor = vec4(mix(night, day, dayW), 1.0);
            }
        """
        private const val CMB_VS = """
            attribute vec3 aPos;
            attribute vec2 aUV;
            uniform mat4 uMVP;
            varying vec2 vUV;
            void main() { vUV = aUV; gl_Position = uMVP * vec4(aPos, 1.0); }
        """
        private const val CMB_FS = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform float uAlpha;
            varying vec2 vUV;
            void main() { gl_FragColor = vec4(texture2D(uTex, vUV).rgb, uAlpha); }
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
                if (t.a < 0.02) discard;
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
    @Volatile var recording = false
    @Volatile var onUnpin: (() -> Unit)? = null
}

class BubbleOverlayService : Service() {

    private var wm: WindowManager? = null
    private var bubble: BubbleGL? = null
    private val handler = Handler(Looper.getMainLooper())
    private val pollSignal = object : Runnable {
        override fun run() {
            bubble?.setSignal(OverlayBus.avg, OverlayBus.peak)
            bubble?.setRecording(OverlayBus.recording)
            handler.postDelayed(this, 300L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            setupOverlay()
        } catch (e: Exception) {
            android.util.Log.e("GnssApp", "Perdengimas nepavyko", e)
            // Nepavykus – grąžiname burbulą į programą ir pranešame
            OverlayBus.onUnpin?.invoke()
            OverlayBus.onUnpin = null
            handler.post { Toast.makeText(this, "Prisegti nepavyko: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show() }
            stopSelf()
        }
    }

    private fun setupOverlay() {
        startAsForeground()
        val dm = resources.displayMetrics
        val size = (88 * dm.density).toInt()
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val view = BubbleGL(this)
        bubble = view
        val lp = WindowManager.LayoutParams(
            size, size * 3 / 2,
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
            override fun onDown(e: MotionEvent): Boolean { keyHaptic(view); return true }
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
                    lp.y = ny.coerceIn(-(size * 1.35f).toInt(), dm.heightPixels - (size * 0.15f).toInt())
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
        // Grąžinti programą į priekį, kad burbulas vėl atsirastų savo vietoje
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) {
            android.util.Log.e("GnssApp", "Grįžimas nepavyko", e)
        }
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
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
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
        layoutParams = LayoutParams(bubbleSize, bubbleSize * 3 / 2)
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
        setText("900")
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

    private val envOptions = arrayOf("Aplinka: nenurodyta", "Lauke, atviras dangus", "Lauke, tarp pastatų", "Lauke, miške",
        "Prie lango", "Patalpoje", "Automobilyje", "Krano kabinoje", "Ant krano (atvirai)", "Einant / judant")
    private val envSpinner = Spinner(context).apply {
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, envOptions)
        minimumHeight = (44 * resources.displayMetrics.density).toInt()
    }
    // Etaloninės (žinomos) vertės – išsaugomos, kad nereikėtų kas kartą rašyti iš naujo
    private val refPrefs = context.getSharedPreferences("known_ref", Context.MODE_PRIVATE)
    private fun refField(hintText: String, key: String, type: Int) = EditText(context).apply {
        hint = hintText
        textSize = 14f
        setSingleLine()
        inputType = type
        setText(refPrefs.getString(key, ""))
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                refPrefs.edit().putString(key, s?.toString() ?: "").apply()
            }
        })
    }
    // Slenkama panelės dalis (antraštė lieka viršuje)
    private val bodyInner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val body = ScrollView(context).apply {
        isFillViewport = false
        isVerticalScrollBarEnabled = true
        addView(bodyInner)
    }

    private val numType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
    private val aglInput = refField("Žinomas aukštis virš žemės, m", "agl", numType)
    private val mslInput = refField("Žinomas aukštis virš jūros, m", "msl", numType)
    private val coordInput = refField("Žinomos koordinatės: platuma, ilguma", "coord", InputType.TYPE_CLASS_TEXT)

    private fun num(e: EditText): Double? = e.text.toString().trim().replace(',', '.').toDoubleOrNull()
    val knownAgl: Double? get() = num(aglInput)
    val knownMsl: Double? get() = num(mslInput)
    /** Koordinatės „54.7399, 25.2546“ (leidžiamas tarpas, kabliataškis arba kablelis tarp skaičių). */
    val knownLatLon: Pair<Double, Double>? get() {
        val parts = coordInput.text.toString().trim().split(Regex("[;\\s]+|,\\s+"))
            .map { it.trim().replace(',', '.') }.filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val la = parts[0].toDoubleOrNull() ?: return null
        val lo = parts[1].toDoubleOrNull() ?: return null
        return if (la in -90.0..90.0 && lo in -180.0..180.0) la to lo else null
    }

    /** Vartotojo nurodyta aplinka (įrašoma į CSV ir ataskaitą). */
    val envTag: String get() = if (envSpinner.selectedItemPosition <= 0) "" else envOptions[envSpinner.selectedItemPosition]

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

    private val dragSlop = 10f * resources.displayMetrics.density
    private val cancelBtn = smallButton("\u2715", 44)     // ✕ (ir atšaukti, ir sumažinti)

    /** „Baigti įrašą“ / „Tęsti įrašą“ – kviečiama veiklos. */
    var onEndRecord: (() -> Unit)? = null
    private val endRecBtn = Button(context).apply {
        text = "Baigti įrašą"
        textSize = 12f
        isAllCaps = false
        minWidth = 0; minimumWidth = 0
        setPadding((8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt(), 0)
    }
    fun setRecording(on: Boolean) { endRecBtn.text = if (on) "Baigti įrašą" else "Tęsti įrašą" }

    /** „Šaltas startas“ – ištrinti GNSS pagalbos duomenis. */
    var onColdStart: (() -> Unit)? = null
    private val coldBtn = Button(context).apply {
        text = "Šaltas startas"
        textSize = 12f
        isAllCaps = false
        minWidth = 0; minimumWidth = 0
        setPadding((8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt(), 0)
    }

    /** „Atnaujinti SP3“ – atsisiųsti naujausią CODE ultra-greitų orbitų failą iš AIUB. */
    var onUpdateSp3: (() -> Unit)? = null
    private val sp3UpdateBtn = Button(context).apply {
        text = "Atnaujinti SP3"
        textSize = 12f
        isAllCaps = false
        minWidth = 0; minimumWidth = 0
        setPadding((8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt(), 0)
    }
    private val sp3Status = TextView(context).apply {
        text = "SP3: nėra"
        textSize = 12f
        setTextColor(Color.DKGRAY)
    }
    fun setSp3Status(line: String) { sp3Status.text = line }
    fun setSp3Busy(busy: Boolean) { sp3UpdateBtn.isEnabled = !busy; if (busy) sp3Status.text = "SP3: atsisiunčiama…" }

    var onPickSp3: (() -> Unit)? = null
    private val sp3Btn = Button(context).apply {
        text = "Pasirinkti SP3 aplanką"
        textSize = 12f
        isAllCaps = false
    }

    private val shareBtn = FrameLayout(context).apply {
        isClickable = true
        layoutParams = LinearLayout.LayoutParams((52 * density).toInt(), (52 * density).toInt())
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFFE2E8F0.toInt())
        }
        addView(ShareIcon(context), LayoutParams((52 * density * 0.6f).toInt(), (52 * density * 0.6f).toInt(), Gravity.CENTER))
    }

    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val inputRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val recRow = LinearLayout(context).apply {
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
            "raw" to "Matavimai + fiksai (2 CSV)"
        )
        for ((k, v) in opts) {
            val cb = CheckBox(context).apply {
                text = v
                textSize = 15f
                minHeight = (44 * density).toInt()
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
        inputRow.addView(sp3UpdateBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48 * density).toInt()))
        inputRow.addView(shareBtn)
        recRow.addView(coldBtn, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f))
        recRow.addView(endRecBtn, LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f))

        // Veiksmų eilutės neslenka, todėl dalinimosi mygtukas visada matomas
        panel.addView(header)
        panel.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(recRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(inputRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        panel.addView(sp3Status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        bodyInner.addView(optionsLayout)
        bodyInner.addView(rawRow)
        bodyInner.addView(alphaLabel)
        bodyInner.addView(alphaSeek)
        bodyInner.addView(envSpinner)
        bodyInner.addView(TextView(context).apply {
            text = "Žinomos (tikrosios) vertės – paklaidoms skaičiuoti:"
            textSize = 12f
            setTextColor(Color.DKGRAY)
        })
        bodyInner.addView(aglInput)
        bodyInner.addView(mslInput)
        bodyInner.addView(coordInput)
        bodyInner.addView(sp3Btn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (48 * density).toInt()))

        addView(panel)
        addView(bubble)

        cancelBtn.setOnClickListener { minimize() }
        shareBtn.setOnClickListener { runShare() }
        endRecBtn.setOnClickListener { onEndRecord?.invoke() }
        coldBtn.setOnClickListener { onColdStart?.invoke() }
        sp3Btn.setOnClickListener { onPickSp3?.invoke() }
        sp3UpdateBtn.setOnClickListener { onUpdateSp3?.invoke() }

        bubble.setOnTouchListener { _, ev -> bubbleGd.onTouchEvent(ev); false }
    }

    /** Burbulo spalva ir efektai: avg – vidutinis C/N0, peak – maksimalus. */
    fun setSignal(avgDb: Float?, peakDb: Float?) {
        bubble.setSignal(avgDb, peakDb)
    }


    var onPin: (() -> Unit)? = null
    private val bubbleAction: () -> Unit = { expandBubble() }
    private val bubbleGd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { keyHaptic(bubble); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { bubbleAction?.invoke(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { onPin?.invoke(); return true }
    })

    private fun expandBubble() {
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
            val secs = rawSecs.text.toString().toIntOrNull()?.coerceIn(5, 1800) ?: 900
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
    private fun setRecordingUi(on: Boolean) {
        bubble.setRecording(on)
        OverlayBus.recording = on
        // Įrašo metu ekranas neužgęsta – kitaip Android sustabdo GNSS matavimus
        (context as? android.app.Activity)?.window?.let { w ->
            if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun startCountdown(secs: Int, done: () -> Unit) {
        counting = true
        setRecordingUi(true)
        remaining = secs
        rawSecs.isEnabled = false
        shareBtn.isEnabled = false
        onCountdownStart?.invoke()
        val tick = object : Runnable {
            override fun run() {
                if (remaining <= 0) {
                    counting = false
                    setRecordingUi(false)
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

    private var kbShift = 0f
    private val kbListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        if (panel.visibility != VISIBLE) return@OnGlobalLayoutListener
        val r = Rect()
        rootView.getWindowVisibleDisplayFrame(r)
        val screenH = rootView.height
        val kb = screenH - r.bottom
        val f = findFocus()
        if (kb > screenH * 0.15f && f is EditText) {
            // Pirma pasukame slenkamą dalį, kad laukas būtų matomas
            val fr = Rect(); f.getDrawingRect(fr)
            try { body.offsetDescendantRectToMyCoords(f, fr); body.requestChildRectangleOnScreen(f, Rect(0, 0, f.width, f.height), true) } catch (_: Exception) {}
            val loc = IntArray(2); f.getLocationOnScreen(loc)
            val over = loc[1] + f.height + (12 * density) - r.bottom
            if (over > 0) { kbShift += over; translationY -= over }
        } else if (kb <= screenH * 0.15f && kbShift != 0f) {
            translationY += kbShift
            kbShift = 0f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnGlobalLayoutListener(kbListener)
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnGlobalLayoutListener(kbListener)
        handler.removeCallbacksAndMessages(null)
        counting = false
        setRecordingUi(false)
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
                    inside(alphaSeek, ev.rawX, ev.rawY) || inside(rawSecs, ev.rawX, ev.rawY) ||
                    inside(envSpinner, ev.rawX, ev.rawY) || inside(aglInput, ev.rawX, ev.rawY) ||
                    inside(mslInput, ev.rawX, ev.rawY) || inside(coordInput, ev.rawX, ev.rawY)
                if (touchOnControl) return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dxm = abs(ev.rawX - lastX); val dym = abs(ev.rawY - lastY)
                if (inside(body, lastX, lastY) && body.canScrollVertically(if (ev.rawY < lastY) 1 else -1) && dym > dxm) {
                    return false   // slenkamas turinys
                }
                if (!touchOnControl && !isDragging) {
                    // Tempimas tik aiškiai pajudinus (≈20 dp), kad bakstelėjimai nebūtų „suvalgomi“
                    if (abs(ev.rawX - lastX) > dragSlop || abs(ev.rawY - lastY) > dragSlop) {
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
            MotionEvent.ACTION_DOWN -> {
                lastX = event.rawX
                lastY = event.rawY
                isDragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Tempimas ir už tuščios panelės vietos (antraštės, kraštų)
                if (!isDragging && (abs(event.rawX - lastX) > dragSlop || abs(event.rawY - lastY) > dragSlop)) {
                    isDragging = true
                }
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
        post { limitBodyHeight(); post { adjustPositionOnExpand() } }
    }

    /** Jei turinys netelpa ekrane (telefone), slenkama dalis apribojama ir tampa slenkama. */
    private fun limitBodyHeight() {
        val parentH = (parent as? View)?.height ?: return
        val w = body.width.takeIf { it > 0 } ?: (width - panel.paddingLeft - panel.paddingRight)
        bodyInner.measure(MeasureSpec.makeMeasureSpec(w.coerceAtLeast(1), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val rowW = MeasureSpec.makeMeasureSpec(w.coerceAtLeast(1), MeasureSpec.EXACTLY)
        val rowH = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        recRow.measure(rowW, rowH)
        inputRow.measure(rowW, rowH)
        sp3Status.measure(rowW, rowH)
        val maxBody = (parentH * 0.88f).toInt() - header.height - recRow.measuredHeight - inputRow.measuredHeight - sp3Status.measuredHeight -
            panel.paddingTop - panel.paddingBottom
        val lp = body.layoutParams
        lp.height = if (bodyInner.measuredHeight > maxBody && maxBody > 0) maxBody else LinearLayout.LayoutParams.WRAP_CONTENT
        body.layoutParams = lp
    }

    private fun adjustPositionOnExpand() {
        val parentW = (parent as? View)?.width?.toFloat() ?: return
        val parentH = (parent as? View)?.height?.toFloat() ?: return

        // Visa panelė ekrane: dalinimosi mygtukas apačioje neturi likti už krašto
        translationX = translationX.coerceIn(0f, (parentW - width).coerceAtLeast(0f))
        translationY = translationY.coerceIn(0f, (parentH - height).coerceAtLeast(0f))
    }

    /** Pasukus ekraną: panelė perskaičiuojama, burbulas grąžinamas į ekraną. */
    fun onParentResized() {
        kbShift = 0f
        if (panel.visibility == VISIBLE) maximize() else adjustPositionOnExpand()
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

/** Bendra spalva visiems elementams (burbulas, fonas, dangaus kupolas) pagal C/N0. */
/** Trumpas „klaviatūros“ vibracijos atsakas (crisp click), kaip spaudžiant klavišą. */
fun keyHaptic(v: View) {
    try {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val vib = v.context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (vib != null && vib.hasVibrator()) {
            when {
                Build.VERSION.SDK_INT >= 29 ->
                    vib.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                Build.VERSION.SDK_INT >= 26 ->
                    vib.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
                else -> @Suppress("DEPRECATION") vib.vibrate(12)
            }
        }
    } catch (e: Throwable) {
        android.util.Log.w("GnssApp", "Vibracija nepavyko", e)
    }
}

/** ULTRA riba: 4 stipriausių naudojamų palydovų C/N0 vidurkis (kaip GNSS Logger). */
const val ULTRA_DB = 40f

fun top4Avg(list: List<Sat>): Float? {
    val v = list.filter { it.used }.map { it.cn0 }.sortedDescending().take(4)
    return if (v.isEmpty()) null else v.average().toFloat()
}

fun signalTint(avg: Float?, peak: Float?): Int = when {
    avg == null -> 0xFF3B82F6.toInt()
    avg < 20f -> 0xFF1F2937.toInt()
    (peak ?: 0f) >= ULTRA_DB -> 0xFFFACC15.toInt()
    else -> signalColor(avg)
}

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

private const val REPORT_FILE = "ataskaita.txt"

class MainActivity : AppCompatActivity() {
    private lateinit var lm: LocationManager
    private lateinit var info: TextView
    private lateinit var skyView: GnssView
    private lateinit var skyDome: SkyDomeGL

    /** Perjungia 2D dangų ir 3D gaublį jų vietoje (dvigubas bakstelėjimas). */
    private fun setGlobe(on: Boolean) {
        use3d = on
        skyView.visibility = if (on) View.GONE else View.VISIBLE
        if (!on && fullscreen) toggleFullscreen()
        skyHolder.visibility = if (on) View.VISIBLE else View.GONE
        if (on) skyDome.start() else skyDome.stop()
    }

    private lateinit var skyHolder: FrameLayout
    private lateinit var fsBtn: TextView
    private var skyHolderParent: ViewGroup? = null
    private var skyHolderLp: ViewGroup.LayoutParams? = null
    private var fullscreen = false

    private var leftCol: View? = null
    private var rightCol: View? = null
    private var contentCol: LinearLayout? = null
    private var rootScroll: ScrollView? = null

    /**
     * Gaublys per visą ekraną ir atgal. GL vaizdas NEperkeliamas į kitą tėvą
     * (tai gadindavo vaizdą ir uždarydavo programą) – keičiamas tik jo dydis,
     * o kiti elementai laikinai paslepiami.
     */
    private fun toggleFullscreen() {
        if (!::skyHolder.isInitialized) return
        if (!fullscreen) {
            val w = container.width
            val h = container.height
            if (w <= 0 || h <= 0) return
            leftCol?.visibility = View.GONE
            cnoView.visibility = View.GONE
            rootScroll?.scrollTo(0, 0)
            skyHolder.layoutParams = LinearLayout.LayoutParams(w, h)
            fsBtn.text = "\u2715"
            fullscreen = true
        } else {
            leftCol?.visibility = View.VISIBLE
            cnoView.visibility = View.VISIBLE
            skyHolder.layoutParams = skyHolderLp
            fsBtn.text = "\u26F6"
            fullscreen = false
        }
        skyHolder.requestLayout()
        sharePanel?.bringToFront()
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
        try {
            startForegroundService(Intent(this, BubbleOverlayService::class.java))
        } catch (e: Exception) {
            android.util.Log.e("GnssApp", "Burbulo prisegimas nepavyko", e)
            OverlayBus.onUnpin = null
            sharePanel?.visibility = View.VISIBLE
            Toast.makeText(this, "Prisegti nepavyko: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }
    private var use3d = false
    private var southUpOn = false
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
            satMap.clear()
            for (x in list) satMap[x.const + x.svid] = x
            skyView.sats = list
            if (::skyDome.isInitialized) skyDome.setSats(list)
            val used = list.filter { it.used }
            val avg = if (used.isNotEmpty()) used.map { it.cn0.toDouble() }.average() else 0.0
            val max = list.maxOfOrNull { it.cn0 } ?: 0f
            val byConst = list.groupingBy { it.const }.eachCount().toSortedMap()

            cnoView.addSample(list, if (used.isNotEmpty()) avg.toFloat() else null)
            bg.setSignal(if (used.isNotEmpty()) avg.toFloat() else null)
            OverlayBus.avg = if (used.isNotEmpty()) avg.toFloat() else null
            OverlayBus.peak = top4Avg(list)
            sharePanel?.setSignal(
                if (used.isNotEmpty()) avg.toFloat() else null,
                top4Avg(list)
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
            RawRecorder.envTag = envTag()
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

    private var afterSp3Pick: (() -> Unit)? = null

    private val sp3PickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            if (Sp3Storage.savePick(this, uri)) Toast.makeText(this, "SP3 aplankas pasirinktas", Toast.LENGTH_SHORT).show()
            else logError("SP3 aplanko leidimas nesuteiktas")
        }
        afterSp3Pick?.invoke()
        afterSp3Pick = null
    }

    private fun launchSp3Picker(next: (() -> Unit)? = null) {
        afterSp3Pick = next
        // Android 11+ neleidžia pasirinkti paties Download, tik jo poaplankį
        Toast.makeText(this, "Pasirinkite arba sukurkite Download poaplankį (pvz. SP3) ir laikykite SP3 failus jame", Toast.LENGTH_LONG).show()
        try {
            sp3PickLauncher.launch(Sp3Storage.initialUri())
        } catch (e: android.content.ActivityNotFoundException) {
            afterSp3Pick = null
            logError("Aplanko pasirinkimas nepasiekiamas: ${e.message}")
            next?.invoke()
        }
    }

    /** Būsenos eilutė po „Atnaujinti SP3“: atsisiųsto failo intervalas (UTC) arba „SP3: nėra“. */
    private fun refreshSp3Status() {
        Thread {
            val line = try { Sp3Aiub.status(this) } catch (e: Exception) { "SP3: nėra" }
            runOnUiThread { sharePanel?.setSp3Status(line) }
        }.start()
    }

    /** „Atnaujinti SP3“: AIUB CODE ultra-greitos orbitos fone į programos privatų aplanką. */
    private fun updateSp3() {
        sharePanel?.setSp3Busy(true)
        Thread {
            val r = try { Sp3Aiub.download(Sp3Aiub.dir(this)) } catch (e: Exception) { Sp3Aiub.Result.Fail("klaida: ${e.message}") }
            val status = try { Sp3Aiub.status(this) } catch (e: Exception) { "SP3: nėra" }
            runOnUiThread {
                sharePanel?.setSp3Busy(false)
                sharePanel?.setSp3Status(status)
                val msg = when (r) {
                    is Sp3Aiub.Result.Ok -> "SP3 atnaujintas: ${r.name}"
                    is Sp3Aiub.Result.Fail -> "SP3 neatnaujintas: ${r.message}"
                }
                if (r is Sp3Aiub.Result.Fail) logError(msg)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /** Vieną kartą pasiūlo pasirinkti SP3 aplanką, jei failo nerasta; po to tęsia eksportą. */
    private fun offerSp3PickerOnce(next: () -> Unit) {
        if (Sp3Storage.pickerOffered(this)) { next(); return }
        Thread {
            val missing = try { Sp3Storage.search(this).used == null } catch (e: Exception) { false }
            runOnUiThread {
                // Iš fono veiklos paleisti nepavyktų ir eksportas liktų laukti rezultato
                val visible = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
                if (!missing || !visible) { next(); return@runOnUiThread }
                Sp3Storage.markPickerOffered(this)
                launchSp3Picker(next)
            }
        }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Geoidas pradedamas krauti iš karto (fone), kol kuriamas vaizdas ir laukiama fikso.
        // render() įvykdomas per runOnUiThread, t. y. tik pasibaigus onCreate.
        Geoid.select(applicationContext, GeoidMode.LIT20G) { runOnUiThread { render() } }
        // Klaviatūra nekeičia ekrano išdėstymo (dalinimosi lapas nesusitraukia ir nejuda)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
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

        val dm = resources.displayMetrics
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val colW = if (landscape) dm.widthPixels / 2 else dm.widthPixels
        val skySide = if (landscape) min(colW, dm.heightPixels) else colW
        val cnoH = (260 * dm.density).toInt()
        val mw = LinearLayout.LayoutParams.MATCH_PARENT
        val ww = LinearLayout.LayoutParams.WRAP_CONTENT

        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        leftCol = left
        left.addView(modeSpinner, LinearLayout.LayoutParams(mw, ww))
        left.addView(btnZero, LinearLayout.LayoutParams(mw, ww))
        left.addView(btnGps, LinearLayout.LayoutParams(mw, ww))
        left.addView(btnReset, LinearLayout.LayoutParams(mw, ww))
        val btnSaveN = Button(this).apply {
            text = "Įrašyti šiaurę (3D: pasukite N į tikrąją šiaurę)"
            setOnClickListener { if (::skyDome.isInitialized) skyDome.saveNorth() }
        }
        val btnSouth = Button(this).apply {
            text = "Pietų vaizdas (apversti Žemę)"
            setOnClickListener {
                southUpOn = !southUpOn
                if (::skyDome.isInitialized) skyDome.setSouthUp(southUpOn)
            }
        }
        left.addView(btnSouth, LinearLayout.LayoutParams(mw, ww))
        val btnResetN = Button(this).apply {
            text = "Atstatyti šiaurę (3D)"
            setOnClickListener { if (::skyDome.isInitialized) skyDome.resetNorth() }
        }
        left.addView(btnSaveN, LinearLayout.LayoutParams(mw, ww))
        left.addView(btnResetN, LinearLayout.LayoutParams(mw, ww))
        val cbStableN = CheckBox(this).apply {
            text = "Stabili šiaurė (ignoruoti magnetometrą)"
            isChecked = getSharedPreferences("sky_cal", Context.MODE_PRIVATE).getBoolean("stable_north", true)
            setOnCheckedChangeListener { _, on ->
                if (::skyDome.isInitialized) skyDome.setStableNorth(on)
                else getSharedPreferences("sky_cal", Context.MODE_PRIVATE).edit().putBoolean("stable_north", on).apply()
            }
        }
        left.addView(cbStableN, LinearLayout.LayoutParams(mw, ww))
        left.addView(TextView(this).apply {
            text = "Moon: NASA SVS CGI Moon Kit (LRO LROC, LOLA) · Earth: NASA Blue Marble, GEBCO"
            textSize = 11f
            alpha = 0.7f
        }, LinearLayout.LayoutParams(mw, ww))
        left.addView(info, LinearLayout.LayoutParams(mw, ww))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(skyView, LinearLayout.LayoutParams(skySide, skySide))
        skyDome = SkyDomeGL(this)
        skyHolder = FrameLayout(this).apply { visibility = View.GONE }
        skyHolder.addView(skyDome, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val fsSize = (44 * resources.displayMetrics.density).toInt()
        fsBtn = TextView(this).apply {
            text = "\u26F6"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x55000000)
            }
            alpha = 0.6f
            setOnClickListener { toggleFullscreen() }
        }
        skyHolder.addView(fsBtn, FrameLayout.LayoutParams(fsSize, fsSize, Gravity.TOP or Gravity.END).apply {
            val m = (10 * resources.displayMetrics.density).toInt()
            setMargins(m, m, m, m)
        })
        skyHolderParent = right
        skyHolderLp = LinearLayout.LayoutParams(skySide, skySide)
        right.addView(skyHolder, skyHolderLp)
        skyDome.onDoubleTapCb = { setGlobe(false) }
        val skyGd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean { setGlobe(true); return true }
        })
        skyView.setOnTouchListener { _, ev -> skyGd.onTouchEvent(ev); true }
        right.addView(cnoView, LinearLayout.LayoutParams(mw, cnoH))

        val content = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        contentCol = content
        rightCol = right
        if (landscape) {
            content.addView(left, LinearLayout.LayoutParams(0, ww, 1f))
            content.addView(right, LinearLayout.LayoutParams(0, ww, 1f))
        } else {
            content.addView(left, LinearLayout.LayoutParams(mw, ww))
            content.addView(right, LinearLayout.LayoutParams(mw, ww))
        }

        val root = ScrollView(this).apply { addView(content) }
        rootScroll = root

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
            var report = buildReport(sections - "screenshot" - "raw", question) +
                "\n=== ĮRAŠYMO PERTRAUKOS ===\n" + GapLog.report(this)
            val finish: (Uri?) -> Unit = { shot ->
                val uris = ArrayList<Uri>()
                shot?.let { uris.add(it) }
                val send = {
                    report += "Eksportuota: " + (listOf(REPORT_FILE) + uris.mapNotNull { it.lastPathSegment }).joinToString(", ") + "\n"
                    // Ataskaita (su klausimu) visada kaip tekstinis failas
                    reportFileUri(report)?.let { uris.add(0, it) }
                    shareContent(report, uris)
                }
                if ("raw" in sections) {
                    rawCsvUri()?.let { uris.add(it) }
                    fixCsvUri()?.let { uris.add(it) }
                    navCsvUri()?.let { uris.add(it) }
                    gapLogUri()?.let { uris.add(it) }
                    // GPS pozicijos skaičiuoklė (1 žingsnis) – fone, rezultatas gnss_solver.csv
                    offerSp3PickerOnce { Thread {
                        val (bad, total) = SessionFiles.badLines(File(cacheDir, SessionFiles.RAW))
                        val (badF, totalF) = SessionFiles.badLines(File(cacheDir, SessionFiles.FIX))
                        val (badN, totalN) = SessionFiles.badLines(File(cacheDir, NavRecorder.FILE))
                        report += "CSV savitikra (laukų skaičius = antraštės): gnss_raw.csv netinkamų $bad iš $total; " +
                            "gnss_fix.csv $badF iš $totalF; gnss_nav.csv $badN iš $totalN\n"
                        val u = solverCsvUri()
                        PositionSolver.lastSp3Note?.let { report += it + "\n" }
                        val s = sp3TestCsvUri(Sp3Solver.OUT_FILE)
                        val s2 = sp3TestCsvUri(Sp3Solver.OUT_FILE_V2)
                        val s3 = sp3TestCsvUri(Sp3Solver.OUT_FILE_V3)
                        val s4 = sp3TestCsvUri(Sp3V4.OUT_FILE_V4)
                        val s5 = sp3TestCsvUri(Sp3V5.OUT_FILE_V5)
                        val s6 = sp3TestCsvUri(Sp3V6.OUT_FILE_V6)
                        val d = solverDiagUri()
                        runOnUiThread { u?.let { uris.add(it) }; s?.let { uris.add(it) }; s2?.let { uris.add(it) }; s3?.let { uris.add(it) }; s4?.let { uris.add(it) }; s5?.let { uris.add(it) }; s6?.let { uris.add(it) }; d?.let { uris.add(it) }; send() }
                    }.start() }
                } else send()
            }
            if (wantScreenshot) {
                // Burbulas ir panelė NEPATENKA į ekrano kopiją; 3D modeliai – patenka
                sharePanel?.visibility = View.INVISIBLE
                takeScreenshotAsync { uri ->
                    sharePanel?.visibility = View.VISIBLE
                    finish(uri)
                }
            } else finish(null)
        }
        panel.onCountdownStart = {
            // Atgalinio skaičiavimo pradžioje CSV buferis pradedamas iš naujo
            SessionFiles.resetRawFix(this)
            // gnss_nav.csv neištrinamas: efemeridėms reikia ir senesnių subkadrų; laikomas iki dalinimosi
            resetRefStats()
        }
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        
        // PAKEISTA: Naudojame START, kad translationX skaičiavimas būtų teisingas
        params.gravity = Gravity.TOP or Gravity.START 
        
        container.addView(panel, params)
        panel.onPin = { pinBubble() }
        panel.onEndRecord = { toggleRecording() }
        panel.onColdStart = { askColdStart() }
        panel.onPickSp3 = { launchSp3Picker() }
        panel.onUpdateSp3 = { updateSp3() }
        refreshSp3Status()
        panel.setRecording(NavRecordService.running || !recordEnded)
        sharePanel = panel
        
        // Nustatome pradinę poziciją viršutiniame dešiniajame kampe
        panel.post {
            val margin = (16 * resources.displayMetrics.density).toInt()
            // Kadangi gravitacija START, translationX pastums į dešinę
            panel.translationX = (container.width - panel.width - margin).toFloat()
            panel.translationY = margin.toFloat()
        }
    }

    /**
     * Pasukus ekraną veikla neperkuriama (manifestas: configChanges), todėl įrašo laikmatis,
     * 3D režimas ir GL vaizdas išlieka; čia pritaikomas tik išdėstymas.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val content = contentCol ?: return
        val right = rightCol ?: return
        val dm = resources.displayMetrics
        val landscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        val colW = if (landscape) dm.widthPixels / 2 else dm.widthPixels
        val skySide = if (landscape) min(colW, dm.heightPixels) else colW
        val mw = LinearLayout.LayoutParams.MATCH_PARENT
        val ww = LinearLayout.LayoutParams.WRAP_CONTENT
        content.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        leftCol?.layoutParams = if (landscape) LinearLayout.LayoutParams(0, ww, 1f) else LinearLayout.LayoutParams(mw, ww)
        right.layoutParams = if (landscape) LinearLayout.LayoutParams(0, ww, 1f) else LinearLayout.LayoutParams(mw, ww)
        skyView.layoutParams = LinearLayout.LayoutParams(skySide, skySide)
        val holderLp = LinearLayout.LayoutParams(skySide, skySide)
        skyHolderLp = holderLp
        if (!fullscreen) skyHolder.layoutParams = holderLp
        container.post {
            if (fullscreen) skyHolder.layoutParams = LinearLayout.LayoutParams(container.width, container.height)
            sharePanel?.onParentResized()
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        baro.start()
        startDeviceSensors()
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
        stopDeviceSensors()
        if (::skyDome.isInitialized) skyDome.stop()
        activityStarted = false
        // Fone matavimus ir fiksus rašo NavRecordService; veiklos klausytojai – tik ekranui
        if (NavRecordService.running) GapLog.event(this, "veikla onStop – įrašymas tęsiamas paslaugoje")
        unregisterGnss()
    }

    private var gnssRegistered = false
    private var activityStarted = false
    private var recordEnded = false

    private fun unregisterGnss() {
        if (!gnssRegistered) return
        try {
            lm.unregisterGnssStatusCallback(statusCallback)
            lm.unregisterGnssMeasurementsCallback(measCallback)
            lm.removeUpdates(locationListener)
        } catch (_: Exception) {}
        SessionFiles.activityWritesFixes = false
        gnssRegistered = false
    }

    /** „Baigti įrašą“ / „Tęsti įrašą“ mygtukas. */
    private fun toggleRecording() {
        if (NavRecordService.running) {
            recordEnded = true
            startService(Intent(this, NavRecordService::class.java).setAction(NavRecordService.ACTION_END))
            sharePanel?.setRecording(false)
        } else {
            recordEnded = false
            SessionFiles.startNew(this)
            try { ContextCompat.startForegroundService(this, Intent(this, NavRecordService::class.java)) } catch (e: Exception) { logError("Įrašymo paslauga: ${e.message}") }
            sharePanel?.setRecording(true)
        }
    }

    /** Šaltas startas: patvirtinimas, tada GNSS atjungiamas, pagalbos duomenys ištrinami ir GNSS paleidžiamas iš naujo. */
    private fun askColdStart() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Šaltas startas")
            .setMessage("Bus ištrinti GNSS pagalbos duomenys (efemeridės, almanachas, padėtis, laikas). " +
                "Prieš tai išjunkite mobiliuosius duomenis ir Wi-Fi, kad planšetė jų iškart negautų iš interneto. " +
                "Pirmas fiksas gali užtrukti kelias minutes.")
            .setPositiveButton("Ištrinti") { _, _ -> coldStart() }
            .setNegativeButton("Atšaukti", null)
            .show()
    }

    private fun coldStart() {
        unregisterGnss()
        if (NavRecordService.running) {
            startService(Intent(this, NavRecordService::class.java).setAction(NavRecordService.ACTION_COLD_START))
        } else {
            val ok = deleteAidingData(lm)
            GapLog.event(this, "ŠALTAS STARTAS: pagalbos duomenų trynimas ${if (ok) "priimtas" else "nepriimtas"} (paslauga neveikė)")
        }
        posText = "Šaltas startas: laukiama fikso..."
        render()
        mainHandler.postDelayed({ if (activityStarted) startGnss() }, 3500)
    }

    /** Vieną kartą paprašoma baterijos optimizavimo išimties (įrašymas išjungus ekraną). */
    @SuppressLint("BatteryLife")
    private fun askBatteryExemptionOnce() {
        val prefs = getSharedPreferences("gap_log", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        if (GapLog.batteryExempt(this)) { GapLog.event(this, "baterijos išimtis jau suteikta"); return }
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle("Įrašymas išjungus ekraną")
                .setMessage("Kad GNSS matavimai būtų įrašomi be pertraukų, kai ekranas išjungtas ar atidaryta kita programa, leiskite programai veikti be baterijos optimizavimo.")
                .setPositiveButton("Leisti") { _, _ ->
                    GapLog.event(this, "baterijos išimties prašymas: atidarytas sistemos langas")
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                    } catch (e: Exception) { logError("Baterijos išimtis: ${e.message}") }
                }
                .setNegativeButton("Ne dabar") { _, _ -> GapLog.event(this, "baterijos išimties prašymas: atmesta") }
                .show()
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun startGnss() {
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            posText = "GPS išjungtas. Įjunkite vietos nustatymus."
            logError("GPS išjungtas")
            render()
        }
        NavRecordService.onEnded = { sharePanel?.setRecording(false) }
        if (!gnssRegistered) {
            lm.registerGnssStatusCallback(statusCallback, mainHandler)
            if (Build.VERSION.SDK_INT >= 31) registerFullTracking() else lm.registerGnssMeasurementsCallback(measCallback, mainHandler)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            SessionFiles.activityWritesFixes = true
            gnssRegistered = true
        }
        if (recordEnded) return
        sharePanel?.setRecording(true)
        // Navigacijos pranešimai – priekinio plano paslaugoje (veikia ir išjungus ekraną)
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 77)
            }
            ContextCompat.startForegroundService(this, Intent(this, NavRecordService::class.java))
        } catch (e: Exception) { logError("Navigacijos paslauga: ${e.message}") }
        askBatteryExemptionOnce()
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
                when {
                    n != null -> {
                        appendLine("Geoido unduliacija (N): ${fx(n, 2)} m")
                        appendLine("Aukštis virš jūros (H): ${fx(loc.altitude - n)} m")
                    }
                    Geoid.lastError != null -> appendLine("Klaida: ${Geoid.lastError}")
                    !Geoid.isReady -> appendLine("Geoidas kraunamas...")
                    else -> appendLine("Taškas už pasirinkto geoido ribų")
                }
            }
            if (loc.hasSpeed()) appendLine("Greitis: ${fx(loc.speed * 3.6)} km/h")
            append("Laikas: ${java.text.DateFormat.getTimeInstance().format(Date(loc.time))}")
        }
        baro.updateMslp(loc.latitude, loc.longitude)
        appendFix(loc)
        fetchKp()
        if (::skyDome.isInitialized) skyDome.setLocation(loc.latitude, loc.longitude, loc.altitude)
        lastGpsH = if (loc.hasAltitude()) Geoid.undulation(loc.latitude, loc.longitude)?.let { loc.altitude - it } else null
        render()
    }

    private fun render() {
        val sb = SpannableStringBuilder()
        sb.append("=== FIKSAS ===\n")
        sb.append(posText)
        sb.append("\n\n=== BAROMETRAS ===\n")
        if (!baro.hasSensor) {
            sb.append("Barometro jutiklio nėra\n")
        } else {
            val alt = baro.altitude()
            sb.append("Slėgis: ${baro.stationHpa?.let { fx(it, 2) } ?: "—"} hPa\n")
            sb.append("MSLP: ${baro.mslpHpa?.let { fx(it) } ?: "—"} hPa (${baro.mslpSource})\n")
            sb.append("Barometrinis aukštis: ${alt?.let { fx(it) + " m" } ?: "—"}\n")
            sb.append("Kalibravimo poslinkis: ${fx(baro.offsetM)} m\n")
        }
        sb.append("\n=== PALYDOVAI ===\n")
        sb.append(satText)
        sb.append("\n\n=== NEAPDOROTI MATAVIMAI (paskutinis GNSS ciklas) ===\n")
        sb.append(measText)
        info.text = sb
    }

    private fun logError(msg: String) {
        val stamp = java.text.DateFormat.getTimeInstance().format(Date())
        errorLog.addLast("$stamp  $msg")
        if (errorLog.size > 20) errorLog.removeFirst()
    }

    // ---------------- NEAPDOROTI MATAVIMAI (CSV) – išplėstiniai AI analizei ----------------
    // gnss_raw.csv rašo NavRecordService per RawRecorder / SessionFiles (vienas rašytojas)
    private val satMap = HashMap<String, Sat>()
    private val bandsSeen get() = synchronized(RawRecorder.bandsSeen) { RawRecorder.bandsSeen.toSortedSet() }

    // Kableliai pakeičiami kabliataškiais, kad CSV stulpeliai nesusimaišytų
    private fun envTag(): String = SessionFiles.clean(sharePanel?.envTag ?: "")

    // ---------------- FIKSAI KAS SEKUNDĘ (CSV) ----------------

    private fun appendFix(loc: Location) {
        val fixLog = StringBuilder(256)
        val n = Geoid.undulation(loc.latitude, loc.longitude)
        val used = lastSats.filter { it.used }
        val avg = if (used.isNotEmpty()) used.map { it.cn0 }.average() else Double.NaN
        val t4 = top4Avg(lastSats)
        fixLog.append(loc.time).append(',')
            .append(fx(loc.latitude, 8)).append(',').append(fx(loc.longitude, 8)).append(',')
            .append(if (loc.hasAltitude()) fx(loc.altitude, 2) else "").append(',')
            .append(n?.let { fx(it, 3) } ?: "").append(',')
            .append(if (loc.hasAltitude() && n != null) fx(loc.altitude - n, 2) else "").append(',')
            .append(if (loc.hasAccuracy()) fx(loc.accuracy.toDouble(), 2) else "").append(',')
            .append(if (Build.VERSION.SDK_INT >= 26 && loc.hasVerticalAccuracy()) fx(loc.verticalAccuracyMeters.toDouble(), 2) else "").append(',')
            .append(if (loc.hasSpeed()) fx(loc.speed.toDouble(), 2) else "").append(',')
            .append(if (loc.hasBearing()) fx(loc.bearing.toDouble(), 1) else "").append(',')
            .append(used.size).append(',').append(lastSats.size).append(',')
            .append(if (avg.isNaN()) "" else fx(avg, 1)).append(',')
            .append(t4?.let { fx(it.toDouble(), 1) } ?: "").append(',')
            .append(fx(devPitch, 1)).append(',').append(fx(devRoll, 1)).append(',').append(fx(devAzimuth, 1)).append(',')
            .append(fx(motionLevel, 3)).append(',')
            .append(envTag()).append(',')
        val kAgl = sharePanel?.knownAgl
        val kMsl = sharePanel?.knownMsl
        val kLL = sharePanel?.knownLatLon
        val hNow = if (loc.hasAltitude() && n != null) loc.altitude - n else null
        val eE = kLL?.let { (loc.longitude - it.second) * 111320.0 * cos(Math.toRadians(it.first)) }
        val eN = kLL?.let { (loc.latitude - it.first) * 111320.0 }
        fixLog.append(kAgl?.let { fx(it, 2) } ?: "").append(',')
            .append(kMsl?.let { fx(it, 2) } ?: "").append(',')
            .append(kLL?.let { fx(it.first, 8) } ?: "").append(',')
            .append(kLL?.let { fx(it.second, 8) } ?: "").append(',')
            .append(if (kMsl != null && hNow != null) fx(hNow - kMsl, 2) else "").append(',')
            .append(if (eE != null && eN != null) fx(kotlin.math.sqrt(eE * eE + eN * eN), 2) else "").append(',')
            .append(eE?.let { fx(it, 2) } ?: "").append(',')
            .append(eN?.let { fx(it, 2) } ?: "").append('\n')
        if (NavRecordService.running) SessionFiles.writeFix(this, fixLog.toString())
        refStats(hNow, kMsl, eE, eN)
    }

    // Suvestinė ataskaitai: vidutinės paklaidos nuo etalono
    private var refN = 0; private var refHSum = 0.0; private var refHAbs = 0.0
    private var refPN = 0; private var refDSum = 0.0; private var refDMax = 0.0
    private fun refStats(h: Double?, kMsl: Double?, eE: Double?, eN: Double?) {
        if (h != null && kMsl != null) { refN++; refHSum += h - kMsl; refHAbs += kotlin.math.abs(h - kMsl) }
        if (eE != null && eN != null) {
            val d = kotlin.math.sqrt(eE * eE + eN * eN)
            refPN++; refDSum += d; if (d > refDMax) refDMax = d
        }
    }
    private fun resetRefStats() { refN = 0; refHSum = 0.0; refHAbs = 0.0; refPN = 0; refDSum = 0.0; refDMax = 0.0 }

    private fun fixCsvUri(): Uri? = try {
        val file = SessionFiles.file(this, SessionFiles.FIX)
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    } catch (e: Exception) {
        logError("Fiksų CSV nepavyko: ${e.message}")
        null
    }

    // ---------------- ĮRENGINIO PADĖTIS IR JUDĖJIMAS ----------------
    private var devPitch = 0.0
    private var devRoll = 0.0
    private var devAzimuth = 0.0
    private var motionLevel = 0.0
    private val devSensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val r = FloatArray(9); val o = FloatArray(3)
                    SensorManager.getRotationMatrixFromVector(r, e.values)
                    SensorManager.getOrientation(r, o)
                    devAzimuth = (Math.toDegrees(o[0].toDouble()) + 360.0) % 360.0
                    devPitch = Math.toDegrees(o[1].toDouble())
                    devRoll = Math.toDegrees(o[2].toDouble())
                }
                Sensor.TYPE_LINEAR_ACCELERATION -> {
                    val a = kotlin.math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                    motionLevel = motionLevel * 0.9 + a * 0.1     // išlygintas judėjimo lygis
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun startDeviceSensors() {
        val sm = getSystemService(SENSOR_SERVICE) as SensorManager
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(devSensorListener, it, SensorManager.SENSOR_DELAY_UI) }
        sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sm.registerListener(devSensorListener, it, SensorManager.SENSOR_DELAY_UI) }
    }

    private fun stopDeviceSensors() {
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(devSensorListener)
    }

    // ---------------- KOSMINIAI ORAI (NOAA Kp) ----------------
    @Volatile private var kpText = "nežinoma"
    @Volatile private var kpFetched = 0L

    private fun fetchKp() {
        val now = System.currentTimeMillis()
        if (now - kpFetched < 30 * 60_000L) return
        kpFetched = now
        Thread {
            kpText = try {
                val txt = URL("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json").readText()
                val arr = org.json.JSONArray(txt)
                val last = arr.get(arr.length() - 1)
                when (last) {
                    is org.json.JSONArray -> "Kp ${last.getString(1)} (${last.getString(0)} UTC)"
                    is org.json.JSONObject -> "Kp ${last.optString("Kp", last.optString("kp_index", "?"))} (${last.optString("time_tag")} UTC)"
                    else -> "nežinoma"
                }
            } catch (e: Exception) {
                kpFetched = 0L
                "nepavyko gauti (${e.javaClass.simpleName})"
            }
        }.start()
    }

    /** GNSS lusto informacija ataskaitai. */
    private fun gnssChipInfo(): String = buildString {
        if (Build.VERSION.SDK_INT >= 28) {
            appendLine("GNSS lustas: ${lm.gnssHardwareModelName ?: "nežinomas"} (metai: ${lm.gnssYearOfHardware})")
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val cap = lm.gnssCapabilities
            appendLine("Galimybės: matavimai=${cap.hasMeasurements()}, navigaciniai pranešimai=${cap.hasNavigationMessages()}, " +
                "antenos info=${cap.hasAntennaInfo()}, matavimų korekcijos=${cap.hasMeasurementCorrections()}")
        }
        appendLine("Pilnas sekimas: programa jo prašo (setFullTracking=true)")
        appendLine("Matyti dažniai: ${if (bandsSeen.isEmpty()) "—" else bandsSeen.joinToString(", ")}")
        val dual = bandsSeen.any { it.endsWith("L5") || it.endsWith("E5a") || it.endsWith("E5b") || it.endsWith("B2b") }
        appendLine("Dviejų dažnių priėmimas: ${if (dual) "taip" else "nematyta"}")
    }

    private fun reportFileUri(text: String): Uri? = try {
        val file = File(cacheDir, REPORT_FILE)
        file.writeText(text)
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    } catch (e: Exception) {
        logError("Ataskaitos failas nepavyko: ${e.message}")
        null
    }

    private fun rawCsvUri(): Uri? = try {
        val file = SessionFiles.file(this, SessionFiles.RAW)
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    } catch (e: Exception) {
        logError("CSV nepavyko: ${e.message}")
        null
    }

    // ---------------- NAVIGACIJOS PRANEŠIMAI (CSV) – NavRecorder / NavRecordService ----------------

    /** GPS-only sprendinys iš gnss_raw.csv + gnss_nav.csv -> gnss_solver.csv (kviečiama ne UI gijoje). */
    private fun solverCsvUri(): Uri? = try {
        val out = File(cacheDir, "gnss_solver.csv")
        // Ankstesnio eksporto rezultatas neturi būti išsiųstas, jei šįkart SP3 sprendimas nevyks
        File(cacheDir, Sp3Solver.OUT_FILE).delete()
        File(cacheDir, Sp3Solver.OUT_FILE_V2).delete()
        File(cacheDir, Sp3Solver.OUT_FILE_V3).delete()
        File(cacheDir, Sp3V4.OUT_FILE_V4).delete()
        File(cacheDir, Sp3V5.OUT_FILE_V5).delete()
        File(cacheDir, Sp3V6.OUT_FILE_V6).delete()
        // Atskaitos taškas SP3 testams: „Žinomos koordinatės“, jei įvestos
        PositionSolver.knownRef = try { sharePanel?.knownLatLon } catch (e: Exception) { null }
        // „SP3 TESTAS“ / „SP3 INTERPOLIACIJA“ diagnostikai (skaičiuoklė SP3 nenaudoja)
        val sols = PositionSolver.solveFiles(File(cacheDir, "gnss_raw.csv"), File(cacheDir, "gnss_nav.csv"), out) {
            Sp3Storage.search(this)
        }
        android.util.Log.i("GnssApp", "gnss_solver.csv: sprendinių ${sols.size}")
        FileProvider.getUriForFile(this, "$packageName.fileprovider", out)
    } catch (e: Exception) {
        runOnUiThread { logError("Skaičiuoklė nepavyko: ${e.message}") }
        null
    }

    /** SP3 sprendinio testas; failo nėra, jei SP3 sprendimas nevyko – tada praleidžiama. */
    private fun sp3TestCsvUri(name: String): Uri? = try {
        val f = File(cacheDir, name)
        if (f.exists()) FileProvider.getUriForFile(this, "$packageName.fileprovider", f) else null
    } catch (e: Exception) { null }

    /** Skaičiuoklės diagnostika (gnss_solver_diag.txt), sukurta kartu su gnss_solver.csv. */
    private fun solverDiagUri(): Uri? = try {
        val f = File(cacheDir, PositionSolver.DIAG_FILE)
        if (f.exists()) FileProvider.getUriForFile(this, "$packageName.fileprovider", f) else null
    } catch (e: Exception) { null }

    private fun gapLogUri(): Uri? = try {
        val f = GapLog.file(this)
        if (!f.exists()) f.writeText("")
        FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
    } catch (e: Exception) { null }

    private fun navCsvUri(): Uri? = try {
        val file = NavRecorder.file(cacheDir)
        FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    } catch (e: Exception) {
        logError("Navigacijos CSV nepavyko: ${e.message}")
        null
    }

    /** Ekrano kopija kartu su OpenGL 3D vaizdu (jis nupiešiamas per PixelCopy). */
    private fun takeScreenshotAsync(done: (Uri?) -> Unit) {
        val view = window.decorView
        val bitmap = try {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) { done(null); return }
        Canvas(bitmap).also { view.draw(it) }
        val save: () -> Unit = {
            val file = File(cacheDir, "screenshot.png")
            val uri = try {
                FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            } catch (e: Exception) {
                logError("Screenshot failed: ${e.message}")
                null
            }
            done(uri)
        }
        val gl = if (::skyDome.isInitialized) skyDome else null
        if (gl == null || !use3d || gl.width <= 0 || gl.height <= 0 || Build.VERSION.SDK_INT < 24) {
            save(); return
        }
        val glBmp = Bitmap.createBitmap(gl.width, gl.height, Bitmap.Config.ARGB_8888)
        try {
            android.view.PixelCopy.request(gl, glBmp, { res ->
                if (res == android.view.PixelCopy.SUCCESS) {
                    val loc = IntArray(2)
                    gl.getLocationInWindow(loc)
                    Canvas(bitmap).drawBitmap(glBmp, loc[0].toFloat(), loc[1].toFloat(), null)
                }
                save()
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            logError("3D kopija nepavyko: ${e.message}")
            save()
        }
    }

    private fun takeScreenshot(): Uri? {
        val view = window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        val file = File(cacheDir, "screenshot.png")
        return try {
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            logError("Screenshot failed: ${e.message}")
            null
        }
    }

    private fun buildReport(sections: Set<String>, question: String): String = buildString {
        appendLine("GNSS App ataskaita")
        appendLine("Laikas: ${java.text.DateFormat.getDateTimeInstance().format(Date())}")
        appendLine()
        if (question.isNotBlank()) { appendLine("Klausimas: $question"); appendLine() }
        if ("irenginys" in sections) {
            appendLine("=== ĮRENGINYS ===")
            appendLine("Modelis: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Barometras: ${if (baro.hasSensor) "yra" else "nėra"}")
            append(gnssChipInfo())
            appendLine("Kosminiai orai (NOAA): $kpText")
            appendLine("Aplinka (vartotojo žyma): ${envTag().ifBlank { "nenurodyta" }}")
            val kAgl = sharePanel?.knownAgl
            val kMsl = sharePanel?.knownMsl
            val kLL = sharePanel?.knownLatLon
            if (kAgl != null || kMsl != null || kLL != null) {
                appendLine("=== ETALONAS (žinomos vertės) ===")
                kAgl?.let { appendLine("Žinomas aukštis virš žemės: ${fx(it, 2)} m") }
                kMsl?.let { appendLine("Žinomas aukštis virš jūros: ${fx(it, 2)} m") }
                kLL?.let { appendLine("Žinomos koordinatės: ${fx(it.first, 8)}, ${fx(it.second, 8)}") }
                if (refN > 0) appendLine("GNSS aukščio paklaida: vidutinė ${fx(refHSum / refN, 2)} m, vid. absoliuti ${fx(refHAbs / refN, 2)} m (${refN} fiksų)")
                if (refPN > 0) appendLine("Horizontali paklaida: vidutinė ${fx(refDSum / refPN, 2)} m, didžiausia ${fx(refDMax, 2)} m (${refPN} fiksų)")
                if (kAgl != null && kMsl != null) appendLine("Žemės aukštis virš jūros (iš etalonų): ${fx(kMsl - kAgl, 2)} m")
            }
            appendLine("Planšetės padėtis: pitch ${fx(devPitch, 0)}°, roll ${fx(devRoll, 0)}°, ekrano azimutas ${fx(devAzimuth, 0)}°, judėjimas ${fx(motionLevel, 2)} m/s²")
            appendLine()
        }
        if ("fiksas" in sections) { appendLine("=== FIKSAS ==="); appendLine(posText); appendLine() }
        if ("geoidas" in sections) {
            appendLine("=== GEOIDAS ===")
            appendLine("Modelis: ${Geoid.mode.label}")
            appendLine("Būsena: ${Geoid.lastError ?: if (Geoid.isReady) "įkrautas" else "kraunamas"}")
            appendLine()
        }
        if ("baro" in sections) {
            appendLine("=== BAROMETRAS ===")
            if (!baro.hasSensor) appendLine("Barometro jutiklio nėra") else {
                appendLine("Slėgis: ${baro.stationHpa?.let { fx(it, 2) } ?: "—"} hPa")
                appendLine("MSLP: ${baro.mslpHpa?.let { fx(it) } ?: "—"} hPa (${baro.mslpSource})")
                appendLine("Barometrinis aukštis: ${baro.altitude()?.let { fx(it) + " m" } ?: "—"}")
                appendLine("Kalibravimo poslinkis: ${fx(baro.offsetM)} m")
            }
            appendLine()
        }
        if ("palydovai" in sections) {
            appendLine("=== PALYDOVAI ===")
            appendLine(satText)
            appendLine(measText)
            appendLine("Navigacinių žinučių gauta: ${NavRecorder.received}, įrašyta į gnss_nav.csv: ${NavRecorder.written}")
            appendLine("Navigacijos klausytojas: ${if (NavRecordService.listenerRegistered) "užregistruotas" else "neužregistruotas"}")
            appendLine("Navigacijos žinučių statusas: ${NavRecordService.navStatusText()}")
            appendLine("Navigacijos paslauga (priekinis planas): ${if (NavRecordService.running) "veikia" else "neveikia"}")
            appendLine("Palydovų sąrašas:")
            lastSats.sortedWith(compareBy({ it.const }, { it.svid })).forEach { s ->
                appendLine("${s.const} ${s.svid}: C/N0 ${fx(s.cn0.toDouble())}, kampas ${fx(s.elev.toDouble(), 0)}°, az ${fx(s.az.toDouble(), 0)}°, fiksui: ${if (s.used) "taip" else "ne"}")
            }
            appendLine()
        }
        if ("cno" in sections) { appendLine("=== C/N0 ISTORIJA ==="); appendLine(cnoView.summary()); appendLine() }
        if ("klaidos" in sections) {
            appendLine("=== KLAIDOS IR BŪSENA ===")
            appendLine("Geoido klaida: ${Geoid.lastError ?: "nėra"}")
            appendLine("MSLP šaltinis: ${baro.mslpSource}")
            if (errorLog.isEmpty()) appendLine("Programos klaidų žurnalas tuščias") else {
                appendLine("Programos žurnalas:")
                errorLog.forEach { appendLine(it) }
            }
            appendLine()
        }
    }

    private fun shareContent(text: String, uris: List<Uri>) {
        if (uris.isEmpty()) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "GNSS App ataskaita")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(intent, "Dalintis ataskaita"))
        } else {
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, java.util.ArrayList<Uri>(uris))
                putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Dalintis"))
        }
    }

}
