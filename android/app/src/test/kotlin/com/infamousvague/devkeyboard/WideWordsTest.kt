package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * English's wider list (assets/dict/en_wide.tsv, made by tool/wide_words.py: the words of English as it is written
 * now that en.tsv's 40,000 do not reach) as [Autocorrect] reads it: its words are words wherever English is loaded
 * and nowhere else; one a dictionary vouches for stands when typed as meant and is what a slip of it is mended to;
 * one only writing vouches for is known and never offered; a misspelling is not made right by it; a possessive is
 * known by its word; and English without the file is English as it was.
 */
class WideWordsTest {
    companion object {
        private val assets: File by lazy { listOf("android/app/src/main/assets", "src/main/assets", "../app/src/main/assets").map(::File).first { it.exists() } }

        private fun loaded(vararg codes: String, without: String? = null): Autocorrect {
            val ac = Autocorrect { name -> File(assets, name).takeIf { it.exists() && name != without }?.inputStream() }
            val state = LanguageState.of(codes.toList(), emptyMap(), "")
            ac.loadNow(state.dictionaries, state.board)
            return ac
        }

        private class Row(val word: String, val zipf: Float, val marks: String)

        private val first: Map<String, Float> by lazy {
            File(assets, "dict/en.tsv").readLines().mapNotNull { l -> l.indexOf('\t').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).toFloat() } }.toMap()
        }
        private val wide: List<Row> by lazy {
            File(assets, "dict/en_wide.tsv").readLines().map { l -> l.split('\t').let { Row(it[0], it[1].toFloat(), it[2]) } }
        }
        private val marks: Map<String, String> by lazy { wide.associate { it.word to it.marks } }
        private val english: Autocorrect by lazy { loaded("en") }

        /** A drawn QWERTY, as TypingAccuracyTest draws it: keys 100 wide and 150 tall, the space bar under the bottom row. */
        private const val W = 100f
        private const val H = 150f
        private val centres: Map<Char, Pair<Float, Float>> = buildMap {
            listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (s, x0) ->
                s.forEachIndexed { i, c -> put(c, ((x0 + i + 0.5f) * W) to ((row + 0.5f) * H)) }
            }
        }
        private val space = floatArrayOf(2.5f * W, 3 * H, 8.5f * W, 4 * H)

        /**
         * [word] as tapped: each letter in the middle of its key, but for the letters in [off], which landed that
         * many key widths to the right (and key heights down) of the middle. An apostrophe has no tap of its own.
         */
        private fun taps(word: String, vararg off: Pair<Int, Pair<Float, Float>>): Taps {
            val by = off.toMap()
            val xs = FloatArray(word.length); val ys = FloatArray(word.length)
            for (i in word.indices) {
                val c = centres[word[i]]
                if (c == null) { xs[i] = Float.NaN; ys[i] = Float.NaN; continue }
                val (dx, dy) = by[i] ?: (0f to 0f)
                xs[i] = c.first + dx * W; ys[i] = c.second + dy * H
            }
            return Taps(xs, ys, centres, W, space)
        }

        /** A finger's path over [word]: straight lines through its keys' middles, a point every eighth of the way. */
        private fun stroke(word: String): List<Pair<Float, Float>> {
            val keys = word.map { centres.getValue(it) }
            val out = arrayListOf(keys[0])
            for (i in 1 until keys.size) for (s in 1..8) out += (keys[i - 1].first + (keys[i].first - keys[i - 1].first) * s / 8f) to (keys[i - 1].second + (keys[i].second - keys[i - 1].second) * s / 8f)
            return out
        }
    }

    @Test fun theFileIsWhatTheToolSaysItIs() {
        assertTrue("${wide.size} lines", wide.size in 60_000..260_000)
        assertEquals("each word once", wide.size, wide.map { it.word }.toSet().size)
        assertEquals("in the order of the words, for the one-pass merge", wide.map { it.word }.sorted(), wide.map { it.word })
        val least = first.values.min()
        for (row in wide) {
            assertTrue("${row.word}: ${row.marks}", row.marks in setOf("d", "dn", "l", "ln", "w"))
            val known = first[row.word]
            if (known != null) {
                // A line for a word of en.tsv says what vouches for it, and carries en.tsv's own frequency.
                assertEquals(row.word, known, row.zipf, 0.001f)
                assertTrue("${row.word} is one of en.tsv's rarer words, vouched for by a dictionary", known < 3.2f && row.marks[0] == 'd')
            } else {
                val letters = row.word.replace("'", "")
                assertTrue("${row.word} is 3 to 24 plain letters with at most one apostrophe inside",
                    letters.length >= 3 && row.word.length <= 24 && letters.all { it in 'a'..'z' } && row.word.count { it == '\'' } <= 1 && row.word.first() != '\'' && row.word.last() != '\'')
                assertFalse("${row.word} is a possessive: those are known by their word", row.word.endsWith("'s"))
                assertTrue("${row.word} at ${row.zipf} is rarer than anything in en.tsv ($least)", row.zipf < least && row.zipf > 0f)
            }
        }
    }

    @Test fun itsWordsAreWordsWhereEnglishIsLoadedAndNowhereElse() {
        for (word in listOf("yurt", "stammer", "berber", "knifed", "dialled", "cupola", "tendonitis", "crawlspace", "envies", "okafor", "ludovic")) {
            assertTrue("$word is in the list, and not in en.tsv", word in marks && word !in first)
            assertTrue("$word is a word in English", english.isWord(word))
            assertTrue("$word typed is offered as itself", english.suggest(word).typedIsWord)
        }
        assertTrue("beside another language too", loaded("en", "es").isWord("yurt"))
        for (alone in listOf("de", "es", "fr")) {
            val other = loaded(alone)
            assertTrue("$alone loads", other.active)
            assertFalse("yurt is no word of $alone alone", other.isWord("yurt"))
            assertFalse("nor is crawlspace", other.isWord("crawlspace"))
        }
    }

    @Test fun bothSpellingsOfAWordAreWords() {
        for ((a, b) in listOf("dialled" to "dialed", "favour" to "favor", "moustache" to "mustache", "sceptical" to "skeptical", "counsellor" to "counselor", "jewellery" to "jewelry")) {
            assertTrue("$a and $b", english.isWord(a) && english.isWord(b))
            assertNull("$a typed as meant is not made $b", english.correct(a, null, taps(a)))
            assertNull("$b typed as meant is not made $a", english.correct(b, null, taps(b)))
        }
    }

    @Test fun aWordADictionaryVouchesForStandsWhenTypedAsMeant() {
        // Each of these was changed while no list had it (docs/versus.md): hurt, never, dialed, envied, knife.
        for (word in listOf("yurt", "berber", "dialled", "envies", "knifed", "stammer", "trill", "gabble")) {
            assertTrue("$word: ${marks[word]}", marks[word]?.startsWith("d") == true)
            assertNull("$word, every tap in the middle of its key", english.correct(word, null, taps(word)))
        }
        // One of en.tsv's own rarer words that a dictionary vouches for is held the same way: "devise" is not "decide".
        for (word in listOf("devise", "fable", "dismisses")) {
            assertTrue("$word is in en.tsv, under the line of a common word, and vouched for", (first[word] ?: 9f) < 3.2f && marks[word] == "d")
            assertNull("$word, every tap in the middle of its key", english.correct(word, null, taps(word)))
        }
    }

    @Test fun withNoTapsARareWordIsHeldAsItAlwaysWas() {
        // Where the taps are not known (the cursor was moved into the word, a list beyond the first six is loaded)
        // a rare word gives way to a far commoner one a neighbouring key away, vouched for or not: nothing says
        // which key was meant, and a slip of the commoner word is the likelier by far ([Autocorrect.SURE_UNTAPPED]).
        assertEquals(0f, Autocorrect.SURE_UNTAPPED, 0f)
        assertEquals("hurt", english.correct("yurt"))
        // The wider list's words are no readings there ([Autocorrect.WIDE_UNTAPPED]): "stammrr" is left, as it was.
        assertEquals(0f, Autocorrect.WIDE_UNTAPPED, 0f)
        assertNull(english.correct("stammrr"))
    }

    @Test fun aTapOnTheLineStillMakesItTheCommonerWord() {
        // "vases" with its v tapped on the line between c and v: the taps fit "cases" as well, and it is far commoner.
        assertNull(english.correct("vases", null, taps("vases")))
        assertEquals("cases", english.correct("vases", null, taps("vases", 0 to (-0.5f to 0f))))
    }

    @Test fun aSlipOfOneIsMendedToIt() {
        // The second r of "stammer" for its e, tapped on the line between the two keys.
        assertEquals("stammer", english.correct("stammrr", null, taps("stammrr", 5 to (-0.5f to 0f))))
        // The u of "cupola" for its o... its i: one key along, tapped on the line.
        assertEquals("cupola", english.correct("cupila", null, taps("cupila", 3 to (0.5f to 0f))))
    }

    @Test fun aWordOnlyWritingVouchesForIsKnownAndNeverOffered() {
        for (word in listOf("okafor", "ludovic", "polisario")) {
            assertEquals("$word: only writing vouches for it", "w", marks[word])
            assertTrue(english.isWord(word))
            assertNull("$word typed as meant", english.correct(word, null, taps(word)))
        }
        // A slip of one is not mended to it: "lydovic", with its y on the line to u, stays as it is or becomes a word
        // of the dictionaries, never "ludovic".
        assertNotEquals("ludovic", english.correct("lydovic", null, taps("lydovic", 1 to (0.5f to 0f))))
        // Nor is one offered where the taps are not known: "okafir" is not "okafor".
        assertNotEquals("okafor", english.correct("okafir"))
    }

    @Test fun aWordOfItThatThePersonTypesOftenIsTheirs() {
        // "In no word list", for working out the person's own words, is as it was: the first lists, not the wider one.
        for (word in listOf("okafor", "yurt", "coffered")) assertTrue("$word is a word, and may still become the person's own", english.isWord(word) && !english.isListed(word))
        assertTrue(english.isListed("the") && english.isListed("definately"))
        // Typed three times it is theirs (NextWords.vocabulary, DevKeyboardService): never changed, mended toward, swiped.
        val ac = loaded("en")
        assertNotEquals("okafor", ac.correct("okafir", null, taps("okafir", 4 to (0.5f to 0f))))
        assertFalse("coffered" in ac.swipePlain(stroke("coffered"), centres, W))
        ac.setOwn(setOf("okafor", "coffered", "koko's"))
        assertEquals("a slip of their word is mended to it, its i for the o one key along", "okafor", ac.correct("okafir", null, taps("okafir", 4 to (0.5f to 0f))))
        assertNull(ac.correct("okafor", null, taps("okafor")))
        assertNull("and with no taps", ac.correct("okafor"))
        assertEquals("and it is read from a stroke", "coffered", ac.swipePlain(stroke("coffered"), centres, W).firstOrNull())
    }

    @Test fun aMisspellingIsNotMadeRight() {
        // What wordfreq lists and no dictionary has is left out where a commoner word is a slip away...
        for (wrong in listOf("tommorow", "awsome", "recieved", "untill", "wierd", "beleive", "goverment", "seperate")) {
            assertFalse("$wrong is not in the wider list", wrong in marks && wrong !in first)
        }
        // ...so these are mended as they were before there was such a list,
        assertEquals("tomorrow", english.correct("tommorow", null, taps("tommorow")))
        assertEquals("definitely", english.correct("definately", null, taps("definately")))
        assertEquals("separate", english.correct("seperate", null, taps("seperate")))
        // and "definately", which en.tsv has and no dictionary vouches for, is as doubtful a word as it was.
        assertTrue("definately" in first && "definately" !in marks)
    }

    @Test fun aPossessiveIsKnownByItsWord() {
        for (word in listOf("plan's", "yurt's", "champion's", "okafor's")) {
            assertFalse("$word is in no list itself", word in first || word in marks)
            assertTrue("$word is a word", english.isWord(word))
            assertTrue(english.suggest(word).typedIsWord)
            // The apostrophe has no tap of its own: it was typed from a long-press, on purpose.
            assertNull("$word typed as meant is not ${english.correct(word, null, taps(word))}", english.correct(word, null, taps(word)))
        }
        // With no taps to go by it is held as its word is: a common word's possessive stands.
        assertNull(english.correct("plan's"))
        assertNull(english.correct("champion's"))
        // A slip in its word is mended, and the 's is kept: the s of "plsn's" for the a of "plan's", tapped on the line.
        assertEquals("plan's", english.correct("plsn's", null, taps("plsn's", 2 to (-0.5f to 0f))))
        // The lists' own possessives and contractions are words as they stand, as they were.
        assertNull(english.correct("tom's", null, taps("tom's")))
        assertNull(english.correct("it's", null, taps("it's")))
        // Letters that are no word do not become one for an 's after them.
        assertFalse(english.isWord("ghostkbd's"))
        assertFalse(english.isWord("xq's"))
    }

    @Test fun aWordOfItIsReadFromAStroke() {
        // "stammer" (Zipf 2.49) is common enough to be a word a stroke may be ([Autocorrect.WIDE_SWIPE]).
        assertTrue((wide.first { it.word == "stammer" }.zipf) >= Autocorrect.WIDE_SWIPE)
        assertEquals("stammer", english.swipePlain(stroke("stammer"), centres, W).firstOrNull())
        // "coffered" (1.96) is known when typed and is not swiped: the rarest words are left out of the strokes.
        val rare = wide.first { it.word == "coffered" }
        assertTrue(rare.zipf < Autocorrect.WIDE_SWIPE && english.isWord("coffered"))
        assertFalse("coffered" in english.swipePlain(stroke("coffered"), centres, W))
    }

    @Test fun englishWithoutTheFileIsEnglishAsItWas() {
        val plain = loaded("en", without = "dict/en_wide.tsv")
        assertTrue(plain.active && plain.settled)
        assertTrue(plain.isWord("the") && plain.isWord("definately"))
        for (word in listOf("yurt", "stammer", "okafor", "plan's", "yurt's")) assertFalse("$word is no word without the wider list", plain.isWord(word))
        // What en.tsv alone made of these (docs/versus.md): the rule for a word a dictionary vouches for is not there to hold them.
        assertEquals("hurt", plain.correct("yurt"))
        assertEquals("plans", plain.correct("plan's", null, taps("plan's")))
    }

    @Test fun aCautiousListLeavesEnglishAsItWas() {
        // A list beyond the first six beside English: no taps are read, and English is held exactly as with en.tsv alone.
        val later = LanguageState.of(listOf("en", "pl"), emptyMap(), "")
        val withIt = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }.also { it.loadNow(later.dictionaries, later.board) }
        val without = Autocorrect { name -> File(assets, name).takeIf { it.exists() && name != "dict/en_wide.tsv" }?.inputStream() }.also { it.loadNow(later.dictionaries, later.board) }
        assertTrue(withIt.active && withIt.settled)
        for (word in listOf("yurt", "stammer", "okafor", "plan's", "yurt's", "the", "definately")) {
            assertEquals(word, without.isWord(word), withIt.isWord(word))
            assertEquals(word, without.correct(word), withIt.correct(word))
            assertEquals(word, without.correct(word, null, taps(word)), withIt.correct(word, null, taps(word)))
        }
        assertFalse(withIt.isWord("yurt"))
    }

    @Test fun aNameOnlyWritingVouchesForIsHeldNoLessThanWhenNoListHadIt() {
        // "piotr", typed as meant, was "poor" on the bench's careful thumb once it became a rare listed word; held at
        // the bar of letters no list has as well ([Autocorrect.WIDE_HOLD]) it stands, as it did unlisted.
        assertEquals(1f, Autocorrect.WIDE_HOLD, 0f)
        for (word in listOf("piotr", "okafor", "zoltan")) {
            assertEquals("$word: only writing vouches for it", "w", marks[word])
            assertNull(word, english.correct(word, null, taps(word)))
        }
        // Its i tapped on the line to o: held by the rare-word rule alone it would be "poor"; held as before, it stands.
        val slip = taps("piotr", 1 to (0.5f to 0f))
        assertNull(english.correct("piotr", null, slip))
        try {
            Autocorrect.WIDE_HOLD = 0f
            assertEquals("poor", english.correct("piotr", null, slip))
        } finally { Autocorrect.WIDE_HOLD = 1f }
    }

    @Test fun theFirstListIsInUseBeforeTheWiderOneIsRead() {
        val ac = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
        val state = LanguageState.of(listOf("en"), emptyMap(), "")
        val told = ArrayList<Boolean>()
        ac.onLoaded = { synchronized(told) { told += ac.isWord("yurt") } }
        ac.setLanguages(state.dictionaries, state.board)
        val until = System.currentTimeMillis() + 60_000
        while (!ac.settled && System.currentTimeMillis() < until) Thread.sleep(10)
        assertTrue(ac.settled)
        // The second onLoaded comes just after settled turns true: wait for it, with a deadline, not a fixed sleep.
        while (synchronized(told) { told.size } < 2 && System.currentTimeMillis() < until) Thread.sleep(10)
        // Told twice: when en.tsv was in (the first words typed are corrected by it), and again with the wider list.
        assertEquals(listOf(false, true), synchronized(told) { told.toList() })
        assertTrue(ac.isWord("the") && ac.isWord("yurt"))
    }
}
