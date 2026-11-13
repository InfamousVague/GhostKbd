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
 * others), plus English's apostrophe-less contractions (dict/en_contractions.tsv: dont → don't) and its wider list
 * (dict/en_wide.tsv, [widen]: the rest of English as it is written now, 87,000 words more, each with what vouches for
 * it; docs/wide-words.md). They are inside the
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
     *
     * With English's wider list read in ([widen]), [words] is the first lists and the wider one as one map
     * ([Listed]), [sorted] holds the words of both, and [marks] says of each word of [sorted] what vouches for it
     * ([SURE], [WRITTEN], [NAMED], [WIDER]) and whether a slip may be read as it ([UNREAD]). Without it [marks] is
     * null and everything is as it was before there was such a list.
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
        /** For each word of [sorted], what English's wider list says of it; null where that list is not read in. */
        val marks: ByteArray? = null,
        /** The first lists' words alone, without the wider list's: [words] itself where that list is not read in. */
        val firstWords: Map<String, Float> = words,
        /** The first lists' words in order, and their frequencies: [sorted] and [sortedZipf] themselves where it is not. */
        val firstSorted: Array<String> = sorted,
        val firstZipf: FloatArray = sortedZipf,
    ) {
        /**
         * The words as a trie, for reading a word by where its taps landed ([TapDecoder]): made as the lists are read.
         * A word marked [UNREAD] is left out of it, so no slip is ever read as that word.
         */
        val trie: WordTrie = WordTrie(sorted, sortedZipf, marks?.let { m -> BooleanArray(m.size) { m[it].toInt() and UNREAD == 0 } })

        /** What English's wider list says of [word] ([SURE] and the rest): 0 where it says nothing, or is not read in. */
        fun mark(word: String): Int {
            val m = marks ?: return 0
            val i = java.util.Arrays.binarySearch(sorted, word)
            return if (i >= 0) m[i].toInt() else 0
        }

        /** [pairRows] read: for a word, each word that follows it (lower case) and its share of what followed. Kept once read. */
        private val pairs = java.util.concurrent.ConcurrentHashMap<String, Map<String, Float>>()

        fun follows(prev: String): Map<String, Float>? {
            pairs[prev]?.let { return it }
            val row = pairRows[prev] ?: return null
            val out = HashMap<String, Float>()
            for (e in row.split(' ')) {
                val c = e.lastIndexOf(':')
                if (c > 0) e.substring(c + 1).toFloatOrNull()?.let { out[e.substring(0, c).lowercase()] = it / 10_000f }
            }
            pairs[prev] = out
            return out
        }
    }

    /**
     * A lexicon's first lists and English's wider list as one map, which is what [Lexicon.words] is once the wider
     * list is read in: a word of the first lists is found as it always was ([first], a hash map), and any other by
     * its place in [sorted], which holds both. So a word of the wider list is a word wherever the lists are asked
     * for one, and costs no map entry of its own (a hundred thousand of them would be 6 MB of entries).
     *
     * Most of what is asked is no word at all (the stems of a slip's readings, letters as they were typed), so each
     * of the wider list's words also sets one bit of [maybe] by its hash (128 KB): letters whose bit is not set are
     * none of them, and eleven in twelve of such questions are answered without halving anything.
     */
    private class Listed(private val first: Map<String, Float>, private val sorted: Array<String>, private val zipf: FloatArray) : AbstractMap<String, Float>() {
        /** [maybe]'s size, a power of two ([WIDE_BITS] as this map was made). */
        private val bits = Integer.highestOneBit(WIDE_BITS.coerceIn(64, 1 shl 26))
        private val maybe = java.util.BitSet(bits).also { set -> for (w in sorted) if (!first.containsKey(w)) set.set(spot(w)) }
        private fun spot(key: String): Int = key.hashCode().let { it xor (it ushr 15) } and (bits - 1)
        /** [key]'s place in [sorted] where it is one of the wider list's words, else a negative number. */
        private fun wider(key: String): Int = if (maybe.get(spot(key))) java.util.Arrays.binarySearch(sorted, key) else -1
        override val size: Int get() = sorted.size
        override fun isEmpty() = sorted.isEmpty()
        override fun containsKey(key: String) = first.containsKey(key) || wider(key) >= 0
        override fun get(key: String): Float? = first[key] ?: wider(key).let { if (it >= 0) zipf[it] else null }
        override val entries: Set<Map.Entry<String, Float>> get() = object : AbstractSet<Map.Entry<String, Float>>() {
            override val size: Int get() = sorted.size
            override fun iterator() = object : Iterator<Map.Entry<String, Float>> {
                private var i = 0
                override fun hasNext() = i < sorted.size
                override fun next(): Map.Entry<String, Float> = java.util.AbstractMap.SimpleImmutableEntry(sorted[i], zipf[i]).also { i++ }
            }
        }
    }

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
    /**
     * The lexicons in [cache] that have English among their lists and have not had its wider list read into them
     * yet ([widen]): the first lists come into use as soon as they are read, and the wider one follows.
     */
    private val widening = HashSet<String>()
    /** The one thread that reads word lists, while there is one to read. */
    private var loader: Thread? = null

    /** Told, on the loader's thread, each time a lexicon that was being read comes into use: there are words now. */
    @Volatile var onLoaded: (() -> Unit)? = null

    @Volatile private var learned: Set<String> = emptySet()
    /** The person's own words, counted from what they type ([NextWords.vocabulary]): treated as learned words are. */
    @Volatile private var own: Set<String> = emptySet()
    @Volatile private var mistaken: Map<String, String> = emptyMap()
    @Volatile private var chosen: Map<String, Map<String, Float>> = emptyMap()
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
            // The person's own words, the first lists' apart; one that English's wider list has is theirs too, unless it is among the listed already.
            val mine = (learned + own).filter { it.isNotEmpty() && !lex.firstWords.containsKey(it) && (lex.marks == null || it !in listed) && letters(it).let { l -> l.isNotEmpty() && l.first() == first && l.last() == last } }
            return if (mine.isEmpty()) listed else listed + mine
        }
        override fun zipf(word: String): Float? {
            val lex = now.lexicon
            return lex.firstWords[word] ?: if (word in learned || word in own) LEARNED_ZIPF else lex.words[word]
        }
        override fun letters(word: String) = swipeLetters(now.board.script, word)
        override fun follows(prev: String): Map<String, Float>? = now.lexicon.follows(prev)
    })
    /** The lexicon the decoder's worked-out shapes are for. */
    private var decoderLexicon: Lexicon? = null

    /**
     * The lexicon asked for last is the one in use, English's wider list and all: for the self-check, which must not
     * ask before the lists are read.
     */
    val settled: Boolean @Synchronized get() = now.lexicon.key == wantedKey && wantedKey !in widening

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
        widening.retainAll(this.keep)
        if (key == wantedKey) {
            now = Now(board, now.lexicon)
            return
        }
        wantedKey = key
        wantedLists = dictionaries
        val ready = if (key.isEmpty()) Lexicon("") else cache[key]
        now = Now(board, ready ?: Lexicon(""))
        if ((ready == null || key in widening) && loader == null) loader = thread(name = "autocorrect-dictionary", isDaemon = true) { loadWanted() }
    }

    /**
     * The loader thread: reads the lexicon that is wanted, then looks again, since another may have been asked for
     * meanwhile (a read that is no longer wanted gives up at its next list). One runs at a time, so turning six
     * languages on in a row never reads six sets at once. A read that fails is forgotten, and the next
     * [setLanguages] tries it again.
     *
     * Where English is among the lists its wider list is read second ([widen]), once the first lists are in use: the
     * first words typed wait no longer for their corrections than they did before there was a wider list, and
     * [onLoaded] is told again when it is in. A wider list that cannot be read leaves the first lists as they are.
     */
    private fun loadWanted() {
        while (true) {
            val (key, lists, script) = synchronized(this) {
                if (wantedKey.isEmpty() || (cache.containsKey(wantedKey) && wantedKey !in widening)) { loader = null; return }
                Triple(wantedKey, wantedLists, wantedBoard.script)
            }
            val gone = { synchronized(this) { wantedKey != key } }
            var first = synchronized(this) { cache[key] }
            if (first == null) {
                val made = runCatching { load(key, lists, script, gone) }.getOrNull()
                val inUse = synchronized(this) {
                    when {
                        wantedKey != key -> { if (made != null && key in keep) { cache[key] = made; if (wider(lists, made)) widening += key }; false }
                        made != null -> { cache[key] = made; if (wider(lists, made)) widening += key; now = Now(wantedBoard, made); true }
                        else -> { wantedKey = ""; loader = null; return }
                    }
                }
                if (!inUse) continue
                runCatching { onLoaded?.invoke() }
                first = made
            }
            val base = first ?: continue
            if (!synchronized(this) { key in widening && wantedKey == key }) continue
            // Null where another lexicon was asked for meanwhile: this one keeps its place in [widening] for next time.
            val whole = runCatching { widen(base, gone) }.getOrDefault(base)
            val inUse = synchronized(this) {
                if (whole == null) false else {
                    widening -= key
                    if (key in keep) cache[key] = whole
                    if (wantedKey == key && whole !== base) { now = Now(wantedBoard, whole); true } else false
                }
            }
            if (inUse) runCatching { onLoaded?.invoke() }
        }
    }

    /**
     * Whether [lists], read into [lex], have a wider list to be read after them: English's, while [WIDE] is on, and
     * never into a [Lexicon.cautious] one. Such a lexicon (a list beyond the first six is loaded) reads no taps, and
     * English beside it is held exactly as it was before there was a wider list.
     */
    private fun wider(lists: List<String>, lex: Lexicon) = WIDE > 0f && "en" in lists && !lex.cautious

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
     * [lex], which has English among its lists, with English's wider list read into it: assets/dict/en_wide.tsv,
     * the words of English as it is written now that en.tsv's 40,000 do not reach (tool/wide_words.py makes it, and
     * says what is in it). Each line is a word, its Zipf frequency and what vouches for it: `d` a dictionary, at the
     * size a spelling checker is made from ([SURE]); `l` a larger dictionary only; `w` writing only ([WRITTEN]); and
     * `n` beside one of those where it is a name ([NAMED]). A line whose word the first lists have says what vouches
     * for that word and nothing else: en.tsv's own frequency stands.
     *
     * The words join [Lexicon.sorted], so each is a word wherever the lists are asked ([Listed]) and a completion
     * where the first lists have few. Those a dictionary vouches for join the trie, and are readings of a slip
     * ([WIDE_READ]); the commoner of them are words a stroke may be ([WIDE_SWIPE]). A word only writing vouches for
     * is known when typed and is no reading of anything unless [WIDE_READ_WRITTEN] lets it be: wordfreq lists
     * misspellings beside names, and a slip must never be mended into one. docs/wide-words.md says what each of
     * those was measured to be worth, and what it costs.
     *
     * Returns [lex] itself where the asset is not there (English is then exactly as it was before there was a wider
     * list), and null where [gone] says another lexicon is wanted now. Nothing here opens a connection.
     */
    private fun widen(lex: Lexicon, gone: () -> Boolean = { false }): Lexicon? {
        if (lex.marks != null || lex.cautious) return lex
        val stream = open("dict/en_wide.tsv") ?: return lex
        val first = lex.words
        var words = arrayOfNulls<String>(1 shl 17)
        var zipfs = FloatArray(1 shl 17)
        var marks = ByteArray(1 shl 17)
        var n = 0
        var inOrder = true
        // What the list says of the words the first lists have.
        val said = HashMap<String, Byte>(1 shl 15)
        var read = 0
        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                // A language switch made meanwhile is not kept waiting for the rest of the file.
                if (++read and 4095 == 0 && gone()) return null
                val tab = line.indexOf('\t')
                val tab2 = if (tab > 0) line.indexOf('\t', tab + 1) else -1
                if (tab2 < 0) continue
                val word = line.substring(0, tab)
                var mark = 0
                for (i in tab2 + 1 until line.length) when (line[i]) { 'd' -> mark = mark or SURE; 'w' -> mark = mark or WRITTEN; 'n' -> mark = mark or NAMED }
                if (first.containsKey(word)) { if (mark != 0) said[word] = mark.toByte(); continue }
                // None of these words is common (the first list would have had it): a file that says otherwise is not believed.
                val zipf = minOf(line.substring(tab + 1, tab2).toFloatOrNull() ?: continue, COMMON - 0.01f)
                // Plain letters with at most one apostrophe inside, as the tool writes them: nothing else is taken.
                if (zipf < WIDE_LEAST || word.isEmpty() || !word.all { it in 'a'..'z' || it == '\'' } || word[0] == '\'' || word[word.length - 1] == '\'' || word.indexOf('\'') != word.lastIndexOf('\'')) continue
                if (n == words.size) { words = words.copyOf(n * 2); zipfs = zipfs.copyOf(n * 2); marks = marks.copyOf(n * 2) }
                if (n > 0 && words[n - 1]!! >= word) inOrder = false
                val unread = zipf < (if (mark and WRITTEN != 0) WIDE_READ_WRITTEN else WIDE_READ)
                words[n] = word; zipfs[n] = zipf; marks[n] = (mark or WIDER or (if (unread) UNREAD else 0)).toByte()
                n++
            }
        }
        if (gone()) return null
        // The tool writes the words in order, so they are merged with the first lists' in one pass; a file in
        // another order is put in order first, and a word it has twice is taken once.
        var order = IntArray(n) { it }
        if (!inOrder) {
            val boxed = Array(n) { it }
            boxed.sortWith { a, b -> words[a]!!.compareTo(words[b]!!) }
            var m = 0
            for (i in boxed) if (m == 0 || words[order[m - 1]] != words[i]) order[m++] = i
            order = order.copyOf(m)
        }
        val was = lex.sorted
        val total = was.size + order.size
        val sorted = arrayOfNulls<String>(total)
        val sortedZipf = FloatArray(total)
        val sortedMarks = ByteArray(total)
        var i = 0
        var j = 0
        for (k in 0 until total) {
            val w = if (j < order.size) words[order[j]]!! else null
            if (w == null || (i < was.size && was[i] < w)) {
                sorted[k] = was[i]; sortedZipf[k] = lex.sortedZipf[i]; sortedMarks[k] = said[was[i]] ?: 0; i++
            } else {
                sorted[k] = w; sortedZipf[k] = zipfs[order[j]]; sortedMarks[k] = marks[order[j]]; j++
            }
        }
        if (gone()) return null
        // Swipe typing's index: the lists the first words are in are left as they are (a stroke may be reading
        // them), and a pair of ends that gains words gets a list of its own.
        val more = HashMap<String, ArrayList<String>>()
        for (at in order) {
            val word = words[at]!!
            if (marks[at].toInt() and UNREAD != 0 || zipfs[at] < WIDE_SWIPE) continue
            val glide = if ('\'' in word) word.replace("'", "") else word
            if (glide.length < 2 || glide.length > 20) continue
            more.getOrPut("${glide[0]}${glide[glide.length - 1]}") { ArrayList() }.add(word)
        }
        val ends = HashMap<String, List<String>>(lex.byEnds)
        for ((pair, list) in more) ends[pair] = ends[pair].orEmpty() + list
        @Suppress("UNCHECKED_CAST")
        val all = sorted as Array<String>
        return Lexicon(
            lex.key, Listed(first, all, sortedZipf), lex.folded, lex.contractions, all, sortedZipf, lex.plainKeys, lex.plainWords, lex.plainZipf,
            ends, lex.givesWay, lex.commonest, lex.sharedI, lex.cautious, lex.pairRows, sortedMarks, first, was, lex.sortedZipf,
        )
    }

    /**
     * Loads [dictionaries] for [board] on the calling thread and returns once they are in: for a worker that needs the
     * answers now and has no keyboard to borrow them from (Mockingbird cleaning its samples from Settings).
     */
    fun loadNow(dictionaries: List<String>, board: Board) {
        val key = key(board.script, dictionaries)
        val first = if (key.isEmpty()) Lexicon("") else load(key, dictionaries, board.script) ?: Lexicon("")
        // English's wider list with them, read here and now as the rest is.
        val made = if (key.isNotEmpty() && wider(dictionaries, first)) runCatching { widen(first) }.getOrNull() ?: first else first
        synchronized(this) {
            wantedKey = key; wantedLists = dictionaries; wantedBoard = board; keep = setOf(key)
            // Only this key is read whole here: another English lexicon still cached keeps its place to be widened.
            widening -= key
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

    /**
     * The person's own words, as next-word prediction has counted them (typed three times or more, in no list):
     * never corrected, targets for correcting slips of them, and words to swipe, as learned words are.
     */
    fun setOwn(words: Set<String>) {
        if (words == own) return
        own = words
        synchronized(decoder) { decoder.clear() }
    }

    /**
     * The person's own mistakes ([Mistakes]: Matt, 2026-11-10: "I want the models to be trained off of previous
     * mistakes"): what a typed word (lower case) was put right to, often enough to go by. Such a word is mended to
     * that whatever its taps say, unless it is itself a common word or one the person kept; a common word's
     * remembered meaning counts [REMEMBERED] more among the readings of its taps ([byTaps]).
     */
    fun setMistakes(typed: Map<String, String>) { mistaken = typed }

    /**
     * The swipe readings the person has chosen over the decoder's first one, by that first reading in lower case, and
     * how often; the first reading counts under its own name where it was the one meant after all ([byChoice]).
     */
    fun setChosen(readings: Map<String, Map<String, Float>>) { chosen = readings }

    /**
     * A stroke's readings [found] (best first, as [swipe] gives them) with the one the person means by such a stroke
     * put first: a reading they have picked over this first one [MistakeMemory.TAKEN] times, and
     * [MistakeMemory.LEAD] more often than the first was meant itself. Only ever one of the readings the stroke gave.
     */
    fun byChoice(found: List<String>): List<String> {
        val first = found.firstOrNull() ?: return found
        val row = chosen[first.lowercase(now.board.locale)] ?: return found
        val locale = now.board.locale
        val stood = row[first.lowercase(locale)] ?: 0f
        var best: String? = null
        var most = 0f
        for (w in found) {
            if (w === first) continue
            val n = row[w.lowercase(locale)] ?: continue
            if (n > most) { most = n; best = w }
        }
        val pick = best ?: return found
        if (most < MistakeMemory.TAKEN || most - stood < MistakeMemory.LEAD) return found
        return listOf(pick) + found.filter { it != pick }
    }

    /**
     * What the person's mistakes say [lower] was meant to be, or null: nothing is remembered of it, they kept it as
     * a word since, or it is a common word here (which only its taps may change).
     */
    private fun remembered(now: Now, lower: String): String? {
        val meant = mistaken[lower] ?: return null
        if (lower in learned || lower in slang) return null
        val lex = now.lexicon
        if (lex.contractions.containsKey(lower)) return null
        val known = lex.words[lower]
        if (known != null && known >= COMMON) return null
        return if (' ' in meant) meant.split(' ').joinToString(" ") { capitalI(now, it) } else capitalI(now, meant)
    }

    /** Texting slang on or off (Matt, 2026-11-09: "add slang options to autocorrect like sybau and lmfao and ts"). */
    fun setSlang(on: Boolean) {
        if (on == slangOn) return
        slangOn = on
        if (!on) { slang = emptySet(); return }
        thread(name = "autocorrect-slang", isDaemon = true) { if (slangOn) slang = slangList }
    }

    /** A word autocorrect never changes: one the person kept or types often, or slang while that is on. */
    private fun kept(lower: String) = lower in learned || lower in slang || lower in own

    /**
     * Whether [lower] is a word here: in a loaded word list, one of English's contractions, a word the person kept,
     * or slang while that is on. Next-word prediction offers such a word back sooner than one nothing knows.
     */
    fun isWord(lower: String): Boolean {
        val lexicon = now.lexicon
        return lexicon.words.containsKey(lower) || lexicon.contractions.containsKey(lower) || kept(lower) || isPossessive(lexicon, lower)
    }

    /**
     * The word [lower] is the possessive of ("plan" for "plan's"), where English's wider list is read in and
     * [lower] is two letters or more with 's after them; null otherwise. Asked only of letters no list has: the
     * lists' own possessives (tom's, world's) and contractions (it's, let's) are words as they stand.
     */
    private fun possessed(lexicon: Lexicon, lower: String): String? {
        val n = lower.length
        if (lexicon.marks == null || POSSESSIVE <= 0f || n < 4 || lower[n - 1] != 's' || (lower[n - 2] != '\'' && lower[n - 2] != '’')) return null
        for (i in 0 until n - 2) if (!lower[i].isLetter()) return null
        return lower.substring(0, n - 2)
    }

    /** Whether [lower] is the possessive of a word of the lists that they do not have themselves (yurt's): known by its word. */
    private fun isPossessive(lexicon: Lexicon, lower: String): Boolean = possessed(lexicon, lower)?.let { lexicon.words.containsKey(it) } == true

    /**
     * Whether [lower] is one of the commonest words of a word list the app ships: Zipf [REPORTABLE] and up, about
     * seven thousand words of English. The only words an anonymous typing report ever carries ([Reports]): the lists
     * themselves hold rarer names and terms (kira, nguyen, the names of illnesses), and those stay on the phone.
     * The very commonest first names (john, sarah) are among the common words, as they are in any text.
     */
    fun isCommonWord(lower: String): Boolean = (now.lexicon.words[lower] ?: 0f) >= REPORTABLE

    /**
     * [isWord] without the person's own words: what "in no word list" means when those are worked out. English's
     * wider list is not counted either: a word of it that the person types three times is theirs as it would have
     * been before there was such a list (their name, their town, the terms of their work), so it is never changed,
     * it is what a slip of it is mended to, and it is swiped, however rare the list says it is.
     */
    fun isListed(lower: String): Boolean {
        val lexicon = now.lexicon
        return lexicon.firstWords.containsKey(lower) || lexicon.contractions.containsKey(lower) || lower in learned || lower in slang
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
     * [taps] are where the fingers landed for it, where the keyboard knows (see [byTaps]): the fix may then be two
     * words ("thankyou" is "thank you").
     */
    fun correct(typed: String, scorer: Scorer? = null, taps: Taps? = null): String? {
        val now = now
        if (now.lexicon.words.isEmpty() || typed.isEmpty() || typed.length > 24) return null
        // An acronym or a deliberate capitalised name of two letters or more is left as typed.
        if (typed.length > 1 && typed.all { it.isUpperCase() }) return null
        // camelCase, iOS, McDonald: a capital after the first letter is deliberate.
        if (typed.drop(1).any { it.isUpperCase() }) return null
        val lower = typed.lowercase(now.board.locale)
        // One of the person's own often-typed words is left alone, unless they have been putting it right by hand.
        if ((kept(lower) && remembered(now, lower) == null) || eszettWord(lower, now.lexicon.words)) return null
        val fixed = best(now, lower, scorer, taps?.takeIf { it.xs.size == typed.length })?.takeIf { it.sure }?.word ?: return null
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
    fun suggest(typed: String, scorer: Scorer? = null, taps: Taps? = null): Suggestions {
        val now = now
        if (now.lexicon.words.isEmpty() || typed.isEmpty() || typed.length > 24) return Suggestions(emptyList(), null, false)
        val locale = now.board.locale
        val lower = typed.lowercase(locale)
        val words = now.lexicon.words
        val contractions = now.lexicon.contractions
        val eszett = eszettWord(lower, words)
        val isWord = kept(lower) || (words[lower] ?: 0f) > 0f || contractions.containsKey(lower) || eszett || isPossessive(now.lexicon, lower)
        val mend = if ((kept(lower) && remembered(now, lower) == null) || eszett) null else best(now, lower, scorer, taps?.takeIf { it.xs.size == typed.length })
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
        val arr = now.lexicon.firstSorted
        val zipfs = now.lexicon.firstZipf
        var lo = firstFrom(arr, lower)
        var scanned = 0
        while (lo < arr.size && arr[lo].startsWith(lower) && scanned < 30_000) {
            val cand = arr[lo]
            if (cand != lower) begun.offer(cand, if (slangNow.isNotEmpty() && cand in slangNow) maxOf(zipfs[lo], SLANG_ZIPF) else zipfs[lo])
            lo++; scanned++
        }
        // English's wider list: every word of it is rarer than en.tsv's rarest, so its words are looked through only
        // where the first lists have fewer words to offer than are kept ("yur" offers "yurt"). A key press that
        // begins a word is then as quick as it was: a letter has three times the words under it with that list.
        val marks = now.lexicon.marks
        if (marks != null && !begun.full) {
            val all = now.lexicon.sorted
            val allZipf = now.lexicon.sortedZipf
            lo = firstFrom(all, lower)
            scanned = 0
            while (lo < all.size && all[lo].startsWith(lower) && scanned < 30_000) {
                if (marks[lo].toInt() and WIDER != 0 && all[lo] != lower) begun.offer(all[lo], allZipf[lo])
                lo++; scanned++
            }
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
        for (l in own) if (l.startsWith(lower) && l != lower && l !in learned) top += l to LEARNED_ZIPF
        for (l in slang) if (l.startsWith(lower) && l != lower && !words.containsKey(l)) top += l to SLANG_ZIPF
        if (scorer != null) inContext(now, lower, scorer, top)
        // The words the taps so far may be the start of, a slip or two allowed ([TapDecoder.finish]): ranked as the
        // others are, less what the slips cost, so they lead only where the taps make them likely.
        val noisy = taps != null && taps.xs.size == typed.length && !now.lexicon.cautious && TAPS
        if (noisy) runCatching {
            top.sortByDescending { it.second }
            val least = (top.getOrNull(4)?.second ?: 0f).coerceAtLeast(FINISH_LEAST)
            val decoder = tapDecoder(now, taps!!)
            decoder.load(lower, taps)
            for (h in decoder.finish(least - (if (scorer == null) 0f else CONTEXT_PICK))) {
                val word = now.lexicon.sorted[h.index]
                if (word == lower || h.cost <= 0f) continue
                val zipf = now.lexicon.sortedZipf[h.index]
                val lift = if (scorer == null) 0f else minOf(scorer.boost(word, zipf), CONTEXT_PICK)
                top += word to zipf + lift - h.cost
            }
        }
        top.sortByDescending { it.second }
        if (scorer == null && !noisy) {
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

        /** As many are kept as there is room for. */
        val full: Boolean get() = size == n

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
    fun swipe(path: List<android.graphics.PointF>, keys: Map<Char, android.graphics.PointF>, keyW: Float, before: String? = null, scorer: Scorer? = null): List<String> =
        swipePlain(path.map { it.x to it.y }, keys.mapValues { (_, p) -> p.x to p.y }, keyW, before, scorer)

    /**
     * [swipe] in plain numbers, as the accuracy replay (src/test AccuracyReplay) calls it off the device. With a
     * [scorer] (next-word prediction is on) the stroke's first word is weighed by what is likely where the cursor
     * stands: the two words before it and the person's own habits, where [before] alone goes by the starter tables'
     * one-word rows; and a word the person types often counts as that common.
     */
    fun swipePlain(path: List<Pair<Float, Float>>, keys: Map<Char, Pair<Float, Float>>, keyW: Float, before: String? = null, scorer: Scorer? = null): List<String> {
        val now = now
        if (now.lexicon.words.isEmpty() || path.size < 2 || keyW <= 0f) return emptyList()
        return synchronized(decoder) {
            // Another board's words: the shapes worked out for the last lexicon are no use.
            if (now.lexicon !== decoderLexicon) { decoder.clear(); decoderLexicon = now.lexicon; swipeKeys = emptyMap() }
            // The same keys keep the same map, so the decoder keeps the words' shapes it has worked out for them.
            if (keys != swipeKeys) {
                swipeKeys = HashMap(keys)
                warm(swipeKeys, now.lexicon)
            }
            val first: ((String, Float) -> Float)? = if (scorer == null) null else { word, zipf ->
                val own = maxOf(zipf, scorer.personalZipf(word, true))
                kotlin.math.log10(Math.pow(10.0, (own - 9f).toDouble()) + SwipeDecoder.PAIRS * scorer.share(word)).toFloat()
            }
            decoder.decode(path, swipeKeys, keyW, before, first = first)
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
    private fun best(now: Now, lower: String, scorer: Scorer? = null, taps: Taps? = null, whole: Boolean = false): Fix? {
        if (lower == "i" && capitalises(now)) return Fix("I", true)
        // A mistake the person has put right before, the same way each time: it is that again.
        remembered(now, lower)?.let { return Fix(it, true) }
        val words = now.lexicon.words
        val folded = now.lexicon.folded
        val contractions = now.lexicon.contractions
        // Where the taps are known and the lists are the first six languages', the word is read by them ([byTaps]).
        val read = taps != null && !now.lexicon.cautious && TAPS
        contractions[lower]?.let { c ->
            if (read) byTaps(now, lower, c, words[c], scorer, taps)?.let { return it }
            return Fix(capitalI(now, c), true)
        }
        val known = words[lower]
        // A possessive no list has (plan's, yurt's, Okafor's) is its word with 's after it ([possessive]).
        if (known == null && !whole) possessed(now.lexicon, lower)?.let { return possessive(now, lower, it, scorer, taps) }
        // Typed without its accents: the accented word, unless the plain spelling is itself as common. A listed plain
        // spelling gives way only on a board where the accent is a long-press away: typed on a board that has the
        // letter as a key (Swedish far beside får, Turkish yas beside yaş), the plain word was meant.
        folded[lower]?.let { accented ->
            if (known == null || known < COMMON || (lower in now.lexicon.givesWay && !keyed(now.board, lower, accented))) return Fix(accented, true)
        }
        if (read) {
            byTaps(now, lower, lower, known, scorer, taps)?.let { return it }
            // Nothing the taps fit better: a listed word stands, with English's capital I where it has one.
            return if (known != null) capitalI(now, lower).takeIf { it != lower }?.let { Fix(it, true) } else null
        }
        // A common word is never changed; a rare one only for a far commoner word one cheap slip away.
        if (known != null && known >= COMMON) return capitalI(now, lower).takeIf { it != lower }?.let { Fix(it, true) }
        // With [SURE_UNTAPPED] on, nor is a rare word that a dictionary vouches for; off, it is held as any rare word.
        if (known != null && SURE_UNTAPPED > 0f && now.lexicon.mark(lower) and SURE != 0) return null
        val pick = pick(now, lower, known, scorer) ?: return null
        if (pick.score < MIN_SCORE) return null
        val sure = !now.lexicon.cautious || (pick.score >= CAUTIOUS_SCORE && !ending(now.board, lower, pick.from) && !inflected(words, lower, pick.from))
        return Fix(capitalI(now, pick.word), sure)
    }

    /**
     * What [lower], a possessive no list has, should be; null to leave it. [stem] is the word it is the possessive
     * of ("plan" for "plan's"), which may be no word either ("plsn's").
     *
     * Where [stem] is a word, the possessive is one: the word is judged as it would be alone, by its own taps, and
     * whatever it is left as or mended to keeps the 's. So "plan's" stands (read whole, its apostrophe was a tap
     * too many and it came out "plans"), and "definately's" is "definitely's". Where [stem] is no word the
     * letters are read whole first, as they always were, and that reading is taken where it has an apostrophe of
     * its own (the lists have possessives too: "tem's" is "tom's"). Where it has none, the apostrophe that was
     * typed is kept if the stem can be mended as it would be alone ("plsn's" is "plan's", not "plans").
     */
    private fun possessive(now: Now, lower: String, stem: String, scorer: Scorer?, taps: Taps?): Fix? {
        if (kept(stem)) return null
        val whole = if (now.lexicon.words.containsKey(stem)) null else best(now, lower, scorer, taps, whole = true)
        if (whole != null && whole.word.any { it == '\'' || it == '’' }) return whole
        val of = best(now, stem, scorer, taps?.let { Taps(it.xs.copyOf(stem.length), it.ys.copyOf(stem.length), it.keys, it.keyW, it.space) })
        return if (of != null && ' ' !in of.word) Fix(of.word + lower.substring(stem.length), of.sure) else whole
    }

    /**
     * The word [lower] was meant to be, read by where its taps landed ([TapDecoder]); null to leave it as typed.
     * [known] is how common the typed word itself is, where the lists have it, and [stands] the word it stands as
     * (itself, or the contraction it is typed for: "im" stands as "i'm").
     *
     * Every reading is worth its word's frequency, plus what the words before lift it by (at most [CONTEXT_PICK]),
     * less what its slips cost. The typed word is one of the readings, with no slips:
     * - a word no list has is worth [UNKNOWN], or [UNKNOWN_ODD] where it does not look like a word at all ([odd]):
     *   any reading worth more replaces it;
     * - a rare listed word gives way to a reading worth [MARGIN_RARE] more than it;
     * - a rare word that a dictionary vouches for (English's wider list says which, [SURE]) gives way only to a
     *   reading worth [SURE_MARGIN] more than the word itself: "yurt" typed on its own keys is "yurt";
     * - a common word gives way only to one worth [MARGIN_COMMON] more, about a hundred to one, and only by slips of
     *   the thumb: "br" with its r tapped on the line between e and r is "be", and with the r tapped in the middle
     *   of its key it is "br".
     * The readings are the lists' words ([TapDecoder.search]), the person's own, and two words run together
     * ([TapDecoder.joins], worth at least [JOIN_BAR]) where the typed one is no common word.
     */
    /** A reader of taps over the lexicon in use: a letter's key is its own, or its plain letter's where the board keeps it behind a long-press. */
    private fun tapDecoder(now: Now, taps: Taps): TapDecoder {
        val lex = now.lexicon
        val script = now.board.script
        val keys = taps.keys
        return TapDecoder(lex.sorted, lex.sortedZipf, lex.trie, { c ->
            if (keys.containsKey(c)) c else script.plain(c.toString()).let { p -> if (p.length == 1 && keys.containsKey(p[0])) p[0] else 0.toChar() }
        }, now.board.neighbours)
    }

    private fun byTaps(now: Now, lower: String, stands: String, known: Float?, scorer: Scorer?, taps: Taps): Fix? {
        val lex = now.lexicon
        val words = lex.words
        val script = now.board.script
        val decoder = tapDecoder(now, taps)
        decoder.load(lower, taps)
        fun lift(word: String, zipf: Float) = if (scorer == null) 0f else minOf(scorer.boost(word, zipf), CONTEXT_PICK)
        val common = known != null && known >= COMMON
        // A rare word that a dictionary vouches for (English's wider list says which): a word, not a misspelling.
        val mark = if (known != null && !common) lex.mark(lower) else 0
        val sure = known != null && !common && SURE_STANDS > 0f && mark and SURE != 0
        // Any other rare listed word: only writing vouches for it, or a larger dictionary's less sure pages.
        val doubtful = known != null && !common && !sure
        // One of those that only the wider list has: it was letters no list has before, and is held no less ([WIDE_HOLD]).
        val held = doubtful && WIDE_HOLD > 0f && mark and (WIDER or SURE) == WIDER
        // What the typed word is worth where no list has it: little where it does not look like a word, less where
        // it is long (a long word a letter from a common one is that word misspelled, far more often than a name).
        val unknown = if (known != null) UNKNOWN else unlisted(lower)
        // A rare listed word that no dictionary vouches for is as often a common misspelling as a word (definately,
        // occured): it gives way to a word [RARE_GAP] commoner that is a slip or a misspelling away ([RARE_BUDGET]),
        // whatever that leaves it worth. One a dictionary vouches for is worth its own frequency, and gives way only
        // to a reading worth [SURE_MARGIN] more than that: "yurt" typed on its own keys stands whatever is a key
        // away, and "vases" typed with its v on the line to c is "cases".
        val bar = when {
            known == null -> unknown
            sure -> known + lift(stands, known) + SURE_MARGIN
            held -> unlisted(lower)
            !common -> Float.NEGATIVE_INFINITY
            else -> known + lift(stands, known) + MARGIN_COMMON
        }
        var bestWord: String? = null
        var bestScore = Float.NEGATIVE_INFINITY
        fun offer(word: String, score: Float, least: Float = bar) {
            if (word == lower || word == stands || score < least || score <= bestScore) return
            bestWord = word; bestScore = score
        }
        // A common word is only ever read as another by a slip of the thumb or two: no misspelling.
        val budget = when { common || sure -> LISTED_BUDGET; known != null -> RARE_BUDGET; else -> TapDecoder.BUDGET }
        val floor = if (known != null && doubtful) known + RARE_GAP - budget else bar - (if (scorer == null) 0f else CONTEXT_PICK)
        for (h in decoder.search(floor, budget, 0, slipsOnly = common)) {
            val word = lex.sorted[h.index]
            if (known != null && doubtful && lex.sortedZipf[h.index] < known + RARE_GAP) continue
            // Typed plainly with a slip: the plain word where there is one (esra is esta, not está).
            val plain = script.plain(word)
            if (plain != word && words.containsKey(plain)) continue
            val zipf = lex.sortedZipf[h.index]
            // A listed word with an ending of its own (chanced, dueled) is a word: only a far likelier reading takes it.
            val least = if (known == null && unknown < UNKNOWN && inflected(words, lower, word)) UNKNOWN else bar
            offer(word, zipf + lift(word, zipf) - h.cost, least)
        }
        // The person's own words: never in the trie, so each is held up against the taps. (One that English's
        // wider list has may be in the trie as the rare word that list says it is: here it is the person's.)
        for (l in learned + own) {
            if (lex.firstWords.containsKey(l) || kotlin.math.abs(l.length - lower.length) > 2) continue
            if (known != null && !sure && LEARNED_ZIPF < known + RARE_GAP) continue
            val cost = decoder.align(l, 0, budget)
            if (!cost.isInfinite()) offer(l, LEARNED_ZIPF + lift(l, LEARNED_ZIPF) - cost)
        }
        // What the person has put this very word right to before (a common word typed for another: form for from):
        // held up against the taps like their own words, and worth [REMEMBERED] more for having been meant before.
        mistaken[lower]?.takeIf { ' ' !in it && lower !in learned && lower !in slang }?.let { meant ->
            val m = meant.lowercase(now.board.locale)
            val zipf = words[m] ?: if (kept(m)) LEARNED_ZIPF else return@let
            val cost = decoder.align(m, 0, TapDecoder.BUDGET)
            if (!cost.isInfinite()) offer(meant, zipf + lift(m, zipf) + REMEMBERED - cost)
        }
        // Two words run together, where what was typed is no common word.
        if (!common && lower.length >= 3 && JOINS) {
            val joinBar = if (known == null) minOf(bar, JOIN_BAR) else known + MARGIN_RARE
            val sorted = lex.sorted
            val zipfs = lex.sortedZipf
            // The least share a first word's row lists: a second word the row does not list follows it less often
            // than that, however common it is elsewhere ("the" after "i": never, for all that "the" is everywhere).
            val least = HashMap<Int, Float>()
            fun worth(a: Int, b: Int): Float {
                val z = zipfs[b]
                if (sorted[b].length < 2 && z < SwipeDecoder.ONE_LETTER) return Float.NEGATIVE_INFINITY
                val row = lex.follows(sorted[a])
                val share = row?.get(sorted[b]) ?: 0f
                if (share > 0f) return 9f + kotlin.math.log10(Math.pow(10.0, (z - 9f).toDouble()) + share).toFloat()
                val unseen = z - JOIN_UNSEEN
                if (row == null || row.size < JOIN_FULL_ROW) return unseen
                val floor = least.getOrPut(a) { 9f + kotlin.math.log10(row.values.min() * JOIN_UNLISTED) }
                return minOf(unseen, floor)
            }
            val joins = decoder.joins(
                joinBar,
                firsts = { i -> zipfs[i] >= JOIN_FIRST && (sorted[i].length >= 2 || zipfs[i] >= SwipeDecoder.ONE_LETTER) },
                second = ::worth,
                followers = { a ->
                    val row = lex.follows(sorted[a])
                    if (row == null) IntArray(0) else row.keys.mapNotNull { w -> java.util.Arrays.binarySearch(sorted, w).takeIf { it >= 0 } }.toIntArray()
                },
            )
            for (j in joins) {
                val a = sorted[j.first]; val b = sorted[j.second]
                val first = zipfs[j.first] + lift(a, zipfs[j.first])
                offer(capitalI(now, a) + " " + capitalI(now, b), first + worth(j.first, j.second) - 9f - j.cost, joinBar)
            }
        }
        return bestWord?.let { Fix(if (' ' in it) it else capitalI(now, it), true) }
    }

    /**
     * The one word that a tap on the space bar cut in two, or null where the two stand as they are. A thumb reaching
     * for the bottom row (c, v, b, n, m) lands on the top of the bar as often as on the key above it, and the word
     * is then two scraps with a space between: "bei g" for "being", "ha e" for "have", or with nothing after it,
     * "o " for "on" (and a second space then made it "o. "). 37 of the 326 words Gboard got and this did not on
     * 2026-11-11 were cut so (docs/versus.md).
     *
     * [first] and [second] are the two scraps as they were typed ([second] is empty where the tap was the word's
     * last letter and nothing has been typed since), [stands] is what stands in the field for the first (itself, or
     * what it was mended to at the space), and [taps] is where every tap landed, the space bar's own among them at
     * [first]'s length (xs and ys are [first], a space and [second] long). [scorer] is the place the first scrap
     * stands in.
     *
     * The letters and the bar's tap are read as one word, as [byTaps] reads any ([TapDecoder] takes the bar's tap as
     * a letter for how much nearer the bar's middle line it was than that letter's key: next to nothing just under
     * the bar's top edge, a great deal in its middle). That reading is held against what stands. Two scraps are
     * worth the first as it stands and the second after it, less nine for being two words, as a join is counted,
     * and the one word must be worth [REJOIN_MARGIN] more: two real words stand however high on the bar the space
     * was tapped ("is a", "to go"), and two scraps that are no words give way even to a tap in the bar's middle
     * ("bei g"). One scrap with nothing after it is a word typed with one tap more, and is held as [byTaps] holds
     * one: letters no list has are worth what they are worth there and give way to any reading worth more, and a
     * listed word gives way only to one worth [REJOIN_MARGIN] more ("the" and two spaces is a full stop, not
     * "then"; "o" and two spaces after "sit" is "on").
     */
    fun rejoin(first: String, second: String, stands: String, scorer: Scorer? = null, taps: Taps): String? {
        val now = now
        val lex = now.lexicon
        if (!TAPS || !REJOIN || lex.cautious || lex.words.isEmpty() || first.isEmpty()) return null
        val typed = "$first $second"
        if (typed.length > 24 || taps.xs.size != typed.length || !taps.has(first.length) || taps.space == null) return null
        // Letters only, and no capital after a scrap's first letter: an acronym or a camelCase name is left alone.
        if (!first.all { it.isLetter() } || !second.all { it.isLetter() } || first.drop(1).any { it.isUpperCase() } || second.any { it.isUpperCase() }) return null
        val locale = now.board.locale
        val words = lex.words
        fun lift(word: String, zipf: Float) = if (scorer == null) 0f else minOf(scorer.boost(word, zipf), CONTEXT_PICK)
        fun alone(word: String): Float {
            val l = word.lowercase(locale)
            return words[l] ?: lex.contractions[l]?.let { words[it] } ?: if (kept(l)) LEARNED_ZIPF else unlisted(l)
        }
        val one = alone(stands).let { it + lift(stands.lowercase(locale), it) }
        val asItStands = if (second.isEmpty()) one else one + alone(second) - 9f
        val decoder = tapDecoder(now, taps)
        decoder.load(typed.lowercase(locale), taps)
        var best: String? = null
        var bestScore = asItStands + (if (second.isEmpty() && !isWord(stands.lowercase(locale))) 0f else REJOIN_MARGIN)
        for (h in decoder.search(bestScore - (if (scorer == null) 0f else CONTEXT_PICK), TapDecoder.BUDGET)) {
            val word = lex.sorted[h.index]
            val zipf = lex.sortedZipf[h.index]
            val score = zipf + lift(word, zipf) - h.cost
            if (score > bestScore) { best = word; bestScore = score }
        }
        return best?.let { recase(locale, first, capitalI(now, it)) }
    }

    /** What letters no list has are worth against a reading of their taps: see [byTaps]. */
    private fun unlisted(lower: String): Float = when {
        lower.length >= 4 && odd(lower) -> UNKNOWN_ODD
        lower.length >= LONG_WORD.toInt() -> UNKNOWN_LONG
        else -> UNKNOWN
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
        // With no taps to go by, a slip is read as a word of the first lists only ([WIDE_UNTAPPED]): the wider
        // list's words are rare, and one of them a neighbouring key from letters no list has is seldom what was meant.
        val words = if (WIDE_UNTAPPED > 0f) now.lexicon.words else now.lexicon.firstWords
        val folded = now.lexicon.folded
        val contractions = now.lexicon.contractions
        var bestWord: String? = null
        var bestFrom = ""
        var bestScore = Float.NEGATIVE_INFINITY
        var bestCost = 0f
        var next = Float.NEGATIVE_INFINITY
        for ((candidate, cost) in candidates(now.board, lower)) {
            val zipf = words[candidate] ?: contractions[candidate]?.let { words[it] }
                ?: (if (candidate in learned || candidate in own) LEARNED_ZIPF else null) ?: folded[candidate]?.let { words[it] } ?: continue
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
        // The first lists' words: none of English's wider list is common, and it comes in without these being made again.
        val loaded = now.lexicon.firstWords
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
        /** Reading a word by where its taps landed ([byTaps]), and two words run together: off only for the benchmark's comparisons. */
        @JvmField var TAPS = true
        @JvmField var JOINS = true
        /** Putting a word together again that a tap on the space bar cut in two ([rejoin]), and how much more than what stands the one word must be worth. */
        @JvmField var REJOIN = true
        @JvmField var REJOIN_MARGIN = 1.0f
        /** What a typed word no list has is worth against the readings of its taps: see [byTaps]. Set on tool/typing_bench.sh. */
        @JvmField var UNKNOWN = 3.2f
        @JvmField var UNKNOWN_ODD = 1.8f
        /** What a typed word of [LONG_WORD] letters or more that no list has is worth: see [byTaps]. */
        @JvmField var UNKNOWN_LONG = 1.6f
        @JvmField var LONG_WORD = 6f
        /** A rare listed word gives way to a word this much commoner, whose slips cost at most [RARE_BUDGET]. */
        @JvmField var RARE_GAP = 2f
        @JvmField var RARE_BUDGET = 2.2f
        /** The least two words run together must be worth to replace a word no list has. */
        @JvmField var JOIN_BAR = 0f
        /** The least a word must be worth to be offered as what a half-typed word with a slip in it was the start of. */
        @JvmField var FINISH_LEAST = 2.5f
        /** How much more than a listed word a reading must be worth to replace it: a rare word's, and a common word's. */
        @JvmField var MARGIN_RARE = 0.6f
        @JvmField var MARGIN_COMMON = 1.6f
        /**
         * English's wider list (assets/dict/en_wide.tsv, [widen]; docs/wide-words.md has these numbers and the rest):
         * read where English is loaded (0: never, and everything is as it was with en.tsv alone). The others say what
         * a word of it is for, by its Zipf frequency. They are read as the list is, so the bench and the scoreboard
         * set them before the keyboard is made. "Seen" and "unseen" below are tool/versus/scoreboard.py's two sets,
         * 5,205 and 6,408 words, of which 306 and 413 are left wrong as these stand (326 and 437 with en.tsv alone; 305 and 413 before [WIDE_HOLD]).
         * - [WIDE_LEAST]: the least a word must have to be taken at all. 0, every word of the file: the file is the
         *   cut (tool/wide_words.py), and this is for trying a shorter one without making it.
         * - [WIDE_READ]: the least for a word a dictionary vouches for to be a reading of a slip (to be in the
         *   trie). 0, all 79,246. At 1.5 one more of the unseen set's words is wrong (414); at 2.0, two more of the
         *   seen set's too (307, 414); with none read, 315 and 430.
         * - [WIDE_READ_WRITTEN]: the same for a word only writing vouches for. 9, none: wordfreq lists names beside
         *   misspellings, and a slip is not mended into what may be one. At 0 one more word of the seen set is right
         *   (304), none of the unseen set's and none of the bench's.
         * - [WIDE_SWIPE]: the least for a word to be one a stroke may be. 2: the 29,621 commonest that a dictionary
         *   vouches for. The bench's strokes (tool/typing_bench.sh swipe, 6,005 words) are read right at first
         *   94.58%, 93.45% and 86.68% of the time for its three hands with none of the list's words, 96.13%, 94.88%
         *   and 87.75% with these, and 96.52%, 95.25% and 87.91% with all of them; but a word's shape is 144 bytes of
         *   heap, 4 MB for these and 11 MB for all, which is more than the list was allowed (the budget is in
         *   docs/wide-words.md).
         * - [WIDE_UNTAPPED]: whether its words are readings where the taps are not known ([pick]). 0, they are not:
         *   on the bench's lines typed without taps (24,000 words, its normal thumb) they mend 66.8% of slips for
         *   66.6%, change 20.8% of the right words no list has for 18.7%, and spoil 5.05 right words in a thousand
         *   for 4.58.
         */
        @JvmField var WIDE = 1f
        @JvmField var WIDE_LEAST = 0f
        @JvmField var WIDE_READ = 0f
        @JvmField var WIDE_READ_WRITTEN = 9f
        @JvmField var WIDE_SWIPE = 2f
        @JvmField var WIDE_UNTAPPED = 0f
        /**
         * The size in bits of the hash filter in front of the wider list's lookups ([Listed]; a power of two, read as
         * a lexicon is widened). Not tuned: 1 shl 20 (128 KB), so the 87,000 words set about one bit in twelve and
         * eleven in twelve lookups of letters that are no word of the list skip the binary search. It changes no
         * reading, only how fast a lookup that finds nothing is (and that was not measurable on a loaded Mac).
         */
        @JvmField var WIDE_BITS = 1 shl 20
        /**
         * A word of the wider list that no dictionary vouches for at a spelling checker's size (`w`, written only, and
         * `l`, a larger dictionary's pages): before there was a wider list it was letters no list has, and gave way to
         * any reading worth more than [unlisted] says such letters are. On (1), it is held at that bar AND by the rule
         * for a doubtful rare word (a reading must also be [RARE_GAP] commoner and a cheap slip away), so knowing it
         * never makes it weaker than it was unlisted ("piotr" is not "poor"). Off, only the rare-word rule holds it.
         * Measured (docs/wide-words.md, "Answering the review"): on, the seen set has one word more wrong (hymie,
         * which the rare-word rule alone made "humor" and which stood unlisted too), the unseen set the same; the
         * bench's sentences (same words each way) -0.01 right careful and normal; the bench's names that the list
         * knows, typed unfiltered: piotr→poor gone, unknown words spoiled 5.0% → 4.6% careful. Held exactly as
         * unlisted letters instead (no rare-word rule at all), the unseen set had two more wrong (incas → a, as).
         */
        @JvmField var WIDE_HOLD = 1f
        /**
         * A rare word that a dictionary vouches for ([SURE]) stands as typed unless a reading of its taps is worth
         * [SURE_MARGIN] more than the word itself ([SURE_STANDS] 0: such a word is as doubtful as any rare word, which
         * is how every rare word was held before the wider list said which are words; the seen and unseen sets then
         * have 307 and 416 words wrong for 305 and 413, measured before [WIDE_HOLD]).
         *
         * The margin was set on 60,000 of the typing bench's words, typed on the same sentences each time
         * (-Dunknown), by the bench's own measure (words right, less four for every right word spoiled, a thumb's
         * mean, and three points for every misspelling mended): en.tsv alone 93.537; the list with [SURE_STANDS] 0
         * 93.645, and with a margin of 0.6 93.679, 1.0 93.700, 1.15 93.707, 1.3 93.726, 1.45 93.720, 1.6 93.715,
         * 2.0 93.690. At 1.3 the three thumbs spoil 70, 72 and 53 right words of those 60,000 where en.tsv alone
         * spoils 90, 87 and 58. (Read only as slips of the thumb, as a common word is, such a word did no better
         * at any margin tried. And the most its slips may cost changes nothing between 2.2 and 4.4: it is
         * [LISTED_BUDGET].)
         *
         * [SURE_UNTAPPED] is the same where the taps are not known ([best] without [byTaps]: the cursor was moved into
         * the word, or it came from a hardware keyboard; a list beyond the first six never has the wider list read in): on, such a word is
         * never changed there. It is off, and the word is held there as every rare word always was (it gives way to a
         * far commoner word one cheap slip away). With no taps, letters that spell a rare word are a slip of the
         * commoner word a key away far more often than they are the rare word: on the bench's lines typed without
         * taps (24,000 words, its normal thumb) on leaves 84.70% of words right and spoils 4.58 right words in a
         * thousand, off 85.18% and 4.78, and en.tsv alone 85.21% and 5.39.
         */
        @JvmField var SURE_STANDS = 1f
        @JvmField var SURE_MARGIN = 1.3f
        @JvmField var SURE_UNTAPPED = 0f
        /**
         * A possessive no list has (plan's, yurt's, Okafor's), where English's wider list is read in: on, it is its
         * word with 's after it ([possessive]), and a word wherever the lists are asked ([isWord]). Off, it is
         * letters no list has, as it was, and "plan's" typed apostrophe and all could come out "plans". 0.20% of
         * running words are such possessives, and the lists have the word of two in three. On the same 60,000
         * words: off, the three thumbs leave 96.47%, 92.44% and 85.61% of words right and spoil 72, 73 and 53
         * right words; on, 96.47%, 92.46% and 85.64%, and 70, 72 and 53.
         */
        @JvmField var POSSESSIVE = 1f
        /** The most the slips may cost that turn one listed word into another. */
        @JvmField var LISTED_BUDGET = 3.4f
        /** The first of two words run together is at least this common; and what a second word costs that the tables never saw follow it. */
        /** How common a list word must be for an anonymous typing report to carry it ([isCommonWord]). */
        const val REPORTABLE = 4.0f
        /** What a reading is worth more for being what the person put this same typed word right to before ([setMistakes]). */
        @JvmField var REMEMBERED = 1.5f
        @JvmField var JOIN_FIRST = 3f
        @JvmField var JOIN_UNSEEN = 0.5f
        /**
         * A first word whose row of followers is this long has had its likely followers listed: one it does not list
         * follows it [JOIN_UNLISTED] as often as the least it does, at most.
         */
        @JvmField var JOIN_UNLISTED = 0.5f
        private const val JOIN_FULL_ROW = 12
        /** Zipf 3.2 is about one in 600,000 words: below it a "word" in the list is as likely a common misspelling. */
        private const val COMMON = 3.2f
        /**
         * What English's wider list says of a word ([Lexicon.marks]): a dictionary vouches for it at the size a
         * spelling checker is made from; only writing vouches for it; it is a name (nothing here goes by that yet:
         * it is where a capital for the word could come from); it is one of the wider list's own words (en.tsv lacks
         * it); and no slip is read as it.
         */
        private const val SURE = 1
        private const val WRITTEN = 2
        private const val NAMED = 4
        private const val WIDER = 8
        private const val UNREAD = 16
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
