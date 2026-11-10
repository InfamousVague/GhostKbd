import UIKit

/// What the keyboard asks of whoever hosts it (the input view controller). KeyboardView.kt's KeyboardListener.
protocol KeyboardListener: AnyObject {
    /// `text` typed, the cursor left `caretBack` characters before its end (a pair's inside).
    func onText(_ text: String, caretBack: Int)
    func onDelete()
    func onSpace()
    func onEnter()
    func onCursor(_ steps: Int)
    func onShiftChanged()
    /// A key's sound and, unless `haptic` is false (a held backspace's repeats), its haptic.
    func onKeyFeedback(_ type: KeyType, haptic: Bool)
    func onOpenSettings()
    /// Swipe typing: the finger's path over the letters, each letter's key centre, and a key's width.
    func onSwipe(_ path: [CGPoint], keys: [Character: CGPoint], keyWidth: CGFloat)
    /// Cursor control: a slide left from backspace takes the word before the cursor.
    func onDeleteWord()
    /// Cursor control: a slide up or down from the space bar moves the cursor by lines (negative is up).
    func onCursorLine(_ lines: Int)
    /// The empty side of a docked or one-handed keyboard was tapped: the keys should move to `side`.
    func onDock(_ side: String, wide: Bool)
    /// The globe key's touch, every phase of it: iOS reads a tap as the next keyboard and a hold as its list.
    func onGlobe(from view: UIView, with event: UIEvent)
}

enum ShiftState { case off, on, locked }

enum EnterIcon { case `return`, search, go, send, next, done }

/// The whole keyboard as one drawn view, Gboard's layout and look, ported from Android's KeyboardView.kt: a strip for
/// the row above the keys, then the keys. The key preview and the long-press panel are drawn by an overlay on top of
/// everything (the row included), since an iOS keyboard cannot draw above its own view.
final class KeyboardView: UIView {
    weak var listener: KeyboardListener?

    // --- settings ---------------------------------------------------------------------------------------------------

    var theme: KeyboardTheme = .dark { didSet { setNeedsDisplay() } }
    var showPreview = true
    var showHints = true { didSet { setNeedsDisplay() } }
    var spaceCursor = true
    /// Cursor control: sliding left from backspace deletes whole words.
    var deleteSwipe = true
    var longPressMs = 300

    /// Gboard's look: only ?123, space and enter have a shape; letters float. On, every key has its own face.
    var keyBorders = false { didSet { setNeedsDisplay() } }

    /// iOS asks for a next-keyboard key where the system shows none of its own (phones with a home button).
    var globeKey = false { didSet { if oldValue != globeKey { rebuild(); setNeedsDisplay() } } }

    /// The row above the keys is shown: its view (PluginRowView) sits in the strip above the keys.
    var pluginRow = false { didSet { if oldValue != pluginRow { invalidateIntrinsicContentSize(); setNeedsLayout(); setNeedsDisplay() } } }

    /// Wide screens (an iPad): "split" in two, "full" width, or a phone-sized board docked "left", "center", "right".
    var wideLayout = "split" { didSet { if oldValue != wideLayout { layoutKeys(); setNeedsDisplay() } } }

    /// One-handed typing on a phone: "off", or the keys narrowed toward the "left" or "right" thumb.
    var oneHanded = "off" { didSet { if oldValue != oneHanded { layoutKeys(); setNeedsDisplay() } } }

    private var areaLeft: CGFloat = 0
    private var areaRight: CGFloat = 0
    private var isWide: Bool { bounds.width >= Self.splitMin }

    /// A docked or one-handed keyboard's empty side: its middle and its name, where the keys go when it is tapped.
    private func dockFlip() -> (CGFloat, String)? {
        if areaRight - areaLeft >= bounds.width - 1 { return nil }
        if areaLeft <= 0.5 { return ((areaRight + bounds.width) / 2, "right") }
        if areaRight >= bounds.width - 0.5 { return (areaLeft / 2, "left") }
        return nil
    }

    /// The adaptive keys plugin's learned aims, or nil without it; and whether its heatmap is drawn over the keys.
    var adaptive: AdaptiveKeys?
    var adaptiveMap = false { didSet { if oldValue != adaptiveMap { setNeedsDisplay() } } }

    /// The letter key a finger last typed, with where it aimed (after touch correction): what adaptive keys learns from.
    struct Typed { let c: Character; let rect: CGRect; let x: CGFloat; let y: CGFloat }
    private var lastTyped: Typed?

    /// The letter just typed by a finger, once: nil for anything typed another way (a popup, the row, a swipe).
    func takeTyped() -> Typed? { defer { lastTyped = nil }; return lastTyped }

    /// `c`'s key on the letters page now: to measure a mistake against the key meant.
    func letterRect(_ c: Character) -> CGRect? {
        guard page == .letters else { return nil }
        let want = String(c).lowercased()
        return keyRects.first { $0.key.type == .char && $0.key.text.lowercased() == want }?.rect
    }

    /// A point as a fraction of the keys' area, for the heatmap, which must hold on any screen.
    func keysFraction(_ x: CGFloat, _ y: CGFloat) -> (CGFloat, CGFloat) { (x / max(bounds.width, 1), (y - keysTop) / max(keysHeight, 1)) }

    /// The backlight, or nil without it.
    var backlight: Backlight? { didSet { setNeedsDisplay() } }

    private func lightId(_ key: Key) -> String { "\(key.type)|\(key.text)|\(key.label)" }

    /// Lights a swiped word's letters when the backlight is off: a quick reactive flare.
    private let wordLight = Backlight(effect: "reactive", colour: "accent", strength: 0.85)
    private var lightNow: Backlight { backlight ?? wordLight }

    private func nowMs() -> Int64 { Int64(CACurrentMediaTime() * 1000) }

