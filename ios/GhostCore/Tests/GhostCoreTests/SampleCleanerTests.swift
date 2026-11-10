import XCTest
@testable import GhostCore

/// A dictionary that would fix anything it is told to, for checking the cleaner's own guards apart from
/// Autocorrect's: `words` are the words it knows, `fixes` what it offers for the rest, and `asked` counts how often a
/// fix was looked up.
private final class Eager: SampleLexicon {
    let words: Set<String>
    let fixes: [String: String]
    var asked = 0
    init(_ words: Set<String>, _ fixes: [String: String]) { self.words = words; self.fixes = fixes }
    var ready: Bool { true }
    func isWord(_ lower: String) -> Bool { words.contains(lower) }
    func fix(_ token: String) -> String? {
        asked += 1
        guard let fix = fixes[token.lowercased()] else { return nil }
        return token.first!.isUppercase ? fix.prefix(1).uppercased() + fix.dropFirst() : fix
    }
}

/// Mockingbird's samples as `record` and `cleanStored` keep them, without the files: each as `typed` and as the AI
/// reads it (`texts`), and the words `kept` as the person's own.
private final class Store {
    let lexicon: SampleLexicon
    var typed: [String] = []
    var texts: [String] = []
    var kept: Set<String> = []
    init(_ lexicon: SampleLexicon) { self.lexicon = lexicon }

    /// A new sample comes in: cleaned against the others, and the others cleaned again if it made a word theirs.
    func add(_ sample: String) {
        let cleaned = SampleCleaner.cleanNew(sample, others: typed, lexicon, kept: kept)
        kept = cleaned.kept
        typed.append(sample)
        texts.append(cleaned.text)
        if cleaned.fresh { cleanAll() }
    }

    /// Every sample cleaned again from what was typed, as before a training.
    func cleanAll() { (texts, kept) = SampleCleaner.cleanAll(typed, lexicon, kept: kept) }

    /// The oldest sample drops off the end of the list.
    func evictOldest() { typed.removeFirst(); texts.removeFirst() }
}

/// The checks of tool/sample_cleaner_check/Check.kt, against the same dictionaries.
final class SampleCleanerTests: XCTestCase {
    /// English, with one learned word, and the slang switch OFF: the cleaner must honour the slang list all the same.
    static let english: Autocorrect = {
        let ac = Autocorrect(dictDirectory: dictDirectory)
        ac.setLearned(["yiu"])
        ac.loadNow(languages: ["en"])
        return ac
    }()
    static let lexicon = english.lexicon()

    private var en: SampleLexicon { SampleCleanerTests.lexicon }

    /// What the person has typed before: the words a slip may be put back to are here, spelled right.
    private let mine = [
        "the cat sat on the mat and that was that",
        "just going to the shop, back in a minute",
        "see you tomorrow at their house",
        "this keyboard is nice because it is quiet",
        "did you receive it? i know the one",
    ]

    /// `text` cleaned as Mockingbird would with `mine` already kept.
    private func clean(_ text: String) -> String { SampleCleaner.clean(text, en, SampleCleaner.habits(mine + [text], en)) }

    /// `text` cleaned at the dictionaries' most eager: every word a slip could become counts as one the person uses.
    private func worst(_ text: String, _ lexicon: SampleLexicon? = nil) -> String {
        SampleCleaner.clean(text, lexicon ?? en, SampleCleaner.Habits(deliberate: [], everyWordUsed: true))
    }

    private func becomes(_ text: String, _ want: String, line: UInt = #line) {
        let got = clean(text)
        XCTAssertEqual(got, want, line: line)
        XCTAssertEqual(clean(got), got, "idempotent", line: line)
    }

    private func stays(_ text: String, _ lexicon: SampleLexicon? = nil, line: UInt = #line) {
        XCTAssertEqual(worst(text, lexicon), text, line: line)
        XCTAssertEqual(clean(text), text, "left by its own samples too", line: line)
    }

    private func frame(_ word: String) -> String { "i think \(word) is what we need here today" }

    func testSlipsAreRepaired() {
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
    }

