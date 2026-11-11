package com.infamousvague.devkeyboard

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * What typing stats count for the Stats page's two pictures of the keyboard (TypingStats.kt keeps one of these with
 * its file; docs/stats.md): for each layout typed on, which letter key was hit where its neighbour was meant
 * ("pressed>meant" and how often), and where the taps that stood land about each key's middle (two sums and a count
 * for each key). Which key for which, and where the thumb falls: never a letter in its order, never a word.
 *
 * No Android here, so KeyTalliesTest can hold it to account.
 */
class KeyTallies {
    private val slips = HashMap<String, HashMap<String, Int>>()
    private val landings = HashMap<String, HashMap<Char, FloatArray>>()

    val isEmpty: Boolean get() = slips.values.all { it.isEmpty() } && landings.values.all { it.isEmpty() }

    /** On [layout], [pressed] was hit where its neighbour [meant] was meant. Past [MAX_PAIRS] pairs the rarest goes. */
    fun slip(layout: String, pressed: Char, meant: Char) {
        val p = pressed.lowercaseChar()
        val m = meant.lowercaseChar()
        if (p == m || !p.isLetter() || !m.isLetter()) return
        val pairs = slips.getOrPut(layout) { HashMap() }
        pairs["$p>$m"] = (pairs["$p>$m"] ?: 0) + 1
        if (pairs.size > MAX_PAIRS) pairs.entries.filter { it.key != "$p>$m" }.minByOrNull { it.value }?.let { pairs.remove(it.key) }
    }

    /**
     * A tap on [key] of [layout] stood: it landed [dx] across and [dy] down from the key's middle, in fractions of
     * the key. Only the running sums are kept, and past [MEMORY] taps they are scaled back as each new one comes, so
     * the mean follows a grip that has changed.
     */
    fun landed(layout: String, key: Char, dx: Float, dy: Float) {
        if (dx.isNaN() || dy.isNaN() || !key.isLetter()) return
        val a = landings.getOrPut(layout) { HashMap() }.getOrPut(key.lowercaseChar()) { FloatArray(3) }
        if (a[2] >= MEMORY) { val keep = (MEMORY - 1f) / a[2]; a[0] *= keep; a[1] *= keep; a[2] *= keep }
        a[0] += dx.coerceIn(-FURTHEST, FURTHEST)
        a[1] += dy.coerceIn(-FURTHEST, FURTHEST)
        a[2] += 1f
    }

    /** The slips counted on [layout]: "pressed>meant" and how often. */
    fun slipsOf(layout: String): Map<String, Int> = slips[layout]?.toMap().orEmpty()

    /** Where the taps on [key] of [layout] land about its middle, and on how many taps; null with none counted. */
    fun landingOf(layout: String, key: Char): Triple<Float, Float, Int>? {
        val a = landings[layout]?.get(key.lowercaseChar())?.takeIf { it[2] >= 1f } ?: return null
        return Triple(a[0] / a[2], a[1] / a[2], a[2].roundToInt())
    }

    /** How often [key] of [layout] was meant, and how many of those taps missed it (landed on a neighbour). */
    fun tapsOf(layout: String, key: Char): Pair<Int, Int> {
        val k = key.lowercaseChar()
        val missed = slips[layout]?.entries?.sumOf { (pair, n) -> if (pair[2] == k) n else 0 } ?: 0
        val stood = landings[layout]?.get(k)?.get(2)?.roundToInt() ?: 0
        return (stood + missed) to missed
    }

    fun clear() { slips.clear(); landings.clear() }

    /** As the stats file keeps them: the slips, and the landings. */
    fun write(): Pair<JSONObject, JSONObject> {
        val s = JSONObject()
        for ((layout, pairs) in slips) if (pairs.isNotEmpty()) s.put(layout, JSONObject().also { o -> for ((pair, n) in pairs) o.put(pair, n) })
        val where = JSONObject()
        for ((layout, keys) in landings) if (keys.isNotEmpty()) where.put(layout, JSONObject().also { o ->
            for ((key, a) in keys) o.put(key.toString(), JSONArray().put(a[0].toDouble()).put(a[1].toDouble()).put(a[2].toDouble()))
        })
        return s to where
    }

    /** Read back from the stats file, in place of what is held. Anything not in the shape [write] gives is left out. */
    fun read(slipsJson: JSONObject?, landedJson: JSONObject?) {
        clear()
        slipsJson?.let { all ->
            for (layout in all.keys()) {
                val pairs = all.optJSONObject(layout) ?: continue
                val into = slips.getOrPut(layout) { HashMap() }
                for (pair in pairs.keys()) if (pair.length == 3 && pair[1] == '>') pairs.optInt(pair).takeIf { it > 0 }?.let { into[pair] = it }
            }
        }
        landedJson?.let { all ->
            for (layout in all.keys()) {
                val keys = all.optJSONObject(layout) ?: continue
                val into = landings.getOrPut(layout) { HashMap() }
                for (key in keys.keys()) {
                    val a = keys.optJSONArray(key) ?: continue
                    val sx = a.optDouble(0, Double.NaN).toFloat()
                    val sy = a.optDouble(1, Double.NaN).toFloat()
                    val n = a.optDouble(2, 0.0).toFloat()
                    if (key.length == 1 && n >= 1f && !sx.isNaN() && !sy.isNaN()) into[key[0]] = floatArrayOf(sx, sy, n)
                }
            }
        }
    }

    companion object {
        /** A layout keeps no more pairs of keys than this. */
        const val MAX_PAIRS = 300
        /** A key's landing place is the mean of this many taps' worth at most. */
        const val MEMORY = 2000f
        /** A tap counts as no further from its key's middle than this, in keys. */
        const val FURTHEST = 1.5f
    }
}
