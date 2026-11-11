package com.infamousvague.devkeyboard

/**
 * The typing trainer's reading of what was typed against what was meant (docs/trainer.md; Matt, 2026-11-11: "a
 * typeracer style thing where we type stories out and it tracks everything like wpm and missed words"). Plain Kotlin
 * with no Android in it, so the unit tests and tool/trainer_sim.sh use it on a desk.
 *
 * Two alignments, both the smallest number of changes that turns one into the other:
 * - [letters] holds a word as typed against the word meant, letter by letter: which typed letter stands for which
 *   meant one, which is one too many, which two are swapped, and which meant letters have none. It is how every tap
 *   of a run gets the key it was meant for.
 * - [words] holds the words typed against the words of the story: a word skipped costs that word and no more, two
 *   run together ("inthe") stand for both, and one split by a stray space ("th e") stands for one.
 */
object TrainerText {
    /** A character of a word: a letter, a digit, or the apostrophe inside one. Everything else ends a word. */
    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '’'

    /** The words of [text]: what stands between its spaces, each with the punctuation that touches it. */
    fun words(text: String): List<String> = text.split(' ', '\n').filter { it.isNotEmpty() }

    /** The run of word characters [text] ends in: the word being typed. Empty after a space or a full stop. */
    fun tail(text: String): String {
        var i = text.length
        while (i > 0 && isWordChar(text[i - 1])) i--
        return text.substring(i)
    }

    /** A word without the punctuation around it: "wife." is "wife", "don't" stays. */
    fun core(word: String): String = word.trim { !isWordChar(it) }

    /**
     * Which word of [text] the character at [at] belongs to, counting from 0: the words wholly before it, and the one
     * it is inside or would begin.
     */
    fun slotAt(text: String, at: Int): Int {
        var n = 0
        var inWord = false
        for (i in 0 until at.coerceAtMost(text.length)) {
            val gap = text[i] == ' ' || text[i] == '\n'
            if (!gap && !inWord) n++
            inWord = !gap
        }
        return if (inWord) n - 1 else n
    }

    // --- letters ----------------------------------------------------------------------------------------------------

    /** What a typed letter was, against the word meant. */
    enum class Kind { MATCH, WRONG, EXTRA, SWAPPED }

    /**
     * A typed word held against the one meant. For each typed letter: [kinds] (it is the letter meant; it stands
     * where another was meant; it is one too many; it and its neighbour are in each other's places) and [meant], the
     * place in the meant word it stands for (-1 for one too many). [omitted] are the places of the meant word nothing
     * was typed for. [cost] is how many changes apart the two are.
     */
    class Letters(val cost: Int, val kinds: Array<Kind>, val meant: IntArray, val omitted: IntArray) {
        /** How far apart the two words are for their length: 0 the same, 1 nothing alike. */
        fun distance(typedLength: Int, meantLength: Int): Float = if (maxOf(typedLength, meantLength) == 0) 0f else (cost.toFloat() / maxOf(typedLength, meantLength)).coerceAtMost(1f)
    }

