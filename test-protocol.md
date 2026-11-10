# Vidly Manual Test Protocol

A hands-on protocol for verifying Vidly's critical paths with two Android devices. No prior knowledge of the app is assumed. Run the full protocol before any release that touches `NativeWebRtcClient.kt`, `NativeSignalingClient.kt`, or `server.js`; run the relevant scenario after any targeted fix. The [automated harness](#automated-harness--toolsnegotiation-repromjs) covers the web client + server negotiation paths and must pass before **every** release; run it first.

## What you need

- Two Android phones (call them **A** and **B**), both on Android 12+, with the debug APK installed:
  ```bash
  cd ~/video-call/android && ./gradlew :app-native:assembleDebug
  adb install -r app-native/build/outputs/apk/debug/app-native-debug.apk
  ```
- USB or Wi-Fi `adb` access to at least one device for log capture.
- SSH access to the VPS (`ssh vm`) for server-side scenarios.
- Wired headphones and a Bluetooth headset for scenario 6.
- Put the two devices on **different networks** unless a scenario says otherwise — same-LAN calls hide TURN and NAT bugs, and on this app's real deployment TURN is the normal path, not an exception (decisions/008). The realistic equivalent of production is **A on the mainland-China host / a Chinese mobile network, B on a US network**; when both devices are local, at least keep one on a different NAT (Wi-Fi vs. mobile data or a tethered hotspot).

## How to capture logs

On the device you're observing:

```bash
adb logcat -v time | grep -E "Vidly|libjingle|org.webrtc" > /tmp/vidly-test.log
```

Tags you will see: `VidlyScreen` (screen share), `VidlyAudio` (audio routing), `VidlyNego` (negotiation: ignored colliding offers, watchdog attempts `n/3` + ICE state, `offer abandoned`, rollback/SRD failures, `signal ... undeliverable`), plus the in-app **header status label**, which is a first-class observable in this protocol. Status strings and what they mean:

| Header status | Meaning |
|---|---|
| `Connecting...` / `Joining room...` | Signaling WebSocket connecting / joining |
| `Connected` | ICE reached CONNECTED for a peer |
| `Reconnecting...` | ICE DISCONNECTED (restart scheduled in 8s) or signaling WS reconnecting |
| `ICE failed` | ICE FAILED — immediate restart triggered |
| `Renegotiating...` | Stuck-offer watchdog fired (rollback + re-offer; at most 3 times, the 4th fire abandons silently — see `VidlyNego`) |
| `Paused - poor network` | No remote video frame for >5s (UI-only overlay, never restarts ICE) |

Server logs: `ssh vm`, then `journalctl -u <vidly service> -f` (the systemd unit running `server.js`; find it with `systemctl list-units --type=service | grep -iE 'vidly|voice|node'`).

## Timing constants you are testing against

| Constant | Value | Where |
|---|---|---|
| ICE DISCONNECTED → restart | 8 s | `NativeWebRtcClient.ICE_RESTART_DELAY_MS` |
| ICE FAILED → restart | immediate | `onIceConnectionChange` |
| Stuck-offer watchdog | 10 s | `OFFER_TIMEOUT_MS` |
| Watchdog re-offers before abandoning | 3 (4th fire rolls back only, ≈40 s after the first offer) | `OFFER_MAX_RETRIES` (web + Android) |
| Signaling reconnect backoff | 1 s doubling to 10 s max | `NativeSignalingClient` |
| WS keepalive pings | client 20 s / server 25 s | OkHttp `pingInterval` / `WS_PING_INTERVAL_MS` |
| Server rejoin grace period | 180 s | `server.js WS_REJOIN_GRACE_MS` |
| Frame-stall overlay | check 3 s, threshold 5 s | `NativeCallActivity` |
| File transfer | 64 KB chunks, 1 MB high-water, 30 s stall takeover | `NativeCallActivity` |

## Baseline setup (used by every scenario)

1. On both devices, open Vidly, enter the same room name (pick a fresh one, e.g. `test-<today>`) and distinct usernames.
2. Join on both. Wait for header status `Connected` on both.
3. Turn mic **on** on both; camera as the scenario requires.

A scenario "passes" only if **all** pass criteria hold and **no** fail criteria appear.

---

## Automated harness — `tools/negotiation-repro.mjs`

