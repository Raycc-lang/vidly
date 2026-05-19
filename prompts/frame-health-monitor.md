# Add frame health monitoring — show "Video paused" indicator during network stalls

## Problem

When network bandwidth drops severely, WebRTC prioritizes audio packets over video. On the receiver side, video frames stop arriving but audio continues. The current code has **no detection or recovery mechanism** for this:

- **Native app**: The SurfaceViewRenderer shows the last frame (frozen). The user thinks video is broken. When bandwidth recovers, frames resume automatically — but there's no visual feedback during the stall.
- **WebView**: Chrome fires `track.onmute` on the inbound video track during bandwidth dips. The handler calls `clearActiveRemoteVideo()` which is too aggressive — it clears srcObject and shows "Waiting for video…". When bandwidth recovers, `onunmute` tries to restore, but may not always fire reliably.

## Goal

Add a frame health monitoring system that:

1. **Detects when video frames stop arriving** while the ICE connection is still alive
2. **Shows a "Video paused — poor network" indicator** (NOT clearing the video)
3. **Auto-recovers** when frames resume — the indicator disappears and video plays normally
4. **Distinguishes bandwidth stalls from intentional camera-off**: real camera-off is signaled via the `video` flag in `media-state` messages and should still fully clear the video

## Files to modify

- `~/video-call/android/app-native/src/main/java/org/raycc/vidly/native/NativeWebRtcClient.kt`
- `~/video-call/android/app-native/src/main/java/org/raycc/vidly/native/NativeCallActivity.kt`
- `~/video-call/public/index.html`

---

## Changes required

### 1. NativeWebRtcClient.kt — track frame arrival timestamps

**Add a timestamp tracking VideoSink for the remote video track.**

When a remote video track is attached to the renderer in `onTrack()`, also attach a lightweight `VideoSink` that records `SystemClock.elapsedRealtime()` on each frame:

```kotlin
// Near the top, new field:
@Volatile private var remoteVideoFrameTimestampMs: Long = 0L

// Custom VideoSink added alongside the renderer in onTrack():
track.addSink(VideoSink { frame ->
    remoteVideoFrameTimestampMs = SystemClock.elapsedRealtime()
    frame.release()
})
```

Don't forget to remove this sink when clearing remote video:

```kotlin
private fun clearRemoteVideo() {
    remoteVideoTrack?.removeSink(remoteVideoFrameSink)  // NEW
    remoteVideoTrack?.removeSink(remoteRenderer)
    remoteVideoTrack = null
    remoteVideoPeerId = null
    remoteRenderer.clearImage()
    onRemoteVideo(false)
}
```

Store the `VideoSink` as a field so it can be passed to `removeSink`.

**Also add a method to check frame staleness** (called from the activity):

```kotlin
fun getRemoteVideoFrameAgeMs(): Long {
    if (remoteVideoTrack == null) return Long.MAX_VALUE
    val now = SystemClock.elapsedRealtime()
    val last = remoteVideoFrameTimestampMs
    return if (last == 0L) Long.MAX_VALUE else now - last
}
```

### 2. NativeCallActivity.kt — periodic health monitor

**Add a Handler-based periodic check** that runs every 3 seconds while `callActive` is true.

