package com.infamousvague.devkeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Ghostwriter plugin: type <<what you want>> in any app and the AI writes it there, as you.
 *
 * `<<engineering standup tldr>>` or `<<why do the posthog metrics not fire on waitlist signup>>`: once the closing >>
 * is typed (or Fill is tapped in the row), the request goes to the AI you chose with what it needs to answer as you —
 * Mockingbird's style profile, the past messages that share its words (your "memory"), the text around the request in
 * the field, and with its switch on, your recent clipboard — and the answer replaces the tag. << >> rather than {{ }}
 * so templates and code that use braces are left alone.
 *
 * Never in password fields or while incognito, and never while Offline is on (Net.kt). The keyboard service owns the
 * row; this object owns the request.
 */
object Ghostwriter {
    /** A request just closed before the cursor: "…<<standup tldr>>". */
    val CLOSED = Regex("<<(?=[^<>\\n]*\\p{L})([^<>\\n]{2,400})>>$")

    /** An unfilled request the cursor is inside or just after, within [text] around the cursor. */
    private val ANY = Regex("<<(?=[^<>\\n]*\\p{L})([^<>\\n]{2,400})>>")

    /** The request the cursor is on, given the text [before] and [after] it: the request, and the whole tag. */
    fun tagAt(before: String, after: String): Pair<String, String>? {
        val joined = before + after
        val cursor = before.length
        return ANY.findAll(joined).firstOrNull { cursor > it.range.first && cursor <= it.range.last + 1 }?.let { it.groupValues[1].trim() to it.value }
    }

    /** A request in flight: [tag] is the text the answer replaces (the <<tag>>, or the selection for a rewrite). */
    class Job(val id: Int, val request: String, val tag: String, val selection: Boolean = false, val mode: String? = null) {
        @Volatile var result: String? = null
        @Volatile var error: String? = null
        @Volatile var cancelled = false
        val running get() = result == null && error == null && !cancelled
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger()

    /** Starts filling [request]; [done] runs on the main thread unless the job is cancelled first. */
    fun start(context: Context, prefs: KeyboardPrefs, request: String, tag: String, before: String, after: String, app: String?, done: (Job) -> Unit): Job {
        val appContext = context.applicationContext
        val job = Job(ids.incrementAndGet(), request, tag)
        return launch(prefs, job, done) {
            val (system, user) = prompt(appContext, prefs, request, before, after, app)
            // Debug builds only: the key "debug-echo" answers without any AI, to try the typing and the row.
            if (Updater.isDebuggable(appContext) && prefs.aiKey(prefs.aiProvider) == "debug-echo") {
                Thread.sleep(800)
                // Says which notes the prompt carried, so a test can see they arrived.
                val notes = system.substringAfter("THEIR NOTES", "").substringBefore("THEIR GITHUB").lines()
                    .filter { it.startsWith("--- ") }.joinToString("; ") { it.removePrefix("--- ") }
                return@launch "Shipped the PostHog waitlist events today. (debug: notes ${notes.ifEmpty { "none" }})"
            }
            // The person's connectors (Connectors.kt): the tools of theirs that are switched on, for the AI to call
            // where the request needs a fact one of them holds. With none on, the request is the one it always was.
            val offered = if (Connectors.armed(prefs)) Connectors.offered(Connectors.all(prefs)) else emptyList()
            if (offered.isEmpty()) return@launch AiClient.complete(prefs, NetPath.GHOSTWRITER, system, user, maxTokens = 4000, effort = "low", webSearch = prefs.ghostwriterWeb)
            val tools = offered.map { AiTools.Tool(it.aiName, it.description, runCatching { org.json.JSONObject(it.tool.schema) }.getOrElse { org.json.JSONObject().put("type", "object") }) }
            var calls = 0
            try {
                AiClient.complete(prefs, NetPath.GHOSTWRITER, system + Connectors.section(offered), user, maxTokens = 4000, effort = "low", webSearch = prefs.ghostwriterWeb, tools = tools) { name, args ->
                    // Stopped, or its field left: no tool is called for an answer nobody is waiting for, and the AI
                    // is not asked again.
                    if (job.cancelled) throw AiTools.Stopped()
                    val tool = offered.firstOrNull { it.aiName == name } ?: return@complete "There is no tool called $name."
                    if (++calls > Connectors.MAX_CALLS) return@complete "No more tools may be called for this request: answer with what you have."
                    Connectors.call(prefs, tool, args)
                }.also { Connectors.note(prefs, null) }
            } catch (e: AiClient.AiError) {
                // Offline, an AI that could not be reached or would not take the key, is said as it is. So is any
                // failure after a tool has been called: asked again without the tools, the AI would answer as if
                // what the tool did had not happened, and a Retry would have it done twice. Anything else may be
                // the tools themselves (a provider or model that will not take them): the request is made again as
                // it was before there were connectors, so they never cost anyone their answer, and once that has
                // answered, Settings › Connectors says what happened.
                if (job.cancelled || calls > 0 || Net.offline(prefs) || !AiTools.plainMayAnswer(e.message)) throw e
                AiClient.complete(prefs, NetPath.GHOSTWRITER, system, user, maxTokens = 4000, effort = "low", webSearch = prefs.ghostwriterWeb)
                    .also { Connectors.note(prefs, "${java.time.LocalDate.now()}: your AI answered without the connectors. ${e.message}") }
            }
        }
    }

