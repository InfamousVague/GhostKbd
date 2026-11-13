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
 * A name comes out with its capital where the field wants capitals ([proper]: tom is Tom, monday Monday), mended
 * or left as typed, from English's list of the words all but always written with one (dict/en_capitals.txt).
 *
 * Where next-word prediction is on, the words before are gone by too ([Scorer], NextWordModel.kt): among the words a
 * prefix could become, the likely next ones rank higher. A word read by its taps ([byTaps]) is weighed by how likely
 * it is at its place in the sentence where a starter table has a row for the words before ([Scorer.Here]): there
 * the place can make a correction that would not have been made without it ("need to rat more" is "eat"), and can
 * keep one from being made, but it never overturns a listed word whose every tap is well inside its own key
 * ([CLEAN_TAP]). With no [Scorer] given everything here is exactly as it was before there was one.
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
        /** What letters no list has are worth ([unlisted]), where the one list loaded has a table for it (English's): else null. */
        val letters: LetterModel? = null,
        /** For each word of [sorted], what English's wider list says of it; null where that list is not read in. */
        val marks: ByteArray? = null,
        /** The first lists' words alone, without the wider list's: [words] itself where that list is not read in. */
        val firstWords: Map<String, Float> = words,
        /** The first lists' words in order, and their frequencies: [sorted] and [sortedZipf] themselves where it is not. */
        val firstSorted: Array<String> = sorted,
        val firstZipf: FloatArray = sortedZipf,
        /** English's words that are all but always written with a capital, each under its lower case ("tom" to "Tom"): see [proper]. */
        val capitals: Map<String, String> = emptyMap(),
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

        /** [follows]' words as places in [sorted], in its order, for reading words run together ([byTaps]). Kept once worked out. */
        private val places = java.util.concurrent.ConcurrentHashMap<String, IntArray>()

        fun followers(prev: String): IntArray = places[prev] ?: run {
            val row = follows(prev)
            val out = if (row == null) IntArray(0) else row.keys.mapNotNull { w -> java.util.Arrays.binarySearch(sorted, w).takeIf { it >= 0 } }.toIntArray().also { it.sort() }
            places[prev] = out
            out
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
        // English's names (dict/en_capitals.txt: Tom, Monday, Algeria), read before the lists. Beside another language
        // some of them are no names: a spelling that language writes without a capital as a word of its own (French
        // "roman", a novel; German "mir"; Spanish "los", and "catalán" typed without its accent). Which ones is in
        // dict/en_capitals_beside.tsv, a line a language, made by tool/capitals.py from how that language's own
        // sentences write each spelling; Tom, Mary and London are names in all of them. A language that file says
        // nothing of gets the cautious rule that was the only one before 2026-11-13: every spelling its list has is
        // taken out as the list is read, and the plain spelling of every accented word it has.
        val caps = HashMap<String, String>()
        val unjudged = HashSet<String>()
        if ("en" in languages) {
            runCatching {
                open("dict/en_capitals.txt")?.bufferedReader()?.useLines { lines ->
                    lines.forEach { line -> val word = line.substringBefore('\t').trim(); if (word.isNotEmpty() && word[0] != '#') caps[word.lowercase(Locale.ROOT)] = word }
                }
            }
            if (caps.isNotEmpty() && languages.size > 1) {
                val beside = HashMap<String, String>()
                runCatching {
                    open("dict/en_capitals_beside.tsv")?.bufferedReader()?.useLines { lines ->
                        lines.forEach { line ->
                            val tab = line.indexOf('\t')
                            if (tab > 0 && line[0] != '#' && line.substring(0, tab) in languages) beside[line.substring(0, tab)] = line.substring(tab + 1)
                        }
                    }
                }
                for (lang in languages) {
                    if (lang == "en") continue
                    val none = beside[lang]
                    if (none == null) unjudged += lang else for (word in none.split(' ')) caps.remove(word)
                }
            }
        }
        for (lang in languages) {
            if (gone()) return null
            val firsts = commonest.getOrPut(lang) { ArrayList(12) }
            val first = lang in STRICT
            val cautious = lang in unjudged
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
                            if (cautious && caps.isNotEmpty()) { caps.remove(word); script.plain(word).let { if (it != word) caps.remove(it) } }
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
        // English alone has a table of what its unlisted words are spelled like ([LetterModel]); any other set of
        // lists holds letters no list has to the fixed bars, as before.
        val letters = if (languages.size == 1 && languages[0] == "en") runCatching { LetterModel.read(open("dict/en_letters.tsv")) }.getOrNull() else null
        return Lexicon(
            key, w, f, c, sorted, FloatArray(sorted.size) { w[sorted[it]] ?: 0f }, plainKeys, plainWords, FloatArray(plainWords.size) { w[plainWords[it]] ?: 0f },
            ends, gives, commonest, sharedI, languages.any { it !in FIRST_SIX }, rows, letters, capitals = caps,
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
            ends, lex.givesWay, lex.commonest, lex.sharedI, lex.cautious, lex.pairRows, lex.letters, sortedMarks, first, was, lex.sortedZipf, lex.capitals,
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
     * words ("thankyou" is "thank you"). [names] says the field wants capitals: then a name comes out with its own
     * ([proper]), mended ("mondya" is "Monday"), left as typed ("tom" is "Tom") or parted from the word it ran into
     * ("withmary" is "with Mary"), in the middle of a sentence too. Capitals typed on purpose are left, as ever,
     * and a word that is no name comes out exactly as it does unasked.
     */
    fun correct(typed: String, scorer: Scorer? = null, taps: Taps? = null, names: Boolean = false): String? {
        val now = now
        if (now.lexicon.words.isEmpty() || typed.isEmpty() || typed.length > 24) return null
        // An acronym or a deliberate capitalised name of two letters or more is left as typed.
        if (typed.length > 1 && typed.all { it.isUpperCase() }) return null
        // camelCase, iOS, McDonald: a capital after the first letter is deliberate.
        if (typed.drop(1).any { it.isUpperCase() }) return null
        val lower = typed.lowercase(now.board.locale)
        // One of the person's own often-typed words is left alone, unless they have been putting it right by hand.
        if ((kept(lower) && remembered(now, lower) == null) || eszettWord(lower, now.lexicon.words)) return null
        val fixed = best(now, lower, scorer, taps?.takeIf { it.xs.size == typed.length })?.takeIf { it.sure }?.word
        // With names a mend has its names' capitals, and a word left as typed is looked at again only where it is a
        // name ([name]): any other word is left without being asked how its capitals would be written back.
        val word = when {
            !names -> fixed ?: return null
            fixed != null -> named(now, fixed)
            else -> name(now, lower) ?: return null
        }
        val cased = recase(now.board.locale, typed, word)
        return if (cased == typed) null else cased
    }

    /**
     * What the row above the keys offers for the word being typed: the fix autocorrect would make (or the word itself
     * when it is a word), then the commonest words it begins. [typedIsWord] says whether the typed word is one.
     */
    class Suggestions(val words: List<String>, val fix: String?, val typedIsWord: Boolean)

    /**
     * The row's suggestions for [typed]. With a [scorer] (prediction is on) the words it begins are ranked by the
     * word before as well as by how common they are, and the person's own often-typed words join them. With [names]
     * (as [correct] has it) a name is offered with its capital, and "tom" has "Tom" first, as the space will leave
     * it, and then "tom", a tap away.
     */
    fun suggest(typed: String, scorer: Scorer? = null, taps: Taps? = null, names: Boolean = false): Suggestions {
        val now = now
        if (now.lexicon.words.isEmpty() || typed.isEmpty() || typed.length > 24) return Suggestions(emptyList(), null, false)
        val locale = now.board.locale
        val lower = typed.lowercase(locale)
        val words = now.lexicon.words
        val contractions = now.lexicon.contractions
        val eszett = eszettWord(lower, words)
        val isWord = kept(lower) || (words[lower] ?: 0f) > 0f || contractions.containsKey(lower) || eszett || isPossessive(now.lexicon, lower)
        val mend = if ((kept(lower) && remembered(now, lower) == null) || eszett) null else best(now, lower, scorer, taps?.takeIf { it.xs.size == typed.length })
        val sure = mend?.takeIf { it.sure }?.word
        // What the space will leave: the mend, and a name's capital where the field wants them, as [correct] has it
        // (which leaves capitals typed on purpose alone: TOM, tOm; a word the person keeps is no name: [name]).
        val fix = when {
            !names -> sure
            sure != null -> named(now, sure)
            typed.drop(1).any { it.isUpperCase() } -> null
            else -> name(now, lower)?.takeIf { recase(locale, typed, it) != typed }
        }
        fun shown(word: String) = if (names) proper(now, word) else capitalI(now, word)
        val out = LinkedHashSet<String>()
        fix?.let { out += recase(locale, typed, it) }
        if (isWord) out += recase(locale, typed, contractions[lower]?.let { capitalI(now, it) } ?: capitalI(now, lower))
        // A mend that is likely but not certain enough to make on its own is offered, never made.
        mend?.takeIf { !it.sure }?.let { out += recase(locale, typed, if (names) named(now, it.word) else it.word) }
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
            decoder.load(lower, aimed(now, spread(now, scorer, taps), scorer))
            for (h in decoder.finish(least - (if (scorer == null) 0f else CONTEXT_PICK))) {
                val word = now.lexicon.sorted[h.index]
                // Nothing amiss: a word the typed letters begin, found above (with a lean off the taps, the letters aimed at).
                if (word == lower || (h.cost <= 0f && decoder.own <= 0f)) continue
                val zipf = now.lexicon.sortedZipf[h.index]
                val lift = if (scorer == null) 0f else minOf(scorer.boost(word, zipf), CONTEXT_PICK)
                top += word to zipf + lift - h.cost
            }
        }
        top.sortByDescending { it.second }
        if (scorer == null && !noisy) {
            for ((cand, _) in top) { if (out.size >= 5) break; out += recase(locale, typed, shown(cand)) }
        } else {
            // A word ranked twice (by frequency, and in context) is offered once, in its higher place.
            val taken = out.mapTo(HashSet()) { it.lowercase(locale) }
            for ((cand, _) in top) { if (out.size >= 5) break; if (taken.add(cand.lowercase(locale))) out += recase(locale, typed, shown(cand)) }
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
     * The word [lower] should be, or null. With a [scorer] and no taps, among the candidates that would each have been
     * a good enough correction on their own, the one likelier after the word before wins (its lift counts for at
     * most [CONTEXT_PICK]); which words may be corrected at all, and the least a correction must score, are untouched.
     * With taps, [byTaps] decides, and there the place in the sentence may decide whether a word is corrected at all.
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
     * Every reading is worth what its word is worth at this place in the sentence, less what its slips cost. Where
     * a starter table has a row for the words before, a word is worth how likely it is after them ([atPlace],
     * [Scorer.Here]): its frequency where no row bears on it, more where a row lists it ("you" after "thank"), and
     * less where it is a common word the rows there do not list ("of" after "tell you"). Where no table has a row, or
     * prediction is off, it is worth its frequency plus what the words before lift it by (at most [CONTEXT_PICK]),
     * as it was before the place was read at all. The typed word is one of the readings, with no slips, and is
     * held the same way: a common word in a place it all but never stands is cheap to replace, and one the place
     * expects is dear.
     * - a word no list has is worth [UNKNOWN], or [UNKNOWN_ODD] where it does not look like a word at all ([odd]),
     *   or less than either where English's letter table says its letters are no word's ([unlisted]): any reading
     *   worth more replaces it;
     * - a rare listed word gives way to a word [RARE_GAP] commoner (at a place: likelier there) that is a slip or a
     *   misspelling away, and at a place to any reading worth [MARGIN_RARE] more than it;
     * - a rare word that a dictionary vouches for (English's wider list says which, [SURE]) gives way only to a
     *   reading worth [SURE_MARGIN] more than the word itself: "yurt" typed on its own keys is "yurt";
     * - a common word gives way only to one worth [MARGIN_COMMON] more, about a hundred to one ([HERE_COMMON] at a
     *   place), and only by slips of the thumb: "br" with its r tapped on the line between e and r is "be", and
     *   with the r tapped in the middle of its key it is "br". At a place it may also be a word the rows list
     *   there, held against the taps with any slip (a swap, an apostrophe) and, by [LISTED_EDITS], of another
     *   length (a letter missed or added);
     * - a listed word whose every tap is well inside its own key ([onTheirKeys], [CLEAN_TAP]) is weighed as though
     *   there were no place: the place alone never overturns what the taps say plainly.
     * The readings are the lists' words ([TapDecoder.search]), the words the rows list at the place (each held up
     * against the taps: the search goes by frequency, and would pass over a word that is rare and likely here),
     * the person's own, and two or three words run together ([TapDecoder.joins], worth at least [JOIN_BAR]) where the
     * typed one is no common word. A join's second word is worth how likely it is after the first ([Scorer.after]).
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
        decoder.load(lower, aimed(now, spread(now, scorer, taps), scorer))
        // With this thumb's lean taken off the taps ([ThumbLean]) the typed letters are not always the keys nearest
        // them: what the typed word then costs as a reading of its own taps comes off what it is worth. Nothing,
        // where no lean is estimated.
        val typedCost = decoder.own
        // The place the word stands in, where a table has a row for the words before it. Null where none has, or
        // prediction is off: every word is then worth how common it is, as before the place was read at all. Null
        // too for a listed word whose every tap is well inside its own key ([onTheirKeys]): the taps say it was
        // meant, and the place alone never overturns that. It is read as it was before there was a place (a lift
        // capped at [CONTEXT_PICK], [MARGIN_COMMON] for a common word): "Its" opening a sentence stands.
        val clean = known != null && onTheirKeys(now, lower, taps)
        val here = if (HERE && !clean) scorer?.here { words[it] } else null
        fun lift(word: String, zipf: Float) = if (scorer == null) 0f else minOf(scorer.boost(word, zipf), CONTEXT_PICK)
        fun worth(word: String, zipf: Float) = if (here != null) atPlace(here, word, zipf) else zipf + lift(word, zipf)
        val common = known != null && known >= COMMON
        val rare = known != null && !common
        // A rare word that a dictionary vouches for (English's wider list says which): a word, not a misspelling.
        val mark = if (rare) lex.mark(lower) else 0
        val sure = rare && SURE_STANDS > 0f && mark and SURE != 0
        // Any other rare listed word: only writing vouches for it, or a larger dictionary's less sure pages.
        val doubtful = rare && !sure
        // One of those that only the wider list has: it was letters no list has before, and is held no less ([WIDE_HOLD]).
        val held = doubtful && WIDE_HOLD > 0f && mark and (WIDER or SURE) == WIDER
        // What the typed word is worth where no list has it: little where it does not look like a word, less where
        // it is long (a long word a letter from a common one is that word misspelled, far more often than a name),
        // and next to nothing where its letters are ones no word puts together ([unlisted]).
        val letters = letterTable(now, scorer)
        val unknown = if (known != null) UNKNOWN else unlisted(now, lower, letters)
        // The least a reading must be worth to replace a word only the wider list has, whatever else holds it.
        val heldBar = if (held) unlisted(now, lower, letters) - typedCost else Float.NEGATIVE_INFINITY
        // What it is worth as it stands: as any reading is, less what its own taps cost it where the thumb's lean
        // is taken off them ([TapDecoder.own]; nothing where no lean is known), and with no other slips to pay for.
        val stood = (if (known != null) worth(stands, known) else unknown) - typedCost
        // A word a dictionary vouches for is worth its own worth here, and gives way only to a reading worth
        // [SURE_MARGIN] more than that: "yurt" typed on its own keys stands whatever is a key away, and "vases"
        // typed with its v on the line to c is "cases".
        val bar = when {
            known == null -> stood
            common -> stood + (if (here == null) MARGIN_COMMON else HERE_COMMON)
            sure -> stood + SURE_MARGIN
            here == null -> heldBar
            else -> maxOf(heldBar, stood + MARGIN_RARE)
        }
        var bestWord: String? = null
        var bestScore = Float.NEGATIVE_INFINITY
        // At a place, a listed typed word is never read as a word it differs from by apostrophes alone ([PLACE_APOSTROPHE]):
        // an apostrophe has no tap, so nothing but the place would say so ("Its" opening a sentence is not "It's").
        val noApostrophe = here != null && known != null && !PLACE_APOSTROPHE
        fun offer(word: String, score: Float, least: Float = bar) {
            if (word == lower || word == stands || score < least || score <= bestScore) return
            if (noApostrophe && ('\'' in word || '’' in word) && word.length > lower.length && word.filter { it != '\'' && it != '’' } == lower) return
            bestWord = word; bestScore = score
        }
        // What a reading must be worth, less its slips, to take the typed word's place.
        // A rare listed word that no dictionary vouches for is as often a common misspelling as a word (definately,
        // occured): it gives way to a word [RARE_GAP] commoner that is a slip or a misspelling away ([RARE_BUDGET]),
        // whatever that leaves it worth (no less than [heldBar]), and to nothing else where there is no place to go by. Where there is, commoner means likelier
        // there, and a reading that is not so much likelier may still outdo it by [MARGIN_RARE].
        // A word no list has, with an ending of its own (chanced, dueled), is a word: only a far likelier reading takes it.
        fun needs(word: String, zipf: Float, worth: Float, cost: Float): Float = when {
            known != null && doubtful && here == null -> if (zipf >= known + RARE_GAP - typedCost) heldBar else Float.POSITIVE_INFINITY
            doubtful -> if (worth >= stood + RARE_GAP && cost <= RARE_BUDGET) heldBar else bar
            known == null && unknown < UNKNOWN && (LETTERS_INFLECTED > 0f || letters == null) && inflected(words, lower, word) -> UNKNOWN - typedCost
            else -> bar
        }
        val trace = TRACE
        if (trace != null) {
            trace("typed $lower: " + (if (known == null) "in no list, worth $unknown" else "listed at $known (${if (common) "common" else "rare"}), worth $stood as it stands") +
                (if (here != null) " here" + (if (known != null && here.lists(stands)) ", where a row lists it" else "") else if (scorer == null) ", with no words before to go by" else if (clean && HERE) ", every tap on its own key (the place read as before)" else ", after words no table has a row for") + "; a reading needs $bar")
            TRACE_MEANT?.let { m ->
                val z = words[m]
                trace("  meant $m: " + (if (z == null) "in no list" else "frequency $z, worth ${worth(m, z)}" + (if (here?.lists(m) == true) " (a row lists it)" else "") + ", slips ${decoder.align(m, 0, 99f)}"))
            }
        }
        // A listed word is only ever read as another by slips whose cost is within [LISTED_BUDGET]; a rare one that no
        // dictionary vouches for, where there is no place to go by, within [RARE_BUDGET].
        val budget = when { known == null -> TapDecoder.BUDGET; common || sure || here != null -> LISTED_BUDGET; else -> RARE_BUDGET }
        // The words the rows list as followers here, each held up against the taps: the search below goes by how
        // common a word is, and would pass over one that is rare and likely here ("straits" after "dire"). They are
        // few (a row has at most 160), and one whose worth cannot pay for its slips is let go at its first letters.
        here?.listed { word ->
            val zipf = words[word] ?: return@listed
            if (word == lower || word == stands || kotlin.math.abs(word.length - lower.length) > 2) return@listed
            if (common && !LISTED_EDITS && word.length != lower.length) return@listed
            val w = worth(word, zipf)
            val room = minOf(w - bestScore, w - needs(word, zipf, w, 0f))
            if (room <= 0f) return@listed
            // A common word's edits as the search reads them with the reach ([TapDecoder.align]'s listed).
            val cost = decoder.align(word, 0, minOf(budget, room), listed = common && now.board.english)
            if (cost.isInfinite()) return@listed
            val plain = script.plain(word)
            if (plain != word && words.containsKey(plain)) return@listed
            val least = needs(word, zipf, w, cost)
            trace?.invoke("  $word: frequency $zipf, worth $w (a row lists it), slips $cost, leaves ${w - cost}, needs $least")
            offer(word, w - cost, least)
        }
        // Every other word, found by how common it is: one no row lists is worth that here, or less.
        val reach = if (here != null || scorer == null) 0f else CONTEXT_PICK
        val floor = when {
            known != null && doubtful && here == null -> known + RARE_GAP - typedCost - budget
            doubtful -> maxOf(minOf(stood + RARE_GAP - RARE_BUDGET, bar), bestScore)
            here != null -> maxOf(bar, bestScore)
            else -> bar - reach
        }
        // A common word is only ever read as another by a slip of the thumb or two: no misspelling.
        for (h in decoder.search(floor, budget, 0, slipsOnly = common, reach = now.board.english)) {
            val word = lex.sorted[h.index]
            // Typed plainly with a slip: the plain word where there is one (esra is esta, not está).
            val plain = script.plain(word)
            if (plain != word && words.containsKey(plain)) continue
            val zipf = lex.sortedZipf[h.index]
            val w = worth(word, zipf)
            val least = needs(word, zipf, w, h.cost)
            trace?.invoke("  $word: frequency $zipf, worth $w" + (if (here?.lists(word) == true) " (a row lists it)" else "") + ", slips ${h.cost}, leaves ${w - h.cost}, needs $least")
            offer(word, w - h.cost, least)
        }
        // The person's own words: never in the trie, so each is held up against the taps. (One that English's
        // wider list has may be in the trie as the rare word that list says it is: here it is the person's.)
        for (l in learned + own) {
            if (lex.firstWords.containsKey(l) || kotlin.math.abs(l.length - lower.length) > 2) continue
            val w = worth(l, LEARNED_ZIPF)
            if (known != null && !sure && (if (here == null) LEARNED_ZIPF < known + RARE_GAP - typedCost else w < stood + RARE_GAP)) continue
            val cost = decoder.align(l, 0, if (doubtful) RARE_BUDGET else budget)
            if (!cost.isInfinite()) {
                trace?.invoke("  $l: one of the person's own words, worth $w, slips $cost, leaves ${w - cost}")
                offer(l, w - cost, if (doubtful) heldBar else bar)
            }
        }
        // What the person has put this very word right to before (a common word typed for another: form for from):
        // held up against the taps like their own words, and worth [REMEMBERED] more for having been meant before.
        mistaken[lower]?.takeIf { ' ' !in it && lower !in learned && lower !in slang }?.let { meant ->
            val m = meant.lowercase(now.board.locale)
            val zipf = words[m] ?: if (kept(m)) LEARNED_ZIPF else return@let
            val cost = decoder.align(m, 0, TapDecoder.BUDGET)
            if (!cost.isInfinite()) {
                trace?.invoke("  $m: what this was put right to before, worth ${worth(m, zipf) + REMEMBERED}, slips $cost")
                offer(meant, worth(m, zipf) + REMEMBERED - cost, if (doubtful) heldBar else bar)
            }
        }
        // Two or three words run together, where what was typed is no common word.
        if (!common && lower.length >= 3 && JOINS) {
            val joinBar = when { known == null -> minOf(bar, JOIN_BAR); here == null -> known + MARGIN_RARE - typedCost; else -> stood + MARGIN_RARE }
            // Letters that look like no word are no word the longer they are: see [JOIN_FALL]. Where they look like
            // none is kept, since where two words meet they need not look like one. With English loaded only: it
            // was set on English typing, and a language that writes its compounds as one word (German) has long
            // words no list holds that are meant, whose bar is left where it was.
            val oddAt = if (known == null && now.board.english && lower.length > JOIN_LONG) oddRuns(lower) else null
            val fallen = if (oddAt != null) joinBar - JOIN_FALL * (lower.length - JOIN_LONG) else joinBar
            val sorted = lex.sorted
            val zipfs = lex.sortedZipf
            // What a second word is worth after a first. Where there is a place to go by, what any reading is worth
            // at its place ([atPlace]), the place being the one after that first word ([Scorer.after]); and
            // [JOIN_UNSEEN] less than its frequency where no row there bears on it, as a pair the tables never saw.
            val afters = HashMap<Int, Scorer.Here>()
            val none = HashSet<Int>()
            fun after(a: Int): Scorer.Here? {
                afters[a]?.let { return it }
                if (here == null || a in none) return null
                val made = scorer!!.after(sorted[a]) { words[it] }
                if (made == null) none += a else afters[a] = made
                return made
            }
            // A word the rows there list is worth what it is worth at its place; any other word pays JOIN_UNSEEN or
            // what the place holds it down by, whichever is more, so a pair the tables have evidence against never
            // outranks one they know nothing of. Measured 2026-11-13, joins fixed by the bench's normal and
            // sloppy thumbs: paying both (as the code review put it) 63.6 / 58.6%, the bench's measure 94.51; this
            // way 63.6 / 59.4%, 94.52; the rule before, a held-down word worth its place alone, 64.1 / 59.4% (at
            // CLEAN_TAP 0.5: 94.44 against 94.42 for paying both). Seen and unseen the same all three ways.
            // Where there is no such place (prediction off, or no table that can be weighed has a row for the first
            // word: [Scorer.after] is null), the least share the first word's row lists: a second word the row does
            // not list follows it less often than that, however common it is elsewhere ("the" after "i": never, for
            // all that "the" is everywhere).
            val least = HashMap<Int, Float>()
            fun second(a: Int, b: Int): Float {
                val z = zipfs[b]
                if (sorted[b].length < 2 && z < SwipeDecoder.ONE_LETTER) return Float.NEGATIVE_INFINITY
                after(a)?.let { at ->
                    val w = sorted[b]
                    val over = HERE_WEIGHT * at.over(w, z)
                    return if (at.lists(w)) z + over else z + minOf(-JOIN_UNSEEN, over)
                }
                val row = lex.follows(sorted[a])
                val share = row?.get(sorted[b]) ?: 0f
                if (share > 0f) return 9f + kotlin.math.log10(Math.pow(10.0, (z - 9f).toDouble()) + share).toFloat()
                val unseen = z - JOIN_UNSEEN
                if (row == null || row.size < JOIN_FULL_ROW) return unseen
                val floor = least.getOrPut(a) { 9f + kotlin.math.log10(row.values.min() * JOIN_UNLISTED) }
                return minOf(unseen, floor)
            }
            fun index(w: String) = java.util.Arrays.binarySearch(sorted, w).takeIf { it >= 0 }
            // Without English loaded, two words run together are read as they were before the search below was
            // made (TapDecoder.pairs): it was set on English typing alone, and nothing measures it in another language.
            if (!now.board.english) {
                val pairs = decoder.pairs(
                    joinBar,
                    firsts = { i -> zipfs[i] >= JOIN_FIRST && (sorted[i].length >= 2 || zipfs[i] >= SwipeDecoder.ONE_LETTER) },
                    second = ::second,
                    followers = { a ->
                        val at = after(a)
                        if (at != null) {
                            val listed = ArrayList<Int>()
                            at.listed { w -> index(w)?.let { listed += it } }
                            listed.toIntArray()
                        } else {
                            val row = lex.follows(sorted[a])
                            if (row == null) IntArray(0) else row.keys.mapNotNull { w -> index(w) }.toIntArray()
                        }
                    },
                )
                for (j in pairs) {
                    val a = sorted[j.first]; val b = sorted[j.second]
                    val first = worth(a, zipfs[j.first])
                    offer(capitalI(now, a) + " " + capitalI(now, b), first + second(j.first, j.second) - 9f - j.cost, joinBar)
                }
                return bestWord?.let { Fix(if (' ' in it) it else capitalI(now, it), true) }
            }
            // What a word is worth as the first of them: its frequency, and [JOIN_LIFT] of what its place makes of it
            // over that (what the words before lift it by, where there is no place to go by).
            fun head(i: Int) = zipfs[i] + JOIN_LIFT * (worth(sorted[i], zipfs[i]) - zipfs[i])
            // A join has the best single word to beat as well as its bar: the search is told, and looks no lower.
            val joins = decoder.joins(
                maxOf(fallen, bestScore), JOIN_FIRST,
                first = { i -> if (zipfs[i] >= JOIN_FIRST && (sorted[i].length >= 2 || zipfs[i] >= SwipeDecoder.ONE_LETTER)) head(i) else Float.NEGATIVE_INFINITY },
                second = ::second,
                followers = { a ->
                    val at = after(a)
                    if (at != null) {
                        val listed = ArrayList<Int>()
                        at.listed { w -> index(w)?.let { listed += it } }
                        listed.toIntArray()
                    } else lex.followers(sorted[a])
                },
            )
            // The best of them says what the letters are, and it alone can be taken.
            var top: TapDecoder.Join? = null
            var topScore = Float.NEGATIVE_INFINITY
            // A thumb read loosely ([Taps.tight] under 1) has its joins' slips counted more tightly than its words'
            // ([ThumbLean.SPREAD_JOINS]): else letters no list has are split into two words ("mapbox", "map of").
            val loose = if (ThumbLean.SPREAD_JOINS == 0f) 1f else Math.pow(minOf(1f, spread(now, scorer, taps).tight).toDouble(), ThumbLean.SPREAD_JOINS.toDouble()).toFloat()
            for (j in joins) {
                var score = head(j.first) + second(j.first, j.second) - 9f - (if (loose == 1f) j.cost else j.cost / loose)
                if (j.third >= 0) score += second(j.second, j.third) - 9f
                trace?.invoke("  ${sorted[j.first]} ${sorted[j.second]}" + (if (j.third >= 0) " " + sorted[j.third] else "") + ": slips ${j.cost}, worth $score, needs $joinBar")
                if (score > topScore) { top = j; topScore = score }
            }
            top?.let { j ->
                var text = capitalI(now, sorted[j.first]) + " " + capitalI(now, sorted[j.second])
                if (j.third >= 0) text += " " + capitalI(now, sorted[j.third])
                // Letters that look like no word only where these words meet, with a word standing as typed on one
                // side of the place, are two words set side by side, which is how a word no list has is made
                // ("worktree", "windowpane"): they are held to the bar as it was.
                val away = oddAt != null && oddAt.indices.any { i -> oddAt[i] && !meets(i, j.from2, j.barred2) && (j.third < 0 || !meets(i, j.from3, j.barred3)) }
                val odd = oddAt != null && (away || !beside(lower, j, words))
                // Under the bar as it was, a join is taken only where no single word reads the letters for as much
                // as that bar, whether or not the word cleared its own, which is higher: where one does, the
                // letters are that word with slips in it, or nothing that can be read, and not two words
                // ("invedyor" is nearer "investor" than "in editor", and is left).
                val single = odd && topScore < joinBar && decoder.search(joinBar - reach, budget).any { h ->
                    worth(sorted[h.index], zipfs[h.index]) - h.cost >= joinBar
                }
                if (!single) offer(text, topScore, if (odd) fallen else joinBar)
            }
        }
        trace?.invoke("  chosen: ${bestWord ?: "none, it stands"}")
        return bestWord?.let { Fix(if (' ' in it) it else capitalI(now, it), true) }
    }

    /**
     * The one word that a tap on the space bar cut in two, or null where the two stand as they are. A thumb reaching
     * for the bottom row (c, v, b, n, m) lands on the top of the bar as often as on the key above it, and the word
     * is then two scraps with a space between: "bei g" for "being", "ha e" for "have"; or with nothing after it,
     * "o " for "on" (and a second space then made it "o. "); or with nothing before it, " ot" for "not" (and the
     * space already there made a full stop of the two: "It did. Ot"). 37 of the 326 words Gboard got and this did
     * not on 2026-11-11 were cut so (docs/versus.md).
     *
     * [first] and [second] are the two scraps as they were typed. [second] is empty where the tap was the word's
     * last letter and nothing has been typed since. [first] is empty where the tap ended no word: it came straight
     * after a space, and either made a full stop of that space ([stopped]) or stands as a second space, and
     * [second] is the word typed after it. [stands] is what stands in the field for the first (itself, or what it
     * was mended to at the space), and [taps] is where every tap landed, the space bar's own among them at
     * [first]'s length (xs and ys are [first], a space and [second] long). [scorer] is the place the one word would
     * stand in: where the first scrap stands, or, with no first scrap, before the full stop or the second space;
     * [after] is then the place [second] stands in as things are, the start of a sentence.
     *
     * [doubled] says the bar's tap and the tap on the bar beside it (the second space after it, or the space
     * before it that it made a full stop of) were one double tap, or that it is not known. A full stop is made by
     * tapping twice on one spot; a letter that fell on the bar and the space beside it land where each was aimed,
     * most of a key apart ([REJOIN_APART]). It is false too where a full stop or a comma follows the space: before
     * those nobody means a space, and there is no double tap in it; [mark] says one was typed, and the word is
     * then put together only with English loaded, as the reading of no first scrap is, what a mend cost and
     * [doubled] itself.
     *
     * The letters and the bar's tap are read as one word, as [byTaps] reads any ([TapDecoder] takes the bar's tap as
     * a letter for how much nearer the bar's middle line it was than that letter's key: next to nothing just under
     * the bar's top edge, a great deal in its middle). That reading is held against what stands:
     * - two scraps are worth the first as it stands and the second after it, less nine for being two words, as a
     *   join is counted, and the one word must be worth [REJOIN_MARGIN] more: two real words stand however high on
     *   the bar the space was tapped ("is a", "to go"), and two scraps that are no words give way even to a tap in
     *   the bar's middle ("bei g");
     * - where the first scrap was mended at the bar's tap, what stands is worth less by what that mending cost:
     *   "eome" made "some" by reading its e as an s, and "women" reads the same taps for less;
     * - one scrap with nothing after it is a word typed with one tap more, and is held as [byTaps] holds one:
     *   letters no list has are worth what they are worth there and give way to any reading worth more, and a
     *   listed word gives way only to one worth [REJOIN_MARGIN] more ("the" and two spaces is a full stop, not
     *   "then"; "o" and two spaces after "sit" is "on"), or, where the taps were no double tap, to one worth
     *   more at all ("i" and a tap on the bar's top edge under the n is "in" where "in" is the likelier there).
     *   The word the scrap was mended to may itself be the reading ("wome" was made "women" by a letter nobody
     *   typed, which the bar's tap now is): it comes back as it stands where the tap costs less as that letter
     *   than the mend did, and the second space is its space, not a full stop;
     * - one scrap with nothing before it is held against the letters as they stand and against the best word
     *   those letters make by themselves. After a full stop that a double tap made, and after a second space
     *   that made none, they stand where they are, at the start of a sentence ([after]), and letters that are a
     *   word give way only to one worth [REJOIN_MARGIN] more: "axj" after a full stop is "back", and "ever" after
     *   one is not "never". After a full stop that two taps apart made, they are held in the place the one word
     *   would stand in, on even terms with it.
     */
    fun rejoin(first: String, second: String, stands: String, scorer: Scorer? = null, taps: Taps, stopped: Boolean = false, doubled: Boolean = true, after: Scorer? = null, mark: Boolean = false): String? {
        val now = now
        val lex = now.lexicon
        if (!TAPS || !REJOIN || lex.cautious || lex.words.isEmpty() || (first.isEmpty() && second.isEmpty())) return null
        // What was added to this on 2026-11-12 (no first scrap, a closing [mark] after the space, what a mend cost,
        // and [doubled]) was set on English typing alone: without English loaded it reads as it did before.
        val english = now.board.english
        if (!english && (first.isEmpty() || mark)) return null
        val doubled = doubled || !english
        val typed = "$first $second"
        if (typed.length > 24 || taps.xs.size != typed.length || !taps.has(first.length) || taps.space == null) return null
        // Letters only, and no capital after a scrap's first letter: an acronym or a camelCase name is left alone.
        // With no first scrap the second's first letter may be a capital: the sentence's, or the full stop's doing.
        val leads = first.isEmpty()
        if (!first.all { it.isLetter() } || !second.all { it.isLetter() } || first.drop(1).any { it.isUpperCase() } || second.drop(if (leads) 1 else 0).any { it.isUpperCase() }) return null
        val locale = now.board.locale
        val words = lex.words
        // The place the first scrap stands in, as [byTaps] has it: null where there is nothing to go by.
        val here = if (HERE) scorer?.here { words[it] } else null
        fun lift(word: String, zipf: Float) = if (scorer == null) 0f else minOf(scorer.boost(word, zipf), CONTEXT_PICK)
        val letters = if (LETTERS_JOINS > 0f) letterTable(now, scorer) else null
        fun worth(word: String, zipf: Float) = if (here != null) atPlace(here, word, zipf) else zipf + lift(word, zipf)
        fun alone(word: String): Float {
            val l = word.lowercase(locale)
            return words[l] ?: lex.contractions[l]?.let { words[it] } ?: if (kept(l)) LEARNED_ZIPF else unlisted(now, l, letters)
        }
        /** The taps of [count] letters from [from] on, as a word's own. */
        fun part(from: Int, count: Int) = Taps(taps.xs.copyOfRange(from, from + count), taps.ys.copyOfRange(from, from + count), taps.keys, taps.keyW, taps.space)
        val decoder = tapDecoder(now, taps)
        val trace = TRACE
        var bestScore: Float
        // The word the first scrap was mended to where a tap of the mend's own making may be the bar's, with what
        // that mend cost and the least a reading of it is worth: null where there is no such word.
        var mendedTo: String? = null
        var mended = 0f
        var floor = Float.POSITIVE_INFINITY
        if (leads) {
            val lower = second.lowercase(locale)
            // A full stop that two taps on one spot made is a full stop: the letters after it start a sentence
            // ([after]) and, where they are a word, give way only to one worth [REJOIN_MARGIN] more; so do letters
            // after a second space that made none. Two taps that landed apart were no double tap, and the full
            // stop they made was nobody's: the letters after it are held in the place the one word would stand
            // in, on even terms with it.
            val meant = !stopped || doubled
            val place = if (meant) after else scorer
            // What the place makes of a word over its frequency, as [byTaps] weighs it ([atPlace]) where a table has
            // a row there, else what the words before lift it by. Letters no list has are worth what they are worth.
            val placed = if (HERE) place?.here { words[it] } else null
            fun placeLift(word: String, zipf: Float) = when {
                place == null -> 0f
                placed != null -> if (words.containsKey(word)) atPlace(placed, word, zipf) - zipf else 0f
                else -> minOf(place.boost(word, zipf), CONTEXT_PICK)
            }
            bestScore = alone(second).let { it + placeLift(lower, it) } + (if (meant && isWord(lower)) REJOIN_MARGIN else 0f)
            // The best word the letters make by themselves, which is what the space would otherwise leave standing.
            decoder.load(lower, part(1, second.length))
            for (h in decoder.search(bestScore - (if (place == null || placed != null) 0f else CONTEXT_PICK), TapDecoder.BUDGET)) {
                val zipf = lex.sortedZipf[h.index]
                bestScore = maxOf(bestScore, zipf + placeLift(lex.sorted[h.index], zipf) - h.cost)
            }
            trace?.invoke("cut before ${second}: the letters alone worth $bestScore")
        } else {
            val stood = stands.lowercase(locale)
            // What it cost to read the first scrap as what stands for it, where the space mended it (into one word:
            // a scrap parted in two there is held as letters no list has, as it was before this was counted).
            if (english && stood != first.lowercase(locale) && ' ' !in stood) {
                decoder.load(first.lowercase(locale), part(0, first.length))
                mended = decoder.align(stood, 0, TapDecoder.BUDGET).let { if (it.isInfinite()) 0f else it }
                if (second.isEmpty() && mended > 0f) { mendedTo = stood; floor = alone(stands) - mended }
            }
            val one = worth(stood, alone(stands)) - mended
            // The second scrap after the first: where there is a place to go by, what the first makes of it there, where
            // that is more than its own frequency ("a" after "is"). Never less: it has not been read by its taps yet, and
            // a slip in it ("u" for "I" after "may") is still to be mended.
            val then = if (second.isEmpty()) 0f else alone(second).let { z ->
                val next = if (here != null) scorer!!.after(lex.contractions[stood] ?: stood) { words[it] } else null
                if (next == null) z else second.lowercase(locale).let { l -> maxOf(z, atPlace(next, lex.contractions[l] ?: l, z)) }
            }
            val asItStands = if (second.isEmpty()) one else one + then - 9f
            bestScore = asItStands + (if (second.isEmpty() && (!isWord(stood) || !doubled)) 0f else REJOIN_MARGIN)
            trace?.invoke("cut in two, $first and ${second.ifEmpty { "nothing" }}: as it stands ($stands) worth $one" + (if (second.isEmpty()) "" else " and $then after it, $asItStands in all") + (if (mended > 0f) ", the mend costing $mended" else "") + "; one word needs $bestScore")
        }
        decoder.load(typed.lowercase(locale), aimed(now, if (ThumbLean.SPREAD_REJOIN) spread(now, scorer, taps) else taps.loose(), scorer))
        // What stands pays for its own taps as the lean has them ([TapDecoder.own]): the letters', and the bar's as a space.
        bestScore -= decoder.own
        var best: String? = null
        var same = false
        // The words the rows list here, each held up against the taps, as [byTaps] holds them.
        here?.listed { word ->
            val zipf = words[word] ?: return@listed
            val w = worth(word, zipf)
            if (w <= bestScore || kotlin.math.abs(word.length - typed.length) > 2) return@listed
            val cost = decoder.align(word, 0, minOf(TapDecoder.BUDGET, w - bestScore))
            if (cost.isInfinite()) return@listed
            if (word == mendedTo) { if (cost < mended) same = true; return@listed }
            trace?.invoke("  $word: frequency $zipf, worth $w (a row lists it), slips $cost, leaves ${w - cost}")
            if (w - cost > bestScore) { best = word; bestScore = w - cost }
        }
        val reach = if (here != null || scorer == null) 0f else CONTEXT_PICK
        for (h in decoder.search(minOf(floor, bestScore - reach), TapDecoder.BUDGET)) {
            val word = lex.sorted[h.index]
            // The word that stands, read with the bar's tap for the letter the mend had to supply: nothing changes
            // but that the tap was no space, so it need only cost less than the mend did.
            if (word == mendedTo) { if (h.cost < mended) same = true; continue }
            val zipf = lex.sortedZipf[h.index]
            val score = worth(word, zipf) - h.cost
            trace?.invoke("  $word: frequency $zipf, worth ${worth(word, zipf)}, slips ${h.cost}, leaves $score")
            if (score > bestScore) { best = word; bestScore = score }
        }
        trace?.invoke("  chosen: ${best ?: if (same) "none, $stands stands" else "none, the two stand"}")
        if (best == null && same) return stands
        // The capital of a word typed after a full stop was the full stop's: without the stop it has none.
        return best?.let { if (leads && stopped) capitalI(now, it) else recase(locale, if (leads) second else first, capitalI(now, it)) }
    }

    /**
     * The second look: the word before, read again now that the word after it has ended (Gboard does so: it holds
     * back at the space where it is unsure and decides with the next word in hand; docs/versus.md, "The word before,
     * read again"). [typed] is the word before as it was typed, [stands] what stands in the field for it, [next] the
     * word that followed as it stands, [scorer] the place the word before stands in and [taps] where its taps landed.
     * Every reading of the taps (what stands, what was typed, and the decoder's readings) is weighed by what its
     * slips cost, by its place after the words before it ([atPlace]) and by how likely [next] is after it
     * ([Scorer.follows]); where another is ahead of what stands by [AGAIN_MARGIN], and the word after itself gives it
     * [AGAIN_LEAST] of that, it is what should stand. A typed word no list has is never put back.
     *
     * What it should be in place of [stands], or null to leave it. Null always where there is no place (prediction
     * off), where a table at the place does not count its words ([Scorer.counted]: every language's but English's
     * today, and English loaded with another), for a cautious list, without taps, and with [AGAIN] off. DevKeyboardService,
     * the replay's Typist and the typing bench call it the same way.
     */
    fun again(typed: String, stands: String, next: String, scorer: Scorer?, taps: Taps?): String? {
        val now = now
        val lex = now.lexicon
        val words = lex.words
        if (!AGAIN || !TAPS || scorer == null || taps == null || lex.cautious || words.isEmpty()) return null
        if (typed.isEmpty() || typed.length > 24 || taps.xs.size != typed.length || ' ' in stands || !scorer.counted) return null
        // An acronym, a camelCase name: left as typed at the space, and now.
        if ((typed.length > 1 && typed.all { it.isUpperCase() }) || typed.drop(1).any { it.isUpperCase() }) return null
        val locale = now.board.locale
        val lower = typed.lowercase(locale)
        if (kept(lower) || mistaken.containsKey(lower) || eszettWord(lower, words) || lex.folded.containsKey(lower)) return null
        // What the typed letters stand as where they are left: themselves, or the contraction they are typed for.
        val asTyped = lex.contractions[lower] ?: lower
        val stood = stands.lowercase(locale).let { if (it == lower) asTyped else it }
        if (stood != asTyped && (kept(stood) || !words.containsKey(stood))) return null
        // The word after, as the tables know it. One no list has says nothing of what came before it.
        val follower = next.lowercase(locale).let { lex.contractions[it] ?: it }
        val followerZipf = words[follower] ?: return null
        val known = words[asTyped]
        val common = known != null && known >= COMMON
        val here = if (HERE) scorer.here { words[it] } else null
        fun lift(word: String, zipf: Float) = minOf(scorer.boost(word, zipf), CONTEXT_PICK)
        fun worth(word: String, zipf: Float) = if (here != null) atPlace(here, word, zipf) else zipf + lift(word, zipf)
        // A pair the tables list speaks for a reading; a pair they never saw speaks against it only so far
        // (AGAIN_LOW): the tables were counted from Tatoeba, and "says only", "keys a" or "fix is" are pairs
        // Tatoeba seldom has, not pairs nobody writes.
        fun after(word: String) = (AGAIN_WEIGHT * scorer.follows(word, follower, followerZipf) { words[it] }).coerceIn(-minOf(AGAIN_LOW, AGAIN_MOST), AGAIN_MOST)
        // What the typed letters are worth as they stand, and how much more than that a reading must be worth to
        // take their place, as the first look had it.
        // A rare word a dictionary vouches for is held by [SURE_MARGIN], as at its space; one only the wider list has
        // ([WIDE_HOLD]) is worth no less than letters no list has, and is never put back, as such letters are not.
        val mark = if (known != null && !common) lex.mark(asTyped) else 0
        val sure = known != null && !common && SURE_STANDS > 0f && mark and SURE != 0
        val held = known != null && !common && !sure && WIDE_HOLD > 0f && mark and (WIDER or SURE) == WIDER
        val unlistedWorth = if (known == null || held) unlisted(now, lower, letterTable(now, scorer)) else 0f
        val typedWorth = if (known == null) unlistedWorth else if (held) maxOf(worth(asTyped, known), unlistedWorth) else worth(asTyped, known)
        val guard = when { known == null -> 0f; common -> if (here != null) HERE_COMMON else MARGIN_COMMON; sure -> SURE_MARGIN; else -> MARGIN_RARE }
        val decoder = tapDecoder(now, taps)
        decoder.load(lower, taps)
        // Every reading: its word, what the word is worth at its place, what its slips cost, what the word after makes of it.
        class Reading(val word: String, val worth: Float, val cost: Float) { var after = 0f; var all = 0f }
        val readings = HashMap<String, Reading>()
        fun offer(word: String, worth: Float, cost: Float) {
            val had = readings[word]
            if (had == null || worth - cost > had.worth - had.cost) readings[word] = Reading(word, worth, cost)
        }
        offer(asTyped, typedWorth, 0f)
        if (stood != asTyped) {
            val cost = decoder.align(stood, 0, TapDecoder.BUDGET)
            if (cost.isInfinite()) return null
            offer(stood, worth(stood, words.getValue(stood)), cost)
        }
        val standing = readings.getValue(stood)
        standing.after = after(stood)
        standing.all = standing.worth - standing.cost + standing.after + (if (stood == asTyped) guard else 0f)
        // The least a reading must be worth, less its slips, for the word after to be able to put it first.
        val least = standing.all + AGAIN_MARGIN - AGAIN_MOST
        val budget = if (known == null) TapDecoder.BUDGET else LISTED_BUDGET
        here?.listed { word ->
            val zipf = words[word] ?: return@listed
            if (word == asTyped || word == stood || kotlin.math.abs(word.length - lower.length) > 2) return@listed
            val w = worth(word, zipf)
            if (w <= least) return@listed
            val cost = decoder.align(word, 0, minOf(budget, w - least))
            if (!cost.isInfinite()) offer(word, w, cost)
        }
        for (h in decoder.search(least, budget, 0, slipsOnly = common && !AGAIN_EDITS)) {
            val word = lex.sorted[h.index]
            if (word == asTyped || word == stood) continue
            val plain = now.board.script.plain(word)
            if (plain != word && words.containsKey(plain)) continue
            offer(word, worth(word, lex.sortedZipf[h.index]), h.cost)
        }
        // The readings nearest what stands are held up to the word after: each costs a look at its rows.
        val nearest = readings.values.filter { it !== standing }.sortedByDescending { (if (it.word == asTyped) 99f else 0f) + it.worth - it.cost }.take(AGAIN_READINGS.toInt())
        var best = standing
        for (r in nearest) {
            // A word no list has is never put back once the space has changed it: the word after cannot speak for it
            // (no row has it, so it is held down by nothing), and the bench's unknown words were the first look's to judge.
            if ((known == null || held) && r.word == asTyped && !AGAIN_UNLISTED) continue
            r.after = after(r.word)
            r.all = r.worth - r.cost + r.after + (if (r.word == asTyped) guard else 0f)
            if (r.all > best.all) best = r
        }
        val trace = TRACE
        if (trace != null) {
            trace("again $lower: stands $stood, then $follower; " + (if (known == null) "in no list" else if (common) "common" else "rare") + ", guard $guard" + (if (here != null) ", at a place" else ", no place"))
            trace("  $stood: worth ${standing.worth}, slips ${standing.cost}, after ${standing.after}, in all ${standing.all} (stands)")
            for (r in nearest) trace("  ${r.word}: worth ${r.worth}, slips ${r.cost}, after ${r.after}, in all ${r.all}")
        }
        if (best === standing || best.all < standing.all + AGAIN_MARGIN || best.after - standing.after < AGAIN_LEAST) return null
        if ((known == null || held) && stood == asTyped && best.all < standing.all + AGAIN_UNKNOWN) return null
        // Putting back what was typed, once the space has changed it, is a word seen changing twice: it must be further ahead.
        if (best.word == asTyped && stood != asTyped && best.all < standing.all + AGAIN_MARGIN + AGAIN_BACK) return null
        trace?.invoke("  chosen: ${best.word}")
        return recase(locale, typed, capitalI(now, best.word)).takeIf { it != stands }
    }

    /**
     * A word has been settled: [typed] stands as [fixed], or as it was typed where that is null. Where that can be
     * trusted its taps teach [lean] where this thumb is landing ([ThumbLean]), each tap against the key of the
     * letter that now stands for it. Trusted is a word of the lists or one of the person's own, with a tap for
     * every letter (an apostrophe that was put in aside): letters no list has may be anything, and a word with a
     * letter missed or added, or two words run together, does not say which tap was which letter's. Nothing is
     * learned where the taps are not read at all (a cautious list).
     */
    fun settled(lean: ThumbLean, typed: String, fixed: String?, taps: Taps?) {
        val now = now
        val lex = now.lexicon
        // Measured in English only: on any other board it learns nothing, and what it held from an English board in
        // the same field (the globe key) is let go, so that nothing is read by it there or back on English.
        if (!now.board.english) { lean.newField(); return }
        lean.pass()
        if (taps == null || !TAPS || lex.cautious || lex.words.isEmpty() || taps.xs.size != typed.length) return
        val locale = now.board.locale
        val lower = typed.lowercase(locale)
        val stands = (fixed ?: typed).lowercase(locale)
        if (lower.length != typed.length || !(lex.words.containsKey(stands) || kept(stands))) return
        val letters = if (stands.length == lower.length) stands else stands.filter { it != '\'' && it != '’' }
        if (letters.length != lower.length) return
        val keys = taps.keys
        val script = now.board.script
        fun key(c: Char): Char = if (keys.containsKey(c)) c else script.plain(c.toString()).let { p -> if (p.length == 1 && keys.containsKey(p[0])) p[0] else 0.toChar() }
        lean.learn(CharArray(letters.length) { key(letters[it]) }, taps)
    }

    /**
     * [taps] as they are read: with how tightly this thumb types ([Taps.tight], [ThumbLean]) only with English alone
     * and the next-word model on, where that was measured; elsewhere (English with another language too) every cost
     * is as it was before there was an estimate.
     */
    private fun spread(now: Now, scorer: Scorer?, taps: Taps): Taps = if (scorer != null && now.lexicon.key == ENGLISH_ALONE) taps else taps.loose()

    /**
     * What [word], of frequency [zipf], is worth at a place the tables have a row for, against the other readings
     * there, in Zipf units. A word no row lists or holds down is worth its own frequency, exactly. Every such word
     * is likelier or less likely there by the same amount (how much room the place leaves for words its rows do not
     * list, [Scorer.Here.room]), and so are letters no list has, so that amount is left out of every word's worth:
     * what [unlisted] makes letters worth against plain frequencies still holds, and a search by frequency finds
     * every such word. Any other word is worth what the place makes it likelier or less likely by than such a
     * word ([Scorer.Here.worth], less the room, over its frequency), counted [HERE_WEIGHT] times.
     */
    private fun atPlace(here: Scorer.Here, word: String, zipf: Float): Float = zipf + HERE_WEIGHT * here.over(word, zipf)

    /**
     * Whether every tap of [lower] landed well inside its own key: nearer it than any other key by at least
     * [CLEAN_TAP] of a key width squared (a tap in a key's middle is a whole one nearer it than the key beside it;
     * one on the line between them, none). Letters with no tap of their own (an apostrophe, a long-press) are passed
     * over; a word with no tap at all, or taps not one a letter, is not.
     */
    private fun onTheirKeys(now: Now, lower: String, taps: Taps): Boolean {
        val w2 = taps.keyW * taps.keyW
        if (taps.xs.size != lower.length || !(w2 > 0f)) return false
        val script = now.board.script
        var any = false
        for (i in lower.indices) {
            if (!taps.has(i)) continue
            val c = lower[i]
            val k = if (taps.keys.containsKey(c)) c else script.plain(c.toString()).let { p -> if (p.length == 1 && taps.keys.containsKey(p[0])) p[0] else return false }
            val at = taps.keys[k] ?: return false
            val x = taps.xs[i]; val y = taps.ys[i]
            val d0 = (x - at.first) * (x - at.first) + (y - at.second) * (y - at.second)
            for ((other, p) in taps.keys) {
                if (other == k) continue
                val d = (x - p.first) * (x - p.first) + (y - p.second) * (y - p.second)
                if ((d - d0) / w2 < CLEAN_TAP) return false
            }
            any = true
        }
        return any
    }

    /**
     * [taps] as the decoder is to read them. By this thumb's lean ([ThumbLean], [Taps.leanX]) only on an English
     * board with prediction on (a [scorer]): that is all it has been measured on (docs/versus.md, "Where this thumb
     * lands"). Anywhere else the lean is taken off again, and every other language, and English with prediction
     * off, reads each word exactly as it did before there was a lean. How tightly the thumb types ([Taps.tight]) is
     * [spread]'s to keep or drop, and is kept here.
     */
    private fun aimed(now: Now, taps: Taps, scorer: Scorer?): Taps =
        if ((taps.leanX == 0f && taps.leanY == 0f) || (now.board.english && scorer != null)) taps
        else Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, 0f, 0f, taps.tight)

    /**
     * What letters no list has are worth against a reading of their taps ([byTaps], [rejoin]), in the Zipf units a
     * listed word's frequency is in.
     *
     * The fixed bars: [UNKNOWN], or [UNKNOWN_ODD] where the letters do not look like a word ([odd]), or
     * [UNKNOWN_LONG] from [LONG_WORD] letters. A fixed bar cannot tell "prrsrnyable" from a name. Where the one list
     * loaded has a letter table (English: [LetterModel]) the letters are also worth what the table makes of them,
     * and the lesser of the two is the answer: the chance that a word no list has was meant here and is spelled so,
     * which is the share of running words no list has times the letters' chance as such a word. In Zipf units that
     * is 9 + log10(share), plus [LETTERS_SHIFT], less [LETTERS_WEIGHT] for each unit the letters cost
     * ([LetterModel.cost]). It falls with every letter, and faster where the letters are ones no word puts
     * together: "kira" and "berber" are worth what the fixed bars say, "prrsrnyable" less than nothing. Where the
     * table has its say [odd] has none ([LETTERS_ODD]): the table says by how much letters do not look like a word.
     *
     * The table only ever lowers a bar, and is not taken at its word. Counted for all it says ([LETTERS_WEIGHT] 1,
     * [LETTERS_SHIFT] 0, no bar for long words above it) it mends 85 of the 326 words the scoreboard's seen set has
     * wrong and changes two in three of the names and terms the typing bench types on purpose: its chance is spread
     * over every string that could be a word, and the words people do type are few of those, so a real name is
     * worth far more than its letters say. docs/versus.md has the curve ("What letters no list has are worth").
     */
    private fun unlisted(now: Now, lower: String, model: LetterModel?): Float {
        val fixed = when {
            lower.length >= 4 && (model == null || LETTERS_ODD > 0f) && odd(lower) -> UNKNOWN_ODD
            lower.length >= LONG_WORD.toInt() -> UNKNOWN_LONG
            else -> UNKNOWN
        }
        if (model == null || lower.length < LETTERS_FROM.toInt()) return fixed
        val cost = model.cost(now.board.script.plain(lower))
        // A character the table has no letter for (another script's, a digit): the fixed bar alone.
        if (cost.isNaN()) return fixed
        return minOf(fixed, maxOf(LETTERS_FLOOR, model.base + LETTERS_SHIFT - LETTERS_WEIGHT * cost))
    }

    /**
     * The letter table [unlisted] answers from, or null for the fixed bars alone: English's, where it is on
     * ([LETTERS]) and the words before are read ([scorer] not null, which it is wherever prediction is off), unless
     * [LETTERS_UNSCORED] lets the table have its say there too.
     */
    private fun letterTable(now: Now, scorer: Scorer?): LetterModel? =
        now.lexicon.letters?.takeIf { LETTERS > 0f && (scorer != null || LETTERS_UNSCORED > 0f) }

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
     * Where [lower] looks like no word: for each letter, whether the run of three it is the middle of is one no
     * common word has (a word's edge counts as a letter, so the first and the last have runs too). Null where
     * every run is some word's, which is [odd] false.
     */
    private fun oddRuns(lower: String): BooleanArray? {
        val seen = runs()
        var out: BooleanArray? = null
        var i = 0
        eachRun(lower) { if (!seen.get(it)) { val o = out ?: BooleanArray(lower.length).also { made -> out = made }; o[i] = true }; i++ }
        return out
    }

    /**
     * Whether the run of three letters with [middle] in its middle lies across the place two words of a join meet:
     * the second starts at the letter [from], and [barred] says the letter before it was the space's own tap,
     * landed on a key. A run across that place is no word's because two words meet there ("rkt" in "worktree"),
     * not because a thumb slipped: it has both letters either side of the gap, or the stray letter, in it.
     */
    private fun meets(middle: Int, from: Int, barred: Boolean) = middle == from - 1 || middle == from || (barred && middle == from - 2)

    /**
     * Whether one of the pieces of [lower] that [join]'s words were read from is a word as it was typed: three
     * letters or more, and as common as a join's first word must be ([JOIN_FIRST]). The letter a tap read as the
     * space typed may belong to the piece on either side of it ("down" and "beat" in "downbeat"). Two words set
     * side by side have at least one of them standing; where neither piece is a word, the letters are no two
     * words yet, whatever they look like ("petsonvtteating").
     */
    private fun beside(lower: String, join: TapDecoder.Join, words: Map<String, Float>): Boolean {
        fun word(from: Int, to: Int) = to - from >= 3 && (words[lower.substring(from, to)] ?: 0f) >= JOIN_FIRST
        val end1 = join.from2 - (if (join.barred2) 1 else 0)
        val last = if (join.third >= 0) join.from3 - (if (join.barred3) 1 else 0) else lower.length
        if (word(0, end1) || word(0, join.from2) || word(join.from2, last) || word(end1, last)) return true
        if (join.third < 0) return false
        return word(join.from2, join.from3) || word(end1, join.from3) || word(join.from3, lower.length) || word(last, lower.length)
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

    /**
     * [word] (lower case) as it is all but always written: a name with its capital (tom is Tom, monday Monday,
     * algeria's Algeria's), I and I'm as [capitalI] has them, any other word as it is.
     *
     * The names are English's ([Lexicon.capitals], dict/en_capitals.txt, made by tool/capitals.py): the words of the
     * list that Tatoeba's sentences write with a capital 19 times in 20 or more where they stand in the middle of a
     * sentence. A word that is a name only some of the time (will, mark, rose, may, bill, march, turkey) is not
     * among them and is left as typed: a capital wrongly given spoils a right word. Never one the person keeps in
     * lower case ([kept]: undoing a capital teaches that, as undoing any fix does), and never a spelling another
     * loaded language writes without a capital as a word of its own ([load]).
     */
    private fun proper(now: Now, word: String): String = name(now, word) ?: capitalI(now, word)

    /**
     * [word] (lower case) with its capital where it is a name to be given one ([proper] says which are), or null
     * where it is none: a word of no list of names, one the person keeps in lower case, any word at all where
     * English is not loaded. What [correct] goes by for a word it would otherwise leave as typed, so that asking
     * for names changes a name and nothing else.
     *
     * On the Turkish board a name that begins with i is none either: I is the capital of ı there and i's is İ, so
     * neither capital is both the board's and the name's (ivan would be Ivan in an English sentence and İvan in a
     * Turkish one, and İvan typed on purpose would be changed to Ivan). It is left as the lone i is ([capitalises]).
     */
    private fun name(now: Now, word: String): String? {
        val caps = now.lexicon.capitals
        if (caps.isEmpty() || word.length < 3) return null
        if (word[0] == 'i' && now.board.locale.language == "tr") return null
        // Nor its plural where the person keeps the name in lower case (Toms is a word of the list of its own).
        caps[word]?.let { return if (kept(word) || (word[word.length - 1] == 's' && word.length > 3 && kept(word.substring(0, word.length - 1)) && word.substring(0, word.length - 1) in caps)) null else it }
        // A name's possessive is the name's.
        if (word.length > 3 && word[word.length - 1] == 's' && (word[word.length - 2] == '\'' || word[word.length - 2] == '’')) {
            val name = word.substring(0, word.length - 2)
            caps[name]?.let { return if (kept(name) || kept(word)) null else it + word.substring(word.length - 2) }
        }
        return null
    }

    /** A fix, or the word as typed, with each of its words as [proper] has it: a fix may be two words. */
    private fun named(now: Now, text: String): String = if (' ' in text) text.split(' ').joinToString(" ") { proper(now, it) } else proper(now, text)

    /**
     * [text] with each name in it given its capital, as [correct] gives one where the field wants capitals: for a
     * word that reached the field another way than through [correct], which is a word put together again by
     * [rejoin] ("lo doj" is "london", and London). A word that has a capital already is left as it is.
     */
    fun named(text: String): String = named(now, text)

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
        /**
         * Told what [byTaps] weighed for a word, a reading a line: for tool/versus/why.py, which asks why a word was
         * left or changed. Null on a phone, where nothing is ever made to tell it.
         */
        @JvmField var TRACE: ((String) -> Unit)? = null
        /** The word that was meant, where [TRACE]'s listener knows: what its slips cost is told too, chosen or not. */
        @JvmField var TRACE_MEANT: String? = null
        /** Reading a word by where its taps landed ([byTaps]), and two words run together: off only for the benchmark's comparisons. */
        @JvmField var TAPS = true
        @JvmField var JOINS = true
        /** Putting a word together again that a tap on the space bar cut in two ([rejoin]), and how much more than what stands the one word must be worth. */
        @JvmField var REJOIN = true
        @JvmField var REJOIN_MARGIN = 1.0f
        /**
         * Two taps on the space bar, one straight after the other, were one double tap where they landed within
         * this much of a key's width of each other ([rejoin]'s doubled; DevKeyboardService.together). Not set on
         * any measurement of double taps, which no set of typings here has: a thumb that never types a full stop
         * with two spaces typed them all. It is half a key because a double tap is one thumb tapping twice
         * without moving, and because the pairs it must tell from one lie further apart: of the 37 times a
         * letter fell on the bar next to a space in the 1,800 typings of tool/versus/scoreboard.py, the two taps
         * were less than half a key apart 3 times, and 1.4 keys apart at the middle one.
         */
        @JvmField var REJOIN_APART = 0.5f
        /**
         * The second look ([again]): the word before, read again once the word after it has ended. Off, every word
         * is settled at its space as before (the scoreboard is then the tree of 9d36d4c word for word, and the
         * bench's lines identical). Set on 2026-11-13 by tool/versus/scoreboard.py --bench (docs/versus.md, "The word
         * before, read again"): on, seen 5.34% to 4.86%, unseen 5.88% to 5.09%, fewer right words spoiled on the bench
         * (careful 1.93 to 1.83 per 1000); on the documents' own sentences (elsewhere.py --at 9d36d4c) 26 words mended, 8 broken.
         */
        @JvmField var AGAIN = true
        /** How much of what the word after makes of a reading ([Scorer.follows]) counts, and the most it may count for one (against one: [AGAIN_LOW]), in Zipf units. Measured before AGAIN_LOW: 1.3 and 1.6 won a word or two on unseen and spoiled more on the bench; 0.7 lost 7 and 17; a cap of 2 lost, 4 was the same as 3. */
        @JvmField var AGAIN_WEIGHT = 1.0f
        @JvmField var AGAIN_MOST = 3.0f
        /** How far ahead of what stands a reading must come, all in, and how much more of that the word after itself must give it. Before AGAIN_LOW: 0 and 0 gave seen 258, unseen 316, but the bench spoiled 2.03 per 1000 careful (1.62 then); a margin of 1.0 gave seen 261, unseen 339. With AGAIN_LOW 1 and AGAIN_BACK 1 (2026-11-13): a margin of 0.25 gave seen 253, unseen 327 and on the documents' sentences 34 mended, 15 broken (0.5: 26 and 8); LEAST 0.25 changed nothing. */
        /** 0.4 and 0.4 since the Joint tuning of 2026-11-13 (docs/versus.md, "Squeezing 0.17.0"): tool/versus/sweep.py search on each held-out half (--holdout 1 and --other-half), the bench's penalty counted against SPREAD_LOW 0.85's bench (--guard), and only the moves both halves made were kept (0.5 and 0.5 before). */
        @JvmField var AGAIN_MARGIN = 0.4f
        @JvmField var AGAIN_LEAST = 0.4f
        /** How much further ahead a reading must be to replace letters no list has that the space left standing (names are the case). 1.0 and 2.5 moved a word or three either way: left as first set. */
        @JvmField var AGAIN_UNKNOWN = 1.5f
        /** How many of the readings nearest what stands are held up to the word after (each costs a look at its rows): 3 lost two words on unseen, 10 the same as 6. */
        @JvmField var AGAIN_READINGS = 6f
        /** Whether the readings of a common word take letters left out and added as well as slips: off lost a word on unseen. */
        @JvmField var AGAIN_EDITS = true
        /**
         * Whether letters no list has may be put back once the space has changed them. Never: the first draft did
         * ("Ery" for Very, "ftlmt" for front on the seen set), since no row has such letters and nothing holds them
         * down. A switch only so that AgainTest can show the guard is what keeps them out.
         */
        @JvmField var AGAIN_UNLISTED = false
        /**
         * The most a pair the tables never saw (or seldom did) counts against a reading, in Zipf units, where [AGAIN_MOST]
         * is the most a pair they list counts for one. Set on 2026-11-13 against the documents' own sentences
         * (tool/versus/elsewhere.py --at 9d36d4c), the one measure not made of Tatoeba's sentences: at 3 (as
         * AGAIN_MOST) the second look mended 36 words there and broke 19; at 1, 27 and 10, with seen 255 to 254 and
         * unseen 325 to 326 words wrong. 0.5 broke 6 but lost 8 words on unseen; 1.5 was as 3.
         */
        @JvmField var AGAIN_LOW = 1.0f
        /**
         * How much further ahead (on top of [AGAIN_MARGIN]) the letters as typed must come to be put back once the space
         * has changed them: a word a person sees change twice. Set on 2026-11-13 with [AGAIN_LOW] at 1: at 0.5 the
         * documents' sentences broke 8 where 0 broke 10 (os, hoe, ein put back), seen 254 to 253, unseen 326 the same;
         * the bench's careful thumb spoiled 1.83 right words per 1000 where 0 spoiled 1.62. 1 and more lost words on unseen.
         */
        @JvmField var AGAIN_BACK = 0.5f
        /** What a typed word no list has is worth against the readings of its taps: see [byTaps]. Set on tool/typing_bench.sh; 3.1 since the Joint tuning of 2026-11-13 (docs/versus.md, "Squeezing 0.17.0"): tool/versus/sweep.py search on each held-out half (--holdout 1 and --other-half), the bench's penalty counted against SPREAD_LOW 0.85's bench (--guard), and only the moves both halves made were kept (3.2 before). */
        @JvmField var UNKNOWN = 3.1f
        @JvmField var UNKNOWN_ODD = 1.8f
        /** What a typed word of [LONG_WORD] letters or more that no list has is worth: see [byTaps]. 1.5 since the Joint tuning of 2026-11-13 (docs/versus.md, "Squeezing 0.17.0"): tool/versus/sweep.py search on each held-out half (--holdout 1 and --other-half), the bench's penalty counted against SPREAD_LOW 0.85's bench (--guard), and only the moves both halves made were kept (1.6 before). */
        @JvmField var UNKNOWN_LONG = 1.5f
        @JvmField var LONG_WORD = 6f
        /**
         * English's letter table ([LetterModel]) has a say in what letters no list has are worth ([unlisted]): at 0
         * they are held to the fixed bars alone, as every other language's are, and the keyboard is as it was before
         * there was a table (the scoreboard and the typing bench say so to the last word).
         */
        @JvmField var LETTERS = 1f
        /**
         * What is added to what the table makes letters worth, and how much of what the letters cost is counted.
         * Measured on the typing bench's own measure (words right less four for every right word spoiled, and the
         * misspellings mended: 93.76 without the table) with the scoreboard's two sets beside it. At a weight of
         * 0.6 the measure is 94.57, 94.60, 94.64, 94.64, 94.62 and 94.52 for a shift of 1.25, 1, 0.75, 0.5, 0.25 and
         * -0.25. Set 2026-11-13 to 1, the least shift tried at which no thumb has more right words spoiled, nor
         * more of the right words no list has, than under the fixed bars, both on the bench's 12,000 words (1.01,
         * 1.21 and 1.03 in a thousand for 1.22, 1.48 and 1.65; 0.8%, 3.0% and 3.6% for 1.5%, 4.3% and 7.1%) and on
         * the tuner's own unseen stretch of the bench, which it was not set on (0.99, 1.14 and 1.36 for 1.18, 1.30
         * and 1.49; 2.5%, 4.0% and 5.5% for 3.1%, 4.7% and 6.2%). 0.75, where it stood first, leaves six fewer of
         * the seen set's words wrong and seven of the unseen set's (281 and 384 for 287 and 391), but on that
         * stretch its sloppy thumb spoils 1.86 right words in a thousand and 8.3% of the unknown ones. Weights of
         * 0.8 and 1 with the shift that suits each (3.05, 5.3) trace the same curve. docs/versus.md has every line.
         */
        @JvmField var LETTERS_SHIFT = 1f
        @JvmField var LETTERS_WEIGHT = 0.6f
        /**
         * The least the table may make letters worth. None: most of what the table mends is long strings and words
         * run together that are worth less than nothing. With a floor of 0, -1, -2, -3 and -4 the bench's measure is
         * 94.23, 94.43, 94.55, 94.60 and 94.64, for 94.64 with none; and a floor gives back little of the time the
         * lower bars cost the search (docs/versus.md).
         */
        @JvmField var LETTERS_FLOOR = -99f
        /**
         * 1 and letters that hold a run no common word has ([odd]) are still held to [UNKNOWN_ODD] where the table
         * has its say; 0 and the table alone says what looks like a word. 0, as measured 2026-11-12: [odd] knows
         * only "has such a run" or "has none", and holds "gyatt" and "kekw" as cheap as "hkpelwss". With it out of
         * the way the bench's unknown words are spoiled as seldom as under the fixed bars at a shift a whole unit
         * lower (0.75 for 1.75), where 11 fewer of the seen set's words and 15 fewer of the unseen set's are left
         * wrong. What it costs: a short slip with such a run in it is no longer held cheap. "truk", "opej", "havun"
         * and "cyss" on the seen set and "musz", "agajn" and "tcis" on the unseen, which the fixed bars mended, are
         * not mended (5 and 3 words broken, as the scoreboard counts them).
         */
        @JvmField var LETTERS_ODD = 0f
        /**
         * The shortest string the table is asked about. 1, every string. With [LETTERS_ODD] 1 and 6 here (the table
         * for long strings only, the fixed bars as they were for the rest) nothing the fixed bars mended is lost down
         * to a shift of -0.25, where the bench's measure is 94.77, the highest of any setting tried; but with the
         * bench's unknown words spoiled as under the fixed bars (a shift of 1.25) it leaves 286 and 396 words of the
         * scoreboard's two sets wrong, where the table in [odd]'s place leaves 281 and 384. With [LETTERS_ODD] 0 it
         * only costs (292 and 400: short strings are then held to [UNKNOWN] whatever their letters).
         */
        @JvmField var LETTERS_FROM = 1f
        /**
         * 1 and a typed word that looks like a listed word with an ending of its own ([inflected]) still gives way
         * only to a reading worth [UNKNOWN], where the table has made it worth less. Left at 1: without the rule 12
         * more of the bench's 264 misspellings are mended and 3 more of the seen set's words (the bench's measure
         * 94.74 for 94.64), and 3 and 2 more of its careful and normal thumbs' unknown words are spoiled, which is
         * what the rule is there to prevent.
         */
        @JvmField var LETTERS_INFLECTED = 1f
        /**
         * 1 and the table has its say where no [Scorer] is passed (prediction off, a terminal, or no place to read
         * the words before from); 0 and those paths hold letters no list has to the fixed bars, as before the table.
         * 0, by the rule that the path with prediction off behaves as it did (review of 2026-11-13). Measured
         * 2026-11-13 with the typing bench's sentences typed with no Scorer at all (tool/typing_bench -Dscorer=none):
         * docs/versus.md ("With prediction off") has both settings against the fixed bars.
         */
        @JvmField var LETTERS_UNSCORED = 0f
        /**
         * 1 and the scraps [rejoin] holds against two words put together are worth what the table says, as in
         * [byTaps]; 0 and they are held to the fixed bars, as before the table. The joins are not this table's to
         * change, so this keeps the two apart for whoever merges: docs/versus.md ("Joins") has both.
         */
        @JvmField var LETTERS_JOINS = 1f
        /** A rare listed word gives way to a word this much commoner, whose slips cost at most [RARE_BUDGET]. */
        @JvmField var RARE_GAP = 2f
        @JvmField var RARE_BUDGET = 2.2f
        /** The least two words run together must be worth to replace a word no list has. */
        @JvmField var JOIN_BAR = 0f
        /**
         * Letters that look like no word ([oddRuns]) are no word the longer they are: for every letter they have
         * over [JOIN_LONG], two or three words run together need be worth this much less to replace them. A thumb
         * that misses the space between two long words, or lands it on a letter, and slips on a letter or two
         * besides, leaves a dozen letters that nobody means ("frimsteangerz" for "from strangers", "gadvzritchds"
         * for "had stitches"). What such letters are worth as a word no list has falls by about one, a letter's
         * worth of chance, with each letter more; [JOIN_BAR] alone held them all to the bar of a six-letter name,
         * and most of the joins of 2026-11-11's run that were still lost had been found and left for that.
         *
         * Not where the letters are two words set side by side, which is how a name or a compound no list has is
         * made: they keep [JOIN_BAR] at any length. That is where every run of letters that looks like no word
         * lies where the best join's words meet ([meets]), and one of the pieces either side of that place is a
         * word as it was typed ([beside]). The best join speaks for the letters: no other is taken. Without the
         * first of those, at 0.75 a letter, "infamousvague", "ghostkeyboard" and "mergeconflict" typed with every
         * tap in the middle of its key came out as two words, and at 1 "worktree" and "midjourney" as well
         * (tool/versus/probe.py words, 166 names and compounds no list has: with the rule the same 4 are changed
         * as before there was any fall). Without the second, two words that each have a slip
         * in them and still look like words were held for a compound ("petsonvtteating" for "person treating"):
         * 4 more words of the seen set left wrong and 2 of the unseen.
         *
         * The fall is for English being loaded: it was set on English typing alone, and the bar of a language
         * that writes a compound as one word is left as it was.
         *
         * Set on tool/versus/scoreboard.py --bench, 2026-11-12, the rest as it stands. Words left wrong of 5,205
         * seen and of 6,408 unseen; then the bench's right words spoiled in a thousand and its unknown words
         * spoiled, for its three thumbs. No fall: 310, 417; 1.22/1.48/1.45, 1.5%/4.3%/7.1%. 0.75 a letter over 6:
         * 294, 406. 1: 291, 406. 1.25: 291, 404, the bench's spoiled the same at all four. 1.5: 288, 404;
         * 1.32/1.48/1.45, 1.9%/4.3%/7.1%. 2: 287, 404; 1.83/1.88/1.86, 3.8%/6.1%/9.5%. 1 a letter over 5: 290, 404;
         * 1.52/1.88/1.65, 2.7%/6.1%/8.3%. Over 7: 294, 407, the bench as at 6. Taken at 1 over 6, in the middle of
         * what spoils nothing and what a letter is worth: a quarter more buys two unseen words and stands next
         * to where the bench's names begin to be parted.
         */
        @JvmField var JOIN_FALL = 1.0f
        @JvmField var JOIN_LONG = 6f
        /**
         * How much of what the words before lift a join's first word by counts ([Scorer.boost], held to
         * [CONTEXT_PICK] first). All of it, as a single word has, parts names no list has into words that are
         * likely where they stand; none of it leaves joins unparted that their place makes plain ("obfrwam" is
         * "I dream", not "a dream"). Set on tool/versus/scoreboard.py --bench, 2026-11-12, the rest as it stands.
         * Words left wrong of 5,205 seen and of 6,408 unseen, with how many of each a half has right and this
         * has not; then the bench's joins fixed, right words spoiled in a thousand and unknown words spoiled,
         * for its three thumbs:
         *   0     297 (6), 410 (4);  64.4/72.3/69.6%;  1.22/1.48/1.65;  1.5/4.3/7.1%
         *   0.25  294 (3), 406 (0);  67.8/74.4/70.1%;  1.22/1.48/1.65;  1.5/4.3/7.1%
         *   0.5   291,     406;      72.2/75.4/71.6%;  1.22/1.48/1.45;  1.5/4.3/7.1%
         *   0.75  293 (2), 406 (2);  72.2/75.9/72.8%;  1.42/1.48/1.65;  1.9/4.3/8.3%
         *   1     293 (2), 406 (2);  73.3/78.5/73.1%;  1.42/1.75/1.65;  1.9/5.5/9.5%
         * Half leaves the fewest words wrong and spoils no more than versus does, which has no lift in its search
         * at all (1.22/1.48/1.65 and 1.5/4.3/7.1%); from three quarters up the bench's names begin to be parted.
         */
        @JvmField var JOIN_LIFT = 0.5f
        /** The least a word must be worth to be offered as what a half-typed word with a slip in it was the start of. */
        @JvmField var FINISH_LEAST = 2.5f
        /**
         * How much more than a listed word a reading must be worth to replace it: a rare word's, and a common word's
         * where there is no place to go by ([HERE_COMMON] where there is). The rare word's bar holds for two words
         * run together, and at a place for any reading. It is not a sensitive number there: 1.0 for 0.6 moved one
         * word of the 6,408 unseen ones, none of the seen, and one or two of the bench's 36,000.
         */
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
        /**
         * Weighing a word by its place in the sentence ([atPlace], [Scorer.Here]) wherever a starter table has a
         * row for the words before that says how often it was seen: off only for the benchmark's comparisons. Off,
         * every word is weighed as it was before the place was read, by its frequency and a capped lift.
         */
        @JvmField var HERE = true
        /**
         * How many times over the place's say counts ([atPlace]). At 1 a word is worth just how likely the tables
         * make it there, and that is too sure of itself: the tables were counted from Tatoeba, a word the rows do
         * not list is only guessed at from its frequency, and what a slip costs is no exact likelihood either.
         *
         * Set on 2026-11-12 on tool/typing_bench.sh's tap test (12,000 words, three thumbs; its tuner's measure is
         * words right less four for every right word spoiled, with misspellings) and tool/versus/scoreboard.py's
         * seen set together, with the unseen set beside them:
         *
         *     weight   measure   seen wrong   unseen wrong   right words spoiled in 1000 (careful, normal, sloppy)
         *     1.0      94.30     5.46%        5.95%          3.04  2.83  4.14
         *     0.8      94.53     5.42%        5.88%          2.13  1.88  3.10
         *     0.7      94.59     5.40%        5.91%          1.93  1.61  2.07
         *     0.6      94.50     5.44%        5.95%          1.83  1.48  2.07
         *     off      93.76     6.26%        6.82%          1.22  1.48  1.65
         *
         * The rows are of the tree before a join's second word was weighed by [atPlace] as well; with that, 0.7
         * gives 94.60, 5.34% and 5.88%, with 1.93, 1.75 and 2.07. Counting the place in full up to a lift of 1.5
         * or of 1.0 and by half beyond was tried in its stead, and gives 94.46 and 94.44.
         */
        @JvmField var HERE_WEIGHT = 0.7f
        /**
         * How much more than a common typed word a reading must be worth to replace it at a place: [MARGIN_COMMON]
         * is the bar where there is no place to go by. It is lower than that one. There the typed word's worth says
         * nothing of where it stands and the bar does all the guarding; here a word that belongs in its place is
         * worth more to begin with. Set with [HERE_WEIGHT], by the same measures, at weight 0.7:
         *
         *     bar      measure   seen wrong   unseen wrong   right words spoiled in 1000
         *     0.8      94.58     5.34%        5.88%          2.13  2.02  2.69
         *     1.0      94.59     5.40%        5.91%          1.93  1.61  2.07
         *     1.3      94.44     5.51%        5.99%          1.83  1.48  1.86
         *
         * and at weight 0.8 the bar of 1.6 that [MARGIN_COMMON] has gives 94.36, 5.73% and 6.07% with 1.73 / 1.48 /
         * 1.86: the place read with no bar lowered still mends more than it did unread.
         */
        @JvmField var HERE_COMMON = 1.0f
        /**
         * A common typed word may be read as a word the rows list at its place of another length (a letter missed
         * or added), where [TapDecoder.search] reads a common word by slips of the thumb alone: "he" typed where "the"
         * stands is mended by it, and "too" is now and then made "to". A word of the same length the rows list is
         * held against the taps whatever this says, with a swap or an apostrophe among its slips. Off, by [HERE_WEIGHT]'s measures: 94.34, 5.53% and
         * 6.09% for 94.59, 5.40% and 5.91%, with 1.83 / 1.61 / 2.07 right words spoiled in a thousand for 1.93 /
         * 1.61 / 2.07.
         */
        @JvmField var LISTED_EDITS = true
        /**
         * How far inside its own key every tap of a listed word must land for the place to be read as it was before
         * it was weighed ([onTheirKeys], [byTaps]): how much nearer its own key's middle than any other's, in key
         * widths squared (1 for a tap in the middle of a key, beside a key a width away; 0 on the line between two).
         * After the code review of 2026-11-13: with the place read, a right word with every tap in the middle of its
         * key was rewritten where the tables expect a neighbour ("Its" opening a sentence was always "It's"), and an
         * apostrophe, a doubled letter or a letter added owe nothing to where the taps landed. 0.7 is a tap within
         * about 0.15 of a key of the middle, toward its nearest neighbour. Measured 2026-11-13 (the bench's measure,
         * seen and unseen words wrong; then mended and broken against 0.5 on prose that is not Tatoeba's, this
         * repository's documents and another project's, 7,119 words each typed by the three thumbs, tool/versus/whatif.py):
         *
         *     clean at   measure   seen     unseen   not Tatoeba's, against 0.5
         *     never      94.58     5.34%    5.88%    +19 -16
         *     0.7        94.51     5.38%    5.93%    +11 -10
         *     0.5        94.42     5.51%    6.05%
         *     0.3        94.35     5.59%    6.15%    (the careful thumb spoils 1.62 right words in 1000 for 1.93)
         *
         * With taps that scatter as the thumbs' do it is near nothing either way on prose that is not Tatoeba's; with
         * every tap in the middle of its key (the code review's typing) it takes every change the place alone made
         * away. 0.7 does that for 2 words of the seen set and 3 of the unseen.
         */
        @JvmField var CLEAN_TAP = 0.7f
        /**
         * Whether at a place a listed typed word may be read as a word it differs from by apostrophes alone ("its" as
         * "it's", "were" as "we're", "hell" as "he'll"). Off: an apostrophe is typed with no tap of its own, so the
         * taps say nothing for such a reading and the place alone would make it, and Tatoeba's sentences, which the
         * tables were counted from, all but never open with "Its". The code review of 2026-11-13 found 11 of the 14
         * clean words the place changed in this repository's documents, and 16 of 33 in another project's, were
         * its to it's. Measured 2026-11-13: on the seen and unseen sets and the bench nothing moves either way (no word
         * mended there was such a reading); on the prose that is not Tatoeba's ([CLEAN_TAP]'s), allowing them breaks 19
         * words and mends none.
         */
        @JvmField var PLACE_APOSTROPHE = false
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
         * follows it [JOIN_UNLISTED] as often as the least it does, at most. This is the bound where there is no
         * place to go by; at a place it is [Scorer.UNLISTED], for a join's second word as for any reading.
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

        /** English's list alone, the one lexicon how tightly this thumb types ([ThumbLean.tight]) was measured on. */
        private val ENGLISH_ALONE = key(Script.LATIN, listOf("en"))

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

/**
 * How likely a string of letters is as a word no list has ([Autocorrect.unlisted]): runs of up to [order] letters
 * with a start and an end mark, counted over the words of real sentences that are in no list, each kind of word
 * once. Read from assets/dict/<language>_letters.tsv, which tool/letters.py makes (English's alone so far): its
 * first line that is no comment holds the order and the [share] of running words that no list had, and every other
 * line a run of letters ("^" a word's start, "$" its end, the apostrophe a letter), what its last letter costs after
 * the ones before it, and what is added before going by one letter less where a longer run is not in the file. Costs
 * are minus the log to base ten of a chance, in hundredths: the units of a Zipf frequency.
 *
 * A run is kept as a number, five bits a mark, so a look-up is a binary search of [keys] and nothing is made for it.
 */
internal class LetterModel private constructor(val order: Int, val share: Float, private val keys: IntArray, private val costs: ShortArray, private val backs: ShortArray) {
    /** What a word no list has would be worth were every such word the same one: 9 + log10 of their [share] of running words. */
    val base: Float = 9f + kotlin.math.log10(share)

    /** How many runs the table holds. */
    val size: Int get() = keys.size

    /**
     * What [word]'s letters cost: minus the log to base ten of its chance as a word no list has, so that
     * [base] less this is what it is worth in Zipf units. NaN where it holds a character the table has no letter
     * for (anything but a to z and the apostrophe: spell it plainly first, [Script.plain]).
     */
    fun cost(word: String): Float {
        val mask = (1 shl (BITS * (order - 1))) - 1
        var before = 0
        repeat(order - 1) { before = (before shl BITS) or START }
        var total = 0
        for (i in 0..word.length) {
            val c = if (i == word.length) END else letter(word[i])
            if (c == 0) return Float.NaN
            // The longest run the table has that ends in this letter: each shorter one tried costs what its longer
            // one says is added for going without it.
            var k = order - 1
            while (true) {
                val run = if (k == 0) 0 else before and ((1 shl (BITS * k)) - 1)
                val at = java.util.Arrays.binarySearch(keys, (run shl BITS) or c)
                if (at >= 0 && costs[at] >= 0) { total += costs[at]; break }
                if (k == 0) { total += UNSEEN; break }
                val b = java.util.Arrays.binarySearch(keys, run)
                if (b >= 0) total += backs[b]
                k--
            }
            before = ((before shl BITS) or c) and mask
        }
        return total / 100f
    }

    companion object {
        private const val BITS = 5
        private const val START = 28
        private const val END = 29
        /** A letter the file gives no cost at all, which no file tool/letters.py writes does: as dear as nine units. */
        private const val UNSEEN = 900
        /** The longest run a number holds: six marks of five bits. */
        private const val MOST = 6

        /** A letter of a word as the table counts it: 1 to 26 for a to z, 27 for an apostrophe, 0 for anything else. */
        private fun letter(c: Char): Int = when (c) {
            in 'a'..'z' -> c - 'a' + 1
            '\'', '’' -> 27
            else -> 0
        }

        /** A mark of a run in the file: a letter, or the start or the end of a word. */
        private fun mark(c: Char): Int = when (c) {
            '^' -> START
            '$' -> END
            else -> letter(c)
        }

        /** The table in [stream], or null where there is none or it is not one (the fixed bars then stand). */
        fun read(stream: InputStream?): LetterModel? {
            stream ?: return null
            var order = 0
            var share = 0f
            // A row as one number, to sort by run without making an object a row: the run, then the cost and one
            // (nothing where the file gives none), then what going one letter shorter adds.
            var rows = LongArray(16_384)
            var n = 0
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isEmpty() || line[0] == '#') continue
                    val f = line.split('\t')
                    if (f[0] == "=") {
                        var i = 1
                        while (i + 1 < f.size) {
                            when (f[i]) { "order" -> order = f[i + 1].toIntOrNull() ?: 0; "share" -> share = f[i + 1].toFloatOrNull() ?: 0f }
                            i += 2
                        }
                        continue
                    }
                    if (f[0].length > MOST) continue
                    var key = 0
                    for (ch in f[0]) { val m = mark(ch); if (m == 0) { key = -1; break }; key = (key shl BITS) or m }
                    if (key <= 0) continue
                    val cost = f.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 32_000) ?: -1
                    val back = f.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 32_000) ?: 0
                    if (n == rows.size) rows = rows.copyOf(n * 2)
                    rows[n++] = (key.toLong() shl 32) or ((cost + 1).toLong() shl 16) or back.toLong()
                }
            }
            if (order !in 1..MOST || share <= 0f || share > 1f || n == 0) return null
            rows = rows.copyOf(n)
            rows.sort()
            return LetterModel(
                order, share, IntArray(n) { (rows[it] ushr 32).toInt() },
                ShortArray(n) { (((rows[it] ushr 16) and 0xFFFF).toInt() - 1).toShort() }, ShortArray(n) { (rows[it] and 0xFFFF).toInt().toShort() },
            )
        }
    }
}
