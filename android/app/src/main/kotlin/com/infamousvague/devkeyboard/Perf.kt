package com.infamousvague.devkeyboard

/**
 * How long the keyboard's own work takes, timed by the keyboard itself (Matt, 2026-11-12: "do a performance pass on the
 * keyboard to make sure everything is as fast as possible"). Until then the only thing timed was a next-word lookup
 * (NextWords.timed); a profiler on the emulator says where the processor's time goes, but not how long a key press
 * waits for the app it types into, which is most of what a key press can wait for.
 *
 * Off, [time] is the work and one read of a flag. It is switched on only in a debug build, and only while the file
 * `files/debug-perf` is there (DevKeyboardService looks as the keys are shown): release builds never time anything.
 * On, each named piece of work keeps how often it ran, how long in all, its longest, and its last [KEEP] times for a
 * middle and a slow one. [report] is written to the log (tag GhostPerf) as the keys are hidden, and all is forgotten.
 *
 *   adb shell run-as com.infamousvague.devkeyboard touch files/debug-perf
 *   ... type ...  hide the keyboard ...
 *   adb logcat -d -s GhostPerf
 *
 * Nothing here touches Android: PerfTest tries the sums.
 */
object Perf {
    @Volatile @JvmField var on = false

    const val KEEP = 2048

    private class Lap {
        var count = 0
        var total = 0L
        var worst = 0L
        val each = LongArray(KEEP)
    }

    private val laps = LinkedHashMap<String, Lap>()

    /** [block], timed under [name] where timing is on. */
    inline fun <T> time(name: String, block: () -> T): T {
        if (!on) return block()
        val from = System.nanoTime()
        try { return block() } finally { took(name, System.nanoTime() - from) }
    }

    /** [name] ran once more and took [ns]. */
    fun took(name: String, ns: Long) {
        synchronized(laps) {
            val lap = laps.getOrPut(name) { Lap() }
            lap.each[lap.count % KEEP] = ns
            lap.count++
            lap.total += ns
            if (ns > lap.worst) lap.worst = ns
        }
    }

    /** One line a name: how often, the time in all (ms), the middle one, the slow one (19 in 20 were quicker) and the longest (µs). */
    fun report(): String = synchronized(laps) {
        laps.entries.joinToString("\n") { (name, lap) ->
            val kept = lap.each.copyOf(minOf(lap.count, KEEP)).also { it.sort() }
            fun at(share: Double) = if (kept.isEmpty()) 0L else kept[((kept.size - 1) * share).toInt()] / 1000
            "%-12s n=%-5d total=%.1fms mid=%dµs p95=%dµs max=%dµs".format(java.util.Locale.ROOT, name, lap.count, lap.total / 1e6, at(0.5), at(0.95), lap.worst / 1000)
        }
    }

    fun reset() = synchronized(laps) { laps.clear() }
}
