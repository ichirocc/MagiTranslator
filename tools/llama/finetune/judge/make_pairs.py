"""Pairs of Nepali translations for an outside AI to judge (which one is better), in random A/B order.

  check.tsv   67 pairs from the two 80-sentence test sets, where the reference translation says which is better:
              the judge's agreement here decides whether its other answers are trusted (answers in check_key.tsv)
  kd.tsv      distillation sentences where the 2B teacher and the 0.8B student differ (no reference)
Each line: id<TAB>Japanese<TAB>A<TAB>B. The judge answers id<TAB>A|B|same, one line per pair (see prompt.md).
Usage: python3 make_pairs.py <dir with j6_*.tsv, j_2b_*.tsv, refs.tsv, t80_ref.tsv>
"""
import os, random, sys
here = os.path.dirname(os.path.abspath(__file__)); repo = os.path.join(here, '..', '..', '..', '..')
sys.path.insert(0, os.path.join(repo, 'tools', 'eval')); import metrics as M
w = sys.argv[1]
rnd = random.Random(11)
R = {}
for f in ['refs.tsv', 't80_ref.tsv']:
    for l in open(os.path.join(w, f), encoding='utf-8'):
        p = l.rstrip('\n').split('\t'); R[(f, p[1])] = p[2]
check, key = [], []
for s, rf in [('testset', 'refs.tsv'), ('t80_in', 't80_ref.tsv')]:
    st = {l.split('\t')[1]: l.rstrip('\n').split('\t')[2] for l in open(os.path.join(w, f'j6_{s}.tsv'), encoding='utf-8')}
    te = {l.split('\t')[1]: l.rstrip('\n').split('\t')[2] for l in open(os.path.join(w, f'j_2b_{s}.tsv'), encoding='utf-8')}
    for ja in st:
        if st[ja] == te[ja]: continue
        r = R[(rf, ja)]
        better = 'teacher' if M.chrf_pp_sentence(te[ja], r) > M.chrf_pp_sentence(st[ja], r) else 'student'
        a_is_teacher = rnd.random() < 0.5
        i = 'c%03d' % (len(check) + 1)
        check.append((i, ja, te[ja] if a_is_teacher else st[ja], st[ja] if a_is_teacher else te[ja]))
        key.append((i, 'A' if (better == 'teacher') == a_is_teacher else 'B', 'A=teacher' if a_is_teacher else 'A=student'))
d = os.path.join(repo, 'tools', 'llama', 'finetune', 'data')
st = {p[1]: p[2] for p in (l.rstrip('\n').split('\t') for l in open(os.path.join(d, 'kd2_student_translations.tsv'), encoding='utf-8')) if p[0] == 'ja' and len(p) > 2}
te = {p[1]: p[2] for p in (l.rstrip('\n').split('\t') for l in open(os.path.join(d, 'kd2_teacher_translations.tsv'), encoding='utf-8')) if p[0] == 'ja' and len(p) > 2}
kd, kdkey = [], []
for ja in st:
    if ja not in te or st[ja].strip() == te[ja].strip(): continue
    a_is_teacher = rnd.random() < 0.5
    i = 'k%03d' % (len(kd) + 1)
    kd.append((i, ja, te[ja] if a_is_teacher else st[ja], st[ja] if a_is_teacher else te[ja]))
    kdkey.append((i, 'A=teacher' if a_is_teacher else 'A=student'))
def wr(name, rows):
    with open(os.path.join(here, name), 'w', encoding='utf-8') as f:
        for r in rows: f.write('\t'.join(r) + '\n')
wr('check.tsv', check); wr('check_key.tsv', key); wr('kd.tsv', kd); wr('kd_key.tsv', kdkey)
print('check', len(check), 'kd', len(kd))
