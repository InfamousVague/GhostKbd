package com.infamousvague.devkeyboard

import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

/**
 * Next-word prediction's reading of text: where the cursor stands among the words before it, and which word was just
 * finished. Plain Kotlin with no Android in it, so tool/next_word_check.sh runs it on a desk.
 *
 * The rule is one, shared with the starter tables' generator (tool/next_words.py, which counted Tatoeba's sentences
 * by it); tool/next_words_fixtures.tsv is run through both so they cannot drift:
 * - a word is a run of letters, the marks that ride on them and the layout's joiners (an apostrophe, a half-space);
 * - a sentence starts after . ! ? … (and Urdu's ۔, Arabic's ؟, a new line, Greek's ;), and at the start of the text;
 * - commas, semicolons, colons, quotes, brackets and dashes are looked through, as spaces are;
 * - a digit, an emoji or any other symbol ends the context: nothing is predicted from across one;
 * - a word straight after a digit or one of @ # / . _ - \ : $ ` is part of something code-shaped (a path, a handle,
 *   an address) and is neither learned nor predicted from, which is the rule autocorrect has for leaving words alone;
 * - so are letters with a digit straight after them (the mp of mp3, the covid of covid19);
 * - a word with a capital after its first letter (camelCase, iOS) is not learned, unless it is all capitals.
 */
object NextWordText {
    /** The context "a sentence starts here". */
    const val START = "^"

    /** What starts a new sentence on every layout. */
    private const val SENTENCE_ENDS = ".!?…۔؟\n"

    /** Looked through as a space is: a word after one of these still follows the word before it. */
    private const val THROUGH = ",;:،؛\"'“”„‘’‚«»‹›()[]{}¿¡—–"

    /** A word straight after one of these (or a digit) is code-shaped: DevKeyboardService.currentWord's rule. */
    private const val CODE = "@#/._-\\:\$`"

    /** The longest word that is learned. */
    const val MAX_WORD = 24

    /**
     * Where the cursor stands: the word before it ([prev], lower case, or [START]), the one before that ([prev2], or
     * null where there is none to go by), the letters of the word being typed ([typing], empty after a space), and
     * the last few words in lower case ([recent], newest last), which say what language is being written.
     */
    class Place(val prev2: String?, val prev: String, val typing: String, val recent: List<String>)

    /**
     * A finished word as it is counted: [word] in lower case after [prev] (and after [prev2] then [prev]), and how it
     * was typed where that says something ([shown]: "Monday", "NASA"; empty at a sentence start, where the capital is
     * the sentence's). [end] is where it ends in the text it was read from (-1 for the word still being typed).
     */
    class Learned(val prev2: String?, val prev: String, val word: String, val shown: String, val end: Int = -1)

