package nie.translator.rtranslator.jane;

/** Neural MT backend (Madlad on device). Must translate one text from src to tgt without side effects on the UI. */
public interface Backend {
    String translate(String text, String srcLang, String tgtLang) throws Exception;
}
