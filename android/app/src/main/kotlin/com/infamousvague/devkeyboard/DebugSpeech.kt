package com.infamousvague.devkeyboard

import android.content.Context
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * Debug builds only: a recording that stands in for the microphone, so a dictation take with words in it can be
 * tried on an emulator, whose microphone hears nothing (as the AI key `debug-echo` stands in for an AI).
 *
 * The recording is `files/debug-speech.wav` in the app's own storage, put there by hand
 * (`adb shell run-as com.infamousvague.devkeyboard ...`): a WAV file of 16-bit samples, one channel, 16 kHz. While it
 * is there, a take in a debug build hears it instead of the microphone, at the speed it was spoken; after its end
 * there is silence, as from a room where nobody speaks, until the take is ended. A release build never looks for it.
 */
object DebugSpeech {
    const val FILE = "debug-speech.wav"

    /** The recording, read as the microphone is: [read] fills its buffer and takes as long as that much sound lasts. */
    class Source internal constructor(private val input: InputStream, private val rate: Int) {
        private var bytes = ByteArray(0)
        private var due = SystemClock.elapsedRealtime()
        private var ended = false

        fun read(buf: ShortArray): Int {
            if (bytes.size != buf.size * 2) bytes = ByteArray(buf.size * 2)
            var got = 0
            while (!ended && got < bytes.size) {
                val n = input.read(bytes, got, bytes.size - got)
                if (n < 0) ended = true else got += n
            }
            val whole = got / 2
            for (i in 0 until whole) buf[i] = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
            for (i in whole until buf.size) buf[i] = 0
            // As long as this much sound takes to be said.
            due += buf.size * 1000L / rate
            val wait = due - SystemClock.elapsedRealtime()
            if (wait > 0) runCatching { Thread.sleep(wait) } else if (wait < -500) due = SystemClock.elapsedRealtime()
            return buf.size
        }

        fun close() { runCatching { input.close() } }
    }

    /** The recording, where this is a debug build and it is there. Null otherwise: the microphone is the microphone. */
    fun open(context: Context, rate: Int = Dictation.RATE): Source? {
        if (!Updater.isDebuggable(context)) return null
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return null
        return runCatching {
            val input = BufferedInputStream(file.inputStream())
            if (!skipToSound(input)) { input.close(); return null }
            Source(input, rate)
        }.getOrNull()
    }

    /** Reads past a WAV file's header to where its samples begin. False where it is not a WAV file with a data chunk. */
    internal fun skipToSound(input: InputStream): Boolean {
        val head = ByteArray(12)
        if (input.readNBytes(head, 0, 12) != 12 || String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return false
        val chunk = ByteArray(8)
        while (input.readNBytes(chunk, 0, 8) == 8) {
            val size = (chunk[4].toLong() and 0xff) or ((chunk[5].toLong() and 0xff) shl 8) or ((chunk[6].toLong() and 0xff) shl 16) or ((chunk[7].toLong() and 0xff) shl 24)
            if (String(chunk, 0, 4, Charsets.US_ASCII) == "data") return true
            // A chunk of an odd length is followed by a byte of padding.
            var left = size + (size and 1)
            while (left > 0) { val n = input.skip(left); if (n <= 0) return false; left -= n }
        }
        return false
    }
}
