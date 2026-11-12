// The calls Whisper.kt makes into whisper.cpp: load a model, transcribe a recording, make a quick draft of one that
// is still being spoken (and give a draft up), free the model.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include "whisper.h"

#define TAG "DevKeyboardWhisper"

// Set to give up the draft being made: a piece that is over is waiting to be written down properly.
static std::atomic<bool> g_draft_abort{false};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_infamousvague_devkeyboard_Whisper_nativeInit(JNIEnv *env, jclass, jstring path) {
    const char *model = env->GetStringUTFChars(path, nullptr);
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(model, params);
    env->ReleaseStringUTFChars(path, model);
    if (ctx == nullptr) __android_log_print(ANDROID_LOG_ERROR, TAG, "could not load the model");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_infamousvague_devkeyboard_Whisper_nativeTranscribe(JNIEnv *env, jclass, jlong handle, jfloatArray samples, jint threads, jstring prompt, jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");
    jsize count = env->GetArrayLength(samples);
    jfloat *audio = env->GetFloatArrayElements(samples, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    // "auto" makes whisper detect the language from the first seconds; English-only models are always given "en".
    const char *lang = env->GetStringUTFChars(language, nullptr);
    params.language = lang;
    params.detect_language = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.suppress_blank = true;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    const char *hint = prompt == nullptr ? nullptr : env->GetStringUTFChars(prompt, nullptr);
    params.initial_prompt = hint;

    std::string text;
    if (whisper_full(ctx, params, audio, count) == 0) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(ctx, i);
    } else {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "transcription failed");
    }

    if (hint != nullptr) env->ReleaseStringUTFChars(prompt, hint);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
    return env->NewStringUTF(text.c_str());
}

// A draft of a piece that is still being spoken (LiveWords.kt): the same sound as nativeTranscribe would be given,
// made quickly. whisper's window is cut to audioCtx frames (0: its whole thirty seconds), it writes one segment with
// no time marks, and it does not try again at a higher temperature. nativeAbortDraft gives it up part way: the
// answer is then empty.
JNIEXPORT jstring JNICALL
Java_com_infamousvague_devkeyboard_Whisper_nativeDraft(JNIEnv *env, jclass, jlong handle, jfloatArray samples, jint threads, jstring prompt, jstring language, jint audioCtx) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) return env->NewStringUTF("");
    jsize count = env->GetArrayLength(samples);
    jfloat *audio = env->GetFloatArrayElements(samples, nullptr);

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads;
    const char *lang = env->GetStringUTFChars(language, nullptr);
    params.language = lang;
    params.detect_language = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = true;
    params.no_timestamps = true;
    params.suppress_blank = true;
    params.temperature_inc = 0.0f;
    params.audio_ctx = audioCtx;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_special = false;
    params.print_timestamps = false;
    params.abort_callback = [](void *flag) { return static_cast<std::atomic<bool> *>(flag)->load(); };
    params.abort_callback_user_data = &g_draft_abort;
    const char *hint = prompt == nullptr ? nullptr : env->GetStringUTFChars(prompt, nullptr);
    params.initial_prompt = hint;

    std::string text;
    g_draft_abort.store(false);
    if (whisper_full(ctx, params, audio, count) == 0 && !g_draft_abort.load()) {
        const int segments = whisper_full_n_segments(ctx);
        for (int i = 0; i < segments; ++i) text += whisper_full_get_segment_text(ctx, i);
    }

    if (hint != nullptr) env->ReleaseStringUTFChars(prompt, hint);
    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(samples, audio, JNI_ABORT);
    return env->NewStringUTF(text.c_str());
}

JNIEXPORT void JNICALL
Java_com_infamousvague_devkeyboard_Whisper_nativeAbortDraft(JNIEnv *, jclass) {
    g_draft_abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_infamousvague_devkeyboard_Whisper_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) whisper_free(ctx);
}

}
