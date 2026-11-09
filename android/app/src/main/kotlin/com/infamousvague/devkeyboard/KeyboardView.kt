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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** What the keyboard asks of whoever hosts it (the input method service). */
interface KeyboardListener {
    /** [text] typed, the cursor left [caretBack] characters before its end (a pair's inside). */
    fun onText(text: String, caretBack: Int = 0)
    fun onDelete()
    fun onSpace()
    fun onEnter()
    fun onCursor(steps: Int)
    fun onShiftChanged()
    fun onKeyFeedback(key: Key)
    fun onShowPicker()
    fun onOpenSettings()
    fun onHideKeyboard()
    /** The mic key, or a tap on the listening panel: starts or ends a dictation. */
    fun onMic()
    /** Swipe typing: the finger's [path] over the letters, each letter's key centre, and a key's width. */
    fun onSwipe(path: List<PointF>, keys: Map<Char, PointF>, keyWidth: Float) {}
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
    override fun onKeyFeedback(key: Key) = guard("onKeyFeedback") { inner.onKeyFeedback(key) }
    override fun onShowPicker() = guard("onShowPicker") { inner.onShowPicker() }
    override fun onOpenSettings() = guard("onOpenSettings") { inner.onOpenSettings() }
    override fun onHideKeyboard() = guard("onHideKeyboard") { inner.onHideKeyboard() }
    override fun onMic() = guard("onMic") { inner.onMic() }
    override fun onSwipe(path: List<PointF>, keys: Map<Char, PointF>, keyWidth: Float) = guard("onSwipe") { inner.onSwipe(path, keys, keyWidth) }
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
class KeyboardView(context: Context, host: KeyboardListener) : View(context) {
    private val listener: KeyboardListener = SafeListener(host)

    // --- settings ---------------------------------------------------------------------------------------------------

    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(value) { field = value; invalidate() }
    var showPreview = true
    var showHints = true
        set(value) { field = value; invalidate() }
    var spaceCursor = true
    var longPressMs = 300L

    /** Gboard's look: only ?123, space and enter have a shape; letters float. On, every key has its own face. */
    var keyBorders = false
        set(value) { field = value; invalidate() }

    /** The dictation plugin is added and its model is here: the mic key stands beside the space bar. */
    var micKey = false
        set(value) { if (field != value) { field = value; rebuild(); invalidate() } }

    var dictation = DictationPhase.OFF
        set(value) { field = value; if (value == DictationPhase.OFF) level = 0f; invalidate() }

    /** How loud the microphone hears it, 0..1, eased. */
    var level = 0f
        set(value) { field = field * 0.6f + value * 0.4f; invalidate() }

    /**
     * The plugin row is shown: a real view (PluginRow) sits in the strip between the preview room and the keys, so
     * this view leaves that strip unpainted and lets its touches through.
     */
    var pluginRow = false
        set(value) { if (field != value) { field = value; requestLayout(); invalidate() } }

    /** Settings › Long-press keys, as KeyboardPrefs keeps it. */
    var popupKeys = ""
        set(value) { if (field != value) { field = value; Layouts.custom = Layouts.parseCustom(value); rebuild(); invalidate() } }

    /** Split the keys in two on a wide screen (Settings › Look & layout). */
    var split = true
        set(value) { if (field != value) { field = value; layoutKeys(); invalidate() } }

    /** The swipe typing plugin is added: a finger sliding across the letters spells a word. */
    var swipeTyping = false

    /** Settings › Developer › Tap log, and not in a password field (the service decides both). */
    var tapLog = false
    private var numberRow = false
    private var showToolbar = false
    private var rowHeightDp = 60f

