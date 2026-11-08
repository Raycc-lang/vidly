# 008 — TURN relay is the normal path, not a fallback

**Status:** accepted (2026-11-08)
**Supersedes:** the implicit assumption, inherited from the Saudi-era design, that
TURN exists only to rescue the occasional CGNAT peer and that calls are
"direct P2P with TURN as a last resort".

## Context

The app's real deployment is Ray in **mainland China** talking to a friend in the
**United States**, with the signaling server on a **Los Angeles** VPS.

Measured facts (2026-11-08, from the Ray-side host and the VPS):

| Path | RTT | Loss (60-packet ICMP) |
|---|---|---|
| Ray host → VPS (LA) | 143–156 ms | **3.3%** |
| Ray host → `turn.cloudflare.com` (141.101.90.1) | 196–207 ms | **20%** |
| Ray host → Cloudflare customer edge (172.67.x) | 245 ms | 20% |
| Ray host → 8.8.8.8 | 183 ms | 20% |
| Ray host → `www.stanford.edu` | 74 ms | 0% |
| VPS (LA) → Stanford (same metro) | 9 ms | — |

**Measurement caveat:** these are ICMP only. Every HTTP client on Ray's host
reports the VPS's own address as its egress IP, because a transparent proxy sits
on his **router** — a curl-derived "location" or "path" reading is an artefact,
not evidence. Ping is the only unobstructed probe here.

Two consequences follow:

1. **The Ray leg crosses the GFW.** It is not a neutral long link; UDP is
   deprioritised/dropped and the path is policed. The 10% ICMP loss on a plain
   `ping` to the VPS is the mildest expression of this.
2. **The tempting "both peers use Cloudflare TURN, ride the backbone" design
   does not pay off on this link.** Cloudflare's own FAQ is explicit that the
   backbone only helps *between two Cloudflare edges*, and cannot improve a leg
   whose bottleneck is the mainland-China exit — Cloudflare has no mainland PoP
   serving TURN. Measured on this link, Cloudflare's anycast TURN edge is **~55 ms
   farther** than the LA VPS (196–207 ms vs 143–156 ms) and **~6× lossier**
   (20% vs 3.3%). The earlier assumption that Cloudflare would be the faster
   option is simply wrong here.

So the relay is not an exceptional path here — it is the expected one. A direct
srflx↔srflx pair across the GFW will frequently fail to gather or fail to
survive; the LAN-style "P2P works fine" intuition does not transfer.

## Decision

**Keep Cloudflare Realtime TURN as the relay, and keep the LA VPS as the
signaling server only — do not turn the VPS into a coturn relay.**

Reasons, in order of weight:

1. **Loss, not distance, is the deciding factor — and neither option wins
   outright, so keep the one with no ops cost.** Both topologies traverse the
   same policed China exit: VPS-as-relay ≈ (VPS→friend) + (Ray→VPS ≈ 143 ms);
   Cloudflare ≈ (Ray→CF edge ≈ 200 ms) + (CF→friend). Latency is roughly a wash.
   The measured difference that matters is loss on Ray's leg, and there the
   *direct* Ray→VPS path (3.3%) beats Ray→CF (20%). That argues against
   *replacing* Cloudflare with the VPS as a latency play — but it does not argue
   for running coturn on the VPS either (reasons 2–4 below stand).
2. **Port reachability.** Cloudflare publishes `turn:80`, `turn:3478`,
   `turns:5349` and `turns:443`. On the VPS, TCP/443 is already taken by nginx
   (`voice.raycc.org`), so coturn could offer only 3478/5349 — precisely the
   ports most likely to be filtered on a policed leg.
3. **Shared fate.** The VPS is 2 vCPU / 1 GB with a `cake` shaper and currently
   runs signaling. Relaying ~800 kbps × 2 directions per call on it couples
   media and signaling failure domains on the weakest machine in the system.
4. **Cost is not a constraint.** Cloudflare gives 1000 GB/month free (SFU+TURN
   shared; $0.05/GB after). At 800 kbps relayed both ways that is >1300 hours
   of 1:1 relayed video per month. Two personal users will use a few percent
   of it.

## Trade-offs and failure modes

- **Adding a second TURN server is legitimate — but not on the LA VPS.** If
  Cloudflare's infrastructure range is ever blocked on Ray's leg, put coturn on
  a host near the *friend* (US East/West or a European anycast-ish provider),
  never on the same machine as signaling. Add both entries to `iceServers` and
  let ICE pick by RTT; do not hard-code a preference.
- **A relayed call roughly doubles relayed bytes** versus a direct pair, and
  doubles the latency penalty of the relay hop. That is the accepted cost of
  reliable media on this link.
- **`path` in telemetry is the ground truth.** `/call-report` samples carry
  `path` (`host/host`, `srflx/relay`, …), `proto` (`relay-udp`, `relay-tcp`,
  …) and `rttMs`. Before changing TURN topology, read a real report. Relay-side
  RTT on this link should land in the 200–250 ms band for a Cloudflare-relayed
  call; systematically higher means the relay path is worse than the ICMP
  baseline suggests, and a relay homed on the LA VPS (or nearer the friend)
  becomes worth measuring.

## Revisit if

- Ray moves, or the friend does, such that the China↔US leg stops being the
  bottleneck (e.g. both peers acquire low-loss paths to a common Cloudflare
  edge, or Ray starts using a non-censored egress).
- Cloudflare's free allowance or the TURN product itself changes materially.
- Telemetry shows relayed `rttMs` consistently far above the observed
  ~150–200 ms band, or heavy relay-side packet loss that the direct pair did
  not have — that would mean the relay itself is the problem, not the link,
  and a nearby alternative relay should be measured.
