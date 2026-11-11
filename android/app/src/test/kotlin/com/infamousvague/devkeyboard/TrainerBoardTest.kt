package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard as the typing trainer's pictures draw it ([TrainerMaps.board]; Matt, 2026-11-11: "Show inforgraphics
 * on the trainer with charts and stuff to show where the mistakes happen and what has shifted where using a
 * simplified keyboard display"): the keys row by row as the layout has them, each with what was measured of it, the
 * slips worth drawing, and nothing a method channel cannot carry.
 */
class TrainerBoardTest {
    private val places = Layouts.QWERTY.positions

    @Suppress("UNCHECKED_CAST")
    private fun rows(b: Map<String, Any?>) = b["rows"] as List<List<Map<String, Any?>>>
    private fun key(b: Map<String, Any?>, c: Char) = rows(b).flatten().first { it["key"] == c.toString() }

    @Suppress("UNCHECKED_CAST")
    private fun slips(b: Map<String, Any?>) = (b["slips"] as List<Map<String, Any?>>).map { "${it["pressed"]}>${it["meant"]}:${it["count"]}" }

    private fun empty(places: Map<Char, Pair<Float, Int>> = this.places) = TrainerMaps.board(places, { null }, { null }, { 0f to 0f }, emptySet(), emptyMap())

    @Test fun `the letter keys are given row by row, where the layout has them`() {
        val b = empty()
        assertEquals(listOf("qwertyuiop", "asdfghjkl", "zxcvbnm"), rows(b).map { row -> row.joinToString("") { it["key"] as String } })
        assertEquals(10.0, b["units"])
        assertEquals(0.0, key(b, 'q')["x"])
        assertEquals(0.5, key(b, 'a')["x"])
        assertEquals(1.5, key(b, 'z')["x"])
        assertEquals(9.0, key(b, 'p')["x"])
        // Nothing measured: every key is there all the same, with nothing to say of it.
        assertEquals(0, b["taps"])
        assertEquals(0, b["missed"])
        assertEquals(emptyList<Any>(), b["slips"])
        assertEquals(mapOf("key" to "q", "x" to 0.0, "taps" to 0, "missed" to 0, "landX" to 0.0, "landY" to 0.0, "landed" to 0, "shiftX" to 0.0, "shiftY" to 0.0, "moved" to false, "instead" to null), key(b, 'q'))
        assertEquals(0.3, b["maxShift"])
    }

    @Test fun `every layout the keyboard has gives three rows that fit their units`() {
        for (spec in Layouts.SPECS) {
            val b = empty(spec.positions)
            val units = b["units"] as Double
            assertTrue("${spec.id}: rows", rows(b).size in 1..3)
            assertEquals("${spec.id}: every key once", spec.positions.size, rows(b).sumOf { it.size })
            for (row in rows(b)) {
                val xs = row.map { it["x"] as Double }
                assertEquals("${spec.id}: left to right", xs.sorted(), xs)
                assertTrue("${spec.id}: inside the board", xs.all { it >= 0.0 && it + 1.0 <= units + 0.001 })
                // No two keys of a row stand on each other.
                assertTrue("${spec.id}: apart", xs.zipWithNext().all { (a, c) -> c - a >= 0.99 })
            }
        }
    }

