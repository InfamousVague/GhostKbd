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
    /** Autocorrect changes words on its own; off, it only suggests. */
    val autocorrect: Boolean get() = bool(AUTOCORRECT)
    val suggestions: Boolean get() = bool(SUGGESTIONS)

    /** The GIF plugin: a GIPHY key of the person's own, used instead of the build's. */
    val giphyKey: String get() = prefs.getString(GIPHY_KEY, "")!!


    /** Markdown and terminal buttons in every app, rather than only the apps they suit (AppContext). */
    val markdownEverywhere: Boolean get() = bool(MARKDOWN_EVERYWHERE)
    val terminalEverywhere: Boolean get() = bool(TERMINAL_EVERYWHERE)

    /** Key press › Sound, without the sounds plugin: a stock sound (SoundPacks.STOCK) or "system", Android's. */
    val stockSound: String get() = prefs.getString(STOCK_SOUND, "bamboo")!!

    /** How loud key sounds play, 0 to 1 (the setting keeps a percentage). */
    val soundVolume: Float get() = (prefs.getString(SOUND_VOLUME, "35")!!.toFloatOrNull() ?: 35f).coerceIn(0f, 100f) / 100f

    /** Split the keys on a wide screen (an unfolded foldable, a tablet). */
    val split: Boolean get() = bool(SPLIT)

    /** Settings › Backlight: on (by default; never in password fields), its effect, colour, strength and mode. */
    val backlight: Boolean get() = bool(BACKLIGHT)
    val backlightEffect: String get() = prefs.getString(BACKLIGHT_EFFECT, "reactive")!!
    val backlightColour: String get() = prefs.getString(BACKLIGHT_COLOUR, "accent")!!
    val backlightStrength: String get() = prefs.getString(BACKLIGHT_STRENGTH, "medium")!!
    val backlightMode: String get() = prefs.getString(BACKLIGHT_MODE, "brighten")!!
    val backlightFade: String get() = prefs.getString(BACKLIGHT_FADE, "medium")!!

    /** The emoji plugin's skin tone (0 none, 1 light to 5 dark), and emoji offered for the word typed. */
    val emojiSkin: Int get() = prefs.getString(EMOJI_SKIN, "0")!!.toIntOrNull() ?: 0
    val emojiSuggest: Boolean get() = bool(EMOJI_SUGGEST)

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

    /** The AI Mockingbird and Ghostwriter use (AiClient.PROVIDERS' ids), and the person's own key and model for each. */
    val aiProvider: String get() = prefs.getString(AI_PROVIDER, "anthropic")!!
    fun aiKey(provider: String): String = prefs.getString("ai_key_$provider", "")!!
    fun aiModel(provider: String): String = prefs.getString("ai_model_$provider", "")!!
    /** The custom provider's API root, an OpenAI-compatible https endpoint ending in /v1. */
    val aiBaseUrl: String get() = prefs.getString(AI_BASE_URL, "")!!

    /** Mockingbird: keep samples of what is typed, and retrain the style profile by itself (at most daily). */
    val mockingbirdCollect: Boolean get() = bool(MOCKINGBIRD_COLLECT)
    val mockingbirdAutoTrain: Boolean get() = bool(MOCKINGBIRD_AUTO_TRAIN)

    /** Ghostwriter: fill a <<request>> as soon as its >> is typed; what it may read for context. */
    val ghostwriterAuto: Boolean get() = bool(GHOSTWRITER_AUTO)
    val ghostwriterMemory: Boolean get() = bool(GHOSTWRITER_MEMORY)
    val ghostwriterClipboard: Boolean get() = bool(GHOSTWRITER_CLIPBOARD)
    /** Ghostwriter may look facts up online, with the provider's own search (AiClient.SEARCHES). */
    val ghostwriterWeb: Boolean get() = bool(GHOSTWRITER_WEB)
    /** Ghostwriter searches the person's GitHub commits with their own token. */
    val ghostwriterGithub: Boolean get() = bool(GHOSTWRITER_GITHUB)
    val githubToken: String get() = prefs.getString(GITHUB_TOKEN, "")!!
    /** The AI types its answer out key by key; off, it appears at once. */
    val ghostwriterTyping: Boolean get() = bool(GHOSTWRITER_TYPING)
    /** Ghostwriter searches Ghost.md's notes: Ghost.md on this phone, else the online connector once signed in. */
    val ghostwriterNotes: Boolean get() = bool(GHOSTWRITER_NOTES)
    /** Adaptive keys: draw the heatmap of mistakes and moved aims over the keys. */
    val adaptiveMap: Boolean get() = bool(ADAPTIVE_MAP)
    /** Arrow keys pinned at the end of the row above the keys, SwiftKey's way. */
    val arrowKeys: Boolean get() = bool(ARROW_KEYS)

    /**
     * Swipe typing, the clipboard, undo and redo and emoji joined autocorrect as plugins on by default (0.8.5, Matt:
     * "enable clipboard manager and undo and redo in the default settings as well as swipe texting and emoji"). A
     * keyboard that already had its own list gets them added once; removed after that, they stay removed.
     */
    fun addNewDefaults() {
        if (prefs.getBoolean("defaults_085", false)) return
        if (prefs.contains(PLUGINS)) {
            val now = plugins.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val next = (now + listOf("swipe", "clipboard", "undo", "emoji")).distinct()
            prefs.edit().putString(PLUGINS, Plugins.ALL.filter { it in next }.joinToString(",")).apply()
        }
        prefs.edit().putBoolean("defaults_085", true).apply()
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
        const val AUTOCORRECT = "autocorrect"
        const val LANGUAGES = "languages"
        const val SUGGESTIONS = "suggestions"
        const val GIPHY_KEY = "giphy_key"
        const val WHISPER_MODEL = "whisper_model"
        const val SPLIT = "split_keyboard"
        const val BACKLIGHT = "backlight"
        const val BACKLIGHT_EFFECT = "backlight_effect"
        const val BACKLIGHT_COLOUR = "backlight_colour"
        const val BACKLIGHT_STRENGTH = "backlight_strength"
        const val BACKLIGHT_MODE = "backlight_mode"
        const val BACKLIGHT_FADE = "backlight_fade"
        /** Settings › Developer shows once the version number in About is tapped five times. */
        const val DEVELOPER = "developer"
        const val EMOJI_SKIN = "emoji_skin"
        const val EMOJI_SUGGEST = "emoji_suggest"
        const val MARKDOWN_EVERYWHERE = "markdown_everywhere"
        const val TERMINAL_EVERYWHERE = "terminal_everywhere"
        const val SOUND_VOLUME = "sound_volume"
        const val STOCK_SOUND = "stock_sound"
        const val DICTATION_LANGUAGE = "dictation_language"
        const val LEARNED_WORDS = "learned_words"
        const val PLUGINS = "plugins"
        const val THEME_PRESET = "theme_preset"
        const val CUSTOM_THEME = "custom_theme"
        const val ONBOARDED = "onboarded"
        const val AI_PROVIDER = "ai_provider"
        const val AI_BASE_URL = "ai_base_url"
        const val MOCKINGBIRD_COLLECT = "mockingbird_collect"
        const val MOCKINGBIRD_AUTO_TRAIN = "mockingbird_auto_train"
        const val GHOSTWRITER_AUTO = "ghostwriter_auto"
        const val GHOSTWRITER_MEMORY = "ghostwriter_memory"
        const val GHOSTWRITER_CLIPBOARD = "ghostwriter_clipboard"
        const val GHOSTWRITER_WEB = "ghostwriter_web"
        const val GHOSTWRITER_GITHUB = "ghostwriter_github"
        const val GITHUB_TOKEN = "github_token"
        const val GHOSTWRITER_TYPING = "ghostwriter_typing"
        const val GHOSTWRITER_NOTES = "ghostwriter_notes"
        const val ADAPTIVE_MAP = "adaptive_map"
        const val ARROW_KEYS = "arrow_keys"

        /** Every provider's key and model setting (AiClient.PROVIDERS' ids). */
        private val AI_PROVIDERS = listOf("anthropic", "openai", "gemini", "mistral", "groq", "deepseek", "openrouter", "custom")

        val DEFAULTS: Map<String, Any> = linkedMapOf(
            THEME to "dark",
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
            AUTOCORRECT to true,
            LANGUAGES to "en",
            SUGGESTIONS to true,
            GIPHY_KEY to "",
            WHISPER_MODEL to "base.en",
            SPLIT to true,
            BACKLIGHT to true,
            BACKLIGHT_EFFECT to "reactive",
            BACKLIGHT_COLOUR to "accent",
            BACKLIGHT_STRENGTH to "medium",
            BACKLIGHT_MODE to "brighten",
            BACKLIGHT_FADE to "medium",
            DEVELOPER to false,
            EMOJI_SKIN to "0",
            EMOJI_SUGGEST to true,
            MARKDOWN_EVERYWHERE to false,
            TERMINAL_EVERYWHERE to false,
            SOUND_VOLUME to "35",
            STOCK_SOUND to "bamboo",
            DICTATION_LANGUAGE to "auto",
            "theme_tint" to "#51FCFB",
            "theme_accent" to "",
            "theme_mode" to "dark",
            LEARNED_WORDS to "",
            PLUGINS to "autocorrect,swipe,clipboard,undo,emoji",
            THEME_PRESET to "ghost",
            CUSTOM_THEME to "#0A0B0C,#232528,#16181A,#51FCFB,#E8EDED",
            ONBOARDED to false,
            AI_PROVIDER to "anthropic",
            AI_BASE_URL to "",
            MOCKINGBIRD_COLLECT to true,
            MOCKINGBIRD_AUTO_TRAIN to true,
            GHOSTWRITER_AUTO to true,
            GHOSTWRITER_MEMORY to true,
            GHOSTWRITER_CLIPBOARD to false,
            GHOSTWRITER_WEB to true,
            GHOSTWRITER_GITHUB to true,
            GITHUB_TOKEN to "",
            GHOSTWRITER_TYPING to true,
            GHOSTWRITER_NOTES to true,
            ADAPTIVE_MAP to false,
            ARROW_KEYS to false,
        ) + AI_PROVIDERS.flatMap { listOf("ai_key_$it" to "", "ai_model_$it" to "") }
    }
}
