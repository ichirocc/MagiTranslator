package nie.translator.rtranslator.jane;

import java.util.Collections;
import java.util.List;

/** Neural MT backend (Madlad on device). Must translate one text from src to tgt without side effects on the UI. */
public interface Backend {
    String translate(String text, String srcLang, String tgtLang) throws Exception;

    /**
     * Up to {@code n} distinct hypotheses from one beam search, best first. Backends without beam access
     * return only the 1-best.
     */
    default List<String> translateNBest(String text, String srcLang, String tgtLang, int n) throws Exception {
        return Collections.singletonList(translate(text, srcLang, tgtLang));
    }
}
