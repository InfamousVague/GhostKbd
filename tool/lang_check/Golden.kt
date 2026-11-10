// The autocorrect golden's inputs and file format (tool/lang_check.sh). The golden (tool/lang_check/golden/*.tsv) was
// recorded from Autocorrect.kt as it stood before languages and layouts (53ae285), on QWERTY, for the six languages it
// had then: what correct(), suggest() and swipe() said for about 2,300 inputs a set. LangCheck.kt asks today's
// Autocorrect the same and lists every difference.
//
// Nothing here knows Autocorrect: it makes inputs from a word list, and reads and writes the file.

import java.io.File
import java.text.Normalizer
import kotlin.random.Random

/** One recorded answer: what was asked ([kind] "c" correct and suggest, "s" swipe), [input], and what came back. */
class GoldenRow(val kind: String, val input: String, val correct: String, val fix: String, val isWord: Boolean, val words: List<String>) {
    fun line() = listOf(kind, input, correct, fix, if (isWord) "1" else "0", words.joinToString("|")).joinToString("\t")

    companion object {
        fun parse(line: String): GoldenRow? {
            val p = line.split('\t')
            if (p.size < 6) return null
            return GoldenRow(p[0], p[1], p[2], p[3], p[4] == "1", p[5].split('|').filter { it.isNotEmpty() })
        }
    }
}

object Golden {
    /** The sets recorded: each language alone, and three together as a mixed message has them. */
    val SETS = listOf(listOf("en"), listOf("de"), listOf("es"), listOf("fr"), listOf("it"), listOf("pt"), listOf("en", "es", "fr"))

    fun file(dir: File, set: List<String>) = File(dir, set.joinToString("_") + ".tsv")

    /** QWERTY as it was hard-coded then: each letter's place in key widths across, and its row. */
    val QWERTY: Map<Char, Pair<Float, Int>> = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(c, i.toFloat() to 0) }
        "asdfghjkl".forEachIndexed { i, c -> put(c, i + 0.5f to 1) }
        "zxcvbnm".forEachIndexed { i, c -> put(c, i + 1.5f to 2) }
    }

    private val NEAR: Map<Char, List<Char>> = QWERTY.mapValues { (c, p) ->
        QWERTY.filter { (o, q) -> o != c && kotlin.math.abs(q.second - p.second) <= 1 && kotlin.math.abs(q.first - p.first) <= 1.01f }.keys.sorted()
    }

    /** A key's centre on a drawn QWERTY: keys 100 wide, rows 150 apart. */
    fun centre(c: Char): Pair<Float, Float>? = QWERTY[c]?.let { (x, row) -> (x * 100f + 50f) to (row * 150f + 75f) }

    const val KEY_WIDTH = 100f

    /** A finger's path for [word] on that QWERTY: straight lines through its letters' keys, a point every 12 units. */
    fun path(word: String): List<Pair<Float, Float>>? {
        val pts = ArrayList<Pair<Float, Float>>()
        var prev = ' '
        for (c in word) { if (c != prev) pts += centre(c) ?: return null; prev = c }
        if (pts.size < 2) return null
        val out = ArrayList<Pair<Float, Float>>()
        out += pts[0]
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            val d = kotlin.math.hypot(b.first - a.first, b.second - a.second)
            val steps = (d / 12f).toInt().coerceAtLeast(1)
            for (s in 1..steps) out += (a.first + (b.first - a.first) * s / steps) to (a.second + (b.second - a.second) * s / steps)
        }
        return out
    }

    fun unaccented(word: String): String =
        Normalizer.normalize(word.replace("ß", "ss"), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** The words of assets/dict/<lang>.tsv, commonest first. */
    fun words(dict: File, lang: String): List<String> =
        File(dict, "$lang.tsv").readLines().mapNotNull { l -> l.indexOf('\t').takeIf { it > 0 }?.let { l.substring(0, it) } }

    /**
     * What to ask for [set]: real words, each kind of slip made of them (a neighbouring key, two letters swapped, one
     * dropped, one doubled), accent-less spellings, the starts of words (for completions), capitals, a few by hand,
     * and words to swipe. The same every run: the picks come from a fixed seed.
     */
    fun inputs(dict: File, set: List<String>): List<Pair<String, String>> {
        val rnd = Random(20261109)
        val all = set.flatMap { words(dict, it).take(30_000) }.distinct()
        val out = LinkedHashSet<Pair<String, String>>()
        fun pick(n: Int, from: List<String> = all, ok: (String) -> Boolean = { true }): List<String> {
            val pool = from.filter(ok)
            if (pool.isEmpty()) return emptyList()
            return List(n) { pool[(rnd.nextDouble().let { it * it } * pool.size).toInt().coerceAtMost(pool.size - 1)] }.distinct()
        }
        fun ascii(w: String) = w.all { it in 'a'..'z' }
        for (w in pick(520)) out += "c" to w
        for (w in pick(520) { it.length >= 4 && ascii(it) }) {
            val i = rnd.nextInt(w.length)
            val n = NEAR[w[i]].orEmpty()
            if (n.isNotEmpty()) out += "c" to w.replaceRange(i, i + 1, n[rnd.nextInt(n.size)].toString())
        }
        for (w in pick(320) { unaccented(it) != it }) out += "c" to unaccented(w)
        for (w in pick(160) { it.length >= 4 }) { val i = rnd.nextInt(w.length - 1); out += "c" to (w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2)) }
        for (w in pick(160) { it.length >= 5 }) { val i = 1 + rnd.nextInt(w.length - 1); out += "c" to w.removeRange(i, i + 1) }
        for (w in pick(110) { it.length >= 4 }) { val i = rnd.nextInt(w.length); out += "c" to (w.substring(0, i) + w[i] + w.substring(i)) }
        for (w in pick(320) { it.length >= 4 }) out += "c" to w.take(2 + rnd.nextInt(3))
        for (w in pick(60) { it.length >= 3 }) out += "c" to w.replaceFirstChar { it.uppercaseChar() }
        for (w in pick(25) { it.length >= 3 }) out += "c" to w.uppercase()
        for (w in pick(40) { it.length >= 4 && ascii(it) }) {
            val i = rnd.nextInt(w.length)
            val n = NEAR[w[i]].orEmpty()
            if (n.isNotEmpty()) out += "c" to w.replaceRange(i, i + 1, n[rnd.nextInt(n.size)].toString()).replaceFirstChar { it.uppercaseChar() }
        }
        for (w in listOf("i", "im", "ive", "ill", "dont", "cant", "wont", "teh", "recieve", "keybkard", "npm", "async", "iOS", "McDonald", "cafe", "senor", "strasse", "straße", "grüße", "weiß", "x", "a", "lmfao", "ngl", "its", "it's", "l'homme", "qu'il", "perche", "citta", "voce", "nao", "garcon", "uber", "fur")) out += "c" to w
        for (w in pick(230) { it.length in 3..10 && unaccented(it).all { c -> c in 'a'..'z' } }) out += "s" to unaccented(w)
        return out.toList()
    }

    fun read(file: File): List<GoldenRow> = file.readLines().mapNotNull(GoldenRow::parse)

    fun write(file: File, rows: List<GoldenRow>) = file.writeText(rows.joinToString("\n", postfix = "\n") { it.line() })
}
