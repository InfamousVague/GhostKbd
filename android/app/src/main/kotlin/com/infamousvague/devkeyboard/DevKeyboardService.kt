package com.infamousvague.devkeyboard

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import kotlin.math.abs

/** The keyboard: a native input method drawing [KeyboardView], configured from [KeyboardPrefs]. */
class DevKeyboardService : InputMethodService(), KeyboardListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: KeyboardPrefs
    private var keyboard: KeyboardView? = null
    private var selStart = -1
    private var selEnd = -1
    private var lastSpaceAt = 0L

    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = KeyboardPrefs(this)
        prefs.prefs.registerOnSharedPreferenceChangeListener(this)
        // Automatic updates: a look at most twice a day, installed with no prompt where Android allows it.
        Updater.backgroundCheck(this)
    }

    override fun onDestroy() {
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onCreateInputView(): View = KeyboardView(this, this).also {
        keyboard = it
        applySettings()
    }

    /**
     * Only the keyboard is the IME: the see-through room above it, where key previews are drawn, lets touches through
     * to the app and does not push the app's content up.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = keyboard ?: return
        if (!isInputViewShown || view.height == 0) return
        val at = IntArray(2)
        view.getLocationInWindow(at)
        val top = at[1] + view.keyboardTop.toInt()
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, top, view.width, at[1] + view.height)
    }

    /** Gboard stays a keyboard in landscape too, rather than taking the screen over with an extract view. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        applySettings()
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
        lastSpaceAt = 0L
        keyboard?.let { view ->
            if (!restarting) view.reset()
            view.enterIcon = enterIconFor(info)
            // Number, phone and date fields open on the ?123 page, as Gboard opens them.
            val cls = info.inputType and InputType.TYPE_MASK_CLASS
            if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) {
                view.showPage(Page.SYMBOLS)
            }
        }
        updateShift()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        updateShift()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySettings()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) = applySettings()

    private fun applySettings() {
        val view = keyboard ?: return
        view.theme = KeyboardTheme.resolve(this, prefs.theme)
        view.configure(prefs.numberRow, prefs.keyHeight, prefs.toolbar)
        view.keyBorders = prefs.keyBorders
        view.showPreview = prefs.keyPopup
        view.showHints = prefs.hints
        view.spaceCursor = prefs.spaceCursor
        view.longPressMs = prefs.longPressMs
    }

    /** Shift on at the start of a sentence, where the field asks for it and the setting allows. */
    private fun updateShift() {
        val view = keyboard ?: return
        if (!prefs.autoCaps) { view.setAutoShift(false); return }
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = if (info.inputType != InputType.TYPE_NULL) ic.getCursorCapsMode(info.inputType) else 0
        view.setAutoShift(caps != 0)
    }

    private fun enterIconFor(info: EditorInfo): EnterIcon {
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return EnterIcon.RETURN
        return when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> EnterIcon.GO
            EditorInfo.IME_ACTION_SEARCH -> EnterIcon.SEARCH
            EditorInfo.IME_ACTION_SEND -> EnterIcon.SEND
            EditorInfo.IME_ACTION_NEXT -> EnterIcon.NEXT
            EditorInfo.IME_ACTION_PREVIOUS -> EnterIcon.PREVIOUS
            EditorInfo.IME_ACTION_DONE -> EnterIcon.DONE
            else -> EnterIcon.RETURN
        }
    }

    // --- KeyboardListener -------------------------------------------------------------------------------------------

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
        lastSpaceAt = 0L
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        lastSpaceAt = 0L
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        val now = System.currentTimeMillis()
        if (prefs.doubleSpacePeriod && now - lastSpaceAt < 1000) {
            // Gboard's double-space full stop: "word␣" + space becomes "word.␣".
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit()) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                ic.endBatchEdit()
                lastSpaceAt = 0L
                return
            }
        }
        ic.commitText(" ", 1)
        lastSpaceAt = now
    }

    override fun onEnter() {
        val info = currentInputEditorInfo
        val ic = currentInputConnection ?: return
        lastSpaceAt = 0L
        if (info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action)
                return
            }
        }
        sendKeyChar('\n')
    }

    override fun onCursor(steps: Int) {
        val ic = currentInputConnection ?: return
        if (selEnd < 0) {
            val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(abs(steps)) { sendDownUpKeyEvents(code) }
            return
        }
        val move = if (steps < 0) -(ic.getTextBeforeCursor(-steps, 0)?.length ?: 0) else (ic.getTextAfterCursor(steps, 0)?.length ?: 0)
        if (move == 0) return
        // From the end of a selection going right, from its start going left, as arrow keys do.
        val from = if (move < 0) minOf(selStart, selEnd) else maxOf(selStart, selEnd)
        val to = (from + move).coerceAtLeast(0)
        ic.setSelection(to, to)
    }

    override fun onShiftChanged() = Unit

    override fun onKeyFeedback(key: Key) {
        if (prefs.vibrate) {
            // Medium is Gboard's feel; tick alone was too faint.
            val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VibrationEffect.createPredefined(
                    when (prefs.haptics) {
                        "light" -> VibrationEffect.EFFECT_TICK
                        "strong" -> VibrationEffect.EFFECT_HEAVY_CLICK
                        else -> VibrationEffect.EFFECT_CLICK
                    },
                )
            } else {
                VibrationEffect.createOneShot(when (prefs.haptics) { "light" -> 10L; "strong" -> 30L; else -> 18L }, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        }
        if (prefs.sound) {
            val fx = when (key.type) {
                KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio.playSoundEffect(fx, -1f)
        }
    }

    override fun onShowPicker() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onOpenSettings() {
        requestHideSelf(0)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onHideKeyboard() = requestHideSelf(0)
}
