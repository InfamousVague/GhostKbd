import XCTest
@testable import GhostCore

/// A pretend text field behind iOS's proxy: its text, its selection, and the ways an app can differ from a plain
/// one. `lineWindow` shows the keyboard only the cursor's line (and nothing before the cursor at a line's start), as
/// iOS does in some apps. `lazyMoves` applies a cursor move only once the app has had a moment (`pump`), as a web
/// view does: a delete sent straight after a move lands where the cursor was.
final class PretendField: FieldPort {
    var text: [Character]
    var start: Int
    var end: Int
    var lineWindow = false
    var lazyMoves = false
    /// Calls that changed the field, for the tests that count them.
    var edits = 0
    private var waitingMoves: [Int] = []
    private var settles: [() -> Void] = []

    init(_ text: String = "", cursor: Int? = nil) {
        self.text = Array(text)
        start = cursor ?? self.text.count
        end = start
    }

    var before: String {
        let all = text[..<start]
        guard lineWindow else { return String(all) }
        return String(all[((all.lastIndex(where: { $0.isNewline }) ?? -1) + 1)...])
    }

    var after: String {
        let all = text[end...]
        guard lineWindow else { return String(all) }
        return String(all[..<(all.firstIndex(where: { $0.isNewline }) ?? all.endIndex)])
    }

    var selected: String { String(text[start..<end]) }

    func insert(_ s: String) {
        edits += 1
        let new = Array(s)
        text.replaceSubrange(start..<end, with: new)
        start += new.count
        end = start
    }

    func deleteBackward() {
        edits += 1
        if end > start { text.removeSubrange(start..<end); end = start; return }
        guard start > 0 else { return }
        text.remove(at: start - 1)
        start -= 1
        end = start
    }

    func move(units: Int) {
        if lazyMoves { waitingMoves.append(units) } else { apply(units) }
    }

    private func apply(_ units: Int) {
        var left = abs(units), at = units < 0 ? start : end
        while left > 0 {
            if units < 0 { guard at > 0 else { break }; at -= 1; left -= String(text[at]).utf16.count }
            else { guard at < text.count else { break }; left -= String(text[at]).utf16.count; at += 1 }
        }
        start = at
        end = at
    }

    func settle(_ body: @escaping () -> Void) { settles.append(body) }

    /// The app drops the cursor moves it was asked for.
    func ignoreMoves() { waitingMoves.removeAll() }

    /// The app catches up: cursor moves land, then what was waiting on them runs.
    func pump() {
        while !waitingMoves.isEmpty || !settles.isEmpty {
            let moves = waitingMoves
            waitingMoves.removeAll()
            moves.forEach(apply)
            if !settles.isEmpty { settles.removeFirst()() }
        }
    }

    /// The person taps in the text, or drags a selection.
    func tap(_ from: Int, _ to: Int? = nil) { start = from; end = to ?? from }

    /// The text with the cursor as | or the selection in [ ].
    var show: String {
        start == end ? String(text[..<start]) + "|" + String(text[start...]) : String(text[..<start]) + "[" + String(text[start..<end]) + "]" + String(text[end...])
    }
}

/// A field, a clock and the editor between them.
final class Rig {
    let field: PretendField
    var now = 100.0
    lazy var editor = FieldEditor(port: field, clock: { [unowned self] in self.now })

    init(_ text: String = "", cursor: Int? = nil) { field = PretendField(text, cursor: cursor) }

    var show: String { field.show }
    var steps: Int { editor.history.undo.count }
    var typed: [String] { editor.history.undo.map { $0.inserted } }

    /// Types `text` a key at a time, a tenth of a second apart.
    func keys(_ text: String) {
        for c in text { now += 0.1; editor.type(String(c)); field.pump() }
    }

    func backspace(_ n: Int = 1) {
        for _ in 0..<n { now += 0.05; editor.backspace(); field.pump() }
    }

    @discardableResult func undo() -> Bool { let done = editor.undo(); field.pump(); return done }
    @discardableResult func redo() -> Bool { let done = editor.redo(); field.pump(); return done }

    /// The person taps in the text: iOS tells the keyboard a moment later.
    func tap(_ from: Int, _ to: Int? = nil) { field.tap(from, to); now += 1; editor.audit() }
}

final class EditHistoryTests: XCTestCase {
    private func step(_ kind: EditKind, _ inserted: String, open: Bool = true, time: Double = 0) -> EditStep {
        let s = EditStep(kind: kind, removed: "", inserted: inserted, before: "", after: "", origin: 0, caret: inserted.count, time: time)
        s.state = open ? .open : .sealed
        return s
    }

    func testAWordKeepsItsSpaceAndItsFullStop() {
        XCTAssertTrue(EditHistory.joinsTyping(step(.typing, "hello"), " ", now: 1))
        XCTAssertTrue(EditHistory.joinsTyping(step(.typing, "hello"), ".", now: 1))
        XCTAssertTrue(EditHistory.joinsTyping(step(.typing, "hello."), " ", now: 1))
        XCTAssertFalse(EditHistory.joinsTyping(step(.typing, "hello "), "w", now: 1))
        XCTAssertTrue(EditHistory.joinsTyping(step(.typing, "hello "), " ", now: 1))
        // A lone space joins the word typed after it.
        XCTAssertTrue(EditHistory.joinsTyping(step(.typing, " "), "w", now: 1))
    }

