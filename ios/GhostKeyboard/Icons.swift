import UIKit

/// The small drawn icons the row's buttons and the keys use (PluginRow.kt's RowIcon).
enum RowIcon { case settings, hide, gif, back, forward, search, key, mic, delete, shift, enter, caretLeft, caretRight, caretUp, caretDown, globe }

/// One icon: `paths` stroked (`lines`) or filled, and `wash` filled under them at `washAlpha`. `box` is the size of
/// its drawing's square (Phosphor draws on 256, the row's own glyphs on 24).
struct PackIcon {
    let lines: Bool
    let paths: [CGPath]
    let wash: [CGPath]
    let washAlpha: CGFloat
    var box: CGFloat = 24
    var rotate: CGFloat = 0

    init(_ lines: Bool, _ paths: [String], _ wash: [String], _ washAlpha: CGFloat, box: CGFloat = 24, rotate: CGFloat = 0) {
        self.lines = lines
        self.paths = paths.map(SvgPath.parse)
        self.wash = wash.map(SvgPath.parse)
        self.washAlpha = washAlpha
        self.box = box
        self.rotate = rotate
    }
}

/// Phosphor's icons (regular, with its duotone wash; MIT), the same drawings as Android's RowIcons.kt.
enum RowIcons {
    private static func caret(_ rotate: CGFloat) -> PackIcon {
        PackIcon(false, ["M165.66,202.34a8,8,0,0,1-11.32,11.32l-80-80a8,8,0,0,1,0-11.32l80-80a8,8,0,0,1,11.32,11.32L91.31,128Z"], ["M160,48V208L80,128Z"], 0.2, box: 256, rotate: rotate)
    }

    private static var cache: [RowIcon: PackIcon] = [:]

    static func icon(_ icon: RowIcon) -> PackIcon {
        if let hit = cache[icon] { return hit }
        let made = make(icon)
        cache[icon] = made
        return made
    }

