package nie.translator.rtranslator.jane.ir;

import java.util.regex.Pattern;

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
}
