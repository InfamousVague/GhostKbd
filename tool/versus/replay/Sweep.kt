// tool/versus/sweep.py's worker: the decoder's dials tried one setting after another, in one JVM.
//
//   tool/versus/replay.sh sweep --sets <file> [--bench-words 12000] [--threads 4] [--last-line 101000]
//   tool/versus/replay.sh sweep --dials             every dial there is and what the tree sets it to; loads nothing
//
// tool/versus/scoreboard.py --set starts a JVM, reads the word lists and types 1,800 sentences and the typing bench
// for every setting it is asked about. This loads everything once and then reads settings from standard input, a
// line each:
//
//     Autocorrect.UNKNOWN=2.4 TapDecoder.OMIT=1.8
//
// (an empty line or "-" is the tree as it is) and answers each with one line on standard output: the words left wrong
// in each set of sentences, by thumb and in all; the typing bench's tap test for its three thumbs (words right, right
// words spoiled in a thousand, right words no list has that were spoiled) and its misspellings; the seconds it took;
// and, after a tab, the same as JSON, which is what sweep.py reads: every count behind those figures, every word's
// verdict, and the microseconds a word that each set and each thumb of the bench cost (the typing threads' own
// computing time, [sweepThreadNanos]). The first line of all is "ready", a tab, and what was loaded.
//
// "only=sets" or "only=bench" on a line types that part alone and answers with that part alone (the sets are half
// the work, and a search needs the bench only for the settings the sets have not already ruled out). A line that
// cannot be read, or a setting the decoder throws at, is answered "error ..." and the worker goes on to the next
// line with every dial put back.
//
// The numbers are scoreboard.py's for the same setting, to the word (`sweep.py check` holds one against the other):
// - the sets are typed by Replay.kt's own Typist on a run's own keys, a typing at a time, and scored as score.py
//   scores a field ([sweepOutWords], [sweepAlign]: its out_words and align, read across; keep them in step);
// - the bench's tap test is Bench.kt's own: its sentences ([sample]), its thumbs ([typeAll], which is run once for
//   each thumb with a keyboard that changes nothing, to keep the taps it makes: they do not depend on what the
//   keyboard does with them), its reading of a word ([byTheTaps]) and its tally ([Tally]).
//
// A dial is any field of the keyboard's package that is public, static, not final and a number or a switch: every
// `@JvmField var` of a companion object, found by reflection, so a dial added to the decoder is a dial here with no
// change to this file. Their values as the classes load are the tree's, and every one is put back to that before
// each line.
//
// The dials are static, so two settings cannot be tried at once in one JVM without a class loader apiece, each with
// its own copy of the word lists and its own warming up. One setting's work divides evenly, though: a typing does not
// depend on the typing before it (a new Typist each, and Autocorrect.correct keeps nothing between words but caches
// that any thread may fill). So settings are tried one at a time and each one's typings are spread over --threads
// threads: the same cores used, one copy of everything, and no order of finishing that could change a number.

import com.infamousvague.devkeyboard.Autocorrect
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * The last line of build/typing-bench/en-heldout.txt that anything here may read. From line 101,001 on the sentences
 * are kept unseen for the end (tool/versus/scoreboard.py --final): a setting found by trying thousands must not have
 * been tried on them.
 */
const val SWEEP_LAST_LINE = 101_000

private val sweepCpu = runCatching {
    java.lang.management.ManagementFactory.getThreadMXBean().takeIf { it.isCurrentThreadCpuTimeSupported }?.also { if (!it.isThreadCpuTimeEnabled) it.isThreadCpuTimeEnabled = true }
}.getOrNull()

/**
 * What the calling thread has spent computing so far, in nanoseconds: its own time on a core, which on a Mac that
 * other work is loading is far less than the time that went by (and is what a setting costs the decoder). Where the
 * JVM cannot say, the clock.
 */
fun sweepThreadNanos(): Long = sweepCpu?.currentThreadCpuTime ?: System.nanoTime()

