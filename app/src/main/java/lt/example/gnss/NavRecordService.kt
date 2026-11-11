package lt.example.gnss

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationListener
import android.os.PowerManager
import android.content.pm.ServiceInfo
import android.location.GnssNavigationMessage
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Priekinio plano paslauga: GNSS navigacijos pranešimai įrašomi į gnss_nav.csv ir išjungus ekraną
 * ar atidarius kitą programą. Nuolatinis pranešimas su mygtuku „Baigti“ paslaugą sustabdo.
 */
class NavRecordService : Service() {

    companion object {
        const val ACTION_END = "lt.example.gnss.NAV_RECORD_END"
        const val ACTION_COLD_START = "lt.example.gnss.NAV_COLD_START"
        private const val CHANNEL = "nav_record"
        private const val NOTIF_ID = 4711
        @Volatile var running = false
            private set
        @Volatile var listenerRegistered = false
            private set
        /** Paskutinis GnssNavigationMessage.Callback.onStatusChanged (−1 – dar negautas). */
        @Volatile var navStatus = -1
            private set
        fun navStatusText(): String = when (navStatus) {
            GnssNavigationMessage.Callback.STATUS_READY -> "STATUS_READY (palaikoma, žinutės siunčiamos)"
            GnssNavigationMessage.Callback.STATUS_NOT_SUPPORTED -> "STATUS_NOT_SUPPORTED (telefonas nepalaiko)"
            GnssNavigationMessage.Callback.STATUS_LOCATION_DISABLED -> "STATUS_LOCATION_DISABLED (vieta išjungta)"
            -1 -> "dar negautas"
            else -> "kodas $navStatus"
        }
                /** Kviečiama paslaugai sustojus (veikla atregistruoja savo GNSS klausytojus, jei ji fone). */
        @Volatile var onEnded: (() -> Unit)? = null
    }

