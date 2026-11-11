package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Stats page's pictures of the keyboard are counted from (KeyTallies), the words it lists as put right most
 * often (MistakeMemory.most), and the picture itself made from everyday counts (TrainerMaps.board, as MainActivity's
 * statsPage calls it).
 */
class KeyTalliesTest {
    @Test fun `a slip counts the pair of keys, by layout, and nothing else`() {
        val t = KeyTallies()
        assertTrue(t.isEmpty)
        repeat(3) { t.slip("qwerty", 'R', 'e') }
        t.slip("qwerty", 'w', 'e')
        t.slip("azerty", 'z', 'e')
        t.slip("qwerty", 'e', 'e')      // the same key is no slip
        t.slip("qwerty", '1', 'e')      // nor is anything but a letter
        assertEquals(mapOf("r>e" to 3, "w>e" to 1), t.slipsOf("qwerty"))
        assertEquals(mapOf("z>e" to 1), t.slipsOf("azerty"))
        assertTrue(t.slipsOf("dvorak").isEmpty())
    }

    @Test fun `a key's taps are those that stood and those that missed it`() {
        val t = KeyTallies()
        repeat(18) { t.landed("qwerty", 'e', 0.1f, -0.05f) }
        repeat(2) { t.slip("qwerty", 'r', 'e') }
        t.slip("qwerty", 'e', 'r')      // e hit for r is r's miss, not e's
        assertEquals(20 to 2, t.tapsOf("qwerty", 'e'))
        assertEquals(1 to 1, t.tapsOf("qwerty", 'r'))
        assertEquals(0 to 0, t.tapsOf("qwerty", 'q'))
    }

    @Test fun `where taps land is their mean, kept within reason`() {
        val t = KeyTallies()
        assertNull(t.landingOf("qwerty", 't'))
        t.landed("qwerty", 'T', 0.2f, 0.1f)
        t.landed("qwerty", 't', 0.4f, -0.1f)
        t.landed("qwerty", 't', Float.NaN, 0f)     // not a place
        t.landed("qwerty", 't', 9f, -9f)           // a wild one counts as the furthest a tap can be
        val (dx, dy, n) = t.landingOf("qwerty", 't')!!
        assertEquals(3, n)
        assertEquals((0.2f + 0.4f + KeyTallies.FURTHEST) / 3, dx, 1e-4f)
        assertEquals((0.1f - 0.1f - KeyTallies.FURTHEST) / 3, dy, 1e-4f)
    }

    @Test fun `the landing place follows a grip that has changed`() {
        val t = KeyTallies()
        repeat(KeyTallies.MEMORY.toInt() + 500) { t.landed("qwerty", 'a', -0.2f, 0f) }
        assertEquals(-0.2f, t.landingOf("qwerty", 'a')!!.first, 1e-3f)
        // The count stops at the memory's length, so new taps keep their weight.
        assertEquals(KeyTallies.MEMORY.toInt(), t.landingOf("qwerty", 'a')!!.third)
        repeat(KeyTallies.MEMORY.toInt() * 3) { t.landed("qwerty", 'a', 0.2f, 0f) }
        assertTrue(t.landingOf("qwerty", 'a')!!.first > 0.15f)
    }

    @Test fun `no more pairs are kept than the most, and the rarest goes first`() {
        val t = KeyTallies()
        val letters = ('a'..'z').toList()
        var made = 0
        outer@ for (p in letters) for (m in letters) {
            if (p == m) continue
            repeat(2) { t.slip("qwerty", p, m) }
            if (++made == KeyTallies.MAX_PAIRS) break@outer
        }
        assertEquals(KeyTallies.MAX_PAIRS, t.slipsOf("qwerty").size)
        t.slip("qwerty", 'z', 'y')                 // one more: it stays, and one of the others goes
        assertEquals(KeyTallies.MAX_PAIRS, t.slipsOf("qwerty").size)
        assertEquals(1, t.slipsOf("qwerty")["z>y"])
    }

