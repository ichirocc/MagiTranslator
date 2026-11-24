package nie.translator.rtranslator.jane;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Cleans Japanese input (often ASR output) before MT. Pure Java, no Android dependency. */
public final class JaNormalizer {
    // Spoken fillers. Only removed when followed by a pause mark, so "あの人" is kept.
    private static final Pattern FILLERS = Pattern.compile(
            "(?:^|(?<=[、。！？\\s]))(?:えーっと|えっと|えーと|えー|あのー|あの|そのー|まあ|うーん|んー|なんか)[、,\\s]+");
    private static final Pattern LONG_MARKS = Pattern.compile("[ー〜~]{2,}");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u3000]+");

    private JaNormalizer() {}

    public static String normalize(String text) {
        if (text == null) return "";
        String t = Normalizer.normalize(text, Normalizer.Form.NFKC);
        t = FILLERS.matcher(t).replaceAll("");
        t = LONG_MARKS.matcher(t).replaceAll("ー");
        t = SPACES.matcher(t).replaceAll(" ").trim();
        // NFKC turns "。" into itself but "！？" into ASCII; keep Japanese full stop for the segmenter
        t = t.replace("!", "！").replace("?", "？");
        t = KanjiNumber.toDigits(t);
        return t.isEmpty() ? text.trim() : t;
    }

    /** Adds an explicit sentence end; Madlad translates complete sentences more faithfully. */
    public static String ensureSentenceEnd(String s) {
        String t = s.trim();
        if (t.isEmpty()) return t;
        char last = t.charAt(t.length() - 1);
        return "。！？".indexOf(last) >= 0 ? t : t + "。";
    }
}
