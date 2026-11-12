// tool/versus: Ghost Keyboard on the JVM, fed the very taps that were injected on the emulator.
//
// Three things, each a sub-command of tool/versus/replay.sh:
//
//   geometry <width px> <height px> <dpi> [nav px]      where the keys are, worked out by the keyboard's own layout
//                                                      code: the first idea tool/versus/calibrate.py starts from
//   probes <geometry.json>                             the taps calibrate.py made on the emulator, read again here:
//                                                      does this file's idea of which key a touch means agree?
//   replay <width> <height> <dpi> [nav px] < taps.tsv  types each line's taps and prints what the keyboard would
//                                                      leave in the field
//
// The decoder is the app's own source (Autocorrect.kt, TapDecoder.kt, NextWordModel.kt, Layouts.kt), compiled as it
// stands in this tree. What is NOT the app's own code is what stands between a touch and the decoder, which lives in
// an Android view and a service and cannot run here: [Board] is KeyboardView's layoutKeys, letterCorrection and keyAt
// read across, and [Typist] is the part of DevKeyboardService a tapped letter, space or full stop goes through
// (onText, onSpace, correctLastWord, tapsFor). Both are held to the emulator: `probes` for the keys, and
// tool/versus/score.py's agreement count for the typing. When either drifts from the app, those two say so.
//
// A line of taps.tsv:  <id> TAB <event> <event> ...   where an event is  T:<x>:<y>:<ms>  (a tap at a screen pixel)
// or  M:<mark>:<ms>  (a mark typed from the ?123 page, which is not modelled: the mark is typed as that page's key
// would type it). Out:  <id> TAB <field's text> TAB <what the keys gave, no autocorrect>.

import com.infamousvague.devkeyboard.Autocorrect
import com.infamousvague.devkeyboard.Key
import com.infamousvague.devkeyboard.KeyRow
import com.infamousvague.devkeyboard.KeyType
import com.infamousvague.devkeyboard.LanguageState
import com.infamousvague.devkeyboard.Layouts
import com.infamousvague.devkeyboard.NextWordText
import com.infamousvague.devkeyboard.NgramTable
import com.infamousvague.devkeyboard.PersonalPairs
import com.infamousvague.devkeyboard.Scorer
import com.infamousvague.devkeyboard.Taps
import com.infamousvague.devkeyboard.Words
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp

/** android.graphics.RectF, as far as the keyboard uses it. */
class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f
    val w get() = right - left
    val h get() = bottom - top
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
}

/**
 * The letters page as KeyboardView draws and reads it at the app's defaults (Normal key height, the row of tools
 * showing, the emoji key, no number row, no mic or globe key, a phone's width, the Adaptive keys plugin off), in the
 * view's own pixels. [top] is where the view starts on the screen.
 */
class Board(root: File, val width: Float, val screenHeight: Float, dpi: Float, val navInset: Int) {
    private val density = dpi / 160f
    private fun dp(v: Float) = v * density

    /** KeyboardView.LETTER_OFFSETS_DP, read from the source so a change there is a change here. */
    private val offsetsDp: Map<String, Pair<Float, Float>> = run {
        val src = File(root, "android/app/src/main/kotlin/com/infamousvague/devkeyboard/KeyboardView.kt").readText()
        val block = src.substringAfter("LETTER_OFFSETS_DP: Map<String, Pair<Float, Float>> = mapOf(").substringBefore("\n        )")
        val found = Regex("\"(\\w)\" to \\((-?[\\d.]+)f to (-?[\\d.]+)f\\)").findAll(block).associate { it.groupValues[1] to (it.groupValues[2].toFloat() to it.groupValues[3].toFloat()) }
        check(found.size == 26) { "KeyboardView.LETTER_OFFSETS_DP: read ${found.size} of 26 letters" }
        found
    }
    private val qwertyRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val qwertyInsets = floatArrayOf(0f, 0.5f, 1.5f)

