package com.infamousvague.devkeyboard

import java.text.Normalizer
import java.util.Locale

/**
 * A writing system the keyboard can type. [cased] scripts have capitals (shift, auto-capitals, "Word" and "WORD");
 * [rtl] ones are written right to left, which is the text field's job to draw: the keys only type, and the space
 * bar's cursor slide is turned round so the caret still follows the finger.
 */
enum class Script(val cased: Boolean, val rtl: Boolean) {
    LATIN(true, false),
    CYRILLIC(true, false),
    GREEK(true, false),
    HEBREW(false, true),
    ARABIC(false, true),
    PERSIAN(false, true),
    URDU(false, true),
    HANGUL(false, false);

    /**
     * [word] as its plainest spelling, the one a hurried thumb types: accents and marks gone, and the letters a board
     * hides behind a long-press turned into the key they sit on. Autocorrect looks a typed word up under this to find
     * the proper one (cafe → café, καλημερα → καλημέρα, میخواهم → می‌خواهم), and swipe typing glides over the plain keys.
     *
     * Letters with a key of their own in their script are never folded: й is not и, ї is not і.
     */
    fun plain(word: String): String = when (this) {
        // é → e, ñ → n, ß → ss, and the four Latin letters Unicode will not take apart: ł, ø, đ and dotless ı.
        LATIN -> if (word.all { it.code < 0x80 }) word else marks(swap(word.replace("ß", "ss"), LATIN_MAP))
        // No Unicode folding here: it would turn й into и and ї into і, which have keys of their own.
        CYRILLIC -> swap(word, CYRILLIC_MAP)
        // Tonos and dialytika go, and the final sigma is the sigma it is.
        GREEK -> swap(marks(word), GREEK_MAP)
        // Vowel points and cantillation.
        HEBREW -> drop(word) { it in '֑'..'ׇ' }
        ARABIC -> swap(drop(word, ::arabicMark), ARABIC_MAP)
        PERSIAN -> swap(drop(word) { arabicMark(it) || it == '\u200C' }, PERSIAN_MAP)
        URDU -> swap(drop(word) { arabicMark(it) || it == '\u200C' }, URDU_MAP)
        HANGUL -> word
    }

    private companion object {
        val LATIN_MAP = mapOf('ł' to 'l', 'ø' to 'o', 'đ' to 'd', 'ı' to 'i')
        val CYRILLIC_MAP = mapOf('ё' to 'е', 'ґ' to 'г', 'ѐ' to 'е', 'ѝ' to 'и')
        val GREEK_MAP = mapOf('ς' to 'σ')
        val ARABIC_MAP = mapOf('أ' to 'ا', 'إ' to 'ا', 'آ' to 'ا', 'ٱ' to 'ا', 'ؤ' to 'و', 'ئ' to 'ي', 'ى' to 'ي', 'ة' to 'ه')
        val PERSIAN_MAP = mapOf('آ' to 'ا', 'أ' to 'ا', 'إ' to 'ا', 'ؤ' to 'و', 'ئ' to 'ی', 'ي' to 'ی', 'ك' to 'ک')
        val URDU_MAP = mapOf('آ' to 'ا', 'ؤ' to 'و', 'ئ' to 'ی', 'ٹ' to 'ت', 'ڈ' to 'د', 'ڑ' to 'ر', 'ں' to 'ن')

        /** Arabic's short vowels and other marks (U+064B to U+065F, the dagger alif) and the stretching tatweel. */
        fun arabicMark(c: Char) = c in 'ً'..'ٟ' || c == 'ٰ' || c == 'ـ'

        /** [word] with every combining mark gone, by way of Unicode's decomposition. */
        fun marks(word: String): String {
            val nfd = Normalizer.normalize(word, Normalizer.Form.NFD)
            return drop(nfd) { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
        }

        inline fun drop(word: String, gone: (Char) -> Boolean): String {
            if (word.none(gone)) return word
            val out = StringBuilder(word.length)
            for (c in word) if (!gone(c)) out.append(c)
            return out.toString()
        }

        fun swap(word: String, map: Map<Char, Char>): String {
            if (word.none { it in map }) return word
            val out = StringBuilder(word.length)
            for (c in word) out.append(map[c] ?: c)
            return out.toString()
        }
    }
}

/**
 * A language the keyboard has: its [code] (the word list's file name), its [name] as its speakers write it and in
 * [english], its [script], the [layouts] it may use (the first is the one it gets when turned on), the word list's
 * stem in assets/dict ([dict]; null where there is none, and then nothing is corrected, suggested or swiped), the
 * characters besides letters that may sit inside its words ([joiners]: an apostrophe, a half-space), the letters of
 * its own that go first on a key's long-press when it shares a board ([own]), and a [note] Settings shows under it.
 * [short] is what the space bar says when the full name will not fit. [locale] is whose capitals and lower case its
 * words have: the root locale for all but Turkish, where i and İ, ı and I are pairs.
 */
class Language(
    val code: String,
    val name: String,
    val english: String,
    val script: Script,
    val layouts: List<String>,
    val dict: String? = code,
    val joiners: String = "",
    val own: Map<String, List<String>> = emptyMap(),
    val note: String? = null,
    val short: String = code.uppercase(Locale.ROOT),
    val locale: Locale = Locale.ROOT,
)

/** A language Settings lists as not built yet, with why. */
class Unavailable(val name: String, val english: String, val why: String)

/** Every language Ghost Keyboard types, and the ones it says it does not yet. */
object Languages {
    /** How many languages can be on at once: each one's word list stays in memory once its script has been typed. */
    const val MAX = 6

