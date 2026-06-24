# Vidly — P2P Video Call

A polished 1‑to‑4 person WebRTC mesh video call app. Single Node process, no
database, no accounts. Camera/audio/chat go peer‑to‑peer; the server only
relays signaling messages.

## Stack

- **Server**: Node.js 18+, [`ws`](https://www.npmjs.com/package/ws) only
- **Frontend**: vanilla JS + CSS (single `public/index.html`)
- **Transport**: WebRTC mesh, perfect-negotiation pattern, DataChannel chat

## Install & run

```bash
npm install
PORT=3000 node server.js
```

Then open `http://localhost:3000`.

## Environment variables

| Var        | Default | Notes |
|------------|---------|-------|
| `PORT`     | `3000`  | HTTP + WebSocket port |
| `TURN_URL` | (none)  | Single‑URL TURN configuration. Format: `turn:HOST:PORT?username=USER&credential=PASS`. The client also auto-derives a `turns:` (TLS) variant. |

Example:

```bash
TURN_URL='turn:turn.example.com:3478?username=demo&credential=secret' \
PORT=3000 node server.js
```

## Behind nginx

The app listens on plain HTTP and serves both the static SPA and the WebSocket
on the same port. Terminate TLS at nginx and proxy everything (including the
WebSocket upgrade) to it:

```nginx
server {
    listen 443 ssl http2;
    server_name vidly.example.com;

    # ... ssl_certificate / ssl_certificate_key ...

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # WebSocket upgrade
        proxy_set_header Upgrade           $http_upgrade;
        proxy_set_header Connection        "upgrade";
        proxy_read_timeout 3600s;
    }
}
```

## Routes

- `GET /` — landing page (create / join a room)
- `GET /room/<id>` — call page, auto-joins the room
- `GET /turn-credentials` — time-limited TURN ICE config: `{ urls, username, credential, ttl, expires }`
- `GET /<file>` — static asset from `public/`
- `WS /*` — signaling (any path; nginx upgrade above covers it)

## Signaling protocol

Server → client:

- `{ type: "welcome", peerId }`
- `{ type: "joined", roomId, peers: [peerId, ...] }`
- `{ type: "peer-joined", peerId }`
- `{ type: "peer-left", peerId }`
- `{ type: "signal", from, payload }` — payload contains `description` or `candidate`
- `{ type: "room-full" }`

Client → server:

- `{ type: "join", roomId }`
- `{ type: "signal", to, payload }`
- `{ type: "leave" }`

The **joining peer** creates offers to existing peers (impolite side of perfect
negotiation); existing peers wait for the offer (polite side). This avoids
glare when several people join nearly simultaneously.

## Features

- 1‑to‑4 participant mesh video + audio
- Built-in text chat over WebRTC DataChannel (no server relay)
- Screen share with one-click revert
- Mic / camera / screen / chat / leave toolbar
- Invite link copy button
- Toast notifications for join/leave, room-full, errors
- Auto WebSocket reconnect with 1→2→4→8s exponential backoff (cap 10s)
- ICE failure recovery via `restartIce()`
- STUN servers (Google, Cloudflare, Twilio) plus optional TURN/TURNS
- Mobile-responsive layout, full-screen chat overlay on small screens
- Dark themed UI with gradients, soft shadows, fade animations

## Browser support

Chrome, Firefox, Safari (desktop and mobile). Requires HTTPS in production for
`getUserMedia`/`getDisplayMedia` (handled by your nginx TLS).
