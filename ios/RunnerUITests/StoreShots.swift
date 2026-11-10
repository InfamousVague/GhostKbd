import XCTest

/// The App Store screenshots (docs/store/ios/screenshots.md): the keyboard in the app's own "Try the keyboard" field.
/// Run after test0a and test0c, with SHOTS set to a folder and ios/tool/stub_server.py serving.
extension KeyboardUITests {
    var ghost: XCUIApplication { XCUIApplication(bundleIdentifier: "com.infamousvague.ghostkbd") }
    var base: String { stub + "&ai_consent=true&onboarded=true&theme=dark&theme_preset=ghost&key_borders=false&number_row=false&ghostwriter_typing=false" }

    /// The app open on Settings with its field focused and the keyboard up.
    func openField() {
        let app = ghost
        app.launch()
        let field = app.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10), "the try-it field\n\(app.debugDescription)")
        field.tap()
        sleep(2)
        // A new Simulator brings up Apple's keyboard first (with its glide tip): move on until ours is showing.
        if app.buttons["Continue"].exists { app.buttons["Continue"].tap(); sleep(1) }
        for _ in 0..<4 where app.buttons["Next keyboard"].exists { app.buttons["Next keyboard"].tap(); sleep(2) }
        XCTAssertFalse(app.keyboards.firstMatch.exists || app.buttons["Next keyboard"].exists, "Ghost Keyboard is not the keyboard showing")
        page = .letters
    }

    /// A screenshot taken from another thread, `after` seconds from now: for a key held down or a glide under way.
    func shotLater(_ name: String, after: TimeInterval) {
        DispatchQueue.global().asyncAfter(deadline: .now() + after) {
            if let dir = ProcessInfo.processInfo.environment["SHOTS"] { try? XCUIScreen.main.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir).appendingPathComponent(name + ".png")) }
        }
    }

    func testShots0_Setup() { set(base) }

    func testShots1_LettersAndPairs() {
        openField()
        shot("00-empty")
        key("shift")
        type("return items.filt")
        sleep(1)
        shot("01-letters")
        type("er")
        key("{ }")
        key("(¦)")
        sleep(1)
        shot("02-developer")
    }

    /// A long press on the developer page's = key, its family showing.
    func testShots2_LongPress() {
        openField()
        key("shift")
        type("if count ")
        key("{ }")
        shotLater("03-longpress", after: 1.2)
        key("=", hold: 2.4)
        sleep(1)
    }

    /// A glide across the letters, caught part-way.
    func testShots3_Swipe() {
        openField()
        type("glide to ")
        let from = point(row: -1) { $0.text == "t" }!, mid = point(row: -1) { $0.text == "y" }!, to = point(row: -1) { $0.text == "p" }!
        shotLater("04-swipe", after: 1.0)
        let a = origin.withOffset(CGVector(dx: from.x, dy: from.y)), b = origin.withOffset(CGVector(dx: mid.x, dy: mid.y + 6)), c = origin.withOffset(CGVector(dx: to.x, dy: to.y))
        _ = b
        a.press(forDuration: 0.05, thenDragTo: c, withVelocity: 90, thenHoldForDuration: 0.6)
        sleep(1)
    }

    /// The Retro keycaps with the number row, a few keys just pressed so the backlight shows.
    func testShots4_Retro() {
        set(base + "&plugins=autocorrect,swipe,clipboard,undo,themes,ghostwriter,rewrite&theme_preset=retro&number_row=true&backlight=true")
        openField()
        shot("05-retro")
        set(base)
    }

    /// A request written where the person types, and the answer in its place.
    func testShots5_Assistant() {
        openField()
        key("{ }"); key("<¦>"); key("<¦>"); key("ABC")
        type("reply that i am running ten minutes late")
        sleep(1)
        shot("06-request")
        key("enter")
        sleep(8)
        shot("07-answer")
    }

    /// The app's own pages: Look & feel.
    func testShots6_Settings() {
        let app = ghost
        app.launch()
        let row = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH %@", "Look & feel")).firstMatch
        let other = app.descendants(matching: .any).matching(NSPredicate(format: "label BEGINSWITH %@", "Look & feel")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 8) || other.exists, app.debugDescription)
        (row.exists ? row : other).tap()
        sleep(2)
        shot("08-look-and-feel")
    }
}
