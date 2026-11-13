// The typing benchmark (tool/typing_bench.sh): autocorrect, swipe typing and next-word prediction scored on sentences
// they never saw, typed by a simulated thumb. Plain Kotlin on the JVM over the keyboard's own Autocorrect.kt,
// SwipeDecoder.kt and NextWordModel.kt; nothing here is in the app.
//
// The sentences are Tatoeba's held-out ones (every 20th, never counted for the starter tables), written to
// build/typing-bench/en-heldout.txt by tool/typing_bench.sh. A simulated thumb is not a person: the numbers are for
// comparing one version of the keyboard with another on the same strokes, not for saying how good it is. The accuracy
// record (AccuracyLog, tool/accuracy-replay.sh) is the measure on real typing.
//
// Usage: Bench <repo root> [taps|row|swipe|turns|next|mistakes|voice|tune|tuneswipe|all]   (-Dwords=…, -Dseed=…, -Dverbose=true)
// taps also takes -Dnames=false (no name is asked for its capital, as before there were any) and -Dcapitals=true
// (which words were given a capital amiss, which names were left without theirs, and which words that came out
// wrong stand with a capital).

import com.infamousvague.devkeyboard.Autocorrect
import com.infamousvague.devkeyboard.LanguageState
import com.infamousvague.devkeyboard.MistakeMemory
import com.infamousvague.devkeyboard.Layouts
import com.infamousvague.devkeyboard.NextWordText
import com.infamousvague.devkeyboard.NgramTable
import com.infamousvague.devkeyboard.PersonalPairs
import com.infamousvague.devkeyboard.Scorer
import com.infamousvague.devkeyboard.TapDecoder
import com.infamousvague.devkeyboard.Taps
import com.infamousvague.devkeyboard.Words
import java.io.File
import java.util.Random

/**
 * A drawn QWERTY: keys [W] wide and [H] tall, the space bar under c v b n m. A phone's keys are about 1 to 1.5;
 * a tablet's or a foldable's inner screen's nearer 1 to 1 (-Dkeyh=100), where the bottom row sits close over the bar.
 */
object Geo {
    const val W = 100f
    val H = System.getProperty("keyh", "150").toFloat()
    val place: Map<Char, Pair<Float, Int>> = Layouts.QWERTY.positions
    val centres: Map<Char, Pair<Float, Float>> = place.mapValues { (_, p) -> (p.first * W + W / 2) to (p.second * H + H / 2) }
    private val rows: List<List<Pair<Char, Float>>> = (0..2).map { r -> place.filter { it.value.second == r }.map { it.key to it.value.first * W }.sortedBy { it.second } }
    const val SPACE_LEFT = 2.5f * W
    const val SPACE_RIGHT = 8.5f * W
    val SPACE_TOP = 3f * H

    /** The key under (x, y): a letter, or ' ' for the space bar. Outside every key, the nearest letter of that row. */
    fun hit(x: Float, y: Float): Char {
        if (y >= SPACE_TOP && x >= SPACE_LEFT && x < SPACE_RIGHT) return ' '
        val row = rows[(y / H).toInt().coerceIn(0, 2)]
        row.firstOrNull { x >= it.second && x < it.second + W }?.let { return it.first }
        return row.minByOrNull { kotlin.math.abs(x - (it.second + W / 2)) }!!.first
    }
}

/** How a simulated thumb misses: Gaussian around the key's middle, a share of wilder taps, and slips of order. */
class Noise(val name: String, val sx: Float, val sy: Float, val wild: Float = 0.06f, val omit: Float = 0.004f, val double: Float = 0.003f, val swap: Float = 0.004f, val noSpace: Float = 0.004f)

val NOISES = listOf(
    Noise("careful", 0.20f, 0.15f),
    Noise("normal", 0.27f, 0.20f),
    Noise("sloppy", 0.34f, 0.25f),
)

/** One word as the thumb typed it: the letters the keys gave, and where each tap landed (NaN for an apostrophe). */
class Typed(val text: String, val xs: FloatArray, val ys: FloatArray)

/** What came of a stretch of the sentence: the words meant, the tokens typed, and what the keyboard left there. */
class Unit(val targets: List<String>, val typed: List<String>, val out: List<String>) {
    val right get() = out.joinToString(" ").lowercase() == targets.joinToString(" ")
    val typedRight get() = typed.joinToString(" ") == targets.joinToString(" ")
}

class Tally {
    var words = 0; var right = 0; var rawRight = 0
    var plain = 0; var plainTypedRight = 0; var spoiled = 0; var slips = 0; var fixed = 0; var misfixed = 0
    var joins = 0; var joinsFixed = 0; var splits = 0
    var oov = 0; var oovTypedRight = 0; var oovSpoiled = 0
    val examples = ArrayList<String>()
    /** Slips that typed another real word (form for from): nothing that leaves real words alone can mend them. */
    var realWord = 0
    var realWordFixed = 0
    var commonSpoiled = 0
    var isWord: (String) -> Boolean = { false }
    /**
     * Capitals, which every other count here leaves aside. Of the names typed in lower case that came out with the
     * right letters (a word the sentence writes with a capital in its middle: Tom, Monday; not I), how many came out
     * with their capital; and of the words it writes in lower case there, how many came out with one, which is a
     * right word spoiled ([capital]).
     */
    var names = 0; var namesCapital = 0; var lowers = 0; var lowersCapital = 0
    /** The words given a capital amiss ("will → Will"), and the names left without theirs, each as the sentence writes it. */
    val amiss = ArrayList<String>()
    val without = ArrayList<String>()
    /**
     * What the two counts above pass over, since each goes by words that came out with the right letters: the words
     * that came out wrong and stand with a capital (a slip that spells a listed name, or is mended into one: "ben"
     * for been is Ben). They were wrong before names too, and no other count moves by them; but it is what is seen.
     * Never I or I'm, which had their capital before names.
     */
    var astray = 0
    val astrays = ArrayList<String>()
    /**
     * [u] as [written] has its one word ("" where the word's capital could be its sentence's, and is not counted):
     * a word typed as one token and come out with the right letters, as one word or mended into it. A stretch that
     * came out wrong is looked at for [astray], wherever it stands (the thumb types no capital, so any is the
     * keyboard's own).
     */
    fun capital(u: Unit, written: String) {
        if (!u.right) {
            val given = u.out.flatMap { it.split(' ') }.filter { it.isNotEmpty() && it[0].isUpperCase() && it != "I" && !it.startsWith("I'") }
            if (given.isNotEmpty()) { astray += given.size; astrays += "${u.typed.joinToString(" ")} → ${u.out.joinToString(" ")}, meant ${u.targets.joinToString(" ")}" }
            return
        }
        if (written.isEmpty() || u.targets.size != 1 || u.typed.size != 1 || written == "I" || written.startsWith("I'")) return
        val given = u.out[0].first().isUpperCase()
        if (written[0].isUpperCase() && written.drop(1) == written.drop(1).lowercase()) { names++; if (given) namesCapital++ else without += written }
        else if (written == written.lowercase()) { lowers++; if (given) { lowersCapital++; amiss += "${u.typed[0]} → ${u.out[0]}" } }
    }
    fun capitalsLine(name: String) = String.format(
        "%-22s capitals: names given theirs %d of %d (%.1f%%), a capital given amiss %d of %d words (%.2f per 1000), on a word that came out wrong %d",
        name, namesCapital, names, 100.0 * namesCapital / names.coerceAtLeast(1), lowersCapital, lowers, 1000.0 * lowersCapital / lowers.coerceAtLeast(1), astray,
    )
    fun add(u: Unit, oovWords: Set<String>) {
        words += u.targets.size
        if (u.right) right += u.targets.size
        if (u.typedRight) rawRight += u.targets.size
        when {
            u.targets.size > 1 -> { joins++; if (u.right) joinsFixed++ else if (examples.size < 400) examples += "join   ${u.typed} → ${u.out}, meant ${u.targets}" }
            u.typed.size != 1 -> splits++
            else -> {
                plain++
                val isOov = u.targets[0] in oovWords
                if (isOov) oov++
                if (u.typedRight) {
                    plainTypedRight++
                    if (isOov) oovTypedRight++
                    if (!u.right) { spoiled++; if (isOov) oovSpoiled++; if (isWord(u.typed[0])) commonSpoiled++; examples += "spoil  ${u.typed[0]} → ${u.out[0]}" }
                } else {
                    slips++
                    if (isWord(u.typed[0])) { realWord++; if (u.right) realWordFixed++ }
                    if (u.right) fixed++ else {
                        if (u.out[0].lowercase() != u.typed[0]) misfixed++
                        if (examples.size < 400) examples += (if (u.out[0].lowercase() != u.typed[0]) "wrong  " else "missed ") + "${u.typed[0]} → ${u.out[0]}, meant ${u.targets[0]}"
                    }
                }
            }
        }
    }
    fun line(name: String) = String.format(
        "%-22s words right %5.2f%% (as typed %5.2f%%)  slips fixed %5.1f%% of %d (wrong word %4.1f%%)  right words spoiled %5.2f per 1000  joins fixed %4.1f%% of %d  unknown words spoiled %4.1f%% of %d",
        name, 100.0 * right / words.coerceAtLeast(1), 100.0 * rawRight / words.coerceAtLeast(1), 100.0 * fixed / slips.coerceAtLeast(1), slips, 100.0 * misfixed / slips.coerceAtLeast(1),
        1000.0 * spoiled / plainTypedRight.coerceAtLeast(1), 100.0 * joinsFixed / joins.coerceAtLeast(1), joins, 100.0 * oovSpoiled / oovTypedRight.coerceAtLeast(1), oovTypedRight,
    ) + String.format("  [real-word slips %d, fixed %d; common words spoiled %d; splits %d]", realWord, realWordFixed, commonSpoiled, splits)
}

