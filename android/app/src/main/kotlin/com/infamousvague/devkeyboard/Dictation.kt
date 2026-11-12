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
 * The dictation plugin's work: record from the microphone at whisper's 16 kHz and write down what is said with
 * whisper.cpp, on the phone. One take at a time; the model stays loaded between takes while the keyboard lives.
 *
 * A take has no time limit (Matt, 2026-11-12: "I like prompt dictation…"; until then a take ended at 60 seconds and
 * its words came only once it was over). It is heard as a row of pieces, each cut at a pause ([SpeechCuts]) and
 * written down on a thread of its own while the next is being spoken, so the words arrive a sentence or two behind
 * the voice ([Listener.onHeard]). Each piece is given the end of the piece before it and the person's own words
 * ([DictationWords.hint]), so the pieces read as one and names are likelier spelled their way.
 *
 * A take ends when the mic is tapped again, after [IDLE_STOP_MS] with nobody speaking (a mic left open), or at
 * [MAX_MINUTES]. What is said is never kept: each piece's sound is let go as soon as it is written down.
 *
 * A take can also be let go ([cancel]): the mic closes, the pieces not yet written down are dropped unheard, and its
 * listener is told nothing more. That is for a take whose field is gone (its words must not be typed into the next
 * one), and for a person who will not wait for a long take's last pieces.
 */
class Dictation(private val context: Context) {
    interface Listener {
        /** How loud it is now, 0..1, a few times a second while listening. */
        fun onLevel(level: Float)
        /** Words written down while the take goes on: a piece that ended at a pause. */
        fun onHeard(text: String)
        /** The mic has closed and the last words are being written down. */
        fun onTranscribing()
        /** The take is over. [text] is its last words: empty where the last piece had none, or everything was heard already. */
        fun onDictated(text: String)
        fun onError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    /** Listens: reads the microphone and cuts the take into pieces. */
    private val worker = Executors.newSingleThreadExecutor()
    /** Writes the pieces down, in the order they were said, while [worker] goes on listening. */
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var recording = false
    /** A piece could not be written down: the take's other pieces are let go, and the mic closes. */
    @Volatile private var failed = false
    private var record: AudioRecord? = null
    private var model = 0L
    private var modelPath: String? = null
    /** The take being heard or written down, until its end has been said or it is let go. Read and set on the main thread. */
    private var current: Take? = null

    val listening get() = recording

