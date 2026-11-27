package nie.translator.rtranslator.jane;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import nie.translator.rtranslator.jane.ir.Person;
import nie.translator.rtranslator.jane.ir.Traps;
import nie.translator.rtranslator.jane.ir.Currency;
import nie.translator.rtranslator.jane.ir.IrText;
import nie.translator.rtranslator.jane.ir.JaAnalyzer;
import nie.translator.rtranslator.jane.ir.LanguageIR;
import nie.translator.rtranslator.jane.ir.Modality;
import nie.translator.rtranslator.jane.ir.NeAnalyzer;
import nie.translator.rtranslator.jane.ir.Places;
import nie.translator.rtranslator.jane.ir.Tense;
import nie.translator.rtranslator.jane.ir.WhConcept;

/**
 * Detects meaning errors that string similarity cannot see. Each issue is "hard" (the candidate is
 * very likely wrong) or "soft". JaNeTranslator ranks candidates lexicographically: fewer hard issues
 * first, then the quality score (same acceptance idea as the MAGI optimizer: HARD -> score).
 *
 * Meaning checks compare the Stage 1 Language IR of the source with that of the candidate
 * (jane.ir: JaAnalyzer / NeAnalyzer). Only string-level checks (empty, leftover script, terms) work
 * on the raw text here.
 */
public final class TranslationValidator {

    public enum Kind { NUMBER_LOST, NUMBER_ADDED, NEGATION_LOST, NEGATION_ADDED, TERM_MISSING, UNTRANSLATED_SOURCE_SCRIPT, EMPTY, QUESTION_LOST, REPETITION,
        // ne->ja meaning checks (checkNeToJaMeaning)
        QUESTION_WORD_LOST, CURRENCY_MISMATCH, PLACE_MISSING, PLACE_ADDED,
        /** ja->ne agreement (IR-lite, SPEC v2 part 2): the candidate dropped what the Japanese predicate states */
        WH_LOST, OBLIGATION_LOST, REQUEST_LOST, PAST_LOST, CONJECTURE_LOST, PERMISSION_LOST, NAME_EXTRA_VOWEL,
        /** ja states the speaker's own action, the candidate tells the listener to do it (zero-pronoun reversal) */
        PERSON_REVERSED,
        /** everyday traps: もう少し as finished, 貸す/借りる swapped (hard); medicine with पिउनु (soft) */
        ADVERB_REVERSED, DIRECTION_REVERSED, MEDICINE_VERB,
        /** OJT traps: guidance turned into its opposite (hard); 見守る reduced to a plain look (soft) */
        GUIDANCE_REVERSED, WATCH_OVER_LOST }

    public static final class Issue {
        public final Kind kind;
        public final boolean hard;
        public final String detail;
        Issue(Kind kind, boolean hard, String detail) { this.kind = kind; this.hard = hard; this.detail = detail; }
        @Override public String toString() { return (hard ? "HARD " : "soft ") + kind + (detail == null ? "" : "(" + detail + ")"); }
    }

    private static final Pattern JA_SCRIPT = Pattern.compile("[\\u3040-\\u30FF\\u4E00-\\u9FFF]");
    private static final Pattern NE_SCRIPT = Pattern.compile("[\\u0900-\\u097F]");

    private TranslationValidator() {}

    public static boolean jaHasNegation(String ja) { return JaAnalyzer.hasNegation(ja); }

    public static boolean neHasNegation(String ne) { return NeAnalyzer.hasNegation(ne); }

    /** ja -> ne */
    public static List<Issue> validateJaToNe(String sourceJa, String ne, List<String> requiredTargets) {
        List<Issue> issues = new ArrayList<>();
        if (ne == null || ne.trim().isEmpty()) { issues.add(new Issue(Kind.EMPTY, true, null)); return issues; }
        return compareJaToNe(JaAnalyzer.analyze(sourceJa), NeAnalyzer.analyze(ne), requiredTargets, issues);
    }

