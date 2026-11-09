package com.infamousvague.devkeyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * The sound packs plugin: key sounds made with ElevenLabs' sound effects (tool/sound_packs.py, run by
 * .github/workflows/sounds.yml), as assets/sounds/<pack>/key1..3, space, delete and enter (.ogg). Played through a
 * SoundPool, loaded once per pack, so a key's sound starts as the finger lands.
 */
object SoundPacks {
    /** Pack id to its name, in Settings' order; the settings app lists the same (lib/plugins/catalogue.dart). */
    val PACKS = linkedMapOf(
        "mechanical" to "Clicky mechanical",
        "thock" to "Deep thock",
        "typewriter" to "Typewriter",
        "bubble" to "Bubble pop",
        "arcade" to "8-bit arcade",
    )

    private var pool: SoundPool? = null
    private var loaded: String? = null
    private val ids = HashMap<String, Int>()
    private var round = 0

    /** Whether [pack]'s sounds are in this build (they arrive once the sounds workflow has run). */
    fun available(context: Context, pack: String) =
        runCatching { context.assets.list("sounds/$pack")?.isNotEmpty() == true }.getOrDefault(false)

    private fun ensure(context: Context, pack: String): Boolean {
        if (loaded == pack) return ids.isNotEmpty()
        pool?.release()
        ids.clear()
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .build()
        pool = p
        loaded = pack
        val files = runCatching { context.assets.list("sounds/$pack")?.toList() }.getOrNull().orEmpty()
        for (file in files) {
            val name = file.substringBeforeLast('.')
            runCatching { context.assets.openFd("sounds/$pack/$file").use { ids[name] = p.load(it, 1) } }
        }
        return ids.isNotEmpty()
    }

    /** Plays [pack]'s sound for [type]; false where the pack has none, so the caller can fall back to Android's. */
    fun play(context: Context, pack: String, type: KeyType, volume: Float = 0.7f): Boolean {
        if (!ensure(context, pack)) return false
        val name = when (type) {
            KeyType.SPACE -> "space"
            KeyType.DELETE -> "delete"
            KeyType.ENTER -> "enter"
            else -> "key${(round++ % 3) + 1}"
        }
        val id = ids[name] ?: ids["key1"] ?: return false
        // A touch of pitch wobble, so a run of keys doesn't sound like one sample.
        val rate = if (name.startsWith("key")) 0.96f + (round % 5) * 0.02f else 1f
        pool?.play(id, volume, volume, 1, 0, rate)
        return true
    }

    fun release() { pool?.release(); pool = null; loaded = null; ids.clear() }
}
