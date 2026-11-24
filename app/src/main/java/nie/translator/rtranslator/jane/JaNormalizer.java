package nie.translator.rtranslator.jane;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Cleans Japanese input (often ASR output) before MT. Pure Java, no Android dependency. */
public final class JaNormalizer {
    // Spoken fillers. Only removed when followed by a pause mark, so "あの人" is kept.
    // うん (= yes) and なんか (= something / somehow) carry meaning and are never removed.
    private static final Pattern FILLERS = Pattern.compile(
            "(?:^|(?<=[、。！？\\s]))(?:えーっと|ええっと|えっと|えーと|ええと|えー|あのー|あのう|あの|そのー|まあ|うーん|んー)[、,\\s]+");
    private static final Pattern LONG_MARKS = Pattern.compile("[ー〜~]{2,}");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u3000]+");

    private JaNormalizer() {}

    public static String normalize(String text) {
        if (text == null) return "";
        String t = Normalizer.normalize(text, Normalizer.Form.NFKC);
        // NFKC turns "。" into itself but "！？" into ASCII; restore them first so that the filler rule
        // (which looks behind for 、。！？) and the segmenter both see the Japanese marks
        t = t.replace("!", "！").replace("?", "？");
        t = FILLERS.matcher(t).replaceAll("");
        t = LONG_MARKS.matcher(t).replaceAll("ー");
        t = SPACES.matcher(t).replaceAll(" ").trim();
        t = KanjiNumber.toDigits(t);
        // Only fillers / punctuation: there is nothing to translate. Returning the raw input here would
        // feed the filler itself to the NMT, so an empty string is returned instead.
        return t.replaceAll("[、。！？,.\\s]", "").isEmpty() ? "" : t;
    }

    /** Adds an explicit sentence end; Madlad translates complete sentences more faithfully. */
    public static String ensureSentenceEnd(String s) {
        String t = s.trim();
        if (t.isEmpty()) return t;
        char last = t.charAt(t.length() - 1);
        return "。！？".indexOf(last) >= 0 ? t : t + "。";
    }
}
