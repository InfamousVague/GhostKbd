import Foundation

// The Undo and redo plugin's history (EditHistory.kt): a list of what the keyboard itself did to one text field,
// newest last, and the list of what undo took back. Nothing here knows about iOS: `FieldEditor` fills it and plays it
// back through the field, and GhostCoreTests checks it on a desk.
//
// It lives in memory for one field and is never saved.

/// What a step was, so a tap on Undo takes back "a word", "a paste" or "an answer" and never half of one.
public enum EditKind { case typing, delete, enter, pair, correction, suggestion, swipe, paste, emoji, snippet, wrap, prefix, ai }

/// A step still growing (a word being typed, a backspace held, an answer typing out), or finished.
public enum StepState { case open, sealed }

/// One thing the keyboard did: `removed` was taken out and `inserted` put in its place.
///
/// Android's step also says where in the field it is, counted from the field's start. iOS tells a keyboard no such
/// thing, so a step here is known only by its own text and the text either side of it. Everything is counted in
/// characters as `deleteBackward` counts them (an emoji is one).
///
/// - `before`: up to `EditHistory.ctx` characters before the change, as they were when it began.
/// - `after`: the text after the change: up to `EditHistory.ctx` characters, and more where the cursor was left
///   further on than the change's end (a line's prefix, whose cursor stays in its place in the line).
/// - `origin`: the cursor before the step, counted from where the change starts in the old text. It is the end of
///   `removed` for all but a line's prefix, where it is further on, in `after`.
/// - `caret`: the cursor after the step, counted from where the change starts in the new text: inside `inserted` for a
///   pair of brackets, past it for a line's prefix.
/// - `redoText`: for an AI answer undone while it was still typing out, the whole answer, which redo writes.
/// - `time`: when it last grew, for grouping by pauses.
public final class EditStep {
    public let kind: EditKind
    public var removed: String
    public var inserted: String
    public var before: String
    public var after: String
    public var origin: Int
    public var caret: Int
    public var time: Double
    public var redoText: String?
    public var state = StepState.sealed

    public init(kind: EditKind, removed: String, inserted: String, before: String, after: String, origin: Int, caret: Int, time: Double) {
        self.kind = kind; self.removed = removed; self.inserted = inserted; self.before = before; self.after = after
        self.origin = origin; self.caret = caret; self.time = time
    }

    /// How much memory the step holds, in bytes of text: its texts, its contexts and a waiting answer.
    public var size: Int { removed.utf8.count + inserted.utf8.count + before.utf8.count + after.utf8.count + (redoText?.utf8.count ?? 0) }

    /// The text before the cursor as the step left it: its context, then its own text (and what follows) up to the cursor.
    public var leftBefore: String { before + String((inserted + after).prefix(caret)) }
    /// The text after the cursor as the step left it.
    public var leftAfter: String { String((inserted + after).dropFirst(caret)) }
    /// The text before the cursor as it was when the step began.
    public var cameBefore: String { before + String((removed + after).prefix(origin)) }
    /// The text after the cursor as it was when the step began.
    public var cameAfter: String { String((removed + after).dropFirst(origin)) }
}

/// The two stacks, their limits, the rules for what joins a step that is still open, and the checks that say whether
/// the text around the cursor is still what a step left.
public final class EditHistory {
    /// What can be undone, newest last.
    public var undo: [EditStep] = []
    /// What undo took back, the next to redo last.
    public var redo: [EditStep] = []

    private let maxSteps: Int
    private let maxUnits: Int

    public init(maxSteps: Int = EditHistory.maxSteps, maxUnits: Int = EditHistory.maxUnits) {
        self.maxSteps = maxSteps; self.maxUnits = maxUnits
    }

    /// The newest step, or nil.
    public var top: EditStep? { undo.last }

    public var isEmpty: Bool { undo.isEmpty && redo.isEmpty }

    /// A new edit: it goes on top, and whatever was waiting to be redone is gone, as in any editor.
    public func push(_ step: EditStep) {
        redo.removeAll()
        undo.append(step)
        trim()
    }