/** Names, handles and terms no word list has: typed on purpose, and to be left alone. Filtered against the lists at run time. */
val UNKNOWN_WORDS = """
    kira nguyen okafor priyanka siobhan thandiwe zoltan anneke bjorn caoimhe dmitri eamon fenwick giulia hamza ingrid
    jovana kwame leilani mireille nikhil oksana piotr quillon raheem saoirse tadeusz ulrika vasil wyndham xiomara yusra
    zainab ghostkbd vibecoding yeeted pushups worktree standup rizzler skibidi gyatt delulu bussin unalive finsta
    grubhub doordashed venmoed ubered pogchamp kekw copium hopium ratioed doomscroll sigma mewing aura fanum looksmax
    kubectl nvim tmux zsh fastlane mapbox supabase vercel nextjs tailwind zustand pnpm turborepo vitest
    mattv attackfm ghostmd glacierui wispr mockbird kbd devkbd
""".trim().split(Regex("\\s+"))

/** Words as they are commonly misspelled, and what was meant: typed on purpose, letter for letter, so no tap is on a line. */
val MISSPELLINGS = """
    definately definitely  seperate separate  recieve receive  occured occurred  untill until  wierd weird
    beleive believe  goverment government  tommorow tomorrow  tomorow tomorrow  accomodate accommodate  embarass embarrass
    calender calendar  grammer grammar  existance existence  independant independent  arguement argument  truely truly
    thier their  freind friend  buisness business  neccessary necessary  necesary necessary  occassion occasion
    adress address  apparantly apparently  basicly basically  becuase because  begining beginning  beautifull beautiful
    completly completely  concious conscious  dissapoint disappoint  enviroment environment  excelent excellent
    experiance experience  familar familiar  finaly finally  foward forward  garantee guarantee  happend happened
    immediatly immediately  intresting interesting  knowlege knowledge  libary library  noticable noticeable
    oppurtunity opportunity  persistant persistent  posession possession  prefered preferred  probaly probably
    realy really  recomend recommend  refered referred  relevent relevant  remeber remember  responsable responsible
    sucessful successful  suprise surprise  tendancy tendency  togther together  unfortunatly unfortunately
    usualy usually  wich which  writting writing  acheive achieve  agressive aggressive  alot a_lot  awfull awful
    comming coming  diffrent different  dosent doesn't  expecially especially  favorit favorite  intrested interested
    litle little  peice piece  recieved received  sincerly sincerely  somthing something  speach speech  succesful successful
    tounge tongue  yesturday yesterday  wether whether  wich which  whith with  youre you're
""".trim().split(Regex("\\s+")).chunked(2).map { it[0] to it[1].replace('_', ' ') }

/** How many of [MISSPELLINGS] the keyboard mends when each is typed with a careful thumb after "the " (three times each). */
fun misspellings(kb: Keyboard, useTaps: Boolean, show: Boolean = false): Pair<Int, Int> {
    val rnd = Random(3)
    var fixed = 0; var asked = 0
    val missed = ArrayList<String>()
    for ((wrong, right) in MISSPELLINGS) repeat(3) {
        val xs = FloatArray(wrong.length); val ys = FloatArray(wrong.length)
        for (i in wrong.indices) { val (cx, cy) = Geo.centres.getValue(wrong[i]); xs[i] = cx + rnd.nextGaussian().toFloat() * 0.13f * Geo.W; ys[i] = cy + rnd.nextGaussian().toFloat() * 0.1f * Geo.H }
        val out = kb.ac.correct(wrong, kb.scorer("i think it is $wrong"), if (useTaps) Taps(xs, ys, Geo.centres, Geo.W, SPACE) else null) ?: wrong
        asked++
        if (out.lowercase() == right) fixed++ else if (it == 0) missed += "$wrong → $out"
    }
    if (show) println("    not mended: $missed")
    return fixed to asked
}

class Sentences(root: File) {
    val lines: List<String> = File(root, "build/typing-bench/en-heldout.txt").readLines()
    /** A sentence's words in lower case: letters and apostrophes only; anything else (a number, a symbol) is dropped with its context. */
    fun words(line: String): List<String> = line.split(Regex("\\s+")).map { it.trim { c -> !c.isLetter() }.replace('’', '\'') }
        // An acronym (NASA, TV) is typed in capitals and never touched: it is not what is measured here.
        .filter { it.isNotEmpty() && !(it.length >= 2 && it.all { c -> c.isUpperCase() }) }.map { it.lowercase() }
        .filter { it.all { c -> c in 'a'..'z' || c == '\'' } }

    /**
     * How the sentence writes each of [words]' words (Tom, will), for counting capitals: "" for a word whose capital
     * could be its sentence's and not its own. A word is counted where tool/capitals.py counts one: straight after
     * another word of its sentence, with a space between them, or a comma or semicolon and a space. Never the first
     * word, nor one after a quotation mark, a bracket, a colon, a dash, a digit or a full stop.
     */
    fun written(line: String): List<String> {
        val raw = line.split(Regex("\\s+"))
        val out = ArrayList<String>()
        for ((i, tok) in raw.withIndex()) {
            val w = tok.trim { c -> !c.isLetter() }.replace('’', '\'')
            if (w.isEmpty() || (w.length >= 2 && w.all { c -> c.isUpperCase() }) || !w.lowercase().all { c -> c in 'a'..'z' || c == '\'' }) continue
            val prev = raw.getOrNull(i - 1).orEmpty()
            val after = prev.isNotEmpty() && (prev.last().isLetter() || (prev.length > 1 && prev.last() in ",;" && prev[prev.length - 2].isLetter()))
            out += if (after && tok.first().isLetter()) w else ""
        }
        return out
    }
}

