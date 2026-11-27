"""Checks for build_ja_ne_distill_dataset.py (runs in CI, no model, < 1 s)."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import build_ja_ne_distill_dataset as B  # noqa: E402


class T(unittest.TestCase):
    def test_word_bounded_replace(self):
        self.assertEqual(B.wsub("काम म गर्छु।", "म", "तपाईं"), "काम तपाईं गर्छु।")

    def test_rejected_are_wrong(self):
        negs = B.ne_negatives("ブレーキを確認してください。", "ब्रेक जाँच गर्नुहोस्।")
        self.assertTrue(any(t == "polarity" and "नगर्नुहोस्" in c for c, t in negs))
        self.assertEqual(B.ne_negatives("確認しました。", "जाँच गरें।"), [])   # nothing verifiably wrong to make

    def test_slots_in_both_languages_only(self):
        e = B.Entry("x", "鈴木様はお茶を飲まれました。", "सुजुकी-ज्यूले चिया पिउनुभयो।")
        out = B.expand_slots(e, __import__("random").Random(1), 10)
        self.assertGreater(len(out), 1)
        for x in out[1:]:
            self.assertNotEqual((x.ja, x.ne), (e.ja, e.ne))
            self.assertNotIn("鈴木様", x.ja) if "सुजुकी" not in x.ne else None

    def test_end_to_end(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "p.tsv"
            p.write_text("#!jane phrases\n確認してください。\tजाँच गर्नुहोस्।\n", encoding="utf-8")
            B.main(["--phrases", str(p), "--out", d])
            rep = json.loads((Path(d) / "dataset_report.json").read_text(encoding="utf-8"))
            self.assertEqual(rep["entries"], 1)
            self.assertGreaterEqual(rep["dpo"], 1)
            mhd = [json.loads(l) for l in (Path(d) / "sft_mhd_grouped.jsonl").read_text(encoding="utf-8").splitlines()]
            self.assertTrue(any(len(m["hypotheses"]) > 1 for m in mhd))


if __name__ == "__main__":
    unittest.main()
