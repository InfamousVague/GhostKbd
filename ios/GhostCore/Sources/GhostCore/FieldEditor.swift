import Foundation

/// What a text field said when asked: the text before the cursor (or the selection), the selected text, and the text
/// after. On iOS each is a window the app chooses, not the whole field.
public struct FieldReading {
    public let before: String
    public let selected: String
    public let after: String

    public init(before: String, selected: String, after: String) {
        self.before = before; self.selected = selected; self.after = after
    }

    /// Whether nothing came back at all: no text either side and nothing selected.
    public var empty: Bool { before.isEmpty && selected.isEmpty && after.isEmpty }
}

/// The text field as the keyboard reaches it: iOS's `textDocumentProxy` cut down to what editing needs
/// (TextEditor.swift), or a pretend field in the tests. Nothing comes back to say whether the app did as asked.
public protocol FieldPort: AnyObject {
    /// The text before the cursor (a window of it).
    var before: String { get }
    /// The text after the cursor (a window of it).
    var after: String { get }
    /// The selected text, empty for none.
    var selected: String { get }
    /// Types `text` over the selection, leaving the cursor after it.
    func insert(_ text: String)
    /// Deletes the selection, or with none the one character before the cursor.
    func deleteBackward()
    /// Moves the cursor `units` UTF-16 units (forward when positive), as iOS counts a move.
    func move(units: Int)
    /// Runs `body` once the app has caught up with a cursor move just asked for: an app applies one a moment after
    /// it is asked, and a delete sent straight after it would land where the cursor was.
    func settle(_ body: @escaping () -> Void)
}

/// Every edit the keyboard makes goes through here (FieldEditor.kt). While the field is being recorded each edit is
/// also written down as an `EditStep` in an `EditHistory`, and `undo` and `redo` play the steps back through the field.
///
/// The rule that keeps text safe: nothing is taken out of a field unless it was first read back and found to be what
/// the step put there, with the text either side of it as it was, as far as iOS shows it. If it is not (the app
/// changed it, something was pasted from the app's own menu, another keyboard typed), the history is dropped.
///
/// What Android's editor has and this cannot: iOS gives a keyboard no positions in the field, no way to select, no
/// key events and no undo of the app's own to ask for. So a step is found by its text around the cursor, or once in
/// the window iOS shows; a selection a step replaced comes back as text with the cursor after it; and a step that
/// left the cursor somewhere other than its end is undone in two beats, the cursor moved first and the text taken out
/// once the app has caught up (`FieldPort.settle`).
///
/// Where the field is not being recorded (the plugin is off, a password) every method sends the same calls and
/// nothing is kept.
public class FieldEditor: TextTarget {
    public let port: FieldPort
    private let clock: () -> Double

    /// Told when an arrow lights or dims.
    public var onChange: () -> Void = {}

    /// The steps. Read by the tests; the keyboard only asks `canUndo` and `canRedo`.
    public let history = EditHistory()

    /// Whether this field's edits are being written down.
    public private(set) var recording = true

    /// The step whose end the cursor is believed to sit at, so the next key can join it.
    private var tip: EditStep?

    /// An AI answer still typing out.
    private var fill: EditStep?

    /// What backspace has taken off the end of the word being typed (`shrunkStep`): at most `EditHistory.slip`
    /// characters, with some of the word left. If typing goes on, it was a slip put right and is forgotten, the word
    /// staying one step. If anything else comes next (more backspaces among them), the word is put down as it was
    /// typed and the backspaces become a step of their own, so undo gives the deleted letters back.
    private var shrunk = ""
    private var shrunkStep: EditStep?

    private var lastEditAt = -1_000.0
    /// Counts the edits and taps: an undo waiting on a cursor move gives up when anything else comes first.
    private var turn = 0
    private var told = (false, false)

    /// A change of text or cursor this soon after a key (in seconds) is taken as that key's doing.
    public static let echo = 0.5

    public init(port: FieldPort, clock: @escaping () -> Double = { ProcessInfo.processInfo.systemUptime }) {
        self.port = port
        self.clock = clock
    }

    /// Whether the keyboard itself changed the field a moment ago: a change iOS reports now is most likely its echo.
    public var justEdited: Bool { clock() - lastEditAt < Self.echo }

