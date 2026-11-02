package lt.example.gnss

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
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
//  GEOIDAS
// ============================================================

enum class GeoidMode(val label: String) {
    LIT20G("LIT20G (Lietuva)"),
    WORLD("Pasaulinis (EGM2008)")
}

/** Vienas geoido tinklelis, nuskaitomas iš assets failo (eilutės: platuma ilguma N). */
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

    /** Kviesti ne pagrindinėje gijoje. Gali mesti išimtį, jei failo nėra. */
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

    /** Bilineari interpoliacija. Null, jei taškas už tinklelio ribų arba nekrautas. */
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

/** Geoido valdymas: pasirinktas modelis naudojamas visiems skaičiavimams. */
object Geoid {

    private val lit20g = GeoidGrid(
        assetName = "LIT20G.txt",
        latMin = 53.0, latStep = 0.01, nLat = 401,
        lonMin = 18.0, lonStep = 0.02, nLon = 451
    )

    private val world = GeoidGrid(
        assetName = "WORLD_EGM2008.txt",
        latMin = -90.0, latStep = 0.25, nLat = 721,
        lonMin = -180.0, lonStep = 0.25, nLon = 1441
    )

    @Volatile var mode: GeoidMode = GeoidMode.LIT20G
        private set

    @Volatile var lastError: String? = null
        private set

    private fun gridOf(m: GeoidMode) = if (m == GeoidMode.LIT20G) lit20g else world

    /** Rankiniu būdu pasirenka modelį ir jį įkelia fone. onReady kviečiamas fono gijoje. */
    fun select(context: Context, m: GeoidMode, onReady: () -> Unit) {
        mode = m
        Thread {
            try {
                gridOf(m).load(context.applicationContext)
                lastError = null
            } catch (e: Exception) {
                lastError = "nepavyko įkelti ${m.label}: ${e.message}"
            }
            onReady()
        }.start()
    }

    val isReady: Boolean get() = gridOf(mode).isLoaded

    /** Unduliacija N (m) pasirinktame modelyje arba null. */
    fun undulation(lat: Double, lon: Double): Double? = gridOf(mode).undulation(lat, lon)
}

// ============================================================
//  BAROMETRAS
// ============================================================

/**
 * Barometrinis aukštis:
 * altitude = getAltitude(MSLP, vietos slėgis) + kalibravimo poslinkis.
 * MSLP ateina iš Open-Meteo pagal GNSS koordinates arba įvedamas rankiniu būdu.
 */
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

    /** Kalibravimo poslinkis (m), išsaugomas tarp paleidimų. */
    var offsetM: Double
        get() = prefs.getFloat("offset", 0f).toDouble()
        private set(v) { prefs.edit().putFloat("offset", v.toFloat()).apply() }

    fun start() {
        sensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        sm.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        stationHpa = event.values[0].toDouble()
    }

    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}

    /** Aukštis virš jūros (m) arba null, jei nėra slėgio duomenų. */
    fun altitude(): Double? {
        val p = stationHpa ?: return null
        return rawAltitude(p) + offsetM
    }

    private fun rawAltitude(p: Double): Double {
        val sea = mslpHpa ?: SensorManager.PRESSURE_STANDARD_ATMOSPHERE.toDouble()
        return SensorManager.getAltitude(sea.toFloat(), p.toFloat()).toDouble()
    }

    /** Atnaujina MSLP, jei praėjo 30 min arba pasikeitė vieta (~5 km). Fone. */
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

    /** Rankinis MSLP (QNH) įvedimas, jei internetas nepasiekiamas. */
    fun setManualMslp(hpa: Double) {
        mslpHpa = hpa
        mslpSource = "rankinis"
    }

    /** Nulis dabartinėje vietoje. */
    fun calibrateZero() {
        val p = stationHpa ?: return
        offsetM = -rawAltitude(p)
    }

    /** Kalibravimas pagal žinomą aukštį (pvz., GPS H arba geodezinio ženklo). */
    fun calibrateTo(targetM: Double) {
        val p = stationHpa ?: return
        offsetM = targetM - rawAltitude(p)
    }

    fun reset() {
        offsetM = 0.0
    }
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
        set(value) {
            field = value
            invalidate()
        }

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

        for (e in listOf(0f, 30f, 60f)) {
            canvas.drawCircle(cx, cy, r0 * (90f - e) / 90f, ringPaint)
        }
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
//  C/N0 LAIKO GRAFIKAS (kreivės kiekvienam palydovui)
// ============================================================

class CnoPlotView(context: Context) : View(context) {

