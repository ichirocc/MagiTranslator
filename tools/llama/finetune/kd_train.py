"""Token-level distillation into the student's LoRA (PLaMo 3 Translate 0.8B), CPU bf16 (AMX).
usage: kd_train.py student_hf_dir init_lora_dir real.jsonl kd.jsonl out_dir steps
real.jsonl: dictionary pairs {"prompt","target"} -> cross-entropy (replay: keeps what the adapter learned)
kd.jsonl:   teacher_topk.py output; "kind": "teacher" -> forward KL to the teacher's top-K distribution on the
            teacher's translation (word-level KD on sequence-level KD data, Kim & Rush 2016);
            "kind": "student" -> generalized JSD (beta 0.5) on the student's own greedy translation, the
            sequence it actually produces in the app (on-policy distillation, GKD: Agarwal et al. 2024).
Both distributions are taken on the teacher's top-K tokens and renormalised there.
Each step: 8 KD examples + 8 real examples (effective 16). LR from env LR (default 1e-4), cosine."""
import json, math, os, random, sys, time, ctypes
import torch
import torch.nn.functional as F
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

M, INIT, REAL, KD, OUT = sys.argv[1:6]
STEPS = int(sys.argv[6])
LR = float(os.environ.get('LR', '1e-4'))
ALPHA_CE = float(os.environ.get('ALPHA_CE', '1.0'))   # weight of the real-pair cross-entropy
torch.manual_seed(0)
torch.set_num_threads(int(os.environ.get('THREADS', '4')))
_libc = ctypes.CDLL('libc.so.6')
tok = AutoTokenizer.from_pretrained(M, trust_remote_code=True)
base = AutoModelForCausalLM.from_pretrained(M, trust_remote_code=True, dtype=torch.bfloat16)
base.config.use_cache = False
model = PeftModel.from_pretrained(base, INIT, is_trainable=True)
model.print_trainable_parameters()
core = model.base_model.model

real = []
for l in open(REAL, encoding='utf-8'):
    r = json.loads(l)
    p = tok(r['prompt'], add_special_tokens=True)['input_ids']; t = tok(r['target'], add_special_tokens=False)['input_ids']
    real.append(((p + t)[:256], len(p)))
kd = []
for l in open(KD, encoding='utf-8'):
    r = json.loads(l)
    p = tok(r['prompt'], add_special_tokens=True)['input_ids']
    ids = p + r['ids']
    if len(ids) > 320: continue
    kd.append((ids, len(p), r['kind'], r['tk_ids'], r['tk_lp']))
print(f'{len(real)} real, {len(kd)} kd ({sum(1 for x in kd if x[2] == "teacher")} teacher, {sum(1 for x in kd if x[2] == "student")} student)', flush=True)

params = [p for p in model.parameters() if p.requires_grad]
opt = torch.optim.AdamW(params, lr=LR, weight_decay=0.0)
warm = 20
sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / max(1, STEPS)))))

def batch_hidden(seqs):
    n = max(len(s) for s in seqs); n = (n + 31) // 32 * 32
    ids = torch.full((len(seqs), n), tok.pad_token_id or 3, dtype=torch.long)
    att = torch.zeros((len(seqs), n), dtype=torch.long)
    for j, s in enumerate(seqs):
        ids[j, :len(s)] = torch.tensor(s); att[j, :len(s)] = 1
    return core.model(input_ids=ids, attention_mask=att, use_cache=False)[0]

def real_loss(items):
    h = batch_hidden([s for s, _ in items])
    hs, ys = [], []
    for j, (s, pl) in enumerate(items):
        pos = torch.arange(pl - 1, len(s) - 1); hs.append(h[j, pos]); ys.append(torch.tensor(s[pl:]))
    logits = core.lm_head(torch.cat(hs)).float()
    return F.cross_entropy(logits, torch.cat(ys))

def kd_loss(items):
    h = batch_hidden([x[0] for x in items])
    total, count, fkl, jsd = 0.0, 0, [], []
    for j, (s, pl, kind, tk_ids, tk_lp) in enumerate(items):
        pos = torch.arange(pl - 1, len(s) - 1)
        lps = torch.log_softmax(core.lm_head(h[j, pos]).float(), -1)          # student, full vocabulary
        idx = torch.tensor(tk_ids)                                            # [T, K]
        lt = torch.tensor(tk_lp)                                              # teacher log-probs on its top-K
        lt = lt - torch.logsumexp(lt, -1, keepdim=True)                       # renormalised on the top-K
        ls = torch.gather(lps, 1, idx); ls = ls - torch.logsumexp(ls, -1, keepdim=True)
        pt, ps = lt.exp(), ls.exp()
        if kind == 'teacher':
            loss = (pt * (lt - ls)).sum(-1).mean()                           # KL(teacher || student)
            fkl.append(loss.item())
        else:
            lm = torch.log(0.5 * pt + 0.5 * ps + 1e-12)
            loss = 0.5 * (pt * (lt - lm)).sum(-1).mean() + 0.5 * (ps * (ls - lm)).sum(-1).mean()   # JSD(0.5)
            jsd.append(loss.item())
        total = total + loss; count += 1
    return total / max(1, count), fkl, jsd

model.train()
rnd = random.Random(0)
t0 = time.time(); hist = []
for step in range(1, STEPS + 1):
    kb = rnd.sample(kd, 8); kb.sort(key=lambda x: len(x[0]))
    rb = rnd.sample(real, 8); rb.sort(key=lambda x: len(x[0]))
    lk, fkl, jsd = kd_loss(kb[:4]); (lk / 2).backward()
    lk2, fkl2, jsd2 = kd_loss(kb[4:]); (lk2 / 2).backward()
    lr_ = real_loss(rb); (ALPHA_CE * lr_).backward()
    torch.nn.utils.clip_grad_norm_(params, 1.0)
    opt.step(); sched.step(); opt.zero_grad(set_to_none=True)
    hist.append((lk.item() + lk2.item()) / 2 + 0 * lr_.item()); hist.append(lr_.item())
    if step % 5 == 0: _libc.malloc_trim(0)
    if step % 10 == 0 or step == 1:
        el = time.time() - t0
        print(f'step {step}/{STEPS} kd {((lk + lk2) / 2).item():.4f} (fKL {sum(fkl + fkl2) / max(1, len(fkl + fkl2)):.4f} JSD {sum(jsd + jsd2) / max(1, len(jsd + jsd2)):.4f}) ce {lr_.item():.3f} lr {sched.get_last_lr()[0]:.1e} {el / step:.1f}s/step', flush=True)
    if step % 100 == 0 or step == STEPS:
        model.save_pretrained(os.path.join(OUT, f'step{step}'))
print('done', flush=True)
