import XCTest
import CoreGraphics
@testable import GhostCore

/// Android's dictionaries, in the repository: android/app/src/main/assets/dict.
let dictDirectory: URL = {
    var url = URL(fileURLWithPath: #filePath)
    for _ in 0..<5 { url.deleteLastPathComponent() }
    return url.appendingPathComponent("android/app/src/main/assets/dict")
}()

func residentBytes() -> UInt64 {
    var info = mach_task_basic_info()
    var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size / MemoryLayout<natural_t>.size)
    let kr = withUnsafeMutablePointer(to: &info) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(MACH_TASK_BASIC_INFO), $0, &count) } }
    return kr == KERN_SUCCESS ? info.resident_size : 0
}

/// A phone-sized QWERTY: 36-point keys, 54-point rows, Gboard's stagger.
let qwertyKeys: [Character: CGPoint] = {
    var keys: [Character: CGPoint] = [:]
    for (r, row) in [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.5)].enumerated() {
        for (i, c) in row.0.enumerated() { keys[c] = CGPoint(x: (Double(i) + row.1 + 0.5) * 36, y: Double(r) * 54 + 27) }
    }
    return keys
}()

/// A finger gliding through `letters`' keys in straight lines, a point every few points of travel.
func glide(_ letters: String, wobble: CGFloat = 0) -> [CGPoint] {
    let stops = letters.map { qwertyKeys[$0]! }
    var path: [CGPoint] = []
    for i in 1..<stops.count {
        let a = stops[i - 1], b = stops[i]
        let steps = max(2, Int(hypot(b.x - a.x, b.y - a.y) / 6))
        for s in 0..<steps {
            let t = CGFloat(s) / CGFloat(steps)
            path.append(CGPoint(x: a.x + (b.x - a.x) * t + wobble * sin(CGFloat(path.count)), y: a.y + (b.y - a.y) * t + wobble * cos(CGFloat(path.count))))
        }
    }
    path.append(stops[stops.count - 1])
    return path
}

final class AutocorrectTests: XCTestCase {
    static let english: Autocorrect = {
        let ac = Autocorrect(dictDirectory: dictDirectory)
        ac.loadNow(languages: ["en"])
        return ac
    }()

    private var en: Autocorrect { AutocorrectTests.english }

    override func setUp() {
        en.setLearned([])
        en.setSlang(false)
    }

    func testDictionaryIsThere() {
        XCTAssertTrue(FileManager.default.fileExists(atPath: dictDirectory.appendingPathComponent("en.tsv").path), "\(dictDirectory.path)")
        XCTAssertTrue(en.isReady)
        XCTAssertGreaterThan(en.wordCount, 39_000)
    }

    func testNothingUntilLoaded() {
        let ac = Autocorrect(dictDirectory: dictDirectory)
        XCTAssertFalse(ac.isReady)
        XCTAssertNil(ac.correct("teh"))
        XCTAssertEqual(ac.suggest("teh"), Autocorrect.Suggestions(words: [], fix: nil, typedIsWord: false))
        XCTAssertEqual(ac.swipe(path: glide("helo"), keys: qwertyKeys, keyW: 36), [])
        let missing = Autocorrect(dictDirectory: URL(fileURLWithPath: "/nonexistent-dictionaries"))
        missing.loadNow(languages: ["en"])
        XCTAssertNil(missing.correct("teh"))   // only the developer words loaded: nothing near enough
        XCTAssertNil(missing.correct("npm"))
    }

    func testCorrections() {
        XCTAssertEqual(en.correct("teh"), "the")
        XCTAssertEqual(en.correct("keybkard"), "keyboard")
        XCTAssertEqual(en.correct("dont"), "don't")
        XCTAssertEqual(en.correct("i"), "I")
        XCTAssertEqual(en.correct("im"), "I'm")
        XCTAssertEqual(en.correct("recieve"), "receive")
        XCTAssertEqual(en.correct("thier"), "their")
        XCTAssertEqual(en.correct("adn"), "and")
    }

