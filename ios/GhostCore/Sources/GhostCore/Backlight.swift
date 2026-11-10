import CoreGraphics
import Foundation

/// The backlight (Settings › Backlight, on by default, off in password fields): the keys' legends lit from behind, as
/// shine-through keycaps on RGB mechanical keyboards are, reacting as you type. Only the legends change, never the
/// keys themselves.
///
/// - reactive: the key pressed glows and fades over about a second, the classic per-key light.
/// - ripple: a ring of light runs out from each key pressed and dims as it goes.
/// - heatmap: keys warm with use and cool over half a minute when left.
/// - rainbow: a slow hue wave across the board; a key pressed flares.
/// - starlight: keys twinkle at random; a key pressed flares.
/// - breathe: the board breathes softly; a key pressed flares.
///
/// It brightens the legends (blending in the theme's primary colour, a fixed colour or a rainbow) or dims them (fading
/// their opacity); the strength is how far. The view asks for each key's `light` while drawing, and keeps drawing
/// while `animating`. Times are milliseconds on any monotonic clock. Not thread-safe: use it from the drawing thread.
public final class Backlight {
    public let effect: String
    public let colour: String
    public let strength: CGFloat
    public let mode: String
    public let fade: CGFloat

    /// How long a lit key lingers and a ripple runs, by Settings › Backlight › Fade (`fadeOf`).
    private let fadeMs: Int64
    private let rippleS: CGFloat

    /// Dim: the effect darkens keys (legends too) rather than lighting them.
    public var dims: Bool { mode == "dim" }

    private var presses: [String: Int64] = [:]
    private var ripples: [(x: CGFloat, y: CGFloat, at: Int64)] = []
    private var heat: [String: CGFloat] = [:]
    private var heatAt: Int64 = 0

    public init(effect: String, colour: String, strength: CGFloat, mode: String = "brighten", fade: CGFloat = 1) {
        self.effect = effect
        self.colour = colour
        self.strength = strength
        self.mode = mode
        self.fade = fade
        fadeMs = Int64(CGFloat(Backlight.fadeMs) * fade)
        rippleS = Backlight.rippleS * fade
    }

    /// The backlight the settings ask for, or nil with it switched off.
    public convenience init?(prefs: KeyboardPrefs) {
        guard prefs.backlight else { return nil }
        self.init(effect: prefs.backlightEffect, colour: prefs.backlightColour, strength: Backlight.strengthOf(prefs.backlightStrength),
                  mode: prefs.backlightMode, fade: Backlight.fadeOf(prefs.backlightFade))
    }

    public func press(id: String, x: CGFloat, y: CGFloat, now: Int64) {
        presses[id] = now
        if effect == "ripple" {
            ripples.append((x, y, now))
            while ripples.count > 12 { ripples.removeFirst() }
        }
        if effect == "heatmap" {
            cool(now)
            heat[id] = (heat[id] ?? 0) + 1
        }
    }

    private func cool(_ now: Int64) {
        if heatAt != 0 {
            let k = CGFloat(exp(-Double(now - heatAt) / 30_000.0))
            for (id, v) in heat {
                let next = v * k
                if next < 0.02 { heat[id] = nil } else { heat[id] = next }
            }
        }
        heatAt = now
    }

    /// How lit the key `id` at (x, y) is, 0 to 1, on a board `width` wide.
    public func light(id: String, x: CGFloat, y: CGFloat, width: CGFloat, now: Int64) -> CGFloat {
        var flare: CGFloat = 0
        if let at = presses[id] {
            let since = now - at
            if since < fadeMs { let f = 1 - CGFloat(since) / CGFloat(fadeMs); flare = f * f }
        }
        switch effect {
        case "ripple":
            var sum = flare
            let ring = width * 0.07
            for r in ripples {
                let age = CGFloat(now - r.at) / 1000
                if age > rippleS { continue }
                let radius = age * width * 0.6
                let d = hypot(x - r.x, y - r.y)
                let off = (d - radius) / ring
                sum += exp(-off * off) * (1 - age / rippleS)
            }
            return min(sum, 1)
        case "heatmap":
            let top = heat.values.max() ?? 0
            let h = heat[id] ?? 0
            return max(top > 0 ? h / top * 0.85 : 0, flare * 0.6)
        case "rainbow":
            return max(0.38, flare)
        case "starlight":
            let seed = Double(id.javaHashCode & 0xffff) / 6553.6
            let twinkle = CGFloat(sin(Double(now) / 650.0 + seed * 2.7))
            return max(twinkle > 0.86 ? (twinkle - 0.86) / 0.14 * 0.7 : 0, flare)
        case "breathe":
            return max(0.12 + 0.14 * (0.5 + 0.5 * CGFloat(sin(Double(now) / 1300.0))), flare)
        default:
            return flare
        }
    }

