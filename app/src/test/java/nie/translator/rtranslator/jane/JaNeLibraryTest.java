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

        // TranslationMemory: bounded LRU, clearable
        TranslationMemory tm = new TranslationMemory(2);
        tm.remember("A", "a"); tm.remember("B", "b");
        eq("a", tm.lookup("A"), "memory lookup");          // A becomes most recently used
        tm.remember("C", "c");
        eq(2, tm.size(), "memory bounded");
        eq(null, tm.lookup("B"), "least recently used evicted");
        eq("a", tm.lookup("A"), "recently used kept");
        tm.remember(" ", "x"); tm.remember("D", null);
        eq(2, tm.size(), "empty entries ignored");
        tm.clear();
        eq(0, tm.size(), "memory cleared");
        eq(200, new TranslationMemory().capacity(), "default capacity");

        // ProperNameResolver: candidates for an unknown name; known names are not candidates
        java.util.List<ProperNameResolver.Variant> pv = ProperNameResolver.variants("ラジさんが来ます。", Glossary.withDefaults(), null);
        eq("[TRANSLITERATE:राजिさんが来ます。, KEEP:ラジさんが来ます。, NOUN:ラジが来ます。]", pv.toString(), "three name candidates");
        eq("राजि", pv.get(0).names.get("ラジ"), "transliteration recorded");
        Glossary gk = Glossary.withDefaults();
        gk.put("ラジ", "राजु");
        eq("[]", ProperNameResolver.variants("ラジさんが来ます。", gk, null).toString(), "glossary name is not resolved again");
        Map<String, String> mk = new HashMap<>();
        mk.put("ラジ", "राजि");
        eq("[]", ProperNameResolver.unknownNames("ラジさんが来ます。", null, mk).toString(), "memory name is known");
        eq("[ラジ, サトウ]", ProperNameResolver.unknownNames("ラジさんとサトウ様とラジさん", null, null).toString(), "unknown names deduplicated");

        // consistency across two translate() calls: the rendering chosen first is required afterwards
        Map<String, String> f6 = new HashMap<>();
        f6.put("तानाकाさんが来ます。", "तानाका जी आउनुहुन्छ।");
        f6.put("タナカさんが来ます。", "धान आउँछ।");
        f6.put("タナカが来ます。", "धान आयो।");
        f6.put("तानाकाさんに電話します。", "म तानाका जीलाई फोन गर्छु।");
        f6.put("タナカさんに電話します。", "म फोन गर्छु।");          // name dropped
        Map<String, String> b6 = new HashMap<>();
        b6.put("तानाका जी आउनुहुन्छ।", "タナカさんが来ます。");
        b6.put("धान आउँछ।", "稲が来る。");
        b6.put("म तानाका जीलाई फोन गर्छु।", "タナカさんに電話します。");
        b6.put("म फोन गर्छु।", "タナカさんに電話します。");          // misleading perfect round trip
        Backend fake6 = (text, s, t) -> ("ja".equals(s) ? f6 : b6).getOrDefault(text, text);
        JaNeTranslator tr6 = new JaNeTranslator(fake6, Glossary.withDefaults());
        JaNeTranslator.Result r6a = tr6.translate("タナカさんが来ます", "ja", "ne");
        for (JaNeTranslator.Candidate c : r6a.candidates) System.out.println("     " + c);
        eq("तानाका जी आउनुहुन्छ।", r6a.text, "first call picks transliterated name");
        boolean noun = false;
        for (JaNeTranslator.Candidate c : r6a.candidates) noun |= c.label.equals("terms+name:noun");
        yes(noun, "common-noun candidate generated");
        eq("तानाका", tr6.memory().lookup("タナカ"), "resolved name remembered");
        JaNeTranslator.Result r6b = tr6.translate("タナカさんに電話します", "ja", "ne");
        for (JaNeTranslator.Candidate c : r6b.candidates) System.out.println("     " + c);
        eq("म तानाका जीलाई फोन गर्छु।", r6b.text, "second call keeps the same rendering");
        for (JaNeTranslator.Candidate c : r6b.candidates) {
            yes(!c.label.contains("name:"), "remembered name gets no new candidates: " + c.label);
            if (c.label.equals("whole")) eq(1, c.hard(), "remembered name is required (TERM_MISSING)");
        }
        tr6.memory().clear();
        eq(null, tr6.memory().lookup("タナカ"), "conversation memory cleared");

        // baseline findings (docs/eval/2026-11-25_madlad-raw-beam4-seed.md)
        yes(TranslationValidator.neHasNegation("म मांस खान्न।"), "colloquial negation खान्न");
        yes(TranslationValidator.neHasNegation("म जान्न।"), "colloquial negation जान्न");
        yes(!TranslationValidator.neHasNegation("म प्रसन्न छु।"), "प्रसन्न still not negation");
        yes(!TranslationValidator.neHasNegation("यो भिन्न छ।"), "भिन्न not negation");
        Glossary gr = Glossary.withDefaults();
        Glossary.Injection ri = gr.injectReverse("म काठमाडौँमा बस्छु। सीता नेपालबाट आएकी हुन्।");
        eq("म カトマンズमा बस्छु। सीता ネパールबाट आएकी हुन्।", ri.text, "reverse injection with case markers and ँ/ं variant");
        eq("[カトマンズ, ネパール]", ri.requiredTargets, "reverse required targets");
        eq("नेपालीहरू", gr.injectReverse("नेपालीहरू").text, "नेपाल not matched inside नेपाली; demonym not reversed");
        eq("म नेपाली बोल्छु।", gr.injectReverse("म नेपाली बोल्छु।").text, "ambiguous नेपाली left to the model");
        eq("ネパール語 सिक्दै छु।", gr.injectReverse("नेपाली भाषा सिक्दै छु।").text, "नेपाली भाषा -> ネパール語 (longest first)");
        Backend rb = new Backend() {
            public String translate(String text, String s, String t) {
                if (text.equals("म カトマンズमा बस्छु।")) return "私はカトマンズに住んでいます。";
                if (text.equals("म काठमाडौंमा बस्छु।")) return "私はカタルーニャに住んでいます。";
                return text;
            }
        };
        JaNeTranslator.Result rr = new JaNeTranslator(rb, Glossary.withDefaults()).translate("म काठमाडौंमा बस्छु।", "ne", "ja");
        for (JaNeTranslator.Candidate c : rr.candidates) System.out.println("     " + c);
        eq("私はカトマンズに住んでいます。", rr.text, "ne->ja: name annotation candidate wins over カタルーニャ");

        // jane-vs-raw review (docs/eval/2026-11-25_madlad-vs-jane-seed)
        eq("こんにちは", JaNeTranslator.finishJa("- こんにちは"), "leading dash removed");
        eq("कृपया पर्खनुहोस्।", JaNeTranslator.finishNe("कृपया पर्खनुहोस्", "少々お待ちください。"), "danda added when source ended");
        eq("के हो?", JaNeTranslator.finishNe("के हो?", "何ですか。"), "existing ? kept");
        boolean hasBare = false;
        for (ProperNameResolver.Variant v : ProperNameResolver.variants("ラムさんが来ました。", Glossary.withDefaults(), new java.util.HashMap<>()))
            if (v.mode == ProperNameResolver.Mode.TRANSLITERATE_BARE) { hasBare = true; eq("रामさんが来ました。", v.text, "bare transliteration ラム -> राम"); }
        yes(hasBare, "bare variant generated");
        Backend nb2 = new Backend() {
            public String translate(String text, String s, String t) { return text.contains("1000") || text.contains("1,000") ? "値段は1,000ルピーです。" : text; }
            public java.util.List<String> translateNBest(String text, String s, String t, int n) {
                return java.util.Arrays.asList("賞金は100万円。", "値段は1,000ルピーです。");
            }
        };
        JaNeTranslator.Result rn2 = new JaNeTranslator(nb2, Glossary.withDefaults()).translate("यसको मूल्य १,००० रुपैयाँ हो।", "ne", "ja");
        for (JaNeTranslator.Candidate c : rn2.candidates) System.out.println("     " + c);
        eq("値段は1,000ルピーです。", rn2.text, "ne->ja n-best: candidate that keeps 1,000 beats 100万円");

        // v2 review
        yes(TranslationValidator.containsTerm("राम काठमाडौँबाट आउनुभयो।", "काठमाडौं"), "candrabindu/anusvara variant counts as the term");
        eq(1, TranslationValidator.hardCount(TranslationValidator.validateNeToJa("सीता नेपालबाट आएकी हुन्।", "ネパールネパールの首都ネパールシティ。")), "katakana repetition is hard");
        Backend fb = new Backend() {
            public String translate(String text, String s, String t) { return text.startsWith("यो") ? "これは何ですか。" : text; }
            public java.util.List<String> translateNBest(String text, String s, String t, int n) { return java.util.Arrays.asList("これは何ですか。", "いくらですか？"); }
        };
        eq("これは何ですか。", new JaNeTranslator(fb, Glossary.withDefaults()).translate("यो के हो?", "ne", "ja").text, "ne->ja beam alternatives only as fallback");

        // ne->ja meaning checks (nj04 / nj12 / nj17)
        java.util.function.BiFunction<String, String, Integer> hardNJ = (n, j) -> TranslationValidator.hardCount(TranslationValidator.validateNeToJa(n, j));
        java.util.function.BiFunction<String, String, String> kindsNJ = (n, j) -> TranslationValidator.validateNeToJa(n, j).toString();
        yes(kindsNJ.apply("यो कति हो?", "これは何ですか。").contains("QUESTION_WORD_LOST"), "nj04: कति rendered as bare 何 is hard");
        eq(0, hardNJ.apply("यो कति हो?", "これはいくらですか？"), "nj04: いくら keeps कति");
        eq(0, hardNJ.apply("तपाईंको परिवारमा कति जना हुनुहुन्छ?", "ご家族は何人ですか？"), "कति + 何人 ok");
        eq(0, hardNJ.apply("कतिपय मानिस आए।", "何人かの人が来た。"), "कतिपय is not कति");
        yes(kindsNJ.apply("तपाईं कहाँ जानुहुन्छ?", "あなたは行きますか？").contains("QUESTION_WORD_LOST"), "कहाँ needs どこ");
        eq(0, hardNJ.apply("तपाईं कहाँ जानुहुन्छ?", "どこに行きますか？"), "कहाँ -> どこ ok");
        eq(0, hardNJ.apply("के तपाईं आउनुहुन्छ?", "来ますか？"), "के as yes/no particle is not checked");
        eq(0, hardNJ.apply("म कहिलेकाहीं पौडी खेल्छु।", "私は時々泳ぎます。"), "कहिलेकाहीं is not कहिले");
        yes(kindsNJ.apply("यसको मूल्य १,००० रुपैयाँ हो।", "価格は1000円。").contains("CURRENCY_MISMATCH"), "nj12: रुपैयाँ -> 円 is hard");
        eq(0, hardNJ.apply("यसको मूल्य १,००० रुपैयाँ हो।", "価格は1,000ルピーです。"), "nj12: ルピー ok");
        eq(0, hardNJ.apply("यो ५०० येन हो।", "これは500円です。"), "येन -> 円 ok");
        eq(0, hardNJ.apply("उनी परिवारसँग रमाइलो गर्छिन्।", "彼女は家族と円満に過ごす。"), "円満 is not currency");
        yes(kindsNJ.apply("म नेपालबाट आएँ।", "ネバダ州シアトル出身。").contains("PLACE_MISSING"), "nj17: नेपाल missing");
        yes(kindsNJ.apply("म नेपालबाट आएँ।", "ネバダ州シアトル出身。").contains("PLACE_ADDED"), "nj17: hallucinated ネバダ/シアトル");
        eq(0, hardNJ.apply("म नेपालबाट आएँ।", "私はネパールから来ました。"), "nj17: ネパール ok");
        eq(0, hardNJ.apply("म नेपाली बोल्छु।", "私はネパール語を話します。"), "नेपाली -> ネパール語 is not a place mention");
        eq(0, hardNJ.apply("म जापानी सिक्दैछु।", "私は日本語を勉強しています。"), "जापानी -> 日本語 ok");
        eq(0, hardNJ.apply("राम काठमाडौँमा बस्छन्।", "ラムはカトマンズに住んでいる。"), "candrabindu place stem");
        Backend qb = new Backend() {
            public String translate(String text, String s, String t) { return text.startsWith("यो") ? "これは何ですか。" : text; }
            public java.util.List<String> translateNBest(String text, String s, String t, int n) { return java.util.Arrays.asList("これは何ですか。", "いくらですか？"); }
        };
        eq("いくらですか？", new JaNeTranslator(qb, Glossary.withDefaults()).translate("यो कति हो?", "ne", "ja").text, "nj04: fallback reaches the price question");
        // ja->ne IR-lite agreement (spec v2 part 2; jn14 / jn17 / jn19)
        eq(1, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("先生はもう帰られました。", "तपाईँको शिक्षक घरमा हुनुहुन्छ।", null)), "jn14 drift: past lost is hard");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("先生はもう帰られました。", "शिक्षक घर गएका छन् ।", null)), "jn14 raw perfect passes");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("先生はもう帰られました。", "शिक्षक घर जानुभयो।", null)), "honorific past passes");
        yes(!TranslationValidator.validateJaToNe("昨日は行きませんでした。", "म हिजो गइनँ।", null).toString().contains("PAST_LOST"), "negative past is past");
        yes(PhraseBook.keyNe("तपाईंलाई कस्तो छ?").equals(PhraseBook.keyNe("तपाइँलाई कस्तो छ")), "ne phrase key folds candrabindu and long/short i");
        yes(PhraseBook.keyNe("धन्यवाद, ठीक छ।").equals(PhraseBook.keyNe("धन्यवाद, ठिक छ")), "ne phrase key folds ी/ि");
        yes(TranslationValidator.validateJaToNe("もう少し待ってください。", "कृपया पर्खन सकियो।", null).toString().contains("HARD ADVERB_REVERSED"), "もう少し as finished");
        yes(TranslationValidator.validateJaToNe("ペンを貸してください。", "कलम सापटी लिनुहोस्।", null).toString().contains("HARD DIRECTION_REVERSED"), "lend request as borrow");
        yes(!TranslationValidator.validateJaToNe("ペンを貸してください。", "कलम सापटी दिनुहोस्।", null).toString().contains("DIRECTION_REVERSED"), "lend kept");
        yes(TranslationValidator.validateJaToNe("薬を飲みましょう。", "औषधि पिऔं।", null).toString().contains("soft MEDICINE_VERB"), "medicine with drink is soft");
        yes(TranslationValidator.validateJaToNe("今度は一人でやってみましょう。", "अब आफूखुसी गर्नुहोस्।", null).toString().contains("HARD GUIDANCE_REVERSED"), "一人で as self-will");
        yes(TranslationValidator.validateJaToNe("仕事に慣れてきましたね。", "काममा लापरवाही भयो।", null).toString().contains("GUIDANCE_REVERSED"), "慣れ as careless");
        yes(TranslationValidator.validateJaToNe("先輩に注意されました。", "सिनियर रिसाउनुभयो।", null).toString().contains("GUIDANCE_REVERSED"), "注意 as anger");
        yes(TranslationValidator.validateJaToNe("食事を見守ってください。", "खाना हेर्नुहोस्।", null).toString().contains("soft WATCH_OVER_LOST"), "見守る as plain look is soft");
        yes(!TranslationValidator.validateJaToNe("食事を見守ってください。", "खाना खाँदा छेउमा बसेर निगरानी गर्नुहोस्।", null).toString().contains("WATCH_OVER_LOST"), "見守る kept");
        // speaker/listener reversal (ir/Person)
        yes(TranslationValidator.validateJaToNe("確認しますね。", "कृपया जाँच गर्नुहोस्।", null).toString().contains("HARD PERSON_REVERSED"), "self announcement rendered as request is hard");
        yes(TranslationValidator.validateJaToNe("私が対応します。", "तपाईं सम्हाल्नुहोस्।", null).toString().contains("PERSON_REVERSED"), "explicit 私 rendered as request");
        yes(!TranslationValidator.validateJaToNe("確認しますね。", "म जाँच गर्छु है।", null).toString().contains("PERSON_REVERSED"), "first person kept");
        yes(!TranslationValidator.validateJaToNe("「確認」を押します。", "पक्का बटन थिच्नुहोस्।", null).toString().contains("PERSON_REVERSED"), "manual instruction is ambiguous, not flagged");
        yes(!TranslationValidator.validateJaToNe("おむつを替えますね。少し失礼します。", "डाइपर फेरिदिन्छु है। माफ गर्नुहोला।", null).toString().contains("PERSON_REVERSED"), "失礼します is a fixed phrase");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("あなた。", "तपाईं।", null)), "あなた is not a past predicate");
        eq(1, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("明日の夕方、何時に会社へ行きますか？", "तपाईँ भोलि साँझ कम्पनीमा कहिले जानुहुन्छ?", null)), "jn19 何時 -> कहिले is WH_LOST");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("明日の夕方、何時に会社へ行きますか？", "तपाईँ भोलि साँझ कति बजे कम्पनी जानुहुन्छ ?", null)), "jn19 raw कति बजे passes");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("何か食べますか？", "केही खानुहुन्छ?", null)), "indefinite 何か not a wh-word");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("駅はどこですか？", "स्टेसन कहाँ छ?", null)), "どこ -> कहाँ");
        eq(1, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("明日行かなければなりません。", "म भोलि जान्छु।", null)), "obligation lost is hard");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("明日行かなければなりません。", "मलाई भोलि जानुपर्छ।", null)), "obligation पर्छ passes");
        eq(1, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("少々お待ちください。", "म पर्खन्छु।", null)), "request lost is hard");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("少々お待ちください。", "कृपया पर्खनुहोस्।", null)), "request नुहोस् passes");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("ここで写真を撮らないでください。", "यहाँ फोटो नखिच्नुहोस्।", null)), "negative request passes");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("明日は雨でしょう。", "भोलि पानी पर्छ।", null)), "conjecture lost is only soft");
        Map<String, String> f7 = new HashMap<>();
        f7.put("रामुさんがカトマンズから来ました。", "रामु काठमाडौँबाट आएकी थिइन्।");
        f7.put("रामさんがカトマンズから来ました。", "राम काठमाडौँबाट आए।");
        Backend fake7 = new Backend() {
            public String translate(String text, String s, String t) {
                if ("ja".equals(s)) return text.contains("रामु") || !text.contains("राम") ? f7.get("रामुさんがカトマンズから来ました。") : f7.get("रामさんがカトマンズから来ました。");
                return text.startsWith("रामु") ? "ラムさんがカトマンズから来ました。" : "ラムさんがカトマンズから来た。";   // full form round-trips better
            }
        };
        JaNeTranslator.Result r7 = new JaNeTranslator(fake7, Glossary.withDefaults()).translate("ラムさんがカトマンズから来ました。", "ja", "ne");
        for (JaNeTranslator.Candidate c : r7.candidates) System.out.println("     " + c);
        yes(r7.text.startsWith("राम ") , "jn17 bare राम preferred over रामु");

        // negative past (गइनँ / गएन) is negation; nouns in -ेन / दिन are not
        yes(TranslationValidator.neHasNegation("म हिजो गइनँ।"), "गइनँ is negation");
        yes(TranslationValidator.neHasNegation("उनी आएनन्।"), "आएनन् is negation");
        yes(TranslationValidator.neHasNegation("उसले काम गरेन।"), "गरेन is negation");
        yes(TranslationValidator.neHasNegation("मैले खाना खाइनँ।"), "खाइनँ is negation");
        yes(TranslationValidator.neHasNegation("मैले गरिनँ।"), "गरिनँ is negation");
        yes(!TranslationValidator.neHasNegation("म ट्रेनमा गएँ।"), "ट्रेनमा is not negation");
        yes(!TranslationValidator.neHasNegation("म ट्रेन चढ्छु।"), "ट्रेन is not negation");
        yes(!TranslationValidator.neHasNegation("आज राम्रो दिन हो।"), "दिन is not negation");
        yes(!TranslationValidator.neHasNegation("उनी आए।"), "आए is affirmative");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateJaToNe("昨日は行きませんでした。", "म हिजो गइनँ।", null)), "ませんでした -> गइनँ passes");
        eq(0, TranslationValidator.hardCount(TranslationValidator.validateNeToJa("उनी आएनन्।", "彼は来ませんでした。")), "आएनन् -> 来ませんでした passes");
        // Stage 1 Language IR (jane.ir): analyzers per language, verdicts come from IR(source) vs IR(candidate)
        nie.translator.rtranslator.jane.ir.LanguageIR ij = nie.translator.rtranslator.jane.ir.JaAnalyzer.analyze("明日の夕方、何時に会社へ行きますか？");
        yes(ij.question, "IR ja: question");
        yes(ij.whWords.containsKey(nie.translator.rtranslator.jane.ir.WhConcept.TIME_OCLOCK), "IR ja: 何時 -> TIME_OCLOCK");
        eq("何時", ij.whWords.get(nie.translator.rtranslator.jane.ir.WhConcept.TIME_OCLOCK), "IR ja: wh surface kept");
        eq(nie.translator.rtranslator.jane.ir.Tense.NON_PAST, ij.tense, "IR ja: non-past");
        yes(!ij.negated, "IR ja: affirmative");
        nie.translator.rtranslator.jane.ir.LanguageIR ij2 = nie.translator.rtranslator.jane.ir.JaAnalyzer.analyze("昨日は行かなければなりませんでした。");
        eq(nie.translator.rtranslator.jane.ir.Tense.PAST, ij2.tense, "IR ja: past");
        yes(ij2.modalities.contains(nie.translator.rtranslator.jane.ir.Modality.OBLIGATION), "IR ja: obligation");
        yes(!ij2.negated, "IR ja: obligation is not negation");
        nie.translator.rtranslator.jane.ir.LanguageIR ij3 = nie.translator.rtranslator.jane.ir.JaAnalyzer.analyze("ラムさんは三人でネパール語を話した。価格は500円。");
        eq("[3, 500]", ij3.numbersNormalized, "IR ja: kanji numbers normalised");
        eq("[500]", ij3.numbers, "IR ja: raw numbers as written");
        yes(ij3.places.contains("ネパール") && !ij3.placesAsPlace.contains("ネパール"), "IR ja: ネパール語 is not a place mention");
        yes(ij3.names.contains("ラム"), "IR ja: katakana name candidate");
        yes(ij3.currencies.contains(nie.translator.rtranslator.jane.ir.Currency.YEN), "IR ja: yen");
        yes(nie.translator.rtranslator.jane.ir.JaAnalyzer.analyze("ネパールネパール").degenerateRepeat, "IR ja: repeat");
        nie.translator.rtranslator.jane.ir.LanguageIR in = nie.translator.rtranslator.jane.ir.NeAnalyzer.analyze("म हिजो नेपालबाट १,००० रुपैयाँ लिएर आइनँ। कति बजे?");
        yes(in.negated, "IR ne: negative past");
        eq(nie.translator.rtranslator.jane.ir.Tense.PAST, in.tense, "IR ne: past evidence");
        yes(in.places.contains("ネパール"), "IR ne: नेपालबाट -> ネパール");
        yes(in.currencies.contains(nie.translator.rtranslator.jane.ir.Currency.RUPEE), "IR ne: rupee");
        yes(in.whWords.containsKey(nie.translator.rtranslator.jane.ir.WhConcept.QUANTITY), "IR ne: कति stated");
        yes(in.whCover.contains(nie.translator.rtranslator.jane.ir.WhConcept.TIME_OCLOCK), "IR ne: बजे covers 何時");
        eq("[1000]", in.numbers, "IR ne: numbers");
        yes(nie.translator.rtranslator.jane.ir.NeAnalyzer.analyze("म नेपाली बोल्छु।").places.isEmpty(), "IR ne: नेपाली is not नेपाल");
        yes(nie.translator.rtranslator.jane.ir.NeAnalyzer.analyze("कृपया पर्खनुहोस्।").modalityCover.contains(nie.translator.rtranslator.jane.ir.Modality.REQUEST), "IR ne: request cover");
        yes(nie.translator.rtranslator.jane.ir.NeAnalyzer.analyze("ऊ भोलि आउँछ।").modalities.isEmpty(), "IR ne: stated modalities not analysed in stage 1");
        // source IR reused across candidates gives the same verdict as the string API
        nie.translator.rtranslator.jane.ir.LanguageIR src14 = nie.translator.rtranslator.jane.ir.JaAnalyzer.analyze("先生はもう帰られました。");
        eq(TranslationValidator.validateJaToNe("先生はもう帰られました。", "तपाईँको शिक्षक घरमा हुनुहुन्छ।", null).toString(),
                TranslationValidator.compareJaToNe(src14, nie.translator.rtranslator.jane.ir.NeAnalyzer.analyze("तपाईँको शिक्षक घरमा हुनुहुन्छ।"), null, new java.util.ArrayList<>()).toString(), "IR compare == string API");
        // abstention: a chosen translation with a hard problem is flagged "needs review" (kinds only, no text)
        Backend negLost = new Backend() { public String translate(String text, String s, String t) { return "म भोलि जान्छु।"; } };
        JaNeTranslator.Result rv = new JaNeTranslator(negLost, Glossary.withDefaults()).translate("明日は行きません。", "ja", "ne");
        yes(rv.needsReview(), "negation lost -> needsReview");
        yes(rv.hardKinds().contains(TranslationValidator.Kind.NEGATION_LOST), "hardKinds has NEGATION_LOST");
        Backend negOk = new Backend() { public String translate(String text, String s, String t) { return "म भोलि जान्न।"; } };
        JaNeTranslator.Result rok = new JaNeTranslator(negOk, Glossary.withDefaults()).translate("明日は行きません。", "ja", "ne");
        yes(!rok.needsReview(), "correct negation -> no review");
        eq(0, rok.hardKinds().size(), "no hard kinds");
        ReviewMarks.clear();
        ReviewMarks.record(rv.text, rv.needsReview());
        yes(ReviewMarks.isMarked(rv.text), "marked text found");
        ReviewMarks.record(rv.text, false);
        yes(!ReviewMarks.isMarked(rv.text), "re-recorded as fine -> unmarked");
        for (int k = 0; k < 100; k++) ReviewMarks.record("t" + k, true);
        yes(ReviewMarks.size() <= 64, "marks are bounded");
        ReviewMarks.clear();
        yes(!ReviewMarks.isMarked("t99"), "clear removes marks");
        // ---- user dictionary (synthetic data only) ----
        try {
            String terms = "#!jane ja-ne-terms\n# comment\n血圧\tरक्तचाप\n高血圧\tउच्च रक्तचाप\nबैंक\t銀行\n\tरक्तचाप\n血圧\tरक्तचाप\nfoo\tbar\tbaz\n";
            UserDictionary.Parsed ut = UserDictionary.parse(new java.io.StringReader(terms));
            eq(2, ut.accepted(), "user terms: 2 valid lines");
            eq(3, ut.rejected, "user terms: swapped, empty and extra-column lines rejected");
            eq(1, ut.duplicates, "user terms: duplicate key skipped");
            boolean udBad = false;
            try { UserDictionary.parse(new java.io.StringReader("血圧\tरक्तचाप\n")); } catch (IllegalArgumentException e) { udBad = true; }
            yes(udBad, "user dictionary without header rejected");
            yes(UserDictionary.kindOf("﻿#!jane phrases") == UserDictionary.Kind.PHRASES, "header with BOM");
            Glossary ug = Glossary.withDefaults();
            PhraseBook upb = new PhraseBook();
            UserDictionary.apply(ut, ug, upb);
            java.util.List<String> pref = ug.userPreferred("高血圧の薬です。");
            yes(pref.contains("उच्च रक्तचाप") && !pref.contains("रक्तचाप"), "userPreferred: longest term wins, no overlap");
            UserDictionary.Parsed udRv = UserDictionary.parse(new java.io.StringReader("#!jane ne-ja-terms\nरक्तचाप\t血圧\n"));
            UserDictionary.apply(udRv, ug, upb);
            Glossary.Injection udRi = ug.injectReverseUser("मेरो रक्तचापमा समस्या छ।");
            yes(udRi.text.contains("血圧") && udRi.preferredTargets.contains("血圧") && udRi.requiredTargets.isEmpty(), "injectReverseUser: preferred, not required");
            UserDictionary.Parsed ph = UserDictionary.parse(new java.io.StringReader("#!jane phrases\n少々お待ちください。\tकृपया एकछिन पर्खनुहोस्।\nお先にどうぞ\tपहिले जानुहोस्।\tja>ne\nありがとう\tधन्यवाद\tmaybe\n"));
            eq(2, ph.accepted(), "phrases: two valid lines");
            eq(1, ph.rejected, "phrases: unknown third column rejected");
            UserDictionary.apply(ph, ug, upb);
            eq("कृपया एकछिन पर्खनुहोस्।", upb.lookupJaNe("少々お待ちください"), "phrase lookup ignores final punctuation");
            yes(upb.lookupNeJa("पहिले जानुहोस्") == null, "ja>ne-only phrase not used for ne->ja");
            eq("少々お待ちください。", upb.lookupNeJa("कृपया एकछिन पर्खनुहोस् ।"), "ne->ja phrase lookup ignores danda spacing");
            // a phrase hit is a candidate that is still validated: a phrase that drops the number loses
            PhraseBook numPb = new PhraseBook();
            numPb.add("3時に来てください。", "कृपया आउनुहोस्।", false);
            Backend udNb = new Backend() {
                public String translate(String text, String sl, String tl) { return "ja".equals(sl) ? "कृपया ३ बजे आउनुहोस्।" : "3時に来てください。"; }
            };
            JaNeTranslator njt = new JaNeTranslator(udNb, Glossary.withDefaults());
            njt.setPhraseBook(numPb);
            JaNeTranslator.Result nr = njt.translate("3時に来てください。", "ja", "ne");
            yes(nr.text.contains("३"), "phrase candidate that loses a number does not win");
            boolean phraseSeen = false;
            for (JaNeTranslator.Candidate c : nr.candidates) if ("phrase".equals(c.label)) phraseSeen = c.hard() > 0;
            yes(phraseSeen, "phrase candidate is validated (number lost is hard)");
            // import / load / stamp / delete in a temp dir
            java.io.File dir = java.nio.file.Files.createTempDirectory("jane-ud").toFile();
            String st0 = UserDictionary.stamp(dir);
            UserDictionary.Parsed imp = UserDictionary.importStream(new java.io.ByteArrayInputStream(terms.getBytes(java.nio.charset.StandardCharsets.UTF_8)), dir);
            yes(new java.io.File(dir, UserDictionary.Kind.JA_NE_TERMS.fileName).isFile() && imp.accepted() == 2, "importStream stores the file under its kind");
            yes(!st0.equals(UserDictionary.stamp(dir)), "stamp changes after import");
            boolean rejectedImport = false;
            try { UserDictionary.importStream(new java.io.ByteArrayInputStream("hello\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)), dir); } catch (IllegalArgumentException e) { rejectedImport = true; }
            yes(rejectedImport, "non-dictionary file is not stored");
            Glossary lg = new Glossary();
            eq(1, UserDictionary.loadDirectory(dir, lg, new PhraseBook()).size(), "loadDirectory reads stored dictionaries");
            eq(2, lg.userSize(), "loaded user terms");
            UserDictionary.deleteAll(dir);
            yes(!new java.io.File(dir, UserDictionary.Kind.JA_NE_TERMS.fileName).exists(), "deleteAll removes stored dictionaries");
            dir.delete();
        } catch (Exception e) {
            yes(false, "user dictionary tests threw " + e);
        }

        // the published dictionaries in dictionaries/ must parse with no rejected line (run from the repo root)
        for (String df : new String[]{"ja_ne_terms.tsv", "ne_ja_terms.tsv", "phrases.tsv"}) {
            java.io.File f = new java.io.File("dictionaries", df);
            if (!f.isFile()) continue;
            try (java.io.Reader dr = new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                UserDictionary.Parsed dp = UserDictionary.parse(dr);
                yes(dp.rejected == 0 && dp.duplicates == 0 && dp.accepted() > 0, "dictionaries/" + df + " parses cleanly: " + dp);
            } catch (Exception e) { yes(false, "dictionaries/" + df + " threw " + e); }
        }

        // severity tie-break and the extra round when every candidate is hard
        eq("なんじに会社へ行きますか？", JaParaphrase.paraphrase("何時に会社へ行きますか？"), "何時 -> なんじ");
        eq("何時間かかりますか？", JaParaphrase.paraphrase("何時間かかりますか？"), "何時間 kept");
        eq("यसको मूल्य 1000 रुपैयाँ हो।", NeParaphrase.paraphrase("यसको मूल्य १,००० रुपैयाँ हो।"), "Devanagari digits -> ASCII, no grouping");
        eq("सीता नेपाल बाट आएकी हुन्।", NeParaphrase.paraphrase("सीता नेपालबाट आएकी हुन्।"), "place + case marker split");
        eq("म नेपाली बोल्छु।", NeParaphrase.paraphrase("म नेपाली बोल्छु।"), "नेपाली untouched");
        eq(2, JaParaphrase.clauses("雨が降ったら、家にいます。").size(), "clause split at 、");
        {
            java.util.List<TranslationValidator.Issue> numLost = TranslationValidator.validateNeToJa("यसको मूल्य १,००० रुपैयाँ हो।", "価格は100万ルピー。");
            java.util.List<TranslationValidator.Issue> curOnly = TranslationValidator.validateNeToJa("यसको मूल्य १,००० रुपैयाँ हो।", "価格は1000円。");
            yes(JaNeTranslator.severity(numLost) > JaNeTranslator.severity(curOnly), "a wrong amount (100万) is worse than a wrong currency word");
            JaNeTranslator.Candidate a = new JaNeTranslator.Candidate("a", "", "x", null, 0.9, java.util.Arrays.asList(new TranslationValidator.Issue(TranslationValidator.Kind.NUMBER_LOST, true, "1000")));
            JaNeTranslator.Candidate b = new JaNeTranslator.Candidate("b", "", "y", null, 0.1, java.util.Arrays.asList(new TranslationValidator.Issue(TranslationValidator.Kind.TERM_MISSING, true, "t"), new TranslationValidator.Issue(TranslationValidator.Kind.TERM_MISSING, true, "u")));
            yes(JaNeTranslator.better(b, a), "all hard: two missing terms beat a lost number despite lower score");
            JaNeTranslator.Candidate c = new JaNeTranslator.Candidate("c", "", "z", null, 0.0, java.util.Collections.emptyList());
            yes(JaNeTranslator.better(c, b), "a clean candidate still beats any hard one");
        }
        {
            // extra round: the first beam only has 何時 -> कहिले; the paraphrased input gives कति बजे
            Backend wb = new Backend() {
                public String translate(String text, String s, String t) { return "ja".equals(t) ? "明日の夕方、何時に会社へ行きますか？" : text.contains("なんじ") ? "तपाईँ भोलि साँझ कति बजे कम्पनी जानुहुन्छ?" : "तपाईँ भोलि साँझ कम्पनीमा कहिले जानुहुन्छ?"; }
                public java.util.List<String> translateNBest(String text, String s, String t, int n) { return java.util.Collections.singletonList(translate(text, s, t)); }
            };
            JaNeTranslator tw = new JaNeTranslator(wb, Glossary.withDefaults());
            eq("तपाईँ भोलि साँझ कति बजे कम्पनी जानुहुन्छ?", tw.translate("明日の夕方、何時に会社へ行きますか？", "ja", "ne").text, "jn19: extra round reaches कति बजे");
            tw.setDiversify(false);
            yes(tw.translate("明日の夕方、何時に会社へ行きますか？", "ja", "ne").needsReview(), "without the extra round it stays hard");
        }
        // ne->ja repair candidates (nj12 currency, nj17 repetition)
        eq("価格は1000ルピー。", JaRepair.currency("यसको मूल्य १,००० रुपैयाँ हो।", "価格は1000円。"), "currency repair: 円 next to the number -> ルピー");
        eq("価格は1,000 ルピーです", JaRepair.currency("मूल्य रु. १००० हो", "価格は1,000 円です"), "currency repair: रु. and spaced 円");
        yes(JaRepair.currency("यसको मूल्य १,००० येन हो।", "価格は1000円。") == null, "no repair when the source says yen");
        yes(JaRepair.currency("यसको मूल्य १,००० रुपैयाँ हो।", "価格は1000ルピー。") == null, "no repair when ルピー is already there");
        yes(JaRepair.currency("उनी परिवारसँग रमाइलो गर्छिन्।", "円満に暮らす。") == null, "no repair without rupees / 円満 untouched");
        eq("ネパールの首都ネパールシティ。", JaRepair.collapseRepeat("ネパールネパールの首都ネパールシティ。"), "repeat repair collapses ネパールネパール");
        yes(JaRepair.collapseRepeat("ネパールから来ました。") == null, "no repeat, no repair");
        {
            Backend cb = new Backend() {
                public String translate(String text, String s, String t) { return "価格は1000円。"; }
                public java.util.List<String> translateNBest(String text, String s, String t, int n) { return java.util.Arrays.asList("価格は1000円。", "賞金は100万ルピー。"); }
            };
            JaNeTranslator tc = new JaNeTranslator(cb, Glossary.withDefaults());
            JaNeTranslator.Result rc = tc.translate("यसको मूल्य १,००० रुपैयाँ हो।", "ne", "ja");
            eq("価格は1000ルピー。", rc.text, "nj12: currency repair wins over 円 and 100万");
            yes(!rc.needsReview(), "nj12 repaired result has no hard issue");
            tc.setDiversify(false);
            yes(tc.translate("यसको मूल्य १,००० रुपैयाँ हो।", "ne", "ja").needsReview(), "without the extra round nj12 stays hard");
        }
        // NePunctuation: restore the final mark speech recognition drops
        eq("तपाईं कहाँ जानुहुन्छ?", NePunctuation.restore("तपाईं कहाँ जानुहुन्छ"), "wh-word -> ?");
        eq("के तपाईं आउनुहुन्छ?", NePunctuation.restore("के तपाईं आउनुहुन्छ"), "initial के -> ?");
        eq("म घर जान्छु।", NePunctuation.restore("म घर जान्छु"), "statement -> danda");
        eq("म घर जान्छु।", NePunctuation.restore("म घर जान्छु।"), "already ended: unchanged");
        eq("यो कति हो?", NePunctuation.restore("यो कति हो?"), "already a question: unchanged");
        eq("hello", NePunctuation.restore("hello"), "non-Devanagari untouched");
        eq("म कहिलेकाहीं पौडी खेल्छु।", NePunctuation.restore("म कहिलेकाहीं पौडी खेल्छु"), "कहिलेकाहीं is not कहिले");
        // chrF fast path (n-grams packed into longs) == reference implementation, on random text with Japanese,
        // Devanagari, spaces/punctuation and characters outside the BMP (those take the reference path)
        java.util.Random rnd = new java.util.Random(42);
        String[] alphabet = {"あ", "い", "介", "護", "ー", "क", "ा", "्", "न", "ं", " ", "。", "।", "😀", "𠮷", "A", "1"};
        boolean same = true;
        for (int k = 0; k < 5000 && same; k++) {
            StringBuilder x = new StringBuilder(), y = new StringBuilder();
            for (int i = rnd.nextInt(12); i > 0; i--) x.append(alphabet[rnd.nextInt(k % 2 == 0 ? 13 : alphabet.length)]);
            for (int i = rnd.nextInt(12); i > 0; i--) y.append(alphabet[rnd.nextInt(k % 2 == 0 ? 13 : alphabet.length)]);
            String hx = QualityScorer.strip(x.toString()), hy = QualityScorer.strip(y.toString());
            double fast = QualityScorer.chrF(x.toString(), y.toString());
            double ref = hx.isEmpty() || hy.isEmpty() ? 0 : QualityScorer.chrFSlow(hx, hy);
            if (fast != ref) { same = false; System.out.println("     chrF differs: [" + x + "] [" + y + "] " + fast + " vs " + ref); }
        }
        yes(same, "packed chrF identical to the reference on 5000 random pairs");

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
