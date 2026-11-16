package com.infamousvague.devkeyboard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.ContentObserver
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.Looper

/*
 * The keyboard's colour, the same as Ghost Messaging's and kept in step with it (Matt, 2026-11-15: "make the color
 * themes available the same as the ghost messaging app and keep the two in sync"; docs/accent-sync.md).
 *
 * The colours. [COLOURS] is Ghost Messaging's list of bubble colours (its lib/backgrounds.dart `bubbleColors`), by
 * the same ids, in the same order: Ghost (the keyboard's own cyan), Ink (the keys' own black or white), Wallpaper
 * (the accent Android takes from the wallpaper, Android 12 and later), then fixed colours. On the keyboard it is the
 * accent: the enter key, a chosen popup key, the backlight where it follows the theme ([KeyboardTheme.withAccent]).
 * Ghost leaves a theme its own accent, so a preset keeps its colour until another is chosen.
 *
 * In step. Each app keeps its choice and when it was made, and answers the other with the two at
 * `content://<package>.accent/current` (here [AccentProvider]; Ghost Messaging's AccentSync.kt is the same). Whichever
 * was chosen last wins: each app asks the other as it starts and comes back to the front, and watches the other's
 * address while it runs, so a colour chosen in one shows in the other at once. Taking the other's choice keeps its
 * time, so the two never trade the same choice back and forth.
 *
 * Whom it asks and answers. The two are signed with different keys, so each pins the other's certificate, as the
 * one-time codes do (GhostMessagingCodes.kt). Nothing is asked of anyone else, and no one else is answered. Only the
 * colour's id and its time cross: nothing typed, nothing about messages.
 */
object AccentSync {
    const val OTHER = GhostMessagingCodes.PACKAGE
    private const val OTHER_AUTHORITY = "$OTHER.accent"

    /** Ghost Messaging's `bubbleColors`, id to colour (null for the three that are not one fixed colour). */
    val COLOURS: LinkedHashMap<String, Int?> = linkedMapOf(
        "ghost" to null,
        "ink" to null,
        "wallpaper" to null,
        "white" to 0xFFFFFFFF.toInt(),
        "silver" to 0xFFD9DBE0.toInt(),
        "gray" to 0xFF8A8D93.toInt(),
        "graphite" to 0xFF3A3D42.toInt(),
        "blue" to 0xFF0A84FF.toInt(),
        "indigo" to 0xFF4F5BD5.toInt(),
        "purple" to 0xFF9A45C9.toInt(),
        "pink" to 0xFFE0457B.toInt(),
        "red" to 0xFFDD3A30.toInt(),
        "orange" to 0xFFE8730C.toInt(),
        "yellow" to 0xFFF2C200.toInt(),
        "green" to 0xFF2A9D4B.toInt(),
        "mint" to 0xFF1F9E8F.toInt(),
    )

    fun ownUri(context: Context): Uri = Uri.parse("content://${context.packageName}.accent/current")
    private val otherUri: Uri by lazy { Uri.parse("content://$OTHER_AUTHORITY/current") }

