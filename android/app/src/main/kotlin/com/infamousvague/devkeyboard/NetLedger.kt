package com.infamousvague.devkeyboard

/**
 * The ledger: every reason Ghost Keyboard has to open a connection, with the servers it may reach, what starts it and
 * what is sent. It is the one list three things read, so they cannot disagree:
 *
 * - the guard ([Net.open]) refuses a connection to any server its path does not list here, and every connection while
 *   Offline is on;
 * - Settings › Offline draws "What it stops", "Still works" and "What leaves your phone" from it ([Net.toMap]);
 * - the Market's "It talks to … and nowhere else" reads each plugin's servers from it.
 *
 * Nothing here touches Android, so tool/net_check.sh can compile it by itself and try it. A new feature that needs the
 * network adds a [NetPath] (the build then asks [Net] to say when it is armed) and connects through [Net.open] or
 * [Net.get]; tool/net_check.sh fails while any other file opens a connection of its own.
 */
object NetLedger {
    /** What every refused feature says while the switch is on: short enough for a chip in the row. */
    const val OFFLINE_MESSAGE = "Offline is on"

    /** The AI providers with an address of their own (AiClient.PROVIDERS, less Custom). */
    val AI_HOSTS = listOf(
        "api.anthropic.com", "api.openai.com", "generativelanguage.googleapis.com", "api.mistral.ai", "api.groq.com",
        "api.deepseek.com", "openrouter.ai",
    )

    /** One thing that goes on working with Offline on: its [id], its [title] and a [line] under it in Settings. */
    class Works(val id: String, val title: String, val line: String)

    /** What needs no connection, as Settings › Offline lists it under "Still works". "dictation"'s line is filled in live. */
    val WORKS = listOf(
        Works("typing", "Typing, autocorrect, suggestions, next words and swipe", "The word lists and the next-word starter tables, for every language that has them, are inside the app; what it learns of your typing is counted on this phone."),
        Works("learning", "Adaptive keys, typing stats and per-app profiles", "Learned and kept on this phone."),
        Works("row", "Clipboard history, emoji, undo and redo, markdown and terminal keys", "None of them has ever needed a connection."),
        Works("dictation", "Dictation", "With a speech model already on the phone."),
        Works("ghostmd", "Ghost.md on this phone", "[[links]], #tags and Save to Ghost.md."),
        Works("passwords", "Password manager logins", "Android and your manager draw and fill them; their connections are their own."),
        Works("samples", "Mockingbird’s samples", "Still kept on this phone where that switch is on. None is sent to train."),
        Works("install", "An update already downloaded", "It can still be installed from About, after a restart too: nothing more is fetched."),
    )

    /** The host an address names, lower case, or null where it has none worth trusting (a user@ part, a backslash, a space). */
    fun hostOf(url: String): String? {
        if (url.any { it == '\\' || it.isWhitespace() || it.isISOControl() }) return null
        val u = runCatching { java.net.URL(url) }.getOrNull() ?: return null
        if (u.userInfo != null) return null
        return u.host?.lowercase()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
    }

    /** The port an address connects to: its own, or 443. */
    private fun portOf(url: String): Int = runCatching { java.net.URL(url).let { if (it.port == -1) 443 else it.port } }.getOrDefault(-1)

    /** Whether [host] is one a ledger entry [pattern] names: the same name, or for "*.giphy.com" any name under giphy.com. */
    fun matches(pattern: String, host: String): Boolean =
        if (pattern.startsWith("*.")) host.endsWith(pattern.substring(1)) && host.length > pattern.length - 1 else host == pattern

    /**
     * Why [path] may not connect to [url], in words for the person, or null when it may. In order: Offline refuses
     * everything; only https; then the server must be one the path lists. The AI's paths may reach one server only,
     * [aiServer], the API root of the provider chosen in Settings (or the person's own server), so a key never goes
     * to a provider it was not made for.
     */
    fun check(offline: Boolean, path: NetPath, url: String, aiServer: String? = null): String? {
        if (offline) return OFFLINE_MESSAGE
        if (!url.startsWith("https://", ignoreCase = true)) return "Ghost Keyboard only connects over https"
        val host = hostOf(url) ?: return "Ghost Keyboard can’t read that address"
        val allowed = if (path.ownServer) {
            val own = aiServer?.let { hostOf(it) }
            own != null && own == host && portOf(aiServer) == portOf(url)
        } else {
            portOf(url) == 443 && path.hosts.any { matches(it, host) }
        }
        return if (allowed) null else "Ghost Keyboard doesn’t talk to $host"
    }
}