    func testAFixIsMadeOnlyToAWordThePersonUses() {
        // "keybkard" can only be "keyboard", but somebody who has never typed "keyboard" may mean something else by it.
        let stranger = SampleCleaner.habits(["the cat sat on the mat", "this keybkard is really nice"], en)
        XCTAssertEqual(SampleCleaner.clean("this keybkard is really nice", en, stranger), "this keybkard is really nice")
        XCTAssertTrue(SampleCleaner.habits(mine, en).used.contains("keyboard"))
        XCTAssertEqual(SampleCleaner.wanted("this keybkard is really nice", en), ["keybkard", "keyboard"])
        XCTAssertEqual(SampleCleaner.wanted("this keyboard is really nice and u gonna lol with npm and yiu", en), [])
        XCTAssertEqual(SampleCleaner.habits(mine, en, among: ["keybkard", "keyboard"]).used, ["keyboard"])
    }

    func testEverythingDeliberateIsLeft() {
        // Each sits in a sentence whose "teh" control proves the sentence itself is being cleaned.
        becomes(frame("teh"), frame("the"))
        let untouched = [
            // Words: texting shorthand, slang (its switch is off here), a learned word, a programmer's word.
            "u", "lol", "gonna", "ur", "tbh", "ngl", "sybau", "yiu", "npm",
            // Code-shaped, each built round a slip that would be fixed if it stood alone.
            "useState", "tEh", "TEH", "foo_bar", "teh_cat", "v2.1", "teh2", "teh.cat", "teh/cat", "teh-cat", "--teh",
            "https://exmaple.com/teh", "www.teh.com", "teh@jsut.com", "@handle", "@teh", "#teh", "`teh`", "teh()", ":teh:", "<teh>", "$teh",
            // A capitalised word mid-sentence is a name.
            "Teh", "Jsut", "Priya", "Keybkard",
            // An emoji, and punctuation by itself.
            "😂", "🤷‍♂️", "...", "?!", "—", "(…)", ":)",
            // Written for effect: stretched, lengthened, laughter, vowel-less abbreviations.
            "heyy", "yess", "sooo", "noooo", "helllo", "hahah", "lolol", "musta", "thx", "tmrw", "pls",
            // A vowel doubled is a word stretched too.
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
        ]
        for word in untouched { XCTAssertEqual(worst(frame(word)), frame(word), word) }
        stays("u gonna run npm install for useState in foo_bar v2.1?? lol 😂 see https://exmaple.com/teh or ask @jsut and Whcih, ok... (yes!) — fine; yiu")
        stays("ok!!! wait... what?!  no way — really; (yes) [maybe] \"sure\" 'fine' :) <3")
        for text in [
            "open it in vim", "just do an upsert on the users table", "the recommender model", "set usrname and passwd",
            "can you ask vishal to send the deck", "i untied the boat and pushed off", "she was a prefect at her old school",
            "we should grab bleu cheese burgers", "play it on the strat", "acme corp sent the invoice", "the emt got there in five minutes",
            "my mum is a cna", "Joni is coming to dinner", "Micheal said he would be there", "just hodl and wait it out",
            "whaat are you serious right now", "i loove this song so much", "pleease can we go to the beach", "thaanks for the lift", "omg that is craazy lol",
        ] { stays(text) }
        // With French on too, a rare English word is still not a French one with its letters swapped.
        let french = Autocorrect(dictDirectory: dictDirectory)
        french.loadNow(languages: ["en", "fr"])
        stays("the onus is on you now", french.lexicon())
    }

    func testACapitalProvesANameWhereNoSentencePlainlyStarts() {
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
        let starts = SampleCleaner.tokens("One two. Three\nFour 😂 Five! Six Dr. Seven etc. Eight… Nine ok. Ten then. Eleven").filter { $0.sentenceStart }.map { $0.word }
        XCTAssertEqual(starts, ["One", "Six", "Nine", "Eleven"])
        // A token's offsets count Unicode scalars: the emoji before it is one.
        let after = SampleCleaner.tokens("😂 teh")
        XCTAssertEqual(after, [SampleCleaner.Token(start: 2, end: 5, word: "teh", sentenceStart: true)])
    }

