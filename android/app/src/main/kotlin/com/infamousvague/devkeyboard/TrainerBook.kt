package com.infamousvague.devkeyboard

/**
 * What the typing trainer keeps from one run to the next (docs/trainer.md): the runs themselves as a line each, where
 * the taps meant for each key have landed, the words and letter pairs missed, and the layout trials. Plain Kotlin
 * with no Android in it; Trainer.kt keeps one on the phone, in a file of its own.
 *
 * It holds the story's words only where they were mistyped, and nothing the person wrote: a story is not their
 * writing. Nothing here is sent anywhere, and "Forget what the trainer learned" deletes all of it.
 */

/** One counted run, as the history shows it. */
class RunLine(
    val at: Long, val story: String, val title: String, val practice: Boolean, val height: String, val trial: Boolean,
    val ms: Long, val wpm: Float, val accuracy: Float, val words: Int, val right: Int, val firstTry: Int, val backspaces: Int, val mended: Int, val spoiled: Int,
)

/**
 * How often something was typed and how often it went wrong, over every run ([n], [missed]) and lately ([recentN],
 * [recentMissed]: each run it turns up in, what was there counts [FADE] as much). [trend] sets the two side by side.
 */
class Tally(var n: Int = 0, var missed: Int = 0, var recentN: Float = 0f, var recentMissed: Float = 0f) {
    fun add(count: Int, wrong: Int) {
        if (count <= 0) return
        n += count; missed += wrong.coerceIn(0, count)
        recentN = recentN * FADE + count
        recentMissed = recentMissed * FADE + wrong.coerceIn(0, count)
    }

    val rate: Float get() = if (n == 0) 0f else missed.toFloat() / n
    val recentRate: Float get() = if (recentN < 1f) rate else recentMissed / recentN

    /** -1 where it goes wrong less often lately than over all, 1 where more, 0 where neither can be said yet. */
    val trend: Int get() = when {
        n < 20 || recentN < 6f || missed < 2 -> 0
        recentRate < rate * 0.7f -> -1
        recentRate > rate * 1.3f && recentMissed >= 2f -> 1
        else -> 0
    }

    companion object { const val FADE = 0.6f }
}

/** A story word across runs: how often it was tried and missed at the first try, what was typed for it, and how its latest tries went ([last]: 1 right, 0 missed, newest at the end). */
class WordNote(var seen: Int = 0, var missed: Int = 0, var last: String = "") {
    val forms = LinkedHashMap<String, Int>()

    fun add(right: Boolean, typed: String) {
        seen++
        if (!right) {
            missed++
            if (typed.isNotEmpty()) {
                forms[typed] = (forms[typed] ?: 0) + 1
                if (forms.size > MAX_FORMS) forms.remove(forms.minByOrNull { it.value }!!.key)
            }
        }
        last = (last + if (right) '1' else '0').takeLast(MAX_LAST)
    }

    /** How many tries in a row, up to now, it was typed right. */
    val streak: Int get() = last.length - 1 - last.lastIndexOf('0').let { if (it < 0) -1 else it }

    companion object { const val MAX_FORMS = 4; const val MAX_LAST = 8 }
}

/** A word missed across runs: the [word], what was [typed] for it most often, how often of how many tries, how the latest tries went, and how many in a row it has now been right. */
class TrainerMistake(val word: String, val typed: String, val missed: Int, val seen: Int, val last: String, val streak: Int)

/** A pair of letters whose second goes wrong often: how often of how many, and whether that is getting better (-1), worse (1) or neither (0). */
class WeakPair(val pair: String, val missed: Int, val n: Int, val trend: Int)

/** A key missed often: how often of how many taps meant for it, the neighbour hit most in its place (or null), and which way that is going. */
class WeakKey(val key: Char, val missed: Int, val n: Int, val toward: Char?, val trend: Int)

/** Where the taps meant for a key land about its middle, in fractions of the key, on how many taps. */
class ProfileKey(val key: Char, val dx: Float, val dy: Float, val samples: Int)

/** A slip: the key [pressed] where [meant] was meant, how often. */
class ProfileSlip(val pressed: Char, val meant: Char, val count: Int)

