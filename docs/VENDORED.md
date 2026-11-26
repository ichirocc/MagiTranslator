# Vendored native libraries

The C/C++ libraries used by the app used to be git submodules pointing at the upstream author's forks
(`github.com/niedev/...`). They are now stored in this repository as plain files, exactly as the
submodules checked them out recursively at the commits below (every file byte-identical, same
permissions; each top folder gets a `.gitattributes` with `* -text` so git never rewrites line endings).
Nothing is downloaded at build time any more.

To update a library: replace the folder with the new upstream checkout (without `.git`), keep the
`.gitattributes`, update this table, and review the diff.

SQLite (`app/src/main/cpp/src/libs/sqlite/sqlite3.c`, `sqlite3.h`) is the official amalgamation
3.54.1 from https://www.sqlite.org/2026/sqlite-amalgamation-3540100.zip (SHA3-256
`92281c86e2957f9b9305890f9ac04204caa7b677cc868dc326d942689df28e30`, as listed on sqlite.org/download.html),
unmodified; public domain. It replaced the prebuilt sqlite4java AAR, whose native libraries were not
16 KB aligned. The Java side (Tatoeba) uses Android's own android.database.sqlite.

| Path | Upstream | Commit |
|---|---|---|
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot` | https://github.com/niedev/bergamot-translator | `2e059c32c4a4238811d3eb1b8ea3feec87b3faca` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev` | https://github.com/niedev/marian-dev | `2781d735d4a10dca876d61be587afdab2726293c` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/examples` | https://github.com/marian-nmt/marian-examples | `c19b7814d71febf1053bd93af6ac314b46204092` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/regression-tests` | https://github.com/marian-nmt/marian-regression-tests | `16914ae94c80f338c678f0461c4e45965149f6aa` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/fbgemm` | https://github.com/marian-nmt/FBGEMM | `0e33146d3e7f070c7de9494efef49147a9d20558` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/fbgemm/third_party/asmjit` | https://github.com/asmjit/asmjit.git | `4da474ac9aa2689e88d5e40a2f37628f302d7e3c` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/fbgemm/third_party/cpuinfo` | https://github.com/pytorch/cpuinfo | `d5e37adf1406cf899d7d9ec1d317c47506ccb970` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/fbgemm/third_party/googletest` | https://github.com/google/googletest | `0fc5466dbb9e623029b1ada539717d10bd45e99e` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/intgemm` | https://github.com/kpu/intgemm | `f7401513da71758dacce52fed1c7855549abee59` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/nccl` | https://github.com/marian-nmt/nccl | `7d3486128ebc865b9f2cad63a5cfd3a8f6abcb5a` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/onnxjs` | https://github.com/browsermt/onnxjs | `924924b08e9596b41aeebada4a172f026be95f5a` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/onnxjs/deps/eigen` | https://github.com/abhi-agg/eigen-git-mirror.git | `fff37f4ca0397af9ed7e04f3bd6b893a1ea2b08e` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/ruy` | https://github.com/google/ruy | `2d950b3bfa7ebfbe7a97ecb44b1cc4da5ac1d6f0` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/ruy/third_party/cpuinfo` | https://github.com/pytorch/cpuinfo | `5916273f79a21551890fd3d56fc5375a78d1598d` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/ruy/third_party/googletest` | https://github.com/google/googletest | `6c58c11d5497b6ee1df3cb400ce30deb72fc28c0` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/sentencepiece` | https://github.com/browsermt/sentencepiece | `ae41b7740d7006596bb9257e83340b2620db9d00` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/simd_utils` | https://github.com/browsermt/simd_utils.git | `d0793d86aea9036a5bc77b9ca7791dff024168ca` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/marian-dev/src/3rd_party/simple-websocket-server` | https://github.com/marian-nmt/Simple-WebSocket-Server | `417a2a9e9dbd720b8d2dfa1dafe57cf1b37ca0d7` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/pybind11` | https://github.com/pybind/pybind11.git | `9ec1128c7aac3d069a4ec2bd1dfc7f57c6526d1c` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/3rd_party/ssplit-cpp` | https://github.com/niedev/ssplit-cpp | `33db900f52e0411ada00c90b5a2d1b39e90f6e1a` |
| `app/src/main/cpp/src/bergamot_translator/libs/bergamot/bergamot-translator-tests` | https://github.com/browsermt/bergamot-translator-tests | `a04432d7921bfa1dd62bc2e5cdca46b226f256de` |
| `app/src/main/cpp/src/fasttext/libs/fastText` | https://github.com/niedev/fastText | `1142dc4c4ecbc19cc16eee5cdd28472e689267e6` |
| `app/src/main/cpp/src/libs/protobuf-lite` | https://github.com/niedev/protobuf | `31fef64d9d9d86aba94192b8e1152f1fe1968793` |
| `app/src/main/cpp/src/libs/sentencepiece` | https://github.com/niedev/sentencepiece | `d4fd0d230394f9ba3a6544fdcf61aef08c08d716` |
