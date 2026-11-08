# 006 — Audio Output Routing Pinned at onWebRtcAudioTrackStart

**Status:** Accepted (2026-07-10, commit e4a492d; callback hook)
**Code:** `NativeWebRtcClient.start()` (`setAudioTrackStateCallback`), `NativeWebRtcClient.setPreferredOutputDevice` (reflection), `NativeCallActivity.applyAudioRoute`

## Problem

Routing call audio to the right output (speaker vs earpiece vs headphones/Bluetooth) requires two cooperating settings on Android 12+ (API 31):

1. `AudioManager.setCommunicationDevice(target)` — the system-level route.
2. `AudioTrack.setPreferredDevice(device)` on **WebRTC's internal playback AudioTrack** — the per-track hint, which only takes effect on the *next* routing change.

Problem one: that internal `AudioTrack` doesn't exist until WebRTC's `JavaAudioDeviceModule` creates it and calls `play()`, which happens at an unpredictable time after the call connects. The old code applied routing on fixed delays (300/800/1500ms after connect) and raced: if the track wasn't created yet, the preferred-device call silently no-op'd (the field is null), and the call started on the wrong output — the classic "call audio comes out of the earpiece even though speaker is selected" bug, unreproducible on fast devices and constant on slow ones.

Problem two: order matters. `setPreferredDevice` must be set **before** `setCommunicationDevice`, because the communication-device change is what triggers the re-route that reads the hint. Swapped order left the track pinned to the previous device while the UI icon updated.

## Decision

Hook the deterministic moment instead of guessing it: `JavaAudioDeviceModule.Builder.setAudioTrackStateCallback` → `onWebRtcAudioTrackStart` fires immediately after the ADM's internal AudioTrack is created and `play()` is called — the earliest instant at which routing can actually stick. The client surfaces this as the `onAudioOutputStarted` constructor callback (fired on WebRTC's AudioTrack thread; the Activity marshals to main and calls `applyAudioRoute()`). Fixed-delay retries are gone. Hot-plug events (`AudioDeviceCallback`) additionally re-apply the route whenever devices appear/disappear.

**Why reflection:** the WebRTC Android SDK does not expose the ADM's internal `android.media.AudioTrack`. `setPreferredOutputDevice` reaches it via `JavaAudioDeviceModule.audioOutput` → `WebRtcAudioTrack.audioTrack` declared-field reflection. Fragile by nature, but wrapped in try/catch (fails soft to system routing) and these field names have been stable across the libwebrtc versions we've shipped. There is no supported alternative short of forking the ADM.

**Version behavior:**
- **Android 12+ (API 31, `S`)** — full path: preferred device first, then `setCommunicationDevice`/`clearCommunicationDevice`. This is the tested, primary path (both daily-driver devices run 12+).
- **Android 6–11 (M–R)** — fallback: preferred-device reflection plus legacy `isSpeakerphoneOn`. Best-effort, lightly tested.
- **Known gap:** pre-12 **Bluetooth** audio doesn't work correctly — we never call `startBluetoothSco()`, so BT headsets get A2DP mis-selected. Open, deliberately deferred (no pre-12 BT users today).

## Trade-offs and failure modes

- Reflection can break on a libwebrtc upgrade: symptom is external-device routing reverting to system defaults, with a `VidlyAudio` warning in logcat. Check that log line after every WebRTC dependency bump.
- If the ADM restarts its track mid-call, `onWebRtcAudioTrackStart` fires again and re-applies — self-healing.

## Revisit if

- A libwebrtc upgrade breaks the field names (immediate), or pre-12 BT support is ever needed (add SCO handling), or WebRTC exposes a supported preferred-device API.
