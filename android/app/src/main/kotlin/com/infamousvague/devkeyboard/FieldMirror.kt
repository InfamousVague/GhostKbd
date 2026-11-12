package com.infamousvague.devkeyboard

/**
 * The text around the cursor, as far as the keyboard can know it without asking the app again (Matt, 2026-11-12: "do
 * a performance pass on the keyboard to make sure everything is as fast as possible").
 *
 * Asking the app for its text is the one slow thing a key press does: the keyboard stops until the app's own main
 * thread answers, and an app is at its busiest just after a letter has been typed into it. Timed on the emulator in
 * another app's field (Perf.kt), every letter stopped the keyboard about 15 ms to ask whether a capital is due, 10 ms
 * to read the words for the row and as long again for the row's other questions, and every space about 64 ms, asking
 * four times before it could correct the word. A key pressed in that time waits its turn.
 *
 * So one reading is kept, and the keyboard's own typing is done to it as it is done to the field: what the keys
 * commit is put on the end of what stands before the cursor, what they delete is taken off it. A question that the
 * reading can answer is answered from it ([textBefore], [textAfter], [selection], [capsMode]) and the app is not asked.
 *
 * The reading is only ever a guess at what the app holds, so it is used on these terms and no others:
 *  - It starts from a real reading that says where the cursor is ([read]: Android 12's getSurroundingText, which the
 *    row's refresh makes a moment after every edit), and each of those readings is held against what was guessed:
 *    twice wrong in a field and the field is never guessed at again ([MAX_STRIKES]).
 *  - The app must be seen to report the cursor where the keys' typing put it ([reported]) before any guess is used,
 *    and any report that is not one of those (a tap in the text, the app's own edit, a selection) drops the reading.
 *  - Anything done to the field that is not plain typing at the cursor or a delete around it drops it ([drop]).
 *  - It goes stale: a guess is used only within [IDLE_MS] of the reading or of the last key that was put on it, so
 *    whatever happened in the app while the keys were at rest is read afresh.
 * Where the reading cannot answer, the app is asked, exactly as it always was.
 *
 * A reading just made is exact rather than a guess: inside [during] (the row's refresh, straight after its one
 * reading) it answers on no terms at all, so the refresh asks once where it asked three or four times.
 *
 * Nothing here touches Android (MirrorConnection.kt is the part that does): FieldMirrorTest tries it off the phone.
 * It is used on the main thread only.
 */
class FieldMirror(private val clock: () -> Long) {
    private val before = StringBuilder()
    private var beforeWhole = false
    private var after = ""
    private var afterWhole = false
    private var selected = ""

    /** Where the cursor is in the whole field, or -1 where the app's reading did not say. */
    private var caret = -1

    /** There is a reading at all; how many of the keyboard's own edits have been done to it; when it, or the last of those, was made. */
    private var held = false
    private var edits = 0
    private var touchedAt = 0L

    /**
     * The cursor positions the keyboard's edits lead to, which the app has yet to report, and for each the moment it
     * stops being waited for ([awaitedBy]). An app does not report a cursor that ends an edit where it began (a
     * letter swapped for another, as every Korean syllable is built), so some of these never come; and one left
     * standing would let a later tap on that very spot pass for the keyboard's own doing. So once a real reading
     * shows the edits done, their reports have [REPORT_MS] left to arrive.
     */
    private val awaited = ArrayDeque<Int>()
    private val awaitedBy = ArrayDeque<Long>()

    /** The app has reported a position that was awaited: it reports the cursor, and where the keys' typing puts it. */
    private var reports = false

    /** How often a real reading in this field was not what was guessed. */
    var strikes = 0
        private set

    /** A reading has just been made, and nothing has happened since ([during]). */
    private var exact = false

    /**
     * Moves on whenever the field may have: at every edit of the keyboard's own, every report of the cursor that was
     * not awaited, every field started or left. A reading asked for off the main thread (the row's refresh) is of the
     * field as it is now only if this is where it was when the app was asked.
     */
    var stamp = 0
        private set

    /** How often the app agreed with [capsMode] where it mattered, and whether it ever did not. */
    private var capsAgreed = 0
    private var capsAsk = false

    /** Another field: nothing is known about it or about the app that holds it. */
    fun newField() {
        drop()
        reports = false
        strikes = 0
        capsAgreed = 0
        capsAsk = false
    }

