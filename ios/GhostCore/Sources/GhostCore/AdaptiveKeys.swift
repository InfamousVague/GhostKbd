import CoreGraphics
import Foundation

/// The adaptive keys plugin: it learns the common mistakes you make in typing and adjusts the touch area of keys to
/// correct for them, with a heatmap of the mistake areas.
///
/// What it keeps, for each letter key on the letters page, is a nudge: how far the place the key listens has moved
/// from its middle, in fractions of the key (so it holds in portrait and landscape).
///
/// The nudge is a pair of training wheels, not a new layout: the correcting of the key placement is very slow and
/// gradual, and over time drifts back to the defaults to get the person on the right track.
/// - Slow. Only a slip moves a key: a touch you took back (backspace, then a neighbouring letter) or one autocorrect
///   swapped for a neighbouring letter moves the letter you meant `learn` of the way toward where the slip landed. It
///   takes about ten slips on a key before the difference can be felt. Each slip is kept, up to `maxMistakes`, for
///   the heatmap.
/// - Back to the defaults. Every clean touch on a key, one inside its own outline that was never corrected, lets it
///   `relax` of the way back to its middle; and whatever you type, every nudge halves each `halfLifeDays` days. So a
///   key helps most while you are slipping on it, and hands the letter back as your aim comes right. A touch that
///   only counted because of the nudge (outside the key's own outline) neither moves it nor lets it back.
///
/// Using it: a touch goes to whichever of the key under it and that key's neighbours has its nudged middle nearest. A
/// nudge is at most `maxShift` of a key, and a key needs `minSamples` touches before it has one, so a key is never
/// taken over from its middle, and a new keyboard behaves as before. Nothing is learned in password fields or while
/// incognito (the caller's rule).
///
/// The model is the same JSON Android keeps, under `"adaptive_model"` in the store. Not thread-safe: use it from one
/// thread (the keyboard's main thread).
public final class AdaptiveKeys {
    public static let minSamples = 15
    public static let maxShift: CGFloat = 0.3
    public static let maxMistakes = 400
    /// One slip moves its key this much of the way toward where the slip landed.
    public static let learn: CGFloat = 0.03
    /// One clean touch lets its key this much of the way back to its middle.
    public static let relax: CGFloat = 1.0 / 500
    /// Every nudge halves in this many days, typing or not.
    public static let halfLifeDays = 14
    /// A nudge smaller than this (of a key) is no nudge: the key is back at its default.
    public static let minShift: CGFloat = 0.02
    public static let storeKey = "adaptive_model"

    /// A key's nudge: how far from its middle it listens, in fractions of the key, and how many touches it has seen.
    public struct Aim: Equatable {
        public var dx: CGFloat
        public var dy: CGFloat
        public var n: Int
        public init(dx: CGFloat = 0, dy: CGFloat = 0, n: Int = 0) { self.dx = dx; self.dy = dy; self.n = n }
    }

    /// A mistake, for the heatmap: where the touch landed (fractions of the letters' area) and the letters.
    public struct Mistake: Equatable {
        public let x: CGFloat
        public let y: CGFloat
        public let pressed: Character
        public let meant: Character
        public init(x: CGFloat, y: CGFloat, pressed: Character, meant: Character) { self.x = x; self.y = y; self.pressed = pressed; self.meant = meant }
    }

    private let store: PrefsStore
    private var aimsByKey: [Character: Aim] = [:]
    public private(set) var mistakes: [Mistake] = []
    private var dirty = false
    private let clock: () -> Date

    /// When the nudges were last aged (`age`), in milliseconds since 1970; 0 until the first time.
    private var agedAt: Int64 = 0

    /// `clock` tells the time (tests move it).
    public init(store: PrefsStore, clock: @escaping () -> Date = { Date() }) {
        self.store = store
        self.clock = clock
        load()
        age()
    }

    /// Reads the model again from the store (the app or the keyboard may have changed it), dropping unsaved changes.
    public func reload() {
        aimsByKey.removeAll(); mistakes.removeAll(); dirty = false; agedAt = 0
        load()
        age()
    }

    private func nowMs() -> Int64 { Int64(clock().timeIntervalSince1970 * 1000) }

