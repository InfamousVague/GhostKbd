package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pretend text field: a string and a selection, doing what Android's InputConnection calls do, with switches for
 * the ways real apps misbehave. Selection changes are queued as reports the test hands to the editor, as Android
 * hands them to a keyboard a moment after each edit.
 */
class FakeField(var text: String = "", var selStart: Int = text.length, var selEnd: Int = selStart) : FieldPort {
    /** Reads come back null: a field that cannot be read. */
    var nullReads = false

    /** Reads carry no position: an app with its own InputConnection. */
    var noOffset = false

    /** Reads give at most this many characters either side. */
    var truncate = Int.MAX_VALUE

    /** setSelection is ignored. */
    var ignoreSelect = false

    /** deleteSurroundingText is ignored. */
    var ignoreDelete = false

    /** Selection changes are not reported at all. */
    var mute = false

    /** The app rewrites the whole text after every commit (a formatter). */
    var rewrite: ((String) -> String)? = null

    /** A composing region left behind by someone: commitText replaces it instead of the selection. */
    var composing: Pair<Int, Int>? = null

    /** The app's edit-menu undo does nothing (Flutter). */
    var menuDeaf = false

    /** What each read costs on the test's clock. */
    var readCost = 0L
    var tick: (Long) -> Unit = {}

    val reports = ArrayList<Pair<Int, Int>>()
    val calls = ArrayList<String>()
    var reads = 0
    var appUndos = 0
    var keyUndos = 0

    /** What the app's own undo would restore, newest last. */
    val appHistory = ArrayList<String>()
    private var batch = 0

    private fun report() {
        if (batch > 0 || mute) return
        if (reports.lastOrNull() != Pair(selStart, selEnd)) reports += Pair(selStart, selEnd)
    }

    override fun read(before: Int, after: Int): Reading? {
        reads++
        tick(readCost)
        if (nullReads) return null
        val b = text.substring(maxOf(0, selStart - minOf(before, truncate)), selStart)
        val a = text.substring(selEnd, minOf(text.length, selEnd + minOf(after, truncate)))
        return Reading(b, text.substring(selStart, selEnd), a, if (noOffset) -1 else selStart, if (noOffset) -1 else selEnd)
    }

    override fun textBefore(n: Int): String? = if (nullReads) null else text.substring(maxOf(0, selStart - n), selStart)

    override fun selectedText(): String? = if (nullReads || selStart == selEnd) null else text.substring(selStart, selEnd)

    override fun commit(text: String) {
        calls += "commit(${text.length})"
        val (from, to) = composing ?: Pair(selStart, selEnd)
        composing = null
        this.text = this.text.substring(0, from) + text + this.text.substring(to)
        selStart = from + text.length
        selEnd = selStart
        rewrite?.let { this.text = it(this.text); selStart = selStart.coerceAtMost(this.text.length); selEnd = selStart }
        report()
    }

    override fun delete(before: Int, after: Int) {
        calls += "delete($before,$after)"
        if (ignoreDelete) return
        val b = minOf(before, selStart)
        val a = minOf(after, text.length - selEnd)
        text = text.substring(0, selStart - b) + text.substring(selStart, selEnd) + text.substring(selEnd + a)
        selStart -= b
        selEnd -= b
        report()
    }

    override fun select(start: Int, end: Int) {
        calls += "select($start,$end)"
        if (ignoreSelect) return
        selStart = minOf(start, end).coerceIn(0, text.length)
        selEnd = maxOf(start, end).coerceIn(0, text.length)
        report()
    }

    override fun beginBatch() { batch++ }

    override fun endBatch() { batch--; report() }

    override fun finishComposing() { calls += "finishComposing"; composing = null }

    override fun nudge(delta: Int) {
        calls += "nudge($delta)"
        selStart = (selEnd + delta).coerceIn(0, text.length)
        selEnd = selStart
        report()
    }

    override fun appUndo(redo: Boolean): Boolean {
        appUndos++
        if (!menuDeaf && !redo && appHistory.isNotEmpty()) { text = appHistory.removeAt(appHistory.size - 1); selStart = text.length; selEnd = selStart; report() }
        return true
    }

    override fun keyUndo(redo: Boolean) {
        keyUndos++
        if (!redo && appHistory.isNotEmpty()) { text = appHistory.removeAt(appHistory.size - 1); selStart = text.length; selEnd = selStart; report() }
    }

    /** Something other than the keyboard changes the field: the app, a paste from its menu, another keyboard. */
    fun outside(text: String, sel: Int = text.length) {
        this.text = text
        selStart = sel
        selEnd = sel
        report()
    }

    /** A tap that moves the cursor, or drags a selection. */
    fun tap(start: Int, end: Int = start) {
        selStart = start
        selEnd = end
        report()
    }

    /** The Enter key reaching the app: a new line, with [extra] after it where the app adds a bullet. */
    fun pressEnter(extra: String = "") {
        text = text.substring(0, selStart) + "\n" + extra + text.substring(selEnd)
        selStart += 1 + extra.length
        selEnd = selStart
        report()
    }

