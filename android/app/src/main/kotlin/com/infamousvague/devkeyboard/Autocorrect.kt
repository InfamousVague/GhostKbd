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
            // Swipe typing: any word of 2 to 20 letters, under its plain first and last letter.
            if (plain.length < 2 || plain.length > 20 || !plain.all { it.isLetter() }) continue
            ends.getOrPut("${plain.first()}${plain.last()}") { ArrayList() }.add(word)
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
        return Lexicon(
            key, w, f, c, sorted, FloatArray(sorted.size) { w[sorted[it]] ?: 0f }, plainKeys, plainWords, FloatArray(plainWords.size) { w[plainWords[it]] ?: 0f },
            ends, gives, commonest, sharedI, languages.any { it !in FIRST_SIX },
        )
    }

    /** Words the person has kept: never corrected, and targets for correcting slips of them. */
    fun setLearned(words: Set<String>) { learned = words }

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
     * context gives it, at most [CONTEXT_LIFT]; so do the person's own often-typed words that begin with it. A word
     * may then be in [top] twice: [suggest] takes the higher-ranked one, with its capitals where it has them (Monday).
     */
    private fun inContext(now: Now, lower: String, scorer: Scorer, top: ArrayList<Pair<String, Float>>) {
        val words = now.lexicon.words
        val script = now.board.script
        for (f in scorer.following(lower) { script.plain(it) }) {
            val zipf = words[f.lower] ?: 0f
            val base = maxOf(zipf, scorer.personalZipf(f.lower, zipf > 0f || isWord(f.lower)))
            if (base > 0f) top += f.shown to base + minOf(scorer.boost(f.lower, base), CONTEXT_LIFT)
        }
        for (p in scorer.personal(lower)) top += p.shown to p.share
    }

    /**
     * Swipe typing: the likeliest words for a finger's [path] over the letters, best first. Each candidate starts
     * near where the finger came down and ends near where it lifted; its ideal path (straight lines through its
     * letters' keys) is compared with the finger's, both resampled to the same number of points, and the mean
     * distance in key widths weighed against how common the word is.
     */
    fun swipe(path: List<android.graphics.PointF>, keys: Map<Char, android.graphics.PointF>, keyW: Float): List<String> {
        val now = now
        if (now.lexicon.words.isEmpty() || path.size < 2 || keyW <= 0f) return emptyList()
        val script = now.board.script
        val byEnds = now.lexicon.byEnds
        val n = 40
        val user = resample(path.map { it.x to it.y }, n)
        val start = user.first(); val end = user.last()
        fun near(p: Pair<Float, Float>, limit: Float) = keys.entries
            .map { (c, k) -> c to dist(p, k.x to k.y) / keyW }
            .filter { it.second <= limit }.sortedBy { it.second }.take(4).map { it.first }
        val starts = near(start, 1.1f); val ends = near(end, 1.1f)
        val words = now.lexicon.words
        val scored = ArrayList<Pair<String, Float>>()
        // The index is by plain letters, so a key that is an accented letter (Turkish ö) looks under its plain one.
        val pairs = LinkedHashSet<String>()
        for (a in starts) for (b in ends) pairs += "${script.plain(a.toString()).first()}${script.plain(b.toString()).last()}"
        for (pair in pairs) {
            val list = byEnds[pair] ?: continue
            for (word in list) {
                // The word's keys, a doubled letter once ("hello" glides h-e-l-o). A letter's key is its own or, for
                // a letter this board keeps behind a long-press, its plain letter's; a word with a letter that has
                // neither cannot be glided here and is passed over.
                val pts = ArrayList<Pair<Float, Float>>(word.length)
                var prev = ' '
                var typable = true
                for (c in word) {
                    if (keys.containsKey(c)) { if (c != prev) { val k = keys[c]!!; pts += k.x to k.y }; prev = c; continue }
                    for (p in script.plain(c.toString())) {
                        val k = keys[p]
                        if (k == null) { typable = false; break }
                        if (p != prev) pts += k.x to k.y
                        prev = p
                    }
                    if (!typable) break
                }
                if (!typable || pts.isEmpty()) continue
                // Quick test: every letter passes near the path, in order.
                var at = 0; var ok = true
                for (p in pts) {
                    while (at < n && dist(user[at], p) > keyW * 1.25f) at++
                    if (at >= n) { ok = false; break }
                }
                if (!ok) continue
                val ideal = if (pts.size == 1) List(n) { pts[0] } else resample(pts, n)
                var d = 0f
                for (i in 0 until n) d += dist(user[i], ideal[i])
                d /= n * keyW
                val zipf = words[word] ?: LEARNED_ZIPF
                scored += word to (zipf * 0.55f - d * 6f)
            }
        }
        for (l in learned) {
            // Learned words glide too: scored the same way when their ends match.
            if (l.length < 2 || l.first() !in starts || l.last() !in ends || words.containsKey(l)) continue
            val pts = l.mapNotNull { keys[it]?.let { k -> k.x to k.y } }
            if (pts.size != l.length) continue
            val ideal = resample(pts, n)
            var d = 0f
            for (i in 0 until n) d += dist(user[i], ideal[i])
            scored += l to (LEARNED_ZIPF * 0.55f - d / (n * keyW) * 6f)
        }
        return scored.sortedByDescending { it.second }.map { capitalI(now, it.first) }.distinct().take(5)
    }

    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = kotlin.math.hypot(a.first - b.first, a.second - b.second)

    /** [pts] as [n] points evenly spaced along its length. */
    private fun resample(pts: List<Pair<Float, Float>>, n: Int): List<Pair<Float, Float>> {
        var total = 0f
        for (i in 1 until pts.size) total += dist(pts[i - 1], pts[i])
        if (total <= 0f) return List(n) { pts.first() }
        val step = total / (n - 1)
        val out = ArrayList<Pair<Float, Float>>(n)
        out += pts.first()
        var acc = 0f
        var i = 1
        var prev = pts.first()
        while (i < pts.size && out.size < n) {
            val cur = pts[i]
            val d = dist(prev, cur)
            if (acc + d >= step && d > 0f) {
                val t = (step - acc) / d
                val q = (prev.first + t * (cur.first - prev.first)) to (prev.second + t * (cur.second - prev.second))
                out += q; prev = q; acc = 0f
            } else { acc += d; prev = cur; i++ }
        }
        while (out.size < n) out += pts.last()
        return out
    }

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
        var bestWord: String? = null
        var bestFrom = ""
        var bestScore = Float.NEGATIVE_INFINITY
        for ((candidate, cost) in candidates(now.board, lower)) {
            val zipf = words[candidate] ?: contractions[candidate]?.let { words[it] }
                ?: (if (candidate in learned) LEARNED_ZIPF else null) ?: folded[candidate]?.let { words[it] } ?: continue
            if (known != null && (cost > 1.5f || zipf < known + 2f)) continue
            val plain = zipf - cost * COST_WEIGHT
            val word = contractions[candidate] ?: folded[candidate]?.takeIf { candidate !in words } ?: candidate
            // Context only chooses among corrections good enough without it; with no scorer the score is the plain one.
            val score = if (scorer == null || plain < MIN_SCORE) plain else plain + minOf(scorer.boost(word, zipf), CONTEXT_PICK)
            if (score > bestScore) { bestScore = score; bestWord = word; bestFrom = candidate }
        }
        if (bestWord == null || bestScore < MIN_SCORE) return null
        val sure = !now.lexicon.cautious || (bestScore >= CAUTIOUS_SCORE && !ending(now.board, lower, bestFrom) && !inflected(words, lower, bestFrom))
        return Fix(capitalI(now, bestWord), sure)
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
        /** The most the word before may lift a completion's rank, and a correction's score, in Zipf units. */
        private const val CONTEXT_LIFT = 4.0f
        private const val CONTEXT_PICK = 1.5f
        /** A learned word counts as moderately common (about one in 30,000 words) when a slip lands near it. */
        private const val LEARNED_ZIPF = 4.0f
        /** Slang ranks as about one in 50,000 words among completions; it is never a target for correcting a slip. */
        private const val SLANG_ZIPF = 3.7f

        /** The name of a set of word lists as one lexicon: the script and the lists' stems ("LATIN:en,pl"); empty with no list. */
        fun key(script: Script, dictionaries: List<String>): String = if (dictionaries.isEmpty()) "" else script.name + ":" + dictionaries.joinToString(",")

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
