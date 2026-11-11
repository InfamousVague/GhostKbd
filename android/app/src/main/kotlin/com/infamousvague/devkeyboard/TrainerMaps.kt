package com.infamousvague.devkeyboard

/**
 * What the typing trainer tells its screen (lib/trainer/), as the plain maps a method channel carries: strings,
 * whole numbers, decimals, booleans, lists and maps, and nothing else (a Char or a Float array would not cross).
 * Plain Kotlin with no Android in it, so the unit tests hold the shapes the screen reads.
 */
object TrainerMaps {
    /** A share as a whole percentage; -1 where there is none to give. */
    fun percent(share: Float): Int = if (share.isNaN()) -1 else Math.round(share * 100).coerceIn(0, 100)

    private fun round1(v: Float): Double = if (v.isNaN() || v.isInfinite()) 0.0 else Math.round(v * 10) / 10.0
    private fun round2(v: Float): Double = if (v.isNaN() || v.isInfinite()) 0.0 else Math.round(v * 100) / 100.0

    fun label(height: String): String = LayoutTrials.LABELS[height] ?: height

    fun progress(p: TrainerProgress): Map<String, Any?> =
        mapOf("fates" to p.fates.toList(), "current" to p.current, "tail" to p.tail, "done" to p.done, "wpm" to p.wpm, "ms" to p.ms)

    fun line(r: RunLine): Map<String, Any?> = mapOf(
        "at" to r.at, "title" to r.title, "practice" to r.practice, "height" to r.height, "heightLabel" to label(r.height), "trial" to r.trial,
        "ms" to r.ms, "wpm" to Math.round(r.wpm), "accuracy" to percent(r.accuracy), "words" to r.words, "right" to r.right,
    )

    fun mistake(m: TrainerMistake): Map<String, Any?> = mapOf("word" to m.word, "typed" to m.typed, "missed" to m.missed, "seen" to m.seen, "last" to m.last, "streak" to m.streak)
    fun pair(p: WeakPair): Map<String, Any?> = mapOf("pair" to p.pair, "missed" to p.missed, "n" to p.n, "trend" to p.trend)
    fun key(k: WeakKey): Map<String, Any?> = mapOf("key" to k.key.toString(), "missed" to k.missed, "n" to k.n, "toward" to k.toward?.toString(), "trend" to k.trend)

    private fun fixes(list: List<Fix>): List<Map<String, Any?>> = list.take(MAX_LISTED).map { mapOf("typed" to it.typed, "made" to it.made, "meant" to it.meant) }
    private fun misses(list: List<WordMiss>): List<Map<String, Any?>> = list.take(MAX_LISTED).map { mapOf("meant" to it.meant, "typed" to it.typed) }

    fun suggestion(s: LayoutTrials.Suggestion?): Map<String, Any?>? = s?.let {
        mapOf("height" to it.height, "label" to label(it.height), "score" to round1(it.score), "runs" to it.runs, "ownScore" to round1(it.ownScore), "ownRuns" to it.ownRuns)
    }

    /** The layout trials as they stand: where home is, what is being tried, and each height's usual score so far. */
    fun layouts(t: LayoutTrials): Map<String, Any?> = mapOf(
        "own" to t.own, "ownLabel" to label(t.own), "home" to t.home, "homeLabel" to label(t.home),
        "candidate" to t.candidate, "candidateLabel" to t.candidate?.let(::label), "settled" to t.settled,
        "scores" to LayoutTrials.HEIGHTS.filter { !t.scores[it].isNullOrEmpty() }.map { h -> mapOf("height" to h, "label" to label(h), "runs" to t.scores.getValue(h).size, "score" to round1(LayoutTrials.median(t.scores.getValue(h)))) },
        "suggestion" to suggestion(t.suggestion()),
    )

    fun profile(p: TrainerProfile, file: String): Map<String, Any?> = mapOf(
        "runs" to p.runs, "wpmFirst" to Math.round(p.wpmFirst), "wpmNow" to Math.round(p.wpmNow), "wpmBest" to Math.round(p.wpmBest),
        "accuracyFirst" to percent(p.accuracyFirst), "accuracyNow" to percent(p.accuracyNow), "lean" to p.lean, "report" to p.report, "layout" to p.layout,
        "keys" to p.keys.map { mapOf("key" to it.key.toString(), "dx" to round2(it.dx), "dy" to round2(it.dy), "samples" to it.samples) },
        "slips" to p.slips.map { mapOf("pressed" to it.pressed.toString(), "meant" to it.meant.toString(), "count" to it.count) },
        "mistakes" to p.mistakes.map(::mistake), "pairs" to p.pairs.map(::pair), "suggestion" to suggestion(p.suggestion), "file" to file,
    )

    /**
     * A run as its results page shows it. [nudged] are the keys whose touch areas the run moved, each with where its
     * nudge now stands; [taught] is how many of its mistakes went to "Your corrections".
     */
    fun result(r: RunResult, nudged: Map<Char, TrainerAims.Aim>, taught: Int): Map<String, Any?> {
        // The keys and pairs this run went wrong on most: enough taps to say, and at least one wrong.
        val keys = r.perKey.entries.filter { it.value.missed > 0 }.sortedWith(compareBy({ -it.value.missed }, { it.key })).take(5).map { (key, stat) ->
            val toward = r.slipPairs.entries.filter { it.key.length == 3 && it.key[2] == key }.maxByOrNull { it.value }?.key?.get(0)
            mapOf("key" to key.toString(), "missed" to stat.missed, "n" to stat.taps, "toward" to toward?.toString())
        }
        val pairs = r.perPair.entries.filter { it.value.misses > 0 }.sortedWith(compareBy({ -it.value.misses }, { it.key })).take(5).map { mapOf("pair" to it.key, "missed" to it.value.misses, "n" to it.value.taps) }
        return mapOf(
            "counted" to r.counted, "ms" to r.ms, "wpm" to Math.round(r.wpm), "gross" to Math.round(r.gross), "accuracy" to percent(r.accuracy),
            "words" to r.words, "right" to r.right, "firstTry" to r.firstTry, "typed" to (r.words - r.skipped.size),
            "missed" to misses(r.missed), "skipped" to r.skipped.take(MAX_LISTED), "extraWords" to r.extraWords,
            "keys" to r.keys, "backspaces" to r.backspaces,
            "mended" to fixes(r.mended), "spoiled" to fixes(r.spoiled), "misfixed" to fixes(r.misfixed), "left" to misses(r.left), "undone" to r.undone,
            "swiped" to r.swiped, "picked" to r.picked, "spacesMissed" to r.spacesMissed, "straySpaces" to r.straySpaces,
            "taps" to r.taps, "hits" to r.hits, "slips" to r.slips, "wrongKeys" to r.wrongKeys, "extraTaps" to r.extraTaps, "omitted" to r.omitted, "swaps" to r.swaps,
            "weakKeys" to keys, "weakPairs" to pairs,
            "nudged" to nudged.entries.sortedBy { it.key }.map { mapOf("key" to it.key.toString(), "dx" to round2(it.value.dx), "dy" to round2(it.value.dy)) },
            "taught" to taught,
        )
    }

    /** No list on a results page is longer than this. */
    const val MAX_LISTED = 12
}
