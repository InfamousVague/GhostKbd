package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The emoji key (Matt, 2026-11-11: "Move the emoji button to the left of the spacebar and to the right of the code
 * toggle, make it the gray color"): where it stands on the letters page, what it takes from the space bar, and the
 * boards it stays off so the bar keeps its three key widths.
 */
class LayoutsTest {
    private fun bottom(rows: List<KeyRow>) = rows.last().keys
    private fun types(rows: List<KeyRow>) = bottom(rows).map { it.type }
    private fun bar(rows: List<KeyRow>) = bottom(rows).first { it.type == KeyType.SPACE }.width
    private fun letters(id: String = "qwerty", globe: Boolean = false) = Layouts.letters(Layouts.spec(id)!!, false, emptyMap(), globe)

    /** As KeyboardView.rebuild puts them together: the emoji key first, then the mic. */
    private fun board(rows: List<KeyRow>, mic: Boolean) = Layouts.withEmoji(rows, mic).let { if (mic) Layouts.withMic(it) else it }

    @Test fun `the emoji key stands straight after the code key, and the space bar comes next`() {
        val rows = board(letters(), mic = false)
        assertEquals(listOf(KeyType.MODE, KeyType.MODE, KeyType.EMOJI, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(rows))
        assertEquals("{ }", bottom(rows)[1].label)
        assertTrue(Layouts.hasEmojiKey(rows))
    }

    @Test fun `it takes one key's width from the space bar, and the row is as wide as it was`() {
        val plain = letters()
        val rows = board(plain, mic = false)
        assertEquals(5f, bar(plain), 0f)
        assertEquals(4f, bar(rows), 0f)
        assertEquals(plain.last().units, rows.last().units, 0f)
        assertEquals(1f, bottom(rows).first { it.type == KeyType.EMOJI }.width, 0f)
    }

    @Test fun `it is drawn as a function key, in the grey of the other special keys`() {
        assertTrue(Key(KeyType.EMOJI).functional)
        assertFalse(Key(KeyType.GLOBE).functional)
    }

    @Test fun `the globe and the mic stand between it and the bar`() {
        assertEquals(listOf(KeyType.MODE, KeyType.MODE, KeyType.EMOJI, KeyType.GLOBE, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(board(letters(globe = true), mic = false)))
        assertEquals(listOf(KeyType.MODE, KeyType.MODE, KeyType.EMOJI, KeyType.MIC, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(board(letters(), mic = true)))
        assertEquals(3f, bar(board(letters(globe = true), mic = false)), 0f)
        assertEquals(3f, bar(board(letters(), mic = true)), 0f)
    }

    @Test fun `with both the globe and the mic the bar has nothing to give, and the board is as it was`() {
        val plain = letters(globe = true)
        assertSame(plain, Layouts.withEmoji(plain, mic = true))
        val rows = board(plain, mic = true)
        assertFalse(Layouts.hasEmojiKey(rows))
        assertEquals(listOf(KeyType.MODE, KeyType.MODE, KeyType.GLOBE, KeyType.MIC, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(rows))
        assertEquals(3f, bar(rows), 0f)
    }

    @Test fun `no board is left with a bar under three keys, and every board without both extras gets the key`() {
        for (spec in Layouts.SPECS) for (globe in listOf(false, true)) for (mic in listOf(false, true)) {
            val rows = board(Layouts.letters(spec, false, emptyMap(), globe), mic)
            assertTrue("${spec.id} globe=$globe mic=$mic leaves a bar of ${bar(rows)}", bar(rows) >= Layouts.LEAST_BAR)
            if (!globe && !mic) assertTrue("${spec.id} has no emoji key", Layouts.hasEmojiKey(rows))
        }
    }

    @Test fun `the other pages have no code key and so no emoji key`() {
        val qwerty = Layouts.spec("qwerty")!!
        for (page in listOf(Layouts.symbols(qwerty), Layouts.more(qwerty), Layouts.dev())) {
            assertSame(page, Layouts.withEmoji(page, mic = false))
            assertFalse(Layouts.hasEmojiKey(page))
        }
    }
}