    func testPausesKindsAndLengthEndAStep() {
        XCTAssertFalse(EditHistory.joinsTyping(step(.typing, "hel"), "l", now: EditHistory.typingPause))
        XCTAssertFalse(EditHistory.joinsTyping(step(.typing, "hel", open: false), "l", now: 1))
        XCTAssertFalse(EditHistory.joinsTyping(step(.paste, "hel"), "l", now: 1))
        XCTAssertFalse(EditHistory.joinsTyping(step(.swipe, "hel"), "l", now: 1))
        XCTAssertFalse(EditHistory.joinsTyping(step(.typing, String(repeating: "a", count: EditHistory.maxRun)), "l", now: 1))
        let inside = step(.typing, "hel")
        inside.caret = 1
        XCTAssertFalse(EditHistory.joinsTyping(inside, "l", now: 1))
        let run = EditStep(kind: .delete, removed: "abc", inserted: "", before: "", after: "", origin: 3, caret: 0, time: 0)
        run.state = .open
        XCTAssertTrue(EditHistory.joinsDelete(run, now: 1))
        XCTAssertFalse(EditHistory.joinsDelete(run, now: EditHistory.deletePause))
    }

    func testCapsOnStepsAndCharacters() {
        let h = EditHistory()
        for i in 0..<150 { h.push(step(.paste, "word\(i)")) }
        XCTAssertEqual(h.undo.count, EditHistory.maxSteps)
        XCTAssertEqual(h.undo.last?.inserted, "word149")
        let small = EditHistory(maxSteps: 100, maxUnits: 1_000)
        for _ in 0..<10 { small.push(step(.paste, String(repeating: "x", count: 300))) }
        XCTAssertEqual(small.undo.count, 3)
        XCTAssertLessThanOrEqual(small.units(), 1_000)
        // One step alone over the limit cannot be kept at all.
        small.push(step(.paste, String(repeating: "x", count: 1_001)))
        XCTAssertTrue(small.isEmpty)
    }

    func testRedoStepsCountTowardsTheCaps() {
        let h = EditHistory(maxSteps: 4, maxUnits: 1_000)
        for i in 0..<4 { h.push(step(.paste, "w\(i)")) }
        h.redo = [h.undo.removeLast(), h.undo.removeLast()]
        h.undo.append(step(.paste, "new"))
        h.trim()
        XCTAssertEqual(h.undo.count + h.redo.count, 4)
        XCTAssertEqual(h.undo.map { $0.inserted }, ["w1", "new"])
    }

    func testTheWindowAgreesAsFarAsItReaches() {
        XCTAssertTrue(EditHistory.endsAlike("Hello world. Next ", "world. Next ", need: 5))
        // The window cut at the sentence still shows all that would be deleted.
        XCTAssertTrue(EditHistory.endsAlike("Next ", "world. Next ", need: 5))
        // A window that shows less than what would be deleted proves nothing.
        XCTAssertFalse(EditHistory.endsAlike(" ", "world. Next ", need: 5))
        XCTAssertFalse(EditHistory.endsAlike("Hello world. Text ", "world. Next ", need: 5))
        // Nothing before the cursor is the start of a line, and no more than the line break is taken on trust.
        XCTAssertTrue(EditHistory.endsAlike("", "line\n", need: 1))
        XCTAssertFalse(EditHistory.endsAlike("", "two\nlines\n", need: 10))
        XCTAssertFalse(EditHistory.endsAlike("", "word", need: 0))
        XCTAssertTrue(EditHistory.startsAlike("rest of it", "rest"))
        XCTAssertFalse(EditHistory.startsAlike("", "rest"))
        // Wanting nothing either side is wanting an empty field.
        XCTAssertTrue(EditHistory.around(before: "", after: "", wantBefore: "", wantAfter: "", need: 0))
        XCTAssertFalse(EditHistory.around(before: "other", after: "", wantBefore: "", wantAfter: "", need: 0))
    }

    func testAStepIsFoundOnceOrNotAtAll() {
        let s = EditStep(kind: .typing, removed: "", inserted: "cat ", before: "once there was a", after: " that sat on the", origin: 0, caret: 4, time: 0)
        // The cursor moved away to the left, and to the right: the step is found and the move to its end worked out.
        XCTAssertEqual(EditHistory.find(s, middle: "cat ", offset: 4, before: "Yes. once there was a", after: "cat  that sat on the mat"), 4)
        XCTAssertEqual(EditHistory.find(s, middle: "cat ", offset: 4, before: "once there was acat  that sat on the mat", after: ""), -20)
        // There twice, or not at all: nothing is guessed.
        XCTAssertNil(EditHistory.find(s, middle: "cat ", offset: 4, before: "once there was acat  that sat on the once there was acat  that sat on the", after: ""))
        XCTAssertNil(EditHistory.find(s, middle: "cat ", offset: 4, before: "once there was a dog that sat on the", after: ""))
        // A step made at the start of what iOS showed must still be at its start; too little text is no place at all.
        let early = EditStep(kind: .typing, removed: "", inserted: "hello there ", before: "", after: "and all of you", origin: 0, caret: 12, time: 0)
        XCTAssertEqual(EditHistory.find(early, middle: "hello there ", offset: 12, before: "", after: "hello there and all of you"), 12)
        XCTAssertNil(EditHistory.find(early, middle: "hello there ", offset: 12, before: "so ", after: "hello there and all of you"))
        let tiny = EditStep(kind: .typing, removed: "", inserted: "ab", before: "", after: "", origin: 0, caret: 2, time: 0)
        XCTAssertEqual(EditHistory.find(tiny, middle: "ab", offset: 2, before: "", after: "ab"), 2)
        XCTAssertNil(EditHistory.find(tiny, middle: "ab", offset: 2, before: "", after: "ab cd"))
    }
}

