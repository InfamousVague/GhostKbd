package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The trainer's adapting, held to what it is for (Matt, 2026-11-11: "I want the keyboard to slowly adapt each run to
 * shift the touch areas and try different layouts"): a thumb that always lands off the keys' middles the same way
 * makes fewer mistakes as the runs go by with the steps, and does not without them ([TrainerSim]); and the layout
 * trials find the layout a person does better on, and leave a person alone who does no better on any.
 *
 * Each of these could fail: the steps going the wrong way, too slowly to matter, or past their bounds would each
 * show here. tool/trainer_sim.sh prints the same courses of runs as a table.
 */
class TrainerAdaptationTest {
    companion object {
        const val RUNS = 12
        const val THUMBS = 6
        private val parts: List<String> by lazy { TrainerStoriesTest.stories.flatMap { it.parts } }

        /** Taps on the wrong key and words wrong after autocorrect, in a hundred, run by run: the mean of [THUMBS] thumbs' courses. */
        class Curve(val keys: FloatArray, val words: FloatArray) {
            fun first(a: FloatArray) = a.take(2).average().toFloat()
            fun last(a: FloatArray) = a.takeLast(4).average().toFloat()
        }

        fun courses(thumb: TrainerSim.Thumb, adapting: Boolean, each: (TrainerSim.Course) -> Unit = {}): Curve {
            val keys = FloatArray(RUNS)
            val words = FloatArray(RUNS)
            for (seed in 1..THUMBS) {
                val course = TrainerSim.Course(adapting)
                // The same seed for both: the two courses are typed on the very same touches.
                val random = Random(seed * 7919L)
                for (r in 0 until RUNS) {
                    val out = course.run(parts[(seed * 5 + r) % parts.size], thumb, random, TypingAccuracyTest.ac)
                    keys[r] += out.keyErrors / THUMBS
                    words[r] += out.wordErrors / THUMBS
                    each(course)
                }
            }
            return Curve(keys, words)
        }
    }

    @Test fun aThumbWithASteadyLeanMakesFewerMistakesAsTheRunsGoByWithTheStepsAndNotWithoutThem() {
        // A quarter of a key to the right and a fifth of one low, every time.
        val thumb = TrainerSim.Thumb(0.24f, 0.18f)
        val drawn = courses(thumb, adapting = false)
        val stepped = courses(thumb, adapting = true)
        // Without the steps nothing gets better by itself.
        assertTrue("as drawn: ${drawn.first(drawn.keys)} to ${drawn.last(drawn.keys)}", drawn.last(drawn.keys) > drawn.first(drawn.keys) * 0.85f)
        // With them the wrong keys fall by well over a third, against where they started and against the keys as drawn.
        assertTrue("with steps: ${stepped.first(stepped.keys)} to ${stepped.last(stepped.keys)}", stepped.last(stepped.keys) < stepped.first(stepped.keys) * 0.6f)
        assertTrue("${stepped.last(stepped.keys)} against ${drawn.last(drawn.keys)}", stepped.last(stepped.keys) < drawn.last(drawn.keys) * 0.6f)
        // And fewer words come out wrong, autocorrect and all.
        assertTrue("words: ${stepped.last(stepped.words)} against ${drawn.last(drawn.words)}", stepped.last(stepped.words) < drawn.last(drawn.words) * 0.8f)
        // Slowly: the first run is no better (nothing has moved yet), and the third has not caught up with the last.
        assertEquals(drawn.keys[0], stepped.keys[0], 1.5f)
        assertTrue(stepped.keys[2] > stepped.last(stepped.keys) * 1.4f)
    }

    @Test fun theSameHoldsForALeanTheOtherWay() {
        val thumb = TrainerSim.Thumb(-0.2f, -0.15f)
        val drawn = courses(thumb, adapting = false)
        val stepped = courses(thumb, adapting = true)
        assertTrue("as drawn: ${drawn.first(drawn.keys)} to ${drawn.last(drawn.keys)}", drawn.last(drawn.keys) > drawn.first(drawn.keys) * 0.85f)
        assertTrue("${stepped.last(stepped.keys)} against ${drawn.last(drawn.keys)}", stepped.last(stepped.keys) < drawn.last(drawn.keys) * 0.7f)
    }