    /// How warm the key `id` is among the keys, 0 to 1 (the heatmap's `heatLevel` for `colourAt`).
    public func heatLevel(id: String) -> CGFloat {
        let top = heat.values.max() ?? 0
        return top > 0 ? (heat[id] ?? 0) / top : 0
    }

    /// The light's colour (ARGB) at x: the accent, a fixed colour, or (rainbow) a hue across the board and time.
    public func colourAt(x: CGFloat, width: CGFloat, now: Int64, accent: UInt32, heatLevel: CGFloat = 0) -> UInt32 {
        if colour == "rainbow" || effect == "rainbow" {
            let hue = ((x / width) * 300 + CGFloat(now) / 25).truncatingRemainder(dividingBy: 360)
            return Backlight.hsvToArgb(h: hue, s: 0.75, v: 1)
        }
        if effect == "heatmap" && colour == "accent" {
            // Cool blue to hot orange as a key warms.
            return Backlight.hsvToArgb(h: 210 - 190 * heatLevel, s: 0.8, v: 1)
        }
        return Backlight.colours[colour] ?? accent
    }

    /// Whether anything is still moving, so the keyboard keeps drawing frames.
    public func animating(now: Int64) -> Bool {
        let lit = presses.values.contains { now - $0 < fadeMs }
        switch effect {
        case "rainbow", "starlight", "breathe": return true
        case "ripple": return ripples.contains { CGFloat(now - $0.at) < rippleS * 1000 } || lit
        case "heatmap": return !heat.isEmpty || lit
        default: return lit
        }
    }

    /// Effects that only change slowly can draw at a gentle rate (ms between frames), saving battery.
    public func frameDelay() -> Int64 {
        switch effect { case "heatmap": return 120; case "breathe": return 50; default: return 16 }
    }

    // Slowed 2026-11-09 (Matt): a lit key lingers about 1.2 s, a ripple runs 1.6 s.
    public static let fadeMs: Int64 = 1200
    public static let rippleS: CGFloat = 1.6

    /// The fixed colours, by id; the settings app lists the same.
    public static let colours: [String: UInt32] = [
        "ghost": 0xFF51FCFB, "ice": 0xFF7FD8FF, "phosphor": 0xFF39FF88,
        "warm": 0xFFFF8A3D, "violet": 0xFFB47CFF, "rose": 0xFFFF5C8A, "white": 0xFFFFFFFF,
    ]

    public static func strengthOf(_ id: String) -> CGFloat {
        switch id { case "low": return 0.35; case "high": return 0.9; default: return 0.6 }
    }

    /// Settings › Backlight › Fade: a lit key lingers 0.6 s (fast), 1.2 s (medium) or 2.4 s (slow), a ripple in step.
    public static func fadeOf(_ id: String) -> CGFloat {
        switch id { case "fast": return 0.5; case "slow": return 2; default: return 1 }
    }

    /// Opaque ARGB from hue (degrees), saturation and value (0 to 1), as Android's Color.HSVToColor.
    public static func hsvToArgb(h: CGFloat, s: CGFloat, v: CGFloat) -> UInt32 {
        var hue = h.truncatingRemainder(dividingBy: 360)
        if hue < 0 { hue += 360 }
        let s = min(1, max(0, s)), v = min(1, max(0, v))
        let sector = hue / 60
        let i = Int(sector) % 6
        let f = sector - CGFloat(Int(sector))
        let p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f))
        let rgb: (CGFloat, CGFloat, CGFloat)
        switch i {
        case 0: rgb = (v, t, p)
        case 1: rgb = (q, v, p)
        case 2: rgb = (p, v, t)
        case 3: rgb = (p, q, v)
        case 4: rgb = (t, p, v)
        default: rgb = (v, p, q)
        }
        func byte(_ x: CGFloat) -> UInt32 { UInt32(min(255, max(0, (x * 255).rounded()))) }
        return 0xFF000000 | (byte(rgb.0) << 16) | (byte(rgb.1) << 8) | byte(rgb.2)
    }
}