    /** The text with the selection marked: "ab|cd" or "a[bc]d". */
    fun show(): String = if (selStart == selEnd) text.substring(0, selStart) + "|" + text.substring(selStart)
    else text.substring(0, selStart) + "[" + text.substring(selStart, selEnd) + "]" + text.substring(selEnd)
}

/** A field, an editor on it, a clock and the two timers, for one test. */
class Rig(text: String = "", sel: Int = text.length, recordable: Boolean = true, hadText: Boolean = text.isNotEmpty()) {
    var now = 10_000L
    val field = FakeField(text, sel)
    var settleAt = -1L
    var auditAt = -1L
    val errors = ArrayList<String>()
    var changes = 0
    private val scheduler = object : Scheduler {
        override fun settleIn(ms: Long) { settleAt = now + ms }
        override fun auditIn(ms: Long) { auditAt = now + ms }
        override fun cancel() { settleAt = -1; auditAt = -1 }
    }
    val editor = FieldEditor(field, { now }, scheduler, { m, t -> errors += "$m: $t" }, { changes++ }, { s -> Regex("\\X").findAll(s).lastOrNull()?.value?.length ?: 1 })

    init {
        field.tick = { now += it }
        editor.attach(recordable, hadText, sel, sel)
    }

    /** Hands the field's selection reports to the editor, as Android would. */
    fun pump() {
        val waiting = ArrayList(field.reports)
        field.reports.clear()
        for ((s, e) in waiting) editor.onSelection(s, e)
    }

    /** Time passes: the timers that fall due run. */
    fun pass(ms: Long) {
        now += ms
        if (settleAt in 0..now) { settleAt = -1; editor.settle() }
        if (auditAt in 0..now) { auditAt = -1; editor.audit() }
        pump()
    }

    /** Types [s] a key at a time, 80 ms apart, the reports arriving between keys. */
    fun keys(s: String) {
        var i = 0
        while (i < s.length) {
            val n = Character.charCount(s.codePointAt(i))
            editor.type(s.substring(i, i + n))
            pump()
            now += 80
            i += n
        }
    }

    fun backspace(times: Int = 1) = repeat(times) { assertTrue(editor.backspace()); pump(); now += 60 }

    fun undo(): FieldEditor.Outcome = editor.undo().also { pump() }

    fun redo(): FieldEditor.Outcome = editor.redo().also { pump() }

    fun enter(extra: String = "", late: Boolean = false) {
        var pressed = false
        editor.enter { if (!late) { field.pressEnter(extra); pressed = true } }
        pump()
        if (late) { now += 100; field.pressEnter(extra); pump() }
        pass(300)
        assertTrue(pressed || late)
    }

    val steps get() = editor.history.undo.size
    val show get() = field.show()
}

/**
 * The Undo and redo history, off the device. Run with tool/undo-selfcheck.sh, or with
 * `cd android && ./gradlew :app:testDebugUnitTest --tests '*FieldEditorTest*'` once Flutter has built the app once.
 */
class FieldEditorTest {
    private val done = FieldEditor.Outcome.DONE
    private val asked = FieldEditor.Outcome.ASKED_APP
    private val nothing = FieldEditor.Outcome.NOTHING

    // --- grouping -----------------------------------------------------------------------------------------------------

    @Test fun wordsAreSteps() {
        val r = Rig()
        r.keys("hello world. Next one")
        assertEquals(4, r.steps)
        assertEquals(listOf("hello ", "world. ", "Next ", "one"), r.editor.history.undo.map { it.inserted.toString() })
        // One read per word, none per letter.
        assertEquals(4, r.field.reads)
    }

    @Test fun undoBackToEmptyAndRedoToTheEnd() {
        val r = Rig()
        r.keys("The cat sat. It was fat.")
        val seen = ArrayList<String>()
        while (r.steps > 0) { assertEquals(done, r.undo()); seen += r.show }
        assertEquals(listOf("The cat sat. It was |", "The cat sat. It |", "The cat sat. |", "The cat |", "The |", "|"), seen)
        assertEquals(nothing, r.undo())
        assertFalse(r.editor.canUndo)
        seen.clear()
        while (r.editor.history.redo.isNotEmpty()) { assertEquals(done, r.redo()); seen += r.show }
        assertEquals(listOf("The |", "The cat |", "The cat sat. |", "The cat sat. It |", "The cat sat. It was |", "The cat sat. It was fat.|"), seen)
        assertEquals(nothing, r.redo())
        assertFalse(r.editor.canRedo)
        assertTrue(r.errors.isEmpty())
    }

    @Test fun aPauseStartsANewStep() {
        val r = Rig()
        r.keys("hel")
        r.now += 4_000
        r.keys("lo")
        assertEquals(2, r.steps)
        r.undo()
        assertEquals("hel|", r.show)
    }

    @Test fun aLoneSpaceJoinsTheNextWord() {
        val r = Rig("abc")
        r.keys(" def ")
        assertEquals(1, r.steps)
        r.undo()
        assertEquals("abc|", r.show)
    }

