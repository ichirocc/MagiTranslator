#!/usr/bin/env python3
"""Token ids a Japanese or Nepali Whisper transcription can use (the rows kept in the ja/ne output layer).

    python3 whisper_vocab.py vocab.json added_tokens.json whisper_ne_allowed_tokens.bin ids.bin

vocab.json / added_tokens.json: openai/whisper-small (the vocabulary of the app's Whisper files).
Kept, in id order:
  - every token whose text is Japanese (kana, kanji, full-width forms, CJK punctuation) or Devanagari;
  - every token that is not a whole UTF-8 text (byte-level BPE pieces: kanji and Devanagari letters that
    have no token of their own are written with them);
  - ASCII tokens of at most 3 characters (digits, punctuation, short Latin pieces: names and acronyms
    can still be spelled), and whitespace tokens;
  - every token the app allows for Nepali (res/raw/whisper_ne_allowed_tokens.bin), so the constrained
    Nepali decoding picks exactly the same tokens;
  - the special tokens the decoding uses: end of text, start of transcript, the language tokens,
    transcribe / translate, start of previous text, no speech, no timestamps. Timestamps are left out
    (the app decodes without them and removes those Whisper inserts anyway).
Writes the ids as little-endian int32.
"""
import json, struct, sys


def byte_decoder():
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(ord("¡"), ord("¬") + 1)) + list(range(ord("®"), ord("ÿ") + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return {chr(c): b for b, c in zip(bs, cs)}


def japanese(ch):
    o = ord(ch)
    return 0x3040 <= o <= 0x30FF or 0x3400 <= o <= 0x4DBF or 0x4E00 <= o <= 0x9FFF or 0xFF00 <= o <= 0xFFEF \
        or 0x3000 <= o <= 0x303F or 0x31F0 <= o <= 0x31FF or 0xF900 <= o <= 0xFAFF


def devanagari(ch):
    return 0x0900 <= ord(ch) <= 0x097F or 0xA8E0 <= ord(ch) <= 0xA8FF


def kept_ids(vocab, added, ne_allowed):
    dec = byte_decoder()
    keep = set()
    special = {"<|endoftext|>", "<|startoftranscript|>", "<|transcribe|>", "<|translate|>", "<|startofprev|>",
               "<|nocaptions|>", "<|nospeech|>", "<|notimestamps|>", "<|startoflm|>"}
    for tok, i in vocab.items():
        b = bytes(dec[c] for c in tok)
        try:
            s = b.decode("utf-8")
        except UnicodeDecodeError:
            keep.add(i)   # a piece of a multi-byte character
            continue
        st = s.strip()
        if not st or any(japanese(c) or devanagari(c) for c in st) or (st.isascii() and len(st) <= 3):
            keep.add(i)
    for tok in special:
        if tok in vocab:   # <|endoftext|> is in vocab.json
            keep.add(vocab[tok])
    for tok, i in added.items():
        lang = tok.startswith("<|") and tok.endswith("|>") and tok[2:-2].isalpha() and tok[2:-2].islower() and len(tok) <= 9
        if tok in special or lang:
            keep.add(i)
    for i, ok in enumerate(ne_allowed):
        if ok:
            keep.add(i)
    return sorted(keep)


def read_allowed(path):
    bits = open(path, "rb").read()
    return [(bits[i >> 3] >> (i & 7)) & 1 == 1 for i in range(len(bits) * 8)]


if __name__ == "__main__":
    vocab = json.load(open(sys.argv[1], encoding="utf-8"))
    added = json.load(open(sys.argv[2], encoding="utf-8"))
    ids = kept_ids(vocab, added, read_allowed(sys.argv[3]))
    with open(sys.argv[4], "wb") as f:
        f.write(struct.pack("<%di" % len(ids), *ids))
    print(f"{len(ids)} of {len(vocab) + len(added)} tokens kept")
