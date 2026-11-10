package com.infamousvague.devkeyboard

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ghost.md's notes online, for Ghostwriter when Ghost.md isn't on this phone (or doesn't answer): the same hosted
 * connector Claude reads notes through, https://ghostmarkdown.com/api/mcp.
 *
 * Sign-in is that server's own OAuth 2.1: this app registers itself once, opens the browser on the server's sign-in
 * page (the password stays in the page; the server holds the account's key for the session, as it says there), and
 * comes back to `ghostkbd://ghost-md/callback` with a code, swapped for tokens with PKCE. The tokens stay in this app's
 * private storage and refresh themselves.
 *
 * Searching: the server's `search_notes` matches the whole query as one phrase, so each word is searched on its own
 * and the notes found for more words come first; the best few are read whole with `read_note`. One JSON-RPC request
 * per call: the server is stateless.
 *
 * Every call goes through [Net], under the ledger's GHOST_SIGN_IN, GHOST_SEARCH or GHOST_SIGN_OUT: to
 * ghostmarkdown.com and nowhere else, whatever addresses the server's own metadata names, and never while Offline is on.
 */
object GhostOnline {
    const val SERVER = "https://ghostmarkdown.com/api/mcp"
    const val REDIRECT = "ghostkbd://ghost-md/callback"
    private const val PREFS = "ghost_online"

    class GhostError(message: String) : Exception(message)

    data class Note(val id: String, val title: String, val snippet: String, val body: String?)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun signedIn(context: Context) = prefs(context).getString("refresh", null) != null || prefs(context).getString("access", null) != null

    // --- sign-in ---------------------------------------------------------------------------------------------------

    private fun endpoints(context: Context, path: NetPath): JSONObject =
        JSONObject(http(context, path, "GET", "$SERVER/.well-known/oauth-authorization-server", null, null).second)

    /** The browser address that starts sign-in: registers this app with the server first, the first time. Blocking. */
    fun signInUrl(context: Context): String {
        val p = prefs(context)
        val path = NetPath.GHOST_SIGN_IN
        val meta = endpoints(context, path)
        // The page the browser is sent to must be Ghost.md's own too, whatever the metadata says.
        Net.refusal(KeyboardPrefs(context), path, meta.getString("authorization_endpoint"))?.let { throw GhostError(it) }
        var clientId = p.getString("client", null)
        if (clientId == null) {
            val reg = JSONObject()
                .put("client_name", "Ghost Keyboard")
                .put("redirect_uris", JSONArray().put(REDIRECT))
                .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
                .put("response_types", JSONArray().put("code"))
                .put("token_endpoint_auth_method", "none")
            val (code, text) = http(context, path, "POST", meta.getString("registration_endpoint"), "application/json", reg.toString())
            if (code !in 200..299) throw GhostError("Ghost.md wouldn't register the keyboard ($code)")
            clientId = JSONObject(text).getString("client_id")
            p.edit().putString("client", clientId).apply()
        }
        val verifier = random(48)
        val state = random(16)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        p.edit().putString("verifier", verifier).putString("state", state).putString("token_endpoint", meta.getString("token_endpoint"))
            .putString("revocation_endpoint", meta.optString("revocation_endpoint")).apply()
        return Uri.parse(meta.getString("authorization_endpoint")).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", "notes")
            .appendQueryParameter("resource", SERVER)
            .build().toString()
    }

    /** The browser came back to [REDIRECT]: swap its code for tokens. Blocking. */
    fun finish(context: Context, uri: Uri) {
        val p = prefs(context)
        uri.getQueryParameter("error")?.let { throw GhostError("Sign-in didn't finish: $it") }
        val code = uri.getQueryParameter("code") ?: throw GhostError("Sign-in came back without a code")
        if (uri.getQueryParameter("state") != p.getString("state", null)) throw GhostError("Sign-in came back from a different attempt; try again")
        token(context, NetPath.GHOST_SIGN_IN, mapOf(
            "grant_type" to "authorization_code", "code" to code, "redirect_uri" to REDIRECT,
            "client_id" to p.getString("client", "")!!, "code_verifier" to p.getString("verifier", "")!!, "resource" to SERVER,
        ))
        p.edit().remove("verifier").remove("state").apply()
    }

    /** Swaps a sign-in code or a refresh token for tokens; [path] is why (signing in, or a search whose sign-in ran out). */
    private fun token(context: Context, path: NetPath, form: Map<String, String>) {
        val p = prefs(context)
        val endpoint = p.getString("token_endpoint", null) ?: endpoints(context, path).getString("token_endpoint")
        val body = form.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val (code, text) = http(context, path, "POST", endpoint, "application/x-www-form-urlencoded", body)
        if (code !in 200..299) {
            if (form["grant_type"] == "refresh_token") clearTokens(context)
            throw GhostError(if (form["grant_type"] == "refresh_token") "Sign in to Ghost.md again (Settings › Ghostwriter)" else "Ghost.md refused the sign-in ($code)")
        }
        val o = JSONObject(text)
        p.edit().putString("access", o.getString("access_token"))
            .putString("refresh", o.optString("refresh_token").ifEmpty { p.getString("refresh", null) })
            .putLong("expires", System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000).apply()
    }

    private fun clearTokens(context: Context) = prefs(context).edit().remove("access").remove("refresh").remove("expires").apply()

    /**
     * Signs out: the server ends the session (and drops the account's key), and the tokens go. Blocking. Refused while
     * Offline is on, rather than forgetting the tokens here and leaving the session alive on the server.
     */
    fun signOut(context: Context) {
        if (Net.offline(KeyboardPrefs(context))) throw GhostError(Net.OFFLINE_MESSAGE)
        val p = prefs(context)
        val refresh = p.getString("refresh", null)
        val endpoint = p.getString("revocation_endpoint", null)
        if (refresh != null && !endpoint.isNullOrEmpty()) runCatching {
            http(context, NetPath.GHOST_SIGN_OUT, "POST", endpoint, "application/x-www-form-urlencoded", "token=${URLEncoder.encode(refresh, "UTF-8")}&client_id=${URLEncoder.encode(p.getString("client", "")!!, "UTF-8")}")
        }
        clearTokens(context)
    }

    // --- the notes ---------------------------------------------------------------------------------------------------

    private fun access(context: Context): String {
        val p = prefs(context)
        val access = p.getString("access", null)
        if (access != null && System.currentTimeMillis() < p.getLong("expires", 0)) return access
        val refresh = p.getString("refresh", null) ?: throw GhostError("Sign in to Ghost.md in Settings › Ghostwriter")
        token(context, NetPath.GHOST_SEARCH, mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to p.getString("client", "")!!, "resource" to SERVER))
        return p.getString("access", null)!!
    }

    private val ids = java.util.concurrent.atomic.AtomicInteger()

    /** Calls one of the server's tools; its answer (the tool's JSON text) parsed. Refreshes once on a 401. */
    private fun tool(context: Context, name: String, args: JSONObject, retried: Boolean = false): JSONObject {
        val rpc = JSONObject().put("jsonrpc", "2.0").put("id", ids.incrementAndGet()).put("method", "tools/call")
            .put("params", JSONObject().put("name", name).put("arguments", args))
        val (code, text) = http(context, NetPath.GHOST_SEARCH, "POST", SERVER, "application/json", rpc.toString(), access(context))
        if (code == 401 && !retried) {
            prefs(context).edit().putLong("expires", 0).apply()
            return tool(context, name, args, retried = true)
        }
        if (code == 401) { clearTokens(context); throw GhostError("Sign in to Ghost.md again (Settings › Ghostwriter)") }
        if (code !in 200..299) throw GhostError("Ghost.md's server said no ($code)")
        // JSON, or the same as one server-sent event.
        val json = if (text.trimStart().startsWith("{")) text else text.lines().filter { it.startsWith("data:") }.joinToString("") { it.removePrefix("data:").trim() }
        val o = JSONObject(json)
        o.optJSONObject("error")?.let { throw GhostError("Ghost.md: ${it.optString("message")}") }
        val result = o.optJSONObject("result") ?: throw GhostError("Ghost.md sent back nothing")
        val first = result.optJSONArray("content")?.optJSONObject(0)?.optString("text").orEmpty()
        if (result.optBoolean("isError")) throw GhostError("Ghost.md: ${first.take(160)}")
        return runCatching { JSONObject(first) }.getOrElse { JSONObject().put("text", first) }
    }

    /**
     * Notes matching [words]: each word searched on its own, the notes found for more of them first, newer as found;
     * the best [read] read whole. Blocking.
     */
    fun search(context: Context, words: List<String>, limit: Int = 6, read: Int = 3): List<Note> {
        if (words.isEmpty() || !signedIn(context)) return emptyList()
        val found = LinkedHashMap<String, Pair<Note, Int>>()
        for (w in words.take(4)) {
            val notes = tool(context, "search_notes", JSONObject().put("query", w).put("limit", 12)).optJSONArray("notes") ?: continue
            for (i in 0 until notes.length()) {
                val n = notes.optJSONObject(i) ?: continue
                val id = n.optString("id")
                if (id.isEmpty()) continue
                val seen = found[id]
                found[id] = (seen?.first ?: Note(id, n.optString("title"), n.optString("snippet"), null)) to ((seen?.second ?: 0) + 1)
            }
        }
        val best = found.values.sortedByDescending { it.second }.map { it.first }.take(limit)
        return best.mapIndexed { i, n ->
            if (i >= read) n else runCatching {
                val note = tool(context, "read_note", JSONObject().put("id", n.id))
                n.copy(body = (note.optJSONObject("note")?.optString("body") ?: note.optString("body")).take(3000).ifEmpty { null })
            }.getOrDefault(n)
        }
    }

    // --- HTTP ----------------------------------------------------------------------------------------------------------

    private fun random(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /** One request to Ghost.md's server, through the guard: its status and body. A redirect comes back as its status, unfollowed. */
    private fun http(context: Context, path: NetPath, method: String, url: String, type: String?, body: String?, bearer: String? = null): Pair<Int, String> {
        val settings = KeyboardPrefs(context)
        val c = try {
            Net.open(settings, path, url)
        } catch (e: java.io.IOException) {
            throw GhostError(Net.message(settings, e, "Couldn't reach Ghost.md: ${e.message ?: "no connection"}"))
        }
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 45_000
            c.setRequestProperty("Accept", "application/json, text/event-stream")
            bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", type ?: "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to text
        } catch (e: java.io.IOException) {
            throw GhostError(Net.message(settings, e, "Couldn't reach Ghost.md: ${e.message ?: "no connection"}"))
        } finally {
            c.disconnect()
        }
    }
}