    /// Whether the Undo arrow does anything.
    public var canUndo: Bool { recording && !history.undo.isEmpty }
    /// Whether the Redo arrow does anything.
    public var canRedo: Bool { recording && !history.redo.isEmpty }

    public var before: String { port.before }
    public var after: String { port.after }
    public var selected: String? { let s = port.selected; return s.isEmpty ? nil : s }
    public var hasSelection: Bool { !port.selected.isEmpty }

    // --- the field coming and going -----------------------------------------------------------------------------------

    /// A field to type in, with an empty history. `recordable` is false for passwords and with the plugin off.
    public func attach(recordable: Bool) {
        forget()
        recording = recordable
        tell()
    }

    /// Empties the history and everything that hangs off it.
    public func forget() {
        history.clear()
        tip = nil
        fill = nil
        shrunk = ""
        shrunkStep = nil
        turn += 1
        tell()
    }

    /// iOS said the text or the cursor changed. Where the field is seen to be empty of the newest step, the history
    /// is dropped now, so the arrows say the truth before they are tapped; and a change that is not a key's echo ends
    /// the step being typed (a tap in the text, the app, another keyboard).
    public func audit() {
        guard recording, !history.isEmpty else { return }
        let r = read()
        if r.empty {
            let there = history.top.map { EditHistory.leftBy(before: "", after: "", $0) } ?? history.redo.last.map { EditHistory.readyFor(before: "", after: "", $0) } ?? true
            if !there { forget() }
            return
        }
        if clock() - lastEditAt > Self.echo {
            if fill == nil { sealAll() }
            tip = nil
        } else {
            checkTip(r)
        }
    }

    // --- typing -------------------------------------------------------------------------------------------------------

    /// Types `text` at the cursor, over any selection. With `caretBack` the cursor is left that many characters
    /// before its end (a pair of brackets, with the cursor inside), which is a step of its own.
    public func type(_ text: String, caretBack: Int = 0) {
        guard !text.isEmpty else { return }
        turn += 1
        guard recording else { send(cut: 0, text, caretBack: caretBack); return }
        let r = read()
        checkTip(r)
        if caretBack == 0, r.selected.isEmpty, let top = history.top, top === tip, EditHistory.joinsTyping(top, text, now: clock()) {
            port.insert(text)
            grow(top, text)
            tell()
            return
        }
        sealAll()
        let step = draft(caretBack > 0 ? .pair : .typing, r, cut: 0, text, caretBack: caretBack, open: caretBack == 0)
        send(cut: 0, text, caretBack: caretBack)
        if let step { record(step) }
        tell()
    }

    /// Puts `text` in as one step of its own: a paste, an emoji, a swiped word, a snippet, a new line.
    public func insert(_ text: String, kind: EditKind, caretBack: Int = 0) {
        guard !text.isEmpty else { return }
        turn += 1
        guard recording else { send(cut: 0, text, caretBack: caretBack); return }
        let r = read()
        checkTip(r)
        sealAll()
        let step = draft(kind, r, cut: 0, text, caretBack: caretBack, open: false)
        send(cut: 0, text, caretBack: caretBack)
        if let step { record(step) }
        tell()
    }

    /// Replaces `old`, just before the cursor, with `new`. False, and nothing done, when `old` is not there and
    /// `must` is set; with `must` off `new` is typed anyway. `typedFirst` is a separator the person pressed that the
    /// fix swallowed: it is written down as typed first, so undoing the fix gives back the word and its space.
    /// A swiped word swapped for another choice stays one step.
    @discardableResult
    public func replaceBefore(_ old: String, with new: String, kind: EditKind, typedFirst: String = "", must: Bool = true) -> Bool {
        let r = read()
        let found = !old.isEmpty && r.selected.isEmpty && r.before.hasSuffix(old)
        if !found && must { return false }
        turn += 1
        let cut = found ? old.count : 0
        guard recording else { send(cut: cut, new, selection: !r.selected.isEmpty); return true }
        checkTip(r)
        if kind == .swipe, found, let top = history.top, top === tip, top.kind == .swipe, top.inserted == old, top.caret == old.count {
            send(cut: cut, new)
            top.inserted = new
            top.caret = new.count
            top.time = clock()
            history.redo.removeAll()
            lastEditAt = clock()
            tell()
            return true
        }
        let sep = found ? typedFirst : ""
        if !sep.isEmpty { noteTyped(r, sep) }
        sealAll()
        let step = draft(kind, r, cut: cut, new, open: false)
        if let step, !sep.isEmpty {
            step.removed += sep
            step.origin = step.removed.count
        }
        send(cut: cut, new, selection: !r.selected.isEmpty)
        if let step { record(step) }
        tell()
        return true
    }