    private var startedAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val statusCb = object : GnssStatus.Callback() {
        override fun onStarted() { GapLog.event(this@NavRecordService, "GNSS būsena: pradėta (onStarted)") }
        override fun onStopped() { GapLog.event(this@NavRecordService, "GNSS būsena: sustabdyta (onStopped)") }
        override fun onFirstFix(ttffMillis: Int) { GapLog.event(this@NavRecordService, "GNSS būsena: pirmas fiksas po $ttffMillis ms") }
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val m = HashMap<String, Sat>()
            for (i in 0 until status.satelliteCount) {
                val x = Sat(const = constName(status.getConstellationType(i)), svid = status.getSvid(i),
                    cn0 = status.getCn0DbHz(i), elev = status.getElevationDegrees(i),
                    az = status.getAzimuthDegrees(i), used = status.usedInFix(i))
                m[x.const + x.svid] = x
            }
            RawRecorder.sats = m
        }
    }

    /** gnss_raw.csv rašo tik paslauga (vienas rašytojas); kiekviena partija nuplaunama. */
    private val measCb = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            RawRecorder.onEvent(this@NavRecordService, event)
        }
    }

    /** Fiksai: kol veikla gyva, juos rašo ji (su padėties laukais); kitaip – paslauga. */
    private val locCb = LocationListener { loc ->
        if (!SessionFiles.activityWritesFixes) SessionFiles.writeFix(this, RawRecorder.fixLineWithoutActivity(loc))
    }

    private val sysReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val what = when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> "ekranas išjungtas"
                Intent.ACTION_SCREEN_ON -> "ekranas įjungtas"
                LocationManager.PROVIDERS_CHANGED_ACTION -> "vietos tiekėjai pasikeitė (GPS ${if (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) "įjungtas" else "išjungtas"})"
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "Doze: ${if ((getSystemService(POWER_SERVICE) as PowerManager).isDeviceIdleMode) "įjungtas" else "išjungtas"}"
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "energijos taupymas: ${if ((getSystemService(POWER_SERVICE) as PowerManager).isPowerSaveMode) "įjungtas" else "išjungtas"}"
                else -> return
            }
            GapLog.event(this@NavRecordService, what)
        }
    }
    private var receiverOn = false

    override fun onCreate() {
        super.onCreate()
        GapLog.event(this, "paslauga onCreate")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        GapLog.event(this, "paslauga onTaskRemoved (programa pašalinta iš naujausių)")
        super.onTaskRemoved(rootIntent)
    }

    private var lm: LocationManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastNotifCount = -1L

    private val callback = object : GnssNavigationMessage.Callback() {
        override fun onGnssNavigationMessageReceived(event: GnssNavigationMessage) {
            NavRecorder.append(cacheDir, event)
            if (event.type == GnssNavigationMessage.TYPE_GPS_L1CA) try {
                BroadcastEph.add(event.svid, event.data, Math.floorDiv(Sp3Live.gpsNow().toLong(), 604_800L).toInt())
            } catch (e: Exception) { Log.w("GnssApp", "BroadcastEph: ${e.message}") }
            // pranešimo tekstą atnaujiname retai
            val n = NavRecorder.written
            if (n - lastNotifCount >= 50) { lastNotifCount = n; notifyCount() }
        }
        override fun onStatusChanged(status: Int) {
            navStatus = status
            GapLog.event(this@NavRecordService, "navigacijos žinučių statusas: ${navStatusText()}")
            Log.i("GnssApp", "NavRecordService: navigacijos statusas ${navStatusText()}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GapLog.event(this, "paslauga onStartCommand${if (intent?.action == ACTION_END) " (Baigti)" else if (intent == null) " (sistemos perkrauta)" else ""}")
        if (intent?.action == ACTION_END) {
            Log.i("GnssApp", "NavRecordService: sustabdyta (Baigti)")
            SessionFiles.end(this)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_COLD_START && running) {
            coldStart()
            return START_STICKY
        }
        try {
            val notif = buildNotification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.e("GnssApp", "NavRecordService: priekinio plano paleisti nepavyko: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null) GapLog.event(this, "PALEISTA IŠ NAUJO (START_STICKY): sistema atkūrė sustabdytą paslaugą")
        SessionFiles.open(this)
        if (startedAt == 0L) startedAt = System.currentTimeMillis()
        register()
        acquireWakeLock()
        if (!receiverOn) {
            receiverOn = true
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED); addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(sysReceiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(sysReceiver, f)
        }
        running = true
        notifyCount()
        Log.i("GnssApp", "NavRecordService: veikia priekiniame plane, klausytojas ${if (listenerRegistered) "užregistruotas" else "neužregistruotas"}")
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun register() {
        if (listenerRegistered) return
        val m = getSystemService(LOCATION_SERVICE) as LocationManager
        lm = m
        listenerRegistered = try {
            m.registerGnssNavigationMessageCallback(callback, handler)
        } catch (e: Exception) {
            Log.e("GnssApp", "NavRecordService: klausytojas: ${e.message}"); false
        }
        try { m.registerGnssStatusCallback(statusCb, handler) } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= 31) m.registerGnssMeasurementsCallback(GnssMeasurementRequest.Builder().setFullTracking(true).build(), mainExecutor, measCb)
            else m.registerGnssMeasurementsCallback(measCb, handler)
        } catch (e: Exception) { Log.e("GnssApp", "NavRecordService: matavimai: ${e.message}") }
        try { m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locCb, Looper.getMainLooper()) } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = try {
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gnss:navrecord").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Exception) { Log.e("GnssApp", "NavRecordService: wake lock: ${e.message}"); null }
    }

    private fun unregisterAll() {
        try { lm?.unregisterGnssNavigationMessageCallback(callback) } catch (_: Exception) {}
        try { lm?.unregisterGnssStatusCallback(statusCb) } catch (_: Exception) {}
        try { lm?.unregisterGnssMeasurementsCallback(measCb) } catch (_: Exception) {}
        try { lm?.removeUpdates(locCb) } catch (_: Exception) {}
        listenerRegistered = false
    }

    /** Šaltas startas: GNSS klausytojai atjungiami, pagalbos duomenys ištrinami, po 3 s prijungiama iš naujo. */
    private fun coldStart() {
        unregisterAll()
        SessionFiles.flush()
        val m = lm ?: (getSystemService(LOCATION_SERVICE) as LocationManager)
        val ok = deleteAidingData(m)
        GapLog.event(this, "ŠALTAS STARTAS: pagalbos duomenų trynimas ${if (ok) "priimtas" else "nepriimtas"}; GNSS klausytojai atjungti 3 s")
        handler.postDelayed({ if (running) { register(); GapLog.event(this, "ŠALTAS STARTAS: GNSS klausytojai prijungti iš naujo") } }, 3000)
    }

    override fun onDestroy() {
        GapLog.event(this, "paslauga onDestroy")
        unregisterAll()
        SessionFiles.flush()
        if (receiverOn) { try { unregisterReceiver(sysReceiver) } catch (_: Exception) {}; receiverOn = false }
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        listenerRegistered = false
        running = false
        handler.post { onEnded?.invoke() }
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "GNSS navigacijos įrašymas", NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val end = PendingIntent.getService(this, 1, Intent(this, NavRecordService::class.java).setAction(ACTION_END), flags)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GNSS navigacijos įrašymas")
            .setContentText("Navigacijos pranešimų: ${NavRecorder.written}")
            .setWhen(if (startedAt > 0) startedAt else System.currentTimeMillis())
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Baigti", end).build())
            .build()
    }

    private fun notifyCount() {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
        } catch (_: Exception) {}
    }
}

/** LocationManager „delete_aiding_data“ (efemeridės, almanachas, padėtis, laikas, ionosfera, UTC, sveikata). */
fun deleteAidingData(m: LocationManager): Boolean = try {
    m.sendExtraCommand(LocationManager.GPS_PROVIDER, "delete_aiding_data", null)
} catch (e: Exception) { Log.e("GnssApp", "delete_aiding_data: ${e.message}"); false }
