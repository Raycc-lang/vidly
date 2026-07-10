package org.raycc.vidly.native

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer

class NativeWebRtcClient(
    private val context: Context,
    private val localRenderer: SurfaceViewRenderer,
    private val remoteRenderer: SurfaceViewRenderer,
    private val signaling: NativeSignalingClient,
    private val status: (String) -> Unit,
    private val onRemoteVideo: (Boolean) -> Unit = {},
    private val cameraRenderer: SurfaceViewRenderer? = null,
    private val onRemoteCamera: (Boolean) -> Unit = {},
    // Fired the moment WebRTC's internal AudioTrack is created and starts
    // playing. This is the deterministic hook for applying output routing
    // (setCommunicationDevice / preferredDevice) — replaces the fixed-delay
    // retries that raced with AudioTrack creation. Fires on the WebRTC
    // AudioTrack thread; callers must marshal to the main thread.
    private val onAudioOutputStarted: () -> Unit = {},
    // Fired when screen sharing is stopped externally (system "Stop sharing"
    // button or MediaProjection revocation). Always dispatched on the main
    // thread so the Activity can reset UI + demote the FGS safely.
    private val onScreenShareStopped: () -> Unit = {}
) {
    interface DataListener {
        fun onChatMessage(fromPeerId: String, fromUsername: String?, msg: JSONObject)
        fun onFileBinary(fromPeerId: String, bytes: ByteArray)
        fun onPeerConnectionStateChanged(peerId: String, connected: Boolean)
    }

    var dataListener: DataListener? = null

    private val eglBase = EglBase.create()
    private val beautyProcessor = BeautyVideoProcessor()
    private val peers = LinkedHashMap<String, Peer>()
    private val iceServers = mutableListOf<PeerConnection.IceServer>()

    private lateinit var factory: PeerConnectionFactory
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var capturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoPeerId: String? = null
    private var remoteCameraTrack: VideoTrack? = null
    private var remoteScreenTrack: VideoTrack? = null
    private var renderedRemoteVideoTrack: VideoTrack? = null
    private var renderedRemoteCameraTrack: VideoTrack? = null
    // Remote toggled its camera/screen off. We keep the cached remote VideoTrack
    // (so we can resume rendering when it comes back) but stop drawing it. The
    // remote uses a stable RtpSender across off→on, so no new track arrives and
    // pc.onTrack does NOT refire; we must re-attach the existing track ourselves.
    private var remoteCameraPaused = false
    private var remoteScreenPaused = false
    // The remote video track that most recently produced a frame
    // (RtpReceiver.Observer.onFirstPacketReceived). Used by
    // reclassifyRemoteVideoTracks to tell an actively-producing track (e.g. a
    // screen track) apart from a muted/stale camera receiver — both are LIVE
    // (the sender keeps its RtpSender through a camera off), but only a
    // producing track fires onFirstPacketReceived. Android WebRTC exposes no
    // track-muted event, so this is the one reliable "alive" signal.
    private var activeVideoTrack: VideoTrack? = null
    @Volatile private var remoteVideoFrameTimestampMs: Long = 0L
    private val remoteVideoFrameSink = VideoSink {
        remoteVideoFrameTimestampMs = SystemClock.elapsedRealtime()
    }
    private var started = false
    // Single handler that owns ALL negotiation state (makingOffer/ignoreOffer,
    // senders, the peers map, remote track roles). WebRTC fires its observer
    // and SDP callbacks on internal threads; every callback that touches this
    // state is marshalled here so the perfect-negotiation flags are never read
    // and written from two threads at once. Peer-scoped delayed work (ICE
    // restarts) is posted with the Peer as token so close() cancels it all.
    private val main = Handler(Looper.getMainLooper())

    // Logical state — does the local user want camera/mic to be sending?
    private var cameraLive = false
    private var micLive = false
    // Logical state — is the local user sharing their screen? Mirrors the
    // cameraLive/micLive pattern: plain var touched from the main thread
    // (setScreenEnabled) plus the capture callback thread via stopScreen().
    private var screenLive = false
    private var screenCapturer: ScreenCapturerAndroid? = null
    private var screenSurfaceHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var localScreenTrack: VideoTrack? = null
    // Which physical camera is active. The initial capturer prefers the
    // front-facing camera (see createCameraCapturer), so we default to true.
    // Drives beautyProcessor.flipHorizontally — front sensor mirrors, so the
    // processor un-mirrors; back sensor does not, so the processor passes through.
    private var isFrontCamera = true
    // The two device names we cycle between on switchCamera(). Picked once at
    // capture start: the front camera and the "main" back camera (standard
    // focal length, skipping redundant wide/tele back cams). Null when only
    // one facing exists.
    private var frontDeviceName: String? = null
    private var backDeviceName: String? = null

    fun start() {
        if (started) return
        started = true

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        localRenderer.init(eglBase.eglBaseContext, null)
        remoteRenderer.init(eglBase.eglBaseContext, null)
        cameraRenderer?.init(eglBase.eglBaseContext, null)
        // Mirroring strategy — single source of truth is the VideoProcessor:
        //   sensor(mirrored for front) → BeautyVideoProcessor(un-mirror for front)
        //     → processed frame (CORRECT orientation for both front and back)
        //        ├→ encoder/remote: correct orientation ✓ (no renderer mirror needed)
        //        └→ localRenderer:  correct orientation ✓ (no renderer mirror needed)
        //  - ALL renderers stay at setMirror(false). The processor is the only
        //    place mirroring is corrected, and it feeds both the encoder and the
        //    local preview from the same already-correct frame.
        //  - Back camera: no sensor mirror, processor passes through, all renderers
        //    still setMirror(false). State is re-synced on capture start and switch.
        localRenderer.setMirror(false)
        remoteRenderer.setMirror(false)
        cameraRenderer?.setMirror(false)
        // setAudioTrackStateCallback fires AFTER the ADM creates its internal
        // AudioTrack and calls play() — the earliest reliable moment to pin the
        // output device. Before this, audioOutput.audioTrack is null, so the
        // preferred-device reflection in setPreferredOutputDevice() silently
        // no-op'd. This replaces the old fixed-delay (300/800/1500ms) retries.
        val adm = JavaAudioDeviceModule.builder(context)
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() = onAudioOutputStarted()
                override fun onWebRtcAudioTrackStop() = Unit
            })
            .createAudioDeviceModule()
        audioDeviceModule = adm
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        status("Joined with camera and mic off")
    }

    fun addTurnServer(url: String, username: String?, credential: String?) {
        if (url.isBlank()) return
        iceServers += PeerConnection.IceServer.builder(url)
            .setUsername(username.orEmpty())
            .setPassword(credential.orEmpty())
            .createIceServer()
    }

    fun onJoined(existingPeers: List<NativeSignalingClient.PeerInfo>) {
        status("Joined. Peers: ${existingPeers.size}")
        // A (re)join starts a fresh signaling session. The server announced us
        // as left the moment our old socket dropped, so remote sides already
        // tore their PC down (and rebuild on peer-resumed). Reusing a PC from
        // the previous session while the remote starts fresh desyncs DTLS/ICE
        // — their new answer never matches our old session and SRD fails.
        // Tear everything down and renegotiate from scratch. This also reaps
        // zombie peers left over after a signaling-server restart.
        if (peers.isNotEmpty()) {
            peers.values.forEach { peer ->
                peer.close(main)
                dataListener?.onPeerConnectionStateChanged(peer.id, false)
            }
            peers.clear()
            clearRemoteVideo()
        }
        existingPeers.forEach { peerInfo ->
            val peer = ensurePeer(peerInfo.peerId, polite = false)
            if (peerInfo.username != null) peer.username = peerInfo.username
            // Offerer creates the DataChannels.
            if (peer.chatChannel == null) {
                peer.chatChannel = peer.pc.createDataChannel("chat", DataChannel.Init())
                attachChatChannel(peer, peer.chatChannel!!)
            }
            if (peer.fileChannel == null) {
                peer.fileChannel = peer.pc.createDataChannel("file", DataChannel.Init())
                attachFileChannel(peer, peer.fileChannel!!)
            }
            makeOffer(peer)
        }
    }

    fun onPeerJoined(peerInfo: NativeSignalingClient.PeerInfo) {
        status("${peerInfo.username ?: "Peer"} joined")
        // peer-resumed (mapped here) and a re-announced peer-joined both mean
        // the remote rebuilt its side after a signaling gap. If we still hold
        // a PC for this id it belongs to the dead session — rebuild ours too
        // so the pair negotiates a fresh session symmetrically.
        peers.remove(peerInfo.peerId)?.let { stale ->
            stale.close(main)
            dataListener?.onPeerConnectionStateChanged(peerInfo.peerId, false)
            if (remoteVideoPeerId == peerInfo.peerId) clearRemoteVideo()
        }
        val peer = ensurePeer(peerInfo.peerId, polite = true)
        if (peerInfo.username != null) peer.username = peerInfo.username
    }

    fun onPeerLeft(peerId: String) {
        peers.remove(peerId)?.close(main)
        if (remoteVideoPeerId == peerId) {
            clearRemoteVideo()
        } else if (peers.isEmpty()) {
            remoteRenderer.clearImage()
            onRemoteVideo(false)
        }
    }

    fun onPeerVideoInactive(peerId: String) {
        if (remoteVideoPeerId == peerId) clearRemoteVideo()
    }

    fun getRemoteVideoFrameAgeMs(): Long {
        if (renderedRemoteVideoTrack == null && renderedRemoteCameraTrack == null) return Long.MAX_VALUE
        val lastFrameAt = remoteVideoFrameTimestampMs
        if (lastFrameAt == 0L) return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - lastFrameAt
    }

    fun onSignal(from: String, payload: JSONObject) {
        val peer = ensurePeer(from, polite = true)
        payload.optJSONObject("description")?.let {
            handleDescription(peer, it)
            return
        }
        payload.optJSONObject("candidate")?.let {
            val candidate = IceCandidate(
                it.optString("sdpMid"),
                it.optInt("sdpMLineIndex"),
                it.optString("candidate")
            )
            runCatching { peer.pc.addIceCandidate(candidate) }
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        if (enabled) {
            ensureAudioTrack()
            val track = localAudioTrack ?: return
            track.setEnabled(true)
            // First-time attach to each peer triggers onRenegotiationNeeded → makeOffer.
            peers.values.forEach { peer ->
                if (peer.audioSender == null) {
                    peer.audioSender = peer.pc.addTrack(track, listOf(LOCAL_STREAM_ID))
                }
            }
            micLive = true
            status("Microphone on")
        } else {
            localAudioTrack?.setEnabled(false)
            micLive = false
            status("Microphone off")
        }
        broadcastMediaState()
    }

    fun isMicLive() = micLive
    fun isCameraLive() = cameraLive

    /** Start camera preview: capture + show in localRenderer; do NOT push to peer senders. */
    fun startCameraPreview() {
        if (localVideoTrack != null) {
            resumeVideo()
        } else {
            ensureVideoTrack()
        }
        localVideoTrack?.setEnabled(true)
        status("Previewing camera")
    }

    /** Confirm preview: attach the local video track to all peer senders. */
    fun confirmCameraPreview() {
        val track = localVideoTrack ?: return
        track.setEnabled(true)
        peers.values.forEach { peer ->
            if (peer.videoSender == null) {
                peer.videoSender = peer.pc.addTrack(track, listOf(LOCAL_STREAM_ID))
            } else {
                peer.videoSender?.setTrack(track, false)
            }
        }
        cameraLive = true
        status("Camera on")
        broadcastMediaState()
    }

    /** Cancel preview: stop the local video capture. Sender stays detached. */
    fun cancelCameraPreview() {
        stopVideo()
        cameraLive = false
        broadcastMediaState()
        status("Camera off")
    }

    fun setCameraEnabled(enabled: Boolean) {
        if (enabled) {
            // Reuse the existing track on resume so the remote peer keeps the
            // same RtpReceiver track. Replacing the track (sender.setTrack with
            // a fresh one) does not re-fire pc.ontrack on the receiver, leaving
            // the web client pointed at the old (disposed) track and the video
            // never resumes for them.
            if (localVideoTrack == null) {
                ensureVideoTrack()
            } else {
                resumeVideo()
            }
            val track = localVideoTrack ?: return
            track.setEnabled(true)
            peers.values.forEach { peer ->
                if (peer.videoSender == null) {
                    peer.videoSender = peer.pc.addTrack(track, listOf(LOCAL_STREAM_ID))
                }
            }
            cameraLive = true
            status("Camera on")
        } else {
            // Keep the track and sender alive across off→on toggles. We just
            // release the camera (so the indicator goes away and the encoder
            // stops sending) and disable the track so any in-flight frames
            // are dropped before reaching peers.
            pauseVideo()
            cameraLive = false
            status("Camera off")
        }
        broadcastMediaState()
    }

    fun setBeautyEnabled(enabled: Boolean) {
        beautyProcessor.enabled = enabled
    }

    fun setBeautyIntensity(value: Float) {
        beautyProcessor.intensity = value
    }

    // ─── Screen share ─────────────────────────────────────────

    /**
     * Start or stop sharing the local screen. Mirrors [setCameraEnabled]
     * but the capture pipeline is fully torn down on disable because the
     * MediaProjection token is single-use — a paused screen sender would hold
     * a dead projection, so we use [PeerConnection.removeTrack] (which
     * renegotiates) instead of pausing the track.
     *
     * @param resultData the Intent returned by MediaProjectionManager consent
     *                  dialog; required and only read when [enabled] is true.
     */
    fun setScreenEnabled(enabled: Boolean, resultData: Intent? = null) {
        if (enabled) {
            ensureScreenTrack(resultData ?: return)
            val track = localScreenTrack ?: run {
                Log.e("VidlyScreen", "setScreenEnabled: localScreenTrack is null after ensureScreenTrack")
                return
            }
            Log.i("VidlyScreen", "setScreenEnabled(true): adding screen track to ${peers.size} peers")
            peers.values.forEach { peer ->
                if (peer.screenSender == null) {
                    peer.screenSender = peer.pc.addTrack(track, listOf(LOCAL_STREAM_ID))
                    Log.i("VidlyScreen", "  added screenSender to peer ${peer.id}, renegotiation should fire")
                }
            }
            screenLive = true
            status("Screen share on")
        } else {
            Log.i("VidlyScreen", "setScreenEnabled(false): stopping screen")
            stopScreen()
            screenLive = false
            peers.values.forEach { peer ->
                peer.screenSender?.let { runCatching { peer.pc.removeTrack(it) } }
                peer.screenSender = null
            }
            status("Screen share off")
        }
        broadcastMediaState()
    }

    fun isScreenLive() = screenLive

    /**
     * Build the screen capture pipeline: ScreenCapturerAndroid →
     * createVideoSource(isScreencast=true) → VideoTrack "vidly-screen".
     * Captures at the display's real resolution scaled to long-edge ≤ 1280,
     * rounded to even dimensions (encoder-friendly). No beauty filter and no
     * local renderer sink — the screen is the remote's to view.
     */
    private fun ensureScreenTrack(resultData: Intent) {
        if (localScreenTrack != null) return
        val (width, height) = computeScreenCaptureSize()
        Log.i("VidlyScreen", "ensureScreenTrack: capture size ${width}x${height}")
        screenSurfaceHelper = SurfaceTextureHelper.create("VidlyScreenThread", eglBase.eglBaseContext)
        // MediaProjection.Callback.onStop fires on the capture/SurfaceTexture
        // helper thread, so marshal to main before touching UI / calling
        // stopScreen — matches how the camera path mutates capture objects.
        screenCapturer = ScreenCapturerAndroid(resultData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w("VidlyScreen", "MediaProjection.Callback.onStop — projection revoked/stopped")
                Handler(Looper.getMainLooper()).post {
                    stopScreen()
                    screenLive = false
                    peers.values.forEach { peer ->
                        peer.screenSender?.let { runCatching { peer.pc.removeTrack(it) } }
                        peer.screenSender = null
                    }
                    broadcastMediaState()
                    onScreenShareStopped()
                }
            }
        })
        screenSource = factory.createVideoSource(true)
        screenCapturer?.initialize(screenSurfaceHelper, context, screenSource!!.capturerObserver)
        runCatching { screenCapturer?.startCapture(width, height, 15) }
            .onFailure {
                Log.e("VidlyScreen", "startCapture FAILED", it)
                status("Screen capture failed: ${it.localizedMessage ?: "unknown error"}")
            }
            .onSuccess { Log.i("VidlyScreen", "startCapture OK") }
        localScreenTrack = factory.createVideoTrack(LOCAL_SCREEN_TRACK_ID, screenSource).apply {
            setEnabled(true)
        }
        Log.i("VidlyScreen", "ensureScreenTrack: track created, capturer started=${screenCapturer != null}")
    }

    /**
     * Read the physical display size and scale to long-edge ≤ 1280 preserving
     * aspect ratio, rounding both dimensions to even numbers.
     */
    private fun computeScreenCaptureSize(): Pair<Int, Int> {
        val point = Point()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (wm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            point.set(wm.currentWindowMetrics.bounds.width(), wm.currentWindowMetrics.bounds.height())
        } else {
            @Suppress("DEPRECATION")
            wm?.defaultDisplay?.getRealSize(point)
        }
        var w = point.x.coerceAtLeast(1)
        var h = point.y.coerceAtLeast(1)
        val longEdge = maxOf(w, h)
        val cap = 1280
        if (longEdge > cap) {
            val scale = cap.toFloat() / longEdge
            w = (w * scale).toInt()
            h = (h * scale).toInt()
        }
        // Even dimensions (encoder-friendly).
        w -= w % 2
        h -= h % 2
        if (w < 2) w = 2
        if (h < 2) h = 2
        return w to h
    }

    /**
     * Tear down the screen capture pipeline. Safe to call when not set up.
     * [ScreenCapturerAndroid.stopCapture] releases the VirtualDisplay and the
     * MediaProjection token internally.
     */
    private fun stopScreen() {
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose()
        screenCapturer = null
        localScreenTrack?.dispose()
        localScreenTrack = null
        screenSource?.dispose()
        screenSource = null
        screenSurfaceHelper?.dispose()
        screenSurfaceHelper = null
    }

    fun switchCamera() {
        val activeCapturer = capturer
        if (activeCapturer == null || localVideoTrack == null) {
            status("Camera is off")
            return
        }
        // Cycle strictly between the front and main-back device. We don't use
        // the no-arg switchCamera() because on multi-camera devices it cycles
        // through every back lens (wide/tele/...), which all look the same and
        // confuse users. If we somehow have only one device (or the names
        // weren't resolved), fall back to the no-arg switch.
        val target = if (isFrontCamera) backDeviceName else frontDeviceName
        val handler = object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                this@NativeWebRtcClient.isFrontCamera = isFrontCamera
                applyLocalMirror()
                status(if (isFrontCamera) "Front camera" else "Back camera")
            }

            override fun onCameraSwitchError(error: String) {
                status("Camera switch failed: $error")
            }
        }
        if (target != null) {
            activeCapturer.switchCamera(handler, target)
        } else {
            activeCapturer.switchCamera(handler)
        }
    }

    fun close() {
        peers.values.forEach { it.close(main) }
        peers.clear()
        stopVideo()
        stopAudio()
        stopScreen()
        cameraLive = false
        micLive = false
        screenLive = false
        clearRemoteVideo()
    }

    fun dispose() {
        close()
        surfaceHelper?.dispose()
        screenSurfaceHelper?.dispose()
        localRenderer.release()
        remoteRenderer.release()
        cameraRenderer?.release()
        if (::factory.isInitialized) factory.dispose()
        audioDeviceModule?.release()
        audioDeviceModule = null
        eglBase.release()
    }

    fun setPreferredOutputDevice(device: AudioDeviceInfo?) {
        val adm = audioDeviceModule ?: return
        try {
            val audioOutputField = adm.javaClass.getDeclaredField("audioOutput")
            audioOutputField.isAccessible = true
            val audioOutput = audioOutputField.get(adm) ?: return

            val audioTrackField = audioOutput.javaClass.getDeclaredField("audioTrack")
            audioTrackField.isAccessible = true
            val audioTrack = audioTrackField.get(audioOutput) as? android.media.AudioTrack
            audioTrack?.preferredDevice = device
        } catch (e: Exception) {
            Log.w("VidlyAudio", "setPreferredOutputDevice failed", e)
        }
    }

    // ─── DataChannel send API ─────────────────────────────────

    /** Broadcast JSON message on the 'chat' channel to all peers. */
    fun sendChatJson(payload: JSONObject) {
        val data = payload.toString().toByteArray(Charsets.UTF_8)
        for (peer in peers.values) {
            val dc = peer.chatChannel ?: continue
            if (dc.state() != DataChannel.State.OPEN) continue
            runCatching { dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), false)) }
        }
    }

    /** Send JSON 'chat' message to a single peer. */
    fun sendChatJsonTo(peerId: String, payload: JSONObject): Boolean {
        val peer = peers[peerId] ?: return false
        val dc = peer.chatChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        val data = payload.toString().toByteArray(Charsets.UTF_8)
        return runCatching {
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), false))
            true
        }.getOrDefault(false)
    }

    /** Broadcast our current mic/camera/screen live state so peers can show status indicators. */
    fun broadcastMediaState() {
        val payload = JSONObject()
            .put("type", "media-state")
            .put("mic", micLive)
            .put("cam", cameraLive)
            .put("video", cameraLive)
            .put("screen", screenLive)
        sendChatJson(payload)
    }

    /** Send a binary file chunk to a single peer on the 'file' channel. */
    fun sendFileChunk(peerId: String, bytes: ByteArray): Boolean {
        val peer = peers[peerId] ?: return false
        val dc = peer.fileChannel ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return runCatching {
            dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
            true
        }.getOrDefault(false)
    }

    fun fileChannelBufferedAmount(peerId: String): Long {
        val peer = peers[peerId] ?: return 0
        return peer.fileChannel?.bufferedAmount() ?: 0
    }

    /**
     * Block until the file channel's bufferedAmount drops to [threshold] or below,
     * up to [timeoutMs] milliseconds. Event-driven via DataChannel.Observer (no
     * fixed-interval polling). Returns true if the threshold was met, false if the
     * timeout fired, the channel closed, or the peer is gone.
     */
    fun waitForFileChannelDrain(peerId: String, threshold: Long, timeoutMs: Long): Boolean {
        val peer = peers[peerId] ?: return false
        val dc = peer.fileChannel ?: return false
        synchronized(peer.fileChannelLock) {
            if (dc.state() != DataChannel.State.OPEN) return false
            if (dc.bufferedAmount() <= threshold) return true
            try {
                peer.fileChannelLock.wait(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (dc.state() != DataChannel.State.OPEN) return false
            return dc.bufferedAmount() <= threshold
        }
    }

    fun knownPeers(): List<Pair<String, String?>> =
        peers.values.map { it.id to it.username }

    fun usernameFor(peerId: String): String? = peers[peerId]?.username

    // ─── Internal media setup ─────────────────────────────────

    private fun ensureVideoTrack() {
        if (localVideoTrack != null) return
        val enumerator = Camera2Enumerator(context)
        val (chosen, frontFacing) = createCameraCapturer(enumerator)
        capturer = chosen
        if (capturer == null) {
            status("No camera available")
            return
        }
        // Reflect the actual device we ended up with (createCameraCapturer
        // falls back to the first available camera if no front-facing one
        // exists), then keep the local preview mirror in sync.
        isFrontCamera = frontFacing
        applyLocalMirror()
        surfaceHelper = SurfaceTextureHelper.create("VidlyCameraThread", eglBase.eglBaseContext)
        videoSource = factory.createVideoSource(false).apply {
            setVideoProcessor(beautyProcessor)
            capturer?.initialize(surfaceHelper, context, capturerObserver)
        }
        runCatching { capturer?.startCapture(1280, 720, 30) }
            .onFailure { status("Camera failed: ${it.localizedMessage ?: "unknown error"}") }
        localVideoTrack = factory.createVideoTrack("vidly-video", videoSource).apply {
            setEnabled(true)
            addSink(localRenderer)
        }
    }

    private fun ensureAudioTrack() {
        if (localAudioTrack != null) return
        audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("vidly-audio", audioSource).apply { setEnabled(true) }
    }

    private fun stopVideo() {
        localVideoTrack?.removeSink(localRenderer)
        localVideoTrack?.dispose()
        localVideoTrack = null
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        videoSource?.dispose()
        videoSource = null
        surfaceHelper?.dispose()
        surfaceHelper = null
        localRenderer.clearImage()
    }

    /**
     * Pause the local camera without disposing the track/capturer/source/sender.
     * Keeps the same VideoTrack on every peer's RtpSender so resuming does not
     * require track replacement (which would not re-fire pc.ontrack on the
     * remote receiver). Safe to call when video is not currently set up.
     */
    private fun pauseVideo() {
        localVideoTrack?.setEnabled(false)
        runCatching { capturer?.stopCapture() }
    }

    /**
     * Resume the local camera after [pauseVideo]. Restarts capture on the
     * existing capturer and re-enables the track. Falls back to a full pipeline
     * rebuild if the capturer is gone or fails to restart; in that case the new
     * track is reattached to any existing RtpSender via [RtpSender.setTrack]
     * so the remote transceiver (and the receiver's MediaStreamTrack) stays
     * the same and we avoid an SDP renegotiation we don't need.
     */
    private fun resumeVideo() {
        capturer?.let { activeCapturer ->
            runCatching { activeCapturer.startCapture(1280, 720, 30) }
                .onSuccess {
                    localVideoTrack?.setEnabled(true)
                    return
                }
                .onFailure { status("Camera restart failed, recreating: ${it.localizedMessage}") }
        }

        // Snapshot sender state BEFORE tearing things down. `cameraLive` is
        // already false at this point (setCameraEnabled sets it after us),
        // so use sender presence instead to decide whether to reattach.
        val hadVideoSender = peers.values.any { it.videoSender != null }

        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        videoSource?.dispose()
        videoSource = null
        surfaceHelper?.dispose()
        surfaceHelper = null
        localVideoTrack?.removeSink(localRenderer)
        localVideoTrack?.dispose()
        localVideoTrack = null
        ensureVideoTrack()

        val track = localVideoTrack ?: return
        track.setEnabled(true)
        if (hadVideoSender) {
            peers.values.forEach { peer ->
                peer.videoSender?.setTrack(track, false)
            }
        }
    }

    private fun clearRemoteVideo() {
        detachRenderedRemoteVideo()
        detachRenderedRemoteCamera()
        remoteCameraTrack?.removeSink(remoteVideoFrameSink)
        remoteCameraTrack?.removeSink(remoteRenderer)
        cameraRenderer?.let { remoteCameraTrack?.removeSink(it) }
        remoteScreenTrack?.removeSink(remoteVideoFrameSink)
        remoteScreenTrack?.removeSink(remoteRenderer)
        remoteCameraTrack = null
        remoteScreenTrack = null
        remoteCameraPaused = false
        remoteScreenPaused = false
        activeVideoTrack = null
        remoteVideoPeerId = null
        remoteVideoFrameTimestampMs = 0L
        remoteRenderer.clearImage()
        cameraRenderer?.clearImage()
        onRemoteVideo(false)
        onRemoteCamera(false)
    }

    private fun detachRenderedRemoteVideo() {
        renderedRemoteVideoTrack?.removeSink(remoteVideoFrameSink)
        renderedRemoteVideoTrack?.removeSink(remoteRenderer)
        renderedRemoteVideoTrack = null
    }

    private fun detachRenderedRemoteCamera() {
        renderedRemoteCameraTrack?.removeSink(remoteVideoFrameSink)
        cameraRenderer?.let { renderedRemoteCameraTrack?.removeSink(it) }
        renderedRemoteCameraTrack = null
    }

    private fun updateRenderedRemoteVideo() {
        val track = if (remoteScreenPaused) null else remoteScreenTrack
        if (renderedRemoteVideoTrack === track) return
        detachRenderedRemoteVideo()
        renderedRemoteVideoTrack = track
        if (track == null) {
            remoteRenderer.clearImage()
            onRemoteVideo(false)
            if (remoteCameraTrack == null && remoteScreenTrack == null) remoteVideoPeerId = null
            return
        }
        track.addSink(remoteVideoFrameSink)
        // Start the frame-age clock at attach time: 0 would read as "stalled
        // forever" and flash the poor-network overlay before the first frame.
        remoteVideoFrameTimestampMs = SystemClock.elapsedRealtime()
        track.addSink(remoteRenderer)
        onRemoteVideo(true)
        status("Remote screen connected")
    }

    private fun updateRenderedRemoteCamera() {
        val track = if (remoteCameraPaused) null else remoteCameraTrack
        if (renderedRemoteCameraTrack === track) return
        detachRenderedRemoteCamera()
        renderedRemoteCameraTrack = track
        if (track == null) {
            cameraRenderer?.clearImage()
            onRemoteCamera(false)
            if (remoteCameraTrack == null && remoteScreenTrack == null) remoteVideoPeerId = null
            return
        }
        // The frame watchdog sink rides on the camera track too — this is what
        // feeds the "Paused - poor network" overlay for ordinary camera video
        // (the screen role is exempted from stall checks in checkFrameHealth).
        track.addSink(remoteVideoFrameSink)
        remoteVideoFrameTimestampMs = SystemClock.elapsedRealtime()
        cameraRenderer?.let { track.addSink(it) }
        onRemoteCamera(true)
        status("Remote camera connected")
    }

    private fun removeRemoteVideoTrack(track: VideoTrack) {
        var cameraChanged = false
        var screenChanged = false
        if (remoteCameraTrack === track) {
            remoteCameraTrack = null
            remoteCameraPaused = false
            cameraChanged = true
        }
        if (remoteScreenTrack === track) {
            remoteScreenTrack = null
            remoteScreenPaused = false
            screenChanged = true
        }
        if (activeVideoTrack === track) activeVideoTrack = null
        if (screenChanged) updateRenderedRemoteVideo()
        if (cameraChanged) updateRenderedRemoteCamera()
    }

    private fun assignRemoteVideoTrack(peer: Peer, track: VideoTrack) {
        if (remoteCameraTrack === track || remoteScreenTrack === track) return
        if (peer.remoteScreenLive && !peer.remoteCameraLive) {
            if (remoteScreenTrack == null) remoteScreenTrack = track else remoteCameraTrack = track
        } else if (peer.remoteScreenLive && peer.remoteCameraLive) {
            if (remoteCameraTrack == null) remoteCameraTrack = track else remoteScreenTrack = track
        } else {
            if (remoteCameraTrack == null) remoteCameraTrack = track else remoteScreenTrack = track
        }
        updateRenderedRemoteVideo()
        updateRenderedRemoteCamera()
    }

    //
    // Re-bind remote video tracks to the correct role when media-state arrives
    // after onTrack. In Unified Plan the remote track id() is regenerated by
    // the receiver and CANNOT be used to classify (W3C PSA, w3c/webrtc-pc#1718),
    // so we mirror the web client (index.html ~L2344-2364): use the media-state
    // booleans as the authoritative signal and reassign the live video
    // receiver tracks accordingly.
    //
    // Critical for the case where screen is shared with the camera OFF: the
    // single screen track arrives before media-state, assignRemoteVideoTrack
    // defaults it to remoteCameraTrack (arrival-order), and it would render in
    // the tiny PiP tile forever. This re-binds it to remoteScreenTrack once the
    // screen=true media-state arrives. Symmetric for the camera role.
    private fun reclassifyRemoteVideoTracks(peer: Peer) {
        if (remoteVideoPeerId != peer.id) return
        val wantCamera = peer.remoteCameraLive
        val wantScreen = peer.remoteScreenLive

        // Only MOVE a track that is already bound to the wrong role — never grab
        // an arbitrary live receiver. A muted/stale camera receiver is still
        // LIVE (the sender keeps its RtpSender through a camera off→on), so
        // "first live receiver" could mis-bind that stale camera to the screen
        // role in a camera-on→off→screen-share flow. The move is therefore
        // gated by [activeVideoTrack]: the real screen track reliably fires
        // onFirstPacketReceived (which sets activeVideoTrack), so the move only
        // fires once frames actually flow — a muted stale camera never does.
        //
        // The §3g case (single screen track, camera off, onTrack before
        // media-state mis-binds it to remoteCameraTrack) is fixed here once the
        // screen's first packet sets activeVideoTrack === remoteCameraTrack.
        if (wantScreen && !wantCamera && remoteScreenTrack == null && remoteCameraTrack != null
            && activeVideoTrack === remoteCameraTrack) {
            moveTrackToScreenRole(remoteCameraTrack!!)
        } else if (wantCamera && !wantScreen && remoteCameraTrack == null && remoteScreenTrack != null
            && activeVideoTrack === remoteScreenTrack) {
            moveTrackToCameraRole(remoteScreenTrack!!)
        }
        // When both roles are wanted but tracks are swapped we cannot
        // disambiguate without track ids (regenerated in Unified Plan), so we
        // leave the arrival-order binding — matching the web client.
    }

    private fun moveTrackToScreenRole(track: VideoTrack) {
        if (renderedRemoteCameraTrack === track) detachRenderedRemoteCamera()
        detachTrackFully(track)
        remoteCameraTrack = null
        remoteScreenTrack = track
    }

    private fun moveTrackToCameraRole(track: VideoTrack) {
        if (renderedRemoteVideoTrack === track) detachRenderedRemoteVideo()
        detachTrackFully(track)
        remoteScreenTrack = null
        remoteCameraTrack = track
    }

    // Remove every sink this client attaches to a remote video track (frame
    // watchdog, main renderer, PiP camera renderer). Used when a track moves
    // between roles so the next updateRendered* starts from a clean slate.
    private fun detachTrackFully(track: VideoTrack?) {
        track?.removeSink(remoteVideoFrameSink)
        track?.removeSink(remoteRenderer)
        cameraRenderer?.let { track?.removeSink(it) }
    }

    // Re-attach the renderer for a remote video track when the remote resumes
    // sending. Called from RtpReceiver.Observer.onFirstPacketReceived — the
    // Android equivalent of the browser's track.onunmute (which the web client
    // also uses, index.html ~line 1921). The remote keeps its RtpSender through
    // a camera off→on, so the cached VideoTrack stays LIVE (no onRemoveTrack,
    // no pc.onTrack refire); onFirstPacketReceived is the reliable "frames are
    // flowing again" signal that lets us recover the display.
    //
    // It ALSO drives role reclassification: a track producing frames is the
    // authoritative "alive" signal, so we record it as [activeVideoTrack] and
    // let reclassifyRemoteVideoTracks move it to the correct role if media-state
    // says it was mis-bound (the §3g screen-share-with-camera-off case).
    private fun onRemoteVideoFirstPacket(track: VideoTrack, peer: Peer) {
        if (remoteVideoPeerId != peer.id) return
        activeVideoTrack = track
        reclassifyRemoteVideoTracks(peer)
        updateRenderedRemoteCamera()
        updateRenderedRemoteVideo()
    }

    private fun stopAudio() {
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
    }

    // Returns the capturer plus whether it is front-facing. The fallback
    // (non-front) branch is marked frontFacing=false so the caller can keep
    // the local mirror in sync.
    // Enumerates cameras, picks the front + main-back pair to cycle between,
    // and creates a capturer for the front (preferred initial device). Also
    // populates frontDeviceName/backDeviceName for switchCamera(). Returns the
    // capturer and whether it is front-facing.
    //
    // "main" back camera: devices with multiple back lenses (main/wide/tele)
    // expose several back-facing IDs that all look similar on a video call.
    // We pick the back camera whose focal length is closest to a standard
    // phone main lens (~4mm) — this skips ultrawide (~2mm or less) and tele
    // (~6mm+). Falls back to the first back camera if focal-length data is
    // unavailable. Fully device-agnostic; no hardcoded device IDs.
    private fun createCameraCapturer(enumerator: CameraEnumerator): Pair<CameraVideoCapturer?, Boolean> {
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
        val backs = enumerator.deviceNames.filter { enumerator.isBackFacing(it) }
        val mainBack = backs.firstOrNull()?.let { pickMainBackCamera(backs) } ?: backs.firstOrNull()
        frontDeviceName = front
        backDeviceName = mainBack

        val chosen = front ?: mainBack
        if (chosen == null) return null to false
        return enumerator.createCapturer(chosen, null) to (chosen == front)
    }

    // Among a set of back-facing device names, returns the one most likely to
    // be the standard "main" rear lens. Strategy:
    //   1. Read focal lengths via Camera2 CameraCharacteristics for each name.
    //      (Camera2Enumerator uses the cameraId as the device name, which maps
    //      directly to CameraManager.getCameraCharacteristics.)
    //   2. Score by distance of the (first) focal length from ~4mm; pick min.
    //   3. If no device yields focal-length data (non-Camera2 enumerator,
    //      permissions, etc.), fall back to the first back device.
    private fun pickMainBackCamera(backs: List<String>): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return backs.first()
        val candidates = mutableListOf<Pair<String, Float>>() // name -> |focal - 4.0|
        for (name in backs) {
            val focal = runCatching {
                manager.getCameraCharacteristics(name)
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()
            }.getOrNull()
            if (focal != null && focal > 0f) {
                candidates.add(name to kotlin.math.abs(focal - 4.0f))
            }
        }
        return candidates.minByOrNull { it.second }?.first ?: backs.first()
    }

    // Keeps the sender-side flip in sync with the active camera. Called at
    // capture start and after every camera switch.
    //
    // All renderers stay at setMirror(false) — the processor is the single
    // source of truth for orientation. Front camera: processor un-mirrors the
    // sensor frame, so both the encoded stream and the local preview (which
    // share the processor's output) see the correct orientation. Back camera:
    // processor passes through, still correct everywhere.
    private fun applyLocalMirror() {
        beautyProcessor.flipHorizontally = isFrontCamera
    }

    private fun ensurePeer(peerId: String, polite: Boolean): Peer {
        peers[peerId]?.let { return it }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = Peer(peerId, polite)
        val pc = factory.createPeerConnection(rtcConfig, PeerObserver(peer)) ?: error("PeerConnection failed")
        peer.pc = pc

        // Mirror web client behavior: only attach tracks via addTrack when we have them.
        // This way SDP m-lines reflect actual sending tracks. addTrack triggers
        // onRenegotiationNeeded which initiates a renegotiation when state allows.
        if (localAudioTrack != null && micLive) {
            peer.audioSender = pc.addTrack(localAudioTrack, listOf(LOCAL_STREAM_ID))
        }
        if (localVideoTrack != null && cameraLive) {
            peer.videoSender = pc.addTrack(localVideoTrack, listOf(LOCAL_STREAM_ID))
        }
        if (localScreenTrack != null && screenLive) {
            peer.screenSender = pc.addTrack(localScreenTrack, listOf(LOCAL_STREAM_ID))
        }
        peers[peerId] = peer
        return peer
    }

    private fun makeOffer(peer: Peer) {
        if (peer.makingOffer) return
        // Don't make a fresh offer while signaling state is unstable.
        if (peer.pc.signalingState() != PeerConnection.SignalingState.STABLE) return
        peer.makingOffer = true
        peer.pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                main.post {
                    if (!peer.makingOffer) return@post
                    if (peer.pc.signalingState() != PeerConnection.SignalingState.STABLE) {
                        peer.makingOffer = false
                        return@post
                    }
                    setLocalAndSend(peer, desc)
                }
            }

            override fun onCreateFailure(error: String) {
                main.post {
                    peer.makingOffer = false
                    status("Offer failed: $error")
                }
            }
        }, MediaConstraints())
    }

    private fun restartIce(peer: Peer) {
        try { peer.pc.restartIce() } catch (_: Throwable) {}
        makeOffer(peer)
    }

    private fun scheduleIceRestart(peer: Peer, delayMs: Long) {
        main.removeCallbacksAndMessages(peer)
        val runnable = Runnable {
            if (peer.pc.iceConnectionState() == PeerConnection.IceConnectionState.DISCONNECTED ||
                peer.pc.iceConnectionState() == PeerConnection.IceConnectionState.FAILED) {
                restartIce(peer)
            }
        }
        // postAtTime: the token overload of postDelayed needs API 28, minSdk is 26.
        main.postAtTime(runnable, peer, SystemClock.uptimeMillis() + delayMs)
    }

    private fun cancelPendingIceRestart(peer: Peer) {
        main.removeCallbacksAndMessages(peer)
    }

    // ─── Stuck-offer watchdog ─────────────────────────────────
    //
    // Signaling messages can be lost without either side noticing: the WS may
    // be silently dead (send() buffers into a dying TCP connection) or down
    // entirely while WebRTC emits an offer (e.g. the ICE-restart path). A lost
    // offer strands us in HAVE_LOCAL_OFFER — and an impolite peer in that
    // state ignores every incoming offer, deadlocking negotiation until the
    // call is torn down. If the state hasn't resolved in OFFER_TIMEOUT_MS,
    // roll back to STABLE and offer again. Cancelled on any successful
    // setRemoteDescription (answer applied / offer accepted).

    private fun scheduleOfferTimeout(peer: Peer) {
        cancelOfferTimeout(peer)
        val runnable = Runnable {
            peer.offerTimeout = null
            if (peers[peer.id] !== peer) return@Runnable
            if (peer.pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return@Runnable
            status("Renegotiating...")
            peer.makingOffer = false
            peer.pc.setLocalDescription(object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    main.post { makeOffer(peer) }
                }
            }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
        }
        peer.offerTimeout = runnable
        main.postDelayed(runnable, OFFER_TIMEOUT_MS)
    }

    private fun cancelOfferTimeout(peer: Peer) {
        peer.offerTimeout?.let { main.removeCallbacks(it) }
        peer.offerTimeout = null
    }

    private fun makeAnswer(peer: Peer) {
        peer.pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                main.post { setLocalAndSend(peer, desc) }
            }

            override fun onCreateFailure(error: String) {
                main.post { status("Answer failed: $error") }
            }
        }, MediaConstraints())
    }

    private fun setLocalAndSend(peer: Peer, desc: SessionDescription) {
        peer.pc.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                main.post {
                    peer.makingOffer = false
                    signaling.sendSignal(peer.id, JSONObject().put("description", desc.toJson()))
                    if (desc.type == SessionDescription.Type.OFFER) scheduleOfferTimeout(peer)
                }
            }

            override fun onSetFailure(error: String) {
                main.post {
                    peer.makingOffer = false
                    status("Local SDP failed: $error")
                }
            }
        }, desc)
    }

    private fun handleDescription(peer: Peer, json: JSONObject) {
        val type = SessionDescription.Type.fromCanonicalForm(json.optString("type"))
        val desc = SessionDescription(type, json.optString("sdp"))
        val offerCollision = type == SessionDescription.Type.OFFER &&
            (peer.makingOffer || peer.pc.signalingState() != PeerConnection.SignalingState.STABLE)
        peer.ignoreOffer = !peer.polite && offerCollision
        if (peer.ignoreOffer) return

        if (type == SessionDescription.Type.OFFER && offerCollision) {
            peer.makingOffer = false
            if (peer.pc.signalingState() != PeerConnection.SignalingState.STABLE) {
                cancelOfferTimeout(peer)
                peer.pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        main.post { setRemoteDescription(peer, desc, type) }
                    }

                    override fun onSetFailure(error: String) {
                        main.post { status("Rollback failed: $error") }
                    }
                }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
                return
            }
        }

        setRemoteDescription(peer, desc, type)
    }

    private fun setRemoteDescription(
        peer: Peer,
        desc: SessionDescription,
        type: SessionDescription.Type
    ) {
        peer.pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                main.post {
                    // The pending-offer state resolved (their answer applied,
                    // or their offer accepted) — the watchdog can stand down.
                    cancelOfferTimeout(peer)
                    if (type == SessionDescription.Type.OFFER) makeAnswer(peer)
                }
            }

            override fun onSetFailure(error: String) {
                main.post { status("Remote SDP failed: $error") }
            }
        }, desc)
    }

    private fun SessionDescription.toJson(): JSONObject {
        return JSONObject().put("type", type.canonicalForm()).put("sdp", description)
    }

    // ─── DataChannel attachment ───────────────────────────────

    private fun attachChatChannel(peer: Peer, dc: DataChannel) {
        peer.chatChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) = Unit
            override fun onStateChange() {
                // When the chat channel opens, push our current media state.
                // Marshalled to main: the live flags are main-owned state.
                if (dc.state() != DataChannel.State.OPEN) return
                main.post {
                    val payload = JSONObject()
                        .put("type", "media-state")
                        .put("mic", micLive)
                        .put("cam", cameraLive)
                        .put("video", cameraLive)
                        .put("screen", screenLive)
                    runCatching {
                        val data = payload.toString().toByteArray(Charsets.UTF_8)
                        dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), false))
                    }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                // Copy on the DataChannel thread (the buffer is only valid for
                // the duration of the callback), then handle on main — this
                // block mutates peer/track-role state that main owns.
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                main.post { handleChatMessage(bytes) }
            }

            private fun handleChatMessage(bytes: ByteArray) {
                val text = String(bytes, Charsets.UTF_8)
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val sender = json.optString("username").takeIf { it.isNotBlank() }
                if (sender != null) peer.username = sender
                if (json.optString("type") == "media-state") {
                    peer.remoteCameraLive = json.optBoolean("cam", false)
                    peer.remoteScreenLive = json.optBoolean("screen", false)
                    // Always sync pause state — media-state can arrive before
                    // onTrack sets remoteVideoPeerId, and we must not lose the
                    // pause state. Rendering ops still need the peer-id guard.
                    remoteCameraPaused = !peer.remoteCameraLive
                    remoteScreenPaused = !peer.remoteScreenLive
                    // A role going false mutes its receiver (kept cached, no
                    // frames). Drop it from the active-track cache so a later
                    // reclassify never treats the muted (but still LIVE)
                    // receiver as the screen candidate.
                    if (!peer.remoteCameraLive && activeVideoTrack === remoteCameraTrack) activeVideoTrack = null
                    if (!peer.remoteScreenLive && activeVideoTrack === remoteScreenTrack) activeVideoTrack = null
                    if (remoteVideoPeerId == peer.id) {
                        reclassifyRemoteVideoTracks(peer)
                        // Force re-attach on resume: updateRendered* short-circuits
                        // when renderedTrack === track (same LIVE object reused
                        // through a pause), so detach first to ensure a fresh
                        // addSink and resume rendering.
                        if (peer.remoteCameraLive) detachRenderedRemoteCamera()
                        if (peer.remoteScreenLive) detachRenderedRemoteVideo()
                        updateRenderedRemoteCamera()
                        updateRenderedRemoteVideo()
                    }
                }
                dataListener?.onChatMessage(peer.id, sender ?: peer.username, json)
            }
        })
    }

    private fun attachFileChannel(peer: Peer, dc: DataChannel) {
        peer.fileChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) {
                // Wake any sender thread waiting for the SCTP queue to drain.
                synchronized(peer.fileChannelLock) {
                    peer.fileChannelLock.notifyAll()
                }
            }
            override fun onStateChange() {
                // State transitions (e.g. CLOSING) should release a stuck sender.
                synchronized(peer.fileChannelLock) {
                    peer.fileChannelLock.notifyAll()
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                dataListener?.onFileBinary(peer.id, bytes)
            }
        })
    }

    // ─── Peer Observer ────────────────────────────────────────

    // All callbacks are marshalled onto [main] — WebRTC invokes them on its
    // internal signaling/worker threads, and everything they touch (negotiation
    // flags, the peers map, remote track roles, renderers) is main-owned.
    private inner class PeerObserver(private val peer: Peer) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            main.post {
                signaling.sendSignal(
                    peer.id,
                    JSONObject().put(
                        "candidate",
                        JSONObject()
                            .put("candidate", candidate.sdp)
                            .put("sdpMid", candidate.sdpMid)
                            .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    )
                )
            }
        }

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
            val receiver = transceiver?.receiver ?: return
            val track = receiver.track()
            if (track is VideoTrack) {
                main.post {
                    if (peers[peer.id] !== peer) return@post
                    val activePeerId = remoteVideoPeerId
                    if (activePeerId != null && activePeerId != peer.id) return@post
                    remoteVideoPeerId = peer.id
                    assignRemoteVideoTrack(peer, track)
                    // Register a receiver observer so that when the remote resumes
                    // sending after a camera off→on (track stays LIVE but goes
                    // muted — no onRemoveTrack, pc.onTrack does not refire), we get
                    // onFirstPacketReceived as the "unmute" signal and can recover
                    // the display. Web clients get this via track.onunmute; the
                    // Android WebRTC API exposes it as RtpReceiver.Observer instead.
                    receiver.SetObserver(object : RtpReceiver.Observer {
                        override fun onFirstPacketReceived(mediaType: MediaStreamTrack.MediaType) {
                            if (mediaType != MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) return
                            main.post { onRemoteVideoFirstPacket(track, peer) }
                        }
                    })
                }
            }
        }

        override fun onRemoveTrack(receiver: org.webrtc.RtpReceiver?) {
            val track = receiver?.track()
            if (track is VideoTrack) {
                main.post {
                    if (remoteVideoPeerId == peer.id) removeRemoteVideoTrack(track)
                }
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            main.post {
                if (peers[peer.id] !== peer) return@post
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        cancelPendingIceRestart(peer)
                        status("Connected")
                        dataListener?.onPeerConnectionStateChanged(peer.id, true)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        status("Reconnecting...")
                        scheduleIceRestart(peer, ICE_RESTART_DELAY_MS)
                        dataListener?.onPeerConnectionStateChanged(peer.id, false)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        status("ICE failed")
                        cancelPendingIceRestart(peer)
                        restartIce(peer)
                        dataListener?.onPeerConnectionStateChanged(peer.id, false)
                    }
                    else -> Unit
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit

        override fun onDataChannel(channel: DataChannel?) {
            val dc = channel ?: return
            main.post {
                when (dc.label()) {
                    "file" -> attachFileChannel(peer, dc)
                    else -> attachChatChannel(peer, dc)
                }
            }
        }

        override fun onRenegotiationNeeded() {
            // Perfect-negotiation pattern: any side may need to renegotiate when
            // tracks are added/removed. Glare is handled in handleDescription.
            main.post {
                if (peers[peer.id] === peer) makeOffer(peer)
            }
        }

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        override fun onSelectedCandidatePairChanged(event: org.webrtc.CandidatePairChangeEvent?) = Unit
    }

    private abstract class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    private class Peer(val id: String, val polite: Boolean) {
        lateinit var pc: PeerConnection
        var username: String? = null
        var makingOffer = false
        var ignoreOffer = false
        var audioSender: RtpSender? = null
        var videoSender: RtpSender? = null
        var screenSender: RtpSender? = null
        var remoteCameraLive = false
        var remoteScreenLive = false
        var chatChannel: DataChannel? = null
        var fileChannel: DataChannel? = null
        // Pending stuck-offer watchdog (see scheduleOfferTimeout).
        var offerTimeout: Runnable? = null
        // Monitor for event-driven backpressure on the file channel. Woken by
        // DataChannel.Observer.onBufferedAmountChange so the sender thread doesn't
        // have to poll bufferedAmount() with Thread.sleep().
        val fileChannelLock: java.lang.Object = java.lang.Object()

        fun close(main: Handler) {
            main.removeCallbacksAndMessages(this)
            offerTimeout?.let { main.removeCallbacks(it) }
            offerTimeout = null
            runCatching { chatChannel?.close() }
            runCatching { fileChannel?.close() }
            runCatching { pc.close() }
        }
    }

    private companion object {
        const val LOCAL_STREAM_ID = "vidly-native"
        const val LOCAL_SCREEN_TRACK_ID = "vidly-screen"
        const val ICE_RESTART_DELAY_MS = 8_000L
        // How long a sent offer may sit unanswered (HAVE_LOCAL_OFFER) before
        // the stuck-offer watchdog rolls back and re-offers. Generous enough
        // for weak-network RTTs + SDP processing; short enough to unstick a
        // deadlocked negotiation within one "why is this call dead" moment.
        const val OFFER_TIMEOUT_MS = 10_000L
    }
}
