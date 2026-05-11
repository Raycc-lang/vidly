/**
 * P2P Video Call - Signaling Server
 *
 * Responsibilities:
 *   - Serve static files from ./public
 *   - Provide a WebSocket signaling channel on the same HTTP server
 *   - Track room membership: roomId -> Set<WebSocket>
 *   - Relay 'signal' messages (offer/answer/ICE) to every other peer in the
 *     same room (the server itself never inspects WebRTC payloads)
 *   - Notify peers of join/leave events so they can build/tear down PCs
 *
 * Optional env:
 *   PORT      - listen port (default 3000)
 *   TURN_URL  - exposed to the client at GET /config so the browser can use
 *               a TURN server for NAT traversal when STUN alone fails.
 *               Format: "turn:host:port?username=USER&credential=PASS"
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 3000;
const PUBLIC_DIR = path.join(__dirname, 'public');

// Static MIME map - small enough that a hard-coded table is simpler than
// pulling in a dependency.
const MIME = {
    '.html': 'text/html; charset=utf-8',
    '.js':   'application/javascript; charset=utf-8',
    '.css':  'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.svg':  'image/svg+xml',
    '.png':  'image/png',
    '.jpg':  'image/jpeg',
    '.ico':  'image/x-icon'
};

// ---- HTTP server ----------------------------------------------------------
const server = http.createServer((req, res) => {
    // Tiny config endpoint: lets the browser discover the optional TURN URL
    // without requiring a build step or env injection in HTML.
    if (req.url === '/config') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ turnUrl: process.env.TURN_URL || null }));
        return;
    }

    // /room/<id> is a SPA route - always serve index.html and let the
    // client-side router handle it.
    let urlPath = req.url.split('?')[0];
    if (urlPath.startsWith('/room/')) urlPath = '/index.html';
    if (urlPath === '/') urlPath = '/index.html';

    // Resolve and confirm the resulting path is inside PUBLIC_DIR (defence
    // against `..` traversal).
    const filePath = path.join(PUBLIC_DIR, urlPath);
    if (!filePath.startsWith(PUBLIC_DIR)) {
        res.writeHead(403); res.end('Forbidden'); return;
    }

    fs.readFile(filePath, (err, data) => {
        if (err) {
            res.writeHead(404); res.end('Not Found'); return;
        }
        const ext = path.extname(filePath).toLowerCase();
        res.writeHead(200, { 'Content-Type': MIME[ext] || 'application/octet-stream' });
        res.end(data);
    });
});

// ---- WebSocket signaling --------------------------------------------------
const wss = new WebSocketServer({ server });

/** roomId -> Map<peerId, WebSocket> */
const rooms = new Map();

function send(ws, obj) {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
}

wss.on('connection', (ws) => {
    // Each socket gets a server-assigned peer id used in all signaling.
    ws.peerId = crypto.randomUUID();
    ws.roomId = null;

    // Tell the client who it is. The client uses this id when addressing
    // signals to specific peers.
    send(ws, { type: 'welcome', peerId: ws.peerId });

    ws.on('message', (raw) => {
        let msg;
        try { msg = JSON.parse(raw.toString()); } catch { return; }

        switch (msg.type) {

            case 'join': {
                // Reject invalid / oversized room ids early.
                const roomId = String(msg.roomId || '').slice(0, 64);
                if (!roomId) return;

                // Cap room size at 4 participants (mesh topology gets too
                // chatty beyond that).
                const room = rooms.get(roomId) || new Map();
                if (room.size >= 4) {
                    send(ws, { type: 'room-full' });
                    return;
                }

                ws.roomId = roomId;
                // Send the joining peer the list of existing peers so it
                // knows who to create offers to. The convention is: the
                // *new* peer initiates offers to existing peers, which
                // avoids glare / simultaneous-offer collisions.
                const existing = [...room.keys()];
                send(ws, { type: 'joined', roomId, peers: existing });

                // Notify existing peers that someone arrived. They will
                // *wait* for an offer rather than make one.
                for (const peer of room.values()) {
                    send(peer, { type: 'peer-joined', peerId: ws.peerId });
                }

                room.set(ws.peerId, ws);
                rooms.set(roomId, room);
                break;
            }

            case 'signal': {
                // Forward an SDP / ICE payload to a single addressed peer.
                // The server is intentionally dumb here - any structure the
                // client cares about lives inside `payload`.
                if (!ws.roomId || !msg.to) return;
                const room = rooms.get(ws.roomId);
                if (!room) return;
                const target = room.get(msg.to);
                if (!target) return;
                send(target, {
                    type: 'signal',
                    from: ws.peerId,
                    payload: msg.payload
                });
                break;
            }

            case 'leave': {
                cleanup(ws);
                break;
            }
        }
    });

    ws.on('close', () => cleanup(ws));
    ws.on('error', () => cleanup(ws));
});

function cleanup(ws) {
    const { roomId, peerId } = ws;
    if (!roomId) return;
    const room = rooms.get(roomId);
    if (!room) return;
    room.delete(peerId);
    if (room.size === 0) {
        rooms.delete(roomId);
    } else {
        // Tell remaining peers so they can tear down their RTCPeerConnection
        // and remove the corresponding video tile.
        for (const peer of room.values()) {
            send(peer, { type: 'peer-left', peerId });
        }
    }
    ws.roomId = null;
}

server.listen(PORT, () => {
    console.log(`Video call server listening on http://localhost:${PORT}`);
    if (process.env.TURN_URL) console.log('TURN configured:', process.env.TURN_URL);
});
