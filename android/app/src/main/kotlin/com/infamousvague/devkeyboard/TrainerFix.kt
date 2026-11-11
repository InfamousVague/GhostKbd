package com.infamousvague.devkeyboard

/**
 * The typing trainer's test of its own fixes (docs/trainer.md; Matt, 2026-11-11: "make each run after the first test
 * a new fixes and a b test the accuracy and don't suggest switching until its proven we got a better result"). Plain
 * Kotlin with no Android in it.
 *
 * A **fix** is where the trainer would have each letter key listen: the keyboard as it is in everyday typing, with
 * every key that has enough taps moved a step nearer to where the taps meant for it land ([TrainerAims.next]). The
 * first run is typed on the keyboard as it is, and only measures. Each story run after it is typed on a new fix, a
 * step on from the last, and is an A/B test of it: the keyboard reads every touch of the run twice, as the fix reads
 * it (which is what is typed) and as the keyboard as it is would have ([KeyTap.asIs]), so the two are held against
 * each other on the very same taps ([FixRun]). No run is set against another run: a story, a tired thumb or a good
 * day is the same for both readings.
 *
 * A fix is the trainer's own. It is kept here, in the trainer's file, and worn only by the run that tests it, in the
 * trainer's own field: nothing a run finds changes everyday typing. The trainer suggests switching to a fix only once
 * it is [Verdict.PROVEN], and it becomes the keyboard's only when the person takes it (Trainer.apply): the fix the
 * runs tested ([testedAt]), never one made since that no run has typed on.
 *
 * Proven means, over the runs that tested a fix since the keyboard last changed (the latest [KEPT] of them):
 * - at least [MIN_RUNS] of them, so one good run decides nothing, and neither do two;
 * - of the taps the two read differently, so many more went the fix's way that chance alone would give a split that
 *   lopsided less than once in [ONE_IN] tries were the fix no better (a sign test, [Proof.chance]);
 * - the fix led steadily from run to run, and not on the strength of one of them: a thumb lands a little differently
 *   every time the phone is picked up, so a run's taps lean together, and a fix can win one run by luck as surely as
 *   a coin can come up heads. Each run's lead is weighed as a run ([lead]), and the leads have to be steadier than
 *   chance gives one time in a hundred (Student's t, [Proof.steady]);
 * - and the run just typed did not go against it.
 * The same held the other way round, on less (one in twenty), is [Verdict.WORSE]: the fix is dropped, and the next
 * starts again from the keyboard as it is. tool/trainer_sim.sh says how often each happens to a thumb with a lean,
 * to one with none, and to one whose lean is new every run; TrainerAdaptationTest holds each build to it.
 */
class FixTrial {
    /** Where the trial stands. NONE: no run has tested a fix since the keyboard last changed. */
    enum class Verdict { NONE, TESTING, PROVEN, WORSE }

    /**
     * One run that tested a fix: which fix, how many taps were read both ways, how many the keyboard as it is put
     * on their key ([asIs]) and how many the fix did ([fixed]), and the taps only one of them read right ([won]: the
     * fix; [lost]: the keyboard as it is).
     */
    class FixRun(val number: Int, val taps: Int, val asIs: Int, val fixed: Int, val won: Int, val lost: Int) {
        /**
         * How far the fix led in this run, as a run among runs: the taps it won less the taps it lost, over the
         * scatter chance alone would give that many taps read differently (their square root). A run with a few
         * such taps and a run with many then weigh alike where their taps split alike; 0 where none differed.
         */
        val lead: Float get() = if (won + lost == 0) 0f else (won - lost) / kotlin.math.sqrt((won + lost).toFloat())
    }

    /** The runs in [runs] added up. */
    class Sum(val runs: Int, val taps: Int, val asIs: Int, val fixed: Int, val won: Int, val lost: Int, val ahead: Int, val behind: Int)

    /** Which fix is in hand: the first one made is 1. 0: none has been made yet. */
    var number = 0
        private set

    /** The keys the fixes have moved, and where the one in hand has each (before the rules of use, [TrainerAims.inUse]). Any other key listens where the keyboard as it is has it. */
    val at = HashMap<Char, TrainerAims.Aim>()

    /** Whether a counted run has tested the fix in hand. True with none in hand: the next run makes one. */
    var tested = true
        private set

