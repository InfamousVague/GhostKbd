import CoreGraphics
import Foundation

/// Autocorrect for one word at a time, run when a word is finished (space or punctuation): a word the dictionaries do
/// not know, or know only as a rare spelling, is replaced by the likeliest real word a slip or two away.
///
/// The dictionaries are `<dictDirectory>/<language>.tsv`: each language's commonest words with their Zipf frequency
/// (from the `wordfreq` project's "best" lists, CC BY-SA 4.0; 40,000 for English, 30,000 for the others), plus
/// English's apostrophe-less contractions (`en_contractions.tsv`: dont → don't). Several languages load together, so
/// mixing them in one message works. A word typed without its accents (cafe, manana) finds the accented one cheaply.
///
/// A candidate scores its frequency minus what its slips cost: a neighbouring key is a cheap slip, a far one dear, a
/// doubled or missing letter in between. Neighbours are QWERTY's, so "keybkard" finds "keyboard" first.
///
/// Learned words (a fix the person undid, or words added in Settings) are never changed, and count as words for
/// correcting near-misses of them.
///
/// Thread-safe: dictionaries load on a background queue and are swapped in whole; `correct`, `suggest` and `swipe`
/// may be called from any thread and answer nothing (nil / empty) until `isReady`.
public final class Autocorrect {
    /// The loaded dictionaries, immutable once built.
    private final class Model {
        var words: [String: Float] = [:]
        var folded: [String: String] = [:]
        var contractions: [String: String] = [:]
        /// Every word, sorted by its UTF-8 bytes, for completions: a prefix's words are one run of it.
        var sorted: [String] = []
        /// Swipe typing's index: words (as indices into `sorted`) by their accent-free first and last letters,
        /// "hello" under h·26+o.
        var byEnds: [[Int32]] = []
    }

    private struct State {
        var model = Model()
        var ready = false
        var learned: Set<String> = []
        var slang: Set<String> = []
        var slangOn = false
        var slangList: Set<String>?
        var loaded: [String] = []
        var generation = 0
    }

    private let dictDirectory: URL
    private let state = Locked(State())
    private let queue = DispatchQueue(label: "autocorrect-dictionary", qos: .userInitiated)

    /// Runs on `callbackQueue` (the main queue unless changed) each time a set of dictionaries has finished loading.
    public var onReady: (() -> Void)?
    public var callbackQueue: DispatchQueue = .main

    /// `dictDirectory` holds `en.tsv`, `en_contractions.tsv`, `en_slang.txt`, `de.tsv` and the rest.
    public init(dictDirectory: URL) {
        self.dictDirectory = dictDirectory
    }

    /// Whether dictionaries are loaded; until then nothing is corrected or suggested.
    public var isReady: Bool { state.with { $0.ready } }

    /// The languages asked for (loading or loaded).
    public var languages: [String] { state.with { $0.loaded } }

    /// How many words are loaded.
    public var wordCount: Int { state.with { $0.model.words.count } }

    /// Loads `languages`' dictionaries in the background, unless they are the ones already loaded.
    public func setLanguages(_ languages: [String]) {
        let generation: Int? = state.with { s in
            if languages == s.loaded { return nil }
            s.loaded = languages
            s.generation += 1
            return s.generation
        }
        guard let generation = generation else { return }
        queue.async { [weak self] in
            guard let self = self else { return }
            // A newer request is already waiting: skip this one.
            if self.state.with({ $0.generation }) != generation { return }
            let model = Autocorrect.load(languages, from: self.dictDirectory)
            let current = self.state.with { s -> Bool in
                if s.generation != generation { return false }
                s.model = model
                s.ready = true
                return true
            }
            if current, let onReady = self.onReady { self.callbackQueue.async(execute: onReady) }
        }
    }

    /// Loads `languages`' dictionaries on the calling thread (tests, tools); `onReady` is not called.
    public func loadNow(languages: [String]) {
        let model = Autocorrect.load(languages, from: dictDirectory)
        state.with { s in
            s.loaded = languages
            s.generation += 1
            s.model = model
            s.ready = true
        }
    }

