import XCTest
@testable import GhostCore

func tempDirectory() -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("ghostcore-tests-\(UUID().uuidString)")
    try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
}

/// A text field in memory, as UITextDocumentProxy behaves.
final class FakeField: TextTarget {
    var before: String
    var after: String
    var selected: String?
    var canMove = true
    init(_ before: String, _ after: String = "", selected: String? = nil) { self.before = before; self.after = after; self.selected = selected }
    func insert(_ text: String) { selected = nil; before += text }
    func deleteBackward(_ count: Int) {
        if selected != nil { selected = nil; return }
        before = String(before.dropLast(count))
    }
    func moveCursor(by offset: Int) -> Bool {
        guard canMove, offset > 0, offset <= after.count else { return false }
        before += after.prefix(offset); after = String(after.dropFirst(offset))
        return true
    }
}

final class GhostwriterParserTests: XCTestCase {
    func testClosedRequest() {
        let r = Ghostwriter.closedRequest(before: "Hi team, <<engineering standup tldr>>")
        XCTAssertEqual(r?.request, "engineering standup tldr"); XCTAssertEqual(r?.tag, "<<engineering standup tldr>>"); XCTAssertEqual(r?.deleteCount, 28)
        XCTAssertTrue(Ghostwriter.hasClosedRequest(before: "<<ok go>>"))
        XCTAssertEqual(Ghostwriter.closedRequest(before: "<<  spaced out  >>")?.request, "spaced out")
        // Emoji count once each for deleting.
        XCTAssertEqual(Ghostwriter.closedRequest(before: "x <<say 👋🏽 nicely>>")?.deleteCount, 16)
    }

    func testNotARequest() {
        for before in [
            "<<unclosed request",          // no >>
            "<<unclosed request>",         // half closed
            "<<>>",                        // empty
            "<<a>>",                       // one character
            "<<12 34>>",                   // no letter
            "<< >>", "<<   >>",
            "<<two\nlines>>",              // a request is one line
            "<<done>> and on",             // typed past it
            "<<done>>\n",
            "plain text", "", ">>", "a << b", "x >> y",
            "<<" + String(repeating: "a", count: 401) + ">>",
        ] { XCTAssertNil(Ghostwriter.closedRequest(before: before), before); XCTAssertFalse(Ghostwriter.hasClosedRequest(before: before), before) }
        XCTAssertNotNil(Ghostwriter.closedRequest(before: "<<" + String(repeating: "a", count: 400) + ">>"))
    }

    func testNested() {
        // The innermost, complete tag is the request.
        let nested = Ghostwriter.closedRequest(before: "<<outer <<inner one>>")
        XCTAssertEqual(nested?.request, "inner one"); XCTAssertEqual(nested?.tag, "<<inner one>>")
        XCTAssertEqual(Ghostwriter.closedRequest(before: "<<<triple up>>")?.tag, "<<triple up>>")
        // "<<a <<b>> c>>" ends with a tag whose body would hold < and >: not one.
        XCTAssertNil(Ghostwriter.closedRequest(before: "<<aa <<bb>> cc>>"))
        // A shift written in code is left alone unless it reads as a tag.
        XCTAssertNil(Ghostwriter.closedRequest(before: "x = 1 << 2 >> 1"))
        XCTAssertEqual(Ghostwriter.closedRequest(before: "std::cout << value >>")?.request, "value")
        // Two requests: the one just closed.
        XCTAssertEqual(Ghostwriter.closedRequest(before: "<<first one>> then <<second one>>")?.request, "second one")
    }

    func testTagAtCursor() {
        // Just after it.
        XCTAssertEqual(Ghostwriter.tagAt(before: "Hi <<standup tldr>>", after: " bye")?.request, "standup tldr")
        // Inside it.
        let inside = Ghostwriter.tagAt(before: "Hi <<stand", after: "up tldr>> bye")
        XCTAssertEqual(inside?.request, "standup tldr"); XCTAssertEqual(inside?.tag, "<<standup tldr>>")
        XCTAssertEqual(Ghostwriter.tagAt(before: "Hi <", after: "<standup tldr>> bye")?.request, "standup tldr")
        // Before it, or past it: not on it.
        XCTAssertNil(Ghostwriter.tagAt(before: "Hi ", after: "<<standup tldr>> bye"))
        XCTAssertNil(Ghostwriter.tagAt(before: "Hi <<standup tldr>> ", after: "bye"))
        XCTAssertNil(Ghostwriter.tagAt(before: "Hi <<standup", after: " tldr"))
        XCTAssertNil(Ghostwriter.tagAt(before: "", after: ""))
        // Of two, the one the cursor is on.
        XCTAssertEqual(Ghostwriter.tagAt(before: "<<first one>> and <<second", after: " one>>")?.request, "second one")
        XCTAssertEqual(Ghostwriter.tagAt(before: "<<first one>>", after: " and <<second one>>")?.request, "first one")
    }

    func testFillAt() {
        let fill = Ghostwriter.fillAt(before: "Morning all. <<standup tldr>>", after: "\nThanks")
        XCTAssertEqual(fill, Ghostwriter.Fill(request: "standup tldr", tag: "<<standup tldr>>", before: "Morning all. ", after: "\nThanks"))
        let inside = Ghostwriter.fillAt(before: "A <<same tag>> B <<same", after: " tag>> C")
        XCTAssertEqual(inside?.before, "A <<same tag>> B "); XCTAssertEqual(inside?.after, " C")
        XCTAssertNil(Ghostwriter.fillAt(before: "nothing here", after: ""))
    }

    func testClean() {
        XCTAssertEqual(Ghostwriter.clean("  hello  "), "hello")
        XCTAssertEqual(Ghostwriter.clean("\"Quoted whole.\""), "Quoted whole.")
        XCTAssertEqual(Ghostwriter.clean("\"One\" and \"two\""), "\"One\" and \"two\"")
        XCTAssertEqual(Ghostwriter.clean("```\ncode here\n```"), "code here")
        XCTAssertEqual(Ghostwriter.clean("```swift\nlet a = 1\n```"), "let a = 1")
        XCTAssertEqual(Ghostwriter.clean("\""), "\"")
    }

