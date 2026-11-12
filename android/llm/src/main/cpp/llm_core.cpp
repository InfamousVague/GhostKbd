// See llm_core.h. Written against llama.cpp v0.9.0 (its examples/simple-chat is the pattern): the batch is a
// llama_batch_ext, a step is llama_process, and the context's memory is asked how far it has got.
#include "llm_core.h"

#include <algorithm>
#include <chrono>
#include <mutex>
#include <vector>

#include "llama.h"

namespace ghostllm {

struct Model {
    llama_model * model = nullptr;
    const llama_vocab * vocab = nullptr;
    std::string chat_template;  // the model's own, as its file gives it; empty where it gives none
    bool thinks = false;        // its template knows about <think>: it reasons before it answers unless told not to
};

namespace {

// The most a request and its answer may take, whatever the model could hold: a keyboard's text is short, and a
// phone's memory is not a desk's.
const int MAX_WINDOW = 8192;
const int MIN_WINDOW = 512;

// An empty thought after the assistant's header, which is how Qwen's own templates switch reasoning off. A model
// that reasons first writes hundreds of tokens before the first word of the answer, and a rewrite of one line would
// take a minute. (Ghost.md's prompt.rs does the same, for the same models.)
const char * EMPTY_THOUGHT = "<think>\n\n</think>\n\n";

double ms_since(std::chrono::steady_clock::time_point from) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - from).count();
}

// llama.cpp's own log: errors only, and to nowhere on a phone (the system log is no place for someone's words).
void quiet(enum ggml_log_level, const char *, void *) {}

std::once_flag started;

// The request as the model's chat template frames it, ending where the assistant is to begin.
std::string frame(const Model & m, const Request & r) {
    std::vector<llama_chat_message> chat;
    chat.push_back({"system", r.system.c_str()});
    size_t chars = r.system.size() + r.user.size();
    for (const auto & shot : r.shots) {
        chat.push_back({"user", shot.first.c_str()});
        chat.push_back({"assistant", shot.second.c_str()});
        chars += shot.first.size() + shot.second.size();
    }
    chat.push_back({"user", r.user.c_str()});
    std::string out;
    int32_t n = -1;
    if (!m.chat_template.empty()) {
        std::vector<char> buf(2 * chars + 2048);
        n = llama_chat_apply_template(m.chat_template.c_str(), chat.data(), chat.size(), true, buf.data(), (int32_t) buf.size());
        if (n > (int32_t) buf.size()) {
            buf.resize((size_t) n);
            n = llama_chat_apply_template(m.chat_template.c_str(), chat.data(), chat.size(), true, buf.data(), (int32_t) buf.size());
        }
        if (n >= 0) out.assign(buf.data(), (size_t) n);
    }
    // A template llama.cpp does not know by its marks, or a file with none: ChatML, which is what the Qwen models
    // the keyboard offers are trained on.
    if (n < 0) {
        out.clear();
        for (const auto & message : chat) out += std::string("<|im_start|>") + message.role + "\n" + message.content + "<|im_end|>\n";
        out += "<|im_start|>assistant\n";
    }
    if (m.thinks) out += EMPTY_THOUGHT;
    return out;
}

// The batch filled with `n` tokens at consecutive places from `at`, asking for what follows the last of them.
bool fill(llama_batch_ext * batch, const llama_token * tokens, int32_t n, llama_pos at) {
    llama_batch_ext_clear(batch);
    for (int32_t i = 0; i < n; ++i) {
        const int32_t idx = llama_batch_ext_add_token(batch, 0, tokens[i]);
        if (idx < 0) return false;
        const llama_pos pos = at + i;
        if (!llama_batch_ext_set_pos(batch, idx, &pos)) return false;
    }
    return llama_batch_ext_set_output_logits(batch, n - 1, true);
}

// A cut may fall inside a character of several bytes: what is left of one at the end is taken off.
void trim_partial_utf8(std::string & s) {
    size_t i = s.size();
    int back = 0;
    while (i > 0 && back < 4 && ((unsigned char) s[i - 1] & 0xC0) == 0x80) { --i; ++back; }
    if (i == 0) return;
    const unsigned char lead = (unsigned char) s[i - 1];
    const int want = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    if (want > 1 && back + 1 < want) s.resize(i - 1);
}

}  // namespace

Model * load(const std::string & path, std::string & error) {
    std::call_once(started, [] {
        llama_log_set(quiet, nullptr);
        llama_backend_init();
    });
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), params);
    if (!model) {
        error = "The model could not be read.";
        return nullptr;
    }
    Model * m = new Model();
    m->model = model;
    m->vocab = llama_model_get_vocab(model);
    if (const char * t = llama_model_chat_template(model, nullptr)) m->chat_template = t;
    m->thinks = m->chat_template.find("<think>") != std::string::npos;
    return m;
}

