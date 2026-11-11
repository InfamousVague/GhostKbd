import XCTest
@testable import GhostCore

/// Answers requests from a queue of canned responses, and remembers what was asked.
final class StubProtocol: URLProtocol {
    static let lock = NSLock()
    static var responses: [(status: Int, body: String)] = []
    static var requests: [URLRequest] = []
    static var bodies: [Data] = []

    static func reset(_ responses: [(Int, String)]) {
        lock.lock(); defer { lock.unlock() }
        self.responses = responses.map { (status: $0.0, body: $0.1) }; requests = []; bodies = []
    }

    static func session() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubProtocol.self]
        return URLSession(configuration: config)
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        var body = request.httpBody ?? Data()
        if body.isEmpty, let stream = request.httpBodyStream {
            stream.open()
            var buffer = [UInt8](repeating: 0, count: 65536)
            while stream.hasBytesAvailable { let n = stream.read(&buffer, maxLength: buffer.count); if n <= 0 { break }; body.append(buffer, count: n) }
            stream.close()
        }
        StubProtocol.lock.lock()
        StubProtocol.requests.append(request); StubProtocol.bodies.append(body)
        let next = StubProtocol.responses.isEmpty ? (status: 500, body: "{}") : StubProtocol.responses.removeFirst()
        StubProtocol.lock.unlock()
        let response = HTTPURLResponse(url: request.url!, statusCode: next.status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(next.body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}

func body(_ request: URLRequest) -> [String: Any] { Json.object(request.httpBody ?? Data()) ?? [:] }

func aiPrefs(_ provider: String, key: String = "k-123", model: String? = nil, base: String? = nil) -> KeyboardPrefs {
    var values: [String: Any] = ["ai_provider": provider]
    if let model = model { values["ai_model_\(provider)"] = model }
    if let base = base { values["ai_base_url"] = base }
    return makePrefs(values, secrets: key.isEmpty ? [:] : ["ai_key_\(provider)": key]).0
}

final class AiClientTests: XCTestCase {
    func testProviders() {
        XCTAssertEqual(AiClient.providers.map { $0.id }, KeyboardPrefs.aiProviders)
        XCTAssertEqual(AiClient.provider("groq").base, "https://api.groq.com/openai/v1")
        XCTAssertEqual(AiClient.provider("nobody").id, "anthropic")
        XCTAssertEqual(AiClient.providers.map { $0.defaultModel }, ["claude-opus-5-5", "gpt-5-mini", "gemini-2.5-flash", "mistral-small-latest", "llama-3.3-70b-versatile", "deepseek-chat", "openrouter/auto", ""])
        XCTAssertEqual(AiClient.searches, ["anthropic", "openai", "gemini", "openrouter"])
        XCTAssertTrue(AiClient.canSearch(prefs: aiPrefs("gemini"))); XCTAssertFalse(AiClient.canSearch(prefs: aiPrefs("mistral")))
    }

    private func setupError(_ prefs: KeyboardPrefs) -> String? {
        do { _ = try AiClient.setup(prefs: prefs); return nil } catch { return (error as? AiClient.AiError)?.message ?? "other" }
    }

    func testSetup() throws {
        XCTAssertEqual(setupError(aiPrefs("anthropic", key: "")), "Add your Claude key in Settings › AI")
        XCTAssertEqual(setupError(aiPrefs("custom", key: "")), "Add your server's address in Settings › AI")
        XCTAssertEqual(setupError(aiPrefs("custom", key: "", base: "ftp://x/v1")), "The server's address must start with https://")
        XCTAssertEqual(setupError(aiPrefs("custom", key: "", base: "https://llm.example.com/v1")), "Pick a model in Settings › AI")
        XCTAssertFalse(AiClient.ready(prefs: aiPrefs("openai", key: "")))
        XCTAssertTrue(AiClient.ready(prefs: aiPrefs("openai")))

        let s = try AiClient.setup(prefs: aiPrefs("anthropic", key: "  sk-ant  "))
        XCTAssertEqual(s.key, "sk-ant"); XCTAssertEqual(s.model, "claude-opus-5-5"); XCTAssertEqual(s.base, "https://api.anthropic.com/v1")
        XCTAssertEqual(try AiClient.setup(prefs: aiPrefs("openai", model: " gpt-x ")).model, "gpt-x")

        // A custom server needs no key, and may be plain http (a stub on 127.0.0.1 in the Simulator).
        let local = try AiClient.setup(prefs: aiPrefs("custom", key: "", model: "llama3", base: " http://127.0.0.1:8089/v1// "))
        XCTAssertEqual(local.base, "http://127.0.0.1:8089/v1"); XCTAssertEqual(local.key, ""); XCTAssertFalse(local.insecure)
        XCTAssertTrue(try AiClient.setup(prefs: aiPrefs("custom", model: "m", base: "http://192.168.1.20:11434/v1")).insecure)
        XCTAssertFalse(try AiClient.setup(prefs: aiPrefs("custom", model: "m", base: "https://llm.example.com/v1")).insecure)
        let request = AiClient.buildRequest(setup: local, system: "s", user: "u", maxTokens: 10)
        XCTAssertEqual(request.url?.absoluteString, "http://127.0.0.1:8089/v1/chat/completions")
        XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
    }

    func testAnthropicRequest() throws {
        let s = try AiClient.setup(prefs: aiPrefs("anthropic", key: "sk-ant"))
        let r = AiClient.buildRequest(setup: s, system: "SYS", user: "USER", maxTokens: 4000, effort: "low", webSearch: true)
        XCTAssertEqual(r.url?.absoluteString, "https://api.anthropic.com/v1/messages"); XCTAssertEqual(r.httpMethod, "POST")
        XCTAssertEqual(r.value(forHTTPHeaderField: "x-api-key"), "sk-ant")
        XCTAssertEqual(r.value(forHTTPHeaderField: "anthropic-version"), "2026-10-21")
        XCTAssertEqual(r.value(forHTTPHeaderField: "anthropic-beta"), "server-side-fallback-2026-07-01")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Accept"), "application/json"); XCTAssertEqual(r.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(r.timeoutInterval, 120)
        let b = body(r)
        XCTAssertEqual(b["model"] as? String, "claude-opus-5-5"); XCTAssertEqual(b["max_tokens"] as? Int, 4000); XCTAssertEqual(b["system"] as? String, "SYS")
        XCTAssertEqual(b["fallbacks"] as? String, "default")
        XCTAssertEqual((b["output_config"] as? [String: Any])?["effort"] as? String, "low")
        let messages = b["messages"] as? [[String: Any]]
        XCTAssertEqual(messages?.count, 1); XCTAssertEqual(messages?[0]["role"] as? String, "user"); XCTAssertEqual(messages?[0]["content"] as? String, "USER")
        let tool = (b["tools"] as? [[String: Any]])?.first
        XCTAssertEqual(tool?["type"] as? String, "web_search_20260209"); XCTAssertEqual(tool?["name"] as? String, "web_search"); XCTAssertEqual(tool?["max_uses"] as? Int, 5)

        // An older model: no effort, no fallback (so no beta header), the basic search; and no tools without search.
        let old = try AiClient.setup(prefs: aiPrefs("anthropic", model: "claude-haiku-4-5"))
        let r2 = AiClient.buildRequest(setup: old, system: "S", user: "U", maxTokens: 100, webSearch: true)
        XCTAssertNil(r2.value(forHTTPHeaderField: "anthropic-beta"))
        XCTAssertNil(body(r2)["output_config"]); XCTAssertNil(body(r2)["fallbacks"])
        XCTAssertEqual((body(r2)["tools"] as? [[String: Any]])?.first?["type"] as? String, "web_search_20250305")
        XCTAssertNil(body(AiClient.buildRequest(setup: old, system: "S", user: "U", maxTokens: 100))["tools"])

        // A paused search goes back with the assistant's turn so far.
        let resumed = body(AiClient.buildRequest(setup: s, system: "S", user: "U", maxTokens: 100, webSearch: true, resuming: [[["type": "server_tool_use", "id": "x"]]]))
        let turns = resumed["messages"] as? [[String: Any]]
        XCTAssertEqual(turns?.count, 2); XCTAssertEqual(turns?[1]["role"] as? String, "assistant")
        XCTAssertEqual(((turns?[1]["content"] as? [[String: Any]])?.first)?["type"] as? String, "server_tool_use")

        let models = AiClient.buildModelsRequest(setup: s)
        XCTAssertEqual(models.url?.absoluteString, "https://api.anthropic.com/v1/models?limit=100"); XCTAssertEqual(models.httpMethod, "GET")
        XCTAssertNil(models.value(forHTTPHeaderField: "anthropic-beta")); XCTAssertNil(models.httpBody)
    }

    func testGeminiRequest() throws {
        let s = try AiClient.setup(prefs: aiPrefs("gemini", key: "g-key"))
        let r = AiClient.buildRequest(setup: s, system: "SYS", user: "USER", maxTokens: 900, webSearch: true)
        XCTAssertEqual(r.url?.absoluteString, "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent")
        XCTAssertEqual(r.value(forHTTPHeaderField: "x-goog-api-key"), "g-key"); XCTAssertNil(r.value(forHTTPHeaderField: "Authorization"))
        let b = body(r)
        XCTAssertEqual((((b["systemInstruction"] as? [String: Any])?["parts"] as? [[String: Any]])?.first)?["text"] as? String, "SYS")
        let contents = (b["contents"] as? [[String: Any]])?.first
        XCTAssertEqual(contents?["role"] as? String, "user"); XCTAssertEqual(((contents?["parts"] as? [[String: Any]])?.first)?["text"] as? String, "USER")
        XCTAssertEqual((b["generationConfig"] as? [String: Any])?["maxOutputTokens"] as? Int, 900)
        XCTAssertNotNil(((b["tools"] as? [[String: Any]])?.first)?["google_search"])
        XCTAssertNil(body(AiClient.buildRequest(setup: s, system: "S", user: "U", maxTokens: 1))["tools"])
        XCTAssertEqual(AiClient.buildModelsRequest(setup: s).url?.absoluteString, "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
    }

    func testOpenAiShapedRequests() throws {
        // OpenAI: chat completions, or the Responses API when it searches.
        let openai = try AiClient.setup(prefs: aiPrefs("openai", key: "sk-o"))
        let chat = AiClient.buildRequest(setup: openai, system: "SYS", user: "USER", maxTokens: 300)
        XCTAssertEqual(chat.url?.absoluteString, "https://api.openai.com/v1/chat/completions")
        XCTAssertEqual(chat.value(forHTTPHeaderField: "Authorization"), "Bearer sk-o")
        XCTAssertEqual(body(chat)["max_completion_tokens"] as? Int, 300); XCTAssertNil(body(chat)["max_tokens"]); XCTAssertEqual(body(chat)["model"] as? String, "gpt-5-mini")
        let messages = body(chat)["messages"] as? [[String: Any]]
        XCTAssertEqual(messages?.map { $0["role"] as? String }, ["system", "user"]); XCTAssertEqual(messages?.map { $0["content"] as? String }, ["SYS", "USER"])
        let responses = AiClient.buildRequest(setup: openai, system: "SYS", user: "USER", maxTokens: 300, webSearch: true)
        XCTAssertEqual(responses.url?.absoluteString, "https://api.openai.com/v1/responses")
        let rb = body(responses)
        XCTAssertEqual(rb["instructions"] as? String, "SYS"); XCTAssertEqual(rb["input"] as? String, "USER"); XCTAssertEqual(rb["max_output_tokens"] as? Int, 300)
        XCTAssertEqual(((rb["tools"] as? [[String: Any]])?.first)?["type"] as? String, "web_search")

        // The others: max_tokens, their own roots, and no search of their own.
        let roots = ["mistral": "https://api.mistral.ai/v1", "groq": "https://api.groq.com/openai/v1", "deepseek": "https://api.deepseek.com/v1"]
        for (id, root) in roots {
            let s = try AiClient.setup(prefs: aiPrefs(id, key: "key-\(id)"))
            let r = AiClient.buildRequest(setup: s, system: "S", user: "U", maxTokens: 50, webSearch: true)
            XCTAssertEqual(r.url?.absoluteString, "\(root)/chat/completions", id)
            XCTAssertEqual(r.value(forHTTPHeaderField: "Authorization"), "Bearer key-\(id)")
            XCTAssertEqual(body(r)["max_tokens"] as? Int, 50); XCTAssertNil(body(r)["max_completion_tokens"]); XCTAssertNil(body(r)["plugins"]); XCTAssertNil(body(r)["tools"])
            XCTAssertNil(r.value(forHTTPHeaderField: "X-Title"))
            XCTAssertEqual(body(r)["model"] as? String, AiClient.provider(id).defaultModel)
            XCTAssertEqual(AiClient.buildModelsRequest(setup: s).url?.absoluteString, "\(root)/models")
        }

        // OpenRouter says who is calling, and searches with its web plugin.
        let router = try AiClient.setup(prefs: aiPrefs("openrouter", key: "or-key"))
        let r = AiClient.buildRequest(setup: router, system: "S", user: "U", maxTokens: 50, webSearch: true)
        XCTAssertEqual(r.url?.absoluteString, "https://openrouter.ai/api/v1/chat/completions")
        XCTAssertEqual(r.value(forHTTPHeaderField: "X-Title"), "Ghost Keyboard"); XCTAssertEqual(r.value(forHTTPHeaderField: "HTTP-Referer"), "https://ghostkbd.com/")
        XCTAssertEqual(((body(r)["plugins"] as? [[String: Any]])?.first)?["id"] as? String, "web"); XCTAssertEqual(body(r)["model"] as? String, "openrouter/auto")
        XCTAssertNil(body(AiClient.buildRequest(setup: router, system: "S", user: "U", maxTokens: 50))["plugins"])

        // A custom server: the person's root and model, their key where they gave one.
        let custom = try AiClient.setup(prefs: aiPrefs("custom", key: "tok", model: "llama3", base: "https://llm.example.com/v1/"))
        let c = AiClient.buildRequest(setup: custom, system: "S", user: "U", maxTokens: 50, webSearch: true)
        XCTAssertEqual(c.url?.absoluteString, "https://llm.example.com/v1/chat/completions"); XCTAssertEqual(c.value(forHTTPHeaderField: "Authorization"), "Bearer tok")
        XCTAssertEqual(body(c)["max_tokens"] as? Int, 50); XCTAssertNil(body(c)["plugins"])
    }

    private func parse(_ id: String, _ json: String, _ status: Int = 200) -> Result<String, AiClient.AiError> {
        AiClient.parseResponse(provider: AiClient.provider(id), data: Data(json.utf8), status: status)
    }

    func testParseAnswers() {
        XCTAssertEqual(parse("anthropic", #"{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":"hm"},{"type":"text","text":" Hello "},{"type":"text","text":"there"}]}"#), .success("Hello there"))
        // Only the text after the last search is the answer.
        XCTAssertEqual(parse("anthropic", #"{"stop_reason":"end_turn","content":[{"type":"text","text":"Let me look."},{"type":"server_tool_use","id":"1"},{"type":"web_search_tool_result"},{"type":"text","text":"It was 3-1."}]}"#), .success("It was 3-1."))
        XCTAssertEqual(parse("anthropic", #"{"stop_reason":"refusal","content":[]}"#), .failure(AiClient.AiError("Claude declined this one")))
        XCTAssertEqual(parse("anthropic", #"{"stop_reason":"pause_turn","content":[{"type":"server_tool_use"}]}"#), .failure(AiClient.AiError("The search took too long")))
        XCTAssertEqual(parse("anthropic", #"{"stop_reason":"end_turn","content":[]}"#), .failure(AiClient.AiError("The AI sent back nothing")))

        XCTAssertEqual(parse("gemini", #"{"candidates":[{"content":{"parts":[{"text":"Hola"},{"text":" mundo"}]}}]}"#), .success("Hola mundo"))
        XCTAssertEqual(parse("gemini", #"{"promptFeedback":{"blockReason":"SAFETY"}}"#), .failure(AiClient.AiError("Gemini blocked it (SAFETY)")))
        XCTAssertEqual(parse("gemini", #"{}"#), .failure(AiClient.AiError("Gemini sent back nothing")))

        XCTAssertEqual(parse("openai", #"{"choices":[{"message":{"role":"assistant","content":"Hi!"}}]}"#), .success("Hi!"))
        XCTAssertEqual(parse("groq", #"{"choices":[{"message":{"content":"  yo  "}}]}"#), .success("yo"))
        XCTAssertEqual(parse("openai", #"{"choices":[]}"#), .failure(AiClient.AiError("The AI sent back nothing")))
        // The Responses API.
        XCTAssertEqual(parse("openai", #"{"output_text":"Direct"}"#), .success("Direct"))
        XCTAssertEqual(parse("openai", #"{"output":[{"type":"web_search_call"},{"type":"message","content":[{"type":"output_text","text":"Found"},{"type":"output_text","text":" it"}]}]}"#), .success("Found it"))
        XCTAssertEqual(parse("openai", "<html>"), .failure(AiClient.AiError("The AI's answer wasn't readable")))
    }

    func testParseErrors() {
        XCTAssertEqual(parse("anthropic", #"{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"#, 401), .failure(AiClient.AiError("The key was refused: invalid x-api-key")))
        XCTAssertEqual(parse("openai", #"{"error":{"message":"The model `gpt-9` does not exist"}}"#, 404), .failure(AiClient.AiError("That model isn't available: The model `gpt-9` does not exist")))
        XCTAssertEqual(parse("groq", #"{"message":"slow down"}"#, 429), .failure(AiClient.AiError("Too many requests, or out of credit: slow down")))
        XCTAssertEqual(parse("gemini", "upstream exploded", 503), .failure(AiClient.AiError("The AI's server had a problem")))
        XCTAssertEqual(parse("mistral", "{}", 418), .failure(AiClient.AiError("The AI said no (418)")))
        XCTAssertEqual(parse("openai", "", 403), .failure(AiClient.AiError("The key was refused")))
        let long = String(repeating: "x", count: 400)
        XCTAssertEqual(AiClient.errorMessage(400, "{\"error\":{\"message\":\"\(long)\"}}"), "The AI said no (400): " + String(repeating: "x", count: 160))
    }

    func testParseModels() {
        func models(_ id: String, _ json: String) -> [String]? { try? AiClient.parseModels(provider: AiClient.provider(id), data: Data(json.utf8), status: 200).get() }
        XCTAssertEqual(models("anthropic", #"{"data":[{"id":"claude-b"},{"id":"claude-a"},{"id":""}]}"#), ["claude-b", "claude-a"])
        XCTAssertEqual(models("openai", #"{"data":[{"id":"gpt-b"},{"id":"gpt-a"}]}"#), ["gpt-a", "gpt-b"])
        XCTAssertEqual(models("gemini", #"{"models":[{"name":"models/gemini-x","supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]}]}"#), ["gemini-x"])
        XCTAssertEqual(AiClient.parseModels(provider: AiClient.provider("openai"), data: Data(), status: 401), .failure(AiClient.AiError("The key was refused")))
    }

    func testCompleteOverASession() throws {
        let session = StubProtocol.session()
        StubProtocol.reset([(200, #"{"choices":[{"message":{"content":"  Done.  "}}]}"#)])
        let prefs = aiPrefs("custom", key: "", model: "stub", base: "http://127.0.0.1:9/v1")
        XCTAssertEqual(try AiClient.complete(prefs: prefs, system: "S", user: "U", maxTokens: 5, session: session), "Done.")
        XCTAssertEqual(StubProtocol.requests.first?.url?.absoluteString, "http://127.0.0.1:9/v1/chat/completions")
        XCTAssertEqual((Json.object(StubProtocol.bodies[0])?["messages"] as? [[String: Any]])?.last?["content"] as? String, "U")

        // A Claude search that pauses is sent back and carries on.
        StubProtocol.reset([
            (200, #"{"stop_reason":"pause_turn","content":[{"type":"server_tool_use","id":"s1"}]}"#),
            (200, #"{"stop_reason":"end_turn","content":[{"type":"text","text":"Answer"}]}"#),
        ])
        XCTAssertEqual(try AiClient.complete(prefs: aiPrefs("anthropic"), system: "S", user: "U", maxTokens: 5, webSearch: true, session: session), "Answer")
        XCTAssertEqual(StubProtocol.requests.count, 2)
        XCTAssertEqual((Json.object(StubProtocol.bodies[1])?["messages"] as? [[String: Any]])?.count, 2)
        StubProtocol.reset(Array(repeating: (200, #"{"stop_reason":"pause_turn","content":[]}"#), count: 6))
        XCTAssertThrowsError(try AiClient.complete(prefs: aiPrefs("anthropic"), system: "S", user: "U", maxTokens: 5, session: session)) {
            XCTAssertEqual(($0 as? AiClient.AiError)?.message, "The search took too long")
        }
        XCTAssertEqual(StubProtocol.requests.count, 4)

        StubProtocol.reset([(401, #"{"error":{"message":"bad key"}}"#)])
        XCTAssertThrowsError(try AiClient.complete(prefs: aiPrefs("openai"), system: "S", user: "U", maxTokens: 5, session: session)) {
            XCTAssertEqual(($0 as? AiClient.AiError)?.message, "The key was refused: bad key")
        }
        XCTAssertThrowsError(try AiClient.complete(prefs: aiPrefs("openai", key: ""), system: "S", user: "U", maxTokens: 5, session: session)) {
            XCTAssertEqual(($0 as? AiClient.AiError)?.message, "Add your OpenAI key in Settings › AI")
        }

        StubProtocol.reset([(200, #"{"data":[{"id":"b"},{"id":"a"}]}"#)])
        XCTAssertEqual(try AiClient.models(prefs: aiPrefs("mistral"), session: session), ["a", "b"])

        let done = expectation(description: "async")
        StubProtocol.reset([(200, #"{"choices":[{"message":{"content":"async"}}]}"#)])
        AiClient.completeAsync(prefs: aiPrefs("openai"), system: "S", user: "U", maxTokens: 5, session: session) { result in
            XCTAssertEqual(result, .success("async")); XCTAssertTrue(Thread.isMainThread); done.fulfill()
        }
        wait(for: [done], timeout: 10)
    }
}

final class RewriteTests: XCTestCase {
    func testModesAndLanguage() {
        let (prefs, _, _) = makePrefs()
        XCTAssertEqual(Rewrite.modes(prefs).map { $0.id }, ["fix", "shorter", "formal", "casual", "friendly", "translate"])
        XCTAssertEqual(Rewrite.mode(prefs, id: "translate")?.label, "→ Spanish")
        XCTAssertNil(Rewrite.mode(prefs, id: "nope")); XCTAssertNil(Rewrite.mode(prefs, id: nil))
        prefs.set(key: KeyboardPrefs.rewriteLanguage, value: "  Deutsch!! <script>  ")
        XCTAssertEqual(Rewrite.language(prefs), "Deutsch script")
        prefs.set(key: KeyboardPrefs.rewriteLanguage, value: "123")
        XCTAssertEqual(Rewrite.language(prefs), "Spanish")
        let system = Rewrite.systemPrompt(Rewrite.mode(prefs, id: "fix")!)
        XCTAssertTrue(system.hasPrefix("You rewrite text inside a phone keyboard."))
        XCTAssertTrue(system.hasSuffix("WHAT TO DO: Fix the spelling, grammar and punctuation. Change nothing else: keep the wording, the tone and the length."))
        XCTAssertTrue(system.contains("links\n  and anything that is code as they are."))
    }

    func testRunOverASession() {
        let session = StubProtocol.session()
        let prefs = aiPrefs("openai")
        let mode = Rewrite.mode(prefs, id: "shorter")!
        XCTAssertThrowsError(try Rewrite.run(prefs: prefs, mode: mode, text: String(repeating: "a", count: 6001), session: session)) {
            XCTAssertEqual(($0 as? AiClient.AiError)?.message, "That's too long to rewrite at once: select a part of it")
        }
        StubProtocol.reset([(200, #"{"choices":[{"message":{"content":"Short."}}]}"#)])
        XCTAssertEqual(try Rewrite.run(prefs: prefs, mode: mode, text: "A long sentence.", session: session), "Short.")
        let sent = Json.object(StubProtocol.bodies[0])
        XCTAssertEqual(sent?["max_completion_tokens"] as? Int, 4000)
        XCTAssertEqual((sent?["messages"] as? [[String: Any]])?.last?["content"] as? String, "A long sentence.")
    }
}

final class GitHubCommitsTests: XCTestCase {
    func testKeywords() {
        XCTAssertEqual(GitHubCommits.keywords("summarize the posthog updates we made this week"), ["posthog"])
        XCTAssertEqual(GitHubCommits.keywords("Why do the PostHog metrics not fire on waitlist signup?"), ["posthog", "metrics", "fire", "waitlist"])
        XCTAssertEqual(GitHubCommits.keywords("ghost-kbd 0.9.0 release notes, 2026"), ["ghost-kbd", "0.9.0"])
        XCTAssertEqual(GitHubCommits.keywords("standup tldr"), [])
        XCTAssertEqual(GitHubCommits.keywords("api api API."), ["api"])
    }

    func testSince() {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        // Wednesday 11 November 2026.
        let today = utc.date(from: DateComponents(year: 2026, month: 11, day: 11, hour: 12))!
        func since(_ r: String) -> String? { GitHubCommits.since(r, today: today, calendar: utc) }
        XCTAssertEqual(since("what did we ship today"), "2026-11-11")
        XCTAssertEqual(since("Yesterday's work"), "2026-11-10")
        XCTAssertEqual(since("updates this week"), "2026-11-09")
        XCTAssertEqual(since("last week"), "2026-10-28")
        XCTAssertEqual(since("this month"), "2026-11-01")
        XCTAssertEqual(since("past month"), "2026-09-11")
        XCTAssertNil(since("posthog updates"))
        let sunday = utc.date(from: DateComponents(year: 2026, month: 11, day: 15, hour: 12))!
        XCTAssertEqual(GitHubCommits.since("this week", today: sunday, calendar: utc), "2026-11-09")
    }

    func testRequestsAndParsing() {
        let url = GitHubCommits.searchURL(word: "posthog", owner: "octo", isUser: true, since: "2026-11-09")
        XCTAssertEqual(url.absoluteString, "https://api.github.com/search/commits?q=posthog+user%3Aocto+committer-date%3A%3E%3D2026-11-09&sort=committer-date&order=desc&per_page=20")
        XCTAssertEqual(GitHubCommits.searchURL(word: "c++", owner: "acme", isUser: false, since: nil).absoluteString,
                       "https://api.github.com/search/commits?q=c%2B%2B+org%3Aacme&sort=committer-date&order=desc&per_page=20")
        let r = GitHubCommits.buildRequest(url: url, token: "ghp_x")
        XCTAssertEqual(r.value(forHTTPHeaderField: "Authorization"), "Bearer ghp_x"); XCTAssertEqual(r.value(forHTTPHeaderField: "Accept"), "application/vnd.github+json")
        XCTAssertEqual(r.value(forHTTPHeaderField: "X-GitHub-Api-Version"), "2022-11-28"); XCTAssertEqual(r.value(forHTTPHeaderField: "User-Agent"), "GhostKeyboard")

        let json = #"{"items":[{"sha":"a1","commit":{"message":"Fix posthog events\n\nCo-Authored-By: Bot <b@x>\nClaude-Session: 1\nMore.","committer":{"date":"2026-11-08T10:00:00Z"}},"repository":{"full_name":"octo/app"}},{"sha":"b2"}]}"#
        let parsed = GitHubCommits.parseSearch(Data(json.utf8))
        XCTAssertEqual(parsed.count, 1); XCTAssertEqual(parsed[0].sha, "a1")
        XCTAssertEqual(parsed[0].commit, GitHubCommits.Commit(repo: "octo/app", date: "2026-11-08", message: "Fix posthog events\n\nMore."))
        XCTAssertTrue(GitHubCommits.parseSearch(Data("nope".utf8)).isEmpty)

        XCTAssertEqual(GitHubCommits.errorMessage(status: 401, body: #"{"message":"Bad credentials"}"#), "GitHub refused the token")
        XCTAssertEqual(GitHubCommits.errorMessage(status: 403, body: #"{"message":"rate limit"}"#), "GitHub's search limit: try again in a minute: rate limit")
        XCTAssertEqual(GitHubCommits.errorMessage(status: 422, body: ""), "GitHub said no (422)")
    }

    func testRank() {
        func c(_ d: String, _ m: String) -> GitHubCommits.Commit { GitHubCommits.Commit(repo: "r", date: d, message: m) }
        let hits = [("a", c("2026-11-01", "one")), ("b", c("2026-11-05", "two")), ("a", c("2026-11-01", "one")), ("c", c("2026-11-03", "three"))].map { (sha: $0.0, commit: $0.1) }
        XCTAssertEqual(GitHubCommits.rank(hits).map { $0.message }, ["two", "three", "one"])
        // A tight budget keeps the commit found for more words, then the newest.
        XCTAssertEqual(GitHubCommits.rank(hits, budget: 50).map { $0.message }, ["two", "one"])
        XCTAssertEqual(GitHubCommits.rank(hits, budget: 1).map { $0.message }, ["one"])
    }

    func testSearchOverASession() throws {
        let session = StubProtocol.session()
        let store = MemoryPrefsStore()
        let github = GitHubCommits(store: store, session: session)
        let item = #"{"items":[{"sha":"a1","commit":{"message":"Add posthog","committer":{"date":"2026-11-08T10:00:00Z"}},"repository":{"full_name":"octo/app"}}]}"#
        StubProtocol.reset([(200, #"{"login":"octo"}"#), (200, #"[{"login":"acme"}]"#), (200, item), (200, #"{"items":[]}"#)])
        let found = try github.search(token: "ghp_x", request: "posthog updates")
        XCTAssertEqual(found.map { $0.message }, ["Add posthog"])
        XCTAssertEqual(StubProtocol.requests.map { $0.url!.path }, ["/user", "/user/orgs", "/search/commits", "/search/commits"])
        XCTAssertTrue(StubProtocol.requests[2].url!.absoluteString.contains("q=posthog+user%3Aocto&")); XCTAssertTrue(StubProtocol.requests[3].url!.absoluteString.contains("q=posthog+org%3Aacme&"))
        // The account is cached under a hash of the token, never the token.
        XCTAssertEqual(store.object(forKey: "github_cache_owners") as? String, "octo,acme")
        XCTAssertEqual(store.object(forKey: "github_cache_token") as? String, String("ghp_x".javaHashCode))
        StubProtocol.reset([])
        XCTAssertEqual(try github.account(token: "ghp_x"), GitHubCommits.Account(login: "octo", owners: ["octo", "acme"]))
        XCTAssertTrue(StubProtocol.requests.isEmpty)
        StubProtocol.reset([(401, #"{"message":"Bad credentials"}"#)])
        XCTAssertThrowsError(try github.account(token: "other")) { XCTAssertEqual(($0 as? GitHubCommits.GitHubError)?.message, "GitHub refused the token") }
        XCTAssertTrue(try github.search(token: "ghp_x", request: "standup tldr").isEmpty)
    }
}

final class AppContextTests: XCTestCase {
    func testKinds() {
        XCTAssertEqual(AppContext.kind(bundleId: nil), .other)
        XCTAssertEqual(AppContext.kind(bundleId: "com.hammerandchisel.discord"), .chat)
        XCTAssertEqual(AppContext.kind(bundleId: "md.obsidian"), .markdown)
        XCTAssertEqual(AppContext.kind(bundleId: "com.mattssoftware.glyph"), .markdown)
        XCTAssertTrue(AppContext.isGhostMd(bundleId: "com.mattssoftware.glyph")); XCTAssertFalse(AppContext.isGhostMd(bundleId: nil))
        XCTAssertEqual(AppContext.kind(hints: AppContext.Hints()), .other)
        XCTAssertEqual(AppContext.kind(hints: AppContext.Hints(returnKeyIsSend: true)), .chat)
        XCTAssertEqual(AppContext.kind(hints: AppContext.Hints(autocorrectionOff: true, autocapitalisationOff: true)), .terminal)
        XCTAssertEqual(AppContext.kind(hints: AppContext.Hints(fieldType: .email, autocorrectionOff: true, autocapitalisationOff: true)), .other)
        XCTAssertEqual(AppContext.kind(hints: AppContext.Hints(autocorrectionOff: true)), .other)
        XCTAssertEqual(AppContext.kind(hints: AppContext.Hints(returnKeyIsSend: true, secure: true)), .other)
        XCTAssertEqual(AppContext.kind(hints: AppContext.Hints(bundleId: "app.ish.iSH", returnKeyIsSend: true)), .terminal)
        XCTAssertEqual(AppContext.label(hints: AppContext.Hints(returnKeyIsSend: true)), "ios.chat")
        XCTAssertEqual(AppContext.label(hints: AppContext.Hints(bundleId: "md.obsidian")), "md.obsidian")
        XCTAssertTrue(AppContext.isPrivateField(AppContext.Hints(secure: true))); XCTAssertTrue(AppContext.isPrivateField(AppContext.Hints(fieldType: .url)))
        XCTAssertFalse(AppContext.isPrivateField(AppContext.Hints(fieldType: .twitter)))
    }
}