    /// The drift back to the defaults with time alone: every nudge halves each `halfLifeDays` days. Checked at most hourly.
    private func age() {
        let now = nowMs()
        let elapsed = now - agedAt
        if agedAt == 0 || elapsed < 0 { agedAt = now; dirty = true; return }
        if elapsed < 3_600_000 { return }
        let keep = CGFloat(pow(0.5, Double(elapsed) / (Double(AdaptiveKeys.halfLifeDays) * 86_400_000)))
        for k in aimsByKey.keys { aimsByKey[k]!.dx *= keep; aimsByKey[k]!.dy *= keep }
        agedAt = now
        dirty = true
    }

    private func load() {
        guard let text = store.object(forKey: AdaptiveKeys.storeKey) as? String, let o = Json.object(text) else { return }
        agedAt = Int64(Json.double(o["at"]) ?? 0)
        if let a = o["aims"] as? [String: Any] {
            for (k, v) in a {
                guard let c = k.first, let v = v as? [Any], v.count >= 3, let dx = Json.double(v[0]), let dy = Json.double(v[1]), let n = Json.int(v[2]) else { continue }
                aimsByKey[c] = Aim(dx: CGFloat(dx), dy: CGFloat(dy), n: n)
            }
        }
        if let m = o["mistakes"] as? [Any] {
            for item in m {
                guard let v = item as? [Any], v.count >= 4, let x = Json.double(v[0]), let y = Json.double(v[1]),
                      let p = (v[2] as? String)?.first, let q = (v[3] as? String)?.first else { continue }
                mistakes.append(Mistake(x: CGFloat(x), y: CGFloat(y), pressed: p, meant: q))
            }
        }
    }

    /// Writes what changed; call it when a word ends, so at most once a word.
    public func save() {
        age()
        if !dirty { return }
        dirty = false
        var a: [String: Any] = [:]
        for (k, v) in aimsByKey { a[String(k)] = [Double(v.dx), Double(v.dy), v.n] as [Any] }
        let m: [Any] = mistakes.map { [Double($0.x), Double($0.y), String($0.pressed), String($0.meant)] as [Any] }
        store.set(Json.string(["aims": a, "mistakes": m, "at": agedAt]), forKey: AdaptiveKeys.storeKey)
    }

    public func reset() {
        aimsByKey.removeAll(); mistakes.removeAll(); dirty = false; agedAt = nowMs()
        store.removeObject(forKey: AdaptiveKeys.storeKey)
    }

    private static func lower(_ c: Character) -> Character { c.lowercased().first ?? c }

    private static func clamp(_ v: CGFloat, _ lo: CGFloat, _ hi: CGFloat) -> CGFloat { min(hi, max(lo, v)) }

    /// Where `c` listens: its nudge, at most `maxShift`; none until it has `minSamples` touches, and none once it has
    /// drifted back under `minShift`.
    public func offset(_ c: Character) -> (dx: CGFloat, dy: CGFloat) {
        guard let a = aimsByKey[AdaptiveKeys.lower(c)], a.n >= AdaptiveKeys.minSamples else { return (0, 0) }
        if abs(a.dx) < AdaptiveKeys.minShift && abs(a.dy) < AdaptiveKeys.minShift { return (0, 0) }
        let s = AdaptiveKeys.maxShift
        return (AdaptiveKeys.clamp(a.dx, -s, s), AdaptiveKeys.clamp(a.dy, -s, s))
    }

    /// The keys being helped now: those with a nudge.
    public func learnedKeys() -> Int { aimsByKey.keys.filter { offset($0) != (0, 0) }.count }

    private static func usable(_ c: Character, _ dx: CGFloat, _ dy: CGFloat) -> Bool {
        c.isLetter && !dx.isNaN && !dy.isNaN && abs(dx) <= 1.5 && abs(dy) <= 1.5
    }

    /// A touch meant for `c` that stood, `dx` and `dy` from its middle in fractions of the key. Inside the key's own
    /// outline it was a clean touch: the key goes `relax` of the way back to its default. Outside it, the nudge is
    /// what made it count, which says nothing either way.
    public func hit(_ c: Character, dx: CGFloat, dy: CGFloat) {
        if !AdaptiveKeys.usable(c, dx, dy) { return }
        let k = AdaptiveKeys.lower(c)
        var a = aimsByKey[k] ?? Aim()
        if a.n < 100_000 { a.n += 1 }
        if abs(dx) <= 0.5 && abs(dy) <= 0.5 {
            a.dx -= a.dx * AdaptiveKeys.relax
            a.dy -= a.dy * AdaptiveKeys.relax
        }
        aimsByKey[k] = a
        dirty = true
    }