    /** [c] is a letter or a mark riding on one. */
    private fun letterOrMark(c: Char): Boolean = c.isLetter() || when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    }

    /** A run of word characters without the joiners and marks at its edges: 'hello' is hello. */
    private fun trim(text: String, from: Int, to: Int): String {
        var a = from
        var b = to
        while (a < b && !text[a].isLetter()) a++
        while (b > a && !letterOrMark(text[b - 1])) b--
        return text.substring(a, b)
    }

    /** A word that may be learned or predicted from: not too long, no capital inside it unless it is all capitals. */
    fun learnable(raw: String): Boolean {
        if (raw.isEmpty() || raw.length > MAX_WORD) return false
        for (i in 1 until raw.length) if (raw[i].isUpperCase()) return raw.all { !it.isLetter() || it.isUpperCase() }
        return true
    }

    /**
     * How a typed word is kept for showing, or "" where its casing says nothing: all capitals only for words of up to
     * five characters (longer is shouting), and a capital first letter at a sentence start is the sentence's.
     */
    fun shown(raw: String, atStart: Boolean): String {
        var letters = 0
        var upper = 0
        for (c in raw) if (c.isLetter()) { letters++; if (c.isUpperCase()) upper++ }
        if (letters >= 2 && upper == letters) return if (raw.length <= 5) raw else ""
        if (atStart && raw[0].isUpperCase()) return ""
        return raw
    }

    /** One word of a walk: as typed, where it ends, whether it may be learned, and the context it stood in. */
    private class Word(val prev2: String?, val prev: String?, val raw: String, val end: Int, val ok: Boolean)

    /**
     * Every word of [text] in order, each with its context, and the context at the text's end (as a last "word" with
     * an empty [Word.raw]). [full] says the text is a window that was full: its start is then no sentence start, and
     * its first word may be cut short, so it is not gone by. [ends] is the layout's own sentence-ending punctuation.
     */
    private fun walk(text: String, isWordChar: (Char) -> Boolean, full: Boolean, locale: Locale, ends: String): ArrayList<Word> {
        val out = ArrayList<Word>()
        var prev2: String? = null
        var prev: String? = if (full) null else START
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (isWordChar(c)) {
                val start = i
                while (i < n && isWordChar(text[i])) i++
                val raw = trim(text, start, i)
                if (raw.isEmpty()) continue
                val before = if (start > 0) text[start - 1] else ' '
                // Code-shaped: straight after a digit or one of CODE's characters, or with a digit straight after it.
                val code = (start > 0 && (before.isDigit() || before in CODE)) || (i < n && text[i].isDigit())
                val ok = !code && !(full && start == 0) && learnable(raw)
                out += Word(prev2, prev, raw, i, ok)
                if (ok) { prev2 = prev; prev = raw.lowercase(locale) } else { prev2 = null; prev = null }
                continue
            }
            when {
                c in SENTENCE_ENDS || c in ends -> { prev2 = null; prev = START }
                c.isWhitespace() || c in THROUGH -> Unit
                else -> { prev2 = null; prev = null }
            }
            i++
        }
        out += Word(prev2, prev, "", n, false)
        return out
    }

    /**
     * Where the cursor stands after [before], the text in front of it, or null where nothing may be predicted there:
     * no context (after a digit, a symbol, something code-shaped), or the word being typed is not one to go by.
     */
    fun place(before: String, isWordChar: (Char) -> Boolean, full: Boolean, locale: Locale = Locale.ROOT, ends: String = ""): Place? {
        val words = walk(before, isWordChar, full, locale, ends)
        val end = words[words.size - 1]
        val last = if (words.size >= 2) words[words.size - 2] else null
        val typing = last != null && last.end == before.length
        val at = if (last != null && typing) last else end
        if (typing && !at.ok) return null
        val prev = at.prev ?: return null
        val recent = ArrayList<String>(4)
        val upTo = words.size - (if (typing) 2 else 1)
        for (k in maxOf(0, upTo - 4) until upTo) if (words[k].ok) recent += words[k].raw.lowercase(locale)
        return Place(at.prev2, prev, if (typing) at.raw else "", recent)
    }

    /**
     * The last word of [before] that something follows (a space, a full stop), as it is to be counted; null where
     * there is none, or it is not one to learn (code-shaped, no context to count it under).
     */
    fun lastFinished(before: String, isWordChar: (Char) -> Boolean, full: Boolean, locale: Locale = Locale.ROOT, ends: String = ""): Learned? {
        val words = walk(before, isWordChar, full, locale, ends)
        for (k in words.size - 2 downTo 0) {
            val w = words[k]
            if (w.end == before.length) continue
            val prev = w.prev
            if (!w.ok || prev == null) return null
            return Learned(w.prev2, prev, w.raw.lowercase(locale), shown(w.raw, prev == START), w.end)
        }
        return null
    }

    /** The word still being typed at [place], as it is counted if the message is sent as it stands; null with none. */
    fun trailing(place: Place, locale: Locale = Locale.ROOT): Learned? {
        if (place.typing.isEmpty() || !learnable(place.typing)) return null
        return Learned(place.prev2, place.prev, place.typing.lowercase(locale), shown(place.typing, place.prev == START))
    }
}

/**
 * Next-word prediction's learning: which of the words that pass under the cursor are counted, and when. The keyboard
 * tells it what the keys did ([typed], [ended], [deleted] and the rest: a line each, nothing of the model runs on a
 * key press) and shows it the text before the cursor a moment after each edit ([look]); it hands each word to count
 * to [learn]. Plain Kotlin with no Android in it, so tool/next_word_check.sh types at it on a desk.
 *
 * - A word that has just ended is not counted at once. It is held until the next word ends, and counted then only if
 *   it still stands as it was typed (a typo fixed by hand, an autocorrection undone, are gone by then): slips are
 *   not learned.
 * - A word is taken up only when letters were entered by hand since the last one: keys, a swipe, a tap in the row.
 *   A swiped word, or one tapped where a space already followed, is still "being typed" at the cursor; it is taken
 *   up when something ends it, never before, and no word is taken up twice.
 * - Nothing behind the fence is gone by, as a word to learn or as the word before one: what was typed while learning
 *   was off (incognito, the switch, a word list still loading) and what the keys did not type (pasted, dictated,
 *   written by the AI). The fence is the end of that text; it is let go at the next sentence start, or once it is
 *   a hundred characters behind the cursor.
 *
 * [window] is how much text [look] is shown at most: a text that long is a window whose start may be cut.
 */
class NextWordLearner(private val window: Int, private val learn: (NextWordText.Learned) -> Unit) {
    /** What a word is made of on the layout being typed on, whose lower case it has, and its own sentence ends. */
    var isWordChar: (Char) -> Boolean = { it.isLetter() }
    var locale: Locale = Locale.ROOT
    var ends: String = ""

    /**
     * The word finished last, held until the next one ends. [heldTail] is the text that ended with it when it was
     * taken up: found again with a word break after it, the word was not edited away. [heldEdited] says a backspace
     * since may have reached it; the next look settles whether it did, so leaving the field straight after a
     * backspace never counts a word half deleted.
     */
    private var held: NextWordText.Learned? = null
    private var heldTail = ""
    private var heldEdited = false

    /** Where the cursor stood at the last look: a message's last word is counted from it when the message is sent. */
    private var seen: NextWordText.Place? = null

    /** A word has ended since the last look; and how many letters entered by hand have not been taken back or taken up. */
    private var wordEnded = false
    private var typedLetters = 0

