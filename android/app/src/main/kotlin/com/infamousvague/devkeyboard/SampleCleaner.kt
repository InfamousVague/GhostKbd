package com.infamousvague.devkeyboard

/**
 * Mockingbird's sample cleaner (Matt, 2026-11-09: "Is the write like me functionality taking into account auto
 * correct so it doesn't assume I'm just leaving mistakes"): takes the obvious accidental slips out of a sample before
 * the AI learns from it, so a one-off "teh" is not read as how the person writes.
 *
 * A word is repaired only when all of this holds; anything less sure is left exactly as typed:
 * - it stands by itself between spaces, with at most ordinary punctuation or an emoji round it: nothing code-shaped
 *   (a digit, an underscore, a dot or slash inside, an @ or #, a URL, an email address, a hyphenated compound);
 * - it is not a word in any language switched on, not a learned word and not texting slang ([Lexicon.isWord]);
 * - it is not a capitalised word in the middle of a sentence, which is a name;
 * - it is not written for effect: a stretched or lengthened word (sooo, yess, heyy, musta), laughter (hahah,
 *   lolol), or a vowel-less abbreviation (thx, tmrw);
 * - the person has not typed it repeatedly ([repeated]: three times or more, in at least two samples), which makes
 *   it theirs;
 * - the dictionaries have one confident correction for it ([Lexicon.fix]), and that correction changes letters
 *   only: it neither adds nor drops an apostrophe or an accent;
 * - the sample is in a language there is a dictionary for ([foreign]): where much of it is words no list has and
 *   no correction explains, all of it is left.
 *
 * Punctuation, spacing, capitals, emoji and the choice of words are never touched: they are style. The fix keeps the
 * word's capitals ("Teh" → "The").
 *
 * No Android here, so it can be checked off the phone: tool/check_sample_cleaner.sh runs it against the real
 * dictionaries.
 */
object SampleCleaner {
    /** The rules' version, kept with each cleaned sample: raise it and stored samples are cleaned again. */
    const val VERSION = 1

    /** Typed this often, in this many different samples, a word that is in no dictionary is the person's own. */
    const val REPEATED_TIMES = 3
    const val REPEATED_SAMPLES = 2

    /** What the cleaner asks of the dictionaries: Autocorrect answers on the phone. */
    interface Lexicon {
        /** False until the dictionaries are loaded; nothing is cleaned without them. */
        val ready: Boolean

        /** [lower] is a word nobody would fix: common in a language that is on, learned, or slang. */
        fun isWord(lower: String): Boolean

        /** [lower] is in the word lists at all, however rare: how a language with a dictionary is told from one without. */
        fun isListed(lower: String): Boolean

        /** The one confident correction for [token] (letters and apostrophes), in [token]'s capitals; null to leave it. */
        fun fix(token: String): String?
    }

    /** A word of a sample that stands by itself: where it [start]s and [end]s, and whether it opens a sentence. */
    class Token(val start: Int, val end: Int, val word: String, val sentenceStart: Boolean)

    /** Characters that, touching a word, make it code-shaped: a handle, a tag, a path, a flag, markup. */
    private const val CODE_BEFORE = "@#/\\._-:\$`<>{}[]=|&%^+~"
    private const val CODE_AFTER = "@#/\\_-\$`<>{}[(=|&%^+~"

    /** An apostrophe, typed plain or curled: part of a word (don't), not its edge. */
    private fun apostrophe(c: Char) = c == '\'' || c == '’'

    /** Ends a sentence: a full stop, ! ? …, or an emoji or other symbol, after which people start afresh. */
    private fun endsSentence(c: Char) = c == '.' || c == '!' || c == '?' || c == '…' || c.code >= 0x2190

