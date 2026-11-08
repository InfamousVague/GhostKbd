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
    @JvmStatic external fun nativeTranscribe(context: Long, samples: FloatArray, threads: Int, prompt: String?): String
    @JvmStatic external fun nativeFree(context: Long)
}
