package com.infamousvague.devkeyboard

/**
 * The typing trainer's layout trials (docs/trainer.md; Matt, 2026-11-11: "try different layouts like more condensed
 * or less condensed", and "we should only do the condensed vs expanded views and shifting the UI majorly if the
 * setting is enabled when we start the trainer"). Plain Kotlin with no Android in it.
 *
 * Only with the trainer's "Try different layouts" switch on, as it stood when a run started, may a run be typed on
 * keys of another height than the person's own. This says which, and what the runs so far add up to.
 *
 * The heights stand in a line, [HEIGHTS], most condensed first. One of them is [home]: the best found so far, which
 * starts as the person's [own] setting. A trial holds home against one neighbour (a step more condensed, or a step
 * less), run about: home, the neighbour, home, the neighbour. It ends when:
 * - the neighbour has scored higher in three of every four pairs or more, and its usual (median) score is [MARGIN]
 *   (six in a hundred) better: it becomes home, one step and no further;
 * - it has lost that clearly, or [MAX_PAIRS] pairs have not settled it: it is let go, and home stays.
 * Never before [MIN_PAIRS] pairs (eight runs), so one noisy run decides nothing. Then the other neighbour is tried, and when
 * neither does better the trials are [settled].
 *
 * Nothing here changes the person's setting. A trial layout is worn by the keyboard only for the run that asked for
 * it (Trainer.kt), and [suggestion] is an offer.
 */
class LayoutTrials {
    /** The person's own key height as it last was, and the best height found from there. */
    var own = "normal"
        private set
    var home = "normal"
        private set

    /** The neighbour being held against [home], or null between trials. */
    var candidate: String? = null
        private set

    /** Heights that were tried from this [home] and did no better. */
    val rejected = LinkedHashSet<String>()

    /** The trial in hand: each side's scores, in the order they were run. */
    val homeScores = ArrayList<Float>()
    val candidateScores = ArrayList<Float>()

    /** Every height's latest scores (at most [KEPT]), for what the profile says of them. */
    val scores = LinkedHashMap<String, ArrayList<Float>>()

    /** A height taking over from another. */
    class Move(val from: String, val to: String)

    /** The height the runs point to in place of the person's own, with what they measured on each. */
    class Suggestion(val height: String, val score: Float, val runs: Int, val ownScore: Float, val ownRuns: Int)

    /**
     * The person's own height, told before each run. Where they have changed it by hand the trials start again from
     * it: their choice is the new home. Taking the trials' own suggestion changes nothing here.
     */
    fun anchor(height: String) {
        val h = if (height in HEIGHTS) height else "normal"
        if (h == own) return
        own = h
        if (h == home) return
        home = h
        clearTrial()
        rejected.clear()
    }

    private fun clearTrial() { candidate = null; homeScores.clear(); candidateScores.clear() }

    private fun neighbours(): List<String> {
        val at = HEIGHTS.indexOf(home)
        return listOfNotNull(HEIGHTS.getOrNull(at - 1), HEIGHTS.getOrNull(at + 1)).filter { it !in rejected }
    }

    /** Neither neighbour of [home] is left to try: the trials have their answer, until [again]. */
    val settled: Boolean get() = candidate == null && neighbours().isEmpty()

    /**
     * The height the next trial run is typed on: [home] first, then the neighbour, turn about. Chooses the neighbour
     * to try where none is in hand: the one tried least, the more condensed where they are level.
     */
    fun next(): String {
        if (candidate == null) candidate = neighbours().minByOrNull { (scores[it]?.size ?: 0) * 10 + HEIGHTS.indexOf(it) }
        val c = candidate ?: return home
        return if (homeScores.size <= candidateScores.size) home else c
    }

    /** A counted run on [height] came to [score]. Says so where that ended a trial with a move. */
    fun record(height: String, score: Float): Move? {
        if (height !in HEIGHTS || score.isNaN() || score <= 0f) return null
        scores.getOrPut(height) { ArrayList() }.let { it += score; while (it.size > KEPT) it.removeAt(0) }
        when (height) {
            home -> homeScores += score
            candidate -> candidateScores += score
            else -> return null
        }
        return decide()
    }

    private fun decide(): Move? {
        val c = candidate ?: return null
        val pairs = minOf(homeScores.size, candidateScores.size)
        if (pairs < MIN_PAIRS) return null
        var wins = 0
        var losses = 0
        for (i in 0 until pairs) { if (candidateScores[i] > homeScores[i]) wins++ else if (candidateScores[i] < homeScores[i]) losses++ }
        val h = median(homeScores.take(pairs))
        val n = median(candidateScores.take(pairs))
        if (wins * 4 >= pairs * 3 && n >= h * (1f + MARGIN)) {
            val from = home
            home = c
            clearTrial()
            rejected.clear()
            // Home was just beaten by this one: it is not tried again from here.
            rejected += from
            return Move(from, c)
        }
        if ((losses * 4 >= pairs * 3 && n <= h * (1f - MARGIN)) || pairs >= MAX_PAIRS) {
            rejected += c
            clearTrial()
        }
        return null
    }

    /** Every height open to trial again from [home], as it stands. */
    fun again() { rejected.clear(); clearTrial() }

    /** The trials as they were kept (TrainerCodec): heights this keyboard does not have are dropped. */
    fun restore(own: String, home: String, candidate: String?, rejected: Collection<String>, homeScores: List<Float>, candidateScores: List<Float>, scores: Map<String, List<Float>>) {
        this.own = own.takeIf { it in HEIGHTS } ?: "normal"
        this.home = home.takeIf { it in HEIGHTS } ?: this.own
        this.candidate = candidate?.takeIf { it in HEIGHTS && it != this.home }
        this.rejected.clear(); this.rejected += rejected.filter { it in HEIGHTS }
        this.homeScores.clear(); this.homeScores += homeScores
        this.candidateScores.clear(); if (this.candidate != null) this.candidateScores += candidateScores
        this.scores.clear()
        for ((height, list) in scores) if (height in HEIGHTS) this.scores[height] = ArrayList(list.takeLast(KEPT))
    }

    /** The height the runs have moved to, where that is not the person's own, with the usual score on each. */
    fun suggestion(): Suggestion? {
        if (home == own) return null
        val there = scores[home].orEmpty()
        val here = scores[own].orEmpty()
        if (there.size < MIN_PAIRS) return null
        return Suggestion(home, median(there), there.size, if (here.isEmpty()) Float.NaN else median(here), here.size)
    }

    companion object {
        /** The key heights (KeyboardPrefs.KEY_HEIGHT), most condensed first, and what Settings calls each. */
        val HEIGHTS = listOf("condensed", "short", "normal", "tall")
        val LABELS = mapOf("condensed" to "Condensed", "short" to "Short", "normal" to "Normal", "tall" to "Tall")

        /**
         * A trial is decided on no fewer pairs of runs than this, and given up as no better after [MAX_PAIRS]. A
         * height takes over only where its usual score is [MARGIN] better, and is let go early only where it is that
         * much worse. The three were set on a person whose runs scatter by a twentieth of their score (as a real
         * person's do, give or take): where no height is better, five people in six are left on their own; where one
         * is a tenth better, nine in ten are moved to it, in about seventeen runs (TrainerAdaptationTest).
         */
        const val MIN_PAIRS = 4
        const val MAX_PAIRS = 6
        const val MARGIN = 0.06f
        const val KEPT = 12

        fun median(values: List<Float>): Float {
            if (values.isEmpty()) return Float.NaN
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
        }
    }
}
