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
 * The Connectors plugin's protocol client (McpClient.kt) against servers made up here: one of the 2026 kind (no
 * handshake, the version in a header), one of the older kind that wants `initialize` and a session, one that answers
 * in an event stream, and ones that refuse. The servers are written to the specification as it was summarised on
 * 2026-11-12; a real server is the test that counts, and none has been asked (docs/connectors.md).
 */
class McpClientTest {
    private val url = "https://mcp.example.com/mcp"

    /** One POST as a server saw it. */
    private class Seen(val headers: Map<String, String>, val body: JSONObject) {
        val method: String get() = body.optString("method")
    }

    private fun ok(id: Any?, result: JSONObject) = JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString()
    private fun error(id: Any?, code: Int, message: String, data: JSONObject? = null) =
        JSONObject().put("jsonrpc", "2.0").put("id", id ?: JSONObject.NULL).put("error", JSONObject().put("code", code).put("message", message).apply { data?.let { put("data", it) } }).toString()

    private val tools = JSONArray()
        .put(JSONObject().put("name", "list_events").put("title", "List events").put("description", "Events between two dates.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject().put("from", JSONObject().put("type", "string"))))
            .put("annotations", JSONObject().put("readOnlyHint", true)))
        .put(JSONObject().put("name", "create_event").put("description", "Adds an event.").put("annotations", JSONObject().put("readOnlyHint", false)))
        .put(JSONObject().put("name", "search").put("description", "Searches."))

    /** A server of the 2026 kind: no handshake; every request must say its version and its method in headers. */
    private fun modern(seen: MutableList<Seen>, version: String = McpClient.VERSION) = McpClient.Transport { _, headers, body, _ ->
        val o = JSONObject(body)
        seen += Seen(headers, o)
        val id = o.opt("id")
        when {
            o.optString("method") == "initialize" -> McpClient.Reply(200, error(id, -32601, "Method not found"))
            headers["MCP-Protocol-Version"] != version ->
                McpClient.Reply(400, error(id, -32022, "Unsupported protocol version", JSONObject().put("supported", JSONArray().put(version))))
            headers["Mcp-Method"] != o.optString("method") -> McpClient.Reply(400, error(id, -32600, "Mcp-Method does not match"))
            o.optString("method") == "tools/list" -> McpClient.Reply(200, ok(id, JSONObject().put("tools", tools)))
            o.optString("method") == "tools/call" -> McpClient.Reply(200, ok(id, JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "Thursday 10:00 Standup")))))
            else -> McpClient.Reply(200, error(id, -32601, "Method not found"))
        }
    }

    /** A server of the older kind: nothing is answered before `initialize`, which gives a session id to be sent from then on. */
    private fun older(seen: MutableList<Seen>, session: String? = "s-1", version: String = "2025-06-18") = McpClient.Transport { _, headers, body, _ ->
        val o = JSONObject(body)
        seen += Seen(headers, o)
        val id = o.opt("id")
        when {
            o.optString("method") == "initialize" ->
                McpClient.Reply(200, ok(id, JSONObject().put("protocolVersion", version).put("capabilities", JSONObject()).put("serverInfo", JSONObject().put("name", "old"))), session = session)
            o.optString("method") == "notifications/initialized" -> McpClient.Reply(202, "")
            session != null && headers["Mcp-Session-Id"] != session -> McpClient.Reply(400, error(null, -32000, "Bad Request: No valid session ID provided"))
            headers["MCP-Protocol-Version"] != version -> McpClient.Reply(400, error(null, -32000, "Bad Request: Unsupported protocol version"))
            o.optString("method") == "tools/list" -> McpClient.Reply(200, ok(id, JSONObject().put("tools", tools)))
            o.optString("method") == "tools/call" -> McpClient.Reply(200, ok(id, JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ok")))))
            else -> McpClient.Reply(200, error(id, -32601, "Method not found"))
        }
    }

    @Test fun `a server of the new kind is asked with no handshake, its version and method in headers`() {
        val seen = ArrayList<Seen>()
        val session = McpClient.Session()
        val found = McpClient.tools(modern(seen), url, "", session)
        assertEquals(listOf("list_events", "create_event", "search"), found.map { it.name })
        assertEquals(1, seen.size)
        val first = seen.single()
        assertEquals("tools/list", first.method)
        assertEquals(McpClient.VERSION, first.headers["MCP-Protocol-Version"])
        assertEquals("tools/list", first.headers["Mcp-Method"])
        assertEquals("application/json, text/event-stream", first.headers["Accept"])
        assertEquals(McpClient.VERSION, first.body.getJSONObject("params").getJSONObject("_meta").getString("io.modelcontextprotocol/protocolVersion"))
        // No token given, none sent.
        assertNull(first.headers["Authorization"])
        assertEquals(false, session.legacy)
    }

    @Test fun `what the server says of a tool is read, its read-only mark only where it gives one`() {
        val found = McpClient.tools(modern(ArrayList()), url, "", McpClient.Session())
        assertEquals(true, found[0].readOnly)
        assertEquals("List events", found[0].title)
        assertEquals("Events between two dates.", found[0].description)
        assertEquals("string", found[0].schema.getJSONObject("properties").getJSONObject("from").getString("type"))
        assertEquals(false, found[1].readOnly)
        // No mark at all is not "read-only": it is unknown.
        assertNull(found[2].readOnly)
        // A tool with no schema takes an object with nothing in it.
        assertEquals("object", found[2].schema.getString("type"))
    }

    @Test fun `a tool call names the tool in a header too, and its token goes as a bearer`() {
        val seen = ArrayList<Seen>()
        val session = McpClient.Session()
        val text = McpClient.call(modern(seen), url, " secret-token ", session, "list_events", JSONObject().put("from", "2026-11-12"))
        assertEquals("Thursday 10:00 Standup", text)
        // Nothing was known of the server: it is first asked for its tools, which changes nothing, then the call.
        assertEquals(listOf("tools/list", "tools/call"), seen.map { it.method })
        val call = seen.last()
        assertEquals("tools/call", call.headers["Mcp-Method"])
        // Once its kind is known, a call is the call alone.
        McpClient.call(modern(seen), url, "", session, "list_events", JSONObject())
        assertEquals(3, seen.size)
        assertEquals("list_events", call.headers["Mcp-Name"])
        assertEquals("Bearer secret-token", call.headers["Authorization"])
        assertEquals("list_events", call.body.getJSONObject("params").getString("name"))
        assertEquals("2026-11-12", call.body.getJSONObject("params").getJSONObject("arguments").getString("from"))
    }

    @Test fun `a server of the older kind is given the handshake, and its session from then on`() {
        val seen = ArrayList<Seen>()
        val session = McpClient.Session()
        val found = McpClient.tools(older(seen), url, "tok", session)
        assertEquals(3, found.size)
        // The new way first (refused), then initialize, the note that it is done, and the request again.
        assertEquals(listOf("tools/list", "initialize", "notifications/initialized", "tools/list"), seen.map { it.method })
        assertEquals(McpClient.LEGACY, seen[1].body.getJSONObject("params").getString("protocolVersion"))
        assertEquals("Ghost Keyboard", seen[1].body.getJSONObject("params").getJSONObject("clientInfo").getString("name"))
        assertFalse("a notification has no id", seen[2].body.has("id"))
        assertEquals("s-1", seen[2].headers["Mcp-Session-Id"])
        assertEquals("s-1", seen[3].headers["Mcp-Session-Id"])
        // The version the server chose is the one spoken from then on.
        assertEquals("2025-06-18", seen[3].headers["MCP-Protocol-Version"])
        assertEquals(true, session.legacy)
        assertEquals("s-1", session.id)
        // Every one of them carried the token, the handshake too.
        assertTrue(seen.all { it.headers["Authorization"] == "Bearer tok" })

        // The next request goes straight in, with no second handshake.
        seen.clear()
        assertEquals("ok", McpClient.call(older(seen), url, "tok", session, "search", JSONObject()))
        assertEquals(listOf("tools/call"), seen.map { it.method })
    }

    @Test fun `an older server that gives no session id is not shaken hands with twice`() {
        val seen = ArrayList<Seen>()
        val session = McpClient.Session()
        val server = older(seen, session = null)
        McpClient.tools(server, url, "", session)
        McpClient.call(server, url, "", session, "search", JSONObject())
        assertEquals(1, seen.count { it.method == "initialize" })
        assertNull(session.id)
        assertTrue(seen.none { it.headers.containsKey("Mcp-Session-Id") })
    }

    @Test fun `a session the server has let go is started again, once`() {
        val seen = ArrayList<Seen>()
        val session = McpClient.Session()
        McpClient.tools(older(seen), url, "", session)
        // The server restarts: it knows a new session only, and answers 404 to the old one.
        val restarted = McpClient.Transport { u, headers, body, done ->
            val o = JSONObject(body)
            if (o.optString("method") != "initialize" && o.optString("method") != "notifications/initialized" && headers["Mcp-Session-Id"] == "s-1") {
                seen += Seen(headers, o)
                McpClient.Reply(404, "")
            } else older(seen, session = "s-2").post(u, headers, body, done)
        }
        seen.clear()
        assertEquals("ok", McpClient.call(restarted, url, "", session, "search", JSONObject()))
        assertEquals(listOf("tools/call", "initialize", "notifications/initialized", "tools/call"), seen.map { it.method })
        assertEquals("s-2", session.id)
    }

    @Test fun `a server that names a newer version of the new kind is asked again in it`() {
        val seen = ArrayList<Seen>()
        val session = McpClient.Session()
        val found = McpClient.tools(modern(seen, version = "2026-12-01"), url, "", session)
        assertEquals(3, found.size)
        assertEquals(listOf(McpClient.VERSION, "2026-12-01"), seen.map { it.headers["MCP-Protocol-Version"] })
        assertEquals("2026-12-01", session.version)
        assertEquals(false, session.legacy)
        assertTrue(seen.none { it.method == "initialize" })
    }

    @Test fun `an answer in an event stream is found among the server's notes on the way`() {
        val stream = { id: Int ->
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}\n\n" +
                ": a comment\n\n" +
                "event: message\r\ndata: " + ok(id, JSONObject().put("tools", tools)) + "\r\n\r\n"
        }
        val asked = ArrayList<Boolean>()
        val server = McpClient.Transport { _, _, body, done ->
            val id = JSONObject(body).getInt("id")
            val whole = stream(id)
            // The transport asks, an event at a time, whether the answer has come: not after the note, yes after the answer.
            asked += done(whole.substringBefore(": a comment"))
            asked += done(whole)
            McpClient.Reply(200, whole, "text/event-stream")
        }
        assertEquals(3, McpClient.tools(server, url, "", McpClient.Session()).size)
        assertEquals(listOf(false, true), asked)
        // The answer to another request is not taken for this one's.
        assertNull(McpClient.found(stream(7), 8))
        assertEquals(3, McpClient.found(stream(7), 7)!!.getJSONObject("result").getJSONArray("tools").length())
    }

    @Test fun `every page of a long list is read, and a server that repeats a page is not asked for ever`() {
        var asked = 0
        val paged = McpClient.Transport { _, _, body, _ ->
            val o = JSONObject(body)
            asked++
            val cursor = o.getJSONObject("params").optString("cursor")
            val page = JSONArray().put(JSONObject().put("name", "tool_${cursor.ifEmpty { "0" }}"))
            val next = when (cursor) { "" -> "1"; "1" -> "2"; else -> "2" }
            McpClient.Reply(200, ok(o.opt("id"), JSONObject().put("tools", page).put("nextCursor", next)))
        }
        val found = McpClient.tools(paged, url, "", McpClient.Session())
        assertEquals(listOf("tool_0", "tool_1", "tool_2"), found.map { it.name })
        assertEquals(3, asked)
    }

    @Test fun `a server that wants a sign-in says so, and is not given the handshake instead`() {
        val seen = ArrayList<Seen>()
        val locked = McpClient.Transport { _, headers, body, _ -> seen += Seen(headers, JSONObject(body)); McpClient.Reply(401, "") }
        try {
            McpClient.tools(locked, url, "", McpClient.Session())
            fail("a 401 is not a list of tools")
        } catch (e: McpClient.McpError) {
            assertTrue(e.signIn)
            assertTrue(e.message!!.contains("wants a sign-in"))
            assertTrue(e.message!!.contains("not built yet"))
        }
        assertEquals(1, seen.size)
        val forbidden = McpClient.Transport { _, _, _, _ -> McpClient.Reply(403, "") }
        try {
            McpClient.tools(forbidden, url, "bad", McpClient.Session())
            fail()
        } catch (e: McpClient.McpError) {
            assertFalse(e.signIn)
            assertTrue(e.message!!.contains("refused the token"))
        }
    }

    @Test fun `a tool that says it failed is an error in its own words, for the AI to be told`() {
        val failing = McpClient.Transport { _, _, body, _ ->
            val o = JSONObject(body)
            McpClient.Reply(200, ok(o.opt("id"), JSONObject().put("isError", true).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "No calendar is connected.")))))
        }
        try {
            McpClient.call(failing, url, "", McpClient.Session().apply { legacy = false }, "list_events", JSONObject())
            fail()
        } catch (e: McpClient.McpError) {
            assertEquals("No calendar is connected.", e.message)
        }
    }

    @Test fun `what a tool answers is given as text, cut to its limit`() {
        val result = JSONObject().put("content", JSONArray()
            .put(JSONObject().put("type", "text").put("text", "First."))
            .put(JSONObject().put("type", "image").put("data", "…"))
            .put(JSONObject().put("type", "resource_link").put("name", "spec").put("uri", "https://example.com/spec"))
            .put(JSONObject().put("type", "resource").put("resource", JSONObject().put("uri", "file:///a").put("text", "Embedded."))))
        assertEquals("First.\n[a picture]\n[spec: https://example.com/spec]\nEmbedded.", McpClient.text(result))
        // Nothing in words: the structured answer, as it is.
        assertEquals("{\"count\":2}", McpClient.text(JSONObject().put("content", JSONArray()).put("structuredContent", JSONObject().put("count", 2))))
        assertEquals("", McpClient.text(JSONObject()))
        val long = McpClient.Transport { _, _, body, _ ->
            McpClient.Reply(200, ok(JSONObject(body).opt("id"), JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "x".repeat(20_000))))))
        }
        assertEquals(McpClient.MAX_RESULT, McpClient.call(long, url, "", McpClient.Session(), "search", JSONObject()).length)
    }

    @Test fun `a server that answers nothing readable is an error in words, not a crash`() {
        for (reply in listOf(McpClient.Reply(200, "<html>not json</html>"), McpClient.Reply(500, ""), McpClient.Reply(200, "{}"))) {
            try {
                McpClient.tools({ _, _, _, _ -> reply }, url, "", McpClient.Session())
                fail("${reply.status} ${reply.body} is not a list of tools")
            } catch (e: McpClient.McpError) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test fun `a null in a server's answer is nothing, not the word null`() {
        // The phone's own JSON reads a null as the word "null" unless it is asked first (JsonText.kt).
        val o = JSONObject("{\"title\":null,\"name\":\"search\",\"n\":3}")
        assertEquals("", o.str("title"))
        assertEquals("", o.str("missing"))
        assertEquals("search", o.str("name"))
        assertEquals("3", o.str("n"))
        var asked = 0
        val server = McpClient.Transport { _, _, body, _ ->
            asked++
            val t = JSONObject().put("name", "search").put("title", JSONObject.NULL).put("description", JSONObject.NULL).put("annotations", JSONObject().put("readOnlyHint", JSONObject.NULL))
            McpClient.Reply(200, ok(JSONObject(body).opt("id"), JSONObject().put("tools", JSONArray().put(t)).put("nextCursor", JSONObject.NULL)))
        }
        val found = McpClient.tools(server, url, "", McpClient.Session())
        // A null cursor is the last page: the server is asked once.
        assertEquals(1, asked)
        assertEquals("", found.single().title)
        assertEquals("", found.single().description)
        assertNull(found.single().readOnly)
        // And a tool that answers with no structured content says nothing, not "null".
        assertEquals("", McpClient.text(JSONObject().put("content", JSONArray()).put("structuredContent", JSONObject.NULL)))
    }

    @Test fun `a server that sends its list again under a new cursor each time is asked a few pages, and each tool kept once`() {
        var asked = 0
        val looping = McpClient.Transport { _, _, body, _ ->
            asked++
            McpClient.Reply(200, ok(JSONObject(body).opt("id"), JSONObject().put("tools", tools).put("nextCursor", "page-$asked")))
        }
        val found = McpClient.tools(looping, url, "", McpClient.Session())
        assertEquals(listOf("list_events", "create_event", "search"), found.map { it.name })
        assertEquals(McpClient.MAX_PAGES, asked)
    }

    /** A server that takes requests with or without the handshake, as many do, and counts the tool calls that reached it. */
    private class Lenient(val answerCall: (Int, Any?) -> McpClient.Reply) {
        var calls = 0
        val methods = ArrayList<String>()
        val transport = McpClient.Transport { _, _, body, _ ->
            val o = JSONObject(body)
            val id = o.opt("id")
            methods += o.optString("method")
            when (o.optString("method")) {
                "initialize" -> McpClient.Reply(200, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject().put("protocolVersion", "2025-06-18")).toString())
                "notifications/initialized" -> McpClient.Reply(202, "")
                "tools/list" -> McpClient.Reply(200, JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "create_event")))).toString())
                else -> { calls++; answerCall(calls, id) }
            }
        }
    }

    @Test fun `a tool that makes something is called once, though its answer cannot be read`() {
        // The keyboard has just started: nothing is known of the server. Its answer to the call is cut short.
        val server = Lenient { _, _ -> McpClient.Reply(200, "event: message\ndata: {\"jsonrpc\":\"2.0\",") }
        try {
            McpClient.call(server.transport, url, "", McpClient.Session(), "create_event", JSONObject().put("title", "Dentist"))
            fail("an answer that cannot be read is an error")
        } catch (e: McpClient.McpError) {
            assertTrue(e.message!!, e.message!!.contains("not one this keyboard can read"))
        }
        // Asked which kind of server it is with a request that changes nothing, then the call, once.
        assertEquals(1, server.calls)
        assertEquals(listOf("tools/list", "tools/call"), server.methods)
    }

    @Test fun `a tool refused for its arguments says so in the server's words, on a server not yet known`() {
        val server = Lenient { _, id -> McpClient.Reply(200, error(id, -32602, "Invalid arguments: title is required")) }
        try {
            McpClient.call(server.transport, url, "", McpClient.Session(), "create_event", JSONObject())
            fail("a refused call is an error")
        } catch (e: McpClient.McpError) {
            assertEquals("The server said: Invalid arguments: title is required", e.message)
        }
        assertEquals(1, server.calls)
    }
}