    /// Writes `sep` down as typed at the cursor without typing it: it joins the word being typed, or is a step of its own.
    private func noteTyped(_ r: FieldReading, _ sep: String) {
        if let top = history.top, top === tip, EditHistory.joinsTyping(top, sep, now: clock()) {
            // As when it is typed (`grow`): a backspace or two before it put a slip right, and is not a step.
            forgetSlip(top)
            top.inserted += sep
            top.caret = top.inserted.count
            top.time = clock()
        } else {
            sealAll()
            history.push(EditStep(kind: .typing, removed: "", inserted: sep, before: String(r.before.suffix(EditHistory.ctx)), after: String(r.after.prefix(EditHistory.ctx)), origin: 0, caret: sep.count, time: clock()))
        }
    }

    // --- deleting -----------------------------------------------------------------------------------------------------

    /// Backspace: the selection deleted, or one character (a whole emoji) taken from before the cursor.
    public func backspace() {
        turn += 1
        guard recording else { port.deleteBackward(); return }
        var r = read()
        checkTip(r)
        if !r.selected.isEmpty {
            sealAll()
            let step = draft(.delete, r, cut: 0, "", open: false)
            port.deleteBackward()
            if let step { record(step) }
            tell()
            return
        }
        // At the start of a line iOS shows a keyboard nothing before the cursor. Straight after a step that ended a
        // line, that step says what is there.
        if r.before.isEmpty, let t = tip, t.leftBefore.last?.isNewline == true {
            r = FieldReading(before: EditHistory.tail(t, EditHistory.ctx + 1), selected: "", after: r.after)
        }
        guard let last = r.before.last, last != EditHistory.object else {
            // Nothing to be read, or a picture: the key is sent, and what it took cannot be put back.
            lost()
            port.deleteBackward()
            tell()
            return
        }
        remove(String(last), r, slip: true)
    }

    /// Deletes `text`, which the keyboard has just read from before the cursor: a swiped word, or the word-delete
    /// slide. Always a deletion of its own in the history, never a slip in the word being typed: undo gives the word
    /// back whatever is typed next.
    public func deleteBefore(_ text: String) {
        guard !text.isEmpty else { return }
        turn += 1
        guard recording else { for _ in 0..<text.count { port.deleteBackward() }; return }
        let r = read()
        checkTip(r)
        guard r.selected.isEmpty, r.before.hasSuffix(text) else {
            lost()
            for _ in 0..<text.count { port.deleteBackward() }
            tell()
            return
        }
        remove(text, r, slip: false)
    }

    /// Deletes the selection (the word-delete slide with text selected).
    public func deleteSelection() {
        turn += 1
        guard recording else { port.deleteBackward(); return }
        let r = read()
        guard !r.selected.isEmpty else { return }
        checkTip(r)
        sealAll()
        let step = draft(.delete, r, cut: 0, "", open: false)
        port.deleteBackward()
        if let step { record(step) }
        tell()
    }

    private enum How { case none, shrink, extend, fresh }

    /// Takes `gone` from before the cursor and writes it down: off the word being typed, onto a run of backspaces, or
    /// as a new step.
    private func remove(_ gone: String, _ r: FieldReading, slip: Bool) {
        let unit = gone.count
        var how = deleteHow(gone, unit, slip: slip)
        var step: EditStep?
        if how == .fresh {
            sealAll()
            step = draft(.delete, r, cut: unit, "", open: true)
            if step == nil { how = .none }
        }
        for _ in 0..<unit { port.deleteBackward() }
        switch how {
        case .none: break
        case .fresh: if let step { record(step) }
        case .shrink:
            if let top = history.top {
                top.inserted.removeLast(unit)
                top.caret = top.inserted.count
                top.time = clock()
                history.redo.removeAll()
                lastEditAt = clock()
                shrunk = gone + shrunk
                shrunkStep = top
            }
        case .extend:
            if let top = history.top {
                top.removed = gone + top.removed
                top.before = String(r.before.dropLast(unit).suffix(EditHistory.ctx))
                top.origin = top.removed.count
                top.time = clock()
                history.redo.removeAll()
                lastEditAt = clock()
            }
        }
        tell()
    }

