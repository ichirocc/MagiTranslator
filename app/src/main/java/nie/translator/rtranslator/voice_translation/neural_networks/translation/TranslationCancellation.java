package nie.translator.rtranslator.voice_translation.neural_networks.translation;

import java.util.concurrent.atomic.AtomicLong;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

/**
 * Per-request cancellation for the local translation engine (SPEC v2 part 3 §16.5).
 *
 * Requests belong to a scope (one per RTranslator mode). A request takes a {@link Ticket} when it is
 * submitted; {@link #cancelScope(int)} invalidates every ticket of that scope taken so far, including
 * requests still waiting for the translation lock. The running request owns one ORT
 * {@link OrtSession.RunOptions}: cancelling sets its terminate flag so an encoder/decoder run in
 * progress stops at the next ORT check point, and the decoder loop checks the ticket between steps.
 *
 * Cancelled requests deliver nothing to the UI: callers check {@link Ticket#isCancelled()} before
 * posting a result, and again inside the posted runnable.
 */
public final class TranslationCancellation {
    private final AtomicLong[] generations;
    private final Object lock = new Object();
    private Ticket active;   // the request currently running (translations are serialised); guarded by lock

    public TranslationCancellation(int scopes) {
        generations = new AtomicLong[scopes];
        for (int i = 0; i < scopes; i++) generations[i] = new AtomicLong();
    }

    /** Taken at submit time, before waiting for the translation lock. */
    public Ticket newTicket(int scope) {
        return new Ticket(this, scope, generations[scope].get());
    }

    /** Cancels every request of the scope submitted until now. Safe from any thread; never blocks on inference. */
    public void cancelScope(int scope) {
        generations[scope].incrementAndGet();
        synchronized (lock) {
            if (active != null && active.scope == scope) active.terminateRun();
        }
    }

    public void cancelAll() {
        for (int s = 0; s < generations.length; s++) cancelScope(s);
    }

    /** Opens the RunOptions of the request that is about to run (call with the translation lock held). */
    public void begin(Ticket t) throws OrtException {
        OrtSession.RunOptions ro = new OrtSession.RunOptions();
        synchronized (lock) {
            t.runOptions = ro;
            active = t;
            if (t.isCancelled()) ro.setTerminate(true);   // cancelled while it was queued
        }
    }

    /** Closes the RunOptions; always call in a finally block after {@link #begin(Ticket)}. */
    public void end(Ticket t) {
        OrtSession.RunOptions ro;
        synchronized (lock) {
            if (active == t) active = null;
            ro = t.runOptions;
            t.runOptions = null;
        }
        if (ro != null) ro.close();
    }

    public static final class Ticket {
        private final TranslationCancellation owner;
        final int scope;
        private final long generation;
        private volatile OrtSession.RunOptions runOptions;

        private Ticket(TranslationCancellation owner, int scope, long generation) {
            this.owner = owner;
            this.scope = scope;
            this.generation = generation;
        }

        public boolean isCancelled() {
            return owner.generations[scope].get() != generation;
        }

        public void throwIfCancelled() throws TranslationCancelledException {
            if (isCancelled()) throw new TranslationCancelledException();
        }

        /** RunOptions to pass to every session.run of this request; null (= ORT defaults) outside a request. */
        public OrtSession.RunOptions runOptions() {
            return runOptions;
        }

        private void terminateRun() {
            OrtSession.RunOptions ro = runOptions;
            if (ro == null) return;
            try {
                ro.setTerminate(true);
            } catch (OrtException ignored) {
                // the decoder loop still stops at the next ticket check
            }
        }
    }
}