    /** The reading is forgotten. What the field has shown of itself (its strikes, its reports) is kept. */
    fun drop() {
        stamp++
        held = false
        exact = false
        edits = 0
        awaited.clear()
        awaitedBy.clear()
        before.setLength(0)
        after = ""
        selected = ""
        caret = -1
    }

    /**
     * A real reading of the field: the text [before] the cursor, what is [selected] and the text [after] it, where
     * the selection [start]s and [end]s in the whole field (-1: the app did not say), and how much was asked for
     * ([askedBefore], [askedAfter]: less than that came back only where there is no more). [fromStart]: the reading
     * is known to begin at the field's own start. Where the keyboard had been guessing, the guess is held against it.
     */
    fun read(before: String, selected: String, after: String, start: Int, end: Int, askedBefore: Int, askedAfter: Int, fromStart: Boolean = false) {
        val whole = fromStart || before.length < askedBefore
        val sameText = sameEnd(this.before, before) && sameStart(this.after, after)
        if (guessing()) {
            val wrong = !sameText || selected.isNotEmpty() || (end >= 0 && end != caret)
            if (wrong) strikes++
        }
        // The same field with the cursor where it was, seen again by a shorter reading (the undo history's own are
        // short): what the longer one held beyond it still stands, unless this one says the field ends sooner.
        val again = held && this.selected.isEmpty() && selected.isEmpty() && caret >= 0 && end == caret && sameText
        val expected = if (held) caret else -1
        if (!(again && this.before.length >= before.length && !(whole && this.before.length > before.length))) {
            this.before.setLength(0)
            this.before.append(before)
            beforeWhole = whole
        }
        val ends = after.length < askedAfter
        if (!(again && this.after.length >= after.length && !(ends && this.after.length > after.length))) {
            this.after = after
            afterWhole = ends
        }
        this.selected = selected
        caret = if (selected.isEmpty()) end else -1
        // The reports still on their way are for edits this reading already shows: they stay awaited a moment longer
        // where they lead to where the cursor is, and are no longer anything to go by where they do not.
        val now = clock()
        if (caret < 0 || expected != caret) {
            awaited.clear()
            awaitedBy.clear()
        } else {
            for (i in awaitedBy.indices) awaitedBy[i] = minOf(awaitedBy[i], now + REPORT_MS)
        }
        held = true
        edits = 0
        touchedAt = now
    }

    /** [block] is run straight after a reading, with nothing done in between: the reading is the field itself for as long as it runs. */
    inline fun <T> during(block: () -> T): T {
        exactNow(true)
        try { return block() } finally { exactNow(false) }
    }

    @PublishedApi internal fun exactNow(on: Boolean) { exact = on }

    /** The keys committed [text] at the cursor, which was left [newCursorPosition] as commitText counts it (1: straight after the text). */
    fun committed(text: CharSequence, newCursorPosition: Int) {
        stamp++
        if (!held) return
        if (selected.isNotEmpty() || caret < 0 || newCursorPosition != 1 || rested()) { drop(); return }
        if (text.isEmpty()) return
        before.append(text)
        if (before.length > KEEP_BEFORE) { before.delete(0, before.length - KEEP_BEFORE); beforeWhole = false }
        caret += text.length
        await(caret)
        edited()
    }

    /** The keys deleted [beforeLength] characters before the cursor and [afterLength] after it (deleteSurroundingText). */
    fun deleted(beforeLength: Int, afterLength: Int) {
        stamp++
        if (!held) return
        if (selected.isNotEmpty() || caret < 0 || beforeLength < 0 || afterLength < 0 || rested()) { drop(); return }
        var b = beforeLength
        var a = afterLength
        // More than is known to be there: all of it where the reading reaches the field's edge, else nothing is known.
        if (b > before.length) { if (beforeWhole) b = before.length else { drop(); return } }
        if (a > after.length) { if (afterWhole) a = after.length else { drop(); return } }
        val from = before.length - b
        // A delete that would part the two halves of one character is each app's own to settle.
        if (b > 0 && splits(before, from)) { drop(); return }
        if (b > 0 && from == 0 && !beforeWhole && before[0].isLowSurrogate()) { drop(); return }
        if (a > 0 && splits(after, a)) { drop(); return }
        before.setLength(from)
        after = after.substring(a)
        if (b > 0) { caret -= b; await(caret) }
        if (b > 0 || a > 0) edited()
    }

