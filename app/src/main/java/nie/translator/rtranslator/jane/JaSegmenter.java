package nie.translator.rtranslator.jane;

import java.util.ArrayList;
import java.util.List;

/**
 * Japanese sentence splitter for MT input, including unpunctuated ASR output.
 * Rules:
 *  - split after 。！？ unless inside brackets 「」『』（）;
 *  - in unpunctuated text, split at a space that follows a polite sentence-final form
 *    (ます/です/ください/ましょう/でしょう… optionally + よ/ね/か) unless the next word is a
 *    conjunctive particle (が, けど, から, ので, のに, し, と, て …) that continues the sentence;
 *  - never split inside "ますます" (= more and more).
 */
public final class JaSegmenter {
    private static final String[] POLITE_ENDS = {"ませんでした", "ましょう", "ました", "ません", "ください", "でしょう", "でした", "ます", "です"};
    private static final String[] CONTINUATIONS = {"が", "けど", "けれど", "から", "ので", "のに", "し", "と", "て", "で", "ね", "よ", "か"};

    private JaSegmenter() {}

    public static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ("「『（(【".indexOf(c) >= 0) depth++;
            if ("」』）)】".indexOf(c) >= 0 && depth > 0) {
                depth--;
                // 「明日行きます。」「今日は帰ります。」: a quoted sentence that is complete, followed by another quote
                // (or the end), is a sentence of its own; 「…。」と言いました stays one
                int last = cur.length() - 1;
                if (depth == 0 && last >= 0 && "。！？".indexOf(cur.charAt(last)) >= 0
                        && (i + 1 >= text.length() || "「『（(【".indexOf(text.charAt(i + 1)) >= 0 || Character.isWhitespace(text.charAt(i + 1)))) {
                    cur.append(c);
                    String q = cur.toString().trim();   //already ends with 。」: no extra 。 (flush would add one)
                    cur.setLength(0);
                    if (!q.isEmpty()) out.add(q);
                    continue;
                }
            }
            if (Character.isWhitespace(c) && depth == 0 && endsWithPoliteForm(cur) && !startsWithContinuation(text, i + 1)) {
                flush(cur, out);
                continue;
            }
            cur.append(c);
            if ("。！？".indexOf(c) >= 0 && depth == 0) {
                // keep trailing closing marks with the sentence (e.g. "。」")
                while (i + 1 < text.length() && "」』）)".indexOf(text.charAt(i + 1)) >= 0) cur.append(text.charAt(++i));
                flush(cur, out);
            }
        }
        flush(cur, out);
        return out;
    }

    /** True when the text contains several sentences (worth a sentence-by-sentence candidate). */
    public static boolean isMulti(String text) {
        return split(text).size() > 1;
    }

    private static void flush(StringBuilder cur, List<String> out) {
        String s = cur.toString().trim();
        cur.setLength(0);
        if (!s.isEmpty()) out.add(JaNormalizer.ensureSentenceEnd(s));
    }

    private static boolean endsWithPoliteForm(CharSequence cs) {
        String s = cs.toString().trim();
        if (s.endsWith("ますます")) return false;
        String core = s;
        if (!core.isEmpty() && "よねか".indexOf(core.charAt(core.length() - 1)) >= 0) core = core.substring(0, core.length() - 1);
        if (!core.isEmpty() && "よねか".indexOf(core.charAt(core.length() - 1)) >= 0) core = core.substring(0, core.length() - 1);  // "ますよね"
        for (String e : POLITE_ENDS) if (core.endsWith(e)) return true;
        return false;
    }

    private static boolean startsWithContinuation(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        String rest = text.substring(i);
        for (String c : CONTINUATIONS) {
            if (rest.startsWith(c)) {
                // a particle only if followed by a delimiter or space (so "から揚げ" or "ので" inside a word is not mis-read)
                int end = c.length();
                if (end >= rest.length()) return true;
                char next = rest.charAt(end);
                if (Character.isWhitespace(next) || "、。！？".indexOf(next) >= 0) return true;
            }
        }
        return false;
    }
}
