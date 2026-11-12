package com.infamousvague.devkeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A model of the person's own choosing (ModelLinks.kt; Matt, 2026-11-12: "ideally supporting all popular local
 * models"): which links are a GGUF file on Hugging Face, and the added models written down and read back.
 */
class ModelLinksTest {
    private val want = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf"

    @Test fun `a link as people copy it is the file's address`() {
        for (link in listOf(
            want,
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/blob/main/Qwen3.5-2B-Q4_K_M.gguf",
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf?download=true",
            "huggingface.co/unsloth/Qwen3.5-2B-GGUF/blob/main/Qwen3.5-2B-Q4_K_M.gguf",
            "  hf.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf  ",
        )) {
            assertEquals(link, want, ModelLinks.read(link)?.url)
            assertEquals(link, "Qwen3.5-2B-Q4_K_M.gguf", ModelLinks.read(link)?.file)
        }
        // A pinned commit, and a file in a folder of its repository.
        val pinned = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/f6d5376be1edb4d416d56da11e5397a961aca8ae/Qwen3.5-2B-Q4_K_M.gguf"
        assertEquals(pinned, ModelLinks.read(pinned)?.url)
        assertEquals("https://huggingface.co/a/b/resolve/main/q4/model.gguf", ModelLinks.read("https://huggingface.co/a/b/blob/main/q4/model.gguf")?.url)
    }

    @Test fun `anything else is not`() {
        for (link in listOf(
            "", "Qwen3.5-2B-Q4_K_M.gguf", "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF",
            // Not a GGUF file, and not a file's link at all.
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/README.md",
            "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/tree/main",
            // Another server, however it is dressed.
            "https://example.com/unsloth/x/resolve/main/model.gguf",
            "https://huggingface.co.example.com/a/b/resolve/main/model.gguf",
            "https://user@huggingface.co/a/b/resolve/main/model.gguf",
            "https://huggingface.co@example.com/a/b/resolve/main/model.gguf",
            "https://huggingface.co/a/b/resolve/main/../../../etc/model.gguf",
            "https://huggingface.co/a/b/resolve/main/mo del.gguf",
            "https://huggingface.co/a/b/resolve//model.gguf",
            "ftp://huggingface.co/a/b/resolve/main/model.gguf",
            "https://https://huggingface.co/a/b/resolve/main/model.gguf",
        )) assertNull(link, ModelLinks.read(link))
    }

    @Test fun `added models are written down and read back`() {
        val sha = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"
        val one = ModelLinks.Added(ModelLinks.idOf(want), "Qwen3.5-2B-Q4_K_M.gguf", want, 1_280_835_840L, sha)
        val other = "https://huggingface.co/a/b/resolve/main/model.gguf"
        val two = ModelLinks.Added(ModelLinks.idOf(other), "model.gguf", other, 5L, sha)
        val back = ModelLinks.parse(ModelLinks.write(listOf(one, two)))
        assertEquals(listOf(one.id, two.id), back.map { it.id })
        assertEquals(listOf(1_280_835_840L, 5L), back.map { it.bytes })
        assertEquals("Qwen3.5-2B-Q4_K_M", back[0].name)
        assertEquals(want, back[0].url)
        // The same file twice is one model; nothing is no models.
        assertEquals(1, ModelLinks.parse(ModelLinks.write(listOf(one, one))).size)
        assertEquals(emptyList<ModelLinks.Added>(), ModelLinks.parse(""))
    }