    /** Whether a take is open or still being written down. */
    val active get() = current != null

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start(listener: Listener) {
        if (recording) return
        if (!hasPermission()) { listener.onError("Ghost Keyboard needs the microphone: allow it in Settings › Plugins › Dictation."); return }
        if (!WhisperModel.isReady(context)) { listener.onError("The dictation model isn't downloaded yet: Settings › Dictation."); return }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
        }.getOrNull()
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) { listener.onError("The microphone could not be opened."); return }
        record = rec
        recording = true
        failed = false
        rec.startRecording()
        // The settings are read once, as the take begins: the words to expect and the language do not change mid-sentence.
        val take = Take(listener, DictationWords.parse(KeyboardPrefs(context).dictationWords))
        current = take
        worker.execute {
            val cuts = SpeechCuts(RATE)
            val pending = Samples()
            val buf = ShortArray(RATE / 10)
            var total = 0L
            var queued = 0
            while (recording && !failed && !take.cancelled && total < RATE.toLong() * 60 * MAX_MINUTES && cuts.idleMs < IDLE_STOP_MS) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                pending.add(buf, n)
                total += n
                // A piece ended (a pause, or the longest a piece may be): it is written down while the take goes on.
                // A stretch with nobody speaking is let go unheard.
                cuts.heard(buf, n)?.let { cut ->
                    val piece = pending.take(cut.samples)
                    if (cut.speech) { queued++; write(take, piece, last = false) }
                }
                var peak = 0
                for (i in 0 until n) peak = max(peak, abs(buf[i].toInt()))
                val level = (peak / 32768f).coerceIn(0f, 1f)
                main.post { if (!take.cancelled) listener.onLevel(level) }
            }
            // Only this take's own flag and microphone: a take let go may already have another after it.
            if (!take.cancelled) recording = false
            runCatching { rec.stop() }
            rec.release()
            if (record === rec) record = null
            // A piece that could not be written down has ended the take already, and said why. A take let go says nothing.
            if (failed || take.cancelled) return@execute
            main.post { if (!take.cancelled) listener.onTranscribing() }
            // What is left is written down where there is speech in it; and a short take that was never taken for
            // speech at all is written down anyway, as every take was before there were pieces: nobody's quiet word
            // is thrown away on a guess.
            val rest = pending.take(pending.size)
            val heard = rest.size >= RATE / 4 && (cuts.rest() || (queued == 0 && !cuts.spokeEver))
            write(take, if (heard) rest else ShortArray(0), last = true)
        }
    }

    /** Ends the take: what was heard and not yet written down is, and then [Listener.onDictated] says it is over. */
    fun stop() { recording = false }

    /**
     * Lets the take go, from the main thread: the mic closes, what was heard and not yet written down is dropped, and
     * its listener hears nothing more of it (not even its end: whoever lets it go puts their own panel right).
     */
    fun cancel() {
        val take = current ?: return
        take.cancelled = true
        current = null
        recording = false
    }

    /**
     * One take: who hears its words, the person's own words for whisper, the end of what has been written down so
     * far, and whether it was let go ([cancel]).
     */
    private class Take(val listener: Listener, val words: List<String>) {
        var before = ""
        @Volatile var cancelled = false
    }

    /** Queues [piece] to be written down after the pieces before it. The [last] one ends the take, with or without sound in it. */
    private fun write(take: Take, piece: ShortArray, last: Boolean) {
        writer.execute {
            // A take that was let go has nothing more written down: its pieces pass unheard.
            if (failed || take.cancelled) return@execute
            val text = if (piece.isEmpty()) "" else try {
                transcribe(piece, take)
            } catch (e: Throwable) {
                if (take.cancelled) return@execute
                failed = true
                recording = false
                main.post { if (!take.cancelled) { if (current === take) current = null; take.listener.onError(e.message ?: "Transcription failed.") } }
                return@execute
            }
            if (text.isNotEmpty()) take.before = text
            main.post {
                // Let go while this piece was being written down: its words are not typed.
                if (take.cancelled) return@post
                if (last) { if (current === take) current = null; take.listener.onDictated(text) } else if (text.isNotEmpty()) take.listener.onHeard(text)
            }
        }
    }

    private fun transcribe(piece: ShortArray, take: Take): String {
        if (!Whisper.load()) throw IllegalStateException("Speech to text is not in this build.")
        val spec = WhisperModel.active(context)
        val path = WhisperModel.file(context, spec).path
        // Another model chosen since the last take: let the old one go first, they are large.
        if (model != 0L && path != modelPath) { Whisper.nativeFree(model); model = 0L }
        if (model == 0L) {
            model = Whisper.nativeInit(path)
            if (model == 0L) throw IllegalStateException("The dictation model could not be loaded.")
            modelPath = path
        }
        // English-only models hear English; the others listen for the language set, or work it out.
        val language = if (!spec.multilingual) "en" else KeyboardPrefs(context).dictationLanguage.ifBlank { "auto" }
        val prompt = DictationWords.hint(language == "en", take.words, take.before)
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val samples = FloatArray(piece.size) { piece[it] / 32768f }
        return Whisper.nativeTranscribe(model, samples, threads, prompt, language).trim()
            // whisper's way of saying it heard nothing.
            .takeUnless { it == "[BLANK_AUDIO]" || it.startsWith("(") && it.endsWith(")") || it.startsWith("[") && it.endsWith("]") } ?: ""
    }

    fun release() {
        cancel()
        recording = false
        // After the listening thread has ended its take and queued whatever it had: a piece queued behind the free
        // would load the model again, and nothing would let that one go.
        worker.execute { writer.execute { if (model != 0L) { Whisper.nativeFree(model); model = 0L } } }
    }

    /** The samples heard and not yet taken as a piece: a row of them that grows at its end and is taken from its start. */
    private class Samples {
        private var data = ShortArray(RATE * 4)
        var size = 0
            private set

        fun add(from: ShortArray, count: Int) {
            if (size + count > data.size) data = data.copyOf(max(data.size * 2, size + count))
            System.arraycopy(from, 0, data, size, count)
            size += count
        }

        /** The first [count] samples, taken out: what follows them moves up to the start. */
        fun take(count: Int): ShortArray {
            val n = count.coerceIn(0, size)
            val out = data.copyOf(n)
            System.arraycopy(data, n, data, 0, size - n)
            size -= n
            return out
        }
    }

    companion object {
        const val RATE = 16_000
        /** A take ends by itself after this long with nobody speaking: a mic left open does not listen for ever. */
        const val IDLE_STOP_MS = 45_000L
        /** The longest a take may be, whatever is said. */
        const val MAX_MINUTES = 30
    }
}
