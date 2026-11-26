"""Training data for a PLaMo 3 Translate LoRA from the private offline dictionary.
usage: build_data.py phrases.tsv ja_ne_terms.tsv ne_ja_terms.tsv testset.tsv outdir
- pairs of the test set (either side) and their near duplicates are left out (no leakage into the evaluation);
- a dev set (120 pairs, also de-duplicated against the test set) is held out for choosing the checkpoint;
- each pair gives ja->ne and, unless marked ja>ne, ne->ja; 35% carry the dictionary terms they contain
  as a PFML3 glossary, 20% the previous 1-2 pairs of the same source as history (the order of the file);
- term pairs are added as short examples (a sample, so short outputs do not dominate).
Prompts are exactly the app's PFML3 (PlamoPrompt.java); the target is the translation + <|plamo:tag|>."""
import json, os, random, sys, unicodedata

TAG, MSG, KEY, VAL, IDX, ITEM = '<|plamo:tag|>', '<|plamo:msg|>', '<|plamo:key|>', '<|plamo:val|>', '<|plamo:index|>', '<|plamo:item|>'


def prompt(src, tgt, text, history=(), terms=(), polite=True):
    p = TAG + 'dataset' + MSG + 'translation'
    for s, t in history:
        p += TAG + 'input' + KEY + 'lang' + VAL + src + MSG + s + TAG + 'output' + KEY + 'lang' + VAL + tgt + MSG + t
    if terms:
        p += TAG + 'glossary' + ''.join(IDX + a + ITEM + b for a, b in terms)
    p += TAG + 'input' + KEY + 'lang' + VAL + src + MSG + text
    p += TAG + 'output' + KEY + 'lang' + VAL + tgt + (KEY + 'form' + VAL + 'polite' if polite and tgt == 'ja' else '') + MSG
    return p


def read_pairs(path):
    out = []
    for l in open(path, encoding='utf-8'):
        if l.startswith('#') or '\t' not in l:
            continue
        c = l.rstrip('\n').split('\t')
        if len(c) >= 2 and c[0].strip() and c[1].strip():
            out.append((c[0].strip(), unicodedata.normalize('NFC', c[1].strip()), len(c) > 2 and c[2].strip() == 'ja>ne'))
    return out


def grams(s, n=3):
    s = ''.join(ch for ch in s if not ch.isspace())
    return {s[i:i + n] for i in range(max(1, len(s) - n + 1))}


def near(a, b):
    ga, gb = grams(a), grams(b)
    return len(ga & gb) / max(1, len(ga | gb)) > 0.6


def find_terms(text, table, maxn=12):
    """longest first, a matched span is not reused (same rule as Glossary.termsJaNe)"""
    work, found = text, []
    for k in sorted(table, key=len, reverse=True):
        i = work.find(k)
        if i < 0 or len(k) < 2:
            continue
        found.append((i, k, table[k]))
        work = work.replace(k, '\0' * len(k))
    return [(k, v) for _, k, v in sorted(found)][:maxn]


def main():
    phrases, jn, nj, test, outdir = sys.argv[1:6]
    rnd = random.Random(7)
    pairs = read_pairs(phrases)
    jaNe = {a: b for a, b, _ in read_pairs(jn)}
    neJa = {a: b for a, b, _ in read_pairs(nj)}
    tests = [l.rstrip('\n').split('\t')[1] for l in open(test, encoding='utf-8')]
    tests_nfc = [unicodedata.normalize('NFC', t) for t in tests]

    def leaks(ja, ne, against):
        return any(ja == t or ne == t or near(ja, t) or near(ne, t) for t in against)

    keep, dropped = [], 0
    for i, (ja, ne, only) in enumerate(pairs):
        if leaks(ja, ne, tests_nfc):
            dropped += 1
            continue
        keep.append((i, ja, ne, only))
    idx = list(range(len(keep)))
    rnd.shuffle(idx)
    dev_ids = set(idx[:120])
    dev = [keep[i] for i in sorted(dev_ids)]
    dev_text = [x for _, a, b, _ in dev for x in (a, b)]
    train = [keep[i] for i in range(len(keep)) if i not in dev_ids and not leaks(keep[i][1], keep[i][2], dev_text)]
    by_pos = {p[0]: p for p in keep}   # history only from pairs that may be used

    ex = []
    for pos, ja, ne, only in train:
        prev = [by_pos[q] for q in (pos - 2, pos - 1) if q in by_pos]
        for src, tgt, text, out in (('ja', 'ne', ja, ne),) + ((() if only else (('ne', 'ja', ne, ja),))):
            hist, terms = [], []
            if prev and rnd.random() < 0.2:
                hist = [(p[1], p[2]) if src == 'ja' else (p[2], p[1]) for p in prev if not (src == 'ne' and p[3])]
            if rnd.random() < 0.35:
                terms = find_terms(text, jaNe if src == 'ja' else neJa)
            ex.append({'prompt': prompt(src, tgt, text, hist, terms), 'target': out + TAG, 'dir': src + tgt})
    for table, src, tgt in ((jaNe, 'ja', 'ne'), (neJa, 'ne', 'ja')):
        items = list(table.items())
        rnd.shuffle(items)
        for a, b in items[: len(items) // 2]:
            if a in tests_nfc or b in tests_nfc:
                continue
            ex.append({'prompt': prompt(src, tgt, a), 'target': b + TAG, 'dir': src + tgt + '-term'})
    rnd.shuffle(ex)
    os.makedirs(outdir, exist_ok=True)
    with open(os.path.join(outdir, 'train.jsonl'), 'w', encoding='utf-8') as f:
        for e in ex:
            f.write(json.dumps(e, ensure_ascii=False) + '\n')
    with open(os.path.join(outdir, 'dev.tsv'), 'w', encoding='utf-8') as f:
        for _, ja, ne, only in dev:
            f.write(f'ja\t{ja}\t{ne}\n')
            if not only:
                f.write(f'ne\t{ne}\t{ja}\n')
    kinds = {}
    for e in ex:
        kinds[e['dir']] = kinds.get(e['dir'], 0) + 1
    print(f'pairs {len(pairs)}, left out for the test set {dropped}, dev {len(dev)}, train pairs {len(train)}, examples {len(ex)} {kinds}')


if __name__ == '__main__':
    main()
