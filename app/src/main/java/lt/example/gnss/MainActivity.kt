package lt.example.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
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
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var lm: LocationManager
    private lateinit var info: TextView
    private lateinit var mainExecutor: Executor
    private val mainHandler = Handler(Looper.getMainLooper())

    private var posText = "Laukiama fikso..."
    private var satText = "Laukiama palydovų..."
    private var measText = "Laukiama matavimų..."

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            var usedSum = 0.0
            var maxCn0 = 0f
            val byConst = sortedMapOf<String, Int>()

            for (i in 0 until status.satelliteCount) {
                val cn0 = status.getCn0DbHz(i)
                val name = constName(status.getConstellationType(i))
                byConst[name] = (byConst[name] ?: 0) + 1
                if (status.usedInFix(i)) {
                    used++
                    usedSum += cn0
                }
                if (cn0 > maxCn0) maxCn0 = cn0
            }
            val avg = if (used > 0) usedSum / used else 0.0

            satText = buildString {
                appendLine("Matomi palydovai: ${status.satelliteCount}")
                appendLine("Naudojami fiksui: $used")
                appendLine("Vidutinis C/N0 (naudojami): ${f(avg, 1)} dB-Hz")
                appendLine("Maks. C/N0: ${f(maxCn0.toDouble(), 1)} dB-Hz")
                appendLine("Pagal konstelaciją: $byConst")
            }
            render()
        }
    }

    private val measCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val list = event.measurements
            val perSat = list.groupBy { constName(it.constellationType) }
            measText = buildString {
                appendLine("Neapdoroti matavimai: ${list.size}")
                for ((c, ms) in perSat) {
                    val avg = ms.map { it.cn0DbHz }.average()
                    appendLine("  $c: ${ms.size} sig., C/N0 vid. ${f(avg, 1)} dB-Hz")
                }
            }
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
            textSize = 15f
            setPadding(32, 32, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(info) })
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

        // Vieta kas 1 sekundę, be minimalaus atstumo
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
    }

    @RequiresApi(31)
    @SuppressLint("MissingPermission")
    private fun registerFullTracking() {
        // Pilnas sekimas tik šiai programai, nepriklausomai nuo kūrėjo parinkties
        val request = GnssMeasurementRequest.Builder()
            .setFullTracking(true)
            .build()
        lm.registerGnssMeasurementsCallback(request, mainExecutor, measCallback)
    }

    private fun onLocation(loc: Location) {
        posText = buildString {
            appendLine("Platuma: ${f(loc.latitude, 7)}")
            appendLine("Ilguma: ${f(loc.longitude, 7)}")
            if (loc.hasAccuracy()) {
                appendLine("Tikslumas: ${f(loc.accuracy.toDouble(), 1)} m")
            } else {
                appendLine("Tikslumas: nežinomas")
            }
            if (loc.hasAltitude()) {
                appendLine("Aukštis (elipsoidas): ${f(loc.altitude, 1)} m")
            }
            if (loc.hasSpeed()) {
                appendLine("Greitis: ${f(loc.speed * 3.6, 1)} km/h")
            }
            appendLine("Laikas: ${java.text.DateFormat.getTimeInstance().format(Date(loc.time))}")
        }
        render()
    }

    private fun render() {
        info.text = buildString {
            appendLine("=== FIKSAS ===")
            appendLine(posText)
            appendLine("=== PALYDOVAI ===")
            appendLine(satText)
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
