// What Scorer.Here makes of sentences it never saw (tool/versus/likelihood.sh): for every word of Tatoeba's held-out
// English sentences that a word list has, what the word was worth where it stood (9 + log10 of how likely the
// starter tables made it after the words before), beside what its own frequency makes it worth anywhere. The higher
// the mean, the better the words before foretold the words that came. This is where Here's smoothing is set
// (Scorer.UNLISTED, PRIOR_HERE_ONE, PRIOR_HERE_TWO), not on the sentences autocorrect is scored on.
//
// Three things are printed.
// - The plain mean, over every word. It is unfair to any bound on unlisted words: what a bound takes from the words
//   it holds down is given to no other word, and a mean over the words that were written only ever sees the taking.
// - The same at one place in every --sum (97) that has a row, with every listed word's share there made to sum to
//   one: what a word is worth among the readings autocorrect could weigh it against, which is the only use it is put
//   to. This is the figure to set the smoothing by.
// - Whether Here is honest about the words a row does not list: how often it expected the 60 commonest words where
//   no row lists them against how often they were written there, and at the sampled places the same for unlisted
//   words by how common they are. A model that shares the left-over out by frequency alone expects "the" after "I"
//   as often as after anything.
//
// Reads build/typing-bench/en-heldout.txt below line 90,000 only: the lines from there on are what tool/versus
// types, and are never looked at here.
//
// Usage: Likelihood <repo root> [--from N] [--to M] [--sum K] [--set Scorer.NAME=VALUE ...]   (lines N+1 to M; M at most 90,000)

import com.infamousvague.devkeyboard.NextWordText
import com.infamousvague.devkeyboard.NgramTable
import com.infamousvague.devkeyboard.Scorer
import java.io.File
import kotlin.math.log10
import kotlin.math.pow

const val LAST_LINE = 90_000