    /// A slip meant for `c` that landed `dx`, `dy` from its middle, `times` over: each moves the key `learn` of the way there.
    private func learn(_ c: Character, dx: CGFloat, dy: CGFloat, times: Int = 1) {
        if !AdaptiveKeys.usable(c, dx, dy) { return }
        let k = AdaptiveKeys.lower(c)
        var a = aimsByKey[k] ?? Aim()
        for _ in 0..<max(0, times) {
            if a.n < 100_000 { a.n += 1 }
            a.dx += (dx - a.dx) * AdaptiveKeys.learn
            a.dy += (dy - a.dy) * AdaptiveKeys.learn
        }
        aimsByKey[k] = a
        dirty = true
    }

    // MARK: typing profiles (TypingProfile)

    /// Every learned aim, for an export.
    public func aims() -> [Character: Aim] { aimsByKey }

    /// A nudge from a typing profile, blended with what is here, each weighted by its touches. It drifts back like any other.
    public func importAim(_ c: Character, dx: CGFloat, dy: CGFloat, samples: Int) {
        let k = AdaptiveKeys.lower(c)
        if !k.isLetter || samples <= 0 { return }
        var a = aimsByKey[k] ?? Aim()
        let total = min(a.n + samples, 100_000)
        let both = CGFloat(a.n + samples)
        a.dx = (a.dx * CGFloat(a.n) + AdaptiveKeys.clamp(dx, -1.5, 1.5) * CGFloat(samples)) / both
        a.dy = (a.dy * CGFloat(a.n) + AdaptiveKeys.clamp(dy, -1.5, 1.5) * CGFloat(samples)) / both
        a.n = total
        aimsByKey[k] = a
        dirty = true
    }

    /// Where the QWERTY letters sit, in key widths across and rows down, for slips that come without a point.
    private static let qwerty: [Character: (x: CGFloat, y: CGFloat)] = {
        var out: [Character: (x: CGFloat, y: CGFloat)] = [:]
        for (i, c) in "qwertyuiop".enumerated() { out[c] = (CGFloat(i), 0) }
        for (i, c) in "asdfghjkl".enumerated() { out[c] = (CGFloat(i) + 0.5, 1) }
        for (i, c) in "zxcvbnm".enumerated() { out[c] = (CGFloat(i) + 1.5, 2) }
        return out
    }()

    /// A slip from a typing profile, `count` times: it teaches `meant`'s aim, at the point given (`x`, `y`, fractions
    /// of the letters' area, placed on the heatmap too) or, without one, part way toward `pressed` on a QWERTY board.
    public func importSlip(pressed: Character, meant: Character, count: Int, x: CGFloat?, y: CGFloat?) {
        let p = AdaptiveKeys.lower(pressed), m = AdaptiveKeys.lower(meant)
        guard let pp = AdaptiveKeys.qwerty[p], let mp = AdaptiveKeys.qwerty[m] else { return }
        let n = min(500, max(1, count))
        // Most of the way to the shared edge: where a slip between the two lands.
        learn(m, dx: (pp.x - mp.x) * 0.6, dy: (pp.y - mp.y) * 0.6, times: min(n, 60))
        if let x = x, let y = y {
            for _ in 0..<min(n, 20) { mistakes.append(Mistake(x: AdaptiveKeys.clamp(x, 0, 1), y: AdaptiveKeys.clamp(y, 0, 1), pressed: p, meant: m)) }
        }
        trimMistakes()
        dirty = true
    }

    /// A touch on `pressed` that was meant for `meant`: it teaches `meant`'s aim (`dx`, `dy` from `meant`'s middle, in
    /// fractions of the key), and goes on the heatmap at (`x`, `y`), fractions of the letters' area.
    public func mistake(pressed: Character, meant: Character, dx: CGFloat, dy: CGFloat, x: CGFloat, y: CGFloat) {
        learn(meant, dx: dx, dy: dy)
        mistakes.append(Mistake(x: x, y: y, pressed: AdaptiveKeys.lower(pressed), meant: AdaptiveKeys.lower(meant)))
        trimMistakes()
        dirty = true
    }

    private func trimMistakes() {
        if mistakes.count > AdaptiveKeys.maxMistakes { mistakes.removeFirst(mistakes.count - AdaptiveKeys.maxMistakes) }
    }
}
