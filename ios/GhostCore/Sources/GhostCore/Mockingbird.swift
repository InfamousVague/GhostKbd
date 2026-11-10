import Foundation

/// The Mockingbird plugin: the AI learns how you write and text.
///
/// - Samples: what you finish typing (a message sent, a field left) is kept on this phone, as a file in the injected
///   directory (the App Group container), up to `maxSamples`. Chat fields (AppContext's `.chat`) are your texting;
///   everything else is your writing. The caller keeps nothing from password, email-address, web-address or number
///   fields (`AppContext.isPrivateField`), while incognito is on, or with collecting switched off.
/// - Slips: a sample is what was left in the field, after autocorrect; a slip autocorrect missed (or made with it off)
///   would otherwise be read as how you write. So `SampleCleaner` repairs the obvious ones as a sample is kept (only
///   where the slip shows and the right word is one you use; a word you type three times is yours, and goes back
///   into the samples it was taken out of), the AI that trains is told that only a habit you repeat is style, and
///   the AI that writes is told to keep your spelling habits and never to copy a slip (`VoiceRules`).
/// - Training: the AI you chose reads a selection of samples and writes a style profile, your voice in a page: how you
///   text and how you write. The samples stay on the phone; only that selection goes, once, to train. Retrains itself
///   unless switched off: at most daily, once `Retrain.afterSamples` new samples have come in, and once more,
///   without waiting for either, after an update that improved the style rules (`Retrain`).
/// - Using it: Ghostwriter (<<…>>) and "In my voice" send the profile and a few samples like the thing being written.
///
/// It is a profile, not fine-tuning: it works the same with every provider and costs one small request to train.
///
/// Thread-safe. `record` and `train` do their work on a private serial queue.
public final class Mockingbird {
    public static let maxSamples = 1500
    public static let maxSampleChars = 1500
    public static let samplesFile = "mockingbird_samples.json"
    public static let profileFile = "mockingbird_profile.json"
    /// The words judged the person's own (`keptWords`), a JSON list, oldest first.
    public static let wordsFile = "mockingbird_words.json"
    static let maxKeptWords = 500
    /// Small things to remember between runs: when an outdated profile's retrain was last tried (`outdated_retrain_at`).
    public static let stateFile = "mockingbird_state.json"
    private static let outdatedTryAt = "outdated_retrain_at"

    /// Something the person typed. `text` is what the AI reads: the sample with its obvious slips repaired. `raw` is
    /// it exactly as typed, kept only where the two differ, so a word can be put back once the person has typed it
    /// often enough to mean it. `cleaned` is the `SampleCleaner.version` that cleaned it, 0 for not yet.
    public struct Sample: Hashable {
        public let text: String
        public let chat: Bool
        public let app: String
        /// Milliseconds since 1970.
        public let at: Int64
        public let raw: String?
        public let cleaned: Int
        public init(text: String, chat: Bool, app: String, at: Int64, raw: String? = nil, cleaned: Int = 0) {
            self.text = text; self.chat = chat; self.app = app; self.at = at; self.raw = raw; self.cleaned = cleaned
        }

        /// As it was typed.
        public var typed: String { raw ?? text }

        func with(text: String, cleaned: Int) -> Sample {
            Sample(text: text, chat: chat, app: app, at: at, raw: typed == text ? nil : typed, cleaned: cleaned)
        }
    }

    /// The style profile; `version` is the `VoiceRules.profileVersion` of the instructions it was trained under.
    public struct Profile: Hashable {
        public let text: String
        /// Milliseconds since 1970.
        public let at: Int64
        public let samples: Int
        public let provider: String
        public let model: String
        public let version: Int
        public init(text: String, at: Int64, samples: Int, provider: String, model: String, version: Int = VoiceRules.profileVersion) {
            self.text = text; self.at = at; self.samples = samples; self.provider = provider; self.model = model; self.version = version
        }
    }