    private static func make(_ icon: RowIcon) -> PackIcon {
        switch icon {
        case .mic: return PackIcon(false, ["M128,176a48.05,48.05,0,0,0,48-48V64a48,48,0,0,0-96,0v64A48.05,48.05,0,0,0,128,176ZM96,64a32,32,0,0,1,64,0v64a32,32,0,0,1-64,0Zm40,143.6V240a8,8,0,0,1-16,0V207.6A80.11,80.11,0,0,1,48,128a8,8,0,0,1,16,0,64,64,0,0,0,128,0,8,8,0,0,1,16,0A80.11,80.11,0,0,1,136,207.6Z"], ["M168,64v64a40,40,0,0,1-40,40h0a40,40,0,0,1-40-40V64a40,40,0,0,1,40-40h0A40,40,0,0,1,168,64Z"], 0.2, box: 256)
        case .delete: return PackIcon(false, ["M216,40H68.53a16.08,16.08,0,0,0-13.72,7.77L9.14,123.88a8,8,0,0,0,0,8.24l45.67,76.11A16.08,16.08,0,0,0,68.53,216H216a16,16,0,0,0,16-16V56A16,16,0,0,0,216,40ZM61.67,204.12,68.53,200h0ZM216,200H68.53l-43.2-72,43.2-72H216ZM106.34,146.34,124.69,128l-18.35-18.34a8,8,0,0,1,11.32-11.32L136,116.69l18.34-18.35a8,8,0,0,1,11.32,11.32L147.31,128l18.35,18.34a8,8,0,0,1-11.32,11.32L136,139.31l-18.34,18.35a8,8,0,0,1-11.32-11.32Z"], ["M224,56V200a8,8,0,0,1-8,8H68.53a8,8,0,0,1-6.86-3.88L16,128,61.67,51.88A8,8,0,0,1,68.53,48H216A8,8,0,0,1,224,56Z"], 0.2, box: 256)
        case .shift: return PackIcon(false, ["M229.66,114.34l-96-96a8,8,0,0,0-11.32,0l-96,96A8,8,0,0,0,32,128H72v80a16,16,0,0,0,16,16h80a16,16,0,0,0,16-16V128h40a8,8,0,0,0,5.66-13.66ZM176,112a8,8,0,0,0-8,8v88H88V120a8,8,0,0,0-8-8H51.31L128,35.31,204.69,112Z"], ["M224,120H176v88a8,8,0,0,1-8,8H88a8,8,0,0,1-8-8V120H32l96-96Z"], 0.2, box: 256)
        case .caretLeft: return caret(0)
        case .caretUp: return caret(90)
        case .caretRight: return caret(180)
        case .caretDown: return caret(270)
        case .enter: return PackIcon(false, ["M200,32V176a8,8,0,0,1-8,8H67.31l34.35,34.34a8,8,0,0,1-11.32,11.32l-48-48a8,8,0,0,1,0-11.32l48-48a8,8,0,0,1,11.32,11.32L67.31,168H184V32a8,8,0,0,1,16,0Z"], ["M96,128v96L48,176Z"], 0.2, box: 256)
        // Phosphor's globe (regular, duotone wash): the next-keyboard key iOS asks every keyboard to offer.
        case .globe: return PackIcon(false, ["M128,24h0A104,104,0,1,0,232,128,104.12,104.12,0,0,0,128,24Zm88,104a87.61,87.61,0,0,1-3.33,24H174.16a157.44,157.44,0,0,0,0-48h38.51A87.61,87.61,0,0,1,216,128ZM102,168H154a115.11,115.11,0,0,1-26,45A115.27,115.27,0,0,1,102,168Zm-3.9-16a140.84,140.84,0,0,1,0-48h59.88a140.84,140.84,0,0,1,0,48ZM40,128a87.61,87.61,0,0,1,3.33-24H81.84a157.44,157.44,0,0,0,0,48H43.33A87.61,87.61,0,0,1,40,128ZM154,88H102a115.11,115.11,0,0,1,26-45A115.27,115.27,0,0,1,154,88Zm52.33,0H170.71a135.28,135.28,0,0,0-22.3-45.6A88.29,88.29,0,0,1,206.37,88ZM107.59,42.4A135.28,135.28,0,0,0,85.29,88H49.63A88.29,88.29,0,0,1,107.59,42.4ZM49.63,168H85.29a135.28,135.28,0,0,0,22.3,45.6A88.29,88.29,0,0,1,49.63,168Zm98.78,45.6a135.28,135.28,0,0,0,22.3-45.6h35.66A88.29,88.29,0,0,1,148.41,213.6Z"], ["M224,128a96,96,0,1,1-96-96A96,96,0,0,1,224,128Z"], 0.2, box: 256)
        case .settings: return PackIcon(false, ["M12 7.5a4.5 4.5 0 104.5 4.5A4.5 4.5 0 0012 7.5Zm0 7.5a3 3 0 113-3 3 3 0 01-3 3Zm8.25-2.8q.01-.2 0-.4l1.4-1.75a.75.75 0 00.14-.66 10.05 10.05 0 00-1.02-2.46.75.75 0 00-.57-.37l-2.22-.25q-.14-.15-.28-.28L17.44 3.8a.75.75 0 00-.37-.56 10.1 10.1 0 00-2.46-1.02.75.75 0 00-.66.14L12.2 3.75q-.2 0-.4 0l-1.75-1.4a.75.75 0 00-.66-.13A10.09 10.09 0 006.93 3.24a.75.75 0 00-.37.56L6.31 6.03q-.15.13-.28.28L3.8 6.56a.75.75 0 00-.56.37A10.1 10.1 0 002.22 9.39a.75.75 0 00.14.66L3.75 11.8q0 .2 0 .4l-1.4 1.75a.75.75 0 00-.13.66 10.05 10.05 0 001.02 2.46.75.75 0 00.56.37l2.22.25q.14.15.28.28l.26 2.23a.75.75 0 00.37.56 10.1 10.1 0 002.46 1.02.75.75 0 00.66-.14l1.75-1.39q.2.01.4 0l1.75 1.4a.75.75 0 00.66.14 10.05 10.05 0 002.46-1.02.75.75 0 00.37-.57l.25-2.22q.15-.14.28-.28l2.23-.26a.75.75 0 00.56-.37 10.1 10.1 0 001.02-2.46.75.75 0 00-.14-.66Zm-1.51-.61a6.93 6.93 0 010 .82.75.75 0 00.16.51l1.33 1.66a8.58 8.58 0 01-.58 1.41l-2.12.24a.75.75 0 00-.48.25 6.95 6.95 0 01-.57.57.75.75 0 00-.25.48l-.24 2.12a8.56 8.56 0 01-1.4.58l-1.66-1.33a.75.75 0 00-.47-.16h-.05a6.93 6.93 0 01-.81 0 .75.75 0 00-.52.16L9.42 20.23a8.58 8.58 0 01-1.41-.58l-.24-2.12a.75.75 0 00-.25-.48 6.95 6.95 0 01-.57-.57.75.75 0 00-.48-.25l-2.12-.24a8.56 8.56 0 01-.58-1.4l1.33-1.67a.75.75 0 00.16-.51 6.93 6.93 0 010-.81.75.75 0 00-.16-.52L3.77 9.42a8.58 8.58 0 01.58-1.41l2.12-.24a.75.75 0 00.48-.25 6.95 6.95 0 01.57-.57.75.75 0 00.25-.48l.24-2.12a8.56 8.56 0 011.4-.58l1.67 1.33a.75.75 0 00.51.16 6.93 6.93 0 01.81 0 .75.75 0 00.52-.16l1.66-1.33a8.58 8.58 0 011.41.58l.24 2.12a.75.75 0 00.25.48 6.95 6.95 0 01.57.57.75.75 0 00.48.25l2.12.24a8.56 8.56 0 01.58 1.4l-1.33 1.66a.75.75 0 00-.16.52Z"], ["M19.49 11.55l1.57-1.97a9.29 9.29 0 00-.94-2.28l-2.51-.28a7.59 7.59 0 00-.64-.64l-.28-2.5a9.32 9.32 0 00-2.27-.94l-1.97 1.57a7.65 7.65 0 00-.91 0L9.57 2.94A9.29 9.29 0 007.3 3.88l-.28 2.51a7.59 7.59 0 00-.64.64l-2.5.28a9.32 9.32 0 00-.94 2.27l1.57 1.97a7.65 7.65 0 000 .91l-1.57 1.97a9.29 9.29 0 00.95 2.27l2.5.28a7.59 7.59 0 00.64.64l.28 2.51a9.32 9.32 0 002.28.93l1.97-1.57a7.65 7.65 0 00.9 0l1.97 1.58a9.29 9.29 0 002.28-.95l.28-2.5a7.59 7.59 0 00.63-.64l2.51-.28a9.32 9.32 0 00.94-2.28l-1.58-1.97a7.65 7.65 0 000-.9ZM12 15.75A3.75 3.75 0 1115.75 12 3.75 3.75 0 0112 15.75Z"], 0.2)
        case .hide: return PackIcon(false, ["M20.03 9.53l-7.5 7.5a.75.75 0 01-1.06 0l-7.5-7.5A.74.74 0 015.03 8.47L12 15.44l6.97-6.97a.74.74 0 011.06 1.06Z"], ["M20.03 9.53l-7.5 7.5a.75.75 0 01-1.06 0l-7.5-7.5A.75.75 0 014.5 8.25h15a.75.75 0 01.53 1.28Z"], 0.33)
        case .back: return PackIcon(false, ["M21 12a.75.75 0 01-.75.75H5.56l5.47 5.47a.74.74 0 01-1.06 1.06l-6.75-6.75a.75.75 0 010-1.06l6.75-6.75a.74.74 0 011.06 1.06L5.56 11.25H20.25A.75.75 0 0121 12Z"], ["M21 12a.75.75 0 01-.75.75h-9v6a.75.75 0 01-1.28.53l-6.75-6.75a.75.75 0 010-1.06l6.75-6.75a.75.75 0 011.28.53v6h9A.75.75 0 0121 12Z"], 0.33)
        case .forward: return PackIcon(false, ["M20.78 12.53l-6.75 6.75a.74.74 0 01-1.06-1.06l5.47-5.47H3.75a.75.75 0 010-1.5H18.44L12.97 5.78a.74.74 0 011.06-1.06l6.75 6.75a.75.75 0 010 1.06Z"], ["M20.78 12.53l-6.75 6.75a.75.75 0 01-1.28-.53v-6h-9a.75.75 0 010-1.5h9v-6a.75.75 0 011.28-.53l6.75 6.75a.75.75 0 010 1.06Z"], 0.33)
        case .search: return PackIcon(false, ["M21.53 20.47l-4.69-4.69a8.26 8.26 0 10-1.06 1.06l4.69 4.69a.74.74 0 001.06-1.06ZM3.75 10.5a6.75 6.75 0 116.75 6.75A6.76 6.76 0 013.75 10.5Z"], ["M18 10.5A7.5 7.5 0 1110.5 3 7.5 7.5 0 0118 10.5Z"], 0.2)
        case .key, .gif: return PackIcon(false, [], [], 0.2)
        }
    }

