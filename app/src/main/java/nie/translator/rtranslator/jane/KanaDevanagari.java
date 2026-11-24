package nie.translator.rtranslator.jane;

import java.util.HashMap;
import java.util.Map;

/**
 * Rule-based Katakana -> Devanagari transliteration for proper names, which NMT often mistranslates
 * literally or drops. Follows common Nepali spelling of Japanese names (e.g. タナカ -> तानाका).
 */
public final class KanaDevanagari {
    private static final Map<String, String> SYL = new HashMap<>();
    private static final Map<Character, String> LONG = new HashMap<>();

    private static void put(String kana, String dev) { SYL.put(kana, dev); }

    static {
        String[][] base = {
                {"ア","अ"},{"イ","इ"},{"ウ","उ"},{"エ","ए"},{"オ","ओ"},
                {"カ","का"},{"キ","कि"},{"ク","कु"},{"ケ","के"},{"コ","को"},
                {"ガ","गा"},{"ギ","गि"},{"グ","गु"},{"ゲ","गे"},{"ゴ","गो"},
                {"サ","सा"},{"シ","शि"},{"ス","सु"},{"セ","से"},{"ソ","सो"},
                {"ザ","जा"},{"ジ","जि"},{"ズ","जु"},{"ゼ","जे"},{"ゾ","जो"},
                {"タ","ता"},{"チ","चि"},{"ツ","त्सु"},{"テ","ते"},{"ト","तो"},
                {"ダ","दा"},{"ヂ","जि"},{"ヅ","जु"},{"デ","दे"},{"ド","दो"},
                {"ナ","ना"},{"ニ","नि"},{"ヌ","नु"},{"ネ","ने"},{"ノ","नो"},
                {"ハ","हा"},{"ヒ","हि"},{"フ","फु"},{"ヘ","हे"},{"ホ","हो"},
                {"バ","बा"},{"ビ","बि"},{"ブ","बु"},{"ベ","बे"},{"ボ","बो"},
                {"パ","पा"},{"ピ","पि"},{"プ","पु"},{"ペ","पे"},{"ポ","पो"},
                {"マ","मा"},{"ミ","मि"},{"ム","मु"},{"メ","मे"},{"モ","मो"},
                {"ヤ","या"},{"ユ","यु"},{"ヨ","यो"},
                {"ラ","रा"},{"リ","रि"},{"ル","रु"},{"レ","रे"},{"ロ","रो"},
                {"ワ","वा"},{"ヲ","वो"},{"ヴ","भु"},
                {"キャ","क्या"},{"キュ","क्यु"},{"キョ","क्यो"},
                {"ギャ","ग्या"},{"ギュ","ग्यु"},{"ギョ","ग्यो"},
                {"シャ","शा"},{"シュ","शु"},{"ショ","शो"},{"シェ","शे"},
                {"ジャ","जा"},{"ジュ","जु"},{"ジョ","जो"},{"ジェ","जे"},
                {"チャ","चा"},{"チュ","चु"},{"チョ","चो"},{"チェ","चे"},
                {"ニャ","न्या"},{"ニュ","न्यु"},{"ニョ","न्यो"},
                {"ヒャ","ह्या"},{"ヒュ","ह्यु"},{"ヒョ","ह्यो"},
                {"ビャ","ब्या"},{"ビュ","ब्यु"},{"ビョ","ब्यो"},
                {"ピャ","प्या"},{"ピュ","प्यु"},{"ピョ","प्यो"},
                {"ミャ","म्या"},{"ミュ","म्यु"},{"ミョ","म्यो"},
                {"リャ","र्या"},{"リュ","र्यु"},{"リョ","र्यो"},
                {"ファ","फा"},{"フィ","फि"},{"フェ","फे"},{"フォ","फो"},
                {"ティ","टि"},{"ディ","डि"},{"トゥ","टु"},{"ドゥ","डु"},
                {"ウィ","वि"},{"ウェ","वे"},{"ウォ","वो"},{"ウァ","वा"},
                {"ヴァ","भा"},{"ヴィ","भि"},{"ヴェ","भे"},{"ヴォ","भो"},{"ヴュ","भ्यु"},
                {"ツァ","त्सा"},{"ツィ","त्सि"},{"ツェ","त्से"},{"ツォ","त्सो"},
                {"クァ","क्वा"},{"クィ","क्वि"},{"クェ","क्वे"},{"クォ","क्वो"},{"グァ","ग्वा"},
                {"テュ","ट्यु"},{"デュ","ड्यु"},{"フュ","फ्यु"},{"シィ","सि"},{"ジィ","जि"},
                {"イェ","ये"},{"キェ","क्ये"},{"ギェ","ग्ये"},{"ニェ","न्ये"},{"チィ","चि"},
                {"ァ","अ"},{"ィ","इ"},{"ゥ","उ"},{"ェ","ए"},{"ォ","ओ"},{"ャ","या"},{"ュ","यु"},{"ョ","यो"},
        };
        for (String[] e : base) put(e[0], e[1]);
        // vowel sign of the previous syllable -> its long form for "ー"
        LONG.put('ि', "ी"); LONG.put('ु', "ू"); LONG.put('े', "े"); LONG.put('ो', "ो"); LONG.put('ा', "ा");
    }

    private KanaDevanagari() {}

    public static boolean isKatakana(char c) {
        return (c >= 'ァ' && c <= 'ヺ') || c == 'ー';
    }

    /** Returns null when the word contains something that cannot be transliterated. */
    public static String transliterate(String kana) {
        StringBuilder out = new StringBuilder();
        boolean geminate = false;
        int i = 0;
        while (i < kana.length()) {
            char c = kana.charAt(i);
            if (c == 'ッ') { geminate = true; i++; continue; }
            if (c == 'ー') {
                if (out.length() == 0) return null;
                char last = out.charAt(out.length() - 1);
                String lng = LONG.get(last);
                if (lng != null) out.setCharAt(out.length() - 1, lng.charAt(0));
                else if (last == 'इ') out.setCharAt(out.length() - 1, 'ई');
                else if (last == 'उ') out.setCharAt(out.length() - 1, 'ऊ');
                else if (last == 'अ') out.setCharAt(out.length() - 1, 'आ');
                i++;
                continue;
            }
            if (c == 'ン') {
                out.append(i + 1 < kana.length() ? "न्" : "न");
                i++;
                continue;
            }
            String syl = null;
            if (i + 1 < kana.length()) syl = SYL.get(kana.substring(i, i + 2));
            int len = 2;
            if (syl == null) { syl = SYL.get(kana.substring(i, i + 1)); len = 1; }
            if (syl == null) return null;
            if (geminate) {
                // double the first consonant with a virama: ッタ -> त्ता
                char first = syl.charAt(0);
                if (first >= 'क' && first <= 'ह') out.append(first).append('्');
                geminate = false;
            }
            out.append(syl);
            i += len;
        }
        return out.length() == 0 ? null : out.toString();
    }
}
