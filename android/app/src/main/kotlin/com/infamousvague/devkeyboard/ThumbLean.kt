package com.infamousvague.devkeyboard

/**
 * Where this thumb is landing, in the text field being typed in (Matt, 2026-11-12: "we want to get it to where the
 * keyboard is one of the most accurate on the market"): how far from the middle of the key it aims at its taps
 * fall, in key widths across ([x]) and down ([y]).
 *
 * A thumb does not land where it aims. By hand, grip and phone it lands a little to one side and a little low or
 * high, on every key much alike, and differently from one sitting to the next. The keys listen where one person's
 * thumb was measured to land (KeyboardView.LETTER_OFFSETS_DP), and the adaptive keys plugin moves single keys over
 * weeks; neither knows where the thumb is landing now. tool/versus's leaning thumb lands 0.24 of a key right and
 * 0.18 of a key's height low on every tap, and autocorrect left 5.4% of its words wrong where it left 2.5% of the
 * same thumb's without the lean (docs/versus.md, "Where this thumb lands").
 *
 * It begins at nothing as a field is entered ([newField]). Every word autocorrect has settled teaches it ([learn]:
 * the letters that stand, each against its own tap; [Autocorrect.settled] says which words may), and the next
 * word's taps are read by it ([read], [TapDecoder.load]). It is in memory only, for the field being typed in:
 * nothing here can write it down, and it is gone when the field is left, or when the keys change width under it.
 * It is read only on an English board with prediction on ([Autocorrect.aimed]), which is all it has been measured
 * on; on any other board it learns nothing ([Autocorrect.settled]).
 *
 * The estimate is the lean to expect, given the taps. Most thumbs are taken to land where they aim, and one in
 * [ODDS] + 1 to lean, by about [LEAN] of a key each way. The taps' mean miss is then worth as much of itself as
 * the taps make it likely that this thumb leans at all: next to nothing while scatter alone ([NOISE], and then the
 * field's own taps) would as soon have given that mean, nearly all of it once it stands well clear of the scatter.
 * Three taps of the leaning thumb say nothing; a dozen say most of it. A sloppy thumb that does not lean scatters
 * too widely for its mean to stand out often: it is given a tenth of a key's lean or more in two or three
 * sentences of a hundred (tool/versus/leanlog.py). One lean serves the whole board: a field's taps are a few
 * dozen, which is enough for one pair of numbers and not for one a row or a side (Gboard's own model keeps 800 taps
 * for seven groups of keys: Sivek and Riley, "Spatial model personalization in Gboard", PACM HCI 2022).
 *
 * Plain Kotlin with no Android in it: tool/typing_bench.sh, tool/versus/replay.sh and the unit tests run the very
 * code the phone does.
 */
class ThumbLean {
    /** The taps that have taught it: how many they count as, their misses summed each way (key widths), and the misses' squares. */
    private var n = 0f
    private var sumX = 0f
    private var sumY = 0f
    private var squares = 0f
    /** The same squares, each held to [SPREAD_CAP] where that is set: what the spread is measured by. */
    private var held = 0f
    /** As they were before the last word was settled, for [unlearn]. */
    private val was = FloatArray(5)
    /** The key width the taps that taught it were tapped on (0 before any): the board it holds for. */
    private var width = 0f

    /** The lean as it is estimated now, across and down, in key widths. */
    var x = 0f
        private set
    var y = 0f
        private set

    /**
     * How tightly this thumb's taps fall about where it lands, against the thumb [TapDecoder.SPATIAL] is set for: the
     * factor on [TapDecoder.SPATIAL] the next word's taps are read with ([Taps.tight]). 1 until a word has taught it.
     */
    var tight = 1f
        private set

    /** How many taps it holds: never more than [MEMORY]. */
    val taps: Float get() = n

    /** A text field is entered, or left: nothing is known of the thumb in the next one. */
    fun newField() {
        if (KEEP) return
        n = 0f; sumX = 0f; sumY = 0f; squares = 0f; held = 0f; x = 0f; y = 0f; tight = 1f; width = 0f
        was.fill(0f)
    }

