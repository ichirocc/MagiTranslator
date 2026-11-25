package nie.translator.rtranslator.jane;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects meaning errors that string similarity cannot see. Each issue is "hard" (the candidate is
 * very likely wrong) or "soft". JaNeTranslator ranks candidates lexicographically: fewer hard issues
 * first, then the quality score (same acceptance idea as the MAGI optimizer: HARD -> score).
 */
public final class TranslationValidator {

    public enum Kind { NUMBER_LOST, NUMBER_ADDED, NEGATION_LOST, NEGATION_ADDED, TERM_MISSING, UNTRANSLATED_SOURCE_SCRIPT, EMPTY, QUESTION_LOST, REPETITION,
        // ne->ja meaning checks (checkNeToJaMeaning)
        QUESTION_WORD_LOST, CURRENCY_MISMATCH, PLACE_MISSING, PLACE_ADDED,
        /** ja->ne agreement (IR-lite, SPEC v2 part 2): the candidate dropped what the Japanese predicate states */
        WH_LOST, OBLIGATION_LOST, REQUEST_LOST, PAST_LOST, CONJECTURE_LOST, PERMISSION_LOST, NAME_EXTRA_VOWEL }

    public static final class Issue {
        public final Kind kind;
        public final boolean hard;
        public final String detail;
        Issue(Kind kind, boolean hard, String detail) { this.kind = kind; this.hard = hard; this.detail = detail; }
        @Override public String toString() { return (hard ? "HARD " : "soft ") + kind + (detail == null ? "" : "(" + detail + ")"); }
    }

    // --- Japanese negation (predicate level). Adjectives that merely end in ない are excluded.
    private static final Pattern JA_NEG = Pattern.compile(
            "(ない|なかった|なくて|ないで|ません|ませんでした|ず(?=[、。に]|$)|ぬ(?=[、。]|$))");
    // adjectives / idioms that end in ない but are not a negated predicate
    private static final Pattern JA_NOT_NEGATION = Pattern.compile(
            "((?:少|すく|危|あぶ|切|せつ|だらし|つまら|くだら|もったい|勿体|仕方が?|しかたが?|間違い|違い|半端|きた|おさ|はか|儚|ぎこち|情け|なさけ|頼り|たより|物足り|もの足り|ものたり|申し訳|申しわけ|そっけ|素っ気|あどけ|あっけ|呆気|味気|あじけ)な(?:い|く|かっ|さ|け)|汚い|幼い|すみません|すいません|申し訳(あり|ござい)ません|かもしれ(ない|ません)|なければ(なら|なり|いけ)(ない|ません)|なくては(なら|なり|いけ)(ない|ません)|ないと(いけ|だめ|ダメ)(ない|ません)?|ませんか)");   // apology / obligation / conjecture / invitation are not negation
    // --- Nepali negation: suffix forms and the negative prefix न- on verbs.
    private static final Pattern NE_NEG_WORD = Pattern.compile(
            "(छैन|छैनन्|छैनौं|होइन|होइनन्|हुँदैन|हुन्न|दैन|दैनन्|दैनौं|दैनौ|दिनँ|दिनौं|हुन्नँ|न्नँ)$");   // e.g. जाँदिनँ, गर्दैन, सक्दैनन्
    // न- before a consonant (नगर्नुहोस्, नजानु) or an independent vowel (नआउनु, नआएको)
    // colloquial negative future: verb stem ending in a vowel sign + न्न (खान्न, जान्न, हुन्न). A consonant
    // before न्न is a noun/adjective (प्रसन्न, सम्पन्न); भिन्न-type words are listed explicitly.
    private static final Pattern NE_NEG_NN = Pattern.compile("[\\u093E-\\u094C\\u0901\\u0902]न्नँ?$");
    private static final String[] NE_NN_NOT_NEGATION = {"भिन्न", "विभिन्न", "खिन्न", "छिन्न", "छिन्नभिन्न"};
    private static final Pattern NE_NEG_PREFIX = Pattern.compile("^न(?:[क-ह]|[अआइईउऊएऐओऔ])");
    // nouns / adjectives / names that start with न + consonant. Matched as prefixes, so they must not
    // swallow verb negations: "नगर" (city) is listed only with its case endings, because
    // नगर्नुहोस् / नगरेको (do not / not done) are real negations.
    private static final String[] NE_NOT_NEGATION = {"नमस्ते", "नमस्कार", "नयाँ", "नजिक", "नदी", "नक्सा", "नक्शा", "नम्बर", "नरम", "नर्स", "नल", "नसा", "नक्कली", "नतिजा", "नर्सरी",
            "नगरपालिका", "नगरमा", "नगरको", "नगरका", "नगरले", "नगरबाट", "नगरवासी", "नगद", "नमुना", "नम्र", "नजर", "नरक", "नमन", "नशा", "नकल", "नरिवल", "नवीन", "नवम्बर",
            "नब्बे", "नहर", "नवजात", "नक्सली", "नमक", "नवौं", "नरेन्द्र", "नरेश", "नवराज", "नकारात्मक", "नक्षत्र"};
    private static final Pattern NE_WORD = Pattern.compile("[\\u0900-\\u0963\\u0966-\\u097F]+");   // excludes the danda । ॥
    private static final Pattern JA_SCRIPT = Pattern.compile("[\\u3040-\\u30FF\\u4E00-\\u9FFF]");
    private static final Pattern JA_QUESTION = Pattern.compile("(か[。？]?$|？|\\?)");
    // whole-word question words: plain substrings matched किन्छु (I buy), किनभने (because), कुनै (any),
    // कतिपय (some) and कहिलेकाहीं (sometimes)
    private static final Pattern NE_QUESTION = Pattern.compile(
            "(\\?|？|को हो|(?<![\\u0900-\\u0963\\u0966-\\u097F])(?:के|किन|कसरी|कस्तो|कस्ती|कस्ता|कुन|कुनचाहिँ|कुनचाहिं|कहाँ\\S*|कति(?!पय)\\S*|कहिले(?!काहीं|काहीँ|काहिँ|काहिं))(?![\\u0900-\\u0963\\u0966-\\u097F]))");

