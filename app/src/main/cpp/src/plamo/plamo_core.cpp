#include "plamo_core.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <thread>

#include "llama.h"

namespace plamo {
namespace {

std::once_flag g_backend_once;

void quiet_log(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) fputs(text, stderr);
}

long long now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

std::vector<int> tokenize(const llama_vocab *vocab, const std::string &text) {
    // add_special: BOS when the model asks for it (PLaMo does); parse_special: the <|plamo:...|> markers
    int n = -llama_tokenize(vocab, text.data(), (int32_t) text.size(), nullptr, 0, true, true);
    std::vector<int> out(std::max(n, 0));
    if (n > 0 && llama_tokenize(vocab, text.data(), (int32_t) text.size(), out.data(), n, true, true) != n) out.clear();
    return out;
}

std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof(buf), 0, true);   // special=true: control tokens are visible
    if (n < 0) {
        std::string big((size_t) -n, '\0');
        n = llama_token_to_piece(vocab, t, &big[0], (int32_t) big.size(), 0, true);
        return n < 0 ? std::string() : big.substr(0, (size_t) n);
    }
    return std::string(buf, (size_t) n);
}

llama_token argmax(const float *logits, int n) {
    int best = 0;
    float v = logits[0];
    for (int i = 1; i < n; i++) if (logits[i] > v) { v = logits[i]; best = i; }
    return (llama_token) best;
}

}  // namespace

Engine *Engine::open(const std::string &path, int threads, int slot_ctx) {
    std::call_once(g_backend_once, [] {
        llama_log_set(quiet_log, nullptr);
        llama_backend_init();
    });
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    // memory-mapped file; the CPU backend copies the Q4 weights it repacks for the i8mm kernels
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    mp.use_extra_bufts = true;
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) return nullptr;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) slot_ctx * SLOTS;   // unified cache shared by the slots, slot_ctx each
    cp.n_batch = (uint32_t) slot_ctx;
    cp.n_ubatch = 512;
    cp.n_seq_max = SLOTS;
    cp.kv_unified = true;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    cp.swa_full = true;   // PLaMo has sliding-window layers: a full cache lets the prefix reuse cut anywhere
    cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return nullptr; }

    auto *e = new Engine();
    e->model_ = model;
    e->ctx_ = ctx;
    e->vocab_ = llama_model_get_vocab(model);
    e->batch_ = llama_batch_ext_init(ctx);
    e->slot_ctx_ = (int) llama_n_ctx(ctx) / SLOTS;
    e->n_batch_ = (int) llama_n_batch(ctx);
    return e;
}

Engine::~Engine() {
    if (batch_) llama_batch_ext_free(batch_);
    if (ctx_) llama_free(ctx_);
    if (adapter_) llama_adapter_lora_free(adapter_);   // before the model it belongs to
    if (model_) llama_model_free(model_);
}

bool Engine::set_adapter(const std::string &path, float scale) {
    llama_adapter_lora *next = nullptr;
    if (!path.empty()) {
        next = llama_adapter_lora_init(model_, path.c_str());
        if (!next) return false;
    }
    if (llama_set_adapters_lora(ctx_, next ? &next : nullptr, next ? 1 : 0, next ? &scale : nullptr) != 0) {
        if (next) llama_adapter_lora_free(next);
        return false;
    }
    if (adapter_) llama_adapter_lora_free(adapter_);
    adapter_ = next;
    for (int s = 0; s < SLOTS; s++) drop(s);
    return true;
}

void Engine::drop(int slot) {
    llama_memory_seq_rm(llama_get_memory(ctx_), slot, -1, -1);   // removing a whole sequence never fails
    cached_[slot].clear();
}

// feeds tokens [from, to) at positions from.. in chunks of n_batch; logits only for the last token
bool Engine::feed(int slot, const std::vector<int> &toks, size_t from, size_t to) {
    for (size_t s = from; s < to; s += (size_t) n_batch_) {
        size_t end = std::min(to, s + (size_t) n_batch_);
        llama_batch_ext_clear(batch_);
        for (size_t i = s; i < end; i++) {
            int32_t idx = llama_batch_ext_add_token(batch_, slot, toks[i]);
            if (idx < 0) return false;
            llama_pos pos = (llama_pos) i;
            llama_batch_ext_set_pos(batch_, idx, &pos);
        }
        if (end == to) llama_batch_ext_set_output_logits(batch_, (int32_t) (end - s - 1), true);
        if (llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, batch_) != 0) return false;
    }
    return true;
}

