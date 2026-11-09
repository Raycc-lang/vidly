#!/usr/bin/env node
// tools/negotiation-repro.mjs — headless reproduction harness for the web
// client's negotiation / signaling-seat behaviour (public/index.html +
// server.js). Each scenario spawns its own server.js, drives real Chromium
// pages against it, and prints exactly one line:
//
//     PASS|FAIL <scenario>: <measured numbers>
//
// Exit code is non-zero if any scenario FAILs (or the harness itself errors).
//
// Usage
//   node tools/negotiation-repro.mjs                       # all scenarios, n=10
//   node tools/negotiation-repro.mjs --only setup --n 20
//   node tools/negotiation-repro.mjs --only ghost-join,name-reuse
//   node tools/negotiation-repro.mjs --server-dir /path/to/other/checkout
//
// Flags
//   --only <a,b>       run only these scenarios (names below)
//   --n <N>            trials for `setup` (default 10, minimum 8)
//   --server-dir <d>   directory whose server.js (and public/) to run; default
//                      is the repo root (resolved relative to this script).
//                      Point it at an old checkout to show a bug reproduces.
//   --verbose          dump relevant page events on FAIL
//
// Requirements
//   - playwright-core: loaded from $PLAYWRIGHT_CORE (default
//     /home/ray/.bun/install/global/node_modules/playwright-core), falling back
//     to a plain require('playwright-core').
//   - A Chromium headless shell: $CHROME_EXE, default the newest
//     ~/.cache/ms-playwright/chromium_headless_shell-*/chrome-headless-shell-linux64/chrome-headless-shell
//     (executablePath is passed explicitly because the global playwright-core
//     expects a browser build that may not be the one installed).
//   - server.js dependencies (ws) installed in --server-dir/node_modules.
//   Servers listen on free ports in 4100–4199 with TURN unset (STUN/host
//   candidates only, which is enough on localhost) and are always killed on
//   exit, including on error and SIGINT. POST /call-report is route-aborted so
//   nothing is written to call-reports/.
//
// Each peer is a separate browser context (separate localStorage, hence a
// separate clientId). Opening /room/<id> with localStorage['vidly-username']
// set auto-joins. The page's top-level const/let (peers, callEvents, myPeerId,
// OFFER_MAX_RETRIES) are readable from page.evaluate, and its top-level
// function declarations (sendSignal, negotiatePeer, ensurePeer) are window
// properties, which is how faults are injected below.
//
// Scenarios
//   setup         N trials: A joins, B joins 1.5s later, observe ~13s. Counts
//                 trials in which either side logged `offer-timeout` (the
//                 polite peer's follow-up offer was ignored as a "collision"
//                 while the joiner's setRemoteDescription(answer) was pending,
//                 so setup stalled until the 10s watchdog). PASS iff 0/N and
//                 every trial ends connected + stable on both sides.
//   ghost-join    A joins then closes its context (seat enters the server's
//                 rejoin grace period); C joins within grace. PASS iff C holds
//                 no peer for A and logs 0 `offer-timeout` over ~15s.
//   name-reuse    Friend in room; Ray joins and closes its context; a NEW
//                 context named Ray (different clientId) joins within grace.
//                 PASS iff it gets `joined` (no `username-taken`) and connects
//                 to Friend.
//   resume        decisions/001: Ray and Friend connected; Ray's page reloads
//                 (same context -> same clientId). PASS iff both sides end
//                 connected + stable within ~15s and Friend rebuilt its PC
//                 (new RTCPeerConnection object, or a sig-peer-joined /
//                 sig-peer-resumed event after the reload). Note a Chromium
//                 reload sends `leave` (beforeunload), so it's a clean leave +
//                 fresh join with a new peerId. A second phase therefore drops
//                 Ray's socket without `leave` (ws.close()) to exercise the real
//                 resume-by-clientId path: same peerId, joined.resumed=true,
//                 and Friend must rebuild again.
//   watchdog-cap  A and B connected; B drops every answer it sends; A makes an
//                 offer. PASS iff A's `offer-timeout` events carry n and ice,
//                 stop at n = OFFER_MAX_RETRIES + 1 with `offer-abandoned`, and
//                 no further `offer-timeout` follows for ~15s (~55-60s total).
//   undeliverable A and B connected; A creates a fake peer and offers to it.
//                 PASS iff A receives `signal-undeliverable`, the fake peer is
//                 removed within a few seconds, and the real peer stays
//                 connected.
//
// Every scenario also FAILs if any page throws an uncaught error (pageerror).

