package com.infamousvague.devkeyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One-time codes from Ghost Messaging (GhostMessagingCodes.kt, docs/ghost-messaging-codes.md): what of its answer
 * is held, and that the keyboard asks nobody but Ghost Messaging and keeps nothing.
 */
class GhostMessagingCodesTest {
    private val nowMs = 1_800_000_000_000L
    private val elapsed = 5_000_000L
    private fun row(code: String, minutesAgo: Double) = GhostMessagingCodes.Row(code, nowMs - (minutesAgo * 60_000).toLong())
    private fun hold(vararg rows: GhostMessagingCodes.Row, seen: MailCodes.Seen = MailCodes.Seen()) = GhostMessagingCodes.toHold(rows.toList(), nowMs, elapsed, seen)

    @Test fun `a code is held with the time its text arrived, on the keyboard's clock`() {
        assertEquals(listOf("482913" to elapsed - 120_000L), hold(row("482913", 2.0)))
        // Oldest first, so the newest is the last one held and leads the row.
        assertEquals(listOf("111111", "222222"), hold(row("222222", 1.0), row("111111", 3.0)).map { it.first })
    }

    @Test fun `a code is taken once, however often Ghost Messaging is asked`() {
        val seen = MailCodes.Seen()
        assertEquals(1, hold(row("482913", 1.0), seen = seen).size)
        // Asked again (another code arrived, the keys were shown again): the one already taken, perhaps typed since,
        // does not come back as new.
        assertEquals(listOf("999000"), hold(row("482913", 1.0), row("999000", 0.1), seen = seen).map { it.first })
        // The same digits in a later text are a new code.
        assertEquals(listOf("482913"), hold(row("482913", 0.05), seen = seen).map { it.first })
    }

    @Test fun `a code as old as codes are kept is not held, and one dated ahead is held as new`() {
        assertTrue(hold(row("482913", 10.0)).isEmpty())
        assertEquals(1, hold(row("482913", 9.9)).size)
        assertEquals(listOf("482913" to elapsed), hold(row("482913", -2.0)))
    }

    @Test fun `what is not shaped like a code is not typed for one`() {
        assertTrue(hold(row("", 1.0), row("ab", 1.0), row("hello", 1.0), row("12 34", 1.0), row("1234567890123", 1.0), row("https://x.co/1", 1.0)).isEmpty())
        assertEquals(listOf("G12345", "9041"), hold(row("9041", 1.0), row("G12345", 2.0)).map { it.first })
    }

    private val main = listOf("android/app/src/main", "src/main", "../app/src/main").map(::File).first { it.exists() }
    private fun code(name: String) = File(main, "kotlin/com/infamousvague/devkeyboard/$name").readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun `only Ghost Messaging is asked, by its package and its certificate`() {
        val source = code("GhostMessagingCodes.kt")
        assertTrue(source.contains("const val PACKAGE = \"com.infamousvague.ghostmessaging\""))
        // Who answers at the address is looked up and held to the package and the pinned certificate before it is asked.
        assertTrue(source.contains("provider.packageName != PACKAGE"))
        assertTrue(source.contains("Updater.certificates(context, PACKAGE).contains(CERTIFICATE)"))
        assertTrue(Regex("CERTIFICATE = \"[0-9a-f]{64}\"").containsMatchIn(source))
        val ask = source.substringAfter("private fun ask(context: Context) {").substringBefore("\n    }")
        assertTrue(ask, ask.contains("if (!wanted(KeyboardPrefs(context)) || !here(context)) return@runCatching"))
        // The phone is told the keyboard may see that app, and its address, and nothing more of it.
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<package android:name=\"com.infamousvague.ghostmessaging\" />"))
        assertTrue(manifest.contains("<provider android:authorities=\"com.infamousvague.ghostmessaging.codes\" />"))
    }

    @Test fun `nothing of a code is written, logged or sent`() {
        val source = code("GhostMessagingCodes.kt")
        for (kept in listOf("getSharedPreferences", "openFileOutput", "File(", "filesDir", "cacheDir", "SQLite", ".edit()", "Log.", "println", "ClipboardManager", "ClipData", "Net.open", "URL(", "HttpURLConnection", "Socket")) {
            assertFalse(kept, source.contains(kept))
        }
        // It is held with the codes from texts, and shown and typed as they are.
        assertTrue(source.contains("SmsCodes.hold(text, at, elapsed, mail = false)"))
        assertTrue(code("SmsCodes.kt").contains("GhostMessagingCodes.wanted(prefs)"))
    }
}