    /// Forgets everything.
    public func clear() {
        undo.removeAll()
        redo.removeAll()
    }

    /// Bytes of text held by both stacks.
    public func units() -> Int { undo.reduce(0) { $0 + $1.size } + redo.reduce(0) { $0 + $1.size } }

    /// Keeps to the limits: the oldest undo steps go first, then the oldest redo steps.
    public func trim() {
        while undo.count + redo.count > maxSteps && undo.count > 1 { undo.removeFirst() }
        while undo.count + redo.count > maxSteps && redo.count > 1 { redo.removeFirst() }
        var held = units()
        while held > maxUnits && undo.count > 1 { held -= undo.removeFirst().size }
        while held > maxUnits && redo.count > 1 { held -= redo.removeFirst().size }
        // One step alone over the limit cannot be kept at all.
        if held > maxUnits { clear() }
    }

    /// Characters of the text either side of a change kept to recognise the place again.
    public static let ctx = 16
    public static let maxSteps = 100
    /// A keyboard extension has little memory: the whole history holds no more text than this.
    public static let maxUnits = 200_000
    /// One step may not be longer than this: a longer edit is made but not kept.
    public static let maxStep = 50_000
    /// A typing step, or a run of backspaces, is cut here so one step is never a whole essay.
    public static let maxRun = 2_000
    /// A pause this long (in seconds) ends a burst of typing.
    public static let typingPause = 3.0
    /// A pause this long ends a run of backspaces.
    public static let deletePause = 2.0
    /// Backspace may take this many characters off the word being typed and still be a slip put right.
    public static let slip = 3
    /// The object replacement character: a picture or other thing in the text that is not text.
    public static let object: Character = "\u{FFFC}"
    /// Where the text iOS shows before the cursor is cut short of a step, this much of the step must still be in it.
    public static let seen = 24

    /// Whether `text` has anything in it but spaces and line ends.
    static func hasInk(_ text: String) -> Bool { text.contains { !$0.isWhitespace } }

    /// Whether `text` begins a new word after `typed`: `typed` already has a word and its space, and `text` is not a
    /// space. So "hello " then "w" is a new step, while a space, a comma or a full stop stays with its word, and a
    /// lone space joins the word typed after it.
    public static func startsWord(_ typed: String, _ text: String) -> Bool {
        guard hasInk(text), let last = typed.last else { return false }
        return last.isWhitespace && hasInk(typed)
    }

    /// Whether `text`, typed at `now` with the cursor still at the end of `step`, belongs to it: the step is typing
    /// that is still open, the pause was short, it is not too long, and `text` does not start a new word.
    public static func joinsTyping(_ step: EditStep, _ text: String, now: Double) -> Bool {
        guard step.kind == .typing, step.state == .open, now - step.time < typingPause else { return false }
        let length = step.inserted.count
        return step.caret == length && length + text.count <= maxRun && !startsWord(step.inserted, text)
    }

    /// Whether one more backspace at `now` belongs to `step`, a run of them still going.
    public static func joinsDelete(_ step: EditStep, now: Double) -> Bool {
        guard step.kind == .delete, step.state == .open, step.inserted.isEmpty, now - step.time < deletePause else { return false }
        let length = step.removed.count
        return step.origin == length && length < maxRun
    }

    // --- is the text still what a step left? --------------------------------------------------------------------------
    //
    // iOS shows a keyboard a window of the text either side of the cursor, and the window is the app's choice: often
    // the sentence or the line the cursor is in, and nothing at all at the start of a line. So where Android asks for
    // the field to hold exactly a step's text and context, these ask for the window to agree with them as far as it
    // reaches.

