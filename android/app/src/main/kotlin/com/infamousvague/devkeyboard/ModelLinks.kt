package com.infamousvague.devkeyboard

/**
 * A model of the person's own choosing, for Rewrite on this phone (Matt, 2026-11-12: "ideally supporting all popular
 * local models"): any GGUF file on Hugging Face, added by pasting its link in Settings. The keyboard offers three
 * models it was tried with (LocalModels.SPECS); every other one there is, and there is a new one most weeks, is a
 * link away.
 *
 * Nothing here touches Android or the network (LocalModels.kt fetches): this reads a link as people copy it, and
 * writes down and reads back the models that were added. ModelLinksTest tries it off the phone.
 *
 * Only Hugging Face, and only https: it is the one place the ledger's LOCAL_MODEL may reach besides the box, and it
 * says, for every large file, its size and SHA-256 before a byte of it is fetched ([answer]; LocalModels.add asks), so a model
 * added by link is held to its hash as the built-in ones are. A GGUF file is data, not a program; what can still be
 * wrong with one is that llama.cpp does not know how its chats are framed, and then it writes badly, which the
 * check on what Rewrite takes back (LocalRewrite.refuse) is there for.
 */
object ModelLinks {
    /** A link read: where the file is fetched from, and the file's own name. */
    class Link(val url: String, val file: String)

    /**
     * [text] as a link to a GGUF file on Hugging Face, or null where it is not one. The file's page
     * (…/blob/main/x.gguf), its download link (…/resolve/main/x.gguf, with or without ?download=true), with or
     * without https://, on huggingface.co or hf.co, are all the same file.
     */
    fun read(text: String): Link? {
        var t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() || it == '\\' || it == '@' }) return null
        t = t.substringBefore('?').substringBefore('#')
        // With a scheme or without one; whichever it came with, the address made of it is https.
        val scheme = t.substringBefore("://", "")
        if (scheme.isNotEmpty()) {
            if (scheme != "https" && scheme != "http") return null
            t = t.substringAfter("://")
        }
        val host = t.substringBefore('/')
        if (host != "huggingface.co" && host != "hf.co" && host != "www.huggingface.co") return null
        val parts = t.substringAfter('/', "").split('/')
        // org / repo / blob|resolve / revision / path to the file
        if (parts.size < 5 || (parts[2] != "blob" && parts[2] != "resolve")) return null
        if (parts.any { it.isEmpty() || it == "." || it == ".." || !it.all(::plain) }) return null
        val file = parts.last()
        if (!file.endsWith(".gguf", ignoreCase = true) || file.length < 6) return null
        return Link("https://huggingface.co/" + (parts.take(2) + "resolve" + parts.drop(3)).joinToString("/"), file)
    }

    private fun plain(c: Char) = c.isLetterOrDigit() && c.code < 128 || c in "-_.+"

    /** An added model as it is kept: its [id], its [file]'s name, where it is fetched from, and its size and SHA-256 as Hugging Face gave them. */
    class Added(val id: String, val file: String, val url: String, val bytes: Long, val sha256: String) {
        /** What it is called in Settings: the file's name without its ending. */
        val name: String get() = file.removeSuffix(".gguf").removeSuffix(".GGUF")

        /** Whose it is on Hugging Face and which of theirs: "unsloth/Qwen3.5-2B-GGUF". */
        val repo: String get() = url.removePrefix("https://huggingface.co/").split('/').take(2).joinToString("/")
    }

    /** The id an added model is known by: "own-" and a number made of its address, the same for the same file. */
    fun idOf(url: String): String = "own-" + (url.hashCode().toLong() and 0xffffffffL).toString(16)

    /** The added models as one setting's text, a line a model. */
    fun write(models: List<Added>): String = models.joinToString("\n") { listOf(it.id, it.file, it.url, it.bytes.toString(), it.sha256).joinToString("\t") }

    /** That text read back. A line that is not whole, or whose address is no longer one this would take, is left out. */
    fun parse(text: String): List<Added> = text.lines().mapNotNull { line ->
        val f = line.split('\t')
        val link = f.getOrNull(2)?.let(::read)
        val bytes = f.getOrNull(3)?.toLongOrNull()
        val sha = f.getOrNull(4).orEmpty()
        if (f.size != 5 || link == null || link.url != f[2] || link.file != f[1] || bytes == null || bytes <= 0 || !SHA.matches(sha) || f[0] != idOf(f[2])) null
        else Added(f[0], f[1], f[2], bytes, sha)
    }.distinctBy { it.id }

    private val SHA = Regex("[0-9a-f]{64}")

    /** The SHA-256 Hugging Face names for a large file, from its answer's x-linked-etag (in quotes there), or null where that is not one. */
    fun hashOf(etag: String?): String? = etag?.trim()?.trim('"')?.lowercase()?.takeIf { SHA.matches(it) }

    /** [link] with its revision (main, a branch) made the commit Hugging Face said it stands at, so the file fetched later is the file that was looked at. */
    fun pinned(link: Link, commit: String?): Link {
        val c = commit?.trim()?.lowercase()?.takeIf { COMMIT.matches(it) } ?: return link
        val parts = link.url.removePrefix("https://huggingface.co/").split('/')
        return Link("https://huggingface.co/" + (parts.take(3) + c + parts.drop(4)).joinToString("/"), link.file)
    }

    private val COMMIT = Regex("[0-9a-f]{40}")

    /** What came of asking Hugging Face about a link: the model to add, or [why] not, in words for Settings. */
    class Answer(val added: Added?, val why: String?)

    /**
     * What Hugging Face's answer to a HEAD of [link]'s address says: its status [code], and its X-Linked-Size,
     * X-Linked-Etag and X-Repo-Commit headers ([size], [etag], [commit]; a large file's answer is a redirect to where
     * it is kept, and those are on the redirect itself, which is not followed). [memory] is the phone's memory in
     * bytes: a model is read into it whole, so one that could not fit is not added (0: not known, not checked).
     */
    fun answer(link: Link, code: Int, size: String?, etag: String?, commit: String?, memory: Long): Answer {
        if (code == 401 || code == 403) return Answer(null, "That model is behind a sign-in on Hugging Face (its makers ask for their terms to be accepted first), and Ghost Keyboard signs in nowhere. Look for an open copy of it.")
        if (code == 404) return Answer(null, "Hugging Face has no file at that link.")
        if (code !in 200..399) return Answer(null, "Hugging Face answered $code.")
        val bytes = size?.trim()?.toLongOrNull()
        val sha = hashOf(etag)
        if (bytes == null || bytes <= 0 || sha == null) return Answer(null, "Hugging Face gave no size and SHA-256 for that file, so it could not be checked once fetched. It is not added.")
        if (memory > 0 && bytes > memory / 10 * FITS_TENTHS) return Answer(null, "That model is ${gb(bytes)} and this phone has ${gb(memory)} of memory: it could not run here. Files of 1 to 4 GB (a 2B to 4B model at Q4_K_M) suit a phone.")
        val at = pinned(link, commit)
        return Answer(Added(idOf(at.url), at.file, at.url, bytes, sha), null)
    }

    /** How much of the phone's memory a model's file may be, in tenths: the rest is the phone's own and the app being typed in. */
    private const val FITS_TENTHS = 7

    /** The most models that may be added: a list in Settings, not a library. */
    const val MAX = 12

    const val NOT_A_LINK = "That is not a link to a .gguf file on Hugging Face. Open the model's Files tab there, pick a .gguf file, and copy its link."

    fun gb(bytes: Long): String = String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1e9)
}
