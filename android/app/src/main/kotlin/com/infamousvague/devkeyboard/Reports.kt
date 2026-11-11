package com.infamousvague.devkeyboard

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * What an anonymous typing report holds between one sending and the next (Matt, 2026-11-10: "allow anonymous
 * reporting of swipe and text metrics and mistakes so we can improve our knowledge over time"). Plain Kotlin but for
 * org.json, so the unit tests fill one and read it back; [Reports] keeps the phone's.
 *
 * Four things, and nothing else:
 * - [counts]: how often things happened, by name ("words.typed", "fix.undone", "swipe.picked"). Never what was typed.
 * - [own] and [slip]: where taps landed about the key that was meant, as two grids of counts a quarter of a key to
 *   the cell: taps on words that stood as typed, and the one tap autocorrect put right in a word it mended by a
 *   single letter. No letters, no order, no words: only how far thumbs land from the middle of a key.
 * - [mistakes]: a word as it was mistyped and the word it was put right to, with how often. Only ever one of the
 *   commonest words of the app's own lists ([ReportRules] sees to that): never a word of the person's own.
 * - [strokes]: a few swipes with the list word they were, each the finger's path in key widths: every misread while
 *   there is room, and one in [SAMPLE_KEPT] of those read right.
 *
 * Everything is bounded ([MAX_MISTAKES], [MAX_STROKES], [MAX_COUNT]), so a report is a few kilobytes however long
 * it waited. Not safe to share between threads: [Reports] holds it on one.
 */
class ReportTally {
    /** A mistyped word and what it was meant to be ([kind] says how that is known), [n] times. */
    class Mistake(val kind: String, val typed: String, val meant: String, var n: Int)

    /** A swipe: how it was settled ([kind]), the first readings it gave, the word it was, and its path (x, y, x, y… in key widths and rows). */
    class Stroke(val kind: String, val read: List<String>, val meant: String, val path: FloatArray)

    val counts = java.util.TreeMap<String, Long>()
    val own = IntArray(GRID * GRID)
    val slip = IntArray(GRID * GRID)
    val swipeMs = IntArray(MS_EDGES.size + 1)
    val mistakes = ArrayList<Mistake>()
    val strokes = ArrayList<Stroke>()

    /** The day (UTC) the first thing in it was counted; empty while it is. */
    var from = ""
    private var keptSeen = 0

    fun count(name: String, n: Long = 1) {
        if (n <= 0 || !NAME.matches(name)) return
        counts[name] = ((counts[name] ?: 0L) + n).coerceAtMost(MAX_COUNT)
    }

    /**
     * A tap [dx] key widths right of, and [dy] rows below, the middle of the key that was meant: its own key in a
     * word that stood, or ([slipped]) the letter autocorrect put in its place. Further than [SPAN] off, it is only
     * counted ("taps.far").
     */
    fun tap(dx: Float, dy: Float, slipped: Boolean) {
        if (dx.isNaN() || dy.isNaN()) return
        val gx = floor((dx + SPAN) / BIN).toInt()
        val gy = floor((dy + SPAN) / BIN).toInt()
        if (gx !in 0 until GRID || gy !in 0 until GRID) { count("taps.far"); return }
        val grid = if (slipped) slip else own
        if (grid[gy * GRID + gx] < Int.MAX_VALUE) grid[gy * GRID + gx]++
    }

    /** [typed] was meant to be [meant], known by [kind] (one of [KINDS]). Letters only; the same pair again counts once more. */
    fun mistake(kind: String, typed: String, meant: String) {
        if (kind !in KINDS || typed == meant || !plain(typed, spaces = false) || !plain(meant, spaces = true)) return
        mistakes.firstOrNull { it.kind == kind && it.typed == typed && it.meant == meant }?.let { if (it.n < MAX_COUNT) it.n++; return }
        if (mistakes.size >= MAX_MISTAKES) { count("mistakes.more"); return }
        mistakes += Mistake(kind, typed, meant, 1)
    }

    /**
     * A swipe that was [meant] as a list word, settled by [kind] (one of [STROKE_KINDS]), with the [read]ings it gave
     * and its [path]. One read right ("kept") is taken one time in [SAMPLE_KEPT]; a misread always, pushing out
     * one that was read right when the list is full.
     */
    fun stroke(kind: String, read: List<String>, meant: String, path: FloatArray) {
        if (kind !in STROKE_KINDS || !plain(meant, spaces = true) || path.size < 4 || path.size > MAX_POINTS * 2 || path.any { it.isNaN() || it.isInfinite() }) return
        val right = kind == "kept"
        if (right && keptSeen++ % SAMPLE_KEPT != 0) return
        if (strokes.size >= MAX_STROKES) {
            val spare = if (right) -1 else strokes.indexOfFirst { it.kind == "kept" }
            if (spare < 0) { count("strokes.more"); return }
            strokes.removeAt(spare)
        }
        strokes += Stroke(kind, read.filter { plain(it, spaces = true) }.take(3), meant, path)
    }

    /** A swipe took [took] milliseconds to read. */
    fun ms(took: Long) {
        var i = 0
        while (i < MS_EDGES.size && took >= MS_EDGES[i]) i++
        if (swipeMs[i] < Int.MAX_VALUE) swipeMs[i]++
    }

    /** How many words it has seen: typed and swiped. */
    val words: Long get() = (counts["words.typed"] ?: 0L) + (counts["words.swiped"] ?: 0L)

    val empty: Boolean get() = counts.isEmpty() && mistakes.isEmpty() && strokes.isEmpty() && own.all { it == 0 } && slip.all { it == 0 }

    /** The tally as it is sent and as it is kept: see docs/reports.md for each part. */
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("from", from)
        val c = JSONObject()
        for ((k, v) in counts) c.put(k, v)
        o.put("counts", c)
        o.put("taps", JSONObject().put("bin", BIN.toDouble()).put("span", SPAN.toDouble()).put("own", JSONArray(own)).put("slip", JSONArray(slip)))
        val m = JSONArray()
        for (x in mistakes) m.put(JSONArray().put(x.kind).put(x.typed).put(x.meant).put(x.n))
        o.put("mistakes", m)
        val s = JSONArray()
        for (x in strokes) {
            val path = JSONArray()
            for (v in x.path) path.put((v * 100f).roundToInt() / 100.0)
            s.put(JSONObject().put("kind", x.kind).put("read", JSONArray(x.read)).put("meant", x.meant).put("path", path))
        }
        o.put("strokes", s)
        o.put("swipeMs", JSONArray(swipeMs))
        return o
    }

    companion object {
        /** The grids: [GRID] cells a side, each [BIN] of a key, from [SPAN] keys one side of the middle to the other. */
        const val GRID = 12
        const val BIN = 0.25f
        const val SPAN = 1.5f
        const val MAX_MISTAKES = 60
        const val MAX_STROKES = 12
        const val MAX_POINTS = 48
        const val MAX_COUNT = 10_000_000L
        const val SAMPLE_KEPT = 10
        const val MAX_WORD = 24
        /** A swipe's reading time falls in the first of these it is under (milliseconds), or the last cell. */
        val MS_EDGES = longArrayOf(5, 10, 20, 40, 80, 160, 320)
        /** How a mistake is known: typed again by hand, a word taken from the row, or a fix that was undone (then it is the word typed that was right, and [Mistake.meant] the fix that was not). */
        val KINDS = setOf("retyped", "picked", "undone")
        /** How a swipe was settled: left as read, another reading chosen, or deleted and drawn again. */
        val STROKE_KINDS = setOf("kept", "picked", "redrawn")
        private val NAME = Regex("[a-z][A-Za-z0-9.]{0,31}")

        /** Letters and apostrophes only (and single spaces between words, where [spaces]): nothing else is ever put in a report as text. */
        fun plain(word: String, spaces: Boolean): Boolean {
            if (word.length < 2 || word.length > (if (spaces) MAX_WORD * 2 else MAX_WORD)) return false
            if (word.first() == ' ' || word.last() == ' ' || "  " in word) return false
            return word.all { it.isLetter() || it == '\'' || it == '’' || (spaces && it == ' ') }
        }

        /** Reads back what [toJson] wrote; what is malformed in it is left out, never guessed at. */
        fun fromJson(o: JSONObject): ReportTally {
            val t = ReportTally()
            t.from = o.optString("from").takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) } ?: ""
            o.optJSONObject("counts")?.let { c -> for (k in c.keys()) t.count(k, c.optLong(k, 0L)) }
            o.optJSONObject("taps")?.let { g ->
                g.optJSONArray("own")?.let { a -> if (a.length() == t.own.size) for (i in t.own.indices) t.own[i] = a.optInt(i).coerceAtLeast(0) }
                g.optJSONArray("slip")?.let { a -> if (a.length() == t.slip.size) for (i in t.slip.indices) t.slip[i] = a.optInt(i).coerceAtLeast(0) }
            }
            o.optJSONArray("mistakes")?.let { m ->
                for (i in 0 until m.length()) {
                    val x = m.optJSONArray(i) ?: continue
                    val kind = x.optString(0); val typed = x.optString(1); val meant = x.optString(2); val n = x.optInt(3)
                    if (n <= 0 || kind !in KINDS || typed == meant || !plain(typed, false) || !plain(meant, true) || t.mistakes.size >= MAX_MISTAKES) continue
                    t.mistakes += Mistake(kind, typed, meant, n)
                }
            }
            o.optJSONArray("strokes")?.let { s ->
                for (i in 0 until s.length()) {
                    val x = s.optJSONObject(i) ?: continue
                    val kind = x.optString("kind"); val meant = x.optString("meant")
                    val p = x.optJSONArray("path") ?: continue
                    if (kind !in STROKE_KINDS || !plain(meant, true) || p.length() < 4 || p.length() > MAX_POINTS * 2 || p.length() % 2 != 0 || t.strokes.size >= MAX_STROKES) continue
                    val path = FloatArray(p.length()) { p.optDouble(it, Double.NaN).toFloat() }
                    if (path.any { it.isNaN() || it.isInfinite() }) continue
                    val read = x.optJSONArray("read")?.let { r -> (0 until r.length()).map { r.optString(it) }.filter { w -> plain(w, true) }.take(3) } ?: emptyList()
                    t.strokes += Stroke(kind, read, meant, path)
                }
            }
            o.optJSONArray("swipeMs")?.let { a -> if (a.length() == t.swipeMs.size) for (i in t.swipeMs.indices) t.swipeMs[i] = a.optInt(i).coerceAtLeast(0) }
            return t
        }

        /**
         * A finger's path as a report carries it: at most [MAX_POINTS] points evenly along it, each in key widths
         * across from [originX] and rows down from [originY] (the middle of the top row's first letter), so it says
         * which keys it ran over on that layout and nothing of the screen it was drawn on.
         */
        fun pathOf(points: List<Pair<Float, Float>>, originX: Float, originY: Float, keyW: Float, row: Float): FloatArray? {
            if (points.size < 2 || keyW <= 0f || row <= 0f) return null
            val n = (SwipeDecoder.length(points) / (keyW / 3f)).roundToInt().coerceIn(2, MAX_POINTS)
            val even = SwipeDecoder.resample(points, n)
            val out = FloatArray(even.size * 2)
            for (i in even.indices) { out[i * 2] = (even[i].first - originX) / keyW; out[i * 2 + 1] = (even[i].second - originY) / row }
            return out
        }

        /** The rows' pitch on a board whose letter keys have these middles: the least step down between two rows. */
        fun rowPitch(keys: Map<Char, Pair<Float, Float>>, keyW: Float): Float? {
            val ys = keys.values.map { it.second }.distinct().sorted()
            var least = Float.MAX_VALUE
            for (i in 1 until ys.size) { val d = ys[i] - ys[i - 1]; if (d > keyW * 0.3f && d < least) least = d }
            return least.takeIf { it != Float.MAX_VALUE }
        }
    }
}

