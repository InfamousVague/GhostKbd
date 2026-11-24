package com.infamousvague.devkeyboard

/**
 * The `musclememory` plugin: Microsoft SwiftKey's board, for someone with ten years of it in their thumbs (Kevin,
 * 2026-11-13, from his own SwiftKey and the GhostKBD Figma mock, nodes 9:7983 and 9:5099). Three parts, each a
 * switch of its own on the plugin's page ([Parts]), all on when it is added:
 *
 * - **Layout** (`mm_layout`): SwiftKey's symbols in the letters' corners and first on their long-press
 *   (`% ^ ~ | [ ] < > { }` along the top, `@ # & * - + = ( )` in the middle, `_ $ " ' : ; /` along the bottom); the
 *   bottom row ?123, emoji, comma (a hold dictates, with Dictation ready), space, full stop, enter; a touch on the
 *   full stop that slides at once opens `! , . ?` with the full stop under the finger ([FLICK]); and every key a face.
 * - **Numbers** (`mm_numbers`): a number row on every page, bare digits a little shorter than a row of keys, as
 *   SwiftKey's is ([NUMBER_ROW]).
 * - **Arrows** (`mm_arrows`): a short row of up, down, left, right under every page, repeating while held.
 *
 * With any part on, a key is the same size on every page (Kevin, 2026-11-14: "make the size of the buttons match in
 * all of the sub areas"): rows are their own height rather than spread over the letters' room, and a page with
 * fewer rows sits on the same bottom line, the room above it empty, so the thumbs find the bottom rows where they were.
 */
object MuscleMemory {
    /** Which parts are on. [any]: one of them is, and the keys keep one size on every page. */
    data class Parts(val layout: Boolean = false, val numbers: Boolean = false, val arrows: Boolean = false) {
        val any: Boolean get() = layout || numbers || arrows
    }

    /** The parts that are on: none unless the plugin is added. */
    fun parts(prefs: KeyboardPrefs): Parts =
        if (!Plugins.isOn(prefs, Plugins.MUSCLE_MEMORY)) Parts() else Parts(prefs.muscleLayout, prefs.muscleNumbers, prefs.muscleArrows)

    /** SwiftKey's corner symbols, by place along each letter row. */
    const val TOP_HINTS = "%^~|[]<>{}"
    const val MIDDLE_HINTS = "@#&*-+=()"
    const val THIRD_HINTS = "_\$\"':;/"

    /** The arrow row's height, in letter rows. */
    const val ARROW_ROW = 0.7f

    /** The number row's height, in letter rows: SwiftKey's digits sit in a shorter row than its letters. */
    const val NUMBER_ROW = 0.75f

    /** What the full stop's corner shows, and the marks its long-press starts with, in that order. */
    const val PERIOD_HINT = ",!?"

    /** The full stop's quick menu, as SwiftKey has it: a slide from the full stop, the full stop under the finger. */
    val FLICK = listOf("!", ",", ".", "?")

    /** How far a finger slides on the full stop before its quick menu opens (dp): past a tap's wobble, well short of a key. */
    const val FLICK_DP = 10f

    /** A number row: bare digits, [NUMBER_ROW] tall where the numbers part is on, each digit's long-press kept. */
    fun numbers(row: KeyRow, condensed: Boolean): KeyRow = KeyRow(row.keys.map { it.copy(bare = true) }, row.inset, if (condensed) NUMBER_ROW else row.height)

    /**
     * [rows], the letters page as [Layouts.letters] makes it (with or without a number row), made SwiftKey's: its
     * corners, and its bottom row. [emoji]: the emoji plugin is added, so its key stands beside ?123 (otherwise the
     * space bar has its width). [mic]: dictation is ready, so a hold on the comma starts it.
     */
    fun letters(rows: List<KeyRow>, emoji: Boolean, mic: Boolean): List<KeyRow> {
        // The last four: three letter rows (the third with shift and delete), then the bottom row.
        if (rows.size < 4) return rows
        val n = rows.size
        return rows.take(n - 4) + listOf(
            hinted(rows[n - 4], TOP_HINTS),
            hinted(rows[n - 3], MIDDLE_HINTS),
            hinted(rows[n - 2], THIRD_HINTS),
            bottom(rows[n - 1], emoji, mic),
        )
    }

