"""Source sentences for distillation: conversational (the training pairs' sentence sources, both directions)
plus some care-domain monolingual text; nothing close to the dev, blind or test sets.
usage: kd_sources.py train.jsonl mono.tsv dev.tsv blind_src.tsv testset.tsv out.tsv [n_conv_per_lang] [n_mono]"""
import json, random, re, sys, unicodedata

train, mono, dev, blind, test, out = sys.argv[1:7]
NCONV = int(sys.argv[7]) if len(sys.argv) > 7 else 900
NMONO = int(sys.argv[8]) if len(sys.argv) > 8 else 300
MSG = re.compile(r'<\|plamo:tag\|>input<\|plamo:key\|>lang<\|plamo:val\|>(ja|ne)<\|plamo:msg\|>([^<]*)<\|plamo:tag\|>output')


def grams(s, n=3):
    s = ''.join(ch for ch in unicodedata.normalize('NFC', s) if not ch.isspace())
    return {s[i:i + n] for i in range(max(1, len(s) - n + 1))}


held = []
for path in (dev, blind, test):
    for l in open(path, encoding='utf-8'):
        c = l.rstrip('\n').split('\t')
        for x in c[1:] if c and c[0] in ('ja', 'ne') else c:
            if len(x) > 3: held.append(grams(x))
index = {}
for k, g in enumerate(held):
    for x in g: index.setdefault(x, []).append(k)


def near_held(s):
    g = grams(s); hits = {}
    for x in g:
        for k in index.get(x, ()): hits[k] = hits.get(k, 0) + 1
    return any(h / max(1, len(g | held[k])) > 0.5 for k, h in hits.items())


conv = {'ja': [], 'ne': []}
seen = set()
for l in open(train, encoding='utf-8'):
    r = json.loads(l)
    if r['dir'] not in ('jane', 'neja'): continue   # sentences, not single dictionary terms
    m = MSG.findall(r['prompt'])
    if not m: continue
    lang, text = m[-1]                              # the input (the last msg before the output tag)
    text = text.strip()
    if len(text) < 4 or (lang, text) in seen: continue
    seen.add((lang, text))
    if not near_held(text): conv[lang].append(text)
monos = []
for l in open(mono, encoding='utf-8'):
    c = l.rstrip('\n').split('\t')
    if len(c) == 2 and not near_held(c[1]): monos.append((c[0], c[1]))
rnd = random.Random(7)
rows = []
for lang in ('ja', 'ne'):
    rnd.shuffle(conv[lang]); rows += [(lang, t, 'conv') for t in conv[lang][:NCONV]]
rnd.shuffle(monos); rows += [(a, b, 'mono') for a, b in monos[:NMONO]]
with open(out, 'w', encoding='utf-8') as f:
    for lang, t, kind in rows: f.write(f'{lang}\t{t}\t{kind}\n')
print('conv ja', len(conv['ja']), 'ne', len(conv['ne']), 'mono', len(monos), '-> kept', len(rows))
