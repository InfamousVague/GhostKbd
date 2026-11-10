package com.infamousvague.devkeyboard

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sherlock's eyes (Matt, 2026-11-10: "read the screen with OCR or whatever is possible so it can automatically reply
 * in my words to any open chat"). An accessibility service the person turns on in Android's settings; it does nothing
 * on its own and listens to no events. The keyboard asks it, when Sherlock drafts a reply, for the text of the app
 * under the keyboard.
 *
 * It reads the app's own text first (what a screen reader reads: exact, and quick). Where an app draws its text
 * itself and that comes back nearly empty, it takes a screenshot of the app above the keyboard and reads it with
 * ML Kit's on-phone OCR (Android 11 and later). Nothing it reads is kept; the keyboard sends it to the person's own AI
 * for the one reply and drops it.
 */
class SherlockService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    enum class Side { THEM, ME, MIDDLE }

    /** One piece of text on the screen, where it sat: [side] by which edge it hugs, as chat bubbles do. */
    data class Line(val text: String, val side: Side, val top: Int, val left: Int)

    /** What was on the screen: the app, the lines at its top (a chat's name), the rest in reading order, and how it was read. */
    data class Screen(val app: String?, val header: List<String>, val lines: List<Line>, val ocr: Boolean) {
        val chars get() = header.sumOf { it.length } + lines.sumOf { it.text.length }
    }

    /**
     * The app under the keyboard, read. [keyboardTop] is where the keyboard starts on the screen, from the keyboard
     * itself: Android does not always list its window here, and an app drawn edge to edge runs on behind it.
     * Blocking (the OCR waits on a screenshot): call it off the main thread.
     */
    fun read(keyboardTop: Int): Screen? {
        val all = runCatching { windows }.getOrNull().orEmpty()
        val listed = all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }?.let { w -> Rect().also { w.getBoundsInScreen(it) }.top }
        val keyboard = listOfNotNull(listed, keyboardTop.takeIf { it > 0 }).minOrNull()?.let { Rect(0, it, 0, it) }
        val apps = all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = apps.firstOrNull { it.isFocused } ?: apps.firstOrNull { it.isActive } ?: apps.maxByOrNull { w -> Rect().also { w.getBoundsInScreen(it) }.let { it.width() * it.height() } }
        val root = window?.root ?: rootInActiveWindow ?: return null
        val bounds = Rect().also { root.getBoundsInScreen(it) }
        val bottom = keyboard?.top?.takeIf { it > bounds.top } ?: bounds.bottom
        val app = root.packageName?.toString()
        val found = ArrayList<Piece>()
        walk(root, found, bottom, bounds.width(), 0, false)
        val fromTree = shape(app, found, bounds, bottom, ocr = false)
        if (fromTree.chars >= MIN_TREE_CHARS || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return fromTree
        return runCatching { ocr(app, bounds, bottom) }.getOrNull()?.takeIf { it.chars > fromTree.chars } ?: fromTree
    }

    /** A piece of text and where it sat; [control] when it is inside a button or chip rather than a message. */
    private class Piece(val text: String, val rect: Rect, val control: Boolean)

    /**
     * Every visible piece of text above the keyboard, but the field being typed in (the reply itself). Text inside a
     * small tappable box is a button or a suggestion chip ("Report spam", a "Yep!" quick reply), not a message: a
     * bubble's tappable row runs most of the width.
     */
    private fun walk(node: AccessibilityNodeInfo, out: MutableList<Piece>, bottom: Int, width: Int, depth: Int, inControl: Boolean) {
        if (depth > 60 || out.size > 400) return
        if (!node.isVisibleToUser) return
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (r.top >= bottom || r.isEmpty) return
        val control = inControl || ((node.isClickable || node.className?.contains("Button") == true) &&
            r.height() < CONTROL_MAX_DP * resources.displayMetrics.density && r.width() < width * 0.7f)
        if (!node.isEditable && !node.isPassword) {
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && out.none { it.text == text && it.rect.contains(r) }) out += Piece(text, r, control)
        }
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, out, bottom, width, depth + 1, control) }
    }

    /** The app above the keyboard as a picture, read with ML Kit's on-phone text recognition. */
    private fun ocr(app: String?, bounds: Rect, bottom: Int): Screen? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var shot: Bitmap? = null
        takeScreenshot(Display.DEFAULT_DISPLAY, shooter, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                shot = runCatching { Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
                buffer.close()
                latch.countDown()
            }
            override fun onFailure(errorCode: Int) { latch.countDown() }
        })
        latch.await(3, TimeUnit.SECONDS)
        val full = shot ?: return null
        // Below the status bar: its clock and icons are not the chat.
        val statusBar = resources.getIdentifier("status_bar_height", "dimen", "android").takeIf { it != 0 }?.let { resources.getDimensionPixelSize(it) } ?: 0
        val top = maxOf(bounds.top, statusBar).coerceIn(0, full.height - 1)
        val height = (bottom.coerceAtMost(full.height) - top).coerceAtLeast(1)
        val picture = Bitmap.createBitmap(full, 0, top, full.width, height)
        val text = Tasks.await(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(picture, 0)), 8, TimeUnit.SECONDS)
        val found = text.textBlocks.mapNotNull { b -> b.boundingBox?.let { box -> Piece(b.text.replace('\n', ' ').trim(), Rect(box).apply { offset(0, top) }, false) } }
        return shape(app, controlsMarked(found, bottom), Rect(0, top, full.width, bottom), bottom, ocr = true)
    }

    /**
     * A picture has no buttons to tell apart, so OCR's pieces are judged by their shape: short labels side by side on
     * one row are chips or buttons ("Yes", "Yep!", "Add contact" · "Report spam"); a message stands alone on its row
     * (a time beside it doesn't count). Text in the strip just above the keyboard is the compose bar's own.
     */
    private fun controlsMarked(found: List<Piece>, bottom: Int): List<Piece> {
        val composeTop = bottom - COMPOSE_DP * resources.displayMetrics.density
        fun short(p: Piece) = p.text.split(' ').size <= 3 && p.text.length <= 24
        fun sameRow(a: Rect, b: Rect) = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > minOf(a.height(), b.height()) / 2
        val real = found.filter { !TIME.matches(it.text) }
        return found.map { p ->
            val chip = short(p) && !TIME.matches(p.text) && real.any { o -> o !== p && short(o) && sameRow(o.rect, p.rect) }
            Piece(p.text, p.rect, chip || p.rect.top >= composeTop)
        }
    }

    /** Sorts the pieces into reading order and tells the other person's bubbles (on the left) from yours (on the right). */
    private fun shape(app: String?, found: List<Piece>, window: Rect, bottom: Int, ocr: Boolean): Screen {
        val width = window.width().coerceAtLeast(1)
        // The header is measured on the whole screen: the keyboard shrinks the app's own window, not its top bar.
        val headerLine = window.top + resources.displayMetrics.heightPixels * HEADER_SHARE
        val header = ArrayList<String>()
        val lines = ArrayList<Line>()
        for (piece in found.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))) {
            val text = piece.text
            val r = piece.rect
            if (r.bottom <= headerLine) { header += text; continue }
            if (piece.control) continue
            val leftGap = r.left - window.left
            val rightGap = window.right - r.right
            val side = when {
                rightGap < leftGap * 0.6f && leftGap > width * 0.12f -> Side.ME
                leftGap < rightGap * 0.6f && rightGap > width * 0.12f -> Side.THEM
                else -> Side.MIDDLE
            }
            lines += Line(text, side, r.top, r.left)
        }
        return Screen(app, header, lines, ocr)
    }

    companion object {
        /** The running service, while Android has it bound (the person turned it on). */
        @Volatile var instance: SherlockService? = null
            private set

        /** Less text than this from the app's own tree means it draws its text itself: read the picture instead. */
        private const val MIN_TREE_CHARS = 40
        /** The top of the app taken as its header: a chat's name and status. */
        private const val HEADER_SHARE = 0.15f
        /** A tappable box shorter than this (dp) and narrower than most of the width is a button or chip. */
        private const val CONTROL_MAX_DP = 64f
        /** The compose bar above the keyboard, as tall as most chat apps draw it (dp). */
        private const val COMPOSE_DP = 60f
        private val TIME = Regex("^\\d{1,2}[:.]\\d{2}(\\s?[AaPp]\\.?[Mm]\\.?)?$")
        private val shooter = Executors.newSingleThreadExecutor()

        /** Whether the person turned Sherlock on in Android's accessibility settings. */
        fun enabled(context: Context): Boolean {
            val me = ComponentName(context, SherlockService::class.java).flattenToString()
            val on = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return on.split(':').any { it.equals(me, ignoreCase = true) }
        }

        /** Android's accessibility settings, where the person turns Sherlock on. */
        fun openSettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
