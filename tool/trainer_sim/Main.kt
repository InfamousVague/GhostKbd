// The typing trainer's simulated thumb (tool/trainer_sim.sh): the app's own autocorrect, run scoring and aim steps,
// typed at by a thumb with a steady lean, with the trainer moving the keys' touch areas and without; and the layout
// trials tried on simulated people for whom one key height is better, or none. The numbers in docs/trainer.md are
// this file's. android/app/src/test/.../TrainerSim.kt is the thumb; TrainerAdaptationTest holds
// the result to its direction on every build.
//
// Usage: Main <repo root>   (-Druns=12 -Dseeds=20 -Dlean=0.24,0.18 -Dscatter=0.20,0.15)

import com.infamousvague.devkeyboard.Autocorrect
import com.infamousvague.devkeyboard.LanguageState
import com.infamousvague.devkeyboard.LayoutTrials
import com.infamousvague.devkeyboard.TrainerAims
import com.infamousvague.devkeyboard.TrainerSim
import com.infamousvague.devkeyboard.TrainerStories
import java.io.File
import java.util.Random

fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: ".")
    val assets = File(root, "android/app/src/main/assets")
    val ac = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
    val state = LanguageState.of(listOf("en"), emptyMap(), "")
    ac.loadNow(state.dictionaries, state.board)
    val parts = TrainerStories.parse(File(assets, "trainer/stories.txt").readText()).flatMap { it.parts }
    val runs = System.getProperty("runs", "12").toInt()
    val seeds = System.getProperty("seeds", "20").toInt()
    fun pair(name: String, default: String) = System.getProperty(name, default).split(',').map { it.toFloat() }
    val scatter = pair("scatter", "0.20,0.15")

    fun table(title: String, lean: List<Float>) {
        val thumb = TrainerSim.Thumb(lean[0], lean[1], scatter[0], scatter[1])
        // Each seed is one thumb's course of runs, typed twice on the very same touches: with the steps, and without.
        val keys = Array(2) { FloatArray(runs) }
        val words = Array(2) { FloatArray(runs) }
        var nudged = 0f
        var far = 0f
        for (seed in 1..seeds) for (mode in 0..1) {
            val course = TrainerSim.Course(adapting = mode == 1)
            val random = Random(seed * 7919L)
            for (r in 0 until runs) {
                val out = course.run(parts[(seed * 5 + r) % parts.size], thumb, random, ac)
                keys[mode][r] += out.keyErrors / seeds
                words[mode][r] += out.wordErrors / seeds
            }
            if (mode == 1) {
                nudged += course.aims.at.keys.count { course.aims.offset(it) != (0f to 0f) }.toFloat() / seeds
                far = maxOf(far, course.aims.at.values.maxOfOrNull { maxOf(kotlin.math.abs(it.dx), kotlin.math.abs(it.dy)) } ?: 0f)
            }
        }
        println("\n$title: lean ${lean[0]} across, ${lean[1]} down; scatter ${scatter[0]}, ${scatter[1]}; $seeds thumbs, $runs runs each")
        println("  run   taps on the wrong key, %      words wrong after autocorrect, %")
        println("        keys as drawn   with steps     keys as drawn   with steps")
        for (r in 0 until runs) println("  %3d   %10.1f   %10.1f     %10.1f   %10.1f".format(r + 1, keys[0][r], keys[1][r], words[0][r], words[1][r]))
        fun mean(a: FloatArray, from: Int, to: Int) = a.slice(from until to).average()
        println("  first two runs to last four: as drawn %.1f -> %.1f, with steps %.1f -> %.1f (keys); as drawn %.1f -> %.1f, with steps %.1f -> %.1f (words)".format(
            mean(keys[0], 0, 2), mean(keys[0], runs - 4, runs), mean(keys[1], 0, 2), mean(keys[1], runs - 4, runs),
            mean(words[0], 0, 2), mean(words[0], runs - 4, runs), mean(words[1], 0, 2), mean(words[1], runs - 4, runs)))
        println("  keys with a nudge at the end: %.1f; the furthest any nudge went: %.2f of a key (bound %.2f, step %.2f a run)".format(nudged, far, TrainerAims.MAX_SHIFT, TrainerAims.STEP))
    }

    /**
     * The layout trials on [people] simulated people who all start on Normal keys: a person's score on a height is
     * [worth] of it, give or take [noise] of it a run. How many end with which height as home, and after how many runs
     * the trials had settled (or [most] runs, where they had not).
     */
    fun layouts(title: String, people: Int, noise: Float, most: Int, worth: (String) -> Float) {
        val homes = HashMap<String, Int>()
        var runs = 0L
        var settled = 0
        for (person in 1..people) {
            val random = Random(person * 104_729L)
            val trials = LayoutTrials()
            trials.anchor("normal")
            var n = 0
            while (n < most && !trials.settled) { val h = trials.next(); trials.record(h, worth(h) * (1f + noise * random.nextGaussian().toFloat())); n++ }
            runs += n
            if (trials.settled) settled++
            homes[trials.home] = (homes[trials.home] ?: 0) + 1
        }
        println("\n$title: $people people, runs scattering by ${(noise * 100).toInt()} in a hundred of their score")
        println("  home at the end: " + LayoutTrials.HEIGHTS.joinToString(", ") { "$it %.1f%%".format(100f * (homes[it] ?: 0) / people) })
        println("  runs until the trials settled: %.1f on average (%.1f%% settled within $most)".format(runs.toFloat() / people, 100f * settled / people))
    }

    table("A thumb that lands right and low", pair("lean", "0.24,0.18"))
    table("A thumb that lands left and high", listOf(-0.2f, -0.15f))
    table("A thumb with a small lean", listOf(0.1f, 0.06f))
    table("A thumb with no lean", listOf(0f, 0f))

    for (noise in listOf(0.05f, 0.07f)) {
        layouts("Layout trials where no height is better", 2000, noise, 80) { 40f }
        layouts("Layout trials where Short is a tenth better than Normal", 2000, noise, 80) { mapOf("condensed" to 39f, "short" to 44f, "normal" to 40f, "tall" to 38f).getValue(it) }
        layouts("Layout trials where Short is six in a hundred better", 2000, noise, 80) { mapOf("condensed" to 39.5f, "short" to 42.4f, "normal" to 40f, "tall" to 39f).getValue(it) }
    }
}
