package org.raycc.vidly.native

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.app.PictureInPictureParams
import android.content.ContentProvider
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.text.SpannableString
import android.text.Spanned
import android.text.InputType
import android.text.TextUtils
import android.util.Rational
import android.util.Patterns
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import android.text.style.URLSpan
import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.raycc.vidly.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class NativeCallActivity : Activity(),
    NativeSignalingClient.Listener,
    NativeWebRtcClient.DataListener {

    // Root layout
    private lateinit var root: FrameLayout
    private lateinit var callStack: LinearLayout
    private lateinit var videoContainer: FrameLayout
    private lateinit var bottomSpacer: View
    private lateinit var joinPanel: LinearLayout
    private lateinit var roomInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var previewDock: LinearLayout
    private lateinit var localPreviewContainer: LinearLayout
    private lateinit var remoteCameraContainer: FrameLayout
    private lateinit var localRenderer: SurfaceViewRenderer
    private lateinit var remoteRenderer: SurfaceViewRenderer
    private lateinit var cameraRenderer: SurfaceViewRenderer

    // Header
    private lateinit var headerBar: LinearLayout
    private lateinit var roomLabel: TextView
    private lateinit var headerStatusLabel: TextView
    private lateinit var participantsScroll: HorizontalScrollView
    private lateinit var participantsRow: LinearLayout
    private var debugLogText: TextView? = null
    private var debugOverlayVisible = false

    // Bottom container (controls + chat)
    private lateinit var bottomContainer: LinearLayout
    private lateinit var controlsRow: LinearLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var chatMessagesContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatList: LinearLayout
    private lateinit var chatInputRow: LinearLayout
    private lateinit var chatInput: EditText
    private lateinit var chatToggleBar: TextView
    private var chatExpanded = false

    // Reaction popup (small panel above chat input when reaction button tapped)
    private lateinit var reactionPicker: LinearLayout

    // Preview controls (below local renderer)
    private lateinit var previewControls: LinearLayout
    private var beautySwitch: Switch? = null

    // Bottom buttons
    private var micButton: Button? = null
    private var cameraButton: Button? = null
    private var speakerButton: Button? = null
    private var screenButton: Button? = null
    private var hangupButton: Button? = null

    // Screen share
    private var screenSharing = false
    // Bound connection to NativeCallService so the Activity can synchronously
    // promote the FGS to mediaProjection type BEFORE obtaining the projection
    // token (Android 14 enforces this ordering, else SecurityException).
    private var boundService: NativeCallService? = null
    private var serviceBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {
            boundService = (service as? NativeCallService.LocalBinder)?.getService()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            boundService = null
        }
    }
    private val mediaProjectionManager: MediaProjectionManager? by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
    }

    // PiP / proximity
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var inPip = false
    private var fullscreen = false

    // State
    private val signaling by lazy {
        NativeSignalingClient(this, this, onDebugLog = { msg ->
            runOnUiThread {
                val tv = debugLogText ?: return@runOnUiThread
                // Skip high-frequency poll logs to avoid flooding
                if (msg.startsWith("poll ") && !msg.contains("failure") && !msg.contains("error")) return@runOnUiThread
                if (msg.startsWith("poll response") && msg.contains("messages=0,")) return@runOnUiThread
                val text = tv.text.toString()
                val lines = text.split("\n")
                val keep = lines.takeLast(40).toMutableList()
                keep.add(msg)
                tv.text = keep.joinToString("\n")
                tv.scrollTo(0, tv.height)
            }
        })
    }
    private val http = OkHttpClient()
    private var rtc: NativeWebRtcClient? = null
    private var micEnabled = false
    private var cameraEnabled = false
    private var inPreview = false
    private var callActive = false
    private var userRequestedLeave = false
    private var currentRoom = ""
    private var currentUsername = ""
    private var loadingHistory = false
    private var lastChatTs = 0L
    private var hasRemoteVideo = false
    private var hasRemoteCamera = false
    private var headerStatus = "Native Vidly"
    private var videoStalled = false
    private var lastIceRestartAtMs = 0L
    private val connectedPeerIds = HashSet<String>()
    private val frameHealthHandler = Handler(Looper.getMainLooper())
    private val frameHealthRunnable = object : Runnable {
        override fun run() {
            checkFrameHealth()
            if (callActive) frameHealthHandler.postDelayed(this, FRAME_HEALTH_CHECK_MS)
        }
    }
    private var normalVideoHeight = 0
    private var previewDragStartRawX = 0f
    private var previewDragStartRawY = 0f
    private var previewDragStartX = 0f
    private var previewDragStartY = 0f
    private var previewDragging = false
    private var localRendererAttached = false
    private lateinit var previewScaleDetector: ScaleGestureDetector
    private var previewScaleStartWidth = 0
    private var previewTileWidth = 0
    private var previewTileHeight = 0
    private var speakerphoneEnabled = true
    private var audioRoutingConfigured = false
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneOn = false
    private var preferSpeakerphoneWhenNoExternal = true
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            runOnUiThread { refreshCallAudioRouteForDevices() }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            runOnUiThread { refreshCallAudioRouteForDevices() }
        }
    }

    // Participants: peerId -> username
    private val participants = LinkedHashMap<String, String>()

    // Peer mic/cam status, keyed by peerId.
    private data class PeerMediaState(val mic: Boolean, val cam: Boolean, val screen: Boolean = false)
    private val peerMediaStates = HashMap<String, PeerMediaState>()

    // File transfer state
    private data class OutgoingFile(
        val id: String,
        val name: String,
        val type: String,
        val size: Long,
        val bytes: ByteArray,
        val targetPeerIds: MutableSet<String>,
        val acceptedBy: MutableSet<String> = mutableSetOf(),
        val completedBy: MutableSet<String> = mutableSetOf()
    )
    private data class IncomingFile(
        val id: String,
        val name: String,
        val type: String,
        val size: Long,
        val fromPeerId: String,
        var fromUsername: String?,
        val chunks: MutableList<ByteArray> = mutableListOf(),
        var received: Long = 0,
        var lastProgressSent: Long = 0,
        val startedAt: Long = SystemClock.elapsedRealtime()
    )
    private val outgoingFiles = HashMap<String, OutgoingFile>()
    private val incomingFiles = HashMap<String, IncomingFile>()
    private val activeIncomingByPeer = HashMap<String, String>()

    // Top inset (status bar + display cutout) applied to header bar.
    private var topInset = 0
    private var keyboardVisible = false
    private var keyboardInsetBottom = 0
    private var rootFullHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Allow content to extend into display cutout, then apply insets manually
        // so the header bar (and status text) clear notches/punch-holes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        NativeCallService.ensureChannel(this)
        NativeIncomingCallListener.ensureChannels(this)
        buildUi()
        applyWindowInsets()
        checkForAppUpdate()

        savedInstanceState?.let {
            currentRoom = it.getString(STATE_ROOM, "")
            currentUsername = it.getString(STATE_USERNAME, "")
            roomInput.setText(currentRoom)
            usernameInput.setText(currentUsername)
        }

        seedJoinFieldsFromIntent()
        setupProximitySensor()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_ROOM, currentRoom)
        outState.putString(STATE_USERNAME, currentUsername)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!callActive) seedJoinFieldsFromIntent()
    }

    override fun onStart() {
        super.onStart()
        NativeIncomingCallListener.setAppForeground(this, true)
    }

    override fun onStop() {
        NativeIncomingCallListener.setAppForeground(this, false)
        super.onStop()
    }

    override fun onDestroy() {
        if (userRequestedLeave || !callActive) {
            signaling.leave()
        }
        signaling.dispose()
        rtc?.dispose()
        rtc = null
        resetCallAudioRouting()
        if (screenSharing) {
            screenSharing = false
        }
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
            boundService = null
        }
        if (callActive) {
            NativeCallService.stop(this)
            callActive = false
        }
        updateProximitySensorState()
        http.dispatcher.executorService.shutdown()
        super.onDestroy()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val hasVideo = cameraEnabled || hasRemoteVideo
        if (callActive && hasVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
                enterPictureInPictureMode(params)
            } catch (_: Throwable) {}
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        val visibility = if (isInPictureInPictureMode || fullscreen) View.GONE else View.VISIBLE
        headerBar.visibility = visibility
        bottomContainer.visibility = visibility
        chatMessagesContainer.visibility = visibility
        updatePreviewDockVisibility()
        if (isInPictureInPictureMode) {
            previewControls.visibility = View.GONE
            reactionPicker.visibility = View.GONE
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST && !hasMediaPermissions()) {
            updateHeaderStatus("Camera and microphone permissions are required.")
            micEnabled = false
            cameraEnabled = false
            updateMediaButtons()
        }
    }

    // ─── Signaling listener callbacks ─────────────────────────

    override fun onJoined(roomId: String, peers: List<NativeSignalingClient.PeerInfo>) {
        rtc?.onJoined(peers)
        participants.clear()
        peers.forEach { participants[it.peerId] = it.username ?: "Peer" }
        refreshParticipants()
        setCallActive(true)
    }

    override fun onPeerJoined(peer: NativeSignalingClient.PeerInfo) {
        rtc?.onPeerJoined(peer)
        participants[peer.peerId] = peer.username ?: "Peer"
        refreshParticipants()
    }

    override fun onPeerLeft(peerId: String, username: String?) {
        rtc?.onPeerLeft(peerId)
        participants.remove(peerId)
        peerMediaStates.remove(peerId)
        connectedPeerIds.remove(peerId)
        if (participants.isEmpty()) {
            hasRemoteVideo = false
            hasRemoteCamera = false
            updatePreviewDockVisibility()
        }
        refreshParticipants()
        updateProximitySensorState()
        updateHeaderStatus("${username ?: "Peer"} left")
    }

    override fun onSignal(from: String, payload: JSONObject) {
        rtc?.onSignal(from, payload)
    }

    override fun onRoomFull() {
        updateHeaderStatus("Room is full.")
        showJoinPanel()
    }

    override fun onUsernameTaken(username: String) {
        updateHeaderStatus("Name \"$username\" is already taken.")
        showJoinPanel()
    }

    override fun onStatus(message: String) {
        updateHeaderStatus(message)
    }

    // ─── DataChannel callbacks ────────────────────────────────

    override fun onChatMessage(fromPeerId: String, fromUsername: String?, msg: JSONObject) {
        runOnUiThread {
            val senderName = fromUsername ?: "Peer"
            when (msg.optString("type")) {
                "chat" -> {
                    val body = msg.optString("body")
                    val ts = msg.optLong("ts")
                    appendChatText(senderName, body, incoming = true, ts = ts)
                    saveChatMsg(JSONObject()
                        .put("type", "chat").put("kind", "other")
                        .put("body", body).put("ts", ts)
                        .put("senderName", senderName))
                }
                "chat-edit" -> {
                    val originalTs = msg.optLong("originalTs")
                    val body = msg.optString("body")
                    applyChatEdit(originalTs, body)
                }
                "reaction" -> {
                    val emoji = msg.optString("emoji")
                    if (emoji.isNotBlank()) {
                        val ts = msg.optLong("ts")
                        appendChatText(senderName, emoji, incoming = true, ts = ts)
                        saveChatMsg(JSONObject()
                            .put("type", "reaction").put("kind", "other")
                            .put("emoji", emoji).put("ts", ts)
                            .put("senderName", senderName))
                    }
                }
                "media" -> {
                    val url = msg.optString("url")
                    val mediaKind = msg.optString("kind")
                    appendChatMedia(senderName, url, mediaKind, incoming = true, ts = msg.optLong("ts"))
                    saveChatMsg(JSONObject()
                        .put("type", "media").put("kind", "other")
                        .put("url", url).put("mediaKind", mediaKind)
                        .put("ts", msg.optLong("ts"))
                        .put("senderName", senderName))
                }
                "media-state" -> {
                    val mic = msg.optBoolean("mic", false)
                    val cam = msg.optBoolean("cam", false)
                    val screen = msg.optBoolean("screen", false)
                    peerMediaStates[fromPeerId] = PeerMediaState(mic, cam, screen)
                    refreshParticipants()
                    updateProximitySensorState()
                }
                "file-offer" -> {
                    val file = msg.optJSONObject("file") ?: return@runOnUiThread
                    handleIncomingFileOffer(fromPeerId, fromUsername, file)
                }
                "file-accept" -> handleFileAccept(fromPeerId, msg.optString("id"))
                "file-reject" -> handleFileReject(fromPeerId, msg.optString("id"))
                "file-progress" -> Unit
                "file-complete" -> handleFileComplete(fromPeerId, msg.optString("id"))
                "file-empty" -> handleFileEmpty(fromPeerId, msg.optString("id"))
            }
        }
    }

    override fun onFileBinary(fromPeerId: String, bytes: ByteArray) {
        runOnUiThread {
            val fileId = activeIncomingByPeer[fromPeerId] ?: return@runOnUiThread
            val transfer = incomingFiles[fileId] ?: return@runOnUiThread
            transfer.chunks.add(bytes)
            transfer.received += bytes.size
            val now = System.currentTimeMillis()
            if (now - transfer.lastProgressSent > 250 || transfer.received >= transfer.size) {
                transfer.lastProgressSent = now
                sendChatJsonTo(fromPeerId, JSONObject()
                    .put("type", "file-progress")
                    .put("id", fileId)
                    .put("received", transfer.received)
                )
            }
            if (transfer.received >= transfer.size) {
                completeIncomingFile(transfer)
            }
        }
    }

    override fun onPeerConnectionStateChanged(peerId: String, connected: Boolean) {
        runOnUiThread {
            if (connected) connectedPeerIds.add(peerId) else connectedPeerIds.remove(peerId)
            checkFrameHealth()
        }
    }

    // ─── UI construction ──────────────────────────────────────

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        callStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        root.addView(callStack, FrameLayout.LayoutParams(-1, -1))

        // Debug log overlay (hidden by default, toggle with long-press on status)
        debugLogText = TextView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.argb(200, 11, 17, 32))
            setTextColor(Color.rgb(180, 200, 220))
            textSize = 9f
            typeface = android.graphics.Typeface.MONOSPACE
            isClickable = false
            isFocusable = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { visibility = View.GONE; debugOverlayVisible = false }
        }
        root.addView(debugLogText, FrameLayout.LayoutParams(-1, -1).apply {
            gravity = Gravity.BOTTOM
            bottomMargin = dp(48)  // above bottom controls
        })

        buildHeaderBar()

        normalVideoHeight = resources.displayMetrics.widthPixels * 9 / 16
        videoContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        callStack.addView(videoContainer, LinearLayout.LayoutParams(-1, normalVideoHeight))

        // Remote video stays inside the dedicated video area and always shows the full frame.
        remoteRenderer = SurfaceViewRenderer(this).apply {
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            val detector = GestureDetector(this@NativeCallActivity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    toggleFullscreen()
                    return true
                }
            })
            setOnTouchListener { _, ev ->
                detector.onTouchEvent(ev)
                true
            }
        }
        videoContainer.addView(remoteRenderer, FrameLayout.LayoutParams(-1, -1))

        val screenWidthPx = resources.displayMetrics.widthPixels
        previewTileWidth = ((screenWidthPx - dp(36)) / 2).coerceAtLeast(dp(112))
        previewTileHeight = (previewTileWidth * 160f / 112f).toInt()
        val minTilePx = dp(96)
        val maxTilePx = ((screenWidthPx - dp(20)) / 2).coerceAtLeast(previewTileWidth + dp(40))
        previewScaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                previewScaleStartWidth = previewTileWidth
                previewDragging = false
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newWidth = (previewScaleStartWidth * detector.scaleFactor).toInt()
                    .coerceIn(minTilePx, maxTilePx)
                setPreviewTileSize(newWidth)
                keepLocalPreviewInBounds()
                return true
            }
        })

        // Preview dock floats above the whole call UI so its confirmation controls are not
        // clipped by the fixed 16:9 video area. It contains both camera tiles and moves/resizes
        // them as one unit while preserving the original portrait camera-preview aspect ratio.
        previewDock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setOnTouchListener { _, ev -> handleLocalPreviewDrag(ev) }
        }
        root.addView(previewDock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(14)
            marginEnd = dp(14)
        })

        localPreviewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setOnTouchListener { _, ev -> handleLocalPreviewDrag(ev) }
        }
        previewDock.addView(localPreviewContainer, LinearLayout.LayoutParams(previewTileWidth, -2))

        // Local renderer (top-right floating preview tile)
        localRenderer = SurfaceViewRenderer(this).apply {
            setZOrderMediaOverlay(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            visibility = View.GONE
            setOnTouchListener { _, ev -> handleLocalPreviewDrag(ev) }
        }
        attachLocalRenderer()

        remoteCameraContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            setOnTouchListener { _, ev -> handleLocalPreviewDrag(ev) }
        }
        previewDock.addView(remoteCameraContainer, LinearLayout.LayoutParams(previewTileWidth, previewTileHeight).apply {
            marginStart = dp(8)
        })
        cameraRenderer = SurfaceViewRenderer(this).apply {
            setZOrderMediaOverlay(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setOnTouchListener { _, ev -> handleLocalPreviewDrag(ev) }
        }
        remoteCameraContainer.addView(cameraRenderer, FrameLayout.LayoutParams(-1, -1))

        // Preview controls (below local renderer, only visible during preview)
        previewControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.rgb(15, 17, 23))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        localPreviewContainer.addView(previewControls, LinearLayout.LayoutParams(previewTileWidth, -2).apply {
            topMargin = dp(6)
        })
        buildPreviewControls()

        bottomSpacer = View(this).apply { setBackgroundColor(Color.BLACK) }
        callStack.addView(bottomSpacer, LinearLayout.LayoutParams(-1, 0, 1f))

        buildBottomContainer()
        buildReactionPicker()
        buildJoinPanel()
    }

    private fun buildHeaderBar() {
        headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(15, 17, 23))
            setPadding(dp(14), dp(12), dp(14), dp(8))
            visibility = View.GONE
        }
        callStack.addView(headerBar, LinearLayout.LayoutParams(-1, -2))

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerBar.addView(row, LinearLayout.LayoutParams(-1, -2))

        roomLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = "Room: —"
            isClickable = true
            isLongClickable = true
            val detector = GestureDetector(this@NativeCallActivity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    shareRoomLink()
                    Toast.makeText(this@NativeCallActivity, "Sharing room link", Toast.LENGTH_SHORT).show()
                    return true
                }
            })
            setOnTouchListener { _, ev ->
                detector.onTouchEvent(ev)
                false
            }
            setOnLongClickListener {
                copyRoomLink()
                true
            }
        }
        row.addView(roomLabel, LinearLayout.LayoutParams(0, -2, 1f))

        val separator = TextView(this).apply {
            text = " | "
            textSize = 14f
            setTextColor(Color.rgb(145, 152, 166))
        }
        row.addView(separator, LinearLayout.LayoutParams(-2, -2))

        headerStatusLabel = TextView(this).apply {
            setTextColor(Color.rgb(205, 211, 224))
            textSize = 13f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = headerStatus
            setOnLongClickListener {
                debugOverlayVisible = !debugOverlayVisible
                debugLogText?.visibility = if (debugOverlayVisible) View.VISIBLE else View.GONE
                true
            }
        }
        row.addView(headerStatusLabel, LinearLayout.LayoutParams(0, -2, 1f))

        participantsScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        participantsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        participantsScroll.addView(participantsRow, ViewGroup.LayoutParams(-2, -2))
        headerBar.addView(participantsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    private fun buildPreviewControls() {
        val confirmCancelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        previewControls.addView(confirmCancelRow, LinearLayout.LayoutParams(-1, -2))

        val confirmBtn = compactIconButton("✓", Color.WHITE, Color.rgb(45, 164, 78)).apply {
            setOnClickListener { confirmPreview() }
        }
        confirmCancelRow.addView(confirmBtn, LinearLayout.LayoutParams(0, dp(36), 1f))

        val cancelBtn = compactIconButton("✕", Color.WHITE, Color.rgb(58, 63, 77)).apply {
            setOnClickListener { cancelPreview() }
        }
        confirmCancelRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) })

        val flipBtn = compactIconButton("🔄", Color.WHITE, Color.argb(80, 255, 255, 255)).apply {
            setOnClickListener {
                if (!cameraEnabled && !inPreview) {
                    Toast.makeText(this@NativeCallActivity, "Camera is off", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                rtc?.switchCamera()
            }
        }
        confirmCancelRow.addView(flipBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) })

        val beautyBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewControls.addView(beautyBlock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val beautyToggle = Switch(this).apply {
            text = "Beauty"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
                rtc?.setBeautyEnabled(enabled)
                rtc?.setBeautyIntensity(if (enabled) 1.0f else 0f)
            }
        }
        beautySwitch = beautyToggle
        beautyBlock.addView(beautyToggle, LinearLayout.LayoutParams(-2, -2))
    }

    private fun roundedDrawable(color: Int, radiusDp: Int = 10): GradientDrawable {
        return GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
        }
    }

    private fun compactIconButton(label: String, textColor: Int, bgColor: Int): Button {
        return Button(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 14f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = roundedDrawable(bgColor)
            stateListAnimator = null
        }
    }

    private fun buildBottomContainer() {
        bottomContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Fully opaque: translucent backgrounds over SurfaceView cause
            // hole-punch flicker when child views relayout.
            setBackgroundColor(Color.rgb(12, 14, 20))
            visibility = View.GONE
        }
        callStack.addView(bottomContainer, LinearLayout.LayoutParams(-1, -2))

        // 1) Chat messages area (collapsible — toggle bar at top to expand/collapse).
        // It is a root overlay rather than a child of bottomContainer so it can be
        // anchored above the IME without moving or being clipped by the controls row.
        chatMessagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
            setBackgroundColor(Color.rgb(15, 17, 23))
            setPadding(dp(4), dp(0), dp(4), dp(2))
        }
        root.addView(chatMessagesContainer, FrameLayout.LayoutParams(-1, dp(130), Gravity.BOTTOM))

        // Toggle bar at top — click to expand/collapse
        chatToggleBar = TextView(this).apply {
            text = "—  Chat  —"
            textSize = 11f
            setTextColor(Color.rgb(140, 146, 160))
            gravity = Gravity.CENTER
            setPadding(dp(0), dp(4), dp(0), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleChatExpanded() }
        }
        chatMessagesContainer.addView(chatToggleBar, LinearLayout.LayoutParams(-1, -2))

        chatScroll = ScrollView(this)
        chatList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatScroll.addView(chatList)
        chatMessagesContainer.addView(chatScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // 2) Main controls (mic / cam / speaker / hangup) — compact emoji buttons.
        controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        bottomContainer.addView(controlsRow, LinearLayout.LayoutParams(-1, -2))

        val ctrlW = dp(44)
        val ctrlH = dp(40)
        val ctrlGap = dp(10)

        val mic = compactIconButton("🎙️", Color.WHITE, Color.rgb(42, 45, 53)).apply {
            setOnClickListener {
                if (!micEnabled && !ensureMediaPermissions()) return@setOnClickListener
                micEnabled = !micEnabled
                rtc?.setMicEnabled(micEnabled)
                updateMediaButtons()
                refreshParticipants()
            }
        }
        micButton = mic
        controlsRow.addView(mic, LinearLayout.LayoutParams(ctrlW, ctrlH))

        val cam = compactIconButton("📷", Color.WHITE, Color.rgb(42, 45, 53)).apply {
            setOnClickListener {
                if (inPreview) return@setOnClickListener
                if (!ensureMediaPermissions()) return@setOnClickListener
                if (!cameraEnabled) {
                    startPreview()
                } else {
                    cameraEnabled = false
                    rtc?.setCameraEnabled(false)
                    hideLocalRenderer()
                    updateMediaButtons()
                    refreshParticipants()
                }
            }
        }
        cameraButton = cam
        controlsRow.addView(cam, LinearLayout.LayoutParams(ctrlW, ctrlH).apply { marginStart = ctrlGap })

        val screen = compactIconButton("🖥️", Color.WHITE, Color.rgb(42, 45, 53)).apply {
            setOnClickListener {
                if (screenSharing) {
                    stopScreenShare()
                } else {
                    startScreenShareConsent()
                }
            }
        }
        screenButton = screen
        controlsRow.addView(screen, LinearLayout.LayoutParams(ctrlW, ctrlH).apply { marginStart = ctrlGap })

        val speaker = compactIconButton("🔈", Color.WHITE, Color.rgb(42, 45, 53)).apply {
            setOnClickListener {
                if (!canToggleAudioRoute()) {
                    Toast.makeText(this@NativeCallActivity, "Only one audio route available", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                speakerphoneEnabled = !speakerphoneEnabled
                preferSpeakerphoneWhenNoExternal = speakerphoneEnabled
                applyAudioRoute()
                updateAudioRouteButton()
            }
        }
        speakerButton = speaker
        controlsRow.addView(speaker, LinearLayout.LayoutParams(ctrlW, ctrlH).apply { marginStart = ctrlGap })

        val hangup = compactIconButton("📞", Color.WHITE, Color.rgb(177, 60, 60)).apply {
            setOnClickListener { leaveCall() }
        }
        hangupButton = hangup
        controlsRow.addView(hangup, LinearLayout.LayoutParams(ctrlW, ctrlH).apply { marginStart = ctrlGap })

        // 4) Chat input row (inside chatMessagesContainer, hidden when collapsed)
        chatInputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(22, 24, 30))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        chatMessagesContainer.addView(chatInputRow, LinearLayout.LayoutParams(-1, -2))

        val fileBtn = TextView(this).apply {
            text = "📎"
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener { promptFileSend() }
        }
        chatInputRow.addView(fileBtn, LinearLayout.LayoutParams(dp(30), dp(30)))

        val gifBtn = TextView(this).apply {
            text = "GIF"
            textSize = 12f
            gravity = Gravity.CENTER
            setOnClickListener { showGiphyPicker() }
        }
        chatInputRow.addView(gifBtn, LinearLayout.LayoutParams(dp(30), dp(30)))

        val reactBtn = TextView(this).apply {
            text = "😊"
            textSize = 16f
            gravity = Gravity.CENTER
            setOnClickListener { toggleReactionPicker() }
        }
        chatInputRow.addView(reactBtn, LinearLayout.LayoutParams(dp(30), dp(30)))

        chatInput = EditText(this).apply {
            hint = "Type a message"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(170, 176, 190))
            setBackgroundColor(Color.rgb(50, 53, 65))
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && !chatExpanded) {
                    clearFocus()
                    hideKeyboard()
                } else if (hasFocus) {
                    refreshKeyboardLayoutSoon()
                }
            }
            setOnClickListener { if (chatExpanded) refreshKeyboardLayoutSoon() }
        }
        chatInputRow.addView(chatInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })

        val sendBtn = TextView(this).apply {
            text = "Send"
            textSize = 14f
            setOnClickListener { sendChatFromInput() }
        }
        chatInputRow.addView(sendBtn, LinearLayout.LayoutParams(-2, dp(42)).apply { marginStart = dp(4) })
    }

    private fun buildReactionPicker() {
        reactionPicker = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
            setBackgroundColor(Color.argb(235, 30, 32, 42))
            setPadding(dp(4), dp(2), dp(4), dp(2))
            gravity = Gravity.CENTER
        }
        chatMessagesContainer.addView(reactionPicker, 0)
        for (emoji in REACTIONS) {
            val btn = TextView(this).apply {
                text = emoji
                textSize = 18f
                setOnClickListener {
                    sendReaction(emoji)
                    reactionPicker.visibility = View.GONE
                }
            }
            reactionPicker.addView(btn, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginStart = dp(2) })
        }
    }

    private fun buildJoinPanel() {
        joinPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.BLACK)
        }
        root.addView(joinPanel, FrameLayout.LayoutParams(-1, -1))

        val title = TextView(this).apply {
            text = "Join Native Vidly"
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        joinPanel.addView(title, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })

        usernameInput = EditText(this).apply {
            hint = "Your name"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(170, 176, 190))
            setBackgroundColor(Color.rgb(30, 32, 42))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        joinPanel.addView(usernameInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        roomInput = EditText(this).apply {
            hint = "Room code"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(170, 176, 190))
            setBackgroundColor(Color.rgb(30, 32, 42))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        joinPanel.addView(roomInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(18) })

        val join = Button(this).apply {
            text = "Join"
            setOnClickListener { joinRoom() }
        }
        joinPanel.addView(join, LinearLayout.LayoutParams(-1, dp(48)))

        // Pre-fill saved username and room from SharedPreferences
        val prefs = getSharedPreferences("vidly_prefs", MODE_PRIVATE)
        val savedUsername = prefs.getString("username", "")
        val savedRoom = prefs.getString("room", "")
        if (!savedUsername.isNullOrBlank()) usernameInput.setText(savedUsername)
        if (!savedRoom.isNullOrBlank()) roomInput.setText(savedRoom)

        // Version label at bottom-right
        val versionLabel = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            textSize = 11f
            setTextColor(Color.rgb(100, 105, 120))
            gravity = Gravity.END
        }
        // Spacer to push version label to bottom
        joinPanel.addView(View(this), LinearLayout.LayoutParams(-1, 0).apply { weight = 1f })
        joinPanel.addView(versionLabel, LinearLayout.LayoutParams(-1, -2))
    }

    // ─── Actions ──────────────────────────────────────────────

    private fun joinRoom() {
        val username = usernameInput.text.toString().trim().take(32)
        val room = roomInput.text.toString().trim().lowercase().replace(Regex("[^a-z0-9_-]"), "")
        if (username.isBlank() || room.isBlank()) {
            updateHeaderStatus("Enter a name and room code.")
            return
        }

        currentRoom = room
        currentUsername = username
        userRequestedLeave = false

        // Save to SharedPreferences
        getSharedPreferences("vidly_prefs", MODE_PRIVATE).edit().apply {
            putString("username", username)
            putString("room", room)
            apply()
        }

        joinPanel.visibility = View.GONE
        headerBar.visibility = View.VISIBLE
        bottomContainer.visibility = View.VISIBLE
        bottomSpacer.visibility = View.VISIBLE
        chatMessagesContainer.visibility = View.VISIBLE
        roomLabel.text = "Room: $room"
        updateHeaderStatus("Connecting...")
        participants.clear()
        peerMediaStates.clear()
        connectedPeerIds.clear()
        hasRemoteVideo = false
        hasRemoteCamera = false
        videoStalled = false
        lastIceRestartAtMs = 0L
        micEnabled = false
        cameraEnabled = false
        inPreview = false
        fullscreen = false
        screenSharing = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        updateVideoContainerLayout()
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        previewControls.visibility = View.GONE
        hideLocalRenderer()
        resetLocalPreviewPosition()
        refreshParticipants()
        chatList.removeAllViews()
        lastChatTs = 0L
        loadChatHistory()
        outgoingFiles.clear()
        incomingFiles.clear()
        activeIncomingByPeer.clear()
        // Initial collapsed state
        chatExpanded = false
        chatInputRow.visibility = View.GONE
        chatToggleBar.text = "+  Chat  +"
        chatMessagesContainer.translationY = 0f
        updateChatContainerHeight()
        configureCallAudioRouting(defaultSpeakerphone = true)
        updateMediaButtons()

        rtc?.dispose()

        // Fetch TURN credentials synchronously BEFORE creating PeerConnections.
        // The old async loadTurnConfig raced with signaling.connect() — peers
        // arrived while iceServers still had only STUN, causing ICE failed.
        Thread {
            val turnServers = fetchTurnServersSync()
            runOnUiThread {
                rtc = NativeWebRtcClient(
                    this,
                    localRenderer,
                    remoteRenderer,
                    signaling,
                    { msg -> runOnUiThread { updateHeaderStatus(msg) } },
                    { active -> runOnUiThread { setRemoteVideoActive(active) } },
                    cameraRenderer,
                    { active -> runOnUiThread { setRemoteCameraActive(active) } },
                    // Fires once WebRTC's AudioTrack actually starts playing — the
                    // deterministic moment to pin the output device. Replaces the
                    // old 300/800/1500ms fixed-delay retries that raced with it.
                    { runOnUiThread { applyAudioRoute() } },
                    // Fired when screen sharing is stopped externally (system
                    // "Stop sharing" notification or MediaProjection revocation).
                    // Already on the main thread; reset UI + demote the FGS.
                    {
                        screenSharing = false
                        boundService?.demoteToDataSync()
                        updateMediaButtons()
                        updateProximitySensorState()
                    }
                ).also {
                    it.dataListener = this
                    for (ts in turnServers) it.addTurnServer(ts.urls, ts.username, ts.credential)
                    it.start()
                }
                signaling.connect(room, username)
            }
        }.start()
    }

    private fun leaveCall() {
        stopFrameHealthMonitor()
        userRequestedLeave = true
        signaling.leave()
        rtc?.close()
        screenSharing = false
        resetCallAudioRouting()
        hasRemoteVideo = false
        hasRemoteCamera = false
        connectedPeerIds.clear()
        showJoinPanel()
        setCallActive(false)
    }

    private fun startPreview() {
        inPreview = true
        // Mic is independent of camera preview — don't touch it here. The user
        // toggles mic via its own button. Starting the camera never enables the
        // mic, and canceling the camera never disables it.
        rtc?.startCameraPreview()
        showLocalPreview()
        previewControls.visibility = View.VISIBLE
        cameraButton?.text = "Preview..."
        updateMediaButtons()
    }

    private fun confirmPreview() {
        inPreview = false
        rtc?.confirmCameraPreview()
        cameraEnabled = true
        showLocalPreview()
        previewControls.visibility = View.GONE
        updateMediaButtons()
        refreshParticipants()
        val msg = if (micEnabled) "Camera on" else "Camera on (mic is off)"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun cancelPreview() {
        inPreview = false
        rtc?.cancelCameraPreview()
        hideLocalRenderer()
        cameraEnabled = false
        // Mic is independent of camera preview — don't restore/disable it here.
        previewControls.visibility = View.GONE
        updateMediaButtons()
        refreshParticipants()
    }

    // ─── Screen share ─────────────────────────────────────────

    /** Kick off the system MediaProjection consent dialog. */
    private fun startScreenShareConsent() {
        if (!callActive) {
            Toast.makeText(this, "Join a call first", Toast.LENGTH_SHORT).show()
            return
        }
        val mpm = mediaProjectionManager
        if (mpm == null) {
            Toast.makeText(this, "Screen capture not available", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST)
        } catch (_: Throwable) {
            Toast.makeText(this, "Screen capture not available", Toast.LENGTH_SHORT).show()
        }
    }

    /** Stop sharing: tears down capture + senders, demotes the FGS. */
    private fun stopScreenShare() {
        rtc?.setScreenEnabled(false)
        screenSharing = false
        boundService?.demoteToDataSync()
        updateMediaButtons()
        updateProximitySensorState()
    }

    private fun toggleChatExpanded() {
        chatExpanded = !chatExpanded
        if (chatExpanded) {
            chatInputRow.visibility = View.VISIBLE
            chatToggleBar.text = "—  Chat  —"
        } else {
            chatInputRow.visibility = View.GONE
            chatToggleBar.text = "+  Chat  +"
            chatInput.clearFocus()
            hideKeyboard()
        }
        applyKeyboardAwareChatLayout()
        if (chatExpanded) {
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun toggleReactionPicker() {
        reactionPicker.visibility = if (reactionPicker.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun toggleFullscreen() {
        if (!fullscreen && !hasRemoteVideo) return
        fullscreen = !fullscreen
        val visibility = if (fullscreen) View.GONE else View.VISIBLE
        headerBar.visibility = visibility
        bottomContainer.visibility = visibility
        chatMessagesContainer.visibility = visibility
        bottomSpacer.visibility = visibility
        // Always GONE in fullscreen; otherwise only visible while previewing.
        previewControls.visibility = if (!fullscreen && inPreview) View.VISIBLE else View.GONE
        updatePreviewDockVisibility()
        reactionPicker.visibility = View.GONE
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        updateVideoContainerLayout()
        requestedOrientation = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    private fun applyWindowInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sys = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout())
                sys.top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            if (top != topInset) {
                topInset = top
                headerBar.setPadding(dp(14), topInset + dp(12), dp(14), dp(8))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                updateKeyboardState(insets.getInsets(WindowInsets.Type.ime()).bottom)
            }
            insets
        }
        root.requestApplyInsets()

        // Keyboard detection fallback: with adjustNothing, some devices do not dispatch
        // IME insets consistently. Keep the window non-fullscreen and compare the
        // visible display frame against the largest root height observed.
        root.viewTreeObserver.addOnGlobalLayoutListener {
            updateKeyboardState(currentKeyboardInsetBottom())
        }
    }

    private fun refreshKeyboardLayoutSoon() {
        root.requestApplyInsets()
        root.post { updateKeyboardState(currentKeyboardInsetBottom()) }
        root.postDelayed({ updateKeyboardState(currentKeyboardInsetBottom()) }, 120)
        root.postDelayed({ updateKeyboardState(currentKeyboardInsetBottom()) }, 300)
    }

    private fun currentKeyboardInsetBottom(): Int {
        val insetsInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        } else {
            0
        }

        val rootRect = Rect()
        root.getWindowVisibleDisplayFrame(rootRect)
        val decorRect = Rect()
        window.decorView.getWindowVisibleDisplayFrame(decorRect)
        val rootVisibleHeight = (rootRect.bottom - rootRect.top).coerceAtLeast(0)
        val decorVisibleHeight = (decorRect.bottom - decorRect.top).coerceAtLeast(0)
        val visibleHeight = listOf(rootVisibleHeight, decorVisibleHeight)
            .filter { it > 0 }
            .minOrNull() ?: 0
        rootFullHeight = maxOf(rootFullHeight, rootVisibleHeight, decorVisibleHeight)
        val visibleFrameInset = if (rootFullHeight > 0 && visibleHeight > 0) {
            (rootFullHeight - visibleHeight).coerceAtLeast(0)
        } else {
            0
        }

        return maxOf(insetsInset, visibleFrameInset)
    }

    private fun updateKeyboardState(imeBottom: Int) {
        val imeVisible = imeBottom > dp(100)
        val insetBottom = if (imeVisible) imeBottom else 0
        if (imeVisible == keyboardVisible && kotlin.math.abs(insetBottom - keyboardInsetBottom) <= dp(10)) return
        keyboardVisible = imeVisible
        keyboardInsetBottom = insetBottom
        if (chatExpanded) applyKeyboardAwareChatLayout()
    }

    private fun updateHeaderStatus(message: String) {
        headerStatus = message
        if (::headerStatusLabel.isInitialized) {
            renderHeaderStatus()
        }
    }

    private fun setRemoteVideoActive(active: Boolean) {
        hasRemoteVideo = active
        if (!active) setVideoStalled(false)
        if (!active && fullscreen) {
            toggleFullscreen()
        }
        checkFrameHealth()
    }

    private fun setRemoteCameraActive(active: Boolean) {
        hasRemoteCamera = active
        updatePreviewDockVisibility()
    }

    private fun renderHeaderStatus() {
        headerStatusLabel.text = if (videoStalled) "Paused - poor network" else headerStatus
    }

    private fun setVideoStalled(stalled: Boolean) {
        if (videoStalled == stalled) return
        videoStalled = stalled
        if (::headerStatusLabel.isInitialized) renderHeaderStatus()
    }

    private fun startFrameHealthMonitor() {
        stopFrameHealthMonitor()
        frameHealthHandler.post(frameHealthRunnable)
    }

    private fun stopFrameHealthMonitor() {
        frameHealthHandler.removeCallbacks(frameHealthRunnable)
        setVideoStalled(false)
    }

    private fun checkFrameHealth() {
        if (!callActive) {
            setVideoStalled(false)
            return
        }
        // A static screencast legitimately produces no new frames, which would
        // trip the frame-age watchdog and cause a false ICE-restart loop. While
        // the remote is sharing screen, skip both the stall action and the
        // "stalled" overlay — the ICE observer (DISCONNECTED/FAILED) remains
        // the primary recovery path (see AGENTS.md).
        if (peerMediaStates.values.any { it.screen }) {
            setVideoStalled(false)
            return
        }
        val remoteConnectionAlive = connectedPeerIds.isNotEmpty()
        val frameAgeMs = rtc?.getRemoteVideoFrameAgeMs() ?: Long.MAX_VALUE
        val stalled = hasRemoteVideo && remoteConnectionAlive && frameAgeMs > FRAME_STALL_MS
        if (stalled && !videoStalled) {
            val now = SystemClock.elapsedRealtime()
            if (lastIceRestartAtMs == 0L || now - lastIceRestartAtMs >= ICE_RESTART_COOLDOWN_MS) {
                lastIceRestartAtMs = now
                rtc?.restartIce()
            }
        }
        setVideoStalled(stalled)
    }

    private fun localPreviewVisibility(): Int =
        if ((cameraEnabled || inPreview) && !inPip && !fullscreen) View.VISIBLE else View.GONE

    private fun remoteCameraVisibility(): Int =
        if (hasRemoteCamera && !inPip && !fullscreen) View.VISIBLE else View.GONE

    private fun updatePreviewDockVisibility() {
        if (!::previewDock.isInitialized) return
        val showDock = localPreviewVisibility() == View.VISIBLE || remoteCameraVisibility() == View.VISIBLE
        previewDock.visibility = if (showDock) View.VISIBLE else View.GONE
        localPreviewContainer.visibility = localPreviewVisibility()
        remoteCameraContainer.visibility = remoteCameraVisibility()
        if (showDock) keepLocalPreviewInBounds()
    }

    private fun showLocalPreview() {
        attachLocalRenderer()
        localRenderer.visibility = View.VISIBLE
        updatePreviewDockVisibility()
    }

    private fun hideLocalRenderer() {
        localRenderer.clearImage()
        localRenderer.visibility = View.GONE
        localPreviewContainer.visibility = View.GONE
        detachLocalRenderer()
        previewDragging = false
        updatePreviewDockVisibility()
    }

    private fun attachLocalRenderer() {
        if (localRendererAttached) return
        localPreviewContainer.addView(localRenderer, 0, LinearLayout.LayoutParams(previewTileWidth, previewTileHeight))
        localRendererAttached = true
    }

    private fun detachLocalRenderer() {
        if (!localRendererAttached) return
        localPreviewContainer.removeView(localRenderer)
        localRendererAttached = false
    }

    private fun resetLocalPreviewPosition() {
        if (!::previewDock.isInitialized) return
        previewDock.post {
            val params = previewDock.layoutParams as FrameLayout.LayoutParams
            params.gravity = Gravity.TOP or Gravity.END
            params.topMargin = videoContainer.top + dp(14)
            params.marginEnd = dp(14)
            params.leftMargin = 0
            previewDock.layoutParams = params
            previewDock.translationX = 0f
            previewDock.translationY = 0f
        }
    }

    private fun handleLocalPreviewDrag(ev: MotionEvent): Boolean {
        if (!::previewDock.isInitialized) return false
        previewScaleDetector.onTouchEvent(ev)
        if (ev.pointerCount > 1 || previewScaleDetector.isInProgress) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previewDragging = true
                previewDragStartRawX = ev.rawX
                previewDragStartRawY = ev.rawY
                // Anchor to the current VISUAL position (layout + any existing translation).
                previewDragStartX = previewDock.x
                previewDragStartY = previewDock.y
                previewDock.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!previewDragging) return false
                val parentWidth = root.width
                val parentHeight = root.height
                if (parentWidth <= 0 || parentHeight <= 0) return false

                val targetX = previewDragStartX + ev.rawX - previewDragStartRawX
                val targetY = previewDragStartY + ev.rawY - previewDragStartRawY

                // Clamp the whole dock inside the screen bounds.
                val clampedX = targetX.coerceIn(
                    0f,
                    (parentWidth - previewDock.width).coerceAtLeast(0).toFloat()
                )
                val clampedY = targetY.coerceIn(
                    0f,
                    (parentHeight - previewDock.height).coerceAtLeast(0).toFloat()
                )

                // translation = desired visual position - layout position (left/top).
                // This works regardless of current gravity or existing translation.
                previewDock.translationX = clampedX - previewDock.left
                previewDock.translationY = clampedY - previewDock.top
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!previewDragging) return false
                previewDragging = false
                previewDock.parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun keepLocalPreviewInBounds() {
        if (!::previewDock.isInitialized) return
        previewDock.post {
            if (previewDock.visibility != View.VISIBLE) return@post
            val parentWidth = root.width
            val parentHeight = root.height
            if (parentWidth <= 0 || parentHeight <= 0) return@post

            val currentX = previewDock.x
            val currentY = previewDock.y

            val clampedX = currentX.coerceIn(
                0f,
                (parentWidth - previewDock.width).coerceAtLeast(0).toFloat()
            )
            val clampedY = currentY.coerceIn(
                0f,
                (parentHeight - previewDock.height).coerceAtLeast(0).toFloat()
            )

            if (clampedX != currentX || clampedY != currentY) {
                previewDock.translationX = clampedX - previewDock.left
                previewDock.translationY = clampedY - previewDock.top
            }
        }
    }

    private fun setPreviewTileSize(width: Int) {
        previewTileWidth = width
        previewTileHeight = (previewTileWidth * 160f / 112f).toInt()
        if (::localPreviewContainer.isInitialized) {
            localPreviewContainer.layoutParams = (localPreviewContainer.layoutParams as LinearLayout.LayoutParams).apply {
                this.width = previewTileWidth
            }
        }
        if (localRendererAttached) {
            localRenderer.layoutParams = (localRenderer.layoutParams as LinearLayout.LayoutParams).apply {
                this.width = previewTileWidth
                this.height = previewTileHeight
            }
        }
        if (::remoteCameraContainer.isInitialized) {
            remoteCameraContainer.layoutParams = (remoteCameraContainer.layoutParams as LinearLayout.LayoutParams).apply {
                this.width = previewTileWidth
                this.height = previewTileHeight
            }
        }
        if (::previewControls.isInitialized) {
            previewControls.layoutParams = (previewControls.layoutParams as LinearLayout.LayoutParams).apply {
                this.width = previewTileWidth
            }
        }
    }

    private fun updateVideoContainerLayout() {
        if (!::videoContainer.isInitialized) return
        val params = videoContainer.layoutParams as LinearLayout.LayoutParams
        if (fullscreen) {
            params.height = 0
            params.weight = 1f
        } else {
            params.height = normalVideoHeight
            params.weight = 0f
        }
        videoContainer.layoutParams = params

        if (::bottomSpacer.isInitialized) {
            val spacerParams = bottomSpacer.layoutParams as LinearLayout.LayoutParams
            spacerParams.height = 0
            spacerParams.weight = 1f
            bottomSpacer.layoutParams = spacerParams
        }
    }

    private fun updateChatContainerHeight() {
        if (!::chatMessagesContainer.isInitialized) return
        val params = chatMessagesContainer.layoutParams as FrameLayout.LayoutParams
        params.height = if (chatExpanded) expandedChatHeight() else dp(130)
        params.gravity = Gravity.BOTTOM
        params.bottomMargin = chatBottomMargin()
        chatMessagesContainer.layoutParams = params
    }

    private fun applyKeyboardAwareChatLayout() {
        if (!::chatMessagesContainer.isInitialized) return
        updateChatContainerHeight()
        updateVideoContainerLayout()
        chatMessagesContainer.translationY = 0f

        if (chatExpanded && keyboardVisible) {
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun chatBottomMargin(): Int {
        val controlsHeight = controlsRow.height.takeIf { it > 0 } ?: dp(52)
        return if (chatExpanded && keyboardVisible) keyboardInsetBottom else controlsHeight
    }

    private fun expandedChatHeight(): Int {
        val rootHeight = root.height
        if (rootHeight <= 0) return dp(400)
        val bottomMargin = chatBottomMargin()
        val maxHeight = rootHeight - headerBar.height - bottomMargin
        return dp(400).coerceAtMost(maxHeight.coerceAtLeast(dp(130)))
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun copyRoomLink() {
        val link = "https://voice.raycc.org/room/$currentRoom"
        val clip = getSystemService(ClipboardManager::class.java)
        clip.setPrimaryClip(ClipData.newPlainText("Vidly room", link))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareRoomLink() {
        val link = "https://voice.raycc.org/room/$currentRoom"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Join my Vidly call: $link")
        }
        try {
            startActivity(Intent.createChooser(send, "Share room link"))
        } catch (_: Throwable) {
            copyRoomLink()
        }
    }

    private fun refreshParticipants() {
        participantsRow.removeAllViews()
        if (participants.isEmpty()) {
            val waiting = TextView(this).apply {
                text = "waiting for others..."
                setTextColor(Color.rgb(120, 126, 140))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.ITALIC)
                maxLines = 1
            }
            participantsRow.addView(waiting, LinearLayout.LayoutParams(-2, -2))
            return
        }

        val blockWidth = (resources.displayMetrics.widthPixels - dp(28)) / 3
        for ((peerId, name) in participants) {
            val state = peerMediaStates[peerId]
            val mic = if (state?.mic == true) "🎙️" else "🔇"
            val cam = if (state?.cam == true) "📷" else "📵"
            val screen = if (state?.screen == true) " 🖥️" else ""
            val block = TextView(this).apply {
                text = "$name $mic$cam$screen"
                setTextColor(Color.rgb(180, 186, 200))
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, 0, dp(8), 0)
            }
            participantsRow.addView(block, LinearLayout.LayoutParams(blockWidth, -2))
        }
    }

    private fun sendChatFromInput() {
        val body = chatInput.text.toString().trim()
        if (body.isBlank()) return
        val ts = System.currentTimeMillis()
        val msg = JSONObject()
            .put("type", "chat")
            .put("body", body)
            .put("ts", ts)
            .put("username", currentUsername)
        rtc?.sendChatJson(msg)
        appendChatText(currentUsername.ifBlank { "You" }, body, incoming = false, ts = ts, editable = true)
        saveChatMsg(JSONObject()
            .put("type", "chat").put("kind", "own")
            .put("body", body).put("ts", ts)
            .put("senderName", currentUsername))
        chatInput.setText("")
    }

    private fun sendReaction(emoji: String) {
        val ts = System.currentTimeMillis()
        val msg = JSONObject()
            .put("type", "reaction")
            .put("emoji", emoji)
            .put("ts", ts)
            .put("username", currentUsername)
        rtc?.sendChatJson(msg)
        appendChatText(currentUsername.ifBlank { "You" }, emoji, incoming = false, ts = ts)
        saveChatMsg(JSONObject()
            .put("type", "reaction").put("kind", "own")
            .put("emoji", emoji).put("ts", ts)
            .put("senderName", currentUsername))
    }

    private fun sendMedia(url: String, kind: String) {
        val ts = System.currentTimeMillis()
        val msg = JSONObject()
            .put("type", "media")
            .put("url", url)
            .put("kind", kind)
            .put("ts", ts)
            .put("username", currentUsername)
        rtc?.sendChatJson(msg)
        appendChatMedia(currentUsername.ifBlank { "You" }, url, kind, incoming = false, ts = ts)
        saveChatMsg(JSONObject()
            .put("type", "media").put("kind", "own")
            .put("url", url).put("mediaKind", kind).put("ts", ts)
            .put("senderName", currentUsername))
    }

    // ─── Chat history (SharedPreferences) ─────────────────────

    private fun chatKey() = "chat-$currentRoom"

    private fun saveChatMsg(msg: JSONObject) {
        if (currentRoom.isBlank() || loadingHistory) return
        try {
            val prefs = getSharedPreferences("vidly_prefs", MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(chatKey(), "[]"))
            arr.put(msg)
            val out = if (arr.length() > CHAT_MAX) {
                JSONArray().apply {
                    for (i in arr.length() - CHAT_MAX until arr.length()) put(arr.get(i))
                }
            } else arr
            prefs.edit().putString(chatKey(), out.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun loadChatHistory() {
        if (currentRoom.isBlank()) return
        loadingHistory = true
        try {
            val arr = JSONArray(getSharedPreferences("vidly_prefs", MODE_PRIVATE).getString(chatKey(), "[]"))
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val incoming = m.optString("kind") == "other"
                val sender = if (incoming) m.optString("senderName", "Peer")
                             else currentUsername.ifBlank { "You" }
                val ts = m.optLong("ts")
                when (m.optString("type")) {
                    "chat" -> appendChatText(sender, m.optString("body"), incoming, ts = ts, editable = !incoming)
                    "reaction" -> appendChatText(sender, m.optString("emoji"), incoming, ts = ts)
                    "media" -> {
                        val mediaKind = m.optString("mediaKind", m.optString("kind"))
                        appendChatMedia(sender, m.optString("url"), mediaKind, incoming, ts = ts)
                    }
                }
            }
        } catch (_: Exception) {}
        loadingHistory = false
    }

    // ─── Chat rendering ───────────────────────────────────────

    private fun appendChatText(
        sender: String,
        body: String,
        incoming: Boolean,
        ts: Long = System.currentTimeMillis(),
        editable: Boolean = false
    ) {
        val item = makeMessageContainer(sender, incoming)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fillTextRow(item, row, body, ts, editable)
        item.addView(row)
        addMessageItem(item, ts)
    }

    /** (Re)populate a horizontal message row with a selectable text view and,
     *  for own messages, a trailing ⋯ menu button (Edit / Delete). */
    private fun fillTextRow(item: LinearLayout, row: LinearLayout, body: String, ts: Long, editable: Boolean) {
        row.removeAllViews()
        val text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setChatMessageText(body)
            setTextIsSelectable(true)
        }
        row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (editable) {
            val menuBtn = TextView(this).apply {
                this.text = "⋯"
                setTextColor(Color.rgb(150, 156, 170))
                textSize = 20f
                setPadding(dp(10), 0, dp(4), 0)
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { showMessageMenu(item, row, ts, this) }
            }
            row.addView(menuBtn)
        }
    }

    private fun showMessageMenu(item: LinearLayout, row: LinearLayout, ts: Long, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "Edit")
        popup.menu.add(0, 2, 1, "Delete")
        popup.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                1 -> { startInlineEdit(item, row, ts); true }
                2 -> { deleteMessage(item, ts); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun startInlineEdit(item: LinearLayout, row: LinearLayout, ts: Long) {
        val oldBody = (row.getChildAt(0) as? TextView)?.text?.toString() ?: return
        row.removeAllViews()
        val edit = EditText(this).apply {
            setText(oldBody)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(120, 126, 140))
            textSize = 14f
            setSelection(oldBody.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val confirm = TextView(this).apply {
            text = "✓"
            setTextColor(Color.rgb(120, 200, 160))
            textSize = 18f
            setPadding(dp(10), 0, dp(6), 0)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                val newBody = edit.text.toString().trim()
                if (newBody.isBlank()) {
                    fillTextRow(item, row, oldBody, ts, true)
                    return@setOnClickListener
                }
                if (newBody != oldBody) {
                    val newTs = System.currentTimeMillis()
                    rtc?.sendChatJson(JSONObject()
                        .put("type", "chat-edit")
                        .put("originalTs", ts)
                        .put("body", newBody)
                        .put("ts", newTs)
                        .put("username", currentUsername))
                    updateChatHistoryBody(ts, newBody)
                }
                fillTextRow(item, row, newBody, ts, true)
                hideKeyboard()
            }
        }
        val cancel = TextView(this).apply {
            text = "✕"
            setTextColor(Color.rgb(200, 120, 120))
            textSize = 18f
            setPadding(dp(6), 0, dp(4), 0)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                fillTextRow(item, row, oldBody, ts, true)
                hideKeyboard()
            }
        }
        row.addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(confirm)
        row.addView(cancel)
        edit.requestFocus()
    }

    private fun deleteMessage(item: LinearLayout, ts: Long) {
        chatList.removeView(item)
        deleteChatHistory(ts)
    }

    /** Apply an incoming chat-edit: update the matching message view + history. */
    private fun applyChatEdit(originalTs: Long, newBody: String) {
        if (originalTs == 0L) return
        for (i in 0 until chatList.childCount) {
            val child = chatList.getChildAt(i)
            if ((child.tag as? Long) == originalTs && child is LinearLayout) {
                val row = child.getChildAt(child.childCount - 1) as? LinearLayout
                (row?.getChildAt(0) as? TextView)?.apply {
                    setChatMessageText(newBody)
                    setTextIsSelectable(true)
                }
                break
            }
        }
        updateChatHistoryBody(originalTs, newBody)
    }

    private fun updateChatHistoryBody(originalTs: Long, newBody: String) {
        if (currentRoom.isBlank()) return
        try {
            val prefs = getSharedPreferences("vidly_prefs", MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(chatKey(), "[]"))
            var changed = false
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (m.optString("type") == "chat" && m.optLong("ts") == originalTs) {
                    m.put("body", newBody)
                    changed = true
                }
            }
            if (changed) prefs.edit().putString(chatKey(), arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun deleteChatHistory(ts: Long) {
        if (currentRoom.isBlank()) return
        try {
            val prefs = getSharedPreferences("vidly_prefs", MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(chatKey(), "[]"))
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                if (m.optLong("ts") == ts) continue
                out.put(m)
            }
            prefs.edit().putString(chatKey(), out.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun TextView.setChatMessageText(body: String) {
        val spannable = SpannableString(body)
        val matcher = Patterns.WEB_URL.matcher(body)
        var hasLinks = false
        while (matcher.find()) {
            val rawUrl = matcher.group().orEmpty()
            if (rawUrl.isBlank()) continue
            val openUrl = normalizeUrl(rawUrl)
            spannable.setSpan(URLSpan(openUrl), matcher.start(), matcher.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            hasLinks = true
        }
        text = spannable
        if (hasLinks) installUrlTouchHandler(this)
    }

    private fun normalizeUrl(url: String): String {
        return if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            url
        } else {
            "https://$url"
        }
    }

    private fun installUrlTouchHandler(textView: TextView) {
        var touchedUrl: URLSpan? = null
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val url = touchedUrl?.url ?: return false
                openUrl(url)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val url = touchedUrl?.url ?: return
                copyUrl(url)
            }
        })
        textView.setOnTouchListener { view, event ->
            val tv = view as TextView
            if (event.action == MotionEvent.ACTION_DOWN) touchedUrl = tv.urlSpanAt(event)
            val handled = touchedUrl != null && detector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                tv.postDelayed({ touchedUrl = null }, 120)
            }
            handled
        }
    }

    private fun TextView.urlSpanAt(event: MotionEvent): URLSpan? {
        val spannable = text as? Spanned ?: return null
        val layout = layout ?: return null
        val x = event.x.toInt() - totalPaddingLeft + scrollX
        val y = event.y.toInt() - totalPaddingTop + scrollY
        if (x < 0 || y < 0 || y > height) return null
        val line = layout.getLineForVertical(y)
        val offset = layout.getOffsetForHorizontal(line, x.toFloat())
        return spannable.getSpans(offset, offset, URLSpan::class.java).firstOrNull()
    }

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show() }
    }

    private fun copyUrl(url: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Vidly link", url))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun appendChatMedia(sender: String, url: String, kind: String, incoming: Boolean, ts: Long = System.currentTimeMillis()) {
        val item = makeMessageContainer(sender, incoming)
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            background = null
            setOnClickListener { showFullscreenImage(url) }
            setOnLongClickListener {
                saveImageUrlToGallery(url, "vidly-${System.currentTimeMillis()}.${extensionForImage(url, kind)}")
                true
            }
        }
        item.addView(image, LinearLayout.LayoutParams(dp(220), -2).apply { topMargin = dp(4) })
        loadImageInto(image, url)
        addMessageItem(item, ts)
    }

    private fun appendChatFileImage(sender: String, name: String, bitmap: Bitmap, incoming: Boolean, bytes: ByteArray? = null, mimeType: String = "image/png", ts: Long = System.currentTimeMillis()) {
        val item = makeMessageContainer(sender, incoming)
        val nameView = TextView(this).apply {
            text = "📎 $name"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        item.addView(nameView)
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            setImageBitmap(bitmap)
            setOnClickListener { showFullscreenImage(bitmap) }
            setOnLongClickListener {
                val saved = bytes?.let { saveImageBytesToGallery(it, name, mimeType) }
                    ?: saveBitmapToGallery(bitmap, name)
                Toast.makeText(
                    this@NativeCallActivity,
                    if (saved) "Saved image to gallery" else "Could not save image",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }
        }
        item.addView(image, LinearLayout.LayoutParams(dp(220), -2).apply { topMargin = dp(4) })
        addMessageItem(item, ts)
    }

    private fun showFullscreenImage(url: String) {
        if (url.isBlank()) return
        val image = showFullscreenImageDialog()
        loadImageInto(image, url)
    }

    private fun showFullscreenImage(bitmap: Bitmap) {
        val image = showFullscreenImageDialog()
        image.setImageBitmap(bitmap)
    }

    private fun showFullscreenImageDialog(): ZoomableImageView {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val image = ZoomableImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnSingleTapConfirmed { dialog.dismiss() }
        }
        dialog.setContentView(image, ViewGroup.LayoutParams(-1, -1))
        dialog.show()
        return image
    }

    private fun saveImageUrlToGallery(url: String, name: String) {
        if (url.isBlank()) return
        Toast.makeText(this, "Saving image...", Toast.LENGTH_SHORT).show()
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { Toast.makeText(this@NativeCallActivity, "Could not save image", Toast.LENGTH_SHORT).show() }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread { Toast.makeText(this@NativeCallActivity, "Could not save image", Toast.LENGTH_SHORT).show() }
                        return@use
                    }
                    val bytes = it.body?.bytes() ?: return@use
                    val mime = it.header("Content-Type")?.substringBefore(';')?.takeIf { value -> value.startsWith("image/") }
                        ?: mimeTypeForName(name)
                    val saved = saveImageBytesToGallery(bytes, name, mime)
                    runOnUiThread {
                        Toast.makeText(
                            this@NativeCallActivity,
                            if (saved) "Saved image to gallery" else "Could not save image",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    private fun saveBitmapToGallery(bitmap: Bitmap, name: String): Boolean {
        val bytes = java.io.ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        return saveImageBytesToGallery(bytes, name, "image/png")
    }

    private fun saveImageBytesToGallery(bytes: ByteArray, name: String, mimeType: String): Boolean {
        val safeName = name.ifBlank { "vidly-${System.currentTimeMillis()}.png" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, safeName)
                    put(MediaStore.Images.Media.MIME_TYPE, mimeType.takeIf { it.startsWith("image/") } ?: mimeTypeForName(safeName))
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Vidly")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
                try {
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    true
                } catch (e: IOException) {
                    contentResolver.delete(uri, null, null)
                    false
                }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Vidly")
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, safeName)
                FileOutputStream(outFile).use { it.write(bytes) }
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(outFile)))
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun extensionForImage(url: String, kind: String): String {
        val path = runCatching { Uri.parse(url).lastPathSegment.orEmpty() }.getOrDefault("")
        val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
        return when {
            ext in setOf("jpg", "jpeg", "png", "gif", "webp") -> ext
            kind == "gif" -> "gif"
            else -> "png"
        }
    }

    private fun mimeTypeForName(name: String): String {
        return when (name.substringAfterLast('.', "").lowercase(Locale.US)) {
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "image/png"
        }
    }

    private fun makeMessageContainer(sender: String, incoming: Boolean): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        val name = TextView(this).apply {
            text = sender
            setTextColor(if (incoming) Color.rgb(170, 176, 220) else Color.rgb(120, 200, 160))
            textSize = 11f
        }
        container.addView(name)
        return container
    }

    private fun addMessageItem(item: LinearLayout, ts: Long) {
        maybeAddTimeDivider(ts)
        item.tag = ts
        chatList.addView(item)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Insert a centered HH:MM divider when the gap from the previous
     *  message exceeds 5 minutes (and always before the first message). */
    private fun maybeAddTimeDivider(ts: Long) {
        if (ts <= 0L) return
        if (lastChatTs == 0L || ts - lastChatTs > 5 * 60 * 1000L) {
            chatList.addView(makeTimeDivider(ts))
        }
        lastChatTs = ts
    }

    private fun makeTimeDivider(ts: Long): TextView {
        return TextView(this).apply {
            text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
            setTextColor(Color.rgb(120, 126, 140))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(2))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
    }

    private fun sendChatJsonTo(peerId: String, payload: JSONObject) {
        rtc?.sendChatJsonTo(peerId, payload)
    }

    // ─── File transfer ────────────────────────────────────────

    private fun promptFileSend() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, FILE_PICK_REQUEST)
        } catch (_: Throwable) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_PICK_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            sendFile(uri)
            return
        }
        if (requestCode == SCREEN_CAPTURE_REQUEST) {
            if (resultCode != RESULT_OK || data == null) {
                Toast.makeText(this, "Screen share permission denied", Toast.LENGTH_SHORT).show()
                return
            }
            // Android 14 ordering: the FGS must already be in the foreground with
            // the mediaProjection type BEFORE the projection token is obtained.
            // The bound service lets us do this synchronously.
            val service = boundService
            if (service == null) {
                Log.e("VidlyScreen", "onActivityResult: boundService is null!")
                Toast.makeText(this, "Call service not ready", Toast.LENGTH_SHORT).show()
                return
            }
            Log.i("VidlyScreen", "onActivityResult: promoting FGS to mediaProjection")
            service.promoteToMediaProjection()
            Log.i("VidlyScreen", "onActivityResult: calling setScreenEnabled(true)")
            rtc?.setScreenEnabled(true, data)
            screenSharing = true
            updateMediaButtons()
            updateProximitySensorState()
        }
    }

    private fun sendFile(uri: Uri) {
        val (name, type, bytes) = readFileFromUri(uri) ?: run {
            Toast.makeText(this, "Could not read file", Toast.LENGTH_SHORT).show()
            return
        }
        val id = "file-${System.currentTimeMillis().toString(36)}-${UUID.randomUUID().toString().take(7)}"
        val targets = rtc?.knownPeers()?.map { it.first }?.toMutableSet() ?: return
        if (targets.isEmpty()) {
            Toast.makeText(this, "No peers connected", Toast.LENGTH_SHORT).show()
            return
        }
        val outgoing = OutgoingFile(id, name, type, bytes.size.toLong(), bytes, targets)
        outgoingFiles[id] = outgoing
        val meta = JSONObject()
            .put("id", id)
            .put("name", name)
            .put("type", type)
            .put("size", bytes.size)
            .put("ts", System.currentTimeMillis())
            .put("username", currentUsername)
        for (peerId in targets) {
            sendChatJsonTo(peerId, JSONObject().put("type", "file-offer").put("file", meta))
        }
        // Show in chat — if image, render the bitmap
        if (type.startsWith("image/")) {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                appendChatFileImage(currentUsername.ifBlank { "You" }, name, bmp, incoming = false, bytes = bytes, mimeType = type)
            } else {
                appendChatText(currentUsername.ifBlank { "You" }, "📎 $name (${formatBytes(bytes.size.toLong())})", incoming = false)
            }
        } else {
            appendChatText(currentUsername.ifBlank { "You" }, "📎 $name (${formatBytes(bytes.size.toLong())})", incoming = false)
        }
    }

    private fun readFileFromUri(uri: Uri): Triple<String, String, ByteArray>? {
        return try {
            val cr = contentResolver
            val name = cr.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx) else "file"
                } else "file"
            } ?: "file"
            val type = cr.getType(uri) ?: "application/octet-stream"
            val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return null
            Triple(name, type, bytes)
        } catch (_: Throwable) {
            null
        }
    }

    private fun handleIncomingFileOffer(fromPeerId: String, fromUsername: String?, file: JSONObject) {
        val id = file.optString("id")
        if (id.isBlank() || incomingFiles.containsKey(id)) return
        val name = file.optString("name", "file")
        val type = file.optString("type", "application/octet-stream")
        val size = file.optLong("size", 0)
        val incoming = IncomingFile(id, name, type, size, fromPeerId, fromUsername)
        incomingFiles[id] = incoming
        if (activeIncomingByPeer.containsKey(fromPeerId)) {
            incomingFiles.remove(id)
            sendChatJsonTo(fromPeerId, JSONObject().put("type", "file-reject").put("id", id))
            appendChatText("System", "Busy receiving another file; declined $name", incoming = true)
            return
        }
        activeIncomingByPeer[fromPeerId] = id
        appendChatText(fromUsername ?: "Peer", "📎 Receiving: $name (${formatBytes(size)})", incoming = true)
        sendChatJsonTo(fromPeerId, JSONObject().put("type", "file-accept").put("id", id))
    }

    private fun handleFileAccept(fromPeerId: String, id: String) {
        val outgoing = outgoingFiles[id] ?: return
        outgoing.acceptedBy.add(fromPeerId)
        Thread { sendFileChunksTo(fromPeerId, outgoing) }.start()
    }

    private fun sendFileChunksTo(peerId: String, file: OutgoingFile) {
        if (file.size == 0L) {
            sendChatJsonTo(peerId, JSONObject().put("type", "file-empty").put("id", file.id))
            return
        }
        var offset = 0
        val chunkSize = FILE_CHUNK_SIZE
        val t0 = SystemClock.elapsedRealtime()
        var waitMs = 0L
        while (offset < file.bytes.size) {
            val rtcRef = rtc ?: break
            // Event-driven backpressure: wake up the moment the SCTP queue drains
            // below FILE_BUFFER_HIGH, with a 200ms safety timeout. The previous
            // 20ms fixed sleep added up to 20ms of idle to every refill cycle.
            while (rtcRef.fileChannelBufferedAmount(peerId) > FILE_BUFFER_HIGH) {
                val ws = SystemClock.elapsedRealtime()
                // Returns true when drained, false on timeout / closed / interrupt.
                // On false we just re-check the while condition; if the channel
                // really closed, bufferedAmount() returns 0 and the loop exits,
                // then sendFileChunk() will fail and we report the error.
                rtcRef.waitForFileChannelDrain(peerId, FILE_BUFFER_HIGH, 200)
                waitMs += SystemClock.elapsedRealtime() - ws
                if (Thread.currentThread().isInterrupted) return
            }
            val end = minOf(offset + chunkSize, file.bytes.size)
            val chunk = file.bytes.copyOfRange(offset, end)
            val sentOk = rtcRef.sendFileChunk(peerId, chunk)
            if (!sentOk) {
                runOnUiThread { appendChatText("System", "File send failed: ${file.name}", incoming = true) }
                return
            }
            offset = end
        }
        val totalMs = SystemClock.elapsedRealtime() - t0
        val mbps = if (totalMs > 0) (file.bytes.size.toLong() * 8L) / totalMs / 1000.0 else 0.0
        android.util.Log.i(
            "VidlyFile",
            "send ${file.name} ${file.bytes.size}B in ${totalMs}ms → %.1f Mb/s (chunk=$chunkSize wait=${waitMs}ms)".format(mbps)
        )
    }

    private fun handleFileReject(fromPeerId: String, id: String) {
        val outgoing = outgoingFiles[id] ?: return
        runOnUiThread {
            appendChatText("System", "${rtc?.usernameFor(fromPeerId) ?: "Peer"} rejected ${outgoing.name}", incoming = true)
        }
    }

    private fun handleFileComplete(fromPeerId: String, id: String) {
        val outgoing = outgoingFiles[id] ?: return
        outgoing.completedBy.add(fromPeerId)
        runOnUiThread {
            appendChatText("System", "Sent ${outgoing.name} to ${rtc?.usernameFor(fromPeerId) ?: "peer"}", incoming = false)
        }
        if (outgoing.completedBy.size >= outgoing.targetPeerIds.size) {
            outgoingFiles.remove(id)
        }
    }

    private fun handleFileEmpty(fromPeerId: String, id: String) {
        val transfer = incomingFiles[id] ?: return
        completeIncomingFile(transfer)
    }

    private fun completeIncomingFile(transfer: IncomingFile) {
        val totalMs = SystemClock.elapsedRealtime() - transfer.startedAt
        val mbps = if (totalMs > 0) (transfer.received * 8L) / totalMs / 1000.0 else 0.0
        android.util.Log.i(
            "VidlyFile",
            "recv ${transfer.name} ${transfer.received}B in ${totalMs}ms → %.1f Mb/s (chunks=${transfer.chunks.size})".format(mbps)
        )
        val all = ByteArray(transfer.received.toInt())
        var offset = 0
        for (chunk in transfer.chunks) {
            System.arraycopy(chunk, 0, all, offset, chunk.size)
            offset += chunk.size
        }
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeName = transfer.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outName = "${ts}_$safeName"
        val saved = try {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloads.exists()) downloads.mkdirs()
            val outFile = File(downloads, outName)
            FileOutputStream(outFile).use { it.write(all) }
            outFile.absolutePath
        } catch (_: IOException) {
            try {
                val outFile = File(filesDir, outName)
                FileOutputStream(outFile).use { it.write(all) }
                outFile.absolutePath
            } catch (_: IOException) { null }
        }
        sendChatJsonTo(transfer.fromPeerId, JSONObject().put("type", "file-complete").put("id", transfer.id))
        if (activeIncomingByPeer[transfer.fromPeerId] == transfer.id) {
            activeIncomingByPeer.remove(transfer.fromPeerId)
        }
        incomingFiles.remove(transfer.id)
        // Render in chat — if image, show inline
        if (transfer.type.startsWith("image/")) {
            val bmp = BitmapFactory.decodeByteArray(all, 0, all.size)
            if (bmp != null) {
                appendChatFileImage(transfer.fromUsername ?: "Peer", transfer.name, bmp, incoming = true, bytes = all, mimeType = transfer.type)
            } else {
                appendChatText(transfer.fromUsername ?: "Peer",
                    "📎 Received ${transfer.name} → ${saved ?: "(failed to save)"}", incoming = true)
            }
        } else {
            appendChatText(transfer.fromUsername ?: "Peer",
                "📎 Received ${transfer.name} → ${saved ?: "(failed to save)"}", incoming = true)
        }
        Toast.makeText(this, "Received ${transfer.name}", Toast.LENGTH_SHORT).show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
        return if (value >= 10 || i == 0) String.format(Locale.US, "%.0f %s", value, units[i])
            else String.format(Locale.US, "%.1f %s", value, units[i])
    }

    // ─── GIPHY ────────────────────────────────────────────────

    private fun showGiphyPicker() {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
        val search = EditText(this).apply { hint = "Search GIFs (empty for trending)" ; setSingleLine(true) }
        container.addView(search, LinearLayout.LayoutParams(-1, -2))
        val scroll = HorizontalScrollView(this)
        val results = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(results)
        container.addView(scroll, LinearLayout.LayoutParams(-1, dp(160)).apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(this)
            .setTitle("GIPHY")
            .setView(container)
            .setNegativeButton("Close", null)
            .show()

        fun load(query: String) {
            results.removeAllViews()
            fetchGiphy(query) { items ->
                runOnUiThread {
                    results.removeAllViews()
                    for (item in items) {
                        val iv = ImageView(this).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setOnClickListener {
                                sendMedia(item.url, "gif")
                                dialog.dismiss()
                            }
                        }
                        results.addView(iv, LinearLayout.LayoutParams(dp(140), dp(140)).apply { marginStart = dp(4) })
                        loadImageInto(iv, item.previewUrl)
                    }
                    if (items.isEmpty()) {
                        val empty = TextView(this).apply { text = "No results"; setTextColor(Color.WHITE) }
                        results.addView(empty)
                    }
                }
            }
        }
        load("")
        search.setOnEditorActionListener { _, _, _ -> load(search.text.toString().trim()); true }
    }

    private data class GiphyItem(val url: String, val previewUrl: String)

    private fun fetchGiphy(query: String, onResult: (List<GiphyItem>) -> Unit) {
        // Route through our own signaling server (/giphy proxy): api.giphy.com is
        // blocked on some client networks (e.g. CN), but the server can reach it.
        // Response is GIPHY's JSON, parsed unchanged below.
        val url = NativeSignalingClient.httpUrlFor("giphy").toHttpUrl()
            .newBuilder()
            .apply { if (query.isNotBlank()) addQueryParameter("q", query) }
            .build()
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { Toast.makeText(this@NativeCallActivity, "GIFs unavailable (network)", Toast.LENGTH_SHORT).show() }
                onResult(emptyList())
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    val items = mutableListOf<GiphyItem>()
                    runCatching {
                        val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                        for (i in 0 until data.length()) {
                            val item = data.optJSONObject(i) ?: continue
                            val images = item.optJSONObject("images") ?: continue
                            val original = images.optJSONObject("original")?.optString("url").orEmpty()
                            val preview = images.optJSONObject("fixed_width_small")?.optString("url")
                                ?: images.optJSONObject("fixed_width")?.optString("url").orEmpty()
                            if (original.isNotBlank()) items.add(GiphyItem(original, preview))
                        }
                    }
                    onResult(items)
                }
            }
        })
    }

    private fun loadImageInto(imageView: ImageView, url: String) {
        if (url.isBlank()) return
        http.newCall(Request.Builder().url(url).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val bytes = it.body?.bytes() ?: return
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return
                    runOnUiThread { imageView.setImageBitmap(bmp) }
                }
            }
        })
    }

    private fun checkForAppUpdate() {
        val versionUrl = NativeSignalingClient.httpUrlFor("version")
        http.newCall(Request.Builder().url(versionUrl).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) return@use
                    val json = runCatching { JSONObject(it.body?.string().orEmpty()) }.getOrNull() ?: return@use
                    val versionCode = json.optInt("versionCode", 0)
                    val versionName = json.optString("versionName", versionCode.toString())
                    val apkUrl = json.optString("apkUrl")
                    if (versionCode > BuildConfig.VERSION_CODE && apkUrl.isNotBlank()) {
                        runOnUiThread { promptAppUpdate(versionCode, versionName, apkUrl) }
                    }
                }
            }
        })
    }

    private fun promptAppUpdate(versionCode: Int, versionName: String, apkUrl: String) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Update available")
            .setMessage("Update available (v$versionName). Download?")
            .setPositiveButton("Download") { _, _ -> downloadAppUpdate(versionCode, apkUrl) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAppUpdate(versionCode: Int, apkUrl: String) {
        Toast.makeText(this, "Downloading update...", Toast.LENGTH_SHORT).show()
        http.newCall(Request.Builder().url(apkUrl).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { Toast.makeText(this@NativeCallActivity, "Update download failed", Toast.LENGTH_SHORT).show() }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        runOnUiThread { Toast.makeText(this@NativeCallActivity, "Update download failed", Toast.LENGTH_SHORT).show() }
                        return@use
                    }
                    val body = it.body ?: return@use
                    val updateDir = File(cacheDir, APK_CACHE_DIR).apply { mkdirs() }
                    val apkFile = File(updateDir, "vidly-update-$versionCode.apk")
                    try {
                        body.byteStream().use { input ->
                            FileOutputStream(apkFile).use { output -> input.copyTo(output) }
                        }
                        runOnUiThread { launchApkInstaller(apkFile) }
                    } catch (_: IOException) {
                        runOnUiThread { Toast.makeText(this@NativeCallActivity, "Could not save update", Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        })
    }

    private fun launchApkInstaller(apkFile: File) {
        val uri = Uri.Builder()
            .scheme("content")
            .authority("${BuildConfig.APPLICATION_ID}.apkprovider")
            .appendPath(APK_CACHE_DIR)
            .appendPath(apkFile.name)
            .build()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Toast.makeText(this, "Could not open installer", Toast.LENGTH_SHORT).show() }
    }

    // ─── Helpers ──────────────────────────────────────────────

    private fun setCallActive(active: Boolean) {
        if (active == callActive) return
        callActive = active
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            NativeCallService.start(this)
            // Bind so the Activity can synchronously promote the FGS to
            // mediaProjection before starting screen capture (Android 14).
            if (!serviceBound) {
                bindService(
                    Intent(this, NativeCallService::class.java),
                    serviceConnection,
                    Context.BIND_AUTO_CREATE
                )
                serviceBound = true
            }
            NativeIncomingCallListener.configure(this, currentRoom, currentUsername)
            updateProximitySensorState()
            startFrameHealthMonitor()
        } else {
            stopFrameHealthMonitor()
            // Stop any in-progress screen share before tearing down the FGS.
            if (screenSharing) {
                rtc?.setScreenEnabled(false)
                screenSharing = false
            }
            if (serviceBound) {
                unbindService(serviceConnection)
                serviceBound = false
                boundService = null
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            NativeCallService.stop(this)
            updateProximitySensorState()
        }
    }

    private fun seedJoinFieldsFromIntent() {
        val intent = intent ?: return
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null && data.host == "voice.raycc.org") {
            val segments = data.pathSegments
            if (segments.size >= 2 && segments[0] == "room") {
                val deepLinkRoom = segments[1]
                roomInput.setText(deepLinkRoom)
                if (currentUsername.isNotBlank()) usernameInput.setText(currentUsername)
            }
            return
        }

        val room = intent.getStringExtra(EXTRA_ROOM).orEmpty()
        val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        if (username.isNotBlank()) usernameInput.setText(username)
        if (room.isNotBlank()) roomInput.setText(room)
        if (username.isNotBlank() && room.isNotBlank() && intent.getBooleanExtra(EXTRA_AUTO_JOIN, false) == true) {
            joinRoom()
        }
    }

    private fun showJoinPanel() {
        stopFrameHealthMonitor()
        connectedPeerIds.clear()
        hasRemoteVideo = false
        hasRemoteCamera = false
        fullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        updateVideoContainerLayout()
        bottomSpacer.visibility = View.VISIBLE
        headerBar.visibility = View.GONE
        bottomContainer.visibility = View.GONE
        hideLocalRenderer()
        updatePreviewDockVisibility()
        previewControls.visibility = View.GONE
        reactionPicker.visibility = View.GONE
        chatExpanded = false
        chatMessagesContainer.translationY = 0f
        chatMessagesContainer.visibility = View.GONE
        joinPanel.visibility = View.VISIBLE
    }

    private fun updateMediaButtons() {
        micButton?.apply {
            text = "🎙️"
            applyToggleState(this, micEnabled)
        }
        cameraButton?.apply {
            text = "📷"
            applyToggleState(this, cameraEnabled || inPreview)
        }
        screenButton?.apply {
            text = "🖥️"
            applyToggleState(this, screenSharing)
        }
        updateAudioRouteButton()
    }

    private fun applyToggleState(button: Button, on: Boolean) {
        if (on) {
            button.background = roundedDrawable(Color.rgb(42, 45, 53))
            button.setTextColor(Color.WHITE)
            button.paintFlags = button.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        } else {
            button.background = roundedDrawable(Color.rgb(58, 34, 34))
            button.setTextColor(Color.rgb(255, 180, 180))
            button.paintFlags = button.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
    }

    private fun configureCallAudioRouting(defaultSpeakerphone: Boolean) {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        if (!audioRoutingConfigured) {
            previousAudioMode = audioManager.mode
            previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
            audioRoutingConfigured = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper())) } catch (_: Throwable) {}
            }
        }
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        preferSpeakerphoneWhenNoExternal = defaultSpeakerphone
        speakerphoneEnabled = shouldUseSpeakerphone(audioManager)
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Throwable) {}
        applyAudioRoute()
        updateAudioRouteButton()
    }

    private fun applyAudioRoute() {
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        val externalRoute = preferredExternalAudioRoute(audioManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Set the WebRTC AudioTrack's preferred device BEFORE changing the
            // communication device. AudioTrack.setPreferredDevice() only takes
            // effect on the NEXT routing change, and setCommunicationDevice is
            // what triggers that change. If we swap them (comm first, then
            // preferred), the preferred hint arrives after the re-route already
            // happened using the previous hint — so speaker→headphone switches
            // left the track pinned to the speaker even though the icon updated.
            rtc?.setPreferredOutputDevice(externalRoute)
            val target = externalRoute ?: audioManager.availableCommunicationDevices.firstOrNull {
                it.type == if (speakerphoneEnabled) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
            }
            if (target != null) {
                try { audioManager.setCommunicationDevice(target) } catch (_: Throwable) {}
            } else if (speakerphoneEnabled) {
                try { audioManager.clearCommunicationDevice() } catch (_: Throwable) {}
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Pre-S fallback: no setCommunicationDevice; the preferred device on
            // the WebRTC track plus the legacy speaker flag are all we have.
            rtc?.setPreferredOutputDevice(externalRoute)
        }
        try { audioManager.isSpeakerphoneOn = externalRoute == null && speakerphoneEnabled } catch (_: Throwable) {}
    }

    private fun resetCallAudioRouting() {
        if (!audioRoutingConfigured) return
        val audioManager = getSystemService(AudioManager::class.java)
        if (audioManager != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) } catch (_: Throwable) {}
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { audioManager.clearCommunicationDevice() } catch (_: Throwable) {}
            }
            try { audioManager.isSpeakerphoneOn = previousSpeakerphoneOn } catch (_: Throwable) {}
            try { audioManager.mode = previousAudioMode } catch (_: Throwable) {}
        }
        volumeControlStream = AudioManager.USE_DEFAULT_STREAM_TYPE
        audioRoutingConfigured = false
        speakerphoneEnabled = true
        preferSpeakerphoneWhenNoExternal = true
        updateAudioRouteButton()
    }

    private fun updateAudioRouteButton() {
        speakerButton?.apply {
            val audioManager = getSystemService(AudioManager::class.java)
            val usingExternalAudio = audioManager != null && hasExternalAudioRoute(audioManager)
            text = when {
                usingExternalAudio -> "🎧"
                speakerphoneEnabled -> "🔈"
                else -> "📞"
            }
            background = roundedDrawable(Color.rgb(42, 45, 53))
            setTextColor(Color.WHITE)
            paintFlags = paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            isSelected = speakerphoneEnabled && !usingExternalAudio
            // Only enable when there's a speaker/earpiece pair to toggle between;
            // external devices are routed automatically and not user-toggleable.
            isEnabled = canToggleAudioRoute()
            alpha = if (isEnabled) 1f else 0.5f
        }
    }

    private fun refreshCallAudioRouteForDevices() {
        if (!audioRoutingConfigured) return
        val audioManager = getSystemService(AudioManager::class.java) ?: return
        // Hot-plug changes return control to auto-routing: auto-switch to
        // headphones on connect, fall back to speaker/earpiece on disconnect.
        speakerphoneEnabled = shouldUseSpeakerphone(audioManager)
        applyAudioRoute()
        updateAudioRouteButton()
    }

    private fun shouldUseSpeakerphone(audioManager: AudioManager): Boolean {
        if (hasExternalAudioRoute(audioManager)) return false
        return preferSpeakerphoneWhenNoExternal || !hasEarpieceRoute(audioManager)
    }

    private fun canToggleAudioRoute(): Boolean {
        val audioManager = getSystemService(AudioManager::class.java) ?: return true
        return !hasExternalAudioRoute(audioManager) && hasSpeakerRoute(audioManager) && hasEarpieceRoute(audioManager)
    }

    private fun hasSpeakerRoute(audioManager: AudioManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            true
        }
    }

    private fun hasEarpieceRoute(audioManager: AudioManager): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        } else {
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
        }
    }

    private fun hasExternalAudioRoute(audioManager: AudioManager): Boolean {
        return preferredExternalAudioRoute(audioManager) != null
    }

    private fun preferredExternalAudioRoute(audioManager: AudioManager): AudioDeviceInfo? {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        } else {
            emptyList()
        }
        return devices.firstOrNull { isExternalAudioRoute(it.type) }
    }

    private fun isExternalAudioRoute(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_DEVICE
    }

    private data class TurnServerInfo(val urls: String, val username: String, val credential: String)

    private fun fetchTurnServersSync(): List<TurnServerInfo> {
        return try {
            val req = Request.Builder().url(NativeSignalingClient.httpUrlFor("turn-credentials")).build()
            val resp = http.newCall(req).execute()
            resp.use {
                if (!it.isSuccessful) return emptyList()
                val body = JSONObject(it.body?.string().orEmpty())
                val iceServers = body.optJSONArray("iceServers") ?: return emptyList()
                val result = mutableListOf<TurnServerInfo>()
                for (i in 0 until iceServers.length()) {
                    val entry = iceServers.optJSONObject(i) ?: continue
                    val urls = entry.optJSONArray("urls") ?: continue
                    val username = entry.optString("username", "")
                    val credential = entry.optString("credential", "")
                    for (j in 0 until urls.length()) {
                        val url = urls.optString(j, "").trim()
                        if (url.isNotBlank()) {
                            result.add(TurnServerInfo(url, username, credential))
                        }
                    }
                }
                result
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun ensureMediaPermissions(): Boolean {
        if (hasMediaPermissions()) return true
        requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), PERMISSIONS_REQUEST)
        return false
    }

    private fun hasMediaPermissions(): Boolean {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupProximitySensor() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "Vidly:NativeProximityWakeLock"
            ).apply { setReferenceCounted(false) }
        }
    }

    // Hold PROXIMITY_SCREEN_OFF_WAKE_LOCK during the call and let the system
    // (PowerManagerService) drive screen off/on from the proximity sensor. We do
    // NOT register our own SensorEventListener: a second consumer reacting to the
    // same sensor jitter fights the system at the near/far boundary and causes
    // screen blackout/flicker.
    private fun updateProximitySensorState() {
        val lock = proximityWakeLock ?: return
        val isAnyRemoteSharingScreen = peerMediaStates.values.any { it.screen }
        // Release the proximity lock while the LOCAL user is sharing screen so
        // their face doesn't black out the display mid-interaction.
        val shouldHold = callActive && !isAnyRemoteSharingScreen && !screenSharing
        if (shouldHold && !lock.isHeld) lock.acquire()
        else if (!shouldHold && lock.isHeld) lock.release()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ROOM = "room"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_AUTO_JOIN = "autoJoin"
        private const val PERMISSIONS_REQUEST = 5101
        private const val FILE_PICK_REQUEST = 5102
        private const val SCREEN_CAPTURE_REQUEST = 5103
        private const val STATE_ROOM = "stateRoom"
        private const val STATE_USERNAME = "stateUsername"
        // File transfer tuning. CHUNK_SIZE and BUFFER_HIGH are coupled: effective
        // pipeline depth ≈ BUFFER_HIGH / CHUNK_SIZE. Keep that ratio ≥ ~16 to avoid
        // stop-and-wait. BUFFER_HIGH must comfortably exceed the bandwidth-delay
        // product of the slowest link (TURN WAN ~500KB BDP). Must match the web
        // client's constants (public/index.html) for symmetric throughput.
        private const val FILE_CHUNK_SIZE = 64 * 1024
        private const val FILE_BUFFER_HIGH = 1L * 1024L * 1024L
        private const val APK_CACHE_DIR = "updates"
        private const val FRAME_STALL_MS = 5000L
        private const val FRAME_HEALTH_CHECK_MS = 3000L
        private const val ICE_RESTART_COOLDOWN_MS = 30_000L
        private const val CHAT_MAX = 200
        private val REACTIONS = listOf("❤️", "😂", "🎉", "😮", "👏", "🤗")
    }
}