    private static func load(_ languages: [String], from directory: URL) -> Model {
        let model = Model()
        var w: [String: Float] = [:]
        w.reserveCapacity(48_000 * max(1, languages.count))
        var c: [String: String] = [:]
        for lang in languages {
            eachLine(of: directory.appendingPathComponent("\(lang).tsv")) { bytes, tab in
                guard tab > bytes.startIndex, let zipf = parseFloat(bytes[(tab + 1)...]) else { return }
                let word = String(decoding: bytes[..<tab], as: UTF8.self)
                if zipf > (w[word] ?? -Float.infinity) { w[word] = zipf }
            }
            if lang == "en" {
                eachLine(of: directory.appendingPathComponent("en_contractions.tsv")) { bytes, tab in
                    guard tab > bytes.startIndex else { return }
                    c[String(decoding: bytes[..<tab], as: UTF8.self)] = String(decoding: bytes[(tab + 1)...], as: UTF8.self)
                }
            }
        }
        // The words of a programmer's day, which a general list misses or ranks as typos (npm, async...).
        for word in devWords { w[word] = max(w[word] ?? 0, 4.5) }
        let sorted = w.keys.sorted { $0.utf8.lexicographicallyPrecedes($1.utf8) }
        // Each accented word under its plain spelling, the commonest winning: cafe → café, manana → mañana.
        var f: [String: String] = [:]
        var ends = [[Int32]](repeating: [], count: 26 * 26)
        for (i, word) in sorted.enumerated() {
            let plain = fold(word)
            if plain != word, let zipf = w[word], (w[plain] ?? 0) < zipf {
                if let had = f[plain], !((w[had] ?? 0) < zipf) {} else { f[plain] = word }
            }
            let u = plain.utf8
            if u.count < 2 || u.count > 20 || !u.allSatisfy({ $0 >= 97 && $0 <= 122 }) { continue }
            ends[Int(u.first! - 97) * 26 + Int(u.last! - 97)].append(Int32(i))
        }
        model.words = w
        model.folded = f
        model.contractions = c
        model.sorted = sorted
        model.byEnds = ends
        return model
    }

    /// Each "word<TAB>rest" line of a file as bytes (without its line ending), with where its first tab is (-1: none).
    private static func eachLine(of url: URL, _ body: (ArraySlice<UInt8>, Int) -> Void) {
        guard let data = try? Data(contentsOf: url, options: .mappedIfSafe) else { return }
        let bytes = [UInt8](data)
        var start = 0
        let n = bytes.count
        while start < n {
            var end = start
            var tab = -1
            while end < n && bytes[end] != 10 {
                if bytes[end] == 9 && tab < 0 { tab = end }
                end += 1
            }
            var stop = end
            if stop > start && bytes[stop - 1] == 13 { stop -= 1 }
            if stop > start { body(bytes[start..<stop], tab) }
            start = end + 1
        }
    }

    /// "7.73" and the like, read without making a String; anything else goes to `Float.init`.
    private static func parseFloat(_ bytes: ArraySlice<UInt8>) -> Float? {
        var whole = 0, frac = 0, scale = 1, seenDot = false, digits = 0
        for b in bytes {
            if b == 46 && !seenDot { seenDot = true; continue }
            guard b >= 48 && b <= 57, digits < 9 else { return Float(String(decoding: bytes, as: UTF8.self).trimmingCharacters(in: .whitespaces)) }
            if seenDot { frac = frac * 10 + Int(b - 48); scale *= 10 } else { whole = whole * 10 + Int(b - 48) }
            digits += 1
        }
        return digits == 0 ? nil : Float(whole) + Float(frac) / Float(scale)
    }

    /// Words the person has kept: never corrected, and targets for correcting slips of them.
    public func setLearned(_ words: Set<String>) { state.with { $0.learned = words } }

    /// Texting slang on or off (`en_slang.txt`): left alone, and offered as you type.
    public func setSlang(_ on: Bool) {
        let needsList: Bool = state.with { s in
            if on == s.slangOn { return false }
            s.slangOn = on
            if !on { s.slang = []; return false }
            if let list = s.slangList { s.slang = list; return false }
            return true
        }
        guard needsList else { return }
        // A kilobyte: read where it is asked for.
        var list = Set<String>()
        if let text = try? String(contentsOf: dictDirectory.appendingPathComponent("en_slang.txt"), encoding: .utf8) {
            for line in text.split(whereSeparator: { $0.isNewline }) {
                let word = line.trimmingCharacters(in: .whitespaces).lowercased()
                if !word.isEmpty && !word.hasPrefix("#") { list.insert(word) }
            }
        }
        state.with { s in
            s.slangList = list
            if s.slangOn { s.slang = list }
        }
    }

