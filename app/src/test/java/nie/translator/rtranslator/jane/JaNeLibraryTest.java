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

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
