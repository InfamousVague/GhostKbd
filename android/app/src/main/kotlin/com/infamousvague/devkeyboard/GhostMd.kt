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
    /**
     * What Ghost.md shares with the keyboard (its KeyboardNotes.kt), at `<its package>.keyboard`: note titles, tags,
     * and a search of the notes for Ghostwriter. It answers only this keyboard, signed with its release key.
     */
    private fun base(context: Context) = "content://${installed(context) ?: AppContext.GHOST_MD.first()}.keyboard"
    private val TITLES get() = "titles"
    private val TAGS get() = "tags"

    /** A note Ghost.md found for a search: its title, the lines round the match, and its start. */
    data class Found(val title: String, val snippet: String, val body: String)

    /**
     * Ghost.md's notes matching [query] (its words, each counting), best first, from Ghost.md on this phone. Empty
     * when Ghost.md isn't here, is an older build, or has Settings › AI › Ghost Keyboard off.
     */
    fun search(context: Context, query: String, limit: Int = 6): List<Found> {
        if (query.isBlank()) return emptyList()
        // Every Ghost.md build on the phone, until one answers: an older build beside a newer one has no search.
        for (pkg in AppContext.GHOST_MD.filter { context.packageManager.getLaunchIntentForPackage(it) != null }) {
            val found = runCatching {
                val uri = Uri.parse("content://$pkg.keyboard/search").buildUpon().appendQueryParameter("q", query).appendQueryParameter("limit", "$limit").build()
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val t = c.getColumnIndex("title"); val s = c.getColumnIndex("snippet"); val b = c.getColumnIndex("body")
                    if (t < 0) emptyList() else buildList { while (c.moveToNext()) add(Found(c.getString(t).orEmpty(), if (s >= 0) c.getString(s).orEmpty() else "", if (b >= 0) c.getString(b).orEmpty() else "")) }
                } ?: emptyList()
            }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private const val FILE = "ghostmd_learned"

    /** Which Ghost.md build is on this phone, if any. */
    fun installed(context: Context): String? =
        AppContext.GHOST_MD.firstOrNull { context.packageManager.getLaunchIntentForPackage(it) != null }

    private fun query(context: Context, path: String, column: String): List<String> = runCatching {
        context.contentResolver.query(Uri.parse("${base(context)}/$path"), arrayOf(column), null, null, null)?.use { c ->
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
