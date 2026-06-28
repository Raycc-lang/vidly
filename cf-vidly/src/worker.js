/**
 * Vidly Signaling Worker — main entry point.
 *
 * Routes:
 *   WebSocket /signal?roomId=<id>     → Room DO
 *   HTTP /api/signal/*?roomId=<id>    → Room DO
 *   GET  /turn-credentials            → Cloudflare Calls TURN creds
 *   GET  /version                     → App version info
 *   GET  /giphy?q=<query>             → Proxy to GIPHY API
 *   POST /crash                       → Crash report handler
 *   GET  /                            → PoP test page
 *   GET  /ping                        → Cloudflare PoP info
 *   GET  /test?size=N                 → Download test
 *
 * Secrets (set via `npx wrangler secret put <NAME>`):
 *   TURN_KEY_ID          — Cloudflare Calls TURN key ID
 *   TURN_KEY_API_TOKEN   — Cloudflare Calls TURN key API token
 *   GIPHY_API_KEY        — GIPHY API key (optional, has default)
 *   ANDROID_VERSION_CODE — Build number for /version endpoint
 *   ANDROID_VERSION_NAME — Version string for /version endpoint
 *   ANDROID_APK_URL      — Download URL for /version endpoint
 */

import { RoomDO } from './room.js';

const GIPHY_LIMIT = 20;
const GIPHY_TIMEOUT_MS = 8000;
const GIPHY_FALLBACK_KEY = 'z3JlLEdcXBGP0Bitbf3ut2XjlnKaV0xn';

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
  'Access-Control-Allow-Headers': '*',
};

// ─── TURN credential generation (needs env access) ─────────────────────

let turnCredsCache = null;
let turnCredsCacheExpiry = 0;

async function generateTurnCredentials(env) {
  const turnKeyId = env.TURN_KEY_ID;
  const turnKeyApiToken = env.TURN_KEY_API_TOKEN;
  const turnTtl = parseInt(env.TURN_CREDENTIAL_TTL || '43200', 10); // 12h default

  if (!turnKeyId || !turnKeyApiToken) {
    // No TURN key configured — return STUN-only (free + unlimited)
    return {
      iceServers: [
        { urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.cloudflare.com:53'] },
      ],
    };
  }

  const safetyMargin = 300_000; // 5 min buffer
  if (turnCredsCache && Date.now() < turnCredsCacheExpiry - safetyMargin) {
    return turnCredsCache;
  }

  try {
    const resp = await fetch(
      `https://rtc.live.cloudflare.com/v1/turn/keys/${turnKeyId}/credentials/generate-ice-servers`,
      {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${turnKeyApiToken}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ ttl: turnTtl }),
      }
    );

    if (!resp.ok) {
      console.error('TURN creds API error:', resp.status);
      throw new Error(`TURN API ${resp.status}`);
    }

    const result = await resp.json();
    turnCredsCache = result;
    turnCredsCacheExpiry = Date.now() + (turnTtl * 1000);
    return result;
  } catch (e) {
    console.error('Failed to generate TURN credentials:', e);
    if (turnCredsCache) return turnCredsCache;
    return {
      iceServers: [
        { urls: ['stun:stun.cloudflare.com:3478', 'stun:stun.cloudflare.com:53'] },
      ],
    };
  }
}

// ─── GIPHY proxy ───────────────────────────────────────────────────────

async function proxyGiphy(request, env) {
  const url = new URL(request.url);
  const query = url.searchParams.get('q') || '';
  const apiKey = env.GIPHY_API_KEY || GIPHY_FALLBACK_KEY;
  const isSearch = query.trim().length > 0;
  const giphyUrl = 'https://api.giphy.com/v1/gifs/' + (isSearch ? 'search' : 'trending')
    + '?api_key=' + encodeURIComponent(apiKey)
    + '&limit=' + GIPHY_LIMIT
    + (isSearch ? '&q=' + encodeURIComponent(query.trim()) : '');

  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), GIPHY_TIMEOUT_MS);
    const resp = await fetch(giphyUrl, { signal: controller.signal });
    clearTimeout(timeoutId);

    const body = await resp.text();
    return new Response(body, {
      status: resp.status || 502,
      headers: {
        'Content-Type': resp.headers.get('content-type') || 'application/json; charset=utf-8',
        'Cache-Control': 'no-store',
        ...corsHeaders,
      },
    });
  } catch (e) {
    return new Response(JSON.stringify({ error: 'giphy_unreachable' }), {
      status: 502,
      headers: { 'Content-Type': 'application/json; charset=utf-8', ...corsHeaders },
    });
  }
}

