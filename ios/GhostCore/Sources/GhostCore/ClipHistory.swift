import Foundation

/// The clipboard plugin's history: the last `max` pieces of text copied, newest first, kept in the injected store
/// under `"clip_history"` (the same JSON Android keeps). It never touches the pasteboard itself: the keyboard reads
/// `UIPasteboard` (which needs Full Access) and feeds the text to `record`. Keep nothing while incognito is on or
/// with the plugin off, and pass `sensitive` for what the system marks as concealed.
///
/// Thread-safe.
public final class ClipHistory {
    public static let max = 25
    public static let storeKey = "clip_history"
    /// Text longer than this is not kept.
    public static let maxChars = 5000

    public struct Clip: Hashable {
        public let text: String
        /// When it was copied, in milliseconds since 1970.
        public let at: Int64
        public init(text: String, at: Int64) { self.text = text; self.at = at }
    }

    private let store: PrefsStore
    private let lock = NSLock()
    private var cache: [Clip]?
    private var freshClip: Clip?

    public init(store: PrefsStore) { self.store = store }

    /// The newest clip, for the row's "just copied" chip; set it to nil once it is pasted.
    public var fresh: Clip? {
        get { lock.lock(); defer { lock.unlock() }; return freshClip }
        set { lock.lock(); freshClip = newValue; lock.unlock() }
    }

    public func all() -> [Clip] { lock.lock(); defer { lock.unlock() }; return load() }

    /// Forgets what is in memory, so the next read comes from the store (the other process may have written).
    public func reload() { lock.lock(); cache = nil; lock.unlock() }

    private func load() -> [Clip] {
        if let c = cache { return c }
        var list: [Clip] = []
        if let raw = store.object(forKey: ClipHistory.storeKey) as? String, let a = Json.array(raw) {
            for item in a {
                guard let o = item as? [String: Any], let t = o["t"] as? String, let at = Json.double(o["at"]) else { list = []; break }
                list.append(Clip(text: t, at: Int64(at)))
            }
        }
        cache = list
        return list
    }

    private func save(_ list: [Clip]) {
        cache = list
        store.set(Json.string(list.map { ["t": $0.text, "at": $0.at] as [String: Any] }), forKey: ClipHistory.storeKey)
    }

    /// Records `text` (what is on the clipboard now), if it is text worth keeping. Returns whether it was kept.
    @discardableResult
    public func record(_ text: String, sensitive: Bool = false, now: Date = Date()) -> Bool {
        if sensitive { return false }
        let text = text.trimmed
        if text.isEmpty || text.utf16.count > ClipHistory.maxChars { return false }
        lock.lock(); defer { lock.unlock() }
        var list = load()
        list.removeAll { $0.text == text }
        let entry = Clip(text: text, at: Int64(now.timeIntervalSince1970 * 1000))
        list.insert(entry, at: 0)
        if list.count > ClipHistory.max { list.removeLast(list.count - ClipHistory.max) }
        save(list)
        freshClip = entry
        return true
    }

    public func remove(_ text: String) {
        lock.lock(); defer { lock.unlock() }
        var list = load()
        list.removeAll { $0.text == text }
        save(list)
        if freshClip?.text == text { freshClip = nil }
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        save([])
        freshClip = nil
    }
}
