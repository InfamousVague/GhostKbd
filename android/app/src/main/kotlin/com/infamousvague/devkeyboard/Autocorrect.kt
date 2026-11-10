package com.infamousvague.devkeyboard

import java.io.InputStream
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Autocorrect for one word at a time, run when a word is finished (space or punctuation): a word the dictionaries do
 * not know, or know only as a rare spelling, is replaced by the likeliest real word a slip or two away.
 *
 * The dictionaries are assets/dict/<language>.tsv: each language's commonest words with their Zipf frequency (made
 * from the `wordfreq` project's lists by tool/dictionaries.py, CC BY-SA 4.0; 40,000 for English, up to 30,000 for the
 * others), plus English's apostrophe-less contractions (dict/en_contractions.tsv: dont → don't). They are inside the
 * app: [open] reads an asset, and nothing here ever opens a connection. Several languages of one script load
 * together, so mixing them in one message works. A word typed in its plainest spelling (cafe, senor, καλημερα) finds
 * the proper one cheaply ([Script.plain]).
 *
 * A candidate scores its frequency minus what its slips cost: a neighbouring key is a cheap slip, a far one dear, a
 * doubled or missing letter in between. Neighbours are the layout's being typed on ([Board]), so on QWERTY "keybkard"
 * finds "keyboard" first, and on a Russian board the slips are that board's.
 *
 * Learned words (a fix the person undid, or words added in Settings) are never changed, and count as words for
 * correcting near-misses of them.
 *
 * The lists added after the first six are of languages with many endings, and 30,000 words are only their commonest
 * forms: a right word the list lacks is one slip from a listed one more often than a typo is. With one of them
 * loaded a mend is made on its own only when it is near certain ([best]); a likely one is offered in the row instead.
 *
 * Where next-word prediction is on, the word before is gone by too ([Scorer], NextWordModel.kt): among the words a
 * prefix could become, the likely next ones rank higher, and between two candidates that would both have been good
 * corrections the likelier one after that word is chosen. Context never causes a correction that would not have been
 * made without it, and with no [Scorer] given everything here is exactly as it was before there was one.
 *
 * Mockingbird reads the same dictionaries to take slips out of its writing samples ([slip], [lexicon]): a far
 * stricter judgement than [correct]'s, since nobody is there to undo it. A word the lists miss is not a slip for
 * that alone.
 */
class Autocorrect(private val open: (String) -> InputStream?) {
    /**
     * The word lists as loaded for one script: every word with its frequency, each accented word under its plain
     * spelling ([folded]), English's contractions, every word sorted (a prefix's completions are one run of it), the
     * accented words sorted by plain spelling ([plainKeys] "cafe" beside [plainWords] "café", for completing "caf"
     * with "café"), each sorted array's frequencies beside it so a scan looks nothing up, and swipe typing's index,
     * the words by the plain first and last letter ("hello" under "ho"). [givesWay] are the plain spellings that are
     * in a list themselves and still give way to their accented word (see [load]). [commonest] are each language's
     * commonest few words, for filling next-word prediction's row. [sharedI] says a list other than English's has
     * "i" as one of its commonest words (Polish, Swedish, Italian), and [cautious] that one of the lists is of the
     * languages added after the first six. Never changed once made.
     */
    private class Lexicon(
        val key: String,
        val words: Map<String, Float> = emptyMap(),
        val folded: Map<String, String> = emptyMap(),
        val contractions: Map<String, String> = emptyMap(),
        val sorted: Array<String> = emptyArray(),
        val sortedZipf: FloatArray = FloatArray(0),
        val plainKeys: Array<String> = emptyArray(),
        val plainWords: Array<String> = emptyArray(),
        val plainZipf: FloatArray = FloatArray(0),
        val byEnds: Map<String, List<String>> = emptyMap(),
        val givesWay: Set<String> = emptySet(),
        val commonest: Map<String, List<String>> = emptyMap(),
        val sharedI: Boolean = false,
        val cautious: Boolean = false,
        /** The starter tables' rows (assets/ngram/<language>.tsv), each word's followers as written, for swipe typing. */
        val pairRows: Map<String, String> = emptyMap(),
    )

    /** What is typed on and what is known, swapped as one so a key press never sees one board's words under another's keys. */
    private class Now(val board: Board, val lexicon: Lexicon)

    @Volatile private var now = Now(Board.of(Layouts.QWERTY, english = false), Lexicon(""))
    /**
     * The lexicon wanted (its key, its lists) and the board it is for. [cache] holds every lexicon made that the
     * settings can still ask for ([keep]: one for each script an enabled layout types), so going round the layouts
     * with the globe key reads each list once. That is never more than the six lists that may be on at once.
     */
    private var wantedKey = ""
    private var wantedLists: List<String> = emptyList()
    private var wantedBoard = now.board
    private var keep: Set<String> = emptySet()
    private val cache = HashMap<String, Lexicon>()
    /** The one thread that reads word lists, while there is one to read. */
    private var loader: Thread? = null

    /** Told, on the loader's thread, each time a lexicon that was being read comes into use: there are words now. */
    @Volatile var onLoaded: (() -> Unit)? = null

    @Volatile private var learned: Set<String> = emptySet()
    /** Texting slang (assets/dict/en_slang.txt) while its switch is on: left alone, and offered as you type. */
    @Volatile private var slang: Set<String> = emptySet()
    @Volatile private var slangOn = false
    private val slangList: Set<String> by lazy {
        runCatching {
            open("dict/en_slang.txt")!!.bufferedReader().useLines { lines ->
                lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
            }
        }.getOrDefault(emptySet())
    }
    /** There are words to correct, suggest and swipe with: false for a layout with no word list (Korean, plain ABC) and while one loads. */
    val active: Boolean get() = now.lexicon.words.isNotEmpty()

