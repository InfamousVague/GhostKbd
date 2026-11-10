// The languages' self-check (tool/lang_check.sh): the layouts, the scripts' plain spellings, the Korean composer, the
// language switch's state, every word list against its keyboard, and autocorrect against the golden recorded before
// there were layouts. Plain Kotlin on the JVM: it compiles the keyboard's own Languages.kt, Layouts.kt, Hangul.kt and
// Autocorrect.kt, none of which needs Android (a two-line stand-in for PointF is beside this file).
//
// Usage: LangCheck <repo root>. Exits 1 on the first kind of failure it cannot explain, having printed each.

import android.graphics.PointF
import com.infamousvague.devkeyboard.Autocorrect
import com.infamousvague.devkeyboard.Board
import com.infamousvague.devkeyboard.Hangul
import com.infamousvague.devkeyboard.Key
import com.infamousvague.devkeyboard.KeyType
import com.infamousvague.devkeyboard.LanguageState
import com.infamousvague.devkeyboard.Languages
import com.infamousvague.devkeyboard.LayoutSpec
import com.infamousvague.devkeyboard.Layouts
import com.infamousvague.devkeyboard.Page
import com.infamousvague.devkeyboard.Script
import com.infamousvague.devkeyboard.Words
import java.io.File
import kotlin.math.abs

private var checks = 0
private var failures = 0

private fun check(ok: Boolean, what: () -> String) {
    checks++
    if (!ok) { failures++; println("FAIL  ${what()}") }
}

private fun <T> same(got: T, want: T, what: String) = check(got == want) { "$what: got $got, wanted $want" }

// --- the letters page as it was hard-coded before layouts (Layouts.kt at 53ae285), to compare QWERTY with ------------

private object Then {
    val accents = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"), "c" to listOf("ç", "ć", "č"), "e" to listOf("è", "é", "ê", "ë", "ē", "ę", "ė"),
        "i" to listOf("î", "ï", "í", "ī", "į", "ì"), "l" to listOf("ł"), "n" to listOf("ñ", "ń"), "o" to listOf("ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"),
        "s" to listOf("ß", "ś", "š"), "u" to listOf("û", "ü", "ù", "ú", "ū"), "y" to listOf("ÿ"), "z" to listOf("ž", "ź", "ż"),
    )
    val digitAlts = mapOf("1" to listOf("¹", "½", "⅓", "¼", "⅛"), "2" to listOf("²", "⅔"), "3" to listOf("³", "¾", "⅜"), "4" to listOf("⁴"), "0" to listOf("ⁿ", "∅"))
    val periodAlts = listOf("?", "!", ",", "'", "\"", ":", ";", "-", "/", "@", "#", "&", "(", ")", "%", "+")
    val digits = "1234567890".map { it.toString() }
    fun letter(c: String, hint: String?) = Key(KeyType.CHAR, c, hint = hint, alts = listOfNotNull(hint) + accents[c].orEmpty())

    /** Each row's keys and inset. */
    fun letters(numberRow: Boolean): List<Pair<List<Key>, Float>> {
        val top = "qwertyuiop".mapIndexed { i, c -> letter(c.toString(), if (numberRow) null else digits[i]) }
        val middle = "asdfghjkl".zip("@#\$_&-+()") { c, h -> letter(c.toString(), h.toString()) }
        val bottom = "zxcvbnm".zip("*\"':;!?") { c, h -> letter(c.toString(), h.toString()) }
        return listOfNotNull(
            if (numberRow) digits.map { Key(KeyType.CHAR, it, alts = digitAlts[it].orEmpty()) } to 0f else null,
            top to 0f,
            middle to 0.5f,
            (listOf(Key(KeyType.SHIFT, width = 1.5f)) + bottom + Key(KeyType.DELETE, width = 1.5f)) to 0f,
            listOf(
                Key(KeyType.MODE, label = "?123", width = 1.5f, page = Page.SYMBOLS),
                Key(KeyType.MODE, label = "{ }", page = Page.DEV, pill = false),
                Key(KeyType.SPACE, " ", width = 5f),
                Key(KeyType.CHAR, ".", alts = periodAlts),
                Key(KeyType.ENTER, width = 1.5f),
            ) to 0f,
        )
    }
}

