# 004 — Perfect Negotiation with Rollback, Main-Thread-Only State

**Status:** Accepted (2026-07-10, commit e4a492d; pattern predates, threading fix is new). Amended 2026-11-09: answer-pending guard (see below).
**Code:** `NativeWebRtcClient.handleDescription`, `setRemoteDescription` (`pendingRemoteAnswers`), `makeOffer`, `PeerObserver.onRenegotiationNeeded`, the `main` Handler; web `onPeerSignal` (`peer.settingRemoteAnswer`) in `public/index.html`

## Problem

Both sides of a call can decide to renegotiate at the same time — user A turns on their camera while user B starts a screen share, or both sides restart ICE after a network blip. Each sends an offer; each receives the other's offer while holding its own pending one. This is "glare". Naive handling (always apply the incoming offer, or always reject it) either corrupts SDP state on both sides or deadlocks with both peers waiting for an answer that never comes.

## Decision

We implement the W3C **Perfect Negotiation** pattern, mirroring the web client:

- Each peer pair has fixed roles: the peer that was already in the room when you joined is **impolite** from your perspective (you offer first, via `onJoined`); a peer that joins after you is **polite** (`ensurePeer(polite = true)` in `onPeerJoined`/`onSignal`).
- On an incoming offer, `offerCollision` = !(not making an offer **and** (signaling state = STABLE **or** our `setRemoteDescription(answer)` is pending)) — the W3C `isSettingRemoteAnswerPending` form; see the 2026-11-09 amendment. The **impolite** peer ignores the colliding offer entirely and lets its own offer win. The **polite** peer yields: it **rolls back** its local description (`setLocalDescription(ROLLBACK)`), then applies the remote offer and answers.
- `onRenegotiationNeeded` simply calls `makeOffer`; `makeOffer` refuses unless state is STABLE and `makingOffer` is false. Glare is handled at receive time, not send time.

**Why rollback is needed:** without it, the polite peer in `HAVE_LOCAL_OFFER` cannot call `setRemoteDescription(offer)` — that's an invalid state transition. Rollback is the only WebRTC primitive that abandons a pending local offer and returns to STABLE so the remote offer can be applied. The abandoned offer's changes aren't lost: WebRTC re-fires `onRenegotiationNeeded` for anything still un-negotiated.

**Why `makingOffer` (and all negotiation state) is main-thread-only:** WebRTC invokes `PeerConnection.Observer` and `SdpObserver` callbacks on its internal signaling/worker threads, while UI actions (camera toggle → `addTrack`) run on the Android main thread. `makingOffer`, `ignoreOffer`, the `peers` map, senders, and remote-track roles were being read and written from both, producing races like an offer sent with a stale `makingOffer=false` (double offer) or a collision check reading a half-updated flag. Rather than sprinkle locks — deadlock-prone against WebRTC's own internal locking — **every callback is marshalled onto the single `main` Handler** before touching this state (~22 `main.post` sites). One deliberate exception: the file channel's `onBufferedAmountChange` stays on the WebRTC thread, because it only notifies a lock (backpressure wake must be prompt) and touches no negotiation state.

### Amendment 2026-11-09 — an offer behind our pending answer is not a collision

**Bug.** The original check was `makingOffer || signalingState != STABLE`. `setRemoteDescription` is async, so while our SRD(answer) is still running the state still reads `have-local-offer`. At every call setup the polite peer sends a follow-up offer right after its answer (its placeholder `addTransceiver('audio')` can't be reused by the joiner's m-line), and that offer lands in exactly this window. The impolite joiner ignored it; the polite side sat in `have-local-offer` until the 10s watchdog (003) — the "Renegotiating..." at call start. It is a race, not deterministic: 5/8 and 12/12 setups stalled on the dev machine with the old code, 4/8 on another run, 3 of 4 connections in prod call-reports. Not caused by `restartIce()` or network loss (no `ice-restart` event in any prod report).

Android had a second, Android-only failure on the polite side: the explicit rollback branch chained a ROLLBACK behind the pending answer; it then ran against STABLE, failed, and the incoming offer was dropped.

**Fix.**
- **Web:** `peer.settingRemoteAnswer` is true for the duration of `await pc.setRemoteDescription(answer)`; an offer is ready-to-accept if `!makingOffer && (stable || settingRemoteAnswer)`. An ignored offer logs `offer-ignored {sig, makingOffer}`.
- **Android:** counter `pendingRemoteAnswers` (main-only; incremented before the async SRD(answer), decremented in both observer callbacks — a counter so a stale and a fresh answer in flight can't clear it early), exposed as `settingRemoteAnswer`. The polite rollback branch is **skipped** while an answer is pending: the answer takes us to STABLE and the offer's SRD runs after it. This relies on libwebrtc M144's per-PC **operations chain** (SRD/SLD/createOffer/createAnswer run FIFO), confirmed from the `.so` symbols — re-check after a libwebrtc upgrade. ICE candidates now use the chained async `addIceCandidate(candidate, AddIceObserver)` so candidates for a chained offer apply after its SRD, not against the previous description. Rollback/SRD failures log to tag `VidlyNego` instead of being swallowed; a polite rollback that fails because we're already STABLE just applies the offer.

**Expected residue.** After the fix, 0/48+ harness setup trials stalled (`tools/negotiation-repro.mjs --only setup`). Exactly **one** `offer-ignored` per setup remains and is genuine glare — the polite peer's first offer reaching the joiner while the joiner's own offer is outstanding. Expected and harmless; don't "fix" it.

## Alternatives considered

- **Locks around the flags.** Rejected: invites lock-order inversions with libwebrtc's internal threads, and the state is UI-adjacent anyway.
- **Single-offerer protocol (only one designated side ever offers).** Rejected: breaks `onRenegotiationNeeded`-driven track adds from the non-offerer side and diverges from the web client.

## Trade-offs and failure modes

- Everything negotiation-related funnels through the main thread; a blocked main thread delays SDP handling. Acceptable at this app's scale.
- The impolite peer ignoring offers is what makes the stuck-offer deadlock possible (003 is the required companion watchdog).
- **Deferred (2026-11-09):** the polite peer's placeholder `addTransceiver('audio')` was deliberately **not** removed. It costs one extra negotiation round (≈ one RTT, ~250 ms here) per call setup; removing it changes the SDP m-line layout on every call and touches the screen-audio sender path, which is more risk than the RTT is worth.
- Android correctness of the answer-pending guard depends on libwebrtc's operations chain ordering, which is an implementation detail, not API.

## Revisit if

- Call-setup latency matters enough to spend a release on the placeholder transceiver (then remove it on both platforms together and re-run scenarios 5 and 7 plus the harness).
- A libwebrtc upgrade changes SRD/addIceCandidate queuing semantics — re-verify the Android answer-pending path (harness covers web only; run manual scenario 7 on two Android devices and grep `VidlyNego`).
- Negotiation work ever becomes heavy enough to jank the UI — then move it to a dedicated single-threaded handler, preserving the "one thread owns the state" invariant, never to "multiple threads with locks".
