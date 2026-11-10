package com.infamousvague.devkeyboard

/**
 * The Undo and redo plugin's history: a list of what the keyboard itself did to one text field, newest last, and the
 * list of what undo took back. Nothing here knows about Android: [FieldEditor] fills it and plays it back through the
 * field, and android/app/src/test checks it on a desk.
 *
 * It lives in memory for one field and is never saved.
 */

/** What a step was, so a tap on Undo takes back "a word", "a paste" or "an answer" and never half of one. */
enum class Kind { TYPING, DELETE, ENTER, PAIR, CORRECTION, SUGGESTION, SWIPE, PASTE, EMOJI, GIF, SNIPPET, WRAP, PREFIX, AI, DICTATION }

/** A step still growing (a word being typed, a backspace held, an answer typing out), finished, or waiting on the app (Enter). */
enum class StepState { OPEN, SEALED, PENDING }

/**
 * One thing the keyboard did: at [at] in the field, [removed] was taken out and [inserted] put in its place.
 *
 * - [at]: where the first changed character sits, counted from the field's start, or -1 where the field does not say.
 * - [before], [after]: up to [EditHistory.CTX] characters either side of the change as they were when it began. Fewer
 *   than that means the field started or ended there, and must still do so for the step to be undone.
 * - [selFrom], [selTo]: the selection before the step, counted from [at] in the old text, so undo can put it back.
 * - [caret]: the cursor after the step, counted from [at] in the new text.
 * - [redoText]: for an AI answer undone while it was still typing out, the whole answer, which redo writes.
 * - [time]: when it last grew, for grouping by pauses.
 */
class Step(
    val kind: Kind,
    var at: Int,
    removed: String,
    inserted: String,
    var before: String,
    var after: String,
    var selFrom: Int,
    var selTo: Int,
    var caret: Int,
    var time: Long,
) {
    val removed = StringBuilder(removed)
    val inserted = StringBuilder(inserted)
    var redoText: String? = null
    var state = StepState.SEALED

    /** How much memory the step holds, in UTF-16 units: its texts, its contexts and a waiting answer. */
    fun size(): Int = removed.length + inserted.length + before.length + after.length + (redoText?.length ?: 0)
}

/** The two stacks, their limits, and the rules for what joins a step that is still open. */
class EditHistory(private val maxSteps: Int = MAX_STEPS, private val maxUnits: Int = MAX_UNITS) {
    /** What can be undone, newest last. */
    val undo = ArrayList<Step>()

    /** What undo took back, the next to redo last. */
    val redo = ArrayList<Step>()

    /** The newest step, or null. */
    fun top(): Step? = undo.lastOrNull()

    /** A new edit: it goes on top, and whatever was waiting to be redone is gone, as in any editor. */
    fun push(step: Step) {
        redo.clear()
        undo.add(step)
        trim()
    }

    /** Forgets everything. */
    fun clear() {
        undo.clear()
        redo.clear()
    }

    /** Characters held by both stacks. */
    fun units(): Int = undo.sumOf { it.size() } + redo.sumOf { it.size() }

    /** Keeps to the limits: the oldest undo steps go first, then the oldest redo steps. */
    fun trim() {
        while (undo.size + redo.size > maxSteps && undo.size > 1) undo.removeAt(0)
        while (undo.size + redo.size > maxSteps && redo.size > 1) redo.removeAt(0)
        var held = units()
        while (held > maxUnits && undo.size > 1) held -= undo.removeAt(0).size()
        while (held > maxUnits && redo.size > 1) held -= redo.removeAt(0).size()
        // One step alone over the limit cannot be kept at all.
        if (held > maxUnits) clear()
    }

    companion object {
        /** Characters of the text either side of a change kept to recognise the place again. */
        const val CTX = 16
        const val MAX_STEPS = 100
        const val MAX_UNITS = 200_000

        /** One step may not be longer than this: a longer edit is made but not kept. */
        const val MAX_STEP = 50_000

        /** A typing step, or a run of backspaces, is cut here so one step is never a whole essay. */
        const val MAX_RUN = 2_000

        /** A pause this long ends a burst of typing. */
        const val TYPING_PAUSE_MS = 3_000L

        /** A pause this long ends a run of backspaces. */
        const val DELETE_PAUSE_MS = 2_000L

        /** Whether [text] has anything in it but spaces and line ends. */
        private fun hasInk(text: CharSequence): Boolean = text.any { !it.isWhitespace() }

        /**
         * Whether [text] begins a new word after [typed]: [typed] already has a word and its space, and [text] is not
         * a space. So "hello " then "w" is a new step, while a space, a comma or a full stop stays with its word, and
         * a lone space joins the word typed after it.
         */
        fun startsWord(typed: CharSequence, text: String): Boolean =
            hasInk(text) && typed.isNotEmpty() && typed.last().isWhitespace() && hasInk(typed)

        /**
         * Whether [text], typed at [now] with the cursor still at the end of [step], belongs to it: the step is typing
         * that is still open, the pause was short, it is not too long, and [text] does not start a new word.
         */
        fun joinsTyping(step: Step, text: String, now: Long): Boolean =
            step.kind == Kind.TYPING && step.state == StepState.OPEN && step.caret == step.inserted.length &&
                now - step.time < TYPING_PAUSE_MS && step.inserted.length + text.length <= MAX_RUN && !startsWord(step.inserted, text)

        /** Whether one more backspace at [now] belongs to [step], a run of them still going. */
        fun joinsDelete(step: Step, now: Long): Boolean =
            step.kind == Kind.DELETE && step.state == StepState.OPEN && step.inserted.isEmpty() &&
                step.selFrom == step.removed.length && step.selTo == step.removed.length &&
                now - step.time < DELETE_PAUSE_MS && step.removed.length < MAX_RUN

        /**
         * How many UTF-16 units one backspace takes from the end of [before]: one code point (both halves of a
         * surrogate pair), or the whole of an emoji made of several (a family, a flag, a keycap, a skin tone), whose
         * length [clusterBefore] gives. This is the stock Android keyboard's rule.
         */
        fun unitBefore(before: String, clusterBefore: (String) -> Int): Int {
            if (before.isEmpty()) return 0
            val last = before.codePointBefore(before.length)
            val one = Character.charCount(last)
            val emojiShaped = Character.isSupplementaryCodePoint(last) || last == 0x200D || last == 0xFE0F || last == 0xFE0E || last == 0x20E3
            if (!emojiShaped) return one
            val cluster = runCatching { clusterBefore(before) }.getOrDefault(one)
            return cluster.coerceIn(one, before.length)
        }
    }
}
