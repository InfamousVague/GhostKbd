package com.infamousvague.devkeyboard

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Anonymous typing reports (Matt, 2026-11-10: "allow anonymous reporting of swipe and text metrics and mistakes"):
 * what a [ReportTally] holds and what [ReportRules] lets into it, with no phone under them. The promise these hold
 * the code to is the one Settings makes: counts, where taps land, and words from the app's own lists; never a name,
 * a word of the person's own, or anything that is not letters.
 */
class ReportsTest {
    companion object {
        /** The commonest words of the app's lists here; rarer words the lists also have; and the person's own. */
        private val lists = setOf("the", "from", "form", "thank", "you", "hello", "there", "work", "in", "tomorrow", "their", "than", "then")
        private val rare = setOf("kira", "nguyen", "herpes", "kirk")
        private val own = setOf("ghostkbd", "omw")
        val rules = ReportRules(common = { it in lists }, known = { it in lists || it in rare || it in own })

        /** A drawn QWERTY: keys 100 wide, rows 120 apart. */
        const val W = 100f
        const val ROW = 120f
        val centres: Map<Char, Pair<Float, Float>> = buildMap {
            listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f).forEachIndexed { row, (s, x0) ->
                s.forEachIndexed { i, c -> put(c, ((x0 + i + 0.5f) * W) to ((row + 0.5f) * ROW)) }
            }
        }

        /** [word] tapped, each letter [dx] key widths right of and [dy] rows below the middle of its key. */
        fun taps(word: String, dx: Float = 0f, dy: Float = 0f) = Taps(
            FloatArray(word.length) { centres.getValue(word[it]).first + dx * W }, FloatArray(word.length) { centres.getValue(word[it]).second + dy * ROW }, centres, W,
        )

