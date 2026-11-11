package com.infamousvague.devkeyboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * The typing trainer's two files (docs/trainer.md), with no Android in them: [write] and [read] are a [TrainerBook] as
 * the phone keeps it (`trainer.json` in the app's no-backup files), and [profile] is a [TrainerProfile] as a Ghost
 * typing profile (docs/typing-profile.md), so what the trainer measured goes into everyday typing through the same
 * door any typing profile does ([TypingProfile.import]).
 *
 * A file this keyboard cannot read (damaged, or from a newer version) is no file: the trainer starts afresh rather
 * than guess.
 */
object TrainerCodec {
    const val VERSION = 1

    /** The typing profile's own name and version ([TypingProfile.FORMAT], [TypingProfile.VERSION]; TrainerTest holds them together). */
    const val PROFILE_FORMAT = "ghost-typing-profile"
    const val PROFILE_VERSION = 1

    private fun num(v: Float): Any = if (v.isNaN() || v.isInfinite()) -1 else Math.round(v * 1000) / 1000.0

    fun write(book: TrainerBook): String {
        val o = JSONObject().put("v", VERSION).put("trials", book.trials)
        val runs = JSONArray()
        for (r in book.runs) {
            runs.put(JSONArray().put(r.at).put(r.story).put(r.title).put(if (r.practice) 1 else 0).put(r.height).put(if (r.trial) 1 else 0).put(r.ms)
                .put(num(r.wpm)).put(num(r.accuracy)).put(r.words).put(r.right).put(r.firstTry).put(r.backspaces).put(r.mended).put(r.spoiled))
        }
        o.put("runs", runs)
        o.put("read", JSONArray(book.read))
        val aims = JSONObject()
        for ((layout, keys) in book.aims) {
            val k = JSONObject()
            for ((c, m) in keys.toSortedMap()) k.put(c.toString(), JSONArray().put(m.n).put(num(m.x)).put(num(m.y)).put(m.total))
            aims.put(layout, k)
        }
        o.put("aims", aims)
        val slips = JSONObject()
        for ((layout, pairs) in book.slips) slips.put(layout, JSONObject().apply { for ((p, n) in pairs.toSortedMap()) put(p, n) })
        o.put("slips", slips)
        val given = JSONObject()
        for ((layout, keys) in book.given) {
            val k = JSONObject()
            for ((c, d) in keys.toSortedMap()) k.put(c.toString(), JSONArray().put(num(d[0])).put(num(d[1])))
            given.put(layout, k)
        }
        o.put("given", given)
        fun tallies(map: Map<String, Tally>) = JSONObject().apply { for ((k, t) in map.toSortedMap()) put(k, JSONArray().put(t.n).put(t.missed).put(num(t.recentN)).put(num(t.recentMissed))) }
        o.put("keys", tallies(book.keys.mapKeys { it.key.toString() }))
        o.put("pairs", tallies(book.pairs))
        val words = JSONObject()
        for ((w, n) in book.words.toSortedMap()) {
            val one = JSONObject().put("s", n.seen).put("m", n.missed).put("l", n.last)
            if (n.forms.isNotEmpty()) one.put("f", JSONObject().apply { for ((f, c) in n.forms) put(f, c) })
            words.put(w, one)
        }
        o.put("words", words)
        val t = book.layouts
        fun floats(list: List<Float>) = JSONArray().apply { for (v in list) put(num(v)) }
        o.put("layouts", JSONObject().put("own", t.own).put("home", t.home).put("candidate", t.candidate ?: JSONObject.NULL).put("rejected", JSONArray(t.rejected.toList()))
            .put("h", floats(t.homeScores)).put("c", floats(t.candidateScores)).put("scores", JSONObject().apply { for ((h, list) in t.scores) put(h, floats(list)) }))
        return o.toString()
    }

    /** The book in [text]; null where it is not one this keyboard reads. */
    fun read(text: String): TrainerBook? {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val v = o.optInt("v", 0)
        if (v < 1 || v > VERSION) return null
        return runCatching {
            val book = TrainerBook()
            book.trials = o.optBoolean("trials", false)
            o.optJSONArray("runs")?.let { runs ->
                for (i in 0 until runs.length()) {
                    val r = runs.optJSONArray(i) ?: continue
                    if (r.length() < 15) continue
                    val accuracy = r.optDouble(8, -1.0).toFloat()
                    book.runs += RunLine(
                        r.optLong(0), r.optString(1), r.optString(2), r.optInt(3) == 1, r.optString(4), r.optInt(5) == 1, r.optLong(6), r.optDouble(7, 0.0).toFloat(),
                        if (accuracy < 0f) Float.NaN else accuracy, r.optInt(9), r.optInt(10), r.optInt(11), r.optInt(12), r.optInt(13), r.optInt(14),
                    )
                }
            }
            while (book.runs.size > TrainerBook.MAX_RUNS) book.runs.removeAt(0)
            o.optJSONArray("read")?.let { for (i in 0 until it.length()) book.read += it.optString(i) }
            o.optJSONObject("aims")?.let { aims ->
                for (layout in aims.keys()) {
                    val keys = aims.optJSONObject(layout) ?: continue
                    val into = book.aims.getOrPut(layout) { HashMap() }
                    for (k in keys.keys()) {
                        val a = keys.optJSONArray(k) ?: continue
                        if (k.length != 1 || a.length() < 4) continue
                        into[k[0]] = TrainerAims.Measure(a.optInt(0).coerceIn(0, TrainerAims.CAP), a.optDouble(1, 0.0).toFloat().coerceIn(-2f, 2f), a.optDouble(2, 0.0).toFloat().coerceIn(-2f, 2f), a.optInt(3).coerceAtLeast(0))
                    }
                }
            }
            o.optJSONObject("slips")?.let { slips ->
                for (layout in slips.keys()) {
                    val pairs = slips.optJSONObject(layout) ?: continue
                    val into = book.slips.getOrPut(layout) { HashMap() }
                    for (p in pairs.keys()) if (p.length == 3 && p[1] == '>') into[p] = pairs.optInt(p).coerceAtLeast(0)
                }
            }
            o.optJSONObject("given")?.let { given ->
                for (layout in given.keys()) {
                    val keys = given.optJSONObject(layout) ?: continue
                    val into = book.given.getOrPut(layout) { HashMap() }
                    for (k in keys.keys()) {
                        val a = keys.optJSONArray(k) ?: continue
                        if (k.length == 1 && a.length() >= 2) into[k[0]] = floatArrayOf(a.optDouble(0, 0.0).toFloat().coerceIn(-1f, 1f), a.optDouble(1, 0.0).toFloat().coerceIn(-1f, 1f))
                    }
                }
            }
            fun tally(a: JSONArray) = Tally(a.optInt(0).coerceAtLeast(0), a.optInt(1).coerceAtLeast(0), a.optDouble(2, 0.0).toFloat().coerceAtLeast(0f), a.optDouble(3, 0.0).toFloat().coerceAtLeast(0f))
            o.optJSONObject("keys")?.let { keys -> for (k in keys.keys()) keys.optJSONArray(k)?.let { if (k.length == 1 && it.length() >= 4) book.keys[k[0]] = tally(it) } }
            o.optJSONObject("pairs")?.let { pairs -> for (k in pairs.keys()) pairs.optJSONArray(k)?.let { if (k.length == 2 && it.length() >= 4) book.pairs[k] = tally(it) } }
            o.optJSONObject("words")?.let { words ->
                for (w in words.keys()) {
                    val one = words.optJSONObject(w) ?: continue
                    if (w.isEmpty() || w.length > TrainerBook.MAX_WORD) continue
                    val note = WordNote(one.optInt("s").coerceAtLeast(0), one.optInt("m").coerceAtLeast(0), one.optString("l").filter { it == '0' || it == '1' }.takeLast(WordNote.MAX_LAST))
                    one.optJSONObject("f")?.let { f -> for (form in f.keys()) if (note.forms.size < WordNote.MAX_FORMS) note.forms[form.take(TrainerBook.MAX_WORD)] = f.optInt(form).coerceAtLeast(1) }
                    book.words[w] = note
                }
            }
            o.optJSONObject("layouts")?.let { t ->
                fun floats(a: JSONArray?): List<Float> = if (a == null) emptyList() else (0 until a.length()).map { a.optDouble(it, 0.0).toFloat() }.filter { it > 0f }
                val scores = LinkedHashMap<String, List<Float>>()
                t.optJSONObject("scores")?.let { s -> for (h in s.keys()) scores[h] = floats(s.optJSONArray(h)) }
                val rejected = t.optJSONArray("rejected")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                book.layouts.restore(t.optString("own", "normal"), t.optString("home", "normal"), if (t.isNull("candidate")) null else t.optString("candidate"), rejected, floats(t.optJSONArray("h")), floats(t.optJSONArray("c")), scores)
            }
            book
        }.getOrNull()
    }

    /**
     * [profile] as a Ghost typing profile (version 1): the key aims the taps were measured at, for its layout, and a
     * few sentences for the person. [slips] adds the slips between keys, for a reader that wants them; they are left
     * out of what the trainer itself applies, since the aims are already the mean of every tap, slips among them.
     * What a typing profile has no field for (the speed, the words missed, the height the runs point to) rides under
     * `trainer`, which every reader of the format passes over.
     */
    fun profile(profile: TrainerProfile, created: String, slips: Boolean = true): String {
        val o = JSONObject().put("format", PROFILE_FORMAT).put("version", PROFILE_VERSION).put("created", created)
            .put("source", JSONObject().put("app", Retrain.OWN_APP).put("tool", "trainer"))
            .put("report", profile.report).put("layout", profile.layout)
        val keys = JSONObject()
        for (k in profile.keys) keys.put(k.key.toString(), JSONObject().put("dx", num(k.dx)).put("dy", num(k.dy)).put("samples", k.samples))
        if (keys.length() > 0) o.put("keys", keys)
        if (slips && profile.slips.isNotEmpty()) o.put("slips", JSONArray().apply { for (s in profile.slips) put(JSONObject().put("pressed", s.pressed.toString()).put("meant", s.meant.toString()).put("count", s.count)) })
        val more = JSONObject().put("runs", profile.runs)
            .put("wordsAMinute", JSONObject().put("first", num(profile.wpmFirst)).put("now", num(profile.wpmNow)).put("best", num(profile.wpmBest)))
            .put("accuracy", JSONObject().put("first", num(profile.accuracyFirst)).put("now", num(profile.accuracyNow)))
        profile.suggestion?.let { more.put("keyHeight", JSONObject().put("suggested", it.height).put("score", num(it.score)).put("runs", it.runs).put("ownScore", num(it.ownScore)).put("ownRuns", it.ownRuns)) }
        if (profile.mistakes.isNotEmpty()) more.put("mistakes", JSONArray().apply { for (m in profile.mistakes) put(JSONObject().put("word", m.word).put("typed", m.typed).put("missed", m.missed).put("of", m.seen)) })
        if (profile.pairs.isNotEmpty()) more.put("pairs", JSONArray().apply { for (p in profile.pairs) put(JSONObject().put("pair", p.pair).put("missed", p.missed).put("of", p.n)) })
        o.put("trainer", more)
        return o.toString(2)
    }
}