    /// Where `unit` characters deleted from before the cursor go: off the word being typed, onto a run of
    /// backspaces, or a new step. Only with `slip` (a backspace key) can they come off the word, and only while it is
    /// a slip: no more than `EditHistory.slip` characters in all, and some of the word left. Past that it is a
    /// deletion, kept to be undone.
    private func deleteHow(_ gone: String, _ unit: Int, slip: Bool) -> How {
        if slip, let top = history.top, top === tip, top.kind == .typing, top.state == .open {
            let length = top.inserted.count
            if top.caret == length, length > unit, top.inserted.hasSuffix(gone), (shrunkStep === top ? shrunk.count : 0) + unit <= EditHistory.slip { return .shrink }
        }
        // More than a slip, or past the start of the word being typed: its backspaces so far become the run this one joins.
        settleShrunk()
        if let top = history.top, top === tip, EditHistory.joinsDelete(top, now: clock()) { return .extend }
        return .fresh
    }

    /// Backspaces taken off the word being typed, with no typing after them: the word is written down whole again and
    /// the backspaces as a deletion after it, still open for more.
    private func settleShrunk() {
        guard let s = shrunkStep else { return }
        shrunkStep = nil
        if shrunk.isEmpty { return }
        let gone = shrunk
        shrunk = ""
        guard history.top === s else { return }
        let d = EditStep(kind: .delete, removed: gone, inserted: "", before: String((s.before + s.inserted).suffix(EditHistory.ctx)), after: s.after, origin: gone.count, caret: 0, time: s.time)
        d.state = .open
        s.inserted += gone
        s.caret = s.inserted.count
        s.state = .sealed
        history.undo.append(d)
        history.trim()
        if tip === s { tip = d }
    }

    // --- markdown, snippets and answers -------------------------------------------------------------------------------

    /// Replaces the selection with what `make` gives for it: the new text, and how far from its end the cursor goes
    /// (markdown around a selection, a link).
    public func replaceSelection(kind: EditKind, _ make: (String) -> (String, Int)) {
        turn += 1
        let r = read()
        let (text, back) = make(r.selected)
        guard recording else { send(cut: 0, text, caretBack: back, selection: !r.selected.isEmpty); return }
        checkTip(r)
        sealAll()
        let step = draft(kind, r, cut: 0, text, caretBack: back, open: false)
        send(cut: 0, text, caretBack: back, selection: !r.selected.isEmpty)
        if let step { record(step) }
        tell()
    }

    /// Puts `prefix` at the start of the cursor's line (a heading, a bullet, a quote), the cursor staying in its
    /// place in the line. The line starts where the text iOS shows starts, where that is nearer than a line break.
    public func insertAtLineStart(_ prefix: String) {
        guard !prefix.isEmpty else { return }
        turn += 1
        let r = read()
        let line: Substring
        if let nl = r.before.lastIndex(where: { $0.isNewline }) { line = r.before[r.before.index(after: nl)...] } else { line = r.before[...] }
        let back = line.count
        var step: EditStep?
        if recording {
            checkTip(r)
            sealAll()
            if r.selected.isEmpty, back <= EditHistory.maxRun, prefix.utf8.count <= EditHistory.maxStep {
                step = EditStep(kind: .prefix, removed: "", inserted: prefix, before: String(r.before.dropLast(back).suffix(EditHistory.ctx)),
                                after: String(line) + String(r.after.prefix(EditHistory.ctx)), origin: back, caret: prefix.count + back, time: clock())
            }
            tip = nil
        }
        let units = line.utf16.count
        let put = { [weak self] in
            guard let self else { return }
            self.port.insert(prefix)
            if units > 0 { self.port.move(units: units) }
            if let step {
                self.history.push(step)
                self.lastEditAt = self.clock()
            }
            self.tell()
        }
        guard units > 0 else { put(); return }
        // The prefix is typed once the cursor has reached the line's start, and only if the line is still there.
        port.move(units: -units)
        lastEditAt = clock()
        let mine = turn
        port.settle { [weak self] in
            guard let self else { return }
            if self.turn == mine, self.port.after.hasPrefix(line), self.port.selected.isEmpty { put(); return }
            if self.recording { self.lost() }
        }
    }

