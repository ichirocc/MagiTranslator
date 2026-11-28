package nie.translator.rtranslator.jane.ir;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nie.translator.rtranslator.jane.text.Numbers;

import static nie.translator.rtranslator.jane.ir.IrText.NE_CH;
import static nie.translator.rtranslator.jane.ir.IrText.NE_L;
import static nie.translator.rtranslator.jane.ir.IrText.NE_R;

/**
 * Nepali Stage 1 analyzer: word-level rules over NFC Devanagari, no stemmer yet. Separate from
 * JaAnalyzer (SPEC v2 part 2, 2.1): Nepali rules are not derived from Japanese ones.
 */
public final class NeAnalyzer {
    private NeAnalyzer() {}

    // --- negation: suffix forms and the negative prefix न- on verbs
    private static final Pattern NEG_WORD = Pattern.compile(
            "(छैन|छैनन्|छैनौं|होइन|होइनन्|हुँदैन|हुन्न|दैन|दैनन्|दैनौं|दैनौ|दिनँ|दिनौं|हुन्नँ|न्नँ)$");   // जाँदिनँ, गर्दैन, सक्दैनन्
    // colloquial negative future: a vowel sign + न्न (खान्न, जान्न, हुन्न). A consonant before न्न is a
    // noun/adjective (प्रसन्न, सम्पन्न); भिन्न-type words are listed explicitly.
    private static final Pattern NEG_NN = Pattern.compile("[\\u093E-\\u094C\\u0901\\u0902]न्नँ?$");
    private static final List<String> NN_NOT_NEGATION = Arrays.asList("भिन्न", "विभिन्न", "खिन्न", "छिन्न", "छिन्नभिन्न",
            "सुन्न", "किन्न", "बुन्न", "चिन्न", "थुन्न");   // infinitives of -न् stems (सुन्+न "to hear"), not negation
    // tag question ("…, होइन?" = "…, right?") and "X कि Y-दैन" ("whether or not") are not negated statements
    private static final Pattern TAG_QUESTION = Pattern.compile("[,،]?\\s*(?:होइन|हैन)\\s*(?:र|त|नि)?\\s*[?？]");
    private static final Pattern ALTERNATIVE = Pattern.compile("(\\S+)\\s+कि\\s+\\S+");
    // negative past: गएन / आएन / गरेन / गरेनन् (3rd person), गइनँ / गरिनँ / गइनौं (1st/2nd person).
    // The bare ि+न ending is "day"-like (दिन, बिन) so it only counts with ँ / न् / ौं; loanwords in े+न are listed.
    private static final Pattern NEG_PAST = Pattern.compile("(?:[एइ]न|ेन|िनँ|िनन्|िनौं|िनौ)(?:ँ|न्|ौं|ौँ|ौ)?$");
    private static final List<String> NEG_PAST_NOT = Arrays.asList("येन", "ट्रेन", "प्लेन", "ब्रेन", "स्पेन", "चेन", "लेन", "सेन", "मेन", "जेन", "डेन", "केन", "पेन", "टेन", "हेन", "क्रेन", "भेन", "सुमिमासेन", "ब्योउइन");
    // न- before a consonant (नगर्नुहोस्, नजानु) or an independent vowel (नआउनु, नआएको)
    private static final Pattern NEG_PREFIX = Pattern.compile("^न(?:[क-ह]|[अआइईउऊएऐओऔ])");
    // nouns / adjectives / names that start with न + consonant. Matched as prefixes, so they must not
    // swallow verb negations: "नगर" (city) is listed only with its case endings, because
    // नगर्नुहोस् / नगरेको (do not / not done) are real negations.
    private static final String[] NOT_NEGATION = {"नमस्ते", "नमस्कार", "नयाँ", "नजिक", "नदी", "नक्सा", "नक्शा", "नम्बर", "नरम", "नर्स", "नल", "नसा", "नक्कली", "नतिजा", "नर्सरी",
            "नगरपालिका", "नगरमा", "नगरको", "नगरका", "नगरले", "नगरबाट", "नगरवासी", "नगद", "नमुना", "नम्र", "नजर", "नरक", "नमन", "नशा", "नकल", "नरिवल", "नवीन", "नवम्बर",
            "नब्बे", "नहर", "नवजात", "नक्सली", "नमक", "नवौं", "नरेन्द्र", "नरेश", "नवराज", "नकारात्मक", "नक्षत्र", "नक्कल", "नवशिशु", "नङ"};
    private static final Pattern WORD = Pattern.compile("[\\u0900-\\u0963\\u0966-\\u097F]+");   // excludes the danda । ॥
    // whole-word question words: plain substrings matched किन्छु (I buy), किनभने (because), कुनै (any),
    // कतिपय (some) and कहिलेकाहीं (sometimes)
    static final Pattern QUESTION = Pattern.compile(
            "(\\?|？|को हो|(?<![\\u0900-\\u0963\\u0966-\\u097F])(?:के|किन|कसरी|कस्तो|कस्ती|कस्ता|कुन|कुनचाहिँ|कुनचाहिं|कहाँ\\S*|कति(?!पय)\\S*|कहिले(?!काहीं|काहीँ|काहिँ|काहिं))(?![\\u0900-\\u0963\\u0966-\\u097F]))");

