import UIKit

/// The row above the keys: what it shows, the first that applies (DevKeyboardService.refreshRow).
extension KeyboardViewController {

    /// Whether any added plugin puts things in the row above the keys.
    func usesRow() -> Bool {
        if Plugins.isOn(prefs, Plugins.autocorrect) && prefs.suggestions { return true }
        return [Plugins.swipe, Plugins.markdown, Plugins.terminal, Plugins.clipboard, Plugins.undo, Plugins.incognito, Plugins.mockingbird, Plugins.ghostwriter, Plugins.rewrite]
            .contains { Plugins.isOn(prefs, $0) }
    }

    /// Refreshes the row soon: many edits in a moment (a batch, a swipe) make one refresh.
    func scheduleRow() {
        rowRefresh?.cancel()
        let work = DispatchWorkItem { [weak self] in self?.refreshRow() }
        rowRefresh = work
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(25), execute: work)
    }

    /// A clip, or a request, as a chip shows it: one line, cut short.
    func preview(_ text: String) -> String {
        let line = text.split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
        return line.count > 28 ? String(line.prefix(27)) + "…" : line
    }

    /// What the row shows, the first that applies: a notice; the clipboard history; Rewrite's choices; an AI answer
    /// on its way; a swiped word's alternatives; what the text before the cursor asks for; autocorrect's suggestions
    /// for the word being typed (or the word a fix replaced, to keep it); and with nothing being typed, the plugins'
    /// own buttons.
    func refreshRow() {
        guard !row.isHidden else { return }
        var items: [RowItem] = []
        if let notice {
            items.append(RowItem(id: "notice-\(notice)", label: notice, chip: true, fill: true) { [weak self] in self?.notice = nil; self?.scheduleRow() })
            row.setItems(items)
            return
        }
        if showingClips {
            items.append(RowItem(id: "clips-back", icon: .back) { [weak self] in self?.showingClips = false; self?.scheduleRow() })
            let all = clips.all()
            if all.isEmpty { items.append(RowItem(id: "clips-none", label: "Nothing copied yet")) }
            for clip in all { items.append(RowItem(id: "clip-\(clip.at)", label: preview(clip.text), chip: true) { [weak self] in self?.pasteClip(clip.text) }) }
            if !all.isEmpty { items.append(RowItem(id: "clips-clear", label: "Clear") { [weak self] in self?.clips.clear(); self?.scheduleRow() }) }
            row.setItems(items)
            return
        }
        if showingRewrite {
            items.append(RowItem(id: "rw-back", icon: .back) { [weak self] in self?.showingRewrite = false; self?.scheduleRow() })
            for mode in Rewrite.modes(prefs) {
                items.append(RowItem(id: "rw-\(mode.id)-\(mode.label)", label: mode.label, chip: true) { [weak self] in self?.rewriteWith(mode) })
            }
            row.setItems(items)
            return
        }
        if aiRow(&items) { row.setItems(items); return }
        if let swipe = lastSwipe {
            for (i, w) in swipe.choices.enumerated() {
                items.append(RowItem(id: "swipe-\(i)-\(w)", label: w, bold: w == swipe.word, wide: true) { [weak self] in self?.replaceSwipe(w) })
            }
            row.setItems(middleBold(items))
            return
        }
        if !editor.hasSelection, contextual(&items) { row.setItems(items); return }
        if Plugins.isOn(prefs, Plugins.autocorrect) && prefs.suggestions && autocorrectAllowed() {
            if let (typed, fixed, _) = lastCorrection {
                items.append(RowItem(id: "keep-\(typed)", label: "\u{201C}\(typed)\u{201D}", wide: true) { [weak self] in self?.undoCorrection(keepSeparator: true) })
                items.append(RowItem(id: "fixed-\(fixed)", label: fixed, bold: true, wide: true) { [weak self] in self?.lastCorrection = nil; self?.scheduleRow() })
            } else if !editor.hasSelection {
                let word = currentWord()
                if !word.isEmpty {
                    let s = autocorrect.suggest(word)
                    if !s.typedIsWord { items.append(RowItem(id: "typed-\(word)", label: "\u{201C}\(word)\u{201D}", wide: true) { [weak self] in self?.keepTyped(word) }) }
                    for (i, w) in s.words.enumerated() {
                        items.append(RowItem(id: "s-\(i)-\(w)", label: w, bold: i == 0 && (s.fix != nil || s.typedIsWord), wide: true) { [weak self] in self?.pickSuggestion(typed: word, word: w) })
                    }
                }
            }
        }
        if items.isEmpty { items = tools() }
        row.setItems(middleBold(items))
    }

    /// Gboard's arrangement: with three suggestions, the pick sits in the middle.
    private func middleBold(_ items: [RowItem]) -> [RowItem] {
        if items.count < 3 || items.contains(where: { !$0.wide }) { return items }
        guard items.firstIndex(where: { $0.bold }) == 0 else { return items }
        return [items[1], items[0]] + items.dropFirst(2)
    }

    /// There is something in the field to rewrite: text beside the cursor, or a selection.
    private func fieldHasText() -> Bool { editor.hasSelection || !editor.before.isEmpty || !editor.after.isEmpty }

    /// The plugins' buttons, shown when nothing is being typed: "In my voice" and Rewrite, incognito's state, a
    /// just-copied clip, terminal keys and markdown, undo and redo, the clipboard.
    private func tools() -> [RowItem] {
        var items: [RowItem] = []
        let aiPlugins = [Plugins.mockingbird, Plugins.ghostwriter, Plugins.rewrite].contains { Plugins.isOn(prefs, $0) }
        // Never silent: where the AI features are added but cannot run, the row says why, once, at its start.
        if aiPlugins, let why = aiBlocked() {
            items.append(RowItem(id: "ai-off-\(why)", label: "✦ \(why)", chip: true) { [weak self] in self?.onOpenSettings() })
        }
        // Mockingbird: the selection rewritten as you.
        if Plugins.isOn(prefs, Plugins.mockingbird), aiAllowed(), let selected = editor.selected, !selected.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            items.append(RowItem(id: "voice", label: "✦ In my voice", bold: true, chip: true) { [weak self] in self?.rewriteSelection(selected) })
        }
        // Rewrite: the selection fixed, shortened, restyled or translated.
        if Plugins.isOn(prefs, Plugins.rewrite), aiAllowed(), fieldHasText() {
            items.append(RowItem(id: "rewrite", label: "✦ Rewrite", bold: editor.hasSelection, chip: true) { [weak self] in self?.showingRewrite = true; self?.scheduleRow() })
        }
        if Plugins.isOn(prefs, Plugins.incognito) {
            items.append(RowItem(id: "incognito-\(incognito)", label: incognito ? "Incognito on" : "Incognito", bold: incognito, chip: true) { [weak self] in
                guard let self else { return }
                self.incognitoOn.toggle(); self.scheduleRow()
            })
        }
        if Plugins.isOn(prefs, Plugins.clipboard), !incognito, hasFullAccess, UIPasteboard.general.changeCount != seenPasteboard, UIPasteboard.general.hasStrings {
            // Something new was copied. What it is stays unread until it is asked for: iOS tells the person each time
            // a keyboard reads the pasteboard.
            items.append(RowItem(id: "fresh-\(UIPasteboard.general.changeCount)", label: "Paste", bold: true, chip: true) { [weak self] in self?.pasteFresh() })
        }
        if Plugins.isOn(prefs, Plugins.terminal) && prefs.terminalEverywhere {
            items.append(RowItem(id: "esc", label: "Esc", chip: true) { [weak self] in self?.editor.insert("\u{1B}") })
            items.append(RowItem(id: "tab", label: "Tab", chip: true) { [weak self] in self?.editor.insert("\t") })
            items.append(RowItem(id: "ctrl-\(ctrlOn)", label: "Ctrl", bold: ctrlOn, chip: true) { [weak self] in self?.ctrlOn.toggle(); self?.scheduleRow() })
            items.append(RowItem(id: "alt-\(altOn)", label: "Alt", bold: altOn, chip: true) { [weak self] in self?.altOn.toggle(); self?.scheduleRow() })
            for (id, label) in [("left", "←"), ("up", "↑"), ("down", "↓"), ("right", "→")] {
                items.append(RowItem(id: id, label: label, chip: true) { [weak self] in self?.arrow(id) })
            }
            for c in ["|", "~", "/", "-", "&&", ">"] { items.append(RowItem(id: "t\(c)", label: c, chip: true) { [weak self] in self?.editor.insert(c) }) }
        }
        if Plugins.isOn(prefs, Plugins.markdown) && prefs.markdownEverywhere {
            items.append(RowItem(id: "md-b", label: "B", bold: true, chip: true) { [weak self] in self?.wrap("**", "**") })
            items.append(RowItem(id: "md-i", label: "I", chip: true) { [weak self] in self?.wrap("_", "_") })
            items.append(RowItem(id: "md-s", label: "S̶", chip: true) { [weak self] in self?.wrap("~~", "~~") })
            items.append(RowItem(id: "md-code", label: "</>", chip: true) { [weak self] in self?.wrap("`", "`") })
            items.append(RowItem(id: "md-block", label: "```", chip: true) { [weak self] in self?.wrap("```\n", "\n```") })
            items.append(RowItem(id: "md-link", label: "Link", chip: true) { [weak self] in self?.link() })
            items.append(RowItem(id: "md-h", label: "#", chip: true) { [weak self] in self?.linePrefix("# ") })
            items.append(RowItem(id: "md-list", label: "•", chip: true) { [weak self] in self?.linePrefix("- ") })
            items.append(RowItem(id: "md-task", label: "☐", chip: true) { [weak self] in self?.linePrefix("- [ ] ") })
            items.append(RowItem(id: "md-quote", label: "❝", chip: true) { [weak self] in self?.linePrefix("> ") })
        }
        if Plugins.isOn(prefs, Plugins.undo) {
            // iOS gives a keyboard no undo key to press: these take back, and put back, what this keyboard typed.
            items.append(RowItem(id: "undo", icon: .back, chip: true) { [weak self] in
                guard let self else { return }
                if !self.editor.undo() { self.flash("Nothing of the keyboard’s to undo") }
                self.lastCorrection = nil; self.lastSwipe = nil; self.updateShift(); self.scheduleRow()
            })
            items.append(RowItem(id: "redo", icon: .forward, chip: true) { [weak self] in
                guard let self else { return }
                if !self.editor.redo() { self.flash("Nothing to redo") }
                self.updateShift(); self.scheduleRow()
            })
        }
        if Plugins.isOn(prefs, Plugins.clipboard), !incognito {
            items.append(RowItem(id: "clips", label: "Clipboard", chip: true) { [weak self] in self?.openClips() })
        }
        return items
    }

    // --- the clipboard ------------------------------------------------------------------------------------------------

    /// The Clipboard chip: reads what is on the pasteboard now into the history (iOS shows its paste notice), and
    /// shows the history. Without Full Access iOS gives a keyboard no pasteboard at all.
    private func openClips() {
        guard hasFullAccess else { flash("Clipboard history needs Allow Full Access (Settings › Keyboards)"); return }
        readPasteboard()
        showingClips = true
        scheduleRow()
    }

    @discardableResult
    private func readPasteboard() -> String? {
        let board = UIPasteboard.general
        seenPasteboard = board.changeCount
        guard board.hasStrings, let text = board.string, !text.isEmpty else { return nil }
        _ = clips.record(text)
        return text
    }

    private func pasteFresh() {
        guard let text = readPasteboard() else { scheduleRow(); return }
        editor.insert(text)
        scheduleRow()
    }

    private func pasteClip(_ text: String) {
        editor.insert(text)
        showingClips = false
        scheduleRow()
    }
}