    func testLeftAlone() {
        for word in ["hello", "the", "keyboard", "don't", "I"] { XCTAssertNil(en.correct(word), word) }
        // Acronyms and deliberate capitals.
        for word in ["NASA", "TEH", "iPhone", "camelCase", "McDonald", "tEh"] { XCTAssertNil(en.correct(word), word) }
        // The words of a programmer's day.
        for word in ["npm", "async", "kubectl", "json"] { XCTAssertNil(en.correct(word), word) }
        XCTAssertNil(en.correct(""))
        XCTAssertNil(en.correct("abcdefghijklmnopqrstuvwxyz"))
        XCTAssertNil(en.correct("zxqvj"))
    }

    func testRecase() {
        XCTAssertEqual(en.correct("Teh"), "The")
        XCTAssertEqual(en.correct("Dont"), "Don't")
        XCTAssertEqual(en.correct("Keybkard"), "Keyboard")
        XCTAssertEqual(en.suggest("TEH").fix, "THE")
    }

    func testLearnedWords() {
        XCTAssertEqual(en.correct("wrod"), "word")
        en.setLearned(["wrod", "ghostkbd"])
        XCTAssertNil(en.correct("wrod"))
        XCTAssertNil(en.correct("Wrod"))
        XCTAssertNil(en.correct("ghostkbd"))
        // A learned word is a target for slips of it, and is offered as a completion.
        XCTAssertEqual(en.correct("ghostkbf"), "ghostkbd")
        XCTAssertTrue(en.suggest("ghostk").words.contains("ghostkbd"))
        XCTAssertTrue(en.suggest("wrod").typedIsWord)
    }

    func testSlang() throws {
        let text = try String(contentsOf: dictDirectory.appendingPathComponent("en_slang.txt"), encoding: .utf8)
        let slang = text.split(whereSeparator: { $0.isNewline }).map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty && !$0.hasPrefix("#") }
        XCTAssertTrue(slang.contains("sybau"))
        let changedWhenOff = slang.filter { en.correct($0) != nil }
        XCTAssertFalse(changedWhenOff.isEmpty, "some slang is corrected with the switch off")
        en.setSlang(true)
        for word in slang { XCTAssertNil(en.correct(word), word) }
        XCTAssertTrue(en.suggest("sybau").typedIsWord)
        XCTAssertTrue(en.suggest("lmf").words.contains("lmfao"))
        en.setSlang(false)
        XCTAssertEqual(slang.filter { en.correct($0) != nil }, changedWhenOff)
    }

    func testSuggest() {
        let keyb = en.suggest("keyb")
        XCTAssertFalse(keyb.typedIsWord)
        XCTAssertTrue(keyb.words.contains("keyboard"))
        XCTAssertLessThanOrEqual(keyb.words.count, 5)
        let th = en.suggest("th")
        XCTAssertEqual(Array(th.words.prefix(2)), ["th", "the"])
        XCTAssertEqual(en.suggest("Hel").words.first, "Hel")
        XCTAssertTrue(en.suggest("Hel").words.contains("Help"))
        let dont = en.suggest("dont")
        XCTAssertEqual(dont.fix, "don't"); XCTAssertEqual(dont.words.first, "don't"); XCTAssertTrue(dont.typedIsWord)
        XCTAssertEqual(en.suggest("i").words.first, "I")
        XCTAssertEqual(en.suggest("teh").fix, "the")
        XCTAssertEqual(Set(th.words).count, th.words.count)
    }

    func testAccentFolding() {
        XCTAssertEqual(Autocorrect.fold("señor"), "senor")
        XCTAssertEqual(Autocorrect.fold("café"), "cafe")
        XCTAssertEqual(Autocorrect.fold("straße"), "strasse")
        XCTAssertEqual(Autocorrect.fold("Ångström"), "Angstrom")
        XCTAssertEqual(Autocorrect.fold("plain"), "plain")

        let ac = Autocorrect(dictDirectory: dictDirectory)
        ac.loadNow(languages: ["en", "es"])
        XCTAssertEqual(ac.languages, ["en", "es"])
        XCTAssertEqual(ac.correct("manana"), "mañana")
        XCTAssertEqual(ac.correct("Manana"), "Mañana")
        XCTAssertEqual(ac.suggest("manana").words.first, "mañana")
        // English still works beside it.
        XCTAssertEqual(ac.correct("teh"), "the")
        XCTAssertNil(ac.correct("mañana"))
        // As Android: es.tsv itself lists "senor" (3.34) and "cafe" (3.81) above the "common" line (3.2), and a
        // common word is never changed, so these two stay as typed.
        XCTAssertNil(ac.correct("senor"))
        XCTAssertNil(ac.correct("cafe"))
    }

    func testSwipe() {
        let found = en.swipe(path: glide("helo"), keys: qwertyKeys, keyW: 36)
        XCTAssertTrue(found.contains("hello"), "\(found)")
        // "help" ends a key away and is the commoner word, so it may rank first, as on Android.
        XCTAssertTrue(found.prefix(2).contains("hello"), "\(found)")
        XCTAssertLessThanOrEqual(found.count, 5)
        XCTAssertEqual(en.swipe(path: glide("keyboard", wobble: 3), keys: qwertyKeys, keyW: 36).first, "keyboard")
        XCTAssertEqual(en.swipe(path: glide("the"), keys: qwertyKeys, keyW: 36).first, "the")
        XCTAssertEqual(en.swipe(path: [CGPoint(x: 10, y: 10)], keys: qwertyKeys, keyW: 36), [])
        XCTAssertEqual(en.swipe(path: glide("helo"), keys: qwertyKeys, keyW: 0), [])
        // A learned word glides too.
        en.setLearned(["qwop"])
        XCTAssertTrue(en.swipe(path: glide("qwop"), keys: qwertyKeys, keyW: 36).contains("qwop"))
    }

    func testBackgroundLoad() {
        let ac = Autocorrect(dictDirectory: dictDirectory)
        let ready = expectation(description: "ready")
        ac.callbackQueue = DispatchQueue(label: "test-callbacks")
        ac.onReady = { ready.fulfill() }
        ac.setLanguages(["en"])
        ac.setLanguages(["en"])   // the same again: nothing more to load
        wait(for: [ready], timeout: 20)
        XCTAssertTrue(ac.isReady)
        XCTAssertEqual(ac.correct("teh"), "the")
    }

}

