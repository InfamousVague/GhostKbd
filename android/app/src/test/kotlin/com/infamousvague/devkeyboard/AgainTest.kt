package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second look ([Autocorrect.again]): the word before, read again once the word after it has ended, against the
 * real English list and starter table. Where nothing counts how often the tables' words were seen, where there is
 * no place to weigh at (prediction off) or the switch is off, there is no second look.
 */
class AgainTest {
    private val ac get() = TypingAccuracyTest.ac
    private fun taps(word: String, vararg off: Pair<Int, Pair<Float, Float>>) = TypingAccuracyTest.taps(word, *off)
    private fun scorer(vararg before: String) = PlaceWorthTest.scorer(*before)

    @Test fun aSlipThatSpellsAWordIsMendedByTheWordAfter() {
        // "we ate going": the r of "are" landed on t, near its left edge. "ate" is a word, and the space left it; "going" after "ate"
        // is unlikely, after "are" it is what comes.
        val t = taps("ate", 1 to (-0.45f to 0f))
        assertEquals("are", ac.again("ate", "ate", "going", scorer("we"), t))
    }

    @Test fun aPairOfCommonWordsTheTablesNeverSawCountsMore() {
        // "be is" opening a sentence: the b tapped a fifth of a key high, toward h. Between words this common the tables would have
        // seen the pair, and it counts up to AGAIN_LOW_BOTH against "be" (Autocorrect.AGAIN_LOW_BOTH).
        val t = taps("be", 0 to (0f to -0.2f))
        assertEquals("he", ac.again("be", "be", "is", scorer("^"), t))
        val was = Autocorrect.AGAIN_LOW_BOTH
        try {
            Autocorrect.AGAIN_LOW_BOTH = 0f
            assertNull(ac.again("be", "be", "is", scorer("^"), t))
        } finally { Autocorrect.AGAIN_LOW_BOTH = was }
        assertNull(ac.again("be", "be", "here", scorer("will"), taps("be")))
    }

    @Test fun aRightWordTheWordAfterBearsOutStands() {
        assertNull(ac.again("are", "are", "going", scorer("we"), taps("are")))
        assertNull(ac.again("walrus", "walrus", "is", scorer("the"), taps("walrus")))
    }

    @Test fun aWordNoListHasIsNeverPutBack() {
        // "ery" stood as "very" after the space: whatever follows, the letters no list has do not come back. Without
        // the guard (AGAIN_UNLISTED) some do ("abbndon", tapped on the middles of its keys, over "abandon"), as the
        // first draft put back "Ery" on the seen set.
        val cases = listOf(Triple("ery", "very", "good"), Triple("abbndon", "abandon", "of"), Triple("acandon", "abandon", "is"))
        val was = Autocorrect.AGAIN_UNLISTED
        val unguarded = try {
            Autocorrect.AGAIN_UNLISTED = true
            cases.count { (typed, stood, next) -> ac.again(typed, stood, next, scorer("^"), taps(typed)) == typed }
        } finally { Autocorrect.AGAIN_UNLISTED = was }
        assertTrue("without the guard some come back", unguarded > 0)
        for ((typed, stood, next) in cases) assertNotEquals(typed, ac.again(typed, stood, next, scorer("^"), taps(typed)))
    }

    @Test fun noSecondLookWithoutAPlaceCountedTablesOrTheSwitch() {
        val t = taps("ate", 1 to (-0.45f to 0f))
        assertNull(ac.again("ate", "ate", "going", null, t))
        assertNull(ac.again("ate", "ate", "going", scorer("we"), null))
        // A table from before the counts (every language's but English's today): nothing to hold one word against another by.
        val old = PlaceWorthTest.table("we\tare=50 ate=1", "are\tgoing=40")
        val uncounted = Scorer(NextWordText.Place(null, "we", "", emptyList()), listOf(old), floatArrayOf(1f), emptyList(), emptyList(), object : Scorer.Lookup {
            override fun isWord(lower: String) = PlaceWorthTest.zipf.containsKey(lower)
            override fun form(lower: String) = lower
            override fun count(lower: String) = 0f
            override fun starting(prefix: String) = emptyList<Pair<String, Float>>()
            override fun fills() = emptyList<String>()
        })
        assertTrue(!uncounted.counted)
        assertNull(ac.again("ate", "ate", "going", uncounted, t))
        val was = Autocorrect.AGAIN
        try {
            Autocorrect.AGAIN = false
            assertNull(ac.again("ate", "ate", "going", scorer("we"), t))
        } finally { Autocorrect.AGAIN = was }
    }

    @Test fun whatAPlaceMakesOfTheWordAfter() {
        val s = scorer("we")
        val z = PlaceWorthTest.z("going")
        // "going" is likelier after "are" than anywhere, unlikely after "ate", and nothing is known after a word no row has.
        assertTrue(s.follows("are", "going", z) { PlaceWorthTest.zipf[it] } > 0f)
        assertTrue(s.follows("ate", "going", z) { PlaceWorthTest.zipf[it] } < s.follows("are", "going", z) { PlaceWorthTest.zipf[it] })
        assertEquals(0f, s.follows("qqzzx", "going", z) { PlaceWorthTest.zipf[it] }, 0f)
    }
}
