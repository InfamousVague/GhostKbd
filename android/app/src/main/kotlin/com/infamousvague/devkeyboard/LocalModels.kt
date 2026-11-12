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
 *
 * And any other model, of the person's own choosing (Matt, 2026-11-12: "ideally supporting all popular local
 * models"): a GGUF file on Hugging Face added by its link ([add], ModelLinks.kt). Hugging Face is asked for the
 * file's size and SHA-256, the model joins the list, and from there it is fetched, checked, chosen and deleted as
 * the two above are, from Hugging Face alone. Those have not been tried for Rewrite by anyone but the person.
 */
object LocalModels {
    /**
     * A model. [file] is what it is kept as on the phone; [mirrors] are the addresses its file is fetched from, first
     * to last; [own] is one the person added.
     */
    data class Spec(val id: String, val name: String, val file: String, val bytes: Long, val sha256: String, val mirrors: List<String>, val note: String, val own: Boolean = false)

    private const val BOX = "https://ghostkbd.com/models"

    /** One of the keyboard's own: on the box, and at a pinned commit of [huggingFace]. */
    private fun ours(id: String, name: String, file: String, bytes: Long, sha256: String, huggingFace: String, note: String) =
        Spec(id, name, file, bytes, sha256, listOf("$BOX/$file", "$huggingFace/$file"), note)

    val SPECS = listOf(
        ours(
            "qwen3.5-4b", "Qwen3.5 4B", "Qwen3.5-4B-Q4_K_M.gguf", 2_740_937_888L, "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4",
            "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/e87f176479d0855a907a41277aca2f8ee7a09523",
            "The careful one: every Rewrite choice comes out well. About half as fast as the 2B.",
        ),
        ours(
            "qwen3.5-2b", "Qwen3.5 2B", "Qwen3.5-2B-Q4_K_M.gguf", 1_280_835_840L, "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223",
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae",
            "The quick one: good at Fix and Shorter. It changes tone less than the 4B does.",
        ),
    )

    /** An added model as one of the list: kept under its id as well as its name, since two makers can call a file the same. */
    private fun own(a: ModelLinks.Added) =
        Spec(a.id, a.name, "${a.id}-${a.file}", a.bytes, a.sha256, listOf(a.url), "Added by you, from ${a.repo} on Hugging Face. Not one the keyboard was tried with.", own = true)

    private fun added(context: Context) = ModelLinks.parse(KeyboardPrefs(context).ownModels)

    /** Every model there is to choose from: the keyboard's own, then the person's, in the order they were added. */
    fun all(context: Context): List<Spec> = SPECS + added(context).map(::own)

    /** Room kept free beyond the model itself (bytes): a phone with none left stops working well. */
    private const val SPARE = 300L * 1024 * 1024

    fun spec(context: Context, id: String?) = all(context).firstOrNull { it.id == id }

    fun file(context: Context, spec: Spec) = File(File(context.applicationContext.filesDir, "models").apply { mkdirs() }, spec.file)

    fun isReady(context: Context, spec: Spec) = file(context, spec).length() == spec.bytes

