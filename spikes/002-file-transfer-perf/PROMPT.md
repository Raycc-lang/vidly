# Task: Analyze WebRTC DataChannel File Transfer Performance

## Context

This is a P2P video call app (Vidly). File/image transfer between peers uses WebRTC DataChannels.
The user reports file transfers are very slow. A previous attempt to optimize (increasing chunk size)
made things WORSE, so it was reverted. We need to understand WHY before making changes.

## Key Constraint

**Previous failed attempt:** Someone increased FILE_CHUNK_SIZE from 16KB to a larger value.
Result: transfers became SLOWER, not faster. This was reverted. We need to understand the root
cause before proposing any changes. Do NOT blindly suggest "increase chunk size" — explain why
it might have backfired.

## What to Analyze

1. **Root cause of slowness** — Is it the chunk size? The stop-and-wait pattern? SCTP congestion
   control? Something else entirely?

2. **Why larger chunk size made things worse** — Possible explanations:
   - Large ArrayBuffer allocation causing GC pressure?
   - SCTP fragmentation at the protocol layer negating the benefit?
   - `file.slice().arrayBuffer()` with large slices being slow?
   - Browser DataChannel send queue behavior with large messages?
   - Interaction with `waitForFileBuffer` backpressure thresholds?

3. **DataChannel configuration** — The file channel is created with defaults (ordered, reliable).
   What SCTP parameters affect throughput? Should we tune `maxPacketLifeTime`, `maxRetransmits`,
   or use `ordered: false`?

4. **Pipelining** — Currently reads chunk → waits for buffer → sends chunk, fully sequential.
   Would pipelining (read next chunk while current one is in flight) help? Or does the await
   on `file.slice().arrayBuffer()` already effectively pipeline?

5. **What actually matters** — Given the previous failed attempt, what changes would ACTUALLY
   improve throughput with evidence/reasoning?

## Code — Web Client (public/index.html)

### Constants
```js
const FILE_CHUNK_SIZE = 16 * 1024;      // 16KB
const FILE_BUFFER_HIGH = 256 * 1024;    // 256KB — pause threshold
const FILE_BUFFER_LOW = 64 * 1024;      // 64KB — resume threshold
```

### DataChannel creation (default config, no special options)
```js
// Line 1982
const fileDc = pc.createDataChannel('file');
attachFileDataChannel(peer, fileDc);
```

### File channel setup
```js
function attachFileDataChannel(peer, dc) {
    peer.fileDc = dc;
    dc.binaryType = 'arraybuffer';
    dc.bufferedAmountLowThreshold = FILE_BUFFER_LOW;  // 64KB
    dc.onmessage = (e) => handleFileChunk(peer, e.data);
    dc.onclose = () => {};
}
```

### Sending (the hot path)
```js
async function sendFileToPeer(peer, transfer) {
    const dc = peer.fileDc;
    if (!dc || dc.readyState !== 'open') throw new Error('file channel is closed');
    if (transfer.file.size === 0) {
        sendPeerDataMessage(peer, { type: 'file-empty', id: transfer.meta.id });
        return;
    }
    let offset = 0;
    while (offset < transfer.file.size) {
        if (dc.readyState !== 'open') throw new Error('file channel is closed');
        const chunk = await transfer.file.slice(offset, offset + FILE_CHUNK_SIZE).arrayBuffer();
        await waitForFileBuffer(dc);
        dc.send(chunk);
        offset += chunk.byteLength;
    }
}
```

### Backpressure
```js
function waitForFileBuffer(dc) {
    if (dc.bufferedAmount <= FILE_BUFFER_HIGH) return Promise.resolve();
    return new Promise((resolve) => {
        let poll = null;
        const done = () => {
            dc.removeEventListener('bufferedamountlow', done);
            if (poll) clearInterval(poll);
            resolve();
        };
        poll = setInterval(() => {
            if (dc.bufferedAmount <= FILE_BUFFER_LOW || dc.readyState !== 'open') done();
        }, 25);
        dc.addEventListener('bufferedamountlow', done);
    });
}
```

### Receiving
```js
function handleFileChunk(peer, data) {
    if (!(data instanceof ArrayBuffer)) return;
    let transfer = incomingFiles.get(peer.activeIncomingFileId);
    if (!transfer || transfer.peer !== peer) transfer = null;
    if (!transfer) return;
    transfer.chunks.push(data);
    transfer.received += data.byteLength;
    // ... progress updates ...
    if (transfer.received >= transfer.meta.size) {
        completeIncomingFile(peer, transfer.meta.id);
    }
}
```

## Code — Android Native (NativeWebRtcClient.kt + NativeCallActivity.kt)

### DataChannel creation (also default config)
```kotlin
peer.fileChannel = peer.pc.createDataChannel("file", DataChannel.Init())
```

### Chunk size + sending (runs on background thread)
```kotlin
private const val FILE_CHUNK_SIZE = 16 * 1024
private const val FILE_BUFFER_HIGH = 256L * 1024L

// In NativeCallActivity:
var offset = 0
val chunkSize = FILE_CHUNK_SIZE
while (offset < file.bytes.size) {
    val rtcRef = rtc ?: break
    while (rtcRef.fileChannelBufferedAmount(peerId) > FILE_BUFFER_HIGH) {
        try { Thread.sleep(20) } catch (_: InterruptedException) { return }
    }
    val end = minOf(offset + chunkSize, file.bytes.size)
    val chunk = file.bytes.copyOfRange(offset, end)
    val ok = rtcRef.sendFileChunk(peerId, chunk)
    if (!ok) { /* fail */ return }
    offset += chunk.size
}
```

### Send chunk
```kotlin
fun sendFileChunk(peerId: String, bytes: ByteArray): Boolean {
    val peer = peers[peerId] ?: return false
    val dc = peer.fileChannel ?: return false
    if (dc.state() != DataChannel.State.OPEN) return false
    return runCatching {
        dc.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), true))
        true
    }.getOrDefault(false)
}
```

## Environment Notes

- The app is used on both LAN (same WiFi) and WAN (TURN relay through VPS)
- TURN relay bandwidth is limited by VPS (likely 100Mbps shared)
- Typical use case: 2-4 peers, sending images (1-5MB) and occasional files (10-50MB)
- Both web and Android clients must be compatible (same protocol)

## Deliverable

Write your analysis to `/home/ray/video-call/spikes/002-file-transfer-perf/ANALYSIS.md`:

1. Root cause analysis with evidence
2. Why the previous chunk size increase backfired
3. Ranked list of improvements that would ACTUALLY help, with expected impact
4. For each proposed change, explain the risk of making things worse
5. A concrete implementation plan with the specific changes to make

Do NOT make any code changes. Analysis only.
