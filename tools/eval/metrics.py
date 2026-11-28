"""Translation quality metrics for ja<->ne evaluation (SPEC §20.2).

Pure Python 3, no dependencies. Negation / number logic is a faithful port of
app/src/main/java/nie/translator/rtranslator/jane/TranslationValidator.java and
QualityScorer.numbers(); keep both in sync when either changes.
"""
import re
import string
import unicodedata
from collections import Counter

# ---------------------------------------------------------------- chrF++
CHAR_ORDER, WORD_ORDER, BETA = 6, 2, 2
_EPS = 1e-16


def _char_ngrams(s, n):
    s = "".join(s.split())  # sacrebleu: whitespace removed for char n-grams
    return Counter(s[i:i + n] for i in range(len(s) - n + 1))


def _words(s):
    """sacrebleu chrF++ word split: one punctuation char split off a token edge."""
    out = []
    for tok in s.split():
        if len(tok) == 1:
            out.append(tok)
        elif tok[-1] in string.punctuation:
            out.extend([tok[:-1], tok[-1]])
        elif tok[0] in string.punctuation:
            out.extend([tok[0], tok[1:]])
        else:
            out.append(tok)
    return out


def _word_ngrams(words, n):
    return Counter(tuple(words[i:i + n]) for i in range(len(words) - n + 1))


def chrf_stats(hyp, ref, char_order=CHAR_ORDER, word_order=WORD_ORDER):
    """[hyp_count, ref_count, match] per n-gram order (chars 1..6, then words 1..2)."""
    stats = []
    for n in range(1, char_order + 1):
        h, r = _char_ngrams(hyp, n), _char_ngrams(ref, n)
        stats.append([sum(h.values()), sum(r.values()), sum((h & r).values())])
    hw, rw = _words(hyp), _words(ref)
    for n in range(1, word_order + 1):
        h, r = _word_ngrams(hw, n), _word_ngrams(rw, n)
        stats.append([sum(h.values()), sum(r.values()), sum((h & r).values())])
    return stats


def _f_from_stats(stats, beta=BETA):
    """sacrebleu 2.x default (effective-order smoothing): average P and R over orders, then F_beta."""
    factor = beta ** 2
    avg_p = avg_r = 0.0
    eff = 0
    for n_hyp, n_ref, n_match in stats:
        if n_hyp > 0 and n_ref > 0:
            avg_p += n_match / n_hyp
            avg_r += n_match / n_ref
            eff += 1
    if eff == 0:
        return 0.0
    avg_p /= eff
    avg_r /= eff
    if avg_p + avg_r == 0:
        return 0.0
    return 100.0 * (1 + factor) * avg_p * avg_r / (factor * avg_p + avg_r)


def chrf_pp_sentence(hyp, ref):
    return _f_from_stats(chrf_stats(hyp, ref))


def chrf_pp_corpus(hyps, refs):
    """Corpus chrF++ (stats summed over sentences, as sacrebleu does)."""
    total = None
    for h, r in zip(hyps, refs):
        st = chrf_stats(h, r)
        total = st if total is None else [[a + b for a, b in zip(x, y)] for x, y in zip(total, st)]
    return 0.0 if total is None else _f_from_stats(total)


# ---------------------------------------------------------------- numbers (QualityScorer.numbers)
_NUMBER = re.compile(r"[0-9]+(?:\.[0-9]+)?")  # Java \d is ASCII-only by default


_NE_LETTER = "\u0900-\u0963\u0966-\u097F"
# whole Nepali number words 1-10 (छ = 6 is left out: it is also the copula "is")
_NE_NUMBER_VALUE = {"एक": "1", "दुई": "2", "तीन": "3", "चार": "4", "पाँच": "5", "सात": "7", "आठ": "8",
                    "नौ": "9", "दश": "10", "दस": "10"}
_NE_NUMBER_WORD = re.compile("(?<![" + _NE_LETTER + "])(" + "|".join(_NE_NUMBER_VALUE) + ")(?![" + _NE_LETTER + "])")


def numbers(s):
    if s is None:
        return []
    t = unicodedata.normalize("NFKC", s)
    t = "".join(chr(ord("0") + ord(c) - 0x0966) if 0x0966 <= ord(c) <= 0x096F else c for c in t)
    t = re.sub(r"(?<=[0-9]),(?=[0-9]{3})", "", t)
    t = _NE_NUMBER_WORD.sub(lambda m: _NE_NUMBER_VALUE[m.group(1)], t)
    return [re.sub(r"^0+(?=[0-9])", "", m.group()) for m in _NUMBER.finditer(t)]


