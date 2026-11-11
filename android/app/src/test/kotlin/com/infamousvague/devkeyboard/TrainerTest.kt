package com.infamousvague.devkeyboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typing trainer's own reckoning (Matt, 2026-11-11: "a typeracer style thing where we type stories out and it
 * tracks everything like wpm and missed words"), with no phone under it: what was typed held against what was meant
 * ([TrainerText]), a run scored ([TrainerScore]), a key's nudge stepped ([TrainerAims]), layouts tried
 * ([LayoutTrials]), and what is kept between runs ([TrainerBook], [TrainerCodec]).
 */
class TrainerTest {
    companion object {
        val board: Map<Char, Pair<Float, Int>> = Layouts.QWERTY.positions
    }

    /**
     * Types a story the way the keyboard is seen to: a key, then the whole text as it stands. Every key is 200 ms
     * after the one before it unless [gap] says otherwise.
     */
    class Typist(val story: String, var gap: Long = 200L) {
        val run = TrainerRecorder(story)
        var text = ""
        var at = 10_000L

        /** A letter key: [c] typed by a tap read as [key], landing [fx], [fy] of a key from that key's middle. */
        fun tap(c: Char, key: Char = c.lowercaseChar(), fx: Float = 0f, fy: Float = 0f): Typist {
            at += gap; text += c
            run.key(at, KeyKind.CHAR, text, KeyTap(key, fx, fy))
            return this
        }

        fun type(word: String): Typist { word.forEach { tap(it) }; return this }

        /** The space bar; [fix] is what autocorrect makes of the word it ends. */
        fun space(fix: String? = null): Typist {
            at += gap
            if (fix != null) text = text.dropLast(TrainerText.tail(text).length) + fix
            text += " "
            run.key(at, KeyKind.SPACE, text)
            return this
        }

        /** A full stop or comma, which ends a word as a space does. */
        fun mark(c: Char, fix: String? = null): Typist {
            at += gap
            if (fix != null) text = text.dropLast(TrainerText.tail(text).length) + fix
            text += c
            run.key(at, KeyKind.CHAR, text, KeyTap(c, 0f, 0f))
            return this
        }

        fun back(): Typist { at += gap; text = text.dropLast(1); run.key(at, KeyKind.BACKSPACE, text); return this }

        /** Backspace straight after a fix: the fix and its space go, and the word as typed is back. */
        fun undo(fixed: String, typed: String): Typist {
            at += gap
            check(text.endsWith("$fixed "))
            text = text.dropLast(fixed.length + 1) + typed
            run.key(at, KeyKind.BACKSPACE, text)
            return this
        }

        /** A word tapped in the row: it takes the place of the word being typed, with a space after it. */
        fun pick(word: String): Typist { at += gap; text = text.dropLast(TrainerText.tail(text).length) + "$word "; run.seen(at, text); return this }

        /** A stroke: drawn, then read a moment later as [word], with a space before it where one is needed. */
        fun swipe(word: String): Typist {
            at += gap; run.key(at, KeyKind.SWIPE, text)
            at += 30; text += (if (text.isEmpty() || text.endsWith(" ")) "" else " ") + word; run.seen(at, text)
            return this
        }

        fun score(): RunResult = TrainerScore.of(story, run.steps, board)
    }

    private fun near(expected: Float, actual: Float, by: Float = 0.001f) = assertEquals(expected, actual, by)

    // --- letters and words ---------------------------------------------------------------------------------------------

    @Test fun aTypedWordIsHeldAgainstTheOneMeantLetterByLetter() {
        TrainerText.letters("the", "The").let { assertEquals(0, it.cost); assertTrue(it.kinds.all { k -> k == TrainerText.Kind.MATCH }) }
        TrainerText.letters("thw", "the").let {
            assertEquals(1, it.cost)
            assertEquals(listOf(TrainerText.Kind.MATCH, TrainerText.Kind.MATCH, TrainerText.Kind.WRONG), it.kinds.toList())
            assertEquals(listOf(0, 1, 2), it.meant.toList())
        }
        // Two letters in each other's places: each was the right key, in the other's turn.
        TrainerText.letters("teh", "the").let {
            assertEquals(1, it.cost)
            assertEquals(listOf(TrainerText.Kind.MATCH, TrainerText.Kind.SWAPPED, TrainerText.Kind.SWAPPED), it.kinds.toList())
            assertEquals(listOf(0, 2, 1), it.meant.toList())
        }
        TrainerText.letters("thhe", "the").let { assertEquals(1, it.cost); assertEquals(1, it.kinds.count { k -> k == TrainerText.Kind.EXTRA }); assertEquals(0, it.omitted.size) }
        TrainerText.letters("te", "the").let { assertEquals(1, it.cost); assertEquals(listOf(1), it.omitted.toList()) }
        // A doubled letter is no swap.
        assertEquals(0, TrainerText.letters("hello", "hello").cost)
        // A word only begun (the row finished it) is no word with its end missing.
        TrainerText.letters("wor", "world", prefix = true).let { assertEquals(0, it.cost); assertEquals(0, it.omitted.size) }
        TrainerText.letters("wrol", "world", prefix = true).let { assertEquals(1, it.cost); assertEquals(2, it.kinds.count { k -> k == TrainerText.Kind.SWAPPED }) }
        assertEquals(2, TrainerText.letters("wor", "world").omitted.size)
    }

