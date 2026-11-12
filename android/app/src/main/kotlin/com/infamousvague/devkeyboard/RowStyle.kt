package com.infamousvague.devkeyboard

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.View

/**
 * The plugin row's colours (Matt, 2026-11-09: "add colors and more iconography"). One hue for each kind of thing, so a
 * glance says what a chip is: the AI's violet, the clipboard's amber, emoji's yellow, GIFs' pink, Ghost.md's teal, a
 * result's green, a failure's red, and a one-time code's blue (CodeChip.kt; 2026-11-11: "a special chip not just the
 * normal clipboard"). Each has a shade for light keyboards and one for dark, picked from the theme.
 */
enum class RowTint(private val light: Int, private val dark: Int) {
    AI(0xFF6A4FD8.toInt(), 0xFFB9A6FF.toInt()),
    CLIPBOARD(0xFFB45F06.toInt(), 0xFFFFB866.toInt()),
    EMOJI(0xFFB98900.toInt(), 0xFFFFD866.toInt()),
    GIFS(0xFFC2185B.toInt(), 0xFFF790B8.toInt()),
    GHOST(0xFF00897B.toInt(), 0xFF5ED6C8.toInt()),
    DONE(0xFF2E7D32.toInt(), 0xFF8BD18F.toInt()),
    CODE(0xFF1565C0.toInt(), 0xFF8AB4F8.toInt()),
    ERROR(0xFFC62828.toInt(), 0xFFFF9C9C.toInt());

    fun ink(theme: KeyboardTheme) = if (theme.dark) dark else light

    /** The chip's ground: the hue, faint, over the row. */
    fun wash(theme: KeyboardTheme) = (ink(theme) and 0x00FFFFFF) or ((if (theme.dark) 0x2E else 0x1F) shl 24)
}

/** Whether the person has animations on (Settings › Accessibility › Remove animations turns them off). */
internal fun animationsOn() = ValueAnimator.areAnimatorsEnabled()

/**
 * How the quill moves while the AI writes ([ThinkingView]): one line of its scribble takes [CYCLE_MS]. For the first
 * [WRITE] of that the nib writes the line from left to right, slow at its ends and quick in its middle; for the rest
 * the pen lifts and goes back to where the line began, while what it wrote fades. Then it writes again.
 *
 * No Android in it, so the motion can be read and tested off the phone (QuillTest). Every value is for [p], how far
 * through the cycle it is, from 0 to 1.
 */
object Quill {
    const val CYCLE_MS = 1800L

    /** The share of a cycle spent writing; the rest is the pen going back. */
    const val WRITE = 0.7f

    /** The line it writes, as its humps' heights (shares of the tallest): a word in a hurried hand. */
    val HUMPS = floatArrayOf(1f, 0.55f, 0.8f, 0.5f)

    private fun ease(x: Float): Float = ((1 - kotlin.math.cos(x.coerceIn(0f, 1f) * Math.PI)) / 2).toFloat()

    /** How much of the line is written, 0 to 1. */
    fun written(p: Float): Float = if (p >= WRITE) 1f else ease(p / WRITE)

    /** How strongly the line shows, 0 to 1: whole while it is being written, fading as the pen goes back. */
    fun ink(p: Float): Float = if (p < WRITE) 1f else (1f - (p - WRITE) / (1f - WRITE)).coerceIn(0f, 1f)

    /** Where the nib is along the line, 0 to 1: at the end of what it has written, then back to the start. */
    fun at(p: Float): Float = if (p < WRITE) written(p) else 1f - ease((p - WRITE) / (1f - WRITE))

    /** How far the nib is lifted off the line, 0 to 1: down while it writes, up and down again on its way back. */
    fun lift(p: Float): Float = if (p < WRITE) 0f else kotlin.math.sin((p - WRITE) / (1f - WRITE) * Math.PI).toFloat().coerceIn(0f, 1f)

    /** The quill's lean in degrees, about its nib: it rocks with each hump of the line, and stands still off it. */
    fun lean(p: Float): Float = if (p < WRITE) (5f * kotlin.math.sin(written(p) * HUMPS.size * 2 * Math.PI)).toFloat() else 0f

    /** Standing still (animations off): the line written whole, the nib at its end. */
    const val STILL = WRITE - 0.001f
}

/**
 * The row while the AI works (Matt, 2026-11-09: "clean up the UI for when the AI is thinking"): a violet pill with a
 * feather quill scribbling a line in it, again and again (Matt, 2026-11-12: "When the AI is writing or rewriting show
 * a feather quill scribbling"; it was a sparkle that breathed), and the words with a light passing over them. The
 * same for Ghostwriter, Rewrite, In my voice, Enhance, Tidy and an answer being typed out: every one of them is the
 * row's busy chip.
 * It animates only while it is on screen, at about 30 frames a second, and stands still with animations off: the
 * line written, the quill at its end.
 */