/** What the runs add up to, once there are [TrainerBook.PROFILE_RUNS] of them. */
class TrainerProfile(
    val runs: Int, val wpmFirst: Float, val wpmNow: Float, val wpmBest: Float, val accuracyFirst: Float, val accuracyNow: Float,
    val layout: String, val keys: List<ProfileKey>, val lean: String, val slips: List<ProfileSlip>,
    val mistakes: List<TrainerMistake>, val pairs: List<WeakPair>, val suggestion: LayoutTrials.Suggestion?, val report: String,
)

class TrainerBook {
    /** "Try different layouts" on the trainer's start screen: off unless the person turns it on, and read as each run starts. */
    var trials = false

    /** Counted runs, oldest first, at most [MAX_RUNS]. */
    val runs = ArrayList<RunLine>()

    /** The story parts typed so far, by id, oldest first: what [TrainerStories.next] goes by. */
    val read = ArrayList<String>()

    /** By layout (Layouts.SPECS' ids), where the taps meant for each letter key have landed. */
    val aims = HashMap<String, HashMap<Char, TrainerAims.Measure>>()

    /** By layout, each slip between two neighbouring keys as "pressed>meant", and how often. */
    val slips = HashMap<String, HashMap<String, Int>>()

    /** By layout, how far the trainer has moved each key's nudge in Adaptive keys' model, across and down: what "Forget" takes back. */
    val given = HashMap<String, HashMap<Char, FloatArray>>()

    val keys = HashMap<Char, Tally>()
    val pairs = HashMap<String, Tally>()
    val words = HashMap<String, WordNote>()
    val layouts = LayoutTrials()

    /** Runs of a story (a practice run of one's own mistakes is no measure of speed): what the profile waits for. */
    val storyRuns: Int get() = runs.count { !it.practice }

    /** A counted run joins the book: its line, its taps by key, its slips, its letter pairs, and its words' first tries. */
    fun add(line: RunLine, result: RunResult, layout: String) {
        runs += line
        while (runs.size > MAX_RUNS) runs.removeAt(0)
        val measured = aims.getOrPut(layout) { HashMap() }
        for ((key, stat) in result.perKey) {
            if (stat.aimed > 0) measured.getOrPut(key) { TrainerAims.Measure() }.add(stat.aimed, stat.sumX, stat.sumY)
            keys.getOrPut(key) { Tally() }.add(stat.taps, stat.missed)
        }
        val slipped = slips.getOrPut(layout) { HashMap() }
        for ((pair, n) in result.slipPairs) slipped[pair] = ((slipped[pair] ?: 0) + n).coerceAtMost(100_000)
        for ((pair, stat) in result.perPair) pairs.getOrPut(pair) { Tally() }.add(stat.taps, stat.misses)
        for (t in result.tries) {
            if (t.meant.length < 2 || t.meant.length > MAX_WORD || !t.meant.all { it.isLetter() || it == '\'' }) continue
            words.getOrPut(t.meant) { WordNote() }.add(t.right, t.typed.take(MAX_WORD))
        }
        // Bounded: the words never missed are the ones let go, the least seen first.
        if (words.size > MAX_WORDS) {
            val spare = words.entries.filter { it.value.missed == 0 }.sortedBy { it.value.seen }.take(words.size - MAX_WORDS + MAX_WORDS / 10)
            for (e in spare) words.remove(e.key)
        }
    }

    /**
     * The words missed at their first try, the ones missed more than once first, and among those the ones still
     * going wrong before the ones that have since come right.
     */
    fun mistakes(limit: Int = 12): List<TrainerMistake> =
        words.entries.filter { it.value.missed > 0 }
            .sortedWith(compareBy<Map.Entry<String, WordNote>>({ if (it.value.missed >= 2) 0 else 1 }, { it.value.streak.coerceAtMost(3) }, { -it.value.missed }, { it.key }))
            .take(limit)
            .map { (word, note) -> TrainerMistake(word, note.forms.maxByOrNull { it.value }?.key.orEmpty(), note.missed, note.seen, note.last, note.streak) }

    /** The letter pairs that go wrong most for how often they are typed, among those typed enough to say. */
    fun weakPairs(limit: Int = 8): List<WeakPair> =
        pairs.entries.filter { it.value.n >= PAIR_MIN && it.value.missed >= 3 && it.value.rate >= 0.06f }
            .sortedWith(compareBy({ -it.value.rate }, { it.key })).take(limit).map { WeakPair(it.key, it.value.missed, it.value.n, it.value.trend) }

