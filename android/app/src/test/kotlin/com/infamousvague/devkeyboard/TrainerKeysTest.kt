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
import java.io.File
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
        // And the calls it has no part in, which a class that only stood in the way would swallow: the ones the
        // keyboard's listener gives a body of its own (a stroke, a layout) most of all.
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

    /** The mic key gives the full stop its key back for a run (DevKeyboardService.applySettings): a story is typed, not spoken. */
    @Test fun aRunIsBeingTypedOnlyInThisAppsOwnFieldAndOnlyWhileItIsOn() {
        assertFalse("no run is on", Trainer.typing("own", "own"))
        run("the cat.")
        assertTrue(Trainer.typing("own", "own"))
        assertFalse("never in another app", Trainer.typing("com.whatsapp", "own"))
        assertFalse(Trainer.typing(null, "own"))
        Trainer.hold(null)
        assertFalse("the run is over", Trainer.typing("own", "own"))
    }

    @Test fun aTrialLayoutAndAFixAreWornOnlyByTheirRunInThisAppsOwnField() {
        assertNull(Trainer.heightFor("own", "own")); assertNull(Trainer.fixFor("own", "own"))
        val fix = mapOf('e' to (0.08f to -0.04f), 'o' to (0f to 0f))
        Trainer.hold(Trainer.Session("s-1", "A story", 1, 1, "the cat.", practice = false, height = "short", trial = true, ownHeight = "normal", layout = "qwerty", fix = fix, fixNumber = 3, differs = setOf('e')))
        assertEquals("short", Trainer.heightFor("own", "own"))
        Trainer.fixFor("own", "own")!!.let { worn ->
            assertEquals(0.08f to -0.04f, worn('e')); assertEquals("a capital is its key", 0.08f to -0.04f, worn('E'))
            assertEquals(0f to 0f, worn('o')); assertEquals("a key the fix does not name listens at its middle", 0f to 0f, worn('q'))
        }
        assertNull("never in another app", Trainer.heightFor("com.whatsapp", "own")); assertNull(Trainer.fixFor("com.whatsapp", "own"))
        assertNull(Trainer.heightFor(null, "own")); assertNull(Trainer.fixFor(null, "own"))
        // A trial run on the person's own height wears nothing, and a run typed on no fix has the keys as they are.
        Trainer.hold(Trainer.Session("s-2", "A story", 1, 1, "the cat.", practice = false, height = "normal", trial = true, ownHeight = "normal", layout = "qwerty"))
        assertNull(Trainer.heightFor("own", "own")); assertNull(Trainer.fixFor("own", "own"))
        Trainer.hold(Trainer.Session("s-3", "A story", 1, 1, "the cat.", practice = false, height = "normal", trial = false, ownHeight = "normal", layout = "qwerty", fix = fix, fixNumber = 3, differs = setOf('e')))
        // The run dropped: the keyboard is told, and nothing is worn.
        applied = 0
        Trainer.leave(told = true)
        assertEquals(1, applied); assertNull(Trainer.session); assertNull(Trainer.heightFor("own", "own")); assertNull(Trainer.fixFor("own", "own"))
        Trainer.leave(told = true)
        assertEquals("nothing to drop, nothing to tell", 1, applied)
        assertTrue(keys.listed("the")); assertFalse(keys.listed("teh"))
    }

    @Test fun theTrainerHasNoHandOnWhatAStoryMustNeverTeach() {
        // A story is not the person's writing. The trainer's field asks keyboards not to learn from it, and is one of
        // this app's own, which next-word prediction, Mockingbird, typing stats, the accuracy record and anonymous
        // reports never learn from. This holds the other half: nothing of the trainer's own calls any of them, keeps a
        // learned word, or opens a connection. Comments aside: they say what is not done.
        val dir = listOf("android/app/src/main/kotlin/com/infamousvague/devkeyboard", "src/main/kotlin/com/infamousvague/devkeyboard", "../app/src/main/kotlin/com/infamousvague/devkeyboard").map(::File).first { it.exists() }
        val sources = dir.listFiles()!!.filter { it.name.startsWith("Trainer") || it.name == "LayoutTrials.kt" }
        assertEquals("every file of the trainer's", 12, sources.size)
        val never = listOf("NextWords", "NextWordLearner", "Mockingbird", "TypingStats", "AccuracyLog", "Reports", "ReportTally", "learnedWords", ".learn(word", "prefs.learn(", "Net.", "NetPath", "openConnection", "URL(", "Socket(")
        for (file in sources) {
            val code = file.readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }
            for (word in never) assertFalse("${file.name} has to do with $word", code.contains(word))
        }
        // What it does hand on, it hands on in one place each: a fix the person took, into the key model, and the
        // corrections' lesson. The model is written nowhere else: a run only ever wears a fix.
        val wiring = File(dir, "Trainer.kt").readText()
        assertTrue(wiring.contains("model.trained(") && wiring.contains("memory.learn(t.typed, t.meant)") && wiring.contains("prefs.learnMistakes"))
        val applying = wiring.substring(wiring.indexOf("fun apply("), wiring.indexOf("fun again("))
        assertTrue("a fix is taken only where it is proven", applying.contains("FixTrial.Verdict.PROVEN"))
        // Outside taking a fix, the model is written only to take back what was given, when everything is forgotten.
        assertEquals(1, Regex("model\\.trained\\(").findAll(wiring.replace(applying, "")).count())
        assertTrue(wiring.substring(wiring.indexOf("fun forget(")).contains("model.trained(key, 0, back.dx, back.dy)"))
        for (file in sources.filter { it.name != "Trainer.kt" }) assertFalse(file.name, file.readText().let { it.contains("Mistakes.get(") || it.contains("AdaptiveKeys.get(") })
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
        plain(TrainerMaps.result(r, mapOf('o' to (0.04f to -0.01f)), 1), "result")
        plain(TrainerMaps.progress(t.run.progress(t.at)), "progress")
        val book = TrainerBook()
        repeat(6) { book.add(RunLine(1L + it, "s-$it", "A story", false, "normal", it % 2 == 0, r.ms, r.wpm, if (it == 0) Float.NaN else r.accuracy, r.words, r.right, r.firstTry, r.backspaces, r.mended.size, r.spoiled.size), r, "qwerty") }
        book.layouts.anchor("normal"); repeat(8) { book.layouts.record(book.layouts.next(), 40f + it) }
        plain(book.runs.map(TrainerMaps::line), "history"); plain(TrainerMaps.layouts(book.layouts, book.runs), "layouts")
        // The test of the fixes: none yet, one in hand, and one with runs behind it.
        plain(TrainerMaps.fix(null, false, 0), "fix")
        val fixes = FixTrial()
        fixes.begin(false, { 0f to 0f }, Layouts.QWERTY.positions.keys, mapOf('e' to TrainerAims.Measure(60, 0.25f, 0.2f, 60)))
        for (run in listOf(FixTrial.FixRun(1, 170, 157, 165, 9, 1), FixTrial.FixRun(1, 172, 158, 167, 10, 1), FixTrial.FixRun(1, 168, 155, 166, 12, 1))) fixes.record(run)
        val told = TrainerMaps.fix(fixes, false, 1)
        plain(told, "fix"); plain(TrainerMaps.fixRun(fixes.runs.last()), "fixRun")
        assertEquals("the fix the runs tested is the one a switch takes", 1, told["last"]); assertEquals(0, TrainerMaps.fix(null, false, 0)["last"])
        assertEquals("proven", told["verdict"]); assertEquals(3, told["runs"]); assertEquals(31, told["won"]); assertEquals(3, told["lost"]); assertEquals(1, told["differs"])
        assertEquals("of 510 taps, to a tenth of a percent", 92.2, told["asIsShare"]); assertEquals(97.6, told["fixedShare"])
        assertTrue("chance gives a split like 31 to 3 far less than once in a hundred", (told["oneIn"] as Int) > 100_000)
        assertEquals("none", TrainerMaps.fix(null, false, 0)["verdict"]); assertEquals(0, TrainerMaps.fix(null, false, 0)["oneIn"])
        assertEquals(false, told["next"]); assertEquals(true, TrainerMaps.fix(null, false, 0, next = true)["next"]); assertNull(told["hold"])
        assertEquals("what keeps a fix from proven is said", "runs", TrainerMaps.fix(FixTrial().also { it.begin(false, { 0f to 0f }, Layouts.QWERTY.positions.keys, mapOf('e' to TrainerAims.Measure(60, 0.25f, 0.2f, 60))); it.record(FixTrial.FixRun(1, 170, 157, 165, 9, 1)) }, false, 1)["hold"])
        assertEquals("a verdict the run left, where the trial has since moved on", "worse", TrainerMaps.fix(fixes, false, 1, FixTrial.Verdict.WORSE)["verdict"])
        // A run's line says which fix it tested and what the keyboard as it is would have made of its taps; one that tested nothing says neither.
        assertEquals(0, TrainerMaps.line(book.runs[1])["fix"]); assertEquals(-1, TrainerMaps.line(book.runs[1])["asIs"])
        TrainerMaps.line(RunLine(9L, "s-9", "A story", false, "normal", false, r.ms, r.wpm, 0.96f, r.words, r.right, r.firstTry, 0, 0, 0, fix = 4, asIs = 0.915f)).let { assertEquals(4, it["fix"]); assertEquals(92, it["asIs"]); assertEquals(96, it["accuracy"]) }
        // Each height's taps on their key are said beside its score.
        (TrainerMaps.layouts(book.layouts, book.runs)["scores"] as List<*>).filterIsInstance<Map<*, *>>().let { rows -> assertTrue(rows.isNotEmpty()); assertTrue(rows.all { it["accuracy"] is Int }) }
        plain(book.mistakes().map(TrainerMaps::mistake) + book.weakPairs().map(TrainerMaps::pair) + book.weakKeys("qwerty").map(TrainerMaps::key), "mistakes")
        val p = book.profile("qwerty", Layouts.QWERTY.positions)!!
        plain(TrainerMaps.profile(p, TrainerCodec.profile(p, "now")), "profile")
        assertEquals(-1, TrainerMaps.line(book.runs[0])["accuracy"]); assertEquals("Normal", TrainerMaps.line(book.runs[0])["heightLabel"])
        assertEquals(listOf(mapOf("key" to "o", "missed" to 1, "n" to 1, "toward" to "p")), TrainerMaps.result(r, emptyMap(), 0)["weakKeys"])
    }
}
