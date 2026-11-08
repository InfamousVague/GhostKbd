package com.infamousvague.devkeyboard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max

/**
 * The dictation plugin's work: record from the microphone at whisper's 16 kHz, then transcribe the whole take with
 * whisper.cpp on a background thread and hand the words back. One take at a time; the model stays loaded between
 * takes while the keyboard lives.
 */
class Dictation(private val context: Context) {
    interface Listener {
        /** How loud it is now, 0..1, a few times a second while listening. */
        fun onLevel(level: Float)
        fun onTranscribing()
        fun onDictated(text: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var recording = false
    private var record: AudioRecord? = null
    private var model = 0L

    val listening get() = recording

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        if (recording) return
        if (!hasPermission()) { listener.onError("Ghost Keyboard needs the microphone: allow it in Settings › Plugins › Dictation."); return }
        if (!WhisperModel.isReady(context)) { listener.onError("The dictation model is still downloading."); return }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { listener.onError("The microphone could not be opened."); return }
        record = rec
        recording = true
        rec.startRecording()
        worker.execute {
            val chunks = ArrayList<ShortArray>()
            val buf = ShortArray(RATE / 10)
            var total = 0
            while (recording && total < RATE * MAX_SECONDS) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                chunks += buf.copyOf(n)
                total += n
                var peak = 0
                for (i in 0 until n) peak = max(peak, abs(buf[i].toInt()))
                val level = (peak / 32768f).coerceIn(0f, 1f)
                main.post { listener.onLevel(level) }
            }
            recording = false
            runCatching { rec.stop() }
            rec.release()
            record = null
            if (total < RATE / 4) { main.post { listener.onDictated("") }; return@execute }
            main.post { listener.onTranscribing() }
            val samples = FloatArray(total)
            var at = 0
            for (chunk in chunks) for (s in chunk) samples[at++] = s / 32768f
            val text = runCatching { transcribe(samples) }.getOrElse { e -> main.post { listener.onError(e.message ?: "Transcription failed.") }; return@execute }
            main.post { listener.onDictated(text) }
        }
    }

    /** Ends the take: what was heard is transcribed. */
    fun stop() { recording = false }

    private fun transcribe(samples: FloatArray): String {
        if (!Whisper.load()) throw IllegalStateException("Speech to text is not in this build.")
        if (model == 0L) {
            model = Whisper.nativeInit(WhisperModel.file(context).path)
            if (model == 0L) throw IllegalStateException("The dictation model could not be loaded.")
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        return Whisper.nativeTranscribe(model, samples, threads, PROMPT).trim()
            // whisper's way of saying it heard nothing.
            .takeUnless { it == "[BLANK_AUDIO]" || it.startsWith("(") && it.endsWith(")") } ?: ""
    }

    fun release() {
        recording = false
        worker.execute { if (model != 0L) { Whisper.nativeFree(model); model = 0L } }
    }

    companion object {
        const val RATE = 16_000
        const val MAX_SECONDS = 60

        /** A hint of a programmer's words, so whisper spells them as written (npm, Kotlin, async). */
        private const val PROMPT = "Dictation from a developer: npm, git, Kotlin, Flutter, JSON, async, API, GitHub."
    }
}
