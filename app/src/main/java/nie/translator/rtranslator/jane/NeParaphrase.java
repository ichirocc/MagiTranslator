package nie.translator.rtranslator.jane;

import nie.translator.rtranslator.jane.ir.Places;

/**
 * Meaning-preserving rewrites of a Nepali source for the extra round when every candidate still has a
 * hard problem: ASCII digits without grouping commas (the model misread १,००० as 100万), and a space
 * between a known place name and its case marker (नेपालबाट -> नेपाल बाट; the model produced ネバダ).
 */
public final class NeParaphrase {
    private NeParaphrase() {}

    public static String paraphrase(String ne) {
        if (ne == null) return "";
        StringBuilder b = new StringBuilder(ne.length());
        for (int i = 0; i < ne.length(); i++) {
            char c = ne.charAt(i);
            b.append(c >= '०' && c <= '९' ? (char) ('0' + (c - '०')) : c);
        }
        String t = b.toString();
        t = t.replaceAll("(?<=\\d)[,،](?=\\d{3})", "");
        for (String stem : Places.nepaliStems())
            t = t.replaceAll("(?<![\\u0900-\\u097F])" + java.util.regex.Pattern.quote(stem) + "(बाट|मा|को|का|की|लाई|देखि|सम्म)", stem + " $1");
        return t;
    }
}
