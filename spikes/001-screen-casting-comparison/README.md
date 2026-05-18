# Spike 001: Android Screen Casting — Approach Comparison

Date: 2026-05-15
Status: Complete

## Executive Summary

**Approach A (WebView + MediaProjection Hybrid) is infeasible.** Android
WebView's RTCPeerConnection is completely isolated from native code — you
cannot inject a native MediaStreamTrack into it. Every workaround routes
through base64-encoded frame transfer or dual PeerConnection architectures,
each of which is more complex and fragile than just going native.

**Approach B (Native WebRTC + WebView hybrid) is the clear winner** for
this use case (1v1 calls with one friend). Specifically, a *hybrid* variant:
native WebRTC for media only (camera, mic, screen share), WebView retained
for everything else (chat, GIF picker, file transfer, UI chrome). This gives
you screen sharing with ~500–700 lines of new Kotlin, 50–100 lines changed
in index.html, and zero reimplementation of chat/GIF/file-transfer features.

Estimated effort: **1–2 weeks** for a working prototype.


---

## Approach A: WebView + MediaProjection Hybrid

### Architecture

```
┌─────────────────────────────────────────────────────┐
│ MainActivity (Kotlin)                               │
│  ┌──────────────────────────────────────────────┐   │
│  │ WebView (loads voice.raycc.org)              │   │
│  │  • RTCPeerConnection (isolated, JS-only)     │   │
│  │  • getUserMedia() for camera/mic             │   │
│  │  • getDisplayMedia() → BLOCKED on Android    │   │
│  └──────────────────────────────────────────────┘   │
│  ┌──────────────────────┐  ┌────────────────────┐   │
│  │ MediaProjection API  │  │ JS Bridge          │   │
│  │  → VirtualDisplay    │  │ (VidlyNative)      │   │
│  │  → Surface/ImageReader│  │ string-only comms  │   │
│  └──────────────────────┘  └────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

### The Core Problem

**You cannot inject a native MediaStreamTrack into a WebView's PeerConnection.**

WebView's WebRTC runtime is a self-contained browser engine. The PeerConnection
object exists only inside the JavaScript VM. The JS bridge (`addJavascriptInterface`)
can exchange strings, booleans, and numbers — but not native MediaStreamTrack
objects, Surfaces, or video frames at interactive framerates.

This means all three known workarounds are broken or impractical:

#### Workaround 1: Canvas Capture Hack
```
MediaProjection → ImageReader → Bitmap → base64 string
  → JS bridge → canvas.putImageData() → canvas.captureStream()
  → replaceTrack() on senders
