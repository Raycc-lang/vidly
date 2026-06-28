/**
 * RoomDO — Durable Object for WebRTC signaling room management.
 *
 * Uses WebSocket Hibernation API.
 *
 * Key design decisions:
 * 1. fetch() only accepts the WebSocket and sends welcome — does NOT add to room.
 * 2. join() is the ONLY place that manages room membership.
 * 3. webSocketClose() properly removes the peer (via removePeerFromRoom).
 *    This is safe even if leave was already handled — removePeerFromRoom
 *    returns null when ws._roomId is already null.
 * 4. ClientId-based reconnection sets resumed=true, preventing duplicate entries.
 */

const rooms = new Map();

class Room {
  constructor(id) {
    this.id = id;
    this.peers = new Map();  // Map<peerId, {transport, ws, username, clientId, pageSessionId, leftAnnounced, queue, nextMessageId}>
    this.watcherWs = new Set();
  }
}

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': '*',
};

function jsonResponse(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { ...corsHeaders, 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
  });
}

function errorResponse(error, status = 400) {
  return jsonResponse({ error }, status);
}

function withDiagnosticHeaders(response, trace, stage) {
  const headers = new Headers(response.headers);
  if (trace) headers.set('X-Vidly-Trace', trace);
  headers.set('X-Vidly-DO-Stage', stage);
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers,
  });
}

function normalizeUsername(username) {
  return (typeof username === 'string' ? username.trim().slice(0, 32) : '');
}

function normalizeClientId(clientId) {
  return (typeof clientId === 'string' ? clientId.trim().slice(0, 64) : '');
}

function normalizePageSessionId(pageSessionId) {
  return (typeof pageSessionId === 'string' ? pageSessionId.trim().slice(0, 64) : '');
}

function ensureQueue(info) {
  if (!info.queue) info.queue = [];
  if (!info.nextMessageId) info.nextMessageId = 1;
}

function enqueue(info, msg) {
  ensureQueue(info);
  const id = info.nextMessageId++;
  info.queue.push({ id, msg });
  if (info.queue.length > 256) info.queue.splice(0, info.queue.length - 256);
  return id;
}

function deliver(info, msg) {
  if (info.transport === 'http') {
    enqueue(info, msg);
    return;
  }
  if (info.ws) sendJson(info.ws, msg);
}

function removePeerFromRoom(ws) {
  const roomId = ws._roomId;
  const peerId = ws._peerId;
  if (!roomId || !peerId) return null;
  const room = rooms.get(roomId);
  if (!room) return null;
  const info = room.peers.get(peerId);
  if (info && info.ws === ws) {
    room.peers.delete(peerId);
    if (room.peers.size === 0) rooms.delete(roomId);
    ws._roomId = null;
    ws._peerId = null;
    ws._clientId = null;
    return { room, peerId, username: info.username };
  }
  return null;
}

function removePeerById(roomId, peerId) {
  if (!roomId || !peerId) return null;
  const room = rooms.get(roomId);
  if (!room) return null;
  const info = room.peers.get(peerId);
  if (!info) return null;
  room.peers.delete(peerId);
  if (info.ws) {
    try { info.ws._roomId = null; info.ws._peerId = null; info.ws._clientId = null; } catch {}
  }
  if (room.peers.size === 0) rooms.delete(roomId);
  return { room, peerId, username: info.username };
}

function sendJson(ws, obj) {
  try { ws.send(JSON.stringify(obj)); } catch {}
}

function broadcast(room, senderPeerId, msg) {
  for (const [pid, info] of room.peers) {
    if (pid !== senderPeerId) deliver(info, msg);
  }
}

function broadcastWatchers(room, msg) {
  for (const ws of room.watcherWs) sendJson(ws, msg);
}

export class RoomDO {
  constructor(ctx) { this.ctx = ctx; }

