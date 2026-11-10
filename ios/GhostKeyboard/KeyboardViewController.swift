import UIKit

/// The keyboard: iOS's input view controller drawing `KeyboardView`, configured from `KeyboardPrefs`. The port of
/// Android's DevKeyboardService; the row above the keys is in KeyboardRow.swift, the AI in KeyboardAI.swift.
final class KeyboardViewController: UIInputViewController, KeyboardListener {

    let store = LayeredStore(group: GhostShared.groupDefaults)
    lazy var prefs = KeyboardPrefs(store: store, secrets: GhostShared.secrets())
    let keyboard = KeyboardView()
    let row = PluginRowView()
    /// The line along the top of the keys that runs while the AI works (RowStyle.swift).
    let working = WorkingLine()
    lazy var editor = TextEditor(proxy: { [unowned self] in self.textDocumentProxy })
    lazy var autocorrect: Autocorrect = {
        let dir = Bundle.main.resourceURL!.appendingPathComponent("dict", isDirectory: true)
        return Autocorrect(dictDirectory: dir)
    }()
    lazy var adaptiveModel = AdaptiveKeys(store: store)
    let feedback = Feedback()
    private var heightConstraint: NSLayoutConstraint?

    var lastSpaceAt: TimeInterval = 0

    /// The last autocorrection, while backspace can still undo it: the word as typed, what replaced it, the separator.
    var lastCorrection: (typed: String, fixed: String, separator: String)?

    /// Swipe typing's last word, while its alternatives are on offer: the space put before it, it, and the others.
    final class SwipeState {
        let lead: String; var word: String; let choices: [String]
        init(lead: String, word: String, choices: [String]) { self.lead = lead; self.word = word; self.choices = choices }
    }
    var lastSwipe: SwipeState?

    /// The terminal keys' sticky modifiers: on until the next key, which they are sent with.
    var ctrlOn = false
    var altOn = false

    /// The row showing the clipboard history, or Rewrite's choices, instead of its usual things.
    var showingClips = false
    var showingRewrite = false

    /// Adaptive keys: the touches of the word being typed, in order (nil where a letter came another way), and the
    /// last one backspace took away, with when.
    private var wordTouches: [KeyboardView.Typed?] = []
    private var takenTouch: (KeyboardView.Typed, TimeInterval)?

    /// Ghostwriter's request or a rewrite in flight, or its answer waiting to be placed (KeyboardAI.swift).
    var aiJob: Ghostwriter.Job?
    /// Ghostwriter typing an answer out: the text and how far it has got.
    final class Typing { let text: [Character]; var at = 0; init(_ text: String) { self.text = Array(text) } }
    var typing: Typing?
    var typeTimer: Timer?
    /// The AI's latest answers, kept out of Mockingbird's samples: they are not how the person writes.
    var aiAnswers: [String] = []
    /// Mockingbird: the field's text as last read, kept as a sample once it is sent or left.
    var draft = ""
    /// Mockingbird cleans slips out of its samples with the keyboard's own speller: no second copy of the dictionaries.
    lazy var mockingbird: Mockingbird = {
        let m = Mockingbird(directory: GhostShared.filesDirectory())
        m.speller = autocorrect
        return m
    }()
    lazy var clips = ClipHistory(store: store)
    var ghostwriterBox: Ghostwriter?
    /// The rewrite in flight was made from the text around the cursor, not from a selection.
    var aiWholeField = false
    /// The pasteboard's change count when the keyboard last looked: a different one means something new was copied.
    var seenPasteboard = UIPasteboard.general.changeCount

    /// The incognito plugin's switch.
    var incognitoOn = false
    var incognito: Bool { Plugins.isOn(prefs, Plugins.incognito) && incognitoOn }

    var rowRefresh: DispatchWorkItem?

    // --- lifecycle ----------------------------------------------------------------------------------------------------

