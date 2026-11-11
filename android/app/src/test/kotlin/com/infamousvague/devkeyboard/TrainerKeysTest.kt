package com.infamousvague.devkeyboard

import android.graphics.PointF
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The typing trainer's ear on the keyboard ([TrainerKeys]): with no run on it passes every key on and touches
 * nothing; with one on, in this app's own field, it writes down each key and the text the key left, and what changed
 * the text between keys. A stand-in for the service types into a stand-in for the field.
 */
class TrainerKeysTest {
    /** The service, as far as the keys are concerned: it types into [text], with a fix where [fixes] has one, and counts what else it was asked. */
    private class Service : KeyboardListener {
        var text = ""
        val fixes = HashMap<String, String>()
        val asked = ArrayList<String>()

        private fun end(mark: String) {
            val word = TrainerText.tail(text)
            fixes[word]?.let { text = text.dropLast(word.length) + it }
            text += mark
        }

        override fun onText(text: String, caretBack: Int) { if (text.all(TrainerText::isWordChar)) this.text += text else end(text) }
        override fun onDelete() { text = text.dropLast(1) }
        override fun onSpace() = end(" ")
        override fun onEnter() { asked += "enter" }
        override fun onCursor(steps: Int) { asked += "cursor $steps" }
        override fun onShiftChanged() { asked += "shift" }
        override fun onKeyFeedback(key: Key, haptic: Boolean) { asked += "feedback" }
        override fun onShowPicker() { asked += "picker" }
        override fun onOpenSettings() { asked += "settings" }
        override fun onHideKeyboard() { asked += "hide" }
        override fun onMic() { asked += "mic" }
        override fun onSwipe(path: List<PointF>, keys: Map<Char, PointF>, keyWidth: Float) { asked += "swipe" }
        override fun onDeleteWord() { text = text.trimEnd().substringBeforeLast(' ', "").let { if (it.isEmpty()) "" else "$it " } }
        override fun onCursorLine(lines: Int) { asked += "line $lines" }
        override fun onDock(side: String, wide: Boolean) { asked += "dock $side" }
        override fun onLayout(id: String) { asked += "layout $id" }
    }

    private val service = Service()
    private var reads = 0
    private var applied = 0
    private var fieldPackage = "own"

