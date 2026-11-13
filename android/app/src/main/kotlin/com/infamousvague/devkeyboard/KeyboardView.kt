package com.infamousvague.devkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** What the keyboard asks of whoever hosts it (the input method service). */
interface KeyboardListener {
    /** [text] typed, the cursor left [caretBack] characters before its end (a pair's inside). */
    fun onText(text: String, caretBack: Int = 0)
    fun onDelete()
    fun onSpace()
    fun onEnter()
    fun onCursor(steps: Int)
    fun onShiftChanged()
    /** A key's sound and, unless [haptic] is false (a held backspace's repeats), its vibration. */
    fun onKeyFeedback(key: Key, haptic: Boolean = true)
    fun onShowPicker()
    fun onOpenSettings()
    fun onHideKeyboard()
    /** The mic key, or a tap on the listening panel: starts or ends a dictation. */
    fun onMic()
    /** The emoji key, where the { } key was, was tapped. */
    fun onEmoji() {}
    /** Another page of keys is showing (letters, ?123, the code keys): the row's { } button says which. */
    fun onPage() {}
    /** Swipe typing: the finger's [path] over the letters, each letter's key centre, and a key's width. */
    fun onSwipe(path: List<PointF>, keys: Map<Char, PointF>, keyWidth: Float) {}
    /** Cursor control: a slide left from backspace takes the word before the cursor. */
    fun onDeleteWord() {}
    /** Cursor control: a slide up or down from the space bar moves the cursor [lines] lines (negative is up). */
    fun onCursorLine(lines: Int) {}
    /** The empty side of a docked or one-handed keyboard was tapped: the keys should move to [side] ("left" or "right"). */
    fun onDock(side: String, wide: Boolean) {}
    /** The layout switch (the globe key, or the space bar's list) chose the layout with this [id] (Layouts.SPECS). */
    fun onLayout(id: String) {}
}

/**
 * [KeyboardListener] with every call guarded: a bug in one key's handling is logged and that keystroke dropped,
 * rather than taking the whole keyboard down with the app's text field still waiting for it.
 */
class SafeListener(private val inner: KeyboardListener) : KeyboardListener {
    private inline fun guard(what: String, block: () -> Unit) {
        try { block() } catch (t: Throwable) { android.util.Log.e("GhostKeyboard", "$what failed", t) }
    }
    override fun onText(text: String, caretBack: Int) = guard("onText") { inner.onText(text, caretBack) }
    override fun onDelete() = guard("onDelete") { inner.onDelete() }
    override fun onSpace() = guard("onSpace") { inner.onSpace() }
    override fun onEnter() = guard("onEnter") { inner.onEnter() }
    override fun onCursor(steps: Int) = guard("onCursor") { inner.onCursor(steps) }
    override fun onShiftChanged() = guard("onShiftChanged") { inner.onShiftChanged() }
    override fun onKeyFeedback(key: Key, haptic: Boolean) = guard("onKeyFeedback") { inner.onKeyFeedback(key, haptic) }
    override fun onShowPicker() = guard("onShowPicker") { inner.onShowPicker() }
    override fun onOpenSettings() = guard("onOpenSettings") { inner.onOpenSettings() }
    override fun onHideKeyboard() = guard("onHideKeyboard") { inner.onHideKeyboard() }
    override fun onMic() = guard("onMic") { inner.onMic() }
    override fun onEmoji() = guard("onEmoji") { inner.onEmoji() }
    override fun onPage() = guard("onPage") { inner.onPage() }
    override fun onSwipe(path: List<PointF>, keys: Map<Char, PointF>, keyWidth: Float) = guard("onSwipe") { inner.onSwipe(path, keys, keyWidth) }
    override fun onDeleteWord() = guard("onDeleteWord") { inner.onDeleteWord() }
    override fun onCursorLine(lines: Int) = guard("onCursorLine") { inner.onCursorLine(lines) }
    override fun onDock(side: String, wide: Boolean) = guard("onDock") { inner.onDock(side, wide) }
    override fun onLayout(id: String) = guard("onLayout") { inner.onLayout(id) }
}

enum class ShiftState { OFF, ON, LOCKED }

/** The dictation plugin's panel over the keys: off, listening (with how loud), or turning speech into words. */
enum class DictationPhase { OFF, LISTENING, TRANSCRIBING }

enum class EnterIcon { RETURN, SEARCH, GO, SEND, NEXT, PREVIOUS, DONE }

