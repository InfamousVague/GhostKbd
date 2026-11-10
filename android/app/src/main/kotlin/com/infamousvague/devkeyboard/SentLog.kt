package com.infamousvague.devkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * What was sent from this phone, so Sherlock knows its own side of a chat (Matt, 2026-11-10: "sherlock doesn't
 * understand the difference between messages it typed and remote requests"). Every message you send from the keyboard
 * in a chat app, and every reply Sherlock sends, is kept as a fingerprint (a hash of its words, never the words), per
 * app, for [KEEP_DAYS]. A line on the screen with a fingerprint here is yours, wherever the app draws its bubble.
 *
 * Kept only while Sherlock is on, never while incognito or in a password field; in this app's private storage.
 */
object SentLog {
    private const val FILE = "sherlock_sent.json"
    private const val KEEP_DAYS = 7
    private const val MAX = 400
    private val lock = Any()
    private var cache: MutableList<Entry>? = null

    private class Entry(val app: String, val hash: String, val at: Long)

    /** The words as a chat shows them again: case, spacing and a trailing ellipsis or full stop don't matter. */
    fun normal(text: String) = text.lowercase().replace(Regex("\\s+"), " ").trim().trimEnd('…', '.', ' ')

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(normal(text).toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    /** [text] was sent in [app]. */
    fun record(context: Context, app: String?, text: String) {
        if (app.isNullOrBlank() || normal(text).isEmpty()) return
        synchronized(lock) {
            val list = load(context)
            list += Entry(app, hash(text), System.currentTimeMillis())
            save(context, list)
        }
    }

    /** [text] on the screen of [app] is one this phone sent. */
    fun sent(context: Context, app: String?, text: String): Boolean {
        if (app.isNullOrBlank()) return false
        val h = hash(text)
        return synchronized(lock) { load(context).any { it.app == app && it.hash == h } }
    }

    fun clear(context: Context) = synchronized(lock) { cache = mutableListOf(); File(context.filesDir, FILE).delete() }

    private fun load(context: Context): MutableList<Entry> {
        cache?.let { return it }
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val list = runCatching {
            val a = JSONArray(File(context.filesDir, FILE).readText())
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Entry(it.optString("app"), it.optString("h"), it.optLong("at")) }
        }.getOrDefault(emptyList()).filter { it.at >= cutoff }.toMutableList()
        cache = list
        return list
    }

    private fun save(context: Context, list: MutableList<Entry>) {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        list.removeAll { it.at < cutoff }
        while (list.size > MAX) list.removeAt(0)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("app", it.app).put("h", it.hash).put("at", it.at)) }
        runCatching { File(context.filesDir, FILE).writeText(a.toString()) }
    }
}