    /// `icon` in a `size`-point square centred on (cx, cy), in `ink`: the wash under it (at its own share of the ink,
    /// or all of it with `filled`), then its shapes.
    static func draw(_ icon: RowIcon, in ctx: CGContext, cx: CGFloat, cy: CGFloat, size: CGFloat, ink: UIColor, filled: Bool = false) {
        let glyph = RowIcons.icon(icon)
        ctx.saveGState()
        ctx.translateBy(x: cx - size / 2, y: cy - size / 2)
        if glyph.rotate != 0 {
            ctx.translateBy(x: size / 2, y: size / 2)
            ctx.rotate(by: glyph.rotate * .pi / 180)
            ctx.translateBy(x: -size / 2, y: -size / 2)
        }
        let s = size / glyph.box
        ctx.scaleBy(x: s, y: s)
        var alpha: CGFloat = 1
        ink.getWhite(nil, alpha: &alpha)
        ctx.setFillColor(ink.withAlphaComponent(filled ? alpha : glyph.washAlpha * alpha).cgColor)
        for p in glyph.wash { ctx.addPath(p); ctx.fillPath() }
        if glyph.lines {
            ctx.setStrokeColor(ink.cgColor)
            ctx.setLineWidth(2 * glyph.box / 24)
            ctx.setLineCap(.round); ctx.setLineJoin(.round)
            for p in glyph.paths { ctx.addPath(p); ctx.strokePath() }
        } else {
            ctx.setFillColor(ink.cgColor)
            for p in glyph.paths { ctx.addPath(p); ctx.fillPath() }
        }
        ctx.restoreGState()
    }
}

