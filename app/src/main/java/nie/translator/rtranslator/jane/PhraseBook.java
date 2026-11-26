package nie.translator.rtranslator.jane;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Whole-sentence phrases from a user dictionary. A hit only adds a candidate: it is validated and
 * ranked like any model output, never returned unconditionally. Lines marked "ja>ne" are not used for
 * ne -> ja (the Nepali is ambiguous when read back). A Nepali sentence that maps to two different
 * Japanese phrases is not used for ne -> ja either.
 */
public final class PhraseBook {
    private final Map<String, String> jaNe = new HashMap<>();
    private final Map<String, String> neJa = new HashMap<>();
    private final Set<String> ambiguousNe = new HashSet<>();

    public void add(String ja, String ne, boolean jaToNeOnly) {
        if (ja == null || ne == null) return;
        String kj = keyJa(ja), kn = keyNe(ne);
        if (kj.isEmpty() || kn.isEmpty()) return;
        jaNe.putIfAbsent(kj, ne.trim());
        if (jaToNeOnly || ambiguousNe.contains(kn)) return;
        String prev = neJa.get(kn);
        if (prev == null) neJa.put(kn, ja.trim());
        else if (!keyJa(prev).equals(kj)) { neJa.remove(kn); ambiguousNe.add(kn); }
    }

    public int size() { return jaNe.size(); }

    public String lookupJaNe(String ja) { return ja == null ? null : jaNe.get(keyJa(ja)); }

    public String lookupNeJa(String ne) { return ne == null ? null : neJa.get(keyNe(ne)); }

    // compiled once (keys are built for every lookup and every loaded line)
    private static final java.util.regex.Pattern JA_SPACES = java.util.regex.Pattern.compile("[\\s　]+");
    private static final java.util.regex.Pattern JA_END_PUNCT = java.util.regex.Pattern.compile("[。．.！!？?、,…]+$");
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s+");
    private static final java.util.regex.Pattern NE_END_PUNCT = java.util.regex.Pattern.compile("[\\s।॥.?!,]+$");

    static String keyJa(String s) {
        String t = JaNormalizer.normalize(s);
        t = JA_SPACES.matcher(t).replaceAll("");
        return JA_END_PUNCT.matcher(t).replaceAll("");
    }

    static String keyNe(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFC).replace('|', '।').replace('ँ', 'ं');
        t = SPACES.matcher(t).replaceAll(" ").trim();
        return NE_END_PUNCT.matcher(t).replaceAll("");
    }
}
