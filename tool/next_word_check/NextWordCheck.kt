// Next-word prediction's self-check (tool/next_word_check.sh). Plain Kotlin on the JVM: it compiles the keyboard's own
// NextWordModel.kt and Autocorrect.kt (neither needs Android) and tries them on the fixtures the generator shares, on
// made-up counts, and on the real word lists and starter tables in the app's assets.
//
// Usage: NextWordCheck <repo root>. Exits 1 if any check fails, having printed each.

import com.infamousvague.devkeyboard.Autocorrect
import com.infamousvague.devkeyboard.LanguageState
import com.infamousvague.devkeyboard.Languages
import com.infamousvague.devkeyboard.NextWordLearner
import com.infamousvague.devkeyboard.NextWordText
import com.infamousvague.devkeyboard.Words
import com.infamousvague.devkeyboard.NgramTable
import com.infamousvague.devkeyboard.PersonalPairs
import com.infamousvague.devkeyboard.Scorer
import com.infamousvague.devkeyboard.Script
import java.io.File
import java.util.Locale

private var checks = 0
private var failures = 0

private fun check(ok: Boolean, what: () -> String) {
    checks++
    if (!ok) { failures++; println("FAIL  ${what()}") }
}

private fun <T> same(got: T, want: T, what: String) = check(got == want) { "$what: got $got, wanted $want" }

/** A word character as the service has it (DevKeyboardService.isWordChar, which is Words.isWordChar): a letter, a mark on one, a joiner. */
private fun wordChar(joiners: String): (Char) -> Boolean = { c -> Words.isWordChar(c, joiners) }

/** The joiners, locale and sentence ends the keyboard has while [code] is typed alone. */
private class Lang(code: String) {
    val state = LanguageState.of(listOf(code), emptyMap(), "")
    val isWordChar = wordChar(state.joiners)
    val locale: Locale = state.locale
    val ends: String = if (state.spec.script == Script.GREEK) ";;" else ""
}

private fun unescape(s: String) = Regex("\\\\u([0-9a-fA-F]{4})").replace(s) { it.groupValues[1].toInt(16).toChar().toString() }.replace("\\n", "\n")

private fun fixtures(root: File) {
    println("the tokenizer, on the fixtures the generator shares")
    val langs = HashMap<String, Lang>()
    var n = 0
    File(root, "tool/next_words_fixtures.tsv").forEachLine { line ->
        if (line.isEmpty() || line.startsWith("#")) return@forEachLine
        val cols = line.split('\t')
        val l = langs.getOrPut(cols[0]) { Lang(cols[0]) }
        val full = cols[1] == "1"
        val text = unescape(cols[2])
        val place = NextWordText.place(text, l.isWordChar, full, l.locale, l.ends)
        same(place?.let { "${it.prev2 ?: "~"}|${it.prev}|${it.typing}" } ?: "-", unescape(cols[3]), "place of \"${cols[2]}\" (${cols[0]})")
        val done = NextWordText.lastFinished(text, l.isWordChar, full, l.locale, l.ends)
        same(done?.let { "${it.prev2 ?: "~"}|${it.prev}|${it.word}|${it.shown}" } ?: "-", unescape(cols[4]), "finished of \"${cols[2]}\" (${cols[0]})")
        n++
    }
    check(n >= 30) { "the fixture file has its cases ($n read)" }
    // The few last words say what language is being written.
    val en = Lang("en")
    same(NextWordText.place("one two three four five six ", en.isWordChar, false)?.recent, listOf("three", "four", "five", "six"), "the last four words")
    same(NextWordText.place("one two thr", en.isWordChar, false)?.recent, listOf("one", "two"), "the words before the one being typed")
    val p = NextWordText.place("see you tomorrow", en.isWordChar, false)!!
    val t = NextWordText.trailing(p)!!
    same("${t.prev2}|${t.prev}|${t.word}|${t.shown}", "see|you|tomorrow|tomorrow", "the word still being typed, as a sent message counts it")
    same(NextWordText.trailing(NextWordText.place("see you ", en.isWordChar, false)!!), null, "nothing trails after a space")
}