    /** The field: all an input method's ear needs of it is its text either side of the cursor. */
    private val connection = Proxy.newProxyInstance(InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)) { _, method, _ ->
        when (method.name) {
            "getTextBeforeCursor" -> { reads++; service.text }
            "getTextAfterCursor" -> ""
            else -> null
        }
    } as InputConnection

    private val keys = TrainerKeys(service, "own", { null }, { connection }, { EditorInfo().apply { packageName = fieldPackage } }, { it == "the" }) { applied++ }

    private fun run(story: String) = Trainer.Session("s-1", "A story", 1, 1, story, practice = false, height = "normal", trial = false, ownHeight = "normal", layout = "qwerty").also { Trainer.hold(it) }

    private var now = 5_000L

    @Before fun aClockOfItsOwn() { Trainer.clock = { now += 150; now } }

    @After fun noRunLeftOn() = Trainer.hold(null)

    private fun type(word: String) = word.forEach { keys.onText(it.toString(), 0) }

    @Test fun withNoRunOnEveryKeyGoesStraightOnAndTheFieldIsNeverRead() {
        type("hi"); keys.onSpace(); keys.onDelete(); keys.onEnter(); keys.onDeleteWord()
        keys.onSwipe(emptyList(), emptyMap(), 1f)
        // And the calls it has no part in, which a class that only stood in the way would swallow.
        keys.onCursor(2); keys.onShiftChanged(); keys.onShowPicker(); keys.onOpenSettings(); keys.onHideKeyboard(); keys.onMic(); keys.onCursorLine(-1); keys.onDock("left", true); keys.onLayout("azerty")
        keys.onKeyFeedback(Key(KeyType.SPACE, " "), true)
        assertEquals("", service.text)
        assertEquals(listOf("enter", "swipe", "cursor 2", "shift", "picker", "settings", "hide", "mic", "line -1", "dock left", "layout azerty", "feedback"), service.asked)
        assertEquals("the field is not looked at when no run is on", 0, reads)
        keys.look()
        assertEquals(0, reads)
    }

    @Test fun aRunIsWrittenDownKeyByKeyWithTheTextEachKeyLeft() {
        val s = run("the cat.")
        service.fixes["teh"] = "the"
        val heard = ArrayList<Map<String, Any?>>()
        Trainer.onProgress = { heard += it }
        type("teh"); keys.onSpace(); type("cay"); keys.onDelete(); type("t"); keys.onText(".", 0)
        Trainer.onProgress = null
        assertEquals("the cat.", service.text)
        assertEquals(listOf("t", "te", "teh", "the ", "the c", "the ca", "the cay", "the ca", "the cat", "the cat."), s.recorder.steps.map { it.text })
        assertEquals(listOf(KeyKind.CHAR, KeyKind.CHAR, KeyKind.CHAR, KeyKind.SPACE, KeyKind.CHAR, KeyKind.CHAR, KeyKind.CHAR, KeyKind.BACKSPACE, KeyKind.CHAR, KeyKind.CHAR), s.recorder.steps.map { it.kind })
        // The screen was told after every key, and that the story was done at its full stop.
        assertEquals(10, heard.size); assertEquals(true, heard.last()["done"]); assertEquals(false, heard[8]["done"])
        assertEquals(listOf(1, 1), heard.last()["fates"])
        val r = TrainerScore.of(s.text, s.recorder.steps, Layouts.QWERTY.positions)
        assertEquals(2, r.right); assertEquals(listOf("teh"), r.mended.map { it.typed }); assertEquals(1, r.backspaces)
        // No view under it, so no tap is known: the letters count for nothing of the aim, and nothing is made up.
        assertTrue(s.recorder.steps.all { it.tap == null }); assertEquals(0, r.taps)
    }

    @Test fun whatChangesTheTextBetweenKeysIsSeenAtTheNextKeyOrWhenTheScreenSaysSo() {
        val s = run("the cat sat.")
        type("the"); keys.onSpace()
        // A word tapped in the row: no key of the keyboard's typed it.
        service.text += "cat "
        keys.look()
        assertEquals(KeyKind.SEEN, s.recorder.steps.last().kind); assertEquals("the cat ", s.recorder.steps.last().text)
        // And one that nobody said anything about is still seen before the next key is written down.
        service.text += "sa"
        keys.onText("t", 0)
        assertEquals(listOf("the cat sa", "the cat sat"), s.recorder.steps.takeLast(2).map { it.text })
        assertEquals(listOf(KeyKind.SEEN, KeyKind.CHAR), s.recorder.steps.takeLast(2).map { it.kind })
    }

    @Test fun aRunIsOnlyHeardInThisAppsOwnField() {
        val s = run("the cat.")
        fieldPackage = "another.app"
        type("the"); keys.onSpace()
        assertEquals("the ", service.text)
        assertTrue("typed in another app: none of it is the run's", s.recorder.steps.isEmpty())
        assertEquals(0, reads)
        assertNull(Trainer.heightFor("another.app", "own"))
    }

    @Test fun aTrialLayoutIsWornOnlyByItsRunInThisAppsOwnField() {
        assertNull(Trainer.heightFor("own", "own")); assertFalse(Trainer.adapts("own", "own"))
        Trainer.hold(Trainer.Session("s-1", "A story", 1, 1, "the cat.", practice = false, height = "short", trial = true, ownHeight = "normal", layout = "qwerty"))
        assertEquals("short", Trainer.heightFor("own", "own")); assertTrue(Trainer.adapts("own", "own"))
        assertNull("never in another app", Trainer.heightFor("com.whatsapp", "own")); assertFalse(Trainer.adapts("com.whatsapp", "own")); assertNull(Trainer.heightFor(null, "own"))
        // A trial run on the person's own height wears nothing.
        Trainer.hold(Trainer.Session("s-2", "A story", 1, 1, "the cat.", practice = false, height = "normal", trial = true, ownHeight = "normal", layout = "qwerty"))
        assertNull(Trainer.heightFor("own", "own"))
        // The run dropped: the keyboard is told, and nothing is worn.
        applied = 0
        Trainer.leave(told = true)
        assertEquals(1, applied); assertNull(Trainer.session); assertNull(Trainer.heightFor("own", "own"))
        Trainer.leave(told = true)
        assertEquals("nothing to drop, nothing to tell", 1, applied)
        assertTrue(keys.listed("the")); assertFalse(keys.listed("teh"))
    }

    @Test fun whatTheScreenIsToldCrossesAChannel() {
        // A method channel carries strings, numbers, booleans, lists and maps: nothing else may be in what the trainer says.
        fun plain(v: Any?, where: String) {
            when (v) {
                null, is String, is Int, is Long, is Double, is Boolean -> Unit
                is List<*> -> v.forEachIndexed { i, e -> plain(e, "$where[$i]") }
                is Map<*, *> -> v.forEach { (k, e) -> assertTrue("$where: a key that is no string", k is String); plain(e, "$where.$k") }
                else -> throw AssertionError("$where is a ${v::class.simpleName}, which no channel carries")
            }
        }
        val t = TrainerTest.Typist("because of their friend.", gap = 300L).type("becuase").space(fix = "because").tap('p', fx = -0.4f).tap('f').space().type("their").space().type("friend").mark('.')
        val r = t.score()
        plain(TrainerMaps.result(r, mapOf('o' to TrainerAims.Aim(0.04f, -0.01f)), 1), "result")
        plain(TrainerMaps.progress(t.run.progress(t.at)), "progress")
        val book = TrainerBook()
        repeat(6) { book.add(RunLine(1L + it, "s-$it", "A story", false, "normal", it % 2 == 0, r.ms, r.wpm, if (it == 0) Float.NaN else r.accuracy, r.words, r.right, r.firstTry, r.backspaces, r.mended.size, r.spoiled.size), r, "qwerty") }
        book.layouts.anchor("normal"); repeat(8) { book.layouts.record(book.layouts.next(), 40f + it) }
        plain(book.runs.map(TrainerMaps::line), "history"); plain(TrainerMaps.layouts(book.layouts), "layouts")
        plain(book.mistakes().map(TrainerMaps::mistake) + book.weakPairs().map(TrainerMaps::pair) + book.weakKeys("qwerty").map(TrainerMaps::key), "mistakes")
        val p = book.profile("qwerty", Layouts.QWERTY.positions)!!
        plain(TrainerMaps.profile(p, TrainerCodec.profile(p, "now")), "profile")
        assertEquals(-1, TrainerMaps.line(book.runs[0])["accuracy"]); assertEquals("Normal", TrainerMaps.line(book.runs[0])["heightLabel"])
        assertEquals(listOf(mapOf("key" to "o", "missed" to 1, "n" to 1, "toward" to "p")), TrainerMaps.result(r, emptyMap(), 0)["weakKeys"])
    }
}
