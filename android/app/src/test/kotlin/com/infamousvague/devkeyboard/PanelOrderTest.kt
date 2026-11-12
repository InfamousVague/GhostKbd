package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Where the mic key's full stop stands in its long-press panel (PanelOrder). The mic key is the last key but one of
 * the bottom row, so its panel of seventeen marks, eight across, is moved in from the screen's edge; the finger
 * chooses the cell it is under, and on the emulator a hold and a lift typed a colon, the seventh mark. The full stop
 * goes to the cell over the key.
 */
class PanelOrderTest {
    private val marks = listOf(".", "?", "!", ",", "'", "\"", ":", ";", "-", "/", "@", "#", "&", "(", ")", "%", "+")

    @Test fun `over a key in the middle of the board the first cell is the key's own`() {
        // A panel that begins over its key: the key's middle is in the first cell.
        assertEquals(0, PanelOrder.home(keyX = 400f, cellsLeft = 340f, cellWidth = 120f, columns = 8, count = 17))
        assertEquals(marks, PanelOrder.withFirstAt(marks, 0))
    }

    @Test fun `the mic key's full stop stands over the key, though the panel came in from the edge`() {
        // The emulator's numbers: a board 1248 across, cells of 124, eight of them ending at the screen's edge, and
        // the mic key's middle at 996.
        val left = 1248f - 5f - 8 * 124f
        val at = PanelOrder.home(keyX = 996f, cellsLeft = left, cellWidth = 124f, columns = 8, count = marks.size)
        assertEquals(6, at)
        val shown = PanelOrder.withFirstAt(marks, at)
        assertEquals(".", shown[at])
        // The other marks keep their order round it, and none is lost.
        assertEquals(listOf("?", "!", ",", "'", "\"", ":", ".", ";"), shown.take(8))
        assertEquals(marks.sorted(), shown.sorted())
    }

    @Test fun `the place is always a cell the first line has`() {
        // A finger off either end of the panel, and a panel of fewer choices than columns.
        assertEquals(0, PanelOrder.home(keyX = 10f, cellsLeft = 300f, cellWidth = 100f, columns = 8, count = 17))
        assertEquals(7, PanelOrder.home(keyX = 5000f, cellsLeft = 300f, cellWidth = 100f, columns = 8, count = 17))
        assertEquals(2, PanelOrder.home(keyX = 5000f, cellsLeft = 300f, cellWidth = 100f, columns = 8, count = 3))
        assertEquals(0, PanelOrder.home(keyX = 500f, cellsLeft = 300f, cellWidth = 0f, columns = 8, count = 17))
        assertEquals(listOf("a"), PanelOrder.withFirstAt(listOf("a"), 3))
        assertEquals(listOf("b", "c", "a"), PanelOrder.withFirstAt(listOf("a", "b", "c"), 9))
    }
}