    @Test fun `a line that was meddled with is left out`() {
        val sha = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"
        val id = ModelLinks.idOf(want)
        fun line(vararg f: String) = f.joinToString("\t")
        // Another server's address under a known id, a file name that is not the address's, no hash, no size.
        assertEquals(0, ModelLinks.parse(line(id, "Qwen3.5-2B-Q4_K_M.gguf", "https://example.com/a/b/resolve/main/Qwen3.5-2B-Q4_K_M.gguf", "5", sha)).size)
        assertEquals(0, ModelLinks.parse(line(id, "../../evil.gguf", want, "5", sha)).size)
        assertEquals(0, ModelLinks.parse(line(id, "Qwen3.5-2B-Q4_K_M.gguf", want, "5", "nothash")).size)
        assertEquals(0, ModelLinks.parse(line(id, "Qwen3.5-2B-Q4_K_M.gguf", want, "0", sha)).size)
        assertEquals(0, ModelLinks.parse(line("own-1", "Qwen3.5-2B-Q4_K_M.gguf", want, "5", sha)).size)
        assertEquals(1, ModelLinks.parse(line(id, "Qwen3.5-2B-Q4_K_M.gguf", want, "5", sha)).size)
    }

    @Test fun `Hugging Face's answer about a file is the model to add`() {
        val sha = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"
        val commit = "f6d5376be1edb4d416d56da11e5397a961aca8ae"
        val link = ModelLinks.read(want)!!
        val gb16 = 16_000_000_000L
        // A large file's answer is a redirect that names its size, hash and commit: the model, pinned to that commit.
        val found = ModelLinks.answer(link, 302, "1280835840", "\"$sha\"", commit, gb16).added!!
        assertEquals("https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/$commit/Qwen3.5-2B-Q4_K_M.gguf", found.url)
        assertEquals(1_280_835_840L, found.bytes)
        assertEquals(sha, found.sha256)
        assertEquals("unsloth/Qwen3.5-2B-GGUF", found.repo)
        assertEquals(ModelLinks.idOf(found.url), found.id)
        // What was found is what is kept and read back.
        assertEquals(found.id, ModelLinks.parse(ModelLinks.write(listOf(found))).single().id)
        // No commit said, or one that is not a commit: the link as it was given.
        assertEquals(want, ModelLinks.answer(link, 302, "1280835840", sha, null, gb16).added!!.url)
        assertEquals(want, ModelLinks.answer(link, 302, "1280835840", sha, "main", gb16).added!!.url)
    }

    @Test fun `and where there is nothing to add it says why`() {
        val sha = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"
        val link = ModelLinks.read(want)!!
        val gb16 = 16_000_000_000L
        fun why(code: Int, size: String? = "1280835840", etag: String? = sha, memory: Long = gb16) = ModelLinks.answer(link, code, size, etag, null, memory).also { assertNull(it.added) }.why!!
        assertEquals(true, why(401).contains("sign-in"))
        assertEquals(true, why(403).contains("sign-in"))
        assertEquals("Hugging Face has no file at that link.", why(404))
        assertEquals("Hugging Face answered 500.", why(500))
        // A small file has no hash to be held to (its etag is a git id), and neither has an answer without one.
        assertEquals(true, why(200, etag = "\"0123456789abcdef0123456789abcdef01234567\"").contains("not added"))
        assertEquals(true, why(302, size = null).contains("not added"))
        assertEquals(true, why(302, size = "0").contains("not added"))
        // A model that could not fit in the phone's memory: 5.7 GB on a phone with 6, and the same one on a phone with 12.
        assertEquals(true, why(302, size = "5680522464", memory = 6_000_000_000L).contains("5.7 GB"))
        assertEquals(true, ModelLinks.answer(link, 302, "5680522464", sha, null, 12_000_000_000L).added != null)
        // A phone that does not say how much it has is not held to it.
        assertEquals(true, ModelLinks.answer(link, 302, "5680522464", sha, null, 0L).added != null)
    }

    @Test fun `Hugging Face's hash for a file is read from its answer`() {
        val sha = "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223"
        assertEquals(sha, ModelLinks.hashOf("\"$sha\""))
        assertEquals(sha, ModelLinks.hashOf(sha.uppercase()))
        // A small file's etag is a git id, not a SHA-256; and no header at all.
        assertNull(ModelLinks.hashOf("\"0123456789abcdef0123456789abcdef01234567\""))
        assertNull(ModelLinks.hashOf(null))
    }
}