import { spawn, execFileSync } from 'node:child_process';
import { createRequire } from 'node:module';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..');
const ALL_SCENARIOS = ['setup', 'ghost-join', 'name-reuse', 'resume', 'watchdog-cap', 'undeliverable'];
// WS_REJOIN_GRACE_MS per scenario (undefined = server default, 180s). The
// ghost/name/resume scenarios must act well inside the grace period; set an
// entry (e.g. 5000) to also observe behaviour after grace expiry.
const SCENARIO_GRACE_MS = {
    'ghost-join': 180000,
    'name-reuse': 180000,
    resume: 180000,
};
const PORT_MIN = 4100;
const PORT_MAX = 4199;

// ─── args ──────────────────────────────────────────────────────────────────
function parseArgs(argv) {
    const opts = { only: null, n: 10, serverDir: REPO_ROOT, verbose: false };
    for (let i = 0; i < argv.length; i++) {
        const a = argv[i];
        const next = () => {
            if (i + 1 >= argv.length) throw new Error(`${a} needs a value`);
            return argv[++i];
        };
        if (a === '--only') opts.only = next().split(',').map(s => s.trim()).filter(Boolean);
        else if (a === '--n') opts.n = parseInt(next(), 10);
        else if (a === '--server-dir') opts.serverDir = path.resolve(next());
        else if (a === '--verbose') opts.verbose = true;
        else if (a === '-h' || a === '--help') {
            console.log('usage: negotiation-repro.mjs [--only a,b] [--n N] [--server-dir DIR] [--verbose]');
            console.log('scenarios: ' + ALL_SCENARIOS.join(', '));
            process.exit(0);
        } else throw new Error(`unknown argument: ${a}`);
    }
    if (!Number.isFinite(opts.n) || opts.n < 8) {
        console.warn(`--n ${opts.n} raised to the minimum of 8`);
        opts.n = 8;
    }
    if (opts.only) {
        const bad = opts.only.filter(s => !ALL_SCENARIOS.includes(s));
        if (bad.length) throw new Error(`unknown scenario(s): ${bad.join(', ')}`);
    }
    if (!fs.existsSync(path.join(opts.serverDir, 'server.js'))) {
        throw new Error(`no server.js in ${opts.serverDir}`);
    }
    return opts;
}

// ─── playwright / chromium ─────────────────────────────────────────────────
function loadPlaywright() {
    const p = process.env.PLAYWRIGHT_CORE || '/home/ray/.bun/install/global/node_modules/playwright-core';
    if (fs.existsSync(p)) return require(p);
    return require('playwright-core');
}

function findChrome() {
    if (process.env.CHROME_EXE) return process.env.CHROME_EXE;
    const base = path.join(os.homedir(), '.cache', 'ms-playwright');
    let dirs = [];
    try { dirs = fs.readdirSync(base).filter(d => /^chromium_headless_shell-\d+$/.test(d)); } catch {}
    dirs.sort((a, b) => parseInt(b.split('-')[1], 10) - parseInt(a.split('-')[1], 10));
    for (const d of dirs) {
        const exe = path.join(base, d, 'chrome-headless-shell-linux64', 'chrome-headless-shell');
        if (fs.existsSync(exe)) return exe;
    }
    throw new Error(`no chromium headless shell under ${base}; set CHROME_EXE`);
}

// ─── server management ─────────────────────────────────────────────────────
const servers = new Set();

function portFree(port) {
    return new Promise((resolve) => {
        const s = net.createServer();
        s.once('error', () => resolve(false));
        s.listen(port, '127.0.0.1', () => s.close(() => resolve(true)));
    });
}

async function freePort() {
    const start = PORT_MIN + (process.pid % (PORT_MAX - PORT_MIN + 1));
    for (let k = 0; k <= PORT_MAX - PORT_MIN; k++) {
        const port = PORT_MIN + ((start - PORT_MIN + k) % (PORT_MAX - PORT_MIN + 1));
        if (await portFree(port)) return port;
    }
    throw new Error('no free port in 4100-4199');
}

