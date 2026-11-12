package com.infamousvague.devkeyboard

/**
 * The keyboard's plugins: modules that ship inside the app and do nothing until added in Settings › Plugins, Ghost.md's
 * model (its lib/plugins/types.dart). Which are added is one setting, [KeyboardPrefs.PLUGINS], a comma-separated list
 * of ids the settings app writes and the keyboard reads; each plugin's own settings are keys of their own.
 *
 * - `autocorrect` (Autocorrect.kt): fixes a misspelled word as it is finished. On by default, with swipe, clipboard, undo and emoji.
 * - `dictation` (Dictation.kt, Whisper.kt, WhisperModel.kt): whisper.cpp speech to text on the phone, from a mic
 *   key next to enter, where the full stop was (Layouts.withMic). Adding it downloads its model and asks for the
 *   microphone.
 * - `themes` (KeyboardTheme.kt): preset colour themes, and one of the person's own.
 * - `swipe` (Autocorrect.swipe, KeyboardView): glide across the letters to spell a word.
 * - `passwords` (DevKeyboardService's inline suggestions): the autofill service's logins in the plugin row.
 * - `gifs` (Gifs.kt): search GIPHY from the plugin row and send a GIF.
 * - `markdown`, `terminal`, `clipboard`, `incognito`: buttons in the plugin row (DevKeyboardService.refreshRow);
 *   markdown and terminal show in the apps they suit (AppContext.kt) unless set to everywhere.
 *   The clipboard plugin also has "One-time codes from texts" (SmsCodes.kt, OtpCodes.kt; docs/sms-codes.md), a switch
 *   of its own that is off to start: a sign-in code from a text message as a chip in the row, held in memory only.
 * - `undo` (EditHistory.kt, FieldEditor.kt, ConnectionPort.kt): Undo and Redo arrows in the row. Every edit the keyboard
 *   makes goes through FieldEditor, which keeps a history of them for the field in hand and plays it back; where it has
 *   nothing of its own it asks the app to undo.
 * - `emoji` (Emoji.kt): an emoji panel and search, every emoji of Unicode's latest standard; :shortcodes and
 *   emoji for the word typed in the row.
 * - `ghostmd` (GhostMd.kt): [[note]] and #tag completion and Ghost.md's blocks in Ghost.md; save a selection to it
 *   from anywhere.
 * - `mockingbird` (Mockingbird.kt, AiClient.kt): learns how you write and text, as a style profile the AI follows;
 *   "In my voice" rewrites a selection.
 * - `ghostwriter` (Ghostwriter.kt): <<request>> anywhere is written by the AI, as you, in place.
 * - `rewrite` (Rewrite.kt, AiClient.kt): a Rewrite button in the row: the selection, or the whole field, fixed,
 *   shortened, made formal or casual, or translated, in place.
 * - `adaptive` (AdaptiveKeys.kt): learns where you aim at each letter and the neighbours you hit by mistake, and moves
 *   the keys' touch areas to suit; a heatmap of the mistakes and the moved aims can be drawn over the keys.
 * - `agent` (AgentKeys.kt): the keys a coding agent in a terminal is driven with (Shift+Tab, Esc twice, Ctrl+C, a new
 *   line that does not send, / @ ! and Ctrl+R) as chips in the row, in terminal apps; in AI chat apps a new line, a
 *   code block, / and @.
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
    const val EMOJI = "emoji"
    const val GHOSTMD = "ghostmd"
    const val MOCKINGBIRD = "mockingbird"
    const val GHOSTWRITER = "ghostwriter"
    const val ADAPTIVE = "adaptive"
    const val REWRITE = "rewrite"
    const val AGENT = "agent"
    val ALL = listOf(AUTOCORRECT, DICTATION, THEMES, SWIPE, PASSWORDS, GIFS, MARKDOWN, TERMINAL, CLIPBOARD, UNDO, INCOGNITO, EMOJI, GHOSTMD, MOCKINGBIRD, GHOSTWRITER, ADAPTIVE, REWRITE, AGENT)

    fun enabled(prefs: KeyboardPrefs): Set<String> =
        prefs.plugins.split(',').map { it.trim() }.filter { it in ALL }.toSet()

    fun isOn(prefs: KeyboardPrefs, id: String) = id in enabled(prefs)

    fun set(prefs: KeyboardPrefs, id: String, on: Boolean): Boolean {
        if (id !in ALL) return false
        val next = enabled(prefs).toMutableSet().apply { if (on) add(id) else remove(id) }
        return prefs.set(KeyboardPrefs.PLUGINS, ALL.filter { it in next }.joinToString(","))
    }
}
