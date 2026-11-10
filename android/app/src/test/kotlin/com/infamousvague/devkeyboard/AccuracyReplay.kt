package com.infamousvague.devkeyboard

import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Replays an accuracy record (AccuracyLog, saved to Downloads from Settings › Typing stats) through autocorrect and
 * the swipe decoder as they are in this tree, and scores them against what was kept. Run by tool/accuracy-replay.sh;
 * does nothing unless given a record (-PaccuracyLog=<file>).
 *
 * The truth for a word is what stood: autocorrect's fix if it was kept, the spelling typed if the fix was undone or
 * the quoted word tapped, the suggestion picked, or the word typed after deleting it. A word deleted with nothing
 * after it says nothing and is skipped. For a swipe: the first reading if it stood, the one picked from the row, or
 * what was typed after deleting it.
 *
 * "Live" is what the phone did when it was recorded (with next-word context and your learned words); "replay" is this
 * tree with neither, so compare replays with each other, run before and after a change.
 */
class AccuracyReplay {
    private class Miss(val kind: String, val typed: String, val got: String, val truth: String)

    @Test
    fun replay() {
        val path = System.getProperty("accuracyLog").orEmpty()
        if (path.isEmpty()) return
        val log = File(path)
        require(log.exists()) { "No record at $path" }
        val assets = File("src/main/assets")
        val ac = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
        ac.setSlang(true)

        var loaded = ""
        var keys: Map<Char, Pair<Float, Float>> = emptyMap()
        var keyW = 0f
        var words = 0; var liveRight = 0; var replayRight = 0; var wrongFix = 0; var missed = 0; var fixesMade = 0; var fixesKept = 0
        var swipes = 0; var swipeLive = 0; var swipeTop1 = 0; var swipeTop3 = 0
        val misses = ArrayList<Miss>()

        log.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val o = JSONObject(line)
            when (o.optString("t")) {
                "layout" -> {
                    val id = o.getString("id")
                    val dicts = o.getJSONArray("dicts").let { a -> (0 until a.length()).map { a.getString(it) } }
                    val key = "$id|$dicts"
                    if (key != loaded) {
                        val spec = Layouts.spec(id) ?: error("Unknown layout $id")
                        ac.loadNow(dicts, Board.of(spec, english = "en" in dicts))
                        loaded = key
                    }
                    val k = o.getJSONObject("keys")
                    keys = k.keys().asSequence().associate { c -> val a = k.getJSONArray(c); c[0] to (a.getDouble(0).toFloat() to a.getDouble(1).toFloat()) }
                    keyW = o.getDouble("keyW").toFloat()
                }
                "word" -> {
                    val typed = o.getString("typed")
                    val live = if (o.isNull("auto")) typed else o.getString("auto")
                    val truth = truth(o.optString("out"), live, typed) ?: return@forEachLine
                    val got = ac.correct(typed) ?: typed
                    words++
                    if (live.equals(truth, true)) liveRight++
                    if (got.equals(truth, true)) replayRight++
                    if (!got.equals(typed, true)) { fixesMade++; if (got.equals(truth, true)) fixesKept++ }
                    when {
                        got.equals(truth, true) -> Unit
                        truth.equals(typed, true) -> { wrongFix++; misses += Miss("wrong fix", typed, got, truth) }
                        got.equals(typed, true) -> { missed++; misses += Miss("missed", typed, got, truth) }
                        else -> misses += Miss("other word", typed, got, truth)
                    }
                }
                "swipe" -> {
                    val choices = o.getJSONArray("choices").let { a -> (0 until a.length()).map { a.getString(it) } }
                    val truth = truth(o.optString("out"), choices.firstOrNull() ?: return@forEachLine, null) ?: return@forEachLine
                    val pts = o.getJSONArray("path").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { p -> p.getDouble(0).toFloat() to p.getDouble(1).toFloat() } } }
                    val before = if (o.isNull("before")) null else o.getString("before")
                    val got = ac.swipePlain(pts, keys, keyW, before)
                    swipes++
                    if (choices.firstOrNull().equals(truth, true)) swipeLive++
                    if (got.firstOrNull().equals(truth, true)) swipeTop1++
                    if (got.take(3).any { it.equals(truth, true) }) swipeTop3++
                    if (!got.firstOrNull().equals(truth, true)) misses += Miss("swipe", "(path)", got.firstOrNull() ?: "nothing", truth)
                }
            }
        }

        fun pct(n: Int, of: Int) = if (of == 0) "—" else "%.1f%%".format(100.0 * n / of)
        val report = buildString {
            appendLine("Accuracy replay of ${log.name}")
            appendLine()
            appendLine("Typed words: $words with a known outcome")
            appendLine("  right, live (on the phone):  ${pct(liveRight, words)}")
            appendLine("  right, replay (this tree):   ${pct(replayRight, words)}")
            appendLine("  fixes made in replay: $fixesMade, right ${pct(fixesKept, fixesMade)}")
            appendLine("  wrong fixes (changed a word that was meant): $wrongFix")
            appendLine("  missed (left a slip that was fixed by hand):  $missed")
            appendLine()
            appendLine("Swipes: $swipes with a known outcome")
            appendLine("  first reading right, live:   ${pct(swipeLive, swipes)}")
            appendLine("  first reading right, replay: ${pct(swipeTop1, swipes)}")
            appendLine("  in the top three, replay:    ${pct(swipeTop3, swipes)}")
            if (misses.isNotEmpty()) {
                appendLine()
                appendLine("Where replay got it wrong (first 40):")
                misses.take(40).forEach { appendLine("  ${it.kind}: ${it.typed} → ${it.got}, meant ${it.truth}") }
            }
        }
        File("build/accuracy-report.txt").apply { parentFile?.mkdirs() }.writeText(report)
        println(report)
    }

    /** What was meant, from what was kept: [out] as AccuracyLog writes it; null when it can't be known. */
    private fun truth(out: String, live: String, typed: String?): String? = when {
        out == "kept" -> live
        out == "undone" || out == "typed" -> typed
        out.startsWith("picked:") || out.startsWith("retyped:") -> out.substringAfter(':').trim().ifEmpty { null }
        else -> null
    }
}