  async fetch(request) {
    const url = new URL(request.url);
    const roomId = url.searchParams.get('roomId') || 'default';
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    if (url.pathname.startsWith('/api/signal/')) {
      return this.handleHttpSignal(request, url, roomId);
    }

    const upgrade = request.headers.get('Upgrade');
    if (!upgrade || upgrade.toLowerCase() !== 'websocket') {
      return new Response('Expected WebSocket upgrade', { status: 426 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.ctx.acceptWebSocket(server);

    // Metadata only — does NOT pre-join any room
    server._peerId = crypto.randomUUID();
    server._roomId = null;
    server._clientId = null;
    server._username = null;

    sendJson(server, { type: 'welcome', peerId: server._peerId });
    return new Response(null, { status: 101, webSocket: client });
  }

  async handleHttpSignal(request, url, roomId) {
    const path = url.pathname;
    const trace = request.headers.get('X-Vidly-Trace') || crypto.randomUUID();
    const started = Date.now();
    console.log('RoomDO httpSignal start', {
      trace,
      method: request.method,
      path,
      roomId,
      contentType: request.headers.get('content-type') || '',
      contentLength: request.headers.get('content-length') || '',
    });

    try {
      if (path === '/api/signal/join' && request.method === 'POST') {
        const bodyText = await request.text();
        console.log('RoomDO join body', {
          trace,
          bytes: bodyText.length,
          preview: bodyText.slice(0, 120),
        });
        const body = JSON.parse(bodyText || '{}');
        const response = this.httpJoin(roomId, body);
        console.log('RoomDO join response', {
          trace,
          status: response.status,
          durationMs: Date.now() - started,
        });
        return withDiagnosticHeaders(response, trace, 'join');
      }
      if (path === '/api/signal/send' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        return withDiagnosticHeaders(this.httpSend(roomId, body), trace, 'send');
      }
      if (path === '/api/signal/poll' && request.method === 'GET') {
        return withDiagnosticHeaders(
          this.httpPoll(roomId, url.searchParams.get('peerId'), url.searchParams.get('since')),
          trace,
          'poll'
        );
      }
      if (path === '/api/signal/leave' && request.method === 'POST') {
        const body = await request.json().catch(() => ({}));
        return withDiagnosticHeaders(this.httpLeave(roomId, body.peerId), trace, 'leave');
      }
      return withDiagnosticHeaders(errorResponse('not_found', 404), trace, 'not-found');
    } catch (e) {
      console.error('handleHttpSignal error:', { trace, error: String(e) });
      return withDiagnosticHeaders(errorResponse('internal_error', 500), trace, 'error');
    }
  }

  joinRoom(transport, endpoint, joinRoomId, usernameRaw, clientIdRaw, pageSessionIdRaw) {
    const username = normalizeUsername(usernameRaw);
    const clientId = normalizeClientId(clientIdRaw);
    const pageSessionId = normalizePageSessionId(pageSessionIdRaw);
    let peerId = endpoint._peerId || crypto.randomUUID();

    let targetRoom = rooms.get(joinRoomId);
    if (!targetRoom) { targetRoom = new Room(joinRoomId); rooms.set(joinRoomId, targetRoom); }

    if (clientId) {
      for (const [pid, info] of targetRoom.peers) {
        if (info.clientId === clientId) {
          for (const [opid, oi] of targetRoom.peers) {
            if (opid !== pid && oi.username && oi.username === username) {
              return { ok: false, message: { type: 'username-taken', username }, status: 409 };
            }
          }

          const previousPageSessionId = info.pageSessionId;
          const wasLeftAnnounced = !!info.leftAnnounced;
          const hardRefresh = !!(pageSessionId && previousPageSessionId !== pageSessionId);

          if (transport === 'ws' && pid !== peerId) endpoint._peerId = pid;
          peerId = pid;
          if (info.ws && info.ws !== endpoint) {
            try { info.ws._roomId = null; info.ws._peerId = null; info.ws._clientId = null; } catch {}
          }
          info.transport = transport;
          info.ws = transport === 'ws' ? endpoint : null;
          info.username = username || info.username;
          info.clientId = clientId;
          info.pageSessionId = pageSessionId || null;
          info.leftAnnounced = false;
          ensureQueue(info);

          if (transport === 'ws') {
            endpoint._roomId = joinRoomId;
            endpoint._clientId = clientId;
            endpoint._username = info.username;
          }

          const otherPeers = [];
          for (const [opid, oi] of targetRoom.peers) {
            if (opid !== peerId) otherPeers.push({ peerId: opid, username: oi.username });
          }

          const joined = {
            type: 'joined', roomId: joinRoomId, peerId,
            peers: otherPeers, resumed: true, hardRefresh,
          };

          for (const { peerId: opid } of otherPeers) {
            const oi = targetRoom.peers.get(opid);
            if (!oi) continue;
            if (hardRefresh || wasLeftAnnounced) {
              if (!wasLeftAnnounced) deliver(oi, { type: 'peer-left', peerId, username: info.username });
              deliver(oi, { type: 'peer-joined', peerId, username: info.username });
            } else {
              deliver(oi, { type: 'peer-resumed', peerId, username: info.username });
            }
          }

          broadcastWatchers(targetRoom, {
            type: hardRefresh || wasLeftAnnounced ? 'peer-joined' : 'peer-resumed',
            peerId, username: info.username,
          });

          return { ok: true, message: joined, cursor: (info.nextMessageId || 1) - 1 };
        }
      }
    }

    for (const [pid, info] of targetRoom.peers) {
      if (transport === 'ws' && info.ws === endpoint) {
        targetRoom.peers.delete(pid);
        break;
      }
    }

    if (targetRoom.peers.size >= 4) {
      return { ok: false, message: { type: 'room-full' }, status: 409 };
    }

    for (const [, info] of targetRoom.peers) {
      if (info.username && info.username === username) {
        return { ok: false, message: { type: 'username-taken', username }, status: 409 };
      }
    }

    const info = {
      transport,
      ws: transport === 'ws' ? endpoint : null,
      username,
      clientId: clientId || null,
      pageSessionId: pageSessionId || null,
      leftAnnounced: false,
      queue: [],
      nextMessageId: 1,
    };

    if (transport === 'ws') {
      endpoint._roomId = joinRoomId;
      endpoint._clientId = clientId || null;
      endpoint._username = username || endpoint._username;
      info.username = endpoint._username;
    }

    targetRoom.peers.set(peerId, info);

    const otherPeers = [];
    for (const [pid, oi] of targetRoom.peers) {
      if (pid !== peerId) otherPeers.push({ peerId: pid, username: oi.username });
    }

    const joined = {
      type: 'joined', roomId: joinRoomId, peerId,
      peers: otherPeers, resumed: false, hardRefresh: false,
    };

    for (const { peerId: pid } of otherPeers) {
      const oi = targetRoom.peers.get(pid);
      if (oi) deliver(oi, { type: 'peer-joined', peerId, username: info.username });
    }

    broadcastWatchers(targetRoom, {
      type: 'peer-joined', peerId, username: info.username,
    });

    return { ok: true, message: joined, cursor: 0 };
  }

  httpJoin(roomId, body) {
    const result = this.joinRoom('http', {}, roomId, body.username, body.clientId, body.pageSessionId);
    return jsonResponse({ ...result.message, cursor: result.cursor }, result.status || 200);
  }

  httpSend(roomId, body) {
    const peerId = typeof body.peerId === 'string' ? body.peerId : '';
    const to = typeof body.to === 'string' ? body.to : '';
    if (!peerId || !to) return errorResponse('missing_peer', 400);
    const room = rooms.get(roomId);
    if (!room || !room.peers.has(peerId)) return errorResponse('not_joined', 404);
    const target = room.peers.get(to);
    if (!target) return jsonResponse({ ok: true, delivered: false });
    deliver(target, { type: 'signal', from: peerId, payload: body.payload || {} });
    return jsonResponse({ ok: true, delivered: true });
  }

  httpPoll(roomId, peerId, sinceRaw) {
    const room = rooms.get(roomId);
    const info = room?.peers.get(peerId || '');
    if (!room || !info) return errorResponse('not_joined', 404);
    ensureQueue(info);
    const since = Math.max(0, parseInt(sinceRaw || '0', 10) || 0);
    const items = info.queue.filter(item => item.id > since);
    const cursor = items.length > 0 ? items[items.length - 1].id : since;
    if (items.length > 0) {
      const keepAfter = Math.max(cursor - 32, 0);
      info.queue = info.queue.filter(item => item.id > keepAfter);
    }
    return jsonResponse({ ok: true, cursor, messages: items.map(item => ({ id: item.id, ...item.msg })) });
  }

  httpLeave(roomId, peerId) {
    const r = removePeerById(roomId, typeof peerId === 'string' ? peerId : '');
    if (r) {
      broadcast(r.room, r.peerId, { type: 'peer-left', peerId: r.peerId, username: r.username });
      broadcastWatchers(r.room, { type: 'peer-left', peerId: r.peerId, username: r.username });
    }
    return jsonResponse({ ok: true });
  }

  async webSocketMessage(ws, raw) {
    try {
      let msg;
      try { msg = JSON.parse(raw); } catch { return; }
    const roomId = ws._roomId;
    const peerId = ws._peerId;

    switch (msg.type) {

      case 'set-username': {
        if (typeof msg.username === 'string') {
          ws._username = normalizeUsername(msg.username);
          const room = rooms.get(roomId);
          if (room && peerId) {
            const info = room.peers.get(peerId);
            if (info) info.username = ws._username;
          }
        }
        break;
      }

      case 'join': {
        if (typeof msg.roomId !== 'string') break;
        const joinRoomId = msg.roomId;

        // Leave previous room if switching
        if (joinRoomId !== roomId) {
          const r = removePeerFromRoom(ws);
          if (r) {
            broadcast(r.room, r.peerId, { type: 'peer-left', peerId: r.peerId, username: r.username });
            broadcastWatchers(r.room, { type: 'peer-left', peerId: r.peerId, username: r.username });
          }
        }

        const result = this.joinRoom('ws', ws, joinRoomId, msg.username, msg.clientId, msg.pageSessionId);
        sendJson(ws, result.message);
        break;
      }

      case 'watch': {
        if (typeof msg.roomId !== 'string') break;
        const watchRoomId = msg.roomId;
        removePeerFromRoom(ws);
        if (ws._watchingRoom) {
          const prev = rooms.get(ws._watchingRoom);
          if (prev) prev.watcherWs.delete(ws);
        }
        ws._watchingRoom = watchRoomId;
        let targetRoom = rooms.get(watchRoomId);
        if (!targetRoom) { targetRoom = new Room(watchRoomId); rooms.set(watchRoomId, targetRoom); }
        targetRoom.watcherWs.add(ws);
        sendJson(ws, { type: 'watching', roomId: watchRoomId });
        for (const [pid, info] of targetRoom.peers) {
          sendJson(ws, { type: 'peer-joined', peerId: pid, username: info.username });
        }
        break;
      }

      case 'signal': {
        if (typeof msg.to !== 'string') break;
        if (!roomId || !peerId) return;
        const room = rooms.get(roomId);
        if (!room) return;
        const target = room.peers.get(msg.to);
        if (!target) return;
        deliver(target, { type: 'signal', from: peerId, payload: msg.payload });
        break;
      }

      case 'ping':
        sendJson(ws, { type: 'pong', ts: Date.now() });
        break;

      case 'leave': {
        const r = removePeerFromRoom(ws);
        if (r) {
          broadcast(r.room, r.peerId, { type: 'peer-left', peerId: r.peerId, username: r.username });
          broadcastWatchers(r.room, { type: 'peer-left', peerId: r.peerId, username: r.username });
        }
        break;
      }
    }
    } catch (e) {
      console.error('webSocketMessage error:', e);
    }
  }

  async webSocketClose(ws, code, reason) {
    const r = removePeerFromRoom(ws);
    if (r) {
      broadcast(r.room, r.peerId, { type: 'peer-left', peerId: r.peerId, username: r.username });
      broadcastWatchers(r.room, { type: 'peer-left', peerId: r.peerId, username: r.username });
    }
  }

  async webSocketError(ws, error) {
    // close will follow
  }
}
