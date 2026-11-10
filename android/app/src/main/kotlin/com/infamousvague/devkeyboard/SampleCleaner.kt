package com.infamousvague.devkeyboard

/**
 * Mockingbird's sample cleaner (Matt, 2026-11-09: "Is the write like me functionality taking into account auto
 * correct so it doesn't assume I'm just leaving mistakes"): takes the obvious accidental slips out of a sample before
 * the AI learns from it, so a one-off "teh" is not read as how the person writes.
 *
 * A wrong repair changes what the person said, and nobody is there to undo it; a slip left in costs little, since
 * the AIs are told to disregard one ([VoiceRules]). So a word is repaired only when all of this holds, and anything
 * less sure is left exactly as typed:
 * - the sample reads as a language there is a dictionary for ([foreign]), judged from the words the dictionaries
 *   know and nothing else, and the words on either side of this one are known too ([settled]): a word of another
 *   language in an English message is not a slip;
 * - it stands by itself between spaces, with at most ordinary punctuation or an emoji round it: nothing code-shaped
 *   (a digit, an underscore, a dot or slash inside, an @ or #, a URL, an email address, a hyphenated compound);
 * - it is not a word in any language switched on, not a learned word and not texting slang ([Lexicon.isWord]);
 * - it is not a capitalised word anywhere but the plain start of a sentence, which is a name: after a line break,
 *   an emoji or "Dr." a capital proves nothing, so it stands;
 * - it is not written for effect: a stretched or lengthened word (sooo, yess, heyy, whaat, musta), laughter
 *   (hahah, lolol), or a vowel-less abbreviation (thx, tmrw);
 * - the person has not typed it repeatedly ([Habits.deliberate]: three times or more, in at least two samples),
 *   which makes it theirs, for good;
 * - the dictionaries have one confident correction for it ([Lexicon.fix]: a slip that shows, not merely a word
 *   they lack), and that correction changes letters only: it neither adds nor drops an apostrophe or an accent;
 * - the correction is a word the person uses ([Habits.used]): it is somewhere else in their samples, spelled right.
 *
 * Punctuation, spacing, capitals, emoji and the choice of words are never touched: they are style. The fix keeps the
 * word's capitals ("Teh" → "The").
 *
 * No Android here, so it can be checked off the phone: tool/check_sample_cleaner.sh runs it against the real
 * dictionaries.
 */
object SampleCleaner {
    /** The rules' version, kept with each cleaned sample: raise it and stored samples are cleaned again. */
    const val VERSION = 2

    /** Typed this often, in this many different samples, a word that is in no dictionary is the person's own. */
    const val REPEATED_TIMES = 3
    const val REPEATED_SAMPLES = 2

    /** What the cleaner asks of the dictionaries: Autocorrect answers on the phone. */
    interface Lexicon {
        /** False until the dictionaries are loaded; nothing is cleaned without them. */
        val ready: Boolean

        /**
         * [lower] is a word nobody would fix: common in a language that is on, learned, or slang. It is also how a
         * language with a dictionary is told from one without: a rare entry will not do, since the English list
         * holds a little of every language ("heb", "buena", "gracias").
         */
        fun isWord(lower: String): Boolean

        /**
         * The one word [token] (letters and apostrophes) was plainly a slip of, in [token]'s capitals; null to leave
         * it. Being in no list is not enough for an answer: the slip itself must show.
         */
        fun fix(token: String): String?
    }

    /**
     * A word of a sample that stands by itself: where it [start]s and [end]s, and whether it plainly opens a
     * sentence (the sample's first word, or the one after a full stop, ! ? or …).
     */
    class Token(val start: Int, val end: Int, val word: String, val sentenceStart: Boolean)

    /**
     * What the cleaner knows about the person from all their samples: the words they type on purpose although no
     * dictionary has them ([deliberate]), and the words they have typed at all ([used]), both lower case.
     */
    class Habits(val deliberate: Set<String>, val used: Set<String>)