    @Test fun `a key carries its taps and misses, where they land, and how far its touch area has moved`() {
        val b = TrainerMaps.board(
            places,
            // More misses than taps cannot be: held to the taps.
            taps = { c -> when (c) { 'e' -> 40 to 6; 'r' -> 20 to 30; 'q' -> -3 to 1; else -> null } },
            landing = { c -> when (c) { 'e' -> Triple(0.123f, -0.056f, 40); 't' -> Triple(Float.NaN, 0f, 9); 'y' -> Triple(0.2f, 0.2f, 0); else -> null } },
            shift = { c -> if (c == 'e') 0.08f to -0.04f else 0f to 0f },
            moved = setOf('e', '1'),
            slips = emptyMap(),
        )
        val e = key(b, 'e')
        assertEquals(40, e["taps"])
        assertEquals(6, e["missed"])
        assertEquals(0.12, e["landX"])
        assertEquals(-0.06, e["landY"])
        assertEquals(40, e["landed"])
        assertEquals(0.08, e["shiftX"])
        assertEquals(-0.04, e["shiftY"])
        assertEquals(true, e["moved"])
        assertEquals(20, key(b, 'r')["missed"])
        assertEquals(0, key(b, 'q')["taps"])
        assertEquals(0, key(b, 'q')["missed"])
        // A landing place that is no number, or stands on no taps, is none.
        assertEquals(0, key(b, 't')["landed"])
        assertEquals(0.0, key(b, 't')["landX"])
        assertEquals(0, key(b, 'y')["landed"])
        assertEquals(0.0, key(b, 'y')["landX"])
        assertEquals(false, key(b, 'w')["moved"])
        assertEquals(60, b["taps"])
        assertEquals(26, b["missed"])
    }

    @Test fun `the slips drawn are between two keys of the board, seen often enough, the commonest first`() {
        val all = mapOf("w>e" to 5, "r>e" to 2, "e>r" to 1, "x>e" to 9, "1>e" to 7, "e>1" to 7, "ee" to 3, "e>e" to 4, "w-e" to 6, "s>a" to 2)
        val over = TrainerMaps.board(places, { null }, { null }, { 0f to 0f }, emptySet(), all)
        assertEquals(listOf("x>e:9", "w>e:5", "r>e:2", "s>a:2"), slips(over))
        // The key hit most in a key's place is the one its row in the lists names: the commonest, and of two as common
        // the one met first (TrainerBook.weakKeys and a run's results go by the same line).
        assertEquals("x", key(over, 'e')["instead"])
        assertEquals("s", key(over, 'a')["instead"])
        assertEquals("e", key(over, 'r')["instead"])
        assertEquals(null, key(over, 't')["instead"])
        val tied = LinkedHashMap<String, Int>().apply { put("j>h", 1); put("b>h", 1); put("g>h", 1) }
        assertEquals("j", key(TrainerMaps.board(places, { null }, { null }, { 0f to 0f }, emptySet(), tied, slipFloor = 1), 'h')["instead"])
        // A run's own picture draws a slip made once.
        val run = TrainerMaps.board(places, { null }, { null }, { 0f to 0f }, emptySet(), all, slipFloor = 1)
        assertEquals(listOf("x>e:9", "w>e:5", "r>e:2", "s>a:2", "e>r:1"), slips(run))
        // And never more than six.
        val many = "qwertyuio".zipWithNext().associate { (a, c) -> "$a>$c" to 3 }
        assertEquals(8, many.size)
        assertEquals(TrainerMaps.MAX_SLIPS, slips(TrainerMaps.board(places, { null }, { null }, { 0f to 0f }, emptySet(), many)).size)
    }

    @Test fun `every value is one a method channel carries`() {
        val b = TrainerMaps.board(places, { 30 to 3 }, { Triple(0.1f, 0.1f, 30) }, { 0.05f to 0.05f }, setOf('a'), mapOf("s>a" to 4))
        fun check(v: Any?, at: String) {
            when (v) {
                null, is String, is Int, is Long, is Double, is Boolean -> Unit
                is List<*> -> v.forEachIndexed { i, e -> check(e, "$at[$i]") }
                is Map<*, *> -> v.forEach { (k, e) -> assertTrue("$at: a key that is no string", k is String); check(e, "$at.$k") }
                else -> throw AssertionError("$at is a ${v::class.simpleName}, which a method channel does not carry")
            }
        }
        check(b, "board")
    }

    @Test fun `a run's line gives its words right first time, for the chart`() {
        val line = TrainerMaps.line(RunLine(1L, "cat-1", "The lighthouse cat", false, "normal", false, 60_000L, 43.4f, 0.957f, 45, 44, 39, 4, 2, 1))
        assertEquals(39, line["firstTry"])
        assertEquals(45, line["words"])
        assertEquals(96, line["accuracy"])
    }
}
