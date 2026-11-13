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
    val joins = d.joins(least, least, Autocorrect.JOIN_FIRST, first = { i -> if (inside.zipfs[i] >= Autocorrect.JOIN_FIRST && (inside.sorted[i].length >= 2 || inside.zipfs[i] >= SwipeDecoder.ONE_LETTER)) inside.zipfs[i] else Float.NEGATIVE_INFINITY },
        second = { a, b -> inside.worth(inside.sorted[a], inside.sorted[b]).first },
        followers = { a -> inside.follows(inside.sorted[a])?.keys?.mapNotNull { w -> inside.index(w).takeIf { it >= 0 } }?.toIntArray()?.also { it.sort() } ?: IntArray(0) })
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

/** One event of a typing as the board reads it: a letter's key ('l'), the space bar ('b'), a mark ('m') or anything else ('x'). */
class Ev(val kind: Char, val c: Char, val x: Float, val y: Float, val meta: String, val raw: String)

fun events(board: Board, events: List<String>, meta: List<String>): List<Ev> = events.mapIndexed { i, e ->
    val p = e.split(':')
    val m = meta.getOrElse(i) { "m" }
    if (p[0] != "T") Ev('m', e.substring(2, 3)[0], 0f, 0f, m, e) else {
        val hit = board.keyAt(p[1].toFloat(), p[2].toFloat() - board.top)
        when {
            hit == null -> Ev('x', ' ', 0f, 0f, m, e)
            hit.key.type == KeyType.SPACE -> Ev('b', ' ', hit.aimX, hit.aimY, m, e)
            hit.key.type == KeyType.CHAR && hit.key.text.length == 1 && hit.key.text[0].isLetter() && m != "m" -> Ev('l', hit.key.text[0], hit.aimX, hit.aimY, m, e)
            else -> Ev('x', ' ', 0f, 0f, m, e)
        }
    }
}

fun play(typist: Typist, e: Ev) {
    val p = e.raw.split(':')
    if (e.kind == 'm') typist.mark(e.c) else typist.tap(p[1].toFloat(), p[2].toFloat(), p[3].toLong())
}

/**
 * How long Autocorrect.correct takes for every typed word of the typings on the input, one at a time, as the
 * keyboard asks at a word's end: the mean, the middle, the slowest in a hundred and in a thousand, and the slowest
 * few by name. Each word is asked three times and its quickest kept, and the slowest few nine times more (so that
 * a pause of the JVM's is not taken for the decoder's). Everything is typed through once first, unmeasured, so
 * that the JVM has compiled it.
 */
