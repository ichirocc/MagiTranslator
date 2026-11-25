package nie.translator.rtranslator.voice_translation.neural_networks.translation;

/** Thrown inside the inference path when the request was cancelled (SPEC v2 part 3 §16.5). Carries no text. */
public final class TranslationCancelledException extends Exception {
    public TranslationCancelledException() {
        super("translation cancelled");
    }
}
