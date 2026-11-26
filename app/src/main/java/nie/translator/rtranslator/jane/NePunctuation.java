package nie.translator.rtranslator.jane;

import java.util.regex.Pattern;

/**
 * Restores the sentence-final mark that Nepali speech recognition drops, before translation.
 * Lost punctuation is a measured cause of worse speech translation for Nepali (arXiv 2602.21647: +4.90 BLEU
 * after restoring it). Only adds a mark when the text has none; never changes the words. Shown text is not touched.
 */
public final class NePunctuation {
    private NePunctuation() {}

    private static final String L = "(?<![\\u0900-\\u0963\\u0966-\\u097F])", R = "(?![\\u0900-\\u0963\\u0966-\\u097F])";
    // wh-words; के only at the start (elsewhere it is "what"/"that" and also fine as a question marker at the start)
    private static final Pattern QUESTION = Pattern.compile(
            "^\\s*के" + R + "|" + L + "(कहाँ|किन|कति|कहिले|कसरी|कुन|कस्तो|कसको|कसलाई)" + R + "|" + L + "(हो|छ|हुन्छ)\\s+कि\\s*$|" + L + "होला\\s*$");
    private static final Pattern HAS_END = Pattern.compile("[।॥?？!！.]\\s*$");

    public static String restore(String ne) {
        if (ne == null) return null;
        String t = ne.trim();
        if (t.isEmpty() || HAS_END.matcher(t).find()) return ne;
        if (!t.matches(".*[\\u0900-\\u097F].*")) return ne;   // not Devanagari: leave it
        return t + (QUESTION.matcher(t).find() ? "?" : "।");
    }
}