    func testTypedRepeatedlyAWordIsThePersonsOwn() {
        func deliberate(_ texts: [String], among: Set<String>? = nil) -> Set<String> { SampleCleaner.habits(texts, en, among: among).deliberate }
        let once = ["this keybkard is really nice", "it is cold in here today", "party tonight at mine then"] + mine
        XCTAssertEqual(deliberate(once), [])
        XCTAssertEqual(SampleCleaner.clean(once[0], en, SampleCleaner.habits(once, en)), "this keyboard is really nice")
        let thrice = ["this keybkard is really nice", "the keybkard is cold today", "Keybkard party tonight then"] + mine
        let theirs = SampleCleaner.habits(thrice, en)
        XCTAssertEqual(theirs.deliberate, ["keybkard"])
        for text in thrice {
            XCTAssertEqual(SampleCleaner.clean(text, en, theirs), text)
        }
        XCTAssertEqual(deliberate(["hpuse hpuse hpuse is all i said", "nothing to see here"]), [], "three times in ONE sample is not enough")
        XCTAssertEqual(deliberate(["the hpuse is cold", "my hpuse is warm"]), [], "twice in two samples is not enough")
        XCTAssertEqual(deliberate(["the hpuse is my hpuse", "my hpuse is warm"]), ["hpuse"])
        XCTAssertEqual(deliberate(thrice + ["a wirk thing and a wirk thing and wirk"], among: SampleCleaner.wanted(thrice[0], en)), ["keybkard"])
        XCTAssertEqual(deliberate(["the cat and u gonna lol with npm and yiu", "the cat and u gonna lol", "the cat and u gonna lol"]), [], "a real word is never deliberate")
        // The other words of a sample with a deliberate word are still cleaned.
        XCTAssertEqual(SampleCleaner.clean("teh cat sat on the mat by my keybkard", en, theirs), "the cat sat on the mat by my keybkard")
    }

    func testOnceTheirsAWordGoesBackAndStays() {
        let store = Store(en)
        for text in mine { store.add(text) }
        store.add("this keybkard is really nice")
        store.add("my keybkard broke again today")
        XCTAssertEqual(Array(store.texts.suffix(2)), ["this keyboard is really nice", "my keyboard broke again today"])
        XCTAssertEqual(store.kept, [])
        store.add("the keybkard came in the post")
        XCTAssertEqual(store.kept, ["keybkard"])
        XCTAssertEqual(Array(store.texts.suffix(3)), ["this keybkard is really nice", "my keybkard broke again today", "the keybkard came in the post"])
        // The list is full and the oldest sample holding the word drops off: two uses are left.
        while store.typed.first != "my keybkard broke again today" { store.evictOldest() }
        store.add("this keyboard is nice because it is quiet")
        store.cleanAll()
        XCTAssertEqual(Array(store.texts.prefix(2)), ["my keybkard broke again today", "the keybkard came in the post"])
        // What keeps it is the remembered word, not the count: without it the two that are left would be respelled.
        XCTAssertEqual(Array(SampleCleaner.cleanAll(store.typed, en, kept: []).texts.prefix(2)), ["my keyboard broke again today", "the keyboard came in the post"])
        XCTAssertEqual(SampleCleaner.cleanNew("where is my keybkard gone", others: store.typed, en, kept: store.kept).text, "where is my keybkard gone")
        // A word that needs no protecting is not remembered: "hpuse" is never changed, however often it is typed.
        XCTAssertEqual(SampleCleaner.cleanAll(["the hpuse is my hpuse", "my hpuse is warm"], en, kept: []).kept, [])
    }

