package com.infamousvague.devkeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The dictation plugin's model: whisper's base.en, 5-bit quantised, Ghost.md's own dictation model (its
 * tauri/src-tauri/src/whisper/model.rs `BASE_EN_Q5_1`): 59.7 MB, English, the file and hash Hugging Face's
 * ggerganov/whisper.cpp serves. Fetched from Ghost.md's mirror first and Hugging Face second, the same order Ghost.md
 * tries; the SHA-256 decides what is kept either way, so the order is about reliability, not trust.
 */
object WhisperModel {
    const val FILE = "ggml-base.en-q5_1.bin"
    const val BYTES = 59_721_011L
    const val SHA256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
    val MIRRORS = listOf("https://attack.fm/glyph/models", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main")

    /** missing, downloading, ready or failed; [progress] 0..1 while downloading. */
    data class State(val phase: String, val progress: Double? = null, val error: String? = null) {
        fun toMap(): Map<String, Any?> = mapOf("phase" to phase, "progress" to progress, "error" to error, "bytes" to BYTES)
    }

    @Volatile var state = State("missing")
        private set
    private val listeners = mutableSetOf<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var cancelled = false

    fun addListener(l: (State) -> Unit) { listeners += l }
    fun removeListener(l: (State) -> Unit) { listeners -= l }
    private fun set(next: State) { state = next; main.post { listeners.toList().forEach { it(next) } } }

    fun file(context: Context) = File(File(context.applicationContext.filesDir, "models").apply { mkdirs() }, FILE)

    fun isReady(context: Context) = file(context).length() == BYTES

    /** Where the model stands on this phone, without touching the network. */
    fun refresh(context: Context) {
        if (state.phase == "downloading") return
        set(if (isReady(context)) State("ready") else State("missing"))
    }

    fun download(context: Context) {
        val app = context.applicationContext
        if (state.phase == "downloading" || isReady(app)) { refresh(app); return }
        cancelled = false
        set(State("downloading", 0.0))
        io.execute {
            var lastError: String? = null
            for (mirror in MIRRORS) {
                if (cancelled) break
                try {
                    fetch(app, "$mirror/$FILE")
                    set(State("ready"))
                    return@execute
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
            set(if (cancelled) State("missing") else State("failed", error = lastError ?: "The model could not be downloaded."))
        }
    }

    fun cancel() { cancelled = true }

    fun delete(context: Context) {
        cancelled = true
        file(context).delete()
        File(file(context).path + ".part").delete()
        set(State("missing"))
    }

    /** One mirror: resumed with a Range where a part is already here, then held to the size and the hash. */
    private fun fetch(context: Context, url: String) {
        val target = file(context)
        val part = File(target.path + ".part")
        val have = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (have in 1 until BYTES) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val resumed = conn.responseCode == 206
            if (conn.responseCode != 200 && !resumed) throw IllegalStateException("$url answered ${conn.responseCode}")
            var got = if (resumed) have else 0L
            var shown = -1
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelled) throw IllegalStateException("Cancelled.")
                        val n = input.read(buf)
                        if (n < 0) break
                        got += n
                        if (got > BYTES) throw IllegalStateException("The model is larger than it should be.")
                        out.write(buf, 0, n)
                        val pct = (got * 100 / BYTES).toInt()
                        if (pct != shown) { shown = pct; set(State("downloading", got.toDouble() / BYTES)) }
                    }
                }
            }
            if (part.length() != BYTES) throw IllegalStateException("The download stopped short.")
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != SHA256) { part.delete(); throw IllegalStateException("The model does not match its hash.") }
            if (!part.renameTo(target)) throw IllegalStateException("Could not keep the model.")
        } finally {
            conn.disconnect()
        }
    }
}
