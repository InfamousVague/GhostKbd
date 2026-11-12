// The Offline switch's self-check: run by tool/net_check.sh, which compiles this with the app's own NetLedger.kt (the
// ledger has no Android in it, so no emulator and no Gradle are needed). It tries the guard's rule on real cases, and
// reads the sources for anything that could reach the network without going through Net.kt.
//
//   tool/net_check.sh        from the repository's root; exits 1 and says what is wrong when something is.

import com.infamousvague.devkeyboard.NetFeature
import com.infamousvague.devkeyboard.NetLedger
import com.infamousvague.devkeyboard.NetPath
import java.io.File
import kotlin.system.exitProcess

private val failures = ArrayList<String>()
private var checks = 0

/** One thing that must be true; [what] is said when it is not. */
private fun expect(ok: Boolean, what: String) {
    checks++
    if (!ok) failures += what
}

/** A Kotlin source without its comments, so words in a doc comment are not taken for code. */
private fun code(text: String): String =
    text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").lines().joinToString("\n") { line ->
        // A // comment, but not the // of an address inside a string.
        val at = Regex("(?<![:\"A-Za-z])//").find(line)?.range?.first
        if (at == null) line else line.substring(0, at)
    }

fun main(args: Array<String>) {
    val root = File(args.firstOrNull() ?: ".")
    val kotlin = File(root, "android/app/src/main/kotlin")
    val own = "https://ollama.home.example:11434/v1"
    val claude = "https://api.anthropic.com/v1"

    // --- the ledger is whole ------------------------------------------------------------------------------------------
    expect(NetPath.values().map { it.id }.distinct().size == NetPath.values().size, "two ledger paths share an id")
    for (p in NetPath.values()) {
        // A connector's path lists no server of its own: the servers are the ones the person added.
        expect(p.hosts.isNotEmpty() != p.personal, if (p.personal) "${p.name} is the person's own servers' and must list none" else "${p.name} lists no server")
        expect(p.trigger.length > 10 && p.trigger.endsWith("."), "${p.name} does not say what starts it")
        expect(p.sends.length > 5 && p.sends.endsWith("."), "${p.name} does not say what is sent")
        expect(p.title.isNotBlank(), "${p.name} has no title")
        for (h in p.hosts) expect(Regex("^(\\*\\.)?[a-z0-9-]+(\\.[a-z0-9-]+)+$").matches(h), "${p.name}: \"$h\" is not a server's name")
        expect(p.ownServer == (p.feature == NetFeature.AI), "${p.name}: only the AI's paths reach the chosen provider")
        expect(p.personal == (p.feature == NetFeature.CONNECTORS), "${p.name}: only the connectors' paths reach the servers the person added")
        expect(!(p.ownServer && p.personal), "${p.name} cannot be both the AI's and a connector's")
    }
    for (f in NetFeature.values()) {
        expect(NetPath.values().any { it.feature == f }, "${f.name} has no path")
        expect(f.sections.isNotEmpty() && f.stops.endsWith("."), "${f.name} does not say what it stops, or on which page")
    }
    expect(NetLedger.WORKS.map { it.id }.contains("dictation"), "the still-works list has no dictation line")

    // --- Offline refuses everything -----------------------------------------------------------------------------------
    val added = listOf("https://mcp.example.com/mcp", "https://home.example.org:8443/mcp")
    for (p in NetPath.values()) {
        val host = p.hosts.firstOrNull()?.removePrefix("*.") ?: "mcp.example.com"
        expect(NetLedger.check(true, p, "https://$host/x", claude, added) == "Offline is on", "${p.name} is not refused while Offline is on")
    }

    // --- with Offline off, only https, only the ledger's servers --------------------------------------------------------
    fun ok(p: NetPath, url: String, ai: String? = null) = expect(NetLedger.check(false, p, url, ai) == null, "${p.name} should reach $url, but: ${NetLedger.check(false, p, url, ai)}")
    fun no(p: NetPath, url: String, ai: String? = null) = expect(NetLedger.check(false, p, url, ai) != null, "${p.name} must not reach $url")
    ok(NetPath.GIF_SEARCH, "https://api.giphy.com/v1/gifs/trending?api_key=k&limit=30")
    ok(NetPath.GIF_IMAGE, "https://media2.giphy.com/media/abc/200w.gif")
    no(NetPath.GIF_IMAGE, "http://media2.giphy.com/media/abc/200w.gif")
    no(NetPath.GIF_IMAGE, "https://evilgiphy.com/a.gif")
    no(NetPath.GIF_IMAGE, "https://giphy.com.evil.io/a.gif")
    no(NetPath.GIF_IMAGE, "https://media.giphy.com@evil.io/a.gif")
    no(NetPath.GIF_IMAGE, "https://evil.io\\@media.giphy.com/a.gif")
    no(NetPath.GIF_IMAGE, "https://media.giphy.com:8443/a.gif")
    no(NetPath.GIF_SEARCH, "https://media2.giphy.com/media/abc/200w.gif")
    ok(NetPath.MODEL, "https://ghostkbd.com/models/ggml-base.en-q5_1.bin")
    no(NetPath.MODEL, "https://attack.fm/glyph/models/ggml-base.en-q5_1.bin")
    ok(NetPath.MODEL, "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin")
    ok(NetPath.MODEL, "https://us.aws.cdn.hf.co/xet-bridge-us/641ab5/3e88?response-content-type=application%2Foctet-stream")
    ok(NetPath.MODEL, "https://cdn-lfs.huggingface.co/repos/aa/bb/cc")
    no(NetPath.MODEL, "https://hf.co.evil.io/model.bin")
    no(NetPath.MODEL, "https://example.com/ggml-tiny-q5_1.bin")
    // ghostkbd.com since 0.12.1, and nowhere else: the address before it is no longer one the app may ask.
    ok(NetPath.UPDATE_CHECK, "https://ghostkbd.com/apk.json?t=1")
    ok(NetPath.UPDATE_DOWNLOAD, "https://ghostkbd.com/devkeyboard.apk")
    no(NetPath.UPDATE_CHECK, "https://attack.fm/devkeyboard/apk.json?t=1")
    no(NetPath.UPDATE_DOWNLOAD, "https://attack.fm/devkeyboard/devkeyboard.apk")
    no(NetPath.UPDATE_CHECK, "https://www.ghostkbd.com.evil.io/apk.json")
    ok(NetPath.REPORT, "https://ghostkbd.com/report")
    no(NetPath.REPORT, "https://attack.fm/devkeyboard/report")
    no(NetPath.UPDATE_DOWNLOAD, "https://github.com/someone/releases/devkeyboard.apk")
    ok(NetPath.GITHUB_SEARCH, "https://api.github.com/search/commits?q=posthog+user%3Ame")
    no(NetPath.GITHUB_SEARCH, "https://github.com/login")
    ok(NetPath.GHOST_SEARCH, "https://ghostmarkdown.com/api/mcp")
    ok(NetPath.GHOST_SIGN_IN, "https://ghostmarkdown.com/api/mcp/authorize")
    no(NetPath.GHOST_SIGN_IN, "https://ghostmarkdown.com.signin.example/authorize")
    no(NetPath.GHOST_SEARCH, "https://api.github.com/user")

    // --- the AI's paths: the chosen provider, and no other -------------------------------------------------------------
    ok(NetPath.GHOSTWRITER, "https://api.anthropic.com/v1/messages", claude)
    no(NetPath.GHOSTWRITER, "https://api.openai.com/v1/chat/completions", claude)
    no(NetPath.GHOSTWRITER, "https://api.anthropic.com/v1/messages", null)
    ok(NetPath.REWRITE, "https://ollama.home.example:11434/v1/chat/completions", own)
    no(NetPath.REWRITE, "https://ollama.home.example/v1/chat/completions", own)
    no(NetPath.REWRITE, "http://ollama.home.example:11434/v1/chat/completions", own)
    no(NetPath.GIF_IMAGE, "https://ollama.home.example:11434/a.gif", own)
    no(NetPath.MODEL, "https://api.anthropic.com/v1/messages", claude)

    // --- the connectors' paths: the servers the person added, and no other ------------------------------------------------
    for (p in NetPath.values().filter { it.personal }) {
        fun reach(url: String, list: List<String> = added) = NetLedger.check(false, p, url, claude, list)
        expect(reach("https://mcp.example.com/mcp") == null, "${p.name} should reach a server that was added")
        expect(reach("https://mcp.example.com/other/page") == null, "${p.name} should reach another page of a server that was added")
        expect(reach("https://home.example.org:8443/mcp") == null, "${p.name} should reach an added server at its own port")
        expect(reach("https://home.example.org/mcp") != null, "${p.name} must not reach another port of an added server")
        expect(reach("https://mcp.example.com:8443/mcp") != null, "${p.name} must not reach another port of an added server")
        expect(reach("http://mcp.example.com/mcp") != null, "${p.name} must not connect without https")
        expect(reach("https://evil.mcp.example.com/mcp") != null, "${p.name} must not reach a name under an added server")
        expect(reach("https://mcp.example.com.evil.io/mcp") != null, "${p.name} must not reach a look-alike of an added server")
        expect(reach("https://mcp.example.com@evil.io/mcp") != null, "${p.name} must not be fooled by a user part")
        expect(reach("https://api.anthropic.com/v1/messages") != null, "${p.name} must not reach the AI's server")
        expect(reach("https://ghostkbd.com/apk.json") != null, "${p.name} must not reach a server of another path")
        expect(reach("https://mcp.example.com/mcp", emptyList()) != null, "${p.name} must reach nothing when no server is added")
    }
    // A server being added lets no other path reach it: the AI's key, and every other request, go where they always did.
    for (p in NetPath.values().filter { !it.personal }) {
        expect(NetLedger.check(false, p, "https://mcp.example.com/mcp", claude, added) != null, "${p.name} must not reach a connector's server")
    }

    // --- the sources: one door ----------------------------------------------------------------------------------------
    val sources = kotlin.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    expect(sources.size > 20, "the Kotlin sources were not found under ${kotlin.path} (run from the repository's root)")
    val banned = listOf(
        Regex("\\.openConnection\\(") to "opens a connection of its own",
        Regex("\\bURL\\(") to "builds a URL to connect to",
        Regex("\\b(Server|Datagram|SSL)?Socket\\(") to "opens a socket",
        Regex("\\bWebView\\b") to "uses a WebView",
        Regex("\\bDownloadManager\\b") to "uses Android's DownloadManager",
        Regex("\\b(OkHttpClient|HttpClient|Retrofit|Volley|Cronet|ExoPlayer)\\b") to "brings an HTTP stack of its own",
        Regex("\\bInetAddress\\b") to "looks a server's name up",
    )
    val ledgerHosts = NetPath.values().flatMap { it.hosts }
    for (f in sources) {
        val text = code(f.readText())
        val door = f.name == "Net.kt"
        val ledger = f.name == "NetLedger.kt"
        for ((pattern, what) in banned) {
            if (door || (ledger && what.startsWith("builds a URL"))) continue
            expect(!pattern.containsMatchIn(text), "${f.name} $what: only Net.kt may")
        }
        // A MediaPlayer may play a file inside the app (a file descriptor), never an address.
        for (line in text.lines().filter { "setDataSource(" in it }) expect("fileDescriptor" in line, "${f.name} plays media from something other than a file in the app")
        expect(!Regex("\"http://").containsMatchIn(text), "${f.name} names an http:// address")
        // Every server a source names is one the ledger lists.
        for (m in Regex("https://([A-Za-z0-9.-]+)").findAll(text)) {
            val host = m.groupValues[1].lowercase().trimEnd('.')
            if (door || ledger || host.isEmpty() || !host.contains('.')) continue
            expect(ledgerHosts.any { NetLedger.matches(it, host) }, "${f.name} names $host, which no ledger path lists")
        }
    }
    // The fixed providers' API roots are the ledger's AI servers, each reachable on the AI's paths when it is the one chosen.
    val bases = Regex("\"(https://[^\"]+)\"").findAll(File(kotlin, "com/infamousvague/devkeyboard/AiClient.kt").readText()).map { it.groupValues[1] }.filter { "/v1" in it }.toList()
    expect(bases.size == NetLedger.AI_HOSTS.size, "AiClient has ${bases.size} providers with an address, the ledger ${NetLedger.AI_HOSTS.size}")
    for (b in bases) {
        expect(NetLedger.hostOf(b) in NetLedger.AI_HOSTS, "AiClient's $b is not among the ledger's AI servers")
        ok(NetPath.GHOSTWRITER, "$b/anything", b)
    }

    // --- the settings app: no network of its own, and the two sides agree ----------------------------------------------
    val dartBanned = listOf("dart:io", "package:http", "NetworkImage", "Image.network", "HttpClient", "url_launcher", "WebSocket", "package:dio", "webview")
    val dart = (File(root, "lib").walkTopDown() + File(root, "packages/glacier/lib").walkTopDown()).filter { it.isFile && it.extension == "dart" }.toList()
    expect(dart.size > 20, "the Dart sources were not found")
    for (f in dart) for (word in dartBanned) expect(word !in f.readText(), "${f.path} uses $word: the settings app has no network of its own")
    val pubspec = File(root, "pubspec.yaml").readText() + File(root, "packages/glacier/pubspec.yaml").takeIf { it.exists() }?.readText().orEmpty()
    for (word in listOf("http:", "dio:", "url_launcher", "webview", "cached_network_image", "firebase", "sentry")) expect(word !in pubspec, "pubspec.yaml depends on $word")
    val gradle = File(root, "android/app/build.gradle.kts").readText()
    for (word in listOf("okhttp", "retrofit", "ktor", "volley", "cronet", "firebase", "sentry", "play-services")) expect(word !in gradle.lowercase(), "build.gradle.kts depends on $word")
    expect("OFFLINE to false" in File(kotlin, "com/infamousvague/devkeyboard/KeyboardPrefs.kt").readText(), "KeyboardPrefs: Offline is not off by default")
    expect("'offline': false" in File(root, "lib/settings_store.dart").readText(), "settings_store.dart: Offline is not off by default")
    val panes = File(root, "lib/settings/panes.dart").readText()
    for (id in NetFeature.values().flatMap { it.sections }.distinct()) expect("'$id'" in panes, "the ledger names the settings page '$id', which panes.dart does not have")
    val catalogue = File(root, "lib/plugins/catalogue.dart").readText()
    for (id in NetPath.values().flatMap { it.plugins }.distinct()) expect("id: '$id'" in catalogue, "the ledger names the plugin '$id', which the catalogue does not have")

    if (failures.isEmpty()) {
        println("net_check: $checks checks passed (${NetPath.values().size} ledger paths, ${sources.size} Kotlin and ${dart.size} Dart sources read)")
    } else {
        println("net_check: ${failures.size} of $checks checks FAILED")
        failures.forEach { println("  - $it") }
        exitProcess(1)
    }
}
