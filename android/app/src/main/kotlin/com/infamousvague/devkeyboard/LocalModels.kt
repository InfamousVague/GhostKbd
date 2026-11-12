package com.infamousvague.devkeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * The language models Rewrite can run on the phone itself (LocalAi.kt; Settings › AI › Rewrite › On this phone):
 * which there are, which are here, and fetching one.
 *
 * They are Ghost.md's own two smaller ones, the same files with the same sizes and hashes (its llm/model.rs: Qwen3.5,
 * Apache-2.0, as unsloth quantised them to Q4_K_M, each pinned to a commit on Hugging Face), so a file checked for
 * one app is the file the other has. Tried for Rewrite on a desk (docs/local-ai.md): the 4B does every choice well;
 * the 2B fixes and shortens well and changes tone less; the 0.8B does neither and is not offered.
 *
 * Fetched as the speech models are (WhisperModel.kt): from the box first (ghostkbd.com/models, where Ghost.md's files
 * are) and Hugging Face second, through [Net] under the ledger's LOCAL_MODEL, each hop checked, resumed where a part
 * is already here, and kept only if the size and the SHA-256 are right. A model is 1.3 or 2.7 GB: it is fetched only
 * when its Download is tapped, never by itself, and not without room for it.
 */
object LocalModels {
    data class Spec(val id: String, val name: String, val file: String, val bytes: Long, val sha256: String, val huggingFace: String, val note: String)

    val SPECS = listOf(
        Spec(
            "qwen3.5-4b", "Qwen3.5 4B", "Qwen3.5-4B-Q4_K_M.gguf", 2_740_937_888L, "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
            "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/e87f176479d0855a907a41277aca2f8ee7a09523",
            "The careful one: every Rewrite choice comes out well. About half as fast as the 2B.",
        ),
        Spec(
            "qwen3.5-2b", "Qwen3.5 2B", "Qwen3.5-2B-Q4_K_M.gguf", 1_280_835_840L, "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae",
            "The quick one: good at Fix and Shorter. It changes tone less than the 4B does.",
        ),
    )
    private const val BOX = "https://ghostkbd.com/models"

    /** Room kept free beyond the model itself (bytes): a phone with none left stops working well. */
    private const val SPARE = 300L * 1024 * 1024

    fun spec(id: String?) = SPECS.firstOrNull { it.id == id }

    fun file(context: Context, spec: Spec) = File(File(context.applicationContext.filesDir, "models").apply { mkdirs() }, spec.file)

    fun isReady(context: Context, spec: Spec) = file(context, spec).length() == spec.bytes

    /** The model Rewrite runs on: the one chosen in Settings where it is here, else whichever is; null with none. */
    fun active(context: Context): Spec? {
        val chosen = spec(KeyboardPrefs(context).localModel)
        return chosen?.takeIf { isReady(context, it) } ?: SPECS.firstOrNull { isReady(context, it) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>()
    @Volatile private var downloading: String? = null
    @Volatile private var progress = 0.0
    @Volatile private var cancelled = false
    private val errors = HashMap<String, String>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() { main.post { listeners.toList().forEach { it() } } }

    /** Why model [id] last failed to download. "Offline is on" is forgotten once Offline is off: it is no failure then. */
    private fun errorOf(context: Context, id: String): String? =
        errors[id]?.takeUnless { it == Net.OFFLINE_MESSAGE && !Net.offline(KeyboardPrefs(context)) }

    /** Every model with where it stands here, and which Rewrite runs on: what Settings lists. */
    fun toMap(context: Context): Map<String, Any?> = mapOf(
        "active" to active(context)?.id,
        "models" to SPECS.map { m ->
            mapOf(
                "id" to m.id, "name" to m.name, "bytes" to m.bytes, "note" to m.note,
                "installed" to isReady(context, m), "downloading" to (downloading == m.id),
                "progress" to if (downloading == m.id) progress else null, "error" to errorOf(context, m.id),
            )
        },
    )

    /**
     * Fetches [spec] unless it is here already, one model at a time. With Offline on, or without room for it, it
     * does not start: the model's line in Settings says why.
     */
    fun download(context: Context, spec: Spec) {
        val ctx = context.applicationContext
        if (isReady(ctx, spec) || downloading == spec.id) { changed(); return }
        val prefs = KeyboardPrefs(ctx)
        if (Net.offline(prefs)) { errors[spec.id] = Net.OFFLINE_MESSAGE; changed(); return }
        val part = File(file(ctx, spec).path + ".part")
        val need = spec.bytes - (if (part.exists()) part.length() else 0L) + SPARE
        val room = file(ctx, spec).parentFile?.usableSpace ?: 0L
        if (room < need) { errors[spec.id] = "Not enough room on this phone: it needs ${gb(need)} free, and there is ${gb(room)}."; changed(); return }
        if (downloading != null) cancelled = true // one at a time: the newest asked for wins
        io.execute {
            cancelled = false
            downloading = spec.id
            progress = 0.0
            errors.remove(spec.id)
            changed()
            var lastError: String? = null
            for (url in listOf("$BOX/${spec.file}", "${spec.huggingFace}/${spec.file}")) {
                if (cancelled) break
                try {
                    fetch(ctx, prefs, spec, url)
                    lastError = null
                    break
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                    // Offline switched on part-way: say so, keep the part for later, and try no other mirror.
                    if (Net.offline(prefs)) { lastError = Net.OFFLINE_MESSAGE; break }
                }
            }
            if (!cancelled && !isReady(ctx, spec)) errors[spec.id] = lastError ?: "The model could not be downloaded."
            // The first model fetched is the one Rewrite runs on, until another is chosen.
            if (isReady(ctx, spec) && spec(prefs.localModel)?.let { isReady(ctx, it) } != true) prefs.set(KeyboardPrefs.LOCAL_MODEL, spec.id)
            downloading = null
            changed()
        }
    }

    fun cancel() { cancelled = true }

    fun delete(context: Context, spec: Spec) {
        if (downloading == spec.id) cancelled = true
        file(context, spec).delete()
        File(file(context, spec).path + ".part").delete()
        errors.remove(spec.id)
        changed()
    }

    private fun gb(bytes: Long) = String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)

    /** One mirror: resumed with a Range where a part is already here, then held to the size and the hash. */
    private fun fetch(context: Context, prefs: KeyboardPrefs, spec: Spec, url: String) {
        val target = file(context, spec)
        val part = File(target.path + ".part")
        val have = if (part.exists()) part.length() else 0L
        val conn = Net.get(prefs, NetPath.LOCAL_MODEL, url) {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (have in 1 until spec.bytes) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val resumed = conn.responseCode == 206
            if (conn.responseCode != 200 && !resumed) throw IllegalStateException("${NetLedger.hostOf(url)} answered ${conn.responseCode}")
            var got = if (resumed) have else 0L
            var shown = -1
            conn.inputStream.use { input ->
                java.io.FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(256 * 1024)
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
            if (part.length() != spec.bytes) throw IllegalStateException("The download stopped short. Tap Download to carry on.")
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buf = ByteArray(1024 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (hex != spec.sha256) { part.delete(); throw IllegalStateException("The model does not match its hash.") }
            if (!part.renameTo(target)) throw IllegalStateException("Could not keep the model.")
        } finally {
            conn.disconnect()
        }
    }
}
