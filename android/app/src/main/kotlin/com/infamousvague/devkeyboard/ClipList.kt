package com.infamousvague.devkeyboard

/**
 * The clipboard history's list and its rules, with no Android in it, so they can be read and tested off the phone
 * (ClipListTest). [ClipHistory] keeps the list and reads the clipboard; this says what a copy, a pin and a clear do
 * to it.
 *
 * Pins (Matt, 2026-11-12, choosing from a list of plugins to add: "I like … clipboard pins"): a clip that is pinned
 * stays. It leads the list, it is not one of the [max] copies the history holds, a newer copy never pushes it out,
 * and clearing the history leaves it where it is. Only unpinning it, or removing it, lets it go.
 */
object ClipList {
    /** One copy: its [text], when it was copied (or pinned by hand in Settings) and whether it is [pinned]. */
    data class Clip(val text: String, val at: Long, val pinned: Boolean = false)

    /** The most that can be pinned: a row to pick from, not a second history. */
    const val MAX_PINS = 25

    /** As the row and Settings list them: the pinned first, then the rest, each newest first. */
    fun shown(list: List<Clip>): List<Clip> =
        list.filter { it.pinned }.sortedByDescending { it.at } + list.filter { !it.pinned }.sortedByDescending { it.at }

    /** Newest first whatever is pinned: what "my recent clipboard" means to Ghostwriter. */
    fun recent(list: List<Clip>): List<Clip> = list.sortedByDescending { it.at }

    /**
     * [text] was copied at [now]: it is the newest clip. Copied before, it is the same clip made new, and stays
     * pinned if it was. No more than [max] unpinned clips are kept, the oldest going first.
     */
    fun copied(list: List<Clip>, text: String, now: Long, max: Int): List<Clip> {
        val was = list.firstOrNull { it.text == text }
        return capped(listOf(Clip(text, now, was?.pinned == true)) + list.filter { it.text != text }, max)
    }

    /** [list] with no more than [max] unpinned clips: the oldest go first, and a pinned clip is never one of them. */
    fun capped(list: List<Clip>, max: Int): List<Clip> {
        val kept = list.filter { !it.pinned }.sortedByDescending { it.at }.take(max.coerceAtLeast(0))
        return list.filter { it.pinned || it in kept }
    }

    /**
     * [text] pinned ([on]) or unpinned. Null where a pin is refused: there are [MAX_PINS] already. An unpinned clip
     * goes back among the copies, where it is as old as it is: with [max] newer ones there it is let go.
     */
    fun pinned(list: List<Clip>, text: String, on: Boolean, max: Int): List<Clip>? {
        val clip = list.firstOrNull { it.text == text } ?: return list
        if (clip.pinned == on) return list
        if (on && list.count { it.pinned } >= MAX_PINS) return null
        return capped(list.map { if (it.text == text) it.copy(pinned = on) else it }, max)
    }

    /**
     * [text] pinned from Settings, where a pin can be written by hand: a clip of its own at [now] if it is not in the
     * list, the same clip pinned if it is. Null where there are [MAX_PINS] already.
     */
    fun added(list: List<Clip>, text: String, now: Long, max: Int): List<Clip>? =
        pinned(if (list.any { it.text == text }) list else listOf(Clip(text, now)) + list, text, true, max)

    /** The history cleared: every copy goes, and what is pinned stays. */
    fun cleared(list: List<Clip>): List<Clip> = list.filter { it.pinned }

    /** [text] removed, pinned or not. */
    fun removed(list: List<Clip>, text: String): List<Clip> = list.filter { it.text != text }
}
