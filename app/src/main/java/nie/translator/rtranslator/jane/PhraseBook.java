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
 * Japanese phrases is not used for ne -> ja either. Lines marked "ne>ja" are used only for ne -> ja.
 * A book can have a fallback (the built-in phrases): it is asked only for sentences this book does not
 * know at all, so the user's own entries always win and never become ambiguous because of it.
 */
public final class PhraseBook {
    private final Map<String, String> jaNe = new HashMap<>();
    private final Map<String, String> neJa = new HashMap<>();
    private final Set<String> ambiguousNe = new HashSet<>();

    private PhraseBook fallback;

    /** Sets the book asked for sentences this one has no entry for (a book is not its own fallback). */
    public PhraseBook withFallback(PhraseBook f) {
        if (f == this) throw new IllegalArgumentException("a phrase book cannot fall back to itself");
        fallback = f;
        return this;
    }

    public void add(String ja, String ne, boolean jaToNeOnly) { add(ja, ne, jaToNeOnly, false); }

    public void add(String ja, String ne, boolean jaToNeOnly, boolean neToJaOnly) {
        if (ja == null || ne == null || (jaToNeOnly && neToJaOnly)) return;
        String kj = keyJa(ja), kn = keyNe(ne);
        if (kj.isEmpty() || kn.isEmpty()) return;
        synchronized (pairs) { pairs.add(new Object[]{ja.trim(), ne.trim(), jaToNeOnly, neToJaOnly}); }
        examples = null;
        jaShare.clear();
        if (!neToJaOnly) jaNe.putIfAbsent(kj, ne.trim());
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

    /** every accepted line as written: {ja, ne, jaToNeOnly, neToJaOnly}, for the example bank */
    private final java.util.List<Object[]> pairs = new java.util.ArrayList<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Double> jaShare = new java.util.concurrent.ConcurrentHashMap<>();

    /** Share of the phrase-book lines whose Japanese contains {@code term} (0..1); cached, cleared by add(). */
    public double jaShare(String term) {
        if (term == null || term.isEmpty() || pairs.isEmpty()) return 0;
        return jaShare.computeIfAbsent(term, t -> {
            int n = 0;
            synchronized (pairs) { for (Object[] p : pairs) if (((String) p[0]).contains(t)) n++; return n / (double) pairs.size(); }
        });
    }
    private volatile ExampleBank examples;

    /** This book's lines (then the fallback's) as an example bank, built on first use. */
    public ExampleBank examples() {
        ExampleBank b = examples;
        if (b != null) return b;
        b = new ExampleBank();
        for (PhraseBook p = this; p != null; p = p.fallback)
            for (Object[] e : p.pairs) b.add((String) e[0], (String) e[1], (Boolean) e[2], (Boolean) e[3]);
        examples = b;
        return b;
    }

    public String lookupJaNe(String ja) {
        if (ja == null) return null;
        String v = jaNe.get(keyJa(ja));
        return v != null || fallback == null ? v : fallback.lookupJaNe(ja);
    }

    public String lookupNeJa(String ne) {
        if (ne == null) return null;
        String kn = keyNe(ne);
        String v = neJa.get(kn);
        // a Nepali this book knows but does not read back (ambiguous or ja>ne) is not handed to the fallback
        if (v != null || fallback == null || ambiguousNe.contains(kn) || oneWayJa.containsKey(kn)) return v;
        return fallback.lookupNeJa(ne);
    }

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
