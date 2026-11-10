package com.infamousvague.devkeyboard

import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Swipe typing's decoder: a finger's path over the letters → the likeliest words, best first. No Android in it, so
 * tool/swipe-selfcheck.sh runs it against the real word lists off the device.
 *
 * One stroke may spell several words (Matt, 2026-11-10: "when trying to swipe to type 'hey what's up' in one
 * consistent swipe it just types 'hardship'"). The finger turns at letters, so the path's corners are where a word can
 * end: the path is cut at corners into one to [MAX_WORDS] pieces, each piece decoded as a word, and every reading,
 * the single word among them, scored alike:
 *
 *     0.55 × Σ log10 P(word)  +  [JOIN] × (words − 1)  −  6 × the mean distance from the path  −  [PIN] × the ends' misses
 *       −  [OFF] × the length of path the reading leaves unexplained
 *
 * P(word) is how likely the word is where it stands: its own frequency (zipf − 9 is its log10), lifted by how
 * often it follows the word before it in the starter tables (assets/ngram, [Lexicon.follows]): "what's" after "hey",
 * "up" after "what's", where "stop" after "what" is nowhere. The word before the first is the one before the cursor,
 * or "^" at a sentence's start. So each extra word costs its own unlikeliness, and [JOIN] gives a little back. A long
 * word swiped alone stays one word: "hardship" is not "hard ship", since "ship" after "hard" costs more than that.
 *
 * A word after the first is drawn from the corner where the last one ended, through its own letters: the glide
 * between two words is part of the second, so the pieces cover the path end to end.
 *
 * Distances are in key widths. The ends' misses are how far the finger came down from the first word's first key and
 * how far each piece ends from its word's last key: a finger starts and stops on a letter far more surely than it
 * passes through the middle ones, so "hello" is not "help" for ending a key short of the p.
 *
 * The unexplained length is how much of the path runs more than half a key from every one of the reading's lines: a
 * detour no word goes through. It counts in full however long the stroke, so "how you" can't skip the "are" the
 * finger went down to spell. So does path the reading is too short for: a word skipped whose letters lie along the
 * next word's own lines.
 */
class SwipeDecoder(private val lex: Lexicon, private val budgetMs: Long = BUDGET_MS) {

    interface Lexicon {
        /** Words whose [letters] start with [first] and end with [last]. */
        fun ends(first: Char, last: Char): List<String>
        /** How common [word] is: its zipf, or null when it isn't a word. */
        fun zipf(word: String): Float?
        /** [word]'s letters a to z, accents folded and apostrophes dropped: "what's" is "whats". */
        fun letters(word: String): String
        /**
         * What follows [prev] (lower case, or "^" for a sentence's start) in the starter tables: each next word in
         * lower case and its share of what followed, 0 to 1. Null where the tables have no row for [prev].
         */
        fun follows(prev: String): Map<String, Float>? = null
    }

    private class Cand(val word: String, val zipf: Float, val err: Float, val len: Float, val pin: Float, val off: Float) {
        val lower = word.lowercase()
        val initial = word.firstOrNull { it.isLetter() }?.lowercaseChar()
    }
    private class Partial(val words: List<String>, val lm: Float, val err: Float, val len: Float, val pin: Float, val off: Float, val point: Boolean = false, val cost: Float = 0f) {
        var score = 0f
    }

    /**
     * The likeliest readings of [path], best first, as text ("hey what's up"); [keys] are the letters' centres.
     * [before] is the word before the cursor in lower case, "^" at a sentence's start, or null when unknown.
     */
    fun decode(path: List<Pair<Float, Float>>, keys: Map<Char, Pair<Float, Float>>, keyW: Float, before: String? = null, max: Int = 5): List<String> =
        scored(path, keys, keyW, before, max).map { it.first }

    /** [decode]'s readings with their scores, for the self-check's messages. */
    fun scored(path: List<Pair<Float, Float>>, keys: Map<Char, Pair<Float, Float>>, keyW: Float, before: String? = null, max: Int = 5): List<Pair<String, Float>> {
        if (path.size < 2 || keyW <= 0f || keys.isEmpty()) return emptyList()
        val p = resample(path, (length(path) / (keyW / 4f)).roundToInt().coerceIn(16, 240))
        // Most strokes are one word: read the stroke as one first, and look for several only when no word explains it
        // (the best word strays, leaves path unexplained, or ends off its letter). It is the quick case by far.
        val deadline = System.nanoTime() + budgetMs * 1_000_000L
        val whole = words(p, keys, keyW, null)
        // Out of time or out of readings: the best single word, however loosely it fits, as the decoder always gave.
        fun asOneWord() = whole.ifEmpty { words(p, keys, keyW, null, loose = true) }.map { c -> Partial(listOf(c.word), likely(c, before?.let { rowOf(it) }), c.err * c.len, c.len, c.pin, c.off) }
            .sortedByDescending { score(it, keyW) }
            .map { capitalI(it.words[0]) to score(it, keyW) }.distinctBy { it.first }.take(max)
        whole.firstOrNull()?.let { top ->
            if (top.err <= keyW * ONE_WORD_FIT && top.off <= keyW * ONE_WORD_OFF && top.pin <= keyW * ONE_WORD_PIN) return asOneWord()
        }
        val (kp, straight) = keypoints(p, keys, keyW)
        val t = kp.size - 1
        val cache = HashMap<Long, List<Cand>>()
        // A piece's words: the first word of the reading starts near where the finger came down. A later one's first
        // letter is where the glide from the last word lands, which is one of the places inside the piece: a corner
        // if the finger turned there, a key pass if it ran straight over it.
        fun piece(a: Int, b: Int, first: Boolean) = cache.getOrPut((a.toLong() shl 32 or b.toLong()) * 2 + (if (first) 1 else 0)) {
            val landings = if (first) null else (a + 1..minOf(b, a + LANDING)).flatMap { near(keys, p[kp[it]], keyW, 0.8f).take(2) }.distinct()
            words(p.subList(kp[a], kp[b] + 1), keys, keyW, landings)
        }
        val compounds = HashMap<String, Boolean>()
        fun extend(prev: Partial, c: Cand, at: Int, point: Boolean = false): Partial {
            val last = prev.words.lastOrNull()?.lowercase()
            val context = last ?: before
            var cost = prev.cost
            if (last != null) {
                // A break where the finger ran straight on is less likely than one where it turned.
                if (straight[at]) cost += STRAIGHT
                // Two words that spell one ("some" "thing") are nearly always that one word swiped.
                val joined = last + c.lower
                if (compounds.getOrPut(joined) { (lex.zipf(joined) ?: 0f) >= COMPOUND_ZIPF }) cost += COMPOUND
            }
            return Partial(prev.words + c.word, prev.lm + likely(c, context?.let { rowOf(it) }), prev.err + c.err * c.len, prev.len + c.len, prev.pin + c.pin, prev.off + c.off, point, cost)
                .also { it.score = score(it, keyW) }
        }
        // A one-letter word ("I", "a") takes no length of path: it sits on a point, and the next word goes on from there.
        fun onePoint(b: Int, list: ArrayList<Partial>) {
            val q = p[kp[b]]
            val singles = near(keys, q, keyW, 0.7f).flatMap { c -> lex.ends(c, c).filter { it.length == 1 }.map { w -> w to keys.getValue(c) } }
            if (singles.isEmpty()) return
            val more = ArrayList<Partial>()
            for (prev in list) {
                if (prev.point || prev.words.size >= MAX_WORDS) continue
                for ((w, k) in singles) {
                    val zipf = lex.zipf(w) ?: continue
                    if (zipf < ONE_LETTER) continue
                    val miss = dist(q, k)
                    more += extend(prev, Cand(w, zipf, miss, keyW * 0.25f, miss, 0f), b, point = true)
                }
            }
            list += more
        }
        // best[b]: the best readings that end a word at keypoint b.
        val best = Array(t + 1) { ArrayList<Partial>() }
        best[0] += Partial(emptyList(), 0f, 0f, 0f, 0f, 0f)
        onePoint(0, best[0])
        for (b in 1..t) {
            // Out of time (a slow phone, a very long stroke): the best single word, rather than keep the person waiting.
            if (System.nanoTime() > deadline) return asOneWord()
            val here = ArrayList<Partial>()
            for (a in maxOf(0, b - MAX_SPAN) until b) {
                if (best[a].isEmpty()) continue
                for (prev in best[a]) {
                    if (prev.words.size >= MAX_WORDS) continue
                    // A word after the first is a short stretch of the path; only the first can be the whole stroke.
                    if (prev.words.isNotEmpty() && b - a > LATER_SPAN) continue
                    for (c in piece(a, b, prev.words.isEmpty())) {
                        // "I" then "important" from the same i is the one word, not two.
                        if (prev.point && c.initial == prev.words.last().firstOrNull()?.lowercaseChar()) continue
                        here += extend(prev, c, a)
                    }
                }
            }
            onePoint(b, here)
            here.sortByDescending { it.score }
            best[b] += here.take(BEAM)
        }
        if (best[t].isEmpty()) return asOneWord()
        return best[t].sortedByDescending { it.score }
            .map { r -> r.words.joinToString(" ") { capitalI(it) } to r.score }
            .distinctBy { it.first }.take(max)
    }

    private val rows = HashMap<String, Map<String, Float>?>()
    private fun rowOf(prev: String) = rows.getOrPut(prev) { lex.follows(prev) }

    /** log10 of how likely [c]'s word is after a word whose [row] of followers this is: see the class's note. */
    private fun likely(c: Cand, row: Map<String, Float>?): Float {
        val pair = row?.get(c.lower) ?: return c.zipf - 9f
        // Only ever a lift: the tables keep a word's commonest followers, so a pair they lack is not a rare one.
        return kotlin.math.log10(Math.pow(10.0, (c.zipf - 9f).toDouble()) + PAIRS * pair).toFloat()
    }

    private fun score(r: Partial, keyW: Float): Float {
        val mean = if (r.len > 0f) r.err / r.len / keyW else 0f
        return 0.55f * r.lm + JOIN * (r.words.size - 1) - 6f * mean - PIN * r.pin / keyW - OFF * r.off / keyW - r.cost
    }

    /**
     * The words a piece of the path could be, best first. With no [landings], it is the reading's first word and
     * starts near where the finger came down. Otherwise it starts at the place the last word ended on, glides from
     * there, and its first letter is one of the [landings]. Either way its last letter is near the piece's end.
     */
    private fun words(sub: List<Pair<Float, Float>>, keys: Map<Char, Pair<Float, Float>>, keyW: Float, landings: List<Char>?, loose: Boolean = false): List<Cand> {
        val first = landings == null
        val len = length(sub).coerceAtLeast(keyW * 0.25f)
        val n = (len / (keyW / 4f)).roundToInt().coerceIn(8, 48)
        // The piece as plain numbers: this loop runs for thousands of words a stroke, and a phone feels every object.
        val user = resample(sub, n)
        val ux = FloatArray(n) { user[it].first }
        val uy = FloatArray(n) { user[it].second }
        val starts = landings ?: near(keys, user.first(), keyW, 1.1f)
        val lasts = near(keys, user.last(), keyW, 1.1f)
        val out = ArrayList<Cand>()
        val lo = if (loose) 0f else len * 0.6f - keyW * 0.7f
        val hi = if (loose) Float.MAX_VALUE else len * 1.35f + keyW * 0.7f
        val reachLimit = keyW * if (loose) 1.4f else 1.2f
        for (f in starts) for (l in lasts) {
            val list = shapesOf(f, l, keys)
            if (list.isEmpty()) continue
            // A word whose lines are far longer than the piece, or far shorter, isn't it (the glide in counted too):
            // the list is in order of length, so only the words that could fit are looked at.
            val glide = if (first) 0f else hyp(ux[0] - list[0].xs[0], uy[0] - list[0].ys[0])
            var i = lowerBound(list, lo - glide)
            while (i < list.size && list[i].length + glide <= hi) {
                val shape = list[i++]
                if (!first && shape.word.length == 1 && shape.zipf < ONE_LETTER) continue
                // The word's points: its keys, after the glide's start for a later word.
                val m = shape.xs.size + if (first) 0 else 1
                if (kx.size < m) { kx = FloatArray(m * 2); ky = FloatArray(m * 2) }
                var o = 0
                if (!first) { kx[0] = ux[0]; ky[0] = uy[0]; o = 1 }
                System.arraycopy(shape.xs, 0, kx, o, shape.xs.size)
                System.arraycopy(shape.ys, 0, ky, o, shape.ys.size)
                // Quick test: every letter passes near the path, in order.
                var at = 0
                var ok = true
                for (k in 0 until m) {
                    while (at < n && hyp(ux[at] - kx[k], uy[at] - ky[k]) > reachLimit) at++
                    if (at >= n) { ok = false; break }
                }
                if (!ok) continue
                val pin = hyp(ux[n - 1] - kx[m - 1], uy[n - 1] - ky[m - 1]) + if (first) hyp(ux[0] - kx[0], uy[0] - ky[0]) else 0f
                val stray = straying(ux, uy, n, m, keyW)
                // A reading that skips a word is shorter than the finger's path, though the skipped letters may lie on
                // its own lines ("see tomorrow" for "see you tomorrow", along the top row): what the length doesn't
                // cover is unexplained too, beyond a fifth for the corners a thumb cuts.
                val ideal = shape.length + glide
                val short = (len - ideal - 0.2f * ideal - 0.3f * keyW).coerceAtLeast(0f)
                out += Cand(shape.word, shape.zipf, (stray + reach(ux, uy, n, m)) / 2, len, pin, maxOf(strayOff * len, short))
            }
        }
        out.sortByDescending { 0.55f * it.zipf - 6f * it.err / keyW - PIN * it.pin / keyW - OFF * it.off / keyW }
        return out.take(PER_PIECE)
    }

    /** A word, its keys in order (a doubled letter once: "hello" glides h-e-l-o), their lines' length, and its zipf. */
    private class Shape(val word: String, val xs: FloatArray, val ys: FloatArray, val length: Float, val zipf: Float)
    private val shapes = HashMap<Int, List<Shape>>()
    private var shapesFor: Map<Char, Pair<Float, Float>>? = null

    /**
     * The words from [first] to [last] as [Shape]s on these [keys], shortest lines first: made once per pair of
     * letters and layout, so a piece finds the words of about its length by halving the list.
     */
    private fun shapesOf(first: Char, last: Char, keys: Map<Char, Pair<Float, Float>>): List<Shape> {
        if (shapesFor !== keys) { shapes.clear(); shapesFor = keys }
        return shapes.getOrPut(first.code shl 16 or last.code) {
            lex.ends(first, last).mapNotNull { word ->
                val zipf = lex.zipf(word) ?: return@mapNotNull null
                val pts = ArrayList<Pair<Float, Float>>()
                var prev = ' '
                for (c in lex.letters(word)) { if (c != prev) pts += keys[c] ?: return@mapNotNull null; prev = c }
                if (pts.isEmpty()) null
                else Shape(word, FloatArray(pts.size) { pts[it].first }, FloatArray(pts.size) { pts[it].second }, length(pts), zipf)
            }.sortedBy { it.length }
        }
    }

    /** The first place in [list] whose length is at least [x]. */
    private fun lowerBound(list: List<Shape>, x: Float): Int {
        var lo = 0; var hi = list.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (list[mid].length < x) lo = mid + 1 else hi = mid }
        return lo
    }

    /** Forgets the words' shapes: call when the word lists change. */
    fun clear() { shapes.clear(); rows.clear() }

    /** Works out the shapes of the words from [first] to [last] on these [keys] ahead of a stroke that needs them. */
    fun warm(first: Char, last: Char, keys: Map<Char, Pair<Float, Float>>) { shapesOf(first, last, keys) }

    // Scratch space for one word's points and [reach]'s two rows, reused word after word (the decoder is used by one
    // thread at a time: Autocorrect holds it while it decodes).
    private var kx = FloatArray(32)
    private var ky = FloatArray(32)
    private var rowA = FloatArray(64)
    private var rowB = FloatArray(64)
    /** [straying]'s second answer: the share of the points more than half a key from every line. */
    private var strayOff = 0f

    /**
     * How far the finger strays from the word's lines (straight from key to key, the [m] points in kx/ky): the mean
     * distance of the path's [n] points from the nearest of them. Unlike pairing points by how far along each they
     * are, it doesn't grow with a word's length as a thumb cuts its corners, so a long word isn't beaten by the same
     * path cut into short ones. Sets [strayOff] too.
     */
    private fun straying(ux: FloatArray, uy: FloatArray, n: Int, m: Int, keyW: Float): Float {
        var total = 0f
        var off = 0
        val far = keyW * 0.5f
        for (j in 0 until n) {
            val qx = ux[j]; val qy = uy[j]
            var best = if (m == 1) hyp(qx - kx[0], qy - ky[0]) else Float.MAX_VALUE
            for (i in 1 until m) {
                val ax = kx[i - 1]; val ay = ky[i - 1]
                val dx = kx[i] - ax; val dy = ky[i] - ay
                val l2 = dx * dx + dy * dy
                val t = if (l2 <= 0f) 0f else (((qx - ax) * dx + (qy - ay) * dy) / l2).coerceIn(0f, 1f)
                val d = hyp(qx - ax - t * dx, qy - ay - t * dy)
                if (d < best) best = d
            }
            total += best
            if (best > far) off++
        }
        strayOff = off.toFloat() / n
        return total / n
    }

    /** How near the finger passes each of the word's [m] keys, taken in order along the path's [n] points: the mean miss. */
    private fun reach(ux: FloatArray, uy: FloatArray, n: Int, m: Int): Float {
        if (rowA.size < n) { rowA = FloatArray(n * 2); rowB = FloatArray(n * 2) }
        // cost[j]: the best total for the keys so far, the last of them met at point j or before.
        var cost = rowA
        var next = rowB
        java.util.Arrays.fill(cost, 0, n, 0f)
        for (k in 0 until m) {
            var bestBefore = Float.MAX_VALUE
            for (j in 0 until n) {
                if (cost[j] < bestBefore) bestBefore = cost[j]
                next[j] = bestBefore + hyp(ux[j] - kx[k], uy[j] - ky[k])
            }
            val swap = cost; cost = next; next = swap
        }
        var least = Float.MAX_VALUE
        for (j in 0 until n) if (cost[j] < least) least = cost[j]
        return least / m
    }

    private fun hyp(dx: Float, dy: Float) = sqrt(dx * dx + dy * dy)

    /** The letters within [limit] key widths of [q], nearest first, at most four. */
    private fun near(keys: Map<Char, Pair<Float, Float>>, q: Pair<Float, Float>, keyW: Float, limit: Float) = keys.entries
        .map { (c, k) -> c to dist(q, k) / keyW }
        .filter { it.second <= limit }.sortedBy { it.second }.take(4).map { it.first }

    /**
     * Where the path turns: its start, its end, and between them each sharpest point of a turn of [CORNER] degrees or
     * more. A turn is measured over a quarter, a half and three quarters of a key either side and the sharpest
     * taken: a quick back-and-forth (r to e and back) shows only close up, a wide bend only from further off. At most
     * [MAX_CORNERS], the sharpest kept.
     */
    private fun keypoints(p: List<Pair<Float, Float>>, keys: Map<Char, Pair<Float, Float>>, keyW: Float): Pair<List<Int>, BooleanArray> {
        val angle = FloatArray(p.size)
        for (w in 1..3) for (i in w until p.size - w) {
            val ax = p[i].first - p[i - w].first; val ay = p[i].second - p[i - w].second
            val bx = p[i + w].first - p[i].first; val by = p[i + w].second - p[i].second
            val la = sqrt(ax * ax + ay * ay); val lb = sqrt(bx * bx + by * by)
            if (la <= 0f || lb <= 0f) continue
            val a = Math.toDegrees(acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f).toDouble())).toFloat()
            // Close up, a turn has to be sharp to count: a quarter key's wobble is not a letter.
            angle[i] = maxOf(angle[i], if (w == 1) a - 20f else a)
        }
        val corners = ArrayList<Int>()
        for (i in 1 until p.size - 1) {
            if (angle[i] < CORNER) continue
            val lo = (i - 2).coerceAtLeast(0); val hi = (i + 2).coerceAtMost(p.size - 1)
            if ((lo..hi).all { it == i || angle[it] < angle[i] || (angle[it] == angle[i] && it > i) }) corners += i
        }
        val kept = corners.sortedByDescending { angle[it] }.take(MAX_CORNERS).filter { it in 2 until p.size - 2 }
        // Key passes: where the path runs over a letter's middle, turning or not. A word can end there and the next
        // go straight on ("you" in "see you soon", where the finger heads on the way it was going).
        val near = FloatArray(p.size) { i -> keys.values.minOf { dist(p[i], it) } }
        val passes = (2 until p.size - 2).filter { i ->
            near[i] <= keyW * PASS && (i - 2..i + 2).all { it == i || near[it] > near[i] || (near[it] == near[i] && it > i) } &&
                kept.none { kotlin.math.abs(it - i) <= 2 }
        }.sortedBy { near[it] }.take(MAX_POINTS - kept.size)
        val all = listOf(0) + (kept + passes).sorted() + (p.size - 1)
        val passSet = passes.toHashSet()
        return all to BooleanArray(all.size) { all[it] in passSet }
    }

    companion object {
        /** What each word after the first gets back of its cost: see the class's note. */
        const val JOIN = 0.5f
        /** How much a pair's share adds to a word's own frequency (both as probabilities). */
        const val PAIRS = 2f
        /** How much a key width's miss at a word's ends counts: see the class's note. */
        const val PIN = 2f
        /** How much each key width of unexplained path counts: see the class's note. */
        const val OFF = 2f
        const val MAX_WORDS = 6
        /** How long a stroke may take to read as several words before the best single word is taken instead. */
        const val BUDGET_MS = 1200L
        /** The stroke is taken as one word, with no search for several, when its best word strays at most this far on
         *  average (key widths), leaves at most [ONE_WORD_OFF] of path unexplained and ends within [ONE_WORD_PIN]. */
        const val ONE_WORD_FIT = 0.3f
        const val ONE_WORD_OFF = 1.0f
        const val ONE_WORD_PIN = 0.8f
        const val MAX_CORNERS = 16
        /** At most this many places a word can end, corners and key passes together. */
        const val MAX_POINTS = 32
        /** A later word's first letter is within this many places of where the last word ended: the glide's reach. */
        const val LANDING = 6
        /** A key pass is within this share of a key width of the letter's middle. */
        const val PASS = 0.3f
        /** A word spans at most this many of those places. */
        const val MAX_SPAN = 28
        /** A word after the first spans at most this many places. */
        const val LATER_SPAN = 20
        /** What a break costs where the finger ran straight on, over one at a corner. */
        const val STRAIGHT = 0.5f
        /** What two words cost that spell one word of at least [COMPOUND_ZIPF] together. */
        const val COMPOUND = 3f
        const val COMPOUND_ZIPF = 3f
        const val CORNER = 28f
        const val BEAM = 8
        const val PER_PIECE = 5
        /** A one-letter word inside a phrase must be this common ("a", "I"), so stray letters don't become words. */
        const val ONE_LETTER = 6f

        fun capitalI(word: String) = if (word == "i" || word.startsWith("i'")) "I" + word.substring(1) else word

        fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)

        fun length(pts: List<Pair<Float, Float>>): Float {
            var total = 0f
            for (i in 1 until pts.size) total += dist(pts[i - 1], pts[i])
            return total
        }

        /** [pts] as [n] points evenly spaced along its length. */
        fun resample(pts: List<Pair<Float, Float>>, n: Int): List<Pair<Float, Float>> {
            val total = length(pts)
            if (total <= 0f) return List(n) { pts.first() }
            val step = total / (n - 1)
            val out = ArrayList<Pair<Float, Float>>(n)
            out += pts.first()
            var acc = 0f
            var i = 1
            var prev = pts.first()
            while (i < pts.size && out.size < n) {
                val cur = pts[i]
                val d = dist(prev, cur)
                if (acc + d >= step && d > 0f) {
                    val t = (step - acc) / d
                    val q = (prev.first + t * (cur.first - prev.first)) to (prev.second + t * (cur.second - prev.second))
                    out += q; prev = q; acc = 0f
                } else { acc += d; prev = cur; i++ }
            }
            while (out.size < n) out += pts.last()
            return out
        }
    }
}
