import Foundation

/// The text field the keyboard is typing into, as far as Ghostwriter needs it. On iOS adapt `UITextDocumentProxy`:
/// `before` = `documentContextBeforeInput ?? ""`, `after` = `documentContextAfterInput ?? ""`, `selected` =
/// `selectedText`, `insert` = `insertText`, `deleteBackward(n)` = `deleteBackward()` n times, `moveCursor(by:)` =
/// `adjustTextPosition(byCharacterOffset:)`.
///
/// iOS only hands a keyboard a window of the text near the cursor (often just the current sentence or paragraph), so
/// `before` and `after` may be cut short: a request is only found while its `<<` is still inside that window.
public protocol TextTarget {
    /// The text before the cursor (a window of it).
    var before: String { get }
    /// The text after the cursor (a window of it).
    var after: String { get }
    /// The selected text, or nil where nothing is selected.
    var selected: String? { get }
    /// Types `text` at the cursor, replacing the selection where there is one.
    func insert(_ text: String)
    /// Deletes `count` characters (grapheme clusters, as `UITextDocumentProxy.deleteBackward()` counts) before the
    /// cursor; with a selection, the first deletes the selection.
    func deleteBackward(_ count: Int)
    /// Moves the cursor `offset` characters (forward when positive). Returns false where it cannot.
    func moveCursor(by offset: Int) -> Bool
}

public extension TextTarget {
    func moveCursor(by offset: Int) -> Bool { false }
}

/// Where Ghostwriter gets the person's notes (Ghost.md on Android). Optional: without one, no notes are sent.
public protocol NotesSource {
    /// Notes matching `words`, best first, and where they came from ("Ghost.md on this phone"). Blocking; may throw.
    func search(words: [String]) throws -> (from: String, notes: [(title: String, body: String)])
}

/// The Ghostwriter plugin: type <<what you want>> in any app and the AI writes it there, as you.
///
/// `<<engineering standup tldr>>` or `<<why do the posthog metrics not fire on waitlist signup>>`: once the closing >>
/// is typed (or Fill is tapped in the row), the request goes to the AI you chose with what it needs to answer as you —
/// Mockingbird's style profile, the past messages that share its words (your "memory"), the text around the request in
/// the field, and with its switch on, your recent clipboard — and the answer replaces the tag. << >> rather than {{ }}
/// so templates and code that use braces are left alone.
///
/// Never in password fields or while incognito (the caller's rule). The keyboard owns the row; this owns the request.
/// The static functions are pure; an instance carries what a request needs and runs one request at a time on its own
/// queue, calling back on `callbackQueue`.
public final class Ghostwriter {
    /// A request just closed before the cursor: "…<<standup tldr>>".
    public static let closed = try! NSRegularExpression(pattern: "<<(?=[^<>\\n]*\\p{L})([^<>\\n]{2,400})>>$")

    /// An unfilled request the cursor is inside or just after, within the text around the cursor.
    private static let any = try! NSRegularExpression(pattern: "<<(?=[^<>\\n]*\\p{L})([^<>\\n]{2,400})>>")

    /// The closed request's match, only where it ends exactly at the cursor (`$` alone would also take a tag with a
    /// line break after it, which Android's fill then turns down because the cursor is past the tag).
    private static func closedMatch(_ before: NSString) -> NSTextCheckingResult? {
        guard let m = closed.firstMatch(in: before as String, range: NSRange(location: 0, length: before.length)),
              m.range.location + m.range.length == before.length else { return nil }
        return m
    }

    /// Whether a request has just been closed before the cursor (auto-fill's test, on each `>` typed).
    public static func hasClosedRequest(before: String) -> Bool { closedMatch(before as NSString) != nil }

    /// The request just closed before the cursor: what it asks, the whole `<<tag>>`, and how many characters
    /// (`TextTarget.deleteBackward`'s count) take the tag away.
    public static func closedRequest(before: String) -> (request: String, tag: String, deleteCount: Int)? {
        let ns = before as NSString
        guard let m = closedMatch(ns) else { return nil }
        let tag = ns.substring(with: m.range)
        return (ns.substring(with: m.range(at: 1)).trimmed, tag, tag.count)
    }

    /// The request the cursor is on, given the text `before` and `after` it: the request, and the whole tag.
    public static func tagAt(before: String, after: String) -> (request: String, tag: String)? {
        let joined = (before + after) as NSString
        let cursor = (before as NSString).length
        for m in any.matches(in: joined as String, range: NSRange(location: 0, length: joined.length)) {
            if cursor > m.range.location && cursor <= m.range.location + m.range.length {
                return (joined.substring(with: m.range(at: 1)).trimmed, joined.substring(with: m.range))
            }
        }
        return nil
    }

