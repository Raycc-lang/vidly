package org.raycc.vidly.native

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

class NativeCallService : Service() {
    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundWithType(FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    /** Promote the running foreground service to include the mediaProjection
     *  type. Must be called synchronously BEFORE the Activity obtains a
     *  MediaProjection token (Android 14 enforces that an FGS of type
     *  mediaProjection is already in the foreground, else SecurityException). */
    fun promoteToMediaProjection() {
        startForegroundWithType(FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Demote back to dataSync-only once screen capture has stopped. */
    fun demoteToDataSync() {
        startForegroundWithType(FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun startForegroundWithType(type: Int) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), type)
        } catch (_: Throwable) {
            // Older API levels (pre-29) lack the typed overload; fall back to
            // the untyped call which keeps the service foregrounded.
            try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (_: Throwable) {}
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): NativeCallService = this@NativeCallService
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vidly:NativeCallWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, NativeCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Vidly Native Call Active")
            .setContentText("Call is kept alive in the background")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(SystemClock.elapsedRealtime())
            .setCategory(Notification.CATEGORY_CALL)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "vidly_native_call_active"
        private const val NOTIFICATION_ID = 3001
        private const val ACTION_STOP = "org.raycc.vidly.native.action.STOP_CALL"
        // Foreground service type flags (API 29+). Wrapped so this compiles on
        // older SDKs; the typed startForeground overload is guarded at runtime.
        private val FOREGROUND_SERVICE_TYPE_DATA_SYNC =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        private val FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

        fun start(context: Context) {
            val intent = Intent(context, NativeCallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NativeCallService::class.java).setAction(ACTION_STOP))
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = context.getSystemService(NotificationManager::class.java)
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Native active calls", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Persistent notification while Vidly Native keeps a call alive."
                    setShowBadge(false)
                }
            )
        }
    }
}
