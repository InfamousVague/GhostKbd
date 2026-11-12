package com.infamousvague.devkeyboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools for the AI, for the Connectors plugin (Connectors.kt): the model is told which tools there are, may ask for
 * one, is given what it answered, and then writes. One such exchange for each of the shapes AiClient speaks:
 * Anthropic's Messages API, OpenAI's chat completions (and the servers that speak them), OpenAI's Responses API,
 * and Gemini.
 *
 * Each takes the request [AiClient] would have sent without tools, adds the tools to it, and goes round until the
 * model answers in words: at most [MAX_ROUNDS] times. A tool is run by [call], which is given its name and its
 * arguments and gives back text; a tool that throws is told to the model as a tool that failed, in its own words, and
 * the model goes on without it.
 *
 * Written from each provider's documented shapes, and held by AiToolsTest against answers made up to those shapes.
 * None of them has been run against a real provider (docs/connectors.md). Nothing here touches Android or opens a
 * connection: [post] does, and is AiClient's.
 */
object AiTools {
    /** A tool the model may call: its [name] (letters, digits, _ and -, at most 64), what it is for, and its arguments' JSON Schema. */
    class Tool(val name: String, val description: String, val schema: JSONObject)

    /** How many times the model may be asked before it must have answered in words. */
    const val MAX_ROUNDS = 6

    private const val STUCK = "The AI kept asking for tools and never answered"

    /**
     * Thrown by [call] to end the whole exchange there: the person stopped the request, or left its field. No more
     * tools are called and the AI is not asked again. Every other failure of a tool is the AI's to hear about.
     */
    class Stopped : RuntimeException("Stopped")

    /**
     * Whether a request that failed with [message] while it carried tools is worth making again without them. Only
     * where the tools may be why (a provider or a model that will not take them, an answer that never came in
     * words): not where the AI could not be reached, was busy, or would not take the key, which a second request
     * meets the same way a moment later.
     */
    fun plainMayAnswer(message: String?): Boolean = message != null && NOT_THE_TOOLS.none { message.startsWith(it) }

    private val NOT_THE_TOOLS = listOf(
        "Couldn't reach", "The key was refused", "Too many requests", "The AI's server had a problem", "That model isn't available",
        "Offline is on", "Add your", "Pick a model", "The server's address",
    )

    /** What [call] says for [name] and [args], or why it could not: the second is whether it failed. */
    private fun run(call: (String, JSONObject) -> String, name: String, args: JSONObject): Pair<String, Boolean> =
        try { call(name, args) to false } catch (e: Stopped) { throw e } catch (e: Throwable) { ("The tool could not be used: ${e.message ?: "it failed"}").take(400) to true }

    /**
     * A tool's JSON Schema as OpenAI's two shapes take it: an object, with its `properties` written out even where
     * it has none (a schema of `{"type":"object"}` alone, which is also what a tool with no schema is given, is
     * refused there).
     */
    fun openAiSchema(schema: JSONObject): JSONObject {
        val out = JSONObject(schema.toString())
        if (out.str("type").isBlank()) out.put("type", "object")
        if (out.str("type") == "object" && out.optJSONObject("properties") == null) out.put("properties", JSONObject())
        return out
    }

