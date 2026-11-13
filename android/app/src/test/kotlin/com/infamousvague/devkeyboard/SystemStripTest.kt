package com.infamousvague.devkeyboard

import com.infamousvague.devkeyboard.SystemStrip.Buttons
import com.infamousvague.devkeyboard.SystemStrip.Phone
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The strip under the keys (SystemStrip.kt; Matt, 2026-11-12: "detect if the system show hide keyboard and keyboard
 * select are enabled and if not be able to slide the keyboard down"): when the phone's own buttons are taken to be
 * there, and how much room the keys keep. Lengths here are a phone's at 3 px to the dp: the strip 144, the gesture
 * bar 72, "More" 60.
 */
class SystemStripTest {
    private val frame = 144
    private val bar = 72
    private val more = 60
    private val other = Phone(galaxy = false)
    private fun galaxy(gestures: Int? = 1, hint: Int? = 1, hide: Int? = 1, keyboard: Int? = 1) = Phone(true, gestures, hint, hide, keyboard)
    private fun room(space: String, reported: Int, buttons: Buttons, gestureBar: Boolean = true) = SystemStrip.room(space, reported, gestureBar, buttons, frame, bar, more)

    @Test fun `Android's own strip in the window is the buttons, whatever a setting says`() {
        // Android 14 and later: the buttons' room comes as a caption bar inset.
        assertEquals(Buttons.DRAWN, SystemStrip.buttons(frame, false, other))
        assertEquals(Buttons.DRAWN, SystemStrip.buttons(frame, false, galaxy(hide = 0, keyboard = 0)))
        // Android 13: the strip is found in the window.
        assertEquals(Buttons.DRAWN, SystemStrip.buttons(0, true, other))
        assertEquals(Buttons.DRAWN, SystemStrip.buttons(0, true, galaxy(hide = 0, keyboard = 0)))
    }

    @Test fun `a Galaxy's buttons are gone only where its settings say both are off`() {
        assertEquals(Buttons.NONE, SystemStrip.buttons(0, false, galaxy(hide = 0, keyboard = 0)))
        // Either one on, and they are there.
        assertEquals(Buttons.DRAWN, SystemStrip.buttons(0, false, galaxy(hide = 1, keyboard = 0)))
        assertEquals(Buttons.DRAWN, SystemStrip.buttons(0, false, galaxy(hide = 0, keyboard = 1)))
        assertEquals(Buttons.DRAWN, SystemStrip.buttons(0, false, galaxy()))
        // With no gesture bar drawn, the button to hide the keyboard is not drawn either, whatever its switch says.
        assertEquals(Buttons.NONE, SystemStrip.buttons(0, false, galaxy(hint = 0, hide = 1, keyboard = 0)))
        assertEquals(Buttons.NONE, SystemStrip.buttons(0, false, galaxy(hint = 0, hide = null, keyboard = 0)))
        assertEquals(Buttons.DRAWN, SystemStrip.buttons(0, false, galaxy(hint = 0, hide = 0, keyboard = 1)))
    }

    @Test fun `what could not be read is not known, and nothing is taken away for it`() {
        // Another maker's phone: none of those settings is its.
        assertEquals(Buttons.UNKNOWN, SystemStrip.buttons(0, false, other))
        // A Galaxy that will not say one of them.
        assertEquals(Buttons.UNKNOWN, SystemStrip.buttons(0, false, galaxy(hide = null, keyboard = 0)))
        assertEquals(Buttons.UNKNOWN, SystemStrip.buttons(0, false, galaxy(hide = 0, keyboard = null)))
        assertEquals(Buttons.UNKNOWN, SystemStrip.buttons(0, false, galaxy(gestures = null, hide = 0, keyboard = 0)))
        // Its three buttons, not gestures: the bar is the buttons, and Android reports all of it.
        assertEquals(Buttons.UNKNOWN, SystemStrip.buttons(0, false, galaxy(gestures = 0, hide = 0, keyboard = 0)))
        for (unknown in listOf(Buttons.UNKNOWN, Buttons.DRAWN)) {
            // As since 0.9.3: the whole strip wherever there is a gesture bar, however little Android reports.
            assertEquals(frame, room("auto", bar, unknown))
            assertEquals(frame, room("auto", 0, unknown))
            assertEquals(frame, room("auto", frame, unknown))
            // And a bar taller than the strip (three buttons on a tablet) is kept whole.
            assertEquals(200, room("auto", 200, unknown))
        }
        // No bar of any kind: nothing is kept.
        assertEquals(0, room("auto", 0, Buttons.UNKNOWN, gestureBar = false))
    }

    @Test fun `with the buttons gone the keys keep the gesture bar's room and no more`() {
        // Android reports the gesture bar: that.
        assertEquals(bar, room("auto", bar, Buttons.NONE))
        // It reports the whole strip with nothing in it: still only the gesture bar.
        assertEquals(bar, room("auto", frame, Buttons.NONE))
        // It reports less, or nothing: no more than it reports.
        assertEquals(30, room("auto", 30, Buttons.NONE))
        assertEquals(0, room("auto", 0, Buttons.NONE))
    }

    @Test fun `None and More are the person's own choice, whatever is found`() {
        for (buttons in Buttons.values()) {
            assertEquals(bar, room("none", bar, buttons))
            assertEquals(0, room("none", 0, buttons))
            assertEquals(frame + more, room("more", bar, buttons))
            assertEquals(frame + more, room("more", 0, buttons))
        }
    }

    @Test fun `what was found is said in one line, in dp, with nothing of the person in it`() {
        val seen = SystemStrip.Seen(72, 0, 0, 96, false, galaxy(hide = 0, keyboard = 0), Buttons.NONE, 72, 3f)
        assertEquals("navigation=24dp caption=0dp tappable=0dp gestures=32dp strip=none galaxy(gestures=1 hint=1 hide=0 keyboard=0) buttons=none room=24dp", seen.line())
        val pixel = SystemStrip.Seen(63, 126, 126, 126, true, other, Buttons.DRAWN, 126, 2.625f)
        assertEquals("navigation=24dp caption=48dp tappable=48dp gestures=48dp strip=shown buttons=drawn room=48dp", pixel.line())
    }
}
