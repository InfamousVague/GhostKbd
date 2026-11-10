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
/// Mockingbird reads the same dictionaries to take slips out of its writing samples (`slip`, `lexicon()`): a far
/// stricter judgement than `correct`'s, since nobody is there to undo it. A word the lists miss is not a slip for
/// that alone.
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
        /// What a word looks like (`runs`), and the dictionaries it was read off.
        var runs: [UInt64] = []
        var runsModel: Model?
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
        let list = readSlang()
        state.with { s in
            s.slangList = list
            if s.slangOn { s.slang = list }
        }
    }

    /// The slang list from its file. A kilobyte: read where it is asked for.
    private func readSlang() -> Set<String> {
        var list = Set<String>()
        if let text = try? String(contentsOf: dictDirectory.appendingPathComponent("en_slang.txt"), encoding: .utf8) {
            for line in text.split(whereSeparator: { $0.isNewline }) {
                let word = line.trimmingCharacters(in: .whitespaces).lowercased()
                if !word.isEmpty && !word.hasPrefix("#") { list.insert(word) }
            }
        }
        return list
    }

    /// The whole slang list, its switch on or off: read once, when first asked for.
    private func slangList() -> Set<String> {
        if let list = state.with({ $0.slangList }) { return list }
        let list = readSlang()
        return state.with { s in
            if s.slangList == nil { s.slangList = list }
            return s.slangList ?? list
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
        // A whole phrase in one glide ("hey what's up" without lifting): offered first where it fits clearly better
        // than any one word, so a long word that happens to spell two ("hardship", "hard ship") stays one.
        if let phrase = swipePhrase(path.map { ($0.x, $0.y) }, letterKeys: letterKeys, keyW: keyW, s: s),
           phrase.score > (ranked.first?.element.score ?? -.infinity) + Autocorrect.phraseMargin {
            out.append(phrase.text)
        }
        for (_, item) in ranked {
            let w = Autocorrect.capitalI(item.word)
            if !out.contains(w) { out.append(w) }
            if out.count >= 5 { break }
        }
        return out
    }

    /// How much better a phrase must score than the best single word to be offered first, and what each word past
    /// the first costs it: a glide is read as one word unless several fit it plainly better.
    static let phraseMargin: CGFloat = 0.6
    static let phraseWordCost: CGFloat = 0.5

    /// One glide read as several words, the finger never lifting between them. A word ends and the next begins at a
    /// corner of the path (the finger turns from the last letter towards the next word's first), so the path's corners
    /// are where it may be cut; each stretch between two corners is matched as one word, as a single glide is, and
    /// the cuts that make the best words win. Words of one letter are not found this way.
    private func swipePhrase(_ raw: [Pt], letterKeys: [Pt?], keyW: CGFloat, s: Snapshot) -> (text: String, score: CGFloat)? {
        guard raw.count >= 2 else { return nil }
        var length: CGFloat = 0
        for i in 1..<raw.count { length += Autocorrect.dist(raw[i - 1], raw[i]) }
        // Two short words are at least this much glide.
        guard length > keyW * 3 else { return nil }
        let m = min(240, max(16, Int(length / (keyW * 0.25)) + 1))
        let p = Autocorrect.resample(raw, m)
        let step = length / CGFloat(m - 1)
        // How sharply the path turns at each point, over a span of about three quarters of a key either side.
        let w = 3
        var turn = [CGFloat](repeating: 0, count: m)
        if m > 2 * w {
            for i in w..<(m - w) {
                let ax = p[i].x - p[i - w].x, ay = p[i].y - p[i - w].y, bx = p[i + w].x - p[i].x, by = p[i + w].y - p[i].y
                let la = hypot(ax, ay), lb = hypot(bx, by)
                if la > 0 && lb > 0 { turn[i] = acos(max(-1, min(1, (ax * bx + ay * by) / (la * lb)))) }
            }
        }
        var anchors = [0]
        if m > 2 * w {
            for i in w..<(m - w) where turn[i] > 0.8 {
                let lo = max(0, i - w), hi = min(m - 1, i + w)
                guard turn[i] >= turn[lo...hi].max() ?? 0 else { continue }
                if let last = anchors.last, CGFloat(i - last) * step < keyW * 0.6 { continue }
                anchors.append(i)
            }
        }
        if let last = anchors.last, CGFloat(m - 1 - last) * step < keyW * 0.6, anchors.count > 1 { anchors.removeLast() }
        anchors.append(m - 1)
        let count = anchors.count
        guard count >= 3, count <= 24 else { return nil }
        func near(_ q: Pt) -> [Int] {
            var found: [(i: Int, d: CGFloat)] = []
            for (i, k) in letterKeys.enumerated() { if let k { let d = Autocorrect.dist(q, k) / keyW; if d <= 1.1 { found.append((i, d)) } } }
            return found.sorted { $0.d < $1.d }.prefix(3).map { $0.i }
        }
        // A word starts at a corner, or where the path runs over a key on its way between two corners (the next word's
        // first letter can lie on the straight line from the last word's end: "what's up" has its u between s and p).
        var nearest = [CGFloat](repeating: .infinity, count: m)
        for i in 0..<m { for k in letterKeys { if let k { nearest[i] = min(nearest[i], Autocorrect.dist(p[i], k) / keyW) } } }
        var heads = Set(anchors.dropLast())
        for i in 1..<(m - 1) where nearest[i] < 0.4 && nearest[i] <= nearest[i - 1] && nearest[i] <= nearest[i + 1] {
            if anchors.contains(where: { CGFloat(abs($0 - i)) * step < keyW * 0.6 }) { continue }
            heads.insert(i)
        }
        var cum = [CGFloat](repeating: 0, count: m)
        for i in 1..<m { cum[i] = cum[i - 1] + Autocorrect.dist(p[i - 1], p[i]) }
        let n = 24, words = s.model.words
        let ends = anchors.map { near(p[$0]) }
        // The best word from each head to each corner after it, at most seven corners on.
        var stretch: [Int: (word: String, score: CGFloat)] = [:]
        var pts: [Pt] = []
        for i0 in heads.sorted() {
            let firsts = near(p[i0])
            guard let after = anchors.firstIndex(where: { $0 > i0 }) else { continue }
            for b in after..<min(count, after + 7) {
                let i1 = anchors[b]
                let span = cum[i1] - cum[i0]
                let sub = Array(p[i0...i1])
                let user = Autocorrect.resample(sub, n)
                var top: (word: String, score: CGFloat)?
                for f in firsts {
                    for l in ends[b] {
                        for index in s.model.byEnds[f * 26 + l] {
                            let word = s.model.sorted[Int(index)]
                            pts.removeAll(keepingCapacity: true)
                            var prev: UInt8 = 32, ok = true
                            for c in Autocorrect.fold(word).utf8 where c != prev {
                                guard c >= 97, c <= 122, let k = letterKeys[Int(c - 97)] else { ok = false; break }
                                pts.append(k); prev = c
                            }
                            guard ok, pts.count >= 2 else { continue }
                            var ideal: CGFloat = 0
                            for i in 1..<pts.count { ideal += Autocorrect.dist(pts[i - 1], pts[i]) }
                            guard ideal > 0, span / ideal > 0.7, span / ideal < 1.6 else { continue }
                            var at = 0
                            for q in pts {
                                while at < sub.count && Autocorrect.dist(sub[at], q) > keyW * 1.25 { at += 1 }
                                if at >= sub.count { ok = false; break }
                            }
                            guard ok else { continue }
                            let shape = Autocorrect.resample(pts, n)
                            var d: CGFloat = 0
                            for i in 0..<n { d += Autocorrect.dist(user[i], shape[i]) }
                            d /= CGFloat(n) * keyW
                            let score = CGFloat(words[word] ?? Autocorrect.learnedZipf) * 0.55 - d * 6
                            if top == nil || score > top!.score { top = (word, score) }
                        }
                    }
                }
                if let top { stretch[i0 * 64 + b] = top }
            }
        }
        // The best k words ending at corner b; the next starts at that corner, or at a head before the next corner.
        let most = 5
        var best = [[CGFloat]](repeating: [CGFloat](repeating: -.infinity, count: count), count: most + 1)
        var back = [[(prev: Int, word: String)?]](repeating: [(prev: Int, word: String)?](repeating: nil, count: count), count: most + 1)
        for b in 1..<count { if let t = stretch[b] { best[1][b] = t.score; back[1][b] = (-1, t.word) } }
        let sortedHeads = heads.sorted()
        for k in 1..<most {
            for b in 1..<(count - 1) where best[k][b] > -.infinity {
                for h in sortedHeads where h >= anchors[b] && h <= anchors[b + 1] {
                    for e in (b + 1)..<count {
                        guard let t = stretch[h * 64 + e] else { continue }
                        let total = best[k][b] + t.score
                        if total > best[k + 1][e] { best[k + 1][e] = total; back[k + 1][e] = (b, t.word) }
                    }
                }
            }
        }
        var pick: (k: Int, score: CGFloat)?
        for k in 2...most where best[k][count - 1] > -.infinity {
            let score = best[k][count - 1] / CGFloat(k) - Autocorrect.phraseWordCost * CGFloat(k - 1)
            if pick == nil || score > pick!.score { pick = (k, score) }
        }
        guard let pick else { return nil }
        var said: [String] = []
        var k = pick.k, b = count - 1
        while k > 0, let step = back[k][b] {
            said.append(Autocorrect.capitalI(s.model.contractions[step.word] ?? step.word))
            b = step.prev; k -= 1
        }
        return (said.reversed().joined(separator: " "), pick.score)
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
        guard let pick = pick(lower, known: known, s), pick.score >= minScore else { return nil }
        return capitalI(pick.word)
    }

    /// The likeliest word a slip or two from a typed one: its `score`, and the `next` best word's score.
    private struct Pick {
        let word: String
        let score: Float
        let next: Float
    }

    /// The best-scoring word among those a slip or two from `lower`, and the score of the runner-up. `known` is
    /// `lower`'s own frequency when the lists have it as a rare word: then only a cheap slip to a far commoner word
    /// can win, though any word still counts as a runner-up.
    private static func pick(_ lower: String, known: Float?, _ s: Snapshot) -> Pick? {
        let words = s.model.words, contractions = s.model.contractions, folded = s.model.folded
        var found: [(word: String, score: Float, mayWin: Bool)] = []
        var bestWord: String?
        var bestScore = -Float.infinity
        for (candidate, cost) in candidates(lower) {
            let inWords = words[candidate]
            guard let zipf = inWords ?? contractions[candidate].flatMap({ words[$0] })
                ?? (s.learned.contains(candidate) ? learnedZipf : nil) ?? folded[candidate].flatMap({ words[$0] }) else { continue }
            let score = zipf - cost * costWeight
            let word = contractions[candidate] ?? (inWords == nil ? folded[candidate] : nil) ?? candidate
            let mayWin = known.map { cost <= 1.5 && zipf >= $0 + 2 } ?? true
            found.append((word, score, mayWin))
            // Equal scores go to the earlier spelling, so the same slip always gets the same fix.
            if mayWin && (score > bestScore || (score == bestScore && bestWord != nil && word < bestWord!)) { bestScore = score; bestWord = word }
        }
        guard let best = bestWord else { return nil }
        var next = -Float.infinity
        for f in found where f.word != best && f.score > next { next = f.score }
        return Pick(word: best, score: bestScore, next: next)
    }

    // MARK: slips in text already written (Mockingbird's samples)

    /// For text already written (Mockingbird's samples), where `correct` is for a word as it is typed: the one word
    /// `typed` was plainly a slip of, in `typed`'s capitals, or nil when it should stand. Nobody is there to undo it,
    /// and a real word, a name or a term can be missing from the lists, so "not in the lists" is never enough: the
    /// slip itself has to show.
    /// - `correct`'s own guards hold (ACRONYMS, camelCase, learned words), and slang counts whether or not its
    ///   switch is on;
    /// - nothing that is a way of writing rather than a slip: no "i" → "I", no apostrophe put into "dont", no
    ///   accent put onto "cafe";
    /// - a spelling the lists hold, however rare, is somebody's word (untied, prefect, strat, nite) and stands,
    ///   whatever commoner word it resembles. The exceptions are named: `listedSlips`;
    /// - a spelling they do not hold is fixed only for one of three slips (`shape`): two letters next to each other
    ///   swapped ("taht", "tomorrwo"), one neighbouring key struck inside the word ("keybkard"), or a consonant
    ///   doubled. The last two only in a word of six letters or more: a short word has too many real neighbours
    ///   (vim beside him), and a different first or last letter is as often another word (linted, linter);
    /// - a word of six letters or more must also not look like a word (`odd`): drooping, vishal and minuet do, and
    ///   are somebody's, however near dropping, visual and minute they sit;
    /// - the fix must be likely (`slipScore`; `slipScoreShort` for a word of five letters or fewer, so only the
    ///   very commonest words are put back: "pani" is not "pain") and well ahead of the next word it could have
    ///   been (`slipMargin`): "hwo" is who or how, so it stands.
    public func slip(_ typed: String) -> String? {
        let length = typed.utf16.count
        guard length >= 3, length <= 24, let s = snapshot() else { return nil }
        if typed.allSatisfy({ $0.isUppercase }) || typed.dropFirst().contains(where: { $0.isUppercase }) { return nil }
        let lower = typed.lowercased()
        if Autocorrect.isWord(lower, s.model, learned: s.learned, slang: slangList()) { return nil }
        let fixed: String
        if s.model.words[lower] != nil {
            guard let listed = Autocorrect.listedSlips[lower], (s.model.words[listed] ?? 0) >= Autocorrect.common else { return nil }
            fixed = listed
        } else {
            let letters = Array(lower.unicodeScalars)
            // Asked first, being cheap: most long words the lists miss look like words, and stand.
            if letters.count > Autocorrect.short && !odd(letters, s.model) { return nil }
            guard let pick = Autocorrect.pick(lower, known: nil, s) else { return nil }
            if !Autocorrect.shape(letters, Array(pick.word.unicodeScalars)) { return nil }
            let bar = letters.count <= Autocorrect.short ? Autocorrect.slipScoreShort : Autocorrect.slipScore
            if pick.score < bar || pick.score - pick.next < Autocorrect.slipMargin { return nil }
            fixed = pick.word
        }
        let cased = Autocorrect.recase(typed, fixed)
        return cased == typed ? nil : cased
    }

    /// Whether one of the slips `slip` takes for accidents turns `fix` into `typed` (both lower case):
    /// - two letters next to each other swapped, in a word of any length;
    /// - one neighbouring key in place of the right one, in a word longer than `short` and not at its first or
    ///   last letter;
    /// - a consonant typed twice, in a word longer than `short`. A doubled vowel is a word stretched (whaat, loove).
    /// A dropped letter, a far key and a letter too many are left: abbreviations and other words look the same.
    private static func shape(_ typed: [Unicode.Scalar], _ fix: [Unicode.Scalar]) -> Bool {
        let n = typed.count
        if n == fix.count {
            guard let i = typed.indices.first(where: { typed[$0] != fix[$0] }), let j = typed.indices.last(where: { typed[$0] != fix[$0] }) else { return false }
            if j == i + 1 && typed[i] == fix[j] && typed[j] == fix[i] { return true }
            return j == i && n > short && i > 0 && i < n - 1 && near(typed[i], fix[i])
        }
        if n == fix.count + 1 && n > short {
            guard let i = typed.indices.first(where: { $0 >= fix.count || typed[$0] != fix[$0] }), i > 0 else { return false }
            if typed[i] != typed[i - 1] || vowels.contains(typed[i]) { return false }
            var without = typed
            without.remove(at: i)
            return without == fix
        }
        return false
    }

    /// The runs of three letters, a word's two edges included, that the common words of the loaded languages hold:
    /// what a word looks like, a bit for each run. Made at `odd`'s first use and again when the dictionaries change,
    /// so the keyboard's own loading never pays for it. Under three kilobytes.
    private func runs(_ model: Model) -> [UInt64] {
        if let made: [UInt64] = state.with({ $0.runsModel === model ? $0.runs : nil }) { return made }
        let letters = Autocorrect.runLetters
        var bits = [UInt64](repeating: 0, count: (letters * letters * letters + 63) / 64)
        // A programmer's abbreviations (npm, kubectl) are words, but not what a word looks like.
        for (word, zipf) in model.words where zipf >= Autocorrect.common && !Autocorrect.devWords.contains(word) {
            Autocorrect.eachRun(word.unicodeScalars) { bits[$0 >> 6] |= 1 << UInt64($0 & 63) }
        }
        state.with { $0.runs = bits; $0.runsModel = model }
        return bits
    }

    /// Each run of three letters in `word`, its edges included ("the": ^th, the, he$), as a number for `runs`.
    private static func eachRun<S: Sequence>(_ word: S, _ visit: (Int) -> Void) where S.Element == Unicode.Scalar {
        var first = 0
        var second = -1
        for c in word {
            let third = runLetter(c)
            if second >= 0 { visit((first * runLetters + second) * runLetters + third) }
            first = max(0, second); second = third
        }
        if second >= 0 { visit((first * runLetters + second) * runLetters) }
    }

    /// A letter's place in a run: 1 to 26 for a to z, 27 for anything else (an accent, an apostrophe); 0 is a word's edge.
    private static func runLetter(_ c: Unicode.Scalar) -> Int { c.value >= 97 && c.value <= 122 ? Int(c.value) - 96 : runLetters - 1 }

    /// `lower` does not look like a word: it holds a run of three letters that no common word of the loaded
    /// languages has ("ybk" in keybkard, "hp" opening hpuse). A real word the lists miss (vishal, drooping,
    /// curation) nearly always looks like one, which is how a neighbouring-key slip is told from it.
    private func odd(_ lower: [Unicode.Scalar], _ model: Model) -> Bool {
        let seen = runs(model)
        var odd = false
        Autocorrect.eachRun(lower) { if seen[$0 >> 6] & (1 << UInt64($0 & 63)) == 0 { odd = true } }
        return odd
    }

    private static func isWord(_ lower: String, _ model: Model, learned: Set<String>, slang: Set<String>) -> Bool {
        learned.contains(lower) || slang.contains(lower) || (model.words[lower] ?? 0) >= common || model.contractions[lower] != nil || model.folded[lower] != nil
    }

    /// `lower` is a word nobody would fix: common in a loaded language, a contraction typed without its apostrophe,
    /// a word spelled without its accents, a learned word, or texting slang (the whole list, its switch on or off).
    public func isWord(_ lower: String) -> Bool {
        let (model, learned) = state.with { ($0.model, $0.learned) }
        return Autocorrect.isWord(lower, model, learned: learned, slang: slangList())
    }

    /// These dictionaries as `SampleCleaner` reads them. Each fix is worked out once and remembered for as long as
    /// the returned object is kept, so make one per pass over the samples, not one to keep; use it from one thread.
    public func lexicon() -> SampleLexicon { SpellerLexicon(self) }

    private final class SpellerLexicon: SampleLexicon {
        private let speller: Autocorrect
        private var fixes: [String: String?] = [:]
        init(_ speller: Autocorrect) { self.speller = speller }
        var ready: Bool { speller.isReady }
        func isWord(_ lower: String) -> Bool { speller.isWord(lower) }
        func fix(_ token: String) -> String? {
            if let had = fixes[token] { return had }
            let fix = speller.slip(token)
            fixes[token] = fix
            return fix
        }
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
    /// `slip`'s bar: a fix this likely, and this far ahead of the next word it could have been.
    static let slipScore: Float = 2.4
    static let slipScoreShort: Float = 4.0
    static let slipMargin: Float = 1.0
    /// `slip`'s short word: up to this many letters. Only swapped letters are fixed in one.
    static let short = 5
    /// Doubled, these are a word stretched for effect, not a slip.
    private static let vowels = Set("aeiouy".unicodeScalars)
    /// `runLetter`'s range: a word's edge, a to z, and one for every other character.
    private static let runLetters = 28
    /// The spellings the lists do hold that nobody means: the commonest swaps, typed often enough to have got into
    /// the word counts. Every other listed spelling stands.
    static let listedSlips = ["teh": "the", "thier": "their", "recieve": "receive"]
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