# ---------------------------------------------------------------- kanji numerals (KanjiNumber.toDigits)
_DIGITS = "〇一二三四五六七八九"
_NUM_COUNTER = re.compile("([〇一二三四五六七八九十百千万]+)(?=時|分|秒|円|人|名|日|月|年|歳|才|個|回|本|枚|階|週間|時間|ヶ月|か月|キロ|メートル)")
_HALF_HOUR = re.compile(r"([0-9]+)時半")
_IDIOMS = ("一人前", "一人っ子", "一人ひとり", "一日中", "一時的", "一回り", "二人三脚", "一枚岩", "十人十色", "一年中",
           "三日月", "三日坊主", "万年筆", "万年床", "一時停止", "一時期", "一時帰国", "一時保存", "二日酔い", "百人一首", "五月雨", "五月蝿")
_JUPPUN_AFTER = re.compile("分(?:間|後|前|以内|ほど|くらい|ぐらい|程度|おき|ごと)")


def _parse_kanji(k):
    if not any(c in k for c in "十百千万"):
        v = 0
        for c in k:
            d = _DIGITS.find(c)
            if d < 0:
                return -1
            v = v * 10 + d
        return v
    total = section = num = 0
    for c in k:
        d = _DIGITS.find(c)
        if d >= 0:
            num = d
            continue
        unit = {"十": 10, "百": 100, "千": 1000}.get(c)
        if unit:
            section += (num or 1) * unit
            num = 0
        elif c == "万":
            total += (section + num or 1) * 10000
            section = num = 0
        else:
            return -1
    return total + section + num


def kanji_to_digits(text):
    """Port of KanjiNumber.toDigits: 三人 -> 3人, 三時半 -> 3時30分; idioms and 十分 (= enough) kept."""
    saved = []
    for idiom in _IDIOMS:
        if idiom in text:
            saved.append(idiom)
            text = text.replace(idiom, "\ue000%d\ue001" % (len(saved) - 1))

    def repl(m):
        k = m.group(1)
        if k == "万":
            return k
        if k == "十" and text.startswith("分", m.end()):
            after_hour = m.start() > 0 and text[m.start() - 1] == "時"
            if not (after_hour or _JUPPUN_AFTER.match(text, m.end())):
                return k
        v = _parse_kanji(k)
        return k if v < 0 else str(v)

    out = _HALF_HOUR.sub(r"\1時30分", _NUM_COUNTER.sub(repl, text))
    for i, idiom in enumerate(saved):
        out = out.replace("\ue000%d\ue001" % i, idiom)
    return out


def numbers_preserved(src, hyp, hyp_lang=None):
    """True if every source number appears in hyp (multiset). None if source has no numbers.
    With hyp_lang == "ja", kanji numerals in hyp are converted first (as validateNeToJa does)."""
    a = numbers(src)
    if not a:
        return None
    rest = list(numbers(kanji_to_digits(hyp) if hyp_lang == "ja" else hyp))
    for n in a:
        if n in rest:
            rest.remove(n)
        else:
            return False
    return True


# ---------------------------------------------------------------- negation (TranslationValidator)
JA_NEG = re.compile(r"(ない|なかった|なくて|ないで|ません|ませんでした|(?:では|じゃ)なく|ず(?=[、。に]|$)|ぬ(?=[、。]|$))")
JA_NOT_NEGATION = re.compile(
    r"((?:少|すく|危|あぶ|切|せつ|だらし|つまら|くだら|もったい|勿体|仕方が?|しかたが?|間違い|違い|半端|きた|おさ|はか|儚|ぎこち|"
    r"情け|なさけ|頼り|たより|物足り|もの足り|ものたり|申し訳|申しわけ|そっけ|素っ気|あどけ|あっけ|呆気|味気|あじけ)な(?:い|く|かっ|さ|け)|"
    r"汚い|幼い|すみません|すいません|申し訳(あり|ござい)ません|かもしれ(ない|ません)|なければ(なら|なり|いけ)(ない|ません)|なくては(なら|なり|いけ)(ない|ません)|ないと(いけ|だめ|ダメ)(ない|ません)?|ませんか(?!ら)|(?:じゃ|では)ない(?:です|でしょう)?か|ないか(?=[、]?(?:確認|見|観察|チェック|調べ|伸ばし|直し))|ないうちに|手が離せ(?:ない|ません)|変わり(?:は)?(?:ない|ありません))")
