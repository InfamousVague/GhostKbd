package com.infamousvague.devkeyboard

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The phone's memory of the person's mistakes ([MistakeMemory]), kept in [FILE] under the app's no-backup files (not
 * in Android's cloud backup, nor moved to a new phone by it), beside what next-word prediction has learned.
 *
 * The service says what happened ([learn], [forget] and the rest: each a line handed to one background thread, so no
 * key press ever waits on it) and reads what is settled from [remembered] and [chosen], plain maps swapped whole each
 * time something changes. Mockingbird's repairs of slips in what was already sent ([setRepairs]) count as mistakes
 * too, without being written here: they are worked out again from its samples.
 *
 * Nothing here opens a connection. What may be learned is the service's to decide (never a password field, incognito,
 * or with "Learn from my corrections" off); "Forget what it learned" ([reset]) deletes every copy.
 */
class Mistakes private constructor(private val context: Context) {
    companion object {
        const val FILE = "mistakes.tsv"
        private const val TAG = "GhostKeyboard"
        /** What an autocorrection left to stand counts: it may not have been looked at. A hand's own fix counts 1. */
        const val STOOD = 0.5f

        @Volatile private var shared: Mistakes? = null

        fun get(context: Context): Mistakes = shared ?: synchronized(this) { shared ?: Mistakes(context.applicationContext).also { shared = it } }
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mistakes").apply { isDaemon = true } }
    /** The worker's own. */
    private var memory = MistakeMemory()
    private var repairs: Map<String, String> = emptyMap()
    private var dirty = false

    /** What a typed word is gone by as, by the word in lower case: settled mistakes and Mockingbird's repairs. Read by any thread. */
    @Volatile var remembered: Map<String, String> = emptyMap()
        private set

    /** For a stroke's first reading in lower case: the readings chosen over it, and how often. Read by any thread. */
    @Volatile var chosen: Map<String, Map<String, Float>> = emptyMap()
        private set

    /** The typed words something is remembered of, settled or not. Read by any thread. */
    @Volatile var mistyped: Set<String> = emptySet()
        private set

    /** Told, on the worker's thread, when [remembered] or [chosen] has changed. */
    @Volatile var onChange: (() -> Unit)? = null

    init { worker.execute { load() } }

    private fun file() = File(context.noBackupFilesDir, FILE)

    private fun load() {
        val f = file()
        memory = (if (f.exists()) runCatching { f.bufferedReader().useLines { MistakeMemory.read(it) } }.getOrNull() else null) ?: MistakeMemory()
        publish()
    }

    /** Makes [remembered] and [chosen] again. Worker only. */
    private fun publish() {
        val settled = HashMap(repairs)
        settled.putAll(memory.settled())
        remembered = settled
        chosen = memory.chosen()
        mistyped = memory.mistyped()
        runCatching { onChange?.invoke() }
    }

    private fun change(what: (MistakeMemory) -> Unit) {
        worker.execute { runCatching { what(memory); dirty = true; publish() }.onFailure { Log.w(TAG, "a mistake was not learned", it) } }
    }

    fun learn(wrong: String, meant: String, weight: Float = 1f) = change { it.learn(wrong, meant, weight) }
    fun forget(wrong: String, meant: String) = change { it.forget(wrong, meant) }
    fun learnSwipe(read: String, chosen: String, weight: Float = 1f) = change { it.learnSwipe(read, chosen, weight) }
    fun forgetSwipe(read: String, chosen: String) = change { it.forgetSwipe(read, chosen) }
    fun stood(read: String, weight: Float = 1f) = count { it.stood(read, weight) }

    /**
     * [typed] was left as it was typed ([MistakeMemory.kept]). Nothing is queued for a word nothing is remembered of
     * ([mistyped]), which is nearly every word typed.
     */
    fun kept(typed: String, weight: Float = STOOD) { if (typed in mistyped) count { it.kept(typed, weight) } }

    /** A tally that may change nothing: only what did change is published and saved. */
    private fun count(what: (MistakeMemory) -> Boolean) {
        worker.execute { runCatching { if (what(memory)) { dirty = true; publish() } }.onFailure { Log.w(TAG, "a mistake was not counted", it) } }
    }

    /** What [Mockingbird]'s samples looked like when their repairs were last worked out: how many, and the newest's time. */
    private var repairsOf = -1L

    /**
     * Reads Mockingbird's repairs again where its samples have changed since the last look ([on] false: none are
     * gone by). A sample is kept twice where its slips were mended: as typed, and as it was meant. A word typed one
     * way and mended the same way in [MistakeMemory.REPAIRED] samples or more, and never left to stand as typed in more samples
     * than it was mended in ([MistakeMemory.repairsIn]), is a mistake of the person's own: it is gone by like one they put right by hand.
     */
    fun readMockingbird(on: Boolean) {
        worker.execute {
            runCatching {
                if (!on) { repairsOf = -1L; if (repairs.isNotEmpty()) { repairs = emptyMap(); publish() }; return@runCatching }
                val samples = Mockingbird.samples(context)
                val mark = samples.size * 31L + (samples.lastOrNull()?.at ?: 0L)
                if (mark == repairsOf) return@runCatching
                repairsOf = mark
                val found = MistakeMemory.repairsIn(samples.map { it.raw to it.text })
                if (found != repairs) { repairs = found; publish() }
            }.onFailure { Log.w(TAG, "Mockingbird's repairs not read", it) }
        }
    }

    /**
     * Mockingbird's repairs: each slip it mended in a sample, by what was typed, and what it was mended to. They are
     * gone by as settled mistakes are, a learned one of the same word winning.
     */
    fun setRepairs(pairs: Map<String, String>) {
        worker.execute { if (pairs != repairs) { repairs = HashMap(pairs); publish() } }
    }

    /** Fades and bounds what is held, and writes it if it changed: the keyboard hid. */
    fun save() {
        worker.execute {
            if (!dirty) return@execute
            runCatching {
                memory.fade()
                val f = file()
                val tmp = File(f.parentFile, "$FILE.tmp")
                tmp.bufferedWriter().use { memory.write(it) }
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                dirty = false
                publish()
            }.onFailure { Log.w(TAG, "mistakes not saved", it) }
        }
    }

    /** Every remembered mistake, in memory and on disk. Waits until it is done. */
    fun reset() {
        worker.submit {
            memory = MistakeMemory()
            dirty = false
            val dir = file().parentFile
            for (name in listOf(FILE, "$FILE.tmp")) runCatching { File(dir, name).delete() }
            publish()
        }.get(5, TimeUnit.SECONDS)
    }

    /** For the Stats page: the words put right most often ([MistakeMemory.most]), as typed, meant and a whole count. Empty where none is kept. */
    fun most(n: Int): List<Map<String, Any>> = runCatching {
        worker.submit<List<Map<String, Any>>> {
            memory.most(n).map { (typed, meant, count) -> mapOf("typed" to typed, "meant" to meant, "count" to count.toDouble().let { Math.round(it).toInt().coerceAtLeast(1) }) }
        }.get(2, TimeUnit.SECONDS)
    }.getOrDefault(emptyList())

    /** For Settings: how many mistakes are remembered (`typed`, `swiped`), how many of those are gone by (`settled`), and Mockingbird's (`repairs`). */
    fun state(): Map<String, Any> = runCatching {
        worker.submit<Map<String, Any>> {
            mapOf("typed" to memory.typedPairs, "swiped" to memory.swipedPairs, "settled" to memory.settled().size, "repairs" to repairs.size)
        }.get(2, TimeUnit.SECONDS)
    }.getOrDefault(mapOf("typed" to 0, "swiped" to 0, "settled" to 0, "repairs" to 0))
}
