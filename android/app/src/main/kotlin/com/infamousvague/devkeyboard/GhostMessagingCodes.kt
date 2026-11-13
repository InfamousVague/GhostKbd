package com.infamousvague.devkeyboard

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/*
 * One-time codes from Ghost Messaging (Matt, 2026-11-13: "The messaging app should also work with ghost kbd to proxy
 * verification codes"; docs/ghost-messaging-codes.md). On unless switched off in Settings › Row tools, and it does
 * nothing on a phone without Ghost Messaging.
 *
 * Why. The keyboard can read a code from a text by itself (SmsCodes.kt), but only with Android's permission to
 * receive texts, which Android 15 refuses an app from outside a store until "Allow restricted settings" is chosen,
 * and Android 17 holds code texts back from every app but the default SMS app for three hours. Ghost Messaging is
 * the default SMS app where it is used: it has the text the moment it arrives, finds the code, and answers the
 * keyboard with the code alone. No permission of Android's is needed here, and nothing Android holds back applies.
 *
 * What happens. Ghost Messaging keeps a door for this keyboard alone (its Codes.kt: a content provider that checks
 * the asker's certificate), and tells whoever is watching when a text with a code arrives. The keyboard watches, asks,
 * and is answered with the codes of the last ten minutes, each with the time its text arrived: never the words,
 * never who sent them. A code is held with the codes from texts (SmsCodes), in memory only: a chip at the start of
 * the row above the keys while it is fresh, the first thing in the clipboard's list while it is held, gone once
 * typed. Nothing here opens a connection, and nothing is written anywhere.
 *
 * Whom it asks. Only the app that answers at Ghost Messaging's address, and only where that app is Ghost Messaging
 * by its package and the certificate its releases are signed with ([CERTIFICATE]): another app that took the
 * address on a phone without Ghost Messaging is not asked, since what it answered would be typed as a code.
 */
object GhostMessagingCodes {
    const val PACKAGE = "com.infamousvague.ghostmessaging"
    const val AUTHORITY = "$PACKAGE.codes"

    /** The SHA-256 of the certificate Ghost Messaging's releases are signed with (its ~/.config/ghostmessaging/cert.sha256). */
    const val CERTIFICATE = "323e0f73edd91b03ec938992c00be7f5bfd52d36e2a9e46a6f34f04e57e26e47"

    /** Made when first asked for, as the watcher and the worker below are: nothing of Android is touched until then. */
    private val LATEST: Uri by lazy { Uri.parse("content://$AUTHORITY/latest") }

    /** One line of Ghost Messaging's answer: a code, and when its text arrived (the clock's time, in milliseconds). */
    class Row(val code: String, val atMs: Long)

    /** What a code is: digits, perhaps with letters, of a length a person types. Anything else is not typed for one. */
    private val shape = Regex("[A-Za-z0-9]{3,12}")

    /**
     * What of [rows] is to be held, at [nowMs] by the clock and [elapsed] by the time codes are kept by
     * ([SystemClock.elapsedRealtime]): each code with when it arrived on that second clock, oldest first. A code is
     * taken once ([seen]: asked again, Ghost Messaging answers with the same ones, and one that was typed must not
     * come back as new), and not at all once it is as old as codes are kept.
     */
    fun toHold(rows: List<Row>, nowMs: Long, elapsed: Long, seen: MailCodes.Seen): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        for (row in rows.sortedBy { it.atMs }) {
            if (!shape.matches(row.code) || row.code.none(Char::isDigit)) continue
            val age = (nowMs - row.atMs).coerceAtLeast(0)
            if (age >= SmsCodes.KEEP_MS) continue
            if (seen.first("${row.atMs}|${row.code}", nowMs)) out += row.code to (elapsed - age)
        }
        return out
    }

    /** The switch in Settings › Row tools, which is the clipboard plugin's as the others for codes are. */
    fun wanted(prefs: KeyboardPrefs) = prefs.ghostMessagingCodes && Plugins.isOn(prefs, Plugins.CLIPBOARD)

    /**
     * Whether Ghost Messaging is on this phone and is what answers at its address: by its package, and by the
     * certificate its releases are signed with (a debug build of the keyboard takes any build of it, for trying the
     * two on an emulator).
     */
    fun here(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val provider = pm.resolveContentProvider(AUTHORITY, 0) ?: return false
        if (provider.packageName != PACKAGE) return false
        BuildConfig.DEBUG || Updater.certificates(context, PACKAGE).contains(CERTIFICATE)
    }.getOrDefault(false)

    private val seen = MailCodes.Seen()
    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor() }
    @Volatile private var watching = false
    @Volatile private var app: Context? = null

    private val watcher by lazy {
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { app?.let { ask(it) } }
        }
    }

    /**
     * Makes the watching match the switch: on, Ghost Messaging is watched for a code arriving and asked once now
     * (for one that came while the keyboard was not running); off, it is neither watched nor asked. Called as the
     * keyboard starts, as the switch is turned, and as the keys are shown (Ghost Messaging may have been installed
     * since: watching an address nothing answers at is refused by Android).
     */
    fun apply(context: Context) {
        val application = context.applicationContext
        app = application
        val on = wanted(KeyboardPrefs(application)) && here(application)
        if (on == watching) return
        if (on) {
            val registered = runCatching { application.contentResolver.registerContentObserver(LATEST, false, watcher) }.isSuccess
            if (!registered) return
            watching = true
            ask(application)
        } else {
            runCatching { application.contentResolver.unregisterContentObserver(watcher) }
            watching = false
        }
    }

    /** Asks Ghost Messaging for its codes, off the main thread, and holds the ones not yet taken. */
    private fun ask(context: Context) {
        worker.execute {
            runCatching {
                if (!wanted(KeyboardPrefs(context)) || !here(context)) return@runCatching
                val rows = context.contentResolver.query(LATEST, null, null, null, null)?.use { c ->
                    val code = c.getColumnIndex("code"); val at = c.getColumnIndex("at")
                    if (code < 0 || at < 0) emptyList() else buildList { while (c.moveToNext() && size < SmsCodes.MAX) add(Row(c.getString(code).orEmpty(), c.getLong(at))) }
                } ?: emptyList()
                val elapsed = SystemClock.elapsedRealtime()
                for ((text, at) in toHold(rows, System.currentTimeMillis(), elapsed, seen)) SmsCodes.hold(text, at, elapsed, mail = false)
            }
        }
    }
}
