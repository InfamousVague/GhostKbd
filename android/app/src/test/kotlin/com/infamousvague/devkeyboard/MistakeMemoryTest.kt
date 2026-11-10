package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the keyboard learns from the person's own corrections (Matt, 2026-11-10: "I want the models to be trained off
 * of previous mistakes"): [MistakeMemory] alone, with no phone under it.
 */
class MistakeMemoryTest {
    @Test fun aMistakeIsGoneByFromItsSecondCorrection() {
        val m = MistakeMemory()
        m.learn("kria", "kira")
        assertNull("once is remembered, not gone by", m.meant("kria"))
        m.learn("kria", "kira")
        assertEquals("kira", m.meant("kria"))
        assertEquals(mapOf("kria" to "kira"), m.settled())
    }

    @Test fun aFixLeftToStandIsHalfALesson() {
        val m = MistakeMemory()
        repeat(3) { m.learn("teh", "the", 0.5f) }
        assertNull(m.meant("teh"))
        m.learn("teh", "the", 0.5f)
        assertEquals("the", m.meant("teh"))
    }

    @Test fun undoingAFixForgetsIt() {
        val m = MistakeMemory()
        repeat(4) { m.learn("ded", "dead") }
        assertEquals("dead", m.meant("ded"))
        m.forget("ded", "Dead")
        assertNull(m.meant("ded"))
        assertEquals(0, m.typedPairs)
    }

    @Test fun twoMeaningsOfOneSpellingSettleOnlyWhenOneLeads() {
        val m = MistakeMemory()
        repeat(2) { m.learn("thw", "the") }
        repeat(2) { m.learn("thw", "thaw") }
        assertNull("neither leads", m.meant("thw"))
        m.learn("thw", "the")
        assertEquals("the", m.meant("thw"))
    }

    @Test fun aSpellingThatIsAlsoMeantIsNotMendedAway() {
        val m = MistakeMemory()
        repeat(2) { m.learn("than", "then") }
        assertEquals("then", m.meant("than"))
        // "than" typed and left as it was: once more and it is meant as often as it is a mistake.
        assertTrue(m.kept("than"))
        assertEquals("still a lead of one", "then", m.meant("than"))
        assertTrue(m.kept("than"))
        assertNull(m.meant("than"))
        // It turns out one of those did not stand after all.
        m.kept("than", -1f); m.kept("than", -1f)
        assertEquals("then", m.meant("than"))
        // A word nothing is remembered of has nothing to count.
        assertFalse(m.kept("hello"))
        assertEquals("the pair is one mistake, however often the spelling stood", 1, m.typedPairs)
    }

    @Test fun onlyWordsASlipApartAreAMistakeAndItsMending() {
        assertTrue(MistakeMemory.alike("kria", "kira"))
        assertTrue(MistakeMemory.alike("teh", "the"))
        assertTrue(MistakeMemory.alike("tommorow", "tomorrow"))
        assertTrue(MistakeMemory.alike("thankyou", "thank you"))
        assertTrue(MistakeMemory.alike("recieve", "receive"))
        assertFalse("a change of mind", MistakeMemory.alike("cat", "dog"))
        assertFalse(MistakeMemory.alike("great", "terrible"))
        assertFalse("two slips in a short word", MistakeMemory.alike("form", "farm".reversed()))
        assertFalse(MistakeMemory.alike("same", "same"))
    }

    @Test fun swipeChoicesAreCountedAgainstTheReadingThatStood() {
        val m = MistakeMemory()
        m.learnSwipe("form", "from")
        m.learnSwipe("form", "from")
        assertEquals(2f, m.chosen().getValue("form").getValue("from"), 0.001f)
        // The first reading stood: counted only because something has been chosen over it before.
        assertTrue(m.stood("form"))
        assertEquals(1f, m.chosen().getValue("form").getValue("form"), 0.001f)
        assertFalse(m.stood("hello"))
        assertEquals(1, m.swipedPairs)
    }

    @Test fun theFileRoundTripsAndANewerOneIsNotRead() {
        val m = MistakeMemory()
        repeat(2) { m.learn("kria", "Kira") }
        m.learn("thankyou", "thank you")
        m.learnSwipe("form", "from")
        m.kept("kria", 0.5f)
        val text = StringBuilder().also { m.write(it) }.toString()
        val back = MistakeMemory.read(text.lineSequence().filter { it.isNotEmpty() })!!
        assertEquals("Kira", back.meant("kria"))
        assertEquals(m.typedPairs, back.typedPairs)
        assertEquals(m.swipedPairs, back.swipedPairs)
        assertEquals(m.events, back.events)
        assertEquals(text, StringBuilder().also { back.write(it) }.toString())
        assertNull(MistakeMemory.read(sequenceOf("#ghost-mistakes\t2\tevents=0")))
        assertNull(MistakeMemory.read(sequenceOf("not a file of ours")))
        assertNull(MistakeMemory.read(emptySequence()))
        // A damaged line is passed over; the rest is read.
        val some = MistakeMemory.read(sequenceOf("#ghost-mistakes\t1\tevents=4", "t\tkria", "junk", "t\tteh\tthe:2.00\tbad", "s\tform\tfrom:nope"))!!
        assertEquals("the", some.meant("teh"))
        assertEquals(1, some.typedPairs)
    }

    @Test fun oldMistakesFadeAndTheWholeIsBounded() {
        val m = MistakeMemory()
        repeat(2) { m.learn("kria", "kira") }
        // Thousands of other mistakes later, it has faded out of use, and then away.
        for (i in 0 until MistakeMemory.FADE_EVERY) m.learn("w$i", "v$i", 0.5f)
        m.fade()
        assertNull("one fading leaves it under what is gone by", m.meant("kria"))
        assertTrue(m.typedPairs <= MistakeMemory.MAX_PAIRS)
        for (i in 0 until MistakeMemory.FADE_EVERY * 12) m.learn("x${i % 50}", "y${i % 50}", 0.5f)
        m.fade()
        assertFalse(m.mistyped().contains("kria"))
        assertTrue(m.typedPairs <= MistakeMemory.MAX_PAIRS)
    }

    @Test fun mockingbirdsRepairsCountOnceMendedTheSameWayTwice() {
        val samples = listOf(
            "see you tomorow then" to "see you tomorrow then",
            "tomorow works for me" to "tomorrow works for me",
            "that was wierd" to "that was weird",
            null to "nothing was mended in this one",
        ).map { (raw, text) -> raw to text }
        assertEquals(mapOf("tomorow" to "tomorrow"), MistakeMemory.repairsIn(samples))
        // A spelling that stands unmended more often than it was mended is the person's own.
        val own = listOf("sup bruh" to "sup bro", "bruh what" to "bro what", null to "bruh no", null to "ok bruh", null to "bruh")
        assertEquals(emptyMap<String, String>(), MistakeMemory.repairsIn(own))
        // Mended two ways: nothing to go by.
        val two = listOf("thw end" to "the end", "thw ice" to "thaw ice", "thw one" to "the one", "let it thw" to "let it thaw")
        assertEquals(emptyMap<String, String>(), MistakeMemory.repairsIn(two))
    }
}
