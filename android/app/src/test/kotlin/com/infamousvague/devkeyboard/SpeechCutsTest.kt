package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Where a long dictation is cut into pieces (SpeechCuts.kt): at a pause, at a piece's longest, and never inside a
 * word that a pause would have spared. The sound here is made, not recorded: a steady tone stands for a voice and a
 * faint hiss for a quiet room, which is enough to hold the rules. How well they suit real voices and rooms is not
 * something a test like this can say.
 */
class SpeechCutsTest {
    private val rate = 16_000
    private val frame = rate * SpeechCuts.FRAME_MS / 1000

    /** [seconds] of a voice: a tone whose loudest sample is [amp]. */
    private fun voice(seconds: Double, amp: Int = 6000): ShortArray =
        ShortArray((seconds * rate).toInt()) { (amp * sin(2 * PI * 220 * it / rate)).toInt().toShort() }

    /** [seconds] of a quiet room: a hiss far under anyone's voice. */
    private fun room(seconds: Double, amp: Int = 40): ShortArray {
        var seed = 12345
        return ShortArray((seconds * rate).toInt()) {
            seed = seed * 1103515245 + 12345
            (((seed ushr 16) and 0x7fff) % (2 * amp + 1) - amp).toShort()
        }
    }

    private operator fun ShortArray.plus(other: ShortArray): ShortArray = ShortArray(size + other.size).also { System.arraycopy(this, 0, it, 0, size); System.arraycopy(other, 0, it, size, other.size) }

    /** A piece that was taken: where in the take it starts and ends, in seconds, and whether it has speech. */
    private class Piece(val from: Double, val to: Double, val speech: Boolean)

    /** What the recorder keeps beside [SpeechCuts]: how much of the take has been taken as pieces, and how much is heard and not yet taken. */
    private val taken = HashMap<SpeechCuts, Long>()
    private val pending = HashMap<SpeechCuts, Int>()

    /**
     * Gives [sound] to [cuts] a tenth of a second at a time, as the recorder does. Back come the pieces taken while
     * it was heard, in order; a take may be heard in several goes.
     */
    private fun hear(cuts: SpeechCuts, sound: ShortArray): List<Piece> {
        val pieces = ArrayList<Piece>()
        val step = rate / 10
        var at = 0
        while (at < sound.size) {
            val n = minOf(step, sound.size - at)
            pending[cuts] = (pending[cuts] ?: 0) + n
            cuts.heard(sound.copyOfRange(at, at + n), n)?.let { cut ->
                val from = taken[cuts] ?: 0L
                assertTrue("a piece is whole frames", cut.samples % frame == 0)
                assertTrue("a piece is never more than was heard", cut.samples in 1..pending.getValue(cuts))
                pieces += Piece(from / rate.toDouble(), (from + cut.samples) / rate.toDouble(), cut.speech)
                taken[cuts] = from + cut.samples
                pending[cuts] = pending.getValue(cuts) - cut.samples
            }
            at += n
        }
        return pieces
    }

    private fun near(expected: Double, actual: Double, within: Double = 0.05) = assertTrue("expected about $expected, was $actual", abs(expected - actual) <= within)

    @Test fun `a pause ends a piece, in the middle of the quiet`() {
        val cuts = SpeechCuts(rate)
        val pieces = hear(cuts, voice(5.0) + room(1.0) + voice(3.0))
        val first = pieces.first()
        assertTrue(first.speech)
        assertEquals(0.0, first.from, 0.0)
        // The quiet runs from 5.0 s; 0.6 s of it ends the piece, and the cut stands half way into that.
        near(5.3, first.to)
        // What follows is still being spoken when the sound ends: it is not cut.
        assertEquals(1, pieces.count { it.speech })
        assertTrue(cuts.rest())
    }

    @Test fun `a breath between words does not end a piece`() {
        val cuts = SpeechCuts(rate)
        // 0.3 s of quiet is not a pause.
        val pieces = hear(cuts, voice(5.0) + room(0.3) + voice(2.0) + room(1.0))
        assertEquals(1, pieces.size)
        near(7.3 + 0.3, pieces.single().to)
    }

    @Test fun `a few words and then quiet wait until the piece is long enough`() {
        val cuts = SpeechCuts(rate)
        val pieces = hear(cuts, voice(1.0) + room(6.0))
        val first = pieces.first()
        assertTrue(first.speech)
        // Not at 1.6 s, when the quiet was long enough, but at 4 s, when the piece was: half the quiet goes with it.
        near(2.5, first.to)
        // The rest of the quiet is let go, and never taken for speech.
        assertTrue(pieces.drop(1).none { it.speech })
        assertFalse(cuts.rest())
    }

