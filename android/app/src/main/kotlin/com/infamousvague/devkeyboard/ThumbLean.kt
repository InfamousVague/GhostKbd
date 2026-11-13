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
    /** As they were before the last word was settled, for [unlearn]. */
    private val was = FloatArray(4)
    /** The key width the taps that taught it were tapped on (0 before any): the board it holds for. */
    private var width = 0f

    /** The lean as it is estimated now, across and down, in key widths. */
    var x = 0f
        private set
    var y = 0f
        private set

    /** How many taps it holds: never more than [MEMORY]. */
    val taps: Float get() = n

    /** A text field is entered, or left: nothing is known of the thumb in the next one. */
    fun newField() {
        if (KEEP) return
        n = 0f; sumX = 0f; sumY = 0f; squares = 0f; x = 0f; y = 0f; width = 0f
        was.fill(0f)
    }

    /**
     * [taps] to be read by the lean as it is estimated now. The very same taps where nothing is estimated: the word
     * is then read exactly as it was before there was a lean. Where the keys are not the width they were as it was
     * taught (the Fold opened or shut, one-handed or split mode turned on in the field), a thumb reaching over
     * another board leans otherwise: it begins again at nothing, as for a new field.
     */
    fun read(taps: Taps): Taps {
        if (!ON) return taps
        if (width != 0f && taps.keyW != width) newField()
        val lx = if (FIXED_X != 0f || FIXED_Y != 0f) FIXED_X else x
        val ly = if (FIXED_X != 0f || FIXED_Y != 0f) FIXED_Y else y
        return if (lx == 0f && ly == 0f) taps else Taps(taps.xs, taps.ys, taps.keys, taps.keyW, taps.space, lx, ly)
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
            n += 1f; sumX += dx; sumY += dy; squares += dx * dx + dy * dy
        }
        // What is older fades, so that it follows a change of hand or grip.
        if (n > MEMORY) { val f = MEMORY / n; n *= f; sumX *= f; sumY *= f; squares *= f }
        estimate()
    }

    /** A word has been settled that teaches nothing: letters no list has, a letter missed or added, no taps. */
    fun pass() { was[0] = n; was[1] = sumX; was[2] = sumY; was[3] = squares }

    /** The last word settled was not what was meant after all (its fix was taken back): it is as it was before that word. */
    fun unlearn() { n = was[0]; sumX = was[1]; sumY = was[2]; squares = was[3]; estimate() }

    private fun estimate() {
        if (n <= 0f) { x = 0f; y = 0f; return }
        val mx = sumX / n; val my = sumY / n
        // The scatter of one tap about where the thumb lands, each way, squared: NOISE's until the field's own taps say.
        val about = (squares - (sumX * sumX + sumY * sumY) / n).coerceAtLeast(0f) / 2f
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
    }
}
