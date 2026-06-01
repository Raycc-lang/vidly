# WebRTC DataChannel File Transfer — Performance Analysis

Spike: `002-file-transfer-perf`
Scope: Web (`public/index.html`) + Android (`app-native/NativeWebRtcClient.kt`, `NativeCallActivity.kt`).
Status: Analysis only — no code changes.

---

## TL;DR

The dominant cost is **not** chunk size and **not** SCTP per‑chunk overhead. It is the
fact that the loop is a **strict stop‑and‑wait at three different layers simultaneously**:

1. **Disk I/O is serialized with the network send** (`await file.slice().arrayBuffer()` runs
   while the SCTP stack drains; the SCTP stack drains while disk I/O blocks the main thread).
2. **The backpressure thresholds (256 KB high / 64 KB low) are far below the bandwidth‑delay
   product** of any non‑trivial link, so the SCTP send buffer is frequently empty.
3. **The backpressure wait has a 25 ms (web) / 20 ms (Android) polling fallback** — meaning
   each refill cycle can cost up to one timer tick of pure idle time.

The previous "increase chunk size" attempt almost certainly made things worse because it
**lowered the pipeline depth**: with 16 KB chunks and a 256 KB high‑water mark you have
~16 chunks in flight; with 128 KB chunks you have ~2; with 256 KB chunks you have **1** —
i.e. you converted an already‑shallow pipeline into a true stop‑and‑wait.

The fix that will actually move the needle is **raise the buffer high‑water mark to ~1 MB
and pipeline the disk read with the network send**. Chunk size is a third‑order knob and
should only be raised in combination with the threshold change.

---

## 1. Root cause analysis (with evidence)

### 1.1 Three layers of stop‑and‑wait

The web hot path is:

```js
while (offset < size) {
    const chunk = await file.slice(off, off + 16384).arrayBuffer(); // (a) read
    await waitForFileBuffer(dc);                                    // (b) backpressure
    dc.send(chunk);                                                 // (c) enqueue
    offset += 16384;
}
```

Each iteration crosses **two `await` boundaries before a single byte is enqueued to the
SCTP stack**. There is no overlap between disk read and network send, ever. The same is
true on Android (`NativeCallActivity.kt:1895–1912`): `copyOfRange` → `Thread.sleep(20)` poll →
`sendFileChunk`, all single‑threaded on a dedicated `Thread {}`.

**Consequence:** measured wall time =
  `sum(read_i) + sum(wait_i) + sum(enqueue_i) + drain_tail`,
all serialized. None of these terms is amortized against any other.

For a 50 MB file at 16 KB chunks = **3 200 iterations**. Even if `file.slice().arrayBuffer()`
costs only 0.3 ms on a fast SSD (Blob proxy IPC + heap allocation is non‑trivial in
Chromium), that is **~1 second of pure read latency** that the network is not using. On
slower devices (mobile/Android) the per‑call cost is higher.

### 1.2 Backpressure thresholds are below the bandwidth‑delay product

`bufferedAmount` is the bytes the application has handed to SCTP that have not yet been
acknowledged by the peer. To keep a link saturated, **`bufferedAmount` must stay above
the BDP at all times**.

Rough BDP numbers:

| Path           | BW       | RTT    | BDP        | Current HIGH | Verdict |
|----------------|----------|--------|------------|--------------|---------|
| LAN same WiFi  | ~300 Mb/s| ~3 ms  | ~110 KB    | 256 KB       | borderline OK |
| LAN wired      | ~1 Gb/s  | ~1 ms  | ~125 KB    | 256 KB       | borderline OK |
| WAN via TURN   | ~100 Mb/s| ~40 ms | **~500 KB**| 256 KB       | **starved** |
| WAN cross‑region| ~50 Mb/s| ~80 ms | **~500 KB**| 256 KB       | **starved** |

For TURN‑relayed transfers (the slow case the user is reporting), the high‑water mark is
**below the BDP**, which guarantees the SCTP congestion window cannot stay full. The
sender is provably under‑feeding the network for the majority of every backpressure cycle.

### 1.3 The 25 ms polling fallback turns one missed event into a long stall

```js
poll = setInterval(() => {
    if (dc.bufferedAmount <= FILE_BUFFER_LOW || dc.readyState !== 'open') done();
}, 25);
dc.addEventListener('bufferedamountlow', done);
```

