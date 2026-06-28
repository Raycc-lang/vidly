/**
 * Vidly PoP Test Worker — diagnostic endpoint for Cloudflare PoP classification
 *
 * Endpoints:
 *   GET /ping            -> {"status":"ok","colo":"<cf.colo>","country":"<cf-ipcountry>"}
 *   GET /test?size=<N>   -> N bytes of response payload (max 50MB)
 *
 * Logs: CF-IPCountry + cf.colo for every request via cf.request.cf metadata.
 */

const MAX_BYTES = 52428800; // 50 MB safety cap
const PADDING_CHAR = 'x';

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);
    const colo = request.cf && request.cf.colo ? request.cf.colo : 'unknown';
    const country = request.cf && request.cf.country ? request.cf.country : 'unknown';
    const ip = request.headers.get('CF-Connecting-IP') || 'unknown';

    // --- CORS headers for all responses ---
    const corsHeaders = {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, OPTIONS',
      'Access-Control-Allow-Headers': '*',
    };

    // Preflight
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: corsHeaders });
    }

    // --- /ping ---
    if (url.pathname === '/ping') {
      console.log(`[ping] colo=${colo} country=${country} ip=${ip}`);
      return new Response(
        JSON.stringify({ status: 'ok', colo, country }),
        { status: 200, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
      );
    }

    // --- /test?size=N ---
    if (url.pathname === '/test') {
      const sizeParam = parseInt(url.searchParams.get('size'), 10);

      if (isNaN(sizeParam) || sizeParam < 1) {
        return new Response(
          JSON.stringify({ error: 'specify ?size=<bytes> as a positive integer' }),
          { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      if (sizeParam > MAX_BYTES) {
        return new Response(
          JSON.stringify({ error: `size too large; max ${MAX_BYTES} bytes (${Math.round(MAX_BYTES/1048576)} MB)` }),
          { status: 400, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
        );
      }

      console.log(`[test] colo=${colo} country=${country} ip=${ip} size=${sizeParam}`);

      // Generate the payload — use repeated char to avoid memory spikes
      // Cloudflare Workers have 128MB memory limit; for 50MB payload we
      // build in chunks.
      const chunkSize = Math.min(sizeParam, 65536); // 64KB chunks
      const chunk = PADDING_CHAR.repeat(chunkSize);
      let remaining = sizeParam;
      const stream = new ReadableStream({
        start(controller) {
          function push() {
            if (remaining <= 0) {
              controller.close();
              return;
            }
            const send = Math.min(remaining, chunkSize);
            controller.enqueue(send === chunkSize ? chunk : PADDING_CHAR.repeat(send));
            remaining -= send;
          }
          push();
        },
        pull(controller) {
          // pull is called when consumer wants more; push as much as needed
          while (remaining > 0) {
            const send = Math.min(remaining, chunkSize);
            controller.enqueue(send === chunkSize ? chunk : PADDING_CHAR.repeat(send));
            remaining -= send;
          }
          controller.close();
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

    // --- / (root) — serve the self-contained test page ---
    if (url.pathname === '/' || url.pathname === '') {
      const page = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1.0">
<title>Cloudflare PoP Test</title>
<style>
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;background:#0b1120;color:#e2e8f0;padding:16px}
h1{font-size:1.4rem;margin-bottom:4px;color:#f1f5f9}
.sub{color:#94a3b8;font-size:0.85rem;margin-bottom:16px}
.card{background:#1e293b;border-radius:12px;padding:16px;margin-bottom:12px;border:1px solid #334155}
.ins{background:#1e293b;border-left:3px solid #f59e0b;border-radius:0 12px 12px 0;padding:12px 16px;margin-bottom:16px;font-size:0.85rem;line-height:1.6}
.ins strong{color:#fbbf24}
.s{b{display:inline-block;width:20px;height:20px;background:#f59e0b;color:#0b1120;border-radius:50%;text-align:center;font-weight:700;font-size:0.75rem;line-height:20px;margin-right:6px}
.cb{display:inline-block;background:#2563eb;color:#fff;padding:2px 12px;border-radius:6px;font-weight:700;font-size:1.3rem}
.co{display:inline-block;background:#334155;color:#cbd5e1;padding:2px 8px;border-radius:6px;font-size:0.8rem}
.pb{width:100%;height:20px;background:#0f172a;border-radius:10px;overflow:hidden}
.pf{height:100%;width:0;background:linear-gradient(90deg,#3b82f6,#8b5cf6);border-radius:10px;transition:width .3s}
.pt{text-align:center;font-size:0.8rem;color:#94a3b8;margin-top:6px}
.btn{display:block;width:100%;padding:14px;border:none;border-radius:10px;font-size:1rem;font-weight:600;cursor:pointer}
.btn:disabled{opacity:.5;cursor:not-allowed}
.btn-b{background:#2563eb;color:#fff}
.btn-g{background:#059669;color:#fff}
.sr{display:flex;justify-content:space-between;margin-bottom:6px;font-family:monospace;font-size:0.85rem}
.sr .l{color:#64748b}
.sr .v{color:#e2e8f0}
.er{color:#f87171;margin-top:8px;font-size:0.85rem;display:none}
.rb{display:none;margin-top:12px;padding:12px;background:#0f172a;border-radius:8px}
</style>
</head>
<body>
<h1>Cloudflare PoP Test</h1>
<p class="sub">Verify: does Saudi carrier route Cloudflare locally?</p>
<div class="ins">
<div><b>1</b> Screenshot carrier data usage (local vs international)</div>
<div><b>2</b> Run test & note PoP below</div>
<div><b>3</b> Wait 3 min, screenshot again</div>
<div><b>4</b> Compare which bucket dropped ~20MB</div>
</div>
<div class="card">
<div style="font-size:0.75rem;text-transform:uppercase;color:#64748b;margin-bottom:8px">Current PoP</div>
<div id="pi"><span style="color:#94a3b8">Querying...</span></div>
</div>
<div class="card">
<div style="font-size:0.75rem;text-transform:uppercase;color:#64748b;margin-bottom:8px">Download Test (20 MB)</div>
<div class="pb"><div id="pf" class="pf"></div></div>
<div id="pt" class="pt">Not started</div>
<button id="btn" class="btn btn-b" onclick="go()">Start Download Test</button>
<div id="rb" class="rb">
<div class="sr"><span class="l">Received</span><span id="r1" class="v"></span></div>
<div class="sr"><span class="l">Time</span><span id="r2" class="v"></span></div>
<div class="sr"><span class="l">Speed</span><span id="r3" class="v"></span></div>
</div>
<div id="er" class="er"></div>
</div>
<script>
const B=20971520;
function f(b){return b>=1048576?(b/1048576).toFixed(1)+' MB':(b/1024).toFixed(1)+' KB'}
fetch('/ping').then(r=>r.json()).then(d=>{document.getElementById('pi').innerHTML='<span class="cb">'+d.colo+'</span> <span class="co">'+d.country+'</span>'}).catch(e=>document.getElementById('pi').innerHTML='<span style="color:#f87171">Error: '+e.message+'</span>');
async function go(){
const btn=document.getElementById('btn'),pf=document.getElementById('pf'),pt=document.getElementById('pt'),rb=document.getElementById('rb'),er=document.getElementById('er');
btn.disabled=true;btn.textContent='Downloading...';btn.className='btn';rb.style.display='none';er.style.display='none';pf.style.width='0';pt.textContent='Starting...';
let r=0,t=performance.now();
try{
const res=await fetch('/test?size='+B);
if(!res.ok)throw new Error('HTTP '+res.status);
const rd=res.body.getReader();
while(true){const{done,value}=await rd.read();if(done)break;r+=value.length;const p=Math.min(100,r/B*100);pf.style.width=p+'%';pt.textContent=f(r)+' / '+f(B)+' ('+p.toFixed(0)+'%)'}
const e=performance.now()-t,s=r/(e/1000),sl=s>=1048576?(s/1048576).toFixed(2)+' MB/s':(s/1024).toFixed(1)+' KB/s';
pf.style.width='100%';pt.textContent='Complete';
document.getElementById('r1').textContent=f(r);document.getElementById('r2').textContent=e>=1000?(e/1000).toFixed(2)+' s':e.toFixed(0)+' ms';document.getElementById('r3').textContent=sl;
rb.style.display='block';btn.textContent='Test Complete';btn.className='btn btn-g';
}catch(e){er.textContent='Error: '+e.message;er.style.display='block';pt.textContent='Failed';btn.textContent='Retry';btn.className='btn btn-b';btn.disabled=false}
}
</script>
</body>
</html>`;
      return new Response(page, {
        status: 200,
        headers: { ...corsHeaders, 'Content-Type': 'text/html;charset=utf-8' },
      });
    }

    // --- fallback ---
    return new Response(
      JSON.stringify({ error: 'unknown endpoint', endpoints: ['/ping', '/test?size=N'] }),
      { status: 404, headers: { ...corsHeaders, 'Content-Type': 'application/json' } }
    );
  },
};