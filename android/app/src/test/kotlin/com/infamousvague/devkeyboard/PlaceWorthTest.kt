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
        assertTrue(english.seen("the") > 100_000)
        assertTrue(english.seen("^") > 1_000_000)
        assertTrue(english.seen("how are") in 1 until english.seen("are"))
        assertEquals(0, english.seen("walrusish"))
    }

    @Test fun aTableWithoutCountsIsReadByItsShares() {
        // As tables were made before 2026-11-12, and as every language's but English's still is: no "=n" on a
        // one-word row. The last followers are names scaled down; "for" and "kids" were each seen three times in
        // about 1,400, and that is what the row is taken to have been seen.
        val old = table("taught\tme:2348 you:728 him:347 all:28 how:28 for:21 kids:21 Tom:8 Layla:1")
        assertEquals(0, old.seen("taught"))
        val counted = table("taught\t=1429 me:2348 you:728 him:347 all:28 how:28 for:21 kids:21 Tom:8 Layla:1")
        assertEquals(1429, counted.seen("taught"))
        // "it" is not listed: it followed at most twice in those 1,400, not twice in the 30,000 that "Layla:1" would say.
        val byShares = here("taught", table = old)!!.share("it", z("it"))
        val byCount = here("taught", table = counted)!!.share("it", z("it"))
        assertEquals(byCount, byShares, byCount * 0.02f)
        assertTrue("it after taught: $byShares", byShares > 0.5f * Scorer.UNLISTED * 0.0021f)
    }

    @Test fun whatThePersonTypesAtAPlaceLiftsAWordAndNeverLowersOne() {
        val plain = here("the")!!
        // Typed after "the" eight times: a word no row lists there is now likely there.
        val mine = here("the", mine = listOf(PersonalPairs.Entry("walrus", 8f)))!!
        assertTrue(mine.lists("walrus"))
        assertTrue(mine.worth("walrus", z("walrus")) > plain.worth("walrus", z("walrus")) + 1f)
        // A word they have not typed there is worth what it was.
        assertEquals(plain.worth("door", z("door")), mine.worth("door", z("door")), 0.001f)
        assertEquals(plain.worth("otter", z("otter")), mine.worth("otter", z("otter")), 0.001f)
    }
}
