package com.infamousvague.devkeyboard

/**
 * The keyboard's plugins: modules that ship inside the app and do nothing until added in Settings › Plugins, Ghost.md's
 * model (its lib/plugins/types.dart). Which are added is one setting, [KeyboardPrefs.PLUGINS], a comma-separated list
 * of ids the settings app writes and the keyboard reads; each plugin's own settings are keys of their own.
 *
 * - `autocorrect` (Autocorrect.kt): fixes a misspelled word as it is finished. Added by default.
 * - `dictation` (Dictation.kt, Whisper.kt, WhisperModel.kt): whisper.cpp speech to text on the phone, from a mic
 *   key beside the space bar. Adding it downloads its model and asks for the microphone.
 * - `themes` (KeyboardTheme.kt): preset colour themes, and one of the person's own.
 * - `swipe` (Autocorrect.swipe, KeyboardView): glide across the letters to spell a word.
 * - `passwords` (DevKeyboardService's inline suggestions): the autofill service's logins in the plugin row.
 * - `gifs` (Gifs.kt): search GIPHY from the plugin row and send a GIF.
 * - `markdown`, `terminal`, `clipboard`, `undo`, `incognito`: buttons in the plugin row (DevKeyboardService.refreshRow);
 *   markdown and terminal show in the apps they suit (AppContext.kt) unless set to everywhere.
 * - `sounds` (SoundPacks.kt): key sounds made with ElevenLabs.
 * - `emoji` (Emoji.kt): an emoji panel and search, every emoji of Unicode's latest standard; :shortcodes and
 *   emoji for the word typed in the row.
 * - `ghostmd` (GhostMd.kt): [[note]] and #tag completion and Ghost.md's blocks in Ghost.md; save a selection to it
 *   from anywhere.
 */
object Plugins {
    const val AUTOCORRECT = "autocorrect"
    const val DICTATION = "dictation"
    const val THEMES = "themes"
    const val SWIPE = "swipe"
    const val PASSWORDS = "passwords"
    const val GIFS = "gifs"
    const val MARKDOWN = "markdown"
    const val TERMINAL = "terminal"
    const val CLIPBOARD = "clipboard"
    const val UNDO = "undo"
    const val INCOGNITO = "incognito"
    const val SOUNDS = "sounds"
    const val EMOJI = "emoji"
    const val GHOSTMD = "ghostmd"
    val ALL = listOf(AUTOCORRECT, DICTATION, THEMES, SWIPE, PASSWORDS, GIFS, MARKDOWN, TERMINAL, CLIPBOARD, UNDO, INCOGNITO, SOUNDS, EMOJI, GHOSTMD)

    fun enabled(prefs: KeyboardPrefs): Set<String> =
        prefs.plugins.split(',').map { it.trim() }.filter { it in ALL }.toSet()

    fun isOn(prefs: KeyboardPrefs, id: String) = id in enabled(prefs)

    fun set(prefs: KeyboardPrefs, id: String, on: Boolean): Boolean {
        if (id !in ALL) return false
        val next = enabled(prefs).toMutableSet().apply { if (on) add(id) else remove(id) }
        return prefs.set(KeyboardPrefs.PLUGINS, ALL.filter { it in next }.joinToString(","))
    }
}
