"""Gup Shup Desktop — Gemma 4 E2B running offline on your computer, with a clean chat UI in the browser.
Double-click GupShup.exe: it downloads the model once (2.6 GB), then works without internet.
"""
import json, os, re, sys, threading, time, urllib.request, webbrowser
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

APP_DIR = os.path.join(os.environ.get("LOCALAPPDATA") or os.path.expanduser("~"), "GupShup")
MODEL = os.path.join(APP_DIR, "gupshup-gemma4.litertlm")
CACHE = os.path.join(APP_DIR, "cache")
PARTS = [
    "https://github.com/abrar1499/bolkhata-brain/releases/download/gemma4-litert-v1/gupshup-gemma4.litertlm.part0",
    "https://github.com/abrar1499/bolkhata-brain/releases/download/gemma4-litert-v1/gupshup-gemma4.litertlm.part1",
]
SYS = ('Tum "Gup Shup" ho, ek dostana aur samajhdar saathi. Hamesha Roman Urdu me jawab do (Latin huroof, jaise WhatsApp par likhte hain), '
       'jab tak user English ya Arabic me na likhe. Jawab saaf aur mukhtasar rakho. Apna naam har jawab me mat likho. '
       'Sanjeeda baat par mazaq mat karo. Tum bina internet ke ho: aaj ka mausam, khabrein ya taaza maloomat tumhein nahi pata, '
       'poocha jaye to imaandari se batao.')
SELF_HARM = ("Mujhe bohat afsos hai ke tum itna bura mehsoos kar rahe ho 💙 Tum akele nahi ho. Please abhi kisi apne ko call karo, "
             "ya kisi doctor ya counsellor se baat karo. Agar khud ko nuqsan ka khatra hai to foran emergency par call karo "
             "(Saudi Arabia: ambulance 997, police 999; Pakistan: 1122). Saudi me Ministry of Health ki 937 line bhi din raat khuli hai.")
ABUSE = ("Ye bilkul theek nahi, aur is me aap ki koi ghalti nahi 💙 Agar aap is waqt khatre me hain to foran police ko call karein "
         "(Saudi Arabia: 999, Pakistan: 15). Saudi Arabia me ghar ke tashaddud ki shikayat ke liye 1919 par 24 ghante muft call kar sakte hain. "
         "Kisi bharose ke rishtedaar ya dost ko bhi batayein. Kya aap is waqt mehfooz hain?")
DAYS = ["Peer", "Mangal", "Budh", "Jumeraat", "Jumma", "Hafta", "Itwaar"]

state = {"state": "starting", "msg": "Shuru ho raha hai...", "pct": 0}
engine = None
conv = None
lock = threading.Lock()


def local_reply(msg):
    l = re.sub(r"\s+", " ", re.sub(r"[^a-z0-9\s]", " ", msg.lower()))
    if re.search(r"khud ?kushi|suicide|mar+ ?jana|marna chaht|je+ne ka dil nahi|jeena nahi chaht|zindagi khatam|khud ko nuqsan|apni jaan", l):
        return SELF_HARM
    if re.search(r"\b(marta|maarta|marti|maarti|marte|maarte|peet|peetta|pitai|zulm karta|tashaddud|abuse)\b|haath uthat", l):
        return ABUSE
    now = datetime.now()
    if re.search(r"\b(time|wa?qt|baje)\b", l) and re.search(r"\b(kya|kia|kitne|batao|hua|ho)\b", l):
        return f"Abhi {now:%I:%M %p} baje hain 🕐"
    if re.search(r"\b(tar(e+|i)kh|date)\b", l):
        return f"Aaj {now:%d %B %Y} hai 📅"
    if re.search(r"(kaun|kon|konsa|kaunsa) ?sa din|kya din hai", l):
        return f"Aaj {DAYS[now.weekday()]} hai 📆"
    return None


def new_conv():
    import litert_lm as L
    return engine.create_conversation(system_message=SYS, thinking_config=L.ThinkingConfig(enable_thinking=False),
                                      sampler_config=L.SamplerConfig(top_k=40, top_p=0.95, temperature=0.7))