    @Test fun newEditClearsRedo() {
        val r = Rig()
        r.keys("one two three ")
        r.undo(); r.undo()
        assertEquals("one |", r.show)
        assertTrue(r.editor.canRedo)
        r.keys("x")
        assertFalse(r.editor.canRedo)
        assertEquals(nothing, r.redo())
        assertEquals("one x|", r.show)
        r.undo()
        assertEquals("one |", r.show)
        assertEquals(done, r.redo())
        assertEquals("one x|", r.show)
    }

    // --- backspace ----------------------------------------------------------------------------------------------------

    @Test fun backspaceShrinksTheWordBeingTyped() {
        val r = Rig()
        r.keys("hello wrl")
        r.backspace(2)
        r.keys("orld")
        assertEquals("hello world|", r.show)
        assertEquals(2, r.steps)
        r.undo()
        assertEquals("hello |", r.show)
        r.redo()
        assertEquals("hello world|", r.show)
    }

    @Test fun heldBackspaceIsOneStep() {
        val r = Rig()
        r.keys("hello world ")
        r.now += 5_000
        r.backspace(9)
        assertEquals("hel|", r.show)
        assertEquals(3, r.steps)
        assertEquals(done, r.undo())
        assertEquals("hello world |", r.show)
        assertEquals(done, r.redo())
        assertEquals("hel|", r.show)
    }

    @Test fun backspaceInOldTextUndoes() {
        val r = Rig("already here", hadText = true)
        r.backspace(4)
        assertEquals("already |", r.show)
        assertEquals(done, r.undo())
        assertEquals("already here|", r.show)
        // Nothing of the keyboard's is left, and it has just undone: the arrow is dim rather than asking the app.
        assertFalse(r.editor.canUndo)
    }

    @Test fun backspaceAtTheStartIsLeftToTheKeyPress() {
        val r = Rig("abc", sel = 0)
        assertFalse(r.editor.backspace())
        assertEquals("|abc", r.show)
    }

    @Test fun wordDeleteAndSelectionDelete() {
        val r = Rig("one two three")
        r.editor.deleteBefore("three"); r.pump()
        r.editor.deleteBefore("two "); r.pump()
        assertEquals("one |", r.show)
        assertEquals(1, r.steps)
        r.undo()
        assertEquals("one two three|", r.show)
        r.field.tap(0, 4); r.pump()
        r.editor.deleteSelection(); r.pump()
        assertEquals("|two three", r.show)
        r.undo()
        assertEquals("[one ]two three", r.show)
    }

    // --- surrogate pairs, emoji, marks, right-to-left -------------------------------------------------------------------

    @Test fun surrogatePairsRoundTrip() {
        val r = Rig()
        r.keys("a😀b 𝒳𝒴 ")
        assertEquals("a😀b 𝒳𝒴 |", r.show)
        r.undo()
        assertEquals("a😀b |", r.show)
        r.redo()
        assertEquals("a😀b 𝒳𝒴 |", r.show)
        r.now += 5_000
        r.backspace(2)
        assertEquals("a😀b 𝒳|", r.show)
        r.undo()
        assertEquals("a😀b 𝒳𝒴 |", r.show)
    }

    @Test fun wholeEmojiPerBackspace() {
        for (emoji in listOf("👨‍👩‍👧", "🇬🇧", "1️⃣", "👍🏽")) {
            val r = Rig("x")
            r.editor.insert(emoji, Kind.EMOJI); r.pump()
            r.now += 5_000
            r.backspace()
            assertEquals("x|", r.show)
            assertEquals(done, r.undo())
            assertEquals("x$emoji|", r.show)
            assertEquals(done, r.undo())
            assertEquals("x|", r.show)
            assertEquals(done, r.redo())
            assertEquals("x$emoji|", r.show)
        }
    }

    @Test fun combiningMarksAndCrLf() {
        val r = Rig("line\r\n")
        r.keys("café ")
        r.now += 5_000
        r.backspace(2)
        assertEquals("line\r\ncafe|", r.show)
        r.undo()
        assertEquals("line\r\ncafé |", r.show)
        r.undo()
        assertEquals("line\r\n|", r.show)
        r.redo()
        assertEquals("line\r\ncafé |", r.show)
    }

    @Test fun rightToLeftIsLeftInLogicalOrder() {
        val text = "שלום ‏עולם‏ مرحبا "
        val r = Rig()
        r.keys(text)
        assertEquals(text, r.field.text)
        val snaps = ArrayList<String>()
        while (r.steps > 0) { snaps += r.field.text; r.undo() }
        assertEquals("", r.field.text)
        for (want in snaps.reversed()) { r.redo(); assertEquals(want, r.field.text) }
    }

    // --- selections and cursor ----------------------------------------------------------------------------------------

    @Test fun selectionReplacedByTypingComesBackSelected() {
        val r = Rig("say hello there")
        r.field.tap(4, 9); r.pump()
        r.keys("bye")
        assertEquals("say bye| there", r.show)
        assertEquals(done, r.undo())
        assertEquals("say [hello] there", r.show)
        assertEquals(done, r.redo())
        assertEquals("say bye| there", r.show)
    }

    @Test fun undoPutsTheCursorBackAfterItMoved() {
        val r = Rig()
        r.keys("alpha beta ")
        r.field.tap(2); r.pump()
        assertEquals(done, r.undo())
        assertEquals("alpha |", r.show)
        assertEquals(done, r.undo())
        assertEquals("|", r.show)
    }