class Keyboard(root: File) {
    val assets = File(root, "android/app/src/main/assets")
    val state = LanguageState.of(listOf("en"), emptyMap(), "")
    val ac = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
    val table: NgramTable = File(assets, "ngram/en.tsv").useLines { NgramTable.parse(it) }!!
    var personal = PersonalPairs()
    val contractions: Map<String, String> = File(assets, "dict/en_contractions.tsv").readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[1] to it[0] } }.toMap()
    val lookup = object : Scorer.Lookup {
        override fun isWord(lower: String) = ac.isWord(lower)
        override fun form(lower: String) = personal.form(lower)
        override fun count(lower: String) = personal.unigram(lower)
        override fun starting(prefix: String) = personal.starting(prefix)
        override fun fills() = personal.top(3).map { personal.form(it) } + ac.commonest(6)
    }
    init {
        ac.loadNow(state.dictionaries, state.board)
        ac.setSlang(true)
        Thread.sleep(300) // the slang list is read on its own thread
    }
    fun isWordChar(c: Char) = Words.isWordChar(c, "'")
    /** A stroke read as the keyboard reads it: [before] is the word before, or "^". */
    var swipeContext = true
    fun swipe(path: List<Pair<Float, Float>>, times: List<Long>, before: String, text: String? = null): List<String> =
        ac.swipePlain(path, Geo.centres, Geo.W, before, if (swipeContext && text != null) scorer(text) else null)
    fun scorer(before: String): Scorer? {
        val place = NextWordText.place(before, ::isWordChar, false) ?: return null
        val one = personal.followers(place.prev)
        val two = place.prev2?.takeIf { place.prev != NextWordText.START }?.let { personal.followers("$it ${place.prev}") } ?: emptyList()
        return Scorer(place, listOf(table), Scorer.weights(listOf(table), place.recent), one, two, lookup)
    }
}

/** Types [sentences] with [noise] and lets [fix] have each finished word; what came of it, tallied. */
fun typeAll(
    kb: Keyboard, sentences: List<List<String>>, noise: Noise, seed: Long, oov: Set<String>,
    /** The letters the person goes for in place of a word's own (a habit of theirs), or null for the word as it is spelled. */
    habit: ((String) -> String?)? = null,
    /** Told what came of each stretch, once the keyboard has had it. */
    after: ((Unit) -> kotlin.Unit)? = null,
    /** How each sentence writes each of its words ([Sentences.written]), where capitals are to be counted ([Tally.capital]). */
    written: List<List<String>>? = null,
    fix: (typed: Typed, before: String) -> String?,
): Tally {
    val rnd = Random(seed)
    val tally = Tally()
    tally.isWord = { kb.ac.beyondFixing(it) }
    fun gauss(s: Float) = (rnd.nextGaussian() * s * (if (rnd.nextFloat() < noise.wild) 2.2 else 1.0)).toFloat()
    for ((at, sentence) in sentences.withIndex()) {
        val before = StringBuilder()
        // The word being typed, the taps that made it, and the words it is meant to be (two, where a space was missed).
        val text = StringBuilder(); val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        val meant = ArrayList<String>(); val tokens = ArrayList<String>(); val outs = ArrayList<String>()
        // Which word of the sentence was meant last: where a stretch is one word, the word [written] says how to write.
        var last = -1
        fun endToken() {
            if (text.isEmpty()) return
            val typed = Typed(text.toString(), xs.toFloatArray(), ys.toFloatArray())
            val out = fix(typed, before.toString() + typed.text) ?: typed.text
            tokens += typed.text; outs += out
            before.append(out).append(' ')
            text.setLength(0); xs.clear(); ys.clear()
        }
        fun endUnit() {
            if (meant.isEmpty()) return
            val unit = Unit(meant.toList(), tokens.toList(), outs.toList())
            tally.add(unit, oov)
            // Every stretch, for the capitals: one with no word to hold to how the sentence writes it (its first word,
            // two run together) still counts where it came out wrong and with a capital.
            if (written != null) tally.capital(unit, written.getOrNull(at)?.getOrNull(last).orEmpty())
            after?.invoke(unit)
            meant.clear(); tokens.clear(); outs.clear()
        }
        fun tap(c: Char) {
            if (c == '\'') { text.append(c); xs += Float.NaN; ys += Float.NaN; return }
            val (cx, cy) = Geo.centres.getValue(c)
            val x = cx + gauss(noise.sx) * Geo.W; val y = cy + gauss(noise.sy) * Geo.H
            val k = Geo.hit(x, y)
            if (k == ' ') endToken() else { text.append(k); xs += x; ys += y }
        }
        for ((n, word) in sentence.withIndex()) {
            meant += word
            last = n
            // A contraction is typed without its apostrophe, as people type them; any other apostrophe on purpose.
            val keys = habit?.invoke(word) ?: kb.contractions[word] ?: word
            var i = 0
            while (i < keys.length) {
                val r = rnd.nextFloat()
                when {
                    r < noise.omit && keys.length > 1 -> Unit
                    r < noise.omit + noise.swap && i + 1 < keys.length -> { tap(keys[i + 1]); tap(keys[i]); i++ }
                    else -> { tap(keys[i]); if (rnd.nextFloat() < noise.double && keys[i] != '\'') tap(keys[i]) }
                }
                i++
            }
            // The space: missed altogether, or a tap that may land on the letters above the bar.
            if (rnd.nextFloat() < noise.noSpace) continue
            val x = (3.5f + rnd.nextFloat() * 3f) * Geo.W + gauss(noise.sx) * Geo.W
            val y = 3.5f * Geo.H + gauss(noise.sy) * Geo.H
            val k = Geo.hit(x, y)
            if (k == ' ') { endToken(); endUnit() } else { text.append(k); xs += x; ys += y }
        }
        endToken(); endUnit()
    }
    return tally
}

val SPACE = floatArrayOf(Geo.SPACE_LEFT, Geo.SPACE_TOP, Geo.SPACE_RIGHT, Geo.SPACE_TOP + Geo.H)

/** The tap test asks names for their capitals ([Autocorrect.correct]'s names), unless told -Dnames=false. */
val NAMES = System.getProperty("names", "true") == "true"

fun taps(root: File) {
    val kb = Keyboard(root)
    val all = Sentences(root)
    val limit = System.getProperty("words", "30000").toInt()
    val seed = System.getProperty("seed", "7").toLong()
    val verbose = System.getProperty("verbose") == "true"
    val oovList = UNKNOWN_WORDS.filter { !kb.ac.isWord(it) }.distinct()
    val oov = oovList.toSet()
    val rnd = Random(seed)
    val sentences = ArrayList<List<String>>()
    // How each sentence writes each word, for the capitals: a word swapped for one no list has is written in lower case.
    val written = ArrayList<List<String>>()
    var n = 0
    for (line in all.lines) {
        if (n >= limit) break
        val how = all.written(line).toMutableList()
        val words = all.words(line).mapIndexed { i, w -> if (rnd.nextFloat() < 0.03f) oovList[rnd.nextInt(oovList.size)].also { if (how[i].isNotEmpty()) how[i] = it } else w }
        if (words.isEmpty()) continue
        sentences += words; written += how; n += words.size
    }
    println("tap typing: $n words in ${sentences.size} held-out sentences, 3% of them swapped for words no list has (${oov.size} of them)")
    misspellings(kb, false, verbose).let { (f, a) -> println("misspellings mended, by neighbours: $f of $a") }
    misspellings(kb, true, true).let { (f, a) -> println("misspellings mended, by the taps:   $f of $a") }
    for (noise in NOISES) {
        val none = typeAll(kb, sentences, noise, seed, oov) { _, _ -> null }
        println(none.line("${noise.name}: no autocorrect"))
        val plain = typeAll(kb, sentences, noise, seed, oov) { t, _ -> kb.ac.correct(t.text) }
        println(plain.line("${noise.name}: no context"))
        val context = typeAll(kb, sentences, noise, seed, oov) { t, before -> kb.ac.correct(t.text, kb.scorer(before)) }
        println(context.line("${noise.name}: with context"))
        val t0 = System.nanoTime()
        var calls = 0
        // As a message field has it: names are asked for their capitals (-Dnames=false: as before there were any).
        // Words are scored with capitals aside, so a capital shows in that line only by what it does to the words
        // after it; the line under it counts the capitals themselves.
        val byTaps = typeAll(kb, sentences, noise, seed, oov, written = written) { t, before -> calls++; kb.ac.correct(t.text, kb.scorer(before), Taps(t.xs, t.ys, Geo.centres, Geo.W, SPACE), NAMES) }
        println(byTaps.line("${noise.name}: by the taps") + String.format("  (%.0f µs a word)", (System.nanoTime() - t0) / 1000.0 / calls.coerceAtLeast(1)))
        println(byTaps.capitalsLine("${noise.name}: by the taps"))
        // -Dcapitals=true: which words were given a capital amiss, and which names were left without, commonest first.
        if (verbose || System.getProperty("capitals") == "true") {
            byTaps.amiss.forEach { println("    capital amiss  $it") }
            byTaps.astrays.forEach { println("    capital on a wrong word  $it") }
            println("    left without   " + byTaps.without.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString(" ") { "${it.key}:${it.value}" })
        }
        if (verbose) byTaps.examples.take(150).forEach { println("    $it") }
        if (System.getProperty("spoils") == "true") byTaps.examples.filter { it.startsWith("spoil") }.forEach { println("    $it") }
    }
}

