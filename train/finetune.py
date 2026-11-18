"""CPU LoRA fine-tune of Qwen3-1.7B on open Roman Urdu instruction data.
Runs inside GitHub Actions (4 CPU, 16 GB RAM). Time-limited."""
import os, re, time, random, torch
from datasets import load_dataset
from transformers import AutoTokenizer, AutoModelForCausalLM
from peft import LoraConfig, get_peft_model

BASE = "Qwen/Qwen3-1.7B"
SYS = 'Tum "Gup Shup" ho, ek dostana saathi. Hamesha Roman Urdu me jawab do. Jawab chhote aur seedhe.'
N_SAMPLES = int(os.environ.get("N_SAMPLES", "1500"))
TIME_LIMIT = float(os.environ.get("TIME_LIMIT_MIN", "260")) * 60
MAXLEN = 256
torch.set_num_threads(os.cpu_count() or 4)
random.seed(0)

latin = re.compile(r"^[\x00-\x7F’‘“”…–—]+$")
rows = []
for name in ["khizer-kt/romanurdudata10k", "Redgerd/RomanUrduEnglish"]:
    try:
        ds = load_dataset(name, split="train")
    except Exception as e:
        print("skip", name, e); continue
    print(f"== {name}: {len(ds)} rows, columns {ds.column_names}")
    for r in ds.select(range(min(3, len(ds)))):
        print("  sample:", {k: str(v)[:150] for k, v in r.items()})
    for r in ds:
        ins = str(r.get("instruction") or "").strip()
        inp = str(r.get("input") or "").strip()
        out = str(r.get("output") or "").strip()
        q = (ins + ("\n" + inp if inp else "")).strip()
        if not q or not out or len(out) > 500 or len(q) > 300:
            continue
        if not (latin.match(q) and latin.match(out)):
            continue
        rows.append((q, out))
print("usable rows:", len(rows))
random.shuffle(rows)
rows = rows[:N_SAMPLES]

tok = AutoTokenizer.from_pretrained(BASE)
def encode(q, a):
    msgs = [{"role": "system", "content": SYS}, {"role": "user", "content": q}]
    prompt = tok.apply_chat_template(msgs, tokenize=False, add_generation_prompt=True, enable_thinking=False)
    full = prompt + a + tok.eos_token
    p_ids = tok(prompt, add_special_tokens=False)["input_ids"]
    ids = tok(full, add_special_tokens=False)["input_ids"][:MAXLEN]
    labels = [-100] * min(len(p_ids), len(ids)) + ids[len(p_ids):]
    return torch.tensor([ids]), torch.tensor([labels[:len(ids)]])
data = [encode(q, a) for q, a in rows]

model = AutoModelForCausalLM.from_pretrained(BASE, torch_dtype=torch.float32)
model.gradient_checkpointing_enable()
model.enable_input_require_grads()
model = get_peft_model(model, LoraConfig(
    r=16, lora_alpha=32, lora_dropout=0.05, task_type="CAUSAL_LM",
    target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"]))
model.print_trainable_parameters()
opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=2e-4)

ACC = 8
start = time.time(); model.train(); step = 0; run = 0.0
for i, (x, y) in enumerate(data):
    loss = model(input_ids=x, labels=y).loss / ACC
    loss.backward(); run += loss.item()
    if (i + 1) % ACC == 0:
        opt.step(); opt.zero_grad(); step += 1
        el = time.time() - start
        print(f"step {step}  ex {i+1}/{len(data)}  loss {run:.3f}  {el/60:.1f} min", flush=True)
        run = 0.0
        if el > TIME_LIMIT:
            print("time limit reached, stopping"); break

model = model.merge_and_unload()
model.eval()
for q in ["kya haal hai yaar?", "ek chhota sa joke sunao", "aaj bohat thak gaya hoon", "Lahore ke bare me batao"]:
    msgs = [{"role": "system", "content": SYS}, {"role": "user", "content": q}]
    ids = tok.apply_chat_template(msgs, add_generation_prompt=True, enable_thinking=False, return_tensors="pt")
    with torch.no_grad():
        out = model.generate(ids, max_new_tokens=60, do_sample=True, temperature=0.6, top_p=0.9)
    print(f"Q: {q}\nA: {tok.decode(out[0][ids.shape[1]:], skip_special_tokens=True).strip()}\n", flush=True)

model.save_pretrained("trained", safe_serialization=True)
tok.save_pretrained("trained")
print("saved")
