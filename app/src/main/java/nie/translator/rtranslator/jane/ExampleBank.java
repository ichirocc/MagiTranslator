package nie.translator.rtranslator.jane;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translated example sentences (the phrase books) looked up by similarity: the closest one or two are given
 * to PLaMo as previous turns, so a sentence the phrase book does not hold word for word is still translated
 * with the wording and terms of a close one (retrieval-augmented few-shot, as a translation memory does).
 * <p>
 * Lookup is a character-bigram inverted index scored with the Dice coefficient: no model call, a few
 * microseconds per sentence for thousands of entries. An example identical to the input is never returned
 * (the phrase book answers that one itself).
 */
public final class ExampleBank {
    /** one example: {source, target} in the direction it was added for */
    private final List<String[]> jaNe = new ArrayList<>(), neJa = new ArrayList<>();
    private final Map<String, List<Integer>> jaIndex = new HashMap<>(), neIndex = new HashMap<>();
    private final List<Integer> jaSize = new ArrayList<>(), neSize = new ArrayList<>();

    public int size() { return jaNe.size(); }

    /** Adds a pair for both directions, or for one (ja>ne / ne>ja lines of a phrase book). */
    public void add(String ja, String ne, boolean jaToNeOnly, boolean neToJaOnly) {
        if (ja == null || ne == null || ja.trim().isEmpty() || ne.trim().isEmpty()) return;
        if (!neToJaOnly) index(jaNe, jaIndex, jaSize, PhraseBook.keyJa(ja), new String[]{ja.trim(), ne.trim()});
        if (!jaToNeOnly) index(neJa, neIndex, neSize, PhraseBook.keyNe(ne), new String[]{ne.trim(), ja.trim()});
    }

    private static void index(List<String[]> list, Map<String, List<Integer>> idx, List<Integer> sizes, String key, String[] pair) {
        Set<String> grams = grams(key);
        if (grams.isEmpty()) return;
        int id = list.size();
        list.add(pair);
        sizes.add(grams.size());
        for (String g : grams) idx.computeIfAbsent(g, x -> new ArrayList<>()).add(id);
    }

    static Set<String> grams(String key) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i + 2 <= key.length(); i++) out.add(key.substring(i, i + 2));
        if (out.isEmpty() && !key.isEmpty()) out.add(key);
        return out;
    }

    /**
     * Up to {@code k} examples for {@code text} in direction {@code src} ("ja" or "ne"), most similar first,
     * each with a Dice similarity of at least {@code min}; identical sentences are skipped.
     */
    public List<String[]> similar(String text, String src, int k, double min) {
        boolean ja = "ja".equals(src);
        String key = ja ? PhraseBook.keyJa(text) : PhraseBook.keyNe(text);
        Set<String> q = grams(key);
        if (q.isEmpty() || k <= 0) return Collections.emptyList();
        List<String[]> list = ja ? jaNe : neJa;
        Map<String, List<Integer>> idx = ja ? jaIndex : neIndex;
        List<Integer> sizes = ja ? jaSize : neSize;
        Map<Integer, Integer> hits = new HashMap<>();
        for (String g : q) {
            List<Integer> ids = idx.get(g);
            if (ids == null || ids.size() > list.size() / 4 + 50) continue;   // a bigram in most entries (です) says nothing
            for (int id : ids) hits.merge(id, 1, Integer::sum);
        }
        List<double[]> scored = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : hits.entrySet()) {
            double dice = 2.0 * e.getValue() / (q.size() + sizes.get(e.getKey()));
            if (dice >= min) scored.add(new double[]{dice, e.getKey()});
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        List<String[]> out = new ArrayList<>();
        for (double[] s : scored) {
            String[] p = list.get((int) s[1]);
            String pk = ja ? PhraseBook.keyJa(p[0]) : PhraseBook.keyNe(p[0]);
            if (pk.equals(key)) continue;   // the input itself
            out.add(p);
            if (out.size() >= k) break;
        }
        return out;
    }
}
