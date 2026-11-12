package com.infamousvague.devkeyboard

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small Model Context Protocol client, for the Connectors plugin (Connectors.kt): it asks a server the person added
 * which tools it has, and calls one. Tools only: no resources, prompts, sampling or anything a server might ask of
 * its client.
 *
 * It speaks to a server over HTTP as the specification's "Streamable HTTP" has it: one POST for each JSON-RPC request,
 * answered with JSON or with an event stream that ends in the answer. Two generations of server are met, and it is
 * not known beforehand which a server is:
 *
 * - the specification of 2026-07-28 ([VERSION]) has no handshake and no session: each request says its version in a
 *   header and in `_meta`, and names its method (and for a tool call the tool) in headers of their own;
 * - the specifications up to 2025-11-25 ([LEGACY]) start with `initialize`, which the server may answer with a session
 *   id to be sent from then on, then `notifications/initialized`, and only then take requests.
 *
 * So a request is first made the new way, which the new servers need and many older ones answer too. A server that
 * refuses it for its shape or its version (not for who is asking) is then given the handshake and asked again, and
 * what worked is remembered for the [Session].
 *
 * Read from modelcontextprotocol.io/specification on 2026-11-12, through a summary and not the pages themselves; the
 * names of a tool's hints (`readOnlyHint`) are from the 2025 specifications as remembered. See docs/connectors.md
 * for what has and has not been tried against a real server.
 *
 * Nothing here touches Android or opens a connection: a [Transport] does that (Connectors.kt gives it one that goes
 * through Net.kt), so the protocol can be tried against a made-up server (McpClientTest).
 */
object McpClient {
    const val VERSION = "2026-07-28"
    const val LEGACY = "2025-11-25"

    /** The legacy versions a server may answer `initialize` with and be understood. */
    val LEGACY_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

    /** The most tools read from one server, and the longest an answer kept from a tool, in characters. */
    const val MAX_TOOLS = 200
    const val MAX_RESULT = 6000

    /** What went wrong, in words for the person. [signIn] says the server wants a sign-in this client cannot do. */
    class McpError(message: String, val signIn: Boolean = false) : Exception(message)

    /** A server's answer to one POST: its HTTP [status], its [body] (JSON, or an event stream as text), and the two headers that matter. */
    class Reply(val status: Int, val body: String, val contentType: String? = null, val session: String? = null)

    /**
     * Makes one POST. [done] is for an event stream: given what has been read so far, it says whether the answer is
     * there, so a stream the server leaves open is not waited on.
     */
    fun interface Transport { fun post(url: String, headers: Map<String, String>, body: String, done: (String) -> Boolean): Reply }

    /** A tool as its server describes it. [readOnly] is its `readOnlyHint` where the server gives one: its own word, not a promise. */
    data class Tool(val name: String, val title: String, val description: String, val schema: JSONObject, val readOnly: Boolean?)

    /**
     * What is known of a server while the keyboard lives: which generation it is ([legacy]; null until one way has
     * worked), the version agreed, its session id where it gave one, and whether the handshake has been made.
     */
    class Session {
        var legacy: Boolean? = null
        var version: String = VERSION
        var id: String? = null
        var shaken = false
    }

    private val ids = AtomicInteger()

