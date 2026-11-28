import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import metrics as M  # noqa: E402


class ChrfTest(unittest.TestCase):
    def test_identical_is_100(self):
        self.assertAlmostEqual(M.chrf_pp_sentence("म घर जान्छु।", "म घर जान्छु।"), 100.0, places=6)

    def test_disjoint_is_zero(self):
        self.assertLess(M.chrf_pp_sentence("abc", "xyz"), 1e-6)

    def test_partial_between(self):
        s = M.chrf_pp_sentence("the cat sat", "the cat sat down")
        self.assertTrue(0 < s < 100)

    def test_empty(self):
        self.assertEqual(M.chrf_pp_corpus([], []), 0.0)
        self.assertEqual(M.chrf_pp_sentence("", "abc"), 0.0)

    def test_word_split_punct(self):
        self.assertEqual(M._words("hello, world."), ["hello", ",", "world", "."])

    def test_against_sacrebleu(self):
        try:
            import sacrebleu
        except ImportError:
            self.skipTest("sacrebleu not installed")
        hyps = ["the cat sat on the mat.", "म भोलि आउँछु", "明日は雨です"]
        refs = ["the cat is on the mat.", "म भोलि आउनेछु।", "明日は雨が降ります"]
        ref = sacrebleu.corpus_chrf(hyps, [refs], word_order=2).score
        self.assertAlmostEqual(M.chrf_pp_corpus(hyps, refs), ref, places=4)
        for h, r in zip(hyps, refs):
            self.assertAlmostEqual(M.chrf_pp_sentence(h, r),
                                   sacrebleu.sentence_chrf(h, [r], word_order=2).score, places=4)


class NumberTest(unittest.TestCase):
    def test_normalize(self):
        self.assertEqual(M.numbers("１,０００円と2.5"), ["1000", "2.5"])
        self.assertEqual(M.numbers("१,००० रुपैयाँ ०५"), ["1000", "5"])

    def test_preserved(self):
        self.assertTrue(M.numbers_preserved("3時に", "३ बजे"))
        self.assertFalse(M.numbers_preserved("3時に", "४ बजे"))
        self.assertIsNone(M.numbers_preserved("こんにちは", "नमस्ते"))


class NegationTest(unittest.TestCase):
    def test_ja(self):
        self.assertTrue(M.ja_has_negation("行きません。"))
        self.assertTrue(M.ja_has_negation("食べなかった"))
        self.assertTrue(M.ja_has_negation("知らず、"))
        self.assertFalse(M.ja_has_negation("危ないです"))
        self.assertFalse(M.ja_has_negation("少ないです"))
        self.assertFalse(M.ja_has_negation("仕方がない"))

    def test_ne(self):
        self.assertTrue(M.ne_has_negation("म जाँदिनँ।"))
        self.assertTrue(M.ne_has_negation("यो होइन"))
        self.assertTrue(M.ne_has_negation("नजाऊ"))
        self.assertFalse(M.ne_has_negation("नमस्ते"))
        self.assertFalse(M.ne_has_negation("नयाँ घर"))
        self.assertFalse(M.ne_has_negation("आज राम्रो दिन हो।"))

    def test_danda_excluded(self):
        # danda must not be part of a word, else the $-anchored suffix would miss
        self.assertTrue(M.ne_has_negation("पैसा छैन।"))

    def test_consistent(self):
        self.assertTrue(M.negation_consistent("行きません", "म जाँदिनँ", "ja-ne"))
        self.assertFalse(M.negation_consistent("行きません", "म जान्छु", "ja-ne"))
        self.assertTrue(M.negation_consistent("म जाँदिनँ", "行きません", "ne-ja"))


class OtherTest(unittest.TestCase):
    def test_terms(self):
        self.assertEqual(M.term_hits("काठमाडौं जान्छु", ["काठमाडौं", "बस"]), (1, 2))
        self.assertEqual(M.term_hits("x", []), (0, 0))

    def test_leftover(self):
        self.assertTrue(M.leftover_source_script("म 東京 जान्छु", "ja-ne"))
        self.assertFalse(M.leftover_source_script("म टोकियो जान्छु", "ja-ne"))
        self.assertTrue(M.leftover_source_script("私は घर", "ne-ja"))


