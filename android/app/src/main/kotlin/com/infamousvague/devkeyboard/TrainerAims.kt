package com.infamousvague.devkeyboard

/**
 * How the typing trainer moves the keys' touch areas (docs/trainer.md; Matt, 2026-11-11: "I want the keyboard to
 * slowly adapt each run to shift the touch areas"). Plain Kotlin with no Android in it.
 *
 * Where a key listens is a nudge: how far from its middle, in fractions of the key, which KeyboardView.adapt reads
 * touches by. Everyday typing ([AdaptiveKeys]) moves a nudge only by slips, since it cannot know what a clean tap was
 * meant for. A trainer run knows what every tap was meant for, so it can measure the thing itself: where the taps
 * meant for a key land about its middle ([Measure]). The best place for a key to listen is that mean (a tap is then
 * read as whichever key's taps it is most like), so each fix the trainer makes has the key one [step] nearer to it
 * than the last ([next]).
 *
 * The step is small and bounded, never a jump: at most [STEP] of a key a fix, less while the measurement stands on few
 * taps, nothing until it stands on [MIN_SAMPLES], and never past [MAX_SHIFT] from the key's middle. A fix is the
 * trainer's own until it is proven and taken ([FixTrial]); only then is it Adaptive keys' to use, and like every
 * nudge there it drifts back as Adaptive keys has all nudges drift.
 */
object TrainerAims {
    /** A key needs this many taps before it has a nudge: Adaptive keys' own rule ([AdaptiveKeys.MIN_SAMPLES]; TrainerTest holds the two together). */
    const val MIN_SAMPLES = 15

    /** A nudge is never more than this of a key: Adaptive keys' own bound ([AdaptiveKeys.MAX_SHIFT]). */
    const val MAX_SHIFT = 0.3f

    /** A nudge under this is none: Adaptive keys' own rule ([AdaptiveKeys.MIN_SHIFT]). */
    const val MIN_SHIFT = 0.02f

    /** The most one fix moves a key from where the last had it, of a key: under two points of a 44-point key. Eight fixes to cross the whole of [MAX_SHIFT]. */
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
     * Where a key at ([dx], [dy]) goes in the next fix, given where its taps have landed ([meanX], [meanY], on
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
     * The next fix: where each key listens in it, given where the last one had it ([from]) and what the trainer has
     * measured of each key over every run ([measured]). Every key whose measure stands on [MIN_SAMPLES] taps or more
     * takes one [step]; a key with fewer stays where it was, so that when it is first moved it is one step from where
     * it stood and no more. Only the keys that move are in the answer.
     */
    fun next(measured: Map<Char, Measure>, from: (Char) -> Aim): Map<Char, Aim> {
        val out = LinkedHashMap<Char, Aim>()
        for (key in measured.keys.sorted()) {
            val m = measured.getValue(key)
            val was = from(key)
            val to = step(was.dx, was.dy, m.x, m.y, m.n)
            if (kotlin.math.abs(to.dx - was.dx) >= MOVED || kotlin.math.abs(to.dy - was.dy) >= MOVED) out[key] = to
        }
        return out
    }

    /**
     * Where a key at ([dx], [dy]) listens once it is in use, by Adaptive keys' own rules ([AdaptiveKeys.offset]): at
     * its middle while it stands under [MIN_SHIFT] of a key off it both ways, and never past [MAX_SHIFT]. A fix is
     * worn by these rules, so that what a run tested is what everyday typing does once it is taken.
     */
    fun inUse(dx: Float, dy: Float): Pair<Float, Float> =
        if (dx.isNaN() || dy.isNaN() || (kotlin.math.abs(dx) < MIN_SHIFT && kotlin.math.abs(dy) < MIN_SHIFT)) 0f to 0f
        else dx.coerceIn(-MAX_SHIFT, MAX_SHIFT) to dy.coerceIn(-MAX_SHIFT, MAX_SHIFT)

    /** A step smaller than this (of a key) is none. */
    private const val MOVED = 0.0005f

    /**
     * A nudge of Adaptive keys' at ([dx], [dy]) with what the trainer gave it ([givenX], [givenY]) taken back out: as
     * much of it as is still there, and never past the key's middle (the nudge has drifted since, and everyday slips
     * have moved it too).
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