    /** Swipe typing's decoder ([SwipeDecoder]), over the lexicon in use; cleared whenever that lexicon changes. */
    private val decoder = SwipeDecoder(object : SwipeDecoder.Lexicon {
        override fun ends(first: Char, last: Char): List<String> {
            val lex = now.lexicon
            val listed = lex.byEnds["$first$last"].orEmpty()
            val mine = learned.filter { it.isNotEmpty() && !lex.words.containsKey(it) && letters(it).let { l -> l.isNotEmpty() && l.first() == first && l.last() == last } }
            return if (mine.isEmpty()) listed else listed + mine
        }
        override fun zipf(word: String): Float? = now.lexicon.words[word] ?: if (word in learned) LEARNED_ZIPF else null
        override fun letters(word: String) = swipeLetters(now.board.script, word)
        override fun follows(prev: String): Map<String, Float>? = now.lexicon.pairRows[prev]?.let { row ->
            val out = HashMap<String, Float>()
            for (e in row.split(' ')) {
                val c = e.lastIndexOf(':')
                if (c > 0) e.substring(c + 1).toFloatOrNull()?.let { out[e.substring(0, c).lowercase()] = it / 10_000f }
            }
            out
        }
    })
    /** The lexicon the decoder's worked-out shapes are for. */
    private var decoderLexicon: Lexicon? = null

    /** The lexicon asked for last is the one in use: for the self-check, which must not ask before the lists are read. */
    val settled: Boolean @Synchronized get() = now.lexicon.key == wantedKey

    /**
     * The word lists to use ([dictionaries], the stems of assets/dict's files) and the keys being typed on. A list is
     * read in the background the first time; until it is there nothing is corrected ([active] is false, and
     * [onLoaded] says when it is). Changing only the board (QWERTY to AZERTY, both with English and French) keeps the
     * words. [keep] are the lexicons the settings can ask for ([LanguageState.lexicons]): any other is let go.
     */
    @Synchronized
    fun setLanguages(dictionaries: List<String>, board: Board, keep: Set<String> = emptySet()) {
        val key = key(board.script, dictionaries)
        wantedBoard = board
        this.keep = keep + key
        cache.keys.retainAll(this.keep)
        if (key == wantedKey) {
            now = Now(board, now.lexicon)
            return
        }
        wantedKey = key
        wantedLists = dictionaries
        val ready = if (key.isEmpty()) Lexicon("") else cache[key]
        now = Now(board, ready ?: Lexicon(""))
        if (ready == null && loader == null) loader = thread(name = "autocorrect-dictionary", isDaemon = true) { loadWanted() }
    }

    /**
     * The loader thread: reads the lexicon that is wanted, then looks again, since another may have been asked for
     * meanwhile (a read that is no longer wanted gives up at its next list). One runs at a time, so turning six
     * languages on in a row never reads six sets at once. A read that fails is forgotten, and the next
     * [setLanguages] tries it again.
     */
    private fun loadWanted() {
        while (true) {
            val (key, lists, script) = synchronized(this) {
                if (wantedKey.isEmpty() || cache.containsKey(wantedKey)) { loader = null; return }
                Triple(wantedKey, wantedLists, wantedBoard.script)
            }
            val made = runCatching { load(key, lists, script) { synchronized(this) { wantedKey != key } } }.getOrNull()
            val inUse = synchronized(this) {
                when {
                    wantedKey != key -> { if (made != null && key in keep) cache[key] = made; false }
                    made != null -> { cache[key] = made; now = Now(wantedBoard, made); true }
                    else -> { wantedKey = ""; loader = null; return }
                }
            }
            if (inUse) runCatching { onLoaded?.invoke() }
        }
    }