    /// A request ready to send: what it asks, its tag, and the field's text before and after the tag.
    public struct Fill: Equatable {
        public let request: String
        public let tag: String
        public let before: String
        public let after: String
        public init(request: String, tag: String, before: String, after: String) {
            self.request = request; self.tag = tag; self.before = before; self.after = after
        }
    }

    /// Where `tag` last starts before the cursor in before + after (UTF-16), or nil.
    private static func lastStart(of tag: String, joined: NSString, cursor: Int) -> Int? {
        let tagLength = (tag as NSString).length
        if cursor < 1 || tagLength == 0 { return nil }
        let limit = min(joined.length, cursor - 1 + tagLength)
        let r = joined.range(of: tag, options: [.backwards, .literal], range: NSRange(location: 0, length: limit))
        return r.location == NSNotFound ? nil : r.location
    }

    /// The request the cursor is on (or just after), with the text around its tag: what Fill sends.
    public static func fillAt(before: String, after: String) -> Fill? {
        guard let (request, tag) = tagAt(before: before, after: after) else { return nil }
        let around = (before + after) as NSString
        let at = lastStart(of: tag, joined: around, cursor: (before as NSString).length) ?? -1
        let end = min(at + (tag as NSString).length, around.length)
        return Fill(request: request, tag: tag, before: around.substring(to: max(at, 0)), after: around.substring(from: max(end, 0)))
    }

    /// A request in flight: `tag` is the text the answer replaces (the <<tag>>, or the selection for a rewrite).
    public final class Job {
        public let id: Int
        public let request: String
        public let tag: String
        public let selection: Bool
        public let mode: String?
        private let state = Locked((result: String?.none, error: String?.none, cancelled: false))

        public init(id: Int, request: String, tag: String, selection: Bool = false, mode: String? = nil) {
            self.id = id; self.request = request; self.tag = tag; self.selection = selection; self.mode = mode
        }

        public internal(set) var result: String? {
            get { state.with { $0.result } }
            set { state.with { $0.result = newValue } }
        }
        public internal(set) var error: String? {
            get { state.with { $0.error } }
            set { state.with { $0.error = newValue } }
        }
        /// Set to drop the answer: `done` is not called for a cancelled job.
        public var cancelled: Bool {
            get { state.with { $0.cancelled } }
            set { state.with { $0.cancelled = newValue } }
        }
        public var running: Bool { state.with { $0.result == nil && $0.error == nil && !$0.cancelled } }
    }

    public let prefs: KeyboardPrefs
    public let mockingbird: Mockingbird
    public let clips: ClipHistory?
    public let github: GitHubCommits?
    public let notes: NotesSource?
    public let session: URLSession
    /// Where `done` runs: the main queue unless changed.
    public var callbackQueue: DispatchQueue = .main
    /// Debug builds only: with it on, the AI key "debug-echo" answers without any AI, to try the typing and the row.
    public var debugEcho = false

    private let worker = DispatchQueue(label: "ghostwriter")
    private let ids = Locked(0)

    public init(prefs: KeyboardPrefs, mockingbird: Mockingbird, clips: ClipHistory? = nil, github: GitHubCommits? = nil,
                notes: NotesSource? = nil, session: URLSession = .shared) {
        self.prefs = prefs
        self.mockingbird = mockingbird
        self.clips = clips
        self.github = github
        self.notes = notes
        self.session = session
    }

    private func nextId() -> Int { ids.with { $0 += 1; return $0 } }

    /// Starts filling `request`; `done` runs on `callbackQueue` unless the job is cancelled first. `before` and `after`
    /// are the field's text around the tag (`fillAt`); `app` names the app or field (`AppContext.label`), `kind` says
    /// what it is.
    @discardableResult
    public func start(request: String, tag: String, before: String, after: String, app: String?, kind: AppKind, done: @escaping (Job) -> Void) -> Job {
        launch(Job(id: nextId(), request: request, tag: tag), done) { [self] in
            let (system, user) = prompt(request: request, before: before, after: after, app: app, kind: kind)
            if debugEcho && prefs.aiKey(prefs.aiProvider) == "debug-echo" {
                Thread.sleep(forTimeInterval: 0.8)
                // Says which notes the prompt carried, so a test can see they arrived.
                var section = system.range(of: "THEIR NOTES").map { String(system[$0.upperBound...]) } ?? ""
                if let end = section.range(of: "THEIR GITHUB") { section = String(section[..<end.lowerBound]) }
                let titles = section.split(whereSeparator: { $0.isNewline }).filter { $0.hasPrefix("--- ") }.map { String($0.dropFirst(4)) }.joined(separator: "; ")
                return "Shipped the PostHog waitlist events today. (debug: notes \(titles.isEmpty ? "none" : titles))"
            }
            return try AiClient.complete(prefs: prefs, system: system, user: user, maxTokens: 4000, effort: "low", webSearch: prefs.ghostwriterWeb, session: session)
        }
    }

