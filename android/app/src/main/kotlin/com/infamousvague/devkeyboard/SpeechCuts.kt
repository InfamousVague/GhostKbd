package com.infamousvague.devkeyboard

import kotlin.math.sqrt

/**
 * Where a long dictation is cut into pieces (Matt, 2026-11-12, of a list of plugins to add: "I like prompt
 * dictation…"; the list had said of it: "Lift the cap, show the words live"). whisper hears thirty seconds at a
 * time and gives its words only when it has heard a piece whole, so a take with no limit is heard as a row of
 * pieces, each written down while the next is still being spoken. This says where one piece ends:
 *
 * - at a pause: once a piece is [MIN_S] seconds long and has speech in it, [PAUSE_MS] of quiet ends it, in the middle
 *   of that quiet, so no word is cut in two;
 * - at [MAX_S] seconds whatever is being said (someone who never pauses, or a room that is never quiet), at the
 *   quietest moment of the piece's last [LOOK_BACK_S] seconds;
 * - and a stretch with no speech in it at all is let go without being written down, but for its last moment, which
 *   may be the breath before a word. whisper makes words up when it is given silence. Before anyone has spoken in
 *   the take this waits a whole piece's length, so a short take in a voice too quiet to be taken for speech is
 *   never thrown away.
 *
 * "Quiet" is quiet for this piece: under [QUIET_SHARE] of the loudest it has been, and never taken to be under
 * [QUIET_FLOOR]. "Speech" is a moment at least [SPEECH] loud. Loudness is the root mean square of the 16-bit samples
 * over [FRAME_MS]. The numbers are chosen, not measured on phones: see docs/dictation.md.
 *
 * It also keeps how long it has been since anyone spoke ([idleMs]), which is how a take that was forgotten ends.
 * Nothing here touches Android or the microphone: it is given samples and says where to cut (SpeechCutsTest).
 */
class SpeechCuts(rate: Int = 16_000) {
    /** A piece to take from the front of what has been heard and not yet taken: [samples] of it, and whether it has [speech] in it. */
    data class Cut(val samples: Int, val speech: Boolean)

    private val frame = rate * FRAME_MS / 1000
    /** The loudness of each whole frame heard and not yet taken. */
    private val frames = ArrayList<Float>()
    /** The frame being filled: the sum of its samples' squares, and how many there are. */
    private var sum = 0.0
    private var filled = 0
    private var idleFrames = 0L
    private var spoke = false

    /** How long it has been since a moment of speech, in milliseconds: since the take began, where there has been none. */
    val idleMs: Long get() = idleFrames * FRAME_MS

    /** Whether anyone has spoken in this take. */
    val spokeEver: Boolean get() = spoke

    /**
     * [count] more samples were heard. Back comes the piece to take now from the front of everything heard and not
     * yet taken, or null where there is none yet. Whoever calls keeps the samples; this keeps only how loud they were.
     */
    fun heard(samples: ShortArray, count: Int): Cut? {
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sum += s * s
            if (++filled == frame) {
                val loud = sqrt(sum / frame).toFloat()
                frames += loud
                if (loud >= SPEECH) { idleFrames = 0; spoke = true } else idleFrames++
                sum = 0.0
                filled = 0
            }
        }
        return decide()
    }

    /** Whether what is left when the take ends (everything heard and not yet taken) has speech in it. */
    fun rest(): Boolean = frames.any { it >= SPEECH }

    /** How much speech there is in what has been heard and not yet taken, in milliseconds: its loud moments, added up. */
    val spokenMs: Int get() = frames.count { it >= SPEECH } * FRAME_MS

    private fun decide(): Cut? {
        val n = frames.size
        if (n == 0) return null
        val peak = frames.max()
        val speech = peak >= SPEECH
        val seconds = n * FRAME_MS / 1000f
        if (!speech) {
            // Nobody is speaking: let go of all but the last moment, so silence never piles up. Until someone has
            // spoken in this take that is only done at a piece's full length: a voice too quiet to be taken for
            // speech is then still there, whole, when a short take ends, and is written down as it always was.
            return if (seconds >= (if (spoke) SILENCE_S else MAX_S)) take(n - KEEP_FRAMES, false) else null
        }
        val quiet = maxOf(QUIET_FLOOR, peak * QUIET_SHARE)
        var trailing = 0
        while (trailing < n && frames[n - 1 - trailing] < quiet) trailing++
        if (seconds >= MIN_S && trailing * FRAME_MS >= PAUSE_MS) return take(n - trailing / 2, true)
        if (seconds >= MAX_S) {
            // No pause came: the quietest moment of the last few seconds, and never the piece's very end.
            val from = (n - (LOOK_BACK_S * 1000 / FRAME_MS).toInt()).coerceAtLeast(1)
            var at = from
            for (i in from until n - 1) if (frames[i] < frames[at]) at = i
            return take(at, true)
        }
        return null
    }

    /** Takes the first [count] frames: the rest stay as the start of the next piece. */
    private fun take(count: Int, speech: Boolean): Cut? {
        if (count <= 0) return null
        frames.subList(0, count).clear()
        return Cut(count * frame, speech)
    }

    companion object {
        const val FRAME_MS = 20
        /** No piece is ended by a pause before it is this long: whisper does better with a sentence than with a word. */
        const val MIN_S = 4f
        /** Quiet this long ends a piece. */
        const val PAUSE_MS = 600
        /** A piece is cut here whatever is being said: whisper hears thirty seconds at a time. */
        const val MAX_S = 24f
        /** How far back from [MAX_S] the quietest moment is looked for. */
        const val LOOK_BACK_S = 6f
        /** A stretch this long with no speech in it is let go, all but [KEEP_FRAMES] of it. */
        const val SILENCE_S = 2f
        const val KEEP_FRAMES = 15
        /**
         * A frame this loud is someone speaking: about -42 dB of full scale. Set low on purpose: taking a quiet room
         * for speech costs a piece that whisper finds nothing in, and taking quiet speech for silence would lose
         * someone's words.
         */
        const val SPEECH = 250f
        /** Quiet is under this share of the piece's loudest frame, and never under [QUIET_FLOOR]. */
        const val QUIET_SHARE = 0.12f
        const val QUIET_FLOOR = 120f
    }
}
