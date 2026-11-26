# PLaMo 3 Translate on the phone (llama.cpp)

Built with PLaMo. Japanese ⇄ Nepali with [PLaMo 3 Translate](https://huggingface.co/pfnet/plamo-3-translate-0.8b)
(Preferred Networks, PLaMo Community License) running on the phone's CPU through llama.cpp. Madlad stays the
fallback: when PLaMo is not installed, its license is not accepted, the CPU cannot run the engine or a call
fails, the translation goes through the existing Madlad pipeline.

## Pieces

| Part | File |
|---|---|
| llama.cpp (library + CPU backend, 11 MB of source) | `app/src/main/cpp/src/libs/llama.cpp`, made by `vendor_llama_cpp.sh` |
| engine (C++): load, greedy decoding, prefix cache per direction | `app/src/main/cpp/src/plamo/plamo_core.{h,cpp}` |
| JNI layer | `app/src/main/cpp/src/plamo/plamo_engine.cpp` → `libplamo_engine.so` |
| Java wrapper, CPU check, model files + license | `app/src/main/java/.../plamo/` |
| PFML3 prompts, glossary terms, history, review | `jane/PlamoPrompt.java`, `jane/PlamoTranslator.java`, `jane/Glossary.termsJaNe/termsNeJa` |
| test without the model | `app/src/test/java/.../plamo/PlamoTest.java` (run by the release workflow) |
| command-line check (Termux, QEMU) | `plamo_cli.cpp` |

## Phone-specific choices

- **CPU code for Armv8.6-A + dotprod + i8mm + fp16** (`GGML_CPU_ARM_ARCH`). Tensor G3/G4/G5 have these; llama.cpp
  repacks the Q4_K weights at load time for its i8mm matrix kernels (`q4_K_8x8`). `CpuFeatures` reads
  `/proc/cpuinfo` before loading the library, so a CPU without i8mm never executes it.
- **Threads = cores outside the slowest cluster** (Tensor G4: 1 × X4 + 3 × A720 = 4). The A520 little cores would
  make every matrix product wait for the slowest thread.
- **Two cache slots, one per direction.** A conversation alternates ja→ne and ne→ja; each direction keeps the
  keys/values of its previous prompt and recomputes only from the first different token. The history grows to 6 turns
  and is then cut back to 3, so most prompts extend the previous one (measured: the 4th turn of a scene reused 164 of
  327 prompt tokens).
- **Greedy decoding** (the model card uses `do_sample=False`): the same input gives the same translation.
- `-O3` for llama.cpp in every build variant; memory-mapped model file.

## What the translator adds to the model (measured on a PC, 0.8B, Q4_K_M)

| | ja→ne chrF | ne→ja chrF |
|---|---|---|
| model alone (llama-cpp-python) | 45.3 | 34.1 |
| this engine, same prompt | 44.6 | 34.4 |
| + dictionary terms as PFML3 glossary | 51.4 | 37.0 |

40 + 40 sentences from the private phrase file, references = the official translations. The glossary was built partly
from the same teaching material, so its gain is an upper bound. The differences between the first two rows are
float rounding (another cache layout), not a different prompt: token ids are identical.

Conversation history fixed the bathing sentence that every model got wrong alone: 「麻痺がある場合には、健側からかけます」
→ स्वस्थ भागबाट **हालिन्छ** ("pour", alone: प्रहार "hit"); the Nepali sentence back to Japanese →
「麻痺がある場合は、健側から水をかけます。」 (alone: an invented bandage). One scene: encouraging, not a proof.

## Checks run

- x86-64: engine through JNI from Java, 80 sentences + the device sentences; `PlamoTest` (no model).
- aarch64: llama.cpp + engine cross-compiled with the phone flags (284 i8mm `smmla` instructions in the CPU backend) and
  run under `qemu-aarch64 -cpu max`: same translations as on x86. The app CMake block builds `libplamo_engine.so`
  with the cross compiler.
- Not run here: the Android (NDK) build and the phone itself. The release workflow builds the APK.

## Measure on the phone (Termux)

```sh
pkg install git cmake clang
git clone <this repository> && cd MagiTranslator
cmake -S app/src/main/cpp/src/libs/llama.cpp -B /tmp/b -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF \
  -DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+i8mm+fp16 -DGGML_OPENMP=OFF -DLLAMA_BUILD_COMMON=OFF \
  -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_APP=OFF -DBUILD_SHARED_LIBS=OFF
cmake --build /tmp/b -j 8
c++ -O3 -std=c++17 -march=armv8.6-a+dotprod+i8mm+fp16 -Iapp/src/main/cpp/src/libs/llama.cpp/include \
  -Iapp/src/main/cpp/src/libs/llama.cpp/ggml/include tools/llama/plamo_cli.cpp app/src/main/cpp/src/plamo/plamo_core.cpp \
  -o plamo_cli /tmp/b/src/libllama.a /tmp/b/ggml/src/libggml.a /tmp/b/ggml/src/libggml-cpu.a /tmp/b/ggml/src/libggml-base.a
curl -L -o plamo08.gguf https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/7de4409f84a9d70e3a5e87b81e84883d49f77b58/plamo-3-translate-0.8b-Q4_K_M.gguf
printf 'ja\t田中さんをベッドから車椅子に移動します\nne\tव्यायाम गर्न समस्या छैन कि?\n' | ./plamo_cli plamo08.gguf
```

The last line printed is the speed (generated tokens per second, prompt time per sentence). Try `./plamo_cli plamo08.gguf 4`
and `... 6` to compare thread counts. Downloading the model yourself means accepting the PLaMo Community License.

## Updating llama.cpp

Change the URL and hash in `vendor_llama_cpp.sh` (another llama-cpp-python sdist from PyPI, whose CHANGELOG names the
llama.cpp commit), run it, and update the row in `docs/VENDORED.md`. The llama.cpp API changes often (this version
deprecated `llama_decode` for `llama_process` + `llama_batch_ext`), so rebuild and rerun the checks above.
