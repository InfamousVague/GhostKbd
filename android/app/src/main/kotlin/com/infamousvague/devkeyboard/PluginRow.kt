package com.infamousvague.devkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/** The small drawn icons the plugin row's buttons use. */
enum class RowIcon {
    SETTINGS, HIDE, GIF, BACK, FORWARD, SEARCH, KEY, MIC, DELETE, SHIFT, ENTER, CARET_LEFT, CARET_RIGHT, CARET_UP, CARET_DOWN, OFFLINE,
    // The tools' and states' own (RowIconsMore.kt).
    CLIPBOARD, EMOJI, GIF_TEXT, SPARKLE, WAND, FEATHER, GHOST, INCOGNITO, UNDO, REDO, BOLD, ITALIC, STRIKE, CODE, CODE_BLOCK,
    LINK, HEADING, LIST, TASK, QUOTE, WIKI, HASH, TODAY, CALLOUT, TABLE, MATH, DIAGRAM, DIVIDER, TRASH, CLOSE, WARNING,
    RETRY, CHECK, NOTE,
}

/**
 * One thing a plugin puts in the row above the keys.
 *
 * - [label]: text, a suggestion or a chip's name. [bold] is the pick (Gboard's middle suggestion).
 * - [icon]: a drawn icon instead of, or before, the label.
 * - [view]: a view of the plugin's own, shown as it is (a password manager's inline suggestion).
 * - [wide]: a suggestion: shares the row's width with the others, a third each, as Gboard's do.
 * - [fill]: takes all the width the others leave (a search bar).
 * - [tint]: the chip's colour (RowTint): its icon in the hue, on a faint ground of it.
 * - [busy]: the AI is working on it: drawn as a ThinkingView, a sparkle and the words with a light passing over them.
 * - [description]: what a reader says for an icon with no words (TalkBack).
 * - [dim]: drawn faint: something that cannot be used as things stand (a network chip while Offline is on, which
 *   still says why when tapped; Undo with nothing to undo, which has no [onTap] and so takes no tap).
 */
class RowItem(
    val id: String,
    val label: CharSequence? = null,
    val icon: RowIcon? = null,
    val bold: Boolean = false,
    val wide: Boolean = false,
    val chip: Boolean = false,
    val view: View? = null,
    val fill: Boolean = false,
    val tint: RowTint? = null,
    val busy: Boolean = false,
    val description: String? = null,
    val dim: Boolean = false,
    val onTap: (() -> Unit)? = null,
)

/**
 * The plugin row: a strip above the keys that plugins fill, scrolling sideways when they offer more than fits.
 * Autocorrect's suggestions, swipe typing's alternatives, a password manager's logins, the GIF key... each plugin
 * hands the service its items and the service puts them here in order (DevKeyboardService.refreshRow). A settings
 * button stays at its start, and a hide button at its end when the old toolbar setting is on.
 */
@SuppressLint("ViewConstructor")
class PluginRow(context: Context, private val onSettings: () -> Unit, private val onHide: () -> Unit, private val onArrow: (Int) -> Unit = {}) : LinearLayout(context) {
    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(value) { field = value; restyle() }

    var showHide = false
        set(value) { field = value; hide.visibility = if (value) VISIBLE else GONE }

    private val settings = IconButton(context, RowIcon.SETTINGS).apply { setOnClickListener { onSettings() } }

    /** A dot on the cog, in the theme's accent, while an update is waiting. */
    var updateBadge = false
        set(value) { field = value; settings.badge = value }

    /** A small quiet mark on the cog while Offline is on (Settings › Offline), where the update dot would be. */
    var offline = false
        set(value) { field = value; settings.offlineMark = value }
    private val hide = IconButton(context, RowIcon.HIDE).apply { setOnClickListener { onHide() }; visibility = GONE }

