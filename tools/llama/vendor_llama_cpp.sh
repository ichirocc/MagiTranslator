#!/bin/sh
# Copies the part of llama.cpp the app needs (library + CPU backend, no tools/servers/GPU backends) into
# app/src/main/cpp/src/libs/llama.cpp. Source: the llama-cpp-python 0.3.38 sdist on PyPI, which ships
# llama.cpp commit f5e6915c2 (its CHANGELOG); the archive is checked against the hash PyPI publishes.
# Usage: sh tools/llama/vendor_llama_cpp.sh [sdist.tar.gz]   (downloads it when not given)
set -eu
URL=https://files.pythonhosted.org/packages/6c/50/e3eba8a25c3927659da15d80bdc3f5ce8692f4f7cda16eeabde5185e1164/llama_cpp_python-0.3.38.tar.gz
SHA=0570c9736b5e2a239b651da18687238eeba7fc2369dcdfd8cbb009dc1321e23f
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST=$ROOT/app/src/main/cpp/src/libs/llama.cpp
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
SDIST=${1:-}
if [ -z "$SDIST" ]; then SDIST=$TMP/sdist.tar.gz; curl -sSfL -o "$SDIST" "$URL"; fi
echo "$SHA  $SDIST" | sha256sum -c -
tar xzf "$SDIST" -C "$TMP"
S=$TMP/llama_cpp_python-0.3.38/vendor/llama.cpp
rm -rf "$DEST"; mkdir -p "$DEST/ggml/src"
cp -R "$S/CMakeLists.txt" "$S/LICENSE" "$S/cmake" "$S/include" "$S/src" "$DEST/"
cp -R "$S/ggml/CMakeLists.txt" "$S/ggml/cmake" "$S/ggml/include" "$DEST/ggml/"
for f in "$S"/ggml/src/*; do
  case "$f" in */ggml-cpu|*/CMakeLists.txt|*.c|*.cpp|*.h|*.in) cp -R "$f" "$DEST/ggml/src/";; esac
done
# KleidiAI is downloaded at configure time when enabled: not used (repacked i8mm kernels are built in)
rm -rf "$DEST/ggml/src/ggml-cpu/kleidiai" "$DEST/ggml/src/ggml-cpu/spacemit"
# the only patch: vendor/ (httplib, json, stb, audio) serves the tools and mtmd, not the library
sed -i 's|^add_subdirectory(vendor)$|# add_subdirectory(vendor)   # MagiTranslator: library only, see tools/llama/vendor_llama_cpp.sh|' "$DEST/CMakeLists.txt"
grep -q '^# add_subdirectory(vendor)' "$DEST/CMakeLists.txt"
printf 'llama.cpp f5e6915c2 from llama-cpp-python 0.3.38 (sha256 %s)\n' "$SHA" > "$DEST/VERSION"
echo "vendored into $DEST ($(du -sh "$DEST" | cut -f1))"
