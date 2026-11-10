import com.infamousvague.devkeyboard.Autocorrect
import com.infamousvague.devkeyboard.LanguageState
import com.infamousvague.devkeyboard.NextWordText
import com.infamousvague.devkeyboard.NgramTable
import com.infamousvague.devkeyboard.PersonalPairs
import com.infamousvague.devkeyboard.Scorer
import java.io.File

/**
 * How well next-word prediction does on sentences it never saw: tool/next_words.py holds every 20th Tatoeba sentence
 * out of the counting and writes its words to build/ngram-eval/<language>.tsv. Each held-out word is "typed" with the
 * keyboard's own Scorer and Autocorrect.suggest, and two numbers come out:
 * - next word, top 3: before a letter is typed, how often the word is one of the three the row offers;
 * - keystrokes saved: of the keys needed to type each word and its space, the share saved by tapping it in the row
 *   as soon as it is one of the first three offered (the tap counts as a key, and puts the space).
 *
 * With -Dlearn=true each word is learned once it has been typed, as the keyboard learns a person's typing, so the
 * numbers show what that adds (on Tatoeba's mixed sentences, a small part of what one person's habits give).
 *
 *   tool/next_word_eval.sh [table.tsv ...]   # each table in turn (default: the bundled one), on the same words
 */
fun main(args: Array<String>) {
    val root = File(args[0])
    val assets = File(root, "android/app/src/main/assets")
    val lang = System.getProperty("lang", "en")
    val limit = System.getProperty("events", "20000").toInt()
    val events = File(root, "build/ngram-eval/$lang.tsv").readLines().take(limit).map { it.split('\t') }
    val state = LanguageState.of(listOf(lang), emptyMap(), "")
    val ac = Autocorrect { name -> File(assets, name).takeIf { it.exists() }?.inputStream() }
    ac.setLanguages(state.dictionaries, state.board)
    while (!ac.settled || !ac.active) Thread.sleep(20)
    val learning = System.getProperty("learn") == "true"
    var personal = PersonalPairs()
    val lookup = object : Scorer.Lookup {
        override fun isWord(lower: String) = ac.isWord(lower)
        override fun form(lower: String) = personal.form(lower)
        override fun count(lower: String) = personal.unigram(lower)
        override fun starting(prefix: String) = personal.starting(prefix)
        override fun fills() = ac.commonest(6)
    }
    val tables = args.drop(1).ifEmpty { listOf(File(assets, "ngram/$lang.tsv").path) }
    val priors = System.getProperty("priors", "${Scorer.PRIOR_TABLE_TWO}").split(',').map { it.toFloat() }
    val weights = System.getProperty("weights", "${Scorer.LIFT_WEIGHT}").split(',').map { it.toFloat() }
    val caps = System.getProperty("caps", "${Scorer.LIFT_CAP}").split(',').map { it.toFloat() }
    for (path in tables) for (prior in priors) for (weight in weights) for (cap in caps) {
        Scorer.PRIOR_TABLE_TWO = prior
        Scorer.LIFT_WEIGHT = weight
        Scorer.LIFT_CAP = cap
        val table = File(path).useLines { NgramTable.parse(it) }!!
        personal = PersonalPairs()
        var hit1 = 0; var hit3 = 0; var keysPlain = 0L; var keysUsed = 0L
        for (e in events) {
            val parts = e[0].split(" ")
            val p2 = if (e[0].isEmpty()) null else parts[0]
            val prev = e[1]
            val raw = e[2]
            val word = raw.lowercase()
            val recent = listOfNotNull(p2?.takeIf { it != NextWordText.START }, prev.takeIf { it != NextWordText.START })
            val one = personal.followers(prev)
            val two = if (p2 != null && prev != NextWordText.START) personal.followers("$p2 $prev") else emptyList()
            fun scorer(typing: String) = Scorer(NextWordText.Place(p2, prev, typing, recent), listOf(table), floatArrayOf(1f), one, two, lookup)
            val top = scorer("").top(3).map { it.lowercase() }
            if (top.firstOrNull() == word) hit1++
            if (word in top) hit3++
            val plain = raw.length + 1
            var used = plain
            if (word in top) used = 1 else {
                for (k in 1 until raw.length) {
                    val offered = ac.suggest(raw.substring(0, k), scorer(raw.substring(0, k)).ready()).words.take(3).map { it.lowercase() }
                    if (word in offered) { used = k + 1; break }
                }
            }
            keysPlain += plain; keysUsed += used
            if (learning) {
                personal.learn(NextWordText.Learned(p2, prev, word, raw))
                if (personal.events % 2000 == 0) personal.maintain()
            }
        }
        val n = events.size.coerceAtLeast(1)
        print(if (learning) "learning " else "")
        println(String.format("%-14s prior %5.1f lift %5.2f cap %4.1f  next word top 1 %5.1f%%  top 3 %5.1f%%  keystrokes saved %5.1f%%  (%d words)",
            File(path).name, prior, weight, cap, 100.0 * hit1 / n, 100.0 * hit3 / n, 100.0 * (keysPlain - keysUsed) / keysPlain, events.size))
    }
}