async function startServer(serverDir, { graceMs } = {}) {
    const port = await freePort();
    const env = { ...process.env, PORT: String(port), TURN_KEY_ID: '', TURN_KEY_SECRET: '' };
    if (graceMs != null) env.WS_REJOIN_GRACE_MS = String(graceMs);
    const child = spawn(process.execPath, [path.join(serverDir, 'server.js')], {
        cwd: serverDir, env, stdio: ['ignore', 'pipe', 'pipe'],
    });
    const srv = { child, port, base: `http://127.0.0.1:${port}`, log: '' };
    servers.add(srv);
    child.stdout.on('data', (d) => { srv.log += d; });
    child.stderr.on('data', (d) => { srv.log += d; });
    child.on('exit', () => servers.delete(srv));
    const deadline = Date.now() + 10000;
    while (Date.now() < deadline) {
        if (child.exitCode != null) throw new Error(`server exited early:\n${srv.log}`);
        try {
            const r = await fetch(srv.base + '/version');
            if (r.ok) return srv;
        } catch {}
        await sleep(100);
    }
    stopServer(srv);
    throw new Error(`server on :${port} did not come up:\n${srv.log}`);
}

function stopServer(srv) {
    if (!srv) return;
    try { srv.child.kill('SIGKILL'); } catch {}
    servers.delete(srv);
}

function killAllServers() {
    for (const srv of Array.from(servers)) {
        stopServer(srv);
        // Belt and braces: the child may be a shim that exec'd another binary.
        try { execFileSync('fuser', ['-k', `${srv.port}/tcp`], { stdio: 'ignore' }); } catch {}
    }
}

// ─── page helpers ──────────────────────────────────────────────────────────
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let roomSeq = 0;
const newRoomId = (tag) => `t${process.pid.toString(36)}${(roomSeq++).toString(36)}${tag.replace(/[^a-z0-9]/gi, '').slice(0, 8)}`;

class Run {
    constructor(browser, srv) {
        this.browser = browser;
        this.srv = srv;
        this.contexts = [];
        this.pageErrors = [];
    }
    async open(name, roomId) {
        const context = await this.browser.newContext();
        this.contexts.push(context);
        await context.addInitScript((n) => { try { localStorage.setItem('vidly-username', n); } catch {} }, name);
        await context.route('**/call-report', r => r.abort());
        const page = await context.newPage();
        // Signaling message types received, captured outside the page so they
        // survive the page reloading itself (e.g. username-taken -> landing).
        const frames = [];
        page.on('websocket', (sock) => sock.on('framereceived', (f) => {
            try { const m = JSON.parse(f.payload); if (m.type !== 'pong') frames.push(m.type); } catch {}
        }));
        page.on('pageerror', (err) => this.pageErrors.push(`${name}: ${err.message}`));
        const consoleErrors = [];
        page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 200)); });
        await page.goto(`${this.srv.base}/room/${roomId}`, { waitUntil: 'domcontentloaded' });
        return { name, context, page, frames, consoleErrors };
    }
    async close() {
        for (const c of this.contexts) { try { await c.close(); } catch {} }
        this.contexts = [];
    }
}

// Snapshot of the page's negotiation state.
function snap(client) {
    return client.page.evaluate(() => ({
        myPeerId,
        url: location.pathname,
        maxRetries: typeof OFFER_MAX_RETRIES === 'undefined' ? null : OFFER_MAX_RETRIES,
        peers: Array.from(peers, ([id, p]) => ({
            id, pc: p.pc.connectionState, sig: p.pc.signalingState, ice: p.pc.iceConnectionState,
        })),
        events: callEvents.filter(e => !['stats', 'visibility', 'wakelock'].includes(e.type)),
    }));
}

const evCount = (s, type) => s.events.filter(e => e.type === type).length;
const connectedTo = (s, id) => s.peers.some(p => (!id || p.id === id) && p.pc === 'connected' && p.sig === 'stable');

async function waitFor(fn, timeoutMs, intervalMs = 250) {
    const deadline = Date.now() + timeoutMs;
    for (;;) {
        const v = await fn();
        if (v) return v;
        if (Date.now() >= deadline) return null;
        await sleep(intervalMs);
    }
}

async function waitJoined(client, timeoutMs = 10000) {
    return waitFor(async () => {
        const s = await snap(client);
        return s.myPeerId && s.events.some(e => e.type === 'sig-joined') ? s : null;
    }, timeoutMs);
}

