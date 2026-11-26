"""Untranslated care-domain sentences for sequence-level distillation (the teacher writes the translation).
usage: extract_mono.py scratchpad_dir phrases.tsv testset.tsv dev.tsv out.tsv
Sources (private, already collected for the dictionary): 介護福祉士国家試験 第38回 text, Joint care news (ja),
Nepali care news, textbook sentences without a translation. Sentences that are (nearly) in the dictionary,
the test set or the dev set are left out: the teacher is only needed where there is no reference."""
import glob, html, os, random, re, sys, unicodedata

S, phrases, test, dev, out = sys.argv[1:6]
JA = re.compile(r'[぀-ヿ一-鿿]')
DEV = re.compile(r'[ऀ-ॿ]')


def ja_sentences(text):
    text = unicodedata.normalize('NFKC', text)
    text = re.sub(r'[ \t　]+', '', text)          # the exam PDF text is spread over many spaced columns
    for part in re.split(r'(?<=[。！？])|\n', text):
        s = part.strip()
        if 10 <= len(s) <= 90 and len(JA.findall(s)) >= 0.6 * len(s) and s.endswith(('。', '？', '！')) \
                and not re.search(r'[|｜《》【】]|問題|選びなさい|https?:|第[0-9０-９一二三四五六七八九十]+[条項号]|^[(（0-9０-９]', s) \
                and sum(ch.isdigit() for ch in s) <= 0.1 * len(s):
            yield s


def ne_sentences(text):
    text = unicodedata.normalize('NFC', text)
    for part in re.split(r'(?<=[।?!])|\n', text):
        s = re.sub(r'\s+', ' ', part).strip()
        if 15 <= len(s) <= 160 and len(DEV.findall(s)) >= 0.6 * len(s.replace(' ', '')) and s.endswith(('।', '?', '!')) \
                and sum(ch.isdigit() or '\u0966' <= ch <= '\u096f' for ch in s) <= 0.1 * len(s):
            yield s


def html_text(path):
    s = open(path, encoding='utf-8', errors='ignore').read()
    s = re.sub(r'(?s)<(script|style).*?</\1>', '', s)
    return html.unescape(re.sub(r'<[^>]+>', '\n', s))


ja, ne = [], []
ja += list(ja_sentences(open(os.path.join(S, 'exam/no38_all.txt'), encoding='utf-8', errors='ignore').read()))
for p in sorted(glob.glob(os.path.join(S, 'crawl/jk/*.html'))):
    ja += list(ja_sentences(html_text(p)))
for l in open(os.path.join(S, 'tb_sent.tsv'), encoding='utf-8'):
    c = l.rstrip('\n').split('\t')
    if len(c) == 2: ja += list(ja_sentences(c[1] if c[1].endswith('。') else c[1] + '。'))
ne += list(ne_sentences(open(os.path.join(S, 'crawl/ne_all.txt'), encoding='utf-8', errors='ignore').read()))


def grams(s, n=3):
    s = ''.join(ch for ch in s if not ch.isspace())
    return {s[i:i + n] for i in range(max(1, len(s) - n + 1))}


known = []
for path in (phrases, test, dev):
    for l in open(path, encoding='utf-8'):
        if l.startswith('#'): continue
        for c in l.rstrip('\n').split('\t'):
            if JA.search(c) or DEV.search(c): known.append(grams(unicodedata.normalize('NFC', c)))
index = {}
for k, g in enumerate(known):
    for x in g: index.setdefault(x, []).append(k)


def is_known(s):
    g = grams(s)
    hits = {}
    for x in g:
        for k in index.get(x, ()): hits[k] = hits.get(k, 0) + 1
    return any(h / max(1, len(g | known[k])) > 0.5 for k, h in hits.items())


rnd = random.Random(3)
res = {}
for lang, items, cap in (('ja', ja, 1400), ('ne', ne, 1000)):
    seen, keep = set(), []
    for s in items:
        if s in seen: continue
        seen.add(s)
        if not is_known(s): keep.append(s)
    rnd.shuffle(keep)
    res[lang] = keep[:cap]
    print(lang, 'candidates', len(items), 'unique+new', len(keep), 'kept', len(res[lang]))
with open(out, 'w', encoding='utf-8') as f:
    for lang in ('ja', 'ne'):
        for s in res[lang]: f.write(f'{lang}\t{s}\n')