    /// Where the dictionaries for cleaning come from.
    private struct Spelling { var speller: Autocorrect?; var dictDirectory: URL? }

    private struct Status { var training = false; var lastError: String? }

    public let directory: URL
    public let session: URLSession
    private let status = Locked(Status())
    private let worker = DispatchQueue(label: "mockingbird")
    private let lock = NSRecursiveLock()
    private var cache: [Sample]?
    private var keptCache: [String]?
    private let spelling = Locked(Spelling())

    /// The keyboard's own Autocorrect while the keyboard is running, set by the keyboard: its dictionaries are loaded
    /// already, and it knows the person's languages and learned words. Nil in the app.
    public var speller: Autocorrect? {
        get { spelling.with { $0.speller } }
        set { spelling.with { $0.speller = newValue } }
    }

    /// Where the dictionaries are (`en.tsv` and the rest), for the app, which has no keyboard to borrow them from:
    /// before a training or an export they are loaded from here for the person's languages, and let go afterwards.
    /// Never used while there is a `speller`: the keyboard has no memory for a second copy.
    public var dictDirectory: URL? {
        get { spelling.with { $0.dictDirectory } }
        set { spelling.with { $0.dictDirectory = newValue } }
    }

    /// `directory` is where the files live (the App Group container on iOS); `session` carries training requests.
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
    private var wordsURL: URL { directory.appendingPathComponent(Mockingbird.wordsFile) }
    private var stateURL: URL { directory.appendingPathComponent(Mockingbird.stateFile) }

    // MARK: samples

    public func samples() -> [Sample] { lock.lock(); defer { lock.unlock() }; return load() }

    /// Forgets what is in memory, so the next read comes from the file (the other process may have written).
    public func reload() { lock.lock(); cache = nil; keptCache = nil; lock.unlock() }

    /// Waits for every `record` so far to be written (tests; before reading right after recording).
    public func flush() { worker.sync {} }

    private func load() -> [Sample] {
        if let c = cache { return c }
        var list: [Sample] = []
        if let data = try? Data(contentsOf: samplesURL), let a = Json.array(data) {
            for item in a {
                guard let o = item as? [String: Any], let t = o["t"] as? String, let at = Json.double(o["at"]) else { list = []; break }
                let raw = Json.str(o["r"])
                list.append(Sample(text: t, chat: (o["c"] as? Bool) ?? false, app: Json.str(o["a"]), at: Int64(at), raw: raw.isEmpty ? nil : raw, cleaned: Json.int(o["v"]) ?? 0))
            }
        }
        cache = list
        return list
    }

