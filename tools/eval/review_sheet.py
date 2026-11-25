#!/usr/bin/env python3
"""Review sheets for the evaluation set (docs/spec_v2/16_eval_dataset.md §4, §5, §8).

Sub-commands:
  ref-sheet    --testset T --out S.tsv            reference-writing sheet (source only, no MT shown)
  verify-sheet --testset T --out S.tsv            verification sheet (source + reference A)
  import-refs  --testset T --sheet S.tsv --out T2 merge written / verified references back (sets review_status)
  score-sheet  --testset T --system N=P ... --out S.tsv --key K.tsv [--seed 1]
                                                  blind scoring sheet: per row, outputs of all systems shuffled
                                                  and labelled A, B, C...; the key file maps labels -> systems
  kappa        --a S1.tsv --b S2.tsv              Cohen's kappa on the critical-error flag of two raters

Scoring sheet columns to fill: meaning(0-4) critical(0/1) critical_types natural(0-4) register(0-2) usable(y/n)
"""
import argparse
import os
import random
import sys
from collections import OrderedDict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import run_eval as R  # noqa: E402

STATUS_ORDER = ["UNVERIFIED", "NATIVE_DRAFTED", "NATIVE_VERIFIED", "ADJUDICATED", "REJECTED"]
SCORE_COLS = ["meaning", "critical", "critical_types", "natural", "register", "usable", "comment"]
CRITICAL_TYPES = "neg_flip|number|proper_noun|addition|omission|modality_flip"


def _read_tsv(path):
    rows, header = [], None
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n").rstrip("\r")
            if not line.strip() or line.startswith("#"):
                continue
            c = line.split("\t")
            if header is None:
                header = c
                continue
            c += [""] * (len(header) - len(c))
            rows.append(OrderedDict(zip(header, c)))
    return header or [], rows


def _write_tsv(path, header, rows, comments=()):
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        for c in comments:
            f.write("# " + c + "\n")
        f.write("\t".join(header) + "\n")
        for r in rows:
            f.write("\t".join(str(r.get(h, "")).replace("\t", " ").replace("\n", " ") for h in header) + "\n")


def ref_sheet(testset, out):
    rows = [OrderedDict(id=r["id"], direction=r["direction"], category=r["category"], source=r["source"],
                        reference="", writer="", notes="") for r in R.read_testset(testset)]
    _write_tsv(out, ["id", "direction", "category", "source", "reference", "writer", "notes"], rows,
               ["Write the reference from the source only. Do not use machine translation.",
                "Several valid references: separate with ||"])
    return len(rows)


def verify_sheet(testset, out):
    rows = [OrderedDict(id=r["id"], direction=r["direction"], source=r["source"], reference=r["reference"],
                        agree="", corrected_reference="", verifier="", notes="") for r in R.read_testset(testset)]
    _write_tsv(out, ["id", "direction", "source", "reference", "agree", "corrected_reference", "verifier", "notes"], rows,
               ["agree = y if the reference is correct (meaning, numbers, negation, register); otherwise n and "
                "write corrected_reference"])
    return len(rows)


def import_refs(testset, sheet, out):
    """Merge a filled ref-sheet or verify-sheet into the full-column test set."""
    header, rows = _read_tsv(testset)
    _, filled = _read_tsv(sheet)
    by_id = {r["id"]: r for r in filled}
    for col in ("review_status", "reviewers", "notes"):
        if col not in header:
            header.append(col)
    changed = 0
    for r in rows:
        f = by_id.get(r["id"])
        if not f:
            continue
        if "agree" in f:   # verification sheet
            if f["agree"].strip().lower() in ("y", "yes"):
                r["review_status"] = "NATIVE_VERIFIED"
            elif f.get("corrected_reference", "").strip():
                r["reference"] = f["corrected_reference"].strip()
                r["review_status"] = "NATIVE_DRAFTED"
                r["notes"] = (r.get("notes", "") + " disagreement: " + f.get("notes", "")).strip()
            else:
                continue
            who = f.get("verifier", "").strip()
        elif f.get("reference", "").strip():
            r["reference"] = f["reference"].strip()
            r["review_status"] = "NATIVE_DRAFTED"
            who = f.get("writer", "").strip()
        else:
            continue
        if who:
            r["reviewers"] = "|".join(x for x in [r.get("reviewers", ""), who] if x)
        changed += 1
    _write_tsv(out, header, rows)
    return changed


