package com.infamousvague.devkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The Mockingbird plugin: the AI learns how you write and text.
 *
 * - Samples: what you finish typing (a message sent, a field left) is kept on this phone, in the app's private files,
 *   up to [MAX_SAMPLES]. Chat apps (AppContext's CHAT) are your texting; everything else is your writing. Nothing is
 *   kept from password, email-address, web-address or number fields, while incognito is on (or the field asks for no
 *   learning), or with collecting switched off.
 * - Slips: a sample is what was left in the field, after autocorrect; a slip autocorrect missed (or made with it off)
 *   would otherwise be read as how you write. So [SampleCleaner] repairs the obvious ones as a sample is kept, the AI
 *   that trains is told that only a habit you repeat is style, and the AI that writes is told never to copy a
 *   misspelling ([VoiceRules]).
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
    /** The fewest samples training takes. */
    private const val MIN_TRAIN_SAMPLES = 10
    private const val SAMPLES_FILE = "mockingbird_samples.json"
    private const val PROFILE_FILE = "mockingbird_profile.json"

    /**
     * Something the person typed. [text] is what the AI reads: the sample with its obvious slips repaired. [raw] is
     * it exactly as typed, kept only where the two differ, so a word can be put back once the person has typed it
     * often enough to mean it. [cleaned] is the [SampleCleaner.VERSION] that cleaned it, 0 for not yet.
     */
    data class Sample(val text: String, val chat: Boolean, val app: String, val at: Long, val raw: String? = null, val cleaned: Int = 0) {
        /** As it was typed. */
        val typed: String get() = raw ?: text
    }

    /** The style profile; [version] is the [VoiceRules.PROFILE_VERSION] of the instructions it was trained under. */
    data class Profile(val text: String, val at: Long, val samples: Int, val provider: String, val model: String, val version: Int = VoiceRules.PROFILE_VERSION)

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
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                Sample(it.getString("t"), it.optBoolean("c"), it.optString("a"), it.getLong("at"), it.optString("r").ifEmpty { null }, it.optInt("v"))
            }.toMutableList()
        }.getOrElse { mutableListOf() }
        cache = list
        return list
    }

    private fun save(context: Context, list: List<Sample>) {
        val a = JSONArray()
        list.forEach { s ->
            val o = JSONObject().put("t", s.text).put("c", s.chat).put("a", s.app).put("at", s.at)
            if (s.raw != null) o.put("r", s.raw)
            if (s.cleaned > 0) o.put("v", s.cleaned)
            a.put(o)
        }
        val file = File(context.filesDir, SAMPLES_FILE)
        val tmp = File(context.filesDir, "$SAMPLES_FILE.tmp")
        tmp.writeText(a.toString())
        tmp.renameTo(file)
    }

    private val FILL_TAG = Regex("<<[^<>\\n]*>>")
    private val URLISH = Regex("^(https?://|www\\.)\\S+$")

    /**
     * Keeps [raw], typed in [app], as a sample when it says something about how you write. A message that grows from
     * one already kept (the same draft, read again) replaces it rather than being kept twice. Its obvious slips are
     * repaired on the way in ([SampleCleaner]), on this object's worker thread, never the key press's.
     */
    fun record(context: Context, raw: String, app: String) = record(context, raw, app, AppContext.kind(app) == AppKind.CHAT)

    /** A sample from a typing profile: texting where [chat], else writing. */
    fun recordImported(context: Context, raw: String, chat: Boolean) = record(context, raw, if (chat) "imported.chat" else "imported", chat)

    private fun record(context: Context, raw: String, app: String, chat: Boolean) {
        val text = raw.replace(FILL_TAG, "").replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim().take(MAX_SAMPLE_CHARS)
        if (text.length < 12 || text.split(Regex("\\s+")).size < 3) return
        if (URLISH.matches(text) || text.count { it.isLetter() } < text.length / 2) return
        val typed = Sample(text, chat, app, System.currentTimeMillis())
        worker.execute {
            // Drafts are compared as typed, not as cleaned: the same draft read again must still be seen as the same.
            val others = synchronized(lock) {
                val list = load(context)
                val last = list.lastOrNull()
                if (last != null && last.app == app && (text.startsWith(last.typed) || last.typed.startsWith(text))) {
                    if (text.length <= last.typed.length) return@execute
                    list.removeAt(list.size - 1)
                }
                list.removeAll { it.typed == text }
                list.map { it.typed }
            }
            // The cleaning is done outside the lock, so Settings and the keyboard never wait on it.
            val lexicon = lexicon(context, wait = false)
            val sample = cleaned(typed, others, lexicon)
            val stale = synchronized(lock) {
                val list = load(context)
                list.add(sample)
                while (list.size > MAX_SAMPLES) list.removeAt(0)
                save(context, list)
                list.any { it.cleaned < SampleCleaner.VERSION }
            }
            // Samples kept before the cleaner (or while no dictionary was loaded) are cleaned now, once.
            if (stale && lexicon != null) cleanStored(context, lexicon)
        }
    }

    // --- cleaning the slips out ---------------------------------------------------------------------------------------

    /**
     * The keyboard's own Autocorrect while the keyboard is running, set by its service: its dictionaries are loaded
     * already, and it knows the person's languages and learned words. Null when only Settings is open.
     */
    @Volatile var speller: Autocorrect? = null

    /**
     * The dictionaries to clean with: the keyboard's where it is running, else, with [wait], ones loaded here for
     * the person's languages and learned words (Settings › Train now with the keyboard not up). Null when there
     * are none and [wait] is off: the sample is kept as typed and cleaned before the next training. Blocks while
     * dictionaries load, so it is for the worker thread only.
     */
    private fun lexicon(context: Context, wait: Boolean): SampleCleaner.Lexicon? {
        speller?.let { shared ->
            var waited = 0
            while (wait && !shared.isReady && waited < 8000) { Thread.sleep(100); waited += 100 }
            if (shared.isReady) return shared.lexicon()
        }
        if (!wait) return null
        val prefs = KeyboardPrefs(context)
        val own = Autocorrect(context)
        own.setLearned(prefs.learnedWords)
        own.loadNow(prefs.languages)
        return own.lexicon()
    }

    /**
     * [sample] with its obvious slips repaired. [others] is every other sample as typed: a word that would be
     * changed is first counted across them, since one the person types repeatedly is theirs. Without a [lexicon]
     * the sample is kept as typed and marked as not yet cleaned.
     */
    private fun cleaned(sample: Sample, others: List<String>, lexicon: SampleCleaner.Lexicon?): Sample {
        val typed = sample.typed
        if (lexicon == null || !lexicon.ready) return sample.copy(text = typed, raw = null, cleaned = 0)
        var text = SampleCleaner.clean(typed, lexicon)
        if (text != typed) {
            val deliberate = SampleCleaner.repeated(others + typed, lexicon, SampleCleaner.suspects(typed, lexicon))
            if (deliberate.isNotEmpty()) text = SampleCleaner.clean(typed, lexicon, deliberate)
        }
        return sample.copy(text = text, raw = typed.takeIf { it != text }, cleaned = SampleCleaner.VERSION)
    }

    /**
     * Every stored sample cleaned again from what was typed, with the repeated words counted over all of them: the
     * samples kept before the cleaner get their first cleaning, and a word since typed often enough to be the
     * person's own goes back into the samples it was taken out of. At most [MAX_SAMPLES] short texts, each
     * unknown word looked up once. Worker thread only.
     */
    private fun cleanStored(context: Context, lexicon: SampleCleaner.Lexicon) {
        if (!lexicon.ready) return
        val before = synchronized(lock) { load(context).toList() }
        if (before.isEmpty()) return
        val deliberate = SampleCleaner.repeated(before.map { it.typed }, lexicon)
        val after = before.map { s ->
            val text = SampleCleaner.clean(s.typed, lexicon, deliberate)
            s.copy(text = text, raw = s.typed.takeIf { it != text }, cleaned = SampleCleaner.VERSION)
        }
        synchronized(lock) {
            val list = load(context)
            // Only this thread adds and removes samples; clear() is the one thing that can have come in between.
            if (list.size != before.size || list.indices.any { list[it] !== before[it] }) return
            if (after != before) {
                for (i in after.indices) list[i] = after[i]
                save(context, list)
            }
        }
    }

    /**
     * The samples, every one cleaned first: what a typing profile exports. Waits for the worker (a training in
     * flight included, up to a minute), so not for the main thread.
     */
    fun cleanedSamples(context: Context): List<Sample> {
        val app = context.applicationContext
        runCatching { worker.submit { lexicon(app, wait = true)?.let { cleanStored(app, it) } }.get(60, TimeUnit.SECONDS) }
        return samples(app)
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
        Profile(o.getString("text"), o.getLong("at"), o.optInt("samples"), o.optString("provider"), o.optString("model"), o.optInt("v", 1))
    }.getOrNull()

    /**
     * True for a profile trained under the old instructions, which asked for the "typos they leave in": it may
     * describe slips as habits, so it is due a retrain. One imported from a typing profile file was not trained
     * here and is the person's choice: never outdated.
     */
    fun outdated(p: Profile): Boolean = p.version < VoiceRules.PROFILE_VERSION && !(p.model.isEmpty() && p.provider.startsWith("Imported"))

    private fun saveProfile(context: Context, p: Profile) {
        File(context.filesDir, PROFILE_FILE).writeText(
            JSONObject().put("text", p.text).put("at", p.at).put("samples", p.samples).put("provider", p.provider).put("model", p.model).put("v", p.version).toString(),
        )
    }

    /** A style profile from a typing profile file, in place of the trained one; [source] says whose it is. */
    fun importProfile(context: Context, text: String, source: String) {
        saveProfile(context, Profile(text.take(6000), System.currentTimeMillis(), samples(context).size, source, ""))
    }

    /** Trains a new profile in the background; [done] gets an error message, or null when it worked. */
    fun train(context: Context, done: ((String?) -> Unit)? = null) {
        if (training) return
        training = true
        lastError = null
        val app = context.applicationContext
        worker.execute {
            val error = runCatching {
                val prefs = KeyboardPrefs(app)
                // The AI reads cleaned samples; a cleaner that fails must not stop the training.
                runCatching { lexicon(app, wait = true)?.let { cleanStored(app, it) } }
                val all = samples(app)
                if (all.size < MIN_TRAIN_SAMPLES) throw AiClient.AiError("Type a little more first: ${all.size} of $MIN_TRAIN_SAMPLES samples")
                val chat = pick(all.filter { it.chat }, 14_000)
                val writing = pick(all.filter { !it.chat }, 10_000)
                val user = buildString {
                    append("TEXTING (${chat.size} messages)\n")
                    chat.forEach { append("---\n").append(it.text).append('\n') }
                    append("\nWRITING (${writing.size} pieces)\n")
                    writing.forEach { append("---\n").append(it.text).append('\n') }
                }
                val text = AiClient.complete(prefs, VoiceRules.TRAIN_SYSTEM, user, maxTokens = 8000, effort = "medium")
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

    /** When an [outdated] profile's retrain was last started by itself, so a failing provider is asked hourly at most. */
    @Volatile private var outdatedRetrainAt = 0L

    /**
     * Retrains by itself when switched on, the AI is set up, enough new samples have come in, and a day has passed.
     * An [outdated] profile does not wait for the samples or the day: it is retrained as soon as there is something
     * to train on.
     */
    fun maybeAutoTrain(context: Context, prefs: KeyboardPrefs) {
        if (!prefs.mockingbirdAutoTrain || training || !AiClient.ready(prefs)) return
        val count = samples(context).size
        val p = profile(context)
        val now = System.currentTimeMillis()
        val due = when {
            p == null -> count >= AUTO_TRAIN_AFTER
            outdated(p) -> (count >= MIN_TRAIN_SAMPLES && now - outdatedRetrainAt > 3_600_000L).also { if (it) outdatedRetrainAt = now }
            else -> count - p.samples >= AUTO_TRAIN_AFTER && now - p.at > 20 * 3_600_000L
        }
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

    /**
     * The profile as a system-prompt section, or a note that there isn't one yet; then, always,
     * [VoiceRules.SPELLING_RULE], so neither an old profile nor a slip in a sample is ever copied.
     */
    fun voiceSection(context: Context): String = VoiceRules.voiceSection(profile(context)?.text)

    /** [text] rewritten in your voice: texting or writing by [app]. Blocking. */
    fun rewrite(context: Context, prefs: KeyboardPrefs, text: String, app: String?): String {
        val chat = AppContext.kind(app) == AppKind.CHAT
        val examples = relevant(context, text, app, 3000)
        // Built line by line: trimIndent can't trim around an interpolated multi-line profile.
        val system = buildString {
            append("Rewrite the user's text so it sounds exactly like this person ${if (chat) "texting" else "writing"}. ")
            append("Keep the meaning, facts and language; change only voice, wording, punctuation and length to match them. ")
            append(VoiceRules.REWRITING).append(' ')
            append("Reply with the rewritten text alone: no quotes, no preface.\n\n")
            append(voiceSection(context))
            if (examples.isNotEmpty()) append("\n\nTHINGS THEY HAVE TYPED\n").append(examples.joinToString("\n---\n") { it.text })
        }
        return AiClient.complete(prefs, system, text, maxTokens = 3000, effort = "low")
    }
}