    /** Characters that, touching a word, make it code-shaped: a handle, a tag, a path, a flag, markup. */
    private const val CODE_BEFORE = "@#/\\._-:\$`<>{}[]=|&%^+~"
    private const val CODE_AFTER = "@#/\\_-\$`<>{}[(=|&%^+~"

    /** An apostrophe, typed plain or curled: part of a word (don't), not its edge. */
    private fun apostrophe(c: Char) = c == '\'' || c == '’'

    /** Always ends a sentence. A full stop may not: it also closes an abbreviation ("Dr.", "etc."). */
    private fun stop(c: Char) = c == '!' || c == '?' || c == '…'

    /** A plain word longer than this, with a full stop after it, ends a sentence; a shorter one may be "Dr." or "St.". */
    private const val ABBREVIATION = 3

    /**
     * The words of [text] the cleaner may look at: each run of letters (and apostrophes) that fills its
     * space-separated chunk but for punctuation at its edges. Code-shaped chunks give no token at all.
     *
     * A word opens a sentence only where that is plain: first in the text, or after ! ? …, or after a full stop
     * that follows a word of four letters or more. A line break, an emoji and a short word with a full stop ("Dr.",
     * "St.", "e.g.") prove nothing, so a capitalised word after one is taken for a name.
     */
    fun tokens(text: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        var sentenceStart = true
        while (i < text.length) {
            if (text[i].isWhitespace()) { i++; continue }
            var end = i
            while (end < text.length && !text[end].isWhitespace()) end++
            var a = i
            while (a < end && !text[a].isLetterOrDigit()) a++
            var b = end
            while (b > a && !text[b - 1].isLetterOrDigit()) b--
            if (a < b) {
                val plain = (a until b).all { text[it].isLetter() || apostrophe(text[it]) } &&
                    (i until a).none { text[it] in CODE_BEFORE } && (b until end).none { text[it] in CODE_AFTER }
                if (plain) out += Token(a, b, text.substring(a, b), sentenceStart)
                sentenceStart = (b until end).any { stop(text[it]) } ||
                    (plain && b - a > ABBREVIATION && (b until end).any { text[it] == '.' })
            } else if ((i until end).any { stop(text[it]) || text[it] == '.' }) {
                sentenceStart = true
            }
            i = end
        }
        return out
    }

    /** [word] folded for looking up: lower case, a typographic apostrophe as a plain one. */
    private fun key(word: String) = word.lowercase().replace('’', '\'')

    /** Written for effect, not by accident: a letter three times running, or four letters and more made of two. */
    private fun forEffect(lower: String): Boolean {
        for (i in 2 until lower.length) if (lower[i] == lower[i - 1] && lower[i] == lower[i - 2]) return true
        return lower.length >= 4 && lower.toSet().size <= 2
    }

    /** Has a vowel (an accented letter counts as one): a word without any is an abbreviation, thx or tmrw. */
    private fun hasVowel(lower: String) = lower.any { it in "aeiouy" || it.code > 127 }

    /** [lower] is long enough, and plain enough, to be judged at all. */
    private fun judged(lower: String) = lower.length in 3..20 && !forEffect(lower) && hasVowel(lower)

    /** [lower] is a word that could be a slip: plain enough to judge, and no dictionary's, learned or slang word. */
    private fun suspect(lower: String, lexicon: Lexicon) = judged(lower) && !lexicon.isWord(lower)