    /** Whether Adaptive keys was in use when this line of fixes began: what "the keyboard as it is" meant for it. */
    var adaptive = false
        private set

    /** The runs that tested a fix since the keyboard last changed, oldest first, at most [KEPT]. */
    val runs = ArrayList<FixRun>()

    /**
     * The fix the latest run tested, and where it had each key the fixes have moved. It is this fix a verdict speaks
     * of and a switch takes: the fix in hand may be a step on from it, made for a run that was then left, and no run
     * has tested that one. 0: none has been tested since the fixes last began.
     */
    var testedNumber = 0
        private set
    val testedAt = HashMap<Char, TrainerAims.Aim>()

    /** The fix everyday typing was last switched to, and the fix last dropped as worse; 0 for none. */
    var taken = 0
        private set
    var dropped = 0
        private set

    /** Where [c] listens in the fix in hand: where the fix has moved it, by the rules of use; else where the keyboard as it is has it ([asIs]). */
    fun offset(c: Char, asIs: (Char) -> Pair<Float, Float>): Pair<Float, Float> = at[c]?.let { TrainerAims.inUse(it.dx, it.dy) } ?: asIs(c)

    /** Where [c] listens in the fix the latest run tested ([testedAt]), by the same rules. */
    fun testedOffset(c: Char, asIs: (Char) -> Pair<Float, Float>): Pair<Float, Float> = testedAt[c]?.let { TrainerAims.inUse(it.dx, it.dy) } ?: asIs(c)

    /** The keys of [keys] that listen somewhere else in the fix in hand than on the keyboard as it is. */
    fun differing(keys: Collection<Char>, asIs: (Char) -> Pair<Float, Float>): Set<Char> = keys.filterTo(LinkedHashSet()) { c ->
        val f = offset(c, asIs)
        val k = asIs(c)
        kotlin.math.abs(f.first - k.first) >= APART || kotlin.math.abs(f.second - k.second) >= APART
    }

    /**
     * A story run begins. Where the fix in hand has been tested (or there is none yet), the next is made: every key
     * of [keys] that [measured] has enough taps for goes a step on from where it stood. A fix left untested (its run
     * was left, or not counted) is tested by this run instead, so a step is only ever taken on a run's evidence.
     *
     * [nowAdaptive] and [asIs] are the keyboard as it is at this moment: whether Adaptive keys is in use, and where
     * each key listens. Where Adaptive keys was added or taken away since the fixes began, they begin again from the
     * keyboard as it now is. [seed] is where a first fix starts from where that is not the keyboard as it is (the
     * steps an earlier version's runs had already given the keys).
     *
     * The answer is what the run wears: where every key of [keys] listens. Null where there is nothing to test: no
     * key has enough taps yet, or every key the fixes have moved is still too near where the keyboard has it to be
     * in use ([TrainerAims.inUse]), so the two would read every touch alike. Such a fix has no number, and goes a
     * step on at the next run all the same.
     */
    fun begin(nowAdaptive: Boolean, asIs: (Char) -> Pair<Float, Float>, keys: Collection<Char>, measured: Map<Char, TrainerAims.Measure>, seed: Map<Char, TrainerAims.Aim> = emptyMap()): Map<Char, Pair<Float, Float>>? {
        if ((number > 0 || at.isNotEmpty()) && nowAdaptive != adaptive) restart()
        adaptive = nowAdaptive
        var fresh = false
        if (number == 0 && at.isEmpty()) for ((c, a) in seed) if (c in keys) { at[c] = a; fresh = true }
        if (tested) {
            val moved = TrainerAims.next(measured.filterKeys { it in keys }) { c -> at[c] ?: asIs(c).let { TrainerAims.Aim(it.first, it.second) } }
            if (moved.isNotEmpty()) { at.putAll(moved); fresh = true }
        }
        if (differing(keys, asIs).isEmpty()) { tested = true; return null }
        if (fresh || number == 0) { number++; tested = false }
        return keys.associateWith { offset(it, asIs) }
    }

    /** This trial over again, to ask what a run started now would be typed on ([begin]) without starting one. */
    fun copy(): FixTrial = FixTrial().also { it.restore(number, tested, adaptive, taken, dropped, at, runs, testedNumber, testedAt) }