/** A cost by name ("A.UNKNOWN" is Autocorrect's, "T.OMIT" is TapDecoder's), for trying others. */
fun setCost(name: String, value: Float) {
    val cls = if (name.startsWith("A.")) Autocorrect::class.java else TapDecoder::class.java
    cls.getField(name.substring(2)).setFloat(null, value)
}
fun getCost(name: String): Float = (if (name.startsWith("A.")) Autocorrect::class.java else TapDecoder::class.java).getField(name.substring(2)).getFloat(null)

fun sample(kb: Keyboard, all: Sentences, from: Int, limit: Int, seed: Long): Pair<List<List<String>>, Set<String>> {
    val oovList = UNKNOWN_WORDS.filter { !kb.ac.isWord(it) }.distinct()
    val rnd = Random(seed)
    val sentences = ArrayList<List<String>>()
    var n = 0
    for (line in all.lines.drop(from)) {
        if (n >= limit) break
        val words = all.words(line).map { if (rnd.nextFloat() < 0.03f) oovList[rnd.nextInt(oovList.size)] else it }
        if (words.isEmpty()) continue
        sentences += words; n += words.size
    }
    return sentences to oovList.toSet()
}

/**
 * Sets the tap reading's costs by trying each in turn on one stretch of sentences (three kinds of thumb), keeping
 * what does best, twice round; then says what that does on another stretch it never saw. What is best: words right,
 * less four for every right word spoiled, since a word changed that was right is the worse fault by far.
 */
val WEIGHT = System.getProperty("weight", "4").toDouble()

fun tune(root: File) {
    val kb = Keyboard(root)
    val all = Sentences(root)
    val (train, oov) = sample(kb, all, 0, System.getProperty("words", "12000").toInt(), 7)
    val (test, oov2) = sample(kb, all, 40_000, 20_000, 11)
    fun worth(sentences: List<List<String>>, o: Set<String>, seed: Long, show: Boolean = false): Double {
        var total = 0.0
        for (noise in NOISES) {
            val t = typeAll(kb, sentences, noise, seed, o) { t, before -> kb.ac.correct(t.text, kb.scorer(before), Taps(t.xs, t.ys, Geo.centres, Geo.W, SPACE)) }
            total += 100.0 * (t.right - WEIGHT * t.spoiled) / t.words
            if (show) println(t.line(noise.name))
        }
        // Misspellings count too: all of them mended is worth three points.
        val (fixed, asked) = misspellings(kb, true)
        if (show) println("misspellings mended: $fixed of $asked")
        return total / NOISES.size + 3.0 * fixed / asked
    }
    val grid = linkedMapOf(
        "T.SPATIAL" to listOf(1.2f, 1.5f, 1.9f, 2.3f, 2.8f),
        "T.WHOLE_CHANGE" to listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f),
        "A.UNKNOWN" to listOf(1.6f, 2.0f, 2.4f, 2.8f, 3.2f),
        "A.UNKNOWN_ODD" to listOf(0.6f, 1.0f, 1.4f, 1.8f, 2.2f),
        "A.UNKNOWN_LONG" to listOf(0.8f, 1.2f, 1.6f, 2.0f, 2.6f, 3.2f),
        "T.VOWEL" to listOf(1.8f, 2.2f, 2.6f, 3.2f, 3.8f),
        "A.LONG_WORD" to listOf(5f, 6f, 7f, 8f),
        "A.RARE_GAP" to listOf(1.5f, 2f, 2.5f),
        "A.RARE_BUDGET" to listOf(1.6f, 2.2f, 2.8f, 3.4f, 4f),
        "A.MARGIN_COMMON" to listOf(1.2f, 1.6f, 2.0f, 2.5f, 3.0f),
        "A.MARGIN_RARE" to listOf(0.6f, 1.0f, 1.5f, 2.0f),
        "A.LISTED_BUDGET" to listOf(1.4f, 1.8f, 2.2f, 2.8f, 3.4f),
        "T.FAR" to listOf(2.4f, 2.8f, 3.2f, 3.8f, 4.4f),
        "T.FAR_SHORT" to listOf(3.2f, 3.8f, 4.4f, 5.2f),
        "T.OMIT" to listOf(1.6f, 2.0f, 2.5f, 3.0f),
        "T.OMIT_DOUBLED" to listOf(0.8f, 1.2f, 1.6f, 2.0f),
        "T.INSERT" to listOf(1.6f, 2.0f, 2.4f, 2.8f),
        "T.INSERT_NEAR" to listOf(0.8f, 1.2f, 1.6f, 2.0f),
        "T.INSERT_DOUBLED" to listOf(0.6f, 1.0f, 1.4f, 1.8f),
        "T.INSERT_FIRST" to listOf(2.4f, 2.8f, 3.4f, 4.0f),
        "T.SWAP" to listOf(1.1f, 1.5f, 1.9f, 2.3f),
        "T.NO_SPACE" to listOf(1.4f, 2.0f, 2.6f, 3.2f),
        "T.JOIN_BUDGET" to listOf(1.4f, 1.8f, 2.2f, 2.8f, 3.4f),
        "A.JOIN_BAR" to listOf(0.0f, 0.6f, 1.2f, 1.8f, 2.4f),
        "A.JOIN_UNSEEN" to listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f),
        "A.JOIN_FIRST" to listOf(3.0f, 3.5f, 4.0f, 4.5f),
    )
    System.getProperty("only")?.split(',')?.let { keep -> grid.keys.retainAll(keep.toSet()) }
    var best = worth(train, oov, 7)
    println(String.format("tuning on %d words: starts at %.3f; unseen sentences %.3f", train.sumOf { it.size }, best, worth(test, oov2, 11)))
    repeat(System.getProperty("rounds", "2").toInt()) { round ->
        for ((name, values) in grid) {
            val was = getCost(name)
            var keep = was
            for (v in values) {
                if (v == was) continue
                setCost(name, v)
                val w = worth(train, oov, 7)
                if (System.getProperty("curve") == "true") println(String.format("    %-18s %4.2f   %.3f", name, v, w))
                if (w > best + 0.005) { best = w; keep = v }
            }
            setCost(name, keep)
            println(String.format("  round %d  %-18s %4.1f → %4.1f   %.3f", round + 1, name, was, keep, best))
        }
    }
    println("set: " + grid.keys.joinToString("  ") { "$it=${getCost(it)}" })
    println(String.format("unseen sentences: %.3f", worth(test, oov2, 11, show = true)))
}

/** How a simulated thumb glides: how far from each letter's middle it aims, how much it drifts, how much of each corner it cuts. */
class Glide(val name: String, val aim: Float, val shake: Float, val cut: Float)

val GLIDES = listOf(Glide("neat", 0.12f, 0.15f, 0.30f), Glide("normal", 0.20f, 0.30f, 0.40f), Glide("sloppy", 0.30f, 0.45f, 0.50f))

fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = kotlin.math.hypot(a.first - b.first, a.second - b.second)

/**
 * A thumb's stroke through [word]'s letters with [g]'s faults: each letter aimed a little off its key's middle, each
 * turn rounded, a slow drift over the whole stroke and a hair of jitter; and when each point was made, the finger
 * slowing into each letter it means and running between them.
 */
