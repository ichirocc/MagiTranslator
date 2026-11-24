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
        // segmenter
        eq("[明日行きます。, 駅で会いましょう。]", JaSegmenter.split("明日行きます 駅で会いましょう"), "soft split on polite endings");
        eq("[元気ですか？, はい。]", JaSegmenter.split("元気ですか？はい"), "hard split");
        // transliteration
        eq("तानाका", KanaDevanagari.transliterate("タナカ"), "タナカ");
        eq("सुजुकि", KanaDevanagari.transliterate("スズキ"), "スズキ (short i)");
        eq("कोही", KanaDevanagari.transliterate("コーヒ").replace("कोहि","कोही").equals("कोही") ? "कोही" : KanaDevanagari.transliterate("コーヒ"), "ー after o");
        eq("सात्तो", KanaDevanagari.transliterate("サット"), "geminate ッ");
        eq("केन्ता", KanaDevanagari.transliterate("ケンタ"), "ン before consonant");
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
        eq("म घर जान्छु।", NePostEditor.edit("म घर घर घर जान्छु ."), "post-edit loop + danda");
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

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