```
**Why it fails:**
- 1920×1080 frame = ~2.5 MB as base64. At 15 fps = 37.5 MB/s of string
  traffic through the JS bridge. Will destroy performance.
- `canvas.captureStream()` returns a 1 fps stream by default in many
  WebView versions. No reliable way to get 15+ fps.
- Enormous CPU overhead: decode base64, paint to canvas, re-encode as
  video frame on every single frame.
- Latency: 200–500ms per frame, making it unusable for real-time.

#### Workaround 2: Dual PeerConnection
Create a *second* native PeerConnection just for screen share, with its own
offer/answer/ICE exchange.
**Why it fails:**
- Remote peer now sees two separate PeerConnections from the same user.
- Requires server-side changes to route two connections per user.
- The web client (index.html) needs major surgery to merge two incoming
  video streams from the same peer into one tile.
- Synchronization issues: audio on one PC, screen video on another.
- This is strictly more complex than Approach B with none of the benefits.

#### Workaround 3: Replace entire PeerConnection natively
When screen share starts, tear down the WebView's PeerConnection, create a
native PeerConnection with camera + screen tracks, redo all signaling.
**Why it fails:**
- You're basically doing Approach B but with extra steps (migrating mid-call).
- Must reimplement signaling, ICE handling, track management natively anyway.
- Brief call interruption during the migration.

### Line-of-Code Estimates (if we tried Workaround 1)

| Component                                        | New/Changed LOC |
|--------------------------------------------------|-----------------|
| ScreenCaptureService.kt (foreground service)     | 80–100          |
| MediaProjection → ImageReader pipeline           | 60–80           |
| Bitmap → base64 frame sender (via JS bridge)     | 40–60           |
| MainActivity.kt changes (permissions, lifecycle) | 40–60           |
| index.html: canvas renderer + captureStream shim | 80–120          |
| index.html: startScreenShare() rewrite           | 40–60           |
| index.html: stopScreenShare() rewrite            | 30–40           |
| **Total**                                        | **370–520**     |

### Dependencies

| Dependency                     | Version    | Purpose                    |
|--------------------------------|------------|----------------------------|
| Android SDK                    | API 26+    | (already targeted)         |
| FOREGROUND_SERVICE_MEDIA_PROJ  | API 29+    | Required on Android 14+    |
| No new library deps            | —          | Uses only platform APIs    |

### Implementation Steps

1. Add `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission to AndroidManifest.xml
2. Create `ScreenCaptureService.kt` — foreground service with `mediaProjection` type
3. In `MainActivity.kt`, add `registerForActivityResult` to request MediaProjection consent
4. On consent, start the foreground service, get MediaProjection token
5. Create `VirtualDisplay` → `ImageReader` → read frames as Bitmaps
6. Encode each Bitmap as base64 PNG/JPEG
7. Send base64 string to WebView via `evaluateJavascript()` or bridge callback
8. In index.html, add a hidden canvas element
9. On receiving base64 frame, decode and paint to canvas
10. Call `canvas.captureStream()` to create a MediaStream
11. Replace video track on all senders via `replaceTrack()`
12. Add liveness checking (check if frames stop arriving)

### Risk Areas

- **CRITICAL: Performance is almost certainly unacceptable.** Base64 frame
  transfer through JS bridge at usable framerates has never been done in
  production apps. Every reference implementation uses native WebRTC.
- `canvas.captureStream()` behavior varies wildly across WebView versions.
  Chromium WebView 110+ may work; older versions may return 1 fps or nothing.
- Frame timing/synchronization: no guarantee the canvas stream framerate
  matches the capture rate.
- Audio capture from screen (AudioPlaybackCapture, API 29+) is a separate
  problem — would need to pipe audio through Web Audio API via JS bridge too.
- Android 14+ requires fresh `createScreenCaptureIntent()` per session — no
  caching the permission intent.
- Debugging is extremely difficult: you're crossing three layers (native →
  JS bridge → canvas → WebRTC internals).

### Changes to index.html vs Android

| Where         | What changes                                              |
|---------------|-----------------------------------------------------------|
| index.html    | Add hidden canvas, base64 frame receiver, captureStream   |
| index.html    | Rewrite startScreenShare/stopScreenShare for native mode  |
| index.html    | Add feature detection: `if (window.VidlyNative)` paths    |
| MainActivity  | MediaProjection consent flow, foreground service start    |
| New file      | ScreenCaptureService.kt (~100 lines)                      |
| New file      | FrameCaptureManager.kt (~80 lines)                        |
| build.gradle  | No new deps needed                                        |

### Verdict

**Do not pursue.** The fundamental WebView isolation makes this approach
either impossible (track injection) or worse than Approach B in every
dimension (canvas hack). The canvas workaround *might* produce a 2–5 fps
screen share that looks terrible — but it's more work than just going native.


---

## Approach B: Native WebRTC + WebView Hybrid

### Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ MainActivity (Kotlin)                                        │
│  ┌───────────────────────────────────────────────────────┐   │
│  │ Native WebRTC Layer                                   │   │
│  │  • PeerConnectionFactory                              │   │
│  │  • PeerConnection (WebSocket signaling)               │   │
│  │  • VideoTrack (Camera2Capturer)                       │   │
│  │  • VideoTrack (ScreenCapturerAndroid) ← screen share! │   │
│  │  • AudioTrack (mic)                                   │   │
│  │  • SurfaceViewRenderer (local + remote video)         │   │
│  └───────────────────────────────────────────────────────┘   │
│  ┌───────────────────────────────────────────────────────┐   │
│  │ WebView (loads voice.raycc.org)                       │   │
│  │  • Chat messages                                      │   │
│  │  • GIF picker                                         │   │
│  │  • File transfer                                      │   │
│  │  • Reactions                                          │   │
│  │  • Room UI / controls (but NOT video elements)        │   │
│  └───────────────────────────────────────────────────────┘   │
│  ┌───────────────────────────────────────────────────────┐   │
│  │ JS Bridge (VidlyNative)                               │   │
│  │  • Media state sync (camera/mic/screen on/off)        │   │
│  │  • Button events (screen share toggle from web UI)    │   │
│  └───────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

### How ScreenCapturerAndroid Works (from Google's webrtc-android SDK)

The SDK ships `ScreenCapturerAndroid` — a ready-made `VideoCapturer`
implementation that does exactly what we need:

```kotlin
// 1. Get MediaProjection permission
val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
val consentIntent = projectionManager.createScreenCaptureIntent()
// → startActivityForResult(consentIntent, REQUEST_SCREEN_SHARE)

// 2. On consent, create the capturer
val capturer = ScreenCapturerAndroid(
    resultData,                           // Intent from consent dialog
    object : MediaProjection.Callback() { // lifecycle callbacks
        override fun onStop() { /* cleanup */ }
    }
)

// 3. Initialize with SurfaceTextureHelper (frame pipeline)
val surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCapture", eglContext)
val source = peerConnectionFactory.createVideoSource(/* isScreencast = */ true)
capturer.initialize(surfaceTextureHelper, applicationContext, source.capturerObserver)

// 4. Start capture
capturer.startCapture(screenWidth, screenHeight, /* framerate = */ 15)

// 5. Create video track and swap it in
val screenTrack = peerConnectionFactory.createVideoTrack("SCREEN_SHARE", source)
// → replaceTrack on all senders (same pattern as index.html)
```

This is the same `replaceTrack` pattern the web client already uses. The
native version swaps the camera VideoTrack for the screen VideoTrack on the
same PeerConnection — no renegotiation needed for the video track itself
(unified-plan handles it).

### What index.html Already Does That We Can Reuse

The web client's screen share flow (lines 2562–2674):
1. `startScreenShare()`: getDisplayMedia → get video track → replaceTrack on
   all senders → show local preview → set up onended + liveness check
2. `stopScreenShare()`: stop screen tracks → replaceTrack back to camera →
   restore UI state
3. Screen share button toggle: `$('screenBtn').onclick`

For the hybrid approach, we need to:
- Tell the web client "screen share is being handled natively" (so it doesn't
  try `getDisplayMedia()`)
- The web client still manages chat/GIF/file-transfer/reactions as before
- Media buttons in the web UI trigger native actions via the JS bridge

### Line-of-Code Estimates

| Component                                       | New/Changed LOC | Notes                                |
|-------------------------------------------------|-----------------|--------------------------------------|
| **New files**                                   |                 |                                      |
| WebRtcManager.kt                                | 200–280         | PeerConnectionFactory, PeerConnection|
|                                                 |                 | lifecycle, ICE config, observer      |
| ScreenShareManager.kt                           | 80–120          | MediaProjection consent,             |
|                                                 |                 | ScreenCapturerAndroid lifecycle,     |
|                                                 |                 | track swap logic                     |
| SignalingClient.kt (native version)             | 100–140         | WebSocket client for native PC;      |
|                                                 |                 | offer/answer/ICE exchange            |
|                                                 |                 | (refactor from SignalingListener.kt) |
| **Modified files**                              |                 |                                      |
| MainActivity.kt                                 | +80–120         | MediaProjection activity result,     |
|                                                 |                 | SurfaceViewRenderer setup,           |
|                                                 |                 | bridge native↔web media state        |
| index.html                                      | +50–80          | Feature detect VidlyNative,          |
|                                                 |                 | hide local video element,            |
|                                                 |                 | route screen share button to bridge, |
|                                                 |                 | receive media state from native      |
| activity_main.xml                               | +30–50          | SurfaceViewRenderer views,           |
|                                                 |                 | WebView overlay positioning          |
| AndroidManifest.xml                             | +10–15          | Foreground service + permission decls|
| build.gradle.kts                                | +2–3            | webrtc-android dependency            |
| **Total**                                       | **550–770**     |                                      |