NE_NEG_WORD = re.compile(r"(छैन|छैनन्|छैनौं|होइन|होइनन्|हुँदैन|हुन्न|दैन|दैनन्|दैनौं|दैनौ|दिनँ|दिनौं|हुन्नँ|न्नँ)$")
NE_NEG_NN = re.compile(r"[\u093E-\u094C\u0901\u0902]न्नँ?$")
NE_NN_NOT_NEGATION = {"भिन्न", "विभिन्न", "खिन्न", "छिन्न", "छिन्नभिन्न", "सुन्न", "किन्न", "बुन्न", "चिन्न", "थुन्न"}
NE_TAG_QUESTION = re.compile(r"[,،]?\s*(?:होइन|हैन)\s*(?:र|त|नि)?\s*[?？]")
NE_ALTERNATIVE = re.compile(r"(\S+)\s+कि\s+\S+")
# negative past: गएन / आएन / गरेन (3rd person), गइनँ / गरिनँ (1st/2nd person); loanwords in े+न listed
NE_NEG_PAST = re.compile("(?:[एइ]न|ेन|िनँ|िनन्|िनौं|िनौ)(?:ँ|न्|ौं|ौँ|ौ)?$")
NE_NEG_PAST_NOT = {"येन", "ट्रेन", "प्लेन", "ब्रेन", "स्पेन", "चेन", "लेन", "सेन", "मेन", "जेन", "डेन", "केन", "पेन", "टेन",
                   "हेन", "क्रेन", "भेन", "सुमिमासेन", "ब्योउइन"}
NE_NEG_PREFIX = re.compile(r"^न(?:[क-ह]|[अआइईउऊएऐओऔ])")
NE_NOT_NEGATION = ("नमस्ते", "नमस्कार", "नयाँ", "नजिक", "नदी", "नक्सा", "नक्शा", "नम्बर", "नरम", "नर्स", "नल", "नसा",
                   "नक्कली", "नतिजा", "नर्सरी", "नगरपालिका", "नगरमा", "नगरको", "नगरका", "नगरले", "नगरबाट", "नगरवासी",
                   "नगद", "नमुना", "नम्र", "नजर", "नरक", "नमन", "नशा", "नकल", "नरिवल", "नवीन", "नवम्बर", "नब्बे", "नहर",
                   "नवजात", "नक्सली", "नमक", "नवौं", "नरेन्द्र", "नरेश", "नवराज", "नकारात्मक", "नक्षत्र", "नक्कल", "नवशिशु", "नङ")
NE_WORD = re.compile("[ऀ-ॣ०-ॿ]+")  # excludes danda । ॥
JA_SCRIPT = re.compile("[぀-ヿ一-鿿]")
DEVANAGARI = re.compile("[ऀ-ॿ]")


def ja_has_negation(ja):
    t = unicodedata.normalize("NFKC", ja)
    t = JA_NOT_NEGATION.sub("", t)
    return JA_NEG.search(t) is not None


def ne_has_negation(ne):
    t = NE_ALTERNATIVE.sub(r"\1", NE_TAG_QUESTION.sub("", unicodedata.normalize("NFC", ne)))
    for m in NE_WORD.finditer(t):
        w = m.group()
        if w == "दिन":  # "day"
            continue
        if NE_NEG_WORD.search(w):
            return True
        if len(w) >= 3 and NE_NEG_PAST.search(w) and w not in NE_NEG_PAST_NOT:
            return True
        if NE_NEG_NN.search(w) and w not in NE_NN_NOT_NEGATION:
            return True
        if len(w) >= 4 and w[0] == "न" and w.endswith(("ईकन", "इकन", "ीकन")):  # नलुकाईकन "without hiding"
            return True
        if len(w) >= 3 and NE_NEG_PREFIX.search(w):
            if not any(w.startswith(x) for x in NE_NOT_NEGATION):
                return True
    return False


def has_negation(text, lang):
    return ja_has_negation(text) if lang == "ja" else ne_has_negation(text)


def negation_consistent(src, hyp, direction):
    """True if negation polarity of hyp matches the source (validator semantics)."""
    s, t = direction.split("-")
    return has_negation(src, s) == has_negation(hyp, t)


# ---------------------------------------------------------------- terms / leftover script
def term_hits(hyp, terms):
    """(hits, total) of required target terms found verbatim (validateJaToNe uses contains)."""
    terms = [t for t in (terms or []) if t]
    # same rule as TranslationValidator.containsTerm: NFKC, candrabindu == anusvara
    canon = lambda x: unicodedata.normalize("NFKC", x).replace("\u0901", "\u0902")
    h = canon(hyp)
    return sum(1 for t in terms if canon(t) in h), len(terms)


