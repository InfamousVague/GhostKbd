package com.infamousvague.devkeyboard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Telephony

/*
 * One-time codes from text messages (Matt, 2026-11-11: "add a feature that can read one time passcodes from text
 * messages and show them in the clipboard"; docs/sms-codes.md). Off unless switched on in Settings › Row tools,
 * and then only once Android's own permission to receive text messages has been given.
 *
 * What happens to a text. Android hands every text that arrives to SmsCodeReceiver: it has no way to hand over
 * only some. The receiver reads it once for a code (OtpCodes.find, OtpCodes.kt) and lets go of it. The text, who sent it and
 * when are kept nowhere. A code that is found is held by SmsCodes, in memory only and never in a file, for
 * SmsCodes.KEEP_MS or until it is typed: a chip at the start of the row above the keys while it is fresh
 * (SmsCodes.FRESH_MS), and the first thing in the clipboard's list for as long as it is held. It is never put on
 * Android's clipboard, never in the clipboard history's file, never among what Ghostwriter may read, never in a
 * typing report, and nothing here opens a connection.
 *
 * With the switch off the receiver is disabled in Android itself (SmsCodes.apply), so no text reaches the app at
 * all. Nothing here reads the phone's stored messages: the app does not ask for READ_SMS.
 *
 * What Android itself keeps back (2026): on Android 15 and later an app from outside a store is refused the
 * permission until "Allow restricted settings" is chosen on its page in Android's settings. On Android 17 a text
 * written for one website to read (its last line starts with @) or for one app to read (it ends in an eleven
 * character jumble) is held back from every other app for three hours, so those codes never show here; ordinary
 * "Your code is 123456" texts are held back the same way from apps built for Android 17 (targetSdk 37), which this
 * app is not. See docs/sms-codes.md before raising targetSdk.
 */

/** The codes found in texts, while they are held: in memory only, newest first, a few at most. */
object SmsCodes {
    /** A code is held this long from its text's arrival, unless it is typed first. */
    const val KEEP_MS = 10 * 60_000L
    /** And for this long of that it leads the row above the keys as a chip. */
    const val FRESH_MS = 3 * 60_000L
    /** Held at once: a code asked for again arrives as a second text. */
    const val MAX = 3

    /**
     * A code and when its text arrived ([SystemClock.elapsedRealtime]). Nothing else of the text is kept. [mail]: it
     * came in a mail app's notification, not a text (MailCodes.kt), which only changes the picture on its chip.
     */
    class Code(val text: String, val at: Long, val mail: Boolean = false)

    private val held = ArrayList<Code>()
    private val listeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /** Told when a code arrives or goes: the keyboard draws its row again. */
    fun addListener(listener: () -> Unit) { listeners += listener }
    fun removeListener(listener: () -> Unit) { listeners -= listener }
    private fun changed() = listeners.forEach { runCatching { it() } }

    /** A text has arrived at [now]: its code, where it has one, is held. The text goes no further than this call. */
    fun take(message: String, now: Long): Code? {
        val code = OtpCodes.find(message) ?: return null
        return hold(code, now, now, mail = false)
    }

    /**
     * Holds [code], which came at [at] (a mail's notification may have been on the screen a while before it was
     * read, MailCodes.kt), as [now] stands. Newest first, the same code once.
     */
    fun hold(code: String, at: Long, now: Long, mail: Boolean): Code {
        val entry = Code(code, at, mail)
        synchronized(held) {
            held.removeAll { it.text == code || now - it.at >= KEEP_MS }
            held.add(entry)
            held.sortByDescending { it.at }
            while (held.size > MAX) held.removeAt(held.size - 1)
        }
        changed()
        return entry
    }

    /** The codes still held at [now], newest first. */
    fun live(now: Long): List<Code> = synchronized(held) {
        held.removeAll { now - it.at >= KEEP_MS }
        held.toList()
    }

    /** The newest code, while it is fresh enough to lead the row. */
    fun fresh(now: Long): Code? = live(now).firstOrNull()?.takeIf { now - it.at < FRESH_MS }

    /** [code] was typed: a one-time code is for one use, and is let go. */
    fun used(code: Code) {
        synchronized(held) { held.removeAll { it.text == code.text } }
        changed()
    }

    fun clear() {
        val had = synchronized(held) { val any = held.isNotEmpty(); held.clear(); any }
        if (had) changed()
    }

    /** Lets go of the codes that came one way, by [mail] or by text: that way's switch was turned off. */
    fun drop(mail: Boolean) {
        val had = synchronized(held) { held.removeAll { it.mail == mail } }
        if (had) changed()
    }

    /** The switch in Settings › Row tools, which is the clipboard plugin's: without the plugin there is nowhere to show a code. */
    fun wanted(prefs: KeyboardPrefs) = prefs.smsCodes && Plugins.isOn(prefs, Plugins.CLIPBOARD)

    /** Codes are shown at all: either way they come is switched on. */
    fun shown(prefs: KeyboardPrefs) = wanted(prefs) || MailCodes.wanted(prefs)

    /** Whether Android lets the app receive text messages. */
    fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Makes Android match the switch: the receiver is enabled only while the switch is on, so with it off no text
     * reaches the app, whatever permission it still holds; and what was held is dropped.
     */
    fun apply(context: Context) {
        val on = wanted(KeyboardPrefs(context))
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            context.packageManager.setComponentEnabledSetting(ComponentName(context, SmsCodeReceiver::class.java), state, PackageManager.DONT_KILL_APP)
        }
        if (!on) drop(mail = false)
    }
}

/**
 * Where Android delivers a text that has arrived (the manifest lets only Android itself send here, and keeps this
 * off until [SmsCodes.apply] turns it on). One look for a code, and the text is let go: nothing is logged.
 */
class SmsCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!SmsCodes.wanted(KeyboardPrefs(context))) return
        // A long text arrives in parts: together they are the message.
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        val body = parts.joinToString("") { it?.displayMessageBody ?: "" }
        SmsCodes.take(body, SystemClock.elapsedRealtime())
    }
}