/**
 * A digest of every class the worker was compiled from but Kotlin's own: the keyboard's, the replay's, the bench's
 * and this file's. sweep.py keeps its answers by it, so a jar made again from the same sources keeps them, and a
 * change to any source (a dial added, a rule changed) starts afresh.
 */
fun sweepBuild(): String {
    val where = File(Autocorrect::class.java.protectionDomain.codeSource.location.toURI())
    val md = java.security.MessageDigest.getInstance("SHA-256")
    if (where.isDirectory) {
        for (f in where.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.sortedBy { it.relativeTo(where).path }) { md.update(f.relativeTo(where).path.toByteArray()); md.update(f.readBytes()) }
    } else java.util.zip.ZipFile(where).use { zip ->
        for (e in zip.entries().asSequence().filter { it.name.endsWith(".class") && !it.name.startsWith("kotlin/") }.sortedBy { it.name }) { md.update(e.name.toByteArray()); md.update(zip.getInputStream(e).readBytes()) }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

/** The decoder's dials, by name ("Autocorrect.UNKNOWN"), each with the value the tree gives it. */
class SweepDials {
    class Dial(val name: String, private val static: Field, val default: Any) {
        val type: String = when (static.type) { java.lang.Float.TYPE -> "float"; java.lang.Integer.TYPE -> "int"; else -> "switch" }
        fun read(text: String): Any = when (type) {
            "float" -> text.toFloat().also { require(!it.isNaN()) { "$name: $text is no number" } }
            "int" -> text.toInt()
            else -> when (text) { "true" -> true; "false" -> false; else -> throw IllegalArgumentException("$name is a switch: true or false, not $text") }
        }
        fun put(value: Any) = when (type) {
            "float" -> static.setFloat(null, value as Float)
            "int" -> static.setInt(null, value as Int)
            else -> static.setBoolean(null, value as Boolean)
        }
    }

    val all = LinkedHashMap<String, Dial>()

    init {
        // Every class of the keyboard's package that was compiled in, from wherever Autocorrect itself was loaded.
        val pkg = "com/infamousvague/devkeyboard/"
        val where = File(Autocorrect::class.java.protectionDomain.codeSource.location.toURI())
        val files = if (where.isDirectory) File(where, pkg).list().orEmpty().toList()
        else java.util.zip.ZipFile(where).use { zip -> zip.entries().asSequence().map { it.name }.filter { it.startsWith(pkg) }.map { it.removePrefix(pkg) }.toList() }
        for (file in files.sorted()) {
            if (!file.endsWith(".class") || '$' in file || '/' in file) continue
            val cls = runCatching { Class.forName("com.infamousvague.devkeyboard." + file.removeSuffix(".class"), false, Autocorrect::class.java.classLoader) }.getOrNull() ?: continue
            for (f in runCatching { cls.fields }.getOrNull().orEmpty()) {
                val m = f.modifiers
                if (!Modifier.isStatic(m) || Modifier.isFinal(m) || f.declaringClass != cls) continue
                if (f.type != java.lang.Float.TYPE && f.type != java.lang.Integer.TYPE && f.type != java.lang.Boolean.TYPE) continue
                all[cls.simpleName + "." + f.name] = Dial(cls.simpleName + "." + f.name, f, f.get(null))
            }
        }
        check(all.isNotEmpty()) { "sweep: no dial found in $where" }
    }

    /**
     * Every dial as the tree has it, then [setting]'s ("Autocorrect.UNKNOWN=2.4 TapDecoder.OMIT=1.8"; nothing, or
     * "-", for the tree as it is). Gives back what was set, by name, each value as the dial now holds it. A setting
     * that cannot be read sets nothing and throws.
     */
    fun apply(setting: String): Map<String, String> {
        val wanted = LinkedHashMap<Dial, Any>()
        for (part in setting.trim().split(Regex("\\s+"))) {
            if (part.isEmpty() || part == "-") continue
            val eq = part.indexOf('=')
            require(eq > 0) { "$part: a setting is Name=value" }
            val dial = all[part.substring(0, eq)] ?: throw IllegalArgumentException("${part.substring(0, eq)}: no such dial")
            wanted[dial] = dial.read(part.substring(eq + 1))
        }
        for (d in all.values) d.put(d.default)
        for ((d, v) in wanted) d.put(v)
        return wanted.entries.associate { it.key.name to it.value.toString() }
    }
}

/** tool/versus/score.py's out_words: the words standing in a field, lower case, the marks about them gone, a curly apostrophe made straight. */
fun sweepOutWords(text: String): List<String> {
    fun plain(c: Char) = c in 'a'..'z' || c in '0'..'9'
    val out = ArrayList<String>()
    for (tok in text.replace('’', '\'').lowercase(Locale.ROOT).split(Regex("\\s+"))) {
        if (tok.isEmpty()) continue
        val w = if (tok.none(::plain)) tok.trim { !plain(it) && it != '\'' }.trim('\'') else tok.trim { !plain(it) }
        if (w.isNotEmpty()) out += w
    }
    return out
}

/**
 * tool/versus/score.py's align: for each word of [target] the word of [got] it lines up with (null where it has
 * none), by the cheapest way to turn one list into the other, a word swapped, dropped or added each one. Where two
 * ways cost the same the walk back takes a swap before a drop before an addition, as score.py's does: which words
 * count as right depends on it.
 */
fun sweepAlign(target: List<String>, got: List<String>): Array<String?> {
    val n = target.size
    val m = got.size
    val cost = Array(n + 1) { IntArray(m + 1) }
    for (i in 1..n) cost[i][0] = i
    for (j in 1..m) cost[0][j] = j
    for (i in 1..n) for (j in 1..m) {
        cost[i][j] = minOf(cost[i - 1][j - 1] + (if (target[i - 1] == got[j - 1]) 0 else 1), cost[i - 1][j] + 1, cost[i][j - 1] + 1)
    }
    val pairs = arrayOfNulls<String>(n)
    var i = n
    var j = m
    while (i > 0 || j > 0) {
        if (i > 0 && j > 0 && cost[i][j] == cost[i - 1][j - 1] + (if (target[i - 1] == got[j - 1]) 0 else 1)) { pairs[i - 1] = got[j - 1]; i--; j-- }
        else if (i > 0 && cost[i][j] == cost[i - 1][j] + 1) i--
        else j--
    }
    return pairs
}

/** One typing of a sentence by one thumb: the words that should stand, which of them the thumb typed as meant, and its taps as Replay.kt's `replay` reads them. */
class SweepTyping(val set: Int, val thumb: Int, val words: List<String>, val clean: BooleanArray?, events: String) {
    private val xs: FloatArray
    private val ys: FloatArray
    private val ms: LongArray
    /** The mark an event types from the ?123 page, or 0 for a tap. */
    private val marks: CharArray

    init {
        val parts = events.split(' ').filter { it.startsWith("T:") || it.startsWith("M:") }
        xs = FloatArray(parts.size); ys = FloatArray(parts.size); ms = LongArray(parts.size); marks = CharArray(parts.size)
        for ((i, e) in parts.withIndex()) {
            if (e[0] == 'M') { marks[i] = e[2]; continue }
            val f = e.split(':')
            xs[i] = f[1].toFloat(); ys[i] = f[2].toFloat(); ms[i] = f[3].toLong()
        }
    }

    /** Types it through the decoder as its dials now stand: for each word, whether it is not there as written. */
    fun wrong(board: Board, decoder: Decoder): BooleanArray {
        val typist = Typist(board, decoder)
        for (i in xs.indices) if (marks[i] != 0.toChar()) typist.mark(marks[i]) else typist.tap(xs[i], ys[i], ms[i])
        val pairs = sweepAlign(words, sweepOutWords(typist.text.toString()))
        return BooleanArray(words.size) { pairs[it] != words[it] }
    }
}

/**
 * The sets of sentences sweep.py wrote, a line a fact, tab between fields:
 *   board <file>                                      the keys every typing was made on (score.py's board_file)
 *   typing <set> <thumb> <words> <typed as meant> <events>
 * <words> are the words that should stand, a space between; <typed as meant> a 1 or a 0 for each of them (or "-"
 * where that is not known); <events> as Replay.kt's `replay` takes them.
 */
class SweepSets(root: File, file: File) {
    val names = ArrayList<String>()
    val thumbs = ArrayList<String>()
    val typings = ArrayList<SweepTyping>()
    lateinit var board: Board

    init {
        for (line in file.readLines()) {
            val f = line.split('\t')
            when (f[0]) {
                "board" -> board = Board.recorded(File(f[1]).let { if (it.isAbsolute) it else File(root, f[1]) })
                "typing" -> {
                    if (f[1] !in names) names += f[1]
                    if (f[2] !in thumbs) thumbs += f[2]
                    val words = f[3].split(' ').filter { it.isNotEmpty() }
                    val clean = if (f[4] == "-") null else BooleanArray(words.size) { f[4][it] == '1' }
                    typings += SweepTyping(names.indexOf(f[1]), thumbs.indexOf(f[2]), words, clean, f[5])
                }
            }
        }
        check(typings.isNotEmpty()) { "sweep: ${file.path} has no typing" }
    }
}

/**
 * The typing bench's tap test, made ready to be typed again and again. [typeAll] draws a thumb's taps from one
 * stream of random numbers for all the sentences, and what the keyboard makes of a word changes none of them; so it
 * is run once for each thumb with a keyboard that changes nothing, and every token it typed and every stretch it
 * tallied is kept, cut by sentence. A setting then reads each sentence's tokens as [taps] does ([byTheTaps], the
 * words before a token as the keyboard left them), any sentence on any thread, and tallies the stretches in the
 * order the bench does.
 */
class SweepBench(root: File, words: Int, private val seed: Long, lastLine: Int = SWEEP_LAST_LINE) {
    class Planned(val stretches: List<Stretch>, val tokens: List<Typed>)

    val kb = Keyboard(root)
    val sentences: List<List<String>>
    val oov: Set<String>
    val plans: List<Pair<Noise, List<Planned>>>

    init {
        val picked = sample(kb, Sentences(root, minOf(lastLine, SWEEP_LAST_LINE)), 0, words, seed)
        sentences = picked.first
        oov = picked.second
        plans = NOISES.map { it to plan(it) }
    }

    private fun plan(noise: Noise): List<Planned> {
        val tokens = ArrayList<Typed>()
        val stretches = ArrayList<Stretch>()
        typeAll(kb, sentences, noise, seed, oov, after = { stretches += it }) { t, _ -> tokens += t; null }
        // A stretch never runs past the end of its sentence, so the sentences' lengths say where to cut.
        val out = ArrayList<Planned>(sentences.size)
        var s = 0
        var t = 0
        for (sentence in sentences) {
            val mine = ArrayList<Stretch>()
            val from = t
            var meant = 0
            while (meant < sentence.size) { val stretch = stretches[s++]; meant += stretch.targets.size; t += stretch.typed.size; mine += stretch }
            check(meant == sentence.size) { "sweep: a stretch of the bench's ran past its sentence" }
            out += Planned(mine, tokens.subList(from, t).toList())
        }
        check(s == stretches.size && t == tokens.size) { "sweep: the bench typed ${tokens.size} tokens in ${stretches.size} stretches, and $t in $s were placed" }
        return out
    }

    /** One sentence's stretches as the keyboard leaves them with its dials as they now stand. */
    fun read(p: Planned): List<Stretch> {
        val before = StringBuilder()
        var k = 0
        return p.stretches.map { s ->
            val outs = ArrayList<String>(s.typed.size)
            repeat(s.typed.size) {
                val typed = p.tokens[k++]
                val out = byTheTaps(kb, typed, before.toString() + typed.text) ?: typed.text
                outs += out
                before.append(out).append(' ')
            }
            Stretch(s.targets, s.typed, outs)
        }
    }

    fun tally(read: List<List<Stretch>>): Tally {
        val tally = Tally()
        tally.isWord = { kb.ac.beyondFixing(it) }
        for (sentence in read) for (stretch in sentence) tally.add(stretch, oov)
        return tally
    }
}

/**
 * Waits until [ac] has read its slang list, which it does on a thread of its own (Decoder and Keyboard each give it
 * a fixed part of a second, and on a busy Mac that is not always enough: the first words typed would then be read
 * without it).
 */
fun sweepAwaitSlang(root: File, ac: Autocorrect) {
    val slang = File(root, "android/app/src/main/assets/dict/en_slang.txt").takeIf { it.exists() }?.readLines().orEmpty()
        .map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    val until = System.nanoTime() + 60_000_000_000L
    while (!slang.all { ac.isWord(it) }) {
        check(System.nanoTime() < until) { "sweep: the slang list was not read within a minute" }
        Thread.sleep(20)
    }
}

private fun sweepJson(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun sweep(root: File, args: List<String>) {
    var setsFile: File? = null
    var benchWords = 12_000
    var benchSeed = 7L
    var threads = 4
    var lastLine = SWEEP_LAST_LINE
    var dialsOnly = false
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--sets" -> setsFile = File(args[++i])
            "--bench-words" -> benchWords = args[++i].toInt()
            "--bench-seed" -> benchSeed = args[++i].toLong()
            "--threads" -> threads = args[++i].toInt().coerceAtLeast(1)
            // Never past SWEEP_LAST_LINE, whatever is asked: sweep.py passes scoreboard.py's own bound, should that come to be lower.
            "--last-line" -> lastLine = args[++i].toInt().coerceIn(0, SWEEP_LAST_LINE)
            "--dials" -> dialsOnly = true
            else -> { System.err.println("sweep: unknown option ${args[i]}"); kotlin.system.exitProcess(2) }
        }
        i++
    }
    val began = System.nanoTime()
    val dials = SweepDials()
    val dialsJson = dials.all.values.joinToString(",") { sweepJson(it.name) + ":{\"type\":" + sweepJson(it.type) + ",\"default\":" + sweepJson(it.default.toString()) + "}" }
    val build = sweepBuild()
    if (dialsOnly) { println("ready\t{\"dials\":{$dialsJson},\"build\":${sweepJson(build)}}"); return }

    val sets = setsFile?.let { SweepSets(root, it) }
    val decoder = sets?.let { Decoder(root) }
    val bench = if (benchWords > 0) SweepBench(root, benchWords, benchSeed, lastLine) else null
    decoder?.let { sweepAwaitSlang(root, it.ac) }
    bench?.let { sweepAwaitSlang(root, it.kb.ac) }
    val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "sweep").also { it.isDaemon = true } }
    val out = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), false, "UTF-8")

    val setsJson = sets?.let { s -> s.names.indices.joinToString(",") { k -> sweepJson(s.names[k]) + ":{\"typings\":" + s.typings.count { it.set == k } + ",\"words\":" + s.typings.filter { it.set == k }.sumOf { it.words.size } + "}" } } ?: ""
    val benchJson = bench?.let { "{\"words\":${it.sentences.sumOf { s -> s.size }},\"sentences\":${it.sentences.size},\"unknown\":${it.oov.size},\"seed\":$benchSeed}" } ?: "null"
    out.println("ready\t{\"dials\":{$dialsJson},\"build\":${sweepJson(build)},\"sets\":{$setsJson},\"thumbs\":[${sets?.thumbs?.joinToString(",") { sweepJson(it) } ?: ""}],\"bench\":$benchJson,\"threads\":$threads,\"seconds\":${"%.2f".format(Locale.ROOT, (System.nanoTime() - began) / 1e9)}}")
    out.flush()

    // A few typings or sentences a task: small enough that no thread is left idle at the end, large enough that handing them out costs nothing.
    val typingChunks = sets?.typings?.indices?.chunked(8).orEmpty()
    val benchChunks = bench?.plans?.map { (_, plan) -> plan.indices.chunked(24) }.orEmpty()
    while (true) {
        val line = readLine() ?: break
        val t0 = System.nanoTime()
        // "only=sets" or "only=bench" before a setting types that part alone: a search asks for the sets first, and
        // for the bench only where the sets have not already ruled the setting out.
        var only = ""
        val setting = line.trim().split(Regex("\\s+")).filter { part -> if (part.startsWith("only=")) { only = part.substring(5); false } else true }.joinToString(" ")
        val set = try {
            require(only == "" || only == "sets" || only == "bench") { "only=$only: only=sets or only=bench" }
            dials.apply(setting)
        } catch (e: Exception) {
            dials.apply("")
            val why = (e.message ?: e.toString()).replace('\t', ' ').replace('\n', ' ')
            out.println("error $why\t{\"error\":${sweepJson(why)}}")
            out.flush()
            continue
        }
        val typed = sets.takeIf { only != "bench" }
        val benched = bench.takeIf { only != "sets" }
        // Everything is handed to the pool at once; each task writes where no other does.
        val wrong = arrayOfNulls<BooleanArray>(typed?.typings?.size ?: 0)
        val setNanos = LongArray(typed?.names?.size ?: 0)
        val read = benched?.plans?.map { arrayOfNulls<List<Stretch>>(it.second.size) }.orEmpty()
        val benchNanos = LongArray(read.size)
        var misspelled: Pair<Int, Int>? = null
        val tasks = ArrayList<Callable<Unit>>()
        if (typed != null) for (chunk in typingChunks) tasks += Callable {
            // A chunk may hold the last typings of one set and the first of the next: each is clocked for its own.
            for (k in chunk) {
                val began = sweepThreadNanos()
                wrong[k] = typed.typings[k].wrong(typed.board, decoder!!)
                val took = sweepThreadNanos() - began
                synchronized(setNanos) { setNanos[typed.typings[k].set] += took }
            }
        }
        if (benched != null) {
            for ((n, chunks) in benchChunks.withIndex()) for (chunk in chunks) tasks += Callable {
                val began = sweepThreadNanos()
                for (k in chunk) read[n][k] = benched.read(benched.plans[n].second[k])
                val took = sweepThreadNanos() - began
                synchronized(benchNanos) { benchNanos[n] += took }
            }
            tasks += Callable { misspelled = misspellings(benched.kb, true) }
        }
        // Every task is waited for, so nothing of this setting is still typing when the next one's dials are set.
        val failed = pool.invokeAll(tasks).firstNotNullOfOrNull { f -> runCatching { f.get() }.exceptionOrNull() }
        if (failed != null) {
            dials.apply("")
            val cause = failed.cause ?: failed
            val why = ("the decoder threw at this setting: " + cause.toString()).replace('\t', ' ').replace('\n', ' ')
            out.println("error $why\t{\"error\":${sweepJson(why)}}")
            out.flush()
            continue
        }

        val human = StringBuilder()
        val json = StringBuilder("{\"setting\":{" + set.entries.joinToString(",") { sweepJson(it.key) + ":" + sweepJson(it.value) } + "}")
        if (typed != null) {
            json.append(",\"sets\":{")
            for (k in typed.names.indices) {
                // For each thumb: words, wrong, of them slips left wrong, of them words typed as meant and spoiled.
                val rows = Array(typed.thumbs.size) { IntArray(4) }
                val bits = StringBuilder()
                for ((t, typing) in typed.typings.withIndex()) {
                    if (typing.set != k) continue
                    val w = wrong[t]!!
                    val row = rows[typing.thumb]
                    for (j in w.indices) {
                        row[0]++
                        if (w[j]) { row[1]++; if (typing.clean != null) { if (typing.clean[j]) row[3]++ else row[2]++ } }
                        bits.append(if (w[j]) '1' else '0')
                    }
                }
                val words = rows.sumOf { it[0] }
                val all = rows.sumOf { it[1] }
                human.append(typed.names[k])
                for ((t, row) in rows.withIndex()) human.append(" ${typed.thumbs[t]} ${"%.2f".format(Locale.ROOT, 100.0 * row[1] / row[0].coerceAtLeast(1))}%")
                human.append(" all ${"%.2f".format(Locale.ROOT, 100.0 * all / words.coerceAtLeast(1))}% ($all of $words) | ")
                if (k > 0) json.append(',')
                json.append(sweepJson(typed.names[k]) + ":{\"thumbs\":{" + rows.indices.joinToString(",") { t -> sweepJson(typed.thumbs[t]) + ":[" + rows[t].joinToString(",") + "]" } + "}")
                json.append(",\"micros\":${"%.1f".format(Locale.ROOT, setNanos[k] / 1000.0 / words.coerceAtLeast(1))},\"wrong\":\"$bits\"}")
            }
            json.append('}')
        }
        if (benched != null) {
            val tallies = benched.plans.indices.map { n -> benched.tally(read[n].map { it!! }) }
            human.append("bench right " + tallies.joinToString(" ") { "%.2f%%".format(Locale.ROOT, 100.0 * it.right / it.words.coerceAtLeast(1)) })
            human.append(", spoiled per 1000 " + tallies.joinToString(" ") { "%.2f".format(Locale.ROOT, 1000.0 * it.spoiled / it.plainTypedRight.coerceAtLeast(1)) })
            human.append(", unknown spoiled " + tallies.joinToString(" ") { "%.1f%%".format(Locale.ROOT, 100.0 * it.oovSpoiled / it.oovTypedRight.coerceAtLeast(1)) })
            val (mended, asked) = misspelled!!
            human.append(", misspellings $mended of $asked | ")
            json.append(",\"bench\":{")
            for ((n, t) in tallies.withIndex()) {
                val name = benched.plans[n].first.name
                if (n > 0) json.append(',')
                json.append(sweepJson(name) + ":{\"words\":${t.words},\"right\":${t.right},\"rawRight\":${t.rawRight},\"plain\":${t.plain},\"plainTypedRight\":${t.plainTypedRight},\"spoiled\":${t.spoiled}")
                json.append(",\"slips\":${t.slips},\"fixed\":${t.fixed},\"misfixed\":${t.misfixed},\"joins\":${t.joins},\"joinsFixed\":${t.joinsFixed},\"splits\":${t.splits}")
                json.append(",\"oov\":${t.oov},\"oovTypedRight\":${t.oovTypedRight},\"oovSpoiled\":${t.oovSpoiled},\"realWord\":${t.realWord},\"realWordFixed\":${t.realWordFixed},\"commonSpoiled\":${t.commonSpoiled}")
                json.append(",\"micros\":${"%.1f".format(Locale.ROOT, benchNanos[n] / 1000.0 / t.words.coerceAtLeast(1))},\"line\":${sweepJson(t.line("$name: by the taps"))}}")
            }
            json.append("},\"misspellings\":[$mended,$asked]")
        }
        val seconds = "%.2f".format(Locale.ROOT, (System.nanoTime() - t0) / 1e9)
        json.append(",\"seconds\":$seconds}")
        human.append("$seconds s | " + if (set.isEmpty()) "as the tree is" else set.entries.joinToString(" ") { it.key + "=" + it.value })
        out.println("$human\t$json")
        out.flush()
    }
    pool.shutdown()
}

fun main(args: Array<String>) {
    val root = File(args.getOrNull(0) ?: ".")
    when (args.getOrNull(1)) {
        "sweep" -> sweep(root, args.drop(2))
        else -> { System.err.println("usage: Sweep <repo root> sweep [--sets <file>] [--bench-words N] [--threads N] [--dials]   (tool/versus/replay.sh sweep ...)"); kotlin.system.exitProcess(2) }
    }
}
