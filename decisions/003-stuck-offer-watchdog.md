# 003 — Stuck-Offer Watchdog (10s Rollback + Re-offer, Bounded)

**Status:** Accepted (2026-07-10, commit e4a492d). Amended 2026-11-09: retry cap + logging.
**Code:** `NativeWebRtcClient.scheduleOfferTimeout` / `cancelOfferTimeout`, `OFFER_TIMEOUT_MS = 10_000`, `OFFER_MAX_RETRIES = 3`, `Peer.offerRetries`; web `scheduleOfferTimeout` in `public/index.html` (same constants)

## Problem

Signaling messages can be lost with neither side noticing. A WebSocket can be *silently* dead — `send()` happily buffers into a dying TCP connection for many seconds before OkHttp's 20s ping or the server's 25s ping notices. If WebRTC emits an offer during that window (the ICE-restart path is the classic case: network flaps, ICE fails, we offer into the dead socket), the offer vanishes.

The sender is now stranded in `HAVE_LOCAL_OFFER`. Under Perfect Negotiation (004), an **impolite** peer in that state ignores every incoming offer (`ignoreOffer = !polite && offerCollision`, and non-STABLE counts as collision). The remote keeps offering; we keep ignoring; nothing external can ever unstick us. The call is deadlocked until torn down manually. This was the last remaining deadlock after the symmetric-rebuild fix (001) — rebuild handles *detected* disconnections, but a silently-dead socket is by definition undetected.

## Decision

Whenever we send an offer (`setLocalAndSend` with type OFFER), arm a per-peer watchdog. If after **10 seconds** the PeerConnection is still in `HAVE_LOCAL_OFFER`, roll the local description back to STABLE, then call `makeOffer` again. The watchdog is cancelled on any successful `setRemoteDescription` — their answer applied, or their offer accepted, both mean negotiation is moving.

**Why 10 seconds:** it must comfortably exceed one round trip plus SDP processing on the target network (a long-haul China↔US link over a policed middlebox, measured ~145 ms RTT with 10% loss and multi-second spikes — see 008), so a merely-slow answer doesn't get preempted — a premature rollback would discard a valid pending negotiation and add churn. It must also be short enough that the user experiences a hiccup, not a dead call; combined with signaling reconnect (max 10s backoff) recovery lands within one "why is this call dead" moment.

**Why rollback + retry instead of just retry:** `makeOffer` refuses to run unless signaling state is STABLE — that guard is what prevents offer storms and glare, and weakening it for a retry path would reopen those bugs. Rolling back is also semantically correct: it abandons the lost offer's ICE/SDP proposal entirely, so the regenerated offer reflects *current* state (any tracks added meanwhile, a fresh ICE generation from `restartIce`). And critically, an impolite peer that has rolled back to STABLE will now *accept* the remote's incoming offer instead of ignoring it — the rollback alone can break the deadlock even before our re-offer arrives.

**Bounded (2026-11-09).** Each peer counts consecutive fires (`offerRetries`). Fires 1–3 roll back and re-offer; the 4th (`n > OFFER_MAX_RETRIES`) rolls back and stops — logged `offer-abandoned`, no re-offer. We are left STABLE, so a remote offer is still accepted, and any later negotiation (track change, ICE restart) still sends one fresh offer, which gets one more timeout before abandoning again. Every fire logs attempt n and ICE state: web event `offer-timeout {n, ice, pc}`, Android tag `VidlyNego`. The counter is reset **only** by a successful `setRemoteDescription` — never in schedule/cancel, which run on every re-offer; a rebuild (001) starts a fresh Peer and counter. Android specifics: rollback failures are logged instead of swallowed (a failed rollback still stuck in `HAVE_LOCAL_OFFER` re-arms, counted); if the watchdog fires while an SRD(answer) is pending (004's `settingRemoteAnswer`) it re-arms **without** counting, because negotiation is moving and a ROLLBACK chained behind the answer would fail against STABLE.

Why a cap: before it, an offer to a peer whose seat was a server-side ghost (009) was re-sent every 10s **forever** — prod reports and the harness both showed retries continuing past grace expiry with the PC stuck at `ice=new`. 009 removes that cause; the cap guarantees no future one can loop.

**Why this matters mostly for impolite peers:** a polite peer stuck in `HAVE_LOCAL_OFFER` self-heals — the moment the remote offers, the polite peer rolls back and answers (that's Perfect Negotiation's glare handling). The impolite peer has no such escape hatch; the watchdog is its only way out. The watchdog is armed on both roles anyway (a polite peer whose *remote* never offers again is also stuck), but the deadlock it exists to break is the impolite one.

## Trade-offs and failure modes

- If the answer arrives just after 10s, we roll back and re-offer; the late answer is rejected (no matching local offer) and one extra negotiation round happens. Harmless, but visible as a "Renegotiating..." status blip.
- At most 3 re-offers (~40s from the first offer to `offer-abandoned`). On a permanently dead socket the reconnect logic (001) rebuilds before or after that; on a live socket whose remote never answers, the offer is abandoned and the call sits STABLE until either side negotiates again. Acceptable: the only known cause of a live-socket non-answer (ghost seats) is now nacked by the server (009).
- Abandoning with a media change pending (verified 2026-11-09, Chromium, web client): an un-answered `addTransceiver` → 4 timeouts → `offer-abandoned` at ~41s, then **no further offers** — negotiation-needed does not re-fire after the rollback because its flag never cleared. The cap therefore really stops; the cost is the other way round: that pending change stays un-negotiated until the next local change or a remote offer. Acceptable, since a remote that ignores 4 offers over a live socket is already broken. Not verified on Safari or Android.
- The `offer-timeout` count is the diagnostic: n=1 at setup means 004's answer-pending race is back; n climbing to `offer-abandoned` means signals aren't reaching the remote.

## Revisit if

- The signaling protocol gains delivery acknowledgements — then lost offers become detectable directly and the timeout could shrink or disappear. **Partly happened (2026-11-09):** the server's `signal-undeliverable` (009) is a *negative* ack for a missing / non-live / non-OPEN target. It covers "nobody there", not "lost in a silently-dead socket" (the original case), so the timeout stays. Only a positive end-to-end ack would let it go.