    @Test fun aThumbWithNoLeanIsLeftAsItWas() {
        val thumb = TrainerSim.Thumb(0f, 0f)
        val drawn = courses(thumb, adapting = false)
        var far = 0f
        val stepped = courses(thumb, adapting = true) { c -> far = maxOf(far, c.aims.at.values.maxOfOrNull { maxOf(kotlin.math.abs(it.dx), kotlin.math.abs(it.dy)) } ?: 0f) }
        // Nothing to learn: the steps do no harm, and no key is moved far by the scatter alone.
        assertTrue("${stepped.last(stepped.keys)} against ${drawn.last(drawn.keys)}", stepped.last(stepped.keys) < drawn.last(drawn.keys) * 1.15f + 0.3f)
        assertTrue("the furthest a nudge went on scatter alone: $far", far < 0.15f)
    }

    @Test fun noKeyEverMovesMoreThanAStepInARunOrPastItsBound() {
        val before = HashMap<Char, TrainerAims.Aim>()
        var runs = 0
        var moved = 0
        courses(TrainerSim.Thumb(0.45f, -0.4f), adapting = true) { c ->
            // A new thumb's course starts with nothing learned.
            if (c.outcomes.size == 1) before.clear()
            for ((key, aim) in c.aims.at) {
                val was = before[key] ?: TrainerAims.Aim(0f, 0f)
                val step = kotlin.math.hypot(aim.dx - was.dx, aim.dy - was.dy)
                assertTrue("$key moved $step in one run", step <= TrainerAims.STEP + 0.0005f)
                assertTrue(kotlin.math.abs(aim.dx) <= TrainerAims.MAX_SHIFT + 0.0005f && kotlin.math.abs(aim.dy) <= TrainerAims.MAX_SHIFT + 0.0005f)
                if (step > 0f) moved++
                before[key] = aim
            }
            // A key is not listened for anywhere new until it has its fifteen touches.
            for (key in c.aims.at.keys) if ((c.aims.seen[key] ?: 0) < TrainerAims.MIN_SAMPLES) assertEquals(0f to 0f, c.aims.offset(key))
            runs++
        }
        assertEquals(RUNS * THUMBS, runs)
        assertTrue(moved > 200)
    }

    // --- layouts ---------------------------------------------------------------------------------------------------------

    /**
     * [people] people each type [runs] trial runs. A person's score on a layout is [worth] of it, give or take the
     * [noise] a real run has; all start from Normal. Where each one's trials stand at the end.
     */
    private fun trials(people: Int, runs: Int, noise: Float, worth: (String) -> Float): List<LayoutTrials> = (1..people).map { person ->
        val random = Random(person * 104_729L)
        val t = LayoutTrials()
        t.anchor("normal")
        repeat(runs) { val h = t.next(); t.record(h, worth(h) * (1f + noise * random.nextGaussian().toFloat())) }
        t
    }

    @Test fun theTrialsFindTheLayoutAPersonDoesBetterOn() {
        // Short is worth a tenth more than Normal to these people, Condensed and Tall a little less than Normal.
        val ended = trials(people = 300, runs = 48, noise = 0.05f) { mapOf("condensed" to 39f, "short" to 44f, "normal" to 40f, "tall" to 38f).getValue(it) }
        val onShort = ended.count { it.home == "short" }
        assertTrue("$onShort of 300 ended on the better layout", onShort >= 300 * 0.8)
        assertTrue("none on the worse ones", ended.count { it.home == "tall" } <= 300 * 0.02)
        assertTrue(ended.filter { it.home == "short" }.all { it.suggestion()?.height == "short" && it.own == "normal" })
    }

    @Test fun whereNoLayoutIsBetterMostPeopleAreLeftOnTheirOwn() {
        val ended = trials(people = 300, runs = 48, noise = 0.05f) { 40f }
        val stayed = ended.count { it.home == "normal" }
        // The runs are noisy and nothing is at stake between layouts that are worth the same: most stay, none goes far.
        assertTrue("$stayed of 300 stayed", stayed >= 300 * 0.75)
        assertTrue(ended.all { kotlin.math.abs(LayoutTrials.HEIGHTS.indexOf(it.home) - 2) <= 1 || it.scores.getValue(it.home).size >= LayoutTrials.MIN_PAIRS })
        assertTrue("settled, not trying for ever", ended.count { it.settled } >= 300 * 0.8)
    }
}