def leftover_source_script(hyp, direction):
    """True if hyp contains source script (ja-ne: kana/kanji; ne-ja: Devanagari)."""
    return (JA_SCRIPT if direction == "ja-ne" else DEVANAGARI).search(hyp) is not None


# ---------------------------------------------------------------- Stage 1 Language IR + full validator
# Port of jane/ir (JaAnalyzer, NeAnalyzer) and TranslationValidator.validateJaToNe / validateNeToJa.
# CI compares this port with the Java validator (check_validator_parity.py); keep both in step.
_NE_CH = "ऀ-ॣ०-ॿ"
_NE_L = "(?<![" + _NE_CH + "])"
_NE_R = "(?![" + _NE_CH + "])"


def _ne_word(alt):
    return re.compile("(?<![" + _NE_CH + "])(?:" + alt + ")(?![" + _NE_CH + "])")


def canon(s):
    """TranslationValidator.canonTerm / IrText.canon: NFKC, candrabindu == anusvara."""
    return unicodedata.normalize("NFKC", s).replace("ँ", "ं")


JA_QUESTION = re.compile(r"(か[。？]?$|？|\?)")
NE_QUESTION = re.compile(
    r"(\?|？|को हो|(?<![ऀ-ॣ०-ॿ])(?:के|किन|कसरी|कस्तो|कस्ती|कस्ता|कुन|कुनचाहिँ|कुनचाहिं|कहाँ\S*|"
    r"कति(?!पय)\S*|कहिले(?!काहीं|काहीँ|काहिँ|काहिं))(?![ऀ-ॣ०-ॿ]))")

_JA_WH = [("TIME_OCLOCK", re.compile("何時(?![間代])")), ("COUNT_PERSON", re.compile("何人")), ("PRICE", re.compile("いくら")),
          ("WHO", re.compile("(?:誰|だれ|どなた)(?![かも])")), ("WHERE", re.compile("どこ(?![かも])")),
          ("WHEN", re.compile("いつ(?![かも])")), ("WHY", re.compile("(?:なぜ|どうして|なんで)")),
          ("HOW", re.compile("(?:どうやって|どのように|どうすれば)")), ("WHICH", re.compile("(?:どれ|どの|どちら|どっち)(?![かも])"))]
_JA_WH_COVER = [("QUANTITY", re.compile("いくら|幾ら|いくつ|幾つ|どれ(くらい|ぐらい|位)|どの(くらい|ぐらい|位)|"
                                        "何[人個歳回時日本枚台度分秒年月週円階番件杯匹冊着名]|"
                                        "なん(にん|こ|さい|かい|じ|にち|ぼん|まい|だい|ど|ぷん|ふん|ねん)")),
                ("WHY", re.compile("なぜ|何故|どうして|なんで|何で|理由")), ("WHERE", re.compile("どこ|何処|どちら|どっち")),
                ("WHEN", re.compile("いつ|何時|何日|何曜")), ("HOW", re.compile("どう|どのよう|どうやって|如何"))]
_NE_WH = [("QUANTITY", re.compile(_NE_L + r"कति(?!पय)\S*")), ("WHY", re.compile(_NE_L + "किन" + _NE_R)),
          ("WHERE", re.compile(_NE_L + r"कह[ाँां]{2}\S*")),
          ("WHEN", re.compile(_NE_L + "कहिले(?!काहीं|काहीँ|काहिँ|काहिं)" + _NE_R)),
          ("HOW", re.compile(_NE_L + "कसरी" + _NE_R))]
_NE_WH_COVER = [("TIME_OCLOCK", _ne_word(r"बजे\S*|बज्यो|बेला\S*")), ("COUNT_PERSON", _ne_word("कति|कतिजना")),
                ("PRICE", _ne_word(r"कति\S*|मूल्य|दाम|पैसा")), ("WHO", _ne_word("को|कसले|कसको|कसलाई|कोसँग|कोही")),
                ("WHERE", _ne_word(r"कहाँ\S*|कता\S*|कुन ठाउँ\S*")), ("WHEN", _ne_word(r"कहिले\S*|कुन दिन\S*|कति बेला|कति बजे")),
                ("WHY", _ne_word("किन")), ("HOW", _ne_word(r"कसरी|के गर\S*")), ("WHICH", _ne_word(r"कुन\S*|कुनचाहिँ|कुनचाहिं|कि\s.+\sकि"))]
