package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the suggested words share the row (PluginRow.WordRoom; Matt, 2026-11-12: "Center the auto complete words and
 * only show about three of them reserve some space on the right to show at least one toolbar icon"): three words a
 * third each, in the row's middle, with room for one tool kept at the row's end where tools follow them.
 *
 * The numbers are pixels; here one dp is one pixel, so a 412-wide row is a common phone, 48 is one tool's room and
 * 80 the least a word is read in.
 */
class WordRoomTest {
    private fun room(total: Int, before: Int = 0, words: Int = 3, firstWord: Int = 0, lastWord: Int = words - 1, count: Int = words) =
        PluginRow.WordRoom.of(total, before, words, firstWord, lastWord, count, end = 48, least = 80)

    /** Where the words' area starts and ends on the screen, given what stands before the strip. */
    private fun span(r: PluginRow.WordRoom, before: Int) = (before + r.lead) to (before + r.lead + 3 * r.width)

    @Test fun `three words and the tools after them, on a phone, stand in the middle with one tool's room at the end`() {
        // Three words, then four tools in the strip.
        val r = room(412, count = 7)
        assertEquals(48, r.lead)
        assertEquals((412 - 96) / 3, r.width)
        assertEquals(0, r.gap)
        val (from, to) = span(r, 0)
        // As much before the words as after them: the middle of the words is the middle of the row.
        assertTrue(kotlin.math.abs((from + to) / 2 - 412 / 2) <= 1)
        // And what is left at the end is one tool's room: the first tool is in view.
        assertTrue(412 - to >= 48)
    }

    @Test fun `words alone, with no tool to show, take the whole row as they did`() {
        val r = room(412)
        assertEquals(0, r.lead)
        assertEquals(412 / 3, r.width)
    }

    @Test fun `one word or two stand in the middle of the room three would have had`() {
        val three = room(412, count = 7)
        val two = room(412, words = 2, count = 6)
        val one = room(412, words = 1, count = 5)
        assertEquals(three.width, two.width)
        assertEquals(three.width, one.width)
        assertEquals(three.width / 2, two.gap)
        assertEquals(three.width, one.gap)
        // The words and the room either side of them still fill the three thirds (to the pixel a half rounds away),
        // so the tools start where they did.
        assertTrue(3 * three.width - (2 * two.width + 2 * two.gap) in 0..1)
        assertEquals(3 * three.width, 1 * one.width + 2 * one.gap)
    }

    @Test fun `beside pinned tools the words are still in the row's middle`() {
        // A foldable's inner screen: four tools pinned at the start (4 x 40 and the edge), then three words.
        val before = 4 * 40 + 6
        val r = room(690, before = before)
        // The pinned tools are already as much as is kept on the other side: nothing more before the words.
        assertEquals(0, r.lead)
        assertEquals((690 - 2 * before) / 3, r.width)
        val (from, to) = span(r, before)
        assertTrue(kotlin.math.abs((from + to) / 2 - 690 / 2) <= 1)
        // One pinned tool is narrower than a tool's room at the end: the difference is kept before the words.
        val one = room(690, before = 46, count = 5)
        assertEquals(2, one.lead)
        assertEquals((690 - 96) / 3, one.width)
    }

    @Test fun `on a narrow row the words are not squeezed to stand in the middle`() {
        // 300 wide: in the middle each word would have 68, under what a word is read in. The tool's room is still kept.
        val r = room(300, count = 6)
        assertEquals(0, r.lead)
        assertEquals((300 - 48) / 3, r.width)
        assertTrue(r.width >= 80)
        // 336 is the least at which three words of 80 fit between two rooms of 48.
        assertEquals(48, room(336, count = 6).lead)
        assertEquals(0, room(335, count = 6).lead)
    }

    @Test fun `with a chip before them the words are not moved, and a tool's room is still kept after them`() {
        // "Incognito on", then three words, then tools: the chip opens the strip.
        val r = room(412, firstWord = 1, lastWord = 3, count = 8)
        assertEquals(0, r.lead)
        assertEquals((412 - 48) / 3, r.width)
        assertEquals(1, r.first)
        assertEquals(3, r.last)
    }

    @Test fun `more than three words never share the row`() {
        // Nothing gives the row more than three today; if something did, they would be a third each and the rest slide.
        val r = room(412, words = 5, count = 5)
        assertEquals(412 / 3, r.width)
        assertEquals(0, r.gap)
    }

    @Test fun `a row with no words keeps no room for them`() {
        val r = room(412, words = 0, firstWord = -1, lastWord = -1, count = 6)
        assertEquals(0, r.width)
        assertEquals(0, r.lead)
        assertEquals(0, r.gap)
    }
}