/** A lookup that knows a fixed set of words and one PersonalPairs, as NextWords gives a Scorer. */
private class Fake(val personal: PersonalPairs, val known: Set<String>, val common: List<String> = emptyList()) : Scorer.Lookup {
    override fun isWord(lower: String) = lower in known
    override fun form(lower: String) = personal.form(lower)
    override fun count(lower: String) = personal.unigram(lower)
    override fun starting(prefix: String) = personal.starting(prefix)
    override fun fills() = personal.top(3).map { personal.form(it) } + common
}

private fun table(vararg rows: String): NgramTable =
    NgramTable.parse((listOf("#ghost-ngram\t1\ten\ttest") + rows.map { it.replace(" > ", "\t") }).asSequence())!!

private val en = wordChar("'")

private fun scorer(text: String, tables: List<NgramTable>, personal: PersonalPairs, known: Set<String>, common: List<String> = emptyList()): Scorer {
    val place = NextWordText.place(text, en, false)!!
    return Scorer(place, tables, Scorer.weights(tables, place.recent), personal.followers(place.prev),
        place.prev2?.let { personal.followers("$it ${place.prev}") } ?: emptyList(), Fake(personal, known, common))
}

/** Types [text] into [personal] as the service would learn it: each word counted once the next thing is typed. */
private fun type(personal: PersonalPairs, text: String) {
    for (i in 1..text.length) {
        if (en(text[i - 1])) continue
        if (i >= 2 && !en(text[i - 2])) continue
        NextWordText.lastFinished(text.substring(0, i), en, false)?.let { personal.learn(it) }
    }
}