/**
 * What may go in a [ReportTally], decided in one place and with no phone under it, so the unit tests hold it to its
 * word: [common] says whether a word (lower case) is one of the commonest of the app's own word lists
 * ([Autocorrect.isCommonWord]), [known] whether it is a word at all here: a rarer list word, one the person kept,
 * one of their own. Text is only ever reported where it is a common word, or a slip that is no word; everything
 * else is a number.
 */
class ReportRules(private val common: (String) -> Boolean, private val known: (String) -> Boolean) {
    private fun listed(words: String): Boolean = words.split(' ').all { it.isNotEmpty() && common(it) }

    /**
     * A word was finished by tapping: [typed], and what [stands] once autocorrect had looked at it. Counted; and
     * where its [taps] are known, each tap's distance from the middle of the key that was meant goes in the grids:
     * its own key where the word stood, the letter put in its place where autocorrect mended one letter (a word
     * mended any other way says nothing sure of where each tap was aimed, and is left out of them). No letter is
     * kept: only how far from a key's middle.
     */
    fun word(t: ReportTally, typed: String, stands: String, taps: Taps?) {
        t.count("words.typed")
        val a = typed.lowercase()
        val b = stands.lowercase()
        val fixed = a != b
        if (fixed) { t.count("fix.made"); if (' ' in b) t.count("fix.joined") }
        if (taps == null) { t.count("words.noTaps"); return }
        val row = ReportTally.rowPitch(taps.keys, taps.keyW) ?: return
        var at = -1
        if (fixed) {
            if (a.length != b.length) return
            var differing = 0
            for (i in a.indices) if (a[i] != b[i]) { differing++; at = i }
            if (differing != 1) return
        }
        for (i in a.indices) {
            if (!taps.has(i)) continue
            val key = taps.keys[if (i == at) b[i] else a[i]] ?: continue
            t.tap((taps.xs[i] - key.first) / taps.keyW, (taps.ys[i] - key.second) / row, slipped = i == at)
        }
    }

