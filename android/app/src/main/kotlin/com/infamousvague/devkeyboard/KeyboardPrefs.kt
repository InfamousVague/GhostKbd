package com.infamousvague.devkeyboard

import android.content.Context
import android.content.SharedPreferences

/**
 * The keyboard's settings, one SharedPreferences file shared by the settings app (through MainActivity's
 * channel) and the input method service. Every key and default lives here, so the two sides cannot drift.
 */
class KeyboardPrefs(context: Context) {
    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** "system", "light" or "dark". */
    val theme: String get() = prefs.getString(THEME, DEFAULTS[THEME] as String)!!
    val numberRow: Boolean get() = bool(NUMBER_ROW)
    val keyPopup: Boolean get() = bool(KEY_POPUP)
    val sound: Boolean get() = bool(SOUND)
    val vibrate: Boolean get() = bool(VIBRATE)
    val hints: Boolean get() = bool(HINTS)
    val autoCaps: Boolean get() = bool(AUTO_CAPS)
    val doubleSpacePeriod: Boolean get() = bool(DOUBLE_SPACE_PERIOD)
    val spaceCursor: Boolean get() = bool(SPACE_CURSOR)
    val autoUpdate: Boolean get() = bool(AUTO_UPDATE)
    val keyBorders: Boolean get() = bool(KEY_BORDERS)
    val toolbar: Boolean get() = bool(TOOLBAR)

    /** "light", "medium" or "strong". */
    val haptics: String get() = prefs.getString(HAPTICS, DEFAULTS[HAPTICS] as String)!!

    /** "short", "normal" or "tall". */
    val keyHeight: String get() = prefs.getString(KEY_HEIGHT, DEFAULTS[KEY_HEIGHT] as String)!!

    /** "fast", "normal" or "slow". */
    val longPress: String get() = prefs.getString(LONG_PRESS, DEFAULTS[LONG_PRESS] as String)!!

    val longPressMs: Long
        get() = when (longPress) {
            "fast" -> 200L
            "slow" -> 500L
            else -> 300L
        }

    private fun bool(key: String) = prefs.getBoolean(key, DEFAULTS[key] as Boolean)

    /** Every setting with its current value, defaults filled in: what the settings app reads. */
    fun all(): Map<String, Any> = DEFAULTS.mapValues { (key, default) ->
        when (default) {
            is Boolean -> prefs.getBoolean(key, default)
            is String -> prefs.getString(key, default)!!
            else -> default
        }
    }

    /** Writes one setting; refuses keys and types it does not know. */
    fun set(key: String, value: Any?): Boolean {
        val default = DEFAULTS[key] ?: return false
        val edit = prefs.edit()
        when {
            default is Boolean && value is Boolean -> edit.putBoolean(key, value)
            default is String && value is String -> edit.putString(key, value)
            else -> return false
        }
        edit.apply()
        return true
    }

    companion object {
        const val FILE = "dev_keyboard"

        const val THEME = "theme"
        const val NUMBER_ROW = "number_row"
        const val KEY_POPUP = "key_popup"
        const val SOUND = "sound"
        const val VIBRATE = "vibrate"
        const val HINTS = "hints"
        const val AUTO_CAPS = "auto_caps"
        const val DOUBLE_SPACE_PERIOD = "double_space_period"
        const val SPACE_CURSOR = "space_cursor"
        const val KEY_HEIGHT = "key_height"
        const val LONG_PRESS = "long_press"
        const val AUTO_UPDATE = "auto_update"
        const val KEY_BORDERS = "key_borders"
        const val TOOLBAR = "toolbar"
        const val HAPTICS = "haptics"

        val DEFAULTS: Map<String, Any> = linkedMapOf(
            THEME to "system",
            NUMBER_ROW to false,
            KEY_POPUP to true,
            SOUND to false,
            VIBRATE to true,
            HINTS to true,
            AUTO_CAPS to true,
            DOUBLE_SPACE_PERIOD to true,
            SPACE_CURSOR to true,
            KEY_HEIGHT to "normal",
            LONG_PRESS to "normal",
            AUTO_UPDATE to true,
            KEY_BORDERS to false,
            TOOLBAR to false,
            HAPTICS to "medium",
        )
    }
}
