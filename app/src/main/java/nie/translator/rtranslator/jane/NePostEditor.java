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
        t = t.replaceAll("(?<=[\\u0900-\\u097F])\\s*\\.(?=\\s|$)", "।");   // Latin period -> danda
        t = t.replaceAll("\\s+([।?!,])", "$1");                         // no space before punctuation
        t = t.replaceAll("।{2,}", "।");
        t = collapseRepeats(t.trim().split("\\s+"));
        return t.trim();
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
