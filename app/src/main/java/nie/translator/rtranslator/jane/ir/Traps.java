package nie.translator.rtranslator.jane.ir;

import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Everyday-conversation reversal traps (owner's general-conversation dictionary, layer 4):
 * もう少し rendered as "finished", 貸す/借りる direction swapped, medicine taken with पिउनु (drink) instead of खानु.
 * Measured on the phrasebook pairs: 0 false positives.
 */
public final class Traps {
    private Traps() {}

    private static final Pattern JA_MORE = Pattern.compile("もう少し|もうちょっと|あと少し|もう一度|もういちど");
    private static final Pattern JA_DONE = Pattern.compile("終わ|済|完了|しまい|ました");
    private static final Pattern NE_DONE = Pattern.compile("सकियो|सकिएको|सकिसक्यो|सकिसकेको");
    private static final Pattern JA_LEND_REQ = Pattern.compile("貸して(ください|下さい|もらえ|くれ|いただけ)");
    private static final Pattern JA_BORROW = Pattern.compile("借り(て|ます|たい|る|ても)");
    private static final Pattern NE_TAKE_IMP = Pattern.compile("सापटी\\s*लिनुहोस्");
    private static final Pattern NE_GIVE_SELF = Pattern.compile("सापटी\\s*दि(न्छु|न्छौं|एँ|एको छु)");
    private static final Pattern JA_MEDICINE = Pattern.compile("薬[^。]*飲");
    private static final Pattern NE_DRINK_MEDICINE = Pattern.compile("औषधि[^।]{0,15}पि(उ|औं|ए|इ|यो)");

    /** もう少し / もう一度 (more, again) rendered as "finished" */
    public static boolean adverbReversed(String ja, String ne) {
        return JA_MORE.matcher(ja).find() && !JA_DONE.matcher(ja).find() && NE_DONE.matcher(ne).find();
    }

    /** 貸してください -> "please borrow", or 借ります -> "I will lend" */
    public static boolean directionReversed(String ja, String ne) {
        if (JA_LEND_REQ.matcher(ja).find()) return NE_TAKE_IMP.matcher(ne).find();
        return JA_BORROW.matcher(ja).find() && NE_GIVE_SELF.matcher(ne).find();
    }

    /** 薬を飲む rendered with पिउनु (Nepali takes tablets with खानु) */
    public static boolean medicineDrink(String ja, String ne) {
        return JA_MEDICINE.matcher(ja).find() && NE_DRINK_MEDICINE.matcher(ne).find();
    }

    // --- OJT traps (owner's OJT dictionary, layer 4)
    private static final Pattern JA_ALONE = Pattern.compile("一人で|ひとりで|自分で");
    private static final Pattern JA_SELFWILL = Pattern.compile("勝手|好き");
    private static final Pattern NE_SELFWILL = Pattern.compile("आफूखुसी|मनपरी");
    private static final Pattern JA_USED_TO = Pattern.compile("慣れ");
    private static final Pattern JA_CARELESS = Pattern.compile("油断|不注意|雑");
    private static final Pattern NE_CARELESS = Pattern.compile("लापरवाही|हेलचेक्र्याइँ");
    private static final Pattern JA_ADVISED = Pattern.compile("注意され|注意した|注意を受け|指導され");
    private static final Pattern JA_ANGER = Pattern.compile("怒|叱");
    private static final Pattern NE_ANGER = Pattern.compile("रिसा|रिस उठ|गाली");
    private static final Pattern JA_WATCH_OVER = Pattern.compile("見守");
    private static final Pattern NE_WATCH_OVER = Pattern.compile("निगरानी|रेखदेख|छेउ|नजिक|साथ|सहयोग|ध्यान|सुरक्षा");

    /** 一人でやって (on your own) -> आफूखुसी (as you please), 慣れた (used to it) -> लापरवाही (careless),
     *  注意された (was advised) -> रिसाउनुभयो/गाली (was scolded in anger): each turns guidance into its opposite */
    public static boolean guidanceReversed(String ja, String ne) {
        if (JA_ALONE.matcher(ja).find() && !JA_SELFWILL.matcher(ja).find() && NE_SELFWILL.matcher(ne).find()) return true;
        if (JA_USED_TO.matcher(ja).find() && !JA_CARELESS.matcher(ja).find() && NE_CARELESS.matcher(ne).find()) return true;
        return JA_ADVISED.matcher(ja).find() && !JA_ANGER.matcher(ja).find() && NE_ANGER.matcher(ne).find();
    }

    /** 見守る (watch over for safety) rendered as a plain "look" with no supervision / staying-near word */
    public static boolean watchOverLost(String ja, String ne) {
        return JA_WATCH_OVER.matcher(ja).find() && !NE_WATCH_OVER.matcher(ne).find();
    }

    // --- care-floor traps (owner's 4-layer dictionary, layer 4)
    private static final Pattern JA_ITCH = Pattern.compile("かゆ|痒");
    private static final Pattern JA_PAIN = Pattern.compile("痛");
    private static final Pattern NE_PAIN = Pattern.compile("दुख");
    private static final Pattern NE_ITCH = Pattern.compile("चिला");
    private static final Pattern JA_CAUSATIVE = Pattern.compile("飲ませ|食べさせ");
    private static final Pattern NE_SELF_EAT = Pattern.compile("खानुहोस्|पिउनुहोस्");
    private static final Pattern NE_CAUSATIVE = Pattern.compile("खुवा|पिला|लगाउ");
    private static final Pattern JA_BREAK = Pattern.compile("休憩");
    private static final Pattern JA_DAY_OFF = Pattern.compile("休み|休暇|有給");
    private static final Pattern NE_DAY_OFF = Pattern.compile("बिदा");

    /** かゆい -> दुख्छ (pain), 飲ませて -> खानुहोस् (you eat it yourself), 休憩 -> बिदा (a day off) */
    public static boolean careWordSwapped(String ja, String ne) {
        if (JA_ITCH.matcher(ja).find() && !JA_PAIN.matcher(ja).find() && NE_PAIN.matcher(ne).find() && !NE_ITCH.matcher(ne).find()) return true;
        if (JA_CAUSATIVE.matcher(ja).find() && NE_SELF_EAT.matcher(ne).find() && !NE_CAUSATIVE.matcher(ne).find()) return true;
        return JA_BREAK.matcher(ja).find() && !JA_DAY_OFF.matcher(ja).find() && NE_DAY_OFF.matcher(ne).find();
    }

    // --- ne -> ja: "can I or not" (सक्छु कि सक्दिनँ) rendered as a flat "I cannot"
    private static final Pattern NE_CAN_OR_NOT = Pattern.compile("सक्छु कि सक्दिनँ|सक्छु कि सक्दिन");
    private static final Pattern JA_WHETHER = Pattern.compile("かどうか|か不安|か心配|か分から|自信|務まるか|できるか");

    public static boolean alternativeLost(String ne, String ja) {
        return NE_CAN_OR_NOT.matcher(ne).find() && !JA_WHETHER.matcher(ja).find();
    }

    // --- seasonal traps (owner's 365-day dictionary, layer 4)
    private static final Pattern JA_HEATSTROKE = Pattern.compile("熱中症");
    private static final Pattern NE_FEVER = Pattern.compile("ज्वरो");
    private static final Pattern NE_HEAT = Pattern.compile("हिट|गर्मी|लु ");
    private static final Pattern JA_SWITCH_OFF = Pattern.compile("(スイッチ|電源|暖房|ヒーター)[^。]*(切|消)");
    private static final Pattern NE_TURN_ON = Pattern.compile("चलाउनुहोस्|अन गर्नुहोस्");
    private static final Pattern NE_TURN_OFF = Pattern.compile("बन्द|अफ");
    private static final Pattern JA_GETS_COLD = Pattern.compile("冷える|冷えない|冷え込");
    private static final Pattern NE_MAKE_COLD = Pattern.compile("चिसो बनाउनुहोस्|चिसो पार्नुहोस्");

    /** 熱中症 -> ज्वरो (fever), turn the heater OFF -> turn it ON, (the body) gets cold -> make it cold */
    public static boolean seasonalReversed(String ja, String ne) {
        if (JA_HEATSTROKE.matcher(ja).find() && NE_FEVER.matcher(ne).find() && !NE_HEAT.matcher(ne).find()) return true;
        if (JA_SWITCH_OFF.matcher(ja).find() && NE_TURN_ON.matcher(ne).find() && !NE_TURN_OFF.matcher(ne).find()) return true;
        return JA_GETS_COLD.matcher(ja).find() && NE_MAKE_COLD.matcher(ne).find();
    }

    // --- polysemous care words: the Nepali shows only the wrong sense (熱を下げる as "take away", 布団をかける as "phone")
    private static final String[][] SENSES = {
            {"下げる(熱・血圧)", "(熱|血圧|体温|熱さまし)[^。]{0,6}下げ", "लैजा|हटाउ", "घट|कम"},
            {"下げる(食器)", "(食器|お皿|皿|お膳|トレー|下膳)[^。]{0,6}下げ|下膳", "घटाउ|कम गर", "लैजा|हटाउ|उठाउ|सफा"},
            {"かける(声)", "声を?かけ|声かけ", "फोन", "बोला|कुरा|भन"},
            {"かける(布団)", "(布団|毛布|タオルケット|ひざ掛け|膝掛け)[^。]{0,4}かけ", "फोन|बोला", "ओढा|ढाक|राख"},
            {"上がる(熱)", "熱が上が", "माथि", "बढ|आयो|आउ"},
            {"出る(便)", "(便|うんち|おしっこ|尿)が出", "बाहिर", "दिसा|पिसाब|मल|मूत्र"}
    };
    private static final Pattern[][] SENSE_P = new Pattern[SENSES.length][];
    static {
        for (int i = 0; i < SENSES.length; i++)
            SENSE_P[i] = new Pattern[]{Pattern.compile(SENSES[i][1]), Pattern.compile(SENSES[i][2]), Pattern.compile(SENSES[i][3])};
    }

    /** @return the sense label when the Nepali carries only the wrong sense, else null */
    public static String wordSense(String ja, String ne) {
        for (int i = 0; i < SENSES.length; i++)
            if (SENSE_P[i][0].matcher(ja).find() && SENSE_P[i][1].matcher(ne).find() && !SENSE_P[i][2].matcher(ne).find()) return SENSES[i][0];
        return null;
    }

    // --- opposite pairs: the source says one side and the output only the other (昨日 -> भोलि, 右 -> बायाँ)
    private static final String[][] ANTONYMS = {
            {"昨日/明日", "昨日|きのう", "हिजो", "明日|あした", "भोलि"},
            {"右/左", "右", "दायाँ|दाहिने", "左", "बायाँ|देब्रे"},
            {"朝/夜", "朝", "बिहान", "夜|晩|夕方", "बेलुका|राति|साँझ"},
            {"熱/冷", "熱い|暑い|あつい", "तातो|गर्मी", "冷たい|寒い|つめたい|さむい", "चिसो|जाडो"}
    };
    private static final Pattern[][] ANTONYM_P = new Pattern[ANTONYMS.length][];
    static {
        for (int i = 0; i < ANTONYMS.length; i++)
            ANTONYM_P[i] = new Pattern[]{Pattern.compile(ANTONYMS[i][1]), Pattern.compile(ANTONYMS[i][2]), Pattern.compile(ANTONYMS[i][3]), Pattern.compile(ANTONYMS[i][4])};
    }

    /** @param srcIsJa true for ja -> ne; @return the pair label when swapped, else null */
    public static String antonymSwapped(String src, String hyp, boolean srcIsJa) {
        for (int i = 0; i < ANTONYMS.length; i++) {
            Pattern ja1 = ANTONYM_P[i][0], ne1 = ANTONYM_P[i][1], ja2 = ANTONYM_P[i][2], ne2 = ANTONYM_P[i][3];
            Pattern[][] sides = srcIsJa ? new Pattern[][]{{ja1, ne1, ja2, ne2}, {ja2, ne2, ja1, ne1}}
                                        : new Pattern[][]{{ne1, ja1, ne2, ja2}, {ne2, ja2, ne1, ja1}};
            for (Pattern[] sd : sides)
                if (sd[0].matcher(src).find() && !sd[2].matcher(src).find() && sd[3].matcher(hyp).find() && !sd[1].matcher(hyp).find())
                    return ANTONYMS[i][0];
        }
        return null;
    }

    // --- counter (助数詞) swapped: the same number with another unit (3人 -> ३ पटक). Mirrors metrics._counter_mismatch.
    private static final String[][] COUNTERS = {
        {"PERSON", "人|名", "जना"}, {"TIMES", "回|度", "पटक|चोटि|पल्ट"}, {"DAY", "日|日間", "दिन|गते"},
        {"HOUR", "時間", "घण्टा"}, {"MINUTE", "分", "मिनेट"}, {"YEN", "円", "येन"}, {"KG", "キロ|kg", "किलो|केजी"},
        {"TIME_OCLOCK", "時", "बजे|बज्यो"}, {"AGE", "歳|才", "वर्ष|बर्ष|उमेर"},
        {"MONTH", "月|ヶ月|か月", "महिना|महिने|मार्च|अप्रिल|जनवरी"}};
    private static final Pattern JA_COUNTER = Pattern.compile("([0-9０-９]+)\\s*(人|名|回|度|日間|日|時間|時|分|円|キロ|kg|歳|才|ヶ月|か月|月)");
    private static final Pattern NE_COUNTER = Pattern.compile("([0-9]+)\\s*(\\S+)");

    private static java.util.Map<String, java.util.Set<String>> units(String t, boolean ja) {
        java.util.Map<String, java.util.Set<String>> out = new java.util.HashMap<>();
        Matcher m = (ja ? JA_COUNTER : NE_COUNTER).matcher(t);
        while (m.find()) {
            String d = m.group(1);
            StringBuilder sb = new StringBuilder();
            for (char c : d.toCharArray()) sb.append(c >= '０' && c <= '９' ? (char) ('0' + c - '０') : c);
            String n = sb.toString().replaceFirst("^0+(?=.)", "");
            for (String[] c : COUNTERS) {
                boolean hit = ja ? m.group(2).matches(c[1]) : Pattern.compile("^(?:" + c[2] + ")").matcher(m.group(2)).find();
                if (hit) out.computeIfAbsent(n, k -> new java.util.HashSet<>()).add(c[0]);
            }
        }
        return out;
    }

    /** "3" when 3人 became ३ पटक etc., else null. ja is the kanji-to-digits form of the source. */
    public static String counterMismatch(String jaDigits, String ne) {
        StringBuilder t = new StringBuilder();
        for (char c : ne.toCharArray()) t.append(c >= '\u0966' && c <= '\u096F' ? (char) ('0' + c - 0x966) : c);
        java.util.Map<String, java.util.Set<String>> a = units(jaDigits, true), b = units(t.toString(), false);
        for (java.util.Map.Entry<String, java.util.Set<String>> e : a.entrySet()) {
            java.util.Set<String> nb = b.get(e.getKey());
            if (nb != null && java.util.Collections.disjoint(e.getValue(), nb)) return e.getKey();
        }
        return null;
    }
}