In the happy path the event fires and `done()` runs. But `setInterval(..., 25)` is a
**floor of 25 ms** for any cycle where the event is delayed (background tab throttling,
heavy main thread, scheduler quantum). At 1 Gb/s that 25 ms = ~3 MB of throughput lost
per missed event. Android is worse: `Thread.sleep(20)` is the *only* mechanism — there is
no event‑driven wakeup at all, so every refill cycle eats up to 20 ms.

### 1.4 Per‑iteration scheduling overhead is non‑trivial

Each loop iteration on the web client costs at least **two microtask boundaries** plus
the promise machinery for `waitForFileBuffer` (an event listener allocation, a setInterval
allocation, a promise allocation, an addEventListener/removeEventListener pair). For
3 200 iterations on a 50 MB transfer this is real: easily 30–100 ms of pure scheduling
overhead, and it pins the main thread (blocking UI, RAF, etc.).

### 1.5 Receive‑side is not the bottleneck (today)

`handleFileChunk` is `O(1)` per chunk and `new Blob([...chunks])` is `O(n)` once at the
end. For 50 MB at 16 KB chunks that's a 3 200‑element array — annoying for GC but not the
gating factor. The `file-progress` ACK is rate‑limited to 250 ms so it doesn't compete
with chunks for the data channel.

### 1.6 What is *not* the root cause

- **SCTP per‑chunk overhead** (DATA chunk header ~16 bytes per fragment): negligible.
  16 KB user messages fragment to ~14 SCTP fragments of ~1200 bytes each over DTLS/UDP;
  per‑fragment header overhead is <2 %.
- **Congestion control being broken**: SCTP cwnd grows normally; we just don't keep it
  full.
- **DTLS encryption cost**: ~1–2 Gb/s on modern CPUs; not the bottleneck for 100 Mb/s
  TURN links.
- **Choice of `ordered: true, reliable`**: correct and necessary for files. Not the
  problem.

---

## 2. Why the previous chunk‑size increase backfired

This is the most important section to internalize, because the intuition "bigger chunks =
faster" is wrong **here** for a specific structural reason.

### 2.1 The pipeline‑depth inversion

`waitForFileBuffer` only pauses when `bufferedAmount > FILE_BUFFER_HIGH` (256 KB). The
**effective in‑flight depth** is therefore approximately:

```
depth ≈ floor(FILE_BUFFER_HIGH / chunk_size) + 1
```

| Chunk size | Depth | Behavior |
|------------|-------|----------|
| 16 KB      | ~17   | smooth pipeline, ~16 chunks queued |
| 64 KB      | ~5    | shallow pipeline |
| 128 KB     | ~3    | near stop‑and‑wait |
| 256 KB     | **1** | true stop‑and‑wait: send → drain to 64KB → send |
| 512 KB     | **1** | strictly worse: drain from 512KB → 64KB before next send |

When chunk size is raised without raising `FILE_BUFFER_HIGH` proportionally, **you
shorten the queue**. The SCTP stack now goes idle between sends, exactly the opposite of
the intended effect. The bigger the chunk you pick, the deeper the trough between sends.

This is, with very high confidence, the primary reason the previous attempt regressed.
It is reproducible by inspection of the constants alone.

### 2.2 Secondary contributors

Even if the depth had been preserved, larger chunks have downsides:

- **Latency to first byte on the wire grows linearly with chunk size.** For a 256 KB
  chunk, the sender must complete the entire `slice().arrayBuffer()` before *any* byte
  enters the SCTP send buffer. The first chunk delays the start of the transfer.
- **Browser `RTCSctpTransport.maxMessageSize` is finite.** Chromium/libwebrtc currently
  advertise 256 KB by default (negotiated via SDP `a=max-message-size`). Sending a
  message at or above this limit can fail outright or be silently rejected depending on
  peer/version. A 256 KB chunk is right at the cliff; 512 KB+ is past it.
- **Firefox interop** historically capped individual messages at 16 KB before EOR (End‑
  of‑Record) support became standard. Most modern Firefoxes do EOR, but mixed‑version
  fleets bite.
- **Allocator pressure.** Repeated 128–256 KB ArrayBuffer allocations land in the large
  object heap in V8 and pin V8's incremental GC into more frequent major cycles. Not
  catastrophic but measurable on long transfers.
- **`Blob.slice().arrayBuffer()` cost is not linear at very small *or* very large
  sizes.** Per‑call IPC overhead dominates for tiny slices; allocator + copy dominates
  for large ones. Around 32–128 KB is the sweet spot on most engines.
- **Per‑message send cost in libwebrtc** (Android side): each `DataChannel.Buffer` is
  copied into the native SCTP send queue. Per‑byte cost is low but per‑call latency
  exists; sending one huge message keeps the JNI thread blocked longer per call,
  reducing how quickly you can react to drain events.

