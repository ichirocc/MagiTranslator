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

    /** Low-resource NMT loops ("घर घर घर"); remove immediately repeated 1-3 word n-grams. */
    static String collapseRepeats(String[] words) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            out.add(w);
            for (int n = 1; n <= 3; n++) {
                int sz = out.size();
                if (sz < 2 * n) continue;
                boolean same = true;
                for (int k = 0; k < n; k++) {
                    if (!out.get(sz - n + k).equals(out.get(sz - 2 * n + k))) { same = false; break; }
                }
                if (same) {
                    for (int k = 0; k < n; k++) out.remove(out.size() - 1);
                    break;
                }
            }
        }
        return String.join(" ", out);
    }
}
