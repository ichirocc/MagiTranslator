#!/usr/bin/env python3
"""Build res/raw/whisper_ne_allowed_tokens.bin: the Devanagari-only token mask for Nepali decoding.

Whisper uses byte-level BPE. A Devanagari letter is 3 UTF-8 bytes (E0 A4|A5 80-BF), and many vocab
entries hold only part of a letter (e.g. the lone lead byte E0, or "A4 95" without its lead). A mask
that only admits tokens decoding to whole Devanagari characters blocks those pieces, so the decoder
cannot spell many words and emits U+FFFD (measured: 7/15 FLEURS clips, tools/whisper_ne/RESULTS.md).

A token is allowed when its bytes can be a slice of a UTF-8 stream made only of:
  Devanagari (U+0900-U+097F), ASCII space, ASCII digits, and the punctuation below,
i.e. a leading run of continuation bytes, then whole allowed characters, then a trailing incomplete
prefix of a Devanagari character. Special tokens (id >= first special) are never allowed.

Bit layout: token id i -> bit (i & 7) of byte (i >> 3), little-endian bits (as Recognizer reads it).

usage: build_allowed_tokens.py VOCAB_JSON OUT_BIN [--check EXISTING_BIN]
"""
import json
import sys

PUNCT = set(" .,?!-'\"()।॥:;0123456789")


def bytes_to_unicode():
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(ord("¡"), ord("¬") + 1)) + list(range(ord("®"), ord("ÿ") + 1))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return {chr(c): b for b, c in zip(bs, cs)}


def is_cont(x):
    return 0x80 <= x <= 0xBF


def char_ok(ch):
    return ch in PUNCT or 0x0900 <= ord(ch) <= 0x097F


def devanagari_prefix_ok(tail):
    # incomplete trailing piece of a Devanagari char: E0, or E0 A4/A5
    if len(tail) == 1:
        return tail[0] == 0xE0
    if len(tail) == 2:
        return tail[0] == 0xE0 and tail[1] in (0xA4, 0xA5)
    return False


def devanagari_suffix_ok(head):
    # leading continuation bytes that end a Devanagari char: "80-BF" or "A4|A5 80-BF"
    if len(head) == 1:
        return is_cont(head[0])
    if len(head) == 2:
        return head[0] in (0xA4, 0xA5) and is_cont(head[1])
    return False


def token_allowed(bb):
    if not bb:
        return False
    i = 0
    while i < len(bb) and i < 2 and is_cont(bb[i]):
        i += 1
    if i and not devanagari_suffix_ok(bb[:i]):
        return False
    # trailing incomplete prefix
    j = len(bb)
    for k in (2, 1):
        if j - k >= i and devanagari_prefix_ok(bb[j - k:]):
            try:
                bb[i:j - k].decode("utf-8")
                j = j - k
                break
            except UnicodeDecodeError:
                pass
    mid = bb[i:j]
    try:
        s = mid.decode("utf-8")
    except UnicodeDecodeError:
        return False
    if not s and i == 0 and j == len(bb):
        return False
    return all(char_ok(c) for c in s)


def build(vocab):
    u = bytes_to_unicode()
    n = max(vocab.values()) + 1
    first_special = vocab.get("<|endoftext|>", n)
    allowed = [False] * n
    for tok, idx in vocab.items():
        if idx >= first_special:
            continue
        try:
            bb = bytes(u[c] for c in tok)
        except KeyError:
            continue
        allowed[idx] = token_allowed(bb)
    return allowed


def to_bits(allowed, size_bits):
    out = bytearray((size_bits + 7) // 8)
    for i, a in enumerate(allowed):
        if a:
            out[i >> 3] |= 1 << (i & 7)
    return bytes(out)


def from_bits(data):
    return [bool((data[i >> 3] >> (i & 7)) & 1) for i in range(len(data) * 8)]


def main():
    vocab = json.load(open(sys.argv[1], encoding="utf-8"))
    allowed = build(vocab)
    if "--check" in sys.argv:
        old = from_bits(open(sys.argv[sys.argv.index("--check") + 1], "rb").read())
        u = bytes_to_unicode()
        inv = {v: k for k, v in vocab.items()}
        added = [i for i in range(len(allowed)) if allowed[i] and not (i < len(old) and old[i])]
        removed = [i for i in range(len(allowed)) if (i < len(old) and old[i]) and not allowed[i]]
        partial = sum(1 for i in added if inv.get(i) is not None)
        print("old allowed", sum(old), "new allowed", sum(allowed), "added", len(added), "removed", len(removed), "old bits", len(old))
        for i in removed[:20]:
            print("removed", i, bytes(u[c] for c in inv[i]))
        for i in added[:10]:
            print("added", i, bytes(u[c] for c in inv[i]))
    old_size = len(open(sys.argv[2], "rb").read()) * 8 if "--keep-size" in sys.argv else len(allowed)
    open(sys.argv[2], "wb").write(to_bits(allowed, max(old_size, len(allowed))))


if __name__ == "__main__":
    main()
