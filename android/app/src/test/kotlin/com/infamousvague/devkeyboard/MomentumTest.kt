package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Typing › Held backspace (Momentum.kt): a rate, letters before words, an optional ramp, a rumble per word. */
class MomentumTest {
    private fun steps(s: Momentum.Settings) = (0 until 80).map { Momentum.step(it, s) }

    @Test fun `the defaults are the keyboard's own held backspace, step for step`() {
        assertEquals(400L, Momentum.HOLD_MS)
        steps(Momentum.Settings()).forEachIndexed { i, s ->
            if (i < KeyboardView.DELETE_CHARS) assertEquals(Momentum.Step(false, 50, 0f), s)
            else assertEquals(Momentum.Step(true, KeyboardView.DELETE_WORD_MS, 0f), s)
        }
    }

    @Test fun `the rate is letters a second, and words follow it`() {
        for (rate in listOf(4, 10, 20, 33, 40)) {
            val all = steps(Momentum.Settings(rate = rate))
            all.filter { !it.word }.forEach { assertEquals(Math.round(1000.0 / rate), it.nextMs) }
            all.filter { it.word }.forEach { assertEquals(Momentum.wordMs(rate), it.nextMs) }
        }
        assertTrue(Momentum.wordMs(40) < Momentum.wordMs(20))
        assertTrue(Momentum.wordMs(4) <= 500L && Momentum.wordMs(40) >= 80L)
    }

    @Test fun `letters before words is how many it takes one by one`() {
        for (n in listOf(0, 1, 5, 15, 40)) {
            val all = steps(Momentum.Settings(letters = n))
            assertTrue(all.take(n).none { it.word })
            assertTrue(all.drop(n).all { it.word })
        }
    }

    @Test fun `every ramp starts at the base and only quickens, harder ones further and sooner`() {
        val reach = Momentum.Ramp.entries.map { ramp ->
            val all = steps(Momentum.Settings(rate = 10, letters = 20, ramp = ramp))
            val letters = all.take(20).map { it.nextMs }
            val words = all.drop(20).map { it.nextMs }
            assertEquals(100L, letters.first())
            assertEquals(Momentum.wordMs(10), words.first())
            assertEquals(letters.sortedDescending(), letters)
            assertEquals(words.sortedDescending(), words)
            assertTrue(letters.all { it >= Momentum.LETTER_FLOOR_MS } && words.all { it >= Momentum.WORD_FLOOR_MS })
            letters[5] to letters.last()
        }
        // Off holds the base; each harder ramp is faster both a few letters in and at the end.
        assertEquals(100L, reach[0].second)
        for (i in 1 until reach.size) {
            assertTrue(reach[i].first < reach[i - 1].first)
            assertTrue(reach[i].second < reach[i - 1].second)
        }
        assertEquals(Momentum.Ramp.OFF, Momentum.Ramp.of("warp"))
    }

    @Test fun `a rumble only on words, only when on, firmer as it goes and by strength`() {
        assertTrue(steps(Momentum.Settings()).all { it.rumble == 0f })
        val r = steps(Momentum.Settings(rumble = true)).let { all -> all.filter { !it.word }.forEach { assertEquals(0f, it.rumble) }; all.filter { it.word }.map { it.rumble } }
        assertTrue(r.all { it > 0f && it <= 1f })
        assertEquals(r.sorted(), r)
        val light = Momentum.step(15, Momentum.Settings(rumble = true, strength = "light")).rumble
        val strong = Momentum.step(15, Momentum.Settings(rumble = true, strength = "strong")).rumble
        assertTrue(light < strong)
    }
}
