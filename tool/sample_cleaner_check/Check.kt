import com.infamousvague.devkeyboard.Board
import com.infamousvague.devkeyboard.Layouts
import com.infamousvague.devkeyboard.Autocorrect
import com.infamousvague.devkeyboard.Retrain
import com.infamousvague.devkeyboard.SampleCleaner
import com.infamousvague.devkeyboard.VoiceRules
import java.io.File
import kotlin.system.exitProcess

/**
 * The self-check for Mockingbird's sample cleaner, the spelling instructions its AIs get, and when a style profile
 * is due its retrain. It runs the app's own SampleCleaner.kt, VoiceRules.kt, Retrain.kt and Autocorrect.kt against
 * the app's own dictionaries, on this computer, with three small stand-ins for the Android classes Autocorrect
 * names (stubs/). Two things only the phone runs are read as source instead: where the keyboard asks for a retrain,
 * and what Settings says about it.
 *
 * Run it with tool/check_sample_cleaner.sh (add --rules to print the instruction texts). It exits 1 on a failure.
 */

private var failed = 0
private var passed = 0

/** One expectation: [what] it is about, what came out, what should have. */
private fun expect(what: String, got: Any?, want: Any?) {
    if (got == want) { passed++; return }
    failed++
    println("FAIL  $what\n      got  ${show(got)}\n      want ${show(want)}")
}

/** A value on one line, its line breaks visible. */
private fun show(v: Any?) = v.toString().replace("\n", "\\n")

/** Every word: the person's samples at their least helpful, where whatever a slip could become is a word they use. */
private object EveryWord : AbstractSet<String>() {
    override val size get() = Int.MAX_VALUE
    override fun iterator() = emptyList<String>().iterator()
    override fun contains(element: String) = true
}

/**
 * A dictionary that would fix anything it is told to, for checking the cleaner's own guards apart from
 * Autocorrect's: [words] are the words it knows, [fixes] what it offers for the rest, and [asked] counts how often
 * a fix was looked up.
 */
private class Eager(val words: Set<String>, val fixes: Map<String, String>) : SampleCleaner.Lexicon {
    var asked = 0
    override val ready = true
    override fun isWord(lower: String) = lower in words
    override fun fix(token: String): String? {
        asked++
        val fix = fixes[token.lowercase()] ?: return null
        return if (token.first().isUpperCase()) fix.replaceFirstChar { it.uppercaseChar() } else fix
    }
}

/**
 * Mockingbird's samples as Mockingbird.record and cleanStored keep them, without the phone: each as [typed] and as
 * the AI [texts] it, and the words [kept] as the person's own.
 */
private class Store(val lexicon: SampleCleaner.Lexicon) {
    val typed = ArrayList<String>()
    var texts = ArrayList<String>()
    var kept: Set<String> = emptySet()

    /** A new sample comes in: cleaned against the others, and the others cleaned again if it made a word theirs. */
    fun add(sample: String) {
        val cleaned = SampleCleaner.cleanNew(sample, typed.toList(), lexicon, kept)
        kept = cleaned.kept
        typed += sample
        texts.add(cleaned.text)
        if (cleaned.fresh) cleanAll()
    }

    /** Every sample cleaned again from what was typed, as before a training. */
    fun cleanAll() {
        val (again, words) = SampleCleaner.cleanAll(typed, lexicon, kept)
        texts = ArrayList(again)
        kept = words
    }

    /** The oldest sample drops off the end of the list. */
    fun evictOldest() { typed.removeAt(0); texts.removeAt(0) }
}

