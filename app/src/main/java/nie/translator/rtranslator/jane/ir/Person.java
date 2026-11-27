package nie.translator.rtranslator.jane.ir;

import java.util.regex.Pattern;

/**
 * Speaker/listener reversal check (zero-pronoun Japanese). Hard evidence only:
 * the last Japanese sentence states the speaker's own action (explicit 私/僕 subject, 〜ますね self-announcement,
 * or 〜てきます / 〜てまいります) and the last Nepali sentence is only a listener imperative (-नुहोस्, no
 * first-person verb). Plain 〜します without that evidence is left alone: manuals use it for instructions
 * (「確認」を押します = press "confirm"). Measured on the 4,548 phrasebook pairs: 0 false positives.
 */
public final class Person {
    private Person() {}

    private static final Pattern JA_SELF = Pattern.compile(
            "(します|いたします|致します|てきます|でてきます|ていきます|ておきます|てあげます|てみます|てまいります|まいります|伺います|[いきしちにびみりぎ]ます)(ね|よ|ね。|よ。)?[。！!]?$");
    private static final Pattern JA_BLOCK = Pattern.compile(
            "(ください|下さい|お願い|ませんか|ましょう|でしょう|か[。？?]?$|？|\\?|[^私僕]が[^、]*ます[。！]?$|さんは|様は|方は|者は|ご家族は|看護師[がは]|先生[がは]|医師[がは]|失礼します)");
    private static final Pattern JA_STRONG = Pattern.compile(
            "(私|僕|わたし|わたくし|自分)[がはも]|ますね[。！]?$|(てきます|でてきます|てまいります|てきますね)[。！]?$");
    private static final Pattern NE_IMPERATIVE_END = Pattern.compile("(नुहोस्|नुहोला|नुस्)[!।\\s]*$");
    private static final Pattern NE_FIRST = Pattern.compile("(छु|छौं|छौँ|एँ|यौं|औं|औँ|उँला|ौंला|ुँला|थें|थिएँ)(?![\\u0900-\\u097F])");

    static String lastJa(String s) {
        String[] p = s.trim().split("(?<=[。！!？?])");
        for (int i = p.length - 1; i >= 0; i--) if (!p[i].trim().isEmpty()) return p[i].trim();
        return s.trim();
    }

    static String lastNe(String s) {
        String[] p = s.trim().split("(?<=[।!?])\\s*");
        for (int i = p.length - 1; i >= 0; i--) if (!p[i].trim().isEmpty()) return p[i].trim();
        return s.trim();
    }

    /** the Japanese text's last sentence clearly states the speaker's own action */
    public static boolean jaStatesOwnAction(String ja) {
        if (ja == null) return false;
        String l = lastJa(ja);
        return JA_SELF.matcher(l).find() && !JA_BLOCK.matcher(l).find() && JA_STRONG.matcher(l).find();
    }

    /** the Nepali text's last sentence only tells the listener to act */
    public static boolean neOnlyListenerImperative(String ne) {
        if (ne == null) return false;
        String l = lastNe(ne);
        return NE_IMPERATIVE_END.matcher(l).find() && !NE_FIRST.matcher(l).find();
    }
}