    func testALanguageWithNoDictionaryLoaded() {
        // English only, as the app ships: Dutch, Spanish and German are left whole, however near English a word sits.
        for text in [
            "hij is nog niet thuis", "ik heb nog geen tijd gehad", "jullie zijn veel te laat", "ik kom morgen wel even langs bij jullie thuis",
            "ik kom morgen teh langs bij jullie thuis", "wat ga je vanavond doen dan", "ik bel je zo terug oke", "heb je de boodschappen al gedaan",
            "me parece muy buena idea", "no puedo ir hoy lo siento", "ya llegue a casa gracias", "vamos a comer algo rico", "nos vemos manana en la oficina",
            "wir sehen uns morgen frueh adn dann schauen wir weiter", "wir sehen uns morgne und dann schauen wir weiter",
        ] { stays(text) }
        // A word or two of another language in an English message is not a slip either.
        for text in [
            "ok see you demain at the office", "das ist auch true lol", "need to buy pani and snacks", "it was muy caliente out there",
            "lets meet at the bahnhof around six", "we had dosa and chai for breakfast", "thanks yaar see you soon", "wir sehen uns later ok",
        ] { stays(text) }
        // The cleaner's own test, with a dictionary that would respell anything: the language is decided from the words
        // it knows, before a single fix is looked up.
        let common = Set("i is it a the to at in on of and not been him this that was for me you we they have time had he she home ok see office true lol are too late much".split(separator: " ").map(String.init))
        let eager = Eager(common, ["hij": "him", "nog": "not", "geen": "been", "thuis": "this", "tijd": "time", "gehad": "had", "veel": "feel", "laat": "last", "auch": "such", "demain": "remain", "whaat": "what", "jsut": "just"])
        for text in ["hij is nog niet thuis", "ik heb nog geen tijd gehad", "jullie zijn veel te laat"] { XCTAssertEqual(worst(text, eager), text) }
        XCTAssertEqual(eager.asked, 0, "no fix was even looked up")
        XCTAssertEqual(worst("that was nog the time for it", eager), "that was not the time for it")
        XCTAssertEqual(worst("that was nog the tijd", eager), "that was nog the tijd", "a quarter of the words unknown, and more than one: left")
        XCTAssertFalse(SampleCleaner.foreign(SampleCleaner.tokens("that nog this"), eager))
        XCTAssertFalse(SampleCleaner.foreign(SampleCleaner.tokens("that was nog the time for it geen"), eager))
        XCTAssertTrue(SampleCleaner.foreign(SampleCleaner.tokens("that was nog the tijd for it geen"), eager))
        // A word whose neighbours are unknown too is left, though the message is mostly English.
        XCTAssertEqual(worst("we have the time and this is zijn nog jullie", eager), "we have the time and this is zijn nog jullie")
        XCTAssertEqual(worst("this is jullie nog and that was the time for it", eager), "this is jullie nog and that was the time for it")
        XCTAssertEqual(worst("ok see Priya nog at the office", eager), "ok see Priya not at the office", "a name beside it says nothing")
        // A vowel doubled is a word stretched, whatever the dictionary offers; so is a capital after a line break a name.
        XCTAssertEqual(worst("whaat was that for", eager), "whaat was that for")
        XCTAssertEqual(worst("see you at home\nJsut", eager), "see you at home\nJsut")
        XCTAssertEqual(worst("see you at home jsut", eager), "see you at home just")

        let none = Autocorrect(dictDirectory: dictDirectory)
        none.loadNow(languages: [])
        XCTAssertEqual(worst("teh cat sat on the mat", none.lexicon()), "teh cat sat on the mat")
        let unloaded = Autocorrect(dictDirectory: dictDirectory)
        XCTAssertEqual(worst("teh cat sat on the mat", unloaded.lexicon()), "teh cat sat on the mat")
        let thrice = ["this keybkard is really nice", "the keybkard is cold today", "Keybkard party tonight then"] + mine
        XCTAssertEqual(SampleCleaner.habits(thrice, unloaded.lexicon()).deliberate, [])
        XCTAssertEqual(SampleCleaner.cleanNew("teh cat sat on the mat", others: mine, unloaded.lexicon(), kept: []).text, "teh cat sat on the mat")
        // With its dictionary loaded, German is cleaned like English; "koennt" (no umlaut on the keyboard) is not "kennt".
        let german = Autocorrect(dictDirectory: dictDirectory)
        german.loadNow(languages: ["en", "de"])
        XCTAssertEqual(worst("wir sehen uns mrogen und dann schauen wir weiter", german.lexicon()), "wir sehen uns morgen und dann schauen wir weiter")
        XCTAssertEqual(worst("wir sehen uns mrogen und dann schauen wir weiter"), "wir sehen uns mrogen und dann schauen wir weiter")
        XCTAssertEqual(worst("ihr koennt gerne morgen kommen", german.lexicon()), "ihr koennt gerne morgen kommen")
        // A message that is a quarter slips cannot be told from another language, so it is left: the price of the above.
        stays("teh cat adn dog")
        stays("jsut goign to teh hpuse")
    }

