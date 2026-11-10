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
 * - an emoji (or any picture character) starts a sentence too: what follows "great 😀" is predicted, and learned,
 *   as the start of one (Matt, 2026-11-10: "make the next word prediction more robust");
 * - a digit or any other symbol ends the context: nothing is predicted from across one;
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
     * [named] says the word before was typed with a capital in the middle of a sentence: a name, as likely as not.
     */
    class Place(val prev2: String?, val prev: String, val typing: String, val recent: List<String>, val named: Boolean = false)

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

    /**
     * The character at [i] is a picture: an emoji or another symbol drawn as one (Unicode's "other symbol", a skin
     * tone), or what holds an emoji together (a joiner, a variation selector, a keycap's mark).
     */
    private fun picture(text: String, i: Int): Boolean {
        val c = text[i]
        if (c == '\u200D' || Words.isEmojiMark(c)) return true
        val point = when {
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> Character.toCodePoint(c, text[i + 1])
            c.isLowSurrogate() && i > 0 && text[i - 1].isHighSurrogate() -> Character.toCodePoint(text[i - 1], c)
            else -> c.code
        }
        val type = Character.getType(point)
        return type == Character.OTHER_SYMBOL.toInt() || (type == Character.MODIFIER_SYMBOL.toInt() && point in 0x1F3FB..0x1F3FF)
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
                c in SENTENCE_ENDS || c in ends || picture(text, i) -> { prev2 = null; prev = START }
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
        // The word before, where it was typed with a capital it did not owe to starting a sentence.
        val before = words.getOrNull(upTo - 1)
        val named = before != null && before.ok && before.prev != START && before.raw.lowercase(locale) == prev && before.raw[0].isUpperCase()
        return Place(at.prev2, prev, if (typing) at.raw else "", recent, named)
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

    /**
     * Every word of [text] as it would be counted had it been typed here: for a text read whole (one of
     * Mockingbird's samples), which starts where a sentence does. Words are runs of letters, their marks and
     * apostrophes; the code-shaped and the unlearnable are passed over as they are when typed.
     */
    fun all(text: String, locale: Locale = Locale.ROOT, isWordChar: (Char) -> Boolean = { letterOrMark(it) || it == '\'' || it == '’' }): List<Learned> {
        val out = ArrayList<Learned>()
        for (w in walk(text, isWordChar, false, locale, "")) {
            val prev = w.prev
            if (w.raw.isEmpty() || !w.ok || prev == null) continue
            out += Learned(w.prev2, prev, w.raw.lowercase(locale), shown(w.raw, prev == START), w.end)
        }
        return out
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
 *
 * A context may be two words ("how are", "^ thank" for thank opening a sentence). Such a row opens with `=n`, how
 * often the two words were seen together where the counts came from ([seen]), so a row from a handful of sentences
 * is trusted less than one from thousands. Keyboards from before two-word rows skip both the entry and the row.
 */
class NgramTable private constructor(
    /** The language's code, the file's stem. */
    val code: String,
    /** Every word of the table in lower case (contexts and followers), to its number. */
    private val ids: HashMap<String, Int>,
    /** A one-word context's row, by its word's number; -1 where it has none. */
    private val oneRow: IntArray,
    /** Two-word contexts, sorted, each its two words' numbers packed ([pack]); and each one's row. */
    private val twoKeys: LongArray,
    private val twoRow: IntArray,
    private val start: IntArray,
    private val counts: IntArray,
    private val next: IntArray,
    private val share: ShortArray,
    private val words: Array<String>,
    private val lowers: Array<String>,
) {
    /** How many word pairs it holds. */
    val pairs: Int get() = next.size

    /** How many contexts have followers. */
    val contexts: Int get() = start.size - 1

    /** Whether [word] (lower case) is one of this table's words: how the language being written is told. */
    fun has(word: String): Boolean = ids.containsKey(word)

    private fun one(key: String): Int {
        val id = ids[key] ?: return -1
        return oneRow[id]
    }

    /**
     * The row of [key]: one word, or two with a space between ("how are"). A word not there whole is looked for by
     * the part after its last apostrophe (j'ai finds ai).
     */
    private fun row(key: String): Int {
        val space = key.indexOf(' ')
        if (space > 0) {
            val a = ids[key.substring(0, space)] ?: return -1
            val b = ids[key.substring(space + 1)] ?: return -1
            val at = twoKeys.binarySearch(pack(a, b))
            return if (at >= 0) twoRow[at] else -1
        }
        val r = one(key)
        if (r >= 0) return r
        val cut = key.lastIndexOf('\'')
        return if (cut >= 0 && cut < key.length - 1) one(key.substring(cut + 1)) else -1
    }

    /** How often a two-word context's words were seen together where the counts came from; 0 for one word or none. */
    fun seen(key: String): Int {
        if (key.indexOf(' ') < 0) return 0
        val r = row(key)
        return if (r < 0) 0 else counts[r]
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
    val approxBytes: Long get() {
        val strings = HashSet<String>()
        var bytes = next.size * 4L + share.size * 2L + start.size * 4L + counts.size * 4L + oneRow.size * 4L + twoKeys.size * 8L + twoRow.size * 4L +
            words.size * 8L + lowers.size * 8L + ids.size * 40L
        fun str(s: String) { if (strings.add(s)) bytes += 40L + s.length * 2 }
        words.forEach(::str); lowers.forEach(::str); ids.keys.forEach(::str)
        return bytes
    }

    companion object {
        /** The newest file version this keyboard reads. */
        const val VERSION = 1

        /** A two-word context packed as one number: 20 bits for each word's number, and 24 for its row when sorting. */
        private const val ID_BITS = 20
        private const val ROW_BITS = 24
        private const val MAX_IDS = 1 shl ID_BITS
        private const val MAX_ROWS = 1 shl ROW_BITS
        private const val ROW_MASK = (1L shl ROW_BITS) - 1
        private fun pack(a: Int, b: Int): Long = (a.toLong() shl ID_BITS) or b.toLong()

        /**
         * Reads a table's [lines]; null where they are not one this keyboard reads (no header, or a newer version).
         * [locale] is whose lower case the language has (Turkish I).
         */
        fun parse(lines: Sequence<String>, locale: Locale = Locale.ROOT): NgramTable? {
            val ids = HashMap<String, Int>()
            fun id(word: String): Int = ids.getOrPut(word) { ids.size }
            val oneKeys = ArrayList<Int>()
            val oneRows = ArrayList<Int>()
            // Each two-word context as one number: its first word's number, its second's, and its row (see [pack]).
            var twoList = LongArray(1 shl 12)
            var twos = 0
            val seenKeys = HashSet<String>()
            val starts = ArrayList<Int>()
            val seen = ArrayList<Int>()
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
                val space = key.indexOf(' ')
                if (space == 0 || space == key.length - 1 || (space > 0 && key.indexOf(' ', space + 1) >= 0)) continue
                if (!seenKeys.add(key)) continue
                val from = n
                var at = tab + 1
                var count = 0
                // A two-word row's "=n": how often its words were seen together.
                if (at < line.length && line[at] == '=') {
                    var end = line.indexOf(' ', at)
                    if (end < 0) end = line.length
                    count = line.substring(at + 1, end).toIntOrNull()?.coerceAtLeast(0) ?: 0
                    at = end + 1
                }
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
                if (n == from) continue
                val row = starts.size
                starts += from; seen += count
                if (space > 0) {
                    val a = id(key.substring(0, space))
                    val b = id(key.substring(space + 1))
                    if (a >= MAX_IDS || b >= MAX_IDS || row >= MAX_ROWS) continue
                    if (twos == twoList.size) twoList = twoList.copyOf(twos * 2)
                    twoList[twos++] = (pack(a, b) shl ROW_BITS) or row.toLong()
                } else {
                    oneKeys += id(key); oneRows += row
                }
            }
            if (code == null) return null
            starts += n
            val shown = words.toTypedArray()
            // One string for each spelling: a follower's lower case is the context's own where both are there.
            val lowers = Array(shown.size) { i -> shown[i].lowercase(locale).let { if (it == shown[i]) shown[i] else it } }
            for (l in lowers) id(l)
            val canonical = HashMap<String, String>(ids.size * 2)
            for (k in ids.keys) canonical[k] = k
            for (i in lowers.indices) lowers[i] = canonical[lowers[i]] ?: lowers[i]
            val oneRow = IntArray(ids.size) { -1 }
            for (k in oneKeys.indices) oneRow[oneKeys[k]] = oneRows[k]
            val sorted = twoList.copyOf(twos).also { it.sort() }
            val twoKeys = LongArray(twos) { sorted[it] ushr ROW_BITS }
            val twoRow = IntArray(twos) { (sorted[it] and ROW_MASK).toInt() }
            return NgramTable(code, ids, oneRow, twoKeys, twoRow, starts.toIntArray(), seen.toIntArray(), next.copyOf(n), share.copyOf(n), shown, lowers)
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

    /** The words that followed [context] as they are held, not a copy: only for pairs nothing changes any more ([of]). */
    fun row(context: String): List<Entry>? = rows[context]

    /** How often [word] was typed at all, faded. */
    fun unigram(word: String): Float = unigrams[word] ?: 0f

    /** How [word] (lower case) is shown: with the capitals it is typed with, else as it is. */
    fun form(word: String): String = forms[word] ?: word

    /** The words typed at least twice ([least] times) that begin with [prefix] (and are not it), with their counts. */
    fun starting(prefix: String, least: Float = 2f): List<Pair<String, Float>> {
        val out = ArrayList<Pair<String, Float>>()
        for ((w, n) in unigrams) if (n >= least && w.length > prefix.length && w.startsWith(prefix)) out += w to n
        return out
    }

    /**
     * The person's own words: typed at least [least] times as counted now (counts fade), two letters or more, and
     * not [listed] in any word list. Names, places, the terms of their work: autocorrect leaves them alone, mends
     * slips toward them, and swipe typing reads them.
     */
    fun own(least: Float, listed: (String) -> Boolean): Set<String> {
        val out = HashSet<String>()
        for ((w, n) in unigrams) if (n >= least && w.length >= 2 && w.all { it.isLetter() || it == '\'' } && !listed(w)) out += w
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
        /**
         * The word pairs of [texts], each read whole as a message that was written and sent: what Mockingbird's
         * samples say comes after what (Matt, 2026-11-10: "the next word prediction should be influenced by
         * mockingbird"). Made once and never changed after, so any thread may read it.
         */
        fun of(texts: Iterable<String>, locale: Locale = Locale.ROOT): PersonalPairs {
            val pairs = PersonalPairs()
            for (text in texts) for (l in NextWordText.all(text, locale)) pairs.learn(l)
            return pairs
        }

        /**
         * [mine] with the followers two other tables hold for the same context added in, [near]'s counting [nearBy]
         * each and [far]'s [farBy]: what was typed here, and what was sent in apps of this kind and of the other.
         */
        fun blended(mine: List<Entry>, near: List<Entry>?, nearBy: Float, far: List<Entry>?, farBy: Float): List<Entry> {
            if (near.isNullOrEmpty() && far.isNullOrEmpty()) return mine
            val out = LinkedHashMap<String, Entry>(mine.size + 8)
            for (e in mine) out[e.word] = Entry(e.word, e.count)
            near?.forEach { e -> out.getOrPut(e.word) { Entry(e.word, 0f) }.count += nearBy * e.count }
            far?.forEach { e -> out.getOrPut(e.word) { Entry(e.word, 0f) }.count += farBy * e.count }
            return ArrayList(out.values)
        }

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
 * tables' share (the likeliest language's, see [weights]). A table's B goes by the two words before where it has a
 * row for them: B = (n·B₂ + K·B₁) / (n + K), with B₂ the two-word row's share, n how often those two words were
 * seen together ([NgramTable.seen]) and K [PRIOR_TABLE_TWO]; so "thank" opening a sentence is followed by what
 * follows it there, and a two-word row seen a handful of times only leans on the one-word share. Then:
 * - after one word: S2 = (u + 20·B) / (U + 20), so with nothing learned it is the starter share, and after a few
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
        val key2 = place.prev2?.takeIf { place.prev != NextWordText.START }?.let { "$it ${place.prev}" }
        // One table's shares here, before they are weighed against the other tables': reused table to table.
        val mine = HashMap<String, Float>()
        val shownOf = HashMap<String, String>()
        for (t in tables.indices) {
            val g = weights[t]
            val table = tables[t]
            val n2 = if (key2 == null) 0 else table.seen(key2)
            mine.clear()
            if (n2 > 0) {
                val two = n2 / (n2 + PRIOR_TABLE_TWO)
                table.followers(place.prev) { shown, lower, share -> mine[lower] = (1f - two) * share; shownOf.putIfAbsent(lower, shown) }
                table.followers(key2!!) { shown, lower, share -> mine[lower] = (mine[lower] ?: 0f) + two * share; shownOf.putIfAbsent(lower, shown) }
            } else {
                table.followers(place.prev) { shown, lower, share -> mine[lower] = share; shownOf.putIfAbsent(lower, shown) }
            }
            for ((lower, share) in mine) {
                val c = out.getOrPut(lower) { Cand(lower, shownOf[lower] ?: lower) }
                if (g * share > c.bundled) { c.bundled = g * share; c.shown = shownOf[lower] ?: c.shown }
            }
            shownOf.clear()
        }
        // A name nobody has a row for (Kira, Mennad) is followed by what follows a name: the row of the tables' own
        // commonest one stands in for it, at half its weight.
        if (out.isEmpty() && place.named && BACKOFF) {
            for (t in tables.indices) {
                val table = tables[t]
                val proxy = NAME_PROXIES.firstOrNull { !table.range(it).isEmpty() } ?: continue
                table.followers(proxy) { shown, lower, share ->
                    val c = out.getOrPut(lower) { Cand(lower, shown) }
                    if (weights[t] * share * NAME_WEIGHT > c.bundled) { c.bundled = weights[t] * share * NAME_WEIGHT; c.shown = shown }
                }
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
     * The [n] likeliest next words as shown, likeliest first; never the word just typed again. Fewer than [n] known
     * are filled up with the person's most-typed words, then the language's commonest; so are all of them where
     * nothing is known of this context (a word no table has, a name nobody has typed before), so the row has words
     * after every word. Empty only where there are none to fill with (a language with no word list).
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
        if (out.size >= n || (out.isEmpty() && !BACKOFF)) return out
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
     * How much the context lifts [lower], a word of frequency [zipf], in Zipf units: log10(1 + [LIFT_WEIGHT] · share /
     * the word's own share of all words). Exactly 0 where the context says nothing of the word, so a word is never
     * ranked down by it. Its rank, zipf plus this, is 9 + log10(own share + weight · share): the context's guess and
     * the word's frequency added together, the context counting forty times over.
     */
    fun boost(lower: String, zipf: Float): Float {
        val s = share(lower)
        if (s <= 0f) return 0f
        return log10(1f + LIFT_WEIGHT * s / 10f.pow(zipf - 9f))
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
        /**
         * How many words of the starter tables' say one-word contexts begin with, and two-word contexts of the
         * one-word blend. [PRIOR_ONE] was 8 until 0.11.0: with that, a person's thin counts outranked the table
         * wherever they had any, and prediction got worse the more of a writer's own sentences it was given
         * (tool/typing_bench.sh voice: the same six writers, 31.8% in the three at 8, 33.0% at 20, 33.8% at 40).
         * 20 keeps a habit quick to show: it unseats a pair the table gives half its row to from the eleventh use.
         * The benchmark sets them to try others; the keyboard never changes them.
         */
        @JvmField var PRIOR_ONE = 20f
        @JvmField var PRIOR_TWO = 3f
        /**
         * Tuned on held-out sentences with tool/next_word_eval.sh (2026-11-10), which sets them to try others; the
         * keyboard never changes them. [PRIOR_TABLE_TWO]: how many sightings of a two-word context its starter row
         * needs to count as much as the one-word row. [LIFT_WEIGHT] and [LIFT_CAP]: see [boost]; the cap is what a
         * completion's rank may gain from context (a correction's gain has its own, smaller cap in Autocorrect).
         */
        @JvmField var PRIOR_TABLE_TWO = 15f
        /** Words after a word nothing is known of, and a name's stand-in ([blend], [top]): off only for the benchmark's comparisons. */
        @JvmField var BACKOFF = true
        /** The names whose rows stand in for a name no table has, in the scripts the tables are in; and how much such a row counts. */
        val NAME_PROXIES = listOf("tom", "том", "توم", "τομ")
        const val NAME_WEIGHT = 0.5f
        @JvmField var LIFT_WEIGHT = 40f
        @JvmField var LIFT_CAP = 6f

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
