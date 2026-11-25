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

    public enum Kind { NUMBER_LOST, NUMBER_ADDED, NEGATION_LOST, NEGATION_ADDED, TERM_MISSING, UNTRANSLATED_SOURCE_SCRIPT, EMPTY, QUESTION_LOST }

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
        if (requiredTargets != null) for (String t : requiredTargets) if (!ne.contains(t)) issues.add(new Issue(Kind.TERM_MISSING, true, t));
        if (JA_SCRIPT.matcher(ne).find()) issues.add(new Issue(Kind.UNTRANSLATED_SOURCE_SCRIPT, true, null));
        if (JA_QUESTION.matcher(sourceJa.trim()).find() && !NE_QUESTION.matcher(ne).find()) issues.add(new Issue(Kind.QUESTION_LOST, false, null));
        return issues;
    }

    /** ne -> ja */
    public static List<Issue> validateNeToJa(String sourceNe, String ja) {
        return validateNeToJa(sourceNe, ja, null);
    }

    /** ne -> ja with required Japanese terms (NeJaGlossary) */
    public static List<Issue> validateNeToJa(String sourceNe, String ja, List<String> requiredTargets) {
        List<Issue> issues = new ArrayList<>();
        if (ja == null || ja.trim().isEmpty()) { issues.add(new Issue(Kind.EMPTY, true, null)); return issues; }
        checkNumbers(sourceNe, KanjiNumber.toDigits(ja), issues);   // 三人 in the output = 3 in the source
        boolean srcNeg = neHasNegation(sourceNe), tgtNeg = jaHasNegation(ja);
        if (srcNeg && !tgtNeg) issues.add(new Issue(Kind.NEGATION_LOST, true, null));
        if (!srcNeg && tgtNeg) issues.add(new Issue(Kind.NEGATION_ADDED, false, null));
        if (Pattern.compile("[\\u0900-\\u097F]").matcher(ja).find()) issues.add(new Issue(Kind.UNTRANSLATED_SOURCE_SCRIPT, true, null));
        if (requiredTargets != null) for (String t : requiredTargets) if (!ja.contains(t)) issues.add(new Issue(Kind.TERM_MISSING, true, t));
        return issues;
    }

    private static void checkNumbers(String src, String tgt, List<Issue> issues) {
        List<String> a = QualityScorer.numbers(src), b = QualityScorer.numbers(tgt);
        List<String> rest = new ArrayList<>(b);
        for (String n : a) {
            if (!rest.remove(n)) issues.add(new Issue(Kind.NUMBER_LOST, true, n));
        }
        for (String n : rest) issues.add(new Issue(Kind.NUMBER_ADDED, false, n));
    }

    public static int hardCount(List<Issue> issues) {
        int c = 0;
        for (Issue i : issues) if (i.hard) c++;
        return c;
    }
}
