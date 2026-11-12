package com.infamousvague.devkeyboard

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.media.MediaPlayer
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/**
 * The settings app's host. One channel, `dev_keyboard/settings`, gives the Flutter side the keyboard's settings
 * ([KeyboardPrefs], read live by the service) and its setup state, and opens the system's keyboard screens.
 */
class MainActivity : FlutterActivity() {
    private var channel: MethodChannel? = null
    private val prefs by lazy { KeyboardPrefs(this) }
    private val imm by lazy { getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager }
    private val onUpdate: (Updater.State) -> Unit = { channel?.invokeMethod("updateChanged", it.toMap(this)) }
    private val onModel: (WhisperModel.State) -> Unit = { channel?.invokeMethod("dictationChanged", dictationState()) }
    private val onLocalModel: () -> Unit = { channel?.invokeMethod("localAiChanged", localAiState()) }

    /**
     * Rewrite on this phone (LocalAi.kt, LocalModels.kt): whether this build can run a model at all ("built": where
     * it cannot, Settings shows none of this), the switch, whether Rewrite runs on the phone as things stand, and
     * every model with where it stands here.
     */
    private fun localAiState(): Map<String, Any?> = LocalModels.toMap(this) + mapOf(
        "built" to LocalAi.built(this),
        "on" to prefs.rewriteLocal,
        "serves" to LocalAi.serves(this, prefs),
    )

    /** A page the app was opened at (the keyboard's mic key, when the microphone is not allowed yet). */
    private var openAt: String? = null

    private fun dictationState(): Map<String, Any?> = WhisperModel.toMap(this) + mapOf(
        "language" to prefs.dictationLanguage,
        "languages" to WhisperModel.LANGUAGES.map { (code, name) -> mapOf("code" to code, "name" to name) },
        "microphone" to (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
    )

    /**
     * One-time codes from texts (SmsCodes.kt): the switch, whether Android has given the permission, and whether it
     * has been asked for before (after a refusal Android may not ask again, and the page then points at Android's own
     * page for the app). `android` and `target` are for the page's note about what Android 17 keeps back.
     */
    /** The clipboard plugin's history as Settings › Row tools shows it: how many clips it holds, and the pinned ones. */
    private fun clipboardState(): Map<String, Any?> {
        val all = ClipHistory.all(this)
        val pins = all.filter { it.pinned }
        return mapOf("count" to all.size, "pinned" to pins.size, "pins" to pins.map { it.text }, "maxPins" to ClipList.MAX_PINS)
    }

    private fun smsState(): Map<String, Any?> = mapOf(
        "on" to prefs.smsCodes,
        "allowed" to SmsCodes.allowed(this),
        "asked" to prefs.prefs.getBoolean(SMS_ASKED, false),
        "android" to Build.VERSION.SDK_INT,
        "target" to applicationInfo.targetSdkVersion,
    )

    private fun askForTexts() {
        prefs.prefs.edit().putBoolean(SMS_ASKED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.RECEIVE_SMS), REQUEST_SMS)
    }

    private var player: MediaPlayer? = null