    func testPlace() {
        let job = Ghostwriter.Job(id: 1, request: "standup tldr", tag: "<<standup tldr>>")
        let field = FakeField("Morning. <<standup tldr>>", " Thanks")
        XCTAssertTrue(Ghostwriter.place(job, text: "Shipped it.", in: field))
        XCTAssertEqual(field.before, "Morning. Shipped it."); XCTAssertEqual(field.after, " Thanks")

        // The cursor inside the tag: it moves to the tag's end first, where the field can.
        let inside = FakeField("Morning. <<stand", "up tldr>> Thanks")
        XCTAssertTrue(Ghostwriter.place(job, text: "Done.", in: inside))
        XCTAssertEqual(inside.before + inside.after, "Morning. Done. Thanks")
        let stuck = FakeField("Morning. <<stand", "up tldr>> Thanks"); stuck.canMove = false
        XCTAssertFalse(Ghostwriter.place(job, text: "Done.", in: stuck))
        XCTAssertEqual(stuck.before, "Morning. <<stand")

        // Typed on past it, or the tag is gone: the answer waits.
        XCTAssertFalse(Ghostwriter.place(job, text: "x", in: FakeField("<<standup tldr>> and more")))
        XCTAssertFalse(Ghostwriter.place(job, text: "x", in: FakeField("something else")))
        XCTAssertFalse(Ghostwriter.place(job, text: "x", in: FakeField("")))

        // Key by key: only the tag goes.
        let typing = FakeField("A <<standup tldr>>")
        XCTAssertTrue(Ghostwriter.place(job, text: "typed later", in: typing, insert: false))
        XCTAssertEqual(typing.before, "A ")

        // A rewrite replaces the selection, while it is still the selection.
        let rewrite = Ghostwriter.Job(id: 2, request: "Fix", tag: "teh text", selection: true, mode: "fix")
        let selected = FakeField("Say ", " now", selected: "teh text")
        XCTAssertTrue(Ghostwriter.place(rewrite, text: "the text", in: selected))
        XCTAssertEqual(selected.before, "Say the text")
        XCTAssertFalse(Ghostwriter.place(rewrite, text: "the text", in: FakeField("Say ", "", selected: "other")))
        XCTAssertFalse(Ghostwriter.place(rewrite, text: "the text", in: FakeField("Say ")))
    }
}

final class GhostwriterPromptTests: XCTestCase {
    private func writer(_ values: [String: Any] = [:], secrets: [String: String] = [:], session: URLSession = StubProtocol.session())
        -> (Ghostwriter, KeyboardPrefs, Mockingbird, ClipHistory) {
        let (prefs, store, _) = makePrefs(values, secrets: secrets)
        let bird = Mockingbird(directory: tempDirectory(), session: session)
        let clips = ClipHistory(store: store)
        return (Ghostwriter(prefs: prefs, mockingbird: bird, clips: clips, github: GitHubCommits(store: store, session: session), session: session), prefs, bird, clips)
    }

    func testPromptWithNothingLearned() {
        let (gw, _, _, clips) = writer()
        clips.record("a copied secret")
        var utc = Calendar(identifier: .gregorian); utc.timeZone = .current
        let now = utc.date(from: DateComponents(year: 2026, month: 11, day: 9, hour: 12))!
        let (system, user) = gw.prompt(request: "decline politely", before: "", after: "", app: nil, kind: .other, now: now)
        XCTAssertTrue(system.hasPrefix("You are Ghostwriter, built into this person's phone keyboard. In a text field they typed a request\nbetween << and >>;"))
        XCTAssertTrue(system.contains("\n- Facts about them (their work, plans, people, what they did) come from their notes, GitHub\n  commits, past messages"))
        XCTAssertTrue(system.hasSuffix("asked for, and no explanation of what you did or searched.\n\nTHEIR STYLE PROFILE\n(Not trained yet: copy the voice of the past messages below.)\n\n" + VoiceRules.spellingRule))
        XCTAssertTrue(system.contains("\n- " + VoiceRules.writing + "\n- Reply with the replacement text only"))
        // The clipboard is off by default: nothing copied is sent.
        XCTAssertFalse(system.contains("a copied secret")); XCTAssertFalse(system.contains("GITHUB")); XCTAssertFalse(system.contains("NOTES"))
        XCTAssertEqual(user, "APP: unknown (other)\nTODAY: 2026-11-09\nTEXT BEFORE THE REQUEST:\n(nothing)\nTEXT AFTER THE REQUEST:\n(nothing)\nREQUEST: decline politely")
    }

    func testPromptCarriesContext() {
        let (gw, prefs, bird, clips) = writer()
        bird.record("the posthog dashboard is finally live, took all week", app: "ios.chat", chat: true)
        bird.record("Please find attached the quarterly numbers for review.", app: "ios.other", chat: false)
        bird.flush()
        clips.record("https://example.com/copied")
        prefs.set(key: KeyboardPrefs.ghostwriterClipboard, value: true)
        let before = String(repeating: "x", count: 2000) + " tail before"
        let (system, user) = gw.prompt(request: "posthog status", before: before, after: "after text", app: "ios.chat", kind: .chat)
        XCTAssertTrue(system.contains("\n\nTHEIR PAST MESSAGES (most relevant first; their memory, for facts and voice)\n---\nthe posthog dashboard is finally live, took all week\n"))
        XCTAssertFalse(system.contains("quarterly numbers"))
        XCTAssertTrue(system.hasSuffix("\nTHEIR RECENT CLIPBOARD (newest first)\n---\nhttps://example.com/copied\n"))
        XCTAssertTrue(user.hasPrefix("APP: ios.chat (chat: texting)\nTODAY: "))
        XCTAssertTrue(user.contains("TEXT BEFORE THE REQUEST:\n" + String(repeating: "x", count: 1488) + " tail before\nTEXT AFTER THE REQUEST:\nafter text\nREQUEST: posthog status"))
        // Memory off, clipboard plugin off: neither goes.
        prefs.set(key: KeyboardPrefs.ghostwriterMemory, value: false)
        Plugins.set(prefs, Plugins.clipboard, on: false)
        let bare = gw.prompt(request: "posthog status", before: "", after: "", app: "ios.chat", kind: .chat).system
        XCTAssertFalse(bare.contains("PAST MESSAGES")); XCTAssertFalse(bare.contains("CLIPBOARD"))
    }

