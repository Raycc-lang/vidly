# The Carrier That Only Speaks Cloudflare

A debugging story about trying to get a WebRTC calling app working behind a
mobile carrier that bills international data aggressively — and what I
learned, and got wrong, about how it classifies traffic.

## The setup

I run a small P2P voice/video app called **Vidly**. The use case is personal:
voice calls with a friend abroad on a weak mobile network. Architecture is
boring-and-cheap on purpose:

- **Signaling**: Cloudflare Workers + a Durable Object (`RoomDO`) keyed by
  room ID. Global single-instance state, so two people on different
  continents land in the same room.
- **Media**: browser WebRTC, with Cloudflare Calls TURN
  (`turn.cloudflare.com`, free 1 TB/month tier) as the relay when NAT
  traversal is needed.
- **TURN credentials**: minted on demand by the Worker via the Cloudflare
  Calls API, served at `/turn-credentials`.

My friend — in Saudi Arabia — couldn't connect. The app sat on
"Joining room…" forever. What followed was two days of detective work that
turned into a case study in carrier traffic classification — and in how
easy it is to convince yourself of the wrong mechanism.

## Phase 1 — Getting her into the room

The first red herring was a half-finished refactor in the Worker that called
a function which didn't exist. Fixed that. Still stuck.

The Android app used OkHttp for signaling, with a 30-second `callTimeout`.
Her logs showed "callback never fired" — but only because nobody had waited
30 seconds. Once we did, it fired: `InterruptedIOException: timeout`. The
request never reached the Worker at all (`wrangler tail` was silent).

A confusing clue: she could open the app's web client in Chrome mobile just
fine, and Chrome reached the Worker. The Android app (Cronet) could not.
Same destination, same DNS, same TLS — different network stack. I latched
onto **ECH (Encrypted Client Hello)** as the explanation: Chrome hides the
SNI; Cronet and OkHttp don't. **That was my first wrong lead** — the split
was hopelessly confounded by app bugs, QUIC/UDP transport differences, and
the international-data quota state at the time. I'll come back to why ECH
wasn't actually the answer.

The pragmatic move was to stop fighting the carrier on WebSocket and rewrite
the web client's signaling from WebSocket to **HTTP long-polling** (join /
poll / send / leave endpoints, ~400 ms poll interval, generation-tagged to
drop stale responses). Deployed on the Worker. This worked — over plain
HTTPS to the Cloudflare edge, she could join a room.

Then a new bug: we could both join the *same* room and not see each other.
Root cause: the Worker's in-memory `rooms` Map is per-PoP. She egresses via
**MRS** (Marseille); I egress via **HKG** (Hong Kong). Different Worker
instances, different room state. Fix: route the HTTP signaling through the
Durable Object (global, keyed by roomId) instead of Worker memory. After
that — we could finally see each other in the participant list.

Progress. But...

## Phase 2 — Silence

We could see each other, but there was **no audio and no text chat**.

Chat runs over a WebRTC DataChannel; audio runs over SRTP. Both depend on
ICE/DTLS coming up. If both are dead, ICE never connected.

I added a tiny on-screen debug overlay to the web client — left corner,
green monospace — that classifies every gathered ICE candidate as
`host` / `srflx` (STUN) / `relay` (TURN) and shows the connection state.
The output was damning:

```
TURN:on peers:1
b00ab5 h3 s0 r0 g:gathering ice:disconnected pc:failed
```

- `TURN:on` — credentials loaded fine.
- `s0` — **zero** STUN (srflx) candidates. UDP to `stun.cloudflare.com`
  returned nothing.
- `r0` — **zero** TURN (relay) candidates. Every TURN URL in the list —
  including `turns:turn.cloudflare.com:443?transport=tcp` — failed.
- Only `host` candidates, all in `100.64.0.0/10`. That's **CGNAT**. She has
  no public IP. Direct P2P is impossible; TURN is *mandatory*. And TURN is
  dead.

