#!/usr/bin/env python3
"""Builds ja<->ne distillation data (MHD SFT, ChatML SFT, DPO hard negatives) from the MagiTranslator dictionaries.

PC-side tool: the app does not train. Design from the owner's spec (2026-11-27); this version reads the
repository's own dictionaries and keeps only hard negatives that are verifiably wrong.

Inputs
  --phrases  dictionaries/phrases.tsv (#!jane phrases: ja<TAB>ne[<TAB>ja>ne])        (default)
  --input-dir  extra TSVs with a header row (id, ja_spoken, ne_polite, ... 8-10 columns; optional)
Outputs (in --out)
  sft_mhd_grouped.jsonl     one source, several valid hypotheses (both directions)
  sft_chatml.jsonl          flattened ChatML messages (both directions)
  dpo_hard_negatives.jsonl  prompt / chosen / rejected / trap
  dataset_report.json       counts
Safety
  * a rejected Nepali candidate is kept only if the app's validator (tools/eval/metrics.py) marks it HARD,
    or it is an honorific clash (तपाईं -> तिमी), which the validator does not judge;
    a rejected Japanese candidate is kept only if it differs in negation or in a number
  * replacements are word-bounded (no "काम " -> "कातपाईं ")
  * slot substitution only when a token is present in both languages
"""
import argparse
import csv
import itertools
import json
import random
import re
import sys
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "eval"))
import metrics as M  # noqa: E402

NE_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")
NE_CH = "ऀ-ॣ०-ॿ"


def norm(s, unify_digits):
    s = unicodedata.normalize("NFC", (s or "").strip()).replace("﻿", "").replace("​", "")
    s = re.sub(r"[ \t]+", " ", s)
    return s.translate(NE_DIGITS) if unify_digits else s


@dataclass
class Entry:
    id: str
    ja: str
    ne: str
    en: str = ""
    category: str = ""
    scene: str = ""
    one_way: bool = False   # ja>ne only: the Nepali side is shared with another entry
    tags: list = field(default_factory=list)


def load_phrases(path, unify):
    out = []
    for i, line in enumerate(path.read_text(encoding="utf-8").splitlines()):
        if not line or line.startswith("#") or "\t" not in line:
            continue
        c = line.split("\t")
        out.append(Entry(f"PH{i}", norm(c[0], unify), norm(c[1], unify), one_way=len(c) > 2 and c[2].strip() == "ja>ne"))
    return out


def load_dir(d, unify):
    out = []
    for p in sorted(d.glob("*.tsv")):
        with p.open(encoding="utf-8") as f:
            for i, row in enumerate(csv.DictReader(f, delimiter="\t")):
                row = {(k or "").strip(): v for k, v in row.items()}
                ja, ne = norm(row.get("ja_spoken"), unify), norm(row.get("ne_polite"), unify)
                if ja and ne:
                    out.append(Entry(row.get("id") or f"{p.stem}_{i}", ja, ne, norm(row.get("en_anchor"), False),
                                     row.get("category") or row.get("phase") or row.get("season_time") or "",
                                     row.get("scene_group") or ""))
    return out


# --- slot substitution (ja, en, ne), applied only when the token occurs in both languages
SLOTS = {
    "resident": [("鈴木様", "Mr./Ms. Suzuki", "सुजुकी-ज्यू"), ("佐藤様", "Mr./Ms. Sato", "सातो-ज्यू"),
                 ("田中様", "Mr./Ms. Tanaka", "तानाका-ज्यू"), ("高橋様", "Mr./Ms. Takahashi", "ताकाहासी-ज्यू"),
                 ("山田様", "Mr./Ms. Yamada", "यामादा-ज्यू"), ("伊藤様", "Mr./Ms. Ito", "इतो-ज्यू")],
    "beverage": [("お茶", "tea", "चिया"), ("お水", "water", "पानी")],
    "day": [("火曜日", "Tuesday", "मंगलबार"), ("水曜日", "Wednesday", "बुधबार"), ("金曜日", "Friday", "शुक्रबार")],
}


def expand_slots(e, rng, cap):
    hits = []
    for cands in SLOTS.values():
        for c in cands:
            if c[0] in e.ja and c[2] in e.ne:
                hits.append((c, [x for x in cands if x != c]))
                break
    if not hits:
        return [e]
    combos = list(itertools.product(*[[h[0]] + h[1] for h in hits]))[1:]
    rng.shuffle(combos)
    out = [e]
    for k, combo in enumerate(combos[:cap], 1):
        ja, en, ne = e.ja, e.en, e.ne
        for (orig, _), new in zip(hits, combo):
            ja, ne = ja.replace(orig[0], new[0]), ne.replace(orig[2], new[2])
            en = en.replace(orig[1], new[1]) if en else en
        out.append(Entry(f"{e.id}_s{k}", ja, ne, en, e.category, e.scene, e.one_way, e.tags + ["slot"]))
    return out


# --- register paraphrases (valid alternative targets for MHD)
JA_PARA = [("確認してください。", ["確認をお願いします。", "確認してもらえますか？"]),
           ("教えてください。", ["教えていただけますか？", "教えてもらえますか？"]),
           ("報告します。", ["報告いたします。", "お伝えします。"])]
NE_PARA = [("जाँच गर्नुहोस्।", ["चेक गर्नुहोस्।", "जाँच गरिदिनुहोस्।"]),
           ("रिपोर्ट गर्छु।", ["जानकारी दिन्छु।"])]


def hyps(text, rules):
    out = [text]
    for src, reps in rules:
        if text.endswith(src):
            out += [text[: -len(src)] + r for r in reps if text[: -len(src)] + r not in out]
    return out


