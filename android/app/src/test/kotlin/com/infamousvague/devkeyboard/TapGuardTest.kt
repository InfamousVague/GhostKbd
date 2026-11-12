package com.infamousvague.devkeyboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tap that moves its own button (TapGuard). Undo and Redo are in the row only while each has something to do (Matt,
 * 2026-11-12: "only show undo and redo when an undo and redo is available"), so the last Undo takes the Undo arrow
 * away and Redo stands where it stood: a thumb that is still tapping must not redo what it has just undone.
 *
 * Places are across the screen in pixels; here an arrow is 40 wide, the first standing at 6.
 */
class TapGuardTest {
    @Test fun `with no arrow tapped every tap is taken`() {
        assertTrue(TapGuard().takes("clips", 6, 46, now = 1000))
    }

    @Test fun `the last undo does not turn the next tap into a redo`() {
        val guard = TapGuard()
        guard.tapped("undo", 6, 46, now = 1000)
        // Nothing is left to undo: Undo has gone and Redo stands in its place. The thumb comes down again.
        assertFalse(guard.takes("redo", 6, 46, now = 1250))
        assertFalse(guard.takes("redo", 6, 46, now = 1599))
        // A moment on, the arrow has been seen to turn: a tap on Redo is a tap on Redo.
        assertTrue(guard.takes("redo", 6, 46, now = 1600))
    }

    @Test fun `undo goes on being taken while it stays where it is`() {
        val guard = TapGuard()
        guard.tapped("undo", 6, 46, now = 1000)
        assertTrue(guard.takes("undo", 6, 46, now = 1150))
        guard.tapped("undo", 6, 46, now = 1150)
        assertTrue(guard.takes("undo", 6, 46, now = 1300))
    }

    @Test fun `the arrow itself is taken wherever the row has put it`() {
        val guard = TapGuard()
        // Tapped at the end of a row of words, and the row turned to its tools: Undo is at the other end now.
        guard.tapped("undo", 364, 412, now = 1000)
        assertTrue(guard.takes("undo", 6, 46, now = 1200))
        // And the tool that stands where Undo was is not taken for it.
        assertFalse(guard.takes("enhance", 340, 420, now = 1200))
    }

    @Test fun `redo beside undo is taken at once`() {
        val guard = TapGuard()
        guard.tapped("undo", 6, 46, now = 1000)
        // Undo is still there, Redo in the next place along: a tap on it is meant.
        assertTrue(guard.takes("redo", 46, 86, now = 1200))
    }

    @Test fun `the last redo does not open the clipboard, nor the redo before it undo`() {
        val guard = TapGuard()
        // Undo and Redo side by side; the last redo takes Redo away and the clipboard slides into its place.
        guard.tapped("redo", 46, 86, now = 1000)
        assertFalse(guard.takes("clips", 46, 86, now = 1300))
        assertTrue(guard.takes("clips", 46, 86, now = 1700))
        // Redo alone, where Undo stood; its tap brings Undo back to that place and moves Redo along.
        guard.tapped("redo", 6, 46, now = 3000)
        assertFalse(guard.takes("undo", 6, 46, now = 3200))
        assertTrue(guard.takes("redo", 46, 86, now = 3200))
    }

    @Test fun `a neighbour that only touches the place is taken`() {
        val guard = TapGuard()
        guard.tapped("undo", 6, 46, now = 1000)
        // A chip that begins where the arrow ended, and one that covers less than half of it.
        assertTrue(guard.takes("clips", 46, 86, now = 1100))
        assertTrue(guard.takes("clips", 30, 70, now = 1100))
        // One that covers most of it is not.
        assertFalse(guard.takes("clips", 20, 60, now = 1100))
        // A wide chip over all of it is not either, however much of the chip lies elsewhere.
        assertFalse(guard.takes("rewrite", 0, 120, now = 1100))
    }

    @Test fun `a clock that went back holds nothing`() {
        val guard = TapGuard()
        guard.tapped("undo", 6, 46, now = 1000)
        assertTrue(guard.takes("redo", 6, 46, now = 900))
    }
}
