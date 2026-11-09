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
enum class RowIcon { SETTINGS, HIDE, GIF, BACK, FORWARD, SEARCH, KEY, MIC, DELETE, SHIFT, ENTER }

/**
 * One thing a plugin puts in the row above the keys.
 *
 * - [label]: text, a suggestion or a chip's name. [bold] is the pick (Gboard's middle suggestion).
 * - [icon]: a drawn icon instead of, or before, the label.
 * - [view]: a view of the plugin's own, shown as it is (a password manager's inline suggestion).
 * - [wide]: a suggestion: shares the row's width with the others, a third each, as Gboard's do.
 * - [fill]: takes all the width the others leave (a search bar).
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
    val onTap: (() -> Unit)? = null,
)

/**
 * The plugin row: a strip above the keys that plugins fill, scrolling sideways when they offer more than fits.
 * Autocorrect's suggestions, swipe typing's alternatives, a password manager's logins, the GIF key... each plugin
 * hands the service its items and the service puts them here in order (DevKeyboardService.refreshRow). A settings
 * button stays at its start, and a hide button at its end when the old toolbar setting is on.
 */
@SuppressLint("ViewConstructor")
class PluginRow(context: Context, private val onSettings: () -> Unit, private val onHide: () -> Unit) : LinearLayout(context) {
    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(value) { field = value; restyle() }

    var showHide = false
        set(value) { field = value; hide.visibility = if (value) VISIBLE else GONE }

    private val settings = IconButton(context, RowIcon.SETTINGS).apply { setOnClickListener { onSettings() } }
    private val hide = IconButton(context, RowIcon.HIDE).apply { setOnClickListener { onHide() }; visibility = GONE }
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
        addView(hide, LayoutParams(dp(44f), LayoutParams.MATCH_PARENT))
        restyle()
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    fun setItems(next: List<RowItem>) {
        // The same words again (a refresh with nothing new) keeps the row as it is, scroll and all.
        if (next.map { it.id to it.label?.toString() to it.bold to it.view } == items.map { it.id to it.label?.toString() to it.bold to it.view }) return
        items = next
        strip.removeAllViews()
        val wideCount = next.count { it.wide }.coerceAtLeast(3)
        val wideWidth = ((scroll.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dp(44f))) / wideCount)
        for (item in next) {
            val v = item.view?.also { (it.parent as? ViewGroup)?.removeView(it) } ?: makeView(item)
            val lp = if (item.fill) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f) else LinearLayout.LayoutParams(
                if (item.wide) wideWidth else ViewGroup.LayoutParams.WRAP_CONTENT,
                if (item.view != null) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            )
            lp.gravity = Gravity.CENTER_VERTICAL
            if (item.chip || item.view != null) { lp.leftMargin = dp(4f); lp.rightMargin = dp(4f); lp.topMargin = dp(6f); lp.bottomMargin = dp(6f) }
            strip.addView(v, lp)
        }
        scroll.scrollTo(0, 0)
    }

    private fun makeView(item: RowItem): View {
        if (item.icon != null && item.label == null) {
            return IconButton(context, item.icon).apply {
                color = theme.toolbarIcon
                minimumWidth = dp(44f)
                setOnClickListener { tap(item) }
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
            background = pressable(item.chip)
            isClickable = item.onTap != null
            setOnClickListener { tap(item) }
            if (item.icon != null) {
                setCompoundDrawablesRelative(IconDrawable(item.icon, theme.toolbarIcon, dp(18f)), null, null, null)
                compoundDrawablePadding = dp(6f)
            }
        }
    }

    private fun tap(item: RowItem) {
        val action = item.onTap ?: return
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        try { action() } catch (t: Throwable) { android.util.Log.e("GhostKeyboard", "row item ${item.id} failed", t) }
    }

    private fun pressable(chip: Boolean): StateListDrawable {
        fun shape(color: Int) = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(color) }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(theme.keyPressed))
            addState(intArrayOf(), shape(if (chip) theme.functionKey else 0))
        }
    }

    private fun restyle() {
        setBackgroundColor(theme.background)
        settings.color = theme.toolbarIcon
        hide.color = theme.toolbarIcon
        val now = items
        items = emptyList()
        setItems(now)
    }

    /** A drawn icon button: no images to ship, and it takes the theme's colour. */
    private class IconButton(context: Context, val icon: RowIcon) : View(context) {
        var color = 0xFF444746.toInt()
            set(value) { field = value; invalidate() }
        private val drawer = IconDrawable(icon, color, 0)

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
    init {
        clipChildren = false
        addView(row)
        addView(keyboard)
        addView(panel)
        panel.visibility = GONE
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        keyboard.measure(widthMeasureSpec, heightMeasureSpec)
        val w = keyboard.measuredWidth
        val h = keyboard.measuredHeight
        row.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowHeight(), MeasureSpec.EXACTLY))
        panel.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((keyboard.keysBottom - keyboard.rowBottom).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY))
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
    }

    // Nothing here consumes touches itself: the children decide.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = false
}