    /// An AI answer taking its place: `expect` is the text it replaces, the selection or the text just before the
    /// cursor. `full` is the answer; with `typeOut` it is typed in bit by bit through `appendFill`, the whole of it
    /// one step. False, and nothing done, when `expect` is not there.
    public func beginFill(expect: String, selection: Bool, full: String, typeOut: Bool) -> Bool {
        let r = read()
        if selection { guard r.selected == expect else { return false } }
        else { guard r.selected.isEmpty, r.before.hasSuffix(expect) else { return false } }
        turn += 1
        let cut = selection ? 0 : expect.count
        let text = typeOut ? "" : full
        var step: EditStep?
        if recording {
            checkTip(r)
            sealAll()
            if full.utf8.count <= EditHistory.maxStep { step = draft(.ai, r, cut: cut, text, open: typeOut) } else { forget() }
            if typeOut { step?.redoText = full }
        }
        send(cut: cut, text, selection: selection)
        if let step {
            record(step)
            if typeOut { fill = step }
        }
        tell()
        return true
    }

    /// The next few characters of an answer typing out. They join the answer's step while the cursor is still at its end.
    public func appendFill(_ chunk: String) {
        guard !chunk.isEmpty else { return }
        turn += 1
        guard recording else { port.insert(chunk); return }
        let r = read()
        checkTip(r)
        if let f = fill, f === tip, f === history.top, f.state == .open, r.selected.isEmpty {
            port.insert(chunk)
            f.inserted += chunk
            f.caret = f.inserted.count
            f.time = clock()
            lastEditAt = clock()
        } else {
            // The cursor was moved mid-answer: what is left is a second step, typed where the cursor is now.
            sealAll()
            let step = draft(.ai, r, cut: 0, chunk, open: true)
            port.insert(chunk)
            if let step { record(step); fill = step }
        }
        tell()
    }

    /// The answer is all typed.
    public func endFill() {
        if let f = fill { f.state = .sealed; f.redoText = nil }
        fill = nil
    }

    // --- things the keyboard does that are not edits ------------------------------------------------------------------

    /// The keyboard did something to the field whose effect it cannot know: the open step ends.
    public func sent() {
        turn += 1
        if recording { lost() }
    }

    private func lost() {
        sealAll()
        tip = nil
    }

    /// Moves the cursor `delta` characters (negative is left), no further than the text iOS shows reaches. Says how
    /// far it went. iOS counts a move in UTF-16 units, so an emoji is two.
    @discardableResult
    public func move(_ delta: Int) -> Int {
        turn += 1
        if recording { lost() }
        return nudge(delta, read(), whole: false)
    }

    public func moveCursor(by offset: Int) -> Bool { move(offset) == offset }

    /// Moves the cursor up (negative) or down a line, to the same column, by counting characters to it. It knows
    /// only the line breaks in the text (not where a long line wraps on screen).
    public func moveLine(_ lines: Int) {
        guard lines != 0 else { return }
        for _ in 0..<abs(lines) {
            let b = Array(before), a = Array(after)
            if lines < 0 {
                guard let nl = b.lastIndex(of: "\n") else { move(-b.count); return }
                let col = b.count - nl - 1
                let prevStart = (b[..<nl].lastIndex(of: "\n") ?? -1) + 1
                let prevLen = nl - prevStart
                move(-(col + 1 + (prevLen - min(col, prevLen))))
            } else {
                guard let nl = a.firstIndex(of: "\n") else { move(a.count); return }
                let col = b.count - ((b.lastIndex(of: "\n") ?? -1) + 1)
                let rest = a[(nl + 1)...]
                let nextLen = rest.firstIndex(of: "\n").map { $0 - (nl + 1) } ?? rest.count
                move(nl + 1 + min(col, nextLen))
            }
        }
    }

    // --- TextTarget ---------------------------------------------------------------------------------------------------

    /// Types `text` at the cursor (over the selection, where there is one), as a step of its own.
    public func insert(_ text: String) { insert(text, kind: .paste) }

