import UIKit

/// One thing a plugin puts in the row above the keys (PluginRow.kt's RowItem).
///
/// - `label`: text, a suggestion or a chip's name. `bold` is the pick (Gboard's middle suggestion).
/// - `icon`: a drawn icon instead of, or before, the label.
/// - `wide`: a suggestion: shares the row's width with the others, a third each, as Gboard's do.
/// - `fill`: takes all the width the others leave (a status line, a search bar).
/// - `tint`: the chip's colour (RowTint): its icon in the hue, on a faint ground of it.
/// - `busy`: the AI is working on it: drawn as a ThinkingView, a sparkle and the words with a light passing over them.
/// - `description`: what a reader says for an icon with no words (VoiceOver).
struct RowItem {
    let id: String
    var label: String? = nil
    var icon: RowIcon? = nil
    var bold = false
    var wide = false
    var chip = false
    var fill = false
    var tint: RowTint? = nil
    var busy = false
    var description: String? = nil
    var onTap: (() -> Void)? = nil
}

/// The row above the keys: a strip that plugins fill, scrolling sideways when they offer more than fits. A settings
/// button stays at its start, arrow keys (where they are on) at its end.
final class PluginRowView: UIView {
    var theme: KeyboardTheme = .dark { didSet { restyle() } }
    /// As KeyboardView.systemBackdrop: the row drawn on iOS's own keyboard backdrop.
    var systemBackdrop = false { didSet { restyle() } }
    var onSettings: () -> Void = {}
    /// An arrow key: "left", "up", "down" or "right".
    var onArrow: (String) -> Void = { _ in }
    /// A tap on anything in the row: the keyboard's haptic.
    var onTapFeedback: () -> Void = {}

    var showArrows = false { didSet { arrows.isHidden = !showArrows; setNeedsLayout() } }

    /// The old toolbar setting: a button at the row's end that puts the keyboard away.
    var showHide = false { didSet { hide.isHidden = !showHide; setNeedsLayout() } }
    var onHide: () -> Void = {}
    private let hide = IconButton(.hide)

    private let settings = IconButton(.settings)
    private let scroll = RowScroll()
    private let arrows = UIView()
    private var arrowButtons: [IconButton] = []
    private var items: [RowItem] = []
    private var views: [UIView] = []
    private var repeatTimer: Timer?

