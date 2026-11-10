import Foundation

/// What the app and the keyboard extension share on iOS: the App Group's settings and files, and the Keychain group
/// the AI keys are kept in.
enum GhostShared {
    static let appGroup = KeyboardPrefs.appGroup
    static let urlScheme = "ghostkbd"

    /// Posted (as a Darwin notification, which crosses processes) when the app changes a setting.
    static let settingsChanged = "com.infamousvague.ghostkbd.settings"

    /// Set by the app each time it starts: its presence tells the keyboard the shared settings are readable.
    static let appMarker = "ios_app_marker"
    /// Set by the keyboard each time it shows: when it last ran, and whether it had Full Access.
    static let keyboardSeen = "ios_keyboard_seen"
    static let keyboardFullAccess = "ios_keyboard_full_access"

    static let groupDefaults: UserDefaults? = UserDefaults(suiteName: appGroup)

    /// The App Group's folder, where Mockingbird's samples and the clipboard history live. Nil where iOS refuses it
    /// (a keyboard without Full Access).
    static var container: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup) }

    /// "TEAMID.com.infamousvague.ghostkbd.shared": the Keychain group both targets' entitlements name.
    static var keychainGroup: String? {
        guard let prefix = Bundle.main.object(forInfoDictionaryKey: "AppIdentifierPrefix") as? String, !prefix.isEmpty else { return nil }
        return prefix + "com.infamousvague.ghostkbd.shared"
    }

    static func secrets() -> SecretStore { KeychainSecretStore(service: KeyboardPrefs.keychainService, accessGroup: keychainGroup) }

    static func postSettingsChanged() {
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(), CFNotificationName(settingsChanged as CFString), nil, nil, true)
    }

    /// A folder for files: the shared container where there is one, else this process's own Application Support.
    static func filesDirectory() -> URL {
        let base = container ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("ghost", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
}

/// Settings read from the App Group first and this process's own defaults second, and written to both: a keyboard
/// without Full Access cannot reach the group, and then keeps what it learns to itself.
final class LayeredStore: PrefsStore {
    let group: UserDefaults?
    let local: UserDefaults

    init(group: UserDefaults?, local: UserDefaults = .standard) {
        self.group = group
        self.local = local
    }

    /// Whether the app's settings can be read from here.
    var groupReadable: Bool { group?.object(forKey: GhostShared.appMarker) != nil }

    func object(forKey key: String) -> Any? { group?.object(forKey: key) ?? local.object(forKey: key) }

    func set(_ value: Any?, forKey key: String) {
        group?.set(value, forKey: key)
        local.set(value, forKey: key)
    }

    func removeObject(forKey key: String) {
        group?.removeObject(forKey: key)
        local.removeObject(forKey: key)
    }

    /// Copies the app's settings into this process's own defaults, so they stay as last seen if Full Access goes.
    func mirror(keys: [String]) {
        guard let group, groupReadable else { return }
        for key in keys { if let v = group.object(forKey: key) { local.set(v, forKey: key) } }
    }
}