    /** ja -> ne, on IRs already built (the source IR can be reused across candidates). */
    public static List<Issue> compareJaToNe(LanguageIR src, LanguageIR tgt, List<String> requiredTargets, List<Issue> issues) {
        String ne = tgt.originalText;
        checkNumbers(src.numbers, tgt.numbers, issues);
        if (src.negated && !tgt.negated) issues.add(new Issue(Kind.NEGATION_LOST, true, null));
        if (!src.negated && tgt.negated) issues.add(new Issue(Kind.NEGATION_ADDED, false, null));   // soft: idioms like "大丈夫です" may surface as negated forms
        if (requiredTargets != null) for (String t : requiredTargets) if (!containsTerm(ne, t)) issues.add(new Issue(Kind.TERM_MISSING, true, t));
        if (JA_SCRIPT.matcher(ne).find()) issues.add(new Issue(Kind.UNTRANSLATED_SOURCE_SCRIPT, true, null));
        if (src.question && !tgt.question) issues.add(new Issue(Kind.QUESTION_LOST, false, null));
        checkAgreementJaToNe(src, tgt, issues);
        checkCurrencyJaToNe(src, tgt, issues);
        return issues;
    }

    private static final Pattern NE_YUAN = Pattern.compile("युआन|युवान|चिनियाँ\\s*युआन");

    /** ja -> ne currency: 円 must stay येन, and no other currency may appear (jn12 「500円」 -> 「५०० युआन」). */
    static void checkCurrencyJaToNe(LanguageIR src, LanguageIR tgt, List<Issue> issues) {
        if (src.currencies.contains(Currency.YEN) && !tgt.currencies.contains(Currency.YEN)) issues.add(new Issue(Kind.CURRENCY_MISMATCH, true, "येन"));
        if (src.currencies.contains(Currency.RUPEE) && !tgt.currencies.contains(Currency.RUPEE)) issues.add(new Issue(Kind.CURRENCY_MISMATCH, true, "रुपैयाँ"));
        if (!src.currencies.contains(Currency.RUPEE) && tgt.currencies.contains(Currency.RUPEE)) issues.add(new Issue(Kind.CURRENCY_MISMATCH, true, "रुपैयाँ"));
        if (!src.currencies.contains(Currency.DOLLAR) && tgt.currencies.contains(Currency.DOLLAR)) issues.add(new Issue(Kind.CURRENCY_MISMATCH, true, "डलर"));
        if (NE_YUAN.matcher(tgt.originalText).find()) issues.add(new Issue(Kind.CURRENCY_MISMATCH, true, "युआन"));
    }

    /** ne -> ja */
    public static List<Issue> validateNeToJa(String sourceNe, String ja) {
        List<Issue> issues = new ArrayList<>();
        if (ja == null || ja.trim().isEmpty()) { issues.add(new Issue(Kind.EMPTY, true, null)); return issues; }
        return compareNeToJa(NeAnalyzer.analyze(sourceNe), JaAnalyzer.analyze(ja), issues);
    }

    /** ne -> ja, on IRs already built. */
    public static List<Issue> compareNeToJa(LanguageIR src, LanguageIR tgt, List<Issue> issues) {
        checkNumbers(src.numbers, tgt.numbersNormalized, issues);   // 三人 in the output = 3 in the source
        if (src.negated && !tgt.negated) issues.add(new Issue(Kind.NEGATION_LOST, true, null));
        if (!src.negated && tgt.negated) issues.add(new Issue(Kind.NEGATION_ADDED, false, null));
        if (NE_SCRIPT.matcher(tgt.originalText).find()) issues.add(new Issue(Kind.UNTRANSLATED_SOURCE_SCRIPT, true, null));
        if (tgt.degenerateRepeat) issues.add(new Issue(Kind.REPETITION, true, null));
        checkNeToJaMeaning(src, tgt, issues);
        return issues;
    }

