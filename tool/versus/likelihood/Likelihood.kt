// What Scorer.Here makes of sentences it never saw (tool/versus/likelihood.sh): for every word of Tatoeba's held-out
// English sentences that a word list has, what the word was worth where it stood (9 + log10 of how likely the
// starter tables made it after the words before), beside what its own frequency makes it worth anywhere. The higher
// the mean, the better the words before foretold the words that came. This is where Here's smoothing is set
// (Scorer.UNLISTED, PRIOR_TABLE_ONE, PRIOR_TABLE_TWO), not on the sentences autocorrect is scored on.
//
// It also says whether Here is honest about the words a row does not list: for the 60 commonest words, how often it
// expected each where no row lists it, against how often it was written there. A model that shares the left-over
// out by frequency alone expects "the" after "I" as often as after anything.
//
// Reads build/typing-bench/en-heldout.txt below line 90,000 only: the lines from there on are what tool/versus
// types, and are never looked at here.
//
// Usage: Likelihood <repo root> [--from N] [--to M] [--set Scorer.NAME=VALUE ...]   (lines N+1 to M; M at most 90,000)

import com.infamousvague.devkeyboard.NextWordText
import com.infamousvague.devkeyboard.NgramTable
import com.infamousvague.devkeyboard.Scorer
import java.io.File

const val LAST_LINE = 90_000

fun main(args: Array<String>) {
    val root = File(args[0])
    var from = 0
    var to = LAST_LINE
    var i = 1
    while (i < args.size) {
        when (args[i]) {
            "--from" -> { from = args[i + 1].toInt(); i++ }
            "--to" -> { to = args[i + 1].toInt(); i++ }
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
    var expectedListed = 0.0; var expectedUnlisted = 0.0; var writtenListed = 0; var writtenUnlisted = 0
    var lines = 0
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
            }
        }
    }
    fun say(name: String, s: Sum) = println(String.format("  %-52s %7d words   worth by their own frequency %.3f   worth here %.3f   (%+.3f)", name, s.n, s.own / s.n.coerceAtLeast(1), s.here / s.n.coerceAtLeast(1), (s.here - s.own) / s.n.coerceAtLeast(1)))
    println("held-out lines ${from + 1} to ${from + lines}: UNLISTED ${Scorer.UNLISTED}, PRIOR_TABLE_ONE ${Scorer.PRIOR_TABLE_ONE}, PRIOR_TABLE_TWO ${Scorer.PRIOR_TABLE_TWO}, SEEN_LEAST ${Scorer.SEEN_LEAST}")
    say("every word a list has", all)
    say("after words no table has a row for", none)
    say("where a row lists the word", listed)
    say("where the rows there do not list it", unlisted)
    println(String.format("  the 60 commonest words, where no row lists them: expected %.0f times, written %d;   where a row does: expected %.0f, written %d", expectedUnlisted, writtenUnlisted, expectedListed, writtenListed))
}