fun time(root: File, boardFile: File, show: Int) {
    // The processor time this thread has had, not the time of day: other people's builds share the Mac.
    val bean = java.lang.management.ManagementFactory.getThreadMXBean()
    fun clock() = bean.currentThreadCpuTime
    val board = Board.recorded(boardFile)
    val decoder = Decoder(root)
    val (keys, keyW, space) = board.tapKeys
    class Token(val id: String, val typed: String, val before: String, val taps: Taps) { var nanos = 0L; var work = 0 }
    val tokens = ArrayList<Token>()
    val lines = generateSequence { readLine() }.toList()
    fun scorerFor(t: Token) = (t.before + t.typed).let { text -> runCatching { NextWordText.place(text.takeLast(96), { c -> Words.isWordChar(c, decoder.state.joiners) }, text.length >= 96, decoder.state.locale, "")?.let { decoder.at(it) } }.getOrNull() }
    for (pass in 0..1) {
        tokens.clear()
        for (line in lines) {
            val f = line.split('\t')
            if (f.size < 4) continue
            val typist = Typist(board, decoder)
            val run = ArrayList<Ev>()
            var before = ""
            fun end() {
                if (run.isEmpty()) return
                val t = Token(f[0], run.joinToString("") { it.c.toString() }, before, Taps(FloatArray(run.size) { run[it].x }, FloatArray(run.size) { run[it].y }, keys, keyW, space))
                val scorer = scorerFor(t)
                // The quickest of three: one reading of the clock is as often the JVM's pause as the decoder's work.
                t.nanos = Long.MAX_VALUE
                repeat(if (pass == 0) 1 else 3) { TapDecoder.JOIN_WORK_LAST = 0; val t0 = clock(); decoder.ac.correct(t.typed, scorer, t.taps); t.nanos = minOf(t.nanos, clock() - t0) }
                t.work = TapDecoder.JOIN_WORK_LAST
                tokens += t
                run.clear()
            }
            for (e in events(board, f[1].split(' '), f[2].split(' '))) {
                if (e.kind == 'l') { if (run.isEmpty()) before = typist.text.toString(); run += e } else end()
                play(typist, e)
            }
            end()
        }
    }
    val sorted = tokens.map { it.nanos }.sorted()
    fun at(q: Double) = sorted[((sorted.size - 1) * q).toInt()] / 1000.0
    println("time: %d typed words; microseconds for Autocorrect.correct: mean %.0f, middle %.0f, slowest in a hundred %.0f, in a thousand %.0f, slowest %.0f".format(
        tokens.size, sorted.average() / 1000.0, at(0.5), at(0.99), at(0.999), sorted.last() / 1000.0))
    val again = tokens.sortedByDescending { it.nanos }.take(maxOf(show, 20)).map { t ->
        val scorer = scorerFor(t)
        var least = Long.MAX_VALUE
        repeat(9) { val t0 = clock(); decoder.ac.correct(t.typed, scorer, t.taps); least = minOf(least, clock() - t0) }
        t to least
    }.sortedByDescending { it.second }
    println("      the slowest, each timed nine times more and its quickest kept:")
    for ((t, least) in again.take(show)) println("      %6.0f µs  %-26s %s  -> %s".format(least / 1000.0, t.typed, t.id, decoder.ac.correct(t.typed, scorerFor(t), t.taps) ?: "(left)"))
    println("time: the slowest single word, at its quickest of nine: %.0f µs".format(again.first().second / 1000.0))
    // The join search's own count of its work (TapDecoder.joinWork), which no other process's load changes.
    val works = tokens.filter { it.work > 0 }.sortedByDescending { it.work }
    println("work: %d of the words were looked at for a join; the search's work a word: mean %.0f, most %d (it stops at %d): %s".format(
        works.size, works.map { it.work }.average(), works.firstOrNull()?.work ?: 0, TapDecoder.MAX_JOIN_WORK, works.take(6).joinToString(", ") { "${it.typed} ${it.work}" }))
}

/**
 * Words cut in two: for every tap of a typing that was meant as a letter and landed on the space bar, the scraps
 * either side of it, what stood in the field before the tap, after it and when the scrap after it ended, what
 * Autocorrect.rejoin answers when asked now, and what the decoder finds when it reads the scraps and the bar's
 * tap as one word (the bar's tap as a letter, by where on the bar it landed).
 */