    /**
     * [typed] was put right to [meant] by hand ([kind] "retyped") or from the row ("picked"). Reported as a pair
     * only where what was meant is a common word and what was typed is a common word or no word at all (never a
     * rarer word, a name, or one of the person's own); else only counted.
     */
    fun mistake(t: ReportTally, kind: String, typed: String, meant: String) {
        t.count("mistakes.$kind")
        val a = typed.lowercase()
        val b = meant.lowercase()
        if (!listed(b) || (known(a) && !common(a))) { t.count("mistakes.unlisted"); return }
        t.mistake(kind, a, b)
    }

    /**
     * An autocorrection of [typed] to [fix] was undone: the fix was wrong. Reported as a pair only where both are
     * common words (a real word changed for another); a name or a word of the person's own that was changed is
     * only counted.
     */
    fun undone(t: ReportTally, typed: String, fix: String) {
        t.count("fix.undone")
        val a = typed.lowercase()
        val b = fix.lowercase()
        if (common(a) && listed(b)) t.mistake("undone", a, b) else t.count("fix.undoneOwn")
    }

    /**
     * A swiped word was settled: left as read ("kept"), another reading chosen ("picked"), or deleted and drawn again
     * ("redrawn": the stroke before it, with what it turned out to be). [read] are the readings the stroke gave,
     * [meant] what stood, [path] the stroke ([ReportTally.pathOf]). The stroke itself is kept only where every word
     * of what it was is a common word, and of its readings only those that are.
     */
    fun stroke(t: ReportTally, kind: String, read: List<String>, meant: String, path: FloatArray?) {
        // A stroke drawn again is the one before it over: it was counted as a word when it was deleted or settled.
        if (kind != "redrawn") t.count("words.swiped")
        t.count("swipe.$kind")
        val m = meant.lowercase()
        if (' ' in m && kind != "redrawn") t.count("swipe.phrases")
        if (path == null || !listed(m)) return
        t.stroke(kind, read.map { it.lowercase() }.filter(::listed), m, path)
    }
}