    @Test fun typingInTheMiddleUndoesThere() {
        val r = Rig()
        r.keys("one three ")
        r.field.tap(4); r.pump()
        r.keys("two ")
        assertEquals("one two |three ", r.show)
        r.field.tap(r.field.text.length); r.pump()
        assertEquals(done, r.undo())
        assertEquals("one |three ", r.show)
        assertEquals(done, r.redo())
        assertEquals("one two |three ", r.show)
    }

    // --- pairs, snippets, markdown ------------------------------------------------------------------------------------

    @Test fun pairsAndSnippetsUndoInPlace() {
        val r = Rig("f")
        r.editor.type("()", caretBack = 1); r.pump()
        assertEquals("f(|)", r.show)
        assertEquals(done, r.undo())
        assertEquals("f|", r.show)
        assertEquals(done, r.redo())
        assertEquals("f(|)", r.show)
        r.editor.insert("[[]]", Kind.SNIPPET, caretBack = 2); r.pump()
        assertEquals("f([[|]])", r.show)
        r.undo()
        assertEquals("f(|)", r.show)
    }

    @Test fun pairsUndoWhereTheFieldGivesNoPositions() {
        val r = Rig("f")
        r.field.noOffset = true
        r.editor.type("{}", caretBack = 1); r.pump()
        assertEquals("f{|}", r.show)
        assertEquals(done, r.undo())
        assertEquals("f|", r.show)
        assertEquals(done, r.redo())
        // With no positions the cursor is left at the end, and undo still finds the pair from there.
        assertEquals("f{}|", r.show)
        assertEquals(done, r.undo())
        assertEquals("f|", r.show)
    }

    @Test fun wrapLinkAndLinePrefix() {
        val r = Rig("make this bold\nsecond line")
        r.field.tap(5, 9); r.pump()
        r.editor.replaceSelection(Kind.WRAP) { Pair("**$it**", 0) }; r.pump()
        assertEquals("make **this**| bold\nsecond line", r.show)
        r.undo()
        assertEquals("make [this] bold\nsecond line", r.show)
        r.field.tap(22); r.pump()
        r.editor.insertAtLineStart("- "); r.pump()
        assertEquals("make this bold\n- second |line", r.show)
        assertEquals(done, r.undo())
        assertEquals("make this bold\nsecond |line", r.show)
        assertEquals(done, r.redo())
        assertEquals("make this bold\n- second |line", r.show)
        r.field.tap(4); r.pump()
        r.editor.replaceSelection(Kind.WRAP) { if (it.isEmpty()) Pair("[]()", 3) else Pair("[$it]()", 1) }; r.pump()
        assertEquals("make[|]() this bold\n- second line", r.show)
        r.undo()
        assertEquals("make| this bold\n- second line", r.show)
    }

    // --- autocorrect, suggestions, swipe ------------------------------------------------------------------------------

    @Test fun autocorrectUndoGivesBackTheWordAndItsSpace() {
        val r = Rig()
        r.keys("hello teh")
        assertTrue(r.editor.replaceBefore("teh", "the ", Kind.CORRECTION, typedFirst = " ")); r.pump()
        assertEquals("hello the |", r.show)
        r.keys("ok ")
        assertEquals("hello the ok |", r.show)
        r.undo()
        assertEquals("hello the |", r.show)
        assertEquals(done, r.undo())
        assertEquals("hello teh |", r.show)
        assertEquals(done, r.undo())
        assertEquals("hello |", r.show)
        r.redo(); r.redo()
        assertEquals("hello the |", r.show)
    }

    @Test fun doubleSpaceFullStop() {
        val r = Rig()
        r.keys("done ")
        assertTrue(r.editor.replaceBefore(" ", ". ", Kind.CORRECTION, typedFirst = " ")); r.pump()
        assertEquals("done. |", r.show)
        r.undo()
        assertEquals("done  |", r.show)
        r.undo()
        assertEquals("|", r.show)
    }

    @Test fun suggestionAndSwipeAlternative() {
        val r = Rig()
        r.keys("hel")
        assertTrue(r.editor.replaceBefore("hel", "hello ", Kind.SUGGESTION)); r.pump()
        r.editor.insert("there", Kind.SWIPE); r.pump()
        assertTrue(r.editor.replaceBefore("there", "three", Kind.SWIPE)); r.pump()
        assertEquals("hello three|", r.show)
        // The swapped swipe is still one step.
        assertEquals(3, r.steps)
        r.undo()
        assertEquals("hello |", r.show)
        r.undo()
        assertEquals("hel|", r.show)
        r.redo(); r.redo()
        assertEquals("hello three|", r.show)
        assertFalse(r.editor.replaceBefore("nope", "x", Kind.SUGGESTION))
        assertEquals("hello three|", r.show)
    }

    @Test fun pasteEmojiDictation() {
        val r = Rig("see ")
        r.editor.insert("https://example.com/a?b=1\nline two", Kind.PASTE); r.pump()
        r.editor.insert(" 🍕", Kind.EMOJI); r.pump()
        r.editor.insert(" and some dictated words", Kind.DICTATION); r.pump()
        assertEquals(3, r.steps)
        r.undo(); r.undo(); r.undo()
        assertEquals("see |", r.show)
        r.redo()
        assertEquals("see https://example.com/a?b=1\nline two|", r.show)
    }

