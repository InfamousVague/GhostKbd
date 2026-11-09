package com.infamousvague.devkeyboard

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The AI the Mockingbird and Ghostwriter plugins talk to: the person's own key for one provider, straight from the
 * phone to that provider, nothing in between. One chosen provider ([KeyboardPrefs.AI_PROVIDER]); each provider keeps
 * its own key and model ([KeyboardPrefs.aiKey], [KeyboardPrefs.aiModel]), so switching back and forth loses nothing.
 *
 * Three wire shapes cover them all: Anthropic's Messages API, Google's Gemini API, and the OpenAI chat shape that
 * OpenAI, Mistral, Groq, DeepSeek, OpenRouter and self-hosted servers (Ollama, LM Studio, vLLM behind https) share.
 * Blocking calls: run them off the main thread.
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

    /**
     * One request: [system] instructions and the [user] message, back as plain text. [effort] is "low" for quick
     * fills and "medium" for heavier work; providers that take no such setting ignore it.
     */
    fun complete(prefs: KeyboardPrefs, system: String, user: String, maxTokens: Int, effort: String = "low"): String {
        val s = setup(prefs)
        return when (s.provider.wire) {
            Wire.ANTHROPIC -> anthropic(s, system, user, maxTokens, effort)
            Wire.GEMINI -> gemini(s, system, user, maxTokens)
            Wire.OPENAI -> openAi(s, system, user, maxTokens)
        }.trim().ifEmpty { throw AiError("The AI sent back nothing") }
    }

    /** The models the chosen provider offers this key, for Settings' picker. */
    fun models(prefs: KeyboardPrefs): List<String> {
        val s = setup(prefs)
        return when (s.provider.wire) {
            Wire.ANTHROPIC -> {
                val o = request("GET", "${s.base}/models?limit=100", anthropicHeaders(s, beta = false), null)
                o.optJSONArray("data").objects().map { it.optString("id") }
            }
            Wire.GEMINI -> {
                val o = request("GET", "${s.base}/models?pageSize=200", mapOf("x-goog-api-key" to s.key), null)
                o.optJSONArray("models").objects()
                    .filter { m -> m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).any { a.optString(it) == "generateContent" } } == true }
                    .map { it.optString("name").removePrefix("models/") }
            }
            Wire.OPENAI -> {
                val o = request("GET", "${s.base}/models", bearer(s), null)
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

    private fun anthropic(s: Setup, system: String, user: String, maxTokens: Int, effort: String): String {
        val fallback = s.model in FALLBACK_MODELS
        val body = JSONObject()
            .put("model", s.model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        // Effort steers how long the model thinks: low keeps a fill quick. Claude 5-family models take it.
        if (Regex("^claude-(opus|sonnet|haiku|fable)-5").containsMatchIn(s.model)) body.put("output_config", JSONObject().put("effort", effort))
        if (fallback) body.put("fallbacks", "default")
        val o = request("POST", "${s.base}/messages", anthropicHeaders(s, fallback), body)
        if (o.optString("stop_reason") == "refusal") throw AiError("Claude declined this one")
        return o.optJSONArray("content").objects().filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
    }

    // --- Gemini -------------------------------------------------------------------------------------------------------

    private fun gemini(s: Setup, system: String, user: String, maxTokens: Int): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        val o = request("POST", "${s.base}/models/${s.model}:generateContent", mapOf("x-goog-api-key" to s.key), body)
        val candidate = o.optJSONArray("candidates")?.optJSONObject(0) ?: throw AiError(o.optJSONObject("promptFeedback")?.optString("blockReason")?.let { "Gemini blocked it ($it)" } ?: "Gemini sent back nothing")
        return candidate.optJSONObject("content")?.optJSONArray("parts").objects().joinToString("") { it.optString("text") }
    }

    // --- OpenAI and the servers that speak its shape -------------------------------------------------------------------

    private fun bearer(s: Setup) = buildMap {
        if (s.key.isNotEmpty()) put("Authorization", "Bearer ${s.key}")
        // OpenRouter asks apps to say who they are.
        if (s.provider.id == "openrouter") { put("X-Title", "Ghost Keyboard"); put("HTTP-Referer", "https://attack.fm/devkeyboard/") }
    }

    private fun openAi(s: Setup, system: String, user: String, maxTokens: Int): String {
        val body = JSONObject()
            .put("model", s.model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        // OpenAI's reasoning models want max_completion_tokens; the others still read max_tokens.
        body.put(if (s.provider.id == "openai") "max_completion_tokens" else "max_tokens", maxTokens)
        val o = request("POST", "${s.base}/chat/completions", bearer(s), body)
        return o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
    }

    // --- HTTP ---------------------------------------------------------------------------------------------------------

    private fun request(method: String, url: String, headers: Map<String, String>, body: JSONObject?): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
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
            throw AiError("Couldn't reach the AI: ${e.message ?: "no connection"}")
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
