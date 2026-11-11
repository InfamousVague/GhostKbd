package com.infamousvague.devkeyboard

import android.graphics.PointF
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.lang.ref.WeakReference

/**
 * The typing trainer's ear on the keyboard (Trainer.kt, docs/trainer.md): it stands between the keys and the service
 * that handles them, and while a trainer run is on in this app's own field it writes down each key and the text the
 * key left behind ([TrainerRecorder]). It changes nothing: every call goes on to [inner] exactly as it came, and with
 * no run on it does nothing else at all.
 *
 * It reads the field itself, before a key and after it, rather than follow what the service does with the key. So
 * whatever the service does (a fix, a full stop for two spaces, a word put back) is seen as it stands in the text,
 * and what changed the text between two keys (a word tapped in the row, a stroke read a moment after it was drawn)
 * is seen as the next key begins, or when the screen says its field changed ([look]). The app and the keyboard are
 * one process, so a look at the field is a call, not a wait.
 *
 * The service makes one with the keyboard's view and gives it what it needs of the service: the view (for where the
 * last tap landed), the field, whether a word is one of the word lists', and [apply], which has the keyboard put on
 * the layout a run asks for, or its own again.
 */
class TrainerKeys(
    private val inner: KeyboardListener,
    private val ownPackage: String,
    private val view: () -> KeyboardView?,
    private val connection: () -> InputConnection?,
    private val field: () -> EditorInfo?,
    private val isListed: (String) -> Boolean,
    private val apply: () -> Unit,
) : KeyboardListener by inner {
    init { Trainer.keys = WeakReference(this) }

    /** The run to write to: one is on, and the field the keyboard is typing in is this app's own. */
    private fun run(): Trainer.Session? = Trainer.session?.takeIf { field()?.packageName == ownPackage }

    /** The whole of the field's text (the screen keeps the cursor at its end). */
    private fun text(): String? {
        val ic = connection() ?: return null
        val before = ic.getTextBeforeCursor(MAX_TEXT, 0) ?: return null
        return before.toString() + ic.getTextAfterCursor(MAX_TEXT, 0)?.toString().orEmpty()
    }

    /** [act] is the key going on to the service. Around it, where a run is on: the text as it stood, and as the key left it. */
    private inline fun step(kind: KeyKind, tap: KeyTap? = null, act: () -> Unit) {
        val s = run()
        if (s == null) { act(); return }
        val at = Trainer.clock()
        try { text()?.let { s.recorder.seen(at, it) } } catch (t: Throwable) { Log.w(TAG, "the trainer missed a look", t) }
        act()
        try {
            text()?.let { s.recorder.key(at, kind, it, tap) }
            Trainer.progressed(s)
        } catch (t: Throwable) { Log.w(TAG, "the trainer missed a key", t) }
    }

    /**
     * Where the finger that typed [text] landed, about the middle of the key it was read as, in fractions of that
     * key; and, in a run typed on a fix, the key the keyboard as it is would have read the same touch as.
     */
    private fun tapOf(text: String): KeyTap? {
        if (text.length != 1) return null
        val t = view()?.peekTyped() ?: return null
        if (t.c.lowercaseChar() != text[0].lowercaseChar() || t.rect.width() <= 0f || t.rect.height() <= 0f) return null
        return KeyTap(t.c.lowercaseChar(), (t.x - t.rect.centerX()) / t.rect.width(), (t.y - t.rect.centerY()) / t.rect.height(), t.asIs.lowercaseChar())
    }

    override fun onText(text: String, caretBack: Int) {
        if (Trainer.session == null) { inner.onText(text, caretBack); return }
        step(KeyKind.CHAR, tapOf(text)) { inner.onText(text, caretBack) }
    }

    override fun onDelete() {
        if (Trainer.session == null) { inner.onDelete(); return }
        step(KeyKind.BACKSPACE) { inner.onDelete() }
    }

    override fun onSpace() {
        if (Trainer.session == null) { inner.onSpace(); return }
        step(KeyKind.SPACE) { inner.onSpace() }
    }

    override fun onEnter() {
        if (Trainer.session == null) { inner.onEnter(); return }
        step(KeyKind.ENTER) { inner.onEnter() }
    }

    override fun onDeleteWord() {
        if (Trainer.session == null) { inner.onDeleteWord(); return }
        step(KeyKind.DELETE_WORD) { inner.onDeleteWord() }
    }

    /** A stroke: its word is typed a moment later, off this call, and is seen then. */
    override fun onSwipe(path: List<PointF>, keys: Map<Char, PointF>, keyWidth: Float) {
        if (Trainer.session == null) { inner.onSwipe(path, keys, keyWidth); return }
        step(KeyKind.SWIPE) { inner.onSwipe(path, keys, keyWidth) }
    }

    /** The field's text may have changed with no key pressed: it is read again, and the screen told where the run stands. */
    fun look() {
        val s = run() ?: return
        try {
            text()?.let { s.recorder.seen(Trainer.clock(), it) }
            Trainer.progressed(s)
        } catch (t: Throwable) { Log.w(TAG, "the trainer missed a look", t) }
    }

    /** A run started or ended: the keyboard puts on the layout it asks for, or the person's own again. */
    fun changed() { try { apply() } catch (t: Throwable) { Log.w(TAG, "the keyboard did not follow the trainer", t) } }

    /** Whether [word] is one of the word lists' words. */
    fun listed(word: String): Boolean = try { isListed(word) } catch (t: Throwable) { false }

    private companion object {
        const val TAG = "GhostKeyboard"

        /** More than any story is long, with what a person might type past its end. */
        const val MAX_TEXT = 4_000
    }
}
