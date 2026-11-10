import Foundation

/// The Mockingbird plugin: the AI learns how you write and text.
///
/// - Samples: what you finish typing (a message sent, a field left) is kept on this phone, as a file in the injected
///   directory (the App Group container), up to `maxSamples`. Chat fields (AppContext's `.chat`) are your texting;
///   everything else is your writing. The caller keeps nothing from password, email-address, web-address or number
///   fields (`AppContext.isPrivateField`), while incognito is on, or with collecting switched off.
/// - Training: the AI you chose reads a selection of samples and writes a style profile, your voice in a page: how you
///   text and how you write. The samples stay on the phone; only that selection goes, once, to train. Retrains itself
///   (at most daily, once `autoTrainAfter` new samples have come in) unless switched off.
/// - Using it: Ghostwriter (<<…>>) and "In my voice" send the profile and a few samples like the thing being written.
///
/// It is a profile, not fine-tuning: it works the same with every provider and costs one small request to train.
///
/// Thread-safe. `record` and `train` do their work on a private serial queue.
public final class Mockingbird {
    public static let maxSamples = 1500
    public static let maxSampleChars = 1500
    public static let autoTrainAfter = 50
    public static let samplesFile = "mockingbird_samples.json"
    public static let profileFile = "mockingbird_profile.json"

    public struct Sample: Hashable {
        public let text: String
        public let chat: Bool
        public let app: String
        /// Milliseconds since 1970.
        public let at: Int64
        public init(text: String, chat: Bool, app: String, at: Int64) { self.text = text; self.chat = chat; self.app = app; self.at = at }
    }

    public struct Profile: Hashable {
        public let text: String
        /// Milliseconds since 1970.
        public let at: Int64
        public let samples: Int
        public let provider: String
        public let model: String
        public init(text: String, at: Int64, samples: Int, provider: String, model: String) {
            self.text = text; self.at = at; self.samples = samples; self.provider = provider; self.model = model
        }
    }

    private struct Status { var training = false; var lastError: String? }

    public let directory: URL
    public let session: URLSession
    private let status = Locked(Status())
    private let worker = DispatchQueue(label: "mockingbird")
    private let lock = NSRecursiveLock()
    private var cache: [Sample]?

    /// `directory` is where the two files live (the App Group container on iOS); `session` carries training requests.
    public init(directory: URL, session: URLSession = .shared) {
        self.directory = directory
        self.session = session
    }

    /// What training is doing, for Settings.
    public var training: Bool { status.with { $0.training } }
    public var lastError: String? { status.with { $0.lastError } }

    private static func nowMs() -> Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    private var samplesURL: URL { directory.appendingPathComponent(Mockingbird.samplesFile) }
    private var profileURL: URL { directory.appendingPathComponent(Mockingbird.profileFile) }

    // MARK: samples

    public func samples() -> [Sample] { lock.lock(); defer { lock.unlock() }; return load() }

    /// Forgets what is in memory, so the next read comes from the file (the other process may have written).
    public func reload() { lock.lock(); cache = nil; lock.unlock() }

    /// Waits for every `record` so far to be written (tests; before reading right after recording).
    public func flush() { worker.sync {} }

    private func load() -> [Sample] {
        if let c = cache { return c }
        var list: [Sample] = []
        if let data = try? Data(contentsOf: samplesURL), let a = Json.array(data) {
            for item in a {
                guard let o = item as? [String: Any], let t = o["t"] as? String, let at = Json.double(o["at"]) else { list = []; break }
                list.append(Sample(text: t, chat: (o["c"] as? Bool) ?? false, app: Json.str(o["a"]), at: Int64(at)))
            }
        }
        cache = list
        return list
    }

    private func save(_ list: [Sample]) {
        cache = list
        let a = list.map { ["t": $0.text, "c": $0.chat, "a": $0.app, "at": $0.at] as [String: Any] }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? Json.data(a).write(to: samplesURL, options: .atomic)
    }

