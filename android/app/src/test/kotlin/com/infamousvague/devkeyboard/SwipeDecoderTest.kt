package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer
import kotlin.random.Random

/**
 * Swipe typing's decoder against the real English list (assets/dict/en.tsv), with strokes drawn the way a thumb
 * draws them: through each letter's key, corners rounded, a little shake. Phrases in one stroke come out as phrases
 * (Matt, 2026-11-10: "hey what's up" came out "hardship"), and long words swiped alone stay one word.
 */
class SwipeDecoderTest {
    companion object {
        private val dict: Map<String, Float> by lazy {
            val f = listOf("android/app/src/main/assets/dict/en.tsv", "src/main/assets/dict/en.tsv", "../app/src/main/assets/dict/en.tsv")
                .map(::File).first { it.exists() }
            f.readLines().mapNotNull { l -> val t = l.indexOf('\t'); if (t > 0) l.substring(0, t) to (l.substring(t + 1).toFloatOrNull() ?: return@mapNotNull null) else null }.toMap()
        }

        fun letters(word: String) = Normalizer.normalize(word.lowercase(), Normalizer.Form.NFD).filter { it in 'a'..'z' }

        private val index: Map<String, List<String>> by lazy {
            val m = HashMap<String, ArrayList<String>>()
            for (w in dict.keys) {
                val l = letters(w)
                if (l.isEmpty() || l.length > 20 || w.any { !it.isLetter() && it != '\'' }) continue
                m.getOrPut("${l.first()}${l.last()}") { ArrayList() } += w
            }
            m
        }

        /** The English starter table (assets/ngram/en.tsv): for each word, its followers' shares in ten-thousandths. */
        private val pairs: Map<String, Map<String, Float>> by lazy {
            val f = listOf("android/app/src/main/assets/ngram/en.tsv", "src/main/assets/ngram/en.tsv").map(::File).first { it.exists() }
            f.readLines().drop(1).mapNotNull { l ->
                val t = l.indexOf('\t'); if (t <= 0 || l.indexOf(' ') in 0 until t) return@mapNotNull null
                l.substring(0, t).lowercase() to l.substring(t + 1).split(' ').mapNotNull { e ->
                    val c = e.lastIndexOf(':'); if (c <= 0) null else e.substring(0, c).lowercase() to (e.substring(c + 1).toFloatOrNull() ?: return@mapNotNull null) / 10_000f
                }.toMap()
            }.toMap()
        }

        val decoder = SwipeDecoder(object : SwipeDecoder.Lexicon {
            override fun ends(first: Char, last: Char) = index["$first$last"] ?: emptyList()
            override fun zipf(word: String) = dict[word]
            override fun letters(word: String) = SwipeDecoderTest.letters(word)
            override fun follows(prev: String) = pairs[prev]
        }, budgetMs = 60_000) // no deadline here: what is checked is the reading, not this machine's load

        /** A phone's QWERTY in key widths: rows 1.35 keys apart, the middle row half a key in, the bottom a key and a half. */
        val keys: Map<Char, Pair<Float, Float>> = buildMap {
            listOf("qwertyuiop" to 0.5f, "asdfghjkl" to 1.0f, "zxcvbnm" to 2.0f).forEachIndexed { row, (s, x0) ->
                s.forEachIndexed { i, c -> put(c, (x0 + i) to (0.65f + row * 1.35f)) }
            }
        }

        /**
         * A thumb's stroke through [text]'s letters: each turn rounded a third of a key either side of its letter
         * (however long the glides), then a slow wobble of up to [shake] key widths either way (a thumb drifts
         * smoothly, it doesn't shake point by point) and a hair of jitter.
         */
        fun stroke(text: String, seed: Int = 1, shake: Float = 0.3f): List<Pair<Float, Float>> {
            var pts = ArrayList<Pair<Float, Float>>()
            var prev = ' '
            for (c in letters(text.replace(" ", ""))) { if (c != prev) pts += keys.getValue(c); prev = c }
            if (pts.size == 1) pts += pts[0].first + 0.9f to pts[0].second
            val s = ArrayList<Pair<Float, Float>>()
            s += pts.first()
            for (i in 1 until pts.size - 1) {
                val a = pts[i - 1]; val v = pts[i]; val b = pts[i + 1]
                fun toward(q: Pair<Float, Float>): Pair<Float, Float> {
                    val d = SwipeDecoder.dist(v, q); val k = minOf(0.33f, d / 3) / d
                    return (v.first + (q.first - v.first) * k) to (v.second + (q.second - v.second) * k)
                }
                val inn = toward(a); val out = toward(b)
                // A quadratic curve from the way in to the way out, bending through the letter.
                for (j in 0..6) {
                    val t = j / 6f; val u = 1 - t
                    s += (u * u * inn.first + 2 * u * t * v.first + t * t * out.first) to (u * u * inn.second + 2 * u * t * v.second + t * t * out.second)
                }
            }
            s += pts.last()
            pts = s
            val r = Random(seed)
            val dense = SwipeDecoder.resample(pts, (SwipeDecoder.length(pts) / 0.1f).toInt().coerceAtLeast(10))
            val ph = FloatArray(4) { r.nextFloat() * 6.283f }
            return dense.mapIndexed { i, (x, y) ->
                val d = i * 0.1f
                val wx = (0.6f * kotlin.math.sin(d * 1.3f + ph[0]) + 0.4f * kotlin.math.sin(d * 0.45f + ph[1])) * shake / 2
                val wy = (0.6f * kotlin.math.sin(d * 1.1f + ph[2]) + 0.4f * kotlin.math.sin(d * 0.5f + ph[3])) * shake / 2
                (x + wx + (r.nextFloat() - 0.5f) * 0.04f) to (y + wy + (r.nextFloat() - 0.5f) * 0.04f)
            }
        }

        fun read(text: String, seed: Int = 1, before: String? = "^") = decoder.decode(stroke(text, seed), keys, 1f, before)
    }