def score_sheet(testset, systems, out, key, seed=1):
    rng = random.Random(seed)
    tests = R.read_testset(testset)
    outs = OrderedDict((n, R.read_outputs(p)) for n, p in systems.items())
    rows, keys = [], []
    for t in tests:
        names = [n for n in outs if t["id"] in outs[n]]
        rng.shuffle(names)
        for i, n in enumerate(names):
            label = chr(ord("A") + i)
            rows.append(OrderedDict([("id", t["id"]), ("direction", t["direction"]), ("source", t["source"]),
                                     ("label", label), ("output", outs[n][t["id"]])] + [(c, "") for c in SCORE_COLS]))
            keys.append(OrderedDict(id=t["id"], label=label, system=n))
    _write_tsv(out, ["id", "direction", "source", "label", "output"] + SCORE_COLS, rows,
               ["Blind scoring: system names are hidden. meaning 0-4, critical 0/1, critical_types one of "
                + CRITICAL_TYPES + ", natural 0-4, register 0-2, usable y/n"])
    _write_tsv(key, ["id", "label", "system"], keys, ["KEY: do not give this file to raters"])
    return len(rows)


def cohen_kappa(a, b):
    """Kappa for two raters' binary labels (lists of 0/1, same length). None if undefined."""
    n = len(a)
    if n == 0 or n != len(b):
        return None
    po = sum(1 for x, y in zip(a, b) if x == y) / n
    pa, pb = sum(a) / n, sum(b) / n
    pe = pa * pb + (1 - pa) * (1 - pb)
    return None if pe == 1 else (po - pe) / (1 - pe)


def kappa_from_sheets(s1, s2):
    _, r1 = _read_tsv(s1)
    _, r2 = _read_tsv(s2)
    m1 = {(r["id"], r["label"]): r["critical"].strip() for r in r1}
    m2 = {(r["id"], r["label"]): r["critical"].strip() for r in r2}
    common = [k for k in m1 if k in m2 and m1[k] in ("0", "1") and m2[k] in ("0", "1")]
    return cohen_kappa([int(m1[k]) for k in common], [int(m2[k]) for k in common]), len(common)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest="cmd", required=True)
    for name in ("ref-sheet", "verify-sheet"):
        p = sp.add_parser(name)
        p.add_argument("--testset", required=True)
        p.add_argument("--out", required=True)
    p = sp.add_parser("import-refs")
    p.add_argument("--testset", required=True)
    p.add_argument("--sheet", required=True)
    p.add_argument("--out", required=True)
    p = sp.add_parser("score-sheet")
    p.add_argument("--testset", required=True)
    p.add_argument("--system", action="append", required=True, metavar="NAME=PATH")
    p.add_argument("--out", required=True)
    p.add_argument("--key", required=True)
    p.add_argument("--seed", type=int, default=1)
    p = sp.add_parser("kappa")
    p.add_argument("--a", required=True)
    p.add_argument("--b", required=True)
    a = ap.parse_args(argv)
    if a.cmd == "ref-sheet":
        print(f"{ref_sheet(a.testset, a.out)} rows -> {a.out}")
    elif a.cmd == "verify-sheet":
        print(f"{verify_sheet(a.testset, a.out)} rows -> {a.out}")
    elif a.cmd == "import-refs":
        print(f"{import_refs(a.testset, a.sheet, a.out)} rows updated -> {a.out}")
    elif a.cmd == "score-sheet":
        systems = OrderedDict(s.split("=", 1) for s in a.system)
        print(f"{score_sheet(a.testset, systems, a.out, a.key, a.seed)} rows -> {a.out} (key {a.key})")
    else:
        k, n = kappa_from_sheets(a.a, a.b)
        print(f"kappa={'n/a' if k is None else f'{k:.3f}'} over {n} shared items")


if __name__ == "__main__":
    main()
