package com.infamousvague.devkeyboard

/**
 * One run of the typing trainer (docs/trainer.md): the story to type, what the keyboard was seen to do while it was
 * typed ([TrainerRecorder]), and what that comes to ([TrainerScore]). Plain Kotlin with no Android in it.
 *
 * The trainer is the one place where the keyboard knows what was meant, so a run can say what everyday typing only
 * guesses at: which key each tap was for, which words autocorrect mended and which it spoiled, and how fast the
 * words that came out right were typed.
 */

/** A tap on a key: the [key] it was read as (a letter key's lower-case letter), and where it landed about that key's middle, in fractions of the key (0.5 is its edge; right and down are positive). */
class KeyTap(val key: Char, val fx: Float, val fy: Float)

/** What the keyboard was asked to do in a step of a run. SEEN is the text found changed with no key pressed: a word tapped in the row, or a stroke read. */
enum class KeyKind { CHAR, SPACE, ENTER, BACKSPACE, DELETE_WORD, SWIPE, SEEN }

/** One step of a run: when, what was pressed, the whole text as it stood afterwards, and for a letter where the tap landed. */
class RunStep(val at: Long, val kind: KeyKind, val text: String, val tap: KeyTap? = null)

/** Where a run stands, for the screen: each story word's fate (0 to come, 1 right, 2 wrong, 3 skipped), the word being typed, and whether the story is done. */
class TrainerProgress(val fates: IntArray, val current: Int, val tail: String, val done: Boolean, val wpm: Int, val ms: Long)

/**
 * A run as it is typed: the story, and every step the keyboard took. It only writes down; [TrainerScore] reads it
 * afterwards. [key] is told after each key the keyboard has handled, with the text as it then stands; [seen] whenever
 * the text is found to differ from what the last key left (the row and the swipe reader change it between keys).
 */
class TrainerRecorder(val story: String) {
    private val meant = TrainerText.words(story)
    val steps = ArrayList<RunStep>()

    /** The text as the last step left it. */
    var text = ""
        private set

    /** When the first key was pressed: a run's clock starts there, not when the story was shown. */
    val startedAt: Long? get() = steps.firstOrNull()?.at

    /** More steps than anyone types a story in: the run is not scored. */
    val overrun: Boolean get() = steps.size >= MAX_STEPS

    fun key(at: Long, kind: KeyKind, after: String, tap: KeyTap? = null) {
        if (steps.size >= MAX_STEPS) return
        steps += RunStep(at, kind, after, tap)
        text = after
    }

    fun seen(at: Long, now: String) {
        if (now != text) key(at, KeyKind.SEEN, now)
    }

    /** Where the run stands at [now]. */
    fun progress(now: Long): TrainerProgress {
        val tokens = TrainerText.words(text)
        val open = text.isNotEmpty() && text.last() != ' ' && text.last() != '\n'
        var held = TrainerText.words(if (open) tokens.dropLast(1) else tokens, meant, whole = false)
        var tail = if (open) tokens.lastOrNull().orEmpty() else ""
        // The last word is finished by the story's own last mark (its full stop), which no space follows.
        val last = story.lastOrNull()
        if (open && held.reached == meant.size - 1 && last != null && !TrainerText.isWordChar(last) && tail.isNotEmpty() && tail.last() == last) {
            held = TrainerText.words(tokens, meant, whole = false)
            tail = ""
        }
        val done = meant.isNotEmpty() && held.reached >= meant.size
        val start = startedAt
        val ms = if (start == null) 0L else (now - start).coerceAtLeast(0L)
        var chars = 0
        for (j in meant.indices) if (held.fates[j] == TrainerText.Fate.RIGHT) chars += meant[j].length + 1
        val wpm = if (ms < 2_000) 0 else Math.round(chars / 5f / (ms / 60_000f))
        return TrainerProgress(IntArray(meant.size) { held.fates[it].ordinal.let { f -> if (f == 3) 0 else f + 1 } }, held.reached, tail, done, wpm, ms)
    }

    companion object {
        const val MAX_STEPS = 8_000
    }
}

/** What was typed where a story word was meant, and left there. */
class WordMiss(val meant: String, val typed: String)

