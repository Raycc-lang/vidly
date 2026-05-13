package org.raycc.vidly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

class CallService : Service() {
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

        startForeground(NOTIFICATION_ID, buildNotification())
        acquireWakeLock()
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Vidly:CallWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Vidly Call Active")
            .setContentText("Call is kept alive in the background")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(SystemClock.elapsedRealtime())
            .setCategory(Notification.CATEGORY_CALL)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "vidly_call_active"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "org.raycc.vidly.action.STOP_CALL"

        fun start(context: Context) {
            val intent = Intent(context, CallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CallService::class.java).setAction(ACTION_STOP))
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Active calls",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Persistent notification while Vidly keeps a call alive."
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
