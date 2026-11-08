# Vidly — Android Native WebRTC Video Call

## What it is

Vidly is a P2P WebRTC voice/video call app (native Android client + web client + tiny Node.js signaling server) built for **one specific job**: reliable voice calls with a friend abroad over a weak, unstable network. Everything unusual about this codebase — aggressive reconnection, TURN-first thinking, a watchdog for lost signaling messages — exists because of that weak-network constraint. Rooms technically hold up to 4 peers (mesh), but the app is designed, tested, and optimized as **1:1** (single remote video peer).

## How a call works, end to end

1. **Join**: client opens a WebSocket to `wss://voice.raycc.org` (`NativeSignalingClient`), sends `join {roomId, username, clientId, pageSessionId}`. Before that it **synchronously** fetches TURN credentials from `/turn-credentials` (async fetch used to race peer arrival → ICE failed).
2. **Server** (`server.js`) replies `joined` with the existing peer list; existing peers get `peer-joined`.
3. **Negotiation**: the joiner offers to each existing peer (it treats them as *impolite*; they treat the newcomer as *polite*). Offer/answer/ICE candidates relay through the server as `signal` messages. Perfect Negotiation handles glare (see decisions/004).
4. **Media**: ICE connects (STUN, falling back to TURN on `turn:voice.raycc.org:3478` / TURNS 443). Tracks are added lazily via `addTrack` only when mic/camera/screen actually turn on — `onRenegotiationNeeded` drives the SDP updates.
5. **Data**: two DataChannels per peer, created by the offerer: `chat` (JSON: chat messages, `media-state` role announcements, file-transfer control) and `file` (binary chunks).
6. **Disconnection**: if a client's socket drops, the server announces `peer-left` immediately but keeps the slot for a **180s grace period** keyed by `clientId`. Rejoin within grace → `peer-resumed` (or `peer-joined` if the departure was announced); either way **both sides tear down and rebuild their PeerConnections** (decisions/001).

## Critical design constraints — do not "fix" these

Each one is a decision record in `decisions/`; read it before touching the area.

1. **ONE ICE restart mechanism** (decisions/002). Only the ICE observer in `NativeWebRtcClient` restarts ICE: `DISCONNECTED` → 8s delayed, `FAILED` → immediate, `CONNECTED` cancels. `checkFrameHealth()` in the Activity is **UI-only** (the "Paused - poor network" overlay) and must never call `restartIce` — a second trigger races the observer and restarts healthy connections.
2. **Signaling rejoin = full symmetric PC rebuild** (decisions/001). `onJoined` and `peer-resumed` tear down every stale PeerConnection **on both sides** and renegotiate from scratch. Never reuse a PC across signaling sessions, and never queue/buffer signals across a reconnect (client or server) — old-epoch signals poison the fresh session.
3. **Negotiation state is main-thread-only** (decisions/004). Every `PeerConnection.Observer` / `SdpObserver` / chat-DataChannel callback in `NativeWebRtcClient` is marshalled to the `main` Handler before touching `makingOffer`/`peers`/track roles. Keep it that way for new callbacks. Deliberate exception: the file channel's `onBufferedAmountChange` (backpressure wake must be prompt).
4. **Stuck-offer watchdog** (decisions/003). An offer unanswered for 10s (`HAVE_LOCAL_OFFER`) is rolled back and re-sent. This is what breaks the impolite-peer deadlock when an offer dies in a silently-dead WebSocket. Cancelled on any successful `setRemoteDescription`.
5. **Remote track roles come from `media-state` + `onFirstPacketReceived`, never track ids** (decisions/007). Unified Plan regenerates receiver-side track ids. `onFirstPacketReceived` is both the "unmute" signal (camera off→on refires no `onTrack`) and the gate for reclassifying a track between camera/screen roles.
6. **Camera off→on keeps the same track and sender** — pause capture + disable track, don't dispose. Replacing the track would not re-fire `pc.onTrack` on the receiver and the remote video never resumes.
7. **Audio routing pins the output in `onWebRtcAudioTrackStart`** (decisions/006), not on fixed delays. Set the WebRTC track's preferred device (reflection) **before** `setCommunicationDevice`. Pre-Android-12 Bluetooth is a known gap.
8. **BeautyVideoProcessor** (decisions/005): GL state lives in per-generation `Gl` objects so the processor survives camera pipeline rebuilds. Every `eglMakeCurrent` must **save and restore** the thread's prior EGL binding — never unbind; unbinding corrupts the SurfaceTextureHelper thread.
9. **TURN URLs**: pass the array (UDP + TCP variants). Fetch synchronously **before** `signaling.connect()`.

## Code map

