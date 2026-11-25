"""Tests for build_candidates.py, review_sheet.py and the run_eval.py extensions (synthetic data only)."""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_candidates as B  # noqa: E402
import review_sheet as S  # noqa: E402
import run_eval as R  # noqa: E402

FULL_HEADER = "\t".join(B.COLUMNS)


def _w(d, name, text):
    p = os.path.join(d, name)
    with open(p, "w", encoding="utf-8") as f:
        f.write(text)
    return p


class BuildCandidatesTest(unittest.TestCase):
    def test_categories(self):
        self.assertEqual(B.categorize("これは1000円です。", "यो १००० येन हो।"), "numbers")
        self.assertEqual(B.categorize("行きません。", "म जान्न।"), "negation")
        self.assertEqual(B.categorize("駅はどこですか？", "स्टेसन कहाँ छ?"), "question")
        self.assertEqual(B.categorize("少々お待ちください。", "कृपया पर्खनुहोस्।"), "modality")
        self.assertEqual(B.categorize("ネパールから来ました。", "म नेपालबाट आएँ।"), "proper_name")
        self.assertEqual(B.categorize("血圧を測ります。", "रक्तचाप नाप्छु।"), "care_medical")
        self.assertEqual(B.categorize("在留カードを見せる。", "निवास कार्ड देखाउने।"), "admin")

    def test_directions_disjoint_and_ja_only(self):
        rows = [("文%d。" % i, "वाक्य %d।" % i, False) for i in range(60)] + [("専用です。", "विशेष हो।", True)]
        out, counts = B.build(rows)
        srcs = {(r["direction"], r["source"]) for r in out}
        ja_used = {s for d, s in srcs if d == "ja-ne"}
        ne_used = {s for d, s in srcs if d == "ne-ja"}
        pairs = {(ja, ne) for ja, ne, _ in rows}
        for ja, ne in pairs:
            self.assertFalse(ja in ja_used and ne in ne_used, "same pair used in both directions")
        self.assertIn("専用です。", ja_used)
        self.assertNotIn("विशेष हो।", ne_used)
        self.assertTrue(all(r["reference"] == "" and r["review_status"] == "UNVERIFIED" and r["in_user_dict"] == "1"
                            for r in out))
        self.assertEqual(sum(counts.values()), len(out))

    def test_per_category_cap_and_facts(self):
        rows = [("%d個です。" % i, "%d वटा हो।" % i, True) for i in range(10)]
        out, counts = B.build(rows, per_category=3)
        self.assertEqual(counts[("ja-ne", "numbers")], 3)
        self.assertTrue(all("num:" in r["critical_facts"] for r in out))

    def test_read_phrases_header(self):
        with tempfile.TemporaryDirectory() as d:
            ok = _w(d, "p.tsv", "#!jane phrases\n# c\nこんにちは。\tनमस्ते।\n行く。\tजान्छु।\tja>ne\n")
            self.assertEqual(B.read_phrases(ok), [("こんにちは。", "नमस्ते।", False), ("行く。", "जान्छु।", True)])
            bad = _w(d, "b.tsv", "x\ty\n")
            with self.assertRaises(ValueError):
                B.read_phrases(bad)