private fun layouts(dict: File) {
    println("layouts")
    for (numberRow in listOf(false, true)) {
        val now = Layouts.letters(numberRow).map { it.keys to it.inset }
        same(now, Then.letters(numberRow), "QWERTY's page (number row $numberRow) is as it was")
        same(Layouts.letters(Layouts.QWERTY, numberRow, Layouts.extras(Layouts.QWERTY, listOf(Languages.of("en")!!, Languages.of("es")!!, Languages.of("fr")!!))).map { it.keys to it.inset }, Then.letters(numberRow), "QWERTY with en, es, fr on it is as it was")
    }
    same(Layouts.positions(Layouts.QWERTY).mapValues { it.value }, Golden.QWERTY, "QWERTY's positions")
    val thenNear = Golden.QWERTY.mapValues { (c, p) -> Golden.QWERTY.filter { (o, q) -> o != c && abs(q.second - p.second) <= 1 && abs(q.first - p.first) <= 1.01f }.keys }
    same(Layouts.neighbours(Layouts.QWERTY), thenNear, "QWERTY's neighbours")
    same(Layouts.QWERTY.alphabet, "abcdefghijklmnopqrstuvwxyz", "QWERTY's alphabet")

    for (spec in Layouts.SPECS) {
        same(spec.rows.size, 3, "${spec.id} has three letter rows")
        val keys = spec.rows.flatten().map { it.substringBefore(' ') }
        same(keys.size, keys.toSet().size, "${spec.id}'s keys are each there once")
        for (globe in listOf(false, true)) for (numberRow in listOf(false, true)) {
            val rows = Layouts.letters(spec, numberRow, globe = globe)
            val units = rows.maxOf { it.units }
            check(abs(rows.last().units - units) < 0.001f && (!numberRow || abs(rows.first().units - units) < 0.001f)) { "${spec.id}: the bottom row, and the number row, fill the board's $units key widths (${rows.map { it.units }})" }
            val third = rows[rows.size - 2]
            same(third.keys.any { it.type == KeyType.SHIFT }, spec.hasShift, "${spec.id} has a shift key only where it has capitals")
            check(third.keys.last().type == KeyType.DELETE && third.keys.last().width >= 1f) { "${spec.id}'s third row ends in a delete key at least a key wide" }
            same(rows.last().keys.any { it.type == KeyType.GLOBE }, globe, "${spec.id}'s globe key")
            val space = Layouts.withMic(rows).last().keys.first { it.type == KeyType.SPACE }
            check(space.width >= 3f) { "${spec.id}: the space bar keeps three key widths with the mic${if (globe) " and globe" else ""} (it has ${space.width})" }
        }
        check(spec.positions.keys.all { it in spec.alphabet }) { "${spec.id}'s alphabet has every letter key" }
        check(spec.neighbours.values.all { it.isNotEmpty() }) { "${spec.id}: every letter key has a neighbour" }
    }
    same(Layouts.spec("russian")!!.let { Layouts.letters(it, false)[2].keys.first().width }, 1f, "Russian's shift is one key wide")
    same(Layouts.spec("arabic")!!.let { Layouts.letters(it, false)[2].keys.none { k -> k.type == KeyType.SHIFT } }, true, "Arabic has no shift")
    check('ъ' in Layouts.spec("russian")!!.alphabet) { "Russian's alphabet has ъ, which sits behind a long-press" }

    // Every word list against the board its language types on: a letter must be a key, a long-press, or the accented
    // form of one (autocorrect and the suggestions supply those).
    for (language in Languages.ALL) {
        for (id in language.layouts) check(Layouts.spec(id) != null) { "${language.code}'s layout $id exists" }
        val stem = language.dict ?: continue
        val file = File(dict, "$stem.tsv")
        check(file.isFile) { "${language.code}'s word list is in assets/dict" }
        if (!file.isFile) continue
        val words = Golden.words(dict, stem)
        check(words.size >= 10_000) { "${language.code}'s list has ${words.size} words" }
        for (id in language.layouts) {
            val spec = Layouts.spec(id) ?: continue
            val extras = Layouts.extras(spec, listOf(language))
            val reach = HashSet<Char>()
            for (cell in spec.rows.flatten()) for (part in cell.split(' ')) reach += part.toList()
            for ((key, more) in extras) { reach += key.toList(); for (m in more) reach += m.toList() }
            reach += language.joiners.toList()
            if (spec.script == Script.LATIN) reach += '\''
            val missing = HashMap<Char, Int>()
            var unreachable = 0
            for (w in words) {
                val lost = w.filter { c -> c !in reach && language.script.plain(c.toString()).any { it !in reach } }
                if (lost.isNotEmpty()) { unreachable++; for (c in lost) missing[c] = (missing[c] ?: 0) + 1 }
            }
            check(unreachable <= words.size / 200) { "${language.code} on $id: $unreachable of ${words.size} words have a letter with no key (${missing.entries.sortedByDescending { it.value }.take(12).joinToString(" ") { "${it.key}×${it.value}" }})" }
            if (id == language.layouts.first() && unreachable > 0) println("      ${language.code} on $id: $unreachable of ${words.size} words need a letter it lacks (${missing.entries.sortedByDescending { it.value }.take(8).joinToString(" ") { "${it.key}×${it.value}" }})")
        }
    }
}

