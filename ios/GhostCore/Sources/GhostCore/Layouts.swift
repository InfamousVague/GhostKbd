import CoreGraphics
import Foundation

/// What a key does when it is pressed. `globe` is iOS's next-keyboard key (not on Android).
public enum KeyType: String, Hashable, CaseIterable {
    case char, shift, delete, space, enter, mode, mic, globe
}

/// Gboard's three pages (letters, ?123, =\<) and the developer page behind the { } key.
public enum Page: String, Hashable, CaseIterable {
    case letters, symbols, more, dev
}

/// One key. `text` is what it types (letters are typed in upper case while shift is on), `label` what it shows,
/// `width` its share of the row in key units, `hint` the small character in its corner, `alts` the long-press
/// choices (the hint, when there is one, comes first), `page` the page a mode key goes to. A `¦` in `text` or an
/// alternative is where the cursor is left: `(¦)` types a pair and puts the cursor between. `pill` is whether a mode
/// key is drawn as Gboard's pill (?123) or as a bare label (the { } key, where Gboard's comma was).
public struct Key: Hashable {
    public var type: KeyType
    public var text: String
    public var label: String
    public var width: CGFloat
    public var hint: String?
    public var alts: [String]
    public var page: Page?
    public var pill: Bool

    /// The mark in `text` and `alts` where the cursor is left.
    public static let caretMark: Character = "¦"

    public init(type: KeyType, text: String = "", label: String? = nil, width: CGFloat = 1, hint: String? = nil,
                alts: [String] = [], page: Page? = nil, pill: Bool = true) {
        self.type = type
        self.text = text
        self.label = label ?? text
        self.width = width
        self.hint = hint
        self.alts = alts
        self.page = page
        self.pill = pill
    }

    /// Drawn in the darker "function" colour, as Gboard draws shift, delete, ?123 and the like (and iOS its globe).
    public var functional: Bool { type == .shift || type == .delete || type == .mode || type == .globe }

    /// This key at another width.
    public func with(width: CGFloat) -> Key { var k = self; k.width = width; return k }
}

/// A row of keys, `inset` key units of space before and after it (Gboard's middle letter row sits half a key in).
public struct KeyRow: Hashable {
    public var keys: [Key]
    public var inset: CGFloat

    public init(_ keys: [Key], inset: CGFloat = 0) {
        self.keys = keys
        self.inset = inset
    }

    public var units: CGFloat { keys.reduce(0) { $0 + $1.width } + inset * 2 }
}

public enum Layouts {
    /// The dictation plugin's mic key, beside the space bar, which gives it a key's width.
    public static func withMic(_ rows: [KeyRow]) -> [KeyRow] {
        guard let last = rows.last else { return rows }
        var keys: [Key] = []
        for k in last.keys {
            if k.type == .space { keys.append(Key(type: .mic)); keys.append(k.with(width: k.width - 1)) } else { keys.append(k) }
        }
        return rows.dropLast() + [KeyRow(keys, inset: last.inset)]
    }

    /// iOS only: the next-keyboard (globe) key, right after the first key of the last row; the space bar gives it a
    /// key's width.
    public static func withGlobe(_ rows: [KeyRow]) -> [KeyRow] {
        guard let last = rows.last, !last.keys.isEmpty else { return rows }
        var keys: [Key] = []
        for (i, k) in last.keys.enumerated() {
            keys.append(k.type == .space ? k.with(width: k.width - 1) : k)
            if i == 0 { keys.append(Key(type: .globe)) }
        }
        return rows.dropLast() + [KeyRow(keys, inset: last.inset)]
    }

    private static let accents: [String: [String]] = [
        "a": ["à", "á", "â", "ä", "æ", "ã", "å", "ā"],
        "c": ["ç", "ć", "č"],
        "e": ["è", "é", "ê", "ë", "ē", "ę", "ė"],
        "i": ["î", "ï", "í", "ī", "į", "ì"],
        "l": ["ł"],
        "n": ["ñ", "ń"],
        "o": ["ô", "ö", "ò", "ó", "œ", "ø", "ō", "õ"],
        "s": ["ß", "ś", "š"],
        "u": ["û", "ü", "ù", "ú", "ū"],
        "y": ["ÿ"],
        "z": ["ž", "ź", "ż"],
    ]

    /// Long-press choices on the symbol pages, with the pairs a programmer reaches for most.
    private static let symbolAlts: [String: [String]] = [
        "1": ["¹", "½", "⅓", "¼", "⅛"],
        "2": ["²", "⅔"],
        "3": ["³", "¾", "⅜"],
        "4": ["⁴"],
        "0": ["ⁿ", "∅"],
        "(": ["<", "[", "{"],
        ")": [">", "]", "}"],
        "-": ["_", "–", "—", "·"],
        "+": ["±"],
        "/": ["\\"],
        "'": ["`", "‘", "’", "‚", "‹", "›"],
        "\"": ["“", "”", "„", "«", "»"],
        "!": ["¡"],
        "?": ["¿", "‽"],
        "$": ["¢", "£", "€", "¥", "₹", "₱"],
        "*": ["†", "‡", "★"],
        "=": ["≠", "≈", "∞"],
        "%": ["‰", "℅"],
        "#": ["№"],
        "&": ["§"],
        "<": ["≤", "«", "‹"],
        ">": ["≥", "»", "›"],
    ]

    /// The period's long-press punctuation panel, as Gboard has it.
    private static let periodAlts = ["?", "!", ",", "'", "\"", ":", ";", "-", "/", "@", "#", "&", "(", ")", "%", "+"]

    private static let digits = "1234567890".map { String($0) }

    private static func letter(_ c: String, _ hint: String?) -> Key {
        Key(type: .char, text: c, hint: hint, alts: (hint.map { [$0] } ?? []) + (accents[c] ?? []))
    }

