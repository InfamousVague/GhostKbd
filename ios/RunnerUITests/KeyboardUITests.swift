import XCTest

/// Drives the keyboard extension in a real app (Safari, on a page with a text area) on the Simulator.
///
/// Before running: the app is installed, Ghost Keyboard is the only keyboard in the Simulator's list, and
/// `python3 ios/tool/stub_server.py` is serving on 127.0.0.1:8765 (ios/tool/ui_test.sh does all three).
final class KeyboardUITests: XCTestCase {
    let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")

    override func setUp() {
        continueAfterFailure = true
    }

    func openPad() {
        safari.launch()
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        _ = springboard
        // The address bar: wherever this iOS puts it.
        let bar = safari.textFields["Address"].exists ? safari.textFields["Address"] : safari.textFields.firstMatch
        if bar.waitForExistence(timeout: 8) { bar.tap() } else { safari.buttons["Address"].firstMatch.tap() }
        safari.typeText("http://127.0.0.1:8765/pad\n")
        XCTAssertTrue(pad.waitForExistence(timeout: 10), "the pad page did not load")
    }

    var pad: XCUIElement { safari.webViews.textViews["pad"].exists ? safari.webViews.textViews["pad"] : safari.webViews.textViews.firstMatch }
    var text: String { (pad.value as? String) ?? "" }

    // The keyboard runs in its own process and iOS shows a UI test nothing inside it, so keys are tapped where the
    // layout (the same Layouts.swift the keyboard draws from) puts them.

    var page: Page = .letters
    /// The strip iOS keeps under a keyboard on an iPhone with no home button (its globe and dictation keys).
    var systemBar: CGFloat = 75
    let rowHeight: CGFloat = 54
    let rowStrip: CGFloat = 44

    lazy var screenSize: CGSize = XCUIApplication(bundleIdentifier: "com.apple.springboard").frame.size
    lazy var origin = XCUIApplication(bundleIdentifier: "com.apple.springboard").coordinate(withNormalizedOffset: .zero)

    func rows() -> [KeyRow] { Layouts.rows(for: page, numberRow: false) }

    func point(row r: Int, where match: (Key) -> Bool) -> CGPoint? {
        let screen = screenSize
        let all = rows()
        let rowH = rowHeight * 4 / CGFloat(all.count)
        let keysTop = screen.height - systemBar - 2 - rowHeight * 4
        let unit = (screen.width - 3) / 10
        for (i, row) in all.enumerated() where r < 0 || r == i {
            var x = 1.5 + row.inset * unit + (10 - row.units) * unit / 2
            for k in row.keys {
                if match(k) { return CGPoint(x: x + k.width * unit / 2, y: keysTop + CGFloat(i) * rowH + rowH / 2 + 3) }
                x += k.width * unit
            }
        }
        return nil
    }

    func tap(_ p: CGPoint, hold: TimeInterval = 0) {
        let c = origin.withOffset(CGVector(dx: p.x, dy: p.y))
        hold > 0 ? c.press(forDuration: hold) : c.tap()
    }

    /// Taps a key by what it types ("q", "(¦)"), or by name: space, delete, shift, enter, ?123, ABC, { }, =\<.
    func key(_ name: String, hold: TimeInterval = 0) {
        let p: CGPoint?
        switch name {
        case "space": p = point(row: -1) { $0.type == .space }
        case "delete": p = point(row: -1) { $0.type == .delete }
        case "shift": p = point(row: -1) { $0.type == .shift }
        case "enter": p = point(row: -1) { $0.type == .enter }
        case "?123", "ABC", "{ }", "=\\<": p = point(row: -1) { $0.type == .mode && $0.label == name }
        default: p = point(row: -1) { $0.type == .char && $0.text == name }
        }
        guard let p else { XCTFail("no key \(name) on \(page)"); return }
        tap(p, hold: hold)
        switch name {
        case "?123": page = .symbols
        case "ABC": page = .letters
        case "{ }": page = .dev
        case "=\\<": page = .more
        case ",": if page == .dev { page = .letters }
        default: break
        }
    }

