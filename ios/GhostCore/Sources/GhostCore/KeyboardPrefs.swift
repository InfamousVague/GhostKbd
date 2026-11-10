import Foundation
import Security

/// Where settings are kept. `UserDefaults` (the App Group's suite on iOS) conforms as it is.
public protocol PrefsStore: AnyObject {
    func object(forKey key: String) -> Any?
    func set(_ value: Any?, forKey key: String)
    func removeObject(forKey key: String)
}

extension UserDefaults: PrefsStore {}

/// Settings in memory, for tests and previews.
public final class MemoryPrefsStore: PrefsStore {
    private var values: [String: Any] = [:]
    private let lock = NSLock()
    public init(_ values: [String: Any] = [:]) { self.values = values }
    public func object(forKey key: String) -> Any? { lock.lock(); defer { lock.unlock() }; return values[key] }
    public func set(_ value: Any?, forKey key: String) { lock.lock(); values[key] = value; lock.unlock() }
    public func removeObject(forKey key: String) { lock.lock(); values[key] = nil; lock.unlock() }
    /// Every key that has been written.
    public var keys: [String] { lock.lock(); defer { lock.unlock() }; return Array(values.keys) }
}

/// Where secrets (AI keys, the GitHub token) are kept: the keychain on iOS, never the prefs store.
public protocol SecretStore: AnyObject {
    /// The secret under `key`, or "" where there is none (or it cannot be read).
    func get(_ key: String) -> String
    /// Stores `value` under `key`; "" removes it.
    func set(_ key: String, _ value: String)
}

public final class MemorySecretStore: SecretStore {
    private var values: [String: String] = [:]
    private let lock = NSLock()
    public init(_ values: [String: String] = [:]) { self.values = values }
    public func get(_ key: String) -> String { lock.lock(); defer { lock.unlock() }; return values[key] ?? "" }
    public func set(_ key: String, _ value: String) { lock.lock(); values[key] = value.isEmpty ? nil : value; lock.unlock() }
    public var keys: [String] { lock.lock(); defer { lock.unlock() }; return Array(values.keys) }
}

/// Secrets as generic passwords in the keychain (`kSecAttrAccessibleAfterFirstUnlock`), shared between the app and
/// the keyboard through `accessGroup`. Where the keychain cannot be reached (no entitlement, the phone still locked
/// since boot) a read is "" and a write is dropped; it never throws or crashes. Reads are remembered for `cacheTTL`
/// seconds so a row refresh does not go to the keychain on every key.
public final class KeychainSecretStore: SecretStore {
    public let service: String
    public let accessGroup: String?
    /// How long a read is remembered in this process; 0 always asks the keychain.
    public var cacheTTL: TimeInterval = 2

    private var cache: [String: (value: String, at: TimeInterval)] = [:]
    private let lock = NSLock()

    public init(service: String, accessGroup: String?) {
        self.service = service
        self.accessGroup = accessGroup
    }

    private func query(_ key: String) -> [String: Any] {
        var q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        if let group = accessGroup, !group.isEmpty { q[kSecAttrAccessGroup as String] = group }
        return q
    }

    public func get(_ key: String) -> String {
        let now = ProcessInfo.processInfo.systemUptime
        lock.lock()
        if let hit = cache[key], cacheTTL > 0, now - hit.at < cacheTTL { lock.unlock(); return hit.value }
        lock.unlock()
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        let value: String
        if status == errSecSuccess, let data = out as? Data { value = String(data: data, encoding: .utf8) ?? "" } else { value = "" }
        lock.lock(); cache[key] = (value, now); lock.unlock()
        return value
    }