    /**
     * The keys have been at rest too long for the reading to be gone by. A key typed then does not make it young
     * again: it is dropped, that key's own questions go to the app, and the reading made after it is the next start.
     */
    private fun rested() = clock() - touchedAt > IDLE_MS

    private fun await(position: Int) {
        awaited.addLast(position)
        awaitedBy.addLast(Long.MAX_VALUE)
        while (awaited.size > MAX_AWAITED) { awaited.removeFirst(); awaitedBy.removeFirst() }
    }

    private fun edited() {
        edits++
        touchedAt = clock()
    }

    /**
     * The app reports its selection from [start] to [end], and whether it has a composing region. One of the
     * positions the keys' own edits lead to is the keyboard's own doing; anything else is not, and the reading goes.
     */
    fun reported(start: Int, end: Int, composing: Boolean) {
        // With no reading there is no telling whose doing this is: the field has moved.
        if (!held) { stamp++; return }
        if (start != end || composing || caret < 0) { drop(); return }
        // Reports that had their time and did not come are not coming.
        val now = clock()
        while (awaitedBy.isNotEmpty() && awaitedBy.first() < now) { awaited.removeFirst(); awaitedBy.removeFirst() }
        val at = awaited.indexOf(end)
        if (at >= 0) {
            repeat(at + 1) { awaited.removeFirst(); awaitedBy.removeFirst() }
            reports = true
            return
        }
        if (awaited.isEmpty() && end == caret) return
        drop()
    }

    /** A real answer to "the [n] characters before the cursor" came back as [text]: the reading must not say otherwise. */
    fun sawBefore(text: String, n: Int) {
        if (!held || selected.isNotEmpty()) return
        if (!sameEnd(before, text) || (text.length < n && before.length > text.length)) { if (guessing()) strikes++; drop() }
    }

    /** The same for the [n] characters after it. */
    fun sawAfter(text: String, n: Int) {
        if (!held) return
        if (!sameStart(after, text) || (text.length < n && after.length > text.length)) { if (guessing()) strikes++; drop() }
    }

    /** The reading has the keyboard's own edits on it: it is a guess, to be held against the next real reading. */
    private fun guessing() = held && edits > 0

    /**
     * Whether the reading may answer for the field now: it has just been made ([during]), or it is a guess on the
     * terms at the head of this file.
     */
    private fun answers(): Boolean {
        if (!held) return false
        if (exact && edits == 0) return true
        return selected.isEmpty() && caret >= 0 && reports && strikes < MAX_STRIKES && !rested()
    }

    /** The [n] characters before the cursor (fewer at the field's start), or null where the app must be asked. */
    fun textBefore(n: Int): String? {
        if (n < 0 || !answers()) return null
        if (n > before.length && !beforeWhole) return null
        return before.substring(maxOf(0, before.length - n))
    }

    /** The [n] characters after the cursor (fewer at the field's end), or null where the app must be asked. */
    fun textAfter(n: Int): String? {
        if (n < 0 || !answers()) return null
        if (n > after.length && !afterWhole) return null
        return after.substring(0, minOf(n, after.length))
    }

    /** What is selected ("" for nothing), or null where the app must be asked. */
    fun selection(): String? = if (answers()) selected else null

    /**
     * Whether a capital is due at the cursor, as getCursorCapsMode says it for [reqModes] (the field's input type),
     * or null where the app must be asked: the reading cannot answer, or cannot see far enough back.
     */
    fun capsMode(reqModes: Int): Int? {
        if (capsAsk || !answers() || selected.isNotEmpty()) return null
        return CapsMode.of(before, beforeWhole, reqModes)
    }

    /**
     * Whether [local], [capsMode]'s answer, should be held against the app's own before it is gone by. Most apps
     * answer with the same sum Android's own text field does, but an app may answer as it likes, so it is asked the
     * first [CAPS_CHECKS] times the answer matters (a capital is due, or the cursor follows a space or a line's
     * start, the only places one could be), and [capsChecked] is told what it said.
     */
    fun capsUnproven(local: Int): Boolean =
        capsAgreed < CAPS_CHECKS && (local != 0 || before.isEmpty() || before[before.length - 1].isWhitespace())

