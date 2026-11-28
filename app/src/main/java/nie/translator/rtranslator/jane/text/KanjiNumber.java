package nie.translator.rtranslator.jane.text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts kanji numerals followed by a counter into Arabic digits (三時半 -> 3時30分) so that
 * the NMT copies numbers instead of guessing them, and QualityScorer can verify they survived.
 * Only numerals directly before a counter are converted, so words like 一緒 stay intact; idioms
 * whose numeral is followed by a counter-like kanji (三日月, 万年筆, 十分 = enough) are protected.
 */
public final class KanjiNumber {
    private static final String DIGITS = "〇一二三四五六七八九";
    private static final Pattern NUM_COUNTER = Pattern.compile(
            "([〇一二三四五六七八九十百千万億兆]+)(?=時|分|秒|円|人|名|日|月|年|歳|才|個|回|本|枚|階|週間|時間|ヶ月|か月|キロ|メートル)");
    private static final Pattern HALF_HOUR = Pattern.compile("(\\d+)時半");

    private KanjiNumber() {}

    // idioms whose numerals are not quantities (一度 = once, 一番 = most, 一人前 = full-fledged ...)
    private static final String[] IDIOMS = {"一人前", "一人っ子", "一人ひとり", "一日中", "一時的", "一回り", "二人三脚", "一枚岩", "十人十色", "一年中",
            "三日月", "三日坊主", "万年筆", "万年床", "一時停止", "一時期", "一時帰国", "一時保存", "二日酔い", "百人一首", "五月雨", "五月蝿",
            // 一時 = "for a while / at one point", not 1 o'clock
            "一時は", "一時の", "一時中断", "一時外出", "一時退院", "一時預かり"};
    // 十分 (じゅうぶん) means "enough"; it is 10 minutes only after an hour (三時十分) or before a
    // duration/relative marker (十分間, 十分後, 十分ほど ...)
    private static final Pattern JUPPUN_AFTER = Pattern.compile("^分(?:間|後|前|以内|ほど|くらい|ぐらい|程度|おき|ごと)");

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
            String k = m.group(1);
            long v = isQuantity(text, m) ? parse(k) : -1;
            m.appendReplacement(sb, v < 0 ? k : Long.toString(v));
        }
        m.appendTail(sb);
        return HALF_HOUR.matcher(sb.toString()).replaceAll("$1時30分");
    }

    private static boolean isQuantity(String text, Matcher m) {
        String k = m.group(1);
        // a bare 万 is not "10000" (万年筆, 万一): real amounts are written 一万
        if (k.equals("万") || k.equals("億") || k.equals("兆")) return false;   //億万長者: a bare unit is not an amount
        if (k.equals("十") && text.startsWith("分", m.end())) {
            boolean afterHour = m.start() > 0 && text.charAt(m.start() - 1) == '時';
            return afterHour || JUPPUN_AFTER.matcher(text.substring(m.end())).lookingAt();
        }
        return true;
    }

    /** Parses 二千三百十五 -> 2315, 二〇二六 -> 2026; -1 when malformed. */
    public static long parse(String k) {
        boolean positional = k.indexOf('十') < 0 && k.indexOf('百') < 0 && k.indexOf('千') < 0 && k.indexOf('万') < 0 && k.indexOf('億') < 0 && k.indexOf('兆') < 0;
        if (positional) {
            long v = 0;
            for (char c : k.toCharArray()) {
                int d = DIGITS.indexOf(c);
                if (d < 0) return -1;
                v = v * 10 + d;
            }
            return v;
        }
        //total: whole 億 / 兆 groups; man: the 万 part below the next 億 (一億二千万 = 1e8 + 2000e4)
        long total = 0, man = 0, section = 0, num = 0;
        for (char c : k.toCharArray()) {
            int d = DIGITS.indexOf(c);
            if (d >= 0) { num = d; continue; }
            switch (c) {
                case '十': section += (num == 0 ? 1 : num) * 10; num = 0; break;
                case '百': section += (num == 0 ? 1 : num) * 100; num = 0; break;
                case '千': section += (num == 0 ? 1 : num) * 1000; num = 0; break;
                case '万': man += (section + num == 0 ? 1 : section + num) * 10000; section = 0; num = 0; break;
                case '億': total += (man + section + num == 0 ? 1 : man + section + num) * 100000000L; man = 0; section = 0; num = 0; break;
                case '兆': total += (man + section + num == 0 ? 1 : man + section + num) * 1000000000000L; man = 0; section = 0; num = 0; break;
                default: return -1;
            }
        }
        return total + man + section + num;
    }
}
