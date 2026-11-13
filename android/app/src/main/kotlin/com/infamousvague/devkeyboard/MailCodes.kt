package com.infamousvague.devkeyboard

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/*
 * One-time codes from email (Matt, 2026-11-12: "for the OTP parsing id like to also be able to hook up gmail so new
 * authentication codes can be parsed and sent to the keyboard automatically"; docs/mail-codes.md). Off unless
 * switched on in Settings › Row tools, and then only once Android's own notification access has been given.
 *
 * Not by signing in to Gmail: Google's rules for its Gmail access name mobile keyboards among the uses it does not
 * allow, and the other way in is a password to the whole account kept in a keyboard (docs/mail-codes.md has both).
 * By the mail app's own notification instead, on the phone: Gmail says a mail has come, and Android shows that to
 * the keyboard as it shows it on the lock screen. Nothing leaves the phone and there is no account to connect.
 *
 * What happens to a notification. Android hands every app's notifications to MailCodeListener: it has no way to hand
 * over only some. One that is not a mail app's (MailCodes.APPS, or one that says it is email) is not read at all.
 * A mail app's is read once for a code, its subject and the start of the mail (OtpCodes.find), and let go of: the
 * subject, the sender and the mail are kept nowhere and written nowhere. A code that is found is held with the codes
 * from texts (SmsCodes), in memory only: a chip at the start of the row above the keys while it is fresh, the first
 * thing in the clipboard's list while it is held, gone once typed. Nothing here opens a connection.
 *
 * With the switch off the listener is disabled in Android itself (MailCodes.apply), so no notification reaches the
 * app at all, whatever access it still holds.
 *
 * What Android itself keeps back. From Android 15 a notification that Android judges to hold a one-time code reaches
 * an app like this one with its words replaced ("Sensitive notification content hidden"), unless the phone's owner
 * has said otherwise (MailCodes.trusted, and docs/mail-codes.md for the two ways). The listener knows such a
 * notification when it sees one and says so in Settings, rather than seem broken.
 */
object MailCodes {
    /** The mail apps whose notifications are read, by their ids. Any other app's that says it is email is read too. */
    val APPS = setOf(
        "com.google.android.gm", "com.google.android.gm.lite", "com.microsoft.office.outlook", "com.samsung.android.email.provider",
        "com.yahoo.mobile.client.android.mail", "ch.protonmail.android", "com.fsck.k9", "net.thunderbird.android", "eu.faircode.email",
    )

    /** Android's shell: in a debug build its notifications are read as Gmail's, so `cmd notification post` can stand for a mail on an emulator. */
    private const val SHELL = "com.android.shell"

    /** The apps whose notifications are read at all: [APPS], and in a debug build the shell's. */
    fun sources(): Set<String> = if (BuildConfig.DEBUG) APPS + SHELL else APPS

    /** Whose notification this is taken to be: its own app's, but for the debug build's shell. */
    fun source(pkg: String) = if (BuildConfig.DEBUG && pkg == SHELL) "com.google.android.gm" else pkg

    /** A mail older than this is not news: its notification, shown again after a restart or a sync, is not read. */
    const val OLD_MS = 30 * 60_000L

    /** Only this much of a mail is looked at: a code is said at the start, and Android's own look for one goes as far. */
    const val LOOK_CHARS = 600

    /**
     * What of a notification is looked at, and nothing else of it is: whose it is, whether it says it is email,
     * whether it only sums up others, its line of text and its longer text (for Gmail the subject, and the subject
     * with the start of the mail), the time it says it is of, when Android posted it, and Android's name for it.
     */
    class Post(val pkg: String, val category: String?, val summary: Boolean, val text: String?, val big: String?, val whenMs: Long, val postedMs: Long, val key: String)

    /** What a notification came to. */
    sealed interface Read
    /** Not a mail app's, or old, or with no code in it. */
    object None : Read
    /** Android had replaced its words: it judged them to hold a code, and does not show those to this app. */
    object Hidden : Read
    /** A code, how long ago its notification was posted (ms), and what tells this mail's code from another's ([Seen]). */
    class Code(val code: String, val ageMs: Long, val stamp: String) : Read

