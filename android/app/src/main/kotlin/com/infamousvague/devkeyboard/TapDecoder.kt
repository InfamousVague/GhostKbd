package com.infamousvague.devkeyboard

/**
 * Where the fingers landed for one typed word (Matt, 2026-11-10: "improving the reliability of the swipe and the
 * autocorrect"): for each character of the word, the point the tap was aimed at ([xs], [ys]; NaN for a character that
 * no tap of its own typed, such as an apostrophe from a long-press), with the middle of every letter key on the board
 * ([keys]) in the same units and a letter key's width ([keyW]). [space] is the space bar's left, top, right and
 * bottom where that is known: a tap low on the bottom row may have been meant for it.
 *
 * Autocorrect reads a word with these as a thumb types it: a tap on the edge between two keys could be either letter,
 * a tap in the middle of a key was that key. Without them it goes by which keys are neighbours, as it always did.
 */
class Taps(val xs: FloatArray, val ys: FloatArray, val keys: Map<Char, Pair<Float, Float>>, val keyW: Float, val space: FloatArray? = null) {
    fun has(i: Int) = i < xs.size && !xs[i].isNaN() && !ys[i].isNaN()
}

/**
 * The word lists as a trie, for [TapDecoder]: a node a letter, its first child ([down]) and next sibling ([next]),
 * the word that ends at it ([word], its place in the sorted list, or -1), and the commonest word at or under it
 * ([best], a Zipf frequency, and [bestWord], its place), by which a search gives up on a branch that can no longer
 * win, and by which a word half typed is finished.
 *
 * Made from the lexicon's sorted words in one pass. Never changed once made, so any thread may read it.
 */
class WordTrie(words: Array<String>, zipf: FloatArray) {
    val ch: CharArray
    val down: IntArray
    val next: IntArray
    val word: IntArray
    val best: FloatArray
    val bestWord: IntArray
    val size: Int

    init {
        var n = 1
        var longest = 0
        var prev = ""
        for (w in words) { n += w.length - shared(prev, w); if (w.length > longest) longest = w.length; prev = w }
        size = n
        ch = CharArray(n)
        down = IntArray(n) { -1 }
        next = IntArray(n) { -1 }
        word = IntArray(n) { -1 }
        best = FloatArray(n) { Float.NEGATIVE_INFINITY }
        bestWord = IntArray(n) { -1 }
        val parent = IntArray(n)
        // The nodes of the word before, one a depth: a new word keeps those of the letters it shares with it.
        val path = IntArray(longest + 1)
        var made = 1
        prev = ""
        for (index in words.indices) {
            val w = words[index]
            if (w.isEmpty()) continue
            val keep = shared(prev, w)
            for (d in keep until w.length) {
                val id = made++
                ch[id] = w[d]
                val up = path[d]
                parent[id] = up
                // Sorted words: the last child made under this node is the word before's node one deeper, if it went on.
                if (d == keep && prev.length > keep) next[path[d + 1]] = id else down[up] = id
                path[d + 1] = id
            }
            word[path[w.length]] = index
            best[path[w.length]] = zipf[index]
            bestWord[path[w.length]] = index
            prev = w
        }
        for (id in n - 1 downTo 1) if (best[id] > best[parent[id]]) { best[parent[id]] = best[id]; bestWord[parent[id]] = bestWord[id] }
    }

    private fun shared(a: String, b: String): Int {
        val m = minOf(a.length, b.length)
        var i = 0
        while (i < m && a[i] == b[i]) i++
        return i
    }

    /** The child of [node] along [c], or -1. */
    fun child(node: Int, c: Char): Int {
        var q = down[node]
        while (q >= 0) { if (ch[q] == c) return q; q = next[q] }
        return -1
    }

    /** About how much memory it takes, in bytes. */
    val approxBytes: Long get() = size * 22L
}

