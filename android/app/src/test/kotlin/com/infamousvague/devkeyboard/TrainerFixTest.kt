package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trainer's test of its own fixes ([FixTrial]; Matt, 2026-11-11: "make each run after the first test a new fixes
 * and a b test the accuracy and don't suggest switching until its proven we got a better result"), with no phone
 * under it: a run's taps read both ways, a new fix a run and never one on an untested one, a fix worn by Adaptive
 * keys' own rules, and what is and is not proof. TrainerAdaptationTest tries the same rules on simulated thumbs.
 */
class TrainerFixTest {
    private val keys = TrainerTest.board.keys
    private val middles: (Char) -> Pair<Float, Float> = { 0f to 0f }

    /** The e's taps land a quarter of a key right and a fifth low, on plenty of taps; the q has had too few to say. */
    private val measured = mapOf('e' to TrainerAims.Measure(60, 0.25f, 0.2f, 60), 'q' to TrainerAims.Measure(5, 0.3f, 0f, 5))

    private fun near(expected: Float, actual: Float, by: Float = 0.001f) = assertEquals(expected, actual, by)
    private fun far(p: Pair<Float, Float>) = kotlin.math.hypot(p.first, p.second)

    /** A trial with fix 1 in hand, and its runs' taps splitting as [splits] say (won to lost), each run of 170 taps. */
    private fun trial(vararg splits: Pair<Int, Int>): FixTrial {
        val f = FixTrial()
        f.begin(false, middles, keys, measured)
        for ((won, lost) in splits) f.record(FixTrial.FixRun(f.number, 170, 160 - won, 160 - lost, won, lost))
        return f
    }

    // --- a run read both ways ------------------------------------------------------------------------------------------

    @Test fun aRunTypedOnAFixIsReadBothWaysOnTheSameTaps() {
        // "the cat.": the h is hit as j whichever way it is read; the e only the fix reads right (the keyboard as it
        // is would have read r); the c only the keyboard as it is would have (the fix read x).
        val r = TrainerTest.Typist("the cat.").tap('t').tap('j', fx = -0.4f).tap('e', asIs = 'r').space()
            .tap('x', fx = 0.4f, asIs = 'c').tap('a').tap('t').mark('.').score()
        assertEquals(6, r.taps)
        assertEquals("as typed, on the fix: t, e, a and t", 4, r.hits)
        assertEquals("as the keyboard is: t, c, a and t", 4, r.asIs)
        assertEquals(1, r.won); assertEquals(1, r.lost)
        // A run typed on no fix has one reading: nothing is won or lost, and it is no test of anything.
        val plain = TrainerTest.Typist("the cat.").tap('t').tap('j', fx = -0.4f).tap('e').space().type("cat").mark('.').score()
        assertEquals(plain.hits, plain.asIs); assertEquals(5, plain.asIs); assertEquals(0, plain.won); assertEquals(0, plain.lost)
        // A tap too many is a tap, and right by neither reading: both are counted over the same taps.
        val extra = TrainerTest.Typist("the cat.").type("th").tap('e', asIs = 'r').tap('x').space().type("cat").mark('.').score()
        assertEquals(7, extra.taps); assertEquals(1, extra.extraTaps); assertEquals(6, extra.hits); assertEquals(5, extra.asIs); assertEquals(1, extra.won)
    }

    // --- a new fix a run ------------------------------------------------------------------------------------------------

