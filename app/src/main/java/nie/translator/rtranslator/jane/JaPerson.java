package nie.translator.rtranslator.jane;

import java.util.regex.Pattern;

/**
 * Who does the action of a Japanese sentence with no subject, read from how it ends. Japanese leaves the
 * subject out when the ending fixes it, and Nepali must then say it (the verb agrees with the person):
 * <ul>
 *   <li>speaker ({@link Person#ME}): a feeling or sensation stated (痛い, 眠い), a wish stated (〜たい), an
 *       intention (〜よう, 〜つもりだ), humble speech (お〜します, いたします, 伺います, 〜させていただきます),
 *       giving (〜てあげる), preparing (〜ておきます);</li>
 *   <li>listener ({@link Person#YOU}): a request (〜てください, 〜ていただけますか, 〜てもらえる？, 〜てくれる？),
 *       a feeling or wish asked about (痛い？, 飲みたい？);</li>
 *   <li>not the speaker ({@link Person#NOT_ME}): respect speech (いらっしゃる, お見えになる), receiving (〜てくださった,
 *       〜てくれた).</li>
 * </ul>
 * Plain です / ます fixes nobody (昨日送りました: I or someone else), so it gives null, as does a sentence that
 * already names its subject. Only the last sentence is read.
 */
public final class JaPerson {
    private JaPerson() {}

    public enum Person { ME, YOU, NOT_ME }

    private static final String TAIL = "(?:よ|ね|わ|ぜ|な|ね|の|かな|かしら)*[。！？!?…\\s]*$";
    private static final Pattern NAMED_SUBJECT = Pattern.compile("(私|わたし|僕|俺|あなた|君|お前|さん|様|先生|彼|彼女|こちら|そちら|皆)(は|が|も|で)");
    private static final Pattern YOU = Pattern.compile(
            "[てで](いただけますか|いただけませんか|もらえますか|もらえませんか|もらえる[？?]|もらえない[？?]|くれますか|くれませんか"
            + "|くれる[？?]|くれない[？?]|くださいますか|くださいませんか|ください|下さい)" + TAIL + "|なさい" + TAIL
            // a sensation or wish asked about, also as a negative question (寒くない？)
            + "|(痛|寒|暑|眠|苦し|つら|辛|だる|た)(い|くない)(ですか|の|ん)?[？?]" + TAIL);
    private static final Pattern ME = Pattern.compile(
            "(?<!そう|がって|みたい|らしい)(痛い|眠い|嬉しい|うれしい|悲しい|怖い|寂しい|苦しい|だるい|つらい|お腹(が)?すいた)(です)?" + TAIL
            + "|(?<![らが])たい(です)?" + TAIL
            + "|(しよう|ようと思います|つもりです|つもりだ|ことにしました|ことにした)" + TAIL
            + "|(いたします|致します|いたしました|参ります|申します|伺います|拝見します|させていただきます|お(?!願い)[^をがは「」、。\\s]{1,6}します)" + TAIL
            + "|[てで](あげる|あげます|さしあげます|おきます|おくね|おく)" + TAIL);
    private static final Pattern NOT_ME = Pattern.compile(
            "(いらっしゃいました|いらっしゃった|お見えになりました|[てで]くださいました|[てで]くださった|[てで]くれました|[てで]くれた)" + TAIL);

    /** The person a sentence's ending fixes for its subject, or null when the ending fixes nobody. */
    public static Person of(String ja) {
        if (ja == null) return null;
        String last = lastSentence(ja);
        if (last.isEmpty() || NAMED_SUBJECT.matcher(last).find()) return null;
        if (YOU.matcher(last).find()) return Person.YOU;
        if (ME.matcher(last).find()) return Person.ME;
        if (NOT_ME.matcher(last).find()) return Person.NOT_ME;
        return null;
    }

    static String lastSentence(String text) {
        String t = text.trim();
        String[] parts = t.split("(?<=[。！？!?])");
        for (int i = parts.length - 1; i >= 0; i--) if (!parts[i].trim().isEmpty()) return parts[i].trim();
        return "";
    }

    // Nepali subject / verb person, last sentence only. मलाई / मेरो / तपाईंको are objects or owners, not subjects.
    private static final Pattern NE_FIRST = Pattern.compile(
            "(?<![\\u0900-\\u097F])(म|मैले|हामी|हामीले)(?![\\u0900-\\u097F])|(छु|छौं|छौँ|एँ|ें|नेछु|नेछौं|ूँ|ुँला|ौँला|सक्छु)(?=$|[\\s,।?!])");
    private static final Pattern NE_OTHER = Pattern.compile(
            "(?<![\\u0900-\\u097F])(तपाईं|तपाईँ|तिमी|उहाँ|उनी|ऊ)(ले)?(?=$|[\\s,।?!])|(नुहुन्छ|नुभयो|नुहोस्|नुहोला|छौ|छन्|यौ|ौ)(?=$|[\\s,।?!])");

    /** Whether the Nepali of a sentence contradicts the person its Japanese ending fixes. */
    public static boolean contradicts(Person p, String ne) {
        if (p == null || ne == null) return false;
        String last = lastNeSentence(ne);
        boolean first = NE_FIRST.matcher(last).find(), other = NE_OTHER.matcher(last).find();
        switch (p) {
            case ME: return other && !first;
            case YOU: case NOT_ME: return first && !other;
            default: return false;
        }
    }

    static String lastNeSentence(String ne) {
        String[] parts = ne.trim().split("(?<=[।?!])");
        for (int i = parts.length - 1; i >= 0; i--) if (!parts[i].trim().isEmpty()) return parts[i].trim();
        return "";
    }

    /** The Japanese with the fixed subject written out in its last sentence, for the model only (私が / あなたが). */
    public static String withSubject(String ja, Person p) {
        if (p == null || p == Person.NOT_ME) return ja;
        String last = lastSentence(ja);
        int at = ja.lastIndexOf(last);
        if (at < 0) return ja;
        return ja.substring(0, at) + (p == Person.ME ? "私が" : "あなたが") + ja.substring(at);
    }
}
