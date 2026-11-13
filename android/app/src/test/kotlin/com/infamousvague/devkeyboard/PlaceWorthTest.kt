package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.log10

/**
 * What a word is worth at its place in the sentence ([Scorer.Here]), and autocorrect weighing the readings of a
 * word's taps by it ([Autocorrect.correct] with a [Scorer]): against the real English list and starter table, and
 * against small tables made here where a row's count is the matter.
 */
class PlaceWorthTest {
    companion object {
        private val assets: File by lazy { listOf("android/app/src/main/assets", "src/main/assets", "../app/src/main/assets").map(::File).first { it.exists() } }

        val zipf: Map<String, Float> by lazy {
            val out = HashMap<String, Float>(60_000)
            File(assets, "dict/en.tsv").forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) line.substring(tab + 1).toFloatOrNull()?.let { z -> if (z > (out[line.substring(0, tab)] ?: 0f)) out[line.substring(0, tab)] = z }
            }
            out
        }

        val english: NgramTable by lazy { File(assets, "ngram/en.tsv").useLines { NgramTable.parse(it) }!! }

        private val lookup = object : Scorer.Lookup {
            override fun isWord(lower: String) = zipf.containsKey(lower)
            override fun form(lower: String) = lower
            override fun count(lower: String) = 0f
            override fun starting(prefix: String) = emptyList<Pair<String, Float>>()
            override fun fills() = emptyList<String>()
        }

        /** The scorer for the place after [before]'s words ("^" for a sentence's start), by [table] and what the person typed after the last of them ([mine]). */
        fun scorer(vararg before: String, table: NgramTable = english, mine: List<PersonalPairs.Entry> = emptyList()): Scorer {
            val place = NextWordText.Place(before.getOrNull(before.size - 2), before.last(), "", emptyList())
            return Scorer(place, listOf(table), floatArrayOf(1f), mine, emptyList(), lookup)
        }

        /** The scorer for the place after [before]'s words by several [tables], weighed by [weights]. */
        fun scorerOf(tables: List<NgramTable>, weights: FloatArray, vararg before: String): Scorer {
            val place = NextWordText.Place(before.getOrNull(before.size - 2), before.last(), "", emptyList())
            return Scorer(place, tables, weights, emptyList(), emptyList(), lookup)
        }

        fun here(vararg before: String, table: NgramTable = english, mine: List<PersonalPairs.Entry> = emptyList()): Scorer.Here? =
            scorer(*before, table = table, mine = mine).here { zipf[it] }

        fun z(word: String): Float = zipf.getValue(word)

        /** A table of [rows], each "context<tab>entries". */
        fun table(vararg rows: String): NgramTable = NgramTable.parse(sequenceOf("#ghost-ngram\t1\ten\ta test's own") + rows.asSequence())!!
    }

    // --- the place ---------------------------------------------------------------------------------------------------

    @Test fun aFollowerARowListsIsWorthItsShareThere() {
        // After "thank" nearly every word is "you": it is worth nearly nine there, far over its frequency.
        val h = here("thank")!!
        assertTrue(h.lists("you"))
        val you = h.worth("you", z("you"))
        assertTrue("you after thank is worth $you", you > 8.6f && you <= 9f)
        assertTrue(you > z("you") + 1f)
    }

    @Test fun aCommonWordARowDoesNotListIsHeldDown() {
        // "of" is one word in forty anywhere. After "thank" no row lists it: it followed less often than the least
        // follower listed, whatever its frequency.
        val h = here("thank")!!
        assertFalse(h.lists("of"))
        assertFalse(h.plain("of", z("of")))
        assertTrue("of after thank is worth ${h.worth("of", z("of"))}", h.worth("of", z("of")) < z("of") - 1f)
    }

    @Test fun aWordNoRowBearsOnIsWorthItsFrequencyLessTheRoomThePlaceHas() {
        // After "the" comes any noun: a rare one no row lists has its share of what the rows leave over.
        val h = here("the")!!
        assertFalse(h.lists("walrus"))
        assertTrue(h.plain("walrus", z("walrus")))
        assertEquals(z("walrus") + h.room, h.worth("walrus", z("walrus")), 0.001f)
        // After "i've" the rows leave little over: what follows is nearly always one of the words they list.
        assertTrue(here("i've")!!.room < h.room)
        assertTrue(here("i've")!!.room < 0f)
    }

    @Test fun afterWordsNoTableHasARowForThereIsNothingToGoBy() {
        assertNull(here("walrusish"))
        // "tom" is never a context: Tatoeba's stock names are scaled out of the tables.
        assertNull(here("tom"))
    }

    @Test fun theTwoWordsBeforeSayMoreThanTheOne() {
        // "you" after "are" is likely; after "how are" it is all but sure.
        val one = here("are")!!.worth("you", z("you"))
        val two = here("how", "are")!!.worth("you", z("you"))
        assertTrue("$one after are, $two after how are", two > one)
    }

    @Test fun aRowSeenAHandfulOfTimesIsBelievedLessThanOneSeenThousandsOfTimes() {
        // The same row, half of what followed "blue" being "moon": from six sightings, and from six thousand.
        fun worth(seen: Int) = here("blue", table = table("blue\t=$seen moon:5000 sky:3000"))!!.worth("moon", z("moon"))
        val thin = worth(6)
        val thick = worth(6000)
        assertEquals(9f + log10(0.5f), thick, 0.01f)
        assertTrue("$thin from six sightings, $thick from six thousand", thin < thick - 0.3f && thin > z("moon"))
    }

    @Test fun englishRowsSayHowOftenTheirWordWasSeen() {
        assertTrue(english.counted)
        assertTrue(english.seen("the") > 100_000)
        assertTrue(english.seen("^") > 1_000_000)
        assertTrue(english.seen("how are") in 1 until english.seen("are"))
        assertEquals(0, english.seen("walrusish"))
    }

    @Test fun aTableWhoseRowsDoNotSayHowOftenTheyWereSeenIsNotGoneBy() {
        // As tables were made before 2026-11-12, and as every language's but English's still is: no "=n" on a
        // one-word row. Its last followers are names scaled down, and nothing in it says the word was seen 1,429
        // times and not 30,000: the place is read as it was before, by frequency and a lift.
        val old = table("taught\tme:2348 you:728 him:347 all:28 how:28 for:21 kids:21 Tom:8 Layla:1", "she taught\t=50 me:3200 him:1000")
        assertEquals(0, old.seen("taught"))
        assertFalse(old.counted)
        assertNull(here("taught", table = old))
        assertNull(here("she", "taught", table = old))
        // Nor is its two-word row gone by where it has no one-word row for the word before: it is the table that
        // was not made for this, whatever one of its rows carries.
        assertNull(here("she", "taught", table = table("teach\tme:2348 you:728", "she taught\t=50 me:3200 him:1000")))
        assertTrue(scorer("taught", table = old).boost("me", z("me")) > 1f)
        val counted = table("taught\t=1429 me:2348 you:728 him:347 all:28 how:28 for:21 kids:21 Tom:8 Layla:1")
        assertEquals(1429, counted.seen("taught"))
        assertTrue(counted.counted)
        // "it" is not listed: it followed at most twice in those 1,429, and is held to a share of three in them,
        // not of the one in ten thousand that "Layla:1" stands for.
        val it = here("taught", table = counted)!!.share("it", z("it"))
        assertTrue("it after taught: $it", it > 0.5f * Scorer.UNLISTED * 3f / 1429f && it < 3f / 1429f)
    }

    @Test fun whatThePersonTypesAtAPlaceLiftsAWordAndNeverLowersOne() {
        val plain = here("the")!!
        // Typed after "the" eight times: a word no row lists there is now likely there, but the counts make it
        // worth no more than Scorer.MINE_LIFT over what the tables make of it.
        val mine = here("the", mine = listOf(PersonalPairs.Entry("walrus", 8f)))!!
        assertTrue(mine.lists("walrus"))
        assertEquals(plain.worth("walrus", z("walrus")) + Scorer.MINE_LIFT, mine.worth("walrus", z("walrus")), 0.001f)
        // A word they have not typed there is worth what it was.
        assertEquals(plain.worth("door", z("door")), mine.worth("door", z("door")), 0.001f)
        assertEquals(plain.worth("otter", z("otter")), mine.worth("otter", z("otter")), 0.001f)
    }

    @Test fun aTableWithNoRowHereCountsAsFarAsItsWeightGoes() {
        // English's row after "thank", in a Scorer whose other (counted) table has no row there: "you" is worth less
        // the more the other table weighs, and still more than its own frequency.
        val other = table("haus\t=100 ist:5000")
        val alone = here("thank")!!.worth("you", z("you"))
        val beside = scorerOf(listOf(english, other), floatArrayOf(0.4f, 1f), "thank").here { zipf[it] }!!.worth("you", z("you"))
        assertTrue("alone $alone, beside $beside", beside < alone - 0.3f && beside > z("you"))
        // Where English leads, it counts nearly in full.
        val leads = scorerOf(listOf(english, other), floatArrayOf(1f, 0.1f), "thank").here { zipf[it] }!!.worth("you", z("you"))
        assertTrue(leads > beside && leads <= alone)
        // Where neither table has a row there is no place, and a weight of 0 has no say at all (never NaN).
        assertNull(scorerOf(listOf(english, other), floatArrayOf(1f, 1f), "walrusish").here { zipf[it] })
        assertNull(scorerOf(listOf(english), floatArrayOf(0f), "thank").here { zipf[it] })
    }

    @Test fun aPossessiveIsNoRowOfTheLetterS() {
        // English's table has a row for the letter s; after "mary's" (no row of its own) it says nothing.
        assertTrue(english.range("s").isEmpty().not())
        assertNull(here("mary's"))
        assertTrue(here("it's") != null)
    }

    // --- autocorrect at a place --------------------------------------------------------------------------------------

    private val ac get() = TypingAccuracyTest.ac
    private fun taps(word: String, vararg off: Pair<Int, Pair<Float, Float>>) = TypingAccuracyTest.taps(word, *off)

    @Test fun aCommonWordWhereItAllButNeverStandsGivesWayToTheWordThatDoes() {
        // "need to rat more": the r landed a third of a key toward e. "rat" is a common word, and stood however
        // its tap leaned until the place was read: "eat" is what follows "need to".
        val leaning = taps("rat", 0 to (-0.33f to 0f))
        assertEquals("eat", ac.correct("rat", scorer("need", "to"), leaning))
        // With no words before to go by, and after words no table has a row for, it stands as it did.
        assertNull(ac.correct("rat", null, leaning))
        assertNull(ac.correct("rat", scorer("walrusish"), leaning))
    }

    @Test fun aWordThePlaceExpectsStandsThoughItsTapLeans() {
        // "eat" after "need to", its e a third of a key toward r: "rat" is not what follows.
        assertNull(ac.correct("eat", scorer("need", "to"), taps("eat", 0 to (0.33f to 0f))))
        // "you" after "thank", the y most of the way to t.
        assertNull(ac.correct("you", scorer("thank"), taps("you", 0 to (-0.45f to 0f))))
    }

    @Test fun aRightWordRareInItsPlaceStandsWhenItsTapsAreOnTheirKeys() {
        // "the walrus", "need to rest", "thank them": none is what the tables expect first, all are what was typed.
        assertNull(ac.correct("walrus", scorer("the"), taps("walrus")))
        assertNull(ac.correct("rest", scorer("need", "to"), taps("rest")))
        assertNull(ac.correct("them", scorer("thank"), taps("them")))
    }

    @Test fun lettersNoListHasAreReadAsTheWordThePlaceExpects() {
        // "thank yoi": the i is beside u and o, and "you" is what follows "thank".
        assertEquals("you", ac.correct("yoi", scorer("thank"), taps("yoi")))
    }

    @Test fun aJoinAfterAWordAnUncountedTableHasARowForIsReadAsBefore() {
        // English and a table without counts (as every language but English's is) that has a row for "was": the
        // place after "was" cannot be weighed, and the join is read as it was before there was a place.
        val uncounted = table("was\tnicht:3000 ein:2000")
        assertFalse(uncounted.counted)
        val both = scorerOf(listOf(english, uncounted), floatArrayOf(1f, 0.5f), "she")
        assertEquals("was afraid", ac.correct("wasafraid", both, taps("wasafraid")))
        assertEquals("was afraid", ac.correct("wasafraid", scorer("she"), taps("wasafraid")))
    }

    @Test fun rightWordsWithEveryTapOnTheirKeysStandWhereTheTablesExpectANeighbour() {
        // A sentence opening with "Its" (Tatoeba hardly has one: "It's" is what the table expects), "too" ending
        // "I went too", "Tom" after "had", "thee" after "in": every tap in the middle of its key.
        assertNull(ac.correct("Its", scorer("^"), taps("Its")))
        assertNull(ac.correct("too", scorer("went"), taps("too")))
        assertNull(ac.correct("Tom", scorer("had"), taps("Tom")))
        assertNull(ac.correct("thee", scorer("in"), taps("thee")))
        // With a tap on the line between two keys the place is read: "rat" a third of a key toward e is "eat"
        // after "need to" (aCommonWordWhereItAllButNeverStandsGivesWayToTheWordThatDoes), and the same "rat" with
        // every tap in the middle of its key stands.
        assertNull(ac.correct("rat", scorer("need", "to"), taps("rat")))
    }

    @Test fun thePersonsCountsAloneNeverTakeACommonWordsPlace() {
        // "Ken" after "hi" where "jen" has been typed there three or ten times: Ken stands, typed cleanly or with
        // its k a third of a key toward j.
        for (n in listOf(3f, 10f)) {
            val mine = listOf(PersonalPairs.Entry("jen", n))
            assertNull(ac.correct("ken", scorer("hi", mine = mine), taps("ken")))
            assertNull(ac.correct("ken", scorer("hi", mine = mine), taps("ken", 0 to (-0.33f to 0f))))
        }
    }

    /** [first], a tap on the space bar under the key [under], [down] of the bar's height below its top edge, then [second]. */
    private fun cut(first: String, second: String, under: Char, down: Float): Taps {
        val a = taps(first)
        val b = taps(second)
        val t = TypingAccuracyTest
        val bar = floatArrayOf(t.space[1] + down * (t.space[3] - t.space[1]))
        return Taps(a.xs + floatArrayOf(t.centres.getValue(under).first) + b.xs, a.ys + bar + b.ys, t.centres, t.W, t.space)
    }

    @Test fun aWordCutInTwoIsPutTogetherAtAPlaceAndTwoWordsStand() {
        assertEquals("being", ac.rejoin("bei", "g", "bei", scorer("am"), cut("bei", "g", 'n', 0.07f)))
        assertEquals("have", ac.rejoin("ha", "e", "ha", scorer("i"), cut("ha", "e", 'v', 0.07f)))
        assertNull(ac.rejoin("is", "a", "is", scorer("it"), cut("is", "a", 'b', 0.3f)))
        assertNull(ac.rejoin("the", "", "the", scorer("in"), cut("the", "", 'n', 0.07f)))
    }
}