// ─── Handlers ──────────────────────────────────────────────────────────

function handleVersion(env) {
  return new Response(JSON.stringify({
    versionCode: parseInt(env.ANDROID_VERSION_CODE || '11', 10),
    versionName: env.ANDROID_VERSION_NAME || '2.0-cf',
    apkUrl: env.ANDROID_APK_URL || 'https://vidly-signal.iceui2016.workers.dev/vidly-native.apk',
  }), {
    status: 200,
    headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-cache' },
  });
}

async function handleCrashReport(request) {
  try {
    const body = await request.text();
    console.log('Crash report:', body.slice(0, 500));
    return new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
    });
  } catch {
    return new Response(JSON.stringify({ ok: false }), {
      status: 400,
      headers: { 'Content-Type': 'application/json; charset=utf-8' },
    });
  }
}

function handlePopTestPage() {
  return new Response(POP_TEST_PAGE_HTML, {
    status: 200,
    headers: { ...corsHeaders, 'Content-Type': 'text/html;charset=utf-8' },
  });
}

function handlePing(request) {
  const colo = request.cf?.colo || 'unknown';
  const country = request.cf?.country || 'unknown';
  return new Response(JSON.stringify({ status: 'ok', colo, country }), {
    status: 200,
    headers: { ...corsHeaders, 'Content-Type': 'application/json' },
  });
}

function handleTest(request) {
  const url = new URL(request.url);
  const MAX_BYTES = 52428800;
  const sizeParam = parseInt(url.searchParams.get('size'), 10);
  if (isNaN(sizeParam) || sizeParam < 1 || sizeParam > MAX_BYTES) {
    return new Response(JSON.stringify({ error: 'invalid size' }), {
      status: 400,
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
    });
  }
  const PAD = 'x';
  const chunkSize = Math.min(sizeParam, 65536);
  const chunk = PAD.repeat(chunkSize);
  let remaining = sizeParam;
  const stream = new ReadableStream({
    pull(controller) {
      if (remaining <= 0) { controller.close(); return; }
      const send = Math.min(remaining, chunkSize);
      controller.enqueue(send === chunkSize ? chunk : PAD.repeat(send));
      remaining -= send;
    },
  });
  return new Response(stream, {
    status: 200,
    headers: {
      ...corsHeaders,
      'Content-Type': 'application/octet-stream',
      'Content-Length': sizeParam.toString(),
    },
  });
}

// ─── WebSocket signaling routing ───────────────────────────────────────

async function handleWebSocket(request, env) {
  const url = new URL(request.url);
  const roomId = url.searchParams.get('roomId') || 'default';

  // Forward the WebSocket upgrade request to the Room DO.
  // The DO creates a WebSocketPair internally and returns one side.
  // Cloudflare's infrastructure bridges this WebSocket back to the client.
  const doId = env.ROOM_DO.idFromName(roomId);
  const doStub = env.ROOM_DO.get(doId);

  // Pass the full request URL + upgrade header so the DO properly
  // establishes the WebSocket hibernation connection.
  const doUrl = new URL('https://do' + url.pathname + url.search);
  const doResponse = await doStub.fetch(doUrl.toString(), {
    headers: { 'Upgrade': 'websocket' },
  });

  return doResponse;
}

