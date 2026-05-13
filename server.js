// Signaling server for the P2P video call app.
// Serves static files from public/ and provides WebSocket signaling for WebRTC.

const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer } = require('ws');

const PORT = parseInt(process.env.PORT || '3000', 10);
const TURN_URL = process.env.TURN_URL || '';
const PUBLIC_DIR = path.join(__dirname, 'public');

const MIME = {
    '.html': 'text/html; charset=utf-8',
    '.js':   'application/javascript; charset=utf-8',
    '.css':  'text/css; charset=utf-8',
    '.json': 'application/json; charset=utf-8',
    '.png':  'image/png',
    '.jpg':  'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif':  'image/gif',
    '.svg':  'image/svg+xml',
    '.ico':  'image/x-icon',
    '.webp': 'image/webp',
    '.woff': 'font/woff',
    '.woff2':'font/woff2',
    '.ttf':  'font/ttf',
    '.map':  'application/json; charset=utf-8',
};

function sendFile(res, filePath, statusCode = 200) {
    fs.readFile(filePath, (err, data) => {
        if (err) {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            res.end('Not found');
            return;
        }
        const ext = path.extname(filePath).toLowerCase();
        res.writeHead(statusCode, {
            'Content-Type': MIME[ext] || 'application/octet-stream',
            'Cache-Control': ext === '.html' ? 'no-cache' : 'public, max-age=300',
        });
        res.end(data);
    });
}

const server = http.createServer((req, res) => {
    // Strip query string for routing
    const urlPath = (req.url || '/').split('?')[0];

    // /config endpoint exposes TURN_URL to the client
    if (urlPath === '/config' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
        res.end(JSON.stringify({ turnUrl: TURN_URL }));
        return;
    }

    // SPA routes — serve index.html for / and /room/<id>
    if (urlPath === '/' || urlPath.startsWith('/room/')) {
        sendFile(res, path.join(PUBLIC_DIR, 'index.html'));
        return;
    }

    // Static files from public/, with traversal protection
    const decoded = decodeURIComponent(urlPath);
    if (decoded.includes('..')) {
        res.writeHead(400, { 'Content-Type': 'text/plain' });
        res.end('Bad request');
        return;
    }
    const filePath = path.join(PUBLIC_DIR, decoded);
    if (!filePath.startsWith(PUBLIC_DIR)) {
        res.writeHead(400, { 'Content-Type': 'text/plain' });
        res.end('Bad request');
        return;
    }
    sendFile(res, filePath);
});

// ─── WebSocket signaling ────────────────────────────────────────────────────
const wss = new WebSocketServer({ server });
const rooms = new Map(); // roomId -> Map<peerId, ws>

function send(ws, msg) {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
}

function leaveRoom(ws) {
    const { roomId, peerId } = ws;
    if (!roomId) return;
    const room = rooms.get(roomId);
    if (!room) return;
    room.delete(peerId);
    for (const peer of room.values()) send(peer, { type: 'peer-left', peerId, username: ws.username || null });
    if (room.size === 0) rooms.delete(roomId);
    ws.roomId = null;
}

wss.on('connection', (ws) => {
    ws.peerId = crypto.randomUUID();
    ws.roomId = null;
    send(ws, { type: 'welcome', peerId: ws.peerId });

    ws.on('message', (raw) => {
        let msg;
        try { msg = JSON.parse(raw.toString()); } catch { return; }

        if (msg.type === 'set-username' && typeof msg.username === 'string') {
            ws.username = msg.username.trim().slice(0, 32);
            return;
        }

        if (msg.type === 'join' && typeof msg.roomId === 'string') {
            // Leave old room if any
            leaveRoom(ws);

            const roomId = msg.roomId;
            let room = rooms.get(roomId);
            if (!room) { room = new Map(); rooms.set(roomId, room); }

            if (room.size >= 4) {
                send(ws, { type: 'room-full' });
                return;
            }

            // Capture existing peers with their usernames before adding ourselves
            const existingPeers = [];
            for (const [id, peerWs] of room.entries()) {
                existingPeers.push({ peerId: id, username: peerWs.username || null });
            }

            // Reject if username already taken in this room
            if (msg.username && existingPeers.some(p => p.username === msg.username.trim().slice(0, 32))) {
                send(ws, { type: 'username-taken', username: msg.username.trim().slice(0, 32) });
                return;
            }
            room.set(ws.peerId, ws);
            ws.roomId = roomId;
            // Store username from join message as well (backup for set-username)
            if (msg.username) ws.username = msg.username.trim().slice(0, 32);

            send(ws, { type: 'joined', roomId, peers: existingPeers });
            for (const { peerId } of existingPeers) {
                const peerWs = room.get(peerId);
                if (peerWs) send(peerWs, { type: 'peer-joined', peerId: ws.peerId, username: ws.username || null });
            }
            return;
        }

        if (msg.type === 'signal' && typeof msg.to === 'string') {
            const room = rooms.get(ws.roomId);
            if (!room) return;
            const target = room.get(msg.to);
            if (!target) return;
            send(target, { type: 'signal', from: ws.peerId, payload: msg.payload });
            return;
        }

        if (msg.type === 'leave') {
            leaveRoom(ws);
            return;
        }
    });

    ws.on('close', () => leaveRoom(ws));
    ws.on('error', () => {});
});

server.listen(PORT, () => {
    console.log(`Server listening on http://localhost:${PORT}`);
    if (TURN_URL) console.log('TURN configured');
});
