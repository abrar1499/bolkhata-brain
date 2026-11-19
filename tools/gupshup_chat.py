"""Test the Gup Shup v3 model (Gemma 4 E2B, LiteRT) here, exactly like the app.
Usage: python3 gupshup_chat.py "message"      (keeps chat history in history.json)
       python3 gupshup_chat.py --reset
"""
import json, os, re, sys, time
from datetime import datetime
import litert_lm as L

MODEL = os.environ.get("GUPSHUP_MODEL", "gupshup-gemma4.litertlm")
HIST = "gupshup_history.json"
SYS = ('Tum "Gup Shup" ho, ek dostana saathi. Hamesha Roman Urdu me jawab do (Latin huroof, jaise WhatsApp par likhte hain). '
       'Jawab chhote rakho, 1-3 jumle. Apna naam har jawab me mat likho. Sanjeeda baat par mazaq mat karo. '
       'Tum bina internet ke ho: aaj ka mausam, khabrein ya taaza maloomat tumhein nahi pata, poocha jaye to imaandari se batao.')
SELF_HARM = ("Mujhe bohat afsos hai ke tum itna bura mehsoos kar rahe ho 💙 Tum akele nahi ho. Please abhi kisi apne ko call karo, "
             "ya kisi doctor ya counsellor se baat karo. Agar khud ko nuqsan ka khatra hai to foran emergency par call karo "
             "(Saudi Arabia: ambulance 997, police 999; Pakistan: 1122). Saudi me Ministry of Health ki 937 line bhi din raat khuli hai.")
ABUSE = ("Ye bilkul theek nahi, aur is me aap ki koi ghalti nahi 💙 Agar aap is waqt khatre me hain to foran police ko call karein "
         "(Saudi Arabia: 999, Pakistan: 15). Saudi Arabia me ghar ke tashaddud ki shikayat ke liye 1919 par 24 ghante muft call kar sakte hain. "
         "Kya aap is waqt mehfooz hain?")
DAYS = ["Peer", "Mangal", "Budh", "Jumeraat", "Jumma", "Hafta", "Itwaar"]

def local_reply(msg):  # same on-phone rules as the app
    l = re.sub(r"\s+", " ", re.sub(r"[^a-z0-9\s]", " ", msg.lower()))
    if re.search(r"khud ?kushi|suicide|mar+ ?jana|marna chaht|je+ne ka dil nahi|jeena nahi chaht|zindagi khatam|khud ko nuqsan|apni jaan", l): return SELF_HARM
    if re.search(r"\b(marta|maarta|marti|maarti|marte|maarte|peet|peetta|pitai|zulm karta|tashaddud|abuse)\b|haath uthat", l): return ABUSE
    now = datetime.now()
    if re.search(r"\b(time|wa?qt|baje)\b", l) and re.search(r"\b(kya|kia|kitne|batao|hua|ho)\b", l): return f"Abhi {now:%I:%M %p} baje hain 🕐"
    if re.search(r"\b(tar(e+|i)kh|date)\b", l): return f"Aaj {now:%d %B %Y} hai 📅"
    if re.search(r"(kaun|kon|konsa|kaunsa) ?sa din|kya din hai", l): return f"Aaj {DAYS[now.weekday()]} hai 📆"
    return None

if sys.argv[1:] == ["--reset"]:
    if os.path.exists(HIST): os.remove(HIST)
    print("(nayi baat-cheet)"); sys.exit()
msg = " ".join(sys.argv[1:]).strip()
hist = json.load(open(HIST)) if os.path.exists(HIST) else []
t = time.time()
ans = local_reply(msg)
src = "phone ka rule"
if ans is None:
    os.makedirs("lcache", exist_ok=True)
    eng = L.Engine(MODEL, backend=L.Backend.CPU(), cache_dir="lcache")
    conv = eng.create_conversation(messages=hist[-12:] or None, system_message=SYS,
                                   thinking_config=L.ThinkingConfig(enable_thinking=False),
                                   sampler_config=L.SamplerConfig(top_k=40, top_p=0.95, temperature=0.7))
    ans = str(conv.send_message(msg)).strip()
    src = "Gemma 4 model"
hist += [{"role": "user", "content": msg}, {"role": "model", "content": ans}]
json.dump(hist, open(HIST, "w"), ensure_ascii=False)
print(f"{ans}\n[{src}, {time.time() - t:.1f}s]")