    /**
     * What [texts] (every sample, as typed) say about the person's words, in one pass over them.
     * [Habits.deliberate] is the words no dictionary has that they typed [REPEATED_TIMES] times or more, in at
     * least [REPEATED_SAMPLES] different texts; [Habits.used] is every word they typed. With [among], only those
     * words are looked for ([wanted], for one new sample), which is all a single sample needs.
     */
    fun habits(texts: Iterable<String>, lexicon: Lexicon, among: Set<String>? = null): Habits {
        if (!lexicon.ready || among?.isEmpty() == true) return Habits(emptySet(), emptySet())
        val times = HashMap<String, Int>()
        val samples = HashMap<String, Int>()
        for (text in texts) {
            val seen = HashSet<String>()
            for (t in tokens(text)) {
                val k = key(t.word)
                if (among != null && k !in among) continue
                times[k] = (times[k] ?: 0) + 1
                if (seen.add(k)) samples[k] = (samples[k] ?: 0) + 1
            }
        }
        val deliberate = HashSet<String>()
        for ((k, n) in times) if (n >= REPEATED_TIMES && (samples[k] ?: 0) >= REPEATED_SAMPLES && suspect(k, lexicon)) deliberate += k
        return Habits(deliberate, times.keys)
    }

    /**
     * The words of [text] the dictionaries would repair, and the words they would become (all lower case): what to
     * look for in the other samples ([habits]) before anything is changed. Empty for a sample with nothing to
     * repair, which is most of them.
     */
    fun wanted(text: String, lexicon: Lexicon): Set<String> {
        if (!lexicon.ready) return emptySet()
        val out = HashSet<String>()
        for (t in tokens(text)) { val fix = candidate(t, lexicon) ?: continue; out += key(t.word); out += key(fix) }
        return out
    }

    /**
     * True when [tokens] do not read as a language the dictionaries cover: more than a quarter of the words, and
     * more than one, are not words the dictionaries know ([Lexicon.isWord]). It is judged from those words alone,
     * never from what a word could be corrected to: a word of another language often sits one slip from an English
     * one ("nog" beside "not"), and that says nothing. Names, and words stretched or abbreviated for effect, are not
     * counted either way. A message that is a quarter slips is left too: there is no telling it from this.
     */
    fun foreign(tokens: List<Token>, lexicon: Lexicon): Boolean {
        var words = 0
        var unknown = 0
        for (t in tokens) {
            val k = key(t.word)
            if (name(t) || forEffect(k) || !hasVowel(k)) continue
            words++
            if (!lexicon.isWord(k)) unknown++
        }
        return unknown > maxOf(1, words / 4)
    }

    /** A capital anywhere but the plain start of a sentence: a name, a place, a product. Not the cleaner's to respell. */
    private fun name(token: Token) = token.word.first().isUpperCase() && !token.sentenceStart

    /**
     * True when the word at [index] sits among words the dictionaries know: the word before it and the word after
     * are each known (a name or the sample's edge passes), and at least one of them is a known word. A word
     * whose neighbours are unknown too is likelier another language, a run of names or a term than a slip.
     */
    private fun settled(tokens: List<Token>, index: Int, lexicon: Lexicon): Boolean {
        val before = tokens.getOrNull(index - 1)
        val after = tokens.getOrNull(index + 1)
        fun known(t: Token?) = t != null && !name(t) && lexicon.isWord(key(t.word))
        fun open(t: Token?) = t == null || name(t)
        return (known(before) || open(before)) && (known(after) || open(after)) && (known(before) || known(after))
    }

    /** [lower] is [fixed] with one of its vowels typed twice ("whaat", "loove"): a word stretched, not a slip. */
    private fun stretched(lower: String, fixed: String): Boolean {
        if (lower.length != fixed.length + 1) return false
        for (i in 1 until lower.length) {
            if (lower[i] == lower[i - 1] && lower[i] in "aeiouy" && lower.removeRange(i, i + 1) == fixed) return true
        }
        return false
    }

    /**
     * What the dictionaries alone make of [token]: the word it was a slip of, or null when it stands. Nothing about
     * the person or the rest of the sample is asked here; [slipFix] does that.
     */
    private fun candidate(token: Token, lexicon: Lexicon): String? {
        val word = token.word
        val lower = key(word)
        if (!suspect(lower, lexicon) || name(token)) return null
        val fix = lexicon.fix(word) ?: return null
        val fixed = key(fix)
        if (fixed == lower || fix.any { it.isWhitespace() }) return null
        // Apostrophes and accents are how someone writes: a fix may move letters, never add or drop those.
        if (marks(lower) != marks(fixed)) return null
        // "heyy", "yess", "musta": a word with more on its end is said that way, not slipped.
        if (lower.length > fixed.length && lower.startsWith(fixed)) return null
        // "whaat", "loove": so is one with a vowel doubled.
        if (stretched(lower, fixed)) return null
        return fix
    }

