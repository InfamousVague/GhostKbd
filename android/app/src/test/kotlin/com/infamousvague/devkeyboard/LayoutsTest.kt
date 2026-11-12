package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom row's guests. The mic key stands where the full stop was (Matt, 2026-11-12: "when dictation is enabled
 * replace the period key next to the enter key"), on the letters page only, and still gives the full stop and its
 * punctuation on a long press. The { } key is a button in the row above the keys (the same day: "move the code button
 * up to where the emoji button used to be in the toolbar"), and its place on the board is the emoji key's ("Move the
 * emoji key to where the dev tools toggle is"), as 0.16.2 has it. With Settings' Comma key on, that place is the
 * comma's and the emoji key stands beside it, a key's width off the space bar ("The comma key should be in the
 * original spot the code was in the emoji key should cut into the spacebar", then: "On second thought remove the
 * comma by default make it a setting users can toggle on"). With no row to hold the button, the { } key keeps its
 * place and the emoji key stands beside that.
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
            val keyed = Layouts.withEmoji(page)
            assertSame(keyed, Layouts.withMic(keyed))
            assertTrue(bottom(keyed).any { it.type == KeyType.CHAR && it.text == "." })
        }
    }

    @Test fun `by default the emoji key has the code key's place, and the bar its whole width`() {
        // The letters as 0.16.2 has them.
        val plain = letters()
        val rows = Layouts.withEmoji(plain, inPlace = true)
        assertEquals(listOf(KeyType.MODE, KeyType.EMOJI, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(rows))
        assertEquals("?123", bottom(rows)[0].label)
        assertEquals(".", bottom(rows)[3].text)
        assertEquals(5f, bar(rows), 0f)
        assertEquals(plain.last().units, rows.last().units, 0f)
        assertTrue(bottom(rows).none { it.type == KeyType.MODE && it.page == Page.DEV })
        plain.dropLast(1).forEachIndexed { i, row -> assertSame(row, rows[i]) }
        // With the mic key and the globe key, whichever is put in first.
        val spoken = listOf(KeyType.MODE, KeyType.EMOJI, KeyType.GLOBE, KeyType.SPACE, KeyType.MIC, KeyType.ENTER)
        assertEquals(spoken, types(Layouts.withEmoji(Layouts.withMic(letters(globe = true)), inPlace = true)))
        assertEquals(spoken, types(Layouts.withMic(Layouts.withEmoji(letters(globe = true), inPlace = true))))
        // The pages that have a comma key keep it, with the emoji key beside it, however the letters are.
        for (page in listOf(Layouts.symbols(), Layouts.more(), Layouts.dev())) {
            assertEquals(listOf(KeyType.MODE, KeyType.CHAR, KeyType.EMOJI, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(Layouts.withEmoji(page, inPlace = true)))
        }
        // The comma key of the setting comes first where it is on: then the emoji key is beside it, not in its place.
        assertEquals(
            listOf(KeyType.MODE, KeyType.CHAR, KeyType.EMOJI, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER),
            types(Layouts.withEmoji(Layouts.withComma(letters()), inPlace = false)),
        )
    }

    @Test fun `the comma takes the code key's place, and nothing from the bar`() {
        val plain = letters()
        val rows = Layouts.withComma(plain)
        assertEquals(listOf(KeyType.MODE, KeyType.CHAR, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(rows))
        assertEquals("?123", bottom(rows)[0].label)
        assertEquals(",", bottom(rows)[1].text)
        // The same key as on the ?123 page.
        assertEquals(bottom(Layouts.symbols())[1].text, bottom(rows)[1].text)
        assertEquals(bottom(Layouts.symbols())[1].alts, bottom(rows)[1].alts)
        // No { } key is left on the board: it is a button in the row above the keys.
        assertTrue(bottom(rows).none { it.type == KeyType.MODE && it.page == Page.DEV })
        assertEquals(5f, bar(rows), 0f)
        assertEquals(plain.last().units, rows.last().units, 0f)
        plain.dropLast(1).forEachIndexed { i, row -> assertSame(row, rows[i]) }
        // A script with a comma of its own has that one, as its ?123 page does.
        val urdu = Layouts.spec("urdu")!!
        assertEquals(bottom(Layouts.symbols(urdu))[1].text, bottom(Layouts.withComma(Layouts.letters(urdu, false, emptyMap(), false), urdu))[1].text)
        // The other pages have their comma already, and come back as they are.
        for (page in listOf(Layouts.symbols(), Layouts.more(), Layouts.dev())) assertSame(page, Layouts.withComma(page))
    }

    @Test fun `the emoji key stands next to the comma, and its width comes from the space bar`() {
        val plain = letters()
        val rows = Layouts.withEmoji(Layouts.withComma(plain))
        assertEquals(listOf(KeyType.MODE, KeyType.CHAR, KeyType.EMOJI, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(rows))
        assertEquals(",", bottom(rows)[1].text)
        assertEquals(".", bottom(rows)[4].text)
        assertEquals(1f, bottom(rows)[2].width, 0f)
        assertEquals(5f, bar(plain), 0f)
        assertEquals(4f, bar(rows), 0f)
        assertEquals(plain.last().units, rows.last().units, 0f)
        plain.dropLast(1).forEachIndexed { i, row -> assertSame(row, rows[i]) }
        // Asked twice, it is still one key.
        assertEquals(1, bottom(Layouts.withEmoji(rows)).count { it.type == KeyType.EMOJI })
    }

    @Test fun `it is drawn as the mic key is, with no pill and not as a function key`() {
        val key = bottom(Layouts.withEmoji(Layouts.withComma(letters())))[2]
        assertEquals(KeyType.EMOJI, key.type)
        assertFalse(key.pill)
        assertFalse(key.functional)
        assertFalse(Key(KeyType.MIC).functional)
        // A tap on it opens emoji: it types nothing, and has no long-press characters of its own.
        assertEquals("", key.text)
        assertTrue(key.alts.isEmpty())
    }

    @Test fun `with the mic key too, whichever is put in first`() {
        val want = listOf(KeyType.MODE, KeyType.CHAR, KeyType.EMOJI, KeyType.SPACE, KeyType.MIC, KeyType.ENTER)
        assertEquals(want, types(Layouts.withEmoji(Layouts.withComma(Layouts.withMic(letters())))))
        // The mic key knows the letters page by its ?123 key, whatever stands beside it.
        assertEquals(want, types(Layouts.withMic(Layouts.withEmoji(Layouts.withComma(letters())))))
        assertEquals(want, types(Layouts.withEmoji(Layouts.withMic(Layouts.withComma(letters())))))
        // The mic took the full stop's key, never the comma's.
        assertEquals(",", bottom(Layouts.withMic(Layouts.withEmoji(Layouts.withComma(letters()))))[1].text)
        assertEquals(
            listOf(KeyType.MODE, KeyType.CHAR, KeyType.EMOJI, KeyType.GLOBE, KeyType.SPACE, KeyType.MIC, KeyType.ENTER),
            types(Layouts.withEmoji(Layouts.withComma(Layouts.withMic(letters(globe = true))))),
        )
    }

    @Test fun `with no row above the keys the code key keeps its place, and the emoji key stands beside it`() {
        // As the board was from 0.12.1 to 0.15.0.
        val rows = Layouts.withEmoji(letters())
        assertEquals(listOf(KeyType.MODE, KeyType.MODE, KeyType.EMOJI, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(rows))
        assertEquals("{ }", bottom(rows)[1].label)
        assertEquals(Page.DEV, bottom(rows)[1].page)
        assertEquals(4f, bar(rows), 0f)
        assertEquals(letters().last().units, rows.last().units, 0f)
        // With the globe key too the bar is at its least, three keys.
        val globe = Layouts.withEmoji(letters(globe = true))
        assertEquals(listOf(KeyType.MODE, KeyType.MODE, KeyType.EMOJI, KeyType.GLOBE, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(globe))
        assertEquals(Layouts.LEAST_BAR, bar(globe), 0f)
    }

    @Test fun `on every board the bar keeps three keys, and the code keys can always be reached`() {
        // The three ways the letters can be: the { } key in the row (emoji in its place), that with the comma key
        // on, and no row at all.
        for (spec in Layouts.SPECS) for (globe in listOf(false, true)) for (way in listOf("row", "comma", "none")) {
            val plain = Layouts.letters(spec, false, emptyMap(), globe)
            val spoken = Layouts.withMic(plain)
            val rows = when (way) {
                "row" -> Layouts.withEmoji(spoken, inPlace = true)
                "comma" -> Layouts.withEmoji(Layouts.withComma(spoken, spec))
                else -> Layouts.withEmoji(spoken)
            }
            val name = "${spec.id} globe=$globe $way"
            assertTrue("$name: the bar is ${bar(rows)}", bar(rows) >= Layouts.LEAST_BAR)
            assertEquals("$name: emoji keys", 1, bottom(rows).count { it.type == KeyType.EMOJI })
            // The { } key is on the board exactly where there is no row to hold its button.
            assertEquals("$name: { } keys on the board", if (way == "none") 1 else 0, bottom(rows).count { it.type == KeyType.MODE && it.page == Page.DEV })
            // The emoji key is next to ?123 by default; with a comma or the { } key there, it is the key after.
            assertEquals("$name", KeyType.EMOJI, bottom(rows)[if (way == "row") 1 else 2].type)
            // Only the default leaves the bar its whole width.
            assertEquals("$name: the bar", bar(plain) - (if (way == "row") 0f else 1f), bar(rows), 0.001f)
            assertEquals("$name: the row's width changed", plain.last().units, rows.last().units, 0.001f)
        }
    }

    @Test fun `on the other pages the emoji key stands next to their comma too`() {
        val pages = mapOf("?123" to Layouts.symbols(), "=\\<" to Layouts.more(), "{ }" to Layouts.dev())
        for ((name, rows) in pages) {
            val keyed = Layouts.withEmoji(rows)
            assertEquals(name, listOf(KeyType.MODE, KeyType.CHAR, KeyType.EMOJI, KeyType.SPACE, KeyType.CHAR, KeyType.ENTER), types(keyed))
            assertEquals(name, "ABC", bottom(keyed)[0].label)
            assertEquals(name, ",", bottom(keyed)[1].text)
            assertEquals(name, ".", bottom(keyed)[4].text)
            assertFalse(name, bottom(keyed)[2].pill)
            assertEquals(name, 5f, bar(rows), 0f)
            assertEquals(name, 4f, bar(keyed), 0f)
            assertEquals(name, rows.last().units, keyed.last().units, 0f)
            rows.dropLast(1).forEachIndexed { i, row -> assertSame(row, keyed[i]) }
            assertEquals(name, 1, bottom(Layouts.withEmoji(keyed)).count { it.type == KeyType.EMOJI })
        }
        // The code keys' comma still types one and goes back to the letters.
        assertEquals(Page.LETTERS, bottom(Layouts.withEmoji(Layouts.dev()))[1].page)
        // A script's own comma has the emoji key beside it as well.
        for (spec in Layouts.SPECS) for (rows in listOf(Layouts.symbols(spec), Layouts.more(spec))) {
            val keyed = bottom(Layouts.withEmoji(rows))
            assertEquals(spec.id, KeyType.EMOJI, keyed[2].type)
            assertEquals(spec.id, bottom(rows)[1].text, keyed[1].text)
        }
    }
}
