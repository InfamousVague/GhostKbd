package com.infamousvague.devkeyboard

import android.content.Context
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Autocorrect for one word at a time, run when a word is finished (space or punctuation): a word the dictionary does
 * not know, or knows only as a rare spelling, is replaced by the likeliest real word a slip or two away.
 *
 * The dictionary is the 40,000 commonest English words with their Zipf frequency (assets/en_words.tsv, from the
 * `wordfreq` project's "best" English list, CC BY-SA 4.0), and the apostrophe-less spellings of contractions
 * (assets/en_contractions.tsv: dont → don't). A candidate scores its frequency minus what its slips cost: a
 * neighbouring key is a cheap slip, a far one dear, a doubled or missing letter in between. Neighbours are QWERTY's,
 * so "keybkard" finds "keyboard" (k sits next to o) before anything else.
 */
class Autocorrect(context: Context) {
    private val words = HashMap<String, Float>(48_000)
    private val contractions = HashMap<String, String>(600)
    @Volatile private var ready = false

    init {
        val app = context.applicationContext
        thread(name = "autocorrect-dictionary", isDaemon = true) {
            runCatching {
                app.assets.open("en_words.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) words[line.substring(0, tab)] = line.substring(tab + 1).toFloat()
                    }
                }
                app.assets.open("en_contractions.tsv").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val tab = line.indexOf('\t')
                        if (tab > 0) contractions[line.substring(0, tab)] = line.substring(tab + 1)
                    }
                }
                // The words of a programmer's day, which a general list misses or ranks as typos (npm, async...).
                for (word in DEV_WORDS) words[word] = maxOf(words[word] ?: 0f, 4.5f)
                ready = true
            }
        }
    }

    /** What [typed] should become, or null to leave it. [typed] is letters and apostrophes only. */
    fun correct(typed: String): String? {
        if (!ready || typed.isEmpty() || typed.length > 24) return null
        // An acronym or a deliberate capitalised name of two letters or more is left as typed.
        if (typed.length > 1 && typed.all { it.isUpperCase() }) return null
        val lower = typed.lowercase()
        val fixed = best(lower) ?: return null
        val cased = recase(typed, fixed)
        return if (cased == typed) null else cased
    }

    private fun best(lower: String): String? {
        if (lower == "i") return "I"
        contractions[lower]?.let { return capitalI(it) }
        val known = words[lower]
        // A common word is never changed; a rare one only for a far commoner word one cheap slip away.
        if (known != null && known >= COMMON) return capitalI(lower).takeIf { it != lower }
        var bestWord: String? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for ((candidate, cost) in candidates(lower)) {
            val zipf = words[candidate] ?: contractions[candidate]?.let { words[it] } ?: continue
            if (known != null && (cost > 1.5f || zipf < known + 2f)) continue
            val score = zipf - cost * COST_WEIGHT
            if (score > bestScore) { bestScore = score; bestWord = contractions[candidate] ?: candidate }
        }
        if (bestWord == null || bestScore < MIN_SCORE) return null
        return capitalI(bestWord)
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
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"

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
