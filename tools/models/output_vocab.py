#!/usr/bin/env python3
"""Output vocabulary of the ja<->ne Madlad decoder: the sentencepiece pieces that a Japanese or a Nepali
translation can contain. Everything else (Cyrillic, Arabic, Thai, Hangul, ... pieces) can never be the
right next token for these two target languages, so the output layer only scores the kept pieces.

A piece is kept when every character (the sentencepiece space "▁" excepted) is in KEEP_RANGES:
Devanagari (+ the joiners Nepali uses), Japanese (kana, CJK ideographs, CJK punctuation, full-width
forms), ASCII (digits, punctuation), general punctuation and letter-like symbols (℃).
Latin words are what most of the vocabulary is (English and other European languages, ~136k pieces)
and Japanese / Nepali output rarely needs them, so a piece with Latin letters is kept only when it is
short (<= 3 letters: any Latin word can still be spelt from them), an all-capital acronym of <= 6
letters (PCR, COVID, LINE, ATM) or one of NAMES (WhatsApp, iPhone ...).
The control pieces (<pad>, </s>, <s>, <unk>) and byte pieces are always kept.
Result for Madlad: see the build output; every token of the 3788 ja/ne phrase pairs is kept.

python3 output_vocab.py spiece.model ids.bin    -> writes the kept ids (int32 little endian, ascending)
"""
import struct, sys
import sentencepiece as spm

KEEP_RANGES = [
    (0x0020, 0x007E), (0x00A0, 0x00FF),          # ASCII, Latin-1 (°, ×, ½ ...)
    (0x0900, 0x097F), (0xA8E0, 0xA8FF),          # Devanagari, Devanagari Extended
    (0x200B, 0x200D),                            # ZW space / ZWNJ / ZWJ (Nepali conjunct control)
    (0x2010, 0x206F), (0x2100, 0x214F),          # general punctuation, letter-like symbols (℃, №)
    (0x2190, 0x21FF), (0x2200, 0x22FF),          # arrows, math operators (− minus, ≪ ≫ used as quotes)
    (0x2460, 0x24FF),                            # circled numbers (①)
    (0x25A0, 0x25FF), (0x2600, 0x26FF),          # geometric shapes, misc symbols (○, ☆)
    (0x3000, 0x303F), (0x3040, 0x309F),          # CJK punctuation, hiragana
    (0x30A0, 0x30FF), (0x31F0, 0x31FF),          # katakana (+ phonetic extensions)
    (0x3200, 0x33FF),                            # enclosed CJK, CJK compatibility (㎡, ㈱)
    (0x3400, 0x4DBF), (0x4E00, 0x9FFF),          # CJK ideographs (ext. A, unified)
    (0xF900, 0xFAFF), (0xFF00, 0xFFEF),          # CJK compatibility ideographs, half/full-width forms
]

NAMES = {"WhatsApp", "iPhone", "Android", "Facebook", "Messenger", "Google", "YouTube", "Zoom", "Viber",
         "TikTok", "Instagram", "Twitter", "Amazon", "Gmail", "Skype", "Wi", "Fi", "Bluetooth", "Excel",
         "Word", "PayPay", "Suica", "PASMO", "Covid", "COVID", "Nepal", "Japan", "Tokyo", "Osaka",
         "Kathmandu", "Pokhara", "OK", "Email", "email"}

def allowed(ch):
    c = ord(ch)
    return any(a <= c <= b for a, b in KEEP_RANGES)

def keep_ids(sp):
    ids = []
    for i in range(sp.get_piece_size()):
        if sp.is_control(i) or sp.is_unknown(i) or sp.is_byte(i):
            ids.append(i); continue
        p = sp.id_to_piece(i).replace("▁", "")
        if p == "":
            ids.append(i); continue
        if not all(allowed(ch) for ch in p):
            continue
        letters = [ch for ch in p if ch.isalpha() and ord(ch) < 0x250]   #Latin letters (incl. Latin-1)
        if not letters:
            ids.append(i); continue
        if not all(ord(ch) < 0x80 for ch in p):
            continue                                                       #accented Latin: European words
        word = "".join(letters)
        if len(letters) <= 3 or (word.isupper() and len(letters) <= 6) or word in NAMES:
            ids.append(i)
    return ids

def main():
    sp = spm.SentencePieceProcessor(model_file=sys.argv[1])
    ids = keep_ids(sp)
    with open(sys.argv[2], "wb") as f:
        f.write(struct.pack("<%di" % len(ids), *ids))
    print(f"kept {len(ids)} of {sp.get_piece_size()} pieces ({100 * len(ids) / sp.get_piece_size():.1f}%)")

if __name__ == "__main__":
    main()