    /** The keys missed most for how often they are meant, with the neighbour hit in their place on [layout]. */
    fun weakKeys(layout: String, limit: Int = 6): List<WeakKey> {
        val slipped = slips[layout].orEmpty()
        return keys.entries.filter { it.value.n >= TrainerAims.MIN_SAMPLES && it.value.missed >= 2 && it.value.rate >= 0.04f }
            .sortedWith(compareBy({ -it.value.rate }, { it.key })).take(limit)
            .map { (key, tally) ->
                val toward = slipped.entries.filter { it.key.length == 3 && it.key[2] == key }.maxByOrNull { it.value }?.key?.get(0)
                WeakKey(key, tally.missed, tally.n, toward, tally.trend)
            }
    }

    /**
     * A run made of the person's own mistakes: the words they miss, three times each, and for each letter pair that
     * goes wrong a few words of [vocabulary] (the stories' own) that have it, twice each, in an order [seed] decides.
     * Null while there is too little to practise.
     */
    fun practice(vocabulary: List<String>, seed: Long): String? {
        val random = java.util.Random(seed)
        val missed = mistakes(8).map { it.word }.filter { w -> w.all { it.isLetter() } }
        val more = ArrayList<String>()
        for (pair in weakPairs(3)) {
            val having = vocabulary.filter { pair.pair in it && it !in missed && it !in more && it.all { c -> c.isLetter() } }
            if (having.isEmpty()) continue
            val from = random.nextInt(having.size)
            for (k in 0 until minOf(3, having.size)) more += having[(from + k * 7) % having.size].let { w -> if (w in more) having[(from + k) % having.size] else w }
        }
        val pool = missed + more.distinct()
        if (pool.size < PRACTICE_MIN) return null
        val list = ArrayList<String>()
        for (w in missed) repeat(3) { list += w }
        for (w in more.distinct()) repeat(2) { list += w }
        list.shuffle(random)
        // The same word twice running is swapped with the next one that differs.
        for (i in 1 until list.size) if (list[i] == list[i - 1]) {
            val j = (i + 1 until list.size).firstOrNull { list[it] != list[i] } ?: continue
            val t = list[i]; list[i] = list[j]; list[j] = t
        }
        val text = list.take(PRACTICE_WORDS).joinToString(" ")
        return text.replaceFirstChar { it.uppercase() } + "."
    }

    /** What the runs on [layout] add up to; null until [PROFILE_RUNS] stories have been typed. */
    fun profile(layout: String, board: Map<Char, Pair<Float, Int>>): TrainerProfile? {
        val story = runs.filter { !it.practice }
        if (story.size < PROFILE_RUNS) return null
        fun mean(list: List<Float>): Float = list.filter { !it.isNaN() }.let { if (it.isEmpty()) Float.NaN else it.sum() / it.size }
        val first = story.take(3)
        val now = story.takeLast(3)
        val measured = aims[layout].orEmpty().filter { it.value.total >= TrainerAims.MIN_SAMPLES }
        val profileKeys = measured.entries.sortedBy { it.key }.map { ProfileKey(it.key, it.value.x, it.value.y, it.value.total) }
        val profileSlips = slips[layout].orEmpty().entries.filter { it.key.length == 3 && it.value >= 2 }.sortedWith(compareBy({ -it.value }, { it.key })).take(8)
            .map { ProfileSlip(it.key[0], it.key[2], it.value) }
        val words = mistakes(6).filter { it.missed >= 2 }
        val weak = weakPairs(5)
        val lean = lean(measured, board)
        val wpmNow = mean(now.map { it.wpm })
        val wpmFirst = mean(first.map { it.wpm })
        val accuracyNow = mean(now.map { it.accuracy })
        val report = buildString {
            append("Over ${story.size} runs: ${Math.round(wpmNow)} words a minute")
            if (Math.round(wpmFirst) != Math.round(wpmNow)) append(" (${Math.round(wpmFirst)} at first)")
            if (!accuracyNow.isNaN()) append(", ${Math.round(accuracyNow * 100)}% of taps on their key")
            append(". ").append(lean)
            if (profileSlips.isNotEmpty()) append(" Slips: ").append(profileSlips.take(3).joinToString(", ") { "${it.pressed} for ${it.meant}" }).append(".")
            if (words.isNotEmpty()) append(" Words missed more than once: ").append(words.take(4).joinToString(", ") { it.word }).append(".")
        }
        return TrainerProfile(
            runs = story.size, wpmFirst = wpmFirst, wpmNow = wpmNow, wpmBest = story.maxOf { it.wpm },
            accuracyFirst = mean(first.map { it.accuracy }), accuracyNow = accuracyNow,
            layout = layout, keys = profileKeys, lean = lean, slips = profileSlips, mistakes = words, pairs = weak,
            suggestion = layouts.suggestion(), report = report,
        )
    }