/**
 * Anonymous typing reports: the phone's [ReportTally], kept in [FILE] under the app's no-backup files and, where
 * Settings › Typing › Typing stats › "Share anonymous typing reports" is on, sent to [ENDPOINT] at most once a day.
 * Off by default; switched off again, what was waiting is deleted.
 *
 * What may be counted is the service's to decide (never a password field, incognito, an address, a name, a search,
 * a terminal, or this app's own fields: the rule next-word learning has), and it counts nothing while the switch is
 * off. What may be reported as text is decided here ([ReportRules]): a word is only ever sent if it is one of the
 * commonest of the app's own word lists ([common]: about seven thousand words of English), so a nickname, a rarer
 * name, a term of someone's work or a word they taught the keyboard never leaves; what does not pass is counted as
 * a number and no more.
 *
 * A report carries no account, no device or install id and no time of day: the two days it covers, the app's
 * version, the layout and languages, the size of a key, and the tally. It goes through [Net] like every other
 * connection (so Offline stops it and Settings › Offline lists it), and the keyboard never waits on any of this: the
 * tally is changed, saved and sent on one background thread.
 */
object Reports {
    const val FILE = "typing_report.json"
    const val ENDPOINT = "https://ghostkbd.com/report"
    /** A report is worth sending once it has seen this many words. */
    const val MIN_WORDS = 100L
    private const val TAG = "GhostKeyboard"
    private const val RETRY_MS = 6 * 3_600_000L
    /** What has waited this many days unsent (the server away, the phone offline) is let go, and counting starts afresh. */
    private const val MAX_DAYS = 14L
    const val VERSION = 1

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "typing-report").apply { isDaemon = true } }

    // The worker's own.
    private var tally: ReportTally? = null
    private var lastSent = ""
    private var lastTry = 0L
    private var dirty = false

    /** Whether a word (lower case) is one of the commonest of the app's own word lists: set by the service. No other word is reported as text. */
    @Volatile var common: (String) -> Boolean = { false }

    /** Whether a word is a word here at all (any list's, one the person kept, one of their own): set by the service. One that is, and is not common, is never reported even as what was mistyped. */
    @Volatile var known: (String) -> Boolean = { true }

    /** The board being typed on, as a report describes it. */
    @Volatile private var board: JSONObject? = null

    /** The layout, its languages and the size of a key (in dp, and a row's height as a share of a key's width). */
    fun board(layout: String, languages: List<String>, keyDp: Float, aspect: Float, condensed: Boolean, numberRow: Boolean) {
        board = JSONObject().put("layout", layout).put("languages", JSONArray(languages.take(6)))
            .put("keyDp", keyDp.roundToInt()).put("rowAspect", (aspect * 20f).roundToInt() / 20.0).put("condensed", condensed).put("numberRow", numberRow)
    }

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE)
    private fun today(): String = LocalDate.now(ZoneOffset.UTC).toString()

    /** The tally, read from its file the first time. Worker only. */
    private fun load(context: Context): ReportTally {
        tally?.let { return it }
        val made = runCatching {
            val o = JSONObject(file(context).readText())
            lastSent = o.optString("lastSent")
            lastTry = o.optLong("lastTry")
            ReportTally.fromJson(o.optJSONObject("tally") ?: JSONObject())
        }.getOrElse { ReportTally() }
        // Waited too long to be sent: let go.
        val stale = made.from.isNotEmpty() && runCatching { LocalDate.parse(made.from).plusDays(MAX_DAYS).isBefore(LocalDate.now(ZoneOffset.UTC)) }.getOrDefault(true)
        return (if (stale) ReportTally() else made).also { tally = it }
    }

    /** Writes the tally whole, by way of a temporary file. Worker only. */
    private fun write(context: Context) {
        val t = tally ?: return
        runCatching {
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(JSONObject().put("tally", t.toJson()).put("lastSent", lastSent).put("lastTry", lastTry).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            dirty = false
        }.onFailure { Log.w(TAG, "typing report not saved", it) }
    }

    private fun change(context: Context, what: (ReportTally) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            runCatching {
                val t = load(app)
                if (t.from.isEmpty()) t.from = today()
                what(t)
                dirty = true
            }.onFailure { Log.w(TAG, "typing report not counted", it) }
        }
    }

    private fun rules() = ReportRules(common, known)

    /** Something happened [n] times. */
    fun count(context: Context, name: String, n: Long = 1) = change(context) { it.count(name, n) }

    /** A word was finished by tapping ([ReportRules.word]). */
    fun word(context: Context, typed: String, stands: String, taps: Taps?) = change(context) { rules().word(it, typed, stands, taps) }

    /** [typed] was put right to [meant] by hand ([kind] "retyped") or from the row ("picked") ([ReportRules.mistake]). */
    fun mistake(context: Context, kind: String, typed: String, meant: String) = change(context) { rules().mistake(it, kind, typed, meant) }

    /** An autocorrection of [typed] to [fix] was undone: the fix was wrong ([ReportRules.undone]). */
    fun undone(context: Context, typed: String, fix: String) = change(context) { rules().undone(it, typed, fix) }

    /** A swiped word was settled ([ReportRules.stroke]). */
    fun stroke(context: Context, kind: String, read: List<String>, meant: String, path: FloatArray?) = change(context) { rules().stroke(it, kind, read, meant, path) }

    /** A swipe took [took] milliseconds to read. */
    fun swipeMs(context: Context, took: Long) = change(context) { it.ms(took) }

    /** Writes what changed: the keyboard hid. */
    fun save(context: Context) {
        val app = context.applicationContext
        worker.execute { if (dirty) write(app) }
    }

    /** Deletes what is waiting: the switch was turned off, or "Discard what is waiting" was tapped. */
    fun discard(context: Context) {
        val app = context.applicationContext
        worker.execute {
            if (tally?.empty == true && !file(app).exists()) return@execute
            tally = ReportTally()
            dirty = false
            runCatching { file(app).delete(); File(file(app).parentFile, "$FILE.tmp").delete() }
        }
    }

    /** The report as it would be sent now. Worker only. */
    private fun report(context: Context, t: ReportTally): JSONObject {
        val o = t.toJson()
        o.put("v", VERSION)
        o.put("app", Updater.currentVersion(context))
        o.put("to", today())
        board?.let { o.put("board", it) }
        return o
    }

    /**
     * Sends what is waiting if it is due: the switch on, Offline off, [MIN_WORDS] words seen, nothing sent today and
     * no try in the last six hours. [now] ("Send now" in Settings) skips the waiting, never the switch nor Offline.
     * [done] hears what happened, in words for the person. A report the server did not take is kept and tried again.
     */
    fun maybeSend(context: Context, prefs: KeyboardPrefs, now: Boolean = false, done: ((String) -> Unit)? = null) {
        val app = context.applicationContext
        worker.execute {
            val said = runCatching { send(app, prefs, now) }.getOrElse { e ->
                if (dirty) write(app)
                if (e is java.io.IOException) Net.message(prefs, e, "Couldn’t reach the server; it will be tried again") else "Not sent"
            }
            runCatching { done?.invoke(said) }
        }
    }

    private fun send(context: Context, prefs: KeyboardPrefs, now: Boolean): String {
        if (!prefs.shareReports) return "Sharing is off"
        if (Net.offline(prefs)) return Net.OFFLINE_MESSAGE
        val t = load(context)
        if (t.empty) return "Nothing is waiting"
        val at = System.currentTimeMillis()
        if (!now) {
            if (t.words < MIN_WORDS) return "Waiting for $MIN_WORDS words"
            if (lastSent == today()) return "Sent today already"
            if (at - lastTry in 0 until RETRY_MS) return "Tried a little while ago"
        }
        lastTry = at
        dirty = true
        val body = report(context, t).toString().toByteArray(Charsets.UTF_8)
        val c = Net.open(prefs, NetPath.REPORT, ENDPOINT)
        val code = try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            c.responseCode
        } finally { runCatching { c.disconnect() } }
        if (code !in 200..299) { write(context); return "The server didn’t take it ($code); it will be tried again" }
        tally = ReportTally()
        lastSent = today()
        write(context)
        return "Sent"
    }

    /**
     * For Settings: `waiting`, the report as it would be sent now (indented JSON, exactly what leaves), `words` it
     * has seen, `enough` once that is [MIN_WORDS], `mistakes` and `strokes` in it, `lastSent` (a day, or empty) and
     * the `server` it goes to.
     */
    fun state(context: Context): Map<String, Any> {
        val app = context.applicationContext
        return runCatching {
            worker.submit<Map<String, Any>> {
                val t = load(app)
                mapOf(
                    "waiting" to (if (t.empty) "" else pretty(report(app, t))), "words" to t.words, "enough" to (t.words >= MIN_WORDS),
                    "mistakes" to t.mistakes.size, "strokes" to t.strokes.size, "lastSent" to lastSent, "server" to (NetLedger.hostOf(ENDPOINT) ?: ""),
                )
            }.get(3, TimeUnit.SECONDS)
        }.getOrDefault(mapOf("waiting" to "", "words" to 0L, "enough" to false, "mistakes" to 0, "strokes" to 0, "lastSent" to "", "server" to (NetLedger.hostOf(ENDPOINT) ?: "")))
    }

    /**
     * A report set out for reading: every part of it, indented, with a list of numbers or words on one line and a
     * grid as its twelve rows. The same values the sending writes, laid out for eyes.
     */
    fun pretty(v: Any?, indent: String = ""): String = when (v) {
        is JSONObject -> if (v.length() == 0) "{}" else v.keys().asSequence().toList().joinToString(",\n", "{\n", "\n$indent}") { k -> "$indent  ${JSONObject.quote(k)}: ${pretty(v.get(k), "$indent  ")}" }
        is JSONArray -> {
            val items = (0 until v.length()).map { v.get(it) }
            when {
                items.isEmpty() -> "[]"
                items.any { it is JSONObject || it is JSONArray } -> items.joinToString(",\n", "[\n", "\n$indent]") { "$indent  ${pretty(it, "$indent  ")}" }
                items.size == ReportTally.GRID * ReportTally.GRID -> items.chunked(ReportTally.GRID).joinToString(",\n", "[\n", "\n$indent]") { row -> "$indent  " + row.joinToString(" ") { one(it) + "," }.trimEnd(',') }
                else -> items.joinToString(", ", "[", "]") { one(it) }
            }
        }
        else -> one(v)
    }

    private fun one(v: Any?): String = when (v) { is String -> JSONObject.quote(v); null, JSONObject.NULL -> "null"; else -> v.toString() }

    /** Waits, at most [ms], until everything queued so far has run: for Settings after a tap. */
    fun awaitIdle(ms: Long) { runCatching { worker.submit { }.get(ms, TimeUnit.MILLISECONDS) } }
}