    private const val SHARED = "qwerty qwertz azerty"

    /** A Latin-script language: its own layout first where it has one, then the three shared boards. */
    private fun latin(
        code: String, name: String, english: String, first: String = "qwerty", own: String = "", note: String? = null, joiners: String = "'",
        short: String = code.uppercase(Locale.ROOT), locale: Locale = Locale.ROOT,
    ) = Language(code, name, english, Script.LATIN, ("$first $SHARED").split(' ').distinct(), joiners = joiners, own = own(own), note = note, short = short, locale = locale)

    /** "a ą, c ć" as a key's own letters: the key, then what joins its long-press. */
    private fun own(spec: String): Map<String, List<String>> =
        if (spec.isEmpty()) emptyMap() else spec.split(", ").associate { part -> part.split(' ').let { it[0] to it.drop(1) } }

    private const val NO_CAPITALS = "No capitals. The text field sets the direction."

    val ALL: List<Language> = listOf(
        latin("en", "English", "English"),
        latin("es", "Español", "Spanish", "spanish"),
        latin("fr", "Français", "French", "azerty"),
        latin("de", "Deutsch", "German", "qwertz", note = "ß is on s; words are listed with ss."),
        latin("it", "Italiano", "Italian"),
        latin("pt", "Português", "Portuguese"),
        latin("nl", "Nederlands", "Dutch", own = "a á ä â à, e é ë ê è, i í ï ì î ĳ, o ó ö ô ò, u ú ü û ù, y ý ÿ"),
        latin("sv", "Svenska", "Swedish", "swedish", own = "a ä å, o ö"),
        latin("nb", "Norsk bokmål", "Norwegian", "norwegian", own = "a å æ, e é, o ø"),
        latin("da", "Dansk", "Danish", "danish", own = "a å æ, e é, o ø"),
        latin("fi", "Suomi", "Finnish", "swedish", own = "a ä å, o ö, s š, z ž"),
        latin("is", "Íslenska", "Icelandic", own = "a á æ, d ð, e é, i í, o ó ö, u ú, y ý, t þ"),
        latin("pl", "Polski", "Polish", own = "a ą, c ć, e ę, l ł, n ń, o ó, s ś, z ż ź"),
        latin("cs", "Čeština", "Czech", "qwertz", own = "a á, c č, d ď, e é ě, i í, n ň, o ó, r ř, s š, t ť, u ú ů, y ý, z ž"),
        latin("sk", "Slovenčina", "Slovak", own = "a á ä, c č, d ď, e é, i í, l ľ ĺ, n ň, o ô ó, r ŕ, s š, t ť, u ú, y ý, z ž"),
        latin("sl", "Slovenščina", "Slovenian", own = "c č ć, s š, z ž"),
        latin("hu", "Magyar", "Hungarian", "qwertz", own = "a á, e é, i í, o ó ö ő, u ú ü ű"),
        latin("ro", "Română", "Romanian", own = "a ă â, i î, s ș, t ț"),
        latin("lt", "Lietuvių", "Lithuanian", own = "a ą, c č, e ė ę, i į, s š, u ū ų, z ž"),
        latin("lv", "Latviešu", "Latvian", own = "a ā, c č, e ē, g ģ, i ī, k ķ, l ļ, n ņ, o ō, s š, u ū, z ž"),
        // İ is on i's long-press too: on a board Turkish shares with another language shift gives that language's I.
        latin("tr", "Türkçe", "Turkish", "turkish", own = "a â, ı i î, i ı İ î, o ö, u ü û, s ş, g ğ, c ç", locale = Locale.forLanguageTag("tr")),
        latin("ca", "Català", "Catalan", "catalan", own = "a à, e è é, i í ï, o ò ó, u ú ü, c ç, l l·l", joiners = "'·"),
        latin("id", "Bahasa Indonesia", "Indonesian"),
        latin("ms", "Bahasa Melayu", "Malay"),
        latin("fil", "Filipino", "Filipino (Tagalog)", "spanish", own = "n ñ"),
        latin(
            "vi", "Tiếng Việt", "Vietnamese",
            own = "a à á ả ã ạ ă ằ ắ ẳ ẵ ặ â ầ ấ ẩ ẫ ậ, d đ, e è é ẻ ẽ ẹ ê ề ế ể ễ ệ, i ì í ỉ ĩ ị, o ò ó ỏ õ ọ ô ồ ố ổ ỗ ộ ơ ờ ớ ở ỡ ợ, u ù ú ủ ũ ụ ư ừ ứ ử ữ ự, y ỳ ý ỷ ỹ ỵ",
            note = "Long-press a vowel for its tones, or pick the accented word from the row. No Telex or VNI yet.",
        ),
        latin("sh", "Hrvatski · Bosanski · Srpski (latinica)", "Croatian, Bosnian, Serbian (Latin)", "qwertz", own = "c č ć, d đ, s š, z ž", short = "HR · BS · SR"),
        Language("ru", "Русский", "Russian", Script.CYRILLIC, listOf("russian")),
        Language("uk", "Українська", "Ukrainian", Script.CYRILLIC, listOf("ukrainian"), joiners = "'"),
        Language("bg", "Български", "Bulgarian", Script.CYRILLIC, listOf("bulgarian")),
        Language("mk", "Македонски", "Macedonian", Script.CYRILLIC, listOf("macedonian")),
        Language("sr", "Српски", "Serbian (Cyrillic)", Script.CYRILLIC, listOf("serbian"), note = "Word list transliterated from the Latin one."),
        Language("el", "Ελληνικά", "Greek", Script.GREEK, listOf("greek")),
        Language("he", "עברית", "Hebrew", Script.HEBREW, listOf("hebrew"), joiners = "'", note = NO_CAPITALS),
        Language("ar", "العربية", "Arabic", Script.ARABIC, listOf("arabic"), note = NO_CAPITALS),
        Language("fa", "فارسی", "Persian", Script.PERSIAN, listOf("persian"), joiners = "\u200C", note = NO_CAPITALS),
        Language("ur", "اردو", "Urdu", Script.URDU, listOf("urdu"), joiners = "\u200C", note = NO_CAPITALS),
        Language("ko", "한국어", "Korean", Script.HANGUL, listOf("korean"), dict = null, note = "Typing only: no autocorrect, suggestions or swipe yet."),
    )