fun stroke(word: String, g: Glide, rnd: Random): Pair<List<Pair<Float, Float>>, List<Long>> {
    val w = Geo.W
    val letters = word.filter { it in 'a'..'z' }
    var pts = ArrayList<Pair<Float, Float>>()
    var prev = ' '
    for (c in letters) { if (c != prev) { val k = Geo.centres.getValue(c); pts += (k.first + rnd.nextGaussian().toFloat() * g.aim * w) to (k.second + rnd.nextGaussian().toFloat() * g.aim * w) }; prev = c }
    if (pts.size == 1) pts += pts[0].first + 0.9f * w to pts[0].second
    val s = ArrayList<Pair<Float, Float>>()
    s += pts.first()
    for (i in 1 until pts.size - 1) {
        val a = pts[i - 1]; val v = pts[i]; val b = pts[i + 1]
        fun toward(q: Pair<Float, Float>): Pair<Float, Float> {
            val d = dist(v, q).coerceAtLeast(1f); val k = minOf(g.cut * w, d / 2.5f) / d
            return (v.first + (q.first - v.first) * k) to (v.second + (q.second - v.second) * k)
        }
        val inn = toward(a); val out = toward(b)
        for (j in 0..6) {
            val t = j / 6f; val u = 1 - t
            s += (u * u * inn.first + 2 * u * t * v.first + t * t * out.first) to (u * u * inn.second + 2 * u * t * v.second + t * t * out.second)
        }
    }
    s += pts.last()
    val step = 0.1f * w
    var total = 0f
    for (i in 1 until s.size) total += dist(s[i - 1], s[i])
    val dense = com.infamousvague.devkeyboard.SwipeDecoder.resample(s, (total / step).toInt().coerceAtLeast(10))
    val ph = FloatArray(4) { rnd.nextFloat() * 6.283f }
    val path = dense.mapIndexed { i, (x, y) ->
        val d = i * 0.1f
        val wx = (0.6f * kotlin.math.sin(d * 1.3f + ph[0]) + 0.4f * kotlin.math.sin(d * 0.45f + ph[1])) * g.shake / 2 * w
        val wy = (0.6f * kotlin.math.sin(d * 1.1f + ph[2]) + 0.4f * kotlin.math.sin(d * 0.5f + ph[3])) * g.shake / 2 * w
        (x + wx + (rnd.nextFloat() - 0.5f) * 0.04f * w) to (y + wy + (rnd.nextFloat() - 0.5f) * 0.04f * w)
    }
    // Time: slow within a third of a key of a letter that was aimed at, quick between (about 0.9 and 4 key widths a tenth of a second).
    val times = ArrayList<Long>(path.size)
    var t = 0.0
    for (i in path.indices) {
        if (i > 0) {
            val near = pts.minOf { dist(it, dense[i]) } / w
            val speed = 0.9 + 3.1 * (1.0 - kotlin.math.exp(-(near * near) / (2 * 0.3 * 0.3)))
            t += dist(path[i - 1], path[i]) / w / speed * 100.0
        }
        times += t.toLong()
    }
    return path to times
}

/** What a set of the swipe decoder's weights is worth: single words' first readings right, and a quarter of phrases', over three glides. */
fun swipeWorth(kb: Keyboard, sentences: List<List<String>>, phrases: List<Pair<String, List<String>>>, seed: Long, show: Boolean = false): Double {
    var total = 0.0
    for (g in GLIDES) {
        val rnd = Random(seed)
        var asked = 0; var top1 = 0
        for (sentence in sentences) {
            var before = "^"
            val text = StringBuilder()
            for (word in sentence) {
                if (word.filter { it in 'a'..'z' }.toSet().size >= 2) {
                    val (path, times) = stroke(word, g, rnd)
                    asked++
                    if (kb.swipe(path, times, before, text.toString()).firstOrNull()?.lowercase() == word) top1++
                }
                before = word; text.append(word).append(' ')
            }
        }
        var right = 0
        for ((before, run) in phrases) {
            val (path, times) = stroke(run.joinToString(""), g, rnd)
            if (kb.swipe(path, times, before).firstOrNull()?.lowercase() == run.joinToString(" ")) right++
        }
        if (show) println(String.format("  %-8s words %5.2f%%  phrases %5.1f%%", g.name, 100.0 * top1 / asked, 100.0 * right / phrases.size.coerceAtLeast(1)))
        total += 100.0 * top1 / asked + 25.0 * right / phrases.size.coerceAtLeast(1)
    }
    return total / GLIDES.size
}

fun phrasesOf(kb: Keyboard, sentences: List<List<String>>, most: Int): List<Pair<String, List<String>>> {
    fun share(a: String, b: String): Float { var s = 0f; kb.table.followers(a) { _, lower, sh -> if (lower == b) s = sh }; return s }
    val phrases = ArrayList<Pair<String, List<String>>>()
    for (sentence in sentences) {
        var i = 0
        while (i + 1 < sentence.size && phrases.size < most) {
            val n = if (i + 2 < sentence.size && phrases.size % 3 == 0) 3 else 2
            val run = sentence.subList(i, i + n)
            if (run.all { w -> w.filter { it in 'a'..'z' }.isNotEmpty() && kb.ac.isWord(w) } && (0 until n - 1).all { share(run[it], run[it + 1]) >= 0.03f }) {
                phrases += (if (i == 0) "^" else sentence[i - 1]) to run.toList()
                i += n
            } else i++
        }
    }
    return phrases
}

fun tuneSwipe(root: File) {
    val kb = Keyboard(root)
    val all = Sentences(root)
    fun take(from: Int, limit: Int): List<List<String>> {
        val out = ArrayList<List<String>>(); var n = 0
        for (line in all.lines.drop(from)) { if (n >= limit) break; val w = all.words(line); if (w.isEmpty()) continue; out += w; n += w.size }
        return out
    }
    val train = take(20_000, System.getProperty("words", "2500").toInt())
    val trainPhrases = phrasesOf(kb, take(24_000, 6000), 200)
    val test = take(60_000, 6000)
    val testPhrases = phrasesOf(kb, test, 400)
    val cls = com.infamousvague.devkeyboard.SwipeDecoder::class.java
    fun get(name: String) = cls.getField(name).getFloat(null)
    fun set(name: String, v: Float) = cls.getField(name).setFloat(null, v)
    val grid = linkedMapOf(
        "SLACK" to listOf(0f, 0.15f, 0.2f, 0.25f, 0.3f),
        "SLACK_LEAST" to listOf(0.35f, 0.5f, 0.65f),
        "LM" to listOf(0.45f, 0.55f, 0.7f, 0.85f),
        "DIST" to listOf(4.5f, 6f, 7.5f),
        "PIN" to listOf(1.2f, 2f, 2.8f),
        "OFF" to listOf(1.2f, 2f, 2.8f),
        "PAIRS" to listOf(1f, 2f, 4f, 8f),
        "JOIN" to listOf(0f, 0.25f, 0.5f, 0.75f),
        "UNSEEN" to listOf(0f, 0.5f, 1f, 1.5f, 2f),
        "SHORT_WORD" to listOf(4f, 4.5f, 5f, 5.5f),
        "THREE_LETTER" to listOf(3f, 3.4f, 3.8f, 4.2f),
        "ONE_WORD_FIT" to listOf(0.25f, 0.3f, 0.36f, 0.42f),
        "STRAIGHT" to listOf(0.25f, 0.5f, 0.8f),
    )
    var best = swipeWorth(kb, train, trainPhrases, 7)
    println(String.format("swipe tuning on %d words and %d phrases: starts at %.3f", train.sumOf { it.size }, trainPhrases.size, best))
    println("unseen, before:"); swipeWorth(kb, test, testPhrases, 11, show = true)
    repeat(System.getProperty("rounds", "2").toInt()) { round ->
        for ((name, values) in grid) {
            val was = get(name)
            var keep = was
            for (v in values) {
                if (v == was) continue
                set(name, v)
                val w = swipeWorth(kb, train, trainPhrases, 7)
                if (w > best + 0.02) { best = w; keep = v }
            }
            set(name, keep)
            println(String.format("  round %d  %-14s %5.2f → %5.2f   %.3f", round + 1, name, was, keep, best))
        }
    }
    println("set: " + grid.keys.joinToString(",") { "$it=${get(it)}" })
    println("unseen, after:"); swipeWorth(kb, test, testPhrases, 11, show = true)
}

