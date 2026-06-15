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
- **Two ICE restart mechanisms**: observer (DISCONNECTED→8s delay, FAILED→immediate) works for all media types. `checkFrameHealth()` is video-only UI feedback, NOT the primary ICE recovery path.
- **Audio routing**: `JavaAudioDeviceModule.Builder.setAudioTrackStateCallback(onWebRtcAudioTrackStart)` fires at the right moment for output routing. Don't use fixed-delay retries.
- **BeautyVideoProcessor**: NO `eglMakeCurrent` on the SurfaceTextureHelper thread — it corrupts SurfaceTexture state.
- **TURN URLs**: pass array (UDP + TCP). Fetch synchronously before `signaling.connect()`.
- **AGENTS.md**: This file. Auto-loaded when workdir=~/video-call/.

## Skills
- `vidly-release` — bump version, build APK, deploy to VPS, restart systemd service