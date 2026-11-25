"""Checks that metrics.validate (Python port) gives the same verdicts as the Java validator.

Input: the file written by nie.translator.rtranslator.jane.ValidatorParityDump. Exit code 1 on any
difference, with the first mismatches printed. Run in CI after the pure-Java tests.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M  # noqa: E402


def main(path):
    n = bad = 0
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            tag, a, b, expect = line.split("\t")
            if tag == "NEG":
                got = str(M.ja_has_negation(b) if a == "ja" else M.ne_has_negation(b)).lower()
            else:
                got = M.format_issues(M.validate(a, b, "ja-ne" if tag == "JN" else "ne-ja"))
            n += 1
            if got != expect:
                bad += 1
                if bad <= 20:
                    print("MISMATCH %s\n  src=%s\n  hyp=%s\n  java=%s\n  py  =%s" % (tag, a, b, expect, got))
    print("validator parity: %d checked, %d mismatches" % (n, bad))
    return 1 if bad or n == 0 else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
