import UIKit
import CoreText

/// The plugin row's colours (RowStyle.kt's RowTint). One hue for each kind of thing, so a glance says what a chip is:
/// the AI's violet, the clipboard's amber, a result's green, a failure's red. Each has a shade for light keyboards and
/// one for dark, picked from the theme. Android's emoji, GIF and Ghost.md hues are not here: iOS has none of the three.
enum RowTint {
    case ai, clipboard, done, error

    private var shades: (light: UInt32, dark: UInt32) {
        switch self {
        case .ai: return (0xFF6A4FD8, 0xFFB9A6FF)
        case .clipboard: return (0xFFB45F06, 0xFFFFB866)
        case .done: return (0xFF2E7D32, 0xFF8BD18F)
        case .error: return (0xFFC62828, 0xFFFF9C9C)
        }
    }

    func ink(_ theme: KeyboardTheme) -> UIColor { UIColor(argb: theme.dark ? shades.dark : shades.light) }

    /// The chip's ground: the hue, faint, over the row (or over iOS's keyboard backdrop, where the row has no ground).
    func wash(_ theme: KeyboardTheme) -> UIColor { ink(theme).withAlphaComponent(CGFloat(theme.dark ? 0x2E : 0x1F) / 255) }
}

/// Whether the person has animations on (Settings › Accessibility › Motion › Reduce Motion turns them off).
func animationsOn() -> Bool { !UIAccessibility.isReduceMotionEnabled }

/// The row while the AI works (RowStyle.kt's ThinkingView): a violet pill with a sparkle that breathes and turns, a
/// small star that twinkles beside it, and the words with a light passing over them. It animates only while it is on
/// screen, at about 30 frames a second, and stands still with animations off.
final class ThinkingView: UIControl {
    private let theme: KeyboardTheme
    private let ink: UIColor
    private let line: CTLine
    private let dots: CTLine
    private let wordsWidth: CGFloat
    private let start = CACurrentMediaTime()
    private var link: CADisplayLink?
    /// The words cut to the room they were last drawn in.
    private var fitted: (room: CGFloat, line: CTLine)?

    init(words: String, theme: KeyboardTheme) {
        self.theme = theme
        ink = RowTint.ai.ink(theme)
        let attrs: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): UIFont.systemFont(ofSize: 14, weight: .medium),
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): UIColor(argb: theme.text).cgColor,
        ]
        line = CTLineCreateWithAttributedString(NSAttributedString(string: words, attributes: attrs))
        dots = CTLineCreateWithAttributedString(NSAttributedString(string: "…", attributes: attrs))
        wordsWidth = CGFloat(CTLineGetTypographicBounds(line, nil, nil, nil))
        super.init(frame: .zero)
        backgroundColor = RowTint.ai.wash(theme)
        isOpaque = false
        contentMode = .redraw
        layer.cornerCurve = .continuous
        isAccessibilityElement = true
        accessibilityLabel = words
        accessibilityTraits = .updatesFrequently
    }

    required init?(coder: NSCoder) { fatalError() }

    override func sizeThatFits(_ size: CGSize) -> CGSize { CGSize(width: ceil(12 + 20 + 8 + 14 + wordsWidth), height: size.height) }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = min(18, bounds.height / 2)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        link?.invalidate(); link = nil
        guard window != nil, animationsOn() else { return }
        let next = CADisplayLink(target: self, selector: #selector(tick))
        next.preferredFramesPerSecond = 30
        next.add(to: .main, forMode: .common)
        link = next
    }

    @objc private func tick() { setNeedsDisplay() }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        let moving = link != nil
        let t = moving ? CGFloat(CACurrentMediaTime() - start) : 0
        let h = bounds.height

        // The sparkle: breathing between 85% and 100%, turning a little either way.
        let size: CGFloat = 19
        let cx = 12 + size / 2, cy = h / 2
        let breathe = 0.925 + 0.075 * sin(t * 2 * .pi / 1.4)
        ctx.saveGState()
        ctx.translateBy(x: cx, y: cy)
        ctx.rotate(by: 10 * sin(t * 2 * .pi / 2.8) * .pi / 180)
        ctx.scaleBy(x: breathe, y: breathe)
        RowIcons.draw(.sparkle, in: ctx, cx: 0, cy: 0, size: size, ink: ink)
        ctx.restoreGState()
        // The small star: up and to the right, fading in and out out of step with the sparkle.
        let twinkle = moving ? 0.5 + 0.5 * sin(t * 2 * .pi / 1.1 + 1.3) : 1
        drawStar(ctx, cx + size * 0.55, cy - size * 0.42, 3.2 * (0.6 + 0.4 * twinkle), ink.withAlphaComponent(0.35 + 0.65 * twinkle))

        // The words, cut to fit, with a light passing over them every 1.6 s.
        let x = 12 + size + 8
        let room = bounds.width - x - 14
        guard room > 0 else { return }
        if fitted?.room != room { fitted = (room, wordsWidth <= room ? line : (CTLineCreateTruncatedLine(line, Double(room), .end, dots) ?? line)) }
        guard let shown = fitted?.line else { return }
        var ascent: CGFloat = 0, descent: CGFloat = 0
        let w = CGFloat(CTLineGetTypographicBounds(shown, &ascent, &descent, nil))
        let text = UIColor(argb: theme.text)
        ctx.saveGState()
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        ctx.textPosition = CGPoint(x: x, y: h / 2 + (ascent - descent) / 2)
        CTLineDraw(shown, ctx)
        // The light is poured over the words only: it colours what is already drawn there and nothing around it.
        if moving, let light = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [text.cgColor, ink.cgColor, text.cgColor] as CFArray, locations: [0, 0.5, 1]) {
            let p = t.truncatingRemainder(dividingBy: 1.6) / 1.6
            let mid = x - w * 0.4 + w * 1.8 * p
            ctx.clip(to: CGRect(x: x, y: 0, width: ceil(w) + 2, height: h))
            ctx.setBlendMode(.sourceAtop)
            ctx.drawLinearGradient(light, start: CGPoint(x: mid - 36, y: 0), end: CGPoint(x: mid + 36, y: 0), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
        }
        ctx.restoreGState()
    }

    /// A four-pointed star of radius `r`: the AI's small spark.
    private func drawStar(_ ctx: CGContext, _ cx: CGFloat, _ cy: CGFloat, _ r: CGFloat, _ colour: UIColor) {
        let k = r * 0.28
        ctx.move(to: CGPoint(x: cx, y: cy - r))
        ctx.addQuadCurve(to: CGPoint(x: cx + r, y: cy), control: CGPoint(x: cx + k, y: cy - k))
        ctx.addQuadCurve(to: CGPoint(x: cx, y: cy + r), control: CGPoint(x: cx + k, y: cy + k))
        ctx.addQuadCurve(to: CGPoint(x: cx - r, y: cy), control: CGPoint(x: cx - k, y: cy + k))
        ctx.addQuadCurve(to: CGPoint(x: cx, y: cy - r), control: CGPoint(x: cx - k, y: cy - k))
        ctx.closePath()
        ctx.setFillColor(colour.cgColor)
        ctx.fillPath()
    }
}

