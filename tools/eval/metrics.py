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


def numbers(s):
    if s is None:
        return []
    t = unicodedata.normalize("NFKC", s)
    t = "".join(chr(ord("0") + ord(c) - 0x0966) if 0x0966 <= ord(c) <= 0x096F else c for c in t)
    t = re.sub(r"(?<=[0-9]),(?=[0-9]{3})", "", t)
    return [re.sub(r"^0+(?=[0-9])", "", m.group()) for m in _NUMBER.finditer(t)]


def numbers_preserved(src, hyp):
    """True if every source number appears in hyp (multiset). None if source has no numbers."""
    a = numbers(src)
    if not a:
        return None
    rest = list(numbers(hyp))
    for n in a:
        if n in rest:
            rest.remove(n)
        else:
            return False
    return True


# ---------------------------------------------------------------- negation (TranslationValidator)
JA_NEG = re.compile(r"(ない|なかった|なくて|ないで|ません|ませんでした|ず(?=[、。に]|$)|ぬ(?=[、。]|$))")
JA_NOT_NEGATION = re.compile(
    r"(少ない|危ない|汚い|幼い|切ない|せつない|だらしない|つまらない|くだらない|もったいない|勿体ない|"
    r"仕方(が)?ない|しかた(が)?ない|間違いない|違いない|半端ない|すくない|あぶない)")
NE_NEG_WORD = re.compile(r"(छैन|छैनन्|छैनौं|होइन|होइनन्|हुँदैन|हुन्न|दैन|दैनन्|दैनौं|दैनौ|दिनँ|दिनौं|न्न)$")
NE_NEG_PREFIX = re.compile(r"^न(?:[क-ह])")
NE_NOT_NEGATION = ("नमस्ते", "नमस्कार", "नयाँ", "नजिक", "नदी", "नगर", "नक्सा", "नम्बर", "नरम", "नर्स",
                   "नल", "नसा", "नक्कली", "नतिजा", "नर्सरी")
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
