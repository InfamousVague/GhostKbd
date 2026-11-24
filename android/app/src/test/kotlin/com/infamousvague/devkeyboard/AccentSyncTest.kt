package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class AccentSyncTest {
    @Test fun theNewerChoiceWins() {
        assertEquals("pink", AccentSync.take(mine = 100, theirId = "pink", theirs = 200))
        assertNull(AccentSync.take(mine = 200, theirId = "pink", theirs = 100))
        assertNull("the same time is the same choice, not taken back", AccentSync.take(mine = 200, theirId = "pink", theirs = 200))
        assertNull("one never chosen there is not taken", AccentSync.take(mine = 0, theirId = "white", theirs = 0))
        assertNull("an id this keyboard does not know", AccentSync.take(mine = 0, theirId = "chartreuse", theirs = 5))
    }

    @Test fun ghostMessagingsColoursInItsOrder() {
        assertEquals(
            listOf("ghost", "ink", "wallpaper", "white", "silver", "gray", "graphite", "blue", "indigo", "purple", "pink", "red", "orange", "yellow", "green", "mint"),
            AccentSync.COLOURS.keys.toList(),
        )
    }

    @Test fun aColourIsTheEnterKeys() {
        val pink = KeyboardTheme.withAccent(KeyboardTheme.DARK, "pink")
        assertEquals(0xFFE0457B.toInt(), pink.accent)
        assertEquals(0xFFE0457B.toInt(), pink.popupSelected)
        assertEquals(0xFFFFFFFF.toInt(), pink.onAccent)
        assertEquals(KeyboardTheme.DARK.background, pink.background)
    }

    @Test fun ghostAndUnseenColoursLeaveTheTheme() {
        assertSame(KeyboardTheme.DARK, KeyboardTheme.withAccent(KeyboardTheme.DARK, "ghost"))
        assertSame("white on the light keyboard would not be seen", KeyboardTheme.LIGHT, KeyboardTheme.withAccent(KeyboardTheme.LIGHT, "white"))
        assertSame("no wallpaper colour before Android 12", KeyboardTheme.DARK, KeyboardTheme.withAccent(KeyboardTheme.DARK, "wallpaper"))
        assertEquals(0xFF123456.toInt(), KeyboardTheme.withAccent(KeyboardTheme.DARK, "wallpaper") { 0xFF123456.toInt() }.accent)
    }

    @Test fun inkIsTheLettering() {
        assertEquals(KeyboardTheme.LIGHT.text, KeyboardTheme.withAccent(KeyboardTheme.LIGHT, "ink").accent)
        assertEquals(KeyboardTheme.DARK.text, KeyboardTheme.withAccent(KeyboardTheme.DARK, "ink").accent)
    }
}