/// The keyboard's sign that something is working (RowStyle.kt's WorkingLine): a hairline along the top of the keys
/// with a violet light running along it, there while the AI writes, types or rewrites. Off, it is hidden and draws
/// nothing. With animations off it is a steady line. The light is a layer the system moves, so nothing is redrawn
/// while it runs.
final class WorkingLine: UIView {
    static let height: CGFloat = 2.5

    var theme: KeyboardTheme = .light { didSet { recolour() } }

    var on = false {
        didSet {
            guard on != oldValue else { return }
            if on {
                isHidden = false
                alpha = 0
                recolour()
                run()
                UIView.animate(withDuration: 0.16) { self.alpha = 1 }
            } else {
                UIView.animate(withDuration: 0.22, animations: { self.alpha = 0 }, completion: { _ in
                    guard !self.on else { return }
                    self.isHidden = true
                    self.light.removeAllAnimations()
                })
            }
        }
    }

    private let light = CAGradientLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isHidden = true
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        clipsToBounds = true
        light.startPoint = CGPoint(x: 0, y: 0.5)
        light.endPoint = CGPoint(x: 1, y: 0.5)
        light.locations = [0, 0.45, 0.6, 1]
        layer.addSublayer(light)
        recolour()
    }

    required init?(coder: NSCoder) { fatalError() }

    /// The track is the hue, faint, the whole width; the light is the hue running into the theme's accent.
    private func recolour() {
        let ink = RowTint.ai.ink(theme)
        let still = !animationsOn()
        backgroundColor = still ? ink : ink.withAlphaComponent(0.25)
        light.isHidden = still
        light.colors = [ink.withAlphaComponent(0).cgColor, ink.cgColor, UIColor(argb: theme.accent).cgColor, ink.withAlphaComponent(0).cgColor]
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        run()
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        run()
    }

    /// The light: a third of the width, running left to right in 1.3 s, easing in and out, then again.
    private func run() {
        light.removeAllAnimations()
        guard on, window != nil, animationsOn(), bounds.width > 0 else { return }
        let w = bounds.width, span = w * 0.35
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        light.bounds = CGRect(x: 0, y: 0, width: span, height: bounds.height)
        light.position = CGPoint(x: -span / 2, y: bounds.height / 2)
        CATransaction.commit()
        let slide = CABasicAnimation(keyPath: "position.x")
        slide.fromValue = -span / 2
        slide.toValue = w + span / 2
        slide.duration = 1.3
        slide.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        slide.repeatCount = .infinity
        slide.isRemovedOnCompletion = false
        light.add(slide, forKey: "run")
    }
}