private fun blend() {
    println("the blend of starter tables and personal counts")
    val t = table("^ > I:1500 the:500 he:400", "thank > you:7800 God:400 me:270", "on > the:5000 a:900 my:500", "my > way:200 own:150")
    val known = setOf("thank", "you", "god", "me", "on", "the", "a", "my", "way", "own", "i", "he", "see", "it", "is", "going")
    val p = PersonalPairs()
    same(scorer("thank ", listOf(t), p, known).top(3), listOf("you", "God", "me"), "the starter table alone, likeliest first, as cased")
    same(scorer("", listOf(t), p, known).top(3), listOf("I", "the", "he"), "a field's start")
    same(scorer("zebra ", listOf(t), p, known).top(3), emptyList<String>(), "a context nothing is known of offers nothing")
    same(scorer("thank ", listOf(t), p, known).share("zebra"), 0f, "a word the context says nothing of has no share")
    same(scorer("thank ", listOf(t), p, known).boost("zebra", 3f), 0f, "and no boost")
    check(scorer("thank ", listOf(t), p, known).boost("you", 6.5f) > 0.5f) { "a likely next word is boosted" }

    // A pair typed once is remembered and not shown; typed twice it shows.
    type(p, "see it ")
    same(scorer("see ", listOf(t), p, known).top(3), emptyList<String>(), "a pair typed once is not offered")
    same(p.count("see", "it"), 1f, "though it is counted")
    type(p, "see it ")
    same(scorer("see ", listOf(t), p, known).top(1), listOf("it"), "typed twice, it is offered")
    // A word no dictionary has needs three.
    val q = PersonalPairs()
    type(q, "see blorp ok see blorp ok ")
    check("blorp" !in scorer("see ", listOf(t), q, known).top(3)) { "a non-word typed twice is not offered" }
    type(q, "see blorp ok ")
    same(scorer("see ", listOf(t), q, known).top(1), listOf("blorp"), "typed three times, it is")
    // A bundled leader holds where personal use is thin, and gives way to a habit.
    val r = PersonalPairs()
    type(r, "on my way. on my way. ")
    same(scorer("go on ", listOf(t), r, known).top(1), listOf("the"), "two uses do not unseat a strong starter pair")
    same(scorer("on ", listOf(t), r, known).top(1), listOf("my"), "but where the two words before are the same as both times, they do")
    repeat(6) { type(r, "on my way. ") }
    same(scorer("go on ", listOf(t), r, known).top(1), listOf("my"), "eight uses do, whatever came before")
    // Two words of context beat one.
    val u = PersonalPairs()
    repeat(4) { type(u, "it is going. ") }
    repeat(3) { type(u, "he is you. ") }
    same(scorer("he is ", listOf(t), u, known).top(1), listOf("you"), "after two words, what followed those two")
    same(scorer("it is ", listOf(t), u, known).top(1), listOf("going"), "and the other pair's own")
    // Never the same word again, never twice, and filled up when thin.
    val v = PersonalPairs()
    repeat(3) { type(v, "the the the. ") }
    check("the" !in scorer("the ", listOf(t), v, known).top(3)) { "the word just typed is not offered again" }
    val w = PersonalPairs()
    type(w, "see it. see it. ")
    same(scorer("see ", listOf(t), w, known, listOf("the", "to", "and")).top(3), listOf("it", "the", "to"), "a thin row is filled with the commonest words")
    // Casing: the person's own mid-sentence form wins; a sentence start's capital is not evidence.
    val c = PersonalPairs()
    type(c, "see Blorp today. see Blorp today. see Blorp today. Blorp is. ")
    same(scorer("see ", listOf(t), c, known).top(1), listOf("Blorp"), "a word typed with a capital is shown with it")
    val d = PersonalPairs()
    type(d, "Going on. Going on. Going on. ")
    same(scorer("", listOf(t), d, known + "going").top(3).first(), "going", "a capital at a sentence start is not kept")
    // Languages: the table that knows the recent words leads.
    val de = NgramTable.parse(sequenceOf("#ghost-ngram\t1\tde\ttest", "das\tist:1000 Haus:900", "^\tich:1500"))!!
    val both = listOf(t, de)
    same(Scorer.weights(both, listOf("on", "my")).toList(), listOf(1f, 1f / 3f), "weights by the recent words")
    same(Scorer.weights(both, emptyList()).toList(), listOf(1f, 0.5f), "with nothing to tell by the first language leads")
    same(scorer("ist das ", both, PersonalPairs(), known).top(2), listOf("ist", "Haus"), "another language's context finds its own followers")
    same(NgramTable.parse(sequenceOf("nonsense")), null, "a file with no header is not a table")
    same(NgramTable.parse(sequenceOf("#ghost-ngram\t2\ten\tx", "a\tb:1")), null, "nor one of a newer version")
    val fr = NgramTable.parse(sequenceOf("#ghost-ngram\t1\tfr\ttest", "ai\tune:1200 pas:900"))!!
    val place = NextWordText.place("j'ai ", en, false)!!
    same(Scorer(place, listOf(fr), floatArrayOf(1f), emptyList(), emptyList(), Fake(PersonalPairs(), emptySet())).top(1), listOf("une"), "an elision finds its last part's row")
    same(Scorer.personalZipf(1f, true), 0f, "one use says nothing of how common a word is")
    same(Scorer.personalZipf(2f, true), 4.0f, "two uses of a dictionary word")
    same(Scorer.personalZipf(2f, false), 0f, "two of a non-word say nothing yet")
    same(Scorer.personalZipf(100_000f, true), 6.5f, "and it stops at 6.5")
}

