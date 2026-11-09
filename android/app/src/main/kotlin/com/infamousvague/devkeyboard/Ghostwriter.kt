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
 * Never in password fields or while incognito. The keyboard service owns the row; this object owns the request.
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
    class Job(val id: Int, val request: String, val tag: String, val selection: Boolean = false) {
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
        return launch(Job(ids.incrementAndGet(), request, tag), done) {
            val (system, user) = prompt(appContext, prefs, request, before, after, app)
            AiClient.complete(prefs, system, user, maxTokens = 4000, effort = "low")
        }
    }

    /** Mockingbird's "In my voice": the [selected] text rewritten as you, to replace the selection. */
    fun rewrite(context: Context, prefs: KeyboardPrefs, selected: String, app: String?, done: (Job) -> Unit): Job {
        val appContext = context.applicationContext
        return launch(Job(ids.incrementAndGet(), "In my voice", selected, selection = true), done) {
            Mockingbird.rewrite(appContext, prefs, selected, app)
        }
    }

    private fun launch(job: Job, done: (Job) -> Unit, work: () -> String): Job {
        worker.execute {
            runCatching(work).onSuccess { job.result = clean(it) }.onFailure { job.error = it.message ?: "Couldn't write it" }
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

    /** The system prompt (instructions, voice, memory) and the user message (the field and the request). */
    fun prompt(context: Context, prefs: KeyboardPrefs, request: String, before: String, after: String, app: String?): Pair<String, String> {
        val kind = AppContext.kind(app)
        val memory = if (prefs.ghostwriterMemory) Mockingbird.relevant(context, "$request ${before.takeLast(300)}", app) else emptyList()
        val clips = if (prefs.ghostwriterClipboard && Plugins.isOn(prefs, Plugins.CLIPBOARD)) {
            var left = 2500
            ClipHistory.all(context).take(6).map { it.text.take(1200) }.takeWhile { c -> (left > 0).also { left -= c.length } }
        } else emptyList()
        val system = buildString {
            append(
                """
                You are Ghostwriter, built into this person's phone keyboard. In a text field they typed a request
                between << and >>; the text you write replaces it, so write the text itself, as them, in their voice.
                - If the request names something to write ("engineering standup tldr", "decline politely"), write it.
                - If it is a question ("why does X happen"), write the answer they would put in this field.
                - Ground facts in their past messages, their clipboard and the text around the request. Never invent
                  specifics (names, numbers, dates, causes, links). Where a needed fact is missing, leave a short
                  [bracketed blank] for them to fill instead of guessing.
                - Fit the field: in a chat app keep it as short and casual as their texts; elsewhere match their
                  writing. Use the field's language. Continue naturally from the text before the request.
                - Reply with the replacement text only: no quotes, no preface, no explanation of what you did.
                """.trimIndent(),
            )
            append("\n\n").append(Mockingbird.voiceSection(context))
            if (memory.isNotEmpty()) {
                append("\n\nTHEIR PAST MESSAGES (most relevant first; their memory, for facts and voice)\n")
                memory.forEach { append("---\n").append(it.text).append('\n') }
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
