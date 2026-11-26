"""Tests with sample text written for this test (not from any textbook)."""
import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout, redirect_stderr

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import textbook_terms as T  # noqa: E402

SAMPLE_JA = """第1章 食事の介助
利用者の誤嚥を防ぐため、食事介助では姿勢に注意する。誤嚥性肺炎は高齢者に多い。
入浴介助の前にバイタルを確認する。血圧と体温を測る。
移乗の介助では車椅子のブレーキを確認する。転倒に注意する。
"""
SAMPLE_GLOSSARY = """用語集
1. 入浴介助 ........ नुहाउन सहयोग
2. 食事介助      खाना खुवाउन सहयोग   (p.12)
3. 車椅子 ह्वीलचियर।
4. 誤嚥 | aspiration
5. 体位変換  शरीरको स्थिति परिवर्तन
6. 本文の行（ネパール語なし）
"""


def write(text, suffix=".txt"):
    f = tempfile.NamedTemporaryFile("w", suffix=suffix, delete=False, encoding="utf-8")
    f.write(text)
    f.close()
    return f.name


class TermsTest(unittest.TestCase):
    def setUp(self):
        # a small dictionary of its own, so the test does not depend on what dictionaries/ contains today
        self.repo = tempfile.mkdtemp()
        os.makedirs(os.path.join(self.repo, "dictionaries", "review"))
        with open(os.path.join(self.repo, "dictionaries", "ja_ne_terms.tsv"), "w", encoding="utf-8") as f:
            f.write("#!jane ja-ne-terms\n車椅子\tह्वीलचियर\n")
        with open(os.path.join(self.repo, "dictionaries", "review", "care_candidates.tsv"), "w", encoding="utf-8") as f:
            f.write("# candidates\n移乗\tस्थानान्तरण\n")
        self.saved, T.REPO = T.REPO, self.repo

    def tearDown(self):
        T.REPO = self.saved

    def test_terms_marks_status(self):
        out = io.StringIO()
        with redirect_stdout(out):
            T.main(["terms", write(SAMPLE_JA), "--top", "50"])
        rows = {l.split("\t")[0]: l.split("\t") for l in out.getvalue().splitlines() if not l.startswith("#")}
        self.assertEqual(rows["誤嚥"][1], "1")                 # 誤嚥を防ぐ
        self.assertIn("誤嚥性肺炎", rows)                        # a run of kanji is one compound word
        self.assertEqual(rows["車椅子"][3], "dictionary")       # already in ja_ne_terms.tsv
        self.assertEqual(rows["移乗"][3], "review")             # in review/care_candidates.tsv
        self.assertIn("バイタル", rows)                          # katakana word
        self.assertNotIn("注意", rows)                           # stop word


class GlossaryTest(unittest.TestCase):
    def test_pairs(self):
        self.assertEqual(T.split_pair("1. 入浴介助 ........ नुहाउन सहयोग"), ("入浴介助", "नुहाउन सहयोग"))
        self.assertEqual(T.split_pair("食事介助      खाना खुवाउन सहयोग   (p.12)"), ("食事介助", "खाना खुवाउन सहयोग"))
        self.assertEqual(T.split_pair("車椅子 ह्वीलचियर।"), ("車椅子", "ह्वीलचियर"))
        self.assertIsNone(T.split_pair("誤嚥 | aspiration"))

    def test_output_is_an_app_dictionary(self):
        out = tempfile.mktemp(suffix=".tsv")
        err = io.StringIO()
        with redirect_stderr(err):
            T.main(["glossary", write(SAMPLE_GLOSSARY), "--out", out])
        lines = open(out, encoding="utf-8").read().splitlines()
        self.assertEqual(lines[0], "#!jane ja-ne-terms")
        entries = [l for l in lines if l and not l.startswith("#")]
        self.assertEqual(entries, ["入浴介助\tनुहाउन सहयोग", "食事介助\tखाना खुवाउन सहयोग", "車椅子\tह्वीलचियर",
                                   "体位変換\tशरीरको स्थिति परिवर्तन"])
        self.assertIn("4 pairs", err.getvalue())


if __name__ == "__main__":
    unittest.main()