    private TranslationValidator() {}

    public static boolean jaHasNegation(String ja) {
        String t = Normalizer.normalize(ja, Normalizer.Form.NFKC);
        t = JA_NOT_NEGATION.matcher(t).replaceAll("");
        return JA_NEG.matcher(t).find();
    }

    public static boolean neHasNegation(String ne) {
        Matcher m = NE_WORD.matcher(Normalizer.normalize(ne, Normalizer.Form.NFC));
        while (m.find()) {
            String w = m.group();
            if (w.equals("दिन")) continue;                 // "day"
            if (NE_NEG_WORD.matcher(w).find()) return true;
            if (NE_NEG_NN.matcher(w).find() && !java.util.Arrays.asList(NE_NN_NOT_NEGATION).contains(w)) return true;
            if (w.length() >= 3 && NE_NEG_PREFIX.matcher(w).find()) {
                boolean excluded = false;
                for (String x : NE_NOT_NEGATION) if (w.startsWith(x)) { excluded = true; break; }
                if (!excluded) return true;
            }
        }
        return false;
    }

    /** ja -> ne */
    public static List<Issue> validateJaToNe(String sourceJa, String ne, List<String> requiredTargets) {
        List<Issue> issues = new ArrayList<>();
        if (ne == null || ne.trim().isEmpty()) { issues.add(new Issue(Kind.EMPTY, true, null)); return issues; }
        checkNumbers(sourceJa, ne, issues);
        boolean srcNeg = jaHasNegation(sourceJa), tgtNeg = neHasNegation(ne);
        if (srcNeg && !tgtNeg) issues.add(new Issue(Kind.NEGATION_LOST, true, null));
        if (!srcNeg && tgtNeg) issues.add(new Issue(Kind.NEGATION_ADDED, false, null));   // soft: idioms like "大丈夫です" may surface as negated forms
        if (requiredTargets != null) for (String t : requiredTargets) if (!containsTerm(ne, t)) issues.add(new Issue(Kind.TERM_MISSING, true, t));
        if (JA_SCRIPT.matcher(ne).find()) issues.add(new Issue(Kind.UNTRANSLATED_SOURCE_SCRIPT, true, null));
        if (JA_QUESTION.matcher(sourceJa.trim()).find() && !NE_QUESTION.matcher(ne).find()) issues.add(new Issue(Kind.QUESTION_LOST, false, null));
        checkAgreementJaToNe(sourceJa, ne, issues);
        return issues;
    }

