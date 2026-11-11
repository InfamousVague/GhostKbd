package com.infamousvague.devkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Typing stats (Matt, 2026-11-09: "typing stats"): how fast and how cleanly you type, a day at a time.
 *
 * Counts only, never text: for each day, the characters and words typed, the time spent typing, the slips taken back
 * with backspace, autocorrect's fixes and the words swiped; and how often each letter is pressed. Kept in the app's
 * private storage for [KEEP_DAYS] days and never sent anywhere. Nothing is counted in a password field, while
 * incognito, or with Settings › Typing › Typing stats switched off (the service decides; see its `statsOn`).
 *
 * For the Stats page's pictures of the keyboard (Matt, 2026-11-11: "a stats page that shows the missed keys and key
 * shifting"; docs/stats.md) two more things are counted, for each layout typed on, and kept until the stats are
 * reset: which letter key was hit where its neighbour was meant ([slip]: "pressed>meant" and how often), and where
 * the taps that stood land about each key's middle ([landed]: two sums and a count for each key). Which key for
 * which, and where the thumb falls: still never a letter in its order, never a word.
 *
 * Every call comes from the main thread: the keyboard's callbacks and the settings channel.
 */
object TypingStats {
    private const val FILE = "typing_stats"
    private const val KEEP_DAYS = 30

    /** A pause longer than this is not time spent typing: the clock stops until the next key. */
    private const val IDLE_MS = 5000L

    private class Day(var chars: Int = 0, var words: Int = 0, var ms: Long = 0, var slips: Int = 0, var corrections: Int = 0, var swipes: Int = 0,
        var undone: Int = 0, var swipesChanged: Int = 0, var swipesDeleted: Int = 0)

    private var days: java.util.TreeMap<String, Day>? = null
    private val letters = IntArray(26)
    /** The Stats page's two pictures: slips between keys, and where taps land (KeyTallies.kt). */
    private val keys = KeyTallies()
    private var lastAt = 0L
    private var inWord = false
    /** The last thing counted was a typed character: a backspace now is a slip; another straight after is not. */
    private var lastTyped = false
    private var unsaved = 0

    private fun load(context: Context): java.util.TreeMap<String, Day> {
        days?.let { return it }
        val map = java.util.TreeMap<String, Day>()
        runCatching {
            val o = JSONObject(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("stats", "{}") ?: "{}")
            o.optJSONObject("days")?.let { d ->
                for (key in d.keys()) {
                    val a = d.getJSONArray(key)
                    map[key] = Day(a.optInt(0), a.optInt(1), a.optLong(2), a.optInt(3), a.optInt(4), a.optInt(5), a.optInt(6), a.optInt(7), a.optInt(8))
                }
            }
            o.optJSONArray("letters")?.let { a -> for (i in 0 until minOf(26, a.length())) letters[i] = a.optInt(i) }
            keys.read(o.optJSONObject("slips"), o.optJSONObject("landed"))
        }
        days = map
        return map
    }

    private fun today(context: Context): Day {
        val map = load(context)
        val key = LocalDate.now().toString()
        return map.getOrPut(key) {
            while (map.size >= KEEP_DAYS) map.remove(map.firstKey())
            Day()
        }
    }

    /** The time since the last key joins today's typing time, unless it was a pause. */
    private fun tick(day: Day) {
        val now = android.os.SystemClock.elapsedRealtime()
        val gap = now - lastAt
        if (lastAt != 0L && gap in 1 until IDLE_MS) day.ms += gap
        lastAt = now
    }

    private fun changed(context: Context) {
        if (++unsaved >= 40) flush(context)
    }

    /** [text] was typed by a key: its characters count, a word ends where a letter or digit is followed by something else. */
    fun typed(context: Context, text: String) {
        if (text.isEmpty()) return
        val day = today(context)
        tick(day)
        for (c in text) {
            day.chars++
            if (c.isLetterOrDigit()) {
                inWord = true
                val i = c.lowercaseChar() - 'a'
                if (i in 0..25) letters[i]++
            } else if (inWord) {
                inWord = false
                day.words++
            }
        }
        lastTyped = true
        changed(context)
    }

    /** Backspace: straight after a typed character it is a slip being fixed; held down, or again, it is editing. */
    fun backspace(context: Context) {
        val day = today(context)
        tick(day)
        if (lastTyped) day.slips++
        lastTyped = false
        changed(context)
    }

