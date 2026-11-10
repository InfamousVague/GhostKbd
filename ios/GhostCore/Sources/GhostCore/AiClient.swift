import Foundation

/// The AI the Mockingbird, Ghostwriter and Rewrite plugins talk to: the person's own key for one provider, straight
/// from the phone to that provider, nothing in between. One chosen provider (`KeyboardPrefs.aiProvider`); each
/// provider keeps its own key and model (`KeyboardPrefs.aiKey`, `aiModel`), so switching back and forth loses nothing.
///
/// Three wire shapes cover them all: Anthropic's Messages API, Google's Gemini API, and the OpenAI chat shape that
/// OpenAI, Mistral, Groq, DeepSeek, OpenRouter and self-hosted servers (Ollama, LM Studio, vLLM) share.
///
/// `buildRequest` and `parseResponse` are pure; `complete` and `models` block on the network (run them off the main
/// thread) and `completeAsync` does the same on a background queue.
public enum AiClient {
    public enum Wire: String, Hashable { case anthropic, gemini, openai }

    /// `base` is the API root; `defaultModel` is used until the person picks one (Settings lists the provider's own).
    public struct Provider: Hashable {
        public let id: String
        public let name: String
        public let wire: Wire
        public let base: String
        public let defaultModel: String
        public let keyFrom: String

        public init(id: String, name: String, wire: Wire, base: String, defaultModel: String, keyFrom: String) {
            self.id = id; self.name = name; self.wire = wire; self.base = base; self.defaultModel = defaultModel; self.keyFrom = keyFrom
        }
    }

    public static let providers: [Provider] = [
        Provider(id: "anthropic", name: "Claude", wire: .anthropic, base: "https://api.anthropic.com/v1", defaultModel: "claude-opus-5-5", keyFrom: "console.anthropic.com"),
        Provider(id: "openai", name: "OpenAI", wire: .openai, base: "https://api.openai.com/v1", defaultModel: "gpt-5-mini", keyFrom: "platform.openai.com"),
        Provider(id: "gemini", name: "Gemini", wire: .gemini, base: "https://generativelanguage.googleapis.com/v1beta", defaultModel: "gemini-2.5-flash", keyFrom: "aistudio.google.com"),
        Provider(id: "mistral", name: "Mistral", wire: .openai, base: "https://api.mistral.ai/v1", defaultModel: "mistral-small-latest", keyFrom: "console.mistral.ai"),
        Provider(id: "groq", name: "Groq", wire: .openai, base: "https://api.groq.com/openai/v1", defaultModel: "llama-3.3-70b-versatile", keyFrom: "console.groq.com"),
        Provider(id: "deepseek", name: "DeepSeek", wire: .openai, base: "https://api.deepseek.com/v1", defaultModel: "deepseek-chat", keyFrom: "platform.deepseek.com"),
        Provider(id: "openrouter", name: "OpenRouter", wire: .openai, base: "https://openrouter.ai/api/v1", defaultModel: "openrouter/auto", keyFrom: "openrouter.ai/keys"),
        Provider(id: "custom", name: "Custom", wire: .openai, base: "", defaultModel: "", keyFrom: "your server (any OpenAI-compatible https endpoint, e.g. Ollama)"),
    ]

    public static func provider(_ id: String) -> Provider { providers.first { $0.id == id } ?? providers[0] }

    /// What went wrong, as a short sentence to show the person.
    public struct AiError: Error, LocalizedError, Equatable {
        public let message: String
        public init(_ message: String) { self.message = message }
        public var errorDescription: String? { message }
    }

    /// The chosen provider, its key, model and API root.
    public struct Setup: Hashable {
        public let provider: Provider
        public let key: String
        public let model: String
        public let base: String

        public init(provider: Provider, key: String, model: String, base: String) {
            self.provider = provider; self.key = key; self.model = model; self.base = base
        }

        /// The key would travel unencrypted to another machine: plain http to a host that is not this device.
        public var insecure: Bool {
            guard base.lowercased().hasPrefix("http://"), let host = URL(string: base)?.host?.lowercased() else { return false }
            return !(host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]" || host.hasSuffix(".localhost"))
        }
    }

