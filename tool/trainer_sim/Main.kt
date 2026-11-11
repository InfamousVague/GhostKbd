// The typing trainer's simulated thumb (tool/trainer_sim.sh): the app's own autocorrect, run scoring and fixes, typed
// at by a thumb with a steady lean, on the trainer's fixes and on the keyboard as it is; the trainer's test of those
// fixes tried on thumbs with a lean and with none, to see how often it says "proven"; and the layout trials tried on
// simulated people for whom one key height is better, or none. The numbers in docs/trainer.md are this file's.
// android/app/src/test/.../TrainerSim.kt is the thumb; TrainerAdaptationTest holds the result to its direction on
// every build.
//
// Usage: Main <repo root>   (-Druns=12 -Dseeds=20 -Dthumbs=400 -Dlean=0.24,0.18 -Dscatter=0.20,0.15)

import com.infamousvague.devkeyboard.Autocorrect
import com.infamousvague.devkeyboard.FixTrial
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
        // Each seed is one thumb's course of runs, typed twice on the very same touches: on the keyboard as it is,
        // and with every run after the first typed on a fix. A run typed on a fix is read both ways as it goes.
        val keys = Array(2) { FloatArray(runs) }
        val words = Array(2) { FloatArray(runs) }
        val asIs = FloatArray(runs)
        var moved = 0f
        var far = 0f
        for (seed in 1..seeds) for (mode in 0..1) {
            val course = TrainerSim.Course(fixing = mode == 1)
            val random = Random(seed * 7919L)
            for (r in 0 until runs) {
                val out = course.run(parts[(seed * 5 + r) % parts.size], thumb, random, ac)
                keys[mode][r] += out.keyErrors / seeds
                words[mode][r] += out.wordErrors / seeds
                if (mode == 1) asIs[r] += out.keyErrorsAsIs / seeds
            }
            if (mode == 1) {
                moved += course.trial.differing(TrainerSim.board.keys) { 0f to 0f }.size.toFloat() / seeds
                far = maxOf(far, course.trial.at.values.maxOfOrNull { maxOf(kotlin.math.abs(it.dx), kotlin.math.abs(it.dy)) } ?: 0f)
            }
        }
        println("\n$title: lean ${lean[0]} across, ${lean[1]} down; scatter ${scatter[0]}, ${scatter[1]}; $seeds thumbs, $runs runs each")
        println("  run   taps on the wrong key, %                        words wrong after autocorrect, %")
        println("        as it is    on the fixes   same taps, as it is   as it is    on the fixes")
        for (r in 0 until runs) println("  %3d   %8.1f   %10.1f   %17.1f   %8.1f   %10.1f".format(r + 1, keys[0][r], keys[1][r], asIs[r], words[0][r], words[1][r]))
        fun mean(a: FloatArray, from: Int, to: Int) = a.slice(from until to).average()
        println("  first two runs to last four: as it is %.1f -> %.1f, on the fixes %.1f -> %.1f (keys); as it is %.1f -> %.1f, on the fixes %.1f -> %.1f (words)".format(
            mean(keys[0], 0, 2), mean(keys[0], runs - 4, runs), mean(keys[1], 0, 2), mean(keys[1], runs - 4, runs),
            mean(words[0], 0, 2), mean(words[0], runs - 4, runs), mean(words[1], 0, 2), mean(words[1], runs - 4, runs)))
        println("  keys the last fix has moved: %.1f; the furthest any went: %.2f of a key (bound %.2f, step %.2f a fix)".format(moved, far, TrainerAims.MAX_SHIFT, TrainerAims.STEP))
    }

    /**
     * The test of the fixes on [thumbs] thumbs of one kind, [runs] runs each: how many were told a fix was proven,
     * and by which run; how many had a fix dropped as worse; and what the two readings of the same taps came to in
     * the runs that tested one. Autocorrect is left out: it changes no tap, and the test reads taps.
     */
    fun proof(title: String, thumb: TrainerSim.Thumb, thumbs: Int, taking: Boolean = false) {
        val by = IntArray(runs + 1)
        var proven = 0
        var dropped = 0
        var taken = 0
        var taps = 0L; var asIs = 0L; var fixed = 0L; var won = 0L; var lost = 0L
        for (seed in 1..thumbs) {
            val course = TrainerSim.Course(fixing = true, taking = taking)
            val random = Random(seed * 15_485_863L + 11)
            var tested = 0
            for (r in 0 until runs) {
                val out = course.run(parts[(seed * 5 + r) % parts.size], thumb, random, null).result
                if (course.verdicts.size > tested) { tested = course.verdicts.size; taps += out.taps; asIs += out.asIs; fixed += out.hits; won += out.won; lost += out.lost }
            }
            if (course.provenAt > 0) { proven++; by[course.provenAt]++ }
            if (course.worse > 0) dropped++
            taken += course.taken
        }
        var seen = 0
        val soFar = (1..runs).map { seen += by[it]; seen }
        println("\n$title: lean ${thumb.leanX} across, ${thumb.leanY} down${if (thumb.wander > 0f) ", straying ${thumb.wander} from run to run" else ""}; $thumbs thumbs, $runs runs each${if (taking) ", each fix taken when proven" else ""}")
        println("  told a fix was proven: %.1f%% (%d of %d)".format(100f * proven / thumbs, proven, thumbs))
        println("  proven by run: " + listOf(3, 4, 5, 6, 8, runs).distinct().filter { it <= runs }.joinToString(", ") { "$it: %.1f%%".format(100f * soFar[it - 1] / thumbs) })
        println("  had a fix dropped as worse: %.1f%%".format(100f * dropped / thumbs))
        if (taking) println("  fixes taken: %.2f a thumb".format(taken.toFloat() / thumbs))
        if (taps > 0) println("  the runs that tested a fix, read both ways: as it is %.1f%% of taps on their key, on the fix %.1f%%; of the taps read differently, %d went the fix's way and %d the keyboard's".format(100.0 * asIs / taps, 100.0 * fixed / taps, won, lost))
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

    // -Donly=tables, proof or layouts prints that part alone.
    val only = System.getProperty("only", "")
    if (only.isEmpty() || only == "tables") {
        table("A thumb that lands right and low", pair("lean", "0.24,0.18"))
        table("A thumb that lands left and high", listOf(-0.2f, -0.15f))
        table("A thumb with a small lean", listOf(0.1f, 0.06f))
        table("A thumb with no lean", listOf(0f, 0f))
    }

    val thumbs = System.getProperty("thumbs", "400").toInt()
    fun thumb(x: Float, y: Float, wander: Float = 0f) = TrainerSim.Thumb(x, y, scatter[0], scatter[1], wander = wander)
    if (only.isEmpty() || only == "proof") {
        println("\nThe test of the fixes: proven needs ${FixTrial.MIN_RUNS} runs or more, a split of the taps read differently that chance gives less than once in ${FixTrial.ONE_IN}, a lead from run to run steadier than chance gives once in 100, and the last run not against it")
        proof("Proof for a thumb that lands right and low", thumb(0.24f, 0.18f), thumbs)
        proof("Proof for a thumb that lands left and high", thumb(-0.2f, -0.15f), thumbs)
        proof("Proof for a thumb with a small lean", thumb(0.1f, 0.06f), thumbs)
        proof("Proof for a thumb with a slight lean", thumb(0.05f, 0.03f), thumbs)
        proof("Proof for a thumb with no lean", thumb(0f, 0f), thumbs)
        proof("Proof for a thumb with no lean of its own that strays", thumb(0f, 0f, 0.08f), thumbs)
        proof("Proof for a thumb with no lean of its own that strays far", thumb(0f, 0f, 0.15f), thumbs)
        proof("Proof for a thumb with a small lean that strays", thumb(0.1f, 0.06f, 0.08f), thumbs)
        proof("Proof for a thumb that lands right and low and strays", thumb(0.24f, 0.18f, 0.08f), thumbs)
        proof("Proof for a thumb that lands right and low, taking each proven fix", thumb(0.24f, 0.18f), thumbs, taking = true)
        proof("Proof for a thumb with no lean, taking each proven fix", thumb(0f, 0f), thumbs, taking = true)
    }

    if (only.isEmpty() || only == "layouts")
    for (noise in listOf(0.05f, 0.07f)) {
        layouts("Layout trials where no height is better", 2000, noise, 120) { 40f }
        layouts("Layout trials where Short is a tenth better than Normal", 2000, noise, 120) { mapOf("condensed" to 39f, "short" to 44f, "normal" to 40f, "tall" to 38f).getValue(it) }
        layouts("Layout trials where Short is six in a hundred better", 2000, noise, 120) { mapOf("condensed" to 39.5f, "short" to 42.4f, "normal" to 40f, "tall" to 39f).getValue(it) }
    }
}
