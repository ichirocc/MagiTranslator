package nie.translator.rtranslator.jane;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A short Japanese answer (はい, いいえ, 大丈夫です, 結構です, いいです, ちょっと…) whose meaning depends on the
 * question just asked, written out in full so that the translation carries it:
 * <ul>
 *   <li>after a negative question, はい agrees with what was asked (まだ終わっていませんか？ → はい = not finished
 *       yet) and いいえ contradicts it: both become the statement they mean;</li>
 *   <li>after an offer (手伝いましょうか？ 袋はいりますか？), 大丈夫です / 結構です / いいです decline: not needed;</li>
 *   <li>after a request for permission or a check (使ってもいいですか？ 問題ないですか？) they agree;</li>
 *   <li>ちょっと… after a request declines it softly (ちょっと難しいです).</li>
 * </ul>
 * Translated as is, 結構です to a bag offered came out as हुन्छ ("all right", a yes) and はい to a negative
 * question as a bare हो. Without a question to read (start of a conversation) nothing is changed.
 */
public final class JaAnswer {
    private JaAnswer() {}

    private static final String END = "[。！？!?…・\\s]*$";
    private static final Pattern YES = Pattern.compile("^(はい|ええ|うん)(、|。)?(そうです)?" + END);
    private static final Pattern NO = Pattern.compile("^(いいえ|いえ|いや|ううん)(、|。)?(違います)?" + END);
    private static final Pattern FINE = Pattern.compile("^(大丈夫|結構|いい|平気)(です|だよ|よ|ですよ)?" + END);
    private static final Pattern HESITATE = Pattern.compile("^ちょっと(…|\\.\\.\\.|、|。)*" + END);

    // the question just asked, by its ending (the last sentence)
    private static final Pattern Q_END = Pattern.compile("[？?]\\s*$|か[。\\s]*$");
    /** negative questions about a state: 終わっていませんか, 痛くないですか, ありませんか (not invitations: 行きませんか) */
    private static final Pattern NEG_Q = Pattern.compile("(ていません|でいません|ていない|でいない|くない|ありません|がない|はない|もない)(ですか|か|の)?[？?]?\\s*$");
    private static final Pattern NOT_NEG_Q = Pattern.compile("(じゃない|ではない|ではありません|じゃありません)(ですか|か)?[？?]?\\s*$");   // "isn't it X?" seeks agreement
    private static final Pattern OFFER = Pattern.compile("(ましょうか|しようか|ようか|おうか|てあげようか|いりますか|要りますか|いる？|要る？|必要ですか|いかがですか|ほしいですか|欲しいですか)[？?]?\\s*$");
    private static final Pattern PERMISSION = Pattern.compile("(てもいい|でもいい|てもよろしい|でもよろしい|ていい|でいい|問題ない|問題ありません|大丈夫|よろしい|いい)(ですか|でしょうか|か)?[？?]\\s*$");
    private static final Pattern REQUEST = Pattern.compile("(てもらえますか|でもらえますか|てくれますか|でくれますか|ていただけますか|でいただけますか|できますか|られますか|てほしい|お願いできますか)[？?]?\\s*$");

    /**
     * 〜は結構です at the end of a sentence declines that thing (レジ袋は結構です = no bag, thank you); it came out
     * as "plastic bags are enough". Not それは結構ですね ("that's fine"), which ends in ね.
     */
    public static String declines(String ja) {
        return ja == null ? "" : ja.replaceAll("は結構です(?=[。！!]|$)", "はいりません");
    }

    /** The answer written out for the translation, or the answer itself when the question does not decide it. */
    public static String clarify(String question, String answer) {
        if (question == null || answer == null) return answer;
        String a = answer.trim(), q = JaPerson.lastSentence(question);
        if (q.isEmpty() || !Q_END.matcher(q).find()) return answer;
        boolean yes = YES.matcher(a).matches(), no = NO.matcher(a).matches(), fine = FINE.matcher(a).matches();
        if (!yes && !no && !fine && !HESITATE.matcher(a).matches()) return answer;
        String core = q.replaceAll("[？?。\\s]+$", "").replaceAll("(ですか|でしょうか|か|の)$", "");
        if (NEG_Q.matcher(q).find() && !NOT_NEG_Q.matcher(q).find()) {
            if (yes) return "はい、" + statement(core) + "。";
            if (no) return "いいえ、" + positive(core) + "。";
        }
        if (fine && OFFER.matcher(q).find()) return "大丈夫です、必要ありません。";
        if (fine && PERMISSION.matcher(q).find()) return "はい、問題ありません。";
        if (!yes && !no && !fine && REQUEST.matcher(q).find()) return "ちょっと難しいです。";
        return answer;
    }

    // まだ終わっていません / 痛くないです / お怪我はありません
    static String statement(String core) {
        String s = core;
        if (s.endsWith("くない") || s.endsWith("がない") || s.endsWith("はない") || s.endsWith("もない")) s += "です";
        return s;
    }

    // the same statement made positive: 終わっています / 痛いです / お怪我はあります
    static String positive(String core) {
        String s = core;
        Matcher m;
        s = s.replaceFirst("^まだ", "もう");   // まだ終わっていません -> もう終わっています
        if ((m = Pattern.compile("(て|で)いません$").matcher(s)).find()) return s.substring(0, m.start()) + m.group(1) + "います";
        if ((m = Pattern.compile("(て|で)いない$").matcher(s)).find()) return s.substring(0, m.start()) + m.group(1) + "います";
        if (s.endsWith("くない")) return s.substring(0, s.length() - 3) + "いです";
        if (s.endsWith("ありません")) return s.substring(0, s.length() - 5) + "あります";
        if (s.endsWith("ない")) return s.substring(0, s.length() - 2) + "あります";
        return s;
    }
}