### 2.3 What the previous engineer probably observed

They likely changed the `16 * 1024` constant on line 884 (and the matching Android
constant) to e.g. `64 * 1024` or `262144`. They left `FILE_BUFFER_HIGH = 256 * 1024` and
`FILE_BUFFER_LOW = 64 * 1024` untouched. As a result:

- The first send filled or overshot the high‑water mark.
- The loop then waited for `bufferedAmount` to fall to ≤ 64 KB, draining 192–448 KB
  before the next send.
- Steady state became: send → wait for ~3× chunk drain → send. Throughput collapsed.

This is the classic anti‑pattern with `bufferedAmountLowThreshold`: **chunk size and the
high/low thresholds are coupled**; you cannot tune one in isolation.

---

## 3. Ranked improvements

Each entry: expected impact, risk, why it might backfire.

### #1 — Raise the backpressure thresholds (HIGH and LOW)

**Change:** `FILE_BUFFER_HIGH = 1 * 1024 * 1024` (1 MB), `FILE_BUFFER_LOW = 256 * 1024`
(256 KB). Match on Android.

**Why it helps:** Keeps the SCTP send queue above BDP for both LAN (~125 KB) and TURN
WAN (~500 KB). The cwnd can stay full; the network stays saturated.

**Expected impact (rough):**
- LAN: 1.5–2× faster (already partially saturated).
- WAN/TURN: **3–5× faster** (currently starved).

**Risk of making things worse:**
- More buffered bytes means **slower cancellation** — if the user aborts a transfer,
  ~1 MB is still in flight on the wire and will be received anyway. Mitigation: send
  a control message and let the receiver discard.
- Slightly larger memory footprint per peer (1 MB × N peers). Acceptable.
- On very slow uplinks (e.g. 1 Mb/s mobile), 1 MB at 125 KB/s = 8 s of latency to
  drain. Likely fine — file transfer latency was never going to be sub‑second on such
  links — but worth noting.
- Will **not** help if disk read or scheduling overhead is the actual bottleneck (see
  #2).

### #2 — Pipeline disk read with network send (prefetch)

**Change:** Maintain one "next chunk" in flight at all times. While the current chunk is
in the SCTP queue, read the next chunk from disk. Structure:

```js
let pending = file.slice(0, CHUNK).arrayBuffer(); // start the first read
while (offset < size) {
    const chunk = await pending;                  // wait for current
    const nextOff = offset + chunk.byteLength;
    pending = nextOff < size
        ? file.slice(nextOff, nextOff + CHUNK).arrayBuffer()
        : null;                                   // start the next read NOW
    await waitForFileBuffer(dc);
    dc.send(chunk);
    offset = nextOff;
}
```

**Why it helps:** Removes disk I/O from the critical path. The read of chunk N+1 happens
concurrently with the wire transmission of chunk N. For files that fit comfortably in
RAM this is a small win; for large files on slower storage, or on Android where the
file is already a `ByteArray` (no read cost), this is bigger on the web client.

**Expected impact:** 10–30 % on web for large files; smaller on Android (file already in
memory there).

**Risk of making things worse:**
- One extra chunk of memory held (CHUNK bytes). Trivial.
- Adds a small amount of code complexity. Low risk of bugs if structured carefully —
  the invariant is "exactly one chunk is being read at any time".
- If chunk size is also changed, must re‑verify behaviour together.

### #3 — Replace the 25 ms / 20 ms poll fallback with event‑driven only (or much
shorter poll)

**Change (web):** Trust the `bufferedamountlow` event. Keep the poll as a safety net but
reduce to 5 ms, **or** remove it entirely (the event is reliable in modern
Chrome/Firefox/Safari for active tabs). Background‑tab throttling can still defeat it,
so a 100 ms safety poll is reasonable.

**Change (Android):** libwebrtc exposes `DataChannel.Observer.onBufferedAmountChange` —
use it to `notify()` the sender thread instead of `Thread.sleep(20)` polling. If the
observer is hard to wire in cleanly, drop the sleep to 2–5 ms.

**Why it helps:** Eliminates up to 20–25 ms of dead air per refill cycle. Combined with
larger thresholds this matters less (cycles are less frequent) but still removes a tail
latency spike.

**Expected impact:** Small in isolation (5–15 %); compounds with #1.

**Risk of making things worse:**
- If `bufferedamountlow` event is delivered late (background tab), removing the poll
  entirely could stall the transfer indefinitely. Keep a long‑interval (100 ms+) safety
  poll, not a 25 ms one.