    /**
     * On [layout], [pressed] was hit where its neighbour [meant] was meant: taken back and typed again as the
     * neighbour, or swapped for it by autocorrect. The pair is counted, and nothing else of the moment.
     */
    fun slip(context: Context, layout: String, pressed: Char, meant: Char) {
        load(context)
        keys.slip(layout, pressed, meant)
        changed(context)
    }

    /**
     * A tap on [key] of [layout] stood (its word was finished with it in): it landed [dx] across and [dy] down from
     * the key's middle, in fractions of the key. Only the running sums are kept.
     */
    fun landed(context: Context, layout: String, key: Char, dx: Float, dy: Float) {
        load(context)
        keys.landed(layout, key, dx, dy)
    }

    /** A word's taps were counted ([landed], once for each): one change to write down, however many letters it had. */
    fun landedWord(context: Context) = changed(context)

    /** The slips counted on [layout]: "pressed>meant" and how often. */
    fun slipsOf(context: Context, layout: String): Map<String, Int> { load(context); return keys.slipsOf(layout) }

    /** Where the taps on [key] of [layout] land about its middle, and on how many taps; null with none counted. */
    fun landingOf(context: Context, layout: String, key: Char): Triple<Float, Float, Int>? { load(context); return keys.landingOf(layout, key) }

    /** How often [key] of [layout] was meant, and how many of those taps missed it (landed on a neighbour). */
    fun tapsOf(context: Context, layout: String, key: Char): Pair<Int, Int> { load(context); return keys.tapsOf(layout, key) }

    /** An autocorrection was undone: it was wrong. */
    fun undone(context: Context) { today(context).undone++; changed(context) }

    /** A swiped word was swapped for another of its readings in the row. */
    fun swipeChanged(context: Context) { today(context).swipesChanged++; changed(context) }

    /** A swiped word was deleted straight away with backspace. */
    fun swipeDeleted(context: Context) { today(context).swipesDeleted++; changed(context) }

    /** Autocorrect fixed a word. */
    fun correction(context: Context) {
        today(context).corrections++
        changed(context)
    }

    /** A word was swiped: it counts as typed, with the space that goes with it. */
    fun swiped(context: Context, word: String) {
        val day = today(context)
        tick(day)
        day.chars += word.length + 1
        day.words++
        day.swipes++
        for (c in word) { val i = c.lowercaseChar() - 'a'; if (i in 0..25) letters[i]++ }
        inWord = false
        lastTyped = false
        changed(context)
    }

    /**
     * A word was tapped in the row above the keys: a predicted next word, or the completion of one whose first
     * [typed] letters were counted as they were typed. The rest of it counts, with the space the tap puts after it,
     * and it is one word; no swipe.
     */
    fun picked(context: Context, word: String, typed: Int = 0) {
        val day = today(context)
        tick(day)
        day.chars += (word.length - typed).coerceAtLeast(0) + 1
        day.words++
        inWord = false
        lastTyped = false
        changed(context)
    }

    /** The field was left: the word in hand is over, and what was counted is written down. */
    fun flush(context: Context) {
        val map = days ?: return
        if (inWord) { inWord = false; today(context).words++ }
        lastAt = 0L
        lastTyped = false
        if (unsaved == 0) return
        unsaved = 0
        val d = JSONObject()
        for ((key, day) in map) d.put(key, JSONArray().put(day.chars).put(day.words).put(day.ms).put(day.slips).put(day.corrections).put(day.swipes).put(day.undone).put(day.swipesChanged).put(day.swipesDeleted))
        val l = JSONArray()
        letters.forEach { l.put(it) }
        val (s, where) = keys.write()
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("stats", JSONObject().put("days", d).put("letters", l).put("slips", s).put("landed", where).toString()).apply()
    }

    fun reset(context: Context) {
        days = java.util.TreeMap()
        letters.fill(0)
        keys.clear()
        lastAt = 0L; inWord = false; lastTyped = false; unsaved = 0
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove("stats").apply()
    }

    /** Words a minute, a word being five characters; 0 until there are ten seconds of typing to measure. */
    private fun wpm(chars: Int, ms: Long): Int = if (ms < 10_000) 0 else ((chars / 5.0) / (ms / 60_000.0)).roundToInt()

    /** How much of what was typed stood, as a percentage: slips and autocorrect's fixes are what didn't. */
    private fun accuracy(day: Day): Int = if (day.chars < 20) 0 else (100.0 * (1.0 - (day.slips + day.corrections).toDouble() / day.chars)).roundToInt().coerceIn(0, 100)

