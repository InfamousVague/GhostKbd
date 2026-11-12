package com.infamousvague.devkeyboard

import android.content.Context
import android.util.Log
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one door to the internet. Every connection Ghost Keyboard makes is opened here and nowhere else (tool/net_check.sh
 * fails the day another file opens one), so one switch, Settings › Offline ([KeyboardPrefs.OFFLINE]), can stop them
 * all, and no feature can forget to ask.
 *
 * Before a connection exists [open] asks the ledger ([NetLedger.check]): with Offline on, nothing; only https; only a
 * server the path lists. Refusing comes before the connection is made, so with Offline on there is not even a name
 * lookup. Redirects are never followed blindly: API calls treat one as an error (a key is never passed on to another
 * server), and downloads follow them by hand through [get], each hop checked like the first.
 *
 * The switch is asked again at the moment of sending: what [open] hands out is a [Guarded] connection, which refuses
 * to connect, send or read once Offline is on, however long ago it was opened. So a request that was being made ready
 * when the switch was turned on never leaves.
 *
 * Blocking, like the connections it hands out: call it off the main thread. The key-press path never comes here; the
 * keyboard reads [KeyboardPrefs.offline] itself to draw its row.
 */
object Net {
    /** What every refused feature shows while the switch is on. */
    const val OFFLINE_MESSAGE = NetLedger.OFFLINE_MESSAGE

    private const val TAG = "GhostNet"

    /** A connection the guard would not open; its message is for the person ("Offline is on"). */
    class Refused(message: String) : IOException(message)

    /** The connections handed out and not yet disconnected by their owners, held weakly, so [cut] can end them. */
    private val live = ArrayList<WeakReference<Guarded>>()

    /**
     * A connection that asks the switch again before anything that would reach the network: connecting, sending,
     * and reading the answer or its headers. With Offline on those throw [Refused] (the header readers, which cannot
     * throw, answer as a connection that failed does: nothing). Everything else is the real connection's own.
     * Disconnecting it takes it off [live].
     */
    private class Guarded(private val c: HttpURLConnection, private val prefs: KeyboardPrefs) : HttpURLConnection(c.url) {
        /** Throws [Refused] where Offline is on, ending the real connection first. */
        private fun ask() {
            if (!offline(prefs)) return
            runCatching { c.disconnect() }
            throw Refused(OFFLINE_MESSAGE)
        }

        /** Whether the network may be reached; where not, the real connection is ended. */
        private fun may(): Boolean = !offline(prefs) || run { runCatching { c.disconnect() }; false }

        /** Ends the real connection without taking it off [live]: [cut]'s part, which may come before it has connected. */
        fun end() = c.disconnect()

        override fun connect() { ask(); c.connect() }
        override fun getOutputStream(): java.io.OutputStream { ask(); return c.outputStream }
        override fun getInputStream(): java.io.InputStream { ask(); return c.inputStream }
        override fun getResponseCode(): Int { ask(); return c.responseCode }
        override fun getResponseMessage(): String? { ask(); return c.responseMessage }
        override fun getContent(): Any? { ask(); return c.content }
        override fun getErrorStream(): java.io.InputStream? = if (may()) c.errorStream else null
        override fun getHeaderField(name: String?): String? = if (may()) c.getHeaderField(name) else null
        override fun getHeaderField(n: Int): String? = if (may()) c.getHeaderField(n) else null
        override fun getHeaderFieldKey(n: Int): String? = if (may()) c.getHeaderFieldKey(n) else null
        override fun getHeaderFields(): Map<String, List<String>> = if (may()) c.headerFields else emptyMap()
        override fun getHeaderFieldInt(name: String?, default: Int): Int = if (may()) c.getHeaderFieldInt(name, default) else default
        override fun getHeaderFieldLong(name: String?, default: Long): Long = if (may()) c.getHeaderFieldLong(name, default) else default
        override fun getHeaderFieldDate(name: String?, default: Long): Long = if (may()) c.getHeaderFieldDate(name, default) else default
        override fun getContentLength(): Int = if (may()) c.contentLength else -1
        override fun getContentLengthLong(): Long = if (may()) c.contentLengthLong else -1L
        override fun getContentType(): String? = if (may()) c.contentType else null
        override fun getContentEncoding(): String? = if (may()) c.contentEncoding else null
        override fun getExpiration(): Long = if (may()) c.expiration else 0L
        override fun getDate(): Long = if (may()) c.date else 0L
        override fun getLastModified(): Long = if (may()) c.lastModified else 0L

