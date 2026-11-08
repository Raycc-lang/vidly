# 003 — Stuck-Offer Watchdog (10s Rollback + Re-offer)

**Status:** Accepted (2026-07-10, commit e4a492d)
**Code:** `NativeWebRtcClient.scheduleOfferTimeout` / `cancelOfferTimeout`, `OFFER_TIMEOUT_MS = 10_000`

## Problem

Signaling messages can be lost with neither side noticing. A WebSocket can be *silently* dead — `send()` happily buffers into a dying TCP connection for many seconds before OkHttp's 20s ping or the server's 25s ping notices. If WebRTC emits an offer during that window (the ICE-restart path is the classic case: network flaps, ICE fails, we offer into the dead socket), the offer vanishes.

The sender is now stranded in `HAVE_LOCAL_OFFER`. Under Perfect Negotiation (004), an **impolite** peer in that state ignores every incoming offer (`ignoreOffer = !polite && offerCollision`, and non-STABLE counts as collision). The remote keeps offering; we keep ignoring; nothing external can ever unstick us. The call is deadlocked until torn down manually. This was the last remaining deadlock after the symmetric-rebuild fix (001) — rebuild handles *detected* disconnections, but a silently-dead socket is by definition undetected.

## Decision

Whenever we send an offer (`setLocalAndSend` with type OFFER), arm a per-peer watchdog. If after **10 seconds** the PeerConnection is still in `HAVE_LOCAL_OFFER`, roll the local description back to STABLE, then call `makeOffer` again. The watchdog is cancelled on any successful `setRemoteDescription` — their answer applied, or their offer accepted, both mean negotiation is moving.

**Why 10 seconds:** it must comfortably exceed one round trip plus SDP processing on the target network (weak international links, multi-second RTT spikes), so a merely-slow answer doesn't get preempted — a premature rollback would discard a valid pending negotiation and add churn. It must also be short enough that the user experiences a hiccup, not a dead call; combined with signaling reconnect (max 10s backoff) recovery lands within one "why is this call dead" moment.

**Why rollback + retry instead of just retry:** `makeOffer` refuses to run unless signaling state is STABLE — that guard is what prevents offer storms and glare, and weakening it for a retry path would reopen those bugs. Rolling back is also semantically correct: it abandons the lost offer's ICE/SDP proposal entirely, so the regenerated offer reflects *current* state (any tracks added meanwhile, a fresh ICE generation from `restartIce`). And critically, an impolite peer that has rolled back to STABLE will now *accept* the remote's incoming offer instead of ignoring it — the rollback alone can break the deadlock even before our re-offer arrives.

**Why this matters mostly for impolite peers:** a polite peer stuck in `HAVE_LOCAL_OFFER` self-heals — the moment the remote offers, the polite peer rolls back and answers (that's Perfect Negotiation's glare handling). The impolite peer has no such escape hatch; the watchdog is its only way out. The watchdog is armed on both roles anyway (a polite peer whose *remote* never offers again is also stuck), but the deadlock it exists to break is the impolite one.

## Trade-offs and failure modes

- If the answer arrives just after 10s, we roll back and re-offer; the late answer is rejected (no matching local offer) and one extra negotiation round happens. Harmless, but visible as a "Renegotiating..." status blip.
- The watchdog assumes the signaling path will eventually deliver; on a permanently dead socket it re-offers into the void every 10s until the reconnect logic rebuilds everything.

## Revisit if

- The signaling protocol gains delivery acknowledgements — then lost offers become detectable directly and the timeout could shrink or disappear.