    /// The chosen provider, its key, model and API root, or (thrown, an `AiError`) why it can't be used yet.
    ///
    /// Unlike Android, a custom server's address may be plain `http://` as well as `https://` (a stub server on
    /// 127.0.0.1 in the Simulator, Ollama on the local network): the client does not force https. `Setup.insecure`
    /// says when that sends the key in the clear to another machine, and App Transport Security has the last word.
    public static func setup(prefs: KeyboardPrefs) throws -> Setup {
        let p = provider(prefs.aiProvider)
        var base = (p.id == "custom" ? prefs.aiBaseUrl : p.base).trimmed
        while base.hasSuffix("/") { base.removeLast() }
        let key = prefs.aiKey(p.id).trimmed
        let chosen = prefs.aiModel(p.id).trimmed
        let model = chosen.isEmpty ? p.defaultModel : chosen
        if base.isEmpty { throw AiError("Add your server's address in Settings › AI") }
        let secure = base.hasPrefix("https://")
        let plain = p.id == "custom" && base.hasPrefix("http://")
        if !(secure || plain) || URL(string: base)?.host == nil { throw AiError("The server's address must start with https://") }
        if key.isEmpty && p.id != "custom" { throw AiError("Add your \(p.name) key in Settings › AI") }
        if model.isEmpty { throw AiError("Pick a model in Settings › AI") }
        return Setup(provider: p, key: key, model: model, base: base)
    }

    /// Whether the AI is set up enough to try.
    public static func ready(prefs: KeyboardPrefs) -> Bool { (try? setup(prefs: prefs)) != nil }

    /// Providers whose API can search the web itself: Claude, OpenAI, Gemini and OpenRouter.
    public static let searches: Set<String> = ["anthropic", "openai", "gemini", "openrouter"]

    public static func canSearch(prefs: KeyboardPrefs) -> Bool { searches.contains(provider(prefs.aiProvider).id) }

    // MARK: requests

    /// Models that take the server-side fallback (a declined request is retried on a model suited to it).
    static let fallbackModels: Set<String> = ["claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-opus-5"]

    private static func matches(_ pattern: String, _ text: String) -> Bool {
        text.range(of: pattern, options: .regularExpression) != nil
    }

    static func anthropicHeaders(_ s: Setup, beta: Bool) -> [String: String] {
        var h = ["x-api-key": s.key, "anthropic-version": "2026-10-21"]
        if beta { h["anthropic-beta"] = "server-side-fallback-2026-07-01" }
        return h
    }

    /// Claude's web search: the filtering version on the models that take it, the basic one on the others.
    static func anthropicSearchTool(_ model: String) -> [String: Any] {
        let dynamic = matches("^claude-(opus-5|opus-4-[678]|sonnet-5|sonnet-4-6|fable-5)", model)
        return ["type": dynamic ? "web_search_20260209" : "web_search_20250305", "name": "web_search", "max_uses": 5]
    }

    static func bearer(_ s: Setup) -> [String: String] {
        var h: [String: String] = [:]
        if !s.key.isEmpty { h["Authorization"] = "Bearer \(s.key)" }
        // OpenRouter asks apps to say who they are.
        if s.provider.id == "openrouter" { h["X-Title"] = "Ghost Keyboard"; h["HTTP-Referer"] = "https://attack.fm/devkeyboard/" }
        return h
    }

    private static func request(_ method: String, _ url: String, _ headers: [String: String], _ body: [String: Any]?) -> URLRequest {
        var r = URLRequest(url: URL(string: url) ?? URL(string: "https://invalid.invalid/")!)
        r.httpMethod = method
        // Android's: 15 s to connect, 120 s to answer (a search can take a while).
        r.timeoutInterval = 120
        r.cachePolicy = .reloadIgnoringLocalCacheData
        r.setValue("application/json", forHTTPHeaderField: "Accept")
        for (k, v) in headers { r.setValue(v, forHTTPHeaderField: k) }
        if let body = body {
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = Json.data(body)
        }
        return r
    }

