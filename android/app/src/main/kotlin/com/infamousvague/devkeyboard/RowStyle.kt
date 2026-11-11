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
 * The row while the AI works (Matt, 2026-11-09: "clean up the UI for when the AI is thinking"): a violet pill with a
 * sparkle that breathes and turns, a small star that twinkles beside it, and the words with a light passing over them.
 * It animates only while it is on screen, at about 30 frames a second, and stands still with animations off.
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

    init {
        contentDescription = words
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** The widest it may be: the row leaves room for the buttons beside it (PluginRow.setItems). */
    var maxWidth = Int.MAX_VALUE
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val want = (dp(12f + 20f + 8f + 14f) + text.measureText(words, 0, words.length)).toInt().coerceAtMost(maxWidth)
        setMeasuredDimension(resolveSize(want, widthMeasureSpec), resolveSize(dp(36f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val moving = animationsOn()
        val t = if (moving) (SystemClock.uptimeMillis() - start) / 1000f else 0f
        val h = height.toFloat()
        box.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(box, h / 2, h / 2, ground)

        // The sparkle: breathing between 85% and 100%, turning a little either way.
        val size = dp(19f)
        val cx = dp(12f) + size / 2
        val cy = h / 2
        val breathe = 0.925f + 0.075f * kotlin.math.sin(t * 2f * Math.PI.toFloat() / 1.4f)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(10f * kotlin.math.sin(t * 2f * Math.PI.toFloat() / 2.8f))
        canvas.scale(breathe, breathe)
        canvas.translate(-size / 2, -size / 2)
        IconDrawable.drawGlyph(canvas, RowIcons.icon(RowIcon.SPARKLE), size, ink, iconPaint)
        canvas.restore()
        // The small star: up and to the right, fading in and out out of step with the sparkle.
        val twinkle = if (moving) (0.5f + 0.5f * kotlin.math.sin(t * 2f * Math.PI.toFloat() / 1.1f + 1.3f)) else 1f
        drawStar(canvas, cx + size * 0.55f, cy - size * 0.42f, dp(3.2f) * (0.6f + 0.4f * twinkle), ink, (255 * (0.35f + 0.65f * twinkle)).toInt())

        // The words, cut to fit, with a light passing over them every 1.6 s.
        val x = dp(12f) + size + dp(8f)
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

    private val star = android.graphics.Path()

    /** A four-pointed star of radius [r]: the AI's small spark. */
    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int, alpha: Int) {
        val k = r * 0.28f
        star.reset()
        star.moveTo(cx, cy - r)
        star.quadTo(cx + k, cy - k, cx + r, cy)
        star.quadTo(cx + k, cy + k, cx, cy + r)
        star.quadTo(cx - k, cy + k, cx - r, cy)
        star.quadTo(cx - k, cy - k, cx, cy - r)
        star.close()
        iconPaint.style = Paint.Style.FILL
        iconPaint.color = color
        iconPaint.alpha = alpha
        canvas.drawPath(star, iconPaint)
        iconPaint.alpha = 255
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
