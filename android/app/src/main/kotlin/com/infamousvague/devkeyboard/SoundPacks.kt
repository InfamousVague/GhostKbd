package com.infamousvague.devkeyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper

/**
 * The sounds plugin: key sounds made with ElevenLabs' sound effects (tool/sound_packs.py, run by
 * .github/workflows/sounds.yml), as assets/sounds/<pack>/key1..3, space, delete and enter (.ogg), played as made
 * through a SoundPool, loaded once per pack, so a key's sound starts the instant the finger lands.
 */
object SoundPacks {
    /**
     * The stock sounds: every keyboard has them, without the plugin (Settings › Key press › Sound), id to name. More
     * are brought in over time; "system" is Android's own.
     */
    val STOCK = linkedMapOf("bamboo" to "Thock", "geiger" to "Geiger counter", "ping-pong" to "Ping pong")

    /** Pack id to its name, in Settings' order, from assets/sounds/packs.json (tool/sound_packs.py writes it). */
    fun packs(context: Context): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            val a = org.json.JSONArray(context.assets.open("sounds/packs.json").bufferedReader().readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let { out[it.getString("id")] = it.getString("name") }
        }
        
        return out
    }

    private val main = Handler(Looper.getMainLooper())
    private var pool: SoundPool? = null
    /** The pack the pool holds. */
    @Volatile private var loaded: String? = null
    @Volatile private var preparing: String? = null
    private val ids = HashMap<String, Int>()
    private var round = 0

    /** Whether [pack]'s sounds are in this build. */
    fun available(context: Context, pack: String) =
        runCatching { context.assets.list("sounds/$pack")?.any { it.endsWith(".ogg") } == true }.getOrDefault(false)

    /** Loads [pack] in the background; [done] runs on the main thread once it can play. */
    fun prepare(context: Context, pack: String, done: (() -> Unit)? = null) {
        if (loaded == pack) { done?.let { main.post(it) }; return }
        if (preparing == pack && done == null) return
        preparing = pack
        val app = context.applicationContext
        main.post {
            if (preparing != pack) return@post
            pool?.release()
            ids.clear()
            val p = SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .build()
            val files = runCatching { app.assets.list("sounds/$pack")?.filter { it.endsWith(".ogg") } }.getOrNull().orEmpty()
            var pending = files.size
            if (pending == 0) { loaded = pack; preparing = null; done?.invoke() }
            p.setOnLoadCompleteListener { _, _, _ -> if (--pending == 0) { loaded = pack; preparing = null; done?.invoke() } }
            for (file in files) runCatching { app.assets.openFd("sounds/$pack/$file").use { ids[file.substringBeforeLast('.')] = p.load(it, 1) } }
            pool = p
        }
    }

    /** Plays [pack]'s sound for [type]; false where it isn't ready, so the caller can fall back to Android's. */
    fun play(context: Context, pack: String, type: KeyType, volume: Float = 0.35f): Boolean {
        if (loaded != pack) { prepare(context, pack); return false }
        // Backspace and enter are the key's own sound, pitched: a little lower for backspace, lower still for enter,
        // so a pack sounds like one board (2026-11-08). Space keeps its own, longer stroke.
        return when (type) {
            KeyType.SPACE -> playNamed("space", volume)
            KeyType.DELETE -> playNamed("key1", volume, 0.9f)
            KeyType.ENTER -> playNamed("key2", volume, 0.8f)
            else -> playNamed("key${(round++ % 3) + 1}", volume)
        }
    }

    private fun playNamed(name: String, volume: Float, pitch: Float? = null): Boolean {
        val id = ids[name] ?: ids["key1"] ?: return false
        // A touch of pitch wobble, so a run of keys doesn't sound like one sample.
        val rate = pitch ?: if (name.startsWith("key")) 0.97f + (round % 4) * 0.02f else 1f
        pool?.play(id, volume, volume, 1, 0, rate)
        return true
    }

    /**
     * A few seconds of typing in [pack]: a word, a space, a slip deleted, another word, enter, at a typist's uneven
     * pace, so a pack can be judged as it will sound in use rather than one click at a time.
     */
    fun sample(context: Context, pack: String, volume: Float) {
        sampleRun++
        val run = sampleRun
        prepare(context, pack) {
            val script = "kkkkk_kkkkdkk_kkkkkkk_kkke"
            var at = 0L
            val rnd = java.util.Random(7)
            for (c in script) {
                at += when (c) { '_' -> 150L; 'd' -> 260L; 'e' -> 220L; else -> 85L } + rnd.nextInt(60)
                main.postDelayed({
                    if (run != sampleRun) return@postDelayed
                    when (c) {
                        '_' -> playNamed("space", volume)
                        'd' -> playNamed("key1", volume, 0.9f)
                        'e' -> playNamed("key2", volume, 0.8f)
                        else -> playNamed("key${(round++ % 3) + 1}", volume)
                    }
                }, at)
            }
        }
    }

    /** The sample playing now: a newer one stops an older one's remaining keys. */
    @Volatile private var sampleRun = 0

    fun release() { pool?.release(); pool = null; loaded = null; ids.clear() }
}
