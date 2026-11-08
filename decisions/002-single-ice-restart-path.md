# 002 — Single ICE Restart Path (Observer Only)

**Status:** Accepted (2026-07-10, commit e4a492d)
**Code:** `NativeWebRtcClient.PeerObserver.onIceConnectionChange`, `scheduleIceRestart`, `restartIce`; `NativeCallActivity.checkFrameHealth` (UI-only)

## Problem

There were two independent mechanisms that could call `restartIce()`:

1. The ICE connection observer: `DISCONNECTED` schedules a restart after 8 seconds (`ICE_RESTART_DELAY_MS`), `FAILED` restarts immediately, `CONNECTED`/`COMPLETED` cancels any pending restart.
2. A frame-health watchdog in the Activity: if no remote video frame arrived for 5 seconds, restart ICE.

Two triggers are worse than one. The frame watchdog fired on conditions that are *not* connection failures — a static screencast, a sender-side camera pause, decoder hiccups, or ordinary weak-network frame droughts while ICE was still `CONNECTED`. Its restart raced the observer's: it could restart a connection the observer considered healthy, generating an unnecessary offer that then collided with real renegotiation traffic (glare), and it reset the observer's own timing assumptions. Debugging was miserable because "why did ICE restart?" had two answers.

The final push to remove it: code inspection showed the watchdog's `restartIce()` call was effectively **dead** anyway — the frame-age sink was only attached to the remote *screen* track, and `checkFrameHealth` explicitly skips the stall check while a screen share is active. So the path either never ran or ran when it shouldn't.

## Alternatives considered

- **Keep both, add coordination flags.** Rejected: coordination between a UI-timer path and a WebRTC-callback path is exactly the kind of cross-thread state this codebase just finished eliminating.
- **Frame-health as the only trigger.** Rejected: frame age measures media symptoms, not transport state. ICE state is authoritative for the transport.

## Decision

`restartIce()` is called from exactly one place: the ICE observer in `NativeWebRtcClient`.

- `DISCONNECTED` → restart scheduled after **8s** (posted with the `Peer` as handler token, so peer close cancels it). The delay exists because DISCONNECTED frequently self-heals on wobbly networks; restarting instantly would churn.
- `FAILED` → restart immediately.
- `CONNECTED`/`COMPLETED` → cancel any pending restart.
- The restart re-checks ICE state when the delayed runnable fires — a recovered connection is left alone.

`checkFrameHealth()` in the Activity survives, but as **UI only**: it drives the "Paused - poor network" overlay (checked every 3s, stall threshold 5s, camera track now included in the frame sink, screen shares exempt). It must never touch ICE.

## Trade-offs and failure modes

- A frozen media stream while ICE stays `CONNECTED` (e.g. one-way media loss that ICE consent checks don't catch quickly) will show the overlay but never auto-restart. Deliberate: the user sees the state and can toggle or rejoin.
- The 8s DISCONNECTED delay means a genuine outage takes ~8s+ICE time to recover instead of ~ICE time.

## Revisit if

- Field evidence shows frozen media with ICE stuck `CONNECTED` for extended periods. The fix would be `getStats()`-based transport-level detection (bytes-received deltas), **not** frame age — and it would feed the *same* observer path, keeping a single trigger.
