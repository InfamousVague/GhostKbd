package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Text shortcuts' rules (Shortcuts.kt; Matt, 2026-11-12: "I like … text shortcuts"): what counts as a shortcut
 * standing before the cursor, what may be one, what it types, and how the list is kept.
 */
class ShortcutsTest {
    private val list = listOf(Shortcuts.Entry("omw", "On my way!"), Shortcuts.Entry("@@", "matt@example.com"), Shortcuts.Entry(";sig", "Thanks,\\nMatt"))
    private fun found(before: String) = Shortcuts.find(list, before)?.shortcut

    @Test fun `a shortcut is found as a word of its own, whatever its capitals`() {
        assertEquals("omw", found("omw"))
        assertEquals("omw", found("see you soon, omw"))
        assertEquals("omw", found("first line\nomw"))
        assertEquals("omw", found("Omw"))
        assertEquals("omw", found("OMW"))
        assertEquals("@@", found("write to @@"))
        assertEquals(";sig", found("done. ;sig"))
    }

    @Test fun `it is not found inside a longer run, or with anything after it`() {
        assertNull(found("homw"))
        assertNull(found("omw."))
        assertNull(found("(omw"))
        assertNull(found("omw "))
        assertNull(found("omww"))
        assertNull(found("a@@"))
        assertNull(found(""))
        assertNull(found("no shortcut here"))
    }

    @Test fun `the run before the cursor ends at the last space or line break`() {
        assertEquals("omw", Shortcuts.token("so omw"))
        assertEquals("omw", Shortcuts.token("so\tomw"))
        assertEquals("omw", Shortcuts.token("so\nomw"))
        assertEquals("", Shortcuts.token("so "))
        assertEquals("so,omw", Shortcuts.token("so,omw"))
    }

    @Test fun `a run longer than a shortcut can be is never one, even where the reading starts inside it`() {
        // The service reads one more character than the longest shortcut: a run that fills the reading is too long.
        val long = "x".repeat(Shortcuts.MAX_SHORTCUT + 1)
        val all = listOf(Shortcuts.Entry("x".repeat(Shortcuts.MAX_SHORTCUT), "y"))
        assertNull(Shortcuts.find(all, long))
        assertNotNull(Shortcuts.find(all, " " + "x".repeat(Shortcuts.MAX_SHORTCUT)))
        assertNotNull(Shortcuts.find(all, "x".repeat(Shortcuts.MAX_SHORTCUT)))
    }

    @Test fun `what may be a shortcut, and why not, in words`() {
        fun why(shortcut: String, text: String = "something") = Shortcuts.refusal(shortcut, text, list)
        assertNull(why("brb"))
        assertNull(why(";addr"))
        assertNull(why("@@"))
        assertTrue(why("")!!.contains("Write the shortcut first"))
        assertTrue(why("on my")!!.contains("no space"))
        assertTrue(why("a")!!.contains("at least two"))
        assertTrue(why("x".repeat(Shortcuts.MAX_SHORTCUT + 1))!!.contains("at most ${Shortcuts.MAX_SHORTCUT}"))
        assertTrue(why("brb", "   ")!!.contains("stands for"))
        assertTrue(why("brb", "x".repeat(Shortcuts.MAX_TEXT + 1))!!.contains("too long"))
        assertNull(why("brb", "x".repeat(Shortcuts.MAX_TEXT)))
    }

    @Test fun `the list is full at the most, but a shortcut already in it can still be changed`() {
        val full = (1..Shortcuts.MAX).map { Shortcuts.Entry("s$it", "text $it") }
        assertTrue(Shortcuts.refusal("new", "text", full)!!.contains("${Shortcuts.MAX} shortcuts"))
        assertNull(Shortcuts.refusal("s7", "changed", full))
        assertNull(Shortcuts.refusal("S7", "changed", full))
    }

    @Test fun `adding keeps one entry a shortcut, the newest, in the shortcuts' own order`() {
        val more = Shortcuts.added(list, "brb", "Be right back")
        assertEquals(listOf(";sig", "@@", "brb", "omw"), more.map { it.shortcut })
        val changed = Shortcuts.added(more, "OMW", "On my way, 10 minutes")
        assertEquals(4, changed.size)
        assertEquals("On my way, 10 minutes", changed.single { it.shortcut.equals("omw", ignoreCase = true) }.text)
        assertEquals(listOf(";sig", "@@", "brb"), Shortcuts.removed(more, "Omw").map { it.shortcut })
    }

    @Test fun `what is typed is the text as written, with the date, the time, the clip and new lines read in it`() {
        fun typed(text: String, clip: String? = "a clip") = Shortcuts.expanded(text, "2026-11-12", "09:05", clip)
        assertEquals("On my way!", typed("On my way!"))
        assertEquals("Thanks,\nMatt", typed("Thanks,\\nMatt"))
        assertEquals("Notes for 2026-11-12 at 09:05", typed("Notes for {date} at {time}"))
        assertEquals("Review this: a clip", typed("Review this: {clipboard}"))
        // No clip to be had (none copied, incognito, the clipboard plugin off): nothing in its place.
        assertEquals("Review this: ", typed("Review this: {clipboard}", clip = null))
        // Anything else in braces is left as it is: code is typed as code.
        assertEquals("if (x) { return {y} }", typed("if (x) { return {y} }"))
        assertEquals("2026-11-12 2026-11-12", typed("{date} {date}"))
    }

    @Test fun `the list is written and read back whole, and a damaged entry is left out`() {
        val odd = list + Shortcuts.Entry("q\"\\", "quotes \" and \\ and\ttabs and üñí 🙂")
        assertEquals(odd, Shortcuts.parse(Shortcuts.write(odd)))
        assertEquals(emptyList<Shortcuts.Entry>(), Shortcuts.parse(null))
        assertEquals(emptyList<Shortcuts.Entry>(), Shortcuts.parse("not json"))
        assertEquals(emptyList<Shortcuts.Entry>(), Shortcuts.parse("[]"))
        // Entries that could never have been kept (one letter, a space in it, nothing to stand for) are not read.
        val damaged = """[{"s":"a","t":"x"},{"s":"two words","t":"x"},{"s":"ok","t":"fine"},{"s":"nothing","t":"  "}]"""
        assertEquals(listOf(Shortcuts.Entry("ok", "fine")), Shortcuts.parse(damaged))
    }

    @Test fun `the plugin is one of the keyboard's, off until added, and expands at a space once it is`() {
        assertTrue(Plugins.SHORTCUTS in Plugins.ALL)
        assertFalse(Plugins.SHORTCUTS in (KeyboardPrefs.DEFAULTS[KeyboardPrefs.PLUGINS] as String).split(','))
        assertEquals(true, KeyboardPrefs.DEFAULTS[KeyboardPrefs.SHORTCUTS_AUTO])
    }
}
