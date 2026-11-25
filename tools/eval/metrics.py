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
JA_NEG = re.compile(r"(ない|なかった|なくて|ないで|ません|ませんでした|ず(?=[、。に]|$)|ぬ(?=[、。]|$))")
JA_NOT_NEGATION = re.compile(
    r"((?:少|すく|危|あぶ|切|せつ|だらし|つまら|くだら|もったい|勿体|仕方が?|しかたが?|間違い|違い|半端|きた|おさ|はか|儚|ぎこち|"
    r"情け|なさけ|頼り|たより|物足り|もの足り|ものたり|申し訳|申しわけ|そっけ|素っ気|あどけ|あっけ|呆気|味気|あじけ)な(?:い|く|かっ|さ|け)|"
    r"汚い|幼い|すみません|すいません|申し訳(あり|ござい)ません|かもしれ(ない|ません)|なければ(なら|なり|いけ)(ない|ません)|なくては(なら|なり|いけ)(ない|ません)|ないと(いけ|だめ|ダメ)(ない|ません)?|ませんか)")
NE_NEG_WORD = re.compile(r"(छैन|छैनन्|छैनौं|होइन|होइनन्|हुँदैन|हुन्न|दैन|दैनन्|दैनौं|दैनौ|दिनँ|दिनौं|हुन्नँ|न्नँ)$")
NE_NEG_NN = re.compile(r"[\u093E-\u094C\u0901\u0902]न्नँ?$")
NE_NN_NOT_NEGATION = {"भिन्न", "विभिन्न", "खिन्न", "छिन्न", "छिन्नभिन्न"}
NE_NEG_PREFIX = re.compile(r"^न(?:[क-ह]|[अआइईउऊएऐओऔ])")
NE_NOT_NEGATION = ("नमस्ते", "नमस्कार", "नयाँ", "नजिक", "नदी", "नक्सा", "नक्शा", "नम्बर", "नरम", "नर्स", "नल", "नसा",
                   "नक्कली", "नतिजा", "नर्सरी", "नगरपालिका", "नगरमा", "नगरको", "नगरका", "नगरले", "नगरबाट", "नगरवासी",
                   "नगद", "नमुना", "नम्र", "नजर", "नरक", "नमन", "नशा", "नकल", "नरिवल", "नवीन", "नवम्बर", "नब्बे", "नहर",
                   "नवजात", "नक्सली", "नमक", "नवौं", "नरेन्द्र", "नरेश", "नवराज", "नकारात्मक", "नक्षत्र")
NE_WORD = re.compile("[ऀ-ॣ०-ॿ]+")  # excludes danda । ॥
JA_SCRIPT = re.compile("[぀-ヿ一-鿿]")
DEVANAGARI = re.compile("[ऀ-ॿ]")


def ja_has_negation(ja):
    t = unicodedata.normalize("NFKC", ja)
    t = JA_NOT_NEGATION.sub("", t)
    return JA_NEG.search(t) is not None


def ne_has_negation(ne):
    for m in NE_WORD.finditer(unicodedata.normalize("NFC", ne)):
        w = m.group()
        if w == "दिन":  # "day"
            continue
        if NE_NEG_WORD.search(w):
            return True
        if NE_NEG_NN.search(w) and w not in NE_NN_NOT_NEGATION:
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
    return sum(1 for t in terms if t in hyp), len(terms)


def leftover_source_script(hyp, direction):
    """True if hyp contains source script (ja-ne: kana/kanji; ne-ja: Devanagari)."""
    return (JA_SCRIPT if direction == "ja-ne" else DEVANAGARI).search(hyp) is not None
