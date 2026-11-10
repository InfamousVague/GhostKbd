import CoreGraphics
import Foundation

/// The adaptive keys plugin: it learns the common mistakes you make in typing and adjusts the touch area of keys to
/// correct for them, with a heatmap of the mistake areas.
///
/// What it learns, for each letter key on the letters page, is where you actually aim at it: the mean of your touches
/// meant for it, as an offset from its middle in fractions of the key (so it holds in portrait and landscape).
/// Touches that were never corrected count once for their key; a touch you took back (backspace, then a neighbouring
/// letter) or one autocorrect swapped for a neighbouring letter counts `mistakeWeight` times for the letter you meant,
/// and is kept, up to `maxMistakes`, for the heatmap.
///
/// Using it: a touch goes to whichever of the key under it and that key's neighbours has its learned aim nearest. An
/// aim moves at most `maxShift` of a key from the middle, and a key needs `minSamples` touches before its aim counts,
/// so a key is never taken over from its middle, and a new keyboard behaves as before. Nothing is learned in password
/// fields or while incognito (the caller's rule).
///
/// The model is the same JSON Android keeps, under `"adaptive_model"` in the store. Not thread-safe: use it from one
/// thread (the keyboard's main thread).
public final class AdaptiveKeys {
    public static let minSamples = 15
    public static let maxShift: CGFloat = 0.35
    public static let mistakeWeight = 3
    public static let maxMistakes = 400
    public static let storeKey = "adaptive_model"

    /// A key's learned aim: its mean offset from the middle, in fractions of the key, and how many touches made it.
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

    public init(store: PrefsStore) {
        self.store = store
        load()
    }

    /// Reads the model again from the store (the app or the keyboard may have changed it), dropping unsaved changes.
    public func reload() {
        aimsByKey.removeAll(); mistakes.removeAll(); dirty = false
        load()
    }

    private func load() {
        guard let text = store.object(forKey: AdaptiveKeys.storeKey) as? String, let o = Json.object(text) else { return }
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
        if !dirty { return }
        dirty = false
        var a: [String: Any] = [:]
        for (k, v) in aimsByKey { a[String(k)] = [Double(v.dx), Double(v.dy), v.n] as [Any] }
        let m: [Any] = mistakes.map { [Double($0.x), Double($0.y), String($0.pressed), String($0.meant)] as [Any] }
        store.set(Json.string(["aims": a, "mistakes": m]), forKey: AdaptiveKeys.storeKey)
    }

    public func reset() {
        aimsByKey.removeAll(); mistakes.removeAll(); dirty = false
        store.removeObject(forKey: AdaptiveKeys.storeKey)
    }

    private static func lower(_ c: Character) -> Character { c.lowercased().first ?? c }

    private static func clamp(_ v: CGFloat, _ lo: CGFloat, _ hi: CGFloat) -> CGFloat { min(hi, max(lo, v)) }

    /// Where to aim for `c`: its learned offset, or none until it has `minSamples` touches; at most `maxShift`.
    public func offset(_ c: Character) -> (dx: CGFloat, dy: CGFloat) {
        guard let a = aimsByKey[AdaptiveKeys.lower(c)], a.n >= AdaptiveKeys.minSamples else { return (0, 0) }
        let s = AdaptiveKeys.maxShift
        return (AdaptiveKeys.clamp(a.dx, -s, s), AdaptiveKeys.clamp(a.dy, -s, s))
    }

    public func learnedKeys() -> Int { aimsByKey.values.filter { $0.n >= AdaptiveKeys.minSamples }.count }

    /// A touch meant for `c`, `dx` and `dy` from its middle in fractions of the key: a running mean that settles
    /// after a few hundred touches and still follows a change of grip.
    public func hit(_ c: Character, dx: CGFloat, dy: CGFloat, weight: Int = 1) {
        if !c.isLetter || dx.isNaN || dy.isNaN || abs(dx) > 1.5 || abs(dy) > 1.5 { return }
        let k = AdaptiveKeys.lower(c)
        var a = aimsByKey[k] ?? Aim()
        for _ in 0..<max(0, weight) {
            a.n += 1
            let rate = 1 / CGFloat(min(a.n, 200))
            a.dx += (dx - a.dx) * rate
            a.dy += (dy - a.dy) * rate
        }
        aimsByKey[k] = a
        dirty = true
    }

    // MARK: typing profiles (TypingProfile)

    /// Every learned aim, for an export.
    public func aims() -> [Character: Aim] { aimsByKey }

    /// An aim from a typing profile, blended with what is here, each weighted by its touches.
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
        hit(m, dx: (pp.x - mp.x) * 0.6, dy: (pp.y - mp.y) * 0.6, weight: n * AdaptiveKeys.mistakeWeight)
        if let x = x, let y = y {
            for _ in 0..<min(n, 20) { mistakes.append(Mistake(x: AdaptiveKeys.clamp(x, 0, 1), y: AdaptiveKeys.clamp(y, 0, 1), pressed: p, meant: m)) }
        }
        trimMistakes()
        dirty = true
    }

    /// A touch on `pressed` that was meant for `meant`: it teaches `meant`'s aim (`dx`, `dy` from `meant`'s middle, in
    /// fractions of the key), and goes on the heatmap at (`x`, `y`), fractions of the letters' area.
    public func mistake(pressed: Character, meant: Character, dx: CGFloat, dy: CGFloat, x: CGFloat, y: CGFloat) {
        hit(meant, dx: dx, dy: dy, weight: AdaptiveKeys.mistakeWeight)
        mistakes.append(Mistake(x: x, y: y, pressed: AdaptiveKeys.lower(pressed), meant: AdaptiveKeys.lower(meant)))
        trimMistakes()
        dirty = true
    }

    private func trimMistakes() {
        if mistakes.count > AdaptiveKeys.maxMistakes { mistakes.removeFirst(mistakes.count - AdaptiveKeys.maxMistakes) }
    }
}