    /**
     * [typed] against [meant], capitals aside. With [prefix] the typed letters are held against only as much of the
     * meant word as they best account for: a word begun by hand and finished by a tap in the row is no word with its
     * end missing.
     */
    fun letters(typed: String, meant: String, prefix: Boolean = false): Letters {
        val a = typed.lowercase()
        val b = meant.lowercase()
        val n = a.length
        val m = b.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            var v = d[i - 1][j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1)
            if (d[i - 1][j] + 1 < v) v = d[i - 1][j] + 1
            if (d[i][j - 1] + 1 < v) v = d[i][j - 1] + 1
            if (i >= 2 && j >= 2 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] && a[i - 1] != a[i - 2] && d[i - 2][j - 2] + 1 < v) v = d[i - 2][j - 2] + 1
            d[i][j] = v
        }
        // Where the typed letters stop in the meant word: its end, or, for a word only begun, wherever costs least
        // (the nearest to as many letters as were typed, where two places cost the same).
        var end = m
        if (prefix) {
            var best = Int.MAX_VALUE
            for (j in 0..m) {
                val c = d[n][j]
                if (c < best || (c == best && kotlin.math.abs(j - n) < kotlin.math.abs(end - n))) { best = c; end = j }
            }
        }
        val kinds = Array(n) { Kind.EXTRA }
        val at = IntArray(n) { -1 }
        val omitted = ArrayList<Int>()
        var i = n
        var j = end
        while (i > 0 || j > 0) {
            val here = d[i][j]
            if (i > 0 && j > 0 && here == d[i - 1][j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1)) {
                kinds[i - 1] = if (a[i - 1] == b[j - 1]) Kind.MATCH else Kind.WRONG
                at[i - 1] = j - 1
                i--; j--
            } else if (i >= 2 && j >= 2 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] && a[i - 1] != a[i - 2] && here == d[i - 2][j - 2] + 1) {
                // Each of the two was the right key, pressed in the other's turn.
                kinds[i - 1] = Kind.SWAPPED; at[i - 1] = j - 2
                kinds[i - 2] = Kind.SWAPPED; at[i - 2] = j - 1
                i -= 2; j -= 2
            } else if (i > 0 && here == d[i - 1][j] + 1) {
                kinds[i - 1] = Kind.EXTRA
                i--
            } else {
                omitted += j - 1
                j--
            }
        }
        return Letters(d[n][end], kinds, at, omitted.reversed().toIntArray())
    }

    /** How many changes apart [a] and [b] are, capitals aside. */
    fun apart(a: String, b: String): Int {
        if (a.equals(b, ignoreCase = true)) return 0
        return letters(a, b).cost
    }

    // --- words ------------------------------------------------------------------------------------------------------

    /** What became of a word of the story. */
    enum class Fate { RIGHT, WRONG, SKIPPED, TODO }

    /**
     * The words typed held against the story's. For each word of the story: its [fates], and [typed], the place of
     * the typed word that stands for it (-1 where none does). [joined] marks a story word whose typed word also
     * stands for the next one (the space between them was missed), [split] one typed as two (a stray space: [typed]
     * is the first of the pair). [of] is, for each typed word, the story word it stands for (-1: one too many).
     * [reached] is how many of the story's words are behind the cursor.
     */
    class Words(val fates: Array<Fate>, val typed: IntArray, val joined: BooleanArray, val split: BooleanArray, val of: IntArray, val reached: Int) {
        val right: Int get() = fates.count { it == Fate.RIGHT }
        val wrong: Int get() = fates.count { it == Fate.WRONG }
        val skipped: Int get() = fates.count { it == Fate.SKIPPED }
    }

    /** What it costs to read the typed word [t] as the story's [m]: nothing where they are the same, 1 where nothing is alike. */
    private fun unlike(t: String, m: String): Float {
        if (t == m) return 0f
        if (t.equals(m, ignoreCase = true)) return 0.05f
        val a = core(t)
        val b = core(m)
        if (a.isEmpty() && b.isEmpty()) return 0.1f
        return 0.1f + 0.9f * letters(a, b).distance(a.length, b.length)
    }

    /**
     * The words [typed] held against the story's words [meant]. With [whole] every story word is accounted for (what
     * was not typed was skipped); without it the typed words are held against as much of the story as they best
     * cover, and the rest is still to do.
     */
    fun words(typed: List<String>, meant: List<String>, whole: Boolean): Words {
        val n = typed.size
        val m = meant.size
        val inf = Float.MAX_VALUE / 4
        // A word is seldom more than a few places from where it should be: only that band of the table is filled.
        val band = BAND + kotlin.math.abs(n - m).coerceAtMost(if (whole) m else 0)
        val d = Array(n + 1) { FloatArray(m + 1) { inf } }
        val how = Array(n + 1) { ByteArray(m + 1) }
        d[0][0] = 0f
        for (i in 0..n) {
            val from = (i - band).coerceAtLeast(0)
            val to = (i + band).coerceAtMost(m)
            for (j in from..to) {
                if (i == 0 && j == 0) continue
                var best = inf
                var by: Byte = 0
                if (i > 0 && j > 0 && d[i - 1][j - 1] < inf) { val v = d[i - 1][j - 1] + unlike(typed[i - 1], meant[j - 1]); if (v < best) { best = v; by = PAIR } }
                if (j > 0 && d[i][j - 1] < inf) { val v = d[i][j - 1] + 1f; if (v < best) { best = v; by = SKIP } }
                if (i > 0 && d[i - 1][j] < inf) { val v = d[i - 1][j] + 1f; if (v < best) { best = v; by = MORE } }
                // Two story words typed as one, the space between them missed (a letter may stand where it was).
                if (i > 0 && j > 1 && d[i - 1][j - 2] < inf) {
                    val both = core(meant[j - 2]) + core(meant[j - 1])
                    val t = core(typed[i - 1])
                    if (t.length >= both.length - 1 && both.length >= 3) {
                        val v = d[i - 1][j - 2] + 0.5f + 0.9f * letters(t, both).distance(t.length, both.length)
                        if (v < best) { best = v; by = JOIN }
                    }
                }
                // One story word typed as two, a space where a letter was meant.
                if (i > 1 && j > 0 && d[i - 2][j - 1] < inf) {
                    val both = core(typed[i - 2]) + core(typed[i - 1])
                    val t = core(meant[j - 1])
                    if (t.length >= both.length && t.length >= 3) {
                        val v = d[i - 2][j - 1] + 0.5f + 0.9f * letters(both, t).distance(both.length, t.length)
                        if (v < best) { best = v; by = SPLIT }
                    }
                }
                d[i][j] = best
                how[i][j] = by
            }
        }
        var end = m
        if (!whole) {
            var best = inf
            for (j in 0..m) if (d[n][j] < best || (d[n][j] == best && kotlin.math.abs(j - n) < kotlin.math.abs(end - n))) { best = d[n][j]; end = j }
        }
        val fates = Array(m) { Fate.TODO }
        val at = IntArray(m) { -1 }
        val joined = BooleanArray(m)
        val split = BooleanArray(m)
        val of = IntArray(n) { -1 }
        var i = n
        var j = end
        while (i > 0 || j > 0) {
            when (how[i][j]) {
                PAIR -> { fates[j - 1] = if (typed[i - 1] == meant[j - 1]) Fate.RIGHT else Fate.WRONG; at[j - 1] = i - 1; of[i - 1] = j - 1; i--; j-- }
                SKIP -> { fates[j - 1] = Fate.SKIPPED; j-- }
                MORE -> { i-- }
                JOIN -> {
                    fates[j - 1] = Fate.WRONG; fates[j - 2] = Fate.WRONG
                    at[j - 1] = i - 1; at[j - 2] = i - 1; joined[j - 2] = true
                    of[i - 1] = j - 2
                    i--; j -= 2
                }
                SPLIT -> {
                    fates[j - 1] = Fate.WRONG; at[j - 1] = i - 2; split[j - 1] = true
                    of[i - 1] = j - 1; of[i - 2] = j - 1
                    i -= 2; j--
                }
                else -> break
            }
        }
        // Whatever of the story lies behind the cursor and has no typed word was skipped.
        for (k in 0 until end) if (fates[k] == Fate.TODO) fates[k] = Fate.SKIPPED
        return Words(fates, at, joined, split, of, end)
    }

    private const val PAIR: Byte = 1
    private const val SKIP: Byte = 2
    private const val MORE: Byte = 3
    private const val JOIN: Byte = 4
    private const val SPLIT: Byte = 5

    /** How far from its place a typed word is looked for. */
    private const val BAND = 6
}
