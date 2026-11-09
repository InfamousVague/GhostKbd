package com.infamousvague.devkeyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * The clipboard plugin's history: the last [MAX] pieces of text copied, newest first, kept in the app's private
 * storage. Android lets only the keyboard in use read the clipboard in the background, so it is recorded while Ghost
 * Keyboard is the keyboard. Nothing is kept that Android marks sensitive (a password manager's copies), while
 * incognito is on, or with the plugin off.
 */
object ClipHistory {
    const val MAX = 25
    private const val FILE = "clip_history"

    data class Clip(val text: String, val at: Long)

    @Volatile private var cache: MutableList<Clip>? = null
    /** When the newest clip was copied, for the row's "just copied" chip; reset once it is pasted. */
    @Volatile var fresh: Clip? = null

    fun all(context: Context): List<Clip> = load(context).toList()

    private fun load(context: Context): MutableList<Clip> {
        cache?.let { return it }
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("clips", "[]") ?: "[]"
        val list = runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getJSONObject(it) }.map { Clip(it.getString("t"), it.getLong("at")) }.toMutableList()
        }.getOrElse { mutableListOf() }
        cache = list
        return list
    }

    private fun save(context: Context, list: List<Clip>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.text).put("at", it.at)) }
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
        if (text.isEmpty() || text.length > 5000) return
        val list = load(context)
        list.removeAll { it.text == text }
        val entry = Clip(text, System.currentTimeMillis())
        list.add(0, entry)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(context, list)
        fresh = entry
    }

    fun remove(context: Context, text: String) {
        val list = load(context)
        list.removeAll { it.text == text }
        save(context, list)
        if (fresh?.text == text) fresh = null
    }

    fun clear(context: Context) {
        load(context).clear()
        save(context, emptyList())
        fresh = null
    }
}
