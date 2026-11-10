import Foundation

/// What the sample cleaner asks of the dictionaries: `Autocorrect.lexicon()` answers on the phone.
public protocol SampleLexicon: AnyObject {
    /// False until the dictionaries are loaded; nothing is cleaned without them.
    var ready: Bool { get }

    /// `lower` is a word nobody would fix: common in a language that is on, learned, or slang. It is also how a
    /// language with a dictionary is told from one without: a rare entry will not do, since the English list holds a
    /// little of every language ("heb", "buena", "gracias").
    func isWord(_ lower: String) -> Bool

    /// The one word `token` (letters and apostrophes) was plainly a slip of, in `token`'s capitals; nil to leave it.
    /// Being in no list is not enough for an answer: the slip itself must show.
    func fix(_ token: String) -> String?
}

/// Mockingbird's sample cleaner: takes the obvious accidental slips out of a sample before the AI learns from it, so
/// a one-off "teh" is not read as how the person writes.
///
/// A wrong repair changes what the person said, and nobody is there to undo it; a slip left in costs little, since
/// the AIs are told to disregard one (`VoiceRules`). So a word is repaired only when all of this holds, and anything
/// less sure is left exactly as typed:
/// - the sample reads as a language there is a dictionary for (`foreign`), judged from the words the dictionaries
///   know and nothing else, and the words on either side of this one are known too: a word of another language in an
///   English message is not a slip;
/// - it stands by itself between spaces, with at most ordinary punctuation or an emoji round it: nothing code-shaped
///   (a digit, an underscore, a dot or slash inside, an @ or #, a URL, an email address, a hyphenated compound);
/// - it is not a word in any language switched on, not a learned word and not texting slang (`SampleLexicon.isWord`);
/// - it is not a capitalised word anywhere but the plain start of a sentence, which is a name: after a line break,
///   an emoji or "Dr." a capital proves nothing, so it stands;
/// - it is not written for effect: a stretched or lengthened word (sooo, yess, heyy, whaat, musta), laughter
///   (hahah, lolol), or a vowel-less abbreviation (thx, tmrw);
/// - the person has not typed it repeatedly (`Habits.deliberate`: three times or more, in at least two samples),
///   which makes it theirs, for good;
/// - the dictionaries have one confident correction for it (`SampleLexicon.fix`: a slip that shows, not merely a word
///   they lack), and that correction changes letters only: it neither adds nor drops an apostrophe or an accent;
/// - the correction is a word the person uses (`Habits.used`): it is somewhere else in their samples, spelled right.
///
/// Punctuation, spacing, capitals, emoji and the choice of words are never touched: they are style. The fix keeps the
/// word's capitals ("Teh" → "The").
///
/// Text is read a Unicode scalar at a time, so an emoji is never a letter and a `Token`'s offsets count scalars.
public enum SampleCleaner {
    public typealias Lexicon = SampleLexicon

    /// The rules' version, kept with each cleaned sample: raise it and stored samples are cleaned again.
    public static let version = 2

    /// Typed this often, in this many different samples, a word that is in no dictionary is the person's own.
    public static let repeatedTimes = 3
    public static let repeatedSamples = 2

    /// A word of a sample that stands by itself: where it `start`s and `end`s (offsets in the text's Unicode
    /// scalars), and whether it plainly opens a sentence (the sample's first word, or the one after a full stop,
    /// ! ? or …).
    public struct Token: Equatable {
        public let start: Int
        public let end: Int
        public let word: String
        public let sentenceStart: Bool
        public init(start: Int, end: Int, word: String, sentenceStart: Bool) { self.start = start; self.end = end; self.word = word; self.sentenceStart = sentenceStart }
    }

    /// What the cleaner knows about the person from all their samples: the words they type on purpose although no
    /// dictionary has them (`deliberate`), and the words they have typed at all (`used`), both lower case.
    public struct Habits {
        public let deliberate: Set<String>
        public let used: Set<String>
        /// Every word counts as used: the samples at their least helpful, for checking the other guards alone.
        let everyWordUsed: Bool

        public init(deliberate: Set<String>, used: Set<String>) { self.deliberate = deliberate; self.used = used; self.everyWordUsed = false }

        init(deliberate: Set<String>, everyWordUsed: Bool) { self.deliberate = deliberate; self.used = []; self.everyWordUsed = everyWordUsed }

        func uses(_ lower: String) -> Bool { everyWordUsed || used.contains(lower) }
    }

    private typealias Scalars = [Unicode.Scalar]