    private val maxPts = 120                  // ~2 min, nes GNSS atnaujina ~1 Hz
    private var total = 0
    // raktas: "KONST|svid", reikšmės: C/N0 arba null (palydovas nematytas tą akimirką)
    private val series = LinkedHashMap<String, ArrayDeque<Float?>>()
    private val avgSeries = ArrayDeque<Float?>()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40334155
        strokeWidth = 1f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeJoin = Paint.Join.ROUND
    }
    private val avgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.RED
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0F172A.toInt()
        textSize = 22f
    }

    /** Pridedama kiekvieno GNSS atnaujinimo metu. */
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

        // Tinklelis ir Y ašis (dB-Hz)
        for (db in 10..50 step 10) {
            val y = yOf(db.toFloat())
            canvas.drawLine(left, y, right, y, gridPaint)
            canvas.drawText("$db", 6f, y + 8f, textPaint)
        }
        canvas.drawText("dB-Hz", 6f, top - 4f, textPaint)
        canvas.drawText("laikas →", right - 110f, bottom + 30f, textPaint)

        // Palydovų kreivės
        for ((k, dq) in series) {
            linePaint.color = constColor(k.substringBefore('|'))
            drawSeries(canvas, dq, ::xOf, ::yOf, linePaint, label = k.substringAfter('|'))
        }

        // Naudojamų palydovų vidurkis (raudona punktyrinė)
        drawSeries(canvas, avgSeries, ::xOf, ::yOf, avgPaint, label = null)
    }

    private fun drawSeries(
        canvas: Canvas,
        dq: ArrayDeque<Float?>,
        xOf: (Int) -> Float,
        yOf: (Float) -> Float,
        paint: Paint,
        label: String?
    ) {
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

        if (label != null && hasLast) {
            canvas.drawText(label, lastX + 4f, lastY + 6f, textPaint)
        }
    }
}

// ============================================================
//  JUDANTIS SPALVŲ FONAS (Gemini stilius)
// ============================================================