    private static func sym(_ c: String) -> Key { Key(type: .char, text: c, alts: symbolAlts[c] ?? []) }

    private static let shift = Key(type: .shift, width: 1.5)
    private static let delete = Key(type: .delete, width: 1.5)

    private static let comma = Key(type: .char, text: ",")

    /// Where Gboard has its comma: the developer page's key (long-press: settings).
    private static let devKey = Key(type: .mode, label: "{ }", page: .dev, pill: false)

    /// In { }'s place on the developer page: a comma, which types one and goes back to the letters.
    private static let devComma = Key(type: .char, text: ",", page: .letters, pill: false)

    private static func bottomRow(_ modeLabel: String, _ modePage: Page, second: Key? = nil) -> KeyRow {
        KeyRow([
            // Gboard's, measured: ?123 and enter a key and a half, space five keys.
            Key(type: .mode, label: modeLabel, width: 1.5, page: modePage),
            second ?? comma,
            Key(type: .space, text: " ", width: 5),
            Key(type: .char, text: ".", alts: periodAlts),
            Key(type: .enter, width: 1.5),
        ])
    }

    private static func numberRow() -> KeyRow { KeyRow(digits.map { sym($0) }) }

    /// QWERTY. With `numberRow` the digits get a row of their own and leave the top row's corners.
    public static func letters(numberRow: Bool) -> [KeyRow] {
        let top = "qwertyuiop".enumerated().map { i, c in letter(String(c), numberRow ? nil : digits[i]) }
        // Gboard's long-press symbol hints: the ?123 page's second and third rows, in order.
        let middle = zip("asdfghjkl", "@#$_&-+()").map { c, h in letter(String(c), String(h)) }
        let bottom = zip("zxcvbnm", "*\"':;!?").map { c, h in letter(String(c), String(h)) }
        var rows: [KeyRow] = []
        if numberRow { rows.append(self.numberRow()) }
        rows.append(KeyRow(top))
        rows.append(KeyRow(middle, inset: 0.5))
        rows.append(KeyRow([shift] + bottom + [delete]))
        rows.append(bottomRow("?123", .symbols, second: devKey))
        return rows
    }

    /// A key that types `text` (with its `¦` caret mark), showing `label`.
    private static func snip(_ label: String, _ text: String, _ alts: String...) -> Key {
        Key(type: .char, text: text, label: label, alts: alts)
    }

    /// The developer page: what code is made of, a tap away. Top row, pairs that leave the cursor inside (long-press
    /// for one half alone) and the shapes typed most; middle, operators, each long-press holding its compounds; then
    /// tab and the punctuation code leans on; the comma lives here now.
    public static func dev() -> [KeyRow] {
        [
            KeyRow([
                snip("( )", "(¦)", "(", ")"),
                snip("[ ]", "[¦]", "[", "]"),
                snip("{ }", "{¦}", "{", "}", "{\n  ¦\n}"),
                snip("< >", "<¦>", "<", ">", "</¦>"),
                snip("\" \"", "\"¦\"", "\""),
                snip("' '", "'¦'", "'"),
                snip("` `", "`¦`", "`", "```\n¦\n```"),
                snip("${ }", "${¦}", "{{¦}}", "%s", "{}"),
                snip("=>", " => ", "->", "<-", "=>", "::"),
                snip("ƒ", "function ¦() {}", "() => {¦}", "const ¦ = () => {}", "def ¦():", "fn ¦() {}", "func ¦() {}"),
            ]),
            KeyRow([
                snip("=", "=", "==", "===", "!=", "!==", "<=", ">="),
                snip("+", "+", "++", "+="),
                snip("-", "-", "--", "-=", "->"),
                snip("*", "*", "**", "*=", "/*¦*/"),
                snip("/", "/", "//", "/=", "\\"),
                snip("|", "|", "||", "|=", "|>"),
                snip("&", "&", "&&", "&="),
                snip("!", "!", "!=", "!!"),
                snip("?", "?", "??", "?.", "??="),
                snip("%", "%", "%=", "^", "^="),
            ]),
            KeyRow(
                [Key(type: .char, text: "\t", label: "⇥", width: 1.5)] + [
                    snip(";", ";"), snip(":", ":", "::", ":="), snip("_", "_", "__"), snip("$", "$", "$("),
                    snip("#", "#", "#!/"), snip("@", "@"), snip("~", "~", "`", "^"),
                ] + [delete]
            ),
            bottomRow("ABC", .letters, second: devComma),
        ]
    }

    /// ?123: digits, the common symbols, and =\< for the rest.
    public static func symbols() -> [KeyRow] {
        [
            numberRow(),
            KeyRow("@#$_&-+()/".map { sym(String($0)) }),
            KeyRow([Key(type: .mode, label: "=\\<", width: 1.5, page: .more)] + "*\"':;!?".map { sym(String($0)) } + [delete]),
            bottomRow("ABC", .letters),
        ]
    }

    /// =\<: the rarer symbols.
    public static func more() -> [KeyRow] {
        [
            KeyRow("~`|•√π÷×§∆".map { sym(String($0)) }),
            KeyRow("£¥€¢^°={}\\".map { sym(String($0)) }),
            KeyRow([Key(type: .mode, label: "?123", width: 1.5, page: .symbols)] + "%©®™✓[]".map { sym(String($0)) } + [delete]),
            bottomRow("ABC", .letters),
        ]
    }

    /// A page's rows (letters take the number-row setting).
    public static func rows(for page: Page, numberRow: Bool) -> [KeyRow] {
        switch page {
        case .letters: return letters(numberRow: numberRow)
        case .symbols: return symbols()
        case .more: return more()
        case .dev: return dev()
        }
    }
}
