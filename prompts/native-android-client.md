# Goal

Convert Vidly Android from a WebView shell into a native WebRTC client with real-time camera beautification.

The primary feature is **camera beautification** (skin smoothing, brightness, etc.) applied to outgoing video frames before they enter WebRTC encoding. Screen sharing is a secondary goal for later.

# Current State

## Android app (WebView shell)
- Location: `/home/ray/video-call/android/`
- 3 Kotlin files: MainActivity.kt (~410 lines), CallService.kt (~104 lines), SignalingListener.kt (~285 lines)
- Loads `https://voice.raycc.org` in a WebView
- Has a JS bridge (VidlyNative) for PiP, proximity sensor, permissions
- Cannot do screen sharing or camera beautification (WebView limitation)

## Web client
- Location: `/home/ray/video-call/public/index.html` (~2700 lines)
- Vanilla JS, no build tools
- WebRTC mesh (max 4 peers), DataChannel for chat, file transfer
- Already has screen sharing via getDisplayMedia (desktop only)
- `replaceTrack()` pattern for camera/screen swaps

## Signaling server
- Location: `/home/ray/video-call/server.js` (173 lines of logic)
- WebSocket protocol, messages:
  - `{ type: "set-username", username }` — set display name
  - `{ type: "join", roomId, username, clientId }` — join room
  - `{ type: "signal", to, data }` — relay SDP/ICE to specific peer
  - `{ type: "leave" }` — leave room
  - `{ type: "watch", roomId }` — passive observer (background listener)
- Server sends back: `welcome`, `joined`, `peer-joined`, `peer-left`, `signal`, `room-full`, `username-taken`

## Infrastructure
- VPS: CentOS 9, nginx:8443 → Node.js:3000
- Domain: voice.raycc.org
- coturn TURN server on port 3478 (turn: only, no turns:)
- Build: `cd /home/ray/video-call/android && ./gradlew assembleDebug`
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

# Architecture Decision

**Go fully native on Android.** Drop the WebView for the call experience. The Android app becomes a real native WebRTC client that speaks the same signaling protocol as the web client, so Android ↔ browser calls still work.

The WebView was a shortcut for quick development. Now that we need camera frame access (for beautification) and screen capture (later), native is the only viable path. 1v1 chat UI is trivial in native — no need to preserve the WebView.

# Phase 1: Native WebRTC + camera beautification

Build a native Android video call client that:
1. Connects to the existing signaling server via WebSocket
2. Joins rooms using the same protocol as index.html
3. Creates native PeerConnection with camera + mic
4. Interops with web browser clients (Android native ↔ Chrome browser = working call)
5. Applies real-time beautification filter to outgoing camera frames

## Beautification pipeline
- Insert an OpenGL/GPU filter chain between Camera2Capturer and WebRTC encoder
- MVP filters: skin smoothing (bilateral/gaussian blur), brightness lift, contrast/saturation adjustment
- Toggle on/off + intensity slider in the UI
- Use GPU (not CPU) for frame processing — must sustain 30fps at 720p
- Approach: custom VideoCapturer wrapping Camera2Capturer, processing frames through GLSurface/OpenGL shader before passing to WebRTC's capturer observer

## Key libraries
- `io.github.webrtc-sdk:android` — PeerConnectionFactory, PeerConnection, VideoCapturer, SurfaceViewRenderer
- OkHttp 4.12.0 (already present) — WebSocket signaling
- OpenGL ES 2.0+ — GPU frame processing (platform API, no extra dep)

## UI (Phase 1)
- Full-screen remote video
- Local video in corner PiP overlay
- Bottom bar: camera toggle, mic toggle, hangup, beautify toggle + slider
- Room join screen: room ID input + username + join button
- Keep it minimal — functional, not polished

## What to ignore in Phase 1
- Chat/text messaging
- GIF picker
- File transfer
- Emoji reactions
- Screen sharing
- Background call detection / incoming call notifications
- PiP mode

# Constraints

- Must interop with existing web browser clients — the friend uses a browser
- Kotlin, Gradle Kotlin DSL (existing stack)
- Do NOT delete or break the existing WebView-based Android app. Keep it working until the native version is complete. The existing MainActivity.kt, CallService.kt, SignalingListener.kt stay as-is. New native code goes in new files/classes. When the native app is ready to replace the WebView app, we switch — but not before.

# What NOT to do

- Do not try to inject native frames into WebView's PeerConnection (impossible — WebView's RTCPeerConnection is isolated from native code)
- Do not use canvas.captureStream() hack (2-5 fps, unusable)
- Do not use CameraX Extensions for beautification (only works for preview/capture, not video pipeline)
- Do not add heavy AR SDKs (ARCore, etc.) — lightweight OpenGL filters only for MVP

# Deliverable

Implement Phase 1. Build the working Android app with native WebRTC and beautification. We already did the spike and architecture analysis — the plan exists. Now write the code.