    /// `slip` is new; `correct`, which fixes words as they are typed, must not have moved.
    func testAutocorrectAsTypedIsAsItWas() {
        let ac = SampleCleanerTests.english
        let cases: [(String, String?)] = [("teh", "the"), ("dont", "don't"), ("i", "I"), ("senor", "senior"), ("keybkard", "keyboard"), ("npm", nil), ("hello", nil), ("NASA", nil), ("useState", nil), ("yiu", nil)]
        for (typed, want) in cases { XCTAssertEqual(ac.correct(typed), want, typed) }
        XCTAssertEqual(ac.slip("teh"), "the"); XCTAssertEqual(ac.slip("Teh"), "The"); XCTAssertEqual(ac.slip("keybkard"), "keyboard")
        XCTAssertNil(ac.slip("dont")); XCTAssertNil(ac.slip("hwo")); XCTAssertNil(ac.slip("TEH")); XCTAssertNil(ac.slip("yiu")); XCTAssertNil(ac.slip("lol"))
        XCTAssertTrue(ac.isWord("lol")); XCTAssertTrue(ac.isWord("dont")); XCTAssertTrue(ac.isWord("the")); XCTAssertFalse(ac.isWord("teh"))
        XCTAssertNil(Autocorrect(dictDirectory: dictDirectory).slip("teh"))
    }
}

final class VoiceRulesTests: XCTestCase {
    func testTheInstructions() {
        let train = VoiceRules.trainSystem
        XCTAssertFalse(train.contains("typos they leave in"))
        XCTAssertTrue(train.hasPrefix("You study one person's writing")); XCTAssertTrue(train.hasSuffix("under 700 words."))
        for phrase in [
            "writes correctly spelled words", "consistently", "again and again", "Always lower case", "\"u\" for \"you\"", "No apostrophes",
            "Dropped full stops", "recurring\n  abbreviation or nickname", "one-off misspellings", "neighbouring-key slips", "doubled or dropped letters",
            "autocorrect artefacts", "Never describe these as\n  habits", "never put one among the quoted phrases",
        ] { XCTAssertTrue(train.contains(phrase), phrase) }
        let rule = "never introduce or copy an accidental misspelling"
        // The conventions the writers keep include the spelling habits the trainer is told to name and the cleaner
        // leaves alone: without them "correctly spelled" reads as an order to put the accents back and unstretch "sooo".
        let conventions = "deliberate conventions (casing, punctuation and apostrophe habits, missing accents, abbreviations, slang, " +
            "stretched words, any spelling the profile names as theirs or that recurs across their messages, emoji use)"
        for text in [VoiceRules.writing, VoiceRules.rewriting, VoiceRules.spellingRule] {
            XCTAssertTrue(text.contains("Write correctly spelled text in their voice")); XCTAssertTrue(text.contains(conventions)); XCTAssertTrue(text.contains(rule))
        }
        for text in [VoiceRules.writing, VoiceRules.rewriting] { XCTAssertTrue(text.contains("SPELLING, below, says how to tell a convention from a slip.")) }
        for phrase in [
            "a spelling is deliberate when the profile names it as a convention they keep to", "or when it recurs across their messages",
            "slang, a texting abbreviation or a stretched word, which nobody types by accident", "\"correctly spelled\" never means undoing them",
            "A slip is an accident of the thumbs seen once", "anything the profile itself calls a typo or a mistake", "When unsure, spell the word the ordinary way.",
        ] { XCTAssertTrue(VoiceRules.spellingRule.contains(phrase), phrase) }
        for (trained, written) in [("\"u\" for \"you\"", "abbreviations"), ("No apostrophes", "apostrophe habits"), ("A word they stretch", "stretched words"), ("abbreviation or nickname", "any spelling the profile names as theirs")] {
            XCTAssertTrue(train.contains(trained) && conventions.contains(written), trained)
        }
        XCTAssertTrue(VoiceRules.voiceSection("They leave typos in.").hasSuffix("They leave typos in.\n\n" + VoiceRules.spellingRule))
        XCTAssertTrue(VoiceRules.voiceSection(nil).hasSuffix(VoiceRules.spellingRule))
        XCTAssertTrue(VoiceRules.spellingRule.contains("the profile describes typos as a habit"))
    }