/**
 * A feature the Offline switch stops, as Settings › Offline lists it: its [title], what [stops], and the settings
 * pages ([sections], by their ids in lib/settings/panes.dart) that say "Offline is on" at their top while it is.
 */
enum class NetFeature(val id: String, val title: String, val stops: String, val sections: List<String>) {
    AI("ai", "AI", "Ghostwriter, Rewrite, In my voice, Mockingbird’s training, Test it and Load models.", listOf("ai", "ai-provider", "mockingbird", "ai-style", "ghostwriter", "ai-filling", "rewrite")),
    GITHUB("github", "GitHub commit search", "Ghostwriter’s search of your commits, and Check the token.", listOf("ai-sources")),
    GHOST_ONLINE("ghost-online", "Ghost.md online", "Signing in, searching your notes online, and signing out.", listOf("ai-sources")),
    GIFS("gifs", "GIFs", "GIPHY’s trending and search, and loading or sending a GIF.", listOf("rowtools")),
    MODELS("models", "Speech model downloads", "Fetching a dictation model. One already on the phone keeps working.", listOf("dictation")),
    UPDATES("updates", "Updates", "The check for a new version, by itself and by hand, the dot on the settings cog, and the download.", listOf("about")),
}

/**
 * One reason to connect. [hosts] are the servers it may reach, each a name or "*.domain" for any name under one;
 * [ownServer] marks the AI's paths, which reach the one provider chosen in Settings: one of [hosts], or the person's
 * own server. [plugins] are the Market plugins it belongs to (lib/plugins/catalogue.dart ids), [trigger] is what
 * starts it and [sends] what goes with it, both in words Settings shows as they are.
 */