/**
 * Autocorrect's reading of a tapped word: the words of the lists that the taps could have been meant for, each with
 * what its slips cost. Plain Kotlin with no Android in it, so tool/typing_bench.sh types at it on a desk.
 *
 * It walks the [trie] with the taps in hand, a letter a tap. At each tap it may take:
 * - the key that was typed, for nothing; or another key, for how much further from the tap that key's middle is than
 *   the typed key's ([SPATIAL] a key width squared: a tap on the line between two keys costs nothing either way, one
 *   in the middle of a key costs about [SPATIAL] to read as its neighbour). A letter far from the tap can still be
 *   meant, as a misspelling rather than a slip of the thumb: any letter costs at most [far];
 * - the tap as one too many ([INSERT]; less where it doubles the letter beside it or is that letter's neighbour);
 * - a letter of the word with no tap at all ([OMIT]; an apostrophe for nearly nothing, a doubled letter for less);
 * - two taps in the wrong order ([SWAP]).
 * A branch is given up as soon as its cost is more than the commonest word under it could repay ([WordTrie.best]),
 * so the search stays a few thousand nodes however many words the lists have.
 *
 * Two words run together are read too ([joins]): where a word ends and taps are left over, a tap low on the bottom
 * row may have been the space bar ("thisbis"), or the space may have been missed ("thankyou").
 */