/**
 * The whole keyboard as one drawn view, Gboard's layout and look: a toolbar strip, then the keys. The key preview
 * and the long-press panel are drawn in the same view, over the toolbar when the top row needs the room, so there
 * are no popup windows to manage.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, host: KeyboardListener, private val embedded: Boolean = false) : View(context) {
    // [embedded]: drawn inside the settings app as its live preview (KeyboardPreview.kt) rather than as the input
    // method's view: a short strip above the keys in place of the see-through room, and no room for Android's buttons.
    private val listener: KeyboardListener = SafeListener(host)

    // --- settings ---------------------------------------------------------------------------------------------------

    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(value) { field = value; invalidate() }
    var showPreview = true
    var showHints = true
        set(value) { field = value; invalidate() }
    var spaceCursor = true
    /** Cursor control: sliding left from backspace deletes whole words. */
    var deleteSwipe = true
    var longPressMs = 300L

    /** Gboard's look: only ?123, space and enter have a shape; letters float. On, every key has its own face. */
    var keyBorders = false
        set(value) { field = value; invalidate() }

    /**
     * The dictation plugin is added, its model is here and the field is one to speak into: on the letters page the
     * mic key stands where the full stop was, next to enter ([Layouts.withMic]).
     */
    /**
     * The emoji plugin is added: its key stands next to the comma, a key's width off the space bar, on every page
     * ([Layouts.withEmoji]).
     */
    var emojiKey = false
        set(value) { if (field != value) { field = value; rebuild(); invalidate() } }

    /**
     * The { } key is away from the board, a button in the row above the keys: its place on the letters, next to
     * ?123, goes to the emoji key, or with [commaKey] to the comma ([Layouts.withComma], [Layouts.withEmoji]).
     * Off, there is no row to hold the button, and the { } key keeps its place.
     */
    var codeAway = false
        set(value) { if (field != value) { field = value; rebuild(); invalidate() } }

    /** Settings › Look & feel › Keys › Comma key: with the { } key away, the comma has its place and the emoji key stands beside it. */
    var commaKey = false
        set(value) { if (field != value) { field = value; rebuild(); invalidate() } }
    var micKey = false
        set(value) { if (field != value) { field = value; rebuild(); invalidate() } }

    var dictation = DictationPhase.OFF
        set(value) {
            if (value == DictationPhase.TRANSCRIBING && field != value) writingSince = android.os.SystemClock.uptimeMillis()
            field = value
            if (value == DictationPhase.OFF) level = 0f
            invalidate()
        }

    /**
     * When a take's last words began to be written down. That is a moment for most takes; after [WRITING_WAIT_MS]
     * of it (a long take, a slow phone) the panel says a tap stops it there, and a tap does (the service lets the
     * rest of the take go and keeps what it has typed).
     */
    private var writingSince = 0L
    private fun writingLong() = dictation == DictationPhase.TRANSCRIBING && android.os.SystemClock.uptimeMillis() - writingSince >= WRITING_WAIT_MS

    /** How loud the microphone hears it, 0..1, eased. */
    var level = 0f
        set(value) { field = field * 0.6f + value * 0.4f; redraw() }

    /**
     * The plugin row is shown: a real view (PluginRow) sits in the strip between the preview room and the keys, so
     * this view leaves that strip unpainted and lets its touches through.
     */
    var pluginRow = false
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }

    /**
     * Foldable layouts (Settings › Look & feel › Layout). On a wide screen (an unfolded foldable, a tablet): "split" in
     * two, "full" width, or a phone-sized board docked "left", "center" or "right".
     */
    var wideLayout = "split"
        set(value) { if (field != value) { field = value; layoutKeys(); invalidate() } }

    /** One-handed typing on a phone: "off", or the keys narrowed toward the "left" or "right" thumb. */
    var oneHanded = "off"
        set(value) { if (field != value) { field = value; layoutKeys(); invalidate() } }

    /** Where the keys stand across the view: all of it, or less when they are docked to a side or one-handed. */
    private var areaLeft = 0f
    private var areaRight = 0f
    private val isWide get() = width / resources.displayMetrics.density >= SPLIT_MIN_DP

    /**
     * A docked or one-handed keyboard's empty side: its middle and its name, where the keys go when it is tapped.
     * Null when the keys fill the width or stand in the middle.
     */
    private fun dockFlip(): Pair<Float, String>? = when {
        areaRight - areaLeft >= width - 1f -> null
        areaLeft <= 0.5f -> (areaRight + width) / 2f to "right"
        areaRight >= width - 0.5f -> areaLeft / 2f to "left"
        else -> null
    }

    /** The adaptive keys plugin's learned aims, or null without it; and whether its heatmap is drawn over the keys. */
    var adaptive: AdaptiveKeys? = null
    var adaptiveMap = false
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * The typing trainer's fix, while a run that tests it is on in the trainer's own field (Trainer.fixFor): where
     * each letter key listens for that run, read in place of [adaptive]'s nudges. Null at every other time. While it
     * is set each letter's touch is read a second time, as the keyboard as it is would have read it ([Typed.asIs]):
     * the run's result holds the two readings against each other on the very same touches.
     */
    var fix: ((Char) -> Pair<Float, Float>)? = null

    /**
     * The letter key a finger last typed, with where it aimed (after touch correction): what the adaptive keys
     * plugin learns from. Set just before the key's text is typed, so the service can read it as it arrives. [asIs]
     * is the letter the keyboard as it is would have read the touch as: another letter only while a trainer run is
     * typed on a [fix], and only for a touch the two read differently.
     */
    class Typed(val c: Char, val rect: RectF, val x: Float, val y: Float, val asIs: Char = c)
    private var lastTyped: Typed? = null

    /** The letter just typed by a finger, once: null for anything typed another way (a popup, the row, a swipe). */
    fun takeTyped(): Typed? = lastTyped.also { lastTyped = null }

    /**
     * The space bar's own tap, once, with where it aimed: a thumb reaching for the bottom row lands on the top of the
     * bar as often as on the key above it, and the service keeps the tap in case it was a letter of the word it cut
     * in two (DevKeyboardService.Cut, Autocorrect.rejoin). Null for a space typed any other way.
     */
    private var lastSpace: Typed? = null
    fun takeSpace(): Typed? = lastSpace.also { lastSpace = null }

    /** The same letter, left where it is for the service to take: the typing trainer's look at a tap (TrainerKeys.kt). */
    fun peekTyped(): Typed? = lastTyped

    /** The middle of [c]'s key on the letters page now, and its size: to measure a mistake against the key meant. */
    fun letterRect(c: Char): RectF? = if (page != Page.LETTERS) null else keyTyping(c.toString())?.second

    /**
     * The key that types the letter [text]: the one it is, else the one its lower case is by the layout's own rule.
     * Never a comparison that ignores case: that calls Turkish's ı and i the same letter, and they are two keys.
     */
    private fun keyTyping(text: String): Pair<Key, RectF>? {
        fun find(t: String) = keyRects.firstOrNull { (k, _) -> k.type == KeyType.CHAR && k.text == t }
        return find(text) ?: find(text.lowercase(locale))
    }

    /** A point as a fraction of the keys' area, for the heatmap, which must hold on a folded or an unfolded screen. */
    fun keysFraction(x: Float, y: Float): Pair<Float, Float> = (x / width.coerceAtLeast(1)) to ((y - keysTop) / keysHeight.coerceAtLeast(1f))

    /** The backlight plugin's light, or null without it. */
    var backlight: Backlight? = null
        set(value) { field = value; invalidate() }

    private fun lightId(key: Key) = "${key.type}|${key.text}|${key.label}"

    /**
     * How lit key [i]'s legend is at [now], as one number: 0 for not at all, else its strength in [LIGHT_LEVELS] steps
     * with its colour (six bits a channel) or the dimming flag. Two frames with the same codes look the same, which
     * is what lets a frame that would change nothing be left undrawn ([lightTick]) and a filter be kept for the next
     * key or frame that needs the same one ([lightFilter]). A step is 1/64 of the way from the legend's colour to the
     * light's: under two parts in 255 of any channel, which nobody can see.
     */
    private fun lightCode(i: Int, key: Key, f: RectF, light: Backlight, now: Long): Int {
        val strength = light.light(lightIds[i], f.centerX(), f.centerY(), width.toFloat(), now) * light.strength
        if (strength <= 0.01f) return 0
        val level = (strength.coerceAtMost(1f) * LIGHT_LEVELS + 0.5f).toInt().coerceAtLeast(1)
        if (light.dims) return level or LIGHT_DIMS
        // On the enter key's accent pill the accent would vanish: its legend is left as it is.
        if (key.type == KeyType.ENTER) return 0
        val c = light.colourAt(f.centerX(), width.toFloat(), now, theme.accent, level / LIGHT_LEVELS.toFloat())
        lightColour = c
        return level or ((c shr 18 and 0x3F) shl 7) or ((c shr 10 and 0x3F) shl 13) or ((c shr 2 and 0x3F) shl 19)
    }

    /** The colour [lightCode] last worked out, in full: what a new filter for that code is made from. */
    private var lightColour = 0

    /**
     * The backlight on a key's legend, as a real board's shine-through legends: brightening blends the light's colour
     * into the label and icon, dimming fades them. Set on the legend's paints for the one key, cleared after; the key
     * itself is drawn as ever. One filter is kept for each [code], so a fading key makes one where it made sixty a
     * second, and the keys of a ripple share theirs.
     */
    private fun lightFilter(code: Int): android.graphics.ColorFilter {
        lightFilters.get(code)?.let { return it }
        val t = (code and LIGHT_LEVEL_MASK) / LIGHT_LEVELS.toFloat()
        val made: android.graphics.ColorFilter = if (code and LIGHT_DIMS != 0) {
            // Fade the legend toward a fifth of its strength.
            android.graphics.ColorMatrixColorFilter(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f - t * 0.8f, 0f))
        } else {
            // Each channel is the legend's own, less the light's share, with that share of the light's colour added.
            val c = lightColour
            val keep = ((1f - t) * 255f + 0.5f).toInt()
            android.graphics.LightingColorFilter(
                android.graphics.Color.rgb(keep, keep, keep),
                android.graphics.Color.rgb((android.graphics.Color.red(c) * t).toInt(), (android.graphics.Color.green(c) * t).toInt(), (android.graphics.Color.blue(c) * t).toInt()),
            )
        }
        // A rainbow goes through every colour there is: what is kept is let go before it is a burden.
        if (lightFilters.size() >= LIGHT_FILTERS) lightFilters.clear()
        lightFilters.put(code, made)
        return made
    }

    private val lightFilters = android.util.SparseArray<android.graphics.ColorFilter>()

    /** Every key's [lightCode] at [now], as one number: the same for two moments whose frames would look the same. */
    private fun lightLook(light: Backlight, now: Long): Int {
        if (light === wordLight && !wordLight.animating(now)) return 0
        var look = 0
        for (i in keyRects.indices) look = look * 31 + lightCode(i, keyRects[i].first, keyFaces[i], light, now)
        return look
    }

    /** [lightLook] as the last frame drew it. */
    private var lightDrawn = 0

    /**
     * The backlight's clock: every [Backlight.frameDelay] while a light is moving, it asks whether the keys would look
     * any different now, and only then are they drawn again. Until 0.16.2 every tick of it drew the whole board, sixty
     * times a second for 1.2 s after each key (483 frames for 75 key presses on the emulator), though a fading key's
     * colour changes far less often than that toward the end of its fade, and not at all in its last tenth.
     */
    private val lightTick = Runnable {
        lightWaiting = false
        // Hidden, the keys are not drawn; shown again, their first frame starts the clock again.
        if (isAttachedToWindow && windowVisibility == VISIBLE && isShown) {
            val light = lightNow
            val now = android.os.SystemClock.uptimeMillis()
            if (lightLook(light, now) != lightDrawn) redraw() else if (light.animating(now)) lightSoon(light.frameDelay())
        }
    }
    private var lightWaiting = false

    private fun lightSoon(delay: Long) {
        if (lightWaiting) return
        lightWaiting = true
        postDelayed(lightTick, delay)
    }

    /**
     * What the keys look like at rest has changed, or may have: every [invalidate] says so, and what is kept from one
     * frame to the next (each key's label as shift has it, the size it fits its key at, where its baseline sits) is
     * worked out again at the next frame. A frame that only moves the light on ([redraw]) keeps it all: nothing but
     * the light has changed, and measuring thirty labels again for each of sixty frames a second was a third of all
     * the keyboard's main thread did while someone typed.
     */
    private var stale = true
    private var labels = arrayOfNulls<String>(0)
    private var sizes = FloatArray(0)
    private var rises = FloatArray(0)

    override fun invalidate() {
        stale = true
        super.invalidate()
    }

    /** Another frame of the keys as they are: only the light has moved on, a key gone down, a trail grown, the mic heard more. */
    private fun redraw() = super.invalidate()
    private val nextFrame = Runnable { redraw() }

    /** Lights a swiped word's letters when the backlight is off (it is null): a quick reactive flare. */
    private val wordLight = Backlight("reactive", "accent", 0.85f)
    private val lightNow get() = backlight ?: wordLight

    /**
     * Swipe typing: the word's letters light one after another, 55 ms apart, as if typed along the path, through the
     * backlight's own effect (or a reactive flare when the backlight is off).
     */
    fun lightWord(word: String) {
        word.lowercase(locale).forEachIndexed { i, c ->
            val rect = letterKey(c) ?: return@forEachIndexed
            postDelayed({
                lightNow.press(lightId(rect.first), rect.second.centerX(), rect.second.centerY(), android.os.SystemClock.uptimeMillis())
                redraw()
            }, i * 55L)
        }
    }

    /**
     * The key a letter lights: its own, else the key of its plain letter (é lights e, ё lights е: [Script.plain]),
     * else none.
     */
    private fun letterKey(c: Char): Pair<Key, RectF>? {
        fun find(text: String) = keyRects.firstOrNull { (k, _) -> k.type == KeyType.CHAR && k.text == text }
        return find(c.toString()) ?: spec.script.plain(c.toString()).firstOrNull()?.let { find(it.toString()) }
    }

    /** A key the AI is "pressing" as it types (Ghostwriter), drawn pressed with its preview, and when it lets go. */
    private var ghostKey: Pair<Key, RectF>? = null
    private val releaseGhost = Runnable { ghostKey = null; redraw() }

    /**
     * Ghostwriter typing: the key for [c] goes down as a finger's would (pressed face, preview bubble, the backlight's
     * flare) and comes up [holdMs] later. A character with no key on this page (a digit on the letters page) still
     * lights nothing, so the text alone moves.
     */
    fun ghostPress(c: Char, holdMs: Long = 70) {
        val match = when {
            c == ' ' -> keyRects.firstOrNull { it.first.type == KeyType.SPACE }
            c == '\n' -> keyRects.firstOrNull { it.first.type == KeyType.ENTER }
            else -> keyRects.firstOrNull { (k, _) -> k.type == KeyType.CHAR && k.text == c.toString() } ?: c.toString().lowercase(locale).firstOrNull()?.let(::letterKey)
        }
        removeCallbacks(releaseGhost)
        ghostKey = match
        if (match != null) lightNow.press(lightId(match.first), match.second.centerX(), match.second.centerY(), android.os.SystemClock.uptimeMillis())
        redraw()
        postDelayed(releaseGhost, holdMs)
    }

    /** The swipe typing plugin is added: a finger sliding across the letters spells a word. */
    var swipeTyping = false

    private var numberRow = false
    private var showToolbar = false
    private var rowHeightDp = 60f

    /**
     * The layout being typed on, its keys' further long-press letters, the space bar's name for it (and a shorter
     * one; null where there is only one layout), whether the globe key stands beside space, and the layouts the
     * switch goes between, as id and name. [locale] is whose capitals shift gives: the layout's own, or its
     * languages' where they share one (Turkish alone on QWERTY: LanguageState.locale).
     */
    var spec: LayoutSpec = Layouts.QWERTY
        private set
    private var locale: java.util.Locale = java.util.Locale.ROOT
    private var extras: Map<String, List<String>> = Layouts.extras(Layouts.QWERTY, emptyList())
    private var spaceLabel: String? = null
    private var spaceShort: String? = null
    private var globe = false
    private var choices: List<Pair<String, String>> = emptyList()

    /** The layout and its languages (LanguageState, from the service or the preview): the keys are rebuilt only when something changed. */
    fun setLanguage(
        spec: LayoutSpec, extras: Map<String, List<String>>, spaceLabel: String?, spaceShort: String?, globe: Boolean, choices: List<Pair<String, String>>,
        locale: java.util.Locale = spec.locale,
    ) {
        if (spec === this.spec && extras == this.extras && spaceLabel == this.spaceLabel && spaceShort == this.spaceShort && globe == this.globe && choices == this.choices && locale == this.locale) return
        val moved = spec !== this.spec
        this.spec = spec
        this.locale = locale
        this.extras = extras
        this.spaceLabel = spaceLabel
        this.spaceShort = spaceShort
        this.globe = globe
        this.choices = choices
        // Another layout: shift starts off (the service turns it on again where a sentence starts and the script has capitals).
        if (moved) { shift = ShiftState.OFF; autoShifted = false; cancelTouch() }
        rebuild()
        invalidate()
    }

    /** The layout after this one, round again to the first: where a tap on the globe key goes. Null with one layout. */
    private fun nextLayout(): String? {
        if (choices.size < 2) return null
        val at = choices.indexOfFirst { it.first == spec.id }
        return choices[(at + 1) % choices.size].first
    }

    fun configure(numberRow: Boolean, keyHeight: String, toolbar: Boolean) {
        // Gboard's rows, measured from its own screenshot: a 48dp key and a 12dp gap, 60dp from row to row.
        // Condensed (Matt, 2026-11-10: "a condensed version of the keyboard that takes up 15% or more less space"):
        // 46dp rows, smaller labels and a slimmer strip above the keys, about a fifth less than Normal in all.
        val height = when (keyHeight) { "condensed" -> CONDENSED_ROW_DP; "short" -> 52f; "tall" -> 68f; else -> 60f }
        if (numberRow == this.numberRow && height == rowHeightDp && toolbar == showToolbar) return
        this.numberRow = numberRow
        rowHeightDp = height
        showToolbar = toolbar
        rebuild()
        requestLayout()
    }

    // --- state ------------------------------------------------------------------------------------------------------

    var page: Page = Page.LETTERS
        private set
    var shift: ShiftState = ShiftState.OFF
        private set

    /** Shift that auto-capitalisation turned on, rather than the person: auto-caps may turn it off again. */
    private var autoShifted = false
    private var lastShiftTap = 0L

    var enterIcon: EnterIcon = EnterIcon.RETURN
        set(value) { field = value; invalidate() }

    private var rows: List<KeyRow> = Layouts.letters(false)
    private val keyRects = ArrayList<Pair<Key, RectF>>()

    /**
     * Where the letter keys listened ([tapKeys]) when the letters were last left for another page: a word typed on
     * them is still read by its taps when a mark of that page ends it (a question mark, an exclamation mark, the
     * comma), and when the row suggests for it meanwhile. Until this was kept, the taps were let go with the page,
     * and the word before every question mark typed from ?123 was read by which keys are neighbours alone
     * (docs/versus.md, 2026-11-11: 42 of the 326 words lost to Gboard ended so). Null until the letters are left,
     * and again at each new field.
     */
    private var lettersKeys: Triple<Map<Char, Pair<Float, Float>>, Float, FloatArray?>? = null

    fun showPage(next: Page) {
        val changed = page != next
        // Before the letters are laid out of the way: where their keys listen now.
        if (page == Page.LETTERS && next != Page.LETTERS) lettersKeys = tapKeys()
        page = next
        if (next != Page.LETTERS) { shift = ShiftState.OFF; autoShifted = false }
        rebuild()
        invalidate()
        if (changed) listener.onPage()
    }

    /**
     * Auto-capitalisation from the editor: turns shift on, or back off if it alone turned it on. Nothing on a script
     * without capitals: there shift is the person's alone (on the Korean board it would double the first consonant).
     */
    fun setAutoShift(on: Boolean) {
        if (!spec.script.cased || page != Page.LETTERS || shift == ShiftState.LOCKED) return
        if (on && shift == ShiftState.OFF) {
            shift = ShiftState.ON; autoShifted = true; invalidate()
        } else if (!on && shift == ShiftState.ON && autoShifted) {
            shift = ShiftState.OFF; autoShifted = false; invalidate()
        }
    }

    /** Back to the letters with shift off: each time a field is started. */
    fun reset() {
        shift = ShiftState.OFF
        autoShifted = false
        page = Page.LETTERS
        lettersKeys = null
        cancelTouch()
        rebuild()
        invalidate()
    }

    private fun rebuild() {
        val letters by lazy { Layouts.letters(spec, numberRow, extras, globe) }
        val base = when (page) {
            Page.LETTERS -> letters
            Page.SYMBOLS -> Layouts.symbols(spec)
            Page.MORE -> Layouts.more(spec)
            Page.DEV -> Layouts.dev()
        }
        // The mic first: it knows the letters page by its { } key, which the emoji key then takes the place of.
        val spoken = if (micKey) Layouts.withMic(base) else base
        // With the { } key away its place is the comma's (the setting on), or the emoji key's, or with neither it
        // stays the { } key's own, which is then the way to the code keys as well as the row's button.
        val pointed = if (codeAway && commaKey) Layouts.withComma(spoken, spec) else spoken
        rows = if (emojiKey) Layouts.withEmoji(pointed, inPlace = codeAway && !commaKey) else pointed
        layoutKeys()
    }

    // --- metrics ----------------------------------------------------------------------------------------------------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /** Room above the keyboard for the key preview and long-press panels, see-through and not touchable there. */
    private val previewSpace get() = if (embedded) dp(EMBEDDED_TOP_DP) else dp(rowHeightDp * 1.3f)
    /** Where the keyboard itself starts, below [previewSpace]: the service tells Android only this part is the IME. */
    val keyboardTop get() = previewSpace
    // Gboard leaves 24dp above the first row's keys (this, and the key's own 6dp of row gap).
    private val toolbarHeight get() = if (showToolbar || pluginRow) dp(if (condensed) 34f else 44f) else dp(if (condensed) 8f else 18f)
    /** Settings › Look & feel › Key height › Condensed. */
    private val condensed get() = rowHeightDp <= CONDENSED_ROW_DP
    /** Labels shrink with condensed keys so they keep the same room around them. */
    private val labelScale get() = if (condensed) 0.87f else 1f
    private val keysTop get() = keyboardTop + toolbarHeight
    /** The plugin row's strip, and where the keys end (above Android's buttons): KeyboardFrame lays out by these. */
    val rowTop get() = keyboardTop
    val rowBottom get() = keysTop
    val keysBottom get() = keysTop + keysHeight + bottomPad
    // Gboard: keys 1.5dp + half a 6dp gap from the screen's edges, and 2dp + half a row gap above Android's buttons.
    private val sidePad get() = dp(1.5f)
    private val bottomPad get() = dp(2f)
    private val keyPadX get() = dp(3f)
    private val keyPadY get() = dp(if (condensed) 4.5f else 6f)
    private val keyRadius get() = dp(6f)
    private var navInset = 0
    private var seenInset = -1L

    /** The letters page sets the height; the symbol pages spread their rows over the same space, as Gboard does. */
    private val rowCount get() = if (numberRow) 5 else 4
    private val keysHeight get() = rowCount * dp(rowHeightDp)

    init {
        isHapticFeedbackEnabled = true
        setOnApplyWindowInsetsListener { _, insets ->
            // onMeasure works the strip out afresh (bottomInset); this only asks for it when one of Android's numbers
            // moves. Every one of them: since Android 14 the room for its own buttons comes as a caption bar, after
            // the navigation bar's and without that one changing, and a keyboard that watched only the navigation bar
            // went on standing where the buttons then were.
            val now = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var all = 0L
                for (type in intArrayOf(WindowInsets.Type.navigationBars(), WindowInsets.Type.captionBar(), WindowInsets.Type.tappableElement(), WindowInsets.Type.systemGestures())) {
                    all = all * 4099 + insets.getInsets(type).bottom
                }
                all
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom.toLong()
            }
            if (!embedded && now != seenInset) { seenInset = now; requestLayout() }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    /**
     * Settings › Look & feel › Keys › Space under the keys. "auto": room for Android's own buttons (see [bottomInset]);
     * "more": that and [MORE_SPACE_DP] again, for a phone whose buttons still crowd the bottom row; "none": only what
     * Android itself reports.
     */
    var bottomSpace = "auto"
        set(value) { if (field != value) { field = value; requestLayout() } }

    /**
     * The phone's own settings for the buttons it draws under a keyboard (SystemStrip.phone), as the service last
     * read them: on a Galaxy with both buttons switched off, "auto" keeps only the gesture bar's room.
     */
    var phone = SystemStrip.Phone(false)
        set(value) { if (field != value) { field = value; requestLayout() } }

    /**
     * The strip Android keeps under the keyboard for its own buttons (collapse, switch keyboard) and the gesture bar.
     * Read from the window itself each layout, so it is right however the insets were dispatched.
     *
     * With gesture navigation Android draws those two buttons over the keyboard's own window, in a strip
     * [NAV_BUTTONS_DP] tall, but some phones report only the thin gesture bar's height as the inset; the buttons then
     * sat on the bottom row (Matt, 2026-11-09: "The keyboard needs to be higher the bottom stock android keyboard
     * collapse and select are intercepting the UI"). So wherever there is a gesture bar at all, the keys keep at
     * least the buttons' height clear; unless the phone itself says its buttons are off (Matt, 2026-11-12: "if not
     * be able to slide the keyboard down since we don't need to reserve the space anymore"), and then only the
     * gesture bar's. SystemStrip.kt has the rule and what it goes by.
     */
    private fun bottomInset(): Int {
        if (embedded) return 0
        val insets = rootWindowInsets ?: return navInset
        var gestures = 0
        var navigation = 0
        var caption = 0
        var tappable = 0
        val reported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            gestures = maxOf(insets.getInsets(WindowInsets.Type.systemGestures()).bottom, insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom)
            navigation = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            caption = insets.getInsets(WindowInsets.Type.captionBar()).bottom
            tappable = insets.getInsets(WindowInsets.Type.tappableElement()).bottom
            maxOf(navigation, caption, tappable)
        } else {
            @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }
        lastReported = maxOf(reported, lastReported)
        if (lastReported > 0 || gestures > 0) hadGestures = true
        val stripShown = androidStrip()?.isShown == true
        val buttons = SystemStrip.buttons(caption, stripShown, phone)
        // The gesture bar alone: Android's own measure of it where that is a gesture bar's, a small margin where the
        // phone draws none, and never more than a gesture bar is.
        val barMost = ceil(dp(if (phone.hint == 0) HIDDEN_BAR_DP else GESTURE_BAR_DP)).toInt()
        val bar = systemBarHeight().takeIf { it in 1..barMost } ?: barMost
        val room = SystemStrip.room(bottomSpace, lastReported, hadGestures, buttons, ceil(dp(NAV_BUTTONS_DP)).toInt(), bar, ceil(dp(MORE_SPACE_DP)).toInt())
        val seen = SystemStrip.Seen(navigation, caption, tappable, gestures, stripShown, phone, buttons, room, resources.displayMetrics.density)
        if (seen != SystemStrip.seen) SystemStrip.seen = seen
        return room
    }

    /** The most Android has reported for the strip, and whether it has ever had a gesture bar: neither flickers back as insets are re-sent. */
    private var lastReported = 0
    private var hadGestures = false

    /**
     * Android's own strip of buttons in this window, where it has put one there (Android 13 and later, with gesture
     * navigation): looked for once the window is up, and again whenever it was not found.
     */
    private var strip: View? = null
    private fun androidStrip(): View? {
        strip?.takeIf { it.isAttachedToWindow }?.let { return it }
        fun find(view: View, depth: Int): View? {
            if (view.javaClass.name == SystemStrip.STRIP_CLASS) return view
            if (view !is android.view.ViewGroup || depth > 6) return null
            for (i in 0 until view.childCount) find(view.getChildAt(i), depth + 1)?.let { return it }
            return null
        }
        strip = runCatching { find(rootView, 0) }.getOrNull()
        return strip
    }

    /**
     * Android's own measure of its bar at the bottom of the screen (px): with gesture navigation, the gesture bar's.
     * 0 where it will not say. Asked each time (it changes with the way of navigating); only its name is looked up once.
     */
    private val systemBarId by lazy { runCatching { android.content.res.Resources.getSystem().getIdentifier("navigation_bar_height", "dimen", "android") }.getOrDefault(0) }
    private fun systemBarHeight(): Int =
        if (systemBarId == 0) 0 else runCatching { android.content.res.Resources.getSystem().getDimensionPixelSize(systemBarId) }.getOrDefault(0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        navInset = bottomInset()
        val height = ceil(keysTop + keysHeight + bottomPad).toInt() + navInset
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    /**
     * Touch correction's offset for each of [keyRects], by the key's place (dp; null where it has none): a key on any
     * layout takes the offset measured for the QWERTY key in the same row whose middle is nearest across the board,
     * since a thumb's reach is about where a key is, not what is printed on it. On QWERTY that is each letter's own.
     */
    private val keyOffsets = ArrayList<Pair<Float, Float>?>()

    /** Each of [keyRects]' visible face and its name to the backlight, made once where the keys are laid out, not for every frame. */
    private val keyFaces = ArrayList<RectF>()
    private val lightIds = ArrayList<String>()

    /** The offset for a key in letter [row] (0 to 2) whose middle is [across] the board (0 to 1); null outside the letter rows. */
    private fun placeOffset(row: Int, across: Float): Pair<Float, Float>? {
        val letters = QWERTY_ROWS.getOrNull(row) ?: return null
        val inset = QWERTY_INSETS[row]
        val nearest = letters.indices.minByOrNull { abs((it + 0.5f + inset) / 10f - across) } ?: return null
        return LETTER_OFFSETS_DP[letters[nearest].toString()]
    }

    private fun layoutKeys() {
        keyRects.clear()
        keyOffsets.clear()
        keyFaces.clear()
        lightIds.clear()
        stale = true
        if (width == 0) return
        val units = rows.maxOf { it.units }
        // Split on a wide screen (an unfolded foldable, a tablet), as Gboard does: each half keeps a phone's key size
        // and the middle is left open for the thumbs. Keys whose centre is left of the middle go left, the rest right;
        // a key across the middle (the space bar) becomes two, one in each half.
        var gap = 0f
        var areaW = width.toFloat()
        var left = 0f
        if (isWide) {
            when (wideLayout) {
                "split" -> gap = width * SPLIT_GAP
                // A phone's keyboard on the big screen, under one thumb or in the middle.
                "left", "center", "right" -> {
                    areaW = min(width.toFloat(), dp(COMPACT_DP))
                    left = when (wideLayout) { "left" -> 0f; "right" -> width - areaW; else -> (width - areaW) / 2f }
                }
            }
        } else if (oneHanded == "left" || oneHanded == "right") {
            areaW = width * ONE_HANDED_SHARE
            left = if (oneHanded == "right") width - areaW else 0f
        }
        areaLeft = left
        areaRight = left + areaW
        val unit = (areaW - sidePad * 2 - gap) / units
        val mid = left + sidePad + units * unit / 2
        val rowH = keysHeight / rows.size
        var y = keysTop
        // The three letter rows of the letters page, counted from its first (after the number row where there is one).
        val firstLetterRow = if (page == Page.LETTERS) rows.size - 4 else -1
        rows.forEachIndexed { r, row ->
            val start = row.inset + (units - row.units) / 2
            var across = start
            var x = left + sidePad + start * unit
            for (key in row.keys) {
                val w = key.width * unit
                val left = x; val right = x + w
                val offset = if (firstLetterRow >= 0 && key.type == KeyType.CHAR) placeOffset(r - firstLetterRow, (across + key.width / 2) / units) else null
                fun put(rect: RectF) { keyRects += key to rect; keyOffsets += offset }
                when {
                    gap == 0f -> put(RectF(left, y, right, y + rowH))
                    key.width >= 2f && left < mid && right > mid -> {
                        put(RectF(left, y, mid, y + rowH))
                        put(RectF(mid + gap, y, right + gap, y + rowH))
                    }
                    (left + right) / 2 <= mid + 0.01f -> put(RectF(left, y, right, y + rowH))
                    else -> put(RectF(left + gap, y, right + gap, y + rowH))
                }
                x += w
                across += key.width
            }
            y += rowH
        }
        for ((key, rect) in keyRects) { keyFaces += face(rect); lightIds += lightId(key) }
    }

    /** The visible key face inside a key's touch area. */
    private fun face(r: RectF) = RectF(r.left + keyPadX, r.top + keyPadY, r.right - keyPadX, r.bottom - keyPadY)

    // --- paints -----------------------------------------------------------------------------------------------------

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val path = Path()

    // --- drawing ----------------------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) = Perf.time("draw") { paint(canvas) }

    private fun paint(canvas: Canvas) {
        fill.color = theme.background
        canvas.drawRect(0f, if (embedded) 0f else if (pluginRow) keysTop else keyboardTop, width.toFloat(), height.toFloat(), fill)
        if (showToolbar && !pluginRow) drawToolbar(canvas)
        // The listening panel stands over every key, and the mic's level moves it many times a second: the keys under
        // it are not drawn at all.
        if (dictation != DictationPhase.OFF) { drawDictation(canvas); return }
        if (stale || labels.size != keyRects.size) {
            labels = arrayOfNulls(keyRects.size); sizes = FloatArray(keyRects.size); rises = FloatArray(keyRects.size)
            stale = false
        }
        val now = android.os.SystemClock.uptimeMillis()
        val light = lightNow
        val lit = !(light === wordLight && !wordLight.animating(now))
        val down = touch?.takeIf { it.panel == null && it.swipe == null }?.key
        val ghost = ghostKey?.first
        var look = 0
        for (i in keyRects.indices) {
            val key = keyRects[i].first
            val f = keyFaces[i]
            // The backlight tints this key's legend only: its label, hint and icon paints, not its face.
            val code = if (lit) lightCode(i, key, f, light, now) else 0
            look = look * 31 + code
            val filter = if (code == 0) null else lightFilter(code)
            label.colorFilter = filter; iconPaint.colorFilter = filter; stroke.colorFilter = filter
            drawKey(canvas, i, key, f, pressed = key === down || key === ghost)
        }
        lightDrawn = look
        label.colorFilter = null; iconPaint.colorFilter = null; stroke.colorFilter = null
        // A docked or one-handed keyboard: a chevron on its empty side, which moves the keys there when tapped.
        dockFlip()?.let { (cx, side) ->
            val cy = keysTop + keysHeight / 2
            val d = if (side == "right") 1f else -1f
            stroke.color = theme.hint
            stroke.strokeWidth = dp(2.2f)
            path.reset()
            path.moveTo(cx - d * dp(5f), cy - dp(10f))
            path.lineTo(cx + d * dp(5f), cy)
            path.lineTo(cx - d * dp(5f), cy + dp(10f))
            canvas.drawPath(path, stroke)
        }
        if (adaptiveMap && page == Page.LETTERS) adaptive?.let { drawAdaptive(canvas, it) }
        // While the backlight moves, its clock asks for the frames that would look any different ([lightTick]).
        if (light.animating(now)) lightSoon(light.frameDelay())
        touch?.swipe?.let { drawSwipe(canvas, it) }
        fadingSwipe?.let { path ->
            val f = 1f - (android.os.SystemClock.uptimeMillis() - fadingSince) / 280f
            if (f <= 0f) fadingSwipe = null else { drawSwipe(canvas, path, f); postOnAnimation(nextFrame) }
        }
        touch?.let { t ->
            when {
                t.panel != null -> drawPanel(canvas, t.panel!!)
                showPreview && t.key.type == KeyType.CHAR && !t.cursorMoved && t.swipe == null -> drawPreview(canvas, t.key, t.rect)
            }
        }
        if (touch == null) ghostKey?.let { (k, r) -> if (showPreview && k.type == KeyType.CHAR) drawPreview(canvas, k, r) }
    }

    private val heatPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val aimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The adaptive keys plugin's heatmap: each remembered mistake a soft red glow where the touch landed, and each
     * letter whose aim has moved a dot where it now is, tied to the key's middle by a line in the theme's colour.
     */
    private fun drawAdaptive(canvas: Canvas, model: AdaptiveKeys) {
        val unit = keyRects.firstOrNull { it.first.type == KeyType.CHAR }?.second ?: return
        val radius = unit.width() * 0.45f
        for (m in model.mistakes) {
            val x = m.x * width; val y = keysTop + m.y * keysHeight
            heatPaint.shader = android.graphics.RadialGradient(x, y, radius, 0x55FF3B30, 0x00FF3B30, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawCircle(x, y, radius, heatPaint)
        }
        heatPaint.shader = null
        aimPaint.color = theme.accent
        aimPaint.strokeWidth = dp(1.5f)
        for ((k, r) in keyRects) {
            if (k.type != KeyType.CHAR || k.text.length != 1 || !k.text[0].isLetter()) continue
            val (ox, oy) = model.offset(k.text[0])
            if (ox == 0f && oy == 0f) continue
            val cx = r.centerX() + ox * r.width(); val cy = r.centerY() + oy * r.height()
            aimPaint.style = Paint.Style.STROKE
            canvas.drawLine(r.centerX(), r.centerY(), cx, cy, aimPaint)
            aimPaint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, dp(3.5f), aimPaint)
        }
    }

    /**
     * Swipe typing's trail: the finger's path as one smooth stroke (quadratic curves through the midpoints of its
     * points), in the accent, fading toward where it started. One path stroked once, so no joins or overlapping caps
     * show. [fade] (1 to 0) dims it as it lingers after a lift.
     */
    private fun drawSwipe(canvas: Canvas, points: List<PointF>, fade: Float = 1f) {
        if (points.size < 2) return
        val from = maxOf(0, points.size - 160)
        val tail = points[from]
        val head = points.last()
        trail.reset()
        trail.moveTo(tail.x, tail.y)
        for (i in from + 1 until points.size) {
            val p = points[i - 1]; val q = points[i]
            trail.quadTo(p.x, p.y, (p.x + q.x) / 2f, (p.y + q.y) / 2f)
        }
        trail.lineTo(head.x, head.y)
        val accent = theme.accent and 0x00FFFFFF
        val strong = ((fade * 235).toInt().coerceIn(0, 255) shl 24) or accent
        val faint = ((fade * 40).toInt().coerceIn(0, 255) shl 24) or accent
        trailPaint.strokeWidth = dp(5f)
        trailPaint.shader = if (kotlin.math.hypot(head.x - tail.x, head.y - tail.y) < dp(4f)) null
            else android.graphics.LinearGradient(tail.x, tail.y, head.x, head.y, faint, strong, android.graphics.Shader.TileMode.CLAMP)
        trailPaint.color = strong
        canvas.drawPath(trail, trailPaint)
    }

    private val trail = Path()
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** The last swipe's path, fading out for a moment after the finger lifts. */
    private var fadingSwipe: List<PointF>? = null
    private var fadingSince = 0L

    /** Each letter key's centre and a letter key's width, for the accuracy record. */
    fun letterLayout(): Pair<Map<Char, PointF>, Float> =
        letterCenters() to (keyRects.firstOrNull { it.first.type == KeyType.CHAR }?.second?.width() ?: 0f)

    /** The space bar's left, top, right and bottom, for the accuracy record. */
    fun spaceRect(): FloatArray? = keyRects.firstOrNull { it.first.type == KeyType.SPACE }?.second?.let { floatArrayOf(it.left, it.top, it.right, it.bottom) }

    /**
     * Where each letter key listens on the letters page, for autocorrect's reading of a word's taps ([Taps]): its
     * middle, moved by the adaptive keys plugin's nudge where it has one, with a letter key's width and the space
     * bar's left, top, right and bottom. On another page, the same as it stood when the letters were left for it
     * ([lettersKeys]): the taps of a word were made on the letters whichever page ends it. Null where the letters
     * have never been laid out.
     */
    fun tapKeys(): Triple<Map<Char, Pair<Float, Float>>, Float, FloatArray?>? {
        if (page != Page.LETTERS) return lettersKeys
        val model = adaptive
        val out = HashMap<Char, Pair<Float, Float>>(48)
        var keyW = 0f
        var space: FloatArray? = null
        for ((k, r) in keyRects) {
            if (k.type == KeyType.SPACE) { space = floatArrayOf(r.left, r.top, r.right, r.bottom); continue }
            if (k.type != KeyType.CHAR || k.text.length != 1 || !k.text[0].isLetter()) continue
            if (keyW == 0f) keyW = r.width()
            val c = k.text[0]
            // Where the key listens now: a trainer run's fix while one is worn, else the plugin's nudge.
            val (ox, oy) = fix?.invoke(c) ?: model?.offset(c) ?: (0f to 0f)
            out[c] = (r.centerX() + ox * r.width()) to (r.centerY() + oy * r.height())
        }
        return if (out.isEmpty() || keyW <= 0f) null else Triple(out, keyW, space)
    }

    /** Each letter key's centre, for the swipe decoder. */
    private fun letterCenters(): Map<Char, PointF> = keyRects
        .filter { (k, _) -> k.type == KeyType.CHAR && k.text.length == 1 && k.text[0].isLetter() }
        .associate { (k, r) -> k.text[0].lowercaseChar() to PointF(r.centerX(), r.centerY()) }

    private val hideRect get() = RectF(width - dp(4f) - toolbarHeight, keyboardTop, width - dp(4f), keysTop)

    /** The old toolbar, where no plugin fills a row: its hide button. Settings opens from the { } key or the comma, held. */
    private fun drawToolbar(canvas: Canvas) {
        stroke.color = theme.toolbarIcon
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = dp(1.8f)
        // Hide: a chevron down.
        val h = hideRect
        path.reset()
        path.moveTo(h.centerX() - dp(6f), h.centerY() - dp(3f))
        path.lineTo(h.centerX(), h.centerY() + dp(3f))
        path.lineTo(h.centerX() + dp(6f), h.centerY() - dp(3f))
        canvas.drawPath(path, stroke)
    }

    private fun drawKey(canvas: Canvas, i: Int, key: Key, f0: RectF, pressed: Boolean) {
        var f = f0
        if (theme.mechanical) {
            f = drawKeycap(canvas, key, f0, pressed)
        } else if (keyBorders) {
            fill.color = when {
                key.type == KeyType.ENTER -> if (pressed) theme.accentPressed else theme.accent
                key.type == KeyType.SHIFT && shift != ShiftState.OFF -> if (pressed) theme.keyPressed else theme.key
                key.functional -> if (pressed) theme.functionKeyPressed else theme.functionKey
                else -> if (pressed) theme.keyPressed else theme.key
            }
            canvas.drawRoundRect(f, keyRadius, keyRadius, fill)
        } else {
            // Gboard: ?123, space and enter are pills about three quarters of a key's height; every other key is only
            // its label, with a wash while pressed.
            val shrink = f.height() * 0.13f
            val p = pillBox.apply { set(f.left, f.top + shrink, f.right, f.bottom - shrink) }
            val pill = p.height() / 2
            when (key.type) {
                KeyType.ENTER -> { fill.color = if (pressed) theme.accentPressed else theme.accent; canvas.drawRoundRect(p, pill, pill, fill) }
                KeyType.SPACE -> { fill.color = if (pressed) theme.keyPressed else theme.key; canvas.drawRoundRect(p, pill, pill, fill) }
                KeyType.MODE -> if (key.pill) {
                    fill.color = if (pressed) theme.keyPressed else theme.key; canvas.drawRoundRect(p, pill, pill, fill)
                } else if (pressed) { fill.color = theme.key; canvas.drawRoundRect(f, keyRadius, keyRadius, fill) }
                else -> if (pressed) { fill.color = theme.key; canvas.drawRoundRect(f, keyRadius, keyRadius, fill) }
            }
        }
        val cx = f.centerX(); val cy = f.centerY()
        when (key.type) {
            KeyType.CHAR -> if (key.text == HALF_SPACE) drawHalfSpace(canvas, cx, cy) else {
                // The label as shift has it, the size it fits the key at and where its baseline sits are worked out
                // once, and kept until the keys change ([stale]): measuring text is the dear part of drawing a key.
                val text = labels[i] ?: shown(key).also { labels[i] = it }
                label.typeface = regular
                label.color = theme.text
                if (sizes[i] == 0f) {
                    val letter = page == Page.LETTERS && text.length == 1 && text[0].isLetter()
                    label.textSize = sp(if (letter) 23f else if (text.length > 2) 17f else 21f) * labelScale
                    fitText(text, f.width() - dp(4f))
                    sizes[i] = label.textSize
                    label.getFontMetrics(metrics)
                    rises[i] = -(metrics.ascent + metrics.descent) / 2
                } else {
                    label.textSize = sizes[i]
                }
                canvas.drawText(text, cx, cy + rises[i], label)
                drawHint(canvas, key, f)
            }
            KeyType.MODE -> {
                label.typeface = medium
                label.textSize = sp(15f) * labelScale
                // On the developer page its { } key is lit: it is the page's way back.
                label.color = if (page == Page.DEV && key.label == "{ }") theme.accent else theme.text
                drawCentered(canvas, key.label, cx, cy)
            }
            KeyType.SPACE -> drawSpaceLabel(canvas, f)
            KeyType.GLOBE -> drawGlobe(canvas, cx, cy)
            // The full stop whose place it took is in its corner, as a letter's long-press character is.
            KeyType.MIC -> { drawMic(canvas, cx, cy, theme.text); drawHint(canvas, key, f) }
            // As the mic key is drawn: the icon alone, in the keys' own colour, with no pill behind it.
            KeyType.EMOJI -> drawPackIcon(canvas, RowIcon.EMOJI, cx, cy, dp(22f), theme.text)
            KeyType.SHIFT -> drawShift(canvas, cx, cy)
            KeyType.DELETE -> drawDelete(canvas, cx, cy)
            KeyType.ENTER -> drawEnter(canvas, cx, cy)
        }
    }

    /** A key's long-press character, small in its corner, where Symbol hints are on. */
    private fun drawHint(canvas: Canvas, key: Key, f: RectF) {
        if (!showHints || key.hint == null) return
        label.typeface = regular
        label.textSize = sp(10.5f) * labelScale
        label.color = theme.hint
        canvas.drawText(key.hint, f.right - dp(if (condensed) 6f else 7f), f.top + dp(if (condensed) 11f else 13f), label)
    }

    /**
     * The space bar's name for the layout being typed on ("Français", "English · Polski"), small and quiet, where
     * there is more than one layout to tell apart. The shorter name ("FR") where the full one will not fit.
     */
    private fun drawSpaceLabel(canvas: Canvas, f: RectF) {
        val full = spaceLabel ?: return
        if (page != Page.LETTERS) return
        label.typeface = regular
        label.textSize = sp(13f)
        label.color = theme.hint
        val room = f.width() - dp(16f)
        val text = if (label.measureText(full) <= room) full else spaceShort ?: full
        fitText(text, room)
        if (label.measureText(text) <= room) drawCentered(canvas, text, f.centerX(), f.centerY())
    }

    /** The globe key: a globe, drawn (a circle, its equator and a meridian). Tap for the next layout, hold for the list. */
    private fun drawGlobe(canvas: Canvas, cx: Float, cy: Float) {
        val r = dp(9f)
        stroke.color = theme.text
        stroke.strokeWidth = dp(1.6f)
        canvas.drawCircle(cx, cy, r, stroke)
        canvas.drawLine(cx - r, cy, cx + r, cy, stroke)
        canvas.drawOval(RectF(cx - r * 0.45f, cy - r, cx + r * 0.45f, cy + r), stroke)
    }

    /** Persian and Urdu's half-space key: a short open box, a space bar's mark at half its width. */
    private fun drawHalfSpace(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(1f)
        stroke.color = theme.text
        stroke.strokeWidth = dp(1.8f)
        path.reset()
        path.moveTo(cx - 5 * u, cy - 1 * u)
        path.lineTo(cx - 5 * u, cy + 5 * u)
        path.lineTo(cx + 5 * u, cy + 5 * u)
        path.lineTo(cx + 5 * u, cy - 1 * u)
        canvas.drawPath(path, stroke)
        canvas.drawLine(cx, cy - 7 * u, cx, cy + 1 * u, stroke)
    }

    /**
     * The Retro theme's keycap: a darker skirt below a cap with a lit top edge, the cap sinking most of the way into
     * its skirt while pressed. Returns the cap's top face, where the label goes.
     */
    private fun drawKeycap(canvas: Canvas, key: Key, f: RectF, pressed: Boolean): RectF {
        val base = when {
            key.type == KeyType.ENTER -> theme.accent
            key.type == KeyType.SHIFT && shift != ShiftState.OFF -> theme.key
            key.functional -> theme.functionKey
            else -> theme.key
        }
        val depth = dp(5f)
        val travel = if (pressed) depth * 0.7f else 0f
        val r = keyRadius * 0.8f
        // The shadow on the case, then the skirt (the cap's sides).
        fill.color = 0x55000000
        canvas.drawRoundRect(RectF(f.left + dp(0.5f), f.top + travel + dp(1.5f), f.right - dp(0.5f), f.bottom + dp(1.5f)), r, r, fill)
        fill.color = KeyboardTheme.mix(base, 0xFF000000.toInt(), 0.38f)
        canvas.drawRoundRect(RectF(f.left, f.top + travel, f.right, f.bottom), r, r, fill)
        // The cap's top: lighter at its upper edge, as light from above catches it.
        val cap = RectF(f.left + dp(1.5f), f.top + travel, f.right - dp(1.5f), f.bottom - depth + travel - dp(1f))
        fill.color = if (pressed) KeyboardTheme.mix(base, 0xFF000000.toInt(), 0.08f) else base
        canvas.drawRoundRect(cap, r, r, fill)
        fill.color = KeyboardTheme.mix(base, 0xFFFFFFFF.toInt(), 0.35f)
        fill.alpha = if (pressed) 60 else 140
        canvas.drawRoundRect(RectF(cap.left + dp(2f), cap.top + dp(1f), cap.right - dp(2f), cap.top + dp(3f)), dp(1f), dp(1f), fill)
        fill.alpha = 255
        return cap
    }

    /** What a character key shows: its label where it has its own (the developer page), else what it types. */
    private fun shown(key: Key) = if (key.label != key.text) key.label else display(key.text)

    /** An alternative or snippet as it is shown: the caret mark gone, a line break as ⏎, a tab as ⇥. */
    private fun pretty(text: String) = text.replace("¦", "").replace("\n", "⏎").replace("\t", "⇥").trim().ifEmpty { text.trim() }

    /** Shrinks [label]'s text size until [text] fits [width]. */
    private fun fitText(text: String, width: Float) {
        while (label.textSize > sp(9f) && label.measureText(text) > width) label.textSize -= sp(1f)
    }

    /**
     * [text] as shift has it on the letters page: the layout's own shifted letter where it has one (Korean's double
     * consonants), else its capital in the layout's locale (Turkish i gives İ), kept only when it is still one
     * letter for one (ß has no single capital, and stays ß). Scripts without capitals are as they are.
     */
    private fun display(text: String): String {
        if (page != Page.LETTERS || shift == ShiftState.OFF) return text
        spec.shifted[text]?.let { return it }
        if (!spec.script.cased) return text
        val upper = text.uppercase(locale)
        return if (upper.length == text.length) upper else text
    }

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float) {
        label.getFontMetrics(metrics)
        canvas.drawText(text, cx, cy - (metrics.ascent + metrics.descent) / 2, label)
    }

    /** Kept for every measure and every pill, so a frame makes no new ones. */
    private val metrics = Paint.FontMetrics()
    private val pillBox = RectF()

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** [icon] (Phosphor's), [size] across, centred on (cx, cy). */
    private fun drawPackIcon(canvas: Canvas, icon: RowIcon, cx: Float, cy: Float, size: Float, color: Int, filled: Boolean = false) {
        canvas.save()
        canvas.translate(cx - size / 2, cy - size / 2)
        IconDrawable.drawGlyph(canvas, RowIcons.icon(icon), size, color, iconPaint, filled)
        canvas.restore()
    }

    private fun drawShift(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(1f)
        // Filled while on; underlined, too, when locked.
        drawPackIcon(canvas, RowIcon.SHIFT, cx, cy, dp(23f), theme.text, filled = shift != ShiftState.OFF)
        if (shift == ShiftState.LOCKED) {
            stroke.color = theme.text
            stroke.strokeWidth = dp(2f)
            canvas.drawLine(cx - 5 * u, cy + 14 * u, cx + 5 * u, cy + 14 * u, stroke)
        }
    }

    /** A microphone: a capsule, its cradle and its stand. */
    private fun drawMic(canvas: Canvas, cx: Float, cy: Float, color: Int, scale: Float = 1f) {
        drawPackIcon(canvas, RowIcon.MIC, cx, cy, dp(22f) * scale, color)
    }

    private val arcBox = RectF()

    /** The listening panel, over the keys: a ring that swells with the voice, and what to do; arcs that turn while it writes. */
    private fun drawDictation(canvas: Canvas) {
        fill.color = theme.background
        canvas.drawRect(0f, keysTop, width.toFloat(), keysTop + keysHeight + bottomPad, fill)
        val cx = width / 2f
        val cy = keysTop + keysHeight * 0.42f
        val r = dp(34f)
        if (dictation == DictationPhase.TRANSCRIBING) {
            // Turning speech into words: two arcs chasing round the mic, the AI's violet into the accent, until done.
            val t = if (animationsOn()) android.os.SystemClock.uptimeMillis() % 1100L / 1100f else 0f
            val ring = r + dp(10f)
            arcBox.set(cx - ring, cy - ring, cx + ring, cy + ring)
            stroke.strokeWidth = dp(3f)
            stroke.color = theme.accent
            stroke.alpha = 50
            canvas.drawCircle(cx, cy, ring, stroke)
            stroke.alpha = 255
            stroke.color = RowTint.AI.ink(theme)
            canvas.drawArc(arcBox, 360f * t - 90f, 70f, false, stroke)
            stroke.color = theme.accent
            canvas.drawArc(arcBox, 360f * t + 90f, 70f, false, stroke)
            // Drawn again while it writes: for the arcs, and so the words change once it has been at it a while.
            removeCallbacks(nextFrame)
            if (animationsOn()) postOnAnimation(nextFrame) else postDelayed(nextFrame, 500)
        } else {
            fill.color = theme.accent
            fill.alpha = 60
            canvas.drawCircle(cx, cy, r + dp(26f) * level.coerceIn(0f, 1f) + dp(4f), fill)
        }
        fill.color = theme.accent
        fill.alpha = 255
        canvas.drawCircle(cx, cy, r, fill)
        drawMic(canvas, cx, cy + dp(1f), theme.onAccent, 1.6f)
        label.typeface = medium
        label.textSize = sp(15f)
        label.color = theme.text
        val words = if (dictation == DictationPhase.LISTENING) "Listening… tap to finish" else if (writingLong()) "Still writing it down… tap to stop here" else "Writing it down…"
        drawCentered(canvas, words, cx, cy + r + dp(30f))
    }

    private fun drawDelete(canvas: Canvas, cx: Float, cy: Float) {
        drawPackIcon(canvas, RowIcon.DELETE, cx, cy, dp(24f), theme.text)
    }

    private fun drawEnter(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(1f)
        stroke.color = theme.onAccent
        stroke.strokeWidth = dp(2f)
        path.reset()
        when (enterIcon) {
            EnterIcon.RETURN -> { drawPackIcon(canvas, RowIcon.ENTER, cx, cy, dp(22f), theme.onAccent); return }
            EnterIcon.SEARCH -> { drawPackIcon(canvas, RowIcon.SEARCH, cx, cy, dp(21f), theme.onAccent); return }
            else -> Unit
        }
        when (enterIcon) {
            EnterIcon.RETURN -> {
                path.moveTo(cx + 8 * u, cy - 7 * u)
                path.lineTo(cx + 8 * u, cy + 2 * u)
                path.lineTo(cx - 8 * u, cy + 2 * u)
                path.moveTo(cx - 4 * u, cy - 2 * u)
                path.lineTo(cx - 8 * u, cy + 2 * u)
                path.lineTo(cx - 4 * u, cy + 6 * u)
            }
            EnterIcon.SEARCH -> {
                canvas.drawCircle(cx - 2 * u, cy - 2 * u, 6 * u, stroke)
                path.moveTo(cx + 2.5f * u, cy + 2.5f * u)
                path.lineTo(cx + 8 * u, cy + 8 * u)
            }
            EnterIcon.GO, EnterIcon.NEXT -> {
                path.moveTo(cx - 8 * u, cy); path.lineTo(cx + 8 * u, cy)
                path.moveTo(cx + 3 * u, cy - 5 * u); path.lineTo(cx + 8 * u, cy); path.lineTo(cx + 3 * u, cy + 5 * u)
            }
            EnterIcon.PREVIOUS -> {
                path.moveTo(cx + 8 * u, cy); path.lineTo(cx - 8 * u, cy)
                path.moveTo(cx - 3 * u, cy - 5 * u); path.lineTo(cx - 8 * u, cy); path.lineTo(cx - 3 * u, cy + 5 * u)
            }
            EnterIcon.DONE -> {
                path.moveTo(cx - 8 * u, cy); path.lineTo(cx - 3 * u, cy + 5 * u); path.lineTo(cx + 8 * u, cy - 6 * u)
            }
            EnterIcon.SEND -> {
                path.moveTo(cx - 8 * u, cy - 7 * u)
                path.lineTo(cx + 9 * u, cy)
                path.lineTo(cx - 8 * u, cy + 7 * u)
                path.lineTo(cx - 5 * u, cy)
                path.close()
            }
        }
        canvas.drawPath(path, stroke)
    }

    /** Gboard's key preview: a bubble the width of the key standing on top of it, the character drawn large. */
    private fun drawPreview(canvas: Canvas, key: Key, rect: RectF) {
        if (key.text == HALF_SPACE) return
        val f = face(rect)
        val h = min(f.height() * 1.15f, f.top - dp(2f))
        if (h < dp(24f)) return
        val bubble = RectF(f.left - dp(2f), f.top - h - dp(2f), f.right + dp(2f), f.top - dp(2f))
        fill.color = theme.popup
        fill.setShadowLayer(dp(4f), 0f, dp(1f), theme.popupShadow)
        canvas.drawRoundRect(bubble, keyRadius, keyRadius, fill)
        fill.clearShadowLayer()
        label.typeface = regular
        label.textSize = sp(30f)
        label.color = theme.text
        val shownText = shown(key)
        fitText(shownText, bubble.width() - dp(4f))
        drawCentered(canvas, shownText, bubble.centerX(), bubble.centerY())
    }

    // --- the long-press panel ---------------------------------------------------------------------------------------

    /**
     * A long-press panel: its [choices] in [cells], [columns] across (eight for a key's characters, one for the list
     * of layouts). [layouts] makes it the layout switch's list: each choice's layout id, null for "Other keyboards",
     * and [current] the one being typed on.
     */
    private class Panel(val choices: List<String>, val cells: List<RectF>, val bounds: RectF, val columns: Int, val layouts: List<String?>? = null, val current: Int = -1) {
        var selected = 0
    }

    /**
     * The layout switch's list, over the space bar or the globe key: every layout that is on, then "Other keyboards"
     * (Android's own picker). The next layout is already chosen, so holding and letting go flips between two.
     */
    private fun makeLayoutPanel(rect: RectF): Panel? {
        if (choices.size < 2) return null
        val names = choices.map { it.second } + "Other keyboards"
        val ids: List<String?> = choices.map { it.first } + null
        val f = face(rect)
        val pad = dp(6f)
        label.typeface = regular
        label.textSize = sp(16f)
        val w = (names.maxOf { label.measureText(it) } + dp(40f)).coerceIn(dp(180f), width - dp(16f))
        val bottom = f.top - dp(2f)
        val cellH = minOf(dp(44f), (bottom - pad * 2) / names.size)
        val h = names.size * cellH + pad * 2
        val left = (f.centerX() - w / 2).coerceIn(dp(8f), (width - dp(8f) - w).coerceAtLeast(dp(8f)))
        val top = (bottom - h).coerceAtLeast(0f)
        val cells = names.indices.map { i -> RectF(left + pad, top + pad + i * cellH, left + w - pad, top + pad + (i + 1) * cellH) }
        val current = choices.indexOfFirst { it.first == spec.id }
        return Panel(names, cells, RectF(left, top, left + w, top + h), columns = 1, layouts = ids, current = current).also { it.selected = (current + 1) % choices.size }
    }

    /**
     * A key's long-press panel, standing on the key. Eight choices a line where that fits above the key; where it
     * would not (Vietnamese's o has twenty-two), the lines are made as long as the board is wide, and if they still
     * do not fit their cells are made shorter, so the panel never comes down over the key the finger is on.
     */
    private fun makePanel(key: Key, rect: RectF, home: Boolean = false): Panel? {
        if (key.alts.isEmpty()) return null
        val first = key.alts.map { display(it) }
        val f = face(rect)
        val cellW = f.width() + keyPadX * 2
        val pad = dp(6f)
        val bottom = f.top - dp(2f)
        val room = (bottom - pad * 2).coerceAtLeast(dp(PANEL_CELL_MIN_DP))
        var cellH = f.height()
        var cols = min(first.size, 8)
        var lines = ceil(first.size / cols.toFloat()).toInt()
        if (lines * cellH > room) {
            val across = floor((width - dp(4f) - pad * 2) / cellW).toInt().coerceAtLeast(cols)
            val fit = floor(room / cellH).toInt().coerceAtLeast(1)
            cols = min(first.size, ceil(first.size / fit.toFloat()).toInt().coerceIn(cols, across))
            lines = ceil(first.size / cols.toFloat()).toInt()
            if (lines * cellH > room) cellH = (room / lines).coerceAtLeast(dp(PANEL_CELL_MIN_DP))
        }
        val w = cols * cellW + pad * 2
        val h = lines * cellH + pad * 2
        // Centred on the key where it fits, the first choice nearest the finger.
        var left = f.centerX() - cellW / 2 - pad
        if (left + w > width - dp(2f)) left = width - dp(2f) - w
        if (left < dp(2f)) left = dp(2f)
        val top = (bottom - h).coerceAtLeast(0f)
        val bounds = RectF(left, top, left + w, top + h)
        // The mic key's panel ([home]): its first choice, the full stop, stands in the cell over the key itself even
        // where the panel had to come in from the screen's edge, and starts chosen, so a hold and a lift types it
        // (the finger chooses the cell it is under: on the phone that was the colon). Every other panel keeps the
        // order it has always had.
        val at = if (home) PanelOrder.home(f.centerX(), left + pad, cellW, cols, first.size) else 0
        val choices = PanelOrder.withFirstAt(first, at)
        val cells = choices.indices.map { i ->
            // The first line is the one nearest the key, as Gboard fills its panels.
            val line = lines - 1 - i / cols
            val col = i % cols
            RectF(left + pad + col * cellW, top + pad + line * cellH, left + pad + (col + 1) * cellW, top + pad + (line + 1) * cellH)
        }
        return Panel(choices, cells, bounds, cols).also { it.selected = at }
    }

    private fun drawPanel(canvas: Canvas, panel: Panel) {
        fill.color = theme.popup
        fill.setShadowLayer(dp(6f), 0f, dp(2f), theme.popupShadow)
        canvas.drawRoundRect(panel.bounds, keyRadius * 1.5f, keyRadius * 1.5f, fill)
        fill.clearShadowLayer()
        label.typeface = regular
        label.textSize = sp(22f)
        panel.choices.forEachIndexed { i, choice ->
            val cell = panel.cells[i]
            if (i == panel.selected) {
                fill.color = theme.popupSelected
                canvas.drawRoundRect(RectF(cell.left + dp(2f), cell.top + dp(2f), cell.right - dp(2f), cell.bottom - dp(2f)), keyRadius, keyRadius, fill)
                label.color = theme.onPopupSelected
            } else {
                // In the list of layouts the one being typed on is in the accent.
                label.color = if (panel.layouts != null && i == panel.current) theme.accent else theme.text
            }
            val text = pretty(choice)
            label.textSize = sp(if (panel.layouts != null) 16f else 22f)
            fitText(text, cell.width() - dp(4f))
            drawCentered(canvas, text, cell.centerX(), cell.centerY())
        }
    }

    private fun selectInPanel(panel: Panel, x: Float, y: Float) {
        if (panel.layouts != null) {
            // The list of layouts: the finger starts below it, on the bar, and what was chosen stays until it comes up into the list.
            if (y > panel.bounds.bottom) return
            val index = panel.cells.indexOfFirst { y < it.bottom }.let { if (it < 0) panel.cells.size - 1 else it }
            if (index != panel.selected) { panel.selected = index; redraw() }
            return
        }
        val cols = panel.columns
        val cellW = panel.cells[0].width()
        val cellH = panel.cells[0].height()
        val lines = ceil(panel.choices.size / cols.toFloat()).toInt()
        val left = panel.cells[0].left
        val col = floor((x - left) / cellW).toInt().coerceIn(0, cols - 1)
        // A finger below the panel is still on its first line.
        val top = panel.bounds.top + dp(6f)
        val lineFromTop = floor((y - top) / cellH).toInt().coerceIn(0, lines - 1)
        val line = lines - 1 - lineFromTop
        val index = (line * cols + col).coerceAtMost(panel.choices.size - 1)
        if (index != panel.selected) { panel.selected = index; redraw() }
    }

    // --- touch ------------------------------------------------------------------------------------------------------

    private class Touch(val pointerId: Int, var key: Key, var rect: RectF, val downX: Float, val downY: Float) {
        val firstKey = key
        /** Where the touch was read as aiming, after correction (keyAt's x and y). */
        var aimX = downX
        var aimY = downY
        /** The key the keyboard as it is would have read the touch as, while a trainer run is typed on a fix ([fix]); null otherwise. */
        var asIs: Key? = null
        var lastX = downX
        var lastY = downY
        var panel: Panel? = null
        var longPressed = false
        var cursorAnchor = downX
        var cursorMoved = false
        /** Cursor control: where the next line step is measured from, as the finger slides up or down from space. */
        var lineAnchor = downY
        /** Cursor control: where the next word is measured from, as the finger slides left from backspace. */
        var wordAnchor = downX
        var wordsDeleted = false
        /** How many times a held backspace has repeated: past [DELETE_CHARS] it takes whole words. */
        var repeats = 0
        /** Swipe typing: the path so far, once the finger has slid far enough to be spelling a word. */
        var swipe: ArrayList<PointF>? = null
    }

    private var touch: Touch? = null
    private val timer = Handler(Looper.getMainLooper())

    private val longPress = Runnable { touch?.let { onLongPress(it) } }
    /**
     * A held backspace: a character every 50 ms, then, once it has taken [DELETE_CHARS] (a little over a second),
     * whole words every [DELETE_WORD_MS] (Matt, 2026-11-10: "when holding backspace make it start to delete entire
     * words at a time"), as Gboard and SwiftKey do. A sound for each character or word it takes (Matt, 2026-11-09);
     * the buzz stays on the first press.
     */
    private val repeatDelete = object : Runnable {
        override fun run() {
            val t = touch ?: return
            if (t.key.type != KeyType.DELETE) return
            listener.onKeyFeedback(t.key, haptic = false)
            if (t.repeats < DELETE_CHARS) {
                t.repeats++
                listener.onDelete()
                timer.postDelayed(this, 50)
            } else {
                listener.onDeleteWord()
                timer.postDelayed(this, DELETE_WORD_MS)
            }
        }
    }

    /**
     * The key a touch at (rawX, rawY) means, after touch position correction (Gboard and AOSP's keyboard have their
     * own). Measured from Matt's tap log (159 taps, 0.1.3): thumbs land further below the keys the higher the row
     * (top row 9.5dp low, middle 5.6dp, bottom on target), and the left thumb lands left of its keys (about 5dp).
     * So a touch is read [LIFT_TOP_DP] higher in the top rows, [LIFT_MIDDLE_DP] in the middle row, as it is in the two
     * bottom rows, and [SHIFT_LEFT_HALF_DP] to the right on the left half.
     */
    private fun keyAt(rawX: Float, rawY: Float): Pair<Key, RectF>? {
        if (rawY < keysTop) return null
        // Docked or one-handed: the empty side is not a key (a little past the keys' edge still is).
        if (rawX < areaLeft - dp(DOCK_SLACK_DP) || rawX > areaRight + dp(DOCK_SLACK_DP)) return null
        val (x, y) = if (page == Page.LETTERS) {
            val (ox, oy) = letterCorrection(rawX, rawY)
            (rawX - ox) to (rawY - oy).coerceAtLeast(keysTop + 1f)
        } else {
            val rowH = keysHeight / rows.size
            val fromBottom = rows.size - 1 - ((rawY - keysTop) / rowH).toInt().coerceIn(0, rows.size - 1)
            val lift = when (fromBottom) { 0, 1 -> 0f; 2 -> dp(LIFT_MIDDLE_DP); else -> dp(LIFT_TOP_DP) }
            (if (rawX < width / 2f) rawX + dp(SHIFT_LEFT_HALF_DP) else rawX) to (rawY - lift).coerceAtLeast(keysTop + 1f)
        }
        lastAim = x to y
        lastAsIs = null
        keyRects.firstOrNull { it.second.contains(x, y) }?.let { hit ->
            val plugin = adaptive?.let { model -> { c: Char -> model.offset(c) } }
            val worn = fix ?: return adapt(hit, x, y, plugin)
            // A trainer run typed on a fix: the fix reads the touch, and the keyboard as it is reads it too.
            lastAsIs = adapt(hit, x, y, plugin).first
            return adapt(hit, x, y, worn)
        }
        // Below the last row or past its ends: the nearest key, so the edges never swallow a touch.
        return keyRects.minByOrNull { (_, r) ->
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            dx * dx + dy * dy
        }
    }

    /**
     * On the letters page, how far from where it is aimed a thumb lands at (x, y): a smooth blend of each key's own
     * measured offset ([LETTER_OFFSETS_DP], given to each key by its place: [keyOffsets]), weighted by how near its centre the touch is. The bottom row's keys
     * (?123, space, enter...) count as landing on target, which keeps the blend from leaking into them.
     */
    private fun letterCorrection(x: Float, y: Float): Pair<Float, Float> {
        val unitW = keyRects.firstOrNull { it.first.type == KeyType.CHAR }?.second?.width() ?: return 0f to 0f
        val sigma2 = 2f * (unitW * 0.6f) * (unitW * 0.6f)
        var sw = 0f; var sx = 0f; var sy = 0f
        val bottomTop = keyRects.last().second.top
        for (i in keyRects.indices) {
            val r = keyRects[i].second
            val off = keyOffsets[i]
            if (off == null && r.top < bottomTop) continue
            val dx = x - r.centerX(); val dy = y - r.centerY()
            val w = kotlin.math.exp(-(dx * dx + dy * dy) / sigma2)
            sw += w
            if (off != null) { sx += w * dp(off.first); sy += w * dp(off.second) }
        }
        return if (sw < 1e-6f) 0f to 0f else (sx / sw) to (sy / sw)
    }

    /** keyAt's corrected point for the last touch, for [Touch.aimX]. */
    private var lastAim: Pair<Float, Float> = 0f to 0f

    /** keyAt's second reading of the last touch, for [Touch.asIs]: the key the keyboard as it is would have read it as, while a [fix] is worn. */
    private var lastAsIs: Key? = null

    /**
     * The adaptive keys plugin, and a trainer run's fix: of the letter under the touch and its neighbours, the one
     * that listens nearest (in fractions of a key), each listening [offset] from its middle. With nothing learned
     * every key listens at its middle, which is the key under the touch but for a touch in a key's corner, where
     * the next row's key half a key along is nearer. Null: the key under the touch, as with no plugin.
     */
    private fun adapt(hit: Pair<Key, RectF>, x: Float, y: Float, offset: ((Char) -> Pair<Float, Float>)?): Pair<Key, RectF> {
        if (offset == null) return hit
        if (page != Page.LETTERS || hit.first.type != KeyType.CHAR || hit.first.text.length != 1 || !hit.first.text[0].isLetter()) return hit
        val w = hit.second.width(); val h = hit.second.height()
        fun cost(r: RectF, c: Char): Float {
            val (ox, oy) = offset(c)
            val cx = r.centerX() + ox * r.width(); val cy = r.centerY() + oy * r.height()
            val dx = (x - cx) / r.width(); val dy = (y - cy) / r.height()
            return dx * dx + dy * dy
        }
        return keyRects.asSequence()
            .filter { (k, r) -> k.type == KeyType.CHAR && k.text.length == 1 && k.text[0].isLetter() && kotlin.math.abs(r.centerX() - hit.second.centerX()) < w * 1.6f && kotlin.math.abs(r.centerY() - hit.second.centerY()) < h * 1.6f }
            .minByOrNull { (k, r) -> cost(r, k.text[0]) } ?: hit
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = Perf.time(
        when (event.actionMasked) { MotionEvent.ACTION_MOVE -> "touch.move"; MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> "touch.up"; else -> "touch.down" },
    ) { touched(event) }

    private fun touched(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val x = event.getX(i); val y = event.getY(i)
                // Rolling typing: a second finger down finishes the first finger's key.
                touch?.let { if (it.panel == null) release(it) else return true }
                if (event.actionMasked == MotionEvent.ACTION_DOWN && y < keysTop) {
                    // The plugin row lies under this view: its strip's touches are the row's.
                    if (pluginRow && y >= keyboardTop) return false
                    if (!showToolbar || y < keyboardTop) return true
                    if (hideRect.contains(x, y)) { performHapticFeedback(KEYBOARD_TAP); listener.onHideKeyboard() }
                    return true
                }
                if (dictation != DictationPhase.OFF) {
                    if (event.actionMasked == MotionEvent.ACTION_DOWN && (dictation == DictationPhase.LISTENING || writingLong())) listener.onMic()
                    return true
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN && (x < areaLeft - dp(DOCK_SLACK_DP) || x > areaRight + dp(DOCK_SLACK_DP))) {
                    dockFlip()?.let { (_, side) -> performHapticFeedback(KEYBOARD_TAP); listener.onDock(side, isWide) }
                    return true
                }
                val (found, foundRect) = keyAt(x, y) ?: return true
                // The mic key's edge by the bar, hit in fast typing, is the bar (Layouts.micEdgeIsSpace).
                val bar = if (found.type == KeyType.MIC && page == Page.LETTERS) keyRects.firstOrNull { it.first.type == KeyType.SPACE } else null
                val (key, rect) = if (bar != null && Layouts.micEdgeIsSpace(foundRect.left, foundRect.right, bar.second.left, bar.second.right, x, event.eventTime - downAt, lastDownLetter)) bar else found to foundRect
                lastDownLetter = key.type == KeyType.CHAR
                downAt = event.eventTime
                press(Touch(event.getPointerId(i), key, rect, x, y).also { it.aimX = lastAim.first; it.aimY = lastAim.second; it.asIs = lastAsIs })
            }
            MotionEvent.ACTION_MOVE -> {
                val t = touch ?: return true
                val i = event.findPointerIndex(t.pointerId)
                if (i < 0) return true
                // A swipe takes the points Android batched between frames too, so its trail follows the finger closely.
                t.swipe?.let { path -> for (h in 0 until event.historySize) addSwipePoint(path, event.getHistoricalX(i, h), event.getHistoricalY(i, h), event.getHistoricalEventTime(h)) }
                moveAt = event.eventTime
                move(t, event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val t = touch ?: return true
                if (event.getPointerId(event.actionIndex) == t.pointerId) release(t)
            }
            MotionEvent.ACTION_CANCEL -> cancelTouch()
        }
        return true
    }

    private fun press(t: Touch) {
        touch = t
        listener.onKeyFeedback(t.key)
        backlight?.press(lightId(t.key), t.rect.centerX(), t.rect.centerY(), android.os.SystemClock.uptimeMillis())
        when (t.key.type) {
            KeyType.DELETE -> {
                listener.onDelete()
                timer.postDelayed(repeatDelete, 400)
            }
            KeyType.CHAR, KeyType.SPACE, KeyType.SHIFT, KeyType.GLOBE -> timer.postDelayed(longPress, longPressMs)
            // The mic key holds the full stop it stands in for; not while it is listening, when a touch only ends the take.
            KeyType.MIC -> if (dictation == DictationPhase.OFF) timer.postDelayed(longPress, longPressMs)
            KeyType.MODE -> if (t.key.label == "{ }") timer.postDelayed(longPress, longPressMs)
            // The emoji key stands where the { } key did, and a hold on it opens Settings as a hold there always has.
            KeyType.EMOJI -> timer.postDelayed(longPress, longPressMs)
            else -> Unit
        }
        // A key down changes no key at rest: the frame is the same keys with one pressed.
        redraw()
    }

    private fun move(t: Touch, x: Float, y: Float) {
        t.lastX = x
        t.lastY = y
        t.panel?.let { selectInPanel(it, x, y); return }
        when (t.key.type) {
            KeyType.SPACE -> if (spaceCursor) {
                // Gboard's cursor control: slide along the space bar to move the cursor a character at a time.
                val step = dp(12f)
                val dx = x - t.cursorAnchor
                if (abs(dx) >= step && (t.cursorMoved || abs(x - t.downX) > dp(16f))) {
                    if (!t.cursorMoved) { t.cursorMoved = true; timer.removeCallbacks(longPress) }
                    val steps = (dx / step).toInt()
                    t.cursorAnchor += steps * step
                    listener.onCursor(steps)
                }
                // Up or down from the space bar moves by lines: a longer pull than a character's, so a sideways slide
                // that drifts does not jump lines.
                val lineStep = dp(30f)
                val dy = y - t.lineAnchor
                if (abs(dy) >= lineStep && (t.cursorMoved || abs(y - t.downY) > dp(34f))) {
                    if (!t.cursorMoved) { t.cursorMoved = true; timer.removeCallbacks(longPress) }
                    val lines = (dy / lineStep).toInt()
                    t.lineAnchor += lines * lineStep
                    listener.onCursorLine(lines)
                }
            }
            KeyType.DELETE -> if (deleteSwipe) {
                // Gboard's word delete: slide left from backspace and each stretch of the slide takes a word.
                val step = dp(26f)
                val dx = t.wordAnchor - x
                if (dx >= step && (t.wordsDeleted || t.downX - x > dp(30f))) {
                    if (!t.wordsDeleted) { t.wordsDeleted = true; timer.removeCallbacks(repeatDelete) }
                    val words = (dx / step).toInt()
                    t.wordAnchor -= words * step
                    repeat(words) { listener.onKeyFeedback(t.key, haptic = false); listener.onDeleteWord() }
                }
            }
            KeyType.CHAR -> {
                t.swipe?.let { addSwipePoint(it, x, y); redraw(); return }
                // Swipe typing: a slide of most of a key's width from a letter starts spelling a word.
                if (swipeTyping && page == Page.LETTERS && t.panel == null && t.firstKey.text.length == 1 && t.firstKey.text[0].isLetter() &&
                    kotlin.math.hypot(x - t.downX, y - t.downY) > t.rect.width() * 0.9f
                ) {
                    t.swipe = arrayListOf(PointF(t.downX, t.downY), PointF(x, y))
                    swipeTimes.clear(); swipeTimes += downAt; swipeTimes += moveAt
                    timer.removeCallbacks(longPress)
                    redraw()
                    return
                }
                // A finger that clearly slides onto another character key types that one instead. A thumb rolls a
                // little as it lifts, so staying within 40% of a key's size past its edge is still that key.
                val slackX = t.rect.width() * 0.4f
                val slackY = t.rect.height() * 0.4f
                if (x >= t.rect.left - slackX && x <= t.rect.right + slackX && y >= t.rect.top - slackY && y <= t.rect.bottom + slackY) return
                val (key, rect) = keyAt(x, y) ?: return
                if (key !== t.key && key.type == KeyType.CHAR) {
                    t.key = key; t.rect = rect
                    timer.removeCallbacks(longPress)
                    timer.postDelayed(longPress, longPressMs)
                    redraw()
                }
            }
            else -> Unit
        }
    }

    /** A point on a swipe's path, unless it is within a hair of the last (jitter that would kink the curve). */
    private fun addSwipePoint(path: ArrayList<PointF>, x: Float, y: Float, at: Long = moveAt) {
        val last = path.lastOrNull()
        if (last != null && kotlin.math.hypot(x - last.x, y - last.y) < dp(1.5f)) return
        path.add(PointF(x, y))
        swipeTimes += at
    }

    /** When each point of the swipe being drawn was made (ms, uptime): the finger slows at the letters it means. */
    private val swipeTimes = ArrayList<Long>()
    private var downAt = 0L
    /** Whether the last touch to go down was a letter's: [Layouts.micEdgeIsSpace] reads it. */
    private var lastDownLetter = false
    private var moveAt = 0L
    /** The last finished swipe's point times, alongside the path given to onSwipe, for the accuracy record. */
    var lastSwipeTimes: List<Long> = emptyList()
        private set

    private fun onLongPress(t: Touch) {
        when (t.key.type) {
            // Holding the space bar starts cursor control where it is on (Matt, 2026-11-09: "Holding spacebar is opening
            // the keyboard selection which makes cursor navigation hard"): a tick, and every slide from here moves the
            // cursor at once, with no first stretch to cross. Let go without sliding and nothing is typed. With cursor
            // control off, the hold opens the list of layouts where more than one is on, else Android's keyboard
            // picker; the globe key always opens the list. Android's own switch button, under the keys, opens the
            // picker either way.
            KeyType.SPACE -> if (spaceCursor) {
                performHapticFeedback(LONG_PRESS)
                t.cursorMoved = true
                t.cursorAnchor = t.lastX
                t.lineAnchor = t.lastY
            } else {
                t.longPressed = true
                val panel = makeLayoutPanel(t.rect)
                if (panel != null) { t.panel = panel; performHapticFeedback(LONG_PRESS); redraw() } else listener.onShowPicker()
            }
            KeyType.GLOBE -> {
                t.longPressed = true
                val panel = makeLayoutPanel(t.rect)
                if (panel != null) { t.panel = panel; performHapticFeedback(LONG_PRESS); redraw() }
            }
            // The { } key, where Gboard's comma was: long-press opens settings, as the comma's did.
            KeyType.MODE, KeyType.EMOJI -> { t.longPressed = true; performHapticFeedback(LONG_PRESS); listener.onOpenSettings() }
            KeyType.SHIFT -> {
                t.longPressed = true
                shift = ShiftState.LOCKED; autoShifted = false
                performHapticFeedback(LONG_PRESS); listener.onShiftChanged(); invalidate()
            }
            KeyType.CHAR -> {
                if (t.key.text == ",") {
                    t.longPressed = true
                    listener.onOpenSettings()
                    return
                }
                val panel = makePanel(t.key, t.rect) ?: return
                t.panel = panel
                t.longPressed = true
                performHapticFeedback(LONG_PRESS)
                redraw()
            }
            // The mic key, where the full stop was: its long-press is the full stop's, the full stop first, so a hold
            // and a lift types one and a slide reaches the rest.
            KeyType.MIC -> {
                val panel = makePanel(t.key, t.rect, home = true) ?: return
                t.panel = panel
                t.longPressed = true
                performHapticFeedback(LONG_PRESS)
                redraw()
            }
            else -> Unit
        }
    }

    private fun release(t: Touch) {
        timer.removeCallbacks(longPress)
        timer.removeCallbacks(repeatDelete)
        touch = null
        val panel = t.panel
        val swipe = t.swipe
        when {
            swipe != null -> {
                val unitW = keyRects.firstOrNull { it.first.type == KeyType.CHAR }?.second?.width() ?: t.rect.width()
                lastSwipeTimes = swipeTimes.toList()
                listener.onSwipe(swipe, letterCenters(), unitW)
                fadingSwipe = swipe
                fadingSince = android.os.SystemClock.uptimeMillis()
                if (shift == ShiftState.ON) { shift = ShiftState.OFF; autoShifted = false; listener.onShiftChanged() }
            }
            panel != null && panel.layouts != null -> panel.layouts[panel.selected].let { id -> if (id == null) listener.onShowPicker() else if (id != spec.id) listener.onLayout(id) }
            panel != null -> commit(panel.choices[panel.selected])
            t.longPressed -> Unit
            else -> when (t.key.type) {
                KeyType.CHAR -> {
                    lastTyped = if (t.key === t.firstKey && t.key.text.length == 1) {
                        val asIs = t.asIs?.takeIf { it.type == KeyType.CHAR && it.text.length == 1 }?.text?.get(0)
                        Typed(t.key.text[0], RectF(t.rect), t.aimX, t.aimY, asIs ?: t.key.text[0])
                    } else null
                    commit(display(t.key.text))
                    // A character key that names a page goes there after typing: the developer page's comma.
                    t.key.page?.let { showPage(it) }
                }
                KeyType.SPACE -> if (!t.cursorMoved) { lastSpace = Typed(' ', RectF(t.rect), t.aimX, t.aimY); listener.onSpace() }
                KeyType.ENTER -> listener.onEnter()
                KeyType.SHIFT -> tapShift()
                KeyType.MODE -> showPage(t.key.page ?: Page.LETTERS)
                KeyType.MIC -> listener.onMic()
                KeyType.EMOJI -> listener.onEmoji()
                KeyType.GLOBE -> nextLayout()?.let { listener.onLayout(it) }
                KeyType.DELETE -> Unit
            }
        }
        invalidate()
    }

    private fun cancelTouch() {
        timer.removeCallbacks(longPress)
        timer.removeCallbacks(repeatDelete)
        touch = null
        redraw()
    }

    private fun commit(raw: String) {
        if (shift == ShiftState.ON) { shift = ShiftState.OFF; autoShifted = false }
        val caret = raw.indexOf('¦')
        if (caret < 0) listener.onText(raw) else listener.onText(raw.removeRange(caret, caret + 1), raw.length - 1 - caret)
        invalidate()
    }

    private fun tapShift() {
        val now = System.currentTimeMillis()
        shift = when (shift) {
            ShiftState.OFF -> ShiftState.ON
            // Gboard: a second tap soon after the first locks caps.
            ShiftState.ON -> if (!autoShifted && now - lastShiftTap < 400) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
        autoShifted = false
        lastShiftTap = now
        listener.onShiftChanged()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelTouch()
    }

    companion object {
        /** A held backspace takes this many characters one by one, then whole words, one each [DELETE_WORD_MS]. */
        const val DELETE_CHARS = 15
        const val DELETE_WORD_MS = 200L
        /** The steps a legend's light is drawn in, and how its code is told apart ([lightCode]). */
        private const val LIGHT_LEVELS = 64
        private const val LIGHT_LEVEL_MASK = 0x7F
        private const val LIGHT_DIMS = 1 shl 25
        /** The most filters kept at once ([lightFilter]). */
        private const val LIGHT_FILTERS = 512
        /** A condensed key row, row to row (Normal is 60). */
        const val CONDENSED_ROW_DP = 46f
        /** Wider than this (dp), the keys split: an unfolded foldable's inner screen is 670 or more, a phone 430 or less. */
        const val SPLIT_MIN_DP = 600f
        /** The open middle of a split keyboard, as a share of its width. */
        const val SPLIT_GAP = 0.3f
        /** A docked keyboard on a wide screen is this wide (dp): a phone's keyboard, in reach of one thumb. */
        const val COMPACT_DP = 400f
        /** One-handed on a phone: the keys take this share of the width, on the thumb's side. */
        const val ONE_HANDED_SHARE = 0.8f
        /** A touch this far (dp) past a docked keyboard's edge still means its nearest key. */
        private const val DOCK_SLACK_DP = 14f
        /** The strip above the keys of the preview in Settings (dp), where a key's popup shows. */
        const val EMBEDDED_TOP_DP = 40f

        /** The least height a long-press panel's cell is squeezed to, so a choice can still be read and hit. */
        private const val PANEL_CELL_MIN_DP = 30f
        /** How long a take's last words are written down before a tap may stop it there. */
        const val WRITING_WAIT_MS = 3000L
        /** The strip Android draws its collapse and switch-keyboard buttons in, with gesture navigation (dp). */
        const val NAV_BUTTONS_DP = 48f
        /** The most a gesture bar by itself is given (dp), and what is kept where the phone draws no gesture bar at all: the corners of the screen. */
        const val GESTURE_BAR_DP = 24f
        const val HIDDEN_BAR_DP = 12f
        /** What "More" adds under the keys (dp). */
        const val MORE_SPACE_DP = 20f
        /**
         * Where Matt's thumb lands relative to each letter's centre, in dp (+x right, +y down): the mean of ~325
         * logged taps over 0.1.3 and 0.1.5, each key pulled toward its row-half's mean where it had few taps
         * (6 taps' weight), capped at 14dp across and 20dp down.
         */
        /** QWERTY's letter rows and how far in each starts (key widths): the places [LETTER_OFFSETS_DP] was measured at. */
        private val QWERTY_ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
        private val QWERTY_INSETS = floatArrayOf(0f, 0.5f, 1.5f)

        private val LETTER_OFFSETS_DP: Map<String, Pair<Float, Float>> = mapOf(
            "q" to (-3.6f to 7.0f), "w" to (-4.1f to 3.0f), "e" to (-5.9f to 2.7f), "r" to (-5.9f to 8.5f),
            "t" to (-9.0f to 6.2f), "y" to (0.7f to 13.9f), "u" to (-2.2f to 17.0f), "i" to (-0.7f to 14.1f),
            "o" to (-3.8f to 16.7f), "p" to (-4.9f to 17.0f), "a" to (-7.2f to 2.7f), "s" to (-10.5f to 4.6f),
            "d" to (-10.1f to 3.2f), "f" to (-4.4f to 5.9f), "g" to (-6.2f to 9.7f), "h" to (5.6f to 8.7f),
            "j" to (3.8f to 3.8f), "k" to (0.5f to 5.6f), "l" to (-2.0f to 14.3f), "z" to (-0.3f to 3.6f),
            "x" to (1.4f to 3.7f), "c" to (-1.3f to 3.6f), "v" to (-3.0f to 0.0f), "b" to (1.5f to -3.3f),
            "n" to (0.2f to 3.5f), "m" to (-1.7f to 0.2f),
        )

        /** The other pages' touch position correction, from the first tap log (see [keyAt]). */
        private const val LIFT_TOP_DP = 11f
        private const val LIFT_MIDDLE_DP = 6f
        private const val SHIFT_LEFT_HALF_DP = 5f
        /** What Persian and Urdu's half-space key types (U+200C): drawn as a mark, never as the invisible character. */
        private const val HALF_SPACE = "\u200C"
        private const val KEYBOARD_TAP = android.view.HapticFeedbackConstants.KEYBOARD_TAP
        private const val LONG_PRESS = android.view.HapticFeedbackConstants.LONG_PRESS
    }
}
