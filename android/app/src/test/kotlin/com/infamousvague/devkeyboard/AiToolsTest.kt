package com.infamousvague.devkeyboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The exchange in which the AI asks for a tool and is given its answer (AiTools.kt), for each of the four shapes
 * AiClient speaks. The providers' answers here are made up to their documented shapes; what is held is what this
 * side sends back: the model's own turn as it came, each call answered under its own id, and words at the end. No
 * real provider has been asked (docs/connectors.md).
 */
class AiToolsTest {
    private val schema = JSONObject().put("type", "object").put("properties", JSONObject().put("from", JSONObject().put("type", "string"))).put("required", JSONArray().put("from"))
    private val tools = listOf(AiTools.Tool("c1_list_events", "[Calendar] Events between two dates.", schema))

    /** Answers each request with the next of [answers], keeping a copy of every request as it was sent. */
    private class Provider(vararg answers: JSONObject) {
        private val left = ArrayDeque(answers.toList())
        val sent = ArrayList<JSONObject>()
        val post: (JSONObject) -> JSONObject = { body -> sent += JSONObject(body.toString()); left.removeFirst() }
    }

    /** A tool that says what it was asked, and keeps it. */
    private class Calendar {
        val asked = ArrayList<Pair<String, String>>()
        val call: (String, JSONObject) -> String = { name, args -> asked += name to args.toString(); "Thursday: Standup 10:00 to 10:30." }
    }

