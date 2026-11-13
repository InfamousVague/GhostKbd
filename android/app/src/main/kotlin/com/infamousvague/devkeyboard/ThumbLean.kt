package com.infamousvague.devkeyboard

/**
 * Where this thumb is landing, in the text field being typed in: how far from the middle of the key it aims at its
 * taps fall, in key widths across ([x]) and down ([y]). EXPERIMENT: the forms still being measured are behind dials.
 */
class ThumbLean {
    /** The taps that have taught it: how many they count as, their misses summed each way (key widths), and the misses' squares. */
    private var n = 0f
    private var sumX = 0f
    private var sumY = 0f
    private var squares = 0f
    /** As they were before the last word that was settled, for [unlearn]. */
    private val was = FloatArray(4)

    /** The lean as it is estimated now, across and down, in key widths. */
    var x = 0f
        private set
    var y = 0f
        private set

    /** How many taps it holds. */
    val taps: Float get() = n

    /** A text field is entered: nothing is known of the thumb in it. */
    fun newField() {
        if (KEEP) return
        n = 0f; sumX = 0f; sumY = 0f; squares = 0f; x = 0f; y = 0f
        was.fill(0f)
    }

    /** [taps] to be read by the lean as it is estimated now: the same taps, where nothing is estimated. */
    fun read(taps: Taps): Taps {
        if (!ON) return taps
        if (FIX_X != 0f || FIX_Y != 0f) return Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, FIX_X, FIX_Y)
        var lx = x; var ly = y
        val w = taps.keyW
        if (AHEAD > 0 && w > 0f) {
            // The word in hand says something too, before it is read: each of its taps against the key nearest it
            // once the lean so far is off it. Nothing of this is kept: the word teaches when it is settled.
            val bar = taps.space
            for (pass in 1..AHEAD) {
                var m = n; var sx = sumX; var sy = sumY; var sq = squares
                for (i in taps.xs.indices) {
                    if (!taps.has(i)) continue
                    val px = taps.xs[i]; val py = taps.ys[i]
                    if (bar != null && py >= bar[1]) continue
                    var best = Float.MAX_VALUE; var bx = 0f; var by = 0f
                    for (c in taps.keys.values) {
                        val ex = px - lx * w - c.first; val ey = py - ly * w - c.second
                        val d = ex * ex + ey * ey
                        if (d < best) { best = d; bx = (px - c.first) / w; by = (py - c.second) / w }
                    }
                    m += 1f; sx += bx; sy += by; sq += bx * bx + by * by
                }
                val e = estimateOf(m, sx, sy, sq)
                lx = e.first; ly = e.second
            }
        }
        return if (lx == 0f && ly == 0f) taps else Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, lx, ly)
    }

    /** The scatter of one tap about where the thumb lands, each way, squared: [NOISE] until the field's own taps say. */
    private fun scatter(): Float = scatterOf(n, sumX, sumY, squares)
    private fun scatterOf(n: Float, sumX: Float, sumY: Float, squares: Float): Float {
        if (n <= 0f) return NOISE * NOISE
        val about = (squares - (sumX * sumX + sumY * sumY) / n).coerceAtLeast(0f) / 2f
        return (NOISE_TAPS * NOISE * NOISE + about) / (NOISE_TAPS + (n - 1f).coerceAtLeast(0f))
    }

    /**
     * A word has been settled, and its taps say where the thumb landed for it: [meant] is the key each tap is now
     * taken to have been meant for (0 where it has none), [typed] the key it typed.
     */
    fun learn(typed: CharArray, meant: CharArray, taps: Taps) {
        pass()
        val w = taps.keyW
        if (w <= 0f) return
        for (i in meant.indices) {
            if (!taps.has(i)) continue
            val at = taps.keys[meant[i]] ?: continue
            val weight = if (meant[i] == typed[i]) 1f else CHANGED
            if (weight <= 0f) continue
            val dx = (taps.xs[i] - at.first) / w
            val dy = (taps.ys[i] - at.second) / w
            // A tap this far from the key now read for it was no slip of the aim: two letters swapped, a misspelling put right.
            if (dx * dx + dy * dy > REACH * REACH) continue
            n += weight; sumX += weight * dx; sumY += weight * dy; squares += weight * (dx * dx + dy * dy)
        }
        // It holds no more than [MEMORY] taps: what is older fades, so that it follows a change of hand or grip.
        if (n > MEMORY) { val f = MEMORY / n; n *= f; sumX *= f; sumY *= f; squares *= f }
        estimate()
    }

    /** A word has been settled that teaches nothing (letters no list has, a letter missed or added). */
    fun pass() { was[0] = n; was[1] = sumX; was[2] = sumY; was[3] = squares }

    private fun estimate() { val e = estimateOf(n, sumX, sumY, squares); x = e.first; y = e.second }

    private fun estimateOf(n: Float, sumX: Float, sumY: Float, squares: Float): Pair<Float, Float> {
        if (n <= 0f) return 0f to 0f
        val mx = sumX / n; val my = sumY / n
        // How far the mean stands out of what scatter alone would make of it.
        val s2 = scatterOf(n, sumX, sumY, squares)
        val t2 = (mx * mx + my * my) / (s2 / n)
        val f = when (FORM) {
            0 -> if (t2 <= PRIOR) 0f else 1f - PRIOR / t2
            else -> {
                // Spike and slab: most thumbs land where they aim; one in ODDS + 1 leans, by about TAU each way.
                val r = n * TAU * TAU / s2
                val w = 1.0 / (1.0 + ODDS * (1.0 + r) * Math.exp(-0.5 * t2 * r / (1.0 + r)))
                (w * r / (1.0 + r)).toFloat()
            }
        }
        return mx * f to my * f
    }

    /** For the experiments' logs: the mean's T-squared. */
    fun debug() = "${n.toInt()}\t${if (n > 0f) (sumX * sumX + sumY * sumY) / n / scatter() else 0f}"

    /** The last word that was settled was not what was meant after all (its fix was taken back): it is as it was before. */
    fun unlearn() { n = was[0]; sumX = was[1]; sumY = was[2]; squares = was[3]; estimate() }

    companion object {
        @JvmField var ON = true
        @JvmField var PRIOR = 9f
        @JvmField var FORM = 2
        @JvmField var TAU = 0.3f
        @JvmField var ODDS = 16f
        @JvmField var NOISE = 0.3f
        @JvmField var NOISE_TAPS = 4f
        @JvmField var MEMORY = 100f
        @JvmField var REACH = 1.25f
        @JvmField var CHANGED = 1f
        @JvmField var KEEP = false
        @JvmField var FIX_X = 0f
        @JvmField var FIX_Y = 0f
        @JvmField var RARE = true
        @JvmField var AHEAD = 0
    }
}
