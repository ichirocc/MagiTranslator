#!/usr/bin/env python3
"""Draft source candidates for the bidirectional evaluation set (docs/spec_v2/16_eval_dataset.md §2, §8).

Reads a `#!jane phrases` file (default: dictionaries/phrases.tsv), tags each pair with one category using the
Stage-1 IR ports in metrics.py, and writes a draft TSV in the §3 column layout with an EMPTY reference and
review_status=UNVERIFIED. References must be written by native speakers (§4); the phrase book's own
translation is NOT used as a reference.

Each phrase pair goes to exactly one direction (stable hash), so ja->ne and ne->ja never share an original
(§0 principle 1). "ja>ne"-only lines go to the ja->ne pool only.

  python3 build_candidates.py [--phrases dictionaries/phrases.tsv] [--out tools/eval/testsets/draft_candidates.tsv]
                              [--per-category 30]
"""
import argparse
import hashlib
import os
import re
import sys
import unicodedata
from collections import OrderedDict, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M  # noqa: E402

COLUMNS = ["id", "direction", "category", "source", "reference", "critical_facts", "register", "origin",
           "in_user_dict", "review_status", "reviewers", "license", "notes"]

CAT_CODE = OrderedDict([
    ("numbers", "num"), ("negation", "neg"), ("question", "q"), ("modality", "mod"), ("proper_name", "pn"),
    ("honorific", "hon"), ("care_medical", "med"), ("admin", "adm"), ("long", "long"), ("omission", "omit"),
    ("daily", "daily"),
])

JA_HON = re.compile(r"(いらっしゃ|おっしゃ|召し上が|ご覧|申し上げ|伺|参り|おり|いただ|くださ|でございます|お[^\s]{1,6}になり)")
NE_HON = re.compile(r"(हजुर|तपाईं|तपाईँ|हुनुहुन्छ|नुहोस्|नुभयो)")
JA_MED = re.compile(r"(介護|看護|病院|病気|薬|痛|熱|血圧|診察|検査|入浴|排泄|食事介助|車いす|車椅子|おむつ|トイレ|骨折|けが|症状|手術)")
JA_ADM = re.compile(r"(在留|ビザ|パスポート|旅券|役所|市役所|区役所|届|申請|証明書|保険証|税|年金|住民|大使館|マイナンバー|手続)")
JA_TOPIC = re.compile(r"[はがも]")
JA_SENT_END = re.compile(r"[。！？!?]")


def _hash_dir(ja, ne):
    h = hashlib.sha1((ja + "\t" + ne).encode("utf-8")).digest()[0]
    return "ja-ne" if h % 2 == 0 else "ne-ja"


def categorize(ja, ne):
    """One category per pair, first match in CAT_CODE order. Uses the Japanese side (the stronger IR)."""
    a = M.analyze_ja(ja)
    if a["numbers"] or a["cur"]:
        return "numbers"
    if a["negated"]:
        return "negation"
    if a["question"] or a["wh"]:
        return "question"
    if a["mod"]:
        return "modality"
    if a["places"] or re.search(r"[ァ-ヴー]{2,}さん", ja):
        return "proper_name"
    if JA_HON.search(ja) or NE_HON.search(ne):
        return "honorific"
    if JA_MED.search(ja):
        return "care_medical"
    if JA_ADM.search(ja):
        return "admin"
    if len(JA_SENT_END.findall(ja)) >= 2 or len(ja) >= 45:
        return "long"
    if len(ja) <= 20 and not JA_TOPIC.search(ja) and not a["question"]:
        return "omission"
    return "daily"


def critical_facts(src, direction):
    a = M.analyze_ja(src) if direction == "ja-ne" else M.analyze_ne(src)
    f = []
    if a["negated"]:
        f.append("neg:yes")
    f += ["num:" + n for n in a["numbers"]]
    f += ["cur:" + c for c in sorted(a["cur"])]
    f += ["place:" + p for p in sorted(a["places"])]
    f += ["wh:" + k for k, _ in a["wh"]]
    f += ["mod:" + m for m in sorted(a["mod"])]
    if a["tense"] == "PAST":
        f.append("tense:past")
    return "|".join(f)


def read_phrases(path):
    rows = []
    with open(path, encoding="utf-8") as fh:
        first = fh.readline().lstrip("﻿").strip()
        if first != "#!jane phrases":
            raise ValueError(f"{path}: not a '#!jane phrases' file")
        for line in fh:
            line = line.rstrip("\n").rstrip("\r")
            if not line.strip() or line.startswith("#"):
                continue
            c = line.split("\t")
            if len(c) < 2 or not c[0].strip() or not c[1].strip():
                continue
            ja, ne = (unicodedata.normalize("NFC", x.strip()) for x in c[:2])
            rows.append((ja, ne, len(c) > 2 and c[2].strip() == "ja>ne"))
    return rows


def build(rows, per_category=None):
    pools = defaultdict(list)   # (direction, category) -> [(src, ja, ne)]
    seen = set()
    for ja, ne, ja_only in rows:
        if (ja, ne) in seen:
            continue
        seen.add((ja, ne))
        d = "ja-ne" if ja_only else _hash_dir(ja, ne)
        cat = categorize(ja, ne)
        src = ja if d == "ja-ne" else ne
        pools[(d, cat)].append(src)
    out, counts = [], OrderedDict()
    for d in ("ja-ne", "ne-ja"):
        pre = "jn" if d == "ja-ne" else "nj"
        for cat, code in CAT_CODE.items():
            items = sorted(pools.get((d, cat), []), key=lambda s: hashlib.sha1(s.encode("utf-8")).hexdigest())
            if per_category:
                items = items[:per_category]
            counts[(d, cat)] = len(items)
            for i, src in enumerate(items, 1):
                out.append(OrderedDict(
                    id=f"{pre}-{code}-{i:03d}", direction=d, category=cat, source=src, reference="",
                    critical_facts=critical_facts(src, d), register="", origin="dictionaries/phrases.tsv",
                    in_user_dict="1", review_status="UNVERIFIED", reviewers="", license="owner-published",
                    notes=""))
    return out, counts


def write_tsv(rows, path):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write("# DRAFT source candidates for the eval set (docs/spec_v2/16_eval_dataset.md). References are empty:\n")
        f.write("# native speakers write them (§4). Evaluation only: never use for training/tuning. in_user_dict=1 rows\n")
        f.write("# inflate dictionary effects; report with and without them.\n")
        f.write("\t".join(COLUMNS) + "\n")
        for r in rows:
            f.write("\t".join(r[c].replace("\t", " ") for c in COLUMNS) + "\n")


def main(argv=None):
    repo = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--phrases", default=os.path.join(repo, "dictionaries", "phrases.tsv"))
    ap.add_argument("--out", default=os.path.join(repo, "tools", "eval", "testsets", "draft_candidates.tsv"))
    ap.add_argument("--per-category", type=int, default=None, help="cap per direction x category")
    a = ap.parse_args(argv)
    rows, counts = build(read_phrases(a.phrases), a.per_category)
    write_tsv(rows, a.out)
    for (d, c), n in counts.items():
        print(f"{d}\t{c}\t{n}")
    print(f"total\t{len(rows)}\twrote {a.out}")


if __name__ == "__main__":
    main()
