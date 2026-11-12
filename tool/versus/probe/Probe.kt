// tool/versus/probe.py: for each stretch of a typing in which two or more words ran together, what the decoder weighed.
//
// A line in:  <id> TAB <events> TAB <what each event was meant as> TAB <the sentence's words>
// where the events are tool/versus/replay/Replay.kt's, and each is meant as  k<word>:<letter>  (a letter of that
// word), s<word>  (the space after that word) or  m  (a mark). The stretch between two taps that did land on the bar
// (or a mark) is one typed word to the keyboard; where it holds letters of more than one word of the sentence, or a
// space that landed on a letter, it is printed with:
//   - what Autocorrect.correct made of it;
//   - the reading that was meant, word by word: how common each is, what its own taps cost to read as it, what the
//     tap between them costs to read as the bar, what the second is worth after the first, and the sum against the bar;
//   - the best few joins and single words the decoder itself found, with the bar off.
//
// It reads Autocorrect's and TapDecoder's private parts by reflection, so that nothing in the app is opened up for
// it: when one of those is renamed this file says so and stops.

import com.infamousvague.devkeyboard.Autocorrect
import com.infamousvague.devkeyboard.KeyType
import com.infamousvague.devkeyboard.NextWordText
import com.infamousvague.devkeyboard.SwipeDecoder
import com.infamousvague.devkeyboard.TapDecoder
import com.infamousvague.devkeyboard.Taps
import com.infamousvague.devkeyboard.Words
import java.io.File

/** One tap that typed a letter: the letter, where it was read as aiming, and what it was meant as. */
class Meant(val c: Char, val x: Float, val y: Float, val word: Int, val letter: Char)

/** Autocorrect's and TapDecoder's own word lists, costs and helpers, reached by reflection. */
class Inside(val ac: Autocorrect) {
    private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
    private val now = Autocorrect::class.java.getDeclaredField("now").apply { isAccessible = true }.get(ac)!!
    private val lex = field(now, "lexicon")!!
    @Suppress("UNCHECKED_CAST") val sorted = field(lex, "sorted") as Array<String>
    val zipfs = field(lex, "sortedZipf") as FloatArray
    @Suppress("UNCHECKED_CAST") val words = field(lex, "words") as Map<String, Float>
    private val followsM = lex.javaClass.getDeclaredMethod("follows", String::class.java).apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") fun follows(prev: String) = followsM.invoke(lex, prev) as Map<String, Float>?
    private val oddM = Autocorrect::class.java.getDeclaredMethod("odd", String::class.java).apply { isAccessible = true }
    fun odd(s: String) = oddM.invoke(ac, s) as Boolean
    private val decoderM = Autocorrect::class.java.getDeclaredMethod("tapDecoder", now.javaClass, Taps::class.java).apply { isAccessible = true }
    fun decoder(t: Taps) = decoderM.invoke(ac, now, t) as TapDecoder
    private val subM = TapDecoder::class.java.getDeclaredMethod("sub", Int::class.javaPrimitiveType, Char::class.javaPrimitiveType).apply { isAccessible = true }
    fun sub(d: TapDecoder, i: Int, c: Char) = subM.invoke(d, i, c) as Float
    private val spaceF = TapDecoder::class.java.getDeclaredField("spaceCost").apply { isAccessible = true }
    fun spaceCost(d: TapDecoder) = spaceF.get(d) as FloatArray
    fun index(w: String) = java.util.Arrays.binarySearch(sorted, w)

    /** Autocorrect.byTaps's own worth of [b] after [a] (its `worth`), and how it came by it. */
    fun worth(a: String, b: String): Pair<Float, String> {
        val z = words[b] ?: return Float.NEGATIVE_INFINITY to "in no list"
        if (b.length < 2 && z < SwipeDecoder.ONE_LETTER) return Float.NEGATIVE_INFINITY to "a lone letter"
        val row = follows(a)
        val share = row?.get(b) ?: 0f
        if (share > 0f) return 9f + kotlin.math.log10(Math.pow(10.0, (z - 9f).toDouble()) + share).toFloat() to "follows it, share %.4f".format(share)
        val unseen = z - Autocorrect.JOIN_UNSEEN
        if (row == null || row.size < 12) return unseen to "unseen after it, a thin row"
        val floor = 9f + kotlin.math.log10(row.values.min() * Autocorrect.JOIN_UNLISTED)
        return minOf(unseen, floor) to "unseen after it (the row's floor %.2f)".format(floor)
    }
}