    /** The last key was backspace: a field that then empties was cleared, not sent, and its last word is not counted. */
    private var deletedLast = false

    /** The end of the text that must not be gone by, and whether the next look is to set it. */
    private var fence: String? = null
    private var fenceDue = false

    /** Something is waiting to be counted: a held word, or letters typed since. */
    val pending: Boolean get() = held != null || typedLetters > 0

    /** A key typed word characters, [letters] of them letters (none: a joiner alone); a swipe, its word's letters. */
    fun typed(letters: Int) { typedLetters += letters; deletedLast = false }

    /** A whole word of [letters] letters was set down by hand in place of what was at the cursor: a tap in the row, an alternative chosen, an autocorrection undone. */
    fun word(letters: Int) { typedLetters = letters; deletedLast = false }

    /** Something ended the word before the cursor: a space, punctuation, the space a swipe or a tap puts in. */
    fun ended() { wordEnded = true }

    /** Backspace. [whole]: it took all that was entered by hand (a swiped word, a word at a time). */
    fun deleted(whole: Boolean = false) {
        heldEdited = true
        deletedLast = true
        if (whole) typedLetters = 0 else if (typedLetters > 0) typedLetters--
    }

    /** The cursor was moved away from the word being typed: what is typed where it lands was not all entered by hand. */
    fun moved() { typedLetters = 0 }

    /** Something reached the field that the keys did not type (a paste, dictation, the AI): it is fenced off. */
    fun notTyped() { typedLetters = 0; fenceDue = true }

    /** The held word is let go uncounted: incognito was switched on. */
    fun drop() { held = null; seen = null }

    /** The held word is let go if it is [word] (lower case): the autocorrection that typed it was undone. */
    fun unhold(word: String) { if (held?.word == word) held = null }

    /** A new field: its text is its own, and nothing typed elsewhere fences it. */
    fun newField() { fence = null; fenceDue = false }

    /**
     * One look at [before], the text before the cursor, a moment after an edit. [place] is where that puts the cursor
     * ([NextWordText.place] of it, or null). With [allowed] false (a password, incognito, the switch off) everything
     * waiting is dropped, and whatever stands there when learning is allowed again is fenced off.
     */
    fun look(before: String?, place: NextWordText.Place?, allowed: Boolean) {
        if (!allowed) { held = null; seen = null; wordEnded = false; typedLetters = 0; heldEdited = false; fence = null; fenceDue = true; return }
        if (before == null) return
        if (fenceDue) {
            // Whatever stands before the cursor now was typed while nothing was learned, or was not typed at all.
            fenceDue = false
            fence = before.takeLast(FENCE_CHARS).takeIf { it.isNotEmpty() }
            wordEnded = false; typedLetters = 0
        }
        var text: String = before
        var offset = 0
        var full = before.length >= window
        var fenced = false
        val wall = fence
        if (wall != null) {
            if (place != null && place.prev == NextWordText.START && place.typing.isEmpty()) {
                // A new sentence: nothing before it is context for what follows, so the fence has done its work.
                fence = null
            } else {
                val at = before.lastIndexOf(wall)
                if (at < 0) {
                    // The fenced text is no longer just behind the cursor (it was edited, or the cursor went
                    // elsewhere): nothing here is gone by until a sentence starts.
                    held = null; seen = null; wordEnded = false; typedLetters = 0
                    return
                }
                // A fence that ends between words keeps its last character, so the word after it is read whole,
                // and still has no word before it. One that ends inside a word leaves the rest of that word unread.
                offset = at + wall.length - (if (isWordChar(wall[wall.length - 1])) 0 else 1)
                text = before.substring(offset)
                full = true
                fenced = true
                // Far behind the cursor, the fence is about to leave the text that is shown here, and no word being
                // finished can reach back to it: it is let go, so a long message with no full stop goes on being learned.
                if (before.length - (at + wall.length) >= FAR_CHARS) fence = null
            }
        }
        seen = if (fenced) NextWordText.place(text, isWordChar, true, locale, ends) else place
        // A backspace since the held word was taken up: it reached the word only if the word no longer stands.
        if (heldEdited && held != null && stands(before, heldTail)) heldEdited = false
        if (wordEnded && typedLetters > 0) {
            val pair = NextWordText.lastFinished(text, isWordChar, full, locale, ends)
            val tail = if (pair == null) "" else before.substring(0, offset + pair.end).takeLast(TAIL_CHARS)
            val old = held
            if (old != null && pair != null && tail == heldTail && old.word == pair.word && old.prev == pair.prev && old.prev2 == pair.prev2) {
                // The held word again (what was typed after it was taken back): it is not taken up twice.
                heldEdited = false
            } else {
                if (old != null && stands(before, heldTail)) learn(old)
                held = pair
                heldTail = tail
                heldEdited = false
            }
            // Letters still at the cursor (a swiped word, the start of the next one typed in the same instant) are
            // a word yet to be taken up.
            val typing = seen?.typing.orEmpty()
            typedLetters = if (typing.isEmpty()) 0 else minOf(typedLetters, typing.length)
        }
        wordEnded = false
    }

