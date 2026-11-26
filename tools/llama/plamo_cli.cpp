// Command-line check of the app's PLaMo engine (plamo_core.cpp), e.g. on a phone in Termux or under QEMU.
//   plamo_cli model.gguf [threads] [lora.gguf] < input.tsv      input: lang<TAB>text per line (ja or ne)
// Prints the translation, the prompt/generated token counts and the speed. The prompt is the same PFML3
// prompt the app sends without glossary or history (see PlamoPrompt.java).
#include <cstdio>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>

#include "../../app/src/main/cpp/src/plamo/plamo_core.h"

int main(int argc, char **argv) {
    if (argc < 2) { fprintf(stderr, "usage: %s model.gguf [threads] [lora.gguf] < lang<TAB>text lines\n", argv[0]); return 2; }
    int threads = argc > 2 ? atoi(argv[2]) : plamo::Engine::performance_cores();
    plamo::Engine *e = plamo::Engine::open(argv[1], threads, 1024);
    if (!e) { fprintf(stderr, "cannot load %s\n", argv[1]); return 1; }
    if (argc > 3 && !e->set_adapter(argv[3], 1.0f)) { fprintf(stderr, "cannot apply the adapter %s\n", argv[3]); return 1; }
    fprintf(stderr, "threads %d%s\n", threads, argc > 3 ? ", with adapter" : "");
    const std::vector<std::string> stops = {"<|plamo:tag|>", "<|plamo:bos|>"};
    std::string line;
    long long gen = 0, gen_us = 0, prompt_us = 0;
    int n = 0;
    while (std::getline(std::cin, line)) {
        size_t tab = line.find('\t');
        if (tab == std::string::npos) continue;
        std::string src = line.substr(0, tab), text = line.substr(tab + 1), tgt = src == "ja" ? "ne" : "ja";
        std::string p = "<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>" + src +
                        "<|plamo:msg|>" + text + "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>" + tgt +
                        (tgt == "ja" ? "<|plamo:key|>form<|plamo:val|>polite" : "") + "<|plamo:msg|>";
        std::string out;
        if (!e->complete(src == "ja" ? 0 : 1, p, 48 + (int) text.size() /* UTF-8 bytes: about 3x the characters, as in PlamoTranslator.maxTokens */, stops, &out)) { printf("%s\tERROR\n", text.c_str()); continue; }
        const long long *s = e->stats();
        printf("%s\t%s\t%s\t%lld/%lld prompt tokens reused\t%lld tokens\t%.2f s\n", src.c_str(), text.c_str(), out.c_str(), s[1], s[0], s[2], (s[3] + s[4]) / 1e6);
        fflush(stdout);
        gen += s[2]; gen_us += s[4]; prompt_us += s[3]; n++;
    }
    if (n) fprintf(stderr, "%d sentences: %.1f generated tokens/s, prompt %.0f ms per sentence\n", n, gen / (gen_us / 1e6), prompt_us / 1e3 / n);
    delete e;
    return 0;
}
