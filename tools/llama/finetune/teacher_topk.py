"""Teacher distributions for token-level distillation (same tokenizer as the student: PLaMo 3, 107520 tokens).
usage: teacher_topk.py teacher_hf_dir teacher_lora_dir in.jsonl out.jsonl [K]
in.jsonl: {"prompt": ..., "target": ..., "kind": "teacher"|"student"} (target = a translation + "<|plamo:tag|>")
out.jsonl: the same plus "ids" (target token ids) and, per target position, the teacher's top-K token ids and
log-probabilities ("tk_ids", "tk_lp") and the teacher log-probability of the target token itself ("lp_tgt").
One teacher forward per example (teacher forcing, no generation), bf16 on the CPU."""
import json, math, os, sys, time, ctypes
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer
from peft import PeftModel

M, LORA, IN, OUT = sys.argv[1:5]
K = int(sys.argv[5]) if len(sys.argv) > 5 else 32
torch.set_num_threads(int(os.environ.get('THREADS', '4')))
_libc = ctypes.CDLL('libc.so.6')
tok = AutoTokenizer.from_pretrained(M, trust_remote_code=True)
model = AutoModelForCausalLM.from_pretrained(M, trust_remote_code=True, dtype=torch.bfloat16)
model.config.use_cache = False
if LORA != '-':
    model = PeftModel.from_pretrained(model, LORA)
    model = model.merge_and_unload()   # plain weights: faster forward
model.eval()
core = model.model if hasattr(model, 'model') else model.base_model.model.model

done = set()
if os.path.exists(OUT):
    for l in open(OUT, encoding='utf-8'):
        try: done.add(json.loads(l)['key'])
        except Exception: pass
rows = [json.loads(l) for l in open(IN, encoding='utf-8')]
t0 = time.time(); n = 0
with open(OUT, 'a', encoding='utf-8') as f, torch.inference_mode():
    for r in rows:
        key = r.get('key') or (r['prompt'] + '\u0001' + r['target'])
        if key in done: continue
        p = tok(r['prompt'], add_special_tokens=True)['input_ids']
        t = tok(r['target'], add_special_tokens=False)['input_ids']
        ids = (p + t)[:320]
        if len(ids) <= len(p): continue
        x = torch.tensor([ids])
        h = core(input_ids=x, use_cache=False)[0][0]           # hidden states
        pos = torch.arange(len(p) - 1, len(ids) - 1)            # positions that predict the target tokens
        logits = model.lm_head(h[pos]).float()
        lp = torch.log_softmax(logits, -1)
        top = lp.topk(K, -1)
        tgt = torch.tensor(ids[len(p):])
        out = dict(r)
        out['key'] = key
        out['ids'] = ids[len(p):]
        out['tk_ids'] = top.indices.tolist()
        out['tk_lp'] = [[round(v, 4) for v in row] for row in top.values.tolist()]
        out['lp_tgt'] = [round(v, 4) for v in lp[torch.arange(len(tgt)), tgt].tolist()]
        f.write(json.dumps(out, ensure_ascii=False) + '\n'); f.flush()
        n += 1
        if n % 20 == 0:
            _libc.malloc_trim(0)
            el = time.time() - t0
            print(f'{n} done, {el / n:.2f} s/example', flush=True)
print('done', n, flush=True)
