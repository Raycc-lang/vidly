package org.raycc.vidly

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

class SignalingListener : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var reconnectMs = 1_000L
    private var intentionalStop = false
    private var roomId = ""
    private var username = ""
    private var clientId = ""

    private val reconnectRunnable = Runnable { connect() }

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        val prefs = prefs()
        roomId = prefs.getString(KEY_ROOM, "") ?: ""
        username = prefs.getString(KEY_USERNAME, "") ?: ""
        clientId = prefs.getString(KEY_CLIENT_ID, "") ?: ""
        if (clientId.isBlank()) {
            clientId = "android-listener-" + UUID.randomUUID().toString()
            prefs.edit().putString(KEY_CLIENT_ID, clientId).apply()
        }
        startForeground(LISTENER_NOTIFICATION_ID, buildListenerNotification())
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                intentionalStop = true
                webSocket?.close(1000, "stopped")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONFIGURE -> {
                val newRoom = intent.getStringExtra(EXTRA_ROOM).orEmpty()
                val newUsername = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
                val changed = newRoom != roomId || newUsername != username
                roomId = newRoom
                username = newUsername
                prefs().edit()
                    .putString(KEY_ROOM, roomId)
                    .putString(KEY_USERNAME, username)
                    .apply()
                if (changed) {
                    webSocket?.close(1000, "room changed")
                    connectSoon(0)
                } else {
                    joinRoom()
                }
            }
            ACTION_JOIN_NOW -> {
                // App went to background — join room to detect incoming calls
                joinRoom()
            }
            ACTION_RECONNECT -> {
                // App came to foreground — reconnect without joining room
                webSocket?.close(1000, "foreground")
                connectSoon(0)
            }
            else -> connect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        intentionalStop = true
        handler.removeCallbacksAndMessages(null)
        webSocket?.close(1000, "service destroyed")
        webSocket = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connect() {
        if (intentionalStop || roomId.isBlank() || username.isBlank()) return
        handler.removeCallbacks(reconnectRunnable)
        webSocket?.cancel()
        val request = Request.Builder().url(SIGNALING_URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectMs = 1_000L
                joinRoom()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!intentionalStop) connectSoon(reconnectMs)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!intentionalStop) connectSoon(reconnectMs)
            }
        })
    }

    private fun connectSoon(delayMs: Long) {
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, delayMs)
        reconnectMs = min(reconnectMs * 2, 30_000L)
    }

    private fun joinRoom() {
        if (roomId.isBlank() || username.isBlank()) return
        // Don't join room when app is in foreground — the WebView handles the call.
        // Only join when backgrounded, to detect incoming peers.
        if (prefs().getBoolean(KEY_FOREGROUND, false)) return
        val payload = JSONObject()
            .put("type", "join")
            .put("roomId", roomId)
            .put("username", "$username (Android listener)")
            .put("clientId", clientId)
        webSocket?.send(payload.toString())
    }

    private fun handleMessage(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (msg.optString("type") != "peer-joined") return
        if (prefs().getBoolean(KEY_FOREGROUND, false)) return

        val caller = msg.optString("username").ifBlank { "Someone" }
        showIncomingCall(caller)
    }

    private fun showIncomingCall(caller: String) {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_ROOM, roomId)
        val pendingIntent = PendingIntent.getActivity(
            this,
            2001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, INCOMING_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("Incoming Call")
            .setContentText("$caller is calling — tap to open")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .setDefaults(Notification.DEFAULT_ALL)
            .build()

        getSystemService(NotificationManager::class.java).notify(INCOMING_NOTIFICATION_ID, notification)
    }

    private fun buildListenerNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, LISTENER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentTitle("Vidly call listener")
            .setContentText("Listening for incoming calls")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    companion object {
        const val EXTRA_ROOM = "room"
        const val EXTRA_USERNAME = "username"

        private const val SIGNALING_URL = "wss://voice.raycc.org"
        private const val PREFS = "vidly"
        private const val KEY_ROOM = "room"
        private const val KEY_USERNAME = "username"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_FOREGROUND = "foreground"
        private const val ACTION_CONFIGURE = "org.raycc.vidly.action.CONFIGURE_SIGNALING"
        private const val ACTION_STOP = "org.raycc.vidly.action.STOP_SIGNALING"
        private const val ACTION_JOIN_NOW = "org.raycc.vidly.action.JOIN_NOW"
        private const val ACTION_RECONNECT = "org.raycc.vidly.action.RECONNECT"
        private const val LISTENER_CHANNEL_ID = "vidly_call_listener"
        private const val INCOMING_CHANNEL_ID = "vidly_incoming_calls"
        private const val LISTENER_NOTIFICATION_ID = 1002
        private const val INCOMING_NOTIFICATION_ID = 2002

        fun start(context: Context, roomId: String = "", username: String = "") {
            val intent = Intent(context, SignalingListener::class.java).apply {
                action = ACTION_CONFIGURE
                putExtra(EXTRA_ROOM, roomId)
                putExtra(EXTRA_USERNAME, username)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun configure(context: Context, roomId: String, username: String) {
            val prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE)
            val oldRoom = prefs.getString(KEY_ROOM, "") ?: ""
            val oldUser = prefs.getString(KEY_USERNAME, "") ?: ""
            if (oldRoom == roomId && oldUser == username) return
            start(context, roomId, username)
        }

        fun setAppForeground(context: Context, foreground: Boolean) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FOREGROUND, foreground)
                .apply()
            // Notify the service: background → join room, foreground → reconnect without joining
            val action = if (foreground) ACTION_RECONNECT else ACTION_JOIN_NOW
            val intent = Intent(context, SignalingListener::class.java).apply { this.action = action }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) { }
        }

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    LISTENER_CHANNEL_ID,
                    "Incoming call listener",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent listener used to keep Vidly reachable."
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    INCOMING_CHANNEL_ID,
                    "Incoming calls",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Heads-up notifications for Vidly incoming calls."
                    enableVibration(true)
                    enableLights(true)
                }
            )
        }
    }
}
