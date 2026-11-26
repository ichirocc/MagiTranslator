package nie.translator.rtranslator.jane;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terminology for ne -> ja, the mirror of {@link Glossary}: a Nepali term in the source is replaced by
 * its Japanese equivalent ("code-switched" input, which the NMT copies), and the Japanese term becomes
 * a required term of the translation.
 *
 * Nepali writes postpositions and the plural attached to the noun (अस्पतालमा "at the hospital",
 * साथीहरूलाई "to the friends"), so a term also matches when followed by such an ending; the ending is
 * kept after the Japanese term (病院मा) so that the model still sees the case.
 * A term never matches inside a longer word (कर "tax" does not match करोड).
 */
public final class NeJaGlossary {
    private final Map<String, String> terms = new LinkedHashMap<>();

    // Devanagari letters and signs, without the danda । ॥ (sentence punctuation)
    private static final String NE_LETTER = "\\u0900-\\u0963\\u0966-\\u097F";
    // endings written attached to a noun: optional plural, then one postposition / particle
    private static final String ENDING = "(?:हरू|हरु)?(?:मा|को|का|की|लाई|ले|बाट|सँग|संग|देखि|सम्म|भन्दा|तिर|मै|नै|लगायत)?";

    // Speed: keys sorted longest first and one compiled pattern per key, built once instead of on every
    // inject call; put() invalidates them.
    private List<String> sortedKeys;
    private final Map<String, Pattern> patterns = new java.util.HashMap<>();
    private final Map<String, String[]> words = new java.util.HashMap<>();

    public synchronized void put(String ne, String ja) {
        if (ne == null || ja == null) return;
        String k = Normalizer.normalize(ne.trim(), Normalizer.Form.NFC);
        String v = ja.trim();
        if (!k.isEmpty() && !v.isEmpty()) {
            terms.put(k, v);
            sortedKeys = null;
            patterns.clear();
            words.clear();
        }
    }

    private synchronized List<String> sortedKeys() {
        if (sortedKeys == null) {
            List<String> keys = new ArrayList<>(terms.keySet());
            keys.sort(Comparator.comparingInt(String::length).reversed());   //stable sort: same order as before
            sortedKeys = Collections.unmodifiableList(keys);
        }
        return sortedKeys;
    }

    private synchronized Pattern pattern(String k) {
        Pattern p = patterns.get(k);
        if (p == null) {
            String w = Pattern.quote(k).replace(" ", "\\E\\s+\\Q");
            p = Pattern.compile("(?<![" + NE_LETTER + "])" + w + "(" + ENDING + ")(?![" + NE_LETTER + "])");
            patterns.put(k, p);
        }
        return p;
    }

    /** The pattern can only match when every word of the term occurs in the text (cheap pre-test). */
    private synchronized boolean mayMatch(String k, String t) {
        String[] ws = words.get(k);
        if (ws == null) { ws = k.split(" "); words.put(k, ws); }
        for (String w : ws) if (!w.isEmpty() && t.indexOf(w) < 0) return false;
        return true;
    }

    public int size() { return terms.size(); }

    public Set<String> keys() { return Collections.unmodifiableSet(terms.keySet()); }

    /** Reads "ne<TAB>ja" lines; lines starting with # are comments. */
    public void load(Reader reader) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] cols = line.split("\t");
            if (cols.length >= 2) put(cols[0], cols[1]);
        }
    }

    public Glossary.Injection inject(String ne) {
        String t = Normalizer.normalize(ne == null ? "" : ne, Normalizer.Form.NFC);
        List<String> required = new ArrayList<>();
        // longest first: नेपाली दूतावास before दूतावास, विश्वविद्यालय is a different word from विद्यालय
        for (String k : sortedKeys()) {
            if (!mayMatch(k, t)) continue;
            Matcher m = pattern(k).matcher(t);
            if (!m.find()) continue;
            String v = terms.get(k);
            StringBuffer sb = new StringBuffer();
            m.reset();
            while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(v + m.group(1)));
            m.appendTail(sb);
            t = sb.toString();
            if (!required.contains(v)) required.add(v);
        }
        return new Glossary.Injection(t, required, Collections.emptyList());
    }
}
