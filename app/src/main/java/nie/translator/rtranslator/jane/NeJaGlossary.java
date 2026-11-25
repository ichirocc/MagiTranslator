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

    public void put(String ne, String ja) {
        if (ne == null || ja == null) return;
        String k = Normalizer.normalize(ne.trim(), Normalizer.Form.NFC);
        String v = ja.trim();
        if (!k.isEmpty() && !v.isEmpty()) terms.put(k, v);
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
        List<String> keys = new ArrayList<>(terms.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        for (String k : keys) {
            String words = Pattern.quote(k).replace(" ", "\\E\\s+\\Q");
            Pattern p = Pattern.compile("(?<![" + NE_LETTER + "])" + words + "(" + ENDING + ")(?![" + NE_LETTER + "])");
            Matcher m = p.matcher(t);
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