private fun scripts() {
    println("scripts")
    same(Script.LATIN.plain("straße"), "strasse", "ß")
    same(Script.LATIN.plain("señor café był smørrebrød"), "senor cafe byl smorrebrod", "Latin accents")
    same(Script.LATIN.plain("việt"), "viet", "Vietnamese tones")
    same(Script.LATIN.plain("hello"), "hello", "plain Latin is itself")
    same(Script.CYRILLIC.plain("йїќўё"), "йїќўе", "й, ї and ќ are never folded; ё is")
    same(Script.CYRILLIC.plain("ґанок"), "ганок", "ґ")
    same(Script.GREEK.plain("καλημέρα"), "καλημερα", "Greek tonos")
    same(Script.GREEK.plain("ΐϋ"), "ιυ", "Greek dialytika")
    same(Script.GREEK.plain("καλός"), "καλοσ", "final sigma")
    same(Script.HEBREW.plain("שָׁלוֹם"), "שלום", "Hebrew points")
    same(Script.ARABIC.plain("مَدْرَسَة"), "مدرسه", "Arabic marks and ة")
    same(Script.ARABIC.plain("إلى"), "الي", "hamza seats and alif maqsura")
    same(Script.PERSIAN.plain("می\u200Cخواهم"), "میخواهم", "Persian's half-space")
    same(Script.PERSIAN.plain("كتاب"), "کتاب", "Arabic kaf in Persian")
    same(Script.URDU.plain("ٹھیک"), "تھیک", "Urdu's retroflex")
    same(Script.HANGUL.plain("한글"), "한글", "Hangul is left alone")
}

private fun type(jamo: String): String {
    var state = Hangul.State()
    val out = StringBuilder()
    for (j in jamo) { val step = Hangul.add(state, j); out.append(step.committed); state = step.state }
    return out.toString() + state.render()
}

private fun hangul() {
    println("hangul")
    same(type("ㅎㅏㄴㄱㅡㄹ"), "한글", "한글")
    same(type("ㄱㅏㅂㅅㅣ"), "갑시", "a vowel takes the second half of a double final")
    same(type("ㄱㅏㅂㅅㅇㅣ"), "값이", "값이")
    same(type("ㄷㅏㄹㄱㅣ"), "달기", "닭 + ㅣ")
    same(type("ㄷㅏㄹㄱ"), "닭", "닭")
    same(type("ㅇㅓㅂㅅㅇㅓ"), "없어", "없어")
    same(type("ㅇㅡㅣㅅㅏ"), "의사", "의사")
    same(type("ㅇㅗㅏ"), "와", "와")
    same(type("ㄲㅏ"), "까", "까")
    same(type("ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ"), "안녕하세요", "안녕하세요")
    same(type("ㅃㅏㄹㄹㅣ"), "빨리", "빨리")
    same(type("ㅇㅣㅆㄷㅏ"), "있다", "있다")
    same(type("ㅏㅏ"), "ㅏㅏ", "lone vowels stand")
    same(type("ㄱㄱ"), "ㄱㄱ", "lone consonants stand")
    same(type("ㅇㅏㄸ"), "아ㄸ", "ㄸ cannot end a block")
    // Backspace, a letter at a time.
    var s = Hangul.State()
    for (j in "ㄷㅏㄹㄱ") s = Hangul.add(s, j).state
    val seen = ArrayList<String>()
    while (true) { seen += s.render(); s = Hangul.backspace(s) ?: break }
    same(seen, listOf("닭", "달", "다", "ㄷ", ""), "backspace through 닭")
    s = Hangul.State()
    for (j in "ㅇㅗㅏ") s = Hangul.add(s, j).state
    same(Hangul.backspace(s)!!.render(), "오", "backspace on 와")
    same(Hangul.backspace(Hangul.State()), null, "backspace on nothing is the ordinary one")
    check(Hangul.isJamo('ㅎ') && Hangul.isJamo('ㅒ') && !Hangul.isJamo('a') && !Hangul.isJamo('한')) { "isJamo" }
}