async function handleHttpSignaling(request, env) {
  const url = new URL(request.url);
  const roomId = url.searchParams.get('roomId') || 'default';
  const trace = request.headers.get('X-Vidly-Client-Trace') || crypto.randomUUID();
  const started = Date.now();
  console.log('httpSignaling start', {
    trace,
    method: request.method,
    path: url.pathname,
    roomId,
    colo: request.cf?.colo || 'unknown',
    country: request.cf?.country || 'unknown',
    contentType: request.headers.get('content-type') || '',
    contentLength: request.headers.get('content-length') || '',
  });

  const doId = env.ROOM_DO.idFromName(roomId);
  const doStub = env.ROOM_DO.get(doId);
  const doUrl = new URL('https://do' + url.pathname + url.search);
  const headersForDo = new Headers(request.headers);
  headersForDo.set('X-Vidly-Trace', trace);

  let body = null;
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    body = await request.clone().text();
    console.log('httpSignaling body read', {
      trace,
      bytes: body.length,
      preview: body.slice(0, 120),
    });
  }

  let doResponse;
  try {
    doResponse = await doStub.fetch(doUrl.toString(), {
      method: request.method,
      headers: headersForDo,
      body,
    });
  } catch (e) {
    console.error('httpSignaling DO fetch failed', { trace, error: String(e) });
    return new Response(JSON.stringify({ error: 'do_fetch_failed', trace }), {
      status: 502,
      headers: {
        ...corsHeaders,
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store',
        'X-Vidly-Trace': trace,
        'X-Vidly-Worker-Stage': 'do-fetch-failed',
      },
    });
  }

  const headers = new Headers(doResponse.headers);
  for (const [key, value] of Object.entries(corsHeaders)) headers.set(key, value);
  headers.set('X-Vidly-Trace', trace);
  headers.set('X-Vidly-Worker-Stage', 'do-response');
  headers.set('X-Vidly-Worker-Duration-Ms', String(Date.now() - started));
  console.log('httpSignaling response', {
    trace,
    status: doResponse.status,
    durationMs: Date.now() - started,
    doStage: headers.get('X-Vidly-DO-Stage') || '',
  });
  return new Response(doResponse.body, {
    status: doResponse.status,
    statusText: doResponse.statusText,
    headers,
  });
}

// ─── Main entry ────────────────────────────────────────────────────────

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const path = url.pathname;

    // CORS preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    // ── 0b. Signaling health check (must be before /signal) ──
    if (path === '/signal-check' && request.method === 'GET') {
      return new Response(JSON.stringify({
        status: 'ok', message: 'signaling worker is running',
        version: env.ANDROID_VERSION_NAME || '2.0-cf',
      }), {
        status: 200,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      });
    }

    // ── 1. HTTP signaling fallback ──
    if (path.startsWith('/api/signal/')) {
      return handleHttpSignaling(request, env);
    }

    // ── 1. WebSocket signaling ──
    if (path.startsWith('/signal')) {
      return handleWebSocket(request, env);
    }

    // ── 1b. Turn credentials ──
    if (path === '/turn-credentials' && request.method === 'GET') {
      const creds = await generateTurnCredentials(env);
      return new Response(JSON.stringify(creds), {
        status: 200,
        headers: {
          ...corsHeaders,
          'Content-Type': 'application/json; charset=utf-8',
          'Cache-Control': 'no-store',
        },
      });
    }

    // ── 3. Version ──
    if (path === '/version' && request.method === 'GET') {
      return handleVersion(env);
    }

    // ── 4. GIPHY proxy ──
    if (path === '/giphy' && request.method === 'GET') {
      return proxyGiphy(request, env);
    }

    // ── 5. Crash report ──
    if (path === '/crash' && request.method === 'POST') {
      return handleCrashReport(request);
    }

    // ── 5b. WebSocket test page ──
    if (path === '/ws-test' && request.method === 'GET') {
      return new Response(WS_TEST_PAGE, {
        status: 200,
        headers: { ...corsHeaders, 'Content-Type': 'text/html;charset=utf-8' },
      });
    }

    // ── 5c. APK download proxy ──
    if ((path === '/vidly-native.apk' || path === '/apk') && request.method === 'GET') {
      const apkUrl = env.ANDROID_APK_URL || 'https://voice.raycc.org/vidly-native.apk';
      try {
        const resp = await fetch(apkUrl);
        return new Response(resp.body, {
          status: resp.status,
          headers: {
            'Content-Type': 'application/vnd.android.package-archive',
            'Content-Disposition': 'attachment; filename="vidly-native.apk"',
            'Cache-Control': 'public, max-age=300',
            ...corsHeaders,
          },
        });
      } catch (e) {
        return new Response('APK unavailable', { status: 502 });
      }
    }

    // ── 6. PoP test pages (from earlier) ──
    if (path === '/' || path === '') {
      return handlePopTestPage();
    }

    if (path === '/ping' && request.method === 'GET') {
      return handlePing(request);
    }

    if (path === '/test' && request.method === 'GET') {
      return handleTest(request);
    }

    // ── 7. Fallback ──
    return new Response(
      JSON.stringify({ error: 'unknown endpoint' }),
      { status: 404, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  },
};

