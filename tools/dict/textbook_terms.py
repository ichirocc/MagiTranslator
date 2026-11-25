#!/usr/bin/env python3
"""Pull care / medical vocabulary out of a textbook you have, for a PRIVATE dictionary.

Textbooks are copyrighted. Words are free to use, but a textbook's bilingual glossary as a whole is
the author's work, so the output of this tool is meant for your own device only:
  - review the candidates, then load the file in the app (Settings -> Load a dictionary file);
  - do NOT commit it to the public repository unless the textbook's licence allows it
    (e.g. a government text under the Japanese government standard terms of use, with attribution).

Two modes (Python 3 standard library only; PDF needs the `pdftotext` command, poppler-utils):

  1) terms: frequent Japanese care / medical words of a Japanese text, marked as
     already-in-dictionary / in-review-list / new
       python3 textbook_terms.py terms textbook.pdf [--top 200] > candidates.tsv

  2) glossary: ja<->ne pairs from a bilingual glossary (lines that contain both Japanese and
     Devanagari, e.g. "入浴介助 ... नुहाउन सहयोग"), written as an importable dictionary
       python3 textbook_terms.py glossary glossary.pdf --out private_ja_ne_terms.tsv

The glossary output uses the app's file format (first line "#!jane ja-ne-terms") and applies the
same line rules as DictionaryFiles.check; lines that break them are listed on stderr, not written.
"""
import argparse
import collections
import os
import re
import shutil
import subprocess
import sys
import unicodedata

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))

JA_WORD = re.compile(r"[\u4e00-\u9fff\u3005]{2,8}|[\u30a1-\u30fa\u30fc]{3,}")   # kanji compounds, katakana words
DEVA = "\u0900-\u097F"
NE_TERM = re.compile(r"^[\u0900-\u0963\u0966-\u097F]+( [\u0900-\u0963\u0966-\u097F]+)*$")
# words that tell a line is about care / medicine (used to rank "terms")
CARE_HINT = re.compile(r"介護|介助|看護|医療|病|症|痛|薬|食事|入浴|排泄|排せつ|移乗|移動|認知|障害|リハビリ|ケア|利用者|施設|血|呼吸|誤嚥|褥瘡|転倒|口腔|体位|清拭|更衣|服薬|バイタル")
# generic words that are frequent in any text and never dictionary entries
STOP = set("場合 必要 今回 以下 以上 部分 状態 方法 内容 目的 確認 実施 対応 注意 重要 理解 説明 自分 相手 気持 一緒 具体 生活 日常 行動 支援 関係 情報 意味 可能 程度 使用 利用 観察 活動 対象 基本".split())


def read_text(path):
    if path.lower().endswith(".pdf"):
        if not shutil.which("pdftotext"):
            sys.exit("pdftotext not found: install poppler-utils, or convert the PDF to text first")
        out = subprocess.run(["pdftotext", "-layout", "-enc", "UTF-8", path, "-"], check=True, capture_output=True)
        text = out.stdout.decode("utf-8", errors="replace")
    else:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
    return unicodedata.normalize("NFC", text)


def load_keys(name, col=0):
    keys = set()
    p = os.path.join(REPO, "dictionaries", name)
    if os.path.exists(p):
        for line in open(p, encoding="utf-8"):
            if line.strip() and not line.startswith("#"):
                cell = line.rstrip("\n").split("\t")[col]
                keys.update(k.strip() for k in cell.split("/"))
    return keys


def cmd_terms(a):
    text = read_text(a.file)
    known = load_keys("ja_ne_terms.tsv")
    review = load_keys(os.path.join("review", "care_candidates.tsv"))
    tf = collections.Counter()
    care = collections.Counter()
    for line in text.splitlines():
        words = [w for w in JA_WORD.findall(line) if w not in STOP]
        tf.update(words)
        if CARE_HINT.search(line):
            care.update(words)
    rows = sorted(tf, key=lambda w: (-care[w], -tf[w]))[: a.top]
    print("# japanese\tcount\tcount_in_care_lines\tstatus  (status: dictionary / review / new)")
    for w in rows:
        status = "dictionary" if w in known else "review" if w in review else "new"
        print(f"{w}\t{tf[w]}\t{care[w]}\t{status}")


def split_pair(line):
    """'入浴介助 ..... नुहाउन सहयोग' -> ('入浴介助', 'नुहाउन सहयोग'); None when the line is not a pair."""
    m = re.search("[" + DEVA + "]", line)
    if not m:
        return None
    ja = line[: m.start()]
    ne = line[m.start():]
    ja = re.sub(r"[\s.・…:：|｜\-–—()（）0-9０-９]+$", "", ja).strip()
    ja = re.sub(r"^[\s0-9０-９.、．)）]+", "", ja).strip()
    ne = re.sub(r"[\s.।|｜]+$", "", re.split(r"\s{2,}|\t|[(（]", ne)[0]).strip()
    if not ja or not ne:
        return None
    return ja, ne


def check_pair(ja, ne):
    if re.search("[" + DEVA + "]", ja) or len(ja) < 2:
        return "japanese term must be at least 2 characters, without Devanagari"
    if not NE_TERM.match(ne):
        return "nepali term must be Devanagari words"
    return None


def cmd_glossary(a):
    text = read_text(a.file)
    pairs, rejected, seen = [], [], set()
    for n, line in enumerate(text.splitlines(), 1):
        p = split_pair(line)
        if not p:
            continue
        why = check_pair(*p)
        if why:
            rejected.append(f"line {n}: {why}: {line.strip()[:80]}")
        elif p[0] not in seen:
            seen.add(p[0])
            pairs.append(p)
    with open(a.out, "w", encoding="utf-8") as f:
        f.write("#!jane ja-ne-terms\n")
        f.write(f"# PRIVATE dictionary made from: {os.path.basename(a.file)}\n")
        f.write("# For your own device only (Settings -> Load a dictionary file). Review every line before use.\n")
        f.write("# Do not publish it unless the source's licence allows it.\n")
        for ja, ne in pairs:
            f.write(f"{ja}\t{ne}\n")
    for r in rejected:
        print("skipped " + r, file=sys.stderr)
    print(f"{len(pairs)} pairs -> {a.out} ({len(rejected)} lines skipped)", file=sys.stderr)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    t = sub.add_parser("terms", help="frequent Japanese care words of a text")
    t.add_argument("file")
    t.add_argument("--top", type=int, default=200)
    g = sub.add_parser("glossary", help="ja<->ne pairs of a bilingual glossary")
    g.add_argument("file")
    g.add_argument("--out", default="private_ja_ne_terms.tsv")
    a = ap.parse_args(argv)
    (cmd_terms if a.cmd == "terms" else cmd_glossary)(a)


if __name__ == "__main__":
    main()