    /** ne -> ja */
    public static List<Issue> validateNeToJa(String sourceNe, String ja) {
        List<Issue> issues = new ArrayList<>();
        if (ja == null || ja.trim().isEmpty()) { issues.add(new Issue(Kind.EMPTY, true, null)); return issues; }
        checkNumbers(sourceNe, KanjiNumber.toDigits(ja), issues);   // 三人 in the output = 3 in the source
        boolean srcNeg = neHasNegation(sourceNe), tgtNeg = jaHasNegation(ja);
        if (srcNeg && !tgtNeg) issues.add(new Issue(Kind.NEGATION_LOST, true, null));
        if (!srcNeg && tgtNeg) issues.add(new Issue(Kind.NEGATION_ADDED, false, null));
        if (Pattern.compile("[\\u0900-\\u097F]").matcher(ja).find()) issues.add(new Issue(Kind.UNTRANSLATED_SOURCE_SCRIPT, true, null));
        if (JA_KATAKANA_REPEAT.matcher(ja).find()) issues.add(new Issue(Kind.REPETITION, true, null));
        checkNeToJaMeaning(sourceNe, ja, issues);
        return issues;
    }

    // ===== ne->ja meaning checks: question word, currency, place names =====
    private static final String NE_L = "(?<![\\u0900-\\u0963\\u0966-\\u097F])";
    private static final String NE_R = "(?![\\u0900-\\u0963\\u0966-\\u097F])";
    // Nepali wh-word -> Japanese forms that render it. के is left out: it is also the yes/no question
    // particle (के तपाईं आउनुहुन्छ? = will you come?). For कति a bare 何 is not enough (これは何ですか for
    // यो कति हो? is the nj04 error): it needs いくら / いくつ / どれくらい or 何 + counter.
    private static final Object[][] NE_WH = {
            {Pattern.compile(NE_L + "कति(?!पय)\\S*"), Pattern.compile("いくら|幾ら|いくつ|幾つ|どれ(くらい|ぐらい|位)|どの(くらい|ぐらい|位)|何[人個歳回時日本枚台度分秒年月週円階番件杯匹冊着名]|なん(にん|こ|さい|かい|じ|にち|ぼん|まい|だい|ど|ぷん|ふん|ねん)")},
            {Pattern.compile(NE_L + "किन" + NE_R), Pattern.compile("なぜ|何故|どうして|なんで|何で|理由")},
            {Pattern.compile(NE_L + "कह[ाँां]{2}\\S*"), Pattern.compile("どこ|何処|どちら|どっち")},
            {Pattern.compile(NE_L + "कहिले(?!काहीं|काहीँ|काहिँ|काहिं)" + NE_R), Pattern.compile("いつ|何時|何日|何曜")},
            {Pattern.compile(NE_L + "कसरी" + NE_R), Pattern.compile("どう|どのよう|どうやって|如何")},
    };
    private static final Pattern NE_RUPEE = Pattern.compile("रुपैयाँ|रुपैयां|रुपियाँ|रुपियां|" + NE_L + "(रु|रू)\\.?\\s*[0-9०-९]|NPR|Rs\\.?\\s*[0-9]");
    private static final Pattern NE_YEN = Pattern.compile(NE_L + "(येन|जापानी येन)" + NE_R);
    private static final Pattern NE_DOLLAR = Pattern.compile(NE_L + "डलर");
    private static final Pattern JA_RUPEE = Pattern.compile("ルピー");
    private static final Pattern JA_YEN = Pattern.compile("[0-9０-９〇一二三四五六七八九十百千万億]\\s*円|円(?![滑満形熟周])");
    private static final Pattern JA_DOLLAR = Pattern.compile("ドル");
    // place names: {Japanese, Nepali stems...}. Stems match at word start (case markers attach: नेपालबाट),
    // but नेपाल must not match नेपाली (the demonym/language is handled by the glossary, not here).
    private static final String[][] PLACES = {
            {"ネパール", "नेपाल"}, {"カトマンズ", "काठमाडौं", "काठमाडौँ"}, {"ポカラ", "पोखरा"}, {"日本", "जापान"},
            {"東京", "टोकियो", "तोक्यो"}, {"大阪", "ओसाका"}, {"インド", "भारत", "इन्डिया"}, {"中国", "चीन"},
            {"アメリカ", "अमेरिका"}, {"ネバダ", "नेभाडा"}, {"シアトル", "सियाटल"}, {"カタルーニャ", "क्याटालोनिया"},
            {"ルンビニ", "लुम्बिनी"}, {"チトワン", "चितवन"}, {"エベレスト", "सगरमाथा", "एभरेस्ट"},
    };
    private static boolean neHasPlace(String ne, String[] place) {
        for (int i = 1; i < place.length; i++) {
            Matcher m = Pattern.compile(NE_L + Pattern.quote(canonTerm(place[i])) + "(?!ी)").matcher(ne);
            if (m.find()) return true;
        }
        return false;
    }

