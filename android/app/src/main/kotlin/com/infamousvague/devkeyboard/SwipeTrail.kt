package com.infamousvague.devkeyboard

/**
 * Swipe typing's trail colours: gradients from the trail's tail to the finger. The settings app lists the same ids,
 * names and colours (lib/plugins/catalogue.dart `swipeTrails`).
 */
object SwipeTrail {
    val PRESETS: Map<String, IntArray> = linkedMapOf(
        "aurora" to intArrayOf(0xFF22D3EE.toInt(), 0xFF818CF8.toInt(), 0xFFE879F9.toInt()),
        "sunset" to intArrayOf(0xFFFBBF24.toInt(), 0xFFF97316.toInt(), 0xFFEC4899.toInt(), 0xFF8B5CF6.toInt()),
        "ocean" to intArrayOf(0xFF0EA5E9.toInt(), 0xFF14B8A6.toInt(), 0xFF22C55E.toInt()),
        "neon" to intArrayOf(0xFF39FF14.toInt(), 0xFF00E5FF.toInt(), 0xFFFF00E5.toInt()),
        "fire" to intArrayOf(0xFFB91C1C.toInt(), 0xFFF97316.toInt(), 0xFFFDE047.toInt()),
        "rainbow" to intArrayOf(0xFFEF4444.toInt(), 0xFFF59E0B.toInt(), 0xFF22C55E.toInt(), 0xFF3B82F6.toInt(), 0xFFA855F7.toInt()),
        "mono" to intArrayOf(0xFF9CA3AF.toInt(), 0xFFFFFFFF.toInt()),
    )

    /** The trail's colours: a preset's, or for "theme" the accent alone. */
    fun colours(id: String, theme: KeyboardTheme): IntArray = PRESETS[id] ?: intArrayOf(theme.accent, theme.accent)

    /** The colour [f] (0 at the tail, 1 at the finger) of the way along [colours]. */
    fun at(colours: IntArray, f: Float): Int {
        if (colours.size == 1) return colours[0]
        val x = f.coerceIn(0f, 1f) * (colours.size - 1)
        val i = x.toInt().coerceAtMost(colours.size - 2)
        return KeyboardTheme.mix(colours[i], colours[i + 1], x - i)
    }
}