def prepare():
    global engine, conv
    try:
        os.makedirs(CACHE, exist_ok=True)
        if not os.path.exists(MODEL):
            tmp = MODEL + ".part"
            total = 0
            for u in PARTS:
                with urllib.request.urlopen(urllib.request.Request(u, method="HEAD"), timeout=30) as r:
                    total += int(r.headers.get("Content-Length") or 0)
            done = 0
            with open(tmp, "wb") as out:
                for u in PARTS:
                    with urllib.request.urlopen(u, timeout=60) as r:
                        while True:
                            b = r.read(1 << 20)
                            if not b:
                                break
                            out.write(b)
                            done += len(b)
                            pct = int(done * 100 / total) if total else 0
                            state.update(state="downloading", pct=pct,
                                         msg=f"Model download ho raha hai (sirf pehli dafa): {pct}%  ({done >> 20} / {total >> 20} MB)")
            os.replace(tmp, MODEL)
        state.update(state="loading", msg="Model load ho raha hai... (pehli dafa 1-3 minute)")
        import litert_lm as L
        engine = L.Engine(MODEL, backend=L.Backend.CPU(), cache_dir=CACHE)
        conv = new_conv()
        state.update(state="ready", msg="Tayyar — bina internet ke chal raha hai")
    except Exception as e:
        state.update(state="error", msg=f"Masla: {e}. App band kar ke dobara kholein (internet check karein).")


