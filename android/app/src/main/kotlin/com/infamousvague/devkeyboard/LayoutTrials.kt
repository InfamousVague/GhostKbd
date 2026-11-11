package com.infamousvague.devkeyboard

/**
 * The typing trainer's layout trials (docs/trainer.md; Matt, 2026-11-11: "try different layouts like more condensed
 * or less condensed", and "we should only do the condensed vs expanded views and shifting the UI majorly if the
 * setting is enabled when we start the trainer"; then "a b test the accuracy and don't suggest switching until its
 * proven we got a better result"). Plain Kotlin with no Android in it.
 *
 * Only with the trainer's "Try different layouts" switch on, as it stood when a run started, may a run be typed on
 * keys of another height than the person's own. This says which, and what the runs so far add up to.
 *
 * The heights stand in a line, [HEIGHTS], most condensed first. One of them is [home]: the best found so far, which
 * starts as the person's [own] setting. A trial is an A/B test of home against one neighbour (a step more condensed,
 * or a step less), run about: home, the neighbour, home, the neighbour, each pair of runs typed one after the other
 * so that a good day counts for both. It ends when:
 * - the neighbour is **proven** better: over [MIN_PAIRS] pairs or more its lead, pair by pair, is so steady that
 *   chance would give one like it less than once in two hundred tries were it no better (a paired t test on the
 *   scores' ratios, [PROVEN]). It becomes home, one step and no further;
 * - its scores are not ahead at all once [MIN_PAIRS] pairs are in, or [MAX_PAIRS] pairs have not proven it: it is
 *   let go, and home stays.
 * One good run decides nothing, and neither do three. Then the other neighbour is tried, and when neither is proven
 * better the trials are [settled].
 *
 * Nothing here changes the person's setting. A trial layout is worn by the keyboard only for the run that asked for
 * it (Trainer.kt), and [suggestion] is an offer, made only of a height that was proven.
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

    /** A height taking over from another, proven over [pairs] pairs of runs of which it scored higher in [wins]. */
    class Move(val from: String, val to: String, val pairs: Int = 0, val wins: Int = 0)

    /** The height the runs proved better than the person's own, with what they measured on each, and the pairs of runs that proved the last step to it. */
    class Suggestion(val height: String, val score: Float, val runs: Int, val ownScore: Float, val ownRuns: Int, val pairs: Int = 0, val wins: Int = 0)

    /** The pairs of runs that proved [home] better than the height before it, and how many of them it scored higher in. 0 while home is where the trials began. */
    var provenPairs = 0
        private set
    var provenWins = 0
        private set

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
        provenPairs = 0; provenWins = 0
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
        // The neighbour's lead in each pair of runs, as the log of the two scores' ratio: 0 is level.
        val lead = FloatArray(pairs) { kotlin.math.ln(candidateScores[it] / homeScores[it]) }
        if (Proof.steady(lead, PROVEN)) {
            val from = home
            home = c
            provenPairs = pairs
            provenWins = lead.count { it > 0f }
            clearTrial()
            rejected.clear()
            // Home was just beaten by this one: it is not tried again from here.
            rejected += from
            return Move(from, c, provenPairs, provenWins)
        }
        if (lead.average() <= 0.0 || pairs >= MAX_PAIRS) {
            rejected += c
            clearTrial()
        }
        return null
    }

    /** Every height open to trial again from [home], as it stands. */
    fun again() { rejected.clear(); clearTrial() }

    /** The trials as they were kept (TrainerCodec): heights this keyboard does not have are dropped. */
    fun restore(own: String, home: String, candidate: String?, rejected: Collection<String>, homeScores: List<Float>, candidateScores: List<Float>, scores: Map<String, List<Float>>, provenPairs: Int = 0, provenWins: Int = 0) {
        this.own = own.takeIf { it in HEIGHTS } ?: "normal"
        this.home = home.takeIf { it in HEIGHTS } ?: this.own
        this.provenPairs = if (this.home == this.own) 0 else provenPairs.coerceAtLeast(0)
        this.provenWins = provenWins.coerceIn(0, this.provenPairs)
        // A home that is not the person's own and was never proven (an earlier version moved on less) is no home:
        // the trials begin again from their own keys, with every height's scores as they were.
        val unproven = this.home != this.own && this.provenPairs < MIN_PAIRS
        if (unproven) { this.home = this.own; this.provenPairs = 0; this.provenWins = 0 }
        this.candidate = candidate?.takeIf { it in HEIGHTS && it != this.home && !unproven }
        this.rejected.clear(); if (!unproven) this.rejected += rejected.filter { it in HEIGHTS }
        this.homeScores.clear(); if (!unproven) this.homeScores += homeScores
        this.candidateScores.clear(); if (this.candidate != null) this.candidateScores += candidateScores
        this.scores.clear()
        for ((height, list) in scores) if (height in HEIGHTS) this.scores[height] = ArrayList(list.takeLast(KEPT))
    }

    /** The height the runs proved better, where that is not the person's own, with the usual score on each. Home only ever moves on proof ([decide]), so there is nothing to suggest until then. */
    fun suggestion(): Suggestion? {
        if (home == own) return null
        val there = scores[home].orEmpty()
        val here = scores[own].orEmpty()
        if (there.size < MIN_PAIRS || provenPairs < MIN_PAIRS) return null
        return Suggestion(home, median(there), there.size, if (here.isEmpty()) Float.NaN else median(here), here.size, provenPairs, provenWins)
    }

    companion object {
        /** The key heights (KeyboardPrefs.KEY_HEIGHT), most condensed first, and what Settings calls each. */
        val HEIGHTS = listOf("condensed", "short", "normal", "tall")
        val LABELS = mapOf("condensed" to "Condensed", "short" to "Short", "normal" to "Normal", "tall" to "Tall")

        /**
         * A trial is decided on no fewer pairs of runs than this, and given up as not proven after [MAX_PAIRS].
         * [PROVEN] is how steady a lead has to be, by how many pairs it stands on: Student's t at one in two hundred,
         * one-sided. A trial is looked at after every pair from [MIN_PAIRS] on, and looking more often finds more by
         * chance, which is why the level is that strict: on simulated people whose runs scatter by a twentieth of
         * their score (tool/trainer_sim.sh), where no height is better 96 in 100 are left on their own (the rule
         * before this one left 84), and where one is a tenth better 85 in 100 are moved to it, in about twenty-two
         * runs. TrainerAdaptationTest holds each build near it.
         */
        const val MIN_PAIRS = 4
        const val MAX_PAIRS = 10
        const val KEPT = 20
        private val PROVEN = Proof.ONE_IN_200

        fun median(values: List<Float>): Float {
            if (values.isEmpty()) return Float.NaN
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2f
        }
    }
}
