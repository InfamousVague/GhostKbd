package com.infamousvague.devkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The adaptive keys plugin (Matt, 2026-11-09: "learns common mistakes you make in typing and adjusts the touch area of
 * keys to correct for common mistakes made", with "a heatmap of mistake areas").
 *
 * What it keeps, for each letter key on the letters page, is a nudge: how far the place the key listens has moved
 * from its middle, in fractions of the key (so it holds on a folded or unfolded screen).
 *
 * The nudge is a pair of training wheels, not a new layout (Matt, 2026-11-09: "The correcting of the key placement
 * should be very slow and gradual and over time drift back to the defaults to get the user on the right track"):
 * - **Slow.** Only a slip moves a key: a touch you took back (backspace, then a neighbouring letter) or one autocorrect
 *   swapped for a neighbouring letter moves the letter you meant [LEARN] of the way toward where the slip landed. It
 *   takes about ten slips on a key before the difference can be felt. Each slip is kept, up to [MAX_MISTAKES], for the
 *   heatmap.
 * - **Back to the defaults.** Every clean touch on a key, one inside its own outline that was never corrected, lets
 *   it [RELAX] of the way back to its middle; and whatever you type, every nudge halves each [HALF_LIFE_DAYS] days.
 *   So a key helps most while you are slipping on it, and hands the letter back as your aim comes right. A touch that
 *   only counted because of the nudge (outside the key's own outline) neither moves it nor lets it back.
 *
 * Using it: a touch goes to whichever of the key under it and that key's neighbours has its nudged middle nearest
 * (KeyboardView.keyAt). A nudge is at most [MAX_SHIFT] of a key, and a key needs [MIN_SAMPLES] touches before it has
 * one, so a key is never taken over from its middle, and a new keyboard behaves as before. Nothing is learned in
 * password fields or while incognito.
 *
 * There is one model for each layout ([layout], Layouts.SPECS' ids): where a thumb aims at QWERTY's e says nothing of
 * where it aims at Russian's у, though they share a place. QWERTY's is the one every keyboard had before layouts.
 */
class AdaptiveKeys private constructor(private val context: Context, val layout: String) {
    companion object {
        const val MIN_SAMPLES = 15
        const val MAX_SHIFT = 0.3f
        const val MAX_MISTAKES = 400
        /** One slip moves its key this much of the way toward where the slip landed. */
        const val LEARN = 0.03f
        /** One clean touch lets its key this much of the way back to its middle. */
        const val RELAX = 1f / 500
        /** Every nudge halves in this many days, typing or not. */
        const val HALF_LIFE_DAYS = 14
        /** A nudge smaller than this (of a key) is no nudge: the key is back at its default. */
        const val MIN_SHIFT = 0.02f
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
            synchronized(shared) { shared.values.forEach { it.aims.clear(); it.mistakes.clear(); it.dirty = false; it.agedAt = System.currentTimeMillis() } }
            context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    /** A key's nudge: how far from its middle it listens, in fractions of the key, and how many touches it has seen. */
    class Aim(var dx: Float = 0f, var dy: Float = 0f, var n: Int = 0)

    /** A mistake, for the heatmap: where the touch landed (fractions of the letters' area) and the letters. */
    class Mistake(val x: Float, val y: Float, val pressed: Char, val meant: Char)

    private val aims = HashMap<Char, Aim>()
    val mistakes = ArrayDeque<Mistake>()
    private var dirty = false

    /** When the nudges were last aged ([age]); 0 until the first time. */
    private var agedAt = 0L

    init { load(); age() }

    /** The drift back to the defaults with time alone: every nudge halves each [HALF_LIFE_DAYS] days. Checked at most hourly. */
    private fun age() {
        val now = System.currentTimeMillis()
        val elapsed = now - agedAt
        if (agedAt == 0L || elapsed < 0) { agedAt = now; dirty = true; return }
        if (elapsed < 3_600_000L) return
        val keep = Math.pow(0.5, elapsed / (HALF_LIFE_DAYS * 86_400_000.0)).toFloat()
        aims.values.forEach { it.dx *= keep; it.dy *= keep }
        agedAt = now
        dirty = true
    }

    private fun prefs() = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun load() = runCatching {
        val o = JSONObject(prefs().getString(slot(layout), "{}")!!)
        agedAt = o.optLong("at", 0L)
        o.optJSONObject("aims")?.let { a -> a.keys().forEach { k -> a.optJSONArray(k)?.let { v -> aims[k[0]] = Aim(v.getDouble(0).toFloat(), v.getDouble(1).toFloat(), v.getInt(2)) } } }
        o.optJSONArray("mistakes")?.let { m ->
            for (i in 0 until m.length()) m.optJSONArray(i)?.let { v -> mistakes.addLast(Mistake(v.getDouble(0).toFloat(), v.getDouble(1).toFloat(), v.getString(2)[0], v.getString(3)[0])) }
        }
    }

    /** Writes what changed; called when a word ends, so at most once a word. */
    fun save() {
        age()
        if (!dirty) return
        dirty = false
        val a = JSONObject()
        aims.forEach { (k, v) -> a.put(k.toString(), JSONArray().put(v.dx.toDouble()).put(v.dy.toDouble()).put(v.n)) }
        val m = JSONArray()
        mistakes.forEach { m.put(JSONArray().put(it.x.toDouble()).put(it.y.toDouble()).put(it.pressed.toString()).put(it.meant.toString())) }
        prefs().edit().putString(slot(layout), JSONObject().put("aims", a).put("mistakes", m).put("at", agedAt).toString()).apply()
    }

    /**
     * Where [c] listens: its nudge, at most [MAX_SHIFT]; none until it has [MIN_SAMPLES] touches, and none once it has
     * drifted back under [MIN_SHIFT].
     */
    fun offset(c: Char): Pair<Float, Float> {
        val a = aims[c.lowercaseChar()] ?: return 0f to 0f
        if (a.n < MIN_SAMPLES) return 0f to 0f
        if (kotlin.math.abs(a.dx) < MIN_SHIFT && kotlin.math.abs(a.dy) < MIN_SHIFT) return 0f to 0f
        return a.dx.coerceIn(-MAX_SHIFT, MAX_SHIFT) to a.dy.coerceIn(-MAX_SHIFT, MAX_SHIFT)
    }

    /** The keys being helped now: those with a nudge. */
    fun learnedKeys() = aims.keys.count { offset(it) != (0f to 0f) }

    private fun usable(c: Char, dx: Float, dy: Float) =
        c.isLetter() && !dx.isNaN() && !dy.isNaN() && kotlin.math.abs(dx) <= 1.5f && kotlin.math.abs(dy) <= 1.5f

    /**
     * A touch meant for [c] that stood, [dx] and [dy] from its middle in fractions of the key. Inside the key's own
     * outline it was a clean touch: the key goes [RELAX] of the way back to its default. Outside it, the nudge is what
     * made it count, which says nothing either way.
     */
    fun hit(c: Char, dx: Float, dy: Float) {
        if (!usable(c, dx, dy)) return
        val a = aims.getOrPut(c.lowercaseChar()) { Aim() }
        if (a.n < 100_000) a.n++
        if (kotlin.math.abs(dx) <= 0.5f && kotlin.math.abs(dy) <= 0.5f) {
            a.dx -= a.dx * RELAX
            a.dy -= a.dy * RELAX
        }
        dirty = true
    }

    /** A slip meant for [c] that landed [dx], [dy] from its middle, [times] over: each moves the key [LEARN] of the way there. */
    private fun learn(c: Char, dx: Float, dy: Float, times: Int = 1) {
        if (!usable(c, dx, dy)) return
        val a = aims.getOrPut(c.lowercaseChar()) { Aim() }
        repeat(times) {
            if (a.n < 100_000) a.n++
            a.dx += (dx - a.dx) * LEARN
            a.dy += (dy - a.dy) * LEARN
        }
        dirty = true
    }

    // --- typing profiles (TypingProfile.kt) ------------------------------------------------------------------------

    /** Every learned aim, for an export. */
    fun aims(): Map<Char, Aim> = aims.toSortedMap()

    /** A nudge from a typing profile, blended with what is here, each weighted by its touches. It drifts back like any other. */
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

    /**
     * The typing trainer's part (Trainer.kt, TrainerAims.kt): [c] has had [taps] more touches meant for it, and where
     * the trainer worked out a step for it its nudge now stands at ([dx], [dy]) (null: it stays where it is). The
     * trainer knows what every tap of a run was meant for, so it steps a nudge toward where the key's taps land, a
     * little a run; the nudge is this model's all the same, and drifts back like any other.
     */
    fun trained(c: Char, taps: Int, dx: Float? = null, dy: Float? = null) {
        val k = c.lowercaseChar()
        if (!k.isLetter()) return
        val a = aims.getOrPut(k) { Aim() }
        a.n = (a.n + taps.coerceAtLeast(0)).coerceAtMost(100_000)
        if (dx != null && dy != null && !dx.isNaN() && !dy.isNaN()) { a.dx = dx.coerceIn(-MAX_SHIFT, MAX_SHIFT); a.dy = dy.coerceIn(-MAX_SHIFT, MAX_SHIFT) }
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
        learn(m, (pp.first - mp.first) * 0.6f, (pp.second - mp.second) * 0.6f, n.coerceAtMost(60))
        if (x != null && y != null) repeat(n.coerceAtMost(20)) { mistakes.addLast(Mistake(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), p, m)) }
        while (mistakes.size > MAX_MISTAKES) mistakes.removeFirst()
        dirty = true
    }

    /** A touch on [pressed] that was meant for [meant]: it teaches [meant]'s aim, and goes on the heatmap at ([x], [y]). */
    fun mistake(pressed: Char, meant: Char, dx: Float, dy: Float, x: Float, y: Float) {
        learn(meant, dx, dy)
        mistakes.addLast(Mistake(x, y, pressed.lowercaseChar(), meant.lowercaseChar()))
        while (mistakes.size > MAX_MISTAKES) mistakes.removeFirst()
        dirty = true
    }
}