_JA_MOD = [("OBLIGATION", re.compile("(なければ(なら|なり|いけ)|なくては(なら|なり|いけ)|ないと(いけ|だめ|ダメ)|なきゃ|なくちゃ|べき)")),
           ("REQUEST", re.compile("(ください|下さい|[てで](くれ(ませんか|ますか)?|ちょうだい|もらえ(?!れば)|いただけ)|お願い)")),
           ("CONJECTURE", re.compile("(でしょう(?!か)|だろう|かもしれ|と思い|と思う|みたい|らしい|はず)")),
           ("PERMISSION", re.compile("[てで]も(いい|良い|よい|大丈夫|かまわ|構わ)"))]
_NE_MOD = [("OBLIGATION", re.compile("(पर्छ|पर्छन्|पर्ने|पर्यो|पर्‍यो|पर्थ्यो|पर्दछ|पर्नेछ|पर्नुहुन्छ|नुपर|चाहिन्छ|अनिवार्य)")),
           ("REQUEST", re.compile("(नुहोस्|नुस्|नुहोला|होस्|देऊ|देउ|दिनु|कृपया|अनुरोध|सक्नुहुन्छ|नुहुन्छ\\s*\\?|सक्छौ|ऊ(?![" + _NE_CH + "])|ओ(?![" + _NE_CH + "]))")),
           ("CONJECTURE", re.compile("(होला|ला(?![" + _NE_CH + "])|सक्छ|सक्छन्|जस्तो|जस्तै|लाग्छ|लाग्यो|ठान्छु|सायद|शायद|पक्का|हुनुपर्छ|होइन र)")),
           ("PERMISSION", re.compile("(हुन्छ|सकिन्छ|सक्नुहुन्छ|सक्छ|ठीक|ठिक|अनुमति|पाइन्छ|पर्दैन|मिल्छ)"))]
JA_PAST = re.compile("(ました|でした|かった(です)?|[っんいきしちにびみりえけせてねべめれげ]た|[っん]だ)(よ|ね|よね|か)?[。！!？?]?$")
NE_PAST = _ne_word(r"\S*(?:थियो|थिए|थिइन्|थिएँ|थियौं|ेँ|ें|यौं|यौँ|थ्यो|यो|एको|एका|एकी|भयो|भएको|सक्यो|सके|इन्|इनँ|इनौं|ए|एँ|एन|एनन्|ेन|ेनन्|ेको|ेका|ेकी|्यो|"
                   "‍यो)")
NE_PAST_NOT = {"यो", "त्यो", "जो", "उनको"}
_JA_CUR = [("RUPEE", re.compile("ルピー")), ("YEN", re.compile(r"[0-9０-９〇一二三四五六七八九十百千万億]\s*円|円(?![滑満形熟周])")),
           ("DOLLAR", re.compile("ドル"))]
_NE_CUR = [("RUPEE", re.compile("रुपैयाँ|रुपैयां|रुपियाँ|रुपियां|" + _NE_L + r"(रु|रू)\.?\s*[0-9०-९]|NPR|Rs\.?\s*[0-9]")),
           ("YEN", re.compile(_NE_L + "(येन|जापानी येन)" + _NE_R)), ("DOLLAR", re.compile(_NE_L + "डलर"))]
_DEMONYM = re.compile("(ネパール|日本|インド|中国|アメリカ)(語|人)")
_KATAKANA_REPEAT = re.compile(r"([ァ-ヺー]{2,})\1")
PLACES = [("ネパール", "नेपाल"), ("カトマンズ", "काठमाडौं", "काठमाडौँ"), ("ポカラ", "पोखरा"), ("日本", "जापान"),
          ("東京", "टोकियो", "तोक्यो"), ("大阪", "ओसाका"), ("インド", "भारत", "इन्डिया"), ("中国", "चीन"),
          ("アメリカ", "अमेरिका"), ("ネバダ", "नेभाडा"), ("シアトル", "सियाटल"), ("カタルーニャ", "क्याटालोनिया"),
          ("ルンビニ", "लुम्बिनी"), ("チトワン", "चितवन"), ("エベレスト", "सगरमाथा", "एभरेस्ट")]


def _last_sentence(ja):
    t = ja.strip()
    for p in reversed(re.split("(?<=[。！!？?])", t)):
        if p.strip():
            return p.strip()
    return t


