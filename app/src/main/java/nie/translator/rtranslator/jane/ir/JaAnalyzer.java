package nie.translator.rtranslator.jane.ir;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nie.translator.rtranslator.jane.text.KanjiNumber;
import nie.translator.rtranslator.jane.text.Numbers;

/**
 * Japanese Stage 1 analyzer: rules over the surface string, no morphological analyzer yet.
 * Kept separate from NeAnalyzer on purpose (SPEC v2 part 2, 2.1): no rule is shared between languages.
 */
public final class JaAnalyzer {
    private JaAnalyzer() {}

    // --- negation (predicate level). Adjectives that merely end in ない are excluded.
    static final Pattern NEG = Pattern.compile(
            "(ない|なかった|なくて|ないで|ません|ませんでした|(?:では|じゃ)なく|ず(?=[、。に]|$)|ぬ(?=[、。]|$))");
    // adjectives / idioms that end in ない but are not a negated predicate;
    // apology / obligation / conjecture / invitation are not negation either
    static final Pattern NOT_NEGATION = Pattern.compile(
            "((?:少|すく|危|あぶ|切|せつ|だらし|つまら|くだら|もったい|勿体|仕方が?|しかたが?|間違い|違い|半端|きた|おさ|はか|儚|ぎこち|情け|なさけ|頼り|たより|物足り|もの足り|ものたり|申し訳|申しわけ|そっけ|素っ気|あどけ|あっけ|呆気|味気|あじけ)な(?:い|く|かっ|さ|け)|汚い|幼い|すみません|すいません|申し訳(あり|ござい)ません|かもしれ(ない|ません)|なければ(なら|なり|いけ)(ない|ません)|なくては(なら|なり|いけ)(ない|ません)|ないと(いけ|だめ|ダメ)(ない|ません)?|ませんか(?!ら)|(?:じゃ|では)ない(?:です|でしょう)?か|ないか(?=[、]?(?:確認|見|観察|チェック|調べ))|ないうちに)");
    static final Pattern QUESTION = Pattern.compile("(か[。？]?$|？|\\?)");

    /** stated question words; 何か / 誰も / いつも (indefinites) excluded */
    private static final Object[][] WH = {
            {WhConcept.TIME_OCLOCK, Pattern.compile("何時(?![間代])")},
            {WhConcept.COUNT_PERSON, Pattern.compile("何人")},
            {WhConcept.PRICE, Pattern.compile("いくら")},
            {WhConcept.WHO, Pattern.compile("(?:誰|だれ|どなた)(?![かも])")},
            {WhConcept.WHERE, Pattern.compile("どこ(?![かも])")},
            {WhConcept.WHEN, Pattern.compile("いつ(?![かも])")},
            {WhConcept.WHY, Pattern.compile("(?:なぜ|どうして|なんで)")},
            {WhConcept.HOW, Pattern.compile("(?:どうやって|どのように|どうすれば)")},
            {WhConcept.WHICH, Pattern.compile("(?:どれ|どの|どちら|どっち)(?![かも])")},
    };
    /** Japanese forms that can render a Nepali question word. For कति a bare 何 is not enough
     *  (これは何ですか for यो कति हो? is the nj04 error): it needs いくら / いくつ / どれくらい or 何 + counter. */
    private static final Object[][] WH_COVER = {
            {WhConcept.QUANTITY, Pattern.compile("いくら|幾ら|いくつ|幾つ|どれ(くらい|ぐらい|位)|どの(くらい|ぐらい|位)|何[人個歳回時日本枚台度分秒年月週円階番件杯匹冊着名]|なん(にん|こ|さい|かい|じ|にち|ぼん|まい|だい|ど|ぷん|ふん|ねん)")},
            {WhConcept.WHY, Pattern.compile("なぜ|何故|どうして|なんで|何で|理由")},
            {WhConcept.WHERE, Pattern.compile("どこ|何処|どちら|どっち")},
            {WhConcept.WHEN, Pattern.compile("いつ|何時|何日|何曜")},
            {WhConcept.HOW, Pattern.compile("どう|どのよう|どうやって|如何")},
    };