HTML = r"""<!DOCTYPE html><html lang="ur"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Gup Shup</title><style>
*{box-sizing:border-box}html,body{height:100%}
body{margin:0;background:#F6F4EF;color:#22201C;font:16px/1.6 "Segoe UI",system-ui,-apple-system,sans-serif;display:flex;flex-direction:column}
header{display:flex;align-items:center;gap:10px;padding:14px 22px;border-bottom:1px solid #E6E1D7;background:#F6F4EF}
.logo{width:30px;height:30px;border-radius:9px;background:#1F7A4D;color:#fff;display:grid;place-items:center;font-weight:700}
header h1{font:600 18px Georgia,"Times New Roman",serif;margin:0}
#st{margin-left:auto;font-size:13px;color:#7A746A}
header button{border:1px solid #DDD6C9;background:#fff;border-radius:10px;padding:6px 12px;font:inherit;font-size:14px;cursor:pointer}
main{flex:1;overflow-y:auto}
.col{max-width:760px;margin:0 auto;padding:28px 20px 10px}
.hello{text-align:center;margin-top:12vh}.hello h2{font:400 34px Georgia,serif;margin:0 0 8px}.hello p{color:#7A746A;margin:0}
.chips{display:flex;flex-wrap:wrap;gap:8px;justify-content:center;margin-top:22px}
.chips button{border:1px solid #DDD6C9;background:#fff;border-radius:999px;padding:8px 14px;font:inherit;font-size:14px;cursor:pointer}
.u{display:flex;justify-content:flex-end;margin:18px 0}.u div{background:#E9E4DA;border-radius:18px;padding:10px 16px;max-width:80%;white-space:pre-wrap}
.a{margin:18px 0;display:flex;gap:12px}.a .ic{flex:none;width:28px;height:28px;border-radius:8px;background:#1F7A4D;color:#fff;display:grid;place-items:center;font-size:14px;font-weight:700;margin-top:2px}
.a div.t{white-space:pre-wrap;flex:1}.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:#1F7A4D;animation:p 1s infinite}
@keyframes p{50%{opacity:.25}}
footer{padding:12px 20px 22px}.box{max-width:760px;margin:0 auto;background:#fff;border:1px solid #DDD6C9;border-radius:22px;box-shadow:0 2px 10px #0000000a;display:flex;align-items:flex-end;padding:8px 8px 8px 16px}
textarea{flex:1;border:0;outline:0;resize:none;font:inherit;max-height:180px;padding:8px 0;background:transparent;color:inherit}
#go{border:0;width:38px;height:38px;border-radius:12px;background:#1F7A4D;color:#fff;font-size:18px;cursor:pointer}#go:disabled{background:#B9C9BF;cursor:default}
.note{max-width:760px;margin:8px auto 0;text-align:center;font-size:12px;color:#9A9386}
.bar{height:6px;background:#E6E1D7;border-radius:6px;overflow:hidden;max-width:360px;margin:16px auto 0}.bar i{display:block;height:100%;background:#1F7A4D;width:0}
@media (prefers-color-scheme:dark){body,header{background:#1E1D1A;color:#ECE8E1}header{border-color:#33312C}.u div{background:#2E2C27}
.box,header button,.chips button{background:#2A2824;border-color:#3A3732;color:#ECE8E1}.bar{background:#33312C}}
</style></head><body>
<header><div class="logo">G</div><h1>Gup Shup</h1><span id="st">…</span><button id="nw">＋ Nayi baat-cheet</button></header>
<main id="m"><div class="col" id="c"><div class="hello" id="hi"><h2>Assalam o Alaikum 👋</h2><p id="hm">Model tayyar ho raha hai…</p>
<div class="bar" id="bar"><i id="bi"></i></div>
<div class="chips" id="ch"><button>Kya haal hai?</button><button>Ek joke sunao</button><button>Mujhe ek achha mashwara do</button><button>Ek chhoti si kahani sunao</button></div></div></div></main>
<footer><div class="box"><textarea id="t" rows="1" placeholder="Gup Shup se kuch bhi poocho…"></textarea><button id="go" disabled>➤</button></div>
<div class="note">Ye AI aap ke computer par bina internet ke chalta hai. Ghalti kar sakta hai — zaroori baat khud tasdeeq karein.</div></footer>
<script>
const $=i=>document.getElementById(i),c=$('c'),m=$('m'),t=$('t'),go=$('go');let ready=false,busy=false;
function esc(s){return s.replace(/[&<>]/g,x=>({'&':'&amp;','<':'&lt;','>':'&gt;'}[x]))}
function add(cls,txt){$('hi')&&$('hi').remove();const d=document.createElement('div');d.className=cls;
 d.innerHTML=cls==='u'?'<div></div>':'<span class="ic">G</span><div class="t"></div>';const x=d.querySelector('div:last-child');x.textContent=txt;c.appendChild(d);m.scrollTop=m.scrollHeight;return x}
async function poll(){try{const s=await (await fetch('/api/status')).json();$('st').textContent=s.msg;
 if($('hm'))$('hm').textContent=s.state==='ready'?'Main Gup Shup hoon. Kuch bhi poocho!':s.msg;
 if($('bi'))$('bi').style.width=(s.state==='downloading'?s.pct:(s.state==='ready'?100:(s.state==='loading'?100:0)))+'%';
 if($('bar')&&s.state==='ready')$('bar').remove();
 ready=s.state==='ready';go.disabled=!ready||busy;if(!ready)setTimeout(poll,1000)}catch(e){setTimeout(poll,2000)}}
async function send(msg){msg=(msg||t.value).trim();if(!msg||!ready||busy)return;t.value='';t.style.height='';add('u',msg);
 const out=add('a','');out.innerHTML='<span class="dot"></span>';busy=true;go.disabled=true;let txt='';
 try{const r=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:msg})});
  const rd=r.body.getReader(),dec=new TextDecoder();while(true){const {done,value}=await rd.read();if(done)break;txt+=dec.decode(value,{stream:true});out.textContent=txt;m.scrollTop=m.scrollHeight}
  if(!txt.trim())out.textContent='Hmm 🤔 dobara poocho?'}catch(e){out.textContent='Masla aa gaya, dobara koshish karein.'}
 busy=false;go.disabled=!ready;t.focus()}
go.onclick=()=>send();t.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}});
t.addEventListener('input',()=>{t.style.height='auto';t.style.height=Math.min(t.scrollHeight,180)+'px'});
document.querySelectorAll('#ch button').forEach(b=>b.onclick=()=>send(b.textContent));
$('nw').onclick=async()=>{if(busy)return;await fetch('/api/reset',{method:'POST'});location.reload()};
poll();
</script></body></html>"""


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json"):
        b = body.encode("utf-8") if isinstance(body, str) else body
        self.send_response(code)
        self.send_header("Content-Type", ctype + "; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        if self.path == "/":
            self._send(200, HTML, "text/html")
        elif self.path == "/api/status":
            self._send(200, json.dumps(state))
        else:
            self._send(404, "{}")

    def do_POST(self):
        global conv
        n = int(self.headers.get("Content-Length") or 0)
        data = json.loads(self.rfile.read(n) or b"{}")
        if self.path == "/api/reset":
            if state["state"] == "ready":
                with lock:
                    conv = new_conv()
            return self._send(200, "{}")
        if self.path != "/api/chat":
            return self._send(404, "{}")
        msg = str(data.get("message", "")).strip()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        quick = local_reply(msg)
        if quick or state["state"] != "ready":
            self.wfile.write((quick or "Model abhi tayyar nahi.").encode("utf-8"))
            return
        with lock:
            try:
                for part in conv.send_message_async(msg):
                    self.wfile.write(str(part).encode("utf-8"))
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                pass
            except Exception as e:
                self.wfile.write(f"\n(Masla: {e})".encode("utf-8"))


def main():
    if "--selftest" in sys.argv:
        import litert_lm  # noqa: F401  (checks the bundled engine loads)
        print("SELFTEST OK", litert_lm.__file__)
        return
    threading.Thread(target=prepare, daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", 0), H)
    url = f"http://127.0.0.1:{srv.server_address[1]}/"
    print("=" * 56)
    print("  Gup Shup chal raha hai:", url)
    print("  Browser khud khul jayega. Band karne ke liye ye window band karein.")
    print("=" * 56)
    if "--no-browser" not in sys.argv:
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    srv.serve_forever()


if __name__ == "__main__":
    main()
