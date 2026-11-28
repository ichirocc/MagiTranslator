package nie.translator.rtranslator.jane;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Cleans Japanese input (often ASR output) before MT. Pure Java, no Android dependency. */
public final class JaNormalizer {
    // Spoken fillers. Only removed when followed by a pause mark, so "あの人" is kept.
    // うん (= yes) and なんか (= something / somehow) carry meaning and are never removed.
    private static final Pattern FILLERS = Pattern.compile(
            "(?:^|(?<=[、。！？\\s]))(?:えーっと|ええっと|えっと|えーと|ええと|えー|あのー|あのう|あの|そのー|まあ|うーん|んー)[、,\\s]+");
    private static final Pattern LONG_MARKS = Pattern.compile("[ー〜~]{2,}");
    private static final Pattern SPACES = Pattern.compile("[\\s\\u3000]+");
    private static final Pattern ONLY_PUNCT = Pattern.compile("[、。！？,.\\s]");

    private JaNormalizer() {}

    public static String normalize(String text) {
        if (text == null) return "";
        String t = Normalizer.normalize(text, Normalizer.Form.NFKC);
        // NFKC turns "。" into itself but "！？" into ASCII; restore them first so that the filler rule
        // (which looks behind for 、。！？) and the segmenter both see the Japanese marks
        t = t.replace("!", "！").replace("?", "？");
        t = FILLERS.matcher(t).replaceAll("");
        t = LONG_MARKS.matcher(t).replaceAll("ー");
        t = SPACES.matcher(t).replaceAll(" ").trim();
        t = KanjiNumber.toDigits(t);
        // Only fillers / punctuation: there is nothing to translate. Returning the raw input here would
        // feed the filler itself to the NMT, so an empty string is returned instead.
        return ONLY_PUNCT.matcher(t).replaceAll("").isEmpty() ? "" : t;
    }

    /**
     * Older and literary forms rewritten in today's Japanese, for the text the model reads (the model was
     * trained on modern written text): お出でだ read as "came" (the sick father "is sleeping" became "I slept and
     * came"), 頂戴 / なすって / 〜たまえ / 〜ぬ / 居る were misread too. Only forms with one modern reading.
     */
    public static String modernize(String ja) {
        if (ja == null || ja.isEmpty()) return ja == null ? "" : ja;
        String t = ja;
        for (String[] r : MODERN) t = t.replaceAll(r[0], r[1]);
        return t;
    }

    private static final String[][] MODERN = {
            {"てお出で?です", "ていらっしゃいます"}, {"てお出で?だ", "ていらっしゃる"},   // also written お出だ
            {"お出でです", "いらっしゃいます"}, {"お出でだ", "いらっしゃる"},
            {"て頂戴(?=[。！？、]|$)", "てください"},
            {"なすっ(?=[てた])", "なさっ"},
            {"(?<=[しせ])たまえ(?=[。！？、]|$)", "なさい"},
            // negative ぬ after an a-row kana (言わぬ, 行かぬ); 死ぬ (to die) is not negative
            {"(?<=[わらかさたまばがなや])ぬ(?=よう|[。！？、]|$|と|が|の|か|ので)", "ない"},
            {"居(?=[るたてれまな])", "い"}, {"居らずに", "いないで"}, {"居らず", "いないで"}, {"居らない", "いない"},
            {"^時に(?=[、]?[^間はも])", "ところで"},   // 時に、 = "by the way"; not 時には / 時にも (sometimes)
            {"仰山", "大げさ"},
            // care words often written in hiragana (forms, plain Japanese): ねつがあります came out as "it has a
            // feeling", うごかない as "not shaken"; only spellings with no other reading
            {"(?<![ぁ-ん])(お)?ねつ(?=[がはもをの]|$)", "$1熱"},
            {"うご(か(?=な|せ|さ)|き(?=ま|た|が)|く(?=[。、]|$|と|の)|け(?=な|ま|る))", "動$1"},
            {"さん歩", "散歩"},
            // spoken contractions, at the end of a clause only (きっとくる, ちょっとくらい are not contractions):
            // こっちでやっとくよ came out as "here alone is enough"
            {"(?<=[っい])と(く|いた|いて|きます|きました)(?=よ|ね|わ|から|けど|[。！？!?、…]|$)", "てお$1"},
            {"(?<=ん)ど(く|いた|いて|きます|きました)(?=よ|ね|わ|から|けど|[。！？!?、…]|$)", "でお$1"},
            {"ちゃった", "てしまった"}, {"じゃった", "でしまった"},
            {"(?:なきゃ|なくちゃ)(?=いけない|ならない)", "なければ"},
            {"(?:なきゃ|なくちゃ)(?=よ|ね|[。！？!?、…]|$)", "なければならない"},
    };

    /** Adds an explicit sentence end; Madlad translates complete sentences more faithfully. */
    public static String ensureSentenceEnd(String s) {
        String t = s.trim();
        if (t.isEmpty()) return t;
        char last = t.charAt(t.length() - 1);
        return "。！？".indexOf(last) >= 0 ? t : t + "。";
    }
}
