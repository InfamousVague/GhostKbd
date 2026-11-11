package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a one-time code's chip writes the code, and how much of its edge is left as its time passes (CodeChip.kt). */
class CodeLookTest {
    @Test fun `six, eight and nine digits are drawn in the groups they are read in`() {
        assertEquals(listOf("482", "913"), CodeLook.groups("482913"))
        assertEquals(listOf("4829", "1375"), CodeLook.groups("48291375"))
        assertEquals(listOf("482", "913", "750"), CodeLook.groups("482913750"))
    }

    @Test fun `any other length is left whole`() {
        for (code in listOf("4829", "48291", "4829137", "4829137501")) assertEquals(listOf(code), CodeLook.groups(code))
        assertEquals(listOf(""), CodeLook.groups(""))
    }

    @Test fun `a code with a letter in it is left whole`() {
        for (code in listOf("A1B2C3", "G7K9PQ2X", "48291A", "482-913")) assertEquals(listOf(code), CodeLook.groups(code))
    }

    @Test fun `the groups are the code and nothing else`() {
        for (code in listOf("482913", "48291375", "482913750", "0000", "A1B2C3")) assertEquals(code, CodeLook.groups(code).joinToString(""))
    }

    @Test fun `the edge is whole as the code arrives and gone when its time is over`() {
        val at = 5_000_000L
        val span = SmsCodes.FRESH_MS
        assertEquals(1f, CodeLook.left(at, at, span), 0f)
        assertEquals(0.5f, CodeLook.left(at + span / 2, at, span), 0.001f)
        assertEquals(0.25f, CodeLook.left(at + span * 3 / 4, at, span), 0.001f)
        assertEquals(0f, CodeLook.left(at + span, at, span), 0f)
        assertEquals(0f, CodeLook.left(at + span * 4, at, span), 0f)
    }

    @Test fun `a clock read before the code came, or no time at all, is not drawn past the edge`() {
        assertEquals(1f, CodeLook.left(900L, 1_000L, 60_000L), 0f)
        assertEquals(0f, CodeLook.left(1_000L, 1_000L, 0L), 0f)
        assertEquals(0f, CodeLook.left(1_000L, 1_000L, -5L), 0f)
    }

    @Test fun `the same code has more of its edge left in the clipboard's list than in the row`() {
        val at = 1_000L
        val now = at + 120_000L
        assertTrue(CodeLook.left(now, at, SmsCodes.KEEP_MS) > CodeLook.left(now, at, SmsCodes.FRESH_MS))
    }

    @Test fun `a screen reader is given the code a character at a time`() {
        assertEquals("4 8 2 9 1 3", CodeLook.spoken("482913"))
        assertEquals("A 1 B 2", CodeLook.spoken("A1B2"))
        assertEquals("", CodeLook.spoken(""))
    }
}
