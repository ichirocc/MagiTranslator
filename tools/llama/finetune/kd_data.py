"""Sequence-level distillation data (Kim & Rush 2016) from the teacher's translations.
usage: kd_data.py teacher.tsv back.tsv real_train.jsonl out.jsonl [min_roundtrip]
teacher.tsv: PlamoHarness output (lang, source, teacher translation, ..., REVIEW flag)
back.tsv:    plamo_cli output of the teacher translations translated back (lang, text, back translation)
Kept: no hard validator issue and round-trip chrF(back, source) >= min (default 0.35). Each kept sentence gives
 - forward: source -> teacher translation (distillation),
 - reverse: teacher translation -> human source (back-translation: the target side is human text),
mixed with half of the real dictionary examples so that the first adapter's training is not forgotten."""
import collections, json, random, sys
sys.path.insert(0, __file__.rsplit('/', 1)[0])
from build_data import prompt, TAG


def chrf(h, r, N=4, beta=2.0):
    strip = lambda s: ''.join(c for c in s if not c.isspace() and c not in '。、！？!?,.।')
    h, r = strip(h), strip(r)
    if not h or not r: return 0.0
    P = R = 0; o = 0
    for n in range(1, N + 1):
        hc = collections.Counter(h[i:i + n] for i in range(len(h) - n + 1)); rc = collections.Counter(r[i:i + n] for i in range(len(r) - n + 1))
        ht, rt = sum(hc.values()), sum(rc.values())
        if not ht or not rt: continue
        m = sum(min(v, rc[k]) for k, v in hc.items()); P += m / ht; R += m / rt; o += 1
    if not o: return 0.0
    p, q = P / o, R / o
    return 0 if p + q == 0 else (1 + beta ** 2) * p * q / (beta ** 2 * p + q)


teacher, back, real, out = sys.argv[1:5]
minrt = float(sys.argv[5]) if len(sys.argv) > 5 else 0.35
backs = {}
for l in open(back, encoding='utf-8'):
    c = l.rstrip('\n').split('\t')
    if len(c) >= 3: backs[(c[0], c[1])] = c[2]
ex, stats = [], collections.Counter()
for l in open(teacher, encoding='utf-8'):
    c = l.rstrip('\n').split('\t')
    src, text, tr = c[0], c[1], c[2]
    tgt = 'ne' if src == 'ja' else 'ja'
    if len(c) > 5 and c[5] == 'REVIEW': stats['review'] += 1; continue
    b = backs.get((tgt, tr))
    if not tr or b is None: stats['missing'] += 1; continue
    if chrf(b, text) < minrt: stats['roundtrip'] += 1; continue
    stats['kept'] += 1
    ex.append({'prompt': prompt(src, tgt, text), 'target': tr + TAG, 'dir': 'kd-' + src + tgt})
    ex.append({'prompt': prompt(tgt, src, tr), 'target': text + TAG, 'dir': 'bt-' + tgt + src})
rnd = random.Random(11)
rows = [json.loads(l) for l in open(real, encoding='utf-8')]
rnd.shuffle(rows)
ex += rows[: len(rows) // 2]
rnd.shuffle(ex)
with open(out, 'w', encoding='utf-8') as f:
    for e in ex: f.write(json.dumps(e, ensure_ascii=False) + '\n')
print(dict(stats), 'examples', len(ex), collections.Counter(e['dir'][:2] for e in ex))
