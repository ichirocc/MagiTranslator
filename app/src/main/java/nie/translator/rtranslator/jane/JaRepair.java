package nie.translator.rtranslator.jane;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, deterministic repairs of a ne->ja candidate. A repair only proposes a new candidate: it goes
 * through the same validator and ranking as every other candidate (never an unconditional output).
 */
public final class JaRepair {
    private JaRepair() {}

    // रुपैयाँ / रुपैयां / रु / रु. in the Nepali source
    private static final Pattern NE_RUPEE = Pattern.compile("रुपैया[ँं]|(?<![\\u0900-\\u097F])रु(?![\\u0900-\\u0963\\u0966-\\u097F])\\.?");
    // a number directly followed by the wrong currency word: 1000円 / 1,000 円 / 100万ドル (not 円満)
    private static final Pattern JA_NUM_CURRENCY = Pattern.compile("([0-9０-９][0-9０-９,，.．万千百億]*)(\\s*)(円|ドル)(?!満)");
    // an immediately repeated katakana word (ネパールネパール)
    private static final Pattern KATAKANA_REPEAT = Pattern.compile("([\\u30A1-\\u30FAー]{2,})\\1+");

    /** Replaces the currency word next to a number with ルピー when the source names rupees; null when not applicable. */
    public static String currency(String sourceNe, String ja) {
        if (sourceNe == null || ja == null) return null;
        String ne = Normalizer.normalize(sourceNe, Normalizer.Form.NFC);
        if (!NE_RUPEE.matcher(ne).find() || ja.contains("ルピー")) return null;
        Matcher m = JA_NUM_CURRENCY.matcher(ja);
        if (!m.find()) return null;
        return ja.substring(0, m.start()) + m.group(1) + m.group(2) + "ルピー" + ja.substring(m.end());
    }

    /** Collapses an immediately repeated katakana word; null when there is none. */
    public static String collapseRepeat(String ja) {
        if (ja == null || !KATAKANA_REPEAT.matcher(ja).find()) return null;
        return KATAKANA_REPEAT.matcher(ja).replaceAll("$1");
    }
}