    /// What one call works from: the dictionaries and the kept words as they are now.
    private struct Snapshot {
        let model: Model
        let learned: Set<String>
        let slang: Set<String>

        /// A word autocorrect never changes: one the person kept, or slang while that is on.
        func kept(_ lower: String) -> Bool { learned.contains(lower) || slang.contains(lower) }

        /// How common a slang word counts, for ranking it among completions: its list frequency, else fairly common.
        func slangZipf(_ word: String) -> Float { max(model.words[word] ?? 0, Autocorrect.slangZipf) }
    }

    private func snapshot() -> Snapshot? {
        state.with { $0.ready ? Snapshot(model: $0.model, learned: $0.learned, slang: $0.slang) : nil }
    }

    /// What `typed` should become, or nil to leave it. `typed` is letters and apostrophes only.
    public func correct(_ typed: String) -> String? {
        guard !typed.isEmpty, typed.utf16.count <= 24, let s = snapshot() else { return nil }
        // An acronym or a deliberate capitalised name of two letters or more is left as typed.
        if typed.count > 1 && typed.allSatisfy({ $0.isUppercase }) { return nil }
        // camelCase, iOS, McDonald: a capital after the first letter is deliberate.
        if typed.dropFirst().contains(where: { $0.isUppercase }) { return nil }
        let lower = typed.lowercased()
        if s.kept(lower) { return nil }
        guard let fixed = Autocorrect.best(lower, s) else { return nil }
        let cased = Autocorrect.recase(typed, fixed)
        return cased == typed ? nil : cased
    }

    /// What the row above the keys offers for the word being typed: the fix autocorrect would make (or the word itself
    /// when it is a word), then the commonest words it begins. `typedIsWord` says whether the typed word is one.
    public struct Suggestions: Equatable {
        public let words: [String]
        public let fix: String?
        public let typedIsWord: Bool

        public init(words: [String], fix: String?, typedIsWord: Bool) {
            self.words = words
            self.fix = fix
            self.typedIsWord = typedIsWord
        }
    }

