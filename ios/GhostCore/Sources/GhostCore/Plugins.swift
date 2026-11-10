import Foundation

/// The keyboard's plugins: modules that ship inside the app and do nothing until added in Settings › Plugins. Which
/// are added is one setting, `KeyboardPrefs.plugins`, a comma-separated list of ids the settings app writes and the
/// keyboard reads; each plugin's own settings are keys of their own.
///
/// - `autocorrect`: fixes a misspelled word as it is finished. On by default, with swipe, clipboard, undo and emoji.
/// - `dictation`: speech to text on the phone, from a mic key beside the space bar.
/// - `themes` (KeyboardTheme): preset colour themes, and one of the person's own.
/// - `swipe` (Autocorrect.swipe): glide across the letters to spell a word.
/// - `passwords`: the autofill service's logins in the plugin row (Android).
/// - `gifs`: search GIPHY from the plugin row and send a GIF.
/// - `markdown`, `terminal`, `clipboard`, `undo`, `incognito`: buttons in the plugin row; markdown and terminal show
///   in the apps they suit (AppContext) unless set to everywhere.
/// - `emoji`: an emoji panel and search; :shortcodes and emoji for the word typed in the row.
/// - `ghostmd`: [[note]] and #tag completion and Ghost.md's blocks in Ghost.md.
/// - `mockingbird` (Mockingbird, AiClient): learns how you write and text, as a style profile the AI follows;
///   "In my voice" rewrites a selection.
/// - `ghostwriter` (Ghostwriter): <<request>> anywhere is written by the AI, as you, in place.
/// - `rewrite` (Rewrite, AiClient): the selection, or the whole field, fixed, shortened, made formal or casual, or
///   translated, in place.
/// - `adaptive` (AdaptiveKeys): learns where you aim at each letter and the neighbours you hit by mistake, and moves
///   the keys' touch areas to suit.
public enum Plugins {
    public static let autocorrect = "autocorrect"
    public static let dictation = "dictation"
    public static let themes = "themes"
    public static let swipe = "swipe"
    public static let passwords = "passwords"
    public static let gifs = "gifs"
    public static let markdown = "markdown"
    public static let terminal = "terminal"
    public static let clipboard = "clipboard"
    public static let undo = "undo"
    public static let incognito = "incognito"
    public static let emoji = "emoji"
    public static let ghostmd = "ghostmd"
    public static let mockingbird = "mockingbird"
    public static let ghostwriter = "ghostwriter"
    public static let adaptive = "adaptive"
    public static let rewrite = "rewrite"
    public static let all = [autocorrect, dictation, themes, swipe, passwords, gifs, markdown, terminal, clipboard, undo, incognito, emoji, ghostmd, mockingbird, ghostwriter, adaptive, rewrite]

    public static func enabled(_ prefs: KeyboardPrefs) -> Set<String> {
        Set(prefs.plugins.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { all.contains($0) })
    }

    public static func isOn(_ prefs: KeyboardPrefs, _ id: String) -> Bool { enabled(prefs).contains(id) }

    @discardableResult
    public static func set(_ prefs: KeyboardPrefs, _ id: String, on: Bool) -> Bool {
        guard all.contains(id) else { return false }
        var next = enabled(prefs)
        if on { next.insert(id) } else { next.remove(id) }
        return prefs.set(key: KeyboardPrefs.plugins, value: all.filter { next.contains($0) }.joined(separator: ","))
    }
}
