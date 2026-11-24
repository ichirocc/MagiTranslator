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
            "(少ない|危ない|汚い|幼い|切ない|せつない|だらしない|つまらない|くだらない|もったいない|勿体ない|仕方(が)?ない|しかた(が)?ない|間違いない|違いない|半端ない|すくない|あぶない)");
    // --- Nepali negation: suffix forms and the negative prefix न- on verbs.
    private static final Pattern NE_NEG_WORD = Pattern.compile(
            "(छैन|छैनन्|छैनौं|होइन|होइनन्|हुँदैन|हुन्न|दैन|दैनन्|दैनौं|दैनौ|दिनँ|दिनौं|न्न)$");   // e.g. जाँदिनँ, गर्दैन, सक्दैनन्
    private static final Pattern NE_NEG_PREFIX = Pattern.compile("^न(?:[क-ह])");
    private static final String[] NE_NOT_NEGATION = {"नमस्ते", "नमस्कार", "नयाँ", "नजिक", "नदी", "नगर", "नक्सा", "नम्बर", "नरम", "नर्स", "नल", "नसा", "नक्कली", "नतिजा", "नर्सरी"};
    private static final Pattern NE_WORD = Pattern.compile("[\\u0900-\\u0963\\u0966-\\u097F]+");   // excludes the danda । ॥
    private static final Pattern JA_SCRIPT = Pattern.compile("[\\u3040-\\u30FF\\u4E00-\\u9FFF]");
    private static final Pattern JA_QUESTION = Pattern.compile("(か[。？]?$|？|\\?)");
    private static final Pattern NE_QUESTION = Pattern.compile("(\\?|？|के |कहाँ|कति|कसरी|किन|कुन|को हो|कहिले|कस्तो)");

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
        List<Issue> issues = new ArrayList<>();
        if (ja == null || ja.trim().isEmpty()) { issues.add(new Issue(Kind.EMPTY, true, null)); return issues; }
        checkNumbers(sourceNe, ja, issues);
        boolean srcNeg = neHasNegation(sourceNe), tgtNeg = jaHasNegation(ja);
        if (srcNeg && !tgtNeg) issues.add(new Issue(Kind.NEGATION_LOST, true, null));
        if (!srcNeg && tgtNeg) issues.add(new Issue(Kind.NEGATION_ADDED, false, null));
        if (Pattern.compile("[\\u0900-\\u097F]").matcher(ja).find()) issues.add(new Issue(Kind.UNTRANSLATED_SOURCE_SCRIPT, true, null));
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