        fun cell(dx: Float, dy: Float) = ((dy + ReportTally.SPAN) / ReportTally.BIN).toInt() * ReportTally.GRID + ((dx + ReportTally.SPAN) / ReportTally.BIN).toInt()
    }

    @Test fun countsAreNamedAndBounded() {
        val t = ReportTally()
        t.count("words.typed"); t.count("words.typed", 4); t.count("fix.made")
        t.count("Bad Name"); t.count("x".repeat(40)); t.count("kira"); t.count("words.typed", -3); t.count("9lives")
        assertEquals(5L, t.counts["words.typed"])
        // "kira" is a well-formed name for a count; nothing the rules count is ever named by what was typed.
        assertEquals(setOf("words.typed", "fix.made", "kira"), t.counts.keys)
        t.count("big", Long.MAX_VALUE)
        assertEquals(ReportTally.MAX_COUNT, t.counts["big"])
    }

    @Test fun aWordThatStoodPutsItsTapsInTheGridByHowFarTheyLanded() {
        val t = ReportTally()
        rules.word(t, "hello", "hello", taps("hello", dx = 0.3f, dy = -0.2f))
        assertEquals(5, t.own[cell(0.3f, -0.2f)])
        assertEquals(5, t.own.sum())
        assertEquals(0, t.slip.sum())
        assertEquals(1L, t.counts["words.typed"])
        assertNull(t.counts["fix.made"])
        // Nothing of the word itself is anywhere in it.
        assertFalse(t.toJson().toString().contains("hello"))
    }

    @Test fun aLetterPutRightIsMeasuredFromTheKeyThatWasMeant() {
        val t = ReportTally()
        // "wirk" mended to "work": the i was tapped at its own middle, which is one key left of the o that was meant.
        rules.word(t, "wirk", "work", taps("wirk"))
        assertEquals(1, t.slip.sum())
        assertEquals(1, t.slip[cell(-1f, 0f)])
        assertEquals(3, t.own[cell(0f, 0f)])
        assertEquals(1L, t.counts["fix.made"])
        // Mended by more than a letter, or to two words: counted, and nothing said of its taps.
        val u = ReportTally()
        rules.word(u, "tommorow", "tomorrow", taps("tommorow"))
        rules.word(u, "inthe", "in the", taps("inthe"))
        assertEquals(0, u.own.sum() + u.slip.sum())
        assertEquals(2L, u.counts["fix.made"])
        assertEquals(1L, u.counts["fix.joined"])
        // With no taps to go by the word is still counted.
        rules.word(u, "hello", "hello", null)
        assertEquals(1L, u.counts["words.noTaps"])
    }

    @Test fun aTapFarFromEveryKeyIsOnlyCounted() {
        val t = ReportTally()
        t.tap(2.4f, 0f, slipped = false); t.tap(0f, -1.6f, slipped = true); t.tap(Float.NaN, 0f, slipped = false)
        assertEquals(0, t.own.sum() + t.slip.sum())
        assertEquals(2L, t.counts["taps.far"])
    }

    @Test fun onlyWordsTheListsHaveAreEverReported() {
        val t = ReportTally()
        rules.mistake(t, "retyped", "teh", "the")
        rules.mistake(t, "retyped", "teh", "the")
        rules.mistake(t, "picked", "thankyiu", "thank you")
        // What was meant is a name the lists have, but no common word: counted, never written. Nor an illness's.
        rules.mistake(t, "retyped", "kria", "Kira")
        rules.mistake(t, "retyped", "herpse", "herpes")
        // What was typed is a rarer word of the lists (a name typed for a common word): not written either.
        rules.mistake(t, "retyped", "kirk", "work")
        // What was typed is a word of the person's own, put right to a list word: not a mistake to tell anyone of.
        rules.mistake(t, "retyped", "omw", "now")
        rules.mistake(t, "retyped", "ghostkbd", "ghost")
        // Not letters: an address, a number, a code.
        rules.mistake(t, "retyped", "h3llo", "hello")
        rules.mistake(t, "retyped", "a@b.co", "the")
        rules.mistake(t, "retyped", "the", "the")
        assertEquals(listOf("retyped teh the 2", "picked thankyiu thank you 1"), t.mistakes.map { "${it.kind} ${it.typed} ${it.meant} ${it.n}" })
        val text = t.toJson().toString()
        for (secret in listOf("kira", "Kira", "kria", "herp", "kirk", "omw", "ghostkbd", "h3llo", "a@b.co")) assertFalse(secret, text.contains(secret))
        assertTrue((t.counts["mistakes.unlisted"] ?: 0L) >= 3L)
    }

    @Test fun aFixThatWasUndoneIsReportedOnlyBetweenTwoListWords() {
        val t = ReportTally()
        // "form" was right and was changed to "from": a real word spoiled, which is the thing most worth knowing.
        rules.undone(t, "form", "from")
        // A name changed to another word and put back: counted, and the name stays on the phone.
        rules.undone(t, "kira", "work")
        rules.undone(t, "Ghostkbd", "the")
        assertEquals(listOf("undone form from 1"), t.mistakes.map { "${it.kind} ${it.typed} ${it.meant} ${it.n}" })
        assertEquals(3L, t.counts["fix.undone"])
        assertEquals(2L, t.counts["fix.undoneOwn"])
        assertFalse(t.toJson().toString().lowercase().contains("kira"))
        assertFalse(t.toJson().toString().lowercase().contains("ghostkbd"))
    }

    @Test fun strokesAreSampledAndAMisreadPushesOutOneReadRight() {
        val path = floatArrayOf(4f, 0f, 5.5f, 1f, 2f, 0f)
        val t = ReportTally()
        repeat(25) { rules.stroke(t, "kept", listOf("the", "there"), "the", path) }
        assertEquals("one in ten of those read right", 3, t.strokes.size)
        assertEquals(25L, t.counts["words.swiped"])
        assertEquals(25L, t.counts["swipe.kept"])
        // A swipe of a word the lists lack is counted, and its path is not kept.
        rules.stroke(t, "picked", listOf("kirk", "kira"), "kira", path)
        assertEquals(3, t.strokes.size)
        // Its readings are cut to those the lists have.
        rules.stroke(t, "picked", listOf("form", "kira", "from"), "from", path)
        assertEquals(listOf("form", "from"), t.strokes.last().read)
        // Full of strokes read right, a misread still gets in; full of misreads, it is only counted.
        val full = ReportTally()
        repeat(ReportTally.MAX_STROKES * ReportTally.SAMPLE_KEPT) { rules.stroke(full, "kept", listOf("the"), "the", path) }
        assertEquals(ReportTally.MAX_STROKES, full.strokes.size)
        repeat(ReportTally.MAX_STROKES + 3) { rules.stroke(full, "picked", listOf("form", "from"), "from", path) }
        assertEquals(ReportTally.MAX_STROKES, full.strokes.size)
        assertTrue(full.strokes.all { it.kind == "picked" })
        assertEquals(3L, full.counts["strokes.more"])
        // A stroke drawn again is the earlier one over, not another word.
        val again = ReportTally()
        rules.stroke(again, "redrawn", listOf("form", "from"), "from", path)
        assertNull(again.counts["words.swiped"])
        assertEquals(1L, again.counts["swipe.redrawn"])
        assertEquals(1, again.strokes.size)
    }

    @Test fun aPathIsInKeysFromTheTopRowNeverInPixels() {
        // From q to p along the top row of a board that starts 300 pixels in and 900 down, keys 123 wide.
        val points = (0..90).map { (300f + 123f * it / 10f) to 900f }
        val path = ReportTally.pathOf(points, 300f, 900f, 123f, 119f)!!
        assertTrue(path.size <= ReportTally.MAX_POINTS * 2)
        assertEquals(0f, path[0], 0.01f); assertEquals(0f, path[1], 0.01f)
        assertEquals(9f, path[path.size - 2], 0.01f); assertEquals(0f, path[path.size - 1], 0.01f)
        assertTrue(path.all { it in -0.01f..9.01f })
        assertNull(ReportTally.pathOf(points.take(1), 0f, 0f, 123f, 119f))
        assertEquals(ROW, ReportTally.rowPitch(centres, W)!!, 0.01f)
    }

    @Test fun mistakesAndTextAreBoundedHoweverLongItWaits() {
        val t = ReportTally()
        for (i in 0 until 200) t.mistake("retyped", "w" + "a".repeat(1 + i % 20) + ('a' + i % 26), "the")
        assertEquals(ReportTally.MAX_MISTAKES, t.mistakes.size)
        assertTrue((t.counts["mistakes.more"] ?: 0L) > 0L)
        assertFalse(ReportTally.plain("a".repeat(25), spaces = false))
        assertFalse(ReportTally.plain("two words", spaces = false))
        assertTrue(ReportTally.plain("two words", spaces = true))
        assertFalse(ReportTally.plain("two  words", spaces = true))
        assertFalse(ReportTally.plain(" lead", spaces = true))
        assertTrue(ReportTally.plain("don't", spaces = false))
        assertFalse(ReportTally.plain("x", spaces = false))
        assertTrue(t.toJson().toString().length < 16_000)
    }

    @Test fun theFileRoundTripsAndWhatIsMalformedIsLeftOut() {
        val t = ReportTally()
        t.from = "2026-11-10"
        rules.word(t, "hello", "hello", taps("hello", 0.1f, 0.1f))
        rules.word(t, "wirk", "work", taps("wirk"))
        rules.mistake(t, "retyped", "teh", "the")
        rules.undone(t, "form", "from")
        rules.stroke(t, "picked", listOf("form", "from"), "from", floatArrayOf(3f, 1f, 3.5f, 0f, 8.5f, 0f, 6.5f, 2f))
        t.ms(3); t.ms(47); t.ms(9_000)
        val text = t.toJson().toString()
        val back = ReportTally.fromJson(JSONObject(text))
        assertEquals(text, back.toJson().toString())
        assertEquals(t.words, back.words)
        assertFalse(back.empty)
        assertTrue(ReportTally().empty)
        // A file someone has been at: what does not pass is dropped, the rest read.
        val bad = JSONObject(text)
        bad.getJSONArray("mistakes").put(org.json.JSONArray().put("retyped").put("p4ss").put("pass").put(1)).put("junk")
        bad.getJSONArray("strokes").put(JSONObject().put("kind", "kept").put("meant", "the").put("path", org.json.JSONArray().put(1).put(2).put(3)))
        bad.getJSONObject("counts").put("Not A Name", 5)
        bad.put("from", "last tuesday")
        val read = ReportTally.fromJson(bad)
        assertEquals(t.mistakes.size, read.mistakes.size)
        assertEquals(t.strokes.size, read.strokes.size)
        assertFalse(read.counts.containsKey("Not A Name"))
        assertEquals("", read.from)
    }

    @Test fun theReportShownIsTheReportSent() {
        // Settings shows the report set out for reading; read back as JSON it is the very report.
        val t = ReportTally()
        t.from = "2026-11-10"
        rules.word(t, "hello", "hello", taps("hello", 0.1f, 0.1f))
        rules.mistake(t, "retyped", "teh", "the")
        rules.stroke(t, "picked", listOf("form", "from"), "from", floatArrayOf(3f, 1f, 3.5f, 0f, 8.5f, 0f, 6.5f, 2f))
        val report = t.toJson().put("v", Reports.VERSION).put("app", "0.12.0").put("to", "2026-11-11").put("board", JSONObject().put("layout", "qwerty").put("condensed", true))
        val shown = Reports.pretty(report)
        assertEquals(JSONObject(report.toString()).toString(), JSONObject(shown).toString())
        // Short enough to read: a grid is twelve lines, not a hundred and forty-four.
        assertTrue(shown, shown.lines().size < 70)
        assertTrue(shown.contains("[\"retyped\", \"teh\", \"the\", 1]"))
    }

    @Test fun theCollectorTakesAReportAsTheKeyboardWritesIt() {
        // The same report, read by the server's own rules (server/reports/collector.py): the two must not drift apart.
        val dir = listOf("server/reports", "../server/reports", "../../server/reports").map(::File).firstOrNull { File(it, "collector.py").exists() } ?: return
        val python = listOf("/usr/bin/python3", "/opt/homebrew/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() } ?: return
        val t = ReportTally()
        t.from = "2026-11-10"
        rules.word(t, "hello", "hello", taps("hello", 0.1f, 0.1f))
        rules.word(t, "wirk", "work", taps("wirk"))
        rules.mistake(t, "retyped", "teh", "the")
        rules.mistake(t, "picked", "thankyiu", "thank you")
        rules.undone(t, "form", "from")
        rules.stroke(t, "picked", listOf("form", "from"), "from", floatArrayOf(3f, 1f, 3.5f, 0f, 8.5f, 0f, 6.5f, 2f))
        t.ms(12)
        val report = t.toJson().put("v", Reports.VERSION).put("app", "0.12.0").put("to", "2026-11-11")
            .put("board", JSONObject().put("layout", "qwerty").put("languages", org.json.JSONArray(listOf("en"))).put("keyDp", 44).put("rowAspect", 0.95).put("condensed", true).put("numberRow", false))
        val file = File.createTempFile("report", ".json").apply { writeText(report.toString()); deleteOnExit() }
        val code = "import collector, json, sys\nr = json.load(open(sys.argv[1]))\nc = collector.clean(json.loads(json.dumps(r)))\nassert c == r, (c, r)\nprint('ok')"
        val p = ProcessBuilder(python, "-c", code, file.absolutePath).directory(dir).redirectErrorStream(true).start()
        val said = p.inputStream.bufferedReader().readText().trim()
        assertEquals(said, 0, p.waitFor())
        assertEquals("ok", said)
        assertNotNull(report.optJSONObject("taps"))
    }
}
