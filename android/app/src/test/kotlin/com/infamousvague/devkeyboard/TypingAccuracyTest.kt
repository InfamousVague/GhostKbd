package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Autocorrect reading a word by where its taps landed ([TapDecoder], 0.11.0), what it has learned from the person's
 * own corrections ([Autocorrect.setMistakes], [Autocorrect.byChoice]), and Mockingbird's samples as word pairs
 * ([PersonalPairs.of]): against the real English list and starter table, with no phone under them.
 */
class TypingAccuracyTest {
    companion object {
        private val assets: File by lazy { listOf("android/app/src/main/assets", "src/main/assets", "../app/src/main/assets").map(::File).first { it.exists() } }

        private fun english(): Autocorrect {
            val ac = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
            val state = LanguageState.of(listOf("en"), emptyMap(), "")
            ac.loadNow(state.dictionaries, state.board)
            return ac
        }

        val ac: Autocorrect by lazy { english() }

        /**
         * [languages]' lists loaded on [layout] (the first language's own where it is not said), from the real
         * assets but for the files of [swapped]: each one's text in its place, or null for a file that is not there.
         */
        private fun loaded(languages: List<String>, layout: String = "", swapped: Map<String, String?> = emptyMap()): Autocorrect {
            val ac = Autocorrect { name -> if (name in swapped) swapped[name]?.byteInputStream() else File(assets, name).takeIf { it.exists() }?.inputStream() }
            val state = LanguageState.of(languages, emptyMap(), layout)
            ac.loadNow(state.dictionaries, state.board)
            return ac
        }

        /** What is likely at the end of [before], as next-word prediction has it with nothing typed yet (the starter table alone). */
        private fun scorer(ac: Autocorrect, before: String): Scorer? {
            val personal = PersonalPairs()
            val place = NextWordText.place(before, { Words.isWordChar(it, "'") }, false) ?: return null
            val lookup = object : Scorer.Lookup {
                override fun isWord(lower: String) = ac.isWord(lower)
                override fun form(lower: String) = personal.form(lower)
                override fun count(lower: String) = personal.unigram(lower)
                override fun starting(prefix: String) = personal.starting(prefix)
                override fun fills() = ac.commonest(6)
            }
            return Scorer(place, listOf(table), Scorer.weights(listOf(table), place.recent), personal.followers(place.prev), emptyList(), lookup)
        }

        /** English's names (dict/en_capitals.txt) in lower case. */
        private val names: Set<String> by lazy {
            File(assets, "dict/en_capitals.txt").readLines().map { it.substringBefore('\t').trim() }.filter { it.isNotEmpty() && it[0] != '#' }.map { it.lowercase() }.toSet()
        }

        /** dict/en_capitals_beside.tsv: for each language it judges, the names that are no names beside it. */
        private val beside: Map<String, Set<String>> by lazy {
            File(assets, "dict/en_capitals_beside.tsv").readLines().filter { it.isNotEmpty() && it[0] != '#' && '\t' in it }
                .associate { it.substringBefore('\t') to it.substringAfter('\t').split(' ').filter { w -> w.isNotEmpty() }.toSet() }
        }

        /** A drawn QWERTY: keys 100 wide and 150 tall, the middle row half a key in, the bottom a key and a half. */
        const val W = 100f
        const val H = 150f
        val centres: Map<Char, Pair<Float, Float>> = buildMap {
            listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (s, x0) ->
                s.forEachIndexed { i, c -> put(c, ((x0 + i + 0.5f) * W) to ((row + 0.5f) * H)) }
            }
        }
        val space = floatArrayOf(2.5f * W, 3 * H, 8.5f * W, 4 * H)

        /**
         * [word] as tapped: each letter in the middle of its key, but for the letters in [off], which landed that
         * many key widths to the right (and key heights down) of the middle.
         */
        fun taps(word: String, vararg off: Pair<Int, Pair<Float, Float>>): Taps {
            val by = off.toMap()
            val xs = FloatArray(word.length); val ys = FloatArray(word.length)
            for (i in word.indices) {
                val c = centres[word[i].lowercaseChar()]
                if (c == null) { xs[i] = Float.NaN; ys[i] = Float.NaN; continue }
                val (dx, dy) = by[i] ?: (0f to 0f)
                xs[i] = c.first + dx * W; ys[i] = c.second + dy * H
            }
            return Taps(xs, ys, centres, W, space)
        }

        private val table: NgramTable by lazy { File(assets, "ngram/en.tsv").useLines { NgramTable.parse(it) }!! }

        /**
         * What is likely next where [before] ends (it ends with the word being typed), by the starter table alone:
         * next-word prediction on and nothing learned.
         */
        fun after(before: String): Scorer {
            val place = NextWordText.place(before, { c -> Words.isWordChar(c, "'") }, false)!!
            val nothing = object : Scorer.Lookup {
                override fun isWord(lower: String) = ac.isWord(lower)
                override fun form(lower: String) = lower
                override fun count(lower: String) = 0f
                override fun starting(prefix: String) = emptyList<Pair<String, Float>>()
                override fun fills() = emptyList<String>()
            }
            return Scorer(place, listOf(table), Scorer.weights(listOf(table), place.recent), emptyList(), emptyList(), nothing)
        }

        /**
         * [TapDecoder] over a few words of its own, each as common as the next: what [TapDecoder.search] reaches and
         * what each reading costs, whatever the lists hold and however a reading is weighed afterwards.
         */
        class Few(vararg words: String) {
            private val names = words.sorted().toTypedArray()
            private val zipf = FloatArray(names.size) { 5f }
            /** Each key's neighbours on the drawn board: the keys beside it, and those above and below it. */
            private val neighbours: Map<Char, Set<Char>> = centres.mapValues { (k, at) ->
                centres.filter { (o, c) -> o != k && kotlin.math.hypot(c.first - at.first, c.second - at.second) <= 1.7f * W }.keys
            }
            private val decoder = TapDecoder(names, zipf, WordTrie(names, zipf), { c -> if (c in 'a'..'z') c else 0.toChar() }, neighbours)

