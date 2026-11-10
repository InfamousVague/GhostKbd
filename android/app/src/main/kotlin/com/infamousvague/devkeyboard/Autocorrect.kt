package com.infamousvague.devkeyboard

import android.content.Context
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Autocorrect for one word at a time, run when a word is finished (space or punctuation): a word the dictionaries do
 * not know, or know only as a rare spelling, is replaced by the likeliest real word a slip or two away.
 *
 * The dictionaries are assets/dict/<language>.tsv: each language's commonest words with their Zipf frequency (from the
 * `wordfreq` project's "best" lists, CC BY-SA 4.0; 40,000 for English, 30,000 for the others), plus English's
 * apostrophe-less contractions (dict/en_contractions.tsv: dont → don't). Several languages load together, so mixing
 * them in one message works. A word typed without its accents (cafe, senor) finds the accented one cheaply.
 *
 * A candidate scores its frequency minus what its slips cost: a neighbouring key is a cheap slip, a far one dear, a
 * doubled or missing letter in between. Neighbours are QWERTY's, so "keybkard" finds "keyboard" first.
 *
 * Learned words (a fix the person undid, or words added in Settings) are never changed, and count as words for
 * correcting near-misses of them.
 *
 * Mockingbird reads the same dictionaries to take slips out of its writing samples ([slip], [lexicon]): a far
 * stricter judgement than [correct]'s, since nobody is there to undo it. A word the lists miss is not a slip for
 * that alone.
 */
class Autocorrect(context: Context) {
    private val app = context.applicationContext
    @Volatile private var words: Map<String, Float> = emptyMap()
    @Volatile private var folded: Map<String, String> = emptyMap()
    @Volatile private var contractions: Map<String, String> = emptyMap()
    @Volatile private var learned: Set<String> = emptySet()
    /** Texting slang (assets/dict/en_slang.txt) while its switch is on: left alone, and offered as you type. */
    @Volatile private var slang: Set<String> = emptySet()
    @Volatile private var slangOn = false
    private val slangList: Set<String> by lazy {
        runCatching {
            app.assets.open("dict/en_slang.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim().lowercase() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
            }
        }.getOrDefault(emptySet())
    }
    /** Every word, sorted, for completions: a prefix's words are one run of it. */
    @Volatile private var sorted: Array<String> = emptyArray()
    /** Swipe typing's index: words by the first and last of their letters (accents and apostrophes off), "what's" under "ws". */
    @Volatile private var byEnds: Map<String, List<String>> = emptyMap()
    /** The starter tables' rows (assets/ngram/<language>.tsv), each word's followers as written there, parsed when asked. */
    @Volatile private var pairRows: Map<String, String> = emptyMap()
    /** Swipe typing's decoder, over these word lists ([SwipeDecoder]). */
    private val decoder = SwipeDecoder(object : SwipeDecoder.Lexicon {
        override fun ends(first: Char, last: Char): List<String> {
            val listed = byEnds["$first$last"].orEmpty()
            val mine = learned.filter { it.isNotEmpty() && !words.containsKey(it) && swipeLetters(it).let { l -> l.isNotEmpty() && l.first() == first && l.last() == last } }
            return if (mine.isEmpty()) listed else listed + mine
        }
        override fun zipf(word: String): Float? = words[word] ?: if (word in learned) LEARNED_ZIPF else null
        override fun letters(word: String) = swipeLetters(word)
        override fun follows(prev: String): Map<String, Float>? = pairRows[prev]?.let { row ->
            val out = HashMap<String, Float>()
            for (e in row.split(' ')) {
                val c = e.lastIndexOf(':')
                if (c > 0) e.substring(c + 1).toFloatOrNull()?.let { out[e.substring(0, c).lowercase()] = it / 10_000f }
            }
            out
        }
    })
    @Volatile private var ready = false
    private var loaded: List<String> = emptyList()

    /** Loads [languages]' dictionaries in the background, unless they are the ones already loaded. */
    @Synchronized
    fun setLanguages(languages: List<String>) {
        if (languages == loaded) return
        loaded = languages
        thread(name = "autocorrect-dictionary", isDaemon = true) { load(languages) }
    }

    /**
     * Loads [languages]' dictionaries on the calling thread and returns once they are in: for a worker that needs the
     * answers now and has no keyboard to borrow them from (Mockingbird cleaning its samples from Settings).
     */
    fun loadNow(languages: List<String>) {
        synchronized(this) { loaded = languages }
        load(languages)
    }

    /** True once the dictionaries are in; until then nothing is corrected. */
    val isReady: Boolean get() = ready

    /** Reads [languages]' word lists and builds the indexes; whichever thread runs it. */
    private fun load(languages: List<String>) {
        val w = HashMap<String, Float>(48_000 * languages.size)
        val c = HashMap<String, String>(600)
        for (lang in languages) {
            runCatching {
                app.assets.open("dict/$lang.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) {
                            val word = line.substring(0, tab)
                            val zipf = line.substring(tab + 1).toFloatOrNull() ?: return@forEach
                            if (zipf > (w[word] ?: Float.NEGATIVE_INFINITY)) w[word] = zipf
                        }
                    }
                }
            }
            if (lang == "en") runCatching {
                app.assets.open("dict/en_contractions.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) c[line.substring(0, tab)] = line.substring(tab + 1)
                    }
                }
            }
        }
        // The words of a programmer's day, which a general list misses or ranks as typos (npm, async...).
        for (word in DEV_WORDS) w[word] = maxOf(w[word] ?: 0f, 4.5f)
        // Each accented word under its plain spelling, the commonest winning: cafe → café, senor → señor.
        val f = HashMap<String, String>()
        for ((word, zipf) in w) {
            val plain = fold(word)
            if (plain != word && (w[plain] ?: 0f) < zipf && (f[plain]?.let { w[it]!! < zipf } != false)) f[plain] = word
        }
        val ends = HashMap<String, ArrayList<String>>()
        for (word in w.keys) {
            // Letters and apostrophes only ("what's" glides w-h-a-t-s), and a one-letter word only if it's common.
            if (word.any { !it.isLetter() && it != '\'' }) continue
            val plain = swipeLetters(word)
            if (plain.isEmpty() || plain.length > 20 || !plain.all { it in 'a'..'z' }) continue
            if (plain.length == 1 && (w[word] ?: 0f) < SwipeDecoder.ONE_LETTER) continue
            ends.getOrPut("${plain.first()}${plain.last()}") { ArrayList() }.add(word)
        }
        // Which word follows which (assets/ngram), kept as each row's text until swipe typing asks for it.
        val rows = HashMap<String, String>()
        for (lang in languages) runCatching {
            app.assets.open("ngram/$lang.tsv").bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line ->
                    val tab = line.indexOf('\t')
                    if (tab > 0) { val key = line.substring(0, tab).lowercase(); if (key !in rows) rows[key] = line.substring(tab + 1) }
                }
            }
        }
        words = w; folded = f; contractions = c
        sorted = w.keys.toTypedArray().also { it.sort() }
        byEnds = ends
        pairRows = rows
        synchronized(decoder) { decoder.clear() }
        ready = true
    }

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

    /** How common a slang word counts, for ranking it among completions: its list frequency, else fairly common. */
    private fun slangZipf(word: String) = maxOf(words[word] ?: 0f, SLANG_ZIPF)

    /** What [typed] should become, or null to leave it. [typed] is letters and apostrophes only. */
    fun correct(typed: String): String? {
        if (!ready || typed.isEmpty() || typed.length > 24) return null
        // An acronym or a deliberate capitalised name of two letters or more is left as typed.
        if (typed.length > 1 && typed.all { it.isUpperCase() }) return null
        // camelCase, iOS, McDonald: a capital after the first letter is deliberate.
        if (typed.drop(1).any { it.isUpperCase() }) return null
        val lower = typed.lowercase()
        if (kept(lower)) return null
        val fixed = best(lower) ?: return null
        val cased = recase(typed, fixed)
        return if (cased == typed) null else cased
    }

    /**
     * What the row above the keys offers for the word being typed: the fix autocorrect would make (or the word itself
     * when it is a word), then the commonest words it begins. [typedIsWord] says whether the typed word is one.
     */
    class Suggestions(val words: List<String>, val fix: String?, val typedIsWord: Boolean)

    fun suggest(typed: String): Suggestions {
        if (!ready || typed.isEmpty() || typed.length > 24) return Suggestions(emptyList(), null, false)
        val lower = typed.lowercase()
        val words = words
        val isWord = kept(lower) || (words[lower] ?: 0f) > 0f || contractions.containsKey(lower)
        val fix = if (kept(lower)) null else best(lower)
        val out = LinkedHashSet<String>()
        fix?.let { out += recase(typed, it) }
        if (isWord) out += recase(typed, contractions[lower]?.let(::capitalI) ?: capitalI(lower))
        // Completions: the commonest few words starting with what is typed (and with its accent-free spelling).
        val arr = sorted
        var lo = java.util.Arrays.binarySearch(arr, lower).let { if (it < 0) -it - 1 else it }
        val top = ArrayList<Pair<String, Float>>()
        var scanned = 0
        while (lo < arr.size && arr[lo].startsWith(lower) && scanned < 30_000) {
            val cand = arr[lo]
            if (cand != lower) top += cand to (if (cand in slang) slangZipf(cand) else words[cand] ?: 0f)
            lo++; scanned++
        }
        for (l in learned) if (l.startsWith(lower) && l != lower) top += l to LEARNED_ZIPF
        for (l in slang) if (l.startsWith(lower) && l != lower && !words.containsKey(l)) top += l to SLANG_ZIPF
        top.sortByDescending { it.second }
        for ((cand, _) in top) { if (out.size >= 5) break; out += recase(typed, capitalI(cand)) }
        return Suggestions(out.toList(), fix?.let { recase(typed, it) }, isWord)
    }

    /**
     * Swipe typing: the likeliest readings of a finger's [path] over the letters, best first: a word, or several
     * words in one stroke ("hey what's up"). [before] is the word before the cursor in lower case, "^" at a
     * sentence's start, or null. The decoding is [SwipeDecoder]'s.
     */
    fun swipe(path: List<android.graphics.PointF>, keys: Map<Char, android.graphics.PointF>, keyW: Float, before: String? = null): List<String> {
        if (!ready || path.size < 2 || keyW <= 0f) return emptyList()
        return synchronized(decoder) {
            // The same keys keep the same map, so the decoder keeps the words' shapes it has worked out for them.
            if (keys != swipeKeysFrom) {
                swipeKeysFrom = HashMap(keys.mapValues { (_, p) -> android.graphics.PointF(p.x, p.y) })
                swipeKeys = keys.mapValues { (_, p) -> p.x to p.y }
                warm(swipeKeys)
            }
            decoder.decode(path.map { it.x to it.y }, swipeKeys, keyW, before)
        }
    }

    /**
     * Works out every word's shape on these [keys] in the background, a pair of letters at a time, so the strokes
     * after the first don't wait for it. A stroke meanwhile waits for one pair at most.
     */
    private fun warm(keys: Map<Char, Pair<Float, Float>>) {
        val pairs = byEnds.keys.toList()
        thread(name = "swipe-warm", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            for (pair in pairs) {
                if (swipeKeys !== keys) return@thread
                synchronized(decoder) { decoder.warm(pair[0], pair[1], keys) }
            }
        }
    }

    private var swipeKeysFrom: Map<Char, android.graphics.PointF>? = null
    @Volatile private var swipeKeys: Map<Char, Pair<Float, Float>> = emptyMap()

    private fun best(lower: String): String? {
        if (lower == "i") return "I"
        contractions[lower]?.let { return capitalI(it) }
        val known = words[lower]
        // Typed without its accents: the accented word, unless the plain spelling is itself as common.
        folded[lower]?.let { accented -> if (known == null || known < COMMON) return accented }
        // A common word is never changed; a rare one only for a far commoner word one cheap slip away.
        if (known != null && known >= COMMON) return capitalI(lower).takeIf { it != lower }
        val pick = pick(lower, known) ?: return null
        if (pick.score < MIN_SCORE) return null
        return capitalI(pick.word)
    }

    /** The likeliest word a slip or two from a typed one: its [score], what its slips [cost], and the [next] best word's score. */
    private class Pick(val word: String, val score: Float, val cost: Float, val next: Float)

    /**
     * The best-scoring word among those a slip or two from [lower], and the score of the runner-up. [known] is
     * [lower]'s own frequency when the lists have it as a rare word: then only a cheap slip to a far commoner word
     * can win, though any word still counts as a runner-up.
     */
    private fun pick(lower: String, known: Float?): Pick? {
        val words = words
        var bestWord: String? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var bestCost = 0f
        var next = Float.NEGATIVE_INFINITY
        for ((candidate, cost) in candidates(lower)) {
            val zipf = words[candidate] ?: contractions[candidate]?.let { words[it] }
                ?: (if (candidate in learned) LEARNED_ZIPF else null) ?: folded[candidate]?.let { words[it] } ?: continue
            val score = zipf - cost * COST_WEIGHT
            val word = contractions[candidate] ?: folded[candidate]?.takeIf { candidate !in words } ?: candidate
            val mayWin = known == null || (cost <= 1.5f && zipf >= known + 2f)
            if (mayWin && score > bestScore) {
                if (word != bestWord) next = maxOf(next, bestScore)
                bestScore = score; bestCost = cost; bestWord = word
            } else if (word != bestWord && score > next) {
                next = score
            }
        }
        return bestWord?.let { Pick(it, bestScore, bestCost, next) }
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
        if (!ready || typed.length < 3 || typed.length > 24) return null
        if (typed.all { it.isUpperCase() } || typed.drop(1).any { it.isUpperCase() }) return null
        val lower = typed.lowercase()
        if (isWord(lower)) return null
        val fixed = if (words.containsKey(lower)) {
            LISTED_SLIPS[lower]?.takeIf { (words[it] ?: 0f) >= COMMON } ?: return null
        } else {
            // Asked first, being cheap: most long words the lists miss look like words, and stand.
            if (lower.length > SHORT && !odd(lower)) return null
            val pick = pick(lower, null) ?: return null
            if (shape(lower, pick.word) == null) return null
            val bar = if (lower.length <= SHORT) SLIP_SCORE_SHORT else SLIP_SCORE
            if (pick.score < bar || pick.score - pick.next < SLIP_MARGIN) return null
            pick.word
        }
        val cased = recase(typed, fixed)
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
    private fun shape(typed: String, fix: String): Slip? {
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
        val loaded = words
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
    fun isWord(lower: String): Boolean =
        lower in learned || lower in slangList || (words[lower] ?: 0f) >= COMMON || contractions.containsKey(lower) || folded.containsKey(lower)

    /**
     * These dictionaries as [SampleCleaner] reads them. Each fix is worked out once and remembered for as long as
     * the returned object is kept, so make one per pass over the samples, not one to keep.
     */
    fun lexicon(): SampleCleaner.Lexicon = object : SampleCleaner.Lexicon {
        private val fixes = HashMap<String, String?>()
        override val ready get() = isReady
        override fun isWord(lower: String) = this@Autocorrect.isWord(lower)
        override fun fix(token: String): String? = if (fixes.containsKey(token)) fixes[token] else slip(token).also { fixes[token] = it }
    }

    /** I, I'm, I've, I'll, I'd: English capitalises them wherever they fall. */
    private fun capitalI(word: String) = if (word == "i" || word.startsWith("i'")) "I" + word.substring(1) else word

    /** The typed word's capitals on the correction: all, first, or none. */
    private fun recase(typed: String, fixed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> fixed.uppercase()
        typed.first().isUpperCase() -> fixed.replaceFirstChar { it.uppercaseChar() }
        else -> fixed
    }

    /** Every word one slip from [w], and two neighbouring-key slips from it, with what the slips cost. */
    private fun candidates(w: String): Map<String, Float> {
        val out = HashMap<String, Float>()
        fun offer(s: String, cost: Float) { if (s.isNotEmpty() && s != w) out[s] = minOf(out[s] ?: Float.MAX_VALUE, cost) }
        for (i in w.indices) {
            // A letter too many: cheap when it doubles its neighbour or sits beside it on the keyboard.
            val extra = w[i]
            val cheap = (i > 0 && (w[i - 1] == extra || near(w[i - 1], extra))) || (i + 1 < w.length && (w[i + 1] == extra || near(w[i + 1], extra)))
            // A first letter is rarely the slip (npm is not pm, async is not sync).
            offer(w.removeRange(i, i + 1), if (i == 0) 2.2f else if (cheap) 1.0f else 1.6f)
            // The wrong letter: a neighbour is the commonest slip.
            for (c in ALPHABET) if (c != w[i]) offer(w.replaceRange(i, i + 1, c.toString()), if (near(w[i], c)) 1.0f else 2.4f)
            // Two letters swapped.
            if (i + 1 < w.length) offer(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2), 1.2f)
        }
        // A letter missed.
        for (i in 0..w.length) for (c in ALPHABET) offer(w.substring(0, i) + c + w.substring(i), 1.7f)
        // Two neighbouring-key slips in one word.
        if (w.length >= 3) {
            for (i in w.indices) for (a in NEIGHBOURS[w[i]].orEmpty()) {
                val once = w.replaceRange(i, i + 1, a.toString())
                for (j in i + 1 until w.length) for (b in NEIGHBOURS[w[j]].orEmpty()) offer(once.replaceRange(j, j + 1, b.toString()), 2.0f)
            }
        }
        return out
    }

    private fun near(a: Char, b: Char) = NEIGHBOURS[a]?.contains(b) == true

    companion object {
        /** Zipf 3.2 is about one in 600,000 words: below it a "word" in the list is as likely a common misspelling. */
        private const val COMMON = 3.2f
        private const val COST_WEIGHT = 1.6f
        private const val MIN_SCORE = 0.8f
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
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"
        /** A learned word counts as moderately common (about one in 30,000 words) when a slip lands near it. */
        private const val LEARNED_ZIPF = 4.0f
        /** Slang ranks as about one in 50,000 words among completions; it is never a target for correcting a slip. */
        private const val SLANG_ZIPF = 3.7f

        /** [word]'s letters for swipe typing: lower case, accents off, apostrophes dropped ("What's" is "whats"). */
        fun swipeLetters(word: String): String = fold(word.lowercase()).filter { it != '\'' && it != '’' }

        /** [word] without its accents: é → e, ñ → n, ß → ss. */
        fun fold(word: String): String =
            java.text.Normalizer.normalize(word.replace("ß", "ss"), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

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

        /** Each letter's QWERTY neighbours, Gboard's stagger: the rows sit half a key and a key and a half in. */
        private val NEIGHBOURS: Map<Char, Set<Char>> = run {
            val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
            val at = HashMap<Char, Pair<Float, Int>>()
            rows.forEachIndexed { r, (letters, inset) -> letters.forEachIndexed { i, c -> at[c] = (i + inset) to r } }
            at.mapValues { (c, p) ->
                at.filter { (o, q) -> o != c && abs(q.second - p.second) <= 1 && abs(q.first - p.first) <= 1.01f }.keys
            }
        }
    }
}