    static final Pattern OBLIGATION = Pattern.compile("(なければ(なら|なり|いけ)|なくては(なら|なり|いけ)|ないと(いけ|だめ|ダメ)|なきゃ|なくちゃ|べき)");
    static final Pattern REQUEST = Pattern.compile("(ください|下さい|[てで](くれ(ませんか|ますか)?|ちょうだい|もらえ|いただけ)|お願い)");
    static final Pattern CONJECTURE = Pattern.compile("(でしょう(?!か)|だろう|かもしれ|と思い|と思う|みたい|らしい|はず)");
    static final Pattern PERMISSION = Pattern.compile("[てで]も(いい|良い|よい|大丈夫|かまわ|構わ)");
    /** sentence-final past/perfect predicate (ました・でした・かった・〜た). あなた etc. excluded by the kana before た. */
    static final Pattern PAST = Pattern.compile("(ました|でした|かった(です)?|[っんいきしちにびみりえけせてねべめれげ]た|[っん]だ)(よ|ね|よね|か)?[。！!？?]?$");

    private static final Pattern RUPEE = Pattern.compile("ルピー");
    private static final Pattern YEN = Pattern.compile("[0-9０-９〇一二三四五六七八九十百千万億]\\s*円|円(?![滑満形熟周])");
    private static final Pattern DOLLAR = Pattern.compile("ドル");
    private static final Pattern DEMONYM = Pattern.compile("(ネパール|日本|インド|中国|アメリカ)(語|人)");
    // a katakana word of 2+ chars immediately repeated (ネパールネパール): degenerate output
    static final Pattern KATAKANA_REPEAT = Pattern.compile("([\\u30A1-\\u30FAー]{2,})\\1");
    private static final Pattern KATAKANA_WORD = Pattern.compile("[\\u30A1-\\u30FAー]{2,}");

    public static boolean hasNegation(String ja) {
        String t = IrText.nfkc(ja);
        t = NOT_NEGATION.matcher(t).replaceAll("");
        return NEG.matcher(t).find();
    }

    static String lastSentence(String ja) {
        String t = ja.trim();
        String[] parts = t.split("(?<=[。！!？?])");
        for (int i = parts.length - 1; i >= 0; i--) if (!parts[i].trim().isEmpty()) return parts[i].trim();
        return t;
    }

    public static LanguageIR analyze(String text) {
        String raw = text == null ? "" : text;
        String ja = IrText.nfkc(raw);

        LinkedHashMap<WhConcept, String> wh = new LinkedHashMap<>();
        for (Object[] r : WH) {
            Matcher m = ((Pattern) r[1]).matcher(ja);
            if (m.find()) wh.put((WhConcept) r[0], m.group());
        }
        EnumSet<WhConcept> cover = EnumSet.noneOf(WhConcept.class);
        for (Object[] r : WH_COVER) if (((Pattern) r[1]).matcher(ja).find()) cover.add((WhConcept) r[0]);

        EnumSet<Modality> mod = EnumSet.noneOf(Modality.class);
        if (OBLIGATION.matcher(ja).find()) mod.add(Modality.OBLIGATION);
        if (REQUEST.matcher(ja).find()) mod.add(Modality.REQUEST);
        if (CONJECTURE.matcher(ja).find()) mod.add(Modality.CONJECTURE);
        if (PERMISSION.matcher(ja).find()) mod.add(Modality.PERMISSION);

        Tense tense = PAST.matcher(lastSentence(ja)).find() ? Tense.PAST : Tense.NON_PAST;

        EnumSet<Currency> cur = EnumSet.noneOf(Currency.class);
        if (RUPEE.matcher(ja).find()) cur.add(Currency.RUPEE);
        if (YEN.matcher(ja).find()) cur.add(Currency.YEN);
        if (DOLLAR.matcher(ja).find()) cur.add(Currency.DOLLAR);

        String asPlaceText = DEMONYM.matcher(ja).replaceAll("");
        LinkedHashSet<String> places = new LinkedHashSet<>(), asPlace = new LinkedHashSet<>();
        for (String[] p : Places.TABLE) {
            if (ja.contains(p[0])) places.add(p[0]);
            if (asPlaceText.contains(p[0])) asPlace.add(p[0]);
        }
        List<String> names = new ArrayList<>();
        Matcher k = KATAKANA_WORD.matcher(ja);
        while (k.find()) if (!places.contains(k.group()) && !names.contains(k.group())) names.add(k.group());

        return new LanguageIR("ja", raw, hasNegation(raw), QUESTION.matcher(ja.trim()).find(), wh, cover,
                mod, EnumSet.noneOf(Modality.class), tense,
                Numbers.numbers(raw), Numbers.numbers(KanjiNumber.toDigits(raw)), cur,
                places, asPlace, names, KATAKANA_REPEAT.matcher(raw).find());
    }
}