enum class NetPath(
    val feature: NetFeature,
    val id: String,
    val title: String,
    val hosts: List<String>,
    val ownServer: Boolean = false,
    val plugins: List<String> = emptyList(),
    val trigger: String,
    val sends: String,
) {
    GHOSTWRITER(
        NetFeature.AI, "ghostwriter", "Ghostwriter", NetLedger.AI_HOSTS, ownServer = true, plugins = listOf("ghostwriter"),
        trigger = "Typing >> with “Fill as soon as I type >>” on, tapping Fill, or pressing enter on a <<request>>.",
        sends = "Your key and model, the request, up to 1,500 characters before it and 400 after, the name of the app you are typing in, today’s date and your style profile. " +
            "Each behind its own switch, and never more than: matching past messages (6,000 characters), Ghost.md notes (9,000, and their titles), commit messages (9,000, each with its date and repository) and recent clipboard (2,500). " +
            "With “The web” on, the provider may search online itself. OpenRouter is also told this app’s name and its page, attack.fm/devkeyboard.",
    ),
    REWRITE(
        NetFeature.AI, "rewrite", "Rewrite", NetLedger.AI_HOSTS, ownServer = true, plugins = listOf("rewrite"),
        trigger = "Tapping Rewrite, then one of its choices.",
        sends = "Your key and model, and the selection or the whole field (up to 6,000 characters).",
    ),
    MY_VOICE(
        NetFeature.AI, "my-voice", "In my voice", NetLedger.AI_HOSTS, ownServer = true, plugins = listOf("mockingbird"),
        trigger = "Tapping In my voice with text selected.",
        sends = "Your key and model, the selection, your style profile, and up to 3,000 characters of similar things you typed.",
    ),
    SHERLOCK(
        NetFeature.AI, "sherlock", "Sherlock", NetLedger.AI_HOSTS, ownServer = true, plugins = listOf("sherlock"),
        trigger = "Tapping Reply, Another or Retry; opening the keyboard in an empty chat box with “Draft as I open a chat” on; and with Auto-reply on, each new message from the other person while a chat app is open.",
        sends = "Your key and model, the chat read off the screen (up to 5,000 characters, the newest kept), what you had typed in the box, the chat app's name, and with Mockingbird your style profile and up to 2,500 characters of similar things you typed.",
    ),
    TRAIN(
        NetFeature.AI, "train", "Mockingbird’s training", NetLedger.AI_HOSTS, ownServer = true, plugins = listOf("mockingbird"),
        trigger = "Tapping Train now. By itself with “Retrain by itself” on: once 50 new samples are kept, no sooner than 20 hours after the last time.",
        sends = "Your key and model, and your newest samples: up to 14,000 characters of texting and 10,000 of writing.",
    ),
    AI_TEST(
        NetFeature.AI, "ai-test", "Test it", NetLedger.AI_HOSTS, ownServer = true,
        trigger = "Tapping Test it in Settings › Your AI.",
        sends = "Your key and model, and the words “Are you there?”.",
    ),
    AI_MODELS(
        NetFeature.AI, "ai-models", "Load models", NetLedger.AI_HOSTS, ownServer = true,
        trigger = "Tapping Load models in Settings › Your AI.",
        sends = "Your key.",
    ),
    GITHUB_SEARCH(
        NetFeature.GITHUB, "github-search", "GitHub commit search", listOf("api.github.com"), plugins = listOf("ghostwriter"),
        trigger = "Each Ghostwriter request, with “Search my commits” on and a token pasted.",
        sends = "Your token, up to 4 words of the request, searched across up to 6 accounts and organizations, and a date where the request names one (“this week”).",
    ),
    GITHUB_CHECK(
        NetFeature.GITHUB, "github-check", "Check the token", listOf("api.github.com"),
        trigger = "Tapping Check the token.",
        sends = "Your token.",
    ),
    GHOST_SIGN_IN(
        NetFeature.GHOST_ONLINE, "ghost-sign-in", "Ghost.md sign-in", listOf("ghostmarkdown.com"), plugins = listOf("ghostwriter"),
        trigger = "Tapping Sign in to search online, and coming back from your browser.",
        sends = "This app’s name and return address, to register it; then the sign-in code and its proof, for tokens. Your password is typed in your browser, never here.",
    ),
    GHOST_SEARCH(
        NetFeature.GHOST_ONLINE, "ghost-search", "Ghost.md notes online", listOf("ghostmarkdown.com"), plugins = listOf("ghostwriter"),
        trigger = "A Ghostwriter request with “Search my notes” on, once signed in, when Ghost.md on this phone finds nothing.",
        sends = "Your sign-in token, up to 4 words of the request, and the ids of up to 3 notes to read. A refresh token when the sign-in has run out.",
    ),
    GHOST_SIGN_OUT(
        NetFeature.GHOST_ONLINE, "ghost-sign-out", "Ghost.md sign-out", listOf("ghostmarkdown.com"),
        trigger = "Tapping Sign out.",
        sends = "The refresh token, so the server ends the session.",
    ),
    GIF_SEARCH(
        NetFeature.GIFS, "gif-search", "GIF search", listOf("api.giphy.com"), plugins = listOf("gifs"),
        trigger = "Opening the GIF panel, which loads what is trending, and each search.",
        sends = "The GIPHY key and your search words.",
    ),
    GIF_IMAGE(
        NetFeature.GIFS, "gif-image", "GIF pictures", listOf("*.giphy.com"), plugins = listOf("gifs"),
        trigger = "Showing results, up to 30 small pictures at a time, and picking one to send.",
        sends = "Which pictures are asked for.",
    ),
    MODEL(
        NetFeature.MODELS, "model", "Speech model download", listOf("attack.fm", "huggingface.co", "*.huggingface.co", "*.hf.co"), plugins = listOf("dictation"),
        trigger = "Adding Dictation, and choosing a speech model that isn’t on the phone.",
        sends = "The model file’s name, and where to carry on from when part of it is already here.",
    ),
    UPDATE_CHECK(
        NetFeature.UPDATES, "update-check", "Update check", listOf("attack.fm"),
        trigger = "Opening the app with “Install updates by itself” on; the keyboard, at most every 12 hours, even with that switch off; and tapping Check for updates.",
        sends = "The request and the time it was made. No version and no id.",
    ),
    UPDATE_DOWNLOAD(
        NetFeature.UPDATES, "update-download", "Update download", listOf("attack.fm"),
        trigger = "A newer version found with “Install updates by itself” on, or tapping Update now.",
        sends = "The request alone.",
    ),
}
