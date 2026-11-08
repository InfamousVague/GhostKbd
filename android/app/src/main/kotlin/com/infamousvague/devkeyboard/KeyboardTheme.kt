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

        /** The theme the setting asks for; "system" follows the phone's dark mode. */
        fun resolve(context: Context, setting: String): KeyboardTheme = when (setting) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> {
                val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
            }
        }
    }
}