/** What the decoder itself finds for [typed], with its bars out of the way: the best joins and the best single words. */
fun found(inside: Inside, d: TapDecoder, typed: String, taps: Taps, show: Int) {
    val least = -30f
    d.load(typed, taps)
    val joins = d.joins(least, least, Autocorrect.JOIN_FIRST, firsts = { i -> inside.zipfs[i] >= Autocorrect.JOIN_FIRST && (inside.sorted[i].length >= 2 || inside.zipfs[i] >= SwipeDecoder.ONE_LETTER) },
        second = { a, b -> inside.worth(inside.sorted[a], inside.sorted[b]).first },
        followers = { a -> inside.follows(inside.sorted[a])?.keys?.mapNotNull { w -> inside.index(w).takeIf { it >= 0 } }?.toIntArray() ?: IntArray(0) })
    val ranked = joins.map { j ->
        val third = if (j.third >= 0) inside.worth(inside.sorted[j.second], inside.sorted[j.third]).first - 9f else 0f
        Triple(inside.sorted[j.first] + " " + inside.sorted[j.second] + (if (j.third >= 0) " " + inside.sorted[j.third] else "") + (if (j.bars > 0) " [${j.bars} on a letter]" else ""),
            inside.zipfs[j.first] + inside.worth(inside.sorted[j.first], inside.sorted[j.second]).first - 9f + third - j.cost, j.cost)
    }.sortedByDescending { it.second }.distinctBy { it.first }.take(show)
    println("     joins found: " + ranked.joinToString("  ") { "%s %.2f (cost %.2f)".format(it.first, it.second, it.third) }.ifEmpty { "none" })
    d.load(typed, taps)
    val hits = d.search(-30f).map { h -> Triple(inside.sorted[h.index], inside.zipfs[h.index] - h.cost, h.cost) }.sortedByDescending { it.second }.take(show)
    println("     words found: " + hits.joinToString("  ") { "%s %.2f (cost %.2f)".format(it.first, it.second, it.third) }.ifEmpty { "none" })
}

/** Words typed with every tap in the middle of its key, one a line on the input: what each is worth and what joins it could be read as. */
fun words(root: File, boardFile: File, show: Int) {
    val board = Board.recorded(boardFile)
    val decoder = Decoder(root)
    val inside = Inside(decoder.ac)
    val (keys, keyW, space) = board.tapKeys
    var line = readLine()
    while (line != null) {
        val typed = line.trim().lowercase()
        line = readLine()
        if (typed.isEmpty() || typed.any { it !in keys }) continue
        val taps = Taps(FloatArray(typed.length) { keys.getValue(typed[it]).first }, FloatArray(typed.length) { keys.getValue(typed[it]).second }, keys, keyW, space)
        val known = inside.words[typed]
        println("== \"$typed\"  decoder: ${decoder.ac.correct(typed, null, taps)?.let { "\"$it\"" } ?: "left as typed"}  (${known?.let { "listed, %.2f".format(it) } ?: if (typed.length >= 4 && inside.odd(typed)) "looks like no word" else if (typed.length >= 6) "long" else "unknown"})")
        found(inside, inside.decoder(taps), typed, taps, show)
    }
}

