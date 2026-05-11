const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 3000;

// HTTP server for serving static files
const server = http.createServer((req, res) => {
  let filePath = req.url === '/' ? '/index.html' : req.url;
  filePath = path.join(__dirname, 'public', filePath);
  const ext = path.extname(filePath);
  const types = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css' };
  
  fs.readFile(filePath, (err, data) => {
    if (err) { res.writeHead(404); res.end('Not found'); return; }
    res.writeHead(200, { 'Content-Type': types[ext] || 'application/octet-stream' });
    res.end(data);
  });
});

// WebSocket signaling server
const wss = new WebSocketServer({ server });
const rooms = new Map(); // roomId -> Set<ws>

wss.on('connection', (ws) => {
  let currentRoom = null;
  
  ws.on('message', (data) => {
    let msg;
    try { msg = JSON.parse(data); } catch { return; }
    
    if (msg.type === 'join') {
      currentRoom = msg.roomId;
      if (!rooms.has(currentRoom)) rooms.set(currentRoom, new Set());
      rooms.get(currentRoom).add(ws);
      // Notify others
      for (const client of rooms.get(currentRoom)) {
        if (client !== ws && client.readyState === 1) {
          client.send(JSON.stringify({ type: 'peer-joined' }));
        }
      }
    } else if (msg.type === 'signal') {
      // Relay to all other peers in the room
      if (currentRoom && rooms.has(currentRoom)) {
        for (const client of rooms.get(currentRoom)) {
          if (client !== ws && client.readyState === 1) {
            client.send(JSON.stringify(msg));
          }
        }
      }
    }
  });
  
  ws.on('close', () => {
    if (currentRoom && rooms.has(currentRoom)) {
      rooms.get(currentRoom).delete(ws);
      for (const client of rooms.get(currentRoom)) {
        if (client.readyState === 1) {
          client.send(JSON.stringify({ type: 'peer-left' }));
        }
      }
      if (rooms.get(currentRoom).size === 0) rooms.delete(currentRoom);
    }
  });
});

server.listen(PORT, () => console.log(`Server running on port ${PORT}`));
