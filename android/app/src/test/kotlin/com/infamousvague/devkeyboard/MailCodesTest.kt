package com.infamousvague.devkeyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One-time codes from email (MailCodes.kt, docs/mail-codes.md; Matt, 2026-11-12: "hook up gmail so new authentication
 * codes can be parsed and sent to the keyboard automatically"): which notifications are read, what is made of one,
 * and what the sources promise of it. The notifications here are made up in the shape Gmail's are reported to have
 * (its line of text the subject, its longer text the subject and the start of the mail): none was taken from a phone.
 */
class MailCodesTest {
    private val now = 1_800_000_000_000L
    private val hidden = "Sensitive notification content hidden"

    private fun post(
        text: String?, big: String? = null, pkg: String = "com.google.android.gm", category: String? = "email", summary: Boolean = false,
        whenMs: Long = now - 2_000, postedMs: Long = now - 500, key: String = "0|com.google.android.gm|1|null|10123",
    ) = MailCodes.Post(pkg, category, summary, text, big, whenMs, postedMs, key)

    private fun code(post: MailCodes.Post) = (MailCodes.read(post, now, hidden) as? MailCodes.Code)?.code

    @Test fun `a mail's code is found in its subject or the start of it`() {
        assertEquals("482913", code(post("482913 is your verification code")))
        assertEquals("482913", code(post("Your verification code", "Your verification code\nHi Matt, your verification code is 482913. It expires in 10 minutes.")))
        // The subject names it, and the mail begins with it.
        assertEquals("739204", code(post("Your login code", "Your login code\n739204\nEnter it on the sign-in page.")))
        // A longer text that does not begin with the subject has the subject put before it.
        assertEquals("739204", code(post("Your login code", "739204 Enter it on the sign-in page.")))
        // Written in halves, and letters with figures where the mail says outright that it is the code.
        assertEquals("482913", code(post("Sign in to Example", "Sign in to Example\nYour one-time code is 482-913")))
        assertEquals("5H7K2", code(post("Access from a new device", "Access from a new device\nHere is the code you need to log in. Code: 5H7K2")))
    }

    @Test fun `a mail without one is nothing`() {
        for (p in listOf(
            post("Lunch on Thursday?", "Lunch on Thursday?\nDoes 12:30 work? I booked a table for 4 at 1200 Main Street."),
            post("Your order 48291 has shipped", "Your order 48291 has shipped\nTrack it with reference 7729104."),
            post("Use code SAVE20 for 20% off", "Use code SAVE20 for 20% off\nThis weekend only."),
            post("Your receipt", "Your receipt\nTotal: $1,482.00 paid with card ending 4821."),
            post(null, null),
        )) assertSame(p.text, MailCodes.None, MailCodes.read(p, now, hidden))
    }

    @Test fun `only a mail app's notification is read`() {
        val words = "482913 is your verification code"
        // Another app's is not, whatever it says, unless it says it is email.
        assertSame(MailCodes.None, MailCodes.read(post(words, pkg = "com.whatsapp", category = "msg"), now, hidden))
        assertSame(MailCodes.None, MailCodes.read(post(words, pkg = "com.example.game", category = null), now, hidden))
        assertEquals("482913", code(post(words, pkg = "com.example.mail", category = "email")))
        // A mail app's is, whether or not it says what kind it is.
        for (app in MailCodes.APPS) assertEquals(app, "482913", code(post(words, pkg = app, category = null)))
        assertTrue("com.google.android.gm" in MailCodes.APPS)
        // The line that only sums up the others ("3 new messages") is not: each mail has its own.
        assertSame(MailCodes.None, MailCodes.read(post(words, summary = true), now, hidden))
    }

    @Test fun `an old mail's code is not news`() {
        val words = "482913 is your verification code"
        // On the screen for a while before the listener was started: its code is as old as that.
        val waited = MailCodes.read(post(words, postedMs = now - 4 * 60_000, whenMs = now - 4 * 60_000), now, hidden) as MailCodes.Code
        assertEquals(4 * 60_000L, waited.ageMs)
        // Longer than a code is held, and it is not taken at all.
        assertSame(MailCodes.None, MailCodes.read(post(words, postedMs = now - SmsCodes.KEEP_MS, whenMs = now - SmsCodes.KEEP_MS), now, hidden))
        // A mail from an hour ago posted again now (after a restart, a sync) is not a new mail.
        assertSame(MailCodes.None, MailCodes.read(post(words, postedMs = now - 100, whenMs = now - 60 * 60_000), now, hidden))
        // A notification that says no time, or one in the future, is judged by when Android posted it.
        assertEquals("482913", code(post(words, whenMs = 0)))
        assertEquals("482913", code(post(words, whenMs = now + 5_000)))
    }

    @Test fun `what Android has hidden is known for what it is`() {
        // Android 15 and later: the words replaced, in the line and in the longer text.
        assertSame(MailCodes.Hidden, MailCodes.read(post(hidden, hidden), now, hidden))
        assertSame(MailCodes.Hidden, MailCodes.read(post(hidden), now, hidden))
        // In the phone's own language, which is what the listener is given to look for.
        assertSame(MailCodes.Hidden, MailCodes.read(post("Contenu sensible masqué"), now, "Contenu sensible masqué"))
        // Before Android 15 nothing is hidden, and those words would be a mail's own.
        assertSame(MailCodes.None, MailCodes.read(post(hidden), now, null))
        // Another app's hidden notification is not the keyboard's business.
        assertSame(MailCodes.None, MailCodes.read(post(hidden, pkg = "com.whatsapp", category = "msg"), now, hidden))
    }

    @Test fun `a hidden copy of what was already read is no loss`() {
        SmsCodes.clear()
        val before = MailCodes.hiddenAt
        // Android has been seen to hand a notification over whole and then again with its words hidden.
        assertTrue(MailCodes.took(post("482913 is your verification code", key = "t1", whenMs = now - 1_000), now, 5_000_000, hidden) is MailCodes.Code)
        assertSame(MailCodes.Hidden, MailCodes.took(post(hidden, hidden, key = "t1", whenMs = now - 1_000), now + 300, 5_000_300, hidden))
        assertEquals(before, MailCodes.hiddenAt)
        assertEquals("482913", SmsCodes.fresh(5_000_300)?.text)
        // One that comes hidden with nothing read from it first is a code the keyboard did not get, and it says so.
        assertSame(MailCodes.Hidden, MailCodes.took(post(hidden, hidden, key = "t2", whenMs = now - 500), now + 400, 5_000_400, hidden))
        assertEquals(5_000_400L, MailCodes.hiddenAt)
        // So is a later mail in the same thread: the same name to Android, another time on it.
        assertSame(MailCodes.Hidden, MailCodes.took(post(hidden, hidden, key = "t1", whenMs = now + 2_000), now + 3_000, 5_003_000, hidden))
        assertEquals(5_003_000L, MailCodes.hiddenAt)
        SmsCodes.clear()
    }

    @Test fun `the same mail is one code however often it is posted`() {
        val seen = MailCodes.Seen()
        val first = MailCodes.read(post("482913 is your verification code", key = "a"), now, hidden) as MailCodes.Code
        // Posted again under another name, as Gmail has been seen to: the same mail, by its time and its code.
        val again = MailCodes.read(post("482913 is your verification code", key = "b"), now, hidden) as MailCodes.Code
        assertEquals(first.stamp, again.stamp)
        assertTrue(seen.first(first.stamp, now))
        assertFalse(seen.first(again.stamp, now + 60_000))
        // Another mail with another code, or the same code in a later mail (asked for again), is new.
        val other = MailCodes.read(post("739204 is your verification code"), now, hidden) as MailCodes.Code
        val later = MailCodes.read(post("482913 is your verification code", whenMs = now - 100), now, hidden) as MailCodes.Code
        assertTrue(seen.first(other.stamp, now))
        assertTrue(seen.first(later.stamp, now))
        // And it is forgotten once the mail is too old to be read anyway.
        assertTrue(seen.first(first.stamp, now + MailCodes.OLD_MS))
        // A notification that says no time is told apart by Android's name for it.
        val nameless = MailCodes.read(post("482913 is your verification code", whenMs = 0, key = "k1"), now, hidden) as MailCodes.Code
        assertEquals("k1|482913", nameless.stamp)
    }

    @Test fun `only the start of a mail is looked at`() {
        val long = "Your statement is ready. " + "x".repeat(700) + " Your code is 482913."
        assertSame(MailCodes.None, MailCodes.read(post("Statement", "Statement\n$long"), now, hidden))
        assertTrue(MailCodes.message("Statement", "Statement\n$long").length <= MailCodes.LOOK_CHARS)
        // The cut falls between words: a number is never cut in two.
        val edge = "Your verification code is here " + "word ".repeat(113) + "4829137"
        val cut = MailCodes.message(null, edge)
        assertTrue(cut.length <= MailCodes.LOOK_CHARS)
        assertTrue(cut, cut.endsWith("word") || cut.endsWith("4829137"))
        assertEquals("Subject\nBody", MailCodes.message("Subject", "Body"))
        assertEquals("Subject and more", MailCodes.message("Subject", "Subject and more"))
        assertEquals("Subject", MailCodes.message("Subject", null))
    }

    @Test fun `a code from a mail is held with the codes from texts, newest first`() {
        SmsCodes.clear()
        val t = 10_000_000L
        SmsCodes.take("Your code is 111111", t)
        // A mail's code that was on the screen two minutes before it was read is older than the text's.
        SmsCodes.hold("222222", t - 120_000, t + 1_000, mail = true)
        assertEquals(listOf("111111", "222222"), SmsCodes.live(t + 1_000).map { it.text })
        assertEquals(listOf(false, true), SmsCodes.live(t + 1_000).map { it.mail })
        // And it stops leading the row that much sooner.
        assertEquals("111111", SmsCodes.fresh(t + 1_000)?.text)
        SmsCodes.used(SmsCodes.live(t + 1_000).first())
        assertEquals("222222", SmsCodes.fresh(t + 30_000)?.text)
        assertEquals(null, SmsCodes.fresh(t - 120_000 + SmsCodes.FRESH_MS))
        // Turning one way off lets go of the codes that came that way, and of no others.
        SmsCodes.take("Your code is 333333", t + 2_000)
        SmsCodes.drop(mail = true)
        assertEquals(listOf("333333"), SmsCodes.live(t + 2_000).map { it.text })
        SmsCodes.hold("444444", t + 3_000, t + 3_000, mail = true)
        SmsCodes.drop(mail = false)
        assertEquals(listOf("444444"), SmsCodes.live(t + 3_000).map { it.text })
        SmsCodes.clear()
    }

    // --- what the sources promise (docs/mail-codes.md) -------------------------------------------------------------

    private val main = listOf("android/app/src/main", "src/main", "../app/src/main").map(::File).first { it.exists() }
    private val manifest = File(main, "AndroidManifest.xml").readText()
    private fun source(name: String) = File(main, "kotlin/com/infamousvague/devkeyboard/$name").readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun `the listener is off until switched on, and only Android can bind it`() {
        val service = Regex("<service\\s[^>]*android:name=\"\\.MailCodeListener\"[^>]*>").find(manifest)!!.value
        assertTrue(service, service.contains("android:enabled=\"false\""))
        assertTrue(service, service.contains("android:permission=\"android.permission.BIND_NOTIFICATION_LISTENER_SERVICE\""))
        // Notifications that stay up are never asked for.
        assertTrue(manifest.contains("android.service.notification.disabled_filter_types\" android:value=\"ongoing\""))
        // Switched on and off in one place, by the switch; and the listener asks the switch before it reads anything.
        val mail = source("MailCodes.kt")
        assertTrue(mail.contains("COMPONENT_ENABLED_STATE_ENABLED") && mail.contains("COMPONENT_ENABLED_STATE_DISABLED"))
        assertTrue(mail.contains("if (!MailCodes.wanted(KeyboardPrefs(this))) return"))
        // Off unless turned on.
        assertEquals(false, Regex("MAIL_CODES to (true|false)").find(source("KeyboardPrefs.kt"))!!.groupValues[1].toBoolean())
    }

    @Test fun `a notification is read for a code and nothing of it is kept, written or sent`() {
        val mail = source("MailCodes.kt")
        for (kept in listOf("getSharedPreferences", "openFileOutput", "File(", "filesDir", "cacheDir", "SQLite", ".edit()", "Log.", "println", "ClipboardManager", "ClipData", "Net.open", "Net.get", "URL(", "HttpURLConnection", "Socket")) {
            assertFalse(kept, mail.contains(kept))
        }
        // Of a notification only these are read: no title (the sender), no sub-text (the account), no picture, no people.
        val extras = Regex("Notification\\.EXTRA_[A-Z_]+").findAll(mail).map { it.value }.toSet()
        assertEquals(setOf("Notification.EXTRA_TEXT", "Notification.EXTRA_BIG_TEXT"), extras)
        // And an app that is not a mail app is turned away before its notification's words are touched.
        val look = mail.substringAfter("private fun look(sbn: StatusBarNotification) {")
        assertTrue(look.indexOf("sbn.packageName !in MailCodes.sources()") in 0 until look.indexOf("notification.extras"))
        // The debug build's stand-in for a mail app is Android's own shell and nothing else, and no release has it.
        assertEquals(1, Regex("BuildConfig\\.DEBUG\\) APPS \\+ SHELL else APPS").findAll(mail).count())
        assertEquals(listOf("\"com.android.shell\""), Regex("SHELL = (\"[^\"]+\")").findAll(mail).map { it.groupValues[1] }.toList())
        // No cancelling, snoozing or answering of anyone's notifications.
        for (act in listOf("cancelNotification", "cancelAllNotifications", "snoozeNotification", "actions", "contentIntent", "RemoteInput")) assertFalse(act, mail.contains(act))
    }
}
