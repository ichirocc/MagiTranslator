package nie.translator.rtranslator.jane;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Unknown proper names (SPEC §10.2). Known names (Glossary, TranslationMemory) are injected by the
 * Glossary and take priority; this class only handles the rest. The rendering of an unknown katakana
 * name with an honorific is not decided by rule: source variants are produced and translated as
 * separate candidates, and the lexicographic rerank (hard issues, then score) picks one.
 *  - TRANSLITERATE: rule transliteration injected (KanaDevanagari), a preferred (not required) target
 *  - KEEP: katakana left for the model
 *  - NOUN: honorific dropped and nothing injected, so the model may treat the word as a common noun
 */
public final class ProperNameResolver {
    public enum Mode { TRANSLITERATE, KEEP, NOUN }

    public static final class Variant {
        public final Mode mode;
        public final String text;
        /** unknown name -> rendering injected (only for TRANSLITERATE) */
        public final Map<String, String> names;
        Variant(Mode mode, String text, Map<String, String> names) { this.mode = mode; this.text = text; this.names = names; }
        @Override public String toString() { return mode + ":" + text; }
    }

    private ProperNameResolver() {}

    /** Unknown katakana names (with honorific) in {@code ja}, in order of appearance, without duplicates. */
    public static List<String> unknownNames(String ja, Glossary glossary, Map<String, String> memory) {
        List<String> out = new ArrayList<>();
        Matcher m = Glossary.KATAKANA_NAME.matcher(ja);
        while (m.find()) {
            String n = m.group(1);
            if (glossary != null && glossary.contains(n)) continue;
            if (memory != null && memory.containsKey(n)) continue;
            if (!out.contains(n)) out.add(n);
        }
        return out;
    }

    /**
     * Variants of {@code ja} (already glossary/memory-injected; unknown names still in katakana), in the
     * order TRANSLITERATE, KEEP, NOUN. Empty when there is no unknown name.
     */
    public static List<Variant> variants(String ja, Glossary glossary, Map<String, String> memory) {
        List<Variant> out = new ArrayList<>();
        List<String> unknown = unknownNames(ja, glossary, memory);
        if (unknown.isEmpty()) return out;
        Map<String, String> names = new LinkedHashMap<>();
        StringBuffer tr = new StringBuffer();
        Matcher m = Glossary.KATAKANA_NAME.matcher(ja);
        while (m.find()) {
            String n = m.group(1);
            String dev = unknown.contains(n) ? KanaDevanagari.transliterate(n) : null;
            if (dev != null) names.put(n, dev);
            m.appendReplacement(tr, Matcher.quoteReplacement(dev == null ? m.group() : dev + m.group(2)));
        }
        m.appendTail(tr);
        StringBuffer noun = new StringBuffer();
        m = Glossary.KATAKANA_NAME.matcher(ja);
        while (m.find()) m.appendReplacement(noun, Matcher.quoteReplacement(unknown.contains(m.group(1)) ? m.group(1) : m.group()));
        m.appendTail(noun);
        if (!names.isEmpty()) out.add(new Variant(Mode.TRANSLITERATE, tr.toString(), names));
        out.add(new Variant(Mode.KEEP, ja, new LinkedHashMap<>()));
        if (!noun.toString().equals(ja)) out.add(new Variant(Mode.NOUN, noun.toString(), new LinkedHashMap<>()));
        return out;
    }
}
