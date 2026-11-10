package com.infamousvague.devkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The adaptive keys plugin (Matt, 2026-11-09: "learns common mistakes you make in typing and adjusts the touch area of
 * keys to correct for common mistakes made", with "a heatmap of mistake areas").
 *
 * What it learns, for each letter key on the letters page, is where you actually aim at it: the mean of your touches
 * meant for it, as an offset from its middle in fractions of the key (so it holds on a folded or unfolded screen).
 * Touches that were never corrected count once for their key; a touch you took back (backspace, then a neighbouring
 * letter) or one autocorrect swapped for a neighbouring letter counts [MISTAKE_WEIGHT] times for the letter you meant,
 * and is kept, up to [MAX_MISTAKES], for the heatmap.
 *
 * Using it: a touch goes to whichever of the key under it and that key's neighbours has its learned aim nearest
 * (KeyboardView.keyAt). An aim moves at most [MAX_SHIFT] of a key from the middle, and a key needs [MIN_SAMPLES]
 * touches before its aim counts, so a key is never taken over from its middle, and a new keyboard behaves as before.
 * Nothing is learned in password fields or while incognito.
 *
 * There is one model for each layout ([layout], Layouts.SPECS' ids): where a thumb aims at QWERTY's e says nothing of
 * where it aims at Russian's у, though they share a place. QWERTY's is the one every keyboard had before layouts.
 */
class AdaptiveKeys private constructor(private val context: Context, val layout: String) {
    companion object {
        const val MIN_SAMPLES = 15
        const val MAX_SHIFT = 0.35f
        const val MISTAKE_WEIGHT = 3
        const val MAX_MISTAKES = 400
        private const val FILE = "adaptive_keys"

        private val shared = HashMap<String, AdaptiveKeys>()

        /** The model for [layout], read from storage the first time it is asked for. */
        fun get(context: Context, layout: String = "qwerty"): AdaptiveKeys =
            synchronized(shared) { shared.getOrPut(layout) { AdaptiveKeys(context.applicationContext, layout) } }

        /** Where a layout's model is kept: QWERTY's where it always was, the others beside it. */
        private fun slot(layout: String) = if (layout == "qwerty") "model" else "model_$layout"

        /** Every layout's model that holds something, for Settings' counts and a typing profile's export. */
        fun all(context: Context): List<AdaptiveKeys> {
            val stored = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            return Layouts.SPECS.filter { stored.contains(slot(it.id)) || synchronized(shared) { shared.containsKey(it.id) } }
                .map { get(context, it.id) }.filter { it.aims.isNotEmpty() || it.mistakes.isNotEmpty() }
        }

        /** Settings › Adaptive keys › Forget what it learned: every layout's model, in memory and in storage. */
        fun resetAll(context: Context) {
            synchronized(shared) { shared.values.forEach { it.aims.clear(); it.mistakes.clear(); it.dirty = false } }
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    /** A key's learned aim: its mean offset from the middle, in fractions of the key, and how many touches made it. */
    class Aim(var dx: Float = 0f, var dy: Float = 0f, var n: Int = 0)

    /** A mistake, for the heatmap: where the touch landed (fractions of the letters' area) and the letters. */
    class Mistake(val x: Float, val y: Float, val pressed: Char, val meant: Char)

    private val aims = HashMap<Char, Aim>()
    val mistakes = ArrayDeque<Mistake>()
    private var dirty = false

    init { load() }

    private fun prefs() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun load() = runCatching {
        val o = JSONObject(prefs().getString(slot(layout), "{}")!!)
        o.optJSONObject("aims")?.let { a -> a.keys().forEach { k -> a.optJSONArray(k)?.let { v -> aims[k[0]] = Aim(v.getDouble(0).toFloat(), v.getDouble(1).toFloat(), v.getInt(2)) } } }
        o.optJSONArray("mistakes")?.let { m ->
            for (i in 0 until m.length()) m.optJSONArray(i)?.let { v -> mistakes.addLast(Mistake(v.getDouble(0).toFloat(), v.getDouble(1).toFloat(), v.getString(2)[0], v.getString(3)[0])) }
        }
    }

    /** Writes what changed; called when a word ends, so at most once a word. */
    fun save() {
        if (!dirty) return
        dirty = false
        val a = JSONObject()
        aims.forEach { (k, v) -> a.put(k.toString(), JSONArray().put(v.dx.toDouble()).put(v.dy.toDouble()).put(v.n)) }
        val m = JSONArray()
        mistakes.forEach { m.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble()).put(it.pressed.toString()).put(it.meant.toString())) }
        prefs().edit().putString(slot(layout), JSONObject().put("aims", a).put("mistakes", m).toString()).apply()
    }

    /** Where to aim for [c]: its learned offset, or none until it has [MIN_SAMPLES] touches; at most [MAX_SHIFT]. */
    fun offset(c: Char): Pair<Float, Float> {
        val a = aims[c.lowercaseChar()] ?: return 0f to 0f
        if (a.n < MIN_SAMPLES) return 0f to 0f
        return a.dx.coerceIn(-MAX_SHIFT, MAX_SHIFT) to a.dy.coerceIn(-MAX_SHIFT, MAX_SHIFT)
    }

    fun learnedKeys() = aims.count { it.value.n >= MIN_SAMPLES }

    /**
     * A touch meant for [c], [dx] and [dy] from its middle in fractions of the key: a running mean that settles
     * after a few hundred touches and still follows a change of grip.
     */
    fun hit(c: Char, dx: Float, dy: Float, weight: Int = 1) {
        if (!c.isLetter() || dx.isNaN() || dy.isNaN() || kotlin.math.abs(dx) > 1.5f || kotlin.math.abs(dy) > 1.5f) return
        val a = aims.getOrPut(c.lowercaseChar()) { Aim() }
        repeat(weight) {
            a.n++
            val rate = 1f / a.n.coerceAtMost(200)
            a.dx += (dx - a.dx) * rate
            a.dy += (dy - a.dy) * rate
        }
        dirty = true
    }

    // --- typing profiles (TypingProfile.kt) ------------------------------------------------------------------------

    /** Every learned aim, for an export. */
    fun aims(): Map<Char, Aim> = aims.toSortedMap()

    /** An aim from a typing profile, blended with what is here, each weighted by its touches. */
    fun importAim(c: Char, dx: Float, dy: Float, samples: Int) {
        val k = c.lowercaseChar()
        if (!k.isLetter() || samples <= 0) return
        val a = aims.getOrPut(k) { Aim() }
        val total = (a.n + samples).coerceAtMost(100_000)
        a.dx = (a.dx * a.n + dx.coerceIn(-1.5f, 1.5f) * samples) / (a.n + samples)
        a.dy = (a.dy * a.n + dy.coerceIn(-1.5f, 1.5f) * samples) / (a.n + samples)
        a.n = total
        dirty = true
    }

    /** Where this layout's letters sit, in key widths across and rows down, for slips that come without a point. */
    private val places: Map<Char, Pair<Float, Int>> get() = Layouts.spec(layout)?.positions.orEmpty()

    /** Whether [c] is a letter key of this layout: the only names a typing profile's aims and slips may carry. */
    fun hasKey(c: Char): Boolean = places.containsKey(c.lowercaseChar())

    /**
     * A slip from a typing profile, [count] times: it teaches [meant]'s aim, at the point given ([x], [y], fractions of
     * the letters' area, placed on the heatmap too) or, without one, part way toward [pressed] on this layout's board.
     */
    fun importSlip(pressed: Char, meant: Char, count: Int, x: Float?, y: Float?) {
        val p = pressed.lowercaseChar(); val m = meant.lowercaseChar()
        val pp = places[p] ?: return
        val mp = places[m] ?: return
        val n = count.coerceIn(1, 500)
        // Most of the way to the shared edge: where a slip between the two lands.
        hit(m, (pp.first - mp.first) * 0.6f, (pp.second - mp.second) * 0.6f, n * MISTAKE_WEIGHT)
        if (x != null && y != null) repeat(n.coerceAtMost(20)) { mistakes.addLast(Mistake(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), p, m)) }
        while (mistakes.size > MAX_MISTAKES) mistakes.removeFirst()
        dirty = true
    }

    /** A touch on [pressed] that was meant for [meant]: it teaches [meant]'s aim, and goes on the heatmap at ([x], [y]). */
    fun mistake(pressed: Char, meant: Char, dx: Float, dy: Float, x: Float, y: Float) {
        hit(meant, dx, dy, MISTAKE_WEIGHT)
        mistakes.addLast(Mistake(x, y, pressed.lowercaseChar(), meant.lowercaseChar()))
        while (mistakes.size > MAX_MISTAKES) mistakes.removeFirst()
        dirty = true
    }
}
