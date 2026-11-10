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
    /** The cog's update badge follows the updater: on when a check finds a release, off once it is installed. */
    private val onUpdate: (Updater.State) -> Unit = { row?.updateBadge = Updater.waiting(this) }

    /** The last autocorrection, while backspace can still undo it: the word as typed, what replaced it, and the separator. */
    private var lastCorrection: Triple<String, String, String>? = null

    // --- the plugin row and its plugins' state ------------------------------------------------------------------------

    private var row: PluginRow? = null
    private var frame: KeyboardFrame? = null
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

    /** The Rewrite plugin: the row showing its choices (Fix, Shorter, Formal…). */
    private var showingRewrite = false

    /** Per-app profiles: the profile of the app being typed in, or null where it has none (AppProfiles.kt). */
    private var profile: AppProfiles.Profile? = null

    /** Ghostwriter's <<request>> or Mockingbird's rewrite in flight, or its answer waiting to be placed. */
    private var aiJob: Ghostwriter.Job? = null

    /** Adaptive keys: the touches of the word being typed, in order (null where a letter came another way), and the
     *  last one backspace took away, with when. */
    private val wordTouches = ArrayList<KeyboardView.Typed?>()
    private var takenTouch: Pair<KeyboardView.Typed, Long>? = null

    /** Ghostwriter typing an answer out: the text and how far it has got. */
    private class Typing(val text: String) { var at = 0 }
    private var typing: Typing? = null
    private val typeHandler = Handler(Looper.getMainLooper())
    private val typeTask = Runnable { typeTick() }
    /** The AI's latest answers, kept out of Mockingbird's samples: they are not how the person writes. */
    private val aiAnswers = ArrayDeque<String>()

    // --- undo and redo (the undo plugin): every edit goes through [editor], which keeps the field's history --------

    private val editHandler = Handler(Looper.getMainLooper())
    private val settleTask = Runnable { editor.settle() }
    private val auditTask = Runnable { editor.audit() }

    /** The editor's two timers, on the main thread: a look at what Enter did, and a check after something else changed the field. */
    private val editTimers = object : Scheduler {
        override fun settleIn(ms: Long) { editHandler.removeCallbacks(settleTask); editHandler.postDelayed(settleTask, ms) }
        override fun auditIn(ms: Long) { editHandler.removeCallbacks(auditTask); editHandler.postDelayed(auditTask, ms) }
        override fun cancel() { editHandler.removeCallbacks(settleTask); editHandler.removeCallbacks(auditTask) }
    }

    /** The gateway every edit goes through (FieldEditor.kt): it sends the edit, and with the undo plugin on, writes it down. */
    private val editor: FieldEditor = FieldEditor(
        ConnectionPort({ currentInputConnection }, { code -> sendDownUpKeyEvents(code) }),
        { android.os.SystemClock.uptimeMillis() },
        editTimers,
        { message, error -> android.util.Log.e("GhostKeyboard", message, error) },
        { scheduleRow() },
        { text -> clusterBefore(text) },
    )

    /**
     * What the keyboard is told about a field when it starts: its view, app, type, Enter action, hint and names. A
     * start that changes any of them is a new field. Two inputs on one web page or Compose screen can still be alike
     * in all of these, which is why the editor reads the field before it keeps a history across a restart.
     */
    private data class FieldKey(
        val id: Int, val app: String?, val type: Int, val options: Int, val hint: String?, val name: String?, val label: String?, val privateOptions: String?,
    )

    /** The field the history belongs to. */
    private var fieldKey: FieldKey? = null

    /** The undo plugin's switch as last applied, so flipping it starts or stops recording the field in hand. */
    private var undoOn = false

    /**
     * After an arrow or a paste the row stays on its tools, the arrows among them, until the next key or a tap in the
     * text. Without it an undo that leaves the cursor at the end of a word brings up that word's suggestions, the
     * arrows go, and the rest cannot be undone.
     */
    private var toolsHeld = false

    /** Mockingbird: the field's text as last read, kept as a sample once it is sent or left. */
    private var draft = ""
    private var draftApp: String? = null

    /** The incognito plugin's switch, and the field asking for no learning (EditorInfo's flag) either way. */
    private var incognitoOn = false
    private val incognito: Boolean get() =
        (Plugins.isOn(prefs, Plugins.INCOGNITO) && incognitoOn) || profile?.flag("incognito") == true ||
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
        prefs.addNewDefaults()
        prefs.prefs.registerOnSharedPreferenceChangeListener(this)
        autocorrect = Autocorrect(this)
        autocorrect.setLanguages(prefs.languages)
        autocorrect.setLearned(prefs.learnedWords)
        autocorrect.setSlang(prefs.slang)
        // Mockingbird cleans slips out of its samples with these dictionaries, on its own thread.
        Mockingbird.speller = autocorrect
        WhisperModel.refresh(this)
        WhisperModel.addListener(onModel)
        // Automatic updates: a look at most twice a day, installed with no prompt where Android allows it.
        Updater.addListener(onUpdate)
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
        Updater.removeListener(onUpdate)
        dictation.release()
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(this)
        if (Mockingbird.speller === autocorrect) Mockingbird.speller = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val view = KeyboardView(this, this)
        val strip = PluginRow(this, onSettings = { onOpenSettings() }, onHide = { onHideKeyboard() }, onArrow = { code -> arrow(code) })
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
        this.frame = frame
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

    /**
     * A field takes the keyboard. A new field (or app) starts with an empty history. A field its app says it is only
     * starting again keeps its history if the editor finds its newest step in the text, read there and then
     * ([FieldEditor.restarted]): inputs that share one view (a web page, Compose, Flutter) arrive the same way.
     */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        runCatching {
            val key = attribute?.let {
                FieldKey(it.fieldId, it.packageName, it.inputType, it.imeOptions, it.hintText?.toString(), it.fieldName, it.label?.toString(), it.privateImeOptions)
            }
            if (attribute == null) { fieldKey = null; editor.detach(); return@runCatching }
            undoOn = Plugins.isOn(prefs, Plugins.UNDO)
            if (restarting && key == fieldKey) { editor.restarted(attribute.initialSelStart, attribute.initialSelEnd); return@runCatching }
            fieldKey = key
            editor.attach(recordable(attribute), hadText(attribute), attribute.initialSelStart, attribute.initialSelEnd)
        }.onFailure { android.util.Log.e("GhostKeyboard", "undo history could not start", it) }
    }

    /** The field is gone, and its history with it. */
    override fun onFinishInput() {
        super.onFinishInput()
        fieldKey = null
        editor.detach()
    }

    /** Whether the undo plugin writes this field's edits down: it is on, and the field is not a password or a field with no text type. */
    private fun recordable(info: EditorInfo?): Boolean =
        info != null && Plugins.isOn(prefs, Plugins.UNDO) && !isPassword(info) && info.inputType != InputType.TYPE_NULL

    /** Whether the field said it already holds text, whose undo is its app's. */
    private fun hadText(info: EditorInfo): Boolean {
        if (info.initialSelStart > 0 || info.initialSelEnd > 0) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return !info.getInitialTextBeforeCursor(1, 0).isNullOrEmpty() || !info.getInitialTextAfterCursor(1, 0).isNullOrEmpty()
    }

    /** How long the last character of [text] is with everything joined to it (a family emoji, a flag): Android's own rule. */
    private fun clusterBefore(text: String): Int {
        val tail = text.takeLast(64)
        val breaks = android.icu.text.BreakIterator.getCharacterInstance()
        breaks.setText(tail)
        val end = breaks.last()
        val start = breaks.previous()
        return if (start == android.icu.text.BreakIterator.DONE) 1 else end - start
    }

    /** An arrow in the row: the keyboard's own last step taken back (or put back), or the app's undo where it has none. */
    private fun undoTap(redo: Boolean) {
        // Undo while an answer is typing out stops it there, and takes back what it typed; redo lets it finish first.
        if (redo) finishTyping() else typing?.let { typing = null; typeHandler.removeCallbacks(typeTask) }
        lastCorrection = null
        lastSwipe = null
        wordTouches.clear(); takenTouch = null
        lastSpaceAt = 0L
        if (redo) editor.redo() else editor.undo()
        toolsHeld = true
        scheduleRow()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        runCatching { AppProfiles.seen(this, prefs, info.packageName) }
        applySettings()
        row?.updateBadge = Updater.waiting(this)
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
        lastSpaceAt = 0L
        lastCorrection = null
        lastSwipe = null
        toolsHeld = false
        wordTouches.clear(); takenTouch = null
        if (!restarting) { inlineViews = emptyList(); closeGifs(); closeEmoji(); showingClips = false; showingRewrite = false; ctrlOn = false; altOn = false; flushDraft(); aiJob?.cancelled = true; aiJob = null }
        // Whatever happened while the keyboard was away, the open step is over. Not on a restart: onStartInput has
        // just dealt with that, and an app that restarts after every key must not end the word at every letter.
        if (!restarting) editor.sent()
        // An empty history asks the field if it has text.
        editor.peek()
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
        if (!restarting) autoSherlock(info)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // A tap in the text (not the keyboard's own doing) lets the row go back to that word's suggestions.
        if (editor.onSelection(newSelStart, newSelEnd)) toolsHeld = false
        selStart = newSelStart
        selEnd = newSelEnd
        // The field emptied under us (a chat app's send button): the message just sent is a sample.
        if (newSelStart == 0 && newSelEnd == 0 && draft.isNotEmpty()) {
            val ic = currentInputConnection
            if (ic != null && ic.getTextBeforeCursor(1, 0).isNullOrEmpty() && ic.getTextAfterCursor(1, 0).isNullOrEmpty()) flushDraft()
        }
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
        autocorrect.setSlang(prefs.slang)
        profile = AppProfiles.of(prefs, currentInputEditorInfo?.packageName)
        // The undo plugin switched on or off: the field in hand starts being recorded, or stops.
        Plugins.isOn(prefs, Plugins.UNDO).let { on ->
            if (on != undoOn) {
                undoOn = on
                val info = currentInputEditorInfo
                if (info != null) editor.attach(recordable(info), true, selStart, selEnd)
            }
        }
        val view = keyboard ?: return
        val theme = KeyboardTheme.resolve(this, prefs)
        view.theme = theme
        view.micKey = Plugins.isOn(prefs, Plugins.DICTATION) && WhisperModel.isReady(this)
        view.configure(profile?.flag("number_row") ?: prefs.numberRow, prefs.keyHeight, prefs.toolbar && !prefs.hideRow)
        view.swipeTyping = Plugins.isOn(prefs, Plugins.SWIPE)
        view.wideLayout = prefs.wideLayout
        view.oneHanded = prefs.oneHanded
        view.bottomSpace = prefs.bottomSpace
        view.deleteSwipe = prefs.deleteSwipe
        // The backlight is on by default, but never while typing a password: lit letters would show what is typed.
        view.adaptive = if (Plugins.isOn(prefs, Plugins.ADAPTIVE)) AdaptiveKeys.get(this) else null
        view.adaptiveMap = Plugins.isOn(prefs, Plugins.ADAPTIVE) && prefs.adaptiveMap
        view.backlight = if (!prefs.backlight || isPassword(currentInputEditorInfo)) null else view.backlight?.takeIf {
            it.effect == prefs.backlightEffect && it.colour == prefs.backlightColour && it.strength == Backlight.strengthOf(prefs.backlightStrength) && it.mode == prefs.backlightMode &&
                it.fade == Backlight.fadeOf(prefs.backlightFade)
        } ?: Backlight(prefs.backlightEffect, prefs.backlightColour, Backlight.strengthOf(prefs.backlightStrength), prefs.backlightMode, Backlight.fadeOf(prefs.backlightFade))
        // The chosen pack, made ready before the first key needs it.
        if (prefs.sound) {
            val pack = soundToPlay()
            if (pack != null) SoundPacks.prepare(this, pack)
        }
        // Hide the row (Matt, 2026-11-10: "an option to hide the toolbar row completely"): no strip, whatever is added.
        val rowShown = !prefs.hideRow && (prefs.toolbar || usesRow() || prefs.arrowKeys || profile?.flag("terminal") == true || profile?.flag("markdown") == true)
        view.pluginRow = rowShown
        row?.let { it.theme = theme; it.condensed = prefs.keyHeight == "condensed"; it.showHide = prefs.toolbar; it.showArrows = prefs.arrowKeys; it.visibility = if (rowShown) View.VISIBLE else View.GONE }
        gifPanel?.theme = theme
        frame?.working?.theme = theme
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
        toolsHeld = false
        finishTyping()
        if (gifMode == GifMode.TYPING) { gifQuery += text; scheduleRow(); return }
        if (emojiMode == GifMode.TYPING) { emojiQuery += text; scheduleRow(); return }
        val ic = currentInputConnection ?: return
        // Terminal keys: a sticky Ctrl or Alt goes with the next key, as a key press the terminal reads (Ctrl+C).
        if ((ctrlOn || altOn) && text.length == 1 && sendWithModifiers(text[0])) return
        stats { TypingStats.typed(this, text) }
        lastSpaceAt = 0L
        // A letter straight after a swiped word starts a new word: a space first, as Gboard does.
        if (lastSwipe != null && text.length == 1 && text[0].isLetterOrDigit()) editor.type(" ")
        lastSwipe = null
        // Punctuation ends a word, as space does: autocorrect it first.
        if (caretBack == 0 && text.length == 1 && text[0] in WORD_ENDS && correctLastWord(ic, text)) { endWord(); return }
        lastCorrection = null
        // A closing bracket or quote in front of the same one (a pair typed earlier) steps over it, as an editor does.
        if (caretBack == 0 && text.length == 1 && text[0] in CLOSERS && ic.getTextAfterCursor(1, 0)?.toString() == text) {
            editor.hop(1)
            if (text == ">") checkFill()
            return
        }
        // A pair's cursor goes inside it: the editor puts it there after typing the pair.
        editor.type(text, caretBack)
        if (text == "]") learnGhostMd()
        adaptiveTyped(text)
        if (text.endsWith(">")) checkFill()
        if (text.length == 1 && text[0] in WORD_ENDS) snapshotDraft()
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
        if (!autocorrectAllowed() || !(profile?.flag("autocorrect") ?: prefs.autocorrect)) return false
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
        // One step in the history, after the separator as typed: undo gives back the word as typed, with its space.
        if (!editor.replaceBefore(word, fixed + separator, Kind.CORRECTION, typedFirst = separator)) return false
        adaptiveCorrected(word, fixed)
        stats { TypingStats.correction(this) }
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
        if (!editor.replaceBefore(tail, if (keepSeparator) typed + separator else typed, Kind.CORRECTION)) return false
        lastSpaceAt = 0L
        if (!incognito) { prefs.learn(typed); autocorrect.setLearned(prefs.learnedWords) }
        scheduleRow()
        return true
    }

    override fun onDelete() {
        toolsHeld = false
        finishTyping()
        keyboard?.takeTyped()
        takenTouch = wordTouches.removeLastOrNull()?.let { it to System.currentTimeMillis() }
        if (gifMode == GifMode.TYPING) { gifQuery = gifQuery.dropLast(1); scheduleRow(); return }
        if (emojiMode == GifMode.TYPING) { if (emojiQuery.isEmpty()) browseEmoji() else emojiQuery = emojiQuery.dropLast(1); scheduleRow(); return }
        val ic = currentInputConnection ?: return
        stats { TypingStats.backspace(this) }
        // Backspace straight after an autocorrection puts back what was typed, as Gboard does.
        if (lastCorrection != null && undoCorrection(ic, keepSeparator = false)) return
        // Backspace straight after a swiped word takes the whole word, as Gboard does.
        lastSwipe?.let { swipe ->
            lastSwipe = null
            val tail = swipe.lead + swipe.word
            if (ic.getTextBeforeCursor(tail.length, 0)?.toString() == tail) {
                editor.deleteBefore(tail)
                scheduleRow()
                return
            }
        }
        // With the undo plugin recording, the editor deletes what it has read, so it can put it back. Elsewhere (and
        // at a field's start, or after a picture) backspace is the key press it always was.
        if (!editor.backspace()) {
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            editor.sent()
        }
        lastSpaceAt = 0L
    }

    /** In Ghost.md, the [[links]] and #tags just finished are kept for completing them later. */
    private fun learnGhostMd() {
        if (!Plugins.isOn(prefs, Plugins.GHOSTMD) || incognito || !AppContext.isGhostMd(currentInputEditorInfo?.packageName)) return
        currentInputConnection?.getTextBeforeCursor(160, 0)?.toString()?.let { GhostMd.learnFrom(this, "$it ") }
    }

    override fun onSpace() {
        toolsHeld = false
        finishTyping()
        try { space() } finally { endWord() }
    }

    private fun space() {
        if (gifMode == GifMode.TYPING) { gifQuery += " "; scheduleRow(); return }
        if (emojiMode == GifMode.TYPING) { emojiQuery += " "; scheduleRow(); return }
        learnGhostMd()
        snapshotDraft()
        val ic = currentInputConnection ?: return
        stats { TypingStats.typed(this, " ") }
        lastSwipe = null
        val now = System.currentTimeMillis()
        if (prefs.doubleSpacePeriod && now - lastSpaceAt < 1000) {
            // Gboard's double-space full stop: "word␣" + space becomes "word.␣".
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit() &&
                editor.replaceBefore(" ", ". ", Kind.CORRECTION, typedFirst = " ")
            ) {
                lastSpaceAt = 0L
                return
            }
        }
        if (correctLastWord(ic, " ")) { lastSpaceAt = now; return }
        lastCorrection = null
        editor.type(" ")
        lastSpaceAt = now
    }

    override fun onEnter() {
        toolsHeld = false
        finishTyping()
        try { enter() } finally { endWord() }
    }

    private fun enter() {
        if (gifMode == GifMode.TYPING) { searchGifs(); return }
        if (emojiMode == GifMode.TYPING) { browseEmoji(); return }
        val info = currentInputEditorInfo
        val ic = currentInputConnection ?: return
        // Enter on a <<request>> (the cursor inside it, as the < > pair key leaves it) fills it rather than sending.
        if (Plugins.isOn(prefs, Plugins.GHOSTWRITER) && aiAllowed() && aiJob?.running != true &&
            Ghostwriter.tagAt(ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty(), ic.getTextAfterCursor(400, 0)?.toString().orEmpty()) != null
        ) { fillAtCursor(); return }
        lastSpaceAt = 0L
        lastCorrection = null
        lastSwipe = null
        snapshotDraft()
        stats { TypingStats.typed(this, "\n") }
        if (info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
                // A message sent (or a form finished): what was typed is a sample. Searches aren't how anyone writes.
                if (action != EditorInfo.IME_ACTION_SEARCH) flushDraft() else draft = ""
                ic.performEditorAction(action)
                // The app does what it likes with the action (a search box keeps its text): the field is checked after.
                editor.sent()
                return
            }
        }
        editor.enter { sendKeyChar('\n') }
    }

    override fun onCursor(steps: Int) {
        toolsHeld = false
        // An answer still typing out is written whole first, so it stays one step and none of it lands at the moved
        // cursor. The selection reported so far is then behind what was just written: this move goes by arrow keys.
        val behind = typing != null
        finishTyping()
        wordTouches.clear(); takenTouch = null
        val ic = currentInputConnection ?: return
        lastCorrection = null
        lastSwipe = null
        if (selEnd < 0 || behind) {
            val code = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(abs(steps)) { sendDownUpKeyEvents(code) }
            editor.sent()
            return
        }
        val move = if (steps < 0) -(ic.getTextBeforeCursor(-steps, 0)?.length ?: 0) else (ic.getTextAfterCursor(steps, 0)?.length ?: 0)
        if (move == 0) return
        // From the end of a selection going right, from its start going left, as arrow keys do.
        val from = if (move < 0) minOf(selStart, selEnd) else maxOf(selStart, selEnd)
        val to = (from + move).coerceAtLeast(0)
        ic.setSelection(to, to)
        editor.moved(to)
    }

    /** Cursor control: a slide left from backspace takes the word before the cursor (or the selection). */
    override fun onDeleteWord() {
        toolsHeld = false
        finishTyping()
        wordTouches.clear(); takenTouch = null; lastCorrection = null; lastSwipe = null
        if (gifMode == GifMode.TYPING) { gifQuery = gifQuery.trimEnd().substringBeforeLast(' ', ""); scheduleRow(); return }
        if (emojiMode == GifMode.TYPING) { emojiQuery = emojiQuery.trimEnd().substringBeforeLast(' ', ""); scheduleRow(); return }
        val ic = currentInputConnection ?: return
        lastSpaceAt = 0L
        if (!ic.getSelectedText(0).isNullOrEmpty()) { editor.deleteSelection(); return }
        val before = ic.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i > 0) {
            if (before[i - 1].isLetterOrDigit()) {
                while (i > 0 && (before[i - 1].isLetterOrDigit() || before[i - 1] == '\'')) i--
            } else {
                // A symbol or an emoji goes on its own, both halves of it where it is a surrogate pair.
                i -= if (Character.isLowSurrogate(before[i - 1]) && i >= 2) 2 else 1
            }
        }
        editor.deleteBefore(before.substring(i))
    }

    /** Cursor control: a slide up or down from the space bar moves by lines, as the arrow keys do. */
    override fun onCursorLine(lines: Int) {
        toolsHeld = false
        finishTyping()
        wordTouches.clear(); takenTouch = null; lastCorrection = null; lastSwipe = null
        repeat(abs(lines)) { sendKey(if (lines < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN) }
    }

    /** The empty side of a docked or one-handed keyboard was tapped: the keys move there, and Settings follows. */
    override fun onDock(side: String, wide: Boolean) {
        prefs.set(if (wide) KeyboardPrefs.WIDE_LAYOUT else KeyboardPrefs.ONE_HANDED, side)
    }

    /** Typing stats count here: switched on, and never in a password field or while incognito. */
    private fun statsOn(): Boolean = prefs.typingStats && !incognito && !isPassword(currentInputEditorInfo)

    /** Counts something for the stats where they are on. Counting must never cost a keystroke: a failure is dropped. */
    private inline fun stats(count: () -> Unit) {
        if (statsOn()) runCatching(count)
    }

    override fun onShiftChanged() = Unit

    override fun onKeyFeedback(key: Key, haptic: Boolean) = keyFeedback(key.type, haptic)

    /** A key's haptic and sound, as Settings has them: for the finger's presses and Ghostwriter's typing alike. */
    private fun keyFeedback(type: KeyType, haptic: Boolean = true) {
        if (haptic && prefs.vibrate) {
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
        if (prefs.sound && volume > 0f && !(pack != null && SoundPacks.play(this, pack, type, volume))) {
            val fx = when (type) {
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

    override fun onTranscribing() { keyboard?.dictation = DictationPhase.TRANSCRIBING; updateWorking() }

    override fun onDictated(text: String) {
        keyboard?.dictation = DictationPhase.OFF
        updateWorking()
        if (text.isEmpty()) return
        toolsHeld = false
        finishTyping()
        val ic = currentInputConnection ?: return
        // A space before the words where they follow other words, none where they start a line.
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val lead = if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'`") " " else ""
        editor.insert(lead + text, Kind.DICTATION)
        lastCorrection = null
    }

    override fun onError(message: String) {
        keyboard?.dictation = DictationPhase.OFF
        updateWorking()
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        finishTyping()
        flushDraft()
        runCatching { TypingStats.flush(this) }
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
            listOf(Plugins.SWIPE, Plugins.PASSWORDS, Plugins.GIFS, Plugins.MARKDOWN, Plugins.TERMINAL, Plugins.CLIPBOARD, Plugins.UNDO, Plugins.INCOGNITO, Plugins.MOCKINGBIRD, Plugins.GHOSTWRITER, Plugins.REWRITE, Plugins.SHERLOCK)
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
        updateWorking()
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
            items += RowItem("clips-back", icon = RowIcon.BACK, description = "Back") { showingClips = false; scheduleRow() }
            val clips = ClipHistory.all(this)
            if (clips.isEmpty()) items += RowItem("clips-none", label = "Nothing copied yet")
            for (clip in clips) items += RowItem("clip-${clip.at}", label = preview(clip.text), chip = true) { pasteClip(clip.text) }
            if (clips.isNotEmpty()) items += RowItem("clips-clear", icon = RowIcon.TRASH, chip = true, description = "Clear clipboard history") { ClipHistory.clear(this); scheduleRow() }
            strip.setItems(items)
            return
        }
        if (showingRewrite) {
            items += RowItem("rw-back", icon = RowIcon.BACK) { showingRewrite = false; scheduleRow() }
            for (mode in Rewrite.modes(prefs)) items += RowItem("rw-${mode.id}-${mode.label}", label = mode.label, chip = true) { rewriteWith(mode) }
            strip.setItems(items)
            return
        }
        if (aiRow(items)) { strip.setItems(items); return }
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
        if (!toolsHeld) currentInputConnection?.let { ic -> if (selStart == selEnd && contextual(ic, items)) { strip.setItems(items); return } }
        if (!toolsHeld && Plugins.isOn(prefs, Plugins.AUTOCORRECT) && prefs.suggestions && autocorrectAllowed()) {
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
                        items += RowItem("se-$shown", label = shown, wide = true) { editor.insert(" $shown", Kind.EMOJI); Emoji.used(this, shown); scheduleRow() }
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
        // Sherlock: your reply to the chat on the screen, drafted in your voice (from what's in the box, if anything).
        if (Plugins.isOn(prefs, Plugins.SHERLOCK) && aiAllowed() && selStart == selEnd) {
            items += RowItem("sherlock", label = "Reply", icon = RowIcon.SEARCH, tint = RowTint.AI, bold = app == AppKind.CHAT, chip = true) { startSherlock(fresh = true) }
        }
        // Mockingbird: the selection rewritten as you.
        if (Plugins.isOn(prefs, Plugins.MOCKINGBIRD) && aiAllowed() && selStart != selEnd) {
            currentInputConnection?.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let { selected ->
                items += RowItem("voice", label = "In my voice", icon = RowIcon.FEATHER, tint = RowTint.AI, bold = true, chip = true) { rewriteSelection(selected) }
            }
        }
        // Rewrite: the selection, or the whole field when nothing is selected, fixed, shortened, restyled or translated.
        if (Plugins.isOn(prefs, Plugins.REWRITE) && aiAllowed() && fieldHasText()) {
            items += RowItem("rewrite", label = "Rewrite", icon = RowIcon.WAND, tint = RowTint.AI, bold = selStart != selEnd, chip = true) { showingRewrite = true; scheduleRow() }
        }
        // Selected text in another app: to Ghost.md as a note.
        if (ghost && !inGhost && selStart != selEnd) {
            currentInputConnection?.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let { selected ->
                items += RowItem("ghost-save", label = "Save to Ghost.md", icon = RowIcon.GHOST, tint = RowTint.GHOST, bold = true, chip = true) {
                    if (!GhostMd.save(this, selected)) android.widget.Toast.makeText(this, "Ghost.md isn’t on this phone", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        if (ghost && inGhost) {
            items += RowItem("g-link", icon = RowIcon.WIKI, tint = RowTint.GHOST, chip = true, description = "Link to a note") { insertSnippet("[[¦]]") }
            items += RowItem("g-tag", icon = RowIcon.HASH, tint = RowTint.GHOST, chip = true, description = "Tag") { insertSnippet("#") }
            items += RowItem("g-task", icon = RowIcon.TASK, tint = RowTint.GHOST, chip = true, description = "Task") { linePrefix("- [ ] ") }
            items += RowItem("g-today", icon = RowIcon.TODAY, tint = RowTint.GHOST, chip = true, description = "Link to today") { insertSnippet("[[" + java.time.LocalDate.now() + "]]") }
            items += RowItem("g-callout", icon = RowIcon.CALLOUT, tint = RowTint.GHOST, chip = true, description = "Callout") { insertSnippet("> [!note] ¦") }
            items += RowItem("g-table", icon = RowIcon.TABLE, tint = RowTint.GHOST, chip = true, description = "Table") { insertSnippet("| ¦ |  |\n| --- | --- |\n|  |  |") }
            items += RowItem("g-math", icon = RowIcon.MATH, tint = RowTint.GHOST, chip = true, description = "Math") { insertSnippet("$$\n¦\n$$") }
            items += RowItem("g-diagram", icon = RowIcon.DIAGRAM, tint = RowTint.GHOST, chip = true, description = "Diagram") { insertSnippet("```mermaid\n¦\n```") }
            items += RowItem("g-divider", icon = RowIcon.DIVIDER, tint = RowTint.GHOST, chip = true, description = "Divider") { insertSnippet("\n---\n") }
            items += RowItem("g-voice", icon = RowIcon.MIC, tint = RowTint.GHOST, chip = true, description = "Voice note") { GhostMd.voice(this) }
        }
        if (Plugins.isOn(prefs, Plugins.INCOGNITO)) {
            items += RowItem("incognito-$incognito", label = if (incognito) "Incognito on" else null, icon = RowIcon.INCOGNITO, bold = incognito, chip = true, description = "Incognito") {
                incognitoOn = !incognitoOn; scheduleRow()
            }
        }
        if (Plugins.isOn(prefs, Plugins.CLIPBOARD) && !incognito) {
            ClipHistory.fresh?.takeIf { System.currentTimeMillis() - it.at < 90_000 }?.let { clip ->
                items += RowItem("fresh-${clip.at}", label = preview(clip.text), icon = RowIcon.CLIPBOARD, tint = RowTint.CLIPBOARD, bold = true, chip = true) { pasteClip(clip.text) }
            }
        }
        // An app's profile decides for that app; without one, the plugin's own rule (the apps it suits, or everywhere).
        if (profile?.flag("terminal") ?: (Plugins.isOn(prefs, Plugins.TERMINAL) && (prefs.terminalEverywhere || app == AppKind.TERMINAL))) {
            items += RowItem("esc", label = "Esc", chip = true) { sendKey(KeyEvent.KEYCODE_ESCAPE) }
            items += RowItem("tab", label = "Tab", chip = true) { tab() }
            items += RowItem("ctrl-$ctrlOn", label = "Ctrl", bold = ctrlOn, chip = true) { ctrlOn = !ctrlOn; scheduleRow() }
            items += RowItem("alt-$altOn", label = "Alt", bold = altOn, chip = true) { altOn = !altOn; scheduleRow() }
            items += RowItem("left", icon = RowIcon.CARET_LEFT, chip = true, description = "Left") { sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            items += RowItem("up", icon = RowIcon.CARET_UP, chip = true, description = "Up") { sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            items += RowItem("down", icon = RowIcon.CARET_DOWN, chip = true, description = "Down") { sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            items += RowItem("right", icon = RowIcon.CARET_RIGHT, chip = true, description = "Right") { sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            for (c in listOf("|", "~", "/", "-", "&&", ">")) items += RowItem("t$c", label = c, chip = true) { editor.type(c) }
        }
        if (profile?.flag("markdown") ?: (Plugins.isOn(prefs, Plugins.MARKDOWN) && (prefs.markdownEverywhere || app == AppKind.MARKDOWN))) {
            items += RowItem("md-b", icon = RowIcon.BOLD, chip = true, description = "Bold") { wrap("**", "**") }
            items += RowItem("md-i", icon = RowIcon.ITALIC, chip = true, description = "Italic") { wrap("_", "_") }
            items += RowItem("md-s", icon = RowIcon.STRIKE, chip = true, description = "Strikethrough") { wrap("~~", "~~") }
            items += RowItem("md-code", icon = RowIcon.CODE, chip = true, description = "Code") { wrap("`", "`") }
            items += RowItem("md-block", icon = RowIcon.CODE_BLOCK, chip = true, description = "Code block") { wrap("```\n", "\n```") }
            items += RowItem("md-link", icon = RowIcon.LINK, chip = true, description = "Link") { link() }
            items += RowItem("md-h", icon = RowIcon.HEADING, chip = true, description = "Heading") { linePrefix("# ") }
            items += RowItem("md-list", icon = RowIcon.LIST, chip = true, description = "List") { linePrefix("- ") }
            items += RowItem("md-task", icon = RowIcon.TASK, chip = true, description = "Task") { linePrefix("- [ ] ") }
            items += RowItem("md-quote", icon = RowIcon.QUOTE, chip = true, description = "Quote") { linePrefix("> ") }
            // Ghost.md's own: a link to another note.
            if (!ghost && inGhost) items += RowItem("md-wiki", icon = RowIcon.WIKI, chip = true, description = "Link to a note") { wrap("[[", "]]") }
        }
        if (Plugins.isOn(prefs, Plugins.UNDO)) {
            // Lit: the keyboard's own last step, or the app's undo where it has none. Dim: nothing to do.
            val canUndo = editor.canUndo
            val canRedo = editor.canRedo
            items += RowItem("undo", icon = RowIcon.UNDO, chip = true, description = "Undo", dim = !canUndo, onTap = if (canUndo) ({ undoTap(redo = false) }) else null)
            items += RowItem("redo", icon = RowIcon.REDO, chip = true, description = "Redo", dim = !canRedo, onTap = if (canRedo) ({ undoTap(redo = true) }) else null)
        }
        if (Plugins.isOn(prefs, Plugins.CLIPBOARD) && !incognito) items += RowItem("clips", icon = RowIcon.CLIPBOARD, tint = RowTint.CLIPBOARD, chip = true, description = "Clipboard") { showingClips = true; scheduleRow() }
        if (Plugins.isOn(prefs, Plugins.EMOJI)) items += RowItem("emoji", icon = RowIcon.EMOJI, tint = RowTint.EMOJI, chip = true, description = "Emoji") { openEmoji() }
        if (Plugins.isOn(prefs, Plugins.GIFS)) items += RowItem("gifs", icon = RowIcon.GIF_TEXT, tint = RowTint.GIFS, chip = true, description = "GIFs") { openGifs() }
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
        finishTyping()
        editor.insert(text, Kind.PASTE)
        toolsHeld = true
        ClipHistory.fresh = null
        showingClips = false
        scheduleRow()
    }

    /** A key press with modifiers, as a hardware keyboard sends it: what terminals and undo listen for. */
    private fun sendKey(code: Int, meta: Int = 0) {
        if (!pressKey(code, meta)) return
        // What a key press does is the app's business: the step being typed is over, and the field is checked after.
        editor.sent()
    }

    /** [code] down and up with [meta], and nothing told to the history. False with no field to send it to. */
    private fun pressKey(code: Int, meta: Int = 0): Boolean {
        val ic = currentInputConnection ?: return false
        val now = android.os.SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
        return true
    }

    /**
     * The terminal row's Tab: a key press, which a terminal reads as one. In an ordinary field it types a tab
     * character, and that is a step in the history like any other typing (the editor sees what the key did, as it does
     * for Enter). Esc, the arrows and Ctrl or Alt with a letter stay key presses whose effect is the app's: the field is
     * checked after them, and a history they made stale is dropped.
     */
    private fun tab() {
        if (currentInputConnection == null) return
        finishTyping()
        lastCorrection = null
        lastSwipe = null
        editor.tab { pressKey(KeyEvent.KEYCODE_TAB) }
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
        finishTyping()
        editor.replaceSelection(Kind.WRAP) { selected -> Pair(open + selected + close, if (selected.isEmpty()) close.length else 0) }
    }

    /** A link: the selection becomes its words, and the cursor waits where the address goes. */
    private fun link() {
        finishTyping()
        editor.replaceSelection(Kind.WRAP) { selected -> if (selected.isEmpty()) Pair("[]()", 3) else Pair("[$selected]()", 1) }
    }

    /** [prefix] at the start of the cursor's line (a heading, a list item, a quote). */
    private fun linePrefix(prefix: String) {
        finishTyping()
        editor.insertAtLineStart(prefix)
    }

    /**
     * What the text just before the cursor asks for, before suggestions: a :shortcode's emoji, and in Ghost.md a
     * [[link]]'s note titles or a #tag's tags. True when it filled [items].
     */
    private fun contextual(ic: InputConnection, items: ArrayList<RowItem>): Boolean {
        val before = ic.getTextBeforeCursor(200, 0)?.toString() ?: return false
        if (Plugins.isOn(prefs, Plugins.GHOSTWRITER)) {
            val after = ic.getTextAfterCursor(400, 0)?.toString().orEmpty()
            val tag = Ghostwriter.tagAt(before, after)
            // Never silent: in a private field (a password, incognito, or an app that asks keyboards not to learn) say why.
            if (!aiAllowed()) {
                if (tag != null || FILL_OPEN.containsMatchIn(before)) {
                    items += RowItem("fill-private", label = "Ghostwriter is off in private fields", icon = RowIcon.SPARKLE, chip = true, fill = true)
                    return true
                }
                return false
            }
            tag?.let { (request, _) ->
                items += RowItem("fill-$request", label = "Fill: ${preview(request)}", icon = RowIcon.SPARKLE, tint = RowTint.AI, bold = true, chip = true, fill = true) { fillAtCursor() }
                return true
            }
            FILL_OPEN.find(before)?.let { m ->
                val request = m.groupValues[1].trim()
                items += RowItem("fill-open", label = if (request.length < 2) "Ghostwriter: say what to write, then >>" else "Fill: ${preview(request)}", icon = RowIcon.SPARKLE, tint = RowTint.AI, chip = true, fill = true) {
                    if (request.length >= 2) { editor.type(">>"); fillAtCursor() }
                }
                return true
            }
        }
        if (Plugins.isOn(prefs, Plugins.EMOJI)) {
            SHORTCODE.find(before)?.let { m ->
                val code = m.groupValues[1]
                val found = Emoji.search(code, 30)
                if (found.isNotEmpty()) {
                    found.forEach { e ->
                        val shown = e.toned(prefs.emojiSkin)
                        items += RowItem("sc-$shown", label = shown) { replaceBefore(":$code", shown, Kind.EMOJI); Emoji.used(this, shown) }
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

    // --- Ghostwriter and Mockingbird ---------------------------------------------------------------------------------

    /** The AI may read this field: not a password, not incognito. */
    private fun aiAllowed(): Boolean = !incognito && !isPassword(currentInputEditorInfo)

    /** A <<request>> just closed before the cursor: fill it, where Ghostwriter fills on its own. */
    private fun checkFill() {
        if (!Plugins.isOn(prefs, Plugins.GHOSTWRITER) || !prefs.ghostwriterAuto || !aiAllowed()) return
        if (aiJob?.running == true) return
        val before = currentInputConnection?.getTextBeforeCursor(2000, 0)?.toString() ?: return
        if (Ghostwriter.CLOSED.containsMatchIn(before)) fillAtCursor()
    }

    /** Fills the <<request>> the cursor is on (or just after). */
    private fun fillAtCursor() {
        val ic = currentInputConnection ?: return
        if (!aiAllowed()) return
        val before = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(400, 0)?.toString().orEmpty()
        val (request, tag) = Ghostwriter.tagAt(before, after) ?: return
        val at = (before + after).lastIndexOf(tag, before.length - 1)
        val around = (before + after)
        aiJob?.cancelled = true
        aiJob = Ghostwriter.start(this, prefs, request, tag, around.substring(0, at.coerceAtLeast(0)), around.substring((at + tag.length).coerceAtMost(around.length)), currentInputEditorInfo?.packageName) { onAiDone(it) }
        scheduleRow()
    }

    /** Sherlock on its own: as the keyboard opens, empty, in a chat app, once the app has settled under it. */
    private fun autoSherlock(info: EditorInfo) {
        if (!Plugins.isOn(prefs, Plugins.SHERLOCK) || !prefs.sherlockAuto || !aiAllowed()) return
        if (AppContext.kind(info.packageName) != AppKind.CHAT || SherlockService.instance == null) return
        rowHandler.postDelayed({
            if (currentInputEditorInfo?.packageName == info.packageName && aiJob == null && !fieldHasText()) startSherlock(fresh = false)
        }, SHERLOCK_SETTLE_MS)
    }

    /** Sherlock: reads the chat and drafts the reply; what is in the box already is what you want to say. */
    private fun startSherlock(fresh: Boolean) {
        if (!aiAllowed()) return
        val ic = currentInputConnection
        val hint = if (ic == null) "" else (ic.getTextBeforeCursor(1000, 0)?.toString().orEmpty() + ic.getTextAfterCursor(1000, 0)?.toString().orEmpty())
        aiJob?.cancelled = true
        // Where the keyboard starts on the screen, so Sherlock reads only the app above it.
        val top = keyboard?.let { v -> IntArray(2).also { v.getLocationOnScreen(it) }[1] + v.keyboardTop.toInt() } ?: 0
        aiJob = Ghostwriter.sherlock(this, prefs, hint, currentInputEditorInfo?.packageName, fresh, top) { onAiDone(it) }
        scheduleRow()
    }

    /** Sherlock's draft into the box, in place of whatever was there (your hint); one step, so undo puts yours back. */
    private fun placeSherlock(text: String): Boolean {
        val ic = currentInputConnection ?: return false
        finishTyping()
        val typeOut = prefs.ghostwriterTyping
        val placed = if (fieldHasText()) {
            val was = editor.selectionNow()
            ic.performContextMenuAction(android.R.id.selectAll)
            editor.sent()
            val all = ic.getSelectedText(0)?.toString().orEmpty()
            editor.rememberRewrite(all, was)
            all.isNotEmpty() && editor.beginFill(0, 0, all, selection = true, full = text, typeOut = typeOut)
        } else editor.beginFill(0, 0, "", selection = false, full = text, typeOut = typeOut)
        if (!placed) return false
        if (typeOut) startTyping(text)
        remember(text)
        return true
    }

    private fun rewriteSelection(selected: String) {
        aiJob?.cancelled = true
        aiJob = Ghostwriter.rewrite(this, prefs, selected, currentInputEditorInfo?.packageName) { onAiDone(it) }
        scheduleRow()
    }

    /** The Rewrite plugin: [mode] done to the selection, or to everything in the field when nothing is selected. */
    private fun rewriteWith(mode: Rewrite.Mode) {
        showingRewrite = false
        val ic = currentInputConnection
        if (ic == null || !aiAllowed()) { scheduleRow(); return }
        var selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isBlank()) {
            // The cursor as it was is kept, so undoing the rewrite puts it back and not a whole-field selection.
            val was = editor.selectionNow()
            ic.performContextMenuAction(android.R.id.selectAll)
            editor.sent()
            selected = ic.getSelectedText(0)?.toString().orEmpty()
            editor.rememberRewrite(selected, was)
        }
        if (selected.isBlank()) { scheduleRow(); return }
        startRewrite(selected, mode)
    }

    private fun startRewrite(selected: String, mode: Rewrite.Mode) {
        aiJob?.cancelled = true
        aiJob = Ghostwriter.rewriteAs(prefs, selected, mode) { onAiDone(it) }
        scheduleRow()
    }

    /** There is something in the field to rewrite: text beside the cursor, or a selection. */
    private fun fieldHasText(): Boolean {
        if (selStart != selEnd) return true
        val ic = currentInputConnection ?: return false
        return !ic.getTextBeforeCursor(1, 0).isNullOrEmpty() || !ic.getTextAfterCursor(1, 0).isNullOrEmpty()
    }

    /** An answer came back: it replaces its tag (or the selection) where that is still there; if not, it waits in the row. */
    private fun onAiDone(job: Ghostwriter.Job) {
        if (job !== aiJob) return
        val text = job.result
        if (job.mode == Sherlock.MODE) {
            // The draft waits in the row unless it is to go straight into the box.
            if (text != null && prefs.sherlockFill && placeSherlock(text)) aiJob = null
            scheduleRow()
            return
        }
        if (text != null && placeAnswer(job, text)) aiJob = null
        scheduleRow()
    }

    private fun placeAnswer(job: Ghostwriter.Job, text: String): Boolean {
        val ic = currentInputConnection ?: return false
        // An answer still typing out is finished first, so each answer is its own step in the history.
        finishTyping()
        val typeOut = prefs.ghostwriterTyping
        if (job.selection) {
            if (!editor.beginFill(0, 0, job.tag, selection = true, full = text, typeOut = typeOut)) return false
            if (typeOut) startTyping(text)
            remember(text)
            return true
        }
        val before = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(400, 0)?.toString().orEmpty()
        val joined = before + after
        // The last copy of the tag that starts before the cursor; the cursor must be inside it or at its end, or the
        // person has typed on past it and the answer waits in the row instead.
        val at = joined.lastIndexOf(job.tag, before.length - 1)
        if (at < 0) return false
        val end = at + job.tag.length
        if (end < before.length) return false
        // The tag goes and the answer comes as one step, however long it takes to type out.
        if (!editor.beginFill(before.length - at, (end - before.length).coerceAtLeast(0), job.tag, selection = false, full = text, typeOut = typeOut)) return false
        if (typeOut) startTyping(text)
        remember(text)
        return true
    }

    private fun remember(text: String) {
        aiAnswers.addFirst(text)
        while (aiAnswers.size > 6) aiAnswers.removeLast()
    }

    /** An arrow key in the row: the cursor moves as a real arrow key moves it, with the key's haptic and sound. */
    private fun arrow(code: Int) {
        toolsHeld = false
        finishTyping()
        wordTouches.clear(); takenTouch = null; lastSwipe = null; lastCorrection = null
        keyFeedback(KeyType.CHAR)
        sendKey(code)
    }

    // --- adaptive keys -----------------------------------------------------------------------------------------------

    private fun adaptiveOn() = Plugins.isOn(prefs, Plugins.ADAPTIVE) && !incognito && !isPassword(currentInputEditorInfo)

    /** Two letters whose keys touch on the letters page. */
    private fun neighbours(a: Char, b: Char): Boolean {
        val ra = keyboard?.letterRect(a) ?: return false
        val rb = keyboard?.letterRect(b) ?: return false
        return a.lowercaseChar() != b.lowercaseChar() && kotlin.math.abs(ra.centerX() - rb.centerX()) < ra.width() * 1.6f && kotlin.math.abs(ra.centerY() - rb.centerY()) < ra.height() * 1.6f
    }

    /** A touch on [pressed] meant for [meant]: measured against [meant]'s key, and placed on the heatmap. */
    private fun learnMistake(t: KeyboardView.Typed, meant: Char) {
        // A touch in the middle of the key it typed was aimed there: taken back, it is a change of mind, not a slip.
        if (kotlin.math.abs(t.x - t.rect.centerX()) < t.rect.width() * 0.2f && kotlin.math.abs(t.y - t.rect.centerY()) < t.rect.height() * 0.2f) return
        val r = keyboard?.letterRect(meant) ?: return
        val (fx, fy) = keyboard?.keysFraction(t.x, t.y) ?: return
        AdaptiveKeys.get(this).mistake(t.c, meant, (t.x - r.centerX()) / r.width(), (t.y - r.centerY()) / r.height(), fx, fy)
    }

    /**
     * After [text] is typed: a letter from a finger joins the word's touches. A letter typed straight after
     * backspace took a neighbouring one away says that touch was meant for this letter.
     */
    private fun adaptiveTyped(text: String) {
        val typed = keyboard?.takeTyped()
        if (!adaptiveOn()) { wordTouches.clear(); takenTouch = null; return }
        if (text.length != 1 || !text[0].isLetter()) { endWord(); return }
        takenTouch?.let { (t, at) ->
            if (System.currentTimeMillis() - at < 3000 && neighbours(t.c, text[0])) learnMistake(t, text[0])
        }
        takenTouch = null
        wordTouches += typed?.takeIf { it.c.equals(text[0], ignoreCase = true) }
    }

    /** Autocorrect swapped [word] for [fixed]: where a letter became its neighbour, that touch was meant for it. */
    private fun adaptiveCorrected(word: String, fixed: String) {
        if (!adaptiveOn() || word.length != fixed.length || wordTouches.size != word.length) return
        val swaps = word.indices.filter { !word[it].equals(fixed[it], ignoreCase = true) }
        if (swaps.isEmpty() || swaps.size > 2) return
        for (i in swaps) {
            val t = wordTouches[i] ?: continue
            if (!neighbours(word[i], fixed[i])) continue
            learnMistake(t, fixed[i])
            wordTouches[i] = null
        }
    }

    /** The word is over: every touch left standing was right, and teaches its own key where it is aimed at. */
    private fun endWord() {
        if (wordTouches.isEmpty()) return
        if (adaptiveOn()) {
            val model = AdaptiveKeys.get(this)
            for (t in wordTouches) t?.let { model.hit(it.c, (it.x - it.rect.centerX()) / it.rect.width(), (it.y - it.rect.centerY()) / it.rect.height()) }
            model.save()
        }
        wordTouches.clear()
        takenTouch = null
    }

    // --- the AI typing --------------------------------------------------------------------------------------------

    private fun startTyping(text: String) {
        finishTyping()
        typing = Typing(text)
        scheduleRow()
        typeTick()
    }

    /**
     * One beat of the AI typing: a few characters committed and their key pressed on the board, then a pause like a
     * typist's: longer after a sentence, a comma or a line. Long answers move more characters a beat, so even a long
     * one is done in about five seconds.
     */
    private fun typeTick() {
        val t = typing ?: return
        val ic = currentInputConnection ?: run { typing = null; return }
        if (t.at >= t.text.length) { typing = null; editor.endFill(); scheduleRow(); return }
        val per = (t.text.length / 150 + 1).coerceAtMost(6)
        var to = (t.at + per).coerceAtMost(t.text.length)
        // Never half an emoji: a beat that would end between the two halves of a surrogate pair takes both.
        if (to < t.text.length && Character.isHighSurrogate(t.text[to - 1])) to++
        val chunk = t.text.substring(t.at, to)
        t.at += chunk.length
        editor.appendFill(chunk)
        keyboard?.ghostPress(chunk.last(), holdMs = 60)
        val last = chunk.last()
        // The key's haptic and sound, where Settings has them on, as if a finger pressed it.
        keyFeedback(when (last) { ' ' -> KeyType.SPACE; '\n' -> KeyType.ENTER; else -> KeyType.CHAR })
        val pause = when (last) {
            '.', '!', '?', '\n' -> 140L
            ',', ';', ':' -> 70L
            ' ' -> 34L
            else -> 22L
        } + (Math.random() * 18).toLong()
        typeHandler.postDelayed(typeTask, pause)
    }

    /** Writes what is left at once: the person pressed a key, or left the field. */
    private fun finishTyping() {
        val t = typing ?: return
        typing = null
        typeHandler.removeCallbacks(typeTask)
        if (t.at < t.text.length) editor.appendFill(t.text.substring(t.at))
        editor.endFill()
        scheduleRow()
    }

    /**
     * The line along the top of the keys runs while something works for the person: the AI writing, rewriting or
     * typing out an answer, or dictation turning speech into words. Every change to those comes through the row's
     * refresh or dictation's callbacks, which call this.
     */
    private fun updateWorking() {
        frame?.working?.on = typing != null || aiJob?.running == true || keyboard?.dictation == DictationPhase.TRANSCRIBING
    }

    /** What the row says the AI is doing for [job]: writing the request, rewriting as asked, or in the person's voice. */
    private fun doing(job: Ghostwriter.Job): String {
        if (job.mode == Sherlock.MODE) return "Reading the chat…"
        Rewrite.mode(prefs, job.mode)?.let { return "${it.label}…" }
        if (job.selection) return "Rewriting in your voice…"
        return "Writing \u201C${preview(job.request)}\u201D…"
    }

    /** The row while an answer is on its way, failed, or couldn't be placed. True when it filled [items]. */
    private fun aiRow(items: ArrayList<RowItem>): Boolean {
        if (typing != null) {
            items += RowItem("ai-typing", label = "Typing it out…", busy = true, fill = true)
            items += RowItem("ai-skip", label = "Skip", icon = RowIcon.FORWARD, bold = true, chip = true) { finishTyping() }
            return true
        }
        val job = aiJob ?: return false
        if (job.cancelled) { aiJob = null; return false }
        val dismiss = RowItem("ai-x-${job.id}", icon = RowIcon.CLOSE, chip = true, description = if (job.running) "Stop" else "Dismiss") { job.cancelled = true; aiJob = null; scheduleRow() }
        when {
            job.running -> {
                items += RowItem("ai-run-${job.id}", label = doing(job), busy = true, fill = true)
                items += dismiss
            }
            job.error != null -> {
                items += RowItem("ai-err-${job.id}", label = job.error, icon = RowIcon.WARNING, tint = RowTint.ERROR, chip = true, fill = true) {
                    if (job.error == Sherlock.NOT_ON) SherlockService.openSettings(this) else onOpenSettings()
                }
                items += RowItem("ai-retry-${job.id}", label = "Retry", icon = RowIcon.RETRY, bold = true, chip = true) {
                    aiJob = null
                    val mode = Rewrite.mode(prefs, job.mode)
                    if (job.mode == Sherlock.MODE) startSherlock(fresh = true) else if (mode != null) startRewrite(job.tag, mode) else if (job.selection) rewriteSelection(job.tag) else fillAtCursor()
                    scheduleRow()
                }
                items += dismiss
            }
            job.mode == Sherlock.MODE -> {
                val text = job.result.orEmpty()
                items += RowItem("sh-put-${job.id}", label = text.replace(Regex("\\s+"), " ").trim().take(200), icon = RowIcon.SEARCH, tint = RowTint.AI, bold = true, chip = true, fill = true, description = "Put this reply in the box") {
                    if (placeSherlock(text)) aiJob = null; scheduleRow()
                }
                items += RowItem("sh-again-${job.id}", label = "Another", icon = RowIcon.RETRY, chip = true) { startSherlock(fresh = true) }
                items += dismiss
            }
            else -> {
                val text = job.result.orEmpty()
                items += RowItem("ai-put-${job.id}", label = "Insert: ${preview(text)}", icon = RowIcon.CHECK, tint = RowTint.DONE, bold = true, chip = true, fill = true) {
                    editor.insert(text, Kind.AI); aiJob = null; scheduleRow()
                }
                items += dismiss
            }
        }
        return true
    }

    /** A field whose text says how you write: not passwords, addresses, numbers, searches or this app's own fields. */
    private fun collectable(info: EditorInfo?): Boolean {
        if (info == null || info.packageName == packageName) return false
        if ((info.inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER, InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS, InputType.TYPE_TEXT_VARIATION_PERSON_NAME,
            InputType.TYPE_TEXT_VARIATION_PHONETIC,
        )
    }

    /** Reads the field's text for Mockingbird, where it may: at word and sentence ends, and before enter. */
    private fun snapshotDraft() {
        if (!Plugins.isOn(prefs, Plugins.MOCKINGBIRD) || !prefs.mockingbirdCollect || incognito) { draft = ""; return }
        val info = currentInputEditorInfo
        if (!collectable(info)) { draft = ""; return }
        val ic = currentInputConnection ?: return
        draft = (ic.getTextBeforeCursor(1500, 0)?.toString().orEmpty() + ic.getTextAfterCursor(500, 0)?.toString().orEmpty())
        draftApp = info?.packageName
    }

    /**
     * Keeps the draft as a sample (Mockingbird decides if it is worth keeping), then lets training catch up. Training
     * is asked whether or not there was a draft: with "Keep samples of what I type" off there never is one, and a
     * profile from before the style rules improved is still due its retrain.
     */
    private fun flushDraft() {
        var text = draft
        draft = ""
        if (incognito || !Plugins.isOn(prefs, Plugins.MOCKINGBIRD)) return
        for (answer in aiAnswers) text = text.replace(answer, " ")
        if (text.isNotBlank()) Mockingbird.record(this, text, draftApp.orEmpty())
        Mockingbird.maybeAutoTrain(this, prefs)
    }

    /** Replaces [old], just before the cursor, with [new]; where [old] is not there, [new] is typed all the same. */
    private fun replaceBefore(old: String, new: String, kind: Kind = Kind.SNIPPET) {
        finishTyping()
        editor.replaceBefore(old, new, kind, must = false)
        scheduleRow()
    }

    /** A snippet with a ¦ where the cursor goes. */
    private fun insertSnippet(snippet: String) {
        finishTyping()
        val caret = snippet.indexOf('¦')
        val text = snippet.replace("¦", "")
        editor.insert(text, Kind.SNIPPET, caretBack = if (caret >= 0) text.length - caret else 0)
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
        finishTyping()
        editor.insert(emoji, Kind.EMOJI)
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
        if (!editor.replaceBefore(typed, "$word ", Kind.SUGGESTION)) { scheduleRow(); return }
        lastCorrection = null
        lastSpaceAt = System.currentTimeMillis()
        scheduleRow()
    }

    /** The typed word tapped in quotes: keep it as it is, learn it, and finish it with a space. */
    private fun keepTyped(word: String) {
        val ic = currentInputConnection ?: return
        if (!incognito) { prefs.learn(word); autocorrect.setLearned(prefs.learnedWords) }
        editor.type(" ")
        lastCorrection = null
        lastSpaceAt = System.currentTimeMillis()
        scheduleRow()
    }

    // --- swipe typing (the swipe plugin) ----------------------------------------------------------------------------

    override fun onSwipe(path: List<android.graphics.PointF>, keys: Map<Char, android.graphics.PointF>, keyWidth: Float) {
        wordTouches.clear(); takenTouch = null
        toolsHeld = false
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
        finishTyping()
        editor.insert(lead + words[0], Kind.SWIPE)
        stats { TypingStats.swiped(this, words[0]) }
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
        // The swapped word stays the one step the swipe was.
        if (!editor.replaceBefore(tail, swipe.lead + word, Kind.SWIPE)) { lastSwipe = null; scheduleRow(); return }
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
        Giphy.search(prefs, query) { gifs, reason ->
            if (gifMode == GifMode.OFF || query != gifQuery) return@search
            if (gifs == null) panel.showMessage(reason ?: "Couldn’t reach GIPHY") else panel.show(gifs)
        }
    }

    /** Sends [gif]: as the picture itself where the app takes GIFs, else as its link. */
    private fun pickGif(gif: Gif) {
        gifPanel?.showMessage("Sending…")
        Giphy.load(gif.full) { bytes ->
            val ic = currentInputConnection
            val info = currentInputEditorInfo
            if (ic == null || info == null) { closeGifs(); return@load }
            // The download took a while: an answer that began typing out meanwhile is finished first, and stays one step.
            finishTyping()
            val takesGifs = info.contentMimeTypes?.any { ClipDescription.compareMimeTypes("image/gif", it) } == true
            if (bytes != null && takesGifs) {
                val uri = Giphy.save(this, gif, bytes)
                val content = InputContentInfo(uri, ClipDescription("GIF", arrayOf("image/gif")), Uri.parse(gif.pageUrl.ifEmpty { gif.full }))
                // A GIF sent as a picture is the app's to take back; its link is text, and a step.
                if (ic.commitContent(content, InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)) editor.sent() else editor.insert(gif.full, Kind.GIF)
            } else {
                editor.insert(gif.full, Kind.GIF)
            }
            closeGifs()
        }
    }

    companion object {
        /** How long Sherlock waits after the keyboard opens before reading the chat: the app moves its messages up first. */
        private const val SHERLOCK_SETTLE_MS = 700L
        /** Punctuation that finishes a word, and so runs autocorrect on it before it is typed. */
        private const val WORD_ENDS = ".,!?"

        /** ":smi" just typed, after a space or at the start: the emoji plugin's shortcode search. */
        private val SHORTCODE = Regex("(?:^|\\s):([a-z0-9_+-]{2,30})$")
        /** "[[Not" just typed in Ghost.md: the start of a link to a note. */
        private val WIKI_OPEN = Regex("\\[\\[([^\\[\\]\\n|]{0,60})$")
        /** "<<standup tl" being typed: a Ghostwriter request not yet closed. */
        private val FILL_OPEN = Regex("<<([^<>\\n]{0,400})$")
        /** "#ide" just typed in Ghost.md, after a space or at a line's start: a tag. */
        private val TAG_OPEN = Regex("(?:^|\\s)#([\\p{L}\\p{N}_/-]{1,40})$")

        /** What a pair key closes with: typed in front of the same character, it steps over it. */
        private const val CLOSERS = ")]}>\"'`"
    }
}