    /**
     * Arrow keys (Settings › Row tools › Arrow keys), SwiftKey's way: ← ↑ ↓ → pinned at the row's end, there whatever
     * the row is showing, each moving the cursor and repeating while held.
     */
    var showArrows = false
        set(value) { field = value; arrows.visibility = if (value) VISIBLE else GONE }
    private val arrowButtons = listOf(
        RowIcon.CARET_LEFT to android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        RowIcon.CARET_UP to android.view.KeyEvent.KEYCODE_DPAD_UP,
        RowIcon.CARET_DOWN to android.view.KeyEvent.KEYCODE_DPAD_DOWN,
        RowIcon.CARET_RIGHT to android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
    ).map { (icon, code) -> IconButton(context, icon).also { repeating(it) { onArrow(code) } } }
    private val arrows = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        visibility = GONE
        for (b in arrowButtons) addView(b, LayoutParams(dp(36f), LayoutParams.MATCH_PARENT))
    }

    /** [action] on touch, then again and again while held: 400 ms before the first repeat, then every 50 ms. */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeating(view: View, action: () -> Unit) {
        val again = object : Runnable { override fun run() { action(); view.postDelayed(this, 50) } }
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { v.isPressed = true; action(); v.postDelayed(again, 400) }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> { v.isPressed = false; v.removeCallbacks(again) }
            }
            true
        }
    }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        isFillViewport = true
    }
    private val strip = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var items: List<RowItem> = emptyList()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        scroll.addView(strip, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(settings, LayoutParams(dp(44f), LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(arrows, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        addView(hide, LayoutParams(dp(44f), LayoutParams.MATCH_PARENT))
        restyle()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    fun setItems(next: List<RowItem>) {
        // The same words again (a refresh with nothing new) keeps the row as it is, scroll and all.
        if (next.map { listOf(it.id, it.label?.toString(), it.bold, it.view, it.tint, it.busy, it.dim) } == items.map { listOf(it.id, it.label?.toString(), it.bold, it.view, it.tint, it.busy, it.dim) }) return
        // The same buttons with one lit or dimmed (Undo, after a tap on it) stay where they were scrolled to: the
        // arrows must not slide out from under the thumb that is tapping them.
        val keepScroll = if (next.map { it.id } == items.map { it.id }) scroll.scrollX else 0
        items = next
        strip.removeAllViews()
        val wideCount = next.count { it.wide }.coerceAtLeast(3)
        val room = scroll.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(44f))
        // Too narrow to read (the arrow keys take part of the row): three across, and the rest scroll.
        // Next-word prediction's three words are followed by the tools: they give up a little width, so the first
        // tool peeks in at the row's end and says there is more to scroll to.
        val peek = if (next.any { it.wide } && next.any { it.chip }) dp(36f) else 0
        val wideWidth = if (room / wideCount < dp(80f)) (room - peek) / 3 else (room - peek) / wideCount
        for (item in next) {
            val v = item.view?.also { (it.parent as? ViewGroup)?.removeView(it) } ?: makeView(item)
            val lp = if (item.fill) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f) else LinearLayout.LayoutParams(
                if (item.wide) wideWidth else ViewGroup.LayoutParams.WRAP_CONTENT,
                if (item.view != null) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            )
            lp.gravity = Gravity.CENTER_VERTICAL
            if (item.chip || item.view != null || item.busy) { lp.leftMargin = dp(4f); lp.rightMargin = dp(4f); lp.topMargin = dp(6f); lp.bottomMargin = dp(6f) }
            strip.addView(v, lp)
        }
        // A filling item (a search bar, the AI's words, an error) shares the row with the buttons beside it and is cut
        // short to leave them room: a long error no longer pushes Retry and the close button off the end.
        if (next.any { it.fill }) {
            var others = 0
            for (i in 0 until strip.childCount) {
                if (next.getOrNull(i)?.fill == true) continue
                val c = strip.getChildAt(i)
                val lp = c.layoutParams as LinearLayout.LayoutParams
                if (lp.width > 0) others += lp.width else {
                    c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(dp(36f), MeasureSpec.EXACTLY))
                    others += c.measuredWidth
                }
                others += lp.leftMargin + lp.rightMargin
            }
            val fills = next.count { it.fill }
            val cap = ((room - others) / fills - dp(8f)).coerceAtLeast(room / 3)
            for (i in 0 until strip.childCount) if (next.getOrNull(i)?.fill == true) when (val c = strip.getChildAt(i)) {
                is TextView -> c.maxWidth = cap
                is ThinkingView -> c.maxWidth = cap
            }
        }
        scroll.scrollTo(keepScroll, 0)
    }

    private fun makeView(item: RowItem): View {
        if (item.busy) {
            return ThinkingView(context, item.label ?: "Thinking…", theme).apply {
                isClickable = item.onTap != null
                setOnClickListener { tap(item) }
            }
        }
        if (item.icon != null && item.label == null) {
            return IconButton(context, item.icon).apply {
                color = item.tint?.ink(theme) ?: theme.toolbarIcon
                minimumWidth = dp(44f)
                contentDescription = item.description
                // A tool's chip: the icon alone on a pill, in its colour where it has one.
                if (item.chip) background = pressable(true, item.tint)
                setOnClickListener { tap(item) }
                if (item.dim) alpha = DIM
                isClickable = item.onTap != null
            }
        }
        return TextView(context).apply {
            text = item.label
            gravity = if (item.fill) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (item.wide) 16f else 14f)
            typeface = Typeface.create(if (item.bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            setTextColor(theme.text)
            val padX = if (item.wide) dp(6f) else dp(12f)
            setPadding(padX, 0, padX, 0)
            background = pressable(item.chip, item.tint)
            contentDescription = item.description
            setOnClickListener { tap(item) }
            isClickable = item.onTap != null
            if (item.dim) alpha = DIM
            if (item.icon != null) {
                setCompoundDrawablesRelative(IconDrawable(item.icon, item.tint?.ink(theme) ?: theme.toolbarIcon, dp(18f)), null, null, null)
                compoundDrawablePadding = dp(6f)
            }
        }
    }

    private fun tap(item: RowItem) {
        val action = item.onTap ?: return
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        try { action() } catch (t: Throwable) { android.util.Log.e("GhostKeyboard", "row item ${item.id} failed", t) }
    }

    private fun pressable(chip: Boolean, tint: RowTint? = null): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(color) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(theme.keyPressed))
            addState(intArrayOf(), shape(if (!chip) 0 else tint?.wash(theme) ?: theme.functionKey))
        }
    }

    private companion object {
        /** How faint a [RowItem.dim] item is drawn. */
        const val DIM = 0.45f
    }

    private fun restyle() {
        setBackgroundColor(theme.background)
        settings.color = theme.toolbarIcon
        settings.badgeColor = theme.accent
        settings.badgeRing = theme.background
        hide.color = theme.toolbarIcon
        arrowButtons.forEach { it.color = theme.toolbarIcon }
        val now = items
        items = emptyList()
        setItems(now)
    }

    /** A drawn icon button: no images to ship, and it takes the theme's colour. */
    private class IconButton(context: Context, val icon: RowIcon) : View(context) {
        var color = 0xFF444746.toInt()
            set(value) { field = value; invalidate() }
        var badge = false
            set(value) { field = value; invalidate() }
        var badgeColor = 0xFF008B8A.toInt()
        var badgeRing = 0
        /** Offline is on: a small struck-through ring at the glyph's corner, in the icon's own colour. */
        var offlineMark = false
            set(value) { field = value; invalidate() }
        private val drawer = IconDrawable(icon, color, 0)
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
            val bg = StateListDrawable()
            bg.addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x22808080) })
            background = bg
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 44f, resources.displayMetrics).toInt()
            setMeasuredDimension(resolveSize(size, widthMeasureSpec), resolveSize(size, heightMeasureSpec))
        }

        override fun onDraw(canvas: Canvas) {
            val s = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics)
            drawer.ink = color
            drawer.setBounds(((width - s) / 2).toInt(), ((height - s) / 2).toInt(), ((width + s) / 2).toInt(), ((height + s) / 2).toInt())
            drawer.draw(canvas)
            if (badge) {
                // Top right of the glyph, ringed in the row's ground so it reads apart from the cog's teeth.
                val r = s * 0.2f
                val cx = (width + s) / 2 - r * 0.6f
                val cy = (height - s) / 2 + r * 0.6f
                dot.color = badgeRing
                canvas.drawCircle(cx, cy, r + s * 0.08f, dot)
                dot.color = badgeColor
                canvas.drawCircle(cx, cy, r, dot)
            } else if (offlineMark) {
                // Where the update dot sits, but quiet: the row's ground, then a ring with a stroke through it.
                val r = s * 0.2f
                val cx = (width + s) / 2 - r * 0.6f
                val cy = (height - s) / 2 + r * 0.6f
                dot.style = Paint.Style.FILL
                dot.color = badgeRing
                canvas.drawCircle(cx, cy, r + s * 0.08f, dot)
                dot.style = Paint.Style.STROKE
                dot.strokeWidth = s * 0.075f
                dot.strokeCap = Paint.Cap.ROUND
                dot.color = color
                canvas.drawCircle(cx, cy, r * 0.8f, dot)
                val d = r * 0.8f * 0.7071f
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, dot)
                dot.style = Paint.Style.FILL
            }
        }
    }
}

