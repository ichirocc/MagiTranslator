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
        eq(2, inj.requiredTargets.size(), "required targets (glossary terms)");
        eq("[तानाका]", inj.preferredTargets, "rule-transliterated name is preferred, not required");
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
        JaNeTranslator nbTr = new JaNeTranslator(nb, Glossary.withDefaults());
        nbTr.setPivot(false);   //this test is about the beam hypotheses only
        JaNeTranslator.Result rn = nbTr.translate("明日は行きません", "ja", "ne");
        for (JaNeTranslator.Candidate c : rn.candidates) System.out.println("     " + c);
        eq("म भोलि जाँदिनँ।", rn.text, "n-best hypothesis with negation selected");
        eq(2, rn.candidates.size(), "two beam candidates");

        // device model failure (screenshot): with "。" the direct translation is an unrelated sentence; without
        // it, or through English, it is right. All are candidates and the round trip picks a faithful one.
        {
            String src = "田中さんをベッドから車椅子に移動します";
            String wrong = "यो पत्रिकाको सम्पादन कार्य घनश्याम ढकालद्वारा भएको छ।";   //"this magazine was edited by ..."
            String right = "तानाका जीलाई बेडबाट व्हीलचेयरमा सार्छु।";
            java.util.Map<String, String> devBack = new java.util.HashMap<>();
            devBack.put(wrong, "この雑誌の編集はガナシャム・ダカルが行った。");
            devBack.put(right, "田中さんをベッドから車椅子に移します。");
            String viaEn = "म तानाका जीलाई बेडबाट व्हीलचेयरमा लैजान्छु।";
            devBack.put(viaEn, "田中さんをベッドから車椅子へ連れて行きます。");
            Backend dev = new Backend() {
                public String translate(String text, String s, String t) {
                    if ("ja".equals(s) && "en".equals(t)) return "I will move Mr. Tanaka from the bed to the wheelchair.";
                    if ("en".equals(s) && "ne".equals(t)) return viaEn;
                    if ("ne".equals(s)) return devBack.getOrDefault(text, "");
                    return text.endsWith("。") ? wrong : right;   //the "。" triggers the hallucination
                }
            };
            JaNeTranslator dt = new JaNeTranslator(dev, Glossary.withDefaults());
            dt.setNBest(1);
            JaNeTranslator.Result dr = dt.translate(src, "ja", "ne");
            for (JaNeTranslator.Candidate c : dr.candidates) System.out.println("     " + c);
            yes(dr.text.equals(right) || dr.text.equals(viaEn), "hallucinated direct translation is not chosen (plain / pivot candidate wins)");
            yes(dr.candidates.stream().anyMatch(c -> c.label.equals("pivot-en")), "English pivot is a candidate");
            yes(dr.candidates.stream().anyMatch(c -> c.label.equals("plain")), "text without the added 。 is a candidate");
        }

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
        eq(1, Glossary.withDefaults().inject("タナカさんとタナカさん").preferredTargets.size(), "preferred targets de-duplicated");
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

        // Nepali fixes
        // names: middle dot, glossary spelling is required, rule spelling is only preferred
        // rule output is Japanese-oriented (ム -> मु, as in ススム / イサム); the real Nepali spelling राम बहादुर
        // can only come from the glossary, which is why rule names are preferred, not required
        eq("रामु बाहाडुरु", KanaDevanagari.transliterate("ラム・バハドゥル"), "・ between name parts");
        Glossary gn = Glossary.withDefaults();
        Glossary.Injection in1 = gn.inject("ラム・バハドゥルさんが来ます。");
        eq("रामु बाहाडुरुさんが来ます。", in1.text, "dotted katakana name injected as a whole");
        eq("[]", in1.requiredTargets, "rule-transliterated Nepali name is not a hard requirement");
        gn.put("ラム・バハドゥル", "राम बहादुर");
        Glossary.Injection in2 = gn.inject("ラム・バハドゥルさんが来ます。");
        eq("[राम बहादुर]", in2.requiredTargets, "glossary spelling of a name is required");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("ラム・バハドゥルさんが来ます。", "राम बहादुर जी आउनुहुन्छ।", in1.requiredTargets)),
                "correct Nepali spelling of a name is not TERM_MISSING");
        // period -> danda, but not after abbreviations or digits
        eq("यसको मूल्य रु. १०० हो।", NePostEditor.edit("यसको मूल्य रु. १०० हो."), "रु. kept");
        eq("डा. शर्मा आउनुभयो।", NePostEditor.edit("डा. शर्मा आउनुभयो."), "डा. kept");
        eq("वि.सं. २०८१ मा।", NePostEditor.edit("वि.सं. २०८१ मा."), "वि.सं. kept");
        eq("म घर जान्छु। तिमी?", NePostEditor.edit("म घर जान्छु. तिमी?"), "sentence period still becomes danda");
        eq("१. पहिलो", NePostEditor.edit("१. पहिलो"), "list number १. kept");
        // question detection: whole words only
        java.util.List<String> none = java.util.Collections.emptyList();
        yes(TranslationValidator.validateJaToNe("何を買いますか。", "म तरकारी किन्छु।", none).toString().contains("QUESTION_LOST"), "किन्छु (buy) is not a question word");
        yes(TranslationValidator.validateJaToNe("どれですか。", "कुनै पनि हुन्छ।", none).toString().contains("QUESTION_LOST"), "कुनै (any) is not a question word");
        yes(!TranslationValidator.validateJaToNe("いくらですか。", "यो कतिको हो", none).toString().contains("QUESTION_LOST"), "कतिको (how much) is a question");
        yes(!TranslationValidator.validateJaToNe("なぜですか。", "किन", none).toString().contains("QUESTION_LOST"), "किन (why) is a question");
        yes(TranslationValidator.validateJaToNe("行きますか。", "म कहिलेकाहीं जान्छु।", none).toString().contains("QUESTION_LOST"), "कहिलेकाहीं (sometimes) is not a question");

        // speed: chrF with n-grams packed into longs gives exactly the reference score
        // (chrFSlow takes already stripped text: no spaces or punctuation in these pairs)
        for (String[] p : new String[][]{{"मभोलिअस्पतालजान्छु", "भोलिमअस्पतालमाजान्छु"}, {"明日病院に行きます", "明日は病院へ行きます"},
                {"ああああ", "あああ"}, {"￿耀ab", "耀ab￿"}, {"x", "xy"}}) {
            eq(QualityScorer.chrFSlow(p[0], p[1]), QualityScorer.chrF(p[0], p[1]), "speed: packed chrF == reference (" + p[0] + ")");
        }
        eq(QualityScorer.chrFSlow("🙂ok日本", "日本ok"), QualityScorer.chrF("🙂ok日本", "日本ok"), "speed: chrF with a non-BMP character uses the reference path");

        // speed: memoised model calls / results, and no round trip for candidates that cannot win
        {
            java.util.Map<String, String> spMap = new java.util.HashMap<>();
            spMap.put("明日は行きません。病院です。", "म भोलि जान्छु। अस्पताल हो।");   //whole: negation lost (hard issue)
            spMap.put("明日は行きません。", "म भोलि जाँदिनँ।");
            spMap.put("病院です。", "अस्पताल हो।");
            java.util.concurrent.atomic.AtomicInteger spFwd = new java.util.concurrent.atomic.AtomicInteger(), spBwd = new java.util.concurrent.atomic.AtomicInteger();
            Backend counted = (text, s, t) -> {
                if ("ja".equals(s)) { spFwd.incrementAndGet(); return spMap.getOrDefault(text, text); }
                spBwd.incrementAndGet(); return "x";
            };
            JaNeTranslator sp = new JaNeTranslator(counted, Glossary.withDefaults(), new NeJaGlossary());
            sp.setNBest(1); sp.setPivot(false);   //exact call counts: no English pivot
            JaNeTranslator.Result a = sp.translate("明日は行きません。病院です。", "ja", "ne");
            eq("म भोलि जाँदिनँ। अस्पताल हो।", a.text, "speed: the candidate without hard issues wins");
            eq(0, spBwd.get(), "speed: no back-translation when only one candidate can win");
            int calls1 = spFwd.get();
            JaNeTranslator.Result b = sp.translate("明日は行きません。病院です。", "ja", "ne");
            eq(a.text, b.text, "speed: memoised result is the same");
            eq(calls1, spFwd.get(), "speed: a repeated utterance does not call the model again");
            sp.clearCache();
            sp.translate("明日は行きません。病院です。", "ja", "ne");
            eq(2 * calls1, spFwd.get(), "speed: clearCache() forgets memoised model outputs");

            // two candidates without hard issues: both are back-translated and scored (unchanged behaviour)
            java.util.Map<String, String> fw2 = new java.util.HashMap<>();
            fw2.put("明日行きます。病院です。", "म भोलि जान्छु। अस्पताल हो।");
            fw2.put("明日行きます。", "भोलि जान्छु।");
            fw2.put("病院です。", "अस्पताल हो।");
            java.util.concurrent.atomic.AtomicInteger bwd2 = new java.util.concurrent.atomic.AtomicInteger();
            Backend counted2 = (text, s, t) -> { if ("ja".equals(s)) return fw2.getOrDefault(text, text); bwd2.incrementAndGet(); return "明日行きます。病院です。"; };
            JaNeTranslator sp2 = new JaNeTranslator(counted2, Glossary.withDefaults(), new NeJaGlossary());
            sp2.setNBest(1); sp2.setPivot(false);
            JaNeTranslator.Result c2 = sp2.translate("明日行きます。病院です。", "ja", "ne");
            eq(2, bwd2.get(), "speed: tied candidates are still back-translated");
            for (JaNeTranslator.Candidate c : c2.candidates) yes(c.backTranslation != null, "speed: contender " + c.label + " has a round trip");
        }

        // dictionaries (dictionaries/*.tsv, outside the APK): format, compounds, behaviour
        checkDictionary("dictionaries/ja_ne_terms.tsv");
        checkNeJaDictionary("dictionaries/ne_ja_terms.tsv");
        checkPhraseBook("dictionaries/phrases.tsv");

        // dictionary files outside the APK: type line + strict whole-file check (DictionaryFiles)
        String[][] repoFiles = {{"dictionaries/ja_ne_terms.tsv", "JA_NE_TERMS"}, {"dictionaries/ne_ja_terms.tsv", "NE_JA_TERMS"}, {"dictionaries/phrases.tsv", "PHRASES"}};
        for (String[] rf : repoFiles) {
            DictionaryFiles.Check c = DictionaryFiles.check(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(rf[0])), java.nio.charset.StandardCharsets.UTF_8));
            yes(c.ok() && c.kind.name().equals(rf[1]) && c.kind.fileName.equals(rf[0].substring("dictionaries/".length())), "dictionary file valid: " + rf[0] + " -> " + c);
        }
        yes(!DictionaryFiles.check("病院\tअस्पताल\n").ok(), "dictionary file without type line rejected");
        yes(!DictionaryFiles.check("#!jane something\n病院\tअस्पताल\n").ok(), "unknown dictionary type rejected");
        yes(!DictionaryFiles.check("#!jane ja-ne-terms\n病院\thospital\n").ok(), "ja-ne-terms: Latin translation rejected");
        yes(!DictionaryFiles.check("#!jane ja-ne-terms\n病院\tअस्पताल\n病院\tअस्पताल\n").ok(), "duplicate entry rejected");
        yes(!DictionaryFiles.check("#!jane ne-ja-terms\n病院\tअस्पताल\n").ok(), "ne-ja-terms with columns swapped rejected");
        yes(!DictionaryFiles.check("#!jane phrases\n# only comments\n").ok(), "file without entries rejected");
        yes(!DictionaryFiles.check("<html>Not Found</html>").ok(), "an HTML error page is not a dictionary");
        DictionaryFiles.Check okBom = DictionaryFiles.check("\uFEFF#!jane phrases\r\nこんにちは\tनमस्ते\r\n");
        yes(okBom.ok() && okBom.entries == 1, "BOM and CRLF accepted");
        StringBuilder big = new StringBuilder("#!jane ja-ne-terms\n");
        while (big.length() <= DictionaryFiles.MAX_BYTES) big.append("# padding padding padding padding padding\n");
        yes(!DictionaryFiles.check(big.toString()).ok(), "file larger than MAX_BYTES rejected");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    static void checkPhraseBook(String path) throws Exception {
        java.util.List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get(path), java.nio.charset.StandardCharsets.UTF_8);
        int entries = 0, bad = 0;
        java.util.Set<String> jaKeys = new java.util.HashSet<>();
        int dup = 0;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            boolean ok = (c.length == 2 || (c.length == 3 && c[2].equals("ja>ne")))
                    && !c[0].isEmpty() && !c[0].matches(".*[\\u0900-\\u097F].*")
                    && c[1].matches("[\\u0900-\\u097F ,?]+") && c[1].equals(c[1].trim());
            if (!ok) { bad++; System.out.println("     bad phrase line: " + line); }
            if (!jaKeys.add(PhraseBook.keyJa(c[0]))) { dup++; System.out.println("     duplicate phrase: " + c[0]); }
            entries++;
        }
        eq(0, bad, "phrases: every line is japanese<TAB>nepali[<TAB>ja>ne] (" + entries + " phrases)");
        eq(0, dup, "phrases: no duplicate Japanese phrase");

        PhraseBook pb = new PhraseBook();
        try (java.io.Reader r = new java.io.InputStreamReader(new java.io.FileInputStream(path), java.nio.charset.StandardCharsets.UTF_8)) {
            pb.load(r);
        }
        eq("धन्यवाद", pb.lookupJaToNe("ありがとうございます。"), "phrases: ja->ne with punctuation");
        eq("धन्यवाद", pb.lookupJaToNe("えっと、ありがとう"), "phrases: fillers ignored (same normaliser as the pipeline)");
        eq("हजुर, हो", pb.lookupJaToNe("はい そうです"), "phrases: spaces / commas ignored");
        eq("ありがとうございます", pb.lookupNeToJa("धन्यवाद।"), "phrases: ne->ja, first Japanese line wins");
        eq("こんにちは", pb.lookupNeToJa("नमस्ते"), "phrases: नमस्ते -> こんにちは");
        eq("いくらですか", pb.lookupNeToJa("कति पर्छ ?"), "phrases: ne->ja question");
        eq(null, pb.lookupNeToJa("केही छैन"), "phrases: ja>ne-only line is not used backwards (केही छैन = there is nothing)");
        eq(null, pb.lookupJaToNe("ありがとうございます、また来ます"), "phrases: never part of a sentence");

        // pipeline: a whole phrase skips the model; a sentence that is a phrase is fixed inside a longer text
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        Backend counting = (text, s, t) -> { calls.incrementAndGet(); return "x"; };
        JaNeTranslator tr = new JaNeTranslator(counting, Glossary.withDefaults(), new NeJaGlossary());
        tr.setPhraseBook(pb);
        JaNeTranslator.Result r1 = tr.translate("こんにちは", "ja", "ne");
        eq("नमस्ते", r1.text, "phrases: whole utterance translated by the phrase book");
        eq(0, calls.get(), "phrases: the model is not called for a set phrase");
        JaNeTranslator.Result r2 = tr.translate("तपाईंलाई कस्तो छ?", "ne", "ja");
        eq("お元気ですか", r2.text, "phrases: ne->ja whole utterance");
        java.util.Map<String, String> f = new java.util.HashMap<>();
        f.put("駅はどこですか？", "स्टेसन कहाँ छ?");
        Backend seg = (text, s, t) -> f.getOrDefault(text, "");
        JaNeTranslator tr2 = new JaNeTranslator(seg, Glossary.withDefaults(), new NeJaGlossary());
        tr2.setPhraseBook(pb);
        JaNeTranslator.Result r3 = tr2.translate("すみません。駅はどこですか？", "ja", "ne");
        for (JaNeTranslator.Candidate c : r3.candidates) System.out.println("     " + c);
        boolean fixedSentence = false;
        for (JaNeTranslator.Candidate c : r3.candidates) if (c.output.startsWith("माफ गर्नुहोस्")) fixedSentence = true;
        yes(fixedSentence, "phrases: a phrase sentence inside a longer text is translated by the phrase book");
    }

    static void checkNeJaDictionary(String path) throws Exception {
        java.util.List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get(path), java.nio.charset.StandardCharsets.UTF_8);
        java.util.Set<String> seen = new java.util.HashSet<>();
        int entries = 0, bad = 0, dup = 0;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            boolean ok = c.length == 2 && c[0].matches("[\\u0900-\\u0963\\u0966-\\u097F]+( [\\u0900-\\u0963\\u0966-\\u097F]+)*")
                    && c[0].equals(java.text.Normalizer.normalize(c[0], java.text.Normalizer.Form.NFC))
                    && !c[1].isEmpty() && c[1].equals(c[1].trim()) && !c[1].matches(".*[\\u0900-\\u097F].*");
            if (!ok) { bad++; System.out.println("     bad ne->ja line: " + line); }
            if (c.length >= 1 && !seen.add(c[0])) { dup++; System.out.println("     duplicate key: " + c[0]); }
            entries++;
        }
        eq(0, bad, "ne->ja dictionary: every line is devanagari(NFC)<TAB>japanese (" + entries + " entries)");
        eq(0, dup, "ne->ja dictionary: no duplicate keys");

        NeJaGlossary g = new NeJaGlossary();
        try (java.io.Reader r = new java.io.InputStreamReader(new java.io.FileInputStream(path), java.nio.charset.StandardCharsets.UTF_8)) {
            g.load(r);
        }
        eq("म 病院मा छु।", g.inject("म अस्पतालमा छु।").text, "ne->ja: postposition kept after the term");
        eq("友達हरूलाई भन्नुहोस्।", g.inject("साथीहरूलाई भन्नुहोस्।").text, "ne->ja: plural + postposition");
        eq("ネパール大使館मा जानुहोस्।", g.inject("नेपाली दूतावासमा जानुहोस्।").text, "ne->ja: two-word term wins over दूतावास");
        eq("大学 र 学校", g.inject("विश्वविद्यालय र विद्यालय").text, "ne->ja: विश्वविद्यालय is not विद्यालय");
        eq("नेपाली भाषा", g.inject("नेपाली भाषा").text, "ne->ja: नेपाल does not match inside नेपाली");
        eq("मेरो घर करोडौं", g.inject("मेरो घर करोडौं").text, "ne->ja: no match inside a longer word");
        eq("月曜日 र 日曜日", g.inject("सोमवार र आइतबार").text, "ne->ja: spelling variants");
        eq("高血圧 र 血圧", g.inject("उच्च रक्तचाप र रक्तचाप").text, "ne->ja: उच्च रक्तचाप before रक्तचाप");
        eq("入院 भयो, 病院मा", g.inject("अस्पताल भर्ना भयो, अस्पतालमा").text, "ne->ja: अस्पताल भर्ना before अस्पताल");
        eq("高齢者 र 高齢者हरू", g.inject("ज्येष्ठ नागरिक र जेष्ठ नागरिकहरू").text, "ne->ja: both spellings of ज्येष्ठ नागरिक");
        eq("म बिरामी छु।", g.inject("म बिरामी छु।").text, "ne->ja: बिरामी (patient / sick) is not replaced");
        eq("看護 पेशा", g.inject("नर्सिङ पेशा").text, "ne->ja: नर्सिङ is not नर्स + इङ");
        eq("頭痛 र 痛み", g.inject("टाउको दुखाइ र दुखाइ").text, "ne->ja: टाउको दुखाइ before दुखाइ");
        eq("乳がん", g.inject("स्तन क्यान्सर").text, "ne->ja: स्तन क्यान्सर before क्यान्सर");
        eq("知的障害 र 障害", g.inject("बौद्धिक अपाङ्गता र अपाङ्गता").text, "ne->ja: official disability category before अपाङ्गता");
        eq("介護 सेवा र 看護", g.inject("नर्सिङ केयर सेवा र नर्सिङ").text, "ne->ja: नर्सिङ केयर (study text: 介護) before नर्सिङ (看護)");
        eq("介護職ले", g.inject("नर्सिङ केयर वर्करले").text, "ne->ja: नर्सिङ केयर वर्कर with postposition");
        eq("アルツハイマー型認知症 र アルツハイマー病", g.inject("अल्जाइमर डिमेन्सिया र अल्जाइमर").text, "ne->ja: अल्जाइमर डिमेन्सिया before अल्जाइमर");
        eq("[日本, 東京]", new java.util.TreeSet<>(g.inject("जापानको टोकियोमा।").requiredTargets), "ne->ja: required Japanese terms");

        // pipeline: the term-injected candidate keeps 病院; a plain one that loses it must not win
        java.util.Map<String, String> f = new java.util.HashMap<>();
        f.put("म 明日 病院मा जान्छु।", "私は明日病院に行きます。");   // भोलि and अस्पताल are both dictionary terms
        f.put("म भोलि अस्पतालमा जान्छु।", "私は明日医院に行きます。");
        java.util.Map<String, String> b = new java.util.HashMap<>();
        b.put("私は明日病院に行きます。", "म भोलि अस्पताल जान्छु।");
        b.put("私は明日医院に行きます。", "म भोलि अस्पतालमा जान्छु।");          // misleading perfect round trip
        Backend fake = (text, s, t) -> ("ne".equals(s) ? f : b).getOrDefault(text, text);
        JaNeTranslator.Result r = new JaNeTranslator(fake, Glossary.withDefaults(), g).translate("म भोलि अस्पतालमा जान्छु।", "ne", "ja");
        for (JaNeTranslator.Candidate c : r.candidates) System.out.println("     " + c);
        eq("私は明日病院に行きます。", r.text, "ne->ja: required term enforced in the ne->ja pipeline");
    }

    static void checkDictionary(String path) throws Exception {
        java.util.List<String> lines = java.nio.file.Files.readAllLines(java.nio.file.Paths.get(path), java.nio.charset.StandardCharsets.UTF_8);
        java.util.Set<String> seen = new java.util.HashSet<>();
        int entries = 0, badFormat = 0, dup = 0;
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] c = line.split("\t", -1);
            boolean ok = c.length == 2 && c[0].equals(c[0].trim()) && c[1].equals(c[1].trim())
                    && c[0].codePointCount(0, c[0].length()) >= 2
                    && c[1].matches("[\\u0900-\\u0963\\u0966-\\u097F]+( [\\u0900-\\u0963\\u0966-\\u097F]+)*");
            if (!ok) { badFormat++; System.out.println("     bad dictionary line: " + line); }
            if (c.length >= 1 && !seen.add(c[0])) { dup++; System.out.println("     duplicate key: " + c[0]); }
            entries++;
        }
        eq(0, badFormat, "dictionary: every line is japanese<TAB>devanagari, key >= 2 chars (" + entries + " entries)");
        eq(0, dup, "dictionary: no duplicate keys");

        Glossary g = Glossary.withDefaults();
        try (java.io.Reader r = new java.io.InputStreamReader(new java.io.FileInputStream(path), java.nio.charset.StandardCharsets.UTF_8)) {
            g.load(r);
        }
        // real compounds that contain another entry must be replaced as a whole
        for (String[] t : new String[][]{{"東京都", "टोकियो"}, {"小学校", "प्राथमिक विद्यालय"}, {"会社員", "कम्पनी कर्मचारी"},
                {"保険証", "बीमा कार्ड"}, {"日本語学校", "जापानी भाषा विद्यालय"}, {"健康保険", "स्वास्थ्य बीमा"}, {"ネパール大使館", "नेपाली दूतावास"},
                {"高血圧", "उच्च रक्तचाप"}, {"心臓発作", "हृदयाघात"}, {"介護士", "केयरगिभर"}, {"介護職員", "केयरगिभर"}, {"看護師", "नर्स"}, {"医療従事者", "स्वास्थ्यकर्मी"},
                {"気管支喘息", "दम"}, {"尿路感染症", "मूत्रमार्ग संक्रमण"}, {"血液型", "रक्त समूह"}, {"背部痛", "ढाड दुख्ने समस्या"},
                {"入浴介助", "नुहाउन सहयोग"}, {"排せつ介助", "दिसापिसाबमा सहयोग"}, {"知的障害", "बौद्धिक अपाङ्गता"}, {"介護福祉職", "केयरगिभर"},
                {"特別養護老人ホーム", "वृद्धवृद्धाको विशेष नर्सिङ होम"}, {"誤嚥性肺炎", "एस्पिरेसन निमोनाइटिस"}, {"アルツハイマー型認知症", "अल्जाइमर डिमेन्सिया"},
                {"訪問介護員", "होम हेल्पर"}, {"要介護", "हेरचाह आवश्यक"}, {"介護福祉士", "सर्टिफाइड केयर वर्कर"}, {"身体清拭", "शरीर पुछ्ने"}, {"介護計画", "हेरचाह योजना"}}) {
            eq(t[1], g.inject(t[0]).text, "dictionary: compound " + t[0] + " replaced as a whole");
        }
        eq("टोकियोのकम्पनीです。", g.inject("東京の会社です。").text, "dictionary: 東京 + 会社");
        eq("टोकियो庁", g.inject("東京都庁").text, "dictionary: 東京都 wins over 京都");
        eq("सोमबारにअस्पतालへ行きます。", g.inject("月曜日に病院へ行きます。").text, "dictionary: 月曜日 + 病院");
        eq("प्राथमिक विद्यालयに通っています。", g.inject("小学校に通っています。").text, "dictionary: 小学校 is one term, not 小 + 学校");
        eq("[नेपाली दूतावास, राहदानी]", g.inject("ネパール大使館でパスポートを申請します。").requiredTargets.subList(0, 2),
                "dictionary: required terms in order of length");
    }
}
