# 004 — Perfect Negotiation with Rollback, Main-Thread-Only State

**Status:** Accepted (2026-07-10, commit e4a492d; pattern predates, threading fix is new)
**Code:** `NativeWebRtcClient.handleDescription`, `makeOffer`, `PeerObserver.onRenegotiationNeeded`, the `main` Handler

## Problem

Both sides of a call can decide to renegotiate at the same time — user A turns on their camera while user B starts a screen share, or both sides restart ICE after a network blip. Each sends an offer; each receives the other's offer while holding its own pending one. This is "glare". Naive handling (always apply the incoming offer, or always reject it) either corrupts SDP state on both sides or deadlocks with both peers waiting for an answer that never comes.

## Decision

We implement the W3C **Perfect Negotiation** pattern, mirroring the web client:

- Each peer pair has fixed roles: the peer that was already in the room when you joined is **impolite** from your perspective (you offer first, via `onJoined`); a peer that joins after you is **polite** (`ensurePeer(polite = true)` in `onPeerJoined`/`onSignal`).
- On an incoming offer, `offerCollision` = (we are making an offer, or signaling state ≠ STABLE). The **impolite** peer ignores the colliding offer entirely and lets its own offer win. The **polite** peer yields: it **rolls back** its local description (`setLocalDescription(ROLLBACK)`), then applies the remote offer and answers.
- `onRenegotiationNeeded` simply calls `makeOffer`; `makeOffer` refuses unless state is STABLE and `makingOffer` is false. Glare is handled at receive time, not send time.

**Why rollback is needed:** without it, the polite peer in `HAVE_LOCAL_OFFER` cannot call `setRemoteDescription(offer)` — that's an invalid state transition. Rollback is the only WebRTC primitive that abandons a pending local offer and returns to STABLE so the remote offer can be applied. The abandoned offer's changes aren't lost: WebRTC re-fires `onRenegotiationNeeded` for anything still un-negotiated.

**Why `makingOffer` (and all negotiation state) is main-thread-only:** WebRTC invokes `PeerConnection.Observer` and `SdpObserver` callbacks on its internal signaling/worker threads, while UI actions (camera toggle → `addTrack`) run on the Android main thread. `makingOffer`, `ignoreOffer`, the `peers` map, senders, and remote-track roles were being read and written from both, producing races like an offer sent with a stale `makingOffer=false` (double offer) or a collision check reading a half-updated flag. Rather than sprinkle locks — deadlock-prone against WebRTC's own internal locking — **every callback is marshalled onto the single `main` Handler** before touching this state (~22 `main.post` sites). One deliberate exception: the file channel's `onBufferedAmountChange` stays on the WebRTC thread, because it only notifies a lock (backpressure wake must be prompt) and touches no negotiation state.

## Alternatives considered

- **Locks around the flags.** Rejected: invites lock-order inversions with libwebrtc's internal threads, and the state is UI-adjacent anyway.
- **Single-offerer protocol (only one designated side ever offers).** Rejected: breaks `onRenegotiationNeeded`-driven track adds from the non-offerer side and diverges from the web client.

## Trade-offs and failure modes

- Everything negotiation-related funnels through the main thread; a blocked main thread delays SDP handling. Acceptable at this app's scale.
- The impolite peer ignoring offers is what makes the stuck-offer deadlock possible (003 is the required companion watchdog).

## Revisit if

- Negotiation work ever becomes heavy enough to jank the UI — then move it to a dedicated single-threaded handler, preserving the "one thread owns the state" invariant, never to "multiple threads with locks".