```
~/video-call/
├── server.js                       # Signaling server: WS relay + rooms + 180s grace,
│                                   #   /turn-credentials, /version, /crash, /giphy proxy,
│                                   #   static files. No DB, all state in memory.
├── public/index.html               # Web client (single file). The Android client mirrors
│                                   #   its negotiation/track-role logic — check here when
│                                   #   behavior between platforms diverges.
├── decisions/                      # Architecture decision records (read before refactoring)
├── test-protocol.md                # Manual 2-device test protocol (run before releases)
├── android/                        # Build root: cd android && ./gradlew ...
│   ├── app-native/src/main/java/org/raycc/vidly/native/
│   │   ├── NativeWebRtcClient.kt   # (~1350 ln) PeerConnections, Perfect Negotiation, ICE
│   │   │                           #   restart, stuck-offer watchdog, tracks & roles,
│   │   │                           #   DataChannels, screen capture pipeline
│   │   ├── NativeCallActivity.kt   # (~3100 ln) All UI, audio routing, frame-health overlay,
│   │   │                           #   chat + file transfer logic, PiP, permissions
│   │   ├── NativeSignalingClient.kt# (~210 ln) WebSocket + reconnect backoff (1s→10s)
│   │   ├── NativeCallService.kt    # Foreground service + WakeLock (FGS type camera/
│   │   │                           #   mediaProjection promoted/demoted with screen share)
│   │   ├── BeautyVideoProcessor.kt # GLES2 beauty filter + front-camera un-mirroring
│   │   ├── CrashReporter.kt        # Uploads crashes to POST /crash
│   │   ├── NativeIncomingCallListener.kt  # Incoming-call notification
│   │   └── VidlyApp.kt             # Application class
│   └── app-webview/                # Legacy WebView wrapper — unused, ignore
```

## Build, install, debug

```bash
# Build
cd ~/video-call/android && ./gradlew :app-native:assembleDebug

# Install (device on LAN with adb-over-wifi)
adb connect 192.168.1.13:5555 && adb install -r android/app-native/build/outputs/apk/debug/app-native-debug.apk

# Logs (app tags: VidlyScreen, VidlyAudio; plus libwebrtc)
adb logcat -v time | grep -E "Vidly|libjingle|org.webrtc"
```

The in-app **header status label** is the primary runtime observable (`Connected`, `Reconnecting...`, `Renegotiating...`, `ICE failed`, `Paused - poor network`) — `test-protocol.md` has the full table of statuses, timing constants, and 10 executable failure-mode scenarios.

## Deploy

- **Server / web client / APK hosting** live on the VPS (`ssh vm`), domain `voice.raycc.org`: Node.js behind nginx (which also terminates wss:// and TURNS on 443), managed by systemd. coturn serves `turn:voice.raycc.org:3478`.
- **Release flow**: use the `vidly-release` project skill — it bumps the version, builds the APK, uploads it to the VPS (served at `https://voice.raycc.org/vidly-native.apk`, advertised by `/version`), and restarts the systemd service.
- Server env knobs (systemd unit): `TURN_SERVER_URL`, `TURN_SECRET`, `WS_REJOIN_GRACE_MS`, `ANDROID_VERSION_CODE/NAME`, `GIPHY_API_KEY`.

## Where to look when something breaks

| Symptom | First place to look |
|---|---|
| Call never connects cross-network | `curl https://voice.raycc.org/turn-credentials`; coturn status on VPS |
| Call dies after a network blip and never recovers | ICE observer + watchdog in `NativeWebRtcClient`; scenarios 1–3, 10 in test-protocol.md |
| One side reconnects, other side frozen | Symmetric rebuild (decisions/001) — check `onJoined`/`onPeerJoined` teardown |
| Remote camera never resumes after off→on | `onFirstPacketReceived` path (decisions/007); scenario 4 |
| Screen share renders in the wrong (PiP) tile | `reclassifyRemoteVideoTracks` (decisions/007); scenario 5 |
| Audio on wrong output at call start | `VidlyAudio` logcat; reflection break after a WebRTC upgrade (decisions/006) |
| Video freezes after camera switch with beauty on | GL generation lifecycle (decisions/005); scenario 8 |
| Crashes in the field | `crash-logs/` on the VPS (uploaded via POST /crash) |

## Known open issues (deliberate, don't rediscover)

- File transfers hold the whole file in RAM on both ends — no streaming to disk yet.
- Pre-Android-12 Bluetooth audio is broken (no `startBluetoothSco`; A2DP mis-selected).
- `/turn-credentials` is unauthenticated (anyone can mint TURN creds).

## Project skills

- `vidly-release` — bump version, build APK, deploy to VPS, restart systemd service
- `webrtc-video-call` — WebRTC mesh architecture, signaling protocol, reconnection, ICE debugging

*(This file is auto-loaded when workdir = `~/video-call/`.)*