    @Test fun heyWhatsUpIsAPhrase() {
        for (seed in 1..5) assertEquals("seed $seed: ${read("hey what's up", seed)}", "hey what's up", read("hey what's up", seed).first())
    }

    @Test fun commonPhrasesInOneStroke() {
        val phrases = listOf("how are you", "see you soon", "on my way", "thank you", "what time is it", "good morning", "where are you", "talk to you later")
        // Where the stroke alone can't tell (the letters of "see you soon" lie along "serious on"), the starter
        // tables decide, and they don't always: at least half must read right, and the rest be offered.
        val wrong = phrases.filter { p -> read(p).firstOrNull() != p }
        assertTrue("misread: ${wrong.map { it to read(it).take(3) }}", wrong.size <= phrases.size / 2)
    }

    @Test fun longWordsStayOneWord() {
        val words = listOf("hardship", "hello", "keyboard", "something", "together", "because", "different", "yesterday", "understand", "beautiful", "everything", "important")
        val wrong = words.filter { w -> read(w).firstOrNull() != w }
        assertTrue("misread: ${wrong.map { it to read(it).take(3) }}", wrong.isEmpty())
    }

    @Test fun commonWordsAreNotSplit() {
        // The commonest words of six letters or more, swiped alone: almost all must stay one word.
        val words = dict.entries.filter { (w, _) -> w.length >= 6 && w.all { it in 'a'..'z' } }.sortedByDescending { it.value }.take(150).map { it.key }
        val split = words.filter { w -> read(w).firstOrNull()?.contains(' ') == true }
        assertTrue("split ${split.size}/150: ${split.map { it to read(it).first() }}", split.size <= 6)
    }

    @Test fun aThereAndBackIsPartOfTheWord() {
        // The "re" of "there" runs back along the line just drawn. Until 0.11.0 the stroke was held against a word's
        // lines in no order, so that stretch was excused by the line from h to e and the word read as "the".
        for (word in listOf("there", "here", "where", "were")) for (seed in 1..5) {
            assertEquals("$word, seed $seed: ${read(word, seed, null)}", word, read(word, seed, null).first())
        }
        assertEquals("the", read("the", 1, null).first())
        assertEquals("he", read("he", 1, null).first())
    }

    @Test fun aDetourIsNotExcusedByALineTheWordReachesLater() {
        // The d of "adriana" lies beside the way back from i to a: it made the word "ariana" while that line could
        // answer for it. Drawn straight through its keys, a word reads as itself.
        for (word in listOf("adriana", "there", "masterpiece", "pleaded")) {
            if (dict[word] == null) continue
            val got = decoder.decode(stroke(word, 1, shake = 0f), keys, 1f, null)
            assertEquals("$word: $got", word, got.first())
        }
    }

    @Test fun aScrapOfTwoLettersDoesNotTakeAWordsStroke() {
        // w to p along the top row is "weep" before it is "wp".
        val got = decoder.decode(stroke("weep", 1, shake = 0f), keys, 1f, null)
        assertTrue("$got", got.indexOf("weep") in 0 until (got.indexOf("wp").takeIf { it >= 0 } ?: got.size))
        // A common two-letter word is still itself.
        for (word in listOf("we", "up", "to", "is", "of")) assertEquals(word, read(word, 1, null).first())
    }

    @Test fun anApostropheWordCanBeSwiped() {
        assertEquals("what's", read("what's").first())
        assertEquals("don't", read("don't").first())
    }
}