    // ---------------- ja -> ne agreement (IR-lite) ----------------
    // Each rule: a Japanese marker that the predicate carries, and the Nepali forms that express it.
    // Lenient on the Nepali side (any accepted form passes) so a correct candidate is not made hard.
    private static final String NE_CH = "\\u0900-\\u0963\\u0966-\\u097F";
    private static Pattern neWord(String alt) { return Pattern.compile("(?<![" + NE_CH + "])(?:" + alt + ")(?![" + NE_CH + "])"); }
    /** question word in a question -> its Nepali counterpart. 何か/誰も/いつも (indefinites) excluded. */
    private static final String[][] WH = {
            {"何時(?![間代])", "बजे|बेला"},
            {"何人", "कति|कतिजना"},
            {"いくら", "कति\\S*|मूल्य|दाम|पैसा"},
            {"(?:誰|だれ|どなた)(?![かも])", "को|कसले|कसको|कसलाई|कोसँग|कोही"},
            {"どこ(?![かも])", "कहाँ\\S*|कुन ठाउँ\\S*"},
            {"いつ(?![かも])", "कहिले\\S*|कुन दिन\\S*|कति बेला|कति बजे"},
            {"(?:なぜ|どうして|なんで)", "किन"},
            {"(?:どうやって|どのように|どうすれば)", "कसरी|के गर\\S*"},
            {"(?:どれ|どの|どちら|どっち)(?![かも])", "कुन\\S*|कुनचाहिँ|कुनचाहिं"},
    };
    private static final Pattern JA_OBLIGATION = Pattern.compile("(なければ(なら|なり|いけ)|なくては(なら|なり|いけ)|ないと(いけ|だめ|ダメ)|なきゃ|なくちゃ|べき)");
    private static final Pattern NE_OBLIGATION = Pattern.compile("(पर्छ|पर्छन्|पर्ने|पर्यो|पर्‍यो|पर्थ्यो|पर्दछ|पर्नेछ|पर्नुहुन्छ|नुपर|चाहिन्छ|अनिवार्य)");
    private static final Pattern JA_REQUEST = Pattern.compile("(ください|下さい|[てで](くれ(ませんか|ますか)?|ちょうだい|もらえ|いただけ)|お願い)");
    private static final Pattern NE_REQUEST = Pattern.compile("(नुहोस्|नुस्|नुहोला|होस्|देऊ|देउ|दिनु|कृपया|अनुरोध|सक्नुहुन्छ|सक्छौ|ऊ(?![" + NE_CH + "])|ओ(?![" + NE_CH + "]))");
    private static final Pattern JA_CONJECTURE = Pattern.compile("(でしょう(?!か)|だろう|かもしれ|と思い|と思う|みたい|らしい|はず)");
    private static final Pattern NE_CONJECTURE = Pattern.compile("(होला|ला(?![" + NE_CH + "])|सक्छ|सक्छन्|जस्तो|जस्तै|लाग्छ|लाग्यो|ठान्छु|सायद|शायद|पक्का|हुनुपर्छ|होइन र)");
    private static final Pattern JA_PERMISSION = Pattern.compile("[てで]も(いい|良い|よい|大丈夫|かまわ|構わ)");
    private static final Pattern NE_PERMISSION = Pattern.compile("(हुन्छ|सकिन्छ|सक्नुहुन्छ|सक्छ|ठीक|ठिक|अनुमति|पाइन्छ|पर्दैन|मिल्छ)");
    /** sentence-final past/perfect predicate (ました・でした・かった・〜た). あなた etc. excluded by the kana before た. */
    private static final Pattern JA_PAST = Pattern.compile("(ました|でした|かった(です)?|[っんいきしちにびみりえけせてねべめれげ]た|[っん]だ)(よ|ね|よね|か)?[。！!？?]?$");
    /** Nepali past, perfect and negative past (गयो, गए, गएका छन्, थियो, गर्नुभयो, गएन). Vowel-sign ए (गरे) is not
     *  used: it also ends ergative nouns (रामले). */
    private static final Pattern NE_PAST = neWord("\\S*(?:थियो|थिए|थिइन्|थिएँ|थियौं|थ्यो|यो|एको|एका|एकी|भयो|भएको|सक्यो|सके|इन्|इनँ|इनौं|ए|एँ|एन|एनन्|ेन|ेनन्|ेको|ेका|ेकी|्यो|‍यो)");
    private static final String[] NE_PAST_NOT = {"यो", "त्यो", "जो", "उनको"};

