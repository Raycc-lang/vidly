# Vidly — Android Native WebRTC Video Call

## What it is
P2P voice/video call app via WebRTC. Primary use: voice calls with a friend abroad on weak network.

## Code Map
```
~/video-call/
├── server.js                          # Signaling server (Node.js, WebSocket)
├── public/index.html                  # Web client
├── android/
│   ├── app-native/                    # Android Native client (Kotlin)
│   │   └── src/main/java/org/raycc/vidly/native/
│   │       ├── NativeCallActivity.kt  # Main UI + audio routing + chat
│   │       ├── NativeWebRtcClient.kt  # PeerConnection + ICE + tracks
│   │       ├── NativeSignalingClient.kt  # WebSocket signaling
│   │       ├── NativeCallService.kt   # Foreground service + WakeLock
│   │       └── BeautyVideoProcessor.kt  # GPU beauty filter (GLES2)
│   └── app-webview/                   # Legacy WebView wrapper (unused)
```

## Build
```bash
cd ~/video-call/android && ./gradlew :app-native:assembleDebug
```

## Install
```bash
adb connect 192.168.1.13:5555 && adb install -r android/app-native/build/outputs/apk/debug/app-native-debug.apk
```

## Key Patterns (Avoid Mistakes)
- **ONE ICE restart mechanism**: the ICE observer in NativeWebRtcClient (DISCONNECTED→8s delayed restart, FAILED→immediate). `checkFrameHealth()` is UI-only (the "Paused - poor network" overlay) and must never call restartIce — a second trigger races the observer and restarts healthy connections.
- **Signaling rejoin = full PC rebuild**: `onJoined` and `peer-resumed` tear down every stale PeerConnection and renegotiate from scratch (both sides). Never reuse a PC across signaling sessions — the remote rebuilt too, and stale DTLS/ICE state deadlocks SRD. This also means old-epoch signals must NOT be queued/buffered across a reconnect (client or server) — they'd poison the fresh session.
- **Negotiation state is main-thread-only**: every PeerConnection.Observer / SdpObserver / DataChannel chat callback in NativeWebRtcClient is marshalled to the `main` handler before touching makingOffer/peers/track roles. Keep it that way for new callbacks.
- **Stuck-offer watchdog**: an offer unanswered for 10s (HAVE_LOCAL_OFFER) is rolled back and re-sent (scheduleOfferTimeout) — covers offers lost on a silently-dead WebSocket.
- **Audio routing**: `JavaAudioDeviceModule.Builder.setAudioTrackStateCallback(onWebRtcAudioTrackStart)` fires at the right moment for output routing. Don't use fixed-delay retries.
- **BeautyVideoProcessor**: never leave the SurfaceTextureHelper thread's EGL binding changed — save the prior binding and RESTORE it after every eglMakeCurrent (init, per-frame, release); unbinding corrupts SurfaceTexture state. GL state lives in a per-generation `Gl` object so the processor survives camera pipeline rebuilds (setSink(null) → re-attach).
- **TURN URLs**: pass array (UDP + TCP). Fetch synchronously before `signaling.connect()`.
- **AGENTS.md**: This file. Auto-loaded when workdir=~/video-call/.

## Infrastructure

- **Signaling**: VPS `voice.raycc.org` — Node.js WebSocket server (server.js) behind nginx, proxied via systemd
- **Web client**: served by the same Node.js server on VPS
- **TURN**: coturn on VPS `turn:voice.raycc.org:3478` (TURNS on 443 via nginx)
- **Android SIGNALING_URL**: `wss://voice.raycc.org`
- **APK URL**: `https://voice.raycc.org/vidly-native.apk`

## Project Skills
- `vidly-release` — bump version, build APK, deploy to VPS, restart systemd service
- `webrtc-video-call` — WebRTC mesh architecture, signaling protocol, reconnection, ICE debugging
