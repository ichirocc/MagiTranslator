#!/usr/bin/env python3
"""Builds Madlad-v2.zip from the mirrored Madlad.zip: the embedding table goes from 8 bits (one global
scale) to 4-bit blocks (GatherBlockQuantized, block 32), everything else is copied byte for byte.

    python3 build_madlad_v2.py Madlad.zip Madlad-v2.zip [--stored]

Reproducible: the input hash is checked, the quantisation is plain numpy (IEEE, no randomness), the zip
entries are sorted with a fixed timestamp and permissions. With --stored (no compression) the output
does not depend on the zlib version either; the default (deflate) is smaller but can differ between
zlib builds, so CI compares the result with models/models.tsv and fails on any difference.
Needs: numpy, onnx (versions in requirements.txt next to this file).
"""
import hashlib, os, shutil, subprocess, sys, tempfile, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
MADLAD_ZIP_SHA256 = "ec3183e6106182938fe095d8e48057e2412ded278b560c2dc818c72cfedd682d"
FIXED_TIME = (2026, 1, 1, 0, 0, 0)

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""): h.update(b)
    return h.hexdigest()

def main():
    src, dst = sys.argv[1], sys.argv[2]
    stored = "--stored" in sys.argv[3:]
    got = sha256(src)
    if got != MADLAD_ZIP_SHA256: sys.exit(f"{src}: sha256 {got} is not the pinned Madlad.zip")
    work = tempfile.mkdtemp()
    try:
        with zipfile.ZipFile(src) as z: z.extractall(work)
        base = os.path.join(work, "Madlad")
        subprocess.run([sys.executable, "-I", os.path.join(HERE, "quantize_embed_4bit.py"),
                        os.path.join(base, "madlad_embed_8bit.onnx"), os.path.join(base, "madlad_embed_4bit.onnx"), "32"], check=True)
        os.remove(os.path.join(base, "madlad_embed_8bit.onnx"))
        for f in os.listdir(base):
            if f.endswith(".deq.npy"): os.remove(os.path.join(base, f))
        entries = []
        for root, dirs, files in os.walk(base):
            rel = os.path.relpath(root, work).replace(os.sep, "/")
            entries.append(rel + "/")
            entries += [rel + "/" + f for f in files]
        method = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
        with zipfile.ZipFile(dst, "w") as out:
            for name in sorted(entries):
                info = zipfile.ZipInfo(name, FIXED_TIME)
                info.create_system = 3
                if name.endswith("/"):
                    info.external_attr = (0o40755 << 16) | 0x10
                    out.writestr(info, b"")
                else:
                    info.external_attr = 0o100644 << 16
                    info.compress_type = method
                    with open(os.path.join(work, name), "rb") as f, out.open(info, "w") as w:   #files < 2 GiB: plain (non-ZIP64) headers
                        shutil.copyfileobj(f, w, 1 << 20)
        print(f"{dst}: {os.path.getsize(dst)} bytes, sha256 {sha256(dst)}")
    finally:
        shutil.rmtree(work)

if __name__ == "__main__":
    main()
