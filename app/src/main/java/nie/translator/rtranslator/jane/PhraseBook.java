package nie.translator.rtranslator.jane;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/**
 * Fixed everyday phrases (greetings, thanks, apologies, simple questions) translated by lookup instead
 * of the NMT model: short set phrases are where a general model is least reliable, and a person in a
 * face-to-face conversation says them all the time.
 *
 * A phrase matches only when the whole utterance (or the whole sentence, for the sentence-by-sentence
 * candidate) is the phrase, ignoring punctuation and spaces; it never replaces part of a sentence.
 * File format: japanese<TAB>nepali[<TAB>ja>ne]. Several Japanese phrases may share a Nepali translation;
 * for ne -> ja the first line wins, so the most neutral Japanese is listed first. A third column
 * "ja>ne" limits a line to that direction (the Nepali is ambiguous when read back, e.g. केही छैन is
 * "you're welcome" but also "there is nothing").
 */
public final class PhraseBook {
    private final Map<String, String> jaToNe = new HashMap<>();
    private final Map<String, String> neToJa = new HashMap<>();

    public void put(String ja, String ne) {
        put(ja, ne, true);
    }

    /** @param bothDirections false = only Japanese -> Nepali */
    public void put(String ja, String ne, boolean bothDirections) {
        if (ja == null || ne == null || ja.trim().isEmpty() || ne.trim().isEmpty()) return;
        String jk = keyJa(ja), nk = keyNe(ne);
        if (jk.isEmpty() || nk.isEmpty()) return;
        jaToNe.putIfAbsent(jk, ne.trim());
        if (bothDirections) neToJa.putIfAbsent(nk, ja.trim());
    }

    public int size() { return jaToNe.size(); }

    /** Reads "ja<TAB>ne" lines; lines starting with # are comments. */
    public void load(Reader reader) throws IOException {
        BufferedReader br = new BufferedReader(reader);
        String line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] cols = line.split("\t");
            if (cols.length >= 2) put(cols[0], cols[1], cols.length < 3 || !"ja>ne".equals(cols[2].trim()));
        }
    }

    /** Nepali translation of a whole Japanese utterance, or null. */
    public String lookupJaToNe(String ja) {
        return ja == null ? null : jaToNe.get(keyJa(ja));
    }

    /** Japanese translation of a whole Nepali utterance, or null. */
    public String lookupNeToJa(String ne) {
        return ne == null ? null : neToJa.get(keyNe(ne));
    }

    /** Lookup in the given direction ("ja"->"ne" or "ne"->"ja"); null for other pairs or no match. */
    public String lookup(String text, String src, String tgt) {
        if ("ja".equals(src) && "ne".equals(tgt)) return lookupJaToNe(text);
        if ("ne".equals(src) && "ja".equals(tgt)) return lookupNeToJa(text);
        return null;
    }

    // Japanese: same normalisation as the pipeline input, then no punctuation and no spaces at all
    // (speech recognition output has none, typed text may have some)
    static String keyJa(String s) {
        String t = JaNormalizer.normalize(s);
        return t.replaceAll("[\\s、。，．,.！？!?…「」『』（）()]", "");
    }

    // Nepali: NFC, danda/punctuation removed, spaces collapsed
    static String keyNe(String s) {
        String t = Normalizer.normalize(s, Normalizer.Form.NFC).replace('|', '।');
        t = t.replaceAll("[।॥,.!?？！‘’'\"“”]", " ");
        return t.replaceAll("\\s+", " ").trim();
    }
}