/** A change autocorrect made: the word as [typed], what it [made] of it, and what was [meant]. */
class Fix(val typed: String, val made: String, val meant: String)

/** The first try at a story word, for the trainer's memory of mistakes: the word [meant] and what was [typed] (both lower case, [typed] empty where it was right), and whether it stood right in the end. */
class WordTry(val meant: String, val typed: String, val right: Boolean, val rightAtLast: Boolean)

/** A letter key over a run: how many taps were meant for it, how many hit it, slipped to a neighbour or went elsewhere, and where the taps meant for it landed about its middle. */
class KeyStat {
    var taps = 0
    var hits = 0
    var slips = 0
    var wrong = 0
    /** Taps that say where the thumb aims at this key: its hits and its slips. Their sum across and down, in fractions of the key. */
    var aimed = 0
    var sumX = 0.0
    var sumY = 0.0
    val missed: Int get() = slips + wrong
    val meanX: Float get() = if (aimed == 0) 0f else (sumX / aimed).toFloat()
    val meanY: Float get() = if (aimed == 0) 0f else (sumY / aimed).toFloat()
}

/** Two letters typed one after the other: how often, how often the second went wrong (or the two were swapped), and the time between their taps. */
class PairStat {
    var taps = 0
    var misses = 0
    var timed = 0
    var ms = 0L
}

/** What a run came to. Speeds are words a minute, a word being five characters; [accuracy] is the share of letter taps that hit the key they were meant for (NaN where no tap was measured). */
class RunResult(
    val ms: Long,
    val wpm: Float,
    val gross: Float,
    val accuracy: Float,
    val words: Int,
    val right: Int,
    val firstTry: Int,
    val missed: List<WordMiss>,
    val skipped: List<String>,
    val extraWords: Int,
    val keys: Int,
    val backspaces: Int,
    val mended: List<Fix>,
    val spoiled: List<Fix>,
    val misfixed: List<Fix>,
    val left: List<WordMiss>,
    val undone: Int,
    val swiped: Int,
    val picked: Int,
    val spacesMissed: Int,
    val straySpaces: Int,
    val taps: Int,
    val hits: Int,
    val slips: Int,
    val wrongKeys: Int,
    val extraTaps: Int,
    val omitted: Int,
    val swaps: Int,
    val perKey: Map<Char, KeyStat>,
    val perPair: Map<String, PairStat>,
    val slipPairs: Map<String, Int>,
    val tries: List<WordTry>,
) {
    /** How much of the story was typed, right or wrong: a run counts from [TrainerScore.COUNTED] of it. */
    val typedShare: Float get() = if (words == 0) 0f else (words - skipped.size).toFloat() / words

    val counted: Boolean get() = typedShare >= TrainerScore.COUNTED && ms >= TrainerScore.MIN_MS

    /**
     * One number for comparing layouts: the speed of the words that came out right, times the share of taps that hit
     * their key. Typing faster by leaning on autocorrect does not win; typing slower to be careful does not either.
     */
    val score: Float get() = wpm * (if (accuracy.isNaN()) 1f else accuracy)
}

/** Reads a run's steps back into what happened to each word and each tap. */
object TrainerScore {
    /** A run counts once this much of the story's words were typed, and it took at least [MIN_MS]. */
    const val COUNTED = 0.8f
    const val MIN_MS = 3_000L

    /** A tap this near the middle of the key it pressed (of the key, each way) was aimed there: a wrong letter, not a slip of the thumb. */
    const val CENTRED = 0.2f

    /** A tap further than this from the key it was meant for (in keys) says nothing of the aim at it. */
    const val FAR = 1.5f

    /** Two taps further apart than this were not typed in one go: the time between them is not a pair's. */
    private const val PAIR_MS = 3_000L

    private class Gone(val index: Int, val tap: KeyTap)

    /** The word at the cursor while it is typed: the tap behind each of its letters (null where it has none of its own), and the taps taken back. */
    private class Open {
        val taps = ArrayList<KeyTap?>()
        val times = ArrayList<Long>()
        val gone = ArrayList<Gone>()
        var swiped = false

        fun clear() { taps.clear(); times.clear(); gone.clear(); swiped = false }

