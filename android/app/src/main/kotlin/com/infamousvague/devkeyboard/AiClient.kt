package com.infamousvague.devkeyboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * The AI the Mockingbird and Ghostwriter plugins talk to: the person's own key for one provider, straight from the
 * phone to that provider, nothing in between. One chosen provider ([KeyboardPrefs.AI_PROVIDER]); each provider keeps
 * its own key and model ([KeyboardPrefs.aiKey], [KeyboardPrefs.aiModel]), so switching back and forth loses nothing.
 *
 * Three wire shapes cover them all: Anthropic's Messages API, Google's Gemini API, and the OpenAI chat shape that
 * OpenAI, Mistral, Groq, DeepSeek, OpenRouter and self-hosted servers (Ollama, LM Studio, vLLM behind https) share.
 * Blocking calls: run them off the main thread. Every request goes through [Net], under the ledger path its caller
 * names (Ghostwriter, Rewrite, training…), so the Offline switch stops them all and a key only ever goes to the
 * provider it was made for.
 */
object AiClient {
    enum class Wire { ANTHROPIC, GEMINI, OPENAI }

    /** [base] is the API root; [defaultModel] is used until the person picks one (Settings lists the provider's own). */
    data class Provider(val id: String, val name: String, val wire: Wire, val base: String, val defaultModel: String, val keyFrom: String)