    func testAnOldProfileStaysDueItsRetrain() {
        let now = VoiceRules.profileVersion
        let own = Retrain.importedBy(Retrain.ownApp)
        XCTAssertTrue(Retrain.outdated(version: 1, provider: "Anthropic", model: "claude"))
        XCTAssertFalse(Retrain.outdated(version: now, provider: "Anthropic", model: "claude"))
        XCTAssertFalse(Retrain.outdated(version: 1, provider: Retrain.importedBy("Some Report Tool, gpt"), model: ""), "written by another app and imported: never")
        XCTAssertFalse(Retrain.outdated(version: 1, provider: Retrain.importedBy(nil), model: ""))
        // The round trip: export, then import on the same phone or a new one.
        let exported = Retrain.exportVersion(version: 1, provider: "Anthropic", model: "claude")
        XCTAssertEqual(exported, 1)
        XCTAssertTrue(Retrain.outdated(version: Retrain.importVersion(sourceApp: Retrain.ownApp, fileVersion: exported), provider: own, model: ""))
        XCTAssertTrue(Retrain.outdated(version: Retrain.importVersion(sourceApp: Retrain.ownApp, fileVersion: nil), provider: own, model: ""), "a file from the build before versions is old")
        XCTAssertFalse(Retrain.outdated(version: Retrain.importVersion(sourceApp: Retrain.ownApp, fileVersion: Retrain.exportVersion(version: now, provider: "Anthropic", model: "claude")), provider: own, model: ""))
        XCTAssertFalse(Retrain.outdated(version: Retrain.importVersion(sourceApp: Retrain.ownApp, fileVersion: Retrain.exportVersion(version: 1, provider: Retrain.importedBy("Some Report Tool"), model: "")), provider: own, model: ""))
        XCTAssertEqual(Retrain.importVersion(sourceApp: "Some Report Tool", fileVersion: 1), now, "another app's file cannot mark itself old or new")
        XCTAssertEqual(Retrain.importVersion(sourceApp: Retrain.ownApp, fileVersion: now + 5), now, "a version from the future is today's")
    }

    func testWhenItTrainsByItself() {
        let hour: Int64 = 3_600_000
        func due(trained: Bool = true, outdated: Bool = false, samples: Int = 500, new: Int = 0, sinceTrained: Int64 = 600_000, sinceTry: Int64 = Int64.max / 2) -> Bool {
            Retrain.due(trained: trained, outdated: outdated, samples: samples, newSamples: new, sinceTrained: sinceTrained, sinceOutdatedTry: sinceTry)
        }
        XCTAssertFalse(due(trained: false, samples: 49)); XCTAssertTrue(due(trained: false, samples: 50))
        XCTAssertTrue(due(new: 50, sinceTrained: 21 * hour)); XCTAssertFalse(due(new: 50, sinceTrained: hour)); XCTAssertFalse(due(new: 49, sinceTrained: 21 * hour))
        XCTAssertTrue(due(outdated: true), "old profile, trained ten minutes ago, no new samples: train once")
        XCTAssertFalse(due(outdated: true, samples: 9))
        XCTAssertFalse(due(outdated: true, sinceTry: hour)); XCTAssertFalse(due(outdated: true, sinceTry: 19 * hour)); XCTAssertTrue(due(outdated: true, sinceTry: 21 * hour))
    }
}
