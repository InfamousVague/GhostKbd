package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs

/**
 * Where this thumb is landing in the field being typed in ([ThumbLean]), and a word's taps read by it
 * ([TapDecoder.load], [Autocorrect.settled]): against the real English list, with no phone under them. What the
 * service does with it is Android's and out of a unit test's reach, so that is held against its source, as
 * [TapKeysSourceTest] holds the view. What it does to typing is measured, not tested: tool/versus/scoreboard.py.
 */
class ThumbLeanTest {
    companion object {
        private val assets: File by lazy { listOf("android/app/src/main/assets", "src/main/assets", "../app/src/main/assets").map(::File).first { it.exists() } }
        private val main: File by lazy { listOf("android/app/src/main", "src/main", "../app/src/main").map(::File).first { it.exists() } }

        val ac: Autocorrect by lazy {
            Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }.also {
                val state = LanguageState.of(listOf("en"), emptyMap(), "")
                it.loadNow(state.dictionaries, state.board)
            }
        }

        /** A drawn QWERTY, as [TypingAccuracyTest] has it: keys 100 wide and 150 tall, the space bar under c v b n m. */
        const val W = 100f
        const val H = 150f
        val centres: Map<Char, Pair<Float, Float>> = buildMap {
            listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (s, x0) ->
                s.forEachIndexed { i, c -> put(c, ((x0 + i + 0.5f) * W) to ((row + 0.5f) * H)) }
            }
        }
        val space = floatArrayOf(2.5f * W, 3 * H, 8.5f * W, 4 * H)

        /**
         * [meant] as a thumb taps it that lands [leanX] and [leanY] key widths from where it aims, right and down,
         * with [scatter] key widths of scatter about that each way.
         */
        fun tapped(meant: String, leanX: Float, leanY: Float, scatter: Float = 0f, rnd: Random? = null): Taps {
            val xs = FloatArray(meant.length); val ys = FloatArray(meant.length)
            for (i in meant.indices) {
                val c = centres.getValue(meant[i])
                xs[i] = c.first + (leanX + scatter * (rnd?.nextGaussian()?.toFloat() ?: 0f)) * W
                ys[i] = c.second + (leanY + scatter * (rnd?.nextGaussian()?.toFloat() ?: 0f)) * W
            }
            return Taps(xs, ys, centres, W, space)
        }

        /** The key under a tap: its row by the tap's height, the nearest key of that row across. */
        fun under(x: Float, y: Float): Char {
            val row = (y / H).toInt().coerceIn(0, 2)
            return centres.filter { (it.value.second / H).toInt() == row }.minByOrNull { abs(it.value.first - x) }!!.key
        }

        /** The letters [taps] typed. */
        fun typed(taps: Taps) = String(CharArray(taps.xs.size) { under(taps.xs[it], taps.ys[it]) })

        /** [words] typed into a field one after another by that thumb, each settled as what was meant. */
        fun teach(lean: ThumbLean, words: List<String>, leanX: Float, leanY: Float, scatter: Float, rnd: Random) {
            for (w in words) {
                val taps = tapped(w, leanX, leanY, scatter, rnd)
                lean.learn(w.toCharArray(), taps)
            }
        }

        /**
         * Prediction on, with no table under it: a scorer that lifts no word. The lean is read only with prediction
         * on ([Autocorrect.aimed]), so the readings below are asked with this.
         */
        val predicting: Scorer by lazy {
            Scorer(NextWordText.place("I saw ", { it.isLetter() }, false)!!, emptyList(), FloatArray(0), emptyList(), emptyList(), object : Scorer.Lookup {
                override fun isWord(lower: String) = ac.isWord(lower)
                override fun form(lower: String) = lower
                override fun count(lower: String) = 0f
                override fun starting(prefix: String) = emptyList<Pair<String, Float>>()
                override fun fills() = emptyList<String>()
            })
        }

        val WORDS = "the quick brown fox jumps over a lazy dog and then it runs back home to sleep for an hour or two".split(' ')
    }

    // --- the estimate -------------------------------------------------------------------------------------------------

    @Test fun nothingIsKnownAsAFieldBegins() {
        val lean = ThumbLean()
        assertEquals(0f, lean.x, 0f)
        assertEquals(0f, lean.y, 0f)
        // And the taps are then read as they are: the very same taps, with no lean on them.
        val taps = tapped("hello", 0f, 0f)
        assertSame(taps, lean.read(taps))
        assertEquals(0f, taps.leanX, 0f)
        assertEquals(0f, taps.leanY, 0f)
    }

    @Test fun aLeanIsFoundWithinAFewWords() {
        // A careful thumb landing a quarter of a key right and a fifth down: after a word it is not believed, after
        // a sentence it is known to within a few hundredths of a key.
        val lean = ThumbLean()
        val rnd = Random(1)
        teach(lean, WORDS.take(1), 0.24f, 0.2f, 0.18f, rnd)
        assertTrue("after one word: ${lean.x}, ${lean.y}", abs(lean.x) < 0.12f && abs(lean.y) < 0.12f)
        teach(lean, WORDS.drop(1).take(9), 0.24f, 0.2f, 0.18f, rnd)
        assertEquals(0.24f, lean.x, 0.07f)
        assertEquals(0.2f, lean.y, 0.07f)
        // The taps are then read by it, and are otherwise the same taps.
        val taps = tapped("hello", 0.24f, 0.2f)
        val read = lean.read(taps)
        assertSame(taps.xs, read.xs)
        assertEquals(lean.x, read.leanX, 0f)
        assertEquals(lean.y, read.leanY, 0f)
    }

    @Test fun aThumbThatLandsWhereItAimsIsNotGivenALean() {
        // Scattered as widely as the bench's sloppy thumb, with no lean: twenty fields of twenty-two words. A few
        // taps can look like a lean by chance, so early in a field it is now and then given a little of one; that
        // is rare, never much, and gone by the field's end.
        var words = 0; var leaning = 0; var most = 0f; var atEnd = 0f
        for (seed in 1L..20L) {
            val lean = ThumbLean()
            val rnd = Random(seed)
            for (w in WORDS) {
                teach(lean, listOf(w), 0f, 0f, 0.33f, rnd)
                val far = maxOf(abs(lean.x), abs(lean.y))
                words++
                if (far > 0.1f) leaning++
                most = maxOf(most, far)
            }
            atEnd = maxOf(atEnd, abs(lean.x), abs(lean.y))
        }
        assertTrue("given a tenth of a key's lean at $leaning words of $words", leaning * 20 <= words)
        assertTrue("the most it was given on the way: $most", most < 0.3f)
        assertTrue("the most it was given at a field's end: $atEnd", atEnd < 0.08f)
    }

    @Test fun aNewFieldBeginsAtNothing() {
        val lean = ThumbLean()
        teach(lean, WORDS, 0.24f, 0.2f, 0.18f, Random(2))
        assertTrue(lean.x > 0.15f)
        lean.newField()
        assertEquals(0f, lean.x, 0f)
        assertEquals(0f, lean.y, 0f)
        assertEquals(0f, lean.taps, 0f)
    }

    @Test fun itFollowsAChangeOfHand() {
        // A long field: the thumb leans one way for thirty words, then the other way. What is older fades
        // ([ThumbLean.MEMORY]): a dozen words on it no longer believes the old side, and sixty on it has the new one.
        val lean = ThumbLean()
        val rnd = Random(3)
        teach(lean, WORDS + WORDS.take(8), 0.24f, 0.2f, 0.18f, rnd)
        assertTrue(lean.x > 0.15f)
        assertTrue("it holds no more than MEMORY taps: ${lean.taps}", lean.taps <= ThumbLean.MEMORY)
        teach(lean, WORDS.take(12), -0.24f, 0.2f, 0.18f, rnd)
        assertTrue("a dozen words after the change: ${lean.x}", lean.x < 0.12f)
        teach(lean, WORDS.drop(12) + WORDS + WORDS + WORDS.take(4), -0.24f, 0.2f, 0.18f, rnd)
        assertEquals(-0.24f, lean.x, 0.08f)
    }

    @Test fun aFixTakenBackIsUnlearned() {
        val lean = ThumbLean()
        val rnd = Random(4)
        teach(lean, WORDS.take(8), 0.24f, 0.2f, 0.18f, rnd)
        val x = lean.x; val y = lean.y; val taps = lean.taps
        teach(lean, listOf("jumps"), 0.24f, 0.2f, 0.18f, rnd)
        assertTrue(lean.taps > taps)
        lean.unlearn()
        assertEquals(x, lean.x, 0f)
        assertEquals(y, lean.y, 0f)
        assertEquals(taps, lean.taps, 0f)
        // Only the last word is ever taken back: a word that taught nothing leaves nothing to take.
        lean.pass()
        lean.unlearn()
        assertEquals(taps, lean.taps, 0f)
    }

    @Test fun aTapFarFromItsKeyTeachesNothing() {
        // "form" put right to "from": the o's tap is read as r's and the r's as o's, each five keys from the key it
        // is now read for. Neither says anything of where the thumb lands; the f and the m do.
        val lean = ThumbLean()
        val taps = tapped("form", 0f, 0f)
        lean.learn("from".toCharArray(), taps)
        assertEquals(2f, lean.taps, 0f)
    }

    // --- how tightly it types ([ThumbLean.tight]) ---------------------------------------------------------------------

    @Test fun aFieldBeginsWithSpatialAsItStands() {
        val lean = ThumbLean()
        assertEquals(1f, lean.tight, 0f)
        assertEquals(1f, lean.read(tapped("hello", 0f, 0f)).tight, 0f)
        teach(lean, WORDS, 0f, 0f, 0.36f, Random(5))
        assertTrue(lean.tight < 1f)
        lean.newField()
        assertEquals(1f, lean.tight, 0f)
    }

    @Test fun aScatteredThumbIsReadLooselyAndACarefulOneTightly() {
        // Twenty fields of a sentence each: a thumb that scatters 0.36 of a key is read below SPATIAL by the
        // sentence's end, never below the floor; one that scatters 0.16 above it, never above the ceiling.
        for (seed in 1L..20L) {
            val sloppy = ThumbLean()
            teach(sloppy, WORDS, 0f, 0f, 0.36f, Random(seed))
            assertTrue("sloppy ${sloppy.tight}", sloppy.tight < 0.9f)
            assertTrue(sloppy.tight >= ThumbLean.SPREAD_LOW)
            val careful = ThumbLean()
            teach(careful, WORDS, 0.24f, 0.2f, 0.16f, Random(seed))
            assertTrue("careful ${careful.tight}", careful.tight > 1f)
            assertTrue(careful.tight <= ThumbLean.SPREAD_HIGH)
            // Read with it: the same taps, the lean and the spread on them.
            val taps = tapped("hello", 0f, 0f)
            assertEquals(sloppy.tight, sloppy.read(taps).tight, 0f)
            assertSame(taps.xs, sloppy.read(taps).xs)
        }
    }

    @Test fun oneWildTapDoesNotMakeACarefulThumbLoose() {
        // A careful thumb's sentence, then a word with one tap 0.9 of a key off (still within REACH, so it counts).
        val lean = ThumbLean()
        teach(lean, WORDS, 0f, 0f, 0.16f, Random(6))
        val before = lean.tight
        val taps = tapped("jumps", 0f, 0f).also { it.xs[2] += 0.9f * W }
        lean.learn("jumps".toCharArray(), taps)
        assertTrue("$before then ${lean.tight}", lean.tight >= 1f)
        // And a fix taken back takes its spread with it.
        lean.unlearn()
        assertEquals(before, lean.tight, 0f)
    }

    @Test fun aWildTapInAFieldsSecondWordLoosensACarefulThumbForAWhile() {
        // The weak place of a plain variance: one tap 0.9 of a key off in the second word of a field, when only the
        // first word's three taps and the prior stand against it. Twenty careful fields: it is read looser than
        // SPATIAL straight after (never below the floor), and tight again by the sentence's end. This is the cost
        // docs/versus.md states ("A wild tap early in a field"); holding each square ([ThumbLean.SPREAD_CAP]) was
        // measured and left off.
        var loosened = 0
        for (seed in 1L..20L) {
            val lean = ThumbLean()
            val rnd = Random(100 + seed)
            teach(lean, WORDS.take(1), 0f, 0f, 0.16f, rnd)
            val taps = tapped(WORDS[1], 0f, 0f, 0.16f, rnd).also { it.xs[2] = centres.getValue(WORDS[1][2]).first + 0.9f * W }
            lean.learn(WORDS[1].toCharArray(), taps)
            if (lean.tight < 1f) loosened++
            assertTrue("seed $seed: ${lean.tight}", lean.tight >= ThumbLean.SPREAD_LOW)
            teach(lean, WORDS.drop(2), 0f, 0f, 0.16f, rnd)
            assertTrue("seed $seed at the end: ${lean.tight}", lean.tight >= 1f)
        }
        assertTrue("loosened in $loosened of 20", loosened >= 15)
    }

    @Test fun theBoundsMoveWithSpatial() {
        // SPREAD_LOW and SPREAD_HIGH are factors on SPATIAL: with SPATIAL moved to 0.9 a scattered thumb is still
        // read looser than it, and a careful one tighter (with 0.9 as a value of SPATIAL the spread did nothing).
        val was = TapDecoder.SPATIAL
        try {
            TapDecoder.SPATIAL = 0.9f
            val sloppy = ThumbLean()
            teach(sloppy, WORDS, 0f, 0f, 0.36f, Random(8))
            assertTrue("sloppy ${sloppy.tight}", sloppy.tight < 0.9f && sloppy.tight >= ThumbLean.SPREAD_LOW)
            val careful = ThumbLean()
            teach(careful, WORDS, 0f, 0f, 0.16f, Random(8))
            assertTrue("careful ${careful.tight}", careful.tight > 1f && careful.tight <= ThumbLean.SPREAD_HIGH)
        } finally { TapDecoder.SPATIAL = was }
    }

    @Test fun theSpreadIsReadWithEnglishAloneOnly() {
        // With English and German loaded the spread is not read, whatever it is: German's words were never measured
        // with it. English alone reads it (some word below comes out otherwise read loosely).
        val both = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }.also {
            val state = LanguageState.of(listOf("en", "de"), emptyMap(), "")
            it.loadNow(state.dictionaries, state.board)
        }
        fun scorer(a: Autocorrect): Scorer {
            val lookup = object : Scorer.Lookup {
                override fun isWord(lower: String) = a.isWord(lower)
                override fun form(lower: String) = lower
                override fun count(lower: String) = 0f
                override fun starting(prefix: String) = emptyList<Pair<String, Float>>()
                override fun fills() = emptyList<String>()
            }
            val place = NextWordText.place("", { Character.isLetter(it) || it == '\'' }, false)!!
            return Scorer(place, emptyList(), FloatArray(0), emptyList(), emptyList(), lookup)
        }
        var english = 0
        for (word in listOf("thw", "teh", "wirk", "yom", "cin", "fot", "ghis", "jello", "nrw", "tge", "abd", "ger")) {
            val taps = Taps(tapped(word, 0f, 0f).xs.also { it[0] += 0.4f * W }, tapped(word, 0f, 0f).ys, centres, W, space)
            val loose = Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, 0f, 0f, 0.4f)
            assertEquals(word, both.correct(word, scorer(both), taps), both.correct(word, scorer(both), loose))
            if (ac.correct(word, scorer(ac), taps) != ac.correct(word, scorer(ac), loose)) english++
        }
        assertTrue("English alone read the spread in none of them", english > 0)
    }

    @Test fun withTheSpreadOffTapsAreReadWithSpatialAsItStands() {
        val lean = ThumbLean()
        teach(lean, WORDS, 0f, 0f, 0.36f, Random(7))
        val was = ThumbLean.SPREAD_ON
        try {
            ThumbLean.SPREAD_ON = false
            assertEquals(1f, lean.read(tapped("hello", 0f, 0f)).tight, 0f)
        } finally { ThumbLean.SPREAD_ON = was }
    }

    @Test fun withNoNextWordModelTheSpreadIsNotRead() {
        // Prediction off (no scorer): every word is read as it was before there was a spread, however loose.
        for (word in listOf("thw", "teh", "wirk", "inthe", "yom", "ghostkbd", "definately", "mapbox")) {
            val taps = tapped(word, 0.1f, 0.1f)
            val loose = Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, 0f, 0f, 0.4f)
            assertEquals(word, ac.correct(word, null, taps), ac.correct(word, null, loose))
        }
    }

    // --- which words teach it -----------------------------------------------------------------------------------------

    @Test fun onlyAWordThatCanBeTrustedTeaches() {
        val lean = ThumbLean()
        // A word of the lists, left as typed: every tap teaches.
        ac.settled(lean, "hello", null, tapped("hello", 0.1f, 0f))
        assertEquals(5f, lean.taps, 0f)
        // Letters no list has may be anything: nothing.
        ac.settled(lean, "hkpelwss", null, tapped("hkpelwss", 0.1f, 0f))
        assertEquals(5f, lean.taps, 0f)
        // Mended: each tap against the letter that now stands for it.
        ac.settled(lean, "thw", "the", tapped("thw", 0.1f, 0f))
        assertEquals(8f, lean.taps, 0f)
        // A contraction's apostrophe was never tapped: the letters still stand one to a tap.
        val dont = tapped("dont", 0.1f, 0f)
        ac.settled(lean, "dont", "don't", dont)
        assertEquals(12f, lean.taps, 0f)
        // A letter missed, or two words parted: which tap was which letter's is not known.
        ac.settled(lean, "helo", "hello", tapped("helo", 0.1f, 0f))
        ac.settled(lean, "inthe", "in the", tapped("inthe", 0.1f, 0f))
        assertEquals(12f, lean.taps, 0f)
        // No taps (a word pasted, swiped, or ended where the keys were not known): nothing.
        ac.settled(lean, "hello", null, null)
        assertEquals(12f, lean.taps, 0f)
    }

    // --- a word read by it --------------------------------------------------------------------------------------------

    @Test fun withNoLeanAWordIsReadAsItAlwaysWas() {
        val fresh = ThumbLean()
        for (word in listOf("hello", "thw", "teh", "wirk", "inthe", "yom", "ghostkbd", "definately")) {
            val taps = tapped(word, 0.2f, 0.1f)
            assertEquals(word, ac.correct(word, predicting, taps), ac.correct(word, predicting, fresh.read(taps)))
        }
    }

    @Test fun aWordIsReadByWhereTheThumbIsLanding() {
        // "tom" by a thumb landing 0.3 of a key to the right, its t just over the line onto y: "yom", which the
        // lists have as a rare word, so that it stands. Read by that lean the taps were aimed at t, o and m.
        val taps = tapped("tom", 0.3f, 0f).also { it.xs[0] = centres.getValue('t').first + 0.55f * W }
        assertEquals("yom", typed(taps))
        assertNull(ac.correct("yom", predicting, taps))
        val right = Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, 0.3f, 0f)
        assertEquals("tom", ac.correct("yom", predicting, right))
        // The same taps by a thumb known to land to the left were aimed further right still: "yom" stands.
        val left = Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, -0.15f, 0f)
        assertNull(ac.correct("yom", predicting, left))
    }

    @Test fun aWordTypedAsMeantByALeaningThumbIsLeftAlone() {
        // Every tap a quarter of a key right and a fifth down of its key's middle, and still on its key.
        for (word in listOf("hello", "form", "from", "there", "their", "were", "where", "on", "in", "of", "if")) {
            val taps = tapped(word, 0.25f, 0.2f)
            assertEquals(word, typed(taps))
            assertNull("$word as it landed", ac.correct(word, predicting, taps))
            assertNull("$word read by its lean", ac.correct(word, predicting, Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, 0.25f, 0.2f)))
        }
        // A name no list has, the same.
        for (word in listOf("ghostkbd", "zustand", "kekw")) {
            if (ac.isWord(word)) continue
            val taps = tapped(word, 0.25f, 0.2f)
            if (typed(taps) != word) continue
            assertNull("$word read by its lean", ac.correct(word, predicting, Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, 0.25f, 0.2f)))
        }
    }

    @Test fun aWordTypedInTheMiddlesOfItsKeysStandsWhateverTheLean() {
        // A lean that is wrong for these taps (they are in the middles of their keys) moves none of them off its key.
        for (word in listOf("hello", "form", "there", "on", "of")) {
            val taps = tapped(word, 0f, 0f)
            assertNull(word, ac.correct(word, predicting, Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, 0.25f, 0.2f)))
        }
    }

    @Test fun theBarsTapIsReadByTheLeanToo() {
        // "about" with its b landed on the bar, a quarter of the way down it: "a out", and two words that stand. By a
        // thumb known to land a third of a key low, that tap was aimed above the bar's top edge: one word.
        val a = tapped("a", 0f, 0f); val out = tapped("out", 0f, 0f)
        val bar = space[1] + 0.25f * (space[3] - space[1])
        val xs = a.xs + floatArrayOf(centres.getValue('b').first) + out.xs
        val ys = a.ys + floatArrayOf(bar) + out.ys
        assertNull(ac.rejoin("a", "out", "a", predicting, Taps(xs, ys, centres, W, space)))
        assertEquals("about", ac.rejoin("a", "out", "a", predicting, Taps(xs, ys, centres, W, space, 0f, 0.33f)))
        // And two words that are two words stand, lean or no lean, however high on the bar the space was tapped.
        val isA = tapped("is", 0f, 0f); val aa = tapped("a", 0f, 0f)
        val top = space[1] + 0.03f * (space[3] - space[1])
        val two = Taps(isA.xs + floatArrayOf(centres.getValue('n').first) + aa.xs, isA.ys + floatArrayOf(top) + aa.ys, centres, W, space, 0f, 0.33f)
        assertNull(ac.rejoin("is", "a", "is", predicting, two))
    }

    @Test fun `only an English board with prediction on reads by the lean`() {
        // The "yom" of aWordIsReadByWhereTheThumbIsLanding, by a thumb known to land 0.3 of a key right: "tom" with
        // prediction on. With it off (no scorer) the lean is not read, and every reading is as with no lean at all.
        val taps = tapped("tom", 0.3f, 0f).also { it.xs[0] = centres.getValue('t').first + 0.55f * W }
        val right = Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, 0.3f, 0f)
        assertEquals("tom", ac.correct("yom", predicting, right))
        assertNull(ac.correct("yom", null, right))
        for (word in listOf("hello", "thw", "teh", "wirk", "inthe", "ghostkbd", "definately", "doodled")) {
            val plain = tapped(word, 0.2f, 0.1f)
            val leant = Taps(plain.xs, plain.ys, plain.keys, plain.keyW, plain.space, 0.25f, 0.2f)
            assertEquals(word, ac.correct(word, null, plain), ac.correct(word, null, leant))
            assertEquals(word, ac.suggest(word, null, plain).words, ac.suggest(word, null, leant).words)
        }
        val a = tapped("a", 0f, 0f); val out = tapped("out", 0f, 0f)
        val xs = a.xs + floatArrayOf(centres.getValue('b').first) + out.xs
        val ys = a.ys + floatArrayOf(space[1] + 0.25f * (space[3] - space[1])) + out.ys
        assertNull(ac.rejoin("a", "out", "a", null, Taps(xs, ys, centres, W, space, 0f, 0.33f)))
    }

    @Test fun `another language learns nothing and lets go of what it held`() {
        val other = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }.also {
            val state = LanguageState.of(listOf("es"), emptyMap(), "")
            it.loadNow(state.dictionaries, state.board)
        }
        val lean = ThumbLean()
        teach(lean, WORDS, 0.3f, 0.2f, 0.05f, Random(3))
        assertTrue(lean.x > 0.1f)
        val taps = tapped("casa", 0.3f, 0.2f)
        other.settled(lean, typed(taps), "casa", taps)
        assertEquals(0f, lean.x, 0f)
        assertEquals(0f, lean.y, 0f)
        assertEquals(0f, lean.taps, 0f)
    }

    @Test fun `keys of another width begin it again`() {
        val lean = ThumbLean()
        teach(lean, WORDS, 0.3f, 0.2f, 0.05f, Random(4))
        assertTrue(lean.x > 0.1f)
        val same = tapped("the", 0f, 0f)
        assertEquals(lean.x, lean.read(same).leanX, 0f)
        // The Fold opened: keys half as wide again. Read as they landed, and nothing is held.
        val wide = Taps(same.xs, same.ys, same.keys, W * 1.5f, same.space)
        assertSame(wide, lean.read(wide))
        assertEquals(0f, lean.taps, 0f)
    }

    // --- the service --------------------------------------------------------------------------------------------------

    /** A source with its comments taken out: what it does, not what it says. */
    private fun code(name: String) = File(main, "kotlin/com/infamousvague/devkeyboard/$name").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun `the estimate is never written down`() {
        // It lives in memory, for the field being typed in. Nothing in it can reach a file or a preference.
        val lean = code("ThumbLean.kt")
        for (word in listOf("import ", "File", "Preferences", "prefs", "Context", "Stream", "Json", "Log.")) assertTrue(word, !lean.contains(word))
    }

    @Test fun `the service begins it at every field and feeds it only from a corrected word's end`() {
        val service = code("DevKeyboardService.kt")
        // One estimate, begun again as a field is entered (not as one is restarted mid-typing) and as one is left.
        assertEquals(1, Regex("ThumbLean\\(\\)").findAll(service).count())
        assertTrue(service.contains("if (!restarting) lean.newField()"))
        val finish = service.substring(service.indexOf("override fun onFinishInput()")).substringBefore("\n    }")
        assertTrue(finish, finish.contains("lean.newField()"))
        // Taught in one place: straight after autocorrect has settled a word, which no password field ever gets to
        // (correctLastWord gives up first where autocorrect is not allowed, and it is never allowed in one).
        // A word left as typed teaches as it is settled, a fix only once it has been put in the field: alone, or
        // together with a change the second look made to the word before (Autocorrect.again).
        assertEquals(3, Regex("\\.settled\\(lean").findAll(service).count())
        assertEquals(0, Regex("lean\\.learn\\(").findAll(service).count())
        val correct = service.substring(service.indexOf("private fun correctLastWord(")).substringBefore("\n    }")
        val allowed = correct.indexOf("if (!autocorrectAllowed()")
        val asked = correct.indexOf("autocorrect.correct(word, scorer, taps")
        val asTyped = correct.indexOf("if (found == null) autocorrect.settled(lean, word, null, taps)")
        val replaced = correct.indexOf("if (!editor.replaceBefore(word, fixed + separator")
        val fixed = correct.lastIndexOf("autocorrect.settled(lean, word, fixed, taps)")
        assertTrue(correct, allowed in 0 until asked && asked < asTyped && asTyped < replaced && replaced < fixed)
        assertTrue(correct, correct.contains("{ lean.pass(); return false }"))
        // Two scraps put together: the first scrap's lesson is taken back once the whole word is in the field.
        val rejoined = service.substring(service.indexOf("private fun rejoined(")).substringBefore("\n    }")
        assertTrue(rejoined, rejoined.indexOf("lean.unlearn()") > rejoined.indexOf("editor.replaceBefore(standing"))
        val gate = service.substring(service.indexOf("private fun autocorrectAllowed()")).substringBefore("\n    }")
        for (kind in listOf("TYPE_TEXT_VARIATION_PASSWORD", "TYPE_TEXT_VARIATION_VISIBLE_PASSWORD", "TYPE_TEXT_VARIATION_WEB_PASSWORD")) assertTrue(kind, gate.contains(kind))
        // Both readings of a word's taps go by it: the word's own, and the letters with the bar's tap among them.
        val taps = service.substring(service.indexOf("private fun tapsFor(word: String)")).substringBefore("\n    }")
        assertTrue(taps, taps.contains("return lean.read(Taps("))
        val across = service.substring(service.indexOf("private fun tapsAcross(")).substringBefore("\n    }")
        assertTrue(across, across.contains("return lean.read(Taps("))
        // A fix taken back takes its lesson with it.
        val undo = service.substring(service.indexOf("private fun undoCorrection(")).substringBefore("\n    }")
        assertTrue(undo, undo.contains("lean.unlearn()"))
    }
}