    /** The app's own answer, [real], where [capsMode] said [local]: once they differ, the app is always asked in this field. */
    fun capsChecked(local: Int, real: Int) {
        if ((local != 0) == (real != 0)) capsAgreed++ else capsAsk = true
    }

    private fun sameEnd(a: CharSequence, b: CharSequence): Boolean {
        val n = minOf(a.length, b.length)
        for (i in 1..n) if (a[a.length - i] != b[b.length - i]) return false
        return true
    }

    private fun sameStart(a: CharSequence, b: CharSequence): Boolean {
        val n = minOf(a.length, b.length)
        for (i in 0 until n) if (a[i] != b[i]) return false
        return true
    }

    /** Whether a cut at [at] in [text] falls between the two halves of a surrogate pair. */
    private fun splits(text: CharSequence, at: Int): Boolean =
        at > 0 && at < text.length && text[at].isLowSurrogate() && text[at - 1].isHighSurrogate()

    companion object {
        /** A guess is used for this long after the reading or the last key put on it (ms). */
        const val IDLE_MS = 2500L
        /** Once a reading shows an edit done, the app's report of where it left the cursor is waited for this long (ms). */
        const val REPORT_MS = 250L
        /** A field's readings were not what was guessed this often: it is not guessed at again. */
        const val MAX_STRIKES = 2
        /** The app's own word on capitals is taken this many times where it matters before the keyboard's sum is trusted. */
        const val CAPS_CHECKS = 3
        /** As much of the text before the cursor as is kept while it grows. */
        const val KEEP_BEFORE = 4096
        private const val MAX_AWAITED = 64
    }
}

/**
 * Whether a capital is due at the end of a text, by the sum Android's own text fields answer getCursorCapsMode with
 * (TextUtils.getCapsMode, which a test off the phone cannot call): all capitals where the field asks for them; a
 * capital at a paragraph's start; at each word's start where the field capitalises words; and after a sentence's end
 * and a space where it capitalises sentences, but not after an abbreviation with a full stop inside it.
 */
object CapsMode {
    const val CHARACTERS = 0x1000
    const val WORDS = 0x2000
    const val SENTENCES = 0x4000

    /** The mode at the end of [text], of which [whole] says whether it starts where the field does. Null where the answer lies before what [text] holds. */
    fun of(text: CharSequence, whole: Boolean, reqModes: Int): Int? {
        var mode = 0
        if (reqModes and CHARACTERS != 0) mode = mode or CHARACTERS
        if (reqModes and (WORDS or SENTENCES) == 0) return mode
        // Back over opening punctuation: a quote or a bracket just typed does not hide the sentence's start.
        var i = text.length
        while (i > 0) {
            val c = text[i - 1]
            if (c != '"' && c != '\'' && Character.getType(c) != Character.START_PUNCTUATION.toInt()) break
            i--
        }
        // A paragraph's start, spaces or not.
        var j = i
        while (j > 0 && (text[j - 1] == ' ' || text[j - 1] == '\t')) j--
        if (j == 0 && !whole) return null
        if (j == 0 || text[j - 1] == '\n') return mode or WORDS
        // A word's start, where words are what is capitalised.
        if (reqModes and SENTENCES == 0) return if (i != j) mode or WORDS else mode
        // No space: the middle of a word, or straight after its full stop.
        if (i == j) return mode
        // Back over closing punctuation: the sentence ended inside the quote or the bracket.
        while (j > 0) {
            val c = text[j - 1]
            if (c != '"' && c != '\'' && Character.getType(c) != Character.END_PUNCTUATION.toInt()) break
            j--
        }
        if (j == 0) return if (whole) mode else null
        val end = text[j - 1]
        if (end != '.' && end != '?' && end != '!') return mode
        if (end == '.') {
            // A word with a full stop inside it as well as at its end is an abbreviation (e.g., U.S.), not a sentence's end.
            var k = j - 2
            while (k >= 0) {
                val c = text[k]
                if (c == '.') return mode
                if (!Character.isLetter(c)) break
                k--
            }
            if (k < 0 && !whole) return null
        }
        return mode or SENTENCES
    }
}