private fun languages() {
    println("languages")
    same(Languages.ALL.map { it.code }.toSet().size, Languages.ALL.size, "every language is listed once")
    same(Languages.ALL.count { it.code == "sh" }, 1, "sh once")
    same(Languages.ALL.count { it.dict != null }, 37, "37 word lists")
    same(Languages.clean(listOf("en", "xx", "ru", "en")), listOf("en", "ru"), "unknown and repeated codes go")
    same(Languages.clean(listOf("en", "es", "fr", "de", "it", "pt", "nl")).size, Languages.MAX, "at most six")
    same(Languages.clean(emptyList()), listOf("en"), "never none")
    same(Languages.pinned(listOf("en", "es", "fr"), emptyMap()), mapOf("es" to "qwerty", "fr" to "qwerty"), "Spanish and French that were on stay on QWERTY")
    same(Languages.pinned(listOf("en", "it"), emptyMap()), emptyMap(), "nothing to pin")
    same(Languages.overrides(Languages.overrides("fr:qwerty,de:qwertz")), "fr:qwerty,de:qwertz", "overrides read and written")

    // A keyboard set up before layouts: one stop, no name on the space bar, nothing to switch.
    val before = LanguageState.of(listOf("en", "es", "fr"), Languages.pinned(listOf("en", "es", "fr"), emptyMap()), "")
    same(before.stops.size, 1, "en, es, fr pinned is one stop")
    same(before.spaceLabel, null, "one stop has no name on the space bar")
    same(before.spec.id, "qwerty", "and it is QWERTY")
    same(before.dictionaries, listOf("en", "es", "fr"), "with all three lists")
    check(before.board.english) { "English is loaded" }

    val fresh = LanguageState.of(listOf("en", "fr"), emptyMap(), "azerty")
    same(fresh.stops.map { it.id }, listOf("qwerty", "azerty"), "French turned on now gets AZERTY")
    same(fresh.spec.id, "azerty", "the layout setting picks the stop")
    same(fresh.dictionaries, listOf("en", "fr"), "Latin lists mix on either board")
    same(fresh.spaceLabel, "Français", "the space bar names the stop")
    same(fresh.next, "qwerty", "the globe key goes round")
    same(LanguageState.of(listOf("en", "fr"), mapOf("fr" to "qwerty"), "azerty").spec.id, "qwerty", "a stale layout falls back to the first stop")
    same(LanguageState.of(listOf("en", "fr"), mapOf("fr" to "russian"), "").stops.map { it.id }, listOf("qwerty", "azerty"), "a layout a language may not use is ignored")

    val mixed = LanguageState.of(listOf("en", "ru", "uk"), emptyMap(), "russian")
    same(mixed.stops.map { it.id }, listOf("qwerty", "russian", "ukrainian"), "three stops")
    same(mixed.dictionaries, listOf("ru", "uk"), "Cyrillic lists on a Cyrillic board")
    check(!mixed.board.english) { "no English on the Russian board" }
    same(LanguageState.of(listOf("en", "ru"), emptyMap(), "russian", latinField = true).spec.id, "qwerty", "a password field gets the Latin stop")

    val arabic = LanguageState.of(listOf("ar"), emptyMap(), "")
    same(arabic.stops.map { it.label }, listOf("العربية", "ABC"), "Arabic alone gets an ABC stop")
    same(LanguageState.of(listOf("ar"), emptyMap(), "qwerty").dictionaries, emptyList(), "ABC has no word list")
    same(LanguageState.of(listOf("ar"), emptyMap(), "", latinField = true).spec.id, "qwerty", "and a password field goes to it")
    same(LanguageState.of(listOf("fa"), emptyMap(), "").joiners, "\u200C", "Persian's joiner is the half-space")
    same(LanguageState.of(listOf("en", "ca"), emptyMap(), "catalan").joiners.toSet(), setOf('\'', '·'), "Catalan's are the apostrophe and the middle dot")
    same(LanguageState.of(listOf("ko"), emptyMap(), "").dictionaries, emptyList(), "Korean has no word list")
    same(LanguageState.of(listOf("sv", "fi"), emptyMap(), "").stops.single().label, "Svenska · Suomi", "Swedish and Finnish share a board")
    same(LanguageState.of(listOf("en", "pl"), emptyMap(), "").extras["a"]!!.first(), "ą", "Polish's own letters lead the long-press")
    same(LanguageState.of(listOf("es"), emptyMap(), "").extras["n"], listOf("ń"), "ñ is a key on the Spanish board, so n's long-press drops it")
}

// --- autocorrect ------------------------------------------------------------------------------------------------------

private fun autocorrect(repo: File, codes: List<String>, layout: String): Autocorrect {
    val ac = Autocorrect { name -> File(repo, "android/app/src/main/assets/$name").takeIf { it.isFile }?.inputStream() }
    val state = LanguageState.of(codes, codes.associateWith { layout }, layout)
    ac.setLanguages(state.dictionaries, state.board)
    ac.setLearned(emptySet())
    ac.setSlang(true)
    while (!ac.settled) Thread.sleep(20)
    Thread.sleep(250) // the slang list is read on its own thread
    return ac
}

/** "I" for "i", as English alone has it: the first of the listed differences. */
private fun english(set: List<String>) = "en" in set

private val NEW_PLAIN = setOf('ł', 'ø', 'đ', 'ı')

