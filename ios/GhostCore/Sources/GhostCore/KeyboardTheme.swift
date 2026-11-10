import Foundation

/// The keyboard's colours (ARGB, 0xAARRGGBB), after Gboard's default (non dynamic-colour) light and dark themes.
public struct KeyboardTheme: Hashable {
    public var background: UInt32
    public var key: UInt32
    public var keyPressed: UInt32
    public var functionKey: UInt32
    public var functionKeyPressed: UInt32
    public var accent: UInt32
    public var accentPressed: UInt32
    public var onAccent: UInt32
    public var text: UInt32
    public var hint: UInt32
    public var popup: UInt32
    public var popupSelected: UInt32
    public var onPopupSelected: UInt32
    public var popupShadow: UInt32
    public var toolbarIcon: UInt32
    public var dark: Bool
    /// Keys drawn as raised mechanical keycaps that sink when pressed (the Retro preset).
    public var mechanical: Bool

    public init(background: UInt32, key: UInt32, keyPressed: UInt32, functionKey: UInt32, functionKeyPressed: UInt32,
                accent: UInt32, accentPressed: UInt32, onAccent: UInt32, text: UInt32, hint: UInt32, popup: UInt32,
                popupSelected: UInt32, onPopupSelected: UInt32, popupShadow: UInt32, toolbarIcon: UInt32, dark: Bool,
                mechanical: Bool = false) {
        self.background = background; self.key = key; self.keyPressed = keyPressed
        self.functionKey = functionKey; self.functionKeyPressed = functionKeyPressed
        self.accent = accent; self.accentPressed = accentPressed; self.onAccent = onAccent
        self.text = text; self.hint = hint; self.popup = popup; self.popupSelected = popupSelected
        self.onPopupSelected = onPopupSelected; self.popupShadow = popupShadow; self.toolbarIcon = toolbarIcon
        self.dark = dark; self.mechanical = mechanical
    }

    public static let light = KeyboardTheme(
        background: 0xFFE8EAF0,
        key: 0xFFFFFFFF,
        keyPressed: 0xFFD5D9E0,
        functionKey: 0xFFCDD3DD,
        functionKeyPressed: 0xFFB4BBC7,
        // The ghost's cyan, deepened to read on white.
        accent: 0xFF008B8A,
        accentPressed: 0xFF006E6D,
        onAccent: 0xFFFFFFFF,
        text: 0xFF1F1F1F,
        hint: 0xFF5F6368,
        popup: 0xFFFFFFFF,
        popupSelected: 0xFF008B8A,
        onPopupSelected: 0xFFFFFFFF,
        popupShadow: 0x33000000,
        toolbarIcon: 0xFF444746,
        dark: false
    )

    /// Ghost Keyboard's own look: nearly black, keys a step up from it, and the ghost's cyan, #51FCFB.
    public static let dark = KeyboardTheme(
        background: 0xFF0A0B0C,
        key: 0xFF232528,
        keyPressed: 0xFF383B3F,
        functionKey: 0xFF16181A,
        functionKeyPressed: 0xFF2C2F33,
        accent: 0xFF51FCFB,
        accentPressed: 0xFF2ED8D7,
        onAccent: 0xFF00302F,
        text: 0xFFE8EDED,
        hint: 0xFF8A9393,
        popup: 0xFF2C2F33,
        popupSelected: 0xFF51FCFB,
        onPopupSelected: 0xFF00302F,
        popupShadow: 0x80000000,
        toolbarIcon: 0xFFB9C4C4,
        dark: true
    )

    /// The theme the settings ask for: the themes plugin's preset or own theme where it is added, else Gboard's.
    /// `systemDark` is the system's appearance (UITraitCollection.userInterfaceStyle == .dark), used for "system".
    public static func resolve(prefs: KeyboardPrefs, systemDark: Bool) -> KeyboardTheme {
        if Plugins.isOn(prefs, Plugins.themes) {
            let preset = prefs.themePreset
            switch preset {
            case "ghost", "gboard": break
            case "custom": if let t = parseCustom(prefs.customTheme) { return t }
            default: if let t = presets[preset] { return t }
            }
        }
        switch prefs.theme {
        case "light": return light
        case "dark": return dark
        default: return systemDark ? dark : light
        }
    }

