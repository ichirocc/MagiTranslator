package nie.translator.rtranslator.jane;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-conversation memory of term / name renderings (SPEC §7.6): once a Japanese term or proper name
 * has been rendered in Nepali, later sentences of the same conversation reuse that rendering
 * (injected and required like a glossary entry). Bounded LRU, in memory only: nothing is written to
 * disk (names are personal data, SPEC §18). Call {@link #clear()} when the conversation ends.
 */
public final class TranslationMemory {
    public static final int DEFAULT_CAPACITY = 200;
    private final int capacity;
    private final LinkedHashMap<String, String> map;

    public TranslationMemory() { this(DEFAULT_CAPACITY); }

    public TranslationMemory(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.map = new LinkedHashMap<String, String>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > TranslationMemory.this.capacity; }
        };
    }

    public synchronized void remember(String ja, String ne) {
        if (ja == null || ne == null) return;
        String k = ja.trim(), v = ne.trim();
        if (k.isEmpty() || v.isEmpty()) return;
        map.put(k, v);
    }

    /** Rendering chosen earlier, or null. Counts as a use for LRU. */
    public synchronized String lookup(String ja) { return ja == null ? null : map.get(ja.trim()); }   //same key as remember()

    public synchronized int size() { return map.size(); }

    public int capacity() { return capacity; }

    public synchronized void clear() { map.clear(); }

    /** Copy of the entries (does not change LRU order). */
    public synchronized Map<String, String> snapshot() { return Collections.unmodifiableMap(new LinkedHashMap<>(map)); }
}
