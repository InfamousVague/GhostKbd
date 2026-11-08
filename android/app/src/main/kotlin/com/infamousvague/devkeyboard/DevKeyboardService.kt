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
    private val rowHandler = Handler(Looper.getMainLooper())
    private val refreshTask = Runnable { refreshRow() }

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
    }

    override fun onDestroy() {
        WhisperModel.removeListener(onModel)
        dictation.release()
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val view = KeyboardView(this, this)
        val strip = PluginRow(this, onSettings = { onOpenSettings() }, onHide = { onHideKeyboard() })
        val panel = GifPanel(this) { pickGif(it) }
        keyboard = view
        row = strip
        gifPanel = panel
        val frame = KeyboardFrame(this, view, strip, panel)
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
        if (!restarting) { inlineViews = emptyList(); closeGifs() }
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
        val rowShown = prefs.toolbar || usesRow()
        view.pluginRow = rowShown
        row?.let { it.theme = theme; it.showHide = prefs.toolbar; it.visibility = if (rowShown) View.VISIBLE else View.GONE }
        gifPanel?.theme = theme
        if (!Plugins.isOn(prefs, Plugins.GIFS)) closeGifs()
        scheduleRow()
        view.keyBorders = prefs.keyBorders
        view.tapLog = prefs.tapLog && !isPassword(currentInputEditorInfo)
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
        val ic = currentInputConnection ?: return
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
        prefs.learn(typed)
        autocorrect.setLearned(prefs.learnedWords)
        scheduleRow()
        return true
    }

    override fun onDelete() {
        if (gifMode == GifMode.TYPING) { gifQuery = gifQuery.dropLast(1); scheduleRow(); return }
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

    override fun onSpace() {
        if (gifMode == GifMode.TYPING) { gifQuery += " "; scheduleRow(); return }
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
        inlineViews = emptyList()
        lastSwipe = null
    }

    // --- the plugin row ---------------------------------------------------------------------------------------------

    /** Whether any added plugin puts things in the row above the keys. */
    private fun usesRow(): Boolean =
        (Plugins.isOn(prefs, Plugins.AUTOCORRECT) && prefs.suggestions) ||
            Plugins.isOn(prefs, Plugins.SWIPE) || Plugins.isOn(prefs, Plugins.PASSWORDS) || Plugins.isOn(prefs, Plugins.GIFS)

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
        if (gifMode != GifMode.OFF) {
            items += RowItem("gif-back", icon = RowIcon.BACK) { if (gifMode == GifMode.TYPING) searchGifs() else closeGifs() }
            val typing = gifMode == GifMode.TYPING
            val label = if (typing) "$gifQuery|" else gifQuery.ifEmpty { "Search GIPHY" }
            items += RowItem("gif-query", label = label, icon = RowIcon.SEARCH, chip = true) { typeGifSearch() }
            if (typing) items += RowItem("gif-go", icon = RowIcon.SEARCH) { searchGifs() }
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
                }
            }
        }
        if (items.isEmpty()) {
            if (Plugins.isOn(prefs, Plugins.GIFS)) items += RowItem("gifs", label = "GIFs", icon = RowIcon.GIF, chip = true) { openGifs() }
            if (Plugins.isOn(prefs, Plugins.PASSWORDS) && isPassword(currentInputEditorInfo)) {
                items += RowItem("passwords-hint", label = "Logins appear here", icon = RowIcon.KEY)
            }
        }
        strip.setItems(middleBold(items))
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
        prefs.learn(word)
        autocorrect.setLearned(prefs.learnedWords)
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
        gifMode = GifMode.BROWSING
        gifQuery = ""
        gifPanel?.visibility = View.VISIBLE
        runGifSearch()
        scheduleRow()
    }

    private fun closeGifs() {
        if (gifMode == GifMode.OFF) return
        gifMode = GifMode.OFF
        gifPanel?.visibility = View.GONE
        scheduleRow()
    }

    /** The search chip tapped: the panel steps aside and the keys type the search. */
    private fun typeGifSearch() {
        gifMode = GifMode.TYPING
        gifPanel?.visibility = View.GONE
        scheduleRow()
    }

    private fun searchGifs() {
        gifMode = GifMode.BROWSING
        gifPanel?.visibility = View.VISIBLE
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

        /** What a pair key closes with: typed in front of the same character, it steps over it. */
        private const val CLOSERS = ")]}>\"'`"
    }
}