    /**
     * Whether [tail], the text that ended with the held word when it was taken up, is still before the cursor with
     * a word break (or the cursor) straight after it: the word was not edited away, whatever backspace did since.
     */
    private fun stands(before: String, tail: String): Boolean {
        if (tail.isEmpty()) return false
        var at = before.lastIndexOf(tail)
        while (at >= 0) {
            val end = at + tail.length
            if (end == before.length || !isWordChar(before[end])) return true
            at = if (at == 0) -1 else before.lastIndexOf(tail, at - 1)
        }
        return false
    }

    /**
     * The field is being left, sent or switched away from: the held word is counted (if no backspace reached it),
     * and nothing is left held. With [finished] (enter was pressed, or the app's own button sent the message) the
     * word still being typed at the last look is counted too, where letters were typed since: it is the message's
     * last word, and the text it fenced is gone with it. Hiding the keyboard or changing field in the middle of a
     * word counts no half-typed word.
     */
    fun flush(finished: Boolean = false) {
        val last = held
        val place = seen
        val typed = typedLetters > 0 && !deletedLast
        val edited = heldEdited
        held = null; heldTail = ""; seen = null; wordEnded = false; typedLetters = 0; heldEdited = false; deletedLast = false
        if (last != null && !edited) learn(last)
        if (finished && typed && place != null) NextWordText.trailing(place, locale)?.let(learn)
    }

    /** The field emptied (the message was sent): the fence went with its text. */
    fun emptied() { fence = null }

    private companion object {
        /** How much of the text behind the cursor marks the fence, and how much of it marks where the held word stood. */
        const val FENCE_CHARS = 48
        const val TAIL_CHARS = 32
        /** How far behind the cursor the fence's end may fall before it is let go: well inside the shortest window. */
        const val FAR_CHARS = 100
    }
}

/**
 * One language's starter table (assets/ngram/<language>.tsv, made by tool/next_words.py from Tatoeba's sentences):
 * for a word, or for the start of a sentence, the words that most often came next and each one's share of what
 * followed. Never changed once read, so any thread may look things up in it.
 *
 * A file is a header (`#ghost-ngram`, the version, the language, where the counts came from), then a line a context:
 * the context in lower case, a tab, and `word:share` entries, likeliest first, the share in ten-thousandths. A word
 * is written as it is usually cased in the middle of a sentence (I, Monday, Haus).
 */
class NgramTable private constructor(
    /** The language's code, the file's stem. */
    val code: String,
    private val rows: HashMap<String, Int>,
    private val start: IntArray,
    private val next: IntArray,
    private val share: ShortArray,
    private val words: Array<String>,
    private val lowers: Array<String>,
    private val known: HashSet<String>,
) {
    /** How many word pairs it holds. */
    val pairs: Int get() = next.size

    /** How many contexts have followers. */
    val contexts: Int get() = rows.size

    /** Whether [word] (lower case) is one of this table's words: how the language being written is told. */
    fun has(word: String): Boolean = rows.containsKey(word) || word in known

    /** The row of [key], or of the part after its last apostrophe where the whole is not there (j'ai finds ai). */
    private fun row(key: String): Int {
        rows[key]?.let { return it }
        val cut = key.lastIndexOf('\'')
        return if (cut >= 0 && cut < key.length - 1) rows[key.substring(cut + 1)] ?: -1 else -1
    }

    /** Each word that follows [key] here, likeliest first: as shown, in lower case, and its share of what followed (0 to 1). */
    inline fun followers(key: String, each: (shown: String, lower: String, share: Float) -> Unit) {
        val range = range(key)
        for (i in range) each(shownAt(i), lowerAt(i), shareAt(i))
    }

    /** The follower entries of [key], for [followers]. */
    fun range(key: String): IntRange {
        val r = row(key)
        return if (r < 0) IntRange.EMPTY else start[r] until start[r + 1]
    }

    fun shownAt(i: Int): String = words[next[i]]
    fun lowerAt(i: Int): String = lowers[next[i]]
    fun shareAt(i: Int): Float = (share[i].toInt() and 0xFFFF) / 10_000f

    /** About how much memory the table takes, in bytes: its arrays, its strings and its hash entries. An estimate. */
    val approxBytes: Long get() =
        next.size * 4L + share.size * 2L + start.size * 4L +
            words.sumOf { 40L + it.length * 2 } + lowers.indices.sumOf { if (lowers[it] === words[it]) 0L else 40L + lowers[it].length * 2 } +
            rows.keys.sumOf { 48L + 40L + it.length * 2 } + known.size * 48L

    companion object {
        /** The newest file version this keyboard reads. */
        const val VERSION = 1

        /**
         * Reads a table's [lines]; null where they are not one this keyboard reads (no header, or a newer version).
         * [locale] is whose lower case the language has (Turkish I).
         */
        fun parse(lines: Sequence<String>, locale: Locale = Locale.ROOT): NgramTable? {
            val rows = HashMap<String, Int>()
            val starts = ArrayList<Int>()
            var next = IntArray(1 shl 14)
            var share = ShortArray(1 shl 14)
            var n = 0
            val index = HashMap<String, Int>()
            val words = ArrayList<String>()
            var code: String? = null
            for (line in lines) {
                if (code == null) {
                    val head = line.split('\t')
                    if (head.size < 3 || head[0] != "#ghost-ngram" || (head[1].toIntOrNull() ?: Int.MAX_VALUE) > VERSION) return null
                    code = head[2]
                    continue
                }
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val key = line.substring(0, tab)
                if (rows.containsKey(key)) continue
                val from = n
                var at = tab + 1
                while (at < line.length) {
                    var end = line.indexOf(' ', at)
                    if (end < 0) end = line.length
                    val colon = line.lastIndexOf(':', end - 1)
                    if (colon > at) {
                        val word = line.substring(at, colon)
                        val s = line.substring(colon + 1, end).toIntOrNull()
                        if (s != null && s > 0) {
                            if (n == next.size) { next = next.copyOf(n * 2); share = share.copyOf(n * 2) }
                            next[n] = index.getOrPut(word) { words += word; words.size - 1 }
                            share[n] = s.coerceAtMost(10_000).toShort()
                            n++
                        }
                    }
                    at = end + 1
                }
                if (n > from) { rows[key] = starts.size; starts += from }
            }
            if (code == null) return null
            starts += n
            val shown = words.toTypedArray()
            val lowers = Array(shown.size) { i -> shown[i].lowercase(locale).let { if (it == shown[i]) shown[i] else it } }
            val known = HashSet<String>(lowers.size * 2)
            for (l in lowers) if (!rows.containsKey(l)) known += l
            return NgramTable(code, rows, starts.toIntArray(), next.copyOf(n), share.copyOf(n), shown, lowers, known)
        }
    }
}