    private fun items(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
    private fun keys(o: JSONObject): Set<String> = o.keys().asSequence().toSet()

    // --- Anthropic ----------------------------------------------------------------------------------------------------

    private fun claudeBody() = JSONObject().put("model", "claude-opus-5-5").put("max_tokens", 4000).put("system", "Write it.")
        .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "REQUEST: when am I free Thursday")))

    private val claudeAsks = JSONObject().put("stop_reason", "tool_use").put("content", JSONArray()
        .put(JSONObject().put("type", "thinking").put("thinking", "I should look.").put("signature", "sig-1"))
        .put(JSONObject().put("type", "text").put("text", "Let me check."))
        .put(JSONObject().put("type", "tool_use").put("id", "toolu_1").put("name", "c1_list_events").put("input", JSONObject().put("from", "2026-11-12"))))
    private val claudeAnswers = JSONObject().put("stop_reason", "end_turn").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Free from 10:30.")))

    @Test fun `Claude is told the tools, given what one answered under its own id, and its turn goes back whole`() {
        val provider = Provider(claudeAsks, claudeAnswers)
        val calendar = Calendar()
        assertEquals("Free from 10:30.", AiTools.anthropic(claudeBody(), tools, calendar.call, provider.post))
        assertEquals(listOf("c1_list_events" to "{\"from\":\"2026-11-12\"}"), calendar.asked)
        // The first request names the tool with its schema.
        val told = provider.sent[0].getJSONArray("tools").getJSONObject(0)
        assertEquals("c1_list_events", told.getString("name"))
        assertEquals("string", told.getJSONObject("input_schema").getJSONObject("properties").getJSONObject("from").getString("type"))
        // The second carries the first turn as it came (its thinking and signature with it), then the tool's answer.
        val messages = items(provider.sent[1].getJSONArray("messages"))
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.getString("role") })
        val turn = items(messages[1].getJSONArray("content"))
        assertEquals(listOf("thinking", "text", "tool_use"), turn.map { it.getString("type") })
        assertEquals("sig-1", turn[0].getString("signature"))
        assertEquals("toolu_1", turn[2].getString("id"))
        val result = messages[2].getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("toolu_1", result.getString("tool_use_id"))
        assertEquals("Thursday: Standup 10:00 to 10:30.", result.getString("content"))
        assertFalse(result.has("is_error"))
    }

    @Test fun `the web search it may already have stays beside the tools`() {
        val body = claudeBody().put("tools", JSONArray().put(JSONObject().put("type", "web_search_20260209").put("name", "web_search")))
        val provider = Provider(claudeAnswers)
        AiTools.anthropic(body, tools, Calendar().call, provider.post)
        assertEquals(listOf("web_search", "c1_list_events"), items(provider.sent[0].getJSONArray("tools")).map { it.getString("name") })
    }

    @Test fun `a tool that fails is told to Claude as a failure, and Claude writes on`() {
        val provider = Provider(claudeAsks, claudeAnswers)
        val text = AiTools.anthropic(claudeBody(), tools, { _, _ -> throw McpClient.McpError("No calendar is connected.") }, provider.post)
        assertEquals("Free from 10:30.", text)
        val result = provider.sent[1].getJSONArray("messages").getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertTrue(result.getBoolean("is_error"))
        assertEquals("The tool could not be used: No calendar is connected.", result.getString("content"))
    }

    @Test fun `a search that pauses the turn is carried on, and only the words after the last tool are the answer`() {
        val paused = JSONObject().put("stop_reason", "pause_turn").put("content", JSONArray().put(JSONObject().put("type", "server_tool_use").put("id", "srv_1").put("name", "web_search")))
        val searched = JSONObject().put("stop_reason", "end_turn").put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "I will search."))
            .put(JSONObject().put("type", "web_search_tool_result").put("tool_use_id", "srv_1"))
            .put(JSONObject().put("type", "text").put("text", "It rained.")))
        val provider = Provider(paused, searched)
        assertEquals("It rained.", AiTools.anthropic(claudeBody(), tools, Calendar().call, provider.post))
        assertEquals("assistant", provider.sent[1].getJSONArray("messages").getJSONObject(1).getString("role"))
    }

    @Test fun `a model that never stops asking is stopped, and a refusal is said`() {
        val asks = Array(AiTools.MAX_ROUNDS + 2) { JSONObject(claudeAsks.toString()) }
        try {
            AiTools.anthropic(claudeBody(), tools, Calendar().call, Provider(*asks).post)
            fail()
        } catch (e: AiClient.AiError) {
            assertTrue(e.message!!.contains("never answered"))
        }
        try {
            AiTools.anthropic(claudeBody(), tools, Calendar().call, Provider(JSONObject().put("stop_reason", "refusal")).post)
            fail()
        } catch (e: AiClient.AiError) {
            assertEquals("Claude declined this one", e.message)
        }
    }

    // --- OpenAI's chat completions -------------------------------------------------------------------------------------

    private fun chatBody() = JSONObject().put("model", "gpt-5-mini").put("messages", JSONArray()
        .put(JSONObject().put("role", "system").put("content", "Write it.")).put(JSONObject().put("role", "user").put("content", "REQUEST: when am I free Thursday")))

    @Test fun `a chat completions model's call is answered as a tool message under the call's id`() {
        val asks = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("message", JSONObject()
            .put("role", "assistant").put("content", JSONObject.NULL)
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "call_1").put("type", "function").put("function", JSONObject().put("name", "c1_list_events").put("arguments", "{\"from\":\"2026-11-12\"}")))))))
        val answers = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", "Free from 10:30."))))
        val provider = Provider(asks, answers)
        val calendar = Calendar()
        assertEquals("Free from 10:30.", AiTools.openAi(chatBody(), tools, calendar.call, provider.post))
        assertEquals(listOf("c1_list_events" to "{\"from\":\"2026-11-12\"}"), calendar.asked)
        val told = provider.sent[0].getJSONArray("tools").getJSONObject(0)
        assertEquals("function", told.getString("type"))
        assertEquals("c1_list_events", told.getJSONObject("function").getString("name"))
        assertEquals("from", told.getJSONObject("function").getJSONObject("parameters").getJSONArray("required").getString(0))
        val messages = items(provider.sent[1].getJSONArray("messages"))
        assertEquals(listOf("system", "user", "assistant", "tool"), messages.map { it.getString("role") })
        assertEquals("call_1", messages[2].getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("call_1", messages[3].getString("tool_call_id"))
        assertEquals("Thursday: Standup 10:00 to 10:30.", messages[3].getString("content"))
    }

    @Test fun `an answer with no words is nothing, not the word null`() {
        val nothing = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", JSONObject.NULL))))
        assertEquals("", AiTools.openAi(chatBody(), tools, Calendar().call, Provider(nothing).post))
    }

    @Test fun `arguments that are not JSON are taken as none, and the call still answered`() {
        val asks = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "call_9").put("function", JSONObject().put("name", "c1_list_events").put("arguments", "not json")))))))
        val answers = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", "ok"))))
        val calendar = Calendar()
        assertEquals("ok", AiTools.openAi(chatBody(), tools, calendar.call, Provider(asks, answers).post))
        assertEquals(listOf("c1_list_events" to "{}"), calendar.asked)
    }

    // --- OpenAI's Responses API ----------------------------------------------------------------------------------------

    @Test fun `a Responses model's call goes back with everything it put out, and its answer under the call's id`() {
        val body = JSONObject().put("model", "gpt-5").put("instructions", "Write it.").put("input", "REQUEST: when am I free Thursday").put("max_output_tokens", 4000)
            .put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        val asks = JSONObject().put("id", "resp_1").put("output", JSONArray()
            .put(JSONObject().put("type", "reasoning").put("id", "rs_1").put("summary", JSONArray()))
            .put(JSONObject().put("type", "function_call").put("id", "fc_1").put("call_id", "call_1").put("name", "c1_list_events").put("arguments", "{\"from\":\"2026-11-12\"}")))
        val answers = JSONObject().put("id", "resp_2").put("output", JSONArray().put(JSONObject().put("type", "message").put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Free from 10:30.")))))
        val provider = Provider(asks, answers)
        val calendar = Calendar()
        assertEquals("Free from 10:30.", AiTools.openAiResponses(body, tools, calendar.call, provider.post))
        assertEquals(1, calendar.asked.size)
        val told = items(provider.sent[0].getJSONArray("tools"))
        assertEquals(listOf("web_search", "function"), told.map { it.getString("type") })
        assertEquals("c1_list_events", told[1].getString("name"))
        // The person's words are the first item; then what the model put out, as it came; then the call's answer.
        val first = items(provider.sent[0].getJSONArray("input"))
        assertEquals("REQUEST: when am I free Thursday", first.single().getString("content"))
        val second = items(provider.sent[1].getJSONArray("input"))
        assertEquals(listOf("", "reasoning", "function_call", "function_call_output"), second.map { it.optString("type") })
        assertEquals("rs_1", second[1].getString("id"))
        assertEquals("call_1", second[3].getString("call_id"))
        assertEquals("Thursday: Standup 10:00 to 10:30.", second[3].getString("output"))
        // Nothing is asked of the server's memory of the last answer: the whole exchange is sent.
        assertFalse(provider.sent[1].has("previous_response_id"))
        assertEquals("Write it.", provider.sent[1].getString("instructions"))
    }

    // --- Gemini --------------------------------------------------------------------------------------------------------

    private fun geminiBody() = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Write it."))))
        .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "REQUEST: when am I free Thursday")))))
        .put("generationConfig", JSONObject().put("maxOutputTokens", 4000))

    @Test fun `Gemini's call goes back with its turn as it came, and its answer as a function response`() {
        val asks = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray()
            .put(JSONObject().put("functionCall", JSONObject().put("name", "c1_list_events").put("args", JSONObject().put("from", "2026-11-12"))).put("thoughtSignature", "sig-g"))))))
        val answers = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray()
            .put(JSONObject().put("text", "thinking aloud").put("thought", true)).put(JSONObject().put("text", "Free from 10:30."))))))
        val provider = Provider(asks, answers)
        val calendar = Calendar()
        assertEquals("Free from 10:30.", AiTools.gemini(geminiBody(), tools, calendar.call, provider.post))
        assertEquals(listOf("c1_list_events" to "{\"from\":\"2026-11-12\"}"), calendar.asked)
        // Functions only: its own search is not asked for in the same request.
        val told = provider.sent[0].getJSONArray("tools")
        assertEquals(1, told.length())
        val fn = told.getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(0)
        assertEquals("c1_list_events", fn.getString("name"))
        assertEquals("object", fn.getJSONObject("parameters").getString("type"))
        assertFalse(provider.sent[0].toString().contains("google_search"))
        val contents = items(provider.sent[1].getJSONArray("contents"))
        assertEquals(listOf("user", "model", "user"), contents.map { it.getString("role") })
        assertEquals("sig-g", contents[1].getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))
        val response = contents[2].getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("c1_list_events", response.getString("name"))
        assertEquals("Thursday: Standup 10:00 to 10:30.", response.getJSONObject("response").getString("result"))
    }

    @Test fun `a blocked Gemini request is said as blocked`() {
        try {
            AiTools.gemini(geminiBody(), tools, Calendar().call, Provider(JSONObject().put("promptFeedback", JSONObject().put("blockReason", "SAFETY"))).post)
            fail()
        } catch (e: AiClient.AiError) {
            assertEquals("Gemini blocked it (SAFETY)", e.message)
        }
    }

    @Test fun `a tool's schema is cut down to what Gemini takes`() {
        val full = JSONObject("""{"${'$'}schema":"https://json-schema.org/draft/2020-12/schema","type":"object","additionalProperties":false,
            "properties":{"q":{"type":["string","null"],"description":"What to look for","minLength":1},
                          "kind":{"type":"string","enum":["issue","pr"],"default":"issue"},
                          "limit":{"type":"integer","minimum":1},
                          "tags":{"type":"array","items":{"type":"string","pattern":"^[a-z]+$"}},
                          "filter":{"type":"object","additionalProperties":true},
                          "where":{"type":"object","properties":{"repo":{"type":"string"}},"required":["repo","gone"]}},
            "required":["q","missing"]}""")
        val g = AiTools.geminiSchema(full, top = true)!!
        assertEquals(setOf("type", "properties", "required"), keys(g))
        val props = g.getJSONObject("properties")
        assertEquals(setOf("type", "description", "nullable"), keys(props.getJSONObject("q")))
        assertTrue(props.getJSONObject("q").getBoolean("nullable"))
        assertEquals("[\"issue\",\"pr\"]", props.getJSONObject("kind").getJSONArray("enum").toString())
        assertFalse(props.getJSONObject("kind").has("default"))
        assertEquals(setOf("type"), keys(props.getJSONObject("limit")))
        assertEquals(setOf("type"), keys(props.getJSONObject("tags").getJSONObject("items")))
        // An object with nothing named in it cannot be asked for as an object: as text.
        assertEquals("string", props.getJSONObject("filter").getString("type"))
        assertEquals("(as JSON)", props.getJSONObject("filter").getString("description"))
        // Only what is there may be required.
        assertEquals("[\"repo\"]", props.getJSONObject("where").getJSONArray("required").toString())
        assertEquals("[\"q\"]", g.getJSONArray("required").toString())
        // A tool that takes nothing has no parameters at all.
        assertNull(AiTools.geminiSchema(JSONObject().put("type", "object"), top = true))
        assertNull(AiTools.geminiSchema(JSONObject().put("type", "object").put("properties", JSONObject()), top = true))
    }
}