def analyze_ja(text):
    """Port of JaAnalyzer.analyze (Stage 1 IR) as a dict."""
    raw = text or ""
    ja = unicodedata.normalize("NFKC", raw)
    as_place = _DEMONYM.sub("", ja)
    return {
        "negated": ja_has_negation(raw), "question": JA_QUESTION.search(ja.strip()) is not None,
        "wh": [(k, m.group()) for k, p in _JA_WH for m in [p.search(ja)] if m],
        "wh_cover": {k for k, p in _JA_WH_COVER if p.search(ja)},
        "mod": {k for k, p in _JA_MOD if p.search(ja)}, "mod_cover": set(),
        "tense": "PAST" if JA_PAST.search(_last_sentence(ja)) else "NON_PAST",
        "numbers": numbers(raw), "numbers_norm": numbers(kanji_to_digits(raw)),
        "cur": {k for k, p in _JA_CUR if p.search(ja)},
        "places": {p[0] for p in PLACES if p[0] in ja}, "places_as_place": {p[0] for p in PLACES if p[0] in as_place},
        "repeat": _KATAKANA_REPEAT.search(raw) is not None,
    }


def analyze_ne(text):
    """Port of NeAnalyzer.analyze (Stage 1 IR) as a dict."""
    raw = text or ""
    ne = unicodedata.normalize("NFC", raw)
    c = canon(raw)
    places = {p[0] for p in PLACES if any(re.search(_NE_L + re.escape(canon(st)) + "(?!ी)", c) for st in p[1:])}
    nums = numbers(raw)
    return {
        "negated": ne_has_negation(raw), "question": NE_QUESTION.search(raw) is not None,
        "wh": [(k, m.group()) for k, p in _NE_WH for m in [p.search(ne)] if m],
        "wh_cover": {k for k, p in _NE_WH_COVER if p.search(ne)},
        "mod": set(), "mod_cover": {k for k, p in _NE_MOD if p.search(ne)},
        "tense": "PAST" if any(m.group() not in NE_PAST_NOT for m in NE_PAST.finditer(ne)) else "UNKNOWN",
        "numbers": nums, "numbers_norm": nums,
        "cur": {k for k, p in _NE_CUR if p.search(ne)}, "places": places, "places_as_place": set(), "repeat": False,
    }


def _check_numbers(a, b, issues):
    rest = list(b)
    for n in a:
        if n in rest:
            rest.remove(n)
        else:
            issues.append(("NUMBER_LOST", True, n))
    for n in rest:
        issues.append(("NUMBER_ADDED", False, n))


# --- speaker/listener reversal (ir/Person.java)
_P_JA_SELF = re.compile(r"(します|いたします|致します|てきます|でてきます|ていきます|ておきます|てあげます|てみます|てまいります|まいります|伺います|[いきしちにびみりぎ]ます)(ね|よ|ね。|よ。)?[。！!]?$")
_P_JA_BLOCK = re.compile(r"(ください|下さい|お願い|ませんか|ましょう|でしょう|か[。？?]?$|？|\?|[^私僕]が[^、]*ます[。！]?$|さんは|様は|方は|者は|ご家族は|看護師[がは]|先生[がは]|医師[がは]|失礼します)")
_P_JA_STRONG = re.compile(r"(私|僕|わたし|わたくし|自分)[がはも]|ますね[。！]?$|(てきます|でてきます|てまいります|てきますね)[。！]?$")
_P_NE_IMP = re.compile(r"(नुहोस्|नुहोला|नुस्)[!।\s]*$")
_P_NE_FIRST = re.compile(r"(छु|छौं|छौँ|एँ|यौं|औं|औँ|उँला|ौंला|ुँला|थें|थिएँ)(?![\u0900-\u097F])")


def _last(s, pat):
    parts = [x.strip() for x in re.split(pat, s.strip()) if x.strip()]
    return parts[-1] if parts else s.strip()


# --- everyday traps (ir/Traps.java)
_T_MORE = re.compile(r"もう少し|もうちょっと|あと少し|もう一度|もういちど")
_T_DONE_JA = re.compile(r"終わ|済|完了|しまい|ました")
_T_DONE_NE = re.compile(r"सकियो|सकिएको|सकिसक्यो|सकिसकेको")
_T_LEND = re.compile(r"貸して(ください|下さい|もらえ|くれ|いただけ)")
_T_BORROW = re.compile(r"借り(て|ます|たい|る|ても)")
_T_TAKE = re.compile(r"सापटी\s*लिनुहोस्")
_T_GIVE = re.compile(r"सापटी\s*दि(न्छु|न्छौं|एँ|एको छु)")
_T_MED = re.compile(r"薬[^。]*飲")
_T_MED_NE = re.compile(r"औषधि[^।]{0,15}पि(उ|औं|ए|इ|यो)")