    @Test fun theWordsTypedAreHeldAgainstTheStorys() {
        val story = listOf("the", "quick", "brown", "fox.")
        TrainerText.words(listOf("the", "quick", "brown", "fox."), story, whole = true).let { assertEquals(4, it.right); assertEquals(4, it.reached) }
        // A word skipped costs that word and no more.
        TrainerText.words(listOf("the", "brown", "fox."), story, whole = true).let {
            assertEquals(listOf(TrainerText.Fate.RIGHT, TrainerText.Fate.SKIPPED, TrainerText.Fate.RIGHT, TrainerText.Fate.RIGHT), it.fates.toList())
            assertEquals(listOf(0, 2, 3), it.of.toList())
        }
        // Part way through, what is left is still to do.
        TrainerText.words(listOf("teh", "quick"), story, whole = false).let {
            assertEquals(listOf(TrainerText.Fate.WRONG, TrainerText.Fate.RIGHT, TrainerText.Fate.TODO, TrainerText.Fate.TODO), it.fates.toList())
            assertEquals(2, it.reached)
        }
        // A capital missed, or its full stop, and the word is not the story's.
        assertEquals(TrainerText.Fate.WRONG, TrainerText.words(listOf("the", "quick", "brown", "fox"), story, whole = true).fates[3])
        // Two words run together stand for both; a word split by a stray space stands for one.
        TrainerText.words(listOf("inthe", "house"), listOf("in", "the", "house"), whole = true).let {
            assertEquals(listOf(TrainerText.Fate.WRONG, TrainerText.Fate.WRONG, TrainerText.Fate.RIGHT), it.fates.toList())
            assertTrue(it.joined[0]); assertEquals(listOf(0, 2), it.of.toList())
        }
        TrainerText.words(listOf("th", "e", "house"), listOf("the", "house"), whole = true).let {
            assertEquals(listOf(TrainerText.Fate.WRONG, TrainerText.Fate.RIGHT), it.fates.toList())
            assertTrue(it.split[0]); assertEquals(listOf(0, 0, 1), it.of.toList())
        }
        // A word typed twice is one too many, and the rest still line up.
        TrainerText.words(listOf("the", "the", "cat"), listOf("the", "cat"), whole = true).let { assertEquals(2, it.right); assertEquals(1, it.of.count { j -> j < 0 }) }
        assertEquals(0, TrainerText.slotAt("the cat", 0)); assertEquals(1, TrainerText.slotAt("the cat", 4)); assertEquals(1, TrainerText.slotAt("the cat", 6)); assertEquals(2, TrainerText.slotAt("the cat ", 8))
    }

    // --- a run -------------------------------------------------------------------------------------------------------------

    @Test fun aCleanRunIsAllRightAndItsSpeedIsTheStorysLengthOverItsTime() {
        val t = Typist("The cat sat.").type("The").space().type("cat").space().type("sat").mark('.')
        val r = t.score()
        // Twelve keys, eleven gaps of 200 ms; twelve characters are 2.4 words.
        assertEquals(2_200L, r.ms)
        near(2.4f / (2.2f / 60f), r.wpm, 0.01f)
        near(r.wpm, r.gross, 0.01f)
        assertEquals(3, r.words); assertEquals(3, r.right); assertEquals(3, r.firstTry)
        assertEquals(9, r.taps); assertEquals(9, r.hits); near(1f, r.accuracy)
        assertEquals(0, r.backspaces); assertTrue(r.missed.isEmpty() && r.skipped.isEmpty() && r.mended.isEmpty() && r.spoiled.isEmpty())
        assertEquals(1f, r.typedShare, 0f)
        // Too short a time to count for anything.
        assertFalse(r.counted)
        // Every pair of letters within a word, with the time between its taps.
        assertEquals(setOf("th", "he", "ca", "at", "sa"), r.perPair.keys)
        assertEquals(2, r.perPair.getValue("at").taps); assertEquals(400L, r.perPair.getValue("at").ms)
    }

