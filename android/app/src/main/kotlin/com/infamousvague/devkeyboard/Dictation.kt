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
import java.util.concurrent.atomic.AtomicBoolean
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
 * While a piece is being spoken it is also written down again and again, about once a second, and shown as a draft
 * ([Listener.onDraft], LiveWords.kt), so the words are in the field as they are said and not only at the next pause
 * (Matt, 2026-11-12: "I want dictation to show as I'm typing in the chat box instead of having it all show up at
 * the very end"). The piece as written down at its pause takes the draft's place.
 *
 * A take can also be let go ([cancel]): the mic closes, the pieces not yet written down are dropped unheard, and its
 * listener is told nothing more. That is for a take whose field is gone (its words must not be typed into the next
 * one), and for a person who will not wait for a long take's last pieces.
 */
class Dictation(private val context: Context) {
    interface Listener {
        /** How loud it is now, 0..1, a few times a second while listening. */
        fun onLevel(level: Float)
        /**
         * The piece being spoken, as far as it has been heard: a draft, whole and from the piece's start each time,
         * to be shown in place of the draft before it. Its last words may change with the next.
         */
        fun onDraft(text: String)
        /**
         * Words written down while the take goes on: a piece that ended at a pause, in place of its drafts. Empty
         * where it turned out to hold no words (a draft shown for it is then taken back).
         */
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
        // Debug builds only: a recording in the app's own files stands in for the microphone (DebugSpeech), so a
        // take with words in it can be tried on an emulator, whose microphone hears nothing.
        val staged = DebugSpeech.open(context)
        val rec = if (staged != null) null else {
            val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val made = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, RATE))
            }.getOrNull()
            if (made == null || made.state != AudioRecord.STATE_INITIALIZED) { listener.onError("The microphone could not be opened."); return }
            made
        }
        record = rec
        recording = true
        failed = false
        rec?.startRecording()
        // The settings are read once, as the take begins: the words to expect and the language do not change
        // mid-sentence, nor does whether its words are shown as they are spoken.
        val prefs = KeyboardPrefs(context)
        val take = Take(listener, DictationWords.parse(prefs.dictationWords), prefs.dictationLive)
        current = take
        worker.execute {
            val cuts = SpeechCuts(RATE)
            val pending = Samples()
            val buf = ShortArray(RATE / 10)
            var total = 0L
            var queued = 0
            // The piece in hand: which it is, and how much of it there was when its last draft was asked for.
            var piece = 0
            var drafted = 0
            while (recording && !failed && !take.cancelled && total < RATE.toLong() * 60 * MAX_MINUTES && cuts.idleMs < IDLE_STOP_MS) {
                val n = if (rec != null) rec.read(buf, 0, buf.size) else staged!!.read(buf)
                if (n <= 0) continue
                pending.add(buf, n)
                total += n
                // A piece ended (a pause, or the longest a piece may be): it is written down while the take goes on.
                // A stretch with nobody speaking is let go unheard.
                val cut = cuts.heard(buf, n)
                if (cut != null) {
                    val whole = pending.take(cut.samples)
                    if (cut.speech) { queued++; write(take, whole, last = false); piece++ }
                    drafted = 0
                } else if (take.live && LiveWords.due(pending.size, drafted, take.drafting.get(), cuts.spokenMs)) {
                    // Still being spoken: written down as far as it has got, to be shown until the next draft.
                    drafted = pending.size
                    draft(take, pending.copy(), piece)
                }
                var peak = 0
                for (i in 0 until n) peak = max(peak, abs(buf[i].toInt()))
                val level = (peak / 32768f).coerceIn(0f, 1f)
                main.post { if (!take.cancelled) listener.onLevel(level) }
            }
            // Only this take's own flag and microphone: a take let go may already have another after it.
            if (!take.cancelled) recording = false
            runCatching { rec?.stop() }
            rec?.release()
            staged?.close()
            if (rec != null && record === rec) record = null
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
    private class Take(val listener: Listener, val words: List<String>, val live: Boolean) {
        var before = ""
        @Volatile var cancelled = false
        /** A draft is being made, or waits its turn: one at a time. */
        val drafting = AtomicBoolean(false)
        /** How many pieces are waiting to be written down or being written: a draft gives way to them. */
        @Volatile var waiting = 0
        /** How many pieces have been given to the listener. A draft of an earlier piece is not shown. Main thread. */
        var settled = 0
    }

    /**
     * Queues a draft of the piece in hand: [sound] is all of it so far, [piece] which piece it is. Not made where a
     * piece is waiting to be written down properly (that comes first), and not shown where its piece has been since.
     */
    private fun draft(take: Take, sound: ShortArray, piece: Int) {
        take.drafting.set(true)
        writer.execute {
            try {
                if (failed || take.cancelled || take.waiting > 0) return@execute
                // A draft that fails is no draft: the piece is still written down at its pause, and says if that fails.
                val text = runCatching { transcribe(sound, take, draft = true) }.getOrDefault("")
                if (text.isEmpty() || take.waiting > 0 || !LiveWords.believable(text, sound.size / (RATE / 1000))) return@execute
                main.post { if (!take.cancelled && take.settled == piece) take.listener.onDraft(text) }
            } finally {
                take.drafting.set(false)
            }
        }
    }

    /** Queues [piece] to be written down after the pieces before it. The [last] one ends the take, with or without sound in it. */
    private fun write(take: Take, piece: ShortArray, last: Boolean) {
        // A draft being made gives way: the piece it was a draft of is over.
        synchronized(take) { take.waiting++ }
        if (take.live && Whisper.load()) runCatching { Whisper.nativeAbortDraft() }
        writer.execute {
            // A take that was let go has nothing more written down: its pieces pass unheard.
            if (failed || take.cancelled) { synchronized(take) { take.waiting-- }; return@execute }
            val text = if (piece.isEmpty()) "" else try {
                transcribe(piece, take, draft = false)
            } catch (e: Throwable) {
                synchronized(take) { take.waiting-- }
                if (take.cancelled) return@execute
                failed = true
                recording = false
                main.post { if (!take.cancelled) { if (current === take) current = null; take.listener.onError(e.message ?: "Transcription failed.") } }
                return@execute
            }
            if (text.isNotEmpty()) take.before = text
            synchronized(take) { take.waiting-- }
            main.post {
                // Let go while this piece was being written down: its words are not typed.
                if (take.cancelled) return@post
                take.settled++
                // A piece with no words in it is still told where its drafts may have been shown: they are taken back.
                if (last) { if (current === take) current = null; take.listener.onDictated(text) } else if (text.isNotEmpty() || take.live) take.listener.onHeard(text)
            }
        }
    }

    /** [piece] as words. A [draft] is made quickly, of sound still being spoken (LiveWords.kt); the other is the piece as it stands. */
    private fun transcribe(piece: ShortArray, take: Take, draft: Boolean): String {
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
        // A draft is given whisper's whole window too (0): cut to the piece's length it was quicker, and wrong more often.
        val heard = if (draft) Whisper.nativeDraft(model, samples, threads, prompt, language, 0)
            else Whisper.nativeTranscribe(model, samples, threads, prompt, language)
        // whisper's way of saying it heard nothing.
        return heard.trim().takeUnless { it == "[BLANK_AUDIO]" || LiveWords.noise(it) } ?: ""
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

        /** A copy of everything held, for a draft: the row itself goes on growing. */
        fun copy(): ShortArray = data.copyOf(size)

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