fun probe(root: File, boardFile: File, show: Int) {
    val board = Board.recorded(boardFile)
    val decoder = Decoder(root)
    val inside = Inside(decoder.ac)
    val (keys, keyW, space) = board.tapKeys
    var line = readLine()
    var joined = 0; var parted = 0
    while (line != null) {
        val f = line.split('\t')
        line = readLine()
        if (f.size < 4) continue
        val id = f[0]
        val events = f[1].split(' ')
        val meta = f[2].split(' ')
        val target = f[3].split(' ')
        val typist = Typist(board, decoder)
        val run = ArrayList<Meant>()
        var before = ""
        fun end() {
            if (run.isEmpty()) return
            val covers = run.map { it.word }.distinct()
            val isJoin = covers.size > 1 || run.any { it.letter == ' ' }
            if (isJoin) {
                joined++
                val typed = run.joinToString("") { it.c.toString() }
                val meant = covers.map { target[it] }
                val taps = Taps(FloatArray(run.size) { run[it].x }, FloatArray(run.size) { run[it].y }, keys, keyW, space)
                val text = before + typed
                val scorer = runCatching { NextWordText.place(text.takeLast(96), { c -> Words.isWordChar(c, decoder.state.joiners) }, text.length >= 96, decoder.state.locale, "")?.let { decoder.at(it) } }.getOrNull()
                val out = decoder.ac.correct(typed, scorer, taps)
                val right = out != null && out.lowercase() == meant.joinToString(" ")
                if (right) parted++
                println("== $id  typed \"$typed\"  meant ${meant.joinToString(" · ")}  decoder: ${out?.let { "\"$it\"" } ?: "left as typed"}  ${if (right) "RIGHT" else "wrong"}")
                val d = inside.decoder(taps)
                d.load(typed, taps)
                val known = inside.words[typed]
                println("     the typed word: ${known?.let { "listed, %.2f".format(it) } ?: if (typed.length >= 4 && inside.odd(typed)) "looks like no word (UNKNOWN_ODD)" else if (typed.length >= 6) "long (UNKNOWN_LONG)" else "UNKNOWN"}")
                // The reading that was meant.
                var total = 0f
                var cost = 0f
                var prev: String? = null
                var at = 0
                for ((n, w) in covers.withIndex()) {
                    val word = target[w]
                    val mine = run.withIndex().filter { it.value.word == w && it.value.letter != ' ' }.map { it.index }
                    val gap = run.withIndex().firstOrNull { it.value.word == w && it.value.letter == ' ' }?.index
                    val from = mine.firstOrNull() ?: at
                    val to = (mine.lastOrNull() ?: (at - 1)) + 1
                    val z = inside.words[word.replace("'", "")] ?: inside.words[word]
                    val letters = word.filter { it != '\'' }
                    val piece = typed.substring(from, to)
                    val c: Float
                    val how: String
                    if (piece.length == letters.length) {
                        val subs = letters.indices.map { inside.sub(d, from + it, letters[it]) }
                        c = subs.sum()
                        how = "letters read as other keys only: " + letters.indices.filter { subs[it] > 0f }.joinToString(" ") { "${piece[it]}>${letters[it]} %.2f".format(subs[it]) }.ifEmpty { "none" }
                    } else if (n == covers.size - 1) {
                        c = d.align(word, from, 99f); how = "a letter missed or added"
                    } else {
                        val part = Taps(FloatArray(to - from) { run[from + it].x }, FloatArray(to - from) { run[from + it].y }, keys, keyW, space)
                        val pd = inside.decoder(part); pd.load(piece, part)
                        c = pd.align(word, 0, 99f); how = "a letter missed or added (so never a first word)"
                    }
                    val worth = if (prev == null) (z ?: Float.NEGATIVE_INFINITY) to "" else inside.worth(prev, word)
                    println("       %-14s typed %-14s zipf %s  cost %5.2f  (%s)%s".format(word, piece, z?.let { "%.2f".format(it) } ?: "none", c, how,
                        if (prev == null) "" else "  worth after \"$prev\" %.2f: %s".format(worth.first, worth.second)))
                    total += worth.first; cost += c
                    if (n < covers.size - 1) {
                        val g = if (gap != null) inside.spaceCost(d)[gap] else TapDecoder.NO_SPACE
                        println("       %-14s %s  cost %5.2f".format("(space)", if (gap != null) "landed on ${typed[gap]}" else "not typed", g))
                        cost += g; total -= 9f
                    }
                    prev = word
                    at = (gap ?: (to - 1)) + 1
                }
                val fall = Autocorrect.JOIN_FALL * (typed.length - Autocorrect.JOIN_LONG).coerceAtLeast(0f)
                println("       as meant it is worth %.2f less %.2f = %.2f; the bar for a join is %.2f, and %.2f where a tap is read as the bar".format(total, cost, total - cost, Autocorrect.JOIN_BAR, Autocorrect.JOIN_BAR - fall))
                found(inside, d, typed, taps, show)
            }
            run.clear()
        }
        for ((i, e) in events.withIndex()) {
            val p = e.split(':')
            val m = meta.getOrElse(i) { "m" }
            when (p[0]) {
                "T" -> {
                    val x = p[1].toFloat(); val y = p[2].toFloat()
                    val hit = board.keyAt(x, y - board.top)
                    if (hit != null && hit.key.type == KeyType.CHAR && hit.key.text.length == 1 && hit.key.text[0].isLetter() && m != "m") {
                        if (run.isEmpty()) before = typist.text.toString()
                        val word = m.drop(1).substringBefore(':').toInt()
                        run += Meant(hit.key.text[0], hit.aimX, hit.aimY, word, if (m[0] == 's') ' ' else m.substringAfter(':')[0])
                    } else end()
                    typist.tap(x, y, p[3].toLong())
                }
                "M" -> { end(); typist.mark(e.substring(2, 3)[0]) }
            }
        }
        end()
    }
    System.err.println("probe: $joined stretches of words run together, $parted parted right")
}

object Probe {
    @JvmStatic fun main(args: Array<String>) {
        if (args.getOrNull(3) == "words") words(File(args[0]), File(args[1]), args[2].toInt()) else probe(File(args[0]), File(args[1]), args.getOrNull(2)?.toInt() ?: 5)
    }
}
