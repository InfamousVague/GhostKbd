package com.infamousvague.devkeyboard

/**
 * llama.cpp, through libdevkeyboard_llm.so (android/llm): the on-phone language model that Rewrite and its Fix can
 * run on (LocalAi.kt). Loaded only in the model's own process (LlmService.kt), the first time a request needs it.
 *
 * Text crosses as UTF-8 bytes, not as strings: JNI's strings are a modified UTF-8 in which an emoji is two broken
 * halves, and a model given those reads nonsense.
 */
object Llm {
    private var loaded = false

    @Synchronized
    fun load(): Boolean {
        if (!loaded) loaded = runCatching { System.loadLibrary("devkeyboard_llm") }.isSuccess
        return loaded
    }

    /** Reads the model file at [path]. 0 where it cannot be read ([nativeError] says why). */
    @JvmStatic external fun nativeLoad(path: String): Long

    /** How many tokens a request and its answer may take together. */
    @JvmStatic external fun nativeWindow(handle: Long): Int

    /**
     * One request: [system] says what to do, [shots] are worked examples (a text, then what was made of it, and so
     * on), [user] is the text. The answer, or null where there is none ([nativeError] says why). Blocking.
     */
    @JvmStatic external fun nativeComplete(handle: Long, system: ByteArray, shots: Array<ByteArray>, user: ByteArray, maxTokens: Int, temperature: Float, seed: Int, threads: Int): ByteArray?

    /** The last request's counts: tokens read, tokens written, ms reading, ms writing, 1 if cut at its limit, 1 if stopped. */
    @JvmStatic external fun nativeStats(): IntArray

    /** Why the last load or request gave nothing: plain words, never anyone's text. */
    @JvmStatic external fun nativeError(): ByteArray

    /** From any thread: the request being run ends where it is. [nativeGo] lets the next one run. */
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeGo()

    @JvmStatic external fun nativeFree(handle: Long)
}
