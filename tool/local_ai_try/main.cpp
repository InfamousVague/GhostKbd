// The keyboard's on-phone model on a desk: one request through llm_core.cpp, the answer on standard output and how
// long it took on standard error. See CMakeLists.txt beside this file.
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>

#include "llm_core.h"

static std::string slurp(const char * path) {
    std::ifstream in(path, std::ios::binary);
    std::stringstream s;
    s << in.rdbuf();
    std::string text = s.str();
    while (!text.empty() && (text.back() == '\n' || text.back() == '\r')) text.pop_back();
    return text;
}

int main(int argc, char ** argv) {
    if (argc < 4) {
        std::fprintf(stderr, "usage: %s <model.gguf> <system.txt> <text.txt> [max tokens] [temperature] [threads] [seed] [example-text.txt example-answer.txt ...]\n", argv[0]);
        return 2;
    }
    std::string error;
    ghostllm::Model * model = ghostllm::load(argv[1], error);
    if (!model) {
        std::fprintf(stderr, "%s\n", error.c_str());
        return 1;
    }
    ghostllm::Request request;
    request.system = slurp(argv[2]);
    request.user = slurp(argv[3]);
    if (argc > 4) request.max_tokens = std::atoi(argv[4]);
    if (argc > 5) request.temperature = (float) std::atof(argv[5]);
    if (argc > 6) request.threads = std::atoi(argv[6]);
    if (argc > 7) request.seed = (unsigned) std::strtoul(argv[7], nullptr, 10);
    for (int i = 8; i + 1 < argc; i += 2) request.shots.push_back({slurp(argv[i]), slurp(argv[i + 1])});
    std::atomic<bool> stop(false);
    const ghostllm::Result result = ghostllm::complete(model, request, stop);
    ghostllm::unload(model);
    if (!result.ok) {
        std::fprintf(stderr, "%s\n", result.text.c_str());
        return 1;
    }
    std::printf("%s\n", result.text.c_str());
    std::fprintf(stderr, "[%d tokens read in %.0f ms, %d written in %.0f ms (%.1f a second)%s]\n", result.prompt_tokens, result.prompt_ms,
                 result.tokens, result.write_ms, result.write_ms > 0 ? result.tokens * 1000.0 / result.write_ms : 0.0, result.full ? ", cut at the limit" : "");
    return 0;
}
