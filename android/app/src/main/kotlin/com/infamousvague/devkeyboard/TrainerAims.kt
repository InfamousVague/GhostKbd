package com.infamousvague.devkeyboard

/**
 * How the typing trainer moves the keys' touch areas (docs/trainer.md; Matt, 2026-11-11: "I want the keyboard to
 * slowly adapt each run to shift the touch areas"). Plain Kotlin with no Android in it.
 *
 * There is one model of where a key listens, [AdaptiveKeys]: a nudge for each letter key, in fractions of the key,
 * used by KeyboardView.adapt. Everyday typing moves a nudge only by slips, since it cannot know what a clean tap was
 * meant for. A trainer run knows what every tap was meant for, so it can measure the thing itself: where the taps
 * meant for a key land about its middle ([Measure]). The best place for a key to listen is that mean (a tap is then
 * read as whichever key's taps it is most like), so after each run the trainer moves the key's nudge one [step]
 * toward it. It writes the result into the same model ([AdaptiveKeys.trained]); it keeps no model of its own.
 *
 * The step is small and bounded, never a jump: at most [STEP] of a key a run, less while the measurement stands on few
 * taps, nothing until it stands on [MIN_SAMPLES], and never past [MAX_SHIFT] from the key's middle. Like every nudge
 * it then drifts back as Adaptive keys has all nudges drift.
 */
object TrainerAims {
    /** A key needs this many taps before it has a nudge: Adaptive keys' own rule ([AdaptiveKeys.MIN_SAMPLES]; TrainerTest holds the two together). */
    const val MIN_SAMPLES = 15

    /** A nudge is never more than this of a key: Adaptive keys' own bound ([AdaptiveKeys.MAX_SHIFT]). */
    const val MAX_SHIFT = 0.3f

    /** A nudge under this is none: Adaptive keys' own rule ([AdaptiveKeys.MIN_SHIFT]). */
    const val MIN_SHIFT = 0.02f

    /** The most one run moves a key's nudge, of a key: under two points of a 44-point key. Eight runs to cross the whole of [MAX_SHIFT]. */
    const val STEP = 0.04f

    /** A measurement is gone by in proportion to its taps: one on this many is trusted halfway. */
    const val TRUST = 20

    /** A measurement weighs at most this many taps, so it follows a thumb that changes: older taps give way once it holds more. */
    const val CAP = 300

    /** A nudge: across and down from the key's middle, in fractions of the key. */
    class Aim(val dx: Float, val dy: Float)

    /** Where the taps meant for a key have landed about its middle: their mean, on [n] taps (at most [CAP]); [total] is every tap ever counted. */
    class Measure(var n: Int = 0, var x: Float = 0f, var y: Float = 0f, var total: Int = 0) {
        /** [count] more taps, whose offsets add up to ([sumX], [sumY]). */
        fun add(count: Int, sumX: Double, sumY: Double) {
            if (count <= 0 || sumX.isNaN() || sumY.isNaN()) return
            val kept = minOf(n, (CAP - count).coerceAtLeast(0))
            x = ((x * kept + sumX) / (kept + count)).toFloat()
            y = ((y * kept + sumY) / (kept + count)).toFloat()
            n = minOf(kept + count, CAP)
            total = (total.toLong() + count).coerceAtMost(1_000_000L).toInt()
        }
    }

    /**
     * Where a nudge at ([dx], [dy]) goes after a run, given where the key's taps have landed ([meanX], [meanY], on
     * [samples] taps): a step toward that mean, no longer than [STEP], shorter while the taps are few, and none at
     * all under [MIN_SAMPLES].
     */
    fun step(dx: Float, dy: Float, meanX: Float, meanY: Float, samples: Int): Aim {
        val fromX = dx.coerceIn(-MAX_SHIFT, MAX_SHIFT)
        val fromY = dy.coerceIn(-MAX_SHIFT, MAX_SHIFT)
        if (samples < MIN_SAMPLES || meanX.isNaN() || meanY.isNaN()) return Aim(fromX, fromY)
        val trust = samples / (samples + TRUST).toFloat()
        var vx = (meanX.coerceIn(-MAX_SHIFT, MAX_SHIFT) - fromX) * trust
        var vy = (meanY.coerceIn(-MAX_SHIFT, MAX_SHIFT) - fromY) * trust
        val length = kotlin.math.hypot(vx, vy)
        if (length > STEP) { vx *= STEP / length; vy *= STEP / length }
        return Aim((fromX + vx).coerceIn(-MAX_SHIFT, MAX_SHIFT), (fromY + vy).coerceIn(-MAX_SHIFT, MAX_SHIFT))
    }

    /**
     * A run's steps, for every key the run has taps for ([touched]: the key, and how many): where each nudge goes
     * from where it stands now ([now]), given what the trainer has measured of that key over every run ([measured]).
     * [seen] is how many touches Adaptive keys' model has counted for the key before this run: a key the model would
     * still not use a nudge for (under [MIN_SAMPLES] touches, this run's among them) is only counted, so that when
     * its nudge is first used it is one step from the middle and no more. Only the keys that move are in the answer.
     */
    fun steps(touched: Map<Char, Int>, measured: Map<Char, Measure>, seen: (Char) -> Int, now: (Char) -> Aim): Map<Char, Aim> {
        val out = LinkedHashMap<Char, Aim>()
        for (key in touched.keys.sorted()) {
            val m = measured[key] ?: continue
            if (seen(key) + touched.getValue(key) < MIN_SAMPLES) continue
            val from = now(key)
            val to = step(from.dx, from.dy, m.x, m.y, m.n)
            if (kotlin.math.abs(to.dx - from.dx) >= MOVED || kotlin.math.abs(to.dy - from.dy) >= MOVED) out[key] = to
        }
        return out
    }

    /** A step smaller than this (of a key) is none. */
    private const val MOVED = 0.0005f

    /**
     * A nudge at ([dx], [dy]) with what the trainer gave it ([givenX], [givenY]) taken back out: as much of it as is
     * still there, and never past the key's middle (the nudge has drifted since, and everyday slips have moved it too).
     */
    fun takeBack(dx: Float, dy: Float, givenX: Float, givenY: Float): Aim {
        fun one(now: Float, given: Float): Float {
            if (given == 0f || now == 0f || (now > 0f) != (given > 0f)) return now
            val back = minOf(kotlin.math.abs(now), kotlin.math.abs(given))
            return if (now > 0f) now - back else now + back
        }
        return Aim(one(dx, givenX), one(dy, givenY))
    }
}