    /** The model Rewrite runs on: the one chosen in Settings where it is here, else whichever is; null with none. */
    fun active(context: Context): Spec? {
        val every = all(context)
        val chosen = every.firstOrNull { it.id == KeyboardPrefs(context).localModel }
        return chosen?.takeIf { isReady(context, it) } ?: every.firstOrNull { isReady(context, it) }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = mutableSetOf<() -> Unit>()
    @Volatile private var downloading: String? = null
    @Volatile private var progress = 0.0
    @Volatile private var cancelled = false
    private val errors = HashMap<String, String>()

    /** A link is being asked about at Hugging Face ([add]); and why the last one was not added. */
    @Volatile private var adding = false
    @Volatile private var addError: String? = null
    private val asker = Executors.newSingleThreadExecutor()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() { main.post { listeners.toList().forEach { it() } } }

    /** Why model [id] last failed to download. "Offline is on" is forgotten once Offline is off: it is no failure then. */
    private fun errorOf(context: Context, id: String): String? =
        errors[id]?.takeUnless { it == Net.OFFLINE_MESSAGE && !Net.offline(KeyboardPrefs(context)) }

    /** Every model with where it stands here, which Rewrite runs on, and how adding one by its link stands: what Settings lists. */
    fun toMap(context: Context): Map<String, Any?> = mapOf(
        "active" to active(context)?.id,
        "adding" to adding,
        "addError" to addError?.takeUnless { it == Net.OFFLINE_MESSAGE && !Net.offline(KeyboardPrefs(context)) },
        "models" to all(context).map { m ->
            mapOf(
                "id" to m.id, "name" to m.name, "bytes" to m.bytes, "note" to m.note, "own" to m.own,
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
        if (room < need) { errors[spec.id] = "Not enough room on this phone: it needs ${ModelLinks.gb(need)} free, and there is ${ModelLinks.gb(room)}."; changed(); return }
        if (downloading != null) cancelled = true // one at a time: the newest asked for wins
        io.execute {
            cancelled = false
            downloading = spec.id
            progress = 0.0
            errors.remove(spec.id)
            changed()
            var lastError: String? = null
            var resumed = 0
            mirrors@ for (url in spec.mirrors) {
                while (true) {
                    if (cancelled) break@mirrors
                    val before = if (part.exists()) part.length() else 0L
                    try {
                        fetch(ctx, prefs, spec, url)
                        lastError = null
                        break@mirrors
                    } catch (e: Exception) {
                        lastError = broke(e)
                        // Offline switched on part-way: say so, keep the part for later, and try no other mirror.
                        if (Net.offline(prefs)) { lastError = Net.OFFLINE_MESSAGE; break@mirrors }
                        if (cancelled) break@mirrors
                        // A download of a gigabyte or more breaks off (seen on the emulator, 2026-11-12: "Software
                        // caused connection abort" at 16% of 533 MB). What has come is kept, so it is taken up where
                        // it stopped, at the same address, for as long as each try gets further; a try that got
                        // nowhere (no such file there, a wrong hash) goes on to the next address.
                        val now = if (part.exists()) part.length() else 0L
                        if (e !is java.io.IOException || now <= before || now >= spec.bytes || ++resumed > RESUMES) break
                        runCatching { Thread.sleep(RESUME_PAUSE_MS) }
                    }
                }
            }
            if (!cancelled && !isReady(ctx, spec)) errors[spec.id] = lastError ?: "The model could not be downloaded."
            // The first model fetched is the one Rewrite runs on, until another is chosen.
            if (isReady(ctx, spec) && spec(ctx, prefs.localModel)?.let { isReady(ctx, it) } != true) prefs.set(KeyboardPrefs.LOCAL_MODEL, spec.id)
            downloading = null
            changed()
        }
    }

    fun cancel() { cancelled = true }

    /** How often one download is taken up again after breaking off, and the pause before each (ms). */
    private const val RESUMES = 40
    private const val RESUME_PAUSE_MS = 2_000L

    /** Why a download stopped, in words for the model's line in Settings: never a network library's own. */
    private fun broke(e: Exception): String = when (e) {
        is Net.Refused -> e.message ?: Net.OFFLINE_MESSAGE
        is java.io.IOException -> "The download broke off. Tap Get to carry on from where it stopped."
        else -> e.message ?: "The model could not be downloaded."
    }

    fun delete(context: Context, spec: Spec) {
        if (downloading == spec.id) cancelled = true
        file(context, spec).delete()
        File(file(context, spec).path + ".part").delete()
        errors.remove(spec.id)
        changed()
    }

    /**
     * Adds the model at [text], a link to a GGUF file on Hugging Face as it is copied there (ModelLinks.read).
     * Hugging Face is asked for the file's size and SHA-256, and for the commit the link stands at, and that is all
     * that is fetched: the model joins the list, and its file comes when its Get is tapped. What is wrong with the
     * link, or what Hugging Face said, is the line under the field in Settings.
     */
    fun add(context: Context, text: String) {
        val ctx = context.applicationContext
        val prefs = KeyboardPrefs(ctx)
        val link = ModelLinks.read(text)
        val refused = when {
            link == null -> ModelLinks.NOT_A_LINK
            added(ctx).size >= ModelLinks.MAX -> "That is ${ModelLinks.MAX} models added: remove one first."
            Net.offline(prefs) -> Net.OFFLINE_MESSAGE
            else -> null
        }
        if (link == null || refused != null) { addError = refused; changed(); return }
        if (adding) return
        adding = true
        addError = null
        changed()
        asker.execute {
            addError = try {
                val answer = ask(ctx, prefs, link)
                val model = answer.added
                when {
                    model == null -> answer.why
                    all(ctx).any { it.id == model.id || it.sha256 == model.sha256 } -> "That model is already in the list."
                    else -> { prefs.set(KeyboardPrefs.OWN_MODELS, ModelLinks.write(added(ctx) + model)); null }
                }
            } catch (e: java.io.IOException) {
                Net.message(prefs, e, "Hugging Face could not be reached.")
            } catch (e: Exception) {
                "Hugging Face could not be asked about that link."
            }
            adding = false
            changed()
        }
    }

    /** One HEAD of the file's address, its redirect not followed: Hugging Face's own answer says the size, the hash and the commit. */
    private fun ask(context: Context, prefs: KeyboardPrefs, link: ModelLinks.Link): ModelLinks.Answer {
        val conn = Net.open(prefs, NetPath.LOCAL_MODEL, link.url)
        try {
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            val memory = android.app.ActivityManager.MemoryInfo().also {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)?.getMemoryInfo(it)
            }.totalMem
            return ModelLinks.answer(link, conn.responseCode, conn.getHeaderField("X-Linked-Size"), conn.getHeaderField("X-Linked-Etag"), conn.getHeaderField("X-Repo-Commit"), memory)
        } finally {
            conn.disconnect()
        }
    }

    /** Takes an added model off the list, and its file off the phone. One of the keyboard's own is only deleted ([delete]). */
    fun remove(context: Context, id: String) {
        val ctx = context.applicationContext
        val spec = spec(ctx, id)?.takeIf { it.own } ?: return
        delete(ctx, spec)
        val prefs = KeyboardPrefs(ctx)
        prefs.set(KeyboardPrefs.OWN_MODELS, ModelLinks.write(added(ctx).filter { it.id != id }))
        if (prefs.localModel == id) prefs.set(KeyboardPrefs.LOCAL_MODEL, "")
        changed()
    }

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
            if (part.length() != spec.bytes) throw java.io.IOException("The download stopped short.")
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
