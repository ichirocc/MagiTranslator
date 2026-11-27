"""Round-3 data from the manually reviewed MHLW pairs: drops (W/drops.txt, line numbers of mhlw_pairs.tsv),
small fixes, trailing English stripped from the Nepali side; test = the 3 held-out files.
usage: kd3_rebuild.py mhlw_pairs.tsv drops.txt real_plus_mhlw.unreviewed.jsonl out_dir"""
import json, re, sys
sys.path.insert(0, __file__.rsplit('/', 1)[0])
from build_ft_data import prompt, TAG
pairs, drops, oldreal, out = sys.argv[1:5]
TEST = {'04_5_000951031.docx', '18_19_Rev.4.12.2024__.xlsx', '19_001251306.xlsx'}
drop = {int(x) for x in open(drops).read().split()}
TRAIL = re.compile(r'(?<=[^A-Za-z\s])\s*[A-Za-z][A-Za-z ,.\'?()/-]*$')
train, test, nd, ns = [], [], 0, 0
for i, l in enumerate(open(pairs, encoding='utf-8'), 1):
    ja, ne, f = l.rstrip('\n').split('\t')
    if i in drop: nd += 1; continue
    ne = re.sub(r'[Ѐ-ӿ]', '', ne).replace('"हो" "हो"', '"हो"')
    if not re.search(r'[A-Za-z]', ja):
        m = TRAIL.search(ne)
        if m: ne = ne[:m.start()].rstrip(); ns += 1
    if re.search(r'[A-Za-z]{4,}', ne) and not re.search(r'[A-Za-z]{4,}', ja): nd += 1; continue  # English left inside
    (test if f in TEST else train).append((ja, ne))
with open(f'{out}/mhlw_test.tsv', 'w') as t:
    for ja, ne in test: t.write(f'ja\t{ja}\t{ne}\nne\t{ne}\t{ja}\n')
rows = []
for ja, ne in train:
    rows.append({'prompt': prompt('ja', 'ne', ja), 'target': ne + TAG, 'dir': 'mhlw-jane'})
    rows.append({'prompt': prompt('ne', 'ja', ne), 'target': ja + TAG, 'dir': 'mhlw-neja'})
with open(f'{out}/mhlw_train.jsonl', 'w') as t:
    for r in rows: t.write(json.dumps(r, ensure_ascii=False) + '\n')
with open(f'{out}/real_plus_mhlw.jsonl', 'w') as t:
    for l in open(oldreal, encoding='utf-8'):
        if 'mhlw' not in json.loads(l)['dir']: t.write(l)
    for r in rows: t.write(json.dumps(r, ensure_ascii=False) + '\n')
with open(f'{out}/kd3_src.tsv', 'w') as t:
    for ja, ne in train: t.write(f'ja\t{ja}\tmhlw\nne\t{ne}\tmhlw\n')
print('dropped', nd, 'english stripped', ns, 'train pairs', len(train), 'test pairs', len(test))
