package com.infamousvague.devkeyboard

import android.content.Context
import android.content.res.Configuration

/** The keyboard's colours, after Gboard's default (non dynamic-colour) light and dark themes. */
data class KeyboardTheme(
    val background: Int,
    val key: Int,
    val keyPressed: Int,
    val functionKey: Int,
    val functionKeyPressed: Int,
    val accent: Int,
    val accentPressed: Int,
    val onAccent: Int,
    val text: Int,
    val hint: Int,
    val popup: Int,
    val popupSelected: Int,
    val onPopupSelected: Int,
    val popupShadow: Int,
    val toolbarIcon: Int,
    val dark: Boolean,
    /** Keys drawn as raised mechanical keycaps that sink when pressed (the Retro preset). */
    val mechanical: Boolean = false,
) {
    companion object {
        val LIGHT = KeyboardTheme(
            background = 0xFFE8EAF0.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFD5D9E0.toInt(),
            functionKey = 0xFFCDD3DD.toInt(),
            functionKeyPressed = 0xFFB4BBC7.toInt(),
            accent = 0xFF0B57D0.toInt(),
            accentPressed = 0xFF0842A0.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            text = 0xFF1F1F1F.toInt(),
            hint = 0xFF5F6368.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            popupSelected = 0xFF0B57D0.toInt(),
            onPopupSelected = 0xFFFFFFFF.toInt(),
            popupShadow = 0x33000000,
            toolbarIcon = 0xFF444746.toInt(),
            dark = false,
        )

        val DARK = KeyboardTheme(
            background = 0xFF1B1B1F.toInt(),
            key = 0xFF3B3C40.toInt(),
            keyPressed = 0xFF55575C.toInt(),
            functionKey = 0xFF2A2B2F.toInt(),
            functionKeyPressed = 0xFF44464B.toInt(),
            accent = 0xFFA8C7FA.toInt(),
            accentPressed = 0xFF8AB4F8.toInt(),
            onAccent = 0xFF062E6F.toInt(),
            text = 0xFFE3E3E3.toInt(),
            hint = 0xFF9AA0A6.toInt(),
            popup = 0xFF46474C.toInt(),
            popupSelected = 0xFFA8C7FA.toInt(),
            onPopupSelected = 0xFF062E6F.toInt(),
            popupShadow = 0x66000000,
            toolbarIcon = 0xFFC4C7C5.toInt(),
            dark = true,
        )

        /** The theme the settings ask for: the themes plugin's preset or own theme where it is added, else Gboard's. */
        fun resolve(context: Context, prefs: KeyboardPrefs): KeyboardTheme {
            if (Plugins.isOn(prefs, Plugins.THEMES)) {
                when (val preset = prefs.themePreset) {
                    "gboard" -> Unit
                    "custom" -> parseCustom(prefs.customTheme)?.let { return it }
                    else -> PRESETS[preset]?.let { return it }
                }
            }
            return when (prefs.theme) {
                "light" -> LIGHT
                "dark" -> DARK
                else -> {
                    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                    if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
                }
            }
        }

        /** A whole theme from five colours: the keyboard's ground, its keys, its function keys, the accent, the ink. */
        fun fromBase(background: Int, key: Int, functionKey: Int, accent: Int, text: Int): KeyboardTheme {
            val dark = luminance(background) < 0.4
            return KeyboardTheme(
                background = background,
                key = key,
                keyPressed = mix(key, text, 0.18f),
                functionKey = functionKey,
                functionKeyPressed = mix(functionKey, text, 0.18f),
                accent = accent,
                accentPressed = mix(accent, if (dark) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), 0.15f),
                onAccent = if (luminance(accent) > 0.5) 0xFF111111.toInt() else 0xFFFFFFFF.toInt(),
                text = text,
                hint = mix(text, background, 0.45f),
                popup = mix(key, text, if (dark) 0.08f else 0f),
                popupSelected = accent,
                onPopupSelected = if (luminance(accent) > 0.5) 0xFF111111.toInt() else 0xFFFFFFFF.toInt(),
                popupShadow = if (dark) 0x66000000 else 0x33000000,
                toolbarIcon = mix(text, background, 0.2f),
                dark = dark,
            )
        }

        /** "#RRGGBB,#RRGGBB,..." in [fromBase]'s order, or null where it does not read. */
        fun parseCustom(spec: String): KeyboardTheme? {
            val c = spec.split(',').mapNotNull { runCatching { 0xFF000000.toInt() or Integer.parseInt(it.trim().removePrefix("#"), 16) }.getOrNull() }
            return if (c.size == 5) fromBase(c[0], c[1], c[2], c[3], c[4]) else null
        }

        /** The themes plugin's presets, by id; the settings app lists the same ids with their names (lib/plugins). */
        val PRESETS: Map<String, KeyboardTheme> = mapOf(
            "glacier" to fromBase(0xFF0E1621.toInt(), 0xFF1D2A3A.toInt(), 0xFF152030.toInt(), 0xFF7CC4FA.toInt(), 0xFFE6F1FB.toInt()),
            "midnight" to fromBase(0xFF000000.toInt(), 0xFF1A1A1A.toInt(), 0xFF0D0D0D.toInt(), 0xFF8AB4F8.toInt(), 0xFFFFFFFF.toInt()),
            "dracula" to fromBase(0xFF21222C.toInt(), 0xFF343746.toInt(), 0xFF282A36.toInt(), 0xFFBD93F9.toInt(), 0xFFF8F8F2.toInt()),
            "nord" to fromBase(0xFF2E3440.toInt(), 0xFF434C5E.toInt(), 0xFF3B4252.toInt(), 0xFF88C0D0.toInt(), 0xFFECEFF4.toInt()),
            "solarized" to fromBase(0xFF002B36.toInt(), 0xFF0E4252.toInt(), 0xFF073642.toInt(), 0xFFB58900.toInt(), 0xFFEEE8D5.toInt()),
            "monokai" to fromBase(0xFF1E1F1C.toInt(), 0xFF3E3D32.toInt(), 0xFF272822.toInt(), 0xFFA6E22E.toInt(), 0xFFF8F8F2.toInt()),
            "github" to fromBase(0xFFF6F8FA.toInt(), 0xFFFFFFFF.toInt(), 0xFFD0D7DE.toInt(), 0xFF0969DA.toInt(), 0xFF1F2328.toInt()),
            "paper" to fromBase(0xFFF4EFE6.toInt(), 0xFFFFFCF6.toInt(), 0xFFE4DCCD.toInt(), 0xFFB5522B.toInt(), 0xFF2B2622.toInt()),
            // A beige mechanical board: cream caps, grey modifiers, a red-orange enter, on a dark case.
            "retro" to fromBase(0xFF34302B.toInt(), 0xFFE9E1CD.toInt(), 0xFFA8A190.toInt(), 0xFFD9542B.toInt(), 0xFF2E2A24.toInt())
                .copy(hint = 0xFF7A715F.toInt(), toolbarIcon = 0xFFD8CFBB.toInt(), mechanical = true),
        )

        private fun luminance(c: Int): Double {
            fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
        }

        fun mix(a: Int, b: Int, t: Float): Int {
            fun lerp(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (lerp((a shr 16) and 0xFF, (b shr 16) and 0xFF) shl 16) or
                (lerp((a shr 8) and 0xFF, (b shr 8) and 0xFF) shl 8) or lerp(a and 0xFF, b and 0xFF)
        }
    }
}