    // --- Enter --------------------------------------------------------------------------------------------------------

    @Test fun enterIsItsOwnStepAndSeesTheBulletTheAppAdds() {
        val r = Rig()
        r.keys("- milk")
        r.enter(extra = "- ")
        assertEquals("- milk\n- |", r.show)
        r.keys("eggs")
        assertEquals(4, r.steps)
        r.undo()
        assertEquals("- milk\n- |", r.show)
        assertEquals(done, r.undo())
        assertEquals("- milk|", r.show)
        assertEquals(done, r.redo())
        assertEquals("- milk\n- |", r.show)
    }

    @Test fun enterAppliedLateIsStillSeen() {
        val r = Rig()
        r.keys("one")
        r.enter(late = true)
        assertEquals("one\n|", r.show)
        assertEquals(2, r.steps)
        r.undo()
        assertEquals("one|", r.show)
    }

    @Test fun enterThatSendsTheMessageDropsTheHistory() {
        val r = Rig()
        r.keys("send me")
        r.editor.enter { r.field.outside("") }
        r.pump(); r.pass(300)
        assertEquals(0, r.steps)
        assertFalse(r.editor.canUndo)
    }

    // --- AI answers ---------------------------------------------------------------------------------------------------

    @Test fun fillTypedOutIsOneStepAndUndoMidTypingTakesItAllBack() {
        val r = Rig()
        r.keys("Dear team, <<status>>")
        val tag = "<<status>>"
        assertTrue(r.editor.beginFill(tag.length, 0, tag, selection = false, full = "All green today.", typeOut = true)); r.pump()
        r.editor.appendFill("All "); r.pump()
        r.editor.appendFill("gre"); r.pump()
        assertEquals("Dear team, All gre|", r.show)
        // Undo while it is still typing: the tag is back, cursor where it was.
        assertEquals(done, r.undo())
        assertEquals("Dear team, <<status>>|", r.show)
        // Redo writes the whole answer.
        assertEquals(done, r.redo())
        assertEquals("Dear team, All green today.|", r.show)
        assertEquals(done, r.undo())
        assertEquals("Dear team, <<status>>|", r.show)
    }

    @Test fun fillFinishedIsOneStep() {
        val r = Rig("<<hi>> tail")
        r.field.tap(4); r.pump()
        assertTrue(r.editor.beginFill(4, 2, "<<hi>>", selection = false, full = "Hello!", typeOut = true)); r.pump()
        for (c in listOf("He", "ll", "o!")) { r.editor.appendFill(c); r.pump() }
        r.editor.endFill()
        assertEquals("Hello!| tail", r.show)
        val before = r.steps
        assertEquals(done, r.undo())
        assertEquals("<<hi|>> tail", r.show)
        assertEquals(before - 1, r.steps)
        assertEquals(done, r.redo())
        assertEquals("Hello!| tail", r.show)
        assertFalse(r.editor.beginFill(4, 2, "<<hi>>", selection = false, full = "x", typeOut = false))
    }

    @Test fun rewriteOfASelectionAndOfTheWholeField() {
        val r = Rig("i has a cat")
        r.field.tap(2, 5); r.pump()
        assertTrue(r.editor.beginFill(0, 0, "has", selection = true, full = "have", typeOut = false)); r.pump()
        assertEquals("i have| a cat", r.show)
        r.undo()
        assertEquals("i [has] a cat", r.show)
        // The whole field: the cursor was at 4, Rewrite selects everything, the answer replaces it.
        r.field.tap(4); r.pump()
        val was = r.editor.selectionNow()
        r.editor.sent(); r.field.tap(0, r.field.text.length); r.pump()
        r.editor.rememberRewrite(r.field.text, was)
        assertTrue(r.editor.beginFill(0, 0, "i has a cat", selection = true, full = "I have a cat.", typeOut = false)); r.pump()
        assertEquals("I have a cat.|", r.show)
        assertEquals(done, r.undo())
        assertEquals("i ha|s a cat", r.show)
        assertFalse(r.editor.beginFill(0, 0, "not selected", selection = true, full = "x", typeOut = false))
    }

    @Test fun cursorMovedMidAnswerMakesASecondStep() {
        val r = Rig("<<q>> end")
        r.field.tap(5); r.pump()
        assertTrue(r.editor.beginFill(5, 0, "<<q>>", selection = false, full = "abcdef", typeOut = true)); r.pump()
        r.editor.appendFill("abc"); r.pump()
        r.field.tap(r.field.text.length); r.pump()
        r.editor.appendFill("def"); r.pump()
        r.editor.endFill()
        assertEquals("abc enddef|", r.show)
        r.undo()
        assertEquals("abc end|", r.show)
        r.undo()
        assertEquals("<<q>>| end", r.show)
        r.redo(); r.redo()
        assertEquals("abc enddef|", r.show)
    }

    // --- the field changing behind the keyboard's back ----------------------------------------------------------------