    /// Deletes `count` characters before the cursor (or the selection, whatever `count` is, where there is one).
    public func deleteBackward(_ count: Int) {
        guard count > 0 else { return }
        if hasSelection { deleteSelection(); return }
        let gone = String(before.suffix(count))
        if gone.count == count { deleteBefore(gone); return }
        // More than iOS shows: deleted all the same, and not to be put back.
        turn += 1
        if recording { forget() }
        for _ in 0..<count { port.deleteBackward() }
    }

    // --- undo and redo ------------------------------------------------------------------------------------------------

    /// Takes back the newest step. False when there is none, or the field no longer holds what it left (nothing is
    /// changed then, and the history is dropped), or text is selected (nothing is changed, and the history is kept).
    @discardableResult
    public func undo() -> Bool {
        turn += 1
        guard recording else { return false }
        sealAll(keepAnswer: true)
        guard let s = history.top else { return false }
        let r = read()
        guard r.selected.isEmpty else { return false }
        let n = s.inserted.count
        var delta: Int?
        if EditHistory.leftBy(before: r.before, after: r.after, s) {
            delta = n - s.caret
        } else {
            // Not at the cursor (it was moved since): the step is looked for in the text iOS shows.
            delta = EditHistory.find(s, middle: s.inserted, offset: n, before: r.before, after: r.after)
        }
        guard let d = delta else { forget(); return false }
        if d == 0 { return undoStep(s) }
        guard nudge(d, r, whole: true) == d else { forget(); return false }
        tip = nil
        let mine = turn
        port.settle { [weak self] in
            guard let self, self.turn == mine, self.history.top === s else { return }
            _ = self.undoStep(s)
        }
        return true
    }

    /// Puts back what `undo` last took. False as for `undo`.
    @discardableResult
    public func redo() -> Bool {
        turn += 1
        guard recording else { return false }
        sealAll()
        guard let s = history.redo.last else { return false }
        let r = read()
        guard r.selected.isEmpty else { return false }
        let m = s.removed.count
        var delta: Int?
        if EditHistory.readyFor(before: r.before, after: r.after, s) {
            delta = m - s.origin
        } else {
            delta = EditHistory.find(s, middle: s.removed, offset: m, before: r.before, after: r.after)
        }
        guard let d = delta else { forget(); return false }
        if d == 0 { return redoStep(s) }
        guard nudge(d, r, whole: true) == d else { forget(); return false }
        tip = nil
        let mine = turn
        port.settle { [weak self] in
            guard let self, self.turn == mine, self.history.redo.last === s else { return }
            _ = self.redoStep(s)
        }
        return true
    }

    /// Undoes `s`, the cursor being at its end, if the field still holds what it left there. False when it does not:
    /// nothing was changed and the history is dropped.
    private func undoStep(_ s: EditStep) -> Bool {
        let r = read()
        guard r.selected.isEmpty, EditHistory.endOf(before: r.before, after: r.after, s) else { forget(); return false }
        for _ in 0..<s.inserted.count { port.deleteBackward() }
        if !s.removed.isEmpty { port.insert(s.removed) }
        history.undo.removeAll { $0 === s }
        // An answer undone while it was typing out: redo writes all of it.
        if let all = s.redoText {
            s.inserted = all
            s.caret = all.count
            s.redoText = nil
        }
        s.state = .sealed
        history.redo.append(s)
        fill = nil
        tip = nil
        shrunk = ""
        shrunkStep = nil
        lastEditAt = clock()
        // What was written is read back: a field that did not do as asked is not one to keep a history for.
        let back = read()
        if !back.selected.isEmpty || !EditHistory.startOf(before: back.before, after: back.after, s) {
            forget()
            return true
        }
        // The cursor goes back where it was before the step. Where it cannot, redo will look for it after the text.
        let away = s.origin - s.removed.count
        if away != 0, nudge(away, back, whole: true) != away { s.origin = s.removed.count }
        tell()
        return true
    }

    /// Does `s` again, the cursor being at the end of what it took out, if the field is still as undo left it.
    private func redoStep(_ s: EditStep) -> Bool {
        let r = read()
        guard r.selected.isEmpty, EditHistory.startOf(before: r.before, after: r.after, s) else { forget(); return false }
        for _ in 0..<s.removed.count { port.deleteBackward() }
        if !s.inserted.isEmpty { port.insert(s.inserted) }
        history.redo.removeAll { $0 === s }
        s.state = .sealed
        history.undo.append(s)
        tip = nil
        lastEditAt = clock()
        let back = read()
        if !back.selected.isEmpty || !EditHistory.endOf(before: back.before, after: back.after, s) {
            forget()
            return true
        }
        let n = s.inserted.count
        let away = s.caret - n
        if away != 0, nudge(away, back, whole: true) != away { s.caret = n }
        tell()
        return true
    }

