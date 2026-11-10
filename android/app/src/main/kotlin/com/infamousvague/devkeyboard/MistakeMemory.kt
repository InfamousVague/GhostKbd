package com.infamousvague.devkeyboard

import java.util.Locale

/**
 * What the keyboard has learned from the person's own mistakes (Matt, 2026-11-10: "I want the models to be trained off
 * of previous mistakes"). Plain Kotlin with no Android in it, so tool/typing_bench.sh and the unit tests use it on a
 * desk; [Mistakes] keeps one on the phone.
 *
 * Two kinds of mistake are remembered, each as what was there and what it was meant to be, with how often:
 * - a word as it was typed and the word it was put right to ("kria" was "kira"): by an autocorrection that was left
 *   to stand (half a count: it may not have been looked at), by typing the right word over it, by taking the right
 *   word from the row, and by Mockingbird's repair of a slip in something already sent;
 * - a stroke's first reading and the reading chosen over it in the row ("form" when "from" was swiped).
 *
 * A typed word's remembered meaning is gone by once it has been seen twice and leads any other meaning of the same
 * word ([meant]): autocorrect then makes that fix whatever the taps say, unless what was typed is a common word.
 * A fix that is undone is forgotten ([forget]). Counts fade ([fade]) and the whole is bounded.
 *
 * Kept as a text file, a header then a line a mistake, tabs between the parts:
 * ```
 * #ghost-mistakes	1	events=12
 * t	kria	kira:2.00
 * s	form	from:3.00	for:1.00
 * ```
 * Not safe to share between threads: [Mistakes] holds it on one.
 */
class MistakeMemory {
    /** What a mistake was meant to be, and how often (faded). */
    class Meant(val word: String, var count: Float)

    private val typed = HashMap<String, ArrayList<Meant>>()
    private val swiped = HashMap<String, ArrayList<Meant>>()

    /** How many mistakes have been learned in all, and the count at which the last fading was done. */
    var events = 0
        private set
    private var fadedAt = 0

    /** How many pairs are held, of each kind. */
    val typedPairs: Int get() = typed.entries.sumOf { (w, row) -> row.count { it.word != w } }
    val swipedPairs: Int get() = swiped.entries.sumOf { (w, row) -> row.count { it.word != w } }

    private fun add(map: HashMap<String, ArrayList<Meant>>, wrong: String, meant: String, weight: Float) {
        if (wrong.isEmpty() || meant.isEmpty() || wrong == meant || wrong.length > MAX_CHARS || meant.length > MAX_CHARS * 2 || weight <= 0f) return
        if (wrong.any { it == '\t' || it == '\n' } || meant.any { it == '\t' || it == '\n' || it == ':' }) return
        val row = map.getOrPut(wrong) { ArrayList(2) }
        val had = row.firstOrNull { it.word == meant }
        if (had != null) had.count = (had.count + weight).coerceAtMost(MAX_COUNT) else {
            if (row.size >= MAX_MEANINGS) row.remove(row.minByOrNull { it.count })
            row += Meant(meant, weight)
        }
        events++
    }

    /** [wrong] (lower case, as typed) was meant to be [meant] (lower case; two words where a space was missed). */
    fun learn(wrong: String, meant: String, weight: Float = 1f) = add(typed, wrong, meant, weight)

    /** A stroke read as [read] was meant to be [chosen] (both lower case). */
    fun learnSwipe(read: String, chosen: String, weight: Float = 1f) = add(swiped, read, chosen, weight)

    /**
     * A stroke read as [read] was meant as it was read. Counted only where something has been chosen over that
     * reading before: it is what keeps a reading the person means half the time from being swapped away for good.
     * True where that changed anything.
     */
    fun stood(read: String, weight: Float = 1f): Boolean = own(swiped, read, weight)

    /**
     * [typed] was left to stand as it was typed. Counted only where it has been a mistake for something before (so
     * a word that is as often meant as mistyped, than beside then, is not mended away), and taken back with a
     * [weight] below zero when it turns out not to have stood after all. True where that changed anything.
     */
    fun kept(typed: String, weight: Float = 1f): Boolean = own(this.typed, typed, weight)

    private fun own(map: HashMap<String, ArrayList<Meant>>, word: String, weight: Float): Boolean {
        val row = map[word] ?: return false
        val had = row.firstOrNull { it.word == word }
        if (had == null) { if (weight <= 0f) return false; row += Meant(word, weight); return true }
        had.count = (had.count + weight).coerceAtMost(MAX_COUNT)
        if (had.count <= 0.01f) { row.remove(had); if (row.isEmpty()) map.remove(word) }
        return true
    }

