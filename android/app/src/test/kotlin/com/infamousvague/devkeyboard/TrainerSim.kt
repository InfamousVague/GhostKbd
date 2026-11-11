package com.infamousvague.devkeyboard

import java.util.Random

/**
 * A simulated thumb typing the trainer's stories, for TrainerAdaptationTest and tool/trainer_sim.sh: does a thumb that
 * always lands off the keys' middles the same way make fewer mistakes as the runs go by, with the trainer moving the
 * keys' touch areas, and not without it?
 *
 * Everything the phone would do is the app's own code: autocorrect reads each word by where its taps landed
 * ([Autocorrect.correct] with [Taps]), the run is written down and scored by [TrainerRecorder] and [TrainerScore], the
 * taps are kept in a [TrainerBook], and the nudges are stepped by [TrainerAims.steps]. What is redrawn here is only
 * the part that lives in Android's view: which key a touch is read as ([read]: KeyboardView.keyAt and adapt) and
 * where a key listens ([Aims.offset]: AdaptiveKeys.offset), each a few lines kept beside the original's rule.
 *
 * A simulated thumb is not a person (docs/typing-accuracy.md). This says the adaptation does what it is meant to
 * for a thumb with a steady lean; how real thumbs lean is what the trainer measures on a phone.
 */
object TrainerSim {
    /** A drawn QWERTY: a key 44 points wide in a row of 60, as the keyboard is at its Normal height. */
    const val W = 100f
    const val H = 136f
    val board: Map<Char, Pair<Float, Int>> = Layouts.QWERTY.positions
    private val rows: List<List<Char>> = (0..2).map { r -> board.filter { it.value.second == r }.keys.sortedBy { board.getValue(it).first } }
    private val space = floatArrayOf(2.5f * W, 3f * H, 7.5f * W, 4f * H)

    /** The middle of [c]'s key, across in key widths and down in rows. */
    fun middle(c: Char): Pair<Float, Float> = board.getValue(c).let { (it.first + 0.5f) to (it.second + 0.5f) }

    /** How a thumb misses: always ([leanX], [leanY]) of a key off the middle it aims for, with a scatter about that, and a share of wilder taps. */
    class Thumb(val leanX: Float, val leanY: Float, val scatterX: Float = 0.20f, val scatterY: Float = 0.15f, val wild: Float = 0.06f)

    /** The nudges as [AdaptiveKeys] keeps them: for each letter how far from its middle it listens, and the touches it has seen. */
    class Aims {
        val at = HashMap<Char, TrainerAims.Aim>()
        val seen = HashMap<Char, Int>()

        /** AdaptiveKeys.offset: none until the key has its touches, none under a fiftieth of a key, never more than its bound (TrainerTest holds TrainerAims' three numbers to AdaptiveKeys' own). */
        fun offset(c: Char): Pair<Float, Float> {
            val a = at[c] ?: return 0f to 0f
            if ((seen[c] ?: 0) < TrainerAims.MIN_SAMPLES) return 0f to 0f
            if (kotlin.math.abs(a.dx) < TrainerAims.MIN_SHIFT && kotlin.math.abs(a.dy) < TrainerAims.MIN_SHIFT) return 0f to 0f
            return a.dx.coerceIn(-TrainerAims.MAX_SHIFT, TrainerAims.MAX_SHIFT) to a.dy.coerceIn(-TrainerAims.MAX_SHIFT, TrainerAims.MAX_SHIFT)
        }

        /** AdaptiveKeys.trained. */
        fun trained(c: Char, aim: TrainerAims.Aim, taps: Int) { at[c] = aim; seen[c] = (seen[c] ?: 0) + taps }
    }

    /**
     * The key a touch at ([x], [y]) is read as (key widths across, rows down): a letter, ' ' for the space bar, or 0
     * for another key of the bottom row. KeyboardView.keyAt: the key under the touch, or past a row's ends the
     * nearest key of it; then KeyboardView.adapt: of that letter and the letters within a key and a half of it, the
     * one whose nudged middle is nearest.
     */
    fun read(x: Float, y: Float, aims: Aims?): Char {
        if (y >= 3f) return if (x >= 2.5f && x < 7.5f) ' ' else 0.toChar()
        val row = rows[kotlin.math.floor(y.toDouble()).toInt().coerceIn(0, 2)]
        val hit = row.firstOrNull { x >= board.getValue(it).first && x < board.getValue(it).first + 1f }
            ?: return row.minByOrNull { kotlin.math.abs(x - middle(it).first) }!!
        if (aims == null) return hit
        val (hx, hy) = middle(hit)
        return board.keys.filter { kotlin.math.abs(middle(it).first - hx) < 1.6f && kotlin.math.abs(middle(it).second - hy) < 1.6f }.minByOrNull { c ->
            val (ox, oy) = aims.offset(c)
            val dx = x - middle(c).first - ox
            val dy = y - middle(c).second - oy
            dx * dx + dy * dy
        } ?: hit
    }