    private static String lastSentence(String ja) {
        String t = ja.trim();
        String[] parts = t.split("(?<=[。！!？?])");
        for (int i = parts.length - 1; i >= 0; i--) if (!parts[i].trim().isEmpty()) return parts[i].trim();
        return t;
    }

    private static boolean nePast(String ne) {
        Matcher m = NE_PAST.matcher(Normalizer.normalize(ne, Normalizer.Form.NFC));
        while (m.find()) {
            String w = m.group();
            boolean skip = false;
            for (String x : NE_PAST_NOT) if (w.equals(x)) { skip = true; break; }
            if (!skip) return true;
        }
        return false;
    }

    /** ne -> ja checks kept separate from the ja->ne code paths. All hard: each one is a meaning change. */
    static void checkNeToJaMeaning(String sourceNe, String ja, List<Issue> issues) {
        String ne = Normalizer.normalize(sourceNe, Normalizer.Form.NFC);
        String neCanon = canonTerm(sourceNe);
        String j = Normalizer.normalize(ja, Normalizer.Form.NFKC);
        // demonyms / languages (ネパール語, 日本人) are not place mentions: नेपाली is not नेपाल
        String jPlaces = j.replaceAll("(ネパール|日本|インド|中国|アメリカ)(語|人)", "");
        for (Object[] wh : NE_WH) {
            Matcher m = ((Pattern) wh[0]).matcher(ne);
            if (m.find() && !((Pattern) wh[1]).matcher(j).find()) issues.add(new Issue(Kind.QUESTION_WORD_LOST, true, m.group()));
        }
        boolean rupee = NE_RUPEE.matcher(ne).find(), yen = NE_YEN.matcher(ne).find(), dollar = NE_DOLLAR.matcher(ne).find();
        if (rupee && !JA_RUPEE.matcher(j).find()) issues.add(new Issue(Kind.CURRENCY_MISMATCH, true, "ルピー"));
        if (!yen && JA_YEN.matcher(j).find()) issues.add(new Issue(Kind.CURRENCY_MISMATCH, true, "円"));
        if (!dollar && JA_DOLLAR.matcher(j).find()) issues.add(new Issue(Kind.CURRENCY_MISMATCH, true, "ドル"));
        for (String[] place : PLACES) {
            boolean inSrc = neHasPlace(neCanon, place), inTgt = jPlaces.contains(place[0]);
            if (inSrc && !j.contains(place[0])) issues.add(new Issue(Kind.PLACE_MISSING, true, place[0]));
            // a known place in the output that the source never mentions: hallucinated (ネバダ州シアトル)
            if (!inSrc && inTgt) issues.add(new Issue(Kind.PLACE_ADDED, true, place[0]));
        }
    }

