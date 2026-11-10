package com.infamousvague.devkeyboard

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Next-word prediction (Matt, 2026-11-09: "next word prediction"): after a space the row above the keys offers the
 * three likeliest next words, and the word before is gone by when completing and correcting the one being typed.
 *
 * Two things are blended ([Scorer]): the starter tables inside the app (assets/ngram/<language>.tsv, counted from
 * Tatoeba's sentences by tool/next_words.py; a language without one starts with nothing) and what the person types,
 * counted on this phone ([PersonalPairs]). Everything here is a file inside the app or in its own storage: nothing
 * opens a connection, so the Offline switch has nothing of this to stop, and there is no path for it in the ledger.
 *
 * What is learned is kept in [FILE] under the app's no-backup files, so it is not in Android's cloud backup nor moved
 * to a new phone by it; a typing profile carries it instead (TypingProfile.kt). The service decides what may be
 * learned (never a password field, incognito, or a field that asks for no learning); this class only counts what it
 * is given.
 *
 * Typing comes first. Key presses and the row's refresh call [at] and [learn], which never wait: they take the lock
 * only if it is free (a lookup then goes without the personal counts, a learned word waits its turn). Reading the
 * tables and the file, fading, saving and forgetting all run in order on one background thread, so a save can never
 * undo a reset that came after it.
 */
class NextWords private constructor(private val context: Context) {
    companion object {
        /** The learned pairs' file, under Context.noBackupFilesDir. */
        const val FILE = "next_words.tsv"
        /** Beside it, a learned file that could not be read: damaged, or written by a newer keyboard. */
        private const val SET_ASIDE_BAD = "next_words.bad"
        private const val SET_ASIDE_NEWER = "next_words.newer"
        private const val TAG = "GhostKeyboard"
        /** A save is due after this many newly learned words, once typing has paused. */
        private const val SAVE_AFTER = 200
        private const val SAVE_PAUSE_MS = 3_000L
        /** How many starter tables are kept in memory: the most languages that can be on at once. */
        private const val MAX_TABLES = Languages.MAX
        /** The most pairs one typing profile may bring, and the most one of them may count. */
        private const val IMPORT_MAX = 50_000
        private const val IMPORT_COUNT_MAX = 1_000f
        private val TURKISH: Locale = Locale.forLanguageTag("tr")

        @Volatile private var shared: NextWords? = null

        /** The app's one instance: the keyboard and the settings app run in one process and share it. */
        fun get(context: Context): NextWords =
            shared ?: synchronized(this) { shared ?: NextWords(context.applicationContext).also { shared = it } }
    }

    private val lock = ReentrantLock()
    private val worker = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "next-words").apply { isDaemon = true } }
    private val loadedLatch = CountDownLatch(1)

    /** What the person has typed; read and changed only with [lock] held. */
    private var personal = PersonalPairs()
    @Volatile private var loaded = false
    /** Words learned before the file was read, or while the lock was busy: counted on the worker in order. */
    private val waiting = ArrayList<NextWordText.Learned>()
    private var unsaved = 0
    @Volatile private var lastLearnAt = 0L
    @Volatile private var saveQueued = false

    /** The starter tables of the languages being typed, the lead language first; swapped whole. */
    @Volatile private var tables: List<NgramTable> = emptyList()
    private var asked: List<String> = emptyList()
    private var askedKeep: Set<String> = emptySet()
    /** The worker's own: the tables read so far of the languages that are on (null where a language has none). */
    private val cache = HashMap<String, NgramTable?>()

    /** Whether a word is one autocorrect knows (its dictionaries, learned words, slang): set by the service. */
    @Volatile var isWord: (String) -> Boolean = { false }

    /** The lead language's commonest words, for filling a row that has too few: set by the service. */
    @Volatile var commonest: () -> List<String> = { emptyList() }

    // How long lookups took, for Settings › Developer and the debug log: the last 512, in microseconds.
    private val took = IntArray(512)
    private var lookups = 0
    private var slowest = 0

    init { worker.execute { load() } }

    private fun file() = File(context.noBackupFilesDir, FILE)

    /**
     * Reads the learned pairs. A file this keyboard cannot read is set aside (deleted only by [reset], with the rest)
     * and it starts empty.
     */
    private fun load() {
        val f = file()
        val read = if (!f.exists()) PersonalPairs() else try {
            f.bufferedReader().useLines { PersonalPairs.read(it) }
        } catch (e: PersonalPairs.Unreadable) {
            runCatching { f.renameTo(File(f.parentFile, if (e.newer) SET_ASIDE_NEWER else SET_ASIDE_BAD)) }
            PersonalPairs()
        } catch (e: Exception) {
            runCatching { f.renameTo(File(f.parentFile, SET_ASIDE_BAD)) }
            PersonalPairs()
        }
        lock.withLock { personal = read }
        loaded = true
        loadedLatch.countDown()
        drain()
    }

    /** Counts the words that were waiting, in the order they were typed. Worker only. */
    private fun drain() {
        val now = synchronized(waiting) { ArrayList(waiting).also { waiting.clear() } }
        if (now.isEmpty()) return
        lock.withLock { for (l in now) personal.learn(l) }
    }

    /**
     * The languages being typed (the stems of their word lists, the lead language first). Their starter tables are
     * read in the background the first time; until one is there, that language predicts from the person's own typing
     * only. [keep] are the languages that are on at all, on whichever layout: their tables stay in memory, so going
     * round the layouts reads each once, and the table of a language turned off is let go. With [on] false
     * (prediction switched off) no table is held.
     */
    fun setLanguages(codes: List<String>, on: Boolean = true, keep: List<String> = codes) {
        val want = if (on) codes.take(MAX_TABLES) else emptyList()
        val kept = if (on) (keep.take(MAX_TABLES) + want).toSet() else emptySet()
        if (want == asked && kept == askedKeep) return
        asked = want
        askedKeep = kept
        worker.execute {
            cache.keys.retainAll(kept)
            val made = ArrayList<NgramTable>()
            for (code in want) {
                if (!cache.containsKey(code)) cache[code] = readTable(code)
                cache[code]?.let { made += it }
            }
            tables = made
        }
    }

    /**
     * One language's table from the app's assets, or null where it has none. Its words are put in lower case by the
     * language's own rule (Turkish I is ı, English I is i), whichever layout is being typed on when it is read.
     */
    private fun readTable(code: String): NgramTable? = runCatching {
        context.assets.open("ngram/$code.tsv").bufferedReader().useLines { NgramTable.parse(it, Languages.of(code)?.locale ?: Locale.ROOT) }
    }.getOrNull()

    private val lookup = object : Scorer.Lookup {
        override fun isWord(lower: String): Boolean = this@NextWords.isWord(lower)
        override fun form(lower: String): String = quick(lower) { personal.form(lower) }
        override fun count(lower: String): Float = quick(0f) { personal.unigram(lower) }
        override fun starting(prefix: String): List<Pair<String, Float>> = quick(emptyList()) { personal.starting(prefix) }
        override fun fills(): List<String> = quick(emptyList()) { personal.top(3).map { personal.form(it) } } + commonest()
    }

    /** [read] with the lock if it is free this instant, else [otherwise]: the typing thread never waits. */
    private inline fun <T> quick(otherwise: T, read: () -> T): T {
        if (!lock.tryLock()) return otherwise
        try { return read() } finally { lock.unlock() }
    }

    /** What is likely to come next at [place]. Never waits: with the lock busy it goes by the starter tables alone. */
    fun at(place: NextWordText.Place): Scorer {
        val now = tables
        var one = emptyList<PersonalPairs.Entry>()
        var two = emptyList<PersonalPairs.Entry>()
        if (lock.tryLock()) {
            try {
                one = personal.followers(place.prev)
                val prev2 = place.prev2
                if (prev2 != null && place.prev != NextWordText.START) two = personal.followers("$prev2 ${place.prev}")
            } finally { lock.unlock() }
        }
        return Scorer(place, now, Scorer.weights(now, place.recent), one, two, lookup)
    }

    /**
     * Runs a lookup and notes how long it took. In a debug build one over a millisecond is logged ("next-word lookup
     * took"), since a lookup runs on the thread that draws the keys.
     */
    fun <T> timed(work: () -> T): T {
        val t0 = System.nanoTime()
        val out = work()
        val micros = ((System.nanoTime() - t0) / 1_000).toInt()
        took[lookups and 511] = micros
        lookups++
        if (micros > slowest) slowest = micros
        if (micros > 1_000 && BuildConfig.DEBUG) Log.w(TAG, "next-word lookup took $micros µs")
        return out
    }

    /** Counts one finished word. Never waits: it is counted now if the lock is free, else on the worker. */
    fun learn(l: NextWordText.Learned) {
        lastLearnAt = android.os.SystemClock.uptimeMillis()
        var counted = false
        if (loaded && lock.tryLock()) {
            try { personal.learn(l); counted = true } finally { lock.unlock() }
        }
        if (!counted) {
            synchronized(waiting) { if (waiting.size < SAVE_AFTER) waiting += l }
            if (loaded) worker.execute { drain() }
        }
        if (++unsaved >= SAVE_AFTER && !saveQueued) { saveQueued = true; worker.schedule({ saveWhenIdle() }, SAVE_PAUSE_MS, TimeUnit.MILLISECONDS) }
    }

    /** Saves once typing has paused for [SAVE_PAUSE_MS]; while it has not, looks again later. */
    private fun saveWhenIdle() {
        val idle = android.os.SystemClock.uptimeMillis() - lastLearnAt
        if (idle < SAVE_PAUSE_MS) { worker.schedule({ saveWhenIdle() }, SAVE_PAUSE_MS - idle, TimeUnit.MILLISECONDS); return }
        write()
    }

    /** Writes the learned pairs soon, off the typing thread: the keyboard hid, or a profile was imported. */
    fun save() { worker.execute { write() } }

    /** Fades and bounds what is held ([PersonalPairs.maintain]) soon, off the typing thread. */
    fun maintain() { worker.execute { drain(); lock.withLock { personal.maintain() } } }

    /** The file written whole to a temporary one and moved into place, from a copy made under the lock. Worker only. */
    private fun write() {
        saveQueued = false
        if (!loaded) return
        drain()
        val copy = lock.withLock { unsaved = 0; personal.copy() }
        runCatching {
            val f = file()
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.bufferedWriter().use { copy.write(it) }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "next words not saved", it) }
        if (BuildConfig.DEBUG) Log.d(TAG, "next-word ${state()}")
    }

    /**
     * Settings › Forget what it learned: every counted pair, in memory and in every file that could hold one: the
     * learned file, a half-written save beside it, and a file set aside because it could not be read ([load]).
     * Waits until it is done.
     */
    fun reset() {
        worker.submit {
            synchronized(waiting) { waiting.clear() }
            lock.withLock { personal = PersonalPairs(); unsaved = 0 }
            val dir = file().parentFile
            for (name in listOf(FILE, "$FILE.tmp", SET_ASIDE_BAD, SET_ASIDE_NEWER)) runCatching { File(dir, name).delete() }
        }.get(5, TimeUnit.SECONDS)
    }

    /**
     * [word] in lower case by its own letters: Turkish's rule where it has a letter only Turkish has (İ or ı, so
     * IŞIK is ışık), else the root rule, so English "I" is "i" on any layout.
     */
    private fun lowerOf(word: String): String = word.lowercase(if ('İ' in word || 'ı' in word) TURKISH else Locale.ROOT)

    /** Waits, at most [ms], for the learned pairs to have been read: for Settings, never for the keyboard. */
    fun awaitLoaded(ms: Long) { loadedLatch.await(ms, TimeUnit.MILLISECONDS) }

    /** Waits, at most [ms], until everything queued on the worker so far has run: for Settings after an import. */
    fun awaitIdle(ms: Long) { runCatching { worker.submit { }.get(ms, TimeUnit.MILLISECONDS) } }

    /**
     * For Settings: `pairs` and `words` learned, `bundled` (the languages with a starter table inside the app),
     * `fileBytes`, the tables in memory now (`loaded`, and `tableBytes`, an estimate), and how long lookups have
     * taken since the keyboard started (`lookups`, `medianMicros`, `slowestMicros`).
     */
    fun state(): Map<String, Any> {
        val (pairs, words) = lock.withLock { personal.entries to personal.words }
        val now = tables
        val n = minOf(lookups, took.size)
        val sorted = took.copyOf(n).also { it.sort() }
        return mapOf(
            "pairs" to pairs, "words" to words,
            "bundled" to (runCatching { context.assets.list("ngram") }.getOrNull().orEmpty()).filter { it.endsWith(".tsv") }.map { it.removeSuffix(".tsv") }.sorted(),
            "fileBytes" to file().length(),
            "loaded" to now.map { it.code }, "tableBytes" to now.sumOf { it.approxBytes },
            "lookups" to lookups, "medianMicros" to (if (n == 0) 0 else sorted[n / 2]), "slowestMicros" to slowest,
        )
    }

    /** The learned pairs for a typing profile: each context with the words that followed it, as shown, and their counts. */
    fun exportPairs(): JSONObject {
        awaitLoaded(2_000)
        val copy = lock.withLock { personal.copy() }
        val out = JSONObject()
        copy.each { context, followers ->
            val row = JSONObject()
            for (e in followers) row.put(copy.form(e.word), Math.round(e.count * 100) / 100.0)
            if (row.length() > 0) out.put(context, row)
        }
        return out
    }

    /**
     * Takes a typing profile's pairs in: added to what is here (at most [IMPORT_MAX] of them, each counting at most
     * [IMPORT_COUNT_MAX]), then faded and bounded as typing is, and saved. Returns how many were added. The file is
     * read outside the lock and added a few thousand at a time, so typing goes on meanwhile.
     *
     * A profile does not say what language each pair is in, so lower case is each word's own ([lowerOf]), never
     * that of the layout that happens to be on when the file is opened.
     */
    fun importPairs(o: JSONObject): Int {
        awaitLoaded(2_000)
        val parsed = ArrayList<Triple<String, String, Float>>()
        for (context in o.keys()) {
            val row = o.optJSONObject(context) ?: continue
            val key = context.trim().split(' ').joinToString(" ") { lowerOf(it) }
            if (key.isEmpty() || key.length > NextWordText.MAX_WORD * 2 + 1 || key.count { it == ' ' } > 1) continue
            for (shown in row.keys()) {
                val n = row.optDouble(shown, 0.0).toFloat()
                if (parsed.size >= IMPORT_MAX) break
                if (n.isNaN() || n <= 0f || shown.isBlank() || shown.any { it.isWhitespace() } || !NextWordText.learnable(shown)) continue
                parsed += Triple(key, shown, n.coerceAtMost(IMPORT_COUNT_MAX))
            }
        }
        for (chunk in parsed.chunked(2_000)) lock.withLock {
            for ((context, shown, n) in chunk) {
                val word = lowerOf(shown)
                personal.add(context, word, if (' ' in context) "" else NextWordText.shown(shown, atStart = false), n)
            }
        }
        maintain()
        save()
        awaitIdle(5_000)
        return parsed.size
    }
}