    /** Everything the trainer has kept, gone; the switch goes back to off. */
    fun forget() {
        trials = false
        runs.clear(); read.clear(); aims.clear(); slips.clear(); given.clear(); keys.clear(); pairs.clear(); words.clear()
        layouts.restore("normal", "normal", null, emptyList(), emptyList(), emptyList(), emptyMap())
    }

    companion object {
        /**
         * The profile is drawn up after this many stories. Five parts are about 250 words and 1,100 letter taps: every
         * letter but the rare ones (j, q, x, z) then stands on more taps than the fifteen Adaptive keys asks before
         * it uses a nudge, five steps of [TrainerAims.STEP] have had room to cover a lean of a fifth of a key, and a
         * trend read off the first three runs and the last three is not one run's doing.
         */
        const val PROFILE_RUNS = 5
        const val MAX_RUNS = 300
        const val MAX_WORDS = 1_500
        const val MAX_WORD = 24

        /** A letter pair is called weak only once it has been typed this often. */
        const val PAIR_MIN = 12

        /** A practice run needs at least this many different words, and is at most [PRACTICE_WORDS] long. */
        const val PRACTICE_MIN = 3
        const val PRACTICE_WORDS = 36

        /** A lean under this (of a key) is none worth saying. */
        private const val LEVEL = 0.04f

        private fun amount(v: Float): String = when {
            kotlin.math.abs(v) < 0.1f -> "a little "
            kotlin.math.abs(v) < 0.2f -> ""
            else -> "well "
        }

        /**
         * Which way the taps lean off the keys' middles, in a sentence: the mean of every measured key, each weighed
         * by its taps, and the row that leans furthest down or up where one stands out.
         */
        fun lean(measured: Map<Char, TrainerAims.Measure>, board: Map<Char, Pair<Float, Int>>): String {
            val total = measured.values.sumOf { it.n }
            if (total == 0) return "Not enough taps yet to say where they land."
            val x = (measured.values.sumOf { (it.x * it.n).toDouble() } / total).toFloat()
            val y = (measured.values.sumOf { (it.y * it.n).toDouble() } / total).toFloat()
            val across = if (kotlin.math.abs(x) < LEVEL) null else amount(x) + (if (x > 0) "to the right of" else "to the left of")
            val down = if (kotlin.math.abs(y) < LEVEL) null else amount(y) + (if (y > 0) "below" else "above")
            val all = when {
                across != null && down != null -> "Your taps land $across a key's middle and $down it."
                across != null -> "Your taps land $across a key's middle."
                down != null -> "Your taps land $down a key's middle."
                else -> "Your taps land in the middle of the keys."
            }
            // The row whose taps sit furthest off, where it is clearly further than the rest.
            val rows = (0..2).mapNotNull { r ->
                val inRow = measured.filter { board[it.key]?.second == r }.values
                val n = inRow.sumOf { it.n }
                if (n < TrainerAims.MIN_SAMPLES * 3) null else r to (inRow.sumOf { (it.y * it.n).toDouble() } / n).toFloat()
            }
            val far = rows.maxByOrNull { kotlin.math.abs(it.second) }
            val names = listOf("top", "middle", "bottom")
            return if (far != null && kotlin.math.abs(far.second) >= 0.1f && kotlin.math.abs(far.second) >= kotlin.math.abs(y) + 0.05f)
                "$all Most on the ${names[far.first]} row, ${amount(far.second)}${if (far.second > 0) "low" else "high"}."
            else all
        }
    }
}