_O_ALONE = re.compile(r"一人で|ひとりで|自分で")
_O_SELFWILL_JA = re.compile(r"勝手|好き")
_O_SELFWILL_NE = re.compile(r"आफूखुसी|मनपरी")
_O_USED = re.compile(r"慣れ")
_O_CARELESS_JA = re.compile(r"油断|不注意|雑")
_O_CARELESS_NE = re.compile(r"लापरवाही|हेलचेक्र्याइँ")
_O_ADVISED = re.compile(r"注意され|注意した|注意を受け|指導され")
_O_ANGER_JA = re.compile(r"怒|叱")
_O_ANGER_NE = re.compile(r"रिसा|रिस उठ|गाली")
_O_WATCH = re.compile(r"見守")
_O_WATCH_NE = re.compile(r"निगरानी|रेखदेख|छेउ|नजिक|साथ|सहयोग|ध्यान|सुरक्षा")


def ja_states_own_action(ja):
    if ja is None:
        return False
    last = _last(ja, r"(?<=[。！!？?])")
    return bool(_P_JA_SELF.search(last)) and not _P_JA_BLOCK.search(last) and bool(_P_JA_STRONG.search(last))


def ne_only_listener_imperative(ne):
    if ne is None:
        return False
    last = _last(ne, r"(?<=[।!?])\s*")
    return bool(_P_NE_IMP.search(last)) and not _P_NE_FIRST.search(last)