    /** A counted run tested the fix in hand and came to [run]. Where that proves the fix worse it is dropped, and the fixes begin again from the keyboard as it is. */
    fun record(run: FixRun): Verdict {
        tested = true
        testedNumber = number
        testedAt.clear(); testedAt.putAll(at)
        runs += run
        while (runs.size > KEPT) runs.removeAt(0)
        val v = verdict
        if (v == Verdict.WORSE) { dropped = number; restart() }
        return v
    }

    /**
     * The person took the fix the runs tested ([testedAt]): it is the keyboard as it is now, with Adaptive keys in
     * use, and what later fixes are held against. A fix made since for a run that was left stays in hand, untested,
     * for the next run.
     */
    fun took() {
        taken = testedNumber
        dropped = 0
        adaptive = true
        runs.clear()
    }

    /** The fixes begin again from the keyboard as it is: nothing moved, nothing tested. The count goes on, so a fix's number is never used twice. */
    private fun restart() {
        at.clear()
        runs.clear()
        tested = true
        testedNumber = 0
        testedAt.clear()
    }

    val sum: Sum get() = Sum(
        runs.size, runs.sumOf { it.taps }, runs.sumOf { it.asIs }, runs.sumOf { it.fixed }, runs.sumOf { it.won }, runs.sumOf { it.lost },
        runs.count { it.won > it.lost }, runs.count { it.lost > it.won },
    )

    val verdict: Verdict get() {
        if (runs.isEmpty()) return Verdict.NONE
        if (runs.size < MIN_RUNS) return Verdict.TESTING
        val s = sum
        val leads = FloatArray(runs.size) { runs[it].lead }
        val last = runs.last()
        if (last.won >= last.lost && Proof.chance(s.won, s.won + s.lost) * ONE_IN <= 1.0 && Proof.steady(leads, Proof.ONE_IN_100)) return Verdict.PROVEN
        for (i in leads.indices) leads[i] = -leads[i]
        if (last.lost >= last.won && Proof.chance(s.lost, s.won + s.lost) * ONE_IN_WORSE <= 1.0 && Proof.steady(leads, Proof.ONE_IN_20)) return Verdict.WORSE
        return Verdict.TESTING
    }

    /** What keeps a fix that is being tested from proven: too few runs yet, no lead at all, a lead chance could give, one that is not steady from run to run, or the run just typed going against it. */
    enum class Hold { RUNS, BEHIND, SPLIT, STEADY, LAST }

    /** Why the fix being tested is not proven yet, for the screen to say; null where it is proven, was dropped, or nothing has tested one. */
    val hold: Hold? get() {
        if (verdict != Verdict.TESTING) return null
        val s = sum
        return when {
            runs.size < MIN_RUNS -> Hold.RUNS
            s.won <= s.lost -> Hold.BEHIND
            Proof.chance(s.won, s.won + s.lost) * ONE_IN > 1.0 -> Hold.SPLIT
            !Proof.steady(FloatArray(runs.size) { runs[it].lead }, Proof.ONE_IN_100) -> Hold.STEADY
            else -> Hold.LAST
        }
    }

    /** The trial as it was kept (TrainerCodec). */
    fun restore(number: Int, tested: Boolean, adaptive: Boolean, taken: Int, dropped: Int, at: Map<Char, TrainerAims.Aim>, runs: List<FixRun>, testedNumber: Int = 0, testedAt: Map<Char, TrainerAims.Aim> = emptyMap()) {
        fun kept(a: TrainerAims.Aim) = TrainerAims.Aim(a.dx.coerceIn(-TrainerAims.MAX_SHIFT, TrainerAims.MAX_SHIFT), a.dy.coerceIn(-TrainerAims.MAX_SHIFT, TrainerAims.MAX_SHIFT))
        this.testedNumber = testedNumber.coerceIn(0, number.coerceAtLeast(0))
        this.testedAt.clear()
        if (this.testedNumber > 0) for ((c, a) in testedAt) if (!a.dx.isNaN() && !a.dy.isNaN()) this.testedAt[c] = kept(a)
        this.number = number.coerceAtLeast(0)
        this.tested = tested || this.number == 0
        this.adaptive = adaptive
        this.taken = taken.coerceIn(0, this.number)
        this.dropped = dropped.coerceIn(0, this.number)
        this.at.clear()
        for ((c, a) in at) if (!a.dx.isNaN() && !a.dy.isNaN()) this.at[c] = kept(a)
        // Kept without saying which fix the runs tested, with the fix in hand tested: it is that one.
        if (this.testedNumber == 0 && this.tested && this.number > 0 && runs.isNotEmpty()) { this.testedNumber = this.number; this.testedAt.putAll(this.at) }
        this.runs.clear()
        for (r in runs.takeLast(KEPT)) {
            val taps = r.taps.coerceAtLeast(0)
            val won = r.won.coerceIn(0, taps)
            this.runs += FixRun(r.number.coerceAtLeast(0), taps, r.asIs.coerceIn(0, taps), r.fixed.coerceIn(0, taps), won, r.lost.coerceIn(0, taps - won))
        }
    }