    public func set(_ key: String, _ value: String) {
        lock.lock(); cache[key] = nil; lock.unlock()
        if value.isEmpty {
            SecItemDelete(query(key) as CFDictionary)
            return
        }
        let data = Data(value.utf8)
        let attrs: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        let status = SecItemUpdate(query(key) as CFDictionary, attrs as CFDictionary)
        if status == errSecItemNotFound {
            var add = query(key)
            attrs.forEach { add[$0.key] = $0.value }
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    /// Forgets remembered reads (the other process may have changed a secret).
    public func invalidate() { lock.lock(); cache.removeAll(); lock.unlock() }
}

/// The keyboard's settings, one store shared by the settings app and the keyboard extension (the App Group's
/// UserDefaults). Every key and default lives here, so the two sides cannot drift. Secrets (each provider's AI key,
/// the GitHub token) go to `secrets` instead, through the same `all()` / `set()` / getters.
public final class KeyboardPrefs {
    /// The App Group both targets belong to, and the keychain service their secrets are kept under.
    public static let appGroup = "group.com.infamousvague.ghostkbd"
    public static let keychainService = "com.infamousvague.ghostkbd.secrets"

    public let store: PrefsStore
    public let secrets: SecretStore

    public init(store: PrefsStore, secrets: SecretStore) {
        self.store = store
        self.secrets = secrets
    }

    /// The shared settings: the App Group's defaults (the standard ones where the group is not set up) and the keychain.
    public convenience init(accessGroup: String? = nil) {
        self.init(store: UserDefaults(suiteName: KeyboardPrefs.appGroup) ?? .standard,
                  secrets: KeychainSecretStore(service: KeyboardPrefs.keychainService, accessGroup: accessGroup))
    }

    // MARK: reading

    private func string(_ key: String, _ fallback: String? = nil) -> String {
        (store.object(forKey: key) as? String) ?? fallback ?? (KeyboardPrefs.defaults[key] as? String) ?? ""
    }

    private func bool(_ key: String) -> Bool {
        (store.object(forKey: key) as? Bool) ?? (KeyboardPrefs.defaults[key] as? Bool) ?? false
    }

    /// "system", "light" or "dark".
    public var theme: String { string(KeyboardPrefs.theme) }
    public var numberRow: Bool { bool(KeyboardPrefs.numberRow) }
    public var keyPopup: Bool { bool(KeyboardPrefs.keyPopup) }
    public var sound: Bool { bool(KeyboardPrefs.sound) }
    public var vibrate: Bool { bool(KeyboardPrefs.vibrate) }
    public var hints: Bool { bool(KeyboardPrefs.hints) }
    public var autoCaps: Bool { bool(KeyboardPrefs.autoCaps) }
    public var doubleSpacePeriod: Bool { bool(KeyboardPrefs.doubleSpacePeriod) }
    public var spaceCursor: Bool { bool(KeyboardPrefs.spaceCursor) }
    public var autoUpdate: Bool { bool(KeyboardPrefs.autoUpdate) }
    public var keyBorders: Bool { bool(KeyboardPrefs.keyBorders) }
    public var toolbar: Bool { bool(KeyboardPrefs.toolbar) }
    /// Autocorrect changes words on its own; off, it only suggests.
    public var autocorrect: Bool { bool(KeyboardPrefs.autocorrect) }
    public var suggestions: Bool { bool(KeyboardPrefs.suggestions) }

    /// The GIF plugin: a GIPHY key of the person's own, used instead of the build's.
    public var giphyKey: String { string(KeyboardPrefs.giphyKey) }

    /// Markdown and terminal buttons in every app, rather than only the apps they suit (AppContext).
    public var markdownEverywhere: Bool { bool(KeyboardPrefs.markdownEverywhere) }
    public var terminalEverywhere: Bool { bool(KeyboardPrefs.terminalEverywhere) }

    /// Key press › Sound, without the sounds plugin: a stock sound or "system".
    public var stockSound: String { string(KeyboardPrefs.stockSound) }

    /// How loud key sounds play, 0 to 1 (the setting keeps a percentage).
    public var soundVolume: Float { min(100, max(0, Float(string(KeyboardPrefs.soundVolume)) ?? 35)) / 100 }

    /// Split the keys on a wide screen: the old switch, read where `wideLayout` was never chosen.
    public var split: Bool { bool(KeyboardPrefs.split) }

    /// The keys on a wide screen (an iPad, a phone on its side): "split" (two halves), "full" (edge to edge), or a
    /// phone-sized board docked "left", "center" or "right" for one thumb. Never chosen, it follows `split`.
    public var wideLayout: String { (store.object(forKey: KeyboardPrefs.wideLayout) as? String) ?? (split ? "split" : "full") }

    /// One-handed typing on a phone: "off", or the keys narrowed toward the "left" or "right" thumb.
    public var oneHanded: String { string(KeyboardPrefs.oneHanded) }

    /// Cursor control: sliding left from backspace deletes a word at a time.
    public var deleteSwipe: Bool { bool(KeyboardPrefs.deleteSwipe) }

    /// Typing stats: speed and accuracy counted on this phone.
    public var typingStats: Bool { bool(KeyboardPrefs.typingStats) }

    /// The Rewrite plugin: the language its Translate goes to, as the person wrote it.
    public var rewriteLanguage: String { string(KeyboardPrefs.rewriteLanguage) }

    /// Settings › Backlight: on (by default; never in password fields), its effect, colour, strength and mode.
    public var backlight: Bool { bool(KeyboardPrefs.backlight) }
    public var backlightEffect: String { string(KeyboardPrefs.backlightEffect) }
    public var backlightColour: String { string(KeyboardPrefs.backlightColour) }
    public var backlightStrength: String { string(KeyboardPrefs.backlightStrength) }
    public var backlightMode: String { string(KeyboardPrefs.backlightMode) }
    public var backlightFade: String { string(KeyboardPrefs.backlightFade) }

    /// The emoji plugin's skin tone (0 none, 1 light to 5 dark), and emoji offered for the word typed.
    public var emojiSkin: Int { Int(string(KeyboardPrefs.emojiSkin)) ?? 0 }
    public var emojiSuggest: Bool { bool(KeyboardPrefs.emojiSuggest) }

    /// The dictation plugin's chosen model and the language it expects ("auto" detects).
    public var whisperModel: String { string(KeyboardPrefs.whisperModel) }
    public var dictationLanguage: String { string(KeyboardPrefs.dictationLanguage) }

    /// Autocorrect's languages: codes with a dictionary, comma-separated ("en,es").
    public var languages: [String] {
        let list = string(KeyboardPrefs.languages).split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        return list.isEmpty ? ["en"] : list
    }

    /// Words autocorrect leaves alone, one a line: added when a fix is undone, removable in Settings.
    public var learnedWords: Set<String> {
        Set(string(KeyboardPrefs.learnedWords).split(whereSeparator: { $0.isNewline }).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
    }

    public func learn(_ word: String) {
        let w = word.lowercased()
        var now = learnedWords
        if !now.contains(w) {
            now.insert(w)
            store.set(now.sorted().joined(separator: "\n"), forKey: KeyboardPrefs.learnedWords)
        }
    }

    /// The AI Mockingbird and Ghostwriter use (AiClient.providers' ids), and the person's own key and model for each.
    public var aiProvider: String { string(KeyboardPrefs.aiProvider) }
    /// From the secret store (the keychain), never the prefs store.
    public func aiKey(_ provider: String) -> String { secrets.get("ai_key_\(provider)") }
    public func aiModel(_ provider: String) -> String { (store.object(forKey: "ai_model_\(provider)") as? String) ?? "" }
    /// The custom provider's API root, an OpenAI-compatible endpoint ending in /v1.
    public var aiBaseUrl: String { string(KeyboardPrefs.aiBaseUrl) }
    /// iOS only: the person agreed in the app that typed text may be sent to their chosen AI provider.
    public var aiConsent: Bool { bool(KeyboardPrefs.aiConsent) }

    /// Mockingbird: keep samples of what is typed, and retrain the style profile by itself (at most daily).
    public var mockingbirdCollect: Bool { bool(KeyboardPrefs.mockingbirdCollect) }
    public var mockingbirdAutoTrain: Bool { bool(KeyboardPrefs.mockingbirdAutoTrain) }

    /// Ghostwriter: fill a <<request>> as soon as its >> is typed; what it may read for context.
    public var ghostwriterAuto: Bool { bool(KeyboardPrefs.ghostwriterAuto) }
    public var ghostwriterMemory: Bool { bool(KeyboardPrefs.ghostwriterMemory) }
    public var ghostwriterClipboard: Bool { bool(KeyboardPrefs.ghostwriterClipboard) }
    /// Ghostwriter may look facts up online, with the provider's own search (AiClient.searches).
    public var ghostwriterWeb: Bool { bool(KeyboardPrefs.ghostwriterWeb) }
    /// Ghostwriter searches the person's GitHub commits with their own token.
    public var ghostwriterGithub: Bool { bool(KeyboardPrefs.ghostwriterGithub) }
    /// From the secret store (the keychain), never the prefs store.
    public var githubToken: String { secrets.get(KeyboardPrefs.githubToken) }
    /// The AI types its answer out key by key; off, it appears at once.
    public var ghostwriterTyping: Bool { bool(KeyboardPrefs.ghostwriterTyping) }
    /// Ghostwriter searches Ghost.md's notes.
    public var ghostwriterNotes: Bool { bool(KeyboardPrefs.ghostwriterNotes) }
    /// Adaptive keys: draw the heatmap of mistakes and moved aims over the keys.
    public var adaptiveMap: Bool { bool(KeyboardPrefs.adaptiveMap) }
    /// Arrow keys pinned at the end of the row above the keys, SwiftKey's way.
    public var arrowKeys: Bool { bool(KeyboardPrefs.arrowKeys) }
    /// Autocorrect leaves texting slang alone and offers it (dict/en_slang.txt).
    public var slang: Bool { bool(KeyboardPrefs.slang) }

    /// Swipe typing, the clipboard, undo and redo and emoji joined autocorrect as plugins on by default (0.8.5). A
    /// keyboard that already had its own list gets them added once; removed after that, they stay removed.
    public func addNewDefaults() {
        if (store.object(forKey: "defaults_085") as? Bool) == true { return }
        if store.object(forKey: KeyboardPrefs.plugins) != nil {
            let now = plugins.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            let next = Set(now + ["swipe", "clipboard", "undo", "emoji"])
            store.set(Plugins.all.filter { next.contains($0) }.joined(separator: ","), forKey: KeyboardPrefs.plugins)
        }
        store.set(true, forKey: "defaults_085")
    }

    /// The added plugins' ids, comma-separated (Plugins).
    public var plugins: String { string(KeyboardPrefs.plugins) }

    /// The themes plugin: a preset's id, or "custom" for `customTheme`.
    public var themePreset: String { string(KeyboardPrefs.themePreset) }

    /// The themes plugin's own theme: "background,key,functionKey,accent,text" as #RRGGBB, comma-separated.
    public var customTheme: String { string(KeyboardPrefs.customTheme) }

    /// "light", "medium" or "strong".
    public var haptics: String { string(KeyboardPrefs.haptics) }

    /// "short", "normal" or "tall".
    public var keyHeight: String { string(KeyboardPrefs.keyHeight) }

    /// "fast", "normal" or "slow".
    public var longPress: String { string(KeyboardPrefs.longPress) }

    public var longPressMs: Int64 {
        switch longPress { case "fast": return 200; case "slow": return 500; default: return 300 }
    }

    // MARK: the settings app's view

    /// Whether `key` is kept in the secret store rather than the prefs store.
    public static func isSecret(_ key: String) -> Bool { key == githubToken || key.hasPrefix("ai_key_") }

    /// Every setting with its current value, defaults filled in: what the settings app reads.
    public func all() -> [String: Any] {
        var out: [String: Any] = [:]
        for key in KeyboardPrefs.defaultKeys {
            let fallback = KeyboardPrefs.defaults[key]!
            if KeyboardPrefs.isSecret(key) { out[key] = secrets.get(key) }
            else if key == KeyboardPrefs.wideLayout { out[key] = wideLayout }
            else if let d = fallback as? Bool { out[key] = (store.object(forKey: key) as? Bool) ?? d }
            else if let d = fallback as? String { out[key] = (store.object(forKey: key) as? String) ?? d }
            else { out[key] = fallback }
        }
        return out
    }

    /// Writes one setting; refuses keys and types it does not know.
    @discardableResult
    public func set(key: String, value: Any?) -> Bool {
        guard let fallback = KeyboardPrefs.defaults[key] else { return false }
        // A Swift Bool and a String are told apart exactly: NSNumber 1 is not taken for true.
        if fallback is Bool, let v = value as? Bool, KeyboardPrefs.isExactlyBool(value) {
            store.set(v, forKey: key)
            return true
        }
        if fallback is String, let v = value as? String {
            if KeyboardPrefs.isSecret(key) { secrets.set(key, v) } else { store.set(v, forKey: key) }
            return true
        }
        return false
    }

    private static func isExactlyBool(_ value: Any?) -> Bool {
        if value is Bool, let n = value as? NSNumber { return CFGetTypeID(n) == CFBooleanGetTypeID() }
        return false
    }

    // MARK: keys

    public static let theme = "theme"
    public static let numberRow = "number_row"
    public static let keyPopup = "key_popup"
    public static let sound = "sound"
    public static let vibrate = "vibrate"
    public static let hints = "hints"
    public static let autoCaps = "auto_caps"
    public static let doubleSpacePeriod = "double_space_period"
    public static let spaceCursor = "space_cursor"
    public static let keyHeight = "key_height"
    public static let longPress = "long_press"
    public static let autoUpdate = "auto_update"
    public static let keyBorders = "key_borders"
    public static let toolbar = "toolbar"
    public static let haptics = "haptics"
    public static let autocorrect = "autocorrect"
    public static let languages = "languages"
    public static let suggestions = "suggestions"
    public static let giphyKey = "giphy_key"
    public static let whisperModel = "whisper_model"
    public static let split = "split_keyboard"
    public static let backlight = "backlight"
    public static let backlightEffect = "backlight_effect"
    public static let backlightColour = "backlight_colour"
    public static let backlightStrength = "backlight_strength"
    public static let backlightMode = "backlight_mode"
    public static let backlightFade = "backlight_fade"
    /// Settings › Developer shows once the version number in About is tapped five times.
    public static let developer = "developer"
    public static let emojiSkin = "emoji_skin"
    public static let emojiSuggest = "emoji_suggest"
    public static let markdownEverywhere = "markdown_everywhere"
    public static let terminalEverywhere = "terminal_everywhere"
    public static let soundVolume = "sound_volume"
    public static let stockSound = "stock_sound"
    public static let dictationLanguage = "dictation_language"
    public static let learnedWords = "learned_words"
    public static let plugins = "plugins"
    public static let themePreset = "theme_preset"
    public static let customTheme = "custom_theme"
    public static let onboarded = "onboarded"
    public static let aiProvider = "ai_provider"
    public static let aiBaseUrl = "ai_base_url"
    public static let mockingbirdCollect = "mockingbird_collect"
    public static let mockingbirdAutoTrain = "mockingbird_auto_train"
    public static let ghostwriterAuto = "ghostwriter_auto"
    public static let ghostwriterMemory = "ghostwriter_memory"
    public static let ghostwriterClipboard = "ghostwriter_clipboard"
    public static let ghostwriterWeb = "ghostwriter_web"
    public static let ghostwriterGithub = "ghostwriter_github"
    public static let githubToken = "github_token"
    public static let ghostwriterTyping = "ghostwriter_typing"
    public static let ghostwriterNotes = "ghostwriter_notes"
    public static let adaptiveMap = "adaptive_map"
    public static let arrowKeys = "arrow_keys"
    public static let slang = "slang"
    public static let wideLayout = "wide_layout"
    public static let oneHanded = "one_handed"
    public static let deleteSwipe = "delete_swipe"
    public static let typingStats = "typing_stats"
    public static let rewriteLanguage = "rewrite_language"
    /// The live preview at the foot of Settings is open.
    public static let previewOpen = "preview_open"
    /// iOS only: the person agreed that typed text may be sent to their chosen AI provider.
    public static let aiConsent = "ai_consent"

    /// Every provider's key and model setting (AiClient.providers' ids).
    public static let aiProviders = ["anthropic", "openai", "gemini", "mistral", "groq", "deepseek", "openrouter", "custom"]

    /// Every setting and its default, in order.
    private static let orderedDefaults: [(String, Any)] = {
        var list: [(String, Any)] = [
            (theme, "dark"),
            (numberRow, false),
            (keyPopup, true),
            (sound, false),
            (vibrate, true),
            (hints, true),
            (autoCaps, true),
            (doubleSpacePeriod, true),
            (spaceCursor, true),
            (keyHeight, "normal"),
            (longPress, "normal"),
            (autoUpdate, true),
            (keyBorders, false),
            (toolbar, false),
            (haptics, "medium"),
            (autocorrect, true),
            (languages, "en"),
            (suggestions, true),
            (giphyKey, ""),
            (whisperModel, "base.en"),
            (split, true),
            (backlight, true),
            (backlightEffect, "reactive"),
            (backlightColour, "accent"),
            (backlightStrength, "medium"),
            (backlightMode, "brighten"),
            (backlightFade, "medium"),
            (developer, false),
            (emojiSkin, "0"),
            (emojiSuggest, true),
            (markdownEverywhere, false),
            (terminalEverywhere, false),
            (soundVolume, "35"),
            (stockSound, "bamboo"),
            (dictationLanguage, "auto"),
            ("theme_tint", "#51FCFB"),
            ("theme_accent", ""),
            ("theme_mode", "dark"),
            (learnedWords, ""),
            (plugins, "autocorrect,swipe,clipboard,undo,emoji"),
            (themePreset, "ghost"),
            (customTheme, "#0A0B0C,#232528,#16181A,#51FCFB,#E8EDED"),
            (onboarded, false),
            (aiProvider, "anthropic"),
            (aiBaseUrl, ""),
            (mockingbirdCollect, true),
            (mockingbirdAutoTrain, true),
            (ghostwriterAuto, true),
            (ghostwriterMemory, true),
            (ghostwriterClipboard, false),
            (ghostwriterWeb, true),
            (ghostwriterGithub, true),
            (githubToken, ""),
            (ghostwriterTyping, true),
            (ghostwriterNotes, true),
            (adaptiveMap, false),
            (arrowKeys, false),
            (slang, true),
            (wideLayout, "split"),
            (oneHanded, "off"),
            (deleteSwipe, true),
            (typingStats, true),
            (rewriteLanguage, "Spanish"),
            (previewOpen, false),
            (aiConsent, false),
        ]
        for p in aiProviders { list.append(("ai_key_\(p)", "")); list.append(("ai_model_\(p)", "")) }
        return list
    }()

    /// Every setting's key, in the order Android's DEFAULTS lists them (then `ai_consent`, then each provider's key and model).
    public static let defaultKeys: [String] = orderedDefaults.map { $0.0 }

    /// Every setting's default (a `Bool` or a `String`), by key.
    public static let defaults: [String: Any] = Dictionary(uniqueKeysWithValues: orderedDefaults.map { ($0.0, $0.1) })
}
