package com.infamousvague.devkeyboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Text shortcuts plugin (Matt, 2026-11-12, choosing from a list of plugins to add: "I like … text shortcuts"): a
 * few letters that stand for a phrase. `omw` and a space types "On my way!"; `@@` an e-mail address; `;sig` a
 * sign-off of several lines.
 *
 * A shortcut is one word of 2 to [MAX_SHORTCUT] characters with no space in it, and it is matched whole and by
 * itself: the run of characters between the last space (or the start of the field) and the cursor, whatever its
 * capitals. So `omw` is found in "omw" and "Omw" and not in "omw." or "(omw" or "homw". While that run is a shortcut
 * the row offers what it stands for as its first chip, and with "Expand when I press space" on, a space or enter
 * straight after it puts the phrase in its place. Backspace straight after that puts the letters back.
 *
 * What a shortcut stands for is typed as written, with four things read in it ([expanded]): `\n` is a new line,
 * `{date}` today's date, `{time}` the time, and `{clipboard}` the newest clip in the clipboard's history.
 *
 * The shortcuts are kept on the phone, in the keyboard's own settings file under [KEY], and nowhere else. They are
 * never expanded in a password field. Nothing here but [all], [add] and [remove] touches Android: the rules can be
 * read and tested off the phone (ShortcutsTest).
 */
object Shortcuts {
    const val KEY = "text_shortcuts"

    /** The most shortcuts kept, and the longest a shortcut and what it stands for may be. */
    const val MAX = 200
    const val MAX_SHORTCUT = 24
    const val MAX_TEXT = 2000

    data class Entry(val shortcut: String, val text: String)

    // --- the rules ---------------------------------------------------------------------------------------------------

    /** The run of characters that ends at the cursor, given the text [before] it: back to the last space or line break. */
    fun token(before: String): String = before.takeLastWhile { !it.isWhitespace() }

    /**
     * The shortcut that stands just before the cursor, if [before] ends in one whole. [before] must reach back at
     * least [MAX_SHORTCUT] + 1 characters where the field has them, so a longer run is known for what it is.
     */
    fun find(entries: List<Entry>, before: String): Entry? {
        val token = token(before)
        if (token.length < 2 || token.length > MAX_SHORTCUT) return null
        return entries.firstOrNull { it.shortcut.equals(token, ignoreCase = true) }
    }

    /** Why [shortcut] cannot stand for [text] beside [others], in words for the person, or null where it can. */
    fun refusal(shortcut: String, text: String, others: List<Entry>): String? = when {
        shortcut.isEmpty() -> "Write the shortcut first: the few letters you will type."
        shortcut.any { it.isWhitespace() } -> "A shortcut is one word, with no space in it."
        shortcut.length < 2 -> "A shortcut needs at least two characters."
        shortcut.length > MAX_SHORTCUT -> "A shortcut is at most $MAX_SHORTCUT characters."
        text.isBlank() -> "Write what $shortcut stands for."
        text.length > MAX_TEXT -> "That is too long for a shortcut: $MAX_TEXT characters at most."
        others.size >= MAX && others.none { it.shortcut.equals(shortcut, ignoreCase = true) } -> "$MAX shortcuts are kept at most. Remove one first."
        else -> null
    }

    /** [list] with [shortcut] standing for [text]: a new entry, or the old one changed. In the shortcuts' own order. */
    fun added(list: List<Entry>, shortcut: String, text: String): List<Entry> =
        (list.filter { !it.shortcut.equals(shortcut, ignoreCase = true) } + Entry(shortcut, text)).sortedBy { it.shortcut.lowercase() }

    fun removed(list: List<Entry>, shortcut: String): List<Entry> = list.filter { !it.shortcut.equals(shortcut, ignoreCase = true) }

    /**
     * What is typed for [text]: as written, with `\n` a new line, `{date}` and `{time}` given as [date] and [time],
     * and `{clipboard}` as [clip], the newest clip (nothing where there is none, or the clipboard may not be read).
     */
    fun expanded(text: String, date: String, time: String, clip: String?): String =
        text.replace("\\n", "\n").replace("{date}", date).replace("{time}", time).replace("{clipboard}", clip.orEmpty())

    fun parse(raw: String?): List<Entry> = runCatching {
        val a = JSONArray(raw ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { Entry(it.getString("s"), it.getString("t")) }
            .filter { it.shortcut.length in 2..MAX_SHORTCUT && it.shortcut.none(Char::isWhitespace) && it.text.isNotBlank() }
    }.getOrElse { emptyList() }

    fun write(list: List<Entry>): String = JSONArray().also { a -> list.forEach { a.put(JSONObject().put("s", it.shortcut).put("t", it.text)) } }.toString()

    // --- on the phone ------------------------------------------------------------------------------------------------

    private var parsedFrom: String? = null
    private var parsed: List<Entry> = emptyList()

    /** The shortcuts kept, read once for each change of them: this is asked at every key. */
    fun all(prefs: KeyboardPrefs): List<Entry> {
        val raw = prefs.prefs.getString(KEY, "[]") ?: "[]"
        if (raw != parsedFrom) { parsed = parse(raw); parsedFrom = raw }
        return parsed
    }

    /** Keeps [shortcut] for [text]. The reason where it cannot ([refusal]), or null when it is kept. */
    fun add(prefs: KeyboardPrefs, shortcut: String, text: String): String? {
        val s = shortcut.trim()
        val t = text.trim()
        refusal(s, t, all(prefs))?.let { return it }
        prefs.prefs.edit().putString(KEY, write(added(all(prefs), s, t))).apply()
        return null
    }

    fun remove(prefs: KeyboardPrefs, shortcut: String) {
        prefs.prefs.edit().putString(KEY, write(removed(all(prefs), shortcut))).apply()
    }

    /** What Settings › Typing › Text shortcuts shows: each shortcut and what it stands for. */
    fun state(prefs: KeyboardPrefs): Map<String, Any?> =
        mapOf("shortcuts" to all(prefs).map { mapOf("shortcut" to it.shortcut, "text" to it.text) }, "max" to MAX)
}
