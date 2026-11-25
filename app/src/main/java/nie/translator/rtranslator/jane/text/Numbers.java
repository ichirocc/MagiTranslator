package nie.translator.rtranslator.jane.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Number extraction shared by the quality scorer and the Stage 1 IR analyzers. Leaf package: depends on
 * nothing in jane / jane.ir, which removes the jane <-> jane.ir package cycle.
 */
public final class Numbers {
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    // whole Nepali words 1-10 (एक is also the article "a": at worst a soft NUMBER_ADDED). छ (6) is left out
    // because it is also the copula "is".
    private static final Pattern NE_NUMBER_WORD = Pattern.compile("(?<![\\u0900-\\u0963\\u0966-\\u097F])(एक|दुई|तीन|चार|पाँच|सात|आठ|नौ|दश|दस)(?![\\u0900-\\u0963\\u0966-\\u097F])");
    private static final Map<String, String> NE_NUMBER_VALUE = new HashMap<>();
    static {
        String[] w = {"एक", "दुई", "तीन", "चार", "पाँच", "सात", "आठ", "नौ", "दश", "दस"};
        String[] v = {"1", "2", "3", "4", "5", "7", "8", "9", "10", "10"};
        for (int i = 0; i < w.length; i++) NE_NUMBER_VALUE.put(w[i], v[i]);
    }

    private Numbers() {}

    /**
     * Numbers as canonical ASCII strings: full-width (NFKC) and Devanagari digits are mapped to ASCII,
     * thousands separators are removed ("1,000" / "१,०००" -> "1000") and decimals are kept ("2.5").
     */
    public static List<String> numbers(String s) {
        if (s == null) return new ArrayList<>();
        String t = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC);
        StringBuilder ascii = new StringBuilder();
        for (char c : t.toCharArray()) ascii.append(c >= 0x0966 && c <= 0x096F ? (char) ('0' + (c - 0x0966)) : c);
        String a = ascii.toString().replaceAll("(?<=\\d),(?=\\d{3})", "");
        Matcher w = NE_NUMBER_WORD.matcher(a);
        StringBuffer wb = new StringBuffer();
        while (w.find()) w.appendReplacement(wb, NE_NUMBER_VALUE.get(w.group(1)));
        w.appendTail(wb);
        a = wb.toString();
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(a);
        while (m.find()) {
            String n = m.group().replaceFirst("^0+(?=\\d)", "");
            out.add(n);
        }
        return out;
    }
}