private fun bounds() {
    println("fading, the bounds and the file")
    val p = PersonalPairs()
    repeat(4) { type(p, "on my way. ") }
    type(p, "see it ")
    p.events = PersonalPairs.DECAY_EVERY
    p.maintain()
    check(kotlin.math.abs(p.count("on", "my") - 4 * PersonalPairs.DECAY) < 1e-4) { "one fading multiplies a count by ${PersonalPairs.DECAY}: ${p.count("on", "my")}" }
    same(p.count("on my", "way") > 0f, true, "two-word contexts fade too, and stay")
    val before = p.entries
    p.events = PersonalPairs.DECAY_EVERY * 7
    p.maintain()
    check(p.count("see", "it") == 0f && p.entries < before) { "a pair typed once is dropped after a few fadings" }
    check(p.count("on", "my") > PersonalPairs.FLOOR) { "one typed four times is still there after seven" }
    p.maintain()
    check(kotlin.math.abs(p.count("on", "my") - 4 * Math.pow(PersonalPairs.DECAY.toDouble(), 7.0).toFloat()) < 1e-3) { "fading is not done twice for the same words" }

    val many = PersonalPairs()
    for (i in 0 until PersonalPairs.MAX_FOLLOWERS + 10) many.add("ctx", "w$i", n = 1f + i)
    same(many.followers("ctx").size, PersonalPairs.MAX_FOLLOWERS, "a context keeps at most ${PersonalPairs.MAX_FOLLOWERS} followers")
    check(many.count("ctx", "w0") == 0f && many.count("ctx", "w41") == 42f) { "and it is the least typed that go" }
    same(many.entries, PersonalPairs.MAX_FOLLOWERS, "the pair count follows")
    many.add("ctx", "x".repeat(25))
    same(many.entries, PersonalPairs.MAX_FOLLOWERS, "a word over ${NextWordText.MAX_WORD} characters is not kept")
    val big = PersonalPairs()
    for (i in 0 until PersonalPairs.MAX_ENTRIES + 500) big.add("c${i / 20}", "w${i % 20}", n = 1f + (i % 7))
    same(big.entries, PersonalPairs.MAX_ENTRIES + 500, "pairs pile up until the keyboard hides")
    big.maintain()
    check(big.entries <= PersonalPairs.MAX_ENTRIES * 9 / 10 && big.entries > PersonalPairs.MAX_ENTRIES * 8 / 10) { "then the least typed go, to nine tenths: ${big.entries}" }
    val text = StringBuilder().also { big.write(it) }
    println("  ${big.entries} pairs are ${text.length} characters as a file")
    check(text.length < 700_000) { "a full file is under 700 KB" }

    val c = PersonalPairs()
    type(c, "see you on Monday then. see you on Monday then. I am. ")
    c.events = 4321
    val written = StringBuilder().also { c.write(it) }.toString()
    val back = PersonalPairs.read(written.lineSequence())
    same(StringBuilder().also { back.write(it) }.toString(), written, "a file read and written again is the same file")
    same(back.events, 4321, "the count of words learned is kept")
    same(back.form("monday"), "Monday", "and so are the capitals")
    same(back.count("you on", "monday"), 2f, "and the two-word contexts")
    same(back.unigram("you"), 2f, "how often a word was typed is counted again from the pairs")
    same(back.entries, c.entries, "pair for pair")
    same(c.copy().let { k -> StringBuilder().also { k.write(it) }.toString() }, written, "a copy is the same")
    check(runCatching { PersonalPairs.read(sequenceOf("hello\tworld:1")) }.exceptionOrNull().let { it is PersonalPairs.Unreadable && !it.newer }) { "a file with a bad header is refused" }
    check(runCatching { PersonalPairs.read(sequenceOf("#ghost-next-words\t9\tevents=1")) }.exceptionOrNull().let { it is PersonalPairs.Unreadable && it.newer }) { "a newer version is refused as newer" }
    check(runCatching { PersonalPairs.read(emptySequence()) }.exceptionOrNull() is PersonalPairs.Unreadable) { "an empty file is refused" }
    same(PersonalPairs.read(sequenceOf("#ghost-next-words\t1\tevents=3", "a\tb:nonsense c:-4 d:2.5", "junk")).entries, 1, "a damaged line loses only itself")
    same(c.starting("mo"), listOf("monday" to 2f), "the person's words that begin with a prefix")
    same(c.top(3).toSet(), emptySet<String>(), "nothing is a most-typed word under three uses")
    type(c, "see you. ")
    same(c.copy().top(1), listOf("see"), "three uses are (the list is worked out afresh every 64 words; a copy starts afresh)")
}

/** Waits for [ac] to have read its lists. */
private fun settle(ac: Autocorrect) { while (!ac.settled) Thread.sleep(20); Thread.sleep(250) }