    private fun args(raw: Any?): JSONObject = when (raw) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
        else -> JSONObject()
    }

    // --- Anthropic ----------------------------------------------------------------------------------------------------

    /**
     * [body] is a Messages request (model, max_tokens, system, messages, and perhaps a web search tool already in
     * `tools`). The answer's text is what follows the last tool or search, as it is without tools.
     */
    fun anthropic(body: JSONObject, tools: List<Tool>, call: (String, JSONObject) -> String, post: (JSONObject) -> JSONObject): String {
        val all = body.optJSONArray("tools") ?: JSONArray().also { body.put("tools", it) }
        tools.forEach { all.put(JSONObject().put("name", it.name).put("description", it.description).put("input_schema", it.schema)) }
        val messages = body.getJSONArray("messages")
        repeat(MAX_ROUNDS + 2) {
            val o = post(body)
            val stop = o.optString("stop_reason")
            if (stop == "refusal") throw AiClient.AiError("Claude declined this one")
            val content = o.optJSONArray("content") ?: JSONArray()
            val blocks = (0 until content.length()).mapNotNull { content.optJSONObject(it) }
            when (stop) {
                // A long search paused the turn: the conversation goes back as it stands and Claude carries on.
                "pause_turn" -> messages.put(JSONObject().put("role", "assistant").put("content", content))
                "tool_use" -> {
                    // Its turn goes back whole (its thinking with it, which the API wants returned as it came).
                    messages.put(JSONObject().put("role", "assistant").put("content", content))
                    val results = JSONArray()
                    for (b in blocks) if (b.optString("type") == "tool_use") {
                        val (out, failed) = run(call, b.optString("name"), args(b.opt("input")))
                        results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id")).put("content", out).apply { if (failed) put("is_error", true) })
                    }
                    messages.put(JSONObject().put("role", "user").put("content", results))
                }
                else -> {
                    val lastTool = blocks.indexOfLast { it.optString("type") != "text" && it.optString("type") != "thinking" }
                    return blocks.drop(lastTool + 1).filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") }
                }
            }
        }
        throw AiClient.AiError(STUCK)
    }

    // --- OpenAI's chat completions, and the servers that speak them ---------------------------------------------------

    /** [body] is a chat completions request (model, messages, a token limit). */
    fun openAi(body: JSONObject, tools: List<Tool>, call: (String, JSONObject) -> String, post: (JSONObject) -> JSONObject): String {
        val all = JSONArray()
        tools.forEach { all.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", it.name).put("description", it.description).put("parameters", openAiSchema(it.schema)))) }
        body.put("tools", all)
        val messages = body.getJSONArray("messages")
        // One ask more than the tools it may call: the last is for its answer in words.
        repeat(MAX_ROUNDS + 1) {
            val o = post(body)
            val message = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: throw AiClient.AiError("The AI sent back nothing")
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) return if (message.isNull("content")) "" else message.optString("content")
            // Its message goes back as it came, with the calls it asked for, then one answer for each call.
            messages.put(message)
            for (i in 0 until calls.length()) {
                val c = calls.optJSONObject(i) ?: continue
                val f = c.optJSONObject("function") ?: JSONObject()
                val (out, _) = run(call, f.optString("name"), args(f.opt("arguments")))
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", c.optString("id")).put("content", out))
            }
        }
        throw AiClient.AiError(STUCK)
    }

    // --- OpenAI's Responses API ----------------------------------------------------------------------------------------

    /**
     * [body] is a Responses request (model, instructions, `input` as the person's words, perhaps a web search tool
     * in `tools`). What the model put out goes back in `input` as it came, with each call's answer after it: the
     * whole exchange is sent each time, with nothing asked of the server's memory of the last one.
     */
    fun openAiResponses(body: JSONObject, tools: List<Tool>, call: (String, JSONObject) -> String, post: (JSONObject) -> JSONObject): String {
        val all = body.optJSONArray("tools") ?: JSONArray().also { body.put("tools", it) }
        // Not strict: a connector's schema is its server's own, with arguments that may be left out, and this API
        // takes a function for strict (every argument required, nothing else allowed) unless told otherwise.
        tools.forEach { all.put(JSONObject().put("type", "function").put("name", it.name).put("description", it.description).put("parameters", openAiSchema(it.schema)).put("strict", false)) }
        val input = JSONArray().put(JSONObject().put("role", "user").put("content", body.optString("input")))
        body.put("input", input)
        repeat(MAX_ROUNDS + 1) {
            val o = post(body)
            val output = o.optJSONArray("output") ?: JSONArray()
            val items = (0 until output.length()).mapNotNull { output.optJSONObject(it) }
            val calls = items.filter { it.optString("type") == "function_call" }
            if (calls.isEmpty()) {
                o.str("output_text").takeIf { it.isNotBlank() }?.let { return it }
                return items.filter { it.optString("type") == "message" }.flatMap { m -> m.optJSONArray("content").let { a -> if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) } } }
                    .filter { it.optString("type") == "output_text" }.joinToString("") { it.optString("text") }
            }
            items.forEach { input.put(it) }
            for (c in calls) {
                val (out, _) = run(call, c.optString("name"), args(c.opt("arguments")))
                input.put(JSONObject().put("type", "function_call_output").put("call_id", c.optString("call_id")).put("output", out))
            }
        }
        throw AiClient.AiError(STUCK)
    }

    // --- Gemini --------------------------------------------------------------------------------------------------------

    /**
     * [body] is a generateContent request (systemInstruction, contents, generationConfig). Its tools become the
     * functions alone: Gemini's own search is not asked for in the same request, which not every Gemini model allows.
     */
    fun gemini(body: JSONObject, tools: List<Tool>, call: (String, JSONObject) -> String, post: (JSONObject) -> JSONObject): String {
        val functions = JSONArray()
        tools.forEach { t ->
            functions.put(JSONObject().put("name", t.name).put("description", t.description).apply { geminiSchema(t.schema, top = true)?.let { put("parameters", it) } })
        }
        body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functions)))
        val contents = body.getJSONArray("contents")
        repeat(MAX_ROUNDS + 1) {
            val o = post(body)
            val candidate = o.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw AiClient.AiError(o.optJSONObject("promptFeedback")?.optString("blockReason")?.let { "Gemini blocked it ($it)" } ?: "Gemini sent back nothing")
            val content = candidate.optJSONObject("content") ?: JSONObject()
            val parts = content.optJSONArray("parts").let { a -> if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            val calls = parts.mapNotNull { it.optJSONObject("functionCall") }
            if (calls.isEmpty()) return parts.filter { !it.optBoolean("thought") }.joinToString("") { it.optString("text") }
            // Its turn goes back as it came (its thought signatures with it), then one answer for each call.
            contents.put(JSONObject(content.toString()).put("role", "model"))
            val answers = JSONArray()
            for (c in calls) {
                val (out, _) = run(call, c.optString("name"), args(c.opt("args")))
                answers.put(JSONObject().put("functionResponse", JSONObject().put("name", c.optString("name")).put("response", JSONObject().put("result", out)).apply { if (c.has("id")) put("id", c.optString("id")) }))
            }
            contents.put(JSONObject().put("role", "user").put("parts", answers))
        }
        throw AiClient.AiError(STUCK)
    }

    /**
     * A tool's JSON Schema as Gemini takes it: the few keywords it knows (type, description, enum, properties,
     * required, items, nullable), everything else left out. A [top] schema with no properties gives no parameters at
     * all (null); further in, an object with none is asked for as text.
     */
    fun geminiSchema(schema: JSONObject, top: Boolean = false): JSONObject? {
        val out = JSONObject()
        var type: String? = null
        when (val t = schema.opt("type")) {
            is String -> type = t
            is JSONArray -> {
                val kinds = (0 until t.length()).map { t.optString(it) }
                type = kinds.firstOrNull { it != "null" }
                if ("null" in kinds) out.put("nullable", true)
            }
        }
        val properties = schema.optJSONObject("properties")
        if (type == null) type = if (properties != null) "object" else "string"
        schema.optString("description").takeIf { it.isNotBlank() }?.let { out.put("description", it.take(600)) }
        when (type) {
            "object" -> {
                if (properties == null || properties.length() == 0) {
                    if (top) return null
                    return out.put("type", "string").put("description", (out.optString("description") + " (as JSON)").trim())
                }
                val props = JSONObject()
                for (key in properties.keys()) properties.optJSONObject(key)?.let { geminiSchema(it)?.let { s -> props.put(key, s) } }
                out.put("type", "object").put("properties", props)
                schema.optJSONArray("required")?.let { r ->
                    val kept = JSONArray()
                    for (i in 0 until r.length()) r.optString(i).takeIf { props.has(it) }?.let { kept.put(it) }
                    if (kept.length() > 0) out.put("required", kept)
                }
            }
            "array" -> out.put("type", "array").put("items", schema.optJSONObject("items")?.let { geminiSchema(it) } ?: JSONObject().put("type", "string"))
            "string", "number", "integer", "boolean" -> {
                out.put("type", type)
                schema.optJSONArray("enum")?.takeIf { type == "string" }?.let { out.put("enum", it) }
            }
            else -> out.put("type", "string")
        }
        return out
    }
}
