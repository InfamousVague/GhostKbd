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
 */
class AdaptiveKeys private constructor(private val context: Context) {
    companion object {
        const val MIN_SAMPLES = 15
        const val MAX_SHIFT = 0.35f
        const val MISTAKE_WEIGHT = 3
        const val MAX_MISTAKES = 400
        private const val FILE = "adaptive_keys"

        @Volatile private var shared: AdaptiveKeys? = null
        fun get(context: Context): AdaptiveKeys = shared ?: synchronized(this) { shared ?: AdaptiveKeys(context.applicationContext).also { shared = it } }
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
        val o = JSONObject(prefs().getString("model", "{}")!!)
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
        prefs().edit().putString("model", JSONObject().put("aims", a).put("mistakes", m).toString()).apply()
    }

    fun reset() {
        aims.clear(); mistakes.clear(); dirty = false
        prefs().edit().clear().apply()
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

    /** A touch on [pressed] that was meant for [meant]: it teaches [meant]'s aim, and goes on the heatmap at ([x], [y]). */
    fun mistake(pressed: Char, meant: Char, dx: Float, dy: Float, x: Float, y: Float) {
        hit(meant, dx, dy, MISTAKE_WEIGHT)
        mistakes.addLast(Mistake(x, y, pressed.lowercaseChar(), meant.lowercaseChar()))
        while (mistakes.size > MAX_MISTAKES) mistakes.removeFirst()
        dirty = true
    }
}
