package com.infamousvague.devkeyboard

import android.content.Context
import java.io.File

/**
 * Where each tap landed and which key it typed, for tuning how touches are read (KeyboardView.keyAt). Turned on in
 * Settings › Developer › Tap log, copied from there to be pasted into a conversation. Off by default, never written
 * in a password field, and kept only on the phone (the last [KEEP] taps, in the app's own files).
 *
 * Lines are short so a paste of a few hundred stays readable:
 *
 *   # w=411.4dp rowH=58.0dp lift=0.12 density=2.62 page=LETTERS        (whenever the geometry changes)
 *   t  down=171.3,36.2  up=172.0,38.9  key=t@172.8,29.0  d=-0.03,+0.13  slid=-  first=t
 *
 * All in dp, y measured from the top of the first row of keys. `key=` is the typed key's centre, `d=` the down point's
 * offset from that centre in key widths (x) and key heights (y), `first=` the key under the finger when it came down
 * (different when the finger slid onto another key before lifting), `slid=` whether it did.
 */
object TapLog {
    private const val KEEP = 500
    private const val FILE = "tap_log.txt"

    private val lines = ArrayDeque<String>()
    private var loaded = false
    private var lastGeometry: String? = null

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val file = File(context.filesDir, FILE)
        if (file.exists()) file.readLines().takeLast(KEEP).forEach { lines.addLast(it) }
    }

    @Synchronized
    fun geometry(context: Context, line: String) {
        if (line == lastGeometry) return
        lastGeometry = line
        add(context, "# $line")
    }

    @Synchronized
    fun add(context: Context, line: String) {
        load(context)
        lines.addLast(line)
        while (lines.size > KEEP) lines.removeFirst()
        val file = File(context.filesDir, FILE)
        // Appended as it goes; rewritten whole only when it has grown well past what is kept.
        if (file.exists() && file.length() > 200_000) file.writeText(lines.joinToString("\n", postfix = "\n"))
        else file.appendText(line + "\n")
    }

    @Synchronized
    fun text(context: Context): String {
        load(context)
        val taps = lines.count { !it.startsWith("#") }
        return "Dev Keyboard tap log: $taps taps\n" + lines.joinToString("\n")
    }

    @Synchronized
    fun count(context: Context): Int {
        load(context)
        return lines.count { !it.startsWith("#") }
    }

    @Synchronized
    fun clear(context: Context) {
        lines.clear()
        loaded = true
        lastGeometry = null
        File(context.filesDir, FILE).delete()
    }
}