    /** Mockingbird's "In my voice": the [selected] text rewritten as you, to replace the selection. */
    fun rewrite(context: Context, prefs: KeyboardPrefs, selected: String, app: String?, done: (Job) -> Unit): Job {
        val appContext = context.applicationContext
        return launch(prefs, Job(ids.incrementAndGet(), "In my voice", selected, selection = true), done) {
            Mockingbird.rewrite(appContext, prefs, selected, app)
        }
    }

    /** The Rewrite plugin: the [selected] text rewritten as [mode] asks, to replace the selection. */
    fun rewriteAs(prefs: KeyboardPrefs, selected: String, mode: Rewrite.Mode, done: (Job) -> Unit): Job =
        launch(prefs, Job(ids.incrementAndGet(), mode.label, selected, selection = true, mode = mode.id), done) { Rewrite.run(prefs, mode, selected) }

    /**
     * The Prompt enhancer plugin: the [selected] text rewritten as a clearer prompt, to replace the selection.
     * [spoken] where it is what a dictation just put in ("As a prompt").
     */
    fun enhance(context: Context, prefs: KeyboardPrefs, selected: String, app: String?, spoken: Boolean = false, done: (Job) -> Unit): Job {
        val appContext = context.applicationContext
        return launch(prefs, Job(ids.incrementAndGet(), "Enhance", selected, selection = true, mode = if (spoken) Enhance.SAID else Enhance.MODE), done) {
            // Debug builds only, as for a <<request>>: the key "debug-echo" answers without any AI, saying where the
            // prompt was going, so the chip, the row and the undo can be tried on an emulator.
            if (Updater.isDebuggable(appContext) && prefs.aiKey(prefs.aiProvider) == "debug-echo") {
                Thread.sleep(800)
                return@launch "Enhanced (debug, ${AppContext.kind(app).name.lowercase()}${if (spoken) ", spoken" else ""}): ${selected.trim().take(120)}"
            }
            Enhance.run(prefs, selected, app, spoken)
        }
    }

    /** Dictation's Tidy: the words a take put in ([selected]), with what speech adds taken out, to replace them. */
    fun tidy(context: Context, prefs: KeyboardPrefs, selected: String, done: (Job) -> Unit): Job {
        val appContext = context.applicationContext
        return launch(prefs, Job(ids.incrementAndGet(), "Tidy", selected, selection = true, mode = DictationWords.TIDY), done) {
            if (Updater.isDebuggable(appContext) && prefs.aiKey(prefs.aiProvider) == "debug-echo") {
                Thread.sleep(800)
                return@launch "Tidied (debug): ${selected.trim().take(120)}"
            }
            DictationWords.tidy(prefs, selected)
        }
    }