/**
 * The row's icons, from the chosen icon pack (RowIcons.kt, Ghost.md's packs): drawn on the 24 grid at whatever size
 * the bounds give, in Ghost.md's look, outlines with a wash inside: the icon's body filled at a third of the ink.
 */
class IconDrawable(private val icon: RowIcon, var ink: Int, size: Int) : android.graphics.drawable.Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    init { if (size > 0) setBounds(0, 0, size, size) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        drawGlyph(canvas, RowIcons.icon(icon), b.width().toFloat(), ink, paint)
        canvas.restore()
    }

    companion object {
        /**
         * [glyph] in a [size]-pixel square at the canvas' origin, in [ink]: the wash under it (at its own share of the
         * ink, or all of it with [filled]), then its lines stroked or its shapes filled.
         */
        fun drawGlyph(canvas: Canvas, glyph: PackIcon, size: Float, ink: Int, paint: Paint, filled: Boolean = false) {
            canvas.save()
            if (glyph.rotate != 0f) canvas.rotate(glyph.rotate, size / 2f, size / 2f)
            val s = size / glyph.box
            canvas.scale(s, s)
            canvas.translate(-glyph.ox, -glyph.oy)
            val alpha = android.graphics.Color.alpha(ink)
            paint.color = ink
            paint.style = Paint.Style.FILL
            paint.alpha = if (filled) alpha else (glyph.washAlpha * alpha).toInt()
            for (d in glyph.wash) canvas.drawPath(SvgPath.parse(d), paint)
            paint.alpha = alpha
            if (glyph.lines) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f * glyph.box / 24f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
            }
            for (d in glyph.paths) canvas.drawPath(SvgPath.parse(d), paint)
            paint.style = Paint.Style.FILL
            canvas.restore()
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * The input view: the drawn keyboard, with the plugin row placed in the strip it leaves between its preview room and
 * its keys, and a plugin panel (GIFs) over the keys when one is open. The keyboard is on top so key previews draw
 * over the row; it hands touches in the row's strip down to the row.
 */
@SuppressLint("ViewConstructor")
class KeyboardFrame(context: Context, val keyboard: KeyboardView, val row: PluginRow, val panel: FrameLayout) : FrameLayout(context) {
    /** The line along the top of the keys that runs while something works (RowStyle.kt). */
    val working = WorkingLine(context)

    init {
        clipChildren = false
        addView(row)
        addView(keyboard)
        addView(panel)
        addView(working)
        panel.visibility = GONE
    }

    private val lineHeight get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, resources.displayMetrics).toInt().coerceAtLeast(2)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        keyboard.measure(widthMeasureSpec, heightMeasureSpec)
        val w = keyboard.measuredWidth
        val h = keyboard.measuredHeight
        row.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowHeight(), MeasureSpec.EXACTLY))
        panel.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((keyboard.keysBottom - keyboard.rowBottom).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY))
        working.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lineHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }

    /**
     * The row's pixels: from the pixel its strip starts in to one past the pixel the keys start in. The strip's edges
     * fall between pixels, and rounding both down left a hairline of the app showing between the row and the keys;
     * the overlap is under the keyboard's own background, which is drawn over the row.
     */
    private fun rowHeight() = (kotlin.math.ceil(keyboard.rowBottom) - kotlin.math.floor(keyboard.rowTop)).toInt().coerceAtLeast(0) + 1

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
        val top = kotlin.math.floor(keyboard.rowTop).toInt()
        row.layout(0, top, row.measuredWidth, top + row.measuredHeight)
        panel.layout(0, keyboard.rowBottom.toInt(), panel.measuredWidth, keyboard.rowBottom.toInt() + panel.measuredHeight)
        // Along the row's lower edge, where the keys begin.
        val line = keyboard.rowBottom.toInt()
        working.layout(0, line - working.measuredHeight, working.measuredWidth, line)
    }

    // Nothing here consumes touches itself: the children decide.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false
}
