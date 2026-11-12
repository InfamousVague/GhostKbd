package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The text around the cursor as the keyboard knows it without asking the app (FieldMirror.kt; Matt, 2026-11-12: "do a
 * performance pass on the keyboard to make sure everything is as fast as possible"): what it answers, and every way
 * it stops answering, since an answer that is wrong is worse than a slow one.
 */
class FieldMirrorTest {
    private var now = 1_000L
    private val mirror = FieldMirror { now }

    /** A real reading of a field holding [before] and [after] with the cursor between them, as the row's refresh asks for it. */
    private fun read(before: String, after: String = "", selected: String = "", at: Int = before.length, asked: Int = 256) =
        mirror.read(before.takeLast(asked), selected, after.take(400), at, at + selected.length, asked, 400, fromStart = before.length <= asked)

    /** The keys type [text]; the app then reports the cursor where that puts it. */
    private fun type(text: String, at: Int): Int {
        mirror.committed(text, 1)
        mirror.reported(at + text.length, at + text.length, false)
        return at + text.length
    }

    /** A field read, and one letter typed and reported: from here the mirror answers. */
    private fun started(before: String, after: String = ""): Int {
        read(before, after)
        return type("x", before.length)
    }

    @Test fun `nothing is answered before the field has been read`() {
        assertNull(mirror.textBefore(1))
        assertNull(mirror.textAfter(1))
        assertNull(mirror.selection())
        assertNull(mirror.capsMode(CapsMode.SENTENCES))
        // Typing into a field that was never read tells nothing either.
        mirror.committed("a", 1)
        assertNull(mirror.textBefore(1))
    }

    @Test fun `a reading alone is not gone by until the app has reported the cursor where typing put it`() {
        read("Hello wor")
        assertNull(mirror.textBefore(3))
        mirror.committed("l", 1)
        // Typed, but the app has said nothing yet: it may be an app that never does.
        assertNull(mirror.textBefore(3))
        mirror.reported(10, 10, false)
        assertEquals("worl", mirror.textBefore(4))
    }

    @Test fun `what the keys type is put on the end of what stands before the cursor`() {
        var at = started("Hello ", " there")
        assertEquals("Hello x", mirror.textBefore(96))
        at = type("y", at)
        type(" ", at)
        assertEquals("xy ", mirror.textBefore(3))
        assertEquals(" ", mirror.textBefore(1))
        // What follows the cursor is as it was: typing at the cursor does not touch it.
        assertEquals(" ", mirror.textAfter(1))
        assertEquals(" there", mirror.textAfter(400))
        assertEquals("", mirror.selection())
    }

    @Test fun `several keys typed before the app reports any of them`() {
        read("ab")
        mirror.committed("c", 1)
        mirror.reported(3, 3, false)
        // Three more, faster than the app answers: the mirror already has all three.
        mirror.committed("d", 1)
        mirror.committed("e", 1)
        mirror.committed("f", 1)
        assertEquals("abcdef", mirror.textBefore(96))
        // The app catches up, one report at a time, then says the last again.
        mirror.reported(4, 4, false)
        mirror.reported(5, 5, false)
        assertEquals("abcdef", mirror.textBefore(96))
        mirror.reported(6, 6, false)
        mirror.reported(6, 6, false)
        assertEquals("abcdef", mirror.textBefore(96))
    }

    @Test fun `an app that reports only where the cursor ended up`() {
        read("ab")
        mirror.committed("c", 1)
        mirror.reported(3, 3, false)
        mirror.committed("d", 1)
        mirror.committed("e", 1)
        // One report for both.
        mirror.reported(5, 5, false)
        assertEquals("abcde", mirror.textBefore(96))
    }

    @Test fun `a cursor that is not where the keys put it drops the reading`() {
        val at = started("Hello world")
        assertEquals("dx", mirror.textBefore(2))
        // A tap in the text: the cursor is back in the first word.
        mirror.reported(3, 3, false)
        assertNull(mirror.textBefore(2))
        // The same place the cursor passed through a moment ago is still a move that was not the keys'.
        read("Hello worldx")
        type("y", at)
        mirror.reported(at, at, false)
        assertNull(mirror.textBefore(2))
    }

    @Test fun `a report that never comes is not waited for long, so a tap on that spot is not taken for the keys' doing`() {
        // A Korean syllable built up: one letter taken out and the fuller one put in, and the cursor ends where it
        // began, which the app does not report.
        var at = started("\uAC00")
        mirror.deleted(1, 0)
        mirror.committed("\uAC04", 1)
        assertEquals("\uAC00\uAC04", mirror.textBefore(96))
        // The row's reading shows it done; the reports have a quarter of a second left.
        read("\uAC00\uAC04")
        now += FieldMirror.REPORT_MS + 1
        // A tap one letter back, on a spot the keys' own edit passed through.
        mirror.reported(at - 1, at - 1, false)
        assertNull(mirror.textBefore(96))
        // Within that quarter of a second the late report of an edit is still the keys' own.
        at = started("ab")
        mirror.committed("c", 1)
        read("abxc")
        now += FieldMirror.REPORT_MS - 1
        mirror.reported(at + 1, at + 1, false)
        assertEquals("abxc", mirror.textBefore(96))
    }

    @Test fun `a selection, or a composing region, drops the reading`() {
        started("Hello")
        mirror.reported(2, 4, false)
        assertNull(mirror.textBefore(1))
        val at = started("Hello")
        mirror.reported(at, at, true)
        assertNull(mirror.textBefore(1))
    }

    @Test fun `a delete takes off what the keys take off`() {
        var at = started("Hello wor", "ld")
        mirror.deleted(1, 0)
        mirror.reported(at - 1, at - 1, false)
        at -= 1
        assertEquals("Hello wor", mirror.textBefore(96))
        // A word at a time, as a held backspace takes them.
        mirror.deleted(4, 0)
        assertEquals("Hello", mirror.textBefore(96))
        mirror.reported(at - 4, at - 4, false)
        assertEquals("Hello", mirror.textBefore(96))
        // After the cursor too.
        mirror.deleted(0, 1)
        assertEquals("d", mirror.textAfter(5))
    }

    @Test fun `a delete past what is known drops the reading, unless the field's start is known to be there`() {
        // 300 characters before the cursor, of which the reading holds the last 256.
        started("a".repeat(300))
        mirror.deleted(300, 0)
        assertNull(mirror.textBefore(1))
        // A short field: the reading is all of it, and a delete of more than there is takes what there is.
        started("abc")
        mirror.deleted(10, 0)
        assertEquals("", mirror.textBefore(5))
    }

    @Test fun `a delete that would part the halves of an emoji drops the reading`() {
        started("hi 😀")
        // The x that [started] typed, and one half of the face.
        mirror.deleted(2, 0)
        assertNull(mirror.textBefore(1))
        // The x and both halves is a delete any app does the same way.
        val at = started("hi 😀")
        mirror.deleted(3, 0)
        mirror.reported(at - 3, at - 3, false)
        assertEquals("hi ", mirror.textBefore(96))
    }

    @Test fun `more than the reading holds is asked of the app, unless the reading is the whole field`() {
        started("a".repeat(300))
        assertEquals(96, mirror.textBefore(96)!!.length)
        assertNull(mirror.textBefore(2000))
        // A short field is known whole: a question for 2,000 characters has all of them.
        started("short")
        assertEquals("shortx", mirror.textBefore(2000))
        assertEquals("", mirror.textAfter(400))
    }

    @Test fun `a shorter reading of the same field does not take away what a longer one held`() {
        // The row's reading reaches 2,000 characters back; the undo history's own, a moment later, 64.
        val text = "word ".repeat(100)
        mirror.read(text, "", "after", text.length, text.length, 2000, 500, fromStart = true)
        val at = type("x", text.length)
        mirror.read((text + "x").takeLast(64), "", "aft", at, at, 64, 3)
        assertEquals(text + "x", mirror.textBefore(1500))
        assertEquals("after", mirror.textAfter(500))
        assertEquals(0, mirror.strikes)
        // A shorter reading that says the field starts sooner than was held is believed: something is gone.
        mirror.read("rd x", "", "", at, at, 64, 3, fromStart = true)
        assertEquals("rd x", mirror.textBefore(1500))
        // And one with the cursor somewhere else replaces everything.
        mirror.read("abc", "", "def", 3, 3, 2000, 500, fromStart = true)
        assertEquals("abc", mirror.textBefore(96))
        assertEquals("def", mirror.textAfter(500))
    }

    @Test fun `the field's stamp moves with every edit and every cursor move that was not the keys' own`() {
        var stamp = mirror.stamp
        fun moved(): Boolean = (mirror.stamp != stamp).also { stamp = mirror.stamp }
        read("ab")
        assertEquals(false, moved())
        mirror.committed("c", 1)
        assertEquals(true, moved())
        // The app saying the cursor is where that key put it is nothing new.
        mirror.reported(3, 3, false)
        assertEquals(false, moved())
        mirror.deleted(1, 0)
        assertEquals(true, moved())
        mirror.reported(2, 2, false)
        assertEquals(false, moved())
        // A tap in the text; then a report with no reading to hold it against.
        mirror.reported(0, 0, false)
        assertEquals(true, moved())
        mirror.reported(1, 1, false)
        assertEquals(true, moved())
        mirror.newField()
        assertEquals(true, moved())
    }

    @Test fun `a cursor left anywhere but straight after the text drops the reading`() {
        started("ab")
        mirror.committed("()", 0)
        assertNull(mirror.textBefore(1))
    }

    @Test fun `a guess goes stale when the keys rest`() {
        started("Hello")
        now += FieldMirror.IDLE_MS
        assertEquals("ox", mirror.textBefore(2))
        now += 1
        // Whatever the app did meanwhile is read afresh.
        assertNull(mirror.textBefore(2))
    }

    @Test fun `a reading that is not what was guessed is a strike, and two end the guessing in that field`() {
        var at = started("teh")
        // The app capitalised what was typed: the same length, the cursor where it was expected.
        read("TEHX")
        assertEquals(1, mirror.strikes)
        at = type("y", at)
        assertEquals("TEHXy", mirror.textBefore(96))
        read("TEHXY")
        assertEquals(2, mirror.strikes)
        type("z", at)
        // Twice wrong: this field is never guessed at again, however it is typed in.
        assertNull(mirror.textBefore(1))
        // Another field starts with a clean sheet.
        mirror.newField()
        started("ok")
        assertEquals("okx", mirror.textBefore(96))
        assertEquals(0, mirror.strikes)
    }

    @Test fun `a reading that is what was guessed is no strike`() {
        val at = started("Hello ", "!")
        type("y", at)
        read("Hello xy", "!")
        assertEquals(0, mirror.strikes)
        // An app that put something after the cursor as the key was typed (a bracket's pair) is a strike.
        mirror.committed("(", 1)
        read("Hello xy(", ")!")
        assertEquals(1, mirror.strikes)
    }

    @Test fun `a real answer that says otherwise drops the reading`() {
        started("Hello")
        // Asked for more than the mirror holds an answer for, and the app's answer ends differently.
        mirror.sawBefore("Jello!", 2000)
        assertNull(mirror.textBefore(1))
        assertEquals(1, mirror.strikes)
    }

    @Test fun `straight after a reading it answers on no terms at all`() {
        // Nothing typed, nothing reported, a selection in hand: but this is the field as it was just read.
        mirror.read("Hello ", "big", " world", 6, 9, 256, 400)
        assertNull(mirror.selection())
        mirror.during {
            assertEquals("big", mirror.selection())
            assertEquals("o ", mirror.textBefore(2))
            assertEquals(" ", mirror.textAfter(1))
        }
        assertNull(mirror.selection())
        // An app that gives no cursor position (an older InputConnection) is never guessed at, but is still read once a refresh.
        mirror.read("abc", "", "", -1, -1, 256, 400)
        mirror.during { assertEquals("abc", mirror.textBefore(96)) }
        mirror.committed("d", 1)
        mirror.during { assertNull(mirror.textBefore(96)) }
    }

    @Test fun `capitals are due where Android's own sum says so`() {
        val s = CapsMode.SENTENCES
        fun due(text: String, modes: Int = s) = CapsMode.of(text, true, modes) != 0
        assertEquals(true, due(""))
        assertEquals(false, due("Hello"))
        assertEquals(false, due("Hello "))
        assertEquals(true, due("Hello. "))
        assertEquals(true, due("Hello? "))
        assertEquals(true, due("Is it! "))
        // Straight after the full stop, with no space, no capital yet.
        assertEquals(false, due("Hello."))
        assertEquals(true, due("Hello.\n"))
        assertEquals(true, due("Hello\n"))
        assertEquals(true, due("Hello\n  "))
        // The sentence ended inside the quote or the bracket; and a quote just opened does not hide its start.
        assertEquals(true, due("He said \"go.\" "))
        assertEquals(true, due("(Really.) "))
        assertEquals(true, due("Hello. \""))
        assertEquals(true, due("Hello. ("))
        // An abbreviation with a full stop inside it is not a sentence's end.
        assertEquals(false, due("in the U.S. "))
        assertEquals(false, due("e.g. "))
        assertEquals(true, due("the end. "))
        // Words: a capital at each word's start. Characters: always. Neither asked for: never.
        assertEquals(true, due("Hello ", CapsMode.WORDS))
        assertEquals(false, due("Hello", CapsMode.WORDS))
        assertEquals(true, due("Hello", CapsMode.CHARACTERS))
        assertEquals(false, due("Hello. ", 0))
    }

    @Test fun `a capital that depends on text the reading does not hold is the app's to say`() {
        // The window starts in the middle of the field: its own start is not a paragraph's.
        assertNull(CapsMode.of("   ", false, CapsMode.SENTENCES))
        assertNull(CapsMode.of("\"", false, CapsMode.SENTENCES))
        // The letters before the full stop run off the window: an abbreviation's other full stop may be out there.
        assertNull(CapsMode.of("abc. ", false, CapsMode.SENTENCES))
        assertEquals(CapsMode.SENTENCES, CapsMode.of("x abc. ", false, CapsMode.SENTENCES))
        assertEquals(0, CapsMode.of("x abc ", false, CapsMode.SENTENCES))
    }

    @Test fun `the app's own word on capitals is taken until it has agreed three times where it matters`() {
        var at = started("Hi. ")
        // Mid-word there is nothing to prove: no capital is due, and the mirror says so.
        assertEquals(0, mirror.capsMode(CapsMode.SENTENCES))
        assertEquals(false, mirror.capsUnproven(0))
        repeat(3) {
            at = type(". ", at)
            val local = mirror.capsMode(CapsMode.SENTENCES)!!
            assertEquals(CapsMode.SENTENCES, local)
            assertEquals(true, mirror.capsUnproven(local))
            mirror.capsChecked(local, CapsMode.SENTENCES)
            at = type("a", at)
        }
        type(". ", at)
        assertEquals(false, mirror.capsUnproven(mirror.capsMode(CapsMode.SENTENCES)!!))
    }

    @Test fun `an app that answers capitals its own way is always asked`() {
        val at = started("Hi. ")
        type(". ", at)
        val local = mirror.capsMode(CapsMode.SENTENCES)!!
        // The app says no capital where Android's sum says one: from here it is asked every time.
        mirror.capsChecked(local, 0)
        assertNull(mirror.capsMode(CapsMode.SENTENCES))
        // In this field only.
        mirror.newField()
        started("Hi. ")
        assertEquals(0, mirror.capsMode(CapsMode.SENTENCES))
    }
}