    @Test fun theFirstRunOnlyMeasuresAndEachRunAfterItTestsANewFix() {
        val f = FixTrial()
        assertNull("nothing measured: the first run is typed on the keyboard as it is", f.begin(false, middles, keys, emptyMap()))
        assertEquals(0, f.number); assertEquals(FixTrial.Verdict.NONE, f.verdict)
        // The second run: fix 1, one step toward where the e's taps land. No other key has moved.
        val first = f.begin(false, middles, keys, measured)!!
        assertEquals(1, f.number); assertFalse(f.tested)
        near(TrainerAims.STEP, far(first.getValue('e'))); assertTrue(first.getValue('e').first > 0f && first.getValue('e').second > 0f)
        assertEquals("too few taps to move", 0f to 0f, first.getValue('q')); assertEquals(0f to 0f, first.getValue('o'))
        assertEquals(setOf('e'), f.differing(keys, middles)); assertEquals(keys, first.keys)
        // Looking at what a run would be typed on makes nothing: a copy is asked, and the trial is as it was.
        val looked = f.copy()
        assertEquals(1, looked.number); assertFalse(looked.tested); assertEquals(first.getValue('e'), looked.begin(false, middles, keys, measured)!!.getValue('e'))
        looked.record(FixTrial.FixRun(1, 170, 160, 163, 4, 1)); looked.begin(false, middles, keys, measured)
        assertEquals(2, looked.number); assertEquals(1, f.number); assertTrue(f.runs.isEmpty()); near(TrainerAims.STEP, far(f.offset('e', middles)))
        // That run was left, or not counted: the next one tests the same fix. A step is never taken on an untested one.
        val again = f.begin(false, middles, keys, measured)!!
        assertEquals(1, f.number); assertEquals(first.getValue('e'), again.getValue('e'))
        // Tested, whatever it came to: the run after it is typed on fix 2, a step on.
        assertEquals(FixTrial.Verdict.TESTING, f.record(FixTrial.FixRun(1, 170, 160, 163, 4, 1)))
        assertTrue(f.tested)
        val second = f.begin(false, middles, keys, measured)!!
        assertEquals(2, f.number); assertFalse(f.tested); near(TrainerAims.STEP * 2, far(second.getValue('e')), 0.002f)
        // Run after run it closes on where the taps land, a step at a time, and stays there.
        repeat(20) { f.record(FixTrial.FixRun(f.number, 170, 160, 163, 4, 1)); f.begin(false, middles, keys, measured) }
        f.offset('e', middles).let { near(0.25f, it.first, 0.01f); near(0.2f, it.second, 0.01f) }
        val settled = f.number
        f.record(FixTrial.FixRun(settled, 170, 160, 163, 4, 1))
        f.begin(false, middles, keys, measured)
        assertEquals("nothing left to move: the same fix is tested again, and no new one is numbered", settled, f.number)
    }

    @Test fun aFixIsWornByAdaptiveKeysOwnRulesOfUse() {
        // Taps that land a hair off: the first step leaves the key under a fiftieth of a key from its middle, which
        // Adaptive keys would not use. There is nothing to test, and it is no fix yet.
        val slight = mapOf('e' to TrainerAims.Measure(15, 0.03f, 0f, 15))
        val f = FixTrial()
        assertNull(f.begin(false, middles, keys, slight)); assertEquals(0, f.number)
        assertTrue("moved, but not in use", f.at.getValue('e').dx > 0f && f.offset('e', middles) == (0f to 0f))
        // It goes a step on at the next run all the same, and is fix 1 once it is in use.
        val worn = f.begin(false, middles, keys, slight)!!
        assertEquals(1, f.number); assertTrue(worn.getValue('e').first >= TrainerAims.MIN_SHIFT)
        // Never past a third of a key, however far off the taps land.
        val wild = FixTrial()
        repeat(30) { wild.begin(false, middles, keys, mapOf('e' to TrainerAims.Measure(200, 0.9f, -0.9f, 200))); wild.record(FixTrial.FixRun(wild.number, 170, 150, 160, 12, 2)) }
        wild.offset('e', middles).let { near(TrainerAims.MAX_SHIFT, it.first, 0.002f); near(-TrainerAims.MAX_SHIFT, it.second, 0.002f) }
        assertTrue(wild.at.values.all { kotlin.math.abs(it.dx) <= TrainerAims.MAX_SHIFT && kotlin.math.abs(it.dy) <= TrainerAims.MAX_SHIFT })
        // A key the fixes have not moved listens where the keyboard as it is has it, whatever that is now.
        val nudged: (Char) -> Pair<Float, Float> = { if (it == 'a') 0.1f to 0f else 0f to 0f }
        val g = FixTrial()
        val on = g.begin(true, nudged, keys, measured)!!
        assertEquals(0.1f to 0f, on.getValue('a')); assertEquals(setOf('e'), g.differing(keys, nudged))
        assertEquals(0.2f to 0f, g.offset('a') { 0.2f to 0f })
    }

