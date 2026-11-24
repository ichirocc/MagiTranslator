package nie.translator.rtranslator.jane;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits Japanese into sentences. ASR output often has no punctuation, so besides 。！？ we also cut
 * after polite sentence-final forms followed by a space (e.g. "行きます 明日は…").
 */
public final class JaSegmenter {
    private static final Pattern HARD_END = Pattern.compile("(?<=[。！？])");
    private static final Pattern SOFT_END = Pattern.compile(
            "(?<=(?:ます|ました|ません|です|でした|ください|ましょう|ですか|ますか|ませんか|でしょう)[よねか]?)\\s+");

    private JaSegmenter() {}

    public static List<String> split(String text) {
        List<String> out = new ArrayList<>();
        for (String hard : HARD_END.split(text)) {
            for (String soft : SOFT_END.split(hard)) {
                String s = soft.trim();
                if (!s.isEmpty()) out.add(JaNormalizer.ensureSentenceEnd(s));
            }
        }
        return out;
    }

    /** True when the text contains several sentences (worth a sentence-by-sentence candidate). */
    public static boolean isMulti(String text) {
        return split(text).size() > 1;
    }

    static int countMatches(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int c = 0;
        while (m.find()) c++;
        return c;
    }
}