    /**
     * A page other than the letters, with the parts that are on: [number] (the letters' number row) above the code
     * keys and =\<, which have no digits of their own, so every page is as many rows as the letters; on ?123 its own
     * digits made the number row.
     */
    fun page(rows: List<KeyRow>, page: Page, number: KeyRow?, condensed: Boolean): List<KeyRow> = when {
        number == null -> rows
        page == Page.SYMBOLS -> listOf(numbers(rows.first(), condensed)) + rows.drop(1)
        else -> listOf(number) + rows
    }

    /** A letter row with [hints] in its letters' corners, by place, each first on its key's long-press. */
    private fun hinted(row: KeyRow, hints: String): KeyRow {
        var at = 0
        return KeyRow(
            row.keys.map { key ->
                if (key.type != KeyType.CHAR) return@map key
                val hint = hints.getOrNull(at++)?.toString() ?: return@map key
                key.copy(hint = hint, alts = (listOf(hint) + key.alts.filter { it != key.hint }).distinct())
            },
            row.inset,
            row.height,
        )
    }

    /** SwiftKey's bottom row from Gboard's: ?123, emoji, comma, space, full stop, enter, as wide as it was. */
    private fun bottom(row: KeyRow, emoji: Boolean, mic: Boolean): KeyRow {
        val keys = row.keys
        val mode = keys.firstOrNull { it.type == KeyType.MODE && it.page == Page.SYMBOLS } ?: return row
        val enter = keys.firstOrNull { it.type == KeyType.ENTER } ?: return row
        val stop = keys.getOrNull(keys.indexOf(enter) - 1)?.takeIf { it.type == KeyType.CHAR } ?: return row
        val globe = keys.firstOrNull { it.type == KeyType.GLOBE }
        val spaceAt = keys.indexOfFirst { it.type == KeyType.SPACE }
        // A key of the layout's own right of the bar (Persian's half-space).
        val extra = keys.withIndex().firstOrNull { (i, k) -> i > spaceAt && k !== stop && k.type == KeyType.CHAR }?.value
        val emojiKey = if (emoji) Key(KeyType.EMOJI, pill = false) else null
        val comma = Key(KeyType.CHAR, ",", holdsMic = mic)
        val period = stop.copy(
            hint = PERIOD_HINT,
            alts = (PERIOD_HINT.map { it.toString() } + stop.alts).distinct().filter { it != stop.text },
            flick = stop.text == ".",
        )
        val units = row.units - row.inset * 2
        val fixed = listOfNotNull(mode, emojiKey, comma, globe, extra, period, enter)
        val space = Key(KeyType.SPACE, " ", width = (units - fixed.sumOf { it.width.toDouble() }.toFloat()).coerceAtLeast(Layouts.LEAST_BAR))
        return KeyRow(listOfNotNull(mode, emojiKey, comma, globe, space, extra, period, enter), row.inset, row.height)
    }

    /** The arrow row, as wide as the board: up, down, left, right, as SwiftKey has them. */
    fun arrows(units: Float): KeyRow {
        val w = units / 4f
        return KeyRow(listOf("up", "down", "left", "right").map { Key(KeyType.ARROW, it, label = it, width = w, bare = true) }, height = ARROW_ROW)
    }

    /**
     * The full stop's quick menu laid out: [FLICK]'s cells, each [cellW] wide, the full stop's centred on [keyX], the
     * whole kept between [minX] and [maxX] (moved in from an edge, never squeezed). The left edge of the first cell.
     */
    fun flickLeft(keyX: Float, cellW: Float, minX: Float, maxX: Float): Float {
        val home = FLICK.indexOf(".")
        val left = keyX - cellW * (home + 0.5f)
        val w = cellW * FLICK.size
        return left.coerceAtMost(maxX - w).coerceAtLeast(minX)
    }
}