    fun configure(numberRow: Boolean, keyHeight: String, toolbar: Boolean) {
        // Gboard's rows, measured from its own screenshot: a 48dp key and a 12dp gap, 60dp from row to row.
        val height = when (keyHeight) { "short" -> 52f; "tall" -> 68f; else -> 60f }
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

    fun showPage(next: Page) {
        page = next
        if (next != Page.LETTERS) { shift = ShiftState.OFF; autoShifted = false }
        rebuild()
        invalidate()
    }

    /** Auto-capitalisation from the editor: turns shift on, or back off if it alone turned it on. */
    fun setAutoShift(on: Boolean) {
        if (page != Page.LETTERS || shift == ShiftState.LOCKED) return
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
        cancelTouch()
        rebuild()
        invalidate()
    }

    private fun rebuild() {
        val base = when (page) {
            Page.LETTERS -> Layouts.letters(numberRow)
            Page.SYMBOLS -> Layouts.symbols()
            Page.MORE -> Layouts.more()
            Page.DEV -> Layouts.dev()
        }
        rows = if (micKey) Layouts.withMic(base) else base
        layoutKeys()
    }

    // --- metrics ----------------------------------------------------------------------------------------------------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /** Room above the keyboard for the key preview and long-press panels, see-through and not touchable there. */
    private val previewSpace get() = dp(rowHeightDp * 1.3f)
    /** Where the keyboard itself starts, below [previewSpace]: the service tells Android only this part is the IME. */
    val keyboardTop get() = previewSpace
    // Gboard leaves 24dp above the first row's keys (this, and the key's own 6dp of row gap).
    private val toolbarHeight get() = if (showToolbar || pluginRow) dp(44f) else dp(18f)
    private val keysTop get() = keyboardTop + toolbarHeight
    /** The plugin row's strip, and where the keys end (above Android's buttons): KeyboardFrame lays out by these. */
    val rowTop get() = keyboardTop
    val rowBottom get() = keysTop
    val keysBottom get() = keysTop + keysHeight + bottomPad
    // Gboard: keys 1.5dp + half a 6dp gap from the screen's edges, and 2dp + half a row gap above Android's buttons.
    private val sidePad get() = dp(1.5f)
    private val bottomPad get() = dp(2f)
    private val keyPadX get() = dp(3f)
    private val keyPadY get() = dp(6f)
    private val keyRadius get() = dp(6f)
    private var navInset = 0

    /** The letters page sets the height; the symbol pages spread their rows over the same space, as Gboard does. */
    private val rowCount get() = if (numberRow) 5 else 4
    private val keysHeight get() = rowCount * dp(rowHeightDp)

    init {
        isHapticFeedbackEnabled = true
        setOnApplyWindowInsetsListener { _, insets ->
            val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            }
            if (bottom != navInset) { navInset = bottom; requestLayout() }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    /**
     * The strip Android keeps under the keyboard for its own buttons (collapse, switch keyboard) and the gesture bar.
     * Read from the window itself each layout, so it is right however the insets were dispatched.
     */
    private fun bottomInset(): Int {
        val insets = rootWindowInsets ?: return navInset
        val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            maxOf(
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsets.Type.tappableElement()).bottom,
            )
        } else {
            @Suppress("DEPRECATION") insets.systemWindowInsetBottom
        }
        return maxOf(bottom, navInset)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        navInset = bottomInset()
        val height = ceil(keysTop + keysHeight + bottomPad).toInt() + navInset
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun layoutKeys() {
        keyRects.clear()
        if (width == 0) return
        val units = rows.maxOf { it.units }
        // Split on a wide screen (an unfolded foldable, a tablet), as Gboard does: each half keeps a phone's key size
        // and the middle is left open for the thumbs. Keys whose centre is left of the middle go left, the rest right;
        // a key across the middle (the space bar) becomes two, one in each half.
        val gap = if (split && width / resources.displayMetrics.density >= SPLIT_MIN_DP) width * SPLIT_GAP else 0f
        val unit = (width - sidePad * 2 - gap) / units
        val mid = sidePad + units * unit / 2
        val rowH = keysHeight / rows.size
        var y = keysTop
        for (row in rows) {
            var x = sidePad + row.inset * unit + (units - row.units) * unit / 2
            for (key in row.keys) {
                val w = key.width * unit
                val left = x; val right = x + w
                when {
                    gap == 0f -> keyRects += key to RectF(left, y, right, y + rowH)
                    key.width >= 2f && left < mid && right > mid -> {
                        keyRects += key to RectF(left, y, mid, y + rowH)
                        keyRects += key to RectF(mid + gap, y, right + gap, y + rowH)
                    }
                    (left + right) / 2 <= mid + 0.01f -> keyRects += key to RectF(left, y, right, y + rowH)
                    else -> keyRects += key to RectF(left + gap, y, right + gap, y + rowH)
                }
                x += w
            }
            y += rowH
        }
        if (tapLog) {
            val d = resources.displayMetrics.density
            TapLog.geometry(context, "w=${"%.1f".format(width / d)}dp rowH=${"%.1f".format(rowH / d)}dp correction=perkey-v1 density=${"%.2f".format(d)} page=$page numberRow=$numberRow")
        }
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

    override fun onDraw(canvas: Canvas) {
        fill.color = theme.background
        canvas.drawRect(0f, if (pluginRow) keysTop else keyboardTop, width.toFloat(), height.toFloat(), fill)
        if (showToolbar && !pluginRow) drawToolbar(canvas)
        for ((key, rect) in keyRects) drawKey(canvas, key, rect, pressed = touch?.let { it.key === key && it.panel == null && it.swipe == null } == true)
        touch?.swipe?.let { drawSwipe(canvas, it) }
        fadingSwipe?.let { path ->
            val f = 1f - (android.os.SystemClock.uptimeMillis() - fadingSince) / 280f
            if (f <= 0f) fadingSwipe = null else { drawSwipe(canvas, path, f); postInvalidateOnAnimation() }
        }
        if (dictation != DictationPhase.OFF) { drawDictation(canvas); return }
        touch?.let { t ->
            when {
                t.panel != null -> drawPanel(canvas, t.panel!!)
                showPreview && t.key.type == KeyType.CHAR && !t.cursorMoved && t.swipe == null -> drawPreview(canvas, t.key, t.rect)
            }
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

    /** Each letter key's centre, for the swipe decoder. */
    private fun letterCenters(): Map<Char, PointF> = keyRects
        .filter { (k, _) -> k.type == KeyType.CHAR && k.text.length == 1 && k.text[0].isLetter() }
        .associate { (k, r) -> k.text[0].lowercaseChar() to PointF(r.centerX(), r.centerY()) }

    private val settingsRect get() = RectF(dp(4f), keyboardTop, dp(4f) + toolbarHeight, keysTop)
    private val hideRect get() = RectF(width - dp(4f) - toolbarHeight, keyboardTop, width - dp(4f), keysTop)

    private fun drawToolbar(canvas: Canvas) {
        stroke.color = theme.toolbarIcon
        stroke.strokeWidth = dp(1.8f)
        // Settings: a cog.
        val g = settingsRect
        val cx = g.centerX(); val cy = g.centerY()
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeWidth = dp(3f)
        for (i in 0 until 8) {
            val a = Math.PI / 4 * i
            canvas.drawLine(
                cx + (dp(6f) * cos(a)).toFloat(), cy + (dp(6f) * sin(a)).toFloat(),
                cx + (dp(9f) * cos(a)).toFloat(), cy + (dp(9f) * sin(a)).toFloat(), stroke,
            )
        }
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = dp(1.8f)
        canvas.drawCircle(cx, cy, dp(6.2f), stroke)
        canvas.drawCircle(cx, cy, dp(2.4f), stroke)
        // Hide: a chevron down.
        val h = hideRect
        path.reset()
        path.moveTo(h.centerX() - dp(6f), h.centerY() - dp(3f))
        path.lineTo(h.centerX(), h.centerY() + dp(3f))
        path.lineTo(h.centerX() + dp(6f), h.centerY() - dp(3f))
        canvas.drawPath(path, stroke)
    }

    private fun drawKey(canvas: Canvas, key: Key, rect: RectF, pressed: Boolean) {
        val f0 = face(rect)
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
            val p = RectF(f.left, f.top + shrink, f.right, f.bottom - shrink)
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
            KeyType.CHAR -> {
                val text = shown(key)
                val letter = page == Page.LETTERS && text.length == 1 && text[0].isLetter()
                label.typeface = regular
                label.textSize = sp(if (letter) 23f else if (text.length > 2) 17f else 21f)
                label.color = theme.text
                fitText(text, f.width() - dp(4f))
                drawCentered(canvas, text, cx, cy)
                if (showHints && key.hint != null) {
                    label.textSize = sp(10.5f)
                    label.color = theme.hint
                    canvas.drawText(key.hint, f.right - dp(7f), f.top + dp(13f), label)
                }
            }
            KeyType.MODE -> {
                label.typeface = medium
                label.textSize = sp(15f)
                // On the developer page its { } key is lit: it is the page's way back.
                label.color = if (page == Page.DEV && key.label == "{ }") theme.accent else theme.text
                drawCentered(canvas, key.label, cx, cy)
            }
            KeyType.SPACE -> Unit
            KeyType.MIC -> drawMic(canvas, cx, cy, theme.text)
            KeyType.SHIFT -> drawShift(canvas, cx, cy)
            KeyType.DELETE -> drawDelete(canvas, cx, cy)
            KeyType.ENTER -> drawEnter(canvas, cx, cy)
        }
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

    private fun display(text: String) = if (page == Page.LETTERS && shift != ShiftState.OFF) text.uppercase() else text

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float) {
        val m = label.fontMetrics
        canvas.drawText(text, cx, cy - (m.ascent + m.descent) / 2, label)
    }

    /** The chosen icon pack (Look & layout › Icons): the keys' icons and the row's. */
    var iconPack = "lucide"
        set(value) { if (field != value) { field = value; invalidate() } }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** [icon] from the chosen pack, [size] across, centred on (cx, cy). */
    private fun drawPackIcon(canvas: Canvas, icon: RowIcon, cx: Float, cy: Float, size: Float, color: Int, filled: Boolean = false) {
        canvas.save()
        canvas.translate(cx - size / 2, cy - size / 2)
        IconDrawable.drawGlyph(canvas, RowIcons.icon(iconPack, icon), size, color, iconPaint, filled)
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

    /** The listening panel, over the keys: a ring that swells with the voice, and what to do. */
    private fun drawDictation(canvas: Canvas) {
        fill.color = theme.background
        canvas.drawRect(0f, keysTop, width.toFloat(), keysTop + keysHeight + bottomPad, fill)
        val cx = width / 2f
        val cy = keysTop + keysHeight * 0.42f
        val r = dp(34f)
        fill.color = theme.accent
        fill.alpha = 60
        canvas.drawCircle(cx, cy, r + dp(26f) * level.coerceIn(0f, 1f) + dp(4f), fill)
        fill.alpha = 255
        canvas.drawCircle(cx, cy, r, fill)
        drawMic(canvas, cx, cy + dp(1f), theme.onAccent, 1.6f)
        label.typeface = medium
        label.textSize = sp(15f)
        label.color = theme.text
        val words = if (dictation == DictationPhase.LISTENING) "Listening… tap to finish" else "Writing it down…"
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

    private class Panel(val choices: List<String>, val cells: List<RectF>, val bounds: RectF) {
        var selected = 0
    }

    private fun makePanel(key: Key, rect: RectF): Panel? {
        if (key.alts.isEmpty()) return null
        val choices = key.alts.map { display(it) }
        val f = face(rect)
        val cellW = f.width() + keyPadX * 2
        val cellH = f.height()
        val cols = min(choices.size, 8)
        val lines = ceil(choices.size / cols.toFloat()).toInt()
        val pad = dp(6f)
        val w = cols * cellW + pad * 2
        val h = lines * cellH + pad * 2
        // Centred on the key where it fits, the first choice nearest the finger.
        var left = f.centerX() - cellW / 2 - pad
        if (left + w > width - dp(2f)) left = width - dp(2f) - w
        if (left < dp(2f)) left = dp(2f)
        val bottom = f.top - dp(2f)
        val top = (bottom - h).coerceAtLeast(0f)
        val bounds = RectF(left, top, left + w, top + h)
        val cells = choices.indices.map { i ->
            // The first line is the one nearest the key, as Gboard fills its panels.
            val line = lines - 1 - i / cols
            val col = i % cols
            RectF(left + pad + col * cellW, top + pad + line * cellH, left + pad + (col + 1) * cellW, top + pad + (line + 1) * cellH)
        }
        return Panel(choices, cells, bounds)
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
                label.color = theme.text
            }
            val text = pretty(choice)
            label.textSize = sp(22f)
            fitText(text, cell.width() - dp(4f))
            drawCentered(canvas, text, cell.centerX(), cell.centerY())
        }
    }

    private fun selectInPanel(panel: Panel, x: Float, y: Float) {
        val cols = min(panel.choices.size, 8)
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
        if (index != panel.selected) { panel.selected = index; invalidate() }
    }

    // --- touch ------------------------------------------------------------------------------------------------------

    private class Touch(val pointerId: Int, var key: Key, var rect: RectF, val downX: Float, val downY: Float) {
        val firstKey = key
        var lastX = downX
        var lastY = downY
        var panel: Panel? = null
        var longPressed = false
        var cursorAnchor = downX
        var cursorMoved = false
        /** Swipe typing: the path so far, once the finger has slid far enough to be spelling a word. */
        var swipe: ArrayList<PointF>? = null
    }

    private var touch: Touch? = null
    private val timer = Handler(Looper.getMainLooper())

    private val longPress = Runnable { touch?.let { onLongPress(it) } }
    private val repeatDelete = object : Runnable {
        override fun run() {
            val t = touch ?: return
            if (t.key.type != KeyType.DELETE) return
            listener.onDelete()
            timer.postDelayed(this, 50)
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
        val (x, y) = if (page == Page.LETTERS) {
            val (ox, oy) = letterCorrection(rawX, rawY)
            (rawX - ox) to (rawY - oy).coerceAtLeast(keysTop + 1f)
        } else {
            val rowH = keysHeight / rows.size
            val fromBottom = rows.size - 1 - ((rawY - keysTop) / rowH).toInt().coerceIn(0, rows.size - 1)
            val lift = when (fromBottom) { 0, 1 -> 0f; 2 -> dp(LIFT_MIDDLE_DP); else -> dp(LIFT_TOP_DP) }
            (if (rawX < width / 2f) rawX + dp(SHIFT_LEFT_HALF_DP) else rawX) to (rawY - lift).coerceAtLeast(keysTop + 1f)
        }
        keyRects.firstOrNull { it.second.contains(x, y) }?.let { return it }
        // Below the last row or past its ends: the nearest key, so the edges never swallow a touch.
        return keyRects.minByOrNull { (_, r) ->
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            dx * dx + dy * dy
        }
    }

    /**
     * On the letters page, how far from where it is aimed a thumb lands at (x, y): a smooth blend of each key's own
     * measured offset ([LETTER_OFFSETS_DP]), weighted by how near its centre the touch is. The bottom row's keys
     * (?123, space, enter...) count as landing on target, which keeps the blend from leaking into them.
     */
    private fun letterCorrection(x: Float, y: Float): Pair<Float, Float> {
        val unitW = keyRects.firstOrNull { it.first.type == KeyType.CHAR }?.second?.width() ?: return 0f to 0f
        val sigma2 = 2f * (unitW * 0.6f) * (unitW * 0.6f)
        var sw = 0f; var sx = 0f; var sy = 0f
        val bottomTop = keyRects.last().second.top
        for ((key, r) in keyRects) {
            val off = LETTER_OFFSETS_DP[key.text]
            if (off == null && r.top < bottomTop) continue
            val dx = x - r.centerX(); val dy = y - r.centerY()
            val w = kotlin.math.exp(-(dx * dx + dy * dy) / sigma2)
            sw += w
            if (off != null) { sx += w * dp(off.first); sy += w * dp(off.second) }
        }
        return if (sw < 1e-6f) 0f to 0f else (sx / sw) to (sy / sw)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
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
                    when {
                        settingsRect.contains(x, y) -> { performHapticFeedback(KEYBOARD_TAP); listener.onOpenSettings() }
                        hideRect.contains(x, y) -> { performHapticFeedback(KEYBOARD_TAP); listener.onHideKeyboard() }
                    }
                    return true
                }
                if (dictation != DictationPhase.OFF) {
                    if (dictation == DictationPhase.LISTENING && event.actionMasked == MotionEvent.ACTION_DOWN) listener.onMic()
                    return true
                }
                val (key, rect) = keyAt(x, y) ?: return true
                press(Touch(event.getPointerId(i), key, rect, x, y))
            }
            MotionEvent.ACTION_MOVE -> {
                val t = touch ?: return true
                val i = event.findPointerIndex(t.pointerId)
                if (i < 0) return true
                // A swipe takes the points Android batched between frames too, so its trail follows the finger closely.
                t.swipe?.let { path -> for (h in 0 until event.historySize) addSwipePoint(path, event.getHistoricalX(i, h), event.getHistoricalY(i, h)) }
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
        when (t.key.type) {
            KeyType.DELETE -> {
                listener.onDelete()
                timer.postDelayed(repeatDelete, 400)
            }
            KeyType.CHAR, KeyType.SPACE, KeyType.SHIFT -> timer.postDelayed(longPress, longPressMs)
            KeyType.MODE -> if (t.key.label == "{ }") timer.postDelayed(longPress, longPressMs)
            else -> Unit
        }
        invalidate()
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
            }
            KeyType.CHAR -> {
                t.swipe?.let { addSwipePoint(it, x, y); invalidate(); return }
                // Swipe typing: a slide of most of a key's width from a letter starts spelling a word.
                if (swipeTyping && page == Page.LETTERS && t.panel == null && t.firstKey.text.length == 1 && t.firstKey.text[0].isLetter() &&
                    kotlin.math.hypot(x - t.downX, y - t.downY) > t.rect.width() * 0.9f
                ) {
                    t.swipe = arrayListOf(PointF(t.downX, t.downY), PointF(x, y))
                    timer.removeCallbacks(longPress)
                    invalidate()
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
                    invalidate()
                }
            }
            else -> Unit
        }
    }

    /** A point on a swipe's path, unless it is within a hair of the last (jitter that would kink the curve). */
    private fun addSwipePoint(path: ArrayList<PointF>, x: Float, y: Float) {
        val last = path.lastOrNull()
        if (last != null && kotlin.math.hypot(x - last.x, y - last.y) < dp(1.5f)) return
        path.add(PointF(x, y))
    }

    private fun onLongPress(t: Touch) {
        when (t.key.type) {
            KeyType.SPACE -> { t.longPressed = true; listener.onShowPicker() }
            // The { } key, where Gboard's comma was: long-press opens settings, as the comma's did.
            KeyType.MODE -> { t.longPressed = true; performHapticFeedback(LONG_PRESS); listener.onOpenSettings() }
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
                invalidate()
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
                listener.onSwipe(swipe, letterCenters(), unitW)
                fadingSwipe = swipe
                fadingSince = android.os.SystemClock.uptimeMillis()
                if (shift == ShiftState.ON) { shift = ShiftState.OFF; autoShifted = false; listener.onShiftChanged() }
            }
            panel != null -> commit(panel.choices[panel.selected])
            t.longPressed -> Unit
            else -> when (t.key.type) {
                KeyType.CHAR -> {
                    if (tapLog && page == Page.LETTERS) logTap(t)
                    commit(display(t.key.text))
                }
                KeyType.SPACE -> if (!t.cursorMoved) listener.onSpace()
                KeyType.ENTER -> listener.onEnter()
                KeyType.SHIFT -> tapShift()
                KeyType.MODE -> showPage(t.key.page ?: Page.LETTERS)
                KeyType.MIC -> listener.onMic()
                KeyType.DELETE -> Unit
            }
        }
        invalidate()
    }