/**
 * What one person types, counted on their phone: how often each word followed the word before it, and the two words
 * before it. Counts fade as new words are typed ([maintain]), so old habits make way for new ones, and the whole is
 * bounded: [MAX_ENTRIES] pairs, [MAX_FOLLOWERS] followers a context.
 *
 * Not safe to share between threads: NextWords holds it behind a lock.
 *
 * Kept as a text file (NextWords.FILE), a header then a line a context:
 * ```
 * #ghost-next-words	1	events=5321	decayed=4000
 * ^	hey:4.00 ok:3.00
 * on	my:6.00 the:2.00
 * on my	way:5.00
 * =	monday=Monday i=I
 * ```
 * A context with a space in it is the two words before. The last line gives the words that are shown with capitals.
 */
class PersonalPairs {
    /** A word that followed a context, and its faded count. */
    class Entry(val word: String, var count: Float)

    /** The file is not one this keyboard reads: damaged, or written by a [newer] keyboard. */
    class Unreadable(val newer: Boolean) : Exception()

    private val rows = HashMap<String, ArrayList<Entry>>()
    /** Each word's count over every one-word context: how often it was typed at all. */
    private val unigrams = HashMap<String, Float>()
    /** The words shown with capitals: lower case to "Monday", "I", "NASA". */
    private val forms = HashMap<String, String>()
    private var top: List<String>? = null
    private var addsSinceTop = 0

    /** How many words have been learned in all, and the count at which the last fading was done. */
    var events = 0
    private var decayedAt = 0

    /** How many pairs are held. */
    var entries = 0
        private set

    /** How many different words have been typed. */
    val words: Int get() = unigrams.size

    /**
     * Counts [word] (lower case) once more after [context] (a word, two words with a space between, or
     * [NextWordText.START]). [shown] is how it was typed where that says something. A context that is full swaps its
     * least-typed follower for the new one.
     */
    fun add(context: String, word: String, shown: String = "", n: Float = 1f) {
        if (word.isEmpty() || word.length > NextWordText.MAX_WORD || n <= 0f) return
        val row = rows.getOrPut(context) { ArrayList(4) }
        val single = ' ' !in context
        var entry: Entry? = null
        for (e in row) if (e.word == word) { entry = e; break }
        if (entry != null) {
            entry.count = (entry.count + n).coerceAtMost(MAX_COUNT)
        } else {
            if (row.size >= MAX_FOLLOWERS) {
                var low = 0
                for (i in 1 until row.size) if (row[i].count < row[low].count) low = i
                val gone = row.removeAt(low)
                entries--
                if (single) unigrams[gone.word]?.let { u -> if (u - gone.count <= 0.01f) unigrams.remove(gone.word) else unigrams[gone.word] = u - gone.count }
            }
            row += Entry(word, n.coerceAtMost(MAX_COUNT))
            entries++
        }
        if (single) unigrams[word] = (unigrams[word] ?: 0f) + n
        if (shown.isNotEmpty()) { if (shown == word) forms.remove(word) else forms[word] = shown }
        if (++addsSinceTop >= 64) top = null
    }

    /** One finished word counted under its contexts: after the word before, and after the two before. */
    fun learn(l: NextWordText.Learned) {
        add(l.prev, l.word, l.shown)
        val prev2 = l.prev2
        if (prev2 != null && l.prev != NextWordText.START) add("$prev2 ${l.prev}", l.word)
        events++
    }