private fun bundled(root: File) {
    println("every starter table, against its word list")
    val dict = File(root, "android/app/src/main/assets/dict")
    val ngram = File(root, "android/app/src/main/assets/ngram")
    val files = ngram.listFiles { f -> f.name.endsWith(".tsv") }!!.sortedBy { it.name }
    check(files.any { it.name == "en.tsv" }) { "English has a starter table" }
    check(File(ngram, "NOTICE.txt").readText().contains("CC BY 2.0 FR")) { "the notice names Tatoeba's licence" }
    check(File(ngram, "CREDITS.txt").length() > 1000) { "the contributors are credited" }
    var total = 0L
    var pairs = 0
    for (f in files) {
        val code = f.name.removeSuffix(".tsv")
        val language = Languages.of(code)
        check(language?.dict == code) { "$code is a language with a word list" }
        if (language == null) continue
        val l = Lang(code)
        val words = File(dict, "$code.tsv").useLines { lines -> lines.map { it.substringBefore('\t') }.toHashSet() }
        fun listed(w: String) = w in words || ('\'' in w && w.split('\'').all { it in words })
        val t = f.useLines { NgramTable.parse(it, l.locale) }
        check(t != null && t.code == code) { "$code's table reads" }
        t ?: continue
        total += f.length()
        pairs += t.pairs
        var bad = 0
        var n = 0
        var first = ""
        f.useLines { lines ->
            lines.drop(1).forEach { line ->
                val key = line.substringBefore('\t')
                if (key != NextWordText.START && !listed(key)) bad++
                for (part in line.substringAfter('\t').split(' ')) {
                    val shown = part.substringBeforeLast(':')
                    val share = part.substringAfterLast(':').toIntOrNull()
                    if (share == null || share !in 1..10_000 || !listed(shown.lowercase(l.locale))) { bad++; if (first.isEmpty()) first = "$key → $part" }
                    n++
                }
            }
        }
        same(n, t.pairs, "$code: every pair was read")
        check(bad == 0) { "$code: $bad entries are not words of its list (the first: $first)" }
        check(t.pairs >= 5_000) { "$code has at least 5,000 pairs (${t.pairs})" }
        var start = 0
        t.followers(NextWordText.START) { _, _, _ -> start++ }
        check(start >= 3) { "$code can start a sentence ($start starters)" }
    }
    println("  ${files.size} tables, $pairs pairs, $total bytes")
    check(total < 12_000_000) { "the tables together are under 12 MB ($total)" }
}