    func type(_ s: String) {
        for c in s { key(c == " " ? "space" : String(c).lowercased()) }
    }

    /// Something in the row above the keys, by how far across it is (0 to 1).
    func tapRowStrip(at fraction: CGFloat) {
        let screen = screenSize
        tap(CGPoint(x: screen.width * fraction, y: screen.height - systemBar - 2 - rowHeight * 4 - rowStrip / 2))
    }

    /// Whether Ghost Keyboard is the keyboard showing: Apple's own is in the app's tree, ours is not.
    var ghostShowing: Bool { !safari.keyboards.firstMatch.exists && !safari.buttons["Next keyboard"].exists }

    func shot(_ name: String) {
        let a = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        a.name = name; a.lifetime = .keepAlways
        add(a)
        // With SHOTS set (TEST_RUNNER_SHOTS=<folder> for xcodebuild), the picture is written there too.
        if let dir = ProcessInfo.processInfo.environment["SHOTS"] { try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png")) }
    }

    // --- setup, through the Settings app as a person does it -------------------------------------------------------

    let settings = XCUIApplication(bundleIdentifier: "com.apple.Preferences")

    /// Taps the row called `label`: the lowest thing on screen with that name (a page's title has it too).
    func tapRow(_ label: String, in app: XCUIApplication, timeout: TimeInterval = 6) -> Bool {
        let all = app.descendants(matching: .any).matching(NSPredicate(format: "label == %@ AND (elementType == %d OR elementType == %d OR elementType == %d)", label, XCUIElement.ElementType.cell.rawValue, XCUIElement.ElementType.button.rawValue, XCUIElement.ElementType.staticText.rawValue))
        guard all.firstMatch.waitForExistence(timeout: timeout) else { return false }
        let row = all.allElementsBoundByIndex.filter { $0.isHittable }.max { $0.frame.minY < $1.frame.minY } ?? all.firstMatch
        row.tap()
        return true
    }

    func openKeyboards() {
        settings.terminate()
        settings.launch()
        XCTAssertTrue(tapRow("General", in: settings), "Settings › General\n\(settings.debugDescription)")
        XCTAssertTrue(tapRow("Keyboard", in: settings, timeout: 3) || tapRow("Keyboards", in: settings), "General › Keyboard")
        sleep(1)
        XCTAssertTrue(tapRow("Keyboards", in: settings), "Keyboard › Keyboards")
        sleep(1)
    }

    /// Adds Ghost Keyboard in Settings › General › Keyboard › Keyboards › Add New Keyboard…
    func test0a_AddTheKeyboard() {
        openKeyboards()
        if settings.staticTexts["Ghost Keyboard"].waitForExistence(timeout: 2) { return }
        XCTAssertTrue(tapRow("Add New Keyboard", in: settings, timeout: 3) || tapRow("Add New Keyboard...", in: settings, timeout: 2) || tapRow("Add New Keyboard…", in: settings, timeout: 2), "Add New Keyboard\n\(settings.debugDescription)")
        XCTAssertTrue(tapRow("Ghost Keyboard", in: settings, timeout: 8), "Ghost Keyboard is not offered\n\(settings.debugDescription)")
        XCTAssertTrue(settings.staticTexts["Ghost Keyboard"].waitForExistence(timeout: 5))
        shot("added")
    }

    func setFullAccess(_ on: Bool) {
        openKeyboards()
        XCTAssertTrue(tapRow("Ghost Keyboard", in: settings), "Ghost Keyboard's row")
        let toggle = settings.switches.matching(NSPredicate(format: "label CONTAINS %@", "Full Access")).firstMatch
        XCTAssertTrue(toggle.waitForExistence(timeout: 5), "Allow Full Access switch\n\(settings.debugDescription)")
        if ((toggle.value as? String) == "1") != on {
            toggle.switches.firstMatch.exists ? toggle.switches.firstMatch.tap() : toggle.tap()
            let allow = settings.alerts.buttons["Allow"]
            if on, allow.waitForExistence(timeout: 4) { allow.tap() }
        }
        sleep(1)
        XCTAssertEqual((toggle.value as? String) == "1", on)
        shot("full-access-\(on)")
    }

    /// Debug builds take settings from a link (SettingsChannel.open): opened here, with iOS's "Open in…?" answered.
    func set(_ query: String) {
        let app = XCUIApplication(bundleIdentifier: "com.infamousvague.ghostkbd")
        app.launch()
        sleep(2)
        XCUIDevice.shared.system.open(URL(string: "ghostkbd://debug-set?\(query)")!)
        let open = XCUIApplication(bundleIdentifier: "com.apple.springboard").buttons["Open"]
        if open.waitForExistence(timeout: 4) { open.tap() }
        sleep(2)
    }

    let stub = "plugins=autocorrect,swipe,clipboard,undo,mockingbird,ghostwriter,rewrite&ai_provider=custom&ai_base_url=http://127.0.0.1:8765/v1&ai_key_custom=test&ai_model_custom=stub-1&ghostwriter_typing=false&learned_words="

    /// Ghostwriter added and pointed at the stand-in provider, the agreement not given.
    func test0d_UseTheStubProviderWithoutConsent() { set(stub + "&ai_consent=false") }
    /// The same, agreed to.
    func test0e_UseTheStubProviderWithConsent() { set(stub + "&ai_consent=true") }

    func test0b_TurnFullAccessOff() { setFullAccess(false) }
    func test0c_TurnFullAccessOn() { setFullAccess(true) }

    /// Brings the keyboard up and does nothing else, then types: for reading its memory from outside (footprint).
    func testMemoryIdle() { openPad(); pad.tap(); sleep(6) }
    func testMemoryTyping() { openPad(); pad.tap(); sleep(2); type("teh keybkard wrold tommorow definately recieve "); sleep(2) }

    func test1_TypesCorrectsAndSwitchesPages() {
        openPad()
        pad.tap()
        sleep(2)
        shot("letters")
        // Auto-capitals at the start, autocorrect on space, double-space full stop.
        type("teh keybkard works")
        key("space"); key("space")
        XCTAssertEqual(text, "The keyboard works. ", "typed text")
        shot("typed")
        // Backspace straight after a correction puts the typed word back.
        type("dont ")
        XCTAssertTrue(text.hasSuffix("Don't ") || text.hasSuffix("Don’t "), text)
        key("delete")
        XCTAssertTrue(text.hasSuffix("Dont"), text)
        // Pages: ?123, the rarer symbols, the developer page's pairs leave the cursor inside.
        key("?123")
        key("1"); key("@")
        shot("symbols")
        key("=\\<")
        shot("more")
        key("~")
        key("ABC")
        key("{ }")
        shot("developer")
        key("(¦)")
        key(",")
        type("x")
        XCTAssertTrue(text.hasSuffix("Dont1@~(,x)"), text)
        // Shift: one capital, then back. A long press: the alternates, the first of them typed on release.
        key("shift"); type("ab")
        XCTAssertTrue(text.contains("Ab)"), text)
        key("e", hold: 0.8)
        XCTAssertTrue(text.contains("Ab3)"), text)
        shot("end")
    }

    func typeRequest() {
        // <<standup>> through the developer page's < > pair key, which leaves the cursor inside.
        key("{ }"); key("<¦>"); key("<¦>"); key("ABC")
        type("standup")
    }

    func test2_GhostwriterFillsARequestFromTheStubbedProvider() {
        openPad()
        pad.tap()
        sleep(2)
        typeRequest()
        sleep(1)
        shot("request")
        key("enter")
        let done = NSPredicate { _, _ in self.text.contains("Stubbed answer.") }
        wait(for: [XCTNSPredicateExpectation(predicate: done, object: nil)], timeout: 20)
        shot("filled")
        XCTAssertFalse(text.contains("<<"), "the tag should be gone: \(text)")
        XCTAssertTrue(text.contains("Stubbed answer."), text)
    }

    /// Without Full Access (or without the agreement in the app) the keyboard still types and corrects; a request is
    /// not sent, and the row says why (see the screenshot attached).
    func test3_WithoutFullAccessItTypesAndSendsNothing() {
        openPad()
        pad.tap()
        sleep(2)
        type("teh wrold ")
        XCTAssertEqual(text, "The world ")
        typeRequest()
        sleep(1)
        shot("ai-off")
        key("enter")
        sleep(3)
        XCTAssertFalse(text.contains("Stubbed answer."), text)
    }

    /// The row: undo takes back what the keyboard typed, an edit at a time; Rewrite sends the text and replaces it.
    /// Needs Full Access and the stub provider agreed to (test0c, test0e).
    func test4_UndoAndRewriteFromTheRow() {
        openPad()
        pad.tap()
        sleep(2)
        type("teh cat ")
        XCTAssertEqual(text, "The cat ")
        sleep(1)
        shot("tools")
        // The row with nothing being typed: Rewrite, undo, redo, the clipboard.
        tapRowStrip(at: 0.40)
        XCTAssertEqual(text, "The cat", "undo takes back the space")
        // A word is under the cursor again, so the row is suggestions; a space brings the tools back.
        key("space")
        sleep(1)
        tapRowStrip(at: 0.23)
        sleep(1)
        shot("rewrite-modes")
        tapRowStrip(at: 0.29)
        let done = NSPredicate { _, _ in self.text == "Stubbed answer." }
        wait(for: [XCTNSPredicateExpectation(predicate: done, object: nil)], timeout: 20)
        shot("rewritten")
    }

    func drag(from a: CGPoint, to b: CGPoint) {
        origin.withOffset(CGVector(dx: a.x, dy: a.y)).press(forDuration: 0.05, thenDragTo: origin.withOffset(CGVector(dx: b.x, dy: b.y)), withVelocity: 120, thenHoldForDuration: 0.1)
    }

    /// Cursor control: a slide along the space bar moves the cursor; a slide left from delete takes whole words.
    func test5_SpaceBarCursorAndWordDelete() {
        openPad()
        pad.tap()
        sleep(2)
        type("one two three")
        XCTAssertEqual(text, "One two three")
        let space = point(row: -1) { $0.type == .space }!
        drag(from: space, to: CGPoint(x: space.x - 70, y: space.y))
        type("x")
        let moved = text
        XCTAssertTrue(moved.contains("x") && !moved.hasSuffix("x"), "the x should land before the end: \(moved)")
        let delete = point(row: -1) { $0.type == .delete }!
        drag(from: delete, to: CGPoint(x: delete.x - 90, y: delete.y))
        XCTAssertTrue(text.count < moved.count - 3, "words should be gone: \(text)")
        shot("gestures")
    }

    /// A preset theme with key borders and the number row, and Ghostwriter typing its answer out key by key.
    func test6_RetroThemeAndTheTypingEffect() {
        set(stub + "&ai_consent=true&plugins=autocorrect,swipe,clipboard,undo,themes,ghostwriter&theme_preset=retro&number_row=true&ghostwriter_typing=true")
        openPad()
        pad.tap()
        sleep(2)
        shot("retro")
        // The number row adds a row above: the layout the taps are placed by must know.
        set(stub + "&ai_consent=true&plugins=autocorrect,swipe,clipboard,undo,themes,ghostwriter&theme_preset=retro&number_row=false&ghostwriter_typing=true")
        openPad()
        pad.tap()
        sleep(2)
        typeRequest()
        key("enter")
        let done = NSPredicate { _, _ in self.text.contains("Stubbed answer.") }
        _ = XCTWaiter().wait(for: [XCTNSPredicateExpectation(predicate: done, object: nil)], timeout: 20)
        shot("typed-out")
        XCTAssertTrue(text.contains("Stubbed answer."), text)
        set(stub + "&ai_consent=true&theme_preset=ghost&theme=light&key_borders=true")
        openPad()
        pad.tap()
        sleep(2)
        shot("light-borders")
        set(stub + "&ai_consent=true&theme=dark&key_borders=false&ghostwriter_typing=false")
    }
}
