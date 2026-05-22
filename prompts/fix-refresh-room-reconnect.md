# Fix: Room refresh breaks peer connections (stale signaling state on existing peers)

## Problem

When a user refreshes the page (F5) during a call, the other participants cannot see their mic/cam status icons, cannot hear their audio, and the bidirectional media connection is silently broken.

### Root cause analysis

The issue is in the **server-side `resumed` reconnection path** (`server.js` L166-222):

When User A refreshes the page:
1. `beforeunload` fires → sends `leave` + closes WS (`index.html` L3210-3217)
2. The `leave` message may or may not reach the server before page tear-down
3. If `leave` doesn't arrive: server's `onclose` fires `scheduleLeaveRoom` with 180s grace
4. User A's fresh page reconnects with the same `clientId`
5. Server finds matching old WS → `resumed=true` (L166-183)
6. Server sends `joined { resumed: true }` to A (L208)
7. **Server does NOT send `peer-left` or `peer-joined` to existing peers** (L209-214: the broadcast is inside `if (!resumed) { ... }`)
8. User B keeps their OLD RTCPeerConnection to User A
9. User A creates a FRESH RTCPeerConnection (fresh page, empty `peers` Map)
10. User A sends SDP offer to B via signaling
11. User B processes the offer on the OLD PC → **renegotiation with m-line mismatch**

### Why renegotiation fails silently

User A's fresh page `ensurePeer()` (`index.html` L1646-1670) creates:
- `addTransceiver('audio', { direction: 'sendrecv' })` with **no track** (`localStream` is empty, `micEnabled=false`)
- **No video transceiver** (`camEnabled=false`)
- New DataChannels

User A's SDP offer has: `audio (recvonly)` + `DataChannel`. **No video m-line.**

User B's existing PC has both audio and video transceivers. Processing this offer through the old PC causes:
- Video transceiver potentially marked `inactive` or removed
- Audio direction mismatch
- Silent SDP processing failures in some cases
- DataChannel state confusion (old DC still open, new one negotiated)

Result: no bidirectional media, DataChannel may not open properly → mic/cam status icons never update.

## What should happen

After a page refresh and reconnect, ALL peers in the room should properly rebuild their WebRTC peer connections. The reconnecting peer creates new connections (already works on its side), and existing peers should GET RID of their stale connections and create fresh ones.

## Proposed fix

### Server-side (`server.js`)

In the `join` handler, when `resumed=true`, also send a notification to existing peers so they rebuild their peer connections. The cleanest approach: in the `resumed` path, send `peer-left` then `peer-joined` to all existing peers (same as a fresh join), while keeping the peerId preserved for signaling continuity.

Change the join handler at L208-214:
- **Before**: `peer-joined` is only sent when `!resumed`
- **After**: always send peer notification to existing peers (even when `resumed`), so they can rebuild the RTCPeerConnection

The key change: remove the `if (!resumed)` guard around the peer notification broadcast. When `resumed=true`, the server sends the same peer notification to existing peers that it sends for a fresh join. The existing peers can then close the stale connection and create a new one.

### Client-side (`public/index.html`)

**`handleSignaling` — add `peer-resumed` handler** (optional if we reuse `peer-joined`):

Actually, if the server sends `peer-left` + `peer-joined` for resumed joins, the existing `peer-left` and `peer-joined` handlers already handle it correctly:
- `peer-left` → `removePeer(peerId)` → closes PC, removes DOM
- `peer-joined` → `ensurePeer(peerId, polite=true, createDC=false)` → waits for offer

**`welcome` handler** — also clean up peers on WebSocket reconnect:
In the `welcome` handler (L1554-1558), add cleanup of existing peers before rejoining. This handles the case where the page DIDN'T refresh but the WebSocket reconnected (e.g., network hiccup), ensuring no stale peers accumulate:

```javascript
case 'welcome':
    myPeerId = msg.peerId;
    // Clean up stale peers from previous connection (WebSocket reconnection)
    for (const [id] of peers) removePeer(id);
    sendWs({ type: 'set-username', username: myUsername });
    sendWs({ type: 'join', roomId, username: myUsername, clientId: getClientId() });
    break;
```

## Files to modify

1. `server.js` — L206-214: peer notification when `resumed=true`
2. `public/index.html` — L1554-1558: welcome handler cleanup

## Constraints

1. Do NOT remove the `clientId` / `resumed` mechanism — it's needed for proper WS reconnect (preserves peerId for signaling)
2. The `resumed=true` response should still include `resumed: true` so the client knows it's a reconnection
3. Verify: after the fix, a user who refreshes their page should see the other peer's connection re-establish (connection dot goes from "connecting" → "connected", mic/cam status icons appear, audio works)
4. Verify: transient WS reconnect (without page refresh, within 180s) should still work — existing peers should be preserved if they reconnect quickly and DON'T refresh

## Verification

1. Open two browser tabs to the same Vidly room, both with mic/camera on
2. Verify audio flows both ways, status icons show mic/cam on
3. Refresh one tab (F5)
4. After reload:
   - [ ] The refreshed user sees the other peer's connection status dot turn green
   - [ ] The refreshed user sees the other peer's mic/cam status icons
   - [ ] Audio flows from the non-refreshed user to the refreshed user
   - [ ] When the refreshed user enables mic/camera, audio flows the other way
5. Bonus: test with screen sharing active before refresh