    /// Whether `webSearch` is honoured for this setup (the provider has a search of its own).
    public static func searching(_ setup: Setup, webSearch: Bool) -> Bool { webSearch && searches.contains(setup.provider.id) }

    /// The request for one completion: `system` instructions and the `user` message. `effort` is "low" for quick fills
    /// and "medium" for heavier work; providers that take no such setting ignore it. `webSearch` lets the model look
    /// things up online with its provider's own search, where the provider has one (`searches`).
    ///
    /// `resuming` is for Claude only: the assistant turns of a search that paused (`Step.paused`), sent back so it
    /// carries on.
    public static func buildRequest(setup s: Setup, system: String, user: String, maxTokens: Int, effort: String = "low",
                                    webSearch: Bool = false, resuming: [[Any]] = []) -> URLRequest {
        let search = searching(s, webSearch: webSearch)
        switch s.provider.wire {
        case .anthropic:
            let fallback = fallbackModels.contains(s.model)
            var messages: [[String: Any]] = [["role": "user", "content": user]]
            for content in resuming { messages.append(["role": "assistant", "content": content]) }
            var body: [String: Any] = ["model": s.model, "max_tokens": maxTokens, "system": system, "messages": messages]
            // Effort steers how long the model thinks: low keeps a fill quick. Claude 5-family models take it.
            if matches("^claude-(opus|sonnet|haiku|fable)-5", s.model) { body["output_config"] = ["effort": effort] }
            if fallback { body["fallbacks"] = "default" }
            if search { body["tools"] = [anthropicSearchTool(s.model)] }
            return request("POST", "\(s.base)/messages", anthropicHeaders(s, beta: fallback), body)
        case .gemini:
            var body: [String: Any] = [
                "systemInstruction": ["parts": [["text": system]]],
                "contents": [["role": "user", "parts": [["text": user]]]],
                "generationConfig": ["maxOutputTokens": maxTokens],
            ]
            // Grounding with Google Search: Gemini decides when to look something up.
            if search { body["tools"] = [["google_search": [String: Any]()]] }
            let model = s.model.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? s.model
            return request("POST", "\(s.base)/models/\(model):generateContent", ["x-goog-api-key": s.key], body)
        case .openai:
            if search && s.provider.id == "openai" {
                // OpenAI with web search: its Responses API, where the search tool lives.
                let body: [String: Any] = [
                    "model": s.model, "instructions": system, "input": user, "max_output_tokens": maxTokens,
                    "tools": [["type": "web_search"]],
                ]
                return request("POST", "\(s.base)/responses", bearer(s), body)
            }
            var body: [String: Any] = [
                "model": s.model,
                "messages": [["role": "system", "content": system], ["role": "user", "content": user]],
            ]
            // OpenAI's reasoning models want max_completion_tokens; the others still read max_tokens.
            body[s.provider.id == "openai" ? "max_completion_tokens" : "max_tokens"] = maxTokens
            // OpenRouter's web plugin searches for any model it routes to.
            if search && s.provider.id == "openrouter" { body["plugins"] = [["id": "web"]] }
            return request("POST", "\(s.base)/chat/completions", bearer(s), body)
        }
    }

    /// The request for the models the chosen provider offers this key (Settings' picker).
    public static func buildModelsRequest(setup s: Setup) -> URLRequest {
        switch s.provider.wire {
        case .anthropic: return request("GET", "\(s.base)/models?limit=100", anthropicHeaders(s, beta: false), nil)
        case .gemini: return request("GET", "\(s.base)/models?pageSize=200", ["x-goog-api-key": s.key], nil)
        case .openai: return request("GET", "\(s.base)/models", bearer(s), nil)
        }
    }

    // MARK: answers

    /// One answer from the provider: the text, or (Claude, mid-search) a turn to send back so it carries on.
    public enum Step {
        case text(String)
        case paused(content: [Any])
    }

