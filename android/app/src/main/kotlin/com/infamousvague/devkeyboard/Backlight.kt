package com.infamousvague.devkeyboard

import android.graphics.Color
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The backlight plugin: the keys' legends lit from behind, as shine-through keycaps on RGB mechanical keyboards are,
 * reacting as you type. Only the legends change (KeyboardView.legendLight), never the keys themselves.
 *
 * - reactive: the key pressed glows and fades over half a second, the classic per-key light.
 * - ripple: a ring of light runs out from each key pressed and dims as it goes.
 * - heatmap: keys warm with use and cool over a quarter of a minute when left.
 * - rainbow: a slow hue wave across the board; a key pressed flares.
 * - starlight: keys twinkle at random; a key pressed flares.
 * - breathe: the board breathes softly; a key pressed flares.
 *
 * It brightens the legends (blending in the theme's primary colour, a fixed colour or a rainbow) or dims them (fading
 * their opacity); the strength is how far.
 * KeyboardView asks for each key's [light] while drawing, and keeps drawing while [animating].
 */
class Backlight(val effect: String, val colour: String, val strength: Float, val mode: String = "brighten") {
    /** Dim: the effect darkens keys (legends too) rather than lighting them. */
    val dims get() = mode == "dim"

    private val presses = HashMap<String, Long>()
    private val ripples = ArrayDeque<Triple<Float, Float, Long>>()
    private val heat = HashMap<String, Float>()
    private var heatAt = 0L

    fun press(id: String, x: Float, y: Float, now: Long) {
        presses[id] = now
        if (effect == "ripple") {
            ripples.addLast(Triple(x, y, now))
            while (ripples.size > 12) ripples.removeFirst()
        }
        if (effect == "heatmap") {
            cool(now)
            heat[id] = (heat[id] ?: 0f) + 1f
        }
    }

    private fun cool(now: Long) {
        if (heatAt != 0L) {
            val k = exp(-(now - heatAt) / 15_000.0).toFloat()
            val it = heat.entries.iterator()
            while (it.hasNext()) { val e = it.next(); e.setValue(e.value * k); if (e.value < 0.02f) it.remove() }
        }
        heatAt = now
    }

    /** How lit the key [id] at (x, y) is, 0 to 1, on a board [width] wide. */
    fun light(id: String, x: Float, y: Float, width: Float, now: Long): Float {
        val since = presses[id]?.let { now - it } ?: Long.MAX_VALUE
        val flare = if (since < FADE_MS) (1f - since / FADE_MS.toFloat()).let { it * it } else 0f
        return when (effect) {
            "ripple" -> {
                var sum = flare
                val ring = width * 0.07f
                for ((rx, ry, t) in ripples) {
                    val age = (now - t) / 1000f
                    if (age > RIPPLE_S) continue
                    val r = age * width * 0.9f
                    val d = hypot(x - rx, y - ry)
                    val off = (d - r) / ring
                    sum += exp(-off * off) * (1f - age / RIPPLE_S)
                }
                sum.coerceAtMost(1f)
            }
            "heatmap" -> {
                val max = heat.values.maxOrNull() ?: 0f
                val h = heat[id] ?: 0f
                maxOf(if (max > 0f) h / max * 0.85f else 0f, flare * 0.6f)
            }
            "rainbow" -> maxOf(0.38f, flare)
            "starlight" -> {
                val seed = (id.hashCode() and 0xffff) / 6553.6
                val twinkle = sin(now / 650.0 + seed * 2.7).toFloat()
                maxOf(if (twinkle > 0.86f) (twinkle - 0.86f) / 0.14f * 0.7f else 0f, flare)
            }
            "breathe" -> maxOf(0.12f + 0.14f * (0.5f + 0.5f * sin(now / 1300.0).toFloat()), flare)
            else -> flare
        }
    }

    /** The light's colour at (x, y): the accent, a fixed colour, or (rainbow) a hue across the board and time. */
    fun colourAt(x: Float, width: Float, now: Long, accent: Int, heatLevel: Float = 0f): Int = when {
        colour == "rainbow" || effect == "rainbow" -> {
            val hue = ((x / width) * 300f + now / 25f) % 360f
            Color.HSVToColor(floatArrayOf(hue, 0.75f, 1f))
        }
        effect == "heatmap" && colour == "accent" -> {
            // Cool blue to hot orange as a key warms.
            Color.HSVToColor(floatArrayOf(210f - 190f * heatLevel, 0.8f, 1f))
        }
        else -> COLOURS[colour] ?: accent
    }

    /** Whether anything is still moving, so the keyboard keeps drawing frames. */
    fun animating(now: Long): Boolean = when (effect) {
        "rainbow", "starlight", "breathe" -> true
        "ripple" -> ripples.any { now - it.third < RIPPLE_S * 1000 } || presses.values.any { now - it < FADE_MS }
        "heatmap" -> heat.isNotEmpty() || presses.values.any { now - it < FADE_MS }
        else -> presses.values.any { now - it < FADE_MS }
    }

    /** Effects that only change slowly can draw at a gentle rate (ms between frames), saving battery. */
    fun frameDelay(): Long = when (effect) { "heatmap" -> 120L; "breathe" -> 50L; else -> 16L }

    companion object {
        const val FADE_MS = 550L
        const val RIPPLE_S = 0.9f
        /** The fixed colours, by id; the settings app lists the same (catalogue.dart backlightColours). */
        val COLOURS = mapOf(
            "ghost" to 0xFF51FCFB.toInt(), "ice" to 0xFF7FD8FF.toInt(), "phosphor" to 0xFF39FF88.toInt(),
            "warm" to 0xFFFF8A3D.toInt(), "violet" to 0xFFB47CFF.toInt(), "rose" to 0xFFFF5C8A.toInt(), "white" to 0xFFFFFFFF.toInt(),
        )
        fun strengthOf(id: String) = when (id) { "low" -> 0.35f; "high" -> 0.9f; else -> 0.6f }
    }
}
