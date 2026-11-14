package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The musclememory plugin's board (MuscleMemory.kt): SwiftKey's letters page made from Gboard's, part by part. */
class MuscleMemoryTest {
    private fun board(emoji: Boolean = true, mic: Boolean = false, numbers: Boolean = true) =
        MuscleMemory.letters(Layouts.letters(Layouts.QWERTY, numberRow = numbers), emoji, mic)

    @Test fun `the corners are SwiftKey's, first on each long-press`() {
        val rows = board()
        assertEquals(MuscleMemory.TOP_HINTS, rows[1].keys.joinToString("") { it.hint.orEmpty() })
        assertEquals(MuscleMemory.MIDDLE_HINTS, rows[2].keys.joinToString("") { it.hint.orEmpty() })
        assertEquals(MuscleMemory.THIRD_HINTS, rows[3].keys.filter { it.type == KeyType.CHAR }.joinToString("") { it.hint.orEmpty() })
        rows.subList(1, 4).flatMap { it.keys }.filter { it.type == KeyType.CHAR }.forEach { assertEquals(it.hint, it.alts.first()) }
        // Gboard's corner on d was $; SwiftKey's is &, and the $ goes with Gboard's corner.
        assertFalse("$" in rows[2].keys.first { it.text == "d" }.alts)
    }

    @Test fun `without a number row the corners are the same, the top row's digits gone`() {
        val rows = board(numbers = false)
        assertEquals(4, rows.size)
        assertEquals(MuscleMemory.TOP_HINTS, rows[0].keys.joinToString("") { it.hint.orEmpty() })
        assertFalse("1" in rows[0].keys.first().alts)
    }

    @Test fun `the number row is bare digits, short where the numbers part is on`() {
        val digits = Layouts.letters(Layouts.QWERTY, numberRow = true).first()
        val short = MuscleMemory.numbers(digits, condensed = true)
        assertEquals("1234567890", short.keys.joinToString("") { it.text })
        assertTrue(short.keys.all { it.bare })
        assertEquals(MuscleMemory.NUMBER_ROW, short.height, 0.001f)
        assertEquals(1f, MuscleMemory.numbers(digits, condensed = false).height, 0.001f)
    }

    @Test fun `the bottom row is ?123, emoji, comma, space, full stop, enter, ten keys wide`() {
        val bottom = board().last()
        assertEquals(listOf(KeyType.MODE, KeyType.EMOJI, KeyType.CHAR, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), bottom.keys.map { it.type })
        assertEquals(",", bottom.keys[2].text)
        assertEquals(".", bottom.keys[4].text)
        assertEquals(MuscleMemory.PERIOD_HINT, bottom.keys[4].hint)
        assertEquals(listOf(",", "!", "?"), bottom.keys[4].alts.take(3))
        assertEquals(10f, bottom.units, 0.001f)
    }

    @Test fun `only the full stop flicks`() {
        val keys = board().flatMap { it.keys }
        assertEquals(listOf("."), keys.filter { it.flick }.map { it.text })
    }

    @Test fun `without the emoji plugin the space bar has its width`() {
        val bottom = board(emoji = false).last()
        assertTrue(bottom.keys.none { it.type == KeyType.EMOJI })
        assertEquals(10f, bottom.units, 0.001f)
        assertEquals(5f, bottom.keys.first { it.type == KeyType.SPACE }.width, 0.001f)
    }

    @Test fun `the comma holds the mic only where dictation is ready`() {
        assertTrue(board(mic = true).last().keys.first { it.text == "," }.holdsMic)
        assertFalse(board(mic = false).last().keys.first { it.text == "," }.holdsMic)
    }

    @Test fun `the arrow row is up, down, left, right across the board, and short`() {
        val row = MuscleMemory.arrows(10f)
        assertEquals(listOf("up", "down", "left", "right"), row.keys.map { it.text })
        assertTrue(row.keys.all { it.type == KeyType.ARROW && it.bare })
        assertEquals(10f, row.units, 0.001f)
        assertEquals(MuscleMemory.ARROW_ROW, row.height, 0.001f)
    }

    @Test fun `every page is headed by the number row, ?123 by its own digits`() {
        val number = MuscleMemory.numbers(Layouts.letters(Layouts.QWERTY, numberRow = true).first(), condensed = true)
        val symbols = MuscleMemory.page(Layouts.symbols(), Page.SYMBOLS, number, condensed = true)
        assertEquals(Layouts.symbols().size, symbols.size)
        assertEquals(MuscleMemory.NUMBER_ROW, symbols.first().height, 0.001f)
        assertTrue(symbols.first().keys.all { it.bare })
        val dev = MuscleMemory.page(Layouts.dev(), Page.DEV, number, condensed = true)
        assertEquals(Layouts.dev().size + 1, dev.size)
        assertEquals("1234567890", dev.first().keys.joinToString("") { it.text })
        // With no number row nothing is added.
        assertEquals(Layouts.more().size, MuscleMemory.page(Layouts.more(), Page.MORE, null, condensed = true).size)
    }

    @Test fun `the quick menu has the full stop over its key, moved in from an edge`() {
        assertEquals(listOf("!", ",", ".", "?"), MuscleMemory.FLICK)
        // Mid-board: the full stop's cell (the third) is centred on the key.
        val left = MuscleMemory.flickLeft(keyX = 500f, cellW = 100f, minX = 0f, maxX = 1000f)
        assertEquals(500f, left + 2.5f * 100f, 0.001f)
        // Near the right edge it comes in, whole.
        assertEquals(600f, MuscleMemory.flickLeft(keyX = 900f, cellW = 100f, minX = 0f, maxX = 1000f), 0.001f)
        assertEquals(0f, MuscleMemory.flickLeft(keyX = 50f, cellW = 100f, minX = 0f, maxX = 1000f), 0.001f)
    }

    @Test fun `other layouts keep their letters, with SwiftKey's corners by place`() {
        val azerty = Layouts.spec("azerty")!!
        val rows = MuscleMemory.letters(Layouts.letters(azerty, numberRow = true), emoji = true, mic = false)
        assertEquals("azertyuiop", rows[1].keys.joinToString("") { it.text })
        assertEquals("%", rows[1].keys[0].hint)
    }

    @Test fun `parts, and any of them`() {
        assertFalse(MuscleMemory.Parts().any)
        assertTrue(MuscleMemory.Parts(arrows = true).any)
        assertTrue(Plugins.MUSCLE_MEMORY in Plugins.ALL)
    }
}