void unload(Model * model) {
    if (!model) return;
    if (model->model) llama_model_free(model->model);
    delete model;
}

int window(const Model * model) {
    return model && model->model ? std::min(MAX_WINDOW, (int) llama_model_n_ctx_train(model->model)) : 0;
}

Result complete(Model * m, const Request & r, const std::atomic<bool> & stop) {
    Result out;
    if (!m || !m->model) {
        out.text = "There is no model.";
        return out;
    }
    const std::string prompt = frame(*m, r);

    // The request as tokens. The template's own marks (<|im_start|>) are read as the tokens they are.
    const int32_t wanted = -llama_tokenize(m->vocab, prompt.c_str(), (int32_t) prompt.size(), nullptr, 0, true, true);
    if (wanted <= 0) {
        out.text = "The text could not be read by the model.";
        return out;
    }
    std::vector<llama_token> tokens((size_t) wanted);
    if (llama_tokenize(m->vocab, prompt.c_str(), (int32_t) prompt.size(), tokens.data(), (int32_t) tokens.size(), true, true) < 0) {
        out.text = "The text could not be read by the model.";
        return out;
    }
    out.prompt_tokens = (int) tokens.size();

    // A window sized to the job: the request, the answer and a little over, no more than the model can hold.
    const int limit = window(m);
    const int max_tokens = std::max(1, r.max_tokens);
    if (out.prompt_tokens + std::min(max_tokens, 64) + 16 > limit) {
        out.text = "That is too long for the model on this phone: select a part of it.";
        return out;
    }
    int n_ctx = out.prompt_tokens + max_tokens + 16;
    n_ctx = ((n_ctx + 255) / 256) * 256;
    n_ctx = std::max(MIN_WINDOW, std::min(limit, n_ctx));
    const int room = n_ctx - out.prompt_tokens - 8;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) n_ctx;
    cparams.n_batch = (uint32_t) n_ctx;
    cparams.n_threads = std::max(1, r.threads);
    cparams.n_threads_batch = std::max(1, r.threads);
    cparams.no_perf = true;
    cparams.abort_callback = [](void * data) { return static_cast<const std::atomic<bool> *>(data)->load(); };
    cparams.abort_callback_data = const_cast<std::atomic<bool> *>(&stop);
    llama_context * ctx = llama_init_from_model(m->model, cparams);
    if (!ctx) {
        out.text = "There is not enough memory free for the model.";
        return out;
    }

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (r.temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        // Ghost.md's settings for the same models: a light hand against repeating, the likely words only, then chance.
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(m->vocab), 256, 1.05f, 0.0f, 0.0f));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_min_p(0.05f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(r.temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(r.seed));
    }
    llama_batch_ext * batch = llama_batch_ext_init(ctx);

    std::string text;
    bool failed = false;
    auto started_at = std::chrono::steady_clock::now();
    llama_pos at = 0;

    // The request, read in one step.
    if (!fill(batch, tokens.data(), (int32_t) tokens.size(), at) || llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
        failed = !stop.load();
    } else {
        at += (llama_pos) tokens.size();
        out.prompt_ms = ms_since(started_at);
        started_at = std::chrono::steady_clock::now();
        // The answer, a token at a time.
        while (true) {
            if (stop.load()) break;
            llama_token token = llama_sampler_sample(sampler, ctx, -1);
            if (llama_vocab_is_eog(m->vocab, token)) break;
            char piece[256];
            const int32_t n = llama_token_to_piece(m->vocab, token, piece, (int32_t) sizeof(piece), 0, false);
            if (n > 0) text.append(piece, (size_t) n);
            out.tokens++;
            if (out.tokens >= max_tokens || out.tokens >= room) { out.full = true; break; }
            if (!fill(batch, &token, 1, at) || llama_process(ctx, LLAMA_PROCESS_TYPE_DECODE, batch) != 0) {
                failed = !stop.load();
                break;
            }
            at += 1;
        }
        out.write_ms = ms_since(started_at);
    }

    llama_batch_ext_free(batch);
    llama_sampler_free(sampler);
    llama_free(ctx);

    out.stopped = stop.load();
    if (failed) {
        out.text = "The model stopped before it had finished.";
        return out;
    }
    trim_partial_utf8(text);
    out.text = text;
    out.ok = true;
    return out;
}

}  // namespace ghostllm
