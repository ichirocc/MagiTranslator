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
    private static final Pattern KATAKANA_NAME = Pattern.compile("([\\u30A1-\\u30FAー][\\u30A1-\\u30FBー]*[\\u30A1-\\u30FAー])(さん|様|さま|くん|君|ちゃん|氏|先生)");

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

    /** Japanese keys (read-only view), e.g. to check a dictionary for overlapping entries. */
    public java.util.Set<String> keys() { return java.util.Collections.unmodifiableSet(terms.keySet()); }

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
        Injection(String text, List<String> requiredTargets, List<String> preferredTargets) {
            this.text = text; this.requiredTargets = requiredTargets; this.preferredTargets = preferredTargets;
        }
        public boolean changed(String original) { return !text.equals(original); }
    }

    public Injection inject(String ja) {
        List<String> required = new ArrayList<>();
        List<String> preferred = new ArrayList<>();
        String t = ja;
        // names first: タナカさん -> तानाका さん (honorific left for the model to render as "जी"/"ज्यू")
        Matcher m = KATAKANA_NAME.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            boolean fromGlossary = terms.containsKey(name);
            String dev = fromGlossary ? terms.get(name) : KanaDevanagari.transliterate(name);
            if (dev == null) { m.appendReplacement(sb, Matcher.quoteReplacement(m.group())); continue; }
            List<String> into = fromGlossary ? required : preferred;
            if (!into.contains(dev)) into.add(dev);
            m.appendReplacement(sb, Matcher.quoteReplacement(dev + m.group(2)));
        }
        m.appendTail(sb);
        t = sb.toString();
        // longest terms first so that "東京都" wins over "東京"
        List<String> keys = new ArrayList<>(terms.keySet());
        keys.sort(Comparator.comparingInt(String::length).reversed());
        for (String k : keys) {
            // a term directly followed by a derivational suffix is only the head of another word
            // (日本語 = Japanese language, not "Japan" + 語); such occurrences are left to the model
            Matcher tm = Pattern.compile(Pattern.quote(k) + "(?![" + COMPOUND_SUFFIX + "])").matcher(t);
            if (!tm.find()) continue;
            String v = terms.get(k);
            t = tm.replaceAll(Matcher.quoteReplacement(v));
            if (!required.contains(v)) required.add(v);
        }
        preferred.removeAll(required);
        return new Injection(t, required, preferred);
    }
}
