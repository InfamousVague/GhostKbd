import android.content.Context
import com.infamousvague.devkeyboard.Autocorrect
import com.infamousvague.devkeyboard.SampleCleaner
import com.infamousvague.devkeyboard.VoiceRules
import java.io.File
import kotlin.system.exitProcess

/**
 * The self-check for Mockingbird's sample cleaner and the spelling instructions its AIs get. It runs the app's own
 * SampleCleaner.kt, VoiceRules.kt and Autocorrect.kt against the app's own dictionaries, on this computer, with
 * three small stand-ins for the Android classes Autocorrect names (stubs/).
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

fun main(args: Array<String>) {
    val assets = File(args.firstOrNull { !it.startsWith("--") } ?: "android/app/src/main/assets")
    require(File(assets, "dict/en.tsv").exists()) { "No dictionaries under $assets: pass the app's assets folder" }

    // English, with one learned word, and the slang switch OFF: the cleaner must honour the slang list all the same.
    val english = Autocorrect(Context(assets))
    english.setLearned(setOf("yiu"))
    english.loadNow(listOf("en"))
    val en = english.lexicon()
    fun clean(text: String, deliberate: Set<String> = emptySet()) = SampleCleaner.clean(text, en, deliberate)
    val inputs = ArrayList<String>()
    fun becomes(text: String, want: String) { inputs += text; expect("clean(${show(text)})", clean(text), want) }
    fun stays(text: String) = becomes(text, text)

    // --- slips are repaired ---------------------------------------------------------------------------------------
    becomes("teh cat", "the cat")
    becomes("see you at teh hpuse later", "see you at the house later")
    becomes("i was goign to say taht", "i was going to say that")
    // The word's capitals are kept.
    becomes("Teh cat sat.", "The cat sat.")
    becomes("ok. Teh cat sat on it", "ok. The cat sat on it")
    becomes("first line\nTeh second line", "first line\nThe second line")
    // An all-lower-case sample stays lower case, and keeps its "i", "dont" and "u".
    becomes("i went to teh shop and i dont know what u want", "i went to the shop and i dont know what u want")
    // Punctuation, spacing, line breaks and emoji round a slip are left exactly.
    becomes("teh  cat ,  sat…\n\nadn then?!", "the  cat ,  sat…\n\nand then?!")
    becomes("(teh cat) \"adn\" *jsut* this 😂", "(the cat) \"and\" *just* this 😂")
    becomes("thats teh one😂 for sure", "thats the one😂 for sure")

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
        "Teh", "Jsut", "Priya",
        // An emoji, and punctuation by itself.
        "😂", "🤷‍♂️", "...", "?!", "—", "(…)", ":)",
        // Written for effect: stretched, lengthened, laughter, vowel-less abbreviations.
        "heyy", "yess", "sooo", "noooo", "helllo", "hahah", "lolol", "musta", "thx", "tmrw", "pls",
        // Ways of writing, not slips: no apostrophe, no accent, a spelling the lists have seen.
        "dont", "cant", "im", "cafe", "nite", "yas", "thru", "tho",
        // Too unsure: two fair readings, or no likely word nearby.
        "hwo", "waht", "gurl", "posthog", "kubernetes", "jaxon",
    )
    for (word in untouched) stays(frame(word))
    stays("u gonna run npm install for useState in foo_bar v2.1?? lol 😂 see https://exmaple.com/teh or ask @jsut and Whcih, ok... (yes!) — fine; yiu")
    stays("ok!!! wait... what?!  no way — really; (yes) [maybe] \"sure\" 'fine' :) <3")

    // --- typed repeatedly, a word is the person's own -------------------------------------------------------------
    val once = listOf("meet at the hpuse later on", "it is cold in here today", "party tonight at mine then")
    expect("a slip typed once is not deliberate", SampleCleaner.repeated(once, en), emptySet<String>())
    expect("a slip typed once is fixed", clean(once[0], SampleCleaner.repeated(once, en)), "meet at the house later on")
    val thrice = listOf("meet at the hpuse later on", "the hpuse is cold today", "Hpuse party tonight then")
    val theirs = SampleCleaner.repeated(thrice, en)
    expect("the same non-word in three samples is deliberate", theirs, setOf("hpuse"))
    for (text in thrice) expect("…and is kept in ${show(text)}", clean(text, theirs), text)
    expect("three times in ONE sample is not enough", SampleCleaner.repeated(listOf("hpuse hpuse hpuse is all i said", "nothing to see here"), en), emptySet<String>())
    expect("twice in two samples is not enough", SampleCleaner.repeated(listOf("the hpuse is cold", "my hpuse is warm"), en), emptySet<String>())
    expect("three times across two samples is", SampleCleaner.repeated(listOf("the hpuse is my hpuse", "my hpuse is warm"), en), setOf("hpuse"))
    expect("counting only one sample's suspects", SampleCleaner.repeated(thrice + "a wirk thing and a wirk thing and wirk", en, SampleCleaner.suspects(thrice[0], en)), setOf("hpuse"))
    expect("a real word is never a suspect", SampleCleaner.suspects("the cat and u gonna lol with npm and yiu", en), emptySet<String>())
    // The other words of a sample with a deliberate word are still cleaned.
    expect("deliberate word kept, slip beside it fixed", clean("teh hpuse is cold", theirs), "the hpuse is cold")

    // --- a language with no dictionary loaded ---------------------------------------------------------------------
    stays("ik kom morgen wel even langs bij jullie thuis")
    stays("ik kom morgen teh langs bij jullie thuis")
    stays("wir sehen uns morgen frueh adn dann schauen wir weiter")
    val none = Autocorrect(Context(assets))
    none.loadNow(emptyList())
    expect("no language loaded: untouched", SampleCleaner.clean("teh cat sat on the mat", none.lexicon()), "teh cat sat on the mat")
    val unloaded = Autocorrect(Context(assets))
    expect("dictionaries not loaded yet: untouched", SampleCleaner.clean("teh cat sat on the mat", unloaded.lexicon()), "teh cat sat on the mat")
    expect("dictionaries not loaded yet: nothing counted", SampleCleaner.repeated(thrice, unloaded.lexicon()), emptySet<String>())
    // With its dictionary loaded, the same German is cleaned like English.
    val german = Autocorrect(Context(assets))
    german.loadNow(listOf("en", "de"))
    expect("German with German loaded", SampleCleaner.clean("wir sehen uns morgne und dann schauen wir weiter", german.lexicon()), "wir sehen uns morgen und dann schauen wir weiter")
    expect("…and not with only English", clean("wir sehen uns morgne und dann schauen wir weiter"), "wir sehen uns morgne und dann schauen wir weiter")
    // Several slips in a short English message are still English.
    becomes("teh cat adn dog", "the cat and dog")
    becomes("jsut goign to teh hpuse", "just going to the house")

    // --- cleaning twice changes nothing ---------------------------------------------------------------------------
    for (text in inputs) { val a = clean(text); expect("idempotent: ${show(text)}", clean(a), a) }
    for (text in thrice) { val a = clean(text, theirs); expect("idempotent with a deliberate word: ${show(text)}", clean(a, theirs), a) }

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
    val conventions = "deliberate conventions (casing, punctuation habits, abbreviations, emoji use)"
    for ((name, text) in listOf("Ghostwriter" to VoiceRules.WRITING, "In my voice" to VoiceRules.REWRITING, "voiceSection" to VoiceRules.SPELLING_RULE)) {
        expect("$name: correctly spelled", text.contains("Write correctly spelled text in their voice"), true)
        expect("$name: keeps conventions", text.contains(conventions), true)
        expect("$name: never a misspelling", text.contains(rule), true)
    }
    expect("the guard follows a profile", VoiceRules.voiceSection("They leave typos in.").endsWith("They leave typos in.\n\n" + VoiceRules.SPELLING_RULE), true)
    expect("the guard is there with no profile", VoiceRules.voiceSection(null).endsWith(VoiceRules.SPELLING_RULE), true)
    expect("the guard covers an old profile", VoiceRules.SPELLING_RULE.contains("the profile describes typos as a habit"), true)

    if ("--rules" in args) {
        println("===== VoiceRules.TRAIN_SYSTEM =====\n$train\n")
        println("===== VoiceRules.WRITING (a line of Ghostwriter's prompt) =====\n${VoiceRules.WRITING}\n")
        println("===== VoiceRules.REWRITING (Mockingbird.rewrite) =====\n${VoiceRules.REWRITING}\n")
        println("===== VoiceRules.voiceSection(null) =====\n${VoiceRules.voiceSection(null)}\n")
    }
    println(if (failed == 0) "sample cleaner: all $passed checks passed" else "sample cleaner: $failed of ${passed + failed} checks FAILED")
    if (failed > 0) exitProcess(1)
}
