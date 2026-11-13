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
        assertEquals(maxOf(TapDecoder.OMIT, TapDecoder.LISTED_EDIT), few.read("th", taps("th"), listed = true).getValue("the"), 0.01f)
        assertEquals(maxOf(TapDecoder.SWAP, TapDecoder.LISTED_EDIT), few.read("ot", taps("ot"), listed = true).getValue("to"), 0.01f)
        assertEquals(maxOf(TapDecoder.INSERT_DOUBLED, TapDecoder.LISTED_EDIT), few.read("soo", taps("soo"), listed = true).getValue("so"), 0.01f)
        assertEquals(maxOf(TapDecoder.INSERT_DOUBLED, TapDecoder.LISTED_EDIT), few.read("inn", taps("inn"), listed = true).getValue("in"), 0.01f)
        // Letters no list has are read as they always were: the swap for SWAP, the doubled letter for INSERT_DOUBLED.
        assertEquals(TapDecoder.SWAP, few.read("ot", taps("ot")).getValue("to"), 0.01f)
        assertEquals(TapDecoder.INSERT_DOUBLED, few.read("soo", taps("soo")).getValue("so"), 0.01f)
        // A tap that doubles nothing is never taken out of a common word: "ink" is not "in" with a k too many.
        assertNull(few.read("ink", taps("ink"), listed = true)["in"])
        assertEquals(TapDecoder.INSERT, few.read("ink", taps("ink")).getValue("in"), 0.01f)
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

    @Test fun aTapTooManyCostsByWhereItLanded() {
        val the = Few("the")
        // "thre" for "the": the r is one tap too many. On the line beside e it is all but e tapped twice; in the
        // middle of its own key it is a tap of its own, and costs what any stray tap costs.
        assertEquals(TapDecoder.INSERT_DOUBLED + 0.15f, the.read("thre", taps("thre", 2 to (-0.45f to 0f))).getValue("the"), 0.01f)
        assertEquals(TapDecoder.INSERT, the.read("thre", taps("thre")).getValue("the"), 0.01f)
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
        // reached only as an o left out and an i too many.
        assertEquals(TapDecoder.OMIT + TapDecoder.INSERT, few.read("wrik", wrik, reach = false).getValue("work"), 0.01f)
        assertEquals(TapDecoder.OMIT + TapDecoder.INSERT, few.read("wrik", taps("wrik")).getValue("work"), 0.01f)
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

    @Test fun theRowOffersTheWordTheTapsAreTheStartOf() {
        val words = ac.suggest("tomm", null, taps("tomm")).words.map { it.lowercase() }
        assertTrue("$words", "tommy" in words || "tomorrow" in words)
        // A slip in what is typed so far still finds the word: "tomor" with the second o tapped as i.
        val slipped = ac.suggest("tomir", null, taps("tomir", 3 to (0.4f to 0f))).words.map { it.lowercase() }
        assertTrue("$slipped", "tomorrow" in slipped)
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