    /** The walkthrough's voice: one of the app's Flutter assets, played once; false where the file is not there. */
    private fun play(asset: String): Boolean {
        stopAudio()
        return runCatching {
            val key = FlutterInjector.instance().flutterLoader().getLookupKeyForAsset(asset)
            assets.openFd(key).use { fd ->
                player = MediaPlayer().apply {
                    setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                    setOnCompletionListener { it.release(); if (player === it) player = null }
                    prepare()
                    start()
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun stopAudio() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    override fun onPause() {
        super.onPause()
        stopAudio()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { channel?.invokeMethod("open", it) }
        signedIn(intent)
        profileIntent(intent)
    }

    /** The browser back from Ghost.md's sign-in page (GhostOnline.REDIRECT): swap the code for tokens, then tell Settings. */
    private fun signedIn(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "ghostkbd" || uri.host != "ghost-md") return
        intent.data = null
        Thread {
            val error = runCatching { GhostOnline.finish(this, uri) }.exceptionOrNull()?.message
            runOnUiThread {
                channel?.invokeMethod("aiChanged", error)
                android.widget.Toast.makeText(this, error ?: "Signed in to Ghost.md", android.widget.Toast.LENGTH_SHORT).show()
            }
        }.start()
        openAt = "ai"
    }

    /**
     * What the export being saved may carry, as the switches beside it stood when the file picker was opened. Both
     * start off, and both are kept across the activity being made again while the picker is open
     * ([onSaveInstanceState]), so a choice to leave something out can never come back as "include it".
     */
    private var exportSamples = false
    private var exportPairs = false

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_EXPORT_SAMPLES, exportSamples)
        outState.putBoolean(STATE_EXPORT_PAIRS, exportPairs)
    }

    @Deprecated("FlutterActivity still routes results here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQUEST_IMPORT -> importProfile(uri)
            REQUEST_EXPORT -> Thread {
                val error = runCatching {
                    val text = TypingProfile.export(this, exportSamples, exportPairs)
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) } ?: throw TypingProfile.ProfileError("Couldn't write the file")
                }.exceptionOrNull()?.message
                runOnUiThread { channel?.invokeMethod("profileDone", mapOf("exported" to (error == null), "error" to error)) }
            }.start()
        }
    }

    /** A typing profile from a file (picked, opened with this app, or shared to it): taken in, then Settings told. */
    private fun importProfile(uri: Uri) {
        Thread {
            val outcome = runCatching {
                val text = contentResolver.openInputStream(uri)?.use { s -> s.readBytes().takeIf { it.size <= 8_000_000 }?.toString(Charsets.UTF_8) }
                    ?: throw TypingProfile.ProfileError("That file is empty or too big")
                TypingProfile.import(this, text)
            }
            runOnUiThread {
                val r = outcome.getOrNull()
                channel?.invokeMethod("profileDone", mapOf("lines" to r?.lines, "report" to r?.report, "source" to r?.source, "error" to outcome.exceptionOrNull()?.message))
            }
        }.start()
    }

    /** Opened with, or shared to, this app: a .json file is a typing profile to import. */
    private fun profileIntent(intent: Intent?) {
        intent ?: return
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return
        if (uri.scheme == "ghostkbd") return
        intent.action = null
        openAt = "profile"
        importProfile(uri)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SMS) { channel?.invokeMethod("smsCodesChanged", smsState()); return }
        channel?.invokeMethod("dictationChanged", dictationState())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportSamples = savedInstanceState?.getBoolean(STATE_EXPORT_SAMPLES) == true
        exportPairs = savedInstanceState?.getBoolean(STATE_EXPORT_PAIRS) == true
        Updater.addListener(onUpdate)
        // A release found before the app was last closed is on offer again (and can be installed with Offline on).
        Updater.restore(this)
        KeyboardPrefs(this).addNewDefaults()
        KeyboardPrefs(this).pinLegacyLayouts()
        WhisperModel.refresh(this)
        WhisperModel.addListener(onModel)
        LocalModels.addListener(onLocalModel)
        openAt = intent?.getStringExtra(EXTRA_OPEN)
        signedIn(intent)
        profileIntent(intent)
        // Automatic updates: look each time the app opens, and install what is found (never while Offline is on).
        if (KeyboardPrefs(this).autoUpdate) Updater.check(this, install = true)
    }

    override fun onDestroy() {
        Updater.removeListener(onUpdate)
        WhisperModel.removeListener(onModel)
        LocalModels.removeListener(onLocalModel)
        super.onDestroy()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // The live preview at the foot of Settings: the keyboard's own view, drawn in the app (KeyboardPreview.kt).
        flutterEngine.platformViewsController.registry.registerViewFactory(KeyboardPreviewFactory.VIEW_TYPE, KeyboardPreviewFactory())
        // The typing trainer's screen has a channel of its own (TrainerChannel.kt).
        TrainerChannel.register(this, flutterEngine.dartExecutor.binaryMessenger)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dev_keyboard/settings").apply {
            setMethodCallHandler { call, result ->
                when (call.method) {
                    "getSettings" -> result.success(prefs.all())
                    "setSetting" -> {
                        val key = call.argument<String>("key")
                        val ok = key != null && prefs.set(key, call.argument<Any>("value"))
                        // The codes' switch by this door too: Android's receiver follows it (never the permission).
                        if (ok && key == KeyboardPrefs.SMS_CODES) SmsCodes.apply(this@MainActivity)
                        if (ok) result.success(null) else result.error("bad_setting", "Unknown setting or wrong type: $key", null)
                    }
                    "status" -> result.success(status())
                    "openInputSettings" -> {
                        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        result.success(null)
                    }
                    "showPicker" -> {
                        imm.showInputMethodPicker()
                        result.success(null)
                    }
                    "playAsset" -> result.success(play(call.argument<String>("asset") ?: ""))
                    "stopAudio" -> { stopAudio(); result.success(null) }
                    "openAt" -> { result.success(openAt); openAt = null }
                    "setPlugin" -> {
                        val id = call.argument<String>("id") ?: ""
                        val on = call.argument<Boolean>("on") == true
                        if (!Plugins.set(prefs, id, on)) { result.error("bad_plugin", "Unknown plugin: $id", null); return@setMethodCallHandler }
                        // One-time codes from texts are the clipboard plugin's: without it their receiver is off.
                        if (id == Plugins.CLIPBOARD) SmsCodes.apply(this@MainActivity)
                        // Adding dictation fetches its model and asks for the microphone, in one go.
                        if (id == Plugins.DICTATION && on) {
                            WhisperModel.download(this@MainActivity)
                            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                            }
                        }
                        result.success(prefs.all())
                    }
                    "openAutofillSettings" -> {
                        // There is no public screen for picking the autofill service on every phone: try the
                        // Passwords & accounts screen some open with, then Settings itself.
                        val tries = listOf(
                            Intent("android.settings.AUTOFILL_SETTINGS"),
                            Intent(android.provider.Settings.ACTION_SYNC_SETTINGS),
                            Intent(android.provider.Settings.ACTION_SETTINGS),
                        )
                        for (intent in tries) {
                            if (runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) break
                        }
                        result.success(null)
                    }
                    "connectorsState" -> result.success(Connectors.state(prefs))
                    // Adding one asks its server for its tools at once: what it has is what there is to switch on.
                    "connectorAdd" -> background(result) {
                        val refused = Connectors.add(prefs, call.argument<String>("name") ?: "", call.argument<String>("url") ?: "", call.argument<String>("token") ?: "")
                        if (refused == null) Connectors.all(prefs).lastOrNull()?.let { Connectors.refresh(prefs, it.id) }
                        Connectors.state(prefs) + mapOf("error" to refused)
                    }
                    "connectorRefresh" -> background(result) { Connectors.refresh(prefs, call.argument<String>("id") ?: ""); Connectors.state(prefs) }
                    "connectorRemove" -> { Connectors.remove(prefs, call.argument<String>("id") ?: ""); result.success(Connectors.state(prefs)) }
                    "connectorTool" -> {
                        Connectors.setTool(prefs, call.argument<String>("id") ?: "", call.argument<String>("tool") ?: "", call.argument<Boolean>("on") == true)
                        result.success(Connectors.state(prefs))
                    }
                    "shortcutsState" -> result.success(Shortcuts.state(prefs))
                    "shortcutAdd" -> {
                        val error = Shortcuts.add(prefs, call.argument<String>("shortcut") ?: "", call.argument<String>("text") ?: "")
                        result.success(Shortcuts.state(prefs) + mapOf("error" to error))
                    }
                    "shortcutRemove" -> { Shortcuts.remove(prefs, call.argument<String>("shortcut") ?: ""); result.success(Shortcuts.state(prefs)) }
                    "clipboardState" -> result.success(clipboardState())
                    "clipRemove" -> { ClipHistory.remove(this@MainActivity, call.argument<String>("text") ?: ""); result.success(clipboardState()) }
                    "clipPinAdd" -> {
                        val text = call.argument<String>("text") ?: ""
                        val added = ClipHistory.add(this@MainActivity, text)
                        result.success(clipboardState() + mapOf("error" to if (added) null else if (text.isBlank()) "Write something to pin first." else if (text.trim().length > ClipHistory.MAX_CHARS) "That is too long to pin: ${ClipHistory.MAX_CHARS} characters at most." else "${ClipList.MAX_PINS} clips are pinned already. Remove one first."))
                    }
                    "smsCodesState" -> result.success(smsState())
                    "setSmsCodes" -> {
                        // On: the switch, Android's receiver, and Android's own question where it has not said yes.
                        val on = call.argument<Boolean>("on") == true
                        prefs.set(KeyboardPrefs.SMS_CODES, on)
                        SmsCodes.apply(this@MainActivity)
                        if (on && !SmsCodes.allowed(this@MainActivity)) askForTexts()
                        result.success(smsState())
                    }
                    "askForTexts" -> { askForTexts(); result.success(null) }
                    "openAppInfo" -> {
                        // Android's page for this app: its permissions, and (⋮) "Allow restricted settings".
                        runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        result.success(null)
                    }
                    "clearClipboard" -> { ClipHistory.clear(this@MainActivity); result.success(clipboardState()) }
                    "sampleSound" -> {
                        val pack = call.argument<String>("pack") ?: prefs.stockSound
                        SoundPacks.sample(this@MainActivity, pack, prefs.soundVolume.coerceAtLeast(0.15f))
                        result.success(null)
                    }
                    "localAiState" -> result.success(localAiState())
                    "useLocalModel" -> {
                        // A model that is here is chosen; one that is not is fetched, and chosen once it is here if no
                        // other is in use (LocalModels.download).
                        LocalModels.spec(this@MainActivity, call.argument<String>("id"))?.let { spec ->
                            if (LocalModels.isReady(this@MainActivity, spec)) prefs.set(KeyboardPrefs.LOCAL_MODEL, spec.id) else LocalModels.download(this@MainActivity, spec)
                        }
                        result.success(localAiState())
                    }
                    "cancelLocalModel" -> { LocalModels.cancel(); result.success(localAiState()) }
                    "deleteLocalModel" -> {
                        LocalModels.spec(this@MainActivity, call.argument<String>("id"))?.let { LocalModels.delete(this@MainActivity, it) }
                        result.success(localAiState())
                    }
                    // A model of the person's own, by its Hugging Face link: asked about there, then one of the list.
                    "addLocalModel" -> { LocalModels.add(this@MainActivity, call.argument<String>("link").orEmpty()); result.success(localAiState()) }
                    "removeLocalModel" -> { LocalModels.remove(this@MainActivity, call.argument<String>("id").orEmpty()); result.success(localAiState()) }
                    "dictationState" -> result.success(dictationState())
                    "downloadModel" -> {
                        val id = call.argument<String>("id")
                        WhisperModel.download(this@MainActivity, if (id == null) WhisperModel.active(this@MainActivity) else WhisperModel.spec(id))
                        result.success(null)
                    }
                    "deleteModel" -> {
                        val id = call.argument<String>("id")
                        WhisperModel.delete(this@MainActivity, if (id == null) WhisperModel.active(this@MainActivity) else WhisperModel.spec(id))
                        result.success(null)
                    }
                    "useModel" -> {
                        // Choosing a model downloads it if it is not here yet. With Offline on, a model that is not
                        // here is not chosen: the one in use stays, and the row says why.
                        val spec = WhisperModel.spec(call.argument<String>("id"))
                        if (WhisperModel.isReady(this@MainActivity, spec) || !Net.offline(prefs)) prefs.set(KeyboardPrefs.WHISPER_MODEL, spec.id)
                        WhisperModel.download(this@MainActivity, spec)
                        result.success(dictationState())
                    }
                    "setDictationLanguage" -> {
                        prefs.set(KeyboardPrefs.DICTATION_LANGUAGE, call.argument<String>("code") ?: "auto")
                        result.success(dictationState())
                    }
                    "requestMicrophone" -> {
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
                        result.success(null)
                    }
                    "updateState" -> { Updater.restore(this@MainActivity); result.success(Updater.state.toMap(this@MainActivity)) }
                    "checkUpdate" -> {
                        Updater.check(this@MainActivity, install = call.argument<Boolean>("install") == true)
                        result.success(null)
                    }
                    "installUpdate" -> {
                        if (!packageManager.canRequestPackageInstalls()) {
                            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                        } else {
                            Updater.installNow(this@MainActivity)
                        }
                        result.success(null)
                    }
                    "aiState" -> result.success(aiState())
                    "aiModels" -> background(result) { AiClient.models(prefs) }
                    "aiTest" -> background(result) { AiClient.complete(prefs, NetPath.AI_TEST, "Reply with the single word OK.", "Are you there?", maxTokens = 2000, effort = "low") }
                    // Settings › Offline, and the Market's "It talks to…": the ledger as things stand (Net.kt).
                    "netState" -> result.success(Net.toMap(this@MainActivity, prefs))
                    "mockingbirdTrain" -> Mockingbird.train(this@MainActivity) { error ->
                        runOnUiThread { result.success(aiState() + mapOf("trainError" to error)) }
                    }
                    "ghostSignIn" -> background(result) {
                        val url = GhostOnline.signInUrl(this@MainActivity)
                        runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        null
                    }
                    "ghostSignOut" -> background(result) { GhostOnline.signOut(this@MainActivity); aiState() }
                    "githubCheck" -> background(result) {
                        val token = prefs.githubToken.trim()
                        if (token.isEmpty()) throw GitHubCommits.GitHubError("Paste a GitHub token first")
                        val a = GitHubCommits.account(this@MainActivity, token, refresh = true, path = NetPath.GITHUB_CHECK)
                        mapOf("login" to a.login, "owners" to a.owners)
                    }
                    "profileImport" -> {
                        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream"))
                        runCatching { startActivityForResult(pick, REQUEST_IMPORT) }
                        result.success(null)
                    }
                    "profileExport" -> {
                        exportSamples = call.argument<Boolean>("samples") == true
                        // Word pairs go along unless the switch beside the export was turned off.
                        exportPairs = call.argument<Boolean>("pairs") != false
                        val save = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                            .putExtra(Intent.EXTRA_TITLE, "ghost-typing-profile.json")
                        runCatching { startActivityForResult(save, REQUEST_EXPORT) }
                        result.success(null)
                    }
                    "statsState" -> result.success(TypingStats.state(this@MainActivity))
                    // The Stats page (lib/stats/stats_screen.dart): the counts and the keyboard's pictures at once, then
                    // the words put right most often, which wait on their own thread.
                    "statsPage" -> {
                        val page = statsPage()
                        background(result) { page + mapOf("misspelled" to if (prefs.learnMistakes) Mistakes.get(this@MainActivity).most(8) else emptyList<Map<String, Any>>()) }
                    }
                    "accuracyState" -> result.success(mapOf("count" to AccuracyLog.count(this@MainActivity)))
                    "accuracyExport" -> background(result) { AccuracyLog.export(this@MainActivity) ?: throw IllegalStateException("Nothing recorded yet") }
                    "accuracyClear" -> { AccuracyLog.clear(this@MainActivity); result.success(mapOf("count" to 0)) }
                    "statsReset" -> { TypingStats.reset(this@MainActivity); result.success(TypingStats.state(this@MainActivity)) }
                    "profilesState" -> result.success(AppProfiles.state(prefs))
                    "profileAdd" -> {
                        AppProfiles.add(this@MainActivity, prefs, call.argument<String>("package") ?: "")
                        result.success(AppProfiles.state(prefs))
                    }
                    "profileSet" -> {
                        // "value" is true, false, or absent to give the setting back to Settings.
                        AppProfiles.set(prefs, call.argument<String>("package") ?: "", call.argument<String>("key") ?: "", call.argument<Boolean>("value"))
                        result.success(AppProfiles.state(prefs))
                    }
                    "profileRemove" -> {
                        AppProfiles.remove(prefs, call.argument<String>("package") ?: "")
                        result.success(AppProfiles.state(prefs))
                    }
                    "adaptiveState" -> result.success(adaptiveState())
                    "adaptiveReset" -> { AdaptiveKeys.resetAll(this@MainActivity); result.success(adaptiveState()) }
                    // Settings › Autocorrect › Next word: what has been learned, and forgetting it (NextWords.kt).
                    // Both wait on its background thread, so neither runs on this one.
                    "nextWordsState" -> background(result) { NextWords.get(this@MainActivity).run { awaitLoaded(2_000); awaitIdle(2_000); state() } }
                    "nextWordsReset" -> background(result) { NextWords.get(this@MainActivity).run { reset(); state() } }
                    // Settings › Autocorrect › Your corrections: what was learned from mistakes, and forgetting it (Mistakes.kt).
                    "mistakesState" -> background(result) { Mistakes.get(this@MainActivity).state() }
                    "mistakesReset" -> background(result) { Mistakes.get(this@MainActivity).run { reset(); state() } }
                    // Settings › Typing › Typing stats › Anonymous reports: what is waiting to be sent, exactly as it
                    // would go; discarding it; and sending it now (Reports.kt). None runs on this thread.
                    "reportState" -> background(result) { Reports.state(this@MainActivity) }
                    "reportDiscard" -> background(result) { Reports.discard(this@MainActivity); Reports.awaitIdle(2_000); Reports.state(this@MainActivity) }
                    "reportSend" -> background(result) {
                        val said = java.util.concurrent.atomic.AtomicReference("Not sent")
                        val latch = java.util.concurrent.CountDownLatch(1)
                        Reports.maybeSend(this@MainActivity, prefs, now = true) { said.set(it); latch.countDown() }
                        latch.await(40, java.util.concurrent.TimeUnit.SECONDS)
                        Reports.state(this@MainActivity) + mapOf("said" to said.get())
                    }
                    // Settings › Languages: every language the keyboard has, and the ones it says it does not (Languages.kt).
                    "languagesState" -> result.success(languagesState())
                    "mockingbirdClear" -> { Mockingbird.clear(this@MainActivity); result.success(aiState()) }
                    else -> result.notImplemented()
                }
            }
        }
    }

    /** Runs [work] off the main thread; its value, or its error's message as an "ai" error, goes back to Flutter. */
    private fun background(result: MethodChannel.Result, work: () -> Any?) {
        Thread {
            val r = runCatching(work)
            runOnUiThread {
                r.onSuccess { result.success(it) }.onFailure { result.error("ai", it.message ?: "Something went wrong", null) }
            }
        }.start()
    }

    /** Settings › Adaptive keys: how many letters have learned aims, and how many mistakes it remembers, over every layout. */
    /**
     * What the Stats page draws beside the counts (Matt, 2026-11-11: "a stats page that shows the missed keys and key
     * shifting"): the keyboard of the layout in use as the trainer's pictures have it ([TrainerMaps.board]), from
     * everyday typing. How often each key was meant and missed and the key hit in its place (TypingStats.slip),
     * where the taps that stood land (TypingStats.landed), and the nudge the adaptive keys plugin has each key's
     * touch area at now: none with the plugin off.
     */
    private fun statsPage(): Map<String, Any?> {
        val layout = prefs.layout
        val spec = Layouts.spec(layout)
        val places = spec?.positions.orEmpty()
        val adaptive = Plugins.isOn(prefs, Plugins.ADAPTIVE)
        val model = if (adaptive) AdaptiveKeys.get(this, layout) else null
        val shift: (Char) -> Pair<Float, Float> = { c -> model?.offset(c) ?: (0f to 0f) }
        val board = TrainerMaps.board(
            places = places,
            taps = { c -> TypingStats.tapsOf(this, layout, c) },
            landing = { c -> TypingStats.landingOf(this, layout, c) },
            shift = shift,
            moved = places.keys.filter { shift(it) != (0f to 0f) }.toSet(),
            slips = TypingStats.slipsOf(this, layout),
        )
        return TypingStats.state(this) + mapOf(
            "board" to board, "adaptive" to adaptive, "counting" to prefs.typingStats, "learning" to prefs.learnMistakes, "layout" to (spec?.label ?: layout),
        )
    }

    private fun adaptiveState(): Map<String, Any> {
        val models = AdaptiveKeys.all(this)
        val mistakes = models.flatMap { it.mistakes }
        return mapOf("keys" to models.sumOf { it.learnedKeys() }, "mistakes" to mistakes.size,
            "common" to mistakes.groupingBy { "${it.pressed}→${it.meant}" }.eachCount().entries.sortedByDescending { it.value }.take(5).map { "${it.key} ×${it.value}" })
    }

    /**
     * Settings › Languages: the catalogue. `languages` (code, its own name, its English name, the script, its layouts
     * as id and label with its own first, whether it has a word list, whether next-word prediction has a starter
     * table for it inside the app, and a note), `unavailable` (name, english, why) and `max`, how many may be on at
     * once.
     */
    private fun languagesState(): Map<String, Any?> {
        val tables = runCatching { assets.list("ngram") }.getOrNull().orEmpty().filter { it.endsWith(".tsv") }.map { it.removeSuffix(".tsv") }.toSet()
        return mapOf(
            "languages" to Languages.ALL.map { l ->
                mapOf(
                    "code" to l.code, "name" to l.name, "english" to l.english, "script" to l.script.name.lowercase(),
                    "layouts" to l.layouts.mapNotNull { id -> Layouts.spec(id)?.let { mapOf("id" to it.id, "label" to it.label) } },
                    "dictionary" to (l.dict != null), "nextWords" to (l.dict != null && l.dict in tables), "note" to l.note,
                )
            },
            "unavailable" to Languages.UNAVAILABLE.map { mapOf("name" to it.name, "english" to it.english, "why" to it.why) },
            "max" to Languages.MAX,
        )
    }

    /** Settings › AI and Mockingbird: the providers, whether one is ready, the samples and the style profile. */
    private fun aiState(): Map<String, Any?> {
        val samples = Mockingbird.samples(this)
        val profile = Mockingbird.profile(this)
        return mapOf(
            "providers" to AiClient.PROVIDERS.map { mapOf("id" to it.id, "name" to it.name, "defaultModel" to it.defaultModel, "keyFrom" to it.keyFrom) },
            "ready" to AiClient.ready(prefs),
            "samples" to samples.size,
            "chatSamples" to samples.count { it.chat },
            "writingSamples" to samples.count { !it.chat },
            "profile" to profile?.let { mapOf("text" to it.text, "at" to it.at, "samples" to it.samples, "provider" to it.provider, "model" to it.model, "outdated" to Mockingbird.outdated(it)) },
            "training" to Mockingbird.training,
            "error" to Mockingbird.lastError,
            "ghostInstalled" to (GhostMd.installed(this) != null),
            "ghostOnline" to GhostOnline.signedIn(this),
        )
    }

    private fun status(): Map<String, Boolean> {
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        val selected = ComponentName.unflattenFromString(current)?.packageName == packageName
        return mapOf("enabled" to enabled, "selected" to selected)
    }

    /** Coming back from the system's keyboard list or the picker: tell the settings app to look again. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) channel?.invokeMethod("statusChanged", status())
    }

    override fun onResume() {
        super.onResume()
        channel?.invokeMethod("statusChanged", status())
    }

    companion object {
        /** An extra naming the settings page to open at. */
        const val EXTRA_OPEN = "open"
        private const val REQUEST_MIC = 41
        private const val REQUEST_SMS = 42
        /** Android has been asked for the texts permission at least once. */
        private const val SMS_ASKED = "sms_asked"
        private const val REQUEST_IMPORT = 42
        private const val REQUEST_EXPORT = 43
        private const val STATE_EXPORT_SAMPLES = "export_samples"
        private const val STATE_EXPORT_PAIRS = "export_pairs"
    }
}