### #4 — Raise chunk size to 64 KB, **only if #1 is done first**

**Change:** `FILE_CHUNK_SIZE = 64 * 1024` on both clients, after `FILE_BUFFER_HIGH ≥
1 MB`. This keeps pipeline depth ~16+, same as the original 16 KB / 256 KB ratio.

**Why it helps:** Reduces iteration count 4× (and microtask boundaries, promise
allocations, `setInterval` churn). Lowers per‑message overhead in the SCTP layer too.
64 KB is below all known per‑message ceilings.

**Expected impact:** 5–15 % on web (mostly reduced main‑thread overhead, smoother UI),
modest on Android.

**Risk of making things worse:**
- **This is exactly the change that backfired before.** Do not ship it without raising
  the threshold *first* and verifying with measurements. If shipped together with the
  threshold change, depth is preserved.
- 64 KB chunks may approach Firefox's pre‑EOR limit on very old Firefox; modern
  Firefox is fine.
- Must verify `pc.sctp?.maxMessageSize >= 65536` defensively (in practice always true,
  but a one‑line check costs nothing).

### #5 — Stream the file with `Blob.stream().getReader()` (web)

**Change:** Replace the `slice().arrayBuffer()` loop with a ReadableStream reader.
Modern browsers stream from disk in their native chunk size and avoid repeated
`slice` IPC.

**Why it helps:** Cleaner code, avoids per‑call `slice` overhead, lets the browser pick
the natural I/O size. Works synergistically with #2.

**Expected impact:** Small, mostly code‑quality. 5–10 % on large files.

**Risk of making things worse:**
- Stream reader chunk sizes don't match `FILE_CHUNK_SIZE`. Need to coalesce/split to
  hit a wire chunk size that respects `maxMessageSize`. Adds a small buffer.
- More moving parts; not worth doing unless #1–#4 don't get us where we need to be.

### #6 — Use a `Worker` for the send loop (web only)

**Change:** Move `sendFileToPeer` into a dedicated Worker so chunk reads + scheduling
don't compete with UI / call rendering on the main thread. Transfer the DataChannel via
`transferControlToOffscreen`‑style API... except DataChannels are **not transferable**.
The realistic version is: do file I/O in a worker, postMessage chunks back, send from
main thread.

**Why it helps:** During an active call, the main thread does a lot (video render,
React/DOM updates, signaling). Offloading I/O removes contention.

**Expected impact:** Variable — depends on how busy the main thread is during a call.
On a 4‑peer call sending 50 MB, probably 10–20 %.

**Risk of making things worse:**
- Significant code change. Worker postMessage of ArrayBuffers can be made
  zero‑copy via transfer, but the channel send is still on main thread.
- Probably not worth it until #1–#4 are exhausted.

### #7 — Things that look tempting but **won't** help (or will hurt)

- **`ordered: false`** — silently corrupts files. Unusable.
- **`maxRetransmits` / `maxPacketLifeTime`** — these make the channel *unreliable* (data
  can be dropped). Files would arrive corrupted. Never do this.
- **Open multiple file DataChannels in parallel per peer** — they share one SCTP
  association and one congestion controller. No throughput gain, more complexity, worse
  fairness with other channels.
- **Compression on the wire** — most images (JPEG/PNG/HEIC) and videos are already
  compressed; CPU cost without benefit. Could help for plain‑text/CSV/log files but is
  out of scope.
- **Switching from SCTP to a custom UDP protocol** — months of work; existing TURN
  servers don't help; reliability and congestion control would have to be re‑built.

---

## 4. Concrete implementation plan

Phased so each step is independently measurable.

### Phase 0 — Add measurement (1 hour)

Before changing anything, instrument both clients to log per‑transfer:
- total bytes, wall time, achieved throughput (B/s)
- cumulative time spent in `await file.slice().arrayBuffer()`
- cumulative time spent in `waitForFileBuffer`
- count of `bufferedamountlow` events vs poll‑triggered resumes
- `bufferedAmount` sampled every 100 ms (min / max / avg)

This is throwaway diagnostic logging; gate behind a `?debugTransfer=1` URL param on web
and a build flag on Android. **Without these numbers we are guessing.** The expected
signature of the current bug is: `wait_time / total_time` high on WAN, `bufferedAmount`
frequently at 0.

### Phase 1 — Raise thresholds only (low risk, biggest win)

