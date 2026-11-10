import UIKit

/// Ghostwriter, Mockingbird and Rewrite in the keyboard (DevKeyboardService's AI half). All of it needs Allow Full
/// Access (a keyboard has no network without it) and the person's agreement in the app (`ai_consent`); where either
/// is missing the row says so in plain words rather than doing nothing.
extension KeyboardViewController {

    private static let fillOpen = try! NSRegularExpression(pattern: "<<([^<>\\n]{0,400})$")

    /// What the app's field tells a keyboard about itself: iOS does not say which app it is.
    var hints: AppContext.Hints {
        let p = textDocumentProxy
        let field: AppContext.FieldType
        switch p.keyboardType ?? .default {
        case .URL: field = .url
        case .emailAddress: field = .email
        case .numberPad, .decimalPad, .numbersAndPunctuation, .asciiCapableNumberPad: field = .number
        case .phonePad, .namePhonePad: field = .phone
        case .webSearch: field = .webSearch
        case .twitter: field = .twitter
        case .asciiCapable: field = .ascii
        default: field = .text
        }
        return AppContext.Hints(bundleId: nil, fieldType: field, returnKeyIsSend: p.returnKeyType == .send,
                                autocorrectionOff: p.autocorrectionType == .no, autocapitalisationOff: p.autocapitalizationType == UITextAutocapitalizationType.none,
                                secure: p.isSecureTextEntry ?? false)
    }

    var ghostwriter: Ghostwriter {
        if let made = ghostwriterBox { return made }
        let made = Ghostwriter(prefs: prefs, mockingbird: mockingbird, clips: clips, github: GitHubCommits(store: store))
        #if DEBUG
        made.debugEcho = true
        #endif
        ghostwriterBox = made
        return made
    }

    /// Why the AI features cannot run here, in the row's words, or nil where they can.
    func aiBlocked() -> String? {
        if !hasFullAccess { return "AI is off: it needs Allow Full Access" }
        if !prefs.aiConsent { return "AI is off: agree in the Ghost Keyboard app" }
        if incognito || isPrivateField { return "AI is off in private fields" }
        return nil
    }

    /// The AI may read this field: Full Access, agreed to, not a private field, not incognito.
    func aiAllowed() -> Bool { aiBlocked() == nil }

    func ghostwriterWants() -> Bool { Plugins.isOn(prefs, Plugins.ghostwriter) && aiAllowed() }

    /// A <<request>> just closed before the cursor: fill it, where Ghostwriter fills on its own.
    func checkFill() {
        guard ghostwriterWants(), prefs.ghostwriterAuto, aiJob?.running != true else { scheduleRow(); return }
        if Ghostwriter.hasClosedRequest(before: editor.before) { fillAtCursor() }
    }

    /// Fills the <<request>> the cursor is on (or just after).
    func fillAtCursor() {
        guard aiAllowed(), let fill = Ghostwriter.fillAt(before: editor.before, after: editor.after) else { return }
        aiJob?.cancelled = true
        aiJob = ghostwriter.start(fill, hints: hints) { [weak self] job in self?.onAiDone(job) }
        scheduleRow()
    }

    func rewriteSelection(_ selected: String) {
        guard aiAllowed() else { scheduleRow(); return }
        aiJob?.cancelled = true
        let h = hints
        aiJob = ghostwriter.rewrite(selected: selected, app: AppContext.label(hints: h), kind: AppContext.kind(hints: h)) { [weak self] job in self?.onAiDone(job) }
        scheduleRow()
    }

    /// The Rewrite plugin: `mode` done to the selection. iOS gives a keyboard no "select all", so with nothing
    /// selected it takes the text it can see around the cursor (iOS shows a keyboard the paragraph, not the page).
    func rewriteWith(_ mode: Rewrite.Mode) {
        showingRewrite = false
        guard aiAllowed() else { scheduleRow(); return }
        if let selected = editor.selected, !selected.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { startRewrite(selected, mode, wholeField: false); return }
        let before = editor.before, after = editor.after
        let all = before + after
        guard !all.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { flash("Nothing here to rewrite"); return }
        // With no selection the job's tag is the text around the cursor; the cursor is moved to its end to replace it.
        editor.move(after.count)
        startRewrite(all, mode, wholeField: true)
    }

