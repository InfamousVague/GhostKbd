package com.infamousvague.devkeyboard

/** What a key does when it is pressed. */
enum class KeyType { CHAR, SHIFT, DELETE, SPACE, ENTER, MODE, MIC }

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

object Layouts {
    /**
     * The person's own long-press keys (Settings › Long-press keys): a key's text to the choices its popup offers,
     * in order. A key here shows its first choice in its corner; an empty list means no popup at all.
     */
    @Volatile var custom: Map<String, List<String>> = emptyMap()

    /** "a=á à â\n.=? !" as [custom] reads it: one key a line, its choices split by spaces. */
    fun parseCustom(spec: String): Map<String, List<String>> = spec.lines().mapNotNull { line ->
        val eq = line.indexOf('=', startIndex = 1)
        if (eq < 1) null else line.substring(0, eq) to line.substring(eq + 1).split(' ').filter { it.isNotEmpty() }
    }.toMap()

    /** The letters page's popups as they ship, QWERTY's order then the period: what Settings shows and resets to. */
    fun defaultPopups(): List<Pair<String, List<String>>> {
        val saved = custom
        custom = emptyMap()
        try {
            return letters(false).flatMap { it.keys }.filter { it.type == KeyType.CHAR && it.text != "," }.map { it.text to it.alts }
        } finally {
            custom = saved
        }
    }

    private fun own(key: Key): Key {
        val mine = custom[key.text] ?: return key
        return key.copy(alts = mine, hint = if (key.type == KeyType.CHAR && key.text.length == 1 && key.text[0].isLetter()) mine.firstOrNull() else key.hint)
    }
    /** The dictation plugin's mic key, beside the space bar, which gives it a key's width. */
    fun withMic(rows: List<KeyRow>): List<KeyRow> {
        val last = rows.last()
        val keys = last.keys.flatMap { if (it.type == KeyType.SPACE) listOf(Key(KeyType.MIC), it.copy(width = it.width - 1f)) else listOf(it) }
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
        own(Key(KeyType.CHAR, c, hint = hint, alts = listOfNotNull(hint) + accents[c].orEmpty()))

    private fun sym(c: String) = own(Key(KeyType.CHAR, c, alts = symbolAlts[c].orEmpty()))

    private val shift = Key(KeyType.SHIFT, width = 1.5f)
    private val delete = Key(KeyType.DELETE, width = 1.5f)

    private val comma = Key(KeyType.CHAR, ",")

    /** Where Gboard has its comma: the developer page's key (long-press: settings). */
    private val devKey = Key(KeyType.MODE, label = "{ }", page = Page.DEV, pill = false)

    /** The same key on the developer page, where it goes back: a second tap of { } (or a double tap) is the letters. */
    private val devBack = Key(KeyType.MODE, label = "{ }", page = Page.LETTERS, pill = false)

    private fun bottomRow(modeLabel: String, modePage: Page, second: Key = comma) = KeyRow(
        listOf(
            // Gboard's, measured: ?123 and enter a key and a half, space five keys.
            Key(KeyType.MODE, label = modeLabel, width = 1.5f, page = modePage),
            second,
            Key(KeyType.SPACE, " ", width = 5f),
            own(Key(KeyType.CHAR, ".", alts = periodAlts)),
            Key(KeyType.ENTER, width = 1.5f),
        ),
    )

    private fun numberRow() = KeyRow(digits.map { sym(it) })

    /** QWERTY. With [numberRow] the digits get a row of their own and leave the top row's corners. */
    fun letters(numberRow: Boolean): List<KeyRow> {
        val top = "qwertyuiop".mapIndexed { i, c -> letter(c.toString(), if (numberRow) null else digits[i]) }
        // Gboard's long-press symbol hints: the ?123 page's second and third rows, in order.
        val middle = "asdfghjkl".zip("@#\$_&-+()") { c, h -> letter(c.toString(), h.toString()) }
        val bottom = "zxcvbnm".zip("*\"':;!?") { c, h -> letter(c.toString(), h.toString()) }
        return listOfNotNull(
            if (numberRow) numberRow() else null,
            KeyRow(top),
            KeyRow(middle, inset = 0.5f),
            KeyRow(listOf(shift) + bottom + delete),
            bottomRow("?123", Page.SYMBOLS, devKey),
        )
    }

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
        bottomRow("ABC", Page.LETTERS, devBack),
    )

    /** ?123: digits, the common symbols, and =\< for the rest. */
    fun symbols(): List<KeyRow> = listOf(
        numberRow(),
        KeyRow("@#\$_&-+()/".map { sym(it.toString()) }),
        KeyRow(
            listOf(Key(KeyType.MODE, label = "=\\<", width = 1.5f, page = Page.MORE)) +
                "*\"':;!?".map { sym(it.toString()) } + delete,
        ),
        bottomRow("ABC", Page.LETTERS),
    )

    /** =\<: the rarer symbols. */
    fun more(): List<KeyRow> = listOf(
        KeyRow("~`|•√π÷×§∆".map { sym(it.toString()) }),
        KeyRow("£¥€¢^°={}\\".map { sym(it.toString()) }),
        KeyRow(
            listOf(Key(KeyType.MODE, label = "?123", width = 1.5f, page = Page.SYMBOLS)) +
                "%©®™✓[]".map { sym(it.toString()) } + delete,
        ),
        bottomRow("ABC", Page.LETTERS),
    )
}
