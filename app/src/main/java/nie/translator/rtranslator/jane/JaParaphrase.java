package nie.translator.rtranslator.jane;

import java.util.ArrayList;
import java.util.List;

/**
 * Meaning-preserving rewrites of a Japanese source, used only in the extra round when every candidate
 * still has a hard problem. Each rule removes an ambiguity the model resolved wrongly on the seed set.
 */
public final class JaParaphrase {
    private JaParaphrase() {}

    /**
     * 何時 is read both なんじ (what time) and いつ (when); the model chose いつ (jn19 -> कहिले). The kana
     * reading keeps "what time". 何時間 (how many hours) and 何時も (always) are left alone.
     */
    public static String paraphrase(String ja) {
        if (ja == null) return "";
        return ja.replaceAll("何時(?![間代もかで])", "なんじ");
    }

    /** Clauses split at 、 (each at least 2 characters), or the whole text when it does not split. */
    public static List<String> clauses(String ja) {
        List<String> out = new ArrayList<>();
        if (ja == null) return out;
        String[] parts = ja.split("(?<=、)");
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (!out.isEmpty() && t.replace("、", "").length() < 2) { out.set(out.size() - 1, out.get(out.size() - 1) + t); continue; }
            out.add(t);
        }
        if (out.size() > 1 && out.get(0).replace("、", "").length() < 2) { out.set(1, out.get(0) + out.get(1)); out.remove(0); }
        return out;
    }
}
