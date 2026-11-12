package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom row's two guests (Matt, 2026-11-12: "Move the emoji back next to the clipboard icon in the toolbar, when
 * dictation is enabled replace the period key next to the enter key"). The mic key stands where the full stop was, on
 * the letters page only, and still gives the full stop and its punctuation on a long press; nothing takes from the
 * space bar any more, the emoji key least of all, which is a button in the row again.
 */
class LayoutsTest {
    private fun bottom(rows: List<KeyRow>) = rows.last().keys
    private fun types(rows: List<KeyRow>) = bottom(rows).map { it.type }
    private fun bar(rows: List<KeyRow>) = bottom(rows).first { it.type == KeyType.SPACE }.width
    private fun letters(id: String = "qwerty", globe: Boolean = false) = Layouts.letters(Layouts.spec(id)!!, false, emptyMap(), globe)
    private fun mic(rows: List<KeyRow>) = bottom(rows).first { it.type == KeyType.MIC }

    @Test fun `without dictation the row is the code key, the bar, the full stop and enter`() {
        val rows = letters()
        assertEquals(listOf(KeyType.MODE, KeyType.MODE, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(rows))
        assertEquals("{ }", bottom(rows)[1].label)
        assertEquals(".", bottom(rows)[3].text)
        assertEquals(5f, bar(rows), 0f)
    }

    @Test fun `the mic key takes the full stop's place, next to enter`() {
        val rows = Layouts.withMic(letters())
        assertEquals(listOf(KeyType.MODE, KeyType.MODE, KeyType.SPACE, KeyType.MIC, KeyType.ENTER), types(rows))
        assertEquals(KeyType.ENTER, bottom(rows)[bottom(rows).indexOfFirst { it.type == KeyType.MIC } + 1].type)
        assertTrue("no full-stop key is left on the row", bottom(rows).none { it.type == KeyType.CHAR && it.text == "." })
    }

    @Test fun `it takes nothing from the space bar, and the row is as wide as it was`() {
        val plain = letters()
        val rows = Layouts.withMic(plain)
        assertEquals(5f, bar(plain), 0f)
        assertEquals(5f, bar(rows), 0f)
        assertEquals(plain.last().units, rows.last().units, 0f)
        assertEquals(1f, mic(rows).width, 0f)
        // The rows above it are the very same.
        plain.dropLast(1).forEachIndexed { i, row -> assertSame(row, rows[i]) }
    }

    @Test fun `the full stop is on its long press, first, with the punctuation the full stop had`() {
        val stop = bottom(letters())[3]
        val key = mic(Layouts.withMic(letters()))
        assertEquals(".", key.alts.first())
        assertEquals(listOf(".") + stop.alts, key.alts)
        // The comma has no key of its own on the letters page: it must still be a long press away.
        assertTrue("," in key.alts)
        assertTrue("?" in key.alts && "!" in key.alts)
        // And it shows in the key's corner, as a letter's long-press character does.
        assertEquals(".", key.hint)
        // A tap on it is the microphone's: it types nothing.
        assertEquals("", key.text)
    }

    @Test fun `a board with a full stop of its own gives that one first, and the Latin one after it`() {
        val urdu = Layouts.letters(Layouts.spec("urdu")!!, false, emptyMap(), false)
        val stop = bottom(urdu).last { it.type == KeyType.CHAR }
        assertEquals("۔", stop.text)
        val key = mic(Layouts.withMic(urdu))
        assertEquals("۔", key.hint)
        assertEquals("۔", key.alts[0])
        assertTrue("." in key.alts)
        assertEquals(key.alts.distinct(), key.alts)
        // Its half-space keeps its key beside the bar: only the full stop gave way.
        assertEquals(listOf("‌", "۔"), bottom(urdu).filter { it.type == KeyType.CHAR }.map { it.text })
        assertEquals(listOf("‌"), bottom(Layouts.withMic(urdu)).filter { it.type == KeyType.CHAR }.map { it.text })
    }

    @Test fun `the globe keeps its place before the bar`() {
        val rows = Layouts.withMic(letters(globe = true))
        assertEquals(listOf(KeyType.MODE, KeyType.MODE, KeyType.GLOBE, KeyType.SPACE, KeyType.MIC, KeyType.ENTER), types(rows))
        assertEquals(4f, bar(rows), 0f)
    }

    @Test fun `on every board the mic is the key before enter, and the bar is at least four keys wide`() {
        for (spec in Layouts.SPECS) for (globe in listOf(false, true)) {
            val plain = Layouts.letters(spec, false, emptyMap(), globe)
            val rows = Layouts.withMic(plain)
            val keys = bottom(rows)
            val at = keys.indexOfFirst { it.type == KeyType.MIC }
            assertTrue("${spec.id} globe=$globe has no mic key", at >= 0)
            assertEquals("${spec.id} globe=$globe", KeyType.ENTER, keys[at + 1].type)
            assertEquals("${spec.id} globe=$globe", spec.fullStop, keys[at].alts.first())
            assertEquals("${spec.id} globe=$globe", bar(plain), bar(rows), 0f)
            assertTrue("${spec.id} globe=$globe leaves a bar of ${bar(rows)}", bar(rows) >= 4f)
            assertEquals("${spec.id} globe=$globe", plain.last().units, rows.last().units, 0f)
        }
    }

    @Test fun `the other pages keep their full stop and have no mic`() {
        val qwerty = Layouts.spec("qwerty")!!
        for (page in listOf(Layouts.symbols(qwerty), Layouts.more(qwerty), Layouts.dev())) {
            assertSame(page, Layouts.withMic(page))
            assertTrue(bottom(page).any { it.type == KeyType.CHAR && it.text == "." })
            assertNull(bottom(page).firstOrNull { it.type == KeyType.MIC })
        }
    }

    @Test fun `no board has an emoji key, and no key type is left for one`() {
        assertFalse(KeyType.values().any { it.name == "EMOJI" })
        assertFalse(Key(KeyType.MIC).functional)
        assertFalse(Key(KeyType.GLOBE).functional)
    }
}
