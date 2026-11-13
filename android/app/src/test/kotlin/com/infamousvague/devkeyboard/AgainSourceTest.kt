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
        // A cursor moved by touch to other words that end the same way: the text before them must be what it was.
        assertTrue(look.contains("if (head.takeLast(HEAD_CHARS) != b.head) return null"))
        assertTrue(look.contains("autocorrect.again(b.typed, b.stood, stands, scorer, taps)"))
        // No place to weigh at (prediction off, a terminal): no second look.
        assertTrue(look.contains("}.getOrNull() ?: return null"))
    }

    @Test fun `it is kept only for a tapped word a space or a comma ended`() {
        val correct = body("private fun correctLastWord(")
        val keep = correct.indexOf("behind = Behind(word, stands, wordTouches.toList(), separator, head, currentInputEditorInfo)")
        assertTrue(keep >= 0)
        assertTrue(correct.contains("if (Autocorrect.AGAIN && taps != null && !swiped && (separator == \" \" || separator == \",\") && lower != wasPutBack)"))
        // Taken at the start, so any way out of the function lets it go; a space after a comma keeps it.
        val taken = correct.indexOf("val ahead = behind\n        behind = null")
        assertTrue(taken in 0 until correct.indexOf("autocorrectAllowed()"))
        // (The branch for no word also keeps the bar's tap for a word cut in two, DevKeyboardService.Cut.)
        val empty = correct.indexOf("if (word.isEmpty()) {")
        val keepsAhead = correct.indexOf("if (separator == \" \" && ahead?.separator == \",\") behind = ahead", empty)
        assertTrue(empty >= 0 && keepsAhead > empty && keepsAhead < correct.indexOf("return false", empty))
    }

    @Test fun `a change behind the cursor is one edit that backspace takes back`() {
        val correct = body("private fun correctLastWord(")
        val edit = correct.indexOf("editor.replaceBefore(was + word, put + stands + separator, Kind.CORRECTION, typedFirst = separator)")
        assertTrue(edit >= 0)
        val undo = correct.indexOf("val change = Triple(was + word, put + stands, separator)", edit)
        assertTrue(undo > edit)
        assertTrue(correct.indexOf("lastCorrection = change", undo) > undo)
        // Before this word's own fix would go in alone.
        assertTrue(edit < correct.indexOf("val fixed = found ?: return false"))
        val undoing = body("private fun undoCorrection(")
        assertTrue(undoing.contains("behind = null"))
        assertTrue(undoing.contains("putBack = typed.substringAfterLast(' ').lowercase(locale)"))
        // The accuracy log's word (the word after) is undone only where its own space had fixed it.
        assertTrue(undoing.contains("if (accuracyOn() && (wasBehind == null || wasBehind.second != null)) AccuracyLog.settle(this, \"undone\")"))
        assertTrue(undoing.indexOf("if (wasBehind != null) {") < undoing.indexOf("learner.word(typed.length)"))
    }

    @Test fun `a word the second look changed and typed again as it was is kept`() {
        // The review of 2026-11-13: "we ate going " became "we are going ", the person went back and typed "ate"
        // again, and the next word changed it again. The change is written into ends, so that "ate" typed over
        // "are" meets the keep-and-learn branch (prior.typed == lower, prior.out is not).
        val correct = body("private fun correctLastWord(")
        val edit = correct.indexOf("editor.replaceBefore(was + word, put + stands + separator")
        val noted = correct.indexOf("noteEnd(b.typed.lowercase(locale), newer, headBefore, now)", edit)
        assertTrue(noted > edit)
        assertTrue(correct.indexOf("ends.firstOrNull { it.field === currentInputEditorInfo && it.head == headBefore }?.let { ends.remove(it) }", edit) in edit until noted)
        // The head it is found by is the one the word had as it ended.
        assertTrue(correct.contains("val headBefore = before.dropLast(was.length + word.length).takeLast(HEAD_CHARS)"))
        assertTrue(correct.contains("val head = before.dropLast(word.length).takeLast(HEAD_CHARS)"))
        assertTrue(correct.contains("if (prior != null && prior.typed == lower && !prior.out.equals(lower, ignoreCase = true)) {"))
        // The word after stands after the changed word now: its record and the one kept to read it again say so.
        assertTrue(correct.contains("noteEnd(lower, stands, headNow, now)"))
        assertTrue(correct.contains("behind = behind?.let { Behind(it.typed, it.stood, it.touches, it.separator, headNow, it.field) }"))
    }

    @Test fun `never at enter`() {
        // enter calls correctLastWord with no separator and then lets go of lastCorrection: nothing could undo it.
        val correct = body("private fun correctLastWord(")
        assertTrue(correct.contains("val again = if (separator.isEmpty()) null else ahead?.let"))
        assertTrue(body("private fun enter(").contains("correctLastWord(ic, \"\")"))
    }

    @Test fun `the cursor moved by the keyboard lets it go`() {
        // Wherever the bar's cut word is let go for a moved cursor, so is the word before.
        assertEquals(Regex("; cut = null").findAll(service).count(), Regex("; cut = null; behind = null").findAll(service).count())
        assertEquals(3, Regex("; cut = null; behind = null").findAll(service).count())
        // And where the app reports the cursor moved by something other than the keyboard (a tap in the text).
        assertTrue(body("private fun selectionMoved(").contains("if (editor.onSelection(newSelStart, newSelEnd)) { toolsHeld = false; behind = null }"))
    }

    @Test fun `the replay and the bench call the same function`() {
        val typist = File(root, "tool/versus/replay/Replay.kt").readText()
        // Raw taps, as the service's tapsOf gives them: no lean on them (ThumbLean), which only the word being typed is read with.
        assertTrue(typist.contains("decoder.ac.again(b.typed, b.stood, stands, scorerAt(back), tapsFor(b.typed, b.touches, leaned = false))"))
        val bench = File(root, "tool/typing_bench/Bench.kt").readText()
        assertTrue(bench.contains("kb.ac.again(t.text, stood, next, kb.scorer(before), Taps(t.xs, t.ys, Geo.centres, Geo.W, SPACE))"))
    }
}