    /// Starts filling `fill` in the field `hints` describe.
    @discardableResult
    public func start(_ fill: Fill, hints: AppContext.Hints, done: @escaping (Job) -> Void) -> Job {
        start(request: fill.request, tag: fill.tag, before: fill.before, after: fill.after, app: AppContext.label(hints: hints), kind: AppContext.kind(hints: hints), done: done)
    }

    /// Mockingbird's "In my voice": the `selected` text rewritten as you, to replace the selection.
    @discardableResult
    public func rewrite(selected: String, app: String?, kind: AppKind, done: @escaping (Job) -> Void) -> Job {
        launch(Job(id: nextId(), request: "In my voice", tag: selected, selection: true), done) { [self] in
            try mockingbird.rewrite(prefs: prefs, text: selected, app: app, chat: kind == .chat)
        }
    }

    /// The Rewrite plugin: the `selected` text rewritten as `mode` asks, to replace the selection.
    @discardableResult
    public func rewriteAs(selected: String, mode: Rewrite.Mode, done: @escaping (Job) -> Void) -> Job {
        launch(Job(id: nextId(), request: mode.label, tag: selected, selection: true, mode: mode.id), done) { [self] in
            try Rewrite.run(prefs: prefs, mode: mode, text: selected, session: session)
        }
    }

    private func launch(_ job: Job, _ done: @escaping (Job) -> Void, _ work: @escaping () throws -> String) -> Job {
        worker.async { [callbackQueue] in
            do { job.result = Ghostwriter.clean(try work()) }
            catch let e as AiClient.AiError { job.error = e.message }
            catch let e { job.error = e.localizedDescription.isEmpty ? "Couldn't write it" : e.localizedDescription }
            callbackQueue.async { if !job.cancelled { done(job) } }
        }
        return job
    }

    /// Takes off the wrapping models sometimes add anyway: quotes round the whole answer, a code fence.
    public static func clean(_ text: String) -> String {
        var t = text.trimmed
        if t.hasPrefix("```") && t.hasSuffix("```") {
            t = String(t.dropFirst(3))
            // Past the fence's own line (its language), where there is one.
            if let nl = t.firstIndex(of: "\n") { t = String(t[t.index(after: nl)...]) }
            if t.hasSuffix("```") { t = String(t.dropLast(3)) }
            t = t.trimmed
        }
        if t.count > 1 && t.first == "\"" && t.last == "\"" && t.filter({ $0 == "\"" }).count == 2 { t = String(t.dropFirst().dropLast()) }
        return t
    }

    static let instructions = """
        You are Ghostwriter, built into this person's phone keyboard. In a text field they typed a request
        between << and >>; the text you write replaces it, so write the text itself, as them, in their voice.
        - If the request names something to write ("engineering standup tldr", "decline politely"), write it.
        - If it is a question ("why does X happen"), write the answer they would put in this field.
        - Facts about them (their work, plans, people, what they did) come from their notes, GitHub
          commits, past messages, clipboard and the text around the request. Work updates come from the commits: say what
          changed and why in plain words, grouped sensibly, without hashes or file lists. Public facts (news, results, dates, figures, how things
          work) you may look up on the web when you have search: do, rather than guess or leave them out.
          Never invent specifics. Leave a short [bracketed blank] only for a fact you could not find.
        - Fit the field: in a chat app keep it as short and casual as their texts; elsewhere match their
          writing. Use the field's language. Continue naturally from the text before the request.
        - \(VoiceRules.writing)
        - Reply with the replacement text only: no quotes, no preface, no sources, citations or links unless
          asked for, and no explanation of what you did or searched.
        """