fun cuts(root: File, boardFile: File, show: Int) {
    val board = Board.recorded(boardFile)
    val decoder = Decoder(root)
    val inside = Inside(decoder.ac)
    val (keys, keyW, space) = board.tapKeys
    var all = 0; var right = 0
    var line = readLine()
    while (line != null) {
        val f = line.split('\t')
        line = readLine()
        if (f.size < 4) continue
        val target = f[3].split(' ')
        val evs = events(board, f[1].split(' '), f[2].split(' '))
        val typist = Typist(board, decoder)
        val after = ArrayList<String>()
        for (e in evs) { play(typist, e); after += typist.text.toString() }
        val out = score(typist.text.toString())
        for ((i, e) in evs.withIndex()) {
            if (e.kind != 'b' || e.meta[0] != 'k') continue
            var a = i
            while (a > 0 && evs[a - 1].kind == 'l') a--
            var b = i
            while (b + 1 < evs.size && evs[b + 1].kind == 'l') b++
            val first = evs.subList(a, i); val second = evs.subList(i + 1, b + 1)
            val word = e.meta.drop(1).substringBefore(':').toInt()
            val meant = target[word]
            val ok = out.getOrNull(word) == meant || meant in out
            all++; if (ok) right++
            val s1 = first.joinToString("") { it.c.toString() }; val s2 = second.joinToString("") { it.c.toString() }
            val bar = space!!
            val depth = (e.y - bar[1]) / (bar[3] - bar[1])
            val above = keys.minByOrNull { (_, c) -> (c.first - e.x) * (c.first - e.x) + (c.second - e.y) * (c.second - e.y) }!!.key
            val before = if (a > 0) after[a - 1] else ""
            println("== ${f[0]}  meant \"$meant\"  typed \"$s1\" [bar] \"$s2\"  the bar's tap ${"%.2f".format(depth)} of its height down, nearest $above  ${if (ok) "RIGHT" else "wrong"}")
            println("     the field: before \"${before.takeLast(24)}\"  after the bar's tap \"${after[i].takeLast(30)}\"  after the next scrap ended \"${after[minOf(b + 1, evs.size - 1)].takeLast(40)}\"")
            val run = first + listOf(e) + second
            val taps = Taps(FloatArray(run.size) { run[it].x }, FloatArray(run.size) { run[it].y }, keys, keyW, space)
            val typed = "$s1 $s2"
            val d = inside.decoder(taps)
            d.load(typed, taps)
            val hits = d.search(-30f).map { h -> Triple(inside.sorted[h.index], inside.zipfs[h.index] - h.cost, h.cost) }.sortedByDescending { it.second }.take(show)
            println("     as one word: " + hits.joinToString("  ") { "%s %.2f (cost %.2f)".format(it.first, it.second, it.third) }.ifEmpty { "none" })
            val z = inside.words[meant]
            val c = d.align(meant.replace("'", ""), 0, 99f)
            println("     the word meant: zipf ${z?.let { "%.2f".format(it) } ?: "none"}, its slips cost %.2f, the bar's tap as its letter %.2f".format(c, inside.sub(d, s1.length, meant.replace("'", "").getOrElse(s1.length) { '?' })))
            for ((name, scrap) in listOf("before" to s1, "after" to s2)) if (scrap.isNotEmpty()) println("     the scrap $name: \"$scrap\" ${inside.words[scrap]?.let { "listed, %.2f".format(it) } ?: if (scrap.length >= 4 && inside.odd(scrap)) "looks like no word" else "in no list"}")
        }
    }
    System.err.println("probe: $all taps meant as a letter landed on the bar, $right of their words came out right")
}

/** tool/versus/thumb.py's thumbs, for typing words here: scatter and lean in key sizes, one tap in sixteen wilder by 2.2. */
class Thumb(val name: String, val sx: Float, val sy: Float, val leanX: Float = 0f, val leanY: Float = 0f)
val THUMBS = listOf(Thumb("careful", 0.20f, 0.15f), Thumb("leaning", 0.20f, 0.15f, 0.24f, 0.18f), Thumb("sloppy", 0.34f, 0.25f))

/** Where a thumb lands for the letters of [word], and the letters its taps typed: null where one landed off the letters. */
fun typeWord(board: Board, thumb: Thumb, rnd: java.util.Random, word: String): Pair<String, Taps>? {
    val (keys, keyW, space) = board.tapKeys
    val keyH = board.keyRects.first { it.first.type == KeyType.CHAR }.second.h
    fun gauss(s: Float) = (rnd.nextGaussian() * s * (if (rnd.nextFloat() < 0.06f) 2.2 else 1.0)).toFloat()
    val xs = FloatArray(word.length); val ys = FloatArray(word.length)
    val typed = StringBuilder()
    for ((i, c) in word.withIndex()) {
        val at = keys[c] ?: return null
        xs[i] = at.first + (thumb.leanX + gauss(thumb.sx)) * keyW
        ys[i] = at.second + (thumb.leanY + gauss(thumb.sy)) * keyH
        val hit = board.keyRects.firstOrNull { it.second.contains(xs[i], ys[i]) }?.first ?: return null
        if (hit.type != KeyType.CHAR || hit.text.length != 1 || !hit.text[0].isLetter()) return null
        typed.append(hit.text[0])
    }
    return typed.toString() to Taps(xs, ys, keys, keyW, space)
}