    @Test fun whereTheKeyboardItIsHeldAgainstChangesTheFixesBeginAgain() {
        val f = FixTrial()
        f.begin(false, middles, keys, measured)
        f.record(FixTrial.FixRun(1, 170, 160, 163, 4, 1))
        f.begin(false, middles, keys, measured)
        assertEquals(2, f.number); assertEquals(1, f.runs.size)
        // Adaptive keys was added by hand, with an e of its own: the runs so far were against another keyboard.
        val own: (Char) -> Pair<Float, Float> = { if (it == 'e') 0.1f to 0f else 0f to 0f }
        val worn = f.begin(true, own, keys, measured)!!
        assertEquals("a number is never used twice", 3, f.number); assertTrue(f.runs.isEmpty()); assertTrue(f.adaptive)
        // One step from where Adaptive keys has the e, not from where the old fixes had got to.
        near(TrainerAims.STEP, kotlin.math.hypot(worn.getValue('e').first - 0.1f, worn.getValue('e').second))
        // The steps an earlier version's runs gave the keys are where a first fix starts, untested.
        val seeded = FixTrial()
        val first = seeded.begin(false, middles, keys, emptyMap(), seed = mapOf('e' to TrainerAims.Aim(0.12f, 0f), '1' to TrainerAims.Aim(0.2f, 0f)))!!
        assertEquals(1, seeded.number); assertFalse(seeded.tested); assertEquals(0.12f to 0f, first.getValue('e')); assertEquals(setOf('e'), seeded.at.keys)
    }

    // --- proof -----------------------------------------------------------------------------------------------------------

    @Test fun oneGoodRunProvesNothingAndNeitherDoTwo() {
        assertEquals(FixTrial.Verdict.TESTING, trial(15 to 0).verdict)
        assertEquals(FixTrial.Verdict.TESTING, trial(15 to 0, 14 to 0).verdict)
        // A third run that says the same is proof: three runs, each well ahead.
        val f = trial(15 to 0, 14 to 0, 13 to 1)
        assertEquals(FixTrial.Verdict.PROVEN, f.verdict)
        f.sum.let { assertEquals(3, it.runs); assertEquals(510, it.taps); assertEquals(42, it.won); assertEquals(1, it.lost); assertEquals(3, it.ahead); assertEquals(0, it.behind) }
        near(8f / kotlin.math.sqrt(10f), FixTrial.FixRun(1, 170, 150, 158, 9, 1).lead)
        assertEquals("no tap read differently: level", 0f, FixTrial.FixRun(1, 170, 160, 160, 0, 0).lead, 0f)
    }

    @Test fun aFixThatWonOneRunByALotIsNotProven() {
        // 23 taps to 3 over three runs: far more than chance splits taps. But twenty of them were one run's, and a
        // run's taps lean together. Run by run the fix was ahead once, behind once and level once: no proof.
        val f = trial(20 to 0, 1 to 2, 2 to 1)
        assertTrue(Proof.chance(23, 26) < 0.01)
        assertEquals(FixTrial.Verdict.TESTING, f.verdict)
        f.record(FixTrial.FixRun(1, 170, 159, 159, 1, 1))
        assertEquals(FixTrial.Verdict.TESTING, f.verdict)
    }

    @Test fun aFewTapsEachWayProveNothingHoweverSteady() {
        // One tap the fix's way in each of three runs is as steady as a lead gets, and three taps say nothing.
        assertEquals(FixTrial.Verdict.TESTING, trial(1 to 0, 1 to 0, 1 to 0).verdict)
        assertEquals(FixTrial.Verdict.TESTING, trial(2 to 0, 2 to 0, 2 to 0).verdict)
        // No tap read differently at all: the fix reads as the keyboard does, and there is nothing to prove.
        assertEquals(FixTrial.Verdict.TESTING, trial(0 to 0, 0 to 0, 0 to 0, 0 to 0).verdict)
    }