    companion object {
        /** A fix is never proven, or dropped, on fewer runs than this. */
        const val MIN_RUNS = 3

        /** The runs a verdict is read from: the latest this many that tested a fix, so it follows the thumb as it is now. */
        const val KEPT = 8

        /**
         * Proven: were the fix no better, chance would split the taps the two read differently this far the fix's
         * way less than once in this many tries. Dropped as worse on less: [ONE_IN_WORSE], since keeping a fix that
         * reads worse costs the person every run it is worn.
         */
        const val ONE_IN = 100
        const val ONE_IN_WORSE = 20

        /** Two places for a key to listen nearer than this (of a key) are one place. */
        const val APART = 0.005f
    }
}

/**
 * The two tests the trainer's verdicts stand on ([FixTrial], [LayoutTrials]), with no Android in them. Neither is the
 * trainer's own invention: a sign test, and Student's t.
 */
object Proof {
    /**
     * The chance of [k] or more heads in [n] tosses of a fair coin: how often chance alone would split [n] things
     * that could each go either way at least this far one way. 1 where there is nothing to split.
     */
    fun chance(k: Int, n: Int): Double {
        if (n <= 0 || k <= 0) return 1.0
        if (k > n) return 0.0
        // Each term from the one before it, in logs: n runs to a few thousand at most.
        var logTerm = -n * Math.log(2.0)
        var total = 0.0
        for (i in 0..n) {
            if (i >= k) total += Math.exp(logTerm)
            if (i < n) logTerm += Math.log((n - i).toDouble()) - Math.log((i + 1).toDouble())
        }
        return total.coerceIn(0.0, 1.0)
    }

    /**
     * Student's t for whether the mean of [values] is above nothing: the mean over its own standard error. Values
     * that are all the same are as steady as a lead gets (or as a deficit, or nothing at all).
     */
    fun student(values: FloatArray): Float {
        if (values.size < 2 || values.any { it.isNaN() || it.isInfinite() }) return 0f
        val mean = values.average()
        val spread = Math.sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
        if (spread < 1e-9) return if (mean > 1e-9) Float.POSITIVE_INFINITY else if (mean < -1e-9) Float.NEGATIVE_INFINITY else 0f
        return (mean / (spread / Math.sqrt(values.size.toDouble()))).toFloat()
    }

    /**
     * How large Student's t has to be before chance gives one like it less than once in twenty tries, in a hundred,
     * and in two hundred (one-sided), by how many values it stands on less one. Past the table's end the last holds,
     * which asks a little more than it need.
     */
    val ONE_IN_20 = floatArrayOf(Float.POSITIVE_INFINITY, 6.314f, 2.920f, 2.353f, 2.132f, 2.015f, 1.943f, 1.895f, 1.860f, 1.833f)
    val ONE_IN_100 = floatArrayOf(Float.POSITIVE_INFINITY, 31.821f, 6.965f, 4.541f, 3.747f, 3.365f, 3.143f, 2.998f, 2.896f, 2.821f)
    val ONE_IN_200 = floatArrayOf(Float.POSITIVE_INFINITY, 63.657f, 9.925f, 5.841f, 4.604f, 4.032f, 3.707f, 3.499f, 3.355f, 3.250f)

    /** Whether [values] stand above nothing more steadily than chance gives at [level] (one of the tables above). */
    fun steady(values: FloatArray, level: FloatArray): Boolean = values.size >= 2 && student(values) >= level[(values.size - 1).coerceAtMost(level.size - 1)]
}
