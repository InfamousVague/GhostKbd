package com.infamousvague.devkeyboard

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * The strip under the keys, where a phone draws its own buttons for a keyboard (hide it, switch to another) and its
 * gesture bar: whether those buttons are there, and how much room the keys keep for them.
 *
 * Matt, 2026-11-12: "Id like to be able to detect if the system show hide keyboard and keyboard select are enabled
 * and if not be able to slide the keyboard down since we don't need to reserve the space anymore". Until then the
 * keys kept the buttons' 48dp wherever there was a gesture bar at all (0.9.3), because his phone had drawn its
 * buttons over the bottom row while reporting less; with both of the phone's buttons switched off that is 48dp of
 * nothing.
 *
 * Three things say whether the buttons are there, in the order they are believed ([buttons]):
 *
 * 1. Android's own strip in the keyboard's window. With gesture navigation, Android 13 and later draw the two
 *    buttons inside the keyboard's own window, and from 14's first quarterly release their room reaches the keyboard
 *    as a "caption bar" inset of its own, not as the navigation bar's (which is then only the gesture bar's 24dp).
 *    A caption bar inset, or Android's strip found shown in the window, is the buttons, whatever any setting says.
 * 2. A Galaxy's own settings, read by name ([Phone]): its gesture navigation, its "Show button to hide keyboard",
 *    its gesture hint, and its "Keyboard button on navigation bar". Only where every one that matters could be read,
 *    and they say that neither button is on, are the buttons taken to be gone.
 * 3. Otherwise it is not known, and the keys keep the room as before.
 *
 * So the keys only ever sit lower where the phone itself says there is nothing under them. Not seen on a Galaxy:
 * the names are Samsung's own, found in its Settings app as other people have taken it apart, and one of them
 * ("show_keyboard_button") is a guess from the preference's name; docs/system-strip.md says which is which, and
 * Settings › Look & feel › Keys says what was found on the phone in hand.
 *
 * Nothing of Android in the two rules ([buttons], [room]): SystemStripTest tries them off the phone.
 */
object SystemStrip {
    /** Whether the phone draws its own buttons under the keyboard. */
    enum class Buttons { DRAWN, NONE, UNKNOWN }

    /**
     * A Galaxy's own settings for what it draws under a keyboard, each as read by its name, null where it is not
     * there to read: [gestures] (1: swipe gestures, 0: its three buttons), [hint] (0: the gesture bar is not drawn),
     * [hide] (1: "Show button to hide keyboard"), [keyboard] (1: "Keyboard button on navigation bar"). [galaxy] is
     * false on any other maker's phone, and nothing was read.
     */
    data class Phone(val galaxy: Boolean, val gestures: Int? = null, val hint: Int? = null, val hide: Int? = null, val keyboard: Int? = null)

    /**
     * Whether the phone's buttons are under the keys: [captionPx] is the caption bar inset the keyboard's window was
     * given at its bottom edge, [stripShown] whether Android's own strip was found shown in that window.
     */
    fun buttons(captionPx: Int, stripShown: Boolean, phone: Phone): Buttons {
        if (captionPx > 0 || stripShown) return Buttons.DRAWN
        if (!phone.galaxy) return Buttons.UNKNOWN
        // With its three buttons a Galaxy's bar is the buttons, and Android reports the whole of it.
        if (phone.gestures != 1) return Buttons.UNKNOWN
        // No gesture bar drawn: nor is the button to hide the keyboard, which stands in it.
        val hide = (if (phone.hint == 0) 0 else phone.hide) ?: return Buttons.UNKNOWN
        val keyboard = phone.keyboard ?: return Buttons.UNKNOWN
        return if (hide == 0 && keyboard == 0) Buttons.NONE else Buttons.DRAWN
    }

    /**
     * The room kept under the keys (px). [space] is Settings › Look & feel › Keys › Space under the keys. [reportedPx]
     * is the most Android reports for the bottom edge (navigation bar, caption bar, anything to tap); [gestureBar]
     * that there is a gesture bar at all; [framePx] the buttons' strip (48dp), [barPx] the gesture bar alone,
     * [morePx] what "More" adds.
     *
     * "none": only what Android reports. "auto": the buttons' strip wherever there is a gesture bar, unless the
     * phone says its buttons are off, and then the gesture bar alone, however much Android reports (a Galaxy has
     * been found to report the whole strip with nothing in it). "more": the strip and more, whatever the phone says.
     */
    fun room(space: String, reportedPx: Int, gestureBar: Boolean, buttons: Buttons, framePx: Int, barPx: Int, morePx: Int): Int {
        if (space == "none") return reportedPx
        if (space == "auto" && buttons == Buttons.NONE) return minOf(reportedPx, barPx)
        val floor = if (reportedPx > 0 || gestureBar) framePx else 0
        return maxOf(reportedPx, floor) + if (space == "more") morePx else 0
    }

    /** What was last found under the keyboard on this phone, for Settings to say and for a report: null until the keys have been shown. */
    data class Seen(val navigationPx: Int, val captionPx: Int, val tappablePx: Int, val gesturesPx: Int, val stripShown: Boolean, val phone: Phone, val buttons: Buttons, val roomPx: Int, val density: Float) {
        /** One line of it, with nothing of the person in it: lengths in dp, and the phone's settings as read. */
        fun line(): String {
            fun dp(px: Int) = Math.round(px / density)
            val galaxy = if (phone.galaxy) " galaxy(gestures=${phone.gestures} hint=${phone.hint} hide=${phone.hide} keyboard=${phone.keyboard})" else ""
            return "navigation=${dp(navigationPx)}dp caption=${dp(captionPx)}dp tappable=${dp(tappablePx)}dp gestures=${dp(gesturesPx)}dp strip=${if (stripShown) "shown" else "none"}$galaxy buttons=${buttons.name.lowercase()} room=${dp(roomPx)}dp"
        }
    }

    @Volatile var seen: Seen? = null

    // --- the phone's own settings ---------------------------------------------------------------------------------

    private const val GESTURES = "navigation_bar_gesture_while_hidden"
    private const val HINT = "navigation_bar_gesture_hint"
    private const val HIDE = "navigation_bar_button_to_hide_keyboard"
    private const val KEYBOARD = "show_keyboard_button"

    /** The settings above as this phone has them. Reading one that is not there, or that the phone will not give, is null. */
    fun phone(context: Context): Phone {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return Phone(false)
        val cr = context.contentResolver
        fun global(name: String) = runCatching { Settings.Global.getInt(cr, name) }.getOrNull()
        // Which of Android's three tables Samsung keeps this one in is not known: it is looked for in each.
        fun anywhere(name: String) = runCatching { Settings.Secure.getInt(cr, name) }.getOrNull()
            ?: runCatching { Settings.System.getInt(cr, name) }.getOrNull()
            ?: global(name)
        return Phone(true, global(GESTURES), global(HINT), global(HIDE), anywhere(KEYBOARD))
    }

    /** Where a change to any of those settings is told ([ContentResolver.registerContentObserver]); none on another maker's phone. */
    fun watched(): List<Uri> {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return emptyList()
        return listOf(GESTURES, HINT, HIDE, KEYBOARD).map(Settings.Global::getUriFor) +
            listOf(Settings.Secure.getUriFor(KEYBOARD), Settings.System.getUriFor(KEYBOARD))
    }

    /** The class of Android's own strip of buttons, as it stands in a keyboard's window (Android 13 and later). */
    const val STRIP_CLASS = "android.inputmethodservice.navigationbar.NavigationBarFrame"
}
