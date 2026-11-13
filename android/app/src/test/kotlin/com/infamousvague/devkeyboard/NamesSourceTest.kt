package com.infamousvague.devkeyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A name typed in lower case gets its capital (docs/versus.md: on 2026-11-11 Gboard gave 134 of 191 names theirs and
 * Ghost Keyboard none). What the decoder does with a name is tried in TypingAccuracyTest. Where it is asked is the
 * service's doing, which is Android's and out of a unit test's reach, so that part is held against its source, as
 * TapKeysSourceTest holds the view: names are asked for only where the field capitalises sentences and the
 * keyboard's own capitals are on; every way a word reaches the field through autocorrect asks (a word ended, a word
 * put together again, the row); a capital is undone and taught as any fix is; and tool/versus's replay, which is what
 * measures all this on the JVM, asks in the same places. Whether the phone then does what these lines say is tried
 * on the emulator (tool/versus/device-check.sh).
 */
class NamesSourceTest {
    private val main = listOf("android/app/src/main", "src/main", "../app/src/main").map(::File).first { it.exists() }

    /** A source with its comments taken out: what it does, not what it says. */
    private fun code(file: File) = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    private val service = code(File(main, "kotlin/com/infamousvague/devkeyboard/DevKeyboardService.kt"))

    /** The body of the service's function that begins [head], up to the next one. */
    private fun body(head: String, source: String = service): String {
        val from = source.indexOf(head)
        assertTrue(head, from >= 0)
        val next = Regex("\n    (?:private |override |internal )?(?:fun|val|var|class) ").find(source, from + head.length)?.range?.first ?: source.length
        return source.substring(from, next)
    }

    @Test fun `names are asked for where the field capitalises sentences and the keyboard's capitals are on`() {
        val wanted = body("private fun namesWanted(): Boolean")
        // The setting, a script that has capitals, and never a terminal: each one alone says no.
        assertTrue(wanted, wanted.contains("if (!prefs.autoCaps || !language.spec.script.cased || terminalApp()) return false"))
        // A text field that asks for its sentences to be capitalised: nothing else says yes.
        assertTrue(wanted, wanted.contains("type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0"))
        // Two ways to say no and the one line that says yes.
        assertEquals(wanted, 3, Regex("\\breturn\\b").findAll(wanted).count())
    }

    @Test fun `a word that ends is corrected with names, and in no other way`() {
        val correct = body("private fun correctLastWord(ic: android.view.inputmethod.InputConnection, separator: String): Boolean")
        // Names where the field wants them, but for the one word whose fix the backspace has just taken back here.
        assertTrue(correct.contains("val found = autocorrect.correct(word, scorer, taps, namesWanted() && !takenBack)"))
        // The one place the service asks for a correction.
        assertEquals(1, Regex("autocorrect\\.correct\\(").findAll(service).count())
    }

    @Test fun `a capital the backspace took back is not given again to the same word typed again`() {
        // The undo keeps the typed word as a learned one, and a learned word is never given a capital; but incognito,
        // or in a field that asks keyboards not to learn, nothing is kept, and the capital came straight back at the
        // next space. The undo marks the word as it ended, whatever the fix was and whether or not it could be kept.
        val undo = body("private fun undoCorrection(ic: InputConnection, keepSeparator: Boolean): Boolean")
        val marks = undo.indexOf("ends.firstOrNull { it.typed == lower && it.field === currentInputEditorInfo }?.let {")
        assertTrue(undo, marks >= 0 && undo.indexOf("it.undone = true", marks) > marks)
        // It is not behind the test that keeps a word only where words may be kept.
        val learns = undo.indexOf("if (!incognito) { prefs.learn(typed, locale); autocorrect.setLearned(prefs.learnedWords) }")
        assertTrue(undo, learns in 0 until marks && !undo.substring(marks, undo.indexOf("it.undone = true", marks)).contains("incognito"))
        // The word that ended in this same place a moment ago, the same letters, its fix taken back: no names for it,
        // and the mark stays with the word as it ends again (its space deleted and typed once more).
        val correct = body("private fun correctLastWord(ic: android.view.inputmethod.InputConnection, separator: String): Boolean")
        assertTrue(correct.contains("val prior = ends.firstOrNull { it.field === currentInputEditorInfo && it.head == head && now - it.at < RETYPE_MS }"))
        assertTrue(correct.contains("val takenBack = prior != null && prior.undone && prior.typed == lower"))
        assertTrue(correct.contains("if (takenBack) ends.peekFirst()?.undone = true"))
        // The row says what the space will leave: it finds the same word the same way, and offers no capital for it.
        val taken = body("private fun capitalTakenBack(word: String, before: String): Boolean")
        assertTrue(taken, taken.contains("val head = before.dropLast(word.length).takeLast(HEAD_CHARS)"))
        assertTrue(taken, taken.contains("val prior = ends.firstOrNull { it.field === currentInputEditorInfo && it.head == head && now - it.at < RETYPE_MS } ?: return false"))
        assertTrue(taken, taken.contains("return prior.undone && prior.typed == word.lowercase(locale)"))
        assertTrue(correct.contains("val head = before.dropLast(word.length).takeLast(HEAD_CHARS)"))
    }

    @Test fun `a word put together again has its name's capital`() {
        val rejoined = body("private fun rejoined(ic: InputConnection, word: String, separator: String): Boolean")
        assertTrue(rejoined.contains("autocorrect.rejoin(c.typed, word, c.stood, scorer, taps)?.let { if (namesWanted()) autocorrect.named(it) else it } ?: return false"))
        assertEquals(1, Regex("autocorrect\\.rejoin\\(").findAll(service).count())
    }

