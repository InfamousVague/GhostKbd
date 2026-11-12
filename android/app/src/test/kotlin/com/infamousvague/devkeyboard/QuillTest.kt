package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the quill moves while the AI writes (Quill, in RowStyle.kt; Matt, 2026-11-12: "When the AI is writing or
 * rewriting show a feather quill scribbling"): it writes a line from left to right, lifts, goes back while the line
 * fades, and writes again. What it looks like is for eyes on a phone; what is held here is that the motion is whole:
 * nothing jumps, nothing goes backwards while it writes, and a cycle ends where the next begins.
 */
class QuillTest {
    private val steps = (0..1000).map { it / 1000f }
    private val writing = steps.filter { it < Quill.WRITE }
    private val back = steps.filter { it >= Quill.WRITE }

    @Test fun `while it writes, the line only grows, the nib is at its end and on the paper, and the ink is whole`() {
        writing.zipWithNext { a, b -> assertTrue("the line shrank between $a and $b", Quill.written(b) >= Quill.written(a)) }
        assertEquals(0f, Quill.written(0f), 0.0001f)
        for (p in writing) {
            assertEquals(Quill.written(p), Quill.at(p), 0f)
            assertEquals(0f, Quill.lift(p), 0f)
            assertEquals(1f, Quill.ink(p), 0f)
        }
        // By the time it stops writing the whole line is there.
        assertTrue(Quill.written(Quill.WRITE - 0.001f) > 0.999f)
        assertEquals(1f, Quill.written(Quill.WRITE), 0f)
    }

    @Test fun `it writes slowly at the line's ends and quickest in its middle`() {
        fun speed(p: Float) = Quill.written(p + 0.01f) - Quill.written(p)
        assertTrue(speed(0f) < speed(Quill.WRITE / 2))
        assertTrue(speed(Quill.WRITE - 0.011f) < speed(Quill.WRITE / 2))
    }

    @Test fun `on its way back the line stays whole and fades, and the nib lifts and comes down again`() {
        for (p in back) assertEquals(1f, Quill.written(p), 0f)
        back.zipWithNext { a, b ->
            assertTrue("the ink came back between $a and $b", Quill.ink(b) <= Quill.ink(a))
            assertTrue("the nib went forward between $a and $b", Quill.at(b) <= Quill.at(a) + 0.0001f)
        }
        assertEquals(1f, Quill.ink(Quill.WRITE), 0.0001f)
        assertTrue(Quill.ink(0.999f) < 0.01f)
        // Off the paper in the middle of the way back, and down at both ends of it.
        assertEquals(0f, Quill.lift(Quill.WRITE), 0.0001f)
        assertTrue(Quill.lift((Quill.WRITE + 1f) / 2) > 0.99f)
        assertTrue(Quill.lift(0.999f) < 0.02f)
        assertTrue(back.all { Quill.lift(it) in 0f..1f })
    }

    @Test fun `nothing jumps, and a cycle ends where the next begins`() {
        // No value moves by more than a small step from one thousandth of the cycle to the next.
        steps.zipWithNext { a, b ->
            assertTrue("the nib jumped at $a", kotlin.math.abs(Quill.at(b) - Quill.at(a)) < 0.01f)
            assertTrue("the lift jumped at $a", kotlin.math.abs(Quill.lift(b) - Quill.lift(a)) < 0.02f)
            assertTrue("the ink jumped at $a", kotlin.math.abs(Quill.ink(b) - Quill.ink(a)) < 0.01f)
            assertTrue("the lean jumped at $a", kotlin.math.abs(Quill.lean(b) - Quill.lean(a)) < 0.6f)
        }
        // At the end of a cycle the nib is back at the start, down, the old line gone and none of the new written.
        assertTrue("the nib is not back", Quill.at(0.9999f) < 0.01f)
        assertTrue("the nib is not down", Quill.lift(0.9999f) < 0.01f)
        assertTrue("the old line still shows", Quill.ink(0.9999f) < 0.01f)
        assertEquals(0f, Quill.at(0f), 0.0001f)
        assertEquals(0f, Quill.lean(0f), 0.0001f)
    }

    @Test fun `it rocks a little with each hump, and never far`() {
        assertTrue(writing.all { kotlin.math.abs(Quill.lean(it)) <= 5.001f })
        assertTrue(writing.any { Quill.lean(it) > 4f } && writing.any { Quill.lean(it) < -4f })
        assertTrue(back.all { Quill.lean(it) == 0f })
    }

    @Test fun `standing still, the line is written and the quill is at its end`() {
        assertTrue(Quill.written(Quill.STILL) > 0.999f)
        assertTrue(Quill.at(Quill.STILL) > 0.999f)
        assertEquals(1f, Quill.ink(Quill.STILL), 0f)
        assertEquals(0f, Quill.lift(Quill.STILL), 0f)
    }

    @Test fun `the line has a few humps, the first the tallest, and a cycle is short enough to read as writing`() {
        assertTrue(Quill.HUMPS.size in 3..5)
        assertEquals(1f, Quill.HUMPS.max(), 0f)
        assertEquals(1f, Quill.HUMPS[0], 0f)
        assertTrue(Quill.HUMPS.all { it in 0.4f..1f })
        assertTrue(Quill.CYCLE_MS in 1200L..2400L)
    }
}