    /**
     * Reads [post] at [nowMs] (the clock's time, as the notification's times are). [hiddenText] is what Android puts
     * in place of words it hides, in the phone's language, or null where this Android hides nothing.
     */
    fun read(post: Post, nowMs: Long, hiddenText: String?): Read {
        if (post.summary) return None
        if (post.pkg !in APPS && post.category != Notification.CATEGORY_EMAIL) return None
        if (!hiddenText.isNullOrEmpty() && (post.text == hiddenText || post.big == hiddenText)) return Hidden
        // Posted a while ago (the listener has just been started, and this was on the screen already), or a mail
        // from a while ago posted again: a code that old is spent, and showing it as new would only mislead.
        val age = (nowMs - post.postedMs).coerceAtLeast(0)
        if (age >= SmsCodes.KEEP_MS) return None
        if (post.whenMs in 1..nowMs && nowMs - post.whenMs >= OLD_MS) return None
        val code = OtpCodes.find(message(post.text, post.big)) ?: return None
        return Code(code, age, if (post.whenMs > 0) "${post.pkg}|${post.whenMs}|$code" else "${post.key}|$code")
    }

    /**
     * The words to look for a code in: the subject, then the start of the mail. A mail app's longer text usually
     * begins with the subject again, and then it is not said twice. Cut at [LOOK_CHARS], between words, so that a
     * number is never cut in two.
     */
    fun message(text: String?, big: String?): String {
        val subject = text?.trim().orEmpty()
        val body = big?.trim().orEmpty()
        val all = when {
            body.isEmpty() -> subject
            subject.isEmpty() || body.startsWith(subject) -> body
            else -> "$subject\n$body"
        }
        if (all.length <= LOOK_CHARS) return all
        val cut = all.substring(0, LOOK_CHARS)
        val space = cut.indexOfLast { it.isWhitespace() }
        return if (space > LOOK_CHARS / 2) cut.substring(0, space) else cut
    }

    /**
     * The mails whose codes have been taken, for [OLD_MS] each: a mail app posts the same mail again when it syncs,
     * and sometimes twice under two names, and a code that was typed must not come back as new because of that.
     */
    class Seen {
        private val taken = LinkedHashMap<String, Long>()

        /** True the first time [stamp] is seen, at [nowMs]; false while it is remembered. */
        @Synchronized fun first(stamp: String, nowMs: Long): Boolean {
            if (has(stamp, nowMs)) return false
            taken[stamp] = nowMs
            while (taken.size > 32) taken.remove(taken.keys.first())
            return true
        }

        /** Whether [stamp] is remembered at [nowMs]. */
        @Synchronized fun has(stamp: String, nowMs: Long): Boolean {
            taken.entries.removeAll { nowMs - it.value >= OLD_MS }
            return stamp in taken
        }
    }

    private val seen = Seen()

    /** The notifications a code was read from, each by Android's name for it and the time it says it is of. */
    private val readFrom = Seen()

    /**
     * When Android last handed over a mail app's notification with its words hidden and nothing had been read from
     * it first ([SystemClock.elapsedRealtime]; 0: never). Android has been seen to hand a notification over whole and
     * then again hidden, once it had judged it (an emulator, Android 16): that second copy is no loss, and not this.
     */
    @Volatile var hiddenAt = 0L
        private set

    /**
     * A notification has been posted: its code, where it is a mail app's and has one, is held with the codes from
     * texts. [nowMs] is the clock's time and [elapsed] the time codes are kept by ([SystemClock.elapsedRealtime]).
     */
    fun took(post: Post, nowMs: Long, elapsed: Long, hiddenText: String?): Read {
        val read = read(post, nowMs, hiddenText)
        val which = "${post.key}|${post.whenMs}"
        when (read) {
            is Hidden -> if (!readFrom.has(which, nowMs)) hiddenAt = elapsed
            is Code -> {
                readFrom.first(which, nowMs)
                if (seen.first(read.stamp, nowMs)) SmsCodes.hold(read.code, elapsed - read.ageMs, elapsed, mail = true)
            }
            else -> {}
        }
        return read
    }

    /** The switch in Settings › Row tools, which is the clipboard plugin's as the one for texts is. */
    fun wanted(prefs: KeyboardPrefs) = prefs.mailCodes && Plugins.isOn(prefs, Plugins.CLIPBOARD)

    private fun component(context: Context) = ComponentName(context, MailCodeListener::class.java)