    /** ne -> ja meaning checks (question word, currency, place names). All hard: each one is a meaning change. */
    static void checkNeToJaMeaning(LanguageIR src, LanguageIR tgt, List<Issue> issues) {
        for (Map.Entry<WhConcept, String> w : src.whWords.entrySet())
            if (!tgt.whCover.contains(w.getKey())) issues.add(new Issue(Kind.QUESTION_WORD_LOST, true, w.getValue()));
        if (src.currencies.contains(Currency.RUPEE) && !tgt.currencies.contains(Currency.RUPEE)) issues.add(new Issue(Kind.CURRENCY_MISMATCH, true, "ルピー"));
        if (!src.currencies.contains(Currency.YEN) && tgt.currencies.contains(Currency.YEN)) issues.add(new Issue(Kind.CURRENCY_MISMATCH, true, "円"));
        if (!src.currencies.contains(Currency.DOLLAR) && tgt.currencies.contains(Currency.DOLLAR)) issues.add(new Issue(Kind.CURRENCY_MISMATCH, true, "ドル"));
        for (String place : Places.keys()) {
            boolean inSrc = src.places.contains(place);
            if (inSrc && !tgt.places.contains(place)) issues.add(new Issue(Kind.PLACE_MISSING, true, place));
            // a known place in the output that the source never mentions: hallucinated (ネバダ州シアトル)
            if (!inSrc && tgt.placesAsPlace.contains(place)) issues.add(new Issue(Kind.PLACE_ADDED, true, place));
        }
    }

    /** ja -> ne agreement: what the Japanese source states must be expressible by the candidate's forms. */
    static void checkAgreementJaToNe(LanguageIR src, LanguageIR tgt, List<Issue> issues) {
        if (src.question) {
            for (Map.Entry<WhConcept, String> w : src.whWords.entrySet())
                if (!tgt.whCover.contains(w.getKey())) issues.add(new Issue(Kind.WH_LOST, true, w.getValue()));
        }
        if (src.modalities.contains(Modality.OBLIGATION) && !tgt.modalityCover.contains(Modality.OBLIGATION)) issues.add(new Issue(Kind.OBLIGATION_LOST, true, null));
        if (src.modalities.contains(Modality.REQUEST) && !tgt.modalityCover.contains(Modality.REQUEST)) issues.add(new Issue(Kind.REQUEST_LOST, true, null));
        // softer: these have many renderings, so they only break ties inside the score
        if (src.modalities.contains(Modality.CONJECTURE) && !tgt.modalityCover.contains(Modality.CONJECTURE)) issues.add(new Issue(Kind.CONJECTURE_LOST, false, null));
        if (src.modalities.contains(Modality.PERMISSION) && !tgt.modalityCover.contains(Modality.PERMISSION)) issues.add(new Issue(Kind.PERMISSION_LOST, false, null));
        // past: hard only for a past statement/question whose candidate has no past/perfect form at all
        // (jn14 先生はもう帰られました -> "is at home")
        if (src.tense == Tense.PAST && tgt.tense != Tense.PAST) issues.add(new Issue(Kind.PAST_LOST, true, null));
        if (Person.jaStatesOwnAction(src.originalText) && Person.neOnlyListenerImperative(tgt.originalText)) issues.add(new Issue(Kind.PERSON_REVERSED, true, null));
        String sj = src.originalText, tn = tgt.originalText;
        if (Traps.adverbReversed(sj, tn)) issues.add(new Issue(Kind.ADVERB_REVERSED, true, null));
        if (Traps.directionReversed(sj, tn)) issues.add(new Issue(Kind.DIRECTION_REVERSED, true, null));
        if (Traps.medicineDrink(sj, tn)) issues.add(new Issue(Kind.MEDICINE_VERB, false, null));
        if (Traps.guidanceReversed(sj, tn)) issues.add(new Issue(Kind.GUIDANCE_REVERSED, true, null));
        if (Traps.watchOverLost(sj, tn)) issues.add(new Issue(Kind.WATCH_OVER_LOST, false, null));
    }

    private static void checkNumbers(List<String> a, List<String> b, List<Issue> issues) {
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

    static String canonTerm(String s) { return IrText.canon(s); }

    public static int hardCount(List<Issue> issues) {
        int c = 0;
        for (Issue i : issues) if (i.hard) c++;
        return c;
    }
}
