# Continuation: fix remaining issues — layout, camera frame residue, and duplicate video guard

Some of the effects weren't quite what I was looking for, so I made a few adjustments; however, this introduced additional issues. Please continue working on the fixes.
##  changes 

1. **VideoContainer fixed height**: Changed from `LinearLayout.LayoutParams(-1, 0, 1f)` (weight-based) to `LinearLayout.LayoutParams(-1, widthPixels * 9 / 16)` (fixed 16:9 height). This keeps the video area from resizing when chat expands/collapses.
2. **Fullscreen orientation**: Changed from `SCREEN_ORIENTATION_UNSPECIFIED` (respects system auto-rotate) to `SCREEN_ORIENTATION_LANDSCAPE` (forces landscape regardless of system setting).

## Remaining issues to fix

### Issue 1: Layout — bottomContainer not pinned to screen bottom

**Current state**: `callStack` is a vertical LinearLayout with headerBar → videoContainer (fixed height) → bottomContainer. Since bottomContainer is the last child, it sits directly below the video — not at the bottom of the screen. When chat is collapsed, there's empty space BELOW the bottom container. The controls row floats in the middle of the screen.

**Expected**: Bottom container must be **pinned to the screen bottom** at all times. When chat is collapsed, there should be empty/dark space between the bottom of the video and the top of the bottom container.


### Issue 2: Camera preview — local renderer frozen frame persists after cancel/off

**Current state**: When the user taps "Cam off" → enters preview mode → local camera feed shows in the PIP (localRenderer). When the user taps ✕ (cancel) or the camera toggles off, `cancelCameraPreview()` calls `stopVideo()` which disposes the video track and calls `localRenderer.clearImage()`. However, the `localRenderer` view (112×160 SurfaceViewRenderer at top-right) still shows the **last frozen frame** from the camera. This frozen frame blocks the remote video behind it.

This exists in the WebView client too (when camera is turned off, the local video preview freezes on the last frame instead of going black).

**Expected**: After camera is turned off (either by cancel or direct toggle off):
- The local renderer should disappear completely (no frozen frame visible)
- The remote video below/behind should be fully visible and unobstructed
- When camera is turned on again, the local renderer reappears with live feed

### Issue 3: Duplicate remote video guard

**Current state**: If a remote peer is currently sharing their screen or camera, and a second remote peer simultaneously begins sharing as well, the two streams will coexist and overlap. Currently, there are no protective mechanisms or corresponding handling logic in place to address this specific scenario.

**Expected**: 
- **Native client**: If a remote video track is already being displayed on `remoteRenderer`, any additional incoming remote video tracks should be silently ignored/dropped. The first peer's video continues uninterrupted.

## Files to modify

- `~/video-call/android/app-native/src/main/java/org/raycc/vidly/native/NativeCallActivity.kt` — layout + camera + duplicate video guard
- `~/video-call/android/app-native/src/main/java/org/raycc/vidly/native/NativeWebRtcClient.kt` — duplicate remote video guard in onTrack
- `~/video-call/public/index.html` — camera frozen frame fix + duplicate remote video guard (same two issues)

## Success criteria

1. Bottom container pinned to screen bottom; controls row always at the bottom
2. When chat is collapsed: empty space between bottom of video and top of bottom container
3. Camera off / preview cancelled: local PIP completely disappears (no frozen frame blocking remote video)
4. Camera on: local PIP appears with live feed
5. Two browser peers sharing screen simultaneously: second peer's video stream is silently dropped, first peer's video continues uninterrupted
6. Same guard applies in both native and WebView clients