    /** stated question words. के is left out: it is also the yes/no question particle (के तपाईं आउनुहुन्छ?). */
    private static final Object[][] WH = {
            {WhConcept.QUANTITY, Pattern.compile(NE_L + "कति(?!पय)\\S*")},
            {WhConcept.WHY, Pattern.compile(NE_L + "किन" + NE_R)},
            {WhConcept.WHERE, Pattern.compile(NE_L + "कह[ाँां]{2}\\S*")},
            {WhConcept.WHEN, Pattern.compile(NE_L + "कहिले(?!काहीं|काहीँ|काहिँ|काहिं)" + NE_R)},
            {WhConcept.HOW, Pattern.compile(NE_L + "कसरी" + NE_R)},
    };
    private static Pattern word(String alt) { return Pattern.compile("(?<![" + NE_CH + "])(?:" + alt + ")(?![" + NE_CH + "])"); }
    /** Nepali forms that can render a Japanese question word */
    private static final Object[][] WH_COVER = {
            {WhConcept.TIME_OCLOCK, word("बजे\\S*|बज्यो|बेला\\S*")},
            {WhConcept.COUNT_PERSON, word("कति|कतिजना")},
            {WhConcept.PRICE, word("कति\\S*|मूल्य|दाम|पैसा")},
            {WhConcept.WHO, word("को|कसले|कसको|कसलाई|कोसँग|कोही")},
            {WhConcept.WHERE, word("कहाँ\\S*|कता\\S*|कुन ठाउँ\\S*")},
            {WhConcept.WHEN, word("कहिले\\S*|कुन दिन\\S*|कति बेला|कति बजे")},
            {WhConcept.WHY, word("किन")},
            {WhConcept.HOW, word("कसरी|के गर\\S*")},
            {WhConcept.WHICH, word("कुन\\S*|कुनचाहिँ|कुनचाहिं|कि\\s.+\\sकि")},
    };

    // modality cover: lenient (any accepted form passes)
    private static final Pattern OBLIGATION = Pattern.compile("(पर्छ|पर्छन्|पर्ने|पर्यो|पर्‍यो|पर्थ्यो|पर्दछ|पर्नेछ|पर्नुहुन्छ|नुपर|चाहिन्छ|अनिवार्य)");
    private static final Pattern REQUEST = Pattern.compile("(नुहोस्|नुस्|नुहोला|होस्|देऊ|देउ|दिनु|कृपया|अनुरोध|सक्नुहुन्छ|नुहुन्छ\\s*\\?|सक्छौ|ऊ(?![" + NE_CH + "])|ओ(?![" + NE_CH + "]))");
    private static final Pattern CONJECTURE = Pattern.compile("(होला|ला(?![" + NE_CH + "])|सक्छ|सक्छन्|जस्तो|जस्तै|लाग्छ|लाग्यो|ठान्छु|सायद|शायद|पक्का|हुनुपर्छ|होइन र)");
    private static final Pattern PERMISSION = Pattern.compile("(हुन्छ|सकिन्छ|सक्नुहुन्छ|सक्छ|ठीक|ठिक|अनुमति|पाइन्छ|पर्दैन|मिल्छ)");
    /** past, perfect and negative past (गयो, गए, गएका छन्, थियो, गर्नुभयो, गएन). Vowel-sign ए (गरे) is not
     *  used: it also ends ergative nouns (रामले). */
    private static final Pattern PAST = word("\\S*(?:थियो|थिए|थिइन्|थिएँ|थियौं|ेँ|ें|यौं|यौँ|थ्यो|यो|एको|एका|एकी|भयो|भएको|सक्यो|सके|इन्|इनँ|इनौं|ए|एँ|एन|एनन्|ेन|ेनन्|ेको|ेका|ेकी|्यो|‍यो)");
    private static final List<String> PAST_NOT = Arrays.asList("यो", "त्यो", "जो", "उनको");

