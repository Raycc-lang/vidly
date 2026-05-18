# Task

Replace the CPU-based BeautyVideoProcessor with a GPU (OpenGL ES 2.0) implementation. The current processor does per-pixel YUV processing on CPU — too slow for 720p@30fps. Rewrite it as an OpenGL shader pipeline.

# Project

Location: `/home/ray/video-call/android/`
Package: `org.raycc.vidly.native`

## Current files (DO NOT modify NativeCallActivity.kt — it calls the same API)

- `NativeCallActivity.kt` — UI, calls `rtc?.setBeautyEnabled(bool)` and `rtc?.setBeautyIntensity(float)`
- `NativeWebRtcClient.kt` — creates `BeautyVideoProcessor()`, calls `videoSource.setVideoProcessor(beautyProcessor)`, starts capturer at 1280x720@30
- `BeautyVideoProcessor.kt` — **THIS IS THE FILE TO REPLACE** — current CPU implementation using `VideoProcessor` interface
- `NativeSignalingClient.kt` — WebSocket signaling (not relevant here)

## What to build

Replace `BeautyVideoProcessor.kt` with a GPU implementation. The key insight: `VideoSource.setVideoProcessor()` gives you `onFrameCaptured(VideoFrame)` → you process → call `sink.onFrame(processedFrame)`. The GPU version should:

1. Create an offscreen EGL context + PBuffer surface (1280x720)
2. Create an OES external texture to receive camera frames
3. Upload each incoming `VideoFrame` (which has a `TextureBuffer` with OES texture ID) to the OES texture
4. Render through OpenGL fragment shader with:
   - **Skin smoothing**: 2-pass separable Gaussian blur on luma, blended with original based on intensity
   - **Brightness lift**: add offset to luma
   - **Contrast**: scale around midpoint
   - **Saturation**: scale chroma
5. Read back the rendered frame as `JavaI420Buffer` (or `TextureBuffer` if you can create one from the GL output)
6. Pass to `sink.onFrame()`

## Shader approach

Use a single-pass shader for simplicity (blur is approximate — box blur with 9-tap kernel is fine for MVP):

```glsl
// Vertex shader: fullscreen quad, pass through OES texture coords
// Fragment shader:
// - Sample OES texture (external sampler)
// - Convert RGB → YUV
// - Apply blur on Y channel (9-tap box blur using offsets)
// - Blend blurred Y with original Y based on intensity
// - Apply brightness + contrast to Y
// - Apply saturation to UV
// - Convert back to RGB
// - Output
```

## Constraints

- Must NOT change NativeCallActivity.kt — it already calls `setBeautyEnabled()` and `setBeautyIntensity()`
- Must NOT change NativeWebRtcClient.kt API — it creates `BeautyVideoProcessor()` and calls `setVideoProcessor()`
- Keep the same public API: `enabled: Boolean`, `intensity: Float`, `setSink(VideoSink?)`
- The class must implement `org.webrtc.VideoProcessor`
- Use only OpenGL ES 2.0 (platform API, no extra dependencies)
- Must handle frame rotation (VideoFrame.rotation)
- Must release EGL resources in a `dispose()` method (NativeWebRtcClient doesn't call dispose on the processor explicitly, so hook into setSink(null) or add a cleanup path)

## Verification

After implementation:
1. `cd /home/ray/video-call/android && ./gradlew assembleDebug` — must compile
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Launch: `adb shell am start -n org.raycc.vidly/.native.NativeCallActivity`

The app should show local camera preview with beauty filter applied when the Beauty toggle is on.