    /** What a run came to: the trainer's own result, and the story's letters. */
    class Outcome(val result: RunResult, val letters: Int) {
        /** Taps read as another key than the one meant, in a hundred. */
        val keyErrors: Float get() = if (result.taps == 0) 0f else 100f * (result.taps - result.hits) / result.taps
        /** Story words that did not come out right, autocorrect and all, in a hundred. */
        val wordErrors: Float get() = 100f * (result.words - result.right) / result.words
    }

    /**
     * [thumb] types [story] once: every letter aimed at its key's middle and landing where the thumb's lean and
     * scatter put it, the space bar the same. It never looks back, so what it gets wrong stays wrong but for what
     * autocorrect [ac] mends. [aims] are the nudges the keyboard reads the touches with (null: none, the keys as drawn).
     */
    fun type(story: String, thumb: Thumb, random: Random, aims: Aims?, ac: Autocorrect?): Outcome {
        val run = TrainerRecorder(story)
        var text = ""
        var at = 1_000L
        fun off(scatter: Float) = (random.nextGaussian() * scatter * (if (random.nextFloat() < thumb.wild) 2.2 else 1.0)).toFloat()
        // Where each letter key listens now, for autocorrect's reading of the taps (KeyboardView.tapKeys).
        val keys = board.keys.associateWith { c -> val (ox, oy) = aims?.offset(c) ?: (0f to 0f); ((middle(c).first + ox) * W) to ((middle(c).second + oy) * H) }
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
            val key = read(x, y, aims)
            when (key) {
                0.toChar() -> Unit
                ' ' -> end(" ")
                else -> {
                    at += 250
                    text += if (capital) key.uppercaseChar() else key
                    xs += x * W; ys += y * H
                    run.key(at, KeyKind.CHAR, text, KeyTap(key, x - middle(key).first, y - middle(key).second))
                }
            }
        }

        var letters = 0
        for (c in story) {
            when {
                c == ' ' -> touch(5f, 3.5f, false)
                c.lowercaseChar() in board -> { letters++; val (mx, my) = middle(c.lowercaseChar()); touch(mx, my, c.isUpperCase()) }
                // A full stop or a comma: its key is not a letter's, and the thumb is given it.
                else -> end(c.toString())
            }
        }
        return Outcome(TrainerScore.of(story, run.steps, board), letters)
    }

    /** One line of a trainer in use: the runs typed so far, and what each came to. */
    class Course(val adapting: Boolean) {
        val book = TrainerBook()
        val aims = Aims()
        val outcomes = ArrayList<Outcome>()

        /** A run, and then what the trainer does after one: the taps join the book, and with [adapting] each key's nudge takes its step. */
        fun run(story: String, thumb: Thumb, random: Random, ac: Autocorrect?): Outcome {
            val out = type(story, thumb, random, if (adapting) aims else null, ac)
            val r = out.result
            book.add(RunLine(outcomes.size.toLong(), "sim-${outcomes.size}", "", false, "normal", false, r.ms, r.wpm, r.accuracy, r.words, r.right, r.firstTry, r.backspaces, r.mended.size, r.spoiled.size), r, "qwerty")
            if (adapting) {
                val touched = r.perKey.filter { it.value.aimed > 0 }.mapValues { it.value.aimed }
                val moved = TrainerAims.steps(touched, book.aims.getValue("qwerty"), { aims.seen[it] ?: 0 }) { aims.at[it] ?: TrainerAims.Aim(0f, 0f) }
                for ((key, taps) in touched) aims.trained(key, moved[key] ?: aims.at[key] ?: TrainerAims.Aim(0f, 0f), taps)
            }
            outcomes += out
            return out
        }
    }
}