fun swipes(root: File) {
    val kb = Keyboard(root)
    if (System.getProperty("old") == "true") {
        // The decoder as it was before 0.11.0: five words kept, any scrap a word of a phrase, no pair unseen, the one-word rows for context.
        com.infamousvague.devkeyboard.SwipeDecoder.WHOLE_KEEP = 5
        com.infamousvague.devkeyboard.SwipeDecoder.SHORT_WORD = 0f
        com.infamousvague.devkeyboard.SwipeDecoder.THREE_LETTER = 0f
        com.infamousvague.devkeyboard.SwipeDecoder.UNSEEN = 0f
        com.infamousvague.devkeyboard.SwipeDecoder.ORDERED = false
        com.infamousvague.devkeyboard.SwipeDecoder.MAX_SAMPLES = 48
        com.infamousvague.devkeyboard.SwipeDecoder.SHORT_ALONE = 0f
        com.infamousvague.devkeyboard.SwipeDecoder.TURNBACK = 0f
        com.infamousvague.devkeyboard.SwipeDecoder.OVERRUN = 0f
        kb.swipeContext = false
    }
    System.getProperty("swipeset")?.split(',')?.forEach { val (k, v) = it.split('='); com.infamousvague.devkeyboard.SwipeDecoder::class.java.getField(k).let { f -> when (f.type) { Int::class.javaPrimitiveType -> f.setInt(null, v.toInt()); Boolean::class.javaPrimitiveType -> f.setBoolean(null, v.toBoolean()); else -> f.setFloat(null, v.toFloat()) } } }
    val all = Sentences(root)
    val limit = System.getProperty("words", "6000").toInt()
    val seed = System.getProperty("seed", "7").toLong()
    val verbose = System.getProperty("verbose") == "true"
    val sentences = ArrayList<List<String>>()
    var n = 0
    for (line in all.lines.drop(60_000)) { if (n >= limit) break; val w = all.words(line); if (w.isEmpty()) continue; sentences += w; n += w.size }
    println("swipe typing: $n words in ${sentences.size} held-out sentences, each word one stroke")
    for (g in GLIDES) {
        val rnd = Random(seed)
        var asked = 0; var top1 = 0; var top3 = 0; var none = 0; var unlisted = 0; var nanos = 0L; var slowest = 0L
        val misses = ArrayList<String>()
        for (sentence in sentences) {
            var before = "^"
            val typedSoFar = StringBuilder()
            for (word in sentence) {
                val letters = word.filter { it in 'a'..'z' }
                // A word of one letter, or of one letter doubled, has no stroke to draw.
                if (letters.toSet().size >= 2) {
                    val (path, times) = stroke(word, g, rnd)
                    asked++
                    if (!kb.ac.isWord(word)) unlisted++
                    val t0 = System.nanoTime()
                    val got = kb.swipe(path, times, before, typedSoFar.toString())
                    val took = System.nanoTime() - t0
                    nanos += took; if (took > slowest) slowest = took
                    if (got.isEmpty()) none++
                    if (got.firstOrNull()?.lowercase() == word) top1++ else if (misses.size < 60) misses += "$word (after $before): ${got.take(3)}"
                    if (got.take(3).any { it.lowercase() == word }) top3++
                }
                before = word
                typedSoFar.append(word).append(' ')
            }
        }
        println(String.format("%-8s first reading right %5.2f%%  in the first three %5.2f%%  nothing read %d  (no list has %.1f%% of the words; %.1f ms a stroke, slowest %.0f)",
            g.name, 100.0 * top1 / asked, 100.0 * top3 / asked, none, 100.0 * unlisted / asked, nanos / 1e6 / asked, slowest / 1e6))
        if (verbose) misses.forEach { println("    $it") }
    }
    // Phrases in one stroke: runs of two and three words that the starter tables know as pairs (what people glide
    // without lifting: "thank you", "how are you"), each drawn as one stroke.
    fun share(a: String, b: String): Float { var s = 0f; kb.table.followers(a) { _, lower, sh -> if (lower == b) s = sh }; return s }
    val phrases = ArrayList<Pair<String, List<String>>>()
    for (sentence in sentences) {
        var i = 0
        while (i + 1 < sentence.size && phrases.size < 400) {
            val n = if (i + 2 < sentence.size && phrases.size % 3 == 0) 3 else 2
            val run = sentence.subList(i, i + n)
            if (run.all { w -> w.filter { it in 'a'..'z' }.isNotEmpty() && kb.ac.isWord(w) } && (0 until n - 1).all { share(run[it], run[it + 1]) >= 0.03f }) {
                phrases += (if (i == 0) "^" else sentence[i - 1]) to run.toList()
                i += n
            } else i++
        }
    }
    for (g in GLIDES) {
        val rnd = Random(seed)
        var right = 0; var top3 = 0
        val misses = ArrayList<String>()
        for ((before, run) in phrases) {
            val text = run.joinToString(" ")
            val (path, times) = stroke(run.joinToString(""), g, rnd)
            val got = kb.swipe(path, times, before).map { it.lowercase() }
            if (got.firstOrNull() == text) right++ else if (misses.size < 40) misses += "$text: ${got.take(3)}"
            if (text in got.take(3)) top3++
        }
        println(String.format("%-8s phrases in one stroke: first reading right %5.1f%%  in the first three %5.1f%%  (%d phrases)", g.name, 100.0 * right / phrases.size.coerceAtLeast(1), 100.0 * top3 / phrases.size.coerceAtLeast(1), phrases.size))
        if (verbose) misses.forEach { println("    $it") }
    }
}

/**
 * The row above the keys while a word is typed with slips: each word of [sentences] is tapped a letter at a time,
 * and after each tap the word is taken from the row if it is one of the three showing (a word typed so far that is
 * no word takes one of the three places, in quotes). What comes out: the share of key presses saved, how many words
 * came from the row, and how many were wrong after the space.
 */
fun rowUnderNoise(kb: Keyboard, sentences: List<List<String>>, noise: Noise, seed: Long, useTaps: Boolean): String {
    val rnd = Random(seed)
    fun gauss(s: Float) = (rnd.nextGaussian() * s * (if (rnd.nextFloat() < noise.wild) 2.2 else 1.0)).toFloat()
    var keysPlain = 0L; var keysUsed = 0L; var words = 0; var fromRow = 0; var wrong = 0
    for (sentence in sentences) {
        val before = StringBuilder()
        for (word in sentence) {
            val keys = kb.contractions[word] ?: word
            val text = StringBuilder(); val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
            var used = -1
            for (i in keys.indices) {
                val c = keys[i]
                if (c == '\'') { text.append(c); xs += Float.NaN; ys += Float.NaN } else {
                    val (cx, cy) = Geo.centres.getValue(c)
                    val x = cx + gauss(noise.sx) * Geo.W; val y = (cy + gauss(noise.sy) * Geo.H).coerceAtMost(Geo.SPACE_TOP - 1f)
                    text.append(Geo.hit(x, y)); xs += x; ys += y
                }
                if (i == keys.length - 1) break
                val taps = if (useTaps) Taps(xs.toFloatArray(), ys.toFloatArray(), Geo.centres, Geo.W, SPACE) else null
                val s = kb.ac.suggest(text.toString(), kb.scorer(before.toString() + text), taps)
                val showing = s.words.take(if (s.typedIsWord) 3 else 2).map { it.lowercase() }
                if (word in showing) { used = i + 2; break }
            }
            words++
            keysPlain += keys.length + 1
            if (used > 0) { fromRow++; keysUsed += used } else {
                keysUsed += keys.length + 1
                val taps = if (useTaps) Taps(xs.toFloatArray(), ys.toFloatArray(), Geo.centres, Geo.W, SPACE) else null
                val out = kb.ac.correct(text.toString(), kb.scorer(before.toString() + text), taps) ?: text.toString()
                if (out.lowercase() != word) wrong++
            }
            before.append(word).append(' ')
        }
    }
    return String.format("key presses saved %5.2f%%  words taken from the row %5.2f%%  wrong after the space %5.2f%%", 100.0 * (keysPlain - keysUsed) / keysPlain, 100.0 * fromRow / words, 100.0 * wrong / words)
}

