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
 *   would otherwise be read as how you write. So [SampleCleaner] repairs the obvious ones as a sample is kept (only
 *   where the slip shows and the right word is one you use; a word you type three times is yours, and goes back
 *   into the samples it was taken out of), the AI that trains is told that only a habit you repeat is style, and
 *   the AI that writes is told to keep your spelling habits and never to copy a slip ([VoiceRules]).
 * - Training: the AI you chose reads a selection of samples and writes a style profile, your voice in a page: how you
 *   text and how you write. The samples stay on the phone; only that selection goes, once, to train. Retrains itself
 *   unless switched off: at most daily, once [Retrain.AFTER_SAMPLES] new samples have come in, and once more,
 *   without waiting for either, after an update that improved the style rules ([Retrain]).
 * - Using it: Ghostwriter (<<…>>) and "In my voice" send the profile and a few samples like the thing being written.
 *
 * It is a profile, not fine-tuning: it works the same with every provider and costs one small request to train.
 */
object Mockingbird {
    const val MAX_SAMPLES = 1500
    private const val MAX_SAMPLE_CHARS = 1500
    private const val SAMPLES_FILE = "mockingbird_samples.json"
    private const val PROFILE_FILE = "mockingbird_profile.json"
    /** The words judged the person's own ([keptWords]), a JSON list, oldest first. */
    private const val WORDS_FILE = "mockingbird_words.json"
    private const val MAX_KEPT_WORDS = 500
    /** Small things to remember between runs: when an outdated profile's retrain was last tried. */
    private const val STATE = "mockingbird"
    private const val OUTDATED_TRY_AT = "outdated_retrain_at"

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
            val (sample, fresh) = cleaned(context, typed, others, lexicon)
            val stale = synchronized(lock) {
                val list = load(context)
                list.add(sample)
                while (list.size > MAX_SAMPLES) list.removeAt(0)
                save(context, list)
                list.any { it.cleaned < SampleCleaner.VERSION }
            }
            // Samples kept before the cleaner (or while no dictionary was loaded) are cleaned now, once. And when
            // this sample has made a word the person's own, the samples it was taken out of get it back now:
            // Ghostwriter reads them between trainings.
            if (lexicon != null && (stale || fresh)) cleanStored(context, lexicon)
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
        val bundled = context.applicationContext.assets
        val own = Autocorrect { name -> bundled.open(name) }
        own.setLearned(prefs.learnedWords)
        val language = prefs.languageState()
        own.loadNow(language.dictionaries, language.board)
        return own.lexicon()
    }

    @Volatile private var keptCache: Set<String>? = null

    /**
     * The words once judged the person's own: in no dictionary, but typed three times or more
     * ([SampleCleaner.Habits.deliberate]). Remembered apart from the samples, so the oldest sample dropping off
     * the end of the list cannot turn a nickname back into a slip. Oldest first; forgotten with [clear].
     */
    private fun keptWords(context: Context): Set<String> {
        keptCache?.let { return it }
        val words = runCatching {
            val a = JSONArray(File(context.filesDir, WORDS_FILE).readText())
            (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it) }
        }.getOrElse { LinkedHashSet() }
        keptCache = words
        return words
    }

    /**
     * The spellings judged the person's own ([keptWords]), in lower case: next-word prediction and autocorrect take
     * them for words of theirs. Reads a small file the first time, so not for the main thread.
     */
    fun ownWords(context: Context): Set<String> = synchronized(lock) { keptWords(context.applicationContext).mapTo(HashSet()) { it.lowercase() } }

    /** Remembers [words] as [keptWords], the newest [MAX_KEPT_WORDS] of them. Worker thread only. */
    private fun keepWords(context: Context, words: Set<String>) {
        if (words == keptWords(context)) return
        val kept = words.toList().takeLast(MAX_KEPT_WORDS)
        val tmp = File(context.filesDir, "$WORDS_FILE.tmp")
        tmp.writeText(JSONArray(kept).toString())
        tmp.renameTo(File(context.filesDir, WORDS_FILE))
        keptCache = LinkedHashSet(kept)
    }

    /**
     * [sample] with its obvious slips repaired ([SampleCleaner.cleanNew]), and whether a word of it has just become
     * the person's own. [others] is every other sample as typed: a word that would be changed is first counted
     * across them, since one the person types repeatedly is theirs, and so is the word it would become, since a
     * fix is made only to a word they use. Without a [lexicon] the sample is kept as typed and marked as not yet
     * cleaned.
     */
    private fun cleaned(context: Context, sample: Sample, others: List<String>, lexicon: SampleCleaner.Lexicon?): Pair<Sample, Boolean> {
        val typed = sample.typed
        if (lexicon == null || !lexicon.ready) return sample.copy(text = typed, raw = null, cleaned = 0) to false
        val result = SampleCleaner.cleanNew(typed, others, lexicon, keptWords(context))
        if (result.fresh) keepWords(context, result.kept)
        return sample.copy(text = result.text, raw = typed.takeIf { it != result.text }, cleaned = SampleCleaner.VERSION) to result.fresh
    }

    /**
     * Every stored sample cleaned again from what was typed ([SampleCleaner.cleanAll]), with the person's habits
     * counted over all of them: the samples kept before the cleaner get their first cleaning, and a word since
     * typed often enough to be the person's own goes back into the samples it was taken out of, and is
     * remembered ([keptWords]). At most [MAX_SAMPLES] short texts, each unknown word looked up once. Worker
     * thread only.
     */
    private fun cleanStored(context: Context, lexicon: SampleCleaner.Lexicon) {
        if (!lexicon.ready) return
        val before = synchronized(lock) { load(context).toList() }
        if (before.isEmpty()) return
        val (texts, kept) = SampleCleaner.cleanAll(before.map { it.typed }, lexicon, keptWords(context))
        val after = before.mapIndexed { i, s -> s.copy(text = texts[i], raw = s.typed.takeIf { it != texts[i] }, cleaned = SampleCleaner.VERSION) }
        synchronized(lock) {
            val list = load(context)
            // Only this thread adds and removes samples; clear() is the one thing that can have come in between.
            if (list.size != before.size || list.indices.any { list[it] !== before[it] }) return
            keepWords(context, kept)
            if (after != before) {
                for (i in after.indices) list[i] = after[i]
                save(context, list)
            }
        }
    }

    /**
     * The samples, every one cleaned first: what a typing profile exports ([Sample.text], and [Sample.raw] beside
     * it where a slip was repaired). Waits for the worker (a training in
     * flight included, up to a minute), so not for the main thread.
     */
    fun cleanedSamples(context: Context): List<Sample> {
        val app = context.applicationContext
        runCatching { worker.submit { lexicon(app, wait = true)?.let { cleanStored(app, it) } }.get(60, TimeUnit.SECONDS) }
        return samples(app)
    }

    fun clear(context: Context) = synchronized(lock) {
        cache = mutableListOf()
        keptCache = LinkedHashSet()
        File(context.filesDir, SAMPLES_FILE).delete()
        File(context.filesDir, PROFILE_FILE).delete()
        File(context.filesDir, WORDS_FILE).delete()
        lastError = null
    }

    // --- the profile --------------------------------------------------------------------------------------------------

    fun profile(context: Context): Profile? = runCatching {
        val o = JSONObject(File(context.filesDir, PROFILE_FILE).readText())
        Profile(o.getString("text"), o.getLong("at"), o.optInt("samples"), o.optString("provider"), o.optString("model"), o.optInt("v", 1))
    }.getOrNull()

    /**
     * True for a profile trained under the old instructions, which asked for the "typos they leave in": it may
     * describe slips as habits, so it is due a retrain ([Retrain.outdated]). One written elsewhere and imported
     * from a typing-profile file is the person's choice and never outdated; one from Ghost Keyboard's own export
     * keeps the version it was trained under.
     */
    fun outdated(p: Profile): Boolean = Retrain.outdated(p.version, p.provider, p.model)

    private fun saveProfile(context: Context, p: Profile) {
        File(context.filesDir, PROFILE_FILE).writeText(
            JSONObject().put("text", p.text).put("at", p.at).put("samples", p.samples).put("provider", p.provider).put("model", p.model).put("v", p.version).toString(),
        )
    }

    /**
     * A style profile from a typing profile file, in place of the trained one. [source] says whose it is
     * ([Retrain.importedBy]) and [version] which style rules it was written under ([Retrain.importVersion]): an
     * old profile of this keyboard's stays due its retrain after a trip through a file.
     */
    fun importProfile(context: Context, text: String, source: String, version: Int) {
        saveProfile(context, Profile(text.take(6000), System.currentTimeMillis(), samples(context).size, source, "", version))
    }

    /**
     * Trains a new profile in the background; [done] gets an error message, or null when it worked. With Offline on
     * nothing is sent and nothing is recorded as a failure: [done] gets "Offline is on".
     */
    fun train(context: Context, done: ((String?) -> Unit)? = null) {
        if (training) return
        if (Net.offline(KeyboardPrefs(context))) { done?.invoke(Net.OFFLINE_MESSAGE); return }
        training = true
        lastError = null
        val app = context.applicationContext
        worker.execute {
            val error = runCatching {
                val prefs = KeyboardPrefs(app)
                // The AI reads cleaned samples; a cleaner that fails must not stop the training.
                runCatching { lexicon(app, wait = true)?.let { cleanStored(app, it) } }
                val all = samples(app)
                if (all.size < Retrain.MIN_SAMPLES) throw AiClient.AiError("Type a little more first: ${all.size} of ${Retrain.MIN_SAMPLES} samples")
                val chat = pick(all.filter { it.chat }, 14_000)
                val writing = pick(all.filter { !it.chat }, 10_000)
                val user = buildString {
                    append("TEXTING (${chat.size} messages)\n")
                    chat.forEach { append("---\n").append(it.text).append('\n') }
                    append("\nWRITING (${writing.size} pieces)\n")
                    writing.forEach { append("---\n").append(it.text).append('\n') }
                }
                val text = AiClient.complete(prefs, NetPath.TRAIN, VoiceRules.TRAIN_SYSTEM, user, maxTokens = 8000, effort = "medium")
                val setup = AiClient.setup(prefs)
                saveProfile(app, Profile(text, System.currentTimeMillis(), all.size, setup.provider.name, setup.model))
            }.exceptionOrNull()?.let { it.message ?: "Training failed" }
            // Stopped by the Offline switch part-way: not a failure to show once it is off again.
            lastError = error.takeUnless { it == Net.OFFLINE_MESSAGE }
            training = false
            done?.invoke(error)
        }
    }

    /** Newest first, as many as fit in [budget] characters (never over it), then back in the order they were typed. */
    private fun pick(list: List<Sample>, budget: Int): List<Sample> =
        Ghostwriter.fitting(list.asReversed(), budget) { it.text.length + 4 }.reversed()

    /**
     * Retrains by itself when switched on and the AI is set up, where [Retrain.due] says so: enough new samples
     * and a day since the last training, or, for an [outdated] profile, at once (and a day later if that fails;
     * the try is remembered across restarts). Cheap for the caller: the samples and the profile are read on the
     * worker thread, so the keyboard can ask whenever a field is left, whether or not anything was kept from it.
     * Never while Offline is on: the samples go on being kept, and training waits.
     */
    fun maybeAutoTrain(context: Context, prefs: KeyboardPrefs) {
        if (!prefs.mockingbirdAutoTrain || training || Net.offline(prefs) || !AiClient.ready(prefs)) return
        val app = context.applicationContext
        worker.execute {
            if (training) return@execute
            val count = samples(app).size
            val p = profile(app)
            val now = System.currentTimeMillis()
            val old = p != null && outdated(p)
            val state = app.getSharedPreferences(STATE, Context.MODE_PRIVATE)
            val due = Retrain.due(
                trained = p != null, outdated = old, samples = count, newSamples = count - (p?.samples ?: 0),
                sinceTrained = now - (p?.at ?: 0L), sinceOutdatedTry = now - state.getLong(OUTDATED_TRY_AT, 0L),
            )
            if (!due) return@execute
            if (old) state.edit().putLong(OUTDATED_TRY_AT, now).apply()
            train(app)
        }
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
        return Ghostwriter.fitting((scored + recent).distinct(), budget) { it.text.length }
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
        return AiClient.complete(prefs, NetPath.MY_VOICE, system, text, maxTokens = 3000, effort = "low")
    }
}
