"""LoRA on PLaMo 3 Translate 0.8B (bf16 on the CPU: AMX, or a CUDA GPU when present), loss on the translation tokens only.
GPU: bf16 when the GPU supports it, float32 otherwise (T4 of the free Colab tier).
usage: train_lora.py model_dir train.jsonl out_dir [epochs] [max_steps]
Batches are sorted by length (little padding); no KV cache (the remote code's cache path is not used)."""
import json, math, os, random, sys, time
import ctypes
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import LoraConfig, get_peft_model

M, data, out = sys.argv[1:4]
epochs = float(sys.argv[4]) if len(sys.argv) > 4 else 1.0
max_steps = int(sys.argv[5]) if len(sys.argv) > 5 else 0
torch.manual_seed(0)
_libc = ctypes.CDLL('libc.so.6')
torch.set_num_threads(4)
tok = AutoTokenizer.from_pretrained(M, trust_remote_code=True)
DEV = 'cuda' if torch.cuda.is_available() else 'cpu'
DT = torch.bfloat16 if DEV == 'cpu' or torch.cuda.is_bf16_supported() else torch.float32
model = AutoModelForCausalLM.from_pretrained(M, trust_remote_code=True, dtype=DT).to(DEV)
print(f'device {DEV}, dtype {DT}', flush=True)
model.config.use_cache = False
cfg = LoraConfig(r=16, lora_alpha=32, lora_dropout=0.05, target_modules=['qkv_proj', 'o_proj', 'gate_up_proj', 'down_proj'], task_type='CAUSAL_LM')
if os.environ.get('GC') == '1':   # recompute activations in the backward pass (2B on 15 GB of RAM)
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={'use_reentrant': False})
    model.enable_input_require_grads()
model = get_peft_model(model, cfg)
model.print_trainable_parameters()

rows = [json.loads(l) for l in open(data, encoding='utf-8')]
examples = []
for r in rows:
    p = tok(r['prompt'], add_special_tokens=True)['input_ids']      # BOS + prompt, as llama.cpp
    t = tok(r['target'], add_special_tokens=False)['input_ids']
    ids = (p + t)[:256]
    labels = ([-100] * len(p) + t)[:256]
    examples.append((ids, labels))
B, ACC = int(os.environ.get('B', '8')), int(os.environ.get('ACC', '2'))   # effective batch 16 (2B: B=4 ACC=4 for 15 GB of RAM)
order = sorted(range(len(examples)), key=lambda i: len(examples[i][0]))
batches = [order[i:i + B] for i in range(0, len(order), B)]
steps_per_epoch = math.ceil(len(batches) / ACC)
total = max_steps or int(steps_per_epoch * epochs)
LR = float(os.environ.get('LR', '2e-4'))   # lower for a second stage on top of an adapter (RESUME=dir:0)
opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=LR, weight_decay=0.0)
warm = 30
sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / warm) * 0.5 * (1 + math.cos(math.pi * min(1.0, s / max(1, total)))))
print(f'{len(examples)} examples, {len(batches)} batches, {steps_per_epoch} steps/epoch, training {total} steps', flush=True)

def collate(ix):
    n = max(len(examples[i][0]) for i in ix)
    n = (n + 31) // 32 * 32   # few distinct shapes: oneDNN caches kernels and buffers per shape (memory grew per new shape)
    ids = torch.full((len(ix), n), tok.pad_token_id or 3, dtype=torch.long)
    lab = torch.full((len(ix), n), -100, dtype=torch.long)
    att = torch.zeros((len(ix), n), dtype=torch.long)
    for j, i in enumerate(ix):
        a, b = examples[i]
        ids[j, :len(a)] = torch.tensor(a); lab[j, :len(b)] = torch.tensor(b); att[j, :len(a)] = 1
    return ids.to(DEV), lab.to(DEV), att.to(DEV)

# The output layer only where there is a target token: full logits are batch x length x 107520 floats
# (about 0.9 GB per batch plus gradients), which exhausted the RAM on long batches.
core = model.base_model.model   # Plamo3ForCausalLM with LoRA layers inside
def target_loss(ids, lab, att):
    h = core.model(input_ids=ids, attention_mask=att, use_cache=False)[0]   # last hidden states
    h, y = h[:, :-1], lab[:, 1:]   # position t predicts token t+1
    sel = y != -100
    logits = core.lm_head(h[sel]).float()
    return torch.nn.functional.cross_entropy(logits, y[sel])

resume = os.environ.get('RESUME')   # "dir:step": LoRA weights of a checkpoint, schedule continued from that step
start = 0
if resume:
    from safetensors.torch import load_file
    rdir, start = resume.rsplit(':', 1); start = int(start)
    from peft import set_peft_model_state_dict
    set_peft_model_state_dict(model, load_file(os.path.join(rdir, 'adapter_model.safetensors'), device=DEV))
    for _ in range(start): sched.step()
    print(f'resumed from {rdir} at step {start}', flush=True)

def rss_gb():
    return int(open('/proc/self/statm').read().split()[1]) * os.sysconf('SC_PAGE_SIZE') / 2**30

model.train()
rnd = random.Random(0)
step, t0, run = start, time.time(), []
while step < total:
    rnd.shuffle(batches)
    for k in range(0, len(batches), ACC):
        for ix in batches[k:k + ACC]:
            ids, lab, att = collate(ix)
            loss = target_loss(ids, lab, att) / ACC
            loss.backward()
            run.append(loss.item() * ACC)
        torch.nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad], 1.0)
        opt.step(); sched.step(); opt.zero_grad(set_to_none=True)
        step += 1
        if step % 5 == 0 and DEV == 'cpu': _libc.malloc_trim(0)   # give freed buffers back (glibc keeps them otherwise)
        if step % 10 == 0 or step == 1:
            el = time.time() - t0
            done_steps = step - start
            print(f'step {step}/{total} loss {sum(run[-20:]) / len(run[-20:]):.3f} lr {sched.get_last_lr()[0]:.2e} {el / done_steps:.1f}s/step eta {el / done_steps * (total - step) / 60:.0f} min rss {rss_gb():.1f} GB', flush=True)
        if step % 100 == 0 or step == total:
            model.save_pretrained(os.path.join(out, f'step{step}'))
        if step >= total:
            break
print('done', flush=True)