    override func loadView() {
        let root = GhostInputView(frame: CGRect(x: 0, y: 0, width: 390, height: 270), inputViewStyle: .keyboard)
        root.allowsSelfSizing = true
        view = root
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        memoryMark("start")
        prefs.addNewDefaults()
        keyboard.listener = self
        keyboard.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(keyboard)
        keyboard.addSubview(row)
        keyboard.addSubview(working)
        NSLayoutConstraint.activate([
            keyboard.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            keyboard.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            keyboard.topAnchor.constraint(equalTo: view.topAnchor),
            keyboard.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        let h = view.heightAnchor.constraint(equalToConstant: keyboard.wantedHeight)
        h.priority = UILayoutPriority(999)
        h.isActive = true
        heightConstraint = h
        row.onSettings = { [weak self] in self?.onOpenSettings() }
        row.onArrow = { [weak self] name in self?.arrow(name) }
        row.onHide = { [weak self] in self?.onHideKeyboard() }
        row.onTapFeedback = { [weak self] in self?.keyFeedback(.mode) }
        autocorrect.onReady = { [weak self] in self?.memoryMark("dictionary"); self?.scheduleRow() }
        // The app changed a setting while the keyboard is up: it shows on the next frame.
        let me = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), me, { _, observer, _, _, _ in
            guard let observer else { return }
            let controller = Unmanaged<KeyboardViewController>.fromOpaque(observer).takeUnretainedValue()
            DispatchQueue.main.async { controller.applySettings() }
        }, GhostShared.settingsChanged as CFString, nil, .deliverImmediately)
        applySettings()
        memoryMark("loaded")
    }

    deinit {
        CFNotificationCenterRemoveEveryObserver(CFNotificationCenterGetDarwinNotifyCenter(), Unmanaged.passUnretained(self).toOpaque())
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // The app's Settings shows whether the keyboard has run, and whether it had Full Access when it did.
        if hasFullAccess, let group = store.group {
            group.set(Date().timeIntervalSince1970, forKey: GhostShared.keyboardSeen)
            group.set(true, forKey: GhostShared.keyboardFullAccess)
        }
        store.local.set(hasFullAccess, forKey: GhostShared.keyboardFullAccess)
        adaptiveModel.reload()
        startField()
        memoryMark("appear")
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        finishTyping()
        flushDraft()
        endWord()
        lastSwipe = nil
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        row.frame = CGRect(x: 0, y: 0, width: keyboard.bounds.width, height: keyboard.rowBottom)
        // Along the row's lower edge, where the keys begin.
        working.frame = CGRect(x: 0, y: keyboard.rowBottom - WorkingLine.height, width: keyboard.bounds.width, height: WorkingLine.height)
        updateHeight()
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        applySettings()
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        // Every language but the first goes: one dictionary is a few megabytes, and iOS ends a keyboard that grows.
        if let first = prefs.languages.first, autocorrect.languages.count > 1 { autocorrect.setLanguages([first]) }
    }

