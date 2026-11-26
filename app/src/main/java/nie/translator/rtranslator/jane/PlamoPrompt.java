package nie.translator.rtranslator.jane;

import java.util.List;
import java.util.regex.Pattern;

/**
 * PFML3 prompts for PLaMo 3 Translate, as documented on the model card ("PFML3 prompt format"):
 * {@code dataset=translation}, then the previous source/translation pairs, then the glossary immediately
 * before the current input, then an {@code output} element whose body the model writes.
 */
public final class PlamoPrompt {
    private PlamoPrompt() {}

    static final String TAG = "<|plamo:tag|>", MSG = "<|plamo:msg|>", KEY = "<|plamo:key|>", VAL = "<|plamo:val|>",
            INDEX = "<|plamo:index|>", ITEM = "<|plamo:item|>", BOS = "<|plamo:bos|>";
    /** the model ends a translation with one of these */
    public static final String[] STOPS = {TAG, BOS};

    /** A previous source text and its translation, in the direction of the current request. */
    public static final class Turn {
        public final String source, translation;
        public Turn(String source, String translation) { this.source = source; this.translation = translation; }
    }

    // a control marker typed or recognised in user text would break the format (or steer the model)
    private static final Pattern CONTROL = Pattern.compile("<\\|plamo:[a-z_]+\\|>");

    /** Removes PLaMo control markers from text that goes into a prompt. */
    public static String clean(String text) {
        return text == null ? "" : CONTROL.matcher(text).replaceAll("").trim();
    }

    /**
     * @param history previous turns, oldest first, already in the src -> tgt direction
     * @param terms glossary entries (source term -> target rendering)
     * @param polite Japanese output in polite style (form=polite, only for target "ja")
     */
    public static String build(String src, String tgt, String text, List<Turn> history, List<Glossary.Term> terms, boolean polite) {
        StringBuilder p = new StringBuilder(256);
        p.append(TAG).append("dataset").append(MSG).append("translation");
        if (history != null) for (Turn t : history) {
            input(p, src, t.source);
            output(p, tgt, false).append(clean(t.translation));
        }
        if (terms != null && !terms.isEmpty()) {
            p.append(TAG).append("glossary");
            for (Glossary.Term t : terms) p.append(INDEX).append(clean(t.source)).append(ITEM).append(clean(t.target));
        }
        input(p, src, text);
        output(p, tgt, polite && "ja".equals(tgt));
        return p.toString();
    }

    private static void input(StringBuilder p, String lang, String text) {
        p.append(TAG).append("input").append(KEY).append("lang").append(VAL).append(lang).append(MSG).append(clean(text));
    }

    private static StringBuilder output(StringBuilder p, String lang, boolean polite) {
        p.append(TAG).append("output").append(KEY).append("lang").append(VAL).append(lang);
        if (polite) p.append(KEY).append("form").append(VAL).append("polite");
        return p.append(MSG);
    }
}
