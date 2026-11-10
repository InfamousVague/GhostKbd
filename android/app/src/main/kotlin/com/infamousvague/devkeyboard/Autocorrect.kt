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
 * Mockingbird reads the same dictionaries to take slips out of its writing samples ([slip], [lexicon]): a stricter
 * judgement than [correct]'s, since nobody is there to undo it.
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
    /** Swipe typing's index: words by their (accent-free) first and last letters, "hello" under "ho". */
    @Volatile private var byEnds: Map<String, List<String>> = emptyMap()
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
            val plain = fold(word)
            if (plain.length < 2 || plain.length > 20 || !plain.all { it in 'a'..'z' }) continue
            ends.getOrPut("${plain.first()}${plain.last()}") { ArrayList() }.add(word)
        }
        words = w; folded = f; contractions = c
        sorted = w.keys.toTypedArray().also { it.sort() }
        byEnds = ends
        ready = true
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
     * Swipe typing: the likeliest words for a finger's [path] over the letters, best first. Each candidate starts
     * near where the finger came down and ends near where it lifted; its ideal path (straight lines through its
     * letters' keys) is compared with the finger's, both resampled to the same number of points, and the mean
     * distance in key widths weighed against how common the word is.
     */
    fun swipe(path: List<android.graphics.PointF>, keys: Map<Char, android.graphics.PointF>, keyW: Float): List<String> {
        if (!ready || path.size < 2 || keyW <= 0f) return emptyList()
        val n = 40
        val user = resample(path.map { it.x to it.y }, n)
        val start = user.first(); val end = user.last()
        fun near(p: Pair<Float, Float>, limit: Float) = keys.entries
            .map { (c, k) -> c to dist(p, k.x to k.y) / keyW }
            .filter { it.second <= limit }.sortedBy { it.second }.take(4).map { it.first }
        val starts = near(start, 1.1f); val ends = near(end, 1.1f)
        val words = words
        val scored = ArrayList<Pair<String, Float>>()
        for (a in starts) for (b in ends) {
            val list = byEnds["$a$b"] ?: continue
            for (word in list) {
                val plain = fold(word)
                // The word's keys, a doubled letter once ("hello" glides h-e-l-o).
                val pts = ArrayList<Pair<Float, Float>>(plain.length)
                var prev = ' '
                for (c in plain) { if (c != prev) { val k = keys[c] ?: break; pts += k.x to k.y }; prev = c }
                if (pts.isEmpty()) continue
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
        return scored.sortedByDescending { it.second }.map { capitalI(it.first) }.distinct().take(5)
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
     * [typed] was plainly a slip of, in [typed]'s capitals, or null when it should stand. Stricter than [correct]:
     * - [correct]'s own guards hold (ACRONYMS, camelCase, learned words), and slang counts whether or not its
     *   switch is on;
     * - nothing that is a way of writing rather than a slip: no "i" → "I", no apostrophe put into "dont", no
     *   accent put onto "cafe";
     * - two letters swapped ("teh") is always a slip. Any other fix needs a spelling the lists have never seen
     *   ("nite" and "yas" are in them, rare: somebody means those) and an ordinary slip: a neighbouring key, a
     *   letter doubled or dropped, not a far key or a missing first letter;
     * - the fix must be likely ([SLIP_SCORE]; a short word has many near neighbours, "gurl" beside "girl", so
     *   [SLIP_SCORE_SHORT] unless its letters were swapped) and well ahead of the next word it could have been
     *   ([SLIP_MARGIN]): "hwo" is who or how, so it stands.
     */
    fun slip(typed: String): String? {
        if (!ready || typed.length < 3 || typed.length > 24) return null
        if (typed.all { it.isUpperCase() } || typed.drop(1).any { it.isUpperCase() }) return null
        val lower = typed.lowercase()
        if (isWord(lower)) return null
        val known = words[lower]
        val pick = pick(lower, known) ?: return null
        val swapped = pick.word.length == lower.length && pick.word.toCharArray().sorted() == lower.toCharArray().sorted()
        if (!swapped && (known != null || pick.cost > SLIP_COST)) return null
        val bar = if (!swapped && lower.length <= SHORT) SLIP_SCORE_SHORT else SLIP_SCORE
        if (pick.score < bar || pick.score - pick.next < SLIP_MARGIN) return null
        val cased = recase(typed, pick.word)
        return if (cased == typed) null else cased
    }

    /**
     * [lower] is a word nobody would fix: common in a loaded language, a contraction typed without its apostrophe,
     * a word spelled without its accents, a learned word, or texting slang (the whole list, its switch on or off).
     */
    fun isWord(lower: String): Boolean =
        lower in learned || lower in slangList || (words[lower] ?: 0f) >= COMMON || contractions.containsKey(lower) || folded.containsKey(lower)

    /** [lower] is in the loaded lists at all, however rare: what tells a language with a dictionary from one without. */
    fun isListed(lower: String): Boolean = isWord(lower) || words.containsKey(lower)

    /**
     * These dictionaries as [SampleCleaner] reads them. Each fix is worked out once and remembered for as long as
     * the returned object is kept, so make one per pass over the samples, not one to keep.
     */
    fun lexicon(): SampleCleaner.Lexicon = object : SampleCleaner.Lexicon {
        private val fixes = HashMap<String, String?>()
        override val ready get() = isReady
        override fun isWord(lower: String) = this@Autocorrect.isWord(lower)
        override fun isListed(lower: String) = this@Autocorrect.isListed(lower)
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
        /** The dearest slip [slip] takes for an accident: two neighbouring keys. Dearer ones are a far key or a first letter. */
        private const val SLIP_COST = 2.0f
        /** [slip]'s short word: up to this many letters. */
        private const val SHORT = 5
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"
        /** A learned word counts as moderately common (about one in 30,000 words) when a slip lands near it. */
        private const val LEARNED_ZIPF = 4.0f
        /** Slang ranks as about one in 50,000 words among completions; it is never a target for correcting a slip. */
        private const val SLANG_ZIPF = 3.7f

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