When `hasRemoteVideo` is true and frames have stopped for > 5 seconds:
- Show "Video paused — poor network" in `headerStatusLabel` (via `updateHeaderStatus()`)
- Keep the remote renderer as-is (don't clear it)
- The frozen frame stays visible, which is better than a black screen

When a new frame arrives (or hasRemoteVideo becomes false, or the connection disconnects):
- Restore the normal header status (e.g., "Connected" or whatever the WebRTC client reports)

**Recommended approach**:

```kotlin
private val frameHealthHandler = Handler(Looper.getMainLooper())
private var videoStalled = false

private fun startFrameHealthMonitor() {
    frameHealthMonitorRunnable = object : Runnable {
        override fun run() {
            if (!callActive) return
            val age = rtc?.getRemoteVideoFrameAgeMs() ?: Long.MAX_VALUE
            val nowStalled = hasRemoteVideo && age > 5000
            if (nowStalled != videoStalled) {
                videoStalled = nowStalled
                if (nowStalled) {
                    updateHeaderStatus("Paused — poor network")
                } else {
                    updateHeaderStatus("Connected")
                }
            }
            frameHealthHandler.postDelayed(this, 3000)
        }
    }
    frameHealthHandler.post(frameHealthMonitorRunnable)
}

private fun stopFrameHealthMonitor() {
    frameHealthHandler.removeCallbacksAndMessages(null)
    videoStalled = false
}
```

Call `startFrameHealthMonitor()` in `joinRoom()` (after the RTC client is created).
Call `stopFrameHealthMonitor()` in `leaveCall()` and `showJoinPanel()`.

**Important**: When `hasRemoteVideo` becomes false (e.g., remote peer leaves, or `clearRemoteVideo()` is called), the health check should reset — the next check will see `hasRemoteVideo = false` and not show "Paused" status. The `setRemoteVideoActive(false)` callback already exists for this.

### 3. index.html — softer mute handling + frame health monitor

**Change how `onmute` works**: instead of calling `clearActiveRemoteVideo()` on network-induced mute, show a stall overlay and keep the video element intact.

The key insight: `track.onmute` fires for BOTH camera-off AND bandwidth dips. We can distinguish them:
- Intentional camera-off: `track.muted` + media-state `video: false` arrives (this already triggers `clearActiveRemoteVideo()` via the data channel handler)
- Bandwidth dip: only `track.onmute` fires, no media-state change

**Changes in `ensurePeer()`'s `ontrack` handler:**

Replace the current `onmute` logic:

```javascript
// OLD (too aggressive):
track.onmute = () => {
    console.log('[WebRTC] video track muted (camera off)');
    clearActiveRemoteVideo(peerId, track, videoEl, tileEl, remoteStream);
};

// NEW: less aggressive — add a stall overlay, don't clear video:
track.onmute = () => {
    console.log('[WebRTC] video track muted');
    // On mute, don't clear the video element — the last frame stays visible.
    // The real camera-off clear comes from the media-state 'video:false' message.
    // Just add a visual indicator if it's a bandwidth-induced stall.
    peer.lastVideoFrameTime = null;
    videoEl.dataset.stalled = 'true';
    showStallOverlay(tileEl);
};

track.onunmute = () => {
    console.log('[WebRTC] video track un-muted');
    // NEW: remove stall overlay on unmute
    delete videoEl.dataset.stalled;
    hideStallOverlay(tileEl);
    // existing restoration logic stays:
    if (activeRemoteVideoPeerId && activeRemoteVideoPeerId !== peerId) return;
    activeRemoteVideoPeerId = peerId;
    activeRemoteVideoTrack = track;
    if (!remoteStream.getTracks().includes(track)) remoteStream.addTrack(track);
    videoEl.srcObject = remoteStream;
    videoEl.style.display = '';
    tileEl.classList.remove('placeholder');
    videoEl.play().catch(() => {});
};
```

**Also track frame timestamps for the health monitor** — add a `timeupdate` or `playing` event listener on the video element to track frame activity:

```javascript
// In ensurePeer(), after the video element is available:
videoEl.addEventListener('timeupdate', () => {
    peer.lastVideoFrameTime = Date.now();
});
```

**Add periodic health check** — in the `joinRoom()` setup (near the UI setup), start a `setInterval`:

```javascript
// New periodic stall monitor:
let stallCheckInterval = null;

function startStallMonitor() {
    if (stallCheckInterval) return;
    stallCheckInterval = setInterval(() => {
        let anyStalled = false;
        for (const [peerId, peer] of peers) {
            if (peer.videoEl?.dataset.stalled === 'true') {
                anyStalled = true;
                break;
            }
            // Also check: remote video is active but no frames for 5+ seconds
            if (activeRemoteVideoPeerId === peerId && peer.lastVideoFrameTime) {
                const age = Date.now() - peer.lastVideoFrameTime;
                if (age > 5000 && peer.pc.iceConnectionState === 'connected') {
                    anyStalled = true;
                    showStallOverlay(peer.tileEl);
                    peer.videoEl.dataset.stalled = 'true';
                }
            }
        }
        if (!anyStalled && document.querySelector('.stall-overlay')) {
            document.querySelectorAll('.stall-overlay').forEach(el => el.remove());
        }
    }, 3000);
}

function stopStallMonitor() {
    if (stallCheckInterval) {
        clearInterval(stallCheckInterval);
        stallCheckInterval = null;
    }
    document.querySelectorAll('.stall-overlay').forEach(el => el.remove());
}

// Called in joinRoom() after setup
// Called in leaveCall() to stop
```

**Stall overlay CSS** (add to the `<style>` section):

```css
.stall-overlay {
    position: absolute; inset: 0;
    display: grid; place-items: center;
    background: rgba(0, 0, 0, 0.5);
    color: var(--muted); font-size: 13px;
    pointer-events: none;
    z-index: 2;
}
.stall-overlay::after {
    content: "Buffering…";
}
```

**Helper functions:**

```javascript
function showStallOverlay(tileEl) {
    if (tileEl.querySelector('.stall-overlay')) return;
    const overlay = document.createElement('div');
    overlay.className = 'stall-overlay';
    tileEl.appendChild(overlay);
}

function hideStallOverlay(tileEl) {
    const overlay = tileEl?.querySelector('.stall-overlay');
    if (overlay) overlay.remove();
}
```

**Clean up stall overlay** when removing a peer:

In `removePeer()`:
```javascript
hideStallOverlay(peer.tileEl);
```

**Also clean up** when clearActiveRemoteVideo() is called (camera truly off):
In `clearActiveRemoteVideo()`:
```javascript
hideStallOverlay(tileEl);
```

---

## Success criteria

1. **Native**: During network instability, header status changes to "Paused — poor network" while connection is alive but video frames stop. When bandwidth recovers, status returns to "Connected" and video plays normally.
2. **Native**: Turning off camera (media-state `video: false`) still clears remote video as before — the stall check doesn't interfere.
3. **WebView**: During network dip, the tile shows "Buffering…" overlay on top of the frozen last frame. When bandwidth recovers, the overlay disappears and video plays. No clearing of srcObject or placeholder state.
4. **WebView**: Intentional camera-off (via media-state `video: false`) still fully clears the video tile and shows "Waiting for video…" placeholder.
5. **Both**: When the remote peer leaves or disconnects entirely, no stale "Paused" indicators remain.

## Constraints
Keep all existing functionality: chat, file transfer, reactions, GIF picker, PiP, fullscreen, camera preview flow, participants, header