private fun golden(repo: File) {
    println("autocorrect, against what it said before layouts (QWERTY)")
    val dir = File(repo, "tool/lang_check/golden")
    val keys = Golden.QWERTY.keys.associateWith { c -> Golden.centre(c)!!.let { PointF(it.first, it.second) } }
    for (set in Golden.SETS) {
        val ac = autocorrect(repo, set, "qwerty")
        val rows = Golden.read(Golden.file(dir, set))
        check(rows.size > 1500) { "${set.joinToString(",")}: the golden has ${rows.size} rows" }
        var same = 0
        val kinds = LinkedHashMap<String, Int>()
        val examples = LinkedHashMap<String, ArrayList<String>>()
        fun differs(kind: String, example: String) {
            kinds[kind] = (kinds[kind] ?: 0) + 1
            examples.getOrPut(kind) { ArrayList() }.let { if (it.size < 4) it += example }
        }
        for (row in rows) {
            val lower = row.input.lowercase()
            if (row.kind == "s") {
                val got = ac.swipe(Golden.path(row.input)!!.map { PointF(it.first, it.second) }, keys, Golden.KEY_WIDTH)
                when {
                    got == row.words -> same++
                    !english(set) && got.map { it.lowercase() } == row.words.map { it.lowercase() } -> differs("\"i\" is a capital only in English", "swipe ${row.input}: ${row.words} → $got")
                    (got + row.words).any { w -> w.any { it in NEW_PLAIN } } -> differs("ł ø đ ı now count as l o d i", "swipe ${row.input}: ${row.words} → $got")
                    else -> { differs("UNEXPLAINED", "swipe ${row.input}: ${row.words} → $got"); failures++ }
                }
                continue
            }
            val s = ac.suggest(row.input)
            val correct = ac.correct(row.input) ?: ""
            val fix = s.fix ?: ""
            if (correct == row.correct && fix == row.fix && s.typedIsWord == row.isWord && s.words == row.words) { same++; continue }
            val line = "${row.input}: ${row.correct.ifEmpty { "–" }} ${row.words} → ${correct.ifEmpty { "–" }} ${s.words}"
            val plainTyped = Script.LATIN.plain(lower)
            val added = s.words.filter { it !in row.words }
            val gone = row.words.filter { it !in s.words }
            when {
                'ß' in lower -> differs("a word with ß that is a word with ss is left alone", line)
                !english(set) && (lower == "i" || lower.startsWith("i'") || (row.words + listOf(row.correct, row.fix)).any { it == "I" || it.startsWith("I'") }) ->
                    differs("\"i\" is a capital only in English", line)
                // The same answer, with accented completions of the plain prefix joining the row (and pushing its tail out).
                correct == row.correct && fix == row.fix && s.typedIsWord == row.isWord && added.isNotEmpty() &&
                    added.all { w -> Script.LATIN.plain(w.lowercase()).startsWith(plainTyped) && !w.lowercase().startsWith(lower) } &&
                    (gone.isEmpty() || s.words.size == 5) && s.words.filter { it in row.words } == row.words.filter { it in s.words } ->
                    differs("accented completions of a plain prefix (caf → café)", line)
                (row.words + s.words + listOf(row.correct, correct)).any { w -> w.any { it in NEW_PLAIN } } -> differs("ł ø đ ı now count as l o d i", line)
                else -> { differs("UNEXPLAINED", line); failures++ }
            }
        }
        checks += rows.size
        println("  ${set.joinToString(",").padEnd(9)} ${rows.size} asked, $same the same" + kinds.entries.joinToString("") { "; ${it.value} ${it.key}" })
        for ((kind, list) in examples) for (e in list) println("      ${if (kind == "UNEXPLAINED") "FAIL " else ""}$e")
    }
}

/** A finger's path over [word] on [spec]'s keys, as Golden.path draws QWERTY's. */
private fun pathOn(spec: LayoutSpec, word: String): Pair<List<PointF>, Map<Char, PointF>> {
    val keys = spec.positions.mapValues { (_, p) -> PointF(p.first * 100f + 50f, p.second * 150f + 75f) }
    val pts = ArrayList<PointF>()
    var prev: PointF? = null
    for (c in word) {
        val k = keys[c] ?: keys[spec.script.plain(c.toString()).first()] ?: continue
        val from = prev
        if (from == null) pts += k else if (from !== k) for (s in 1..12) pts += PointF(from.x + (k.x - from.x) * s / 12f, from.y + (k.y - from.y) * s / 12f)
        prev = k
    }
    return pts to keys
}

