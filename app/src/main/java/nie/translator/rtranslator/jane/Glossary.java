package nie.translator.rtranslator.jane;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Terminology for ja -> ne. Terms are injected inline in the source as target-language text
 * ("code-switched" input), which NMT copies far more reliably than it guesses a rare term
 * (target lemma annotation: Exel et al. 2020, Bergmanis & Pinnis 2021).
 * Person names with an honorific (田中さん is kanji, タナカさん katakana) are transliterated.
 */
public final class Glossary {
    private final Map<String, String> terms = new LinkedHashMap<>();
    private static final String COMPOUND_SUFFIX = "語人製産風式的";
    // a katakana name, possibly with middle dots (ラム・バハドゥル), followed by an honorific
    static final Pattern KATAKANA_NAME = Pattern.compile("([\\u30A1-\\u30FAー][\\u30A1-\\u30FBー]*[\\u30A1-\\u30FAー])(さん|様|さま|くん|君|ちゃん|氏|先生)");

    /** Only facts that are certain; users extend it with a TSV file (ja<TAB>ne per line). */
    public static Glossary withDefaults() {
        Glossary g = new Glossary();
        g.put("日本", "जापान");
        g.put("日本語", "जापानी भाषा");
        g.put("日本人", "जापानी");
        g.put("ネパール", "नेपाल");
        g.put("ネパール語", "नेपाली भाषा");
        g.put("ネパール人", "नेपाली");
        g.put("東京", "टोकियो");
        g.put("大阪", "ओसाका");
        g.put("カトマンズ", "काठमाडौं");
        g.put("ポカラ", "पोखरा");
        return g;
    }

    public void put(String ja, String ne) {
        if (ja != null && ne != null && !ja.trim().isEmpty() && !ne.trim().isEmpty()) terms.put(ja.trim(), ne.trim());
    }

    public int size() { return terms.size(); }

    // ---- user dictionary terms (UserDictionary): PREFERRED only, never required ----
    // Measured on the seed set, required/injected terms helped ne->ja but hurt ja->ne, and user data is
    // not verified: these terms only raise the score of candidates that use them.
    private final Map<String, String> userTerms = new LinkedHashMap<>();
    private final Map<String, String> userReverse = new LinkedHashMap<>();

    public void putUser(String ja, String ne) {
        if (ja != null && ne != null && !ja.trim().isEmpty() && !ne.trim().isEmpty()) userTerms.put(ja.trim(), ne.trim());
    }

    public void putUserReverse(String ne, String ja) {
        if (ja != null && ne != null && !ja.trim().isEmpty() && !ne.trim().isEmpty()) userReverse.put(ne.trim(), ja.trim());
    }

    public int userSize() { return userTerms.size() + userReverse.size(); }

