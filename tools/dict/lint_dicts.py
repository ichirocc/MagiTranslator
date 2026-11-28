#!/usr/bin/env python3
"""Checks the jane dictionaries (dictionaries/*.tsv) for data accidents.

Errors (exit 1): a missing or wrong header, a line without exactly the expected columns, an empty side, a line in
the wrong script (no Japanese on the ja side, no Devanagari on the ne side), a phrase marker other than ja>ne.
Warnings (exit 0, listed with --warnings): the same key twice with different targets, and terms whose two
directions disagree (ja->ne X but ne->ja X -> another Japanese word). Warnings are often legitimate (synonyms).

Usage: python3 tools/dict/lint_dicts.py [dictionaries_dir] [--warnings]
"""
import os
import re
import sys
import unicodedata
from collections import defaultdict

JA = re.compile(r"[぀-ヿ㐀-鿿]")
NE = re.compile(r"[ऀ-ॿ]")
FILES = {"ja_ne_terms.tsv": ("#!jane ja-ne-terms", "ja", "ne", 2),
         "ne_ja_terms.tsv": ("#!jane ne-ja-terms", "ne", "ja", 2),
         "phrases.tsv": ("#!jane phrases", "ja", "ne", 3)}


def script_ok(text, lang):
    if lang == "ja" and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9 .\-]*", text):
        return True   # an abbreviation used as is in Japanese (BPSD, ADL)
    return bool((JA if lang == "ja" else NE).search(text))


def rows(path):
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            line = line.rstrip("\n").rstrip("\r")
            yield n, line


def lint(d):
    errors, warnings = [], []
    terms = {}
    for name, (header, a, b, maxcols) in FILES.items():
        path = os.path.join(d, name)
        if not os.path.exists(path):
            errors.append("%s: missing" % name)
            continue
        seen = defaultdict(set)
        first = True
        for n, line in rows(path):
            if first:
                first = False
                if line.strip() != header:
                    errors.append("%s:%d: header must be '%s'" % (name, n, header))
                continue
            if not line.strip() or line.startswith("#"):
                continue
            cols = line.split("\t")
            if len(cols) < 2 or len(cols) > maxcols:
                errors.append("%s:%d: %d columns (expected 2%s)" % (name, n, len(cols), " or 3" if maxcols == 3 else ""))
                continue
            src, tgt = cols[0].strip(), cols[1].strip()
            if not src or not tgt:
                errors.append("%s:%d: empty side" % (name, n))
                continue
            if len(cols) == 3 and cols[2].strip() not in ("", "ja>ne"):
                errors.append("%s:%d: third column must be ja>ne, not %r" % (name, n, cols[2]))
            if not script_ok(src, a):
                errors.append("%s:%d: %s side has no %s text: %r" % (name, n, a, a, src[:30]))
            if not script_ok(tgt, b):
                errors.append("%s:%d: %s side has no %s text: %r" % (name, n, b, b, tgt[:30]))
            key = unicodedata.normalize("NFC", src)
            seen[key].add(unicodedata.normalize("NFC", tgt))
        for k, v in seen.items():
            if len(v) > 1:
                warnings.append("%s: %r has %d targets: %s" % (name, k, len(v), " / ".join(sorted(v))[:120]))
        if name != "phrases.tsv":
            terms[(a, b)] = seen
    jn, nj = terms.get(("ja", "ne"), {}), terms.get(("ne", "ja"), {})
    for ja, nes in jn.items():
        for ne in nes:
            back = nj.get(ne)
            if back and ja not in back:
                warnings.append("terms: %s -> %s, but %s -> %s" % (ja, ne, ne, " / ".join(sorted(back))[:80]))
    return errors, warnings


def main(argv):
    args = [x for x in argv if not x.startswith("--")]
    d = args[0] if args else os.path.join(os.path.dirname(__file__), "..", "..", "dictionaries")
    errors, warnings = lint(d)
    for e in errors:
        print("ERROR", e)
    if "--warnings" in argv:
        for w in warnings:
            print("warn ", w)
    print("%d errors, %d warnings" % (len(errors), len(warnings)))
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