    private val rowHeightDp = 60f
    private val previewSpace = dp(rowHeightDp * 1.3f)
    private val toolbarHeight = dp(44f)
    val keysTop = previewSpace + toolbarHeight
    val rows: List<KeyRow> = Layouts.withEmoji(Layouts.letters(Layouts.QWERTY, false, Layouts.extras(Layouts.QWERTY, emptyList()), false), false)
    val symbols: List<KeyRow> = Layouts.symbols(Layouts.QWERTY)
    private val keysHeight = 4 * dp(rowHeightDp)
    private val sidePad = dp(1.5f)
    private val bottomPad = dp(2f)
    val height = ceil(keysTop + keysHeight + bottomPad).toInt() + navInset
    val top = screenHeight - height
    /** The part Android is told is the keyboard: below the see-through room for key previews. */
    val shownHeight = height - previewSpace

    val keyRects = ArrayList<Pair<Key, Rect>>()
    private val keyOffsets = ArrayList<Pair<Float, Float>?>()

    init { layout(rows, keyRects, keyOffsets, letters = true) }

    private fun placeOffset(row: Int, across: Float): Pair<Float, Float>? {
        val letters = qwertyRows.getOrNull(row) ?: return null
        val inset = qwertyInsets[row]
        val nearest = letters.indices.minByOrNull { abs((it + 0.5f + inset) / 10f - across) } ?: return null
        return offsetsDp[letters[nearest].toString()]
    }

    /** KeyboardView.layoutKeys, for a board that is neither wide nor one-handed. */
    private fun layout(rows: List<KeyRow>, rects: ArrayList<Pair<Key, Rect>>, offsets: ArrayList<Pair<Float, Float>?>?, letters: Boolean) {
        val units = rows.maxOf { it.units }
        val unit = (width - sidePad * 2) / units
        val rowH = keysHeight / rows.size
        var y = keysTop
        val firstLetterRow = if (letters) rows.size - 4 else -1
        rows.forEachIndexed { r, row ->
            val start = row.inset + (units - row.units) / 2
            var across = start
            var x = sidePad + start * unit
            for (key in row.keys) {
                val w = key.width * unit
                rects += key to Rect(x, y, x + w, y + rowH)
                offsets?.add(if (firstLetterRow >= 0 && key.type == KeyType.CHAR) placeOffset(r - firstLetterRow, (across + key.width / 2) / units) else null)
                x += w
                across += key.width
            }
            y += rowH
        }
    }

    /** The ?123 page's keys, laid out as the letters are (it spreads its rows over the same space). */
    val symbolRects: List<Pair<Key, Rect>> by lazy { ArrayList<Pair<Key, Rect>>().also { layout(symbols, it, null, letters = false) } }

    /** KeyboardView.letterCorrection. */
    private fun letterCorrection(x: Float, y: Float): Pair<Float, Float> {
        val unitW = keyRects.firstOrNull { it.first.type == KeyType.CHAR }?.second?.w ?: return 0f to 0f
        val sigma2 = 2f * (unitW * 0.6f) * (unitW * 0.6f)
        var sw = 0f; var sx = 0f; var sy = 0f
        val bottomTop = keyRects.last().second.top
        for (i in keyRects.indices) {
            val r = keyRects[i].second
            val off = keyOffsets[i]
            if (off == null && r.top < bottomTop) continue
            val dx = x - r.cx; val dy = y - r.cy
            val w = exp(-(dx * dx + dy * dy) / sigma2)
            sw += w
            if (off != null) { sx += w * dp(off.first); sy += w * dp(off.second) }
        }
        return if (sw < 1e-6f) 0f to 0f else (sx / sw) to (sy / sw)
    }

    class Hit(val key: Key, val rect: Rect, val aimX: Float, val aimY: Float)

    /** KeyboardView.keyAt on the letters page, the view's own pixels in and out: the key, and where the touch was read as aiming. */
    fun keyAt(rawX: Float, rawY: Float): Hit? {
        if (rawY < keysTop) return null
        val (ox, oy) = letterCorrection(rawX, rawY)
        val x = rawX - ox
        val y = (rawY - oy).coerceAtLeast(keysTop + 1f)
        keyRects.firstOrNull { it.second.contains(x, y) }?.let { return Hit(it.first, it.second, x, y) }
        val near = keyRects.minByOrNull { (_, r) ->
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            dx * dx + dy * dy
        } ?: return null
        return Hit(near.first, near.second, x, y)
    }