// Both sides hold exactly one peer, each other, connected + stable.
async function waitPair(a, b, timeoutMs) {
    return waitFor(async () => {
        const [sa, sb] = await Promise.all([snap(a), snap(b)]);
        if (sa.peers.length === 1 && sb.peers.length === 1 &&
            connectedTo(sa, sb.myPeerId) && connectedTo(sb, sa.myPeerId)) return [sa, sb];
        return null;
    }, timeoutMs);
}

async function pairDebug(...clients) {
    const out = [];
    for (const c of clients) {
        try {
            const s = await snap(c);
            out.push(`${c.name}${JSON.stringify(s.peers)}[${summarizeEvents(s)}] consoleErrors=${JSON.stringify(c.consoleErrors)}`);
        } catch (e) { out.push(`${c.name}: snap failed ${e.message}`); }
    }
    return out;
}

const summarizeEvents = (s) => s.events
    .filter(e => /^(sig-|offer-|ice-restart|pc-state|ws-)/.test(e.type))
    .map(e => { const { t, type, ...rest } = e; return `${type}${Object.keys(rest).length ? JSON.stringify(rest) : ''}`; })
    .join(' ');

// ─── scenarios ─────────────────────────────────────────────────────────────
// Each returns { pass, detail, debug? }.
const scenarios = {
    async setup(ctx) {
        let withTimeout = 0, notConnected = 0, ignored = 0;
        const ignoredBy = {}; // "<side>:<signalingState>/makingOffer=<bool>" -> count
        const debug = [];
        for (let i = 0; i < ctx.opts.n; i++) {
            const run = ctx.newRun();
            try {
                const room = newRoomId('setup');
                const a = await run.open('A', room);
                await sleep(1500);
                const b = await run.open('B', room);
                const joinedAt = Date.now();
                await sleep(Math.max(0, 13000 - (Date.now() - joinedAt)));
                const [sa, sb] = await Promise.all([snap(a), snap(b)]);
                const t = evCount(sa, 'offer-timeout') + evCount(sb, 'offer-timeout');
                for (const [side, st] of [['A', sa], ['B', sb]]) {
                    for (const e of st.events.filter(x => x.type === 'offer-ignored')) {
                        ignored++;
                        const k = `${side}:${e.sig}/makingOffer=${e.makingOffer}`;
                        ignoredBy[k] = (ignoredBy[k] || 0) + 1;
                    }
                }
                const ok = sa.peers.length === 1 && sb.peers.length === 1 &&
                    connectedTo(sa, sb.myPeerId) && connectedTo(sb, sa.myPeerId);
                if (t > 0) withTimeout++;
                if (!ok) notConnected++;
                if (t > 0 || !ok) debug.push(`trial ${i + 1}: A${JSON.stringify(sa.peers)}[${summarizeEvents(sa)}] consoleErrors=${JSON.stringify(a.consoleErrors)} B${JSON.stringify(sb.peers)}[${summarizeEvents(sb)}] consoleErrors=${JSON.stringify(b.consoleErrors)}`);
            } finally {
                ctx.pageErrors.push(...run.pageErrors);
                await run.close();
            }
        }
        const n = ctx.opts.n;
        return {
            pass: withTimeout === 0 && notConnected === 0,
            detail: `${withTimeout}/${n} trials had offer-timeout, ${n - notConnected}/${n} connected+stable, offer-ignored events=${ignored}${ignored ? ` (${Object.entries(ignoredBy).map(([k, v]) => `${k} x${v}`).join(', ')})` : ''}`,
            debug,
        };
    },

    async 'ghost-join'(ctx) {
        const run = ctx.newRun();
        try {
            const room = newRoomId('ghost');
            const a = await run.open('A', room);
            const sa = await waitJoined(a);
            if (!sa) return { pass: false, detail: 'A never joined' };
            const aId = sa.myPeerId;
            await a.context.close();
            await sleep(1000); // server sees the close -> A's seat is now in grace
            const c = await run.open('C', room);
            if (!(await waitJoined(c))) return { pass: false, detail: 'C never joined' };
            await sleep(15000);
            const sc = await snap(c);
            const holdsA = sc.peers.some(p => p.id === aId);
            const timeouts = evCount(sc, 'offer-timeout');
            return {
                pass: !holdsA && timeouts === 0,
                detail: `C holds ghost A=${holdsA}${holdsA ? ` (A ${JSON.stringify(sc.peers.find(p => p.id === aId))})` : ''}, offer-timeout=${timeouts} over 15s, undeliverable=${evCount(sc, 'sig-signal-undeliverable')}`,
                debug: [`C[${summarizeEvents(sc)}]`],
            };
        } finally {
            ctx.pageErrors.push(...run.pageErrors);
            await run.close();
        }
    },

    async 'name-reuse'(ctx) {
        const run = ctx.newRun();
        try {
            const room = newRoomId('name');
            const friend = await run.open('Friend', room);
            if (!(await waitJoined(friend))) return { pass: false, detail: 'Friend never joined' };
            const ray1 = await run.open('Ray', room);
            if (!(await waitPair(friend, ray1, 15000))) return { pass: false, detail: 'first Ray never connected to Friend', debug: await pairDebug(friend, ray1) };
            await ray1.context.close();
            await sleep(1000);
            const ray2 = await run.open('Ray', room); // new context = new clientId, same name
            await sleep(3000); // username-taken + 2s redirect would have happened by now
            const pair = await waitPair(friend, ray2, 12000);
            const s2 = await snap(ray2);
            const taken = ray2.frames.filter(t => t === 'username-taken').length;
            const joined = ray2.frames.filter(t => t === 'joined').length;
            return {
                pass: joined > 0 && taken === 0 && !!pair,
                detail: `new Ray: frames=[${ray2.frames.slice(0, 6).join(',')}] url=${s2.url} connected-to-Friend=${!!pair}`,
                debug: [`Ray2[${summarizeEvents(s2)}]`, `Friend[${summarizeEvents(await snap(friend))}]`],
            };
        } finally {
            ctx.pageErrors.push(...run.pageErrors);
            await run.close();
        }
    },

    async resume(ctx) {
        const run = ctx.newRun();
        try {
            const room = newRoomId('resume');
            const friend = await run.open('Friend', room);
            if (!(await waitJoined(friend))) return { pass: false, detail: 'Friend never joined' };
            const ray = await run.open('Ray', room);
            const before = await waitPair(friend, ray, 15000);
            if (!before) return { pass: false, detail: 'Ray and Friend never connected', debug: await pairDebug(friend, ray) };
            const rayId = before[1].myPeerId;

            // Mark Friend's current PC for Ray, perform `action`, then wait for
            // the pair to be connected + stable again on a PC object Friend
            // did not have before (i.e. it rebuilt, decisions/001).
            const phase = async (action) => {
                const prevId = (await snap(ray)).myPeerId;
                await friend.page.evaluate((id) => { window.__harnessOldPc = peers.get(id)?.pc || null; }, prevId);
                const at = Date.now();
                await action();
                const t0 = Date.now();
                const done = await waitFor(async () => {
                    const pair = await waitPair(friend, ray, 0);
                    if (!pair) return null;
                    const fresh = await friend.page.evaluate((id) => {
                        const p = peers.get(id);
                        return !!p && p.pc !== window.__harnessOldPc;
                    }, pair[1].myPeerId);
                    return fresh ? pair : null;
                }, 15000);
                const secs = ((Date.now() - t0) / 1000).toFixed(1);
                const [sf, sr] = await Promise.all([snap(friend), snap(ray)]);
                const newPc = await friend.page.evaluate((id) => {
                    const p = peers.get(id);
                    return !!p && p.pc !== window.__harnessOldPc;
                }, sr.myPeerId);
                const evs = sf.events.filter(e => e.t >= at && (e.type === 'sig-peer-joined' || e.type === 'sig-peer-resumed')).map(e => e.type);
                const resumedJoin = sr.events.some(e => e.t >= at && e.type === 'sig-joined' && e.resumed === true);
                return { ok: !!done, secs, newPc, evs, samePeerId: sr.myPeerId === prevId, resumedJoin, sf, sr };
            };

            // Phase 1 (the scenario as specified): page reload in the same
            // context, so the same clientId. The page's beforeunload sends
            // `leave`, so this is a clean leave + fresh join (new peerId).
            const r1 = await phase(() => ray.page.reload({ waitUntil: 'domcontentloaded' }));
            // Phase 2: signaling socket drop without `leave` -> the client
            // reconnects with the same clientId into its grace-period seat
            // (server resume path; same peerId, joined.resumed=true).
            const r2 = await phase(() => ray.page.evaluate(() => { ws.close(); }));

            const ok1 = r1.ok && (r1.newPc || r1.evs.length > 0);
            const ok2 = r2.ok && (r2.newPc || r2.evs.length > 0) && r2.samePeerId && r2.resumedJoin;
            const fmt = (r) => `connected+stable=${r.ok}${r.ok ? ` in ${r.secs}s` : ''}, Friend new PC=${r.newPc}, Friend events=[${r.evs.join(',')}], same peerId=${r.samePeerId}, joined.resumed=${r.resumedJoin}`;
            return {
                pass: ok1 && ok2,
                detail: `reload: ${fmt(r1)}; socket-drop: ${fmt(r2)}`,
                debug: [`Friend[${summarizeEvents(r2.sf)}]`, `Ray[${summarizeEvents(r2.sr)}]`],
            };
        } finally {
            ctx.pageErrors.push(...run.pageErrors);
            await run.close();
        }
    },

    async 'watchdog-cap'(ctx) {
        const run = ctx.newRun();
        try {
            const room = newRoomId('wdcap');
            const a = await run.open('A', room);
            if (!(await waitJoined(a))) return { pass: false, detail: 'A never joined' };
            const b = await run.open('B', room);
            const pair = await waitPair(a, b, 15000);
            if (!pair) return { pass: false, detail: 'A and B never connected', debug: await pairDebug(a, b) };
            const [sa0, sb0] = pair;
            await sleep(1000); // let any trailing negotiation settle
            await b.page.evaluate(() => {
                const orig = window.sendSignal;
                window.__harnessDroppedAnswers = 0;
                window.sendSignal = (to, payload) => {
                    if (payload && payload.description && payload.description.type === 'answer') {
                        window.__harnessDroppedAnswers++;
                        return;
                    }
                    return orig(to, payload);
                };
            });
            const startedAt = Date.now();
            await a.page.evaluate((id) => negotiatePeer(peers.get(id), 'harness'), sb0.myPeerId);
            const maxRetries = sa0.maxRetries;
            const since = (s, type) => s.events.filter(e => e.type === type && e.t >= startedAt);

            // Phase 1: wait for offer-abandoned (or a disqualifying observation).
            let abandonedAt = null, early = null;
            await waitFor(async () => {
                const s = await snap(a);
                const tos = since(s, 'offer-timeout');
                if (tos.some(e => typeof e.n !== 'number' || !('ice' in e))) { early = 'offer-timeout without n/ice'; return true; }
                if (maxRetries != null && tos.length > maxRetries + 1) { early = 'more timeouts than cap'; return true; }
                const ab = since(s, 'offer-abandoned');
                if (ab.length) { abandonedAt = ab[0].t; return true; }
                return false;
            }, 60000, 500);
            // Phase 2: ~15s of quiet after abandonment.
            if (abandonedAt) await sleep(15000);
            const s = await snap(a);
            const tos = since(s, 'offer-timeout');
            const ab = since(s, 'offer-abandoned');
            const ns = tos.map(e => e.n);
            const expected = maxRetries == null ? null : Array.from({ length: maxRetries + 1 }, (_, k) => k + 1);
            const after = abandonedAt ? tos.filter(e => e.t > abandonedAt).length : 0;
            const dropped = await b.page.evaluate(() => window.__harnessDroppedAnswers);
            const pass = !early && maxRetries != null && abandonedAt != null &&
                JSON.stringify(ns) === JSON.stringify(expected) &&
                tos.every(e => typeof e.ice === 'string') &&
                ab.length === 1 && ab[0].n === maxRetries + 1 && after === 0 &&
                s.peers.every(p => p.sig === 'stable');
            return {
                pass,
                detail: `OFFER_MAX_RETRIES=${maxRetries}, offer-timeout n=[${ns.join(',')}] ice=[${tos.map(e => e.ice).join(',')}], ` +
                    `offer-abandoned=${ab.length}${ab.length ? `(n=${ab[0].n}, at ${((abandonedAt - startedAt) / 1000).toFixed(1)}s)` : ''}, ` +
                    `timeouts after abandon=${after}, answers dropped by B=${dropped}, A sig=${s.peers.map(p => p.sig).join(',')}, ` +
                    `elapsed=${((Date.now() - startedAt) / 1000).toFixed(0)}s${early ? `, stopped early: ${early}` : ''}`,
                debug: [`A[${summarizeEvents(s)}]`],
            };
        } finally {
            ctx.pageErrors.push(...run.pageErrors);
            await run.close();
        }
    },

    async undeliverable(ctx) {
        const run = ctx.newRun();
        try {
            const room = newRoomId('undeliv');
            const a = await run.open('A', room);
            if (!(await waitJoined(a))) return { pass: false, detail: 'A never joined' };
            const b = await run.open('B', room);
            const pair = await waitPair(a, b, 15000);
            if (!pair) return { pass: false, detail: 'A and B never connected', debug: await pairDebug(a, b) };
            const bId = pair[1].myPeerId;
            const fakeId = '00000000-dead-beef-0000-000000000000';
            const t0 = Date.now();
            await a.page.evaluate((id) => negotiatePeer(ensurePeer(id, false, true, 'ghost'), 'harness'), fakeId);
            const removed = await waitFor(async () => {
                const s = await a.page.evaluate((id) => !peers.has(id), fakeId);
                return s;
            }, 5000, 100);
            const removedMs = Date.now() - t0;
            await sleep(1000);
            const s = await snap(a);
            const nack = s.events.filter(e => e.type === 'sig-signal-undeliverable' && e.t >= t0).length;
            const realOk = connectedTo(s, bId);
            return {
                pass: !!removed && nack > 0 && realOk,
                detail: `signal-undeliverable events=${nack}, fake peer removed=${!!removed}${removed ? ` in ${removedMs}ms` : ' (after 5s)'}, real peer connected+stable=${realOk}`,
                debug: [`A[${summarizeEvents(s)}]`],
            };
        } finally {
            ctx.pageErrors.push(...run.pageErrors);
            await run.close();
        }
    },
};