    /// Debug builds note the keyboard's memory at each stage of starting, for the budget in docs/IOS.md.
    func memoryMark(_ stage: String) {
        #if DEBUG
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let ok = withUnsafeMutablePointer(to: &info) { $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) } }
        guard ok == KERN_SUCCESS else { return }
        let line = "\(stage)=\(info.phys_footprint / 1_048_576)MB peak=\(info.ledger_phys_footprint_peak / 1_048_576)MB"
        let had = store.local.string(forKey: "debug_memory") ?? ""
        store.local.set(stage == "start" ? line : had + "; " + line, forKey: "debug_memory")
        store.group?.set(store.local.string(forKey: "debug_memory"), forKey: "debug_memory")
        #endif
    }

    private func updateHeight() {
        let want = keyboard.wantedHeight
        if heightConstraint?.constant != want { heightConstraint?.constant = want }
    }

    /// A field is started (or the keyboard is shown again): back to the letters, nothing pending.
    private func startField() {
        applySettings()
        lastSpaceAt = 0; lastCorrection = nil; lastSwipe = nil
        wordTouches.removeAll(); takenTouch = nil
        showingClips = false; showingRewrite = false; ctrlOn = false; altOn = false
        editor.forget()
        keyboard.reset()
        keyboard.enterIcon = enterIcon()
        // Number and phone fields open on the ?123 page, as Gboard opens them.
        switch textDocumentProxy.keyboardType ?? .default {
        case .numberPad, .phonePad, .decimalPad, .numbersAndPunctuation, .asciiCapableNumberPad: keyboard.showPage(.symbols)
        default: break
        }
        updateShift()
        scheduleRow()
    }

    override func textWillChange(_ textInput: UITextInput?) {}

    /// iOS's word for "the text or the cursor moved under you": the selection, the field, or what is in it.
    override func textDidChange(_ textInput: UITextInput?) {
        // The field emptied under us (a chat app's send button): the message just sent is a sample.
        if !draft.isEmpty, editor.before.isEmpty, editor.after.isEmpty, !editor.hasSelection { flushDraft() }
        keyboard.enterIcon = enterIcon()
        let dark = systemDark
        if dark != lastDark { applySettings() }
        updateShift()
        scheduleRow()
    }

    override func selectionDidChange(_ textInput: UITextInput?) {
        updateShift()
        scheduleRow()
    }

    // --- settings -----------------------------------------------------------------------------------------------------

    private var lastDark = false
    private var systemDark: Bool {
        if textDocumentProxy.keyboardAppearance == .dark { return true }
        if textDocumentProxy.keyboardAppearance == .light { return false }
        return traitCollection.userInterfaceStyle == .dark
    }

    /// A field whose text must never be learned from or lit up.
    var isPrivateField: Bool { textDocumentProxy.isSecureTextEntry ?? false }

    func applySettings() {
        store.mirror(keys: KeyboardPrefs.defaultKeys + [AdaptiveKeys.storeKey])
        autocorrect.setLanguages(prefs.languages)
        autocorrect.setLearned(prefs.learnedWords)
        autocorrect.setSlang(prefs.slang)
        lastDark = systemDark
        let theme = KeyboardTheme.resolve(prefs: prefs, systemDark: lastDark)
        keyboard.theme = theme
        // The default themes take iOS's own keyboard backdrop, so the keyboard and the strip iOS draws under it (the
        // globe and dictation keys, which a keyboard cannot remove) read as one surface. A chosen theme keeps its colour.
        let backdrop = theme == .dark || theme == .light
        keyboard.systemBackdrop = backdrop
        row.systemBackdrop = backdrop
        keyboard.globeKey = needsInputModeSwitchKey
        keyboard.configure(numberRow: prefs.numberRow, keyHeight: prefs.keyHeight)
        keyboard.swipeTyping = Plugins.isOn(prefs, Plugins.swipe)
        keyboard.wideLayout = prefs.wideLayout
        keyboard.oneHanded = prefs.oneHanded
        keyboard.deleteSwipe = prefs.deleteSwipe
        let adaptiveOn = Plugins.isOn(prefs, Plugins.adaptive)
        keyboard.adaptive = adaptiveOn ? adaptiveModel : nil
        keyboard.adaptiveMap = adaptiveOn && prefs.adaptiveMap
        // The backlight is on by default, but never in a private field: lit letters would show what is typed.
        if !prefs.backlight || isPrivateField { keyboard.backlight = nil }
        else {
            let strength = Backlight.strengthOf(prefs.backlightStrength), fade = Backlight.fadeOf(prefs.backlightFade)
            if let b = keyboard.backlight, b.effect == prefs.backlightEffect, b.colour == prefs.backlightColour, b.strength == strength, b.mode == prefs.backlightMode, b.fade == fade {} else {
                keyboard.backlight = Backlight(effect: prefs.backlightEffect, colour: prefs.backlightColour, strength: strength, mode: prefs.backlightMode, fade: fade)
            }
        }
        let rowShown = prefs.toolbar || usesRow() || prefs.arrowKeys
        keyboard.pluginRow = rowShown
        row.isHidden = !rowShown
        row.theme = theme
        working.theme = theme
        row.showArrows = prefs.arrowKeys
        row.showHide = prefs.toolbar
        keyboard.keyBorders = prefs.keyBorders
        keyboard.showPreview = prefs.keyPopup
        keyboard.showHints = prefs.hints
        keyboard.spaceCursor = prefs.spaceCursor
        keyboard.longPressMs = Int(prefs.longPressMs)
        updateHeight()
        view.setNeedsLayout()
        scheduleRow()
    }

    /// Shift on at the start of a sentence, where the field asks for it and the setting allows.
    func updateShift() {
        guard prefs.autoCaps else { keyboard.setAutoShift(false); return }
        let before = editor.before
        let on: Bool
        switch textDocumentProxy.autocapitalizationType ?? .sentences {
        case .none: on = false
        case .allCharacters: on = true
        case .words: on = before.isEmpty || before.last!.isWhitespace
        case .sentences:
            let trimmed = before.trimmingCharacters(in: .whitespaces)
            if trimmed.isEmpty { on = true }
            else if before.last == "\n" { on = true }
            else if before.last!.isWhitespace, let end = trimmed.last { on = ".!?".contains(end) }
            else { on = false }
        @unknown default: on = false
        }
        keyboard.setAutoShift(on && !editor.hasSelection)
    }

    private func enterIcon() -> EnterIcon {
        switch textDocumentProxy.returnKeyType ?? .default {
        case .go, .join, .route, .continue: return .go
        case .search, .google, .yahoo: return .search
        case .send: return .send
        case .next: return .next
        case .done: return .done
        default: return .return
        }
    }

    // --- KeyboardListener: typing -------------------------------------------------------------------------------------

    static let wordEnds: Set<Character> = [".", ",", "!", "?"]
    /// What a pair key closes with: typed in front of the same character, it steps over it.
    static let closers: Set<Character> = [")", "]", "}", ">", "\"", "'", "`"]

    func onText(_ text: String, caretBack: Int) {
        finishTyping()
        // Terminal keys: a sticky Ctrl or Alt goes with the next key, as the control character a terminal reads.
        if (ctrlOn || altOn), text.count == 1, sendWithModifiers(text.first!) { return }
        lastSpaceAt = 0
        let single = text.count == 1 ? text.first : nil
        // A letter straight after a swiped word starts a new word: a space first, as Gboard does.
        if lastSwipe != nil, let c = single, c.isLetter || c.isNumber { editor.insert(" ") }
        lastSwipe = nil
        // Punctuation ends a word, as space does: autocorrect it first.
        if caretBack == 0, let c = single, Self.wordEnds.contains(c), correctLastWord(separator: text) { endWord(); updateShift(); return }
        lastCorrection = nil
        // A closing bracket or quote in front of the same one (a pair typed earlier) steps over it, as an editor does.
        if caretBack == 0, let c = single, Self.closers.contains(c), editor.after.first == c {
            editor.move(1)
            if c == ">" { checkFill() }
            return
        }
        editor.insert(text)
        // A pair's cursor goes inside it.
        if caretBack > 0 { editor.move(-caretBack) }
        adaptiveTyped(text)
        if text.hasSuffix(">") { checkFill() }
        if let c = single, Self.wordEnds.contains(c) { snapshotDraft() }
        updateShift()
        scheduleRow()
    }

    /// Autocorrect where this field wants it: not addresses, or fields that ask for no correction.
    func autocorrectAllowed() -> Bool {
        guard Plugins.isOn(prefs, Plugins.autocorrect), !isPrivateField else { return false }
        if textDocumentProxy.autocorrectionType == .no { return false }
        switch textDocumentProxy.keyboardType ?? .default {
        case .URL, .emailAddress, .numberPad, .phonePad, .decimalPad, .asciiCapableNumberPad, .numbersAndPunctuation, .webSearch: return false
        default: return true
        }
    }

    private static let codeLead: Set<Character> = ["@", "#", "/", ".", "_", "-", "\\", ":", "$", "`"]

    /// The letters (and apostrophes) just before the cursor: the word being typed. Empty in code-shaped text.
    func currentWord() -> String {
        let before = Array(editor.before.suffix(48))
        var start = before.count
        while start > 0, before[start - 1].isLetter || before[start - 1] == "'" || before[start - 1] == "’" { start -= 1 }
        if start > 0 {
            let prev = before[start - 1]
            if prev.isLetter || prev.isNumber || Self.codeLead.contains(prev) { return "" }
        }
        var word = String(before[start...])
        while word.first == "'" || word.first == "’" { word.removeFirst() }
        return word
    }

    /// The word just before the cursor, corrected and followed by `separator`, if autocorrect has a better one. True
    /// when it typed the separator itself. Words in code-shaped text (after @ # / . _ - or a digit) are left alone.
    func correctLastWord(separator: String) -> Bool {
        guard autocorrectAllowed(), prefs.autocorrect else { return false }
        let word = currentWord()
        guard !word.isEmpty, word.contains(where: { $0.isLetter }) else { return false }
        // In the middle of a word (the cursor moved back into it): the word goes on past the cursor, leave it.
        if let next = editor.after.first, next.isLetter || next.isNumber || next == "'" { return false }
        guard let fixed = autocorrect.correct(word) else { return false }
        adaptiveCorrected(word, fixed)
        editor.batch {
            editor.deleteBackward(word.count)
            editor.insert(fixed + separator)
        }
        lastCorrection = (word, fixed, separator)
        scheduleRow()
        return true
    }

    /// Puts back the word an autocorrection replaced, and keeps it as a learned word: undoing a fix says the word was
    /// meant, so the next space does not fix it again. False when the fix is no longer just before the cursor.
    @discardableResult
    func undoCorrection(keepSeparator: Bool) -> Bool {
        guard let (typed, fixed, separator) = lastCorrection else { return false }
        lastCorrection = nil
        let tail = fixed + separator
        guard editor.before.hasSuffix(tail) else { return false }
        editor.batch {
            editor.deleteBackward(tail.count)
            editor.insert(keepSeparator ? typed + separator : typed)
        }
        lastSpaceAt = 0
        if !incognito { prefs.learn(typed); autocorrect.setLearned(prefs.learnedWords) }
        scheduleRow()
        return true
    }

    func onDelete() {
        finishTyping()
        _ = keyboard.takeTyped()
        takenTouch = wordTouches.popLast().flatMap { $0 }.map { ($0, CACurrentMediaTime()) }
        defer { updateShift(); scheduleRow() }
        // Backspace straight after an autocorrection puts back what was typed, as Gboard does.
        if lastCorrection != nil, undoCorrection(keepSeparator: false) { return }
        // Backspace straight after a swiped word takes the whole word, as Gboard does.
        if let swipe = lastSwipe {
            lastSwipe = nil
            let tail = swipe.lead + swipe.word
            if editor.before.hasSuffix(tail) { editor.deleteBackward(tail.count); return }
        }
        editor.deleteBackward(1)
        lastSpaceAt = 0
    }

    func onSpace() {
        finishTyping()
        space()
        endWord()
        updateShift()
        scheduleRow()
    }

    private func space() {
        snapshotDraft()
        lastSwipe = nil
        let now = CACurrentMediaTime()
        if prefs.doubleSpacePeriod, now - lastSpaceAt < 1 {
            // Gboard's double-space full stop: "word␣" + space becomes "word.␣".
            let before = Array(editor.before.suffix(2))
            if before.count == 2, before[1] == " ", before[0].isLetter || before[0].isNumber {
                editor.batch { editor.deleteBackward(1); editor.insert(". ") }
                lastSpaceAt = 0
                return
            }
        }
        if correctLastWord(separator: " ") { lastSpaceAt = now; return }
        lastCorrection = nil
        editor.insert(" ")
        lastSpaceAt = now
    }

    func onEnter() {
        finishTyping()
        enter()
        endWord()
        updateShift()
        scheduleRow()
    }

    private func enter() {
        // Enter on a <<request>> (the cursor inside it, as the < > pair key leaves it) fills it rather than sending.
        if ghostwriterWants(), aiJob?.running != true, Ghostwriter.tagAt(before: editor.before, after: editor.after) != nil { fillAtCursor(); return }
        lastSpaceAt = 0; lastCorrection = nil; lastSwipe = nil
        snapshotDraft()
        switch textDocumentProxy.returnKeyType ?? .default {
        case .default: break
        // Searches aren't how anyone writes; a message sent (or a form finished) is a sample.
        case .search, .google, .yahoo: draft = ""
        default: flushDraft()
        }
        editor.insert("\n")
    }

    func onCursor(_ steps: Int) {
        wordTouches.removeAll(); takenTouch = nil
        lastCorrection = nil; lastSwipe = nil
        editor.move(steps)
    }

    /// Cursor control: a slide left from backspace takes the word before the cursor (or the selection).
    func onDeleteWord() {
        finishTyping()
        wordTouches.removeAll(); takenTouch = nil; lastCorrection = nil; lastSwipe = nil
        lastSpaceAt = 0
        defer { updateShift(); scheduleRow() }
        if editor.hasSelection { editor.deleteBackward(1); return }
        let before = Array(editor.before.suffix(96))
        if before.isEmpty { return }
        var i = before.count
        while i > 0, before[i - 1].isWhitespace { i -= 1 }
        if i > 0 {
            if before[i - 1].isLetter || before[i - 1].isNumber {
                while i > 0, before[i - 1].isLetter || before[i - 1].isNumber || before[i - 1] == "'" { i -= 1 }
            } else {
                // A symbol or an emoji goes on its own.
                i -= 1
            }
        }
        editor.deleteBackward(before.count - i)
    }

    /// Cursor control: a slide up or down from the space bar moves by lines, as arrow keys do.
    func onCursorLine(_ lines: Int) {
        finishTyping()
        wordTouches.removeAll(); takenTouch = nil; lastCorrection = nil; lastSwipe = nil
        editor.moveLine(lines)
    }

    /// The empty side of a docked or one-handed keyboard was tapped: the keys move there, and Settings follows.
    func onDock(_ side: String, wide: Bool) {
        _ = prefs.set(key: wide ? KeyboardPrefs.wideLayout : KeyboardPrefs.oneHanded, value: side)
        applySettings()
    }

    func onShiftChanged() {}

    func onKeyFeedback(_ type: KeyType, haptic: Bool) { keyFeedback(type, haptic: haptic) }

    /// A key's haptic and sound, as Settings has them: for the finger's presses and Ghostwriter's typing alike.
    func keyFeedback(_ type: KeyType, haptic: Bool = true) {
        feedback.key(type, haptic: haptic, vibrate: prefs.vibrate, strength: prefs.haptics, sound: prefs.sound, fullAccess: hasFullAccess)
    }

    func onGlobe(from view: UIView, with event: UIEvent) { handleInputModeList(from: view, with: event) }

    func onNextKeyboard() { advanceToNextInputMode() }

    /// The cog, or a long press on { }: the Ghost Keyboard app, at its Settings. A keyboard may not open another app
    /// by iOS's front door, so this asks the app hosting it to; where that is refused, the row says where to go.
    func onOpenSettings() {
        guard let url = URL(string: "\(GhostShared.urlScheme)://settings") else { return }
        if !openInHost(url) { flash("Open the Ghost Keyboard app to change settings") }
    }

    /// Whether the cog may ask the app hosting the keyboard to open the Ghost Keyboard app. One switch, should App
    /// Review object to it (docs/store/ios/review-notes.md): off, the row says where to go instead.
    static let canOpenApp = true

    private func openInHost(_ url: URL) -> Bool {
        guard Self.canOpenApp, hasFullAccess else { return false }
        let selector = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self
        while let r = responder {
            if r.isKind(of: NSClassFromString("UIApplication") ?? NSNull.self), r.responds(to: selector), let imp = r.method(for: selector) {
                typealias Open = @convention(c) (AnyObject, Selector, NSURL, NSDictionary, AnyObject?) -> Void
                unsafeBitCast(imp, to: Open.self)(r, selector, url as NSURL, NSDictionary(), nil)
                return true
            }
            responder = r.next
        }
        return false
    }

    func onHideKeyboard() { dismissKeyboard() }

    /// An arrow key in the row: the cursor moves, with the key's haptic and sound.
    func arrow(_ name: String) {
        finishTyping()
        wordTouches.removeAll(); takenTouch = nil; lastSwipe = nil; lastCorrection = nil
        keyFeedback(.char)
        switch name {
        case "left": editor.move(-1)
        case "right": editor.move(1)
        case "up": editor.moveLine(-1)
        default: editor.moveLine(1)
        }
    }

    /// `c` sent with the sticky Ctrl and Alt, then both let go: the control character a terminal reads (Ctrl+C is
    /// 0x03), with an Esc before it for Alt. False for a character Ctrl has no code for.
    private func sendWithModifiers(_ c: Character) -> Bool {
        guard let ascii = c.asciiValue else { return false }
        var out = ""
        if ctrlOn {
            let lower = Character(String(c).lowercased()).asciiValue ?? ascii
            let code: UInt8
            if lower >= 97 && lower <= 122 { code = lower - 96 }
            else if c == "[" { code = 0x1B } else if c == "\\" { code = 0x1C } else if c == "]" { code = 0x1D } else if c == " " { code = 0 }
            else { return false }
            out = String(UnicodeScalar(code))
        } else {
            out = String(c)
        }
        if altOn { out = "\u{1B}" + out }
        editor.insert(out)
        ctrlOn = false; altOn = false
        scheduleRow()
        return true
    }

    // --- adaptive keys ------------------------------------------------------------------------------------------------

    private func adaptiveOn() -> Bool { Plugins.isOn(prefs, Plugins.adaptive) && !incognito && !isPrivateField }

    /// Two letters whose keys touch on the letters page.
    private func neighbours(_ a: Character, _ b: Character) -> Bool {
        guard let ra = keyboard.letterRect(a), let rb = keyboard.letterRect(b) else { return false }
        return a.lowercased() != b.lowercased() && abs(ra.midX - rb.midX) < ra.width * 1.6 && abs(ra.midY - rb.midY) < ra.height * 1.6
    }

    /// A touch on one key meant for `meant`: measured against `meant`'s key, and placed on the heatmap.
    private func learnMistake(_ t: KeyboardView.Typed, _ meant: Character) {
        // A touch in the middle of the key it typed was aimed there: taken back, it is a change of mind, not a slip.
        if abs(t.x - t.rect.midX) < t.rect.width * 0.2 && abs(t.y - t.rect.midY) < t.rect.height * 0.2 { return }
        guard let r = keyboard.letterRect(meant) else { return }
        let (fx, fy) = keyboard.keysFraction(t.x, t.y)
        adaptiveModel.mistake(pressed: t.c, meant: meant, dx: (t.x - r.midX) / r.width, dy: (t.y - r.midY) / r.height, x: fx, y: fy)
    }

    /// After `text` is typed: a letter from a finger joins the word's touches. A letter typed straight after
    /// backspace took a neighbouring one away says that touch was meant for this letter.
    private func adaptiveTyped(_ text: String) {
        let typed = keyboard.takeTyped()
        guard adaptiveOn() else { wordTouches.removeAll(); takenTouch = nil; return }
        guard text.count == 1, let c = text.first, c.isLetter else { endWord(); return }
        if let (t, at) = takenTouch, CACurrentMediaTime() - at < 3, neighbours(t.c, c) { learnMistake(t, c) }
        takenTouch = nil
        wordTouches.append(typed.flatMap { String($0.c).lowercased() == String(c).lowercased() ? $0 : nil })
    }

    /// Autocorrect swapped `word` for `fixed`: where a letter became its neighbour, that touch was meant for it.
    private func adaptiveCorrected(_ word: String, _ fixed: String) {
        let w = Array(word), f = Array(fixed)
        guard adaptiveOn(), w.count == f.count, wordTouches.count == w.count else { return }
        let swaps = w.indices.filter { String(w[$0]).lowercased() != String(f[$0]).lowercased() }
        if swaps.isEmpty || swaps.count > 2 { return }
        for i in swaps {
            guard let t = wordTouches[i], neighbours(w[i], f[i]) else { continue }
            learnMistake(t, f[i])
            wordTouches[i] = nil
        }
    }

    /// The word is over: every touch left standing was right, and teaches its own key where it is aimed at.
    func endWord() {
        if wordTouches.isEmpty { return }
        if adaptiveOn() {
            for case let t? in wordTouches { adaptiveModel.hit(t.c, dx: (t.x - t.rect.midX) / t.rect.width, dy: (t.y - t.rect.midY) / t.rect.height) }
            adaptiveModel.save()
        }
        wordTouches.removeAll()
        takenTouch = nil
    }

    // --- swipe typing -------------------------------------------------------------------------------------------------

    func onSwipe(_ path: [CGPoint], keys: [Character: CGPoint], keyWidth: CGFloat) {
        wordTouches.removeAll(); takenTouch = nil
        let found = autocorrect.swipe(path: path, keys: keys, keyW: keyWidth)
        guard !found.isEmpty else { return }
        let shift = keyboard.shift
        let words = found.map { w -> String in
            switch shift {
            case .locked: return w.uppercased()
            case .on: return w.prefix(1).uppercased() + w.dropFirst()
            case .off: return w
            }
        }
        let lead: String
        if let last = editor.before.last, !last.isWhitespace, !"([{\"'`".contains(last) { lead = " " } else { lead = "" }
        editor.insert(lead + words[0])
        // The word's letters light in turn, as if typed along the path (never in a private field).
        if !isPrivateField { keyboard.lightWord(words[0]) }
        lastCorrection = nil
        lastSpaceAt = 0
        lastSwipe = SwipeState(lead: lead, word: words[0], choices: words)
        updateShift()
        scheduleRow()
    }

    func replaceSwipe(_ word: String) {
        guard let swipe = lastSwipe else { return }
        let tail = swipe.lead + swipe.word
        guard editor.before.hasSuffix(tail) else { lastSwipe = nil; scheduleRow(); return }
        editor.batch { editor.deleteBackward(tail.count); editor.insert(swipe.lead + word) }
        swipe.word = word
        if !isPrivateField { keyboard.lightWord(word) }
        scheduleRow()
    }

    // --- suggestions --------------------------------------------------------------------------------------------------

    /// A suggestion tapped: it replaces the word being typed, and a space follows.
    func pickSuggestion(typed: String, word: String) {
        guard currentWord() == typed else { scheduleRow(); return }
        editor.batch { editor.deleteBackward(typed.count); editor.insert(word + " ") }
        lastCorrection = nil
        lastSpaceAt = CACurrentMediaTime()
        updateShift()
        scheduleRow()
    }

    /// The typed word tapped in quotes: keep it as it is, learn it, and finish it with a space.
    func keepTyped(_ word: String) {
        if !incognito { prefs.learn(word); autocorrect.setLearned(prefs.learnedWords) }
        editor.insert(" ")
        lastCorrection = nil
        lastSpaceAt = CACurrentMediaTime()
        scheduleRow()
    }

    // --- markdown and snippets ----------------------------------------------------------------------------------------

    /// Markdown around the selection, or an empty pair with the cursor inside it.
    func wrap(_ open: String, _ close: String) {
        let selected = editor.selected ?? ""
        editor.batch {
            editor.insert(open + selected + close)
            if selected.isEmpty { editor.move(-close.count) }
        }
    }

    /// A link: the selection becomes its words, and the cursor waits where the address goes.
    func link() {
        let selected = editor.selected ?? ""
        editor.batch {
            if selected.isEmpty { editor.insert("[]()"); editor.move(-3) }
            else { editor.insert("[\(selected)]()"); editor.move(-1) }
        }
    }

    /// `prefix` at the start of the cursor's line (a heading, a list item, a quote).
    func linePrefix(_ prefix: String) {
        let before = editor.before
        let back = before.count - ((before.lastIndex(of: "\n").map { before.distance(from: before.startIndex, to: $0) + 1 }) ?? 0)
        if back > 0 { editor.move(-back) }
        editor.insert(prefix)
        if back > 0 { editor.move(back) }
    }

    /// A short notice in the row, for a few seconds: something the keyboard could not do, and why.
    var notice: String?
    func flash(_ text: String) {
        notice = text
        scheduleRow()
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
            guard let self, self.notice == text else { return }
            self.notice = nil
            self.scheduleRow()
        }
    }
}