    private static func json(_ data: Data, _ status: Int) -> Result<[String: Any], AiError> {
        if !(200...299).contains(status) { return .failure(AiError(errorMessage(status, String(decoding: data, as: UTF8.self)))) }
        guard let o = Json.object(data) else { return .failure(AiError("The AI's answer wasn't readable")) }
        return .success(o)
    }

    /// Reads one HTTP answer to a `buildRequest` request. The text is as the provider sent it (not yet trimmed).
    public static func parseStep(provider: Provider, data: Data, status: Int) -> Result<Step, AiError> {
        let o: [String: Any]
        switch json(data, status) {
        case .failure(let e): return .failure(e)
        case .success(let v): o = v
        }
        switch provider.wire {
        case .anthropic:
            let stop = Json.str(o["stop_reason"])
            if stop == "refusal" { return .failure(AiError("Claude declined this one")) }
            let content = (o["content"] as? [Any]) ?? []
            if stop == "pause_turn" { return .success(.paused(content: content)) }
            // Only the text after the last search is the answer; anything before it is Claude narrating its search.
            let blocks = Json.objects(content)
            let lastTool = blocks.lastIndex { let t = Json.str($0["type"]); return t != "text" && t != "thinking" } ?? -1
            let text = blocks[(lastTool + 1)...].filter { Json.str($0["type"]) == "text" }.map { Json.str($0["text"]) }.joined()
            return .success(.text(text))
        case .gemini:
            guard let candidate = (o["candidates"] as? [Any])?.first as? [String: Any] else {
                if let feedback = o["promptFeedback"] as? [String: Any] { return .failure(AiError("Gemini blocked it (\(Json.str(feedback["blockReason"])))")) }
                return .failure(AiError("Gemini sent back nothing"))
            }
            let parts = Json.objects((candidate["content"] as? [String: Any])?["parts"])
            return .success(.text(parts.map { Json.str($0["text"]) }.joined()))
        case .openai:
            // The Responses API (OpenAI with web search) answers in `output`; everything else in `choices`.
            if o["output"] != nil || o["output_text"] != nil {
                let direct = Json.str(o["output_text"])
                if !direct.isBlank { return .success(.text(direct)) }
                let text = Json.objects(o["output"]).filter { Json.str($0["type"]) == "message" }
                    .flatMap { Json.objects($0["content"]) }.filter { Json.str($0["type"]) == "output_text" }
                    .map { Json.str($0["text"]) }.joined()
                return .success(.text(text))
            }
            let message = ((o["choices"] as? [Any])?.first as? [String: Any])?["message"] as? [String: Any]
            return .success(.text(Json.str(message?["content"])))
        }
    }

    /// Reads one HTTP answer through to the text `complete` would return: trimmed, and an error where the provider
    /// sent an error, nothing readable, or nothing at all. (A Claude search that paused reads as "The search took too
    /// long": `complete` is what sends it back to carry on.)
    public static func parseResponse(provider: Provider, data: Data, status: Int) -> Result<String, AiError> {
        switch parseStep(provider: provider, data: data, status: status) {
        case .failure(let e): return .failure(e)
        case .success(.paused): return .failure(AiError("The search took too long"))
        case .success(.text(let raw)):
            let text = raw.trimmed
            return text.isEmpty ? .failure(AiError("The AI sent back nothing")) : .success(text)
        }
    }

    /// Reads the answer to `buildModelsRequest`: the model ids.
    public static func parseModels(provider: Provider, data: Data, status: Int) -> Result<[String], AiError> {
        let o: [String: Any]
        switch json(data, status) {
        case .failure(let e): return .failure(e)
        case .success(let v): o = v
        }
        let ids: [String]
        switch provider.wire {
        case .anthropic:
            ids = Json.objects(o["data"]).map { Json.str($0["id"]) }
        case .gemini:
            ids = Json.objects(o["models"])
                .filter { (($0["supportedGenerationMethods"] as? [Any]) ?? []).contains { Json.str($0) == "generateContent" } }
                .map { name -> String in
                    let n = Json.str(name["name"])
                    return n.hasPrefix("models/") ? String(n.dropFirst("models/".count)) : n
                }
        case .openai:
            ids = Json.objects(o["data"]).map { Json.str($0["id"]) }.sorted()
        }
        return .success(ids.filter { !$0.isBlank })
    }