// ─── main ──────────────────────────────────────────────────────────────────
let browser = null;
let cleanedUp = false;
async function cleanup() {
    if (cleanedUp) return;
    cleanedUp = true;
    killAllServers();
    if (browser) { try { await browser.close(); } catch {} }
}
process.on('exit', () => killAllServers());
for (const sig of ['SIGINT', 'SIGTERM']) {
    process.on(sig, () => { cleanup().finally(() => process.exit(130)); });
}

async function main() {
    const opts = parseArgs(process.argv.slice(2));
    const { chromium } = loadPlaywright();
    browser = await chromium.launch({
        executablePath: findChrome(),
        headless: true,
        args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'],
    });
    const names = opts.only || ALL_SCENARIOS;
    console.log(`server-dir=${opts.serverDir} scenarios=${names.join(',')} n=${opts.n}`);
    let failures = 0;
    const t0 = Date.now();
    for (const name of names) {
        // Fresh server per scenario: clean room state and a per-scenario grace.
        const graceMs = SCENARIO_GRACE_MS[name];
        const srv = await startServer(opts.serverDir, { graceMs });
        const ctx = { opts, pageErrors: [], newRun: () => new Run(browser, srv) };
        let res;
        const st = Date.now();
        try {
            res = await scenarios[name](ctx);
        } catch (err) {
            res = { pass: false, detail: `harness error: ${err && err.stack || err}` };
        } finally {
            stopServer(srv);
        }
        if (ctx.pageErrors.length) {
            res.pass = false;
            res.detail += `; pageerrors=${ctx.pageErrors.length}: ${ctx.pageErrors.slice(0, 3).join(' | ')}`;
        }
        if (!res.pass) failures++;
        console.log(`${res.pass ? 'PASS' : 'FAIL'} ${name}: ${res.detail} [${((Date.now() - st) / 1000).toFixed(0)}s]`);
        if (!res.pass && opts.verbose && res.debug) for (const line of res.debug) console.log('    ' + line);
    }
    console.log(`done in ${((Date.now() - t0) / 1000).toFixed(0)}s, ${failures} failed`);
    return failures;
}

main().then(async (failures) => {
    await cleanup();
    process.exit(failures ? 1 : 0);
}, async (err) => {
    console.error(err && err.stack || err);
    await cleanup();
    process.exit(2);
});
