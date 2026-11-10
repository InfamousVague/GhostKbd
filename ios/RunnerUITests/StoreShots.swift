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
}
