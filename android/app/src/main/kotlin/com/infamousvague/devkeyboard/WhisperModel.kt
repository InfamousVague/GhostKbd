package com.infamousvague.devkeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The dictation plugin's speech models: whisper.cpp's quantised files, any of which can be downloaded and one chosen
 * (Settings › Dictation). English-only models (".en") are a little more accurate in English for their size; the others
 * hear about a hundred languages, and either detect which is spoken or are told one.
 *
 * Sizes and SHA-256s are Hugging Face's own for ggerganov/whisper.cpp (its LFS ids, listed by .github/workflows/models.yml);
 * base.en matches Ghost.md's pinned `BASE_EN_Q5_1`. Fetched from Ghost.md's mirror first and Hugging Face second;
 * the hash decides what is kept either way, so the order is about reliability, not trust. Downloads go through
 * [Net] (the ledger's MODEL): Hugging Face sends them on to its CDN, and each hop is checked. With Offline on nothing
 * is fetched; a model already on the phone works as before.
 */
object WhisperModel {
    data class Spec(
        val id: String,
        val name: String,
        val file: String,
        val bytes: Long,
        val sha256: String,
        val multilingual: Boolean,
        val note: String,
    )

    val SPECS = listOf(
        Spec("base.en", "Base, English", "ggml-base.en-q5_1.bin", 59_721_011L, "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f", false, "Quick and accurate for English. The one it starts with."),
        Spec("tiny", "Tiny, all languages", "ggml-tiny-q5_1.bin", 32_152_673L, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7", true, "The fastest and smallest; rougher on accents and names."),
        Spec("base", "Base, all languages", "ggml-base-q5_1.bin", 59_707_625L, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", true, "The everyday pick for languages other than English."),
        Spec("small", "Small, all languages", "ggml-small-q5_1.bin", 190_085_487L, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", true, "Noticeably better in every language; a few seconds slower."),
        Spec("small.en", "Small, English", "ggml-small.en-q5_1.bin", 190_098_681L, "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30", false, "The most accurate English that stays quick on a phone."),
        Spec("turbo", "Large v3 Turbo, all languages", "ggml-large-v3-turbo-q5_0.bin", 574_041_195L, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", true, "Near the best whisper does, in any language. Slow on most phones."),
    )
    val DEFAULT = SPECS.first()
    val MIRRORS = listOf("https://attack.fm/glyph/models", "https://huggingface.co/ggerganov/whisper.cpp/resolve/main")

    fun spec(id: String?) = SPECS.firstOrNull { it.id == id } ?: DEFAULT

    /** The chosen model (Settings › Dictation). */
    fun active(context: Context) = spec(KeyboardPrefs(context).whisperModel)

    /** The chosen model's state, as the keyboard and the settings app's first glance need it. */
    data class State(val phase: String, val progress: Double? = null, val error: String? = null)

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<(State) -> Unit>()
    @Volatile private var app: Context? = null
    @Volatile private var downloading: String? = null
    @Volatile private var progress = 0.0
    @Volatile private var cancelled = false
    private val errors = HashMap<String, String>()

    val state: State get() {
        val ctx = app ?: return State("missing")
        val s = active(ctx)
        val error = errorOf(ctx, s.id)
        return when {
            downloading == s.id -> State("downloading", progress)
            isReady(ctx, s) -> State("ready")
            error != null -> State("failed", error = error)
            else -> State("missing")
        }
    }

    /** Why model [id] last failed to download. "Offline is on" is forgotten once Offline is off: it is no failure then. */
    private fun errorOf(context: Context, id: String): String? =
        errors[id]?.takeUnless { it == Net.OFFLINE_MESSAGE && !Net.offline(KeyboardPrefs(context)) }

    fun addListener(l: (State) -> Unit) { listeners += l }
    fun removeListener(l: (State) -> Unit) { listeners -= l }
    private fun changed() { val s = state; main.post { listeners.toList().forEach { it(s) } } }

    fun file(context: Context, spec: Spec = active(context)) =
        File(File(context.applicationContext.filesDir, "models").apply { mkdirs() }, spec.file)

    fun isReady(context: Context, spec: Spec = active(context)) = file(context, spec).length() == spec.bytes

    /** Every model with where it stands here: what Settings › Dictation lists. */
    fun toMap(context: Context): Map<String, Any?> {
        app = context.applicationContext
        val s = state
        val active = active(context)
        return mapOf(
            "phase" to s.phase, "progress" to s.progress, "error" to s.error, "bytes" to active.bytes,
            "model" to active.id,
            "models" to SPECS.map { m ->
                mapOf(
                    "id" to m.id, "name" to m.name, "bytes" to m.bytes, "multilingual" to m.multilingual, "note" to m.note,
                    "installed" to isReady(context, m), "downloading" to (downloading == m.id),
                    "progress" to if (downloading == m.id) progress else null, "error" to errorOf(context, m.id),
                )
            },
        )
    }

    /** Where the models stand on this phone, without touching the network. */
    fun refresh(context: Context) { app = context.applicationContext; changed() }

    /**
     * Fetches [spec] unless it is here already, one model at a time. With Offline on it does not start: the model's
     * state says "Offline is on" instead.
     */
    fun download(context: Context, spec: Spec = active(context)) {
        val ctx = context.applicationContext
        app = ctx
        if (isReady(ctx, spec) || downloading == spec.id) { changed(); return }
        val prefs = KeyboardPrefs(ctx)
        if (Net.offline(prefs)) { errors[spec.id] = Net.OFFLINE_MESSAGE; changed(); return }
        if (downloading != null) cancelled = true // one at a time: the newest asked for wins
        io.execute {
            cancelled = false
            downloading = spec.id
            progress = 0.0
            errors.remove(spec.id)
            changed()
            var lastError: String? = null
            for (mirror in MIRRORS) {
                if (cancelled) break
                try {
                    fetch(ctx, prefs, spec, "$mirror/${spec.file}")
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    // Offline switched on part-way: say so, keep the part for later, and try no other mirror.
                    if (Net.offline(prefs)) { lastError = Net.OFFLINE_MESSAGE; break }
                }
            }
            if (!cancelled && !isReady(ctx, spec)) errors[spec.id] = lastError ?: "The model could not be downloaded."
            downloading = null
            changed()
        }
    }

    fun cancel() { cancelled = true }

    fun delete(context: Context, spec: Spec = active(context)) {
        if (downloading == spec.id) cancelled = true
        file(context, spec).delete()
        File(file(context, spec).path + ".part").delete()
        errors.remove(spec.id)
        changed()
    }

    /** One mirror: resumed with a Range where a part is already here, then held to the size and the hash. */
    private fun fetch(context: Context, prefs: KeyboardPrefs, spec: Spec, url: String) {
        val target = file(context, spec)
        val part = File(target.path + ".part")
        val have = if (part.exists()) part.length() else 0L
        val conn = Net.get(prefs, NetPath.MODEL, url) {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (have in 1 until spec.bytes) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val resumed = conn.responseCode == 206
            if (conn.responseCode != 200 && !resumed) throw IllegalStateException("$url answered ${conn.responseCode}")
            var got = if (resumed) have else 0L
            var shown = -1
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelled) throw IllegalStateException("Cancelled.")
                        if (Net.offline(prefs)) throw Net.Refused(Net.OFFLINE_MESSAGE)
                        val n = input.read(buf)
                        if (n < 0) break
                        got += n
                        if (got > spec.bytes) throw IllegalStateException("The model is larger than it should be.")
                        out.write(buf, 0, n)
                        val pct = (got * 100 / spec.bytes).toInt()
                        if (pct != shown) { shown = pct; progress = got.toDouble() / spec.bytes; changed() }
                    }
                }
            }
            if (part.length() != spec.bytes) throw IllegalStateException("The download stopped short.")
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != spec.sha256) { part.delete(); throw IllegalStateException("The model does not match its hash.") }
            if (!part.renameTo(target)) throw IllegalStateException("Could not keep the model.")
        } finally {
            conn.disconnect()
        }
    }

    /** The languages a multilingual model can be told to expect ("auto": it listens for which). */
    val LANGUAGES = listOf(
        "auto" to "Detect it", "en" to "English", "es" to "Español", "fr" to "Français", "de" to "Deutsch",
        "it" to "Italiano", "pt" to "Português", "nl" to "Nederlands", "pl" to "Polski", "sv" to "Svenska",
        "tr" to "Türkçe", "ru" to "Русский", "uk" to "Українська", "ar" to "العربية", "hi" to "हिन्दी",
        "ja" to "日本語", "ko" to "한국어", "zh" to "中文", "vi" to "Tiếng Việt", "id" to "Bahasa Indonesia",
    )
}