    @Test fun theRunJustTypedMustNotGoAgainstIt() {
        val f = trial(9 to 1, 10 to 1, 12 to 1)
        assertEquals(FixTrial.Verdict.PROVEN, f.verdict)
        assertEquals("proven until now, and this run went the keyboard's way", FixTrial.Verdict.TESTING, f.record(FixTrial.FixRun(1, 170, 159, 152, 2, 9)))
        // Runs that go its way again prove it again, with the bad one counted among them: it takes four to live it down.
        repeat(3) { assertEquals(FixTrial.Verdict.TESTING, f.record(FixTrial.FixRun(1, 170, 150, 160, 11, 1))) }
        assertEquals(FixTrial.Verdict.PROVEN, f.record(FixTrial.FixRun(1, 170, 150, 160, 12, 1)))
    }

    @Test fun aVerdictIsReadFromTheLatestRunsOnly() {
        val f = trial(9 to 1, 10 to 1, 12 to 1)
        repeat(FixTrial.KEPT) { f.record(FixTrial.FixRun(1, 170, 160, 160, 1, 1)) }
        assertEquals(FixTrial.KEPT, f.runs.size)
        assertEquals("what it once proved is not what it does now", FixTrial.Verdict.TESTING, f.verdict)
    }

    @Test fun aFixThatReadsWorseIsDroppedAndTheNextStartsFromTheKeyboardAsItIs() {
        val f = trial(1 to 6, 0 to 5)
        assertEquals(FixTrial.Verdict.TESTING, f.verdict)
        assertEquals(FixTrial.Verdict.WORSE, f.record(FixTrial.FixRun(1, 170, 160, 154, 1, 7)))
        assertEquals(1, f.dropped); assertTrue(f.at.isEmpty()); assertTrue(f.runs.isEmpty()); assertEquals(FixTrial.Verdict.NONE, f.verdict)
        // The next run tests a new fix, one step from the keyboard as it is.
        val next = f.begin(false, middles, keys, measured)!!
        assertEquals(2, f.number); near(TrainerAims.STEP, far(next.getValue('e')))
        // Worse on less than better is proven on: keeping a fix that reads worse costs every run it is worn.
        assertTrue(FixTrial.ONE_IN_WORSE < FixTrial.ONE_IN)
    }

    @Test fun aSwitchTakesTheFixTheRunsTestedAndNotOneMadeSinceForARunThatWasLeft() {
        val f = trial(9 to 1, 10 to 1, 12 to 1)
        assertEquals(1, f.testedNumber); assertEquals(f.offset('e', middles), f.testedOffset('e', middles))
        val proven = f.testedOffset('e', middles)
        // A run is started, which makes fix 2, and is left: fix 2 is in hand, and no run has typed on it.
        val next = f.begin(false, middles, keys, measured)!!
        assertEquals(2, f.number); assertFalse(f.tested); assertEquals(FixTrial.Verdict.PROVEN, f.verdict)
        near(TrainerAims.STEP * 2, far(next.getValue('e')), 0.002f)
        // What is proven, and what a switch takes, is still fix 1 as the runs tested it.
        assertEquals(1, f.testedNumber); assertEquals(proven, f.testedOffset('e', middles)); near(TrainerAims.STEP, far(proven))
        assertEquals("a key the fixes have not moved is where the keyboard has it", 0.1f to 0f, f.testedOffset('a') { 0.1f to 0f })
        f.took()
        assertEquals(1, f.taken); assertEquals(2, f.number); assertFalse("fix 2 is still to be tested, against fix 1 now", f.tested)
        val asIs: (Char) -> Pair<Float, Float> = { if (it == 'e') proven else 0f to 0f }
        assertEquals(setOf('e'), f.differing(keys, asIs))
        assertEquals(2, f.copy().also { it.begin(true, asIs, keys, measured) }.number)
        // It comes back from a copy as it went.
        f.copy().let { assertEquals(1, it.testedNumber); assertEquals(proven, it.testedOffset('e', middles)); assertEquals(1, it.taken) }
        // A trial kept without saying which fix its runs tested: the fix in hand, where that one is tested; none, where it is not.
        FixTrial().also { it.restore(3, true, false, 0, 0, mapOf('e' to TrainerAims.Aim(0.1f, 0f)), listOf(FixTrial.FixRun(3, 170, 150, 160, 12, 2))) }.let { assertEquals(3, it.testedNumber); assertEquals(0.1f to 0f, it.testedOffset('e', middles)) }
        FixTrial().also { it.restore(3, false, false, 0, 0, mapOf('e' to TrainerAims.Aim(0.1f, 0f)), listOf(FixTrial.FixRun(2, 170, 150, 160, 12, 2))) }.let { assertEquals(0, it.testedNumber); assertTrue(it.testedAt.isEmpty()) }
        // Dropped as worse, nothing is left to take.
        val bad = trial(1 to 6, 0 to 5, 1 to 7)
        assertEquals(0, bad.testedNumber); assertTrue(bad.testedAt.isEmpty())
    }