private fun english(root: File) {
    println("English: context in suggestions and corrections, and how long a lookup takes")
    val assets = File(root, "android/app/src/main/assets")
    val state = LanguageState.of(listOf("en"), emptyMap(), "")
    val ac = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
    ac.setLanguages(state.dictionaries, state.board)
    settle(ac)
    val rt = Runtime.getRuntime()
    System.gc(); Thread.sleep(100)
    val heap0 = rt.totalMemory() - rt.freeMemory()
    val t0 = System.nanoTime()
    val table = File(assets, "ngram/en.tsv").useLines { NgramTable.parse(it) }!!
    val readMs = (System.nanoTime() - t0) / 1_000_000
    System.gc(); Thread.sleep(100)
    val heap1 = rt.totalMemory() - rt.freeMemory()
    println("  en.tsv: ${table.pairs} pairs after ${table.contexts} contexts, read in $readMs ms; about ${(heap1 - heap0) / 1024} KB of this JVM's heap (its own estimate: ${table.approxBytes / 1024} KB)")
    check(table.approxBytes < 6_000_000) { "the English table is under 6 MB in memory by its own estimate" }

    val personal = PersonalPairs()
    val lookup = object : Scorer.Lookup {
        override fun isWord(lower: String) = ac.isWord(lower)
        override fun form(lower: String) = personal.form(lower)
        override fun count(lower: String) = personal.unigram(lower)
        override fun starting(prefix: String) = personal.starting(prefix)
        override fun fills() = personal.top(3).map { personal.form(it) } + ac.commonest(6)
    }
    fun at(text: String): Scorer {
        val place = NextWordText.place(text, en, false)!!
        return Scorer(place, listOf(table), Scorer.weights(listOf(table), place.recent), personal.followers(place.prev),
            place.prev2?.let { personal.followers("$it ${place.prev}") } ?: emptyList(), lookup)
    }
    println("  how are → ${at("how are ").top(3)};  thank → ${at("thank ").top(3)};  a field's start → ${at("").top(3)};  I → ${at("I ").top(3)}")
    check("you" in at("thank ").top(3)) { "thank is followed by you" }
    check("you" in at("how are ").top(3)) { "how are is followed by you" }
    same(ac.commonest(3), listOf("the", "to", "and"), "English's commonest words")
    check(ac.isWord("don't") && ac.isWord("hello") && !ac.isWord("blorp")) { "isWord knows the lists and the contractions" }

    // With no scorer, and with one that knows nothing, autocorrect is what it was.
    val nothing = Scorer(NextWordText.Place(null, "zzzz", "", emptyList()), listOf(table), floatArrayOf(1f), emptyList(), emptyList(), lookup)
    for (w in listOf("teh", "wrold", "th", "yo", "hel", "keybkard", "becuase", "i", "dont", "caf", "mond", "happ", "tomorow", "qwzx")) {
        same(ac.correct(w, nothing), ac.correct(w), "correct($w) with a scorer that knows nothing")
        same(ac.suggest(w, nothing).words, ac.suggest(w).words, "suggest($w) with a scorer that knows nothing")
    }
    // Context ranks completions.
    val plainMo = ac.suggest("mo").words
    val afterGood = ac.suggest("mo", at("good mo")).words
    println("  mo → $plainMo;  good mo → $afterGood")
    check(afterGood.indexOf("morning") in 0..2 && plainMo.indexOf("morning").let { it < 0 || it > afterGood.indexOf("morning") }) { "after good, mo offers morning sooner: $afterGood (without context: $plainMo)" }
    val plainM = ac.suggest("m").words
    val afterHow = ac.suggest("m", at("how m")).words
    check(afterHow.indexOf("many") in 0..2 && afterHow.indexOf("much") in 0..2) { "after how, m offers many and much: $afterHow (without context: $plainM)" }
    check(ac.suggest("Mond", at("on Mond")).words.contains("Monday")) { "a completion keeps its capital" }
    // A common word is never changed by context, and a correction is never caused by it.
    for (w in listOf("the", "you", "are", "going", "form", "from")) same(ac.correct(w, at("thank $w")), null, "the common word $w is left alone after thank")
    same(ac.correct("yuo", at("thank yuo")), "you", "a slip is still mended in context")
    same(ac.correct("blorp", at("thank blorp")), ac.correct("blorp"), "context makes no correction of its own")
    // The person's own words join the completions once typed often enough.
    repeat(3) { type(personal, "ask blorpington now. ") }
    check(ac.suggest("blor", at("ask blor")).words.contains("blorpington")) { "a word typed three times after ask is offered there: ${ac.suggest("blor", at("ask blor")).words}" }
    check(ac.suggest("blor", at("the blor")).words.contains("blorpington")) { "and elsewhere, as one of the person's words" }

    // Timing: making a scorer and asking its top three, over real contexts, on this machine.
    val contexts = File(assets, "dict/en.tsv").useLines { it.take(3000).map { l -> l.substringBefore('\t') }.toList() }
    repeat(5) { type(personal, contexts.take(400).joinToString(" ") + ". ") }
    val texts = contexts.map { "$it " } + contexts.take(1000).zipWithNext { a, b -> "$a $b " }
    repeat(3) { for (text in texts) at(text).top(3) }   // warm
    val took = LongArray(texts.size)
    for ((i, text) in texts.withIndex()) { val a = System.nanoTime(); at(text).top(3); took[i] = System.nanoTime() - a }
    took.sort()
    val median = took[took.size / 2] / 1000.0
    val p99 = took[took.size * 99 / 100] / 1000.0
    val worst = took.last() / 1000.0
    println("  ${texts.size} lookups (place, blend, top three): median %.1f µs, 99th %.1f µs, worst %.1f µs".format(median, p99, worst))
    check(median < 50.0) { "a lookup's median is under 50 µs here ($median)" }
    check(p99 < 1000.0) { "and 99 in 100 are under a millisecond ($p99)" }
    val typing = listOf("how a", "thank y", "i w", "the b", "on m", "see you t")
    repeat(200) { for (text in typing) ac.suggest(text.substringAfterLast(' '), at(text)) }
    val took2 = LongArray(600)
    var k = 0
    repeat(100) { for (text in typing) { val a = System.nanoTime(); ac.suggest(text.substringAfterLast(' '), at(text)); took2[k++] = System.nanoTime() - a } }
    took2.sort()
    // The same completions with no context, to see what the context adds: most of either is the scan of the word list.
    val took3 = LongArray(600)
    k = 0
    repeat(100) { for (text in typing) { val a = System.nanoTime(); ac.suggest(text.substringAfterLast(' ')); took3[k++] = System.nanoTime() - a } }
    took3.sort()
    println("  completions of one letter: median %.0f µs in context, %.0f µs without (worst %.0f and %.0f)".format(took2[300] / 1000.0, took3[300] / 1000.0, took2.last() / 1000.0, took3.last() / 1000.0))
}

