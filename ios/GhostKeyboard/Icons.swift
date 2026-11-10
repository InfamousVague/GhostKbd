import UIKit

/// The small drawn icons the row's buttons and the keys use (PluginRow.kt's RowIcon).
enum RowIcon {
    case settings, hide, gif, back, forward, search, key, mic, delete, shift, enter, caretLeft, caretRight, caretUp, caretDown, globe
    // The tools' and states' own (RowIconsMore.kt), less the ones only emoji, GIFs and Ghost.md use, which iOS has not.
    case clipboard, sparkle, wand, feather, incognito, undo, redo, bold, italic, strike, code, codeBlock, link, heading, list, task, quote, trash, close, warning, retry, check
}

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
        // The row's tool and status icons, on Phosphor's 256 grid: its duotone icons (@phosphor-icons/core 2.1.1, MIT), the
        // regular lines with the duotone weight's own 20% layer as the wash. The same drawings as RowIconsMore.kt.
        case .clipboard: return PackIcon(false, ["M168,152a8,8,0,0,1-8,8H96a8,8,0,0,1,0-16h64A8,8,0,0,1,168,152Zm-8-40H96a8,8,0,0,0,0,16h64a8,8,0,0,0,0-16Zm56-64V216a16,16,0,0,1-16,16H56a16,16,0,0,1-16-16V48A16,16,0,0,1,56,32H92.26a47.92,47.92,0,0,1,71.48,0H200A16,16,0,0,1,216,48ZM96,64h64a32,32,0,0,0-64,0ZM200,48H173.25A47.93,47.93,0,0,1,176,64v8a8,8,0,0,1-8,8H88a8,8,0,0,1-8-8V64a47.93,47.93,0,0,1,2.75-16H56V216H200Z"], ["M208,48V216a8,8,0,0,1-8,8H56a8,8,0,0,1-8-8V48a8,8,0,0,1,8-8H96a39.83,39.83,0,0,0-8,24v8h80V64a39.83,39.83,0,0,0-8-24h40A8,8,0,0,1,208,48Z"], 0.2, box: 256)  // clipboard-text
        case .sparkle: return PackIcon(false, ["M197.58,129.06,146,110l-19-51.62a15.92,15.92,0,0,0-29.88,0L78,110l-51.62,19a15.92,15.92,0,0,0,0,29.88L78,178l19,51.62a15.92,15.92,0,0,0,29.88,0L146,178l51.62-19a15.92,15.92,0,0,0,0-29.88ZM137,164.22a8,8,0,0,0-4.74,4.74L112,223.85,91.78,169A8,8,0,0,0,87,164.22L32.15,144,87,123.78A8,8,0,0,0,91.78,119L112,64.15,132.22,119a8,8,0,0,0,4.74,4.74L191.85,144ZM144,40a8,8,0,0,1,8-8h16V16a8,8,0,0,1,16,0V32h16a8,8,0,0,1,0,16H184V64a8,8,0,0,1-16,0V48H152A8,8,0,0,1,144,40ZM248,88a8,8,0,0,1-8,8h-8v8a8,8,0,0,1-16,0V96h-8a8,8,0,0,1,0-16h8V72a8,8,0,0,1,16,0v8h8A8,8,0,0,1,248,88Z"], ["M194.82,151.43l-55.09,20.3-20.3,55.09a7.92,7.92,0,0,1-14.86,0l-20.3-55.09-55.09-20.3a7.92,7.92,0,0,1,0-14.86l55.09-20.3,20.3-55.09a7.92,7.92,0,0,1,14.86,0l20.3,55.09,55.09,20.3A7.92,7.92,0,0,1,194.82,151.43Z"], 0.2, box: 256)  // sparkle
        case .wand: return PackIcon(false, ["M248,152a8,8,0,0,1-8,8H224v16a8,8,0,0,1-16,0V160H192a8,8,0,0,1,0-16h16V128a8,8,0,0,1,16,0v16h16A8,8,0,0,1,248,152ZM56,72H72V88a8,8,0,0,0,16,0V72h16a8,8,0,0,0,0-16H88V40a8,8,0,0,0-16,0V56H56a8,8,0,0,0,0,16ZM184,192h-8v-8a8,8,0,0,0-16,0v8h-8a8,8,0,0,0,0,16h8v8a8,8,0,0,0,16,0v-8h8a8,8,0,0,0,0-16ZM219.31,80,80,219.31a16,16,0,0,1-22.62,0L36.68,198.63a16,16,0,0,1,0-22.63L176,36.69a16,16,0,0,1,22.63,0l20.68,20.68A16,16,0,0,1,219.31,80Zm-54.63,32L144,91.31l-96,96L68.68,208ZM208,68.69,187.31,48l-32,32L176,100.69Z"], ["M176,112,74.34,213.66a8,8,0,0,1-11.31,0L42.34,193a8,8,0,0,1,0-11.31L144,80Z"], 0.2, box: 256)  // magic-wand
        case .feather: return PackIcon(false, ["M221.28,34.75a64,64,0,0,0-90.49,0L60.69,104A15.9,15.9,0,0,0,56,115.31v73.38L26.34,218.34a8,8,0,0,0,11.32,11.32L67.32,200H140.7A15.92,15.92,0,0,0,152,195.32l0,0,69.23-70A64,64,0,0,0,221.28,34.75ZM142.07,46.06A48,48,0,0,1,211.79,112H155.33l34.35-34.34a8,8,0,0,0-11.32-11.32L120,124.69V67.87ZM72,115.35l32-31.67v57l-32,32ZM140.7,184H83.32l56-56h56.74Z"], ["M215.8,119.6l-69.26,70.06a8,8,0,0,1-5.65,2.34H64.2V115.31a8,8,0,0,1,2.34-5.65L112.2,64.52V144l24-24Z"], 0.2, box: 256)  // feather
        case .incognito: return PackIcon(false, ["M53.92,34.62A8,8,0,1,0,42.08,45.38L61.32,66.55C25,88.84,9.38,123.2,8.69,124.76a8,8,0,0,0,0,6.5c.35.79,8.82,19.57,27.65,38.4C61.43,194.74,93.12,208,128,208a127.11,127.11,0,0,0,52.07-10.83l22,24.21a8,8,0,1,0,11.84-10.76Zm47.33,75.84,41.67,45.85a32,32,0,0,1-41.67-45.85ZM128,192c-30.78,0-57.67-11.19-79.93-33.25A133.16,133.16,0,0,1,25,128c4.69-8.79,19.66-33.39,47.35-49.38l18,19.75a48,48,0,0,0,63.66,70l14.73,16.2A112,112,0,0,1,128,192Zm6-95.43a8,8,0,0,1,3-15.72,48.16,48.16,0,0,1,38.77,42.64,8,8,0,0,1-7.22,8.71,6.39,6.39,0,0,1-.75,0,8,8,0,0,1-8-7.26A32.09,32.09,0,0,0,134,96.57Zm113.28,34.69c-.42.94-10.55,23.37-33.36,43.8a8,8,0,1,1-10.67-11.92A132.77,132.77,0,0,0,231.05,128a133.15,133.15,0,0,0-23.12-30.77C185.67,75.19,158.78,64,128,64a118.37,118.37,0,0,0-19.36,1.57A8,8,0,1,1,106,49.79,134,134,0,0,1,128,48c34.88,0,66.57,13.26,91.66,38.35,18.83,18.83,27.3,37.62,27.65,38.41A8,8,0,0,1,247.31,131.26Z"], ["M128,56C48,56,16,128,16,128s32,72,112,72,112-72,112-72S208,56,128,56Zm0,112a40,40,0,1,1,40-40A40,40,0,0,1,128,168Z"], 0.2, box: 256)  // eye-slash
        case .undo: return PackIcon(false, ["M224,128a96,96,0,0,1-94.71,96H128A95.38,95.38,0,0,1,62.1,197.8a8,8,0,0,1,11-11.63A80,80,0,1,0,71.43,71.39a3.07,3.07,0,0,1-.26.25L44.59,96H72a8,8,0,0,1,0,16H24a8,8,0,0,1-8-8V56a8,8,0,0,1,16,0V85.8L60.25,60A96,96,0,0,1,224,128Z"], ["M216,128a88,88,0,1,1-88-88A88,88,0,0,1,216,128Z"], 0.2, box: 256)  // arrow-counter-clockwise
        case .redo: return PackIcon(false, ["M240,56v48a8,8,0,0,1-8,8H184a8,8,0,0,1,0-16H211.4L184.81,71.64l-.25-.24a80,80,0,1,0-1.67,114.78,8,8,0,0,1,11,11.63A95.44,95.44,0,0,1,128,224h-1.32A96,96,0,1,1,195.75,60L224,85.8V56a8,8,0,1,1,16,0Z"], ["M216,128a88,88,0,1,1-88-88A88,88,0,0,1,216,128Z"], 0.2, box: 256)  // arrow-clockwise
        case .bold: return PackIcon(false, ["M178.48,115.7A44,44,0,0,0,148,40H80a8,8,0,0,0-8,8V200a8,8,0,0,0,8,8h80a48,48,0,0,0,18.48-92.3ZM88,56h60a28,28,0,0,1,0,56H88Zm72,136H88V128h72a32,32,0,0,1,0,64Z"], ["M200,160a40,40,0,0,1-40,40H80V48h68a36,36,0,0,1,0,72h12A40,40,0,0,1,200,160Z"], 0.2, box: 256)  // text-b
        case .italic: return PackIcon(false, ["M200,56a8,8,0,0,1-8,8H157.77L115.1,192H144a8,8,0,0,1,0,16H64a8,8,0,0,1,0-16H98.23L140.9,64H112a8,8,0,0,1,0-16h80A8,8,0,0,1,200,56Z"], ["M192,56,144,200H64L112,56Z"], 0.2, box: 256)  // text-italic
        case .strike: return PackIcon(false, ["M224,128a8,8,0,0,1-8,8H175.93c9.19,7.11,16.07,17.2,16.07,32,0,13.34-7,25.7-19.75,34.79C160.33,211.31,144.61,216,128,216s-32.33-4.69-44.25-13.21C71,193.7,64,181.34,64,168a8,8,0,0,1,16,0c0,17.35,22,32,48,32s48-14.65,48-32c0-14.85-10.54-23.58-38.77-32H40a8,8,0,0,1,0-16H216A8,8,0,0,1,224,128ZM76.33,104a8,8,0,0,0,7.61-10.49A17.3,17.3,0,0,1,83.11,88c0-18.24,19.3-32,44.89-32,18.84,0,34.16,7.42,41,19.85a8,8,0,0,0,14-7.7C173.33,50.52,152.77,40,128,40,93.29,40,67.11,60.63,67.11,88a33.73,33.73,0,0,0,1.62,10.49A8,8,0,0,0,76.33,104Z"], ["M176,72l-55.31,51.05c-24-6.7-45.58-14.26-45.58-35,0-22.09,22-40,52.89-40C151.2,48,168.37,57.64,176,72Zm-55.31,51.05L72,168c0,22.09,25.07,40,56,40s56-17.91,56-40C184,138.43,150.52,131.4,120.69,123.05Z"], 0.2, box: 256)  // text-strikethrough
        case .code: return PackIcon(false, ["M69.12,94.15,28.5,128l40.62,33.85a8,8,0,1,1-10.24,12.29l-48-40a8,8,0,0,1,0-12.29l48-40a8,8,0,0,1,10.24,12.3Zm176,27.7-48-40a8,8,0,1,0-10.24,12.3L227.5,128l-40.62,33.85a8,8,0,1,0,10.24,12.29l48-40a8,8,0,0,0,0-12.29ZM162.73,32.48a8,8,0,0,0-10.25,4.79l-64,176a8,8,0,0,0,4.79,10.26A8.14,8.14,0,0,0,96,224a8,8,0,0,0,7.52-5.27l64-176A8,8,0,0,0,162.73,32.48Z"], ["M240,128l-48,40H64L16,128,64,88H192Z"], 0.2, box: 256)  // code
        case .codeBlock: return PackIcon(false, ["M58.34,101.66l-32-32a8,8,0,0,1,0-11.32l32-32A8,8,0,0,1,69.66,37.66L43.31,64,69.66,90.34a8,8,0,0,1-11.32,11.32Zm40,0a8,8,0,0,0,11.32,0l32-32a8,8,0,0,0,0-11.32l-32-32A8,8,0,0,0,98.34,37.66L124.69,64,98.34,90.34A8,8,0,0,0,98.34,101.66ZM200,40H176a8,8,0,0,0,0,16h24V200H56V136a8,8,0,0,0-16,0v64a16,16,0,0,0,16,16H200a16,16,0,0,0,16-16V56A16,16,0,0,0,200,40Z"], ["M152,32V96a16,16,0,0,1-16,16H32A16,16,0,0,1,16,96V32A16,16,0,0,1,32,16H136A16,16,0,0,1,152,32Z"], 0.2, box: 256)  // code-block
        case .link: return PackIcon(false, ["M240,88.23a54.43,54.43,0,0,1-16,37L189.25,160a54.27,54.27,0,0,1-38.63,16h-.05A54.63,54.63,0,0,1,96,119.84a8,8,0,0,1,16,.45A38.62,38.62,0,0,0,150.58,160h0a38.39,38.39,0,0,0,27.31-11.31l34.75-34.75a38.63,38.63,0,0,0-54.63-54.63l-11,11A8,8,0,0,1,135.7,59l11-11A54.65,54.65,0,0,1,224,48,54.86,54.86,0,0,1,240,88.23ZM109,185.66l-11,11A38.41,38.41,0,0,1,70.6,208h0a38.63,38.63,0,0,1-27.29-65.94L78,107.31A38.63,38.63,0,0,1,144,135.71a8,8,0,0,0,7.78,8.22H152a8,8,0,0,0,8-7.78A54.86,54.86,0,0,0,144,96a54.65,54.65,0,0,0-77.27,0L32,130.75A54.62,54.62,0,0,0,70.56,224h0a54.28,54.28,0,0,0,38.64-16l11-11A8,8,0,0,0,109,185.66Z"], ["M218.34,119.6,183.6,154.34a46.58,46.58,0,0,1-44.31,12.26c-.31.34-.62.67-.95,1L103.6,202.34A46.63,46.63,0,1,1,37.66,136.4L72.4,101.66A46.6,46.6,0,0,1,116.71,89.4c.31-.34.62-.67,1-1L152.4,53.66a46.63,46.63,0,0,1,65.94,65.94Z"], 0.2, box: 256)  // link
        case .heading: return PackIcon(false, ["M208,56V200a8,8,0,0,1-16,0V136H64v64a8,8,0,0,1-16,0V56a8,8,0,0,1,16,0v64H192V56a8,8,0,0,1,16,0Z"], ["M200,56V200H56V56Z"], 0.2, box: 256)  // text-h
        case .list: return PackIcon(false, ["M80,64a8,8,0,0,1,8-8H216a8,8,0,0,1,0,16H88A8,8,0,0,1,80,64Zm136,56H88a8,8,0,1,0,0,16H216a8,8,0,0,0,0-16Zm0,64H88a8,8,0,1,0,0,16H216a8,8,0,0,0,0-16ZM44,52A12,12,0,1,0,56,64,12,12,0,0,0,44,52Zm0,64a12,12,0,1,0,12,12A12,12,0,0,0,44,116Zm0,64a12,12,0,1,0,12,12A12,12,0,0,0,44,180Z"], ["M216,64V192H88V64Z"], 0.2, box: 256)  // list-bullets
        case .task: return PackIcon(false, ["M173.66,98.34a8,8,0,0,1,0,11.32l-56,56a8,8,0,0,1-11.32,0l-24-24a8,8,0,0,1,11.32-11.32L112,148.69l50.34-50.35A8,8,0,0,1,173.66,98.34ZM224,48V208a16,16,0,0,1-16,16H48a16,16,0,0,1-16-16V48A16,16,0,0,1,48,32H208A16,16,0,0,1,224,48ZM208,208V48H48V208H208Z"], ["M216,48V208a8,8,0,0,1-8,8H48a8,8,0,0,1-8-8V48a8,8,0,0,1,8-8H208A8,8,0,0,1,216,48Z"], 0.2, box: 256)  // check-square
        case .quote: return PackIcon(false, ["M100,56H40A16,16,0,0,0,24,72v64a16,16,0,0,0,16,16h60v8a32,32,0,0,1-32,32,8,8,0,0,0,0,16,48.05,48.05,0,0,0,48-48V72A16,16,0,0,0,100,56Zm0,80H40V72h60ZM216,56H156a16,16,0,0,0-16,16v64a16,16,0,0,0,16,16h60v8a32,32,0,0,1-32,32,8,8,0,0,0,0,16,48.05,48.05,0,0,0,48-48V72A16,16,0,0,0,216,56Zm0,80H156V72h60Z"], ["M108,72v72H40a8,8,0,0,1-8-8V72a8,8,0,0,1,8-8h60A8,8,0,0,1,108,72Zm108-8H156a8,8,0,0,0-8,8v64a8,8,0,0,0,8,8h68V72A8,8,0,0,0,216,64Z"], 0.2, box: 256)  // quotes
        case .trash: return PackIcon(false, ["M216,48H176V40a24,24,0,0,0-24-24H104A24,24,0,0,0,80,40v8H40a8,8,0,0,0,0,16h8V208a16,16,0,0,0,16,16H192a16,16,0,0,0,16-16V64h8a8,8,0,0,0,0-16ZM96,40a8,8,0,0,1,8-8h48a8,8,0,0,1,8,8v8H96Zm96,168H64V64H192ZM112,104v64a8,8,0,0,1-16,0V104a8,8,0,0,1,16,0Zm48,0v64a8,8,0,0,1-16,0V104a8,8,0,0,1,16,0Z"], ["M200,56V208a8,8,0,0,1-8,8H64a8,8,0,0,1-8-8V56Z"], 0.2, box: 256)  // trash
        case .close: return PackIcon(false, ["M205.66,194.34a8,8,0,0,1-11.32,11.32L128,139.31,61.66,205.66a8,8,0,0,1-11.32-11.32L116.69,128,50.34,61.66A8,8,0,0,1,61.66,50.34L128,116.69l66.34-66.35a8,8,0,0,1,11.32,11.32L139.31,128Z"], [], 0.2, box: 256)  // x, its square wash left out
        case .warning: return PackIcon(false, ["M128,24A104,104,0,1,0,232,128,104.11,104.11,0,0,0,128,24Zm0,192a88,88,0,1,1,88-88A88.1,88.1,0,0,1,128,216Zm-8-80V80a8,8,0,0,1,16,0v56a8,8,0,0,1-16,0Zm20,36a12,12,0,1,1-12-12A12,12,0,0,1,140,172Z"], ["M224,128a96,96,0,1,1-96-96A96,96,0,0,1,224,128Z"], 0.2, box: 256)  // warning-circle
        case .retry: return PackIcon(false, ["M224,48V96a8,8,0,0,1-8,8H168a8,8,0,0,1,0-16h28.69L182.06,73.37a79.56,79.56,0,0,0-56.13-23.43h-.45A79.52,79.52,0,0,0,69.59,72.71,8,8,0,0,1,58.41,61.27a96,96,0,0,1,135,.79L208,76.69V48a8,8,0,0,1,16,0ZM186.41,183.29a80,80,0,0,1-112.47-.66L59.31,168H88a8,8,0,0,0,0-16H40a8,8,0,0,0-8,8v48a8,8,0,0,0,16,0V179.31l14.63,14.63A95.43,95.43,0,0,0,130,222.06h.53a95.36,95.36,0,0,0,67.07-27.33,8,8,0,0,0-11.18-11.44Z"], ["M216,128a88,88,0,1,1-88-88A88,88,0,0,1,216,128Z"], 0.2, box: 256)  // arrows-clockwise
        case .check: return PackIcon(false, ["M205.66,85.66l-96,96a8,8,0,0,1-11.32,0l-40-40a8,8,0,0,1,11.32-11.32L104,164.69l90.34-90.35a8,8,0,0,1,11.32,11.32Z"], [], 0.2, box: 256)  // check, its square wash left out
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
