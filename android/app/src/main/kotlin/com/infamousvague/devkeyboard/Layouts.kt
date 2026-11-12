package com.infamousvague.devkeyboard

import java.util.Locale
import kotlin.math.abs

/**
 * What a key does when it is pressed. GLOBE goes to the next layout (long-press: the list of them); MIC starts or
 * ends a dictation (long-press: the full stop it stands in for, and that key's punctuation).
 */
enum class KeyType { CHAR, SHIFT, DELETE, SPACE, ENTER, MODE, MIC, GLOBE, EMOJI }

/** Gboard's three pages (letters, ?123, =\<) and the developer page behind the { } key. */
enum class Page { LETTERS, SYMBOLS, MORE, DEV }

/**
 * One key. [text] is what it types (letters are typed in upper case while shift is on), [label] what it shows,
 * [width] its share of the row in key units, [hint] the small character in its corner, [alts] the long-press
 * choices (the hint, when there is one, comes first), [page] the page a MODE key goes to. A `¦` in [text] or an
 * alternative is where the cursor is left: `(¦)` types a pair and puts the cursor between. [pill] is whether a MODE
 * key is drawn as Gboard's pill (?123) or as a bare label (the { } key, where Gboard's comma was).
 */
data class Key(
    val type: KeyType,
    val text: String = "",
    val label: String = text,
    val width: Float = 1f,
    val hint: String? = null,
    val alts: List<String> = emptyList(),
    val page: Page? = null,
    val pill: Boolean = true,
) {
    /** Drawn in the darker "function" colour, as Gboard draws shift, delete, ?123 and the like. */
    val functional: Boolean get() = type == KeyType.SHIFT || type == KeyType.DELETE || type == KeyType.MODE
}

/** A row of keys, [inset] key units of space before and after it (Gboard's middle letter row sits half a key in). */
class KeyRow(val keys: List<Key>, val inset: Float = 0f) {
    val units: Float get() = keys.sumOf { it.width.toDouble() }.toFloat() + inset * 2
}

/**
 * A letters page: which letters sit where. [rows] are its three letter rows, each cell a key and then its own
 * long-press letters ("е ё"); [shifted] is what shift turns a key into where that is not its capital (Korean's double
 * consonants); [locale] is whose capitals it has (Turkish i becomes İ). [fullStop] is what the full-stop key and a
 * double space type, [wordEnds] the punctuation of its own that ends a word as . , ! ? do, [periodFirst] what leads
 * the full stop's long-press, [bottomExtra] a key of its own right of the space bar (Persian's half-space), and
 * [swipe] whether a finger can spell words across it. [digits] are the script's own digits, 0 to 9, where it has
 * them (Arabic's ٠ to ٩): each leads its Latin digit's long-press. [marks] are the punctuation of its own that takes
 * a Latin mark's place on the ?123 page and in the letters' corners (Arabic's ؟ for ?, the Latin one a long-press
 * away), and [markAlts] what leads a ?123 key's long-press without taking its place (٪ on %).
 */
class LayoutSpec(
    val id: String,
    val label: String,
    val script: Script,
    val rows: List<List<String>>,
    val shifted: Map<String, String> = emptyMap(),
    val locale: Locale = Locale.ROOT,
    val fullStop: String = ".",
    val wordEnds: String = "",
    val periodFirst: List<String> = emptyList(),
    val bottomExtra: Key? = null,
    val swipe: Boolean = true,
    val digits: String = "",
    val marks: Map<String, String> = emptyMap(),
    val markAlts: Map<String, List<String>> = emptyMap(),
) {
    /** A shift key stands on it: the script has capitals, or shift has letters of its own to give. */
    val hasShift: Boolean get() = script.cased || shifted.isNotEmpty()

    /** Each letter key's place: across in key widths (QWERTY's q is 0, a is 0.5) and its row. */
    val positions: Map<Char, Pair<Float, Int>> by lazy { Layouts.positions(this) }

    /** Each letter key's neighbours: the keys a slipping thumb lands on instead. */
    val neighbours: Map<Char, Set<Char>> by lazy { Layouts.neighbours(this) }

    /**
     * The letters autocorrect tries when it mends a word: every letter key, and the letters of the layout's own
     * long-presses that are not just a key's accented form (Russian's ъ, Urdu's ص), in code-point order.
     */
    val alphabet: String by lazy {
        val keys = positions.keys
        val more = rows.flatten().flatMap { it.split(' ').drop(1) }.filter { it.length == 1 && it[0].isLetter() && script.plain(it) == it }.map { it[0] }
        (keys + more).distinct().sorted().joinToString("")
    }
}

