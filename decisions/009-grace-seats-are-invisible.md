# 009 — Grace-Period Seats Are Invisible; Undeliverable Signals Are Nacked

**Status:** Accepted (2026-11-09)
**Code:** `server.js` `isLiveSeat`, `announcePeerLeft` (`ws.leftAt`), join handler (cap / `existingPeers` / username check / ghost eviction), `watch`, `signal` relay; web `handleSignaling` case `signal-undeliverable`; Android `NativeSignalingClient.Listener.onSignalUndeliverable`, `NativeWebRtcClient.onSignalUndeliverable`, `NativeCallActivity.onSignalUndeliverable`

## Problem

When a socket drops, the server announces `peer-left` at once but keeps the seat in the room map for 180s (`leftAnnounced`) so the same clientId can resume it (001). Before this decision that **ghost** seat was otherwise treated as live:

1. **Listed in `joined.peers`.** A new joiner offered into the closed socket. The relay dropped it silently; at grace expiry `leaveRoom` sends no `peer-left` (already announced), so the joiner never learned the peer was gone and the watchdog (003) re-offered every 10s **forever** — verified: retries continued after expiry, PC stuck at `ice=new`.
2. **Held its username.** Same name + a *different* clientId (other device/browser, private window, cleared storage) within grace got `username-taken` and was bounced to the landing page. A same-clientId reconnect was never affected (its own seat is excluded).
3. **Counted toward the 4-seat cap** (`room.size`) and was sent to `watch`ers as present.

## Alternatives considered

1. **Send `peer-left` again at grace expiry.** Insufficient: the joiner never had a reason to hold the ghost in the first place, and would still waste up to 180s offering into it and refuse the name meanwhile.
2. **Buffer signals for the ghost until it resumes.** Forbidden by 001 — old-epoch signals poison the rebuilt session.
3. **Client-side watchdog cap only.** Necessary (added to 003) but insufficient alone: it bounds the retries, but the joiner still holds a dead peer, and the name/cap problems remain server-side.
4. **Drop the grace period.** Rejected: resume-by-clientId is how a socket drop recovers without a fresh peerId (001).

## Decision

- **`isLiveSeat(ws) = !ws.leftAnnounced`.** Only live seats appear in `joined.peers`, count for the username-taken check, count toward the cap (live seats, excluding the joiner's own), and are reported to `watch`. The ghost remains visible to exactly one thing: a join carrying its own clientId (resume).
- **Ghost eviction.** Before seating a new joiner, while `room.size >= 4` evict the oldest ghost (by `ws.leftAt`) via `leaveRoom` — no new message is sent, `peer-left` already went out. The map never exceeds 4. Eviction runs **after** the username check, so a rejected join doesn't discard a resumable seat. An evicted client that returns simply joins fresh (`peer-joined` → rebuild).
- **Negative ack.** A `signal` whose target is missing, not live, or not OPEN gets `{type:'signal-undeliverable', to}` back. Web and Android drop that peer exactly like `peer-left` (idempotent; the server nacks every undelivered signal, ICE candidates included, so they arrive in bursts). Nothing is buffered or replayed — 001's no-replay rule is intact. Because the server processes messages in order, a nack reaches a client only when that peer's `peer-left` has already been sent (or, if the target's socket is mid-close, is about to be).

## Trade-offs and failure modes

- An evicted ghost loses resumability and gets a fresh peerId on return. Equivalent under 001 — resume and fresh join both end in a full rebuild; only the peerId differs.
- Eviction only happens when ghosts fill the map (4 seats incl. ghosts) — in 1:1 use only after several fresh joins (different clientIds) within 180s.
- A client running pre-009 code ignores `signal-undeliverable` and falls back to the (now capped) watchdog.
- The nack is per-signal, so a dead target can produce a burst of nacks; both clients act once (they check they still hold the peer).

## Revisit if

- Signals gain session/epoch ids (001's revisit) — a ghost could then be allowed to receive buffered signals safely.
- Rooms are used beyond 1:1 and ghost eviction order starts to matter.
- A positive delivery ack is added — then the nack folds into it (003).
