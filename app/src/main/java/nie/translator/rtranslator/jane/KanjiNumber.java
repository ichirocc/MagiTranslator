package nie.translator.rtranslator.jane;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts kanji numerals followed by a counter into Arabic digits (三時半 -> 3時30分) so that
 * the NMT copies numbers instead of guessing them, and QualityScorer can verify they survived.
 * Only numerals directly before a counter are converted, so words like 一緒 or 三日月 stay intact
 * unless they are real quantities (三日 = 3日).
 */
public final class KanjiNumber {
    private static final String DIGITS = "〇一二三四五六七八九";
    private static final Pattern NUM_COUNTER = Pattern.compile(
            "([〇一二三四五六七八九十百千万]+)(?=時|分|秒|円|人|名|日|月|年|歳|才|個|回|本|枚|階|週間|時間|ヶ月|か月|キロ|メートル)");
    private static final Pattern HALF_HOUR = Pattern.compile("(\\d+)時半");

    private KanjiNumber() {}

    // idioms whose numerals are not quantities (一度 = once, 一番 = most, 一人前 = full-fledged ...)
    private static final String[] IDIOMS = {"一人前", "一人っ子", "一人ひとり", "一日中", "一時的", "一回り", "二人三脚", "一枚岩", "十人十色", "一年中"};

    public static String toDigits(String text) {
        java.util.List<String> saved = new java.util.ArrayList<>();
        for (String idiom : IDIOMS) {
            if (text.contains(idiom)) {
                saved.add(idiom);
                text = text.replace(idiom, "\uE000" + (saved.size() - 1) + "\uE001");
            }
        }
        String converted = toDigitsRaw(text);
        for (int i = 0; i < saved.size(); i++) converted = converted.replace("\uE000" + i + "\uE001", saved.get(i));
        return converted;
    }

    private static String toDigitsRaw(String text) {
        Matcher m = NUM_COUNTER.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            long v = parse(m.group(1));
            m.appendReplacement(sb, v < 0 ? m.group(1) : Long.toString(v));
        }
        m.appendTail(sb);
        return HALF_HOUR.matcher(sb.toString()).replaceAll("$1時30分");
    }

    /** Parses 二千三百十五 -> 2315, 二〇二六 -> 2026; -1 when malformed. */
    static long parse(String k) {
        boolean positional = k.indexOf('十') < 0 && k.indexOf('百') < 0 && k.indexOf('千') < 0 && k.indexOf('万') < 0;
        if (positional) {
            long v = 0;
            for (char c : k.toCharArray()) {
                int d = DIGITS.indexOf(c);
                if (d < 0) return -1;
                v = v * 10 + d;
            }
            return v;
        }
        long total = 0, section = 0, num = 0;
        for (char c : k.toCharArray()) {
            int d = DIGITS.indexOf(c);
            if (d >= 0) { num = d; continue; }
            switch (c) {
                case '十': section += (num == 0 ? 1 : num) * 10; num = 0; break;
                case '百': section += (num == 0 ? 1 : num) * 100; num = 0; break;
                case '千': section += (num == 0 ? 1 : num) * 1000; num = 0; break;
                case '万': total += (section + num == 0 ? 1 : section + num) * 10000; section = 0; num = 0; break;
                default: return -1;
            }
        }
        return total + section + num;
    }
}