@SuppressLint("ViewConstructor")
class ThinkingView(context: Context, private val words: CharSequence, private val theme: KeyboardTheme) : View(context) {
    private val ink = RowTint.AI.ink(theme)
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RowTint.AI.wash(theme) }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = theme.text
    }
    private val box = RectF()
    private val start = SystemClock.uptimeMillis()

    /** The line the quill writes, the part of it written so far, and where the nib is on it. */
    private val line = android.graphics.Path()
    private val drawn = android.graphics.Path()
    private val measure = android.graphics.PathMeasure()
    private val nib = FloatArray(2)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.6f, resources.displayMetrics)
        color = ink
    }

    init {
        contentDescription = words
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** The widest it may be: the row leaves room for the buttons beside it (PluginRow.setItems). */
    var maxWidth = Int.MAX_VALUE
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = (dp(12f + QUILL_DP + 14f) + text.measureText(words, 0, words.length)).toInt().coerceAtMost(maxWidth)
        setMeasuredDimension(resolveSize(want, widthMeasureSpec), resolveSize(dp(36f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val moving = animationsOn()
        val t = if (moving) (SystemClock.uptimeMillis() - start) / 1000f else 0f
        val h = height.toFloat()
        box.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(box, h / 2, h / 2, ground)

        // The quill. Its line: a row of humps along a baseline a little under the pill's middle.
        val x0 = dp(12f)
        val base = h / 2 + dp(6f)
        val step = dp(LINE_DP) / Quill.HUMPS.size
        line.reset()
        line.moveTo(x0, base)
        // A hump is a curve whose handle stands twice as high as the hump comes to.
        Quill.HUMPS.forEachIndexed { i, share -> line.quadTo(x0 + step * (i + 0.5f), base - 2 * dp(HUMP_DP) * share, x0 + step * (i + 1), base) }
        val p = if (moving) ((SystemClock.uptimeMillis() - start) % Quill.CYCLE_MS) / Quill.CYCLE_MS.toFloat() else Quill.STILL
        measure.setPath(line, false)
        val length = measure.length
        // What it has written so far, fading once the pen has left it.
        drawn.reset()
        measure.getSegment(0f, length * Quill.written(p), drawn, true)
        stroke.alpha = (255 * Quill.ink(p)).toInt()
        canvas.drawPath(drawn, stroke)
        // The quill itself: Phosphor's feather, whose nib is at its lower left, set down where the line ends.
        measure.getPosTan(length * Quill.at(p), nib, null)
        val size = dp(FEATHER_DP)
        val px = nib[0]
        val py = nib[1] - dp(LIFT_DP) * Quill.lift(p)
        canvas.save()
        canvas.rotate(Quill.lean(p), px, py)
        canvas.translate(px - size * NIB_X, py - size * NIB_Y)
        IconDrawable.drawGlyph(canvas, RowIcons.icon(RowIcon.FEATHER), size, ink, iconPaint)
        canvas.restore()

        // The words, cut to fit, with a light passing over them every 1.6 s.
        val x = dp(12f + QUILL_DP)
        val room = width - x - dp(14f)
        if (room > 0) {
            val shown = TextUtils.ellipsize(words, text, room, TextUtils.TruncateAt.END)
            val w = text.measureText(shown, 0, shown.length)
            if (moving) {
                val p = (t % 1.6f) / 1.6f
                val mid = x - w * 0.4f + (w * 1.8f) * p
                text.shader = LinearGradient(mid - dp(36f), 0f, mid + dp(36f), 0f, intArrayOf(theme.text, ink, theme.text), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            } else text.shader = null
            val baseline = h / 2 - (text.descent() + text.ascent()) / 2
            canvas.drawText(shown, 0, shown.length, x, baseline, text)
        }
        if (moving && isShown) postInvalidateDelayed(33)
    }

    private companion object {
        /** The line the quill writes: how long it is and how tall its tallest hump, in dp. */
        const val LINE_DP = 16f
        const val HUMP_DP = 6f
        /** The feather: its size, how far its nib lifts off the line on the way back, and where in its square the nib is. */
        const val FEATHER_DP = 18f
        const val LIFT_DP = 3.5f
        const val NIB_X = 0.12f
        const val NIB_Y = 0.88f
        /** What the quill and its line take of the pill before the words: the line, the feather beyond its nib, and a gap. */
        const val QUILL_DP = LINE_DP + FEATHER_DP * (1f - NIB_X) + 6f
    }
}

/**
 * The keyboard's sign that something is working (Matt, 2026-11-09: "better iconography for when things are processing
 * like effects on the keyboard"): a hairline along the top of the keys with a violet light running along it, there
 * while the AI writes, types or rewrites and while dictation turns speech into words. Off, it takes no room and draws
 * nothing. With animations off it is a steady line.
 */
class WorkingLine(context: Context) : View(context) {
    var theme: KeyboardTheme = KeyboardTheme.LIGHT
        set(value) { field = value; invalidate() }

    var on = false
        set(value) {
            if (field == value) return
            field = value
            if (value) { since = SystemClock.uptimeMillis(); visibility = VISIBLE; alpha = 0f; animate().alpha(1f).setDuration(160).start() }
            else animate().alpha(0f).setDuration(220).withEndAction { if (!field) visibility = GONE }.start()
        }

    private var since = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { visibility = GONE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val ink = RowTint.AI.ink(theme)
        // The track: the hue, faint, the whole width.
        paint.shader = null
        paint.color = (ink and 0x00FFFFFF) or (0x40 shl 24)
        canvas.drawRect(0f, 0f, w, h, paint)
        if (!animationsOn()) { paint.color = ink; canvas.drawRect(0f, 0f, w, h, paint); return }
        // The light: a third of the width, running left to right in 1.3 s, easing in and out, then again.
        val p = ((SystemClock.uptimeMillis() - since) % 1300L) / 1300f
        val eased = (1 - kotlin.math.cos(p * Math.PI)).toFloat() / 2f
        val run = w * 0.35f
        val mid = -run / 2 + (w + run) * eased
        val clear = ink and 0x00FFFFFF
        paint.shader = LinearGradient(mid - run / 2, 0f, mid + run / 2, 0f, intArrayOf(clear, ink, theme.accent, clear), floatArrayOf(0f, 0.45f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint)
        if (on) postInvalidateOnAnimation()
    }
}
