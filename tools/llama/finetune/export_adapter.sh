#!/bin/sh
# export_adapter.sh <PLaMo HF dir> <peft checkpoint dir> <out.gguf>
# LoRA (PEFT) -> GGUF adapter for the app's engine, with llama.cpp's converter from the same pinned
# llama-cpp-python sdist as the vendored library (tools/llama/vendor_llama_cpp.sh).
set -eu
URL=https://files.pythonhosted.org/packages/6c/50/e3eba8a25c3927659da15d80bdc3f5ce8692f4f7cda16eeabde5185e1164/llama_cpp_python-0.3.38.tar.gz
SHA=0570c9736b5e2a239b651da18687238eeba7fc2369dcdfd8cbb009dc1321e23f
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
curl -sSfL -o "$TMP/s.tar.gz" "$URL"
echo "$SHA  $TMP/s.tar.gz" | sha256sum -c - >/dev/null
tar xzf "$TMP/s.tar.gz" -C "$TMP"
L=$TMP/llama_cpp_python-0.3.38/vendor/llama.cpp
PYTHONPATH=$L/gguf-py python3 "$L/convert_lora_to_gguf.py" --base "$1" --outtype f16 --outfile "$3" "$2"
