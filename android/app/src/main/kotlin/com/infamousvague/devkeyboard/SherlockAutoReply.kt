package com.infamousvague.devkeyboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sherlock's Auto-reply (Matt, 2026-11-10: "Sherlock should automatically send if enabled. With sherlock enabled i
 * should be able to have a full conversation if the toggle switch is enabled to auto reply and have full
 * conversations with people").
 *
 * While a chat app is open on the screen and the switch is on, each new message from the other person is answered:
 * Sherlock reads the conversation, writes the reply as you and sends it with the app's own send button. It runs
 * from [SherlockService]'s window events, keyboard or not.
 *
 * What keeps it from running away:
 * - It answers only the other person's newest message, once; never one of its own replies, never your own message.
 * - Text you have started in the box is never overwritten or sent: it waits.
 * - At most [MAX_IN_A_ROW] replies in a conversation without you writing in it yourself, then it pauses there and
 *   says so; at least [MIN_GAP_MS] between two replies in one conversation.
 * - Every reply sent posts a notification saying what it sent, with Stop, which turns Auto-reply off.
 * - Only in chat apps (AppContext), never with Offline on, never without the AI set up.
 */
object SherlockAutoReply {
    const val MAX_IN_A_ROW = 20
    /** How long the chat must be still before it is read: a message arriving comes in several window changes. */
    private const val SETTLE_MS = 1800L
    private const val MIN_GAP_MS = 6000L
    private const val CHANNEL = "sherlock"
    private const val NOTIFICATION = 7101
    const val STOP_ACTION = "com.infamousvague.devkeyboard.SHERLOCK_STOP"

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private var pending: Runnable? = null
    private var prefs: KeyboardPrefs? = null

    /** Per conversation (app and header): the message last answered, replies in a row, and when the last went. */
    private val answered = HashMap<String, String>()
    private val inARow = HashMap<String, Int>()
    private val lastSentAt = HashMap<String, Long>()
    private val pausedSaid = HashSet<String>()
    /** Its own recent replies: never answered, and not taken for you writing. */
    private val sent = ArrayDeque<String>()

    fun armed(prefs: KeyboardPrefs) = Sherlock.on(prefs) && prefs.sherlockAutoreply && !Net.offline(prefs) && Sherlock.ready(prefs)

    /** A window change in some app: if it is a chat and Auto-reply is armed, look again once it has settled. */
    fun onEvent(service: SherlockService, event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == service.packageName || AppContext.kind(pkg) != AppKind.CHAT) return
        val p = prefs ?: KeyboardPrefs(service).also { prefs = it }
        if (!armed(p)) return
        pending?.let(main::removeCallbacks)
        pending = Runnable { worker.execute { check(service, p, pkg) } }.also { main.postDelayed(it, SETTLE_MS) }
    }

    private fun check(service: SherlockService, prefs: KeyboardPrefs, pkg: String) {
        if (!armed(prefs) || !busy.compareAndSet(false, true)) return
        try {
            val screen = service.read(SherlockService.keyboardTopNow) ?: return
            if (screen.app != pkg) return
            val messages = screen.lines.filter { it.side != SherlockService.Side.MIDDLE }
            val last = messages.lastOrNull() ?: return
            val conversation = pkg + "|" + screen.header.joinToString(" ")
            val ours = synchronized(sent) { sent.any { same(it, last.text) } }
            if (last.side == SherlockService.Side.ME) {
                // You wrote in it yourself: the count starts again.
                if (!ours) { inARow[conversation] = 0; pausedSaid.remove(conversation) }
                return
            }
            if (ours) return
            val key = last.text + "#" + messages.count { it.text == last.text }
            if (answered[conversation] == key) return
            if ((inARow[conversation] ?: 0) >= MAX_IN_A_ROW) {
                if (pausedSaid.add(conversation)) notify(service, title(screen, pkg), "Paused after $MAX_IN_A_ROW replies in a row. Write one yourself to let it carry on.")
                return
            }
            val wait = MIN_GAP_MS - (SystemClock.uptimeMillis() - (lastSentAt[conversation] ?: 0L))
            if (wait > 0) { main.postDelayed({ worker.execute { check(service, prefs, pkg) } }, wait); return }
            // You are typing: no reply is written over you (and none is paid for).
            if (service.boxBusy()) return
            // Marked before writing, so a failure is not tried again and again.
            answered[conversation] = key
            val reply = Sherlock.write(service, prefs, screen, "", pkg).trim()
            if (reply.isEmpty()) return
            if (!service.send(reply, replacing = false)) {
                Log.w("Sherlock", "auto-reply: couldn't send in $pkg")
                return
            }
            synchronized(sent) { sent.addLast(reply); while (sent.size > 30) sent.removeFirst() }
            inARow[conversation] = (inARow[conversation] ?: 0) + 1
            lastSentAt[conversation] = SystemClock.uptimeMillis()
            notify(service, title(screen, pkg), reply)
        } catch (t: Throwable) {
            Log.w("Sherlock", "auto-reply failed", t)
        } finally {
            busy.set(false)
        }
    }

    /** Its reply as the chat shows it again: the same words, give or take spacing and an ellipsis at the end. */
    private fun same(ours: String, shown: String): Boolean {
        fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim().removeSuffix("…").trim()
        val a = norm(ours); val b = norm(shown)
        return a == b || (b.length >= 20 && a.startsWith(b))
    }

    private fun title(screen: SherlockService.Screen, pkg: String) =
        "Sherlock replied" + (screen.header.firstOrNull()?.let { " to $it" } ?: " in $pkg")

    /** Says what it sent, with Stop. Needs Android's notification permission from 13 on (asked when the switch is turned on). */
    private fun notify(context: Context, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sherlock auto-reply", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Each reply Sherlock sends for you, with Stop"
            })
        }
        val stop = PendingIntent.getBroadcast(
            context, 0, Intent(context, SherlockStopReceiver::class.java).setAction(STOP_ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) android.app.Notification.Builder(context, CHANNEL) else @Suppress("DEPRECATION") android.app.Notification.Builder(context))
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .addAction(android.app.Notification.Action.Builder(null, "Stop auto-reply", stop).build())
            .build()
        runCatching { nm.notify(NOTIFICATION, n) }
    }

    /** Stop: Auto-reply off, everything it remembered dropped. */
    fun stop(context: Context) {
        KeyboardPrefs(context).set(KeyboardPrefs.SHERLOCK_AUTOREPLY, false)
        pending?.let(main::removeCallbacks)
        answered.clear(); inARow.clear(); lastSentAt.clear(); pausedSaid.clear()
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION)
    }
}

/** The notification's Stop. */
class SherlockStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SherlockAutoReply.STOP_ACTION) SherlockAutoReply.stop(context)
    }
}
