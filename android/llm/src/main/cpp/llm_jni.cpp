// The keyboard's way into its on-phone language model (Llm.kt): llm_core.cpp, one model and one request at a time,
// in a process of its own (LlmService.kt). Text crosses as UTF-8 bytes, never as Java's strings: JNI's own string
// functions use a modified UTF-8 in which an emoji is two broken halves, and a model given those reads nonsense.
#include <jni.h>

#include <atomic>
#include <mutex>
#include <string>

#include "llm_core.h"

namespace {

std::atomic<bool> g_stop(false);
std::mutex g_mutex;        // one request at a time
std::string g_error;       // why the last load or request gave nothing
ghostllm::Result g_last;   // the last request's counts

std::string bytes(JNIEnv * env, jbyteArray array) {
    if (!array) return std::string();
    const jsize n = env->GetArrayLength(array);
    std::string out((size_t) n, '\0');
    if (n > 0) env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_infamousvague_devkeyboard_Llm_nativeLoad(JNIEnv * env, jclass, jstring path) {
    const char * chars = env->GetStringUTFChars(path, nullptr);
    std::string file(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(path, chars);
    std::lock_guard<std::mutex> lock(g_mutex);
    g_error.clear();
    return reinterpret_cast<jlong>(ghostllm::load(file, g_error));
}

JNIEXPORT jint JNICALL
Java_com_infamousvague_devkeyboard_Llm_nativeWindow(JNIEnv *, jclass, jlong handle) {
    return ghostllm::window(reinterpret_cast<ghostllm::Model *>(handle));
}

// The answer as UTF-8 bytes, or null where there is none (nativeError says why). `shots` is the worked examples,
// a text and then what was made of it, and so on.
JNIEXPORT jbyteArray JNICALL
Java_com_infamousvague_devkeyboard_Llm_nativeComplete(JNIEnv * env, jclass, jlong handle, jbyteArray system, jobjectArray shots, jbyteArray user,
                                                      jint maxTokens, jfloat temperature, jint seed, jint threads) {
    ghostllm::Request request;
    request.system = bytes(env, system);
    request.user = bytes(env, user);
    const jsize count = shots ? env->GetArrayLength(shots) : 0;
    for (jsize i = 0; i + 1 < count; i += 2) {
        jbyteArray text = static_cast<jbyteArray>(env->GetObjectArrayElement(shots, i));
        jbyteArray made = static_cast<jbyteArray>(env->GetObjectArrayElement(shots, i + 1));
        request.shots.push_back({bytes(env, text), bytes(env, made)});
        env->DeleteLocalRef(text);
        env->DeleteLocalRef(made);
    }
    request.max_tokens = maxTokens;
    request.temperature = temperature;
    request.seed = (unsigned) seed;
    request.threads = threads;

    std::lock_guard<std::mutex> lock(g_mutex);
    g_error.clear();
    g_last = ghostllm::complete(reinterpret_cast<ghostllm::Model *>(handle), request, g_stop);
    if (!g_last.ok) {
        g_error = g_last.text;
        return nullptr;
    }
    jbyteArray out = env->NewByteArray((jsize) g_last.text.size());
    if (out && !g_last.text.empty()) env->SetByteArrayRegion(out, 0, (jsize) g_last.text.size(), reinterpret_cast<const jbyte *>(g_last.text.data()));
    return out;
}

// The last request's counts: tokens read, tokens written, ms reading, ms writing, 1 if cut at its limit, 1 if stopped.
JNIEXPORT jintArray JNICALL
Java_com_infamousvague_devkeyboard_Llm_nativeStats(JNIEnv * env, jclass) {
    const jint values[6] = {g_last.prompt_tokens, g_last.tokens, (jint) g_last.prompt_ms, (jint) g_last.write_ms, g_last.full ? 1 : 0, g_last.stopped ? 1 : 0};
    jintArray out = env->NewIntArray(6);
    if (out) env->SetIntArrayRegion(out, 0, 6, values);
    return out;
}

// Why the last load or request gave nothing, as UTF-8 bytes of plain words (never anyone's text).
JNIEXPORT jbyteArray JNICALL
Java_com_infamousvague_devkeyboard_Llm_nativeError(JNIEnv * env, jclass) {
    jbyteArray out = env->NewByteArray((jsize) g_error.size());
    if (out && !g_error.empty()) env->SetByteArrayRegion(out, 0, (jsize) g_error.size(), reinterpret_cast<const jbyte *>(g_error.data()));
    return out;
}

// From any thread: the request being run ends where it is. Cleared by nativeGo before the next one.
JNIEXPORT void JNICALL
Java_com_infamousvague_devkeyboard_Llm_nativeStop(JNIEnv *, jclass) {
    g_stop.store(true);
}

JNIEXPORT void JNICALL
Java_com_infamousvague_devkeyboard_Llm_nativeGo(JNIEnv *, jclass) {
    g_stop.store(false);
}

JNIEXPORT void JNICALL
Java_com_infamousvague_devkeyboard_Llm_nativeFree(JNIEnv *, jclass, jlong handle) {
    std::lock_guard<std::mutex> lock(g_mutex);
    ghostllm::unload(reinterpret_cast<ghostllm::Model *>(handle));
}

}  // extern "C"