    private func save(_ list: [Sample]) {
        cache = list
        let a = list.map { s -> [String: Any] in
            var o: [String: Any] = ["t": s.text, "c": s.chat, "a": s.app, "at": s.at]
            if let raw = s.raw { o["r"] = raw }
            if s.cleaned > 0 { o["v"] = s.cleaned }
            return o
        }
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
    /// draft, read again) replaces it rather than being kept twice. Its obvious slips are repaired on the way in
    /// (`SampleCleaner`), on the worker queue, never the key press's.
    public func record(_ raw: String, app: String, chat: Bool) {
        guard let text = Mockingbird.cleaned(raw) else { return }
        let typed = Sample(text: text, chat: chat, app: app, at: Mockingbird.nowMs())
        worker.async { [self] in
            // Drafts are compared as typed, not as cleaned: the same draft read again must still be seen as the same.
            lock.lock()
            var list = load()
            if let last = list.last, last.app == app, text.hasPrefix(last.typed) || last.typed.hasPrefix(text) {
                if text.utf16.count <= last.typed.utf16.count { lock.unlock(); return }
                list.removeLast()
            }
            list.removeAll { $0.typed == text }
            cache = list
            let others = list.map { $0.typed }
            lock.unlock()
            // The cleaning is done outside the lock, so Settings and the keyboard never wait on it.
            let lexicon = self.lexicon(prefs: nil, wait: false)
            let (sample, fresh) = repaired(typed, others: others, lexicon)
            lock.lock()
            list = load()
            list.append(sample)
            if list.count > Mockingbird.maxSamples { list.removeFirst(list.count - Mockingbird.maxSamples) }
            save(list)
            let stale = list.contains { $0.cleaned < SampleCleaner.version }
            lock.unlock()
            // Samples kept before the cleaner (or while no dictionary was loaded) are cleaned now, once. And when
            // this sample has made a word the person's own, the samples it was taken out of get it back now:
            // Ghostwriter reads them between trainings.
            if let lexicon = lexicon, stale || fresh { cleanStored(lexicon) }
        }
    }

    /// Keeps `raw` typed in the field `hints` describe: texting in a chat field, writing elsewhere.
    public func record(_ raw: String, hints: AppContext.Hints) {
        record(raw, app: AppContext.label(hints: hints), chat: AppContext.kind(hints: hints) == .chat)
    }

    /// A sample from a typing profile: texting where `chat`, else writing.
    public func recordImported(_ raw: String, chat: Bool) { record(raw, app: chat ? "imported.chat" : "imported", chat: chat) }

    // MARK: cleaning the slips out

    /// The dictionaries to clean with: the keyboard's where it is running (`speller`; with `wait`, once they have
    /// loaded), else, with `wait` and `prefs`, ones loaded here from `dictDirectory` for the person's languages and
    /// learned words (Settings › Train now). Nil when there are none: the sample is kept as typed and cleaned before
    /// the next training. Blocks while dictionaries load, so it is for the worker queue only.
    private func lexicon(prefs: KeyboardPrefs?, wait: Bool) -> SampleLexicon? {
        let from = spelling.get()
        if let shared = from.speller {
            var waited = 0
            while wait && !shared.isReady && waited < 8000 { Thread.sleep(forTimeInterval: 0.1); waited += 100 }
            return shared.isReady ? shared.lexicon() : nil
        }
        guard wait, let prefs = prefs, let directory = from.dictDirectory else { return nil }
        let own = Autocorrect(dictDirectory: directory)
        own.setLearned(prefs.learnedWords)
        own.loadNow(languages: prefs.languages)
        return own.lexicon()
    }

    /// The words once judged the person's own: in no dictionary, but typed three times or more
    /// (`SampleCleaner.Habits.deliberate`). Remembered apart from the samples, so the oldest sample dropping off the
    /// end of the list cannot turn a nickname back into a slip. Oldest first; forgotten with `clear`.
    private func keptWords() -> [String] {
        lock.lock(); defer { lock.unlock() }
        if let k = keptCache { return k }
        var words: [String] = []
        if let data = try? Data(contentsOf: wordsURL), let a = Json.array(data) {
            for case let w as String in a where !words.contains(w) { words.append(w) }
        }
        keptCache = words
        return words
    }

    /// Remembers `words` as `keptWords`, the newest `maxKeptWords` of them. Worker queue only.
    private func keepWords(_ words: Set<String>) {
        lock.lock(); defer { lock.unlock() }
        let had = keptWords()
        let new = words.subtracting(had).sorted()
        if new.isEmpty { return }
        let kept = Array((had + new).suffix(Mockingbird.maxKeptWords))
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? Json.data(kept).write(to: wordsURL, options: .atomic)
        keptCache = kept
    }

    /// `sample` with its obvious slips repaired (`SampleCleaner.cleanNew`), and whether a word of it has just become
    /// the person's own. `others` is every other sample as typed: a word that would be changed is first counted
    /// across them, since one the person types repeatedly is theirs, and so is the word it would become, since a fix
    /// is made only to a word they use. Without a `lexicon` the sample is kept as typed and marked as not yet cleaned.
    private func repaired(_ sample: Sample, others: [String], _ lexicon: SampleLexicon?) -> (Sample, Bool) {
        let typed = sample.typed
        guard let lexicon = lexicon, lexicon.ready else { return (sample.with(text: typed, cleaned: 0), false) }
        let result = SampleCleaner.cleanNew(typed, others: others, lexicon, kept: Set(keptWords()))
        if result.fresh { keepWords(result.kept) }
        return (sample.with(text: result.text, cleaned: SampleCleaner.version), result.fresh)
    }

    /// Every stored sample cleaned again from what was typed (`SampleCleaner.cleanAll`), with the person's habits
    /// counted over all of them: the samples kept before the cleaner get their first cleaning, and a word since
    /// typed often enough to be the person's own goes back into the samples it was taken out of, and is remembered
    /// (`keptWords`). At most `maxSamples` short texts, each unknown word looked up once. Worker queue only.
    private func cleanStored(_ lexicon: SampleLexicon) {
        if !lexicon.ready { return }
        let before = samples()
        if before.isEmpty { return }
        let (texts, kept) = SampleCleaner.cleanAll(before.map { $0.typed }, lexicon, kept: Set(keptWords()))
        let after = before.enumerated().map { $1.with(text: texts[$0], cleaned: SampleCleaner.version) }
        lock.lock(); defer { lock.unlock() }
        // Only the worker adds and removes samples; clear() is the one thing that can have come in between.
        if load() != before { return }
        keepWords(kept)
        if after != before { save(after) }
    }

    /// The samples, every one cleaned first: what a typing profile exports (`Sample.text`, and `Sample.raw` beside it
    /// where a slip was repaired). Waits for the worker (a training in flight included, up to a minute), so not for
    /// the main thread.
    public func cleanedSamples(prefs: KeyboardPrefs) -> [Sample] {
        let done = DispatchSemaphore(value: 0)
        worker.async { [self] in
            if let lexicon = lexicon(prefs: prefs, wait: true) { cleanStored(lexicon) }
            done.signal()
        }
        _ = done.wait(timeout: .now() + 60)
        return samples()
    }

    /// Deletes every sample, the words kept as the person's own, and the profile.
    public func clear() {
        worker.sync {}
        lock.lock(); defer { lock.unlock() }
        cache = []
        keptCache = []
        try? FileManager.default.removeItem(at: samplesURL)
        try? FileManager.default.removeItem(at: profileURL)
        try? FileManager.default.removeItem(at: wordsURL)
        status.with { $0.lastError = nil }
    }

    // MARK: the profile

    public func profile() -> Profile? {
        guard let data = try? Data(contentsOf: profileURL), let o = Json.object(data),
              let text = o["text"] as? String, let at = Json.double(o["at"]) else { return nil }
        return Profile(text: text, at: Int64(at), samples: Json.int(o["samples"]) ?? 0, provider: Json.str(o["provider"]), model: Json.str(o["model"]), version: Json.int(o["v"]) ?? 1)
    }

    /// True for a profile trained under the old instructions, which asked for the "typos they leave in": it may
    /// describe slips as habits, so it is due a retrain (`Retrain.outdated`). One written elsewhere and imported from
    /// a typing-profile file is the person's choice and never outdated; one from Ghost Keyboard's own export keeps
    /// the version it was trained under.
    public func outdated(_ p: Profile) -> Bool { Retrain.outdated(version: p.version, provider: p.provider, model: p.model) }

    private func saveProfile(_ p: Profile) {
        let o: [String: Any] = ["text": p.text, "at": p.at, "samples": p.samples, "provider": p.provider, "model": p.model, "v": p.version]
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? Json.data(o).write(to: profileURL, options: .atomic)
    }

    /// A style profile from a typing profile file, in place of the trained one. `source` says whose it is
    /// (`Retrain.importedBy`) and `version` which style rules it was written under (`Retrain.importVersion`): an old
    /// profile of this keyboard's stays due its retrain after a trip through a file.
    public func importProfile(_ text: String, source: String, version: Int = VoiceRules.profileVersion) {
        saveProfile(Profile(text: text.take(6000), at: Mockingbird.nowMs(), samples: samples().count, provider: source, model: "", version: version))
    }

    /// What the AI that trains the style profile is told (`VoiceRules.trainSystem`).
    public static var trainSystem: String { VoiceRules.trainSystem }

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
                // The AI reads cleaned samples; without dictionaries it reads them as typed.
                if let lexicon = lexicon(prefs: prefs, wait: true) { cleanStored(lexicon) }
                let all = samples()
                if all.count < Retrain.minSamples { throw AiClient.AiError("Type a little more first: \(all.count) of \(Retrain.minSamples) samples") }
                let text = try AiClient.complete(prefs: prefs, system: VoiceRules.trainSystem, user: Mockingbird.trainingMessage(all),
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

    /// When an outdated profile's retrain was last tried, in milliseconds since 1970; 0 for never.
    private func outdatedTryAt() -> Int64 {
        guard let data = try? Data(contentsOf: stateURL), let o = Json.object(data) else { return 0 }
        return Int64(Json.double(o[Mockingbird.outdatedTryAt]) ?? 0)
    }

    private func setOutdatedTryAt(_ at: Int64) {
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try? Json.data([Mockingbird.outdatedTryAt: at]).write(to: stateURL, options: .atomic)
    }

    /// Whether training by itself is due (`Retrain.due`), and whether that is because the profile is outdated.
    private func autoTrain(_ now: Int64) -> (due: Bool, outdated: Bool) {
        let count = samples().count
        let p = profile()
        let old = p.map { outdated($0) } ?? false
        let due = Retrain.due(trained: p != nil, outdated: old, samples: count, newSamples: count - (p?.samples ?? 0),
                              sinceTrained: now - (p?.at ?? 0), sinceOutdatedTry: now - outdatedTryAt())
        return (due, old)
    }

    /// Whether a retrain is due: enough new samples have come in and (after the first) most of a day has passed, or
    /// the profile is outdated and its retrain has not been tried in the last day.
    public func autoTrainDue(now: Date = Date()) -> Bool { autoTrain(Int64(now.timeIntervalSince1970 * 1000)).due }

    /// Retrains by itself when switched on and the AI is set up, where `Retrain.due` says so: enough new samples and
    /// a day since the last training, or, for an `outdated` profile, at once (and a day later if that fails; the try
    /// is remembered across restarts). Cheap for the caller: the samples and the profile are read on the worker
    /// queue, so the keyboard can ask whenever a field is left, whether or not anything was kept from it. On iOS it
    /// also needs the person's consent to send typed text to their provider (`KeyboardPrefs.aiConsent`), since this
    /// is the one request the keyboard makes without being asked.
    public func maybeAutoTrain(prefs: KeyboardPrefs) {
        if !prefs.mockingbirdAutoTrain || !prefs.aiConsent || training || !AiClient.ready(prefs: prefs) { return }
        worker.async { [self] in
            if training { return }
            let now = Mockingbird.nowMs()
            let (due, old) = autoTrain(now)
            if !due { return }
            if old { setOutdatedTryAt(now) }
            train(prefs: prefs)
        }
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

    /// The profile as a system-prompt section, or a note that there isn't one yet; then, always,
    /// `VoiceRules.spellingRule`, so neither an old profile nor a slip in a sample is ever copied.
    public func voiceSection() -> String { VoiceRules.voiceSection(profile()?.text) }

    /// The system prompt "In my voice" sends for `text`.
    public func rewriteSystem(text: String, app: String?, chat: Bool) -> String {
        let examples = relevant(query: text, app: app, budget: 3000)
        var system = "Rewrite the user's text so it sounds exactly like this person \(chat ? "texting" : "writing"). "
        system += "Keep the meaning, facts and language; change only voice, wording, punctuation and length to match them. "
        system += VoiceRules.rewriting + " "
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
