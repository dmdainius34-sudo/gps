package lt.example.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

data class Sat(
    val const: String,
    val svid: Int,
    val cn0: Float,
    val elev: Float,
    val az: Float,
    val used: Boolean
)

/** Piešia dangaus žemėlapį viršuje ir C/N0 stulpelius apačioje. */
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
        color = Color.DKGRAY
        textSize = 26f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun colorOf(c: String): Int = when (c) {
        "GPS" -> Color.rgb(37, 99, 235)
        "GAL" -> Color.rgb(22, 163, 74)
        "GLO" -> Color.rgb(234, 88, 12)
        "BDS" -> Color.rgb(220, 38, 38)
        "SBS" -> Color.rgb(107, 114, 128)
        else -> Color.DKGRAY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)

        val w = width.toFloat()
        val h = height.toFloat()
        val skyH = h * 0.5f

        // ---- Dangaus žemėlapis ----
        val cx = w / 2f
        val cy = skyH / 2f + 20f
        val r0 = min(w, skyH) / 2f - 40f

        // Žiedai: 0°, 30°, 60° kampas nuo horizonto
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
            val col = colorOf(s.const)

            dotPaint.color = col
            dotPaint.style = if (s.used) Paint.Style.FILL else Paint.Style.STROKE
            canvas.drawCircle(x, y, 16f, dotPaint)
            canvas.drawText(s.svid.toString(), x + 18f, y + 8f, textPaint)
        }

        // ---- C/N0 stulpeliai ----
        if (sats.isEmpty()) return

        val top = skyH + 40f
        val bottom = h - 50f
        val bh = bottom - top

        // 20 ir 40 dB-Hz linijos
        for (db in listOf(20f, 40f)) {
            val y = bottom - (db / 50f) * bh
            canvas.drawLine(20f, y, w - 20f, y, ringPaint)
            canvas.drawText("${db.toInt()}", 4f, y - 4f, textPaint)
        }
        canvas.drawText("C/N0 dB-Hz", 20f, top - 12f, textPaint)

        val sorted = sats.sortedWith(compareBy({ it.const }, { it.svid }))
        val slot = (w - 40f) / sorted.size
        val bw = min(slot * 0.7f, 40f)

        sorted.forEachIndexed { i, s ->
            val x0 = 20f + i * slot + (slot - bw) / 2f
            val barH = (s.cn0 / 50f).coerceIn(0f, 1f) * bh
            barPaint.color = colorOf(s.const)
            barPaint.alpha = if (s.used) 255 else 90
            canvas.drawRect(x0, bottom - barH, x0 + bw, bottom, barPaint)
            canvas.drawText(s.svid.toString(), x0, h - 20f, textPaint)
        }
    }
}

class MainActivity : AppCompatActivity() {

    private lateinit var lm: LocationManager
    private lateinit var info: TextView
    private lateinit var skyView: GnssView
    private lateinit var mainExecutor: Executor
    private val mainHandler = Handler(Looper.getMainLooper())

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

        info = TextView(this).apply {
            textSize = 14f
            setPadding(32, 32, 32, 16)
        }
        skyView = GnssView(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(info, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(skyView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
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
            if (loc.hasAltitude()) appendLine("Aukštis (elipsoidas): ${f(loc.altitude, 1)} m")
            if (loc.hasSpeed()) appendLine("Greitis: ${f(loc.speed * 3.6, 1)} km/h")
            append("Laikas: ${java.text.DateFormat.getTimeInstance().format(Date(loc.time))}")
        }
        render()
    }

    private fun render() {
        info.text = buildString {
            appendLine("=== FIKSAS ===")
            appendLine(posText)
            appendLine()
            appendLine("=== PALYDOVAI ===")
            appendLine(satText)
            appendLine()
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
