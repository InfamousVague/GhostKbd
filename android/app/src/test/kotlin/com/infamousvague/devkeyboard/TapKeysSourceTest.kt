package com.infamousvague.devkeyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A word keeps its taps when a mark of another page ends it (docs/versus.md: until 2026-11-12 the word before every
 * question mark typed from ?123 was corrected without them). The keyboard's view is Android's and out of a unit
 * test's reach, so what it promises is held against its source: where the letters' keys listen is kept as the
 * letters are left, answered with on any other page, forgotten at a new field, and the page is changed nowhere
 * that would get round it. The typing itself is tried on the emulator (tool/versus).
 */
class TapKeysSourceTest {
    private val main = listOf("android/app/src/main", "src/main", "../app/src/main").map(::File).first { it.exists() }

    /** A source with its comments taken out: what it does, not what it says. */
    private fun code(name: String) = File(main, "kotlin/com/infamousvague/devkeyboard/$name").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    private val view = code("KeyboardView.kt")

    /** The body of the function that begins [head], up to the next one. */
    private fun body(head: String): String {
        val from = view.indexOf(head)
        assertTrue(head, from >= 0)
        val next = Regex("\n    (?:private |override |internal )?(?:fun|val|var) ").find(view, from + head.length)?.range?.first ?: view.length
        return view.substring(from, next)
    }

    @Test fun `where the letters' keys listen is kept as the letters are left`() {
        val show = body("fun showPage(next: Page)")
        val kept = show.indexOf("if (page == Page.LETTERS && next != Page.LETTERS) lettersKeys = tapKeys()")
        // Before the page is changed: afterwards the keys laid out are the other page's.
        assertTrue(show, kept >= 0 && kept < show.indexOf("page = next"))
    }

    @Test fun `on another page the kept keys are the answer`() {
        val keys = body("fun tapKeys()")
        assertTrue(keys, keys.contains("if (page != Page.LETTERS) return lettersKeys"))
    }

    @Test fun `a new field forgets them`() {
        assertTrue(body("fun reset()").contains("lettersKeys = null"))
    }

    @Test fun `the page is changed in two places, and both know of the kept keys`() {
        // showPage and reset: a third would be a way to leave the letters with the taps let go.
        assertEquals(2, Regex("(?<![.\\w])page = ").findAll(view).count())
        // Nothing else writes the kept keys.
        assertEquals(2, Regex("lettersKeys = ").findAll(view).count())
    }

    @Test fun `autocorrect asks the view, whichever page is showing`() {
        val service = code("DevKeyboardService.kt")
        val taps = service.substring(service.indexOf("private fun tapsFor(word: String)"))
        assertTrue(taps.substringBefore("\n    }").contains("keyboard?.tapKeys() ?: return null"))
        // And does not look at the page itself before asking.
        assertTrue(!taps.substringBefore("\n    }").contains("Page."))
    }
}