/// On its own (no shared dictionary loaded first), so the resident size it prints is the dictionary's:
/// `swift test -c release -Xswiftc -enable-testing --filter AutocorrectMeasure`.
final class AutocorrectMeasure: XCTestCase {
    func testMemoryAndSpeed() {
        let before = residentBytes()
        let start = Date()
        let ac = Autocorrect(dictDirectory: dictDirectory)
        ac.loadNow(languages: ["en"])
        let loadMs = Date().timeIntervalSince(start) * 1000
        let after = residentBytes()
        XCTAssertEqual(ac.correct("keybkard"), "keyboard")

        func time(_ runs: Int, _ body: () -> Void) -> Double {
            let t = Date()
            for _ in 0..<runs { body() }
            return Date().timeIntervalSince(t) * 1000 / Double(runs)
        }
        let correctMs = time(50) { _ = ac.correct("keybkardzz") }   // ten letters, no fix: the full search
        let fixMs = time(50) { _ = ac.correct("keybkard") }
        let suggestMs = time(50) { _ = ac.suggest("keyb") }
        let path = glide("keyboard", wobble: 3)
        let swipeMs = time(20) { _ = ac.swipe(path: path, keys: qwertyKeys, keyW: 36) }
        let delta = Double(Int64(after) - Int64(before)) / 1_048_576
        #if DEBUG
        let build = "debug"
        #else
        let build = "release"
        #endif
        print(String(format: "MEASURED (%@ build) en load %.0f ms, resident +%.1f MB (%.1f → %.1f MB), correct(10 letters) %.2f ms, correct(keybkard) %.2f ms, suggest %.2f ms, swipe %.2f ms",
                     build, loadMs, delta, Double(before) / 1_048_576, Double(after) / 1_048_576, correctMs, fixMs, suggestMs, swipeMs))
        XCTAssertLessThan(delta, 30)
        XCTAssertLessThan(correctMs, 100)
    }
}
