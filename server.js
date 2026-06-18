// Signaling server for the P2P video call app.
// Serves static files from public/ and provides WebSocket signaling for WebRTC.

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');
const { WebSocketServer } = require('ws');

const PORT = parseInt(process.env.PORT || '3000', 10);
const TURN_SERVER_URL = process.env.TURN_SERVER_URL || 'turn:xray.raycc.org:3478';
const TURN_SECRET = process.env.TURN_SECRET || 'TURN_SECRET_PLACEHOLDER';
const TURN_CREDENTIAL_TTL_SECONDS = parseInt(process.env.TURN_CREDENTIAL_TTL_SECONDS || '3600', 10);
const ANDROID_VERSION_CODE = parseInt(process.env.ANDROID_VERSION_CODE || '1', 10);
const ANDROID_VERSION_NAME = process.env.ANDROID_VERSION_NAME || '1.0';
const ANDROID_APK_URL = process.env.ANDROID_APK_URL || 'https://voice.raycc.org/vidly-native.apk';
// GIPHY proxy — api.giphy.com is blocked on some client networks (e.g. CN),
// while the signaling server (this) can reach it. Clients fetch GIF metadata
// via /giphy instead of hitting GIPHY directly. Key lives server-side only.
const GIPHY_API_KEY = process.env.GIPHY_API_KEY || 'z3JlLEdcXBGP0Bitbf3ut2XjlnKaV0xn';
const GIPHY_LIMIT = parseInt(process.env.GIPHY_LIMIT || '20', 10);
const GIPHY_TIMEOUT_MS = parseInt(process.env.GIPHY_TIMEOUT_MS || '8000', 10);
const PUBLIC_DIR = path.join(__dirname, 'public');
const CRASH_LOGS_DIR = path.join(__dirname, 'crash-logs');
const CRASH_MAX_BYTES = 512 * 1024; // cap per report — keep the 1GB VPS safe
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

// MIME types worth compressing (text-based formats)
const COMPRESSIBLE = new Set(['.html', '.js', '.css', '.json', '.svg', '.map']);

function sendFile(res, filePath, statusCode = 200) {
    fs.stat(filePath, (err, stat) => {
        if (err) {
            res.writeHead(404, { 'Content-Type': 'text/plain' });
            res.end('Not found');
            return;
        }
        const ext = path.extname(filePath).toLowerCase();
        const contentType = MIME[ext] || 'application/octet-stream';
        const cacheControl = ext === '.html' ? 'no-cache' : 'public, max-age=300';
        const acceptEncoding = res.req?.headers?.['accept-encoding'] || '';
        const canGzip = COMPRESSIBLE.has(ext) && acceptEncoding.includes('gzip') && stat.size > 1024;

        const headers = {
            'Content-Type': contentType,
            'Cache-Control': cacheControl,
        };

        if (canGzip) {
            headers['Content-Encoding'] = 'gzip';
            headers['Vary'] = 'Accept-Encoding';
            res.writeHead(statusCode, headers);
            const readStream = fs.createReadStream(filePath);
            readStream.on('error', () => { try { res.end(); } catch {} });
            readStream.pipe(zlib.createGzip({ level: 6 })).pipe(res);
        } else {
            headers['Content-Length'] = stat.size;
            res.writeHead(statusCode, headers);
            const readStream = fs.createReadStream(filePath);
            readStream.on('error', () => { try { res.end(); } catch {} });
            readStream.pipe(res);
        }
    });
}

function createTurnCredentials() {
    const ttl = Number.isFinite(TURN_CREDENTIAL_TTL_SECONDS) && TURN_CREDENTIAL_TTL_SECONDS > 0
        ? TURN_CREDENTIAL_TTL_SECONDS
        : 3600;
    const expires = Math.floor(Date.now() / 1000) + ttl;
    const username = `${expires}:vidly`;
    const credential = crypto.createHmac('sha1', TURN_SECRET).update(username).digest('base64');
    return {
        urls: [TURN_SERVER_URL, TURN_SERVER_URL + "?transport=tcp"],
        username,
        credential,
        ttl,
        expires,
    };
}