    // --- the workings -------------------------------------------------------------------------------------------------

    private func read() -> FieldReading { FieldReading(before: port.before, selected: port.selected, after: port.after) }

    /// Works out the step for replacing `cut` characters before the cursor and the selection with `text`, from the
    /// reading `r`. Nil (and the history dropped) when it is too big to keep, reaches past what iOS shows, or would
    /// take out a picture or other embedded object, which cannot be put back as text.
    private func draft(_ kind: EditKind, _ r: FieldReading, cut: Int, _ text: String, caretBack: Int = 0, open: Bool) -> EditStep? {
        let gone = String(r.before.suffix(cut))
        let removed = gone + r.selected
        if gone.count != cut || removed.utf8.count + text.utf8.count > EditHistory.maxStep || removed.contains(EditHistory.object) {
            forget()
            return nil
        }
        let step = EditStep(kind: kind, removed: removed, inserted: text, before: String(r.before.dropLast(cut).suffix(EditHistory.ctx)),
                            after: String(r.after.prefix(EditHistory.ctx)), origin: removed.count, caret: text.count - caretBack, time: clock())
        step.state = open ? .open : .sealed
        return step
    }

    /// Sends one replacement to the field: the delete, the text, then the cursor.
    private func send(cut: Int, _ text: String, caretBack: Int = 0, selection: Bool = false) {
        for _ in 0..<cut { port.deleteBackward() }
        if !text.isEmpty { port.insert(text) } else if selection { port.deleteBackward() }
        if caretBack > 0 { port.move(units: -text.suffix(caretBack).utf16.count) }
    }

    /// Writes a sent step down as the newest.
    private func record(_ s: EditStep) {
        history.push(s)
        tip = history.top === s ? s : nil
        lastEditAt = clock()
    }

    /// `text` typed onto the end of the open step `s`.
    private func grow(_ s: EditStep, _ text: String) {
        forgetSlip(s)
        s.inserted += text
        s.caret = s.inserted.count
        s.time = clock()
        history.redo.removeAll()
        lastEditAt = clock()
    }

    /// Typing goes on at the end of `s` after a backspace or two: they put a slip right, and the word is still one step.
    private func forgetSlip(_ s: EditStep) {
        if shrunkStep === s { shrunk = ""; shrunkStep = nil }
    }

    /// Ends whatever step is still growing. An answer still typing out loses its unwritten rest unless `keepAnswer`.
    private func sealAll(keepAnswer: Bool = false) {
        settleShrunk()
        if let top = history.top, top.state == .open { top.state = .sealed }
        if !keepAnswer { fill?.redoText = nil; fill = nil }
    }

    /// The step the cursor was last left by is the place the next key joins. Where the field shows other text before
    /// the cursor (a tap, the app, another keyboard), that step is ended and the next edit starts from what is read.
    private func checkTip(_ r: FieldReading) {
        guard let t = tip else { return }
        if !r.selected.isEmpty || !EditHistory.endsAlike(String(r.before.suffix(64)), EditHistory.tail(t, 64), need: 1) {
            sealAll()
            tip = nil
        }
    }

    /// Moves the cursor `delta` characters over the text in `r`. With `whole`, all the way or not at all.
    private func nudge(_ delta: Int, _ r: FieldReading, whole: Bool) -> Int {
        if delta == 0 { return 0 }
        let over = delta < 0 ? r.before.suffix(-delta) : r.after.prefix(delta)
        let moved = over.count
        if moved == 0 || (whole && moved != abs(delta)) { return 0 }
        port.move(units: delta < 0 ? -over.utf16.count : over.utf16.count)
        // A move of the keyboard's own: iOS's report of it is not a tap in the text.
        lastEditAt = clock()
        return delta < 0 ? -moved : moved
    }

    /// Tells the keyboard when an arrow lights or dims.
    private func tell() {
        let now = (canUndo, canRedo)
        if now != told {
            told = now
            onChange()
        }
    }
}
