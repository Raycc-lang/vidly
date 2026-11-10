# Vidly — Android Native WebRTC Video Call

## What it is

Vidly is a P2P WebRTC voice/video call app (native Android client + web client + tiny Node.js signaling server) for **personal 1:1 voice and video calls**. Rooms technically hold up to 4 peers (mesh), but the app is designed, tested and optimized as **1:1** (single remote video peer).

The real deployment it is tuned for: **Ray in mainland China calling a friend in the United States**, with the signaling server on a Los Angeles VPS. That makes it a **long-haul link**: ~160 ms RTT China↔LA on Ray's leg plus ≈90 ms (inferred; the friend blocks ICMP) LA↔friend, so the call RTT floor is ≈250 ms, and Ray's leg crosses the GFW. Ray's router transparently tunnels all non-CN traffic — **including all WebRTC UDP** — through xray (VLESS/xhttp over QUIC) to the **same LA VPS** that runs Vidly; only the signaling WebSocket goes direct. So Ray's media always egresses in LA, whatever ICE picks (decisions/008 addendum). The unusual parts of this codebase — aggressive reconnection, a watchdog for lost signaling messages, TURN that is expected to be used rather than a rare fallback — exist because a long-haul NANOG-style path plus a censoring middlebox drops and stalls things that a LAN call would never notice.

Do not assume a legacy assumption still holds: earlier versions of this app were tuned for a **Saudi user on a quota-metered mobile carrier**. That user is gone and that case is archived in `docs/historical/2026-01-saudi-carrier-saga.md` — read it for the generic lessons, not for the network model.

## How a call works, end to end

1. **Join**: client opens a WebSocket to `wss://voice.raycc.org/ws` (`NativeSignalingClient` — the `/ws` path matters, nginx only upgrades there), sends `join {roomId, username, clientId, pageSessionId}`. Before that it **synchronously** fetches TURN credentials from `/turn-credentials` (async fetch used to race peer arrival → ICE failed).
2. **Server** (`server.js`) replies `joined` with the existing peer list; existing peers get `peer-joined`.
3. **Negotiation**: the joiner offers to each existing peer (it treats them as *impolite*; they treat the newcomer as *polite*). Offer/answer/ICE candidates relay through the server as `signal` messages. Perfect Negotiation handles glare (see decisions/004).
4. **Media**: ICE connects (STUN + Cloudflare Realtime TURN). Through Ray's tunnel srflx candidates are valid, and srflx/srflx is common — 3 of 4 prod PCs observed 2026-11-09 used it, one used relay-tcp; TURN must still be available (decisions/008 + addendum). Tracks are added lazily via `addTrack` only when mic/camera/screen actually turn on — `onRenegotiationNeeded` drives the SDP updates.
5. **Data**: two DataChannels per peer, created by the offerer: `chat` (JSON: chat messages, `media-state` role announcements, file-transfer control) and `file` (binary chunks).
6. **Disconnection**: if a client's socket drops, the server announces `peer-left` immediately but keeps the slot for a **180s grace period** keyed by `clientId`. Rejoin within grace → `peer-resumed` (or `peer-joined` if the departure was announced); either way **both sides tear down and rebuild their PeerConnections** (decisions/001). A grace seat is invisible to everyone but its own clientId (decisions/009). A Chromium page reload is a clean `leave` + fresh join with a new peerId, not a resume.

## Critical design constraints — do not "fix" these

Each one is a decision record in `decisions/`; read it before touching the area.