    /** The typed words something is remembered of: the only ones [kept] has anything to count for. */
    fun mistyped(): Set<String> = HashSet(typed.keys)

    /** [wrong] was not meant to be [meant] after all: the fix was undone. */
    fun forget(wrong: String, meant: String) { typed[wrong]?.let { row -> row.removeAll { it.word.equals(meant, ignoreCase = true) }; if (row.isEmpty()) typed.remove(wrong) } }

    /** The reading chosen over [read] was taken back. */
    fun forgetSwipe(read: String, chosen: String) { swiped[read]?.let { row -> row.removeAll { it.word.equals(chosen, ignoreCase = true) }; if (row.isEmpty()) swiped.remove(read) } }

    /**
     * What [wrong] is gone by as: its likeliest meaning, once that has counted [TAKEN] and leads the next by
     * [LEAD]; null where nothing is settled.
     */
    fun meant(wrong: String): String? {
        val row = typed[wrong] ?: return null
        val best = row.maxByOrNull { it.count } ?: return null
        // Meant as it was typed more often than as anything else: no mistake.
        if (best.word == wrong || best.count < TAKEN) return null
        val next = row.filter { it !== best }.maxOfOrNull { it.count } ?: 0f
        return if (best.count - next >= LEAD) best.word else null
    }

    /** Every settled meaning ([meant]) by what was typed. */
    fun settled(): Map<String, String> {
        val out = HashMap<String, String>()
        for (wrong in typed.keys) meant(wrong)?.let { out[wrong] = it }
        return out
    }

    /** For each stroke reading that something was chosen over: the readings chosen, and how often. */
    fun chosen(): Map<String, Map<String, Float>> = swiped.mapValues { (_, row) -> row.associate { it.word to it.count } }

    /**
     * Fades what is held: every [FADE_EVERY] mistakes learned, each count is multiplied by [FADE], and one under
     * [FLOOR] is dropped; and past [MAX_PAIRS] of a kind the least counted go.
     */
    fun fade() {
        val steps = (events - fadedAt) / FADE_EVERY
        if (steps > 0) {
            fadedAt += steps * FADE_EVERY
            val f = Math.pow(FADE.toDouble(), steps.toDouble()).toFloat()
            for (map in listOf(typed, swiped)) {
                val each = map.values.iterator()
                while (each.hasNext()) {
                    val row = each.next()
                    row.forEach { it.count *= f }
                    row.removeAll { it.count < FLOOR }
                    if (row.isEmpty()) each.remove()
                }
            }
        }
        for (map in listOf(typed, swiped)) {
            var over = map.values.sumOf { it.size } - MAX_PAIRS
            if (over <= 0) continue
            val least = map.entries.sortedBy { e -> e.value.maxOf { it.count } }
            for (e in least) { if (over <= 0) break; over -= e.value.size; map.remove(e.key) }
        }
    }

    fun write(out: Appendable) {
        out.append(HEADER).append('\t').append(VERSION.toString()).append("\tevents=").append(events.toString()).append("\tfaded=").append(fadedAt.toString()).append('\n')
        for ((kind, map) in listOf("t" to typed, "s" to swiped)) for (wrong in map.keys.sorted()) {
            out.append(kind).append('\t').append(wrong)
            for (m in map.getValue(wrong).sortedByDescending { it.count }) out.append('\t').append(m.word).append(':').append(String.format(Locale.ROOT, "%.2f", m.count))
            out.append('\n')
        }
    }