private fun newLanguages(repo: File) {
    println("autocorrect, the new languages on their own boards")
    fun on(code: String, layout: String = Languages.of(code)!!.layouts.first()) = autocorrect(repo, listOf(code), layout)
    fun fixes(ac: Autocorrect, typed: String, want: String, what: String) = same(ac.correct(typed), want, "$what: $typed")
    fun leaves(ac: Autocorrect, typed: String, what: String) = same(ac.correct(typed), null, "$what: $typed is left alone")
    fun offers(ac: Autocorrect, typed: String, want: String, what: String) = check(want in ac.suggest(typed).words) { "$what: $typed offers $want (it offers ${ac.suggest(typed).words})" }
    fun swipes(ac: Autocorrect, spec: String, word: String) {
        val (path, keys) = pathOn(Layouts.spec(spec)!!, word)
        val got = ac.swipe(path, keys, 100f)
        check(got.firstOrNull() == word) { "swiping $word on $spec gives it first (it gives $got)" }
    }

    val ru = on("ru")
    fixes(ru, "привт", "привет", "Russian, a letter missed")
    fixes(ru, "пртвет", "привет", "Russian, a neighbouring key (т beside и)")
    fixes(ru, "Спасибл", "Спасибо", "Russian, a capital kept")
    leaves(ru, "привет", "Russian")
    offers(ru, "здравс", "здравствуйте", "Russian")
    swipes(ru, "russian", "привет")
    same(ru.correct("hello"), null, "a Latin word on the Russian board is not touched")

    val uk = on("uk")
    leaves(uk, "їх", "Ukrainian ї is its own letter")
    offers(uk, "дяку", "дякую", "Ukrainian")

    val el = on("el")
    fixes(el, "καλημερα", "καλημέρα", "Greek, typed without its accent")
    fixes(el, "ευχαριστω", "ευχαριστώ", "Greek")
    fixes(el, "καλοσ", "καλός", "Greek, a final sigma typed as σ")
    offers(el, "καλημ", "καλημέρα", "Greek, a plain prefix")
    swipes(el, "greek", "και")

    val he = on("he")
    leaves(he, "שלום", "Hebrew")
    offers(he, "תוד", "תודה", "Hebrew")

    val ar = on("ar")
    leaves(ar, "مرحبا", "Arabic")
    leaves(ar, "الى", "Arabic written without its hamza is a style, and is left")
    fixes(ar, "وزاره", "وزارة", "Arabic, ة typed as ه")
    offers(ar, "شكر", "شكرا", "Arabic")
    swipes(ar, "arabic", "من")

    val fa = on("fa")
    fixes(fa, "شدهاند", "شده\u200Cاند", "Persian gains its half-space")
    leaves(fa, "میکنم", "Persian written joined, where that is as common, is left")
    leaves(fa, "سلام", "Persian")

    val ur = on("ur")
    leaves(ur, "شکریہ", "Urdu")

    val tr = on("tr")
    leaves(tr, "ışık", "Turkish dotless ı")
    // The Turkish board has ç as a key: "icin" typed there is taken as typed. On plain QWERTY it is mended.
    leaves(tr, "icin", "Turkish, on the board that has ç")
    fixes(on("tr", "qwerty"), "icin", "için", "Turkish on QWERTY")
    same(tr.suggest("İst").words.firstOrNull()?.first(), 'İ', "Turkish capitals: a completion of İst starts with İ")
    swipes(tr, "turkish", "için")

    val pl = on("pl")
    fixes(pl, "byl", "był", "Polish ł from l")
    fixes(pl, "dziekuje", "dziękuję", "Polish")
    fixes(pl, "moze", "może", "Polish, though moze is in the list as people type it")
    same(pl.correct("i"), null, "\"i\" is a word in Polish, not a capital")

    val vi = on("vi")
    offers(vi, "viet", "việt", "Vietnamese, a plain prefix")
    fixes(vi, "khong", "không", "Vietnamese tones from plain keys")
    // With English beside it the first six keep their stricter rule: a common plain word is never changed.
    val enPl = autocorrect(repo, listOf("en", "pl"), "qwerty")
    leaves(enPl, "resume", "English beside Polish")
    fixes(enPl, "moze", "może", "Polish beside English")

    val de = on("de")
    leaves(de, "straße", "German ß")
    check(de.suggest("straße").typedIsWord) { "straße counts as a word" }
    swipes(de, "qwertz", "zeit")

    val fr = on("fr")
    offers(fr, "caf", "café", "French, a plain prefix")
    swipes(fr, "azerty", "bonjour")

    val sr = on("sr")
    leaves(sr, "хвала", "Serbian Cyrillic")
    val sh = on("sh")
    leaves(sh, "hvala", "Serbo-Croatian Latin")

    // Every list loads on its own board and knows its own commonest long word.
    for (language in Languages.ALL) {
        val stem = language.dict ?: continue
        val ac = on(language.code)
        check(ac.active) { "${language.code}: its list loads" }
        val word = Golden.words(File(repo, "android/app/src/main/assets/dict"), stem).first { it.length >= 5 }
        check(ac.suggest(word).typedIsWord) { "${language.code}: $word is a word" }
        same(ac.correct(word), null, "${language.code}: $word is left alone")
    }

    // No word list: nothing is corrected, suggested or swiped, and nothing throws.
    val ko = on("ko")
    check(!ko.active) { "Korean has no word list, so autocorrect is not active" }
    same(ko.correct("한글"), null, "Korean is never corrected")
    same(ko.suggest("한").words, emptyList(), "nor suggested")

    // Changing only the board keeps the words: QWERTY to AZERTY with the same two lists is at once.
    val both = autocorrect(repo, listOf("en", "fr"), "qwerty")
    val azerty = LanguageState.of(listOf("en", "fr"), mapOf("en" to "qwerty", "fr" to "azerty"), "azerty")
    both.setLanguages(azerty.dictionaries, azerty.board)
    check(both.settled && both.active) { "a board change with the same lists does not reload them" }
    // On AZERTY q sits beside s, so "qalut" is one slip from "salut"; on QWERTY q is far from s.
    same(both.correct("qalut"), "salut", "the neighbours are AZERTY's now")
}