    @Test fun aSlipTakenBackIsMeasuredFromTheKeyThatWasMeant() {
        // "the": the h landed on g's right-hand edge (g is the key left of h), was taken back, and typed again.
        val t = Typist("the.").tap('t').tap('g', fx = 0.4f, fy = 0.1f).back().tap('h').tap('e').mark('.')
        val r = t.score()
        assertEquals(4, r.taps); assertEquals(3, r.hits); assertEquals(1, r.slips); near(0.75f, r.accuracy)
        assertEquals(1, r.backspaces); assertEquals(1, r.right)
        assertEquals("right in the end, but not at the first go", 0, r.firstTry)
        val h = r.perKey.getValue('h')
        assertEquals(2, h.taps); assertEquals(1, h.hits); assertEquals(1, h.slips); assertEquals(2, h.aimed)
        // The slip was 0.4 right of g's middle, which is 0.6 left of h's; the tap that hit h was in its middle.
        near(-0.3f, h.meanX); near(0.05f, h.meanY)
        assertEquals(mapOf("g>h" to 1), r.slipPairs)
        assertNull("nothing was meant for g", r.perKey['g'])
    }

    @Test fun aLetterTakenBackWithTheSlipBeforeItStillCountsAsTheKeyItHit() {
        // "tge", then both the e and the g taken back to put the h in: the e had hit its key.
        val r = Typist("the.").tap('t').tap('g', fx = 0.45f).tap('e', fx = -0.1f).back().back().tap('h').tap('e', fx = 0.1f).mark('.').score()
        assertEquals(5, r.taps); assertEquals(4, r.hits); assertEquals(1, r.slips)
        assertEquals(2, r.perKey.getValue('e').hits); near(0f, r.perKey.getValue('e').meanX)
        assertEquals(2, r.backspaces)
    }

    @Test fun aWrongLetterPressedInTheMiddleOfItsKeyIsNoSlipOfTheThumb() {
        // k for c: nowhere near it. And s for a, its neighbour, but pressed dead centre: a misspelling.
        val r = Typist("cat.").tap('k').tap('s', fx = 0.05f, fy = -0.05f).tap('t').mark('.').score()
        assertEquals(3, r.taps); assertEquals(1, r.hits); assertEquals(0, r.slips); assertEquals(2, r.wrongKeys)
        assertEquals(0, r.perKey.getValue('c').aimed); assertEquals(0, r.perKey.getValue('a').aimed)
        assertEquals(listOf("cat." to "kst."), r.missed.map { it.meant to it.typed })
        assertEquals(listOf("cat" to "kst"), r.left.map { it.meant to it.typed })
        assertEquals(listOf(WordTry("cat", "kst", false, false).let { "${it.meant} ${it.typed} ${it.right} ${it.rightAtLast}" }), r.tries.map { "${it.meant} ${it.typed} ${it.right} ${it.rightAtLast}" })
    }

    @Test fun autocorrectIsToldRightFromWrong() {
        // Mended rightly: "teh" became "the". Spoiled: "form" was right and became "from". Changed to another wrong word: "cst" became "cost".
        val r = Typist("the form cat.").type("teh").space(fix = "the").type("form").space(fix = "from").type("cst").mark('.', fix = "cost").score()
        assertEquals(listOf("teh the the"), r.mended.map { "${it.typed} ${it.made} ${it.meant}" })
        assertEquals(listOf("form from form"), r.spoiled.map { "${it.typed} ${it.made} ${it.meant}" })
        assertEquals(listOf("cst cost cat"), r.misfixed.map { "${it.typed} ${it.made} ${it.meant}" })
        assertEquals(1, r.right)
        assertEquals(listOf("form" to "from", "cat." to "cost."), r.missed.map { it.meant to it.typed })
        // The letters of "teh" each hit their key, two of them in the wrong order.
        assertEquals(1, r.swaps); assertEquals(1, r.perPair.getValue("he").misses)
        // "form" was typed right the first time, whatever became of it; "teh" and "cst" were not.
        assertEquals(1, r.firstTry)
        assertEquals(listOf("the teh false true", "form  true false", "cat cst false false"), r.tries.map { "${it.meant} ${it.typed} ${it.right} ${it.rightAtLast}" })
        // One wrong key among the ten taps: the s of "cst", in the middle of its key.
        assertEquals(10, r.taps); assertEquals(9, r.hits)
    }

