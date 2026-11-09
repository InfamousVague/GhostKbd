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
    val tapLog: Boolean get() = bool(TAP_LOG)
    /** Autocorrect changes words on its own; off, it only suggests. */
    val autocorrect: Boolean get() = bool(AUTOCORRECT)
    val suggestions: Boolean get() = bool(SUGGESTIONS)

    /** The GIF plugin: a GIPHY key of the person's own, used instead of the build's. */
    val giphyKey: String get() = prefs.getString(GIPHY_KEY, "")!!

    /** The person's own long-press keys: "a=á à â" a line (Layouts.parseCustom). */
    val popupKeys: String get() = prefs.getString(POPUP_KEYS, "")!!

    /** The plugin row's icon pack (RowIcons.PACKS). */
    val iconPack: String get() = prefs.getString(ICON_PACK, "lucide")!!

    /** The dictation plugin's chosen model (WhisperModel.SPECS' ids) and the language it expects ("auto" detects). */
    val whisperModel: String get() = prefs.getString(WHISPER_MODEL, "base.en")!!
    val dictationLanguage: String get() = prefs.getString(DICTATION_LANGUAGE, "auto")!!

    /** Autocorrect's languages: codes with a dictionary in assets/dict, comma-separated ("en,es"). */
    val languages: List<String> get() = prefs.getString(LANGUAGES, DEFAULTS[LANGUAGES] as String)!!.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("en") }

    /** Words autocorrect leaves alone, one a line: added when a fix is undone, removable in Settings. */
    val learnedWords: Set<String> get() = prefs.getString(LEARNED_WORDS, "")!!.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun learn(word: String) {
        val w = word.lowercase()
        val now = learnedWords
        if (w !in now) prefs.edit().putString(LEARNED_WORDS, (now + w).sorted().joinToString("\n")).apply()
    }

    /** The added plugins' ids, comma-separated (Plugins.kt). */
    val plugins: String get() = prefs.getString(PLUGINS, DEFAULTS[PLUGINS] as String)!!

    /** The themes plugin: a preset's id, or "custom" for [customTheme]. */
    val themePreset: String get() = prefs.getString(THEME_PRESET, DEFAULTS[THEME_PRESET] as String)!!

    /** The themes plugin's own theme: "background,key,functionKey,accent,text" as #RRGGBB, comma-separated. */
    val customTheme: String get() = prefs.getString(CUSTOM_THEME, DEFAULTS[CUSTOM_THEME] as String)!!

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
        const val TAP_LOG = "tap_log"
        const val AUTOCORRECT = "autocorrect"
        const val LANGUAGES = "languages"
        const val SUGGESTIONS = "suggestions"
        const val GIPHY_KEY = "giphy_key"
        const val POPUP_KEYS = "popup_keys"
        const val WHISPER_MODEL = "whisper_model"
        const val ICON_PACK = "icon_pack"
        const val DICTATION_LANGUAGE = "dictation_language"
        const val LEARNED_WORDS = "learned_words"
        const val PLUGINS = "plugins"
        const val THEME_PRESET = "theme_preset"
        const val CUSTOM_THEME = "custom_theme"
        const val ONBOARDED = "onboarded"

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
            TAP_LOG to false,
            AUTOCORRECT to true,
            LANGUAGES to "en",
            SUGGESTIONS to true,
            GIPHY_KEY to "",
            POPUP_KEYS to "",
            WHISPER_MODEL to "base.en",
            ICON_PACK to "lucide",
            DICTATION_LANGUAGE to "auto",
            "theme_tint" to "#7CC4FA",
            "theme_accent" to "",
            "theme_mode" to "dark",
            LEARNED_WORDS to "",
            PLUGINS to "autocorrect",
            THEME_PRESET to "gboard",
            CUSTOM_THEME to "#1B1B1F,#3B3C40,#2A2B2F,#A8C7FA,#E3E3E3",
            ONBOARDED to false,
        )
    }
}