fun row(root: File) {
    val kb = Keyboard(root)
    val all = Sentences(root)
    val (sentences, _) = sample(kb, all, 80_000, System.getProperty("words", "8000").toInt(), 5)
    val plain = sentences.map { s -> s.filter { kb.ac.isWord(it) } }.filter { it.isNotEmpty() }
    println("the row while typing with slips: ${plain.sumOf { it.size }} words")
    for (noise in NOISES) {
        println(String.format("%-8s by neighbours  %s", noise.name, rowUnderNoise(kb, plain, noise, 5, false)))
        println(String.format("%-8s by the taps    %s", noise.name, rowUnderNoise(kb, plain, noise, 5, true)))
    }
}

/**
 * Next-word prediction over whole sentences as they were written (capitals, names, numbers and all): at each word,
 * what the row offered before a letter of it was typed. Unlike tool/next_word_eval.sh, which asks only where the
 * word before is one the tables know, this asks everywhere, so it shows what is offered after a name or a word no
 * list has.
 */
fun next(root: File) {
    val kb = Keyboard(root)
    val all = Sentences(root)
    val limit = System.getProperty("words", "30000").toInt()
    val word = Regex("[\\p{L}][\\p{L}']*")
    for (backoff in listOf(false, true)) {
        Scorer.BACKOFF = backoff
        var asked = 0; var offered = 0; var hit1 = 0; var hit3 = 0
        var after = 0; var afterHit = 0; var named = 0; var namedHit = 0
        for (raw in all.lines.drop(30_000)) {
            if (asked >= limit) break
            val line = raw.replace('’', '\'')
            for (m in word.findAll(line)) {
                val before = line.substring(0, m.range.first)
                val target = m.value.trim('\'').lowercase()
                if (target.isEmpty()) continue
                asked++
                val place = NextWordText.place(before, kb::isWordChar, false) ?: continue
                val top = kb.scorer(before)!!.top(3).map { it.lowercase() }
                if (top.isEmpty()) continue
                offered++
                if (top[0] == target) hit1++
                if (target in top) hit3++
                // Where nothing was known of the word before: is what is offered there any use?
                val known = kb.table.range(place.prev).let { !it.isEmpty() }
                if (!known) { after++; if (target in top) afterHit++; if (place.named) { named++; if (target in top) namedHit++ } }
            }
        }
        println(String.format("%-28s words offered at %5.1f%% of places  first right %5.2f%%  in the three %5.2f%%  (of all %d places)   after a word no table has: %d places, right %4.1f%%; after a name: %d, right %4.1f%%",
            if (backoff) "with the fallbacks" else "as it was (0.10.4)", 100.0 * offered / asked, 100.0 * hit1 / asked, 100.0 * hit3 / asked, asked, after, 100.0 * afterHit / after.coerceAtLeast(1), named, 100.0 * namedHit / named.coerceAtLeast(1)))
    }
    Scorer.BACKOFF = true
}

/**
 * Learning from mistakes (MistakeMemory): a person with habits. Sixty words they get wrong the same way more often
 * than not (two letters swapped, a neighbouring key, a letter dropped or doubled, each typed on purpose with the
 * thumb's usual scatter on top) type one stretch of sentences, putting right most of what the keyboard leaves wrong
 * or changes wrongly; what that taught is then scored on a second stretch, beside the same keyboard with no memory.
 * -Dfixes=0.7 is how often they bother to put a word right; -Dhabit=0.6 how often a habit shows.
 */
fun mistakes(root: File) {
    val kb = Keyboard(root)
    val all = Sentences(root)
    val seed = System.getProperty("seed", "7").toLong()
    val limit = System.getProperty("words", "30000").toInt()
    val bothers = System.getProperty("fixes", "0.7").toFloat()
    val shows = System.getProperty("habit", "0.6").toFloat()
    val (first, _) = sample(kb, all, 0, limit, seed)
    val (second, oov) = sample(kb, all, 45_000, limit, seed + 1)
    // The habits: the commonest words of four letters or more in what will be scored, each with one wrong spelling.
    val counts = HashMap<String, Int>()
    for (sn in second) for (w in sn) if (w.length >= 4 && w.all { it in 'a'..'z' }) counts[w] = (counts[w] ?: 0) + 1
    val pick = Random(seed)
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    fun beside(c: Char): Char { val row = rows.first { c in it }; val i = row.indexOf(c); return if (i + 1 < row.length && (i == 0 || pick.nextBoolean())) row[i + 1] else row[i - 1] }
    val habits = HashMap<String, String>()
    var realWords = 0
    for ((w, _) in counts.entries.sortedByDescending { it.value }.drop(20)) {
        if (habits.size >= 60) break
        val i = 1 + pick.nextInt(w.length - 1)
        val wrong = when (pick.nextInt(4)) {
            0 -> if (i + 1 < w.length && w[i] != w[i + 1]) w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) else continue
            1 -> w.substring(0, i) + beside(w[i]) + w.substring(i + 1)
            2 -> w.substring(0, i) + w.substring(i + 1)
            else -> w.substring(0, i) + w[i] + w.substring(i)
        }
        if (wrong == w || wrong.length < 3) continue
        if (kb.ac.isWord(wrong)) { if (realWords >= 10) continue; realWords++ }
        habits[w] = wrong
    }
    println("learning from mistakes: ${habits.size} habits ($realWords of them real words), ${first.sumOf { it.size }} words typed to learn from, ${second.sumOf { it.size }} scored; a word is put right ${(bothers * 100).toInt()}% of the time")
    if (System.getProperty("verbose") == "true") println("    " + habits.entries.joinToString("  ") { "${it.key}>${it.value}" })
    val noise = NOISES[1]
    val memory = MistakeMemory()
    val kept = HashSet<String>()
    val who = Random(seed + 5)
    var last: String? = null
    val habit: (String) -> String? = { w -> habits[w]?.takeIf { who.nextFloat() < shows }.also { last = it } }
    fun taps(t: Typed) = Taps(t.xs, t.ys, Geo.centres, Geo.W, SPACE)
    // The first stretch: typed, and what is wrong put right most of the time, as a person does.
    val person = Random(seed + 9)
    typeAll(kb, first, noise, seed, oov, habit, after = { u ->
        if (u.targets.size == 1 && u.typed.size == 1) {
            val typed = u.typed[0]; val out = u.out[0].lowercase(); val meant = u.targets[0]
            when {
                out == meant && typed != meant -> memory.learn(typed, out, 0.5f)
                out == meant -> memory.kept(typed, 0.5f)
                person.nextFloat() >= bothers -> { if (out != typed) memory.learn(typed, out, 0.5f) else memory.kept(typed, 0.5f) }
                // A right word changed: the fix is undone, and the word kept.
                typed == meant -> { memory.forget(typed, out); kept += typed; kb.ac.setLearned(kept) }
                // Left wrong, or mended to the wrong word: typed again, right.
                else -> { if (out != typed) memory.forget(typed, out); if (MistakeMemory.alike(typed, meant)) memory.learn(typed, meant) }
            }
            kb.ac.setMistakes(memory.settled())
        }
    }) { t, before -> kb.ac.correct(t.text, kb.scorer(before), taps(t)) }
    println("    remembered after the first stretch: ${memory.typedPairs} words, ${memory.settled().size} of them settled; ${kept.size} words kept")
    // The second stretch, with what was learned and without: the same thumb, the same habits showing.
    for (learned in listOf(false, true)) {
        kb.ac.setMistakes(if (learned) memory.settled() else emptyMap())
        kb.ac.setLearned(if (learned) kept else emptySet())
        val shown = Random(seed + 11)
        var made = 0; var mended = 0; var madeReal = 0; var mendedReal = 0
        var flag: String? = null
        val tally = typeAll(kb, second, noise, seed + 1, oov, { w -> habits[w]?.takeIf { shown.nextFloat() < shows }.also { flag = it } }, after = { u ->
            val h = flag
            if (h != null && u.targets.size == 1 && u.typed.size == 1) {
                val real = kb.ac.isListed(h)
                made++; if (real) madeReal++
                if (u.right) { mended++; if (real) mendedReal++ }
            }
            flag = null
        }) { t, before -> kb.ac.correct(t.text, kb.scorer(before), taps(t)) }
        println(tally.line(if (learned) "with what was learned" else "with no memory"))
        if (System.getProperty("spoils") == "true") println("    " + tally.examples.filter { it.startsWith("spoil") }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.joinToString("  ") { "${it.key.removePrefix("spoil").trim()} ×${it.value}" })
        println(String.format("        habits mended %5.1f%% of %d  (the %d that are real words: %4.1f%%)", 100.0 * mended / made.coerceAtLeast(1), made, madeReal, 100.0 * mendedReal / madeReal.coerceAtLeast(1)))
    }
    kb.ac.setMistakes(emptyMap()); kb.ac.setLearned(emptySet())
}

