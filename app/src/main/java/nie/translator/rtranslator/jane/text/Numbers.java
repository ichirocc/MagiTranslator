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
    // whole Nepali number words 1-20, the tens and 25 (एक is also the article "a": at worst a soft NUMBER_ADDED). छ (6)
    // is left out because it is also the copula "is"; सत्र (17) because it also means "session"
    private static final Pattern NE_NUMBER_WORD = Pattern.compile("(?<![\\u0900-\\u0963\\u0966-\\u097F])(उन्नाइस|पन्ध्र|पच्चीस|सत्तरी|बाह्र|तेह्र|सोह्र|चालीस|नब्बे|पाँच|एघार|अठार|पचास|साठी|दुई|तीन|चार|सात|चौध|बीस|तीस|असी|एक|आठ|नौ|दश|दस)(?![\\u0900-\\u0963\\u0966-\\u097F])");
    private static final Map<String, String> NE_NUMBER_VALUE = new HashMap<>();
    static {
        String[] w = {"एक", "दुई", "तीन", "चार", "पाँच", "सात", "आठ", "नौ", "दश", "दस", "एघार", "बाह्र", "तेह्र", "चौध", "पन्ध्र", "सोह्र", "अठार", "उन्नाइस", "बीस", "पच्चीस", "तीस", "चालीस", "पचास", "साठी", "सत्तरी", "असी", "नब्बे"};
        String[] v = {"1", "2", "3", "4", "5", "7", "8", "9", "10", "10", "11", "12", "13", "14", "15", "16", "18", "19", "20", "25", "30", "40", "50", "60", "70", "80", "90"};
        for (int i = 0; i < w.length; i++) NE_NUMBER_VALUE.put(w[i], v[i]);
    }
    // "N सय / हजार / लाख / करोड" = N x 100 / 1,000 / 100,000 / 10,000,000 (a bare multiplier word is not counted)
    private static final Pattern NE_MULTIPLIED = Pattern.compile("(\\d+)\\s*(सय|हजार|लाख|करोड)(?![\\u0900-\\u0963\\u0966-\\u097F])");

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
        Matcher mu = NE_MULTIPLIED.matcher(a);
        StringBuffer mb = new StringBuffer();
        while (mu.find()) {
            long base = Long.parseLong(mu.group(1));
            String u = mu.group(2);
            long mult = u.equals("सय") ? 100 : u.equals("हजार") ? 1000 : u.equals("लाख") ? 100000 : 10000000;
            mu.appendReplacement(mb, Long.toString(base * mult));
        }
        mu.appendTail(mb);
        a = mb.toString();
        List<String> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(a);
        while (m.find()) {
            String n = m.group().replaceFirst("^0+(?=\\d)", "");
            out.add(n);
        }
        return out;
    }
}
