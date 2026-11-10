package com.infamousvague.devkeyboard

import com.infamousvague.devkeyboard.EditHistory.Companion.CTX
import com.infamousvague.devkeyboard.EditHistory.Companion.MAX_STEP

/**
 * What a text field said when asked: the text [before] the selection, the text [selected], and the text [after] it.
 * [selStart] and [selEnd] count from the field's start, or are -1 where the field does not say where the cursor is.
 */
class Reading(val before: String, val selected: String, val after: String, val selStart: Int, val selEnd: Int) {
    /** Whether the field said where its cursor is. */
    val known: Boolean get() = selStart >= 0

    /** Whether nothing came back at all: no text either side and nothing selected. */
    val empty: Boolean get() = before.isEmpty() && selected.isEmpty() && after.isEmpty()

    /** Whether [other] is the same answer. */
    fun sameAs(other: Reading): Boolean =
        before == other.before && selected == other.selected && after == other.after && selStart == other.selStart && selEnd == other.selEnd
}

/**
 * The text field as the keyboard reaches it: Android's InputConnection, cut down to what editing needs
 * (ConnectionPort.kt), or a pretend field in the tests. Every call but the reads is one-way: nothing comes back to
 * say whether the app did it.
 */
interface FieldPort {
    /** Up to [before] characters before the selection, the selection, and up to [after] after it. Null: it cannot be read. */
    fun read(before: Int, after: Int): Reading?

    /** The [n] characters before the cursor, as 0.9.4 asked for them. */
    fun textBefore(n: Int): String?

    /** The selected text, or null for none. */
    fun selectedText(): String?

    /** Types [text] over the selection, leaving the cursor after it. */
    fun commit(text: String)

    /** Deletes [before] characters before the selection and [after] after it. */
    fun delete(before: Int, after: Int)

    /** Selects from [start] to [end], counted from the field's start. */
    fun select(start: Int, end: Int)

    /** Starts a run of calls the app should show as one change. */
    fun beginBatch()

    /** Ends the run [beginBatch] started. */
    fun endBatch()

    /** Lets go of any composing text, so the next calls act on plain text. */
    fun finishComposing()

    /** Moves the cursor [delta] characters from where the app has it, the way 0.9.4 did. */
    fun nudge(delta: Int)

    /** Asks the app for its own undo (or redo) from its edit menu. False only when the request could not be sent. */
    fun appUndo(redo: Boolean): Boolean

    /** Ctrl+Z (or Ctrl+Shift+Z) as a key press. */
    fun keyUndo(redo: Boolean)
}

/** Two timers the editor asks for: one to look at what Enter did, one to check the field after something else changed it. */
interface Scheduler {
    /** Calls [FieldEditor.settle] in [ms], replacing an earlier request. */
    fun settleIn(ms: Long)

    /** Calls [FieldEditor.audit] in [ms], replacing an earlier request. */
    fun auditIn(ms: Long)

    /** Forgets both. */
    fun cancel()
}

/**
 * Where the keyboard expects the cursor to be. Every edit of its own adds the selection it should lead to; when the
 * app reports a selection that was expected, the keyboard did it, and when it reports any other, something else moved
 * the cursor (a tap, the app, another keyboard).
 */
class Caret(private val clock: () -> Long) {
    /** What a reported selection was: the same again, the keyboard's own doing, or somebody else's. */
    enum class Verdict { SAME, OWN, FOREIGN }

    /** The selection last taken as true, or -1. */
    var start = -1
        private set
    var end = -1
        private set
    private var seen = false
    private val expected = ArrayDeque<LongArray>()
    private var adopt = 0
    private var adoptAt = 0L

    /** A new field: the selection its app gave at the start, not yet confirmed by a report. */
    fun seed(s: Int, e: Int) {
        expected.clear()
        seen = false
        adopt = 0
        if (s < 0 || e < 0) { start = -1; end = -1 } else { start = minOf(s, e); end = maxOf(s, e) }
    }

    /** Forgets expectations more than two seconds old: an app that never reports must not fill the list. */
    private fun expire() {
        val now = clock()
        while (expected.isNotEmpty() && now - expected.first()[2] > 2_000) expected.removeFirst()
        if (adopt > 0 && now - adoptAt > 2_000) adopt = 0
    }

    /** The selection start the keyboard can rely on: reported by the app, with nothing of its own still to be reported. Else -1. */
    fun knownStart(): Int { expire(); return if (seen && start >= 0 && expected.isEmpty() && adopt == 0) start else -1 }

    /** The selection end to go with [knownStart]. */
    fun knownEnd(): Int = if (knownStart() >= 0) end else -1

    /** How long the selection is, as far as is known. */
    fun selectionLength(): Int = if (start >= 0) end - start else 0

    /** The keyboard is about to make the selection [s] to [e]. */
    fun predict(s: Int, e: Int = s) {
        if (s < 0 || e < 0) { unknown(); return }
        expire()
        expected.addLast(longArrayOf(minOf(s, e).toLong(), maxOf(s, e).toLong(), clock()))
        while (expected.size > 64) expected.removeFirst()
    }

    /** The keyboard typed or deleted [delta] characters at the cursor. */
    fun advance(delta: Int) {
        expire()
        val base = if (expected.isNotEmpty()) expected.last()[0].toInt() else start
        if (base < 0 || adopt > 0 || base + delta < 0) unknown() else predict(base + delta)
    }

    /** The keyboard did something whose result it cannot work out: the next report is its own, whatever it says. */
    fun unknown() {
        expected.clear()
        start = -1; end = -1
        adopt = (adopt + 1).coerceAtMost(8)
        adoptAt = clock()
    }

    /** The field itself said the selection is [s] to [e] (a read): believed over everything else. */
    fun agree(s: Int, e: Int) {
        expire()
        val lo = minOf(s, e); val hi = maxOf(s, e)
        if (expected.isNotEmpty() && expected.last()[0].toInt() == lo && expected.last()[1].toInt() == hi) return
        if (expected.isEmpty() && start == lo && end == hi) { seen = true; return }
        expected.clear()
        start = lo; end = hi; seen = true
    }

