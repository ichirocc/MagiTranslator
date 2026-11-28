package nie.translator.rtranslator.jane;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Subjectless Japanese set phrases the model translates with the wrong person or word for word, rewritten
 * into plain Japanese with the subject stated before the text goes to PLaMo (measured 2026-11-28):
 * 手が離せない -> "I cannot move my hand", お待たせしました -> "sorry that YOU made me wait",
 * 隣で見守っていますね -> "YOU are watching". The rewrite keeps negation and request: 手が離せない
 * has no negation of meaning in Nepali (व्यस्त/अरू काम गरिरहेको), so the validator checks the rewritten text.
 */
public final class JaIdioms {
    private JaIdioms() {}

    private static final Pattern[] FROM = {
            Pattern.compile("手が離せない(ので|から)"),
            Pattern.compile("手が離せません(ので|から)"),
            Pattern.compile("手が離せ(ない|ません)"),
            Pattern.compile("お待たせ(しました|いたしました|致しました|しております|しちゃった|して)(すみません|ごめんなさい|申し訳ありません)?"),
    };
    private static final String[] TO = {
            "私は他の仕事をしている$1",
            "私は他の仕事をしています$1",
            "私は他の仕事中",
            "待たせてごめんなさい",
    };
    // a watching/waiting statement with no subject in the sentence is the speaker's own
    private static final Pattern SELF_WATCH = Pattern.compile("(見守って|見て|そばにいて|ここにいて)(います|いる|ます)(ね|よ)?(?=[。！!]?\\s*$)");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！!？?])");
    private static final Pattern HAS_SUBJECT = Pattern.compile("[はが]");
    private static final Pattern NOT_SELF = Pattern.compile("ください|下さい|ましょう|ませんか|[か？?][。]?$|て[ねよ]?[。！!]?$");

    public static String rewrite(String ja) {
        if (ja == null || ja.isEmpty()) return ja;
        String t = ja;
        for (int i = 0; i < FROM.length; i++) t = FROM[i].matcher(t).replaceAll(TO[i]);
        StringBuilder out = new StringBuilder();
        for (String s : SENTENCE_END.split(t)) out.append(selfSubject(s));
        return out.toString();
    }

    private static String selfSubject(String s) {
        Matcher m = SELF_WATCH.matcher(s);
        if (!m.find() || HAS_SUBJECT.matcher(s).find() || NOT_SELF.matcher(s.substring(m.start())).find()) return s;
        return s.substring(0, m.start()) + "私が" + s.substring(m.start());
    }
}
