import UIKit

/// Everything the keyboard does to the text, through iOS's `textDocumentProxy`: the only door a keyboard has. It
/// sees a window of text either side of the cursor and the selected text; it can insert, delete backward and move
/// the cursor by characters, and nothing else.
///
/// It also keeps the journal behind the row's undo and redo. iOS gives a keyboard no undo key to press, so undo here
/// undoes what this keyboard typed: each edit is remembered as what it removed and what it put in, and is taken back
/// only while the text before the cursor still ends with what it put in.
final class TextEditor: TextTarget {
    private let proxy: () -> UITextDocumentProxy

    init(proxy: @escaping () -> UITextDocumentProxy) { self.proxy = proxy }

    var before: String { proxy().documentContextBeforeInput ?? "" }
    var after: String { proxy().documentContextAfterInput ?? "" }
    var selected: String? { let s = proxy().selectedText; return (s ?? "").isEmpty ? nil : s }
    var hasSelection: Bool { selected != nil }

    // --- the journal --------------------------------------------------------------------------------------------------

    private struct Edit { var removed: String; var inserted: String }
    private var undoStack: [Edit] = []
    private var redoStack: [Edit] = []
    private var journalling = true
    private var batch: Edit?
    private var batchDepth = 0

    var canUndo: Bool { !undoStack.isEmpty }
    var canRedo: Bool { !redoStack.isEmpty }

    /// Several edits that are one thing to undo (a correction: the word out, the fix and its space in).
    func batch(_ body: () -> Void) {
        if batchDepth == 0 { batch = Edit(removed: "", inserted: "") }
        batchDepth += 1
        body()
        batchDepth -= 1
        if batchDepth == 0, let b = batch {
            batch = nil
            if !b.removed.isEmpty || !b.inserted.isEmpty { push(b) }
        }
    }

    private func push(_ e: Edit) {
        redoStack.removeAll()
        // Letters typed one after another are one word to undo, as an editor's undo takes a word at a time.
        if e.removed.isEmpty, e.inserted.count == 1, let c = e.inserted.first, c.isLetter || c.isNumber,
           var last = undoStack.last, last.removed.isEmpty, let l = last.inserted.last, l.isLetter || l.isNumber, last.inserted.count < 40 {
            last.inserted += e.inserted
            undoStack[undoStack.count - 1] = last
            return
        }
        undoStack.append(e)
        if undoStack.count > 100 { undoStack.removeFirst() }
    }

    private func record(removed: String, inserted: String) {
        guard journalling else { return }
        if batch != nil {
            // Within a batch: what is deleted either eats what the batch itself typed, or reaches further back.
            if !removed.isEmpty {
                if batch!.inserted.hasSuffix(removed) { batch!.inserted.removeLast(removed.count) }
                else { batch!.removed = removed + batch!.removed; batch!.inserted = "" }
            }
            batch!.inserted += inserted
        } else {
            push(Edit(removed: removed, inserted: inserted))
        }
    }

    func forget() { undoStack.removeAll(); redoStack.removeAll() }

    @discardableResult
    func undo() -> Bool {
        guard let e = undoStack.popLast() else { return false }
        guard before.hasSuffix(e.inserted) else { undoStack.removeAll(); return false }
        journalling = false
        rawDelete(e.inserted.count)
        if !e.removed.isEmpty { proxy().insertText(e.removed) }
        journalling = true
        redoStack.append(e)
        return true
    }

    @discardableResult
    func redo() -> Bool {
        guard let e = redoStack.popLast() else { return false }
        guard before.hasSuffix(e.removed) else { redoStack.removeAll(); return false }
        journalling = false
        rawDelete(e.removed.count)
        if !e.inserted.isEmpty { proxy().insertText(e.inserted) }
        journalling = true
        undoStack.append(e)
        return true
    }

    // --- edits --------------------------------------------------------------------------------------------------------

    /// Types `text` at the cursor (over the selection, where there is one).
    func insert(_ text: String) {
        guard !text.isEmpty else { return }
        let replaced = selected ?? ""
        proxy().insertText(text)
        record(removed: replaced, inserted: text)
    }

    private func rawDelete(_ count: Int) {
        guard count > 0 else { return }
        let p = proxy()
        for _ in 0..<count { p.deleteBackward() }
    }

    /// Deletes `count` characters before the cursor (or the selection, whatever `count` is, where there is one).
    func deleteBackward(_ count: Int) {
        guard count > 0 else { return }
        if let sel = selected {
            proxy().deleteBackward()
            record(removed: sel, inserted: "")
            return
        }
        let gone = String(before.suffix(count))
        rawDelete(count)
        record(removed: gone, inserted: "")
    }

    /// Moves the cursor `delta` characters (negative is left), no further than the text iOS shows us reaches.
    /// iOS counts this move in UTF-16 units, so an emoji is two.
    @discardableResult
    func move(_ delta: Int) -> Int {
        if delta == 0 { return 0 }
        let units: Int, moved: Int
        if delta < 0 {
            let s = before.suffix(-delta)
            units = -s.utf16.count; moved = -s.count
        } else {
            let s = after.prefix(delta)
            units = s.utf16.count; moved = s.count
        }
        if units != 0 { proxy().adjustTextPosition(byCharacterOffset: units) }
        return moved
    }

    func moveCursor(by offset: Int) -> Bool { move(offset) == offset }

    /// Moves the cursor up (negative) or down a line, to the same column, by counting characters to it. It knows
    /// only the line breaks in the text (not where a long line wraps on screen).
    func moveLine(_ lines: Int) {
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

    /// Replaces `old`, just before the cursor, with `new` (types `new` alone where `old` is not there).
    func replaceBefore(_ old: String, with new: String) {
        batch {
            if !old.isEmpty, before.hasSuffix(old) { deleteBackward(old.count) }
            insert(new)
        }
    }
}
