import Foundation

/// What kind of app the keyboard is typing into. Plugins that suit some apps (terminal keys in an SSH client, markdown
/// in a notes app) show themselves there by default, and anywhere at all with their "Everywhere" setting.
public enum AppKind: String, Hashable, CaseIterable {
    case terminal, markdown, code, chat, other
}

/// On Android the kind comes from the host app's package. An iOS keyboard extension is not told which app it is typing
/// into, so here the kind is worked out from hints the keyboard does have: the text field's traits
/// (`UITextDocumentProxy`'s return key, keyboard type, autocorrection and autocapitalisation), and a bundle id only
/// where the caller happens to know one (the containing app typing into itself, a test, a future API).
public enum AppContext {
    /// The field's keyboard type, as far as it matters here (`UIKeyboardType`, reduced).
    public enum FieldType: String, Hashable {
        case text, ascii, url, email, number, phone, webSearch, twitter
    }

    /// What the keyboard can see of the field it types into. Fill in what `UITextDocumentProxy` gives:
    /// `returnKeyIsSend` = `returnKeyType == .send`; `autocorrectionOff` = `autocorrectionType == .no`;
    /// `autocapitalisationOff` = `autocapitalizationType == .none`; `fieldType` from `keyboardType`;
    /// `secure` = `isSecureTextEntry == true`. `bundleId` is the host app's, where known (usually nil).
    public struct Hints: Hashable {
        public var bundleId: String?
        public var fieldType: FieldType
        public var returnKeyIsSend: Bool
        public var autocorrectionOff: Bool
        public var autocapitalisationOff: Bool
        public var secure: Bool

        public init(bundleId: String? = nil, fieldType: FieldType = .text, returnKeyIsSend: Bool = false,
                    autocorrectionOff: Bool = false, autocapitalisationOff: Bool = false, secure: Bool = false) {
            self.bundleId = bundleId
            self.fieldType = fieldType
            self.returnKeyIsSend = returnKeyIsSend
            self.autocorrectionOff = autocorrectionOff
            self.autocapitalisationOff = autocapitalisationOff
            self.secure = secure
        }
    }

    /// Ghost.md's bundle ids (Android's package names, assumed the same on iOS).
    public static let ghostMd: Set<String> = ["com.mattssoftware.glyph", "com.mattssoftware.glyph.flutter", "com.mattssoftware.glyph.perf"]

    // Best-effort App Store bundle ids of the apps Android's lists name; only used where a bundle id is supplied.
    private static let terminals: Set<String> = ["com.crystalnix.ServerAuditor", "AsheKube.app.a-Shell", "app.ish.iSH"]
    private static let markdown: Set<String> = ghostMd.union([
        "md.obsidian", "net.cozic.joplin", "notion.id", "com.github.stormbreaker.prod", "net.shinyfrog.bear-iOS",
    ])
    private static let code: Set<String> = ["com.appliedphasor.working-copy", "com.github.stormbreaker.prod"]
    private static let chat: Set<String> = [
        "com.hammerandchisel.discord", "com.tinyspeck.chatlyio", "ph.telegra.Telegraph", "net.whatsapp.WhatsApp",
        "org.whispersystems.signal", "com.apple.MobileSMS", "com.facebook.Messenger", "com.microsoft.skype.teams", "im.vector.app",
    ]

    /// The kind of a known app, by bundle id; `.other` for nil and for apps not on the lists.
    public static func kind(bundleId: String?) -> AppKind {
        guard let id = bundleId else { return .other }
        if terminals.contains(id) { return .terminal }
        if markdown.contains(id) { return .markdown }
        if code.contains(id) { return .code }
        if chat.contains(id) { return .chat }
        return .other
    }

    /// The kind of field the keyboard is in, from what it can see:
    /// - a known bundle id decides, as on Android;
    /// - a Send return key is a chat composer (`.chat`: Mockingbird files what is typed as texting);
    /// - a plain text field with autocorrection and autocapitalisation both off is where code or a shell is typed
    ///   (`.terminal`: SSH clients and code editors set exactly this, and the two cannot be told apart);
    /// - everything else is `.other`. `.markdown` and `.code` only ever come from a bundle id.
    public static func kind(hints: Hints) -> AppKind {
        let known = kind(bundleId: hints.bundleId)
        if known != .other { return known }
        if hints.secure { return .other }
        if hints.returnKeyIsSend { return .chat }
        if hints.autocorrectionOff && hints.autocapitalisationOff && (hints.fieldType == .text || hints.fieldType == .ascii) { return .terminal }
        return .other
    }

    public static func isGhostMd(bundleId: String?) -> Bool { bundleId.map { ghostMd.contains($0) } ?? false }

    /// Fields Mockingbird never keeps anything from: passwords, email-address, web-address and number fields.
    public static func isPrivateField(_ hints: Hints) -> Bool {
        hints.secure || hints.fieldType == .email || hints.fieldType == .url || hints.fieldType == .number || hints.fieldType == .phone
    }

    /// The name Mockingbird files a sample under and Ghostwriter tells the AI: the bundle id where known, else the kind.
    public static func label(hints: Hints) -> String { hints.bundleId ?? "ios.\(kind(hints: hints).rawValue)" }
}