class ReviewFixesTest(unittest.TestCase):
    """Kept in sync with the Java fixes (JaNeLibraryTest 'review fixes')."""

    def test_ne_negation_false_positives(self):
        for w in ("प्रसन्न", "भिन्न", "सम्पन्न", "नगद", "नमुना", "नम्र", "नगरपालिका"):
            self.assertFalse(M.ne_has_negation("म " + w + " छु।"), w)

    def test_ne_negation_true(self):
        for s in ("नगर्नुहोस्।", "उहाँ नआउनुभयो।", "यो हुन्न।", "मलाई थाहा छैन।"):
            self.assertTrue(M.ne_has_negation(s), s)

    def test_ja_negation(self):
        self.assertFalse(M.ja_has_negation("部屋がきたないです。"))
        self.assertFalse(M.ja_has_negation("人が少なかったです。"))
        self.assertTrue(M.ja_has_negation("人が少なくないです。"))

    def test_numbers_ne_words(self):
        self.assertEqual(M.numbers("तीन जना"), ["3"])
        self.assertEqual(M.numbers("यो राम्रो छ।"), [])
        self.assertEqual(M.numbers("एकदम राम्रो"), [])

    def test_kanji_to_digits(self):
        self.assertEqual(M.kanji_to_digits("三人が来ました。"), "3人が来ました。")
        self.assertEqual(M.kanji_to_digits("十分です。"), "十分です。")
        self.assertEqual(M.kanji_to_digits("三時十分です。"), "3時10分です。")
        self.assertEqual(M.kanji_to_digits("万年筆"), "万年筆")
        self.assertEqual(M.kanji_to_digits("三時半"), "3時30分")
        self.assertEqual(M.kanji_to_digits("一万五千円"), "15000円")

    def test_numbers_preserved_ne_ja_kanji(self):
        self.assertTrue(M.numbers_preserved("३ जना आउनुभयो।", "三人が来ました。", "ja"))


class ValidatorPortTest(unittest.TestCase):
    """Port of the IR-based validator kinds (JaNeLibraryTest; full parity: check_validator_parity.py)."""

    def kinds(self, src, hyp, d):
        return [k for k, _, _ in M.validate(src, hyp, d)]

    def test_negative_past(self):
        for s in ("म हिजो गइनँ।", "उनी आएनन्।", "उसले काम गरेन।", "मैले खाना खाइनँ।", "मैले गरिनँ।"):
            self.assertTrue(M.ne_has_negation(s), s)
        for s in ("म ट्रेनमा गएँ।", "आज राम्रो दिन हो।", "उनी आए।", "यो ५०० येन हो।"):
            self.assertFalse(M.ne_has_negation(s), s)
        self.assertEqual(M.hard_count(M.validate("昨日は行きませんでした。", "म हिजो गइनँ।", "ja-ne")), 0)

    def test_ja_ne_agreement(self):
        self.assertEqual(self.kinds("先生はもう帰られました。", "तपाईँको शिक्षक घरमा हुनुहुन्छ।", "ja-ne"), ["PAST_LOST"])
        self.assertEqual(M.hard_count(M.validate("先生はもう帰られました。", "शिक्षक घर गएका छन् ।", "ja-ne")), 0)
        self.assertIn("WH_LOST", self.kinds("明日の夕方、何時に会社へ行きますか？", "तपाईँ भोलि साँझ कम्पनीमा कहिले जानुहुन्छ?", "ja-ne"))
        self.assertEqual(M.hard_count(M.validate("明日の夕方、何時に会社へ行きますか？", "तपाईँ भोलि साँझ कति बजे कम्पनी जानुहुन्छ ?", "ja-ne")), 0)
        self.assertEqual(self.kinds("明日行かなければなりません。", "म भोलि जान्छु।", "ja-ne"), ["OBLIGATION_LOST"])
        self.assertEqual(self.kinds("少々お待ちください。", "म पर्खन्छु।", "ja-ne"), ["REQUEST_LOST"])
        self.assertEqual(self.kinds("確認しますね。", "कृपया जाँच गर्नुहोस्।", "ja-ne"), ["PERSON_REVERSED"])
        self.assertEqual(self.kinds("確認しますね。", "म जाँच गर्छु है।", "ja-ne"), [])
        self.assertEqual(self.kinds("「確認」を押します。", "पक्का बटन थिच्नुहोस्।", "ja-ne"), [])
        self.assertEqual(self.kinds("ペンを貸してください。", "कलम सापटी लिनुहोस्।", "ja-ne"), ["DIRECTION_REVERSED"])
        self.assertEqual(self.kinds("今度は一人でやってみましょう。", "अब आफूखुसी गर्नुहोस्।", "ja-ne"), ["GUIDANCE_REVERSED"])
        self.assertEqual(M.validate("明日は雨でしょう。", "भोलि पानी पर्छ।", "ja-ne"), [("CONJECTURE_LOST", False, None)])
        self.assertEqual(M.validate("ここで写真を撮ってもいいですか？", "यहाँ फोटो खिच्ने?", "ja-ne")[-1], ("PERMISSION_LOST", False, None))

    def test_ne_ja_meaning(self):
        self.assertIn("QUESTION_WORD_LOST", self.kinds("यो कति हो?", "これは何ですか。", "ne-ja"))
        self.assertEqual(M.hard_count(M.validate("यो कति हो?", "これはいくらですか？", "ne-ja")), 0)
        self.assertEqual(M.hard_count(M.validate("के तपाईं आउनुहुन्छ?", "来ますか？", "ne-ja")), 0)
        self.assertIn("CURRENCY_MISMATCH", self.kinds("यसको मूल्य १,००० रुपैयाँ हो।", "価格は1000円。", "ne-ja"))
        self.assertEqual(M.hard_count(M.validate("यो ५०० येन हो।", "これは500円です。", "ne-ja")), 0)
        k = self.kinds("म नेपालबाट आएँ।", "ネバダ州シアトル出身。", "ne-ja")
        self.assertIn("PLACE_MISSING", k)
        self.assertIn("PLACE_ADDED", k)
        self.assertEqual(M.hard_count(M.validate("म नेपाली बोल्छु।", "私はネパール語を話します。", "ne-ja")), 0)
        self.assertEqual(M.hard_count(M.validate("राम काठमाडौँमा बस्छन्।", "ラムはカトマンズに住んでいる。", "ne-ja")), 0)

    def test_repetition_and_format(self):
        self.assertEqual(M.format_issues(M.validate("नमस्ते", "ラムラムです", "ne-ja")), "[HARD REPETITION]")
        self.assertEqual(M.validate("x", "  ", "ja-ne"), [("EMPTY", True, None)])

    def test_name_extra_vowel(self):
        self.assertEqual(M.name_extra_vowel("रामु काठमाडौँबाट आए।", ["रामु"]), [("NAME_EXTRA_VOWEL", True, "रामु")])
        self.assertEqual(M.name_extra_vowel("राम काठमाडौँबाट आए।", ["रामु"]), [])

    def test_ir(self):
        ir = M.analyze_ja("ラムさんは三人でネパール語を話した。")
        self.assertEqual(ir["numbers_norm"], ["3"])
        self.assertIn("ネパール", ir["places"])
        self.assertNotIn("ネパール", ir["places_as_place"])
        self.assertEqual(ir["tense"], "PAST")
        ne = M.analyze_ne("म नेपालबाट १,००० रुपैयाँ लिएर आइनँ।")
        self.assertTrue(ne["negated"])
        self.assertEqual(ne["places"], {"ネパール"})
        self.assertEqual(ne["cur"], {"RUPEE"})