    /**
     * Runs [work] on the worker and hands [job] back on the main thread. With Offline on the work is never started
     * (no notes read, no commits searched): the job ends with "Offline is on", which the row shows as its error.
     */
    private fun launch(prefs: KeyboardPrefs, job: Job, done: (Job) -> Unit, work: () -> String): Job {
        worker.execute {
            runCatching { if (Net.offline(prefs)) throw AiClient.AiError(Net.OFFLINE_MESSAGE) else work() }
                .onSuccess { job.result = clean(it) }.onFailure { job.error = it.message ?: "Couldn't write it" }
            main.post { if (!job.cancelled) done(job) }
        }
        return job
    }

    /** Takes off the wrapping models sometimes add anyway: quotes round the whole answer, a code fence. */
    private fun clean(text: String): String {
        var t = text.trim()
        if (t.startsWith("```") && t.endsWith("```")) t = t.removePrefix("```").substringAfter('\n').removeSuffix("```").trim()
        if (t.length > 1 && t.first() == '"' && t.last() == '"' && t.count { it == '"' } == 2) t = t.substring(1, t.length - 1)
        return t
    }

    /**
     * As many of [items], in order, as fit in [budget] characters, each counting [size]: the list ends before the
     * first that would cross it. So a budget is a cap, and what Settings › Offline says is sent is the most that is.
     */
    inline fun <T> fitting(items: List<T>, budget: Int, size: (T) -> Int): List<T> {
        var left = budget
        return items.takeWhile { item -> size(item).let { n -> (n <= left).also { fits -> if (fits) left -= n } } }
    }

    /** The system prompt (instructions, voice, memory) and the user message (the field and the request). */
    /** Ghost.md notes found for some words: title and text, where they came from, or why they couldn't be searched. */
    class Notes(val notes: List<Pair<String, String>>, val from: String, val note: String?)

    /**
     * Ghost.md's notes that match [words]: from Ghost.md on this phone, else online through its connector when signed
     * in. Blocking.
     */
    fun notes(context: Context, words: List<String>): Notes {
        if (words.isEmpty()) return Notes(emptyList(), "", null)
        val local = GhostMd.search(context, words.joinToString(" "), 6).map { it.title to it.body.ifBlank { it.snippet } }
        if (local.isNotEmpty() || !GhostOnline.signedIn(context)) return Notes(local, "Ghost.md on this phone", null)
        return runCatching { Notes(GhostOnline.search(context, words).map { it.title to (it.body ?: it.snippet) }, "Ghost.md (online)", null) }
            .getOrElse { Notes(emptyList(), "Ghost.md (online)", it.message) }
    }

    /** The notes as a prompt section, at most about 9,000 characters, best first. */
    fun notesSection(found: Notes, words: List<String>): String = buildString {
        if (found.notes.isNotEmpty()) {
            append("\nTHEIR NOTES from ").append(found.from).append(" matching ").append(words.joinToString(", ")).append(" (best first)\n")
            var left = 9000
            for ((title, body) in found.notes) {
                if (left <= 0) break
                val part = body.take(left.coerceAtMost(2500))
                left -= part.length
                append("--- ").append(title).append('\n').append(part).append('\n')
            }
        } else if (found.note != null) {
            append("\n(Their Ghost.md notes couldn't be searched: ").append(found.note).append(")\n")
        }
    }