    override init(frame: CGRect) {
        super.init(frame: frame)
        scroll.showsHorizontalScrollIndicator = false
        scroll.showsVerticalScrollIndicator = false
        scroll.alwaysBounceHorizontal = false
        scroll.delaysContentTouches = false
        scroll.canCancelContentTouches = true
        // iOS 26 blurs a scroll view's content near its edges, for content sliding under bars: this row is all edge.
        if #available(iOS 26.0, *) {
            scroll.topEdgeEffect.isHidden = true
            scroll.bottomEdgeEffect.isHidden = true
            scroll.leftEdgeEffect.isHidden = true
            scroll.rightEdgeEffect.isHidden = true
        }
        addSubview(settings); addSubview(scroll); addSubview(arrows); addSubview(hide)
        hide.isHidden = true
        hide.accessibilityLabel = "Hide keyboard"
        hide.addAction(UIAction { [weak self] _ in self?.onTapFeedback(); self?.onHide() }, for: .touchUpInside)
        settings.accessibilityLabel = "Keyboard settings"
        settings.addAction(UIAction { [weak self] _ in self?.onTapFeedback(); self?.onSettings() }, for: .touchUpInside)
        for (icon, name) in [(RowIcon.caretLeft, "left"), (.caretUp, "up"), (.caretDown, "down"), (.caretRight, "right")] {
            let b = IconButton(icon)
            b.accessibilityLabel = "Arrow \(name)"
            // On touch, then again and again while held: 400 ms before the first repeat, then every 50 ms.
            b.addAction(UIAction { [weak self] _ in
                guard let self else { return }
                self.onArrow(name)
                self.repeatTimer?.invalidate()
                self.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.4, repeats: false) { [weak self] _ in
                    self?.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in self?.onArrow(name) }
                }
            }, for: .touchDown)
            b.addAction(UIAction { [weak self] _ in self?.repeatTimer?.invalidate(); self?.repeatTimer = nil }, for: [.touchUpInside, .touchUpOutside, .touchCancel])
            arrowButtons.append(b)
            arrows.addSubview(b)
        }
        arrows.isHidden = true
        restyle()
    }

    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        let h = bounds.height
        settings.frame = CGRect(x: 0, y: 0, width: 44, height: h)
        let arrowsW: CGFloat = showArrows ? 36 * 4 : 0
        let hideW: CGFloat = showHide ? 44 : 0
        hide.frame = CGRect(x: bounds.width - hideW, y: 0, width: hideW, height: h)
        arrows.frame = CGRect(x: bounds.width - hideW - arrowsW, y: 0, width: arrowsW, height: h)
        for (i, b) in arrowButtons.enumerated() { b.frame = CGRect(x: CGFloat(i) * 36, y: 0, width: 36, height: h) }
        let next = CGRect(x: 44, y: 0, width: max(0, bounds.width - 44 - arrowsW - hideW), height: h)
        if next != scroll.frame { scroll.frame = next; layoutItems() }
    }

    func setItems(_ next: [RowItem]) {
        // The same words again (a refresh with nothing new) keeps the row as it is, scroll and all; only what a tap
        // does is brought up to date.
        func sig(_ list: [RowItem]) -> [String] { list.map { "\($0.id)|\($0.label ?? "")|\($0.bold)|\($0.icon.map { "\($0)" } ?? "")|\($0.tint.map { "\($0)" } ?? "")|\($0.busy)" } }
        if sig(next) == sig(items) { items = next; return }
        items = next
        views.forEach { $0.removeFromSuperview() }
        views = next.enumerated().map { i, item in makeView(item, i) }
        views.forEach { scroll.addSubview($0) }
        scroll.contentOffset = .zero
        layoutItems()
    }

    private func layoutItems() {
        let room = scroll.bounds.width
        let h = scroll.bounds.height
        guard room > 0 else { return }
        let wideCount = CGFloat(max(items.filter { $0.wide }.count, 3))
        // Too narrow to read (the arrow keys take part of the row): three across, and the rest scroll.
        let wideWidth = room / wideCount < 80 ? room / 3 : room / wideCount
        var widths: [CGFloat] = []
        var fixed: CGFloat = 0
        for (i, item) in items.enumerated() {
            let margin: CGFloat = item.chip || item.busy ? 8 : 0
            let w: CGFloat
            if item.fill { w = 0 }
            else if item.wide { w = wideWidth }
            else { w = max(views[i].sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: h)).width, item.label == nil ? 44 : 0) }
            widths.append(w)
            fixed += w + margin
        }
        let fills = CGFloat(items.filter { $0.fill }.count)
        // A filling item (the AI's words, an error) shares the row with the buttons beside it and is cut short to leave
        // them room: a long error does not push Retry and the close button off the end.
        let fillW = fills > 0 ? max((room - fixed) / fills, room / 3) : 0
        var x: CGFloat = 0
        for (i, item) in items.enumerated() {
            let w = item.fill ? fillW : widths[i]
            let padded = item.chip || item.busy
            let mx: CGFloat = padded ? 4 : 0, my: CGFloat = padded ? 6 : 0
            views[i].frame = CGRect(x: x + mx, y: my, width: w, height: h - my * 2)
            x += w + mx * 2
        }
        scroll.contentSize = CGSize(width: max(x, room), height: h)
    }

    private func makeView(_ item: RowItem, _ index: Int) -> UIView {
        if item.busy {
            let v = ThinkingView(words: item.label ?? "Thinking…", theme: theme)
            v.isUserInteractionEnabled = item.onTap != nil
            v.accessibilityIdentifier = item.id
            v.addAction(UIAction { [weak self] _ in self?.tap(index) }, for: .touchUpInside)
            return v
        }
        if let icon = item.icon, item.label == nil, !item.chip {
            let b = IconButton(icon)
            b.colour = item.tint?.ink(theme) ?? UIColor(argb: theme.toolbarIcon)
            b.isUserInteractionEnabled = item.onTap != nil
            b.accessibilityLabel = item.description ?? item.id
            b.accessibilityIdentifier = item.id
            b.addAction(UIAction { [weak self] _ in self?.tap(index) }, for: .touchUpInside)
            return b
        }
        // A chip: its words, its icon, or both, on a pill, in its colour where it has one.
        let b = ChipButton()
        b.normal = item.chip ? item.tint?.wash(theme) ?? UIColor(argb: theme.functionKey) : .clear
        b.pressed = UIColor(argb: theme.keyPressed)
        b.icon = item.icon
        b.iconColour = item.tint?.ink(theme) ?? UIColor(argb: theme.toolbarIcon)
        b.label.text = item.label
        b.label.font = .systemFont(ofSize: item.wide ? 16 : 14, weight: item.bold ? .medium : .regular)
        b.label.textColor = UIColor(argb: theme.text)
        b.label.textAlignment = item.fill ? .natural : .center
        b.padX = item.wide ? 6 : 12
        b.isUserInteractionEnabled = item.onTap != nil
        b.accessibilityLabel = item.description ?? item.label
        b.accessibilityIdentifier = item.id
        b.addAction(UIAction { [weak self] _ in self?.tap(index) }, for: .touchUpInside)
        return b
    }

    private func tap(_ index: Int) {
        guard index < items.count, let action = items[index].onTap else { return }
        onTapFeedback()
        action()
    }

    private func restyle() {
        backgroundColor = systemBackdrop ? .clear : UIColor(argb: theme.background)
        isOpaque = !systemBackdrop
        scroll.backgroundColor = backgroundColor
        let ink = UIColor(argb: theme.toolbarIcon)
        settings.colour = ink
        hide.colour = ink
        arrowButtons.forEach { $0.colour = ink }
        let now = items
        items = []
        setItems(now)
    }

    /// Scrolls even when the drag starts on a chip, which a plain scroll view refuses to do over controls.
    final class RowScroll: UIScrollView {
        override func touchesShouldCancel(in view: UIView) -> Bool { true }
    }

    /// A drawn icon button: no images to ship, and it takes the theme's colour.
    final class IconButton: UIControl {
        let icon: RowIcon
        var colour = UIColor.gray { didSet { setNeedsDisplay() } }
        override var isHighlighted: Bool { didSet { setNeedsDisplay() } }

        init(_ icon: RowIcon) {
            self.icon = icon
            super.init(frame: .zero)
            backgroundColor = .clear
            isOpaque = false
            contentMode = .redraw
            isAccessibilityElement = true
            accessibilityTraits = .button
        }

        required init?(coder: NSCoder) { fatalError() }

        override func sizeThatFits(_ size: CGSize) -> CGSize { CGSize(width: 44, height: 44) }

        override func draw(_ rect: CGRect) {
            guard let ctx = UIGraphicsGetCurrentContext() else { return }
            if isHighlighted {
                ctx.setFillColor(UIColor(white: 0.5, alpha: 0.13).cgColor)
                let d = min(bounds.width, bounds.height) - 6
                ctx.fillEllipse(in: CGRect(x: bounds.midX - d / 2, y: bounds.midY - d / 2, width: d, height: d))
            }
            RowIcons.draw(icon, in: ctx, cx: bounds.midX, cy: bounds.midY, size: 20, ink: colour)
        }
    }

    /// A suggestion or a chip: a label (an icon before it where it has one), or an icon alone, on a rounded ground
    /// that darkens when pressed.
    final class ChipButton: UIControl {
        let label = UILabel()
        var normal = UIColor.clear { didSet { backgroundColor = normal } }
        var pressed = UIColor.gray
        var icon: RowIcon? { didSet { setNeedsDisplay(); setNeedsLayout() } }
        var iconColour = UIColor.gray
        var padX: CGFloat = 12
        override var isHighlighted: Bool { didSet { backgroundColor = isHighlighted ? pressed : normal } }

        override init(frame: CGRect) {
            super.init(frame: frame)
            label.lineBreakMode = .byTruncatingTail
            label.isUserInteractionEnabled = false
            addSubview(label)
            layer.cornerRadius = 16
            layer.cornerCurve = .continuous
            // A view that draws itself paints its ground into its own picture, which the corner radius alone does not
            // cut: without the mask the chip is a plain rectangle.
            layer.masksToBounds = true
            isOpaque = false
            contentMode = .redraw
            isAccessibilityElement = true
            accessibilityTraits = .button
        }

        required init?(coder: NSCoder) { fatalError() }

        private var iconRoom: CGFloat { icon == nil ? 0 : 24 }
        /// An icon with no words: the tool's chip, as wide as a thumb needs.
        private var iconOnly: Bool { icon != nil && (label.text ?? "").isEmpty }

        override func sizeThatFits(_ size: CGSize) -> CGSize {
            if iconOnly { return CGSize(width: 44, height: size.height) }
            return CGSize(width: ceil(label.sizeThatFits(size).width) + padX * 2 + iconRoom, height: size.height)
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            layer.cornerRadius = min(18, bounds.height / 2)
            label.frame = CGRect(x: padX + iconRoom, y: 0, width: max(0, bounds.width - padX * 2 - iconRoom), height: bounds.height)
        }

        override func draw(_ rect: CGRect) {
            guard let icon, let ctx = UIGraphicsGetCurrentContext() else { return }
            if iconOnly { RowIcons.draw(icon, in: ctx, cx: bounds.midX, cy: bounds.midY, size: 20, ink: iconColour) }
            else { RowIcons.draw(icon, in: ctx, cx: padX + 9, cy: bounds.midY, size: 18, ink: iconColour) }
        }
    }
}
