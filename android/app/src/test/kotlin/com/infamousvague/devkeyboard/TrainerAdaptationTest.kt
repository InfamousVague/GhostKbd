package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The trainer's adapting, held to what it is for (Matt, 2026-11-11: "I want the keyboard to slowly adapt each run to
 * shift the touch areas and try different layouts", then "make each run after the first test a new fixes and a b test
 * the accuracy and don't suggest switching until its proven we got a better result"): a thumb that always lands off
 * the keys' middles the same way makes fewer mistakes as the runs go by typed on the trainer's fixes, and does not
 * without them ([TrainerSim]); the test of those fixes calls them proven for that thumb, and for no thumb they do
 * nothing for; and the layout trials find the layout a person does better on, and leave a person alone who does no
 * better on any.
 *
 * Each of these could fail: the fixes going the wrong way, too slowly to matter, or past their bounds would each
 * show here, and so would a test that calls anything proven, or nothing. tool/trainer_sim.sh prints the same courses
 * of runs as tables.
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

        fun courses(thumb: TrainerSim.Thumb, fixing: Boolean, each: (TrainerSim.Course) -> Unit = {}): Curve {
            val keys = FloatArray(RUNS)
            val words = FloatArray(RUNS)
            for (seed in 1..THUMBS) {
                val course = TrainerSim.Course(fixing)
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

    @Test fun aThumbWithASteadyLeanMakesFewerMistakesAsTheRunsGoByOnTheFixesAndNotWithoutThem() {
        // A quarter of a key to the right and a fifth of one low, every time.
        val thumb = TrainerSim.Thumb(0.24f, 0.18f)
        val drawn = courses(thumb, fixing = false)
        val stepped = courses(thumb, fixing = true)
        // Without the steps nothing gets better by itself.
        assertTrue("as drawn: ${drawn.first(drawn.keys)} to ${drawn.last(drawn.keys)}", drawn.last(drawn.keys) > drawn.first(drawn.keys) * 0.85f)
        // With them the wrong keys fall by well over a third, against where they started and against the keys as drawn.
        assertTrue("with steps: ${stepped.first(stepped.keys)} to ${stepped.last(stepped.keys)}", stepped.last(stepped.keys) < stepped.first(stepped.keys) * 0.6f)
        assertTrue("${stepped.last(stepped.keys)} against ${drawn.last(drawn.keys)}", stepped.last(stepped.keys) < drawn.last(drawn.keys) * 0.6f)
        // And fewer words come out wrong, autocorrect and all.
        assertTrue("words: ${stepped.last(stepped.words)} against ${drawn.last(drawn.words)}", stepped.last(stepped.words) < drawn.last(drawn.words) * 0.8f)
        // Slowly: the first run is the very same (it is typed on the keyboard as it is), and the third has not caught up with the last.
        assertEquals(drawn.keys[0], stepped.keys[0], 0.001f)
        assertTrue(stepped.keys[2] > stepped.last(stepped.keys) * 1.4f)
    }

    @Test fun theSameHoldsForALeanTheOtherWay() {
        val thumb = TrainerSim.Thumb(-0.2f, -0.15f)
        val drawn = courses(thumb, fixing = false)
        val stepped = courses(thumb, fixing = true)
        assertTrue("as drawn: ${drawn.first(drawn.keys)} to ${drawn.last(drawn.keys)}", drawn.last(drawn.keys) > drawn.first(drawn.keys) * 0.85f)
        assertTrue("${stepped.last(stepped.keys)} against ${drawn.last(drawn.keys)}", stepped.last(stepped.keys) < drawn.last(drawn.keys) * 0.7f)
    }

    @Test fun aThumbWithNoLeanIsLeftAsItWas() {
        val thumb = TrainerSim.Thumb(0f, 0f)
        val drawn = courses(thumb, fixing = false)
        var far = 0f
        val stepped = courses(thumb, fixing = true) { c -> far = maxOf(far, c.trial.at.values.maxOfOrNull { maxOf(kotlin.math.abs(it.dx), kotlin.math.abs(it.dy)) } ?: 0f) }
        // Nothing to learn: the steps do no harm, and no key is moved far by the scatter alone.
        assertTrue("${stepped.last(stepped.keys)} against ${drawn.last(drawn.keys)}", stepped.last(stepped.keys) < drawn.last(drawn.keys) * 1.15f + 0.3f)
        assertTrue("the furthest a nudge went on scatter alone: $far", far < 0.15f)
    }

    @Test fun noKeyEverMovesMoreThanAStepInARunOrPastItsBound() {
        val before = HashMap<Char, TrainerAims.Aim>()
        var runs = 0
        var moved = 0
        var dropped = 0
        courses(TrainerSim.Thumb(0.45f, -0.4f), fixing = true) { c ->
            // A new thumb's course starts with nothing moved, and so do the fixes after one was dropped as worse.
            if (c.outcomes.size == 1 || c.worse != dropped) before.clear()
            dropped = c.worse
            for ((key, aim) in c.trial.at) {
                val was = before[key] ?: TrainerAims.Aim(0f, 0f)
                val step = kotlin.math.hypot(aim.dx - was.dx, aim.dy - was.dy)
                assertTrue("$key moved $step in one run", step <= TrainerAims.STEP + 0.0005f)
                assertTrue(kotlin.math.abs(aim.dx) <= TrainerAims.MAX_SHIFT + 0.0005f && kotlin.math.abs(aim.dy) <= TrainerAims.MAX_SHIFT + 0.0005f)
                if (step > 0f) moved++
                before[key] = aim
                // A key is not moved at all until the trainer has measured it on its fifteen taps.
                assertTrue("$key was moved on too few taps", c.book.aims.getValue("qwerty").getValue(key).total >= TrainerAims.MIN_SAMPLES)
            }
            runs++
        }
        assertEquals(RUNS * THUMBS, runs)
        assertTrue(moved > 200)
    }

    // --- the test of the fixes -------------------------------------------------------------------------------------------

    /** [thumbs] thumbs of one kind, twelve runs each with every run after the first testing a fix: each one's course. Autocorrect is left out: it changes no tap. */
    private fun tested(thumb: TrainerSim.Thumb, thumbs: Int, taking: Boolean = false): List<TrainerSim.Course> = (1..thumbs).map { seed ->
        val course = TrainerSim.Course(fixing = true, taking = taking)
        val random = Random(seed * 15_485_863L + 11)
        for (r in 0 until RUNS) course.run(parts[(seed * 5 + r) % parts.size], thumb, random, null)
        course
    }

    @Test fun everyRunAfterTheFirstTestsAFixAndReadsItsTapsBothWays() {
        val course = tested(TrainerSim.Thumb(0.24f, 0.18f), 1).single()
        val results = course.outcomes.map { it.result }
        // The first run is typed on the keyboard as it is: one reading, nothing won or lost, no verdict.
        assertEquals(results[0].hits, results[0].asIs); assertEquals(0, results[0].won + results[0].lost)
        assertEquals("the eleven runs after it each tested a fix", RUNS - 1, course.verdicts.size)
        assertEquals("a new one each run", RUNS - 1, course.trial.number)
        // The fix's reading is what was typed; the keyboard's own reading of the same taps is its accuracy as it is.
        for (r in results.drop(1)) assertEquals(r.hits - r.asIs, r.won - r.lost)
        assertTrue("by the last runs the fix reads far more of this thumb's taps right", results.takeLast(4).sumOf { it.won } > 6 * results.takeLast(4).sumOf { it.lost })
        // The same touches typed on the keyboard as it is come to what the second reading said they would.
        val plain = TrainerSim.Course(fixing = false)
        val random = Random(1 * 15_485_863L + 11)
        for (r in 0 until RUNS) plain.run(parts[(5 + r) % parts.size], TrainerSim.Thumb(0.24f, 0.18f), random, null)
        val typed = plain.outcomes.drop(1).sumOf { it.result.taps - it.result.hits }
        val read = results.drop(1).sumOf { it.taps - it.asIs }
        assertTrue("wrong keys as it is: $typed typed, $read read back", kotlin.math.abs(typed - read) <= typed / 20 + 2)
    }

    @Test fun aSteadyLeanIsProvenWithinAFewRunsAndNotOnOne() {
        val thumbs = tested(TrainerSim.Thumb(0.24f, 0.18f), 40)
        assertTrue("never before the fourth run: three runs have to have tested a fix", thumbs.all { it.provenAt == 0 || it.provenAt >= 1 + FixTrial.MIN_RUNS })
        assertTrue("${thumbs.count { it.provenAt in 1..8 }} of 40 proven by the eighth run", thumbs.count { it.provenAt in 1..8 } >= 36)
        assertEquals("all of them by the twelfth", 40, thumbs.count { it.provenAt > 0 })
        assertEquals("and none had a fix dropped", 0, thumbs.count { it.worse > 0 })
        // The other way, and a smaller lean more slowly.
        val left = tested(TrainerSim.Thumb(-0.2f, -0.15f), 40)
        assertTrue("${left.count { it.provenAt > 0 }} of 40", left.count { it.provenAt > 0 } >= 38)
        val small = tested(TrainerSim.Thumb(0.1f, 0.06f), 60)
        assertTrue("a small lean: ${small.count { it.provenAt > 0 }} of 60 in twelve runs", small.count { it.provenAt > 0 } in 24..54)
        assertTrue("and later", small.filter { it.provenAt > 0 }.map { it.provenAt }.average() > thumbs.map { it.provenAt }.average() + 1.0)
    }

    @Test fun aThumbTheFixesDoNothingForIsNotToldToSwitch() {
        // No lean at all: the fixes follow the scatter, and read no better.
        val none = tested(TrainerSim.Thumb(0f, 0f), 150)
        assertTrue("${none.count { it.provenAt > 0 }} of 150 thumbs with no lean were told a fix was proven", none.count { it.provenAt > 0 } <= 2)
        // A lean that is new every run (the phone held another way each time): a fix made from the runs before says
        // nothing of the next. Each run's taps lean together, which is what fools a count of taps alone.
        val straying = tested(TrainerSim.Thumb(0f, 0f, wander = 0.15f), 150)
        assertTrue("${straying.count { it.provenAt > 0 }} of 150 straying thumbs were told a fix was proven", straying.count { it.provenAt > 0 } <= 7)
        val a = straying.sumOf { c -> c.outcomes.sumOf { it.result.won } }
        val b = straying.sumOf { c -> c.outcomes.sumOf { it.result.lost } }
        assertTrue("it is no better for them: $a taps its way, $b the keyboard's", a < b * 1.1)
    }

    @Test fun takingAProvenFixMakesItTheKeyboardThatLaterFixesAreHeldAgainst() {
        val thumbs = tested(TrainerSim.Thumb(0.24f, 0.18f), 20, taking = true)
        assertTrue(thumbs.all { it.taken >= 1 })
        for (c in thumbs) {
            val at = c.provenAt
            // Before it was taken the keyboard as it is read this thumb badly; after, it reads the same taps well.
            val before = c.outcomes.take(at).drop(1).map { it.keyErrorsAsIs }.average()
            val after = c.outcomes.drop(at).map { it.keyErrorsAsIs }.average()
            if (at < RUNS) assertTrue("as it is: $before before the fix was taken, $after after", after < before * 0.75)
        }
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
        assertTrue("$onShort of 300 ended on the better layout", onShort >= 300 * 0.75)
        assertTrue("none on the worse ones", ended.count { it.home == "tall" } <= 300 * 0.02)
        assertTrue(ended.filter { it.home == "short" }.all { it.suggestion()?.height == "short" && it.own == "normal" })
    }

    @Test fun whereNoLayoutIsBetterMostPeopleAreLeftOnTheirOwn() {
        val ended = trials(people = 300, runs = 48, noise = 0.05f) { 40f }
        val stayed = ended.count { it.home == "normal" }
        // The runs are noisy and nothing is at stake between layouts that are worth the same: nearly all stay (the
        // rule before this one moved one in six by chance), and none goes far.
        assertTrue("$stayed of 300 stayed", stayed >= 300 * 0.93)
        assertTrue(ended.all { kotlin.math.abs(LayoutTrials.HEIGHTS.indexOf(it.home) - 2) <= 1 || it.scores.getValue(it.home).size >= LayoutTrials.MIN_PAIRS })
        assertTrue("settled, not trying for ever", ended.count { it.settled } >= 300 * 0.8)
    }
}
