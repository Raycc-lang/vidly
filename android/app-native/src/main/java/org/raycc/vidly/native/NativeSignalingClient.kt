package org.raycc.vidly.native

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

class NativeSignalingClient(
    context: Context,
    private val listener: Listener,
    private val signalingUrl: String = SIGNALING_URL
) {
    interface Listener {
        fun onJoined(roomId: String, peers: List<PeerInfo>)
        fun onPeerJoined(peer: PeerInfo)
        fun onPeerLeft(peerId: String, username: String?)
        fun onSignal(from: String, payload: JSONObject)
        fun onRoomFull()
        fun onUsernameTaken(username: String)
        fun onStatus(message: String)
    }

    data class PeerInfo(val peerId: String, val username: String?)

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var roomId = ""
    private var username = ""
    private var closedByUser = false
    private var reconnectMs = INITIAL_RECONNECT_MS
    private val clientId by lazy { getOrCreateClientId(context.applicationContext ?: context) }
    private val pageSessionId = UUID.randomUUID().toString()
    private val reconnectRunnable = Runnable { reconnect() }

    fun connect(roomId: String, username: String) {
        this.roomId = roomId
        this.username = username
        closedByUser = false
        reconnectMs = INITIAL_RECONNECT_MS
        main.removeCallbacks(reconnectRunnable)
        listener.onStatus("Connecting...")
        openWebSocket()
    }

    fun sendSignal(to: String, payload: JSONObject) {
        send(JSONObject().put("type", "signal").put("to", to).put("payload", payload))
    }

    fun leave() {
        closedByUser = true
        main.removeCallbacks(reconnectRunnable)
        send(JSONObject().put("type", "leave"))
        webSocket?.close(1000, "leaving")
        webSocket = null
    }

    fun dispose() {
        closedByUser = true
        main.removeCallbacks(reconnectRunnable)
        webSocket?.cancel()
        webSocket = null
        http.dispatcher.executorService.shutdown()
    }

    private fun openWebSocket() {
        val previous = webSocket
        webSocket = null
        previous?.cancel()
        webSocket = http.newWebSocket(Request.Builder().url(signalingUrl).build(), SocketListener())
    }

    private fun reconnect() {
        if (closedByUser || roomId.isBlank() || username.isBlank()) return
        listener.onStatus("Reconnecting...")
        openWebSocket()
    }

    private fun scheduleReconnect(message: String) {
        if (closedByUser) return
        listener.onStatus(message)
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, reconnectMs)
        reconnectMs = min(reconnectMs * 2, MAX_RECONNECT_MS)
    }

    private fun join() {
        send(JSONObject().put("type", "set-username").put("username", username))
        send(
            JSONObject()
                .put("type", "join")
                .put("roomId", roomId)
                .put("username", username)
                .put("clientId", clientId)
                .put("pageSessionId", pageSessionId)
        )
    }

    private fun send(payload: JSONObject) {
        webSocket?.send(payload.toString())
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "joined" -> {
                val peers = msg.optJSONArray("peers").toPeerInfos()
                listener.onJoined(msg.optString("roomId"), peers)
            }
            "peer-joined" -> listener.onPeerJoined(
                PeerInfo(msg.optString("peerId"), msg.optString("username").takeIf { it.isNotBlank() })
            )
            "peer-resumed" -> listener.onPeerJoined(
                PeerInfo(msg.optString("peerId"), msg.optString("username").takeIf { it.isNotBlank() })
            )
            "peer-left" -> listener.onPeerLeft(
                msg.optString("peerId"),
                msg.optString("username").takeIf { it.isNotBlank() }
            )
            "signal" -> listener.onSignal(msg.optString("from"), msg.optJSONObject("payload") ?: JSONObject())
            "room-full" -> listener.onRoomFull()
            "username-taken" -> listener.onUsernameTaken(msg.optString("username"))
        }
    }

    private fun JSONArray?.toPeerInfos(): List<PeerInfo> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val peer = optJSONObject(i) ?: continue
                add(PeerInfo(peer.optString("peerId"), peer.optString("username").takeIf { it.isNotBlank() }))
            }
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            main.post {
                if (this@NativeSignalingClient.webSocket !== webSocket) return@post
                reconnectMs = INITIAL_RECONNECT_MS
                listener.onStatus("Joining room...")
                join()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post {
                if (this@NativeSignalingClient.webSocket !== webSocket) return@post
                handle(text)
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            main.post {
                if (this@NativeSignalingClient.webSocket !== webSocket) return@post
                this@NativeSignalingClient.webSocket = null
                scheduleReconnect("Disconnected. Reconnecting soon...")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            main.post {
                if (this@NativeSignalingClient.webSocket !== webSocket) return@post
                this@NativeSignalingClient.webSocket = null
                scheduleReconnect("Signaling failed: ${t.localizedMessage ?: "unknown error"}. Reconnecting soon...")
            }
        }
    }

    companion object {
        const val SIGNALING_URL = "wss://voice.raycc.org"
        private const val PREFS_NAME = "vidly_prefs"
        private const val KEY_CLIENT_ID = "client_id"
        private const val INITIAL_RECONNECT_MS = 1_000L
        private const val MAX_RECONNECT_MS = 10_000L

        private fun getOrCreateClientId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_CLIENT_ID, null)?.let { return it }
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_CLIENT_ID, newId).apply()
            return newId
        }

        fun httpUrlFor(path: String, signalingUrl: String = SIGNALING_URL): String {
            val base = signalingUrl
                .replaceFirst("wss://", "https://")
                .replaceFirst("ws://", "http://")
                .trimEnd('/')
            val root = runCatching { base.toHttpUrl().newBuilder().encodedPath("/").build().toString().trimEnd('/') }
                .getOrDefault(base)
            return "$root/${path.trimStart('/')}"
        }
    }
}