# Bug: Vidly Native crashes ~1 second after joining a room

## Context
The app is at `/home/ray/video-call/android/`. The native implementation is in `app/src/main/java/org/raycc/vidly/native/`.

The crash happens when the user joins a room in the NativeCallActivity. The join UI works fine, but about 1 second after joining, the app crashes.

## Your task
1. Read all 4 native files to understand the flow:
   - NativeCallActivity.kt — UI, join flow, calls `rtc?.start()` and `signaling.connect()`
   - NativeWebRtcClient.kt — PeerConnection, camera, mic setup
   - NativeSignalingClient.kt — WebSocket signaling
   - BeautyVideoProcessor.kt — GPU OpenGL beauty filter

2. Look for potential crash causes in the join → call setup flow:
   - Thread safety issues (WebRTC callbacks on non-main threads)
   - EGL/OpenGL context issues on first frame
   - Missing null checks
   - Race conditions between signaling and PeerConnection setup
   - SurfaceViewRenderer init issues
   - Permission issues

3. To reproduce: 
   - `cd /home/ray/video-call/android && ./gradlew assembleDebug`
   - `adb install -r app/build/outputs/apk/debug/app-debug.apk`
   - `adb logcat -c && adb shell am start -n org.raycc.vidly/.native.NativeCallActivity`
   - Wait a few seconds, then check: `adb logcat -d -s "AndroidRuntime:E" "System.err:W" "WebRTC:*"`
   - If no crash in logcat, the crash may happen when a remote peer connects (signal flow). Check the SDP/ICE handling code for null safety.

4. If you find the bug and it's a simple fix (< 20 lines), fix it directly. If it's complex, describe the issue and propose a staged fix plan.

5. After any fix, rebuild and install: `./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