    @Test fun aFixUndoneGivesTheWordBackItsTaps() {
        // "form" spoiled to "from", put back with backspace, and ended again with a space.
        val r = Typist("form here.").type("form").space(fix = "from").undo("from", "form").space().type("here").mark('.').score()
        assertEquals(1, r.spoiled.size); assertEquals(1, r.undone)
        assertEquals(2, r.right); assertTrue(r.missed.isEmpty())
        // Its four taps are counted once, not once for each time the word ended.
        assertEquals(8, r.taps); assertEquals(8, r.hits)
    }

    @Test fun wordsFromTheRowAndStrokesAreCountedAndSayNothingOfTheTaps() {
        // "wor" finished by a tap in the row; "of" taken whole from the row; "cats" drawn as a stroke.
        val t = Typist("world of cats here.").type("wor").pick("world")
        t.at += 200; t.text += "of "; t.run.seen(t.at, t.text)
        val r = t.swipe("cats").space().type("here").mark('.').score()
        assertEquals(4, r.right); assertEquals(2, r.picked); assertEquals(1, r.swiped)
        assertEquals("only the letters fingers tapped", 7, r.taps); assertEquals(7, r.hits)
        assertEquals(listOf("world", "here"), r.tries.map { it.meant })
        assertTrue(r.tries.all { it.right })
    }

    @Test fun aLetterTypedStraightAfterAStrokeEndsItAndStartsTheNextWord() {
        // The keyboard puts the space in itself.
        val t = Typist("cats sit.").swipe("cats")
        t.at += 200; t.text += " s"; t.run.key(t.at, KeyKind.CHAR, t.text, KeyTap('s', 0f, 0f))
        val r = t.type("it").mark('.').score()
        assertEquals(2, r.right); assertEquals(1, r.swiped); assertEquals(3, r.taps)
    }

    @Test fun twoWordsRunTogetherAreASpaceMissed() {
        // Parted by autocorrect: both words come out right, and the space was still missed.
        val fixed = Typist("in the house.").type("inthe").space(fix = "in the").type("house").mark('.').score()
        assertEquals(3, fixed.right); assertEquals(1, fixed.spacesMissed); assertEquals(1, fixed.mended.size)
        assertEquals(10, fixed.hits)
        // Left as typed, with the b the thumb hit above the bar: one wrong pair, and b was one tap too many.
        val left = Typist("in the house.").type("inbthe").space().type("house").mark('.').score()
        assertEquals(1, left.right); assertEquals(1, left.spacesMissed); assertEquals(1, left.extraTaps)
        assertEquals(listOf("in the" to "inbthe"), left.missed.map { it.meant to it.typed })
    }

    @Test fun aWordSkippedAndARunCutShortAreSaidSo() {
        val skipped = Typist("the quick brown fox.").type("the").space().type("brown").space().type("fox").mark('.').score()
        assertEquals(listOf("quick"), skipped.skipped); assertEquals(3, skipped.right); near(0.75f, skipped.typedShare)
        assertFalse("three words of four is under four fifths", skipped.counted)
        val whole = Typist("one two three four five six.", gap = 400L).type("one").space().type("two").space().type("three").space().type("four").space().type("five").space().type("six").mark('.').score()
        assertTrue(whole.counted)
    }

    @Test fun progressFollowsTheStoryAndKnowsItsEnd() {
        val t = Typist("The cat sat.").type("The").space().type("cst").space().type("sa")
        t.run.progress(t.at).let {
            assertEquals(listOf(1, 2, 0), it.fates.toList()); assertEquals(2, it.current); assertEquals("sa", it.tail); assertFalse(it.done)
        }
        t.tap('t')
        assertFalse("the last word still wants its full stop", t.run.progress(t.at).done)
        t.mark('.')
        assertTrue(t.run.progress(t.at).done)
        // A last word typed wrong still ends the story, and so does a space after it.
        assertTrue(Typist("The cat.").type("The").space().type("cst").mark('.').run.progress(0).done)
        assertTrue(Typist("The cat.").type("The").space().type("cat").space().run.progress(0).done)
        assertFalse(Typist("The cat.").type("The").space().run.progress(0).done)
        assertEquals(0, TrainerRecorder("The cat.").progress(0).ms)
    }