    /** The tools [url] offers, in the server's order: every page of them, up to [MAX_TOOLS]. */
    fun tools(transport: Transport, url: String, token: String, session: Session): List<Tool> {
        val out = ArrayList<Tool>()
        var cursor: String? = null
        do {
            val result = request(transport, url, token, session, "tools/list", JSONObject().apply { cursor?.let { put("cursor", it) } }, null)
            val list = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until list.length()) {
                val t = list.optJSONObject(i) ?: continue
                val name = t.optString("name")
                if (name.isBlank()) continue
                val hints = t.optJSONObject("annotations")
                out += Tool(
                    name, t.optString("title").ifBlank { hints?.optString("title").orEmpty() }, t.optString("description"),
                    t.optJSONObject("inputSchema") ?: JSONObject().put("type", "object"),
                    if (hints != null && hints.has("readOnlyHint")) hints.optBoolean("readOnlyHint") else null,
                )
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() && it != cursor }
        } while (cursor != null && out.size < MAX_TOOLS)
        return out.take(MAX_TOOLS)
    }

    /**
     * Calls [name] with [args] and gives back what it said as text, cut to [MAX_RESULT]. A tool that says it failed
     * is an [McpError] with its own words: the AI is told, and can say so or try another way.
     */
    fun call(transport: Transport, url: String, token: String, session: Session, name: String, args: JSONObject): String {
        val result = request(transport, url, token, session, "tools/call", JSONObject().put("name", name).put("arguments", args), name)
        val text = text(result)
        if (result.optBoolean("isError")) throw McpError(text.ifBlank { "The tool failed" }.take(400))
        return text.take(MAX_RESULT)
    }

    /** A tool result as text: its text items, a word for what cannot be shown in words, or its structured content. */
    fun text(result: JSONObject): String {
        val parts = ArrayList<String>()
        val content = result.optJSONArray("content")
        if (content != null) for (i in 0 until content.length()) {
            val item = content.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "text" -> item.optString("text").takeIf { it.isNotBlank() }?.let { parts += it }
                "resource" -> item.optJSONObject("resource")?.optString("text")?.takeIf { it.isNotBlank() }?.let { parts += it }
                "resource_link" -> parts += "[${item.optString("name").ifBlank { "a link" }}: ${item.optString("uri")}]"
                "image" -> parts += "[a picture]"
                "audio" -> parts += "[a recording]"
            }
        }
        if (parts.isEmpty()) result.opt("structuredContent")?.let { parts += it.toString() }
        return parts.joinToString("\n").trim()
    }

    // --- one request, the new way or the old ------------------------------------------------------------------------

    private fun request(transport: Transport, url: String, token: String, session: Session, method: String, params: JSONObject, tool: String?): JSONObject = synchronized(session) {
        if (session.legacy != true) {
            try {
                val result = send(transport, url, token, method, params, statelessHeaders(session.version, method, tool), session.version)
                session.legacy = false
                return@synchronized result
            } catch (e: Refused) {
                // Known to be a server of the new kind, and it still refuses: that is the answer.
                if (session.legacy == false && e.versions.isEmpty()) throw McpError(e.message ?: "The server refused the request")
                // It names versions of the new kind it does speak: asked again in the newest of them.
                val newer = e.versions.filter { it >= "2026" && it != session.version }.maxOrNull()
                if (newer != null) {
                    session.version = newer
                    session.legacy = false
                    return@synchronized send(transport, url, token, method, params, statelessHeaders(newer, method, tool), newer)
                }
                // Otherwise it is taken for a server of the older kind, which wants the handshake first.
            }
        }
        legacy(transport, url, token, session, method, params, retry = true)
    }

    private fun statelessHeaders(version: String, method: String, tool: String?) = buildMap {
        put("MCP-Protocol-Version", version)
        put("Mcp-Method", method)
        if (tool != null) put("Mcp-Name", tool)
    }

    private fun legacy(transport: Transport, url: String, token: String, session: Session, method: String, params: JSONObject, retry: Boolean): JSONObject {
        if (session.legacy != true || !session.shaken) handshake(transport, url, token, session)
        val headers = buildMap {
            put("MCP-Protocol-Version", session.version)
            session.id?.let { put("Mcp-Session-Id", it) }
        }
        return try {
            send(transport, url, token, method, params, headers, null)
        } catch (e: Refused) {
            // A session the server has let go (it answers 404 to its id): one new handshake, and the request again.
            if (retry && e.status == 404 && session.id != null) {
                session.id = null
                session.shaken = false
                legacy(transport, url, token, session, method, params, retry = false)
            } else throw McpError(e.message ?: "The server refused the request")
        }
    }

    /** `initialize`, then `notifications/initialized`: what a server of the older kind wants before anything else. */
    private fun handshake(transport: Transport, url: String, token: String, session: Session) {
        val id = ids.incrementAndGet()
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", "initialize").put(
            "params",
            JSONObject().put("protocolVersion", LEGACY).put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "Ghost Keyboard").put("version", "1")),
        )
        val reply = post(transport, url, token, emptyMap(), body, id)
        val result = try { answer(reply, id) } catch (e: Refused) { throw McpError(e.message ?: "The server refused to start") }
        val version = result.optString("protocolVersion")
        if (version !in LEGACY_VERSIONS) throw McpError("The server speaks a version of the protocol this keyboard does not (${version.ifBlank { "it did not say which" }.take(20)})")
        session.legacy = true
        session.version = version
        session.id = reply.session?.takeIf { it.isNotBlank() }
        session.shaken = true
        // A notification has no answer: the server says 202 and nothing more. Whatever it says, the handshake is done.
        val note = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        runCatching {
            transport.post(url, baseHeaders(token) + buildMap { put("MCP-Protocol-Version", version); session.id?.let { put("Mcp-Session-Id", it) } }, note.toString()) { true }
        }
    }

    // --- the wire ----------------------------------------------------------------------------------------------------

    /** A request the server would not take as it was made: its HTTP [status], and the [versions] it says it speaks, where it says. */
    private class Refused(message: String, val status: Int, val versions: List<String> = emptyList()) : Exception(message)

    private fun baseHeaders(token: String) = buildMap {
        put("Accept", "application/json, text/event-stream")
        put("Content-Type", "application/json")
        if (token.isNotBlank()) put("Authorization", "Bearer ${token.trim()}")
    }

    private fun post(transport: Transport, url: String, token: String, headers: Map<String, String>, body: JSONObject, id: Int): Reply =
        transport.post(url, baseHeaders(token) + headers, body.toString()) { read -> found(read, id) != null }

    private fun send(transport: Transport, url: String, token: String, method: String, params: JSONObject, headers: Map<String, String>, meta: String?): JSONObject {
        val id = ids.incrementAndGet()
        val p = JSONObject(params.toString())
        if (meta != null) p.put("_meta", JSONObject().put("io.modelcontextprotocol/protocolVersion", meta))
        val body = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", p)
        return answer(post(transport, url, token, headers, body, id), id)
    }

    /** The result in [reply] for request [id]. Who is asking being refused is an [McpError]; any other refusal is [Refused]. */
    private fun answer(reply: Reply, id: Int): JSONObject {
        if (reply.status == 401 || reply.status == 403) {
            throw McpError(
                if (reply.status == 401) "This server wants a sign-in. Give it a token in Settings › Connectors, if it offers one: signing in through a browser is not built yet."
                else "This server refused the token (403).",
                signIn = reply.status == 401,
            )
        }
        val message = found(reply.body, id)
        val error = message?.optJSONObject("error")
        if (error != null) {
            val supported = error.optJSONObject("data")?.optJSONArray("supported")
            val versions = if (supported == null) emptyList() else (0 until supported.length()).map { supported.optString(it) }.filter { it.isNotBlank() }
            throw Refused("The server said: ${error.optString("message").ifBlank { "error ${error.optInt("code")}" }.take(200)}", reply.status, versions)
        }
        if (reply.status !in 200..299) throw Refused("The server said no (${reply.status})", reply.status)
        return message?.optJSONObject("result") ?: throw Refused("The server's answer was not one this keyboard can read", reply.status)
    }

    /**
     * The JSON-RPC answer to request [id] in [body]: the body itself where it is JSON, or the event of a stream that
     * carries it (other events, the server's notes on the way, are passed over). Null where it is not there (yet).
     */
    fun found(body: String, id: Int): JSONObject? {
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return pick(trimmed, id)
        // An event stream: events parted by a blank line, each one's data lines joined.
        for (event in body.replace("\r\n", "\n").split("\n\n")) {
            val data = event.lines().filter { it.startsWith("data:") }.joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
            if (data.isBlank()) continue
            pick(data, id)?.let { return it }
        }
        return null
    }

    private fun pick(json: String, id: Int): JSONObject? {
        val t = json.trim()
        fun mine(o: JSONObject?) = o?.takeIf { (it.has("result") || it.has("error")) && (it.opt("id")?.toString() == id.toString() || it.isNull("id") && it.has("error")) }
        return runCatching {
            if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).firstNotNullOfOrNull { mine(a.optJSONObject(it)) } } else mine(JSONObject(t))
        }.getOrNull()
    }
}
