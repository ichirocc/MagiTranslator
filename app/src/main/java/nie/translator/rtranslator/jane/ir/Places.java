package nie.translator.rtranslator.jane.ir;

/**
 * Known place names: {Japanese, Nepali stems...}. Nepali stems match at word start (case markers
 * attach: नेपालबाट) but नेपाल must not match नेपाली (the demonym / language).
 */
public final class Places {
    private Places() {}

    static final String[][] TABLE = {
            {"ネパール", "नेपाल"}, {"カトマンズ", "काठमाडौं", "काठमाडौँ"}, {"ポカラ", "पोखरा"}, {"日本", "जापान"},
            {"東京", "टोकियो", "तोक्यो"}, {"大阪", "ओसाका"}, {"インド", "भारत", "इन्डिया"}, {"中国", "चीन"},
            {"アメリカ", "अमेरिका"}, {"ネバダ", "नेभाडा"}, {"シアトル", "सियाटल"}, {"カタルーニャ", "क्याटालोनिया"},
            {"ルンビニ", "लुम्बिनी"}, {"チトワン", "चितवन"}, {"エベレスト", "सगरमाथा", "एभरेस्ट"},
    };

    /** Japanese keys in table order. */
    public static java.util.List<String> keys() {
        java.util.List<String> k = new java.util.ArrayList<>();
        for (String[] p : TABLE) k.add(p[0]);
        return k;
    }

    /** All Nepali stems of the known places. */
    public static java.util.List<String> nepaliStems() {
        java.util.List<String> k = new java.util.ArrayList<>();
        for (String[] p : TABLE) for (int i = 1; i < p.length; i++) k.add(p[i]);
        return k;
    }
}