Headless reproduction of the web client's negotiation and the server's seat handling. Each scenario spawns its own `server.js` (free port 4100–4199, TURN unset — host/STUN candidates suffice on localhost; `/call-report` is blocked so nothing lands in `call-reports/`), drives real Chromium pages (one browser context per peer = separate clientId), and prints one `PASS|FAIL <scenario>: <numbers>` line. Exit code is non-zero on any FAIL. Runtime ≈ 4 min for the full set. **Must pass before a release.** It does not exercise the Android client.

```bash
node tools/negotiation-repro.mjs                        # all 7 scenarios, setup n=10
node tools/negotiation-repro.mjs --only setup --n 20    # one or more scenarios, comma-separated
node tools/negotiation-repro.mjs --server-dir ../old-checkout   # show a bug reproduces on old code
node tools/negotiation-repro.mjs --verbose              # dump page events on FAIL
```

**Requirements:** `playwright-core` (from `$PLAYWRIGHT_CORE`, default `/home/ray/.bun/install/global/node_modules/playwright-core`, else `require('playwright-core')`); a Chromium headless shell (`$CHROME_EXE`, default the newest `~/.cache/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-linux64/chrome-headless-shell`); `ws` installed in the server dir's `node_modules`. Any page `pageerror` fails the scenario.

| Scenario | Proves | Pass |
|---|---|---|
| `setup` | Answer-pending guard (decisions/004): the polite peer's follow-up offer is not ignored while the joiner's SRD(answer) is pending. N trials of A joins, B joins 1.5 s later, observe ~13 s. | **0/N** trials with any `offer-timeout`, and every trial ends connected + stable on both sides. `offer-ignored events=0` is expected since the offer hold (004, 2026-11-10). |
| `ghost-join` | A grace-period seat isn't offered to a joiner (decisions/009). A joins, closes; C joins within grace. | C holds no peer for A and logs 0 `offer-timeout` over 15 s. |
| `name-reuse` | A ghost doesn't hold its username (009). Ray leaves; a **new** context (new clientId) named Ray joins within grace. | New Ray gets `joined` (no `username-taken`) and connects to Friend. |
| `resume` | Symmetric rebuild on both rejoin paths (decisions/001). Phase 1: Ray's page reloads (Chromium: clean `leave` + new peerId). Phase 2: Ray's WebSocket is closed without `leave` (resume: same peerId, `joined.resumed=true`). | Each phase: connected + stable again within ~15 s on a PC Friend didn't have before (or a `peer-joined`/`peer-resumed`); phase 2 additionally keeps the peerId and is a resumed join. |
| `watchdog-cap` | Bounded watchdog (decisions/003). B drops every answer; A offers. | A's `offer-timeout` events carry `n` and `ice`, stop at n = 4 with `offer-abandoned`, and no further `offer-timeout` for ~15 s (~55–60 s total). |
| `join-during-share` | Offer hold (decisions/004, 2026-11-10). Ray shares with the mic off (fake `getDisplayMedia`: canvas + oscillator); Maya joins, then reloads. | Maya decodes video frames and receives audio after the join **and** after the reload; Ray logs 0 `negotiate-failed`. |
| `undeliverable` | Negative ack (009). A offers to a fake peer id. | A gets `signal-undeliverable`, drops the fake peer within a few seconds, and the real peer stays connected. |

**`setup` is a rate, not a yes/no.** The bug it guards is a race: the old code stalled in 5/8, 12/12 and 4/8 trials on different runs, so a small N under-samples it. `--n` has a minimum of 8; only **0/N** passes. If you are validating a negotiation change, use `--n 20` or more.

---

## Scenario 1 — ICE restart on network loss

**Tests:** the single observer-based ICE restart path (decisions/002).

**Setup:** Baseline call, camera on both sides, A on a different network from B (mobile data or the China-side host — see above).

**Steps:**
1. On A, enable airplane mode.
2. Wait 10 seconds.
3. Disable airplane mode. Start a stopwatch.

**Pass:**
- Within ~10s of the cut, both devices show `Reconnecting...`.
- After the network returns: audio and video resume on both devices **within 30 s** of airplane-off, header returns to `Connected`.
- Only **one** recovery sequence happens (one `Renegotiating...`/offer exchange, not a storm).

