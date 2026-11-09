package com.infamousvague.devkeyboard

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The Ghost.md plugin: the keyboard and Ghost.md working together.
 *
 * - In Ghost.md: `[[` offers note titles and `#` tags, from Ghost.md itself where it shares them (its keyboard
 *   provider, [TITLES] and [TAGS]) and from the links and tags you have typed there; plus Ghost.md's own blocks
 *   (callouts, tables, math, diagrams, today's date) a tap away.
 * - Everywhere else: selected text goes to Ghost.md as a new note (its share sheet), in a tap.
 */
object GhostMd {
    /** What Ghost.md shares with the keyboard, where it does: a "title" column, and a "tag" column. */
    val TITLES: Uri = Uri.parse("content://com.mattssoftware.glyph.keyboard/titles")
    val TAGS: Uri = Uri.parse("content://com.mattssoftware.glyph.keyboard/tags")

    private const val FILE = "ghostmd_learned"

    /** Which Ghost.md build is on this phone, if any. */
    fun installed(context: Context): String? =
        AppContext.GHOST_MD.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    private fun query(context: Context, uri: Uri, column: String): List<String> = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            val i = c.getColumnIndex(column)
            if (i < 0) emptyList() else buildList { while (c.moveToNext()) c.getString(i)?.let { add(it) } }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun learned(context: Context, key: String): List<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, "")!!.split('\n').filter { it.isNotBlank() }

    private fun learn(context: Context, key: String, value: String) {
        val now = listOf(value) + learned(context, key).filter { !it.equals(value, ignoreCase = true) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, now.take(500).joinToString("\n")).apply()
    }

    @Volatile private var titleCache: Pair<Long, List<String>>? = null
    @Volatile private var tagCache: Pair<Long, List<String>>? = null

    /** Note titles that match [query], Ghost.md's own first (re-read at most every half minute), then learned ones. */
    fun titles(context: Context, query: String, limit: Int = 8): List<String> {
        val now = System.currentTimeMillis()
        val shared = titleCache?.takeIf { now - it.first < 30_000 }?.second ?: query(context, TITLES, "title").also { titleCache = now to it }
        return match((shared + learned(context, "titles")).distinct(), query, limit)
    }

    fun tags(context: Context, query: String, limit: Int = 8): List<String> {
        val now = System.currentTimeMillis()
        val shared = tagCache?.takeIf { now - it.first < 30_000 }?.second ?: query(context, TAGS, "tag").also { tagCache = now to it }
        return match((shared + learned(context, "tags")).distinct(), query, limit)
    }

    private fun match(all: List<String>, query: String, limit: Int): List<String> {
        val q = query.lowercase()
        if (q.isEmpty()) return all.take(limit)
        val starts = all.filter { it.lowercase().startsWith(q) }
        val words = all.filter { it !in starts && it.lowercase().split(' ', '-', '/').any { w -> w.startsWith(q) } }
        val contains = all.filter { it !in starts && it !in words && it.lowercase().contains(q) }
        return (starts + words + contains).take(limit)
    }

    private val WIKI = Regex("\\[\\[([^\\[\\]\\n|]{1,80})(?:\\|[^\\]]*)?]]")
    private val TAG = Regex("(?:^|\\s)#([\\p{L}\\p{N}_/-]{2,40})(?=\\s)")

    /** Picks up the [[links]] and #tags in text typed in Ghost.md, so they are offered next time. */
    fun learnFrom(context: Context, text: String) {
        WIKI.findAll(text).forEach { learn(context, "titles", it.groupValues[1].trim()) }
        TAG.findAll(text).forEach { learn(context, "tags", it.groupValues[1]) }
    }

    /** [text] to Ghost.md as a new note, through its share sheet. */
    fun save(context: Context, text: String): Boolean {
        val pkg = installed(context) ?: return false
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage(pkg)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Ghost.md's voice notes (ghostmd://voice). */
    fun voice(context: Context): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("ghostmd://voice")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
