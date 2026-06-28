package org.raycc.vidly.native

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlin.math.min

class NativeSignalingClient(
    context: Context,
    private val listener: Listener,
    private val signalingUrl: String = SIGNALING_URL,
    private val onDebugLog: (String) -> Unit = {}
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
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var roomId = ""
    private var username = ""
    private var peerId: String? = null
    private var pollCursor = 0L
    private var closedByUser = false
    private var reconnectMs = INITIAL_RECONNECT_MS
    private var generation = 0
    private var pollInFlight = false
    private val clientId by lazy { getOrCreateClientId(context.applicationContext ?: context) }
    private val pageSessionId = UUID.randomUUID().toString()
    private val reconnectRunnable = Runnable { reconnect() }
    private val pollRunnable = Runnable { pollOnce() }

    fun connect(roomId: String, username: String) {
        this.roomId = roomId
        this.username = username
        this.peerId = null
        pollCursor = 0L
        closedByUser = false
        reconnectMs = INITIAL_RECONNECT_MS
        generation++
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(pollRunnable)
        listener.onStatus("Connecting...")
        log("connect room=${roomId.ifBlank { "default" }} usernameLength=${username.length} gen=$generation")
        join(generation)
    }

    fun sendSignal(to: String, payload: JSONObject) {
        val from = peerId ?: return
        val requestGeneration = generation
        val body = JSONObject()
            .put("peerId", from)
            .put("to", to)
            .put("payload", payload)
        postJson(apiUrl("send"), body, requestGeneration, object : JsonCallback {
            override fun onSuccess(json: JSONObject) = Unit
            override fun onFailure(message: String) {
                main.post {
                    if (!isCurrent(requestGeneration)) return@post
                    listener.onStatus("Signaling send failed: $message")
                }
            }
        })
    }

    fun leave() {
        closedByUser = true
        generation++
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(pollRunnable)
        val leavingPeerId = peerId
        peerId = null
        pollInFlight = false
        if (leavingPeerId != null && roomId.isNotBlank()) {
            postJson(
                apiUrl("leave"),
                JSONObject().put("peerId", leavingPeerId),
                generation,
                object : JsonCallback {
                    override fun onSuccess(json: JSONObject) = Unit
                    override fun onFailure(message: String) = Unit
                }
            )
        }
    }

    fun dispose() {
        closedByUser = true
        generation++
        main.removeCallbacks(reconnectRunnable)
        main.removeCallbacks(pollRunnable)
        http.dispatcher.cancelAll()
        http.dispatcher.executorService.shutdown()
    }

    private fun join(requestGeneration: Int) {
        if (closedByUser || roomId.isBlank() || username.isBlank()) return
        listener.onStatus("Joining room...")
        log("join start gen=$requestGeneration room=${roomId.ifBlank { "default" }}")
        val body = JSONObject()
            .put("username", username)
            .put("clientId", clientId)
            .put("pageSessionId", pageSessionId)
        postJson(apiUrl("join"), body, requestGeneration, object : JsonCallback {
            override fun onSuccess(json: JSONObject) {
                main.post {
                    if (!isCurrent(requestGeneration)) {
                        log("join response dropped stale requestGen=$requestGeneration currentGen=$generation closed=$closedByUser")
                        return@post
                    }
                    log("join response accepted type=${json.optString("type")} peer=${json.optString("peerId").take(8)} cursor=${json.optLong("cursor", -1L)}")
                    when (json.optString("type")) {
                        "joined" -> {
                            reconnectMs = INITIAL_RECONNECT_MS
                            peerId = json.optString("peerId").takeIf { it.isNotBlank() }
                            pollCursor = json.optLong("cursor", 0L)
                            handle(json)
                            schedulePoll(POLL_INTERVAL_MS)
                        }
                        "room-full" -> listener.onRoomFull()
                        "username-taken" -> listener.onUsernameTaken(json.optString("username"))
                        else -> scheduleReconnect("Unexpected signaling response. Reconnecting soon...")
                    }
                }
            }

            override fun onFailure(message: String) {
                main.post {
                    if (!isCurrent(requestGeneration)) {
                        log("join failure dropped stale requestGen=$requestGeneration currentGen=$generation message=$message")
                        return@post
                    }
                    log("join failure currentGen=$generation message=$message")
                    scheduleReconnect("Signaling failed: $message. Reconnecting soon...")
                }
            }
        })
    }

    private fun pollOnce() {
        if (closedByUser || pollInFlight) return
        val currentPeerId = peerId ?: return
        val requestGeneration = generation
        pollInFlight = true
        val url = apiUrl("poll").toHttpUrl().newBuilder()
            .addQueryParameter("peerId", currentPeerId)
            .addQueryParameter("since", pollCursor.toString())
            .build()
        val requestId = nextRequestId()
        log("poll enqueue id=$requestId gen=$requestGeneration peer=${currentPeerId.take(8)} since=$pollCursor url=$url")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("User-Agent", "Vidly-Android-Native")
            .header("X-Vidly-Client-Trace", requestId)
            .get()
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post {
                    pollInFlight = false
                    if (!isCurrent(requestGeneration)) {
                        log("poll failure dropped id=$requestId requestGen=$requestGeneration currentGen=$generation error=${e.javaClass.simpleName}:${e.localizedMessage}")
                        return@post
                    }
                    log("poll failure id=$requestId error=${e.javaClass.simpleName}:${e.localizedMessage}")
                    scheduleReconnect("Signaling poll failed: ${e.localizedMessage ?: "network error"}. Reconnecting soon...")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val successful = response.isSuccessful
                val text = response.use { it.body?.string().orEmpty() }
                val status = response.code
                val trace = response.header("X-Vidly-Trace").orEmpty()
                main.post {
                    pollInFlight = false
                    if (!isCurrent(requestGeneration)) {
                        log("poll response dropped id=$requestId status=$status trace=$trace requestGen=$requestGeneration currentGen=$generation body=${text.take(200)}")
                        return@post
                    }
                    log("poll response id=$requestId status=$status trace=$trace bytes=${text.length}")
                    if (!successful) {
                        scheduleReconnect("Signaling disconnected. Reconnecting soon...")
                        return@post
                    }
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    if (json == null) {
                        schedulePoll(POLL_INTERVAL_MS)
                        return@post
                    }
                    pollCursor = json.optLong("cursor", pollCursor)
                    val messages = json.optJSONArray("messages")
                    if (messages != null) {
                        for (i in 0 until messages.length()) {
                            messages.optJSONObject(i)?.let { handle(it) }
                        }
                    }
                    schedulePoll(POLL_INTERVAL_MS)
                }
            }
        })
    }

    private fun reconnect() {
        if (closedByUser || roomId.isBlank() || username.isBlank()) return
        peerId = null
        pollCursor = 0L
        pollInFlight = false
        generation++
        listener.onStatus("Reconnecting...")
        log("reconnect gen=$generation")
        join(generation)
    }

    private fun scheduleReconnect(message: String) {
        if (closedByUser) return
        peerId = null
        pollInFlight = false
        listener.onStatus(message)
        main.removeCallbacks(pollRunnable)
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, reconnectMs)
        reconnectMs = min(reconnectMs * 2, MAX_RECONNECT_MS)
    }

    private fun schedulePoll(delayMs: Long) {
        if (closedByUser || peerId == null) return
        main.removeCallbacks(pollRunnable)
        main.postDelayed(pollRunnable, delayMs)
    }

    private fun postJson(url: String, body: JSONObject, requestGeneration: Int, callback: JsonCallback) {
        val requestId = nextRequestId()
        log("postJson enqueue id=$requestId gen=$requestGeneration url=$url bytes=${body.toString().length} keys=${body.keys().asSequence().joinToString(",")}")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("User-Agent", "Vidly-Android-Native")
            .header("X-Vidly-Client-Trace", requestId)
            .post(body.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!isCurrent(requestGeneration)) {
                    log("postJson failure dropped id=$requestId requestGen=$requestGeneration currentGen=$generation error=${e.javaClass.simpleName}:${e.localizedMessage}")
                    return
                }
                log("postJson failure id=$requestId error=${e.javaClass.simpleName}:${e.localizedMessage}")
                callback.onFailure(e.localizedMessage ?: "network error")
            }

            override fun onResponse(call: Call, response: Response) {
                val status = response.code
                val trace = response.header("X-Vidly-Trace").orEmpty()
                val workerStage = response.header("X-Vidly-Worker-Stage").orEmpty()
                val doStage = response.header("X-Vidly-DO-Stage").orEmpty()
                val text = response.use { it.body?.string().orEmpty() }
                if (!isCurrent(requestGeneration)) {
                    log("postJson response dropped id=$requestId status=$status trace=$trace requestGen=$requestGeneration currentGen=$generation body=${text.take(200)}")
                    return
                }
                log("postJson response id=$requestId status=$status trace=$trace workerStage=$workerStage doStage=$doStage bytes=${text.length} body=${text.take(200)}")
                if (!response.isSuccessful) {
                    callback.onFailure("HTTP $status ${text.take(120)}")
                    return
                }
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json == null) {
                    callback.onFailure("bad response")
                    return
                }
                callback.onSuccess(json)
            }
        })
    }

    private fun handle(msg: JSONObject) {
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

    private fun apiUrl(action: String): String {
        return httpUrlFor("api/signal/$action", signalingUrl).toHttpUrl().newBuilder()
            .addQueryParameter("roomId", roomId.ifBlank { "default" })
            .build()
            .toString()
    }

    private fun isCurrent(requestGeneration: Int): Boolean {
        return !closedByUser && requestGeneration == generation
    }

    private fun log(message: String) {
        Log.d(LOG_TAG, message)
        main.post { onDebugLog(message) }
    }

    private interface JsonCallback {
        fun onSuccess(json: JSONObject)
        fun onFailure(message: String)
    }

    companion object {
        const val SIGNALING_HOST = "vidly-signal.iceui2016.workers.dev"
        const val SIGNALING_URL = "wss://$SIGNALING_HOST/signal"
        private const val LOG_TAG = "VidlySignalHTTP"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val REQUEST_COUNTER = AtomicLong(0)
        private const val PREFS_NAME = "vidly_prefs"
        private const val KEY_CLIENT_ID = "client_id"
        private const val INITIAL_RECONNECT_MS = 1_000L
        private const val MAX_RECONNECT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 400L

        private fun getOrCreateClientId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(KEY_CLIENT_ID, null)?.let { return it }
            val newId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_CLIENT_ID, newId).apply()
            return newId
        }

        fun httpUrlFor(path: String, signalingUrl: String = SIGNALING_URL): String {
            val base = signalingUrl
                .substringBefore("?")
                .removeSuffix("/signal")
                .removeSuffix("/")
                .replaceFirst("wss://", "https://")
                .replaceFirst("ws://", "http://")
            return "$base/${path.trimStart('/')}"
        }

        private fun nextRequestId(): String {
            return "${System.currentTimeMillis()}-${REQUEST_COUNTER.incrementAndGet()}"
        }
    }
}
