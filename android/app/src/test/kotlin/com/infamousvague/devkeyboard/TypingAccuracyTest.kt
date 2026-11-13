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

    @Test fun aSpellingAnotherLoadedLanguageHasIsNoName() {
        // "roman" is Roman in English and a novel in French: with both loaded it is left. Monday is nobody else's word.
        assertEquals("Roman", ac.correct("roman", names = true))
        val both = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
        val state = LanguageState.of(listOf("en", "fr"), emptyMap(), "")
        both.loadNow(state.dictionaries, state.board)
        assertNull(both.correct("roman", names = true))
        assertEquals("Monday", both.correct("monday", names = true))
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
        // With both loaded, a name German's list has too is left (it may be an ordinary word there), and one it lacks is English's.
        val both = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
        val two = LanguageState.of(listOf("en", "de"), emptyMap(), "")
        both.loadNow(two.dictionaries, two.board)
        assertNull(both.correct("berlin", names = true))
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
        // lower case and with a capital, some of English's names and some odd capitals. Asked for names or not, the
        // answer and the row are the same, because there is no list of names to go by ([Autocorrect.load]).
        val odd = listOf("tom", "Tom", "TOM", "monday", "london", "tom's", "i", "i'm", "i'ma", "İrem", "ǅemal", "mondya", "tmo")
        var tried = 0
        for (language in Languages.ALL) {
            val dict = language.dict ?: continue
            if (language.code == "en") continue
            val other = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
            val state = LanguageState.of(listOf(language.code), emptyMap(), "")
            assertTrue(language.code, "en" !in state.dictionaries)
            other.loadNow(state.dictionaries, state.board)
            val common = File(assets, "dict/$dict.tsv").useLines { lines -> lines.take(150).map { it.substringBefore('\t') }.filter { it.isNotEmpty() }.toList() }
            for (word in common + common.map { w -> w.replaceFirstChar { it.titlecase(state.board.locale) } } + odd) {
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
