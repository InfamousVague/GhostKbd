package com.infamousvague.devkeyboard

/**
 * What kind of app the keyboard is typing into, from its package (EditorInfo.packageName): plugins that suit some
 * apps (terminal keys in an SSH client, markdown in a notes app) show themselves there by default, and anywhere at all
 * with their "Everywhere" setting. Ghost.md is known by name, for integrations of its own.
 */
enum class AppKind { TERMINAL, MARKDOWN, CODE, CHAT, OTHER }

object AppContext {
    /** Ghost.md's Android builds (glyph android/app/build.gradle.kts). */
    val GHOST_MD = setOf("com.mattssoftware.glyph", "com.mattssoftware.glyph.flutter", "com.mattssoftware.glyph.perf")

    private val terminals = setOf(
        "com.termux", "com.server.auditor.ssh.client", "com.sonelli.juicessh", "org.connectbot", "jackpal.androidterm",
        "com.offsec.nhterm", "yarolegovich.materialterminal", "com.googlecode.android_scripting", "io.neoterm",
        "com.zpwebsites.linuxonandroid", "tech.ula", "com.termoneplus",
    )
    private val markdown = GHOST_MD + setOf(
        "md.obsidian", "net.cozic.joplin", "net.gsantner.markor", "notion.id", "com.github.android", "com.logseq.app",
        "com.orgzly", "io.github.leonardog.notes", "org.qosp.notes", "com.standardnotes", "com.automattic.simplenote",
        "com.bear.notes", "io.anytype.app", "com.ideashower.readitlater.pro",
    )
    private val code = setOf(
        "com.foxdebug.acode", "com.foxdebug.acodefree", "com.rhmsoft.code", "io.spck", "com.aide.ui", "com.tdcm.htmleditor",
        "com.vectras.vm", "com.github.android", "com.gitlab.mobile",
    )
    private val chat = setOf(
        "com.discord", "com.Slack", "org.telegram.messenger", "com.whatsapp", "org.thoughtcrime.securesms",
        "com.google.android.apps.messaging", "com.facebook.orca", "com.microsoft.teams", "im.vector.app",
    )

    fun kind(packageName: String?): AppKind = when (packageName) {
        null -> AppKind.OTHER
        in terminals -> AppKind.TERMINAL
        in markdown -> AppKind.MARKDOWN
        in code -> AppKind.CODE
        in chat -> AppKind.CHAT
        else -> AppKind.OTHER
    }

    fun isGhostMd(packageName: String?) = packageName in GHOST_MD
}