    private static func regex(_ pattern: String) -> NSRegularExpression { try! NSRegularExpression(pattern: pattern) }
    private static let fillTag = regex("<<[^<>\\n]*>>")
    private static let spaces = regex("[ \\t]+")
    private static let blankLines = regex("\\n{3,}")
    private static let urlish = regex("^(https?://|www\\.)\\S+$")
    private static let whitespace = regex("\\s+")
    private static let notWord = regex("[^\\p{L}\\p{N}]+")

    private static func replace(_ re: NSRegularExpression, _ text: String, _ with: String) -> String {
        re.stringByReplacingMatches(in: text, range: NSRange(location: 0, length: text.utf16.count), withTemplate: with)
    }

    private static func split(_ re: NSRegularExpression, _ text: String) -> [String] {
        let ns = text as NSString
        var out: [String] = []
        var at = 0
        for m in re.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            out.append(ns.substring(with: NSRange(location: at, length: m.range.location - at)))
            at = m.range.location + m.range.length
        }
        out.append(ns.substring(from: at))
        return out
    }

    /// What `raw` is kept as, or nil when it says nothing about how you write (too short, a link, mostly not letters).
    public static func cleaned(_ raw: String) -> String? {
        let text = replace(blankLines, replace(spaces, replace(fillTag, raw, ""), " "), "\n\n").trimmed.take(maxSampleChars)
        if text.utf16.count < 12 || split(whitespace, text).count < 3 { return nil }
        let all = NSRange(location: 0, length: text.utf16.count)
        if urlish.firstMatch(in: text, range: all).map({ $0.range == all }) ?? false { return nil }
        if text.filter({ $0.isLetter }).count < text.utf16.count / 2 { return nil }
        return text
    }

    /// Keeps `raw`, typed in `app` (`AppContext.label(hints:)`, or a bundle id), as a sample when it says something
    /// about how you write: texting where `chat`, else writing. A message that grows from one already kept (the same
    /// draft, read again) replaces it rather than being kept twice. Written on the worker queue.
    public func record(_ raw: String, app: String, chat: Bool) {
        guard let text = Mockingbird.cleaned(raw) else { return }
        let sample = Sample(text: text, chat: chat, app: app, at: Mockingbird.nowMs())
        worker.async { [self] in
            lock.lock(); defer { lock.unlock() }
            var list = load()
            if let last = list.last, last.app == app, text.hasPrefix(last.text) || last.text.hasPrefix(text) {
                if text.utf16.count <= last.text.utf16.count { return }
                list.removeLast()
            }
            list.removeAll { $0.text == text }
            list.append(sample)
            if list.count > Mockingbird.maxSamples { list.removeFirst(list.count - Mockingbird.maxSamples) }
            save(list)
        }
    }

    /// Keeps `raw` typed in the field `hints` describe: texting in a chat field, writing elsewhere.
    public func record(_ raw: String, hints: AppContext.Hints) {
        record(raw, app: AppContext.label(hints: hints), chat: AppContext.kind(hints: hints) == .chat)
    }

    /// A sample from a typing profile: texting where `chat`, else writing.
    public func recordImported(_ raw: String, chat: Bool) { record(raw, app: chat ? "imported.chat" : "imported", chat: chat) }

    /// Deletes every sample and the profile.
    public func clear() {
        worker.sync {}
        lock.lock(); defer { lock.unlock() }
        cache = []
        try? FileManager.default.removeItem(at: samplesURL)
        try? FileManager.default.removeItem(at: profileURL)
        status.with { $0.lastError = nil }
    }

    // MARK: the profile

    public func profile() -> Profile? {
        guard let data = try? Data(contentsOf: profileURL), let o = Json.object(data),
              let text = o["text"] as? String, let at = Json.double(o["at"]) else { return nil }
        return Profile(text: text, at: Int64(at), samples: Json.int(o["samples"]) ?? 0, provider: Json.str(o["provider"]), model: Json.str(o["model"]))
    }

    private func saveProfile(_ p: Profile) {
        let o: [String: Any] = ["text": p.text, "at": p.at, "samples": p.samples, "provider": p.provider, "model": p.model]
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? Json.data(o).write(to: profileURL, options: .atomic)
    }

    /// A style profile from a typing profile file, in place of the trained one; `source` says whose it is.
    public func importProfile(_ text: String, source: String) {
        saveProfile(Profile(text: text.take(6000), at: Mockingbird.nowMs(), samples: samples().count, provider: source, model: ""))
    }

    public static let trainSystem = """
        You study one person's writing so that another AI can write exactly as they do. You get real things they typed
        on their phone: TEXTING (messages in chat apps) and WRITING (everything else: email, notes, posts, forms).

        Write their style profile, addressed to the AI that will imitate them ("They write…"). Cover, for texting and
        for writing separately where they differ:
        - capitalisation, punctuation, spelling habits and typos they leave in
        - length and rhythm: how long messages and sentences run, line breaks, lists
        - tone and register: warmth, bluntness, humour, sarcasm, swearing, formality, and how it shifts by context
        - vocabulary: words and phrases they reach for, slang, abbreviations, filler, words they never use
        - emoji, emoticons and reactions: which, how often, where
        - how they open, ask, agree, decline, apologise, thank and sign off
        - technical or work vocabulary and how they explain things
        End with 8 to 12 short phrases quoted from the samples that sound most like them.

        Describe style only. Leave out private facts: names other than common greetings, addresses, numbers,
        passwords, health, money, or anything about other people. Plain text with short headings, under 700 words.
        """

    /// The user message training sends: the newest samples that fit (14,000 characters of texting, 10,000 of writing).
    public static func trainingMessage(_ all: [Sample]) -> String {
        let chat = pick(all.filter { $0.chat }, 14_000)
        let writing = pick(all.filter { !$0.chat }, 10_000)
        var user = "TEXTING (\(chat.count) messages)\n"
        for s in chat { user += "---\n" + s.text + "\n" }
        user += "\nWRITING (\(writing.count) pieces)\n"
        for s in writing { user += "---\n" + s.text + "\n" }
        return user
    }

    /// Trains a new profile in the background; `done` gets an error message, or nil when it worked, on `queue`.
    public func train(prefs: KeyboardPrefs, queue: DispatchQueue = .main, done: ((String?) -> Void)? = nil) {
        let start: Bool = status.with { s in
            if s.training { return false }
            s.training = true
            s.lastError = nil
            return true
        }
        guard start else { return }
        worker.async { [self] in
            var error: String?
            do {
                let all = samples()
                if all.count < 10 { throw AiClient.AiError("Type a little more first: \(all.count) of 10 samples") }
                let text = try AiClient.complete(prefs: prefs, system: Mockingbird.trainSystem, user: Mockingbird.trainingMessage(all),
                                                 maxTokens: 8000, effort: "medium", session: session)
                let setup = try AiClient.setup(prefs: prefs)
                saveProfile(Profile(text: text, at: Mockingbird.nowMs(), samples: all.count, provider: setup.provider.name, model: setup.model))
            } catch let e as AiClient.AiError {
                error = e.message
            } catch let e {
                error = e.localizedDescription.isEmpty ? "Training failed" : e.localizedDescription
            }
            status.with { $0.lastError = error; $0.training = false }
            if let done = done { queue.async { done(error) } }
        }
    }

    /// Newest first, as many as fit in `budget` characters, then back in the order they were typed.
    static func pick(_ list: [Sample], _ budget: Int) -> [Sample] {
        var left = budget
        var out: [Sample] = []
        for s in list.reversed() {
            if left <= 0 { break }
            left -= s.text.utf16.count + 4
            out.append(s)
        }
        return out.reversed()
    }

    /// Whether a retrain is due: enough new samples have come in, and (after the first) most of a day has passed.
    public func autoTrainDue(now: Date = Date()) -> Bool {
        let count = samples().count
        guard let p = profile() else { return count >= Mockingbird.autoTrainAfter }
        return count - p.samples >= Mockingbird.autoTrainAfter && Int64(now.timeIntervalSince1970 * 1000) - p.at > 20 * 3_600_000
    }

    /// Retrains by itself when switched on, the AI is set up, enough new samples have come in, and a day has passed.
    /// On iOS it also needs the person's consent to send typed text to their provider (`KeyboardPrefs.aiConsent`),
    /// since this is the one request the keyboard makes without being asked. Returns whether training started.
    @discardableResult
    public func maybeAutoTrain(prefs: KeyboardPrefs) -> Bool {
        if !prefs.mockingbirdAutoTrain || !prefs.aiConsent || training || !AiClient.ready(prefs: prefs) { return false }
        if !autoTrainDue() { return false }
        train(prefs: prefs)
        return true
    }

    // MARK: using it

    private static let stop: Set<String> = [
        "the", "and", "for", "you", "your", "are", "but", "not", "with", "that", "this", "was", "have", "has", "had", "its",
        "from", "they", "them", "what", "when", "why", "how", "who", "can", "just", "about", "into", "out", "our", "all",
        "will", "would", "there", "their", "been", "were", "then", "than", "also", "some", "any", "get", "got", "did", "does",
    ]

    private static func words(_ text: String) -> [String] {
        split(notWord, text.lowercased()).filter { $0.utf16.count >= 3 && !stop.contains($0) }
    }

    /// Past messages that share the most words with `query` (rarer words count for more), then the latest from `app`;
    /// at most `budget` characters. The "memory" Ghostwriter answers from.
    public func relevant(query: String, app: String?, budget: Int = 6000) -> [Sample] {
        let all = samples()
        if all.isEmpty { return [] }
        let q = Set(Mockingbird.words(query))
        var df: [String: Int] = [:]
        let bags: [Set<String>] = all.map { s in
            let b = Set(Mockingbird.words(s.text))
            for w in b { df[w, default: 0] += 1 }
            return b
        }
        let n = Double(all.count)
        var scored: [(index: Int, score: Double)] = []
        for i in all.indices {
            var sum = 0.0
            for w in q where bags[i].contains(w) { sum += log(1 + n / Double(df[w] ?? 1)) }
            if sum > 0 { scored.append((i, sum)) }
        }
        func rank(_ e: (index: Int, score: Double)) -> Double { e.score + Double(e.index) / n * 0.5 }
        scored.sort { a, b in rank(a) != rank(b) ? rank(a) > rank(b) : a.index < b.index }
        let best = scored.prefix(15).map { all[$0.index] }
        let recent = all.reversed().filter { $0.app == app }.prefix(6)
        var left = budget
        var seen = Set<Sample>()
        var out: [Sample] = []
        for s in best + recent where !seen.contains(s) {
            seen.insert(s)
            if left <= 0 { break }
            left -= s.text.utf16.count
            out.append(s)
        }
        return out
    }

    /// The profile as a system-prompt section, or a note that there isn't one yet.
    public func voiceSection() -> String {
        if let p = profile() { return "THEIR STYLE PROFILE\n\(p.text)" }
        return "THEIR STYLE PROFILE\n(Not trained yet: copy the voice of the past messages below.)"
    }

    /// The system prompt "In my voice" sends for `text`.
    public func rewriteSystem(text: String, app: String?, chat: Bool) -> String {
        let examples = relevant(query: text, app: app, budget: 3000)
        var system = "Rewrite the user's text so it sounds exactly like this person \(chat ? "texting" : "writing"). "
        system += "Keep the meaning, facts and language; change only voice, wording, punctuation and length to match them. "
        system += "Reply with the rewritten text alone: no quotes, no preface.\n\n"
        system += voiceSection()
        if !examples.isEmpty { system += "\n\nTHINGS THEY HAVE TYPED\n" + examples.map { $0.text }.joined(separator: "\n---\n") }
        return system
    }

    /// `text` rewritten in your voice: texting where `chat`, else writing. Blocking; throws an `AiClient.AiError`.
    public func rewrite(prefs: KeyboardPrefs, text: String, app: String?, chat: Bool) throws -> String {
        try AiClient.complete(prefs: prefs, system: rewriteSystem(text: text, app: app, chat: chat), user: text, maxTokens: 3000, effort: "low", session: session)
    }
}
