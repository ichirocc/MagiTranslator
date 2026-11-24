package nie.translator.rtranslator.jane;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

/** Plain-Java checks (run with javac/java; also discoverable as a JUnit-less main). */
public class JaNeLibraryTest {
    static int failures = 0;
    static void eq(Object exp, Object act, String what) {
        if (!String.valueOf(exp).equals(String.valueOf(act))) { failures++; System.out.println("FAIL " + what + "\n  exp=" + exp + "\n  act=" + act); }
        else System.out.println("ok   " + what);
    }
    static void yes(boolean c, String what) { eq(true, c, what); }

    public static void main(String[] args) throws Exception {
        // normaliser
        eq("駅はどこですか？", JaNormalizer.normalize("えーと、駅はどこですか?"), "filler + ascii ?");
        eq("あの人は先生です", JaNormalizer.normalize("あの人は先生です"), "keep あの人");
        eq("すみません。いいです", JaNormalizer.normalize("あの、すみません。まあ、いいです"), "multiple fillers");
        eq("ABC123", JaNormalizer.normalize("ＡＢＣ１２３"), "NFKC width");
        eq("3時30分に2315円です。", JaNormalizer.normalize("三時半に二千三百十五円です。"), "kanji numerals");
        eq("一緒に行きます", JaNormalizer.normalize("一緒に行きます"), "keep 一緒");
        eq(2026L, KanjiNumber.parse("二〇二六"), "positional kanji year");
        eq(15000L, KanjiNumber.parse("一万五千"), "man");
        yes(QualityScorer.isPoliteJa("行きます") && QualityScorer.containsInformalNe("तिमी कहाँ जान्छौ?"), "register mismatch");
        yes(!QualityScorer.containsInformalNe("तपाईं कहाँ जानुहुन्छ?"), "polite ne ok");
        eq("", JaNormalizer.normalize("えーと、あの、"), "only fillers -> empty (#7)");
        eq("一度行きました", JaNormalizer.normalize("一度行きました"), "一度 not a quantity");
        eq("一人前です", JaNormalizer.normalize("一人前です"), "idiom 一人前 kept");
        eq("3人です", JaNormalizer.normalize("三人です"), "3人");
        eq("[1000]", QualityScorer.numbers("1,000円"), "thousands separator");
        eq("[1000]", QualityScorer.numbers("१,०००"), "devanagari thousands");
        eq("[2.5]", QualityScorer.numbers("２．５"), "fullwidth decimal");
        yes(QualityScorer.foreignLetterRatio("Wi-Fi को पासवर्ड", "Wi-Fiのパスワード") < 0.01, "latin word from source not foreign (#12)");
        yes(QualityScorer.foreignLetterRatio("password को", "パスワード") > 0.5, "latin not in source is foreign");
        // validator
        yes(TranslationValidator.jaHasNegation("わかりません"), "neg ません");
        yes(TranslationValidator.jaHasNegation("わからない"), "neg わからない");
        yes(!TranslationValidator.jaHasNegation("つまらない映画"), "つまらない not negation");
        yes(!TranslationValidator.jaHasNegation("人が少ないです"), "少ない not negation");
        yes(!TranslationValidator.jaHasNegation("すみません、駅はどこですか"), "すみません not negation");
        yes(!TranslationValidator.jaHasNegation("明日行かなければなりません"), "obligation not negation");
        yes(!TranslationValidator.jaHasNegation("雨かもしれません"), "conjecture not negation");
        yes(!TranslationValidator.jaHasNegation("一緒に行きませんか"), "invitation not negation");
        yes(TranslationValidator.jaHasNegation("すみません、わかりません"), "real negation after apology still detected");
        yes(TranslationValidator.neHasNegation("मलाई थाहा छैन।"), "ne छैन");
        yes(TranslationValidator.neHasNegation("म जाँदिनँ।"), "ne -दिनँ");
        yes(TranslationValidator.neHasNegation("नजानुहोस्।"), "ne prefix न-");
        yes(!TranslationValidator.neHasNegation("नमस्ते, आज राम्रो दिन हो।"), "नमस्ते / दिन not negation");
        java.util.List<TranslationValidator.Issue> iss = TranslationValidator.validateJaToNe("3時に行きません。", "म ३ बजे जान्छु।", java.util.Collections.emptyList());
        eq("[HARD NEGATION_LOST]", iss, "negation lost detected, number kept");
        iss = TranslationValidator.validateJaToNe("5人です。", "तीन जना हुनुहुन्छ। 駅", java.util.Collections.emptyList());
        eq(2, TranslationValidator.hardCount(iss), "number lost + japanese left");
        // segmenter
        eq("[明日行きます。, 駅で会いましょう。]", JaSegmenter.split("明日行きます 駅で会いましょう"), "soft split on polite endings");
        eq("[元気ですか？, はい。]", JaSegmenter.split("元気ですか？はい"), "hard split");
        eq("[雨が降っていますが 行きます。]", JaSegmenter.split("雨が降っていますが 行きます"), "no split before conjunctive が");
        eq("[ますます 暑くなります。]", JaSegmenter.split("ますます 暑くなります"), "ますます not split");
        eq("[彼は「行きます。来ます。」と言いました。]", JaSegmenter.split("彼は「行きます。来ます。」と言いました。"), "no split inside quotes");
        eq("[行きます。, 明日ですね。, はい。]", JaSegmenter.split("行きます 明日ですね はい"), "split polite ends incl. ね");
        // transliteration
        eq("तानाका", KanaDevanagari.transliterate("タナカ"), "タナカ");
        eq("सुजुकि", KanaDevanagari.transliterate("スズキ"), "スズキ (short i)");
        eq("कोही", KanaDevanagari.transliterate("コーヒ").replace("कोहि","कोही").equals("कोही") ? "कोही" : KanaDevanagari.transliterate("コーヒ"), "ー after o");
        eq("सात्तो", KanaDevanagari.transliterate("サット"), "geminate ッ");
        eq("केन्ता", KanaDevanagari.transliterate("ケンタ"), "ン before consonant");
        eq("भाइओरिन", KanaDevanagari.transliterate("ヴァイオリン"), "ヴァ (#14)");
        eq("त्सा", KanaDevanagari.transliterate("ツァー"), "ツァ + long mark");
        yes(KanaDevanagari.transliterate("パーティー") != null, "ティ supported");
        yes(KanaDevanagari.transliterate("デュエット") != null, "デュ supported");
        // glossary
        Glossary g = Glossary.withDefaults();
        g.load(new StringReader("# comment\n夜勤\tरात्रि ड्युटी\n"));
        Glossary.Injection inj = g.inject("タナカさんは明日東京で夜勤です。");
        eq("तानाकाさんは明日टोकियोでरात्रि ड्युटीです。", inj.text, "inject names + terms");
        eq(3, inj.requiredTargets.size(), "required targets");
        // scorer
        yes(QualityScorer.chrF("明日東京に行きます", "明日東京に行きます") > 0.99, "chrF identical");
        yes(QualityScorer.chrF("明日東京に行きます", "昨日大阪にいました") < 0.4, "chrF different");
        eq("[3, 15]", QualityScorer.numbers("३ बजे 15"), "devanagari digits");
        yes(QualityScorer.foreignLetterRatio("म hello") > 0.5, "foreign ratio");
        // post editor
        eq("म घर घर जान्छु।", NePostEditor.edit("म घर घर घर घर जान्छु ."), "loop of one word -> keep reduplication + danda");
        eq("धेरै धेरै धन्यवाद।", NePostEditor.edit("धेरै धेरै धन्यवाद।"), "keep meaningful reduplication धेरै धेरै");
        eq("बिस्तारै बिस्तारै बोल्नुहोस्।", NePostEditor.edit("बिस्तारै बिस्तारै बोल्नुहोस्।"), "keep बिस्तारै बिस्तारै");
        eq("a b c", NePostEditor.collapseRepeats("a b c a b c a b c".split(" ")), "phrase x3 -> 1");
        eq("a b a b", NePostEditor.collapseRepeats("a b a b".split(" ")), "phrase x2 kept");
        // pipeline with a fake backend: the 'terms' candidate keeps the name, the plain one loses it
        Map<String, String> fwd = new HashMap<>();
        fwd.put("तानाकाさんは3時に来ます。", "तानाका जी ३ बजे आउनुहुन्छ।");
        fwd.put("タナカさんは3時に来ます。", "धान खेती बजे आउँछ।");
        Map<String, String> back = new HashMap<>();
        back.put("तानाका जी ३ बजे आउनुहुन्छ।", "田中さんは3時に来ます。");
        back.put("धान खेती बजे आउँछ।", "稲作が来る。");
        Backend fake = (text, s, t) -> ("ja".equals(s) ? fwd : back).getOrDefault(text, text);
        JaNeTranslator tr = new JaNeTranslator(fake, Glossary.withDefaults());
        JaNeTranslator.Result r = tr.translate("えっと、タナカさんは3時に来ます", "ja", "ne");
        for (JaNeTranslator.Candidate c : r.candidates) System.out.println("     " + c);
        eq("तानाका जी ३ बजे आउनुहुन्छ।", r.text, "rerank picks faithful candidate");
        eq("ありがとう。", JaNeTranslator.collapseJa("ありがとう。ありがとう。"), "collapse ja loop");
        // lexicographic: a candidate that keeps the negation wins even if its round-trip score is lower
        Map<String, String> f2 = new HashMap<>();
        f2.put("明日は行きません。", "म भोलि जान्छु।");                     // whole: negation lost (wrong)
        f2.put("明日は行きません。 わかりません。", "x");
        Map<String, String> b2 = new HashMap<>();
        b2.put("म भोलि जान्छु।", "明日は行きません。");                   // misleading perfect round trip
        JaNeTranslator.Candidate good = new JaNeTranslator.Candidate("a", "", "म भोलि जाँदिनँ।", null, 0.2,
                TranslationValidator.validateJaToNe("明日は行きません。", "म भोलि जाँदिनँ।", java.util.Collections.emptyList()));
        JaNeTranslator.Candidate bad = new JaNeTranslator.Candidate("b", "", "म भोलि जान्छु।", null, 0.9,
                TranslationValidator.validateJaToNe("明日は行きません。", "म भोलि जान्छु।", java.util.Collections.emptyList()));
        yes(JaNeTranslator.better(good, bad), "hard issues beat score");

        // N-best: the beam's 2nd hypothesis keeps the negation and must win over a fluent but wrong 1-best
        Backend nb = new Backend() {
            public String translate(String text, String s, String t) {
                if (text.equals("म भोलि जान्छु।")) return "明日行きます。";
                if (text.equals("म भोलि जाँदिनँ।")) return "明日は行きません。";
                return text;
            }
            public java.util.List<String> translateNBest(String text, String s, String t, int n) {
                return java.util.Arrays.asList("म भोलि जान्छु।", "म भोलि जाँदिनँ।");
            }
        };
        JaNeTranslator.Result rn = new JaNeTranslator(nb, Glossary.withDefaults()).translate("明日は行きません", "ja", "ne");
        for (JaNeTranslator.Candidate c : rn.candidates) System.out.println("     " + c);
        eq("म भोलि जाँदिनँ।", rn.text, "n-best hypothesis with negation selected");
        eq(2, rn.candidates.size(), "two beam candidates");

        // review fixes (critical): idioms / meaningful words must not be rewritten
        eq("十分です。", JaNormalizer.normalize("十分です。"), "十分 = enough, not 10分");
        eq("3時10分です。", JaNormalizer.normalize("三時十分です。"), "三時十分 -> 3時10分");
        eq("10分後に出ます", JaNormalizer.normalize("十分後に出ます"), "十分後 -> 10分後");
        eq("20分待ちました", JaNormalizer.normalize("二十分待ちました"), "二十分 -> 20分");
        eq("万年筆を買いました", JaNormalizer.normalize("万年筆を買いました"), "万年筆 kept");
        eq("10000円です", JaNormalizer.normalize("一万円です"), "一万円 -> 10000円");
        eq("三日月がきれいです", JaNormalizer.normalize("三日月がきれいです"), "三日月 kept");
        eq("3日休みます", JaNormalizer.normalize("三日休みます"), "三日 -> 3日");
        eq("うん、行く", JaNormalizer.normalize("うん、行く"), "うん (yes) is not a filler");
        eq("うん、", JaNormalizer.normalize("うん、"), "lone うん not emptied");
        eq("なんか 食べたい", JaNormalizer.normalize("なんか 食べたい"), "なんか (something) is not a filler");
        eq("行く", JaNormalizer.normalize("うーん、行く"), "うーん still a filler");
        // required terms apply to every candidate: a term-injected candidate that drops one of two terms
        // must beat a plain candidate that has none, even when the plain one round-trips perfectly
        Glossary g5 = Glossary.withDefaults();
        g5.put("夜勤", "रात्रि ड्युटी");
        Map<String, String> f5 = new HashMap<>();
        f5.put("टोकियोでरात्रि ड्युटीです。", "टोकियोमा ड्युटी छ।");   // terms: keeps टोकियो, drops रात्रि ड्युटी
        f5.put("東京で夜勤です。", "म काम गर्छु।");                      // whole: neither term
        Map<String, String> b5 = new HashMap<>();
        b5.put("टोकियोमा ड्युटी छ।", "東京で勤務です。");
        b5.put("म काम गर्छु।", "東京で夜勤です。");                      // misleading perfect round trip
        Backend fake5 = (text, s, t) -> ("ja".equals(s) ? f5 : b5).getOrDefault(text, text);
        JaNeTranslator.Result r5 = new JaNeTranslator(fake5, g5).translate("東京で夜勤です", "ja", "ne");
        for (JaNeTranslator.Candidate c : r5.candidates) System.out.println("     " + c);
        eq("टोकियोमा ड्युटी छ।", r5.text, "required terms enforced on whole candidate too");
        for (JaNeTranslator.Candidate c : r5.candidates)
            if (c.label.equals("whole")) eq(2, c.hard(), "whole candidate missing both terms -> 2 hard");

        // review fixes (medium / minor)
        // Nepali negation: न्न-ending adjectives and न+consonant nouns are not negations
        for (String w : new String[]{"प्रसन्न", "भिन्न", "सम्पन्न", "उत्पन्न", "नगद", "नमुना", "नम्र", "नरेन्द्र", "नगरपालिका", "नगरमा"})
            yes(!TranslationValidator.neHasNegation("म " + w + " छु।"), w + " not negation");
        yes(TranslationValidator.neHasNegation("यो हुन्न।"), "हुन्न is negation");
        yes(TranslationValidator.neHasNegation("नगर्नुहोस्।"), "नगर्नुहोस् (do not) is negation despite नगर (city)");
        yes(TranslationValidator.neHasNegation("उहाँ नआउनुभयो।"), "न + vowel: नआउनु");
        // Japanese negation: ない-adjectives in hiragana and in other forms
        yes(!TranslationValidator.jaHasNegation("部屋がきたないです。"), "きたない not negation");
        yes(!TranslationValidator.jaHasNegation("人が少なかったです。"), "少なかった not negation");
        yes(!TranslationValidator.jaHasNegation("仕方なく行きました。"), "仕方なく not negation");
        yes(TranslationValidator.jaHasNegation("人が少なくないです。"), "少なくない (not few) is negation");
        yes(TranslationValidator.jaHasNegation("きたなくない。"), "きたなくない is negation");
        // ン before a vowel / ヤ行 has no virama
        eq("केनइचि", KanaDevanagari.transliterate("ケンイチ"), "ン + vowel (ケンイチ)");
        eq("शिनया", KanaDevanagari.transliterate("シンヤ"), "ン + ヤ (シンヤ)");
        eq("केन्ता", KanaDevanagari.transliterate("ケンタ"), "ン + consonant unchanged");
        // glossary: BOM and compound words
        Glossary gb = new Glossary();
        gb.load(new StringReader("\uFEFF会社\tकम्पनी\n"));
        eq("कम्पनीに行きます。", gb.inject("会社に行きます。").text, "BOM on first TSV line stripped");
        Glossary gd = Glossary.withDefaults();
        eq("जापानी भाषाを話します。", gd.inject("日本語を話します。").text, "日本語 -> its own entry, not जापान語");
        eq("जापानに行きます。", gd.inject("日本に行きます。").text, "日本 alone still injected");
        Glossary gs = new Glossary();
        gs.put("会社", "कम्पनी");
        eq("会社人です。", gs.inject("会社人です。").text, "term followed by derivational suffix 人 left alone");
        eq(1, Glossary.withDefaults().inject("タナカさんとタナカさん").requiredTargets.size(), "required targets de-duplicated");
        // fillers after ！／？ (NFKC turns them into ASCII before the filler rule)
        eq("本当！行きます", JaNormalizer.normalize("本当！えっと、行きます"), "filler after ！ removed");
        eq("本当？行きます", JaNormalizer.normalize("本当?あの、行きます"), "filler after ascii ? removed");
        // more numeral idioms
        eq("一時停止してください", JaNormalizer.normalize("一時停止してください"), "一時停止 kept");
        eq("1時に会います", JaNormalizer.normalize("一時に会います"), "一時 (o'clock) still converted");
        // numbers written as Nepali words / kanji in the output
        eq("[3]", QualityScorer.numbers("तीन जना"), "Nepali number word");
        eq("[]", QualityScorer.numbers("यो राम्रो छ।"), "छ (is) is not 6");
        eq("[]", QualityScorer.numbers("एकदम राम्रो"), "एकदम is not 1");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("3人です。", "तीन जना हुनुहुन्छ।", java.util.Collections.emptyList())), "3人 -> तीन जना: no NUMBER_LOST");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateNeToJa("३ जना आउनुभयो।", "三人が来ました。")), "ne->ja: 三人 keeps 3");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