So signaling works (it's just HTTPS to Cloudflare), but the WebRTC media
path is completely blocked.

## Phase 3 — It's the quota, not a block (and not SNI)

Here's where I went wrong for a while. She could reach:

- `raycc.org` (my blog, Cloudflare-proxied) — ✅
- `signal.raycc.org` (the Worker) — ✅

She could **not** reach:

- `voice.raycc.org` (my VPS, DNS-only, direct nginx) — ❌
- `turn.cloudflare.com` — ❌
- `stun.cloudflare.com` — ❌
- Cloudflare **WARP** — wouldn't connect.

Resolving the hostnames revealed the pattern:

| Host | IP | Reachable? |
|---|---|---|
| `raycc.org` / `signal.raycc.org` | 104.21.x / 172.67.x (CF customer edge) | ✅ |
| `voice.raycc.org` | 95.169.1.13 (my VPS, datacenter) | ❌ |
| `turn.cloudflare.com` | 141.101.90.1 (CF infrastructure) | ❌ |
| `stun.cloudflare.com` | 162.159.207.0 (CF infrastructure) | ❌ |

My first theory: the carrier filters by **SNI**, and ECH is the bypass.
Cloudflare customer domains have ECH, so Chrome hides the SNI and the
carrier can't blocklist it; `turn.cloudflare.com` has no ECH, so its SNI is
visible and gets dropped.

**That theory was wrong**, and my friend corrected it with one fact: *the
VPS worked fine until her monthly international-data quota ran out.* This
was never a hard block — it was **billing**. The carrier meters
international data and drops it when the quota is exhausted. Pay for more,
and international destinations work again.

So the real mechanism is **IP-based, with a whitelist plus international
quota**:

- A set of IP ranges is quota-exempt (zero-rated / always allowed).
  Cloudflare's **customer-edge anycast ranges** (`104.16.0.0/13`,
  `172.67.0.0/16`) — the IPs that serve normal proxied websites — are on
  it.
- Everything else is **international, quota-counted**: Cloudflare's own
  *infrastructure* ranges (`141.101.0.0/16`, `162.159.0.0/16` — used for
  `1.1.1.1`, TURN, WARP), all datacenter IPs (my VPS, AWS, Hetzner, DO),
  and other CDNs. When the quota is exhausted, packets to these get
  dropped.

That cleanly explains why `turn.cloudflare.com` fails even though it's
"Cloudflare" — it lives on the infrastructure range, not the customer-edge
range, so it's billed as international and dropped when the quota is gone.
Same fate as my VPS.

The tell that ruled out SNI: **my own domains work.** `raycc.org` and
`signal.raycc.org` are *my* personal domains — no carrier would put them on
a per-hostname allowlist. They pass only because they resolve onto
Cloudflare's whitelisted customer-edge IPs. If the classification were by
SNI/hostname, my domains would never be allowed. So it has to be by IP
range. ECH was incidental, not causal.

The business logic is clear once you see it: zero-rating Cloudflare's
customer edge means a huge fraction of the web keeps working for customers
even when their international quota is shot — happy customers, less churn.
But deliberately *excluding* Cloudflare's infrastructure ranges keeps the
bypass tools (`1.1.1.1` DNS, TURN, WARP VPN) quota-gated. "Let them browse
via Cloudflare; don't let them use Cloudflare to bypass us."

## Phase 4 — But then why is WebSocket blocked?

This is the puzzle that broke the IP-only model. If it's just IP whitelist
plus quota, then HTTP and WebSocket to the *same* whitelisted IP
(`signal.raycc.org` → 104.x) should behave identically — both are TLS on
443 to a whitelisted IP. HTTP works; WebSocket times out. The IP model
can't explain that.

First I had to rule out a server-side bug. I tested the WebSocket endpoint
from my own (unfiltered) network:

```
$ curl --http1.1 -H "Connection: Upgrade" -H "Upgrade: websocket" \
       -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: ..." \
       https://signal.raycc.org/signal?roomId=wstest

HTTP/1.1 101 Switching Protocols
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=
~B{"type":"welcome","peerId":"d77e7fa7-..."}
```

`101 Switching Protocols` and the server immediately pushed the welcome
frame. The endpoint works. So her `wss://` timeout is the carrier, not the
server.