    /// Characters that, touching a word, make it code-shaped: a handle, a tag, a path, a flag, markup.
    private static let codeBefore = Set("@#/\\._-:$`<>{}[]=|&%^+~".unicodeScalars)
    private static let codeAfter = Set("@#/\\_-$`<>{}[(=|&%^+~".unicodeScalars)
    private static let vowels = Set("aeiouy".unicodeScalars)

    private static func isLetter(_ c: Unicode.Scalar) -> Bool {
        if c.isASCII { return (c.value >= 97 && c.value <= 122) || (c.value >= 65 && c.value <= 90) }
        switch c.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter: return true
        default: return false
        }
    }

    private static func isLetterOrDigit(_ c: Unicode.Scalar) -> Bool { isLetter(c) || c.properties.generalCategory == .decimalNumber }

    private static func isSpace(_ c: Unicode.Scalar) -> Bool { c.properties.isWhitespace }

    /// An apostrophe, typed plain or curled: part of a word (don't), not its edge.
    private static func apostrophe(_ c: Unicode.Scalar) -> Bool { c == "'" || c == "\u{2019}" }

    /// Always ends a sentence. A full stop may not: it also closes an abbreviation ("Dr.", "etc.").
    private static func stop(_ c: Unicode.Scalar) -> Bool { c == "!" || c == "?" || c == "\u{2026}" }

    /// A plain word longer than this, with a full stop after it, ends a sentence; a shorter one may be "Dr." or "St.".
    private static let abbreviation = 3

    private static func string(_ scalars: ArraySlice<Unicode.Scalar>) -> String {
        var view = String.UnicodeScalarView()
        view.append(contentsOf: scalars)
        return String(view)
    }

    /// The words of `text` the cleaner may look at: each run of letters (and apostrophes) that fills its
    /// space-separated chunk but for punctuation at its edges. Code-shaped chunks give no token at all.
    ///
    /// A word opens a sentence only where that is plain: first in the text, or after ! ? …, or after a full stop
    /// that follows a word of four letters or more. A line break, an emoji and a short word with a full stop ("Dr.",
    /// "St.", "e.g.") prove nothing, so a capitalised word after one is taken for a name.
    public static func tokens(_ text: String) -> [Token] { tokens(Array(text.unicodeScalars)) }

    private static func tokens(_ text: Scalars) -> [Token] {
        var out: [Token] = []
        var i = 0
        var sentenceStart = true
        let n = text.count
        while i < n {
            if isSpace(text[i]) { i += 1; continue }
            var end = i
            while end < n && !isSpace(text[end]) { end += 1 }
            var a = i
            while a < end && !isLetterOrDigit(text[a]) { a += 1 }
            var b = end
            while b > a && !isLetterOrDigit(text[b - 1]) { b -= 1 }
            if a < b {
                let plain = text[a..<b].allSatisfy { isLetter($0) || apostrophe($0) } &&
                    !text[i..<a].contains(where: { codeBefore.contains($0) }) && !text[b..<end].contains(where: { codeAfter.contains($0) })
                if plain { out.append(Token(start: a, end: b, word: string(text[a..<b]), sentenceStart: sentenceStart)) }
                sentenceStart = text[b..<end].contains(where: { stop($0) }) || (plain && b - a > abbreviation && text[b..<end].contains("."))
            } else if text[i..<end].contains(where: { stop($0) || $0 == "." }) {
                sentenceStart = true
            }
            i = end
        }
        return out
    }

    /// `word` folded for looking up: lower case, a typographic apostrophe as a plain one.
    private static func key(_ word: String) -> String { word.lowercased().replacingOccurrences(of: "\u{2019}", with: "'") }

    /// Written for effect, not by accident: a letter three times running, or four letters and more made of two.
    private static func forEffect(_ lower: Scalars) -> Bool {
        if lower.count >= 3 { for i in 2..<lower.count where lower[i] == lower[i - 1] && lower[i] == lower[i - 2] { return true } }
        return lower.count >= 4 && Set(lower).count <= 2
    }

    /// Has a vowel (an accented letter counts as one): a word without any is an abbreviation, thx or tmrw.
    private static func hasVowel(_ lower: Scalars) -> Bool { lower.contains { vowels.contains($0) || $0.value > 127 } }

    /// `lower` is long enough, and plain enough, to be judged at all.
    private static func judged(_ lower: Scalars) -> Bool { lower.count >= 3 && lower.count <= 20 && !forEffect(lower) && hasVowel(lower) }

    /// `lower` is a word that could be a slip: plain enough to judge, and no dictionary's, learned or slang word.
    private static func suspect(_ lower: String, _ lexicon: Lexicon) -> Bool { judged(Array(lower.unicodeScalars)) && !lexicon.isWord(lower) }

    /// What `texts` (every sample, as typed) say about the person's words, in one pass over them. `Habits.deliberate`
    /// is the words no dictionary has that they typed `repeatedTimes` times or more, in at least `repeatedSamples`
    /// different texts; `Habits.used` is every word they typed. With `among`, only those words are looked for
    /// (`wanted`, for one new sample), which is all a single sample needs.
    public static func habits(_ texts: [String], _ lexicon: Lexicon, among: Set<String>? = nil) -> Habits {
        if !lexicon.ready || among?.isEmpty == true { return Habits(deliberate: [], used: []) }
        var times: [String: Int] = [:]
        var samples: [String: Int] = [:]
        for text in texts {
            var seen = Set<String>()
            for t in tokens(text) {
                let k = key(t.word)
                if let among = among, !among.contains(k) { continue }
                times[k, default: 0] += 1
                if seen.insert(k).inserted { samples[k, default: 0] += 1 }
            }
        }
        var deliberate = Set<String>()
        for (k, n) in times where n >= repeatedTimes && (samples[k] ?? 0) >= repeatedSamples && suspect(k, lexicon) { deliberate.insert(k) }
        return Habits(deliberate: deliberate, used: Set(times.keys))
    }

    /// The words of `text` the dictionaries would repair, and the words they would become (all lower case): what to
    /// look for in the other samples (`habits`) before anything is changed. Empty for a sample with nothing to
    /// repair, which is most of them.
    public static func wanted(_ text: String, _ lexicon: Lexicon) -> Set<String> {
        if !lexicon.ready { return [] }
        var out = Set<String>()
        for t in tokens(text) {
            guard let fix = candidate(t, lexicon) else { continue }
            out.insert(key(t.word)); out.insert(key(fix))
        }
        return out
    }

    /// True when `tokens` do not read as a language the dictionaries cover: more than a quarter of the words, and
    /// more than one, are not words the dictionaries know (`SampleLexicon.isWord`). It is judged from those words
    /// alone, never from what a word could be corrected to: a word of another language often sits one slip from an
    /// English one ("nog" beside "not"), and that says nothing. Names, and words stretched or abbreviated for effect,
    /// are not counted either way. A message that is a quarter slips is left too: there is no telling it from this.
    public static func foreign(_ tokens: [Token], _ lexicon: Lexicon) -> Bool {
        var words = 0
        var unknown = 0
        for t in tokens {
            let k = key(t.word)
            let scalars = Array(k.unicodeScalars)
            if name(t) || forEffect(scalars) || !hasVowel(scalars) { continue }
            words += 1
            if !lexicon.isWord(k) { unknown += 1 }
        }
        return unknown > max(1, words / 4)
    }

    /// A capital anywhere but the plain start of a sentence: a name, a place, a product. Not the cleaner's to respell.
    private static func name(_ token: Token) -> Bool { (token.word.unicodeScalars.first?.properties.isUppercase ?? false) && !token.sentenceStart }

    /// True when the word at `index` sits among words the dictionaries know: the word before it and the word after
    /// are each known (a name or the sample's edge passes), and at least one of them is a known word. A word whose
    /// neighbours are unknown too is likelier another language, a run of names or a term than a slip.
    private static func settled(_ tokens: [Token], _ index: Int, _ lexicon: Lexicon) -> Bool {
        let before: Token? = index > 0 ? tokens[index - 1] : nil
        let after: Token? = index + 1 < tokens.count ? tokens[index + 1] : nil
        func known(_ t: Token?) -> Bool { t.map { !name($0) && lexicon.isWord(key($0.word)) } ?? false }
        func open(_ t: Token?) -> Bool { t.map { name($0) } ?? true }
        return (known(before) || open(before)) && (known(after) || open(after)) && (known(before) || known(after))
    }

    /// `lower` is `fixed` with one of its vowels typed twice ("whaat", "loove"): a word stretched, not a slip.
    private static func stretched(_ lower: Scalars, _ fixed: Scalars) -> Bool {
        if lower.count != fixed.count + 1 { return false }
        for i in 1..<lower.count where lower[i] == lower[i - 1] && vowels.contains(lower[i]) {
            var without = lower
            without.remove(at: i)
            if without == fixed { return true }
        }
        return false
    }

    /// The characters of `lower` that are not plain letters (apostrophes, accented letters), in order.
    private static func marks(_ lower: Scalars) -> [UInt32] { lower.filter { $0.value < 97 || $0.value > 122 }.map { $0.value }.sorted() }

    /// What the dictionaries alone make of `token`: the word it was a slip of, or nil when it stands. Nothing about
    /// the person or the rest of the sample is asked here; `slipFix` does that.
    private static func candidate(_ token: Token, _ lexicon: Lexicon) -> String? {
        let word = token.word
        let lower = key(word)
        if !suspect(lower, lexicon) || name(token) { return nil }
        guard let fix = lexicon.fix(word) else { return nil }
        let fixed = key(fix)
        if fixed == lower || fix.unicodeScalars.contains(where: { isSpace($0) }) { return nil }
        let l = Array(lower.unicodeScalars), f = Array(fixed.unicodeScalars)
        // Apostrophes and accents are how someone writes: a fix may move letters, never add or drop those.
        if marks(l) != marks(f) { return nil }
        // "heyy", "yess", "musta": a word with more on its end is said that way, not slipped.
        if l.count > f.count && l.starts(with: f) { return nil }
        // "whaat", "loove": so is one with a vowel doubled.
        if stretched(l, f) { return nil }
        return fix
    }

    /// The decision for the word at `index` of a sample's `tokens`: what it should have been, or nil when it stands.
    /// `habits` is what the person's samples say (`habits(_:_:among:)`): a word they type repeatedly stands, and a
    /// fix is made only to a word they use. The sample-wide test (`foreign`) is `clean`'s.
    public static func slipFix(_ tokens: [Token], _ index: Int, _ lexicon: Lexicon, _ habits: Habits) -> String? {
        let token = tokens[index]
        if habits.deliberate.contains(key(token.word)) { return nil }
        guard let fix = candidate(token, lexicon) else { return nil }
        if !habits.uses(key(fix)) || !settled(tokens, index, lexicon) { return nil }
        return fix
    }

    /// A new sample cleaned: its `text`, the words `kept` as the person's own afterwards, and whether one is `fresh`.
    public struct Cleaned: Equatable {
        public let text: String
        public let kept: Set<String>
        public let fresh: Bool
    }

    /// One new sample, `typed`, with its obvious slips repaired. `others` is every other sample as typed: the words
    /// that would be changed, and what they would become, are looked for across them first (`habits`). `kept` is the
    /// words judged the person's own before now (`Habits.deliberate`, remembered so that a sample dropping off the
    /// end cannot undo it). When a word of this sample has just become one, `Cleaned.fresh` says so: the samples it
    /// was taken out of earlier need `cleanAll` to get it back.
    public static func cleanNew(_ typed: String, others: [String], _ lexicon: Lexicon, kept: Set<String>) -> Cleaned {
        let wanted = wanted(typed, lexicon)
        if wanted.isEmpty { return Cleaned(text: typed, kept: kept, fresh: false) }
        let seen = habits(others + [typed], lexicon, among: wanted)
        let all = kept.union(seen.deliberate)
        return Cleaned(text: clean(typed, lexicon, Habits(deliberate: all, used: seen.used)), kept: all, fresh: all.count > kept.count)
    }

    /// Every sample cleaned again from what was `typed`, with the habits counted over all of them: the texts in the
    /// same order, and `kept` with the words that have since become the person's own. A word in `kept` stays theirs
    /// however few samples still hold it.
    public static func cleanAll(_ typed: [String], _ lexicon: Lexicon, kept: Set<String>) -> (texts: [String], kept: Set<String>) {
        if !lexicon.ready { return (typed, kept) }
        let seen = habits(typed, lexicon)
        // Only a word the dictionaries would change needs remembering: any other stands whatever is kept.
        let all = kept.union(seen.deliberate.filter { lexicon.fix($0) != nil })
        let habits = Habits(deliberate: seen.deliberate.union(all), used: seen.used)
        return (typed.map { clean($0, lexicon, habits) }, all)
    }

    /// `text` with its obvious slips repaired, and everything else as it was. `habits` is what all the person's
    /// samples, as typed, say about their words. Unchanged when the dictionaries are not loaded or do not cover the
    /// sample's language. Cleaning a cleaned text changes nothing.
    public static func clean(_ text: String, _ lexicon: Lexicon, _ habits: Habits) -> String {
        if !lexicon.ready { return text }
        var scalars = Array(text.unicodeScalars)
        let tokens = tokens(scalars)
        if tokens.isEmpty || foreign(tokens, lexicon) { return text }
        var changed = false
        for i in tokens.indices.reversed() {
            guard let fix = slipFix(tokens, i, lexicon, habits) else { continue }
            scalars.replaceSubrange(tokens[i].start..<tokens[i].end, with: fix.unicodeScalars)
            changed = true
        }
        return changed ? string(scalars[...]) : text
    }
}