            /**
             * The words [typed]'s [taps] could be, each with what its slips cost. [listed]: the typed letters spell a
             * common word. [reach]: asked as [Autocorrect.byTaps] asks.
             */
            fun read(typed: String, taps: Taps, listed: Boolean = false, reach: Boolean = true): Map<String, Float> {
                decoder.load(typed, taps)
                return decoder.search(-100f, TapDecoder.BUDGET, 0, slipsOnly = listed, reach = reach).associate { names[it.index] to it.cost }
            }
        }
    }

    // --- by the taps ------------------------------------------------------------------------------------------------

    @Test fun withNoTapsItCorrectsAsItAlwaysDid() {
        for (typed in listOf("teh", "recieve", "hello", "form", "im", "dont", "ghostkbd", "wierd", "thier", "i")) {
            assertEquals(typed, ac.correct(typed), ac.correct(typed, null, null))
        }
        assertEquals("the", ac.correct("teh"))
        assertEquals("I'm", ac.correct("im"))
        assertNull(ac.correct("hello"))
    }

    @Test fun aWordTappedCleanlyIsLeftAlone() {
        for (word in listOf("hello", "form", "from", "there", "their", "were", "where", "on", "in", "of", "if")) {
            assertNull("$word, every tap in the middle of its key", ac.correct(word, null, taps(word)))
        }
    }

    @Test fun aNameNoListHasTypedCleanlyIsLeftAlone() {
        for (word in listOf("ghostkbd", "zustand", "kekw", "supabase")) {
            if (ac.isWord(word)) continue
            assertNull("$word, every tap in the middle of its key", ac.correct(word, null, taps(word)))
        }
    }

    @Test fun aSlipIsMended() {
        assertEquals("the", ac.correct("thw", null, taps("thw")))
        assertEquals("the", ac.correct("teh", null, taps("teh")))
        assertEquals("because", ac.correct("becuase", null, taps("becuase")))
        assertEquals("definitely", ac.correct("definately", null, taps("definately")))
        assertEquals("tomorrow", ac.correct("tommorow", null, taps("tommorow")))
        assertEquals("Hello", ac.correct("Hrllo", null, taps("Hrllo")))
    }

    @Test fun whereTheTapLandedDecidesBetweenTwoWords() {
        // "br" with its r tapped on the line between e and r is "be"; with the r tapped in the middle of its key it is "br".
        assertNull(ac.correct("br", null, taps("br")))
        assertEquals("be", ac.correct("br", null, taps("br", 1 to (-0.5f to 0f))))
        // "ho" with its h on the line toward g is "go"; tapped squarely it is "ho". With no taps it was always left.
        assertNull(ac.correct("ho", null, taps("ho")))
        assertEquals("go", ac.correct("ho", null, taps("ho", 0 to (-0.5f to 0f))))
        assertNull(ac.correct("ho"))
        // A word no list has goes to the word its taps were nearest.
        assertEquals("work", ac.correct("wirk", null, taps("wirk", 1 to (0.45f to 0f))))
    }

    @Test fun twoTapsJustOverTheirLinesAreOneChangeBetweenThem() {
        // "the" with the h tapped just under its line (on the b below it) and the e just over its line (on the r
        // beside it): three letters, two of them another key's, and still "the". The search's bound on changes
        // counts each by how far its tap was from the key it is read as (TapDecoder.WHOLE_CHANGE).
        assertEquals("the", ac.correct("tbr", null, taps("tbr", 1 to (0f to -0.45f), 2 to (-0.45f to 0f))))
        assertEquals("he", ac.correct("jr", null, taps("jr", 0 to (-0.45f to 0f), 1 to (-0.45f to 0f))))
        // Both in the middles of the wrong keys, the same letters are another word, or no word: they are left.
        assertNull(ac.correct("tbr", null, taps("tbr")))
        assertNull(ac.correct("jr", null, taps("jr")))
        // One tap in the middle of the wrong key is one change, as it always was.
        assertEquals("the", ac.correct("thr", null, taps("thr")))
    }

    // What TapDecoder.search reaches where Autocorrect.byTaps asks (its reach), held to the costs themselves on a
    // few words of the test's own, and then to the real lists.

    @Test fun aShortWordIsReadALittlePastItsOneChange() {
        val the = Few("the")
        // The h a third of a key under its line (on b) and the e a quarter of a key over its line (on r): one change
        // and a twentieth between them. Read, for SHORT_PAST more than the two slips cost; not read without the reach.
        val near = taps("tbr", 1 to (0f to -0.3f), 2 to (-0.25f to 0f))
        assertEquals(1.35f + 0.75f + TapDecoder.SHORT_PAST, the.read("tbr", near).getValue("the"), 0.01f)
        assertNull(the.read("tbr", near, reach = false)["the"])
        // Both taps in the middles of the wrong keys are a change and three quarters: more than SHORT_MORE allows.
        assertNull(the.read("tbr", taps("tbr"))["the"])
        // A letter left out and another just over its line: "eit" is "with", not "wit" (which one change reaches).
        assertEquals("with", ac.correct("eit", null, taps("eit", 0 to (-0.4f to 0f))))
        assertEquals("the", ac.correct("tbr", null, near))
        // Four letters are not short: two changes and no more, and four taps a tenth of a key off their middles toward
        // the keys of "that" are two and two fifths.
        val that = Few("that")
        assertNull(that.read("ygsr", taps("ygsr", 0 to (-0.1f to 0f), 1 to (0.1f to 0f), 2 to (-0.1f to 0f), 3 to (0.1f to 0f)))["that"])
        assertEquals(4 * 0.9f, that.read("ygsr", taps("ygsr", 0 to (-0.2f to 0f), 1 to (0.2f to 0f), 2 to (-0.2f to 0f), 3 to (0.2f to 0f))).getValue("that"), 0.01f)
    }

    @Test fun lettersThatSpellACommonWordMayHaveLostALetterOrHadTwoSwapped() {
        val few = Few("th", "the", "to", "ot", "in", "inn", "ink", "so", "soo")
        // Without the reach a common word is read by slips of the thumb alone, as it was: nothing but itself here.
        assertEquals(setOf("th"), few.read("th", taps("th"), listed = true, reach = false).keys)
        assertEquals(setOf("ot"), few.read("ot", taps("ot"), listed = true, reach = false).keys)
        // With it: a letter left out, two swapped, a letter tapped twice, each for LISTED_EDIT at the least.
        assertEquals(maxOf(TapDecoder.REACH_OMIT_END, TapDecoder.LISTED_EDIT), few.read("th", taps("th"), listed = true).getValue("the"), 0.01f)
        assertEquals(maxOf(TapDecoder.SWAP, TapDecoder.LISTED_EDIT), few.read("ot", taps("ot"), listed = true).getValue("to"), 0.01f)
        assertEquals(maxOf(TapDecoder.INSERT_DOUBLED, TapDecoder.LISTED_EDIT), few.read("soo", taps("soo"), listed = true).getValue("so"), 0.01f)
        assertEquals(maxOf(TapDecoder.INSERT_DOUBLED, TapDecoder.LISTED_EDIT), few.read("inn", taps("inn"), listed = true).getValue("in"), 0.01f)
        // Letters no list has are read as they always were: the swap for SWAP, the doubled letter for INSERT_DOUBLED.
        assertEquals(TapDecoder.SWAP, few.read("ot", taps("ot")).getValue("to"), 0.01f)
        assertEquals(TapDecoder.INSERT_DOUBLED, few.read("soo", taps("soo")).getValue("so"), 0.01f)
        // A tap that doubles nothing is never taken out of a common word: "ink" is not "in" with a k too many.
        assertNull(few.read("ink", taps("ink"), listed = true)["in"])
        // Where they spell no word, the k in the middle of its own key is a tap of its own, for REACH_INSERT.
        assertEquals(TapDecoder.REACH_INSERT, Few("in").read("ink", taps("ink")).getValue("in"), 0.01f)
        // Against the real lists, where the words before make the other word likely: and not without them.
        assertEquals("the", ac.correct("th", after("it was th"), taps("th")))
        assertEquals("to", ac.correct("ot", after("we are going ot"), taps("ot")))
        assertNull(ac.correct("ink", after("i live ink"), taps("ink")))
        for (word in listOf("on", "in", "of", "if", "is", "it", "to", "so", "no", "do", "me", "my", "we", "he", "be", "at", "as", "an", "or", "up", "us", "go", "ink", "inn", "off", "too", "see", "been", "here", "there", "form", "from")) {
            assertNull("$word, every tap in the middle of its key", ac.correct(word, null, taps(word)))
        }
    }

    @Test fun aWordTypedAsOneLetterMayHaveLostItsOther() {
        val few = Few("t", "to", "it")
        assertEquals(TapDecoder.LONE_OMIT, few.read("t", taps("t"), listed = true).getValue("to"), 0.01f)
        assertEquals(TapDecoder.LONE_OMIT, few.read("t", taps("t"), listed = true).getValue("it"), 0.01f)
        assertEquals(setOf("t"), few.read("t", taps("t"), listed = true, reach = false).keys)
        // "t" where "to" is likely is "to"; by itself a letter stands, whichever it is.
        assertEquals("to", ac.correct("t", after("i want t"), taps("t")))
        for (letter in "bcdefghjklmnopqrstuvwxyz") assertNull("$letter by itself", ac.correct(letter.toString(), null, taps(letter.toString())))
    }

    @Test fun aAndIStandWhereAWordOfTwoLettersWouldFit() {
        // LONE_OMIT reads every one-letter word, a and i too: they stand on their own frequency, even where the words
        // before make at, an, as, in, it, is or if likely. i stands as itself or as its capital.
        for (before in listOf("look a", "we are a", "it is a", "i want a", "she lives a", "they sat a", "a")) {
            assertNull(before, ac.correct("a", after(before), taps("a")))
        }
        for (before in listOf("i live i", "i think i", "is i", "what i", "i went i", "i")) {
            val out = ac.correct("i", after(before), taps("i"))
            assertTrue("$before: $out", out == null || out == "I")
        }
        assertNull(ac.correct("a", null, taps("a")))
        assertTrue(ac.correct("i", null, taps("i")).let { it == null || it == "I" })
    }

    @Test fun aLetterLeftOutAfterTheLastTapCostsAsItAlwaysDid() {
        val few = Few("civilize", "civilized", "wark", "work")
        // A word typed whole that a listed word goes on from: the letter after the last tap costs REACH_OMIT_END
        // (as OMIT), a letter left out within the word REACH_OMIT.
        assertEquals(TapDecoder.REACH_OMIT_END, few.read("civilize", taps("civilize")).getValue("civilized"), 0.01f)
        assertEquals(TapDecoder.OMIT, few.read("civilize", taps("civilize"), reach = false).getValue("civilized"), 0.01f)
        assertEquals(TapDecoder.REACH_OMIT, few.read("wrk", taps("wrk")).getValue("work"), 0.01f)
        assertNull(ac.correct("civilize", null, taps("civilize")))
    }

    @Test fun aTapTooManyCostsByWhereItLanded() {
        val the = Few("the")
        // "thre" for "the": the r is one tap too many. On the line beside e it is all but e tapped twice; in the
        // middle of its own key it is INSERT_DOUBLED and what a tap in the middle of the key beside costs to read as
        // e (1.5); a tap far from both keys beside it is a tap of its own, for REACH_INSERT.
        assertEquals(TapDecoder.INSERT_DOUBLED + 0.15f, the.read("thre", taps("thre", 2 to (-0.45f to 0f))).getValue("the"), 0.01f)
        assertEquals(TapDecoder.INSERT_DOUBLED + 1.5f, the.read("thre", taps("thre")).getValue("the"), 0.01f)
        assertEquals(TapDecoder.REACH_INSERT, the.read("thpe", taps("thpe")).getValue("the"), 0.01f)
        // Without the reach the plain costs stand: r is e's neighbour, p is nobody's here.
        assertEquals(TapDecoder.INSERT_NEAR, the.read("thre", taps("thre"), reach = false).getValue("the"), 0.01f)
        assertEquals(TapDecoder.INSERT, the.read("thpe", taps("thpe"), reach = false).getValue("the"), 0.01f)
        // The same key twice is a doubled letter, as it always was.
        assertEquals(TapDecoder.INSERT_DOUBLED, the.read("thee", taps("thee")).getValue("the"), 0.01f)
        // A character with no tap of its own (NaN) still goes by which keys are neighbours: r is e's.
        val blind = Taps(FloatArray(4) { Float.NaN }, FloatArray(4) { Float.NaN }, centres, W, space)
        assertEquals(TapDecoder.INSERT_NEAR, the.read("thre", blind).getValue("the"), 0.01f)
    }

    @Test fun oneOfTwoSwappedTapsMayHaveLandedOnANeighbour() {
        val few = Few("could", "work")
        // "wrik" for "work": the o and the r in the wrong order, and the o tapped on i, two fifths of a key from its
        // own middle toward o. SWAP and what the i costs to read as o.
        val wrik = taps("wrik", 2 to (0.4f to 0f))
        assertEquals(TapDecoder.SWAP + 0.3f, few.read("wrik", wrik).getValue("work"), 0.01f)
        // Without the reach, and with the tap in the middle of the i key (an i: more than SWAP_SLIP), "work" is
        // reached only as an o left out and an i too many: without the reach at the plain costs (i is k's
        // neighbour), with it at REACH_OMIT and REACH_INSERT (a tap in the middle of i is far from both r and k).
        assertEquals(TapDecoder.OMIT + TapDecoder.INSERT_NEAR, few.read("wrik", wrik, reach = false).getValue("work"), 0.01f)
        assertEquals(TapDecoder.REACH_OMIT + TapDecoder.REACH_INSERT, few.read("wrik", taps("wrik")).getValue("work"), 0.01f)
        // Both of the two on a neighbour's key ("weik": the r on e and the o on i) is not read: one slip to a swap.
        assertNull(few.read("weik", taps("weik", 1 to (0.4f to 0f), 2 to (0.4f to 0f)))["work"])
        // "iculs" for "could": c and o swapped, the o on i, and the d on s besides.
        val iculs = taps("iculs", 0 to (0.4f to 0f), 4 to (0.4f to 0f))
        assertEquals(TapDecoder.SWAP + 0.3f + 0.3f, few.read("iculs", iculs).getValue("could"), 0.01f)
        assertEquals("could", ac.correct("iculs", null, iculs))
        assertEquals("work", ac.correct("wrik", null, wrik))
    }

    /** [first], a tap on the space bar under the key [under], [down] of the bar's height below its top edge, then [second]. */
    private fun cut(first: String, second: String, under: Char, down: Float): Taps {
        val a = taps(first)
        val b = taps(second)
        val bar = floatArrayOf(space[1] + down * (space[3] - space[1]))
        return Taps(a.xs + floatArrayOf(centres.getValue(under).first) + b.xs, a.ys + bar + b.ys, centres, W, space)
    }

    @Test fun aWordCutInTwoByATapOnTheBarIsPutTogether() {
        // "being" with its n landed just under the top edge of the bar: "bei g" in the field, and one word by its taps.
        assertEquals("being", ac.rejoin("bei", "g", "bei", null, cut("bei", "g", 'n', 0.07f)))
        assertEquals("have", ac.rejoin("ha", "e", "ha", null, cut("ha", "e", 'v', 0.07f)))
        assertEquals("wouldn't", ac.rejoin("would", "t", "would", null, cut("would", "t", 'n', 0.07f)))
        // The scrap before the tap may have been mended at the space: what stands is held against the one word.
        assertEquals("practicing", ac.rejoin("practici", "g", "practice", null, cut("practici", "g", 'n', 0.07f)))
        // A capital the first scrap had is the word's.
        assertEquals("Being", ac.rejoin("Bei", "g", "Bei", null, cut("Bei", "g", 'n', 0.07f)))
        // Two scraps that could be two words are one only by a tap near the bar's top edge: "a out" is "about" there,
        // and stands where the tap was in the bar's middle.
        assertEquals("about", ac.rejoin("a", "out", "a", null, cut("a", "out", 'b', 0.07f)))
        assertNull(ac.rejoin("a", "out", "a", null, cut("a", "out", 'b', 0.5f)))
        // Two that are no words at all give way to the one word wherever on the bar the tap was.
        assertEquals("being", ac.rejoin("bei", "g", "bei", null, cut("bei", "g", 'n', 0.5f)))
    }

    @Test fun aWordWhoseLastLetterLandedOnTheBarIsPutTogetherAtTheNextSpace() {
        // "on" typed as "o" and a tap on the bar under n: with nothing after it (the next tap is the space itself,
        // which used to make a full stop of the two).
        assertEquals("on", ac.rejoin("o", "", "o", null, cut("o", "", 'n', 0.07f)))
        assertEquals("tragic", ac.rejoin("tragi", "", "tragi", null, cut("tragi", "", 'c', 0.07f)))
        // A word that stands by itself is not made a longer one: "the" and two spaces is a full stop, not "then".
        assertNull(ac.rejoin("the", "", "the", null, cut("the", "", 'n', 0.07f)))
        assertNull(ac.rejoin("see", "", "see", null, cut("see", "", 'n', 0.07f)))
        assertNull(ac.rejoin("o", "", "o", null, cut("o", "", 'n', 0.5f)))
    }

    @Test fun aWordMendedAtTheBarsTapIsHeldAtWhatTheMendCost() {
        // "women" with its w tapped as e and its n on the bar: the space made "some" of "eome", by reading the e as
        // an s a row away. What stands is worth less by that, and "women" reads the same taps for less.
        assertEquals("women", ac.rejoin("eome", "", "some", null, cut("eome", "", 'n', 0.05f)))
        // "wome" was made "women" at the space by a letter nobody typed. With the bar's tap for that letter it is
        // "women" as it stands, and the second space is its space: it comes back, so that no full stop is made.
        assertEquals("women", ac.rejoin("wome", "", "women", null, cut("wome", "", 'n', 0.07f)))
        // In the middle of the bar the tap was the space, the letter was left out, and two spaces are a full stop.
        assertNull(ac.rejoin("wome", "", "women", null, cut("wome", "", 'n', 0.5f)))
    }

    @Test fun aWordWhoseFirstLetterLandedOnTheBarIsPutTogether() {
        // The tap came straight after a space and ended no word: there is no first scrap. "ery" after it is "very".
        assertEquals("very", ac.rejoin("", "ery", "", null, cut("", "ery", 'v', 0.07f)))
        assertEquals("begin", ac.rejoin("", "egin", "", null, cut("", "egin", 'b', 0.07f)))
        // At the start of a sentence the capital is the word's; where the tap made a full stop of the space before
        // it, the capital was that full stop's, and goes with it.
        assertEquals("Begin", ac.rejoin("", "Egin", "", null, cut("", "egin", 'b', 0.07f)))
        assertEquals("very", ac.rejoin("", "Ery", "", null, cut("", "ery", 'v', 0.07f), stopped = true))
        // A word that stands after a full stop is not made a longer one: "It was. Ever since" is not "never".
        assertNull(ac.rejoin("", "Ever", "", null, cut("", "ever", 'n', 0.07f), stopped = true))
        assertNull(ac.rejoin("", "An", "", null, cut("", "an", 'c', 0.07f), stopped = true))
        assertNull(ac.rejoin("", "Ear", "", null, cut("", "ear", 'n', 0.07f), stopped = true))
        // Unless the two taps that made the full stop landed apart: no double tap, and nobody's full stop.
        assertEquals("near", ac.rejoin("", "Ear", "", null, cut("", "ear", 'n', 0.07f), stopped = true, doubled = false))
        // In the middle of the bar the tap was a space wherever the other landed.
        assertNull(ac.rejoin("", "Ear", "", null, cut("", "ear", 'n', 0.5f), stopped = true, doubled = false))
        // Letters that make a better word by themselves keep it: "thr" after a full stop is "the", whatever the bar's tap was under.
        assertNull(ac.rejoin("", "Thr", "", null, cut("", "thr", 'b', 0.07f), stopped = true))
    }

    @Test fun aSecondSpaceAfterASentenceLeavesTheNextWordAlone() {
        // Two spaces after a full stop (or a newline and a space) arm DevKeyboardService's Cut with no first scrap,
        // so the word typed next is offered to rejoin with the second space's tap as its first letter. A word that
        // stands is not made a longer one: "ice" is not "nice", "all" not "ball". A second space is tapped where the
        // first was, well inside the bar; from a third of the way down nothing changes. On the bar's very top edge,
        // where a letter of the bottom row lands, a word far rarer than the longer one gives way to it ("ow" under
        // the n is "now", "ore" under the m "more"), and no common one does.
        for (word in listOf("ice", "ear", "ever", "one", "all", "an", "at", "and", "ow", "ore", "old", "it", "a", "us", "is")) {
            for (under in "cvbnm") {
                for (down in floatArrayOf(0.07f, 0.3f, 0.5f, 0.8f)) {
                    val got = ac.rejoin("", word, "", null, cut("", word, under, down))
                    val edge = down < 0.1f && (word == "ow" && under == 'n' || word == "ore" && under == 'm')
                    if (edge) assertEquals("$word under $under at $down", "$under$word", got) else assertNull("$word under $under at $down", got)
                }
            }
        }
    }

    @Test fun withoutEnglishTheJoinsAndTheRejoinReadAsBefore() {
        // The join search with its fallen bar, slips in a first word and three words, and rejoin's reading of no
        // first scrap and of a closing mark, were set on English typing alone. With German alone loaded the joins
        // go by TapDecoder.pairs, as before them: the new search never runs (its work is never counted).
        val de = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
        val state = LanguageState.of(listOf("de"), emptyMap(), "")
        de.loadNow(state.dictionaries, state.board)
        TapDecoder.JOIN_WORK_LAST = -1
        assertEquals("ich bin", de.correct("ichbin", null, taps("ichbin")))
        de.correct("rinfachzuviel", null, taps("rinfachzuviel"))
        assertEquals(-1, TapDecoder.JOIN_WORK_LAST)
        assertEquals("in the", ac.correct("inthe", null, taps("inthe")))
        assertTrue(TapDecoder.JOIN_WORK_LAST >= 0)
        // No first scrap ("ery" after a second space, "very" in English) and a closing mark after the space are English's alone.
        assertEquals("very", ac.rejoin("", "ery", "", null, cut("", "ery", 'v', 0.07f)))
        assertNull(de.rejoin("", "ie", "", null, cut("", "ie", 'n', 0.07f)))
        assertEquals("tragic", ac.rejoin("tragi", "", "tragi", null, cut("tragi", "", 'c', 0.07f), doubled = false, mark = true))
        assertNull(de.rejoin("ihne", "", "ihne", null, cut("ihne", "", 'n', 0.07f), doubled = false, mark = true))
        // A word cut in two with both scraps reads in German as it did ("ih en" for "ihnen", under the n).
        assertEquals("ihnen", de.rejoin("ih", "en", "ih", null, cut("ih", "en", 'n', 0.07f)))
    }

    @Test fun twoTapsOnTheBarThatLandedApartAreNoDoubleTap() {
        // "i" and two spaces is "I." where the two were one double tap, as it always was.
        assertNull(ac.rejoin("i", "", "I", null, cut("i", "", 'n', 0f)))
        // Where the second landed away from the first, the first is held as no likelier a space than its place
        // says: on the top edge of the bar, beneath the n, "i" is as well "in", which is the commoner word.
        assertEquals("in", ac.rejoin("i", "", "I", null, cut("i", "", 'n', 0f), doubled = false))
        // A word that is far commoner than the longer one still stands.
        assertNull(ac.rejoin("the", "", "the", null, cut("the", "", 'n', 0f), doubled = false))
    }

    @Test fun twoWordsThatAreTwoWordsStand() {
        // However high on the bar the space was tapped.
        for ((a, b) in listOf("is" to "a", "to" to "go", "in" to "the", "it" to "was", "we" to "can", "a" to "man", "be" to "nice")) {
            for (under in "cvbnm") assertNull("$a $b under $under", ac.rejoin(a, b, a, null, cut(a, b, under, 0.03f)))
        }
        // No taps for the scraps, or none for the bar: nothing to go by, nothing done.
        assertNull(ac.rejoin("bei", "g", "bei", null, Taps(FloatArray(5) { Float.NaN }, FloatArray(5) { Float.NaN }, centres, W, space)))
    }

    @Test fun twoWordsRunTogetherAreParted() {
        assertEquals("in the", ac.correct("inthe", null, taps("inthe")))
        assertEquals("see you", ac.correct("seeyou", null, taps("seeyou")))
        assertEquals("good morning", ac.correct("goodmorning", null, taps("goodmorning")))
        // The space bar missed for the b above it.
        assertEquals("this is", ac.correct("thisbis", null, taps("thisbis", 4 to (0f to 0.45f))))
        // With no taps it never parted words, and still does not.
        assertNull(ac.correct("seeyou"))
        // On a keyboard whose rows are a key's width apart (a tablet, a foldable's inner screen) the bottom row sits
        // close over the space bar: a letter tapped squarely there is still that letter, not the bar missed
        // ("inthe" is "in the", not "I the" with the n for a space).
        val wide = buildMap { listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (s, x0) -> s.forEachIndexed { i, c -> put(c, ((x0 + i + 0.5f) * W) to ((row + 0.5f) * W)) } } }
        fun onWide(word: String) = Taps(FloatArray(word.length) { wide.getValue(word[it]).first }, FloatArray(word.length) { wide.getValue(word[it]).second }, wide, W, floatArrayOf(2.5f * W, 3 * W, 8.5f * W, 4 * W))
        assertEquals("in the", ac.correct("inthe", null, onWide("inthe")))
        assertEquals("see you", ac.correct("seeyou", null, onWide("seeyou")))
        // A long word is not cut in two.
        assertNull(ac.correct("something", null, taps("something")))
        assertNull(ac.correct("understand", null, taps("understand")))
    }

    @Test fun lettersThatLookLikeNoWordArePartedTheLongerTheyAre() {
        // "from strangers" with the space missed, the o tapped on the i's side of their line, the r on the e's and
        // the s on the z under it: thirteen letters nobody means. Two words as rare together as these are worth
        // less than JOIN_BAR, and are taken all the same, the letters being no word (Autocorrect.JOIN_FALL).
        assertEquals("from strangers", ac.correct("frimsteangerz", null, taps("frimsteangerz", 2 to (0.45f to 0f), 6 to (0.45f to 0f), 12 to (0f to -0.45f))))
        // "had stitches", its space landed on the v over the bar and four letters on the keys beside their own.
        assertEquals("had stitches", ac.correct("gadvzritchds", null, taps("gadvzritchds", 0 to (0.45f to 0f), 3 to (0f to 0.45f), 4 to (0f to -0.45f), 5 to (0.45f to 0f), 10 to (-0.3f to -0.4f))))
        // The same letters with every tap in the middle of its key are what was typed: nothing near enough to part them into.
        assertNull(ac.correct("gadvzritchds", null, taps("gadvzritchds")))
        // "person treating", the space on the v and an r tapped as t in each: both halves still look like words,
        // and the only letters that look like none are where they meet. Neither half is a word as typed, so
        // they are no two words set side by side, and the bar falls for them too.
        assertEquals("person treating", ac.correct("petsonvtteating", null, taps("petsonvtteating", 2 to (-0.45f to 0f), 6 to (0f to 0.45f), 8 to (-0.45f to 0f))))
    }

    @Test fun twoWordsSetSideBySideMayBeOneWordNoListHas() {
        // Letters that look like no word only where two listed words meet ("rkt" in worktree, "owp" in windowpane)
        // are a name or a compound no list has, however long: the bar for parting them does not fall.
        for (word in listOf("worktree", "infamousvague", "ghostkeyboard", "mergeconflict", "midjourney", "windowpane", "tablecloth", "fastlane", "churchgoers")) {
            if (ac.isWord(word)) continue
            assertNull("$word, every tap in the middle of its key", ac.correct(word, null, taps(word)))
        }
    }

    @Test fun theRowOffersTheWordTheTapsAreTheStartOf() {
        val words = ac.suggest("tomm", null, taps("tomm")).words.map { it.lowercase() }
        assertTrue("$words", "tommy" in words || "tomorrow" in words)
        // A slip in what is typed so far still finds the word: "tomor" with the second o tapped as i.
        val slipped = ac.suggest("tomir", null, taps("tomir", 3 to (0.4f to 0f))).words.map { it.lowercase() }
        assertTrue("$slipped", "tomorrow" in slipped)
    }

    // --- names ------------------------------------------------------------------------------------------------------

    @Test fun aNameGetsItsCapitalInTheMiddleOfASentence() {
        // Left as typed: a person, a day, a month, a country, a city, a language.
        for ((typed, name) in listOf("tom" to "Tom", "mary" to "Mary", "monday" to "Monday", "january" to "January", "algeria" to "Algeria", "london" to "London", "french" to "French")) {
            assertEquals(name, ac.correct(typed, null, taps(typed), names = true))
            assertEquals("$typed, with no taps to go by", name, ac.correct(typed, names = true))
        }
        // Mended, it has the name's capital too.
        assertEquals("Monday", ac.correct("mondya", null, taps("mondya"), names = true))
        assertEquals("Monday", ac.correct("mondya", names = true))
        // A name's possessive is the name's.
        assertEquals("Tom's", ac.correct("tom's", names = true))
        // Typed with its capital (a sentence's first word, or the shift key) there is nothing to do; all capitals stay.
        assertNull(ac.correct("Tom", null, taps("Tom"), names = true))
        assertNull(ac.correct("TOM", names = true))
        // Every other word is as it was.
        for (word in listOf("hello", "there", "table", "Hello")) assertNull(word, ac.correct(word, null, taps(word), names = true))
        assertEquals("the", ac.correct("teh", null, taps("teh"), names = true))
        assertEquals("I'm", ac.correct("im", null, taps("im"), names = true))
        assertEquals("in the", ac.correct("inthe", null, taps("inthe"), names = true))
    }

    @Test fun aWordThatIsOnlySometimesANameIsLeftInLowerCase() {
        // Each is written without a capital more than once in twenty times where it stands in the middle of a
        // sentence (tool/capitals.py): a capital wrongly given is a right word spoiled.
        for (word in listOf("will", "mark", "rose", "may", "bill", "march", "turkey", "polish", "frank", "grace", "hope", "jack", "god")) {
            assertNull(word, ac.correct(word, null, taps(word), names = true))
        }
        // Two letters are as often a slip or a shorthand as a title, and are left too.
        for (word in listOf("mr", "dr", "st")) assertNull(word, ac.correct(word, null, taps(word), names = true))
    }

    @Test fun noCapitalUnlessTheFieldWantsThem() {
        // Not asked for (a field that capitalises nothing, a terminal, capitals turned off), a name is as it always was.
        for (word in listOf("tom", "monday", "algeria")) {
            assertNull(word, ac.correct(word, null, taps(word)))
            assertNull(word, ac.correct(word))
            assertNull(word, ac.suggest(word, null, taps(word)).fix)
        }
        assertEquals("monday", ac.correct("mondya", null, taps("mondya")))
    }

    @Test fun aNameThePersonKeepsInLowerCaseStaysThere() {
        val mine = english()
        assertEquals("Monday", mine.correct("monday", names = true))
        // Undoing the capital keeps the word, as undoing any fix does: it is not given again, nor as a mend's.
        mine.setLearned(setOf("monday"))
        assertNull(mine.correct("monday", null, taps("monday"), names = true))
        assertNull(mine.suggest("monday", null, taps("monday"), names = true).fix)
        assertEquals("monday", mine.correct("mondya", null, taps("mondya"), names = true))
        assertEquals("Tuesday", mine.correct("tuesday", names = true))
        // A name kept in lower case keeps its plural and its possessive so (the list has Toms as a word of its own).
        val tom = english()
        assertEquals("Toms", tom.correct("toms", names = true))
        tom.setLearned(setOf("tom"))
        for (word in listOf("tom", "toms", "tom's")) assertEquals(word, tom.correct(word), tom.correct(word, names = true))
        assertEquals("Mary", tom.correct("mary", names = true))
    }

    @Test fun aNameNoListHasIsLeftAsTyped() {
        // The names are words of the list: one it lacks (the typing bench's unknown words) is never given a capital.
        for (word in listOf("okafor", "siobhan", "thandiwe", "ghostkbd")) {
            if (ac.isWord(word)) continue
            assertNull(word, ac.correct(word, null, taps(word), names = true))
        }
    }

    @Test fun theRowShowsANameAsTheSpaceWillLeaveIt() {
        val row = ac.suggest("tom", null, taps("tom"), names = true)
        assertEquals("Tom", row.fix)
        // The name first, then the word as typed, a tap away.
        assertEquals(listOf("Tom", "tom"), row.words.take(2))
        // A word being typed is offered with its capital.
        assertTrue(ac.suggest("mond", null, taps("mond"), names = true).words.contains("Monday"))
        assertTrue(ac.suggest("mond", null, taps("mond")).words.contains("monday"))
        // Typed with its capital there is no fix to show.
        val shifted = ac.suggest("Tom", null, taps("Tom"), names = true)
        assertNull(shifted.fix)
        assertEquals("Tom", shifted.words.first())
    }

    @Test fun aSpellingAnotherLoadedLanguageWritesAsAWordOfItsOwnIsNoName() {
        // "roman" is Roman in English and a novel in French: with both loaded it is left. Monday is nobody else's word.
        assertEquals("Roman", ac.correct("roman", names = true))
        val both = loaded(listOf("en", "fr"))
        assertNull(both.correct("roman", names = true))
        assertEquals("Monday", both.correct("monday", names = true))
        // Each of these is a name in English alone, and beside the language named a word that language writes in
        // lower case, by its own sentences (dict/en_capitals_beside.tsv): asked for names it is as it is unasked.
        val own = listOf(
            "de" to listOf("mir", "seine", "los", "weiss"), "es" to listOf("los", "las", "catalan", "china"), "fr" to listOf("roman", "les", "marie"),
            "nl" to listOf("ben", "dan", "april", "september"), "cs" to listOf("tom", "sam", "jim"), "it" to listOf("bella", "verde", "costa"), "sl" to listOf("sami", "mali"),
        )
        for ((other, words) in own) {
            val two = loaded(listOf("en", other))
            for (word in words) {
                assertEquals(word, word.replaceFirstChar { it.uppercase() }, ac.correct(word, names = true))
                assertTrue("$other: $word", word in beside.getValue(other))
                assertEquals("$other: $word", two.correct(word), two.correct(word, names = true))
                assertEquals("$other: $word", two.suggest(word).fix, two.suggest(word, names = true).fix)
                assertEquals("$other: $word", word, two.named(word))
            }
        }
    }

    @Test fun besideAnotherLanguageANameItWritesAsANameIsStillOne() {
        // Until 2026-11-13 every spelling the other list has was taken out, and the other lists have Tom, Mary and
        // London: their writers tell of them as English's do. Beside German 26 of the 146 names the measured run
        // gives their capital were left. These are names in each of them, by how its own sentences write them.
        for (other in listOf("de", "es", "fr", "it", "pt", "nl", "pl", "tr", "sv")) {
            val two = loaded(listOf("en", other))
            for ((typed, name) in listOf("tom" to "Tom", "mary" to "Mary", "john" to "John", "london" to "London", "boston" to "Boston", "monday" to "Monday", "english" to "English")) {
                assertEquals("$other: $typed", name, two.correct(typed, names = true))
                assertEquals("$other: $typed", name, two.correct(typed, null, taps(typed), names = true))
            }
            assertEquals("$other", "Tom", two.suggest("tom", names = true).fix)
        }
        // German's own nouns are German's affair, but Berlin is Berlin in both.
        assertEquals("Berlin", loaded(listOf("en", "de")).correct("berlin", names = true))
        // The order the languages were turned on in changes nothing.
        val turned = loaded(listOf("de", "en"), layout = "qwerty")
        assertEquals("Tom", turned.correct("tom", names = true))
        assertEquals(turned.correct("mir"), turned.correct("mir", names = true))
        // With three, what either of the others writes as a word of its own is out: French's roman, German's mir.
        val three = loaded(listOf("en", "de", "fr"))
        assertEquals("Tom", three.correct("tom", names = true))
        for (word in listOf("roman", "mir")) assertEquals(word, three.correct(word), three.correct(word, names = true))
    }

    @Test fun everyLanguageEnglishCanBeLoadedBesideHasItsLineAndItDecides() {
        // Every Latin-script language with a word list is judged in dict/en_capitals_beside.tsv; each of its words
        // is a name of the list; and with that language loaded beside English, on English's board, a name it takes
        // out never gets a capital, and the names it leaves get theirs (all but the few the decoder reads as
        // another word, as in English alone), with no letter changed by the asking.
        var judged = 0
        for (language in Languages.ALL) {
            if (language.script != Script.LATIN || language.dict == null || language.code == "en") continue
            val out = beside[language.code]
            assertNotNull("${language.code} has no line in dict/en_capitals_beside.tsv: make it again (tool/capitals.py)", out)
            judged++
            assertTrue("${language.code}: ${out!! - names}", names.containsAll(out))
            val two = loaded(listOf("en", language.code))
            var given = 0
            for (name in names) {
                val plain = two.correct(name) ?: name
                val asked = two.correct(name, names = true) ?: name
                assertEquals("${language.code}: $name", plain.lowercase(), asked.lowercase())
                // A name taken out that is read as another word (beside Portuguese "chiba", a name since the capitals were
                // made over English's wider list, is "china") is that word, and has that word's capital or none.
                if (name in out) { if (plain.lowercase() == name) assertEquals("${language.code}: $name", plain, asked) } else if (asked != plain) given++
            }
            assertTrue("${language.code}: $given of ${names.size - out.size}", given >= (names.size - out.size) * 9 / 10)
        }
        assertEquals(beside.keys.toString(), judged, beside.size)
    }

    @Test fun aLanguageTheFileHasNoLineForLosesEverySpellingItsListHas() {
        // A language added to the keyboard since the file was made, or a file that could not be read: the rule
        // there was before the file. Every spelling the other list has is out, and now the plain spelling of every
        // accented word it has too (Spanish "catalán", typed catalan, was given English's capital).
        for (file in listOf<String?>("# made before Spanish was added\nde\tmir seine\n", "", null)) {
            val two = loaded(listOf("en", "es"), swapped = mapOf("dict/en_capitals_beside.tsv" to file))
            for (word in listOf("tom", "mary", "london", "china", "catalan", "los")) assertEquals("$word, $file", two.correct(word), two.correct(word, names = true))
            // A name Spanish's list does not have is English's still.
            assertEquals("Wednesday", two.correct("wednesday", names = true))
        }
        // A language with a line loses that line's words and no others, whatever its list has.
        val german = loaded(listOf("en", "de"), swapped = mapOf("dict/en_capitals_beside.tsv" to "# two words\nde\tmir seine\nfr\ttom\n"))
        for (word in listOf("mir", "seine")) assertEquals(word, german.correct(word), german.correct(word, names = true))
        assertEquals("Tom", german.correct("tom", names = true))
        assertEquals("Los", german.correct("los", names = true))
        // And with English alone the file is not looked at.
        assertEquals("Tom", loaded(listOf("en"), swapped = mapOf("dict/en_capitals_beside.tsv" to "en\ttom\n")).correct("tom", names = true))
    }

    @Test fun aLineOfTheNamesFileMayCarryMoreThanTheName() {
        // Should the list ever say more of a name than the name (a count, a mark), what follows a tab is left aside.
        val one = loaded(listOf("en"), swapped = mapOf("dict/en_capitals.txt" to "# names\nTom\t9142\n  Mary  \n\nMonday\t12\tx\n"))
        for ((typed, name) in listOf("tom" to "Tom", "mary" to "Mary", "monday" to "Monday", "tom's" to "Tom's")) assertEquals(name, one.correct(typed, names = true))
        assertNull(one.correct("london", names = true))
        // No file, or one with no name in it: no names, and everything else as it was.
        for (file in listOf<String?>(null, "", "# nothing\n")) {
            val none = loaded(listOf("en"), swapped = mapOf("dict/en_capitals.txt" to file))
            for (word in listOf("tom", "monday", "hello", "teh", "im")) assertEquals("$word, $file", ac.correct(word), none.correct(word, names = true))
        }
    }

    @Test fun onTheTurkishBoardANameThatBeginsWithIIsLeft() {
        // English beside Turkish, typed on Turkish's own board: I is the capital of ı there and i's is İ. Ivan would
        // be Ivan in an English sentence and İvan in a Turkish one, and İvan typed on purpose was changed to Ivan.
        // As the lone i is left on that board (tool/lang_check), so is a name that begins with it, typed either way.
        val turkish = loaded(listOf("en", "tr"), layout = "turkish")
        for (typed in listOf("ivan", "İvan", "ireland", "İreland", "italy", "İtaly", "iraq", "İraq", "isaac", "ivan's")) {
            assertEquals(typed, turkish.correct(typed), turkish.correct(typed, names = true))
            assertEquals(typed, turkish.suggest(typed).fix, turkish.suggest(typed, names = true).fix)
            assertEquals(typed, turkish.suggest(typed).words, turkish.suggest(typed, names = true).words)
            assertEquals(typed, typed, turkish.named(typed))
        }
        for (typed in listOf("İvan", "İreland", "İtaly")) assertNull(typed, turkish.correct(typed, names = true))
        // A name that begins with another letter has its capital there as anywhere.
        assertEquals("Monday", turkish.correct("monday", names = true))
        assertEquals("London", turkish.correct("london", names = true))
        // On QWERTY, which Turkish shares with English and where shift gives English's I, Ireland has its capital.
        // (Not Ivan: Turkish writes "ivan", a hall, in lower case, so beside Turkish he is out on either board.)
        val shared = loaded(listOf("en", "tr"))
        assertEquals("Ireland", shared.correct("ireland", names = true))
        assertEquals("Italy", shared.correct("italy", names = true))
        assertNull(shared.correct("ivan", names = true))
    }

    @Test fun theRowAndTheSpaceAgreeOnANameWithTheWordsBeforeToGoBy() {
        // With next-word prediction on (a Scorer for the place) as without: the space leaves "Tom", and the row has
        // "Tom" as its pick and "tom" next to it.
        val before = scorer(ac, "i met tom")
        assertNotNull(before)
        assertEquals("Tom", ac.correct("tom", before, taps("tom"), names = true))
        val row = ac.suggest("tom", before, taps("tom"), names = true)
        assertEquals("Tom", row.fix)
        assertEquals(listOf("Tom", "tom"), row.words.take(2))
        assertNull(ac.correct("tom", before, taps("tom")))
        // A name mended by its taps and the words before has its capital too, and a word that is no name is as it was.
        assertEquals("Monday", ac.correct("mondya", scorer(ac, "see you on mondya"), taps("mondya"), names = true))
        val plain = scorer(ac, "i met them")
        assertEquals(ac.correct("them", plain, taps("them")), ac.correct("them", plain, taps("them"), names = true))
    }

    @Test fun everyNameOfTheListIsAWordAndAskingForNamesChangesNoLetter() {
        val names = File(assets, "dict/en_capitals.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && it[0] != '#' }
        assertTrue("${names.size} names", names.size > 1000)
        assertEquals("a name is listed once", names.size, names.map { it.lowercase() }.toSet().size)
        var given = 0
        for (name in names) {
            val lower = name.lowercase()
            // Three letters or more, written with one capital, and a word the keyboard knows: autocorrect mends toward
            // nothing else, so a name outside the lists could only ever be given to a word typed without a slip.
            assertTrue(name, name.length >= 3 && name[0].isUpperCase() && name.drop(1) == lower.drop(1) && name.all { it.isLetter() })
            assertTrue(name, ac.isWord(lower))
            // Typed cleanly in lower case: whatever the decoder makes of its letters without names (a rare name may
            // be read as a commoner word, as any rare word may), it makes with them. Only the capital is new.
            val plain = ac.correct(lower, null, taps(lower)) ?: lower
            val asked = ac.correct(lower, null, taps(lower), names = true) ?: lower
            assertEquals(name, plain.lowercase(), asked.lowercase())
            if (asked == name) given++ else assertTrue("$name: $asked", asked.lowercase() != lower)
        }
        // All but the few rare names the decoder reads as another word (monet as money, bern as been) get their capital.
        assertTrue("$given of ${names.size}", given >= names.size * 9 / 10)
    }

    @Test fun aNameInsideTwoWordsRunTogetherGetsItsCapital() {
        // The fix is two words, and each is looked at: the name among them has its capital, the other is as it was.
        assertEquals("with Mary", ac.correct("withmary", null, taps("withmary"), names = true))
        assertEquals("in London", ac.correct("inlondon", null, taps("inlondon"), names = true))
        assertEquals("with mary", ac.correct("withmary", null, taps("withmary")))
        // The row shows what the space will leave.
        assertEquals("with Mary", ac.suggest("withmary", null, taps("withmary"), names = true).fix)
    }

    @Test fun aWordPutTogetherAgainHasItsNamesCapital() {
        // "london" with its first n landed on the bar: "lo doj" in the field. Put together it is a name like any other,
        // and the service gives it its capital by [Autocorrect.named] where the field wants them.
        val whole = ac.rejoin("lo", "doj", "lo", null, cut("lo", "doj", 'n', 0.07f))
        assertEquals("london", whole)
        assertEquals("London", ac.named(whole!!))
        // A capital it has already (the scrap opened a sentence) stays, and a word that is no name is as it was.
        assertEquals("London", ac.named(ac.rejoin("Lo", "doj", "Lo", null, cut("Lo", "doj", 'n', 0.07f))!!))
        assertEquals("being", ac.named("being"))
        assertEquals("Being", ac.named("Being"))
        // Each word of two, as a fix may be.
        assertEquals("with Mary", ac.named("with mary"))
        // One the person keeps in lower case is left there.
        val mine = english()
        mine.setLearned(setOf("london"))
        assertEquals("london", mine.named("london"))
    }

    @Test fun capitalsTypedOnPurposeAreNoNamesToMend() {
        // Caps lock, and a capital after the first letter: the space leaves both as typed, and the row offers no fix.
        for (typed in listOf("TOM", "tOm", "MONDAY", "mOnday")) {
            assertNull(typed, ac.correct(typed, null, taps(typed), names = true))
            assertNull(typed, ac.suggest(typed, null, taps(typed), names = true).fix)
        }
    }

    @Test fun slangThatIsAlsoANameIsLeftWhileSlangIsOn() {
        // "stan" is on both lists: with texting slang on it is the person's word, as every slang word is.
        assertEquals("Stan", ac.correct("stan", null, taps("stan"), names = true))
        val texting = english()
        texting.setSlang(true)
        Thread.sleep(400) // the slang list is read on its own thread
        assertNull(texting.correct("stan", null, taps("stan"), names = true))
        assertNull(texting.suggest("stan", null, taps("stan"), names = true).fix)
    }

    @Test fun withoutEnglishLoadedAskingForNamesChangesNothing() {
        // The list is English's. German capitalises every noun by rules of its own, and its list has tom, berlin and
        // montag in lower case as English's has them: asked for names or not, every answer is the same.
        val german = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
        val state = LanguageState.of(listOf("de"), emptyMap(), "")
        german.loadNow(state.dictionaries, state.board)
        for (word in listOf("tom", "berlin", "london", "montag", "monday", "maria", "china", "haus", "ich", "i", "hallo", "montga", "berlni", "tom's")) {
            assertEquals(word, german.correct(word, null, taps(word)), german.correct(word, null, taps(word), names = true))
            assertEquals(word, german.correct(word), german.correct(word, names = true))
            val plain = german.suggest(word, null, taps(word))
            val asked = german.suggest(word, null, taps(word), names = true)
            assertEquals(word, plain.words, asked.words)
            assertEquals(word, plain.fix, asked.fix)
            assertEquals(word, word, german.named(word))
        }
        // With both loaded, a word German writes in lower case is left ("mir", to me), and a name German writes as a
        // name is English's still, whether or not German's list has it.
        val both = loaded(listOf("en", "de"))
        assertEquals(both.correct("mir"), both.correct("mir", names = true))
        assertEquals("Berlin", both.correct("berlin", names = true))
        assertEquals("Monday", both.correct("monday", names = true))
    }

    @Test fun askingForNamesTouchesNoWordThatIsNoName() {
        // A word left as typed is looked at again only where it is a name. Not where its capital does not come back
        // from lower case as it was typed (İ, on i's long-press beside Turkish, lowers to two characters on a board
        // that is not Turkish's; ǅ is one letter that lowers to ǆ and is no capital to raise again), nor where it
        // only begins as I'm does: each is exactly what it is unasked, to the character.
        for (typed in listOf("İrem", "İreland", "ǅemal", "i'ma", "i'xyz", "Ωmega", "hello", "Hello", "HELLO", "a", "A", "x", "tmo", "Tmo")) {
            assertEquals(typed, ac.correct(typed), ac.correct(typed, names = true))
            assertEquals(typed, ac.suggest(typed).fix, ac.suggest(typed, names = true).fix)
        }
        for (typed in listOf("hello", "Hello", "i'ma", "tmo", "Tmo", "x")) {
            assertEquals(typed, ac.correct(typed, null, taps(typed)), ac.correct(typed, null, taps(typed), names = true))
            assertEquals(typed, ac.suggest(typed, null, taps(typed)).fix, ac.suggest(typed, null, taps(typed), names = true).fix)
        }
        // Beside another language too, where English's names are fewer but still given.
        val both = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
        val two = LanguageState.of(listOf("en", "tr"), emptyMap(), "")
        both.loadNow(two.dictionaries, two.board)
        for (typed in listOf("İrem", "ǅemal", "i'ma", "merhaba", "Merhaba")) assertEquals(typed, both.correct(typed), both.correct(typed, names = true))
        assertEquals("Monday", both.correct("monday", names = true))
    }

    @Test fun askedForNamesEveryWordOfTheListThatIsNoNameIsAsItWas() {
        // English's whole list, each word typed in lower case with no taps to go by: asked for names, a word comes
        // out as it does unasked unless a name is in it, and then its letters are the same and only that capital new.
        val names = File(assets, "dict/en_capitals.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && it[0] != '#' }.map { it.lowercase() }.toSet()
        fun isName(word: String) = word in names || (word.endsWith("'s") && word.dropLast(2) in names)
        var words = 0
        var capitals = 0
        File(assets, "dict/en.tsv").useLines { lines ->
            lines.forEach { line ->
                val word = line.substringBefore('\t')
                if (word.isEmpty() || word.length > 24) return@forEach
                words++
                val plain = ac.correct(word)
                val asked = ac.correct(word, names = true)
                if (plain == asked) return@forEach
                val stands = asked ?: word
                assertEquals(word, (plain ?: word).lowercase(), stands.lowercase())
                // Every capital it has that it would not have unasked is a name's.
                val before = (plain ?: word).split(' ')
                val after = stands.split(' ')
                assertEquals(word, before.size, after.size)
                for (i in after.indices) if (after[i] != before[i]) assertTrue("$word: $asked", isName(before[i].lowercase()) && after[i] == before[i].replaceFirstChar { it.uppercase() })
                capitals++
            }
        }
        assertTrue("$words words", words > 30_000)
        assertTrue("$capitals of ${names.size} names", capitals >= names.size * 9 / 10)
    }

    @Test fun inEveryLanguageButEnglishAskingForNamesChangesNothing() {
        // Every other language the keyboard has a list for, loaded alone on its own board: its commonest words, in
        // lower case and with a capital, some of English's names and some odd capitals; and for the five that
        // tool/lang_check keeps a record of (German, Spanish, French, Italian, Portuguese), every word and slip of that
        // record. Asked for names or not, the answer and the row are the same, because there is no list of names to
        // go by ([Autocorrect.load]).
        val odd = listOf("tom", "Tom", "TOM", "monday", "london", "tom's", "i", "i'm", "i'ma", "İrem", "ǅemal", "mondya", "tmo")
        val golden = listOf("tool/lang_check/golden", "../../tool/lang_check/golden", "../tool/lang_check/golden").map(::File).firstOrNull { it.isDirectory }
        var tried = 0
        var recorded = 0
        for (language in Languages.ALL) {
            val dict = language.dict ?: continue
            if (language.code == "en") continue
            val other = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
            val state = LanguageState.of(listOf(language.code), emptyMap(), "")
            assertTrue(language.code, "en" !in state.dictionaries)
            other.loadNow(state.dictionaries, state.board)
            val common = File(assets, "dict/$dict.tsv").useLines { lines -> lines.take(150).map { it.substringBefore('\t') }.filter { it.isNotEmpty() }.toList() }
            // What lang_check asks this language alone (its rows of kind c: a word, a slip of one, the start of one).
            val record = golden?.let { File(it, "${language.code}.tsv") }?.takeIf { it.exists() }?.readLines().orEmpty()
                .map { it.split('\t') }.filter { it.size >= 2 && it[0] == "c" && it[1].isNotEmpty() }.map { it[1] }
            recorded += record.size
            for (word in common + common.map { w -> w.replaceFirstChar { it.titlecase(state.board.locale) } } + odd + record) {
                assertEquals("${language.code}: $word", other.correct(word), other.correct(word, names = true))
                val plain = other.suggest(word)
                val asked = other.suggest(word, names = true)
                assertEquals("${language.code}: $word", plain.fix, asked.fix)
                assertEquals("${language.code}: $word", plain.words, asked.words)
                assertEquals("${language.code}: $word", word, other.named(word))
                tried++
            }
        }
        assertTrue("$tried", tried > 30 * 300)
        // Where the tools are there to read (a copy of the app's sources alone has no record): all five records were.
        if (golden != null) assertTrue("$recorded", recorded > 5 * 1500)
    }

    // --- the person's own mistakes ----------------------------------------------------------------------------------

    @Test fun aRememberedMistakeIsMendedTheWayItWasPutRight() {
        val mine = english()
        assertNull(mine.correct("kria", null, taps("kria"))?.takeIf { it.equals("kira", true) })
        mine.setMistakes(mapOf("kria" to "Kira", "thankyiu" to "thank you"))
        assertEquals("Kira", mine.correct("kria"))
        assertEquals("Kira", mine.correct("kria", null, taps("kria")))
        assertEquals("Kira", mine.correct("Kria"))
        assertEquals("thank you", mine.correct("thankyiu"))
        assertEquals("Kira", mine.suggest("kria").fix)
        // A word the person kept wins over a mistake remembered of it; one they merely type often does not.
        mine.setOwn(setOf("kria"))
        assertEquals("Kira", mine.correct("kria"))
        mine.setLearned(setOf("kria"))
        assertNull(mine.correct("kria"))
    }

    @Test fun aCommonWordIsNeverMendedByMemoryAlone() {
        val mine = english()
        mine.setMistakes(mapOf("form" to "from", "than" to "then"))
        assertNull("with no taps to go by", mine.correct("form"))
        assertNull(mine.correct("than"))
        // Tapped cleanly, letter for letter in the order of the other word: the memory tips it.
        assertEquals("from", mine.correct("form", null, taps("form", 1 to (-0.3f to 0f), 2 to (0.3f to 0f))))
        // With nothing remembered the same taps leave it.
        assertNull(ac.correct("form", null, taps("form", 1 to (-0.3f to 0f), 2 to (0.3f to 0f))))
    }

    @Test fun aSwipeGoesByTheReadingThePersonKeepsChoosing() {
        val mine = english()
        val found = listOf("form", "from", "for", "firm")
        assertEquals(found, mine.byChoice(found))
        mine.setChosen(mapOf("form" to mapOf("from" to 2f)))
        assertEquals(listOf("from", "form", "for", "firm"), mine.byChoice(found))
        // Only a reading the stroke gave is ever put first.
        assertEquals(listOf("form", "for"), mine.byChoice(listOf("form", "for")))
        // Chosen as often as it stood: the stroke's own reading stays.
        mine.setChosen(mapOf("form" to mapOf("from" to 2f, "form" to 1.5f)))
        assertEquals(found, mine.byChoice(found))
        assertEquals(emptyList<String>(), mine.byChoice(emptyList()))
    }

    // --- Mockingbird's samples as word pairs ------------------------------------------------------------------------

    @Test fun samplesAreCountedAsTheyWouldBeTyped() {
        val heard = PersonalPairs.of(listOf("On my way home 🏠 see you soon", "on my way now. See you there!", "omw lol"))
        assertEquals(2f, heard.row("on")!!.first { it.word == "my" }.count, 0.001f)
        assertEquals(2f, heard.row("on my")!!.first { it.word == "way" }.count, 0.001f)
        // After the emoji and after the full stop a sentence starts.
        assertEquals(2f, heard.row(NextWordText.START)!!.first { it.word == "see" }.count, 0.001f)
        assertEquals(2f, heard.row(NextWordText.START)!!.first { it.word == "on" }.count, 0.001f)
        assertNull(heard.row("home"))
        assertEquals(2f, heard.unigram("way"), 0.001f)
    }

    @Test fun whatWasSentJoinsWhatWasTyped() {
        val typed = listOf(PersonalPairs.Entry("my", 3f), PersonalPairs.Entry("the", 1f))
        val chats = PersonalPairs.of(listOf("on my way", "on my own", "on point"))
        val notes = PersonalPairs.of(listOf("on Monday", "on Monday", "on the list"))
        val both = PersonalPairs.blended(typed, chats.row("on"), 0.5f, notes.row("on"), 0.25f).associate { it.word to it.count }
        assertEquals(4f, both.getValue("my"), 0.001f)
        assertEquals(0.5f, both.getValue("point"), 0.001f)
        assertEquals(0.5f, both.getValue("monday"), 0.001f)
        assertEquals(1.25f, both.getValue("the"), 0.001f)
        // With nothing heard, what was typed is handed back as it is.
        assertTrue(PersonalPairs.blended(typed, null, 0.5f, null, 0.25f) === typed)
        // And what was typed is never changed by the blending.
        assertEquals(3f, typed[0].count, 0.001f)
        assertNotNull(chats.row("on"))
    }
}
