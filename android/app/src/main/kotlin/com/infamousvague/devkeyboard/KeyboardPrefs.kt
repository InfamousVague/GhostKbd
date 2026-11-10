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
    /**
     * Settings › Offline: Ghost Keyboard opens no connection at all while this is on. [Net] refuses every one, and the
     * keyboard's row says so where it would have offered something that needs the network.
     */
    val offline: Boolean get() = bool(OFFLINE)
    val keyBorders: Boolean get() = bool(KEY_BORDERS)
    val toolbar: Boolean get() = bool(TOOLBAR)
    /** Autocorrect changes words on its own; off, it only suggests. */
    val autocorrect: Boolean get() = bool(AUTOCORRECT)
    val suggestions: Boolean get() = bool(SUGGESTIONS)

    /**
     * Settings › Autocorrect › Suggest the next word: after a space the row offers the three likeliest next words,
     * and the word before helps rank completions and choose between corrections (NextWords.kt). Off, the row,
     * completions and corrections are exactly as they were before there was prediction.
     */
    val nextWord: Boolean get() = bool(NEXT_WORD)

    /**
     * Settings › Autocorrect › Learn from what I type: word pairs are counted on this phone as they are typed (never
     * in password fields, while incognito, or where a field asks keyboards not to learn). Off, nothing more is
     * counted; what was learned already is still used until it is forgotten in Settings.
     */
    val nextWordLearn: Boolean get() = bool(NEXT_WORD_LEARN)

    /** The GIF plugin: a GIPHY key of the person's own, used instead of the build's. */
    val giphyKey: String get() = prefs.getString(GIPHY_KEY, "")!!


    /** Markdown and terminal buttons in every app, rather than only the apps they suit (AppContext). */
    val markdownEverywhere: Boolean get() = bool(MARKDOWN_EVERYWHERE)
    val terminalEverywhere: Boolean get() = bool(TERMINAL_EVERYWHERE)

    /** Key press › Sound, without the sounds plugin: a stock sound (SoundPacks.STOCK) or "system", Android's. */
    val stockSound: String get() = prefs.getString(STOCK_SOUND, "bamboo")!!

    /** How loud key sounds play, 0 to 1 (the setting keeps a percentage). */
    val soundVolume: Float get() = (prefs.getString(SOUND_VOLUME, "35")!!.toFloatOrNull() ?: 35f).coerceIn(0f, 100f) / 100f

    /** Split the keys on a wide screen (an unfolded foldable, a tablet): the old switch, read where [wideLayout] was never chosen. */
    val split: Boolean get() = bool(SPLIT)

    /**
     * Foldable layouts (0.9.0): the keys on a wide screen (an unfolded foldable, a tablet). "split" (two halves, a
     * phone's key size each), "full" (edge to edge), or a phone-sized board docked "left", "center" or "right" for one
     * thumb. A keyboard set up before 0.9.0 keeps what its Split switch said.
     */
    val wideLayout: String get() = prefs.getString(WIDE_LAYOUT, null) ?: if (split) "split" else "full"

    /** One-handed typing on a phone: "off", or the keys narrowed toward the "left" or "right" thumb. */
    val oneHanded: String get() = prefs.getString(ONE_HANDED, DEFAULTS[ONE_HANDED] as String)!!

    /** Cursor control: sliding left from backspace deletes a word at a time. */
    val deleteSwipe: Boolean get() = bool(DELETE_SWIPE)

    /** Typing stats: speed and accuracy counted on this phone (TypingStats.kt). */
    val typingStats: Boolean get() = bool(TYPING_STATS)

    /** The Rewrite plugin: the language its Translate goes to, as the person wrote it. */
    val rewriteLanguage: String get() = prefs.getString(REWRITE_LANGUAGE, DEFAULTS[REWRITE_LANGUAGE] as String)!!

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

    /**
     * The languages that are on: codes from Languages.ALL, comma-separated, in the order they were turned on
     * ("en,pl,ru"). Codes this keyboard does not have are dropped, at most Languages.MAX count, and never none.
     */
    val languages: List<String> get() = Languages.clean(prefs.getString(LANGUAGES, DEFAULTS[LANGUAGES] as String)!!.split(','))

    /** The layout chosen for a language in place of its own: "fr:qwerty,de:qwerty" as a map. */
    val languageLayouts: Map<String, String> get() = Languages.overrides(prefs.getString(LANGUAGE_LAYOUTS, "")!!)

    /** Settings › Languages › Globe key beside space: shown once two layouts are on. */
    val globeKey: Boolean get() = bool(GLOBE_KEY)

    /**
     * The languages and the layout being typed on, worked out from `languages`, `language_layouts` and `layout` (the
     * active layout's id; empty or no longer on, the first one). With [latinField] a board that is not Latin steps
     * aside for the first Latin one (a password, an e-mail address, a web address).
     */
    fun languageState(latinField: Boolean = false): LanguageState =
        LanguageState.of(languages, languageLayouts, prefs.getString(LAYOUT, "")!!, latinField)

    /** The layout being typed on, by id: what `layout` says, or the first layout where that is empty or no longer on. */
    val layout: String get() = languageState().spec.id

    /**
     * Layouts came after 0.9.0, where every language typed on QWERTY. Once, on the first run with them: Spanish, French
     * or German that were already on are pinned to QWERTY (Languages.pinned), so a keyboard that was set up opens
     * exactly as it did, with one layout and nothing to switch. A language turned on afterwards gets its own layout.
     */
    fun pinLegacyLayouts() {
        if (prefs.getBoolean("defaults_languages", false)) return
        val edit = prefs.edit().putBoolean("defaults_languages", true)
        if (prefs.contains(LANGUAGES)) edit.putString(LANGUAGE_LAYOUTS, Languages.overrides(Languages.pinned(languages, languageLayouts)))
        edit.apply()
    }

    /**
     * Words autocorrect leaves alone, one a line: added when a fix is undone, removable in Settings. A word kept
     * before lower case followed the layout may have a capital İ stored as i with a dot above it (two characters,
     * which no typed word ever is): it is read as the i it is.
     */
    val learnedWords: Set<String> get() = prefs.getString(LEARNED_WORDS, "")!!.lines().map { it.trim().replace("i\u0307", "i") }.filter { it.isNotEmpty() }.toSet()

    /**
     * Keeps [word] as a learned word, in lower case by [locale]: the layout's it was typed on, which is how autocorrect
     * looks it up (on the Turkish board Işıl is ışıl and İlkim is ilkim).
     */
    fun learn(word: String, locale: java.util.Locale = java.util.Locale.ROOT) {
        val w = word.lowercase(locale)
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
    /** Autocorrect leaves texting slang alone and offers it (assets/dict/en_slang.txt). */
    val slang: Boolean get() = bool(SLANG)

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
        when {
            key == WIDE_LAYOUT -> wideLayout
            default is Boolean -> prefs.getBoolean(key, default)
            default is String -> prefs.getString(key, default)!!
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
        // Offline switched on: what is already under way stops too.
        if (key == OFFLINE && value == true) Net.cut()
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
        /** The Offline switch: no connection is opened while it is on (Net.kt). */
        const val OFFLINE = "offline"
        const val KEY_BORDERS = "key_borders"
        const val TOOLBAR = "toolbar"
        const val HAPTICS = "haptics"
        const val AUTOCORRECT = "autocorrect"
        const val LANGUAGES = "languages"
        /** Each language's layout where one was chosen for it: "fr:qwerty,de:qwerty". */
        const val LANGUAGE_LAYOUTS = "language_layouts"
        /** The layout being typed on, by id (Layouts.SPECS): the globe key and the space bar's list write it. */
        const val LAYOUT = "layout"
        const val GLOBE_KEY = "globe_key"
        const val SUGGESTIONS = "suggestions"
        /** Next-word prediction, and whether it learns from what is typed (NextWords.kt). */
        const val NEXT_WORD = "next_word"
        const val NEXT_WORD_LEARN = "next_word_learn"
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
        const val SLANG = "slang"
        const val WIDE_LAYOUT = "wide_layout"
        const val ONE_HANDED = "one_handed"
        const val DELETE_SWIPE = "delete_swipe"
        const val TYPING_STATS = "typing_stats"
        const val REWRITE_LANGUAGE = "rewrite_language"
        /** The live preview at the foot of Settings is open (lib/settings/keyboard_preview.dart). */
        const val PREVIEW_OPEN = "preview_open"

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
            OFFLINE to false,
            KEY_BORDERS to false,
            TOOLBAR to false,
            HAPTICS to "medium",
            AUTOCORRECT to true,
            LANGUAGES to "en",
            LANGUAGE_LAYOUTS to "",
            LAYOUT to "",
            GLOBE_KEY to true,
            SUGGESTIONS to true,
            NEXT_WORD to true,
            NEXT_WORD_LEARN to true,
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
            SLANG to true,
            WIDE_LAYOUT to "split",
            ONE_HANDED to "off",
            DELETE_SWIPE to true,
            TYPING_STATS to true,
            REWRITE_LANGUAGE to "Spanish",
            PREVIEW_OPEN to false,
        ) + AI_PROVIDERS.flatMap { listOf("ai_key_$it" to "", "ai_model_$it" to "") }
    }
}