    @Test fun outsideEditNextToTheStepIsNotUndoneOver() {
        val r = Rig()
        r.keys("hello world ")
        r.field.appHistory += r.field.text
        r.field.outside("hello world PASTED"); r.pump()
        val before = r.field.text
        // The stale history is refused: nothing of the keyboard's is taken out, the app is asked instead.
        assertEquals(asked, r.undo())
        assertEquals(1, r.field.appUndos)
        assertEquals(0, r.field.keyUndos)
        assertEquals("hello world ", r.field.text)
        assertNotEquals(before, r.field.text)
        assertEquals(0, r.steps)
        assertTrue(r.editor.handOff)
        assertTrue(r.editor.canUndo)
    }

    @Test fun outsideEditInsideTheStepLeavesTheTextByteForByte() {
        val r = Rig()
        r.keys("hello world ")
        r.field.outside("hello w0rld ", 12); r.pump()
        r.field.menuDeaf = true
        assertEquals(asked, r.undo())
        assertEquals("hello w0rld |", r.show)
        // The menu's undo changed nothing, so Ctrl+Z followed, once.
        assertEquals(1, r.field.appUndos)
        assertEquals(1, r.field.keyUndos)
    }

    @Test fun auditDimsTheArrowsWhenTheFieldWasEmptied() {
        val r = Rig()
        r.keys("message to send ")
        assertTrue(r.editor.canUndo)
        r.field.outside(""); r.pump()
        r.pass(400)
        assertEquals(0, r.steps)
        assertFalse(r.editor.canUndo)
        assertFalse(r.editor.handOff)
    }

    @Test fun auditDropsAHistoryTheAppRewrote() {
        val r = Rig()
        r.keys("abc def ")
        r.field.outside(">ABC DEF ", 9); r.pump()
        r.pass(400)
        assertEquals(0, r.steps)
        assertTrue(r.editor.handOff)
    }

    @Test fun auditSeesTextAddedAfterTheLastStep() {
        val r = Rig()
        r.keys("typed here ")
        r.field.outside("typed here XYZ"); r.pump()
        r.pass(400)
        assertEquals(0, r.steps)
        assertTrue(r.editor.handOff)
        assertEquals("typed here XYZ|", r.show)
    }

    @Test fun outsideEditFarAwayStillUndoesOurStep() {
        val r = Rig("0123456789 0123456789 0123456789 ")
        r.keys("mine ")
        // Something is put in at the very start: everything shifts, the keyboard's word is untouched.
        r.field.outside("NEW " + r.field.text); r.pump()
        // Tapped before the check has run: the word is found at the cursor and taken back exactly.
        assertEquals(done, r.undo())
        assertEquals("NEW 0123456789 0123456789 0123456789 |", r.show)
    }

    @Test fun outsideEditFarAwaySeenByTheCheckDropsTheHistory() {
        val r = Rig("0123456789 0123456789 0123456789 ")
        r.keys("mine ")
        r.field.outside("NEW " + r.field.text); r.pump()
        // The check runs first: the text is not where the history says, so the history goes rather than be trusted.
        r.pass(400)
        assertEquals(0, r.steps)
        assertEquals(asked, r.undo())
        assertEquals("NEW 0123456789 0123456789 0123456789 mine |", r.show)
    }

    @Test fun staleRedoIsRefused() {
        val r = Rig()
        r.keys("one two ")
        r.undo()
        r.field.outside("one X", 5); r.pump()
        assertEquals(asked, r.redo())
        assertEquals("one X|", r.show)
    }

    // --- hostile fields -----------------------------------------------------------------------------------------------

    @Test fun unreadableFieldIsNotRecorded() {
        val r = Rig()
        r.field.nullReads = true
        r.keys("abc ")
        assertEquals("abc |", r.show)
        assertFalse(r.editor.recording)
        assertEquals(0, r.steps)
        assertFalse(r.editor.backspace())
        assertEquals(asked, r.undo())
        assertEquals(1, r.field.keyUndos)
        assertEquals("abc |", r.show)
    }

    @Test fun slowReadsStopRecording() {
        val r = Rig()
        r.field.readCost = 200
        r.keys("one two three ")
        assertEquals("one two three |", r.show)
        assertFalse(r.editor.recording)
    }

    @Test fun truncatedReadsNeverCorrupt() {
        val r = Rig("some text already in the field ")
        r.field.truncate = 6
        r.keys("and a long sentence typed here ")
        val typed = r.field.text
        repeat(12) { r.undo() }
        // Whatever was undone was undone exactly: the text is a prefix of what was typed.
        assertTrue(typed.startsWith(r.field.text))
        assertTrue(r.field.text.startsWith("some text already in the field "))
    }

    @Test fun ignoredSetSelectionNeverCorrupts() {
        val r = Rig()
        r.field.ignoreSelect = true
        r.keys("one two three ")
        r.field.tap(2); r.pump()
        val out = r.undo()
        assertTrue(out == asked || out == done)
        assertTrue(r.field.text == "one two three " || r.field.text == "one two ")
    }

