package com.infamousvague.devkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import kotlin.math.ceil

/**
 * How a one-time code from a text is shown (Matt, 2026-11-11: "make the code show up as a special chip not just the
 * normal clipboard"). What the chip draws is worked out here, with nothing of Android in it, so it is tested without
 * a screen (CodeLookTest).
 */
object CodeLook {
    /** How long a code that has just arrived is marked as new: a light passes over it, twice. */
    const val ARRIVAL_MS = 2400L

    /**
     * The code in the groups it is read in: six digits as two threes, eight as two fours, nine as three threes, the
     * way sign-in pages and the texts themselves write them. Any other length, and any code with a letter in it, is
     * left whole. This is only how it is drawn: what is typed is the code as it came.
     */
    fun groups(code: String): List<String> {
        if (code.isEmpty() || !code.all { it in '0'..'9' }) return listOf(code)
        return when (code.length) {
            6, 9 -> code.chunked(3)
            8 -> code.chunked(4)
            else -> listOf(code)
        }
    }

    /** What is left of the time a code that came at [at] is offered for ([span]): 1 as it arrives, 0 once it is over. */
    fun left(now: Long, at: Long, span: Long): Float {
        if (span <= 0) return 0f
        return (1f - (now - at).toFloat() / span).coerceIn(0f, 1f)
    }

    /** The code as a screen reader should say it: one character at a time, never as a number in the thousands. */
    fun spoken(code: String): String = code.toList().joinToString(" ")
}

/**
 * What makes a row item a one-time code's chip ([RowItem.code]): when the code came and how long it is offered where
 * the chip is, the row's start or the clipboard's list. Both on SystemClock.elapsedRealtime, as SmsCodes keeps them.
 */
class RowCode(val at: Long, val span: Long, val mail: Boolean = false)

/**
 * A text message with something in it: Phosphor's chat bubble (regular, with the duotone weight's 20% layer as the
 * wash; @phosphor-icons/core 2.1.1, MIT, as RowIcons.kt's) and three dots where chat-dots has them. On the 24 grid.
 */
internal val TEXT_CODE_ICON = PackIcon(
    false,
    listOf(
        "M20.25 4.5H3.75A1.5 1.5 0 002.25 6V21a1.49 1.49 0 00.87 1.36 1.5 1.5 0 00.63.14 1.49 1.49 0 00.96-.35l.01-.01L7.78 19.5H20.25a1.5 1.5 0 001.5-1.5V6a1.5 1.5 0 00-1.5-1.5ZM3.75 21h0Zm16.5-3H7.5a.75.75 0 00-.49.18L3.75 21V6h16.5Z",
        "M6.75 12a1.125 1.125 0 1 0 2.25 0a1.125 1.125 0 1 0 -2.25 0Z",
        "M10.875 12a1.125 1.125 0 1 0 2.25 0a1.125 1.125 0 1 0 -2.25 0Z",
        "M15 12a1.125 1.125 0 1 0 2.25 0a1.125 1.125 0 1 0 -2.25 0Z",
    ),
    listOf("M21 6V18a.75.75 0 01-.75.75H7.5L4.23 21.57A.75.75 0 013 21V6a.75.75 0 01.75-.75h16.5A.75.75 0 0121 6Z"),
    0.2f,
)

/**
 * A mail, for a code that came in a mail app's notification (MailCodes.kt): Phosphor's envelope-simple (regular, with
 * the duotone weight's flap as the wash; as above), on its own 256 grid.
 */
internal val MAIL_CODE_ICON = PackIcon(
    false,
    listOf("M224,48H32a8,8,0,0,0-8,8V192a16,16,0,0,0,16,16H216a16,16,0,0,0,16-16V56A8,8,0,0,0,224,48ZM203.43,64,128,133.15,52.57,64ZM216,192H40V74.19l82.59,75.71a8,8,0,0,0,10.82,0L216,74.19V192Z"),
    listOf("M224,56l-96,88L32,56Z"),
    0.2f,
    box = 256f,
)

/**
 * A one-time code in the row: a chip unlike the others, so it is not taken for something copied. A pill in the code's
 * own blue (RowTint.CODE) with a text message on it, the code in the groups it is read in, and an outline that runs
 * down as the time it is offered for passes: it reads as what it is, there for a few minutes and then gone. As it
 * arrives it settles into place and a light passes over the digits; with animations off it does neither. The outline
 * is drawn afresh once a second either way, as a clock is.
 */
