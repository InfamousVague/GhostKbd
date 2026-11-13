package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What letters no list has are worth ([LetterModel], Autocorrect.unlisted): the table read as tool/letters.py writes
 * it, the bundled English one held to what the tool itself says of it, and what autocorrect then does with a string
 * no word looks like and with a name, against the real English list.
 */
class LetterModelTest {
    companion object {
        private val assets: File by lazy { listOf("android/app/src/main/assets", "src/main/assets", "../app/src/main/assets").map(::File).first { it.exists() } }

        private fun keyboard(vararg languages: String): Autocorrect {
            val ac = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
            val state = LanguageState.of(languages.toList(), emptyMap(), "")
            ac.loadNow(state.dictionaries, state.board)
            return ac
        }

        private val english: Autocorrect by lazy { keyboard("en") }
        private val bundled: LetterModel by lazy { LetterModel.read(File(assets, "dict/en_letters.tsv").inputStream())!! }

        /** A table of runs of two, by hand: a after the start, b after a, and either ending a word. */
        private const val BY_HAND = "# a comment\n=\torder\t2\tshare\t0.01\n$\t100\t\n^\t\t25\n^a\t10\t\na\t50\t30\na$\t20\t\nab\t40\t\nb\t60\t10\nb$\t15\t\n"

        private fun table(text: String): LetterModel? = LetterModel.read(text.byteInputStream())

        /** What [block] gives with the table out of the way: the fixed bars alone, as every other language has them. */
        private fun <T> withoutTheTable(block: () -> T): T {
            val was = Autocorrect.LETTERS
            Autocorrect.LETTERS = 0f
            try { return block() } finally { Autocorrect.LETTERS = was }
        }

        /** A drawn QWERTY, as TypingAccuracyTest draws it: keys 100 wide and 150 tall, the middle row half a key in. */
        private const val W = 100f
        private const val H = 150f
        private val centres: Map<Char, Pair<Float, Float>> = buildMap {
            listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (s, x0) ->
                s.forEachIndexed { i, c -> put(c, ((x0 + i + 0.5f) * W) to ((row + 0.5f) * H)) }
            }
        }

