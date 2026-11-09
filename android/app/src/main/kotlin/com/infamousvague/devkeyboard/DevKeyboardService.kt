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
import android.content.ClipDescription
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import android.widget.inline.InlinePresentationSpec
import kotlin.math.abs

/** The keyboard: a native input method drawing [KeyboardView], configured from [KeyboardPrefs]. */
class DevKeyboardService : InputMethodService(), KeyboardListener, Dictation.Listener, SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var prefs: KeyboardPrefs
    private var keyboard: KeyboardView? = null
    private var selStart = -1
    private var selEnd = -1
    private var lastSpaceAt = 0L
    private lateinit var autocorrect: Autocorrect
    private val dictation by lazy { Dictation(this) }
    private val onModel: (WhisperModel.State) -> Unit = { applySettings() }

    /** The last autocorrection, while backspace can still undo it: the word as typed, what replaced it, and the separator. */
    private var lastCorrection: Triple<String, String, String>? = null

    // --- the plugin row and its plugins' state ------------------------------------------------------------------------

    private var row: PluginRow? = null
    private var gifPanel: GifPanel? = null
    private var emojiPanel: EmojiPanel? = null
    /** Where a plugin's panel goes, over the keys: the GIF results or the emoji. */
    private var panelHost: android.widget.FrameLayout? = null
    private val rowHandler = Handler(Looper.getMainLooper())
    private val refreshTask = Runnable { runCatching { refreshRow() }.onFailure { android.util.Log.e("GhostKeyboard", "row refresh failed", it) } }

    /** The passwords plugin: the autofill service's inline suggestions for this field, as views. */
    private var inlineViews: List<View> = emptyList()
    private var inlineGeneration = 0

    /** Swipe typing's last word, while its alternatives are on offer: the space put before it, it, and the others. */
    private class SwipeState(val lead: String, var word: String, val choices: List<String>)
    private var lastSwipe: SwipeState? = null

    /** The GIF plugin: closed, showing results, or with the keys typing its search. */
    private enum class GifMode { OFF, BROWSING, TYPING }
    private var gifMode = GifMode.OFF
    private var gifQuery = ""

    /** The terminal plugin's sticky modifiers: on until the next key, which they are sent with. */
    private var ctrlOn = false
    private var altOn = false

    /** The clipboard plugin: the row showing the history instead of its usual things. */
    private var showingClips = false

    /** The incognito plugin's switch, and the field asking for no learning (EditorInfo's flag) either way. */
    private var incognitoOn = false
    private val incognito: Boolean get() =
        (Plugins.isOn(prefs, Plugins.INCOGNITO) && incognitoOn) ||
            (currentInputEditorInfo?.let { it.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0 } == true)

    private val clipboard by lazy { getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager }
    private val onClip = android.content.ClipboardManager.OnPrimaryClipChangedListener {
        if (Plugins.isOn(prefs, Plugins.CLIPBOARD) && !incognito) { ClipHistory.record(this, clipboard); scheduleRow() }
    }

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
        autocorrect = Autocorrect(this)
        autocorrect.setLanguages(prefs.languages)
        autocorrect.setLearned(prefs.learnedWords)
        WhisperModel.refresh(this)
        WhisperModel.addListener(onModel)
        // Automatic updates: a look at most twice a day, installed with no prompt where Android allows it.
        Updater.backgroundCheck(this)
        clipboard.addPrimaryClipChangedListener(onClip)
        // Sound shaping is gone (0.5.7): any filters set with it are cleared, so every pack plays as made.
        prefs.prefs.edit().remove("sound_filters").apply()
        // Long-press customisation is gone too (0.5.8): every key's popup is as it ships.
        prefs.prefs.edit().remove("popup_keys").remove("tap_log").remove("icon_pack").remove("sound_pack").remove("sound_source").apply()
        deleteFile("tap_log.txt")
    }

    override fun onDestroy() {
        runCatching { clipboard.removePrimaryClipChangedListener(onClip) }
        SoundPacks.release()
        WhisperModel.removeListener(onModel)
        dictation.release()
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val view = KeyboardView(this, this)
        val strip = PluginRow(this, onSettings = { onOpenSettings() }, onHide = { onHideKeyboard() })
        val panel = GifPanel(this) { pickGif(it) }
        val emoji = EmojiPanel(this) { commitEmoji(it) }
        val host = android.widget.FrameLayout(this).apply {
            addView(panel, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(emoji, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        keyboard = view
        row = strip
        gifPanel = panel
        emojiPanel = emoji
        panelHost = host
        val frame = KeyboardFrame(this, view, strip, host)
        applySettings()
        return frame
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
        lastCorrection = null
        lastSwipe = null
        if (!restarting) { inlineViews = emptyList(); closeGifs(); closeEmoji(); showingClips = false; ctrlOn = false; altOn = false }
        scheduleRow()
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
        scheduleRow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySettings()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) = applySettings()

    private fun applySettings() {
        autocorrect.setLanguages(prefs.languages)
        autocorrect.setLearned(prefs.learnedWords)
        val view = keyboard ?: return
        val theme = KeyboardTheme.resolve(this, prefs)
        view.theme = theme
        view.micKey = Plugins.isOn(prefs, Plugins.DICTATION) && WhisperModel.isReady(this)
        view.configure(prefs.numberRow, prefs.keyHeight, prefs.toolbar)
        view.swipeTyping = Plugins.isOn(prefs, Plugins.SWIPE)
        view.split = prefs.split
        // The backlight is on by default, but never while typing a password: lit letters would show what is typed.
        view.backlight = if (!prefs.backlight || isPassword(currentInputEditorInfo)) null else view.backlight?.takeIf {
            it.effect == prefs.backlightEffect && it.colour == prefs.backlightColour && it.strength == Backlight.strengthOf(prefs.backlightStrength) && it.mode == prefs.backlightMode &&
                it.fade == Backlight.fadeOf(prefs.backlightFade)
        } ?: Backlight(prefs.backlightEffect, prefs.backlightColour, Backlight.strengthOf(prefs.backlightStrength), prefs.backlightMode, Backlight.fadeOf(prefs.backlightFade))
        // The chosen pack, made ready before the first key needs it.
        if (prefs.sound) {
            val pack = soundToPlay()
            if (pack != null) SoundPacks.prepare(this, pack)
        }
        val rowShown = prefs.toolbar || usesRow()
        view.pluginRow = rowShown
        row?.let { it.theme = theme; it.showHide = prefs.toolbar; it.visibility = if (rowShown) View.VISIBLE else View.GONE }
        gifPanel?.theme = theme
        emojiPanel?.theme = theme
        if (!Plugins.isOn(prefs, Plugins.EMOJI)) closeEmoji()
        if (!Plugins.isOn(prefs, Plugins.GIFS)) closeGifs()
        scheduleRow()
        view.keyBorders = prefs.keyBorders
        view.showPreview = prefs.keyPopup
        view.showHints = prefs.hints
        view.spaceCursor = prefs.spaceCursor
        view.longPressMs = prefs.longPressMs
    }

    /** A field whose text must never be recorded: the tap log stays off in it. */
    private fun isPassword(info: EditorInfo?): Boolean {
        if (info == null) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
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

    override fun onText(text: String, caretBack: Int) {
        if (gifMode == GifMode.TYPING) { gifQuery += text; scheduleRow(); return }
        if (emojiMode == GifMode.TYPING) { emojiQuery += text; scheduleRow(); return }
        val ic = currentInputConnection ?: return
        // Terminal keys: a sticky Ctrl or Alt goes with the next key, as a key press the terminal reads (Ctrl+C).
        if ((ctrlOn || altOn) && text.length == 1 && sendWithModifiers(text[0])) return
        lastSpaceAt = 0L
        // A letter straight after a swiped word starts a new word: a space first, as Gboard does.
        if (lastSwipe != null && text.length == 1 && text[0].isLetterOrDigit()) ic.commitText(" ", 1)
        lastSwipe = null
        // Punctuation ends a word, as space does: autocorrect it first.
        if (caretBack == 0 && text.length == 1 && text[0] in WORD_ENDS && correctLastWord(ic, text)) return
        lastCorrection = null
        // A closing bracket or quote in front of the same one (a pair typed earlier) steps over it, as an editor does.
        if (caretBack == 0 && text.length == 1 && text[0] in CLOSERS && ic.getTextAfterCursor(1, 0)?.toString() == text) {
            moveCursorBy(ic, 1)
            return
        }
        ic.commitText(text, 1)
        // A pair's cursor goes inside it: commitText can only leave it before or after what it typed.
        if (caretBack > 0) moveCursorBy(ic, -caretBack)
        if (text == "]") learnGhostMd()
    }

    /** Moves the cursor [delta] characters, from where it is now in the editor (not where onUpdateSelection last said). */
    private fun moveCursorBy(ic: android.view.inputmethod.InputConnection, delta: Int) {
        val extracted = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
        if (extracted != null && extracted.selectionEnd >= 0) {
            val at = (extracted.startOffset + extracted.selectionEnd + delta).coerceAtLeast(0)
            ic.setSelection(at, at)
        } else {
            val code = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(kotlin.math.abs(delta)) { sendDownUpKeyEvents(code) }
        }
    }

    /** Autocorrect where this field wants it: not passwords, addresses, or fields that ask for no suggestions. */
    private fun autocorrectAllowed(): Boolean {
        if (!Plugins.isOn(prefs, Plugins.AUTOCORRECT)) return false
        val info = currentInputEditorInfo ?: return false
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        if (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return when (info.inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_FILTER -> false
            else -> true
        }
    }

    /**
     * The word just before the cursor, corrected and followed by [separator], if autocorrect has a better one. True
     * when it typed the separator itself. Words in code-shaped text (after @ # / . _ - or a digit) are left alone.
     */
    private fun correctLastWord(ic: android.view.inputmethod.InputConnection, separator: String): Boolean {
        if (!autocorrectAllowed() || !prefs.autocorrect) return false
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return false
        var start = before.length
        while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
        val word = before.substring(start).trimStart('\'')
        if (word.isEmpty() || !word.any { it.isLetter() }) return false
        if (start > 0) {
            val prev = before[start - 1]
            if (prev.isLetterOrDigit() || prev in "@#/._-\\:\$`") return false
        }
        // In the middle of a word (the cursor moved back into it): the word goes on past the cursor, leave it.
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && (after[0].isLetterOrDigit() || after[0] == '\'')) return false
        val fixed = autocorrect.correct(word) ?: return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText(fixed + separator, 1)
        ic.endBatchEdit()
        lastCorrection = Triple(word, fixed, separator)
        return true
    }

    /**
     * Puts back the word an autocorrection replaced, and keeps it as a learned word: undoing a fix says the word was
     * meant, so the next space does not fix it again. False when the fix is no longer just before the cursor.
     */
    private fun undoCorrection(ic: InputConnection, keepSeparator: Boolean): Boolean {
        val (typed, fixed, separator) = lastCorrection ?: return false
        lastCorrection = null
        val tail = fixed + separator
        if (ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(tail.length, 0)
        ic.commitText(if (keepSeparator) typed + separator else typed, 1)
        ic.endBatchEdit()
        lastSpaceAt = 0L
        if (!incognito) { prefs.learn(typed); autocorrect.setLearned(prefs.learnedWords) }
        scheduleRow()
        return true
    }

    override fun onDelete() {
        if (gifMode == GifMode.TYPING) { gifQuery = gifQuery.dropLast(1); scheduleRow(); return }
        if (emojiMode == GifMode.TYPING) { if (emojiQuery.isEmpty()) browseEmoji() else emojiQuery = emojiQuery.dropLast(1); scheduleRow(); return }
        val ic = currentInputConnection ?: return
        // Backspace straight after an autocorrection puts back what was typed, as Gboard does.
        if (lastCorrection != null && undoCorrection(ic, keepSeparator = false)) return
        // Backspace straight after a swiped word takes the whole word, as Gboard does.
        lastSwipe?.let { swipe ->
            lastSwipe = null
            val tail = swipe.lead + swipe.word
            if (ic.getTextBeforeCursor(tail.length, 0)?.toString() == tail) {
                ic.deleteSurroundingText(tail.length, 0)
                scheduleRow()
                return
            }
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        lastSpaceAt = 0L
    }

    /** In Ghost.md, the [[links]] and #tags just finished are kept for completing them later. */
    private fun learnGhostMd() {
        if (!Plugins.isOn(prefs, Plugins.GHOSTMD) || incognito || !AppContext.isGhostMd(currentInputEditorInfo?.packageName)) return
        currentInputConnection?.getTextBeforeCursor(160, 0)?.toString()?.let { GhostMd.learnFrom(this, "$it ") }
    }

    override fun onSpace() {
        if (gifMode == GifMode.TYPING) { gifQuery += " "; scheduleRow(); return }
        if (emojiMode == GifMode.TYPING) { emojiQuery += " "; scheduleRow(); return }
        learnGhostMd()
        val ic = currentInputConnection ?: return
        lastSwipe = null
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
        if (correctLastWord(ic, " ")) { lastSpaceAt = now; return }
        lastCorrection = null
        ic.commitText(" ", 1)
        lastSpaceAt = now
    }

    override fun onEnter() {
        if (gifMode == GifMode.TYPING) { searchGifs(); return }
        if (emojiMode == GifMode.TYPING) { browseEmoji(); return }
        val info = currentInputEditorInfo
        val ic = currentInputConnection ?: return
        lastSpaceAt = 0L
        lastCorrection = null
        lastSwipe = null
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
        lastCorrection = null
        lastSwipe = null
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
        val volume = prefs.soundVolume
        // The stock sound chosen in Settings › Sounds, else Android's.
        val pack = soundToPlay()
        if (prefs.sound && volume > 0f && !(pack != null && SoundPacks.play(this, pack, key.type, volume))) {
            val fx = when (key.type) {
                KeyType.DELETE -> AudioManager.FX_KEYPRESS_DELETE
                KeyType.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                KeyType.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
            audio.playSoundEffect(fx, volume)
        }
    }

    /** The stock sound picked in Settings › Sounds, or null for Android's own. */
    private fun soundToPlay(): String? = prefs.stockSound.takeIf { it in SoundPacks.STOCK }

    override fun onShowPicker() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onOpenSettings() {
        requestHideSelf(0)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onHideKeyboard() = requestHideSelf(0)

    // --- dictation (the dictation plugin) ---------------------------------------------------------------------------

    override fun onMic() {
        val view = keyboard ?: return
        if (dictation.listening) { dictation.stop(); return }
        if (view.dictation == DictationPhase.TRANSCRIBING) return
        if (!dictation.hasPermission()) {
            // An input method cannot ask for a permission itself: the settings app asks, on Dictation's page.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_OPEN, "dictation"))
            return
        }
        view.dictation = DictationPhase.LISTENING
        dictation.start(this)
    }

    override fun onLevel(level: Float) { keyboard?.level = level }

    override fun onTranscribing() { keyboard?.dictation = DictationPhase.TRANSCRIBING }

    override fun onDictated(text: String) {
        keyboard?.dictation = DictationPhase.OFF
        if (text.isEmpty()) return
        val ic = currentInputConnection ?: return
        // A space before the words where they follow other words, none where they start a line.
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val lead = if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'`") " " else ""
        ic.commitText(lead + text, 1)
        lastCorrection = null
    }

    override fun onError(message: String) {
        keyboard?.dictation = DictationPhase.OFF
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (dictation.listening) dictation.stop()
        closeGifs()
        closeEmoji()
        inlineViews = emptyList()
        lastSwipe = null
    }

    // --- the plugin row ---------------------------------------------------------------------------------------------

    /** Whether any added plugin puts things in the row above the keys. */
    private fun usesRow(): Boolean =
        (Plugins.isOn(prefs, Plugins.AUTOCORRECT) && prefs.suggestions) ||
            listOf(Plugins.SWIPE, Plugins.PASSWORDS, Plugins.GIFS, Plugins.MARKDOWN, Plugins.TERMINAL, Plugins.CLIPBOARD, Plugins.UNDO, Plugins.INCOGNITO)
                .any { Plugins.isOn(prefs, it) }

    /** Refreshes the row soon: many edits in a moment (a batch, a swipe) make one refresh. */
    private fun scheduleRow() {
        rowHandler.removeCallbacks(refreshTask)
        rowHandler.postDelayed(refreshTask, 25)
    }

    /**
     * What the row shows, the first that applies: the GIF search; a password manager's logins; a swiped word's
     * alternatives; autocorrect's suggestions for the word being typed (or the word a fix replaced, to keep it);
     * and with nothing being typed, the plugins' own buttons.
     */
    private fun refreshRow() {
        val strip = row ?: return
        if (strip.visibility != View.VISIBLE) return
        val items = ArrayList<RowItem>()
        if (emojiMode != GifMode.OFF) {
            val typing = emojiMode == GifMode.TYPING
            items += RowItem("emoji-back", icon = RowIcon.BACK) { if (typing) browseEmoji() else closeEmoji() }
            // Browsing, the search bar takes the row's full width; typing, it shrinks to leave room for the results.
            items += RowItem("emoji-query-$typing", label = if (typing) "$emojiQuery|" else "Search emoji", icon = RowIcon.SEARCH, chip = true, fill = !typing) { typeEmojiSearch() }
            if (typing) Emoji.search(emojiQuery, 40).forEach { e ->
                val shown = e.toned(prefs.emojiSkin)
                items += RowItem("emoji-$shown", label = shown) { commitEmoji(shown) }
            }
            strip.setItems(items)
            return
        }
        if (gifMode != GifMode.OFF) {
            items += RowItem("gif-back", icon = RowIcon.BACK) { if (gifMode == GifMode.TYPING) searchGifs() else closeGifs() }
            val typing = gifMode == GifMode.TYPING
            val label = if (typing) "$gifQuery|" else gifQuery.ifEmpty { "Search GIPHY" }
            items += RowItem("gif-query", label = label, icon = RowIcon.SEARCH, chip = true, fill = true) { typeGifSearch() }
            if (typing) items += RowItem("gif-go", icon = RowIcon.SEARCH) { searchGifs() }
            strip.setItems(items)
            return
        }
        if (showingClips) {
            items += RowItem("clips-back", icon = RowIcon.BACK) { showingClips = false; scheduleRow() }
            val clips = ClipHistory.all(this)
            if (clips.isEmpty()) items += RowItem("clips-none", label = "Nothing copied yet")
            for (clip in clips) items += RowItem("clip-${clip.at}", label = preview(clip.text), chip = true) { pasteClip(clip.text) }
            if (clips.isNotEmpty()) items += RowItem("clips-clear", label = "Clear") { ClipHistory.clear(this); scheduleRow() }
            strip.setItems(items)
            return
        }
        if (inlineViews.isNotEmpty() && Plugins.isOn(prefs, Plugins.PASSWORDS)) {
            inlineViews.forEachIndexed { i, v -> items += RowItem("inline-$i", view = v) }
            strip.setItems(items)
            return
        }
        lastSwipe?.let { swipe ->
            swipe.choices.forEachIndexed { i, w -> items += RowItem("swipe-$i-$w", label = w, bold = w == swipe.word, wide = true) { replaceSwipe(w) } }
            strip.setItems(middleBold(items))
            return
        }
        currentInputConnection?.let { ic -> if (selStart == selEnd && contextual(ic, items)) { strip.setItems(items); return } }
        if (Plugins.isOn(prefs, Plugins.AUTOCORRECT) && prefs.suggestions && autocorrectAllowed()) {
            val ic = currentInputConnection
            val correction = lastCorrection
            if (correction != null) {
                val (typed, fixed, _) = correction
                items += RowItem("keep-$typed", label = "\u201C$typed\u201D", wide = true) { currentInputConnection?.let { undoCorrection(it, keepSeparator = true) } }
                items += RowItem("fixed-$fixed", label = fixed, bold = true, wide = true) { lastCorrection = null; scheduleRow() }
            } else if (ic != null && selStart == selEnd) {
                val word = currentWord(ic)
                if (word.isNotEmpty()) {
                    val s = autocorrect.suggest(word)
                    if (!s.typedIsWord) items += RowItem("typed-$word", label = "\u201C$word\u201D", wide = true) { keepTyped(word) }
                    s.words.forEachIndexed { i, w ->
                        items += RowItem("s-$i-$w", label = w, bold = i == 0 && (s.fix != null || s.typedIsWord), wide = true) { pickSuggestion(word, w) }
                    }
                    // pizza → 🍕: an emoji for the word, tapped in after it.
                    if (Plugins.isOn(prefs, Plugins.EMOJI) && prefs.emojiSuggest) Emoji.forWord(word).forEach { e ->
                        val shown = e.toned(prefs.emojiSkin)
                        items += RowItem("se-$shown", label = shown, wide = true) { currentInputConnection?.commitText(" $shown", 1); Emoji.used(this, shown); scheduleRow() }
                    }
                }
            }
        }
        if (items.isEmpty()) items += tools()
        strip.setItems(middleBold(items))
    }

    /**
     * The plugins' buttons, shown when nothing is being typed: incognito's state, a just-copied clip, terminal keys
     * and markdown (in the apps they suit, or everywhere), undo and redo, the clipboard, GIFs.
     */
    private fun tools(): List<RowItem> {
        val items = ArrayList<RowItem>()
        val app = AppContext.kind(currentInputEditorInfo?.packageName)
        val ghost = Plugins.isOn(prefs, Plugins.GHOSTMD)
        val inGhost = AppContext.isGhostMd(currentInputEditorInfo?.packageName)
        // Selected text in another app: to Ghost.md as a note.
        if (ghost && !inGhost && selStart != selEnd) {
            currentInputConnection?.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let { selected ->
                items += RowItem("ghost-save", label = "Save to Ghost.md", bold = true, chip = true) {
                    if (!GhostMd.save(this, selected)) android.widget.Toast.makeText(this, "Ghost.md isn’t on this phone", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (ghost && inGhost) {
            items += RowItem("g-link", label = "[[ ]]", chip = true) { insertSnippet("[[¦]]") }
            items += RowItem("g-tag", label = "#", chip = true) { insertSnippet("#") }
            items += RowItem("g-task", label = "☐", chip = true) { linePrefix("- [ ] ") }
            items += RowItem("g-today", label = "Today", chip = true) { insertSnippet("[[" + java.time.LocalDate.now() + "]]") }
            items += RowItem("g-callout", label = "Callout", chip = true) { insertSnippet("> [!note] ¦") }
            items += RowItem("g-table", label = "Table", chip = true) { insertSnippet("| ¦ |  |\n| --- | --- |\n|  |  |") }
            items += RowItem("g-math", label = "∑ Math", chip = true) { insertSnippet("$$\n¦\n$$") }
            items += RowItem("g-diagram", label = "Diagram", chip = true) { insertSnippet("```mermaid\n¦\n```") }
            items += RowItem("g-divider", label = "—", chip = true) { insertSnippet("\n---\n") }
            items += RowItem("g-voice", label = "Voice note", chip = true) { GhostMd.voice(this) }
        }
        if (Plugins.isOn(prefs, Plugins.INCOGNITO)) {
            items += RowItem("incognito-$incognito", label = if (incognito) "Incognito on" else "Incognito", bold = incognito, chip = true) {
                incognitoOn = !incognitoOn; scheduleRow()
            }
        }
        if (Plugins.isOn(prefs, Plugins.CLIPBOARD) && !incognito) {
            ClipHistory.fresh?.takeIf { System.currentTimeMillis() - it.at < 90_000 }?.let { clip ->
                items += RowItem("fresh-${clip.at}", label = preview(clip.text), bold = true, chip = true) { pasteClip(clip.text) }
            }
        }
        if (Plugins.isOn(prefs, Plugins.TERMINAL) && (prefs.terminalEverywhere || app == AppKind.TERMINAL)) {
            items += RowItem("esc", label = "Esc", chip = true) { sendKey(KeyEvent.KEYCODE_ESCAPE) }
            items += RowItem("tab", label = "Tab", chip = true) { sendKey(KeyEvent.KEYCODE_TAB) }
            items += RowItem("ctrl-$ctrlOn", label = "Ctrl", bold = ctrlOn, chip = true) { ctrlOn = !ctrlOn; scheduleRow() }
            items += RowItem("alt-$altOn", label = "Alt", bold = altOn, chip = true) { altOn = !altOn; scheduleRow() }
            items += RowItem("left", label = "←", chip = true) { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            items += RowItem("up", label = "↑", chip = true) { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            items += RowItem("down", label = "↓", chip = true) { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            items += RowItem("right", label = "→", chip = true) { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            for (c in listOf("|", "~", "/", "-", "&&", ">")) items += RowItem("t$c", label = c, chip = true) { currentInputConnection?.commitText(c, 1) }
        }
        if (Plugins.isOn(prefs, Plugins.MARKDOWN) && (prefs.markdownEverywhere || app == AppKind.MARKDOWN)) {
            items += RowItem("md-b", label = "B", bold = true, chip = true) { wrap("**", "**") }
            items += RowItem("md-i", label = "I", chip = true) { wrap("_", "_") }
            items += RowItem("md-s", label = "S̶", chip = true) { wrap("~~", "~~") }
            items += RowItem("md-code", label = "</>", chip = true) { wrap("`", "`") }
            items += RowItem("md-block", label = "```", chip = true) { wrap("```\n", "\n```") }
            items += RowItem("md-link", label = "Link", chip = true) { link() }
            items += RowItem("md-h", label = "#", chip = true) { linePrefix("# ") }
            items += RowItem("md-list", label = "•", chip = true) { linePrefix("- ") }
            items += RowItem("md-task", label = "☐", chip = true) { linePrefix("- [ ] ") }
            items += RowItem("md-quote", label = "❝", chip = true) { linePrefix("> ") }
            // Ghost.md's own: a link to another note.
            if (!ghost && inGhost) items += RowItem("md-wiki", label = "[[ ]]", chip = true) { wrap("[[", "]]") }
        }
        if (Plugins.isOn(prefs, Plugins.UNDO)) {
            items += RowItem("undo", icon = RowIcon.BACK, chip = true) { sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON) }
            items += RowItem("redo", icon = RowIcon.FORWARD, chip = true) {
                sendKey(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
            }
        }
        if (Plugins.isOn(prefs, Plugins.CLIPBOARD) && !incognito) items += RowItem("clips", label = "Clipboard", chip = true) { showingClips = true; scheduleRow() }
        if (Plugins.isOn(prefs, Plugins.EMOJI)) items += RowItem("emoji", label = "😀 Emoji", chip = true) { openEmoji() }
        if (Plugins.isOn(prefs, Plugins.GIFS)) items += RowItem("gifs", label = "GIFs", icon = RowIcon.GIF, chip = true) { openGifs() }
        if (Plugins.isOn(prefs, Plugins.PASSWORDS) && isPassword(currentInputEditorInfo)) {
            items += RowItem("passwords-hint", label = "Logins appear here", icon = RowIcon.KEY)
        }
        return items
    }

    /** A clip as a chip shows it: one line, cut short. */
    private fun preview(text: String): String {
        val line = text.replace(Regex("\\s+"), " ").trim()
        return if (line.length > 28) line.take(27) + "…" else line
    }

    private fun pasteClip(text: String) {
        currentInputConnection?.commitText(text, 1)
        ClipHistory.fresh = null
        showingClips = false
        scheduleRow()
    }

    /** A key press with modifiers, as a hardware keyboard sends it: what terminals and undo listen for. */
    private fun sendKey(code: Int, meta: Int = 0) {
        val ic = currentInputConnection ?: return
        val now = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
    }

    /** [c] sent with the sticky Ctrl and Alt, then both let go. False for a character with no key of its own. */
    private fun sendWithModifiers(c: Char): Boolean {
        val code = when {
            c in 'a'..'z' || c in 'A'..'Z' -> KeyEvent.KEYCODE_A + (c.lowercaseChar() - 'a')
            c in '0'..'9' -> KeyEvent.KEYCODE_0 + (c - '0')
            c == '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
            c == ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
            c == '\\' -> KeyEvent.KEYCODE_BACKSLASH
            c == ' ' -> KeyEvent.KEYCODE_SPACE
            else -> return false
        }
        var meta = 0
        if (ctrlOn) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (altOn) meta = meta or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        sendKey(code, meta)
        ctrlOn = false; altOn = false
        scheduleRow()
        return true
    }

    /** Markdown around the selection, or an empty pair with the cursor inside it. */
    private fun wrap(open: String, close: String) {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        ic.beginBatchEdit()
        ic.commitText(open + selected + close, 1)
        if (selected.isEmpty()) moveCursorBy(ic, -close.length)
        ic.endBatchEdit()
    }

    /** A link: the selection becomes its words, and the cursor waits where the address goes. */
    private fun link() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        ic.beginBatchEdit()
        if (selected.isEmpty()) { ic.commitText("[]()", 1); moveCursorBy(ic, -3) }
        else { ic.commitText("[$selected]()", 1); moveCursorBy(ic, -1) }
        ic.endBatchEdit()
    }

    /** [prefix] at the start of the cursor's line (a heading, a list item, a quote). */
    private fun linePrefix(prefix: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        val lineStart = before.lastIndexOf('\n') + 1
        val back = before.length - lineStart
        ic.beginBatchEdit()
        if (back > 0) moveCursorBy(ic, -back)
        ic.commitText(prefix, 1)
        if (back > 0) moveCursorBy(ic, back)
        ic.endBatchEdit()
    }

    /**
     * What the text just before the cursor asks for, before suggestions: a :shortcode's emoji, and in Ghost.md a
     * [[link]]'s note titles or a #tag's tags. True when it filled [items].
     */
    private fun contextual(ic: InputConnection, items: ArrayList<RowItem>): Boolean {
        val before = ic.getTextBeforeCursor(200, 0)?.toString() ?: return false
        if (Plugins.isOn(prefs, Plugins.EMOJI)) {
            SHORTCODE.find(before)?.let { m ->
                val code = m.groupValues[1]
                val found = Emoji.search(code, 30)
                if (found.isNotEmpty()) {
                    found.forEach { e ->
                        val shown = e.toned(prefs.emojiSkin)
                        items += RowItem("sc-$shown", label = shown) { replaceBefore(":$code", shown); Emoji.used(this, shown) }
                    }
                    return true
                }
            }
        }
        if (Plugins.isOn(prefs, Plugins.GHOSTMD) && AppContext.isGhostMd(currentInputEditorInfo?.packageName)) {
            WIKI_OPEN.find(before)?.let { m ->
                val q = m.groupValues[1]
                GhostMd.titles(this, q).forEach { t -> items += RowItem("note-$t", label = t, chip = true) { replaceBefore(q, "$t]]") } }
                if (q.isNotBlank() && items.none { (it.label as? String).equals(q, ignoreCase = true) }) {
                    items += RowItem("note-new-$q", label = "New: $q", chip = true) { replaceBefore(q, "$q]]") }
                }
                if (items.isEmpty()) items += RowItem("note-none", label = "Type a note’s name")
                return true
            }
            TAG_OPEN.find(before)?.let { m ->
                val q = m.groupValues[1]
                val tags = GhostMd.tags(this, q)
                if (tags.isNotEmpty()) {
                    tags.forEach { t -> items += RowItem("tag-$t", label = "#$t", chip = true) { replaceBefore(q, "$t ") } }
                    return true
                }
            }
        }
        return false
    }

    /** Replaces [old], just before the cursor, with [new]. */
    private fun replaceBefore(old: String, new: String) {
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (old.isNotEmpty() && ic.getTextBeforeCursor(old.length, 0)?.toString() == old) ic.deleteSurroundingText(old.length, 0)
        ic.commitText(new, 1)
        ic.endBatchEdit()
        scheduleRow()
    }

    /** A snippet with a ¦ where the cursor goes. */
    private fun insertSnippet(snippet: String) {
        val ic = currentInputConnection ?: return
        val caret = snippet.indexOf('¦')
        val text = snippet.replace("¦", "")
        ic.beginBatchEdit()
        ic.commitText(text, 1)
        if (caret >= 0) moveCursorBy(ic, -(text.length - caret))
        ic.endBatchEdit()
    }

    // --- emoji (the emoji plugin) -----------------------------------------------------------------------------------

    private var emojiMode = GifMode.OFF
    private var emojiQuery = ""

    private fun showPanel(panel: View?) {
        gifPanel?.visibility = if (panel === gifPanel) View.VISIBLE else View.GONE
        emojiPanel?.visibility = if (panel === emojiPanel) View.VISIBLE else View.GONE
        panelHost?.visibility = if (panel == null) View.GONE else View.VISIBLE
    }

    private fun openEmoji() {
        closeGifs()
        showingClips = false
        emojiMode = GifMode.BROWSING
        emojiQuery = ""
        showPanel(emojiPanel)
        Emoji.load(this) { emojiPanel?.show(Emoji.recent(this), prefs.emojiSkin) }
        scheduleRow()
    }

    private fun browseEmoji() {
        emojiMode = GifMode.BROWSING
        showPanel(emojiPanel)
        emojiPanel?.show(Emoji.recent(this), prefs.emojiSkin)
        scheduleRow()
    }

    private fun typeEmojiSearch() {
        emojiMode = GifMode.TYPING
        emojiQuery = ""
        showPanel(null)
        scheduleRow()
    }

    private fun closeEmoji() {
        if (emojiMode == GifMode.OFF) return
        emojiMode = GifMode.OFF
        showPanel(null)
        scheduleRow()
    }

    private fun commitEmoji(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        if (!incognito) Emoji.used(this, emoji)
    }

    /** Gboard's arrangement: with three suggestions, the pick sits in the middle. */
    private fun middleBold(items: List<RowItem>): List<RowItem> {
        if (items.size < 3 || items.any { !it.wide }) return items
        val bold = items.indexOfFirst { it.bold }
        if (bold != 0) return items
        return listOf(items[1], items[0]) + items.drop(2)
    }

    /** The letters (and apostrophes) just before the cursor: the word being typed. */
    private fun currentWord(ic: InputConnection): String {
        val before = ic.getTextBeforeCursor(48, 0)?.toString() ?: return ""
        var start = before.length
        while (start > 0 && (before[start - 1].isLetter() || before[start - 1] == '\'')) start--
        if (start > 0) {
            val prev = before[start - 1]
            if (prev.isLetterOrDigit() || prev in "@#/._-\\:\$`") return ""
        }
        return before.substring(start).trimStart('\'')
    }

    /** A suggestion tapped: it replaces the word being typed, and a space follows. */
    private fun pickSuggestion(typed: String, word: String) {
        val ic = currentInputConnection ?: return
        if (!currentWord(ic).equals(typed, ignoreCase = false)) { scheduleRow(); return }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(typed.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        lastCorrection = null
        lastSpaceAt = System.currentTimeMillis()
        scheduleRow()
    }

    /** The typed word tapped in quotes: keep it as it is, learn it, and finish it with a space. */
    private fun keepTyped(word: String) {
        val ic = currentInputConnection ?: return
        if (!incognito) { prefs.learn(word); autocorrect.setLearned(prefs.learnedWords) }
        ic.commitText(" ", 1)
        lastCorrection = null
        lastSpaceAt = System.currentTimeMillis()
        scheduleRow()
    }

    // --- swipe typing (the swipe plugin) ----------------------------------------------------------------------------

    override fun onSwipe(path: List<android.graphics.PointF>, keys: Map<Char, android.graphics.PointF>, keyWidth: Float) {
        if (gifMode == GifMode.TYPING) return
        val ic = currentInputConnection ?: return
        val found = autocorrect.swipe(path, keys, keyWidth)
        if (found.isEmpty()) return
        val shift = keyboard?.shift ?: ShiftState.OFF
        val words = found.map { w ->
            when (shift) {
                ShiftState.LOCKED -> w.uppercase()
                ShiftState.ON -> w.replaceFirstChar { it.uppercaseChar() }
                ShiftState.OFF -> w
            }
        }
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val lead = if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'`") " " else ""
        ic.commitText(lead + words[0], 1)
        // The word's letters light in turn, as if typed along the path (never in a password field).
        if (!isPassword(currentInputEditorInfo)) keyboard?.lightWord(words[0])
        lastCorrection = null
        lastSpaceAt = 0L
        lastSwipe = SwipeState(lead, words[0], words)
        scheduleRow()
    }

    private fun replaceSwipe(word: String) {
        val swipe = lastSwipe ?: return
        val ic = currentInputConnection ?: return
        val tail = swipe.lead + swipe.word
        if (ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail) { lastSwipe = null; scheduleRow(); return }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(tail.length, 0)
        ic.commitText(swipe.lead + word, 1)
        ic.endBatchEdit()
        swipe.word = word
        if (!isPassword(currentInputEditorInfo)) keyboard?.lightWord(word)
        scheduleRow()
    }

    // --- password managers (the passwords plugin) -------------------------------------------------------------------

    /** Android 11+: asks the autofill service (NordPass, 1Password, Bitwarden...) for logins to show in the row. */
    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!Plugins.isOn(prefs, Plugins.PASSWORDS)) return null
        val d = resources.displayMetrics.density
        val style = androidx.autofill.inline.UiVersions.newStylesBuilder()
            .addStyle(androidx.autofill.inline.v1.InlineSuggestionUi.newStyleBuilder().build())
            .build()
        val spec = InlinePresentationSpec.Builder(Size((80 * d).toInt(), (32 * d).toInt()), Size((320 * d).toInt(), (32 * d).toInt()))
            .setStyle(style)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(6).build()
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!Plugins.isOn(prefs, Plugins.PASSWORDS)) return false
        val suggestions = response.inlineSuggestions
        val generation = ++inlineGeneration
        if (suggestions.isEmpty()) { inlineViews = emptyList(); scheduleRow(); return true }
        val d = resources.displayMetrics.density
        val views = arrayOfNulls<View>(suggestions.size)
        var pending = suggestions.size
        suggestions.forEachIndexed { i, suggestion ->
            suggestion.inflate(this, Size(ViewGroup.LayoutParams.WRAP_CONTENT, (32 * d).toInt()), mainExecutor) { v ->
                if (generation != inlineGeneration) return@inflate
                views[i] = v
                if (--pending == 0) {
                    // Pinned entries (the manager's own button) go last, as Android asks.
                    inlineViews = suggestions.indices.sortedBy { suggestions[it].info.isPinned }.mapNotNull { views[it] }
                    scheduleRow()
                }
            }
        }
        return true
    }

    // --- GIFs (the gifs plugin) -------------------------------------------------------------------------------------

    private fun openGifs() {
        closeEmoji()
        showingClips = false
        gifMode = GifMode.BROWSING
        gifQuery = ""
        showPanel(gifPanel)
        runGifSearch()
        scheduleRow()
    }

    private fun closeGifs() {
        if (gifMode == GifMode.OFF) return
        gifMode = GifMode.OFF
        showPanel(null)
        scheduleRow()
    }

    /** The search chip tapped: the panel steps aside and the keys type the search. */
    private fun typeGifSearch() {
        gifMode = GifMode.TYPING
        showPanel(null)
        scheduleRow()
    }

    private fun searchGifs() {
        gifMode = GifMode.BROWSING
        showPanel(gifPanel)
        runGifSearch()
        scheduleRow()
    }

    private fun runGifSearch() {
        val panel = gifPanel ?: return
        if (Giphy.key(prefs).isBlank()) { panel.showMessage("Add a GIPHY key in Settings › GIFs"); return }
        panel.showMessage(if (gifQuery.isBlank()) "Loading trending GIFs…" else "Searching…")
        val query = gifQuery
        Giphy.search(prefs, query) { gifs ->
            if (gifMode == GifMode.OFF || query != gifQuery) return@search
            if (gifs == null) panel.showMessage("Couldn’t reach GIPHY") else panel.show(gifs)
        }
    }

    /** Sends [gif]: as the picture itself where the app takes GIFs, else as its link. */
    private fun pickGif(gif: Gif) {
        gifPanel?.showMessage("Sending…")
        Giphy.load(gif.full) { bytes ->
            val ic = currentInputConnection
            val info = currentInputEditorInfo
            if (ic == null || info == null) { closeGifs(); return@load }
            val takesGifs = info.contentMimeTypes?.any { ClipDescription.compareMimeTypes("image/gif", it) } == true
            if (bytes != null && takesGifs) {
                val uri = Giphy.save(this, gif, bytes)
                val content = InputContentInfo(uri, ClipDescription("GIF", arrayOf("image/gif")), Uri.parse(gif.pageUrl.ifEmpty { gif.full }))
                if (!ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) ic.commitText(gif.full, 1)
            } else {
                ic.commitText(gif.full, 1)
            }
            closeGifs()
        }
    }

    companion object {
        /** Punctuation that finishes a word, and so runs autocorrect on it before it is typed. */
        private const val WORD_ENDS = ".,!?"

        /** ":smi" just typed, after a space or at the start: the emoji plugin's shortcode search. */
        private val SHORTCODE = Regex("(?:^|\\s):([a-z0-9_+-]{2,30})$")
        /** "[[Not" just typed in Ghost.md: the start of a link to a note. */
        private val WIKI_OPEN = Regex("\\[\\[([^\\[\\]\\n|]{0,60})$")
        /** "#ide" just typed in Ghost.md, after a space or at a line's start: a tag. */
        private val TAG_OPEN = Regex("(?:^|\\s)#([\\p{L}\\p{N}_/-]{1,40})$")

        /** What a pair key closes with: typed in front of the same character, it steps over it. */
        private const val CLOSERS = ")]}>\"'`"
    }
}
