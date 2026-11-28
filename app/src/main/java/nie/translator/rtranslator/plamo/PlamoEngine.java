package nie.translator.rtranslator.plamo;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import nie.translator.rtranslator.jane.TextCompletion;

/**
 * PLaMo 3 Translate (GGUF) running on the CPU through llama.cpp ({@code libplamo_engine.so}).
 * One instance holds the model (memory-mapped) and one context; calls are serialised.
 */
public final class PlamoEngine implements TextCompletion, Closeable {
    private static volatile boolean libraryLoaded;

    /** Loads libplamo_engine.so; false when the library is missing or the CPU cannot run it. */
    public static synchronized boolean loadLibrary() {
        if (libraryLoaded) return true;
        if (!CpuFeatures.canRunEngine()) return false;
        try {
            System.loadLibrary("plamo_engine");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            libraryLoaded = false;
        }
        return libraryLoaded;
    }

    /** Big and middle cores (the slowest cluster left out); the default thread count. */
    public static int performanceCores() {
        return loadLibrary() ? Math.max(1, nativePerformanceCores()) : 1;
    }

    private long handle;
    public final int contextTokens;

    private PlamoEngine(long handle, int contextTokens) {
        this.handle = handle;
        this.contextTokens = contextTokens;
    }

    /**
     * @param threads 0 = {@link #performanceCores()}
     * @param contextTokens prompt + translation budget (PFML3 header, history, glossary, text, output)
     */
    public static PlamoEngine open(File gguf, int threads, int contextTokens) throws IOException {
        if (!loadLibrary()) throw new IOException("PLaMo engine not available on this device");
        if (!gguf.isFile()) throw new IOException("model file missing");
        int t = threads > 0 ? threads : performanceCores();
        long h = nativeLoad(gguf.getAbsolutePath(), t, contextTokens);
        if (h == 0) throw new IOException("cannot load the PLaMo model");
        return new PlamoEngine(h, contextTokens);
    }

    /** Slots of the context: one KV cache per translation direction, each {@link #contextTokens} long. */
    public static final int SLOTS = 2;

    @Override
    public String complete(String prompt, int maxTokens, String... stops) throws IOException {
        return complete(0, prompt, maxTokens, stops);
    }

    /** Like {@link #complete(String, int, String...)} in the given slot (keeps that slot's prefix cache). */
    public synchronized String complete(int slot, String prompt, int maxTokens, String... stops) throws IOException {
        return complete(slot, prompt, maxTokens, (java.util.function.Consumer<String>) null, stops);
    }

    /** With drafts: {@code draft} gets the text so far after each generated token (complete characters, no stop string). */
    @Override
    public synchronized String complete(int slot, String prompt, int maxTokens, java.util.function.Consumer<String> draft, String... stops) throws IOException {
        if (handle == 0) throw new IOException("engine closed");
        if (slot < 0 || slot >= SLOTS) throw new IllegalArgumentException("slot " + slot);
        byte[][] s = new byte[stops.length][];
        for (int i = 0; i < stops.length; i++) s[i] = stops[i].getBytes(StandardCharsets.UTF_8);
        java.util.function.BooleanSupplier cancel = cancelCheck;
        Object bridge = draft == null && cancel == null ? null : new DraftBridge(draft, cancel);
        byte[] out = nativeComplete(handle, slot, prompt.getBytes(StandardCharsets.UTF_8), maxTokens, s, bridge);
        if (out == null && cancel != null && cancel.getAsBoolean()) throw new IOException("PLaMo generation cancelled");
        if (out == null) throw new IOException("PLaMo generation failed (prompt too long or decode error)");
        return new String(out, StandardCharsets.UTF_8);
    }

    /**
     * Applies a LoRA adapter trained for this model (GGUF), or removes it with {@code null}. The prefix
     * caches are dropped. False when the file is not an adapter for this model.
     */
    public synchronized boolean setAdapter(File adapter, float scale) throws IOException {
        if (handle == 0) throw new IOException("engine closed");
        return nativeSetAdapter(handle, adapter == null ? null : adapter.getAbsolutePath(), scale);
    }

    /** prompt tokens, of which reused from the cache, generated tokens, prompt microseconds, generation microseconds */
    public synchronized long[] lastStats() {
        return handle == 0 ? new long[5] : nativeLastStats(handle);
    }

    @Override
    public synchronized void close() {
        if (handle != 0) { nativeFree(handle); handle = 0; }
    }

    private static native int nativePerformanceCores();
    private static native long nativeLoad(String path, int threads, int contextTokens);
    private static native byte[] nativeComplete(long handle, int slot, byte[] prompt, int maxTokens, byte[][] stops, Object draftListener);

    /** Called from the native generation loop (plamo_engine.cpp looks up onPartial([B)V). */
    private static final class DraftBridge {
        private final java.util.function.Consumer<String> draft;
        private final java.util.function.BooleanSupplier cancel;

        DraftBridge(java.util.function.Consumer<String> draft, java.util.function.BooleanSupplier cancel) { this.draft = draft; this.cancel = cancel; }

        @SuppressWarnings("unused")
        void onPartial(byte[] utf8) {
            if (draft != null) draft.accept(new String(utf8, StandardCharsets.UTF_8));
        }

        @SuppressWarnings("unused")
        boolean isCancelled() {   //polled by the native loop before each token
            return cancel != null && cancel.getAsBoolean();
        }
    }

    private volatile java.util.function.BooleanSupplier cancelCheck;

    /** A check the generation loop polls before each token; true stops it (complete() then throws). */
    public void setCancelCheck(java.util.function.BooleanSupplier check) { cancelCheck = check; }
    private static native long[] nativeLastStats(long handle);
    private static native boolean nativeSetAdapter(long handle, String path, float scale);
    private static native void nativeFree(long handle);
}
