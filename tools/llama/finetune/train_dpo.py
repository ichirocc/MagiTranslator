"""DPO (+ SFT on the chosen side) on top of a PLaMo LoRA adapter, from tools/distill hard negatives.
usage: train_dpo.py model_dir adapter_dir pairs.jsonl dev.jsonl out_dir [epochs]
pairs: {"prompt": PFML3 prompt, "chosen": text+tag, "rejected": text+tag}
- reference = the starting adapter (its log-probs are computed once before training, so only one model is held);
- loss = DPO(beta) + ALPHA * NLL(chosen) per token, so fluency does not drift while the trap is pushed down;
- reports the preference accuracy on dev pairs (chosen more likely than rejected) before and after.
CPU (bf16, AMX) or a CUDA GPU."""
import json, math, os, random, sys, time
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

M, ADA, data, dev, out = sys.argv[1:6]
epochs = float(sys.argv[6]) if len(sys.argv) > 6 else 1.0
BETA, ALPHA, LR = float(os.environ.get('BETA', '0.1')), float(os.environ.get('ALPHA', '0.2')), float(os.environ.get('LR', '2e-5'))
torch.manual_seed(0); torch.set_num_threads(4)
DEV = 'cuda' if torch.cuda.is_available() else 'cpu'
DT = torch.bfloat16 if DEV == 'cpu' or torch.cuda.is_bf16_supported() else torch.float32
tok = AutoTokenizer.from_pretrained(M, trust_remote_code=True)
base = AutoModelForCausalLM.from_pretrained(M, trust_remote_code=True, dtype=DT).to(DEV)
base.config.use_cache = False
model = PeftModel.from_pretrained(base, ADA, is_trainable=True)
core = model.base_model.model

def enc(p, t):
    a = tok(p, add_special_tokens=True)['input_ids']; b = tok(t, add_special_tokens=False)['input_ids']
    return (a + b)[:256], ([-100] * len(a) + b)[:256]

def load(path):
    rows = [json.loads(l) for l in open(path, encoding='utf-8')]
    return [(enc(r['prompt'], r['chosen']), enc(r['prompt'], r['rejected'])) for r in rows]

def logp(seqs):
    """sum of target-token log-probs and target-token counts, one per sequence"""
    n = (max(len(s[0]) for s in seqs) + 31) // 32 * 32
    ids = torch.full((len(seqs), n), tok.pad_token_id or 3, dtype=torch.long); lab = torch.full((len(seqs), n), -100, dtype=torch.long)
    att = torch.zeros((len(seqs), n), dtype=torch.long)
    for j, (a, b) in enumerate(seqs):
        ids[j, :len(a)] = torch.tensor(a); lab[j, :len(b)] = torch.tensor(b); att[j, :len(a)] = 1
    ids, lab, att = ids.to(DEV), lab.to(DEV), att.to(DEV)
    h = core.model(input_ids=ids, attention_mask=att, use_cache=False)[0][:, :-1]
    y = lab[:, 1:]; sel = y != -100
    lp = torch.log_softmax(core.lm_head(h[sel]).float(), -1).gather(1, y[sel].unsqueeze(1)).squeeze(1)
    row = sel.nonzero()[:, 0]
    tot = torch.zeros(len(seqs), device=DEV).index_add(0, row, lp)
    cnt = torch.zeros(len(seqs), device=DEV).index_add(0, row, torch.ones_like(lp))
    return tot, cnt

@torch.no_grad()
def ref_of(pairs, B=8):
    model.eval(); out = []
    for i in range(0, len(pairs), B):
        chunk = pairs[i:i + B]
        c, _ = logp([p[0] for p in chunk]); r, _ = logp([p[1] for p in chunk])
        out += list(zip(c.tolist(), r.tolist()))
    return out

def acc(pairs):
    return sum(1 for c, r in ref_of(pairs) if c > r) / max(1, len(pairs))

train, devp = load(data), load(dev)
print(f'{len(train)} train pairs, {len(devp)} dev pairs, device {DEV}', flush=True)
t0 = time.time(); ref = ref_of(train); print(f'reference log-probs {time.time() - t0:.0f}s; dev preference accuracy before {acc(devp):.3f}', flush=True)
B = 4
order = list(range(len(train))); total = math.ceil(len(order) / B * epochs)
opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=LR, weight_decay=0.0)
sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / 10) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / max(1, total)))))
rnd, step, hist = random.Random(0), 0, []
model.train()
while step < total:
    rnd.shuffle(order)
    for k in range(0, len(order), B):
        ix = order[k:k + B]
        lc, nc = logp([train[i][0] for i in ix]); lr_, _ = logp([train[i][1] for i in ix])
        rc = torch.tensor([ref[i][0] for i in ix], device=DEV); rr = torch.tensor([ref[i][1] for i in ix], device=DEV)
        z = BETA * ((lc - rc) - (lr_ - rr))
        loss = -torch.nn.functional.logsigmoid(z).mean() + ALPHA * (-(lc / nc).mean())
        loss.backward(); torch.nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad], 1.0)
        opt.step(); sched.step(); opt.zero_grad(set_to_none=True); step += 1
        hist.append((loss.item(), (z > 0).float().mean().item()))
        if step % 10 == 0 or step == 1:
            l = hist[-10:]; el = time.time() - t0
            print(f'step {step}/{total} loss {sum(a for a, _ in l) / len(l):.3f} margin>0 {sum(b for _, b in l) / len(l):.2f} {el / step:.1f}s/step', flush=True)
        if step >= total: break
model.save_pretrained(out)
print(f'dev preference accuracy after {acc(devp):.3f}', flush=True)
print('done', flush=True)