    /** How often [word] followed [context], faded. */
    fun count(context: String, word: String): Float = rows[context]?.firstOrNull { it.word == word }?.count ?: 0f

    /** How often anything followed [context], faded. */
    fun total(context: String): Float = rows[context]?.let { row -> var t = 0f; for (e in row) t += e.count; t } ?: 0f

    /** A copy of the words that followed [context], for a [Scorer] to read outside the lock. */
    fun followers(context: String): List<Entry> = rows[context]?.map { Entry(it.word, it.count) } ?: emptyList()

    /** How often [word] was typed at all, faded. */
    fun unigram(word: String): Float = unigrams[word] ?: 0f

    /** How [word] (lower case) is shown: with the capitals it is typed with, else as it is. */
    fun form(word: String): String = forms[word] ?: word

    /** The words typed at least twice that begin with [prefix] (and are not it), with their counts. */
    fun starting(prefix: String): List<Pair<String, Float>> {
        val out = ArrayList<Pair<String, Float>>()
        for ((w, n) in unigrams) if (n >= 2f && w.length > prefix.length && w.startsWith(prefix)) out += w to n
        return out
    }

    /** The [n] words typed most (three times or more each), most first. Worked out afresh every 64 words learned. */
    fun top(n: Int): List<String> {
        top?.let { return it.take(n) }
        val made = unigrams.entries.filter { it.value >= 3f }.sortedByDescending { it.value }.take(8).map { it.key }
        top = made
        addsSinceTop = 0
        return made.take(n)
    }

    /**
     * Fades and bounds what is held: every [DECAY_EVERY] words learned, each count is multiplied by [DECAY] (so a
     * pair not typed again is half forgotten in about 8,000 words) and a count under [FLOOR] is dropped; and past
     * [MAX_ENTRIES] pairs, the least-typed go until nine tenths are left. Run when the keyboard hides, not on a key.
     */
    fun maintain() {
        val steps = (events - decayedAt) / DECAY_EVERY
        if (steps > 0) {
            val f = DECAY.pow(steps)
            decayedAt += steps * DECAY_EVERY
            val each = rows.values.iterator()
            while (each.hasNext()) {
                val row = each.next()
                val it = row.iterator()
                while (it.hasNext()) { val e = it.next(); e.count *= f; if (e.count < FLOOR) { it.remove(); entries-- } }
                if (row.isEmpty()) each.remove()
            }
        }
        if (entries > MAX_ENTRIES) {
            val counts = FloatArray(entries)
            var k = 0
            for (row in rows.values) for (e in row) counts[k++] = e.count
            counts.sort()
            val keep = MAX_ENTRIES * 9 / 10
            val floor = counts[entries - keep]
            var over = entries - keep
            val each = rows.values.iterator()
            while (each.hasNext() && over > 0) {
                val row = each.next()
                val it = row.iterator()
                while (it.hasNext() && over > 0) { if (it.next().count <= floor) { it.remove(); entries--; over-- } }
                if (row.isEmpty()) each.remove()
            }
        }
        recount()
    }

    /** The per-word counts and shown forms, made again from the pairs. */
    private fun recount() {
        unigrams.clear()
        for ((context, row) in rows) if (' ' !in context) for (e in row) unigrams[e.word] = (unigrams[e.word] ?: 0f) + e.count
        forms.keys.retainAll { unigrams.containsKey(it) }
        top = null
    }

    /** A copy to write out or export while typing goes on. */
    fun copy(): PersonalPairs {
        val c = PersonalPairs()
        for ((context, row) in rows) c.rows[context] = row.mapTo(ArrayList(row.size)) { Entry(it.word, it.count) }
        c.unigrams.putAll(unigrams)
        c.forms.putAll(forms)
        c.events = events
        c.decayedAt = decayedAt
        c.entries = entries
        return c
    }

    /** Every context with its followers, most typed first, for an export. */
    fun each(visit: (context: String, followers: List<Entry>) -> Unit) {
        for (context in rows.keys.sorted()) visit(context, rows[context]!!.sortedByDescending { it.count })
    }

    /** The file's text (see the class comment). */
    fun write(out: Appendable) {
        out.append(HEADER).append('\t').append(VERSION.toString()).append("\tevents=").append(events.toString()).append("\tdecayed=").append(decayedAt.toString()).append('\n')
        each { context, followers ->
            out.append(context).append('\t')
            followers.forEachIndexed { i, e -> if (i > 0) out.append(' '); out.append(e.word).append(':').append(String.format(Locale.ROOT, "%.2f", e.count)) }
            out.append('\n')
        }
        if (forms.isNotEmpty()) {
            out.append(FORMS).append('\t')
            forms.keys.sorted().forEachIndexed { i, w -> if (i > 0) out.append(' '); out.append(w).append('=').append(forms[w]) }
            out.append('\n')
        }
    }

