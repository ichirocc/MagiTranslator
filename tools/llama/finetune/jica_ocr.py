"""Nepali | katakana | Japanese tables of a phrase-book PDF rendered at 300 dpi: find the ruled cells and OCR the
Nepali (nep) and Japanese (jpn) columns of each row. usage: jica_ocr.py doc.pdf out.tsv page.png..."""
import sys, subprocess, re
import numpy as np
from PIL import Image

def runs(mask, minlen=1):
    idx = np.where(mask)[0]; out = []
    if not len(idx): return out
    s = p = idx[0]
    for i in idx[1:]:
        if i != p + 1: out.append((s, p)); s = i
        p = i
    out.append((s, p)); return [r for r in out if r[1] - r[0] + 1 >= minlen]

def ocr(img, lang):
    img.save('/tmp/_cell.png')
    t = subprocess.run(['tesseract', '/tmp/_cell.png', '-', '-l', lang, '--psm', '6'], capture_output=True, text=True).stdout
    return re.sub(r'\s+', ' ', t).strip()

PDF = sys.argv[1]
out = open(sys.argv[2], 'w')
for path in sys.argv[3:]:
    im = Image.open(path).convert('L'); a = np.array(im) < 128
    H, W = a.shape
    hl = [(s + e) // 2 for s, e in runs(a.mean(1) > 0.5)]           # horizontal rules
    n = 0
    for y0, y1 in zip(hl, hl[1:]):
        if y1 - y0 < 40: continue
        band = a[y0 + 3:y1 - 3]
        vl = [(s + e) // 2 for s, e in runs(band.mean(0) > 0.9)]   # vertical rules across this row
        if len(vl) != 4: continue
        c = [im.crop((vl[i] + 6, y0 + 6, vl[i + 1] - 6, y1 - 6)) for i in range(3)]
        ne = ocr(c[0], 'nep')
        pg = int(re.search(r'-(\d+)\.png', path).group(1))
        x, y, w, h = vl[2] + 4, y0 + 4, vl[3] - vl[2] - 8, y1 - y0 - 8
        ja = subprocess.run(['pdftotext', '-f', str(pg), '-l', str(pg), '-r', '300', '-x', str(x), '-y', str(y), '-W', str(w), '-H', str(h), PDF, '-'], capture_output=True, text=True).stdout
        ja = re.sub(r'\s+', '', ja)
        if ne and ja: out.write(f'{ja}\t{ne}\t{path.rsplit("/",1)[-1]}\n'); n += 1
    print(path, n, flush=True)