    fun prompt(context: Context, prefs: KeyboardPrefs, request: String, before: String, after: String, app: String?): Pair<String, String> {
        val kind = AppContext.kind(app)
        val memory = if (prefs.ghostwriterMemory) Mockingbird.relevant(context, "$request ${before.takeLast(300)}", app) else emptyList()
        val clips = if (prefs.ghostwriterClipboard && Plugins.isOn(prefs, Plugins.CLIPBOARD)) {
            // The newest copies, whatever is pinned: a pin from last month is not "recent".
            fitting(ClipHistory.recent(context).take(6).map { it.text.take(1200) }, 2500) { it.length }
        } else emptyList()
        // The work behind "<<posthog updates we made>>": the person's commits that match, from GitHub.
        var githubNote: String? = null
        val commits = if (prefs.ghostwriterGithub && prefs.githubToken.isNotBlank()) {
            runCatching { GitHubCommits.search(context, prefs.githubToken.trim(), request) }
                .onFailure { githubNote = it.message }.getOrDefault(emptyList())
        } else emptyList()
        val words = GitHubCommits.keywords(request)
        val found = if (prefs.ghostwriterNotes) notes(context, words) else Notes(emptyList(), "", null)
        val system = buildString {
            append(
                """
                You are Ghostwriter, built into this person's phone keyboard. In a text field they typed a request
                between << and >>; the text you write replaces it, so write the text itself, as them, in their voice.
                - If the request names something to write ("engineering standup tldr", "decline politely"), write it.
                - If it is a question ("why does X happen"), write the answer they would put in this field.
                - Facts about them (their work, plans, people, what they did) come from their notes, GitHub
                  commits, past messages, clipboard and the text around the request. Work updates come from the commits: say what
                  changed and why in plain words, grouped sensibly, without hashes or file lists. Public facts (news, results, dates, figures, how things
                  work) you may look up on the web when you have search: do, rather than guess or leave them out.
                  Never invent specifics. Leave a short [bracketed blank] only for a fact you could not find.
                - Fit the field: in a chat app keep it as short and casual as their texts; elsewhere match their
                  writing. Use the field's language. Continue naturally from the text before the request.
                - ${VoiceRules.WRITING}
                - Reply with the replacement text only: no quotes, no preface, no sources, citations or links unless
                  asked for, and no explanation of what you did or searched.
                """.trimIndent(),
            )
            append("\n\n").append(Mockingbird.voiceSection(context))
            if (memory.isNotEmpty()) {
                append("\n\nTHEIR PAST MESSAGES (most relevant first; their memory, for facts and voice)\n")
                memory.forEach { append("---\n").append(it.text).append('\n') }
            }
            append(notesSection(found, words))
            if (commits.isNotEmpty()) {
                append("\nTHEIR GITHUB COMMITS matching ").append(words.joinToString(", ")).append(" (newest first; what they and their AI agents actually changed)\n")
                commits.forEach { append("---\n").append(it.date).append(' ').append(it.repo).append('\n').append(it.message).append('\n') }
            } else if (githubNote != null) {
                append("\n(Their GitHub couldn't be searched: ").append(githubNote).append(")\n")
            } else if (prefs.ghostwriterGithub && prefs.githubToken.isNotBlank() && words.isNotEmpty()) {
                append("\n(No GitHub commits of theirs mention ").append(words.joinToString(", ")).append(".)\n")
            }
            if (clips.isNotEmpty()) {
                append("\nTHEIR RECENT CLIPBOARD (newest first)\n")
                clips.forEach { append("---\n").append(it).append('\n') }
            }
        }
        val user = buildString {
            append("APP: ").append(app ?: "unknown").append(" (").append(if (kind == AppKind.CHAT) "chat: texting" else kind.name.lowercase()).append(")\n")
            append("TODAY: ").append(java.time.LocalDate.now()).append('\n')
            append("TEXT BEFORE THE REQUEST:\n").append(before.takeLast(1500).ifBlank { "(nothing)" }).append('\n')
            append("TEXT AFTER THE REQUEST:\n").append(after.take(400).ifBlank { "(nothing)" }).append('\n')
            append("REQUEST: ").append(request)
        }
        return system to user
    }
}
