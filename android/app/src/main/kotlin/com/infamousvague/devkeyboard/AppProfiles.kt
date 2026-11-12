package com.infamousvague.devkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-app profiles (Matt, 2026-11-09: "per app profiles"): a few settings that follow the app being typed in. An app
 * with a profile can have the number row, autocorrect's own fixes, the terminal keys, the agent keys, the markdown
 * buttons and incognito each forced on or off; a setting the profile leaves alone follows Settings as it always did.
 *
 * Profiles live in the keyboard's settings file ([KeyboardPrefs.prefs]) as one JSON object under [KEY], so the
 * service hears a change the moment Settings makes it. The apps typed in lately are kept beside them ([RECENT]: the
 * package and its name, nothing else) so Settings can offer them without asking Android for every installed app.
 */
object AppProfiles {
    const val KEY = "app_profiles"
    private const val RECENT = "app_recent"
    private const val MAX_RECENT = 12

    /** What a profile can set, in the order Settings lists them. */
    val FIELDS = listOf("number_row", "autocorrect", "terminal", "agent", "markdown", "incognito")

    class Profile(val pkg: String, val label: String, val values: Map<String, Boolean>) {
        /** On, off, or null where the profile leaves [key] to Settings. */
        fun flag(key: String): Boolean? = values[key]
    }

    private var parsedFrom: String? = null
    private var parsed: Map<String, Profile> = emptyMap()

    fun all(prefs: KeyboardPrefs): Map<String, Profile> {
        val raw = prefs.prefs.getString(KEY, "{}") ?: "{}"
        if (raw == parsedFrom) return parsed
        val map = LinkedHashMap<String, Profile>()
        runCatching {
            val o = JSONObject(raw)
            for (pkg in o.keys()) {
                val p = o.getJSONObject(pkg)
                val values = HashMap<String, Boolean>()
                for (f in FIELDS) if (p.has(f)) values[f] = p.optBoolean(f)
                map[pkg] = Profile(pkg, p.optString("label", pkg), values)
            }
        }
        parsedFrom = raw
        parsed = map
        return map
    }

    fun of(prefs: KeyboardPrefs, pkg: String?): Profile? = if (pkg == null) null else all(prefs)[pkg]

    private fun write(prefs: KeyboardPrefs, change: (JSONObject) -> Unit) {
        val o = runCatching { JSONObject(prefs.prefs.getString(KEY, "{}") ?: "{}") }.getOrElse { JSONObject() }
        change(o)
        prefs.prefs.edit().putString(KEY, o.toString()).apply()
    }

    /** A profile for [pkg] that changes nothing yet. */
    fun add(context: Context, prefs: KeyboardPrefs, pkg: String) {
        if (pkg.isBlank()) return
        write(prefs) { o -> if (!o.has(pkg)) o.put(pkg, JSONObject().put("label", label(context, pkg))) }
    }

    /** Sets [key] for [pkg]'s profile; null gives it back to Settings. */
    fun set(prefs: KeyboardPrefs, pkg: String, key: String, value: Boolean?) {
        if (key !in FIELDS) return
        write(prefs) { o ->
            val p = o.optJSONObject(pkg) ?: return@write
            if (value == null) p.remove(key) else p.put(key, value)
        }
    }

    fun remove(prefs: KeyboardPrefs, pkg: String) = write(prefs) { it.remove(pkg) }

    /** The app's name as its launcher shows it, or the last part of its package where Android won't say. */
    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercaseChar() }

    /** The keyboard opened in [pkg]: it goes to the head of the recent apps, unless it is there already. */
    fun seen(context: Context, prefs: KeyboardPrefs, pkg: String?) {
        if (pkg.isNullOrBlank() || pkg == context.packageName) return
        val now = recent(prefs)
        if (now.firstOrNull()?.first == pkg) return
        val next = (listOf(pkg to label(context, pkg)) + now.filter { it.first != pkg }).take(MAX_RECENT)
        val a = JSONArray()
        next.forEach { (p, l) -> a.put(JSONObject().put("p", p).put("l", l)) }
        prefs.prefs.edit().putString(RECENT, a.toString()).apply()
    }

    fun recent(prefs: KeyboardPrefs): List<Pair<String, String>> = runCatching {
        val a = JSONArray(prefs.prefs.getString(RECENT, "[]") ?: "[]")
        (0 until a.length()).map { a.getJSONObject(it) }.map { it.getString("p") to it.optString("l", it.getString("p")) }
    }.getOrElse { emptyList() }

    /** What Settings › Per-app profiles shows: each profile with what it sets, and the recent apps without one. */
    fun state(prefs: KeyboardPrefs): Map<String, Any> {
        val profiles = all(prefs)
        return mapOf(
            "profiles" to profiles.values.map { p -> mapOf("package" to p.pkg, "label" to p.label, "values" to p.values) },
            "recent" to recent(prefs).filter { it.first !in profiles }.map { (pkg, label) -> mapOf("package" to pkg, "label" to label) },
        )
    }
}