    /**
     * Nepali renderings of user terms found in a Japanese source: longest first, a matched span is not
     * reused by a shorter term, and a term followed by a derivational suffix is skipped (日本語 is not 日本).
     */
    public List<String> userPreferred(String ja) {
        List<String> out = new ArrayList<>();
        if (ja == null || userTerms.isEmpty()) return out;
        StringBuilder work = new StringBuilder(ja);
        List<String> keys = new ArrayList<>(userTerms.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        for (String k : keys) {
            if (terms.containsKey(k)) continue;   // built-in glossary entries are handled by inject()
            Matcher m = Pattern.compile(Pattern.quote(k) + "(?![" + COMPOUND_SUFFIX + "])").matcher(work);
            if (!m.find()) continue;
            String v = userTerms.get(k);
            if (!out.contains(v)) out.add(v);
            do { for (int i = m.start(); i < m.end(); i++) work.setCharAt(i, '\u0000'); } while (m.find());
        }
        return out;
    }

    /**
     * ne -> ja user terms: annotates the Nepali source with the Japanese term (like injectReverse) but
     * returns the terms as preferred, not required. Longest Nepali first, word-bounded.
     */
    public Injection injectReverseUser(String ne) {
        List<String> preferred = new ArrayList<>();
        String t = ne;
        if (ne == null || userReverse.isEmpty()) return new Injection(ne == null ? "" : ne, new ArrayList<>(), preferred);
        List<String> keys = new ArrayList<>(userReverse.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        for (String neTerm : keys) {
            String jaTerm = userReverse.get(neTerm);
            StringBuilder rx = new StringBuilder("(?<![\\u0900-\\u0963\\u0966-\\u097F])");
            for (char c : neTerm.toCharArray()) {
                if (c == '\u0901' || c == '\u0902') rx.append("[\\u0901\\u0902]");
                else rx.append(Pattern.quote(String.valueOf(c)));
            }
            rx.append("(?=$|[^\\u0900-\\u0963\\u0966-\\u097F]|").append(NE_CASE_MARKERS).append(")");
            Matcher m = Pattern.compile(rx.toString()).matcher(t);
            if (!m.find()) continue;
            t = m.replaceAll(Matcher.quoteReplacement(jaTerm));
            if (!preferred.contains(jaTerm)) preferred.add(jaTerm);
        }
        return new Injection(t, new ArrayList<>(), preferred);
    }

    /** Reads "ja<TAB>ne" lines; lines starting with # are comments. */
    public void load(Reader reader) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("\uFEFF")) line = line.substring(1);   // UTF-8 BOM (Windows Notepad)
            if (line.startsWith("#")) continue;
            String[] cols = line.split("\t");
            if (cols.length >= 2) put(cols[0], cols[1]);
        }
    }

    /**
     * Result of injecting terminology: the rewritten source, the target terms that must appear (glossary
     * entries: a missing one is a hard error) and the preferred ones (names transliterated by rule).
     * Rule-based kana -> Devanagari is lossy for Nepali names written in katakana (バハドゥル -> बाहाडुरु,
     * the real spelling is बहादुर), so a candidate where the model restored the correct spelling must not
     * be rejected: rule-transliterated names only lower the score. Put the right spelling in the
     * glossary (バハドゥル<TAB>बहादुर) to make it required.
     */
    public static final class Injection {
        public final String text;
        public final List<String> requiredTargets;
        public final List<String> preferredTargets;
        /** ja source term/name -> target rendering actually injected (glossary, memory or rule) */
        public final Map<String, String> applied;
        Injection(String text, List<String> requiredTargets, List<String> preferredTargets) {
            this(text, requiredTargets, preferredTargets, new LinkedHashMap<>());
        }
        Injection(String text, List<String> requiredTargets, List<String> preferredTargets, Map<String, String> applied) {
            this.text = text; this.requiredTargets = requiredTargets; this.preferredTargets = preferredTargets; this.applied = applied;
        }
        public boolean changed(String original) { return !text.equals(original); }
    }

    private static final String NE_CASE_MARKERS = "(?:मा|बाट|को|का|की|ले|लाई|सँग|तिर|देखि|सम्म|हरू|भन्दा|मै)(?![\\u093E-\\u094D])";

    public Injection inject(String ja) { return inject(ja, null, true); }

    /**
     * ne -> ja: replaces known Nepali renderings in a Nepali source by the Japanese term (target lemma
     * annotation in the other direction) and returns the Japanese terms that must appear in the output.
     * Nepali case markers attach to the word (काठमाडौंमा = in Kathmandu), so a term matches at the start of a
     * word; candrabindu/anusvara spelling variants (ँ / ं) are treated as equal.
     */
    public Injection injectReverse(String ne) {
        List<String> required = new ArrayList<>();
        String t = ne;
        List<Map.Entry<String, String>> entries = new ArrayList<>(terms.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue().length(), a.getValue().length()));   // longest Nepali first
        for (Map.Entry<String, String> e : entries) {
            String neTerm = e.getValue(), jaTerm = e.getKey();
            if (neTerm.isEmpty() || !neTerm.chars().anyMatch(c -> c >= 0x0900 && c <= 0x097F)) continue;
            // demonyms are ambiguous in Nepali (नेपाली = Nepali person / language / adjective): not reversed
            if (jaTerm.endsWith("人")) continue;
            StringBuilder rx = new StringBuilder("(?<![\\u0900-\\u0963\\u0966-\\u097F])");
            for (char c : neTerm.toCharArray()) {
                if (c == '\u0901' || c == '\u0902') rx.append("[\\u0901\\u0902]");
                else rx.append(Pattern.quote(String.valueOf(c)));
            }
            // the term must end the word or be followed by a case marker / plural (काठमाडौंमा, नेपालबाट);
            // नेपाल must not match inside नेपाली
            rx.append("(?=$|[^\\u0900-\\u0963\\u0966-\\u097F]|").append(NE_CASE_MARKERS).append(")");
            Matcher m = Pattern.compile(rx.toString()).matcher(t);
            if (!m.find()) continue;
            t = m.replaceAll(Matcher.quoteReplacement(jaTerm));
            if (!required.contains(jaTerm)) required.add(jaTerm);
        }
        return new Injection(t, required, new ArrayList<>());
    }

    /** Whether {@code ja} is a known glossary entry. */
    public boolean contains(String ja) { return ja != null && terms.containsKey(ja); }

    /**
     * @param memory conversation renderings (TranslationMemory); used after the glossary (glossary wins)
     *               and required like glossary entries, so a term keeps the same rendering in a conversation
     * @param transliterateUnknown false leaves unknown katakana names untouched (ProperNameResolver decides)
     */
    public Injection inject(String ja, Map<String, String> memory, boolean transliterateUnknown) {
        Map<String, String> applied = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        List<String> preferred = new ArrayList<>();
        String t = ja;
        // names first: タナカさん -> तानाका さん (honorific left for the model to render as "जी"/"ज्यू")
        Matcher m = KATAKANA_NAME.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            boolean fromGlossary = terms.containsKey(name) || (memory != null && memory.containsKey(name));
            if (!fromGlossary && !transliterateUnknown) { m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue; }
            String dev = terms.containsKey(name) ? terms.get(name)
                    : fromGlossary ? memory.get(name) : KanaDevanagari.transliterate(name);
            if (dev == null) { m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue; }
            List<String> into = fromGlossary ? required : preferred;
            if (!into.contains(dev)) into.add(dev);
            applied.put(name, dev);
            m.appendReplacement(sb, Matcher.quoteReplacement(dev + m.group(2)));
        }
        m.appendTail(sb);
        t = sb.toString();
        // longest terms first so that "東京都" wins over "東京"
        Map<String, String> all = new LinkedHashMap<>();
        if (memory != null) all.putAll(memory);
        all.putAll(terms);   // glossary wins over memory
        List<String> keys = new ArrayList<>(all.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        for (String k : keys) {
            // a term directly followed by a derivational suffix is only the head of another word
            // (日本語 = Japanese language, not "Japan" + 語); such occurrences are left to the model
            Matcher tm = Pattern.compile(Pattern.quote(k) + "(?![" + COMPOUND_SUFFIX + "])").matcher(t);
            if (!tm.find()) continue;
            String v = all.get(k);
            t = tm.replaceAll(Matcher.quoteReplacement(v));
            if (!required.contains(v)) required.add(v);
            applied.put(k, v);
        }
        preferred.removeAll(required);
        return new Injection(t, required, preferred, applied);
    }
}