    /** KeyboardView.tapKeys: each letter key's middle, a letter key's width, the space bar. */
    val tapKeys: Triple<Map<Char, Pair<Float, Float>>, Float, FloatArray?> = run {
        val out = HashMap<Char, Pair<Float, Float>>(48)
        var keyW = 0f
        var space: FloatArray? = null
        for ((k, r) in keyRects) {
            if (k.type == KeyType.SPACE) { space = floatArrayOf(r.left, r.top, r.right, r.bottom); continue }
            if (k.type != KeyType.CHAR || k.text.length != 1 || !k.text[0].isLetter()) continue
            if (keyW == 0f) keyW = r.w
            out[k.text[0]] = r.cx to r.cy
        }
        Triple(out, keyW, space)
    }

    /** A key's name in the geometry file. */
    fun name(key: Key): String? = when (key.type) {
        KeyType.CHAR -> key.text
        KeyType.SPACE -> "space"
        KeyType.SHIFT -> "shift"
        KeyType.DELETE -> "delete"
        KeyType.ENTER -> "enter"
        KeyType.EMOJI -> "emoji"
        KeyType.MODE -> when (key.label) { "?123" -> "symbols"; "{ }" -> "dev"; "ABC" -> "letters"; else -> key.label }
        else -> null
    }
}

/** The decoder with its word lists and starter table, as the service sets it up for English with nothing learned. */
class Decoder(root: File) {
    private val assets = File(root, "android/app/src/main/assets")
    val state = LanguageState.of(listOf("en"), emptyMap(), "")
    val ac = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
    private val table: NgramTable = File(assets, "ngram/en.tsv").useLines { NgramTable.parse(it) }!!
    private val personal = PersonalPairs()
    private val lookup = object : Scorer.Lookup {
        override fun isWord(lower: String) = ac.isWord(lower)
        override fun form(lower: String) = personal.form(lower)
        override fun count(lower: String) = personal.unigram(lower)
        override fun starting(prefix: String) = personal.starting(prefix)
        override fun fills() = personal.top(3).map { personal.form(it) } + ac.commonest(6)
    }
    init {
        ac.loadNow(state.dictionaries, state.board)
        ac.setSlang(true)
        Thread.sleep(400) // the slang list is read on its own thread
    }
    /** NextWords.at with nothing typed before and Mockingbird off. */
    fun at(place: NextWordText.Place): Scorer {
        val one = personal.followers(place.prev)
        val two = place.prev2?.takeIf { place.prev != NextWordText.START }?.let { personal.followers("$it ${place.prev}") } ?: emptyList()
        return Scorer(place, listOf(table), Scorer.weights(listOf(table), place.recent), one, two, lookup)
    }
}

/**
 * What DevKeyboardService does with a tapped letter, a space and a mark that ends a word, in a message field
 * (sentences capitalised, autocorrect wanted) that asks for no learning. [correcting] off leaves the keys' own text.
 */
class Typist(private val board: Board, private val decoder: Decoder, private val correcting: Boolean = true) {
    val text = StringBuilder()
    private class Touch(val c: Char, val x: Float, val y: Float)
    private val wordTouches = ArrayList<Touch?>()
    private var lastSpaceAt = 0L
    private val joiners = decoder.state.joiners
    private val locale = decoder.state.locale
    var unhandled = 0
    /** What autocorrect did, a word at a time: "typed>stood" where it changed one. */
    val fixes = ArrayList<String>()

    private fun isWordChar(c: Char) = Words.isWordChar(c, joiners)

    /** android.text.TextUtils.getCapsMode for a field that capitalises sentences, the cursor at the end: is shift on? */
    private fun capsAtEnd(): Boolean {
        val cs = text
        var i = cs.length
        while (i > 0) {
            val c = cs[i - 1]
            if (c != '"' && c != '\'' && Character.getType(c) != Character.START_PUNCTUATION.toInt()) break
            i--
        }
        var j = i
        while (j > 0 && (cs[j - 1] == ' ' || cs[j - 1] == '\t')) j--
        if (j == 0 || cs[j - 1] == '\n') return true
        if (i == j) return false
        while (j > 0) {
            val c = cs[j - 1]
            if (c != '"' && c != '\'' && Character.getType(c) != Character.END_PUNCTUATION.toInt()) break
            j--
        }
        if (j > 0) {
            val c = cs[j - 1]
            if (c == '.' || c == '?' || c == '!') {
                if (c == '.') {
                    var k = j - 2
                    while (k >= 0) {
                        val d = cs[k]
                        if (d == '.') return false
                        if (!d.isLetter()) break
                        k--
                    }
                }
                return true
            }
        }
        return false
    }

