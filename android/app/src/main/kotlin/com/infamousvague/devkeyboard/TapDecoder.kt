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
 * Made from the lexicon's sorted words in one pass. Never changed once made, so any thread may read it. [read] says
 * which of the words are in it (all of them where it is null): a word left out keeps its place in the sorted list
 * and is never a reading of anything (English's wider list has words that are known when typed and never offered).
 */
class WordTrie(words: Array<String>, zipf: FloatArray, read: BooleanArray? = null) {
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
        for (index in words.indices) {
            if (read != null && !read[index]) continue
            val w = words[index]
            n += w.length - shared(prev, w); if (w.length > longest) longest = w.length; prev = w
        }
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
            if (w.isEmpty() || (read != null && !read[index])) continue
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
 * - the tap as one too many ([INSERT]; less where it doubles the letter beside it or is that letter's neighbour,
 *   and where the search reaches, by how near it landed to the key typed beside it, [REACH_INSERT] at the most);
 * - a letter of the word with no tap at all ([OMIT]; an apostrophe for nearly nothing, a doubled letter for less,
 *   and where the search reaches [REACH_OMIT], [REACH_OMIT_END] after the last tap, [LONE_OMIT] where one letter was typed);
 * - two taps in the wrong order ([SWAP]), one of which may have landed on a neighbour's key besides ([SWAP_SLIP]).
 * A branch is given up as soon as its cost is more than the commonest word under it could repay ([WordTrie.best]),
 * so the search stays a few thousand nodes however many words the lists have.
 *
 * Words run together are read too ([joins]): where a word ends and taps are left over, a tap low on the bottom
 * row may have been the space bar ("thisbis"), or the space may have been missed ("thankyou"); and the same again
 * after a second word ("heardvthecdoor").
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

    /**
     * Two or three words run together: each one's place in the list ([third] is -1 where there are two), what the
     * slips cost (the missing spaces among them), and how many of the spaces were a tap that landed on a letter
     * above the bar ([bars]; the others were never typed). [from2] and [from3] are the taps the second and the
     * third word start at (-1 with no third), and [barred2] and [barred3] say the tap before that one was the
     * space's: where the words meet, for telling letters that look like no word from two words set side by side.
     */
    class Join(val first: Int, val second: Int, val third: Int, val cost: Float, val bars: Int, val from2: Int, val barred2: Boolean, val from3: Int = -1, val barred3: Boolean = false)

    /** A word the taps from some tap on begin with: its place in the list, the first tap that is not its own, and what its slips cost. */
    private class Start(val index: Int, val end: Int, val cost: Float)

    private var typed = ""
    private var n = 0
    private var tk = CharArray(0)
    private var nearCh = emptyArray<CharArray>()
    private var nearCost = emptyArray<FloatArray>()
    private var far = FAR
    private var spaceCost = FloatArray(0)
    /** Whether each character had a tap of its own, and what that tap costs to read as the key typed before or after it. */
    private var aimed = BooleanArray(0)
    private var again = FloatArray(0)
    /** For each tap, the other keys it landed within [SWAP_SLIP] of, and what each costs: see [swap]. */
    private var slipCh = emptyArray<CharArray>()
    private var slipCost = emptyArray<FloatArray>()
    private val keyCache = HashMap<Char, Char>()
    private var visits = 0
    /** The most nodes a [search] may walk: [MAX_VISITS], or less where [joins] has only that much work left. */
    private var visitCap = MAX_VISITS

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
        aimed = BooleanArray(n)
        for (i in 0 until n) {
            val t = tk[i]
            // A tap that typed a space, in letters a stray one may have cut in two ([Autocorrect.rejoin]): read as a
            // letter, it costs by how much nearer the bar's middle line it landed than that letter's key. A tap just
            // under the bar's top edge is as near the key above it as the bar; one in the bar's middle was the bar.
            if (lower[i] == ' ') {
                val bar = taps?.space
                if (taps != null && taps.has(i) && bar != null) {
                    val x = taps.xs[i]; val y = taps.ys[i]
                    val mid = (bar[1] + bar[3]) / 2
                    val d0 = (y - mid) * (y - mid) / w2
                    val chars = ArrayList<Char>(6)
                    val costs = ArrayList<Float>(6)
                    for ((k, c) in taps.keys) {
                        val d = ((x - c.first) * (x - c.first) + (y - c.second) * (y - c.second)) / w2
                        val cost = SPATIAL * (d - d0)
                        if (cost < far) { chars += k; costs += cost.coerceAtLeast(0f) }
                    }
                    nearCh[i] = chars.toCharArray()
                    nearCost[i] = costs.toFloatArray()
                }
                continue
            }
            val at = if (taps != null && taps.has(i)) taps.keys[t] else null
            if (taps == null || at == null) {
                // No tap of its own: the keys beside it are a slip away, as they always were.
                val near = neighbours[t].orEmpty()
                nearCh[i] = near.toCharArray()
                nearCost[i] = FloatArray(near.size) { NEIGHBOUR }
                continue
            }
            aimed[i] = true
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
        reachReady = false
    }

    /** Whether [again], [slipCh] and [slipCost] are this word's: only a search asked to reach reads them, and builds them. */
    private var reachReady = false

    /** [search]'s reach reads, of each tap, what it costs as the key beside it ([again]) and the keys it slipped onto ([slipCh]). */
    private fun readyReach() {
        if (reachReady) return
        reachReady = true
        again = FloatArray(n) { i -> minOf(if (i > 0) lands(i, tk[i - 1]) else Float.POSITIVE_INFINITY, if (i + 1 < n) lands(i, tk[i + 1]) else Float.POSITIVE_INFINITY) }
        slipCh = Array(n) { EMPTY_CHARS }
        slipCost = Array(n) { EMPTY_COSTS }
        val slip = SWAP_SLIP
        if (slip > 0f) for (i in 0 until n) {
            if (!aimed[i]) continue
            val costs = nearCost[i]
            var some = 0
            for (c in costs) if (c <= slip) some++
            if (some == 0) continue
            val chars = CharArray(some)
            val theirs = FloatArray(some)
            var at = 0
            for (j in costs.indices) if (costs[j] <= slip) { chars[at] = nearCh[i][j]; theirs[at++] = costs[j] }
            slipCh[i] = chars
            slipCost[i] = theirs
        }
    }

    /** What it costs to read tap [i] as the key [k], by where it landed: nothing where that is the key it typed. */
    private fun lands(i: Int, k: Char): Float {
        if (k == tk[i]) return 0f
        val chars = nearCh[i]
        for (j in chars.indices) if (chars[j] == k) return nearCost[i][j]
        return Float.POSITIVE_INFINITY
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

    /**
     * What it costs to read tap [i] as the letter [c] by a slip of the thumb alone: how much further that key's
     * middle is from the tap than the typed key's, and never for a key the tap was nowhere near. [sub] lets one
     * vowel stand for another wherever it was tapped as well, which is how a word is misspelled and not how a
     * thumb slips: a first word of a join is read by this ([starts]).
     */
    private fun slip(i: Int, c: Char): Float {
        if (c == typed[i]) return 0f
        val k = key(c)
        if (k == 0.toChar()) return Float.POSITIVE_INFINITY
        if (k == tk[i]) return 0f
        val chars = nearCh[i]
        for (j in chars.indices) if (chars[j] == k) return nearCost[i][j]
        return Float.POSITIVE_INFINITY
    }

    /** What it costs to take tap [i] as one too many. */
    private fun insert(i: Int): Float {
        // The space bar's own tap is a space or a letter that missed its key, never nothing at all.
        if (tk[i] == ' ') return Float.POSITIVE_INFINITY
        if (i == 0) return INSERT_FIRST
        // A second tap of the key typed before it or after it, by where it landed: on that key again it is a letter
        // tapped twice, just over the line from that key it is nearly one, and in the middle of another key it is a
        // tap of its own, up to [REACH_INSERT]. Only where the search reaches: elsewhere, and a character with no tap,
        // goes by which keys are neighbours, as every one did.
        if (reaching && aimed[i]) return minOf(REACH_INSERT, INSERT_DOUBLED + again[i])
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
        reaching -> REACH_OMIT
        else -> OMIT
    }

    /** A letter with no tap after the last tap: as [omit], but [REACH_OMIT_END] where the search reaches. */
    private fun omitAfter(c: Char, last: Char): Float = when {
        c == '\'' || c == '’' -> APOSTROPHE
        c == last -> OMIT_DOUBLED
        reaching -> REACH_OMIT_END
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
     *
     * [slipsOnly] is for letters that spell a common word: no misspelling is read into one, and no letter left out,
     * added or swapped, unless [reach] is asked too. [reach] is [Autocorrect.byTaps] asking for the word as it was
     * typed, and reads further than the rest do (two words run together, a word cut in two, a word half typed):
     * - a word of up to [SHORT] letters may be changed by [SHORT_MORE] more than its one change, for [SHORT_PAST];
     * - letters that spell a common word may have had a letter left out, a letter tapped twice or two swapped,
     *   each for at least [LISTED_EDIT];
     * - a letter left out of a word typed as one letter costs [LONE_OMIT];
     * - one of two taps in the wrong order may have landed on a neighbour's key ([SWAP_SLIP]);
     * - a tap too many costs by where it landed ([REACH_INSERT]) and a letter left out [REACH_OMIT].
     */
    fun search(least: Float, budget: Float = BUDGET, from: Int = 0, slipsOnly: Boolean = false, reach: Boolean = false): List<Hit> {
        hits.clear()
        floor = least
        top = Float.NEGATIVE_INFINITY
        visits = 0
        only = slipsOnly
        finishing = false
        // A short word changed in most of its letters is another word, not a slip of this one. In hundredths of a
        // change: a letter read as the key its tap all but landed on counts for less than one ([counts]).
        val len = n - from
        bound = 100 * (if (slipsOnly) (if (len <= 4) 1 else 2) else when { len <= 3 -> 1; len <= 5 -> 2; len <= 8 -> 3; else -> 4 })
        changes = bound
        past = 0f
        edits = !slipsOnly
        dear = 0f
        lone = false
        reaching = reach
        if (reach) {
            if (len <= SHORT.toInt()) { changes = bound + (SHORT_MORE * 100f).toInt(); past = SHORT_PAST }
            if (slipsOnly && LISTED_EDIT < NEVER) { edits = true; dear = LISTED_EDIT }
            lone = len == 1
            readyReach()
        }
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
        bound = 100 * when { n <= 3 -> 1; n <= 6 -> 2; else -> 3 }
        changes = bound
        past = 0f
        edits = true
        dear = 0f
        lone = false
        reaching = false
        walk(0, 0, 0f, 0, 0, 0.toChar(), budget, 0)
        finishing = false
        val out = ArrayList<Hit>(hits.size)
        for ((index, cost) in hits) if (zipfs[index] - cost >= floor) out += Hit(index, cost)
        return out
    }

    /**
     * [search]'s bounds. [only]: the letters spell a common word, and no misspelling is read into them. [bound]: how
     * many changes at most, in hundredths of a change, and [changes] the same with what a short word is let past it,
     * each reading that goes past it costing [past] more. [edits]: whether a letter may have been left out, added or
     * swapped, each for at least [dear]. [lone]: the word was typed as one letter. [reaching]: the search was asked
     * to reach, which [swap] goes by too.
     */
    private var only = false
    private var bound = 400
    private var changes = 400
    private var past = 0f
    private var edits = true
    private var dear = 0f
    private var lone = false
    private var reaching = false

    /**
     * What a letter read as another key for [s] counts against [changes], in hundredths: a whole change from
     * [WHOLE_CHANGE] up, and its share of one below that. A letter missed, added or swapped is always a whole one.
     */
    private fun counts(s: Float): Int = if (s >= WHOLE_CHANGE) 100 else (s / WHOLE_CHANGE * 100f).toInt()
    /** What it costs more to count [w] on top of [changed]: [past], the once that takes a reading over the [bound]. */
    private fun beyond(changed: Int, w: Int): Float = if (changed <= bound && changed + w > bound) past else 0f
    /** A letter left out, added or swapped where the letters spell a common word: never less than [dear]. */
    private fun edit(e: Float): Float = if (e < dear) dear else e
    /** Whether tap [i] typed the key of the tap before it or after it over again: a letter tapped twice. */
    private fun twice(i: Int): Boolean = (i > 0 && tk[i] == tk[i - 1]) || (i + 1 < n && tk[i] == tk[i + 1])
    /** [finish] is asking: where the taps run out, the commonest word under the node is taken, whether or not a word ends there. */
    private var finishing = false

    private fun walk(node: Int, i: Int, cost: Float, omits: Int, inserts: Int, last: Char, budget: Float, changed: Int) {
        if (trie.best[node] - cost < floor || cost > budget || ++visits > visitCap) return
        // A letter left out, added or swapped is a whole change: whether one more is allowed, and what it costs past the bound.
        val more = edits && changed + 100 <= changes
        val over = beyond(changed, 100)
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
                else if (more && omits < MAX_OMITS) walk(q, i, cost + (if (lone) LONE_OMIT else edit(omitAfter(c, last))) + over, omits + 1, inserts, c, budget, changed + 100)
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
                val t = if (same) 0f else s + beyond(changed, w)
                if (cost + t <= budget && (same || (changed + w <= changes && !(only && s >= far)))) walk(q, i + 1, cost + t, omits, inserts, c, budget, changed + w)
            }
            // An apostrophe is nearly free and is not counted as a letter missed.
            val free = c == '\'' || c == '’'
            if (free) walk(q, i, cost + APOSTROPHE, omits, inserts, c, budget, changed)
            else if (more && omits < MAX_OMITS) walk(q, i, cost + (if (lone) LONE_OMIT else edit(omit(c, last))) + over, omits + 1, inserts, c, budget, changed + 100)
            q = trie.next[q]
        }
        if (!more) return
        // One tap too many. From letters that spell a common word only a letter tapped twice is ever taken away:
        // "inn" may be "in", and "ink" is never "in" with a k too many.
        if (inserts < MAX_INSERTS && (!only || twice(i))) walk(node, i + 1, cost + edit(insert(i)) + over, omits, inserts + 1, last, budget, changed + 100)
        if (i + 1 < n) swap(node, i, cost + edit(SWAP), omits, inserts, budget, changed)
    }

    /**
     * Taps [i] and [i] + 1 in the wrong order: the later one read as the word's next letter and the earlier as the
     * letter after that. [cost] has what the swap costs in it already. Where the search was asked to reach, one of
     * the two (never both) may have landed on a neighbour's key as well, for what any tap read as that key costs:
     * "iculs" is "could" with its c and o in the wrong order and the o tapped on i.
     */
    private fun swap(node: Int, i: Int, cost: Float, omits: Int, inserts: Int, budget: Float, changed: Int) {
        // Nothing under this node can repay the swap: [walk] would say so of each pair, one look at a time.
        if (trie.best[node] - cost < floor || cost > budget) return
        val first = typed[i + 1]
        val second = typed[i]
        pair(node, i, first, second, cost, 0f, omits, inserts, budget, changed)
        // Two taps on one key are not read as swapped, whichever of them was nearer another key.
        if (!reaching || tk[i] == tk[i + 1]) return
        val a = slipCh[i + 1]
        for (j in a.indices) pair(node, i, a[j], second, cost, slipCost[i + 1][j], omits, inserts, budget, changed)
        val b = slipCh[i]
        for (j in b.indices) pair(node, i, first, b[j], cost, slipCost[i][j], omits, inserts, budget, changed)
    }

    /** [swap]'s two letters, [a] then [b], with what the one that slipped cost ([s], nothing where neither did). */
    private fun pair(node: Int, i: Int, a: Char, b: Char, cost: Float, s: Float, omits: Int, inserts: Int, budget: Float, changed: Int) {
        // The same letter twice is no swap: read in order, the taps say as much for less.
        if (a == b) return
        val w = 100 + counts(s)
        if (changed + w > changes) return
        val p = trie.child(node, a)
        if (p < 0) return
        val q = trie.child(p, b)
        if (q >= 0) walk(q, i + 2, cost + s + beyond(changed, w), omits, inserts, b, budget, changed + w)
        if (!reaching) return
        // An apostrophe between the two is stepped over, as [walk] steps over any: "dotn" is "don't", and without
        // this the swap reached only "dont", which the lists hold too.
        for (mark in APOSTROPHES) {
            val h = trie.child(p, mark)
            if (h < 0) continue
            val r = trie.child(h, b)
            if (r >= 0) walk(r, i + 2, cost + s + APOSTROPHE + beyond(changed, w), omits, inserts, b, budget, changed + w)
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
        // The plain costs, whatever the last search reached for: [insert] and [omit] go by [reaching].
        reaching = false
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
     * Words run together: the ways the taps split into a first word and a last, or a first, a middle and a last,
     * with a tap between two of them that may have been the space bar, or no tap at all (the space missed).
     * [first] says what a word is worth as the first of them (its frequency, and what the words before lift it by;
     * minus infinity for a word that may not start a join) and [second] what a word is worth after the one before
     * it (the place in the list of each), as Zipf frequencies; [followers] are the words the tables list after a
     * word, in the list's order. A join is worth its first word, and each later word after the one before, less 9
     * for each space (two words are one in a billion times less likely than one, so each must be common to be
     * worth it), less what the slips cost. Those worth at least [least] come back.
     *
     * A first or a middle word is at least [common], and is read with the slips a thumb makes inside one word:
     * letters read as keys their taps were near, and one letter missed, added or swapped ([starts]). The last is
     * read as any word is ([search]; [fits] for a word the tables list after the one before), held to
     * [JOIN_BUDGET].
     *
     * What comes back is the best join there is and whichever others were found on the way to it, not every join:
     * the likeliest first words are tried first, each find raises what the rest must be worth, and a first word
     * that could not be part of a better join is passed over. The taps left after a first word are read once for
     * every place they could start at, whichever first words end there. All of it stops at [MAX_JOIN_WORK] with
     * what it has ([JOIN_WORK_LAST] says how far it went).
     */
    fun joins(least: Float, common: Float, first: (Int) -> Float, second: (first: Int, second: Int) -> Float, followers: (first: Int) -> IntArray): List<Join> {
        joinWork = 0
        val out = joined(least, common, first, second, followers)
        if (joinWork > JOIN_WORK_MOST) JOIN_WORK_MOST = joinWork
        JOIN_WORK_LAST = joinWork
        return out
    }

    private fun joined(least: Float, common: Float, first: (Int) -> Float, second: (first: Int, second: Int) -> Float, followers: (first: Int) -> IntArray): List<Join> {
        val out = ArrayList<Join>()
        if (n < 3) return out
        var best = Float.NEGATIVE_INFINITY
        // A word's followers in the tables, in the list's order, asked for once.
        val rows = HashMap<Int, IntArray>()
        // What a follower's slips cost from a tap on: the same word follows many first words.
        val aligned = HashMap<Long, Float>()
        fun cost(from: Int, word: Int): Float {
            val w = words[word]
            val len = n - from
            if (w.length - len > 4 || len - w.length > 2) return Float.POSITIVE_INFINITY
            // One of the first two taps is one of the word's first two letters, or nothing fits within the budget.
            val a = w[0]; val b = if (w.length > 1) w[1] else a
            if (sub(from, a) > JOIN_BUDGET && sub(from, b) > JOIN_BUDGET && (from + 1 >= n || (sub(from + 1, a) > JOIN_BUDGET && sub(from + 1, b) > JOIN_BUDGET))) return Float.POSITIVE_INFINITY
            return aligned.getOrPut((from.toLong() shl 32) or word.toLong()) { joinWork += ALIGN_WORK; fits(w, from, JOIN_BUDGET) }
        }
        // The words the taps from a tap on could be, by frequency less cost, best first; and the least each list holds.
        val tails = arrayOfNulls<List<Hit>>(n)
        val tailsFrom = FloatArray(n)
        fun tails(from: Int, need: Float): List<Hit> {
            tails[from]?.let { if (tailsFrom[from] <= need) return it }
            // No more nodes than the work left: the search's own bound is otherwise 40,000, past the whole cap.
            val left = (MAX_JOIN_WORK - joinWork).toInt()
            if (left <= 0) return emptyList()
            visitCap = minOf(MAX_VISITS, left)
            val found = try { search(need, JOIN_BUDGET, from).sortedByDescending { zipfs[it.index] - it.cost } } finally { visitCap = MAX_VISITS }
            joinWork += minOf(visits, left)
            // A search cut short is not all there is: never kept for another first word to take as the whole.
            if (visits <= left) { tails[from] = found; tailsFrom[from] = need }
            return found
        }
        // The last word after [before], from the tap [from]: [have] is what the words and slips before it come to.
        // Each one found is handed to [found] with its cost.
        fun last(before: Int, from: Int, have: Float, found: (index: Int, cost: Float) -> Unit) {
            var need = maxOf(least, best) + 9f - have
            if (need > MAX_ZIPF + PAIR_LIFT) return
            // The words the tables list after [before], which may be worth more there than they are anywhere:
            // each is held up against the taps by itself.
            val row = rows.getOrPut(before) { followers(before) }
            joinWork += row.size
            for (f in row) {
                val c = cost(from, f)
                if (c.isInfinite()) continue
                joinWork++
                val s = second(before, f)
                if (s - c < need) continue
                found(f, c)
                val worth = have + s - c - 9f
                if (worth > best) { best = worth; need = best + 9f - have }
            }
            // Any other word is worth no more after [before] than it is anywhere: the commonest that fit, till they fall short.
            if (need > MAX_ZIPF) return
            for (h in tails(from, need)) {
                if (zipfs[h.index] - h.cost < need) break
                if (java.util.Arrays.binarySearch(row, h.index) >= 0) continue
                joinWork++
                val s = second(before, h.index)
                if (s - h.cost < need) continue
                found(h.index, h.cost)
                val worth = have + s - h.cost - 9f
                if (worth > best) { best = worth; need = best + 9f - have }
            }
        }
        // Every first word with the space after it, the likeliest first: where the search has to stop, it has looked at those.
        val leads = ArrayList<Lead>()
        for (head in starts(0, common, MAX_JOIN_WORK * JOIN_START_SHARE)) {
            val z = first(head.index)
            if (z == Float.NEGATIVE_INFINITY) continue
            for (spaced in 0..1) {
                // The taps before [head.end] are the first word; the tap there is the space, or the cut falls between taps.
                val gap = if (spaced == 1) spaceCost[head.end] else NO_SPACE
                val from = head.end + spaced
                if (gap > JOIN_BUDGET || from >= n) continue
                leads += Lead(head.index, from, head.cost + gap, z - head.cost - gap, spaced)
            }
        }
        leads.sortByDescending { it.have }
        // No word is worth more after another than MAX_ZIPF + PAIR_LIFT: half a point over the nine a space costs.
        val most = MAX_ZIPF + PAIR_LIFT - 9f
        for (lead in leads) {
            if (joinWork > MAX_JOIN_WORK) break
            if (lead.have + most < maxOf(least, best)) continue
            last(lead.index, lead.from, lead.have) { index, cost -> out += Join(lead.index, index, -1, lead.spent + cost, lead.bars, lead.from, lead.bars > 0) }
        }
        // Three words, once every two have been looked for: a middle word, and the same again after it.
        if (!JOIN_THREE) return out
        val mids = arrayOfNulls<List<Start>>(n)
        for (lead in leads) {
            if (joinWork > MAX_JOIN_WORK) break
            if (n - lead.from < 3 || lead.have + 2 * most < maxOf(least, best)) continue
            val middles = mids[lead.from] ?: starts(lead.from, common, MAX_JOIN_WORK).filter { first(it.index) != Float.NEGATIVE_INFINITY }.also { mids[lead.from] = it }
            for (mid in middles) {
                if (joinWork > MAX_JOIN_WORK) break
                if (lead.have - mid.cost + 2 * most < maxOf(least, best)) continue
                joinWork++
                val have = lead.have + second(lead.index, mid.index) - 9f - mid.cost
                for (spaced in 0..1) {
                    val gap = if (spaced == 1) spaceCost[mid.end] else NO_SPACE
                    val from = mid.end + spaced
                    if (gap > JOIN_BUDGET || from >= n) continue
                    if (have - gap + most < maxOf(least, best)) continue
                    last(mid.index, from, have - gap) { index, cost -> out += Join(lead.index, mid.index, index, lead.spent + mid.cost + gap + cost, lead.bars + spaced, lead.from, lead.bars > 0, from, spaced == 1) }
                }
            }
        }
        return out
    }

    /**
     * [align] for the last word of a join, held as [search] holds any word it finds: its slips may cost no more
     * than [budget], and may be no more changes than [search] allows a word of as many taps (one in three taps or
     * fewer, two up to five, three up to eight, four beyond; a letter read as a near key counts for its share,
     * [counts]). The changes are counted along the cheapest reading. Without this a word the tables list after
     * the first was read however many of its letters had to be missed, added and swapped to get there, as no
     * other word is: "eke" was "week" after "a" (a letter missed and two swapped, in three taps), which made
     * "a week" of the name "anneke". On tool/typing_bench.sh taps (12,000 words, the careful thumb) that was one
     * right word in a thousand spoiled more, 1.32 where it is now 1.22, and 1.9% of the unknown words where it is
     * 1.5%; the seen and the unseen typings of tool/versus/scoreboard.py are the same with it and without.
     */
    private fun fits(word: String, from: Int, budget: Float): Float {
        val m = word.length
        val len = n - from
        if (m == 0 || kotlin.math.abs(m - len) > MAX_OMITS + MAX_INSERTS + 2) return Float.POSITIVE_INFINITY
        val most = 100 * when { len <= 3 -> 1; len <= 5 -> 2; len <= 8 -> 3; else -> 4 }
        // cost[j][i] and changes[j][i]: the first j letters of the word against the first i taps.
        var before2 = FloatArray(len + 1); var before = FloatArray(len + 1); var row = FloatArray(len + 1)
        var count2 = IntArray(len + 1); var count1 = IntArray(len + 1); var count = IntArray(len + 1)
        for (i in 1..len) { before[i] = before[i - 1] + insert(from + i - 1); count1[i] = count1[i - 1] + 100 }
        for (j in 1..m) {
            val c = word[j - 1]
            val last = if (j >= 2) word[j - 2] else 0.toChar()
            val free = c == '\'' || c == '’'
            row[0] = before[0] + omit(c, last); count[0] = count1[0] + (if (free) 0 else 100)
            var least = row[0]
            for (i in 1..len) {
                val s = sub(from + i - 1, c)
                var v = before[i - 1] + s
                var k = count1[i - 1] + (if (s == 0f) 0 else counts(s))
                val skip = before[i] + omit(c, last)
                if (skip < v) { v = skip; k = count1[i] + (if (free) 0 else 100) }
                val extra = row[i - 1] + insert(from + i - 1)
                if (extra < v) { v = extra; k = count[i - 1] + 100 }
                if (j >= 2 && i >= 2 && c == typed[from + i - 2] && last == typed[from + i - 1] && c != last) {
                    val swap = before2[i - 2] + SWAP
                    if (swap < v) { v = swap; k = count2[i - 2] + 100 }
                }
                row[i] = v; count[i] = k
                if (v < least) least = v
            }
            if (least > budget) return Float.POSITIVE_INFINITY
            val spare = before2; before2 = before; before = row; row = spare
            val spareN = count2; count2 = count1; count1 = count; count = spareN
        }
        return if (before[len] > budget || count1[len] > most) Float.POSITIVE_INFINITY else before[len]
    }

    /**
     * How much work [joins] has done since it was last asked: trie nodes walked, words held up against the taps
     * ([ALIGN_WORK] each) and pairs weighed. The time a join search takes is this, give or take; it is counted
     * so that the search can stop itself, and so that the tools can say what the worst word costs whatever else
     * the machine is doing ([JOIN_WORK_MOST]).
     */
    private var joinWork = 0

    /**
     * A first word of a join with the space after it settled: the tap the next word starts at, what the slips so
     * far cost (the space among them), what the word is worth less that, and whether the space was a tap ([bars] 1).
     */
    private class Lead(val index: Int, val from: Int, val spent: Float, val have: Float, val bars: Int)

    /**
     * The words the taps from [from] on begin with, each with where it ends and what its slips cost: any number of
     * letters read as keys their taps were near ([slip]: a thumb's slips, and no misspelling), and at most one
     * letter missed, added or swapped ("mnoey" is "money", "gve" is "gave"), within [JOIN_BUDGET] in all. Only
     * words at least [common], and never one that takes every tap that is left: another word is to follow. One
     * walk finds them for every place such a word could end. It walks the trie in the order of the letters, so it
     * stops at [until] (the join search's work so far, this walk's included) with whichever words it reached.
     */
    private fun starts(from: Int, common: Float, until: Float): List<Start> {
        val found = HashMap<Long, Float>()
        fun go(node: Int, i: Int, cost: Float, slipped: Boolean, last: Char) {
            if (cost > JOIN_BUDGET || trie.best[node] < common || ++joinWork > until) return
            if (i >= n) return
            val w = trie.word[node]
            if (w >= 0 && i > from && zipfs[w] >= common) { val key = (w.toLong() shl 16) or i.toLong(); val had = found[key]; if (had == null || cost < had) found[key] = cost }
            var q = trie.down[node]
            while (q >= 0) {
                val c = trie.ch[q]
                val s = slip(i, c)
                if (s < far) go(q, i + 1, cost + s, slipped, c)
                if (c == '\'' || c == '’') go(q, i, cost + APOSTROPHE, slipped, c)
                else if (!slipped && omit(c, last) <= JOIN_SLIP) go(q, i, cost + omit(c, last), true, c)
                q = trie.next[q]
            }
            if (slipped) return
            if (i > from && insert(i) <= JOIN_SLIP) go(node, i + 1, cost + insert(i), true, last)
            if (SWAP <= JOIN_SLIP && i + 1 < n && typed[i] != typed[i + 1]) {
                val a = trie.child(node, typed[i + 1])
                if (a >= 0) { val b = trie.child(a, typed[i]); if (b >= 0) go(b, i + 2, cost + SWAP, true, typed[i]) }
            }
        }
        go(0, from, 0f, false, 0.toChar())
        return found.map { (key, cost) -> Start((key shr 16).toInt(), (key and 0xffff).toInt(), cost) }
    }

    /**
     * Two words run together: every way the taps split into a first word and a second, with a tap between them that
     * may have been the space bar, or no tap at all (the space missed). [second] scores a second word after a first
     * (the place in the list of each): how common it is there, as a Zipf frequency. A join's worth is the first
     * word's frequency and the second's there, less 9 (two words are one in a billion times less likely than one,
     * so each must be common to be worth it), less the cost; only those worth at least [least] come back.
     *
     * The join search as it was before [joins], kept word for word for every language but English (Autocorrect's
     * run-together block takes this where English is not loaded): [joins] was set on English typing alone, and
     * nothing measures it in another language.
     */
    fun pairs(least: Float, firsts: (Int) -> Boolean, second: (first: Int, second: Int) -> Float, followers: (first: Int) -> IntArray): List<Join> {
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
                        if (second(first.index, f) - c >= need) out += Join(first.index, f, -1, spent + c, spaced, restFrom, spaced == 1)
                    }
                    if (need <= MAX_ZIPF) {
                        for (h in search(need, JOIN_BUDGET, restFrom)) {
                            if (h.index in seen) continue
                            if (second(first.index, h.index) - h.cost >= need) out += Join(first.index, h.index, -1, spent + h.cost, spaced, restFrom, spaced == 1)
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
        private const val APOSTROPHES = "'’"
        const val LONG = 5
        /** A neighbouring key in place of a character that had no tap of its own. */
        @JvmField var NEIGHBOUR = 1.6f
        /**
         * A tap too many ([insert]): [INSERT] a tap of its own, [INSERT_NEAR] one on a key next to the key typed
         * beside it, [INSERT_DOUBLED] a second tap on that key itself, [INSERT_FIRST] the word's first tap. A letter
         * with no tap ([omit]): [OMIT], [OMIT_DOUBLED] where it doubles the letter before it. Set as they were
         * before 2026-11-12. Where [search] is asked to reach ([Autocorrect.byTaps] on a board with English among its
         * lists), [REACH_INSERT] and [REACH_OMIT] stand in for [INSERT], [INSERT_NEAR] and [OMIT] on taps that landed
         * somewhere; [INSERT_DOUBLED], [INSERT_FIRST] and [OMIT_DOUBLED] still count there. Every other search, and
         * byTaps on any other board, goes by these alone.
         */
        @JvmField var INSERT = 2.8f
        @JvmField var INSERT_NEAR = 2.0f
        @JvmField var INSERT_DOUBLED = 1.4f
        @JvmField var INSERT_FIRST = 4.0f
        @JvmField var OMIT = 2.0f
        /**
         * Where the search reaches ([search]'s reach), a tap too many goes by where it landed: [INSERT_DOUBLED] and
         * what the tap costs to read as the key typed before it or after it, [REACH_INSERT] at the most. A thumb
         * that taps a letter twice scatters the second tap as it does any, so "thre" with its r just over the line
         * from e is "the" for little more than "thee" is; and a tap in the middle of a key next door was read out
         * of words for [INSERT_NEAR] (2): "fole" for "file" came out "for", "wer" for "wear" came out "we".
         *
         * Set on the scoreboard's seen and unseen sets and tool/typing_bench.sh tune's measure (words right less four
         * for every right word spoiled, on its 12,000 words and on its other 20,000). On the decoder with the short
         * word's reach, the common word's edits and the lone letter's, before the swap's: by where it landed at
         * [INSERT]'s 2.8, 5 words mended and none broken on the seen set, 9 and 1 on the unseen, the measures 94.13
         * to 94.22 and 94.36 to 94.47, and fewer right words spoiled on the 20,000 (1.30 and 1.46 in a thousand to
         * 1.18 and 1.38 for the careful and the normal thumb). With the rest of the reach (298 words wrong on the
         * seen set, 405 on the unseen, 94.30 and 94.55), against [REACH_OMIT] 1.8: 3.1 leaves 294 and 397, 94.38 and
         * 94.58; 3.4 leaves 293 and 396, 94.40 and 94.60; 3.8 leaves 293 and 395, 94.40 and 94.61. The bench's
         * thumb never adds a tap that is not a letter twice and the sets' thumbs seldom do, so past 3.4, where the
         * gain is one word, neither can say what a stray tap is worth.
         */
        @JvmField var REACH_INSERT = 3.4f
        /**
         * A letter with no tap where the search reaches ([search]'s reach), but for a doubled letter or an
         * apostrophe. Set as [REACH_INSERT] was, with it at 3.4 (OMIT 2.0 there: 295 words wrong on the seen set,
         * 402 on the unseen, 94.36 and 94.56): 1.6 leaves 293 and 392, 94.40 and 94.63, but spoils more of the
         * sloppy thumb's words (1.61 in a thousand to 1.74 on the 20,000, and of the right words no list has 6.2%
         * to 6.9%); 1.8 leaves 293 and 396, 94.40 and 94.60, for one right word more spoiled by the careful thumb
         * (1.24 to 1.30) and one fewer by the normal one (1.38 to 1.30). A letter left out is the slip most often
         * still lost to Gboard (docs/versus.md).
         */
        @JvmField var REACH_OMIT = 1.8f
        /**
         * [REACH_OMIT] for a letter left out after the last tap, where the search reaches: a word typed whole that
         * a listed word goes on from by a letter ("civilize", and any word with a -d or -s of its own to come). At
         * [REACH_OMIT] the bench's careful thumb had "civilize" made "civilized" three times in 12,000 words. Set
         * on the tree with the rest of the reach (LONE_OMIT 1.8): 2.0, as [OMIT], against 1.8 leaves 291 words
         * wrong on the seen set for 290 and 398 for 397 on the unseen, and on the bench's 12,000 words right words
         * spoiled 1.42, 1.61 and 1.65 in a thousand for the three thumbs to 1.32, 1.48 and 1.45 (1.22, 1.48 and
         * 1.65 before the reach), words right 96.75, 92.91 and 86.61% to 96.75, 92.91 and 86.62%.
         */
        @JvmField var REACH_OMIT_END = 2.0f
        @JvmField var OMIT_DOUBLED = 1.2f
        @JvmField var APOSTROPHE = 0.2f
        @JvmField var SWAP = 1.1f
        /** The space missed altogether between two words run together. */
        @JvmField var NO_SPACE = 3.2f
        /** The most the slips of any one of two or three joined words may cost. */
        @JvmField var JOIN_BUDGET = 3.4f
        /**
         * A first or middle word of a join may have one letter missed, added or swapped that costs no more than
         * this (at 1.5: a doubled letter missed or added, or two letters swapped); and three words run together
         * are looked for. Tried either side on tool/versus/scoreboard.py --bench, 2026-11-12, the rest as it then
         * stood (294 words of 5,205 seen left wrong, 408 of 6,408 unseen): with no such slip 296 and 411; at 2.1
         * or 2.9, which let any letter be missed or added, 295 and 409, and the bench's unknown words spoiled go
         * from 1.5%, 4.3% and 7.1% to 1.9%, 5.5% and 8.3%; without three words 300 and 412.
         */
        @JvmField var JOIN_SLIP = 1.5f
        @JvmField var JOIN_THREE = true
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
        /**
         * A word of up to [SHORT] letters may be changed by [SHORT_MORE] more than its one change where
         * [Autocorrect.byTaps] asks ([search]'s reach), and a reading that goes past the one change costs
         * [SHORT_PAST] more than its slips. One change does not cover a thumb that scatters over a word this
         * short: "the" comes out "yhw" and "rhd", "you" comes out "yih", "with" comes out "eit" (the h left out
         * and the w tapped on e), and none of those was read at all: 18 of the 199 words still lost to Gboard on
         * 2026-11-12 (docs/versus.md). Half a change more reads taps that were, between them, up to a change and
         * a half from the keys meant; two in the middles of the wrong keys ("tbr" typed squarely) are a change and
         * three quarters and are still another word.
         *
         * Set on the scoreboard's two sets and the bench's tuner together (tool/versus/scoreboard.py; tool/
         * typing_bench.sh tune, whose measure is words right less four for every right word spoiled, on its 12,000
         * words and on its other 20,000). On the decoder of 9468bfd with nothing else changed, as words mended and
         * broken on the seen set, the same on the unseen set, and the two measures (93.76 and 94.13 before): more
         * 0.25, 9 and 1, 4 and 1, 93.89 and 94.21; more 0.5, 12 and 1, 6 and 2, 93.92 and 94.23; more 0.5 past
         * 0.5, 11 and 0, 5 and 0, 93.91 and 94.22; more 0.5 past 1, 10 and 0, 5 and 0, 93.89 and 94.20; more 1
         * past 1, 10 and 0, 5 and 0, 93.92 and 94.21. With nothing to pay, more of the bench's right words that no
         * list has are spoiled (4.7% to 5.1% for its normal thumb on the 20,000); from half a point up they are
         * not. Reading past the bound only toward a word of some frequency (5, then 6) did nothing the half point
         * had not. Four letters and five ([SHORT] 4, 5) gain no word on either set and lose on the bench. In the
         * tree with the rest of the reach, taking this out leaves 309 words wrong on the seen set for 298, 413 for
         * 405 on the unseen, and 94.13 and 94.45 for 94.30 and 94.55.
         */
        @JvmField var SHORT = 3f
        @JvmField var SHORT_MORE = 0.5f
        @JvmField var SHORT_PAST = 0.5f
        /**
         * The least a letter left out, a letter tapped twice or two letters swapped costs where the typed letters
         * spell a common word and [Autocorrect.byTaps] asks ([search]'s reach); from [NEVER] up none is read, as
         * none was before 2026-11-12. A common word was read as another by slips of the thumb alone, and the lists
         * hold th, ot, su, tae, ff, ni, ou and soo as common words: they stood for the, to, us, take, off, in, out
         * and so however likely those were. The plain costs ([SWAP] 1.1, [OMIT_DOUBLED] 1.2, [INSERT_DOUBLED] 1.4)
         * were set where the typed letters are no word and little competes; against a word the lists hold each is
         * held to this. A tap that doubles nothing is never taken out of a common word, at any cost: tried, the
         * bench's "ink" came out "in" three times in 12,000 words.
         *
         * Set as [SHORT_MORE] was. On the decoder of 9468bfd with nothing else changed, as words mended and broken
         * on the seen set, the same on the unseen set and the two measures (93.76 and 94.13 before): no least, 12
         * and 4, 10 and 2, 93.94 and 94.23; 1.5, 11 and 2, 9 and 1, 93.93 and 94.24; 2, 11 and 2, 9 and 0, 93.92
         * and 94.23; 2.4, 5 and 1, 7 and 0, 93.84 and 94.19. In the tree with the rest of the reach (298 and 405
         * words wrong, 94.30 and 94.55): 1.6 leaves 298 and 409; 2.4 leaves 302 and 410, 94.27 and 94.53; never
         * leaves 308 and 418, 94.09 and 94.41. What it spoils on the bench is "th" made "the" five times and "nd"
         * made "and" once in 96,000 words typed, which are the bench's own "5th" and "2nd" with their digits
         * trimmed: the keyboard itself corrects nothing that follows a digit.
         */
        @JvmField var LISTED_EDIT = 2.0f
        /**
         * What a letter left out costs where the word was typed as one letter ("t" for "to", "n" for "in") and
         * [Autocorrect.byTaps] asks; from [NEVER] up, never. It stands in for [REACH_OMIT] and is held to no
         * [LISTED_EDIT], though every letter is a listed word. The lists hold each letter about as common as "ask"
         * (s 5.86, d 5.46, t 5.43, n 5.15, o 5.12), which a letter typed by itself is not: of the letters other than
         * a and i that the seen set typed alone and that were no slip of a or i, 10 were a word of two letters with
         * one of them gone (o for no, on and of, s for is, t, n and d for to, in and do) and 3 were meant (the "d"
         * of one sentence, by its three thumbs). A reading has still to beat the letter as the common word the lists
         * make it. 1.8 is an estimate: what a letter left out costs a thumb (0.4% of letters, 2.4) less how far
         * that count puts a lone letter under its listed frequency (3 meant in 5,205 words, among some ten letters,
         * is about 4.8 each, against 5.4: 0.6). One sentence is a thin count, and the estimate no finer than that.
         *
         * Until 2026-11-13 this was 1.4, from the same count made over the seen and the unseen set together, which
         * the unseen set is not to be used for; recounted on the seen set alone it is 1.8. In the tree with the rest
         * of the reach ([REACH_OMIT_END] 2.0), as words wrong on the seen set, the unseen set's total, and the
         * bench tuner's measure on its 12,000 words and its other 20,000: 1.8, 291, 398, 94.39 and 94.55; 1.4, 292,
         * 397, 94.41 and 94.59. With [REACH_OMIT_END] at 1.8: 1.0, 289, 395, 94.39 and 94.61; 1.4, 291, 396, 94.37
         * and 94.59; 1.8, 290, 397, 94.35 and 94.56; never, 292, 399, 94.27 and 94.54. The bench would take it a
         * little cheaper (no right word more spoiled either way); the seen set's count and its total say 1.8. Two
         * of the words it breaks on the seen set are [Autocorrect.rejoin]'s to mend: "o" and a tap on the bar's
         * edge was "on", and is "of" or "to" now by the time the next space asks (see docs/versus.md).
         */
        @JvmField var LONE_OMIT = 1.8f
        /**
         * The most that one of two taps in the wrong order may cost to read as a neighbour's key, where
         * [Autocorrect.byTaps] asks ([swap]); at 0 two letters are swapped letter for letter or not at all, as
         * before 2026-11-12. A thumb that types two letters in the wrong order scatters no less for it: "iculs" is
         * "could" with its c and o swapped and the o tapped on i, "conevrned" is "concerned". Never both of the
         * two: tried, it read the same words.
         *
         * In the tree with the rest of the reach (298 and 405 words wrong, 94.30 and 94.55): 0 leaves 302 and 411,
         * 94.22 and 94.48; 0.5 leaves 299 and 409, 94.28 and 94.53; 1.5 reads the same words as 1 but two. It
         * spoils one more of the bench's right words that no list has ("nvim" made "nick" by the careful thumb:
         * 2.7% to 2.9% of 479 on the 20,000).
         */
        @JvmField var SWAP_SLIP = 1.0f
        /** From this up, [LISTED_EDIT] and [LONE_OMIT] mean never. */
        const val NEVER = 90f
        /** Readings this far under the best one found are let go. */
        @JvmField var KEEP = 3f
        const val MAX_OMITS = 2
        const val MAX_INSERTS = 2
        /** A search that has looked at this many nodes stops with what it has: no word is worth a key press's wait. */
        const val MAX_VISITS = 40_000
        /**
         * [joins] stops with what it has at this much work: trie nodes walked, [ALIGN_WORK] for every word held up
         * against the taps, and one for every pair of words weighed. Each search for a last word walks no more
         * nodes than the work left, so the bound is a whole one, but for one [fits] (a word's letters against the
         * taps, held as [ALIGN_WORK]) and Autocorrect's own single search after it. The first words found are
         * tried likeliest first, so what is found is found early and the rest of the work is looking for better;
         * the walk that finds them goes in the order of the letters, and has [JOIN_START_SHARE] of the work at
         * most, so that it never leaves the rest none. Set on
         * tool/versus/scoreboard.py --bench, 2026-11-12: its 1,800 typings and the bench's 12,000 words read the
         * same at 16,000 as at 60,000, which is what it was first set to; at 8,000 the typings are still the same
         * and the bench's sloppy thumb has one join fewer parted; at 5,000 three words of the typings are lost,
         * at 3,000 nine. 16,000 is twice the least that loses anything here: a longer word list makes each search
         * longer, and this is to be tried again when there is one. A number with a point, as every dial is, so
         * that the bench can set it by name.
         */
        @JvmField var MAX_JOIN_WORK = 16_000f
        /**
         * The share of [MAX_JOIN_WORK] the walk for a join's first words ([starts]) may take before the rest has
         * any: where it took the lot, no join was looked for at all. Set to a half by reasoning, not measured.
         */
        @JvmField var JOIN_START_SHARE = 0.5f
        const val ALIGN_WORK = 8
        /** The work the last [joins] did, and the most any has done since this was set to 0: read by the tools and the tests, never by the keyboard. */
        @JvmField var JOIN_WORK_LAST = 0
        @JvmField var JOIN_WORK_MOST = 0
        /** No word is commoner than this, and no pair lifts a second word more than [PAIR_LIFT] over it. */
        const val MAX_ZIPF = 8f
        const val PAIR_LIFT = 1.5f
    }
}