    public func suggest(_ typed: String) -> Suggestions {
        guard !typed.isEmpty, typed.utf16.count <= 24, let s = snapshot() else { return Suggestions(words: [], fix: nil, typedIsWord: false) }
        let lower = typed.lowercased()
        let words = s.model.words
        let contractions = s.model.contractions
        let isWord = s.kept(lower) || (words[lower] ?? 0) > 0 || contractions[lower] != nil
        let fix = s.kept(lower) ? nil : Autocorrect.best(lower, s)
        var out: [String] = []
        func add(_ w: String) { if !out.contains(w) { out.append(w) } }
        if let fix = fix { add(Autocorrect.recase(typed, fix)) }
        if isWord { add(Autocorrect.recase(typed, Autocorrect.capitalI(contractions[lower] ?? lower))) }
        // Completions: the commonest few words starting with what is typed.
        let arr = s.model.sorted
        let prefix = Array(lower.utf8)
        var lo = 0, hi = arr.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if arr[mid].utf8.lexicographicallyPrecedes(prefix) { lo = mid + 1 } else { hi = mid }
        }
        var top: [(word: String, zipf: Float)] = []
        var scanned = 0
        while lo < arr.count && arr[lo].utf8.starts(with: prefix) && scanned < 30_000 {
            let cand = arr[lo]
            if cand != lower { top.append((cand, s.slang.contains(cand) ? s.slangZipf(cand) : words[cand] ?? 0)) }
            lo += 1; scanned += 1
        }
        for l in s.learned where l.hasPrefix(lower) && l != lower { top.append((l, Autocorrect.learnedZipf)) }
        for l in s.slang where l.hasPrefix(lower) && l != lower && words[l] == nil { top.append((l, Autocorrect.slangZipf)) }
        // Commonest first; equals in the order found, then by spelling, so the row never reshuffles.
        let ranked = top.enumerated().sorted { a, b in a.element.zipf != b.element.zipf ? a.element.zipf > b.element.zipf : a.offset < b.offset }
        for (_, cand) in ranked {
            if out.count >= 5 { break }
            add(Autocorrect.recase(typed, Autocorrect.capitalI(cand.word)))
        }
        return Suggestions(words: out, fix: fix.map { Autocorrect.recase(typed, $0) }, typedIsWord: isWord)
    }

    // MARK: swipe typing

    private typealias Pt = (x: CGFloat, y: CGFloat)

    /// Swipe typing: the likeliest words for a finger's `path` over the letters, best first. Each candidate starts
    /// near where the finger came down and ends near where it lifted; its ideal path (straight lines through its
    /// letters' keys) is compared with the finger's, both resampled to the same number of points, and the mean
    /// distance in key widths weighed against how common the word is. `keys` is each lower-case letter's key centre,
    /// in the path's coordinates; `keyW` one key's width.
    public func swipe(path: [CGPoint], keys: [Character: CGPoint], keyW: CGFloat) -> [String] {
        guard path.count >= 2, keyW > 0, let s = snapshot() else { return [] }
        let n = 40
        let user = Autocorrect.resample(path.map { ($0.x, $0.y) }, n)
        let start = user[0], end = user[n - 1]
        func near(_ p: Pt, _ limit: CGFloat) -> [Character] {
            var found: [(c: Character, d: CGFloat)] = []
            for (c, k) in keys {
                let d: CGFloat = Autocorrect.dist(p, (k.x, k.y)) / keyW
                if d <= limit { found.append((c, d)) }
            }
            found.sort { a, b in a.d != b.d ? a.d < b.d : a.c < b.c }
            return found.prefix(4).map { $0.c }
        }
        let starts = near(start, 1.1), ends = near(end, 1.1)
        let words = s.model.words
        // a to z's keys by letter, for the candidates' paths.
        var letterKeys = [Pt?](repeating: nil, count: 26)
        for (c, p) in keys { if let a = c.asciiValue, a >= 97, a <= 122 { letterKeys[Int(a - 97)] = (p.x, p.y) } }
        var scored: [(word: String, score: CGFloat)] = []
        var pts: [Pt] = []
        for a in starts {
            for b in ends {
                guard let ai = a.asciiValue, let bi = b.asciiValue, ai >= 97, ai <= 122, bi >= 97, bi <= 122 else { continue }
                for index in s.model.byEnds[Int(ai - 97) * 26 + Int(bi - 97)] {
                    let word = s.model.sorted[Int(index)]
                    let plain = Autocorrect.fold(word)
                    // The word's keys, a doubled letter once ("hello" glides h-e-l-o).
                    pts.removeAll(keepingCapacity: true)
                    var prev: UInt8 = 32
                    for c in plain.utf8 {
                        if c != prev {
                            guard c >= 97, c <= 122, let k = letterKeys[Int(c - 97)] else { break }
                            pts.append(k)
                        }
                        prev = c
                    }
                    if pts.isEmpty { continue }
                    // Quick test: every letter passes near the path, in order.
                    var at = 0, ok = true
                    for p in pts {
                        while at < n && Autocorrect.dist(user[at], p) > keyW * 1.25 { at += 1 }
                        if at >= n { ok = false; break }
                    }
                    if !ok { continue }
                    let ideal = pts.count == 1 ? [Pt](repeating: pts[0], count: n) : Autocorrect.resample(pts, n)
                    var d: CGFloat = 0
                    for i in 0..<n { d += Autocorrect.dist(user[i], ideal[i]) }
                    d /= CGFloat(n) * keyW
                    let zipf = CGFloat(words[word] ?? Autocorrect.learnedZipf)
                    scored.append((word, zipf * 0.55 - d * 6))
                }
            }
        }
        for l in s.learned.sorted() {
            // Learned words glide too: scored the same way when their ends match.
            guard l.count >= 2, let first = l.first, let last = l.last, starts.contains(first), ends.contains(last), words[l] == nil else { continue }
            let lp: [Pt] = l.compactMap { keys[$0].map { ($0.x, $0.y) } }
            if lp.count != l.count { continue }
            let ideal = Autocorrect.resample(lp, n)
            var d: CGFloat = 0
            for i in 0..<n { d += Autocorrect.dist(user[i], ideal[i]) }
            scored.append((l, CGFloat(Autocorrect.learnedZipf) * 0.55 - d / (CGFloat(n) * keyW) * 6))
        }
        let ranked = scored.enumerated().sorted { a, b in a.element.score != b.element.score ? a.element.score > b.element.score : a.offset < b.offset }
        var out: [String] = []
        for (_, item) in ranked {
            let w = Autocorrect.capitalI(item.word)
            if !out.contains(w) { out.append(w) }
            if out.count >= 5 { break }
        }
        return out
    }

    private static func dist(_ a: Pt, _ b: Pt) -> CGFloat { hypot(a.x - b.x, a.y - b.y) }

    /// `pts` as `n` points evenly spaced along its length.
    private static func resample(_ pts: [Pt], _ n: Int) -> [Pt] {
        var total: CGFloat = 0
        for i in 1..<max(1, pts.count) { total += dist(pts[i - 1], pts[i]) }
        if total <= 0 { return [Pt](repeating: pts[0], count: n) }
        let step = total / CGFloat(n - 1)
        var out: [Pt] = []
        out.reserveCapacity(n)
        out.append(pts[0])
        var acc: CGFloat = 0
        var i = 1
        var prev = pts[0]
        while i < pts.count && out.count < n {
            let cur = pts[i]
            let d = dist(prev, cur)
            if acc + d >= step && d > 0 {
                let t = (step - acc) / d
                let q: Pt = (prev.x + t * (cur.x - prev.x), prev.y + t * (cur.y - prev.y))
                out.append(q); prev = q; acc = 0
            } else { acc += d; prev = cur; i += 1 }
        }
        while out.count < n { out.append(pts[pts.count - 1]) }
        return out
    }

    // MARK: the fix

    private static func best(_ lower: String, _ s: Snapshot) -> String? {
        if lower == "i" { return "I" }
        let words = s.model.words, contractions = s.model.contractions, folded = s.model.folded
        if let c = contractions[lower] { return capitalI(c) }
        let known = words[lower]
        // Typed without its accents: the accented word, unless the plain spelling is itself as common.
        if let accented = folded[lower], known == nil || known! < common { return accented }
        // A common word is never changed; a rare one only for a far commoner word one cheap slip away.
        if let known = known, known >= common {
            let cap = capitalI(lower)
            return cap != lower ? cap : nil
        }
        var bestWord: String?
        var bestScore = -Float.infinity
        for (candidate, cost) in candidates(lower) {
            let inWords = words[candidate]
            guard let zipf = inWords ?? contractions[candidate].flatMap({ words[$0] })
                ?? (s.learned.contains(candidate) ? learnedZipf : nil) ?? folded[candidate].flatMap({ words[$0] }) else { continue }
            if let known = known, cost > 1.5 || zipf < known + 2 { continue }
            let score = zipf - cost * costWeight
            let word = contractions[candidate] ?? (inWords == nil ? folded[candidate] : nil) ?? candidate
            // Equal scores go to the earlier spelling, so the same slip always gets the same fix.
            if score > bestScore || (score == bestScore && bestWord != nil && word < bestWord!) { bestScore = score; bestWord = word }
        }
        guard let found = bestWord, bestScore >= minScore else { return nil }
        return capitalI(found)
    }

    /// I, I'm, I've, I'll, I'd: English capitalises them wherever they fall.
    private static func capitalI(_ word: String) -> String {
        word == "i" || word.hasPrefix("i'") ? "I" + word.dropFirst() : word
    }

    /// The typed word's capitals on the correction: all, first, or none.
    private static func recase(_ typed: String, _ fixed: String) -> String {
        if typed.count > 1 && typed.allSatisfy({ !$0.isLetter || $0.isUppercase }) { return fixed.uppercased() }
        if let first = typed.first, first.isUppercase, let f = fixed.first { return f.uppercased() + fixed.dropFirst() }
        return fixed
    }

    /// Every word one slip from `word`, and two neighbouring-key slips from it, with what the slips cost.
    private static func candidates(_ word: String) -> [String: Float] {
        let w = Array(word.unicodeScalars)
        let n = w.count
        var out: [String: Float] = [:]
        out.reserveCapacity(n * 240)
        var buf = [Unicode.Scalar]()
        buf.reserveCapacity(n + 1)
        func offer(_ cost: Float) {
            if buf.isEmpty { return }
            var view = String.UnicodeScalarView()
            view.append(contentsOf: buf)
            let s = String(view)
            if s == word { return }
            if let had = out[s] { if cost < had { out[s] = cost } } else { out[s] = cost }
        }
        for i in 0..<n {
            // A letter too many: cheap when it doubles its neighbour or sits beside it on the keyboard.
            let extra = w[i]
            let cheap = (i > 0 && (w[i - 1] == extra || near(w[i - 1], extra))) || (i + 1 < n && (w[i + 1] == extra || near(w[i + 1], extra)))
            // A first letter is rarely the slip (npm is not pm, async is not sync).
            buf = w; buf.remove(at: i)
            offer(i == 0 ? 2.2 : cheap ? 1.0 : 1.6)
            // The wrong letter: a neighbour is the commonest slip.
            buf = w
            for c in alphabet where c != w[i] {
                buf[i] = c
                offer(near(w[i], c) ? 1.0 : 2.4)
            }
            // Two letters swapped.
            if i + 1 < n {
                buf = w; buf.swapAt(i, i + 1)
                offer(1.2)
            }
        }
        // A letter missed.
        for i in 0...n {
            buf = w; buf.insert("a", at: i)
            for c in alphabet { buf[i] = c; offer(1.7) }
        }
        // Two neighbouring-key slips in one word.
        if n >= 3 {
            for i in 0..<n {
                for a in neighbours[w[i]] ?? [] {
                    buf = w; buf[i] = a
                    for j in (i + 1)..<n {
                        let keep = buf[j]
                        for b in neighbours[w[j]] ?? [] { buf[j] = b; offer(2.0) }
                        buf[j] = keep
                    }
                }
            }
        }
        return out
    }

    private static func near(_ a: Unicode.Scalar, _ b: Unicode.Scalar) -> Bool { neighbours[a]?.contains(b) == true }

    /// Zipf 3.2 is about one in 600,000 words: below it a "word" in the list is as likely a common misspelling.
    static let common: Float = 3.2
    static let costWeight: Float = 1.6
    static let minScore: Float = 0.8
    private static let alphabet: [Unicode.Scalar] = Array("abcdefghijklmnopqrstuvwxyz".unicodeScalars)
    /// A learned word counts as moderately common (about one in 30,000 words) when a slip lands near it.
    static let learnedZipf: Float = 4.0
    /// Slang ranks as about one in 50,000 words among completions; it is never a target for correcting a slip.
    static let slangZipf: Float = 3.7

    /// `word` without its accents: é → e, ñ → n, ß → ss.
    public static func fold(_ word: String) -> String {
        if word.utf8.allSatisfy({ $0 < 128 }) { return word }
        let nfd = word.replacingOccurrences(of: "ß", with: "ss").decomposedStringWithCanonicalMapping
        var view = String.UnicodeScalarView()
        for u in nfd.unicodeScalars where u.properties.generalCategory != .nonspacingMark { view.append(u) }
        return String(view)
    }

    static let devWords: Set<String> = Set("""
        npm npx pnpm yarn pip pipx venv conda brew apt sudo ssh scp rsync git github gitlab repo repos rebase
        diff diffs linter lint eslint prettier webpack vite deno bun nginx caddy redis postgres postgresql mongo
        mongodb sqlite mysql docker dockerfile kubectl helm terraform async await const enum enums struct structs
        impl impls bool bools int ints str def fn func var vars args kwargs params init lambda lambdas nullable
        uri uris url urls json yaml toml xml csv http https api apis sdk sdks cli gui ui ux regex stdout stderr
        stdin src dev prod config configs env auth oauth jwt sql nosql db dbs ios macos xcode kotlin dart rust
        golang typescript javascript tsx jsx html css scss dom vm vms tcp udp dns cdn ci cd pr prs readme
        changelog localhost backend frontend middleware runtime namespace namespaces typedef boolean booleans
        println printf stringify endpoint endpoints webhook webhooks dotfiles gradle flutter widget widgets
        refactor refactored refactoring deploy deployed deploys repo's monorepo submodule codebase
        """.split(whereSeparator: { $0.isWhitespace }).map(String.init))

    /// Each letter's QWERTY neighbours, Gboard's stagger: the rows sit half a key and a key and a half in.
    static let neighbours: [Unicode.Scalar: [Unicode.Scalar]] = {
        let rows: [(String, Float)] = [("qwertyuiop", 0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)]
        var at: [Unicode.Scalar: (x: Float, row: Int)] = [:]
        for (r, row) in rows.enumerated() {
            for (i, c) in row.0.unicodeScalars.enumerated() { at[c] = (Float(i) + row.1, r) }
        }
        var out: [Unicode.Scalar: [Unicode.Scalar]] = [:]
        for (c, p) in at {
            out[c] = at.filter { o, q in o != c && abs(q.row - p.row) <= 1 && abs(q.x - p.x) <= 1.01 }.keys.sorted { $0.value < $1.value }
        }
        return out
    }()
}