    private func startRewrite(_ text: String, _ mode: Rewrite.Mode, wholeField: Bool) {
        aiJob?.cancelled = true
        aiWholeField = wholeField
        aiJob = ghostwriter.rewriteAs(selected: text, mode: mode) { [weak self] job in self?.onAiDone(job) }
        scheduleRow()
    }

    /// An answer came back: it replaces its tag (or the selection) where that is still there; if not, it waits in
    /// the row.
    private func onAiDone(_ job: Ghostwriter.Job) {
        guard job === aiJob else { return }
        if let text = job.result, placeAnswer(job, text) { aiJob = nil }
        scheduleRow()
    }

    private func placeAnswer(_ job: Ghostwriter.Job, _ text: String) -> Bool {
        let typeOut = prefs.ghostwriterTyping
        var placed = false
        editor.batch {
            if job.selection && aiWholeField {
                // The text the rewrite was made from must still end at the cursor.
                if editor.before.hasSuffix(job.tag) { editor.deleteBackward(job.tag.count); if !typeOut { editor.insert(text) }; placed = true }
            } else {
                placed = Ghostwriter.place(job, text: text, in: editor, insert: !typeOut)
            }
        }
        guard placed else { return false }
        if typeOut { startTyping(text) }
        aiAnswers.insert(text, at: 0)
        if aiAnswers.count > 6 { aiAnswers.removeLast() }
        updateShift()
        return true
    }

    // --- the AI typing ------------------------------------------------------------------------------------------------

    private func startTyping(_ text: String) {
        finishTyping()
        typing = Typing(text)
        scheduleRow()
        typeTick()
    }

    /// One beat of the AI typing: a few characters typed and their key pressed on the board, then a pause like a
    /// typist's: longer after a sentence, a comma or a line. Long answers move more characters a beat, so even a long
    /// one is done in about five seconds.
    private func typeTick() {
        guard let t = typing else { return }
        if t.at >= t.text.count { typing = nil; scheduleRow(); updateShift(); return }
        let per = min(t.text.count / 150 + 1, 6)
        let chunk = t.text[t.at..<min(t.at + per, t.text.count)]
        t.at += chunk.count
        editor.insert(String(chunk))
        let last = chunk.last!
        keyboard.ghostPress(last, holdMs: 60)
        // The key's haptic and sound, where Settings has them on, as if a finger pressed it.
        keyFeedback(last == " " ? .space : last == "\n" ? .enter : .char)
        let pause: Double
        switch last {
        case ".", "!", "?", "\n": pause = 0.140
        case ",", ";", ":": pause = 0.070
        case " ": pause = 0.034
        default: pause = 0.022
        }
        typeTimer?.invalidate()
        typeTimer = Timer.scheduledTimer(withTimeInterval: pause + Double.random(in: 0..<0.018), repeats: false) { [weak self] _ in self?.typeTick() }
    }

    /// Writes what is left at once: the person pressed a key, or left the field.
    func finishTyping() {
        guard let t = typing else { return }
        typing = nil
        typeTimer?.invalidate(); typeTimer = nil
        if t.at < t.text.count { editor.insert(String(t.text[t.at...])) }
        scheduleRow()
    }

    // --- the row ------------------------------------------------------------------------------------------------------

    /// The row while an answer is on its way, failed, or couldn't be placed. True when it filled `items`.
    func aiRow(_ items: inout [RowItem]) -> Bool {
        if typing != nil {
            items.append(RowItem(id: "ai-typing", label: "✦ Typing…", chip: true, fill: true))
            items.append(RowItem(id: "ai-skip", label: "Skip", bold: true, chip: true) { [weak self] in self?.finishTyping() })
            return true
        }
        guard let job = aiJob else { return false }
        if job.cancelled { aiJob = nil; return false }
        let dismiss = RowItem(id: "ai-x-\(job.id)", label: "✕", chip: true) { [weak self] in job.cancelled = true; self?.aiJob = nil; self?.scheduleRow() }
        if job.running {
            items.append(RowItem(id: "ai-run-\(job.id)", label: "✦ Writing “\(preview(job.request))”…", chip: true, fill: true))
            items.append(dismiss)
        } else if let error = job.error {
            items.append(RowItem(id: "ai-err-\(job.id)", label: "✦ \(error)", chip: true, fill: true) { [weak self] in self?.onOpenSettings() })
            items.append(RowItem(id: "ai-retry-\(job.id)", label: "Retry", bold: true, chip: true) { [weak self] in
                guard let self else { return }
                self.aiJob = nil
                if let mode = Rewrite.mode(self.prefs, id: job.mode) { self.startRewrite(job.tag, mode, wholeField: self.aiWholeField) }
                else if job.selection { self.rewriteSelection(job.tag) }
                else { self.fillAtCursor() }
                self.scheduleRow()
            })
            items.append(dismiss)
        } else {
            let text = job.result ?? ""
            items.append(RowItem(id: "ai-put-\(job.id)", label: "Insert: \(preview(text))", bold: true, chip: true, fill: true) { [weak self] in
                self?.editor.insert(text); self?.aiJob = nil; self?.scheduleRow()
            })
            items.append(dismiss)
        }
        return true
    }