function sendTurnCredentials(res) {
    const credentials = createTurnCredentials();
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify(credentials));
}

// Proxy GIPHY trending/search. Clients behind networks that block api.giphy.com
// hit /giphy here instead. `q` empty -> trending, otherwise -> search.
function proxyGiphy(req, res) {
    const params = new URL(req.url, 'http://localhost').searchParams;
    const query = (params.get('q') || '').trim();
    const isSearch = query.length > 0;
    const giphyUrl = 'https://api.giphy.com/v1/gifs/' + (isSearch ? 'search' : 'trending')
        + '?api_key=' + encodeURIComponent(GIPHY_API_KEY)
        + '&limit=' + GIPHY_LIMIT
        + (isSearch ? '&q=' + encodeURIComponent(query) : '');

    const upstream = https.get(giphyUrl, { timeout: GIPHY_TIMEOUT_MS }, (upRes) => {
        // Pass GIPHY's status through; stream the body straight to the client.
        const chunks = [];
        upRes.on('data', (c) => chunks.push(c));
        upRes.on('end', () => {
            const body = Buffer.concat(chunks);
            res.writeHead(upRes.statusCode || 502, {
                'Content-Type': upRes.headers['content-type'] || 'application/json; charset=utf-8',
                'Cache-Control': 'no-store',
            });
            res.end(body);
        });
    });
    upstream.on('timeout', () => upstream.destroy(new Error('GIPHY upstream timeout')));
    upstream.on('error', (err) => {
        console.error('GIPHY proxy error:', err.message);
        if (!res.headersSent) {
            res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
            res.end(JSON.stringify({ error: 'giphy_unreachable' }));
        }
    });
}

function saveCrashReport(req, res) {
    let size = 0;
    const chunks = [];
    let aborted = false;
    req.on('data', (chunk) => {
        if (aborted) return;
        size += chunk.length;
        if (size > CRASH_MAX_BYTES) {
            aborted = true;
            res.writeHead(413, { 'Content-Type': 'text/plain' });
            res.end('Crash report too large');
            req.destroy();
            return;
        }
        chunks.push(chunk);
    });
    req.on('end', () => {
        if (aborted) return;
        const body = Buffer.concat(chunks).toString('utf8');
        let report;
        try { report = JSON.parse(body); } catch {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            res.end('Invalid JSON');
            return;
        }
        const ts = (report.timestamp || new Date().toISOString()).replace(/[:.]/g, '-');
        const model = String(report.deviceModel || 'unknown').replace(/[^A-Za-z0-9._-]/g, '_').slice(0, 64);
        const rand = crypto.randomBytes(3).toString('hex');
        const fileName = `${ts}_${model}_${rand}.json`;
        fs.mkdir(CRASH_LOGS_DIR, { recursive: true }, (mkErr) => {
            if (mkErr) {
                res.writeHead(500, { 'Content-Type': 'text/plain' });
                res.end('Could not store crash report');
                return;
            }
            fs.writeFile(path.join(CRASH_LOGS_DIR, fileName), JSON.stringify(report, null, 2), (writeErr) => {
                if (writeErr) {
                    res.writeHead(500, { 'Content-Type': 'text/plain' });
                    res.end('Could not store crash report');
                    return;
                }
                console.log(`Crash report saved: ${fileName}`);
                res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
                res.end(JSON.stringify({ ok: true }));
            });
        });
    });
    req.on('error', () => {
        if (!res.headersSent) {
            res.writeHead(400, { 'Content-Type': 'text/plain' });
            res.end('Bad request');
        }
    });
}