class GradientBackground(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 14000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedFraction
            invalidate()
        }
    }

    private val blobs = intArrayOf(
        0xFF60A5FA.toInt(), // mėlyna
        0xFFA78BFA.toInt(), // violetinė
        0xFF34D399.toInt(), // žalia
        0xFFF472B6.toInt()  // rožinė
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(0xFFF8FAFC.toInt())

        val angle = t * 2 * Math.PI
        val radius = max(w, h) * 0.6f

        for (i in blobs.indices) {
            val phase = angle + i * Math.PI / 2
            val cx = w * (0.5f + 0.35f * cos(phase).toFloat())
            val cy = h * (0.5f + 0.25f * sin(phase * 1.3).toFloat())

            paint.shader = RadialGradient(
                cx, cy, radius,
                withAlpha(blobs[i], 0.45f), 0x00FFFFFF,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
}

// ============================================================
//  PAGRINDINIS EKRANAS
// ============================================================

class MainActivity : AppCompatActivity() {

    private lateinit var lm: LocationManager
    private lateinit var info: TextView
    private lateinit var skyView: GnssView
    private lateinit var cnoView: CnoPlotView
    private lateinit var baro: Barometer
    private lateinit var mainExecutor: Executor
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastGpsH: Double? = null

    private var posText = "Laukiama fikso..."
    private var satText = "Laukiama palydovų..."
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
            skyView.sats = list

            val used = list.filter { it.used }
            val avg = if (used.isNotEmpty()) used.map { it.cn0.toDouble() }.average() else 0.0
            val max = list.maxOfOrNull { it.cn0 } ?: 0f
            val byConst = list.groupingBy { it.const }.eachCount().toSortedMap()

            cnoView.addSample(list, if (used.isNotEmpty()) avg.toFloat() else null)

            satText = buildString {
                appendLine("Matomi palydovai: ${list.size}")
                appendLine("Naudojami fiksui: ${used.size}")
                appendLine("Vidutinis C/N0 (naudojami): ${f(avg, 1)} dB-Hz")
                appendLine("Maks. C/N0: ${f(max.toDouble(), 1)} dB-Hz")
                append("Pagal konstelaciją: $byConst")
            }
            render()
        }
    }

    private val measCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            measText = "Neapdoroti matavimai: ${event.measurements.size}"
            render()
        }
    }

    private val locationListener = LocationListener { loc -> onLocation(loc) }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startGnss()
        } else {
            posText = "Reikia vietos leidimo."
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        val modeSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                GeoidMode.values().map { it.label }
            )
            setSelection(GeoidMode.values().indexOf(Geoid.mode))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    Geoid.select(applicationContext, GeoidMode.values()[pos]) {
                        runOnUiThread { render() }
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        val btnZero = Button(this).apply {
            text = "Nulis čia"
            setOnClickListener { baro.calibrateZero(); render() }
        }
        val btnGps = Button(this).apply {
            text = "Kalibruoti pagal GPS aukštį"
            setOnClickListener {
                val h = lastGpsH
                if (h != null) {
                    baro.calibrateTo(h)
                } else {
                    posText = "Nėra GPS aukščio (laukiama fikso ir geoido)."
                }
                render()
            }
        }
        val btnReset = Button(this).apply {
            text = "Atstatyti kalibravimą"
            setOnClickListener { baro.reset(); render() }
        }

        // Pirmą kartą įkeliame LIT20G fone
        Geoid.select(applicationContext, GeoidMode.LIT20G) {
            runOnUiThread { render() }
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(modeSpinner, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(btnZero, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(btnGps, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(btnReset, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(info, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(skyView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(cnoView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // Judantis fonas po visu turiniu
        val container = FrameLayout(this)
        container.addView(
            GradientBackground(this),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        container.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(container)
    }

    override fun onStart() {
        super.onStart()
        baro.start()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startGnss()
        } else {
            permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onStop() {
        super.onStop()
        baro.stop()
        lm.unregisterGnssStatusCallback(statusCallback)
        lm.unregisterGnssMeasurementsCallback(measCallback)
        lm.removeUpdates(locationListener)
    }

    @SuppressLint("MissingPermission")
    private fun startGnss() {
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            posText = "GPS išjungtas. Įjunkite vietos nustatymus."
            render()
        }

        lm.registerGnssStatusCallback(statusCallback, mainHandler)

        if (Build.VERSION.SDK_INT >= 31) {
            registerFullTracking()
        } else {
            lm.registerGnssMeasurementsCallback(measCallback, mainHandler)
        }

        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
    }

    @RequiresApi(31)
    @SuppressLint("MissingPermission")
    private fun registerFullTracking() {
        val request = GnssMeasurementRequest.Builder()
            .setFullTracking(true)
            .build()
        lm.registerGnssMeasurementsCallback(request, mainExecutor, measCallback)
    }

    private fun onLocation(loc: Location) {
        posText = buildString {
            appendLine("Platuma: ${f(loc.latitude, 7)}")
            appendLine("Ilguma: ${f(loc.longitude, 7)}")
            appendLine(
                if (loc.hasAccuracy()) "Tikslumas: ${f(loc.accuracy.toDouble(), 1)} m"
                else "Tikslumas: nežinomas"
            )
            if (loc.hasAltitude()) {
                appendLine("Aukštis (elipsoidas): ${f(loc.altitude, 1)} m")
                appendLine("Geoidas: ${Geoid.mode.label}")
                val n = Geoid.undulation(loc.latitude, loc.longitude)
                when {
                    n != null -> {
                        appendLine("Geoido unduliacija (N): ${f(n, 2)} m")
                        appendLine("Aukštis virš jūros (H): ${f(loc.altitude - n, 1)} m")
                    }
                    Geoid.lastError != null -> appendLine("Klaida: ${Geoid.lastError}")
                    !Geoid.isReady -> appendLine("Geoidas kraunamas...")
                    else -> appendLine("Taškas už pasirinkto geoido ribų")
                }
            }
            if (loc.hasSpeed()) appendLine("Greitis: ${f(loc.speed * 3.6, 1)} km/h")
            append("Laikas: ${java.text.DateFormat.getTimeInstance().format(Date(loc.time))}")
        }

        baro.updateMslp(loc.latitude, loc.longitude)

        lastGpsH = if (loc.hasAltitude()) {
            Geoid.undulation(loc.latitude, loc.longitude)?.let { loc.altitude - it }
        } else null

        render()
    }

    private fun render() {
        info.text = buildString {
            appendLine("=== FIKSAS ===")
            appendLine(posText)
            appendLine()
            appendLine("=== BAROMETRAS ===")
            if (!baro.hasSensor) {
                appendLine("Barometro jutiklio nėra")
            } else {
                val alt = baro.altitude()
                appendLine("Slėgis: ${baro.stationHpa?.let { f(it, 2) } ?: "—"} hPa")
                appendLine("MSLP: ${baro.mslpHpa?.let { f(it, 1) } ?: "—"} hPa (${baro.mslpSource})")
                appendLine("Barometrinis aukštis: ${alt?.let { f(it, 1) + " m" } ?: "—"}")
                appendLine("Kalibravimo poslinkis: ${f(baro.offsetM, 1)} m")
            }
            appendLine()
            appendLine("=== PALYDOVAI ===")
            appendLine(satText)
            appendLine()
            appendLine("=== NEAPDOROTI MATAVIMAI ===")
            append(measText)
        }
    }

    private fun f(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)

    private fun constName(type: Int) = when (type) {
        GnssStatus.CONSTELLATION_GPS -> "GPS"
        GnssStatus.CONSTELLATION_GALILEO -> "GAL"
        GnssStatus.CONSTELLATION_GLONASS -> "GLO"
        GnssStatus.CONSTELLATION_BEIDOU -> "BDS"
        GnssStatus.CONSTELLATION_QZSS -> "QZS"
        GnssStatus.CONSTELLATION_SBAS -> "SBS"
        GnssStatus.CONSTELLATION_IRNSS -> "NVC"
        else -> "?"
    }
}
