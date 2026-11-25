#!/usr/bin/env python3
"""Desk check: every token of real Nepali text (as Whisper tokenizes it) must be allowed by the mask.

usage: check_mask_coverage.py TOKENIZER_DIR MASK_BIN TEXT_FILE...
"""
import sys

from transformers import WhisperTokenizer

tok = WhisperTokenizer.from_pretrained(sys.argv[1])
data = open(sys.argv[2], "rb").read()
allowed = lambda i: (i >> 3) < len(data) and (data[i >> 3] >> (i & 7)) & 1
total = blocked = 0
for path in sys.argv[3:]:
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        ids = tok.encode(" " + line, add_special_tokens=False)
        bad = [i for i in ids if not allowed(i)]
        total += len(ids)
        blocked += len(bad)
        if bad:
            print("BLOCKED", [tok.convert_ids_to_tokens(i) for i in bad][:5], line[:40])
print("tokens", total, "blocked", blocked)
