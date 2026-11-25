package nie.translator.rtranslator.jane;

/** Kept for existing callers; the implementation lives in the leaf package {@link nie.translator.rtranslator.jane.text}. */
public final class KanjiNumber {
    private KanjiNumber() {}

    public static String toDigits(String text) { return nie.translator.rtranslator.jane.text.KanjiNumber.toDigits(text); }

    static long parse(String k) { return nie.translator.rtranslator.jane.text.KanjiNumber.parse(k); }
}
