package com.infamousvague.devkeyboard

/**
 * Dictation's words as they are spoken (Matt, 2026-11-12: "I want dictation to show as I'm typing in the chat box
 * instead of having it all show up at the very end"). Until 0.16.2 a take's words came a piece at a time, and a piece
 * ended only at a pause of 0.6 seconds, so someone who speaks a message straight through saw nothing until they
 * tapped to finish.
 *
 * whisper does not hear word by word: it is given sound and gives back all it makes of it. So the piece in hand
 * (what has been said since the last pause) is written down again and again while it is being spoken, about once a
 * second, each time whole and from its start, and what comes back is shown in the field as a draft that the next
 * one replaces. A draft's last word or two can change as more is heard ("what you are" becomes "what your country").
 * At the pause the piece is written down once more as it always was, with whisper's whole window, and that takes the
 * draft's place for good.
 *
 * A draft is made a little faster than a piece is: whisper writes one segment with no time marks and does not try
 * again at a higher temperature. It is still given its whole thirty-second window. Cut to the piece's own length and
 * three seconds over, the window made a draft in a third of the time on a desk, but on the emulator such drafts took
 * "Ask" for "It's" and once filled the field with "is is is is": so the window is whole, and a draft that repeats
 * itself or says more than anyone could have said is not shown at all ([believable]).
 *
 * Nothing here touches Android, the microphone or whisper: these are the rules, so they can be tried off the phone
 * (LiveWordsTest).
 */
object LiveWords {
    /**
     * How much speech a piece must have in it before its first draft, and how much more of the piece must be heard
     * before each draft after that, in milliseconds. Speech, not sound: a piece begins with the end of the pause
     * before it, and a draft of a quarter of a second of a word was "S0", or "And", on the emulator.
     */
    const val FIRST_MS = 900
    const val STEP_MS = 900

    /**
     * Whether the piece in hand should be written down again now. [pending] is how many samples of it there are,
     * [last] how many there were when the last draft was asked for (0: none has been), [busy] whether that draft is
     * still being made (one at a time: a slow phone makes them as fast as it can and no faster), and [spokenMs] how
     * much speech the piece has in it (whisper makes words up when it is given a quiet room, or the start of a word).
     */
    fun due(pending: Int, last: Int, busy: Boolean, spokenMs: Int, rate: Int = 16_000): Boolean =
        !busy && spokenMs >= FIRST_MS && (last == 0 || pending - last >= rate / 1000 * STEP_MS)

    /**
     * Whether a draft [text] of [ms] of sound can be believed. Given too little to go on, whisper sometimes says one
     * word over and over, or a phrase twice, or far more than was said: such a draft is not shown, and the one
     * before it stands until the next.
     */
    fun believable(text: String, ms: Int): Boolean {
        val words = text.lowercase().split(' ').map { w -> w.trim { !it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        // Nobody says more than about five words a second.
        if (words.size > 3 + ms * 5 / 1000) return false
        // One word four times running.
        var run = 1
        for (i in 1 until words.size) {
            run = if (words[i] == words[i - 1]) run + 1 else 1
            if (run >= 4) return false
        }
        // The same few words said twice over, and nothing else.
        val half = words.size / 2
        if (words.size >= 4 && words.size % 2 == 0 && words.subList(0, half) == words.subList(half, words.size)) return false
        return true
    }

    /**
     * What is shown of a draft [raw]. The person is in the middle of a sentence, whatever whisper makes of where the
     * sound stops: the full stop, the dots or the comma it ends the draft with are not shown (they would come and go
     * with every draft). Its notes on a sound that is not speech ("[BLANK_AUDIO]", "(music)") are nothing.
     */
    fun shown(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || noise(t)) return ""
        return t.trimEnd('.', '!', '?', ',', ';', ':', '…', '-', ' ')
    }

    /** whisper's way of saying it heard something that is not words. */
    fun noise(text: String): Boolean = text.startsWith("[") && text.endsWith("]") || text.startsWith("(") && text.endsWith(")")

    /**
     * The space that parts dictated words from what stands [before] the cursor: one after other words, none at the
     * start of a field or a line, after a space, or after an opening bracket or quote.
     */
    fun lead(before: String): String {
        val c = before.lastOrNull() ?: return ""
        return if (!c.isWhitespace() && c !in "([{\"'`") " " else ""
    }
}
