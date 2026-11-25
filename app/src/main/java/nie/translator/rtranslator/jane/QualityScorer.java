package nie.translator.rtranslator.jane;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reference-free quality estimate for a ja -> ne candidate:
 *   chrF (Popovic 2015) between the source and the candidate's back-translation (round-trip adequacy),
 *   minus penalties for lost numbers, missing required terms, foreign script and abnormal length.
 * Used to rerank candidates (quality-aware selection, Fernandes et al. 2022).
 */
public final class QualityScorer {
    private static final int CHRF_N = 4;   // characters; good for both Japanese and Devanagari
    private static final double BETA = 2.0;

    private QualityScorer() {}

    public static double chrF(String hypothesis, String reference) {
        String h = strip(hypothesis), r = strip(reference);
        if (h.isEmpty() || r.isEmpty()) return 0;
        double precSum = 0, recSum = 0;
        int orders = 0;
        for (int n = 1; n <= CHRF_N; n++) {
            Map<String, Integer> hc = ngrams(h, n), rc = ngrams(r, n);
            int ht = total(hc), rt = total(rc);
            if (ht == 0 || rt == 0) continue;
            int match = 0;
            for (Map.Entry<String, Integer> e : hc.entrySet()) match += Math.min(e.getValue(), rc.getOrDefault(e.getKey(), 0));
            precSum += (double) match / ht;
            recSum += (double) match / rt;
            orders++;
        }
        if (orders == 0) return 0;
        double p = precSum / orders, rcl = recSum / orders;
        if (p + rcl == 0) return 0;
        double b2 = BETA * BETA;
        return (1 + b2) * p * rcl / (b2 * p + rcl);
    }

    /** Numbers as canonical ASCII strings; see {@link nie.translator.rtranslator.jane.text.Numbers#numbers}. */
    public static List<String> numbers(String s) { return nie.translator.rtranslator.jane.text.Numbers.numbers(s); }

    public static double foreignLetterRatio(String ne) {
        return foreignLetterRatio(ne, "");
    }

    /**
     * Share of non-Devanagari letters. Latin words that also appear in the source (names, "Wi-Fi", "ID",
     * "LINE") are legitimate and are not counted as foreign.
     */
    public static double foreignLetterRatio(String ne, String source) {
        java.util.Set<String> allowed = new java.util.HashSet<>();
        Matcher lm = LATIN_WORD.matcher(source == null ? "" : java.text.Normalizer.normalize(source, java.text.Normalizer.Form.NFKC));
        while (lm.find()) allowed.add(lm.group().toLowerCase(java.util.Locale.ROOT));
        String t = ne;
        if (!allowed.isEmpty()) {
            Matcher m = LATIN_WORD.matcher(ne);
            StringBuffer sb = new StringBuffer();
            while (m.find()) m.appendReplacement(sb, allowed.contains(m.group().toLowerCase(java.util.Locale.ROOT)) ? "" : Matcher.quoteReplacement(m.group()));
            m.appendTail(sb);
            t = sb.toString();
        }
        int letters = 0, foreign = 0;
        for (char c : t.toCharArray()) {
            if (Character.isLetter(c)) {
                letters++;
                if (c < 0x0900 || c > 0x097F) foreign++;
            }
        }
        if (letters == 0) return allowed.isEmpty() ? 1.0 : 0.0;
        return (double) foreign / letters;
    }

    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9\\-]*");

    public static double score(String sourceJa, String candidateNe, String backTranslationJa, List<String> requiredTargets) {
        double s = chrF(backTranslationJa, sourceJa);
        // numbers in the source must survive
        List<String> srcNums = numbers(sourceJa), candNums = numbers(candidateNe);
        for (String n : srcNums) if (!candNums.remove(n)) s -= 0.15;
        // required terminology
        for (String t : requiredTargets) if (!TranslationValidator.containsTerm(candidateNe, t)) s -= 0.10;
        // script purity
        s -= 0.5 * foreignLetterRatio(candidateNe, sourceJa);
        // register: a polite Japanese source (です/ます) must not become informal Nepali (तिमी / तँ forms)
        if (isPoliteJa(sourceJa) && containsInformalNe(candidateNe)) s -= 0.10;
        // length sanity: Nepali in Devanagari is typically 0.8x-3x the Japanese character count
        double ratio = (double) strip(candidateNe).length() / Math.max(1, strip(sourceJa).length());
        if (ratio < 0.5 || ratio > 4.0) s -= 0.2;
        return s;
    }

    private static final Pattern POLITE_JA = Pattern.compile("(です|ます|ました|ません|でした|ください|ございます|いただ)");
    private static final Pattern INFORMAL_NE = Pattern.compile("(^|[\\s,।])(तिमी|तिमीलाई|तिम्रो|तँ|तेरो|तँलाई)(?=$|[\\s,।?])");

    public static boolean isPoliteJa(String ja) { return POLITE_JA.matcher(ja).find(); }

    public static boolean containsInformalNe(String ne) { return INFORMAL_NE.matcher(ne).find(); }

    private static String strip(String s) {
        if (s == null) return "";
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC);
        return s.replaceAll("[\\s。、！？!?,.।]", "");
    }

    private static Map<String, Integer> ngrams(String s, int n) {
        Map<String, Integer> m = new HashMap<>();
        int[] cps = s.codePoints().toArray();
        for (int i = 0; i + n <= cps.length; i++) {
            String g = new String(cps, i, n);
            m.merge(g, 1, Integer::sum);
        }
        return m;
    }

    private static int total(Map<String, Integer> m) {
        int t = 0;
        for (int v : m.values()) t += v;
        return t;
    }
}
