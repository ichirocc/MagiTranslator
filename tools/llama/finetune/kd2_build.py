"""Distillation inputs from the teacher's and the student's translations of the same sources (plamo_cli output:
lang, source, translation, ...). Same PFML3 prompt as the app without glossary or history (polite Japanese).
usage: kd2_build.py teacher.tsv student.tsv out.jsonl
Dropped: empty translations and length ratios beyond 4x either way (a loop or a lost sentence); the student's
translations are otherwise all kept (on-policy: the teacher's distribution corrects them where they go wrong)."""
import json, sys
sys.path.insert(0, __file__.rsplit('/', 1)[0])
from build_data import prompt, TAG

teacher, student, out = sys.argv[1:4]


def rows(path):
    for l in open(path, encoding='utf-8', errors='replace'):   # a translation cut at the token limit can end inside a character
        c = l.rstrip('\n').split('\t')
        if len(c) >= 3 and c[0] in ('ja', 'ne'): yield c[0], c[1], c[2]


n = {'teacher': 0, 'student': 0, 'dropped': 0}
with open(out, 'w', encoding='utf-8') as f:
    for kind, path in (('teacher', teacher), ('student', student)):
        for src, text, tr in rows(path):
            tgt = 'ne' if src == 'ja' else 'ja'
            tr = tr.strip()
            ratio = (len(tr) + 1) / (len(text) + 1)
            if not tr or ratio > 4 or ratio < 0.25 or '\ufffd' in tr or '\ufffd' in text:
                n['dropped'] += 1; continue
            f.write(json.dumps({'prompt': prompt(src, tgt, text), 'target': tr + TAG, 'kind': kind, 'dir': src + tgt}, ensure_ascii=False) + '\n')
            n[kind] += 1
print(n)
