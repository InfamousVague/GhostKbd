package com.infamousvague.devkeyboard

import android.content.ContentValues
import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The accuracy record (Matt, 2026-11-10: "think of ways we can improve the accuracy of autocorrect and swipe", then
 * "Yes, start there": measure first, on real typing). Off unless turned on in Settings › Typing › Typing stats.
 *
 * Each finished word is kept with the taps that made it (where each finger landed, in pixels, with the letter the
 * keyboard took), what autocorrect made of it, and what you kept in the end: the fix ("kept"), your own spelling
 * ("undone", or the quoted word tapped), a suggestion ("picked:w"), or what you typed after deleting it
 * ("retyped:w"). Each swipe is kept with its path and the time of every point, the readings offered, and which you
 * kept. The layout's letter centres are written whenever they change, so a replay can measure taps and paths against
 * the keys. tool/accuracy-replay.sh runs autocorrect and the swipe decoder over the record on a Mac and scores them.
 *
 * This is your typing, so: only while the switch is on, never in a password field, never while incognito, never in
 * fields whose text says nothing about how you type (addresses, numbers, searches); kept in this app's private
 * storage, at most [MAX_BYTES], until you delete it, and leaving the phone only when you save it to Downloads yourself.
 */
object AccuracyLog {
    private const val FILE = "accuracy_log.jsonl"
    private const val MAX_BYTES = 6_000_000L
    private val lock = Any()

    /** The taps of the word being typed: letter, x, y (px), and the key's size. */
    private val taps = ArrayList<JSONArray>()
    /** The last word or swipe, waiting to learn what was kept. */
    private var pending: JSONObject? = null
    private var pendingDeleted = false
    /** Backspaces since the last word or swipe: one only takes its space away, so typing on continues it. */
    private var deletes = 0
    private var lastLayout: String? = null

    fun tap(c: Char, x: Float, y: Float, keyW: Float, keyH: Float) {
        taps += JSONArray().put(c.toString()).put(r(x)).put(r(y)).put(r(keyW)).put(r(keyH))
    }

    fun clearTaps() = taps.clear()

    /** The keys as they are now, written when they change. */
    fun layout(context: Context, id: String, dictionaries: List<String>, keys: Map<Char, PointF>, keyW: Float) {
        val o = JSONObject().put("t", "layout").put("id", id).put("dicts", JSONArray(dictionaries)).put("keyW", r(keyW))
        val k = JSONObject()
        keys.toSortedMap().forEach { (c, p) -> k.put(c.toString(), JSONArray().put(r(p.x)).put(r(p.y))) }
        o.put("keys", k)
        val sig = "$id|$dictionaries|${r(keyW)}|$k"
        if (sig == lastLayout) return
        lastLayout = sig
        write(context, o)
    }

    /** A word finished: [typed] as the keys made it, [auto] what autocorrect turned it into (null: left alone). */
    fun word(context: Context, typed: String, auto: String?, before: String, app: String?) {
        // No finger made it (a swipe's word ending at its space, a paste): nothing here to measure.
        if (taps.isEmpty()) return
        // A swipe goes whole with one backspace; a typed word needs more than its space taken.
        if (pendingDeleted && (deletes > 1 || pending?.optString("t") == "swipe")) settle(context, "retyped:" + (auto ?: typed))
        flush(context)
        pending = JSONObject().put("t", "word").put("at", System.currentTimeMillis()).put("app", app.orEmpty())
            .put("before", before).put("typed", typed).put("auto", auto ?: JSONObject.NULL).put("taps", JSONArray(taps.toList()))
            // Typing on after taking only the space away: the word is longer than its taps.
            .put("tapsMatch", taps.size == typed.length)
        taps.clear()
    }

    /** A swipe typed: its path (px) and each point's time, and the readings offered, best first. */
    fun swipe(context: Context, path: List<PointF>, times: List<Long>, choices: List<String>, before: String?, app: String?) {
        if (pendingDeleted && (deletes > 1 || pending?.optString("t") == "swipe")) settle(context, "retyped:" + choices.firstOrNull().orEmpty())
        flush(context)
        val start = times.firstOrNull() ?: 0L
        val pts = JSONArray()
        path.forEachIndexed { i, p -> pts.put(JSONArray().put(r(p.x)).put(r(p.y)).put(times.getOrNull(i)?.minus(start) ?: JSONObject.NULL)) }
        pending = JSONObject().put("t", "swipe").put("at", System.currentTimeMillis()).put("app", app.orEmpty())
            .put("before", before ?: JSONObject.NULL).put("path", pts).put("choices", JSONArray(choices))
        taps.clear()
    }

    /** What was kept of the last word or swipe, now known: "undone", "typed", "picked:w". */
    fun settle(context: Context, out: String) {
        val p = pending ?: return
        p.put("out", out)
        pending = null
        pendingDeleted = false
        deletes = 0
        write(context, p)
    }

    /** A backspace straight after the last word or swipe: whatever comes next is what was meant instead. */
    fun deleted() {
        if (taps.isNotEmpty()) taps.removeAt(taps.size - 1)
        else if (pending != null) { pendingDeleted = true; deletes++ }
    }

    /** Nothing changed it: it stood as typed (or as autocorrect left it). */
    fun flush(context: Context) {
        val p = pending ?: return
        if (pendingDeleted) { p.put("out", "deleted"); pendingDeleted = false } else if (!p.has("out")) p.put("out", "kept")
        deletes = 0
        pending = null
        write(context, p)
    }

    /** Drops the waiting word and taps: the field changed, or recording isn't allowed here. */
    fun drop() { pending = null; pendingDeleted = false; deletes = 0; taps.clear() }

    fun count(context: Context): Int = synchronized(lock) {
        runCatching { File(context.filesDir, FILE).useLines { lines -> lines.count { it.contains("\"t\":\"word\"") || it.contains("\"t\":\"swipe\"") } } }.getOrDefault(0)
    }

    fun clear(context: Context) = synchronized(lock) { drop(); lastLayout = null; File(context.filesDir, FILE).delete() }

    /** Saves the record to Downloads as ghost-kbd-accuracy-<date>.json, for tool/accuracy-replay.sh. The file's name, or null. */
    fun export(context: Context): String? = synchronized(lock) {
        val src = File(context.filesDir, FILE)
        if (!src.exists()) return null
        // .json, not .jsonl: Downloads adds .json to a file of that type whatever it is called. One JSON object a line.
        val name = "ghost-kbd-accuracy-${java.time.LocalDate.now()}.json"
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
            } else {
                @Suppress("DEPRECATION")
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                src.copyTo(File(dir, name), overwrite = true)
            }
            name
        }.getOrNull()
    }

    private fun write(context: Context, o: JSONObject) = synchronized(lock) {
        runCatching {
            val f = File(context.filesDir, FILE)
            // Past the cap, the older half goes.
            if (f.length() > MAX_BYTES) {
                val lines = f.readLines()
                f.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
                lastLayout = null
            }
            f.appendText(o.toString() + "\n")
        }
    }

    private fun r(v: Float) = (v * 10).toInt() / 10.0
}
