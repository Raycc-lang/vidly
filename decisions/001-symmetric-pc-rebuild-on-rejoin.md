# 001 — Symmetric PeerConnection Rebuild on Signaling Rejoin

**Status:** Accepted (2026-07-10, commit e4a492d)
**Code:** `NativeWebRtcClient.onJoined()`, `NativeWebRtcClient.onPeerJoined()`; server `scheduleLeaveRoom`/`announcePeerLeft` in `server.js`

## Problem

When a client's signaling WebSocket drops and reconnects, what happens to the WebRTC PeerConnections that were built during the previous signaling session?

Before this decision, the answer was "it depends which side you were": the reconnecting side sometimes kept its old PeerConnection while the remote side — which received `peer-left` the moment the socket dropped — tore its down and built a fresh one on `peer-resumed`. A fresh PeerConnection and a stale one can never negotiate: the stale side's DTLS certificates, ICE ufrag/pwd, and SDP session state belong to a session the other side no longer has. `setRemoteDescription` fails or silently mismatches, and the call is dead until both users leave and rejoin manually.

A related trap: signals (offers/answers/candidates) generated during the disconnection. If the client queued them for delivery after reconnect, or the server buffered them during its grace period, they would arrive *into the rebuilt session* — SDP and candidates from a dead PeerConnection epoch, poisoning the fresh negotiation.

## Alternatives considered

1. **Keep PCs alive across signaling blips, buffer signals until reconnected.** Attractive because the media path (ICE) often survives a signaling outage untouched. Rejected: it requires both sides to agree on whether the session survived, which is exactly the information a signaling outage destroys. Any asymmetry (one side's socket died "harder", server restarted, grace expired on one side only) recreates the deadlock. Epoch-tagging every signal could fix this but adds protocol complexity for a two-person app.
2. **Server-side signal buffering during the 3-minute grace period.** Rejected for the same reason: buffered old-epoch signals delivered into a rebuilt session are worse than dropped ones.
3. **Rebuild everything on every rejoin (chosen).**

## Decision

A (re)`joined` event and a `peer-resumed`/re-announced `peer-joined` event both mean: **close every PeerConnection you hold for that peer (or all peers, on your own rejoin) and negotiate from scratch.** Both sides do this, so the pair is always symmetric. Signals are never queued or buffered anywhere — `NativeSignalingClient.send()` on a dead socket is a deliberate no-op, and the server relays only to currently-connected sockets. A lost signal is recovered by rebuild or by the stuck-offer watchdog (003), never by replay.

This also reaps zombie peers after a signaling-server restart: the fresh `joined` peer list is authoritative.

## Trade-offs and failure modes

- Every signaling reconnect causes a brief media gap (~1–3s of renegotiation) even when the P2P media path was healthy the whole time. For the target use case (weak network, where signaling and media usually die together) this is acceptable.
- DataChannels (chat, file transfer) are torn down with the PC. In-flight file transfers abort; `activeIncomingByPeer` is cleaned on peer-left so a new transfer can start.
- More offer traffic on flappy networks; bounded by the reconnect backoff (1s→10s).

## Revisit if

- Signals carry a session/epoch id end-to-end, so stale ones can be detected and discarded instead of avoided. Only then is it safe to let a PeerConnection outlive a signaling session.
- The app moves beyond 1:1 usage and rebuild storms become expensive.