        override fun disconnect() {
            synchronized(live) { live.removeAll { it.get().let { g -> g == null || g === this } } }
            c.disconnect()
        }
        override fun usingProxy(): Boolean = c.usingProxy()
        override fun setRequestMethod(method: String?) { c.requestMethod = method }
        override fun getRequestMethod(): String = c.requestMethod
        override fun setInstanceFollowRedirects(follow: Boolean) { c.instanceFollowRedirects = follow }
        override fun getInstanceFollowRedirects(): Boolean = c.instanceFollowRedirects
        override fun setFixedLengthStreamingMode(length: Int) = c.setFixedLengthStreamingMode(length)
        override fun setFixedLengthStreamingMode(length: Long) = c.setFixedLengthStreamingMode(length)
        override fun setChunkedStreamingMode(chunk: Int) = c.setChunkedStreamingMode(chunk)
        override fun setConnectTimeout(ms: Int) { c.connectTimeout = ms }
        override fun getConnectTimeout(): Int = c.connectTimeout
        override fun setReadTimeout(ms: Int) { c.readTimeout = ms }
        override fun getReadTimeout(): Int = c.readTimeout
        override fun getURL(): URL = c.url
        override fun setDoInput(on: Boolean) { c.doInput = on }
        override fun getDoInput(): Boolean = c.doInput
        override fun setDoOutput(on: Boolean) { c.doOutput = on }
        override fun getDoOutput(): Boolean = c.doOutput
        override fun setAllowUserInteraction(on: Boolean) { c.allowUserInteraction = on }
        override fun getAllowUserInteraction(): Boolean = c.allowUserInteraction
        override fun setUseCaches(on: Boolean) { c.useCaches = on }
        override fun getUseCaches(): Boolean = c.useCaches
        override fun setDefaultUseCaches(on: Boolean) { c.defaultUseCaches = on }
        override fun getDefaultUseCaches(): Boolean = c.defaultUseCaches
        override fun setIfModifiedSince(since: Long) { c.ifModifiedSince = since }
        override fun getIfModifiedSince(): Long = c.ifModifiedSince
        override fun setRequestProperty(key: String?, value: String?) = c.setRequestProperty(key, value)
        override fun addRequestProperty(key: String?, value: String?) = c.addRequestProperty(key, value)
        override fun getRequestProperty(key: String?): String? = c.getRequestProperty(key)
        override fun getRequestProperties(): Map<String, List<String>> = c.requestProperties
        override fun toString(): String = c.toString()
    }

    /** Whether Offline is on. Never throws: a setting that cannot be read counts as offline. */
    fun offline(prefs: KeyboardPrefs): Boolean = runCatching { prefs.offline }.getOrDefault(true)

    /** The API root of the AI chosen in Settings (a provider's, or the person's own server): the one server the AI's paths may reach. */
    private fun aiServer(prefs: KeyboardPrefs): String? = runCatching {
        val p = AiClient.provider(prefs.aiProvider)
        (if (p.id == "custom") prefs.aiBaseUrl else p.base).trim().ifEmpty { null }
    }.getOrNull()

    /** Why [path] may not connect to [url] as things stand, or null when it may: [NetLedger.check] with this phone's settings. */
    fun refusal(prefs: KeyboardPrefs, path: NetPath, url: String): String? =
        NetLedger.check(offline(prefs), path, url, if (path.ownServer) aiServer(prefs) else null)

    /**
     * A connection to [url] for [path], not yet sent, or [Refused]. It follows no redirect by itself and says it is
     * Ghost Keyboard. The caller sets its method, headers and timeouts, and disconnects it when done. It is
     * [Guarded]: it asks the switch again each time it would reach the network.
     */
    fun open(prefs: KeyboardPrefs, path: NetPath, url: String): HttpURLConnection {
        refusal(prefs, path, url)?.let { reason ->
            log("refused", path, url)
            throw Refused(reason)
        }
        val real = URL(url).openConnection() as? HttpURLConnection ?: throw Refused("Ghost Keyboard only connects over https")
        real.instanceFollowRedirects = false
        real.setRequestProperty("User-Agent", "GhostKeyboard")
        val c = Guarded(real, prefs)
        cutPrefs = prefs
        synchronized(live) {
            live.removeAll { it.get() == null }
            live.add(WeakReference(c))
        }
        // The switch may have been turned on while this was being made: it wins.
        if (offline(prefs)) {
            runCatching { c.disconnect() }
            log("refused", path, url)
            throw Refused(OFFLINE_MESSAGE)
        }
        log("open", path, url)
        return c
    }