def wsub(text, src, dst):
    """Word-bounded single replacement for Nepali."""
    return re.sub(r"(?<![" + NE_CH + "])" + re.escape(src) + r"(?![" + NE_CH + "])", dst, text, count=1)


NE_TRAPS = [
    ("polarity", [("नचलाउनुहोस्", "चलाउनुहोस्"), ("नदिनुहोस्", "दिनुहोस्"), ("नगर्नुहोस्", "गर्नुहोस्"),
                  ("भएको छैन", "भएको छ"), ("सकिएको छैन", "सकिएको छ"), ("सक्दिनँ", "सक्छु"), ("गर्दिनँ", "गर्छु"),
                  ("बुझिनँ", "बुझें"), ("चाहिँदैन", "चाहिन्छ"), ("गर्नुहोस्", "नगर्नुहोस्")]),
    ("actor", [("खुवाउनुहोस्", "खानुहोस्"), ("म", "तपाईं"), ("मैले", "तपाईंले"), ("गर्छु", "गर्नुहोस्")]),
    ("vocab", [("चिलाएको", "दुखेको"), ("चिलाउने", "दुख्ने"), ("ब्रेक", "बिदा"), ("विश्राम", "बिदा")]),
]


def ne_negatives(ja, ne):
    out = []
    for trap, pairs in NE_TRAPS:
        for src, dst in pairs:
            cand = wsub(ne, src, dst)
            if cand != ne:
                out.append((cand, trap))
                break
    m = re.search(r"\d+(?:\.\d+)?", ne)
    if m:
        out.append((ne[: m.start()] + ("10" if m.group() != "10" else "50") + ne[m.end():], "number"))
    kept = []
    for cand, trap in out:
        issues = M.validate(ja, cand, "ja-ne")
        flagged = any(h for _, h, _ in issues) or (trap == "polarity" and any(k == "NEGATION_ADDED" for k, _, _ in issues))
        if flagged and not any(h for _, h, _ in M.validate(ja, ne, "ja-ne")):
            kept.append((cand, trap))
    if "तपाईं" in ne:
        kept.append((ne.replace("तपाईं", "तिमी"), "honorific_clash"))
    return kept


JA_TRAPS = [("しないでください", "してください"), ("していません", "しました"), ("分かりません", "分かりました"),
            ("いりません", "必要です"), ("飲まれました", "飲みました"), ("かゆい", "痛い"), ("休憩", "休暇")]


def ja_negatives(ja):
    out = []
    for src, dst in JA_TRAPS:
        if src in ja:
            out.append((ja.replace(src, dst, 1), "ja_flip"))
    m = re.search(r"\d+(?:\.\d+)?", ja)
    if m:
        out.append((ja[: m.start()] + ("0" if m.group() != "0" else "99") + ja[m.end():], "ja_number"))
    return [(c, t) for c, t in out if c != ja]


SYS = {"ja-ne": "Translate the Japanese into polite Nepali (तपाईं register). Keep negation, numbers and who does what.",
       "ne-ja": "Translate the Nepali into natural polite Japanese. Keep negation, numbers and who does what."}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--phrases", type=Path, default=Path("dictionaries/phrases.tsv"))
    ap.add_argument("--input-dir", type=Path)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--slots-per-entry", type=int, default=4)
    ap.add_argument("--keep-ne-digits", action="store_true", help="do not turn Nepali digits into ASCII")
    ap.add_argument("--seed", type=int, default=42)
    a = ap.parse_args(argv)
    rng = random.Random(a.seed)
    unify = not a.keep_ne_digits
    entries = load_phrases(a.phrases, unify) if a.phrases and a.phrases.exists() else []
    if a.input_dir and a.input_dir.exists():
        entries += load_dir(a.input_dir, unify)
    expanded = [x for e in entries for x in expand_slots(e, rng, a.slots_per_entry)]
    a.out.mkdir(parents=True, exist_ok=True)
    stats = {"entries": len(entries), "after_slots": len(expanded), "mhd": 0, "chatml": 0, "dpo": 0, "dpo_by_trap": {}}
    with (a.out / "sft_mhd_grouped.jsonl").open("w", encoding="utf-8") as mhd, \
         (a.out / "sft_chatml.jsonl").open("w", encoding="utf-8") as chat, \
         (a.out / "dpo_hard_negatives.jsonl").open("w", encoding="utf-8") as dpo:
        for e in expanded:
            dirs = [("ja-ne", e.ja, hyps(e.ne, NE_PARA))]
            if not e.one_way:
                dirs.append(("ne-ja", e.ne, hyps(e.ja, JA_PARA)))
            for d, src, hs in dirs:
                mhd.write(json.dumps({"id": e.id, "direction": d, "source": src, "hypotheses": hs, "tags": e.tags}, ensure_ascii=False) + "\n")
                stats["mhd"] += 1
                for h in hs:
                    chat.write(json.dumps({"messages": [{"role": "system", "content": SYS[d]}, {"role": "user", "content": src},
                                                        {"role": "assistant", "content": h}]}, ensure_ascii=False) + "\n")
                    stats["chatml"] += 1
            negs = [("ja-ne", e.ja, e.ne, c, t) for c, t in ne_negatives(e.ja, e.ne)]
            if not e.one_way:
                negs += [("ne-ja", e.ne, e.ja, c, t) for c, t in ja_negatives(e.ja)]
            for d, src, good, bad, trap in negs:
                dpo.write(json.dumps({"id": e.id, "direction": d, "prompt": src, "chosen": good, "rejected": bad, "trap": trap}, ensure_ascii=False) + "\n")
                stats["dpo"] += 1
                stats["dpo_by_trap"][trap] = stats["dpo_by_trap"].get(trap, 0) + 1
    (a.out / "dataset_report.json").write_text(json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(stats, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