    /**
     * Reads [languages]' lists for [script] into a [Lexicon].
     *
     * The lists of the languages added after the first six carry accent-less spellings as words (moze, byl, icin,
     * ευχαριστω), because that is how people type them online, and wordfreq counts what people type. Left as words they
     * would never be mended, which is the one thing a Polish or Turkish typist on plain keys wants most. So in those
     * languages, where their script has accents to lose (Latin, Greek, Cyrillic), a plain spelling gives way to its
     * accented word when that is about eight times as common ([PLAIN_MARGIN]). The first six keep the rule they
     * always had: a plain spelling that is a common word (Spanish "esta", "mas") is never changed. Catalan keeps it
     * too ([STRICT]): its accents tell words apart as Spanish's do (mes and més, son and són). Where the accented
     * letter has a key of its own on the board being typed on, a listed plain spelling never gives way ([best]).
     *
     * [gone] is asked before each list: true, and the read is given up (null), because another lexicon is wanted now.
     */
    private fun load(key: String, languages: List<String>, script: Script, gone: () -> Boolean = { false }): Lexicon? {
        val w = HashMap<String, Float>(48_000 * languages.size)
        val c = HashMap<String, String>(600)
        val lenient = script == Script.LATIN || script == Script.GREEK || script == Script.CYRILLIC
        val strict = HashSet<String>()
        val commonest = LinkedHashMap<String, ArrayList<String>>()
        var sharedI = false
        for (lang in languages) {
            if (gone()) return null
            val firsts = commonest.getOrPut(lang) { ArrayList(12) }
            val first = lang in STRICT
            runCatching {
                open("dict/$lang.tsv")!!.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            val word = line.substring(0, tab)
                            val zipf = line.substring(tab + 1).toFloatOrNull() ?: return@forEach
                            if (zipf > (w[word] ?: Float.NEGATIVE_INFINITY)) w[word] = zipf
                            // Each list has its commonest words first: the first dozen of each are kept.
                            if (firsts.size < 12) firsts += word
                            if (first && lenient && languages.size > 1) strict += word
                            if (word == "i" && lang != "en" && zipf >= I_WORD) sharedI = true
                        }
                    }
                }
            }
            if (lang == "en") runCatching {
                open("dict/en_contractions.tsv")!!.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) c[line.substring(0, tab)] = line.substring(tab + 1)
                    }
                }
            }
        }
        // The words of a programmer's day, which a general list misses or ranks as typos (npm, async...): Latin boards only.
        if (script == Script.LATIN) for (word in DEV_WORDS) w[word] = maxOf(w[word] ?: 0f, 4.5f)
        // Each accented word under its plain spelling, the commonest winning: cafe → café, senor → señor.
        val f = HashMap<String, String>()
        val ends = HashMap<String, ArrayList<String>>()
        val plains = ArrayList<String>()
        if (gone()) return null
        for ((word, zipf) in w) {
            val plain = script.plain(word)
            if (plain != word) {
                if ((w[plain] ?: 0f) < zipf && (f[plain]?.let { w[it]!! < zipf } != false)) f[plain] = word
                plains += plain + "\t" + word
            }
            // Swipe typing: letters and apostrophes ("what's" glides w-h-a-t-s), up to 20 letters, under the plain first
            // and last letter; a one-letter word only if it's common (it may sit inside a phrase glided in one stroke).
            if (word.any { !it.isLetter() && it != '\'' && it != '’' }) continue
            val glide = swipeLetters(script, word)
            if (glide.isEmpty() || glide.length > 20 || !glide.all { it.isLetter() }) continue
            if (glide.length == 1 && zipf < SwipeDecoder.ONE_LETTER) continue
            ends.getOrPut("${glide.first()}${glide.last()}") { ArrayList() }.add(word)
        }
        val gives = HashSet<String>()
        if (lenient && languages.any { it !in STRICT }) {
            for ((plain, accented) in f) {
                val known = w[plain] ?: continue
                if (known >= COMMON && accented !in strict && plain !in strict && w[accented]!! >= known + PLAIN_MARGIN) gives += plain
            }
        }
        val sorted = w.keys.toTypedArray().also { it.sort() }
        // "cafe\tcafé" sorts by plain spelling, then by word: the order the two arrays are then cut into.
        plains.sort()
        val plainKeys = Array(plains.size) { plains[it].substring(0, plains[it].indexOf('\t')) }
        val plainWords = Array(plains.size) { plains[it].substring(plains[it].indexOf('\t') + 1) }
        // Which word follows which (assets/ngram), kept as each row's text until swipe typing asks for it.
        val rows = HashMap<String, String>()
        for (lang in languages) runCatching {
            open("ngram/$lang.tsv")?.bufferedReader()?.useLines { lines ->
                lines.drop(1).forEach { line ->
                    val tab = line.indexOf('\t')
                    // One word's row only: swipe typing asks what follows a word, never two ("how are"), and an
                    // entry with no colon (a row's "=n" total) is passed over when the row is read.
                    if (tab > 0 && line.indexOf(' ') !in 0 until tab) {
                        val k = line.substring(0, tab).lowercase()
                        if (k !in rows) rows[k] = line.substring(tab + 1)
                    }
                }
            }
        }
        return Lexicon(
            key, w, f, c, sorted, FloatArray(sorted.size) { w[sorted[it]] ?: 0f }, plainKeys, plainWords, FloatArray(plainWords.size) { w[plainWords[it]] ?: 0f },
            ends, gives, commonest, sharedI, languages.any { it !in FIRST_SIX }, rows,
        )
    }

    /**
     * Loads [dictionaries] for [board] on the calling thread and returns once they are in: for a worker that needs the
     * answers now and has no keyboard to borrow them from (Mockingbird cleaning its samples from Settings).
     */
    fun loadNow(dictionaries: List<String>, board: Board) {
        val key = key(board.script, dictionaries)
        val made = if (key.isEmpty()) Lexicon("") else load(key, dictionaries, board.script) ?: Lexicon("")
        synchronized(this) {
            wantedKey = key; wantedLists = dictionaries; wantedBoard = board; keep = setOf(key)
            if (key.isNotEmpty()) cache[key] = made
            now = Now(board, made)
        }
    }

    /** True once there are words; until then nothing is corrected. */
    val isReady: Boolean get() = active

    /** Words the person has kept: never corrected, and targets for correcting slips of them. */
    fun setLearned(words: Set<String>) {
        if (words == learned) return
        learned = words
        synchronized(decoder) { decoder.clear() }
    }

    /** Texting slang on or off (Matt, 2026-11-09: "add slang options to autocorrect like sybau and lmfao and ts"). */
    fun setSlang(on: Boolean) {
        if (on == slangOn) return
        slangOn = on
        if (!on) { slang = emptySet(); return }
        thread(name = "autocorrect-slang", isDaemon = true) { if (slangOn) slang = slangList }
    }

    /** A word autocorrect never changes: one the person kept, or slang while that is on. */
    private fun kept(lower: String) = lower in learned || lower in slang

    /**
     * Whether [lower] is a word here: in a loaded word list, one of English's contractions, a word the person kept,
     * or slang while that is on. Next-word prediction offers such a word back sooner than one nothing knows.
     */
    fun isWord(lower: String): Boolean {
        val lexicon = now.lexicon
        return lexicon.words.containsKey(lower) || lexicon.contractions.containsKey(lower) || kept(lower)
    }

    /**
     * The [n] commonest words of [language] (a loaded word list's stem; the first loaded where it is not one),
     * commonest first, at most a dozen: what next-word prediction fills a thin row with.
     */
    fun commonest(n: Int, language: String? = null): List<String> {
        val now = now
        val lists = now.lexicon.commonest
        return (lists[language] ?: lists.values.firstOrNull()).orEmpty().take(n).map { capitalI(now, it) }
    }

    /** How common a slang word counts, for ranking it among completions: its list frequency, else fairly common. */
    private fun slangZipf(words: Map<String, Float>, word: String) = maxOf(words[word] ?: 0f, SLANG_ZIPF)

    /**
     * German's ß typed where the lists spell ss (wordfreq folds it away): "straße" is the word "strasse", so it is
     * left exactly as typed and counts as a word.
     */
    private fun eszettWord(lower: String, words: Map<String, Float>) = 'ß' in lower && words.containsKey(lower.replace("ß", "ss"))

    /**
     * What [typed] should become, or null to leave it. [typed] is letters, their marks and the layout's joiners (an
     * apostrophe, a half-space) only. [scorer] is what is likely at this place in the text, where prediction is on.
     */
    fun correct(typed: String, scorer: Scorer? = null): String? {
        val now = now
        if (now.lexicon.words.isEmpty() || typed.isEmpty() || typed.length > 24) return null
        // An acronym or a deliberate capitalised name of two letters or more is left as typed.
        if (typed.length > 1 && typed.all { it.isUpperCase() }) return null
        // camelCase, iOS, McDonald: a capital after the first letter is deliberate.
        if (typed.drop(1).any { it.isUpperCase() }) return null
        val lower = typed.lowercase(now.board.locale)
        if (kept(lower) || eszettWord(lower, now.lexicon.words)) return null
        val fixed = best(now, lower, scorer)?.takeIf { it.sure }?.word ?: return null
        val cased = recase(now.board.locale, typed, fixed)
        return if (cased == typed) null else cased
    }

    /**
     * What the row above the keys offers for the word being typed: the fix autocorrect would make (or the word itself
     * when it is a word), then the commonest words it begins. [typedIsWord] says whether the typed word is one.
     */
    class Suggestions(val words: List<String>, val fix: String?, val typedIsWord: Boolean)

    /**
     * The row's suggestions for [typed]. With a [scorer] (prediction is on) the words it begins are ranked by the
     * word before as well as by how common they are, and the person's own often-typed words join them.
     */
    fun suggest(typed: String, scorer: Scorer? = null): Suggestions {
        val now = now
        if (now.lexicon.words.isEmpty() || typed.isEmpty() || typed.length > 24) return Suggestions(emptyList(), null, false)
        val locale = now.board.locale
        val lower = typed.lowercase(locale)
        val words = now.lexicon.words
        val contractions = now.lexicon.contractions
        val eszett = eszettWord(lower, words)
        val isWord = kept(lower) || (words[lower] ?: 0f) > 0f || contractions.containsKey(lower) || eszett
        val mend = if (kept(lower) || eszett) null else best(now, lower, scorer)
        val fix = mend?.takeIf { it.sure }?.word
        val out = LinkedHashSet<String>()
        fix?.let { out += recase(locale, typed, it) }
        if (isWord) out += recase(locale, typed, contractions[lower]?.let { capitalI(now, it) } ?: capitalI(now, lower))
        // A mend that is likely but not certain enough to make on its own is offered, never made.
        mend?.takeIf { !it.sure }?.let { out += recase(locale, typed, it.word) }
        // English's "I" where a lone i is left as typed because another language has it as a word: a tap away.
        if (lower == "i" && now.board.english && now.lexicon.sharedI && now.board.locale.language != "tr") out += "I"
        // Completions: the commonest few words starting with what is typed, and, where it is typed plainly, the
        // accented words whose plain spelling starts with it ("caf" offers "café", "viet" offers "việt").
        // Each scan keeps only its few commonest as it goes (the row shows five), so a one-letter prefix with
        // thousands of words under it allocates and sorts nothing.
        val top = ArrayList<Pair<String, Float>>()
        val slangNow = slang
        val begun = Best(SCAN_KEEP)
        val arr = now.lexicon.sorted
        val zipfs = now.lexicon.sortedZipf
        var lo = firstFrom(arr, lower)
        var scanned = 0
        while (lo < arr.size && arr[lo].startsWith(lower) && scanned < 30_000) {
            val cand = arr[lo]
            if (cand != lower) begun.offer(cand, if (slangNow.isNotEmpty() && cand in slangNow) maxOf(zipfs[lo], SLANG_ZIPF) else zipfs[lo])
            lo++; scanned++
        }
        begun.into(top)
        if (now.board.script.plain(lower) == lower) {
            val accented = Best(SCAN_KEEP)
            val keys = now.lexicon.plainKeys
            val plainWords = now.lexicon.plainWords
            val plainZipf = now.lexicon.plainZipf
            lo = firstFrom(keys, lower)
            scanned = 0
            while (lo < keys.size && keys[lo].startsWith(lower) && scanned < 30_000) {
                val cand = plainWords[lo]
                // One that starts with the typed letters as they are was found above already.
                if (cand != lower && !cand.startsWith(lower)) accented.offer(cand, plainZipf[lo])
                lo++; scanned++
            }
            accented.into(top)
        }
        for (l in learned) if (l.startsWith(lower) && l != lower) top += l to LEARNED_ZIPF
        for (l in slang) if (l.startsWith(lower) && l != lower && !words.containsKey(l)) top += l to SLANG_ZIPF
        if (scorer != null) inContext(now, lower, scorer, top)
        top.sortByDescending { it.second }
        if (scorer == null) {
            for ((cand, _) in top) { if (out.size >= 5) break; out += recase(locale, typed, capitalI(now, cand)) }
        } else {
            // A word ranked twice (by frequency, and in context) is offered once, in its higher place.
            val taken = out.mapTo(HashSet()) { it.lowercase(locale) }
            for ((cand, _) in top) { if (out.size >= 5) break; if (taken.add(cand.lowercase(locale))) out += recase(locale, typed, capitalI(now, cand)) }
        }
        return Suggestions(out.toList(), fix?.let { recase(locale, typed, it) }, isWord)
    }

    /**
     * The few highest-ranked of many candidates, kept in order as they are offered. Of two ranked the same the one
     * offered first stays ahead, which is the order a sort of them all would give.
     */
    private class Best(private val n: Int) {
        private val words = arrayOfNulls<String>(n)
        private val ranks = FloatArray(n)
        private var size = 0

        fun offer(word: String, rank: Float) {
            if (size == n && rank <= ranks[n - 1]) return
            var at = if (size < n) size++ else n - 1
            while (at > 0 && rank > ranks[at - 1]) { words[at] = words[at - 1]; ranks[at] = ranks[at - 1]; at-- }
            words[at] = word
            ranks[at] = rank
        }

        fun into(out: ArrayList<Pair<String, Float>>) { for (i in 0 until size) out += words[i]!! to ranks[i] }
    }

    /** The first place in [sorted] (which may hold a spelling more than once) that is not before [prefix]. */
    private fun firstFrom(sorted: Array<String>, prefix: String): Int {
        var lo = 0
        var hi = sorted.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (sorted[mid] < prefix) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Next-word prediction's part in the completions of [lower]: each word that is likely next here and begins with
     * it joins [top] ranked by its frequency (or the person's own use of it, if that says more) plus the lift the
     * context gives it, at most [Scorer.LIFT_CAP]; so do the person's own often-typed words that begin with it. A word
     * may then be in [top] twice: [suggest] takes the higher-ranked one, with its capitals where it has them (Monday).
     */
    private fun inContext(now: Now, lower: String, scorer: Scorer, top: ArrayList<Pair<String, Float>>) {
        val words = now.lexicon.words
        val script = now.board.script
        for (f in scorer.following(lower) { script.plain(it) }) {
            val zipf = words[f.lower] ?: 0f
            val base = maxOf(zipf, scorer.personalZipf(f.lower, zipf > 0f || isWord(f.lower)))
            if (base > 0f) top += f.shown to base + minOf(scorer.boost(f.lower, base), Scorer.LIFT_CAP)
        }
        for (p in scorer.personal(lower)) top += p.shown to p.share
    }

    /**
     * Swipe typing: the likeliest readings of a finger's [path] over the letters, best first: a word, or several
     * words in one stroke ("hey what's up"). [before] is the word before the cursor in lower case, "^" at a
     * sentence's start, or null. The decoding is [SwipeDecoder]'s, over the lexicon in use.
     */
    fun swipe(path: List<android.graphics.PointF>, keys: Map<Char, android.graphics.PointF>, keyW: Float, before: String? = null): List<String> {
        val now = now
        if (now.lexicon.words.isEmpty() || path.size < 2 || keyW <= 0f) return emptyList()
        return synchronized(decoder) {
            // Another board's words: the shapes worked out for the last lexicon are no use.
            if (now.lexicon !== decoderLexicon) { decoder.clear(); decoderLexicon = now.lexicon; swipeKeysFrom = null }
            // The same keys keep the same map, so the decoder keeps the words' shapes it has worked out for them.
            if (keys != swipeKeysFrom) {
                swipeKeysFrom = HashMap(keys.mapValues { (_, p) -> android.graphics.PointF(p.x, p.y) })
                swipeKeys = keys.mapValues { (_, p) -> p.x to p.y }
                warm(swipeKeys, now.lexicon)
            }
            decoder.decode(path.map { it.x to it.y }, swipeKeys, keyW, before)
        }
    }

    /**
     * Works out every word's shape on these [keys] in the background, a pair of letters at a time, so the strokes
     * after the first don't wait for it. A stroke meanwhile waits for one pair at most.
     */
    private fun warm(keys: Map<Char, Pair<Float, Float>>, lexicon: Lexicon) {
        val pairs = lexicon.byEnds.keys.toList()
        thread(name = "swipe-warm", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            for (pair in pairs) {
                if (swipeKeys !== keys || now.lexicon !== lexicon) return@thread
                synchronized(decoder) { decoder.warm(pair[0], pair[1], keys) }
            }
        }
    }

    private var swipeKeysFrom: Map<Char, android.graphics.PointF>? = null
    @Volatile private var swipeKeys: Map<Char, Pair<Float, Float>> = emptyMap()

    /** What a word should be: [word], and whether autocorrect is [sure] enough to change it by itself (else it is only offered). */
    private class Fix(val word: String, val sure: Boolean)

    /**
     * The word [lower] should be, or null. With a [scorer], among the candidates that would each have been a good
     * enough correction on their own, the one likelier after the word before wins (its lift counts for at most
     * [CONTEXT_PICK]); which words may be corrected at all, and the least a correction must score, are untouched.
     *
     * With a [Lexicon.cautious] list loaded, a word that is one slip from a listed one is changed on its own only when
     * the mend scores [CAUTIOUS_SCORE], is not the kind an ending makes ([ending]), and the typed word does not look
     * like a listed word with an ending of its own ([inflected]); else it comes back not [Fix.sure], to be offered.
     * With the first six alone every mend is sure, as it always was.
     */
    private fun best(now: Now, lower: String, scorer: Scorer? = null): Fix? {
        if (lower == "i" && capitalises(now)) return Fix("I", true)
        val words = now.lexicon.words
        val folded = now.lexicon.folded
        val contractions = now.lexicon.contractions
        contractions[lower]?.let { return Fix(capitalI(now, it), true) }
        val known = words[lower]
        // Typed without its accents: the accented word, unless the plain spelling is itself as common. A listed plain
        // spelling gives way only on a board where the accent is a long-press away: typed on a board that has the
        // letter as a key (Swedish far beside får, Turkish yas beside yaş), the plain word was meant.
        folded[lower]?.let { accented ->
            if (known == null || known < COMMON || (lower in now.lexicon.givesWay && !keyed(now.board, lower, accented))) return Fix(accented, true)
        }
        // A common word is never changed; a rare one only for a far commoner word one cheap slip away.
        if (known != null && known >= COMMON) return capitalI(now, lower).takeIf { it != lower }?.let { Fix(it, true) }
        val pick = pick(now, lower, known, scorer) ?: return null
        if (pick.score < MIN_SCORE) return null
        val sure = !now.lexicon.cautious || (pick.score >= CAUTIOUS_SCORE && !ending(now.board, lower, pick.from) && !inflected(words, lower, pick.from))
        return Fix(capitalI(now, pick.word), sure)
    }

    /**
     * The likeliest word a slip or two from a typed one ([from] the candidate spelling it came by): its [score], what
     * its slips [cost], and the [next] best word's score.
     */
    private class Pick(val word: String, val from: String, val score: Float, val cost: Float, val next: Float)

    /**
     * The best-scoring word among those a slip or two from [lower], and the score of the runner-up. [known] is
     * [lower]'s own frequency when the lists have it as a rare word: then only a cheap slip to a far commoner word
     * can win, though any word still counts as a runner-up. Context only chooses among corrections good enough
     * without it; with no [scorer] the score is the plain one.
     */
    private fun pick(now: Now, lower: String, known: Float?, scorer: Scorer? = null): Pick? {
        val words = now.lexicon.words
        val folded = now.lexicon.folded
        val contractions = now.lexicon.contractions
        var bestWord: String? = null
        var bestFrom = ""
        var bestScore = Float.NEGATIVE_INFINITY
        var bestCost = 0f
        var next = Float.NEGATIVE_INFINITY
        for ((candidate, cost) in candidates(now.board, lower)) {
            val zipf = words[candidate] ?: contractions[candidate]?.let { words[it] }
                ?: (if (candidate in learned) LEARNED_ZIPF else null) ?: folded[candidate]?.let { words[it] } ?: continue
            val plain = zipf - cost * COST_WEIGHT
            val word = contractions[candidate] ?: folded[candidate]?.takeIf { candidate !in words } ?: candidate
            val score = if (scorer == null || plain < MIN_SCORE) plain else plain + minOf(scorer.boost(word, zipf), CONTEXT_PICK)
            val mayWin = known == null || (cost <= 1.5f && zipf >= known + 2f)
            if (mayWin && score > bestScore) {
                if (word != bestWord) next = maxOf(next, bestScore)
                bestScore = score; bestCost = cost; bestWord = word; bestFrom = candidate
            } else if (word != bestWord && score > next) {
                next = score
            }
        }
        return bestWord?.let { Pick(it, bestFrom, bestScore, bestCost, next) }
    }

    /**
     * For text already written (Mockingbird's samples), where [correct] is for a word as it is typed: the one word
     * [typed] was plainly a slip of, in [typed]'s capitals, or null when it should stand. Nobody is there to undo it,
     * and a real word, a name or a term can be missing from the lists, so "not in the lists" is never enough: the
     * slip itself has to show.
     * - [correct]'s own guards hold (ACRONYMS, camelCase, learned words), and slang counts whether or not its
     *   switch is on;
     * - nothing that is a way of writing rather than a slip: no "i" → "I", no apostrophe put into "dont", no
     *   accent put onto "cafe";
     * - a spelling the lists hold, however rare, is somebody's word (untied, prefect, strat, nite) and stands,
     *   whatever commoner word it resembles. The exceptions are named: [LISTED_SLIPS];
     * - a spelling they do not hold is fixed only for one of three slips ([shape]): two letters next to each other
     *   swapped ("taht", "tomorrwo"), one neighbouring key struck inside the word ("keybkard"), or a consonant
     *   doubled. The last two only in a word of six letters or more: a short word has too many real neighbours
     *   (vim beside him), and a different first or last letter is as often another word (linted, linter);
     * - a word of six letters or more must also not look like a word ([odd]): drooping, vishal and minuet do, and
     *   are somebody's, however near dropping, visual and minute they sit;
     * - the fix must be likely ([SLIP_SCORE]; [SLIP_SCORE_SHORT] for a word of five letters or fewer, so only the
     *   very commonest words are put back: "pani" is not "pain") and well ahead of the next word it could have
     *   been ([SLIP_MARGIN]): "hwo" is who or how, so it stands.
     */
    fun slip(typed: String): String? {
        val now = now
        val words = now.lexicon.words
        if (words.isEmpty() || typed.length < 3 || typed.length > 24) return null
        if (typed.all { it.isUpperCase() } || typed.drop(1).any { it.isUpperCase() }) return null
        val lower = typed.lowercase()
        if (beyondFixing(lower)) return null
        val fixed = if (words.containsKey(lower)) {
            LISTED_SLIPS[lower]?.takeIf { (words[it] ?: 0f) >= COMMON } ?: return null
        } else {
            // Asked first, being cheap: most long words the lists miss look like words, and stand.
            if (lower.length > SHORT && !odd(lower)) return null
            val pick = pick(now, lower, null) ?: return null
            if (shape(now.board, lower, pick.word) == null) return null
            val bar = if (lower.length <= SHORT) SLIP_SCORE_SHORT else SLIP_SCORE
            if (pick.score < bar || pick.score - pick.next < SLIP_MARGIN) return null
            pick.word
        }
        val cased = recase(now.board.locale, typed, fixed)
        return if (cased == typed) null else cased
    }

    /** The slips [slip] takes for accidents: letters swapped, a neighbouring key, a letter doubled. */
    private enum class Slip { SWAP, KEY, DOUBLED }

    /**
     * Which slip turns [fix] into [typed] (both lower case), or null when it is none [slip] trusts:
     * - two letters next to each other swapped, in a word of any length;
     * - one neighbouring key in place of the right one, in a word longer than [SHORT] and not at its first or
     *   last letter;
     * - a consonant typed twice, in a word longer than [SHORT]. A doubled vowel is a word stretched (whaat, loove).
     * A dropped letter, a far key and a letter too many are left: abbreviations and other words look the same.
     */
    private fun shape(board: Board, typed: String, fix: String): Slip? {
        fun near(a: Char, b: Char) = board.neighbours[a]?.contains(b) == true
        if (typed.length == fix.length) {
            val i = typed.indices.firstOrNull { typed[it] != fix[it] } ?: return null
            val j = typed.indices.last { typed[it] != fix[it] }
            if (j == i + 1 && typed[i] == fix[j] && typed[j] == fix[i]) return Slip.SWAP
            if (j == i && typed.length > SHORT && i > 0 && i < typed.length - 1 && near(typed[i], fix[i])) return Slip.KEY
            return null
        }
        if (typed.length == fix.length + 1 && typed.length > SHORT) {
            val i = typed.indices.first { it >= fix.length || typed[it] != fix[it] }
            if (i > 0 && typed[i] == typed[i - 1] && typed[i] !in VOWELS && typed.removeRange(i, i + 1) == fix) return Slip.DOUBLED
        }
        return null
    }

    /**
     * The runs of three letters, a word's two edges included, that the common words of the loaded languages hold:
     * what a word looks like. Made at [odd]'s first use and again when the dictionaries change, so the keyboard's
     * own loading never pays for it.
     */
    @Volatile private var runs: Pair<Map<String, Float>, java.util.BitSet>? = null

    /** [runs] for the dictionaries loaded now. */
    private fun runs(): java.util.BitSet {
        val loaded = now.lexicon.words
        runs?.let { if (it.first === loaded) return it.second }
        val set = java.util.BitSet(RUN_LETTERS * RUN_LETTERS * RUN_LETTERS)
        // A programmer's abbreviations (npm, kubectl) are words, but not what a word looks like.
        for ((word, zipf) in loaded) if (zipf >= COMMON && word !in DEV_WORDS) eachRun(word) { set.set(it) }
        runs = loaded to set
        return set
    }

    /** Each run of three letters in [word], its edges included ("the": ^th, the, he$), as a number for [runs]. */
    private inline fun eachRun(word: String, visit: (Int) -> Unit) {
        if (word.isEmpty()) return
        var first = 0
        var second = runLetter(word[0])
        for (i in 1..word.length) {
            val third = if (i < word.length) runLetter(word[i]) else 0
            visit((first * RUN_LETTERS + second) * RUN_LETTERS + third)
            first = second; second = third
        }
    }

    /** A letter's place in a run: 1 to 26 for a to z, 27 for anything else (an accent, an apostrophe); 0 is a word's edge. */
    private fun runLetter(c: Char) = if (c in 'a'..'z') c - 'a' + 1 else RUN_LETTERS - 1

    /**
     * [lower] does not look like a word: it holds a run of three letters that no common word of the loaded
     * languages has ("ybk" in keybkard, "hp" opening hpuse). A real word the lists miss (vishal, drooping,
     * curation) nearly always looks like one, which is how a neighbouring-key slip is told from it.
     */
    private fun odd(lower: String): Boolean {
        val seen = runs()
        var odd = false
        eachRun(lower) { if (!seen.get(it)) odd = true }
        return odd
    }

    /**
     * [lower] is a word nobody would fix: common in a loaded language, a contraction typed without its apostrophe,
     * a word spelled without its accents, a learned word, or texting slang (the whole list, its switch on or off).
     */
    fun beyondFixing(lower: String): Boolean {
        val lexicon = now.lexicon
        return lower in learned || lower in slangList || (lexicon.words[lower] ?: 0f) >= COMMON || lexicon.contractions.containsKey(lower) ||
            lexicon.folded.containsKey(lower)
    }

    /**
     * These dictionaries as [SampleCleaner] reads them. Each fix is worked out once and remembered for as long as
     * the returned object is kept, so make one per pass over the samples, not one to keep.
     */
    fun lexicon(): SampleCleaner.Lexicon = object : SampleCleaner.Lexicon {
        private val fixes = HashMap<String, String?>()
        override val ready get() = isReady
        override fun isWord(lower: String) = this@Autocorrect.beyondFixing(lower)
        override fun fix(token: String): String? = if (fixes.containsKey(token)) fixes[token] else slip(token).also { fixes[token] = it }
    }

    /**
     * Whether [accented] differs from the [plain] spelling that was typed in a letter with a key of its own on
     * [board] (å on the Swedish board, ş on the Turkish one): there the plain letter was typed by choice.
     */
    private fun keyed(board: Board, plain: String, accented: String): Boolean =
        accented.length == plain.length && plain.indices.any { accented[it] != plain[it] && board.neighbours.containsKey(accented[it]) }

    /**
     * Whether mending [typed] to [candidate] is the kind of change that, in a language of many endings and prefixes,
     * as often turns one right word into another as it mends a slip: a letter taken off either end (Finnish kissani
     * to kissan, Arabic وبيتي to وبيت; a doubled last letter is a slip), a first or last letter changed to one that is
     * no neighbouring key, or two letters changed at once (Polish kotami to nogami).
     */
    private fun ending(board: Board, typed: String, candidate: String): Boolean {
        val n = typed.length
        if (candidate.length == n - 1) {
            if (typed.endsWith(candidate)) return true
            return typed.startsWith(candidate) && !(n >= 2 && typed[n - 1] == typed[n - 2])
        }
        if (candidate.length != n) return false
        var first = -1
        var last = -1
        var changed = 0
        for (i in 0 until n) if (typed[i] != candidate[i]) { if (changed++ == 0) first = i; last = i }
        return when (changed) {
            1 -> (first == 0 || first == n - 1) && board.neighbours[typed[first]]?.contains(candidate[first]) != true
            // Two letters swapped is one slip; any other two is two.
            2 -> !(last == first + 1 && typed[first] == candidate[last] && typed[last] == candidate[first])
            else -> false
        }
    }

    /**
     * Whether [typed], a word no list has, looks like a listed word with an ending or a prefix on it (Turkish kedi-m,
     * Finnish kissa-ni, Arabic و-بيتي) that mending it to [candidate] would break: a common word of four letters or
     * more with up to three letters after it, or one or two before it, which [candidate] no longer begins (or ends)
     * with. Such a word is more likely right as it stands than a slip of some other word (kedim is not kerim). A
     * mend that keeps the listed word and changes only what follows it is a slip in the ending (спасибл, спасибо).
     */
    private fun inflected(words: Map<String, Float>, typed: String, candidate: String): Boolean {
        val n = typed.length
        for (k in 1..3) {
            if (n - k < 4) break
            val stem = typed.substring(0, n - k)
            if ((words[stem] ?: 0f) >= COMMON && !candidate.startsWith(stem)) return true
        }
        for (k in 1..2) {
            if (n - k < 4) break
            val stem = typed.substring(k)
            if ((words[stem] ?: 0f) >= COMMON && !candidate.endsWith(stem)) return true
        }
        return false
    }

    /**
     * Whether a lone "i" becomes "I" here: English is loaded, no other loaded list has "i" as one of its commonest
     * words (Polish and Catalan "and", Swedish and Danish "in", Italian "the"), and the board's capitals are not
     * Turkish's, where I is the capital of ı.
     */
    private fun capitalises(now: Now): Boolean = now.board.english && !now.lexicon.sharedI && now.board.locale.language != "tr"

    /** I, I'm, I've, I'll, I'd: English capitalises them wherever they fall, so only with English loaded; the lone "i" only where [capitalises]. */
    private fun capitalI(now: Now, word: String) = when {
        !now.board.english -> word
        word == "i" -> if (capitalises(now)) "I" else word
        word.startsWith("i'") -> "I" + word.substring(1)
        else -> word
    }

    /** The typed word's capitals on the correction: all, first, or none. Capitals are the layout's (Turkish i gives İ). */
    private fun recase(locale: Locale, typed: String, fixed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> fixed.uppercase(locale)
        typed.first().isUpperCase() -> fixed.replaceFirstChar { c -> c.toString().uppercase(locale).takeIf { it.length == 1 } ?: c.toString() }
        else -> fixed
    }

    /** Every word one slip from [w], and two neighbouring-key slips from it, with what the slips cost. */
    private fun candidates(board: Board, w: String): Map<String, Float> {
        val alphabet = board.alphabet
        val neighbours = board.neighbours
        fun near(a: Char, b: Char) = neighbours[a]?.contains(b) == true
        val out = HashMap<String, Float>()
        fun offer(s: String, cost: Float) { if (s.isNotEmpty() && s != w) out[s] = minOf(out[s] ?: Float.MAX_VALUE, cost) }
        for (i in w.indices) {
            // A letter too many: cheap when it doubles its neighbour or sits beside it on the keyboard.
            val extra = w[i]
            val cheap = (i > 0 && (w[i - 1] == extra || near(w[i - 1], extra))) || (i + 1 < w.length && (w[i + 1] == extra || near(w[i + 1], extra)))
            // A first letter is rarely the slip (npm is not pm, async is not sync).
            offer(w.removeRange(i, i + 1), if (i == 0) 2.2f else if (cheap) 1.0f else 1.6f)
            // The wrong letter: a neighbour is the commonest slip.
            for (c in alphabet) if (c != w[i]) offer(w.replaceRange(i, i + 1, c.toString()), if (near(w[i], c)) 1.0f else 2.4f)
            // Two letters swapped.
            if (i + 1 < w.length) offer(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2), 1.2f)
        }
        // A letter missed.
        for (i in 0..w.length) for (c in alphabet) offer(w.substring(0, i) + c + w.substring(i), 1.7f)
        // Two neighbouring-key slips in one word.
        if (w.length >= 3) {
            for (i in w.indices) for (a in neighbours[w[i]].orEmpty()) {
                val once = w.replaceRange(i, i + 1, a.toString())
                for (j in i + 1 until w.length) for (b in neighbours[w[j]].orEmpty()) offer(once.replaceRange(j, j + 1, b.toString()), 2.0f)
            }
        }
        return out
    }

    companion object {
        /** Zipf 3.2 is about one in 600,000 words: below it a "word" in the list is as likely a common misspelling. */
        private const val COMMON = 3.2f
        /** How much commoner (in Zipf, so 0.9 is about eight times) an accented word must be for its plain spelling, itself listed, to give way to it. */
        private const val PLAIN_MARGIN = 0.9f
        /** The languages the keyboard began with: with these alone, every correction is made as it always was. */
        private val FIRST_SIX = setOf("en", "de", "es", "fr", "it", "pt")
        /** The languages whose plain spellings keep the stricter rule (see [load]): the first six, and Catalan. */
        private val STRICT = FIRST_SIX + "ca"
        /** From this frequency "i" is one of a language's commonest words (Slovak's is 6.16); under it, it is the letter's name (Hungarian's 6.00). */
        private const val I_WORD = 6.1f
        /** The least a mend must score to be made on its own while a list beyond the first six is loaded (see [best]). */
        private const val CAUTIOUS_SCORE = 2.4f
        /** How many of a scan's commonest words [suggest] keeps: the five the row shows, and room for ones it already has. */
        private const val SCAN_KEEP = 8
        private const val COST_WEIGHT = 1.6f
        private const val MIN_SCORE = 0.8f
        /** The most the words before may lift a correction's score, in Zipf units (a completion's: Scorer.LIFT_CAP). */
        private const val CONTEXT_PICK = 1.5f
        /** [slip]'s bar: a fix this likely, and this far ahead of the next word it could have been. */
        private const val SLIP_SCORE = 2.4f
        private const val SLIP_SCORE_SHORT = 4.0f
        private const val SLIP_MARGIN = 1.0f
        /** [slip]'s short word: up to this many letters. Only swapped letters are fixed in one. */
        private const val SHORT = 5
        /** Doubled, these are a word stretched for effect, not a slip. */
        private const val VOWELS = "aeiouy"
        /** [runLetter]'s range: a word's edge, a to z, and one for every other character. */
        private const val RUN_LETTERS = 28
        /**
         * The spellings the lists do hold that nobody means: the commonest swaps, typed often enough to have got
         * into the word counts. Every other listed spelling stands.
         */
        private val LISTED_SLIPS = mapOf("teh" to "the", "thier" to "their", "recieve" to "receive")
        /** A learned word counts as moderately common (about one in 30,000 words) when a slip lands near it. */
        private const val LEARNED_ZIPF = 4.0f
        /** Slang ranks as about one in 50,000 words among completions; it is never a target for correcting a slip. */
        private const val SLANG_ZIPF = 3.7f

        /** The name of a set of word lists as one lexicon: the script and the lists' stems ("LATIN:en,pl"); empty with no list. */
        fun key(script: Script, dictionaries: List<String>): String = if (dictionaries.isEmpty()) "" else script.name + ":" + dictionaries.joinToString(",")

        /** [word]'s letters for swipe typing: lower case, plain ([Script.plain]), apostrophes dropped ("What's" is "whats"). */
        fun swipeLetters(script: Script, word: String): String = script.plain(word.lowercase()).filter { it != '\'' && it != '’' }

        private val DEV_WORDS = """
            npm npx pnpm yarn pip pipx venv conda brew apt sudo ssh scp rsync git github gitlab repo repos rebase
            diff diffs linter lint eslint prettier webpack vite deno bun nginx caddy redis postgres postgresql mongo
            mongodb sqlite mysql docker dockerfile kubectl helm terraform async await const enum enums struct structs
            impl impls bool bools int ints str def fn func var vars args kwargs params init lambda lambdas nullable
            uri uris url urls json yaml toml xml csv http https api apis sdk sdks cli gui ui ux regex stdout stderr
            stdin src dev prod config configs env auth oauth jwt sql nosql db dbs ios macos xcode kotlin dart rust
            golang typescript javascript tsx jsx html css scss dom vm vms tcp udp dns cdn ci cd pr prs readme
            changelog localhost backend frontend middleware runtime namespace namespaces typedef boolean booleans
            println printf stringify endpoint endpoints webhook webhooks dotfiles gradle flutter widget widgets
            refactor refactored refactoring deploy deployed deploys repo's monorepo submodule codebase
        """.trim().split(Regex("\\s+")).toSet()
    }
}