/// An SVG path's `d` as a CGPath: every command (M L H V C S Q T A Z, absolute and relative), with SVG's compact
/// numbers ("1.5.5", "0-3") and an arc's flags run together ("a8,8,0,0,1-11.32").
enum SvgPath {
    static func parse(_ d: String) -> CGPath {
        let path = CGMutablePath()
        let s = Array(d.utf8)
        var i = 0
        var cmd: UInt8 = 0
        var cur = CGPoint.zero, start = CGPoint.zero
        var lastCtrl: CGPoint? = nil
        var lastCmd: UInt8 = 0

        func skip() { while i < s.count, s[i] == 32 || s[i] == 44 || s[i] == 10 || s[i] == 9 || s[i] == 13 { i += 1 } }
        func isCmd(_ c: UInt8) -> Bool { (c >= 65 && c <= 90 || c >= 97 && c <= 122) && c != 101 && c != 69 }
        func num() -> CGFloat {
            skip()
            let from = i
            if i < s.count, s[i] == 45 || s[i] == 43 { i += 1 }
            var dot = false
            while i < s.count {
                let c = s[i]
                if c >= 48 && c <= 57 { i += 1 }
                else if c == 46 && !dot { dot = true; i += 1 }
                else if (c == 101 || c == 69), i + 1 < s.count { i += 1; if s[i] == 45 || s[i] == 43 { i += 1 } }
                else { break }
            }
            return CGFloat(Double(String(decoding: s[from..<i], as: UTF8.self)) ?? 0)
        }
        func flag() -> Bool { skip(); guard i < s.count else { return false }; let v = s[i] == 49; i += 1; return v }
        func more() -> Bool { skip(); return i < s.count && !isCmd(s[i]) }

        while i < s.count {
            skip()
            guard i < s.count else { break }
            if isCmd(s[i]) { cmd = s[i]; i += 1 }
            let rel = cmd >= 97
            let base = rel ? cur : .zero
            switch cmd {
            case 77, 109: // M
                let p = CGPoint(x: base.x + num(), y: base.y + num())
                path.move(to: p); cur = p; start = p
                cmd = rel ? 108 : 76
            case 76, 108:
                let p = CGPoint(x: base.x + num(), y: base.y + num()); path.addLine(to: p); cur = p
            case 72, 104:
                let p = CGPoint(x: base.x + num(), y: cur.y); path.addLine(to: p); cur = p
            case 86, 118:
                let p = CGPoint(x: cur.x, y: base.y + num()); path.addLine(to: p); cur = p
            case 67, 99:
                let c1 = CGPoint(x: base.x + num(), y: base.y + num())
                let c2 = CGPoint(x: base.x + num(), y: base.y + num())
                let p = CGPoint(x: base.x + num(), y: base.y + num())
                path.addCurve(to: p, control1: c1, control2: c2); lastCtrl = c2; cur = p
            case 83, 115:
                let c1 = (lastCmd == 67 || lastCmd == 99 || lastCmd == 83 || lastCmd == 115) ? CGPoint(x: 2 * cur.x - (lastCtrl?.x ?? cur.x), y: 2 * cur.y - (lastCtrl?.y ?? cur.y)) : cur
                let c2 = CGPoint(x: base.x + num(), y: base.y + num())
                let p = CGPoint(x: base.x + num(), y: base.y + num())
                path.addCurve(to: p, control1: c1, control2: c2); lastCtrl = c2; cur = p
            case 81, 113:
                let c = CGPoint(x: base.x + num(), y: base.y + num())
                let p = CGPoint(x: base.x + num(), y: base.y + num())
                path.addQuadCurve(to: p, control: c); lastCtrl = c; cur = p
            case 84, 116:
                let c = (lastCmd == 81 || lastCmd == 113 || lastCmd == 84 || lastCmd == 116) ? CGPoint(x: 2 * cur.x - (lastCtrl?.x ?? cur.x), y: 2 * cur.y - (lastCtrl?.y ?? cur.y)) : cur
                let p = CGPoint(x: base.x + num(), y: base.y + num())
                path.addQuadCurve(to: p, control: c); lastCtrl = c; cur = p
            case 65, 97:
                let rx = num(), ry = num(), rot = num()
                let large = flag(), sweep = flag()
                let p = CGPoint(x: base.x + num(), y: base.y + num())
                arc(path, from: cur, to: p, rx: rx, ry: ry, rotation: rot, large: large, sweep: sweep); cur = p
            case 90, 122:
                path.closeSubpath(); cur = start
            default:
                i += 1
            }
            lastCmd = cmd
            if cmd == 90 || cmd == 122 { continue }
            if !more() { continue }
        }
        return path
    }

