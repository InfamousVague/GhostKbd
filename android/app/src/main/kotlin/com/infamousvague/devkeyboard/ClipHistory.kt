package com.infamousvague.devkeyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * The clipboard plugin's history: the last [MAX] pieces of text copied, newest first, and the clips that were pinned,
 * kept in the app's private storage. Android lets only the keyboard in use read the clipboard in the background, so
 * it is recorded while Ghost Keyboard is the keyboard. Nothing is kept that Android marks sensitive (a password
 * manager's copies), while incognito is on, or with the plugin off.
 *
 * A pinned clip stays until it is unpinned or removed: it leads the list, it is not counted among the [MAX], and
 * clearing the history leaves it ([ClipList] has the rules). It is pinned by holding it in the row's list, or
 * written by hand in Settings › Row tools.
 */
object ClipHistory {
    const val MAX = 25
    /** The longest text kept, copied or pinned by hand. */
    const val MAX_CHARS = 5000
    private const val FILE = "clip_history"

    @Volatile private var cache: List<ClipList.Clip>? = null
    /** When the newest clip was copied, for the row's "just copied" chip; reset once it is pasted. */
    @Volatile var fresh: ClipList.Clip? = null

    /** As the row lists them: the pinned first, then the copies, each newest first. */
    fun all(context: Context): List<ClipList.Clip> = ClipList.shown(load(context))

    /** Newest first whatever is pinned: Ghostwriter's "my recent clipboard". */
    fun recent(context: Context): List<ClipList.Clip> = ClipList.recent(load(context))

    /** What is pinned, newest first. */
    fun pins(context: Context): List<ClipList.Clip> = all(context).filter { it.pinned }

    private fun load(context: Context): List<ClipList.Clip> {
        cache?.let { return it }
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("clips", "[]") ?: "[]"
        val list = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map { ClipList.Clip(it.getString("t"), it.getLong("at"), it.optBoolean("p")) }
        }.getOrElse { emptyList() }
        cache = list
        return list
    }

    private fun save(context: Context, list: List<ClipList.Clip>) {
        cache = list
        val a = JSONArray()
        // A clip that is not pinned is written as it always was, with no "p" of its own.
        list.forEach { a.put(JSONObject().put("t", it.text).put("at", it.at).apply { if (it.pinned) put("p", true) }) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("clips", a.toString()).apply()
    }

    /** Records what is on the clipboard now, if it is text worth keeping. */
    fun record(context: Context, clipboard: ClipboardManager) {
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return
        val description = clip.description
        if (Build.VERSION.SDK_INT >= 33 && description?.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return
        // Older password managers flag their copies this way.
        if (description?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true) return
        if (clip.itemCount == 0) return
        val text = clip.getItemAt(0).coerceToText(context)?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_CHARS) return
        val list = ClipList.copied(load(context), text, System.currentTimeMillis(), MAX)
        save(context, list)
        fresh = list.firstOrNull { it.text == text }
    }

    /** Pins [text] or lets it go back among the copies. False where a pin is refused: [ClipList.MAX_PINS] are pinned already. */
    fun pin(context: Context, text: String, on: Boolean): Boolean {
        val list = ClipList.pinned(load(context), text, on, MAX) ?: return false
        save(context, list)
        if (on) pinnedOnce(context, true)
        return true
    }

    /**
     * Whether a clip has ever been pinned on this phone: until one has, the row's list says how it is done ("Hold a
     * clip to pin it"), and never again after.
     */
    fun pinnedOnce(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("pinned_once", false)

    private fun pinnedOnce(context: Context, done: Boolean) {
        if (pinnedOnce(context) != done) context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("pinned_once", done).apply()
    }

    /** Pins [text] written by hand in Settings. False where it is empty, too long, or [ClipList.MAX_PINS] are pinned already. */
    fun add(context: Context, text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > MAX_CHARS) return false
        val list = ClipList.added(load(context), clean, System.currentTimeMillis(), MAX) ?: return false
        save(context, list)
        pinnedOnce(context, true)
        return true
    }

    fun remove(context: Context, text: String) {
        save(context, ClipList.removed(load(context), text))
        if (fresh?.text == text) fresh = null
    }

    /** Clears the copies. What is pinned stays: a pin is let go one at a time, by unpinning or removing it. */
    fun clear(context: Context) {
        save(context, ClipList.cleared(load(context)))
        fresh = null
    }
}
