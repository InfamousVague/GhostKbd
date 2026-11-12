package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard's own timers (Perf.kt): off they cost nothing and keep nothing, on they keep each name's count, sum, middle and longest. */
class PerfTest {
    @Test fun `off, the work is done and nothing is kept`() {
        Perf.on = false
        Perf.reset()
        assertEquals(7, Perf.time("sum") { 3 + 4 })
        assertEquals("", Perf.report())
    }

    @Test fun `on, each name keeps how often, how long in all, the middle one and the longest`() {
        Perf.reset()
        // 1 to 100 ms, each once, in no order.
        for (ms in (1..100).shuffled(java.util.Random(7))) Perf.took("draw", ms * 1_000_000L)
        Perf.took("tick", 2_500_000L)
        val lines = Perf.report().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("draw"))
        assertTrue(lines[0], "n=100 " in lines[0] && "total=5050.0ms" in lines[0] && "mid=50000µs" in lines[0] && "p95=95000µs" in lines[0] && "max=100000µs" in lines[0])
        assertTrue(lines[1], "n=1 " in lines[1] && "total=2.5ms" in lines[1] && "max=2500µs" in lines[1])
        Perf.reset()
        assertEquals("", Perf.report())
    }

    @Test fun `timed work that throws is still counted, and still throws`() {
        Perf.reset()
        Perf.on = true
        try {
            runCatching { Perf.time("bad") { error("no") } }.also { assertTrue(it.isFailure) }
            assertTrue(Perf.report().startsWith("bad"))
        } finally {
            Perf.on = false
            Perf.reset()
        }
    }
}