    @Test fun ignoredDeleteStopsRecordingAfterOneStep() {
        val r = Rig()
        r.keys("one two ")
        r.field.ignoreDelete = true
        r.undo()
        // The field did not do as asked: nothing more is tried on it.
        assertFalse(r.editor.recording)
        assertEquals("one two |", r.show)
    }

    @Test fun composingRegionLeftBehindIsLetGoFirst() {
        val r = Rig()
        r.keys("abc def ")
        r.field.composing = Pair(0, 3)
        assertEquals(done, r.undo())
        assertEquals("abc |", r.show)
        r.field.composing = Pair(0, 3)
        assertEquals(done, r.redo())
        assertEquals("abc def |", r.show)
    }

    @Test fun fieldThatRewritesAsYouTypeGoesToThePlainPath() {
        val r = Rig()
        r.field.rewrite = { it.uppercase() }
        r.keys("abc def ghi jkl mno pqr ")
        assertEquals("ABC DEF GHI JKL MNO PQR |", r.show)
        assertFalse(r.editor.recording)
        assertEquals(0, r.steps)
    }

    @Test fun lateAndMissingReportsDoNotBreakGrouping() {
        val late = Rig()
        // Reports arrive two keys late.
        val text = "hello world "
        val held = ArrayList<Pair<Int, Int>>()
        for (c in text) {
            late.editor.type(c.toString())
            held += late.field.reports; late.field.reports.clear()
            if (held.size > 2) { val (s, e) = held.removeAt(0); late.editor.onSelection(s, e) }
            late.now += 50
        }
        for ((s, e) in held) late.editor.onSelection(s, e)
        assertEquals(2, late.steps)
        late.undo(); late.undo()
        assertEquals("|", late.show)

        val mute = Rig()
        mute.field.mute = true
        mute.keys("hello world ")
        assertEquals(2, mute.steps)
        mute.undo()
        assertEquals("hello |", mute.show)
    }

    @Test fun noPositionsStillUndoesAtTheCursor() {
        val r = Rig()
        r.field.noOffset = true
        r.keys("one two ")
        r.editor.insert("PASTE", Kind.PASTE); r.pump()
        r.undo(); r.undo(); r.undo()
        assertEquals("|", r.show)
        r.redo(); r.redo(); r.redo()
        assertEquals("one two PASTE|", r.show)
    }

    // --- limits, passwords, the arrows --------------------------------------------------------------------------------

    @Test fun capsOnStepsAndCharacters() {
        val r = Rig()
        repeat(150) { r.editor.insert("w$it ", Kind.PASTE); r.pump() }
        assertEquals(EditHistory.MAX_STEPS, r.steps)
        val big = Rig()
        repeat(8) { big.editor.insert("x".repeat(40_000), Kind.PASTE); big.pump() }
        assertTrue(big.editor.history.units() <= EditHistory.MAX_UNITS)
        assertTrue(big.steps in 1..5)
        assertEquals(done, big.undo())
        assertEquals(7 * 40_000, big.field.text.length)
    }

    @Test fun oversizedStepIsMadeButNotKept() {
        val r = Rig()
        r.keys("small ")
        r.editor.insert("y".repeat(EditHistory.MAX_STEP + 1), Kind.PASTE); r.pump()
        assertEquals(6 + EditHistory.MAX_STEP + 1, r.field.text.length)
        assertEquals(0, r.steps)
        assertTrue(r.editor.handOff)
    }

    @Test fun picturesAreNotTakenOut() {
        val r = Rig("a￼b")
        r.field.tap(0, 3); r.pump()
        r.keys("x")
        assertEquals("x|", r.show)
        assertEquals(0, r.steps)
        val p = Rig("a￼")
        assertFalse(p.editor.backspace())
    }

    @Test fun passwordFieldsRecordNothingAndSendTheOldCalls() {
        val r = Rig(recordable = false)
        r.keys("secret ")
        r.editor.insert("paste", Kind.PASTE)
        assertEquals(0, r.steps)
        assertEquals(0, r.field.reads)
        assertFalse(r.editor.backspace())
        assertTrue(r.editor.canUndo)
        assertEquals(asked, r.undo())
        assertEquals(asked, r.redo())
        assertEquals(List(8) { "commit(${if (it < 7) 1 else 5})" }, r.field.calls.filter { it.startsWith("commit") })
    }

    @Test fun arrowsDimAndLight() {
        val empty = Rig()
        assertFalse(empty.editor.canUndo)
        assertFalse(empty.editor.canRedo)
        empty.keys("a")
        assertTrue(empty.editor.canUndo)
        assertFalse(empty.editor.canRedo)
        empty.undo()
        assertFalse(empty.editor.canUndo)
        assertTrue(empty.editor.canRedo)
        assertTrue(empty.changes >= 2)
        // A field that already had text: the app's undo is on offer until the keyboard does something of its own.
        val full = Rig("was here")
        assertTrue(full.editor.canUndo)
        assertEquals(asked, full.undo())
        full.keys(" more")
        full.undo()
        assertEquals("was here|", full.show)
        assertFalse(full.editor.canUndo)
    }

