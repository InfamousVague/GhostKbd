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
    /** The row's Update chip follows the updater: there when a check finds a release, gone once it is installed. */
    private val onUpdate: (Updater.State) -> Unit = { showUpdateBadge() }
    private var updateWaiting = false

    /**
     * Settings › Offline, as last read ([applySettings] reads it on every settings change and each time the keyboard
     * shows): the row and the enter key ask this field, so a key press never waits on a setting.
     */
    private var offlineOn = false

    /** Until when (uptime) the row says "Offline is on" in place of its tools: a network chip was tapped while it is. */
    private var offlineNoteUntil = 0L
    private val offlineNoteDone = Runnable { offlineNoteUntil = 0L; scheduleRow() }

    /** The languages that are on and the layout being typed on, as [applySettings] last worked them out (Languages.kt). */
    private var language: LanguageState = LanguageState.of(listOf("en"), emptyMap(), "")
        set(value) { field = value; joiners = value.joiners; locale = value.locale; learner.locale = value.locale; learner.ends = sentenceEnds() }

    /** The active layout's joiners and whose capitals it types, kept from [language] so a look at the text makes neither again for every character. */
    private var joiners = "'"
    private var locale: java.util.Locale = java.util.Locale.ROOT

    /** A word list that was being read is in use: swipe typing may take a finger's path again, and the row has words to offer. */
    private val wordsLoaded = Runnable { keyboard?.swipeTyping = swipeAllowed(); scheduleRow() }

    /**
     * This field wants Latin letters (a password, an e-mail address, a web address), so a board of another script steps
     * aside for the first Latin one while it is typed in. Choosing a layout by hand in the field ends that.
     */
    private var latinField = false

    /** Korean: the syllable block being built just before the cursor (Hangul.kt). */
    private var hangul = Hangul.State()

    /** The last autocorrection, while backspace can still undo it: the word as typed, what replaced it, and the separator. */
    private var lastCorrection: Triple<String, String, String>? = null

    /**
     * The text shortcut a space has just put in (Shortcuts.kt), while backspace can still take it back: the letters
     * as typed, what they became, and the separator. The very next key decides: backspace puts the letters back, and
     * anything else lets the phrase stand.
     */
    private var lastShortcut: Triple<String, String, String>? = null

    /**
     * What the dictation that has just ended put in the field, exactly as it went in (every piece, with the spaces
     * between), while it still stands just before the cursor and no key has been pressed since: the row then offers
     * to tidy it, or to make a prompt of it. [saying] is the same for the take still going on.
     */
    private var said: String? = null
    private val saying = StringBuilder()

    /**
     * A dictation take is open or still being written down, and the field it was started in: its words are for that
     * field alone. In any other field, or once that one is gone, what is not yet written down is let go ([dropTake])
     * rather than typed where it was not said.
     */
    private var takeOpen = false
    private var takeField: FieldKey? = null

    /** The { } button is in the row above the keys, where the emoji button was: the emoji key has the { } key's place on the board ([applySettings]). */
    private var codeInRow = false

    /**
     * The draft of the piece being spoken, as it stands in the field just before the cursor ([onDraft]), with the
     * space that parts it from what came before ([liveLead]); null between pieces. [liveLost]: the draft was no
     * longer there to be revised (the cursor was moved, or the app changed its text), so it stands as it is and no
     * more is shown of this piece until it is written down.
     */
    private var liveDraft: String? = null
    private var liveLead = ""
    private var liveLost = false

    /**
     * A shortcut's letters that were just said to be meant as typed (their own chip tapped, or their phrase taken
     * back with backspace): the next space leaves them alone, once.
     */
    private var keptShortcut: String? = null

    // --- next-word prediction (NextWords.kt, NextWordModel.kt) -------------------------------------------------------
    //
    // The key handlers below only set the flags here: nothing of the model runs on a key press. The look at the text
    // that follows each edit ([tick], a moment later, off the key's path) does the counting and the predicting.

    private lateinit var nextWords: NextWords

    /** What is learned from the words that pass under the cursor, and when (NextWordModel.kt): it counts into [nextWords]. */
    private val learner = NextWordLearner(CONTEXT_CHARS) { nextWords.learn(it) }
    /** What the keyboard has learned from the person's own corrections (Mistakes.kt). */
    private lateinit var mistakes: Mistakes

    /**
     * A tap in the row typed a word and a space after it: a full stop or comma typed next takes that space's place.
     * [autoSpaceAt] is where the cursor stood after the tap; moved from there, the space is the person's own again.
     */
    private var autoSpaced = false
    private var autoSpaceAt = -1

    // --- the plugin row and its plugins' state ------------------------------------------------------------------------

    private var row: PluginRow? = null
    private var frame: KeyboardFrame? = null
    private var gifPanel: GifPanel? = null
    private var emojiPanel: EmojiPanel? = null
    /** Where a plugin's panel goes, over the keys: the GIF results or the emoji. */
    private var panelHost: android.widget.FrameLayout? = null
    private val rowHandler = Handler(Looper.getMainLooper())
    private val refreshTask = Runnable { runCatching { tick() }.onFailure { android.util.Log.e("GhostKeyboard", "row refresh failed", it) } }

    /** The passwords plugin: the autofill service's inline suggestions for this field, as views. */
    private var inlineViews: List<View> = emptyList()
    private var inlineGeneration = 0

    /** Swipe typing's last word, while its alternatives are on offer: the space put before it, it, and the others. */
    private class SwipeState(val lead: String, var word: String, val choices: List<String>, val raw: String = word, val over: SwipeGone? = null) {
        /** The word was chosen in the row, not left as it was read. */
        var picked = false
        /** The stroke as an anonymous report would carry it ([ReportTally.pathOf]); null where none is being kept. */
        var path: FloatArray? = null
    }
    private var lastSwipe: SwipeState? = null

    /** A swiped word deleted whole a moment ago: what the decoder read it as, what else it offered, and where it stood. */
    private class SwipeGone(val raw: String, val choices: List<String>, val head: String, val whenMs: Long, val field: EditorInfo?, val path: FloatArray? = null)
    private var swipeGone: SwipeGone? = null

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

    /**
     * With [toolsHeld] after an arrow: the row was suggesting words when the arrow was tapped, the arrow in view at its
     * end, and goes on suggesting them (or keeps their room, where there are none here) so the arrow stays under the
     * thumb. A row that jumped to its tools there put Undo at its other end, and the next tap on whatever tool stood
     * where Undo had been. Read only while [toolsHeld] is set, and set afresh wherever that is.
     */
    private var heldWords = false

    /** Mockingbird: the field's text as last read, kept as a sample once it is sent or left. */
    private var draft = ""
    private var draftApp: String? = null

    /** The incognito plugin's switch, and the field asking for no learning (EditorInfo's flag) either way. */
    private var incognitoOn = false
    private val incognito: Boolean get() =
        (Plugins.isOn(prefs, Plugins.INCOGNITO) && incognitoOn) || profile?.flag("incognito") == true ||
            (currentInputEditorInfo?.let { it.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0 } == true)

    /** A one-time code arrived by text, was typed, or ran out (SmsCodes.kt): the row is drawn again, and once more when the newest stops being fresh. */
    private val codesFresh = Runnable { scheduleRow() }
    private val onSmsCode: () -> Unit = {
        rowHandler.post {
            scheduleRow()
            rowHandler.removeCallbacks(codesFresh)
            rowHandler.postDelayed(codesFresh, SmsCodes.FRESH_MS + 500)
        }
    }

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
        prefs.pinLegacyLayouts()
        // Sherlock was tried and taken out (Matt, 2026-11-10: "Remove the sherlock plugin completely"): what it left on the
        // phone goes too, its record of sent messages and its settings.
        runCatching {
            java.io.File(filesDir, "sherlock_sent.json").delete()
            val gone = prefs.prefs.all.keys.filter { it.startsWith("sherlock_") }
            if (gone.isNotEmpty()) prefs.prefs.edit().apply { gone.forEach { remove(it) } }.apply()
        }
        prefs.prefs.registerOnSharedPreferenceChangeListener(this)
        // The word lists are the app's own assets: nothing here opens a connection.
        val bundled = applicationContext.assets
        autocorrect = Autocorrect { name -> bundled.open(name) }
        language = prefs.languageState()
        // Once a word list is in, what the person types that is "in no list" can be told ([NextWords.vocabulary]).
        autocorrect.onLoaded = { rowHandler.post(wordsLoaded); runCatching { nextWords.refreshOwnWords() } }
        autocorrect.setLanguages(language.dictionaries, language.board, language.lexicons)
        autocorrect.setLearned(prefs.learnedWords)
        autocorrect.setSlang(prefs.slang)
        // Next-word prediction's starter tables are the app's own assets too, and what it learns is a file of its own.
        nextWords = NextWords.get(this)
        learner.isWordChar = ::isWordChar
        nextWords.isWord = { autocorrect.isWord(it) }
        nextWords.commonest = { autocorrect.commonest(6, leadLanguage()) }
        // The person's own words (typed three times or more, in no list) are autocorrect's too: left alone, mended
        // toward, and swiped. Until a word list is in, nothing counts as being in none.
        nextWords.isListed = { !autocorrect.active || autocorrect.isListed(it) }
        nextWords.onVocabulary = { autocorrect.setOwn(nextWords.vocabulary) }
        autocorrect.setOwn(nextWords.vocabulary)
        nextWords.refreshOwnWords()
        setPredictionLanguages()
        // The person's own mistakes: what they put a word right to, and the swipe readings they chose over the
        // first one, are gone by from now on (Mistakes.kt). A file of this app's own, like the rest.
        mistakes = Mistakes.get(this)
        mistakes.onChange = { autocorrect.setMistakes(mistakes.remembered); autocorrect.setChosen(mistakes.chosen) }
        autocorrect.setMistakes(mistakes.remembered)
        autocorrect.setChosen(mistakes.chosen)
        // Anonymous typing reports (off unless switched on): only the commonest words of the app's lists are ever in one.
        Reports.common = { autocorrect.isCommonWord(it) }
        Reports.known = { autocorrect.isWord(it) }
        // Mockingbird cleans slips out of its samples with these dictionaries, on its own thread.
        Mockingbird.speller = autocorrect
        readMockingbird()
        WhisperModel.refresh(this)
        WhisperModel.addListener(onModel)
        // Automatic updates: a look at most twice a day, installed with no prompt where Android allows it.
        Updater.addListener(onUpdate)
        Updater.restore(this)
        Updater.backgroundCheck(this)
        clipboard.addPrimaryClipChangedListener(onClip)
        // One-time codes from texts: Android's receiver is made to match the switch, and a code that arrives redraws the row.
        SmsCodes.apply(this)
        SmsCodes.addListener(onSmsCode)
        // Sound shaping is gone (0.5.7): any filters set with it are cleared, so every pack plays as made.
        prefs.prefs.edit().remove("sound_filters").apply()
        // Long-press customisation is gone too (0.5.8): every key's popup is as it ships.
        prefs.prefs.edit().remove("popup_keys").remove("tap_log").remove("icon_pack").remove("sound_pack").remove("sound_source").apply()
        deleteFile("tap_log.txt")
    }

    override fun onDestroy() {
        swipeWorker.shutdownNow()
        gone = true
        reader.shutdownNow()
        runCatching { clipboard.removePrimaryClipChangedListener(onClip) }
        SmsCodes.removeListener(onSmsCode)
        rowHandler.removeCallbacks(codesFresh)
        SoundPacks.release()
        WhisperModel.removeListener(onModel)
        Updater.removeListener(onUpdate)
        dictation.release()
        rowHandler.removeCallbacks(offlineNoteDone)
        autocorrect.onLoaded = null
        nextWords.onVocabulary = null
        mistakes.onChange = null
        rowHandler.removeCallbacks(wordsLoaded)
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(this)
        if (Mockingbird.speller === autocorrect) Mockingbird.speller = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // The typing trainer hears the keys on their way here (TrainerKeys.kt): with no run on it only passes them on.
        val view = KeyboardView(this, TrainerKeys(this, packageName, { keyboard }, { currentInputConnection }, { currentInputEditorInfo }, { autocorrect.isListed(it) }) { applySettings() })
        val strip = PluginRow(this, onHide = { onHideKeyboard() }, onArrow = { code -> arrow(code) })
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
    /**
     * The text around the cursor as the keyboard knows it without asking the app again (FieldMirror.kt), and the
     * connection that keeps it in step with all the keyboard does to the field.
     */
    private val mirror = FieldMirror { android.os.SystemClock.uptimeMillis() }
    private var mirrored: MirrorConnection? = null

    /** The app in this field does not answer Android 12's one reading of it: the row reads the old way here. */
    private var plainReads = false

    /**
     * The field, for everything the keyboard does to it, the service's own code and Android's helpers alike: Android's
     * connection with the mirror kept in step (MirrorConnection.kt). Another connection is another field, or the same
     * one started again: what was known is forgotten. The mirror is the main thread's alone, so a call from any
     * other thread is given Android's connection as it is.
     */
    override fun getCurrentInputConnection(): InputConnection? {
        val real = super.getCurrentInputConnection() ?: return null
        if (Looper.myLooper() !== Looper.getMainLooper()) return real
        mirrored?.let { if (it.target === real) return it }
        mirror.drop()
        return MirrorConnection(real, mirror).also { mirrored = it }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        // Another field starts with nothing known about it; the same field started again keeps what it has shown of
        // itself (whether its app reports the cursor, whether a reading of it was ever not as guessed).
        if (restarting) mirror.drop() else { mirror.newField(); plainReads = false }
        runCatching {
            val key = attribute?.let {
                FieldKey(it.fieldId, it.packageName, it.inputType, it.imeOptions, it.hintText?.toString(), it.fieldName, it.label?.toString(), it.privateImeOptions)
            }
            if (takeOpen && key != takeField) dropTake()
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
        mirror.drop()
        dropTake()
        runCatching { AccuracyLog.flush(this); AccuracyLog.clearTaps() }
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
        // The row keeps the shape it had: its tools, or its words with the arrow at their end.
        heldWords = row?.showsWords == true
        toolsHeld = true
        scheduleRow()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        // A debug build times its own work while files/debug-perf is there (Perf.kt); a release build never does.
        if (BuildConfig.DEBUG) Perf.on = java.io.File(filesDir, "debug-perf").exists()
        Perf.time("show") { startView(info, restarting) }
    }

    private fun startView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        runCatching { AppProfiles.seen(this, prefs, info.packageName) }
        // What Mockingbird heard in apps of this kind (a chat, or anything else) counts most toward the next word here.
        runCatching { nextWords.chat = AppContext.kind(info.packageName) == AppKind.CHAT }
        // A field is asked about once, as it is entered: an app restarting its input (many do, mid-typing) must not
        // undo a layout chosen by hand in it.
        if (!restarting) latinField = wantsLatin(info)
        // What was typed in the field before this one is counted now; this field starts with nothing held.
        if (!restarting) { learner.flush(); learner.newField() }
        autoSpaced = false
        hangul = Hangul.State()
        applySettings()
        showUpdateBadge()
        selStart = info.initialSelStart
        selEnd = info.initialSelEnd
        lastSpaceAt = 0L
        lastCorrection = null
        lastSwipe = null
        toolsHeld = false
        wordTouches.clear(); takenTouch = null
        if (!restarting) { inlineViews = emptyList(); closeGifs(); closeEmoji(); showingClips = false; showingRewrite = false; ctrlOn = false; altOn = false; flushDraft(); aiJob?.cancelled = true; aiJob = null; ends.clear(); swipeGone = null; strokes.clear(); said = null; lastShortcut = null; keptShortcut = null }
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
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) =
        Perf.time("selection") { selectionMoved(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd) }

    private fun selectionMoved(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // First of all: a cursor that is not where the keys' own typing put it means the mirror no longer holds.
        mirror.reported(newSelStart, newSelEnd, candidatesStart != -1 || candidatesEnd != -1)
        // A tap in the text (not the keyboard's own doing) lets the row go back to that word's suggestions.
        if (editor.onSelection(newSelStart, newSelEnd)) toolsHeld = false
        selStart = newSelStart
        selEnd = newSelEnd
        // The space a row tap typed is the keyboard's to take back only while the cursor stays just after it.
        if (autoSpaced) {
            if (autoSpaceAt < 0) autoSpaceAt = newSelEnd
            else if (newSelEnd != autoSpaceAt || newSelStart != newSelEnd) autoSpaced = false
        }
        // The field emptied under us (a chat app's send button): the message just sent is a sample, and its last
        // words are counted for next-word prediction.
        if (newSelStart == 0 && newSelEnd == 0 && (draft.isNotEmpty() || learner.pending || ends.isNotEmpty() || swipeGone != null)) {
            val ic = currentInputConnection
            if (ic != null && ic.getTextBeforeCursor(1, 0).isNullOrEmpty() && ic.getTextAfterCursor(1, 0).isNullOrEmpty()) {
                val counted = draft.isNotEmpty() || learner.pending
                if (draft.isNotEmpty()) flushDraft()
                if (counted) learner.flush(finished = true)
                learner.emptied()
                // Nothing typed from here on is typed over a word of the message that went.
                ends.clear(); swipeGone = null
            }
        }
        updateShift()
        scheduleRow()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // Turned or unfolded mid-take: Android may build the keys again, without their listening panel, while the
        // mic is still open. The take ends here, and what it heard is written down.
        if (takeOpen && dictation.listening) dictation.stop()
        super.onConfigurationChanged(newConfig)
        applySettings()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) = applySettings()

    /**
     * An Update chip leads the row's tools while a release waits (the settings cog that carried a dot for it is
     * gone); with Offline on, never: nothing is asked.
     */
    private fun showUpdateBadge() {
        val waiting = !prefs.offline && Updater.waiting(this)
        if (waiting == updateWaiting) return
        updateWaiting = waiting
        scheduleRow()
    }

    private fun applySettings() = Perf.time("settings") { applyNow() }

    private fun applyNow() {
        offlineOn = prefs.offline
        language = prefs.languageState(latinField)
        autocorrect.setLanguages(language.dictionaries, language.board, language.lexicons)
        autocorrect.setLearned(prefs.learnedWords)
        autocorrect.setSlang(prefs.slang)
        setPredictionLanguages()
        readMockingbird()
        if (!prefs.shareReports) runCatching { Reports.discard(this) }
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
        // The mic key stands where the full stop was (Layouts.withMic). In a password, an e-mail address or a web
        // address the full stop keeps its key: nothing is dictated there, and an address needs one at every turn.
        // So it does while a trainer run is typed: a story is typed, full stops and all, never spoken.
        view.micKey = Plugins.isOn(prefs, Plugins.DICTATION) && WhisperModel.isReady(this) && !wantsLatin(currentInputEditorInfo) &&
            !Trainer.typing(currentInputEditorInfo?.packageName, packageName)
        // A trainer run that is trying a layout has the keyboard wear it, in the trainer's own field and for that run
        // only (Trainer.heightFor); the setting itself is never touched.
        val keyHeight = Trainer.heightFor(currentInputEditorInfo?.packageName, packageName) ?: prefs.keyHeight
        view.configure(profile?.flag("number_row") ?: prefs.numberRow, keyHeight, prefs.toolbar && !prefs.hideRow)
        view.setLanguage(language.spec, language.extras, language.spaceLabel, language.spaceShort, prefs.globeKey && language.stops.size > 1, language.choices, locale)
        view.swipeTyping = swipeAllowed()
        view.wideLayout = prefs.wideLayout
        view.oneHanded = prefs.oneHanded
        view.bottomSpace = prefs.bottomSpace
        view.deleteSwipe = prefs.deleteSwipe
        // The backlight is on by default, but never while typing a password: lit letters would show what is typed.
        view.adaptive = if (Plugins.isOn(prefs, Plugins.ADAPTIVE)) AdaptiveKeys.get(this, language.spec.id) else null
        // A trainer run that tests a fix has the keys listen where the fix has them, in the trainer's own field and
        // for that run only (Trainer.fixFor). The fix is worn, like a trial layout: the model is never written.
        view.fix = Trainer.fixFor(currentInputEditorInfo?.packageName, packageName)
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
        // Wherever there is a row, the { } key is a button in it (everyday), and its place on the board, next to
        // ?123, is the emoji key's; with Settings › Look & feel › Keys › Comma key on it is the comma's, and the emoji
        // key stands beside that, cut from the space bar (Matt, 2026-11-12: "The comma key should be in the original
        // spot the code was in the emoji key should cut into the spacebar", then: "On second thought remove the comma
        // by default make it a setting users can toggle on"). With no row (it is hidden, or nothing is in it) the
        // { } key keeps that place, as it always had it, and the emoji key stands beside it (Layouts.withEmoji).
        // The { } key goes to the row only where something takes its place on the board, and then there is always
        // a row to hold it (as since 0.16.2), unless the row is hidden.
        val emojiOn = Plugins.isOn(prefs, Plugins.EMOJI)
        val taken = emojiOn || prefs.commaKey
        val rowShown = !prefs.hideRow && (prefs.toolbar || usesRow() || taken || prefs.arrowKeys || profile?.flag("terminal") == true || profile?.flag("markdown") == true || profile?.flag("agent") == true)
        codeInRow = rowShown && taken
        view.emojiKey = emojiOn
        view.codeAway = codeInRow
        view.commaKey = prefs.commaKey
        view.pluginRow = rowShown
        row?.let { it.theme = theme; it.condensed = keyHeight == "condensed"; it.showHide = prefs.toolbar; it.showArrows = prefs.arrowKeys; it.visibility = if (rowShown) View.VISIBLE else View.GONE }
        gifPanel?.theme = theme
        frame?.working?.theme = theme
        emojiPanel?.theme = theme
        if (!Plugins.isOn(prefs, Plugins.EMOJI)) closeEmoji()
        if (!Plugins.isOn(prefs, Plugins.GIFS) || offlineOn) closeGifs()
        showUpdateBadge()
        scheduleRow()
        view.keyBorders = prefs.keyBorders
        view.showPreview = prefs.keyPopup
        view.showHints = prefs.hints
        view.spaceCursor = prefs.spaceCursor
        view.longPressMs = prefs.longPressMs
        // A layout just switched to starts with shift off: on again where a sentence starts and the script has capitals.
        updateShift()
    }

    /**
     * Swipe needs a board made for it and words to find: Korean has neither, the plain ABC board no words, and a
     * layout just switched to has none until its list is read ([wordsLoaded] asks again then), so a finger's path is
     * never taken for a word that cannot be found.
     */
    private fun swipeAllowed(): Boolean = Plugins.isOn(prefs, Plugins.SWIPE) && language.spec.swipe && autocorrect.active

    /** A field whose text must never be recorded: the tap log stays off in it. */
    private fun isPassword(info: EditorInfo?): Boolean {
        if (info == null) return false
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    /** A field for Latin letters whatever the language: a password, an e-mail address, a web address. */
    private fun wantsLatin(info: EditorInfo?): Boolean {
        if (info == null || info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        return isPassword(info) || when (info.inputType and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_URI, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> true
            else -> false
        }
    }

    /**
     * The globe key or the space bar's list chose a layout: the word being typed is over, and the choice is kept (the
     * settings listener then gives it to the keys and to autocorrect). In a field that was held to Latin letters,
     * choosing by hand lets it go.
     */
    override fun onLayout(id: String) {
        finishTyping()
        // What was typed on the layout being left is counted under it; the next word starts afresh on the new one.
        learner.flush()
        autoSpaced = false
        endWord()
        lastCorrection = null
        lastSwipe = null
        lastSpaceAt = 0L
        hangul = Hangul.State()
        latinField = false
        // The same layout as the setting holds (it had stepped aside in this field) changes no setting, so nothing would re-apply.
        if (prefs.prefs.getString(KeyboardPrefs.LAYOUT, "") == id) applySettings() else prefs.set(KeyboardPrefs.LAYOUT, id)
    }

    /**
     * A character of a word on the layout being typed on: a letter, a mark that rides on one (Arabic's vowels, a
     * combining accent), or one of the layout's joiners (an apostrophe on Latin boards, Catalan's middle dot, Persian
     * and Urdu's half-space). Never an emoji's own marks ([Words.isEmojiMark]).
     */
    private fun isWordChar(c: Char): Boolean = Words.isWordChar(c, joiners)

    /** Punctuation that finishes a word here: . , ! ? and the layout's own (Arabic's ، ؟ ؛, Greek's ;). */
    private fun isWordEnd(c: Char): Boolean = c in WORD_ENDS || c in language.spec.wordEnds

    /** Shift on at the start of a sentence, where the field asks for it and the setting allows. Scripts without capitals have none. */
    private fun updateShift() {
        val view = keyboard ?: return
        if (!language.spec.script.cased) return
        if (!prefs.autoCaps) { view.setAutoShift(false); return }
        val info = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = if (info.inputType != InputType.TYPE_NULL) Perf.time("caps") { ic.getCursorCapsMode(info.inputType) } else 0
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
        val jamo = text.singleOrNull()?.takeIf { caretBack == 0 && language.spec.script == Script.HANGUL && Hangul.isJamo(it) }
        // A key typed after a shortcut's phrase lets it stand, and letters typed on are no longer the letters kept:
        // a key typed into a GIF or emoji search too, so a backspace long after cannot take the phrase back.
        lastShortcut = null
        keptShortcut = null
        said = null
        if (gifMode == GifMode.TYPING) { gifQuery = if (jamo != null) hangulOnto(gifQuery, jamo) else gifQuery + text; scheduleRow(); return }
        if (emojiMode == GifMode.TYPING) { emojiQuery = if (jamo != null) hangulOnto(emojiQuery, jamo) else emojiQuery + text; scheduleRow(); return }
        val ic = currentInputConnection ?: return
        if (jamo != null) { typeHangul(ic, jamo); return }
        hangul = Hangul.State()
        // Terminal keys: a sticky Ctrl or Alt goes with the next key, as a key press the terminal reads (Ctrl+C).
        if ((ctrlOn || altOn) && text.length == 1 && sendWithModifiers(text[0])) return
        stats { TypingStats.typed(this, text) }
        if (reporting()) keysTyped += text.length
        lastSpaceAt = 0L
        // Next-word prediction's flags: letters typed by hand (a long-press may type several inside a word: Arabic's
        // لا, Catalan's l·l), or something that ends the word before it.
        if (text.isNotEmpty() && text.all(::isWordChar)) learner.typed(text.count { it !in joiners }) else { learner.ended(); learner.typed(0) }
        val swapSpace = autoSpaced && caretBack == 0 && text.length == 1 && isWordEnd(text[0])
        autoSpaced = false
        // A letter straight after a swiped word starts a new word: a space first, as Gboard does.
        if (lastSwipe != null && text.length == 1 && text[0].isLetterOrDigit()) { editor.type(" "); learner.ended() }
        atSwipe = lastSwipe?.word
        swipeStood()
        lastSwipe = null
        // A full stop or comma straight after a word tapped in the row takes the place of the space the tap typed
        // ("you ." becomes "you."); the tapped word is a word already, so it is not autocorrected either.
        if (swapSpace && ic.getTextBeforeCursor(1, 0)?.toString() == " ") {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(text, 1)
            ic.endBatchEdit()
            lastCorrection = null
            adaptiveTyped(text)
            snapshotDraft()
            return
        }
        // Punctuation ends a word, as space does: autocorrect it first.
        if (caretBack == 0 && text.length == 1 && isWordEnd(text[0]) && correctLastWord(ic, text)) { endWord(); return }
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
        if (text.length == 1 && isWordEnd(text[0])) snapshotDraft()
    }

    /**
     * Korean: [jamo] typed. The block being built (where it is still just before the cursor) is taken out and the
     * block as it now stands put in, in one edit, the way an autocorrection replaces a word: no composing region is
     * left open for a row action or another app's edit to overwrite.
     */
    private fun typeHangul(ic: InputConnection, jamo: Char) {
        stats { TypingStats.typed(this, jamo.toString()) }
        lastSpaceAt = 0L
        lastSwipe = null
        lastCorrection = null
        val built = hangul.render()
        val there = built.isNotEmpty() && ic.getSelectedText(0).isNullOrEmpty() && ic.getTextBeforeCursor(built.length, 0)?.toString() == built
        if (!there) hangul = Hangul.State()
        val step = Hangul.add(hangul, jamo)
        hangul = step.state
        ic.beginBatchEdit()
        if (there) ic.deleteSurroundingText(built.length, 0)
        ic.commitText(step.committed + step.state.render(), 1)
        ic.endBatchEdit()
        adaptiveTyped(jamo.toString())
    }

    /** Korean in the GIF and emoji searches: [jamo] typed onto [text], whose end is the block being built. */
    private fun hangulOnto(text: String, jamo: Char): String {
        val built = hangul.render()
        val there = built.isNotEmpty() && text.endsWith(built)
        if (!there) hangul = Hangul.State()
        val step = Hangul.add(hangul, jamo)
        hangul = step.state
        return (if (there) text.dropLast(built.length) else text) + step.committed + step.state.render()
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
        val before = ic.getTextBeforeCursor(CORRECT_CHARS, 0)?.toString() ?: return false
        // As far back as it always looked: a run of letters longer than that is no word to mend. The word starts at
        // its first letter, so one typed straight after an emoji is mended (or left) alone, and the emoji stays.
        val from = (before.length - 48).coerceAtLeast(0)
        val word = before.substring(Words.lastStart(before, joiners, from))
        if (word.isEmpty()) return false
        val start = Words.runStart(before, joiners, from)
        if (start > 0) {
            val prev = before[start - 1]
            if (prev.isLetterOrDigit() || prev in "@#/._-\\:\$`") return false
        }
        // In the middle of a word (the cursor moved back into it): the word goes on past the cursor, leave it.
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && (after[0].isDigit() || isWordChar(after[0]))) return false
        // The words before it help choose between corrections that would each do (never whether to correct at all).
        // Working that out must not cost the key press: any failure and the word is corrected as it always was.
        val scorer = if (!prefs.nextWord || terminalApp()) null else runCatching {
            NextWordText.place(before, ::isWordChar, before.length >= CORRECT_CHARS, locale, sentenceEnds())?.let { nextWords.at(it) }
        }.getOrNull()
        val lower = word.lowercase(locale)
        val now = android.os.SystemClock.uptimeMillis()
        // The place the word stands in is told by the text before it, which is read from the field itself (the
        // cursor's position is the app's to report, and may be a key press behind).
        val head = before.dropLast(word.length).takeLast(HEAD_CHARS)
        // Where this word stands a sentence starts: its capital is the sentence's, not the word's.
        val opens = head.trimEnd(' ').let { it.isEmpty() || it.last() in ".!?…\n" }
        // The word that ended in this same place a moment ago, where one did: this one is typed over it.
        val prior = ends.firstOrNull { it.field === currentInputEditorInfo && it.head == head && now - it.at < RETYPE_MS }
        prior?.let { ends.remove(it) }
        // Typed again as it was where a fix was taken out: the word was meant (Matt, 2026-11-10: "improving the
        // reliability of ... the autocorrect"). It is left as typed and kept from now on, and the fix is forgotten.
        // The same word typed further on (a habitual slip, fixed each time) is not this.
        if (prior != null && prior.typed == lower && !prior.out.equals(lower, ignoreCase = true)) {
            if (!incognito) { prefs.learn(word, locale); autocorrect.setLearned(prefs.learnedWords) }
            if (mistakesOn()) mistakes.forget(prior.typed, prior.out)
            if (reporting()) Reports.count(this, "words.keptAfterFix")
            noteEnd(lower, word, head, now)
            return false
        }
        val taps = tapsFor(word)
        val found = autocorrect.correct(word, scorer, taps)
        if (accuracyOn()) recordWord(word, found, before.dropLast(word.length))
        val stands = found ?: word
        if (prior != null) retyped(prior, stands, opens)
        swipedThenTyped(head, stands, now)
        noteEnd(lower, stands, head, now)
        // Left as typed: where this spelling has been a mistake before, that it is also meant counts against mending it.
        if (found == null && mistakesOn()) mistakes.kept(lower)
        // A swiped word that a space or a full stop has just ended was counted as a swipe: it is not a typed word too.
        val swiped = atSwipe?.equals(word, ignoreCase = true) == true
        atSwipe = null
        if (reporting() && !swiped) { reportKeys(); reportBoard(taps); Reports.word(this, word, stands, taps) }
        val fixed = found ?: return false
        // One step in the history, after the separator as typed: undo gives back the word as typed, with its space.
        if (!editor.replaceBefore(word, fixed + separator, Kind.CORRECTION, typedFirst = separator)) return false
        adaptiveCorrected(word, fixed)
        stats { TypingStats.correction(this) }
        lastCorrection = Triple(word, fixed, separator)
        // A fix left to stand is half a lesson (it may not have been looked at); undoing it takes the lesson back.
        if (mistakesOn()) mistakes.learn(lower, meantAs(fixed, opens), Mistakes.STOOD)
        return true
    }

    /**
     * A word that finished in this field a moment ago: as it was typed (lower case) and as it stood once autocorrect
     * had looked at it ([out], with its capitals), the text that stood before it ([head], which says where it was)
     * and when. [taught] says undoing its fix is what made the typed spelling a learned word.
     */
    private class Ended(val typed: String, val out: String, val head: String, val at: Long, val field: EditorInfo?) { var taught = false }
    private val ends = java.util.ArrayDeque<Ended>()

    private fun noteEnd(typed: String, out: String, head: String, at: Long) {
        ends.addFirst(Ended(typed, out, head, at, currentInputEditorInfo))
        while (ends.size > ENDS_KEPT) ends.removeLast()
    }

    /**
     * Corrections made in this field may be learned from (Matt, 2026-11-10: "I want the models to be trained off of
     * previous mistakes"): the switch is on, and it is ordinary writing. Never a password, incognito or a field that
     * asks keyboards not to learn ([incognito] covers both), an address, a name, or a terminal.
     */
    private fun mistakesOn(): Boolean {
        if (!prefs.learnMistakes) return false
        val info = currentInputEditorInfo ?: return false
        return !incognito && !isPassword(info) && collectable(info) && !terminalApp()
    }

    /**
     * What is typed here may be counted for an anonymous typing report (Matt, 2026-11-10: "allow anonymous reporting
     * of swipe and text metrics and mistakes"): its switch is on (it is off unless the person turns it on), and the
     * field is ordinary writing, by the rule learning has: never a password, incognito or a field that asks keyboards
     * not to learn, an address, a name, a search, a terminal, or this app's own fields.
     */
    private fun reporting(): Boolean {
        if (!prefs.shareReports) return false
        val info = currentInputEditorInfo ?: return false
        if (incognito || isPassword(info) || !collectable(info) || terminalApp()) return false
        return info.imeOptions and EditorInfo.IME_MASK_ACTION != EditorInfo.IME_ACTION_SEARCH
    }

    /** The swiped word at the cursor as a key that ends it was pressed, for [correctLastWord] to know it by. */
    private var atSwipe: String? = null

    /** Keys and backspaces since they were last handed to the report: counted here so a key press queues nothing. */
    private var keysTyped = 0
    private var backspaces = 0

    private fun reportKeys() {
        if (keysTyped > 0) Reports.count(this, "keys", keysTyped.toLong())
        if (backspaces > 0) Reports.count(this, "backspaces", backspaces.toLong())
        keysTyped = 0; backspaces = 0
    }

    /** The board as a report describes it, told once for each size of key: its layout, languages, and how big a key is. */
    private var boardReported = ""
    private fun reportBoard(taps: Taps?) {
        taps ?: return
        val id = "${language.spec.id}|${language.dictionaries}|${taps.keyW}|${prefs.keyHeight}"
        if (id == boardReported) return
        boardReported = id
        val row = ReportTally.rowPitch(taps.keys, taps.keyW) ?: return
        Reports.board(language.spec.id, language.dictionaries, taps.keyW / resources.displayMetrics.density, row / taps.keyW, prefs.keyHeight == "condensed", profile?.flag("number_row") ?: prefs.numberRow)
    }

    /**
     * How a word the person put there is remembered: in lower case, unless they gave it a capital of its own in the
     * middle of a sentence and it is no ordinary word (a name: "kria" was "Kira"). [opens] says it opened a sentence.
     */
    private fun meantAs(stands: String, opens: Boolean): String {
        val lower = stands.lowercase(locale)
        return if (!opens && stands.isNotEmpty() && stands[0].isUpperCase() && stands.drop(1) == lower.drop(1) && !autocorrect.isListed(lower)) stands else lower
    }

    /**
     * A word was finished where [prior] ended a moment ago, and [stands] there now: the person went back and typed
     * it again. Where the two are a slip apart, what was typed first was a mistake for what stands now, and is
     * remembered as one; a fix autocorrect made there that is not what stands now is forgotten; and a spelling that
     * became a learned word only because that fix was undone on the way is let go again.
     */
    private fun retyped(prior: Ended, stands: String, opens: Boolean) {
        val meant = stands.lowercase(locale)
        val was = prior.out.lowercase(locale)
        if (meant == was && was == prior.typed) return
        if (prior.taught && meant != prior.typed) { prefs.unlearn(prior.typed, locale); autocorrect.setLearned(prefs.learnedWords) }
        // For an anonymous report: a word put right by hand, and a fix of autocorrect's that was not what was meant.
        if (reporting() && meant != prior.typed && MistakeMemory.alike(prior.typed, meant)) {
            Reports.mistake(this, "retyped", prior.typed, meant)
            if (was != prior.typed && was != meant) Reports.count(this, "fix.retyped")
        }
        if (!mistakesOn()) return
        if (was != prior.typed && was != meant) mistakes.forget(prior.typed, prior.out)
        // It had been left as typed, and did not stand after all.
        if (was == prior.typed && meant != prior.typed) mistakes.kept(prior.typed, -Mistakes.STOOD)
        if (meant != prior.typed && MistakeMemory.alike(prior.typed, meant)) mistakes.learn(prior.typed, meantAs(stands, opens))
    }

    /**
     * A word typed by hand where a swiped one was deleted a moment ago: where it is one of the readings that swipe
     * offered, it is what the stroke was, and is remembered as the reading meant by it.
     */
    private fun swipedThenTyped(head: String, stands: String, now: Long) {
        val gone = swipeGone ?: return
        swipeGone = null
        // The same place, give or take the space the swipe had put in for itself.
        if (!mistakesOn() || gone.field !== currentInputEditorInfo || now - gone.whenMs > RESWIPE_MS || head.trimEnd(' ') != gone.head.trimEnd(' ')) return
        val meant = stands.lowercase(locale)
        if (meant != gone.raw && gone.choices.any { it.lowercase(locale) == meant }) mistakes.learnSwipe(gone.raw, meant)
    }

    /**
     * The swiped word at the cursor is being left as it is (something is typed after it): what the stroke was read
     * as, and what stood, are learned from. A reading the person chose in the row counts in full, one left as it
     * came half; and a stroke drawn again where one was deleted says what that one was, where it had offered it.
     */
    private fun swipeStood() {
        val swipe = lastSwipe ?: return
        if (reporting()) {
            val stood = swipe.word.lowercase(locale)
            Reports.stroke(this, if (swipe.picked) "picked" else "kept", swipe.choices, stood, swipe.path)
            // The stroke that was deleted before this one: where it had offered this word, it was this word misread.
            swipe.over?.let { gone -> if (stood != gone.raw && gone.choices.any { it.lowercase(locale) == stood }) Reports.stroke(this, "redrawn", gone.choices, stood, gone.path) }
        }
        if (!mistakesOn()) return
        val meant = swipe.word.lowercase(locale)
        val weight = if (swipe.picked) 1f else Mistakes.STOOD
        if (meant == swipe.raw) mistakes.stood(swipe.raw, weight) else mistakes.learnSwipe(swipe.raw, meant, weight)
        swipe.over?.let { gone -> if (meant != gone.raw && gone.choices.any { it.lowercase(locale) == meant }) mistakes.learnSwipe(gone.raw, meant) }
    }

    /** Mockingbird's samples are looked at again, for the slips it mended in them and for the words it heard together. */
    private fun readMockingbird() {
        val on = Plugins.isOn(prefs, Plugins.MOCKINGBIRD)
        runCatching { mistakes.readMockingbird(on && prefs.learnMistakes) }
        runCatching { nextWords.readMockingbird(on && prefs.nextWord && prefs.nextWordMockingbird, leadLanguage()) }
    }

    /**
     * Puts back the word an autocorrection replaced, and keeps it as a learned word: undoing a fix says the word was
     * meant, so the next space does not fix it again. False when the fix is no longer just before the cursor.
     */
    private fun undoCorrection(ic: InputConnection, keepSeparator: Boolean): Boolean {
        val (typed, fixed, separator) = lastCorrection ?: return false
        lastCorrection = null
        if (accuracyOn()) AccuracyLog.settle(this, "undone")
        stats { TypingStats.undone(this) }
        val tail = fixed + separator
        if (!editor.replaceBefore(tail, if (keepSeparator) typed + separator else typed, Kind.CORRECTION)) return false
        lastSpaceAt = 0L
        // The fix was never meant, so it is never counted; the word put back is the one that was typed.
        learner.unhold(fixed.lowercase(locale))
        learner.word(typed.length)
        if (keepSeparator) learner.ended()
        val lower = typed.lowercase(locale)
        val fresh = lower !in prefs.learnedWords
        if (!incognito) { prefs.learn(typed, locale); autocorrect.setLearned(prefs.learnedWords) }
        // The fix was not wanted: it is no lesson. Should another word be typed over this one next, the spelling put
        // back was no word either, and is let go again ([retyped]).
        if (mistakesOn()) mistakes.forget(lower, fixed)
        if (reporting()) Reports.undone(this, lower, fixed)
        if (fresh && !incognito && !keepSeparator) ends.firstOrNull { it.typed == lower && it.field === currentInputEditorInfo }?.taught = true
        scheduleRow()
        return true
    }

    override fun onDelete() {
        toolsHeld = false
        said = null
        finishTyping()
        keyboard?.takeTyped()
        takenTouch = wordTouches.removeLastOrNull()?.let { it to System.currentTimeMillis() }
        // The shortcut just expanded, for this backspace alone: whatever this one does, the next is plain backspace.
        val shortcut = lastShortcut
        lastShortcut = null
        if (gifMode == GifMode.TYPING) { hangul = Hangul.State(); gifQuery = gifQuery.dropLast(1); scheduleRow(); return }
        if (emojiMode == GifMode.TYPING) { hangul = Hangul.State(); if (emojiQuery.isEmpty()) browseEmoji() else emojiQuery = emojiQuery.dropLast(1); scheduleRow(); return }
        val ic = currentInputConnection ?: return
        stats { TypingStats.backspace(this) }
        if (reporting()) backspaces++
        if (accuracyOn()) AccuracyLog.deleted()
        learner.deleted()
        autoSpaced = false
        // Korean: backspace on the block being built takes one letter of it, not the whole syllable.
        if (!hangul.empty) {
            val built = hangul.render()
            if (ic.getSelectedText(0).isNullOrEmpty() && ic.getTextBeforeCursor(built.length, 0)?.toString() == built) {
                val left = Hangul.backspace(hangul) ?: Hangul.State()
                hangul = left
                ic.beginBatchEdit()
                ic.deleteSurroundingText(built.length, 0)
                if (!left.empty) ic.commitText(left.render(), 1)
                ic.endBatchEdit()
                lastSpaceAt = 0L
                return
            }
            hangul = Hangul.State()
        }
        // Backspace straight after an autocorrection puts back what was typed, as Gboard does.
        if (lastCorrection != null && undoCorrection(ic, keepSeparator = false)) return
        // Backspace straight after a shortcut's phrase puts back the letters that were typed, and the next space
        // leaves them be. Only the very next key: where the phrase is no longer just before the cursor, or another key
        // came between, backspace is backspace.
        shortcut?.let { (typed, text, separator) ->
            if (editor.replaceBefore(text + separator, typed, Kind.SNIPPET)) {
                keptShortcut = typed
                lastSpaceAt = 0L
                scheduleRow()
                return
            }
        }
        // Backspace straight after a swiped word takes the whole word, as Gboard does.
        lastSwipe?.let { swipe ->
            lastSwipe = null
            val tail = swipe.lead + swipe.word
            if (ic.getTextBeforeCursor(tail.length, 0)?.toString() == tail) {
                stats { TypingStats.swipeDeleted(this) }
                // What it was read as is kept a moment: the word swiped or typed in its place says what it was.
                swipeGone = SwipeGone(swipe.raw, swipe.choices, ic.getTextBeforeCursor(HEAD_CHARS + tail.length, 0)?.toString().orEmpty().dropLast(tail.length).takeLast(HEAD_CHARS), android.os.SystemClock.uptimeMillis(), currentInputEditorInfo, swipe.path)
                if (reporting()) Reports.count(this, "swipe.deleted")
                editor.deleteBefore(tail)
                // The swiped word is gone whole: nothing entered by hand is left at the cursor.
                learner.deleted(whole = true)
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
        said = null
        finishTyping()
        try { space() } finally { endWord() }
    }

    private fun space() {
        hangul = Hangul.State()
        if (gifMode == GifMode.TYPING) { gifQuery += " "; scheduleRow(); return }
        if (emojiMode == GifMode.TYPING) { emojiQuery += " "; scheduleRow(); return }
        learnGhostMd()
        snapshotDraft()
        val ic = currentInputConnection ?: return
        stats { TypingStats.typed(this, " ") }
        atSwipe = lastSwipe?.word
        swipeStood()
        lastSwipe = null
        learner.ended()
        autoSpaced = false
        val now = System.currentTimeMillis()
        if (prefs.doubleSpacePeriod && now - lastSpaceAt < 1000) {
            // Gboard's double-space full stop: "word␣" + space becomes "word.␣" (the layout's own full stop: Urdu's is ۔).
            val before = ic.getTextBeforeCursor(2, 0)
            if (before != null && before.length == 2 && before[1] == ' ' && before[0].isLetterOrDigit() &&
                editor.replaceBefore(" ", language.spec.fullStop + " ", Kind.CORRECTION, typedFirst = " ")
            ) {
                lastSpaceAt = 0L
                return
            }
        }
        // A shortcut by itself before the cursor becomes what it stands for, before autocorrect sees its letters.
        lastShortcut = null
        val kept = keptShortcut
        if (expandShortcut(ic, " ")) { lastSpaceAt = now; return }
        // Letters that were kept as typed in place of their phrase are kept from autocorrect too: a plain space.
        if (kept != null && keptShortcut == null) { lastCorrection = null; editor.type(" "); lastSpaceAt = now; return }
        if (correctLastWord(ic, " ")) { lastSpaceAt = now; return }
        lastCorrection = null
        editor.type(" ")
        lastSpaceAt = now
    }

    override fun onEnter() {
        toolsHeld = false
        said = null
        finishTyping()
        try { enter() } finally { endWord() }
    }

    private fun enter() {
        hangul = Hangul.State()
        if (gifMode == GifMode.TYPING) { searchGifs(); return }
        if (emojiMode == GifMode.TYPING) { browseEmoji(); return }
        val info = currentInputEditorInfo
        val ic = currentInputConnection ?: return
        // Enter on a <<request>> (the cursor inside it, as the < > pair key leaves it) fills it rather than sending.
        // With Offline on enter is plain enter, as it is in a private field.
        if (Plugins.isOn(prefs, Plugins.GHOSTWRITER) && !offlineOn && aiAllowed() && aiJob?.running != true &&
            Ghostwriter.tagAt(ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty(), ic.getTextAfterCursor(400, 0)?.toString().orEmpty()) != null
        ) { fillAtCursor(); return }
        // Enter straight after a shortcut puts its phrase in first, as a space would, and then does what enter does:
        // the phrase stands, with no backspace to take it back once the line or the message has gone.
        val kept = keptShortcut
        val expanded = runCatching { expandShortcut(ic, "") }.getOrDefault(false)
        lastShortcut = null
        // The phrase a shortcut stands for is not autocorrect's to mend, and nor are letters kept as typed in place
        // of their phrase (as a space leaves both alone).
        val settled = expanded || (kept != null && keptShortcut == null)
        // Enter finishes a message's last word as a space would have: it is mended too, where enter sends a message
        // or starts a new line of one (never a search, a form's one-word field, or the first word of anything).
        atSwipe = lastSwipe?.word
        if (!settled && endsProse(info, ic)) runCatching { correctLastWord(ic, "") }
        lastSpaceAt = 0L
        lastCorrection = null
        swipeStood()
        lastSwipe = null
        snapshotDraft()
        // The line or message ends here: its last words are counted before the app takes the text away.
        autoSpaced = false
        runCatching { learnNow(ic); learner.flush(finished = true) }
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

    /**
     * Whether enter here ends a word of running text: it sends a message or starts a new line, and there is at least
     * a word before the one at the cursor. A form's Next or Done, Go and Search are not it: what is typed there is as
     * often a name or a term as a sentence, and nobody gets to see a fix made as the field is left.
     */
    private fun endsProse(info: EditorInfo?, ic: InputConnection): Boolean {
        if (info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && action != EditorInfo.IME_ACTION_SEND) return false
        }
        val before = ic.getTextBeforeCursor(CORRECT_CHARS, 0)?.toString() ?: return false
        return before.trimEnd().any { it.isWhitespace() }
    }

    override fun onCursor(slid: Int) {
        toolsHeld = false
        // An answer still typing out is written whole first, so it stays one step and none of it lands at the moved
        // cursor. The selection reported so far is then behind what was just written: this move goes by arrow keys.
        val behind = typing != null
        finishTyping()
        wordTouches.clear(); takenTouch = null
        val ic = currentInputConnection ?: return
        lastCorrection = null
        lastSwipe = null
        autoSpaced = false
        // The cursor leaves the word that was being typed: what is typed where it lands was not all typed by hand.
        learner.moved()
        if (selEnd < 0 || behind) {
            // Arrow keys move the caret the way they point whichever way the text runs, so the finger's own way is theirs.
            val code = if (slid < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            repeat(abs(slid)) { sendDownUpKeyEvents(code) }
            editor.sent()
            return
        }
        val before = ic.getTextBeforeCursor(CURSOR_CHARS, 0) ?: ""
        val after = ic.getTextAfterCursor(CURSOR_CHARS, 0) ?: ""
        // Right-to-left text runs the other way: the steps are turned round so the caret still follows the finger.
        // Which way is the text's at the caret, not the layout's: an Arabic sentence is slid through the same on the
        // English layout. Only an empty field, or one of digits and symbols, goes by the layout's script.
        val steps = if (rightToLeft(before, after) ?: language.spec.script.rtl) -slid else slid
        val move = if (steps < 0) -minOf(-steps, before.length) else minOf(steps, after.length)
        if (move == 0) return
        // From the end of a selection going right, from its start going left, as arrow keys do.
        val from = if (move < 0) minOf(selStart, selEnd) else maxOf(selStart, selEnd)
        val to = (from + move).coerceAtLeast(0)
        ic.setSelection(to, to)
        editor.moved(to)
    }

    /**
     * Which way the text at the caret runs: that of the nearest character with a direction of its own, looking back
     * from the caret first, then forward. Null where there is none near (an empty field, digits, symbols).
     */
    private fun rightToLeft(before: CharSequence, after: CharSequence): Boolean? {
        fun of(c: Char): Boolean? = when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> false
            else -> null
        }
        for (i in before.length - 1 downTo 0) of(before[i])?.let { return it }
        for (c in after) of(c)?.let { return it }
        return null
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
        learner.deleted(whole = true)
        autoSpaced = false
        // A Korean syllable being built goes with its word: the next letter starts a new one.
        hangul = Hangul.State()
        if (!ic.getSelectedText(0).isNullOrEmpty()) { editor.deleteSelection(); return }
        val before = ic.getTextBeforeCursor(96, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        if (i > 0) {
            if (before[i - 1].isLetterOrDigit() || isWordChar(before[i - 1])) {
                while (i > 0 && (before[i - 1].isLetterOrDigit() || isWordChar(before[i - 1]))) i--
            } else {
                // A symbol or an emoji goes on its own: its variation selector or keycap mark with it, and both
                // halves of it where it is a surrogate pair.
                while (i > 1 && Words.isEmojiMark(before[i - 1])) i--
                i -= if (Character.isLowSurrogate(before[i - 1]) && i >= 2) 2 else 1
            }
        }
        editor.deleteBefore(before.substring(i))
    }

    /** Cursor control: a slide up or down from the space bar moves by lines, as the arrow keys do. */
    override fun onCursorLine(lines: Int) {
        toolsHeld = false
        finishTyping()
        wordTouches.clear(); takenTouch = null; lastCorrection = null; lastSwipe = null; learner.moved()
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

    /** The emoji key, where the { } key was. */
    override fun onEmoji() = openEmoji()

    /** Another page of keys is showing: the row's { } button is lit on the code keys, and plain on any other. */
    override fun onPage() = scheduleRow()

    /** The row's { } button: the code keys, and the letters again. */
    private fun toggleCode() {
        val view = keyboard ?: return
        view.showPage(if (view.page == Page.DEV) Page.LETTERS else Page.DEV)
    }

    override fun onMic() {
        val view = keyboard ?: return
        if (dictation.listening) { dictation.stop(); return }
        // Still writing the last take down (the keys ask only after a few seconds of it: a long take on a slow
        // phone): a tap lets the rest of it go, and what it has typed already stays, a draft among it.
        if (takeOpen) { dictation.cancel(); keepDraft(); onDictated(""); return }
        if (view.dictation == DictationPhase.TRANSCRIBING) return
        if (!dictation.hasPermission()) {
            // An input method cannot ask for a permission itself: the settings app asks, on Dictation's page.
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_OPEN, "dictation"))
            return
        }
        view.dictation = DictationPhase.LISTENING
        said = null
        saying.setLength(0)
        liveDraft = null
        liveLost = false
        dictation.start(this)
        takeOpen = dictation.active
        takeField = fieldKey
    }

    /** Lets a take go whose field is gone: the mic closes, nothing more of it is typed, and the keys come back. */
    private fun dropTake() {
        if (!takeOpen) return
        takeOpen = false
        dictation.cancel()
        keyboard?.dictation = DictationPhase.OFF
        saying.setLength(0)
        said = null
        // A draft stands as it is in the field it was spoken into: nothing more is known of it.
        liveDraft = null
        liveLost = false
        updateWorking()
    }

    /** A take ends with a draft in the field that will not be written down again: it stands, as words the take said. */
    private fun keepDraft() {
        val run = liveDraft ?: return
        liveDraft = null
        if (saying.isNotEmpty()) saying.append(liveLead)
        saying.append(run.removePrefix(liveLead))
    }

    override fun onLevel(level: Float) { keyboard?.level = level }

    override fun onTranscribing() { keyboard?.dictation = DictationPhase.TRANSCRIBING; updateWorking() }

    /**
     * The piece being spoken, as far as it has been heard (LiveWords.kt): shown in the field in place of the draft
     * before it, so the words are there as they are said. One step in the undo history however often it is revised.
     */
    override fun onDraft(text: String) {
        val shown = LiveWords.shown(text)
        // A draft with no words in it leaves what is shown alone: the next has more to go on.
        if (shown.isEmpty() || liveLost) return
        val ic = currentInputConnection ?: return
        val old = liveDraft
        if (old == null) {
            toolsHeld = false
            finishTyping()
            liveLead = LiveWords.lead(ic.getTextBeforeCursor(1, 0)?.toString().orEmpty())
            val run = liveLead + shown
            editor.insert(run, Kind.DICTATION)
            liveDraft = run
            lastCorrection = null
            swipeStood()
            lastSwipe = null
            notTyped()
            autoSpaced = false
            return
        }
        val run = liveLead + shown
        if (run == old) return
        // No longer just before the cursor (a tap in the text, an app that rewrote it): it stands as it is.
        if (editor.replaceBefore(old, run, Kind.DICTATION)) liveDraft = run else { liveDraft = null; liveLost = true }
    }

    /** Words written down while the take goes on (a piece that ended at a pause): they go in at once, and the mic stays open. */
    override fun onHeard(text: String) = settle(text)

    /**
     * A piece as written down at its pause, [text], takes its draft's place; with no draft shown it goes in at the
     * cursor as it always did. Where it has no words after all, its draft is taken back.
     */
    private fun settle(text: String) {
        val old = liveDraft
        liveDraft = null
        liveLost = false
        if (old != null) {
            val run = if (text.isEmpty()) "" else liveLead + text
            if (editor.replaceBefore(old, run, Kind.DICTATION)) {
                if (text.isNotEmpty()) {
                    if (saying.isNotEmpty()) saying.append(liveLead)
                    saying.append(text)
                }
                return
            }
            // The draft is no longer before the cursor: it stays where it is, and the piece goes in where the cursor is now.
        }
        speak(text)
    }

    /** The take is over: its last words go in, and what it put in altogether can be tidied from the row. */
    override fun onDictated(text: String) {
        takeOpen = false
        keyboard?.dictation = DictationPhase.OFF
        updateWorking()
        settle(text)
        said = saying.toString().takeIf { it.isNotBlank() }
        saying.setLength(0)
        // The row shows its tools now, Tidy first among them, and not suggestions for the take's last word: the
        // chips are for this moment, and the next key takes them away.
        if (said != null) { toolsHeld = true; heldWords = false }
        scheduleRow()
    }

    /** Puts dictated [text] in at the cursor, and keeps it as part of what this take has said. */
    private fun speak(text: String) {
        if (text.isEmpty()) return
        toolsHeld = false
        finishTyping()
        val ic = currentInputConnection ?: return
        // A space before the words where they follow other words, none where they start a line.
        val lead = LiveWords.lead(ic.getTextBeforeCursor(1, 0)?.toString().orEmpty())
        editor.insert(lead + text, Kind.DICTATION)
        // What the take has said, for Tidy to replace: its words, and not the space that parts them from what stood
        // before (an answer comes back trimmed, and "the update. Um can you" must not become "the update.Can you").
        if (saying.isNotEmpty()) saying.append(lead)
        saying.append(text)
        lastCorrection = null
        // A word swiped before the take is done with: the row is the take's now (its Tidy chip), not that word's others.
        swipeStood()
        lastSwipe = null
        // Spoken words are whisper's guess at them, not typing: they are not counted for next-word prediction.
        notTyped()
        autoSpaced = false
    }

    override fun onError(message: String) {
        takeOpen = false
        keyboard?.dictation = DictationPhase.OFF
        saying.setLength(0)
        liveDraft = null
        liveLost = false
        updateWorking()
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (Perf.on) { android.util.Log.i("GhostPerf", "\n" + Perf.report() + "\nmirror       strikes=${mirror.strikes}"); Perf.reset() }
        mirror.drop()
        finishTyping()
        flushDraft()
        // Next-word prediction: the last words are counted, then (off this thread) old counts fade and the file is written.
        runCatching { learner.flush(); nextWords.maintain(); nextWords.save() }
        // What was learned from corrections is written, and Mockingbird's samples looked at again for both.
        runCatching { mistakes.save(); readMockingbird() }
        // An anonymous report, where sharing is on: what was counted is written, and sent if one is due.
        runCatching { if (prefs.shareReports) { reportKeys(); Reports.save(this); Reports.maybeSend(this, prefs) } }
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
            listOf(Plugins.SWIPE, Plugins.PASSWORDS, Plugins.GIFS, Plugins.MARKDOWN, Plugins.TERMINAL, Plugins.CLIPBOARD, Plugins.UNDO, Plugins.INCOGNITO, Plugins.MOCKINGBIRD, Plugins.GHOSTWRITER, Plugins.REWRITE, Plugins.AGENT, Plugins.SHORTCUTS, Plugins.ENHANCE)
                .any { Plugins.isOn(prefs, it) }

    /** Refreshes the row soon: many edits in a moment (a batch, a swipe) make one refresh. */
    private fun scheduleRow() {
        rowHandler.removeCallbacks(refreshTask)
        rowHandler.postDelayed(refreshTask, 25)
    }

    /**
     * One look at the text around the cursor, a moment after each edit: what next-word prediction learns from it
     * ([NextWordLearner.look], also while the row is hidden), then what the row shows for it ([refreshRow]);
     * [refreshWith] does both. The text is read once and shared.
     *
     * On Android 12 and later the reading is the one call that brings what stands before the cursor, what is
     * selected, what follows and where the cursor is (which is what the mirror starts from: FieldMirror.kt), and it
     * is made off the main thread ([reader]): asking is the one thing here that waits on the app, 10 to 16 ms a key on
     * the emulator and far longer in an app that is busy. The answer is used only if nothing was typed or moved while
     * it was on its way ([FieldMirror.stamp]); otherwise the field is read again.
     */
    private fun tick() {
        val wanted = learnAllowed() || row?.visibility == View.VISIBLE
        val conn = currentInputConnection as? MirrorConnection
        // Nothing to learn here and no row to draw: the app is not asked for its text at all. And before Android 12,
        // or in a field whose app does not answer its one reading, the text is read as it always was, here and now.
        if (!wanted || conn == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || plainReads) {
            Perf.time("tick") { refreshWith(if (wanted) Perf.time("tick.read") { currentInputConnection?.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() } else null) }
            return
        }
        // One reading at a time: a refresh asked for while the app is still answering the last is that answer's to
        // give (the row is filled as things stand when it comes), or the reading after it where the field has moved.
        val now = android.os.SystemClock.uptimeMillis()
        if (reading && now - readingSince < READ_LOST_MS) return
        // Android gives up on an app after two seconds, so an answer this late is not coming: this field is read
        // the old way from here on, and the row is not left waiting for it.
        if (reading) { reading = false; readTurn++; plainReads = true; tick(); return }
        reading = true
        readingSince = now
        val turn = ++readTurn
        val stamp = mirror.stamp
        val real = conn.target
        reader.execute {
            val asked = android.os.SystemClock.uptimeMillis()
            val around = runCatching { Perf.time("tick.read") { real.getSurroundingText(READ_BEFORE, READ_AFTER, 0) } }.getOrNull()
            val took = android.os.SystemClock.uptimeMillis() - asked
            rowHandler.post {
                // The service is gone, or this answer was given up on.
                if (gone || turn != readTurn) return@post
                reading = false
                // Something was typed or moved while the app was answering, or the field is another: this is a reading
                // of a field that has moved on. It is read again now (the wait before a refresh has been had).
                if (stamp != mirror.stamp || mirrored !== conn) { tick(); return@post }
                runCatching {
                    Perf.time("tick") {
                        conn.took(around, READ_BEFORE, READ_AFTER)
                        val at = around?.let { minOf(it.selectionStart, it.selectionEnd) } ?: -1
                        when {
                            // What was just read is the field itself until the row is filled: the row's further
                            // questions (what follows the cursor, what is selected) are answered from it, and the
                            // app is asked once where it was asked three or four times.
                            around != null && at >= 0 && at <= around.text.length ->
                                mirror.during { refreshWith(around.text.subSequence(maxOf(0, at - CONTEXT_CHARS), at).toString()) }
                            // Nothing after a long wait is Android giving up on an app that is not answering: it is not asked again.
                            took >= GAVE_UP_MS -> refreshWith(null)
                            // The app does not answer this call: from here on this field is read the old way.
                            else -> { plainReads = true; refreshWith(conn.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString()) }
                        }
                    }
                }.onFailure { android.util.Log.e("GhostKeyboard", "row refresh failed", it) }
            }
        }
    }

    /**
     * The row's readings of the field are made on this thread (Matt, 2026-11-12: "do a performance pass on the
     * keyboard to make sure everything is as fast as possible"): the keys answer the next touch while the app takes
     * its time, where the whole keyboard used to stand still until it had answered. [reading]: one is being made.
     */
    private val reader = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "field-reader").apply { isDaemon = true } }
    private var reading = false
    private var readingSince = 0L
    private var readTurn = 0

    /** The service has been destroyed: an answer that comes after that is for nobody. */
    private var gone = false

    /**
     * What a refresh does with its reading: [before] is the [CONTEXT_CHARS] before the cursor (before the selection,
     * where there is one), null where the app was not asked or did not answer.
     */
    private fun refreshWith(before: String?) {
        val learn = learnAllowed()
        val place = if (before != null && (learn || predictionAllowed())) runCatching { placeAt(before) }.getOrNull() else null
        // Learning must never stop the row from drawing. The AI's own typing is not the person's.
        if (typing == null) runCatching { learner.look(before, place, learn) }.onFailure { android.util.Log.e("GhostKeyboard", "next-word learning failed", it) }
        Perf.time("tick.row") { refreshRow(before, place) }
    }

    /** Where the cursor stands among the words of [before], for next-word prediction; null where nothing is predicted. */
    private fun placeAt(before: String): NextWordText.Place? =
        NextWordText.place(before, ::isWordChar, before.length >= CONTEXT_CHARS, locale, sentenceEnds())

    /** Greek ends a question with ; (and its look-alike U+037E): a sentence start there, a pause everywhere else. */
    private fun sentenceEnds(): String = if (language.spec.script == Script.GREEK) ";\u037e" else ""

    /** An app that is a terminal, or one whose profile turns the terminal keys on: shell commands are not sentences. */
    private fun terminalApp(): Boolean =
        AppContext.kind(currentInputEditorInfo?.packageName) == AppKind.TERMINAL || profile?.flag("terminal") == true

    /**
     * Next words are offered here: Autocorrect is added (it owns the word lists and the row's suggestions), both
     * "Suggestions above the keys" and "Suggest the next word" are on, the field is one autocorrect works in (not a
     * password, an address or one that asks for no suggestions), there are words for this layout, and the app is
     * not a terminal.
     */
    private fun predictionAllowed(): Boolean =
        prefs.suggestions && prefs.nextWord && autocorrectAllowed() && autocorrect.active && !terminalApp()

    /**
     * What is typed here may be counted for next-word prediction: prediction and its learning are on, and the field
     * is ordinary writing. Never a password, never while incognito or where the field asks keyboards not to learn
     * ([incognito] covers both), never an address, a name, a search or a field that wants no suggestions, never this
     * app's own fields, and never a terminal.
     */
    private fun learnAllowed(): Boolean {
        if (!prefs.nextWord || !prefs.nextWordLearn || !Plugins.isOn(prefs, Plugins.AUTOCORRECT) || !autocorrect.active) return false
        val info = currentInputEditorInfo ?: return false
        if (incognito || isPassword(info) || !collectable(info) || terminalApp()) return false
        if (info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) return false
        return info.imeOptions and EditorInfo.IME_MASK_ACTION != EditorInfo.IME_ACTION_SEARCH
    }

    /**
     * Something reached the field that the keys did not type (a paste, dictation, the AI): next-word prediction
     * neither counts it nor goes by it as the word before one.
     */
    private fun notTyped() = learner.notTyped()

    /**
     * One more look at the text right now, before something takes it away (enter sends the message). Where nothing
     * may be learned (a password, incognito, the switch off) the app is not asked for its text at all.
     */
    private fun learnNow(ic: InputConnection) {
        if (typing != null) return
        if (!learnAllowed()) { learner.look(null, null, false); return }
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString()
        learner.look(before, before?.let(::placeAt), true)
    }

    /** The language whose keys are being typed on: it leads next-word prediction where several share a script. */
    private fun leadLanguage(): String? = language.active.languages.firstNotNullOfOrNull { it.dict }

    /** Next-word prediction's starter tables follow the word lists: the active layout's languages first. */
    private fun setPredictionLanguages() {
        val lead = language.active.languages.mapNotNull { it.dict }
        val on = prefs.nextWord && Plugins.isOn(prefs, Plugins.AUTOCORRECT)
        nextWords.setLanguages(lead + language.dictionaries.filter { it !in lead }, on, language.enabled.mapNotNull { it.dict })
    }

    /** [word] as shift has it: a capital first with shift on (a sentence's start), all capitals with caps lock. */
    private fun shifted(word: String): String = when (keyboard?.shift ?: ShiftState.OFF) {
        ShiftState.LOCKED -> word.uppercase(locale)
        ShiftState.ON -> word.replaceFirstChar { c -> c.toString().uppercase(locale).takeIf { it.length == 1 } ?: c.toString() }
        ShiftState.OFF -> word
    }

    /**
     * What the row shows, the first that applies: the GIF search; a password manager's logins; a swiped word's
     * alternatives; autocorrect's suggestions for the word being typed (or the word a fix replaced, to keep it);
     * after a space, the three likeliest next words; and with nothing to suggest, the plugins' own buttons. Words
     * are about three at a time, with the tools after them in the strip ([showWords]). [before] is the text before the cursor as [tick] read it, and [place] where
     * that puts the cursor for next-word prediction.
     */
    private fun refreshRow(before: String?, place: NextWordText.Place?) {
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
            // One-time codes from texts lead the list for as long as they are held: they are not in the history's file.
            val codes = codeItems(fresh = false)
            items += codes
            if (clips.isEmpty() && codes.isEmpty()) items += RowItem("clips-none", label = "Nothing copied yet")
            // How a clip is pinned, said until one has been: a hold is not something anyone finds by themselves.
            if (clips.isNotEmpty() && !ClipHistory.pinnedOnce(this)) items += RowItem("clips-hint", label = "Hold a clip to pin it", icon = RowIcon.PIN, dim = true)
            // The pinned lead the list, each with its pin; a hold pins a clip or lets it go, a tap pastes either.
            for (clip in clips) items += RowItem(
                "clip-${clip.at}-${clip.pinned}", label = preview(clip.text), icon = RowIcon.PIN.takeIf { clip.pinned }, tint = RowTint.CLIPBOARD.takeIf { clip.pinned }, chip = true,
                description = "${if (clip.pinned) "Pinned. " else ""}${preview(clip.text)}. Hold to ${if (clip.pinned) "unpin" else "pin"}.",
                onLong = { pinClip(clip) },
            ) { pasteClip(clip.text) }
            // Clearing takes the copies; what is pinned stays, so with nothing but pins there is nothing to clear.
            if (clips.any { !it.pinned }) items += RowItem("clips-clear", icon = RowIcon.TRASH, chip = true, description = "Clear the copies. Pinned clips stay.") { ClipHistory.clear(this); scheduleRow() }
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
            // A code that has just arrived comes before a password manager's logins: this is the field it is for.
            items += codeItems(fresh = true)
            inlineViews.forEachIndexed { i, v -> items += RowItem("inline-$i", view = v) }
            strip.setItems(items)
            return
        }
        lastSwipe?.let { swipe ->
            // About three, as every row of words is: the word it was read as is always one of them.
            val shown = swipe.choices.take(WORDS).let { if (swipe.word in it) it else listOf(swipe.word) + it.take(WORDS - 1) }
            shown.forEachIndexed { i, w -> items += RowItem("swipe-$i-$w", label = w, bold = w == swipe.word, wide = true) { replaceSwipe(w) } }
            showWords(strip, items)
            return
        }
        if (!toolsHeld) currentInputConnection?.let { ic -> if (selStart == selEnd && contextual(ic, before, items)) { strip.setItems(items); return } }
        // An arrow tapped at the end of a row of words: the words go on, so the arrow stays where the thumb is.
        val wordsHeld = toolsHeld && heldWords
        // Only while there are words to suggest from: on a layout with no word list (Korean, plain ABC) every word
        // would otherwise get a quoted "keep" chip and nothing else.
        if ((!toolsHeld || wordsHeld) && Plugins.isOn(prefs, Plugins.AUTOCORRECT) && prefs.suggestions && autocorrectAllowed() && autocorrect.active) {
            val ic = currentInputConnection
            val correction = lastCorrection
            // Next-word prediction: what is likely here, where it may be offered. Made in well under a millisecond
            // (NextWords.timed notes how long), from tables and counts already in memory.
            // Not while the row is saying "Offline is on": that note has its three seconds.
            val noting = offlineOn && android.os.SystemClock.uptimeMillis() < offlineNoteUntil
            val predict = place?.takeIf { !noting && predictionAllowed() }
            if (correction != null) {
                val (typed, fixed, _) = correction
                items += RowItem("keep-$typed", label = "\u201C$typed\u201D", wide = true) { currentInputConnection?.let { undoCorrection(it, keepSeparator = true) } }
                // The fix stands in the text already: beside the way back to what was typed, the two likeliest next words.
                val next = if (predict != null && predict.typing.isEmpty() && before != null && atWordStart(before, ic)) nextWords.timed { nextWords.at(predict).top(2) }.map(::shifted) else emptyList()
                if (next.isEmpty()) items += RowItem("fixed-$fixed", label = fixed, bold = true, wide = true) { lastCorrection = null; scheduleRow() }
                else next.forEach { w -> items += RowItem("nw-$w", label = w, wide = true) { pickPrediction(w) } }
            } else if (ic != null && selStart == selEnd) {
                val word = if (before != null) currentWord(before) else currentWord(ic)
                if (word.isEmpty() && predict != null && before != null && atWordStart(before, ic)) {
                    // After a space, or at a field's or sentence's start: the three likeliest next words, the
                    // likeliest in the middle as Gboard has its pick, then the tools, the first of them peeking in.
                    // While incognito two, so its chip fits in front of them: nothing may cover the sign that it is on.
                    val tools = tools()
                    val private = incognito
                    val words = nextWords.timed { nextWords.at(predict).top(if (private) 2 else 3) }.map(::shifted).distinct()
                    if (words.isNotEmpty()) {
                        val order = if (words.size >= 2) listOf(words[1], words[0]) + words.drop(2) else words
                        // "Incognito on", a clip copied a moment ago and a waiting update come before any word.
                        val first = tools.filter(::leads)
                        items += first
                        order.forEach { w -> items += RowItem("nw-$w", label = w, wide = true) { pickPrediction(w) } }
                        // The everyday tools next (or pinned before it all, where the row is wide), then the rest.
                        val at = items.size
                        items += tools.filter { it !in first }
                        fill(strip, items, at)
                        return
                    }
                }
                if (word.isNotEmpty()) {
                    val taps = tapsFor(word)
                    val s = if (predict != null) autocorrect.suggest(word, nextWords.timed { nextWords.at(predict).ready() }, taps) else autocorrect.suggest(word, null, taps)
                    if (!s.typedIsWord) items += RowItem("typed-$word", label = "\u201C$word\u201D", wide = true) { keepTyped(word) }
                    s.words.forEachIndexed { i, w ->
                        items += RowItem("s-$i-$w", label = w, bold = i == 0 && (s.fix != null || s.typedIsWord), wide = true) { pickSuggestion(word, w) }
                    }
                    // About three words, the likeliest (Matt, 2026-11-12: "only show about three of them"): the word as
                    // typed, where it is offered, is one of the three.
                    while (items.size > WORDS) items.removeAt(items.size - 1)
                    // pizza → 🍕: an emoji for the word, tapped in after it. A small chip after the words, not one of them.
                    if (Plugins.isOn(prefs, Plugins.EMOJI) && prefs.emojiSuggest) Emoji.forWord(word).forEach { e ->
                        val shown = e.toned(prefs.emojiSkin)
                        items += RowItem("se-$shown", label = shown, chip = true) { editor.insert(" $shown", Kind.EMOJI); Emoji.used(this, shown); scheduleRow() }
                    }
                }
            }
        }
        // A network chip was tapped while Offline is on: for a few seconds the tools give way to why, and the way to Settings.
        if (items.isEmpty() && offlineOn && android.os.SystemClock.uptimeMillis() < offlineNoteUntil) {
            items += RowItem("offline-note", label = Net.OFFLINE_MESSAGE, icon = RowIcon.OFFLINE, fill = true)
            items += RowItem("offline-settings", label = "Settings", bold = true, chip = true) { openOfflineSettings() }
            strip.setItems(items)
            return
        }
        // Nothing to suggest: the tools, the everyday ones first but for what is about this very moment.
        if (items.isEmpty()) {
            val tools = tools()
            // No word to suggest here, and the row was showing words when the arrow was tapped: their room stays,
            // empty, and the tools follow it as they did.
            if (wordsHeld) {
                fill(strip, listOf(RowItem("words-held", label = "", wide = true)) + tools.filter { !leads(it) }, 1)
                return
            }
            val first = tools.filter(::leads)
            fill(strip, first + tools.filter { it !in first }, first.size)
            return
        }
        showWords(strip, items)
    }

    /**
     * Fills the row with [items], the everyday tools pinned before them (on a row too narrow for that, put in at [at]:
     * [PluginRow.setItems]). Whether they are pinned is asked as if both arrows were among them, so the row keeps its
     * shape as Undo and Redo come and go.
     */
    private fun fill(strip: PluginRow, items: List<RowItem>, at: Int) {
        val everyday = everyday()
        val missing = if (Plugins.isOn(prefs, Plugins.UNDO)) 2 - everyday.count { it.guard } else 0
        // GIFs lead the tools that follow the words (and the everyday ones too, on a row too narrow to pin those).
        val gif = gifTool()
        strip.setItems(if (gif == null) items else items.take(at) + gif + items.drop(at), everyday, at, everyday.size + missing)
    }

    /**
     * The row with words in it (Matt, 2026-11-12: "Center the auto complete words and only show about three of them
     * reserve some space on the right to show at least one toolbar icon"): the words, the pick in the middle, then
     * whatever small chips came with them (an emoji for the word), then the tools, so the first of the tools is in
     * view at the row's end and the rest are a slide away ([PluginRow.setItems] centres the words and keeps that
     * room). The tools about this very moment are not among them: they come back when the word is done.
     */
    private fun showWords(strip: PluginRow, items: List<RowItem>) {
        val shown = middleBold(items.filter { it.wide }) + items.filter { !it.wide }
        fill(strip, shown + tools().filter { !leads(it) }, shown.size)
    }

    /** A tool about this very moment, which comes before everything: the selection's, a clip just copied, "Incognito on", an update. */
    private fun leads(item: RowItem): Boolean =
        item.id.startsWith("fresh-") || item.id == INCOGNITO_ON || item.id == "update" || item.id.startsWith("voice") || item.id == "ghost-save" || item.id.startsWith("said-") || ((item.id.startsWith("rewrite") || item.id.startsWith("enhance")) && item.bold)

    /**
     * The tools reached for in every app: the clipboard, the { } button, undo and redo. They stand at the row's
     * start, before the suggested words (Matt, 2026-11-12: "Clipboard, forward and back, and emoji should be left
     * aligned on the toolbar before the auto suggest"), on every row but one too narrow for them and two words
     * ([PluginRow.pins]). Icons only. The clipboard and the { } button come first, because they are always there;
     * the arrows, which are there only while each has something to do, come and go after them, so no button moves
     * when one does. The { } button stands where the emoji button stood until 0.16.2: emoji is a key on the board
     * now, in the { } key's place (Matt, the same day; [Layouts.withEmoji]). GIFs, which were among them until
     * 0.16.0, lead the tools that follow the words ([gifTool]).
     */
    private fun everyday(): List<RowItem> {
        val items = ArrayList<RowItem>(4)
        if (Plugins.isOn(prefs, Plugins.CLIPBOARD) && !incognito) items += RowItem("clips", icon = RowIcon.CLIPBOARD, tint = RowTint.CLIPBOARD, chip = true, description = "Clipboard") { showingClips = true; scheduleRow() }
        // Where the emoji button was (Matt, 2026-11-12: "Move the emoji key to where the dev tools toggle is ... And
        // move the code button up to where the emoji button used to be in the toolbar"): the { } key, as a button.
        // A tap shows the code keys and another the letters again; it is lit while they are showing; and a hold opens
        // Settings, as a hold on the key always did.
        if (codeInRow) {
            val on = keyboard?.page == Page.DEV
            items += RowItem("code-$on", icon = RowIcon.BRACES, tint = RowTint.CODE.takeIf { on }, chip = true, description = if (on) "Code keys, showing. Letters" else "Code keys", onLong = { onOpenSettings() }) { toggleCode() }
        }
        if (Plugins.isOn(prefs, Plugins.UNDO)) {
            // Each is there only while it has something to do (Matt, 2026-11-12: "only show undo and redo when an undo
            // and redo is available"): the keyboard's own last step, or the app's undo where it has none. They were
            // always there, and dim with nothing to do.
            // An arrow's own tap can take it away (the last Undo), and Redo then stands where it stood: each is
            // guarded, so a thumb still tapping does not land on what took its place (TapGuard).
            if (editor.canUndo) items += RowItem("undo", icon = RowIcon.UNDO, chip = true, description = "Undo", guard = true) { undoTap(redo = false) }
            if (editor.canRedo) items += RowItem("redo", icon = RowIcon.REDO, chip = true, description = "Redo", guard = true) { undoTap(redo = true) }
        }
        return items
    }

    /**
     * The GIF button, the first of the tools that follow the words: on a phone it is the tool in view at the row's
     * end. GIFs need the network: with Offline on the chip stays, faint, and a tap says why.
     */
    private fun gifTool(): RowItem? = when {
        !Plugins.isOn(prefs, Plugins.GIFS) -> null
        offlineOn -> RowItem("gifs-offline", icon = RowIcon.GIF_TEXT, tint = RowTint.GIFS, chip = true, description = "GIFs · Offline", dim = true) { sayOffline() }
        else -> RowItem("gifs", icon = RowIcon.GIF_TEXT, tint = RowTint.GIFS, chip = true, description = "GIFs") { openGifs() }
    }

    /**
     * A chip for something that needs the network (GIFs, Rewrite, In my voice). With Offline on it is still there, so
     * nothing seems to have vanished, but faint and saying so ("GIFs · Offline"), and a tap explains instead of acting.
     */
    private fun netItem(
        id: String, label: String?, icon: RowIcon? = null, tint: RowTint? = null, description: String? = null, bold: Boolean = false, action: () -> Unit,
    ): RowItem =
        if (offlineOn) RowItem("$id-offline", label = "${label ?: description} · Offline", icon = icon, tint = tint, chip = true, description = description, dim = true) { sayOffline() }
        else RowItem(id, label = label, icon = icon, tint = tint, bold = bold, chip = true, description = description, onTap = action)

    /** The row says "Offline is on" for three seconds, with a Settings chip beside it. */
    private fun sayOffline() {
        offlineNoteUntil = android.os.SystemClock.uptimeMillis() + OFFLINE_NOTE_MS
        rowHandler.removeCallbacks(offlineNoteDone)
        rowHandler.postDelayed(offlineNoteDone, OFFLINE_NOTE_MS)
        scheduleRow()
    }

    /** Opens the settings app on its Offline page: where the switch is, and what it stops. */
    private fun openOfflineSettings() {
        requestHideSelf(0)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_OPEN, "offline"))
    }

    /**
     * The plugins' buttons, shown when nothing is being typed: a waiting update, incognito's state, a just-copied
     * clip, terminal keys and markdown (in the apps they suit, or everywhere). Where next words are offered they
     * come first and these scroll after them, but for "Incognito on", the fresh clip and the update ([refreshRow]).
     * Undo and redo, the clipboard, emoji and GIFs are not among them: they lead the row ([everyday]).
     */
    private fun tools(): List<RowItem> {
        val items = ArrayList<RowItem>()
        // A release is waiting (the dot the settings cog used to carry): one tap opens the app, which installs it.
        if (updateWaiting) items += RowItem("update", label = "Update", icon = RowIcon.SETTINGS, bold = true, chip = true, description = "An update is waiting") { onOpenSettings() }
        val app = AppContext.kind(currentInputEditorInfo?.packageName)
        val ghost = Plugins.isOn(prefs, Plugins.GHOSTMD)
        val inGhost = AppContext.isGhostMd(currentInputEditorInfo?.packageName)
        // Straight after a dictation: what the take put in, tidied, or made a prompt of. Each sends those words to
        // the person's own AI, and only when tapped; with no AI set up, or Offline on, there is nothing to offer.
        if (Plugins.isOn(prefs, Plugins.DICTATION) && said != null && aiAllowed() && !offlineOn && selStart == selEnd && AiClient.ready(prefs)) {
            if (prefs.dictationTidy) items += netItem("said-tidy", "Tidy", icon = RowIcon.WAND, tint = RowTint.AI, bold = true) { tidySaid(prompt = false) }
            if (Plugins.isOn(prefs, Plugins.ENHANCE)) items += netItem("said-prompt", "As a prompt", icon = RowIcon.SPARKLE, tint = RowTint.AI, bold = true) { tidySaid(prompt = true) }
        }
        // Mockingbird: the selection rewritten as you.
        if (Plugins.isOn(prefs, Plugins.MOCKINGBIRD) && aiAllowed() && selStart != selEnd) {
            currentInputConnection?.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }?.let { selected ->
                items += netItem("voice", "In my voice", icon = RowIcon.FEATHER, tint = RowTint.AI, bold = true) { rewriteSelection(selected) }
            }
        }
        // Rewrite: the selection, or the whole field when nothing is selected, fixed, shortened, restyled or translated.
        if (Plugins.isOn(prefs, Plugins.REWRITE) && aiAllowed() && fieldHasText()) {
            // On the phone's own model Rewrite needs no network: its chip is as it always is, Offline on or off.
            items += if (LocalAi.serves(this, prefs)) RowItem("rewrite", label = "Rewrite", icon = RowIcon.WAND, tint = RowTint.AI, bold = selStart != selEnd, chip = true) { showingRewrite = true; scheduleRow() }
                else netItem("rewrite", "Rewrite", icon = RowIcon.WAND, tint = RowTint.AI, bold = selStart != selEnd) { showingRewrite = true; scheduleRow() }
        }
        // Prompt enhancer: the selection, or the whole field, as a clearer prompt. It leads the row where what is
        // typed is a prompt (an AI chat app, or an app the agent keys show in) and with a selection; elsewhere it
        // waits among the tools.
        if (Plugins.isOn(prefs, Plugins.ENHANCE) && aiAllowed() && fieldHasText()) {
            val prompts = app == AppKind.ASSISTANT || (profile?.flag("agent") ?: (Plugins.isOn(prefs, Plugins.AGENT) && AgentKeys.suits(app)))
            items += netItem("enhance", "Enhance", icon = RowIcon.SPARKLE, tint = RowTint.AI, bold = prompts || selStart != selEnd) { enhance() }
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
                incognitoOn = !incognitoOn
                // Going incognito drops the word that was waiting to be counted; coming out of it, what was typed
                // meanwhile is fenced off from learning (the next look at the text sees that learning was off).
                learner.drop()
                scheduleRow()
            }
        }
        // A one-time code that arrived by text a moment ago (SmsCodes.kt). Incognito does not hide it: nothing of it
        // is kept either way, and a sign-in is where incognito is most often on.
        items += codeItems(fresh = true)
        if (Plugins.isOn(prefs, Plugins.CLIPBOARD) && !incognito) {
            ClipHistory.fresh?.takeIf { System.currentTimeMillis() - it.at < 90_000 }?.let { clip ->
                items += RowItem("fresh-${clip.at}", label = preview(clip.text), icon = RowIcon.CLIPBOARD, tint = RowTint.CLIPBOARD, bold = true, chip = true) { pasteClip(clip.text) }
            }
        }
        // Agent keys (AgentKeys.kt): what a coding agent is driven with, before the terminal's own keys. An app's
        // profile decides for that app; without one they show where the plugin is added and the app suits them.
        val agent = profile?.flag("agent") ?: (Plugins.isOn(prefs, Plugins.AGENT) && AgentKeys.suits(app))
        if (agent) for (chip in AgentKeys.chips(app, enterSends())) {
            items += RowItem("agent-${chip.id}", label = chip.label, chip = true, description = chip.says) { agentTap(chip) }
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
            // The agent keys have a / of their own, nearer the thumb: the row does not show two.
            for (c in listOf("|", "~", "/", "-", "&&", ">")) if (!(agent && c == "/")) items += RowItem("t$c", label = c, chip = true) { editor.type(c) }
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

    /**
     * One-time codes from texts as chips: the one that has just arrived, for the row's start ([fresh]), or every
     * one still held, for the clipboard's list. None with the switch off. A chip of its own (CodeChip.kt), not the
     * clipboard's: its own colour, a text message on it, and the time it has left there running round its edge.
     */
    private fun codeItems(fresh: Boolean): List<RowItem> {
        if (!SmsCodes.wanted(prefs)) return emptyList()
        val now = android.os.SystemClock.elapsedRealtime()
        val codes = if (fresh) listOfNotNull(SmsCodes.fresh(now)) else SmsCodes.live(now)
        return codes.map { code ->
            RowItem(
                "${if (fresh) "fresh-code" else "code"}-${code.at}", label = code.text, tint = RowTint.CODE, chip = true,
                code = RowCode(code.at, if (fresh) SmsCodes.FRESH_MS else SmsCodes.KEEP_MS),
                description = "One-time code ${CodeLook.spoken(code.text)}, from a text message",
            ) { pasteCode(code) }
        }
    }

    /** Types a one-time code and lets go of it: it is for one use. Pasted, not typed: nothing learns from it. */
    private fun pasteCode(code: SmsCodes.Code) {
        finishTyping()
        editor.insert(code.text, Kind.PASTE)
        notTyped()
        autoSpaced = false
        toolsHeld = true; heldWords = false
        showingClips = false
        SmsCodes.used(code)
        scheduleRow()
    }

    // --- text shortcuts (Shortcuts.kt) --------------------------------------------------------------------------------

    /** Text shortcuts are read here: the plugin is added, and the field is not a password. */
    private fun shortcutsHere(): Boolean = Plugins.isOn(prefs, Plugins.SHORTCUTS) && !isPassword(currentInputEditorInfo)

    /** What [entry] types now: its text with today's date, the time and the newest clip put in where it asks for them. */
    private fun shortcutText(entry: Shortcuts.Entry): String {
        // The clipboard is read only where the phrase asks for it, and never while incognito or with its plugin off.
        val clip = if ("{clipboard}" in entry.text && Plugins.isOn(prefs, Plugins.CLIPBOARD) && !incognito) ClipHistory.recent(this).firstOrNull()?.text else null
        val now = java.time.LocalDateTime.now()
        return Shortcuts.expanded(entry.text, now.toLocalDate().toString(), "%02d:%02d".format(now.hour, now.minute), clip)
    }

    /**
     * A space or enter straight after a shortcut: its letters become what it stands for, followed by [separator].
     * True when it did. Not where shortcuts wait for a tap ([KeyboardPrefs.shortcutsAuto] off), where the letters were
     * just kept as typed, or where the cursor stands inside a longer run of characters.
     */
    private fun expandShortcut(ic: InputConnection, separator: String): Boolean {
        if (!shortcutsHere()) return false
        val entries = Shortcuts.all(prefs)
        if (entries.isEmpty()) return false
        val before = ic.getTextBeforeCursor(Shortcuts.MAX_SHORTCUT + 1, 0)?.toString() ?: return false
        val entry = Shortcuts.find(entries, before) ?: return false
        val typed = Shortcuts.token(before)
        // Letters kept as typed are let be, once, whether or not a space would have expanded them: the caller sees
        // they were, and keeps autocorrect off them too.
        if (typed.equals(keptShortcut, ignoreCase = true)) { keptShortcut = null; return false }
        if (!prefs.shortcutsAuto) return false
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && !after[0].isWhitespace()) return false
        val text = shortcutText(entry)
        // One step in the history, after the separator as typed: undo gives back the letters and their space.
        if (!editor.replaceBefore(typed, text + separator, Kind.SNIPPET, typedFirst = separator)) return false
        lastShortcut = Triple(typed, text, separator)
        lastCorrection = null
        // A phrase put in whole was not typed word by word: next-word prediction does not count it.
        notTyped()
        return true
    }

    /** The row's chip for a shortcut was tapped: [typed], just before the cursor, becomes [text]. */
    private fun useShortcut(typed: String, text: String) {
        finishTyping()
        if (!editor.replaceBefore(typed, text, Kind.SNIPPET)) { scheduleRow(); return }
        lastCorrection = null
        lastShortcut = null
        notTyped()
        scheduleRow()
    }

    /** A clip was held in the clipboard's list: it is pinned, or let go back among the copies. */
    private fun pinClip(clip: ClipList.Clip) {
        if (!ClipHistory.pin(this, clip.text, !clip.pinned)) {
            android.widget.Toast.makeText(this, "${ClipList.MAX_PINS} clips are pinned already. Unpin one first.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        scheduleRow()
    }

    private fun pasteClip(text: String) {
        finishTyping()
        editor.insert(text, Kind.PASTE)
        // Pasted words were not typed: next-word prediction does not count them.
        notTyped()
        autoSpaced = false
        toolsHeld = true; heldWords = false
        ClipHistory.fresh = null
        showingClips = false
        scheduleRow()
    }

    /**
     * Whether enter in this field does something other than start a new line: sends the message, searches, goes on
     * to the next field ([enter] asks the same of it). A new-line chip is only worth its place where it does.
     */
    private fun enterSends(): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) return false
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        return action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED
    }

    /**
     * An agent key was tapped (AgentKeys.kt). A press is a key press with its modifiers, as the terminal row's Esc
     * and Ctrl send theirs: what it does is the app's business, and the field is checked after it. What is typed or
     * wrapped is an edit like any other, and a step in the history.
     */
    private fun agentTap(chip: AgentKeys.Chip) {
        finishTyping()
        lastCorrection = null
        lastSwipe = null
        when (val does = chip.does) {
            is AgentKeys.Does.Press -> {
                val code = when (does.code) {
                    AgentKeys.Code.TAB -> KeyEvent.KEYCODE_TAB
                    AgentKeys.Code.ESCAPE -> KeyEvent.KEYCODE_ESCAPE
                    AgentKeys.Code.C -> KeyEvent.KEYCODE_C
                    AgentKeys.Code.J -> KeyEvent.KEYCODE_J
                    AgentKeys.Code.R -> KeyEvent.KEYCODE_R
                }
                var meta = 0
                if (does.ctrl) meta = meta or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
                if (does.shift) meta = meta or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                repeat(does.times) { sendKey(code, meta) }
            }
            is AgentKeys.Does.Type -> editor.type(does.text)
            is AgentKeys.Does.Wrap -> wrap(does.open, does.close)
        }
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
     * [[link]]'s note titles or a #tag's tags. True when it filled [items]. [text] is that text as [tick] read it.
     */
    private fun contextual(ic: InputConnection, text: String?, items: ArrayList<RowItem>): Boolean {
        val before = text ?: return false
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
            // Never silent while Offline is on either: the request stays as typed, and the row says why (a tap opens the switch).
            if (offlineOn) {
                if (tag != null || FILL_OPEN.containsMatchIn(before)) {
                    items += RowItem("fill-offline", label = "Ghostwriter is off: ${Net.OFFLINE_MESSAGE}", icon = RowIcon.SPARKLE, chip = true, fill = true) { openOfflineSettings() }
                    return true
                }
            } else {
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
        }
        // A text shortcut standing by itself before the cursor: what it stands for, a tap away (and a space away,
        // where shortcuts expand by themselves), with the letters themselves beside it for when they are what is meant.
        if (shortcutsHere()) Shortcuts.find(Shortcuts.all(prefs), before)?.let { entry ->
            val typed = Shortcuts.token(before)
            if (!typed.equals(keptShortcut, ignoreCase = true)) {
                val text = shortcutText(entry)
                items += RowItem("shortcut-${entry.shortcut}", label = preview(text), bold = true, chip = true, fill = true, description = "Shortcut: ${preview(text)}") { useShortcut(typed, text) }
                items += RowItem("shortcut-keep-$typed", label = "\u201C$typed\u201D", chip = true, description = "Keep $typed as typed") { keptShortcut = typed; scheduleRow() }
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

    /** A <<request>> just closed before the cursor: fill it, where Ghostwriter fills on its own (never while Offline is on). */
    private fun checkFill() {
        if (offlineOn || !Plugins.isOn(prefs, Plugins.GHOSTWRITER) || !prefs.ghostwriterAuto || !aiAllowed()) return
        if (aiJob?.running == true) return
        val before = currentInputConnection?.getTextBeforeCursor(2000, 0)?.toString() ?: return
        if (Ghostwriter.CLOSED.containsMatchIn(before)) fillAtCursor()
    }

    /** Fills the <<request>> the cursor is on (or just after). */
    private fun fillAtCursor() {
        val ic = currentInputConnection ?: return
        if (offlineOn || !aiAllowed()) return
        val before = ic.getTextBeforeCursor(2000, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(400, 0)?.toString().orEmpty()
        val (request, tag) = Ghostwriter.tagAt(before, after) ?: return
        val at = (before + after).lastIndexOf(tag, before.length - 1)
        val around = (before + after)
        aiJob?.cancelled = true
        aiJob = Ghostwriter.start(this, prefs, request, tag, around.substring(0, at.coerceAtLeast(0)), around.substring((at + tag.length).coerceAtMost(around.length)), currentInputEditorInfo?.packageName) { onAiDone(it) }
        scheduleRow()
    }

    /** The accuracy record may keep this: its switch on, and a field whose text says how you type, not incognito. */
    private fun accuracyOn(): Boolean {
        if (!prefs.accuracyLog || incognito) return false
        val info = currentInputEditorInfo
        if (info == null || isPassword(info) || !collectable(info)) { AccuracyLog.drop(); return false }
        return true
    }

    /** A finished word into the accuracy record, with the keys as they are and the two words before it. */
    private fun recordWord(typed: String, auto: String?, before: String) {
        runCatching {
            keyboard?.letterLayout()?.let { (keys, w) -> if (keys.isNotEmpty()) AccuracyLog.layout(this, language.spec.id, language.dictionaries, keys, w, keyboard?.spaceRect()) }
            val context = before.trimEnd().split(Regex("\\s+")).filter { it.isNotEmpty() }.takeLast(2).joinToString(" ")
            AccuracyLog.word(this, typed, auto, context, currentInputEditorInfo?.packageName)
        }
    }

    private fun rewriteSelection(selected: String) {
        if (offlineOn) { sayOffline(); return }
        aiJob?.cancelled = true
        aiJob = Ghostwriter.rewrite(this, prefs, selected, currentInputEditorInfo?.packageName) { onAiDone(it) }
        scheduleRow()
    }

    /** The Rewrite plugin: [mode] done to the selection, or to everything in the field when nothing is selected. */
    private fun rewriteWith(mode: Rewrite.Mode) {
        showingRewrite = false
        if (offlineOn && !LocalAi.serves(this, prefs)) { sayOffline(); return }
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
        if (offlineOn && !LocalAi.serves(this, prefs)) { sayOffline(); return }
        aiJob?.cancelled = true
        aiJob = Ghostwriter.rewriteAs(this, prefs, selected, mode) { onAiDone(it) }
        scheduleRow()
    }

    /**
     * The Prompt enhancer plugin: the selection, or everything in the field when nothing is selected, as a clearer
     * prompt. The field is selected the way Rewrite selects it, so undo puts back the cursor and not a selection.
     */
    private fun enhance() {
        if (offlineOn) { sayOffline(); return }
        val ic = currentInputConnection
        if (ic == null || !aiAllowed()) { scheduleRow(); return }
        var selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isBlank()) {
            val was = editor.selectionNow()
            ic.performContextMenuAction(android.R.id.selectAll)
            editor.sent()
            selected = ic.getSelectedText(0)?.toString().orEmpty()
            editor.rememberRewrite(selected, was)
        }
        if (selected.isBlank()) { scheduleRow(); return }
        startEnhance(selected)
    }

    private fun startEnhance(selected: String, spoken: Boolean = false) {
        if (offlineOn) { sayOffline(); return }
        aiJob?.cancelled = true
        aiJob = Ghostwriter.enhance(this, prefs, selected, currentInputEditorInfo?.packageName, spoken) { onAiDone(it) }
        scheduleRow()
    }

    /**
     * Tidy or As a prompt, straight after a dictation: the words that take put in are selected, exactly those and no
     * others, and the answer takes their place (undo puts them back, with the cursor where it was). Where they no
     * longer stand just before the cursor (something else was typed, the cursor was moved, the app changed them)
     * nothing is selected and nothing is sent.
     */
    private fun tidySaid(prompt: Boolean) {
        val text = said
        said = null
        if (offlineOn) { sayOffline(); return }
        val ic = currentInputConnection
        if (text == null || ic == null || !aiAllowed() || selStart != selEnd || selEnd < text.length) { scheduleRow(); return }
        if (ic.getTextBeforeCursor(text.length, 0)?.toString() != text) { scheduleRow(); return }
        val was = editor.selectionNow()
        ic.setSelection(selEnd - text.length, selEnd)
        editor.sent()
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected != text) {
            // The app did not select what was asked for: the cursor goes back, and nothing is sent.
            ic.setSelection(selEnd, selEnd)
            editor.sent()
            scheduleRow()
            return
        }
        editor.rememberRewrite(selected, was)
        if (prompt) startEnhance(selected, spoken = true) else startTidy(selected)
    }

    private fun startTidy(selected: String) {
        if (offlineOn) { sayOffline(); return }
        aiJob?.cancelled = true
        aiJob = Ghostwriter.tidy(this, prefs, selected) { onAiDone(it) }
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
        if (text != null && placeAnswer(job, text)) aiJob = null
        scheduleRow()
    }

    private fun placeAnswer(job: Ghostwriter.Job, text: String): Boolean {
        val ic = currentInputConnection ?: return false
        // An answer still typing out is finished first, so each answer is its own step in the history.
        finishTyping()
        val typeOut = prefs.ghostwriterTyping
        // What the AI writes is not the person's typing: next-word prediction does not count it.
        notTyped()
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
        wordTouches.clear(); takenTouch = null; lastSwipe = null; lastCorrection = null; learner.moved()
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

    /**
     * A touch on [pressed] meant for [meant]. Typing stats count the pair where they are on (which key for which,
     * never the word: the Stats page's picture of missed keys); the adaptive keys plugin, where it is on, measures
     * the touch against [meant]'s key and places it on the heatmap.
     */
    private fun learnMistake(t: KeyboardView.Typed, meant: Char) {
        // A touch in the middle of the key it typed was aimed there: taken back, it is a change of mind, not a slip.
        if (kotlin.math.abs(t.x - t.rect.centerX()) < t.rect.width() * 0.2f && kotlin.math.abs(t.y - t.rect.centerY()) < t.rect.height() * 0.2f) return
        stats { TypingStats.slip(this, language.spec.id, t.c, meant) }
        if (!adaptiveOn()) return
        val r = keyboard?.letterRect(meant) ?: return
        val (fx, fy) = keyboard?.keysFraction(t.x, t.y) ?: return
        AdaptiveKeys.get(this, language.spec.id).mistake(t.c, meant, (t.x - r.centerX()) / r.width(), (t.y - r.centerY()) / r.height(), fx, fy)
    }

    /**
     * After [text] is typed: a letter from a finger joins the word's touches. A letter typed straight after
     * backspace took a neighbouring one away says that touch was meant for this letter.
     */
    private fun adaptiveTyped(text: String) {
        val typed = keyboard?.takeTyped()
        if (accuracyOn() && text.length == 1 && text[0].isLetter()) {
            if (typed != null) AccuracyLog.tap(text[0], typed.x, typed.y, typed.rect.width(), typed.rect.height()) else AccuracyLog.tap(text[0], -1f, -1f, 0f, 0f)
        }
        // The word's touches are kept whether or not the adaptive keys plugin is on: autocorrect reads the word by
        // them ([tapsFor]). They are never written anywhere; only the plugin learns from them, and only where it may.
        // Several letters from one long-press (Arabic's لا) are part of the word, each with no touch of its own.
        if (text.length > 1 && text.all { it.isLetter() }) { repeat(text.length) { wordTouches += null }; takenTouch = null; return }
        // A joiner inside a word (the apostrophe of don't) is part of it, with no touch to go by.
        if (text.length == 1 && text[0] in joiners && wordTouches.isNotEmpty()) { wordTouches += null; takenTouch = null; return }
        if (text.length != 1 || !text[0].isLetter()) { endWord(); return }
        if (adaptiveOn() || statsOn()) takenTouch?.let { (t, at) ->
            if (System.currentTimeMillis() - at < 3000 && neighbours(t.c, text[0])) learnMistake(t, text[0])
        }
        takenTouch = null
        wordTouches += typed?.takeIf { it.c == text[0] || it.c.toString().lowercase(locale) == text.lowercase(locale) }
    }

    /**
     * Where the fingers landed for [word], the word just before the cursor, for autocorrect to read it by; null where
     * that is not known for every letter of it (the cursor was moved into it, part of it was pasted or swiped), and
     * autocorrect then goes by which keys are neighbours, as it always did. A word ended from another page (?123's question mark, the comma) keeps its taps: the keyboard
     * remembers where the letters' keys were when it left them ([KeyboardView.tapKeys]).
     */
    private fun tapsFor(word: String): Taps? {
        if (word.isEmpty() || wordTouches.size != word.length) return null
        val xs = FloatArray(word.length) { Float.NaN }
        val ys = FloatArray(word.length) { Float.NaN }
        var known = 0
        for (i in word.indices) {
            val t = wordTouches[i] ?: continue
            if (t.c != word[i] && t.c.toString().lowercase(locale) != word[i].toString().lowercase(locale)) return null
            xs[i] = t.x; ys[i] = t.y; known++
        }
        // A word of which few letters were tapped is read the old way.
        if (known * 2 < word.length) return null
        val (keys, keyW, space) = keyboard?.tapKeys() ?: return null
        return Taps(xs, ys, keys, keyW, space)
    }

    /** Autocorrect swapped [word] for [fixed]: where a letter became its neighbour, that touch was meant for it. */
    private fun adaptiveCorrected(word: String, fixed: String) {
        if ((!adaptiveOn() && !statsOn()) || word.length != fixed.length || wordTouches.size != word.length) return
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
        // Typing stats keep where the taps that stood land about each key (two sums and a count a key: the Stats
        // page's picture of where the thumbs fall), whether or not the adaptive keys plugin is learning from them.
        val counting = statsOn()
        val model = if (adaptiveOn()) AdaptiveKeys.get(this, language.spec.id) else null
        if (model != null || counting) {
            for (t in wordTouches) t?.let {
                val dx = (it.x - it.rect.centerX()) / it.rect.width()
                val dy = (it.y - it.rect.centerY()) / it.rect.height()
                model?.hit(it.c, dx, dy)
                if (counting) runCatching { TypingStats.landed(this, language.spec.id, it.c, dx, dy) }
            }
            model?.save()
            if (counting) runCatching { TypingStats.landedWord(this) }
        }
        wordTouches.clear()
        takenTouch = null
    }

    // --- the AI typing --------------------------------------------------------------------------------------------

    private fun startTyping(text: String) {
        finishTyping()
        notTyped()
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
        if (t.at >= t.text.length) { typing = null; editor.endFill(); notTyped(); scheduleRow(); return }
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
        notTyped()
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
        if (job.mode == Enhance.MODE) return "Enhancing your prompt…"
        if (job.mode == Enhance.SAID) return "Making it a prompt…"
        if (job.mode == DictationWords.TIDY) return "Tidying what you said…"
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
                    when (job.error) {
                        Net.OFFLINE_MESSAGE -> openOfflineSettings()
                        else -> onOpenSettings()
                    }
                }
                items += RowItem("ai-retry-${job.id}", label = "Retry", icon = RowIcon.RETRY, bold = true, chip = true) {
                    aiJob = null
                    val mode = Rewrite.mode(prefs, job.mode)
                    if (job.mode == DictationWords.TIDY) startTidy(job.tag) else if (job.mode == Enhance.SAID) startEnhance(job.tag, spoken = true) else if (job.mode == Enhance.MODE) startEnhance(job.tag) else if (mode != null) startRewrite(job.tag, mode) else if (job.selection) rewriteSelection(job.tag) else fillAtCursor()
                    scheduleRow()
                }
                items += dismiss
            }
            else -> {
                val text = job.result.orEmpty()
                items += RowItem("ai-put-${job.id}", label = "Insert: ${preview(text)}", icon = RowIcon.CHECK, tint = RowTint.DONE, bold = true, chip = true, fill = true) {
                    editor.insert(text, Kind.AI); notTyped(); aiJob = null; scheduleRow()
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
        notTyped()
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

    /** The letters (and the layout's joiners: an apostrophe, a half-space) just before the cursor: the word being typed. */
    private fun currentWord(ic: InputConnection): String = currentWord(ic.getTextBeforeCursor(48, 0)?.toString() ?: return "")

    /** The same, of text already read: the word at the end of [text], as far back as 48 characters. */
    private fun currentWord(text: String): String {
        val before = if (text.length > 48) text.substring(text.length - 48) else text
        // From its first letter: a word typed straight after an emoji is the word alone (Words.lastStart).
        val start = Words.runStart(before, joiners)
        if (start > 0) {
            val prev = before[start - 1]
            if (prev.isLetterOrDigit() || prev in "@#/._-\\:\$`") return ""
        }
        return before.substring(Words.lastStart(before, joiners))
    }

    /** A suggestion tapped: it replaces the word being typed, and a space follows. */
    private fun pickSuggestion(typed: String, word: String) {
        val ic = currentInputConnection ?: return
        if (!currentWord(ic).equals(typed, ignoreCase = false)) { scheduleRow(); return }
        if (!editor.replaceBefore(typed, "$word ", Kind.SUGGESTION)) { scheduleRow(); return }
        if (accuracyOn()) { recordWord(typed, null, ic.getTextBeforeCursor(CORRECT_CHARS, 0)?.toString().orEmpty().dropLast(word.length + 1)); AccuracyLog.settle(this, "picked:$word") }
        // A word taken from the row that what was typed is no start of, but a slip from: a mistake put right by hand.
        if (typed.length >= 3 && (mistakesOn() || reporting())) {
            val a = typed.lowercase(locale)
            val b = word.lowercase(locale)
            if (!b.startsWith(a) && MistakeMemory.alike(a, b)) {
                if (mistakesOn()) mistakes.learn(a, meantAs(word, typed[0].isUpperCase()))
                if (reporting()) Reports.mistake(this, "picked", a, b)
            }
        }
        if (reporting()) Reports.count(this, "words.completed")
        lastCorrection = null
        lastSpaceAt = System.currentTimeMillis()
        // The word was chosen by hand: it counts as typed, and its space gives way to a full stop typed next.
        learner.word(word.length); learner.ended()
        autoSpaced = true; autoSpaceAt = -1
        stats { TypingStats.picked(this, word, typed.length) }
        // Mockingbird's draft is read at word ends, and this is one.
        snapshotDraft()
        scheduleRow()
    }

    /** The typed word tapped in quotes: keep it as it is, learn it, and finish it with a space. */
    private fun keepTyped(word: String) {
        val ic = currentInputConnection ?: return
        if (!incognito) { prefs.learn(word, locale); autocorrect.setLearned(prefs.learnedWords) }
        if (accuracyOn()) { recordWord(word, null, ic.getTextBeforeCursor(CORRECT_CHARS, 0)?.toString().orEmpty().dropLast(word.length)); AccuracyLog.settle(this, "typed") }
        editor.type(" ")
        lastCorrection = null
        lastSpaceAt = System.currentTimeMillis()
        learner.ended()
        autoSpaced = false
        scheduleRow()
    }

    /**
     * The cursor is where a word would start: straight after a space or a new line, or at the very start, with no
     * letter or digit straight after it (it is not in the middle of a word).
     */
    private fun atWordStart(before: String, ic: InputConnection?): Boolean {
        if (before.isNotEmpty() && !before[before.length - 1].isWhitespace()) return false
        val after = ic?.getTextAfterCursor(1, 0)
        return after.isNullOrEmpty() || !(after[0].isLetterOrDigit() || isWordChar(after[0]))
    }

    /**
     * A predicted next word tapped: it is typed with a space after it (none where a space is there already), and the
     * row then offers what follows it. Refused if the cursor has left the place the word was offered for.
     */
    private fun pickPrediction(word: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        if (selStart != selEnd || (!before.isNullOrEmpty() && !before[0].isWhitespace())) { scheduleRow(); return }
        val after = ic.getTextAfterCursor(1, 0)
        val space = if (!after.isNullOrEmpty() && after[0].isWhitespace()) "" else " "
        ic.commitText(word + space, 1)
        if (reporting()) Reports.count(this, "words.predicted")
        lastCorrection = null
        lastSwipe = null
        wordTouches.clear(); takenTouch = null
        if (space.isNotEmpty()) { lastSpaceAt = System.currentTimeMillis(); autoSpaced = true; autoSpaceAt = -1 }
        // Chosen by hand, so it counts as typed: learned like any word once the next one ends. With its space it has
        // ended; set down before a space that was already there, it is a word still at the cursor until something
        // typed ends it (the word before it ended long ago, and is not looked at again).
        learner.word(word.length)
        if (space.isNotEmpty()) learner.ended()
        stats { TypingStats.picked(this, word) }
        // Mockingbird's draft is read at word ends: a message finished with taps is kept whole.
        snapshotDraft()
        updateShift()
        scheduleRow()
    }

    // --- swipe typing (the swipe plugin) ----------------------------------------------------------------------------

    /** Decodes swipes away from the main thread: a long stroke read as several words can take a moment. */
    private val swipeWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "swipe-decoder").apply { isDaemon = true } }
    private val swipeHandler = Handler(Looper.getMainLooper())

    /** A finger's stroke as it was lifted: its path and each point's time, the keys it ran over, and the field it was drawn in. */
    private class Stroke(val points: List<android.graphics.PointF>, val times: List<Long>, val centres: Map<Char, android.graphics.PointF>, val keyWidth: Float, val field: EditorInfo?)

    /**
     * Strokes waiting to be read, in the order they were drawn, and whether one is being read now. A stroke is read
     * only once the one before it has been typed (Matt, 2026-11-10: "improving the reliability of the swipe"): a
     * quick second stroke used to cancel the first, whose word was then lost, and the second was read without it.
     * Now each is read in turn, with the word before it in the text.
     */
    private val strokes = java.util.ArrayDeque<Stroke>()
    private var strokeBusy = false

    override fun onSwipe(path: List<android.graphics.PointF>, keys: Map<Char, android.graphics.PointF>, keyWidth: Float) {
        wordTouches.clear(); takenTouch = null
        hangul = Hangul.State()
        toolsHeld = false
        said = null
        if (gifMode == GifMode.TYPING) return
        if (currentInputConnection == null) return
        // More strokes than anyone draws ahead of the reading: the oldest is let go rather than typed long after.
        if (strokes.size >= MAX_STROKES) strokes.pollFirst()
        strokes.addLast(Stroke(path.map { android.graphics.PointF(it.x, it.y) }, keyboard?.lastSwipeTimes.orEmpty(), HashMap(keys), keyWidth, currentInputEditorInfo))
        if (!strokeBusy) readStroke()
    }

    /**
     * Reads the next waiting stroke in the background and types it. The text before the cursor is looked at now, so
     * it has the word the stroke before this one typed: that word (or "^" at a sentence's start) is the context the
     * stroke's first word is read in, with next-word prediction's own guess where that is on (the two words before,
     * and the person's habits). The reading is typed only if the field and the text before the cursor are as they
     * are now, so one that comes late never lands somewhere the person has since moved on from.
     */
    private fun readStroke() {
        val stroke = strokes.pollFirst()
        val ic = currentInputConnection
        if (stroke == null || ic == null || currentInputEditorInfo !== stroke.field) { strokes.clear(); strokeBusy = false; return }
        strokeBusy = true
        val text = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString().orEmpty()
        // A swiped word has no space after it yet: the stroke that follows puts one in, and is read as if it stood.
        val asIf = if (text.isEmpty() || text.last().isWhitespace() || text.last() in "([{\"'`") text else "$text "
        val place = runCatching { placeAt(asIf) }.getOrNull()?.takeIf { it.typing.isEmpty() }
        val prior = place?.prev
        // Worked out here, on the thread that may ask for the person's counts, and only read on the decoder's.
        val scorer = if (place != null && prefs.nextWord && !terminalApp()) runCatching { nextWords.at(place).ready() }.getOrNull() else null
        if (accuracyOn()) AccuracyLog.layout(this, language.spec.id, language.dictionaries, stroke.centres, stroke.keyWidth, keyboard?.spaceRect())
        swipeWorker.execute {
            val started = android.os.SystemClock.elapsedRealtime()
            val read = runCatching { autocorrect.swipe(stroke.points, stroke.centres, stroke.keyWidth, prior, scorer) }.getOrDefault(emptyList())
            // The reading the person has chosen over this first one before goes first ([Autocorrect.byChoice]).
            val found = runCatching { autocorrect.byChoice(read) }.getOrDefault(read)
            // How long it took, never what was typed: the system log is no place for someone's words.
            val took = android.os.SystemClock.elapsedRealtime() - started
            android.util.Log.d("GhostKeyboard", "swipe decoded in $took ms, ${found.firstOrNull()?.count { it == ' ' }?.plus(1) ?: 0} words")
            swipeHandler.post {
                if (reporting()) Reports.swipeMs(this, took)
                val now = currentInputConnection
                val same = now != null && currentInputEditorInfo === stroke.field && selStart == selEnd &&
                    now.getTextBeforeCursor(SWIPE_SAME_CHARS, 0)?.toString().orEmpty() == text.takeLast(SWIPE_SAME_CHARS)
                if (same) runCatching { typeSwipe(found, read.firstOrNull() ?: "", stroke, prior) }
                strokeBusy = false
                if (strokes.isNotEmpty()) readStroke()
            }
        }
    }

    /**
     * Types a swipe's reading [found] (best first) at the cursor, the rest kept as alternatives in the row. [read] is
     * what the decoder itself read first, before the person's own choices were gone by.
     */
    private fun typeSwipe(found: List<String>, read: String, stroke: Stroke, prior: String?) {
        val ic = currentInputConnection ?: return
        if (found.isEmpty()) return
        val shift = keyboard?.shift ?: ShiftState.OFF
        val words = found.map { w ->
            when (shift) {
                ShiftState.LOCKED -> w.uppercase(locale)
                ShiftState.ON -> w.replaceFirstChar { c -> c.toString().uppercase(locale).takeIf { it.length == 1 } ?: c.toString() }
                ShiftState.OFF -> w
            }
        }
        val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
        val lead = if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{\"'`") " " else ""
        finishTyping()
        // The swiped word before this one stood as it was; and a stroke drawn where one was just deleted is that one again.
        swipeStood()
        val now = android.os.SystemClock.uptimeMillis()
        val here = ic.getTextBeforeCursor(HEAD_CHARS, 0)?.toString().orEmpty()
        val over = swipeGone?.takeIf { it.field === currentInputEditorInfo && now - it.whenMs < RESWIPE_MS && it.head.trimEnd(' ') == here.trimEnd(' ') }
        swipeGone = null
        editor.insert(lead + words[0], Kind.SWIPE)
        stats { TypingStats.swiped(this, words[0]) }
        // The word's letters light in turn, as if typed along the path (never in a password field).
        if (!isPassword(currentInputEditorInfo)) keyboard?.lightWord(words[0])
        lastCorrection = null
        lastSpaceAt = 0L
        lastSwipe = SwipeState(lead, words[0], words, read.lowercase(locale), over).also { state ->
            // For an anonymous report: the stroke in key widths from the top row's first key, never in pixels.
            if (reporting()) runCatching {
                val keys = stroke.centres.mapValues { (_, p) -> p.x to p.y }
                val row = ReportTally.rowPitch(keys, stroke.keyWidth)
                if (row != null && keys.isNotEmpty()) state.path = ReportTally.pathOf(stroke.points.map { it.x to it.y }, keys.values.minOf { it.first }, keys.values.minOf { it.second }, stroke.keyWidth, row)
            }
        }
        if (accuracyOn()) AccuracyLog.swipe(this, stroke.points, stroke.times, found, prior, currentInputEditorInfo?.packageName)
        // A swiped word is entered by hand, and is still at the cursor: it is taken up when something ends it (a
        // space, a full stop, the next swipe's space), not now. It ends the word before it only where it put a
        // space after that word; else that word ended at its own space and was taken up then.
        learner.typed(words[0].length)
        if (lead.isNotEmpty()) learner.ended()
        autoSpaced = false
        scheduleRow()
    }

    private fun replaceSwipe(word: String) {
        val swipe = lastSwipe ?: return
        val ic = currentInputConnection ?: return
        val tail = swipe.lead + swipe.word
        // The swapped word stays the one step the swipe was.
        if (!editor.replaceBefore(tail, swipe.lead + word, Kind.SWIPE)) { lastSwipe = null; scheduleRow(); return }
        if (accuracyOn()) AccuracyLog.settle(this, "picked:$word")
        stats { TypingStats.swipeChanged(this) }
        swipe.word = word
        swipe.picked = true
        learner.word(word.length)
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
        if (offlineOn) { sayOffline(); return }
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
        Giphy.load(prefs, gif.full) { bytes ->
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
        /** Punctuation that finishes a word on every layout, and so runs autocorrect on it before it is typed ([isWordEnd] adds the layout's own). */
        private const val WORD_ENDS = ".,!?"

        /** ":smi" just typed, after a space or at the start: the emoji plugin's shortcode search. */
        private val SHORTCODE = Regex("(?:^|\\s):([a-z0-9_+-]{2,30})$")
        /** "[[Not" just typed in Ghost.md: the start of a link to a note. */
        private val WIKI_OPEN = Regex("\\[\\[([^\\[\\]\\n|]{0,60})$")
        /** "<<standup tl" being typed: a Ghostwriter request not yet closed. */
        private val FILL_OPEN = Regex("<<([^<>\\n]{0,400})$")
        /** "#ide" just typed in Ghost.md, after a space or at a line's start: a tag. */
        private val TAG_OPEN = Regex("(?:^|\\s)#([\\p{L}\\p{N}_/-]{1,40})$")

        /** How much text before the cursor each look reads: enough for the row's own patterns and the last few words. */
        private const val CONTEXT_CHARS = 200
        /**
         * What the row's one reading of the field asks for, before the cursor and after it: enough for every question
         * a key press or the row then has, of which the longest are Mockingbird's look at the message being written
         * (1,500 and 500, at every word's end) and Ghostwriter's at a request (2,000 and 400). The reading is made
         * off the main thread, so its length costs the keys nothing.
         */
        private const val READ_BEFORE = 2000
        private const val READ_AFTER = 500
        /** A reading that came back with nothing after this long was not refused by the app: it never answered. */
        private const val GAVE_UP_MS = 200L
        /** A reading not back after this long is not coming (Android itself gives up after two seconds). */
        private const val READ_LOST_MS = 4000L
        /** How many words the row suggests at once: Gboard's three. */
        private const val WORDS = 3
        /** How many strokes may wait to be read, and how much of the text before the cursor must be as it was for a reading to be typed. */
        private const val MAX_STROKES = 4
        /** How many of a field's latest autocorrections are remembered, and for how long a word typed again where one stood counts as meant. */
        private const val ENDS_KEPT = 8
        /** How much of the text before a word says where the word stood: enough that two places in a field never look alike by chance. */
        private const val HEAD_CHARS = 32
        /** A stroke drawn, or a word typed, this soon after a swiped word was deleted in the same place is that word again. */
        private const val RESWIPE_MS = 30_000L
        private const val RETYPE_MS = 120_000L
        private const val SWIPE_SAME_CHARS = 48

        /** How much text either side of the caret the space bar's slide reads: its steps, and which way the text runs. */
        private const val CURSOR_CHARS = 24

        /** The "Incognito on" chip's id: it stays in front of the row's next words. */
        private const val INCOGNITO_ON = "incognito-true"

        /** How much text autocorrect reads when a word ends: the word, and the two before it for context. */
        private const val CORRECT_CHARS = 96

        /** How long the row says "Offline is on" after a network chip is tapped while it is. */
        private const val OFFLINE_NOTE_MS = 3000L

        /** What a pair key closes with: typed in front of the same character, it steps over it. */
        private const val CLOSERS = ")]}>\"'`"
    }
}
