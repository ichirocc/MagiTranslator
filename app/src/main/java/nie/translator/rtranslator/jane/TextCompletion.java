package nie.translator.rtranslator.jane;

/** A text-completion model (PLaMo 3 Translate on device). Deterministic: the same prompt gives the same text. */
public interface TextCompletion {
    /** Continues {@code prompt} for at most {@code maxTokens} tokens, stopping before any of {@code stops}. */
    String complete(String prompt, int maxTokens, String... stops) throws Exception;

    /**
     * Same in a cache slot: a model that keeps the computed prompt prefix per slot can reuse it when the next
     * prompt of that slot starts the same way (one slot per translation direction). Default: no slots.
     */
    default String complete(int slot, String prompt, int maxTokens, String... stops) throws Exception {
        return complete(prompt, maxTokens, stops);
    }

    /**
     * Same, and {@code draft} (may be null) receives the text generated so far while the model generates, so
     * it can be shown before the end. Default: no drafts.
     */
    default String complete(int slot, String prompt, int maxTokens, java.util.function.Consumer<String> draft, String... stops) throws Exception {
        return complete(slot, prompt, maxTokens, stops);
    }
}
