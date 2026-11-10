import CoreGraphics
import Foundation

/// Typing profiles (docs/typing-profile.md, docs/typing-profile.schema.json): one open JSON file for where someone
/// aims at each key, their slips, their words and how they write, so other models can build reports and import them
/// into Ghost. Exported from what this keyboard has learned; imported into Adaptive keys, autocorrect's learned words
/// and Mockingbird.
public enum TypingProfile {
    public static let format = "ghost-typing-profile"
    public static let version = 1

    public struct ProfileError: Error, LocalizedError, Equatable {
        public let message: String
        public init(_ message: String) { self.message = message }
        public var errorDescription: String? { message }
    }

    /// What an import did, in a few lines for Settings.
    public struct Result: Equatable {
        public let lines: [String]
        public let report: String?
        public let source: String?
        public init(lines: [String], report: String?, source: String?) { self.lines = lines; self.report = report; self.source = source }
    }

    private static func round3(_ f: CGFloat) -> Double { (Double(f) * 1000).rounded() / 1000 }

    /// This keyboard's learning as a profile (pretty-printed JSON). Message samples only with `samples`, since they
    /// are the person's words.
    public static func export(prefs: KeyboardPrefs, adaptive: AdaptiveKeys, mockingbird: Mockingbird, samples: Bool, now: Date = Date()) -> String {
        let iso = ISO8601DateFormatter()
        iso.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        var o: [String: Any] = [
            "format": format, "version": version,
            "created": iso.string(from: now),
            "source": ["app": "Ghost Keyboard"],
            "layout": "qwerty",
        ]
        var keys: [String: Any] = [:]
        for (c, a) in adaptive.aims() where a.n > 0 { keys[String(c)] = ["dx": round3(a.dx), "dy": round3(a.dy), "samples": a.n] as [String: Any] }
        if !keys.isEmpty { o["keys"] = keys }
        // Each remembered slip with its point, so a report can draw them; the counts are read off them.
        let slips: [[String: Any]] = adaptive.mistakes.map { ["pressed": String($0.pressed), "meant": String($0.meant), "count": 1, "x": round3($0.x), "y": round3($0.y)] }
        if !slips.isEmpty { o["slips"] = slips }
        let learned = prefs.learnedWords
        if !learned.isEmpty { o["words"] = ["learned": learned.sorted()] }
        var style: [String: Any] = [:]
        if let p = mockingbird.profile() { style["profile"] = p.text }
        if samples {
            let a: [[String: Any]] = mockingbird.samples().map { ["text": $0.text, "kind": $0.chat ? "chat" : "writing"] }
            if !a.isEmpty { style["samples"] = a }
        }
        if !style.isEmpty { o["style"] = style }
        return Json.string(o, pretty: true)
    }

    /// "1 slip", "20 slips".
    private static func count(_ n: Int, _ one: String, _ many: String) -> String { "\(n) \(n == 1 ? one : many)" }

    private static func lowerLetter(_ s: String) -> Character? {
        guard s.count == 1, let c = s.first, let a = c.asciiValue, a >= 97, a <= 122 else { return nil }
        return c
    }

    /// Takes `text` in; refuses (throws a `ProfileError`) what isn't a version 1 typing profile. Sections whose plugin
    /// isn't added are skipped.
    public static func `import`(_ text: String, prefs: KeyboardPrefs, adaptive: AdaptiveKeys, mockingbird: Mockingbird) throws -> Result {
        guard let o = Json.object(text) else { throw ProfileError("That file isn't JSON") }
        if Json.str(o["format"]) != format { throw ProfileError("That file isn't a Ghost typing profile (no \"format\": \"\(format)\")") }
        if (Json.int(o["version"]) ?? 0) != version {
            let shown = o["version"].map { Json.str($0).isEmpty ? "\($0)" : Json.str($0) } ?? "null"
            throw ProfileError("That typing profile is version \(shown); this keyboard reads version \(version)")
        }
        var lines: [String] = []
        let adaptiveOn = Plugins.isOn(prefs, Plugins.adaptive)

        let layout = (o["layout"] as? String) ?? "qwerty"
        if let keys = o["keys"] as? [String: Any] {
            if layout != "qwerty" { lines.append("Key aims skipped: they are for \(layout), and this keyboard reads qwerty") }
            else if !adaptiveOn { lines.append("Key aims skipped: add Adaptive keys to use them") }
            else {
                var n = 0
                for (k, value) in keys.sorted(by: { $0.key < $1.key }) {
                    guard let v = value as? [String: Any], let c = lowerLetter(k) else { continue }
                    adaptive.importAim(c, dx: CGFloat(Json.double(v["dx"]) ?? 0), dy: CGFloat(Json.double(v["dy"]) ?? 0), samples: Json.int(v["samples"]) ?? 50)
                    n += 1
                }
                lines.append(count(n, "key aim", "key aims") + " blended in")
            }
        }
        if let slips = o["slips"] as? [Any] {
            if !adaptiveOn { lines.append("Slips skipped: add Adaptive keys to use them") }
            else {
                var n = 0
                for item in slips {
                    guard let s = item as? [String: Any], let p = Json.str(s["pressed"]).first, let m = Json.str(s["meant"]).first,
                          let pa = p.asciiValue, let ma = m.asciiValue, pa >= 97, pa <= 122, ma >= 97, ma <= 122, p != m else { continue }
                    let times = Json.int(s["count"]) ?? 1
                    adaptive.importSlip(pressed: p, meant: m, count: times, x: Json.double(s["x"]).map { CGFloat($0) }, y: Json.double(s["y"]).map { CGFloat($0) })
                    n += times
                }
                lines.append(count(n, "slip", "slips") + " learned")
            }
        }
        if adaptiveOn { adaptive.save() }

        if let words = (o["words"] as? [String: Any])?["learned"] as? [Any] {
            var n = 0
            for item in words {
                let w = Json.str(item).trimmed
                if !w.isEmpty && w.utf16.count <= 40 && !w.contains(where: { $0.isWhitespace }) { prefs.learn(w); n += 1 }
            }
            lines.append(count(n, "word", "words") + " added to autocorrect")
        }

        var source: String?
        if let s = o["source"] as? [String: Any] {
            let joined = [Json.str(s["app"]), Json.str(s["model"])].filter { !$0.isBlank }.joined(separator: ", ")
            source = joined.isBlank ? nil : joined
        }
        if let style = o["style"] as? [String: Any] {
            if !Plugins.isOn(prefs, Plugins.mockingbird) { lines.append("Writing style skipped: add Mockingbird to use it") }
            else {
                let profile = Json.str(style["profile"])
                if !profile.isBlank {
                    mockingbird.importProfile(profile, source: "Imported" + (source.map { " from \($0)" } ?? ""))
                    lines.append("Style profile replaced")
                }
                if let a = style["samples"] as? [Any] {
                    var n = 0
                    for item in a {
                        guard let s = item as? [String: Any] else { continue }
                        let t = Json.str(s["text"])
                        if t.isBlank { continue }
                        mockingbird.recordImported(t, chat: Json.str(s["kind"]) == "chat")
                        n += 1
                    }
                    lines.append(count(n, "writing sample", "writing samples") + " added")
                }
            }
        }
        if lines.isEmpty { lines.append("Nothing in it this keyboard reads") }
        let report = Json.str(o["report"])
        return Result(lines: lines, report: report.isBlank ? nil : report.take(4000), source: source)
    }
}