class ZoomableImageView(context: android.content.Context) : ImageView(context) {
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f)
            applyTransform()
            return true
        }
    })
    private val tapDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTapConfirmed?.invoke()
            return true
        }
    })
    private var zoom = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var onSingleTapConfirmed: (() -> Unit)? = null

    init {
        scaleType = ScaleType.FIT_CENTER
    }

    fun setOnSingleTapConfirmed(listener: () -> Unit) {
        onSingleTapConfirmed = listener
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> if (zoom > 1f && !scaleDetector.isInProgress) {
                val dx = event.x - lastX
                val dy = event.y - lastY
                translateX += dx
                translateY += dy
                lastX = event.x
                lastY = event.y
                applyTransform()
            }
        }
        return true
    }

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        resetTransform()
    }

    private fun resetTransform() {
        zoom = 1f
        translateX = 0f
        translateY = 0f
        applyTransform()
    }

    private fun applyTransform() {
        scaleX = zoom
        scaleY = zoom
        translationX = translateX
        translationY = translateY
    }
}

class ApkCacheProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (!mode.contains('r')) throw FileNotFoundException("Read only")
        val file = fileForUri(uri)
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun fileForUri(uri: Uri): File {
        val ctx = context ?: throw FileNotFoundException("No context")
        val segments = uri.pathSegments
        if (segments.size != 2 || segments[0] != "updates" || !segments[1].endsWith(".apk")) {
            throw FileNotFoundException(uri.toString())
        }
        val file = File(File(ctx.cacheDir, "updates"), segments[1])
        val root = File(ctx.cacheDir, "updates").canonicalFile
        val canonical = file.canonicalFile
        if (!canonical.path.startsWith(root.path)) throw FileNotFoundException(uri.toString())
        return canonical
    }
}
