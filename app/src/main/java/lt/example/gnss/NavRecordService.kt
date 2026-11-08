package lt.example.gnss

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
        register()
        running = true
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
    }

    override fun onDestroy() {
        try { lm?.unregisterGnssNavigationMessageCallback(callback) } catch (_: Exception) {}
        listenerRegistered = false
        running = false
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
            .setContentText("Įrašyta į gnss_nav.csv: ${NavRecorder.written}")
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
