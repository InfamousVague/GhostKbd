package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The code finder against its table of messages, and the holder's rules: how long a code is kept, and that using it lets it go. */
class OtpCodesTest {
    @Test fun `every message of the table gives its code, or none`() {
        val wrong = OtpCodesTable.MESSAGES.mapNotNull { (message, code) ->
            val found = OtpCodes.find(message)
            if (found == code) null else "${message.take(70).replace('\n', ' ')} -> $found, not $code"
        }
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }

    @Test fun `the table has codes and messages without one`() {
        assertTrue(OtpCodesTable.MESSAGES.count { it.second != null } >= 50)
        assertTrue(OtpCodesTable.MESSAGES.count { it.second == null } >= 25)
    }

    @Test fun `a code is held from its text, fresh for three minutes and gone at ten`() {
        SmsCodes.clear()
        val code = SmsCodes.take("Your code is 482913", 1_000)!!
        assertEquals("482913", code.text)
        assertSame(code, SmsCodes.fresh(1_000))
        assertSame(code, SmsCodes.fresh(1_000 + SmsCodes.FRESH_MS - 1))
        assertNull(SmsCodes.fresh(1_000 + SmsCodes.FRESH_MS))
        assertEquals(listOf(code), SmsCodes.live(1_000 + SmsCodes.KEEP_MS - 1))
        assertTrue(SmsCodes.live(1_000 + SmsCodes.KEEP_MS).isEmpty())
        SmsCodes.clear()
    }

    @Test fun `a text without a code holds nothing`() {
        SmsCodes.clear()
        assertNull(SmsCodes.take("Running late, there in ten", 1_000))
        assertNull(SmsCodes.take("Your order #483920 has shipped", 1_000))
        assertTrue(SmsCodes.live(1_000).isEmpty())
    }

    @Test fun `a typed code is let go, and the others stay`() {
        SmsCodes.clear()
        val first = SmsCodes.take("Your code is 482913", 1_000)!!
        val second = SmsCodes.take("Your code is 771204", 2_000)!!
        assertEquals(listOf("771204", "482913"), SmsCodes.live(3_000).map { it.text })
        assertSame(second, SmsCodes.fresh(3_000))
        SmsCodes.used(second)
        assertEquals(listOf("482913"), SmsCodes.live(3_000).map { it.text })
        SmsCodes.used(first)
        assertTrue(SmsCodes.live(3_000).isEmpty())
    }

    @Test fun `the same code sent again is held once, from its newest text`() {
        SmsCodes.clear()
        SmsCodes.take("Your code is 482913", 1_000)
        SmsCodes.take("Reminder: your code is 482913", 60_000)
        val live = SmsCodes.live(61_000)
        assertEquals(1, live.size)
        assertEquals(60_000L, live.single().at)
        SmsCodes.clear()
    }

    @Test fun `only the newest few are held`() {
        SmsCodes.clear()
        for (i in 0 until SmsCodes.MAX + 2) SmsCodes.take("Your code is 48291$i", 1_000L + i)
        assertEquals(SmsCodes.MAX, SmsCodes.live(2_000).size)
        assertEquals("48291${SmsCodes.MAX + 1}", SmsCodes.live(2_000).first().text)
        SmsCodes.clear()
    }

    @Test fun `listeners hear a code arrive and go`() {
        SmsCodes.clear()
        var heard = 0
        val listener = { heard++; Unit }
        SmsCodes.addListener(listener)
        val code = SmsCodes.take("Your code is 482913", 1_000)!!
        SmsCodes.take("See you at eight", 1_500)
        SmsCodes.used(code)
        SmsCodes.removeListener(listener)
        SmsCodes.take("Your code is 771204", 2_000)
        assertEquals(2, heard)
        SmsCodes.clear()
    }
}