    @Test fun aNewFieldForgetsTheOldOne() {
        val r = Rig()
        r.keys("first field ")
        r.editor.attach(true, false, 0, 0)
        assertEquals(0, r.steps)
        assertFalse(r.editor.canUndo)
        r.editor.detach()
        assertTrue(r.editor.canUndo)
        assertFalse(r.editor.recording)
    }

    @Test fun restartOfTheSameFieldKeepsAHistoryThatStillFits() {
        val r = Rig()
        r.keys("kept words ")
        r.editor.restarted(r.field.selStart, r.field.selEnd)
        r.pass(10)
        assertEquals(2, r.steps)
        assertEquals(done, r.undo())
        assertEquals("kept |", r.show)
    }

    // --- everything together ------------------------------------------------------------------------------------------

    @Test fun fuzzUndoThenRedoReproducesEverySnapshot() {
        for (seed in 1..60) {
            val rnd = java.util.Random(seed.toLong())
            val r = Rig()
            if (seed % 5 == 0) r.field.noOffset = true
            // after[k]: the field once step k was last changed. before[k]: the field just before step k+1 began.
            val after = ArrayList<String>(); val before = ArrayList<String>()
            after += r.show; before += r.show
            fun mark(edit: Boolean) {
                val k = r.steps
                while (after.size > k + 1) { after.removeAt(after.size - 1); before.removeAt(before.size - 1) }
                while (after.size < k + 1) { after += r.show; before += r.show }
                if (edit) after[k] = r.show
                before[k] = r.show
            }
            val words = listOf("alpha", "be", "😀", "γάμμα", "שלום", "x́y", "tab\there", "new\nline", "👨‍👩‍👧", "🇬🇧")
            repeat(40) {
                val was = r.field.text
                when (rnd.nextInt(12)) {
                    0, 1, 2, 3 -> {
                        // A key at a time, marking after each: one word can be two steps (a line end inside it).
                        val word = words[rnd.nextInt(words.size)] + " "
                        var i = 0
                        while (i < word.length) {
                            val n = Character.charCount(word.codePointAt(i))
                            r.keys(word.substring(i, i + n))
                            mark(true)
                            i += n
                        }
                        r.now += 4_000
                    }
                    4 -> { r.editor.insert(words[rnd.nextInt(words.size)], Kind.PASTE); r.pump() }
                    5 -> if (r.field.selStart > 0 && r.field.selStart == r.field.selEnd) {
                        val pre = r.show
                        val k0 = r.steps
                        r.now += 4_000
                        r.backspace()
                        if (r.steps == k0) {
                            // It came off the word being typed. Typing on makes it a slip put right, the word still one
                            // step; anything else makes the backspace a step of its own after the word as it was typed.
                            val mid = r.show
                            if (rnd.nextBoolean()) {
                                r.keys("q")
                            } else {
                                r.editor.insert("P", Kind.PASTE); r.pump()
                                assertEquals(k0 + 2, r.steps)
                                after[k0] = pre; before[k0] = pre
                                after += mid; before += mid
                            }
                        }
                        r.now += 4_000
                    }
                    6 -> if (!r.field.noOffset && r.field.text.isNotEmpty()) {
                        // A tap, on a code point boundary.
                        var at = rnd.nextInt(r.field.text.length + 1)
                        while (at > 0 && at < r.field.text.length && Character.isLowSurrogate(r.field.text[at])) at--
                        r.field.tap(at); r.pump()
                        mark(false)
                        return@repeat
                    }
                    7 -> if (!r.field.noOffset && r.field.text.length > 3) {
                        var a = rnd.nextInt(r.field.text.length - 1)
                        var b = a + 1 + rnd.nextInt(minOf(6, r.field.text.length - a))
                        while (a > 0 && Character.isLowSurrogate(r.field.text[a])) a--
                        while (b < r.field.text.length && Character.isLowSurrogate(r.field.text[b])) b++
                        r.field.tap(a, b); r.pump()
                        mark(false)
                        r.keys("Z"); r.now += 4_000
                    }
                    8 -> { r.editor.type("()", caretBack = 1); r.pump() }
                    9 -> { r.editor.replaceSelection(Kind.WRAP) { s -> Pair("**$s**", if (s.isEmpty()) 2 else 0) }; r.pump() }
                    10 -> if (!r.field.noOffset) { r.editor.insertAtLineStart("- "); r.pump() }
                    11 -> { r.enter(); r.now += 4_000 }
                }
                mark(r.field.text != was)
            }
            assertTrue("seed $seed errors ${r.errors}", r.errors.isEmpty())
            assertTrue("seed $seed stopped recording", r.editor.recording)
            val top = r.steps
            for (k in top downTo 1) {
                assertEquals("seed $seed undo $k", done, r.undo())
                if (r.field.noOffset) assertEquals("seed $seed undo $k", before[k - 1].replace("|", "").replace("[", "").replace("]", ""), r.field.text)
                else assertEquals("seed $seed undo $k", before[k - 1], r.show)
            }
            for (k in 1..top) {
                assertEquals("seed $seed redo $k", done, r.redo())
                if (r.field.noOffset) assertEquals("seed $seed redo $k", after[k].replace("|", "").replace("[", "").replace("]", ""), r.field.text)
                else assertEquals("seed $seed redo $k", after[k], r.show)
            }
        }
    }
}