    companion object {
        const val VERSION = 1
        private const val HEADER = "#ghost-next-words"
        private const val FORMS = "="

        /** The most pairs held, and the most followers one context keeps. */
        const val MAX_ENTRIES = 30_000
        const val MAX_FOLLOWERS = 32
        /** Fading: every this many learned words, every count is multiplied by [DECAY]; under [FLOOR] it is dropped. */
        const val DECAY_EVERY = 2_000
        const val DECAY = 0.84f
        const val FLOOR = 0.4f
        /** No count grows past this, however a profile's import piles on. */
        const val MAX_COUNT = 100_000f

        /** Reads a file's [lines]; throws [Unreadable] where they are not a file this keyboard reads. */
        fun read(lines: Sequence<String>): PersonalPairs {
            val p = PersonalPairs()
            var headed = false
            for (line in lines) {
                if (!headed) {
                    val head = line.split('\t')
                    if (head.size < 2 || head[0] != HEADER) throw Unreadable(newer = false)
                    val version = head[1].toIntOrNull() ?: throw Unreadable(newer = false)
                    if (version > VERSION) throw Unreadable(newer = true)
                    for (part in head.drop(2)) {
                        if (part.startsWith("events=")) p.events = part.substring(7).toIntOrNull() ?: 0
                        if (part.startsWith("decayed=")) p.decayedAt = part.substring(8).toIntOrNull() ?: 0
                    }
                    headed = true
                    continue
                }
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val context = line.substring(0, tab)
                for (part in line.substring(tab + 1).split(' ')) {
                    if (context == FORMS) {
                        val eq = part.indexOf('=')
                        if (eq > 0 && eq < part.length - 1) p.forms[part.substring(0, eq)] = part.substring(eq + 1)
                        continue
                    }
                    val colon = part.lastIndexOf(':')
                    val n = if (colon > 0) part.substring(colon + 1).toFloatOrNull() else null
                    if (n == null || n <= 0f || n.isNaN()) continue
                    val word = part.substring(0, colon)
                    if (word.length > NextWordText.MAX_WORD) continue
                    val row = p.rows.getOrPut(context) { ArrayList(4) }
                    if (row.size < MAX_FOLLOWERS && row.none { it.word == word }) { row += Entry(word, n.coerceAtMost(MAX_COUNT)); p.entries++ }
                }
            }
            if (!headed) throw Unreadable(newer = false)
            p.rows.values.removeAll { it.isEmpty() }
            if (p.decayedAt > p.events) p.decayedAt = p.events
            p.recount()
            return p
        }
    }
}

/**
 * The words likely to come next at one place in the text, from the starter tables of the languages being typed and
 * from what the person has typed there before. Made afresh each time the row is drawn; cheap to make, and it works
 * nothing out until it is asked.
 *
 * The blend, with u a faded personal count, U everything the person typed after that context, and B the starter
 * tables' share (the likeliest language's, see [weights]):
 * - after one word: S2 = (u + 8·B) / (U + 8), so with nothing learned it is the starter share, and after a few
 *   dozen words in a context the person's own habits lead;
 * - after two words, where the person has typed that pair before: S = (u + 3·S2) / (U + 3).
 * A personal count is only gone by from its second use (third, for a word no dictionary has): a pair typed once is
 * remembered and not yet shown.
 */