    private val BY_CODE = ALL.associateBy { it.code }

    /** The language with this [code], or null where the keyboard has none such. */
    fun of(code: String): Language? = BY_CODE[code]

    /** Said in Settings, so nobody hunts for them. */
    val UNAVAILABLE = listOf(
        Unavailable("中文", "Chinese", "Needs a conversion engine (pinyin, kana). Not built yet."),
        Unavailable("日本語", "Japanese", "Needs a conversion engine (pinyin, kana). Not built yet."),
        Unavailable("हिन्दी", "Hindi", "Needs a layered layout we have not built yet."),
        Unavailable("বাংলা", "Bengali", "Needs a layered layout we have not built yet."),
        Unavailable("தமிழ்", "Tamil", "Needs a layered layout we have not built yet."),
    )

    /** The `languages` setting as it may be used: codes this keyboard has, each once, at most [MAX], never none. */
    fun clean(codes: List<String>): List<String> =
        codes.map { it.trim() }.filter { it in BY_CODE }.distinct().take(MAX).ifEmpty { listOf("en") }

    /** "fr:qwerty,de:qwerty" as a map: each language's layout where the person chose one. */
    fun overrides(setting: String): Map<String, String> =
        setting.split(',').mapNotNull { part -> part.trim().split(':').takeIf { it.size == 2 && it[0].isNotEmpty() && it[1].isNotEmpty() }?.let { it[0] to it[1] } }.toMap()

