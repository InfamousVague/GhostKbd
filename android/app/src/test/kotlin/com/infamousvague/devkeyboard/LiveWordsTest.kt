package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Dictation's words as they are spoken (LiveWords.kt; Matt, 2026-11-12: "I want dictation to show as I'm typing in
 * the chat box instead of having it all show up at the very end"): when the piece in hand is written down again, how
 * much of whisper's window a draft is given, and what of a draft is shown.
 */
class LiveWordsTest {
    private val second = 16_000

    @Test fun `a draft is made about once a second while someone is speaking`() {
        // Nothing until there is most of a second of speech in the piece, however long the piece is with its quiet start.
        assertFalse(LiveWords.due(pending = 2 * second, last = 0, busy = false, spokenMs = 400))
        assertTrue(LiveWords.due(pending = 2 * second, last = 0, busy = false, spokenMs = 900))
        // Then not again until 0.9 s more has been heard.
        assertFalse(LiveWords.due(pending = second * 28 / 10, last = 2 * second, busy = false, spokenMs = 1700))
        assertTrue(LiveWords.due(pending = second * 29 / 10, last = 2 * second, busy = false, spokenMs = 1800))
    }

    @Test fun `one draft at a time, and none of a quiet room`() {
        // The last draft is still being made (a slow phone): the next waits for it, however much has been said since.
        assertFalse(LiveWords.due(pending = 10 * second, last = second, busy = true, spokenMs = 9000))
        // Nobody has spoken in this piece: whisper would make words up.
        assertFalse(LiveWords.due(pending = 10 * second, last = 0, busy = false, spokenMs = 0))
    }

    @Test fun `a draft that repeats itself, or says more than was said, is not shown`() {
        // What whisper made of the start of "Ask not what your country can do for you" on the emulator, one draft.
        assertFalse(LiveWords.believable("is is is is is is is is is is is is is is is is is is is is", ms = 3000))
        // One word four times running, however much sound there was.
        assertFalse(LiveWords.believable("no no no no", ms = 20_000))
        // A phrase said twice over, as a desk run gave for 1.8 seconds of sound.
        assertFalse(LiveWords.believable("Ask not! Ask not!", ms = 1800))
        // More words than anyone says in the time: 3 and five a second.
        assertFalse(LiveWords.believable("one two three four five six seven eight nine", ms = 1000))
        assertTrue(LiveWords.believable("one two three four five six seven eight", ms = 1000))
        // Ordinary drafts, and ordinary repetition, are believed.
        assertTrue(LiveWords.believable("Ask not what your country can do for you", ms = 4200))
        assertTrue(LiveWords.believable("no no no, not that one", ms = 2500))
        assertTrue(LiveWords.believable("very very good", ms = 1500))
        assertTrue(LiveWords.believable("And", ms = 900))
        assertFalse(LiveWords.believable("...", ms = 900))
        assertFalse(LiveWords.believable("", ms = 900))
    }

    @Test fun `a draft is shown without the stop whisper ends it with`() {
        // The drafts whisper made of one sentence, on a desk, as more of it was heard.
        assertEquals("And so my fellow", LiveWords.shown(" And so my fellow"))
        assertEquals("And so, my fellow Americans", LiveWords.shown(" And so, my fellow Americans!"))
        assertEquals("And so my fellow Americans ask not", LiveWords.shown("And so my fellow Americans ask not."))
        assertEquals("ask what you", LiveWords.shown("ask what you..."))
        assertEquals("ask what you", LiveWords.shown("ask what you…"))
        assertEquals("well", LiveWords.shown("well, "))
        // A stop inside it is the end of a sentence that was said: it stays.
        assertEquals("Thanks. See you", LiveWords.shown("Thanks. See you."))
        // What is not speech is nothing.
        assertEquals("", LiveWords.shown("[BLANK_AUDIO]"))
        assertEquals("", LiveWords.shown(" (music) "))
        assertEquals("", LiveWords.shown("   "))
        assertEquals("", LiveWords.shown("..."))
    }

    @Test fun `dictated words are parted from what stands before them`() {
        assertEquals(" ", LiveWords.lead("Thanks for the update."))
        assertEquals("", LiveWords.lead(""))
        assertEquals("", LiveWords.lead("Thanks. "))
        assertEquals("", LiveWords.lead("line one\n"))
        assertEquals("", LiveWords.lead("she said \""))
        assertEquals("", LiveWords.lead("see ("))
    }

    @Test fun `what a revised draft keeps of the one before is where they begin alike`() {
        assertEquals(16, EditHistory.sharedStart("And so my fellow", "And so my fellow Americans"))
        assertEquals(12, EditHistory.sharedStart("ask what you are", "ask what your country"))
        assertEquals(0, EditHistory.sharedStart("Hello", "hello"))
        assertEquals(5, EditHistory.sharedStart("Hello", "Hello"))
        assertEquals(0, EditHistory.sharedStart("", "Hello"))
        // Never between the two halves of one character: two emoji that begin with the same half.
        assertEquals(3, EditHistory.sharedStart("ok 😀", "ok 😁"))
    }

    @Test fun `the stand-in recording's samples are found past its header`() {
        fun le(n: Int, bytes: Int) = ByteArray(bytes) { (n shr (8 * it)).toByte() }
        val fmt = "fmt ".toByteArray() + le(16, 4) + ByteArray(16)
        // A chunk of an odd length (a note someone's editor left) is passed over with its byte of padding.
        val note = "LIST".toByteArray() + le(3, 4) + byteArrayOf(1, 2, 3, 0)
        val sound = byteArrayOf(10, 0, 20, 0)
        val wav = "RIFF".toByteArray() + le(0, 4) + "WAVE".toByteArray() + fmt + note + "data".toByteArray() + le(sound.size, 4) + sound
        val input = ByteArrayInputStream(wav)
        assertTrue(DebugSpeech.skipToSound(input))
        assertEquals(10, input.read())
        assertFalse(DebugSpeech.skipToSound(ByteArrayInputStream("not a recording at all".toByteArray())))
        assertFalse(DebugSpeech.skipToSound(ByteArrayInputStream("RIFF".toByteArray() + le(0, 4) + "WAVE".toByteArray() + fmt)))
    }
}
