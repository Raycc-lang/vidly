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

## Infrastructure

- **Signaling**: Cloudflare Worker `vidly-signal.iceui2016.workers.dev` + Durable Object `RoomDO` (migrated 2026-06-27)
- **TURN**: Cloudflare Calls (`turn.cloudflare.com`) — 1000GB/mo free tier
- **Web client**: VPS at `voice.raycc.org`
- **Android SIGNALING_URL**: `wss://vidly-signal.iceui2016.workers.dev/signal?roomId=<id>`

## Project Skills (`.qoder/skills/`)
- `vidly-context` — user preferences, device info, project memory, infra details
- `vidly-feature-specs` — write implementation specs for coding agents (design-first workflow)
- `cloudflare-webrtc-signaling` — Cloudflare Workers + Durable Objects + Calls TURN setup
- `webrtc-video-call` — WebRTC mesh architecture, signaling protocol, reconnection, ICE debugging
- `vidly-release` — bump version, build APK, deploy to VPS, restart systemd service