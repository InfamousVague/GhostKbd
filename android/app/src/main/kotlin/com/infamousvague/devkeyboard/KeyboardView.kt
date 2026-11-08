package com.infamousvague.devkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
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
    fun onText(text: String)
    fun onDelete()
    fun onSpace()
    fun onEnter()
    fun onCursor(steps: Int)
    fun onShiftChanged()
    fun onKeyFeedback(key: Key)
    fun onShowPicker()
    fun onOpenSettings()
    fun onHideKeyboard()
}

enum class ShiftState { OFF, ON, LOCKED }

enum class EnterIcon { RETURN, SEARCH, GO, SEND, NEXT, PREVIOUS, DONE }

/**
 * The whole keyboard as one drawn view, Gboard's layout and look: a toolbar strip, then the keys. The key preview
 * and the long-press panel are drawn in the same view, over the toolbar when the top row needs the room, so there
 * are no popup windows to manage.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: KeyboardListener) : View(context) {

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

    /** Settings › Developer › Tap log, and not in a password field (the service decides both). */
    var tapLog = false
    private var numberRow = false
    private var showToolbar = false
    private var rowHeightDp = 58f

    fun configure(numberRow: Boolean, keyHeight: String, toolbar: Boolean) {
        // Gboard's default row is about 58dp from one row's keys to the next's.
        val height = when (keyHeight) { "short" -> 50f; "tall" -> 66f; else -> 58f }
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
        rows = when (page) {
            Page.LETTERS -> Layouts.letters(numberRow)
            Page.SYMBOLS -> Layouts.symbols()
            Page.MORE -> Layouts.more()
        }
        layoutKeys()
    }

    // --- metrics ----------------------------------------------------------------------------------------------------

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    /** Room above the keyboard for the key preview and long-press panels, see-through and not touchable there. */
    private val previewSpace get() = dp(rowHeightDp * 1.3f)
    /** Where the keyboard itself starts, below [previewSpace]: the service tells Android only this part is the IME. */
    val keyboardTop get() = previewSpace
    private val toolbarHeight get() = if (showToolbar) dp(44f) else dp(6f)
    private val keysTop get() = keyboardTop + toolbarHeight
    private val sidePad get() = dp(3f)
    private val bottomPad get() = dp(6f)
    private val keyPadX get() = dp(3f)
    private val keyPadY get() = dp(4f)
    private val keyRadius get() = dp(8f)
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
        val unit = (width - sidePad * 2) / units
        val rowH = keysHeight / rows.size
        var y = keysTop
        for (row in rows) {
            var x = sidePad + row.inset * unit + (units - row.units) * unit / 2
            for (key in row.keys) {
                val w = key.width * unit
                keyRects += key to RectF(x, y, x + w, y + rowH)
                x += w
            }
            y += rowH
        }
        if (tapLog) {
            val d = resources.displayMetrics.density
            TapLog.geometry(context, "w=${"%.1f".format(width / d)}dp rowH=${"%.1f".format(rowH / d)}dp lift=$TOUCH_LIFT density=${"%.2f".format(d)} page=$page numberRow=$numberRow")
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
        canvas.drawRect(0f, keyboardTop, width.toFloat(), height.toFloat(), fill)
        if (showToolbar) drawToolbar(canvas)
        for ((key, rect) in keyRects) drawKey(canvas, key, rect, pressed = touch?.let { it.key === key && it.panel == null } == true)
        touch?.let { t ->
            when {
                t.panel != null -> drawPanel(canvas, t.panel!!)
                showPreview && t.key.type == KeyType.CHAR && !t.cursorMoved -> drawPreview(canvas, t.key, t.rect)
            }
        }
    }

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
        val f = face(rect)
        if (keyBorders) {
            fill.color = when {
                key.type == KeyType.ENTER -> if (pressed) theme.accentPressed else theme.accent
                key.type == KeyType.SHIFT && shift != ShiftState.OFF -> if (pressed) theme.keyPressed else theme.key
                key.functional -> if (pressed) theme.functionKeyPressed else theme.functionKey
                else -> if (pressed) theme.keyPressed else theme.key
            }
            canvas.drawRoundRect(f, keyRadius, keyRadius, fill)
        } else {
            // Gboard: ?123, space and enter are pills; every other key is only its label, with a wash while pressed.
            val pill = f.height() / 2
            when (key.type) {
                KeyType.ENTER -> { fill.color = if (pressed) theme.accentPressed else theme.accent; canvas.drawRoundRect(f, pill, pill, fill) }
                KeyType.SPACE, KeyType.MODE -> { fill.color = if (pressed) theme.keyPressed else theme.key; canvas.drawRoundRect(f, pill, pill, fill) }
                else -> if (pressed) { fill.color = theme.key; canvas.drawRoundRect(f, keyRadius, keyRadius, fill) }
            }
        }
        val cx = f.centerX(); val cy = f.centerY()
        when (key.type) {
            KeyType.CHAR -> {
                val text = display(key.text)
                val letter = page == Page.LETTERS && text.length == 1 && text[0].isLetter()
                label.typeface = regular
                label.textSize = sp(if (letter) 23f else 21f)
                label.color = theme.text
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
                label.color = theme.text
                drawCentered(canvas, key.label, cx, cy)
            }
            KeyType.SPACE -> Unit
            KeyType.SHIFT -> drawShift(canvas, cx, cy)
            KeyType.DELETE -> drawDelete(canvas, cx, cy)
            KeyType.ENTER -> drawEnter(canvas, cx, cy)
        }
    }

    private fun display(text: String) = if (page == Page.LETTERS && shift != ShiftState.OFF) text.uppercase() else text

    private fun drawCentered(canvas: Canvas, text: String, cx: Float, cy: Float) {
        val m = label.fontMetrics
        canvas.drawText(text, cx, cy - (m.ascent + m.descent) / 2, label)
    }

    private fun drawShift(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(1f)
        path.reset()
        path.moveTo(cx, cy - 9 * u)
        path.lineTo(cx + 9 * u, cy + 1 * u)
        path.lineTo(cx + 4 * u, cy + 1 * u)
        path.lineTo(cx + 4 * u, cy + 7 * u)
        path.lineTo(cx - 4 * u, cy + 7 * u)
        path.lineTo(cx - 4 * u, cy + 1 * u)
        path.lineTo(cx - 9 * u, cy + 1 * u)
        path.close()
        stroke.color = theme.text
        stroke.strokeWidth = dp(1.8f)
        if (shift == ShiftState.OFF) {
            canvas.drawPath(path, stroke)
        } else {
            fill.color = theme.text
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
        if (shift == ShiftState.LOCKED) {
            stroke.strokeWidth = dp(2f)
            canvas.drawLine(cx - 4 * u, cy + 11 * u, cx + 4 * u, cy + 11 * u, stroke)
        }
    }

    private fun drawDelete(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(1f)
        val x = cx + 1 * u
        path.reset()
        path.moveTo(x - 11 * u, cy)
        path.lineTo(x - 5 * u, cy - 7 * u)
        path.lineTo(x + 9 * u, cy - 7 * u)
        path.lineTo(x + 9 * u, cy + 7 * u)
        path.lineTo(x - 5 * u, cy + 7 * u)
        path.close()
        stroke.color = theme.text
        stroke.strokeWidth = dp(1.8f)
        canvas.drawPath(path, stroke)
        canvas.drawLine(x - 1 * u, cy - 3 * u, x + 5 * u, cy + 3 * u, stroke)
        canvas.drawLine(x + 5 * u, cy - 3 * u, x - 1 * u, cy + 3 * u, stroke)
    }

    private fun drawEnter(canvas: Canvas, cx: Float, cy: Float) {
        val u = dp(1f)
        stroke.color = theme.onAccent
        stroke.strokeWidth = dp(2f)
        path.reset()
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
        drawCentered(canvas, display(key.text), bubble.centerX(), bubble.centerY())
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
            drawCentered(canvas, choice, cell.centerX(), cell.centerY())
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
     * The key a touch at (x, y) means. A thumb's contact lands below the point it aims at, so the touch is read a
     * little higher, as Gboard and AOSP's keyboard do (their "touch position correction").
     */
    private fun keyAt(x: Float, rawY: Float): Pair<Key, RectF>? {
        if (rawY < keysTop) return null
        val rowH = keysHeight / rows.size
        val y = (rawY - rowH * TOUCH_LIFT).coerceAtLeast(keysTop + 1f)
        keyRects.firstOrNull { it.second.contains(x, y) }?.let { return it }
        // Below the last row or past its ends: the nearest key, so the edges never swallow a touch.
        return keyRects.minByOrNull { (_, r) ->
            val dx = if (x < r.left) r.left - x else if (x > r.right) x - r.right else 0f
            val dy = if (y < r.top) r.top - y else if (y > r.bottom) y - r.bottom else 0f
            dx * dx + dy * dy
        }
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
                    if (!showToolbar || y < keyboardTop) return true
                    when {
                        settingsRect.contains(x, y) -> { performHapticFeedback(KEYBOARD_TAP); listener.onOpenSettings() }
                        hideRect.contains(x, y) -> { performHapticFeedback(KEYBOARD_TAP); listener.onHideKeyboard() }
                    }
                    return true
                }
                val (key, rect) = keyAt(x, y) ?: return true
                press(Touch(event.getPointerId(i), key, rect, x, y))
            }
            MotionEvent.ACTION_MOVE -> {
                val t = touch ?: return true
                val i = event.findPointerIndex(t.pointerId)
                if (i < 0) return true
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

    private fun onLongPress(t: Touch) {
        when (t.key.type) {
            KeyType.SPACE -> { t.longPressed = true; listener.onShowPicker() }
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
        when {
            panel != null -> commit(panel.choices[panel.selected])
            t.longPressed -> Unit
            else -> when (t.key.type) {
                KeyType.CHAR -> {
                    if (tapLog) logTap(t)
                    commit(display(t.key.text))
                }
                KeyType.SPACE -> if (!t.cursorMoved) listener.onSpace()
                KeyType.ENTER -> listener.onEnter()
                KeyType.SHIFT -> tapShift()
                KeyType.MODE -> showPage(t.key.page ?: Page.LETTERS)
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

    private fun commit(text: String) {
        if (shift == ShiftState.ON) { shift = ShiftState.OFF; autoShifted = false }
        listener.onText(text)
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
        /** How far up a touch is read, as a share of a row: about where a thumb's aim sits above its contact. */
        private const val TOUCH_LIFT = 0.12f
        private const val KEYBOARD_TAP = android.view.HapticFeedbackConstants.KEYBOARD_TAP
        private const val LONG_PRESS = android.view.HapticFeedbackConstants.LONG_PRESS
    }
}
