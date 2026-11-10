package com.infamousvague.devkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Typing profiles (docs/typing-profile.md, docs/typing-profile.schema.json; Matt, 2026-11-09: "a standard way to
 * import training data on how a person types, so other models can build reports and import them into Ghost"): one
 * open JSON file for where someone aims at each key, their slips, their words and how they write. Exported from what
 * this keyboard has learned; imported into Adaptive keys, autocorrect's learned words and Mockingbird.
 */
object TypingProfile {
    const val FORMAT = "ghost-typing-profile"
    const val VERSION = 1

    class ProfileError(message: String) : Exception(message)

    /** What an import did, in a few lines for Settings. */
    data class Result(val lines: List<String>, val report: String?, val source: String?)

    /**
     * This keyboard's learning as a profile. Message samples only with [samples], since they are the person's words;
     * they go out as Mockingbird's AI reads them, with the obvious slips cleaned out, and as typed beside that
     * where the two differ. Waits for that cleaning, so not for the main thread.
     */
    fun export(context: Context, samples: Boolean): String {
        val prefs = KeyboardPrefs(context)
        val o = JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .put("created", Instant.now().toString())
            .put("source", JSONObject().put("app", Retrain.OWN_APP))
            .put("layout", "qwerty")
        val adaptive = AdaptiveKeys.get(context)
        val keys = JSONObject()
        adaptive.aims().forEach { (c, a) -> if (a.n > 0) keys.put(c.toString(), JSONObject().put("dx", round(a.dx)).put("dy", round(a.dy)).put("samples", a.n)) }
        if (keys.length() > 0) o.put("keys", keys)
        val slips = JSONArray()
        // Each remembered slip with its point, so a report can draw them; the counts are read off them.
        adaptive.mistakes.forEach { m -> slips.put(JSONObject().put("pressed", m.pressed.toString()).put("meant", m.meant.toString()).put("count", 1).put("x", round(m.x)).put("y", round(m.y))) }
        if (slips.length() > 0) o.put("slips", slips)
        val learned = prefs.learnedWords
        if (learned.isNotEmpty()) o.put("words", JSONObject().put("learned", JSONArray(learned.sorted())))
        val style = JSONObject()
        // The profile goes with the version of the style rules it was trained under, so an old one stays due its retrain.
        Mockingbird.profile(context)?.let { style.put("profile", it.text).put("profileVersion", Retrain.exportVersion(it.version, it.provider, it.model)) }
        if (samples) {
            val a = JSONArray()
            Mockingbird.cleanedSamples(context).forEach {
                val s = JSONObject().put("text", it.text).put("kind", if (it.chat) "chat" else "writing")
                // Where a slip was repaired, the sample as typed goes too: the keyboard that takes it in judges for itself.
                if (it.raw != null) s.put("typed", it.raw)
                a.put(s)
            }
            if (a.length() > 0) style.put("samples", a)
        }
        if (style.length() > 0) o.put("style", style)
        return o.toString(2)
    }

    /** "1 slip", "20 slips". */
    private fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"

    private fun round(f: Float) = Math.round(f * 1000) / 1000.0

    /** Takes [text] in; refuses what isn't a version 1 typing profile. Sections whose plugin isn't added are skipped. */
    fun import(context: Context, text: String): Result {
        val o = runCatching { JSONObject(text) }.getOrElse { throw ProfileError("That file isn't JSON") }
        if (o.optString("format") != FORMAT) throw ProfileError("That file isn't a Ghost typing profile (no \"format\": \"$FORMAT\")")
        if (o.optInt("version", 0) != VERSION) throw ProfileError("That typing profile is version ${o.opt("version")}; this keyboard reads version $VERSION")
        val prefs = KeyboardPrefs(context)
        val lines = ArrayList<String>()
        val adaptiveOn = Plugins.isOn(prefs, Plugins.ADAPTIVE)
        val adaptive = AdaptiveKeys.get(context)

        val layout = o.optString("layout", "qwerty")
        o.optJSONObject("keys")?.let { keys ->
            when {
                layout != "qwerty" -> lines += "Key aims skipped: they are for $layout, and this keyboard reads qwerty"
                !adaptiveOn -> lines += "Key aims skipped: add Adaptive keys to use them"
                else -> {
                    var n = 0
                    keys.keys().forEach { k ->
                        val v = keys.optJSONObject(k) ?: return@forEach
                        if (k.length == 1 && k[0] in 'a'..'z') { adaptive.importAim(k[0], v.optDouble("dx", 0.0).toFloat(), v.optDouble("dy", 0.0).toFloat(), v.optInt("samples", 50)); n++ }
                    }
                    lines += count(n, "key aim", "key aims") + " blended in"
                }
            }
        }
        o.optJSONArray("slips")?.let { slips ->
            if (!adaptiveOn) lines += "Slips skipped: add Adaptive keys to use them"
            else {
                var n = 0
                for (i in 0 until slips.length()) {
                    val s = slips.optJSONObject(i) ?: continue
                    val p = s.optString("pressed").firstOrNull() ?: continue
                    val m = s.optString("meant").firstOrNull() ?: continue
                    if (p !in 'a'..'z' || m !in 'a'..'z' || p == m) continue
                    val x = if (s.has("x")) s.optDouble("x").toFloat() else null
                    val y = if (s.has("y")) s.optDouble("y").toFloat() else null
                    adaptive.importSlip(p, m, s.optInt("count", 1), x, y)
                    n += s.optInt("count", 1)
                }
                lines += count(n, "slip", "slips") + " learned"
            }
        }
        if (adaptiveOn) adaptive.save()

        o.optJSONObject("words")?.optJSONArray("learned")?.let { words ->
            var n = 0
            for (i in 0 until words.length()) {
                val w = words.optString(i).trim()
                if (w.isNotEmpty() && w.length <= 40 && w.none { it.isWhitespace() }) { prefs.learn(w); n++ }
            }
            lines += count(n, "word", "words") + " added to autocorrect"
        }

        val source = o.optJSONObject("source")?.let { s -> listOf(s.optString("app"), s.optString("model")).filter { it.isNotBlank() }.joinToString(", ") }?.ifBlank { null }
        o.optJSONObject("style")?.let { style ->
            if (!Plugins.isOn(prefs, Plugins.MOCKINGBIRD)) lines += "Writing style skipped: add Mockingbird to use it"
            else {
                style.optString("profile").takeIf { it.isNotBlank() }?.let {
                    // Ghost Keyboard's own export says which style rules trained the profile; one that says nothing is from before there were two.
                    val version = Retrain.importVersion(o.optJSONObject("source")?.optString("app"), if (style.has("profileVersion")) style.optInt("profileVersion", 1) else null)
                    Mockingbird.importProfile(context, it, Retrain.importedBy(source), version)
                    lines += "Style profile replaced"
                }
                style.optJSONArray("samples")?.let { a ->
                    var n = 0
                    for (i in 0 until a.length()) {
                        val s = a.optJSONObject(i) ?: continue
                        // As typed where the file has it: this keyboard cleans a sample itself, against this person's other samples.
                        val t = s.optString("typed").takeIf { it.isNotBlank() } ?: s.optString("text").takeIf { it.isNotBlank() } ?: continue
                        Mockingbird.recordImported(context, t, s.optString("kind") == "chat"); n++
                    }
                    lines += count(n, "writing sample", "writing samples") + " added"
                }
            }
        }
        if (lines.isEmpty()) lines += "Nothing in it this keyboard reads"
        return Result(lines, o.optString("report").takeIf { it.isNotBlank() }?.take(4000), source)
    }
}