    // --- the keys' nudges --------------------------------------------------------------------------------------------------

    @Test fun theTrainerGoesByAdaptiveKeysOwnBounds() {
        assertEquals(AdaptiveKeys.MIN_SAMPLES, TrainerAims.MIN_SAMPLES)
        assertEquals(AdaptiveKeys.MAX_SHIFT, TrainerAims.MAX_SHIFT, 0f)
        assertEquals(AdaptiveKeys.MIN_SHIFT, TrainerAims.MIN_SHIFT, 0f)
        assertEquals(TypingProfile.FORMAT, TrainerCodec.PROFILE_FORMAT)
        assertEquals(TypingProfile.VERSION, TrainerCodec.PROFILE_VERSION)
        // A step is small beside the whole of what a nudge may be.
        assertTrue(TrainerAims.STEP * 5 < TrainerAims.MAX_SHIFT)
    }

    @Test fun aNudgeMovesAStepAtATimeAndNeverJumps() {
        // Too few taps: nothing moves.
        TrainerAims.step(0f, 0f, 0.25f, 0.2f, TrainerAims.MIN_SAMPLES - 1).let { near(0f, it.dx); near(0f, it.dy) }
        // Enough: a step toward the mean, no longer than STEP however far the mean is.
        var aim = TrainerAims.step(0f, 0f, 0.25f, 0.2f, 60)
        near(TrainerAims.STEP, kotlin.math.hypot(aim.dx, aim.dy), 0.0005f)
        assertTrue(aim.dx > 0f && aim.dy > 0f)
        // Run after run it closes on the mean and stays there.
        var steps = 1
        repeat(40) {
            val next = TrainerAims.step(aim.dx, aim.dy, 0.25f, 0.2f, 60)
            assertTrue(kotlin.math.hypot(next.dx - aim.dx, next.dy - aim.dy) <= TrainerAims.STEP + 0.0005f)
            if (kotlin.math.hypot(0.25f - next.dx, 0.2f - next.dy) > 0.01f) steps++
            aim = next
        }
        near(0.25f, aim.dx, 0.005f); near(0.2f, aim.dy, 0.005f)
        assertTrue("about eight runs to cover a third of a key, not one", steps in 7..14)
        // Never past the bound, however far off the taps land.
        var far = TrainerAims.Aim(0f, 0f)
        repeat(60) { far = TrainerAims.step(far.dx, far.dy, 0.9f, -0.9f, 200) }
        near(TrainerAims.MAX_SHIFT, far.dx); near(-TrainerAims.MAX_SHIFT, far.dy)
        // A mean in the key's middle brings a nudge back, a step at a time.
        val back = TrainerAims.step(0.2f, 0f, 0f, 0f, 100)
        assertTrue(back.dx < 0.2f && back.dx >= 0.2f - TrainerAims.STEP - 0.0005f)
        // Few taps are trusted less than many.
        assertTrue(TrainerAims.step(0f, 0f, 0.03f, 0f, 15).dx < TrainerAims.step(0f, 0f, 0.03f, 0f, 200).dx)
    }

    @Test fun whatTheTrainerGaveCanBeTakenBack() {
        TrainerAims.takeBack(0.2f, -0.1f, 0.12f, -0.3f).let { near(0.08f, it.dx); near(0f, it.dy) }
        // Never past the middle, and never against a nudge that has since gone the other way.
        TrainerAims.takeBack(0.05f, 0.1f, 0.12f, -0.1f).let { near(0f, it.dx); near(0.1f, it.dy) }
    }

    @Test fun aMeasurementIsTheMeanOfItsTapsAndFollowsAThumbThatChanges() {
        val m = TrainerAims.Measure()
        m.add(10, 2.0, -1.0); m.add(30, 0.0, 3.0)
        assertEquals(40, m.n); near(0.05f, m.x); near(0.05f, m.y); assertEquals(40, m.total)
        // Past the cap the older taps give way.
        repeat(40) { m.add(50, 50 * 0.3, 0.0) }
        assertEquals(TrainerAims.CAP, m.n); near(0.3f, m.x, 0.005f); assertEquals(2040, m.total)
    }

    // --- layouts -----------------------------------------------------------------------------------------------------------

