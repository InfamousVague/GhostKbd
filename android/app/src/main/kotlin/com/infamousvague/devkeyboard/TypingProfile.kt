package com.infamousvague.devkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Typing profiles (docs/typing-profile.md, docs/typing-profile.schema.json; Matt, 2026-11-09: "a standard way to
 * import training data on how a person types, so other models can build reports and import them into Ghost"): one
 * open JSON file for where someone aims at each key, their slips, their words, which words they put together, and
 * how they write. Exported from what this keyboard has learned; imported into Adaptive keys, autocorrect's learned
 * words, next-word prediction and Mockingbird.
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
     * where the two differ. Waits for that cleaning, so not for the main thread. The word pairs next-word prediction
     * has counted go only with [pairs], since they can show phrases and names.
     */
    fun export(context: Context, samples: Boolean, pairs: Boolean = true): String {
        val prefs = KeyboardPrefs(context)
        val o = JSONObject()
            .put("format", FORMAT).put("version", VERSION)
            .put("created", Instant.now().toString())
            .put("source", JSONObject().put("app", Retrain.OWN_APP))
        // The layout being typed on names the file's own keys and slips; what other layouts have learned rides beside
        // them under "layouts", which older keyboards pass over.
        val active = prefs.layout
        o.put("layout", active)
        val (keys, slips) = keysAndSlips(AdaptiveKeys.get(context, active))
        if (keys.length() > 0) o.put("keys", keys)
        if (slips.length() > 0) o.put("slips", slips)
        val others = JSONObject()
        for (model in AdaptiveKeys.all(context)) {
            if (model.layout == active) continue
            val (k, s) = keysAndSlips(model)
            val part = JSONObject()
            if (k.length() > 0) part.put("keys", k)
            if (s.length() > 0) part.put("slips", s)
            if (part.length() > 0) others.put(model.layout, part)
        }
        if (others.length() > 0) o.put("layouts", others)
        val words = JSONObject()
        val learned = prefs.learnedWords
        if (learned.isNotEmpty()) words.put("learned", JSONArray(learned.sorted()))
        // Next-word prediction's counts: what followed each word (and each two words), "^" being a sentence's start.
        if (pairs) NextWords.get(context).exportPairs().takeIf { it.length() > 0 }?.let { words.put("next", it) }
        if (words.length() > 0) o.put("words", words)
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

    /** One layout's learned aims and remembered slips, as a profile carries them. */
    private fun keysAndSlips(adaptive: AdaptiveKeys): Pair<JSONObject, JSONArray> {
        val keys = JSONObject()
        adaptive.aims().forEach { (c, a) -> if (a.n > 0) keys.put(c.toString(), JSONObject().put("dx", round(a.dx)).put("dy", round(a.dy)).put("samples", a.n)) }
        val slips = JSONArray()
        // Each remembered slip with its point, so a report can draw them; the counts are read off them.
        adaptive.mistakes.forEach { m -> slips.put(JSONObject().put("pressed", m.pressed.toString()).put("meant", m.meant.toString()).put("count", 1).put("x", round(m.x)).put("y", round(m.y))) }
        return keys to slips
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

        // Key aims and slips are for a layout: the file's own ("layout", QWERTY where it says none) and any others
        // under "layouts". Each goes to that layout's model, where this keyboard has the layout.
        var aims = 0
        var slipCount = 0
        var sawKeys = false
        var sawSlips = false
        val unknown = LinkedHashSet<String>()
        fun takeIn(layout: String, keys: JSONObject?, slips: JSONArray?) {
            if (keys == null && slips == null) return
            sawKeys = sawKeys || keys != null
            sawSlips = sawSlips || slips != null
            if (!adaptiveOn) return
            if (Layouts.spec(layout) == null) { unknown += layout; return }
            val adaptive = AdaptiveKeys.get(context, layout)
            keys?.keys()?.forEach { k ->
                val v = keys.optJSONObject(k) ?: return@forEach
                if (k.length == 1 && adaptive.hasKey(k[0])) { adaptive.importAim(k[0], v.optDouble("dx", 0.0).toFloat(), v.optDouble("dy", 0.0).toFloat(), v.optInt("samples", 50)); aims++ }
            }
            if (slips != null) for (i in 0 until slips.length()) {
                val s = slips.optJSONObject(i) ?: continue
                val p = s.optString("pressed").singleOrNull() ?: continue
                val m = s.optString("meant").singleOrNull() ?: continue
                if (!adaptive.hasKey(p) || !adaptive.hasKey(m) || p.lowercaseChar() == m.lowercaseChar()) continue
                val x = if (s.has("x")) s.optDouble("x").toFloat() else null
                val y = if (s.has("y")) s.optDouble("y").toFloat() else null
                adaptive.importSlip(p, m, s.optInt("count", 1), x, y)
                slipCount += s.optInt("count", 1).coerceAtLeast(1)
            }
            adaptive.save()
        }
        takeIn(o.optString("layout", "qwerty").ifBlank { "qwerty" }, o.optJSONObject("keys"), o.optJSONArray("slips"))
        o.optJSONObject("layouts")?.let { more -> more.keys().forEach { id -> more.optJSONObject(id)?.let { takeIn(id, it.optJSONObject("keys"), it.optJSONArray("slips")) } } }
        if (!adaptiveOn) {
            if (sawKeys) lines += "Key aims skipped: add Adaptive keys to use them"
            if (sawSlips) lines += "Slips skipped: add Adaptive keys to use them"
        } else {
            if (sawKeys && (aims > 0 || unknown.isEmpty())) lines += count(aims, "key aim", "key aims") + " blended in"
            if (sawSlips && (slipCount > 0 || unknown.isEmpty())) lines += count(slipCount, "slip", "slips") + " learned"
            for (id in unknown) lines += "Key aims skipped: they are for $id, a layout this keyboard does not have"
        }

        o.optJSONObject("words")?.optJSONArray("learned")?.let { words ->
            var n = 0
            for (i in 0 until words.length()) {
                val w = words.optString(i).trim()
                if (w.isNotEmpty() && w.length <= 40 && w.none { it.isWhitespace() }) { prefs.learn(w); n++ }
            }
            lines += count(n, "word", "words") + " added to autocorrect"
        }

        // Word pairs are added to what next-word prediction has counted, under the same bounds and fading. They are
        // taken in even with its learning switched off: importing them is the person asking for them.
        o.optJSONObject("words")?.optJSONObject("next")?.let { next ->
            if (!Plugins.isOn(prefs, Plugins.AUTOCORRECT)) lines += "Word pairs skipped: add Autocorrect to use them"
            else lines += count(NextWords.get(context).importPairs(next), "word pair", "word pairs") + " added to next-word suggestions"
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