    val PROVIDERS = listOf(
        Provider("anthropic", "Claude", Wire.ANTHROPIC, "https://api.anthropic.com/v1", "claude-opus-5-5", "console.anthropic.com"),
        Provider("openai", "OpenAI", Wire.OPENAI, "https://api.openai.com/v1", "gpt-5-mini", "platform.openai.com"),
        Provider("gemini", "Gemini", Wire.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", "aistudio.google.com"),
        Provider("mistral", "Mistral", Wire.OPENAI, "https://api.mistral.ai/v1", "mistral-small-latest", "console.mistral.ai"),
        Provider("groq", "Groq", Wire.OPENAI, "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile", "console.groq.com"),
        Provider("deepseek", "DeepSeek", Wire.OPENAI, "https://api.deepseek.com/v1", "deepseek-chat", "platform.deepseek.com"),
        Provider("openrouter", "OpenRouter", Wire.OPENAI, "https://openrouter.ai/api/v1", "openrouter/auto", "openrouter.ai/keys"),
        Provider("custom", "Custom", Wire.OPENAI, "", "", "your server (any OpenAI-compatible https endpoint, e.g. Ollama)"),
    )

    fun provider(id: String) = PROVIDERS.firstOrNull { it.id == id } ?: PROVIDERS[0]

    class AiError(message: String) : Exception(message)

    /** The chosen provider, its key, model and API root, or why it can't be used yet. */
    data class Setup(val provider: Provider, val key: String, val model: String, val base: String)

    fun setup(prefs: KeyboardPrefs): Setup {
        val p = provider(prefs.aiProvider)
        val base = (if (p.id == "custom") prefs.aiBaseUrl else p.base).trim().trimEnd('/')
        val key = prefs.aiKey(p.id).trim()
        val model = prefs.aiModel(p.id).trim().ifEmpty { p.defaultModel }
        if (base.isEmpty()) throw AiError("Add your server's address in Settings › AI")
        if (!base.startsWith("https://")) throw AiError("The server's address must start with https://")
        if (key.isEmpty() && p.id != "custom") throw AiError("Add your ${p.name} key in Settings › AI")
        if (model.isEmpty()) throw AiError("Pick a model in Settings › AI")
        return Setup(p, key, model, base)
    }

    /** Whether the AI is set up enough to try. */
    fun ready(prefs: KeyboardPrefs) = runCatching { setup(prefs) }.isSuccess

    /** Providers whose API can search the web itself: Claude, OpenAI, Gemini and OpenRouter. */
    val SEARCHES = setOf("anthropic", "openai", "gemini", "openrouter")

    fun canSearch(prefs: KeyboardPrefs) = provider(prefs.aiProvider).id in SEARCHES

    /** Whose request this is: the settings the guard reads, and the ledger path it is made under. */
    private class Via(val prefs: KeyboardPrefs, val path: NetPath)

    /**
     * One request, made for [path] (which feature is asking: the ledger's GHOSTWRITER, REWRITE, TRAIN…): [system]
     * instructions and the [user] message, back as plain text. [effort] is "low" for quick fills and "medium" for
     * heavier work; providers that take no such setting ignore it. [webSearch] lets the model look things up online
     * with its provider's own search, where the provider has one ([SEARCHES]). With Offline on it fails at once with
     * "Offline is on".
     */
    fun complete(prefs: KeyboardPrefs, path: NetPath, system: String, user: String, maxTokens: Int, effort: String = "low", webSearch: Boolean = false): String {
        if (Net.offline(prefs)) throw AiError(Net.OFFLINE_MESSAGE)
        val s = setup(prefs)
        val via = Via(prefs, path)
        val search = webSearch && s.provider.id in SEARCHES
        return when (s.provider.wire) {
            Wire.ANTHROPIC -> anthropic(via, s, system, user, maxTokens, effort, search)
            Wire.GEMINI -> gemini(via, s, system, user, maxTokens, search)
            Wire.OPENAI -> if (search && s.provider.id == "openai") openAiResponses(via, s, system, user, maxTokens) else openAi(via, s, system, user, maxTokens, search)
        }.trim().ifEmpty { throw AiError("The AI sent back nothing") }
    }

    /** The models the chosen provider offers this key, for Settings' picker. */
    fun models(prefs: KeyboardPrefs): List<String> {
        if (Net.offline(prefs)) throw AiError(Net.OFFLINE_MESSAGE)
        val s = setup(prefs)
        val via = Via(prefs, NetPath.AI_MODELS)
        return when (s.provider.wire) {
            Wire.ANTHROPIC -> {
                val o = request(via, "GET", "${s.base}/models?limit=100", anthropicHeaders(s, beta = false), null)
                o.optJSONArray("data").objects().map { it.optString("id") }
            }
            Wire.GEMINI -> {
                val o = request(via, "GET", "${s.base}/models?pageSize=200", mapOf("x-goog-api-key" to s.key), null)
                o.optJSONArray("models").objects()
                    .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.optString(it) == "generateContent" } } == true }
                    .map { it.optString("name").removePrefix("models/") }
            }
            Wire.OPENAI -> {
                val o = request(via, "GET", "${s.base}/models", bearer(s), null)
                o.optJSONArray("data").objects().map { it.optString("id") }.sorted()
            }
        }.filter { it.isNotBlank() }
    }

    // --- Anthropic ----------------------------------------------------------------------------------------------------

    /** Models that take the server-side fallback (a declined request is retried on a model suited to it). */
    private val FALLBACK_MODELS = setOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-fable-5-1", "claude-opus-5")

    private fun anthropicHeaders(s: Setup, beta: Boolean) = buildMap {
        put("x-api-key", s.key)
        put("anthropic-version", "2026-10-21")
        if (beta) put("anthropic-beta", "server-side-fallback-2026-07-01")
    }

    /** Claude's web search: the filtering version on the models that take it, the basic one on the others. */
    private fun anthropicSearchTool(model: String): JSONObject {
        val dynamic = Regex("^claude-(opus-5|opus-4-[678]|sonnet-5|sonnet-4-6|fable-5)").containsMatchIn(model)
        return JSONObject().put("type", if (dynamic) "web_search_20260209" else "web_search_20250305").put("name", "web_search").put("max_uses", 5)
    }

    private fun anthropic(via: Via, s: Setup, system: String, user: String, maxTokens: Int, effort: String, search: Boolean): String {
        val fallback = s.model in FALLBACK_MODELS
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject()
            .put("model", s.model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", messages)
        // Effort steers how long the model thinks: low keeps a fill quick. Claude 5-family models take it.
        if (Regex("^claude-(opus|sonnet|haiku|fable)-5").containsMatchIn(s.model)) body.put("output_config", JSONObject().put("effort", effort))
        if (fallback) body.put("fallbacks", "default")
        if (search) body.put("tools", JSONArray().put(anthropicSearchTool(s.model)))
        val text = StringBuilder()
        // A long search can pause the turn: send the conversation back as it stands and Claude carries on.
        repeat(4) {
            val o = request(via, "POST", "${s.base}/messages", anthropicHeaders(s, fallback), body)
            if (o.optString("stop_reason") == "refusal") throw AiError("Claude declined this one")
            val content = o.optJSONArray("content") ?: JSONArray()
            if (o.optString("stop_reason") != "pause_turn") {
                // Only the text after the last search is the answer; anything before it is Claude narrating its search.
                val blocks = content.objects()
                val lastTool = blocks.indexOfLast { it.optString("type") != "text" && it.optString("type") != "thinking" }
                blocks.drop(lastTool + 1).filter { it.optString("type") == "text" }.forEach { text.append(it.optString("text")) }
                return text.toString()
            }
            messages.put(JSONObject().put("role", "assistant").put("content", content))
        }
        throw AiError("The search took too long")
    }

    // --- Gemini -------------------------------------------------------------------------------------------------------

    private fun gemini(via: Via, s: Setup, system: String, user: String, maxTokens: Int, search: Boolean): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        // Grounding with Google Search: Gemini decides when to look something up.
        if (search) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val o = request(via, "POST", "${s.base}/models/${s.model}:generateContent", mapOf("x-goog-api-key" to s.key), body)
        val candidate = o.optJSONArray("candidates")?.optJSONObject(0) ?: throw AiError(o.optJSONObject("promptFeedback")?.optString("blockReason")?.let { "Gemini blocked it ($it)" } ?: "Gemini sent back nothing")
        return candidate.optJSONObject("content")?.optJSONArray("parts").objects().joinToString("") { it.optString("text") }
    }

    // --- OpenAI and the servers that speak its shape -------------------------------------------------------------------

    private fun bearer(s: Setup) = buildMap {
        if (s.key.isNotEmpty()) put("Authorization", "Bearer ${s.key}")
        // OpenRouter asks apps to say who they are.
        if (s.provider.id == "openrouter") { put("X-Title", "Ghost Keyboard"); put("HTTP-Referer", "https://ghostkbd.com/") }
    }

    /** OpenAI with web search: its Responses API, where the search tool lives. */
    private fun openAiResponses(via: Via, s: Setup, system: String, user: String, maxTokens: Int): String {
        val body = JSONObject()
            .put("model", s.model)
            .put("instructions", system)
            .put("input", user)
            .put("max_output_tokens", maxTokens)
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        val o = request(via, "POST", "${s.base}/responses", bearer(s), body)
        o.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        return o.optJSONArray("output").objects().filter { it.optString("type") == "message" }
            .flatMap { it.optJSONArray("content").objects() }.filter { it.optString("type") == "output_text" }
            .joinToString("") { it.optString("text") }
    }

    private fun openAi(via: Via, s: Setup, system: String, user: String, maxTokens: Int, search: Boolean = false): String {
        val body = JSONObject()
            .put("model", s.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        // OpenAI's reasoning models want max_completion_tokens; the others still read max_tokens.
        body.put(if (s.provider.id == "openai") "max_completion_tokens" else "max_tokens", maxTokens)
        // OpenRouter's web plugin searches for any model it routes to.
        if (search && s.provider.id == "openrouter") body.put("plugins", JSONArray().put(JSONObject().put("id", "web")))
        val o = request(via, "POST", "${s.base}/chat/completions", bearer(s), body)
        return o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
    }

    // --- HTTP ---------------------------------------------------------------------------------------------------------

    /**
     * One call to the provider, through the guard. A redirect is an error like any other answer that is not a 2xx: the
     * key is never sent on to another server.
     */
    private fun request(via: Via, method: String, url: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
        val c = try {
            Net.open(via.prefs, via.path, url)
        } catch (e: java.io.IOException) {
            throw AiError(Net.message(via.prefs, e, "Couldn't reach the AI: ${e.message ?: "no connection"}"))
        }
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 120_000
            c.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AiError(errorMessage(code, text))
            return runCatching { JSONObject(text) }.getOrElse { throw AiError("The AI's answer wasn't readable") }
        } catch (e: AiError) {
            throw e
        } catch (e: java.io.IOException) {
            throw AiError(Net.message(via.prefs, e, "Couldn't reach the AI: ${e.message ?: "no connection"}"))
        } finally {
            c.disconnect()
        }
    }

    /** A provider's error as a short sentence: their message where they send one. */
    private fun errorMessage(code: Int, text: String): String {
        val message = runCatching {
            val o = JSONObject(text)
            o.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: o.optString("message").takeIf { it.isNotBlank() }
        }.getOrNull()
        val lead = when (code) {
            401, 403 -> "The key was refused"
            404 -> "That model isn't available"
            429 -> "Too many requests, or out of credit"
            in 500..599 -> "The AI's server had a problem"
            else -> "The AI said no ($code)"
        }
        return if (message.isNullOrBlank()) lead else "$lead: ${message.take(160)}"
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