### Dependencies

| Dependency                        | Version        | Purpose                         |
|-----------------------------------|----------------|---------------------------------|
| io.github.webrtc-sdk:android      | 125.6422.06+   | PeerConnection, VideoCapturer,  |
|                                   |                | ScreenCapturerAndroid,          |
|                                   |                | SurfaceViewRenderer             |
| com.squareup.okhttp3:okhttp       | 4.12.0         | (already present) WebSocket for |
|                                   |                | signaling                       |
| Android SDK API                   | 26+ (current)  | minSdk stays the same           |
| FOREGROUND_SERVICE_MEDIA_PROJECTION| API 29+       | Required for Android 14+        |

### Implementation Steps

**Phase 1: Native WebRTC foundation (~3–4 days)**

1. Add `io.github.webrtc-sdk:android:125.6422.06` to `build.gradle.kts`
2. Create `WebRtcManager.kt`:
   - Initialize `PeerConnectionFactory` with default encoder/decoder
   - Create `EglBase` context for video rendering
   - Create `PeerConnection` with STUN servers (Google's free STUN)
   - Implement `PeerConnection.Observer` for ICE/SDP events
3. Create `SignalingClient.kt` (refactor from `SignalingListener.kt`):
   - Add `offer`/`answer`/`ice-candidate` message types
   - Handle SDP exchange for the native PeerConnection
4. Add `SurfaceViewRenderer` to `activity_main.xml`:
   - Local video preview (small, corner overlay)
   - Remote video (fullscreen behind WebView)
   - WebView on top with transparent background in call mode
5. Wire camera capturer:
   - `Camera2Capturer` or `CameraEnumerator` → `VideoTrack`
   - `AudioSource` → `AudioTrack`
   - Add tracks to PeerConnection
6. Handle incoming tracks:
   - `onTrack` callback → attach remote video to `SurfaceViewRenderer`
7. Test: basic video call between Android native and web browser

**Phase 2: Screen share (~2–3 days)**

8. Add manifest declarations:
   ```xml
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
   <service android:name=".ScreenShareService"
            android:foregroundServiceType="mediaProjection" />
   ```
9. Create `ScreenShareService.kt`:
   - Foreground notification with "Screen sharing" indicator
   - Holds the MediaProjection alive (Android 14+ requirement)
10. Create `ScreenShareManager.kt`:
    - `registerForActivityResult` for MediaProjection consent
    - On consent: start ScreenShareService, create `ScreenCapturerAndroid`
    - Initialize with `SurfaceTextureHelper`, create video source/track
    - Swap camera track for screen track via `sender.replaceTrack(screenTrack)`
    - Stop: swap back to camera track, release capturer, stop service
11. Bridge to web UI:
    - Add `@JavascriptInterface fun startScreenShare()` to NativeBridge
    - Add `@JavascriptInterface fun stopScreenShare()` to NativeBridge
    - index.html: screen share button calls `VidlyNative.startScreenShare()`
      when `window.VidlyNative` is detected
    - Native side fires JS callback to update screen share button state
12. Test: screen share from Android to web browser

**Phase 3: Polish (~1–2 days)**

13. Handle edge cases: screen share ended by OS, app backgrounded during
    screen share, PiP mode interaction
14. Audio: optionally capture system audio via AudioPlaybackCapture (API 29+)
15. UI: screen share indicator, "you're sharing your screen" banner
16. Cleanup: proper disposal of EglBase, SurfaceTextureHelper, tracks on
    call end and activity destroy

### Risk Areas

| Risk                                 | Severity | Mitigation                                    |
|--------------------------------------|----------|-----------------------------------------------|
| webrtc-android SDK version drift     | Medium   | Pin to specific version; test on updates       |
| Dual signaling (web + native)        | Medium   | Native PC handles media; web signaling only    |
|                                      |          | for chat/file-transfer data channels if needed |
| WebView + SurfaceViewRenderer z-order| Low      | WebView transparent bg, renderers behind it    |
| Android 14+ foreground service       | Low      | Well-documented requirement; follow ForaSoft   |
|                                      |          | pattern exactly                                |
| Audio routing between web/native     | Medium   | Mute WebView's getUserMedia; native handles    |
|                                      |          | all audio. Or keep web audio if camera/mic     |
|                                      |          | stays in WebView (see "unknown" below)         |
| Camera/mic ownership conflict        | Medium   | If WebView still calls getUserMedia(), it will |
|                                      |          | conflict with native capturer. Must disable    |
|                                      |          | web-side getUserMedia when native is active.   |

### Key Unknown

**Should camera/mic also move to native, or only screen share?**

Option B1 (simpler): Move ALL media to native. WebView handles only chat,
GIF, file transfer, reactions. index.html calls `VidlyNative.startCamera()`
instead of `getUserMedia()`. This is cleaner but requires more index.html
changes (~150 lines) to route all media buttons through the bridge.

Option B2 (minimal web changes): Keep camera/mic in WebView via
`getUserMedia()` (which works fine), and only move screen share to native.
The native PeerConnection handles screen video; the WebView PeerConnection
handles camera/mic. But this creates the dual-PeerConnection problem again.

**Recommended: Option B1.** Move all media to native. The web client already
has the `replaceTrack` pattern; we just need to tell it "don't manage media,
the native side does it." This adds ~70 more lines to index.html but avoids
dual PeerConnection complexity entirely.

### Changes to index.html vs Android

| Where         | What changes                                             |
|---------------|----------------------------------------------------------|
| index.html    | Feature-detect `window.VidlyNative` to skip getUserMedia |
| index.html    | Hide `<video id="localVideo">` (native renders local)    |
| index.html    | Screen share button → `VidlyNative.startScreenShare()`   |
| index.html    | Camera/mic buttons → `VidlyNative.toggleCamera/Mic()`    |
| index.html    | Remove RTCPeerConnection code (or guard with feature flag)|
| index.html    | Add JS callbacks for native media state updates          |
| MainActivity  | SurfaceViewRenderer, WebRtcManager, ScreenShareManager   |
| New file      | WebRtcManager.kt (~250 lines)                            |
| New file      | ScreenShareManager.kt (~100 lines)                       |
| New file      | ScreenShareService.kt (~80 lines)                        |
| Modified      | SignalingListener.kt → refactor into SignalingClient.kt  |
| build.gradle  | +1 line (webrtc-android dep)                             |

### Verdict

This is the correct approach. `ScreenCapturerAndroid` is a production-grade
class that wraps MediaProjection → VirtualDisplay → SurfaceTexture → WebRTC
frame pipeline. It's what Google built specifically for this use case. The
`replaceTrack` pattern for screen sharing is identical to what index.html
already does, so the conceptual model carries over directly.


---

## Head-to-Head Comparison

| Dimension              | Approach A (Hybrid)         | Approach B (Native WebRTC)     |
|------------------------|-----------------------------|--------------------------------|
| **New LOC**            | 370–520                     | 550–770                        |
| **Changed LOC**        | 120–180 (mostly index.html) | 100–180 (index.html + manifest)|
| **Time estimate**      | 1–2 weeks                   | 1–2 weeks                      |
| **Dependencies**       | None (platform APIs only)   | +io.github.webrtc-sdk:android  |
| **Screen share quality**| Terrible (2–5 fps, janky)   | Excellent (native, 15–30 fps)  |
| **Maintenance burden** | High (JS bridge frame pipe) | Medium (standard WebRTC code)  |
| **Feature completeness**| Partial (video only, no sys | Full (video + system audio)    |
|                        |  audio capture through      |                                |
|                        |  canvas)                    |                                |
| **Risk**               | Very high (unproven pattern, | Low (well-documented pattern,  |
|                        |  canvas.captureStream is    |  ScreenCapturerAndroid is a    |
|                        |  unreliable in WebView)     |  shipped SDK class)            |
| **Debuggability**      | Very hard (3 layers)        | Normal (standard Android)      |
| **Future extensibility**| Poor (stuck in WebView     | Good (native WebRTC is         |
|                        |  limitations)               |  standard for Android apps)    |
| **Reuses existing code**| ~80% of index.html unchanged| ~60% of index.html unchanged   |
| **Call interruption**  | None (WebView stays)        | None (WebView stays for chat)  |
| **Works on Android 14+**| Technically yes, practically| Yes (follows documented API)   |
|                        |  untested pattern           |                                |


---

## Recommendation

**Go with Approach B (Native WebRTC + WebView hybrid), Option B1 (all media native).**

Reasoning:

1. **Approach A has no proven path.** The canvas.captureStream() hack has
   never been used in production. Every Android WebRTC app — WhatsApp, Google
   Meet, Discord, Telegram — uses native WebRTC, not WebView injection. The
   reason is simple: it doesn't work reliably.

2. **`ScreenCapturerAndroid` exists specifically for this.** Google ships this
   class in the WebRTC SDK. It wraps MediaProjection → VirtualDisplay →
   SurfaceTexture → frame pipeline in ~50 lines of usage code. The
   `replaceTrack` pattern is identical to what the web client already does.

3. **The LOC difference is small.** Approach A is 370–520 lines of *hacky*
   code. Approach B is 550–770 lines of *standard* code. The extra 200 lines
   buy you a production-grade screen share that actually works at 15–30 fps.

4. **The hybrid retains all web features.** Chat, GIF picker, file transfer,
   reactions — none of these need to be reimplemented. The WebView stays;
   only media moves to native.

5. **Maintenance is easier.** Standard WebRTC Android patterns are well-
   documented. The canvas hack would be a custom snowflake that only you
   understand and debug.

6. **Timeline is the same.** Both approaches take 1–2 weeks. Approach B's
   extra LOC are offset by Approach A's debugging time fighting the canvas
   pipeline.

### Suggested Next Step

Prototype Phase 1 (native WebRTC without screen share) in 2–3 days. Get a
basic video call working between the native Android SurfaceViewRenderer and
a web browser. Once that works, screen share is just Phase 2 — swap one
VideoCapturer for another.


---

## Appendix: Key Source File References

- **index.html** (`/home/ray/video-call/public/index.html`, 2757 lines):
  - Screen share: lines 2562–2674 (`startScreenShare`, `stopScreenShare`)
  - PeerConnection creation: line 1402 (`new RTCPeerConnection`)
  - replaceTrack pattern: lines 1049–1067 (track swap on existing senders)
  - Track liveness check: lines 2607–2616 (3s interval, `readyState === 'ended'`)

- **MainActivity.kt** (`/home/ray/video-call/android/.../MainActivity.kt`, 411 lines):
  - WebView setup: lines 63–75
  - JS bridge: lines 385–403 (`NativeBridge` class)
  - Injected JS hooks: lines 342–383 (`injectNativeBridgeHooks`)
  - PiP: lines 199–218

- **SignalingListener.kt** (271 lines): WebSocket signaling for incoming
  call detection (background only). Would be refactored into
  `SignalingClient.kt` that also handles offer/answer/ICE for native PC.

- **build.gradle.kts**: `minSdk = 26`, `targetSdk = 34`, only dep is OkHttp.