Web (`public/index.html` lines 884–886):
```js
const FILE_CHUNK_SIZE = 16 * 1024;        // unchanged
const FILE_BUFFER_HIGH = 1 * 1024 * 1024; // 1 MB (was 256 KB)
const FILE_BUFFER_LOW  = 256 * 1024;      // 256 KB (was 64 KB)
```

Android (`NativeCallActivity.kt` lines 2451–2452 in `app-native/`):
```kotlin
private const val FILE_CHUNK_SIZE = 16 * 1024
private const val FILE_BUFFER_HIGH = 1L * 1024L * 1024L
```

Update `dc.bufferedAmountLowThreshold = FILE_BUFFER_LOW` on web (already done at line
2343, will pick up the new constant).

**Verify** with Phase 0 logs that `bufferedAmount` no longer hits 0 on WAN.

### Phase 2 — Pipeline disk read with send (web only)

Refactor `sendFileToPeer` (lines 2595–2610) to prefetch the next chunk while the current
chunk is being awaited/sent. See pseudocode in §3 #2. Keep chunk size 16 KB at this
point. ~15 lines of change.

Android needs no equivalent: `file.bytes` is already a `ByteArray` in memory; the
`copyOfRange` is cheap. (Long term, Android should `mmap` or stream from disk for very
large files, but that's a separate task.)

### Phase 3 — Reduce poll interval / use event‑driven wakeup

Web: change `setInterval(..., 25)` to `setInterval(..., 100)` as a *safety net only*,
since `bufferedamountlow` reliably fires in foreground tabs. This trades worst‑case
background‑tab stall for foreground efficiency. If we want to be paranoid, keep 25 ms
but gate on `document.visibilityState`.

Android: replace `Thread.sleep(20)` with a `synchronized(lock) { lock.wait(50) }`
woken by a `DataChannel.Observer.onBufferedAmountChange` callback. The observer is
already attached for state changes in `NativeWebRtcClient.kt` — extend it. If wiring the
observer is non‑trivial, dropping the sleep to 5 ms is an acceptable compromise.

### Phase 4 — Raise chunk size to 64 KB (only after Phase 1 ships and is measured)

Both clients: `FILE_CHUNK_SIZE = 64 * 1024`. Verify `pc.sctp?.maxMessageSize >= 65536`
on web before raising. Re‑measure.

### Phase 5 — Optional, if still not fast enough

- Web: switch to `Blob.stream()` reader.
- Web: move file read off main thread via Worker.
- Both: investigate per‑peer transfer scheduling — currently we initiate parallel
  transfers to all peers (`broadcastFile`), which means each peer's transfer competes
  for the local uplink. Serializing per‑peer would not increase total throughput but
  would improve per‑recipient latency.

### Acceptance criteria

For a 10 MB file on TURN WAN (target ~100 Mb/s shared, expect ~50 Mb/s effective):
- **Today (baseline)**: estimate ~5–15 Mb/s observed (user reports "very slow"). Logs
  will confirm.
- **After Phase 1**: ≥ 25 Mb/s.
- **After Phase 1+2+3**: ≥ 40 Mb/s.
- **After Phase 1–4**: within 80 % of link capacity.

Do not regress LAN performance. Same‑subnet transfer of a 10 MB file should stay under
2 s.

### Rollback plan

Each phase is two constants or a localized refactor. Reverting is one commit each.
Critically, **Phase 4 must not be shipped without Phase 1** — that is the exact
configuration that caused the previous regression.

---

## 5. Summary

| Layer            | Today                        | Proposed (Phase 1–4)           |
|------------------|------------------------------|--------------------------------|
| Chunk size       | 16 KB                        | 64 KB                          |
| HIGH watermark   | 256 KB                       | **1 MB**                       |
| LOW watermark    | 64 KB                        | **256 KB**                     |
| Pipeline depth   | ~16 chunks                   | ~16 chunks (preserved)         |
| Disk I/O         | serial with send             | **prefetched**                 |
| Backpressure wait| 25 ms poll fallback          | event‑driven + 100 ms safety   |
| Per‑file iters   | 3 200 (50 MB)                | 800 (50 MB)                    |
| BDP coverage     | starves on WAN               | covers WAN BDP (~500 KB)       |

The single most important thing to remember: **`FILE_CHUNK_SIZE` and `FILE_BUFFER_HIGH`
are coupled**. Pipeline depth is roughly `HIGH / CHUNK`. Tune them together or not at
all. That is the lesson of the previous failed attempt, and it is the reason the right
fix is "raise the watermark" first, "raise the chunk size" second, and "do both
together" if you must do them in one commit.