    /// Swipe typing: the word's letters light one after another, 55 ms apart, as if typed along the path.
    func lightWord(_ word: String) {
        let letters = Array(Autocorrect.fold(word.lowercased()))
        for (i, c) in letters.enumerated() {
            guard let hit = keyRects.first(where: { $0.key.type == .char && $0.key.text == String(c) }) else { continue }
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(i * 55)) { [weak self] in
                guard let self else { return }
                self.lightNow.press(id: self.lightId(hit.key), x: hit.rect.midX, y: hit.rect.midY, now: self.nowMs())
                self.startAnimating()
            }
        }
    }

    /// A key the AI is "pressing" as it types (Ghostwriter), drawn pressed with its preview, until it lets go.
    private var ghostKey: (key: Key, rect: CGRect)?
    private var ghostRelease: DispatchWorkItem?

    /// Ghostwriter typing: the key for `c` goes down as a finger's would and comes up `holdMs` later.
    func ghostPress(_ c: Character, holdMs: Int = 70) {
        let match: (key: Key, rect: CGRect)?
        if c == " " { match = keyRects.first { $0.key.type == .space } }
        else if c == "\n" { match = keyRects.first { $0.key.type == .enter } }
        else {
            let folded = Autocorrect.fold(String(c).lowercased()).first.map(String.init) ?? String(c)
            match = keyRects.first { $0.key.type == .char && ($0.key.text == String(c) || $0.key.text.lowercased() == folded) }
        }
        ghostRelease?.cancel()
        ghostKey = match
        if let m = match { lightNow.press(id: lightId(m.key), x: m.rect.midX, y: m.rect.midY, now: nowMs()) }
        redraw()
        let work = DispatchWorkItem { [weak self] in self?.ghostKey = nil; self?.redraw() }
        ghostRelease = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(holdMs), execute: work)
    }

    /// The swipe typing plugin is added: a finger sliding across the letters spells a word.
    var swipeTyping = false

    private var numberRow = false
    private var rowHeight: CGFloat = 54
    private var keyHeightSetting = "normal"

    func configure(numberRow: Bool, keyHeight: String) {
        let changed = numberRow != self.numberRow || keyHeight != keyHeightSetting
        self.numberRow = numberRow
        keyHeightSetting = keyHeight
        updateRowHeight()
        if changed { rebuild(); invalidateIntrinsicContentSize(); setNeedsDisplay() }
    }

    /// An iPhone's keyboard rows are a little shorter than Gboard's 60dp on Android; landscape has far less room.
    private func updateRowHeight() {
        let landscape = (window?.windowScene?.interfaceOrientation.isLandscape ?? false) && traitCollection.userInterfaceIdiom == .phone
        let h: CGFloat
        switch keyHeightSetting { case "short": h = 48; case "tall": h = 60; default: h = 54 }
        let next = landscape ? min(h, 40) : h
        if next != rowHeight { rowHeight = next; layoutKeys(); invalidateIntrinsicContentSize() }
    }

    // --- state ------------------------------------------------------------------------------------------------------

    private(set) var page: Page = .letters
    private(set) var shift: ShiftState = .off

    /// Shift that auto-capitalisation turned on, rather than the person: auto-caps may turn it off again.
    private var autoShifted = false
    private var lastShiftTap: TimeInterval = 0

    var enterIcon: EnterIcon = .return { didSet { setNeedsDisplay() } }

    private var rows: [KeyRow] = Layouts.letters(numberRow: false)
    private var keyRects: [(key: Key, rect: CGRect)] = []

    func showPage(_ next: Page) {
        page = next
        if next != .letters { shift = .off; autoShifted = false }
        rebuild()
        redraw()
    }

    /// Auto-capitalisation from the field: turns shift on, or back off if it alone turned it on.
    func setAutoShift(_ on: Bool) {
        if page != .letters || shift == .locked { return }
        if on && shift == .off { shift = .on; autoShifted = true; setNeedsDisplay() }
        else if !on && shift == .on && autoShifted { shift = .off; autoShifted = false; setNeedsDisplay() }
    }

    /// Back to the letters with shift off: each time a field is started.
    func reset() {
        shift = .off; autoShifted = false; page = .letters
        cancelTouch()
        rebuild()
        redraw()
    }

    private func rebuild() {
        let base: [KeyRow]
        switch page {
        case .letters: base = Layouts.letters(numberRow: numberRow)
        case .symbols: base = Layouts.symbols()
        case .more: base = Layouts.more()
        case .dev: base = Layouts.dev()
        }
        rows = globeKey ? Layouts.withGlobe(base) : base
        layoutKeys()
    }

    // --- metrics ----------------------------------------------------------------------------------------------------

    /// The strip above the keys: the row's, or a sliver that leaves the top row room to breathe.
    private var toolbarHeight: CGFloat { pluginRow ? 44 : 10 }
    private var keysTop: CGFloat { toolbarHeight }
    var rowBottom: CGFloat { keysTop }
    private let sidePad: CGFloat = 1.5
    private let bottomPad: CGFloat = 2
    private let keyPadX: CGFloat = 3
    private var keyPadY: CGFloat { rowHeight >= 50 ? 5.5 : 4 }
    private let keyRadius: CGFloat = 6

    /// The letters page sets the height; the symbol pages spread their rows over the same space, as Gboard does.
    private var rowCount: Int { numberRow ? 5 : 4 }
    private var keysHeight: CGFloat { CGFloat(rowCount) * rowHeight }
    var wantedHeight: CGFloat { ceil(keysTop + keysHeight + bottomPad) }

    override var intrinsicContentSize: CGSize { CGSize(width: UIView.noIntrinsicMetric, height: wantedHeight) }

    private let overlay = OverlayView()
    private var lastWidth: CGFloat = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        isOpaque = true
        isMultipleTouchEnabled = true
        contentMode = .redraw
        overlay.isUserInteractionEnabled = false
        overlay.backgroundColor = .clear
        overlay.isOpaque = false
        overlay.owner = self
        overlay.layer.zPosition = 10
        addSubview(overlay)
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        overlay.frame = bounds
        bringSubviewToFront(overlay)
        updateRowHeight()
        if bounds.width != lastWidth { lastWidth = bounds.width; layoutKeys() }
    }

    private func redraw() { setNeedsDisplay(); overlay.setNeedsDisplay() }

    private func layoutKeys() {
        keyRects.removeAll()
        let width = bounds.width
        guard width > 0 else { return }
        let units = CGFloat(rows.map { $0.units }.max() ?? 10)
        // Split on a wide screen, as Gboard does: each half keeps a phone's key size and the middle is left open for
        // the thumbs. A key across the middle (the space bar) becomes two, one in each half.
        var gap: CGFloat = 0
        var areaW = width
        var left: CGFloat = 0
        if isWide {
            switch wideLayout {
            case "split": gap = width * Self.splitGap
            case "left", "center", "right":
                areaW = min(width, Self.compactWidth)
                left = wideLayout == "left" ? 0 : wideLayout == "right" ? width - areaW : (width - areaW) / 2
            default: break
            }
        } else if oneHanded == "left" || oneHanded == "right" {
            areaW = width * Self.oneHandedShare
            left = oneHanded == "right" ? width - areaW : 0
        }
        areaLeft = left
        areaRight = left + areaW
        let unit = (areaW - sidePad * 2 - gap) / units
        let mid = left + sidePad + units * unit / 2
        let rowH = keysHeight / CGFloat(rows.count)
        var y = keysTop
        for row in rows {
            var x = left + sidePad + CGFloat(row.inset) * unit + (units - CGFloat(row.units)) * unit / 2
            for key in row.keys {
                let w = CGFloat(key.width) * unit
                let l = x, r = x + w
                if gap == 0 { keyRects.append((key, CGRect(x: l, y: y, width: w, height: rowH))) }
                else if key.width >= 2 && l < mid && r > mid {
                    keyRects.append((key, CGRect(x: l, y: y, width: mid - l, height: rowH)))
                    keyRects.append((key, CGRect(x: mid + gap, y: y, width: r - mid, height: rowH)))
                } else if (l + r) / 2 <= mid + 0.01 { keyRects.append((key, CGRect(x: l, y: y, width: w, height: rowH))) }
                else { keyRects.append((key, CGRect(x: l + gap, y: y, width: w, height: rowH))) }
                x += w
            }
            y += rowH
        }
        redraw()
    }

    /// The visible key face inside a key's touch area.
    private func face(_ r: CGRect) -> CGRect { r.insetBy(dx: keyPadX, dy: keyPadY) }

    // --- drawing ----------------------------------------------------------------------------------------------------

    private var displayLink: CADisplayLink?
    private var lastFrame: CFTimeInterval = 0

    /// The backlight keeps drawing while it moves; the link stops itself once everything has settled.
    private func startAnimating() {
        setNeedsDisplay()
        guard displayLink == nil, window != nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(tick))
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    @objc private func tick(_ link: CADisplayLink) {
        let now = nowMs()
        let light = lightNow
        let fading = fadingSwipe != nil
        guard light.animating(now: now) || fading else { link.invalidate(); displayLink = nil; setNeedsDisplay(); return }
        // Effects that only change slowly draw at a gentle rate, saving battery.
        let delay = fading ? 0 : Double(light.frameDelay()) / 1000
        if link.timestamp - lastFrame >= delay - 0.004 { lastFrame = link.timestamp; setNeedsDisplay(); if fading { overlay.setNeedsDisplay() } }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        if window == nil { displayLink?.invalidate(); displayLink = nil; cancelTouch() }
        else if lightNow.animating(now: nowMs()) { startAnimating() }
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        ctx.setFillColor(UIColor(argb: theme.background).cgColor)
        ctx.fill(bounds)
        let now = nowMs()
        let light = lightNow
        let lightActive = !(light === wordLight && !wordLight.animating(now: now))
        for (key, r) in keyRects {
            let pressed = (touch.map { $0.key == key && $0.rect == r && $0.panel == nil && $0.swipe == nil } ?? false) || (ghostKey.map { $0.key == key && $0.rect == r } ?? false)
            drawKey(ctx, key, r, pressed: pressed, legend: lightActive ? legendLight(key, face(r), light, now) : nil)
        }
        // A docked or one-handed keyboard: a chevron on its empty side, which moves the keys there when tapped.
        if let (cx, side) = dockFlip() {
            let cy = keysTop + keysHeight / 2
            let d: CGFloat = side == "right" ? 1 : -1
            ctx.setStrokeColor(UIColor(argb: theme.hint).cgColor)
            ctx.setLineWidth(2.2); ctx.setLineCap(.round); ctx.setLineJoin(.round)
            ctx.move(to: CGPoint(x: cx - d * 5, y: cy - 10))
            ctx.addLine(to: CGPoint(x: cx + d * 5, y: cy))
            ctx.addLine(to: CGPoint(x: cx - d * 5, y: cy + 10))
            ctx.strokePath()
        }
        if adaptiveMap, page == .letters, let model = adaptive { drawAdaptive(ctx, model) }
        if light.animating(now: now) { startAnimating() }
    }

    /// The backlight on a key's legend, as a real board's shine-through legends: brightening blends the light's colour
    /// into the label and icon, dimming fades them. Returns how to change the legend's colour, or nil.
    private func legendLight(_ key: Key, _ f: CGRect, _ light: Backlight, _ now: Int64) -> ((UIColor) -> UIColor)? {
        let i = light.light(id: lightId(key), x: f.midX, y: f.midY, width: bounds.width, now: now) * light.strength
        if i <= 0.01 { return nil }
        let t = CGFloat(min(max(i, 0), 1))
        if light.dims {
            // Fade the legend toward a fifth of its strength.
            return { c in var a: CGFloat = 1; c.getWhite(nil, alpha: &a); return c.withAlphaComponent(a * (1 - t * 0.8)) }
        }
        // On the enter key's accent pill the accent would vanish.
        if key.type == .enter { return nil }
        let lit = UIColor(argb: light.colourAt(x: f.midX, width: bounds.width, now: now, accent: theme.accent, heatLevel: t))
        var lr: CGFloat = 0, lg: CGFloat = 0, lb: CGFloat = 0
        lit.getRed(&lr, green: &lg, blue: &lb, alpha: nil)
        return { c in
            var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 1
            c.getRed(&r, green: &g, blue: &b, alpha: &a)
            let k = 1 - t
            return UIColor(red: r * k + lr * t, green: g * k + lg * t, blue: b * k + lb * t, alpha: a)
        }
    }

    /// The adaptive keys plugin's heatmap: each remembered mistake a soft red glow where the touch landed, and each
    /// letter whose aim has moved a dot where it now is, tied to the key's middle by a line in the theme's accent.
    private func drawAdaptive(_ ctx: CGContext, _ model: AdaptiveKeys) {
        guard let unit = keyRects.first(where: { $0.key.type == .char })?.rect else { return }
        let radius = unit.width * 0.45
        let space = CGColorSpaceCreateDeviceRGB()
        let colours = [UIColor(red: 1, green: 0.23, blue: 0.19, alpha: 0.33).cgColor, UIColor(red: 1, green: 0.23, blue: 0.19, alpha: 0).cgColor] as CFArray
        if let gradient = CGGradient(colorsSpace: space, colors: colours, locations: [0, 1]) {
            for m in model.mistakes {
                let p = CGPoint(x: CGFloat(m.x) * bounds.width, y: keysTop + CGFloat(m.y) * keysHeight)
                ctx.drawRadialGradient(gradient, startCenter: p, startRadius: 0, endCenter: p, endRadius: radius, options: [])
            }
        }
        let accent = UIColor(argb: theme.accent).cgColor
        ctx.setStrokeColor(accent); ctx.setFillColor(accent); ctx.setLineWidth(1.5)
        for (k, r) in keyRects {
            guard k.type == .char, k.text.count == 1, let c = k.text.first, c.isLetter else { continue }
            let (ox, oy) = model.offset(c)
            if ox == 0 && oy == 0 { continue }
            let p = CGPoint(x: r.midX + CGFloat(ox) * r.width, y: r.midY + CGFloat(oy) * r.height)
            ctx.move(to: CGPoint(x: r.midX, y: r.midY)); ctx.addLine(to: p); ctx.strokePath()
            ctx.fillEllipse(in: CGRect(x: p.x - 3.5, y: p.y - 3.5, width: 7, height: 7))
        }
    }

    private func fillRound(_ ctx: CGContext, _ r: CGRect, _ radius: CGFloat, _ colour: UIColor) {
        ctx.setFillColor(colour.cgColor)
        ctx.addPath(UIBezierPath(roundedRect: r, cornerRadius: min(radius, min(r.width, r.height) / 2)).cgPath)
        ctx.fillPath()
    }

    private func drawKey(_ ctx: CGContext, _ key: Key, _ rect: CGRect, pressed: Bool, legend: ((UIColor) -> UIColor)?) {
        let f0 = face(rect)
        var f = f0
        let t = theme
        func c(_ v: UInt32) -> UIColor { UIColor(argb: v) }
        if t.mechanical {
            f = drawKeycap(ctx, key, f0, pressed: pressed)
        } else if keyBorders {
            let colour: UInt32
            if key.type == .enter { colour = pressed ? t.accentPressed : t.accent }
            else if key.type == .shift && shift != .off { colour = pressed ? t.keyPressed : t.key }
            else if key.functional || key.type == .globe { colour = pressed ? t.functionKeyPressed : t.functionKey }
            else { colour = pressed ? t.keyPressed : t.key }
            fillRound(ctx, f, keyRadius, c(colour))
        } else {
            // Gboard: ?123, space and enter are pills about three quarters of a key's height; every other key is only
            // its label, with a wash while pressed.
            let shrink = f.height * 0.13
            let p = f.insetBy(dx: 0, dy: shrink)
            let pill = p.height / 2
            switch key.type {
            case .enter: fillRound(ctx, p, pill, c(pressed ? t.accentPressed : t.accent))
            case .space: fillRound(ctx, p, pill, c(pressed ? t.keyPressed : t.key))
            case .mode where key.pill: fillRound(ctx, p, pill, c(pressed ? t.keyPressed : t.key))
            default: if pressed { fillRound(ctx, f, keyRadius, c(t.key)) }
            }
        }
        let cx = f.midX, cy = f.midY
        func ink(_ v: UInt32) -> UIColor { let base = UIColor(argb: v); return legend?(base) ?? base }
        switch key.type {
        case .char:
            let text = shown(key)
            let letter = page == .letters && text.count == 1 && (text.first?.isLetter ?? false)
            let size: CGFloat = letter ? 23 : text.count > 2 ? 17 : 21
            drawText(text, cx, cy, size: size, weight: .regular, colour: ink(t.text), fit: f.width - 4)
            if showHints, let hint = key.hint {
                let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 10.5), .foregroundColor: ink(t.hint)]
                let s = (hint as NSString).size(withAttributes: attrs)
                (hint as NSString).draw(at: CGPoint(x: f.maxX - 7 - s.width / 2, y: f.minY + 2), withAttributes: attrs)
            }
        case .mode:
            // On the developer page its { } key is lit: it is the page's way back.
            drawText(key.label, cx, cy, size: 15, weight: .medium, colour: ink(page == .dev && key.label == "{ }" ? t.accent : t.text), fit: f.width - 2)
        case .space: break
        case .mic: RowIcons.draw(.mic, in: ctx, cx: cx, cy: cy, size: 22, ink: ink(t.text))
        case .globe: RowIcons.draw(.globe, in: ctx, cx: cx, cy: cy, size: 22, ink: ink(t.text))
        case .shift:
            // Filled while on; underlined, too, when locked.
            RowIcons.draw(.shift, in: ctx, cx: cx, cy: cy, size: 23, ink: ink(t.text), filled: shift != .off)
            if shift == .locked {
                ctx.setStrokeColor(ink(t.text).cgColor); ctx.setLineWidth(2); ctx.setLineCap(.round)
                ctx.move(to: CGPoint(x: cx - 5, y: cy + 14)); ctx.addLine(to: CGPoint(x: cx + 5, y: cy + 14)); ctx.strokePath()
            }
        case .delete: RowIcons.draw(.delete, in: ctx, cx: cx, cy: cy, size: 24, ink: ink(t.text))
        case .enter: drawEnter(ctx, cx, cy, ink(t.onAccent))
        }
    }

    /// The Retro theme's keycap: a darker skirt below a cap with a lit top edge, the cap sinking most of the way into
    /// its skirt while pressed. Returns the cap's top face, where the label goes.
    private func drawKeycap(_ ctx: CGContext, _ key: Key, _ f: CGRect, pressed: Bool) -> CGRect {
        let base: UInt32
        if key.type == .enter { base = theme.accent }
        else if key.type == .shift && shift != .off { base = theme.key }
        else if key.functional || key.type == .globe { base = theme.functionKey }
        else { base = theme.key }
        let depth: CGFloat = 5
        let travel: CGFloat = pressed ? depth * 0.7 : 0
        let r = keyRadius * 0.8
        fillRound(ctx, CGRect(x: f.minX + 0.5, y: f.minY + travel + 1.5, width: f.width - 1, height: f.height - travel), r, UIColor(argb: 0x55000000))
        fillRound(ctx, CGRect(x: f.minX, y: f.minY + travel, width: f.width, height: f.height - travel), r, UIColor(argb: KeyboardTheme.mix(base, 0xFF000000, 0.38)))
        let cap = CGRect(x: f.minX + 1.5, y: f.minY + travel, width: f.width - 3, height: f.height - depth - 1)
        fillRound(ctx, cap, r, UIColor(argb: pressed ? KeyboardTheme.mix(base, 0xFF000000, 0.08) : base))
        fillRound(ctx, CGRect(x: cap.minX + 2, y: cap.minY + 1, width: cap.width - 4, height: 2), 1, UIColor(argb: KeyboardTheme.mix(base, 0xFFFFFFFF, 0.35)).withAlphaComponent(pressed ? 60 / 255 : 140 / 255))
        return cap
    }

    /// What a character key shows: its label where it has its own (the developer page), else what it types.
    private func shown(_ key: Key) -> String { key.label != key.text ? key.label : display(key.text) }

    /// An alternative or snippet as it is shown: the caret mark gone, a line break as ⏎, a tab as ⇥.
    private func pretty(_ text: String) -> String {
        let p = text.replacingOccurrences(of: "¦", with: "").replacingOccurrences(of: "\n", with: "⏎").replacingOccurrences(of: "\t", with: "⇥").trimmingCharacters(in: .whitespaces)
        return p.isEmpty ? text.trimmingCharacters(in: .whitespaces) : p
    }

    private func display(_ text: String) -> String { page == .letters && shift != .off ? text.uppercased() : text }

    /// `text` centred on (cx, cy), its size shrunk until it fits `fit`.
    fileprivate func drawText(_ text: String, _ cx: CGFloat, _ cy: CGFloat, size: CGFloat, weight: UIFont.Weight, colour: UIColor, fit: CGFloat) {
        var size = size
        var font = UIFont.systemFont(ofSize: size, weight: weight)
        var s = (text as NSString).size(withAttributes: [.font: font])
        while size > 9 && s.width > fit {
            size -= 1
            font = UIFont.systemFont(ofSize: size, weight: weight)
            s = (text as NSString).size(withAttributes: [.font: font])
        }
        (text as NSString).draw(at: CGPoint(x: cx - s.width / 2, y: cy - s.height / 2), withAttributes: [.font: font, .foregroundColor: colour])
    }

    private func drawEnter(_ ctx: CGContext, _ cx: CGFloat, _ cy: CGFloat, _ ink: UIColor) {
        switch enterIcon {
        case .return: RowIcons.draw(.enter, in: ctx, cx: cx, cy: cy, size: 22, ink: ink); return
        case .search: RowIcons.draw(.search, in: ctx, cx: cx, cy: cy, size: 21, ink: ink); return
        default: break
        }
        ctx.setStrokeColor(ink.cgColor); ctx.setLineWidth(2); ctx.setLineCap(.round); ctx.setLineJoin(.round)
        func m(_ x: CGFloat, _ y: CGFloat) { ctx.move(to: CGPoint(x: cx + x, y: cy + y)) }
        func l(_ x: CGFloat, _ y: CGFloat) { ctx.addLine(to: CGPoint(x: cx + x, y: cy + y)) }
        switch enterIcon {
        case .go, .next: m(-8, 0); l(8, 0); m(3, -5); l(8, 0); l(3, 5)
        case .done: m(-8, 0); l(-3, 5); l(8, -6)
        case .send: m(-8, -7); l(9, 0); l(-8, 7); l(-5, 0); ctx.closePath()
        default: break
        }
        ctx.strokePath()
    }

    // --- the overlay: key preview, long-press panel, swipe trail -------------------------------------------------------

    fileprivate final class OverlayView: UIView {
        weak var owner: KeyboardView?
        override func draw(_ rect: CGRect) {
            guard let ctx = UIGraphicsGetCurrentContext() else { return }
            owner?.drawOverlay(ctx)
        }
    }

    fileprivate func drawOverlay(_ ctx: CGContext) {
        if let path = touch?.swipe { drawSwipe(ctx, path, fade: 1) }
        if let path = fadingSwipe {
            let f = 1 - CGFloat(CACurrentMediaTime() - fadingSince) / 0.28
            if f <= 0 { fadingSwipe = nil } else { drawSwipe(ctx, path, fade: f) }
        }
        if let t = touch {
            if let panel = t.panel { drawPanel(ctx, panel) }
            else if showPreview && t.key.type == .char && !t.cursorMoved && t.swipe == nil { drawPreview(ctx, t.key, t.rect) }
        } else if let g = ghostKey, showPreview, g.key.type == .char {
            drawPreview(ctx, g.key, g.rect)
        }
    }

    /// Swipe typing's trail: the finger's path as one smooth stroke, in the accent, fading toward where it started.
    private func drawSwipe(_ ctx: CGContext, _ points: [CGPoint], fade: CGFloat) {
        guard points.count >= 2 else { return }
        let from = max(0, points.count - 160)
        let tail = points[from], head = points[points.count - 1]
        let trail = CGMutablePath()
        trail.move(to: tail)
        for i in (from + 1)..<points.count {
            let p = points[i - 1], q = points[i]
            trail.addQuadCurve(to: CGPoint(x: (p.x + q.x) / 2, y: (p.y + q.y) / 2), control: p)
        }
        trail.addLine(to: head)
        let accent = UIColor(argb: theme.accent)
        let strong = accent.withAlphaComponent(min(max(fade * 235 / 255, 0), 1))
        let faint = accent.withAlphaComponent(min(max(fade * 40 / 255, 0), 1))
        ctx.saveGState()
        ctx.setLineWidth(5); ctx.setLineCap(.round); ctx.setLineJoin(.round)
        if hypot(head.x - tail.x, head.y - tail.y) < 4 {
            ctx.setStrokeColor(strong.cgColor); ctx.addPath(trail); ctx.strokePath()
        } else if let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [faint.cgColor, strong.cgColor] as CFArray, locations: [0, 1]) {
            ctx.addPath(trail)
            ctx.replacePathWithStrokedPath()
            ctx.clip()
            ctx.drawLinearGradient(gradient, start: tail, end: head, options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        }
        ctx.restoreGState()
    }

    /// The last swipe's path, fading out for a moment after the finger lifts.
    private var fadingSwipe: [CGPoint]?
    private var fadingSince: CFTimeInterval = 0

    /// Each letter key's centre, for the swipe decoder.
    private func letterCenters() -> [Character: CGPoint] {
        var out: [Character: CGPoint] = [:]
        for (k, r) in keyRects where k.type == .char && k.text.count == 1 {
            if let c = k.text.lowercased().first, c.isLetter { out[c] = CGPoint(x: r.midX, y: r.midY) }
        }
        return out
    }

    private func shadowed(_ ctx: CGContext, blur: CGFloat, dy: CGFloat, _ body: () -> Void) {
        ctx.saveGState()
        ctx.setShadow(offset: CGSize(width: 0, height: dy), blur: blur, color: UIColor(argb: theme.popupShadow).cgColor)
        body()
        ctx.restoreGState()
    }

    /// Gboard's key preview: a bubble the width of the key standing on top of it, the character drawn large.
    private func drawPreview(_ ctx: CGContext, _ key: Key, _ rect: CGRect) {
        let f = face(rect)
        let h = min(f.height * 1.15, f.minY - 2)
        if h < 24 { return }
        let bubble = CGRect(x: f.minX - 2, y: f.minY - h - 2, width: f.width + 4, height: h)
        shadowed(ctx, blur: 4, dy: 1) { fillRound(ctx, bubble, keyRadius, UIColor(argb: theme.popup)) }
        drawText(shown(key), bubble.midX, bubble.midY, size: 30, weight: .regular, colour: UIColor(argb: theme.text), fit: bubble.width - 4)
    }

    // --- the long-press panel ---------------------------------------------------------------------------------------

    fileprivate final class Panel {
        let choices: [String]; let cells: [CGRect]; let bounds: CGRect
        var selected = 0
        init(choices: [String], cells: [CGRect], bounds: CGRect) { self.choices = choices; self.cells = cells; self.bounds = bounds }
    }

    private func makePanel(_ key: Key, _ rect: CGRect) -> Panel? {
        if key.alts.isEmpty { return nil }
        let choices = key.alts.map { display($0) }
        let f = face(rect)
        // On a wide screen a key is far wider than a choice needs; a panel of eight must still fit a phone's width.
        let cellW = min(f.width + keyPadX * 2, (bounds.width - 16) / CGFloat(min(choices.count, 8)))
        let cellH = f.height
        let cols = min(choices.count, 8)
        let lines = Int(ceil(Double(choices.count) / Double(cols)))
        let pad: CGFloat = 6
        let w = CGFloat(cols) * cellW + pad * 2
        let h = CGFloat(lines) * cellH + pad * 2
        // Centred on the key where it fits, the first choice nearest the finger.
        var left = f.midX - cellW / 2 - pad
        if left + w > bounds.width - 2 { left = bounds.width - 2 - w }
        if left < 2 { left = 2 }
        let bottom = f.minY - 2
        let top = max(bottom - h, 0)
        let b = CGRect(x: left, y: top, width: w, height: h)
        let cells = choices.indices.map { i -> CGRect in
            // The first line is the one nearest the key, as Gboard fills its panels.
            let line = lines - 1 - i / cols
            let col = i % cols
            return CGRect(x: left + pad + CGFloat(col) * cellW, y: top + pad + CGFloat(line) * cellH, width: cellW, height: cellH)
        }
        return Panel(choices: choices, cells: cells, bounds: b)
    }

    private func drawPanel(_ ctx: CGContext, _ panel: Panel) {
        shadowed(ctx, blur: 6, dy: 2) { fillRound(ctx, panel.bounds, keyRadius * 1.5, UIColor(argb: theme.popup)) }
        for (i, choice) in panel.choices.enumerated() {
            let cell = panel.cells[i]
            var colour = UIColor(argb: theme.text)
            if i == panel.selected {
                fillRound(ctx, cell.insetBy(dx: 2, dy: 2), keyRadius, UIColor(argb: theme.popupSelected))
                colour = UIColor(argb: theme.onPopupSelected)
            }
            drawText(pretty(choice), cell.midX, cell.midY, size: 22, weight: .regular, colour: colour, fit: cell.width - 4)
        }
    }

    private func selectInPanel(_ panel: Panel, _ x: CGFloat, _ y: CGFloat) {
        let cols = min(panel.choices.count, 8)
        let cellW = panel.cells[0].width, cellH = panel.cells[0].height
        let lines = Int(ceil(Double(panel.choices.count) / Double(cols)))
        let left = panel.cells.map { $0.minX }.min() ?? 0
        let col = min(max(Int(floor((x - left) / cellW)), 0), cols - 1)
        // A finger below the panel is still on its first line.
        let top = panel.bounds.minY + 6
        let lineFromTop = min(max(Int(floor((y - top) / cellH)), 0), lines - 1)
        let line = lines - 1 - lineFromTop
        let index = min(line * cols + col, panel.choices.count - 1)
        if index != panel.selected { panel.selected = index; overlay.setNeedsDisplay() }
    }

    // --- touch ------------------------------------------------------------------------------------------------------

    fileprivate final class Touch {
        let uiTouch: UITouch
        var key: Key
        var rect: CGRect
        let downX: CGFloat, downY: CGFloat
        let firstKey: Key
        /// Where the touch was read as aiming, after correction.
        var aimX: CGFloat, aimY: CGFloat
        var panel: Panel?
        var longPressed = false
        var cursorAnchor: CGFloat
        var cursorMoved = false
        var lineAnchor: CGFloat
        var wordAnchor: CGFloat
        var wordsDeleted = false
        /// Swipe typing: the path so far, once the finger has slid far enough to be spelling a word.
        var swipe: [CGPoint]?
        var lastEvent: UIEvent?

        init(_ t: UITouch, _ key: Key, _ rect: CGRect, _ x: CGFloat, _ y: CGFloat) {
            uiTouch = t; self.key = key; self.rect = rect; downX = x; downY = y; firstKey = key
            aimX = x; aimY = y; cursorAnchor = x; lineAnchor = y; wordAnchor = x
        }
    }

    private var touch: Touch?
    private var longPressWork: DispatchWorkItem?
    private var repeatTimer: Timer?

    private func scheduleLongPress() {
        longPressWork?.cancel()
        let work = DispatchWorkItem { [weak self] in if let self, let t = self.touch { self.onLongPress(t) } }
        longPressWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(longPressMs), execute: work)
    }

    private func cancelTimers() {
        longPressWork?.cancel(); longPressWork = nil
        repeatTimer?.invalidate(); repeatTimer = nil
    }

    /// The key a touch at (rawX, rawY) means, after touch position correction: thumbs land below and to one side of
    /// where they aim, more so the higher the row (measured on Android from logged taps; see KeyboardView.kt).
    private func keyAt(_ rawX: CGFloat, _ rawY: CGFloat) -> (key: Key, rect: CGRect)? {
        if rawY < keysTop { return nil }
        // Docked or one-handed: the empty side is not a key (a little past the keys' edge still is).
        if rawX < areaLeft - Self.dockSlack || rawX > areaRight + Self.dockSlack { return nil }
        let x: CGFloat, y: CGFloat
        if page == .letters {
            let (ox, oy) = letterCorrection(rawX, rawY)
            x = rawX - ox; y = max(rawY - oy, keysTop + 1)
        } else {
            let rowH = keysHeight / CGFloat(rows.count)
            let fromBottom = rows.count - 1 - min(max(Int((rawY - keysTop) / rowH), 0), rows.count - 1)
            let lift: CGFloat = fromBottom <= 1 ? 0 : fromBottom == 2 ? Self.liftMiddle : Self.liftTop
            x = rawX < bounds.width / 2 ? rawX + Self.shiftLeftHalf : rawX
            y = max(rawY - lift, keysTop + 1)
        }
        lastAim = (x, y)
        if let hit = keyRects.first(where: { $0.rect.contains(CGPoint(x: x, y: y)) }) { return adapt(hit, x, y) }
        // Below the last row or past its ends: the nearest key, so the edges never swallow a touch.
        return keyRects.min { a, b in dist2(a.rect, x, y) < dist2(b.rect, x, y) }
    }

    private func dist2(_ r: CGRect, _ x: CGFloat, _ y: CGFloat) -> CGFloat {
        let dx = x < r.minX ? r.minX - x : x > r.maxX ? x - r.maxX : 0
        let dy = y < r.minY ? r.minY - y : y > r.maxY ? y - r.maxY : 0
        return dx * dx + dy * dy
    }

    /// On the letters page, how far from where it is aimed a thumb lands at (x, y): a smooth blend of each key's own
    /// measured offset, weighted by how near its centre the touch is. The bottom row's keys count as on target.
    private func letterCorrection(_ x: CGFloat, _ y: CGFloat) -> (CGFloat, CGFloat) {
        guard let unitW = keyRects.first(where: { $0.key.type == .char })?.rect.width, let last = keyRects.last else { return (0, 0) }
        // The offsets were measured on 60dp rows; a shorter row takes a like share of them.
        let scale = rowHeight / 60
        let sigma2 = 2 * (unitW * 0.6) * (unitW * 0.6)
        var sw: CGFloat = 0, sx: CGFloat = 0, sy: CGFloat = 0
        let bottomTop = last.rect.minY
        for (key, r) in keyRects {
            let off = Self.letterOffsets[key.text]
            if off == nil && r.minY < bottomTop { continue }
            let dx = x - r.midX, dy = y - r.midY
            let w = exp(-(dx * dx + dy * dy) / sigma2)
            sw += w
            if let off { sx += w * off.0 * scale; sy += w * off.1 * scale }
        }
        return sw < 1e-6 ? (0, 0) : (sx / sw, sy / sw)
    }

    private var lastAim: (CGFloat, CGFloat) = (0, 0)

    /// The adaptive keys plugin: of the letter under the touch and its neighbours, the one whose learned aim is nearest.
    private func adapt(_ hit: (key: Key, rect: CGRect), _ x: CGFloat, _ y: CGFloat) -> (key: Key, rect: CGRect) {
        guard let model = adaptive, page == .letters, hit.key.type == .char, hit.key.text.count == 1, hit.key.text.first?.isLetter == true else { return hit }
        let w = hit.rect.width, h = hit.rect.height
        func cost(_ r: CGRect, _ c: Character) -> CGFloat {
            let (ox, oy) = model.offset(c)
            let cx = r.midX + CGFloat(ox) * r.width, cy = r.midY + CGFloat(oy) * r.height
            let dx = (x - cx) / r.width, dy = (y - cy) / r.height
            return dx * dx + dy * dy
        }
        let near = keyRects.filter { k, r in
            k.type == .char && k.text.count == 1 && k.text.first?.isLetter == true && abs(r.midX - hit.rect.midX) < w * 1.6 && abs(r.midY - hit.rect.midY) < h * 1.6
        }
        return near.min { cost($0.rect, $0.key.text.first!) < cost($1.rect, $1.key.text.first!) } ?? hit
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for ui in touches {
            let p = ui.location(in: self)
            // Rolling typing: a second finger down finishes the first finger's key.
            if let t = touch { if t.panel == nil { release(t) } else { continue } }
            if p.y < keysTop { continue }
            if p.x < areaLeft - Self.dockSlack || p.x > areaRight + Self.dockSlack {
                if let (_, side) = dockFlip() { listener?.onKeyFeedback(.mode, haptic: true); listener?.onDock(side, wide: isWide) }
                continue
            }
            guard let hit = keyAt(p.x, p.y) else { continue }
            let t = Touch(ui, hit.key, hit.rect, p.x, p.y)
            t.aimX = lastAim.0; t.aimY = lastAim.1
            t.lastEvent = event
            press(t)
        }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touch, touches.contains(t.uiTouch) else { return }
        t.lastEvent = event
        // The globe key is iOS's own: it is handed every phase of its touch (a tap is the next keyboard, a hold the list).
        if t.key.type == .globe { if let event { listener?.onGlobe(from: self, with: event) }; return }
        // A swipe takes the points iOS gathered between frames too, so its trail follows the finger closely.
        if t.swipe != nil, let all = event?.coalescedTouches(for: t.uiTouch), all.count > 1 {
            for c in all.dropLast() { let q = c.location(in: self); addSwipePoint(t, q.x, q.y) }
        }
        let p = t.uiTouch.location(in: self)
        move(t, p.x, p.y)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touch, touches.contains(t.uiTouch) else { return }
        t.lastEvent = event
        if t.key.type == .globe, let event { listener?.onGlobe(from: self, with: event) }
        release(t)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) { cancelTouch() }

    private func press(_ t: Touch) {
        touch = t
        listener?.onKeyFeedback(t.key.type, haptic: true)
        backlight?.press(id: lightId(t.key), x: t.rect.midX, y: t.rect.midY, now: nowMs())
        switch t.key.type {
        case .delete:
            listener?.onDelete()
            repeatTimer?.invalidate()
            repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self] _ in
                self?.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
                    guard let self, let t = self.touch, t.key.type == .delete else { return }
                    // A sound for each character a held backspace takes; the haptic stays on the first press.
                    self.listener?.onKeyFeedback(.delete, haptic: false)
                    self.listener?.onDelete()
                }
            }
        case .globe: if let e = t.lastEvent { listener?.onGlobe(from: self, with: e) }
        case .char, .space, .shift: scheduleLongPress()
        case .mode: if t.key.label == "{ }" { scheduleLongPress() }
        default: break
        }
        redraw()
    }

    private func move(_ t: Touch, _ x: CGFloat, _ y: CGFloat) {
        if let panel = t.panel { selectInPanel(panel, x, y); return }
        switch t.key.type {
        case .space:
            guard spaceCursor else { return }
            // Gboard's cursor control: slide along the space bar to move the cursor a character at a time.
            let step: CGFloat = 12
            let dx = x - t.cursorAnchor
            if abs(dx) >= step && (t.cursorMoved || abs(x - t.downX) > 16) {
                if !t.cursorMoved { t.cursorMoved = true; longPressWork?.cancel(); redraw() }
                let steps = Int(dx / step)
                t.cursorAnchor += CGFloat(steps) * step
                listener?.onCursor(steps)
            }
            // Up or down from the space bar moves by lines: a longer pull than a character's.
            let lineStep: CGFloat = 30
            let dy = y - t.lineAnchor
            if abs(dy) >= lineStep && (t.cursorMoved || abs(y - t.downY) > 34) {
                if !t.cursorMoved { t.cursorMoved = true; longPressWork?.cancel(); redraw() }
                let lines = Int(dy / lineStep)
                t.lineAnchor += CGFloat(lines) * lineStep
                listener?.onCursorLine(lines)
            }
        case .delete:
            guard deleteSwipe else { return }
            // Gboard's word delete: slide left from backspace and each stretch of the slide takes a word.
            let step: CGFloat = 26
            let dx = t.wordAnchor - x
            if dx >= step && (t.wordsDeleted || t.downX - x > 30) {
                if !t.wordsDeleted { t.wordsDeleted = true; repeatTimer?.invalidate(); repeatTimer = nil }
                let words = Int(dx / step)
                t.wordAnchor -= CGFloat(words) * step
                for _ in 0..<words { listener?.onKeyFeedback(.delete, haptic: false); listener?.onDeleteWord() }
            }
        case .char:
            if t.swipe != nil { addSwipePoint(t, x, y); overlay.setNeedsDisplay(); return }
            // Swipe typing: a slide of most of a key's width from a letter starts spelling a word.
            if swipeTyping && page == .letters && t.firstKey.text.count == 1 && t.firstKey.text.first?.isLetter == true && hypot(x - t.downX, y - t.downY) > t.rect.width * 0.9 {
                t.swipe = [CGPoint(x: t.downX, y: t.downY), CGPoint(x: x, y: y)]
                longPressWork?.cancel()
                redraw()
                return
            }
            // A finger that clearly slides onto another character key types that one instead. A thumb rolls a little
            // as it lifts, so staying within 40% of a key's size past its edge is still that key.
            if t.rect.insetBy(dx: -t.rect.width * 0.4, dy: -t.rect.height * 0.4).contains(CGPoint(x: x, y: y)) { return }
            guard let hit = keyAt(x, y) else { return }
            if (hit.key != t.key || hit.rect != t.rect) && hit.key.type == .char {
                t.key = hit.key; t.rect = hit.rect
                scheduleLongPress()
                redraw()
            }
        default: break
        }
    }

    /// A point on a swipe's path, unless it is within a hair of the last (jitter that would kink the curve).
    private func addSwipePoint(_ t: Touch, _ x: CGFloat, _ y: CGFloat) {
        if let last = t.swipe?.last, hypot(x - last.x, y - last.y) < 1.5 { return }
        t.swipe?.append(CGPoint(x: x, y: y))
    }

    private func onLongPress(_ t: Touch) {
        switch t.key.type {
        case .space, .globe: break
        case .mode:
            // The { } key, where Gboard's comma was: long-press opens settings, as the comma's did.
            t.longPressed = true; listener?.onOpenSettings()
        case .shift:
            t.longPressed = true
            shift = .locked; autoShifted = false
            listener?.onShiftChanged(); redraw()
        case .char:
            if t.key.text == "," { t.longPressed = true; listener?.onOpenSettings(); return }
            guard let panel = makePanel(t.key, t.rect) else { return }
            t.panel = panel
            t.longPressed = true
            listener?.onKeyFeedback(.mode, haptic: true)
            redraw()
        default: break
        }
    }

    private func release(_ t: Touch) {
        cancelTimers()
        touch = nil
        if let swipe = t.swipe {
            let unitW = keyRects.first { $0.key.type == .char }?.rect.width ?? t.rect.width
            let wasShift = shift
            listener?.onSwipe(swipe, keys: letterCenters(), keyWidth: unitW)
            fadingSwipe = swipe
            fadingSince = CACurrentMediaTime()
            startAnimating()
            if wasShift == .on { shift = .off; autoShifted = false; listener?.onShiftChanged() }
        } else if let panel = t.panel {
            commit(panel.choices[panel.selected])
        } else if !t.longPressed {
            switch t.key.type {
            case .char:
                if t.key == t.firstKey, t.key.text.count == 1, let c = t.key.text.first { lastTyped = Typed(c: c, rect: t.rect, x: t.aimX, y: t.aimY) } else { lastTyped = nil }
                commit(display(t.key.text))
                // A character key that names a page goes there after typing: the developer page's comma.
                if let next = t.key.page { showPage(next) }
            case .space: if !t.cursorMoved { listener?.onSpace() }
            case .enter: listener?.onEnter()
            case .shift: tapShift()
            case .mode: showPage(t.key.page ?? .letters)
            case .mic, .delete, .globe: break
            }
        }
        redraw()
    }

    private func cancelTouch() {
        cancelTimers()
        touch = nil
        redraw()
    }

    private func commit(_ raw: String) {
        if shift == .on { shift = .off; autoShifted = false }
        if let caret = raw.firstIndex(of: "¦") {
            var text = raw
            text.remove(at: caret)
            listener?.onText(text, caretBack: raw.distance(from: caret, to: raw.endIndex) - 1)
        } else {
            listener?.onText(raw, caretBack: 0)
        }
        redraw()
    }

    private func tapShift() {
        let now = CACurrentMediaTime()
        switch shift {
        case .off: shift = .on
        // Gboard: a second tap soon after the first locks caps.
        case .on: shift = (!autoShifted && now - lastShiftTap < 0.4) ? .locked : .off
        case .locked: shift = .off
        }
        autoShifted = false
        lastShiftTap = now
        listener?.onShiftChanged()
        redraw()
    }

    // --- testing ------------------------------------------------------------------------------------------------------

    /// Where each key stands now, by what it shows: for UI tests and the settings preview.
    func keyFrames() -> [(label: String, type: KeyType, rect: CGRect)] { keyRects.map { (shown($0.key), $0.key.type, $0.rect) } }

    /// Wider than this (points), the keys split: an iPad is 744 or more across, a phone 440 or less.
    static let splitMin: CGFloat = 600
    /// The open middle of a split keyboard, as a share of its width.
    static let splitGap: CGFloat = 0.3
    /// A docked keyboard on a wide screen is this wide: a phone's keyboard, in reach of one thumb.
    static let compactWidth: CGFloat = 400
    /// One-handed on a phone: the keys take this share of the width, on the thumb's side.
    static let oneHandedShare: CGFloat = 0.8
    private static let dockSlack: CGFloat = 14
    /// Where a thumb lands relative to each letter's centre (+x right, +y down), from Android's tap logs.
    private static let letterOffsets: [String: (CGFloat, CGFloat)] = [
        "q": (-3.6, 7.0), "w": (-4.1, 3.0), "e": (-5.9, 2.7), "r": (-5.9, 8.5),
        "t": (-9.0, 6.2), "y": (0.7, 13.9), "u": (-2.2, 17.0), "i": (-0.7, 14.1),
        "o": (-3.8, 16.7), "p": (-4.9, 17.0), "a": (-7.2, 2.7), "s": (-10.5, 4.6),
        "d": (-10.1, 3.2), "f": (-4.4, 5.9), "g": (-6.2, 9.7), "h": (5.6, 8.7),
        "j": (3.8, 3.8), "k": (0.5, 5.6), "l": (-2.0, 14.3), "z": (-0.3, 3.6),
        "x": (1.4, 3.7), "c": (-1.3, 3.6), "v": (-3.0, 0.0), "b": (1.5, -3.3),
        "n": (0.2, 3.5), "m": (-1.7, 0.2),
    ]
    private static let liftTop: CGFloat = 11
    private static let liftMiddle: CGFloat = 6
    private static let shiftLeftHalf: CGFloat = 5
}
