package nie.translator.rtranslator.jane;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

/** Deterministic clean-up of Nepali MT output. */
public final class NePostEditor {
    private NePostEditor() {}

    public static String edit(String ne) {
        if (ne == null) return "";
        String t = Normalizer.normalize(ne, Normalizer.Form.NFC);
        t = t.replace('|', '।');
        t = periodToDanda(t);
        t = SPACE_BEFORE_PUNCT.matcher(t).replaceAll("$1");             // no space before punctuation
        t = DANDAS.matcher(t).replaceAll("।");
        t = collapseRepeats(SPACES.split(t.trim()));
        return t.trim();
    }

    private static final java.util.regex.Pattern SPACE_BEFORE_PUNCT = java.util.regex.Pattern.compile("\\s+([।?!,])");
    private static final java.util.regex.Pattern DANDAS = java.util.regex.Pattern.compile("।{2,}");
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s+");

    // Nepali abbreviations written with a Latin period: रु. 100 (Rs.), डा. (Dr.), प्रा. (Prof.), नं. (No.),
    // वि.सं. / ई.सं. (calendar eras), कि.मि. (km) ... Turning their period into a danda split the sentence.
    private static final java.util.Set<String> ABBREVIATIONS = new java.util.HashSet<>(java.util.Arrays.asList(
            "रु", "डा", "प्रा", "नं", "सं", "वि", "ई", "कि", "मि", "से", "पृ", "जि", "गा", "पा", "न", "स", "प्र", "उ", "अ", "क्र", "इ", "श्री"));
    // a Devanagari word (letters and signs, no digits, no danda) directly before a Latin period
    private static final java.util.regex.Pattern WORD_PERIOD = java.util.regex.Pattern.compile("([\\u0900-\\u0963\\u0970-\\u097F]+)\\s*\\.(?=\\s|$)");

    /** Latin period -> danda after a Devanagari word, except after an abbreviation or a digit (१. list item). */
    static String periodToDanda(String t) {
        java.util.regex.Matcher m = WORD_PERIOD.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String w = m.group(1);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(ABBREVIATIONS.contains(w) ? w + "." : w + "।"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Low-resource NMT loops ("घर घर घर घर"). Nepali also has meaningful reduplication
     * (धेरै धेरै, बिस्तारै बिस्तारै, घर घर), so two repeats are always kept:
     *  - a single word repeated 3+ times is reduced to 2 (keeps the reduplicated form);
     *  - a 2-3 word phrase repeated 3+ times is reduced to 1;
     *  - a 2-3 word phrase repeated exactly twice is kept (could be intentional).
     */
    static String collapseRepeats(String[] words) {
        List<String> in = new ArrayList<>();
        for (String w : words) if (!w.isEmpty()) in.add(w);
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int n = 3; n >= 1; n--) {
                for (int i = 0; i + 3 * n <= in.size(); i++) {
                    int reps = 1;
                    while (i + (reps + 1) * n <= in.size() && same(in, i, i + reps * n, n)) reps++;
                    if (reps >= 3) {
                        int keep = (n == 1) ? 2 : 1;
                        for (int r = reps; r > keep; r--) {
                            for (int k = 0; k < n; k++) in.remove(i + keep * n);
                        }
                        changed = true;
                        break outer;
                    }
                }
            }
        }
        return String.join(" ", in);
    }

    private static boolean same(List<String> l, int a, int b, int n) {
        for (int k = 0; k < n; k++) if (!l.get(a + k).equals(l.get(b + k))) return false;
        return true;
    }
}