/** What a review of the languages found, held here so it stays mended. */
private fun reviewed(repo: File) {
    println("what the review found: words after emoji, the globe key's round, \"i\", plain spellings, endings, digits")
    fun leaves(ac: Autocorrect, typed: String, what: String) = same(ac.correct(typed), null, "$what: $typed is left alone")
    fun offers(ac: Autocorrect, typed: String, want: String, what: String) = check(want in ac.suggest(typed).words) { "$what: $typed offers $want (it offers ${ac.suggest(typed).words})" }

    // A word typed straight after an emoji is the word alone: the emoji's variation selector is no part of it.
    val smile = "\u263A\uFE0F"
    check(!Words.isWordChar('\uFE0F', "'")) { "an emoji's variation selector is not a word character" }
    check(!Words.isWordChar('\u20E3', "'")) { "nor is the keycap's enclosing mark" }
    check(Words.isWordChar('\u0301', "'") && Words.isWordChar('\u064E', "")) { "a combining accent and an Arabic vowel mark are" }
    fun last(text: String, joiners: String = "'") = text.substring(Words.lastStart(text, joiners))
    same(last(":heart${smile}ice"), "ice", "the word after an emoji")
    same(last("1\uFE0F\u20E3st"), "st", "the word after a keycap")
    same(last("say 'hello"), "hello", "a word does not start at its quote")
    same(last("don't"), "don't", "an apostrophe inside a word stays")
    same(last("cafe\u0301"), "cafe\u0301", "a combining accent rides on its letter")
    same(last("hello "), "", "no word after a space")
    same(last("\u0645\u064E\u0631\u062D\u0628\u0627", ""), "\u0645\u064E\u0631\u062D\u0628\u0627", "Arabic with its vowel marks is one word")

    // Going round three scripts with the globe key reads each list once: the third stop and every one after is at once.
    val round = listOf("en", "ru", "he")
    val ac = Autocorrect { name -> File(repo, "android/app/src/main/assets/$name").takeIf { it.isFile }?.inputStream() }
    var state = LanguageState.of(round, emptyMap(), "")
    same(state.lexicons, setOf("LATIN:en", "CYRILLIC:ru", "HEBREW:he"), "one lexicon a script")
    var loads = 0
    ac.onLoaded = { loads++ }
    repeat(9) { turn ->
        ac.setLanguages(state.dictionaries, state.board, state.lexicons)
        if (turn >= 3) check(ac.settled && ac.active) { "stop ${state.spec.id} on turn $turn is in memory" }
        while (!ac.settled) Thread.sleep(10)
        check(ac.active) { "${state.spec.id} has words" }
        state = LanguageState.of(round, emptyMap(), state.next)
    }
    Thread.sleep(50)
    same(loads, 3, "nine turns of the globe key read three lists")
    // A language turned off lets its list go; turning it on again reads it.
    val fewer = LanguageState.of(listOf("en", "ru"), emptyMap(), "")
    ac.setLanguages(fewer.dictionaries, fewer.board, fewer.lexicons)
    val back = LanguageState.of(round, emptyMap(), "hebrew")
    ac.setLanguages(back.dictionaries, back.board, back.lexicons)
    check(!ac.settled) { "Hebrew was let go while it was off, and is read again" }
    while (!ac.settled) Thread.sleep(10)
    // Many changes in a row are read one at a time, and only the last is kept wanted.
    for (codes in listOf(listOf("en"), listOf("en", "pl"), listOf("en", "pl", "cs"), listOf("en", "pl", "cs", "de"))) {
        val s = LanguageState.of(codes, emptyMap(), "")
        ac.setLanguages(s.dictionaries, s.board, s.lexicons)
    }
    while (!ac.settled) Thread.sleep(10)
    check(ac.active && ac.isWord("dziękuję") && ac.isWord("danke")) { "the last of four quick changes is the one in use" }

    // A lone "i" is English's capital only where no other language on has "i" as a word.
    fun both(vararg codes: String, layout: String = "qwerty") = autocorrect(repo, codes.toList(), layout)
    same(both("en", "fr").correct("i"), "I", "English beside French: i is I")
    for (other in listOf("pl", "sv", "da", "nb", "ca", "cs", "sh", "it", "sk", "ro")) {
        val pair = autocorrect(repo, listOf("en", other), Languages.of(other)!!.layouts.first())
        same(pair.correct("i"), null, "English beside $other, where i is a word: i is left")
        offers(pair, "i", "I", "English beside $other")
    }
    val enTr = autocorrect(repo, listOf("en", "tr"), "turkish")
    same(enTr.correct("i"), null, "on the Turkish board I is the capital of ı, so i is left")

    // A listed plain spelling is the word that was meant on a board with the accented letter as a key.
    val fi = autocorrect(repo, listOf("fi"), "swedish")
    for (w in listOf("silta", "riita", "lammin", "vaarin")) leaves(fi, w, "Finnish, where ä is a key")
    val enFi = autocorrect(repo, listOf("en", "fi"), "swedish")
    leaves(enFi, "silta", "Finnish beside English")
    val sv = autocorrect(repo, listOf("sv"), "swedish")
    for (w in listOf("far", "kor", "hall", "lat", "bada")) leaves(sv, w, "Swedish, where å ä ö are keys")
    offers(sv, "far", "får", "Swedish still offers the accented word")
    val da = autocorrect(repo, listOf("en", "da"), "danish")
    for (w in listOf("maske", "bade")) leaves(da, w, "Danish, where å is a key")
    val tr = autocorrect(repo, listOf("tr"), "turkish")
    for (w in listOf("yas", "adi", "hayati")) leaves(tr, w, "Turkish, where ş and ı are keys")
    // Catalan's accents tell words apart, as Spanish's do: its common plain spellings are words.
    val ca = autocorrect(repo, listOf("ca"), "catalan")
    for (w in listOf("mes", "son", "mon")) leaves(ca, w, "Catalan")
    // Where the board has no such key the plain spelling still gives way: that is the typist it is for.
    same(autocorrect(repo, listOf("sv"), "qwerty").correct("far"), "får", "Swedish on plain QWERTY: far")
    same(autocorrect(repo, listOf("pl"), "qwerty").correct("sie"), "się", "Polish on QWERTY: sie")

    // A right word the list lacks is not turned into its neighbour: an ending taken off, a prefix, two letters at once.
    leaves(fi, "kissani", "Finnish (my cat)")
    leaves(fi, "taloni", "Finnish (my house)")
    offers(fi, "kissani", "kissan", "Finnish still offers the near word")
    leaves(tr, "kedim", "Turkish (my cat)")
    leaves(autocorrect(repo, listOf("hu"), "qwertz"), "házamban", "Hungarian (in my house)")
    val ar = autocorrect(repo, listOf("ar"), "arabic")
    leaves(ar, "وبيتي", "Arabic (and my house)")
    leaves(ar, "لأصدقائي", "Arabic (for my friends)")
    leaves(autocorrect(repo, listOf("pl"), "qwerty"), "kotami", "Polish (with cats)")

    // Serbian's Cyrillic list has no English spelled in Cyrillic letters; Urdu's has no two words run together.
    val sr = autocorrect(repo, listOf("en", "sr"), "serbian")
    for (w in listOf("тхе", "оф", "анд", "хттп", "мицхаел", "гоогле", "фацебоок", "цхицаго", "офф")) check(!sr.isWord(w)) { "Serbian: $w is not a word" }
    for (w in listOf("море", "мост", "со", "хвала", "зоо", "момент")) check(sr.isWord(w)) { "Serbian: $w is a word" }
    same(sr.correct("тхр"), null, "Serbian: тхр is not mended to тхе")
    check(sr.suggest("тх").words.none { it.startsWith("тхе") || it.startsWith("тхо") }) { "Serbian: тх offers no English (${sr.suggest("тх").words})" }
    val ur = autocorrect(repo, listOf("en", "ur"), "urdu")
    same(ur.suggest("ہے").words.filter { it.length > 2 && it.startsWith("ہے") }, emptyList(), "Urdu: ہے offers no two words run together")
    check(ur.isWord("بےشک") && ur.isWord("شکریہ")) { "Urdu keeps its بے words" }

    // Turkish's capitals follow Turkish wherever it is typed alone, and İ is within reach where it shares a board.
    same(LanguageState.of(listOf("tr"), mapOf("tr" to "qwerty"), "").locale.language, "tr", "Turkish alone on QWERTY has Turkish capitals")
    same(LanguageState.of(listOf("en", "tr"), mapOf("tr" to "qwerty"), "").locale.language, "", "Turkish beside English on QWERTY keeps English's I")
    check("İ" in LanguageState.of(listOf("en", "tr"), mapOf("tr" to "qwerty"), "").extras["i"].orEmpty()) { "and İ is on i's long-press there" }
    same(LanguageState.of(listOf("en", "tr"), emptyMap(), "turkish").locale.language, "tr", "the Turkish board has Turkish capitals")
    same(autocorrect(repo, listOf("tr"), "qwerty").suggest("İst").words.firstOrNull()?.first(), 'İ', "Turkish on QWERTY: a completion of İst starts with İ")

    // Arabic, Persian and Urdu have their own digits a long-press away, and their own ؟ ، ؛ on ?123.
    for ((id, three) in listOf("arabic" to "٣", "persian" to "۳", "urdu" to "۳")) {
        val spec = Layouts.spec(id)!!
        val symbols = Layouts.symbols(spec).flatMap { it.keys }
        same(symbols.first { it.text == "3" }.alts.first(), three, "$id: 3's long-press leads with its own digit")
        for ((own, latin) in listOf("؟" to "?", "،" to ",", "؛" to ";")) same(symbols.firstOrNull { it.text == own }?.alts?.first(), latin, "$id: $own is on ?123, with $latin behind it")
        check(Layouts.more(spec).flatMap { it.keys }.first { it.text == "%" }.alts.first() == "٪") { "$id: ٪ leads %'s long-press" }
        same(Layouts.letters(spec, numberRow = false)[0].keys[2].alts.take(2), listOf("3", three), "$id: the top row's third key has 3 then $three")
        same(Layouts.letters(spec, numberRow = true)[0].keys[2].alts.first(), three, "$id: and so has the number row's 3")
    }
    same(Layouts.symbols().flatMap { it.keys }.map { it.text to it.alts }, Layouts.symbols(Layouts.QWERTY).flatMap { it.keys }.map { it.text to it.alts }, "?123 with no layout given is QWERTY's")
    check(Layouts.symbols().flatMap { it.keys }.none { k -> k.text.any { it in "؟،؛" } || k.alts.any { a -> a.any { it in "٠١٢٣٤٥٦٧٨٩٪" } } }) { "a Latin board's ?123 has nothing Arabic on it" }
}

fun main(args: Array<String>) {
    val repo = File(args.firstOrNull() ?: ".")
    val dict = File(repo, "android/app/src/main/assets/dict")
    layouts(dict)
    scripts()
    hangul()
    languages()
    golden(repo)
    newLanguages(repo)
    reviewed(repo)
    println(if (failures == 0) "lang_check: $checks checks passed" else "lang_check: $failures of $checks checks FAILED")
    if (failures > 0) kotlin.system.exitProcess(1)
}
