package com.infamousvague.devkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Ghostwriter's GitHub search: the commits behind "<<posthog updates we made>>".
 *
 * The request's own words (less the asking words: summarize, updates, we, made…) are searched for in the commit
 * messages of the repositories the person owns and the organizations they belong to, newest first. Searched by owner
 * rather than author, because many commits are authored by an AI agent rather than the person. "today", "yesterday",
 * "this week", "last week" and "this month" narrow the dates. With the person's own token, straight to GitHub,
 * through [Net] (the ledger's GITHUB_SEARCH and GITHUB_CHECK): never while Offline is on.
 */
object GitHubCommits {
    data class Commit(val repo: String, val date: String, val message: String)
    data class Account(val login: String, val owners: List<String>)

    private const val API = "https://api.github.com"
    private const val CACHE = "github_cache"

    class GitHubError(message: String) : Exception(message)

    /**
     * The token's account and the owners searched (the account, then its organizations), cached per token. [path] is
     * why it is asked: a Ghostwriter request's search, or Settings' "Check the token".
     */
    fun account(context: Context, token: String, refresh: Boolean = false, path: NetPath = NetPath.GITHUB_SEARCH): Account {
        val prefs = KeyboardPrefs(context)
        val cache = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
        val tag = token.hashCode().toString()
        if (!refresh && cache.getString("token", null) == tag) {
            val login = cache.getString("login", "")!!
            if (login.isNotEmpty()) return Account(login, cache.getString("owners", "")!!.split(',').filter { it.isNotBlank() })
        }
        val login = JSONObject(get(prefs, path, "$API/user", token)).optString("login").ifEmpty { throw GitHubError("GitHub didn't say whose token this is") }
        val orgs = runCatching { JSONArray(get(prefs, path, "$API/user/orgs?per_page=50", token)) }.getOrNull()
        val owners = listOf(login) + (0 until (orgs?.length() ?: 0)).mapNotNull { orgs?.optJSONObject(it)?.optString("login") }.filter { it.isNotBlank() }
        cache.edit().putString("token", tag).putString("login", login).putString("owners", owners.joinToString(",")).apply()
        return Account(login, owners)
    }

    private val ASKING = setOf(
        "summarize", "summarise", "summary", "update", "updates", "we", "made", "make", "did", "done", "change", "changes",
        "changed", "recent", "recently", "latest", "new", "post", "write", "list", "tell", "about", "what", "which", "the",
        "and", "for", "our", "my", "me", "to", "of", "in", "on", "a", "an", "all", "any", "some", "with", "from", "that",
        "this", "these", "those", "is", "are", "was", "were", "been", "have", "has", "had", "do", "does", "please", "pls",
        "today", "yesterday", "week", "month", "last", "past", "since", "tldr", "standup", "work", "worked", "working",
        "commit", "commits", "pushed", "shipped", "release", "released", "notes", "status", "progress", "github", "repo",
        "how", "why", "when", "where", "who", "it", "its", "be", "by", "so", "up", "out", "into", "i", "you", "your",
        "then", "also", "just", "get", "give", "not", "no", "dont", "doesnt", "isnt", "didnt", "wont", "us", "them", "they", "can", "could", "should", "would", "team", "slack",
    )

    /** The words worth searching for: what the request is about, not how it asks. */
    fun keywords(request: String): List<String> =
        request.lowercase().split(Regex("[^\\p{L}\\p{N}_.-]+")).map { it.trim('.', '-') }
            .filter { it.length >= 2 && it !in ASKING && !it.all(Char::isDigit) }.distinct().take(4)

    /** The earliest commit date the request asks about, if it names a window. */
    fun since(request: String, today: LocalDate = LocalDate.now()): LocalDate? {
        val r = request.lowercase()
        return when {
            "today" in r -> today
            "yesterday" in r -> today.minusDays(1)
            "last week" in r || "past week" in r -> today.minusDays(14)
            "this week" in r -> today.minusDays(today.dayOfWeek.value.toLong() - 1)
            "this month" in r -> today.withDayOfMonth(1)
            "last month" in r || "past month" in r -> today.minusMonths(2)
            else -> null
        }
    }

    /**
     * Commits matching the request, newest first, within [budget] characters. Each word is searched on its own
     * across every owner, and the commits that turn up for more words come first. Empty when nothing matches.
     */
    fun search(context: Context, token: String, request: String, budget: Int = 9000): List<Commit> {
        val words = keywords(request)
        if (words.isEmpty()) return emptyList()
        val prefs = KeyboardPrefs(context)
        val owners = account(context, token).owners
        val window = since(request)?.let { " committer-date:>=$it" }.orEmpty()
        val hits = LinkedHashMap<String, Pair<Commit, Int>>()
        for (word in words) for (owner in owners.take(6)) {
            val q = "$word ${if (owner == owners.first()) "user" else "org"}:$owner$window"
            val url = "$API/search/commits?q=${URLEncoder.encode(q, "UTF-8")}&sort=committer-date&order=desc&per_page=20"
            val items = runCatching { JSONObject(get(prefs, NetPath.GITHUB_SEARCH, url, token)).optJSONArray("items") }.getOrNull() ?: continue
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val sha = item.optString("sha")
                val commit = item.optJSONObject("commit") ?: continue
                val date = commit.optJSONObject("committer")?.optString("date").orEmpty().take(10)
                val message = commit.optString("message").lines().filter { !it.startsWith("Co-Authored-By") && !it.startsWith("Claude-Session") }
                    .joinToString("\n").trim().take(900)
                val repo = item.optJSONObject("repository")?.optString("full_name").orEmpty()
                val seen = hits[sha]
                hits[sha] = (seen?.first ?: Commit(repo, date, message)) to ((seen?.second ?: 0) + 1)
            }
        }
        val best = hits.values.sortedWith(compareByDescending<Pair<Commit, Int>> { it.second }.thenByDescending { it.first.date }).map { it.first }
        return Ghostwriter.fitting(best, budget) { it.message.length + 40 }.sortedByDescending { it.date }
    }

    /** One GET of GitHub's API, through the guard; a redirect is an error, so the token goes to api.github.com only. */
    private fun get(prefs: KeyboardPrefs, path: NetPath, url: String, token: String): String {
        val c = try {
            Net.open(prefs, path, url)
        } catch (e: java.io.IOException) {
            throw GitHubError(Net.message(prefs, e, "Couldn't reach GitHub: ${e.message ?: "no connection"}"))
        }
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull().orEmpty()
                throw GitHubError(when (code) {
                    401 -> "GitHub refused the token"
                    403, 429 -> "GitHub's search limit: try again in a minute"
                    else -> "GitHub said no ($code)"
                } + if (message.isNotBlank() && code != 401) ": ${message.take(120)}" else "")
            }
            return text
        } catch (e: GitHubError) {
            throw e
        } catch (e: java.io.IOException) {
            throw GitHubError(Net.message(prefs, e, "Couldn't reach GitHub: ${e.message ?: "no connection"}"))
        } finally {
            c.disconnect()
        }
    }
}