/**
 * Prediction in a person's own voice (Mockingbird's samples, NextWords.readMockingbird): for each of a few of
 * Tatoeba's contributors, 1,500 of their held-out sentences stand for what Mockingbird has kept of them, and the
 * next words of their other held-out sentences are predicted with those and without. The starter table has seen
 * the rest of each contributor's sentences already, so this understates what a stranger's own samples add.
 * Needs build/typing-bench/en-authors.tsv (tool/typing_bench.sh cuts it).
 */
fun voice(root: File) {
    val kb = Keyboard(root)
    val file = File(root, "build/typing-bench/en-authors.tsv")
    if (!file.exists()) { println("voice: no ${file.path}"); return }
    val by = LinkedHashMap<String, ArrayList<String>>()
    file.forEachLine { l -> val t = l.indexOf('\t'); if (t > 0) by.getOrPut(l.substring(0, t)) { ArrayList() } += l.substring(t + 1) }
    val word = Regex("[\\p{L}][\\p{L}']*")
    val skip = System.getProperty("skip", "CK").split(',').toSet()
    System.getProperty("prior1")?.let { Scorer.PRIOR_ONE = it.toFloat() }
    System.getProperty("prior2")?.let { Scorer.PRIOR_TWO = it.toFloat() }
    var sumBase = 0.0; var sumNear = 0.0; var sumFar = 0.0; var people = 0
    for ((name, lines) in by) {
        if (name in skip || lines.size < 2500) continue
        // -Dsplit=mixed deals the sentences out in turn, so the samples are of the same days as what is predicted;
        // the default takes the 1,500 written before it.
        val mixed = System.getProperty("split") == "mixed"
        val samples = if (mixed) lines.take(3000).filterIndexed { i, _ -> i % 2 == 0 } else lines.take(1500)
        val test = if (mixed) lines.take(3000).filterIndexed { i, _ -> i % 2 == 1 } else lines.drop(1500).take(1500)
        val heard = PersonalPairs.of(samples)
        fun score(by: Float): Triple<Double, Double, Int> {
            var asked = 0; var hit1 = 0; var hit3 = 0
            for (raw in test) {
                val line = raw.replace('’', '\'')
                for (m in word.findAll(line)) {
                    val before = line.substring(0, m.range.first)
                    val target = m.value.trim('\'').lowercase()
                    if (target.isEmpty()) continue
                    asked++
                    val place = NextWordText.place(before, kb::isWordChar, false) ?: continue
                    val one = if (by > 0f) PersonalPairs.blended(emptyList(), heard.row(place.prev), by, null, 0f) else emptyList()
                    val key = place.prev2?.takeIf { place.prev != NextWordText.START }?.let { "$it ${place.prev}" }
                    val two = if (by > 0f && key != null) PersonalPairs.blended(emptyList(), heard.row(key), by, null, 0f) else emptyList()
                    val lookup = object : Scorer.Lookup {
                        override fun isWord(lower: String) = kb.ac.isWord(lower)
                        override fun form(lower: String) = if (by > 0f) heard.form(lower) else lower
                        override fun count(lower: String) = by * heard.unigram(lower)
                        override fun starting(prefix: String) = emptyList<Pair<String, Float>>()
                        override fun fills() = kb.ac.commonest(6)
                    }
                    val top = Scorer(place, listOf(kb.table), Scorer.weights(listOf(kb.table), place.recent), one, two, lookup).top(3).map { it.lowercase() }
                    if (top.firstOrNull() == target) hit1++
                    if (target in top) hit3++
                }
            }
            return Triple(100.0 * hit1 / asked.coerceAtLeast(1), 100.0 * hit3 / asked.coerceAtLeast(1), asked)
        }
        val weights = System.getProperty("weights", "0.1,0.25").split(',').map { it.toFloat() }
        val base = score(0f); val far = score(weights[0]); val near = score(weights[1])
        println(String.format("%-12s %6d places  in the three: starter table %5.2f%%   + their samples, the other kind of app %5.2f%%   + their samples, this kind %5.2f%%   (first right %5.2f%% → %5.2f%%)",
            name, base.third, base.second, far.second, near.second, base.first, near.first))
        sumBase += base.second; sumFar += far.second; sumNear += near.second; people++
    }
    if (people > 0) println(String.format("%-12s in the three: %5.2f%% → %5.2f%% (other kind) → %5.2f%% (this kind), over %d people", "mean", sumBase / people, sumFar / people, sumNear / people, people))
}

/**
 * Words that end in a there-and-back (there, here, were: the "re" runs back along the line just drawn) beside the
 * shorter words their strokes pass for (the, he, we), each drawn forty times in each kind of glide after a few
 * different words: how often the first reading is the word drawn.
 */
fun turns(root: File) {
    val kb = Keyboard(root)
    System.getProperty("swipeset")?.split(',')?.forEach { val (k, v) = it.split('='); com.infamousvague.devkeyboard.SwipeDecoder::class.java.getField(k).let { f -> when (f.type) { Int::class.javaPrimitiveType -> f.setInt(null, v.toInt()); Boolean::class.javaPrimitiveType -> f.setBoolean(null, v.toBoolean()); else -> f.setFloat(null, v.toFloat()) } } }
    val long = listOf("there", "here", "where", "were", "these", "three", "never", "ever", "even", "level", "data", "area", "idea", "banana", "letter", "pretty")
    val short = listOf("the", "he", "we", "her", "see", "been", "are", "is", "it", "to", "of", "and", "they", "then", "them", "this")
    val befores = listOf("^", "and", "is", "over", "we")
    for ((name, words) in listOf("with a there-and-back" to long, "plain" to short)) {
        val line = StringBuilder(String.format("%-22s", name))
        for (g in GLIDES) {
            val rnd = Random(11)
            var asked = 0; var right = 0
            val wrong = HashMap<String, Int>()
            for (w in words) repeat(40) { k ->
                val before = befores[k % befores.size]
                val (path, times) = stroke(w, g, rnd)
                asked++
                val got = kb.swipe(path, times, before, if (before == "^") "" else "$before ").firstOrNull()?.lowercase()
                if (got == w) right++ else wrong["$w→$got"] = (wrong["$w→$got"] ?: 0) + 1
            }
            line.append(String.format("  %s %5.1f%%", g.name, 100.0 * right / asked))
            if (System.getProperty("verbose") == "true") println("    ${g.name}: " + wrong.entries.sortedByDescending { it.value }.take(14).joinToString("  ") { "${it.key} ×${it.value}" })
        }
        println(line)
    }
}

fun main(args: Array<String>) {
    val root = File(args.getOrNull(0) ?: ".")
    System.getProperty("set")?.split(',')?.forEach { val (k, v) = it.split('='); setCost(k, v.toFloat()) }
    when (args.getOrNull(1) ?: "all") {
        "taps" -> taps(root)
        "tune" -> tune(root)
        "swipe" -> swipes(root)
        "tuneswipe" -> tuneSwipe(root)
        "row" -> row(root)
        "next" -> next(root)
        "mistakes" -> mistakes(root)
        "voice" -> voice(root)
        "turns" -> turns(root)
        else -> { taps(root); swipes(root) }
    }
}
