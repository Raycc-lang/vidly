package org.raycc.vidly.native

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.SharedPreferences
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class NativeCallActivity : Activity(),
    NativeSignalingClient.Listener,
    NativeWebRtcClient.DataListener,
    SensorEventListener {

    // Root layout
    private lateinit var root: FrameLayout
    private lateinit var callStack: LinearLayout
    private lateinit var videoContainer: FrameLayout
    private lateinit var bottomSpacer: View
    private lateinit var joinPanel: LinearLayout
    private lateinit var roomInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var localPreviewContainer: LinearLayout
    private lateinit var localRenderer: SurfaceViewRenderer
    private lateinit var remoteRenderer: SurfaceViewRenderer

    // Header
    private lateinit var headerBar: LinearLayout
    private lateinit var roomLabel: TextView
    private lateinit var headerStatusLabel: TextView
    private lateinit var participantsScroll: HorizontalScrollView
    private lateinit var participantsRow: LinearLayout

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
    private var beautySlider: SeekBar? = null

    // Bottom buttons
    private var micButton: Button? = null
    private var cameraButton: Button? = null
    private var speakerButton: Button? = null

    // PiP / proximity
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var inPip = false
    private var isProximityRegistered = false
    private var fullscreen = false

    // State
    private val signaling = NativeSignalingClient(this)
    private val http = OkHttpClient()
    private var rtc: NativeWebRtcClient? = null
    private var micEnabled = false
    private var cameraEnabled = false
    private var inPreview = false
    private var micEnabledBeforePreview = false
    private var callActive = false
    private var currentRoom = ""
    private var currentUsername = ""
    private var hasRemoteVideo = false
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
        var lastProgressSent: Long = 0
    )
    private val outgoingFiles = HashMap<String, OutgoingFile>()
    private val incomingFiles = HashMap<String, IncomingFile>()
    private val activeIncomingByPeer = HashMap<String, String>()

    // Top inset (status bar + display cutout) applied to header bar.
    private var topInset = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
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
        signaling.leave()
        signaling.dispose()
        rtc?.dispose()
        rtc = null
        resetCallAudioRouting()
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
        if (callActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        localPreviewContainer.visibility = if (isInPictureInPictureMode) View.GONE else localPreviewVisibility()
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
        if (participants.isEmpty()) hasRemoteVideo = false
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
            when (msg.optString("type")) {
                "chat" -> appendChatText(fromUsername ?: "Peer", msg.optString("body"), incoming = true)
                "reaction" -> {
                    val emoji = msg.optString("emoji")
                    if (emoji.isNotBlank()) {
                        appendChatText(fromUsername ?: "Peer", emoji, incoming = true)
                    }
                }
                "media" -> appendChatMedia(fromUsername ?: "Peer", msg.optString("url"), msg.optString("kind"), incoming = true)
                "media-state" -> {
                    val mic = msg.optBoolean("mic", false)
                    val cam = msg.optBoolean("cam", false)
                    val screen = msg.optBoolean("screen", false)
                    peerMediaStates[fromPeerId] = PeerMediaState(mic, cam, screen)
                    if (msg.has("video") && !msg.optBoolean("video", false)) {
                        rtc?.onPeerVideoInactive(fromPeerId)
                    }
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

        // Local preview floats above the whole call UI so its confirmation controls are not
        // clipped by the fixed 16:9 video area.
        localPreviewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setOnTouchListener { _, ev -> handleLocalPreviewDrag(ev) }
        }
        root.addView(localPreviewContainer, FrameLayout.LayoutParams(dp(112), -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(14)
            marginEnd = dp(14)
        })

        // Local renderer (top-right floating preview tile)
        localRenderer = SurfaceViewRenderer(this).apply {
            setZOrderMediaOverlay(true)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            visibility = View.GONE
            setOnTouchListener { _, ev -> handleLocalPreviewDrag(ev) }
        }
        attachLocalRenderer()

        // Preview controls (below local renderer, only visible during preview)
        previewControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.rgb(15, 17, 23))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        localPreviewContainer.addView(previewControls, LinearLayout.LayoutParams(dp(112), -2).apply {
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
        val confirmCancelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        previewControls.addView(confirmCancelRow, LinearLayout.LayoutParams(-1, -2))

        val confirmBtn = Button(this).apply {
            text = "✓"
            setOnClickListener { confirmPreview() }
        }
        confirmCancelRow.addView(confirmBtn, LinearLayout.LayoutParams(0, dp(40), 1f))

        val cancelBtn = Button(this).apply {
            text = "✕"
            setOnClickListener { cancelPreview() }
        }
        confirmCancelRow.addView(cancelBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(6) })

        val beautyBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        previewControls.addView(beautyBlock, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val beautyToggle = Switch(this).apply {
            text = "Beauty"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean -> rtc?.setBeautyEnabled(enabled) }
        }
        beautySwitch = beautyToggle
        beautyBlock.addView(beautyToggle, LinearLayout.LayoutParams(-2, -2))

        val slider = SeekBar(this).apply {
            max = 100
            progress = 45
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    rtc?.setBeautyIntensity(progress / 100f)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        beautySlider = slider
        beautyBlock.addView(slider, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
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

        // 1) Chat messages area (collapsible — toggle bar at top to expand/collapse)
        chatMessagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
            setBackgroundColor(Color.rgb(15, 17, 23))
            setPadding(dp(4), dp(0), dp(4), dp(2))
        }
        bottomContainer.addView(chatMessagesContainer, LinearLayout.LayoutParams(-1, dp(130)))

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

        // 2) Main controls (mic / cam / hangup)
        controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        bottomContainer.addView(controlsRow, LinearLayout.LayoutParams(-1, -2))

        val mic = Button(this).apply {
            text = "Mic off"
            setOnClickListener {
                if (!micEnabled && !ensureMediaPermissions()) return@setOnClickListener
                micEnabled = !micEnabled
                rtc?.setMicEnabled(micEnabled)
                updateMediaButtons()
                refreshParticipants()
            }
        }
        micButton = mic
        controlsRow.addView(mic, LinearLayout.LayoutParams(0, dp(44), 1f))

        val cam = Button(this).apply {
            text = "Cam off"
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
        controlsRow.addView(cam, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })

        val speaker = Button(this).apply {
            text = "Speaker"
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
        controlsRow.addView(speaker, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })

        val hangup = Button(this).apply {
            text = "Hang up"
            setOnClickListener { leaveCall() }
        }
        controlsRow.addView(hangup, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6) })

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
            setOnFocusChangeListener { _, hasFocus -> if (hasFocus && !chatExpanded) toggleChatExpanded() }
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
        videoStalled = false
        lastIceRestartAtMs = 0L
        micEnabled = false
        cameraEnabled = false
        inPreview = false
        fullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        updateVideoContainerLayout()
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        previewControls.visibility = View.GONE
        hideLocalRenderer()
        resetLocalPreviewPosition()
        refreshParticipants()
        chatList.removeAllViews()
        outgoingFiles.clear()
        incomingFiles.clear()
        activeIncomingByPeer.clear()
        // Initial collapsed state
        chatExpanded = false
        chatInputRow.visibility = View.GONE
        chatToggleBar.text = "+  Chat  +"
        (chatMessagesContainer.layoutParams as LinearLayout.LayoutParams).height = dp(130)
        configureCallAudioRouting(defaultSpeakerphone = true)
        updateMediaButtons()

        rtc?.dispose()
        rtc = NativeWebRtcClient(
            this,
            localRenderer,
            remoteRenderer,
            signaling,
            { msg -> runOnUiThread { updateHeaderStatus(msg) } },
            { active -> runOnUiThread { setRemoteVideoActive(active) } }
        ).also {
            it.dataListener = this
            it.start()
            loadTurnConfig(it)
        }
        signaling.connect(room, username)
    }

    private fun leaveCall() {
        stopFrameHealthMonitor()
        signaling.leave()
        rtc?.close()
        resetCallAudioRouting()
        hasRemoteVideo = false
        connectedPeerIds.clear()
        showJoinPanel()
        setCallActive(false)
    }

    private fun startPreview() {
        inPreview = true
        micEnabledBeforePreview = micEnabled
        if (!micEnabled) {
            micEnabled = true
            rtc?.setMicEnabled(true)
        }
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
        Toast.makeText(this, "Camera and mic on", Toast.LENGTH_SHORT).show()
    }

    private fun cancelPreview() {
        inPreview = false
        rtc?.cancelCameraPreview()
        hideLocalRenderer()
        cameraEnabled = false
        if (!micEnabledBeforePreview && micEnabled) {
            micEnabled = false
            rtc?.setMicEnabled(false)
        }
        previewControls.visibility = View.GONE
        updateMediaButtons()
        refreshParticipants()
    }

    private fun toggleChatExpanded() {
        chatExpanded = !chatExpanded
        val params = chatMessagesContainer.layoutParams as LinearLayout.LayoutParams
        if (chatExpanded) {
            params.height = expandedChatHeight()
            chatInputRow.visibility = View.VISIBLE
            chatToggleBar.text = "—  Chat  —"
        } else {
            params.height = dp(130)
            chatInputRow.visibility = View.GONE
            chatToggleBar.text = "+  Chat  +"
        }
        chatMessagesContainer.layoutParams = params
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
        bottomSpacer.visibility = visibility
        // Always GONE in fullscreen; otherwise only visible while previewing.
        previewControls.visibility = if (!fullscreen && inPreview) View.VISIBLE else View.GONE
        localPreviewContainer.visibility = if (fullscreen) View.GONE else localPreviewVisibility()
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
                // Push header below the cutout / status bar.
                headerBar.setPadding(dp(14), topInset + dp(12), dp(14), dp(8))
            }
            insets
        }
        root.requestApplyInsets()
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

    private fun showLocalPreview() {
        attachLocalRenderer()
        localRenderer.visibility = View.VISIBLE
        localPreviewContainer.visibility = localPreviewVisibility()
        keepLocalPreviewInBounds()
    }

    private fun hideLocalRenderer() {
        localRenderer.clearImage()
        localRenderer.visibility = View.GONE
        localPreviewContainer.visibility = View.GONE
        detachLocalRenderer()
        previewDragging = false
    }

    private fun attachLocalRenderer() {
        if (localRendererAttached) return
        localPreviewContainer.addView(localRenderer, 0, LinearLayout.LayoutParams(dp(112), dp(160)))
        localRendererAttached = true
    }

    private fun detachLocalRenderer() {
        if (!localRendererAttached) return
        localPreviewContainer.removeView(localRenderer)
        localRendererAttached = false
    }

    private fun resetLocalPreviewPosition() {
        if (!::localPreviewContainer.isInitialized) return
        localPreviewContainer.post {
            val params = localPreviewContainer.layoutParams as FrameLayout.LayoutParams
            params.gravity = Gravity.TOP or Gravity.END
            params.topMargin = videoContainer.top + dp(14)
            params.marginEnd = dp(14)
            params.leftMargin = 0
            localPreviewContainer.layoutParams = params
            localPreviewContainer.translationX = 0f
            localPreviewContainer.translationY = 0f
        }
    }

    private fun handleLocalPreviewDrag(ev: MotionEvent): Boolean {
        if (!::localPreviewContainer.isInitialized) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previewDragging = true
                previewDragStartRawX = ev.rawX
                previewDragStartRawY = ev.rawY
                // Anchor to the current VISUAL position (layout + any existing translation).
                previewDragStartX = localPreviewContainer.x
                previewDragStartY = localPreviewContainer.y
                localPreviewContainer.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!previewDragging) return false
                val parentWidth = root.width
                val parentHeight = root.height
                if (parentWidth <= 0 || parentHeight <= 0) return false

                val minVisible = dp(60).toFloat()
                val targetX = previewDragStartX + ev.rawX - previewDragStartRawX
                val targetY = previewDragStartY + ev.rawY - previewDragStartRawY

                // Clamp so at least minVisible pixels stay on screen.
                val clampedX = targetX.coerceIn(
                    minVisible - localPreviewContainer.width.toFloat(),
                    parentWidth - minVisible
                )
                val clampedY = targetY.coerceIn(
                    minVisible - localPreviewContainer.height.toFloat(),
                    parentHeight - minVisible
                )

                // translation = desired visual position - layout position (left/top).
                // This works regardless of current gravity or existing translation.
                localPreviewContainer.translationX = clampedX - localPreviewContainer.left
                localPreviewContainer.translationY = clampedY - localPreviewContainer.top
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!previewDragging) return false
                previewDragging = false
                localPreviewContainer.parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun keepLocalPreviewInBounds() {
        if (!::localPreviewContainer.isInitialized) return
        localPreviewContainer.post {
            if (localPreviewContainer.visibility != View.VISIBLE) return@post
            val parentWidth = root.width
            val parentHeight = root.height
            if (parentWidth <= 0 || parentHeight <= 0) return@post

            val minVisible = dp(60).toFloat()
            val currentX = localPreviewContainer.x
            val currentY = localPreviewContainer.y

            val clampedX = currentX.coerceIn(
                minVisible - localPreviewContainer.width.toFloat(),
                parentWidth - minVisible
            )
            val clampedY = currentY.coerceIn(
                minVisible - localPreviewContainer.height.toFloat(),
                parentHeight - minVisible
            )

            if (clampedX != currentX || clampedY != currentY) {
                localPreviewContainer.translationX = clampedX - localPreviewContainer.left
                localPreviewContainer.translationY = clampedY - localPreviewContainer.top
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
    }

    private fun expandedChatHeight(): Int {
        val desired = dp(400)
        val rootHeight = root.height
        if (rootHeight <= 0) return desired
        val reserved = headerBar.height + normalVideoHeight + controlsRow.height
        val available = rootHeight - reserved
        return available.coerceAtLeast(dp(130)).coerceAtMost(desired)
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
        val msg = JSONObject()
            .put("type", "chat")
            .put("body", body)
            .put("ts", System.currentTimeMillis())
            .put("username", currentUsername)
        rtc?.sendChatJson(msg)
        appendChatText(currentUsername.ifBlank { "You" }, body, incoming = false)
        chatInput.setText("")
    }

    private fun sendReaction(emoji: String) {
        val msg = JSONObject()
            .put("type", "reaction")
            .put("emoji", emoji)
            .put("ts", System.currentTimeMillis())
            .put("username", currentUsername)
        rtc?.sendChatJson(msg)
        appendChatText(currentUsername.ifBlank { "You" }, emoji, incoming = false)
    }

    private fun sendMedia(url: String, kind: String) {
        val msg = JSONObject()
            .put("type", "media")
            .put("url", url)
            .put("kind", kind)
            .put("ts", System.currentTimeMillis())
            .put("username", currentUsername)
        rtc?.sendChatJson(msg)
        appendChatMedia(currentUsername.ifBlank { "You" }, url, kind, incoming = false)
    }

    // ─── Chat rendering ───────────────────────────────────────

    private fun appendChatText(sender: String, body: String, incoming: Boolean) {
        val item = makeMessageContainer(sender, incoming)
        val text = TextView(this).apply {
            text = body
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        item.addView(text)
        addMessageItem(item, "$sender: $body")
    }

    private fun appendChatMedia(sender: String, url: String, kind: String, incoming: Boolean) {
        val item = makeMessageContainer(sender, incoming)
        val image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            background = null
            setOnLongClickListener {
                saveImageUrlToGallery(url, "vidly-${System.currentTimeMillis()}.${extensionForImage(url, kind)}")
                true
            }
        }
        item.addView(image, LinearLayout.LayoutParams(dp(220), -2).apply { topMargin = dp(4) })
        loadImageInto(image, url)
        addMessageItem(item, "$sender: 🖼️ $kind")
    }

    private fun appendChatFileImage(sender: String, name: String, bitmap: Bitmap, incoming: Boolean, bytes: ByteArray? = null, mimeType: String = "image/png") {
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
        addMessageItem(item, "$sender: 📎 $name (image)")
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

    private fun addMessageItem(item: LinearLayout, compact: String) {
        chatList.addView(item)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
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
        while (offset < file.bytes.size) {
            val rtcRef = rtc ?: break
            while (rtcRef.fileChannelBufferedAmount(peerId) > FILE_BUFFER_HIGH) {
                try { Thread.sleep(20) } catch (_: InterruptedException) { return }
            }
            val end = minOf(offset + chunkSize, file.bytes.size)
            val chunk = file.bytes.copyOfRange(offset, end)
            val ok = rtcRef.sendFileChunk(peerId, chunk)
            if (!ok) {
                runOnUiThread { appendChatText("System", "File send failed: ${file.name}", incoming = true) }
                return
            }
            offset = end
        }
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
        val urlBuilder = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.giphy.com")
            .addPathSegments(if (query.isNotBlank()) "v1/gifs/search" else "v1/gifs/trending")
        urlBuilder.addQueryParameter("api_key", GIPHY_API_KEY)
        urlBuilder.addQueryParameter("limit", GIPHY_LIMIT.toString())
        if (query.isNotBlank()) urlBuilder.addQueryParameter("q", query)
        http.newCall(Request.Builder().url(urlBuilder.build()).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult(emptyList()) }
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

    // ─── Helpers ──────────────────────────────────────────────

    private fun setCallActive(active: Boolean) {
        if (active == callActive) return
        callActive = active
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            NativeCallService.start(this)
            NativeIncomingCallListener.configure(this, currentRoom, currentUsername)
            updateProximitySensorState()
            startFrameHealthMonitor()
        } else {
            stopFrameHealthMonitor()
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
        fullscreen = false
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        updateVideoContainerLayout()
        bottomSpacer.visibility = View.VISIBLE
        headerBar.visibility = View.GONE
        bottomContainer.visibility = View.GONE
        hideLocalRenderer()
        previewControls.visibility = View.GONE
        reactionPicker.visibility = View.GONE
        chatExpanded = false
        chatMessagesContainer.visibility = View.GONE
        joinPanel.visibility = View.VISIBLE
    }

    private fun updateMediaButtons() {
        micButton?.text = if (micEnabled) "Mic" else "Mic off"
        cameraButton?.text = when {
            inPreview -> "Preview..."
            cameraEnabled -> "Cam"
            else -> "Cam off"
        }
        updateAudioRouteButton()
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
                usingExternalAudio -> "Headset"
                speakerphoneEnabled -> "Speaker"
                else -> "Earpiece"
            }
            isSelected = speakerphoneEnabled && !usingExternalAudio
            isEnabled = !usingExternalAudio && canToggleAudioRoute()
        }
    }

    private fun refreshCallAudioRouteForDevices() {
        if (!audioRoutingConfigured) return
        val audioManager = getSystemService(AudioManager::class.java) ?: return
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

    private fun loadTurnConfig(client: NativeWebRtcClient) {
        http.newCall(Request.Builder().url("https://voice.raycc.org/config").build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val turnUrl = JSONObject(it.body?.string().orEmpty()).optString("turnUrl")
                    val match = Regex("""^turn:([^?]+)(?:\?(.+))?$""").matchEntire(turnUrl) ?: return
                    val params = match.groupValues.getOrNull(2).orEmpty()
                    val values = params.split("&").mapNotNull { part ->
                        val pieces = part.split("=", limit = 2)
                        if (pieces.size == 2) pieces[0] to pieces[1] else null
                    }.toMap()
                    client.addTurnServer("turn:${match.groupValues[1]}", values["username"], values["credential"])
                }
            }
        })
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
        sensorManager = getSystemService(SensorManager::class.java)
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    private fun registerProximity() {
        if (isProximityRegistered) return
        val sensor = proximitySensor ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        isProximityRegistered = true
    }

    private fun unregisterProximity() {
        if (!isProximityRegistered) return
        sensorManager?.unregisterListener(this)
        proximityWakeLock?.takeIf { it.isHeld }?.release()
        isProximityRegistered = false
    }

    private fun updateProximitySensorState() {
        if (!callActive) {
            unregisterProximity()
            return
        }
        val isAnyRemoteSharingScreen = peerMediaStates.values.any { it.screen }
        if (isAnyRemoteSharingScreen) {
            unregisterProximity()
        } else {
            registerProximity()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!callActive || event.sensor.type != Sensor.TYPE_PROXIMITY) return
        val near = event.values.firstOrNull()?.let { it < event.sensor.maximumRange } ?: false
        val lock = proximityWakeLock ?: return
        if (near && !lock.isHeld) lock.acquire()
        else if (!near && lock.isHeld) lock.release()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ROOM = "room"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_AUTO_JOIN = "autoJoin"
        private const val PERMISSIONS_REQUEST = 5101
        private const val FILE_PICK_REQUEST = 5102
        private const val STATE_ROOM = "stateRoom"
        private const val STATE_USERNAME = "stateUsername"
        private const val FILE_CHUNK_SIZE = 16 * 1024
        private const val FILE_BUFFER_HIGH = 256L * 1024L
        private const val FRAME_STALL_MS = 5000L
        private const val FRAME_HEALTH_CHECK_MS = 3000L
        private const val ICE_RESTART_COOLDOWN_MS = 30_000L
        private const val GIPHY_API_KEY = "z3JlLEdcXBGP0Bitbf3ut2XjlnKaV0xn"
        private const val GIPHY_LIMIT = 20
        private val REACTIONS = listOf("❤️", "😂", "🎉", "😮", "👏", "🤗")
    }
}
