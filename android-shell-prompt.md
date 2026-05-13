# Vidly Android Native Shell

## Goal

Build a native Android app that wraps the existing web app at `https://voice.raycc.org` in a WebView. The app's purpose is to solve three problems that a browser cannot:
1. Keep the call alive when the app is in the background
2. Show incoming call notifications when the app is not in the foreground
3. Maintain a persistent WebSocket connection to the signaling server

No changes to the server (server.js) or web client (index.html).

## Architecture

- **UI**: Single Activity with a full-screen WebView loading `https://voice.raycc.org`
- **Foreground Service**: Runs during active calls. Shows a persistent notification ("Vidly Call Active"). Acquires a partial wake lock to prevent CPU sleep. Keeps WebSocket alive.
- **WebSocket Listener**: A lightweight service that connects to the signaling server's WebSocket (`wss://voice.raycc.org`). When it receives an event indicating someone is calling the user, it shows a high-priority notification ("Incoming Call — tap to open").

## Signaling Server WebSocket Protocol

The server is a Node.js WebSocket server. Key details for the Android shell:

- Connect to: `wss://voice.raycc.org`
- On connect, the server expects a `join` message: `{"type":"join","roomId":"<room>","username":"<name>","clientId":"<uuid>"}`
- The server broadcasts `{"type":"peer-joined","peerId":"<id>","username":"<name>"}` to other peers in the room
- The server broadcasts `{"type":"peer-left","peerId":"<id>"}` when someone disconnects
- For incoming call detection: when the app is in background, maintain a WebSocket connection with the user's identity. When a `peer-joined` arrives in the user's designated room, trigger an incoming call notification.

**Important**: The web client handles all WebRTC negotiation, DataChannel, media capture, etc. The Android shell does NOT touch any of that. It only needs the WebSocket for presence/call detection.

## Feature Requirements

### Must Have
1. WebView loading `https://voice.raycc.org` with full JavaScript, WebRTC, and camera/mic permissions enabled
2. Foreground Service that:
   - Starts when a call is active (detect via WebView URL or JavaScript bridge)
   - Shows persistent notification with call duration
   - Acquires partial wake lock
   - Stops when call ends
3. WebSocket-based incoming call notification:
   - Connect on app start, reconnect on disconnect (exponential backoff, max 30s)
   - Show heads-up notification on incoming call event
   - Tap notification opens the app to the correct room
4. Request all necessary permissions at runtime: camera, microphone, notification (Android 13+)
5. Handle WebView lifecycle: restore state on config change, handle back button as WebView history navigation

### Nice to Have
6. JavaScript bridge (`JavascriptInterface`) for the web page to notify the native layer about call state changes (call started / call ended), so the Foreground Service can start/stop precisely
7. Proximity sensor handling during calls (turn off screen when phone is held to ear)
8. Keep screen on flag during active video calls
9. Picture-in-Picture mode support

## Tech Stack (suggested)

- Language: Kotlin
- Min SDK: 26 (Android 8.0)
- Target SDK: 34
- Build: Gradle with Kotlin DSL
- WebView: Android system WebView (Chrome-based, supports WebRTC)
- WebSocket: OkHttp WebSocket (already a common dep, clean API)
- Notifications: Android NotificationChannel + NotificationManager
- No Jetpack Compose — use XML layouts for simplicity (single activity, one layout with a WebView)

## Project Structure (suggested)

```
android/
  app/
    src/main/
      java/org/raycc/vidly/
        MainActivity.kt          — WebView setup, permissions, lifecycle
        CallService.kt           — Foreground Service (wake lock, notification)
        SignalingListener.kt     — WebSocket connection for call detection
      res/
        layout/activity_main.xml — Full-screen WebView
      AndroidManifest.xml
  build.gradle.kts
  settings.gradle.kts
```

## Success Criteria

1. `./gradlew assembleDebug` produces a working APK
2. Install on device, grant permissions, voice.raycc.org loads and works (video/audio call)
3. Press home during a call — call continues, notification shows "Call Active"
4. App closed, someone joins your room — notification appears "Incoming Call"
5. Tap notification — app opens to the call

## Constraints

- This runs on a OnePlus Ace 5 (Android 16, KernelSU)
- The signaling server is at `voice.raycc.org` (HTTPS + WSS via nginx reverse proxy on port 8443)
- coturn TURN server available at the same domain, port 3478 (turn: only, no turns:)
- Keep the codebase minimal — this is a shell, not a full app