@SuppressLint("ViewConstructor")
class CodeChip(context: Context, code: CharSequence, private val timing: RowCode, private val theme: KeyboardTheme) : View(context) {
    private val groups = CodeLook.groups(code.toString())
    private val ink = RowTint.CODE.ink(theme)
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = dp(1.5f) }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        // Every digit as wide as the next, and a little apart: a code is read a digit at a time.
        fontFeatureSettings = "tnum"
        letterSpacing = 0.06f
        color = theme.text
    }
    private val box = RectF()
    private val outline = Path()
    private val rest = Path()
    private val measure = PathMeasure()
    private var wordsWidth = 0f
    private val tick = Runnable { invalidate() }

    init {
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var words = 0f
        for ((i, group) in groups.withIndex()) words += text.measureText(group) + if (i < groups.lastIndex) dp(GROUP_GAP_DP) else 0f
        wordsWidth = words
        val want = dp(PAD_DP + ICON_DP + GAP_DP + PAD_DP) + words
        setMeasuredDimension(resolveSize(ceil(want).toInt(), widthMeasureSpec), resolveSize(dp(36f).toInt(), heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The pill's outline, from the middle of its top and clockwise: what is left of the time is its end, so the
        // gap opens at the top and goes round as a clock's hand does.
        val half = line.strokeWidth / 2
        box.set(half, half, w - half, h - half)
        val r = box.height() / 2
        outline.rewind()
        outline.moveTo(box.centerX(), box.top)
        outline.lineTo(box.right - r, box.top)
        outline.arcTo(box.right - 2 * r, box.top, box.right, box.bottom, -90f, 180f, false)
        outline.lineTo(box.left + r, box.bottom)
        outline.arcTo(box.left, box.top, box.left + 2 * r, box.bottom, 90f, 180f, false)
        outline.close()
        measure.setPath(outline, false)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.elapsedRealtime()
        val age = now - timing.at
        val arriving = animationsOn() && age in 0 until CodeLook.ARRIVAL_MS
        val w = width.toFloat()
        val h = height.toFloat()
        val r = box.height() / 2

        canvas.save()
        // Settling into place: a little small at first, its full size within a quarter of a second.
        if (arriving && age < SETTLE_MS) {
            val p = 1f - age / SETTLE_MS.toFloat()
            val s = 1f - 0.06f * p * p
            canvas.scale(s, s, w / 2, h / 2)
        }

        ground.color = if (isPressed) theme.keyPressed else RowTint.CODE.wash(theme)
        canvas.drawRoundRect(box, r, r, ground)

        // The outline: all of it faint, and over that what is left of the code's time.
        val left = CodeLook.left(now, timing.at, timing.span)
        line.color = (ink and 0x00FFFFFF) or (0x3D shl 24)
        canvas.drawPath(outline, line)
        if (left > 0f) {
            val length = measure.length
            rest.rewind()
            measure.getSegment(length * (1f - left), length, rest, true)
            line.color = ink
            canvas.drawPath(rest, line)
        }

        // The text message, then the code; the other way round where the screen reads right to left. The code itself
        // is always written left to right.
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val icon = dp(ICON_DP)
        canvas.save()
        canvas.translate(if (rtl) w - dp(PAD_DP) - icon else dp(PAD_DP), (h - icon) / 2)
        IconDrawable.drawGlyph(canvas, if (timing.mail) MAIL_CODE_ICON else TEXT_CODE_ICON, icon, ink, iconPaint)
        canvas.restore()

        val start = if (rtl) dp(PAD_DP) else dp(PAD_DP + ICON_DP + GAP_DP)
        if (arriving) {
            // The light: across the digits in 1.2 s, twice.
            val band = dp(30f)
            val p = (age % PASS_MS) / PASS_MS.toFloat()
            val mid = start - band + (wordsWidth + 2 * band) * p
            text.shader = LinearGradient(mid - band, 0f, mid + band, 0f, intArrayOf(theme.text, ink, theme.text), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        } else text.shader = null
        val baseline = h / 2 - (text.descent() + text.ascent()) / 2
        var x = start
        for ((i, group) in groups.withIndex()) {
            canvas.drawText(group, x, baseline, text)
            x += text.measureText(group) + if (i < groups.lastIndex) dp(GROUP_GAP_DP) else 0f
        }
        canvas.restore()

        removeCallbacks(tick)
        if (!isShown) return
        if (arriving) postInvalidateOnAnimation() else if (left > 0f) postDelayed(tick, 1000)
    }

    private companion object {
        /** The chip's measures, in dp: its padding at either end, the icon, the room between the icon and the code, and between the code's groups. */
        const val PAD_DP = 12f
        const val ICON_DP = 18f
        const val GAP_DP = 7f
        const val GROUP_GAP_DP = 5f
        /** How long the chip takes to settle as it arrives, and one pass of the light, in milliseconds. */
        const val SETTLE_MS = 260L
        const val PASS_MS = 1200L
    }
}
