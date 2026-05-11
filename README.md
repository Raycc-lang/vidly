# Mesh — P2P Video Call

A small, dependency-light **peer-to-peer video, audio and chat** web app.

- **Server**: Node.js + [`ws`](https://github.com/websockets/ws). Serves the
  static frontend and acts as a dumb WebRTC signaling relay.
- **Frontend**: single-file `public/index.html` (HTML + CSS + JS, no
  frameworks, no build step).
- **Topology**: full WebRTC **mesh** — each peer holds an `RTCPeerConnection`
  to every other peer in the room. Capped at **4 participants** per room.
- **Extras**: text chat over `RTCDataChannel`, screen sharing, mute / cam
  toggles, auto-reconnect signaling, ICE restart on failure, dark UI, mobile
  responsive.

---

## Quick start

```bash
# 1. install
npm install

# 2. run
npm start            # default port 3000
PORT=8080 npm start  # custom port
```

Then open <http://localhost:3000> in two or more tabs/devices.

> **Camera & mic permissions** require a *secure context*. `localhost` works
> in all major browsers. For LAN / production use you must serve over
> **HTTPS** (run behind a reverse proxy like Caddy / nginx / Cloudflare).

---

## Usage

1. On the landing page, click **Create new room** (or paste a code into
   *Join existing*).
2. The URL becomes `/room/<id>`. Click **📋 Copy link** in the top bar and
   send it to up to **3** other participants.
3. Use the bottom toolbar to mute, disable camera, share screen, or leave.
4. Use the **💬** button (mobile) to open the chat panel.

---

## Configuration

| Variable   | Default | Description                                                                 |
|------------|---------|-----------------------------------------------------------------------------|
| `PORT`     | `3000`  | HTTP / WebSocket port the server binds to.                                  |
| `TURN_URL` | *unset* | Optional TURN server URL. See format below. Exposed to client via `/config`. |

**`TURN_URL` format**

```
turn:HOST:PORT?username=USER&credential=PASS
```

Example:

```bash
TURN_URL='turn:turn.example.com:3478?username=alice&credential=s3cret' npm start
```

If unset, the client falls back to Google + Twilio public STUN servers, which
are sufficient for most NATs but may fail on symmetric NATs / strict
firewalls. In that case provide a TURN server (e.g. a self-hosted
[coturn](https://github.com/coturn/coturn)).

---

## How it works

### Signaling protocol

The server is intentionally tiny and never inspects WebRTC payloads.

```diagram
╭────────╮  join         ╭─────────╮  peer-joined  ╭────────╮
│ Client │──────────────▶│ Server  │──────────────▶│ Others │
╰────────╯               ╰────┬────╯               ╰────────╯
                              │ relay
                  signal ◀────┴────▶ signal   (offer / answer / ICE)
```

Messages (JSON over WebSocket):

| Type           | Direction       | Payload                                |
|----------------|-----------------|----------------------------------------|
| `welcome`      | server → client | `{ peerId }` — your assigned id        |
| `join`         | client → server | `{ roomId }`                           |
| `joined`       | server → client | `{ roomId, peers[] }`                  |
| `peer-joined`  | server → client | `{ peerId }` — a new peer arrived      |
| `peer-left`    | server → client | `{ peerId }` — a peer disconnected     |
| `signal`       | both            | `{ to, from, payload }` — opaque blob  |
| `room-full`    | server → client | rejected, room already has 4 peers     |

### WebRTC negotiation

- The **joining** peer creates the `RTCPeerConnection` for each existing peer
  and opens the data channel — it is the *offerer* (impolite side).
- Existing peers wait for the offer (polite side). Glare (simultaneous
  offers, e.g. after WS reconnect) is handled by the
  [perfect-negotiation](https://w3c.github.io/webrtc-pc/#perfect-negotiation-example)
  pattern.
- On `iceConnectionState === 'failed'` the client calls `pc.restartIce()` to
  trigger a fresh ICE gathering pass.

### Reconnect

The WebSocket reconnects with **exponential backoff (1 s → 10 s)**. On
reconnect the client re-`join`s the room, which causes existing peers to
re-offer.

---

## Files

| Path                   | Purpose                                         |
|------------------------|-------------------------------------------------|
| `server.js`            | HTTP static + WebSocket signaling.              |
| `public/index.html`    | Whole frontend (HTML + CSS + JS in one file).   |
| `package.json`         | Single dependency: `ws`.                        |

---

## Limitations

- Mesh topology means every participant uploads their stream **N − 1** times.
  Practical only for small calls (≤ 4 here). For larger calls use an SFU
  (mediasoup, Janus, LiveKit, etc.).
- No persistence — rooms exist only while at least one peer is connected.
- No authentication. Anyone with the room URL can join. Add auth at the
  reverse-proxy layer if needed.

---

## License

MIT