final class FieldEditorTests: XCTestCase {

    // --- grouping -----------------------------------------------------------------------------------------------------

    func testWordsAreSteps() {
        let r = Rig()
        r.keys("hello world. Next one")
        XCTAssertEqual(r.typed, ["hello ", "world. ", "Next ", "one"])
    }

    func testUndoBackToEmptyAndRedoToTheEnd() {
        let r = Rig()
        r.keys("The cat sat. It was fat.")
        var seen: [String] = []
        while r.steps > 0 { XCTAssertTrue(r.undo()); seen.append(r.show) }
        XCTAssertEqual(seen, ["The cat sat. It was |", "The cat sat. It |", "The cat sat. |", "The cat |", "The |", "|"])
        XCTAssertFalse(r.undo())
        XCTAssertFalse(r.editor.canUndo)
        seen = []
        while r.editor.canRedo { XCTAssertTrue(r.redo()); seen.append(r.show) }
        XCTAssertEqual(seen, ["The |", "The cat |", "The cat sat. |", "The cat sat. It |", "The cat sat. It was |", "The cat sat. It was fat.|"])
        XCTAssertFalse(r.redo())
    }

    func testAPauseStartsANewStep() {
        let r = Rig()
        r.keys("hel")
        r.now += 4
        r.keys("lo")
        XCTAssertEqual(r.steps, 2)
        r.undo()
        XCTAssertEqual(r.show, "hel|")
    }

    func testALoneSpaceJoinsTheNextWord() {
        let r = Rig("abc")
        r.keys(" def ")
        XCTAssertEqual(r.steps, 1)
        r.undo()
        XCTAssertEqual(r.show, "abc|")
    }

    func testNewEditClearsRedo() {
        let r = Rig()
        r.keys("one two three ")
        r.undo(); r.undo()
        XCTAssertEqual(r.show, "one |")
        XCTAssertTrue(r.editor.canRedo)
        r.keys("x")
        XCTAssertFalse(r.editor.canRedo)
        XCTAssertFalse(r.redo())
        XCTAssertEqual(r.show, "one x|")
        r.undo()
        XCTAssertEqual(r.show, "one |")
        XCTAssertTrue(r.redo())
        XCTAssertEqual(r.show, "one x|")
    }

    // --- backspace ----------------------------------------------------------------------------------------------------

    func testBackspaceShrinksTheWordBeingTyped() {
        let r = Rig()
        r.keys("hello wrl")
        r.backspace(2)
        r.keys("orld")
        XCTAssertEqual(r.show, "hello world|")
        XCTAssertEqual(r.steps, 2)
        r.undo()
        XCTAssertEqual(r.show, "hello |")
        r.redo()
        XCTAssertEqual(r.show, "hello world|")
    }

