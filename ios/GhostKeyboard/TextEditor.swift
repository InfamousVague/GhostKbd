import UIKit

/// Everything the keyboard does to the text, through iOS's `textDocumentProxy`: the only door a keyboard has. It
/// sees a window of text either side of the cursor and the selected text; it can insert, delete backward and move
/// the cursor by UTF-16 units, and nothing else.
///
/// The edits, and the history behind the row's undo and redo, are `FieldEditor`'s (GhostCore/FieldEditor.swift, the
/// port of Android's FieldEditor.kt, tested in GhostCoreTests). This adds the proxy, and knows a field by iOS's
/// `documentIdentifier`: another field, with the keyboard still up, starts with an empty history.
final class TextEditor: FieldEditor {
    private var document: UUID?

    init(proxy: @escaping () -> UITextDocumentProxy) {
        super.init(port: ProxyPort(proxy: proxy))
    }

    /// A field to type in (the keyboard came up): an empty history, recorded unless `recordable` is false (a
    /// password, or the Undo plugin off).
    func start(recordable: Bool, document id: UUID?) {
        document = id
        attach(recordable: recordable)
    }

    /// iOS said the text or the selection changed: in another field (the app moved focus with the keyboard up) the
    /// history starts again; in this one, it is checked against what the field now shows.
    func changed(recordable: Bool, document id: UUID?) {
        if let id, id != document { start(recordable: recordable, document: id); return }
        audit()
    }

    /// `FieldPort` over the proxy.
    final class ProxyPort: FieldPort {
        private let proxy: () -> UITextDocumentProxy

        init(proxy: @escaping () -> UITextDocumentProxy) { self.proxy = proxy }

        var before: String { proxy().documentContextBeforeInput ?? "" }
        var after: String { proxy().documentContextAfterInput ?? "" }
        var selected: String { proxy().selectedText ?? "" }
        func insert(_ text: String) { proxy().insertText(text) }
        func deleteBackward() { proxy().deleteBackward() }
        func move(units: Int) { if units != 0 { proxy().adjustTextPosition(byCharacterOffset: units) } }

        /// An app applies a cursor move a moment after it is asked for (web views especially), so what depends on
        /// it waits that moment rather than racing it, as Ghostwriter's answers do (KeyboardAI.swift).
        func settle(_ body: @escaping () -> Void) {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.08, execute: body)
        }
    }
}
