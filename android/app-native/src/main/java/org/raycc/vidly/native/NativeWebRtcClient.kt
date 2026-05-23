package org.raycc.vidly.native

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
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
    private val onRemoteVideo: (Boolean) -> Unit = {}
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
    private val iceServers = mutableListOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478").createIceServer()
    )

    private lateinit var factory: PeerConnectionFactory
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var capturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoPeerId: String? = null
    private var remoteCameraTrack: VideoTrack? = null
    private var remoteScreenTrack: VideoTrack? = null
    private var renderedRemoteVideoTrack: VideoTrack? = null
    @Volatile private var remoteVideoFrameTimestampMs: Long = 0L
    private val remoteVideoFrameSink = VideoSink {
        remoteVideoFrameTimestampMs = SystemClock.elapsedRealtime()
    }
    private var started = false

    // Logical state — does the local user want camera/mic to be sending?
    private var cameraLive = false
    private var micLive = false

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
        localRenderer.setMirror(true)
        remoteRenderer.setMirror(false)

        val adm = JavaAudioDeviceModule.builder(context).createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        adm.release()
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
        val peer = ensurePeer(peerInfo.peerId, polite = true)
        if (peerInfo.username != null) peer.username = peerInfo.username
    }

    fun onPeerLeft(peerId: String) {
        peers.remove(peerId)?.close()
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
        if (renderedRemoteVideoTrack == null) return Long.MAX_VALUE
        val lastFrameAt = remoteVideoFrameTimestampMs
        if (lastFrameAt == 0L) return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - lastFrameAt
    }

    fun restartIce() {
        peers.values.forEach { restartIce(it) }
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
        ensureVideoTrack()
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
            ensureVideoTrack()
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
        } else {
            // Detach but keep sender so we don't have to renegotiate next time on.
            peers.values.forEach { peer ->
                peer.videoSender?.setTrack(null, false)
            }
            stopVideo()
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

    fun close() {
        peers.values.forEach { it.close() }
        peers.clear()
        stopVideo()
        stopAudio()
        cameraLive = false
        micLive = false
        clearRemoteVideo()
    }

    fun dispose() {
        close()
        surfaceHelper?.dispose()
        localRenderer.release()
        remoteRenderer.release()
        if (::factory.isInitialized) factory.dispose()
        eglBase.release()
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

    /** Broadcast our current mic/camera live state so peers can show status indicators. */
    fun broadcastMediaState() {
        val payload = JSONObject()
            .put("type", "media-state")
            .put("mic", micLive)
            .put("cam", cameraLive)
            .put("video", cameraLive)
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

    fun knownPeers(): List<Pair<String, String?>> =
        peers.values.map { it.id to it.username }

    fun usernameFor(peerId: String): String? = peers[peerId]?.username

    // ─── Internal media setup ─────────────────────────────────

    private fun ensureVideoTrack() {
        if (localVideoTrack != null) return
        val enumerator = Camera2Enumerator(context)
        capturer = createCameraCapturer(enumerator)
        if (capturer == null) {
            status("No camera available")
            return
        }
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

    private fun clearRemoteVideo() {
        detachRenderedRemoteVideo()
        remoteCameraTrack?.removeSink(remoteVideoFrameSink)
        remoteCameraTrack?.removeSink(remoteRenderer)
        remoteScreenTrack?.removeSink(remoteVideoFrameSink)
        remoteScreenTrack?.removeSink(remoteRenderer)
        remoteCameraTrack = null
        remoteScreenTrack = null
        remoteVideoPeerId = null
        remoteVideoFrameTimestampMs = 0L
        remoteRenderer.clearImage()
        onRemoteVideo(false)
    }

    private fun detachRenderedRemoteVideo() {
        renderedRemoteVideoTrack?.removeSink(remoteVideoFrameSink)
        renderedRemoteVideoTrack?.removeSink(remoteRenderer)
        renderedRemoteVideoTrack = null
    }

    private fun updateRenderedRemoteVideo() {
        val track = remoteScreenTrack ?: remoteCameraTrack
        if (renderedRemoteVideoTrack === track) return
        detachRenderedRemoteVideo()
        remoteVideoFrameTimestampMs = 0L
        renderedRemoteVideoTrack = track
        if (track == null) {
            remoteRenderer.clearImage()
            onRemoteVideo(false)
            if (remoteCameraTrack == null && remoteScreenTrack == null) remoteVideoPeerId = null
            return
        }
        track.addSink(remoteVideoFrameSink)
        track.addSink(remoteRenderer)
        onRemoteVideo(true)
        status(if (track === remoteScreenTrack) "Remote screen connected" else "Remote video connected")
    }

    private fun removeRemoteVideoTrack(track: VideoTrack) {
        var changed = false
        if (remoteCameraTrack === track) {
            remoteCameraTrack = null
            changed = true
        }
        if (remoteScreenTrack === track) {
            remoteScreenTrack = null
            changed = true
        }
        if (changed) updateRenderedRemoteVideo()
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
    }

    private fun stopAudio() {
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
    }

    private fun createCameraCapturer(enumerator: CameraEnumerator): VideoCapturer? {
        enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }?.let {
            return enumerator.createCapturer(it, null)
        }
        enumerator.deviceNames.firstOrNull()?.let {
            return enumerator.createCapturer(it, null)
        }
        return null
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
                setLocalAndSend(peer, desc)
            }

            override fun onCreateFailure(error: String) {
                peer.makingOffer = false
                status("Offer failed: $error")
            }
        }, MediaConstraints())
    }

    private fun restartIce(peer: Peer) {
        try { peer.pc.restartIce() } catch (_: Throwable) {}
        makeOffer(peer)
    }

    private fun makeAnswer(peer: Peer) {
        peer.pc.createAnswer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(desc: SessionDescription) {
                setLocalAndSend(peer, desc)
            }

            override fun onCreateFailure(error: String) {
                status("Answer failed: $error")
            }
        }, MediaConstraints())
    }

    private fun setLocalAndSend(peer: Peer, desc: SessionDescription) {
        peer.pc.setLocalDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                peer.makingOffer = false
                signaling.sendSignal(peer.id, JSONObject().put("description", desc.toJson()))
            }

            override fun onSetFailure(error: String) {
                peer.makingOffer = false
                status("Local SDP failed: $error")
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

        peer.pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                if (type == SessionDescription.Type.OFFER) makeAnswer(peer)
            }

            override fun onSetFailure(error: String) {
                status("Remote SDP failed: $error")
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
                if (dc.state() == DataChannel.State.OPEN) {
                    val payload = JSONObject()
                        .put("type", "media-state")
                        .put("mic", micLive)
                        .put("cam", cameraLive)
                        .put("video", cameraLive)
                    runCatching {
                        val data = payload.toString().toByteArray(Charsets.UTF_8)
                        dc.send(DataChannel.Buffer(ByteBuffer.wrap(data), false))
                    }
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val sender = json.optString("username").takeIf { it.isNotBlank() }
                if (sender != null) peer.username = sender
                if (json.optString("type") == "media-state") {
                    peer.remoteCameraLive = json.optBoolean("cam", false)
                    peer.remoteScreenLive = json.optBoolean("screen", false)
                    val remoteVideoLive = if (json.has("video")) {
                        json.optBoolean("video", false)
                    } else {
                        peer.remoteCameraLive || peer.remoteScreenLive
                    }
                    if (!remoteVideoLive && remoteVideoPeerId == peer.id) {
                        clearRemoteVideo()
                    } else if (!peer.remoteScreenLive && remoteScreenTrack != null && remoteVideoPeerId == peer.id) {
                        removeRemoteVideoTrack(remoteScreenTrack!!)
                    }
                }
                dataListener?.onChatMessage(peer.id, sender ?: peer.username, json)
            }
        })
    }

    private fun attachFileChannel(peer: Peer, dc: DataChannel) {
        peer.fileChannel = dc
        dc.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                dataListener?.onFileBinary(peer.id, bytes)
            }
        })
    }

    // ─── Peer Observer ────────────────────────────────────────

    private inner class PeerObserver(private val peer: Peer) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
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

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
            val track = transceiver?.receiver?.track()
            if (track is VideoTrack) {
                val activePeerId = remoteVideoPeerId
                if (activePeerId != null && activePeerId != peer.id) return
                remoteVideoPeerId = peer.id
                assignRemoteVideoTrack(peer, track)
            }
        }

        override fun onRemoveTrack(receiver: org.webrtc.RtpReceiver?) {
            val track = receiver?.track()
            if (track is VideoTrack && remoteVideoPeerId == peer.id) {
                removeRemoteVideoTrack(track)
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED -> {
                    status("Connected")
                    dataListener?.onPeerConnectionStateChanged(peer.id, true)
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    status("Reconnecting...")
                    restartIce(peer)
                    dataListener?.onPeerConnectionStateChanged(peer.id, false)
                }
                PeerConnection.IceConnectionState.FAILED -> {
                    status("ICE failed")
                    restartIce(peer)
                    dataListener?.onPeerConnectionStateChanged(peer.id, false)
                }
                else -> Unit
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
            when (dc.label()) {
                "file" -> attachFileChannel(peer, dc)
                else -> attachChatChannel(peer, dc)
            }
        }

        override fun onRenegotiationNeeded() {
            // Perfect-negotiation pattern: any side may need to renegotiate when
            // tracks are added/removed. Glare is handled in handleDescription.
            makeOffer(peer)
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
        var remoteCameraLive = false
        var remoteScreenLive = false
        var chatChannel: DataChannel? = null
        var fileChannel: DataChannel? = null

        fun close() {
            runCatching { chatChannel?.close() }
            runCatching { fileChannel?.close() }
            runCatching { pc.close() }
        }
    }

    private companion object {
        const val LOCAL_STREAM_ID = "vidly-native"
    }
}
