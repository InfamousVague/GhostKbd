package com.infamousvague.devkeyboard

import kotlin.math.floor

/**
 * Where a long-press panel's first choice stands. A panel is a grid of cells over its key, its first line nearest
 * the key, and the finger chooses whichever cell it is under. Over a key in the middle of the board the first cell is
 * over the key; over one near the screen's edge the panel is moved in to fit, and the cell over the key is some other.
 * For the mic key, whose full stop is "a hold and a lift" (Layouts.withMic), the full stop is put in that cell.
 *
 * No Android in it (PanelOrderTest).
 */
object PanelOrder {
    /**
     * The column over a key whose middle is at [keyX], in a panel whose first cell begins at [cellsLeft], each cell
     * [cellWidth] wide, [columns] across, with [count] choices: never a cell the first line does not have.
     */
    fun home(keyX: Float, cellsLeft: Float, cellWidth: Float, columns: Int, count: Int): Int {
        val last = minOf(columns, count) - 1
        if (cellWidth <= 0f || last <= 0) return 0
        return floor((keyX - cellsLeft) / cellWidth).toInt().coerceIn(0, last)
    }

    /** [choices] with the first of them moved to place [at]: the others keep their order around it. */
    fun <T> withFirstAt(choices: List<T>, at: Int): List<T> {
        if (at <= 0 || choices.size < 2) return choices
        val out = choices.toMutableList()
        val first = out.removeAt(0)
        out.add(at.coerceAtMost(out.size), first)
        return out
    }
}