        /** A word of [n] letters that no tap of this run spelled (a stroke's, a word from the row). */
        fun blank(n: Int) { clear(); repeat(n) { taps += null; times += 0L } }
    }

    /** A word as it ended: as the taps [raw] spelled it, and as it stood straight after ([out]: autocorrect's fix, or the word chosen in the row). */
    private class Ending(
        val slot: Int, val raw: String, val out: String, val picked: Boolean, val arrived: Boolean, val swiped: Boolean,
        val taps: List<KeyTap?>, val times: List<Long>, val gone: List<Gone>,
    ) {
        var undone = false

        /** The word was opened again with these same taps (a fix undone, its space taken back): they are counted where it ends next. */
        var carried = false
    }

    private fun shared(a: String, b: String): Int {
        val m = minOf(a.length, b.length)
        var i = 0
        while (i < m && a[i] == b[i]) i++
        return i
    }

    /**
     * What [steps] made of [story]. [board] is where each letter key stands, across in key widths and by row
     * ([LayoutSpec.positions]): a tap on a neighbour of the key meant is measured from the key meant.
     */
    fun of(story: String, steps: List<RunStep>, board: Map<Char, Pair<Float, Int>>): RunResult {
        val meant = TrainerText.words(story)
        val endings = ArrayList<Ending>()
        val open = Open()
        var prev = ""
        var keys = 0
        var backspaces = 0
        var strokes = 0

        fun end(slot: Int, raw: String, out: String, picked: Boolean = false, arrived: Boolean = false) {
            endings += Ending(slot, raw, out, picked, arrived, open.swiped || (arrived && strokes > 0), open.taps.toList(), open.times.toList(), open.gone.toList())
            if (arrived && strokes > 0) strokes--
            open.clear()
        }

        /** A word stands at the cursor that this step did not spell: one that had ended, opened again; or one that arrived whole. */
        fun reopen(next: String, step: RunStep) {
            val tail = TrainerText.tail(next)
            open.clear()
            if (tail.isEmpty()) {
                // The word in quotes was tapped in the row: the fix before the cursor went back to what was typed.
                val words = next.trimEnd { !TrainerText.isWordChar(it) }
                val word = TrainerText.tail(words)
                val was = endings.lastOrNull { it.slot == TrainerText.slotAt(next, words.length - word.length) }
                if (was != null && step.kind == KeyKind.SEEN && !was.undone && was.out != was.raw && was.raw == word) was.undone = true
                return
            }
            val slot = TrainerText.slotAt(next, next.length - tail.length)
            val was = endings.lastOrNull { it.slot == slot }
            when {
                // Backspace straight after a fix puts back what was typed: the taps are that word's again.
                was != null && step.kind == KeyKind.BACKSPACE && was.out != was.raw && was.raw == tail && !was.undone -> {
                    was.undone = true; was.carried = true
                    open.taps += was.taps; open.times += was.times; open.gone += was.gone
                }
                was != null && was.out == tail && was.raw == was.out -> { was.carried = true; open.taps += was.taps; open.times += was.times; open.gone += was.gone; open.swiped = was.swiped }
                else -> open.blank(tail.length)
            }
        }

        for (step in steps) {
            when (step.kind) {
                KeyKind.BACKSPACE, KeyKind.DELETE_WORD -> backspaces++
                KeyKind.CHAR, KeyKind.SPACE, KeyKind.ENTER -> keys++
                KeyKind.SWIPE -> strokes++
                KeyKind.SEEN -> Unit
            }
            val next = step.text
            if (next == prev) continue
            val tail = TrainerText.tail(prev)
            val base = prev.length - tail.length
            if (next.length == prev.length + 1 && next.startsWith(prev) && TrainerText.isWordChar(next.last())) {
                // One more letter of the word.
                open.taps += step.tap?.takeIf { step.kind == KeyKind.CHAR && it.key == next.last().lowercaseChar() }
                open.times += step.at
            } else if (prev.length == next.length + 1 && prev.startsWith(next) && tail.isNotEmpty()) {
                // One letter of it taken back.
                val i = open.taps.size - 1
                if (i >= 0) {
                    val tap = open.taps.removeAt(i)
                    open.times.removeAt(i)
                    if (tap != null) open.gone += Gone(i, tap)
                }
            } else if (tail.isNotEmpty()) {
                // The word that was open has ended, been swapped for another, or gone.
                val rest = if (shared(prev, next) >= base) next.substring(base) else null
                val cut = rest?.indexOfLast { !TrainerText.isWordChar(it) } ?: -1
                when {
                    rest == null || rest.isEmpty() -> reopen(next, step)
                    // Another word in its place, with nothing after it: a stroke's other reading.
                    cut < 0 -> { val swiped = open.swiped; open.blank(rest.length); open.swiped = swiped }
                    else -> {
                        val out = rest.substring(0, cut + 1).trimEnd { !TrainerText.isWordChar(it) }
                        val after = rest.substring(cut + 1)
                        if (out.isNotEmpty()) end(TrainerText.slotAt(prev, base), tail, out, picked = step.kind == KeyKind.SEEN && out != tail) else open.clear()
                        // What stands after it now: nothing, or the first letter of the next word (a letter typed
                        // straight after a swiped word brings its own space), or a word that arrived whole.
                        if (after.isNotEmpty()) {
                            open.blank(after.length)
                            if (step.kind == KeyKind.CHAR && after.length == 1 && step.tap?.key == after[0].lowercaseChar()) { open.taps[0] = step.tap; open.times[0] = step.at }
                            else if (strokes > 0) { open.swiped = true; strokes-- }
                        }
                    }
                }
            } else if (next.startsWith(prev)) {
                // Nothing was open, and more stands there now: words that arrived whole (a next word tapped in the
                // row, a stroke), each ended already where something follows it.
                val added = next.substring(prev.length)
                var i = 0
                while (i < added.length) {
                    if (!TrainerText.isWordChar(added[i])) { i++; continue }
                    var j = i
                    while (j < added.length && TrainerText.isWordChar(added[j])) j++
                    val word = added.substring(i, j)
                    if (j < added.length) end(TrainerText.slotAt(next, prev.length + i), "", word, arrived = true)
                    else {
                        open.blank(word.length)
                        if (step.kind == KeyKind.CHAR && word.length == 1 && step.tap?.key == word[0].lowercaseChar()) { open.taps[0] = step.tap; open.times[0] = step.at }
                        else if (strokes > 0) { open.swiped = true; strokes-- }
                    }
                    i = j
                }
            } else {
                // Something before the cursor was taken away: the word now at the end is one that had ended.
                reopen(next, step)
            }
            val now = TrainerText.tail(next)
            if (open.taps.size != now.length) open.blank(now.length)
            prev = next
        }
        val last = TrainerText.tail(prev)
        if (last.isNotEmpty()) end(TrainerText.slotAt(prev, prev.length - last.length), last, last)

        // --- which story word each typed word stands for ---------------------------------------------------------------
        val typed = TrainerText.words(prev)
        val held = TrainerText.words(typed, meant, whole = true)

        /**
         * The word an ending was meant to be, as letters: its story word, or that and the next where the two were run
         * together (the third part says so: the space between them was missed).
         */
        fun target(e: Ending): Triple<Int, String, Boolean>? {
            val j = held.of.getOrElse(e.slot) { -1 }
            if (j < 0) return null
            val one = TrainerText.core(meant[j])
            if (held.split[j]) return Triple(j, one, false)
            val next = meant.getOrNull(j + 1)?.let(TrainerText::core)
            if (next != null && e.raw.isNotEmpty() && (e.out.contains(' ') || held.joined[j])) {
                val both = one + next
                if (TrainerText.letters(e.raw, both).cost < TrainerText.letters(e.raw, one).cost) return Triple(j, both, true)
            }
            return Triple(j, one, false)
        }

        // --- each tap, against the key it was meant for -----------------------------------------------------------------
        val perKey = HashMap<Char, KeyStat>()
        val perPair = HashMap<String, PairStat>()
        val slipPairs = HashMap<String, Int>()
        var taps = 0; var hits = 0; var slips = 0; var wrongKeys = 0; var extraTaps = 0; var omitted = 0; var swaps = 0
        var spacesMissed = 0

        fun measure(want: Char, tap: KeyTap) {
            val stat = perKey.getOrPut(want) { KeyStat() }
            stat.taps++; taps++
            if (tap.key == want) {
                stat.hits++; hits++
                if (kotlin.math.abs(tap.fx) <= FAR && kotlin.math.abs(tap.fy) <= FAR) { stat.aimed++; stat.sumX += tap.fx; stat.sumY += tap.fy }
                return
            }
            val p = board[tap.key]
            val m = board[want]
            val dx = if (p != null && m != null) tap.fx + (p.first - m.first) else Float.NaN
            val dy = if (p != null && m != null) tap.fy + (p.second - m.second) else Float.NaN
            // A neighbour of the key meant, and not pressed in its middle: the thumb slipped. Anything else was a
            // wrong letter (a misspelling, a misreading), which says nothing of where the thumb lands.
            val near = p != null && m != null && kotlin.math.abs(p.first - m.first) <= 1.01f && kotlin.math.abs(p.second - m.second) <= 1
            val deliberate = kotlin.math.abs(tap.fx) < CENTRED && kotlin.math.abs(tap.fy) < CENTRED
            if (near && !deliberate && kotlin.math.abs(dx) <= FAR && kotlin.math.abs(dy) <= FAR) {
                stat.slips++; slips++
                stat.aimed++; stat.sumX += dx; stat.sumY += dy
                val pair = "${tap.key}>$want"
                slipPairs[pair] = (slipPairs[pair] ?: 0) + 1
            } else {
                stat.wrong++; wrongKeys++
            }
        }

        fun pair(a: Char, b: Char, missed: Boolean, ms: Long = -1L) {
            val x = a.lowercaseChar()
            val y = b.lowercaseChar()
            if (!x.isLetter() || !y.isLetter()) return
            val stat = perPair.getOrPut("$x$y") { PairStat() }
            stat.taps++
            if (missed) stat.misses++
            if (ms in 1 until PAIR_MS) { stat.timed++; stat.ms += ms }
        }

        for (e in endings) {
            // A word no tap of this run spelled (a stroke's, one from the row) says nothing of the taps.
            if (e.raw.isEmpty() || e.carried || (e.taps.all { it == null } && e.gone.isEmpty())) continue
            val (j, want, both) = target(e) ?: continue
            if (both) spacesMissed++
            val a = TrainerText.letters(e.raw, want, prefix = e.picked)
            for (i in e.raw.indices) {
                val tap = e.taps.getOrNull(i)
                when (a.kinds[i]) {
                    TrainerText.Kind.EXTRA -> { if (tap != null) { extraTaps++; taps++ } }
                    else -> {
                        val c = want[a.meant[i]].lowercaseChar()
                        if (tap != null && c.isLetter()) measure(c, tap)
                    }
                }
                if (i == 0) continue
                val k = a.kinds[i]
                val before = a.kinds[i - 1]
                if (k == TrainerText.Kind.SWAPPED && before == TrainerText.Kind.SWAPPED && a.meant[i] == a.meant[i - 1] - 1) {
                    swaps++
                    pair(want[a.meant[i]], want[a.meant[i - 1]], missed = true)
                } else if ((k == TrainerText.Kind.MATCH || k == TrainerText.Kind.WRONG) && (before == TrainerText.Kind.MATCH || before == TrainerText.Kind.WRONG) && a.meant[i] == a.meant[i - 1] + 1) {
                    // Two letters each typed by a tap of its own: only those say how the pair goes.
                    if (e.taps.getOrNull(i) != null && e.taps.getOrNull(i - 1) != null) pair(want[a.meant[i - 1]], want[a.meant[i]], missed = k == TrainerText.Kind.WRONG, ms = e.times[i] - e.times[i - 1])
                }
            }
            // A tap taken back stood for whatever stands in its place now.
            for (g in e.gone) {
                if (g.index >= e.raw.length) { extraTaps++; taps++; continue }
                val k = a.kinds[g.index]
                if (k == TrainerText.Kind.EXTRA) { extraTaps++; taps++; continue }
                val c = want[a.meant[g.index]].lowercaseChar()
                if (c.isLetter()) measure(c, g.tap)
            }
            // A letter of the word that no tap stood for (not where one word was typed as two: its other half has them).
            if (!held.split[j] && !e.picked) for (o in a.omitted) {
                if (!want[o].isLetter()) continue
                omitted++
                if (o > 0) pair(want[o - 1], want[o], missed = true)
            }
        }

        // --- the words -----------------------------------------------------------------------------------------------------
        val missed = ArrayList<WordMiss>()
        val skipped = ArrayList<String>()
        var straySpaces = 0
        for (j in meant.indices) {
            when (held.fates[j]) {
                TrainerText.Fate.WRONG -> {
                    val at = held.typed[j]
                    if (held.split[j]) straySpaces++
                    // The second of two words run together was said with the first.
                    if (j > 0 && held.joined[j - 1]) continue
                    val said = if (held.split[j]) typed[at] + " " + typed[at + 1] else typed[at]
                    missed += WordMiss(if (held.joined[j]) meant[j] + " " + meant[j + 1] else meant[j], said)
                }
                TrainerText.Fate.SKIPPED -> skipped += meant[j]
                else -> Unit
            }
        }

        val mended = ArrayList<Fix>()
        val spoiled = ArrayList<Fix>()
        val misfixed = ArrayList<Fix>()
        val left = ArrayList<WordMiss>()
        var undone = 0
        var swiped = 0
        var picked = 0
        val tries = ArrayList<WordTry>()
        val tried = HashSet<Int>()
        var firstTry = 0
        for (e in endings) {
            if (e.swiped) swiped++ else if (e.picked || e.arrived) picked++
            val j = held.of.getOrElse(e.slot) { -1 }
            if (j < 0) continue
            val one = TrainerText.core(meant[j])
            // Two words where one was typed: autocorrect parted them, and the pair is what was meant.
            val want = if (e.out.contains(' ') && j + 1 < meant.size) one + " " + TrainerText.core(meant[j + 1]) else one
            if (e.undone) undone++
            if (!e.picked && !e.arrived && e.raw.isNotEmpty()) {
                if (e.out != e.raw) {
                    val fix = Fix(e.raw, e.out, want)
                    when {
                        e.out == want -> mended += fix
                        e.raw == want -> spoiled += fix
                        else -> misfixed += fix
                    }
                } else if (!e.raw.equals(want, ignoreCase = true)) left += WordMiss(want, e.raw)
            }
            // The first try at each story word, for what is remembered of mistakes: a word as the taps spelled it.
            if (e.raw.isEmpty() || e.swiped || !tried.add(j)) continue
            val right = if (e.picked) one.startsWith(e.raw, ignoreCase = true) else e.raw.equals(one, ignoreCase = true)
            if (right && e.gone.isEmpty()) firstTry++
            val form = if (right || e.picked) "" else e.raw.lowercase()
            tries += WordTry(one.lowercase(), form, right, held.fates[j] == TrainerText.Fate.RIGHT)
        }

        // --- the speed -----------------------------------------------------------------------------------------------------
        val ms = if (steps.isEmpty()) 0L else (steps.last().at - steps.first().at).coerceAtLeast(0L)
        val minutes = ms / 60_000f
        var chars = 0
        for (j in meant.indices) if (held.fates[j] == TrainerText.Fate.RIGHT) chars += meant[j].length + 1
        if (chars > 0) chars--
        val wpm = if (ms < 1_000) 0f else chars / 5f / minutes
        val gross = if (ms < 1_000) 0f else prev.length / 5f / minutes
        return RunResult(
            ms = ms, wpm = wpm, gross = gross, accuracy = if (taps == 0) Float.NaN else hits.toFloat() / taps,
            words = meant.size, right = held.right, firstTry = firstTry, missed = missed, skipped = skipped, extraWords = held.of.count { it < 0 },
            keys = keys, backspaces = backspaces, mended = mended, spoiled = spoiled, misfixed = misfixed, left = left, undone = undone,
            swiped = swiped, picked = picked, spacesMissed = spacesMissed, straySpaces = straySpaces,
            taps = taps, hits = hits, slips = slips, wrongKeys = wrongKeys, extraTaps = extraTaps, omitted = omitted, swaps = swaps,
            perKey = perKey, perPair = perPair, slipPairs = slipPairs, tries = tries,
        )
    }
}