    /// SVG's endpoint arc as cubic curves (the standard conversion to a centre, then quarter-turn pieces).
    private static func arc(_ path: CGMutablePath, from p0: CGPoint, to p1: CGPoint, rx rxIn: CGFloat, ry ryIn: CGFloat, rotation: CGFloat, large: Bool, sweep: Bool) {
        var rx = abs(rxIn), ry = abs(ryIn)
        if rx == 0 || ry == 0 || p0 == p1 { path.addLine(to: p1); return }
        let phi = rotation * .pi / 180
        let cosP = cos(phi), sinP = sin(phi)
        let dx = (p0.x - p1.x) / 2, dy = (p0.y - p1.y) / 2
        let x1 = cosP * dx + sinP * dy, y1 = -sinP * dx + cosP * dy
        let lambda = x1 * x1 / (rx * rx) + y1 * y1 / (ry * ry)
        if lambda > 1 { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        let numer = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
        let denom = rx * rx * y1 * y1 + ry * ry * x1 * x1
        var coef = denom == 0 ? 0 : sqrt(max(0, numer / denom))
        if large == sweep { coef = -coef }
        let cxp = coef * rx * y1 / ry, cyp = -coef * ry * x1 / rx
        let cx = cosP * cxp - sinP * cyp + (p0.x + p1.x) / 2
        let cy = sinP * cxp + cosP * cyp + (p0.y + p1.y) / 2
        func angle(_ ux: CGFloat, _ uy: CGFloat, _ vx: CGFloat, _ vy: CGFloat) -> CGFloat {
            let a = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
            return a
        }
        let theta1 = angle(1, 0, (x1 - cxp) / rx, (y1 - cyp) / ry)
        var delta = angle((x1 - cxp) / rx, (y1 - cyp) / ry, (-x1 - cxp) / rx, (-y1 - cyp) / ry)
        if !sweep && delta > 0 { delta -= 2 * .pi } else if sweep && delta < 0 { delta += 2 * .pi }
        let pieces = max(1, Int(ceil(abs(delta) / (.pi / 2) - 0.0001)))
        let step = delta / CGFloat(pieces)
        let t = 4 / 3 * tan(step / 4)
        var a = theta1
        for _ in 0..<pieces {
            let b = a + step
            func pt(_ ang: CGFloat) -> CGPoint { CGPoint(x: cx + rx * cos(ang) * cosP - ry * sin(ang) * sinP, y: cy + rx * cos(ang) * sinP + ry * sin(ang) * cosP) }
            func d(_ ang: CGFloat) -> CGPoint { CGPoint(x: -rx * sin(ang) * cosP - ry * cos(ang) * sinP, y: -rx * sin(ang) * sinP + ry * cos(ang) * cosP) }
            let s = pt(a), e = pt(b), ds = d(a), de = d(b)
            path.addCurve(to: e, control1: CGPoint(x: s.x + t * ds.x, y: s.y + t * ds.y), control2: CGPoint(x: e.x - t * de.x, y: e.y - t * de.y))
            a = b
        }
    }
}

extension UIColor {
    /// 0xAARRGGBB, as the themes keep their colours.
    convenience init(argb: UInt32) {
        self.init(red: CGFloat((argb >> 16) & 0xFF) / 255, green: CGFloat((argb >> 8) & 0xFF) / 255, blue: CGFloat(argb & 0xFF) / 255, alpha: CGFloat((argb >> 24) & 0xFF) / 255)
    }
}