    /// A provider's error as a short sentence: their message where they send one.
    public static func errorMessage(_ code: Int, _ text: String) -> String {
        var message: String?
        if let o = Json.object(text) {
            let inner = Json.str((o["error"] as? [String: Any])?["message"])
            let outer = Json.str(o["message"])
            message = !inner.isBlank ? inner : (!outer.isBlank ? outer : nil)
        }
        let lead: String
        switch code {
        case 401, 403: lead = "The key was refused"
        case 404: lead = "That model isn't available"
        case 429: lead = "Too many requests, or out of credit"
        case 500...599: lead = "The AI's server had a problem"
        default: lead = "The AI said no (\(code))"
        }
        if let m = message, !m.isBlank { return "\(lead): \(m.take(160))" }
        return lead
    }

    // MARK: the network

    private static func send(_ request: URLRequest, _ session: URLSession) throws -> Http.Response {
        switch Http.send(request, session: session) {
        case .success(let r): return r
        case .failure(let e): throw AiError("Couldn't reach the AI: \(e.localizedDescription.isEmpty ? "no connection" : e.localizedDescription)")
        }
    }

    /// One request: `system` instructions and the `user` message, back as plain text; throws an `AiError`.
    /// Blocking: run it off the main thread (or use `completeAsync`).
    public static func complete(prefs: KeyboardPrefs, system: String, user: String, maxTokens: Int, effort: String = "low",
                                webSearch: Bool = false, session: URLSession = .shared) throws -> String {
        let s = try setup(prefs: prefs)
        var resuming: [[Any]] = []
        // A long search can pause the turn: send the conversation back as it stands and Claude carries on.
        for _ in 0..<4 {
            let request = buildRequest(setup: s, system: system, user: user, maxTokens: maxTokens, effort: effort, webSearch: webSearch, resuming: resuming)
            let response = try send(request, session)
            switch try parseStep(provider: s.provider, data: response.data, status: response.status).get() {
            case .paused(let content): resuming.append(content)
            case .text(let raw):
                let text = raw.trimmed
                if text.isEmpty { throw AiError("The AI sent back nothing") }
                return text
            }
        }
        throw AiError("The search took too long")
    }

    /// `complete` on a background queue; `completion` runs on `queue` (the main queue unless told otherwise).
    public static func completeAsync(prefs: KeyboardPrefs, system: String, user: String, maxTokens: Int, effort: String = "low",
                                     webSearch: Bool = false, session: URLSession = .shared, queue: DispatchQueue = .main,
                                     completion: @escaping (Result<String, AiError>) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let result: Result<String, AiError>
            do { result = .success(try complete(prefs: prefs, system: system, user: user, maxTokens: maxTokens, effort: effort, webSearch: webSearch, session: session)) }
            catch let e as AiError { result = .failure(e) }
            catch { result = .failure(AiError(error.localizedDescription)) }
            queue.async { completion(result) }
        }
    }

    /// The models the chosen provider offers this key, for Settings' picker. Blocking; throws an `AiError`.
    public static func models(prefs: KeyboardPrefs, session: URLSession = .shared) throws -> [String] {
        let s = try setup(prefs: prefs)
        let response = try send(buildModelsRequest(setup: s), session)
        return try parseModels(provider: s.provider, data: response.data, status: response.status).get()
    }

    /// `models` on a background queue; `completion` runs on `queue`.
    public static func modelsAsync(prefs: KeyboardPrefs, session: URLSession = .shared, queue: DispatchQueue = .main,
                                   completion: @escaping (Result<[String], AiError>) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            let result: Result<[String], AiError>
            do { result = .success(try models(prefs: prefs, session: session)) }
            catch let e as AiError { result = .failure(e) }
            catch { result = .failure(AiError(error.localizedDescription)) }
            queue.async { completion(result) }
        }
    }
}
