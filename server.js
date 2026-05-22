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
const WS_REJOIN_GRACE_MS = parseInt(process.env.WS_REJOIN_GRACE_MS || '180000', 10);
const WS_PING_INTERVAL_MS = parseInt(process.env.WS_PING_INTERVAL_MS || '25000', 10);

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
const watchers = new Map(); // roomId -> Set<ws>

function send(ws, msg) {
    if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(msg));
}

function leaveRoom(ws) {
    const { roomId, peerId } = ws;
    if (ws.leaveTimer) {
        clearTimeout(ws.leaveTimer);
        ws.leaveTimer = null;
    }
    if (roomId) {
        const room = rooms.get(roomId);
        if (room) {
            if (room.get(peerId) === ws) {
                room.delete(peerId);
                for (const peer of room.values()) send(peer, { type: 'peer-left', peerId, username: ws.username || null });
                const roomWatchers = watchers.get(roomId);
                if (roomWatchers) {
                    for (const watcher of roomWatchers) send(watcher, { type: 'peer-left', peerId, username: ws.username || null });
                }
            }
            if (room.size === 0) rooms.delete(roomId);
        }
        ws.roomId = null;
    }
    unwatchRoom(ws);
}

function scheduleLeaveRoom(ws) {
    if (!ws.roomId || !ws.clientId) {
        leaveRoom(ws);
        return;
    }
    if (ws.leaveTimer) clearTimeout(ws.leaveTimer);
    ws.leaveTimer = setTimeout(() => leaveRoom(ws), WS_REJOIN_GRACE_MS);
}

function unwatchRoom(ws) {
    const { watchingRoom } = ws;
    if (!watchingRoom) return;
    const roomWatchers = watchers.get(watchingRoom);
    if (roomWatchers) {
        roomWatchers.delete(ws);
        if (roomWatchers.size === 0) watchers.delete(watchingRoom);
    }
    ws.watchingRoom = null;
}

wss.on('connection', (ws) => {
    ws.peerId = crypto.randomUUID();
    ws.roomId = null;
    ws.watchingRoom = null;
    ws.clientId = null;
    ws.leaveTimer = null;
    ws.isAlive = true;
    send(ws, { type: 'welcome', peerId: ws.peerId });

    ws.on('pong', () => { ws.isAlive = true; });

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
            const username = typeof msg.username === 'string' ? msg.username.trim().slice(0, 32) : '';
            const clientId = typeof msg.clientId === 'string' ? msg.clientId.trim().slice(0, 64) : '';
            let room = rooms.get(roomId);
            if (!room) { room = new Map(); rooms.set(roomId, room); }

            let resumed = false;
            if (clientId) {
                for (const peerWs of room.values()) {
                    if (peerWs.clientId === clientId) {
                        if (peerWs.leaveTimer) {
                            clearTimeout(peerWs.leaveTimer);
                            peerWs.leaveTimer = null;
                        }
                        ws.peerId = peerWs.peerId;
                        peerWs.roomId = null;
                        peerWs.watchingRoom = null;
                        try { peerWs.close(1000, 'replaced by reconnect'); } catch {}
                        room.set(ws.peerId, ws);
                        resumed = true;
                        break;
                    }
                }
            }

            if (!resumed && room.size >= 4) {
                send(ws, { type: 'room-full' });
                return;
            }

            // Capture existing peers with their usernames before adding ourselves
            const existingPeers = [];
            for (const [id, peerWs] of room.entries()) {
                if (id === ws.peerId) continue;
                existingPeers.push({ peerId: id, username: peerWs.username || null });
            }

            // Reject if username already taken in this room
            if (username && existingPeers.some(p => p.username === username)) {
                send(ws, { type: 'username-taken', username });
                return;
            }
            if (!resumed) room.set(ws.peerId, ws);
            ws.roomId = roomId;
            ws.clientId = clientId || null;
            // Store username from join message as well (backup for set-username)
            if (username) ws.username = username;

            if (resumed) {
                for (const { peerId } of existingPeers) {
                    const peerWs = room.get(peerId);
                    if (peerWs) send(peerWs, { type: 'peer-resumed', peerId: ws.peerId, username: ws.username || null });
                }
            }
            send(ws, { type: 'joined', roomId, peerId: ws.peerId, peers: existingPeers, resumed });
            if (!resumed) {
                for (const { peerId } of existingPeers) {
                    const peerWs = room.get(peerId);
                    if (peerWs) send(peerWs, { type: 'peer-joined', peerId: ws.peerId, username: ws.username || null });
                }
            }
            const roomWatchers = watchers.get(roomId);
            if (roomWatchers) {
                for (const watcher of roomWatchers) send(watcher, {
                    type: resumed ? 'peer-resumed' : 'peer-joined',
                    peerId: ws.peerId,
                    username: ws.username || null
                });
            }
            return;
        }

        if (msg.type === 'watch' && typeof msg.roomId === 'string') {
            leaveRoom(ws);
            unwatchRoom(ws);

            const roomId = msg.roomId;
            let roomWatchers = watchers.get(roomId);
            if (!roomWatchers) { roomWatchers = new Set(); watchers.set(roomId, roomWatchers); }
            roomWatchers.add(ws);
            ws.watchingRoom = roomId;

            send(ws, { type: 'watching', roomId });
            const room = rooms.get(roomId);
            if (room) {
                for (const [peerId, peerWs] of room.entries()) {
                    send(ws, { type: 'peer-joined', peerId, username: peerWs.username || null });
                }
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

        if (msg.type === 'ping') {
            send(ws, { type: 'pong', ts: Date.now() });
            return;
        }

        if (msg.type === 'leave') {
            leaveRoom(ws);
            return;
        }
    });

    ws.on('close', () => scheduleLeaveRoom(ws));
    ws.on('error', () => {});
});

const heartbeatTimer = setInterval(() => {
    for (const ws of wss.clients) {
        if (ws.isAlive === false) {
            try { ws.terminate(); } catch {}
            continue;
        }
        ws.isAlive = false;
        try { ws.ping(); } catch {}
    }
}, WS_PING_INTERVAL_MS);
heartbeatTimer.unref?.();

server.listen(PORT, () => {
    console.log(`Server listening on http://localhost:${PORT}`);
    if (TURN_URL) console.log('TURN configured');
});