    /// The system prompt (instructions, voice, memory) and the user message (the field and the request). Blocking
    /// where GitHub or notes are searched: `start` calls it on the worker queue.
    public func prompt(request: String, before: String, after: String, app: String?, kind: AppKind, now: Date = Date()) -> (system: String, user: String) {
        let memory = prefs.ghostwriterMemory ? mockingbird.relevant(query: "\(request) \(before.takeLast(300))", app: app) : []
        var clipTexts: [String] = []
        if prefs.ghostwriterClipboard, Plugins.isOn(prefs, Plugins.clipboard), let clips = clips {
            var left = 2500
            for c in clips.all().prefix(6).map({ $0.text.take(1200) }) {
                if left <= 0 { break }
                left -= c.utf16.count
                clipTexts.append(c)
            }
        }
        // The work behind "<<posthog updates we made>>": the person's commits that match, from GitHub.
        var githubNote: String?
        var commits: [GitHubCommits.Commit] = []
        let token = prefs.ghostwriterGithub ? prefs.githubToken : ""
        let githubOn = prefs.ghostwriterGithub && !token.isBlank
        if githubOn, let github = github {
            do { commits = try github.search(token: token.trimmed, request: request, today: now) }
            catch let e as GitHubCommits.GitHubError { githubNote = e.message }
            catch let e { githubNote = e.localizedDescription }
        }
        let words = GitHubCommits.keywords(request)
        let wordList = words.joined(separator: ", ")
        // The person's notes that match, where the keyboard has a source of them.
        var notesFrom = "Ghost.md on this phone"
        var notesNote: String?
        var found: [(title: String, body: String)] = []
        if prefs.ghostwriterNotes, !words.isEmpty, let notes = notes {
            do { let r = try notes.search(words: words); notesFrom = r.from; found = r.notes }
            catch let e { notesNote = (e as? LocalizedError)?.errorDescription ?? e.localizedDescription }
        }
        var system = Ghostwriter.instructions
        system += "\n\n" + mockingbird.voiceSection()
        if !memory.isEmpty {
            system += "\n\nTHEIR PAST MESSAGES (most relevant first; their memory, for facts and voice)\n"
            for m in memory { system += "---\n" + m.text + "\n" }
        }
        if !found.isEmpty {
            system += "\nTHEIR NOTES from \(notesFrom) matching \(wordList) (best first)\n"
            var left = 9000
            for (title, body) in found {
                if left <= 0 { break }
                let part = body.take(min(left, 2500))
                left -= part.utf16.count
                system += "--- \(title)\n\(part)\n"
            }
        } else if let note = notesNote {
            system += "\n(Their Ghost.md notes couldn't be searched: \(note))\n"
        }
        if !commits.isEmpty {
            system += "\nTHEIR GITHUB COMMITS matching \(wordList) (newest first; what they and their AI agents actually changed)\n"
            for c in commits { system += "---\n\(c.date) \(c.repo)\n\(c.message)\n" }
        } else if let note = githubNote {
            system += "\n(Their GitHub couldn't be searched: \(note))\n"
        } else if githubOn && github != nil && !words.isEmpty {
            system += "\n(No GitHub commits of theirs mention \(wordList).)\n"
        }
        if !clipTexts.isEmpty {
            system += "\nTHEIR RECENT CLIPBOARD (newest first)\n"
            for c in clipTexts { system += "---\n" + c + "\n" }
        }
        let c = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day], from: now)
        let today = String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
        let beforeText = before.takeLast(1500), afterText = after.take(400)
        var user = "APP: \(app ?? "unknown") (\(kind == .chat ? "chat: texting" : kind.rawValue))\n"
        user += "TODAY: \(today)\n"
        user += "TEXT BEFORE THE REQUEST:\n\(beforeText.isBlank ? "(nothing)" : beforeText)\n"
        user += "TEXT AFTER THE REQUEST:\n\(afterText.isBlank ? "(nothing)" : afterText)\n"
        user += "REQUEST: \(request)"
        return (system, user)
    }

    /// An answer came back: it replaces its tag (or the selection) where that is still there. Returns false where the
    /// tag is gone, or the person has typed on past it: the answer then waits in the row instead.
    ///
    /// With `insert` false only the tag (or the selection) is taken away and the cursor left where the answer goes,
    /// for the keyboard to type `text` out key by key (`KeyboardPrefs.ghostwriterTyping`).
    ///
    /// A tag the cursor sits inside needs `TextTarget.moveCursor`; without it (the default) only a tag that ends at
    /// the cursor is replaced.
    public static func place(_ job: Job, text: String, in target: TextTarget, insert: Bool = true) -> Bool {
        if job.selection {
            guard target.selected == job.tag else { return false }
            if insert { target.insert(text) } else { target.deleteBackward(1) }
            return true
        }
        let before = Array(target.before), joined = before + Array(target.after), tag = Array(job.tag)
        if tag.isEmpty || before.isEmpty { return false }
        // The last copy of the tag that starts before the cursor; the cursor must be inside it or at its end.
        var at = min(before.count - 1, joined.count - tag.count)
        while at >= 0 && !joined[at...].starts(with: tag) { at -= 1 }
        if at < 0 { return false }
        let end = at + tag.count
        if end < before.count { return false }
        let ahead = end - before.count
        if ahead > 0 && !target.moveCursor(by: ahead) { return false }
        target.deleteBackward(tag.count)
        if insert { target.insert(text) }
        return true
    }
}
