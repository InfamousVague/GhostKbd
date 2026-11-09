package com.infamousvague.devkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The Mockingbird plugin: the AI learns how you write and text.
 *
 * - Samples: what you finish typing (a message sent, a field left) is kept on this phone, in the app's private files,
 *   up to [MAX_SAMPLES]. Chat apps (AppContext's CHAT) are your texting; everything else is your writing. Nothing is
 *   kept from password, email-address, web-address or number fields, while incognito is on (or the field asks for no
 *   learning), or with collecting switched off.
 * - Training: the AI you chose reads a selection of samples and writes a style profile, your voice in a page: how you
 *   text and how you write. The samples stay on the phone; only that selection goes, once, to train. Retrains itself
 *   (at most daily, once [AUTO_TRAIN_AFTER] new samples have come in) unless switched off.
 * - Using it: Ghostwriter (<<…>>) and "In my voice" send the profile and a few samples like the thing being written.
 *
 * It is a profile, not fine-tuning: it works the same with every provider and costs one small request to train.
 */
object Mockingbird {
    const val MAX_SAMPLES = 1500
    private const val MAX_SAMPLE_CHARS = 1500
    const val AUTO_TRAIN_AFTER = 50
    private const val SAMPLES_FILE = "mockingbird_samples.json"
    private const val PROFILE_FILE = "mockingbird_profile.json"

    data class Sample(val text: String, val chat: Boolean, val app: String, val at: Long)
    data class Profile(val text: String, val at: Long, val samples: Int, val provider: String, val model: String)

    /** What training is doing, for Settings. */
    @Volatile var training = false
        private set
    @Volatile var lastError: String? = null
        private set

    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()
    @Volatile private var cache: MutableList<Sample>? = null

    // --- samples ------------------------------------------------------------------------------------------------------

    fun samples(context: Context): List<Sample> = synchronized(lock) { load(context).toList() }

    private fun load(context: Context): MutableList<Sample> {
        cache?.let { return it }
        val file = File(context.filesDir, SAMPLES_FILE)
        val list = runCatching {
            val a = JSONArray(file.readText())
            (0 until a.length()).map { a.getJSONObject(it) }.map { Sample(it.getString("t"), it.optBoolean("c"), it.optString("a"), it.getLong("at")) }.toMutableList()
        }.getOrElse { mutableListOf() }
        cache = list
        return list
    }

    private fun save(context: Context, list: List<Sample>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.text).put("c", it.chat).put("a", it.app).put("at", it.at)) }
        val file = File(context.filesDir, SAMPLES_FILE)
        val tmp = File(context.filesDir, "$SAMPLES_FILE.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    private val FILL_TAG = Regex("<<[^<>\\n]*>>")
    private val URLISH = Regex("^(https?://|www\\.)\\S+$")

    /**
     * Keeps [raw], typed in [app], as a sample when it says something about how you write. A message that grows from
     * one already kept (the same draft, read again) replaces it rather than being kept twice.
     */
    fun record(context: Context, raw: String, app: String) = record(context, raw, app, AppContext.kind(app) == AppKind.CHAT)

    /** A sample from a typing profile: texting where [chat], else writing. */
    fun recordImported(context: Context, raw: String, chat: Boolean) = record(context, raw, if (chat) "imported.chat" else "imported", chat)

    private fun record(context: Context, raw: String, app: String, chat: Boolean) {
        val text = raw.replace(FILL_TAG, "").replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim().take(MAX_SAMPLE_CHARS)
        if (text.length < 12 || text.split(Regex("\\s+")).size < 3) return
        if (URLISH.matches(text) || text.count { it.isLetter() } < text.length / 2) return
        val sample = Sample(text, chat, app, System.currentTimeMillis())
        worker.execute {
            synchronized(lock) {
                val list = load(context)
                val last = list.lastOrNull()
                if (last != null && last.app == app && (text.startsWith(last.text) || last.text.startsWith(text))) {
                    if (text.length <= last.text.length) return@execute
                    list.removeAt(list.size - 1)
                }
                list.removeAll { it.text == text }
                list.add(sample)
                while (list.size > MAX_SAMPLES) list.removeAt(0)
                save(context, list)
            }
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        cache = mutableListOf()
        File(context.filesDir, SAMPLES_FILE).delete()
        File(context.filesDir, PROFILE_FILE).delete()
        lastError = null
    }

    // --- the profile --------------------------------------------------------------------------------------------------

    fun profile(context: Context): Profile? = runCatching {
        val o = JSONObject(File(context.filesDir, PROFILE_FILE).readText())
        Profile(o.getString("text"), o.getLong("at"), o.optInt("samples"), o.optString("provider"), o.optString("model"))
    }.getOrNull()

    private fun saveProfile(context: Context, p: Profile) {
        File(context.filesDir, PROFILE_FILE).writeText(
            JSONObject().put("text", p.text).put("at", p.at).put("samples", p.samples).put("provider", p.provider).put("model", p.model).toString(),
        )
    }

    /** A style profile from a typing profile file, in place of the trained one; [source] says whose it is. */
    fun importProfile(context: Context, text: String, source: String) {
        saveProfile(context, Profile(text.take(6000), System.currentTimeMillis(), samples(context).size, source, ""))
    }

    private val TRAIN_SYSTEM = """
        You study one person's writing so that another AI can write exactly as they do. You get real things they typed
        on their phone: TEXTING (messages in chat apps) and WRITING (everything else: email, notes, posts, forms).

        Write their style profile, addressed to the AI that will imitate them ("They write…"). Cover, for texting and
        for writing separately where they differ:
        - capitalisation, punctuation, spelling habits and typos they leave in
        - length and rhythm: how long messages and sentences run, line breaks, lists
        - tone and register: warmth, bluntness, humour, sarcasm, swearing, formality, and how it shifts by context
        - vocabulary: words and phrases they reach for, slang, abbreviations, filler, words they never use
        - emoji, emoticons and reactions: which, how often, where
        - how they open, ask, agree, decline, apologise, thank and sign off
        - technical or work vocabulary and how they explain things
        End with 8 to 12 short phrases quoted from the samples that sound most like them.

        Describe style only. Leave out private facts: names other than common greetings, addresses, numbers,
        passwords, health, money, or anything about other people. Plain text with short headings, under 700 words.
    """.trimIndent()

    /** Trains a new profile in the background; [done] gets an error message, or null when it worked. */
    fun train(context: Context, done: ((String?) -> Unit)? = null) {
        if (training) return
        training = true
        lastError = null
        val app = context.applicationContext
        worker.execute {
            val error = runCatching {
                val prefs = KeyboardPrefs(app)
                val all = samples(app)
                if (all.size < 10) throw AiClient.AiError("Type a little more first: ${all.size} of 10 samples")
                val chat = pick(all.filter { it.chat }, 14_000)
                val writing = pick(all.filter { !it.chat }, 10_000)
                val user = buildString {
                    append("TEXTING (${chat.size} messages)\n")
                    chat.forEach { append("---\n").append(it.text).append('\n') }
                    append("\nWRITING (${writing.size} pieces)\n")
                    writing.forEach { append("---\n").append(it.text).append('\n') }
                }
                val text = AiClient.complete(prefs, TRAIN_SYSTEM, user, maxTokens = 8000, effort = "medium")
                val setup = AiClient.setup(prefs)
                saveProfile(app, Profile(text, System.currentTimeMillis(), all.size, setup.provider.name, setup.model))
            }.exceptionOrNull()?.let { it.message ?: "Training failed" }
            lastError = error
            training = false
            done?.invoke(error)
        }
    }

    /** Newest first, as many as fit in [budget] characters, then back in the order they were typed. */
    private fun pick(list: List<Sample>, budget: Int): List<Sample> {
        var left = budget
        return list.asReversed().takeWhile { s -> (left > 0).also { left -= s.text.length + 4 } }.reversed()
    }

    /** Retrains by itself when switched on, the AI is set up, enough new samples have come in, and a day has passed. */
    fun maybeAutoTrain(context: Context, prefs: KeyboardPrefs) {
        if (!prefs.mockingbirdAutoTrain || training || !AiClient.ready(prefs)) return
        val count = samples(context).size
        val p = profile(context)
        val due = if (p == null) count >= AUTO_TRAIN_AFTER else count - p.samples >= AUTO_TRAIN_AFTER && System.currentTimeMillis() - p.at > 20 * 3_600_000L
        if (due) train(context)
    }

    // --- using it -----------------------------------------------------------------------------------------------------

    private val STOP = setOf(
        "the", "and", "for", "you", "your", "are", "but", "not", "with", "that", "this", "was", "have", "has", "had", "its",
        "from", "they", "them", "what", "when", "why", "how", "who", "can", "just", "about", "into", "out", "our", "all",
        "will", "would", "there", "their", "been", "were", "then", "than", "also", "some", "any", "get", "got", "did", "does",
    )

    private fun words(text: String) = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in STOP }

    /**
     * Past messages that share the most words with [query] (rarer words count for more), then the latest from [app];
     * at most [budget] characters. The "memory" Ghostwriter answers from.
     */
    fun relevant(context: Context, query: String, app: String?, budget: Int = 6000): List<Sample> {
        val all = samples(context)
        if (all.isEmpty()) return emptyList()
        val q = words(query).toSet()
        val df = HashMap<String, Int>()
        val bags = all.map { s -> words(s.text).toSet().also { b -> b.forEach { df[it] = (df[it] ?: 0) + 1 } } }
        val n = all.size.toDouble()
        val scored = all.indices
            .map { i -> i to q.sumOf { w -> if (w in bags[i]) Math.log(1 + n / (df[w] ?: 1)) else 0.0 } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second + it.first / n * 0.5 }
            .take(15).map { all[it.first] }
        val recent = all.asReversed().filter { it.app == app }.take(6)
        var left = budget
        return (scored + recent).distinct().takeWhile { s -> (left > 0).also { left -= s.text.length } }
    }

    /** The profile as a system-prompt section, or a note that there isn't one yet. */
    fun voiceSection(context: Context): String = profile(context)?.let { "THEIR STYLE PROFILE\n${it.text}" }
        ?: "THEIR STYLE PROFILE\n(Not trained yet: copy the voice of the past messages below.)"

    /** [text] rewritten in your voice: texting or writing by [app]. Blocking. */
    fun rewrite(context: Context, prefs: KeyboardPrefs, text: String, app: String?): String {
        val chat = AppContext.kind(app) == AppKind.CHAT
        val examples = relevant(context, text, app, 3000)
        // Built line by line: trimIndent can't trim around an interpolated multi-line profile.
        val system = buildString {
            append("Rewrite the user's text so it sounds exactly like this person ${if (chat) "texting" else "writing"}. ")
            append("Keep the meaning, facts and language; change only voice, wording, punctuation and length to match them. ")
            append("Reply with the rewritten text alone: no quotes, no preface.\n\n")
            append(voiceSection(context))
            if (examples.isNotEmpty()) append("\n\nTHINGS THEY HAVE TYPED\n").append(examples.joinToString("\n---\n") { it.text })
        }
        return AiClient.complete(prefs, system, text, maxTokens = 3000, effort = "low")
    }
}