    func testHeldBackspaceIsOneStep() {
        let r = Rig()
        r.keys("hello world ")
        r.now += 5
        r.backspace(9)
        XCTAssertEqual(r.show, "hel|")
        XCTAssertEqual(r.steps, 3)
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "hello world |")
        XCTAssertTrue(r.redo())
        XCTAssertEqual(r.show, "hel|")
    }

    func testBackspaceInOldTextUndoes() {
        let r = Rig("already here")
        r.backspace(4)
        XCTAssertEqual(r.show, "already |")
        XCTAssertEqual(r.steps, 1)
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "already here|")
        XCTAssertFalse(r.editor.canUndo)
    }

    func testBackspaceAtTheStartIsSentAndNotKept() {
        let r = Rig("abc", cursor: 0)
        r.backspace()
        XCTAssertEqual(r.show, "|abc")
        XCTAssertEqual(r.steps, 0)
    }

    func testWordDeleteAndSelectionDelete() {
        let r = Rig("one two three")
        r.editor.deleteBefore("three")
        r.editor.deleteBefore("two ")
        XCTAssertEqual(r.show, "one |")
        XCTAssertEqual(r.steps, 1)
        r.undo()
        XCTAssertEqual(r.show, "one two three|")
        r.tap(0, 4)
        r.editor.deleteSelection()
        XCTAssertEqual(r.show, "|two three")
        // iOS gives a keyboard no way to select: the text comes back with the cursor after it.
        r.undo()
        XCTAssertEqual(r.show, "one |two three")
    }

    func testWordDeleteSlideThenTypingKeepsTheWordToUndo() {
        let r = Rig()
        r.keys("hello world")
        r.editor.deleteBefore("world")
        r.keys("x")
        XCTAssertEqual(r.show, "hello x|")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "hello |")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "hello world|")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "hello |")
        XCTAssertTrue(r.redo()); XCTAssertTrue(r.redo()); XCTAssertTrue(r.redo())
        XCTAssertEqual(r.show, "hello x|")
    }

    func testHeldBackspaceOverTheWordBeingTypedThenTypingKeepsTheWordToUndo() {
        let r = Rig()
        r.keys("see you tomorrow")
        r.backspace(8)
        XCTAssertEqual(r.show, "see you |")
        r.keys("tonight")
        XCTAssertEqual(r.typed, ["see ", "you ", "tomorrow", "", "tonight"])
        XCTAssertEqual(r.editor.history.undo[3].removed, "tomorrow")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "see you |")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "see you tomorrow|")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "see you |")
        XCTAssertTrue(r.redo()); XCTAssertTrue(r.redo()); XCTAssertTrue(r.redo())
        XCTAssertEqual(r.show, "see you tonight|")
    }

    func testASlipIsAFewLettersWithSomeOfTheWordLeft() {
        // Three letters off a longer word and typed again: one step.
        let slip = Rig()
        slip.keys("wonderfol")
        slip.backspace(EditHistory.slip)
        slip.keys("ful")
        XCTAssertEqual(slip.typed, ["wonderful"])
        // One more than that is a deletion.
        let more = Rig()
        more.keys("wonderfol")
        more.backspace(EditHistory.slip + 1)
        more.keys("rful")
        XCTAssertEqual(more.typed, ["wonderfol", "", "rful"])
        XCTAssertTrue(more.undo()); XCTAssertTrue(more.undo())
        XCTAssertEqual(more.show, "wonderfol|")
        // A word backspaced away whole is a deletion however short it is.
        let whole = Rig()
        whole.keys("go no")
        whole.backspace(2)
        whole.keys("yes")
        XCTAssertEqual(whole.show, "go yes|")
        XCTAssertTrue(whole.undo()); XCTAssertTrue(whole.undo())
        XCTAssertEqual(whole.show, "go no|")
    }

    func testAWordDeleteIsNeverASlip() {
        let r = Rig()
        r.keys("abcdef")
        r.editor.deleteBefore("ef")
        r.keys("gh")
        XCTAssertEqual(r.steps, 3)
        r.undo(); r.undo()
        XCTAssertEqual(r.show, "abcdef|")
    }

    // --- emoji --------------------------------------------------------------------------------------------------------

    func testEmojiAreWholeCharacters() {
        let r = Rig()
        r.keys("hi ")
        r.editor.insert("👨‍👩‍👧🇬🇧", kind: .emoji)
        r.keys("ok")
        XCTAssertEqual(r.show, "hi 👨‍👩‍👧🇬🇧ok|")
        r.undo(); r.undo()
        XCTAssertEqual(r.show, "hi |")
        r.redo()
        XCTAssertEqual(r.show, "hi 👨‍👩‍👧🇬🇧|")
        r.now += 5
        r.backspace()
        XCTAssertEqual(r.show, "hi 👨‍👩‍👧|")
        r.undo()
        XCTAssertEqual(r.show, "hi 👨‍👩‍👧🇬🇧|")
    }

    func testAPairAroundAnEmojiMovesInUtf16() {
        let r = Rig("😀")
        r.editor.type("()", caretBack: 1)
        XCTAssertEqual(r.show, "😀(|)")
        r.editor.move(-2)
        XCTAssertEqual(r.show, "|😀()")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "😀|")
    }

    // --- the cursor ---------------------------------------------------------------------------------------------------

    func testSelectionReplacedByTypingComesBackAsText() {
        let r = Rig("one two three")
        r.tap(4, 7)
        r.keys("2")
        XCTAssertEqual(r.show, "one 2| three")
        r.undo()
        XCTAssertEqual(r.show, "one two| three")
        r.redo()
        XCTAssertEqual(r.show, "one 2| three")
    }

    func testUndoFindsTheStepAfterTheCursorMoved() {
        let r = Rig("start ")
        r.keys("middle ")
        r.tap(2)
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "start |")
        r.tap(0)
        XCTAssertTrue(r.redo())
        XCTAssertEqual(r.show, "start middle |")
    }

    func testTypingInTheMiddleUndoesThere() {
        let r = Rig("ac", cursor: 1)
        r.keys("b")
        XCTAssertEqual(r.show, "ab|c")
        r.tap(3)
        r.keys("d")
        XCTAssertEqual(r.steps, 2)
        r.undo(); r.undo()
        XCTAssertEqual(r.show, "a|c")
    }

    func testATapEndsTheWordBeingTyped() {
        let r = Rig("xx ")
        r.keys("ab")
        r.tap(0)
        r.keys("cd")
        XCTAssertEqual(r.show, "cd|xx ab")
        XCTAssertEqual(r.typed, ["ab", "cd"])
        // The same when iOS never said the cursor moved: the text before it is not what the word left.
        let quiet = Rig("xx ")
        quiet.keys("ab")
        quiet.field.tap(1)
        quiet.keys("cd")
        XCTAssertEqual(quiet.typed, ["ab", "cd"])
    }

    func testAStepThatCannotBeToldApartIsNotGuessedAt() {
        let r = Rig()
        r.editor.insert("ab", kind: .paste)
        // The app doubled the text, and the cursor is elsewhere: which copy is the keyboard's cannot be told.
        r.field.text = Array("ab ab")
        r.tap(0)
        XCTAssertFalse(r.undo())
        XCTAssertEqual(r.show, "|ab ab")
        XCTAssertFalse(r.editor.canUndo)
    }

    func testASelectionStopsUndoAndKeepsTheHistory() {
        let r = Rig()
        r.keys("hello world")
        r.tap(0, 5)
        XCTAssertFalse(r.undo())
        XCTAssertEqual(r.show, "[hello] world")
        XCTAssertTrue(r.editor.canUndo)
        r.tap(11)
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "hello |")
    }

    // --- pairs, markdown ----------------------------------------------------------------------------------------------

    func testPairsUndoInPlace() {
        let r = Rig("f")
        r.editor.type("()", caretBack: 1)
        XCTAssertEqual(r.show, "f(|)")
        r.keys("x")
        XCTAssertEqual(r.show, "f(x|)")
        XCTAssertEqual(r.steps, 2)
        r.undo()
        XCTAssertEqual(r.show, "f(|)")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "f|")
        XCTAssertTrue(r.redo())
        XCTAssertEqual(r.show, "f(|)")
        XCTAssertTrue(r.redo())
        XCTAssertEqual(r.show, "f(x|)")
    }

    func testAPairIsUndoneOnlyOnceTheCursorHasMoved() {
        let r = Rig("f")
        r.field.lazyMoves = true
        r.editor.type("()", caretBack: 1)
        r.field.pump()
        XCTAssertEqual(r.show, "f(|)")
        let edits = r.field.edits
        XCTAssertTrue(r.editor.undo())
        // The cursor has been asked to step over the bracket, and nothing is deleted until it has.
        XCTAssertEqual(r.field.edits, edits)
        XCTAssertEqual(r.show, "f(|)")
        r.field.pump()
        XCTAssertEqual(r.show, "f|")
        XCTAssertTrue(r.editor.canRedo)
    }

    func testAKeyBeforeTheCursorHasMovedCallsTheUndoOff() {
        let r = Rig("f")
        r.field.lazyMoves = true
        r.editor.type("()", caretBack: 1)
        r.field.pump()
        XCTAssertTrue(r.editor.undo())
        r.editor.type("x")
        r.field.pump()
        // The move landed after the key: nothing was taken out, and the text is all still there.
        XCTAssertEqual(String(r.field.text), "f(x)")
        XCTAssertFalse(r.editor.canRedo)
    }

    func testAMoveTheAppIgnoredNeverCorrupts() {
        let r = Rig("f")
        r.editor.type("()", caretBack: 1)
        r.field.lazyMoves = true
        XCTAssertTrue(r.editor.undo())
        // The app never moved the cursor: the check at the step's end fails, and nothing is taken out.
        r.field.ignoreMoves()
        r.field.pump()
        XCTAssertEqual(r.show, "f(|)")
        XCTAssertFalse(r.editor.canUndo)
    }

    func testWrapLinkAndLinePrefix() {
        let r = Rig("make this bold\n- second line", cursor: 9)
        r.tap(5, 9)
        r.editor.replaceSelection(kind: .wrap) { ("**" + $0 + "**", 0) }
        XCTAssertEqual(r.show, "make **this**| bold\n- second line")
        r.undo()
        XCTAssertEqual(r.show, "make this| bold\n- second line")
        r.redo()
        XCTAssertEqual(r.show, "make **this**| bold\n- second line")
        r.undo()
        // With nothing selected: an empty pair, the cursor inside it.
        r.tap(4)
        r.editor.replaceSelection(kind: .wrap) { ("[" + $0 + "]()", 3) }
        XCTAssertEqual(r.show, "make[|]() this bold\n- second line")
        r.undo()
        XCTAssertEqual(r.show, "make| this bold\n- second line")
        // A prefix goes to the line's start and the cursor keeps its place in the line.
        r.tap(23)
        r.editor.insertAtLineStart("> "); r.field.pump()
        XCTAssertEqual(r.show, "make this bold\n> - second| line")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "make this bold\n- second| line")
        XCTAssertTrue(r.redo())
        XCTAssertEqual(r.show, "make this bold\n> - second| line")
        r.keys("!")
        r.undo()
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "make this bold\n- second| line")
    }

    func testLinePrefixOnTheFirstLine() {
        let r = Rig("title")
        r.editor.insertAtLineStart("# "); r.field.pump()
        XCTAssertEqual(r.show, "# title|")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "title|")
        XCTAssertTrue(r.redo())
        XCTAssertEqual(r.show, "# title|")
    }

    // --- autocorrect, suggestions, swipe ------------------------------------------------------------------------------

    func testAutocorrectUndoGivesBackTheWordAndItsSpace() {
        let r = Rig()
        r.keys("hello teh")
        XCTAssertTrue(r.editor.replaceBefore("teh", with: "the ", kind: .correction, typedFirst: " "))
        XCTAssertEqual(r.show, "hello the |")
        r.keys("ok ")
        XCTAssertEqual(r.show, "hello the ok |")
        r.undo()
        XCTAssertEqual(r.show, "hello the |")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "hello teh |")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "hello |")
        r.redo(); r.redo()
        XCTAssertEqual(r.show, "hello the |")
    }

    /// What the keyboard's UI test types: "teh cat " a key at a time, the first word corrected at its space.
    func testTehCatThenOneUndo() {
        let r = Rig()
        r.keys("teh")
        XCTAssertTrue(r.editor.replaceBefore("teh", with: "The ", kind: .correction, typedFirst: " "))
        r.keys("cat ")
        XCTAssertEqual(r.show, "The cat |")
        XCTAssertEqual(r.typed, ["teh ", "The ", "cat "])
        r.undo()
        XCTAssertEqual(r.show, "The |")
        r.undo()
        XCTAssertEqual(r.show, "teh |")
        r.undo()
        XCTAssertEqual(r.show, "|")
        r.redo(); r.redo(); r.redo()
        XCTAssertEqual(r.show, "The cat |")
    }

    func testBackspaceAfterACorrectionIsAStepToo() {
        let r = Rig()
        r.keys("teh")
        r.editor.replaceBefore("teh", with: "The ", kind: .correction, typedFirst: " ")
        // Backspace straight after puts back what was typed.
        r.editor.replaceBefore("The ", with: "teh", kind: .correction)
        XCTAssertEqual(r.show, "teh|")
        r.undo()
        XCTAssertEqual(r.show, "The |")
        r.undo()
        XCTAssertEqual(r.show, "teh |")
    }

    func testDoubleSpaceFullStop() {
        let r = Rig()
        r.keys("done ")
        XCTAssertTrue(r.editor.replaceBefore(" ", with: ". ", kind: .correction, typedFirst: " "))
        XCTAssertEqual(r.show, "done. |")
        r.undo()
        XCTAssertEqual(r.show, "done  |")
        r.undo()
        XCTAssertEqual(r.show, "|")
    }

    func testSuggestionAndSwipeAlternative() {
        let r = Rig()
        r.keys("hel")
        XCTAssertTrue(r.editor.replaceBefore("hel", with: "hello ", kind: .suggestion))
        r.editor.insert("there", kind: .swipe)
        XCTAssertTrue(r.editor.replaceBefore("there", with: "three", kind: .swipe))
        XCTAssertEqual(r.show, "hello three|")
        // The swapped swipe is still one step.
        XCTAssertEqual(r.steps, 3)
        r.undo()
        XCTAssertEqual(r.show, "hello |")
        r.undo()
        XCTAssertEqual(r.show, "hel|")
        r.redo(); r.redo()
        XCTAssertEqual(r.show, "hello three|")
        XCTAssertFalse(r.editor.replaceBefore("nope", with: "x", kind: .suggestion))
        XCTAssertEqual(r.show, "hello three|")
    }

    func testPasteEmojiAndEnterAreStepsOfTheirOwn() {
        let r = Rig("see ")
        r.editor.insert("https://example.com/a?b=1\nline two", kind: .paste)
        r.editor.insert(" 🍕", kind: .emoji)
        r.editor.insert("\n", kind: .enter)
        r.keys("next")
        XCTAssertEqual(r.steps, 4)
        r.undo(); r.undo(); r.undo(); r.undo()
        XCTAssertEqual(r.show, "see |")
        r.redo(); r.redo(); r.redo(); r.redo()
        XCTAssertEqual(r.show, "see https://example.com/a?b=1\nline two 🍕\nnext|")
    }

    // --- AI answers ---------------------------------------------------------------------------------------------------

    func testFillTypedOutIsOneStepAndUndoMidTypingTakesItAllBack() {
        let r = Rig("Dear team, <<say thanks>>")
        let answer = "thank you all for the hard work"
        XCTAssertTrue(r.editor.beginFill(expect: "<<say thanks>>", selection: false, full: answer, typeOut: true))
        XCTAssertEqual(r.show, "Dear team, |")
        r.editor.appendFill("thank ")
        r.editor.appendFill("you ")
        XCTAssertEqual(r.steps, 1)
        XCTAssertEqual(r.show, "Dear team, thank you |")
        // Undo while it is still typing: the keyboard stops the typing, and all of it goes.
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "Dear team, <<say thanks>>|")
        // Redo writes the whole answer.
        XCTAssertTrue(r.redo())
        XCTAssertEqual(r.show, "Dear team, " + answer + "|")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "Dear team, <<say thanks>>|")
    }

    func testFillFinishedIsOneStep() {
        let r = Rig("x <<q>>")
        XCTAssertTrue(r.editor.beginFill(expect: "<<q>>", selection: false, full: "one two three", typeOut: true))
        for chunk in ["one ", "two ", "three"] { r.now += 0.05; r.editor.appendFill(chunk) }
        r.editor.endFill()
        r.keys(" four")
        XCTAssertEqual(r.steps, 2)
        r.undo()
        XCTAssertEqual(r.show, "x one two three|")
        r.undo()
        XCTAssertEqual(r.show, "x <<q>>|")
        r.redo()
        XCTAssertEqual(r.show, "x one two three|")
    }

    func testRewriteOfASelection() {
        let r = Rig("say it badly here")
        r.tap(7, 12)
        XCTAssertFalse(r.editor.beginFill(expect: "other", selection: true, full: "well", typeOut: false))
        XCTAssertTrue(r.editor.beginFill(expect: "badly", selection: true, full: "well", typeOut: false))
        XCTAssertEqual(r.show, "say it well| here")
        XCTAssertEqual(r.steps, 1)
        r.undo()
        XCTAssertEqual(r.show, "say it badly| here")
        r.redo()
        XCTAssertEqual(r.show, "say it well| here")
    }

    func testCursorMovedMidAnswerMakesASecondStep() {
        let r = Rig("a <<q>> z")
        r.tap(7)
        XCTAssertTrue(r.editor.beginFill(expect: "<<q>>", selection: false, full: "one two", typeOut: true))
        r.editor.appendFill("one ")
        r.tap(0)
        r.editor.appendFill("two")
        r.editor.endFill()
        XCTAssertEqual(r.show, "two|a one  z")
        XCTAssertEqual(r.steps, 2)
        r.undo()
        XCTAssertEqual(r.show, "|a one  z")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "a <<q>>| z")
    }

    func testATagThatIsNotThereIsNotFilled() {
        let r = Rig("no tag here")
        XCTAssertFalse(r.editor.beginFill(expect: "<<q>>", selection: false, full: "x", typeOut: false))
        XCTAssertEqual(r.show, "no tag here|")
        XCTAssertEqual(r.steps, 0)
    }

    // --- text the keyboard did not type -------------------------------------------------------------------------------

    func testUndoingADeleteSomewhereElsePutsNothingBack() {
        let r = Rig("keep this word")
        r.editor.deleteBefore("word")
        XCTAssertEqual(r.show, "keep this |")
        // The app replaced the text (or the cursor is in other text): the deleted word is not typed into it.
        r.field.text = Array("something else entirely")
        r.field.tap(9)
        XCTAssertFalse(r.undo())
        XCTAssertEqual(r.show, "something| else entirely")
        XCTAssertFalse(r.editor.canUndo)
        XCTAssertFalse(r.editor.canRedo)
    }

    func testOutsideEditNextToTheStepIsNotUndoneOver() {
        let r = Rig()
        r.keys("hello world")
        // The app's own paste put text after the word.
        r.field.insert("!!")
        XCTAssertFalse(r.undo())
        XCTAssertEqual(r.show, "hello world!!|")
        XCTAssertFalse(r.editor.canUndo)
    }

    func testOutsideEditInsideTheStepLeavesTheTextByteForByte() {
        let r = Rig()
        r.keys("hello ")
        r.editor.insert("pasted text", kind: .paste)
        r.field.text = Array("hello pasted TEXT")
        XCTAssertFalse(r.undo())
        XCTAssertEqual(r.show, "hello pasted TEXT|")
        XCTAssertFalse(r.editor.canUndo)
    }

    func testAMismatchDropsRedoToo() {
        let r = Rig()
        r.keys("one two three ")
        r.undo()
        XCTAssertTrue(r.editor.canRedo)
        r.field.text = Array("one 2 ")
        r.field.tap(6)
        XCTAssertFalse(r.undo())
        XCTAssertFalse(r.editor.canUndo)
        XCTAssertFalse(r.editor.canRedo)
    }

    func testStaleRedoIsRefused() {
        let r = Rig()
        r.keys("one two ")
        r.undo()
        XCTAssertEqual(r.show, "one |")
        r.field.insert("x")
        XCTAssertFalse(r.redo())
        XCTAssertEqual(r.show, "one x|")
        XCTAssertFalse(r.editor.canRedo)
    }

    func testAuditDimsTheArrowsWhenTheFieldWasEmptied() {
        let r = Rig()
        var told = 0
        r.editor.onChange = { told += 1 }
        r.keys("a message ")
        XCTAssertTrue(r.editor.canUndo)
        XCTAssertEqual(told, 1)
        // The app sent the message and cleared its field.
        r.field.text = []
        r.field.tap(0)
        r.editor.audit()
        XCTAssertFalse(r.editor.canUndo)
        XCTAssertEqual(told, 2)
    }

    func testAnEchoOfTheKeyboardsOwnKeyEndsNoStep() {
        let r = Rig()
        r.keys("hel")
        r.editor.audit()
        r.keys("lo")
        XCTAssertEqual(r.typed, ["hello"])
    }

    func testANewFieldForgetsTheOldOne() {
        let r = Rig()
        r.keys("one two ")
        r.undo()
        r.editor.attach(recordable: true)
        XCTAssertFalse(r.editor.canUndo)
        XCTAssertFalse(r.editor.canRedo)
        XCTAssertFalse(r.undo())
        XCTAssertEqual(r.show, "one |")
    }

    func testPasswordFieldsRecordNothingAndSendTheSameCalls() {
        let r = Rig()
        r.editor.attach(recordable: false)
        r.keys("secret")
        r.editor.type("()", caretBack: 1)
        r.backspace()
        r.editor.insert("x", kind: .paste)
        r.editor.replaceBefore("x", with: "y", kind: .correction)
        XCTAssertEqual(r.show, "secrety|)")
        XCTAssertEqual(r.steps, 0)
        XCTAssertFalse(r.editor.canUndo)
        XCTAssertFalse(r.undo())
        XCTAssertEqual(r.show, "secrety|)")
    }

    func testArrowsDimAndLight() {
        let r = Rig()
        var told = 0
        r.editor.onChange = { told += 1 }
        XCTAssertFalse(r.editor.canUndo); XCTAssertFalse(r.editor.canRedo)
        r.keys("hi")
        XCTAssertTrue(r.editor.canUndo); XCTAssertFalse(r.editor.canRedo)
        XCTAssertEqual(told, 1)
        r.undo()
        XCTAssertFalse(r.editor.canUndo); XCTAssertTrue(r.editor.canRedo)
        XCTAssertEqual(told, 2)
        r.redo()
        XCTAssertTrue(r.editor.canUndo); XCTAssertFalse(r.editor.canRedo)
        XCTAssertEqual(told, 3)
    }

    func testOversizedStepIsMadeButNotKept() {
        let r = Rig()
        r.keys("small ")
        let big = String(repeating: "x", count: EditHistory.maxStep + 1)
        r.editor.insert(big, kind: .paste)
        XCTAssertEqual(r.field.text.count, 6 + big.count)
        XCTAssertFalse(r.editor.canUndo)
    }

    func testPicturesAreNotTakenOut() {
        let r = Rig("a\u{FFFC}")
        r.backspace()
        XCTAssertEqual(r.show, "a|")
        XCTAssertEqual(r.steps, 0)
    }

    // --- the window iOS shows -----------------------------------------------------------------------------------------

    func testLinesUndoWhereIosShowsOnlyTheCursorsLine() {
        let r = Rig()
        r.field.lineWindow = true
        r.keys("first line")
        r.now += 0.1; r.editor.insert("\n", kind: .enter)
        r.keys("second ")
        XCTAssertEqual(r.typed, ["first ", "line", "\n", "second "])
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "first line\n|")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "first line|")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "first |")
        XCTAssertTrue(r.redo()); XCTAssertTrue(r.redo()); XCTAssertTrue(r.redo())
        XCTAssertEqual(r.show, "first line\nsecond |")
    }

    func testEnterThenBackspaceAtALinesStart() {
        let r = Rig()
        r.field.lineWindow = true
        r.keys("one")
        r.now += 0.1; r.editor.insert("\n", kind: .enter)
        r.backspace()
        XCTAssertEqual(r.show, "one|")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "one\n|")
        XCTAssertTrue(r.undo())
        XCTAssertEqual(r.show, "one|")
    }

    func testAnEmptyWindowIsNotTakenForTheStepsText() {
        let r = Rig()
        r.field.lineWindow = true
        r.keys("first")
        r.now += 0.1; r.editor.insert("\n", kind: .enter)
        r.editor.insert("two\nlines\n", kind: .paste)
        // The field was cleared: at a line's start iOS shows the same nothing, and no paste is deleted on trust.
        r.field.text = Array("other\n")
        r.field.tap(6)
        XCTAssertFalse(r.undo())
        XCTAssertEqual(r.show, "other\n|")
    }

    // --- everything at once -------------------------------------------------------------------------------------------

    /// Random edits, then undo all the way and redo all the way: every snapshot comes back, text and cursor.
    func testFuzzUndoThenRedoReproducesEverySnapshot() {
        var seed: UInt64 = 0x9E3779B97F4A7C15
        func next(_ n: Int) -> Int {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Int((seed >> 33) % UInt64(n))
        }
        let words = ["alpha", "beta", "gamma", "héllo", "😀", "x", "the", "teh"]
        for round in 0..<40 {
            let r = Rig()
            r.field.lazyMoves = round % 2 == 1
            var shots = [r.show]
            func shoot() { if r.steps != shots.count - 1 { shots.append(r.show) } else { shots[shots.count - 1] = r.show } }
            for _ in 0..<30 {
                r.now += Double(next(5))
                switch next(10) {
                case 0, 1, 2: r.keys(words[next(words.count)] + " ")
                case 3: r.backspace(1 + next(6))
                case 4: r.editor.insert(words[next(words.count)], kind: .paste)
                case 5: r.editor.type("()", caretBack: 1); r.field.pump()
                case 6: r.editor.insertAtLineStart("- "); r.field.pump()
                case 7: r.now += 0.1; r.editor.insert("\n", kind: .enter)
                case 8:
                    let w = words[next(words.count)]
                    r.keys(w)
                    r.editor.replaceBefore(w, with: w.uppercased() + " ", kind: .correction, typedFirst: " ")
                default: r.editor.replaceSelection(kind: .wrap) { ("**" + $0 + "**", 2) }; r.field.pump()
                }
                // A snapshot for every step: the text as the newest step left it.
                while shots.count - 1 > r.steps { shots.removeLast() }
                while shots.count - 1 < r.steps { shots.append(r.show) }
                shots[r.steps] = r.show
            }
            let final = r.show
            var texts: [String] = [String(r.field.text)]
            while r.editor.canUndo { XCTAssertTrue(r.undo(), "round \(round)"); texts.append(String(r.field.text)) }
            XCTAssertEqual(String(r.field.text), "", "round \(round)")
            var i = texts.count - 1
            while r.editor.canRedo {
                XCTAssertTrue(r.redo(), "round \(round)")
                i -= 1
                XCTAssertEqual(String(r.field.text), texts[i], "round \(round)")
            }
            XCTAssertEqual(r.show, final, "round \(round)")
        }
    }

    /// A field that does as it likes between the keyboard's edits never gets text put in, or taken out, by an undo
    /// that was not exactly a step's own: after any undo or redo the text is the one before it, or one edit away.
    /// Whether `now` is `was` with one copy of `out` replaced by `put`.
    private func swapped(_ was: String, _ now: String, _ out: String, _ put: String) -> Bool {
        let w = Array(was), o = Array(out)
        guard w.count >= o.count else { return false }
        for i in 0...(w.count - o.count) where Array(w[i..<(i + o.count)]) == o {
            if String(w[..<i]) + put + String(w[(i + o.count)...]) == now { return true }
        }
        return false
    }

    func testFuzzHostileFieldOnlyEverChangesByAStepsOwnText() {
        var seed: UInt64 = 42
        func next(_ n: Int) -> Int {
            seed = seed &* 6364136223846793005 &+ 1442695040888963407
            return Int((seed >> 33) % UInt64(n))
        }
        for _ in 0..<60 {
            let r = Rig()
            for _ in 0..<12 {
                r.now += Double(next(4))
                switch next(6) {
                case 0, 1: r.keys(["one ", "two ", "three. "][next(3)])
                case 2: r.backspace(1 + next(5))
                case 3:
                    // The app rewrites, or the person taps somewhere.
                    if next(2) == 0 { r.field.text = Array(["", "zzz", "one two", "x\ny"][next(4)]) }
                    r.field.tap(next(r.field.text.count + 1))
                    if next(2) == 0 { r.editor.audit() }
                case 4:
                    let was = String(r.field.text)
                    let done = r.undo()
                    let now = String(r.field.text)
                    if done, now != was {
                        let s = r.editor.history.redo.last
                        XCTAssertNotNil(s)
                        if let s { XCTAssertTrue(swapped(was, now, s.inserted, s.removed), "\(was) -> \(now)") }
                    } else {
                        XCTAssertEqual(now, was)
                    }
                default:
                    let was = String(r.field.text)
                    let done = r.redo()
                    let now = String(r.field.text)
                    if done, now != was {
                        let s = r.editor.history.undo.last
                        XCTAssertNotNil(s)
                        if let s { XCTAssertTrue(swapped(was, now, s.removed, s.inserted), "\(was) -> \(now)") }
                    } else {
                        XCTAssertEqual(now, was)
                    }
                }
            }
        }
    }
}