    func testPromptWithGitHubAndNotes() {
        struct Notes: NotesSource {
            func search(words: [String]) throws -> (from: String, notes: [(title: String, body: String)]) { ("Ghost.md on this phone", [("PostHog plan", "Fire events on signup.")]) }
        }
        let session = StubProtocol.session()
        let (prefs, store, _) = makePrefs(secrets: ["github_token": "ghp_x"])
        let gw = Ghostwriter(prefs: prefs, mockingbird: Mockingbird(directory: tempDirectory()), github: GitHubCommits(store: store, session: session), notes: Notes(), session: session)
        let item = #"{"items":[{"sha":"a1","commit":{"message":"Add posthog events","committer":{"date":"2026-11-08T10:00:00Z"}},"repository":{"full_name":"octo/app"}}]}"#
        StubProtocol.reset([(200, #"{"login":"octo"}"#), (200, "[]"), (200, item)])
        var system = gw.prompt(request: "posthog updates we made", before: "", after: "", app: nil, kind: .other).system
        XCTAssertTrue(system.contains("\nTHEIR NOTES from Ghost.md on this phone matching posthog (best first)\n--- PostHog plan\nFire events on signup.\n"))
        XCTAssertTrue(system.hasSuffix("\nTHEIR GITHUB COMMITS matching posthog (newest first; what they and their AI agents actually changed)\n---\n2026-11-08 octo/app\nAdd posthog events\n"))
        StubProtocol.reset([(200, #"{"items":[]}"#)])
        system = gw.prompt(request: "posthog updates we made", before: "", after: "", app: nil, kind: .other).system
        XCTAssertTrue(system.hasSuffix("\n(No GitHub commits of theirs mention posthog.)\n"))
        // GitHub switched off: the token is not used at all.
        prefs.set(key: KeyboardPrefs.ghostwriterGithub, value: false)
        prefs.set(key: KeyboardPrefs.ghostwriterNotes, value: false)
        StubProtocol.reset([])
        system = gw.prompt(request: "posthog updates we made", before: "", after: "", app: nil, kind: .other).system
        XCTAssertFalse(system.contains("GitHub commits of theirs")); XCTAssertFalse(system.contains("THEIR NOTES")); XCTAssertTrue(StubProtocol.requests.isEmpty)
        // A refused token is said, not hidden.
        prefs.set(key: KeyboardPrefs.ghostwriterGithub, value: true)
        prefs.set(key: KeyboardPrefs.githubToken, value: "ghp_other")
        StubProtocol.reset([(401, "{}")])
        system = gw.prompt(request: "posthog updates we made", before: "", after: "", app: nil, kind: .other).system
        XCTAssertTrue(system.hasSuffix("\n(Their GitHub couldn't be searched: GitHub refused the token)\n"))
    }

    func testStartFillsAndCleans() {
        let session = StubProtocol.session()
        let (gw, _, _, _) = writer(["ai_provider": "openai", "ghostwriter_web": false], secrets: ["ai_key_openai": "sk"], session: session)
        StubProtocol.reset([(200, #"{"choices":[{"message":{"content":"\"Shipped it.\""}}]}"#)])
        let done = expectation(description: "done")
        let fill = Ghostwriter.fillAt(before: "Team: <<standup tldr>>", after: "")!
        let job = gw.start(fill, hints: AppContext.Hints(returnKeyIsSend: true)) { job in
            XCTAssertTrue(Thread.isMainThread)
            XCTAssertEqual(job.result, "Shipped it."); XCTAssertNil(job.error); XCTAssertFalse(job.running)
            done.fulfill()
        }
        XCTAssertEqual(job.tag, "<<standup tldr>>"); XCTAssertFalse(job.selection)
        wait(for: [done], timeout: 10)
        let sent = Json.object(StubProtocol.bodies[0])
        let messages = sent?["messages"] as? [[String: Any]]
        XCTAssertTrue((messages?.last?["content"] as? String)?.contains("APP: ios.chat (chat: texting)") ?? false)
        XCTAssertTrue((messages?.last?["content"] as? String)?.hasSuffix("TEXT BEFORE THE REQUEST:\nTeam: \nTEXT AFTER THE REQUEST:\n(nothing)\nREQUEST: standup tldr") ?? false)
        XCTAssertEqual(sent?["max_completion_tokens"] as? Int, 4000)
    }

    func testErrorsCancelAndRewrites() {
        let session = StubProtocol.session()
        let (gw, prefs, _, _) = writer(["ai_provider": "openai"], session: session)
        // No key: the job ends with the reason.
        let failed = expectation(description: "failed")
        gw.start(request: "anything at all", tag: "<<anything at all>>", before: "", after: "", app: nil, kind: .other) { job in
            XCTAssertEqual(job.error, "Add your OpenAI key in Settings › AI"); XCTAssertNil(job.result); failed.fulfill()
        }
        wait(for: [failed], timeout: 10)

        prefs.set(key: "ai_key_openai", value: "sk")
        // A cancelled job never calls back.
        StubProtocol.reset([(200, #"{"choices":[{"message":{"content":"late"}}]}"#), (200, #"{"choices":[{"message":{"content":"Fixed text."}}]}"#), (200, #"{"choices":[{"message":{"content":"my voice"}}]}"#)])
        let cancelled = gw.start(request: "anything at all", tag: "<<anything at all>>", before: "", after: "", app: nil, kind: .other) { _ in XCTFail("cancelled") }
        cancelled.cancelled = true
        XCTAssertFalse(cancelled.running)

        let rewritten = expectation(description: "rewritten")
        let job = gw.rewriteAs(selected: "teh text", mode: Rewrite.mode(prefs, id: "fix")!) { job in
            XCTAssertEqual(job.result, "Fixed text."); rewritten.fulfill()
        }
        XCTAssertTrue(job.selection); XCTAssertEqual(job.mode, "fix"); XCTAssertEqual(job.request, "Fix"); XCTAssertEqual(job.tag, "teh text")
        let voiced = expectation(description: "voiced")
        let voice = gw.rewrite(selected: "Hello there.", app: "ios.chat", kind: .chat) { job in
            XCTAssertEqual(job.result, "my voice"); voiced.fulfill()
        }
        XCTAssertEqual(voice.request, "In my voice"); XCTAssertTrue(voice.id > job.id)
        wait(for: [rewritten, voiced], timeout: 10)
        let system = (Json.object(StubProtocol.bodies[2])?["messages"] as? [[String: Any]])?.first?["content"] as? String
        XCTAssertTrue(system?.hasPrefix("Rewrite the user's text so it sounds exactly like this person texting. Keep the meaning") ?? false)
    }

    func testDebugEcho() {
        let (gw, _, _, _) = writer(secrets: ["ai_key_anthropic": "debug-echo"])
        gw.debugEcho = true
        let done = expectation(description: "echo")
        gw.start(request: "standup tldr", tag: "<<standup tldr>>", before: "", after: "", app: nil, kind: .other) { job in
            XCTAssertEqual(job.result, "Shipped the PostHog waitlist events today. (debug: notes none)"); done.fulfill()
        }
        wait(for: [done], timeout: 10)
    }
}

final class MockingbirdTests: XCTestCase {
    func testWhatIsKept() {
        XCTAssertNil(Mockingbird.cleaned("too short"))
        XCTAssertNil(Mockingbird.cleaned("onlytwo wordshere"))
        XCTAssertNil(Mockingbird.cleaned("https://example.com/a/very/long/link/indeed"))
        XCTAssertNil(Mockingbird.cleaned("12345 67890 12345 67890 a b"))
        XCTAssertNil(Mockingbird.cleaned("<<write my standup for me please>>"))
        XCTAssertEqual(Mockingbird.cleaned("  hey   are\tyou coming <<ask nicely>> tonight?\n\n\n\nlet me know  "), "hey are you coming tonight?\n\nlet me know")
        XCTAssertEqual(Mockingbird.cleaned(String(repeating: "word ", count: 600))?.count, Mockingbird.maxSampleChars)
    }

    func testSamplesAreStoredAndLimited() {
        let dir = tempDirectory()
        let bird = Mockingbird(directory: dir)
        XCTAssertTrue(bird.samples().isEmpty)
        bird.record("hey are you coming tonight", app: "ios.chat", chat: true)
        // The same draft, grown: kept once, at its longest.
        bird.record("hey are you coming tonight or not", app: "ios.chat", chat: true)
        bird.record("hey are you coming", app: "ios.chat", chat: true)
        bird.record("Dear all, the meeting has moved to Thursday.", app: "ios.other", chat: false)
        bird.record("short", app: "ios.chat", chat: true)
        bird.record("an email address field is the caller's to skip", hints: AppContext.Hints(returnKeyIsSend: true))
        bird.flush()
        XCTAssertEqual(bird.samples().map { $0.text }, ["hey are you coming tonight or not", "Dear all, the meeting has moved to Thursday.", "an email address field is the caller's to skip"])
        XCTAssertEqual(bird.samples().map { $0.chat }, [true, false, true]); XCTAssertEqual(bird.samples()[2].app, "ios.chat")
        // On disk in the directory given, as Android's JSON; another instance reads the same.
        let file = dir.appendingPathComponent("mockingbird_samples.json")
        let json = Json.array((try? Data(contentsOf: file)) ?? Data()) as? [[String: Any]]
        XCTAssertEqual(json?.count, 3); XCTAssertEqual(json?[0]["t"] as? String, "hey are you coming tonight or not"); XCTAssertEqual(json?[0]["c"] as? Bool, true)
        XCTAssertEqual(json?[0]["a"] as? String, "ios.chat"); XCTAssertNotNil(json?[0]["at"] as? Int)
        XCTAssertEqual(Mockingbird(directory: dir).samples(), bird.samples())
        // A repeat moves to the end rather than being kept twice.
        bird.record("Dear all, the meeting has moved to Thursday.", app: "ios.mail", chat: false)
        bird.flush()
        XCTAssertEqual(bird.samples().count, 3); XCTAssertEqual(bird.samples().last?.app, "ios.mail")

        for i in 0..<(Mockingbird.maxSamples + 20) { bird.record("sample number \(i) says something here", app: "app\(i)", chat: i % 2 == 0) }
        bird.flush()
        XCTAssertEqual(bird.samples().count, Mockingbird.maxSamples)
        XCTAssertEqual(bird.samples().last?.text, "sample number \(Mockingbird.maxSamples + 19) says something here")
        XCTAssertEqual(bird.samples().first?.text, "sample number 20 says something here")

        bird.clear()
        XCTAssertTrue(bird.samples().isEmpty); XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
    }

    func testProfileStorage() {
        let dir = tempDirectory()
        let bird = Mockingbird(directory: dir)
        XCTAssertNil(bird.profile())
        XCTAssertEqual(bird.voiceSection(), "THEIR STYLE PROFILE\n(Not trained yet: copy the voice of the past messages below.)\n\n" + VoiceRules.spellingRule)
        bird.importProfile(String(repeating: "p", count: 7000), source: "Imported from Report")
        let p = Mockingbird(directory: dir).profile()
        XCTAssertEqual(p?.text.count, 6000); XCTAssertEqual(p?.provider, "Imported from Report"); XCTAssertEqual(p?.model, ""); XCTAssertEqual(p?.samples, 0)
        XCTAssertTrue(bird.voiceSection().hasPrefix("THEIR STYLE PROFILE\nppp"))
        XCTAssertTrue(FileManager.default.fileExists(atPath: dir.appendingPathComponent("mockingbird_profile.json").path))
        bird.clear()
        XCTAssertNil(bird.profile())
    }

    func testRelevant() {
        let bird = Mockingbird(directory: tempDirectory())
        bird.record("the posthog dashboard is finally live", app: "slack", chat: true)
        bird.record("dinner at eight tonight works for me", app: "sms", chat: true)
        bird.record("posthog events were missing on waitlist signup", app: "slack", chat: true)
        bird.record("see you all at the offsite next month", app: "sms", chat: true)
        bird.flush()
        // Shared words first (the newer of equals), then the latest from this app.
        XCTAssertEqual(bird.relevant(query: "posthog waitlist", app: "sms").map { $0.text },
                       ["posthog events were missing on waitlist signup", "the posthog dashboard is finally live", "see you all at the offsite next month", "dinner at eight tonight works for me"])
        XCTAssertEqual(bird.relevant(query: "posthog waitlist", app: nil).count, 2)
        XCTAssertEqual(bird.relevant(query: "posthog waitlist", app: "sms", budget: 50).count, 2)
        XCTAssertEqual(bird.relevant(query: "posthog", app: nil, budget: 1).count, 1)
        XCTAssertTrue(bird.relevant(query: "the and for", app: nil).isEmpty)
    }

    func testTraining() {
        let session = StubProtocol.session()
        let dir = tempDirectory()
        let bird = Mockingbird(directory: dir, session: session)
        let prefs = aiPrefs("openai")
        let tooFew = expectation(description: "too few")
        bird.train(prefs: prefs) { error in XCTAssertEqual(error, "Type a little more first: 0 of 10 samples"); tooFew.fulfill() }
        wait(for: [tooFew], timeout: 10)
        XCTAssertEqual(bird.lastError, "Type a little more first: 0 of 10 samples"); XCTAssertFalse(bird.training)

        for i in 0..<12 { bird.record("message number \(i) with a few words", app: "ios.chat", chat: i < 8) }
        bird.flush()
        let message = Mockingbird.trainingMessage(bird.samples())
        XCTAssertTrue(message.hasPrefix("TEXTING (8 messages)\n---\nmessage number 0 with a few words\n"))
        XCTAssertTrue(message.contains("\n\nWRITING (4 pieces)\n---\nmessage number 8 with a few words\n"))
        XCTAssertTrue(Mockingbird.trainSystem.hasPrefix("You study one person's writing")); XCTAssertTrue(Mockingbird.trainSystem.hasSuffix("under 700 words."))

        StubProtocol.reset([(200, #"{"choices":[{"message":{"content":"They write in lower case."}}]}"#)])
        let trained = expectation(description: "trained")
        bird.train(prefs: prefs) { error in XCTAssertNil(error); trained.fulfill() }
        wait(for: [trained], timeout: 10)
        let p = bird.profile()
        XCTAssertEqual(p?.text, "They write in lower case."); XCTAssertEqual(p?.samples, 12); XCTAssertEqual(p?.provider, "OpenAI"); XCTAssertEqual(p?.model, "gpt-5-mini")
        let sent = Json.object(StubProtocol.bodies[0])
        XCTAssertEqual(sent?["max_completion_tokens"] as? Int, 8000)
        XCTAssertEqual((sent?["messages"] as? [[String: Any]])?.last?["content"] as? String, message)
        XCTAssertNil(bird.lastError)
    }

    func testPickKeepsTheNewest() {
        let samples = (0..<10).map { Mockingbird.Sample(text: String(repeating: "a", count: 96), chat: true, app: "x", at: Int64($0)) }
        XCTAssertEqual(Mockingbird.pick(samples, 250).map { $0.at }, [7, 8, 9])
        XCTAssertEqual(Mockingbird.pick(samples, 100_000).count, 10)
    }

    func testAutoTrainCadence() {
        let session = StubProtocol.session()
        let dir = tempDirectory()
        let bird = Mockingbird(directory: dir, session: session)
        let prefs = aiPrefs("openai")
        func add(_ range: Range<Int>) { for i in range { bird.record("auto sample \(i) with enough words", app: "a\(i)", chat: true) }; bird.flush() }
        add(0..<49)
        XCTAssertFalse(bird.autoTrainDue())
        add(49..<50)
        XCTAssertTrue(bird.autoTrainDue())
        // Without the person's consent nothing is sent by itself.
        StubProtocol.reset([])
        func started(_ prefs: KeyboardPrefs) -> Bool { bird.maybeAutoTrain(prefs: prefs); bird.flush(); bird.flush(); return !StubProtocol.requests.isEmpty }
        XCTAssertFalse(started(prefs))
        prefs.set(key: KeyboardPrefs.aiConsent, value: true)
        prefs.set(key: KeyboardPrefs.mockingbirdAutoTrain, value: false)
        XCTAssertFalse(started(prefs))
        prefs.set(key: KeyboardPrefs.mockingbirdAutoTrain, value: true)
        XCTAssertFalse(started(aiPrefs("openai", key: "")))   // the AI is not set up

        // Trained on 50: due again only after 50 more, and most of a day.
        bird.importProfile("profile", source: "test")
        XCTAssertFalse(bird.autoTrainDue(now: Date().addingTimeInterval(86_400)))
        add(50..<100)
        XCTAssertFalse(bird.autoTrainDue())
        XCTAssertFalse(bird.autoTrainDue(now: Date().addingTimeInterval(19 * 3600)))
        XCTAssertTrue(bird.autoTrainDue(now: Date().addingTimeInterval(21 * 3600)))

        let fresh = Mockingbird(directory: tempDirectory(), session: session)
        for i in 0..<50 { fresh.record("fresh sample \(i) with enough words", app: "a\(i)", chat: true) }
        fresh.flush()
        StubProtocol.reset([(200, #"{"choices":[{"message":{"content":"Profile."}}]}"#)])
        fresh.maybeAutoTrain(prefs: prefs)
        fresh.flush(); fresh.flush()   // the check, then the training it started
        XCTAssertFalse(fresh.training)
        XCTAssertEqual(fresh.profile()?.text, "Profile."); XCTAssertEqual(fresh.profile()?.version, VoiceRules.profileVersion)
    }
}

/// Mockingbird with the dictionaries to hand: slips are repaired as a sample is kept (SampleCleanerTests has the rules).
final class MockingbirdSlipsTests: XCTestCase {
    private let mine = ["the cat sat on the mat and that was that", "this keyboard is nice because it is quiet", "see you tomorrow at their house"]

    func testSamplesAreCleanedAsTheyAreKept() {
        let dir = tempDirectory()
        let bird = Mockingbird(directory: dir)
        // No dictionaries yet: kept as typed, and marked as not cleaned.
        bird.record("see you at teh house later", app: "ios.chat", chat: true)
        bird.flush()
        XCTAssertEqual(bird.samples().map { $0.text }, ["see you at teh house later"]); XCTAssertEqual(bird.samples()[0].cleaned, 0); XCTAssertNil(bird.samples()[0].raw)
        // With the keyboard's speller the new sample is cleaned, and so is the one that was waiting.
        bird.speller = SampleCleanerTests.english
        for (i, text) in mine.enumerated() { bird.record(text, app: "a\(i)", chat: true) }
        bird.record("this keybkard is really nice", app: "ios.other", chat: false)
        bird.flush()
        let kept = bird.samples()
        XCTAssertEqual(kept.first?.text, "see you at the house later"); XCTAssertEqual(kept.first?.raw, "see you at teh house later"); XCTAssertEqual(kept.first?.typed, "see you at teh house later")
        XCTAssertEqual(kept.last?.text, "this keyboard is really nice"); XCTAssertEqual(kept.last?.raw, "this keybkard is really nice")
        XCTAssertTrue(kept.allSatisfy { $0.cleaned == SampleCleaner.version }); XCTAssertNil(kept[1].raw)
        // On disk as Android keeps them: "r" as typed where it differs, "v" the cleaner's version.
        let json = Json.array((try? Data(contentsOf: dir.appendingPathComponent(Mockingbird.samplesFile))) ?? Data()) as? [[String: Any]]
        XCTAssertEqual(json?.last?["t"] as? String, "this keyboard is really nice"); XCTAssertEqual(json?.last?["r"] as? String, "this keybkard is really nice")
        XCTAssertEqual(json?.last?["v"] as? Int, SampleCleaner.version); XCTAssertNil(json?[1]["r"])
        XCTAssertEqual(Mockingbird(directory: dir).samples(), kept)
        // The same draft read again is still the same draft, though it was cleaned.
        bird.record("this keybkard is really nice today", app: "ios.other", chat: false)
        bird.flush()
        XCTAssertEqual(bird.samples().count, kept.count); XCTAssertEqual(bird.samples().last?.text, "this keyboard is really nice today")
        // The AIs read the cleaned text.
        XCTAssertTrue(Mockingbird.trainingMessage(bird.samples()).contains("this keyboard is really nice today"))
        XCTAssertFalse(bird.rewriteSystem(text: "keyboard nice", app: nil, chat: false).contains("keybkard"))
        XCTAssertTrue(bird.rewriteSystem(text: "x", app: nil, chat: true).contains(VoiceRules.rewriting + " Reply with the rewritten text alone"))

        // Typed a third time, in other samples, the word is theirs: it goes back where it was taken out, and is remembered.
        bird.record("my keybkard broke again today", app: "b1", chat: true)
        bird.flush()
        XCTAssertEqual(bird.samples().last?.text, "my keyboard broke again today")
        bird.record("the keybkard came in the post", app: "b2", chat: true)
        bird.flush()
        XCTAssertEqual(Array(bird.samples().suffix(3)).map { $0.text }, ["this keybkard is really nice today", "my keybkard broke again today", "the keybkard came in the post"])
        XCTAssertTrue(bird.samples().suffix(3).allSatisfy { $0.raw == nil })
        let words = dir.appendingPathComponent(Mockingbird.wordsFile)
        XCTAssertEqual(Json.array((try? Data(contentsOf: words)) ?? Data()) as? [String], ["keybkard"])
        XCTAssertEqual(bird.samples().first?.text, "see you at the house later")
        bird.clear()
        XCTAssertFalse(FileManager.default.fileExists(atPath: words.path))
    }

    func testTheAppLoadsDictionariesToTrain() {
        let session = StubProtocol.session()
        let bird = Mockingbird(directory: tempDirectory(), session: session)
        let prefs = aiPrefs("openai")
        for i in 0..<9 { bird.record("the cat sat on the mat number \(i)", app: "a\(i)", chat: true) }
        bird.record("see you at teh house later", app: "ios.chat", chat: true)
        bird.flush()
        XCTAssertEqual(bird.samples().last?.text, "see you at teh house later")
        bird.dictDirectory = dictDirectory
        StubProtocol.reset([(200, #"{"choices":[{"message":{"content":"They write."}}]}"#)])
        let trained = expectation(description: "trained")
        bird.train(prefs: prefs) { error in XCTAssertNil(error); trained.fulfill() }
        wait(for: [trained], timeout: 30)
        XCTAssertEqual(bird.samples().last?.text, "see you at the house later")
        let sent = (Json.object(StubProtocol.bodies[0])?["messages"] as? [[String: Any]])
        XCTAssertEqual(sent?.first?["content"] as? String, VoiceRules.trainSystem)
        XCTAssertTrue((sent?.last?["content"] as? String ?? "").contains("see you at the house later"))
        XCTAssertEqual(bird.cleanedSamples(prefs: prefs), bird.samples())
    }

    func testAnOutdatedProfileRetrainsOnce() throws {
        let session = StubProtocol.session()
        let dir = tempDirectory()
        let bird = Mockingbird(directory: dir, session: session)
        let prefs = aiPrefs("openai")
        prefs.set(key: KeyboardPrefs.aiConsent, value: true)
        for i in 0..<10 { bird.record("old sample \(i) with enough words", app: "a\(i)", chat: true) }
        bird.flush()
        // A profile from before versions were written: trained under the first instructions.
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        try Json.data(["text": "They leave typos in.", "at": now - 600_000, "samples": 10, "provider": "OpenAI", "model": "gpt-5-mini"] as [String: Any])
            .write(to: dir.appendingPathComponent(Mockingbird.profileFile))
        let old = try XCTUnwrap(bird.profile())
        XCTAssertEqual(old.version, 1); XCTAssertTrue(bird.outdated(old)); XCTAssertTrue(bird.autoTrainDue())
        XCTAssertTrue(bird.voiceSection().hasSuffix("They leave typos in.\n\n" + VoiceRules.spellingRule))
        // The retrain fails: not tried again until a day later, and the try is remembered in the files.
        StubProtocol.reset([(500, #"{"error":{"message":"down"}}"#)])
        bird.maybeAutoTrain(prefs: prefs)
        bird.flush(); bird.flush()
        XCTAssertEqual(StubProtocol.requests.count, 1); XCTAssertNotNil(bird.lastError)
        XCTAssertFalse(bird.autoTrainDue()); XCTAssertFalse(Mockingbird(directory: dir).autoTrainDue(now: Date().addingTimeInterval(19 * 3600)))
        XCTAssertTrue(bird.autoTrainDue(now: Date().addingTimeInterval(21 * 3600)))
        bird.maybeAutoTrain(prefs: prefs)
        bird.flush(); bird.flush()
        XCTAssertEqual(StubProtocol.requests.count, 1)
        // Trained again, it is current.
        StubProtocol.reset([(200, #"{"choices":[{"message":{"content":"They write correctly spelled words."}}]}"#)])
        let trained = expectation(description: "trained")
        bird.train(prefs: prefs) { _ in trained.fulfill() }
        wait(for: [trained], timeout: 10)
        let fresh = try XCTUnwrap(bird.profile())
        XCTAssertEqual(fresh.version, VoiceRules.profileVersion); XCTAssertFalse(bird.outdated(fresh)); XCTAssertFalse(bird.autoTrainDue(now: Date().addingTimeInterval(21 * 3600)))
        // One written elsewhere is the person's choice, whatever version it is kept at.
        bird.importProfile("Theirs.", source: Retrain.importedBy("Some Report Tool"), version: 1)
        XCTAssertFalse(bird.outdated(try XCTUnwrap(bird.profile())))
    }

    func testTypingProfileCarriesTheVersionAndWhatWasTyped() throws {
        let (prefs, store, _) = makePrefs(["plugins": "autocorrect,adaptive,mockingbird"])
        let adaptive = AdaptiveKeys(store: store)
        let dir = tempDirectory()
        let bird = Mockingbird(directory: dir)
        bird.speller = SampleCleanerTests.english
        for (i, text) in mine.enumerated() { bird.record(text, app: "a\(i)", chat: true) }
        bird.record("see you at teh house later", app: "ios.chat", chat: true)
        bird.flush()
        try Json.data(["text": "Old.", "at": 1, "samples": 4, "provider": "OpenAI", "model": "m"] as [String: Any]).write(to: dir.appendingPathComponent(Mockingbird.profileFile))
        let exported = TypingProfile.export(prefs: prefs, adaptive: adaptive, mockingbird: bird, samples: true)
        let style = try XCTUnwrap(Json.object(exported)?["style"] as? [String: Any])
        XCTAssertEqual(style["profileVersion"] as? Int, 1)
        let last = try XCTUnwrap((style["samples"] as? [[String: Any]])?.last)
        XCTAssertEqual(last["text"] as? String, "see you at the house later"); XCTAssertEqual(last["typed"] as? String, "see you at teh house later")
        XCTAssertNil((style["samples"] as? [[String: Any]])?.first?["typed"])

        // Into another keyboard: the profile is still due its retrain, and the sample comes in as typed.
        let bird2 = Mockingbird(directory: tempDirectory())
        _ = try TypingProfile.import(exported, prefs: prefs, adaptive: adaptive, mockingbird: bird2)
        bird2.flush()
        let p = try XCTUnwrap(bird2.profile())
        XCTAssertEqual(p.provider, "Imported from Ghost Keyboard"); XCTAssertEqual(p.version, 1); XCTAssertTrue(bird2.outdated(p))
        XCTAssertEqual(bird2.samples().last?.text, "see you at teh house later")
        // Our own file from before versions is old; another app's is never outdated; a current one stays current.
        func imported(_ json: String) throws -> Mockingbird.Profile {
            let b = Mockingbird(directory: tempDirectory())
            _ = try TypingProfile.import(json, prefs: prefs, adaptive: adaptive, mockingbird: b)
            return try XCTUnwrap(b.profile())
        }
        let before = try imported(#"{"format":"ghost-typing-profile","version":1,"source":{"app":"Ghost Keyboard"},"style":{"profile":"x"}}"#)
        XCTAssertEqual(before.version, 1)
        let other = try imported(#"{"format":"ghost-typing-profile","version":1,"source":{"app":"Reporter"},"style":{"profile":"x","profileVersion":1}}"#)
        XCTAssertEqual(other.version, VoiceRules.profileVersion); XCTAssertEqual(other.provider, "Imported from Reporter")
        let current = try imported(#"{"format":"ghost-typing-profile","version":1,"source":{"app":"Ghost Keyboard"},"style":{"profile":"x","profileVersion":2}}"#)
        XCTAssertEqual(current.version, 2)
    }
}

final class TypingProfileTests: XCTestCase {
    func testExportImportRoundTrip() throws {
        let (prefs, store, _) = makePrefs(["plugins": "autocorrect,adaptive,mockingbird"])
        let adaptive = AdaptiveKeys(store: store)
        for _ in 0..<20 { adaptive.hit("a", dx: 0.1234, dy: -0.2) }
        adaptive.mistake(pressed: "s", meant: "a", dx: 0.4, dy: 0, x: 0.15, y: 0.5)
        prefs.learn("ghostkbd")
        let bird = Mockingbird(directory: tempDirectory())
        bird.importProfile("They write tersely.", source: "test")
        bird.record("a message worth keeping around", app: "ios.chat", chat: true)
        bird.flush()

        let exported = TypingProfile.export(prefs: prefs, adaptive: adaptive, mockingbird: bird, samples: true)
        let o = Json.object(exported)
        XCTAssertEqual(o?["format"] as? String, "ghost-typing-profile"); XCTAssertEqual(o?["version"] as? Int, 1); XCTAssertEqual(o?["layout"] as? String, "qwerty")
        XCTAssertEqual((o?["source"] as? [String: Any])?["app"] as? String, "Ghost Keyboard")
        XCTAssertEqual(((o?["keys"] as? [String: Any])?["a"] as? [String: Any])?["samples"] as? Int, 21)
        XCTAssertEqual((o?["slips"] as? [[String: Any]])?.first?["meant"] as? String, "a")
        XCTAssertEqual((o?["words"] as? [String: Any])?["learned"] as? [String], ["ghostkbd"])
        XCTAssertEqual((o?["style"] as? [String: Any])?["profile"] as? String, "They write tersely.")
        XCTAssertEqual(((o?["style"] as? [String: Any])?["samples"] as? [[String: Any]])?.first?["kind"] as? String, "chat")
        XCTAssertNil((Json.object(TypingProfile.export(prefs: prefs, adaptive: adaptive, mockingbird: bird, samples: false))?["style"] as? [String: Any])?["samples"])

        // Into a fresh keyboard.
        let (prefs2, store2, _) = makePrefs(["plugins": "autocorrect,adaptive,mockingbird"])
        let adaptive2 = AdaptiveKeys(store: store2)
        let bird2 = Mockingbird(directory: tempDirectory())
        let result = try TypingProfile.import(exported, prefs: prefs2, adaptive: adaptive2, mockingbird: bird2)
        bird2.flush()
        XCTAssertEqual(result.lines, ["1 key aim blended in", "1 slip learned", "1 word added to autocorrect", "Style profile replaced", "1 writing sample added"])
        XCTAssertEqual(result.source, "Ghost Keyboard"); XCTAssertNil(result.report)
        XCTAssertEqual(adaptive2.aims()["a"]?.n, 22); XCTAssertEqual(adaptive2.mistakes.count, 1)
        XCTAssertEqual(prefs2.learnedWords, ["ghostkbd"])
        XCTAssertEqual(bird2.profile()?.provider, "Imported from Ghost Keyboard"); XCTAssertEqual(bird2.samples().first?.app, "imported.chat")
        XCTAssertNotNil(store2.object(forKey: AdaptiveKeys.storeKey))
    }

    func testImportRefusalsAndSkips() throws {
        let (prefs, store, _) = makePrefs()
        let adaptive = AdaptiveKeys(store: store)
        let bird = Mockingbird(directory: tempDirectory())
        func error(_ text: String) -> String? {
            do { _ = try TypingProfile.import(text, prefs: prefs, adaptive: adaptive, mockingbird: bird); return nil } catch { return (error as? TypingProfile.ProfileError)?.message }
        }
        XCTAssertEqual(error("not json"), "That file isn't JSON")
        XCTAssertEqual(error("{}"), "That file isn't a Ghost typing profile (no \"format\": \"ghost-typing-profile\")")
        XCTAssertEqual(error(#"{"format":"ghost-typing-profile","version":2}"#), "That typing profile is version 2; this keyboard reads version 1")
        // Adaptive keys and Mockingbird are not added: their sections are skipped, the words still go in.
        let profile = #"{"format":"ghost-typing-profile","version":1,"source":{"app":"Reporter","model":"m-1"},"report":"All good.","keys":{"a":{"dx":0.1,"dy":0,"samples":40}},"slips":[{"pressed":"s","meant":"a","count":3}],"words":{"learned":["Kubectl","two words",""]},"style":{"profile":"x"}}"#
        let result = try TypingProfile.import(profile, prefs: prefs, adaptive: adaptive, mockingbird: bird)
        XCTAssertEqual(result.lines, ["Key aims skipped: add Adaptive keys to use them", "Slips skipped: add Adaptive keys to use them", "1 word added to autocorrect", "Writing style skipped: add Mockingbird to use it"])
        XCTAssertEqual(result.source, "Reporter, m-1"); XCTAssertEqual(result.report, "All good.")
        XCTAssertEqual(prefs.learnedWords, ["kubectl"]); XCTAssertTrue(adaptive.aims().isEmpty); XCTAssertNil(bird.profile())
        Plugins.set(prefs, Plugins.adaptive, on: true)
        let again = try TypingProfile.import(profile, prefs: prefs, adaptive: adaptive, mockingbird: bird)
        XCTAssertEqual(Array(again.lines.prefix(2)), ["1 key aim blended in", "3 slips learned"])
        XCTAssertEqual(adaptive.aims()["a"]?.n, 43)
        let dvorak = try TypingProfile.import(#"{"format":"ghost-typing-profile","version":1,"layout":"dvorak","keys":{"a":{}}}"#, prefs: prefs, adaptive: adaptive, mockingbird: bird)
        XCTAssertEqual(dvorak.lines, ["Key aims skipped: they are for dvorak, and this keyboard reads qwerty"])
        XCTAssertEqual(try TypingProfile.import(#"{"format":"ghost-typing-profile","version":1}"#, prefs: prefs, adaptive: adaptive, mockingbird: bird).lines, ["Nothing in it this keyboard reads"])
    }
}