    /// What the text just before the cursor asks for, before suggestions: a <<request>> being typed or ready to
    /// fill. True when it filled `items`.
    func contextual(_ items: inout [RowItem]) -> Bool {
        // Without Full Access iOS may keep the app's settings from the keyboard altogether, so it cannot know whether
        // Ghostwriter was added: a request typed anyway is answered with why nothing will happen.
        let settingsUnknown = !hasFullAccess && !store.groupReadable
        guard Plugins.isOn(prefs, Plugins.ghostwriter) || settingsUnknown else { return false }
        let before = String(editor.before.suffix(600))
        let tag = Ghostwriter.tagAt(before: before, after: editor.after)
        let ns = before as NSString
        let open = Self.fillOpen.firstMatch(in: before, range: NSRange(location: 0, length: ns.length))
        // Never silent: where Ghostwriter cannot run, say why.
        if let why = aiBlocked() {
            guard tag != nil || (open != nil && !settingsUnknown) else { return false }
            items.append(RowItem(id: "fill-off-\(why)", label: "✦ \(why)", chip: true, fill: true) { [weak self] in self?.onOpenSettings() })
            return true
        }
        if let (request, _) = tag {
            items.append(RowItem(id: "fill-\(request)", label: "✦ Fill: \(preview(request))", bold: true, chip: true, fill: true) { [weak self] in self?.fillAtCursor() })
            return true
        }
        if let m = open {
            let request = ns.substring(with: m.range(at: 1)).trimmingCharacters(in: .whitespaces)
            let label = request.count < 2 ? "✦ Ghostwriter: say what to write, then >>" : "✦ Fill: \(preview(request))"
            items.append(RowItem(id: "fill-open-\(label)", label: label, chip: true, fill: true) { [weak self] in
                guard let self, request.count >= 2 else { return }
                self.editor.insert(">>"); self.fillAtCursor()
            })
            return true
        }
        return false
    }

    // --- Mockingbird's samples ----------------------------------------------------------------------------------------

    /// A field whose text says how you write: not passwords, addresses, numbers or searches.
    private func collectable() -> Bool {
        let h = hints
        if h.secure || AppContext.isPrivateField(h) { return false }
        switch h.fieldType {
        case .text, .ascii, .twitter: break
        default: return false
        }
        switch textDocumentProxy.returnKeyType ?? .default {
        case .search, .google, .yahoo: return false
        default: return true
        }
    }

    /// Reads the field's text for Mockingbird, where it may: at word and sentence ends, and before enter.
    func snapshotDraft() {
        guard Plugins.isOn(prefs, Plugins.mockingbird), prefs.mockingbirdCollect, !incognito, hasFullAccess, prefs.aiConsent, collectable() else { draft = ""; return }
        draft = String(editor.before.suffix(1500)) + String(editor.after.prefix(500))
    }

    /// Keeps the draft as a sample (Mockingbird decides if it is worth keeping), then lets training catch up.
    func flushDraft() {
        var text = draft
        draft = ""
        for answer in aiAnswers { text = text.replacingOccurrences(of: answer, with: " ") }
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, !incognito, Plugins.isOn(prefs, Plugins.mockingbird), hasFullAccess, prefs.aiConsent else { return }
        mockingbird.record(text, hints: hints)
        _ = mockingbird.maybeAutoTrain(prefs: prefs)
    }
}