    @Test fun oneGoodRunOnAnotherLayoutDecidesNothing() {
        val t = LayoutTrials()
        t.anchor("normal")
        assertEquals("the person's own layout first", "normal", t.next())
        assertNull(t.record("normal", 40f))
        assertEquals("then the more condensed neighbour", "short", t.next())
        assertNull("far better, once", t.record("short", 80f))
        assertEquals("normal", t.home); assertNull(t.suggestion())
        // Turn about, and still nothing after two pairs.
        assertEquals("normal", t.next()); t.record("normal", 41f)
        assertEquals("short", t.next()); assertNull(t.record("short", 45f))
        assertEquals("normal", t.home)
    }

    @Test fun aLayoutThatKeepsDoingBetterTakesOverOneStepAtATime() {
        val t = LayoutTrials()
        t.anchor("normal")
        var move: LayoutTrials.Move? = null
        var runs = 0
        while (move == null && runs < 20) { val h = t.next(); move = t.record(h, if (h == "short") 46f + runs % 2 else 42f + runs % 3); runs++ }
        assertNotNull(move); assertEquals("normal", move!!.from); assertEquals("short", move.to)
        assertEquals("four pairs, no fewer", 8, runs)
        assertEquals("short", t.home); assertEquals("the setting is the person's still", "normal", t.own)
        t.suggestion()!!.let { assertEquals("short", it.height); assertEquals(4, it.runs); assertEquals(4, it.ownRuns); assertTrue(it.score > it.ownScore) }
        // From there the next step out is tried, never the one just beaten, and never two steps at once.
        assertEquals("short", t.next()); t.record("short", 46f)
        assertEquals("condensed", t.next())
    }

    @Test fun aLayoutThatDoesNoBetterIsLetGoAndTheTrialsSettle() {
        val t = LayoutTrials()
        t.anchor("normal")
        var runs = 0
        // Worse on short, level on tall: neither takes over.
        while (!t.settled && runs < 60) { val h = t.next(); t.record(h, when (h) { "short" -> 36f; "tall" -> 42f + (runs % 3 - 1) * 0.2f; else -> 42f + (runs % 2) * 0.2f }); runs++ }
        assertTrue(t.settled); assertEquals("normal", t.home); assertNull(t.suggestion())
        assertEquals(setOf("short", "tall"), t.rejected.toSet())
        assertEquals("four pairs to let the worse one go, six to give up on the level one", 8 + 12, runs)
        assertEquals("settled, every run is on the person's own", "normal", t.next())
        // Asked to, it tries them again.
        t.again(); assertFalse(t.settled)
    }

    @Test fun theTrialsFollowThePersonsOwnSetting() {
        val t = LayoutTrials()
        t.anchor("condensed")
        assertEquals("condensed", t.home); assertEquals("condensed", t.next()); t.record("condensed", 40f)
        assertEquals("nothing is more condensed: the one way to go", "short", t.next())
        // They change it by hand: the trials start again from there.
        t.record("short", 50f)
        t.anchor("tall")
        assertEquals("tall", t.home); assertNull(t.candidate); assertTrue(t.homeScores.isEmpty())
        // Taking the trials' own suggestion is no change of mind.
        val u = LayoutTrials()
        u.anchor("normal")
        repeat(4) { u.record(u.next(), 40f); u.record(u.next(), 50f) }
        assertEquals("short", u.home)
        u.anchor("short")
        assertEquals("short", u.home); assertEquals("short", u.own); assertNull(u.suggestion()); assertTrue("normal" in u.rejected)
    }

    // --- what is kept --------------------------------------------------------------------------------------------------------

    private fun line(n: Int, wpm: Float, accuracy: Float = 0.95f, practice: Boolean = false) =
        RunLine(1_700_000_000_000L + n * 60_000L, "story-$n", "A story", practice, "normal", false, 60_000L, wpm, accuracy, 45, 44, 40, 3, 2, 0)

    /** A run of "because of their friend." where "because" is typed [first] and the o of "of" slips onto p. */
    private fun run(first: String = "because", fix: String? = null) =
        Typist("because of their friend.", gap = 300L).type(first).space(fix = fix).tap('p', fx = -0.4f).tap('f').space().type("their").space().type("friend").mark('.').score()

