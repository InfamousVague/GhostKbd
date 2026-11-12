package com.infamousvague.devkeyboard

import java.util.Random

/**
 * A simulated thumb typing the trainer's stories, for TrainerAdaptationTest and tool/trainer_sim.sh: does a thumb that
 * always lands off the keys' middles the same way make fewer mistakes as the runs go by, typed on the trainer's fixes,
 * and not without them? And does the trainer's test of those fixes say "proven" to that thumb, and to no thumb the
 * fixes do nothing for?
 *
 * Everything the phone would do is the app's own code: autocorrect reads each word by where its taps landed
 * ([Autocorrect.correct] with [Taps]), the run is written down and scored by [TrainerRecorder] and [TrainerScore], the
 * taps are kept in a [TrainerBook], and the fixes are made, worn and judged by [FixTrial]. What is redrawn here is
 * only the part that lives in Android's view: which key a touch is read as ([read]: KeyboardView.keyAt and adapt), a
 * few lines kept beside the original's rule, and read twice where a fix is worn, as the view reads it.
 *
 * A simulated thumb is not a person (docs/typing-accuracy.md). This says the fixes and their test do what they are
 * meant to for a thumb with a steady lean; how real thumbs lean is what the trainer measures on a phone.
 */
object TrainerSim {
    /** A drawn QWERTY: a key 44 points wide in a row of 60, as the keyboard is at its Normal height. */
    const val W = 100f
    const val H = 136f
    val board: Map<Char, Pair<Float, Int>> = Layouts.QWERTY.positions
    private val rows: List<List<Char>> = (0..2).map { r -> board.filter { it.value.second == r }.keys.sortedBy { board.getValue(it).first } }
    /** The space bar, five keys wide between the { } key and the full stop (or the mic key in its place), in key widths. */
    const val BAR_LEFT = 2.5f
    const val BAR_RIGHT = 7.5f
    private val space = floatArrayOf(BAR_LEFT * W, 3f * H, BAR_RIGHT * W, 4f * H)

    /** The middle of [c]'s key, across in key widths and down in rows. */
    fun middle(c: Char): Pair<Float, Float> = board.getValue(c).let { (it.first + 0.5f) to (it.second + 0.5f) }

    /**
     * How a thumb misses: always ([leanX], [leanY]) of a key off the middle it aims for, with a scatter about that,
     * and a share of wilder taps. [wander] is how far the lean itself strays from one run to the next (the phone held
     * another way each time): a lean that is all wander is no lean a fix can follow.
     */
    class Thumb(val leanX: Float, val leanY: Float, val scatterX: Float = 0.20f, val scatterY: Float = 0.15f, val wild: Float = 0.06f, val wander: Float = 0f)

    /**
     * The key a touch at ([x], [y]) is read as (key widths across, rows down): a letter, ' ' for the space bar, or 0
     * for another key of the bottom row. KeyboardView.keyAt: the key under the touch, or past a row's ends the
     * nearest key of it; then KeyboardView.adapt with where each key listens ([offsets]: Adaptive keys' nudges, or a
     * fix): of that letter and the letters within a key and a half of it, the one that listens nearest. Null: the
     * key under the touch, as with no Adaptive keys.
     */
    fun read(x: Float, y: Float, offsets: ((Char) -> Pair<Float, Float>)?): Char {
        if (y >= 3f) return if (x >= BAR_LEFT && x < BAR_RIGHT) ' ' else 0.toChar()
        val row = rows[kotlin.math.floor(y.toDouble()).toInt().coerceIn(0, 2)]
        val hit = row.firstOrNull { x >= board.getValue(it).first && x < board.getValue(it).first + 1f }
            ?: return row.minByOrNull { kotlin.math.abs(x - middle(it).first) }!!
        if (offsets == null) return hit
        val (hx, hy) = middle(hit)
        return board.keys.filter { kotlin.math.abs(middle(it).first - hx) < 1.6f && kotlin.math.abs(middle(it).second - hy) < 1.6f }.minByOrNull { c ->
            val (ox, oy) = offsets(c)
            val dx = x - middle(c).first - ox
            val dy = y - middle(c).second - oy
            dx * dx + dy * dy
        } ?: hit
    }

    /** What a run came to: the trainer's own result, and the story's letters. */
    class Outcome(val result: RunResult, val letters: Int) {
        /** Taps read as another key than the one meant, in a hundred. */
        val keyErrors: Float get() = if (result.taps == 0) 0f else 100f * (result.taps - result.hits) / result.taps
        /** The same taps as the keyboard as it is would have read them: read as another key than the one meant, in a hundred. */
        val keyErrorsAsIs: Float get() = if (result.taps == 0) 0f else 100f * (result.taps - result.asIs) / result.taps
        /** Story words that did not come out right, autocorrect and all, in a hundred. */
        val wordErrors: Float get() = 100f * (result.words - result.right) / result.words
    }

