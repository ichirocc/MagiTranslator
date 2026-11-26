// MagiTranslator: on-device PLaMo 3 Translate through llama.cpp (CPU, GGUF). Plain C++ (no JNI) so the
// same code is tested on a PC and on aarch64 under QEMU; plamo_engine.cpp is the JNI layer on top.
#pragma once

#include <string>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_batch_ext;
struct llama_vocab;
struct llama_adapter_lora;

namespace plamo {

// One model and one context. Not thread-safe: calls must be serialised (the Java wrapper is synchronized).
// Decoding is greedy (the model card uses do_sample=False), so the same prompt always gives the same text.
// The context has SLOTS sequences (one per translation direction); in each, the KV cache of the previous
// prompt is reused up to the first differing token: consecutive prompts of a direction share the PFML3
// header and the conversation history, so only the new part is computed.
class Engine {
public:
    static constexpr int SLOTS = 2;

    // nullptr when the file cannot be loaded. slot_ctx = tokens per slot (prompt + output).
    static Engine *open(const std::string &path, int threads, int slot_ctx);
    ~Engine();

    // Greedy continuation of prompt (UTF-8, PFML3 markers are parsed as special tokens) in a slot. Stops at
    // an end-of-generation token, before any of the stop strings, or after max_tokens. False on error
    // (prompt longer than the slot, decode failure); the slot's cache is then dropped.
    bool complete(int slot, const std::string &prompt, int max_tokens, const std::vector<std::string> &stops, std::string *out);

    // Applies a LoRA adapter (GGUF, made for this model) with the given scale; an empty path removes it.
    // Both slots' caches are dropped (their keys/values were computed with the previous weights).
    bool set_adapter(const std::string &path, float scale);

    // last call: prompt tokens, of which reused from the cache, generated tokens, prompt us, generation us
    const long long *stats() const { return stats_; }

    // cores outside the slowest cluster (big + middle cores); see the .cpp
    static int performance_cores();

private:
    Engine() = default;
    bool feed(int slot, const std::vector<int> &toks, size_t from, size_t to);
    void drop(int slot);

    ::llama_model *model_ = nullptr;
    ::llama_context *ctx_ = nullptr;
    ::llama_batch_ext *batch_ = nullptr;
    const ::llama_vocab *vocab_ = nullptr;
    struct ::llama_adapter_lora *adapter_ = nullptr;
    std::vector<int> cached_[SLOTS];   // per slot: tokens whose keys/values are in the cache, in order
    int slot_ctx_ = 0, n_batch_ = 0;
    long long stats_[5] = {0, 0, 0, 0, 0};
};

}  // namespace plamo
