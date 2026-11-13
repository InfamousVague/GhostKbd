package com.infamousvague.devkeyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second look on the phone (DevKeyboardService.lookAgain, Autocorrect.again): the service is Android's and out
 * of a unit test's reach, so what it promises is held against its source, as TapKeysSourceTest holds KeyboardView's.
 * The word before is read again only in the field it was typed in, with nothing selected and the text before the
 * cursor exactly as it was left; it is let go wherever the cursor is moved by the keyboard's own keys; backspace
 * straight after takes the change back, and a word put back is not read again. The replay's Typist and the bench
 * call the same function, so that all three measure the same thing.
 */
class AgainSourceTest {
    private val main = listOf("android/app/src/main", "src/main", "../app/src/main").map(::File).first { it.exists() }
    private val root = main.parentFile.parentFile.parentFile.parentFile

    private fun code(file: File) = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    private val service = code(File(main, "kotlin/com/infamousvague/devkeyboard/DevKeyboardService.kt"))

    private fun body(head: String): String {
        val from = service.indexOf(head)
        assertTrue(head, from >= 0)
        val next = Regex("\n    (?:private |override |internal )?(?:fun|val|var) ").find(service, from + head.length)?.range?.first ?: service.length
        return service.substring(from, next)
    }

    @Test fun `the word before is read again only where it was left`() {
        val look = body("private fun lookAgain(")
        assertTrue(look.contains("b.field !== currentInputEditorInfo"))
        assertTrue(look.contains("ic.getSelectedText(0).isNullOrEmpty()"))
        assertTrue(look.contains("if (!before.endsWith(standing)) return null"))
        assertTrue(look.contains("autocorrect.again(b.typed, b.stood, stands, scorer, taps)"))
        // No place to weigh at (prediction off, a terminal): no second look.
        assertTrue(look.contains("}.getOrNull() ?: return null"))
    }

    @Test fun `it is kept only for a tapped word a space or a comma ended`() {
        val correct = body("private fun correctLastWord(")
        val keep = correct.indexOf("behind = Behind(word, stands, wordTouches.toList(), separator, currentInputEditorInfo)")
        assertTrue(keep >= 0)
        assertTrue(correct.contains("if (Autocorrect.AGAIN && taps != null && !swiped && (separator == \" \" || separator == \",\") && lower != wasPutBack)"))
        // Taken at the start, so any way out of the function lets it go; a space after a comma keeps it.
        val taken = correct.indexOf("val ahead = behind\n        behind = null")
        assertTrue(taken in 0 until correct.indexOf("autocorrectAllowed()"))
        assertTrue(correct.contains("if (word.isEmpty()) { if (separator == \" \" && ahead?.separator == \",\") behind = ahead; return false }"))
    }

    @Test fun `a change behind the cursor is one edit that backspace takes back`() {
        val correct = body("private fun correctLastWord(")
        val edit = correct.indexOf("editor.replaceBefore(was + word, put + stands + separator, Kind.CORRECTION, typedFirst = separator)")
        assertTrue(edit >= 0)
        val undo = correct.indexOf("lastCorrection = Triple(was + word, put + stands, separator)", edit)
        assertTrue(undo > edit)
        // Before this word's own fix would go in alone.
        assertTrue(edit < correct.indexOf("val fixed = found ?: return false"))
        val undoing = body("private fun undoCorrection(")
        assertTrue(undoing.contains("behind = null"))
        assertTrue(undoing.contains("putBack = typed.substringAfterLast(' ').lowercase(locale)"))
    }

    @Test fun `the cursor moved by the keyboard lets it go`() {
        // Wherever the bar's cut word is let go for a moved cursor, so is the word before.
        assertEquals(Regex("; cut = null").findAll(service).count(), Regex("; cut = null; behind = null").findAll(service).count())
        assertEquals(3, Regex("; cut = null; behind = null").findAll(service).count())
    }

    @Test fun `the replay and the bench call the same function`() {
        val typist = File(root, "tool/versus/replay/Replay.kt").readText()
        assertTrue(typist.contains("decoder.ac.again(b.typed, b.stood, stands, scorerAt(back), tapsFor(b.typed, b.touches))"))
        val bench = File(root, "tool/typing_bench/Bench.kt").readText()
        assertTrue(bench.contains("kb.ac.again(t.text, stood, next, kb.scorer(before), Taps(t.xs, t.ys, Geo.centres, Geo.W, SPACE))"))
    }
}
