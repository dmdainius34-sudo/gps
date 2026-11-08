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
import android.location.GnssStatus
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
        private const val CHANNEL = "nav_record"
        private const val NOTIF_ID = 4711
        @Volatile var running = false
            private set
        @Volatile var listenerRegistered = false
            private set
        /** Kviečiama paslaugai sustojus (veikla atregistruoja savo GNSS klausytojus, jei ji fone). */
        @Volatile var onEnded: (() -> Unit)? = null
    }

    private var startedAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val statusCb = object : GnssStatus.Callback() {
        override fun onStarted() { GapLog.event(this@NavRecordService, "GNSS būsena: pradėta (onStarted)") }
        override fun onStopped() { GapLog.event(this@NavRecordService, "GNSS būsena: sustabdyta (onStopped)") }
        override fun onFirstFix(ttffMillis: Int) { GapLog.event(this@NavRecordService, "GNSS būsena: pirmas fiksas po $ttffMillis ms") }
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
            // pranešimo tekstą atnaujiname retai
            val n = NavRecorder.written
            if (n - lastNotifCount >= 50) { lastNotifCount = n; notifyCount() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        GapLog.event(this, "paslauga onStartCommand${if (intent?.action == ACTION_END) " (Baigti)" else if (intent == null) " (sistemos perkrauta)" else ""}")
        if (intent?.action == ACTION_END) {
            Log.i("GnssApp", "NavRecordService: sustabdyta (Baigti)")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
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
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = try {
            (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gnss:navrecord").apply {
                setReferenceCounted(false); acquire()
            }
        } catch (e: Exception) { Log.e("GnssApp", "NavRecordService: wake lock: ${e.message}"); null }
    }

    override fun onDestroy() {
        GapLog.event(this, "paslauga onDestroy")
        try { lm?.unregisterGnssNavigationMessageCallback(callback) } catch (_: Exception) {}
        try { lm?.unregisterGnssStatusCallback(statusCb) } catch (_: Exception) {}
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