    @Test fun mistakesAreRememberedAcrossRunsAndSeenToComeRight() {
        val book = TrainerBook()
        book.add(line(1, 40f), run("becuase"), "qwerty")
        book.add(line(2, 41f), run("becuase", fix = "because"), "qwerty")
        book.add(line(3, 42f), run("becasue"), "qwerty")
        // Two words went wrong each time: "because", and "of" with its slip.
        assertEquals(setOf("because", "of"), book.mistakes().map { it.word }.toSet())
        val m = book.mistakes().first { it.word == "because" }
        assertEquals("becuase", m.typed); assertEquals(3, m.missed); assertEquals(3, m.seen); assertEquals("000", m.last); assertEquals(0, m.streak)
        // Typed right twice since: the tries say so.
        book.add(line(4, 43f), run(), "qwerty"); book.add(line(5, 44f), run(), "qwerty")
        book.mistakes().first { it.word == "because" }.let { assertEquals("00011", it.last); assertEquals(2, it.streak); assertEquals(5, it.seen) }
        // The one still going wrong comes before the one that has come right.
        assertEquals(listOf("of", "because"), book.mistakes().map { it.word })
        assertEquals("pf", book.mistakes().first().typed)
        // The slip onto p, five times in five: where the taps meant for o land, and the neighbour hit for it.
        assertEquals(mapOf("p>o" to 5), book.slips.getValue("qwerty"))
        assertEquals(5, book.aims.getValue("qwerty").getValue('o').total)
        near(0.6f, book.aims.getValue("qwerty").getValue('o').x)
        assertTrue("five taps are too few to call a key weak", book.weakKeys("qwerty").isEmpty())
        // Words never missed are not mistakes.
        assertFalse(book.words.getValue("their").missed > 0)
        assertEquals(5, book.storyRuns)
        // Fifteen taps on, the key is one of the weak ones, with the neighbour hit in its place.
        repeat(10) { book.add(line(6 + it, 44f), run(), "qwerty") }
        assertEquals(listOf("o 15 15 p"), book.weakKeys("qwerty").map { "${it.key} ${it.missed} ${it.n} ${it.toward}" })
    }

    @Test fun aPracticeRunIsMadeOfThePersonsOwnMistakes() {
        val book = TrainerBook()
        val vocabulary = listOf("because", "friend", "their", "house", "about", "found", "would", "should", "sound")
        assertNull("nothing missed yet", book.practice(vocabulary, 1))
        book.words["because"] = WordNote().apply { add(false, "becuase"); add(false, "becuase") }
        book.words["their"] = WordNote().apply { add(false, "thier") }
        book.words["friend"] = WordNote().apply { add(false, "freind"); add(true, "") }
        book.pairs["ou"] = Tally().apply { add(40, 9) }
        val text = book.practice(vocabulary, 7)!!
        val words = TrainerText.words(text).map { TrainerText.core(it).lowercase() }
        assertEquals(3, words.count { it == "because" }); assertEquals(3, words.count { it == "their" }); assertEquals(3, words.count { it == "friend" })
        // And words of the stories' own that have the pair that goes wrong, twice each.
        val more = words.filter { "ou" in it }.toSet()
        assertEquals(3, more.size); assertTrue(more.all { w -> words.count { it == w } == 2 })
        assertTrue(text[0].isUpperCase() && text.endsWith(".")); assertTrue(text.all { it.isLetter() || it == ' ' || it == '.' })
        assertEquals("the same seed, the same run", text, book.practice(vocabulary, 7))
        for (i in 1 until words.size) assertFalse("no word twice running", words[i] == words[i - 1])
    }

    @Test fun theProfileWaitsForFiveStoriesAndSaysWhereTheTapsLand() {
        val book = TrainerBook()
        repeat(4) { book.add(line(it, 38f + it), run("becuase"), "qwerty") }
        book.add(line(9, 60f, practice = true), run(), "qwerty")
        assertNull("four stories and a practice run are not five stories", book.profile("qwerty", board))
        book.add(line(5, 45f), run(), "qwerty")
        // Every key's taps a little right and well low.
        for (c in "etaoinshr") book.aims.getValue("qwerty").getOrPut(c) { TrainerAims.Measure() }.let { it.n = 0; it.total = 0; it.add(60, 60 * 0.07, 60 * 0.24) }
        val p = book.profile("qwerty", board)!!
        assertEquals(5, p.runs); near(39f, p.wpmFirst); near((40f + 41f + 45f) / 3, p.wpmNow); near(45f, p.wpmBest)
        assertEquals("Your taps land a little to the right of a key’s middle and well below it.", p.lean)
        assertEquals(9, p.keys.size); p.keys.first { it.key == 'e' }.let { near(0.07f, it.dx); near(0.24f, it.dy); assertEquals(60, it.samples) }
        assertEquals(listOf("of", "because"), p.mistakes.map { it.word })
        assertEquals(listOf("p o 6"), p.slips.map { "${it.pressed} ${it.meant} ${it.count}" })
        assertEquals("Over 5 runs: 42 words a minute (39 at first), 95% of taps on their key. Your taps land", p.report.take(86))
        assertTrue(p.report.contains("p for o") && p.report.contains("because"))
        // As a typing profile it is one the keyboard's own import reads: its format, its layout, its keys.
        val file = JSONObject(TrainerCodec.profile(p, "2026-11-11T10:00:00Z"))
        assertEquals("ghost-typing-profile", file.getString("format")); assertEquals(1, file.getInt("version")); assertEquals("qwerty", file.getString("layout"))
        assertEquals(60, file.getJSONObject("keys").getJSONObject("e").getInt("samples")); assertEquals(0.24, file.getJSONObject("keys").getJSONObject("e").getDouble("dy"), 0.001)
        assertEquals("p", file.getJSONArray("slips").getJSONObject(0).getString("pressed"))
        assertEquals(5, file.getJSONObject("trainer").getInt("runs"))
        assertFalse("what the trainer applies itself carries no slips: the aims are every tap already", JSONObject(TrainerCodec.profile(p, "x", slips = false)).has("slips"))
        // The story's own words are in it only where they were mistyped.
        val kept = file.getJSONObject("trainer").getJSONArray("mistakes")
        assertEquals(listOf("of", "because"), (0 until kept.length()).map { kept.getJSONObject(it).getString("word") })
        assertFalse(file.toString().contains("friend"))
    }