    static void checkAgreementJaToNe(String sourceJa, String ne, List<Issue> issues) {
        String ja = Normalizer.normalize(sourceJa, Normalizer.Form.NFKC);
        String n = Normalizer.normalize(ne, Normalizer.Form.NFC);
        if (JA_QUESTION.matcher(ja.trim()).find()) {
            for (String[] w : WH) {
                Matcher jm = Pattern.compile(w[0]).matcher(ja);
                if (jm.find() && !neWord(w[1]).matcher(n).find()) issues.add(new Issue(Kind.WH_LOST, true, jm.group()));
            }
        }
        if (JA_OBLIGATION.matcher(ja).find() && !NE_OBLIGATION.matcher(n).find()) issues.add(new Issue(Kind.OBLIGATION_LOST, true, null));
        if (JA_REQUEST.matcher(ja).find() && !NE_REQUEST.matcher(n).find()) issues.add(new Issue(Kind.REQUEST_LOST, true, null));
        // softer: these have many renderings, so they only break ties inside the score
        if (JA_CONJECTURE.matcher(ja).find() && !NE_CONJECTURE.matcher(n).find()) issues.add(new Issue(Kind.CONJECTURE_LOST, false, null));
        if (JA_PERMISSION.matcher(ja).find() && !NE_PERMISSION.matcher(n).find()) issues.add(new Issue(Kind.PERMISSION_LOST, false, null));
        // past: hard only for a past question/statement whose candidate has no past/perfect form at all
        // (jn14 先生はもう帰られました -> "is at home")
        if (JA_PAST.matcher(lastSentence(ja)).find() && !nePast(n)) issues.add(new Issue(Kind.PAST_LOST, true, null));
    }

    private static void checkNumbers(String src, String tgt, List<Issue> issues) {
        List<String> a = QualityScorer.numbers(src), b = QualityScorer.numbers(tgt);
        List<String> rest = new ArrayList<>(b);
        for (String n : a) {
            if (!rest.remove(n)) issues.add(new Issue(Kind.NUMBER_LOST, true, n));
        }
        for (String n : rest) issues.add(new Issue(Kind.NUMBER_ADDED, false, n));
    }

    /**
     * Term containment that ignores spelling variants the model produces: NFC, candrabindu vs anusvara
     * (काठमाडौँ / काठमाडौं), and full-width vs ASCII for Latin/digits.
     */
    public static boolean containsTerm(String text, String term) {
        if (text == null || term == null) return false;
        return canonTerm(text).contains(canonTerm(term));
    }

    static String canonTerm(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replace('\u0901', '\u0902');
    }

    // a katakana word of 2+ chars immediately repeated (ネパールネパール): degenerate output
    private static final Pattern JA_KATAKANA_REPEAT = Pattern.compile("([\\u30A1-\\u30FAー]{2,})\\1");

    public static int hardCount(List<Issue> issues) {
        int c = 0;
        for (Issue i : issues) if (i.hard) c++;
        return c;
    }
}
