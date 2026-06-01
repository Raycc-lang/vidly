# WebRTC DataChannel File Transfer Performance Analysis

## Executive Summary

The current slowness is unlikely to be caused by `FILE_CHUNK_SIZE = 16KB` being too small. The stronger suspect is the sender pacing/backpressure policy around `bufferedAmount`, combined with reliable ordered SCTP behavior and competition with live audio/video on the same `RTCPeerConnection`.

The reverted chunk-size experiment probably backfired because larger application messages are not the same thing as larger network packets. WebRTC DataChannels run over SCTP, which fragments large user messages internally. RFC 8831 explicitly says that when SCTP message interleaving is not supported, senders should limit messages to 16KB to avoid monopolizing the SCTP association. Larger chunks can therefore increase head-of-line blocking, queue sawtooth amplitude, retransmission cost, allocation pressure, and latency for chat/media control traffic without improving actual wire throughput.

The first performance work should be measurement plus pacing: record real throughput, `bufferedAmount` behavior, ICE candidate pair type, RTT, packet loss, and whether the path is LAN, direct WAN, or TURN. Then tune the send queue window while keeping chunks near 16KB. Do not change file DataChannels to unreliable delivery for normal files.

Sources:

- MDN: [`RTCDataChannel.bufferedAmount`](https://developer.mozilla.org/en-US/docs/Web/API/RTCDataChannel/bufferedAmount) is bytes queued in the user agent, not OS/network overhead.
- MDN: [`RTCDataChannel`](https://developer.mozilla.org/en-US/docs/Web/API/RTCDataChannel) defaults to ordered delivery; `maxRetransmits: null` means no retransmit limit.
- MDN: [`RTCDataChannel.send()`](https://developer.mozilla.org/en-US/docs/Web/API/RTCDataChannel/send) can throw `OperationError` when data would need buffering and there is no room.
- RFC 8831: [WebRTC Data Channels](https://www.rfc-editor.org/rfc/rfc8831.html) require SCTP congestion control, fragmentation/reassembly, and recommend limiting message size to 16KB when message interleaving is unavailable.
- W3C WebRTC: [`bufferedAmountLowThreshold`](https://www.w3.org/TR/webrtc/#dom-datachannel-bufferedamountlowthreshold) fires when the buffered amount crosses from above the threshold to at or below it.

## Current Implementation

Web:

- File channel is created with defaults: `pc.createDataChannel('file')`.
- Defaults mean ordered, reliable delivery.
- Chunk size is `16 * 1024`.
- High watermark is `256KB`; low watermark is `64KB`.
- Send loop reads one chunk, waits only if `dc.bufferedAmount > FILE_BUFFER_HIGH`, then calls `dc.send(chunk)`.
- Receiver stores every `ArrayBuffer` in `transfer.chunks`, updates UI per chunk, then creates one `Blob` at completion.

Android native:

- File channel is also created with default `DataChannel.Init()`.
- Current `android/app-native` uses `16KB` chunks and `256KB` high watermark.
- The older `android/app` path still shows `64KB` chunks and `512KB` high watermark. Confirm whether this module is shipped. If it is still used, web/native behavior is not aligned.
- Android sender preloads the whole selected file into a `ByteArray`, then allocates a new `copyOfRange` byte array for every chunk.

## Root Cause Analysis

### 1. The sender is not true stop-and-wait, but it is a small sawtooth queue

The web sender does not wait for a per-chunk ACK before sending the next chunk. It keeps calling `send()` until `bufferedAmount` crosses the high watermark. With 16KB chunks and a 256KB high watermark, it can queue roughly 16 to 17 chunks before pausing.

However, once paused, web waits for `bufferedAmount` to fall to 64KB, then refills to about 272KB. That creates a small queue sawtooth of roughly 192KB. On a high-throughput or high-RTT path, especially through TURN, the SCTP sender may not always have enough application data queued to keep the path full.

This is not the same as TCP/SCTP congestion window size, and `bufferedAmount` does not include all network or OS buffering, but it is the only pacing signal available at the JS layer. If the application starves the user-agent queue, throughput suffers.

### 2. The 25ms polling fallback can cap refill responsiveness

The web code relies on `bufferedamountlow` plus a 25ms poll. In ideal browsers the event wakes immediately when crossing `FILE_BUFFER_LOW`. If the event is delayed, missed due to timing, or not fired consistently, the 25ms poll becomes the refill cadence.

A 192KB refill every 25ms is about 7.7MB/s, or about 61Mbps, before any SCTP, DTLS, TURN, retransmission, JS, and UI overhead. That is fine for many networks but below what LAN can do, and jitter can make it much worse. Android uses a 20ms sleep and waits only until `bufferedAmount <= 256KB`, so its sawtooth behavior differs.

### 3. Reliable ordered SCTP is correct for files but sensitive to loss

Files need reliable delivery. With default DataChannel settings, lost SCTP data is retransmitted and delivery is ordered. On lossy WAN/TURN paths, one lost fragment can block delivery of later ordered messages to the application until retransmission completes. This is normal head-of-line blocking.

This can look like "slow file transfer" even if the sender is actively transmitting, especially when the TURN VPS is bandwidth-limited or congested.

### 4. File traffic competes with audio/video and chat on the same PeerConnection

RFC 8831 requires DataChannels to be congestion-controlled and notes interaction with SRTP media streams. Vidly sends files during calls, often with 2 to 4 peers. Large reliable file traffic can compete with audio/video and chat/control messages on the same `RTCPeerConnection`.

This matters more on TURN because all traffic is relayed through the VPS. If the relay is the bottleneck, application chunk size tuning cannot exceed relay capacity.

### 5. Receiver and Android allocation patterns are secondary but real

The receiver keeps a list of all chunks until completion. For 10 to 50MB files, this is acceptable but can increase memory pressure, especially on mobile. Android additionally reads the whole file into memory before sending and allocates a new `ByteArray` per chunk with `copyOfRange`. Larger chunks reduce chunk count but increase allocation size and GC pause risk. For images, decode/preview work can also compete with UI responsiveness.

These are probably not the primary throughput limiter for 1 to 5MB images, but they can become visible for 50MB transfers on lower-end Android devices.

## Why Increasing Chunk Size Backfired

Increasing chunk size was a plausible optimization only if JS/Kotlin per-message overhead dominated. The observed regression means per-message overhead was probably not the bottleneck.

Likely explanations, ranked:

1. SCTP fragmentation and message interleaving

   DataChannels send SCTP user messages. Larger `dc.send()` payloads are fragmented below the API. If message interleaving is unavailable or limited, one large message can monopolize the SCTP association. RFC 8831 specifically recommends limiting message size to 16KB in that case. This directly explains why larger chunks could make the app slower even though there are fewer JS sends.

2. Worse `bufferedAmount` sawtooth

   The code checks `bufferedAmount` before sending. With 16KB chunks and a 256KB high watermark, the queue overshoots to about 272KB. With 64KB chunks, it can overshoot to about 320KB. With 256KB chunks, it can overshoot to about 512KB, then wait until 64KB before sending another large message.

   Bigger chunks therefore produce burstier traffic and longer idle gaps at the application layer. They also reduce the precision of backpressure control.

3. Larger retransmission and head-of-line units

   Although SCTP retransmits fragments internally, the browser delivers whole DataChannel messages to JS. A large user message affected by loss can delay application-visible progress more than several smaller messages. On an ordered reliable channel, this can amplify stalls.

4. Browser send queue limits and `OperationError`

   `RTCDataChannel.send()` has finite buffering. Larger messages consume more of that buffer per call and are more likely to hit implementation-specific limits or error paths. The current web sender does not catch `dc.send()` errors inside the loop.

5. Allocation and copy pressure

   Web creates an `ArrayBuffer` per chunk with `file.slice(...).arrayBuffer()`. Android creates a new `ByteArray` per chunk with `copyOfRange`. Larger chunks mean fewer allocations, but each allocation is larger and harder for GC to move or reclaim smoothly. This is a secondary explanation, but it can make mobile regressions sharper.

## DataChannel Configuration

### Keep default reliable delivery for normal files

Do not set `maxPacketLifeTime` or `maxRetransmits` for normal file transfer. Those options create partially reliable channels. That is useful for game state, telemetry, or ephemeral previews, but not for files unless the app adds its own chunk-level retransmission, checksums, and resume logic.

If a chunk is dropped on a partially reliable channel, the current protocol has no sequence numbers, missing-chunk detection, retransmit request, or integrity check. The receiver would either assemble corrupt data or stall forever depending on byte counts.

### `ordered: false` is not a free throughput switch

Unordered reliable delivery can reduce application-level head-of-line blocking between messages, but the current receiver assumes in-order chunks and appends them directly. Using unordered delivery would require:

- chunk sequence numbers
- total chunk count or final offset
- reassembly by offset
- missing-chunk tracking
- completion based on all chunks present, not only received byte count

For a single file, reliable unordered may improve progress smoothness under loss but not necessarily total completion time, because every byte is still required. It is more interesting when multiple files or control messages share a channel, but Vidly already has separate chat and file channels.

### Priority may be worth investigating, not relying on

The browser API exposes a `priority` option/property, but implementation support and impact are browser-dependent. It should not be the first fix. The more dependable control is application pacing.

## Pipelining Analysis

The web loop is sequential at the JS level:

1. read chunk into an `ArrayBuffer`
2. wait for backpressure if needed
3. enqueue with `dc.send()`
4. repeat

But `dc.send()` only queues data to the user agent; it does not wait for network delivery. Therefore the current loop already pipelines network transmission up to the `bufferedAmount` high watermark.

What is not pipelined is disk/blob reading while waiting for buffer drain. The code reads the next chunk before `waitForFileBuffer()`, so when the buffer is high it may hold one extra chunk in memory while waiting. That is not useful pipelining; it just moves the read earlier. A better shape is:

- wait until there is queue capacity
- read a bounded batch of chunks
- send until reaching high watermark
- yield to the event loop

For typical local `File` objects, `arrayBuffer()` for 16KB slices is probably not the bottleneck. For Android, reading the whole file before the transfer is a bigger architectural issue than per-chunk pipelining.

## What Actually Matters

### Ranked improvements

#### 1. Add transfer instrumentation before tuning

Expected impact: high confidence diagnosis, low direct throughput impact.

Measure per transfer:

- file size, chunk size, high/low watermarks
- bytes sent per second and bytes received per second
- time spent reading chunks
- time spent waiting for `bufferedAmount`
- `bufferedAmount` min/max over time
- count of `bufferedamountlow` events versus poll wakeups
- `dc.send()` exceptions
- selected ICE candidate pair: direct LAN, srflx/prflx, or relay
- `getStats()` current RTT, available outgoing bitrate if exposed, bytes sent, packets lost/retransmitted where available
- whether audio/video tracks are active and approximate media bitrate

Risk of making things worse: very low if logs are sampled, not emitted per chunk to the DOM/console. Per-chunk console logging would itself slow transfers.

#### 2. Keep chunk size at 16KB; tune queue watermarks instead

Expected impact: medium to high on LAN/direct WAN if the current sender is underfeeding the transport.

Change the experiment from "larger chunks" to "same 16KB chunks, larger application send window." For example, test:

- chunk: 16KB, high: 512KB, low: 256KB
- chunk: 16KB, high: 1MB, low: 512KB
- chunk: 16KB, high: 2MB, low: 1MB

The goal is to keep the SCTP sender fed without creating huge messages. This directly targets the suspected queue starvation while respecting the 16KB SCTP guidance.

Risk of making things worse: medium. Larger queues increase memory use, can increase latency for chat/control traffic, and can compete harder with video. This should be adaptive or capped, and measurements should include call quality.

#### 3. Replace the web wait function with explicit capacity-based pacing

Expected impact: medium.

The current `waitForFileBuffer()` waits only when `bufferedAmount > high`, then resumes only at `low`. Prefer a send loop that checks whether `bufferedAmount + nextChunkSize <= high` before reading/sending. That avoids high watermark overshoot and makes behavior stable across chunk sizes.

Also use a single `onbufferedamountlow`/event path plus a short timeout fallback, and record which path woke the sender. Avoid creating many event listeners if the loop evolves.

Risk of making things worse: low to medium. Too strict a capacity check can underfill the queue if `high` remains too small. The fix should be paired with watermark experiments.

#### 4. Stream Android file reads instead of preloading and copying the whole file

Expected impact: low for tiny images, medium for 10 to 50MB files, high for memory stability.

Android currently reads the whole URI into a `ByteArray`, then `copyOfRange`s each chunk. Use an `InputStream` and a reusable buffer, copying only the bytes needed for the DataChannel buffer if required by WebRTC object lifetime. This reduces peak memory and GC pressure.

Risk of making things worse: medium. URI streams can block, need cancellation, and must not run on the UI thread. The buffer must not be mutated before WebRTC has consumed it unless `send()` copies synchronously, which should not be assumed without verifying.

#### 5. Add chunk metadata and integrity checks

Expected impact: low immediate speed impact, high protocol robustness.

Add a small binary or JSON control envelope containing transfer id, sequence number, offset, and total chunks, or use a compact binary header before each payload. Add a final hash for integrity. This enables future unordered reliable transfer, resume, missing-chunk repair, and safer failure detection.

Risk of making things worse: medium. It changes the wire protocol and must remain compatible between web and Android. Metadata overhead is small, but implementation bugs can break transfers.

#### 6. Consider reliable unordered only after reassembly exists

Expected impact: uncertain. Potentially improves progress smoothness on lossy paths, but may not improve total completion time.

Once chunks have sequence numbers and reassembly by offset, test a file channel with `ordered: false` while keeping reliability. Keep `maxRetransmits` and `maxPacketLifeTime` unset.

Risk of making things worse: medium to high. Browser/native behavior may vary, and out-of-order delivery stresses receiver memory and bookkeeping. It can also make progress UI less intuitive if chunks arrive far ahead of gaps.

#### 7. Separate bulk files from live call quality policy

Expected impact: medium for user experience, not necessarily raw throughput.

When a large file is active, consider either:

- limiting file queue target while video is active
- warning that TURN relay transfers may be slower
- optionally lowering video bitrate during large transfers
- showing path type: "direct" versus "relay"

Risk of making things worse: medium. Over-throttling files makes transfers slower; lowering video bitrate harms call quality. This needs user-facing policy, not a hidden global throttle.

## Concrete Implementation Plan

### Phase 1: Measurement only

No protocol changes.

Web:

- Add a transfer stats object in `sendFileToPeer`.
- Sample once every 250ms or 500ms, not per chunk.
- Track bytes sent, elapsed time, current `dc.bufferedAmount`, max buffered amount, read wait time, buffer wait time, and send errors.
- Add `pc.getStats()` sampling for the selected candidate pair and outbound transport where available.
- Record whether the selected candidate pair is relay/direct.
- Show or log a compact summary at completion/failure.

Android:

- Add equivalent timing counters around buffer wait, chunk copy/read, and `sendFileChunk`.
- Sample `fileChannel.bufferedAmount()`.
- If possible through native WebRTC stats APIs, capture candidate pair type and RTT.

Acceptance criteria:

- A completed transfer reports average Mbps, peak `bufferedAmount`, total buffer-wait time, path type, and active media state.
- LAN and TURN runs can be compared.

### Phase 2: Pacing experiment with 16KB chunks

Keep `FILE_CHUNK_SIZE = 16 * 1024`.

Web:

- Change the sender to wait for capacity before reading the next chunk:

  ```js
  while (offset < file.size) {
      const nextSize = Math.min(FILE_CHUNK_SIZE, file.size - offset);
      await waitForFileBufferCapacity(dc, nextSize);
      const chunk = await file.slice(offset, offset + nextSize).arrayBuffer();
      dc.send(chunk);
      offset += chunk.byteLength;
  }
  ```

- Implement `waitForFileBufferCapacity(dc, nextSize)` as `bufferedAmount + nextSize <= FILE_BUFFER_HIGH`.
- Test high/low pairs of `512KB/256KB`, `1MB/512KB`, and `2MB/1MB`.
- Keep a timeout/poll fallback, but measure when fallback is used.

Android:

- Align the same high watermark experiment.
- Prefer a low watermark if native callbacks are available; otherwise keep short sleep but measure wait time.

Acceptance criteria:

- Throughput improves on LAN/direct WAN without significant media degradation.
- TURN throughput is explained by relay capacity if it remains slow.
- No regression versus the current 16KB/256KB baseline.

### Phase 3: Android memory improvement

- Replace whole-file preload for large files with stream-based sending.
- Keep existing preload path for small images only if it simplifies preview.
- Avoid mutating buffers after passing them to WebRTC unless the native API is confirmed to copy synchronously.

Acceptance criteria:

- 50MB file send does not require holding multiple full-file copies.
- Android GC pauses and UI stalls decrease.

### Phase 4: Protocol hardening

- Add chunk sequence/offset metadata and a protocol version.
- Add a final checksum.
- Keep ordered reliable mode at first.
- Once stable, run an A/B test for reliable unordered channels.

Acceptance criteria:

- Receivers can detect missing/corrupt transfers.
- Web and Android remain interoperable.
- Unordered mode is enabled only if measured to help.

## Recommendations Not To Do Yet

- Do not increase `FILE_CHUNK_SIZE` as the primary fix.
- Do not set `maxRetransmits` or `maxPacketLifeTime` for normal files.
- Do not switch to `ordered: false` until the protocol can reassemble by sequence/offset.
- Do not judge performance without separating LAN, direct WAN, and TURN relay cases.
- Do not add per-chunk console logging or DOM updates as instrumentation.

## Bottom Line

The safest hypothesis is: 16KB chunks are deliberately conservative and likely correct; the current queue window and pacing are too small/coarse for some paths; reliable ordered SCTP and TURN bottlenecks explain stalls under loss; and larger chunks made things worse because they fought SCTP's fragmentation/interleaving behavior and made the application's backpressure sawtooth burstier.

The next change should be measured 16KB chunk pacing with larger bounded watermarks, not bigger chunks.