class Scorer(
    private val place: NextWordText.Place,
    private val tables: List<NgramTable>,
    private val weights: FloatArray,
    private val one: List<PersonalPairs.Entry>,
    private val two: List<PersonalPairs.Entry>,
    private val lookup: Lookup,
) {
    /** What a [Scorer] asks of the rest of the keyboard, each answer cheap. */
    interface Lookup {
        /** Whether [lower] is a word some dictionary, the learned words or slang has. */
        fun isWord(lower: String): Boolean
        /** How the person's [lower] is shown: with the capitals they type it with, else as it is. */
        fun form(lower: String): String
        /** How often the person has typed [lower], faded. */
        fun count(lower: String): Float
        /** The person's words that begin with [prefix], with their counts. */
        fun starting(prefix: String): List<Pair<String, Float>>
        /** Words to fill a row that has too few: the person's most typed, then the language's commonest. */
        fun fills(): List<String>
    }

    /** A word that may come next: in lower case, as shown, and how likely (0 to 1). */
    class Next(val lower: String, val shown: String, val share: Float)

    private class Cand(val lower: String, var shown: String, var bundled: Float = 0f, var u1: Float = 0f, var u2: Float = 0f, var share: Float = 0f)

    private val cands: HashMap<String, Cand> by lazy(LazyThreadSafetyMode.NONE) { blend() }

    /** A personal count as it is gone by: nothing until the pair has been typed twice (three times for a non-word). */
    private fun counted(u: Float, lower: String): Float = if (u >= 2.5f || (u >= 1.5f && lookup.isWord(lower))) u else 0f

    private fun blend(): HashMap<String, Cand> {
        val out = HashMap<String, Cand>()
        for (t in tables.indices) {
            val g = weights[t]
            tables[t].followers(place.prev) { shown, lower, share ->
                val c = out.getOrPut(lower) { Cand(lower, shown) }
                if (g * share > c.bundled) { c.bundled = g * share; c.shown = shown }
            }
        }
        var total1 = 0f
        var total2 = 0f
        for (e in one) { total1 += e.count; out.getOrPut(e.word) { Cand(e.word, "") }.u1 = e.count }
        for (e in two) { total2 += e.count; out.getOrPut(e.word) { Cand(e.word, "") }.u2 = e.count }
        for (c in out.values) {
            val u1 = if (c.u1 > 0f) counted(c.u1, c.lower) else 0f
            val u2 = if (c.u2 > 0f) counted(c.u2, c.lower) else 0f
            val s2 = (u1 + PRIOR_ONE * c.bundled) / (total1 + PRIOR_ONE)
            c.share = if (total2 > 0f) (u2 + PRIOR_TWO * s2) / (total2 + PRIOR_TWO) else s2
            // The person's own casing where they have typed the word here; else the table's; else as it is.
            if (u1 > 0f || u2 > 0f || c.shown.isEmpty()) c.shown = lookup.form(c.lower).takeIf { it != c.lower } ?: c.shown.ifEmpty { c.lower }
        }
        return out
    }

    /**
     * The [n] likeliest next words as shown, likeliest first; never the word just typed again. Empty where nothing
     * is known of this context, so the row can show its tools instead. Fewer than [n] known are filled up with the
     * person's most-typed words, then the language's commonest.
     */
    fun top(n: Int): List<String> {
        val best = arrayOfNulls<Cand>(n)
        for (c in cands.values) {
            if (c.share <= 0f || c.lower == place.prev) continue
            var at = n
            while (at > 0 && (best[at - 1] == null || c.share > best[at - 1]!!.share)) at--
            if (at >= n) continue
            for (k in n - 1 downTo at + 1) best[k] = best[k - 1]
            best[at] = c
        }
        val out = ArrayList<String>(n)
        val seen = HashSet<String>()
        for (c in best) if (c != null) { out += c.shown; seen += c.lower }
        if (out.isEmpty() || out.size >= n) return out
        for (w in lookup.fills()) {
            if (out.size >= n) break
            val lower = w.lowercase()
            if (lower != place.prev && seen.add(lower)) out += w
        }
        return out
    }

    /** Works the blend out now rather than at the first question, so the time it takes can be measured. */
    fun ready(): Scorer { cands.size; return this }

    /** How likely [lower] is to come next here, 0 to 1; 0 where nothing says so. */
    fun share(lower: String): Float = cands[lower]?.share ?: 0f

    /**
     * How much the context lifts [lower], a word of frequency [zipf], in Zipf units: log10(1 + 2.33 · share / the
     * word's own share of all words). Exactly 0 where the context says nothing of the word, so a word is never ranked
     * down by it, and about 0.5 where the context only agrees with how common the word is anyway.
     */
    fun boost(lower: String, zipf: Float): Float {
        val s = share(lower)
        if (s <= 0f) return 0f
        return log10(1f + 2.33f * s / 10f.pow(zipf - 9f))
    }

    /**
     * How common the person's own use makes [lower], in Zipf units: 0 until it has been typed twice (three times for
     * a word no dictionary has, [isWord] false), then 3.5 and half a point more each time its count doubles, to 6.5.
     */
    fun personalZipf(lower: String, isWord: Boolean): Float = personalZipf(lookup.count(lower), isWord)

    /** The words that may come next and begin with [prefix], as typed or in their plain spelling ([plain]). */
    fun following(prefix: String, plain: (String) -> String): List<Next> {
        val out = ArrayList<Next>()
        for (c in cands.values) {
            if (c.share <= 0f || c.lower == prefix) continue
            if (c.lower.startsWith(prefix) || plain(c.lower).startsWith(prefix)) out += Next(c.lower, c.shown, c.share)
        }
        return out
    }

    /** The person's own words that begin with [prefix]: lower case, as shown, and how common their use makes each. */
    fun personal(prefix: String): List<Next> = lookup.starting(prefix).mapNotNull { (w, n) ->
        val z = personalZipf(n, lookup.isWord(w))
        if (z <= 0f) null else Next(w, lookup.form(w), z)
    }

    companion object {
        /** How many words of the starter tables' say one-word contexts begin with, and two-word contexts of the one-word blend. */
        const val PRIOR_ONE = 8f
        const val PRIOR_TWO = 3f

        /** See [Scorer.personalZipf]. */
        fun personalZipf(n: Float, isWord: Boolean): Float =
            if (n < (if (isWord) 2f else 3f)) 0f else minOf(6.5f, 3.5f + 0.5f * (kotlin.math.ln(n) / kotlin.math.ln(2f)))

        /**
         * How much each of [tables] is gone by, from the last few words typed ([recent]): the table that knows the
         * most of them counts in full, the others by how many they know. With nothing to tell by, the first language
         * leads and the rest count half.
         */
        fun weights(tables: List<NgramTable>, recent: List<String>): FloatArray {
            val hits = IntArray(tables.size)
            var most = 0
            for (t in tables.indices) {
                for (w in recent) if (tables[t].has(w)) hits[t]++
                if (hits[t] > most) most = hits[t]
            }
            return FloatArray(tables.size) { t -> if (most == 0) (if (t == 0) 1f else 0.5f) else (hits[t] + 1f) / (most + 1f) }
        }
    }
}
