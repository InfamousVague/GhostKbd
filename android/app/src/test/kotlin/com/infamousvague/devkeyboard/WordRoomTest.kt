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

    @Test fun `a chip before the words takes its room out of theirs, and the tool is still in view`() {
        // "Incognito on" (120 with its margins and the edge) and two words, then the tools: 412 - 120 - 48 = 244.
        val two = room(412, before = 120, words = 2, firstWord = 1, lastWord = 2, count = 7)
        assertEquals(0, two.lead)
        assertEquals(244 / 3, two.width)
        // The chip, the words and the room either side of them end where the tool's room begins.
        assertTrue(412 - (120 + 2 * two.width + 2 * two.gap) in 48..50)
        // A clip copied a moment ago, cut at a third of the row (137 and the edge), and three words: 221 for them.
        val three = room(412, before = 143, firstWord = 1, lastWord = 3, count = 8)
        assertEquals(221 / 3, three.width)
        // The pixel or two a third rounds away is shared between the words' two sides.
        assertTrue(three.gap in 0..1)
        assertTrue(412 - (143 + 3 * three.width + 2 * three.gap) in 48..50)
    }

    @Test fun `in a tight row one word or two are as wide as a word is read in`() {
        // 228 for the words: a third each would be 76, under the 80 a word is read in.
        val two = room(412, before = 136, words = 2, firstWord = 1, lastWord = 2, count = 7)
        assertEquals(80, two.width)
        assertEquals((228 - 160) / 2, two.gap)
        val one = room(412, before = 136, words = 1, firstWord = 1, lastWord = 1, count = 6)
        assertEquals(80, one.width)
        assertEquals((228 - 80) / 2, one.gap)
        // Three share what there is, and the tool keeps its room.
        val three = room(412, before = 136, firstWord = 1, lastWord = 3, count = 8)
        assertEquals(76, three.width)
        assertEquals(0, three.gap)
    }

    @Test fun `words are never cut below three quarters of what a word is read in`() {
        // A row with 150 left for three words: 50 each would be cut to a letter or two. They keep 60 and the row slides.
        val r = room(412, before = 214, firstWord = 1, lastWord = 3, count = 8)
        assertEquals(60, r.width)
        assertEquals(0, r.gap)
        // And where what stands before them is wider than the row itself.
        val none = room(300, before = 400, firstWord = 2, lastWord = 4, count = 9)
        assertEquals(60, none.width)
        assertEquals(0, none.gap)
    }

    @Test fun `an emoji that came with the word stands between the words and the tool, and the tool is still in view`() {
        // Three words, one emoji chip (52 with its margins), then the tools.
        val r = PluginRow.WordRoom.of(412, 0, 3, 0, 2, 9, end = 48, least = 80, after = 52)
        // In the middle each word would have 70: they are not squeezed for it, and share what the chip and the tool leave.
        assertEquals(0, r.lead)
        assertEquals((412 - 52 - 48) / 3, r.width)
        assertTrue(412 - (3 * r.width + 52) >= 48)
        // Two chips: the words give up that much more.
        assertEquals((412 - 104 - 48) / 3, PluginRow.WordRoom.of(412, 0, 3, 0, 2, 10, end = 48, least = 80, after = 104).width)
        // On a wide row the words keep to the middle, with as much clear before them as the chip and the tool take.
        val wide = PluginRow.WordRoom.of(690, 0, 3, 0, 2, 9, end = 48, least = 80, after = 52)
        assertEquals(100, wide.lead)
        assertEquals((690 - 200) / 3, wide.width)
    }

    @Test fun `the everyday tools are pinned only where the words still stand in the middle beside them`() {
        fun pins(total: Int, tools: Int) = PluginRow.WordRoom.pins(total, tools * 40 + 6, tools, least = 80)
        // A phone, and a wide phone (475, where until 0.16.0 five were pinned and the words stood off to one side).
        assertEquals(false, pins(412, 4))
        assertEquals(false, pins(475, 5))
        assertEquals(false, pins(475, 3))
        // A foldable's inner screen and a tablet: all five, with three words of 80 or more in the middle.
        assertEquals(true, pins(690, 5))
        assertEquals(true, pins(800, 5))
        // The least width for five: twice their 206 and three words of 80.
        assertEquals(true, pins(2 * 206 + 240, 5))
        assertEquals(false, pins(2 * 206 + 239, 5))
        // Nothing to pin is never pinned.
        assertEquals(false, pins(2000, 0))
        // And where they are pinned the words are centred: the same sum WordRoom.of makes.
        val r = room(690, before = 206, count = 7)
        assertEquals(0, r.lead)
        assertEquals((690 - 412) / 3, r.width)
        assertTrue(r.width >= 80)
    }
}