    /** Whether Android lets the app see notifications: its own switch, in its settings, which only the person can turn. */
    fun allowed(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) manager.isNotificationListenerAccessGranted(component(context))
        else android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty().split(':').contains(component(context).flattenToString())
    }.getOrDefault(false)

    /**
     * Whether Android shows this app a notification it judges to hold a code as it is. Before Android 15 it always
     * does. From 15 it does only where the phone's owner has allowed the app that (an app-op that Android gives no
     * switch for: `adb shell cmd appops set` is how it is set); with Android's own search for codes turned off there
     * is nothing for it to hide either, which cannot be seen from here.
     *
     * Android looks at this only when a listener connects (NotificationManagerService, Android 16; and seen on an
     * emulator: allowed, the next code was still hidden until the listener had connected again). So the listener
     * remembers what it was when it connected, and connects again where it has changed ([MailCodeListener.reconnect]).
     */
    fun trusted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 35) return true
        return runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
    }

    /** Android's name for the app-op that lets a notification listener see notifications that hold a code (AppOpsManager, hidden there). */
    private const val OP = "android:receive_sensitive_notifications"

    /** What the phone's owner types on a computer, once, for [trusted]. */
    fun command(packageName: String) = "adb shell cmd appops set --user 0 $packageName RECEIVE_SENSITIVE_NOTIFICATIONS allow"

    /**
     * What Android puts in place of a notification's words when it hides them, in the phone's language: Android's
     * own string, read by its name. Null before Android 15, and where it is not to be had.
     */
    fun hiddenText(): String? {
        if (Build.VERSION.SDK_INT < 35) return null
        return runCatching {
            val system = Resources.getSystem()
            system.getIdentifier("redacted_notification_message", "string", "android").takeIf { it != 0 }?.let(system::getString)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Sensitive notification content hidden"
    }

    /**
     * Makes Android match the switch: the listener is enabled only while the switch is on, so with it off no
     * notification reaches the app, whatever access it still holds; and the codes that came by mail are dropped.
     */
    fun apply(context: Context) {
        val on = wanted(KeyboardPrefs(context))
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching { context.packageManager.setComponentEnabledSetting(component(context), state, PackageManager.DONT_KILL_APP) }
        if (!on) { SmsCodes.drop(mail = true); hiddenAt = 0L }
        // Switched back on with access still given: Android is asked to connect the listener again.
        else if (allowed(context)) runCatching { NotificationListenerService.requestRebind(component(context)) }
    }
}

/**
 * Where Android hands over notifications once the person has given the app notification access (the manifest lets
 * only Android itself bind this, and keeps it off until [MailCodes.apply] turns it on). One look at a mail app's for
 * a code, and it is let go: nothing is logged, kept or sent.
 */
class MailCodeListener : NotificationListenerService() {
    private val hiddenText by lazy { MailCodes.hiddenText() }

    /** Connected: a mail that came in the last few minutes may still be on the screen, and its code is as good as one that comes now. */
    override fun onListenerConnected() {
        live = this
        trustedThen = MailCodes.trusted(this)
        runCatching { activeNotifications }.getOrNull()?.forEach(::look)
    }

    override fun onListenerDisconnected() {
        if (live === this) live = null
    }

    override fun onDestroy() {
        if (live === this) live = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::look)
    }

    companion object {
        /** The listener while Android has it connected, and whether Android showed it codes when it connected ([MailCodes.trusted]). */
        @Volatile private var live: MailCodeListener? = null
        @Volatile private var trustedThen = false

        /**
         * Connects to Android again where the phone's owner has changed whether this app is shown codes since the
         * listener connected: Android takes that up only on connecting. Connected afresh, the listener goes over
         * what is on the screen, so a code Android had hidden a moment ago is read after all.
         */
        fun reconnect(context: Context) {
            val listener = live ?: return
            if (MailCodes.trusted(context) == trustedThen) return
            runCatching {
                listener.requestUnbind()
                requestRebind(ComponentName(context, MailCodeListener::class.java))
            }
        }
    }

    private fun look(sbn: StatusBarNotification) {
        runCatching {
            if (!MailCodes.wanted(KeyboardPrefs(this))) return
            val notification = sbn.notification ?: return
            // Whose it is and what kind it is, before a word of it is read.
            if (sbn.packageName !in MailCodes.sources() && notification.category != Notification.CATEGORY_EMAIL) return
            val extras = notification.extras ?: return
            val post = MailCodes.Post(
                MailCodes.source(sbn.packageName), notification.category, notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(), extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                notification.`when`, sbn.postTime, sbn.key,
            )
            val read = MailCodes.took(post, System.currentTimeMillis(), SystemClock.elapsedRealtime(), hiddenText)
            // Hidden, though the phone's owner has said this app may see codes: Android has not taken that up yet.
            if (read is MailCodes.Hidden) reconnect(this)
        }
    }
}