    @Test fun `written and read back, it is the same, and a file of another shape reads as nothing`() {
        val t = KeyTallies()
        repeat(4) { t.slip("qwerty", 'r', 'e') }
        t.slip("qwerty", 'm', 'n')
        repeat(7) { t.landed("qwerty", 'e', 0.12f, 0.08f) }
        t.landed("azerty", 'a', -0.3f, 0.2f)
        val (slips, landed) = t.write()
        val back = KeyTallies().also { it.read(org.json.JSONObject(slips.toString()), org.json.JSONObject(landed.toString())) }
        assertEquals(t.slipsOf("qwerty"), back.slipsOf("qwerty"))
        assertEquals(t.tapsOf("qwerty", 'e'), back.tapsOf("qwerty", 'e'))
        val a = t.landingOf("qwerty", 'e')!!
        val b = back.landingOf("qwerty", 'e')!!
        assertEquals(a.first, b.first, 1e-5f)
        assertEquals(a.second, b.second, 1e-5f)
        assertEquals(a.third, b.third)
        assertEquals(1, back.landingOf("azerty", 'a')!!.third)
        // Nothing of words in what is written: pairs of single letters and numbers only.
        assertTrue(Regex("^\\{(\"[a-z]+\":\\{(\"[a-z]>[a-z]\":\\d+,?)+\\},?)*\\}$").matches(slips.toString()))
        val odd = KeyTallies().also { it.read(org.json.JSONObject("{\"qwerty\":{\"hello\":3,\"r>e\":-1,\"r>e>t\":2}}"), org.json.JSONObject("{\"qwerty\":{\"word\":[1,1,5],\"e\":[\"x\"]}}")) }
        assertTrue(odd.isEmpty)
        t.clear()
        assertTrue(t.isEmpty)
    }

    @Test fun `the words put right most often come first, a word's commonest meaning only`() {
        val m = MistakeMemory()
        repeat(4) { m.learn("teh", "the") }
        repeat(2) { m.learn("form", "from") }
        m.learn("form", "forum")
        m.learn("anout", "about")
        // A word meant as it was typed more often than as anything else is no mistake.
        m.learn("its", "it's")
        repeat(3) { m.kept("its") }
        val most = m.most(3)
        assertEquals(listOf("teh" to "the", "form" to "from", "anout" to "about"), most.map { it.first to it.second })
        assertEquals(4f, most[0].third, 0.01f)
        assertTrue(m.most(10).none { it.first == "its" })
        assertTrue(MistakeMemory().most(5).isEmpty())
    }

    @Test fun `the keyboard's picture from everyday counts says which key is missed, what is hit instead, and what has shifted`() {
        val t = KeyTallies()
        val layout = Layouts.spec("qwerty")!!
        for (c in "etaoinshr") repeat(30) { t.landed("qwerty", c, 0.1f, 0.05f) }
        repeat(6) { t.slip("qwerty", 'r', 'e') }
        t.slip("qwerty", 'w', 'e')
        repeat(2) { t.slip("qwerty", 'm', 'n') }
        val shift: (Char) -> Pair<Float, Float> = { c -> if (c == 't') 0.12f to 0.06f else 0f to 0f }
        @Suppress("UNCHECKED_CAST")
        val board = TrainerMaps.board(
            places = layout.positions,
            taps = { c -> t.tapsOf("qwerty", c) },
            landing = { c -> t.landingOf("qwerty", c) },
            shift = shift,
            moved = layout.positions.keys.filter { shift(it) != (0f to 0f) }.toSet(),
            slips = t.slipsOf("qwerty"),
        )
        val keys = (board["rows"] as List<List<Map<String, Any?>>>).flatten().associateBy { it["key"] as String }
        assertEquals(26, keys.size)
        assertEquals(37, keys["e"]!!["taps"])
        assertEquals(7, keys["e"]!!["missed"])
        assertEquals("r", keys["e"]!!["instead"])
        assertEquals(30, keys["e"]!!["landed"])
        assertEquals(true, keys["t"]!!["moved"])
        assertEquals(0.12, (keys["t"]!!["shiftX"] as Number).toDouble(), 0.001)
        assertEquals(false, keys["e"]!!["moved"])
        assertEquals(0, keys["q"]!!["taps"])
        // Only a slip seen twice or more is drawn as an arrow, the commonest first.
        val drawn = (board["slips"] as List<Map<String, Any?>>).map { "${it["pressed"]}>${it["meant"]}:${it["count"]}" }
        assertEquals(listOf("r>e:6", "m>n:2"), drawn)
    }
}
