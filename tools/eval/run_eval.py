#!/usr/bin/env python3
"""ja<->ne evaluation runner (SPEC §20).

Usage:
  python3 run_eval.py --testset testset_seed.tsv \
      --system baseline=out/baseline.tsv --system jane=out/jane.tsv \
      --label baseline-vs-jane [--commit abc123] [--model madlad-int4] [--out-dir docs/eval]

Test set TSV (lines starting with '#' are comments, optional header row starting with 'id'):
  id <TAB> direction(ja-ne|ne-ja) <TAB> category <TAB> source <TAB> reference [<TAB> term1|term2...]
System output TSV: id <TAB> output   (one file per system)

Only measured values are reported. Missing outputs are counted and listed, never imputed;
rates with no applicable sample are reported as null / "n/a".
"""
import argparse
import datetime
import json
import os
import sys
from collections import OrderedDict, defaultdict

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M  # noqa: E402

DIRECTIONS = ("ja-ne", "ne-ja")


def read_testset(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        for ln, line in enumerate(f, 1):
            line = line.rstrip("\n").rstrip("\r")
            if not line.strip() or line.startswith("#"):
                continue
            cols = line.split("\t")
            if cols[0] == "id":
                continue
            if len(cols) < 5:
                raise ValueError(f"{path}:{ln}: expected >=5 columns, got {len(cols)}")
            if cols[1] not in DIRECTIONS:
                raise ValueError(f"{path}:{ln}: bad direction {cols[1]!r}")
            terms = [t for t in cols[5].split("|") if t] if len(cols) > 5 else []
            rows.append(dict(id=cols[0], direction=cols[1], category=cols[2],
                             source=cols[3], reference=cols[4], terms=terms))
    ids = [r["id"] for r in rows]
    dup = {i for i in ids if ids.count(i) > 1}
    if dup:
        raise ValueError(f"duplicate ids in test set: {sorted(dup)}")
    return rows


def read_outputs(path):
    out = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n").rstrip("\r")
            if not line or line.startswith("#"):
                continue
            k, _, v = line.partition("\t")
            out[k] = v
    return out


def _rate(num, den):
    return None if den == 0 else num / den


def score_group(rows, outputs):
    hyps, refs = [], []
    num_ok = num_n = neg_ok = term_hit = term_tot = left = 0
    for r in rows:
        h = outputs[r["id"]]
        hyps.append(h)
        refs.append(r["reference"])
        p = M.numbers_preserved(r["source"], h)
        if p is not None:
            num_n += 1
            num_ok += int(p)
        neg_ok += int(M.negation_consistent(r["source"], h, r["direction"]))
        hit, tot = M.term_hits(h, r["terms"])
        term_hit += hit
        term_tot += tot
        left += int(M.leftover_source_script(h, r["direction"]))
    n = len(rows)
    return OrderedDict(
        n=n,
        chrfpp=M.chrf_pp_corpus(hyps, refs) if n else None,
        number_preservation=_rate(num_ok, num_n), number_n=num_n,
        negation_consistency=_rate(neg_ok, n),
        term_accuracy=_rate(term_hit, term_tot), term_n=term_tot,
        leftover_source_script=_rate(left, n),
    )


def evaluate(testset, systems):
    result = OrderedDict()
    for name, outputs in systems.items():
        present = [r for r in testset if r["id"] in outputs]
        missing = [r["id"] for r in testset if r["id"] not in outputs]
        sysres = OrderedDict(missing_ids=missing, directions=OrderedDict())
        for d in DIRECTIONS:
            drows = [r for r in present if r["direction"] == d]
            if not drows:
                continue
            cats = defaultdict(list)
            for r in drows:
                cats[r["category"]].append(r)
            sysres["directions"][d] = OrderedDict(
                overall=score_group(drows, outputs),
                categories=OrderedDict((c, score_group(cats[c], outputs)) for c in sorted(cats)))
        result[name] = sysres
    return result


def _fmt(v, pct=False):
    if v is None:
        return "n/a"
    return f"{100 * v:.1f}%" if pct else f"{v:.2f}"


def to_markdown(result):
    lines = []
    for d in DIRECTIONS:
        lines.append(f"\n### {d}\n")
        lines.append("| system | category | n | chrF++ | num.pres (n) | neg.cons | term.acc (n) | leftover src |")
        lines.append("|---|---|---:|---:|---:|---:|---:|---:|")
        for name, sr in result.items():
            dr = sr["directions"].get(d)
            if not dr:
                continue
            for cat, g in [("**ALL**", dr["overall"])] + list(dr["categories"].items()):
                lines.append(
                    f"| {name} | {cat} | {g['n']} | {_fmt(g['chrfpp'])} | "
                    f"{_fmt(g['number_preservation'], True)} ({g['number_n']}) | "
                    f"{_fmt(g['negation_consistency'], True)} | "
                    f"{_fmt(g['term_accuracy'], True)} ({g['term_n']}) | "
                    f"{_fmt(g['leftover_source_script'], True)} |")
    for name, sr in result.items():
        if sr["missing_ids"]:
            lines.append(f"\n**{name}**: {len(sr['missing_ids'])} missing outputs (excluded): "
                         + ", ".join(sr["missing_ids"]))
    return "\n".join(lines)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--testset", required=True)
    ap.add_argument("--system", action="append", required=True, metavar="NAME=PATH")
    ap.add_argument("--label", required=True, help="short label used in the JSON file name")
    ap.add_argument("--commit", default=None)
    ap.add_argument("--model", default=None)
    ap.add_argument("--notes", default=None)
    repo = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
    ap.add_argument("--out-dir", default=os.path.join(repo, "docs", "eval"))
    ap.add_argument("--no-json", action="store_true")
    a = ap.parse_args(argv)

    testset = read_testset(a.testset)
    systems = OrderedDict()
    for spec in a.system:
        name, _, path = spec.partition("=")
        if not path:
            ap.error(f"--system must be NAME=PATH, got {spec!r}")
        systems[name] = read_outputs(path)

    result = evaluate(testset, systems)
    print(f"# Eval: {a.label}  (testset={os.path.basename(a.testset)}, rows={len(testset)})")
    print(to_markdown(result))

    if not a.no_json:
        today = datetime.date.today().isoformat()
        os.makedirs(a.out_dir, exist_ok=True)
        path = os.path.join(a.out_dir, f"{today}_{a.label}.json")
        doc = OrderedDict(date=today, label=a.label, commit=a.commit, model=a.model, notes=a.notes,
                          testset=os.path.basename(a.testset), testset_rows=len(testset),
                          metrics_def="chrF++ (char6, word2, beta2; sacrebleu-compatible); rates are fractions",
                          systems=result)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, indent=2)
        print(f"\nwrote {path}")


if __name__ == "__main__":
    main()