class TapDecoder(
    private val words: Array<String>,
    private val zipfs: FloatArray,
    private val trie: WordTrie,
    /** The letter key a word's letter is typed with on this board: itself, its plain letter (é is e), or 0 for none. */
    private val keyOf: (Char) -> Char,
    /** Each key's neighbours, for a character with no tap of its own. */
    private val neighbours: Map<Char, Set<Char>>,
) {
    /** A word of the lists (its place in the sorted list) and what the slips to it cost. */
    class Hit(val index: Int, val cost: Float)

    /** Two words run together: the first, the second, and what the slips (the missing space among them) cost. */
    class Join(val first: Int, val second: Int, val cost: Float)

    private var typed = ""
    private var n = 0
    private var tk = CharArray(0)
    private var nearCh = emptyArray<CharArray>()
    private var nearCost = emptyArray<FloatArray>()
    private var far = FAR
    private var spaceCost = FloatArray(0)
    private val keyCache = HashMap<Char, Char>()
    private var visits = 0

    private fun key(c: Char): Char = keyCache.getOrPut(c) { keyOf(c) }

    /** Reads the evidence in: the word as typed (lower case) and where its taps landed. */
    fun load(lower: String, taps: Taps?) {
        typed = lower
        n = lower.length
        tk = CharArray(n) { i -> key(lower[i]).let { if (it == 0.toChar()) lower[i] else it } }
        far = if (n >= LONG) FAR else FAR_SHORT
        spaceCost = FloatArray(n) { Float.POSITIVE_INFINITY }
        val w2 = if (taps != null) taps.keyW * taps.keyW else 1f
        nearCh = Array(n) { EMPTY_CHARS }
        nearCost = Array(n) { EMPTY_COSTS }
        for (i in 0 until n) {
            val t = tk[i]
            val at = if (taps != null && taps.has(i)) taps.keys[t] else null
            if (taps == null || at == null) {
                // No tap of its own: the keys beside it are a slip away, as they always were.
                val near = neighbours[t].orEmpty()
                nearCh[i] = near.toCharArray()
                nearCost[i] = FloatArray(near.size) { NEIGHBOUR }
                continue
            }
            val x = taps.xs[i]; val y = taps.ys[i]
            val d0 = ((x - at.first) * (x - at.first) + (y - at.second) * (y - at.second)) / w2
            val chars = ArrayList<Char>(6)
            val costs = ArrayList<Float>(6)
            for ((k, c) in taps.keys) {
                if (k == t) continue
                val d = ((x - c.first) * (x - c.first) + (y - c.second) * (y - c.second)) / w2
                val cost = SPATIAL * (d - d0)
                if (cost < far) { chars += k; costs += cost.coerceAtLeast(0f) }
            }
            nearCh[i] = chars.toCharArray()
            nearCost[i] = costs.toFloatArray()
            // The space bar, where this tap is over it or just above it: how much further its middle line is than the key's.
            val bar = taps.space
            if (bar != null && x >= bar[0] - taps.keyW * 0.5f && x <= bar[2] + taps.keyW * 0.5f) {
                val mid = (bar[1] + bar[3]) / 2
                val d = (y - mid) * (y - mid) / w2
                spaceCost[i] = (SPATIAL * (d - d0)).coerceAtLeast(0f)
            }
        }
    }

    /** What it costs to read tap [i] as the letter [c] of a word. */
    private fun sub(i: Int, c: Char): Float {
        if (c == typed[i]) return 0f
        val k = key(c)
        if (k == 0.toChar()) return Float.POSITIVE_INFINITY
        if (k == tk[i]) return 0f
        val chars = nearCh[i]
        // One vowel for another is how words are misspelled (definately, seperate): never dearer than [VOWEL].
        val most = if (k in VOWELS && tk[i] in VOWELS) minOf(far, VOWEL) else far
        for (j in chars.indices) if (chars[j] == k) return minOf(nearCost[i][j], most)
        return most
    }

    /** What it costs to take tap [i] as one too many. */
    private fun insert(i: Int): Float {
        if (i == 0) return INSERT_FIRST
        val c = tk[i]
        val before = tk[i - 1]
        val after = if (i + 1 < n) tk[i + 1] else 0.toChar()
        if (c == before || c == after) return INSERT_DOUBLED
        val near = neighbours[c]
        return if (near != null && (before in near || after in near)) INSERT_NEAR else INSERT
    }

    /** What it costs for the word to have the letter [c] with no tap for it, after the letter [last]. */
    private fun omit(c: Char, last: Char): Float = when {
        c == '\'' || c == '’' -> APOSTROPHE
        c == last -> OMIT_DOUBLED
        else -> OMIT
    }

    private val hits = HashMap<Int, Float>()
    private var floor = 0f
    private var top = Float.NEGATIVE_INFINITY

    private fun offer(index: Int, cost: Float) {
        val score = zipfs[index] - cost
        if (score < floor) return
        val had = hits[index]
        if (had == null || cost < had) hits[index] = cost
        if (score > top) { top = score; if (top - KEEP > floor) floor = top - KEEP }
    }

    /**
     * The words the taps of [from] onward could be, each with its cost: those scoring (frequency less cost) at least
     * [least], and within [KEEP] of the best found. [budget] is the most the slips may cost.
     */
    fun search(least: Float, budget: Float = BUDGET, from: Int = 0, slipsOnly: Boolean = false): List<Hit> {
        hits.clear()
        floor = least
        top = Float.NEGATIVE_INFINITY
        visits = 0
        only = slipsOnly
        finishing = false
        // A short word changed in most of its letters is another word, not a slip of this one. In hundredths of a
        // change: a letter read as the key its tap all but landed on counts for less than one ([counts]).
        val len = n - from
        changes = 100 * (if (slipsOnly) (if (len <= 4) 1 else 2) else when { len <= 3 -> 1; len <= 5 -> 2; len <= 8 -> 3; else -> 4 })
        walk(0, from, 0f, 0, 0, 0.toChar(), budget, 0)
        val out = ArrayList<Hit>(hits.size)
        for ((index, cost) in hits) if (zipfs[index] - cost >= floor) out += Hit(index, cost)
        return out
    }

    /**
     * The words the taps so far could be the start of, each the commonest word its reading begins, with what the
     * slips so far cost: "tomm" is the start of "tomorrow" with a tap too many, and a word begun on the line between
     * r and t may be "the". Those worth at least [least], within [KEEP] of the best.
     */
    fun finish(least: Float, budget: Float = FINISH_BUDGET): List<Hit> {
        hits.clear()
        floor = least
        top = Float.NEGATIVE_INFINITY
        visits = 0
        only = false
        finishing = true
        changes = 100 * when { n <= 3 -> 1; n <= 6 -> 2; else -> 3 }
        walk(0, 0, 0f, 0, 0, 0.toChar(), budget, 0)
        finishing = false
        val out = ArrayList<Hit>(hits.size)
        for ((index, cost) in hits) if (zipfs[index] - cost >= floor) out += Hit(index, cost)
        return out
    }

    /**
     * [search]'s bounds: only slips of the thumb (no letter missed, added or swapped, no misspelling), and how many
     * changes at most, in hundredths of a change.
     */
    private var only = false
    private var changes = 400

    /**
     * What a letter read as another key for [s] counts against [changes], in hundredths: a whole change from
     * [WHOLE_CHANGE] up, and its share of one below that. A letter missed, added or swapped is always a whole one.
     */
    private fun counts(s: Float): Int = if (s >= WHOLE_CHANGE) 100 else (s / WHOLE_CHANGE * 100f).toInt()
    /** [finish] is asking: where the taps run out, the commonest word under the node is taken, whether or not a word ends there. */
    private var finishing = false

    private fun walk(node: Int, i: Int, cost: Float, omits: Int, inserts: Int, last: Char, budget: Float, changed: Int) {
        if (trie.best[node] - cost < floor || cost > budget || ++visits > MAX_VISITS) return
        val more = changed + 100 <= changes
        if (i == n) {
            if (finishing) { val w = trie.bestWord[node]; if (w >= 0) offer(w, cost); return }
            val w = trie.word[node]
            if (w >= 0) offer(w, cost)
            // The word goes on for a letter or two the thumb never typed (an apostrophe and its letter count as one).
            var q = trie.down[node]
            while (q >= 0) {
                val c = trie.ch[q]
                val free = c == '\'' || c == '’'
                if (free) walk(q, i, cost + APOSTROPHE, omits, inserts, c, budget, changed)
                else if (!only && more && omits < MAX_OMITS) walk(q, i, cost + omit(c, last), omits + 1, inserts, c, budget, changed + 100)
                q = trie.next[q]
            }
            return
        }
        // The key that was typed first: the likeliest reading is found early, and raises the bar for the rest.
        val exact = typed[i]
        var q = trie.down[node]
        while (q >= 0) {
            val c = trie.ch[q]
            if (c == exact) walk(q, i + 1, cost, omits, inserts, c, budget, changed)
            q = trie.next[q]
        }
        q = trie.down[node]
        while (q >= 0) {
            val c = trie.ch[q]
            if (c != exact) {
                val s = sub(i, c)
                // The same key under another letter (é for e) is no change; a misspelling is not a slip of the thumb.
                val same = s == 0f && key(c) == tk[i]
                val w = if (same) 0 else counts(s)
                if (cost + s <= budget && (same || (changed + w <= changes && !(only && s >= far)))) walk(q, i + 1, cost + s, omits, inserts, c, budget, changed + w)
            }
            // An apostrophe is nearly free and is not counted as a letter missed.
            val free = c == '\'' || c == '’'
            if (free) walk(q, i, cost + APOSTROPHE, omits, inserts, c, budget, changed)
            else if (!only && more && omits < MAX_OMITS) walk(q, i, cost + omit(c, last), omits + 1, inserts, c, budget, changed + 100)
            q = trie.next[q]
        }
        if (only || !more) return
        if (inserts < MAX_INSERTS) walk(node, i + 1, cost + insert(i), omits, inserts + 1, last, budget, changed + 100)
        if (i + 1 < n && typed[i] != typed[i + 1]) {
            val a = trie.child(node, typed[i + 1])
            if (a >= 0) { val b = trie.child(a, typed[i]); if (b >= 0) walk(b, i + 2, cost + SWAP, omits, inserts, typed[i], budget, changed + 100) }
        }
    }

    /**
     * What the slips cost to read the taps of [from] onward as [word] exactly (a learned word, one the person types,
     * one that is likely next): the same costs as [search], worked out for the one word. Infinite past [budget].
     */
    fun align(word: String, from: Int = 0, budget: Float = BUDGET): Float {
        val m = word.length
        val len = n - from
        if (m == 0 || kotlin.math.abs(m - len) > MAX_OMITS + MAX_INSERTS + 2) return Float.POSITIVE_INFINITY
        // cost[j][i]: the first j letters of the word against the first i taps.
        var before2 = FloatArray(len + 1)
        var before = FloatArray(len + 1)
        var row = FloatArray(len + 1)
        before[0] = 0f
        for (i in 1..len) before[i] = before[i - 1] + insert(from + i - 1)
        for (j in 1..m) {
            val c = word[j - 1]
            val last = if (j >= 2) word[j - 2] else 0.toChar()
            row[0] = before[0] + omit(c, last)
            var least = row[0]
            for (i in 1..len) {
                var v = before[i - 1] + sub(from + i - 1, c)
                val skip = before[i] + omit(c, last)
                if (skip < v) v = skip
                val extra = row[i - 1] + insert(from + i - 1)
                if (extra < v) v = extra
                if (j >= 2 && i >= 2 && c == typed[from + i - 2] && last == typed[from + i - 1] && c != last) {
                    val swap = before2[i - 2] + SWAP
                    if (swap < v) v = swap
                }
                row[i] = v
                if (v < least) least = v
            }
            if (least > budget) return Float.POSITIVE_INFINITY
            val spare = before2; before2 = before; before = row; row = spare
        }
        return before[len].let { if (it > budget) Float.POSITIVE_INFINITY else it }
    }

    /**
     * Two words run together: every way the taps split into a first word and a second, with a tap between them that
     * may have been the space bar, or no tap at all (the space missed). [second] scores a second word after a first
     * (the place in the list of each): how common it is there, as a Zipf frequency. A join's worth is the first
     * word's frequency and the second's there, less 9 (two words are one in a billion times less likely than one,
     * so each must be common to be worth it), less the cost; only those worth at least [least] come back.
     */
    fun joins(least: Float, firsts: (Int) -> Boolean, second: (first: Int, second: Int) -> Float, followers: (first: Int) -> IntArray): List<Join> {
        val out = ArrayList<Join>()
        if (n < 3) return out
        // The first word: one that the first taps spell with little amiss.
        for (cut in 1 until n) {
            // The taps before the cut are the first word; the tap at the cut is the space, or the cut falls between taps.
            for (spaced in 0..1) {
                val gap = if (spaced == 1) spaceCost[cut] else NO_SPACE
                if (gap > JOIN_BUDGET) continue
                val restFrom = if (spaced == 1) cut + 1 else cut
                if (restFrom >= n) continue
                for (first in prefixWords(cut)) {
                    if (!firsts(first.index)) continue
                    val z1 = zipfs[first.index]
                    val spent = first.cost + gap
                    // The second word must be worth: least + 9 - z1 + spent, less whatever its cost adds.
                    val need = least + 9f - z1 + spent
                    if (need > MAX_ZIPF + PAIR_LIFT) continue
                    val seen = HashSet<Int>()
                    for (f in followers(first.index)) {
                        val c = align(words[f], restFrom, JOIN_BUDGET)
                        if (c.isInfinite()) continue
                        seen += f
                        if (second(first.index, f) - c >= need) out += Join(first.index, f, spent + c)
                    }
                    if (need <= MAX_ZIPF) {
                        for (h in search(need, JOIN_BUDGET, restFrom)) {
                            if (h.index in seen) continue
                            if (second(first.index, h.index) - h.cost >= need) out += Join(first.index, h.index, spent + h.cost)
                        }
                    }
                }
            }
        }
        return out
    }

    /** The words the first [cut] taps spell, with at most a slip of the thumb or two among them. */
    private fun prefixWords(cut: Int): List<Hit> {
        val out = ArrayList<Hit>(4)
        fun go(node: Int, i: Int, cost: Float) {
            if (cost > JOIN_BUDGET) return
            if (i == cut) { val w = trie.word[node]; if (w >= 0) out += Hit(w, cost); return }
            var q = trie.down[node]
            while (q >= 0) {
                val c = trie.ch[q]
                val s = sub(i, c)
                if (s < far && cost + s <= JOIN_BUDGET) go(q, i + 1, cost + s)
                if (c == '\'' || c == '’') go(q, i, cost + APOSTROPHE)
                q = trie.next[q]
            }
        }
        go(0, 0, 0f)
        return out
    }

    companion object {
        private val EMPTY_CHARS = CharArray(0)
        private val EMPTY_COSTS = FloatArray(0)

        // Costs are in Zipf units (a power of ten in how common a word is): a slip that costs 2 is repaid by a word a
        // hundred times as common. They are set on tool/typing_bench.sh, which holds them to three kinds of thumb.

        /** What a key width squared of extra distance from a tap costs: about 0.434 / (2σ²), with σ a thumb's scatter in key widths. */
        @JvmField var SPATIAL = 1.5f
        /** The most any letter costs in place of the one typed: a misspelling, not a slip ([FAR_SHORT] in a word under [LONG] letters). */
        @JvmField var FAR = 3.8f
        @JvmField var FAR_SHORT = 4.4f
        /** One vowel typed for another, wherever the tap landed: a misspelling. */
        @JvmField var VOWEL = 2.2f
        const val VOWELS = "aeiou"
        const val LONG = 5
        /** A neighbouring key in place of a character that had no tap of its own. */
        @JvmField var NEIGHBOUR = 1.6f
        @JvmField var INSERT = 2.8f
        @JvmField var INSERT_NEAR = 2.0f
        @JvmField var INSERT_DOUBLED = 1.4f
        @JvmField var INSERT_FIRST = 4.0f
        @JvmField var OMIT = 2.0f
        @JvmField var OMIT_DOUBLED = 1.2f
        @JvmField var APOSTROPHE = 0.2f
        @JvmField var SWAP = 1.1f
        /** The space missed altogether between two words run together. */
        @JvmField var NO_SPACE = 3.2f
        /** The most the slips of either half of two joined words may cost. */
        @JvmField var JOIN_BUDGET = 3.4f
        /** The most all of a word's slips may cost. */
        @JvmField var BUDGET = 5.5f
        /** The most the slips so far may cost for a word half typed to be finished by them ([finish]). */
        @JvmField var FINISH_BUDGET = 2.6f
        /**
         * What a letter read as another key must cost to count as a whole change against a search's bound on them
         * ([search]: one change in a word of three letters or fewer, two up to five, three up to eight, four
         * beyond). One that costs less counts for its share: two taps each just over the line to the next key are
         * a fraction of a change between them, two in the middles of far keys are two. The bound used to count
         * letters whatever their taps said, and "it" typed "ur" or "the" typed "tbr" was never read at all: 89 of
         * the 326 words Gboard got and this did not on 2026-11-11 (docs/versus.md).
         *
         * Set on tool/typing_bench.sh tune, whose measure is words right less four for every right word spoiled:
         * 93.02 at 0.5, 93.45 at 1, 93.63 at 1.5, 93.76 at 2, 93.80 at 2.5, 93.84 at 4. Taken at 2, where the curve
         * flattens. On 12,000 of the bench's words that takes the sloppy thumb's words right from 82.7% to 85.7% and
         * the normal one's from 91.6% to 92.3% (the careful one's stay at 96.4%); right words spoiled go from 1.1 to
         * 1.5 in a thousand for the normal thumb, and the right words no list has that are spoiled from 2.4% to 4.3%
         * (6.0% to 7.1% for the sloppy one, 1.5% either way for the careful one).
         */
        @JvmField var WHOLE_CHANGE = 2.0f
        /** Readings this far under the best one found are let go. */
        @JvmField var KEEP = 3f
        const val MAX_OMITS = 2
        const val MAX_INSERTS = 2
        /** A search that has looked at this many nodes stops with what it has: no word is worth a key press's wait. */
        const val MAX_VISITS = 40_000
        /** No word is commoner than this, and no pair lifts a second word more than [PAIR_LIFT] over it. */
        const val MAX_ZIPF = 8f
        const val PAIR_LIFT = 1.5f
    }
}