/**
 * A typist at one field, for [NextWordLearner]: what each key does to the text and tells the learner (as
 * DevKeyboardService's handlers do), with a look at the text after each, as the service's tick gives it.
 */
private class Typist {
    val pairs = PersonalPairs()
    val learner = NextWordLearner(200) { pairs.learn(it) }.also { it.isWordChar = en }
    val text = StringBuilder()
    var allowed = true
    private var swiped = false

    fun look() = text.toString().let { learner.look(it, NextWordText.place(it, en, it.length >= 200), allowed) }

    /** Keys, one at a time: a letter after a swiped word gets the space the keyboard puts before it. */
    fun type(s: String): Typist {
        for (c in s) {
            if (swiped && c.isLetterOrDigit()) { text.append(' '); learner.ended() }
            swiped = false
            text.append(c)
            if (en(c)) learner.typed(if (c == '\'') 0 else 1) else { learner.ended(); learner.typed(0) }
            look()
        }
        return this
    }

    fun back(n: Int = 1): Typist { repeat(n) { text.setLength(text.length - 1); learner.deleted(); look() }; return this }

    fun swipe(word: String): Typist {
        val lead = if (text.isNotEmpty() && !text.last().isWhitespace()) " " else ""
        text.append(lead).append(word)
        learner.typed(word.length)
        if (lead.isNotEmpty()) learner.ended()
        swiped = true
        look()
        return this
    }

    /** A predicted word tapped in the row: typed with its space. */
    fun tap(word: String): Typist { text.append(word).append(' '); learner.word(word.length); learner.ended(); swiped = false; look(); return this }

    fun paste(s: String): Typist { text.append(s); learner.notTyped(); swiped = false; look(); return this }

    /** The incognito chip (or the learning switch): off and on again in the same field. */
    fun learning(on: Boolean): Typist { if (!on) learner.drop(); allowed = on; look(); return this }

    /** Enter sends the message: one more look, the last words counted, and the field empties. */
    fun enter(): Typist { look(); learner.flush(finished = true); text.setLength(0); learner.emptied(); swiped = false; return this }

    fun n(context: String, word: String) = pairs.count(context, word)
}