    companion object {
        const val VERSION = 1
        private const val HEADER = "#ghost-mistakes"
        /** A meaning is gone by once it has counted this much and leads the next by [LEAD]. */
        const val TAKEN = 2f
        const val LEAD = 1f
        const val MAX_CHARS = 24
        const val MAX_MEANINGS = 4
        const val MAX_PAIRS = 3000
        const val MAX_COUNT = 50f
        const val FADE_EVERY = 3000
        const val FADE = 0.8f
        const val FLOOR = 0.3f

        /** How many samples a slip must have been mended in, the same way, for Mockingbird's repair of it to be gone by. */
        const val REPAIRED = 2

        /**
         * The slips mended in [samples] (each as typed, null where nothing was mended, and as cleaned): what was
         * typed, in lower case, to what it was mended to, where that happened [REPAIRED] times or more, always the
         * same way, and the typed spelling does not also stand unmended more often than that.
         */
        fun repairsIn(samples: List<Pair<String?, String>>): Map<String, String> {
            val mended = HashMap<String, HashMap<String, Int>>()
            val stands = HashMap<String, Int>()
            for ((raw, text) in samples) {
                val clean = SampleCleaner.tokens(text)
                for (t in clean) { val w = t.word.lowercase(); stands[w] = (stands[w] ?: 0) + 1 }
                if (raw == null) continue
                val typed = SampleCleaner.tokens(raw)
                if (typed.size != clean.size) continue
                for (i in typed.indices) {
                    val a = typed[i].word.lowercase()
                    val b = clean[i].word.lowercase()
                    if (a == b || a.length < 2 || !alike(a, b)) continue
                    val row = mended.getOrPut(a) { HashMap(2) }
                    row[b] = (row[b] ?: 0) + 1
                }
            }
            val out = HashMap<String, String>()
            for ((a, row) in mended) {
                if (row.size != 1) continue
                val (b, n) = row.entries.first()
                if (n >= REPAIRED && (stands[a] ?: 0) <= n) out[a] = b
            }
            return out
        }

        /** Reads a file's [lines]; null where they are not a file this keyboard reads (no header, or a newer version). */
        fun read(lines: Sequence<String>): MistakeMemory? {
            val m = MistakeMemory()
            var headed = false
            for (line in lines) {
                val parts = line.split('\t')
                if (!headed) {
                    if (parts.size < 2 || parts[0] != HEADER || (parts[1].toIntOrNull() ?: Int.MAX_VALUE) > VERSION) return null
                    for (p in parts.drop(2)) {
                        if (p.startsWith("events=")) m.events = p.substring(7).toIntOrNull() ?: 0
                        if (p.startsWith("faded=")) m.fadedAt = p.substring(6).toIntOrNull() ?: 0
                    }
                    headed = true
                    continue
                }
                if (parts.size < 3) continue
                val map = when (parts[0]) { "t" -> m.typed; "s" -> m.swiped; else -> continue }
                val wrong = parts[1]
                if (wrong.isEmpty() || wrong.length > MAX_CHARS) continue
                val row = ArrayList<Meant>(2)
                for (e in parts.drop(2)) {
                    val colon = e.lastIndexOf(':')
                    val n = if (colon > 0) e.substring(colon + 1).toFloatOrNull() else null
                    if (n == null || n <= 0f || n.isNaN() || row.size >= MAX_MEANINGS) continue
                    row += Meant(e.substring(0, colon), n.coerceAtMost(MAX_COUNT))
                }
                if (row.isNotEmpty()) map[wrong] = row
            }
            if (!headed) return null
            if (m.fadedAt > m.events) m.fadedAt = m.events
            return m
        }

        /**
         * Whether [b] could be what [a] was meant to be: one slip apart in a short word, two in a longer one (a letter
         * wrong, missing, added, or two swapped). Two unlike words typed one after the other in the same place are a
         * change of mind, not a mistake and its mending.
         */
        fun alike(a: String, b: String): Boolean {
            if (a == b || a.isEmpty() || b.isEmpty()) return false
            val most = if (maxOf(a.length, b.length) <= 4) 1 else 2
            if (kotlin.math.abs(a.length - b.length) > most) return false
            return slips(a, b, most) <= most
        }

        /** How many slips apart [a] and [b] are (letters wrong, missing, added, or two swapped), counting no further than [most] + 1. */
        fun slips(a: String, b: String, most: Int): Int {
            var before2 = IntArray(b.length + 1)
            var before = IntArray(b.length + 1) { it }
            var row = IntArray(b.length + 1)
            for (i in 1..a.length) {
                row[0] = i
                var least = row[0]
                for (j in 1..b.length) {
                    var v = before[j - 1] + (if (a[i - 1] == b[j - 1]) 0 else 1)
                    if (before[j] + 1 < v) v = before[j] + 1
                    if (row[j - 1] + 1 < v) v = row[j - 1] + 1
                    if (i >= 2 && j >= 2 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] && before2[j - 2] + 1 < v) v = before2[j - 2] + 1
                    row[j] = v
                    if (v < least) least = v
                }
                if (least > most) return most + 1
                val spare = before2; before2 = before; before = row; row = spare
            }
            return before[b.length]
        }
    }
}
