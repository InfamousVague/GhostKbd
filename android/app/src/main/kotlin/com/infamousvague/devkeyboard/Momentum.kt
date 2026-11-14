package com.infamousvague.devkeyboard

/**
 * A held backspace, as Settings › Typing › Held backspace has it (Kevin, 2026-11-14: "base speed slider and speed
 * ramp options like the turbo").
 *
 * - [Settings.rate]: the base speed, letters a second ([RATE_MIN] to [RATE_MAX]). Whole words come at a pace that
 *   follows it ([wordMs]): 20 a second, the keyboard's own, gives a word every 200 ms as it always has.
 * - [Settings.ramp]: how hard it speeds up from that base while held ([Ramp]): Off holds the base; Gentle, Quick and
 *   Turbo climb to 1.6, 2.5 and 4 times it, each sooner than the last. Letters and words climb alike.
 * - [Settings.letters]: how many letters it takes one by one before it moves to whole words.
 * - [Settings.rumble]: a rumble for each word, firmer as it goes, from the strength chosen.
 *
 * The defaults (20 a second, no ramp, 15 letters) are the held backspace as it was (Matt, 2026-11-10), step for step.
 * No Android in it (MomentumTest).
 */
object Momentum {
    /** One repeat of a held backspace: a word or a character, how long until the next, and the word's rumble (0..1, 0 none). */
    data class Step(val word: Boolean, val nextMs: Long, val rumble: Float)

    /** How hard a held backspace speeds up: [top] times the base at most, closing the gap by [ease] each repeat. */
    enum class Ramp(val id: String, val top: Double, val ease: Double) {
        OFF("off", 1.0, 1.0),
        GENTLE("gentle", 1.6, 0.9),
        QUICK("quick", 2.5, 0.82),
        TURBO("turbo", 4.0, 0.7);

        /** How many times the base, [n] repeats into a phase. */
        fun factor(n: Int): Double = 1 + (top - 1) * (1 - Math.pow(ease, n.toDouble()))

        companion object {
            fun of(id: String?): Ramp = entries.firstOrNull { it.id == id } ?: OFF
        }
    }

    const val RATE_MIN = 4
    const val RATE_MAX = 40
    const val RATE_DEFAULT = 20
    const val LETTERS_MIN = 0
    const val LETTERS_MAX = 40
    const val LETTERS_DEFAULT = 15

    /** The wait before the first repeat. */
    const val HOLD_MS = 400L

    /** The fastest a ramp may go: a letter every 20 ms, a word every 50. */
    const val LETTER_FLOOR_MS = 20L
    const val WORD_FLOOR_MS = 50L

    data class Settings(
        val rate: Int = RATE_DEFAULT,
        val letters: Int = LETTERS_DEFAULT,
        val ramp: Ramp = Ramp.OFF,
        val rumble: Boolean = false,
        val strength: String = "medium",
    )

    fun settings(prefs: KeyboardPrefs) = Settings(
        rate = prefs.backspaceRate.toIntOrNull()?.coerceIn(RATE_MIN, RATE_MAX) ?: RATE_DEFAULT,
        letters = prefs.backspaceLetters.toIntOrNull()?.coerceIn(LETTERS_MIN, LETTERS_MAX) ?: LETTERS_DEFAULT,
        ramp = Ramp.of(prefs.backspaceRamp),
        rumble = prefs.backspaceRumble,
        strength = prefs.backspaceRumbleStrength,
    )

    /** A word's wait at the base speed [rate]: 4000 / rate ms, kept between 80 and 500. */
    fun wordMs(rate: Int): Long = (4000L / rate.coerceAtLeast(1)).coerceIn(80L, 500L)

    /** The rumble's starting strength for each of Settings' three, and how much each word adds. */
    fun rumbleBase(strength: String?): Float = when (strength) { "light" -> 0.35f; "strong" -> 0.8f; else -> 0.55f }
    const val RUMBLE_STEP = 0.06f

    /** The [repeat]th repeat (0 is the first after [HOLD_MS]). */
    fun step(repeat: Int, s: Settings = Settings()): Step {
        val rate = s.rate.coerceIn(RATE_MIN, RATE_MAX).toDouble()
        if (repeat < s.letters) {
            val ms = Math.round(1000.0 / (rate * s.ramp.factor(repeat))).coerceAtLeast(LETTER_FLOOR_MS)
            return Step(word = false, nextMs = ms, rumble = 0f)
        }
        val w = repeat - s.letters
        val ms = Math.round(wordMs(s.rate) / s.ramp.factor(w)).coerceAtLeast(WORD_FLOOR_MS)
        val rumble = if (s.rumble) (rumbleBase(s.strength) + w * RUMBLE_STEP).coerceAtMost(1f) else 0f
        return Step(word = true, nextMs = ms, rumble = rumble)
    }
}