    @Test fun `the row is asked as the space will be`() {
        val row = body("private fun refreshRow(before: String?, place: NextWordText.Place?)")
        assertTrue(row.contains("val names = namesWanted() && !(before != null && capitalTakenBack(word, before))"))
        // With next-word prediction and without it.
        assertTrue(row.contains("autocorrect.suggest(word, nextWords.timed { nextWords.at(predict).ready() }, taps, names) else autocorrect.suggest(word, null, taps, names)"))
        assertEquals(2, Regex("autocorrect\\.suggest\\(").findAll(service).count())
    }

    @Test fun `a capital is undone and taught as any fix is`() {
        // Every fix the space makes can be taken back by the next backspace, a capital among them: nothing between
        // the correction and this line asks what kind of fix it was.
        val correct = body("private fun correctLastWord(ic: android.view.inputmethod.InputConnection, separator: String): Boolean")
        val replace = "if (!editor.replaceBefore(word, fixed + separator, Kind.CORRECTION, typedFirst = separator)) return false"
        val made = correct.indexOf(replace)
        val kept = correct.indexOf("lastCorrection = Triple(word, fixed, separator)")
        assertTrue(correct, made >= 0 && kept > made)
        val between = correct.substring(made + replace.length, kept)
        assertTrue(between, !between.contains("return") && !between.contains("if ("))
        // Undoing puts back what was typed, in the letters and the capitals it was typed in, and keeps it as a word
        // of the person's own: the decoder never gives a kept word a capital (TypingAccuracyTest).
        val undo = body("private fun undoCorrection(ic: InputConnection, keepSeparator: Boolean): Boolean")
        assertTrue(undo.contains("editor.replaceBefore(tail, if (keepSeparator) typed + separator else typed, Kind.CORRECTION)"))
        assertTrue(undo.contains("if (!incognito) { prefs.learn(typed, locale); autocorrect.setLearned(prefs.learnedWords) }"))
        // A fix that only gave a capital taught the mistakes nothing: there is nothing of it to forget, and the count
        // of the word having stood as typed is not to go with it.
        assertTrue(undo.contains("if (mistakesOn() && !fixed.equals(typed, ignoreCase = true)) mistakes.forget(lower, fixed)"))
        // Nor is its undoing reported as a fix undone: a report counts a fix made by the letters alone
        // (ReportRules.word), so a capital was never one, and one undone would be a fix undone that was never made.
        assertTrue(undo.contains("if (reporting() && !fixed.equals(typed, ignoreCase = true)) Reports.undone(this, lower, fixed)"))
        assertEquals(1, Regex("Reports\\.undone\\(").findAll(service).count())
        val reports = code(File(main, "kotlin/com/infamousvague/devkeyboard/Reports.kt"))
        assertTrue(reports.contains("val a = typed.lowercase()") && reports.contains("val b = stands.lowercase()") && reports.contains("val fixed = a != b"))
        // A word given only its capital stood in the letters it was typed in.
        assertTrue(correct.contains("if ((found == null || found.equals(word, ignoreCase = true)) && mistakesOn()) mistakes.kept(lower)"))
    }

    @Test fun `a capital alone teaches the mistake memory nothing`() {
        // A fix left to stand is half a lesson, but not one that changed no letter. A listed name was passed over
        // anyway (it is remembered in lower case, and a word meant as itself is no lesson); the possessive of a name
        // is a word the lists mostly lack, so "linda's" was remembered as a mistake for "Linda's", which is what the
        // memory does with a word and its own letters in another case (the last lines here show that it would).
        val correct = body("private fun correctLastWord(ic: android.view.inputmethod.InputConnection, separator: String): Boolean")
        assertTrue(correct.contains("if (mistakesOn() && !fixed.equals(word, ignoreCase = true)) mistakes.learn(lower, meantAs(fixed, opens), Mistakes.STOOD)"))
        // The one lesson a word's end gives, and the only one in that function.
        assertEquals(1, Regex("mistakes\\.learn\\(").findAll(correct).count())
        val memory = MistakeMemory()
        memory.learn("linda's", "Linda's", 0.5f)
        assertTrue("what the service must not teach would be kept", memory.most(8).any { it.first == "linda's" && it.second == "Linda's" })
    }

    @Test fun `a listed name's capital is never what a correction teaches`() {
        // What a fix is remembered as keeps a capital only for a word no list has (a name of the person's own). Of
        // two words it is the first that is asked about: "Tom is" is remembered as "tom is", as it was before names.
        val meant = body("private fun meantAs(stands: String, opens: Boolean): String")
        assertTrue(meant, meant.contains("&& !autocorrect.isListed(lower.substringBefore(' '))) stands else lower"))
    }

    @Test fun `the replay on the JVM asks for names where the service does`() {
        val file = listOf("tool/versus/replay/Replay.kt", "../../tool/versus/replay/Replay.kt", "../tool/versus/replay/Replay.kt").map(::File).firstOrNull { it.exists() }
        // Only where the tools are there to read: a copy of the app's sources alone has no replay to hold.
        assumeTrue(file != null)
        val replay = code(file!!)
        // A field that capitalises sentences, at the app's defaults: names unless told otherwise.
        assertTrue(replay.contains("private val names: Boolean = true"))
        assertTrue(replay.contains("val fixed = decoder.ac.correct(word, scorer, tapsFor(word), names)"))
        assertEquals(1, Regex("\\.ac\\.correct\\(").findAll(replay).count())
        // Both ways a word is put together again: at the next word's end, and at a second space.
        assertEquals(2, Regex("decoder\\.ac\\.rejoin\\(").findAll(replay).count())
        assertEquals(2, Regex("decoder\\.ac\\.rejoin\\([^\n]*\\)\\?\\.let \\{ if \\(names\\) decoder\\.ac\\.named\\(it\\) else it \\}").findAll(replay).count())
    }
}