    /**
     * The words of [text] the cleaner may look at: each run of letters (and apostrophes) that fills its
     * space-separated chunk but for punctuation at its edges. Code-shaped chunks give no token at all.
     */
    fun tokens(text: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        var sentenceStart = true
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                // A new line starts afresh, as a full stop does: chat lines and lists rarely end in one.
                if (text[i] == '\n') sentenceStart = true
                i++
                continue
            }
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
                sentenceStart = (b until end).any { endsSentence(text[it]) }
            } else if ((i until end).any { endsSentence(text[it]) }) {
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

    /**
     * The words of [text] that are in no dictionary (lower case): the ones that could be slips, and so the ones
     * worth counting across the samples.
     */
    fun suspects(text: String, lexicon: Lexicon): Set<String> {
        if (!lexicon.ready) return emptySet()
        val out = HashSet<String>()
        for (t in tokens(text)) { val k = key(t.word); if (judged(k) && !lexicon.isWord(k)) out += k }
        return out
    }

    /**
     * The words the person types on purpose although no dictionary has them: those among [texts]' suspects seen
     * [REPEATED_TIMES] times or more, in at least [REPEATED_SAMPLES] different texts. With [among], only those
     * words are counted (the suspects of one new sample), which is all a single sample needs.
     */
    fun repeated(texts: Iterable<String>, lexicon: Lexicon, among: Set<String>? = null): Set<String> {
        if (!lexicon.ready || among?.isEmpty() == true) return emptySet()
        val times = HashMap<String, Int>()
        val samples = HashMap<String, Int>()
        for (text in texts) {
            val seen = HashSet<String>()
            for (t in tokens(text)) {
                val k = key(t.word)
                if (among != null) { if (k !in among) continue } else if (!judged(k) || lexicon.isWord(k)) continue
                times[k] = (times[k] ?: 0) + 1
                if (seen.add(k)) samples[k] = (samples[k] ?: 0) + 1
            }
        }
        return times.filterTo(HashMap()) { (k, n) -> n >= REPEATED_TIMES && (samples[k] ?: 0) >= REPEATED_SAMPLES }.keys
    }

    /**
     * True when [tokens] read as a language the dictionaries do not cover: two or more words that are in no list at
     * all and have no likely correction either, making up over a third of the sample. Its words would all look
     * like slips, so none is touched. (A slip is in no list too, which is why only the words with no correction
     * count: "teh cat adn dog" is English.) Looks up corrections only for a sample already over the line, and stops
     * at the answer.
     */
    fun foreign(tokens: List<Token>, lexicon: Lexicon): Boolean {
        // Names (a capital mid-sentence) are in no list in any language, so they say nothing either way.
        val words = tokens.filter { !name(it) }
        val unlisted = words.filter { !lexicon.isListed(key(it.word)) }
        if (unlisted.size < 2 || unlisted.size * 3 <= words.size) return false
        var strange = 0
        for (t in unlisted) {
            if (!judged(key(t.word)) || lexicon.fix(t.word) == null) strange++
            if (strange >= 2 && strange * 3 > words.size) return true
        }
        return false
    }

    /** A capital in the middle of a sentence: a name, a place, a product. Not the cleaner's to respell. */
    private fun name(token: Token) = token.word.first().isUpperCase() && !token.sentenceStart

    /**
     * The decision for one word: what [token] should have been, or null when it stands. [deliberate] is the words
     * the person types repeatedly ([repeated]). The sample-wide test ([foreign]) is [clean]'s.
     */
    fun slipFix(token: Token, lexicon: Lexicon, deliberate: Set<String>): String? {
        val word = token.word
        val lower = key(word)
        if (!judged(lower) || lower in deliberate) return null
        if (name(token)) return null
        if (lexicon.isWord(lower)) return null
        val fix = lexicon.fix(word) ?: return null
        val fixed = key(fix)
        if (fixed == lower || fix.any { it.isWhitespace() }) return null
        // Apostrophes and accents are how someone writes: a fix may move letters, never add or drop those.
        if (marks(lower) != marks(fixed)) return null
        // "heyy", "yess", "musta": a word with more on its end is said that way, not slipped.
        if (lower.length > fixed.length && lower.startsWith(fixed)) return null
        return fix
    }

    /** The characters of [lower] that are not plain letters (apostrophes, accented letters), in order. */
    private fun marks(lower: String) = lower.filter { it !in 'a'..'z' }.toList().sorted()

    /**
     * [text] with its obvious slips repaired, and everything else as it was. [deliberate] is the words the person
     * types repeatedly ([repeated], counted over all their samples as typed). Unchanged when the dictionaries are
     * not loaded or do not cover the sample's language. Cleaning a cleaned text changes nothing.
     */
    fun clean(text: String, lexicon: Lexicon, deliberate: Set<String> = emptySet()): String {
        if (!lexicon.ready) return text
        val tokens = tokens(text)
        if (tokens.isEmpty() || foreign(tokens, lexicon)) return text
        var out: StringBuilder? = null
        for (t in tokens.asReversed()) {
            val fix = slipFix(t, lexicon, deliberate) ?: continue
            out = (out ?: StringBuilder(text)).replace(t.start, t.end, fix)
        }
        return out?.toString() ?: text
    }
}