    /** The app reported the selection [s0] to [e0]. */
    fun report(s0: Int, e0: Int): Verdict {
        val s = minOf(s0, e0); val e = maxOf(s0, e0)
        expire()
        val i = expected.indexOfFirst { it[0].toInt() == s && it[1].toInt() == e }
        if (i >= 0) {
            repeat(i + 1) { expected.removeFirst() }
            start = s; end = e; seen = true
            return Verdict.OWN
        }
        if (s == start && e == end) { seen = true; return Verdict.SAME }
        if (adopt > 0) {
            adopt--
            start = s; end = e; seen = true
            return Verdict.OWN
        }
        expected.clear()
        start = s; end = e; seen = true
        return Verdict.FOREIGN
    }

    /** Takes [s] to [e] as true without judging it (while Enter is being worked out). */
    fun take(s: Int, e: Int) {
        expected.clear()
        adopt = 0
        start = minOf(s, e); end = maxOf(s, e); seen = true
    }
}

/**
 * Every edit the keyboard makes goes through here. While the Undo and redo plugin is recording a field, each edit is
 * also written down as a [Step] in an [EditHistory], and [undo] and [redo] play the steps back through the field.
 *
 * The rule that keeps text safe: nothing is taken out of a field unless it was first read back and found to be exactly
 * what the step put there, with the text either side of it as it was. If it is not (the app changed it, something was
 * pasted from the app's own menu, another keyboard typed), the history is dropped and the app is asked to undo instead.
 *
 * Where the field is not being recorded (the plugin is off, a password, a field that cannot be read) every method
 * sends exactly the calls 0.9.4 sent and nothing is kept.
 *
 * Everything runs on the main thread. A failure in the bookkeeping is logged, drops the history and stops recording
 * the field; the edit itself still goes out.
 */