    /// Whether `actual`, the text the field shows before the cursor, ends as `want` does. Where the window is shorter
    /// than `want` it must still show `need` characters (the ones about to be taken out or stepped over), or `seen`
    /// of them: nothing is deleted that was not read. A window with nothing in it agrees only with wanting nothing, or
    /// with the start of a line (where iOS shows a keyboard nothing) when no more than the line break is needed.
    public static func endsAlike(_ actual: String, _ want: String, need: Int) -> Bool {
        if want.isEmpty { return true }
        if actual.isEmpty { return need <= 1 && (want.last?.isNewline ?? false) }
        if actual.hasSuffix(want) { return true }
        return want.hasSuffix(actual) && actual.count >= min(need, seen)
    }

    /// Whether `actual`, the text the field shows after the cursor, starts as `want` does, as far as the shorter reaches.
    public static func startsAlike(_ actual: String, _ want: String) -> Bool {
        if want.isEmpty { return true }
        if actual.isEmpty { return false }
        return actual.hasPrefix(want) || want.hasPrefix(actual)
    }

    /// Whether a cursor with `before` and `after` either side of it sits where `wantBefore` and `wantAfter` meet.
    /// Wanting nothing either side is wanting an empty field: text in it then is somebody else's.
    public static func around(before: String, after: String, wantBefore: String, wantAfter: String, need: Int) -> Bool {
        if wantBefore.isEmpty && wantAfter.isEmpty { return before.isEmpty && after.isEmpty }
        return endsAlike(before, wantBefore, need: need) && startsAlike(after, wantAfter)
    }

    /// Whether the field shows `step` as it left it, the cursor where it put it: what undo looks for first.
    public static func leftBy(before: String, after: String, _ step: EditStep) -> Bool {
        around(before: before, after: after, wantBefore: step.leftBefore, wantAfter: step.leftAfter, need: min(step.caret, step.inserted.count))
    }

    /// Whether the field shows `step`'s text with the cursor at its end, ready to be taken out.
    public static func endOf(before: String, after: String, _ step: EditStep) -> Bool {
        around(before: before, after: after, wantBefore: step.before + step.inserted, wantAfter: step.after, need: step.inserted.count)
    }

    /// Whether the field is as it was before `step`, the cursor where it began: what redo looks for first.
    public static func readyFor(before: String, after: String, _ step: EditStep) -> Bool {
        around(before: before, after: after, wantBefore: step.cameBefore, wantAfter: step.cameAfter, need: min(step.origin, step.removed.count))
    }

    /// Whether the field shows what `step` took out with the cursor at its end, ready to be replaced again.
    public static func startOf(before: String, after: String, _ step: EditStep) -> Bool {
        around(before: before, after: after, wantBefore: step.before + step.removed, wantAfter: step.after, need: step.removed.count)
    }

    /// The last `n` characters before the cursor as `step` left them.
    public static func tail(_ step: EditStep, _ n: Int) -> String { String(step.leftBefore.suffix(n)) }

    /// How far the cursor must move (in characters, forward when positive) to sit `offset` characters into the one
    /// place where `step`'s text (`middle`: what it put in, or what it took out) is found with its context in the
    /// window `before` + `after`. Nil where it is not there, or is there twice: a step is only looked for where there
    /// is no doubt which text is meant. A context cut short (the step was made at the start or end of what iOS
    /// showed) must still meet the window's start or end, so a step with little text either side is found only where it is all the window shows.
    public static func find(_ step: EditStep, middle: String, offset: Int, before: String, after: String, reach: Int = 2_000) -> Int? {
        let text = step.before + middle + step.after
        guard !text.isEmpty else { return nil }
        let b = String(before.suffix(reach)), a = String(after.prefix(reach))
        let window = b + a
        guard let first = window.range(of: text, options: .literal) else { return nil }
        let next = window.index(after: first.lowerBound)
        if window.range(of: text, options: .literal, range: next..<window.endIndex) != nil { return nil }
        if step.before.count < ctx && first.lowerBound != window.startIndex { return nil }
        if step.after.count < ctx && first.upperBound != window.endIndex { return nil }
        return window.distance(from: window.startIndex, to: first.lowerBound) + step.before.count + offset - b.count
    }
}