    /// A whole theme from five colours: the keyboard's ground, its keys, its function keys, the accent, the ink.
    public static func fromBase(background: UInt32, key: UInt32, functionKey: UInt32, accent: UInt32, text: UInt32) -> KeyboardTheme {
        let dark = luminance(background) < 0.4
        let onAccent: UInt32 = luminance(accent) > 0.5 ? 0xFF111111 : 0xFFFFFFFF
        return KeyboardTheme(
            background: background,
            key: key,
            keyPressed: mix(key, text, 0.18),
            functionKey: functionKey,
            functionKeyPressed: mix(functionKey, text, 0.18),
            accent: accent,
            accentPressed: mix(accent, dark ? 0xFFFFFFFF : 0xFF000000, 0.15),
            onAccent: onAccent,
            text: text,
            hint: mix(text, background, 0.45),
            popup: mix(key, text, dark ? 0.08 : 0),
            popupSelected: accent,
            onPopupSelected: onAccent,
            popupShadow: dark ? 0x66000000 : 0x33000000,
            toolbarIcon: mix(text, background, 0.2),
            dark: dark
        )
    }

    /// "#RRGGBB,#RRGGBB,..." in `fromBase`'s order, or nil where it does not read.
    public static func parseCustom(_ spec: String) -> KeyboardTheme? {
        let c: [UInt32] = spec.split(separator: ",", omittingEmptySubsequences: false).compactMap { part in
            var s = part.trimmingCharacters(in: .whitespaces)
            if s.hasPrefix("#") { s.removeFirst() }
            guard let v = Int32(s, radix: 16) else { return nil }
            return 0xFF000000 | UInt32(bitPattern: v)
        }
        return c.count == 5 ? fromBase(background: c[0], key: c[1], functionKey: c[2], accent: c[3], text: c[4]) : nil
    }

    /// The themes plugin's presets, by id; the settings app lists the same ids with their names.
    public static let presets: [String: KeyboardTheme] = {
        // A beige mechanical board: cream caps, grey modifiers, a red-orange enter, on a dark case.
        var retro = fromBase(background: 0xFF34302B, key: 0xFFE9E1CD, functionKey: 0xFFA8A190, accent: 0xFFD9542B, text: 0xFF2E2A24)
        retro.hint = 0xFF7A715F
        retro.toolbarIcon = 0xFFD8CFBB
        retro.mechanical = true
        return [
            "glacier": fromBase(background: 0xFF0E1621, key: 0xFF1D2A3A, functionKey: 0xFF152030, accent: 0xFF7CC4FA, text: 0xFFE6F1FB),
            "midnight": fromBase(background: 0xFF000000, key: 0xFF1A1A1A, functionKey: 0xFF0D0D0D, accent: 0xFF8AB4F8, text: 0xFFFFFFFF),
            "paper": fromBase(background: 0xFFF4EFE6, key: 0xFFFFFCF6, functionKey: 0xFFE4DCCD, accent: 0xFFB5522B, text: 0xFF2B2622),
            "retro": retro,
        ]
    }()

    /// The preset ids in the order Settings lists them.
    public static let presetIds = ["glacier", "midnight", "paper", "retro"]

    /// Relative luminance (WCAG), 0 to 1; alpha is ignored.
    public static func luminance(_ c: UInt32) -> Double {
        func ch(_ v: UInt32) -> Double { let x = Double(v) / 255.0; return x <= 0.03928 ? x / 12.92 : pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c >> 16) & 0xFF) + 0.7152 * ch((c >> 8) & 0xFF) + 0.0722 * ch(c & 0xFF)
    }

    /// `a` moved the fraction `t` toward `b`, opaque.
    public static func mix(_ a: UInt32, _ b: UInt32, _ t: Float) -> UInt32 {
        func lerp(_ x: UInt32, _ y: UInt32) -> UInt32 {
            let v = Int(Float(x) + (Float(y) - Float(x)) * t)
            return UInt32(min(255, max(0, v)))
        }
        return 0xFF000000 | (lerp((a >> 16) & 0xFF, (b >> 16) & 0xFF) << 16) | (lerp((a >> 8) & 0xFF, (b >> 8) & 0xFF) << 8) | lerp(a & 0xFF, b & 0xFF)
    }
}