Which means the carrier runs a **second filter on top of the IP whitelist**,
and it blocks WebSocket even to whitelisted IPs. The hard part is *how*: a
`wss://` connection is TLS end-to-end, the carrier can't read the
`Upgrade: websocket` header, and it can't MITM a Cloudflare customer domain
(no carrier CA on her phone — her HTTPS works without "connection not
private" errors). With the content invisible, the only thing left to
classify on is **what's visible on the wire**: packet sizes, timing,
direction, connection lifetime. Encrypted traffic classification.

- **HTTP request-response**: repeated short cycles — TCP/TLS, request,
  response, close (or reuse), repeat. Looks like ordinary browsing.
- **WebSocket**: one long-lived connection with frequent bidirectional
  small frames. A distinctive shape.

The carrier allows the HTTP shape and drops the long-lived bidirectional
shape. Same logic by which carriers fingerprint and block VPN/Tor tunnels
without decrypting them.

This is also why the HTTP-polling signaling works: it impersonates ordinary
web browsing. And it carries a warning for anyone trying the obvious
fallback — a long-lived **streaming GET** (the low-latency variant of an
HTTP media relay) might trip the same classifier, because it looks too much
like a WebSocket. Short-cycle **long-poll** is the shape that stays under
the radar.

## Phase 5 — The diagnostic

Tired of guessing, I built a one-page diagnostic that runs entirely from
*her* phone's browser and auto-reports back to a Durable Object I can read.
Hosted at `signal.raycc.org/diag` — on the one edge that works. It tests,
in parallel:

1. **HTTP reachability** to a panel of CDNs, datacenters, and RTC providers.
2. **ICE candidate gathering** against Google STUN, Cloudflare STUN,
   Cloudflare TURN, and a free public TURN — classifying host/srflx/relay.
3. **WebSocket** reachability.

The results (run while her international quota was exhausted):

- **HTTP**: Cloudflare edge (245 ms) and `cloudflare.com` (692 ms) — the
  *only* reachable targets. AWS CloudFront, Fastly, Akamai, Bunny CDN,
  Google, Hetzner, DigitalOcean, Agora, LiveKit — **all** timed out at 6 s.
- **ICE**: every config returned exactly one candidate, `100.75.208.225`
  (`typ host`) — the CGNAT address. `srflx:0`, `relay:0` across the board.
  No UDP out, no TURN allocation.
- **WebSocket**: timeout.
- Egress: `colo=MRS`, `country=SA`, `asn=35753`, `ip=82.167.111.1`.

With the quota model in hand, the HTTP results read differently than my
first panicked read: it wasn't "everything but Cloudflare is hard-blocked."
It was "Cloudflare's customer edge is quota-exempt, so it works despite
exhaustion; everything else is quota-counted, so it's dropped until she
pays." The ICE and WebSocket results are the separate filters — UDP and the
WebSocket traffic shape — layered on top.

## The verdict

The clever idea — ride Cloudflare's edge network on *local* data so the
call doesn't burn her international quota — doesn't work, for two
independent reasons:

1. **TURN relays don't live on the exempt ranges.** WebRTC behind CGNAT
   *needs* a TURN relay, and every reachable TURN option
   (`turn.cloudflare.com`, a self-hosted coturn on a VPS, a third-party RTC
   provider) sits on quota-counted international IPs. The exemption only
   covers Cloudflare's customer-edge web traffic, not its TURN
   infrastructure.
2. **WebSocket is pattern-blocked**, so even a non-WebRTC HTTP relay has to
   stick to short-cycle long-poll — and at ~300–400 ms one-way (her ~120 ms
   to MRS + my ~150 ms HKG→MRS + encode/playout), that's walkie-talkie
   territory, plus you lose WebRTC's free AEC.

Not worth building. The actual fix is boring: she tops up international
data, and the VPS + a coturn on `TURNS:443` (TCP, so UDP being blocked
doesn't matter) gives native WebRTC. The free-local-data shortcut doesn't
exist on this carrier.

She calls me on WhatsApp instead, which has local peering and rides the
local-data path for free. Sometimes the right answer is the boring one.

## Lessons

A few things I'm taking away:

- **Separate billing from blocking.** "International quota exhausted" looks
  exactly like "hard block" from outside (both are packet drop), but the
  fix is completely different — one is solved by paying, the other isn't.
  Always ask: *is this a meter or a filter?*
- **If your own domains pass, it's not SNI.** No carrier allowlists
  `raycc.org` by name. Personal domains passing means the classification is
  by IP range, not hostname. I wasted half a day on an ECH theory that the
  "my domains work" fact already ruled out.
- **CGNAT (`100.64.0.0/10`) in the host candidate is the tell** that TURN
  is mandatory. If you see only `100.6x.x.x` host candidates and no
  `srflx`, stop debugging your signaling — the media path is the problem.
- **"HTTP works, WebSocket doesn't, same host, same TLS" means encrypted
  traffic classification.** Since `wss://` is opaque inside TLS and there's
  no MITM, the carrier can only be fingerprinting the traffic *shape*
  (long-lived bidirectional vs. short request-response). Verify the endpoint
  from an unfiltered network first — I almost misattributed a server bug to
  the carrier.
- **Durable Object state is global; Worker memory is per-PoP.** Two users
  in different countries can join the "same" in-memory room and never see
  each other. Route shared state through the DO.
- **A self-hosted diagnostic page that auto-reports to a Durable Object is
  worth its weight in gold** when the user is non-technical and on another
  continent. No screenshots, no "what does the console say" — just a URL
  and a view endpoint.

The app still doesn't run for her on the free path. But the failure is now
*understood* failure, which is a different thing entirely from mysterious
failure. That's the part worth writing down.