private fun learning() {
    println("what is learned: typed, swiped and tapped words, backspace, and the fence")
    fun counts(t: Typist, what: String, vararg want: Pair<String, String>) {
        for ((context, word) in want) same(t.n(context, word), 1f, "$what: $context > $word is counted once")
        same(t.pairs.events, want.size, "$what: ${want.size} words are counted in all")
    }
    val s = NextWordText.START
    counts(Typist().type("how are you").enter(), "typed", s to "how", "how" to "are", "are" to "you")
    counts(Typist().swipe("how").swipe("are").swipe("you").enter(), "swiped", s to "how", "how" to "are", "are" to "you")
    counts(Typist().swipe("thanks").enter(), "one swiped word, sent", s to "thanks")
    counts(Typist().type("how ").swipe("are").type("you ").enter(), "typed, swiped, typed", s to "how", "how" to "are", "are" to "you")
    counts(Typist().swipe("how").type("are ").swipe("you").type(".").enter(), "swiped, typed, swiped", s to "how", "how" to "are", "are" to "you")
    counts(Typist().type("how ").tap("are").tap("you").enter(), "typed, then two taps in the row", s to "how", "how" to "are", "are" to "you")
    counts(Typist().swipe("see").swipe("you").type(" ").enter(), "a swiped word ended by a space", s to "see", "see" to "you")

    // A slip mended in a sentence's first word does not lose the last word of the sentence before.
    val mended = Typist().type("see you tomorrow. thna").back(2).type("anks for ").enter()
    counts(mended, "a slip mended after a sentence end", s to "see", "see" to "you", "you" to "tomorrow", s to "thanks", "thanks" to "for")
    same(mended.pairs.unigram("thna"), 0f, "the slip itself is not learned")
    // A word retyped is the retyped word; the typo is never counted.
    val retyped = Typist().type("teh ").back(4).type("the cat ").enter()
    counts(retyped, "a typo deleted and typed again", s to "the", "the" to "cat")
    // Backspace that only takes the space after a word leaves the word standing.
    counts(Typist().type("hello world ").back(1).also { it.learner.flush() }, "the space after a word deleted, then the field left", s to "hello", "hello" to "world")
    // Leaving straight after a backspace, before any look: the held word may be half gone, and is not counted.
    counts(Typist().type("hello world ").also { it.learner.deleted(); it.learner.flush() }, "a backspace, then the field left at once", s to "hello")
    // A letter typed and taken back, then a space: the word before is not taken up a second time.
    counts(Typist().type("how a").back(1).type(" ").enter(), "a letter taken back", s to "how")
    // Several letters from one long-press (Arabic's لا) are inside the word, and end nothing.
    val alt = Typist().type("hi a")
    alt.text.append("bc"); alt.learner.typed(2); alt.look()
    counts(alt.type("d ").enter(), "a long-press that types two letters", s to "hi", "hi" to "abcd")
    // Half a word when the keyboard hides is no word.
    counts(Typist().type("how ar").also { it.learner.flush() }, "the field left in the middle of a word", s to "how")

    // Incognito switched off in the middle of a message: nothing typed before that is learned, or is the word before one.
    val secret = Typist().learning(false).type("meet at lisa's place ").learning(true).type("tonight ok ").enter()
    counts(secret, "after incognito", "tonight" to "ok")
    for (w in listOf("meet", "at", "lisa's", "place")) same(secret.pairs.unigram(w), 0f, "after incognito: $w was not learned")
    same(secret.n("place", "tonight") + secret.n("lisa's place", "tonight") + secret.n("place tonight", "ok"), 0f, "after incognito: no word typed before is a context")
    // A word begun while incognito and finished after is not learned, whole or in part.
    val begun = Typist().learning(false).type("see you tom").learning(true).type("orrow ok fine ").enter()
    counts(begun, "a word begun while incognito", "ok" to "fine")
    // The next sentence is learned in full.
    counts(Typist().learning(false).type("something private. ").learning(true).type("hello there ").enter(), "a sentence after incognito's", s to "hello", "hello" to "there")
    counts(Typist().learning(false).type("something private ").learning(true).type("and more. hello there ").enter(), "the sentence after the one incognito was in", "and" to "more", s to "hello", "hello" to "there")
    // The fenced text edited: nothing is gone by until a sentence starts.
    counts(Typist().learning(false).type("private words ").learning(true).back(3).type("ld here now. new start ").enter(), "the fenced text edited", s to "new", "new" to "start")
    // Pasted text is fenced the same way; what was typed before it is still counted.
    counts(Typist().type("see ").paste("the pasted thing").type(" works well ").enter(), "around pasted text", s to "see", "works" to "well")
    // A long message with no full stop after the fence goes on being learned: the fence is let go once it is far behind.
    val long = Typist().learning(false).type("private start ").learning(true)
    repeat(30) { long.type("word$it ".replace(Regex("[0-9]"), "x")) }
    long.type("nearly done now ").enter()
    same(long.n("nearly", "done") to long.n("done", "now"), 1f to 1f, "far past the fence, words are learned")
    same(long.pairs.unigram("private") + long.pairs.unigram("start"), 0f, "and what was behind it never is")
    // Learning off: nothing at all.
    counts(Typist().learning(false).type("nothing here is kept ").enter(), "with learning off")
}

fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: ".")
    fixtures(root)
    blend()
    bounds()
    learning()
    bundled(root)
    english(root)
    if (failures > 0) { println("next_word_check: $failures of $checks checks FAILED"); kotlin.system.exitProcess(1) }
    println("next_word_check: $checks checks passed")
}