    @Test fun whereTheTapsLeanIsSaidInWords() {
        fun of(x: Float, y: Float, top: Float = y) = TrainerBook.lean("qwertyuiopasdfghjklzxcvbnm".associate { it to TrainerAims.Measure(40, x, if (board.getValue(it).second == 0) top else y, 40) }, board)
        assertEquals("Your taps land in the middle of the keys.", of(0.01f, -0.02f))
        assertEquals("Your taps land to the left of a key’s middle.", of(-0.15f, 0f))
        assertEquals("Your taps land a little above a key’s middle.", of(0f, -0.06f))
        assertEquals("Your taps land a little below a key’s middle. Most on the top row, well low.", of(0f, 0.02f, top = 0.22f))
        assertEquals("Not enough taps yet to say where they land.", TrainerBook.lean(emptyMap(), board))
    }

    @Test fun somethingThatGoesWrongLessOftenLatelyIsGettingBetter() {
        val t = Tally()
        repeat(4) { t.add(10, 4) }
        assertEquals(0, t.trend)
        repeat(4) { t.add(10, 0) }
        assertEquals(-1, t.trend)
        val worse = Tally()
        repeat(6) { worse.add(10, 0) }
        repeat(3) { worse.add(10, 5) }
        assertEquals(1, worse.trend)
        assertEquals("too few to say", 0, Tally().apply { add(5, 3) }.trend)
    }

    @Test fun theBookComesBackFromItsFileAsItWent() {
        val book = TrainerBook()
        book.trials = true
        repeat(3) { book.add(line(it, 40f + it, if (it == 1) Float.NaN else 0.9f), run("becuase"), "qwerty") }
        book.read += listOf("the-lighthouse-cat-1", "the-lighthouse-cat-2")
        book.given.getOrPut("qwerty") { HashMap() }['o'] = floatArrayOf(0.08f, -0.04f)
        book.layouts.anchor("short")
        book.layouts.record(book.layouts.next(), 40f); book.layouts.record(book.layouts.next(), 44f)
        val text = TrainerCodec.write(book)
        val back = TrainerCodec.read(text)!!
        assertEquals(text, TrainerCodec.write(back))
        assertTrue(back.trials); assertEquals(3, back.runs.size); assertTrue(back.runs[1].accuracy.isNaN()); near(42f, back.runs[2].wpm)
        assertEquals(book.read, back.read)
        assertEquals("becuase", back.mistakes().first { it.word == "because" }.typed)
        near(0.08f, back.given.getValue("qwerty").getValue('o')[0])
        assertEquals("short", back.layouts.home); assertEquals("condensed", back.layouts.candidate); assertEquals(listOf(44f), back.layouts.candidateScores)
        // What is no book of this keyboard's is no book.
        assertNull(TrainerCodec.read("not json")); assertNull(TrainerCodec.read("{}")); assertNull(TrainerCodec.read("{\"v\": 99}"))
        // Forgetting leaves nothing, and the switch off.
        back.forget()
        assertEquals(TrainerCodec.write(TrainerBook()), TrainerCodec.write(back))
        assertFalse(back.trials)
    }
}
