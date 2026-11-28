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
        if (ambiguousNe.contains(kn)) return;
        //a ja>ne line is not read back itself, but it still counts as a second Japanese reading of this Nepali:
        //either order (one-way line first or last), the Nepali is then ambiguous and not used for ne -> ja
        String other = neJa.containsKey(kn) ? keyJa(neJa.get(kn)) : oneWayJa.get(kn);
        if (other != null && !other.equals(kj)) {
            neJa.remove(kn);
            oneWayJa.remove(kn);
            ambiguousNe.add(kn);
            return;
        }
        if (jaToNeOnly) oneWayJa.putIfAbsent(kn, kj);
        else neJa.putIfAbsent(kn, ja.trim());
    }

    /** Nepali key -> Japanese key of ja>ne lines, kept only to detect ambiguity */
    private final Map<String, String> oneWayJa = new HashMap<>();

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
        // spelling variants Nepali writers mix freely: candrabindu/anusvara, long/short i and u, ZWJ/ZWNJ
        String t = Normalizer.normalize(s, Normalizer.Form.NFC).replace('|', '।').replace('ँ', 'ं')
                .replace('ी', 'ि').replace('ू', 'ु').replace('ई', 'इ').replace('ऊ', 'उ')
                .replace("\u200C", "").replace("\u200D", "");
        t = SPACES.matcher(t).replaceAll(" ").trim();
        return NE_END_PUNCT.matcher(t).replaceAll("");
    }
}
