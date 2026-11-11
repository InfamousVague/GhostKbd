package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The stories the typing trainer has people type (assets/trainer/stories.txt): that there are enough of them, that a
 * part holds nothing the letters page cannot type, and that a part typed cleanly is one autocorrect leaves alone, so
 * a run never loses a word to the story's own vocabulary.
 */
class TrainerStoriesTest {
    companion object {
        private val assets: File by lazy { listOf("android/app/src/main/assets", "src/main/assets", "../app/src/main/assets").map(::File).first { it.exists() } }
        val stories: List<Story> by lazy { TrainerStories.parse(File(assets, "trainer/stories.txt").readText()) }
    }

    @Test fun thereAreEnoughStoriesThatRunsDoNotComeRoundSoon() {
        assertTrue("eight stories or more", stories.size >= 8)
        val parts = stories.sumOf { it.parts.size }
        assertTrue("forty parts or more, so a run a day does not repeat in a month ($parts)", parts >= 40)
        assertEquals("every story has a name of its own", stories.size, stories.map { it.id }.toSet().size)
        assertTrue(stories.all { it.id.matches(Regex("[a-z0-9-]+")) && it.title.isNotBlank() && it.parts.size >= 3 })
        assertEquals("no part twice", parts, stories.flatMap { it.parts }.toSet().size)
    }

    @Test fun aPartIsAMinutesTypingOfLettersFullStopsAndCommas() {
        for (story in stories) for ((i, part) in story.parts.withIndex()) {
            val where = "${story.title}, part ${i + 1}"
            val words = TrainerText.words(part)
            assertTrue("$where is ${words.size} words", words.size in 35..55)
            assertTrue("$where has something the letters page does not type: ${part.filterNot { it.isLetter() || it == ' ' || it == '.' || it == ',' }}", part.all { (it in 'a'..'z') || (it in 'A'..'Z') || it == ' ' || it == '.' || it == ',' })
            assertTrue("$where ends with a full stop", part.endsWith("."))
            assertTrue("$where has no double space", "  " !in part && part == part.trim())
            assertTrue("$where: a comma costs two taps on this keyboard, so few of them (${part.count { it == ',' }})", part.count { it == ',' } <= 3)
            // A sentence starts with a capital (the keyboard's own shift gives it), and nothing else has one mid-word.
            assertTrue("$where starts with a capital", part[0].isUpperCase())
            for (w in words) assertTrue("$where: $w", TrainerText.core(w).drop(1).all { it.isLowerCase() })
        }
    }

    @Test fun aStoryTypedCleanlyIsLeftAloneByAutocorrect() {
        val ac = TypingAccuracyTest.ac
        val spoiled = ArrayList<String>()
        val unknown = ArrayList<String>()
        for (story in stories) for (part in story.parts) for (token in TrainerText.words(part)) {
            val word = TrainerText.core(token)
            if (!ac.isWord(word.lowercase())) unknown += word
            val made = ac.correct(word, null, TypingAccuracyTest.taps(word))
            if (made != null) spoiled += "$word -> $made"
        }
        assertEquals("words of the stories that the English list does not have", emptyList<String>(), unknown.distinct())
        assertEquals("words of the stories that autocorrect changes though every tap was in the middle of its key", emptyList<String>(), spoiled.distinct())
    }

    @Test fun theNextPartFollowsTheStoryAndNothingComesRoundEarly() {
        val read = ArrayList<String>()
        val first = TrainerStories.next(stories, read)!!
        assertEquals(stories[0].id + "-1", first.id); assertEquals(stories[0].parts[0], first.text)
        val total = stories.sumOf { it.parts.size }
        repeat(total) {
            val part = TrainerStories.next(stories, read)!!
            assertTrue("${part.id} came round before every part was read", part.id !in read)
            // A story is told through before another begins.
            if (part.index > 0) assertEquals("${part.story.id}-${part.index}", read.last())
            read += part.id
        }
        // Every part read: the story left alone longest comes first again.
        assertEquals(stories[0].id + "-1", TrainerStories.next(stories, read)!!.id)
        assertNull(TrainerStories.next(emptyList(), read))
        // The stories' own words, for practice runs: plain lower-case words.
        val vocabulary = TrainerStories.vocabulary(stories)
        assertTrue(vocabulary.size > 300 && "lighthouse" !in vocabulary && "keeper" in vocabulary && vocabulary.all { w -> w.all { it.isLowerCase() } })
    }

    @Test fun theFileIsReadAsItIsWritten() {
        val two = TrainerStories.parse("# A note at the top of the file.\n\n# The first one\nPart one.\nPart  two.\n\n# Another, later\nIts only part.\n")
        assertEquals(listOf("the-first-one", "another-later"), two.map { it.id })
        assertEquals(listOf("Part one.", "Part two."), two[0].parts)
        assertEquals("another-later-1", StoryPart(two[1], 0).id); assertEquals(3, StoryPart(two[1], 0).words)
    }
}
