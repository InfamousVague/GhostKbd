package com.infamousvague.devkeyboard

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The sounds plugin: key sounds made with ElevenLabs' sound effects (tool/sound_packs.py, run by
 * .github/workflows/sounds.yml), as assets/sounds/<pack>/key1..3, space, delete and enter (.ogg).
 *
 * Each kind of sound (keys, space, delete, enter) can be shaped in Settings › Sound packs ([Shape]): its volume,
 * bass, treble, a tone filter that rounds the top off, its pitch and its length. The pack is decoded and put through
 * those filters on a background thread whenever they change, written to the cache as WAV and loaded into a SoundPool,
 * so a key's sound still starts the instant the finger lands.
 */
object SoundPacks {
    /** Pack id to its name, in Settings' order, from assets/sounds/packs.json (tool/sound_packs.py writes it). */
    fun packs(context: Context): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            val a = org.json.JSONArray(context.assets.open("sounds/packs.json").bufferedReader().readText())
            for (i in 0 until a.length()) a.getJSONObject(i).let { out[it.getString("id")] = it.getString("name") }
        }
        if (out.isEmpty()) out += linkedMapOf("mechanical" to "Clicky mechanical", "thock" to "Deep thock", "typewriter" to "Typewriter", "bubble" to "Bubble pop", "arcade" to "8-bit arcade")
        return out
    }

    /** The kinds of sound that are shaped apart: every letter key shares "key". */
    val KINDS = listOf("key", "space", "delete", "enter")

    /**
     * One kind of sound's filters: [gain] and the shelves in dB (bass at 150 Hz, treble at 4 kHz), [tone] the low-pass
     * cut-off in Hz (16000 leaves it open), [pitch] in semitones, [length] the share of the sound kept (0.3 to 1),
     * the rest faded away.
     */
    data class Shape(val gain: Float = 0f, val bass: Float = 0f, val treble: Float = DEFAULT_TREBLE, val tone: Float = DEFAULT_TONE, val pitch: Float = 0f, val length: Float = 1f) {
        /** Leaves the sound exactly as made (not the defaults, which round the top off). */
        val flat get() = gain == 0f && bass == 0f && treble == 0f && tone >= 15999f && pitch == 0f && length >= 1f
    }

    /** The defaults every sound starts from: Matt's ear, 2026-11-08 (treble -9 dB, tone 3.8 kHz). */
    const val DEFAULT_TREBLE = -9f
    const val DEFAULT_TONE = 3800f

    /** "sound_filters" as KeyboardPrefs keeps it: {"key": {"gain": 0, ...}, "space": {...}}. */
    fun parse(spec: String): Map<String, Shape> = runCatching {
        val o = JSONObject(spec.ifBlank { "{}" })
        KINDS.associateWith { kind ->
            val s = o.optJSONObject(kind) ?: return@associateWith Shape()
            Shape(
                s.optDouble("gain", 0.0).toFloat().coerceIn(-18f, 6f),
                s.optDouble("bass", 0.0).toFloat().coerceIn(-12f, 12f),
                s.optDouble("treble", DEFAULT_TREBLE.toDouble()).toFloat().coerceIn(-12f, 12f),
                s.optDouble("tone", DEFAULT_TONE.toDouble()).toFloat().coerceIn(800f, 16000f),
                s.optDouble("pitch", 0.0).toFloat().coerceIn(-12f, 12f),
                s.optDouble("length", 1.0).toFloat().coerceIn(0.2f, 1f),
            )
        }
    }.getOrDefault(KINDS.associateWith { Shape() })

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var pool: SoundPool? = null
    /** What the pool holds: the pack and its filters, as one string. */
    @Volatile private var loaded: String? = null
    @Volatile private var preparing: String? = null
    private val ids = HashMap<String, Int>()
    private var round = 0

    /** Whether [pack]'s sounds are in this build. */
    fun available(context: Context, pack: String) =
        runCatching { context.assets.list("sounds/$pack")?.any { it.endsWith(".ogg") } == true }.getOrDefault(false)

    /**
     * Makes [pack] with [filters] ready to play, in the background; [done] runs on the main thread once it is. A pack
     * already loaded the same way is ready at once.
     */
    fun prepare(context: Context, pack: String, filters: String, done: (() -> Unit)? = null) {
        val key = "$pack|$filters"
        if (loaded == key) { done?.let { main.post(it) }; return }
        if (preparing == key && done == null) return
        preparing = key
        val app = context.applicationContext
        worker.execute {
            val shapes = parse(filters)
            val dir = File(app.cacheDir, "sounds/$pack").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val files = runCatching { app.assets.list("sounds/$pack")?.filter { it.endsWith(".ogg") } }.getOrNull().orEmpty()
            val rendered = HashMap<String, String>()
            for (file in files) {
                val name = file.substringBeforeLast('.')
                val kind = if (name.startsWith("key")) "key" else name
                runCatching {
                    val (samples, rate) = decode(app, "sounds/$pack/$file")
                    val out = File(dir, "$name.wav")
                    writeWav(out, process(samples, rate, shapes[kind] ?: Shape()), rate)
                    rendered[name] = out.path
                }
            }
            main.post {
                if (preparing != key) return@post
                pool?.release()
                ids.clear()
                val p = SoundPool.Builder()
                    .setMaxStreams(4)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .build()
                var pending = rendered.size
                if (pending == 0) { loaded = key; preparing = null; done?.invoke() }
                p.setOnLoadCompleteListener { _, _, _ -> if (--pending == 0) { loaded = key; preparing = null; done?.invoke() } }
                for ((name, path) in rendered) ids[name] = p.load(path, 1)
                pool = p
            }
        }
    }

    /** Plays [pack]'s sound for [type]; false where it isn't ready, so the caller can fall back to Android's. */
    fun play(context: Context, pack: String, filters: String, type: KeyType, volume: Float = 0.35f): Boolean {
        if (loaded != "$pack|$filters") { prepare(context, pack, filters); return false }
        val name = when (type) {
            KeyType.SPACE -> "space"
            KeyType.DELETE -> "delete"
            KeyType.ENTER -> "enter"
            else -> "key${(round++ % 3) + 1}"
        }
        return playNamed(name, volume)
    }

    private fun playNamed(name: String, volume: Float): Boolean {
        val id = ids[name] ?: ids["key1"] ?: return false
        // A touch of pitch wobble, so a run of keys doesn't sound like one sample.
        val rate = if (name.startsWith("key")) 0.97f + (round % 4) * 0.02f else 1f
        pool?.play(id, volume, volume, 1, 0, rate)
        return true
    }

    /** Settings' Play button: [kind] of [pack], with [filters], as it will sound under the keys. */
    fun preview(context: Context, pack: String, filters: String, kind: String, volume: Float) {
        prepare(context, pack, filters) { playNamed(if (kind == "key") "key1" else kind, volume) }
    }

    /**
     * A few seconds of typing in [pack]: a word, a space, a slip deleted, another word, enter, at a typist's uneven
     * pace, so a pack can be judged as it will sound in use rather than one click at a time.
     */
    fun sample(context: Context, pack: String, filters: String, volume: Float) {
        sampleRun++
        val run = sampleRun
        prepare(context, pack, filters) {
            val script = "kkkkk_kkkkdkk_kkkkkkk_kkke"
            var at = 0L
            val rnd = java.util.Random(7)
            for (c in script) {
                at += when (c) { '_' -> 150L; 'd' -> 260L; 'e' -> 220L; else -> 85L } + rnd.nextInt(60)
                main.postDelayed({
                    if (run != sampleRun) return@postDelayed
                    when (c) {
                        '_' -> playNamed("space", volume)
                        'd' -> playNamed("delete", volume)
                        'e' -> playNamed("enter", volume)
                        else -> playNamed("key${(round++ % 3) + 1}", volume)
                    }
                }, at)
            }
        }
    }

    /** The sample playing now: a newer one stops an older one's remaining keys. */
    @Volatile private var sampleRun = 0

    fun release() { pool?.release(); pool = null; loaded = null; ids.clear() }

    // --- signal ---------------------------------------------------------------------------------------------------

    /** An asset's audio as mono floats in -1..1, and its sample rate. */
    private fun decode(context: Context, asset: String): Pair<FloatArray, Int> {
        val extractor = MediaExtractor()
        context.assets.openFd(asset).use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
        val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val out = ArrayList<Float>(rate)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                    buf.position(info.offset); buf.limit(info.offset + info.size)
                    val shorts = buf.asShortBuffer()
                    var c = 0; var acc = 0f
                    while (shorts.hasRemaining()) {
                        acc += shorts.get() / 32768f
                        if (++c == channels) { out += acc / channels; c = 0; acc = 0f }
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            codec.stop(); codec.release(); extractor.release()
        }
        return out.toFloatArray() to rate
    }

    /** [shape]'s filters on [input]: pitch (resampled), the shelves, the tone filter, gain, length, a soft limit. */
    private fun process(input: FloatArray, rate: Int, shape: Shape): FloatArray {
        if (shape.flat) return input
        var x = input
        if (shape.pitch != 0f) {
            // Faster playback is higher: read through the sound at 2^(semitones/12).
            val step = 2.0.pow(shape.pitch / 12.0)
            val n = (x.size / step).toInt().coerceAtLeast(1)
            x = FloatArray(n) { i ->
                val p = i * step
                val a = p.toInt().coerceAtMost(input.size - 1)
                val b = (a + 1).coerceAtMost(input.size - 1)
                (input[a] + (input[b] - input[a]) * (p - a)).toFloat()
            }
        }
        if (shape.bass != 0f) x = biquad(x, shelf(rate, 150.0, shape.bass.toDouble(), low = true))
        if (shape.treble != 0f) x = biquad(x, shelf(rate, 4000.0, shape.treble.toDouble(), low = false))
        if (shape.tone < 15999f) x = biquad(biquad(x, lowpass(rate, shape.tone.toDouble())), lowpass(rate, shape.tone.toDouble()))
        val gain = 10.0.pow(shape.gain / 20.0).toFloat()
        val keep = (x.size * shape.length).toInt().coerceIn(1, x.size)
        val fade = (keep * 0.35f).coerceAtLeast(1f)
        return FloatArray(keep) { i ->
            val tail = if (i > keep - fade) exp(-4f * (i - (keep - fade)) / fade) else 1f
            // A soft knee rather than a hard clip, so a boost never crackles.
            tanh(x[i] * gain * tail * 1.2f) / 1.2f
        }
    }

    /** RBJ's cookbook: a shelf at [f0] Hz by [db]. */
    private fun shelf(rate: Int, f0: Double, db: Double, low: Boolean): DoubleArray {
        val a = 10.0.pow(db / 40)
        val w = 2 * PI * f0 / rate
        val cw = cos(w)
        val alpha = sin(w) / 2 * sqrt(2.0)
        val sa = 2 * sqrt(a) * alpha
        return if (low) doubleArrayOf(
            a * ((a + 1) - (a - 1) * cw + sa), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sa),
            (a + 1) + (a - 1) * cw + sa, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sa,
        ) else doubleArrayOf(
            a * ((a + 1) + (a - 1) * cw + sa), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sa),
            (a + 1) - (a - 1) * cw + sa, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sa,
        )
    }

    private fun lowpass(rate: Int, f0: Double): DoubleArray {
        val w = 2 * PI * f0.coerceAtMost(rate * 0.45) / rate
        val cw = cos(w)
        val alpha = sin(w) / (2 * 0.7071)
        return doubleArrayOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2, 1 + alpha, -2 * cw, 1 - alpha)
    }

    private fun biquad(x: FloatArray, c: DoubleArray): FloatArray {
        val b0 = c[0] / c[3]; val b1 = c[1] / c[3]; val b2 = c[2] / c[3]; val a1 = c[4] / c[3]; val a2 = c[5] / c[3]
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        return FloatArray(x.size) { i ->
            val x0 = x[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0; y2 = y1; y1 = y0
            y0.toFloat()
        }
    }

    private fun writeWav(file: File, samples: FloatArray, rate: Int) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.capacity()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.capacity())
        }
        FileOutputStream(file).use { it.write(header.array()); it.write(data.array()) }
    }
}