export { RoomDO };

// ─── PoP test page HTML (embedded, no deps) ────────────────────────────

const POP_TEST_PAGE_HTML = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>Vidly — PoP Test</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0b1120;color:#e2e8f0;padding:16px}
h1{font-size:1.4rem;margin-bottom:4px;color:#f1f5f9}
.sub{color:#94a3b8;font-size:0.85rem;margin-bottom:16px}
.card{background:#1e293b;border-radius:12px;padding:16px;margin-bottom:12px;border:1px solid #334155}
.ins{background:#1e293b;border-left:3px solid #f59e0b;border-radius:0 12px 12px 0;padding:12px 16px;margin-bottom:16px;font-size:0.85rem;line-height:1.6}
.ins b{display:inline-block;width:20px;height:20px;background:#f59e0b;color:#0b1120;border-radius:50%;text-align:center;font-weight:700;font-size:0.75rem;line-height:20px;margin-right:6px}
.cb{display:inline-block;background:#2563eb;color:#fff;padding:2px 12px;border-radius:6px;font-weight:700;font-size:1.3rem}
.co{display:inline-block;background:#334155;color:#cbd5e1;padding:2px 8px;border-radius:6px;font-size:0.8rem}
.pb{width:100%;height:20px;background:#0f172a;border-radius:10px;overflow:hidden}
.pf{height:100%;width:0;background:linear-gradient(90deg,#3b82f6,#8b5cf6);border-radius:10px;transition:width .3s}
.pt{text-align:center;font-size:0.8rem;color:#94a3b8;margin-top:6px}
.btn{display:block;width:100%;padding:14px;border:none;border-radius:10px;font-size:1rem;font-weight:600;cursor:pointer;color:#fff}
.btn:disabled{opacity:.5;cursor:not-allowed}
.btn-b{background:#2563eb}
.btn-g{background:#059669}
.sr{display:flex;justify-content:space-between;margin-bottom:6px;font-family:monospace;font-size:0.85rem}
.sr .l{color:#64748b} .sr .v{color:#e2e8f0}
.er{color:#f87171;margin-top:8px;font-size:0.85rem;display:none}
.rb{display:none;margin-top:12px;padding:12px;background:#0f172a;border-radius:8px}
</style>
</head>
<body>
<h1>Vidly — PoP Test</h1>
<p class="sub">Verify: does Saudi carrier route Cloudflare locally?</p>
<div class="ins"><div><b>1</b> Screenshot carrier data usage</div><div><b>2</b> Run test & note PoP</div><div><b>3</b> Wait 3 min, screenshot again</div><div><b>4</b> Compare which bucket dropped</div></div>
<div class="card"><div style="font-size:0.75rem;text-transform:uppercase;color:#64748b;margin-bottom:8px">PoP</div><div id="pi">Querying...</div></div>
<div class="card"><div style="font-size:0.75rem;text-transform:uppercase;color:#64748b;margin-bottom:8px">Download Test (20 MB)</div>
<div class="pb"><div id="pf" class="pf"></div></div><div id="pt" class="pt">Not started</div>
<button id="btn" class="btn btn-b" onclick="go()">Start Download Test</button>
<div id="rb" class="rb"><div class="sr"><span class="l">Received</span><span id="r1" class="v"></span></div><div class="sr"><span class="l">Time</span><span id="r2" class="v"></span></div><div class="sr"><span class="l">Speed</span><span id="r3" class="v"></span></div></div>
<div id="er" class="er"></div></div>
<script>
const B=20971520;
function f(b){return b>=1048576?(b/1048576).toFixed(1)+' MB':(b/1024).toFixed(1)+' KB'}
fetch('/ping').then(r=>r.json()).then(d=>{document.getElementById('pi').innerHTML='<span class="cb">'+d.colo+'</span> <span class="co">'+d.country+'</span>'}).catch(e=>document.getElementById('pi').innerHTML='Error');
async function go(){
const btn=document.getElementById('btn'),pf=document.getElementById('pf'),pt=document.getElementById('pt'),rb=document.getElementById('rb'),er=document.getElementById('er');
btn.disabled=true;btn.textContent='Downloading...';rb.style.display='none';er.style.display='none';pf.style.width='0';pt.textContent='Starting...';let r=0,t=performance.now();
try{const res=await fetch('/test?size='+B);if(!res.ok)throw new Error('HTTP '+res.status);const rd=res.body.getReader();while(true){const{done,value}=await rd.read();if(done)break;r+=value.length;const p=Math.min(100,r/B*100);pf.style.width=p+'%';pt.textContent=f(r)+' / '+f(B)+' ('+p.toFixed(0)+'%)'}
const e=performance.now()-t,s=r/(e/1000);pf.style.width='100%';pt.textContent='Complete';document.getElementById('r1').textContent=f(r);document.getElementById('r2').textContent=e>=1000?(e/1000).toFixed(2)+' s':e.toFixed(0)+' ms';document.getElementById('r3').textContent=s>=1048576?(s/1048576).toFixed(2)+' MB/s':(s/1024).toFixed(1)+' KB/s';rb.style.display='block';btn.textContent='Test Complete';btn.className='btn btn-g'
}catch(e){er.textContent='Error: '+e.message;er.style.display='block';pt.textContent='Failed';btn.textContent='Retry';btn.className='btn btn-b';btn.disabled=false}}
</script>
</body>
</html>`;

const WS_TEST_PAGE = `<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>WebSocket Test</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0b1120;color:#e2e8f0;padding:16px}
h1{font-size:1.4rem;margin-bottom:4px}
.sub{color:#94a3b8;font-size:0.85rem;margin-bottom:16px}
.card{background:#1e293b;border-radius:12px;padding:16px;margin-bottom:12px;border:1px solid #334155}
.card-title{font-size:0.75rem;text-transform:uppercase;color:#64748b;margin-bottom:8px}
.st{display:flex;align-items:center;gap:8px;margin-bottom:6px}
.st .l{color:#94a3b8;font-size:0.85rem}
.st .v{font-family:monospace}
.ok{color:#34d399}
.fail{color:#f87171}
.wait{color:#fbbf24}
.btn{display:block;width:100%;padding:14px;border:none;border-radius:10px;font-size:1rem;font-weight:600;cursor:pointer;color:#fff}
.btn:disabled{opacity:.5;cursor:not-allowed}
.btn-b{background:#2563eb}
.log{background:#0f172a;border-radius:8px;padding:12px;font-family:monospace;font-size:0.8rem;line-height:1.6;max-height:200px;overflow-y:auto;margin-top:8px}
.log .ts{color:#64748b;margin-right:8px}
</style>
</head>
<body>
<h1>WebSocket Test</h1>
<p class="sub">Tests connectivity to the Vidly signaling server</p>
<div id="pop-card" class="card"><div class="card-title">Cloudflare PoP</div><div class="st"><span class="l">PoP:</span><span id="pop-val" class="v wait">Checking...</span></div></div>
<div class="card"><div class="card-title">WebSocket Test</div>
<div class="st"><span class="l">Step 1: Connect</span><span id="s1" class="v wait">⏳</span></div>
<div class="st"><span class="l">Step 2: Welcome</span><span id="s2" class="v wait">-</span></div>
<div class="st"><span class="l">Step 3: Join Room</span><span id="s3" class="v wait">-</span></div>
<div class="st"><span class="l">Step 4: Keepalive (5s)</span><span id="s4" class="v wait">-</span></div>
<button id="btn" class="btn btn-b" onclick="runTest()">▶ Run WebSocket Test</button>
<div id="log" class="log" style="display:none"></div>
</div>
<script>
function log(msg){const el=document.getElementById('log');el.style.display='block';el.innerHTML+='<div><span class="ts">></span>'+msg+'</div>';el.scrollTop=el.scrollHeight}
function setStep(id,status,text){document.getElementById(id).innerHTML='<span class="'+status+'">'+text+'</span>'}

async function runTest(){
  const btn=document.getElementById('btn');btn.disabled=true;btn.textContent='Testing...';
  document.getElementById('log').innerHTML='';
  setStep('s1','wait','⏳ Connecting...');

  const roomId='ws-test-'+(+Date.now());
  const ws=new WebSocket('wss://'+location.host+'/signal?roomId='+roomId);
  log('Connecting to wss://'+location.host+'/signal?roomId='+roomId);

  const timeout=setTimeout(()=>{
    setStep('s1','fail','❌ Timeout after 10s');
    log('❌ CONNECTION TIMEOUT - WebSocket upgrade failed');
    log('This means the carrier likely blocks WebSocket connections');
    btn.disabled=false;btn.textContent='↻ Retry';
  },10000);

  ws.onopen=()=>{
    clearTimeout(timeout);
    setStep('s1','ok','✅ Connected');
    setStep('s2','wait','⏳ Waiting...');
    log('✅ WebSocket opened successfully');
    log('Sending join message...');
    ws.send(JSON.stringify({type:'join',roomId,username:'ws-tester',clientId:'ws-'+roomId,pageSessionId:'p1'}));
  };

  ws.onmessage=(e)=>{
    const msg=JSON.parse(e.data);
    log('📨 '+msg.type+(msg.peerId?' peer='+msg.peerId.slice(0,8):''));
    if(msg.type==='welcome'){
      setStep('s2','ok','✅ Welcome received');
    }else if(msg.type==='joined'){
      setStep('s3','ok','✅ Joined room');
      log('Room: '+msg.roomId+', Peers: '+msg.peers.length);
      // Wait 5 seconds to test connection stability
      setStep('s4','wait','⏳ Waiting 5s...');
      setTimeout(()=>{
        setStep('s4','ok','✅ Stable (5s)');
        log('✅ Connection stable for 5 seconds - WebSocket works!');
        log('');
        log('RESULT: WebSocket signaling is working correctly from this network');
        ws.close();
        btn.disabled=false;btn.textContent='↻ Test Again';
      },5000);
    }else if(msg.type==='pong'){
      log('🏓 pong');
    }
  };

  ws.onerror=(e)=>{
    clearTimeout(timeout);
    setStep('s1','fail','❌ Connection error');
    log('❌ WebSocket error - the carrier may be blocking WebSocket');
    btn.disabled=false;btn.textContent='↻ Retry';
  };

  ws.onclose=(e)=>{
    clearTimeout(timeout);
    if(!document.getElementById('s3').innerHTML.includes('ok')){
      setStep('s1','fail','❌ Closed: code='+e.code);
      log('❌ WebSocket closed unexpectedly (code='+e.code+')');
      btn.disabled=false;btn.textContent='↻ Retry';
    }
  };
}

// Load PoP info on startup
fetch('/ping').then(r=>r.json()).then(d=>{
  document.getElementById('pop-val').innerHTML='<span class="ok">'+d.colo+'</span> <span style="color:#94a3b8">('+d.country+')</span>';
}).catch(()=>{
  document.getElementById('pop-val').innerHTML='<span class="fail">Unreachable</span>';
});
</script>
</body>
</html>`;