def validate(src, hyp, direction, required_targets=None):
    """Port of TranslationValidator.validateJaToNe / validateNeToJa: [(kind, hard, detail)] in Java order."""
    if hyp is None or not hyp.strip():
        return [("EMPTY", True, None)]
    issues = []
    if direction == "ja-ne":
        s, t = analyze_ja(src), analyze_ne(hyp)
        _check_numbers(s["numbers"], t["numbers"], issues)
        if s["negated"] != t["negated"]:
            issues.append(("NEGATION_LOST", True, None) if s["negated"] else ("NEGATION_ADDED", False, None))
        for term in required_targets or []:
            if canon(term) not in canon(hyp):
                issues.append(("TERM_MISSING", True, term))
        if JA_SCRIPT.search(hyp):
            issues.append(("UNTRANSLATED_SOURCE_SCRIPT", True, None))
        if s["question"] and not t["question"]:
            issues.append(("QUESTION_LOST", False, None))
        if s["question"]:
            issues += [("WH_LOST", True, surface) for k, surface in s["wh"] if k not in t["wh_cover"]]
        for k, hard in (("OBLIGATION", True), ("REQUEST", True), ("CONJECTURE", False), ("PERMISSION", False)):
            if k in s["mod"] and k not in t["mod_cover"]:
                issues.append((k + "_LOST", hard, None))
        if s["tense"] == "PAST" and t["tense"] != "PAST":
            issues.append(("PAST_LOST", True, None))
        if ja_states_own_action(src) and ne_only_listener_imperative(hyp):
            issues.append(("PERSON_REVERSED", True, None))
        if _T_MORE.search(src) and not _T_DONE_JA.search(src) and _T_DONE_NE.search(hyp):
            issues.append(("ADVERB_REVERSED", True, None))
        if (_T_LEND.search(src) and _T_TAKE.search(hyp)) or (not _T_LEND.search(src) and _T_BORROW.search(src) and _T_GIVE.search(hyp)):
            issues.append(("DIRECTION_REVERSED", True, None))
        if _T_MED.search(src) and _T_MED_NE.search(hyp):
            issues.append(("MEDICINE_VERB", False, None))
        if ((_O_ALONE.search(src) and not _O_SELFWILL_JA.search(src) and _O_SELFWILL_NE.search(hyp))
                or (_O_USED.search(src) and not _O_CARELESS_JA.search(src) and _O_CARELESS_NE.search(hyp))
                or (_O_ADVISED.search(src) and not _O_ANGER_JA.search(src) and _O_ANGER_NE.search(hyp))):
            issues.append(("GUIDANCE_REVERSED", True, None))
        if _O_WATCH.search(src) and not _O_WATCH_NE.search(hyp):
            issues.append(("WATCH_OVER_LOST", False, None))
        if ((re.search(r"かゆ|痒", src) and not re.search(r"痛", src) and re.search(r"दुख", hyp) and not re.search(r"चिला", hyp))
                or (re.search(r"飲ませ|食べさせ", src) and re.search(r"खानुहोस्|पिउनुहोस्", hyp) and not re.search(r"खुवा|पिला|लगाउ", hyp))
                or (re.search(r"休憩", src) and not re.search(r"休み|休暇|有給", src) and re.search(r"बिदा", hyp))):
            issues.append(("CARE_WORD_SWAPPED", True, None))
        if ((re.search(r"熱中症", src) and re.search(r"ज्वरो", hyp) and not re.search(r"हिट|गर्मी|लु ", hyp))
                or (re.search(r"(スイッチ|電源|暖房|ヒーター)[^。]*(切|消)", src) and re.search(r"चलाउनुहोस्|अन गर्नुहोस्", hyp) and not re.search(r"बन्द|अफ", hyp))
                or (re.search(r"冷える|冷えない|冷え込", src) and re.search(r"चिसो बनाउनुहोस्|चिसो पार्नुहोस्", hyp))):
            issues.append(("SEASONAL_REVERSED", True, None))
        # currency (TranslationValidator.checkCurrencyJaToNe)
        if "YEN" in s["cur"] and "YEN" not in t["cur"]:
            issues.append(("CURRENCY_MISMATCH", True, "येन"))
        if "RUPEE" in s["cur"] and "RUPEE" not in t["cur"]:
            issues.append(("CURRENCY_MISMATCH", True, "रुपैयाँ"))
        if "RUPEE" not in s["cur"] and "RUPEE" in t["cur"]:
            issues.append(("CURRENCY_MISMATCH", True, "रुपैयाँ"))
        if "DOLLAR" not in s["cur"] and "DOLLAR" in t["cur"]:
            issues.append(("CURRENCY_MISMATCH", True, "डलर"))
        if re.search(r"युआन|युवान|चिनियाँ\s*युआन", hyp):
            issues.append(("CURRENCY_MISMATCH", True, "युआन"))
        return issues
    s, t = analyze_ne(src), analyze_ja(hyp)
    _check_numbers(s["numbers"], t["numbers_norm"], issues)
    if s["negated"] != t["negated"]:
        issues.append(("NEGATION_LOST", True, None) if s["negated"] else ("NEGATION_ADDED", False, None))
    if DEVANAGARI.search(hyp):
        issues.append(("UNTRANSLATED_SOURCE_SCRIPT", True, None))
    if t["repeat"]:
        issues.append(("REPETITION", True, None))
    issues += [("QUESTION_WORD_LOST", True, surface) for k, surface in s["wh"] if k not in t["wh_cover"]]
    if "RUPEE" in s["cur"] and "RUPEE" not in t["cur"]:
        issues.append(("CURRENCY_MISMATCH", True, "ルピー"))
    if "YEN" not in s["cur"] and "YEN" in t["cur"]:
        issues.append(("CURRENCY_MISMATCH", True, "円"))
    if "DOLLAR" not in s["cur"] and "DOLLAR" in t["cur"]:
        issues.append(("CURRENCY_MISMATCH", True, "ドル"))
    for p in PLACES:
        if p[0] in s["places"] and p[0] not in t["places"]:
            issues.append(("PLACE_MISSING", True, p[0]))
        if p[0] not in s["places"] and p[0] in t["places_as_place"]:
            issues.append(("PLACE_ADDED", True, p[0]))
    if (re.search(r"(ज्यू|जी|ज्यु|आमाजु|बुबाजु|उहाँ)(ले)?\s", src) and re.search(r"नुभयो|नुहुन्छ|नुभएको", src)
            and not re.search(r"(^|[\s,।])(म|मैले|मलाई|मेरो|हामी|हामीले)(?=[\s,।])", src) and re.search(r"私|僕|わたし", hyp)):
        issues.append(("PERSON_REVERSED", True, None))
    if re.search(r"सक्छु कि सक्दिनँ|सक्छु कि सक्दिन", src) and not re.search(r"かどうか|か不安|か心配|か分から|自信|務まるか|できるか", hyp):
        issues.append(("ALTERNATIVE_LOST", True, None))
    return issues


def hard_count(issues):
    return sum(1 for _, h, _ in issues if h)


def format_issues(issues):
    """Same text as Java List<Issue>.toString()."""
    return "[" + ", ".join(("HARD " if h else "soft ") + k + ("" if d is None else "(" + d + ")") for k, h, d in issues) + "]"


def name_extra_vowel(candidate, extra_vowel_forms):
    """Port of JaNeTranslator's NAME_EXTRA_VOWEL rule: one hard issue per name spelled with the extra
    katakana u (रामु) while a bare rendering (राम) exists."""
    return [("NAME_EXTRA_VOWEL", True, d) for d in extra_vowel_forms if canon(d) in canon(candidate)]