object Layouts {
    /**
     * The dictation plugin's mic key (Matt, 2026-11-12: "when dictation is enabled replace the period key next to the
     * enter key"): on the letters page it stands where the full stop was, next to enter, and the space bar keeps its
     * whole width. The full stop is not lost with its key: it leads the mic key's long-press, with the full stop's own
     * punctuation after it, and shows in the key's corner as a letter's hint does; a double space still types one.
     *
     * The other pages have no { } key and come back as they were, full stop and all: numbers are typed there, and
     * need it. So does a letters page with no full stop before enter to take the place of.
     *
     * The letters page is known by its { } key, or by the emoji key that has taken that key's place ([withEmoji]).
     */
    fun withMic(rows: List<KeyRow>): List<KeyRow> {
        val last = rows.last()
        if (last.keys.none { it.type == KeyType.MODE && it.page == Page.DEV || it.type == KeyType.EMOJI }) return rows
        val at = last.keys.indexOfFirst { it.type == KeyType.ENTER } - 1
        val stop = last.keys.getOrNull(at)?.takeIf { it.type == KeyType.CHAR && it.text.isNotEmpty() } ?: return rows
        val mic = Key(KeyType.MIC, hint = stop.text, alts = (listOf(stop.text) + stop.alts).distinct())
        return rows.dropLast(1) + KeyRow(last.keys.toMutableList().also { it[at] = mic }, last.inset)
    }

    /** The least the space bar is left with, in key widths, whatever stands beside it. */
    const val LEAST_BAR = 3f

    /**
     * The emoji plugin's key (Matt, 2026-11-12: "Move the emoji key to where the dev tools toggle is. And make it the
     * same as the dictation where it's just the icon no background. And move the code button up to where the emoji
     * button used to be in the toolbar"): on the letters page it stands in the { } key's place, between ?123 and
     * the space bar, and is drawn as the mic key is, its icon alone. The { } key is then a button in the row above
     * the keys (DevKeyboardService.everyday).
     *
     * Where there is no row to hold that button ([keepCode]: the row is hidden, or nothing is in it), the { } key
     * keeps a key on the board, straight after the emoji key, with a key's width taken from the space bar, so the
     * code keys can still be reached. That is not done where it would leave the bar under [LEAST_BAR] key widths:
     * there the board keeps its { } key as it was, and has no emoji key.
     *
     * Its third time on the board: it was a key between { } and the space bar from 0.12.1 to 0.15.0, and a button in
     * the row in 0.16. Pages with no { } key come back as they were.
     */
    fun withEmoji(rows: List<KeyRow>, keepCode: Boolean): List<KeyRow> {
        val last = rows.last()
        val at = last.keys.indexOfFirst { it.type == KeyType.MODE && it.page == Page.DEV }
        if (at < 0) return rows
        val emoji = Key(KeyType.EMOJI, pill = false)
        if (!keepCode) return rows.dropLast(1) + KeyRow(last.keys.toMutableList().also { it[at] = emoji }, last.inset)
        val space = last.keys.firstOrNull { it.type == KeyType.SPACE } ?: return rows
        if (space.width - 1f < LEAST_BAR) return rows
        val keys = ArrayList<Key>(last.keys.size + 1)
        last.keys.forEach { key ->
            if (key === last.keys[at]) keys += emoji
            keys += if (key.type == KeyType.SPACE) key.copy(width = key.width - 1f) else key
        }
        return rows.dropLast(1) + KeyRow(keys, last.inset)
    }