    /** The wallpaper's accent as Ghost Messaging's Wallpaper bubble has it: accent1 200 on dark, 600 on light. */
    fun wallpaper(context: Context, dark: Boolean): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching { context.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_600) }.getOrNull()
    }

    /** A colour was chosen here (Settings › Colours): stamped now, and the other app told. */
    fun chosen(context: Context) {
        KeyboardPrefs(context).prefs.edit().putString(KeyboardPrefs.ACCENT_AT, System.currentTimeMillis().toString()).apply()
        runCatching { context.contentResolver.notifyChange(ownUri(context), null) }
    }

    /**
     * What to do with the other app's answer: its id where it was chosen after ours ([mine] and [theirs] are the
     * times), else null. An id this keyboard does not know is not taken.
     */
    fun take(mine: Long, theirId: String?, theirs: Long): String? =
        if (theirId != null && theirId in COLOURS && theirs > mine) theirId else null

    /** Ghost Messaging is on this phone and is what answers at its address. */
    private fun trusted(context: Context): Boolean = runCatching {
        val provider = context.packageManager.resolveContentProvider(OTHER_AUTHORITY, 0) ?: return false
        provider.packageName == OTHER && (BuildConfig.DEBUG || Updater.certificates(context, OTHER).contains(GhostMessagingCodes.CERTIFICATE))
    }.getOrDefault(false)

    /** Asks Ghost Messaging for its colour and takes it if it is newer. True where the colour changed. */
    fun pull(context: Context): Boolean {
        if (!trusted(context)) return false
        val prefs = KeyboardPrefs(context)
        val (id, at) = runCatching {
            context.contentResolver.query(otherUri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) to c.getString(1).toLong() else null
            }
        }.getOrNull() ?: return false
        val taken = take(prefs.accentAt, id, at) ?: return false
        val changed = taken != prefs.accent
        prefs.prefs.edit().putString(KeyboardPrefs.ACCENT, taken).putString(KeyboardPrefs.ACCENT_AT, at.toString()).apply()
        // Ours changed too, for anything watching this side (the settings app's own preview reads the preferences).
        runCatching { context.contentResolver.notifyChange(ownUri(context), null) }
        return changed
    }

    private val nowUri: Uri by lazy { Uri.parse("content://$OTHER_AUTHORITY/now") }

    /**
     * The colour Ghost Messaging is wearing this moment: the open chat's own where it has one, else its colour everywhere
     * (Matt, 2026-11-15: "sync the keyboard colors to each chat so if i switch to a chat that uses different bubble
     * colors the keyboard should copy those instead"). Worn while the keyboard types in Ghost Messaging, never kept.
     * Null where it is not here, or answers nothing this keyboard knows.
     */
    fun now(context: Context): String? {
        if (!trusted(context)) return null
        return runCatching {
            context.contentResolver.query(nowUri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()?.takeIf { it in COLOURS }
    }

    /** Watches what [now] answers, calling [onChange] on the main thread when Ghost Messaging says it changed. */
    fun watchNow(context: Context, onChange: () -> Unit): ContentObserver? {
        if (!trusted(context)) return null
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        return if (runCatching { context.contentResolver.registerContentObserver(nowUri, false, observer) }.isSuccess) observer else null
    }

    /** Watches Ghost Messaging's address while [context] runs, pulling when it says its colour changed. */
    fun watch(context: Context, onChange: () -> Unit = {}): ContentObserver? {
        if (!trusted(context)) return null
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                Thread { if (pull(context)) Handler(Looper.getMainLooper()).post(onChange) }.start()
            }
        }
        return if (runCatching { context.contentResolver.registerContentObserver(otherUri, false, observer) }.isSuccess) observer else null
    }

    fun unwatch(context: Context, observer: ContentObserver?) {
        if (observer != null) runCatching { context.contentResolver.unregisterContentObserver(observer) }
    }
}

/**
 * `content://com.infamousvague.devkeyboard.accent/current`: the keyboard's colour (`accent`, one of
 * [AccentSync.COLOURS]' ids) and when it was chosen (`at`, milliseconds since 1970, 0 where it never was). Read-only,
 * and for Ghost Messaging alone (its package and the SHA-256 of its release certificate; in a debug build of the
 * keyboard, any build of it).
 */
class AccentProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val context = context ?: return null
        val cursor = MatrixCursor(arrayOf("accent", "at"))
        if (uri.lastPathSegment != "current" || !allowed(context)) return cursor
        val prefs = KeyboardPrefs(context)
        cursor.addRow(arrayOf<Any>(prefs.accent, prefs.accentAt.toString()))
        return cursor
    }

    private fun allowed(context: Context): Boolean {
        val packages = context.packageManager.getPackagesForUid(Binder.getCallingUid()) ?: return false
        if (AccentSync.OTHER !in packages) return false
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return debuggable || Updater.certificates(context, AccentSync.OTHER).contains(GhostMessagingCodes.CERTIFICATE)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