        /** [word] as tapped: every letter in the middle of its key, but for those in [off], that many key widths right and key heights down of it. */
        private fun taps(word: String, vararg off: Pair<Int, Pair<Float, Float>>): Taps {
            val by = off.toMap()
            val xs = FloatArray(word.length); val ys = FloatArray(word.length)
            for (i in word.indices) {
                val (cx, cy) = centres.getValue(word[i])
                val (dx, dy) = by[i] ?: (0f to 0f)
                xs[i] = cx + dx * W; ys[i] = cy + dy * H
            }
            return Taps(xs, ys, centres, W, floatArrayOf(2.5f * W, 3 * H, 8.5f * W, 4 * H))
        }
    }

    // --- the table ---------------------------------------------------------------------------------------------------

    @Test fun aRunTheTableHasCostsWhatItSays() {
        val t = table(BY_HAND)!!
        assertEquals(2, t.order)
        assertEquals(0.01f, t.share, 0f)
        assertEquals(7f, t.base, 0.0001f)
        assertEquals(8, t.size)
        // "a": a after the start, then the end after a. "ab": and b between them.
        assertEquals(0.30f, t.cost("a"), 0.0001f)
        assertEquals(0.65f, t.cost("ab"), 0.0001f)
    }

    @Test fun aRunItHasNotCostsTheShorterRunAndTheStepDown() {
        val t = table(BY_HAND)!!
        // b never began a word: what the start adds for going without it (25), then b by itself (60), then b's end.
        assertEquals(1.00f, t.cost("b"), 0.0001f)
        // "ba": and a never followed b (10 and 50), then a's end.
        assertEquals(1.65f, t.cost("ba"), 0.0001f)
        assertEquals(1.10f, t.cost("aa"), 0.0001f)
        // No letters at all: the end straight after the start.
        assertEquals(1.25f, t.cost(""), 0.0001f)
        // A letter the file says nothing of is as dear as nine units; nothing is added for leaving a run never seen.
        assertEquals(10.25f, t.cost("c"), 0.0001f)
    }

    @Test fun whatIsNoLetterHasNoCost() {
        val t = table(BY_HAND)!!
        for (word in listOf("é", "a1", "a b", "A", "a^", "$", "я")) assertTrue(word, t.cost(word).isNaN())
        // Either apostrophe is the one letter.
        assertEquals(t.cost("a'"), t.cost("a’"), 0f)
    }

    @Test fun whatIsNoTableIsNotRead() {
        assertNull(LetterModel.read(null))
        assertNull(table(""))
        assertNull("no line of facts", table("a\t50\t30\n"))
        assertNull("no runs", table("=\torder\t2\tshare\t0.01\n"))
        assertNull("runs longer than a number holds", table("=\torder\t7\tshare\t0.01\na\t50\t30\n"))
        assertNull("no share", table("=\torder\t2\tshare\t0\na\t50\t30\n"))
        // A line that is no run (another script's letters, a run too long) is passed over, and the rest read.
        assertEquals(1, table("=\torder\t2\tshare\t0.01\na\t50\t30\nя\t5\t\nabcdefg\t5\t\n")!!.size)
    }

    @Test fun theBundledTableIsReadAsTheToolReadsIt() {
        // tool/letters.py writes what a few strings cost by the file it has just made, read back as this reads it.
        val line = File(assets, "dict/en_letters.tsv").useLines { lines -> lines.first { it.startsWith("# check: ") } }
        val probes = line.removePrefix("# check: ").split(", ").map { it.substringBeforeLast(' ') to it.substringAfterLast(' ').toInt() }
        assertTrue("$probes", probes.size >= 8)
        for ((word, hundredths) in probes) assertEquals(word, hundredths / 100f, bundled.cost(word), 0.0001f)
    }

    @Test fun lettersNoWordPutsTogetherCostFarMoreThanAWordsDo() {
        assertEquals(3, bundled.order)
        assertTrue("${bundled.share}", bundled.share > 0.001f && bundled.share < 0.05f)
        // The slip costs many units more than the word it was a slip of, letter for letter as long.
        for ((slip, word) in listOf("prrsrnyable" to "presentable", "hkpelwss" to "hopeless", "sgsrtet" to "starter", "exteaotdinarily" to "extraordinarily")) {
            assertTrue("$slip ${bundled.cost(slip)}, $word ${bundled.cost(word)}", bundled.cost(slip) > bundled.cost(word) + 4f)
        }
        // A name the lists lack costs about a unit a letter, as a word does.
        for (name in listOf("berber", "kira", "yurt", "ziri", "tatoeba")) {
            assertTrue("$name ${bundled.cost(name)}", bundled.cost(name) < 1.4f * (name.length + 1))
        }
    }

    // --- what autocorrect does with it -------------------------------------------------------------------------------

    @Test fun lettersNoWordPutsTogetherGoToTheWordTheirTapsReach() {
        // Every tap in the middle of the key it typed: three keys off "presentable", and no word.
        val typed = "prrsrnyable"
        assertEquals("presentable", english.correct(typed, null, taps(typed)))
        // The fixed bars hold it as dear as they hold a name, and it stood.
        assertNull(withoutTheTable { english.correct(typed, null, taps(typed)) })
    }

    @Test fun aNameTypedCleanlyStandsAsItDid() {
        for (word in listOf("ghostkbd", "zustand", "kekw", "supabase", "okafor", "thandiwe", "caoimhe", "xiomara", "piotr", "kwame", "nvim", "tmux", "zsh", "kbd", "mattv", "vercel")) {
            if (english.isWord(word)) continue
            assertNull("$word, every tap in the middle of its key", english.correct(word, null, taps(word)))
        }
    }

    @Test fun onlyEnglishAloneHasATable() {
        // German's list alone, and English's with German's beside it: the same letters stand, as under the fixed bars.
        val typed = "prrsrnyable"
        for (ac in listOf(keyboard("de"), keyboard("en", "de"))) {
            val with = ac.correct(typed, null, taps(typed))
            assertEquals(withoutTheTable { ac.correct(typed, null, taps(typed)) }, with)
            assertNull(with)
        }
        assertNotNull(english.correct(typed, null, taps(typed)))
    }
}