    @Test fun takingAFixMakesItTheKeyboardAndTheTestStartsAgain() {
        val f = trial(9 to 1, 10 to 1, 12 to 1)
        val fix = keys.associateWith { f.offset(it, middles) }
        f.took()
        assertEquals(1, f.taken); assertTrue(f.runs.isEmpty()); assertEquals(FixTrial.Verdict.NONE, f.verdict); assertTrue("taking adds Adaptive keys", f.adaptive)
        // The keyboard as it is now reads as that fix does; the next fix is a step on from it, and is held against it.
        val asIs: (Char) -> Pair<Float, Float> = { fix.getValue(it) }
        val next = f.begin(true, asIs, keys, measured)!!
        assertEquals(2, f.number); assertEquals(1, f.taken)
        near(TrainerAims.STEP, kotlin.math.hypot(next.getValue('e').first - fix.getValue('e').first, next.getValue('e').second - fix.getValue('e').second), 0.002f)
    }

    @Test fun theTwoTestsAreTheOrdinaryOnes() {
        assertEquals(1.0, Proof.chance(0, 10), 0.0); assertEquals(1.0, Proof.chance(3, 0), 0.0)
        assertEquals(1.0 / 1024, Proof.chance(10, 10), 1e-12)
        assertEquals(56.0 / 1024, Proof.chance(8, 10), 1e-12)
        assertEquals(0.5, Proof.chance(6, 11), 1e-12)
        assertTrue("three hundred taps do not overflow it", Proof.chance(200, 300) in 1e-9..1e-7)
        // Student's t: the mean over its standard error.
        near(3f / (1f / kotlin.math.sqrt(3f)), Proof.student(floatArrayOf(2f, 3f, 4f)))
        assertEquals(Float.POSITIVE_INFINITY, Proof.student(floatArrayOf(2f, 2f, 2f)), 0f); assertEquals(0f, Proof.student(floatArrayOf(0f, 0f, 0f)), 0f)
        assertEquals("one value has no spread to judge by", 0f, Proof.student(floatArrayOf(5f)), 0f)
        // One in a hundred asks more of three values than of eight, and more than one in twenty does.
        assertTrue(Proof.steady(floatArrayOf(2.5f, 2.7f, 3.0f), Proof.ONE_IN_100))
        assertFalse(Proof.steady(floatArrayOf(4.5f, -0.6f, 0.6f), Proof.ONE_IN_100))
        assertFalse(Proof.steady(floatArrayOf(3f, 1f, 2.6f), Proof.ONE_IN_100)); assertTrue(Proof.steady(floatArrayOf(3f, 1f, 2.6f), Proof.ONE_IN_20))
        for (i in 2 until Proof.ONE_IN_100.size) { assertTrue(Proof.ONE_IN_100[i] < Proof.ONE_IN_100[i - 1]); assertTrue(Proof.ONE_IN_20[i] < Proof.ONE_IN_100[i]); assertTrue(Proof.ONE_IN_100[i] < Proof.ONE_IN_200[i]) }
    }
}
