package com.infamousvague.devkeyboard

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * The text field as [FieldEditor] reaches it: Android's InputConnection, whichever one is current when a call is
 * made. Every call is wrapped so a dead or misbehaving connection costs nothing but that call. [pressKey] presses one
 * key down and up the way the service does (for stepping the cursor where the app gives no positions).
 */
class ConnectionPort(private val connection: () -> InputConnection?, private val pressKey: (Int) -> Unit) : FieldPort {

    /** Runs [call] on the current connection; a missing connection or a throw is nothing done. */
    private inline fun on(call: (InputConnection) -> Unit) {
        try { connection()?.let(call) } catch (t: Throwable) { android.util.Log.w("GhostKeyboard", "input connection call failed", t) }
    }

    /**
     * One read around the cursor. On Android 12 and later a single call that also says where the cursor is; before
     * that, or where the app does not answer it, the three older calls, which give no position.
     */
    override fun read(before: Int, after: Int): Reading? = try {
        val ic = connection()
        if (ic == null) null else readNew(ic, before, after) ?: readOld(ic, before, after)
    } catch (t: Throwable) {
        null
    }

    /** Android 12's one call: the text around the cursor and where it starts in the field. Null where the app does not answer it. */
    private fun readNew(ic: InputConnection, before: Int, after: Int): Reading? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val around = ic.getSurroundingText(before, after, 0) ?: return null
        val text = around.text.toString()
        val lo = minOf(around.selectionStart, around.selectionEnd)
        val hi = maxOf(around.selectionStart, around.selectionEnd)
        if (lo < 0 || hi > text.length) return null
        val offset = around.offset
        return Reading(text.substring(0, lo), text.substring(lo, hi), text.substring(hi), if (offset >= 0) offset + lo else -1, if (offset >= 0) offset + hi else -1)
    }

    /** The three calls every Android has: the text before, the selection, the text after. They give no position. */
    private fun readOld(ic: InputConnection, before: Int, after: Int): Reading? {
        val b = ic.getTextBeforeCursor(before, 0) ?: return null
        val a = ic.getTextAfterCursor(after, 0) ?: return null
        return Reading(b.toString(), ic.getSelectedText(0)?.toString().orEmpty(), a.toString(), -1, -1)
    }

    /** getTextBeforeCursor, as 0.9.4 used it for its checks. */
    override fun textBefore(n: Int): String? = try { connection()?.getTextBeforeCursor(n, 0)?.toString() } catch (t: Throwable) { null }

    /** getSelectedText: null where nothing is selected. */
    override fun selectedText(): String? = try { connection()?.getSelectedText(0)?.toString() } catch (t: Throwable) { null }

    /** commitText with the cursor left after the text. */
    override fun commit(text: String) = on { it.commitText(text, 1) }

    /** deleteSurroundingText. */
    override fun delete(before: Int, after: Int) = on { it.deleteSurroundingText(before, after) }

    /** setSelection. */
    override fun select(start: Int, end: Int) = on { it.setSelection(start, end) }

    /** beginBatchEdit: the app holds its redraws and reports until [endBatch]. */
    override fun beginBatch() = on { it.beginBatchEdit() }

    /** endBatchEdit. */
    override fun endBatch() = on { it.endBatchEdit() }

    /** finishComposingText: any composing region becomes plain text. */
    override fun finishComposing() = on { it.finishComposingText() }

    /** Moves the cursor [delta] characters from where it is now in the editor: by position where the app gives one, else with arrow keys. */
    override fun nudge(delta: Int) = on { ic ->
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        if (extracted != null && extracted.selectionEnd >= 0) {
            val at = (extracted.startOffset + extracted.selectionEnd + delta).coerceAtLeast(0)
            ic.setSelection(at, at)
        } else {
            val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(kotlin.math.abs(delta)) { pressKey(code) }
        }
    }

    /** The app's edit-menu Undo or Redo. True means the request was sent, not that the app did anything with it. */
    override fun appUndo(redo: Boolean): Boolean = try {
        connection()?.performContextMenuAction(if (redo) android.R.id.redo else android.R.id.undo) ?: false
    } catch (t: Throwable) {
        false
    }

    /**
     * Ctrl+Z, or Ctrl+Shift+Z for redo, as a hardware keyboard sends it: Ctrl goes down, then Z, then both come up.
     * 0.9.4 sent Z alone with Ctrl only marked on it, which Android's own text fields take but Flutter does not: it
     * goes by the Ctrl key it has seen pressed.
     */
    override fun keyUndo(redo: Boolean) = on { ic ->
        val now = SystemClock.uptimeMillis()
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val shift = if (redo) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        /** One key event of the chord, all at the same moment. */
        fun key(action: Int, code: Int, meta: Int) = ic.sendKeyEvent(KeyEvent(now, now, action, code, 0, meta))
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_CTRL_LEFT, ctrl)
        if (redo) key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, ctrl or shift)
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, ctrl or shift)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, ctrl or shift)
        if (redo) key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, ctrl)
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_CTRL_LEFT, 0)
    }
}