/**
 * Right words spoiled, on far more text than the typing bench's 12,000 words: the sentences on the input are
 * typed by the three thumbs, and every word whose taps all landed on its own keys is given to Autocorrect.correct
 * with the sentence before it as it was meant. Whatever is changed was right and is spoiled. Said for all such
 * words and for those no list has (names, compounds, slang: what a person types on purpose), and of the spoiled,
 * how many were parted into two words. Nothing is measured of slips mended: this is the guard, not the gain.
 */
fun spoils(root: File, boardFile: File, show: Int) {
    val board = Board.recorded(boardFile)
    val decoder = Decoder(root)
    val lines = generateSequence { readLine() }.toList()
    for (thumb in THUMBS) {
        val rnd = java.util.Random(7)
        var clean = 0; var spoiled = 0; var unlisted = 0; var unlistedSpoiled = 0; var parted = 0
        val examples = LinkedHashMap<String, Int>()
        // The words no list has, each counted once however often the sentences have it (Tatoeba has "Mennad" in hundreds).
        val kinds = HashSet<String>(); val kindsSpoiled = HashSet<String>(); val kindsParted = HashSet<String>()
        for (line in lines) {
            val before = StringBuilder()
            for (raw in line.split(' ')) {
                val word = raw.trim { !it.isLetter() }
                val lower = word.lowercase()
                // Letters only, as the thumb types them; a capital inside or throughout is typed on purpose and never touched.
                if (lower.length < 2 || !lower.all { it in 'a'..'z' } || word.drop(1).any { it.isUpperCase() }) { before.append(raw).append(' '); continue }
                val typed = typeWord(board, thumb, rnd, lower)
                if (typed != null && typed.first == lower) {
                    clean++
                    val listed = decoder.ac.isWord(lower)
                    if (!listed) { unlisted++; kinds += lower }
                    val text = before.toString() + lower
                    val scorer = runCatching { NextWordText.place(text.takeLast(96), { c -> Words.isWordChar(c, decoder.state.joiners) }, text.length >= 96, decoder.state.locale, "")?.let { decoder.at(it) } }.getOrNull()
                    val out = decoder.ac.correct(lower, scorer, typed.second)
                    if (out != null && out.lowercase() != lower) {
                        spoiled++
                        if (!listed) { unlistedSpoiled++; kindsSpoiled += lower }
                        if (' ' in out) { parted++; if (!listed) kindsParted += lower }
                        val key = "$lower → ${out.lowercase()}" + (if (listed) "" else " *")
                        examples[key] = (examples[key] ?: 0) + 1
                    }
                }
                before.append(raw).append(' ')
            }
        }
        println("spoils %-8s %d right words, %d spoiled (%.2f per 1000), %d of them parted in two; of %d that no list has, %d spoiled (%.1f%%); of the %d different ones, %d spoiled, %d parted".format(
            thumb.name, clean, spoiled, 1000.0 * spoiled / clean.coerceAtLeast(1), parted, unlisted, unlistedSpoiled, 100.0 * unlistedSpoiled / unlisted.coerceAtLeast(1), kinds.size, kindsSpoiled.size, kindsParted.size))
        examples.entries.filter { ' ' in it.key.substringAfter("→ ").removeSuffix(" *") }.sortedByDescending { it.value }.take(show).forEach { println("        parted  ${it.key}" + if (it.value > 1) " (${it.value})" else "") }
    }
}

/** The words of a field's text as tool/versus/score.py counts them: lower case, marks aside. */
fun score(text: String): List<String> = text.lowercase().replace('’', '\'').split(Regex("[^a-z']+")).filter { it.isNotEmpty() }

object Probe {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0]); val board = File(args[1]); val show = args.getOrNull(2)?.toInt() ?: 5
        // Any of the decoder's dials for this run, as tool/versus/replay/Replay.kt sets them: Autocorrect.JOINS=false.
        args.drop(4).forEach { dial(it) }
        when (args.getOrNull(3) ?: "joins") {
            "words" -> words(root, board, show)
            "time" -> time(root, board, show)
            "cuts" -> cuts(root, board, show)
            "spoils" -> spoils(root, board, show)
            else -> probe(root, board, show)
        }
    }
}