    /** The same map as the `language_layouts` setting keeps it. */
    fun overrides(map: Map<String, String>): String = map.entries.joinToString(",") { "${it.key}:${it.value}" }

    /** The layout [language] types on: the one chosen for it where it may use that one, else its own. */
    fun layoutOf(language: Language, overrides: Map<String, String>): LayoutSpec {
        val id = overrides[language.code]?.takeIf { it in language.layouts } ?: language.layouts.first()
        return Layouts.spec(id) ?: Layouts.QWERTY
    }

    /**
     * A keyboard set up before layouts (0.9.0 and earlier) had Spanish, French and German on QWERTY. Whichever of them
     * is on keeps it, so the keys do not move under anyone's thumbs on the day of the update: the layout choices to
     * add to `language_layouts` for [enabled].
     */
    fun pinned(enabled: List<String>, overrides: Map<String, String>): Map<String, String> =
        overrides + enabled.filter { it in LEGACY_QWERTY && it !in overrides }.associateWith { "qwerty" }

    private val LEGACY_QWERTY = setOf("es", "fr", "de")
}

/**
 * What autocorrect needs to know of the keys being typed on: the [layout]'s id, its [alphabet] (the letters it can
 * type, in code-point order), which letters sit beside which ([neighbours]), the [script] (for [Script.plain]), the
 * [locale] its capitals follow (Turkish i and İ), and whether [english] is one of the languages loaded, since "i" is
 * a capital only there.
 */
class Board(val layout: String, val alphabet: String, val neighbours: Map<Char, Set<Char>>, val script: Script, val locale: Locale, val english: Boolean) {
    companion object {
        /** The keys of [spec], with [english] loaded or not, and whose capitals are typed on it ([locale]; the layout's own unless said). */
        fun of(spec: LayoutSpec, english: Boolean, locale: Locale = spec.locale) = Board(spec.id, spec.alphabet, spec.neighbours, spec.script, locale, english)
    }
}

/**
 * What a word is made of, and where the word before the cursor starts: the one rule the service's corrections,
 * suggestions and word-delete share. Plain Kotlin, so tool/lang_check.sh tries it on a desk.
 */
object Words {
    /**
     * A mark that belongs to an emoji and not to a letter: a variation selector (U+FE0F makes ☺ the picture ☺️) or
     * the keycap's enclosing mark (U+20E3). Unicode files them with the accents, but no word has one.
     */
    fun isEmojiMark(c: Char): Boolean = c in '\uFE00'..'\uFE0F' || c == '\u20E3'

    /**
     * A character of a word: a letter, a mark that rides on one (Arabic's vowels, a combining accent), or one of
     * [joiners] (an apostrophe on Latin boards, Catalan's middle dot, Persian and Urdu's half-space).
     */
    fun isWordChar(c: Char, joiners: String): Boolean = c.isLetter() || c in joiners || (!isEmojiMark(c) && when (Character.getType(c)) {
        Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
        else -> false
    })

    /**
     * Where the word at the end of [text] starts ([text]'s length where it ends in none): back over word characters,
     * no further than [from], then forward to its first letter, since a mark or a joiner rides on a letter and
     * cannot start a word. So a word typed straight after an emoji is the word alone.
     */
    fun lastStart(text: String, joiners: String, from: Int = 0): Int {
        var start = runStart(text, joiners, from)
        while (start < text.length && !text[start].isLetter()) start++
        return start
    }

    /**
     * Where the run of word characters at the end of [text] starts, joiners and marks at its head included: the
     * character before it says whether the word is part of something code-shaped (a path, a handle).
     */
    fun runStart(text: String, joiners: String, from: Int = 0): Int {
        var start = text.length
        while (start > from && isWordChar(text[start - 1], joiners)) start--
        return start
    }
}

/** One stop of the layout switch (the globe key, the space bar's list): a [layout] and the [languages] typed on it. */
class Stop(val layout: LayoutSpec, val languages: List<Language>) {
    val id: String get() = layout.id

    /** What the space bar and the list call it: its languages' own names ("English · Polski"), or "ABC" for plain QWERTY. */
    val label: String get() = if (languages.isEmpty()) "ABC" else languages.joinToString(" · ") { it.name }