fun main(args: Array<String>) {
    val root = File(args[0])
    var from = 0
    var to = LAST_LINE
    var every = 97
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--from" -> { from = args[i + 1].toInt(); i++ }
            "--to" -> { to = args[i + 1].toInt(); i++ }
            "--sum" -> { every = args[i + 1].toInt(); i++ }
            "--set" -> {
                val name = args[i + 1].substringBefore('=')
                val value = args[i + 1].substringAfter('=')
                Class.forName("com.infamousvague.devkeyboard." + name.substringBefore('.')).getField(name.substringAfter('.')).setFloat(null, value.toFloat())
                System.err.println("dial: $name = $value")
                i++
            }
        }
        i++
    }
    require(to <= LAST_LINE) { "likelihood: the lines after $LAST_LINE are tool/versus's own, and are not read here" }
    val assets = File(root, "android/app/src/main/assets")
    val zipf = HashMap<String, Float>(60_000)
    File(assets, "dict/en.tsv").forEachLine { line ->
        val tab = line.indexOf('\t')
        if (tab > 0) line.substring(tab + 1).toFloatOrNull()?.let { z -> if (z > (zipf[line.substring(0, tab)] ?: 0f)) zipf[line.substring(0, tab)] = z }
    }
    val vocabulary = zipf.keys.toTypedArray()
    val zipfs = FloatArray(vocabulary.size) { zipf[vocabulary[it]]!! }
    val ownSum = zipfs.sumOf { 10.0.pow(it - 9.0) }
    val table = File(assets, "ngram/en.tsv").useLines { NgramTable.parse(it) }!!
    val lookup = object : Scorer.Lookup {
        override fun isWord(lower: String) = zipf.containsKey(lower)
        override fun form(lower: String) = lower
        override fun count(lower: String) = 0f
        override fun starting(prefix: String) = emptyList<Pair<String, Float>>()
        override fun fills() = emptyList<String>()
    }
    val commonest = zipf.entries.sortedByDescending { it.value }.take(60).map { it.key }
    // Words: how many, what they were worth by their own frequency, and what they were worth here.
    class Sum { var n = 0; var own = 0.0; var here = 0.0 }
    val all = Sum(); val none = Sum(); val listed = Sum(); val unlisted = Sum()
    val summed = Sum(); val summedListed = Sum(); val summedUnlisted = Sum()
    var expectedListed = 0.0; var expectedUnlisted = 0.0; var writtenListed = 0; var writtenUnlisted = 0
    // Unlisted words at the sampled places, by how common: how many the place expected, and how many were written.
    val bands = floatArrayOf(7f, 6f, 5.5f, 5f, 4.5f, 4f, 0f)
    val expected = DoubleArray(bands.size); val written = IntArray(bands.size)
    fun band(z: Float): Int { for (b in bands.indices) if (z >= bands[b]) return b; return bands.size - 1 }
    var total = 0.0
    var lines = 0
    var places = 0
    File(root, "build/typing-bench/en-heldout.txt").useLines { all_ ->
        for (line in all_.take(to).drop(from)) {
            lines++
            for (l in NextWordText.all(line.replace('’', '\''))) {
                val z = zipf[l.word] ?: continue
                val place = NextWordText.Place(l.prev2, l.prev, "", emptyList())
                val here = Scorer(place, listOf(table), floatArrayOf(1f), emptyList(), emptyList(), lookup).here { zipf[it] }
                val worth = here?.worth(l.word, z) ?: z
                for (s in listOf(all, if (here == null) none else if (here.lists(l.word)) listed else unlisted)) { s.n++; s.own += z; s.here += worth }
                if (here == null) continue
                for (w in commonest) if (here.lists(w)) expectedListed += here.share(w, zipf[w]!!) else expectedUnlisted += here.share(w, zipf[w]!!)
                if (l.word in commonest) { if (here.lists(l.word)) writtenListed++ else writtenUnlisted++ }
                if (every <= 0 || places++ % every != 0) continue
                var sum = 0.0
                val there = DoubleArray(bands.size)
                for (k in vocabulary.indices) {
                    val share = here.share(vocabulary[k], zipfs[k])
                    sum += share
                    if (!here.lists(vocabulary[k])) there[band(zipfs[k])] += share.toDouble()
                }
                for (b in bands.indices) expected[b] += there[b] / sum
                total += sum
                if (!here.lists(l.word)) written[band(z)]++
                val among = 9.0 + log10(here.share(l.word, z).toDouble().coerceAtLeast(1e-12) / sum)
                val ownAmong = 9.0 + log10(10.0.pow(z - 9.0) / ownSum)
                for (s in listOf(summed, if (here.lists(l.word)) summedListed else summedUnlisted)) { s.n++; s.own += ownAmong; s.here += among }
            }
        }
    }
    fun say(name: String, s: Sum) = println(String.format("  %-52s %7d words   worth by their own frequency %.3f   worth here %.3f   (%+.3f)", name, s.n, s.own / s.n.coerceAtLeast(1), s.here / s.n.coerceAtLeast(1), (s.here - s.own) / s.n.coerceAtLeast(1)))
    println("held-out lines ${from + 1} to ${from + lines}: UNLISTED ${Scorer.UNLISTED}, PRIOR_HERE_ONE ${Scorer.PRIOR_HERE_ONE}, PRIOR_HERE_TWO ${Scorer.PRIOR_HERE_TWO}, SEEN_LEAST ${Scorer.SEEN_LEAST}")
    say("every word a list has", all)
    say("after words no table has a row for", none)
    say("where a row lists the word", listed)
    say("where the rows there do not list it", unlisted)
    println(String.format("  the 60 commonest words, where no row lists them: expected %.0f times, written %d;   where a row does: expected %.0f, written %d", expectedUnlisted, writtenUnlisted, expectedListed, writtenListed))
    if (summed.n > 0) {
        println(String.format("  one place in %d with a row, every listed word's share there made to sum to one (they sum to %.3f as they are):", every, total / summed.n))
        say("  every word", summed)
        say("  where a row lists the word", summedListed)
        say("  where the rows there do not list it", summedUnlisted)
        println("    unlisted words there by how common, expected and written: " + bands.indices.joinToString("  ") { b ->
            String.format("%s %.0f/%d", if (b == 0) "7 up" else if (bands[b] == 0f) "under ${bands[b - 1]}" else "${bands[b]}-${bands[b - 1]}", expected[b], written[b])
        } + String.format("  all %.0f/%d", expected.sum(), written.sum()))
    }
}
