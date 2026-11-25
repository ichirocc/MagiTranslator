package nie.translator.rtranslator.jane.ir;

import java.text.Normalizer;

/** Text helpers shared by the analyzers. */
public final class IrText {
    private IrText() {}

    /** Devanagari letters and signs, without the danda । ॥ */
    public static final String NE_CH = "\\u0900-\\u0963\\u0966-\\u097F";
    public static final String NE_L = "(?<![" + NE_CH + "])";
    public static final String NE_R = "(?![" + NE_CH + "])";

    /** Lenient key for containment: NFKC and candrabindu treated as anusvara (काठमाडौँ / काठमाडौं). */
    public static String canon(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC).replace('ँ', 'ं');
    }

    public static String nfc(String s) { return Normalizer.normalize(s, Normalizer.Form.NFC); }
    public static String nfkc(String s) { return Normalizer.normalize(s, Normalizer.Form.NFKC); }
}
