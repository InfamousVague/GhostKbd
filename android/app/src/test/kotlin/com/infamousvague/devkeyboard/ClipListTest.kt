package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clipboard history's rules (ClipList.kt), pins most of all (Matt, 2026-11-12: "I like … clipboard pins"): a
 * pinned clip leads the list, is not one of the copies the history counts, is never pushed out by a newer copy, and
 * stays when the history is cleared.
 */
class ClipListTest {
    private fun clip(text: String, at: Long, pinned: Boolean = false) = ClipList.Clip(text, at, pinned)
    private fun texts(list: List<ClipList.Clip>) = list.map { it.text }

    /** [n] copies, "c1" the oldest at time 1. */
    private fun copies(n: Int, max: Int = 25): List<ClipList.Clip> = (1..n).fold(emptyList()) { list, i -> ClipList.copied(list, "c$i", i.toLong(), max) }

    @Test fun `copies are newest first, and the history holds no more than it is given room for`() {
        val list = copies(30)
        assertEquals(25, list.size)
        assertEquals("c30", ClipList.shown(list).first().text)
        assertEquals("c6", ClipList.shown(list).last().text)
        assertFalse("c5" in texts(list))
    }

    @Test fun `a text copied again is the same clip made new, not a second one`() {
        val list = ClipList.copied(copies(3), "c1", 10, 25)
        assertEquals(listOf("c1", "c3", "c2"), texts(ClipList.shown(list)))
        assertEquals(10, list.first { it.text == "c1" }.at)
    }

    @Test fun `a pinned clip leads the list, however old it is`() {
        val list = ClipList.pinned(copies(5), "c1", true, 25)!!
        assertEquals(listOf("c1", "c5", "c4", "c3", "c2"), texts(ClipList.shown(list)))
        assertTrue(ClipList.shown(list).first().pinned)
        // Two pins: the newer of them first, then the copies.
        val two = ClipList.pinned(list, "c4", true, 25)!!
        assertEquals(listOf("c4", "c1", "c5", "c3", "c2"), texts(ClipList.shown(two)))
    }

    @Test fun `a pinned clip is not one of the copies counted, and a newer copy never pushes it out`() {
        var list = ClipList.pinned(copies(25), "c1", true, 25)!!
        // Twenty-five more copies: every unpinned clip from before is gone, the pin is not.
        for (i in 26..50) list = ClipList.copied(list, "c$i", i.toLong(), 25)
        assertEquals(26, list.size)
        assertEquals(25, list.count { !it.pinned })
        assertTrue(list.single { it.pinned }.text == "c1")
        assertEquals("c1", ClipList.shown(list).first().text)
        assertEquals("c26", ClipList.shown(list).last().text)
    }

    @Test fun `copying a pinned text again keeps it pinned`() {
        val list = ClipList.copied(ClipList.pinned(copies(3), "c2", true, 25)!!, "c2", 9, 25)
        val again = list.single { it.text == "c2" }
        assertTrue(again.pinned)
        assertEquals(9, again.at)
        assertEquals(3, list.size)
    }

    @Test fun `clearing takes the copies and leaves what is pinned`() {
        val list = ClipList.pinned(ClipList.pinned(copies(6), "c2", true, 25)!!, "c5", true, 25)!!
        assertEquals(listOf("c5", "c2"), texts(ClipList.shown(ClipList.cleared(list))))
        assertEquals(emptyList<ClipList.Clip>(), ClipList.cleared(copies(6)))
    }

    @Test fun `unpinned, a clip goes back among the copies as old as it is`() {
        val pinned = ClipList.pinned(copies(4), "c1", true, 25)!!
        val back = ClipList.pinned(pinned, "c1", false, 25)!!
        assertEquals(listOf("c4", "c3", "c2", "c1"), texts(ClipList.shown(back)))
        assertTrue(back.none { it.pinned })
        // With the history full of newer copies there is no room for it: unpinned, it is let go.
        var full = ClipList.pinned(copies(25), "c1", true, 25)!!
        full = ClipList.copied(full, "c26", 26, 25)
        val gone = ClipList.pinned(full, "c1", false, 25)!!
        assertFalse("c1" in texts(gone))
        assertEquals(25, gone.size)
    }

    @Test fun `no more than the most can be pinned, and a refused pin changes nothing`() {
        var list = copies(ClipList.MAX_PINS + 1, max = 100)
        for (i in 1..ClipList.MAX_PINS) list = ClipList.pinned(list, "c$i", true, 100)!!
        assertEquals(ClipList.MAX_PINS, list.count { it.pinned })
        assertNull(ClipList.pinned(list, "c${ClipList.MAX_PINS + 1}", true, 100))
        assertNull(ClipList.added(list, "a new one", 999, 100))
        // Unpinning still works at the limit, and makes room for another.
        val fewer = ClipList.pinned(list, "c1", false, 100)!!
        assertEquals(ClipList.MAX_PINS, ClipList.pinned(fewer, "c${ClipList.MAX_PINS + 1}", true, 100)!!.count { it.pinned })
    }

    @Test fun `pinning what is pinned, or what is not there, leaves the list as it was`() {
        val list = ClipList.pinned(copies(3), "c1", true, 25)!!
        assertSame(list, ClipList.pinned(list, "c1", true, 25))
        assertSame(list, ClipList.pinned(list, "never copied", true, 25))
        assertSame(list, ClipList.pinned(list, "c2", false, 25))
    }

    @Test fun `a pin written by hand is a clip of its own, or the same clip pinned`() {
        val list = ClipList.added(copies(2), "my address", 50, 25)!!
        assertEquals(listOf("my address", "c2", "c1"), texts(ClipList.shown(list)))
        assertTrue(list.single { it.text == "my address" }.pinned)
        // Writing what was already copied pins that clip; it is not kept twice.
        val same = ClipList.added(copies(2), "c1", 50, 25)!!
        assertEquals(2, same.size)
        assertTrue(same.single { it.text == "c1" }.pinned)
        assertEquals(1, same.single { it.text == "c1" }.at)
    }

    @Test fun `removing takes a clip for good, pinned or not`() {
        val list = ClipList.pinned(copies(3), "c2", true, 25)!!
        assertEquals(listOf("c3", "c1"), texts(ClipList.shown(ClipList.removed(list, "c2"))))
        assertEquals(listOf("c2", "c1"), texts(ClipList.shown(ClipList.removed(list, "c3"))))
    }

    @Test fun `recent is by time alone, whatever is pinned`() {
        val list = ClipList.pinned(copies(4), "c1", true, 25)!!
        assertEquals(listOf("c4", "c3", "c2", "c1"), texts(ClipList.recent(list)))
        // What Ghostwriter is given as "my recent clipboard" is the newest copies, not an old pin first.
        assertEquals(listOf("c4", "c3"), texts(ClipList.recent(list).take(2)))
    }
}