    private static final Pattern RUPEE = Pattern.compile("रुपैयाँ|रुपैयां|रुपियाँ|रुपियां|" + NE_L + "(रु|रू)\\.?\\s*[0-9०-९]|NPR|Rs\\.?\\s*[0-9]");
    private static final Pattern YEN = Pattern.compile(NE_L + "(येन|जापानी येन)" + NE_R);
    private static final Pattern DOLLAR = Pattern.compile(NE_L + "डलर");

    public static boolean hasNegation(String ne) {
        String t = ALTERNATIVE.matcher(TAG_QUESTION.matcher(IrText.nfc(ne)).replaceAll("")).replaceAll("$1");
        Matcher m = WORD.matcher(t);
        while (m.find()) {
            String w = m.group();
            if (w.equals("दिन")) continue;                 // "day"
            if (NEG_WORD.matcher(w).find()) return true;
            if (w.length() >= 3 && NEG_PAST.matcher(w).find() && !NEG_PAST_NOT.contains(w)) return true;
            if (NEG_NN.matcher(w).find() && !NN_NOT_NEGATION.contains(w)) return true;
            if (w.length() >= 4 && w.charAt(0) == 'न' && (w.endsWith("ईकन") || w.endsWith("इकन") || w.endsWith("ीकन"))) return true;   // नलुकाईकन "without hiding"
            if (w.length() >= 3 && NEG_PREFIX.matcher(w).find()) {
                boolean excluded = false;
                for (String x : NOT_NEGATION) if (w.startsWith(x)) { excluded = true; break; }
                if (!excluded) return true;
            }
        }
        return false;
    }

    private static boolean hasPast(String nfc) {
        Matcher m = PAST.matcher(nfc);
        while (m.find()) if (!PAST_NOT.contains(m.group())) return true;
        return false;
    }

    public static LanguageIR analyze(String text) {
        String raw = text == null ? "" : text;
        String ne = IrText.nfc(raw);

        LinkedHashMap<WhConcept, String> wh = new LinkedHashMap<>();
        for (Object[] r : WH) {
            Matcher m = ((Pattern) r[1]).matcher(ne);
            if (m.find()) wh.put((WhConcept) r[0], m.group());
        }
        EnumSet<WhConcept> cover = EnumSet.noneOf(WhConcept.class);
        for (Object[] r : WH_COVER) if (((Pattern) r[1]).matcher(ne).find()) cover.add((WhConcept) r[0]);

        EnumSet<Modality> modCover = EnumSet.noneOf(Modality.class);
        if (OBLIGATION.matcher(ne).find()) modCover.add(Modality.OBLIGATION);
        if (REQUEST.matcher(ne).find()) modCover.add(Modality.REQUEST);
        if (CONJECTURE.matcher(ne).find()) modCover.add(Modality.CONJECTURE);
        if (PERMISSION.matcher(ne).find()) modCover.add(Modality.PERMISSION);

        EnumSet<Currency> cur = EnumSet.noneOf(Currency.class);
        if (RUPEE.matcher(ne).find()) cur.add(Currency.RUPEE);
        if (YEN.matcher(ne).find()) cur.add(Currency.YEN);
        if (DOLLAR.matcher(ne).find()) cur.add(Currency.DOLLAR);

        String canon = IrText.canon(raw);
        LinkedHashSet<String> places = new LinkedHashSet<>();
        for (String[] p : Places.TABLE) {
            for (int i = 1; i < p.length; i++) {
                if (Pattern.compile(NE_L + Pattern.quote(IrText.canon(p[i])) + "(?!ी)").matcher(canon).find()) { places.add(p[0]); break; }
            }
        }
        List<String> nums = Numbers.numbers(raw);
        return new LanguageIR("ne", raw, hasNegation(raw), QUESTION.matcher(raw).find(), wh, cover,
                EnumSet.noneOf(Modality.class), modCover, hasPast(ne) ? Tense.PAST : Tense.UNKNOWN,
                nums, new ArrayList<>(nums), cur, places, new LinkedHashSet<>(), new ArrayList<>(), false);
    }
}