**Fail:**
- Either device stuck on `Reconnecting...` / `ICE failed` for >60 s after network restore.
- Audio resumes but video never does (or vice versa).
- Repeated restart loops (status flapping `Connected` ↔ `Reconnecting...` more than twice).

**Logs:** on A, expect `IceConnectionState: DISCONNECTED` then (≥8 s later, if not recovered) an ICE restart offer; after restore, `CONNECTED`. There must be **no** ICE restart triggered by the frame-health check — if you see a restart while status was `Connected`, that's a regression of decisions/002.

---

## Scenario 2 — Signaling server death and restart

**Tests:** WS disconnect detection, reconnect backoff, symmetric PC rebuild (decisions/001).

**Setup:** Baseline call, camera on.

**Steps:**
1. `ssh vm` and stop the service: `sudo systemctl stop <vidly service>`.
2. Observe both devices for ~40 s. **Expected:** media (audio/video) keeps flowing — the P2P path doesn't depend on signaling.
3. Both devices should show `Reconnecting soon...` / `Signaling failed...` within ~45 s (ping timeout).
4. `sudo systemctl start <vidly service>`.

**Pass:**
- Media continues during the outage (P2P is independent of signaling).
- After server restart, both clients reconnect automatically (≤10 s backoff), rejoin, and — because a fresh server has no room state — perform a **full rebuild**: both sides get a fresh `joined`, tear down old PeerConnections, renegotiate, and return to `Connected` with working audio+video within 30 s of server start.
- Chat DataChannel works after recovery (send a test message both ways).

**Fail:**
- Clients never notice the outage (still "Connected" status on the signaling layer after 60 s+).
- After restart, one side shows `Connected` but the other doesn't (asymmetric rebuild — the exact bug decisions/001 fixed).
- Zombie peer: participant list shows the same user twice.

**Logs:** server journal shows both `join` messages after restart with `resumed: false`.

---

## Scenario 3 — Extended outage past the 3-minute grace period

**Tests:** server grace expiry and full re-join (`WS_REJOIN_GRACE_MS = 180s`).

**Setup:** Baseline call, camera on, A on a different network from B (mobile data or the China-side host).