class NumberWordsTest(unittest.TestCase):
    def test_words(self):
        self.assertEqual(M.numbers("पच्चीस जना"), ["25"])
        self.assertEqual(M.numbers("एक लाख रुपैयाँ"), ["100000"])
        self.assertEqual(M.kanji_to_digits("一億二千万円"), "120000000円")
        self.assertNotIn("CURRENCY_MISMATCH", [i[0] for i in M.validate("ह्यान्डल घुमाउँछु।", "ハンドルを回します。", "ne-ja")])


class AnswerNoTest(unittest.TestCase):
    def test_leading_iie(self):
        self.assertIn("NEGATION_LOST", [i[0] for i in M.validate("अहँ, छैन।", "いいえ、あります。", "ne-ja")])
        self.assertNotIn("NEGATION_LOST", [i[0] for i in M.validate("अहँ, छैन।", "いいえ、ありません。", "ne-ja")])
        self.assertNotIn("NEGATION_LOST", [i[0] for i in M.validate("होइन", "いいえ", "ne-ja")])


class AntonymTest(unittest.TestCase):
    def test_swap(self):
        self.assertIn("ANTONYM_SWAPPED", [i[0] for i in M.validate("昨日、転びました。", "भोलि लडें।", "ja-ne")])
        self.assertNotIn("ANTONYM_SWAPPED", [i[0] for i in M.validate("昨日、転びました。", "हिजो लडें।", "ja-ne")])
        self.assertIn("ANTONYM_SWAPPED", [i[0] for i in M.validate("दायाँ हात उठाउनुहोस्।", "左手を上げてください。", "ne-ja")])


class WordSenseTest(unittest.TestCase):
    def test_sense(self):
        self.assertIn("WORD_SENSE", [i[0] for i in M.validate("熱を下げる薬です。", "यो ज्वरो लैजाने औषधि हो।", "ja-ne")])
        self.assertNotIn("WORD_SENSE", [i[0] for i in M.validate("お皿を下げますね。", "थाल लैजान्छु है।", "ja-ne")])


class NeJaCoverTest(unittest.TestCase):
    def test_direction_invented(self):
        k = [i[0] for i in M.validate("सीता नेपालबाट आएकी हुन्।", "北ネパールから来たんです。", "ne-ja")]
        self.assertIn("PLACE_ADDED", k)

    def test_negation_without_nai(self):
        for ne, ja in [("चिन्ता नगर्नुहोस्", "大丈夫ですよ"), ("नदौडनुहोस्।", "走るな！"), ("पर्दैन।", "けっこうです。")]:
            self.assertNotIn("NEGATION_LOST", [i[0] for i in M.validate(ne, ja, "ne-ja")])
        self.assertIn("NEGATION_LOST", [i[0] for i in M.validate("आज म जान्नँ।", "今日は行きます。", "ne-ja")])

if __name__ == "__main__":
    unittest.main()