    /**
     * The decision for the word at [index] of a sample's [tokens]: what it should have been, or null when it
     * stands. [habits] is what the person's samples say ([SampleCleaner.habits]): a word they type repeatedly
     * stands, and a fix is made only to a word they use. The sample-wide test ([foreign]) is [clean]'s.
     */
    fun slipFix(tokens: List<Token>, index: Int, lexicon: Lexicon, habits: Habits): String? {
        val token = tokens[index]
        if (key(token.word) in habits.deliberate) return null
        val fix = candidate(token, lexicon) ?: return null
        if (key(fix) !in habits.used || !settled(tokens, index, lexicon)) return null
        return fix
    }

    /** A new sample cleaned: its [text], the words [kept] as the person's own afterwards, and whether one is [fresh]. */
    class Cleaned(val text: String, val kept: Set<String>, val fresh: Boolean)

    /**
     * One new sample, [typed], with its obvious slips repaired. [others] is every other sample as typed: the words
     * that would be changed, and what they would become, are looked for across them first ([habits]). [kept] is
     * the words judged the person's own before now ([Habits.deliberate], remembered so that a sample dropping off
     * the end cannot undo it). When a word of this sample has just become one, [Cleaned.fresh] says so: the
     * samples it was taken out of earlier need [cleanAll] to get it back.
     */
    fun cleanNew(typed: String, others: List<String>, lexicon: Lexicon, kept: Set<String>): Cleaned {
        val wanted = wanted(typed, lexicon)
        if (wanted.isEmpty()) return Cleaned(typed, kept, false)
        val seen = habits(others + typed, lexicon, wanted)
        val all = kept + seen.deliberate
        return Cleaned(clean(typed, lexicon, Habits(all, seen.used)), all, all.size > kept.size)
    }

    /**
     * Every sample cleaned again from what was [typed], with the habits counted over all of them: the texts in the
     * same order, and [kept] with the words that have since become the person's own. A word in [kept] stays
     * theirs however few samples still hold it.
     */
    fun cleanAll(typed: List<String>, lexicon: Lexicon, kept: Set<String>): Pair<List<String>, Set<String>> {
        if (!lexicon.ready) return typed to kept
        val seen = habits(typed, lexicon)
        // Only a word the dictionaries would change needs remembering: any other stands whatever is kept.
        val all = kept + seen.deliberate.filter { lexicon.fix(it) != null }
        val habits = Habits(seen.deliberate + all, seen.used)
        return typed.map { clean(it, lexicon, habits) } to all
    }

    /** The characters of [lower] that are not plain letters (apostrophes, accented letters), in order. */
    private fun marks(lower: String) = lower.filter { it !in 'a'..'z' }.toList().sorted()

    /**
     * [text] with its obvious slips repaired, and everything else as it was. [habits] is what all the person's
     * samples, as typed, say about their words ([SampleCleaner.habits]). Unchanged when the dictionaries are not
     * loaded or do not cover the sample's language. Cleaning a cleaned text changes nothing.
     */
    fun clean(text: String, lexicon: Lexicon, habits: Habits): String {
        if (!lexicon.ready) return text
        val tokens = tokens(text)
        if (tokens.isEmpty() || foreign(tokens, lexicon)) return text
        var out: StringBuilder? = null
        for (i in tokens.indices.reversed()) {
            val fix = slipFix(tokens, i, lexicon, habits) ?: continue
            out = (out ?: StringBuilder(text)).replace(tokens[i].start, tokens[i].end, fix)
        }
        return out?.toString() ?: text
    }
}