fun main(args: Array<String>) {
    val assets = File(args.firstOrNull { !it.startsWith("--") } ?: "android/app/src/main/assets")
    require(File(assets, "dict/en.tsv").exists()) { "No dictionaries under $assets: pass the app's assets folder" }
    val sources = File(assets.parentFile, "kotlin/com/infamousvague/devkeyboard")
    val repo = assets.absoluteFile.parentFile.parentFile.parentFile.parentFile.parentFile

    // English, with one learned word, and the slang switch OFF: the cleaner must honour the slang list all the same.
    val english = speller(assets)
    english.setLearned(setOf("yiu"))
    english.loadNow(listOf("en"), qwerty(listOf("en")))
    val en = english.lexicon()

    // What the person has typed before: the words a slip may be put back to are here, spelled right.
    val mine = listOf(
        "the cat sat on the mat and that was that",
        "just going to the shop, back in a minute",
        "see you tomorrow at their house",
        "this keyboard is nice because it is quiet",
        "did you receive it? i know the one",
    )
    /** [text] cleaned as Mockingbird would with [mine] already kept. */
    fun clean(text: String) = SampleCleaner.clean(text, en, SampleCleaner.habits(mine + text, en))
    /** [text] cleaned at the dictionaries' most eager: every word a slip could become counts as one the person uses. */
    fun worst(text: String, lexicon: SampleCleaner.Lexicon = en) = SampleCleaner.clean(text, lexicon, SampleCleaner.Habits(emptySet(), EveryWord))
    val inputs = ArrayList<String>()
    fun becomes(text: String, want: String) { inputs += text; expect("clean(${show(text)})", clean(text), want) }
    fun stays(text: String, lexicon: SampleCleaner.Lexicon = en) { inputs += text; expect("left alone: ${show(text)}", worst(text, lexicon), text) }

    // --- slips are repaired ---------------------------------------------------------------------------------------
    becomes("teh cat", "the cat")
    becomes("see you at teh house later", "see you at the house later")
    becomes("i was goign to say that to you", "i was going to say that to you")
    becomes("i jsut got home adn it is late", "i just got home and it is late")
    becomes("thier house is really nice", "their house is really nice")
    becomes("did you recieve the package", "did you receive the package")
    // A long word: one neighbouring key inside it, a consonant doubled, two letters swapped.
    becomes("this keybkard is really nice", "this keyboard is really nice")
    becomes("this keybboard is really nice", "this keyboard is really nice")
    becomes("see you tomorriw at the office", "see you tomorrow at the office")
    becomes("see you tomorrwo at the office", "see you tomorrow at the office")
    // The word's capitals are kept.
    becomes("Teh cat sat.", "The cat sat.")
    becomes("fine then. Teh cat sat on it", "fine then. The cat sat on it")
    becomes("first line.\nTeh second line", "first line.\nThe second line")
    becomes("really?! Teh cat sat on it", "really?! The cat sat on it")
    // An all-lower-case sample stays lower case, and keeps its "i", "dont" and "u".
    becomes("i went to teh shop and i dont know what u want", "i went to the shop and i dont know what u want")
    // Punctuation, spacing, line breaks and emoji round a slip are left exactly.
    becomes("teh  cat ,  sat…\n\nand then?!", "the  cat ,  sat…\n\nand then?!")
    becomes("(teh cat) \"and\" *just* this 😂", "(the cat) \"and\" *just* this 😂")
    becomes("the cat \"adn\" the dog, *really* 😂", "the cat \"and\" the dog, *really* 😂")
    becomes("thats teh one😂 for sure", "thats the one😂 for sure")

    // --- a fix is made only to a word the person uses --------------------------------------------------------------
    // "keybkard" can only be "keyboard", but somebody who has never typed "keyboard" may mean something else by it.
    val stranger = SampleCleaner.habits(listOf("the cat sat on the mat", "this keybkard is really nice"), en)
    expect("the fix is not a word they use: left", SampleCleaner.clean("this keybkard is really nice", en, stranger), "this keybkard is really nice")
    expect("…and it is the samples that say so", "keyboard" in SampleCleaner.habits(mine, en).used, true)
    expect("what one sample needs looked up", SampleCleaner.wanted("this keybkard is really nice", en), setOf("keybkard", "keyboard"))
    expect("nothing to repair, nothing to look up", SampleCleaner.wanted("this keyboard is really nice and u gonna lol with npm and yiu", en), emptySet<String>())
    expect("looking up only those words", SampleCleaner.habits(mine, en, setOf("keybkard", "keyboard")).used, setOf("keyboard"))

    // --- everything deliberate is left ----------------------------------------------------------------------------
    // Each sits in a sentence whose "teh" control (the first line) proves the sentence itself is being cleaned.
    fun frame(word: String) = "i think $word is what we need here today"
    becomes(frame("teh"), frame("the"))
    val untouched = listOf(
        // Words: texting shorthand, slang (its switch is off here), a learned word, a programmer's word.
        "u", "lol", "gonna", "ur", "tbh", "ngl", "sybau", "yiu", "npm",
        // Code-shaped, each built round a slip that would be fixed if it stood alone.
        "useState", "tEh", "TEH", "foo_bar", "teh_cat", "v2.1", "teh2", "teh.cat", "teh/cat", "teh-cat", "--teh",
        "https://exmaple.com/teh", "www.teh.com", "teh@jsut.com", "@handle", "@teh", "#teh", "`teh`", "teh()", ":teh:", "<teh>", "\$teh",
        // A capitalised word mid-sentence is a name.
        "Teh", "Jsut", "Priya", "Keybkard",
        // An emoji, and punctuation by itself.
        "😂", "🤷‍♂️", "...", "?!", "—", "(…)", ":)",
        // Written for effect: stretched, lengthened, laughter, vowel-less abbreviations.
        "heyy", "yess", "sooo", "noooo", "helllo", "hahah", "lolol", "musta", "thx", "tmrw", "pls",
        // A vowel doubled is a word stretched too (the reviewers' six, and the shorter ones).
        "whaat", "loove", "pleease", "thaanks", "craazy", "reaally", "heey", "duude", "daamn", "yeaah",
        // Ways of writing, not slips: no apostrophe, no accent, a spelling the lists have seen.
        "dont", "cant", "im", "cafe", "nite", "yas", "thru", "tho",
        // Too unsure: two fair readings, or no likely word nearby.
        "hwo", "waht", "gurl", "posthog", "kubernetes", "jaxon",
        // A short word is fixed only for swapped letters, and then only to one of the very commonest words.
        "hpuse", "wprk", "pani", "nad", "hodl",
        // Not in the 40,000-word list is not the same as not a word. Terms:
        "vim", "upsert", "recommender", "usrname", "thunk", "forking", "buf", "linted", "looker",
        // Ordinary words the list lacks:
        "forceps", "deplete", "drooping", "recuse", "curation", "roomie", "minuet", "arcing", "preform", "pervious",
        // Names typed in lower case:
        "vishal", "johnathan", "robbin", "tuan",
        // Dialect and stylised spellings:
        "youse", "manky", "oot", "oor", "bby", "congratz",
        // A spelling the lists hold, however rare, is never swapped for a commoner one with the same letters.
        "untied", "prefect", "bleu", "strat", "acme", "emt", "cna", "joni", "micheal", "cation",
    )
    for (word in untouched) stays(frame(word))
    stays("u gonna run npm install for useState in foo_bar v2.1?? lol 😂 see https://exmaple.com/teh or ask @jsut and Whcih, ok... (yes!) — fine; yiu")
    stays("ok!!! wait... what?!  no way — really; (yes) [maybe] \"sure\" 'fine' :) <3")
    // The reviewers' sentences, whole.
    for (text in listOf(
        "open it in vim", "just do an upsert on the users table", "the recommender model", "set usrname and passwd",
        "can you ask vishal to send the deck", "i untied the boat and pushed off", "she was a prefect at her old school",
        "we should grab bleu cheese burgers", "play it on the strat", "acme corp sent the invoice", "the emt got there in five minutes",
        "my mum is a cna", "Joni is coming to dinner", "Micheal said he would be there", "just hodl and wait it out",
        "whaat are you serious right now", "i loove this song so much", "pleease can we go to the beach", "thaanks for the lift", "omg that is craazy lol",
    )) stays(text)
    // With French on too, a rare English word is still not a French one with its letters swapped.
    val french = speller(assets)
    french.loadNow(listOf("en", "fr"), qwerty(listOf("en", "fr")))
    stays("the onus is on you now", french.lexicon())

    // --- a capital proves a name wherever a sentence does not plainly start ---------------------------------------
    // "Jsut" would be fixed at the start of a sentence (the first line): after a line break, an emoji or "Dr." it
    // may as well be a name, so it stands.
    becomes("really. Jsut the one", "really. Just the one")
    stays("Cheers,\nJsut")
    stays("first line\nJsut the second line")
    stays("love you ❤️ Jsut will pick you up")
    stays("thanks Dr. Jsut for the help")
    stays("see Mr. Jsut and St. Jsut about it")
    stays("ok. Jsut the one")
    stays("ask them, e.g. Jsut or the others")
    stays("Cheers,\nVishal")
    stays("thanks Dr. Tuan")
    stays("love you ❤️ Vishal will pick you up")
    val starts = SampleCleaner.tokens("One two. Three\nFour 😂 Five! Six Dr. Seven etc. Eight… Nine ok. Ten then. Eleven").filter { it.sentenceStart }.map { it.word }
    expect("where a sentence plainly starts", starts, listOf("One", "Six", "Nine", "Eleven"))

    // --- typed repeatedly, a word is the person's own -------------------------------------------------------------
    fun deliberate(texts: List<String>, among: Set<String>? = null) = SampleCleaner.habits(texts, en, among).deliberate
    val once = listOf("this keybkard is really nice", "it is cold in here today", "party tonight at mine then") + mine
    expect("a slip typed once is not deliberate", deliberate(once), emptySet<String>())
    expect("a slip typed once is fixed", SampleCleaner.clean(once[0], en, SampleCleaner.habits(once, en)), "this keyboard is really nice")
    val thrice = listOf("this keybkard is really nice", "the keybkard is cold today", "Keybkard party tonight then") + mine
    val theirs = SampleCleaner.habits(thrice, en)
    expect("the same non-word in three samples is deliberate", theirs.deliberate, setOf("keybkard"))
    for (text in thrice) expect("…and is kept in ${show(text)}", SampleCleaner.clean(text, en, theirs), text)
    expect("three times in ONE sample is not enough", deliberate(listOf("hpuse hpuse hpuse is all i said", "nothing to see here")), emptySet<String>())
    expect("twice in two samples is not enough", deliberate(listOf("the hpuse is cold", "my hpuse is warm")), emptySet<String>())
    expect("three times across two samples is", deliberate(listOf("the hpuse is my hpuse", "my hpuse is warm")), setOf("hpuse"))
    expect("counting only one sample's words", deliberate(thrice + "a wirk thing and a wirk thing and wirk", SampleCleaner.wanted(thrice[0], en)), setOf("keybkard"))
    expect("a real word is never deliberate", deliberate(listOf("the cat and u gonna lol with npm and yiu", "the cat and u gonna lol", "the cat and u gonna lol")), emptySet<String>())
    // The other words of a sample with a deliberate word are still cleaned.
    expect("deliberate word kept, slip in the same sample fixed", SampleCleaner.clean("teh cat sat on the mat by my keybkard", en, theirs), "the cat sat on the mat by my keybkard")

    // --- once theirs, a word goes back into the samples it was taken out of, and stays theirs ---------------------
    val store = Store(en)
    for (text in mine) store.add(text)
    store.add("this keybkard is really nice")
    store.add("my keybkard broke again today")
    expect("typed once and twice: read as a slip", store.texts.takeLast(2), listOf("this keyboard is really nice", "my keyboard broke again today"))
    expect("…with nothing kept yet", store.kept, emptySet<String>())
    store.add("the keybkard came in the post")
    expect("the third time it is theirs", store.kept, setOf("keybkard"))
    expect("…and is back in the samples it was taken out of, at once", store.texts.takeLast(3), listOf("this keybkard is really nice", "my keybkard broke again today", "the keybkard came in the post"))
    // The list is full and the oldest sample holding the word drops off: two uses are left.
    while (store.typed.first() != "my keybkard broke again today") store.evictOldest()
    store.add("this keyboard is nice because it is quiet")
    store.cleanAll()
    expect("a sample dropping off the list does not make it a slip again", store.texts.take(2), listOf("my keybkard broke again today", "the keybkard came in the post"))
    // What keeps it is the remembered word, not the count: without it the two that are left would be respelled.
    expect("…and it is the kept word that holds it", SampleCleaner.cleanAll(store.typed, en, emptySet()).first.take(2), listOf("my keyboard broke again today", "the keyboard came in the post"))
    expect("a later use is left as well", SampleCleaner.cleanNew("where is my keybkard gone", store.typed, en, store.kept).text, "where is my keybkard gone")
    // A word that needs no protecting is not remembered: "hpuse" is never changed, however often it is typed.
    expect("only a word that would be changed is remembered", SampleCleaner.cleanAll(listOf("the hpuse is my hpuse", "my hpuse is warm"), en, emptySet()).second, emptySet<String>())

    // --- a language with no dictionary loaded ---------------------------------------------------------------------
    // English only, as the app ships: Dutch, Spanish and German are left whole, however near English a word sits.
    for (text in listOf(
        "hij is nog niet thuis", "ik heb nog geen tijd gehad", "jullie zijn veel te laat", "ik kom morgen wel even langs bij jullie thuis",
        "ik kom morgen teh langs bij jullie thuis", "wat ga je vanavond doen dan", "ik bel je zo terug oke", "heb je de boodschappen al gedaan",
        "me parece muy buena idea", "no puedo ir hoy lo siento", "ya llegue a casa gracias", "vamos a comer algo rico", "nos vemos manana en la oficina",
        "wir sehen uns morgen frueh adn dann schauen wir weiter", "wir sehen uns morgne und dann schauen wir weiter",
    )) stays(text)
    // A word or two of another language in an English message is not a slip either.
    for (text in listOf(
        "ok see you demain at the office", "das ist auch true lol", "need to buy pani and snacks", "it was muy caliente out there",
        "lets meet at the bahnhof around six", "we had dosa and chai for breakfast", "thanks yaar see you soon", "wir sehen uns later ok",
    )) stays(text)
    // The cleaner's own test, with a dictionary that would respell anything: the language is decided from the words
    // it knows, before a single fix is looked up.
    val common = "i is it a the to at in on of and not been him this that was for me you we they have time had he she home ok see office true lol are too late much".split(" ").toSet()
    val eager = Eager(common, mapOf("hij" to "him", "nog" to "not", "geen" to "been", "thuis" to "this", "tijd" to "time", "gehad" to "had", "veel" to "feel", "laat" to "last", "auch" to "such", "demain" to "remain", "whaat" to "what", "jsut" to "just"))
    for (text in listOf("hij is nog niet thuis", "ik heb nog geen tijd gehad", "jullie zijn veel te laat")) expect("eager dictionary, Dutch: ${show(text)}", worst(text, eager), text)
    expect("…and no fix was even looked up", eager.asked, 0)
    expect("eager dictionary, English: it does fix", worst("that was nog the time for it", eager), "that was not the time for it")
    expect("a quarter of the words unknown, and more than one: left", worst("that was nog the tijd", eager), "that was nog the tijd")
    expect("one unknown word in a short message is not a language", SampleCleaner.foreign(SampleCleaner.tokens("that nog this"), eager), false)
    expect("two in eight is not more than a quarter", SampleCleaner.foreign(SampleCleaner.tokens("that was nog the time for it geen"), eager), false)
    expect("three in eight is", SampleCleaner.foreign(SampleCleaner.tokens("that was nog the tijd for it geen"), eager), true)
    // A word whose neighbours are unknown too is left, though the message is mostly English.
    expect("unknown neighbours: left", worst("we have the time and this is zijn nog jullie", eager), "we have the time and this is zijn nog jullie")
    expect("an unknown word on one side is enough to leave it", worst("this is jullie nog and that was the time for it", eager), "this is jullie nog and that was the time for it")
    expect("a name beside it says nothing", worst("ok see Priya nog at the office", eager), "ok see Priya not at the office")
    // A vowel doubled is a word stretched, whatever the dictionary offers; so is a capital after a line break a name.
    expect("eager dictionary: a doubled vowel is left", worst("whaat was that for", eager), "whaat was that for")
    expect("eager dictionary: a capital after a line break is left", worst("see you at home\nJsut", eager), "see you at home\nJsut")
    expect("eager dictionary: the same word mid-line is fixed", worst("see you at home jsut", eager), "see you at home just")

    val none = speller(assets)
    none.loadNow(emptyList(), qwerty(emptyList()))
    expect("no language loaded: untouched", worst("teh cat sat on the mat", none.lexicon()), "teh cat sat on the mat")
    val unloaded = speller(assets)
    expect("dictionaries not loaded yet: untouched", worst("teh cat sat on the mat", unloaded.lexicon()), "teh cat sat on the mat")
    expect("dictionaries not loaded yet: nothing counted", SampleCleaner.habits(thrice, unloaded.lexicon()).deliberate, emptySet<String>())
    expect("dictionaries not loaded yet: a new sample is kept as typed", SampleCleaner.cleanNew("teh cat sat on the mat", mine, unloaded.lexicon(), emptySet()).text, "teh cat sat on the mat")
    // With its dictionary loaded, German is cleaned like English; "koennt" (no umlaut on the keyboard) is not "kennt".
    val german = speller(assets)
    german.loadNow(listOf("en", "de"), qwerty(listOf("en", "de")))
    expect("German with German loaded", worst("wir sehen uns mrogen und dann schauen wir weiter", german.lexicon()), "wir sehen uns morgen und dann schauen wir weiter")
    expect("…and not with only English", worst("wir sehen uns mrogen und dann schauen wir weiter"), "wir sehen uns mrogen und dann schauen wir weiter")
    stays("ihr koennt gerne morgen kommen", german.lexicon())
    // A message that is a quarter slips cannot be told from another language, so it is left: the price of the above.
    stays("teh cat adn dog")
    stays("jsut goign to teh hpuse")

    // --- cleaning twice changes nothing ---------------------------------------------------------------------------
    for (text in inputs) { val a = clean(text); expect("idempotent: ${show(text)}", clean(a), a) }
    for (text in thrice) { val a = SampleCleaner.clean(text, en, theirs); expect("idempotent with a deliberate word: ${show(text)}", SampleCleaner.clean(a, en, theirs), a) }

    // --- Autocorrect as typed is as it was --------------------------------------------------------------------------
    // slip() is new; correct(), which fixes words as they are typed, must not have moved.
    for ((typed, want) in listOf("teh" to "the", "dont" to "don't", "i" to "I", "senor" to "senior", "keybkard" to "keyboard", "npm" to null, "hello" to null, "NASA" to null, "useState" to null, "yiu" to null)) {
        expect("correct($typed)", english.correct(typed), want)
    }

    // --- the instructions ---------------------------------------------------------------------------------------------
    val train = VoiceRules.TRAIN_SYSTEM
    expect("training no longer asks for typos", train.contains("typos they leave in"), false)
    for (phrase in listOf(
        "writes correctly spelled words", "consistently", "again and again", "Always lower case", "\"u\" for \"you\"", "No apostrophes",
        "Dropped full stops", "recurring\n  abbreviation or nickname", "one-off misspellings", "neighbouring-key slips", "doubled or dropped letters",
        "autocorrect artefacts", "Never describe these as\n  habits", "never put one among the quoted phrases",
    )) expect("training says: ${show(phrase)}", train.contains(phrase), true)
    val rule = "never introduce or copy an accidental misspelling"
    // The conventions the writers keep include the spelling habits the trainer is told to name and the cleaner
    // leaves alone: without them "correctly spelled" reads as an order to put the accents back and unstretch "sooo".
    val conventions = "deliberate conventions (casing, punctuation and apostrophe habits, missing accents, abbreviations, slang, " +
        "stretched words, any spelling the profile names as theirs or that recurs across their messages, emoji use)"
    for ((name, text) in listOf("Ghostwriter" to VoiceRules.WRITING, "In my voice" to VoiceRules.REWRITING, "voiceSection" to VoiceRules.SPELLING_RULE)) {
        expect("$name: correctly spelled", text.contains("Write correctly spelled text in their voice"), true)
        expect("$name: keeps conventions, spelling habits among them", text.contains(conventions), true)
        expect("$name: never a misspelling", text.contains(rule), true)
    }
    for ((name, text) in listOf("Ghostwriter" to VoiceRules.WRITING, "In my voice" to VoiceRules.REWRITING)) {
        expect("$name: points at the test", text.contains("SPELLING, below, says how to tell a convention from a slip."), true)
    }
    // The writers get the trainer's test for deliberate against accidental, and which wins.
    for (phrase in listOf(
        "a spelling is deliberate when the profile names it as a convention they keep to", "or when it recurs across their messages",
        "slang, a texting abbreviation or a stretched word, which nobody types by accident", "\"correctly spelled\" never means undoing them",
        "A slip is an accident of the thumbs seen once", "anything the profile itself calls a typo or a mistake", "When unsure, spell the word the ordinary way.",
    )) expect("voiceSection says: ${show(phrase)}", VoiceRules.SPELLING_RULE.contains(phrase), true)
    // Every kind of spelling the trainer is told to name as style has a place in the writers' list.
    for ((trained, written) in listOf("\"u\" for \"you\"" to "abbreviations", "No apostrophes" to "apostrophe habits", "A word they stretch" to "stretched words", "abbreviation or nickname" to "any spelling the profile names as theirs")) {
        expect("the trainer names ${show(trained)}; the writers keep ${show(written)}", train.contains(trained) && conventions.contains(written), true)
    }
    expect("the guard follows a profile", VoiceRules.voiceSection("They leave typos in.").endsWith("They leave typos in.\n\n" + VoiceRules.SPELLING_RULE), true)
    expect("the guard is there with no profile", VoiceRules.voiceSection(null).endsWith(VoiceRules.SPELLING_RULE), true)
    expect("the guard covers an old profile", VoiceRules.SPELLING_RULE.contains("the profile describes typos as a habit"), true)

    // --- an old profile stays due its retrain ---------------------------------------------------------------------
    val now = VoiceRules.PROFILE_VERSION
    val own = Retrain.importedBy(Retrain.OWN_APP)
    expect("trained under the old rules: outdated", Retrain.outdated(1, "Anthropic", "claude"), true)
    expect("trained under today's: not", Retrain.outdated(now, "Anthropic", "claude"), false)
    expect("written by another app and imported: never", Retrain.outdated(1, Retrain.importedBy("Some Report Tool, gpt"), ""), false)
    expect("imported from nowhere in particular: never", Retrain.outdated(1, Retrain.importedBy(null), ""), false)
    // The round trip: export, then import on the same phone or a new one.
    val exported = Retrain.exportVersion(1, "Anthropic", "claude")
    expect("an old profile is exported as old", exported, 1)
    expect("…and is still outdated once imported again", Retrain.outdated(Retrain.importVersion(Retrain.OWN_APP, exported), own, ""), true)
    expect("a file from the build before versions (it wrote none) is old", Retrain.outdated(Retrain.importVersion(Retrain.OWN_APP, null), own, ""), true)
    expect("a current profile comes back current", Retrain.outdated(Retrain.importVersion(Retrain.OWN_APP, Retrain.exportVersion(now, "Anthropic", "claude")), own, ""), false)
    expect("another app's profile, through this keyboard's export and back, is still not outdated",
        Retrain.outdated(Retrain.importVersion(Retrain.OWN_APP, Retrain.exportVersion(1, Retrain.importedBy("Some Report Tool"), "")), own, ""), false)
    expect("another app's file cannot mark itself old or new", Retrain.importVersion("Some Report Tool", 1), now)
    expect("a version from the future is today's", Retrain.importVersion(Retrain.OWN_APP, now + 5), now)

    // --- when it trains by itself ---------------------------------------------------------------------------------
    val hour = 3_600_000L
    fun due(trained: Boolean = true, outdated: Boolean = false, samples: Int = 500, new: Int = 0, sinceTrained: Long = 10 * 60_000L, sinceTry: Long = Long.MAX_VALUE / 2) =
        Retrain.due(trained, outdated, samples, new, sinceTrained, sinceTry)
    expect("never trained, 49 samples: wait", due(trained = false, samples = 49), false)
    expect("never trained, 50 samples: train", due(trained = false, samples = 50), true)
    expect("current, 50 new samples, a day on: train", due(new = 50, sinceTrained = 21 * hour), true)
    expect("current, 50 new samples, an hour on: wait", due(new = 50, sinceTrained = hour), false)
    expect("current, 49 new samples, a day on: wait", due(new = 49, sinceTrained = 21 * hour), false)
    expect("old profile, trained ten minutes ago, no new samples: train once", due(outdated = true), true)
    expect("old profile, too few samples to train on: wait", due(outdated = true, samples = 9), false)
    expect("old profile, the try an hour ago failed: not again yet", due(outdated = true, sinceTry = hour), false)
    expect("…nor after nineteen hours", due(outdated = true, sinceTry = 19 * hour), false)
    expect("…but a day later", due(outdated = true, sinceTry = 21 * hour), true)

    // --- what only the phone runs, read as source -----------------------------------------------------------------
    // The keyboard asks for a retrain when a field is left, whether or not a draft was kept: with "Keep samples of
    // what I type" off there never is one, and an old profile must still be retrained.
    val service = File(sources, "DevKeyboardService.kt").readText()
    val flush = service.substringAfter("private fun flushDraft() {", "").substringBefore("\n    }")
    val asks = flush.indexOf("Mockingbird.maybeAutoTrain(")
    expect("flushDraft asks for training", asks >= 0, true)
    expect("…and no blank draft returns before it does", flush.take(maxOf(asks, 0)).lines().none { it.contains("return") && (it.contains("isBlank") || it.contains("isEmpty")) }, true)
    expect("…and the sample is only kept from a draft that has something in it", flush.contains("if (text.isNotBlank()) Mockingbird.record("), true)
    // Settings says when it retrains, the once after an update included.
    val panes = File(repo, "lib/settings/panes.dart").readText()
    val hint = panes.lineSequence().firstOrNull { it.contains("'mockingbird_auto_train'") }.orEmpty()
    expect("the Retrain by itself hint keeps its daily rule", hint.contains("At most daily, after ${Retrain.AFTER_SAMPLES} new samples"), true)
    expect("…and owns up to the retrain after an update", hint.contains("once more after an update to the style rules"), true)

    if ("--rules" in args) {
        println("===== VoiceRules.TRAIN_SYSTEM =====\n$train\n")
        println("===== VoiceRules.WRITING (a line of Ghostwriter's prompt) =====\n${VoiceRules.WRITING}\n")
        println("===== VoiceRules.REWRITING (Mockingbird.rewrite) =====\n${VoiceRules.REWRITING}\n")
        println("===== VoiceRules.voiceSection(null) =====\n${VoiceRules.voiceSection(null)}\n")
    }
    println(if (failed == 0) "sample cleaner: all $passed checks passed" else "sample cleaner: $failed of ${passed + failed} checks FAILED")
    if (failed > 0) exitProcess(1)
}

/** An Autocorrect reading the app's assets folder, as the keyboard reads its bundled assets. */
fun speller(assets: File) = Autocorrect { name -> File(assets, name).takeIf { it.isFile }?.inputStream() }

/** The QWERTY board, with English's rules where English is one of [lists]. */
fun qwerty(lists: List<String>) = Board.of(Layouts.QWERTY, english = "en" in lists)