    /** Of autocorrect's fixes, the share that stood (not undone), as a percentage; 0 until there are five. */
    private fun kept(day: Day): Int = if (day.corrections < 5) 0 else (100.0 * (day.corrections - day.undone).coerceAtLeast(0) / day.corrections).roundToInt()

    /** Of swiped words, the share whose first reading stood (not swapped or deleted), as a percentage; 0 until there are five. */
    private fun swipesFirst(day: Day): Int = if (day.swipes < 5) 0 else
        (100.0 * (day.swipes - day.swipesChanged - day.swipesDeleted).coerceAtLeast(0) / day.swipes).roundToInt()

    /** What Settings › Typing › Typing stats shows: today, the last seven days, everything kept, and the busiest keys. */
    fun state(context: Context): Map<String, Any> {
        val map = load(context)
        val now = LocalDate.now()
        val t = map[now.toString()] ?: Day()
        val week = (6 downTo 0).map { back ->
            val date = now.minusDays(back.toLong())
            val d = map[date.toString()] ?: Day()
            mapOf("day" to date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), "words" to d.words, "wpm" to wpm(d.chars, d.ms),
                "accuracy" to accuracy(d), "minutes" to (d.ms / 60_000.0).roundToInt())
        }
        // The Stats page's own: the days in a row with typing up to today (or yesterday, before today's first word),
        // the fastest and the fullest day kept, and every letter's share of the keys pressed.
        var streak = 0
        var back = if ((map[now.toString()]?.chars ?: 0) > 0) 0L else 1L
        while ((map[now.minusDays(back).toString()]?.chars ?: 0) > 0) { streak++; back++ }
        val fastest = map.entries.filter { wpm(it.value.chars, it.value.ms) > 0 && it.value.words >= 20 }.maxByOrNull { wpm(it.value.chars, it.value.ms) }
        val fullest = map.entries.filter { it.value.words > 0 }.maxByOrNull { it.value.words }
        fun dayName(key: String) = runCatching { LocalDate.parse(key).let { "${it.dayOfMonth} ${it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}" } }.getOrDefault(key)
        val all = Day()
        for (d in map.values) { all.chars += d.chars; all.words += d.words; all.ms += d.ms; all.slips += d.slips; all.corrections += d.corrections; all.swipes += d.swipes
            all.undone += d.undone; all.swipesChanged += d.swipesChanged; all.swipesDeleted += d.swipesDeleted }
        val pressed = letters.sum()
        val top = if (pressed < 50) emptyList() else letters.indices.sortedByDescending { letters[it] }.take(6)
            .map { "${('a' + it).uppercaseChar()} ${(100.0 * letters[it] / pressed).roundToInt()}%" }
        return mapOf(
            "today" to mapOf(
                "chars" to t.chars, "words" to t.words, "wpm" to wpm(t.chars, t.ms), "accuracy" to accuracy(t),
                "slips" to t.slips, "corrections" to t.corrections, "swipes" to t.swipes, "minutes" to (t.ms / 60_000.0).roundToInt(),
                "correctionsKept" to kept(t), "swipesFirst" to swipesFirst(t),
            ),
            "week" to week,
            "all" to mapOf(
                "words" to all.words, "wpm" to wpm(all.chars, all.ms), "accuracy" to accuracy(all), "days" to map.values.count { it.chars > 0 },
                "minutes" to (all.ms / 60_000.0).roundToInt(),
                "corrections" to all.corrections, "correctionsKept" to kept(all), "swipes" to all.swipes, "swipesFirst" to swipesFirst(all),
                "slips" to all.slips, "undone" to all.undone, "chars" to all.chars,
            ),
            "topKeys" to top,
            "streak" to streak,
            "fastest" to (fastest?.let { mapOf("day" to dayName(it.key), "wpm" to wpm(it.value.chars, it.value.ms)) } ?: emptyMap<String, Any>()),
            "fullest" to (fullest?.let { mapOf("day" to dayName(it.key), "words" to it.value.words) } ?: emptyMap<String, Any>()),
            "pressed" to pressed,
            "letters" to (if (pressed < 50) emptyList() else letters.indices.sortedByDescending { letters[it] }.take(8)
                .map { mapOf("key" to ('a' + it).toString(), "count" to letters[it], "share" to (1000.0 * letters[it] / pressed).roundToInt() / 10.0) }),
        )
    }
}
