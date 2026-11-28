package lt.example.gnss

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import java.io.BufferedWriter
import java.io.File
import java.util.Locale

/**
 * Judesio jutiklių įrašas ZIP'ui (kaip Google GSDC device_imu.csv): akselerometras, giroskopas ir magnetometras be kalibravimo,
 * 20 Hz kiekvienas. Rašoma, kol programa atidaryta; failas ne didesnis kaip ~25 MB (~2 val.) – tada pradedamas iš naujo.
 * Stulpeliai: tipas (UncalAccel / UncalGyro / UncalMag), laikas_ms (UTC), jutiklio_ns (įvykio laikas nuo įjungimo), x, y, z.
 */
object ImuLog : SensorEventListener {
    const val FILE = "device_imu.csv"
    private const val HEADER = "tipas,laikas_ms,jutiklio_ns,x,y,z\n"
    private const val MIN_DT_NS = 50_000_000L
    private const val MAX_BYTES = 25L * 1024 * 1024
    private var w: BufferedWriter? = null
    private var f: File? = null
    private var written = 0L
    private var lastFlush = 0L
    private val lastNs = LongArray(3)
    @Volatile var rows = 0L
        private set

    @Synchronized fun start(ctx: Context) {
        if (w != null) return
        val file = File(ctx.cacheDir, FILE); f = file
        if (!file.exists() || file.length() > MAX_BYTES) file.writeText(HEADER)
        written = file.length()
        w = java.io.FileWriter(file, true).buffered()
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = (if (Build.VERSION.SDK_INT >= 26) sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED) else null) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyr = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED) ?: sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) ?: sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        for (s in listOfNotNull(acc, gyr, mag)) sm.registerListener(this, s, SensorManager.SENSOR_DELAY_GAME)
    }

    @Synchronized fun stop(ctx: Context) {
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        try { w?.flush(); w?.close() } catch (_: Exception) {}
        w = null
    }

    /** Eksportui: failas su visais įrašytais duomenimis. */
    @Synchronized fun file(ctx: Context): File {
        try { w?.flush() } catch (_: Exception) {}
        val file = File(ctx.cacheDir, FILE)
        if (!file.exists()) file.writeText(HEADER)
        return file
    }

    @Synchronized override fun onSensorChanged(e: SensorEvent) {
        val i = when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> 0
            Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> 1
            else -> 2
        }
        if (e.timestamp - lastNs[i] < MIN_DT_NS) return
        lastNs[i] = e.timestamp
        val out = w ?: return
        val ms = System.currentTimeMillis() + (e.timestamp - SystemClock.elapsedRealtimeNanos()) / 1_000_000L
        val name = when (i) { 0 -> "UncalAccel"; 1 -> "UncalGyro"; else -> "UncalMag" }
        val line = "%s,%d,%d,%.5f,%.5f,%.5f\n".format(Locale.US, name, ms, e.timestamp, e.values[0], e.values[1], e.values[2])
        try {
            out.write(line); written += line.length; rows++
            val now = SystemClock.elapsedRealtime()
            if (now - lastFlush > 1000L) { out.flush(); lastFlush = now }
            if (written > MAX_BYTES) {
                // per didelis – pradedama iš naujo (senesni duomenys pašalinami)
                out.close(); f?.writeText(HEADER); written = HEADER.length.toLong()
                w = java.io.FileWriter(f, true).buffered()
            }
        } catch (_: Exception) {}
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}