    @Test fun `someone who never pauses is cut at a piece's longest, at the quietest moment of its last seconds`() {
        val cuts = SpeechCuts(rate)
        // Half a minute of voice, dropping to a murmur for a fifth of a second at 21 s: too short to be a pause.
        val pieces = hear(cuts, voice(21.0) + voice(0.2, amp = 1500) + voice(8.8))
        val first = pieces.first()
        assertTrue(first.speech)
        near(21.1, first.to, within = 0.12)
        assertTrue("no piece is longer than whisper can hear", pieces.all { it.to - it.from <= SpeechCuts.MAX_S + 0.11 })
    }

    @Test fun `a steady voice with no dip at all is still cut before it is too long`() {
        val cuts = SpeechCuts(rate)
        val pieces = hear(cuts, voice(60.0))
        assertTrue(pieces.size >= 2)
        assertTrue(pieces.all { it.speech && it.to - it.from <= SpeechCuts.MAX_S + 0.11 })
        // Each piece starts where the one before ended: nothing between them is lost or heard twice.
        pieces.zipWithNext { a, b -> assertEquals(a.to, b.from, 0.0) }
    }

    @Test fun `after someone has spoken, quiet is let go a little at a time and never written down`() {
        val cuts = SpeechCuts(rate)
        val pieces = hear(cuts, voice(5.0) + room(20.0))
        assertEquals(1, pieces.count { it.speech })
        val quiet = pieces.filter { !it.speech }
        assertTrue("quiet was let go", quiet.size >= 5)
        // No more is kept waiting than a couple of seconds of it.
        near(25.0, pieces.last().to, within = SpeechCuts.SILENCE_S + 0.4)
        assertFalse(cuts.rest())
    }

    @Test fun `before anyone has spoken nothing is let go for a whole piece's length`() {
        // A short take in a voice too quiet to be taken for speech: every sample of it is still there at the end,
        // for whisper to hear as it always did.
        val quietVoice = SpeechCuts(rate)
        assertEquals(emptyList<Piece>(), hear(quietVoice, room(10.0, amp = 150)))
        assertFalse(quietVoice.spokeEver)
        assertFalse(quietVoice.rest())
        // A mic left open in a quiet room: after a piece's length the silence starts to be let go.
        val open = SpeechCuts(rate)
        val pieces = hear(open, room(40.0))
        assertTrue(pieces.isNotEmpty())
        assertTrue(pieces.none { it.speech })
        near(SpeechCuts.MAX_S.toDouble() - SpeechCuts.KEEP_FRAMES * SpeechCuts.FRAME_MS / 1000.0, pieces.first().to, within = 0.11)
    }

    @Test fun `quiet is judged against the piece's own loudness`() {
        val cuts = SpeechCuts(rate)
        // A loud voice in a room with a fan: the fan alone would count as speech, but beside the voice it is the pause.
        val pieces = hear(cuts, voice(5.0, amp = 12_000) + room(1.0, amp = 600) + voice(1.0, amp = 12_000))
        assertTrue(pieces.first().speech)
        near(5.3, pieces.first().to)
    }

    @Test fun `how long since anyone spoke`() {
        val cuts = SpeechCuts(rate)
        hear(cuts, room(3.0))
        assertEquals(3000L, cuts.idleMs)
        assertFalse(cuts.spokeEver)
        hear(cuts, voice(2.0))
        assertEquals(0L, cuts.idleMs)
        assertTrue(cuts.spokeEver)
        hear(cuts, room(10.0))
        assertEquals(10_000L, cuts.idleMs)
        // Forty-five seconds of it is when a take ends by itself.
        hear(cuts, room(36.0))
        assertTrue(cuts.idleMs >= Dictation.IDLE_STOP_MS)
    }

    @Test fun `the samples of a frame not yet whole are kept for the next`() {
        val cuts = SpeechCuts(rate)
        // Fed in odd sizes: the loudness of each frame is the same as fed whole, so the cut is where it would be.
        val sound = voice(5.0) + room(1.0) + voice(1.0)
        var at = 0
        var taken = -1
        var heard = 0
        val sizes = intArrayOf(7, 1601, 333, 160, 2999)
        var i = 0
        while (at < sound.size && taken < 0) {
            val n = minOf(sizes[i++ % sizes.size], sound.size - at)
            heard += n
            cuts.heard(sound.copyOfRange(at, at + n), n)?.let { taken = it.samples }
            at += n
        }
        assertTrue(taken > 0)
        // The cut is made when a go ends with the quiet long enough, which an odd-sized go may overshoot a little.
        near(5.3, taken / rate.toDouble(), within = 0.12)
        assertTrue(taken <= heard)
    }

    @Test fun `how much speech the piece in hand has in it`() {
        // LiveWords waits for most of a second of it before a piece's first draft: the quiet a piece begins with is not speech.
        val cuts = SpeechCuts()
        hear(cuts, room(1.0))
        assertEquals(0, cuts.spokenMs)
        hear(cuts, voice(0.5))
        near(0.5, cuts.spokenMs / 1000.0)
        hear(cuts, room(0.2) + voice(0.7))
        near(1.2, cuts.spokenMs / 1000.0)
    }
}