**Steps:**
1. Enable airplane mode on A. Start a stopwatch.
2. On B, confirm A is announced as left (participant list shrinks; `peer-left` is sent the moment A's socket drops or times out).
3. Wait a full **4 minutes**.
4. Disable airplane mode on A.

**Pass:**
- A's client auto-reconnects and rejoins as a **new** peer (server journal: `resumed: false` — its grace slot expired).
- B receives `peer-joined`, rebuilds, and the call re-establishes: `Connected` + audio/video on both within 30 s of A's network restore.
- No duplicate of A in B's participant list.

**Fail:**
- A reconnects but negotiation never completes (stuck `Reconnecting...` >60 s).
- B still holds the dead session (frozen video of A forever, no rebuild).

**Logs:** on B, `peer-left` for A during the outage, then `peer-joined` (not `peer-resumed`) after 4 min.

---

## Scenario 4 — Camera on → off → on

**Tests:** track pause/resume without renegotiation or PC churn; `onFirstPacketReceived` unmute recovery (decisions/007).

**Setup:** Baseline call, camera **on** both sides. Open the chat on both devices.

**Steps:**
1. On A, toggle camera **off**. On B, verify A's video tile stops/clears within ~2 s.
2. Wait 10 s. Send a chat message A→B (proves the DataChannel — and therefore the same PeerConnection — is still alive).
3. On A, toggle camera **on**.

**Pass:**
- B sees A's video resume within 5 s of step 3.
- Chat messages deliver instantly throughout — this is the observable proof that **no new PeerConnection was created** (a rebuild would drop and reopen the DataChannel).
- Header status stays `Connected` the whole time; no `Renegotiating...`.

**Fail:**
- B never sees video resume (the classic muted-track bug: no `onTrack` refire, `onFirstPacketReceived` missed).
- Status flaps or chat pauses around the toggle (indicates renegotiation/rebuild that shouldn't happen).
- A's video resumes into the wrong tile on B (role mis-binding).

**Logs:** no `createOffer`/SDP exchange should appear for the off→on toggle itself.

---

## Scenario 5 — Screen share × camera combinations

**Tests:** track role classification (decisions/007), `VidlyScreen` pipeline.

**Setup:** Baseline call. B is the observer; A performs all toggles. Repeat the matrix with roles swapped if time allows.

**Steps & pass criteria** (check B's display after each step; screen belongs in the **main view**, camera in the **small PiP tile**):

| # | A does | B must see |
|---|---|---|
| 1 | Screen share on (camera off) | A's screen in main view — **not** in the PiP tile |
| 2 | Screen off | Main view clears |
| 3 | Camera on (no screen) | A's camera in PiP tile |
| 4 | Screen on (camera still on) | Screen in main view **and** camera still in PiP |
| 5 | Camera off (screen still on) | Camera tile clears; screen unaffected |
| 6 | Camera on again | Both again, correct roles |
| 7 | Screen off (camera on) | Screen clears; camera stays |

Also verify: while A shares a **static** screen (no movement), B must **not** get the `Paused - poor network` overlay (screen shares are exempt from the stall check).

**Fail:**
- Any step renders a track in the wrong slot (especially step 1 — the screen-in-PiP bug is the regression this guards).
- Step 4/5: toggling one track kills the other.
- A's side: stopping share via the **system** "stop sharing" notification (instead of the in-app button) leaves the in-app button in the wrong state.

**Logs:** `VidlyScreen` lines: `ensureScreenTrack: capture size WxH`, `startCapture OK`, `added screenSender to peer ...`. On revocation: `MediaProjection.Callback.onStop`.

---

## Scenario 6 — Audio routing

**Tests:** `onWebRtcAudioTrackStart`-driven routing, hot-plug auto-switch (decisions/006).

**Setup:** Voice call (camera off), no headphones connected. B just talks continuously (or plays music near the mic).

**Steps on A:**
1. Join the call. **Pass:** audio starts on the **speaker** (🔈 icon), from the very first second — not after a delay, not from the earpiece.
2. Tap the audio route button. **Pass:** audio moves to the earpiece (📞), immediately.
3. Plug in wired headphones. **Pass:** audio auto-switches to headphones (🎧); the route button becomes disabled (external routes aren't toggleable).
4. Unplug. **Pass:** audio falls back to speaker/earpiece and the toggle re-enables.
5. Connect a Bluetooth headset. **Pass (Android 12+):** audio moves to the BT headset within a few seconds, mic included.
6. Disconnect BT. **Pass:** falls back again.

**Fail:**
- Audio plays from the wrong device at call start (the race the `onAudioTrackStart` hook eliminated — if this reappears, check `VidlyAudio` for `setPreferredOutputDevice failed`, which means the reflection broke on a WebRTC upgrade).
- Icon updates but the actual sound doesn't move (the setPreferredDevice/setCommunicationDevice ordering bug).
- **Known limitation:** on Android < 12, Bluetooth routing is expected to misbehave (no SCO handling) — not a regression.

---

## Scenario 7 — Two-device cold start to stable call

**Tests:** the happy path end-to-end: TURN fetch, join, SDP/ICE, first media.

**Setup:** Fresh app start on both devices (swipe away from recents first). A fresh room name.

**Steps:**
1. Open the app on both devices, enter the room name and usernames, join A first, then B. Start a stopwatch at B's join.
2. Turn on mic + camera on both.

**Pass:**
- Both show `Connected` and each device shows the other's video **within 15 s** of B joining.
- Two-way audio confirmed by speaking.
- Repeat once with both devices on cellular (usually forces TURN): still connects within 15 s.
- No `Renegotiating...` during setup, and no `unanswered after` line under `VidlyNego` — a 10 s stall at call start is the answer-pending race (decisions/004), which the web harness can't catch on Android.

**Fail:**
- `ICE failed` on the cellular-only run → TURN credentials issue (Cloudflare; there is no coturn): verify `curl https://voice.raycc.org/turn-credentials` returns `{iceServers:[...]}` with `urls`, `username`, `credential` (503 = keys unset, 502 = Cloudflare error).
- Connects on Wi-Fi but not cross-network → NAT/TURN, same check.
- `room-full` or `username-taken` caused by a seat from a previous test that is still in its grace period → regression of decisions/009 (ghost seats must not count toward the cap or hold a name). Run the harness `ghost-join` / `name-reuse`.

---

## Scenario 8 — Beauty filter toggle during active call

**Tests:** per-generation GL state (decisions/005).

**Setup:** Baseline call, A's camera on, beauty filter available in A's UI.

**Steps:**
1. On A, toggle beauty **on**. Observe both A's local preview and B's received video.
2. Toggle intensity up/down if the UI exposes it.
3. Toggle beauty **off**.
4. Stress the GL-generation path: with beauty **on**, switch camera front→back→front, then toggle camera off→on.

**Pass:**
- Filter visibly applies/removes on both A's preview and B's view within ~1 s.
- No black frames, frozen frames, or green/garbage frames at any toggle or camera switch.
- Call stays `Connected` throughout; frame rate doesn't visibly collapse.
- Front camera: B sees A **un-mirrored** (text behind A readable); this must hold with beauty both on and off.

**Fail:**
- Video freezes permanently after a camera switch with beauty on (GL generation not rebuilt — the exact bug the per-generation refactor fixed).
- App crash (`eglMakeCurrent`/GL errors in logcat).

---

## Scenario 9 — File transfer over DataChannel

**Tests:** file channel, backpressure, cleanup.

**Setup:** Baseline call (camera state irrelevant).

**Steps:**
1. From A's chat, send a small text file (< 64 KB, i.e. single chunk).
2. From A, send a photo (2–10 MB, hundreds of chunks).
3. During the photo transfer, keep talking — audio must not degrade noticeably.
4. On B, open both received files.

**Pass:**
- Both files arrive complete and open correctly (photo not truncated/corrupt).
- Progress is visible and monotonic on both ends; the large file completes in a reasonable time for the link.
- A second transfer immediately after works (per-peer session freed by `file-complete`).

**Fail:**
- Transfer stalls forever with no error. (Note: a transfer stalled >30 s can be taken over by sending a **new** file offer from the same peer — verify this takeover works if you hit a stall.)
- Audio breaks up badly during the transfer (backpressure regression — high-water is 1 MB).
- After a mid-transfer `peer-left` (kill B's app), A's next transfer to a rejoined B is blocked (cleanup regression).

**Known limitation:** files are held fully in RAM on both ends — don't test with files in the hundreds of MB; that's a documented open issue, not a test failure.

---

## Scenario 10 — Stuck-offer watchdog

**Tests:** the 10 s rollback+re-offer and its 3-retry cap (decisions/003).

**Setup:** Baseline call, A on a lossy/different network, camera off on both. The trick is to make A **lose an offer into a silently-dead socket**: kill the network and immediately force a renegotiation, before OkHttp's ping (20 s) notices the socket is dead.

**Steps:**
1. On A: enable airplane mode and **within 1–2 s** toggle the camera **on** (camera on = `addTrack` → renegotiation → offer sent into the dead socket and lost).
2. After ~3 s, disable airplane mode. Start a stopwatch.
3. Watch A's header status.

**Pass:**
- Within **15 s** of the offer being lost, A shows `Renegotiating...` (the watchdog rolled back and re-offered), and the negotiation completes: B sees A's camera video and status returns to `Connected`.
- The recovery path may instead be a full signaling reconnect+rebuild (if OkHttp declared the socket dead first) — that also counts as a pass **as long as B ends up seeing A's video within 30 s**. The fail condition is the deadlock, not the specific recovery route.

**Fail:**
- A stuck permanently: status frozen, B's incoming offers ignored, camera never appears on B — the pre-fix `HAVE_LOCAL_OFFER` deadlock.
- `Renegotiating...` shows 3 times and B still never gets A's camera although the network is back (`VidlyNego`: `attempt 1/3` … then `offer abandoned`) → re-offers are being lost on a live path; signaling send path broken. More than 3 consecutive `Renegotiating...` for one offer is a regression of the cap.

**Logs:** on A, `VidlyNego` `offer to <peer> unanswered after 10000ms: attempt 1/3 ice=... signaling=HAVE_LOCAL_OFFER`, then a fresh `createOffer`. Timing between the lost offer and `Renegotiating...` should be ≈10 s. `watchdog rollback failed` lines mean the rollback itself is broken — investigate even if the call recovered.

---

## After the run

Record results as a checklist (scenario # → pass/fail + notes), attach `/tmp/vidly-test.log` for any failure, and file the failing scenario number against the relevant decision record in `decisions/` — each scenario names the decision it guards.