    /**
     * A GET that may be sent elsewhere (a model on Hugging Face's CDN, a GIF, the APK): up to five redirects followed
     * by hand, each hop through [open] and so checked against the ledger, with [configure] (timeouts, a Range) put on
     * each. Back comes the connection that answered with something other than a redirect, its response code read.
     */
    fun get(prefs: KeyboardPrefs, path: NetPath, url: String, configure: HttpURLConnection.() -> Unit = {}): HttpURLConnection {
        var at = url
        repeat(6) {
            val c = open(prefs, path, at)
            try {
                c.configure()
                val code = c.responseCode
                val next = if (code in REDIRECTS) c.getHeaderField("Location") else null
                if (next.isNullOrBlank()) return c
                at = URL(URL(at), next).toString()
                c.disconnect()
            } catch (e: Exception) {
                runCatching { c.disconnect() }
                throw e
            }
        }
        throw Refused("Too many redirects")
    }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    /**
     * Ends every request and download in flight: called as Offline is switched on ([KeyboardPrefs.set]). The sockets
     * are closed off the caller's thread (closing one can itself write to the network, which Android forbids on the
     * main thread); each feature then sees its connection fail and says "Offline is on" ([message]).
     *
     * A connection that has not connected yet has no socket to close, and stays on [live]: its [Guarded] refuses when
     * it comes to connect. One that passed that check in the instant before the switch was turned would slip through
     * both, so the list is gone over again a moment later and once more after that, while Offline is still on. Each
     * connection leaves the list when its owner disconnects it.
     */
    fun cut() {
        if (synchronized(live) { live.none { it.get() != null } }) return
        Thread({
            for (wait in CUT_PASSES_MS) {
                runCatching { Thread.sleep(wait) }
                if (!cutting()) break
                synchronized(live) { live.mapNotNull { it.get() } }.forEach { c -> runCatching { c.end() } }
            }
        }, "ghost-net-cut").start()
    }

    /** How long before each of [cut]'s passes over the connections in flight: at once, then twice more. */
    private val CUT_PASSES_MS = longArrayOf(0L, 300L, 1500L)

    /** Whether [cut] should go on: Offline is still on. Asked through the newest connection's settings. */
    private fun cutting(): Boolean = cutPrefs?.let { offline(it) } ?: true

    /** The settings [cut] reads the switch from on its later passes: those of whoever opened a connection last. */
    @Volatile private var cutPrefs: KeyboardPrefs? = null

    /** What to tell the person when a connection failed with [e]: the guard's own words, or [otherwise]. */
    fun message(prefs: KeyboardPrefs, e: IOException, otherwise: String): String = when {
        e is Refused -> e.message ?: OFFLINE_MESSAGE
        offline(prefs) -> OFFLINE_MESSAGE
        else -> otherwise
    }

    /** Debug builds say which path opened or was refused, and the server: never the address, which can carry a key. */
    private fun log(what: String, path: NetPath, url: String) {
        if (BuildConfig.DEBUG) Log.d(TAG, "$what ${path.id} ${NetLedger.hostOf(url) ?: "?"}")
    }

    // --- what Settings draws ------------------------------------------------------------------------------------------

