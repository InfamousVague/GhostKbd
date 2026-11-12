package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom row's two guests. The mic key stands where the full stop was (Matt, 2026-11-12: "when dictation is
 * enabled replace the period key next to the enter key"), on the letters page only, and still gives the full stop and
 * its punctuation on a long press. The emoji key stands where the { } key was (Matt, the same day, later: "Move the
 * emoji key to where the dev tools toggle is. And make it the same as the dictation where it's just the icon no
 * background. And move the code button up to where the emoji button used to be in the toolbar"). Neither takes
 * anything from the space bar, unless the row above the keys is hidden and the { } key has to stay on the board.
 * On the pages that have a comma key the emoji key stands next to it (Matt, the same day, later still: "additionally
 * include the emoji next to the comma key as I've become accustomed to using that"), a key's width off the bar.
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
            // With the emoji key beside their comma too: an emoji key does not make a page the letters.
            val keyed = Layouts.withEmoji(page, keepCode = false)
            assertSame(keyed, Layouts.withMic(keyed))
            assertTrue(bottom(keyed).any { it.type == KeyType.CHAR && it.text == "." })
        }
    }

    @Test fun `the emoji key takes the code key's place, and nothing from the bar`() {
        val plain = letters()
        val rows = Layouts.withEmoji(plain, keepCode = false)
        assertEquals(listOf(KeyType.MODE, KeyType.EMOJI, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(rows))
        assertEquals("?123", bottom(rows)[0].label)
        // No { } key is left on the board: it is a button in the row above the keys.
        assertTrue(bottom(rows).none { it.type == KeyType.MODE && it.page == Page.DEV })
        assertEquals(5f, bar(rows), 0f)
        assertEquals(plain.last().units, rows.last().units, 0f)
        assertEquals(1f, bottom(rows)[1].width, 0f)
        plain.dropLast(1).forEachIndexed { i, row -> assertSame(row, rows[i]) }
    }

    @Test fun `it is drawn as the mic key is, with no pill and not as a function key`() {
        val key = bottom(Layouts.withEmoji(letters(), keepCode = false))[1]
        assertFalse(key.pill)
        assertFalse(key.functional)
        assertFalse(Key(KeyType.MIC).functional)
        // A tap on it opens emoji: it types nothing, and has no long-press characters of its own.
        assertEquals("", key.text)
        assertTrue(key.alts.isEmpty())
    }

    @Test fun `with the mic key too, whichever is put in first`() {
        val want = listOf(KeyType.MODE, KeyType.EMOJI, KeyType.SPACE, KeyType.MIC, KeyType.ENTER)
        assertEquals(want, types(Layouts.withEmoji(Layouts.withMic(letters()), keepCode = false)))
        // The mic key knows the letters page by its { } key, or by the emoji key in that key's place.
        assertEquals(want, types(Layouts.withMic(Layouts.withEmoji(letters(), keepCode = false))))
        assertEquals(listOf(KeyType.MODE, KeyType.EMOJI, KeyType.GLOBE, KeyType.SPACE, KeyType.MIC, KeyType.ENTER), types(Layouts.withEmoji(Layouts.withMic(letters(globe = true)), keepCode = false)))
    }

    @Test fun `with no row above the keys the code key stays on the board, after the emoji key`() {
        val rows = Layouts.withEmoji(letters(), keepCode = true)
        assertEquals(listOf(KeyType.MODE, KeyType.EMOJI, KeyType.MODE, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(rows))
        assertEquals("{ }", bottom(rows)[2].label)
        assertEquals(Page.DEV, bottom(rows)[2].page)
        // Its width comes from the space bar, and the row is as wide as it was.
        assertEquals(4f, bar(rows), 0f)
        assertEquals(letters().last().units, rows.last().units, 0f)
        // With the globe key too the bar is at its least, three keys.
        val globe = Layouts.withEmoji(letters(globe = true), keepCode = true)
        assertEquals(listOf(KeyType.MODE, KeyType.EMOJI, KeyType.MODE, KeyType.GLOBE, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(globe))
        assertEquals(Layouts.LEAST_BAR, bar(globe), 0f)
    }

    @Test fun `on every board the bar keeps three keys, and the code keys can always be reached`() {
        for (spec in Layouts.SPECS) for (globe in listOf(false, true)) for (keep in listOf(false, true)) {
            val rows = Layouts.withEmoji(Layouts.withMic(Layouts.letters(spec, false, emptyMap(), globe)), keep)
            val name = "${spec.id} globe=$globe keep=$keep"
            assertTrue("$name: the bar is ${bar(rows)}", bar(rows) >= Layouts.LEAST_BAR)
            val emoji = bottom(rows).count { it.type == KeyType.EMOJI }
            val code = bottom(rows).count { it.type == KeyType.MODE && it.page == Page.DEV }
            assertTrue("$name: more than one emoji key", emoji <= 1)
            // Where the row cannot hold the { } button, the board always has the { } key, with or without emoji beside it.
            if (keep) assertEquals("$name: no { } key on the board", 1, code)
            // Where it can, the { } key has gone only if the emoji key took its place.
            if (!keep) assertEquals("$name", 1, emoji + code)
            assertEquals("$name: the row's width changed", Layouts.letters(spec, false, emptyMap(), globe).last().units, rows.last().units, 0.001f)
        }
    }

    @Test fun `on the pages with a comma key the emoji key stands next to the comma`() {
        val pages = mapOf("?123" to Layouts.symbols(), "=\\<" to Layouts.more(), "{ }" to Layouts.dev())
        for ((name, rows) in pages) for (keep in listOf(false, true)) {
            val keyed = Layouts.withEmoji(rows, keepCode = keep)
            assertEquals(name, listOf(KeyType.MODE, KeyType.CHAR, KeyType.EMOJI, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(keyed))
            assertEquals(name, "ABC", bottom(keyed)[0].label)
            assertEquals(name, ",", bottom(keyed)[1].text)
            assertEquals(name, ".", bottom(keyed)[4].text)
            // The same key as on the letters: its icon alone, nothing typed.
            val emoji = bottom(keyed)[2]
            assertFalse(name, emoji.pill)
            assertEquals(name, "", emoji.text)
            assertEquals(name, 1f, emoji.width, 0f)
            // Its width comes from the space bar, and the row is as wide as it was.
            assertEquals(name, 5f, bar(rows), 0f)
            assertEquals(name, 4f, bar(keyed), 0f)
            assertEquals(name, rows.last().units, keyed.last().units, 0f)
            rows.dropLast(1).forEachIndexed { i, row -> assertSame(row, keyed[i]) }
            // Asked twice, it is still one key.
            assertEquals(name, 1, bottom(Layouts.withEmoji(keyed, keepCode = keep)).count { it.type == KeyType.EMOJI })
        }
        // The code keys' comma still types one and goes back to the letters.
        assertEquals(Page.LETTERS, bottom(Layouts.withEmoji(Layouts.dev(), keepCode = false))[1].page)
    }

    @Test fun `a script's own comma has the emoji key beside it too`() {
        for (spec in Layouts.SPECS) for (rows in listOf(Layouts.symbols(spec), Layouts.more(spec))) {
            val keyed = bottom(Layouts.withEmoji(rows, keepCode = false))
            assertEquals(spec.id, KeyType.EMOJI, keyed[2].type)
            assertEquals(spec.id, bottom(rows)[1].text, keyed[1].text)
            assertTrue("${spec.id}: the bar is ${keyed.first { it.type == KeyType.SPACE }.width}", keyed.first { it.type == KeyType.SPACE }.width >= Layouts.LEAST_BAR)
        }
    }
}
