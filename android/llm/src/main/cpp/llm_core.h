// Ghost Keyboard's on-phone language model: one request in, the model's words out, through llama.cpp.
//
// Matt, 2026-11-12: "Add support for local AI models to work in the keyboard", and of what for: "please support them
// for rewriting and grammar checking". Rewrite and its Fix are a system prompt and the person's text in, and the text
// written again out, which is all this does. Nothing here knows about Android: llm_jni.cpp is the keyboard's way in,
// and tool/local_ai_try builds the same file on a desk, so a prompt can be tried against a model without a phone.
#pragma once

#include <atomic>
#include <string>
#include <utility>
#include <vector>

namespace ghostllm {

// A model that has been read from its file. Made by load, let go by unload, and used by one request at a time.
struct Model;

struct Request {
    std::string system;        // what to do
    // Worked examples, each a text and what was made of it, put to the model as earlier turns of the same talk. A
    // small model does what it is shown far more surely than what it is told.
    std::vector<std::pair<std::string, std::string>> shots;
    std::string user;          // the text to do it to
    int max_tokens = 256;      // the most it may write
    float temperature = 0.3f;  // 0 or less: always the likeliest next word
    unsigned seed = 42;        // the same seed and the same request write the same answer
    int threads = 4;
};

struct Result {
    bool ok = false;
    std::string text;      // the answer; or, where ok is false, why there is none
    int prompt_tokens = 0;
    int tokens = 0;        // how many it wrote
    double prompt_ms = 0;  // reading the request
    double write_ms = 0;   // writing the answer
    bool stopped = false;  // told to stop before it had finished
    bool full = false;     // it wrote max_tokens and had more to say
};

// Reads the model at `path`. Null, with `error` saying why, where it cannot be read.
Model * load(const std::string & path, std::string & error);

void unload(Model * model);

// How many tokens the model was trained to hold at once: a request and its answer must fit in that.
int window(const Model * model);

// Runs one request. `stop` is looked at between tokens and inside the model's own work: once it is true the request
// ends where it is, with what was written so far and `stopped` set.
Result complete(Model * model, const Request & request, const std::atomic<bool> & stop);

}  // namespace ghostllm