    /** A tap at a screen pixel, [ms] after the sentence began. */
    fun tap(screenX: Float, screenY: Float, ms: Long) {
        val hit = board.keyAt(screenX, screenY - board.top) ?: run { unhandled++; return }
        when (hit.key.type) {
            KeyType.CHAR -> {
                val key = hit.key.text
                val shown = if (capsAtEnd() && key.length == 1 && key[0].isLetter()) key.uppercase(locale) else key
                onText(shown, if (key.length == 1) Touch(key[0], hit.aimX, hit.aimY) else null)
            }
            KeyType.SPACE -> { try { space(ms) } finally { wordTouches.clear() } }
            else -> unhandled++
        }
    }

    /** A mark typed from another page. */
    fun mark(c: Char) = onText(c.toString(), null)

    private fun onText(t: String, touch: Touch?) {
        lastSpaceAt = 0L
        if (t.length == 1 && t[0] in ".,!?" && correctLastWord(t)) { wordTouches.clear(); return }
        text.append(t)
        // adaptiveTyped: a letter's touch joins the word's; a joiner inside a word has none; anything else ends the word.
        if (t.length == 1 && t[0] in joiners && wordTouches.isNotEmpty()) { wordTouches += null; return }
        if (t.length != 1 || !t[0].isLetter()) { wordTouches.clear(); return }
        wordTouches += touch?.takeIf { it.c == t[0] || it.c.toString().lowercase(locale) == t.lowercase(locale) }
    }

    private fun space(now: Long) {
        // The double-space full stop.
        if (lastSpaceAt != 0L && now - lastSpaceAt < 1000 && text.length >= 2 && text[text.length - 1] == ' ' && text[text.length - 2].isLetterOrDigit()) {
            text.setLength(text.length - 1)
            text.append(". ")
            lastSpaceAt = 0L
            return
        }
        if (correctLastWord(" ")) { lastSpaceAt = now.coerceAtLeast(1L); return }
        text.append(' ')
        lastSpaceAt = now.coerceAtLeast(1L)
    }

    private fun tapsFor(word: String): Taps? {
        if (word.isEmpty() || wordTouches.size != word.length) return null
        val xs = FloatArray(word.length) { Float.NaN }
        val ys = FloatArray(word.length) { Float.NaN }
        var known = 0
        for (i in word.indices) {
            val t = wordTouches[i] ?: continue
            if (t.c != word[i] && t.c.toString().lowercase(locale) != word[i].toString().lowercase(locale)) return null
            xs[i] = t.x; ys[i] = t.y; known++
        }
        if (known * 2 < word.length) return null
        val (keys, keyW, space) = board.tapKeys
        return Taps(xs, ys, keys, keyW, space)
    }

    /** DevKeyboardService.correctLastWord, less what needs a person (a word typed again, a swipe, a report). */
    private fun correctLastWord(separator: String): Boolean {
        if (!correcting) return false
        val before = text.takeLast(96).toString()
        val from = (before.length - 48).coerceAtLeast(0)
        val word = before.substring(Words.lastStart(before, joiners, from))
        if (word.isEmpty()) return false
        val start = Words.runStart(before, joiners, from)
        if (start > 0) {
            val prev = before[start - 1]
            if (prev.isLetterOrDigit() || prev in "@#/._-\\:\$`") return false
        }
        val scorer = runCatching { NextWordText.place(before, ::isWordChar, before.length >= 96, locale, "")?.let { decoder.at(it) } }.getOrNull()
        val fixed = decoder.ac.correct(word, scorer, tapsFor(word)) ?: return false
        fixes += "$word>$fixed"
        text.setLength(text.length - word.length)
        text.append(fixed).append(separator)
        return true
    }
}

fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun board(root: File, args: List<String>): Board {
    val w = args[0].toFloat(); val h = args[1].toFloat(); val dpi = args[2].toFloat()
    // The strip Android keeps under the keys with gesture navigation: KeyboardView.NAV_BUTTONS_DP (48) at this density.
    val nav = args.getOrNull(3)?.toInt() ?: ceil(48f * dpi / 160f).toInt()
    return Board(root, w, h, dpi, nav)
}

/** The hint calibrate.py starts from: every key's cell on the screen, before any touch correction. */
fun geometry(root: File, args: List<String>) {
    val b = board(root, args)
    fun rect(r: Rect) = "[%.2f, %.2f, %.2f, %.2f]".format(r.left, r.top + b.top, r.right, r.bottom + b.top)
    val keys = b.keyRects.mapNotNull { (k, r) -> b.name(k)?.let { json(it) + ": " + rect(r) } }
    val marks = b.symbolRects.mapNotNull { (k, r) -> b.name(k)?.takeIf { it in listOf(",", "?", "!", "'", "letters") }?.let { json(it) + ": " + rect(r) } }
    println("{")
    println(" \"from\": \"KeyboardView.layoutKeys, read across in tool/versus/replay/Replay.kt\",")
    println(" \"keyboard_height\": ${ceil(b.shownHeight).toInt()},")
    println(" \"keys\": {" + keys.joinToString(", ") + "},")
    println(" \"symbols_page\": {" + marks.joinToString(", ") + "}")
    println("}")
}

/** calibrate.py's taps, read by [Board.keyAt]: each must mean the key the emulator typed. */
fun probes(root: File, args: List<String>) {
    val text = File(args[0]).readText()
    fun num(key: String) = Regex("\"$key\": (\\d+)").find(text)!!.groupValues[1].toFloat()
    val b = Board(root, num("width"), num("height"), num("dpi"), ceil(48f * num("dpi") / 160f).toInt())
    val taps = Regex("\\[\\s*(-?[\\d.]+),\\s*(-?[\\d.]+),\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*\\]").findAll(text.substringAfter("\"probes\":")).toList()
    var same = 0
    val wrong = ArrayList<String>()
    for (m in taps) {
        val x = m.groupValues[1].toFloat(); val y = m.groupValues[2].toFloat(); val typed = m.groupValues[3]
        val hit = b.keyAt(x, y - b.top)
        val here = when (hit?.key?.type) { KeyType.CHAR -> hit.key.text; KeyType.SPACE -> " "; else -> "" }
        if (here == typed) same++ else wrong += "(%.0f, %.0f) typed %s on the emulator, %s here".format(x, y, json(typed), json(here))
    }
    println("probes: ${taps.size} taps from the emulator, $same read the same here")
    wrong.take(30).forEach { println("  $it") }
    if (taps.isEmpty() || same != taps.size) kotlin.system.exitProcess(1)
}

fun replay(root: File, args: List<String>) {
    val b = board(root, args)
    val decoder = Decoder(root)
    var line = readLine()
    var n = 0
    while (line != null) {
        val tab = line.indexOf('\t')
        if (tab > 0) {
            val id = line.substring(0, tab)
            val typist = Typist(b, decoder)
            val plain = Typist(b, decoder, correcting = false)
            for (e in line.substring(tab + 1).split(' ')) {
                val f = e.split(':')
                when (f.getOrNull(0)) {
                    "T" -> { typist.tap(f[1].toFloat(), f[2].toFloat(), f[3].toLong()); plain.tap(f[1].toFloat(), f[2].toFloat(), f[3].toLong()) }
                    "M" -> { val c = e.substring(2, 3)[0]; typist.mark(c); plain.mark(c) }
                }
            }
            println(id + "\t" + typist.text + "\t" + plain.text + "\t" + typist.fixes.joinToString(" ") + "\t" + typist.unhandled)
            n++
        }
        line = readLine()
    }
    System.err.println("replayed $n sentences")
}

fun main(args: Array<String>) {
    val root = File(args.getOrNull(0) ?: ".")
    val rest = args.drop(2)
    when (args.getOrNull(1)) {
        "geometry" -> geometry(root, rest)
        "probes" -> probes(root, rest)
        "replay" -> replay(root, rest)
        else -> { System.err.println("usage: Replay <repo root> geometry|probes|replay ..."); kotlin.system.exitProcess(2) }
    }
}