    /**
     * [thumb] types [story] once: every letter aimed at its key's middle and landing where the thumb's lean and
     * scatter put it, the space bar the same. It never looks back, so what it gets wrong stays wrong but for what
     * autocorrect [ac] mends. [worn] is where each key listens for this run where it is typed on a fix (null: none,
     * the run is typed on the keyboard as it is). [asIs] is where each key listens on the keyboard as it is (null:
     * Adaptive keys is not added, and a touch is the key under it). Each touch is read by what the run wears, and
     * where that is a fix by the keyboard as it is too, as the phone's view reads it.
     */
    fun type(story: String, thumb: Thumb, random: Random, worn: ((Char) -> Pair<Float, Float>)?, asIs: ((Char) -> Pair<Float, Float>)?, ac: Autocorrect?): Outcome {
        val reads = worn ?: asIs
        val run = TrainerRecorder(story)
        var text = ""
        var at = 1_000L
        fun off(scatter: Float) = (random.nextGaussian() * scatter * (if (random.nextFloat() < thumb.wild) 2.2 else 1.0)).toFloat()
        // Where each letter key listens now, for autocorrect's reading of the taps (KeyboardView.tapKeys).
        val keys = board.keys.associateWith { c -> val (ox, oy) = reads?.invoke(c) ?: (0f to 0f); ((middle(c).first + ox) * W) to ((middle(c).second + oy) * H) }
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()

        /** The word at the cursor ends with [mark] after it: autocorrect has its say first, as on the phone. */
        fun end(mark: String) {
            val word = TrainerText.tail(text)
            val fixed = if (ac == null || word.isEmpty() || xs.size != word.length) null else ac.correct(word, null, Taps(xs.toFloatArray(), ys.toFloatArray(), keys, W, space))
            if (fixed != null) text = text.dropLast(word.length) + fixed
            text += mark
            xs.clear(); ys.clear()
            at += 250
            run.key(at, if (mark == " ") KeyKind.SPACE else KeyKind.CHAR, text, if (mark == " ") null else KeyTap(mark[0], 0f, 0f))
        }

        /** One touch meant for the point ([mx], [my]): the letter it types, if it types one. */
        fun touch(mx: Float, my: Float, capital: Boolean) {
            val x = mx + thumb.leanX + off(thumb.scatterX)
            val y = (my + thumb.leanY + off(thumb.scatterY)).coerceAtLeast(0.01f)
            val key = read(x, y, reads)
            when (key) {
                0.toChar() -> Unit
                ' ' -> end(" ")
                else -> {
                    at += 250
                    text += if (capital) key.uppercaseChar() else key
                    xs += x * W; ys += y * H
                    // The second reading, which the view makes only while a fix is worn.
                    val other = if (worn == null) key else read(x, y, asIs).takeIf { it in board } ?: key
                    run.key(at, KeyKind.CHAR, text, KeyTap(key, x - middle(key).first, y - middle(key).second, other))
                }
            }
        }

        var letters = 0
        for (c in story) {
            when {
                c == ' ' -> touch((BAR_LEFT + BAR_RIGHT) / 2f, 3.5f, false)
                c.lowercaseChar() in board -> { letters++; val (mx, my) = middle(c.lowercaseChar()); touch(mx, my, c.isUpperCase()) }
                // A full stop or a comma: its key is not a letter's, and the thumb is given it.
                else -> end(c.toString())
            }
        }
        return Outcome(TrainerScore.of(story, run.steps, board), letters)
    }

    /**
     * One line of a trainer in use: the runs typed so far, and what each came to. With [fixing] every run after the
     * first is typed on a fix and tests it, as on the phone; without, every run is typed on the keyboard as it is.
     * With [taking] the fix is taken the moment it is proven, as a person tapping "Switch" would: Adaptive keys is
     * added, the keyboard as it is becomes that fix, and the fixes after it are held against it.
     */
    class Course(val fixing: Boolean, val taking: Boolean = false) {
        val book = TrainerBook()
        val trial = FixTrial()
        val outcomes = ArrayList<Outcome>()

        /** The verdict each run that tested a fix left; [provenAt] is the first run (counted from 1) that left a fix proven, [worse] how many fixes were dropped, [taken] how many taken. */
        val verdicts = ArrayList<FixTrial.Verdict>()
        var provenAt = 0
        var worse = 0
        var taken = 0

        /** Adaptive keys' nudges, once a fix has been taken: null until then, when the keyboard as it is reads the key under a touch. */
        private var model: Map<Char, Pair<Float, Float>>? = null
        private val asIs: (Char) -> Pair<Float, Float> = { c -> model?.get(c) ?: (0f to 0f) }

        /** A run, and then what the trainer does after one: the taps join the book, and a run typed on a fix is that fix's test. */
        fun run(story: String, thumb: Thumb, random: Random, ac: Autocorrect?): Outcome {
            // The lean this run is typed with: the thumb's own, and how far it has strayed today.
            val today = if (thumb.wander <= 0f) thumb else Thumb(
                thumb.leanX + (random.nextGaussian() * thumb.wander).toFloat(), thumb.leanY + (random.nextGaussian() * thumb.wander).toFloat(), thumb.scatterX, thumb.scatterY, thumb.wild,
            )
            val worn = if (fixing) trial.begin(model != null, asIs, board.keys, book.aims["qwerty"].orEmpty()) else null
            val out = type(story, today, random, worn?.let { w -> { c: Char -> w[c] ?: (0f to 0f) } }, if (model == null) null else asIs, ac)
            val r = out.result
            book.add(RunLine(outcomes.size.toLong(), "sim-${outcomes.size}", "", false, "normal", false, r.ms, r.wpm, r.accuracy, r.words, r.right, r.firstTry, r.backspaces, r.mended.size, r.spoiled.size), r, "qwerty")
            outcomes += out
            if (worn != null) {
                val v = trial.record(FixTrial.FixRun(trial.number, r.taps, r.asIs, r.hits, r.won, r.lost))
                verdicts += v
                if (v == FixTrial.Verdict.PROVEN && provenAt == 0) provenAt = outcomes.size
                if (v == FixTrial.Verdict.WORSE) worse++
                if (v == FixTrial.Verdict.PROVEN && taking) {
                    model = board.keys.associateWith { trial.offset(it, asIs) }
                    trial.took()
                    taken++
                }
            }
            return out
        }
    }
}
