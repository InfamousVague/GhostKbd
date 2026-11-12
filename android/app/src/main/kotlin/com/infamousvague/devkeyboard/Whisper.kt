package com.infamousvague.devkeyboard

/** whisper.cpp, through libdevkeyboard_whisper.so (src/main/cpp). Loaded the first time dictation needs it. */
object Whisper {
    private var loaded = false

    @Synchronized
    fun load(): Boolean {
        if (!loaded) loaded = runCatching { System.loadLibrary("devkeyboard_whisper") }.isSuccess
        return loaded
    }

    @JvmStatic external fun nativeInit(path: String): Long
    /** [language]: a whisper code ("en", "es"), or "auto" to detect it. */
    @JvmStatic external fun nativeTranscribe(context: Long, samples: FloatArray, threads: Int, prompt: String?, language: String): String
    /**
     * A quick draft of sound that is still being spoken (LiveWords.kt): whisper's window cut to [audioCtx] frames (0:
     * all of it), one segment, no second try. Empty where [nativeAbortDraft] gave it up.
     */
    @JvmStatic external fun nativeDraft(context: Long, samples: FloatArray, threads: Int, prompt: String?, language: String, audioCtx: Int): String
    /** Gives up the draft being made, from another thread: a piece that is over is waiting to be written down. */
    @JvmStatic external fun nativeAbortDraft()
    @JvmStatic external fun nativeFree(context: Long)
}
