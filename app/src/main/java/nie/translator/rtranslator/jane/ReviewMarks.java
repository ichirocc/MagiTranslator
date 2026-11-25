package nie.translator.rtranslator.jane;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translations that the library returned with a hard problem still present (spec v2 part 5, abstention):
 * the UI shows them with a "要確認" marker. Kept in memory only, bounded, never logged or persisted.
 * Keyed by the exact output text because the result reaches the UI through the existing listener API.
 */
public final class ReviewMarks {
    private static final int MAX = 64;
    private static final Map<String, Boolean> MARKS = new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) { return size() > MAX; }
    };

    private ReviewMarks() {}

    /** Records the outcome of one library translation (unmarks a text that is now fine). */
    public static synchronized void record(String output, boolean needsReview) {
        if (output == null || output.isEmpty()) return;
        if (needsReview) MARKS.put(output, Boolean.TRUE); else MARKS.remove(output);
    }

    public static synchronized boolean isMarked(String output) {
        return output != null && MARKS.containsKey(output);
    }

    /** Called with the conversation memory reset, so marks never outlive a conversation. */
    public static synchronized void clear() { MARKS.clear(); }

    static synchronized int size() { return MARKS.size(); }
}
