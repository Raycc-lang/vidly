package org.raycc.vidly.native

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

class NativeSignalingClient(
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
    private val clientId = UUID.randomUUID().toString()

    fun connect(roomId: String, username: String) {
        this.roomId = roomId
        this.username = username
        closedByUser = false
        listener.onStatus("Connecting...")
        webSocket?.cancel()
        webSocket = http.newWebSocket(Request.Builder().url(signalingUrl).build(), SocketListener())
    }

    fun sendSignal(to: String, payload: JSONObject) {
        send(JSONObject().put("type", "signal").put("to", to).put("payload", payload))
    }

    fun leave() {
        closedByUser = true
        send(JSONObject().put("type", "leave"))
        webSocket?.close(1000, "leaving")
        webSocket = null
    }

    fun dispose() {
        closedByUser = true
        webSocket?.cancel()
        webSocket = null
        http.dispatcher.executorService.shutdown()
    }

    private fun join() {
        send(JSONObject().put("type", "set-username").put("username", username))
        send(
            JSONObject()
                .put("type", "join")
                .put("roomId", roomId)
                .put("username", username)
                .put("clientId", clientId)
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
                listener.onStatus("Joining room...")
                join()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            main.post { handle(text) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!closedByUser) main.post { listener.onStatus("Disconnected") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!closedByUser) main.post { listener.onStatus("Signaling failed: ${t.localizedMessage ?: "unknown error"}") }
        }
    }

    companion object {
        const val SIGNALING_URL = "wss://voice.raycc.org"

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