1. **ONE ICE restart mechanism** (decisions/002). Only the ICE observer in `NativeWebRtcClient` restarts ICE: `DISCONNECTED` → 8s delayed, `FAILED` → immediate, `CONNECTED` cancels. `checkFrameHealth()` in the Activity is **UI-only** (the "Paused - poor network" overlay) and must never call `restartIce` — a second trigger races the observer and restarts healthy connections.
2. **Signaling rejoin = full symmetric PC rebuild** (decisions/001). `onJoined` and `peer-resumed` tear down every stale PeerConnection **on both sides** and renegotiate from scratch. Never reuse a PC across signaling sessions, and never queue/buffer signals across a reconnect (client or server) — old-epoch signals poison the fresh session.
3. **Negotiation state is main-thread-only** (decisions/004). Every `PeerConnection.Observer` / `SdpObserver` / chat-DataChannel callback in `NativeWebRtcClient` is marshalled to the `main` Handler before touching `makingOffer`/`peers`/track roles. Keep it that way for new callbacks. Deliberate exception: the file channel's `onBufferedAmountChange` (backpressure wake must be prompt).
4. **Stuck-offer watchdog** (decisions/003). An offer unanswered for 10s (`HAVE_LOCAL_OFFER`) is rolled back and re-sent, at most `OFFER_MAX_RETRIES = 3` times; the 4th fire rolls back and logs `offer-abandoned`. This is what breaks the impolite-peer deadlock when an offer dies in a silently-dead WebSocket. Cancelled on any successful `setRemoteDescription`, which is also the **only** thing that resets the retry count.
5. **Remote track roles come from `media-state` + `onFirstPacketReceived`, never track ids** (decisions/007). Unified Plan regenerates receiver-side track ids. `onFirstPacketReceived` is both the "unmute" signal (camera off→on refires no `onTrack`) and the gate for reclassifying a track between camera/screen roles.
6. **Camera off→on keeps the same track and sender** — pause capture + disable track, don't dispose. Replacing the track would not re-fire `pc.onTrack` on the receiver and the remote video never resumes.
7. **Audio routing pins the output in `onWebRtcAudioTrackStart`** (decisions/006), not on fixed delays. Set the WebRTC track's preferred device (reflection) **before** `setCommunicationDevice`. Pre-Android-12 Bluetooth is a known gap.
8. **BeautyVideoProcessor** (decisions/005): GL state lives in per-generation `Gl` objects so the processor survives camera pipeline rebuilds. Every `eglMakeCurrent` must **save and restore** the thread's prior EGL binding — never unbind; unbinding corrupts the SurfaceTextureHelper thread.
9. **An offer arriving while our `setRemoteDescription(answer)` is pending is not a collision** (decisions/004 amendment). Web `peer.settingRemoteAnswer`, Android `pendingRemoteAnswers` (and the polite rollback is skipped while it's > 0). Without it every call setup can stall 10s on the watchdog.
10. **Grace-period seats are invisible** (decisions/009). Server: `isLiveSeat` gates `joined.peers`, the username check, the cap and `watch`; ghosts are evicted oldest-first when the map is full. A signal to a non-live target gets `signal-undeliverable`, which clients treat exactly like `peer-left`. It is a nack, not a buffer — never queue for a ghost.
11. **TURN URLs**: pass the array. Credentials come from `/turn-credentials` as a Cloudflare `{iceServers:[...]}` passthrough and must be fetched synchronously **before** `signaling.connect()`. The set already includes UDP, TCP/80 and TLS/443 fallbacks — do not prune them, the China leg needs them.
12. **The existing peer holds its first offer until the joiner's is applied** (decisions/004, 2026-11-10 amendment; web `peer.holdOffer`, 3s fallback). Offering simultaneously forces a rollback, after which Chrome rejects the follow-up offer when a share is live with the mic off — the joiner gets no media while both sides read connected. Call setup should log **zero** `offer-ignored`; a `negotiate-failed` event in a call-report is always a bug.

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
├── test-protocol.md                # Manual 2-device test protocol + automated harness (run before releases)
├── tools/negotiation-repro.mjs     # Headless Chromium harness: 7 web/server negotiation &
│                                   #   seat scenarios against a local server.js (PASS/FAIL lines)
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

# Logs (app tags: VidlyScreen, VidlyAudio, VidlyNego; plus libwebrtc)
adb logcat -v time | grep -E "Vidly|libjingle|org.webrtc"

# Test (web/server negotiation; ≈4 min; must pass before a release — see test-protocol.md)
node tools/negotiation-repro.mjs
```

The in-app **header status label** is the primary runtime observable (`Connected`, `Reconnecting...`, `Renegotiating...`, `ICE failed`, `Paused - poor network`) — `test-protocol.md` has the full table of statuses, timing constants, 10 manual failure-mode scenarios, and the automated harness.

## Deploy

- **Server / web client / APK hosting** live on the VPS (`ssh vm`), domain `voice.raycc.org`: Node.js behind nginx (TCP/443, terminates wss://), managed by systemd. UDP/443 on the VPS is xray's h3 listener (Ray's tunnel). There is **no coturn**: TURN is Cloudflare Realtime, minted by `/turn-credentials`.
- **Release flow**: use the `vidly-release` project skill — it bumps the version, builds the APK, uploads it to the VPS (served at `https://voice.raycc.org/vidly-native.apk`, advertised by `/version`), and restarts the systemd service.
- Server env knobs (systemd unit): `TURN_KEY_ID`, `TURN_KEY_SECRET` (Cloudflare), `WS_REJOIN_GRACE_MS`, `ANDROID_VERSION_CODE/NAME`, `GIPHY_API_KEY`.

## Where to look when something breaks

| Symptom | First place to look |
|---|---|
| Call never connects cross-network | `curl https://voice.raycc.org/turn-credentials` (503 = keys unset, 502 = Cloudflare error) |
| Call setup stalls ~10s / `Renegotiating...` right at call start | Answer-pending race (decisions/004 amendment); `node tools/negotiation-repro.mjs --only setup`; web `offer-timeout` with n=1 in call-reports, Android `VidlyNego` |
| Someone joins/reloads during a screen share and sees nothing, both sides `Connected` | Offer hold (decisions/004, 2026-11-10); `negotiate-failed` in the sharer's call-report; harness `join-during-share` |
| Joiner keeps offering to a peer that already left, or `username-taken` when rejoining from another device/browser within 3 min | Ghost seats (decisions/009); harness `ghost-join`, `name-reuse` |
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
- `/turn-credentials` is unauthenticated (anyone can mint TURN creds, i.e. burn the shared 1 TB/month Cloudflare allowance).
- Call setup pays one extra negotiation round (≈ one RTT) because the polite peer's placeholder `addTransceiver('audio')` can't be reused — deliberately deferred (decisions/004).
- The automated harness covers the web client + server only; Android negotiation changes still need manual scenarios 7 and 10.
- A relayed 1:1 call costs roughly 2× the media bitrate for the whole duration — check `call-reports/` sample `path` values before assuming direct P2P is happening.

## Project skills

- `vidly-release` — bump version, build APK, deploy to VPS, restart systemd service
- `webrtc-video-call` — WebRTC mesh architecture, signaling protocol, reconnection, ICE debugging

*(This file is auto-loaded when workdir = `~/video-call/`.)*