bool Engine::complete(int slot, const std::string &prompt, int max_tokens, const std::vector<std::string> &stops, std::string *out) {
    out->clear();
    if (slot < 0 || slot >= SLOTS || max_tokens < 0) return false;
    long long t0 = now_us();
    std::vector<int> toks = tokenize(vocab_, prompt);
    if (toks.empty() || (int) toks.size() + max_tokens > slot_ctx_) return false;

    // reuse the cached prefix; at least the last prompt token is recomputed to get its logits
    std::vector<int> &cached = cached_[slot];
    size_t keep = 0;
    while (keep < cached.size() && keep < toks.size() && cached[keep] == toks[keep]) keep++;
    if (keep == toks.size()) keep--;
    llama_memory_t mem = llama_get_memory(ctx_);
    if (!llama_memory_seq_rm(mem, slot, (llama_pos) keep, -1)) { drop(slot); keep = 0; }
    cached.assign(toks.begin(), toks.begin() + (long) keep);
    if (!feed(slot, toks, keep, toks.size())) { drop(slot); return false; }
    cached = toks;
    long long t1 = now_us();

    const int n_vocab = llama_vocab_n_tokens(vocab_);
    int generated = 0;
    bool ok = true;
    for (; generated < max_tokens; generated++) {
        const float *logits = llama_get_logits_ith(ctx_, -1);
        if (!logits) { ok = false; break; }
        llama_token t = argmax(logits, n_vocab);
        if (llama_vocab_is_eog(vocab_, t)) break;
        *out += piece(vocab_, t);
        // a stop string can be split over several tokens, so the whole output is searched after each token
        bool stop = false;
        for (const auto &s : stops) {
            size_t at = s.empty() ? std::string::npos : out->find(s);
            if (at != std::string::npos) { out->resize(at); stop = true; break; }
        }
        if (stop) break;
        llama_batch_ext_clear(batch_);
        int32_t idx = llama_batch_ext_add_token(batch_, slot, t);
        llama_pos pos = (llama_pos) cached.size();
        llama_batch_ext_set_pos(batch_, idx, &pos);
        llama_batch_ext_set_output_logits(batch_, idx, true);
        if (llama_process(ctx_, LLAMA_PROCESS_TYPE_DECODE, batch_) != 0) { ok = false; break; }
        cached.push_back(t);
    }
    long long t2 = now_us();
    stats_[0] = (long long) toks.size();
    stats_[1] = (long long) keep;
    stats_[2] = generated;
    stats_[3] = t1 - t0;
    stats_[4] = t2 - t1;
    if (!ok) { drop(slot); out->clear(); return false; }
    return true;
}

// Number of cores outside the slowest cluster (big + middle cores). The little cores of a Tensor G4/G5
// (Cortex-A520) are several times slower and would hold the others back at the end of each matrix
// product, so they are left out. All cores alike (a PC): all of them. Unreadable cpufreq: half.
int Engine::performance_cores() {
    std::vector<long> freq;
    for (int i = 0; i < 64; i++) {
        char path[96];
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE *f = fopen(path, "r");
        if (!f) break;
        long v = 0;
        if (fscanf(f, "%ld", &v) != 1) v = 0;
        fclose(f);
        freq.push_back(v);
    }
    unsigned hw = std::thread::hardware_concurrency();
    if (freq.empty() || std::count(freq.begin(), freq.end(), 0L) > 0) return (int) std::max(1u, hw / 2);
    long lowest = *std::min_element(freq.begin(), freq.end());
    int n = (int) std::count_if(freq.begin(), freq.end(), [&](long v) { return v > lowest; });
    return n > 0 ? n : (int) freq.size();
}

}  // namespace plamo
