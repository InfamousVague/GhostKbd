package com.infamousvague.devkeyboard

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import androidx.annotation.RequiresApi

/**
 * The app's text field as the whole keyboard reaches it (DevKeyboardService.getCurrentInputConnection hands this
 * out in place of Android's own), with [FieldMirror] kept in step: what the keys commit and delete is done to the
 * mirror too, a question the mirror can answer is not put to the app, and anything else done to the field drops the
 * mirror, so the next question is put to the app as it always was.
 *
 * Every call the keyboard makes on a text field comes through here, the service's own and Android's helpers alike
 * (sendDownUpKeyEvents and the rest ask the service for its connection), which is what lets the mirror be believed:
 * nothing the keyboard does to the field goes round it. A call that is not listed below is Android's own wrapper
 * passing it on untouched; none of those changes the text.
 *
 * [ask] times each question that does go to the app on the main thread (Perf.kt: "ask.before", "ask.after",
 * "ask.selected", "ask.caps", "ask.around"): those are the waits a key press can have.
 */
class MirrorConnection(val target: InputConnection, private val mirror: FieldMirror) : InputConnectionWrapper(target, false) {

    private inline fun <T> ask(what: String, question: () -> T): T = Perf.time(what, question)

    // --- questions ------------------------------------------------------------------------------------------------

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        if (flags == 0) mirror.textBefore(n)?.let { return it }
        val real = ask("ask.before") { super.getTextBeforeCursor(n, flags) }
        if (flags == 0 && real != null) mirror.sawBefore(real.toString(), n)
        return real
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        if (flags == 0) mirror.textAfter(n)?.let { return it }
        val real = ask("ask.after") { super.getTextAfterCursor(n, flags) }
        if (flags == 0 && real != null) mirror.sawAfter(real.toString(), n)
        return real
    }

    /** Nothing selected is null, as Android's own text field answers it. */
    override fun getSelectedText(flags: Int): CharSequence? {
        if (flags == 0) mirror.selection()?.let { return it.ifEmpty { null } }
        return ask("ask.selected") { super.getSelectedText(flags) }
    }

    override fun getCursorCapsMode(reqModes: Int): Int {
        val local = mirror.capsMode(reqModes) ?: return ask("ask.caps") { super.getCursorCapsMode(reqModes) }
        if (!mirror.capsUnproven(local)) return local
        val real = ask("ask.caps") { super.getCursorCapsMode(reqModes) }
        mirror.capsChecked(local, real)
        return real
    }

    /** Android 12's one reading of the field, which also says where the cursor is: what the mirror starts from. */
    @RequiresApi(Build.VERSION_CODES.S)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
        val around = ask("ask.around") { super.getSurroundingText(beforeLength, afterLength, flags) }
        if (flags == 0) took(around, beforeLength, afterLength)
        return around
    }

    /**
     * A reading of the field, [around], that asked for [beforeLength] and [afterLength] characters: the mirror starts
     * again from it (null, or one that makes no sense, and the mirror has nothing). The row's refresh makes its
     * reading of [target] off the main thread and hands it over here, on the main thread, once it is known that
     * nothing was typed or moved while the app was answering.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun took(around: SurroundingText?, beforeLength: Int, afterLength: Int) {
        if (around == null) { mirror.drop(); return }
        val text = around.text.toString()
        val lo = minOf(around.selectionStart, around.selectionEnd)
        val hi = maxOf(around.selectionStart, around.selectionEnd)
        if (lo < 0 || hi > text.length) { mirror.drop(); return }
        val offset = around.offset
        mirror.read(
            text.substring(0, lo), text.substring(lo, hi), text.substring(hi),
            if (offset >= 0) offset + lo else -1, if (offset >= 0) offset + hi else -1,
            beforeLength, afterLength, fromStart = offset == 0,
        )
    }

    // --- the keys' own typing: done to the mirror as it is done to the field ---------------------------------------------

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val sent = super.commitText(text, newCursorPosition)
        if (sent && text != null) mirror.committed(text, newCursorPosition) else mirror.drop()
        return sent
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val sent = super.deleteSurroundingText(beforeLength, afterLength)
        if (sent) mirror.deleted(beforeLength, afterLength) else mirror.drop()
        return sent
    }

    // --- everything else that can change the text or move the cursor: the mirror is dropped --------------------------

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        mirror.drop(); return super.commitText(text, newCursorPosition, textAttribute)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        mirror.drop(); return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        mirror.drop(); return super.setComposingText(text, newCursorPosition)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        mirror.drop(); return super.setComposingText(text, newCursorPosition, textAttribute)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        mirror.drop(); return super.setComposingRegion(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?): Boolean {
        mirror.drop(); return super.setComposingRegion(start, end, textAttribute)
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        mirror.drop(); return super.replaceText(start, end, text, newCursorPosition, textAttribute)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        mirror.drop(); return super.setSelection(start, end)
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean {
        mirror.drop(); return super.commitCompletion(text)
    }

    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean {
        mirror.drop(); return super.commitCorrection(correctionInfo)
    }

    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
        mirror.drop(); return super.commitContent(inputContentInfo, flags, opts)
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        mirror.drop(); return super.performEditorAction(editorAction)
    }

    override fun performContextMenuAction(id: Int): Boolean {
        mirror.drop(); return super.performContextMenuAction(id)
    }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean {
        mirror.drop(); return super.performPrivateCommand(action, data)
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        mirror.drop(); return super.sendKeyEvent(event)
    }

    override fun closeConnection() {
        mirror.drop(); super.closeConnection()
    }
}