class RunEvalTest(unittest.TestCase):
    def test_seed_format_still_reads(self):
        with tempfile.TemporaryDirectory() as d:
            p = _w(d, "s.tsv", "# c\nid\tdirection\tcategory\tsource\treference\tterms\n"
                               "a1\tja-ne\tx\t明日\tभोलि\tभोलि\n")
            rows = R.read_testset(p)
            self.assertEqual(rows[0]["terms"], ["भोलि"])

    def test_full_format_and_verified_only(self):
        with tempfile.TemporaryDirectory() as d:
            p = _w(d, "f.tsv", FULL_HEADER + "\n"
                   "jn-1\tja-ne\tnumbers\t1000円です。\t१००० येन हो।\tnum:1000\t\to\t1\tNATIVE_VERIFIED\ta|b\tx\t\n"
                   "jn-2\tja-ne\tdaily\tこんにちは。\tनमस्ते।\t\t\to\t1\tUNVERIFIED\t\tx\t\n")
            self.assertEqual(len(R.read_testset(p)), 2)
            rows = R.read_testset(p, verified_only=True)
            self.assertEqual([r["id"] for r in rows], ["jn-1"])
            self.assertEqual(rows[0]["critical_facts"], "num:1000")

    def test_auto_critical_error_rate(self):
        rows = [dict(id="a", direction="ja-ne", category="c", source="1000円です。", reference="१००० येन हो।", terms=[]),
                dict(id="b", direction="ja-ne", category="c", source="行きません。", reference="म जान्न।", terms=[])]
        g = R.score_group(rows, {"a": "१००० येन हो।", "b": "म जान्छु।"})
        self.assertAlmostEqual(g["auto_critical_error_rate"], 0.5)


class ReviewSheetTest(unittest.TestCase):
    def _testset(self, d):
        return _w(d, "t.tsv", FULL_HEADER + "\n"
                  "jn-1\tja-ne\tdaily\tこんにちは。\t\t\t\to\t1\tUNVERIFIED\t\tx\t\n"
                  "jn-2\tja-ne\tdaily\tありがとう。\t\t\t\to\t1\tUNVERIFIED\t\tx\t\n")

    def test_ref_then_verify_import(self):
        with tempfile.TemporaryDirectory() as d:
            t = self._testset(d)
            sheet = os.path.join(d, "ref.tsv")
            self.assertEqual(S.ref_sheet(t, sheet), 2)
            h, rows = S._read_tsv(sheet)
            rows[0]["reference"], rows[0]["writer"] = "नमस्ते।", "w1"
            S._write_tsv(sheet, h, rows)
            t2 = os.path.join(d, "t2.tsv")
            self.assertEqual(S.import_refs(t, sheet, t2), 1)
            r = {x["id"]: x for x in R.read_testset(t2)}
            self.assertEqual((r["jn-1"]["reference"], r["jn-1"]["review_status"]), ("नमस्ते।", "NATIVE_DRAFTED"))
            v = os.path.join(d, "ver.tsv")
            S.verify_sheet(t2, v)
            h, rows = S._read_tsv(v)
            rows[0]["agree"], rows[0]["verifier"] = "y", "v1"
            S._write_tsv(v, h, rows)
            t3 = os.path.join(d, "t3.tsv")
            S.import_refs(t2, v, t3)
            r = {x["id"]: x for x in R.read_testset(t3, verified_only=True)}
            self.assertEqual(list(r), ["jn-1"])
            self.assertEqual(r["jn-1"]["reviewers"], "w1|v1")

    def test_score_sheet_blind_and_key(self):
        with tempfile.TemporaryDirectory() as d:
            t = self._testset(d)
            s1 = _w(d, "s1.tsv", "jn-1\tनमस्ते\njn-2\tधन्यवाद\n")
            s2 = _w(d, "s2.tsv", "jn-1\tहेलो\n")
            out, key = os.path.join(d, "score.tsv"), os.path.join(d, "key.tsv")
            self.assertEqual(S.score_sheet(t, {"raw": s1, "jane": s2}, out, key), 3)
            h, rows = S._read_tsv(out)
            self.assertNotIn("system", h)
            _, keys = S._read_tsv(key)
            self.assertEqual(sorted(k["system"] for k in keys if k["id"] == "jn-1"), ["jane", "raw"])

    def test_kappa(self):
        self.assertAlmostEqual(S.cohen_kappa([1, 0, 1, 0], [1, 0, 1, 0]), 1.0)
        self.assertAlmostEqual(S.cohen_kappa([1, 1, 0, 0], [1, 0, 1, 0]), 0.0)
        self.assertIsNone(S.cohen_kappa([1, 1], [1, 1]))
        self.assertIsNone(S.cohen_kappa([], []))


if __name__ == "__main__":
    unittest.main()