class FieldEditor(
    private val port: FieldPort,
    private val clock: () -> Long,
    private val scheduler: Scheduler,
    private val log: (String, Throwable?) -> Unit,
    private val onChange: () -> Unit,
    private val clusterBefore: (String) -> Int,
) {
    /** What a tap on an arrow came to: a step of the keyboard's own, a request to the app, or nothing. */
    enum class Outcome { DONE, ASKED_APP, NOTHING }

    /** The steps. Read by the tests; the service only asks [canUndo] and [canRedo]. */
    val history = EditHistory()

    /** Whether this field's edits are being written down. */
    var recording = false
        private set

    /** Whether, with nothing of the keyboard's own to undo, the app's undo is the right thing to ask for. */
    var handOff = false
        private set

    /** The step whose end the cursor is believed to sit at, so the next key can join it without reading the field. */
    private var tip: Step? = null

    /** Enter, sent as a key press and not yet seen in the field. */
    private var pending: Step? = null

    /** An AI answer still typing out. */
    private var fill: Step? = null

    /**
     * What backspace has taken off the end of the word being typed ([shrunkStep]), newest first in the text's order.
     * If typing goes on, it was a slip put right and is forgotten, the word staying one step. If anything else comes
     * next, the word is put down as it was typed and the backspaces become a step of their own, so undo gives the
     * deleted letters back.
     */
    private val shrunk = StringBuilder()
    private var shrunkStep: Step? = null

    private val caret = Caret(clock)
    private var strikes = 0
    private var echoes = 0
    private var slow = 0
    private var lastEditAt = 0L
    private var judging = false
    private var told = Pair(true, true)

    /** For Rewrite on a whole field: the text it selected, and the cursor from before it selected everything. */
    private var rewrite: Triple<String, Int, Int>? = null

    /** Whether the Undo arrow does anything: a step of the keyboard's own, or the app's undo. */
    val canUndo: Boolean get() = !recording || history.undo.isNotEmpty() || pending != null || handOff

    /** Whether the Redo arrow does anything. */
    val canRedo: Boolean get() = !recording || history.redo.isNotEmpty() || handOff

    // --- the field coming and going -----------------------------------------------------------------------------------

    /**
     * A field to type in. [recordable] is false for passwords, fields with no text type, and with the plugin off;
     * [hadText] says the field already held text, whose undo is the app's; [selStart] and [selEnd] are the app's
     * opening selection.
     */
    fun attach(recordable: Boolean, hadText: Boolean, selStart: Int, selEnd: Int) {
        forget()
        recording = recordable
        handOff = hadText
        strikes = 0; echoes = 0; slow = 0
        caret.seed(selStart, selEnd)
        tell()
    }

    /** The same field started again by its app: the history stays, and is checked against the field straight away. */
    fun restarted(selStart: Int, selEnd: Int) {
        if (!recording) return
        guarded {
            sealAll()
            tip = null
            caret.seed(selStart, selEnd)
            if (clock() - lastEditAt < 500) strike()
            scheduler.auditIn(0)
        }
        tell()
    }

    /** The field is gone: its history goes with it. */
    fun detach() {
        forget()
        recording = false
        handOff = false
        tell()
    }

    /**
     * The keyboard came up on a field with no history: one small read to see whether it holds text already, so the
     * arrows are lit (the app's undo) where there is something to undo and dim where there is not.
     */
    fun peek() {
        if (!recording || handOff || history.undo.isNotEmpty() || history.redo.isNotEmpty()) return
        val r = runCatching { port.read(1, 1) }.getOrNull() ?: return
        if (!r.empty) handOff = true
        tell()
    }

    /**
     * The app reported a selection: the keyboard's own doing, or a tap, the app, or another keyboard. True when it
     * was not the keyboard's.
     */
    fun onSelection(start: Int, end: Int): Boolean {
        if (!recording || start < 0 || end < 0) return false
        var foreign = false
        guarded {
            if (pending != null) { caret.take(start, end); return@guarded }
            when (caret.report(start, end)) {
                Caret.Verdict.SAME -> Unit
                Caret.Verdict.OWN -> if (judging) { judging = false; echoes = 0 }
                Caret.Verdict.FOREIGN -> {
                    foreign = true
                    if (judging && clock() - lastEditAt < 500) strike()
                    if (recording) {
                        sealAll()
                        tip = null
                        scheduler.auditIn(300)
                    }
                }
            }
        }
        tell()
        return foreign
    }

    /** One more sign that the app rewrites the text as it is typed. After four in a row the field is left alone. */
    private fun strike() {
        if (!judging) return
        judging = false
        if (++echoes >= 4) {
            drop(true)
            recording = false
        }
    }

    // --- typing -------------------------------------------------------------------------------------------------------

    /**
     * Types [text] at the cursor, over any selection. With [caretBack] the cursor is left that many characters
     * before its end (a pair of brackets, with the cursor inside), which is a step of its own.
     */
    fun type(text: String, caretBack: Int = 0) {
        if (text.isEmpty()) return
        var join: Step? = null
        var plan: Plan? = null
        if (recording) guarded {
            val top = history.top()
            if (pending == null && caretBack == 0 && top != null && top === tip && EditHistory.joinsTyping(top, text, clock())) {
                join = top
            } else {
                sealAll()
                stepRead()?.let { plan = draft(if (caretBack > 0) Kind.PAIR else Kind.TYPING, it, 0, 0, text, caretBack, open = caretBack == 0) }
            }
        }
        send(0, 0, text, caretBack, false, plan?.step?.at ?: -1, batch = false)
        guarded {
            join?.let { grow(it, text) }
            plan?.let { record(it) }
        }
        tell()
    }

    /** Puts [text] in as one step of its own: a paste, an emoji, a swiped word, a snippet, dictated words. */
    fun insert(text: String, kind: Kind, caretBack: Int = 0) {
        if (text.isEmpty()) return
        var plan: Plan? = null
        if (recording) guarded {
            sealAll()
            stepRead()?.let { plan = draft(kind, it, 0, 0, text, caretBack, open = false) }
        }
        send(0, 0, text, caretBack, false, plan?.step?.at ?: -1, batch = caretBack > 0)
        guarded { plan?.let { record(it) } }
        tell()
    }

    /**
     * Replaces [old], just before the cursor, with [new]. False, and nothing done, when [old] is not there and [must]
     * is set; with [must] off [new] is typed anyway. [typedFirst] is a separator the person pressed that the fix
     * swallowed: it is written down as typed first, so undoing the fix gives back the word and its space.
     * A swiped word swapped for another choice stays one step.
     */
    fun replaceBefore(old: String, new: String, kind: Kind, typedFirst: String = "", must: Boolean = true): Boolean {
        var r: Reading? = null
        if (recording) guarded { r = stepRead(maxOf(STEP_BEFORE, old.length + CTX)) }
        val read = r
        val found = old.isNotEmpty() && (if (read != null) read.before.endsWith(old) else port.textBefore(old.length) == old)
        if (!found && must) { tell(); return false }
        val cut = if (found) old.length else 0
        var plan: Plan? = null
        var amend: Step? = null
        if (read != null) guarded {
            val top = history.top()
            if (kind == Kind.SWIPE && found && read.selected.isEmpty() && top != null && top === tip && top.kind == Kind.SWIPE &&
                top.caret == top.inserted.length && top.inserted.toString() == old
            ) {
                amend = top
            } else {
                val sep = if (found && read.selected.isEmpty()) typedFirst else ""
                if (sep.isNotEmpty()) noteTyped(read, sep)
                sealAll()
                plan = draft(kind, read, cut, 0, new, 0, open = false)?.also {
                    if (sep.isNotEmpty()) {
                        it.step.removed.append(sep)
                        it.step.selFrom = it.step.removed.length
                        it.step.selTo = it.step.removed.length
                    }
                }
            }
        }
        send(cut, 0, new, 0, false, -1, batch = true)
        guarded {
            amend?.let {
                it.inserted.setLength(0)
                it.inserted.append(new)
                it.caret = new.length
                it.time = clock()
                history.redo.clear()
                edited()
                if (it.at >= 0) { caret.predict(it.at); caret.predict(it.at + new.length) } else caret.unknown()
            }
            plan?.let { record(it) }
        }
        tell()
        return true
    }

    /** Writes [sep] down as typed at the cursor without typing it: it joins the word being typed, or is a step of its own. */
    private fun noteTyped(r: Reading, sep: String) {
        val top = history.top()
        if (top != null && top === tip && EditHistory.joinsTyping(top, sep, clock())) {
            top.inserted.append(sep)
            top.caret = top.inserted.length
            top.time = clock()
        } else {
            sealAll()
            val pos = position(r)
            history.push(Step(Kind.TYPING, pos, "", sep, r.before.takeLast(CTX), r.after.take(CTX), 0, 0, sep.length, clock()))
        }
    }

    // --- deleting -----------------------------------------------------------------------------------------------------

    /**
     * Backspace. True when it was done here: the selection deleted, or one character (a whole emoji) taken from before
     * the cursor. False where the old key press is the right thing (the field is not recorded, the cursor is at the
     * field's start or after a picture), which the service then sends.
     */
    fun backspace(): Boolean {
        if (!recording) return false
        var how = NONE
        var plan: Plan? = null
        var unit = 0
        var r: Reading? = null
        guarded {
            val read = stepRead() ?: return@guarded
            r = read
            if (read.selected.isNotEmpty()) {
                sealAll()
                plan = draft(Kind.DELETE, read, 0, 0, "", 0, open = false)
                if (plan != null) how = SELECTION
                return@guarded
            }
            if (read.before.isEmpty() || read.before.last() == OBJECT) { sentNow(); return@guarded }
            unit = EditHistory.unitBefore(read.before, clusterBefore)
            how = deleteHow(read, unit)
            if (how == FRESH) {
                sealAll()
                plan = draft(Kind.DELETE, read, unit, 0, "", 0, open = true)
                if (plan == null) how = NONE
            }
        }
        if (how == NONE) { tell(); return false }
        if (how == SELECTION) port.commit("") else port.delete(unit, 0)
        guarded { deleted(how, plan, r!!, unit) }
        tell()
        return true
    }

    /** Deletes [text], which the service has just read from before the cursor: a swiped word, or the word-delete slide. */
    fun deleteBefore(text: String) {
        if (text.isEmpty()) return
        var how = NONE
        var plan: Plan? = null
        var r: Reading? = null
        if (recording) guarded {
            val read = stepRead(maxOf(STEP_BEFORE, text.length + CTX)) ?: return@guarded
            if (read.selected.isNotEmpty() || !read.before.endsWith(text)) { drop(true); return@guarded }
            r = read
            how = deleteHow(read, text.length)
            if (how == FRESH) {
                sealAll()
                plan = draft(Kind.DELETE, read, text.length, 0, "", 0, open = true)
                if (plan == null) how = NONE
            }
        }
        port.delete(text.length, 0)
        if (how != NONE) guarded { deleted(how, plan, r!!, text.length) }
        tell()
    }

    /** Deletes the selection (the word-delete slide with text selected). */
    fun deleteSelection() {
        var plan: Plan? = null
        if (recording) guarded {
            sealAll()
            stepRead()?.let { if (it.selected.isNotEmpty()) plan = draft(Kind.DELETE, it, 0, 0, "", 0, open = false) }
        }
        port.commit("")
        guarded { plan?.let { record(it) } }
        tell()
    }

    /** Where [unit] characters deleted from before the cursor go: off the word being typed, onto a run of backspaces, or a new step. */
    private fun deleteHow(r: Reading, unit: Int): Int {
        var top = history.top()
        val gone = r.before.takeLast(unit)
        if (top != null && top === tip && top.kind == Kind.TYPING && top.state == StepState.OPEN &&
            top.caret == top.inserted.length && top.inserted.endsWith(gone)
        ) return SHRINK
        // Past the start of the word being typed: its backspaces so far become the run this one joins.
        settleShrunk()
        top = history.top()
        return if (top != null && top === tip && EditHistory.joinsDelete(top, clock())) EXTEND else FRESH
    }

    /**
     * Backspaces taken off the word being typed, with no typing after them: the word is written down whole again and
     * the backspaces as a deletion after it, still open for more.
     */
    private fun settleShrunk() {
        val s = shrunkStep ?: return
        shrunkStep = null
        if (shrunk.isEmpty()) return
        val gone = shrunk.toString()
        shrunk.setLength(0)
        if (history.top() !== s) return
        val kept = s.inserted.length
        val d = Step(
            Kind.DELETE, if (s.at >= 0) s.at + kept else -1, gone, "", (s.before + s.inserted).takeLast(CTX), s.after,
            gone.length, gone.length, 0, s.time,
        )
        d.state = StepState.OPEN
        s.inserted.append(gone)
        s.caret = s.inserted.length
        s.state = StepState.SEALED
        history.undo.add(d)
        history.trim()
        if (tip === s) tip = d
    }

    /** Writes a deletion down once it has been sent. */
    private fun deleted(how: Int, plan: Plan?, r: Reading, unit: Int) {
        val top = history.top()
        when (how) {
            SELECTION, FRESH -> plan?.let { record(it) }
            SHRINK -> if (top != null) {
                top.inserted.setLength(top.inserted.length - unit)
                top.caret = top.inserted.length
                top.time = clock()
                history.redo.clear()
                handOff = false
                edited()
                caret.advance(-unit)
                shrunk.insert(0, r.before.takeLast(unit))
                shrunkStep = top
            }
            EXTEND -> if (top != null) {
                top.removed.insert(0, r.before.takeLast(unit))
                if (top.at >= 0) top.at -= unit
                top.before = r.before.dropLast(unit).takeLast(CTX)
                top.selFrom = top.removed.length
                top.selTo = top.removed.length
                top.time = clock()
                history.redo.clear()
                handOff = false
                edited()
                caret.advance(-unit)
            }
        }
    }

    // --- Enter --------------------------------------------------------------------------------------------------------

    /**
     * Enter, which [send] presses as a key: the app decides what it does (a new line, a new bullet, nothing), so the
     * step is filled in from what the field shows a moment later.
     */
    fun enter(send: () -> Unit) {
        var step: Step? = null
        if (recording) guarded {
            sealAll()
            val r = stepRead() ?: return@guarded
            val pos = position(r)
            step = Step(Kind.ENTER, pos, r.selected, "", r.before.takeLast(CTX), r.after.take(CTX), 0, r.selected.length, 0, clock())
                .also { it.state = StepState.PENDING }
        }
        send()
        guarded {
            step?.let {
                history.redo.clear()
                tip = null
                pending = it
                scheduler.settleIn(SETTLE_MS)
            }
        }
        tell()
    }

    /** Looks at what Enter did, with a read of its own or with [given], one another edit has just made. */
    fun settle(given: Reading? = null) {
        val s = pending ?: return
        guarded {
            pending = null
            val r = given ?: timedRead(STEP_BEFORE, CTX) ?: return@guarded
            val shortStart = s.before.length < CTX
            val shortEnd = s.after.length < CTX
            val pos = if (r.known) r.selStart else -1
            // Nothing happened: the app took Enter as something else (send, next field).
            if (r.selected == s.removed.toString() && around(r, s.before, s.after, shortStart, shortEnd) && (pos < 0 || s.at < 0 || pos == s.at)) return@guarded
            var x: String? = null
            if (r.selected.isEmpty()) {
                if (pos >= 0 && s.at >= 0) {
                    val n = pos - s.at
                    if (n in 1..MAX_ENTER && n <= r.before.length) x = r.before.takeLast(n)
                } else if (r.before.endsWith("\n")) {
                    x = "\n"
                }
            }
            val made = x
            if (made != null && made.contains('\n') && around(r, s.before + made, s.after, shortStart, shortEnd)) {
                s.inserted.append(made)
                s.caret = made.length
                s.state = StepState.SEALED
                s.time = clock()
                history.push(s)
                tip = s
                handOff = false
            } else {
                drop(!r.empty)
            }
        }
        tell()
    }

    // --- markdown, snippets and answers -------------------------------------------------------------------------------

    /**
     * Replaces the selection with what [make] gives for it: the new text, and how far from its end the cursor goes
     * (markdown around a selection, a link).
     */
    fun replaceSelection(kind: Kind, make: (String) -> Pair<String, Int>) {
        var r: Reading? = null
        if (recording) guarded { sealAll(); r = stepRead() }
        val selected = r?.selected ?: port.selectedText().orEmpty()
        val (text, back) = make(selected)
        var plan: Plan? = null
        guarded { r?.let { plan = draft(kind, it, 0, 0, text, back, open = false) } }
        send(0, 0, text, back, true, plan?.step?.at ?: -1, batch = true)
        guarded { plan?.let { record(it) } }
        tell()
    }

    /** Puts [prefix] at the start of the cursor's line (a heading, a bullet, a quote), the cursor staying in its place in the line. */
    fun insertAtLineStart(prefix: String) {
        var step: Step? = null
        var back = -1
        if (recording) guarded {
            sealAll()
            val r = stepRead(LINE_BEFORE) ?: return@guarded
            val pos = position(r)
            if (r.selected.isNotEmpty() || pos < 0) { drop(true); return@guarded }
            val lineStart = r.before.lastIndexOf('\n') + 1
            back = r.before.length - lineStart
            step = Step(
                Kind.PREFIX, pos - back, "", prefix, r.before.substring(0, lineStart).takeLast(CTX),
                (r.before.substring(lineStart) + r.after).take(CTX), back, back, prefix.length + back, clock(),
            )
        }
        val s = step
        port.beginBatch()
        if (s != null) {
            port.select(s.at, s.at)
            port.commit(prefix)
            port.select(s.at + s.caret, s.at + s.caret)
        } else {
            // As 0.9.4 did it, from the app's own idea of where the cursor is.
            val before = port.textBefore(LINE_BEFORE).orEmpty()
            val gap = before.length - (before.lastIndexOf('\n') + 1)
            if (gap > 0) port.nudge(-gap)
            port.commit(prefix)
            if (gap > 0) port.nudge(gap)
        }
        port.endBatch()
        guarded {
            if (s != null) {
                history.push(s)
                tip = null
                handOff = false
                edited()
                caret.predict(s.at); caret.predict(s.at + prefix.length); caret.predict(s.at + s.caret)
            } else if (recording) {
                sentNow()
            }
        }
        tell()
    }

    /** Before Rewrite selects the whole field: where the cursor was, so undoing the rewrite puts it back there. -1 if unknown. */
    fun selectionNow(): Pair<Int, Int> = Pair(caret.knownStart(), caret.knownEnd())

    /** Rewrite selected the whole field, [all], with the cursor having been at [was]. */
    fun rememberRewrite(all: String, was: Pair<Int, Int>) {
        rewrite = if (was.first >= 0 && was.second >= 0) Triple(all, was.first, was.second) else null
    }

    /**
     * An AI answer taking its place: [expect] is the text it replaces, [cutBefore] of it before the cursor and
     * [cutAfter] after (both 0 when it is the selection). [full] is the answer; with [typeOut] it is typed in bit by
     * bit through [appendFill], the whole of it one step. False, and nothing done, when [expect] is not there.
     */
    fun beginFill(cutBefore: Int, cutAfter: Int, expect: String, selection: Boolean, full: String, typeOut: Boolean): Boolean {
        var r: Reading? = null
        if (recording) guarded { sealAll(); r = stepRead(maxOf(STEP_BEFORE, cutBefore + CTX), cutAfter + CTX) }
        val read = r
        if (selection) {
            if ((read?.selected ?: port.selectedText()) != expect) { tell(); return false }
        } else if (read != null) {
            if (read.selected.isNotEmpty() || cutBefore > read.before.length || cutAfter > read.after.length ||
                read.before.takeLast(cutBefore) + read.after.take(cutAfter) != expect
            ) { tell(); return false }
        }
        val text = if (typeOut) "" else full
        var plan: Plan? = null
        if (read != null) guarded {
            plan = draft(Kind.AI, read, if (selection) 0 else cutBefore, if (selection) 0 else cutAfter, text, 0, open = typeOut)?.also { p ->
                if (typeOut) p.step.redoText = full
                val was = rewrite
                // Rewrite of a whole field: the cursor goes back to where it was before everything was selected.
                if (selection && was != null && was.first == expect && p.step.at >= 0) {
                    val from = was.second - p.step.at
                    val to = was.third - p.step.at
                    if (from in 0..expect.length && to in from..expect.length) { p.step.selFrom = from; p.step.selTo = to }
                }
            }
        }
        rewrite = null
        if (selection) port.commit(text)
        else {
            port.beginBatch()
            port.delete(cutBefore, cutAfter)
            if (!typeOut) port.commit(text)
            port.endBatch()
        }
        guarded {
            plan?.let {
                record(it)
                if (typeOut) fill = it.step
            }
        }
        tell()
        return true
    }

    /** The next few characters of an answer typing out. They join the answer's step while the cursor is still at its end. */
    fun appendFill(chunk: String) {
        if (chunk.isEmpty()) return
        var join: Step? = null
        var plan: Plan? = null
        if (recording) guarded {
            val f = fill
            if (f != null && f === tip && f === history.top() && f.state == StepState.OPEN && f.inserted.length + chunk.length <= MAX_STEP) {
                join = f
            } else {
                // The cursor was moved mid-answer: what is left is a second step, typed where the cursor is now.
                sealAll()
                stepRead()?.let { plan = draft(Kind.AI, it, 0, 0, chunk, 0, open = true) }
            }
        }
        port.commit(chunk)
        guarded {
            join?.let {
                it.inserted.append(chunk)
                it.caret = it.inserted.length
                it.time = clock()
                edited()
                caret.advance(chunk.length)
            }
            plan?.let { record(it); fill = it.step }
        }
        tell()
    }

    /** The answer is all typed. */
    fun endFill() {
        fill?.let { it.state = StepState.SEALED; it.redoText = null }
        fill = null
    }

    // --- things the keyboard does that are not edits it can follow ---------------------------------------------------

    /**
     * The keyboard sent something whose effect it cannot know (an arrow or terminal key, an editor action, a picture):
     * the open step ends, and the field is checked in a moment.
     */
    fun sent() {
        if (!recording) return
        guarded { sentNow() }
        tell()
    }

    /** [sent], inside bookkeeping that is already guarded. */
    private fun sentNow() {
        sealAll()
        tip = null
        judging = false
        caret.unknown()
        scheduler.auditIn(AUDIT_MS)
    }

    /** The keyboard moved the cursor itself to [to] (the space bar's slide). */
    fun moved(to: Int) {
        if (!recording) return
        guarded {
            sealAll()
            tip = null
            judging = false
            caret.predict(to)
        }
    }

    /** The keyboard stepped the cursor [delta] characters (over a closing bracket). */
    fun hop(delta: Int) {
        port.nudge(delta)
        if (!recording) return
        guarded {
            sealAll()
            tip = null
            judging = false
            val at = caret.knownStart()
            if (at >= 0) caret.predict(at + delta) else caret.unknown()
        }
    }

    // --- undo and redo ------------------------------------------------------------------------------------------------

    /** Takes back the newest step, or asks the app to undo where the keyboard has nothing of its own. */
    fun undo(): Outcome {
        var out = Outcome.NOTHING
        var ask = !recording
        if (recording) guarded {
            sealAll(keepAnswer = true)
            if (pending != null) settle()
            if (!recording) { ask = true; return@guarded }
            val s = history.top()
            if (s == null) { ask = handOff; return@guarded }
            if (undoStep(s)) out = Outcome.DONE else ask = true
        }
        if (ask) { askApp(redo = false); out = Outcome.ASKED_APP }
        tell()
        return out
    }

    /** Puts back what [undo] last took, or asks the app to redo. */
    fun redo(): Outcome {
        var out = Outcome.NOTHING
        var ask = !recording
        if (recording) guarded {
            sealAll()
            if (pending != null) settle()
            if (!recording) { ask = true; return@guarded }
            val s = history.redo.lastOrNull()
            if (s == null) { ask = handOff; return@guarded }
            if (redoStep(s)) out = Outcome.DONE else ask = true
        }
        if (ask) { askApp(redo = true); out = Outcome.ASKED_APP }
        tell()
        return out
    }

    /**
     * Undoes [s] if the field still holds exactly what it left. False when it does not, or the field cannot be read:
     * nothing was changed and the history is dropped.
     */
    private fun undoStep(s: Step): Boolean {
        val n = s.inserted.length
        var c = s.caret
        val shortStart = s.before.length < CTX
        val shortEnd = s.after.length < CTX
        val ins = s.inserted.toString()
        var ok = false
        var r = timedRead(CTX + n + 1, CTX + n + 1) ?: return false
        if (c in 0..n && r.selected.isEmpty() && around(r, s.before + ins.substring(0, c), ins.substring(c) + s.after, shortStart, shortEnd)) {
            ok = true
            // The field says where this is: believed over what was written down (text before it may have changed).
            if (r.known) s.at = r.selStart - c
        } else {
            val from = if (r.known) r.selStart else caret.knownStart()
            val to = if (r.known) r.selEnd else caret.knownEnd()
            if (s.at >= 0 && from >= 0) {
                port.select(s.at + n, s.at + n)
                val r2 = timedRead(CTX + n + 1, CTX + 1)
                if (r2 != null && r2.selected.isEmpty() && (!r2.known || r2.selStart == s.at + n) && around(r2, s.before + ins, s.after, shortStart, shortEnd)) {
                    ok = true
                    c = n
                    r = r2
                } else {
                    port.select(from, to)
                    caret.unknown()
                }
            }
        }
        if (!ok) {
            if (recording) drop(true)
            return false
        }
        val removed = s.removed.toString()
        port.beginBatch()
        port.finishComposing()
        if (n > 0) port.delete(c, n - c)
        if (removed.isNotEmpty()) port.commit(removed)
        port.endBatch()
        // What was written is read back: a field that did not do as asked is not one to keep a history for.
        val want = (s.before + removed).takeLast(CTX + minOf(removed.length, 64))
        val back = timedRead(want.length + 1, CTX + 1)
        if (back == null || !back.selected.isEmpty() || !around(back, want, s.after, shortStart && want.length == s.before.length + removed.length, shortEnd)) {
            drop(false)
            recording = false
            return true
        }
        if (s.at >= 0) {
            caret.predict(s.at); caret.predict(s.at + removed.length)
            if (s.selFrom != removed.length || s.selTo != removed.length) {
                port.select(s.at + s.selFrom, s.at + s.selTo)
                caret.predict(s.at + s.selFrom, s.at + s.selTo)
            }
        } else {
            s.selFrom = removed.length
            s.selTo = removed.length
            caret.unknown()
        }
        history.undo.remove(s)
        // An answer undone while it was typing out: redo writes all of it.
        s.redoText?.let {
            s.inserted.setLength(0)
            s.inserted.append(it)
            s.caret = it.length
            s.redoText = null
        }
        s.state = StepState.SEALED
        history.redo.add(s)
        fill = null
        tip = null
        handOff = false
        judging = false
        return true
    }

    /** Does [s] again if the field still holds exactly what undo left. False as for [undoStep]. */
    private fun redoStep(s: Step): Boolean {
        val m = s.removed.length
        val removed = s.removed.toString()
        val ins = s.inserted.toString()
        val shortStart = s.before.length < CTX
        val shortEnd = s.after.length < CTX
        var from = s.selFrom
        var to = s.selTo
        var ok = false
        val r = timedRead(CTX + m + 1, CTX + m + 1) ?: return false
        if (from in 0..m && to in from..m && r.selected == removed.substring(from, to) &&
            around(r, s.before + removed.substring(0, from), removed.substring(to) + s.after, shortStart, shortEnd)
        ) {
            ok = true
            if (r.known) s.at = r.selStart - from
        } else {
            val wasFrom = if (r.known) r.selStart else caret.knownStart()
            val wasTo = if (r.known) r.selEnd else caret.knownEnd()
            if (s.at >= 0 && wasFrom >= 0) {
                port.select(s.at + m, s.at + m)
                val r2 = timedRead(CTX + m + 1, CTX + 1)
                if (r2 != null && r2.selected.isEmpty() && (!r2.known || r2.selStart == s.at + m) && around(r2, s.before + removed, s.after, shortStart, shortEnd)) {
                    ok = true
                    from = m
                    to = m
                } else {
                    port.select(wasFrom, wasTo)
                    caret.unknown()
                }
            }
        }
        if (!ok) {
            if (recording) drop(true)
            return false
        }
        port.beginBatch()
        port.finishComposing()
        if (from > 0 || m - to > 0) port.delete(from, m - to)
        if (ins.isNotEmpty() || to > from) port.commit(ins)
        port.endBatch()
        val want = (s.before + ins).takeLast(CTX + minOf(ins.length, 64))
        val back = timedRead(want.length + 1, CTX + 1)
        if (back == null || !back.selected.isEmpty() || !around(back, want, s.after, shortStart && want.length == s.before.length + ins.length, shortEnd)) {
            drop(false)
            recording = false
            return true
        }
        if (s.at >= 0) {
            caret.predict(s.at, s.at + (to - from)); caret.predict(s.at + ins.length)
            if (s.caret != ins.length) {
                port.select(s.at + s.caret, s.at + s.caret)
                caret.predict(s.at + s.caret)
            }
        } else {
            s.caret = ins.length
            caret.unknown()
        }
        history.redo.remove(s)
        s.state = StepState.SEALED
        history.undo.add(s)
        tip = null
        handOff = false
        judging = false
        return true
    }

    /**
     * The app's own undo. Its edit menu's Undo is asked first; a field that shows no change after it (Flutter, a web
     * page) gets Ctrl+Z, which is all 0.9.4 ever sent. Sending both blindly would undo twice in an ordinary field.
     */
    private fun askApp(redo: Boolean) {
        runCatching {
            val before = port.read(ASK_READ, ASK_READ)
            val asked = before != null && port.appUndo(redo)
            val after = if (asked) port.read(ASK_READ, ASK_READ) else null
            if (!asked || (before != null && after != null && after.sameAs(before))) port.keyUndo(redo)
        }.onFailure { log("asking the app to undo failed", it) }
        if (recording) guarded { caret.unknown(); tip = null }
    }

    // --- checks -------------------------------------------------------------------------------------------------------

    /**
     * After something else touched the field: is the newest step still there? Where it can be seen not to be, the
     * history is dropped now, so the arrows say the truth before they are tapped. Never on the key path.
     */
    fun audit() {
        if (!recording || pending != null) return
        guarded {
            val s = history.top()
            val back = history.redo.lastOrNull()
            if (s == null && back == null) return@guarded
            val r = timedRead(AUDIT_READ, AUDIT_READ) ?: return@guarded
            // What should be in the field around the newest change: after the last step, or before the next redo.
            val at = s?.at ?: back!!.at
            val want = if (s != null) s.before + s.inserted + s.after else back!!.before + back.removed + back.after
            val lead = if (s != null) s.before.length else back!!.before.length
            if (r.empty) {
                if (want.isNotEmpty()) drop(false)
                return@guarded
            }
            if (!r.known || at < 0) return@guarded
            val text = r.before + r.selected + r.after
            val window = r.selStart - r.before.length
            val from = at - lead - window
            if (from < 0 || from + want.length > text.length) return@guarded
            if (text.substring(from, from + want.length) != want) { drop(true); return@guarded }
            // A step made at the field's very start or end: the field must still start or end there.
            val wholeStart = r.before.length < AUDIT_READ
            val wholeEnd = r.after.length < AUDIT_READ
            val trail = if (s != null) s.after.length else back!!.after.length
            if (lead < CTX && wholeStart && from != 0) drop(true)
            else if (trail < CTX && wholeEnd && from + want.length != text.length) drop(true)
        }
        tell()
    }

    /** Whether [r] has [wantBefore] right before the cursor and [wantAfter] right after; [shortStart] and [shortEnd] ask for the field to start or end exactly there. */
    private fun around(r: Reading, wantBefore: String, wantAfter: String, shortStart: Boolean, shortEnd: Boolean): Boolean {
        if (!r.before.endsWith(wantBefore) || !r.after.startsWith(wantAfter)) return false
        if (shortStart && r.before.length != wantBefore.length) return false
        if (shortEnd && r.after.length != wantAfter.length) return false
        return true
    }

    // --- the workings -------------------------------------------------------------------------------------------------

    /** One recorded replacement, worked out before it is sent. */
    private class Plan(val step: Step, val cutBefore: Int, val cutAfter: Int, val text: String, val caretBack: Int)

    /**
     * Works out the step for replacing [cutBefore] characters before the selection, the selection, and [cutAfter]
     * after it with [text], from the reading [r]. Null (and the history dropped) when it is too big to keep or would
     * take out a picture or other embedded object, which cannot be put back as text.
     */
    private fun draft(kind: Kind, r: Reading, cutBefore: Int, cutAfter: Int, text: String, caretBack: Int, open: Boolean): Plan? {
        if (cutBefore > r.before.length || cutAfter > r.after.length) { drop(true); return null }
        val removed = r.before.takeLast(cutBefore) + r.selected + r.after.take(cutAfter)
        if (removed.length + text.length > MAX_STEP || removed.contains(OBJECT)) { drop(true); return null }
        val pos = position(r)
        val step = Step(
            kind, if (pos >= 0) pos - cutBefore else -1, removed, text,
            r.before.dropLast(cutBefore).takeLast(CTX), r.after.drop(cutAfter).take(CTX),
            cutBefore, cutBefore + r.selected.length, text.length - caretBack, clock(),
        )
        step.state = if (open) StepState.OPEN else StepState.SEALED
        return Plan(step, cutBefore, cutAfter, text, caretBack)
    }

    /**
     * Sends one replacement to the field, in the order 0.9.4 sent it: the delete, the text, then the cursor.
     * [commitEmpty] types even an empty text (to delete a selection); [at] is where the change starts if known, to
     * place the cursor exactly.
     */
    private fun send(cutBefore: Int, cutAfter: Int, text: String, caretBack: Int, commitEmpty: Boolean, at: Int, batch: Boolean) {
        if (batch) port.beginBatch()
        if (cutBefore > 0 || cutAfter > 0) port.delete(cutBefore, cutAfter)
        if (text.isNotEmpty() || commitEmpty) port.commit(text)
        if (caretBack > 0) {
            if (at >= 0) port.select(at + text.length - caretBack, at + text.length - caretBack) else port.nudge(-caretBack)
        }
        if (batch) port.endBatch()
    }

    /** Writes a sent plan down as the newest step. */
    private fun record(p: Plan) {
        val s = p.step
        history.push(s)
        tip = s
        handOff = false
        edited()
        if (s.at >= 0) {
            if (p.cutBefore > 0 || p.cutAfter > 0) caret.predict(s.at, s.at + (s.selTo - s.selFrom))
            caret.predict(s.at + p.text.length)
            if (p.caretBack > 0) caret.predict(s.at + s.caret)
        } else {
            caret.unknown()
        }
    }

    /** [text] typed onto the end of the open step [s]. */
    private fun grow(s: Step, text: String) {
        // Typing on after a few backspaces: they put a slip right, and the word is still one step.
        if (shrunkStep === s) { shrunk.setLength(0); shrunkStep = null }
        s.inserted.append(text)
        s.caret = s.inserted.length
        s.time = clock()
        history.redo.clear()
        handOff = false
        edited()
        caret.advance(text.length)
    }

    /** An edit of the keyboard's own was just made: the next selection report is judged against it. */
    private fun edited() {
        lastEditAt = clock()
        judging = true
    }

    /** Ends whatever step is still growing. An answer still typing out loses its unwritten rest unless [keepAnswer]. */
    private fun sealAll(keepAnswer: Boolean = false) {
        settleShrunk()
        history.top()?.let { if (it.state == StepState.OPEN) it.state = StepState.SEALED }
        if (!keepAnswer) { fill?.redoText = null; fill = null }
    }

    /** Where the selection starts, from the field itself or from its reports. -1 if neither says. */
    private fun position(r: Reading): Int = if (r.known) r.selStart else caret.knownStart()

    /**
     * The read a new step starts with. It also settles a waiting Enter, and checks that the text before the cursor is
     * still what the last step left: if the app rewrote it, the history is dropped. Null: do not record this edit.
     */
    private fun stepRead(before: Int = STEP_BEFORE, after: Int = CTX): Reading? {
        if (caret.selectionLength() > MAX_STEP) { drop(true); return null }
        val r = timedRead(before, after) ?: return null
        if (pending != null) { settle(r); if (!recording) return null }
        val t = tip
        if (t != null && t.caret in 0..t.inserted.length) {
            val want = (t.before + t.inserted.substring(0, t.caret)).takeLast(64)
            if (!r.before.endsWith(want)) {
                drop(true)
                if (++strikes >= 3) recording = false
                return null
            }
        }
        if (r.known) caret.agree(r.selStart, r.selEnd)
        return r
    }

    /** A read, timed. A field that cannot be read, or is slow twice, is not recorded any more. */
    private fun timedRead(before: Int, after: Int): Reading? {
        val t0 = clock()
        val r = port.read(before, after)
        val took = clock() - t0
        if (r == null || (took > SLOW_MS && ++slow >= 2)) {
            forget()
            recording = false
            return null
        }
        return r
    }

    /** Drops the history. [toApp] says whether the app's undo is now the thing to ask for. */
    private fun drop(toApp: Boolean) {
        forget()
        handOff = toApp
    }

    /** Empties the history and everything that hangs off it, and stops the timers. */
    private fun forget() {
        history.clear()
        tip = null
        pending = null
        fill = null
        rewrite = null
        judging = false
        shrunk.setLength(0)
        shrunkStep = null
        runCatching { scheduler.cancel() }
    }

    /** Runs bookkeeping that must never cost a keystroke: a failure is logged and ends recording for this field. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            runCatching { log("undo history failed", t) }
            runCatching { forget() }
            recording = false
        }
    }

    /** Tells the service when an arrow lights or dims. */
    private fun tell() {
        val now = Pair(canUndo, canRedo)
        if (now != told) {
            told = now
            runCatching { onChange() }
        }
    }

    companion object {
        /** The object replacement character: a picture or other thing in the text that is not text. */
        const val OBJECT = '￼'

        /** How much is read before the cursor at the start of a step. */
        const val STEP_BEFORE = 96
        private const val LINE_BEFORE = 2000
        private const val MAX_ENTER = 64
        private const val SETTLE_MS = 250L
        private const val AUDIT_MS = 300L
        private const val AUDIT_READ = 256
        private const val ASK_READ = 64
        private const val SLOW_MS = 120L

        private const val NONE = 0
        private const val SELECTION = 1
        private const val SHRINK = 2
        private const val EXTEND = 3
        private const val FRESH = 4
    }
}