    private val accents = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "c" to listOf("ç", "ć", "č"),
        "e" to listOf("è", "é", "ê", "ë", "ē", "ę", "ė"),
        "i" to listOf("î", "ï", "í", "ī", "į", "ì"),
        "l" to listOf("ł"),
        "n" to listOf("ñ", "ń"),
        "o" to listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"),
        "s" to listOf("ß", "ś", "š"),
        "u" to listOf("û", "ü", "ù", "ú", "ū"),
        "y" to listOf("ÿ"),
        "z" to listOf("ž", "ź", "ż"),
    )

    /** Long-press choices on the symbol pages, with the pairs a programmer reaches for most. */
    private val symbolAlts = mapOf(
        "1" to listOf("¹", "½", "⅓", "¼", "⅛"),
        "2" to listOf("²", "⅔"),
        "3" to listOf("³", "¾", "⅜"),
        "4" to listOf("⁴"),
        "0" to listOf("ⁿ", "∅"),
        "(" to listOf("<", "[", "{"),
        ")" to listOf(">", "]", "}"),
        "-" to listOf("_", "–", "—", "·"),
        "+" to listOf("±"),
        "/" to listOf("\\"),
        "'" to listOf("`", "‘", "’", "‚", "‹", "›"),
        "\"" to listOf("“", "”", "„", "«", "»"),
        "!" to listOf("¡"),
        "?" to listOf("¿", "‽"),
        "$" to listOf("¢", "£", "€", "¥", "₹", "₱"),
        "*" to listOf("†", "‡", "★"),
        "=" to listOf("≠", "≈", "∞"),
        "%" to listOf("‰", "℅"),
        "#" to listOf("№"),
        "&" to listOf("§"),
        "<" to listOf("≤", "«", "‹"),
        ">" to listOf("≥", "»", "›"),
    )

    /** The period's long-press punctuation panel, as Gboard has it. */
    private val periodAlts = listOf("?", "!", ",", "'", "\"", ":", ";", "-", "/", "@", "#", "&", "(", ")", "%", "+")

    private val digits = "1234567890".map { it.toString() }

    private fun letter(c: String, hint: String?) =
        Key(KeyType.CHAR, c, hint = hint, alts = listOfNotNull(hint) + accents[c].orEmpty())

    private fun sym(c: String) = Key(KeyType.CHAR, c, alts = symbolAlts[c].orEmpty())

    /**
     * The ?123 pages' key for [c] on [spec]: where the layout has a mark of its own for it (Arabic's ؟ for ?), that
     * is what it types, with the Latin one first on its long-press; and whatever the layout puts first there (٪ on %).
     */
    private fun sym(c: String, spec: LayoutSpec): Key {
        val own = spec.marks[c]
        return Key(KeyType.CHAR, own ?: c, alts = spec.markAlts[c].orEmpty() + listOfNotNull(c.takeIf { own != null }) + symbolAlts[c].orEmpty())
    }

    /** [spec]'s own digit for the Latin digit [d] (٣ for 3 on the Arabic board), or null where it writes Latin digits. */
    private fun ownDigit(spec: LayoutSpec, d: String): String? = spec.digits.getOrNull(d[0] - '0')?.toString()

    /** A digit's key on [spec]: the Latin digit, the script's own leading its long-press. */
    private fun digit(d: String, spec: LayoutSpec): Key = sym(d).let { key -> ownDigit(spec, d)?.let { key.copy(alts = listOf(it) + key.alts) } ?: key }

    private val shift = Key(KeyType.SHIFT, width = 1.5f)
    private val delete = Key(KeyType.DELETE, width = 1.5f)

    private val comma = Key(KeyType.CHAR, ",")

    /** Where Gboard has its comma: the developer page's key (long-press: settings). */
    private val devKey = Key(KeyType.MODE, label = "{ }", page = Page.DEV, pill = false)

    /** In { }'s place on the developer page: a comma, which types one and goes back to the letters. */
    private val devComma = Key(KeyType.CHAR, ",", page = Page.LETTERS, pill = false)

    /** The symbol pages' last row. [periodFirst] leads the full stop's long-press (a layout's own marks). */
    private fun bottomRow(modeLabel: String, modePage: Page, second: Key = comma, periodFirst: List<String> = emptyList()) = KeyRow(
        listOf(
            // Gboard's, measured: ?123 and enter a key and a half, space five keys.
            Key(KeyType.MODE, label = modeLabel, width = 1.5f, page = modePage),
            second,
            Key(KeyType.SPACE, " ", width = 5f),
            Key(KeyType.CHAR, ".", alts = (periodFirst + periodAlts).distinct()),
            Key(KeyType.ENTER, width = 1.5f),
        ),
    )

    /** QWERTY with the accents every Latin board has: the page as it was before there were layouts. */
    fun letters(numberRow: Boolean): List<KeyRow> = letters(QWERTY, numberRow, extras(QWERTY, emptyList()))

    /**
     * [spec]'s letters page. With [numberRow] the digits get a row of their own and leave the top row's corners.
     * [extras] are each letter key's further long-press letters (the languages' own, the accents); [globe] puts the
     * layout switch's key between { } and the space bar.
     *
     * The board is as many key widths across as its longest row (ten for QWERTY, eleven for Russian, twelve for
     * Turkish); shorter rows stand in its middle. Shift and delete share what the third row leaves; a script without capitals has no shift.
     */
    fun letters(spec: LayoutSpec, numberRow: Boolean, extras: Map<String, List<String>> = emptyMap(), globe: Boolean = false): List<KeyRow> {
        val cells = spec.rows.map { row -> row.map { it.split(' ') } }
        // A board with a key of its own beside the space bar is at least eleven across, so the bar keeps four key
        // widths with the globe key there too.
        val units = maxOf(if (spec.bottomExtra != null) 11 else 10, cells.maxOf { it.size }).toFloat()
        fun key(cell: List<String>, hint: String?, also: String? = null) =
            Key(KeyType.CHAR, cell[0], hint = hint, alts = (listOfNotNull(hint, also) + cell.drop(1) + extras[cell[0]].orEmpty()).distinct())
        // A corner's mark is the layout's own where it has one (Arabic's ؟), the Latin one next on the long-press.
        fun marked(cell: List<String>, latin: String?) = latin?.let { spec.marks[it] }?.let { key(cell, it, latin) } ?: key(cell, latin)
        // Gboard's long-press hints, by place on every layout: digits along the top (the script's own digit after
        // each, where it has them), then the ?123 page's second and third rows in order.
        val top = cells[0].mapIndexed { i, c -> (if (numberRow) null else digits.getOrNull(i)).let { d -> key(c, d, d?.let { ownDigit(spec, it) }) } }
        val middle = cells[1].mapIndexed { i, c -> marked(c, MIDDLE_HINTS.getOrNull(i)?.toString()) }
        val third = cells[2].mapIndexed { i, c -> marked(c, THIRD_HINTS.getOrNull(i)?.toString()) }
        val spare = units - third.size
        val thirdRow = if (spec.hasShift) {
            val w = maxOf(1f, spare / 2f)
            listOf(shift.copy(width = w)) + third + delete.copy(width = w)
        } else {
            third + delete.copy(width = spare.coerceIn(1f, 2f))
        }
        val period = Key(KeyType.CHAR, spec.fullStop, alts = (spec.periodFirst + (if (spec.fullStop == ".") emptyList() else listOf(".")) + periodAlts).distinct())
        val bottom = listOfNotNull(
            // Gboard's, measured: ?123 and enter a key and a half, space the rest (five keys of QWERTY's ten).
            Key(KeyType.MODE, label = "?123", width = 1.5f, page = Page.SYMBOLS),
            devKey,
            if (globe) Key(KeyType.GLOBE) else null,
            Key(KeyType.SPACE, " ", width = units - 5f - (if (globe) 1f else 0f) - (if (spec.bottomExtra != null) 1f else 0f)),
            spec.bottomExtra,
            period,
            Key(KeyType.ENTER, width = 1.5f),
        )
        return listOfNotNull(
            if (numberRow) KeyRow(digits.map { digit(it, spec).copy(width = units / 10f) }) else null,
            KeyRow(top),
            // Gboard's middle row sits half a key in where it is a key shorter than the top one.
            KeyRow(middle, inset = if (middle.size.toFloat() == units - 1f) 0.5f else 0f),
            KeyRow(thirdRow),
            KeyRow(bottom),
        )
    }

    private const val MIDDLE_HINTS = "@#\$_&-+()/"
    private const val THIRD_HINTS = "*\"':;!?"

    /**
     * Each letter key of [spec]: how far across its middle is, in key widths less a half (so QWERTY's q is 0, a is 0.5
     * and z is 1.5, as Gboard staggers them), and its row.
     */
    fun positions(spec: LayoutSpec): Map<Char, Pair<Float, Int>> {
        val rows = letters(spec, numberRow = false)
        val units = rows.maxOf { it.units }
        val out = LinkedHashMap<Char, Pair<Float, Int>>()
        rows.take(3).forEachIndexed { r, row ->
            var x = row.inset + (units - row.units) / 2f
            for (key in row.keys) {
                if (key.type == KeyType.CHAR && key.text.length == 1 && key.text[0].isLetter()) out[key.text[0]] = (x + key.width / 2f - 0.5f) to r
                x += key.width
            }
        }
        return out
    }

    /** Each letter's neighbours on [spec]: the keys within a row of it and about a key away. */
    fun neighbours(spec: LayoutSpec): Map<Char, Set<Char>> {
        val at = positions(spec)
        return at.mapValues { (c, p) -> at.filter { (o, q) -> o != c && abs(q.second - p.second) <= 1 && abs(q.first - p.first) <= 1.01f }.keys }
    }

    /**
     * The further long-press letters of each key of [spec] with [languages] typed on it: each language's own letters
     * first (Polish's ą before à), then, on a Latin board, the accents every Latin board has had since the first one.
     */
    fun extras(spec: LayoutSpec, languages: List<Language>): Map<String, List<String>> {
        val out = LinkedHashMap<String, ArrayList<String>>()
        for (l in languages) for ((key, letters) in l.own) out.getOrPut(key) { ArrayList() }.addAll(letters)
        if (spec.script == Script.LATIN) for ((key, letters) in accents) out.getOrPut(key) { ArrayList() }.addAll(letters)
        // A letter that is a key of its own on this board (ñ on the Spanish one) need not hide behind another.
        val keys = spec.rows.flatten().map { it.substringBefore(' ') }.toSet()
        return out.mapValues { (_, letters) -> letters.distinct().filter { it !in keys } }.filterValues { it.isNotEmpty() }
    }

    /** "q w e|é r" as a row: cells apart by spaces, a cell's long-press letters after bars. */
    private fun rows(vararg rows: String): List<List<String>> = rows.map { row -> row.split(' ').map { it.replace('|', ' ') } }

    /** A Latin-script board: its id, what Settings calls it, and its three rows. */
    private fun latin(id: String, label: String, vararg r: String, locale: Locale = Locale.ROOT) = LayoutSpec(id, label, Script.LATIN, rows(*r), locale = locale)

    /** Persian and Urdu's half-space (U+200C): it parts two halves of a word without a gap, می‌خواهم. */
    private val halfSpace = Key(KeyType.CHAR, "\u200C", label = "half-space")

    private val arabicMarks = listOf("؟", "،", "؛")

    /** The Arabic script's comma, question mark and semicolon for the Latin ones, and its per cent sign beside %. */
    private val arabicSwaps = mapOf("," to "،", "?" to "؟", ";" to "؛")
    private val arabicMore = mapOf("%" to listOf("٪"))
    private const val ARABIC_DIGITS = "٠١٢٣٤٥٦٧٨٩"
    private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

    val QWERTY = latin("qwerty", "QWERTY", "q w e r t y u i o p", "a s d f g h j k l", "z x c v b n m")

    /** Every layout, by id. The rows follow AOSP's (as HeliBoard carries them), so each is the board its speakers know. */
    val SPECS: List<LayoutSpec> = listOf(
        QWERTY,
        latin("qwertz", "QWERTZ", "q w e r t z u i o p", "a s d f g h j k l", "y x c v b n m"),
        latin("azerty", "AZERTY", "a z e r t y u i o p", "q s d f g h j k l m", "w x c v b n '"),
        latin("spanish", "QWERTY + Ñ", "q w e r t y u i o p", "a s d f g h j k l ñ", "z x c v b n m"),
        latin("catalan", "QWERTY + Ç", "q w e r t y u i o p", "a s d f g h j k l ç", "z x c v b n m"),
        latin("swedish", "QWERTY + Å Ö Ä", "q w e r t y u i o p å", "a s d f g h j k l ö ä", "z x c v b n m"),
        latin("danish", "QWERTY + Å Æ Ø", "q w e r t y u i o p å", "a s d f g h j k l æ ø", "z x c v b n m"),
        latin("norwegian", "QWERTY + Å Ø Æ", "q w e r t y u i o p å", "a s d f g h j k l ø æ", "z x c v b n m"),
        latin("turkish", "Türkçe Q", "q w e r t y u ı o p ğ ü", "a s d f g h j k l ş i", "z x c v b n m ö ç", locale = Locale.forLanguageTag("tr")),
        LayoutSpec("russian", "Русская", Script.CYRILLIC, rows("й ц у к е|ё н г ш щ з х|ъ", "ф ы в а п р о л д ж э", "я ч с м и т ь|ъ б ю")),
        LayoutSpec("ukrainian", "Українська", Script.CYRILLIC, rows("й ц у к е н г|ґ ш щ з х ї", "ф і в а п р о л д ж є|'", "я ч с м и т ь б ю")),
        LayoutSpec("bulgarian", "Българска", Script.CYRILLIC, rows("я в е р т ъ у и|ѝ о п ч", "а с д ф г х й к л ш щ", "з ь ц ж б н м ю")),
        LayoutSpec("macedonian", "Македонска", Script.CYRILLIC, rows("љ њ е|ѐ р т ѕ у и|ѝ о п ш", "а с д ф г х ј к л ч ќ", "з џ ц в б н м ѓ ж")),
        LayoutSpec("serbian", "Српска", Script.CYRILLIC, rows("љ њ е|ѐ р т з|ѕ у и|ѝ о п ш", "а с д ф г х ј к л ч ћ", "џ ц в б н м ђ ж")),
        // No dead-accent key: an accent comes by long-press, or from autocorrect, which knows the accented word.
        LayoutSpec("greek", "Ελληνικά", Script.GREEK, rows(";|: ς ε|έ ρ τ υ|ύ|ϋ|ΰ θ ι|ί|ϊ|ΐ ο|ό π", "α|ά σ δ φ γ η|ή ξ κ λ", "ζ χ ψ ω|ώ β ν μ"), wordEnds = ";"),
        LayoutSpec("hebrew", "עברית", Script.HEBREW, rows("' - ק ר א ט ו ן ם פ", "ש ד ג כ ע י ח ל ך ף", "ז ס ב ה נ מ צ ת ץ")),
        LayoutSpec(
            "arabic", "العربية", Script.ARABIC,
            rows("ض ص ث ق ف|ڤ غ ع ه خ ح ج|چ", "ش س ي|ئ|ى ب|پ ل|لا|لأ|لإ|لآ ا|آ|ء|أ|إ|ٱ ت ن م ك|گ ط", "ذ ء ؤ ر ى ة و|ؤ ز|ژ ظ د"),
            wordEnds = "،؟؛", periodFirst = arabicMarks, digits = ARABIC_DIGITS, marks = arabicSwaps, markAlts = arabicMore,
        ),
        LayoutSpec(
            "persian", "فارسی", Script.PERSIAN,
            rows("ض ص ث ق ف غ ع ه|ة خ ح ج", "ش س ی|ئ|ي ب ل ا|آ|ء|أ|إ ت ن م ک|ك گ", "ظ ط ژ ز ر ذ د پ و|ؤ چ"),
            wordEnds = "،؟؛", periodFirst = arabicMarks, bottomExtra = halfSpace, digits = PERSIAN_DIGITS, marks = arabicSwaps, markAlts = arabicMore,
        ),
        // The phonetic board, its second layer on the long-presses.
        LayoutSpec(
            "urdu", "اردو", Script.URDU,
            rows("ق و|ؤ ع ر|ڑ ت|ٹ ے ء|ئ ی ہ|ۃ|ۂ پ", "ا|آ س|ص د|ڈ ف گ|غ ح|ھ ج|ض ک|خ ل", "ز|ذ ش|ژ چ|ث ط|ظ ب ن|ں م"),
            fullStop = "۔", wordEnds = "،؟؛۔", periodFirst = arabicMarks, bottomExtra = halfSpace, digits = PERSIAN_DIGITS, marks = arabicSwaps, markAlts = arabicMore,
        ),
        // Two-set Korean: shift gives the five double consonants and two more vowels; Hangul.kt builds the syllables.
        LayoutSpec(
            "korean", "한국어", Script.HANGUL,
            rows("ㅂ ㅈ ㄷ ㄱ ㅅ ㅛ ㅕ ㅑ ㅐ ㅔ", "ㅁ ㄴ ㅇ ㄹ ㅎ ㅗ ㅓ ㅏ ㅣ", "ㅋ ㅌ ㅊ ㅍ ㅠ ㅜ ㅡ"),
            shifted = mapOf("ㅂ" to "ㅃ", "ㅈ" to "ㅉ", "ㄷ" to "ㄸ", "ㄱ" to "ㄲ", "ㅅ" to "ㅆ", "ㅐ" to "ㅒ", "ㅔ" to "ㅖ"),
            swipe = false,
        ),
    )

    private val BY_ID = SPECS.associateBy { it.id }

    /** The layout with this [id], or null where there is none (a typing profile made for another keyboard's). */
    fun spec(id: String): LayoutSpec? = BY_ID[id]

    /** A key that types [text] (with its `¦` caret mark), showing [label]. */
    private fun snip(label: String, text: String, vararg alts: String) = Key(KeyType.CHAR, text, label = label, alts = alts.toList())

    /**
     * The developer page: what code is made of, a tap away. Top row, pairs that leave the cursor inside (long-press
     * for one half alone) and the shapes typed most; middle, operators, each long-press holding its compounds; then
     * tab and the punctuation code leans on; the comma lives here now.
     */
    fun dev(): List<KeyRow> = listOf(
        KeyRow(
            listOf(
                snip("( )", "(¦)", "(", ")"),
                snip("[ ]", "[¦]", "[", "]"),
                snip("{ }", "{¦}", "{", "}", "{\n  ¦\n}"),
                snip("< >", "<¦>", "<", ">", "</¦>"),
                snip("\" \"", "\"¦\"", "\""),
                snip("' '", "'¦'", "'"),
                snip("` `", "`¦`", "`", "```\n¦\n```"),
                snip("\${ }", "\${¦}", "{{¦}}", "%s", "{}"),
                snip("=>", " => ", "->", "<-", "=>", "::"),
                snip("ƒ", "function ¦() {}", "() => {¦}", "const ¦ = () => {}", "def ¦():", "fn ¦() {}", "func ¦() {}"),
            ),
        ),
        KeyRow(
            listOf(
                snip("=", "=", "==", "===", "!=", "!==", "<=", ">="),
                snip("+", "+", "++", "+="),
                snip("-", "-", "--", "-=", "->"),
                snip("*", "*", "**", "*=", "/*¦*/"),
                snip("/", "/", "//", "/=", "\\"),
                snip("|", "|", "||", "|=", "|>"),
                snip("&", "&", "&&", "&="),
                snip("!", "!", "!=", "!!"),
                snip("?", "?", "??", "?.", "??="),
                snip("%", "%", "%=", "^", "^="),
            ),
        ),
        KeyRow(
            listOf(Key(KeyType.CHAR, "\t", label = "⇥", width = 1.5f)) +
                listOf(
                    snip(";", ";"), snip(":", ":", "::", ":="), snip("_", "_", "__"), snip("$", "$", "\$("),
                    snip("#", "#", "#!/"), snip("@", "@"), snip("~", "~", "`", "^"),
                ) + delete,
        ),
        bottomRow("ABC", Page.LETTERS, devComma),
    )

    /**
     * ?123: digits, the common symbols, and =\< for the rest. On [spec]'s page its own digits lead each digit's
     * long-press, and its own comma, question mark and semicolon take the Latin ones' keys (Arabic, Persian, Urdu).
     */
    fun symbols(spec: LayoutSpec = QWERTY): List<KeyRow> = listOf(
        KeyRow(digits.map { digit(it, spec) }),
        KeyRow("@#\$_&-+()/".map { sym(it.toString(), spec) }),
        KeyRow(
            listOf(Key(KeyType.MODE, label = "=\\<", width = 1.5f, page = Page.MORE)) +
                "*\"':;!?".map { sym(it.toString(), spec) } + delete,
        ),
        bottomRow("ABC", Page.LETTERS, sym(",", spec), spec.periodFirst),
    )

    /** =\<: the rarer symbols, with [spec]'s own beside them (٪ on %'s long-press). */
    fun more(spec: LayoutSpec = QWERTY): List<KeyRow> = listOf(
        KeyRow("~`|•√π÷×§∆".map { sym(it.toString(), spec) }),
        KeyRow("£¥€¢^°={}\\".map { sym(it.toString(), spec) }),
        KeyRow(
            listOf(Key(KeyType.MODE, label = "?123", width = 1.5f, page = Page.SYMBOLS)) +
                "%©®™✓[]".map { sym(it.toString(), spec) } + delete,
        ),
        bottomRow("ABC", Page.LETTERS, sym(",", spec), spec.periodFirst),
    )
}