    private fun logTap(t: Touch) {
        val d = resources.displayMetrics.density
        fun px(v: Float) = "%.1f".format(v / d)
        fun py(v: Float) = "%.1f".format((v - keysTop) / d)
        val face = face(t.rect)
        val dx = (t.downX - face.centerX()) / t.rect.width()
        val dy = (t.downY - face.centerY()) / t.rect.height()
        TapLog.add(
            context,
            "${t.key.text}  down=${px(t.downX)},${py(t.downY)}  up=${px(t.lastX)},${py(t.lastY)}  " +
                "key=${t.key.text}@${px(face.centerX())},${py(face.centerY())}  d=${"%+.2f".format(dx)},${"%+.2f".format(dy)}  " +
                "slid=${if (t.key !== t.firstKey) "yes" else "-"}  first=${t.firstKey.text.ifEmpty { t.firstKey.label }}",
        )
    }

    private fun cancelTouch() {
        timer.removeCallbacks(longPress)
        timer.removeCallbacks(repeatDelete)
        touch = null
        invalidate()
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
        /** Wider than this (dp), the keys split: an unfolded foldable's inner screen is 670 or more, a phone 430 or less. */
        const val SPLIT_MIN_DP = 600f
        /** The open middle of a split keyboard, as a share of its width. */
        const val SPLIT_GAP = 0.3f
        /**
         * Where Matt's thumb lands relative to each letter's centre, in dp (+x right, +y down): the mean of ~325
         * logged taps over 0.1.3 and 0.1.5, each key pulled toward its row-half's mean where it had few taps
         * (6 taps' weight), capped at 14dp across and 20dp down.
         */
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
        private const val KEYBOARD_TAP = android.view.HapticFeedbackConstants.KEYBOARD_TAP
        private const val LONG_PRESS = android.view.HapticFeedbackConstants.LONG_PRESS
    }
}