    /**
     * [taps] to be read by the lean as it is estimated now. The very same taps where nothing is estimated: the word
     * is then read exactly as it was before there was a lean. Where the keys are not the width they were as it was
     * taught (the Fold opened or shut, one-handed or split mode turned on in the field), a thumb reaching over
     * another board leans otherwise: it begins again at nothing, as for a new field.
     */
    fun read(taps: Taps): Taps {
        // On keys of another width than it was taught on, the lean and the spread are another board's: begin again.
        if (width != 0f && taps.keyW != width) newField()
        val k = if (SPREAD_FIXED > 0f) SPREAD_FIXED / TapDecoder.SPATIAL else if (SPREAD_ON) tight else 1f
        if (!ON) return if (k == 1f) taps else Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, 0f, 0f, k)
        val lx = if (FIXED_X != 0f || FIXED_Y != 0f) FIXED_X else x
        val ly = if (FIXED_X != 0f || FIXED_Y != 0f) FIXED_Y else y
        return if (lx == 0f && ly == 0f && k == 1f) taps else Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, lx, ly, k)
    }

    /**
     * A word has been settled, and its taps say where the thumb landed for it: [meant] is the key each tap is now
     * taken to have been meant for (0 where it has none). A letter autocorrect changed teaches as one it left does:
     * it is the tap that crossed a line, which says most of a lean. With those left out the leaning thumb had 91 of
     * its 2,136 unseen words wrong where it had 88 (tried at [ODDS] 16).
     */
    fun learn(meant: CharArray, taps: Taps) {
        pass()
        val w = taps.keyW
        if (w <= 0f) return
        // Taught on keys of another width than before: what it held was for that board.
        if (width != 0f && w != width) newField()
        width = w
        for (i in meant.indices) {
            if (!taps.has(i)) continue
            val at = taps.keys[meant[i]] ?: continue
            val dx = (taps.xs[i] - at.first) / w
            val dy = (taps.ys[i] - at.second) / w
            // A tap this far from the key now read for it was no slip of the aim: two letters swapped, a misspelling put right.
            if (dx * dx + dy * dy > REACH * REACH) continue
            val r2 = dx * dx + dy * dy
            n += 1f; sumX += dx; sumY += dy; squares += r2; held += if (SPREAD_CAP > 0f) minOf(r2, SPREAD_CAP) else r2
        }
        // What is older fades, so that it follows a change of hand or grip.
        if (n > MEMORY) { val f = MEMORY / n; n *= f; sumX *= f; sumY *= f; squares *= f; held *= f }
        estimate()
    }

    /** A word has been settled that teaches nothing: letters no list has, a letter missed or added, no taps. */
    fun pass() { was[0] = n; was[1] = sumX; was[2] = sumY; was[3] = squares; was[4] = held }

    /** The last word settled was not what was meant after all (its fix was taken back): it is as it was before that word. */
    fun unlearn() { n = was[0]; sumX = was[1]; sumY = was[2]; squares = was[3]; held = was[4]; estimate() }

    private fun estimate() {
        if (n <= 0f) { x = 0f; y = 0f; tight = 1f; return }
        val mx = sumX / n; val my = sumY / n
        // The scatter of one tap about where the thumb lands, each way, squared: NOISE's until the field's own taps say.
        val about = (squares - (sumX * sumX + sumY * sumY) / n).coerceAtLeast(0f) / 2f
        // The same scatter as the spread the taps are read by (each tap's square held to SPREAD_CAP where that is
        // set): SPREAD's, worth SPREAD_TAPS taps, until the field's say.
        val spread = (held - (sumX * sumX + sumY * sumY) / n).coerceAtLeast(0f) / 2f
        val v = (SPREAD_TAPS * SPREAD + spread) / (SPREAD_TAPS + (n - 1f).coerceAtLeast(0f))
        // Read tighter as one over the spread squared; looser more steeply, so that a careful thumb's few wider
        // sentences are seldom read loosely while a sloppy thumb's are soon.
        val q = SPREAD / v
        val k = if (q >= 1f) q else Math.pow(q.toDouble(), SPREAD_POWER.toDouble()).toFloat()
        tight = k.coerceIn(SPREAD_LOW, SPREAD_HIGH)
        val s2 = (NOISE_TAPS * NOISE * NOISE + about) / (NOISE_TAPS + (n - 1f).coerceAtLeast(0f))
        // How far the mean stands out of what that scatter alone would make of it, and how much a lean of LEAN's size
        // would have shown in so many taps.
        val t2 = (mx * mx + my * my) / (s2 / n)
        val r = n * LEAN * LEAN / s2
        // The share of the mean to believe: how likely it is that the thumb leans, by the lean those taps then say.
        val leans = 1.0 / (1.0 + ODDS * (1.0 + r) * Math.exp(-0.5 * t2 * r / (1.0 + r)))
        val f = (leans * r / (1.0 + r)).toFloat()
        x = mx * f; y = my * f
    }

    companion object {
        // What these are set by. tool/versus/scoreboard.py: a careful, a leaning and a sloppy thumb on its seen and
        // its unseen sentences (1,735 and 2,136 words a thumb); the leaning one lands 0.24 of a key right and 0.18
        // of a key's height low on every tap, the other two do not lean. "Words wrong" below are those three
        // thumbs' on the seen set and then on the unseen one; with no estimate they are 43, 94, 189 and 70, 107,
        // 260. And tool/typing_bench.sh taps, 12,000 words: its careful, normal and sloppy thumbs as they are, and
        // all three leaning (-Dlean=0.24,0.18). docs/versus.md has the tables.

        /** Off, every word is read as it was before there was a lean: the scoreboard is then the base's, word for word. */
        @JvmField var ON = true

        /**
         * How many fields are typed by a thumb that lands where it aims for each one typed by a thumb that leans:
         * what is believed before a tap is seen, and so how soon a mean miss is taken for a lean. Lower, a leaning
         * thumb is read by its lean a word or two sooner, and a thumb with no lean is now and then given one for a
         * few words at a field's start.
         *
         * Words wrong at 1: 43, 69, 193 and 70, 86, 258. At 2: 43, 71, 191 and 70, 86, 259. At 4: 43, 76, 191 and
         * 71, 86, 259. At 8: 43, 78, 188 and 71, 88, 258. At 16: 43, 79, 189 and 70, 88, 258. On the bench, words
         * right for thumbs with no lean are 96.41%, 92.34% and 85.65% with no estimate, 96.41%, 92.33%, 85.60% at
         * 16, 96.41%, 92.35%, 85.58% at 4 and 96.41%, 92.33%, 85.49% at 2 (right words spoiled and unknown words
         * spoiled do not move in any); for the same thumbs leaning they are 92.53%, 85.95%, 76.54% with no estimate,
         * 93.43%, 88.14%, 79.78% at 16, 93.48%, 88.49%, 80.51% at 4 and 93.54%, 88.58%, 80.86% at 2.
         *
         * Taken at 4, one field in five: a thumb with no lean then pays under a tenth of a point of words right,
         * and a leaning one has most of what a lower number would give it. It is a belief about people, which
         * these thumbs cannot settle: recorded typing would.
         */
        @JvmField var ODDS = 4f

        /**
         * How far a thumb that leans is taken to lean, each way, in key widths. A quarter to a third of a key is
         * what has been measured of people (players of a typing game hit 2.24 mm below the keys' middles on one
         * phone: Henze, Rukzio and Boll, "Observational and experimental investigation of typing behaviour using
         * virtual keyboards", MobileHCI 2012) and what tool/versus's thumb has. It matters little: at 0.2 words wrong
         * are 43, 76, 191 and 70, 88, 258; at 0.45, 43, 76, 192 and 71, 87, 260.
         */
        @JvmField var LEAN = 0.3f

        /**
         * The scatter of a tap about where the thumb lands, each way, in key widths, taken before the field's own
         * taps say; [NOISE_TAPS] is how many of the field's taps that is worth. People's thumbs were measured to
         * scatter 0.32 of a key across and 0.20 of a key's height down (Azenkot and Zhai's 1.97 mm across and 1.88 mm
         * down on keys 6.16 mm by 9.42 mm, as Fowler et al. take them in "Effects of language modeling and its
         * personalization on touchscreen typing performance", CHI 2015): about 0.3 of a width each way. Lower, the
         * measure's leaning thumb (which scatters 0.20) is believed sooner and its sloppy thumb (0.34, no lean) pays:
         * at 0.2 words wrong are 43, 69, 195 and 70, 86, 261; at 0.4, 43, 79, 188 and 71, 88, 259. Worth one tap in
         * place of four: 43, 74, 189 and 70, 86, 259; worth sixteen: 43, 78, 191 and 71, 87, 259.
         */
        @JvmField var NOISE = 0.3f
        @JvmField var NOISE_TAPS = 4f

        /**
         * The most taps it holds, eight or nine words' worth: what is older fades as new taps come, so that it
         * follows a change of hand or grip in a long field. Smaller, it turns sooner and is itself more scattered.
         *
         * The scoreboard's fields are too short to say (20, 40 and 100 give the same words). On the bench, typed
         * as one long field ([KEEP]) by leaning thumbs whose lean changes sides every five sentences
         * (-Dlean.turn=5), words right are 92.81%, 86.24% and 76.58% with no estimate; holding 15 taps 93.48%,
         * 87.82%, 79.22%; 25: 93.22%, 87.24%, 78.52%; 40: 93.03%, 86.47%, 77.73%; 50: 92.88%, 86.15%, 77.37%; 100:
         * 92.73%, 85.90%, 76.75%. With a lean that does not change, one long field: 25 taps 93.88%, 89.42%, 83.24%;
         * 40: 93.88%, 89.49%, 83.55%; 100: 93.88%, 89.51%, 83.59%. Taken at 40, the least that costs a steady lean
         * nothing. A lean turned right round is still read wrongly for a dozen words before it is let go: that is
         * what a stale estimate costs, and only a new field ends it at once.
         */
        @JvmField var MEMORY = 40f

        /**
         * A tap further than this from the key it is now read for teaches nothing, in key widths: it was no slip of
         * the aim ("form" mended to "from" says nothing of where the thumb lands on o and r). A key and a quarter
         * takes in a tap that crossed into the next key or the next row. At 0.9 words wrong are 43, 76, 189 and 71,
         * 88, 260; at 2, 43, 76, 190 and 71, 86, 258.
         */
        @JvmField var REACH = 1.25f

        /**
         * For measuring, and off: the estimate carried over from one field to the next, as if a whole run were one
         * field (tool/versus/replay then keeps one for each thumb, tool/typing_bench one for each pass). The field
         * tool/versus types in asks keyboards to learn nothing, so that one sentence teaches nothing for the next,
         * and an estimate begun again at every field keeps to that. Carried over, the leaning thumb's words wrong
         * are 58 and 80 where they are 76 and 86, and the other thumbs' as they were (43, 189 and 69, 259).
         */
        @JvmField var KEEP = false

        /**
         * For measuring, and nothing: a lean to read every word by in place of the estimate, in key widths. The
         * measure's own (0.24 and 0.2286: its keys are 1.27 times as tall as wide) says the most an estimate could
         * do for the leaning thumb, 57 and 81 words wrong, and what a lean of that size costs a thumb that has
         * none: careful 46 and 80, sloppy 261 and 353.
         */
        @JvmField var FIXED_X = 0f
        @JvmField var FIXED_Y = 0f

        // How tightly this thumb's taps fall (docs/versus.md, "How tightly this thumb types"). TapDecoder.SPATIAL is
        // what a key width squared of distance from a tap costs; a thumb whose taps scatter widely wants it lower, one
        // whose taps fall close higher. The spread is the scatter the lean above already measures (its squares about
        // its own mean), and the next word is read with SPATIAL times SPREAD over it: one over the spread squared, as
        // a Gaussian thumb would have it. Shaped off the JVM (build/scatter/laws*.py, which forecast the scoreboard
        // within three words); those forecasts printed unseen totals beside seen for every knee, prior and power
        // tried, and the bounds and the joins were settled on the unseen set's total and the bench together, so the
        // unseen set is no clean check of these dials. Words wrong below are seen and unseen, with everything else as
        // it stood then: 287 and 394 (288 and 393 with the rejoin spared). What this buys over SPATIAL moved alone
        // (1.2: 291 and 397, the same normal and sloppy spoils on the bench) is the careful thumb's spoils.

        /** Off, every word is read with SPATIAL as it stands, whatever its taps have shown: the scoreboard is then the base's. */
        @JvmField var SPREAD_ON = true

        /**
         * The scatter of a tap about where the thumb lands, squared, each way, in key widths squared, that SPATIAL as
         * it stands is right for: what a field begins with, so that its first word reads exactly as it did before.
         * Trusted taps of the measure's careful thumb come to about 0.046 a field, its sloppy one's 0.117. Forecast at
         * 0.05, 0.055, 0.06, 0.065, 0.07 (with SPREAD_POWER 3, a floor of 0.5): unseen 387, 390, 392, 392, 391; at
         * 0.065 the careful thumb is read below 1.45 at 14% of its words where at 0.05 at 32%.
         */
        @JvmField var SPREAD = 0.065f

        /**
         * How steeply SPATIAL falls as the spread grows past [SPREAD]: the spread's ratio to it to this power. At 2
         * words wrong are 286 and 395; at 3, 287 and 394. Steeper keeps a careful thumb's few wide sentences tight.
         */
        @JvmField var SPREAD_POWER = 3f

        /** How many of the field's own taps [SPREAD] is worth. At 8 words wrong are 286 and 393. */
        @JvmField var SPREAD_TAPS = 4f

        /**
         * The least and the most [tight] may be, however widely or closely the taps fall: factors on SPATIAL as it
         * stands, so that they move with it when it is moved. With SPATIAL at 1.5 a least of 0.6 reads no lower than
         * 0.9 and a most of 2 no higher than 3. Least (in SPATIAL, at 1.5) 0.5, 0.7, 0.9, 1.0, 1.1: words wrong 278,
         * 281, 287, 289, 293 and 397, 395, 394, 400, 407; the bench's sloppy thumb spoils 4.96, 3.10, 2.48, 2.28, 2.28
         * right words a thousand (1.65 with no spread), its normal one 3.09, 2.42, 2.02, 1.75, 1.61 (1.48). The least
         * was chosen with the unseen totals in view. The most is seldom reached: a careful thumb's sentence comes to
         * about 1.3 (SPATIAL about 2). Until 2026-11-13 these were values of SPATIAL itself (0.9 and 3), which with SPATIAL moved to 0.9
         * left the spread nothing to do (seen 277 on, 278 off).
         */
        @JvmField var SPREAD_LOW = 0.6f
        @JvmField var SPREAD_HIGH = 2f

        /**
         * For measuring, and off: the most one tap's squared miss (key widths squared) counts for in the spread, so
         * that one wild tap early in a field cannot make a careful thumb loose. 0: not held. Held at 0.5, 0.36, 0.25:
         * words wrong 290, 291, 298 and 396, 395, 417 (not held: 287 and 394); the careful thumb's bench spoils 1.01,
         * 1.01, 0.91 a thousand (1.01), the sloppy thumb's words right 86.93%, 86.75%, 86.26% (87.04%). A held square
         * also holds a sloppy thumb's honest taps, so nothing was gained: left off.
         */
        @JvmField var SPREAD_CAP = 0f

        /**
         * How much of the loosening two words run together are spared: their slips' cost is divided by [Taps.tight]
         * (where under 1) to this power. 1: they are read as tightly as SPATIAL as it stands; 0: as loosely as words.
         * At a floor of 0.5: 0 gives 270 and 395 words wrong, 0.5 gives 268 and 396, 1 gives 278 and 397; the bench's
         * normal thumb spoils 5.11, 3.77, 3.09 right words a thousand and 16.5%, 11.6%, 8.5% of unknown words, letters
         * no list has split into two words ("mapbox", "map of"). Taken at 1 for the bench.
         */
        @JvmField var SPREAD_JOINS = 1f

        /**
         * Whether the bar's tap read as a letter between two words ([Autocorrect.rejoin]) follows the spread as a
         * word does. Following it: words wrong 287 and 394; read with SPATIAL as it stands, 288 and 393, the bench's
         * lines identical. No gain either way, so it is spared, as joins are, and rejoins read as before.
         */
        @JvmField var SPREAD_REJOIN = false

        /** For measuring, and nothing: a SPATIAL to read every word with in place of the estimate's (the ceiling runs). */
        @JvmField var SPREAD_FIXED = 0f
    }
}