    /** The same in less room: "EN · PL". */
    val short: String get() = if (languages.isEmpty()) "ABC" else languages.joinToString(" · ") { it.short }
}

/**
 * The languages that are on and the layout being typed on, as one value the service works out from the settings and
 * hands to autocorrect and the keys. [stops] are the distinct layouts of the enabled languages, in the order the
 * languages were turned on; [active] is the one in use.
 */
class LanguageState(val enabled: List<Language>, val stops: List<Stop>, val active: Stop) {
    val spec: LayoutSpec get() = active.layout

    /**
     * The word lists loaded: every enabled language written in the active layout's script, whichever board it is on,
     * so English and French still mix in one message on QWERTY or AZERTY.
     */
    val dictionaries: List<String> get() = enabled.filter { it.script == spec.script }.mapNotNull { it.dict }

    val board: Board get() = Board.of(spec, english = "en" in dictionaries, locale = locale)

    /**
     * Whose capitals and lower case are typed here: the layout's own where it has one (the Turkish board), else the
     * one every language of the active stop shares (Turkish alone on QWERTY still gets İ from shift and i), else the
     * root locale. Turkish sharing a board with another language keeps that language's I; İ is on i's long-press.
     */
    val locale: Locale get() = spec.locale.takeIf { it != Locale.ROOT }
        ?: active.languages.map { it.locale }.distinct().singleOrNull()
        ?: Locale.ROOT

    /**
     * Every set of word lists a stop of these languages can ask for ([Autocorrect.key]): what autocorrect keeps in
     * memory, so going round the stops with the globe key reads each list once and never again.
     */
    val lexicons: Set<String> get() = stops.map { stop -> Autocorrect.key(stop.layout.script, enabled.filter { it.script == stop.layout.script }.mapNotNull { it.dict }) }.filter { it.isNotEmpty() }.toSet()

    /** Each letter key's extra long-press letters on this stop: its languages' own first, then the accents every Latin board has. */
    val extras: Map<String, List<String>> get() = Layouts.extras(spec, active.languages)

    /** The characters that may sit inside a word here besides letters: an apostrophe on Latin boards, a half-space in Persian. */
    val joiners: String get() = (active.languages.flatMap { it.joiners.toList() } + (if (spec.script == Script.LATIN) listOf('\'') else emptyList())).distinct().joinToString("")

    /** The space bar's name for the active stop, and a shorter one; null with one stop, when there is nothing to tell apart. */
    val spaceLabel: String? get() = if (stops.size < 2) null else active.label
    val spaceShort: String? get() = if (stops.size < 2) null else active.short

    /** The stops as the long-press list shows them: id and label. */
    val choices: List<Pair<String, String>> get() = stops.map { it.id to it.label }

    /** The stop after the active one, round again to the first: where a tap on the globe key goes. */
    val next: String get() = stops[(stops.indexOf(active) + 1) % stops.size].id

    companion object {
        /**
         * [enabled] are the `languages` setting's codes, [overrides] the layouts chosen for some of them, [active] the
         * `layout` setting (empty or stale: the first stop). With [latinField] (a password, an e-mail address, a web
         * address) a board that is not Latin steps aside for the first Latin one.
         *
         * If none of the languages types Latin letters an "ABC" stop (QWERTY, no word list) comes last, so an address
         * or a line of code can always be typed.
         */
        fun of(enabled: List<String>, overrides: Map<String, String>, active: String, latinField: Boolean = false): LanguageState {
            val languages = Languages.clean(enabled).mapNotNull(Languages::of)
            val byLayout = LinkedHashMap<String, Pair<LayoutSpec, ArrayList<Language>>>()
            for (l in languages) {
                val spec = Languages.layoutOf(l, overrides)
                byLayout.getOrPut(spec.id) { spec to ArrayList() }.second += l
            }
            val stops = byLayout.values.mapTo(ArrayList()) { (spec, on) -> Stop(spec, on) }
            if (stops.none { it.layout.script == Script.LATIN }) stops += Stop(Layouts.QWERTY, emptyList())
            var now = stops.firstOrNull { it.id == active } ?: stops.first()
            if (latinField && now.layout.script != Script.LATIN) now = stops.first { it.layout.script == Script.LATIN }
            return LanguageState(languages, stops, now)
        }
    }
}