const server = http.createServer((req, res) => {
    // Strip query string for routing
    const urlPath = (req.url || '/').split('?')[0];

    if ((urlPath === '/turn-credentials' || urlPath === '/config') && req.method === 'GET') {
        sendTurnCredentials(res);
        return;
    }

    if (urlPath === '/crash' && req.method === 'POST') {
        saveCrashReport(req, res);
        return;
    }

    if (urlPath === '/version' && req.method === 'GET') {
        res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-cache' });
        res.end(JSON.stringify({
            versionCode: ANDROID_VERSION_CODE,
            versionName: ANDROID_VERSION_NAME,
            apkUrl: ANDROID_APK_URL,
        }));
        return;
    }

    if (urlPath === '/giphy' && req.method === 'GET') {
        proxyGiphy(req, res);
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
                if (!ws.leftAnnounced) {
                    for (const peer of room.values()) send(peer, { type: 'peer-left', peerId, username: ws.username || null });
                    const roomWatchers = watchers.get(roomId);
                    if (roomWatchers) {
                        for (const watcher of roomWatchers) send(watcher, { type: 'peer-left', peerId, username: ws.username || null });
                    }
                }
            }
            if (room.size === 0) rooms.delete(roomId);
        }
        ws.roomId = null;
    }
    unwatchRoom(ws);
}

// Announce the peer as gone immediately, but keep it in the room map during
// the grace period so the join handler can still match by clientId.
function announcePeerLeft(ws) {
    const { roomId, peerId } = ws;
    if (!roomId || ws.leftAnnounced) return;
    const room = rooms.get(roomId);
    if (room) {
        for (const peer of room.values()) {
            if (peer !== ws) send(peer, { type: 'peer-left', peerId, username: ws.username || null });
        }
        const roomWatchers = watchers.get(roomId);
        if (roomWatchers) {
            for (const watcher of roomWatchers) send(watcher, { type: 'peer-left', peerId, username: ws.username || null });
        }
    }
    ws.leftAnnounced = true;
    unwatchRoom(ws);
}

function scheduleLeaveRoom(ws) {
    if (!ws.roomId || !ws.clientId) {
        leaveRoom(ws);
        return;
    }
    announcePeerLeft(ws);
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
    ws.pageSessionId = null;
    ws.leftAnnounced = false;
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
            const pageSessionId = typeof msg.pageSessionId === 'string' ? msg.pageSessionId.trim().slice(0, 64) : '';
            let room = rooms.get(roomId);
            if (!room) { room = new Map(); rooms.set(roomId, room); }

            let resumed = false;
            let hardRefresh = false;
            let peerLeftAnnounced = false;
            if (clientId) {
                for (const peerWs of room.values()) {
                    if (peerWs.clientId === clientId) {
                        hardRefresh = !!(pageSessionId && peerWs.pageSessionId !== pageSessionId);
                        peerLeftAnnounced = !!peerWs.leftAnnounced;
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
            ws.pageSessionId = pageSessionId || null;
            // Store username from join message as well (backup for set-username)
            if (username) ws.username = username;

            if (resumed) {
                for (const { peerId } of existingPeers) {
                    const peerWs = room.get(peerId);
                    if (!peerWs) continue;
                    if (hardRefresh || peerLeftAnnounced) {
                        if (!peerLeftAnnounced) send(peerWs, { type: 'peer-left', peerId: ws.peerId, username: ws.username || null });
                        send(peerWs, { type: 'peer-joined', peerId: ws.peerId, username: ws.username || null });
                    } else {
                        send(peerWs, { type: 'peer-resumed', peerId: ws.peerId, username: ws.username || null });
                    }
                }
            }
            send(ws, { type: 'joined', roomId, peerId: ws.peerId, peers: existingPeers, resumed, hardRefresh });
            if (!resumed) {
                for (const { peerId } of existingPeers) {
                    const peerWs = room.get(peerId);
                    if (peerWs) send(peerWs, { type: 'peer-joined', peerId: ws.peerId, username: ws.username || null });
                }
            }
            const roomWatchers = watchers.get(roomId);
            if (roomWatchers) {
                for (const watcher of roomWatchers) send(watcher, {
                    type: resumed && !hardRefresh && !peerLeftAnnounced ? 'peer-resumed' : 'peer-joined',
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
    if (TURN_SERVER_URL && TURN_SECRET) console.log('TURN configured');
});
