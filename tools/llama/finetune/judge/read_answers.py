"""Reads an outside AI's judgments (id<TAB>A|B|same).
  check <answers>  agreement with the references on check.tsv, with the chance level and a one-sided binomial p
  kd <answers>     kd_sft.jsonl (picked translation) and kd_dpo.jsonl (picked / rejected) for train_lora.py / train_dpo.py
"""
import json, math, os, sys
here = os.path.dirname(os.path.abspath(__file__))
def tsv(n): return [l.rstrip('\n').split('\t') for l in open(os.path.join(here, n) if not os.path.isabs(n) else n, encoding='utf-8') if l.strip()]
def answers(n):
    out = {}
    for p in tsv(n):
        if len(p) >= 2 and p[1].strip() in ('A', 'B', 'same'): out[p[0].strip()] = p[1].strip()
    return out
mode, f = sys.argv[1], sys.argv[2]
ans = answers(f)
if mode == 'check':
    key = {p[0]: p[1] for p in tsv('check_key.tsv')}
    dec = [(ans[i], key[i]) for i in key if i in ans and ans[i] != 'same']
    ok = sum(a == k for a, k in dec); n = len(dec)
    p = sum(math.comb(n, j) for j in range(ok, n + 1)) / 2 ** n if n else 1.0
    print(f'answered {len(ans)}/{len(key)}, decided {n}, agree {ok} ({100 * ok / max(1, n):.0f}%), chance 50%, p={p:.3f}')
    print('trust: YES' if n >= 40 and p < 0.01 and ok / n >= 0.65 else 'trust: NO (do not use this judge)')
else:
    P = lambda x: "<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>ja<|plamo:msg|>%s<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ne<|plamo:msg|>" % x
    rows = {p[0]: p for p in tsv('kd.tsv')}
    sft, dpo = [], []
    for i, a in ans.items():
        if i not in rows or a == 'same': continue
        _, ja, A, B = rows[i]
        good, bad = (A, B) if a == 'A' else (B, A)
        sft.append({"prompt": P(ja), "target": good + "<|plamo:tag|>", "dir": "jane-judged"})
        dpo.append({"prompt": P(ja), "chosen": good + "<|plamo:tag|>", "rejected": bad + "<|plamo:tag|>"})
    for n, rows_ in [('kd_sft.jsonl', sft), ('kd_dpo.jsonl', dpo)]:
        with open(os.path.join(here, n), 'w', encoding='utf-8') as fo:
            for r in rows_: fo.write(json.dumps(r, ensure_ascii=False) + '\n')
    print(f'sft {len(sft)}, dpo {len(dpo)} (same/unknown skipped: {len(ans) - len(sft)})')
