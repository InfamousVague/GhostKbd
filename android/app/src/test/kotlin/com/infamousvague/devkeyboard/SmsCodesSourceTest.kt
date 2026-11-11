package com.infamousvague.devkeyboard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What docs/sms-codes.md promises of one-time codes from texts, held against the sources themselves: what the app
 * asks Android for, that the receiver is off until switched on and only Android can reach it, and that a code is
 * kept in memory and goes nowhere but into the field it is tapped into.
 */
class SmsCodesSourceTest {
    private val main = listOf("android/app/src/main", "src/main", "../app/src/main").map(::File).first { it.exists() }
    private val kotlin = File(main, "kotlin/com/infamousvague/devkeyboard")
    private val manifest = File(main, "AndroidManifest.xml").readText()

    /** A source with its comments taken out: what it does, not what it says. */
    private fun code(name: String) = File(kotlin, name).readText().replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { it.substringBefore("//") }

    @Test fun `the app asks to receive texts, and for nothing else of them`() {
        val asked = Regex("<uses-permission\\s+android:name=\"android\\.permission\\.([A-Z_]+)\"").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue("RECEIVE_SMS" in asked)
        for (other in listOf("READ_SMS", "SEND_SMS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH", "READ_CONTACTS", "READ_PHONE_STATE", "READ_PHONE_NUMBERS")) assertFalse(other, other in asked)
        // A phone without a SIM can still install it.
        assertTrue(Regex("<uses-feature\\s+android:name=\"android\\.hardware\\.telephony\"\\s+android:required=\"false\"").containsMatchIn(manifest))
    }

    @Test fun `the receiver is off until switched on, and only Android can send to it`() {
        val receiver = Regex("<receiver\\s[^>]*android:name=\"\\.SmsCodeReceiver\"[^>]*>").find(manifest)!!.value
        assertTrue(receiver, receiver.contains("android:enabled=\"false\""))
        assertTrue(receiver, receiver.contains("android:permission=\"android.permission.BROADCAST_SMS\""))
        // Switched on and off in one place, by the switch.
        val sms = code("SmsCodes.kt")
        assertTrue(sms.contains("COMPONENT_ENABLED_STATE_ENABLED") && sms.contains("COMPONENT_ENABLED_STATE_DISABLED"))
        assertTrue(sms.contains("if (!SmsCodes.wanted(KeyboardPrefs(context))) return"))
    }

    @Test fun `a code is kept in memory and nowhere else`() {
        for (name in listOf("SmsCodes.kt", "OtpCodes.kt")) {
            val source = code(name)
            for (kept in listOf("getSharedPreferences", "openFileOutput", "File(", "filesDir", "cacheDir", "noBackupFilesDir", "SQLite", ".edit()", "Log.", "println", "ClipboardManager", "ClipData", "Net.open", "URL(", "HttpURLConnection", "Socket")) {
                assertFalse("$name: $kept", source.contains(kept))
            }
        }
        // The finder is text in and a code out: nothing of Android in it at all.
        assertFalse(code("OtpCodes.kt").contains("import android"))
        // The off-by-default setting is the only thing of it in the settings file.
        assertEquals(false, Regex("SMS_CODES to (true|false)").find(code("KeyboardPrefs.kt"))!!.groupValues[1].toBoolean())
    }

    @Test fun `a code goes only into the field it is tapped into`() {
        val service = code("DevKeyboardService.kt")
        // Typed as a paste, so nothing learns from it, and let go at once.
        val paste = service.substringAfter("private fun pasteCode(code: SmsCodes.Code) {").substringBefore("\n    }")
        assertTrue(paste, paste.contains("editor.insert(code.text, Kind.PASTE)"))
        assertTrue(paste, paste.contains("notTyped()"))
        assertTrue(paste, paste.contains("SmsCodes.used(code)"))
        // No other source reads the codes: not the clipboard history's file, not the AI, not the reports, not the stats.
        val readers = kotlin.listFiles()!!.filter { it.extension == "kt" && code(it.name).contains(Regex("SmsCodes\\.(live|fresh|take)\\(")) }.map { it.name }.sorted()
        assertEquals(listOf("DevKeyboardService.kt", "SmsCodes.kt"), readers)
        for (name in listOf("ClipHistory.kt", "Ghostwriter.kt", "AiClient.kt", "Mockingbird.kt", "Reports.kt", "TypingStats.kt", "TypingProfile.kt", "Rewrite.kt")) {
            assertFalse(name, code(name).contains("SmsCodes") || code(name).contains("OtpCodes"))
        }
    }
}