    /**
     * Whether [path] can happen as Settings stand, leaving the Offline switch aside: its plugin added, its key or
     * token there, its own switch on. Every path must answer here, so a new one cannot be left off the list.
     */
    private fun armed(context: Context, prefs: KeyboardPrefs, path: NetPath): Boolean {
        val ai = AiClient.ready(prefs)
        fun on(id: String) = Plugins.isOn(prefs, id)
        return when (path) {
            NetPath.GHOSTWRITER -> on(Plugins.GHOSTWRITER) && ai
            NetPath.REWRITE -> on(Plugins.REWRITE) && ai
            NetPath.ENHANCE -> on(Plugins.ENHANCE) && ai
            NetPath.TIDY -> on(Plugins.DICTATION) && ai && prefs.dictationTidy
            NetPath.MY_VOICE, NetPath.TRAIN -> on(Plugins.MOCKINGBIRD) && ai
            NetPath.AI_TEST, NetPath.AI_MODELS -> ai
            NetPath.GITHUB_SEARCH -> on(Plugins.GHOSTWRITER) && ai && prefs.ghostwriterGithub && prefs.githubToken.isNotBlank()
            NetPath.GITHUB_CHECK -> prefs.githubToken.isNotBlank()
            NetPath.GHOST_SIGN_IN -> on(Plugins.GHOSTWRITER) && !GhostOnline.signedIn(context)
            NetPath.GHOST_SEARCH -> on(Plugins.GHOSTWRITER) && ai && prefs.ghostwriterNotes && GhostOnline.signedIn(context)
            NetPath.GHOST_SIGN_OUT -> GhostOnline.signedIn(context)
            NetPath.GIF_SEARCH, NetPath.GIF_IMAGE -> on(Plugins.GIFS) && Giphy.key(prefs).isNotBlank()
            NetPath.MODEL -> on(Plugins.DICTATION)
            NetPath.UPDATE_CHECK, NetPath.UPDATE_DOWNLOAD -> !Updater.isDebuggable(context)
            NetPath.REPORT -> prefs.shareReports
        }
    }

    /** The servers [path] can reach as Settings stand: the ledger's, or for the AI's paths the one provider chosen. */
    private fun hostsNow(prefs: KeyboardPrefs, path: NetPath): List<String> =
        if (path.ownServer) listOfNotNull(aiServer(prefs)?.let { NetLedger.hostOf(it) }) else path.hosts

    /**
     * The ledger as Settings › Offline and the Market draw it: `offline`; `features` (id, title, stops, sections);
     * `sections`, every settings page that says "Offline is on"; `paths` (id, feature, title, hosts, trigger, sends,
     * armed); `works` (id, title, line), dictation's line saying which model is on the phone; and `hosts`, each
     * plugin's servers by its id.
     */
    fun toMap(context: Context, prefs: KeyboardPrefs): Map<String, Any?> {
        val model = WhisperModel.SPECS.filter { WhisperModel.isReady(context, it) }
        val active = WhisperModel.active(context)
        val dictation = when {
            WhisperModel.isReady(context, active) -> "${active.name} is on this phone."
            model.isNotEmpty() -> "${model.first().name} is on this phone; choose it in Settings › Dictation."
            offline(prefs) -> "No speech model is on this phone yet, and none can be fetched while Offline is on."
            else -> "Once a speech model is on the phone. None is yet."
        }
        val plugins = LinkedHashMap<String, LinkedHashSet<String>>()
        for (p in NetPath.values()) for (id in p.plugins) {
            // The Market says whose server the AI's is: the one chosen, not a provider the plugin is tied to.
            val hosts = if (p.ownServer) listOf(hostsNow(prefs, p).firstOrNull()?.let { "$it (the AI you chose)" } ?: "the AI server you choose") else p.hosts
            plugins.getOrPut(id) { LinkedHashSet() }.addAll(hosts)
        }
        return mapOf(
            "offline" to offline(prefs),
            "message" to OFFLINE_MESSAGE,
            "features" to NetFeature.values().map { mapOf("id" to it.id, "title" to it.title, "stops" to it.stops, "sections" to it.sections) },
            "sections" to NetFeature.values().flatMap { it.sections }.distinct(),
            "paths" to NetPath.values().map { p ->
                mapOf(
                    "id" to p.id, "feature" to p.feature.id, "title" to p.title, "hosts" to hostsNow(prefs, p), "ownServer" to p.ownServer,
                    "trigger" to p.trigger, "sends" to p.sends, "armed" to runCatching { armed(context, prefs, p) }.getOrDefault(false),
                )
            },
            "works" to NetLedger.WORKS.map { mapOf("id" to it.id, "title" to it.title, "line" to if (it.id == "dictation") dictation else it.line) },
            "hosts" to plugins.mapValues { it.value.toList() },
        )
    }
}
